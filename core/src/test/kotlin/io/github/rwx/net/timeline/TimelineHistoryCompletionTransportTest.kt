package io.github.rwx.net.timeline

import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real framed sockets bind HISTORY completion to the exact last verified committed library. */
class TimelineHistoryCompletionTransportTest {
    private val f = TimelineTestFixture
    private val context = f.context().copy(mode = TimelineStreamMode.HISTORY, acceptedFrom = 0, acceptedUntil = 5)

    private fun publication(store: TimelineStore, reset: Boolean): TimelinePublication {
        val ops = if (reset) f.root() + f.unit() else emptyList()
        val h = f.header(ops, store.current, observed = 10, sealed = 5, until = 5, control = 0, reset = reset)
        val bytes = TimelineBinary.encodeOperations(ops)
        store.apply(h, bytes) // Genuine Profile/Store-validated source, including fixed HISTORY bounds.
        return TimelinePublication(h, bytes)
    }
    private fun end(h: TimelineHeader, until: Long = 10) = timelineFrame(112,
        tn(1, h.streamId, true), tn(2, h.epoch), tn(3, 2, true), tn(4, h.retainedFrom), tn(5, until))
    private fun chunk(h: TimelineHeader, bytes: TimelineBytes) = timelineFrame(107,
        tn(1, h.streamId, true), tn(2, h.epoch), tn(3, h.revision), tn(4, 0), tb(5, bytes))
    private fun check(h: TimelineHeader, id: Long, digest: TimelineBytes) = timelineFrame(109,
        tn(1, h.streamId, true), tn(2, h.epoch), tn(3, h.revision), tn(4, id), tb(5, digest))

    private fun rejectsUncheckedReplacement(reset: Boolean) {
        fake({ peer, proceed, _ ->
            peer.handshake(); peer.open()
            val store = TimelineStore(context); val old = publication(store, reset = true)
            peer.status(old.header); peer.publish(old); peer.verify(store.current!!, 1)
            check(proceed.await(4, TimeUnit.SECONDS))
            val next = publication(store, reset)
            peer.publish(next); peer.send(end(next.header))
            assertFailsWith<EOFException> { peer.receive() }
        }) { client, proceed, _, _ ->
            client.openTimeline(1, TimelineStreamMode.HISTORY, 0, 5)
            await { client.latest(1) != null && client.wireStatistics().stateAcks == 1L }
            assertNull(client.completedHistory(1)); proceed.countDown()
            await { !client.isConnected }
            assertNotNull(client.failure)
            assertTrue(client.failure!!.message!!.contains("verified current complete state"))
            assertNull(client.completedHistory(1))
        }
    }

    @Test fun `old successful check cannot authorize End after new same-epoch revision`() = rejectsUncheckedReplacement(false)

    @Test fun `old successful check cannot authorize End after replacement RESET epoch`() = rejectsUncheckedReplacement(true)

    @Test fun `checking the replacement revision or epoch permits only its exact completion receipt`() {
        for (reset in listOf(false, true)) fake({ peer, proceed, _ ->
            peer.handshake(); peer.open()
            val store = TimelineStore(context); val old = publication(store, true)
            peer.status(old.header); peer.publish(old); peer.verify(store.current!!, 1)
            check(proceed.await(4, TimeUnit.SECONDS))
            val next = publication(store, reset)
            peer.publish(next); peer.verify(store.current!!, 2); peer.send(end(next.header)); peer.done.countDown()
        }) { client, proceed, _, done ->
            client.openTimeline(1, TimelineStreamMode.HISTORY, 0, 5)
            await { client.wireStatistics().stateAcks == 1L }
            assertNull(client.completedHistory(1)); proceed.countDown()
            assertTrue(done.await(4, TimeUnit.SECONDS)); await { client.completedHistory(1) != null }
            val receipt = assertNotNull(client.completedHistory(1)); val state = assertNotNull(client.latest(1))
            assertEquals(TimelineHistoryEndReceipt(1, if (reset) 2 else 1, if (reset) 1 else 2, 0, 5), receipt)
            assertEquals(state.header.epoch, receipt.epoch); assertEquals(state.header.revision, receipt.revision)
            assertEquals(2L, client.wireStatistics().stateAcks)
            assertTrue(client.isConnected); assertNull(client.failure)
        }
    }

    @Test fun `ignored old-epoch End is not delivered to observers or mistaken for new completion`() {
        fake({ peer, proceed, _ ->
            peer.handshake(); peer.open()
            val store = TimelineStore(context); val old = publication(store, true)
            peer.status(old.header); peer.publish(old); peer.verify(store.current!!, 1)
            check(proceed.await(4, TimeUnit.SECONDS))
            val next = publication(store, true); peer.publish(next)
            peer.send(end(old.header))
            peer.verify(store.current!!, 2); peer.send(end(next.header)); peer.done.countDown()
        }) { client, proceed, _, done ->
            val ends = CopyOnWriteArrayList<TimelineFrame>()
            client.observeFrames { if (it.type == 112) ends.add(it) }.use {
                client.openTimeline(1, TimelineStreamMode.HISTORY, 0, 5)
                await { client.wireStatistics().stateAcks == 1L }; proceed.countDown()
                assertTrue(done.await(4, TimeUnit.SECONDS))
                await { client.completedHistory(1) != null && ends.isNotEmpty() }
                assertEquals(listOf(2L), ends.map { it.body.number(2) })
                assertEquals(2L, client.completedHistory(1)?.epoch)
                assertTrue(client.isConnected); assertNull(client.failure)
            }
        }
    }

    @Test fun `digest mismatch and restore cannot retain an earlier successful verification`() {
        fake({ peer, proceed, _ ->
            peer.handshake(); peer.open()
            val store = TimelineStore(context); val old = publication(store, true)
            peer.status(old.header); peer.publish(old); peer.verify(store.current!!, 1)
            check(proceed.await(4, TimeUnit.SECONDS))
            peer.send(check(old.header, 2, TimelineBytes(ByteArray(16))))
            val actual = peer.receive(); assertEquals(8, actual.type)
            assertEquals(TimelineCanonical.digest(store.current!!), actual.body.bytes(6))
            val restored = publication(store, true); peer.publish(restored); peer.send(end(restored.header))
            assertFailsWith<EOFException> { peer.receive() }
        }) { client, proceed, _, _ ->
            client.openTimeline(1, TimelineStreamMode.HISTORY, 0, 5)
            await { client.wireStatistics().stateAcks == 1L }; proceed.countDown()
            await { !client.isConnected }
            assertEquals(1L, client.wireStatistics().stateMismatches)
            assertNotNull(client.failure); assertNull(client.completedHistory(1))
        }
    }

    @Test fun `End global archive availability may exceed the accepted HISTORY window`() {
        fake({ peer, proceed, _ ->
            peer.handshake(); peer.open()
            val store = TimelineStore(context); val current = publication(store, true)
            peer.status(current.header); peer.publish(current); peer.verify(store.current!!, 1)
            check(proceed.await(4, TimeUnit.SECONDS))
            peer.send(end(current.header, until = 100)); peer.done.countDown()
        }) { client, proceed, _, done ->
            client.openTimeline(1, TimelineStreamMode.HISTORY, 0, 5)
            await { client.wireStatistics().stateAcks == 1L }; proceed.countDown()
            assertTrue(done.await(4, TimeUnit.SECONDS)); await { client.completedHistory(1) != null }
            assertEquals(TimelineHistoryEndReceipt(1, 1, 1, 0, 5), client.completedHistory(1))
            assertTrue(client.isConnected); assertNull(client.failure)
        }
    }

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
        fun open() {
            val request = receive(); assertEquals(3, request.type); assertEquals(2L, request.body.number(2))
            assertEquals(0L, request.body.number(3)); assertEquals(5L, request.body.number(4))
        }
        fun status(h: TimelineHeader) = send(timelineFrame(110, tn(1, 1, true), tn(2, 0, true),
            tn(3, h.streamId, true), tn(4, 2, true), tn(5, 0), tn(6, 5), tn(7, 1_048_576, true), tn(8, 0), tn(9, 10)))
        fun publish(p: TimelinePublication) {
            send(p.header.beginFrame()); send(chunk(p.header, p.operations))
            send(timelineFrame(108, tn(1, p.header.streamId, true), tn(2, p.header.epoch), tn(3, p.header.revision)))
        }
        fun verify(state: TimelineState, id: Long) {
            val digest = TimelineCanonical.digest(state); send(check(state.header, id, digest))
            val ack = receive(); assertEquals(8, ack.type); assertEquals(state.header.epoch, ack.body.number(2))
            assertEquals(state.header.revision, ack.body.number(3)); assertEquals(digest, ack.body.bytes(6))
        }
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
                TimelineClient("127.0.0.1", listener.localPort, "History receipt", "c".repeat(32)).use { client ->
                    assertTrue(ready.await(3, TimeUnit.SECONDS)); test(client, proceed, release, peer.get().done)
                }
            } finally { proceed.countDown(); release.countDown(); finish.countDown(); thread.join(6000) }
            assertFalse(thread.isAlive); failure.get()?.let { throw AssertionError("Fake HISTORY server failed", it) }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition()) { check(System.nanoTime() < deadline) { "HISTORY receipt fixture timed out" }; Thread.sleep(3) }
    }
}
