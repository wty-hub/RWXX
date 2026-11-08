package io.github.rwx.net.timeline

import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TimelineRecoveryTransportTest {
    private val f = TimelineTestFixture

    @Test fun `Begin and partial chunks never publish an incomplete library`() {
        fake({ peer, proceed, release ->
            peer.handshake(); peer.open()
            val p = publication()
            peer.status(p.header); peer.send(p.header.beginFrame())
            val bytes = p.operations.toByteArray(); val half = bytes.size / 2
            peer.send(chunk(p.header, 0, TimelineBytes(bytes.copyOfRange(0, half))))
            proceed.countDown(); check(release.await(4, TimeUnit.SECONDS))
            peer.send(chunk(p.header, half.toLong(), TimelineBytes(bytes.copyOfRange(half, bytes.size))))
            peer.send(commit(p.header)); peer.done.countDown()
        }) { client, proceed, release, done ->
            client.openTimeline(1); assertTrue(proceed.await(3, TimeUnit.SECONDS))
            assertNull(client.latest(1)); assertTrue(client.isSynchronizing)
            release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS)); await { client.latest(1) != null }
            assertEquals(1L, client.wireStatistics().transactions)
        }
    }

    @Test fun `wrong complete digest revokes before ACK and restores only by a new epoch`() {
        fake({ peer, proceed, release ->
            peer.handshake(); peer.open(); val p = publication(); peer.status(p.header); peer.publish(p)
            check(proceed.await(4, TimeUnit.SECONDS))
            peer.send(check(p.header, 1, TimelineBytes(ByteArray(16))))
            val ack = peer.receive()
            assertEquals(8, ack.type); assertEquals(0L, ack.body.number(5))
            val store = TimelineStore(f.context()); val expected = store.apply(p.header, p.operations)
            assertEquals(TimelineCanonical.digest(expected), ack.body.bytes(6))
            peer.done.countDown(); check(release.await(4, TimeUnit.SECONDS))
            val restored = publication(epoch = 2, control = 2); peer.publish(restored)
            peer.send(check(p.header, 2, TimelineBytes(ByteArray(16)))) // Late old epoch is ignored.
            val newState = store.apply(restored.header, restored.operations)
            peer.send(check(restored.header, 3, TimelineCanonical.digest(newState)))
            val accepted = peer.receive()
            assertEquals(8, accepted.type); assertEquals(2L, accepted.body.number(2)); assertEquals(3L, accepted.body.number(4))
        }) { client, proceed, release, done ->
            client.openTimeline(1); await { client.latest(1) != null }; proceed.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS)); assertNull(client.latest(1)); assertTrue(client.isSynchronizing)
            release.countDown(); await { client.latest(1)?.header?.epoch == 2L && client.wireStatistics().stateAcks == 2L }
            assertEquals(1L, client.wireStatistics().stateMismatches)
            assertEquals(0L, client.wireStatistics().restores, "Bad actual ACK is the single implicit restore request")
        }
    }

    @Test fun `missing base consumes in flight transactions and checks without duplicate Restore`() {
        fake({ peer, proceed, release ->
            peer.handshake(); peer.open(); val initial = publication(); peer.status(initial.header); peer.publish(initial)
            check(proceed.await(4, TimeUnit.SECONDS))
            val empty = TimelineBinary.encodeOperations(emptyList())
            val lost = initial.header.copy(kind = TimelineTransactionKind.UPDATE, revision = 4, baseRevision = 3,
                operationBytes = empty.size.toLong(), operationSha256 = TimelineCanonical.sha256(empty))
            peer.publish(TimelinePublication(lost, empty))
            val request = peer.receive(); assertEquals(9, request.type); assertEquals(1L, request.body.number(3))
            peer.send(check(lost, 1, TimelineBytes(ByteArray(16))))
            val unavailable = peer.receive(); assertEquals(8, unavailable.type); assertEquals(1L, unavailable.body.number(5)); assertEquals(0, unavailable.body.bytes(6).size)
            val inFlight = lost.copy(revision = 5, baseRevision = 4)
            peer.publish(TimelinePublication(inFlight, empty))
            peer.done.countDown(); check(release.await(4, TimeUnit.SECONDS))
            val restored = publication(epoch = 2, control = 2); peer.publish(restored)
            val store = TimelineStore(f.context()); store.apply(initial.header, initial.operations)
            val state = store.apply(restored.header, restored.operations)
            peer.send(check(restored.header, 2, TimelineCanonical.digest(state)))
            assertEquals(8, peer.receive().type, "No second explicit restore is queued behind the first")
        }) { client, proceed, release, done ->
            client.openTimeline(1); await { client.latest(1) != null }; proceed.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS)); assertNull(client.latest(1)); assertEquals(1L, client.wireStatistics().restores)
            release.countDown(); await { client.latest(1)?.header?.epoch == 2L && client.wireStatistics().stateAcks == 2L }
            assertTrue(client.isConnected)
        }
    }

    @Test fun `malformed operation is fatal even when its base is missing`() {
        fake({ peer, proceed, _ ->
            peer.handshake(); peer.open(); val p = publication(); peer.status(p.header); peer.publish(p)
            check(proceed.await(4, TimeUnit.SECONDS))
            val invalid = TimelineBytes(byteArrayOf(1, 99, 0))
            peer.publish(TimelinePublication(p.header.copy(kind = TimelineTransactionKind.UPDATE, revision = 4, baseRevision = 3,
                operationBytes = invalid.size.toLong(), operationSha256 = TimelineCanonical.sha256(invalid)), invalid))
            assertFailsWith<EOFException> { peer.receive() }
        }) { client, proceed, _, _ ->
            client.openTimeline(1); await { client.latest(1) != null }; proceed.countDown()
            await { !client.isConnected }; assertNotNull(client.failure)
        }
    }

    @Test fun `cancellation at a frame boundary discards staged history without harming LIVE`() {
        fake({ peer, proceed, release ->
            peer.handshake(); peer.open(); val live = publication(); peer.status(live.header); peer.publish(live)
            val historyOpen = peer.receive(); assertEquals(3, historyOpen.type)
            val history = publication().let { p -> TimelinePublication(p.header.copy(streamId = 2, controlEpoch = 0,
                retainedUntil = 5, sealedThrough = 5), p.operations) }
            peer.status(history.header, request = 2, mode = 2); peer.send(history.header.beginFrame())
            val bytes = history.operations.toByteArray()
            peer.send(chunk(history.header, 0, TimelineBytes(bytes.copyOfRange(0, bytes.size / 2))))
            proceed.countDown()
            val close = peer.receive(); assertEquals(4, close.type); assertEquals(2L, close.body.number(1))
            peer.send(timelineFrame(112, tn(1, 2, true), tn(2, 1), tn(3, 1, true), tn(4, 0), tn(5, 10)))
            check(release.await(4, TimeUnit.SECONDS))
            val empty = TimelineBinary.encodeOperations(emptyList())
            val next = live.header.copy(kind = TimelineTransactionKind.UPDATE, revision = 2, baseRevision = 1,
                observedThrough = 20, retainedUntil = 21, operationBytes = empty.size.toLong(), operationSha256 = TimelineCanonical.sha256(empty))
            peer.publish(TimelinePublication(next, empty)); peer.done.countDown()
        }) { client, proceed, release, done ->
            client.openTimeline(1); await { client.latest(1) != null }
            client.openTimeline(2, TimelineStreamMode.HISTORY, 0, 5)
            assertTrue(proceed.await(3, TimeUnit.SECONDS)); assertNull(client.latest(2))
            client.closeTimeline(2); release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS)); await { client.latest(1)?.header?.revision == 2L }
            assertNull(client.latest(2)); assertTrue(client.isConnected)
        }
    }

    @Test fun `incomplete transaction absolute deadline is not renewed by Pong`() {
        fake({ peer, proceed, _ ->
            peer.handshake(); peer.open(); val p = publication(); peer.status(p.header); peer.send(p.header.beginFrame())
            proceed.countDown()
            while (true) {
                try {
                    val ping = TimelineBinary.readFrame(peer.socket.getInputStream())
                    assertEquals(7, ping.type)
                    peer.send(timelineFrame(111, tn(1, ping.body.number(1)), tn(2, ping.body.number(2)), tn(3, 10)))
                } catch (_: IOException) { break }
            }
        }) { client, proceed, _, _ ->
            client.timeoutMillis = 150; client.heartbeatMillis = 20; client.openTimeline(1)
            assertTrue(proceed.await(3, TimeUnit.SECONDS)); await { !client.isConnected }
            assertNull(client.latest(1)); assertTrue(client.failure?.message.orEmpty().contains("deadline"))
        }
    }

    @Test fun `production first check is delayed one second and frozen state still gets later checks`() {
        realServer().use { server ->
            Raw(server.localPort).use { raw ->
                val began = System.nanoTime(); raw.initialize()
                val check1 = raw.nextCheck()
                assertTrue(System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(800))
                raw.ack(check1)
                val check2 = raw.nextCheck()
                assertEquals(check1.body.number(3), check2.body.number(3))
                assertTrue(check2.body.number(4) > check1.body.number(4)); raw.ack(check2)
            }
        }
    }

    @Test fun `bad ACK creates new epoch late old ACK is harmless and fourth persistent failure closes`() {
        realServer().use { server ->
            server.checkIntervalMillis = 30
            Raw(server.localPort).use { raw ->
                raw.initialize()
                var check = raw.nextCheck()
                repeat(3) { index ->
                    val old = check
                    raw.ack(check, TimelineBytes(ByteArray(16)))
                    raw.untilCommit(index + 2L)
                    raw.ack(old, TimelineBytes(ByteArray(16)))
                    check = raw.nextCheck(minEpoch = index + 2L)
                }
                raw.ack(check, TimelineBytes(ByteArray(16)))
                assertFailsWith<EOFException> { while (true) raw.receive() }
            }
        }
    }

    @Test fun `confirmation deadline survives Ping and recovery RESET`() {
        realServer().use { server ->
            server.timeoutMillis = 250; server.checkIntervalMillis = 30
            Raw(server.localPort).use { raw ->
                raw.initialize(); val check = raw.nextCheck()
                raw.send(timelineFrame(9, tn(1, 1, true), tn(2, 1), tn(3, 1), tn(4, 2, true)))
                var restored = false
                val pumping = java.util.concurrent.atomic.AtomicBoolean(true)
                val pump = Thread {
                    var nonce = 1L
                    while (pumping.get()) {
                        try { raw.send(timelineFrame(7, tn(1, nonce++), tn(2, 0))); Thread.sleep(25) }
                        catch (_: IOException) { break }
                    }
                }.apply { isDaemon = true; start() }
                val started = System.nanoTime()
                try {
                    while (true) try {
                        val frame = raw.receive()
                        if (frame.type == 108 && frame.body.number(2) == 2L) restored = true
                    } catch (error: IOException) {
                        if (error is EOFException || error is java.net.SocketException) break else throw error
                    }
                } finally { pumping.set(false); raw.close(); pump.join(2000) }
                assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(180))
                assertTrue(restored)
                assertEquals(1L, check.body.number(3))
            }
        }
    }

    private fun publication(epoch: Long = 1, control: Long = 1): TimelinePublication {
        val ops = f.root() + f.unit()
        val bytes = TimelineBinary.encodeOperations(ops)
        return TimelinePublication(f.header(ops, epoch = epoch, control = control), bytes)
    }
    private fun chunk(h: TimelineHeader, offset: Long, bytes: TimelineBytes) = timelineFrame(107,
        tn(1, h.streamId, true), tn(2, h.epoch), tn(3, h.revision), tn(4, offset), tb(5, bytes))
    private fun commit(h: TimelineHeader) = timelineFrame(108, tn(1, h.streamId, true), tn(2, h.epoch), tn(3, h.revision))
    private fun check(h: TimelineHeader, id: Long, digest: TimelineBytes) = timelineFrame(109,
        tn(1, h.streamId, true), tn(2, h.epoch), tn(3, h.revision), tn(4, id), tb(5, digest))

    private inner class Fake(val socket: Socket) : AutoCloseable {
        val done = CountDownLatch(1)
        fun send(frame: TimelineFrame) { TimelineBinary.writeFrame(socket.getOutputStream(), frame); socket.getOutputStream().flush() }
        fun receive(): TimelineFrame {
            while (true) {
                val frame = TimelineBinary.readFrame(socket.getInputStream()); TimelineMessages.validate(frame, true)
                if (frame.type != 7) return frame
                send(timelineFrame(111, tn(1, frame.body.number(1)), tn(2, frame.body.number(2)), tn(3, 10)))
            }
        }
        fun handshake() {
            assertEquals(1, receive().type)
            send(timelineFrame(101, tn(1, 1, true), tn(2, 0, true), tn(3, 60, true), tn(4, 1024, true),
                tb(5, TimelineBytes(ByteArray(16))), tb(6, TimelineBytes(ByteArray(16))), tn(7, 10), tn(8, 0), tn(9, 61440),
                tn(10, 30, true), tn(11, 100, true), tb(12, TimelineBytes(ByteArray(16) { 1 })), tn(13, 0), tn(14, 1, true)))
            val map = TimelineBinary.encodeRecord(f.map()); val key = TimelineCanonical.mapKey(f.map())
            send(timelineFrame(103, tb(1, key), tn(2, map.size.toLong())))
            assertEquals(2, receive().type)
            send(timelineFrame(104, tb(1, key), tn(2, 0), tb(3, map))); send(timelineFrame(105, tb(1, key)))
        }
        fun open() { assertEquals(3, receive().type) }
        fun status(h: TimelineHeader, request: Long = 1, mode: Long = 1) = send(timelineFrame(110,
            tn(1, request, true), tn(2, 0, true), tn(3, h.streamId, true), tn(4, mode, true), tn(5, h.retainedFrom),
            tn(6, h.retainedUntil), tn(7, 1_048_576, true), tn(8, 0), tn(9, 10)))
        fun publish(p: TimelinePublication) { send(p.header.beginFrame()); send(chunk(p.header, 0, p.operations)); send(commit(p.header)) }
        override fun close() = socket.close()
    }

    private fun fake(server: (Fake, CountDownLatch, CountDownLatch) -> Unit,
                     test: (TimelineClient, CountDownLatch, CountDownLatch, CountDownLatch) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val proceed = CountDownLatch(1); val release = CountDownLatch(1); val finish = CountDownLatch(1)
            val ready = CountDownLatch(1); val peer = AtomicReference<Fake>(); val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                try { Fake(listener.accept().apply { soTimeout = 5000 }).use { p ->
                    peer.set(p); ready.countDown(); server(p, proceed, release); check(finish.await(5, TimeUnit.SECONDS))
                } } catch (error: Throwable) { failure.set(error); ready.countDown() }
            }.apply { isDaemon = true; start() }
            try {
                TimelineClient("127.0.0.1", listener.localPort, "Tester", TOKEN).use { client ->
                    assertTrue(ready.await(3, TimeUnit.SECONDS)); test(client, proceed, release, peer.get().done)
                }
            } finally { proceed.countDown(); release.countDown(); finish.countDown(); thread.join(6000) }
            assertFalse(thread.isAlive); failure.get()?.let { throw AssertionError("Fake timeline server failed", it) }
        }
    }

    private fun realServer(): TimelineServer {
        val source = TimelineFeedFactory { request -> object : TimelineFeed {
            override fun next(cursor: TimelineSendCursor): TimelinePublication? = if (!cursor.reset) null else
                publication(cursor.epoch, (cursor.previous?.header?.controlEpoch ?: 0) + 1).let {
                    it.copy(header = it.header.copy(streamId = request.streamId))
                }
        } }
        return TimelineServer(InetAddress.getByName("127.0.0.1"), 0,
            mapOf(TOKEN to TimelineSeat(0, TimelineBytes(ByteArray(16) { 1 }))), f.map(), TimelineBytes(ByteArray(16)), source,
            serverTime = { 10 }, historyUntil = { 10 })
    }
    private class Raw(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 4000 }
        fun send(frame: TimelineFrame) { TimelineBinary.writeFrame(socket.getOutputStream(), frame); socket.getOutputStream().flush() }
        fun receive() = TimelineBinary.readFrame(socket.getInputStream()).also { TimelineMessages.validate(it, false) }
        fun initialize() {
            send(timelineFrame(1, tn(1, 1, true), TimelineMessages.textField(2, "Tester"), TimelineMessages.textField(3, TOKEN)))
            assertEquals(101, receive().type); val offer = receive(); assertEquals(103, offer.type)
            send(timelineFrame(2, tb(1, offer.body.bytes(1)), TimelineMessages.boolField(2, false)))
            while (receive().type != 105) { }
            send(timelineFrame(3, tn(1, 1, true), tn(2, 1, true), tn(3, 0), tn(4, 0), tn(5, 16_777_216, true)))
            untilCommit(1)
        }
        fun untilCommit(epoch: Long) { while (true) { val frame = receive(); if (frame.type == 108 && frame.body.number(2) == epoch) return } }
        fun nextCheck(minEpoch: Long = 1): TimelineFrame {
            while (true) { val frame = receive(); if (frame.type == 109 && frame.body.number(2) >= minEpoch) return frame }
        }
        fun ack(check: TimelineFrame, digest: TimelineBytes = check.body.bytes(5)) = send(timelineFrame(8,
            tn(1, check.body.number(1), true), tn(2, check.body.number(2)), tn(3, check.body.number(3)), tn(4, check.body.number(4)), tn(5, 0, true), tb(6, digest)))
        override fun close() = socket.close()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition()) { check(System.nanoTime() < deadline) { "Timeline recovery timed out" }; Thread.sleep(3) }
    }
    private companion object { val TOKEN = "c".repeat(32) }
}
