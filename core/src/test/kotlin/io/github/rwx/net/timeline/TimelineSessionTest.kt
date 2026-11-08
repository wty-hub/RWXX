package io.github.rwx.net.timeline

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class TimelineSessionTest {
    private val f = TimelineTestFixture
    private val second = 60L * 1024
    private fun observation(time: Long, units: List<TimelineObservedUnit> = emptyList(), credits: Long = 1000) =
        TimelineObservation(time, credits, (f.running() as TimelineValue.Blob).value, f.bytes(0, 0, 0, 0), units)
    private fun unit() = TimelineObservedUnit(77, TimelineValue.Vec3(256, 512, -1), 65000,
        25600, 25600, 65535, 0, "tank", 1, true)
    private inline fun temporary(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("timeline-session-test-")
        try { block(directory) }
        finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
    private inline fun <T> TimelineSession.running(block: (TimelineSession) -> T): T =
        try { block(this) } finally { close(); stoppedAsync().get(5, TimeUnit.SECONDS) }
    private fun await(message: String = "Session operation timed out", test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!test()) { check(System.nanoTime() < deadline) { message }; Thread.sleep(2) }
    }
    private fun request(context: TimelineContext, from: Long, until: Long, streamId: Long = 2) =
        TimelineStreamRequest(context.teamId, 1, streamId, streamId,
            context.copy(mode = TimelineStreamMode.HISTORY, acceptedFrom = from, acceptedUntil = until), 1_048_576)

    @Test fun `all accepted observations reach whole game archive including short observations between publications`() = temporary { directory ->
        val path = directory.resolve("facts.rwxr")
        TimelineSession(f.context(), path).running { session ->
            assertTrue(session.offer(observation(0)))
            assertTrue(session.offer(observation(1024, listOf(unit()))))
            assertTrue(session.offer(observation(2048)))
            assertEquals(path, session.finishAsync().get(5, TimeUnit.SECONDS))
            assertEquals(3L, session.statistics().processedObservations)
            assertEquals(2048L, session.archivedUntil)
            assertNull(session.unitKey(1))
            assertFalse(session.offer(observation(3072)))
            TimelineArchive.open(path).use { archive ->
                val historical = assertNotNull(archive.seek(1500))
                assertNotNull(historical.sample(1, 1, 1500))
                assertNull(historical.sample(1, 1, 2048))
                assertEquals(1024L, historical.state.objects.getValue(1).observedFrom)
                assertEquals(2048L, historical.state.objects.getValue(1).close?.until)
            }
            assertNull(session.failure)
        }
    }

    @Test fun `bounded LIVE retention still permits asynchronous old history while new observations continue`() = temporary { directory ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val path = directory.resolve("rolling.rwxr")
        TimelineSession(f.context(), path, TimelineSessionLimits(liveWindowSeconds = 2)).running { session ->
            for (tick in 0L..240L) {
                assertTrue(session.offer(observation(tick * 1024, listOf(unit()), 1000 + tick)))
                await { session.processedThrough == tick * 1024 }
            }
            await { session.archivedUntil >= second * 3 }
            assertTrue(assertNotNull(session.latest).header.retainedFrom >= second)
            assertFalse(Files.exists(path))
            session.beforeHistoryRead = { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
            val request = request(session.context, 0, second)
            assertNotNull(session.open(request)).use { feed ->
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val cursor = TimelineSendCursor(request.context, request.streamId, 1, null, true)
                    assertNull(feed.next(cursor), "Socket feed must return immediately while history worker is blocked")
                    assertTrue(session.offer(observation(241L * 1024, listOf(unit()), 9999)))
                    await { session.processedThrough == 241L * 1024 }
                    release.countDown()
                    var transaction: TimelinePublication? = null
                    await { transaction = feed.next(cursor); transaction != null }
                    val committed = TimelineStore(request.context).apply(transaction!!.header, transaction!!.operations)
                    assertEquals(0L, committed.header.retainedFrom); assertEquals(second, committed.header.retainedUntil)
                    assertEquals(TimelineValue.SInt(1000), TimelineProfile.sample(committed, 0, 1, 0))
                    assertEquals(0L, committed.header.controlEpoch)
                } finally { release.countDown() }
            }
            session.finishAsync().get(5, TimeUnit.SECONDS)
            assertEquals(0, session.statistics().historyReservations)
            assertNull(session.failure)
            TimelineArchive.open(path).use { archive ->
                assertTrue(archive.index.size >= 3, "Two short-window batches and the finish tail remain independently indexed")
                assertEquals(0L, archive.index.first().from)
                assertEquals(241L * 1024, archive.index.last().until)
                archive.index.zipWithNext().forEach { (a, b) -> assertEquals(a.until, b.from) }
                for (entry in archive.index) for (time in listOf(entry.from, entry.until - 1))
                    assertNotNull(assertNotNull(archive.seek(time)).sample(1, 1, time))
                assertEquals(TimelineValue.SInt(1240),
                    assertNotNull(archive.seek(241L * 1024 - 1)).sample(0, 1, 241L * 1024 - 1))
            }
        }
    }

    @Test fun `observation admission includes in flight work and fails instead of dropping or blocking`() = temporary { directory ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val path = directory.resolve("overflow.rwxr")
        TimelineSession(f.context(), path, TimelineSessionLimits(observationCount = 1)).running { session ->
            session.beforeObserve = { entered.countDown(); release.await() }
            try {
                assertTrue(session.offer(observation(0)))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertEquals(1, session.statistics().pendingObservations)
                assertFalse(session.offer(observation(1)))
                assertNotNull(session.failure)
                assertFalse(session.isAccepting)
                assertNull(session.latest)
                assertFailsWith<ExecutionException> { session.finishAsync().get(3, TimeUnit.SECONDS) }
            } finally { release.countDown() }
        }
        assertFalse(Files.exists(path))
    }

    @Test fun `observation byte budget is enforced before admission`() = temporary { directory ->
        val path = directory.resolve("oversize.rwxr")
        val input = observation(0, listOf(unit()))
        val estimate = TimelineSession.estimateObservationBytes(input)
        TimelineSession(f.context(), path, TimelineSessionLimits(observationBytes = estimate - 1)).running { session ->
            assertFalse(session.offer(input)); assertNotNull(session.failure)
            assertEquals(0L, session.statistics().offeredObservations)
            assertEquals(0, session.statistics().pendingObservations)
            assertEquals(0, session.statistics().peakPendingObservations)
            assertEquals(0L, session.statistics().peakPendingObservationBytes)
        }
    }

    @Test fun `reservation peaks include in flight work and remain after drain`() = temporary { directory ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        TimelineSession(f.context(), directory.resolve("peaks.rwxr"), TimelineSessionLimits(observationCount = 2)).running { session ->
            val first = observation(0); val next = observation(1024)
            session.beforeObserve = { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
            try {
                assertTrue(session.offer(first)); assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertTrue(session.offer(next))
                val reserved = session.statistics()
                val total = TimelineSession.estimateObservationBytes(first) + TimelineSession.estimateObservationBytes(next)
                assertEquals(2, reserved.peakPendingObservations)
                assertEquals(total, reserved.peakPendingObservationBytes)
                assertEquals(reserved.peakPendingObservationBytes, reserved.pendingObservationBytes)
                release.countDown(); session.finishAsync().get(5, TimeUnit.SECONDS)
                await { session.statistics().pendingObservations == 0 }
                val drained = session.statistics()
                assertEquals(2, drained.peakPendingObservations); assertEquals(total, drained.peakPendingObservationBytes)
                assertEquals(0L, drained.pendingObservationBytes)
                assertTrue(drained.peakPendingArchives in 1..session.limits.archiveCount)
                assertTrue(drained.peakPendingArchiveBytes in 1..session.limits.archiveBytes)
            } finally { release.countDown() }
        }
    }

    @Test fun `close orders its gate after in flight admission and all accepted reservations drain before stopped`() = temporary { directory ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            TimelineSession(f.context(), directory.resolve("close-gate.rwxr")).running { session ->
                session.beforeOfferEnqueue = { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
                val offering = workers.submit<Boolean> { session.offer(observation(0)) }
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val closing = workers.submit { session.close() }
                    // This artificial hook pauses the otherwise CPU-only admission critical section.
                    // Closing must not let workers exit and then allow this old admission to enqueue.
                    assertFailsWith<TimeoutException> { closing.get(50, TimeUnit.MILLISECONDS) }
                    release.countDown()
                    assertTrue(offering.get(2, TimeUnit.SECONDS)); closing.get(2, TimeUnit.SECONDS)
                    session.stoppedAsync().get(3, TimeUnit.SECONDS)
                    assertTrue(session.workersStopped)
                    assertEquals(0, session.statistics().pendingObservations)
                    assertEquals(0L, session.statistics().pendingObservationBytes)
                    assertEquals(0, session.statistics().pendingArchives)
                    assertFalse(session.offer(observation(1)))
                    assertNull(session.failure, "Intentional close is not a failed grant")
                } finally { release.countDown() }
            }
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun `explicit terminal fence archives the terminal instant and finish retains a frozen LIVE source`() = temporary { directory ->
        val path = directory.resolve("terminal.rwxr")
        TimelineSession(f.context(), path).running { session ->
            assertTrue(session.offer(observation(0)))
            val terminal = (f.finished(second) as TimelineValue.Blob).value
            fun ended(time: Long) = TimelineObservation(time, 1000, terminal, f.bytes(10, 10, 10, 10), emptyList())
            assertTrue(session.offer(ended(second)))
            assertTrue(session.offer(ended(second + 1)))
            session.finishAsync().get(5, TimeUnit.SECONDS)
            assertEquals(second + 1, session.archivedUntil)
            val frozen = assertNotNull(session.latest)
            assertEquals(second + 1, frozen.header.observedThrough)
            assertFalse(session.isAccepting)
            TimelineArchive.open(path).use { archive ->
                assertEquals(TimelineValue.Blob(terminal), assertNotNull(archive.seek(second)).sample(0, 2, second))
            }
            val request = TimelineStreamRequest(0, 1, 1, 1, session.context, 1_048_576)
            assertNotNull(session.open(request)).use { feed ->
                val tx = assertNotNull(feed.next(TimelineSendCursor(session.context, 1, 1, null, true)))
                assertEquals(second + 1, TimelineStore(session.context).apply(tx.header, tx.operations).header.observedThrough)
            }
            assertSame(frozen, session.latest)
        }
    }

    @Test fun `stalled archive deadline fails only its grant and keeps observation owner and another grant independent`() = temporary { directory ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val brokenPath = directory.resolve("blocked.rwxr")
        TimelineSession(f.context(), brokenPath, TimelineSessionLimits(operationTimeoutMillis = 250)).running { blocked ->
            TimelineSession(f.context(), directory.resolve("healthy.rwxr")).running { healthy ->
                blocked.beforeArchiveAppend = { entered.countDown(); release.await() }
                try {
                    assertTrue(blocked.offer(observation(0))); await { blocked.processedThrough == 0L }
                    assertTrue(blocked.offer(observation(second * 5)))
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    assertTrue(healthy.offer(observation(0)))
                    assertTrue(healthy.offer(observation(second)))
                    healthy.finishAsync().get(3, TimeUnit.SECONDS)
                    await { blocked.failure != null }
                    assertTrue(blocked.failure!!.message.orEmpty().contains("deadline"))
                    assertNull(blocked.latest); assertNull(healthy.failure)
                    assertEquals(second, healthy.archivedUntil)
                } finally { release.countDown() }
            }
        }
        assertFalse(Files.exists(brokenPath))
    }

    @Test fun `history windows reserve a bounded quota and enforce access grant and archive availability`() = temporary { directory ->
        val limit = TimelineSessionLimits(historyStateBytes = 65536, historyBytes = 65536)
        TimelineSession(f.context(), directory.resolve("quota.rwxr"), limit).running { session ->
            assertTrue(session.offer(observation(0, listOf(unit()))))
            await { session.processedThrough == 0L }
            assertTrue(session.offer(observation(second, listOf(unit()))))
            session.finishAsync().get(5, TimeUnit.SECONDS)
            assertNotNull(session.unitKey(1))
            assertNull(session.open(request(session.context, 0, second + 1)))
            assertNull(session.open(request(session.context.copy(accessId = TimelineBytes(ByteArray(16) { 2 })), 0, second)))
            val request = request(session.context, 0, second)
            val feed = assertNotNull(session.open(request))
            assertNull(session.open(request.copy(streamId = 3, requestId = 3)))
            assertEquals(65536L, session.statistics().reservedHistoryBytes)
            feed.close(); await { session.statistics().historyReservations == 0 }
            assertNotNull(session.open(request.copy(streamId = 4, requestId = 4))).close()
            assertNull(session.failure)
        }
    }

    @Test fun `LIVE feeds use independent committed cursors and Session never requires a socket to publish`() = temporary { directory ->
        val signals = AtomicLong()
        TimelineSession(f.context(), directory.resolve("live.rwxr"), onPublished = { signals.incrementAndGet() }).running { session ->
            assertTrue(session.offer(observation(0, listOf(unit()))))
            await { session.latest != null }
            val request = TimelineStreamRequest(0, 1, 1, 1, session.context, 1_048_576)
            val first = assertNotNull(session.open(request)); val late = assertNotNull(session.open(request.copy(streamId = 2)))
            first.use { feed -> late.use { other ->
                val initial = assertNotNull(feed.next(TimelineSendCursor(session.context, 1, 1, null, true)))
                val store = TimelineStore(session.context); val old = store.apply(initial.header, initial.operations)
                assertTrue(session.offer(observation(second, listOf(unit()), 900)))
                await { session.latest?.header?.observedThrough == second }
                val change = assertNotNull(feed.next(TimelineSendCursor(session.context, 1, 1, old, false)))
                val current = store.apply(change.header, change.operations)
                val lateJoin = assertNotNull(other.next(TimelineSendCursor(session.context, 2, 1, null, true)))
                assertEquals(TimelineTransactionKind.UPDATE, change.header.kind)
                assertEquals(TimelineTransactionKind.RESET, lateJoin.header.kind)
                val joined = TimelineStore(session.context).apply(lateJoin.header, lateJoin.operations)
                assertEquals(TimelineProfile.sample(current, 0, 1, second), TimelineProfile.sample(joined, 0, 1, second))
                assertTrue(signals.get() >= 2)
                assertNotNull(session.unitKey(1))
            } }
            session.finishAsync().get(5, TimeUnit.SECONDS)
        }
    }
}
