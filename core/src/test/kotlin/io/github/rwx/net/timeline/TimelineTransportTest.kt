package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TimelineTransportTest {
    private val f = TimelineTestFixture

    @Test fun `owner starvation reports the exact peer input queue limit without changing its budget`() {
        val source = Source()
        server(source).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Queue metadata only", TOKEN0).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val first = checkNotNull(client.latest(1))
                repeat(32) { client.sendCommand(stop(first, it + 1L)) }
                await { server.pendingRequestCount == 32 }
                // Respect the unchanged60/s command limit; refreshing the real receipt also
                // prevents a slow CI runner from testing receipt expiry instead of capacity.
                CountDownLatch(1).await(1100, TimeUnit.MILLISECONDS)
                source.time.set(20); server.signal(0)
                await { client.latest(1)?.header?.observedThrough == 20L }
                val refreshed = checkNotNull(client.latest(1))
                repeat(33) { client.sendCommand(stop(refreshed, it + 33L)) }
                await { server.peerClosureStatistics().closedAfterHello == 1L }
                val closed = server.peerClosureStatistics().closures.single()
                assertEquals(TimelinePeerCloseReason.PEER_INPUT_QUEUE_LIMIT, closed.reason)
                assertEquals(65, closed.peerQueuedCountAtCause)
                assertEquals(64, closed.ownerQueueSizeAtRecord)
                assertEquals(ProtocolException::class.java.name, closed.exceptionClass)
                assertEquals(0L, server.authenticationStatistics().failedBeforeFirstHello)
                assertEquals(64, server.pendingRequestCount)
            }
        }
    }

    @Test fun `post Hello protocol classification retains the original command sequence guard`() {
        server(Source()).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Sequence metadata only", TOKEN0).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val state = checkNotNull(client.latest(1)); client.sendCommand(stop(state, 1))
                await { server.pendingRequestCount == 1 }
                client.sendCommand(stop(state, 1))
                await { server.peerClosureStatistics().closedAfterHello == 1L }
                assertEquals(TimelinePeerCloseReason.COMMAND_RATE_OR_SEQUENCE, server.peerClosureStatistics().closures.single().reason)
                assertEquals(1, server.pendingRequestCount)
            }
        }
    }

    @Test fun `post Hello closure ring is bounded immutable and never stores names tokens or exception text`() {
        server(Source()).use { server ->
            repeat(66) { index ->
                TimelineClient("127.0.0.1", server.localPort, "secret-name-not-in-closure", TOKEN0).use { client ->
                    await { server.authenticationStatistics().firstHellosWritten == index + 1L }
                    server.disconnect(0)
                    await { server.peerClosureStatistics().closedAfterHello == index + 1L }
                    assertFalse(client.isConnected && client.latestStates().isNotEmpty())
                }
                await { server.connectedTeamIds().isEmpty() }
            }
            val stats = server.peerClosureStatistics()
            assertEquals(66L, stats.closedAfterHello); assertEquals(64, stats.retainedCapacity)
            assertEquals(64, stats.closures.size); assertEquals(2L, stats.droppedClosures)
            assertEquals((3L..66L).toList(), stats.closures.map { it.generation })
            assertTrue(stats.closures.all { it.reason == TimelinePeerCloseReason.REQUESTED_DISCONNECT && it.exceptionClass == null })
            assertEquals(0L, server.authenticationStatistics().failedBeforeFirstHello)
            assertFalse(stats.toString().contains(TOKEN0)); assertFalse(stats.toString().contains("secret-name-not-in-closure"))
            assertFailsWith<UnsupportedOperationException> { (stats.closures as MutableList<TimelinePeerClosure>).clear() }
        }
    }

    @Test fun `map cache pins immutable record and live atomic commits have complete real checks`() {
        val source = Source()
        server(source).use { server ->
            server.checkIntervalMillis = 30
            val map = f.map(); val key = TimelineCanonical.mapKey(map)
            val saves = AtomicLong()
            TimelineClient("127.0.0.1", server.localPort, "Tester", TOKEN0,
                mapResolver = { if (it == key) map else null }, onMapReceived = { saves.incrementAndGet() }).use { client ->
                client.openTimeline(1) // May run before MapComplete.
                await { client.latest(1) != null }
                assertEquals(1L, client.wireStatistics().mapCacheHits)
                assertEquals(0L, client.wireStatistics().mapInitializations); assertEquals(0L, saves.get())
                source.time.set(20); server.signal(0)
                await { client.latest(1)?.header?.observedThrough == 20L }
                await { client.wireStatistics().stateAcks >= 2 }
                assertEquals(0L, client.wireStatistics().stateMismatches)
                assertTrue(client.wireStatistics().stateDigestNanos > 0)
                assertEquals(TimelineCanonical.digest(assertNotNull(server.committedState(0, 1))), TimelineCanonical.digest(assertNotNull(client.latest(1))))
                val state = client.latest(1)!!
                client.sendCommand(stop(state, 1))
                val command = AtomicReference<TimelineRequest?>()
                await { server.drainRequests { command.set(it) }; command.get() != null }
                assertEquals(5, command.get()!!.frame.type)
                assertTrue(command.get()!!.receipt.containsUnit(1))
            }
        }
    }

    @Test fun `HISTORY checks once ends and a later seek does not replace LIVE`() {
        val source = Source().apply { time.set(20) }
        server(source).use { server ->
            server.checkIntervalMillis = 30
            TimelineClient("127.0.0.1", server.localPort, "Tester", TOKEN0).use { client ->
                val completed = ConcurrentHashMap.newKeySet<Long>()
                client.observeFrames { if (it.type == 112 && it.body.number(3) == 2L) completed.add(it.body.number(1)) }.use {
                    client.openTimeline(1); client.openTimeline(2, TimelineStreamMode.HISTORY, 5, 10)
                    await { client.latest(1) != null && completed.contains(2) }
                    assertEquals(5L, client.latest(2)?.header?.retainedFrom)
                    assertEquals(10L, client.latest(2)?.header?.retainedUntil)
                    assertEquals(TimelineStreamMode.HISTORY, client.latest(2)?.context?.mode)
                    source.time.set(30); server.signal(0)
                    await { client.latest(1)?.header?.observedThrough == 30L }
                    client.openTimeline(3, TimelineStreamMode.HISTORY, 10, 15)
                    await { completed.contains(3) }
                    assertEquals(30L, client.latest(1)?.header?.observedThrough)
                    assertEquals(1L, client.latest(2)?.header?.revision)
                    assertTrue(client.isConnected)
                }
            }
        }
    }

    @Test fun `second LIVE and fifth active stream are rejected without corrupting existing streams`() {
        val source = Source().apply { holdHistory.set(true) }
        server(source).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Tester", TOKEN0).use { client ->
                val status = ConcurrentHashMap<Long, Long>()
                client.observeFrames { if (it.type == 110) status[it.body.number(1)] = it.body.number(2) }.use {
                    client.openTimeline(1); await { client.latest(1) != null }
                    client.openTimeline(2); await { status[2] == 2L }
                    client.openTimeline(3, TimelineStreamMode.HISTORY, 0, 5)
                    client.openTimeline(4, TimelineStreamMode.HISTORY, 0, 5)
                    client.openTimeline(5, TimelineStreamMode.HISTORY, 0, 5)
                    client.openTimeline(6, TimelineStreamMode.HISTORY, 0, 5)
                    await { status[6] == 2L }
                    assertEquals(1, client.latestStates().size)
                    assertTrue(client.isConnected)
                }
            }
        }
    }

    @Test fun `midmatch connect receives its own access range while first seat keeps advancing`() {
        val source = Source()
        val seats = mapOf(TOKEN0 to seat(0), TOKEN1 to seat(1).copy(joinedAt = 5, historyFrom = 5))
        server(source, seats).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "One", TOKEN0).use { first ->
                first.openTimeline(1); await { first.latest(1) != null }
                source.time.set(30); server.signal(0)
                TimelineClient("127.0.0.1", server.localPort, "Two", TOKEN1).use { joined ->
                    joined.openTimeline(1); await { joined.latest(1) != null && first.latest(1)?.header?.observedThrough == 30L }
                    assertEquals(5L, joined.latest(1)?.header?.retainedFrom)
                    assertEquals(1, joined.teamId)
                    val refused = CountDownLatch(1)
                    joined.observeFrames { if (it.type == 110 && it.body.number(1) == 2L && it.body.number(2) == 1L) refused.countDown() }.use {
                        joined.openTimeline(2, TimelineStreamMode.HISTORY, 0, 4)
                        assertTrue(refused.await(3, TimeUnit.SECONDS))
                    }
                    source.time.set(40); server.signal(0); server.signal(1)
                    await { first.latest(1)?.header?.observedThrough == 40L && joined.latest(1)?.header?.observedThrough == 40L }
                }
            }
        }
    }

    @Test fun `exclusive seat reconnect changes generation and stale generation cannot stop replacement`() {
        val source = Source()
        server(source).use { server ->
            val first = TimelineClient("127.0.0.1", server.localPort, "One", TOKEN0)
            val old = assertNotNull(server.connectionGeneration(0))
            assertFailsWith<IOException> { TimelineClient("127.0.0.1", server.localPort, "Occupied", TOKEN0) }
            first.close(); await { server.connectionGeneration(0) == null }
            TimelineClient("127.0.0.1", server.localPort, "Again", TOKEN0).use { replacement ->
                assertNotEquals(old, server.connectionGeneration(0))
                server.disconnect(0, old)
                replacement.openTimeline(1); await { replacement.latest(1) != null }
                assertTrue(replacement.isConnected)
            }
        }
    }

    @Test fun `retention gap requests one fresh epoch and keeps controls disabled until Commit`() {
        val source = Source()
        server(source).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Tester", TOKEN0).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val before = client.latest(1)!!
                source.requireReset.set(true); server.signal(0)
                await { client.latest(1)?.header?.epoch == 2L }
                assertTrue(client.latest(1)!!.header.controlEpoch > before.header.controlEpoch)
                assertEquals(1L, client.wireStatistics().mapInitializations)
                assertTrue(client.isConnected)
            }
        }
    }

    @Test fun `owner publish and healthy peer continue while a heartbeat sending slow peer does not read`() {
        val source = Source().apply { largeTeam = 1 }
        server(source, mapOf(TOKEN0 to seat(0), TOKEN1 to seat(1))).use { server ->
            server.timeoutMillis = 1000; server.checkIntervalMillis = 30
            TimelineClient("127.0.0.1", server.localPort, "Healthy", TOKEN0).use { healthy ->
                healthy.openTimeline(1); await { healthy.latest(1) != null }
                Raw(server.localPort, TOKEN1).use { slow ->
                    slow.mapHandshake(); slow.send(open(1))
                    val pumping = AtomicBoolean(true)
                    val pump = Thread {
                        var nonce = 1L
                        while (pumping.get()) {
                            try { slow.send(timelineFrame(7, tn(1, nonce++), tn(2, 0))) } catch (_: IOException) { break }
                            Thread.sleep(40)
                        }
                    }.apply { isDaemon = true; start() }
                    try {
                        for (time in 11L..40L) { source.time.set(time); server.signal(0); server.signal(1) }
                        await { healthy.latest(1)?.header?.observedThrough == 40L }
                        await { 1 !in server.connectedTeamIds() }
                        assertTrue(healthy.isConnected)
                        assertTrue(healthy.wireStatistics().stateAcks > 0)
                    } finally { pumping.set(false); slow.close(); pump.join(2000) }
                }
            }
        }
    }

    @Test fun `client close LIVE immediately blocks new and queued commands`() {
        val source = Source()
        server(source).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Tester", TOKEN0).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val taken = CountDownLatch(1); val resume = CountDownLatch(1)
                client.beforeCommandWrite = { taken.countDown(); check(resume.await(4, TimeUnit.SECONDS)) }
                try {
                    val old = client.latest(1)!!; client.sendCommand(stop(old, 1))
                    assertTrue(taken.await(3, TimeUnit.SECONDS))
                    client.closeTimeline(1)
                    assertNull(client.latest(1))
                    assertFailsWith<IOException> { client.sendCommand(stop(old, 2)) }
                    resume.countDown()
                    await { server.committedState(0, 1) == null }
                    var count = 0; server.drainRequests { count++ }; assertEquals(0, count)
                    assertTrue(client.isConnected)
                } finally { resume.countDown() }
            }
        }
    }

    @Test fun `real socket malformed version is closed without a fallback handshake`() {
        server(Source()).use { server ->
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 3000
                val frame = TimelineBinary.encodeFrame(timelineFrame(1, tn(1, 1, true), TimelineMessages.textField(2, "Tester"), TimelineMessages.textField(3, TOKEN0))).toByteArray()
                frame[9] = 6 // wireVersion u16 at offsets 8/9.
                socket.getOutputStream().write(frame); socket.getOutputStream().flush()
                assertEquals(-1, socket.getInputStream().read())
            }
            assertTrue(server.connectedTeamIds().isEmpty())
        }
    }

    @Test fun `blocked observer never holds the client state lock or delays close`() {
        server(Source()).use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
            TimelineClient("127.0.0.1", server.localPort, "Tester", TOKEN0).use { client ->
                client.observeLatest { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }.use {
                    var closer: Thread? = null
                    try {
                        client.openTimeline(1); assertTrue(entered.await(3, TimeUnit.SECONDS))
                        closer = Thread { client.close(); closed.countDown() }.apply { isDaemon = true; start() }
                        assertTrue(closed.await(1, TimeUnit.SECONDS), "close must not wait for observer I/O")
                        assertNull(client.latest(1))
                    } finally { release.countDown(); closer?.join(2000) }
                }
            }
        }
    }

    @Test fun `owner drain rechecks a target that left disclosure after command enqueue`() {
        val source = Source().apply { addEnemy = true }
        server(source).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "Tester", TOKEN0).use { client ->
                client.openTimeline(1); await { client.latest(1) != null }
                val old = client.latest(1)!!
                val attack = timelineFrame(5, tn(1, 1, true), tn(2, old.header.epoch), tn(3, 1), tn(4, old.header.revision),
                    tn(5, old.header.controlEpoch), tn(6, 4, true), tb(7, f.compact(1, 1)), tn(8, 0),
                    TimelineMessages.recordField(9, TimelineRecord(listOf(tn(1, 2, true), tn(4, 2)))), tn(10, 0, true))
                client.sendCommand(attack); await { server.pendingRequestCount == 1 }
                source.closeEnemy.set(true); source.time.set(20); server.signal(0)
                await { client.latest(1)?.objects?.get(2)?.close != null }
                assertTrue(checkNotNull(client.latest(1)).header.controlEpoch > old.header.controlEpoch,
                    "Closing an observation revokes the prior control epoch")
                var consumed = 0; server.drainRequests { consumed++ }
                assertEquals(0, consumed); assertEquals(0, server.pendingRequestCount)
            }
        }
    }

    @Test fun `blocked feed cleanup cannot stop another peers deadline or simulation publication`() {
        val source = Source().apply { closeTeam = 0 }
        val token2 = "d".repeat(32)
        server(source, mapOf(TOKEN0 to seat(0), TOKEN1 to seat(1), token2 to seat(2))).use { server ->
            server.checkIntervalMillis = 30; server.timeoutMillis = 350
            val first = TimelineClient("127.0.0.1", server.localPort, "First", TOKEN0)
            try {
                first.openTimeline(1); await { first.latest(1) != null }
                TimelineClient("127.0.0.1", server.localPort, "Healthy", token2).use { healthy ->
                    healthy.openTimeline(1); await { healthy.latest(1) != null }
                    Raw(server.localPort, TOKEN1).use { unconfirmed ->
                        unconfirmed.mapHandshake(); unconfirmed.send(open(1))
                        while (unconfirmed.read().type != 109) { }
                        val pumping = AtomicBoolean(true)
                        val pump = Thread {
                            var nonce = 1L
                            while (pumping.get()) {
                                try { unconfirmed.send(timelineFrame(7, tn(1, nonce++), tn(2, 0))); Thread.sleep(25) }
                                catch (_: IOException) { break }
                            }
                        }.apply { isDaemon = true; start() }
                        try {
                            first.close(); assertTrue(source.closeEntered.await(2, TimeUnit.SECONDS))
                            source.time.set(20); server.signal(2)
                            await { healthy.latest(1)?.header?.observedThrough == 20L }
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                            while (1 in server.connectedTeamIds()) {
                                assertTrue(System.nanoTime() < deadline, "A different feed's close blocked the global watchdog")
                                Thread.sleep(5)
                            }
                            assertTrue(healthy.isConnected)
                        } finally { pumping.set(false); unconfirmed.close(); pump.join(2000) }
                    }
                }
            } finally { source.releaseClose.countDown(); first.close() }
        }
    }

    private inner class Source : TimelineFeedFactory {
        val time = AtomicLong(10)
        val requireReset = AtomicBoolean()
        val holdHistory = AtomicBoolean()
        val closeEnemy = AtomicBoolean()
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        var addEnemy = false
        var closeTeam = -1
        var largeTeam = -1
        override fun open(request: TimelineStreamRequest) = object : TimelineFeed {
            override fun next(cursor: TimelineSendCursor): TimelinePublication? {
                if (request.context.mode == TimelineStreamMode.HISTORY && holdHistory.get()) return null
                if (!cursor.reset && requireReset.compareAndSet(true, false)) throw TimelineResetRequired()
                val old = cursor.previous
                if (!cursor.reset && old!!.header.observedThrough == time.get()) return null
                val context = request.context
                val from = context.acceptedFrom ?: context.historyFrom
                val observed = time.get()
                val ops: List<TimelineOperation> = if (cursor.reset) buildList<TimelineOperation> {
                    addAll(f.root(from = from)); addAll(f.unit(from = from, team = context.teamId.toLong()))
                    if (addEnemy) addAll(f.unit(handle = 2, from = from, team = 1))
                    if (request.teamId == largeTeam) repeat(48) { index ->
                        val id = 65536 + index
                        add(TimelineOperation.Declare(0, id, TimelineMode.STEP, TimelineValueType.BLOB, false))
                        add(f.step(0, id, TimelineValue.Blob(TimelineBytes(ByteArray(65536))), from))
                    }
                } else if (closeEnemy.get() && old!!.objects[2]?.close == null) buildList<TimelineOperation> {
                    old.objects.getValue(2).curves.values.forEach { curve ->
                        add(TimelineOperation.Splice(2, curve.attributeId, curve.curveRevision, observed,
                            TimelineProtocol.FOREVER, emptyList()))
                    }
                    add(TimelineOperation.Close(2, observed, TimelineCloseKind.ENDED))
                } else emptyList()
                val revoked = ops.any { it is TimelineOperation.Close }
                val header = f.header(ops, old, observed = observed,
                    sealed = context.acceptedUntil ?: from, from = from, until = context.acceptedUntil ?: observed + 1,
                    control = if (context.mode == TimelineStreamMode.HISTORY) 0 else if (cursor.reset || revoked) (old?.header?.controlEpoch ?: 0) + 1 else old!!.header.controlEpoch,
                    reset = cursor.reset, epoch = cursor.epoch, highWater = if (addEnemy) 2 else 1).copy(streamId = request.streamId)
                val formats = if (cursor.reset) emptyMap() else checkNotNull(old).objects.values.flatMap { obj ->
                    obj.curves.values.map { TimelineCurveKey(obj.handle, it.attributeId) to it.format }
                }.toMap()
                return TimelinePublication(header, TimelineBinary.encodeOperations(ops, formats))
            }
            override fun close() {
                if (request.teamId == closeTeam) { closeEntered.countDown(); check(releaseClose.await(10, TimeUnit.SECONDS)) }
            }
        }
    }

    private fun seat(team: Int) = TimelineSeat(team, TimelineBytes(ByteArray(16) { (team + 1).toByte() }))
    private fun server(source: Source, seats: Map<String, TimelineSeat> = mapOf(TOKEN0 to seat(0))) =
        TimelineServer(InetAddress.getByName("127.0.0.1"), 0, seats, f.map(), TimelineBytes(ByteArray(16)), source,
            serverTime = source.time::get, historyUntil = { source.time.get() })
    private fun stop(state: TimelineState, sequence: Long) = timelineFrame(5,
        tn(1, state.header.streamId, true), tn(2, state.header.epoch), tn(3, sequence), tn(4, state.header.revision),
        tn(5, state.header.controlEpoch), tn(6, 3, true), tb(7, f.compact(1, 1)), tn(8, 0),
        TimelineMessages.recordField(9, TimelineRecord(listOf(tn(1, 0, true)))), tn(10, 0, true))
    private fun open(request: Long, mode: Long = 1, from: Long = 0, until: Long = 0) = timelineFrame(3,
        tn(1, request, true), tn(2, mode, true), tn(3, from), tn(4, until), tn(5, 16_777_216, true))
    private class Raw(port: Int, token: String) : AutoCloseable {
        val socket = Socket().apply { receiveBufferSize = 1024; connect(java.net.InetSocketAddress("127.0.0.1", port)); soTimeout = 4000 }
        init { send(timelineFrame(1, tn(1, 1, true), TimelineMessages.textField(2, "Raw"), TimelineMessages.textField(3, token))); assertEquals(101, read().type) }
        fun send(frame: TimelineFrame) { TimelineBinary.writeFrame(socket.getOutputStream(), frame); socket.getOutputStream().flush() }
        fun read(): TimelineFrame = TimelineBinary.readFrame(socket.getInputStream()).also { TimelineMessages.validate(it, false) }
        fun mapHandshake() {
            val offer = read(); assertEquals(103, offer.type)
            send(timelineFrame(2, tb(1, offer.body.bytes(1)), TimelineMessages.boolField(2, false)))
            while (read().type != 105) { }
        }
        override fun close() = socket.close()
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition()) { check(System.nanoTime() < until) { "Timeline transport wait timed out" }; Thread.sleep(3) }
    }
    private companion object { val TOKEN0 = "a".repeat(32); val TOKEN1 = "b".repeat(32) }
}
