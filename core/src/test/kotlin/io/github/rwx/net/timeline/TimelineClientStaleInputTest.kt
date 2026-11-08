package io.github.rwx.net.timeline

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Actual socket revisions deterministically reproduce a captured-frame/reader advancement race. */
class TimelineClientStaleInputTest {
    private val f = TimelineTestFixture
    private val token = "q".repeat(32)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition()) { check(System.nanoTime() < deadline) { "Stale input fixture timed out" }; Thread.sleep(2) }
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

    @Test fun `captured old control is typed refusal without enqueue or disconnect and fresh input still arrives`() {
        val source = Source()
        server(source).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Control race", token).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val captured = checkNotNull(client.latest(1)); val reachedWriter = AtomicInteger()
                client.beforeCommandWrite = { reachedWriter.incrementAndGet() }
                source.control.set(2); source.time.set(20); server.signal(0)
                await { client.latest(1)?.header?.controlEpoch == 2L }
                assertFailsWith<TimelineStaleInputException> { client.sendCommand(stop(captured, 1)) }
                assertEquals(0, server.pendingRequestCount); assertEquals(0, reachedWriter.get())
                assertTrue(client.isConnected); assertNull(client.failure)
                val fresh = checkNotNull(client.latest(1))
                assertFailsWith<ProtocolException> { client.sendCommand(stop(fresh, 2, control = 3)) }
                val invalid = stop(fresh, 2).let { frame -> frame.copy(body = TimelineRecord(frame.body.fields.map {
                    if (it.id == 6) tn(6, 1, true) else it // MOVE with NONE target remains a schema error.
                })) }
                assertFailsWith<ProtocolException> { client.sendCommand(invalid) }
                client.sendCommand(stop(fresh, 2))
                val delivered = AtomicReference<TimelineRequest?>()
                await { server.drainRequests { delivered.set(it) }; delivered.get() != null }
                assertEquals(2L, delivered.get()!!.frame.body.number(3)); assertEquals(1, reachedWriter.get())
                assertTrue(client.isConnected); assertEquals(0L, server.peerClosureStatistics().closedAfterHello)
            }
        }
    }

    @Test fun `old LIVE epoch refuses command and selection while HISTORY remains a protocol error`() {
        val source = Source()
        server(source).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Epoch race", token).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val captured = checkNotNull(client.latest(1)); source.resetNext.set(true); source.time.set(20); server.signal(0)
                await { (client.latest(1)?.header?.epoch ?: 0) > captured.header.epoch }
                val reachedWriter = AtomicInteger(); client.beforeCommandWrite = { reachedWriter.incrementAndGet() }
                assertFailsWith<TimelineStaleInputException> { client.sendCommand(stop(captured, 1)) }
                assertFailsWith<TimelineStaleInputException> { client.select(select(captured, 1)) }
                val fresh = checkNotNull(client.latest(1))
                val futureEpoch = fresh.copy(header = fresh.header.copy(epoch = fresh.header.epoch + 1))
                assertFailsWith<ProtocolException> { client.sendCommand(stop(futureEpoch, 2)) }
                val historyEnded = AtomicBoolean()
                client.observeFrames { if (it.type == 112 && it.body.number(1) == 2L && it.body.number(3) == 2L) historyEnded.set(true) }.use {
                    client.openTimeline(2, TimelineStreamMode.HISTORY, 0, 5)
                    await { client.latest(2) != null }
                    val history = checkNotNull(client.latest(2))
                    assertFailsWith<ProtocolException> { client.select(select(history, 2)) }
                    assertFailsWith<ProtocolException> { client.sendCommand(stop(history, 2, control = 1)) }
                    assertEquals(0, reachedWriter.get()); assertEquals(0, server.pendingRequestCount)
                    await { historyEnded.get() }
                }
                source.time.set(30); server.signal(0)
                await { client.latest(1)?.header?.observedThrough == 30L }
                client.select(select(checkNotNull(client.latest(1)), 2))
                val delivered = AtomicReference<TimelineRequest?>()
                await { server.drainRequests { delivered.set(it) }; delivered.get() != null }
                assertEquals(6, delivered.get()!!.frame.type); assertEquals(1, reachedWriter.get())
                assertTrue(client.isConnected); assertNull(client.failure)
            }
        }
    }
}
