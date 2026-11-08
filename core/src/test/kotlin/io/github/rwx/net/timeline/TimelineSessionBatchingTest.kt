package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.*

class TimelineSessionBatchingTest {
    private val f = TimelineTestFixture
    private val second = 60L * 1024
    private fun unit(tick: Long) = TimelineObservedUnit(77, TimelineValue.Vec3(256 + tick, 512, -1),
        65000, 25600, 25600, 65535, 0, "tank", 1, true)
    private fun event(tick: Long, tail: Long) = TimelineObservedEvent(tick + 10000, tick * 1024,
        TimelineRecord(listOf(
            TimelineField(1, true, TimelineTlvValue.U32(1)),
            TimelineField(2, true, TimelineTlvValue.Bytes(ByteArrayOutputStream().let { out ->
                listOf(256L, 512L, 0L).forEach { TimelineBinary.writeVSInt(out, it) }
                TimelineBytes(out.toByteArray())
            })),
            TimelineField(3, true, TimelineTlvValue.U64(tail)),
            TimelineField(4, true, TimelineTlvValue.U32(99)))), tail)
    private fun observation(tick: Long, present: Boolean = true, events: List<TimelineObservedEvent> = emptyList(), credits: Long = 1000) =
        TimelineObservation(tick * 1024, credits, (f.running() as TimelineValue.Blob).value,
            f.bytes(0, 0, 0, 0), if (present) listOf(unit(tick)) else emptyList(), events = events)
    private inline fun temporary(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("timeline-session-batch-test-")
        try { block(directory) }
        finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) } } }
    }
    private inline fun TimelineSession.running(block: (TimelineSession) -> Unit) {
        try { block(this) } finally { close(); stoppedAsync().get(5, TimeUnit.SECONDS) }
    }
    private fun await(message: String = "Session operation timed out", test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
        while (!test()) { check(System.nanoTime() < deadline) { message }; Thread.sleep(2) }
    }
    private fun offerAndWait(session: TimelineSession, observation: TimelineObservation) {
        assertTrue(session.offer(observation), session.failure?.message)
        await { session.failure != null || session.processedThrough == observation.time }
        assertNull(session.failure)
    }
    private fun events(state: TimelineState) = state.objects.values.filter { it.kind == TimelineObjectKind.VISUAL }
        .flatMap { it.curves.getValue(1).events }

    @Test fun `finishing before the first seal batch archives movement instant effect and closed object tail`() = temporary { directory ->
        val path = directory.resolve("three-seconds.rwxr")
        TimelineSession(f.context(), path).running { session ->
            val instant = event(135, 0)
            for (tick in 0L..180L) offerAndWait(session, observation(tick, tick < 150,
                if (tick == 135L) listOf(instant) else emptyList()))
            assertEquals(path, session.finishAsync().get(5, TimeUnit.SECONDS))
            val final = assertNotNull(session.latest)
            assertEquals(3 * second, final.header.observedThrough)
            assertEquals(final.header.observedThrough, final.header.sealedThrough)
            assertEquals(181L, session.statistics().processedObservations)
            assertEquals(3 * second, session.archivedUntil)
            assertNull(session.failure)
            TimelineArchive.open(path).use { archive ->
                assertTrue(archive.readOnly)
                assertEquals(0L, archive.index.first().from)
                assertEquals(3 * second, archive.index.last().until)
                val beforeClose = assertNotNull(archive.seek(149 * 1024L + 512))
                assertEquals(unit(149).position, beforeClose.sample(1, 1, 149 * 1024L + 512))
                assertEquals(150 * 1024L, beforeClose.state.objects.getValue(1).close?.until)
                val closed = assertNotNull(archive.seek(150 * 1024L))
                assertNull(closed.sample(1, 1, 150 * 1024L))
                val effectSlice = assertNotNull(archive.seek(135 * 1024L))
                assertEquals(1, events(effectSlice.state).count { it.time == instant.time })
                val last = assertNotNull(archive.seek(3 * second - 1))
                assertEquals(TimelineValue.SInt(1000), last.sample(0, 1, 3 * second - 1))
                assertEquals(TimelineStreamMode.HISTORY, last.state.context.mode)
                assertEquals(0L, last.state.header.controlEpoch)
                assertNull(archive.seek(3 * second))
            }
        }
    }

    @Test fun `two sealing batches preserve bounded archive queue carry in random seeks and four independent history windows`() = temporary { directory ->
        val path = directory.resolve("two-batches.rwxr")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        // Four tiny HISTORY fixtures reserve 64 KiB each; archive count/byte limits remain defaults.
        val limits = TimelineSessionLimits(historyStateBytes = 65536, historyBytes = 4 * 65536L)
        TimelineSession(f.context(), path, limits).running { session ->
            session.beforeArchiveAppend = { entered.countDown(); check(release.await(7, TimeUnit.SECONDS)) }
            val crossing = event(230, 3 * second)
            try {
                for (tick in 0L..600L) offerAndWait(session, observation(tick,
                    events = when (tick) { 230L -> listOf(crossing); 435L -> listOf(event(tick, 0)); else -> emptyList() }))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertTrue(session.statistics().pendingArchives in 1..limits.archiveCount)
                assertTrue(session.statistics().peakPendingArchives <= 4)
                assertTrue(session.statistics().peakPendingArchiveBytes <= limits.archiveBytes)
                release.countDown()
                await { session.failure != null || session.archivedUntil >= 8 * second }
                assertNull(session.failure)
                val windows = listOf(0L to second, second to 2 * second, 3 * second to 4 * second, 6 * second to 7 * second)
                val feeds = ArrayList<TimelineFeed>()
                try {
                    val histories = windows.mapIndexed { index, (from, until) ->
                        val history = session.context.copy(mode = TimelineStreamMode.HISTORY, acceptedFrom = from, acceptedUntil = until)
                        val stream = index + 2L
                        val request = TimelineStreamRequest(session.context.teamId, 1, stream, stream, history, 1_048_576)
                        val feed = assertNotNull(session.open(request)); feeds.add(feed)
                        val cursor = TimelineSendCursor(history, stream, 1, null, true)
                        var publication: TimelinePublication? = null
                        await { publication = feed.next(cursor); publication != null }
                        TimelineStore(history).apply(publication!!.header, publication!!.operations).also { state ->
                            assertEquals(from, state.header.retainedFrom); assertEquals(until, state.header.retainedUntil)
                            assertEquals(0L, state.header.controlEpoch)
                            assertEquals(TimelineStreamMode.HISTORY, state.context.mode)
                            assertEquals(TimelineValue.Vec3(256 + from / 1024, 512, -1), TimelineProfile.sample(state, 1, 1, from))
                        }
                    }
                    val digests = histories.map(TimelineCanonical::digest)
                    assertEquals(4, session.statistics().historyReservations)
                    offerAndWait(session, observation(606, credits = 1001))
                    await { session.latest?.header?.observedThrough == 606 * 1024L }
                    assertEquals(TimelineStreamMode.LIVE, assertNotNull(session.latest).context.mode)
                    assertEquals(digests, histories.map(TimelineCanonical::digest))
                } finally { feeds.forEach(TimelineFeed::close) }
                await { session.statistics().historyReservations == 0 }
                session.finishAsync().get(5, TimeUnit.SECONDS)
                assertEquals(606 * 1024L, session.archivedUntil)
                assertNull(session.failure)
            } finally { release.countDown() }
            TimelineArchive.open(path).use { archive ->
                assertTrue(archive.index.size >= 2)
                assertEquals(0L, archive.index.first().from)
                assertEquals(606 * 1024L, archive.index.last().until)
                archive.index.zipWithNext().forEach { (a, b) -> assertEquals(a.until, b.from) }
                assertTrue(archive.index.all { it.until - it.from <= 120 * second })
                val random = Random(715)
                val times = List(100) { random.nextLong(606 * 1024L) } + archive.index.flatMap { listOf(it.from, it.until - 1) }
                val values = times.map { time ->
                    val slice = assertNotNull(archive.seek(time))
                    assertTrue(slice.readOnly)
                    val expected = TimelineValue.Vec3(256 + (time + 512) / 1024, 512, -1)
                    assertEquals(expected, slice.sample(1, 1, time))
                    time to expected
                }
                values.asReversed().forEach { (time, expected) ->
                    assertEquals(expected, assertNotNull(archive.seek(time)).sample(1, 1, time))
                }
                val carryIn = archive.index.first { it.from > crossing.time && it.from < crossing.time + crossing.maxTailTime }
                assertTrue(events(assertNotNull(archive.seek(carryIn.from)).state).any { it.time == crossing.time })
            }
        }
    }
}
