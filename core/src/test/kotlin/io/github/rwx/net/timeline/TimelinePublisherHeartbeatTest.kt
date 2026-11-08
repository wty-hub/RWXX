package io.github.rwx.net.timeline

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TimelinePublisherHeartbeatTest {
    private val f = TimelineTestFixture
    private val second = 60L * TimelineProtocol.SUBDIVISIONS
    private val unit = TimelineObservedUnit(77, TimelineValue.Vec3(256, 512, -1), 65000,
        25600, 25600, 65535, 0, "tank", 1, true)

    @Test fun `frozen committed observations retain the exact library across full confirmation scans`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observation(0))
        val initial = assertNotNull(publisher.publish(0))
        val digest = TimelineCanonical.digest(initial)
        val scans = publisher.confirmationScans
        for (index in 1L..4L) {
            assertNull(publisher.publish(index * 1_000_000_000L))
            assertEquals(scans + index, publisher.confirmationScans)
            assertSame(initial, publisher.latest)
            assertEquals(digest, TimelineCanonical.digest(assertNotNull(publisher.latest)))
            repeat(5) { poll -> assertNull(publisher.publish(index * 1_000_000_000L + 100_000_000 + poll * 1_000_000)) }
            assertEquals(scans + index, publisher.confirmationScans)
        }
    }

    @Test fun `a newer unchanged time still flushes and same tick selection changes still revoke controls`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observation(0))
        val initial = assertNotNull(publisher.publish(0))
        publisher.observe(observation(1024))
        assertNull(publisher.publish(100_000_000))
        assertSame(initial, publisher.latest)
        val advanced = assertNotNull(publisher.publish(1_000_000_000))
        assertEquals(1024L, advanced.header.observedThrough)
        assertEquals(0L, advanced.header.sealedThrough)
        assertEquals(initial.objects, advanced.objects)
        assertNull(publisher.publish(2_000_000_000))
        assertSame(advanced, publisher.latest)

        publisher.observe(observation(1024, selected = true))
        val selected = assertNotNull(publisher.publish(2_100_000_000))
        assertEquals(1024L, selected.header.observedThrough)
        assertTrue(selected.header.controlEpoch > advanced.header.controlEpoch)
        val panel = selected.objects.values.single { it.kind == TimelineObjectKind.PANEL }
        assertNull(panel.close)
        publisher.observe(observation(1024))
        val cleared = assertNotNull(publisher.publish(2_200_000_000))
        assertTrue(cleared.header.controlEpoch > selected.header.controlEpoch)
        assertEquals(1024L, cleared.objects.getValue(panel.handle).close?.until)
        assertEquals(TimelineValue.Blob(f.compact(0)), TimelineProfile.sample(cleared, 0, 3, 1024))
        assertNull(selected.objects.getValue(panel.handle).close)
    }

    @Test fun `frozen Session keeps real socket heartbeats checks and fresh receipts then flushes time history and terminal fence`() = temporary { directory ->
        val path = directory.resolve("heartbeat.rwxr")
        val context = f.context()
        val session = TimelineSession(context, path)
        try {
            assertTrue(session.offer(observation(0)))
            await { session.latest != null && session.statistics().publications == 1L }
            val initial = assertNotNull(session.latest)
            val publications = session.statistics().publications
            val token = "h".repeat(32)
            TimelineServer(InetAddress.getByName("127.0.0.1"), 0,
                mapOf(token to TimelineSeat(0, context.accessId)), context.mapRecord, context.matchId, session,
                serverTime = { session.processedThrough.coerceAtLeast(0) },
                historyUntil = { session.archivedUntil }).use { server ->
                // Default one-second encoder/check cadence, real sockets and actual receiver hashes.
                TimelineClient("127.0.0.1", server.localPort, "Frozen observation", token).use { client ->
                    val historyEnded = ConcurrentHashMap.newKeySet<Long>()
                    client.observeFrames { frame ->
                        if (frame.type == 112 && frame.body.number(3) == 2L) historyEnded.add(frame.body.number(1))
                    }.use {
                        client.openTimeline(1)
                        await { client.latest(1) != null }
                        val firstRevision = assertNotNull(client.latest(1)).header.revision
                        await {
                            val stats = client.wireStatistics()
                            stats.stateAcks >= 2 && stats.stateChecks >= 2 &&
                                (client.latest(1)?.header?.revision ?: 0) >= firstRevision + 2
                        }
                        assertSame(initial, session.latest)
                        assertEquals(publications, session.statistics().publications)
                        assertEquals(0L, client.latest(1)?.header?.observedThrough)
                        assertTrue(client.wireStatistics().stateDigestNanos > 0)
                        assertTrue(assertNotNull(server.wireStatistics(0)).stateDigestNanos > 0)
                        assertEquals(0L, client.wireStatistics().stateMismatches)
                        assertEquals(0L, client.wireStatistics().restores)

                        // A receipt sent by a network-only heartbeat remains usable after >2 s.
                        val state = assertNotNull(client.latest(1))
                        client.sendCommand(stop(state))
                        val command = AtomicReference<TimelineRequest?>()
                        await { server.drainRequests { request -> command.set(request) }; command.get() != null }
                        assertTrue(assertNotNull(command.get()).receipt.containsUnit(1))

                        assertTrue(session.offer(observation(second)))
                        await { client.latest(1)?.header?.observedThrough == second && session.archivedUntil == second }
                        client.openTimeline(2, TimelineStreamMode.HISTORY, 0, second)
                        await { historyEnded.contains(2) }
                        val historical = assertNotNull(client.latest(2))
                        assertEquals(TimelineStreamMode.HISTORY, historical.context.mode)
                        assertEquals(0L, historical.header.controlEpoch)
                        assertEquals(second, historical.header.retainedUntil)
                        assertEquals(TimelineValue.SInt(1000), TimelineProfile.sample(historical, 0, 1, second - 1))

                        val finish = second + 1024
                        val status = (f.finished(finish) as TimelineValue.Blob).value
                        fun ended(time: Long) = TimelineObservation(time, 1000, status, f.bytes(10, 10, 10, 10), emptyList())
                        assertTrue(session.offer(ended(finish)))
                        assertTrue(session.offer(ended(finish + 1)))
                        assertEquals(path, session.finishAsync().get(5, TimeUnit.SECONDS))
                        await { client.latest(1)?.header?.observedThrough == finish + 1 }
                        assertEquals(finish + 1, session.archivedUntil)
                        TimelineArchive.open(path).use { archive ->
                            assertEquals(TimelineValue.Blob(status), assertNotNull(archive.seek(finish)).sample(0, 2, finish))
                            assertEquals(TimelineValue.SInt(1000), assertNotNull(archive.seek(second - 1)).sample(0, 1, second - 1))
                        }
                        assertTrue(client.isConnected)
                        assertEquals(0L, client.wireStatistics().stateMismatches)
                        assertEquals(0L, client.wireStatistics().restores)
                        assertNull(client.failure)
                        assertNull(session.failure)
                    }
                }
            }
        } finally {
            session.close()
            session.stoppedAsync().get(5, TimeUnit.SECONDS)
        }
    }

    private fun observation(time: Long, selected: Boolean = false) = TimelineObservation(time, 1000,
        (f.running() as TimelineValue.Blob).value, f.bytes(0, 0, 0, 0), listOf(unit), if (selected) unit.key else null)

    private fun stop(state: TimelineState) = timelineFrame(5, tn(1, state.header.streamId, true),
        tn(2, state.header.epoch), tn(3, 1), tn(4, state.header.revision), tn(5, state.header.controlEpoch),
        tn(6, 3, true), tb(7, f.compact(1, 1)), tn(8, 0),
        TimelineMessages.recordField(9, TimelineRecord(listOf(tn(1, 0, true)))), tn(10, 0, true))

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
        while (!condition()) { check(System.nanoTime() < deadline) { "Timeline heartbeat wait timed out" }; Thread.sleep(3) }
    }

    private inline fun temporary(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("timeline-heartbeat-test-")
        try { block(directory) }
        finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
