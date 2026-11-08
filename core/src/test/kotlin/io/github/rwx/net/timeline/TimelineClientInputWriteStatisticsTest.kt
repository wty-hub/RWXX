package io.github.rwx.net.timeline

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real writer/receiver integration; bounded barriers distinguish queueing, writing and acceptance. */
class TimelineClientInputWriteStatisticsTest {
    private val f = TimelineTestFixture
    private val token = "q".repeat(32)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition()) { check(System.nanoTime() < deadline) { "Input writer fixture timed out" }; Thread.sleep(2) }
    }
    private inner class Source : TimelineFeedFactory {
        val time = AtomicLong(10); val control = AtomicLong(1); val resetNext = AtomicBoolean()
        override fun open(request: TimelineStreamRequest): TimelineFeed {
            // HISTORY is one fixed requested window, not a second LIVE observer of this clock.
            val historyObserved = time.get()
            return object : TimelineFeed {
            override fun next(cursor: TimelineSendCursor): TimelinePublication? {
                if (request.context.mode == TimelineStreamMode.HISTORY && !cursor.reset) return null
                if (request.context.mode == TimelineStreamMode.LIVE && !cursor.reset && resetNext.compareAndSet(true, false)) throw TimelineResetRequired()
                val old = cursor.previous
                val observed = if (request.context.mode == TimelineStreamMode.HISTORY) historyObserved else time.get()
                val nextControl = if (request.context.mode == TimelineStreamMode.HISTORY) 0 else
                    if (cursor.reset) (old?.header?.controlEpoch ?: 0) + 1 else maxOf(control.get(), checkNotNull(old).header.controlEpoch)
                if (!cursor.reset && old!!.header.observedThrough == observed && old.header.controlEpoch == nextControl) return null
                val from = request.context.acceptedFrom ?: request.context.historyFrom
                val operations = if (cursor.reset) f.root(from = from) + f.unit(from = from) else emptyList()
                val header = f.header(operations, old, observed = observed, sealed = request.context.acceptedUntil ?: from,
                    from = from, until = request.context.acceptedUntil ?: observed + 1, control = nextControl,
                    reset = cursor.reset, epoch = cursor.epoch).copy(streamId = request.streamId)
                val formats = old?.objects?.values?.flatMap { obj -> obj.curves.values.map { TimelineCurveKey(obj.handle, it.attributeId) to it.format } }?.toMap()
                    ?.takeUnless { cursor.reset } ?: emptyMap()
                return TimelinePublication(header, TimelineBinary.encodeOperations(operations, formats))
            }
            }
        }
    }
    private fun server(source: Source) = TimelineServer(InetAddress.getLoopbackAddress(), 0,
        mapOf(token to TimelineSeat(0, TimelineBytes(ByteArray(16)))), f.map(), TimelineBytes(ByteArray(16)),
        source, serverTime = { 100 }, historyUntil = { 100 })
    private fun stop(state: TimelineState, sequence: Long, control: Long = state.header.controlEpoch) = timelineFrame(5,
        tn(1, state.header.streamId, true), tn(2, state.header.epoch), tn(3, sequence), tn(4, state.header.revision),
        tn(5, control), tn(6, 3, true), tb(7, f.compact(1, 1)), tn(8, 0),
        TimelineMessages.recordField(9, TimelineRecord(listOf(tn(1, 0, true)))), tn(10, 0, true))
    private fun select(state: TimelineState, sequence: Long) = timelineFrame(6,
        tn(1, state.header.streamId, true), tn(2, state.header.epoch), tn(3, sequence), tn(4, state.header.revision), tb(5, f.compact(1, 1)))

    @Test fun `successful complete Command and Select writes are counted separately from checks and immutable snapshots`() {
        val source = Source()
        server(source).use { server ->
            server.checkIntervalMillis = 30
            TimelineClient("127.0.0.1", server.localPort, "Written input", token).use { client ->
                client.openTimeline(1); await { client.latest(1) != null && client.wireStatistics().stateAcks > 0 }
                val before = client.inputWriteStatistics()
                assertEquals(TimelineInputWriteStatistics(0, 0), before)
                val current = checkNotNull(client.latest(1))
                client.sendCommand(stop(current, 1)); client.select(select(current, 1))
                val received = ArrayList<TimelineRequest>()
                await { server.drainRequests { received.add(it) }; received.size == 2 &&
                    client.inputWriteStatistics() == TimelineInputWriteStatistics(1, 1) }
                assertEquals(listOf(5, 6), received.map { it.frame.type })
                assertEquals(TimelineInputWriteStatistics(0, 0), before)
                val fresh = client.inputWriteStatistics()
                assertEquals(TimelineInputWriteStatistics(1, 1), fresh)
                assertTrue(client.wireStatistics().stateAcks > 0)
                assertTrue(client.isConnected); assertNull(client.failure)
            }
        }
    }

    @Test fun `a queued Command revoked before the writer boundary never increments successful writes`() {
        val source = Source()
        server(source).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Queued input", token).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val taken = CountDownLatch(1); val resume = CountDownLatch(1); val once = AtomicBoolean()
                client.beforeCommandWrite = {
                    if (once.compareAndSet(false, true)) { taken.countDown(); check(resume.await(4, TimeUnit.SECONDS)) }
                }
                try {
                    val old = checkNotNull(client.latest(1)); client.sendCommand(stop(old, 1))
                    assertTrue(taken.await(3, TimeUnit.SECONDS))
                    assertEquals(TimelineInputWriteStatistics(0, 0), client.inputWriteStatistics())
                    source.time.set(20); source.control.set(2); server.signal(0)
                    await { client.latest(1)?.header?.controlEpoch == 2L }
                    resume.countDown()
                    client.select(select(checkNotNull(client.latest(1)), 1))
                    val received = ArrayList<TimelineRequest>()
                    await { server.drainRequests { received.add(it) }; received.isNotEmpty() &&
                        client.inputWriteStatistics().selectionsWritten == 1L }
                    assertEquals(listOf(6), received.map { it.frame.type })
                    assertEquals(TimelineInputWriteStatistics(0, 1), client.inputWriteStatistics())
                    assertTrue(client.isConnected); assertNull(client.failure)
                } finally { resume.countDown() }
            }
        }
    }

    @Test fun `failed actual buffered flush is not reported as a complete Command write`() {
        server(Source()).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Failed writer", token).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val attempts = AtomicInteger()
                client.beforeCommandWrite = {
                    // Fault only the existing writer's output on its own thread. The real client,
                    // framing, buffered flush and failure path run; no socket-close/read race.
                    val field = TimelineClient::class.java.getDeclaredField("wire").apply { isAccessible = true }
                    val wire = field.get(client) as TimelineSocket
                    val output = TimelineSocket::class.java.getDeclaredField("output").apply { isAccessible = true }
                    output.set(wire, BufferedOutputStream(object : OutputStream() {
                        override fun write(value: Int) { attempts.incrementAndGet(); throw IOException("Injected flush failure") }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            attempts.incrementAndGet(); throw IOException("Injected flush failure")
                        }
                    }))
                }
                client.sendCommand(stop(checkNotNull(client.latest(1)), 1))
                await { !client.isConnected && client.failure != null }
                assertEquals(1, attempts.get(), "The actual frame reached its failing output flush")
                assertEquals("Injected flush failure", client.failure?.message)
                assertEquals(TimelineInputWriteStatistics(0, 0), client.inputWriteStatistics())
                assertEquals(0, server.pendingRequestCount)
            }
        }
    }

    @Test fun `HISTORY input rejection and stale enqueue do not count while healthy LIVE writes still do`() {
        val source = Source()
        server(source).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Readonly input", token).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val old = checkNotNull(client.latest(1))
                source.control.set(2); source.time.set(20); server.signal(0)
                await { client.latest(1)?.header?.controlEpoch == 2L }
                assertFailsWith<TimelineStaleInputException> { client.sendCommand(stop(old, 1)) }
                val ended = AtomicBoolean()
                client.observeFrames { if (it.type == 112 && it.body.number(1) == 2L && it.body.number(3) == 2L) ended.set(true) }.use {
                    client.openTimeline(2, TimelineStreamMode.HISTORY, 0, 5)
                    await { client.latest(2) != null }
                    val history = checkNotNull(client.latest(2))
                    assertFailsWith<ProtocolException> { client.select(select(history, 1)) }
                    assertFailsWith<ProtocolException> { client.sendCommand(stop(history, 1, control = 1)) }
                    await { ended.get() }
                }
                assertEquals(TimelineInputWriteStatistics(0, 0), client.inputWriteStatistics())
                assertEquals(0, server.pendingRequestCount)
                client.sendCommand(stop(checkNotNull(client.latest(1)), 2))
                await { server.pendingRequestCount == 1 && client.inputWriteStatistics().commandsWritten == 1L }
                assertEquals(TimelineInputWriteStatistics(1, 0), client.inputWriteStatistics())
                assertTrue(client.isConnected); assertNull(client.failure)
            }
        }
    }
}
