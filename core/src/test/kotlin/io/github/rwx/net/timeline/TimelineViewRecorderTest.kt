package io.github.rwx.net.timeline

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TimelineViewRecorderTest {
    private val f = TimelineTestFixture
    private inline fun temporary(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("timeline-recorder-")
        try { block(directory) }
        finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) { check(System.nanoTime() < until) { "Recorder condition timed out" }; Thread.sleep(2) }
    }

    @Test fun `blocked writer keeps only processing and latest pending while short lived observations survive coalescing`() = temporary { dir ->
        val context = f.context(); val store = TimelineStore(context)
        val first = f.commit(store, f.root() + f.unit(), observed = 5, sealed = 5)
        val appeared = f.commit(store, f.unit(2, from = 10), observed = 10, sealed = 5, highWater = 2)
        val disappeared = f.commit(store, listOf(TimelineOperation.Close(2, 20, TimelineCloseKind.ENDED)),
            observed = 20, sealed = 20, control = 2)
        val last = f.commit(store, emptyList(), observed = 30, sealed = 30)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val recorder = TimelineViewRecorder(dir.resolve("coalesced.rwxr"))
        recorder.beforeProcess = { if (calls.getAndIncrement() == 0) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
        try {
            assertTrue(recorder.record(first)); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(recorder.record(appeared)); assertTrue(recorder.record(disappeared)); assertTrue(recorder.record(last))
            val stats = recorder.statistics()
            assertEquals(1, stats.processingStates); assertEquals(1, stats.pendingStates); assertEquals(2L, stats.coalescedStates)
            val finished = recorder.finishAsync()
            assertFalse(finished.isDone); assertFalse(recorder.record(last))
            release.countDown()
            val result = finished.get(3, TimeUnit.SECONDS)
            assertEquals(0L, result.gapCount); assertTrue(result.partial); assertTrue(result.unsealedTail)
            TimelineArchive.open(result.path).use { archive ->
                assertNotNull(archive.seek(15)?.sample(2, 1, 15))
                assertNull(archive.seek(25)?.sample(2, 1, 25))
                assertEquals(30L, archive.index.last().until)
            }
        } finally { release.countDown(); recorder.close(); recorder.stoppedAsync().get(3, TimeUnit.SECONDS) }
    }

    @Test fun `a reset that loses retained history writes a real index gap and reports partial recording`() = temporary { dir ->
        val context = f.context()
        val early = f.commit(TimelineStore(context), f.root() + f.unit(), observed = 10, sealed = 10)
        val late = f.commit(TimelineStore(context), f.root(from = 30) + f.unit(from = 30), observed = 40, sealed = 40, from = 30)
        val recorder = TimelineViewRecorder(dir.resolve("gap.rwxr"))
        try {
            assertTrue(recorder.record(early)); await { recorder.statistics().sliceCount == 1L }
            assertTrue(recorder.record(late))
            val result = recorder.finishAsync().get(3, TimeUnit.SECONDS)
            assertEquals(1L, result.gapCount); assertTrue(result.partial)
            TimelineArchive.open(result.path).use { archive ->
                assertEquals(listOf(0L to 10L, 30L to 40L), archive.index.map { it.from to it.until })
                assertNotNull(archive.seek(5)); assertNull(archive.seek(20)); assertNotNull(archive.seek(35))
            }
        } finally { recorder.close(); recorder.stoppedAsync().get(3, TimeUnit.SECONDS) }
    }

    @Test fun `terminal observation followed by the exclusive fence records the result without an unsealed tail`() = temporary { dir ->
        val store = TimelineStore(f.context())
        val first = f.commit(store, f.root() + f.unit(), observed = 0)
        val terminal = f.commit(store, listOf(f.step(0, 2, f.finished(20), from = 20, revision = 1),
            TimelineOperation.Close(1, 20, TimelineCloseKind.ENDED)), observed = 20, sealed = 20, control = 2)
        val fence = f.commit(store, emptyList(), observed = 21, sealed = 21)
        val recorder = TimelineViewRecorder(dir.resolve("terminal.rwxr"))
        try {
            assertTrue(recorder.record(first)); assertTrue(recorder.record(terminal)); assertTrue(recorder.record(fence))
            val result = recorder.finishAsync().get(3, TimeUnit.SECONDS)
            assertFalse(result.partial); assertFalse(result.unsealedTail); assertEquals(0L, result.gapCount)
            TimelineArchive.open(result.path).use { archive ->
                assertEquals(f.finished(20), assertNotNull(archive.seek(20)).sample(0, 2, 20))
                assertNull(archive.seek(21))
            }
        } finally { recorder.close(); recorder.stoppedAsync().get(3, TimeUnit.SECONDS) }
    }

    @Test fun `grant and pinned map changes fail even if their revisions would be coalesced away`() = temporary { dir ->
        val state = f.commit(TimelineStore(f.context()), f.root() + f.unit(), observed = 10, sealed = 10)
        val contexts = listOf(state.context.copy(mode = TimelineStreamMode.HISTORY),
            state.context.copy(accessId = TimelineBytes(ByteArray(16) { 9 })),
            state.context.copy(mapRecord = TimelineRecord(state.context.mapRecord.fields)), state.context.copy(teamId = 1))
        contexts.forEachIndexed { index, context ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val recorder = TimelineViewRecorder(dir.resolve("changed-$index.rwxr"))
            recorder.beforeProcess = { entered.countDown(); release.await() }
            try {
                assertTrue(recorder.record(state)); assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertFalse(recorder.record(state.copy(context = context)))
                assertFalse(recorder.record(state))
                assertFailsWith<ExecutionException> { recorder.finishAsync().get(2, TimeUnit.SECONDS) }
                assertFalse(Files.exists(recorder.path))
            } finally { release.countDown(); recorder.close(); recorder.stoppedAsync().get(3, TimeUnit.SECONDS) }
        }
    }

    @Test fun `deadline fails promptly while close never waits for an uninterruptible disk stage`() = temporary { dir ->
        val state = f.commit(TimelineStore(f.context()), f.root() + f.unit(), observed = 10, sealed = 10)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val recorder = TimelineViewRecorder(dir.resolve("blocked.rwxr"), operationTimeoutMillis = 100)
        recorder.beforeProcess = {
            entered.countDown()
            while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { /* Simulated uninterruptible I/O. */ }
        }
        try {
            assertTrue(recorder.record(state)); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val result = recorder.finishAsync()
            assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
            recorder.close()
            assertFalse(recorder.stoppedAsync().isDone, "Cleanup must not be reported before the blocked worker actually stops")
            assertNotNull(recorder.failure); assertFalse(Files.exists(recorder.path))
        } finally { release.countDown(); recorder.close(); recorder.stoppedAsync().get(3, TimeUnit.SECONDS) }
        assertEquals(0L, Files.list(dir).use { it.count() })
    }

    @Test fun `existing recording and empty input are never overwritten or labelled complete`() = temporary { dir ->
        val path = dir.resolve("existing.rwxr"); Files.writeString(path, "existing content")
        val state = f.commit(TimelineStore(f.context()), f.root() + f.unit(), observed = 10, sealed = 10)
        val existing = TimelineViewRecorder(path)
        try {
            assertTrue(existing.record(state))
            assertFailsWith<ExecutionException> { existing.finishAsync().get(3, TimeUnit.SECONDS) }
            assertEquals("existing content", Files.readString(path))
        } finally { existing.close(); existing.stoppedAsync().get(3, TimeUnit.SECONDS) }
        val empty = TimelineViewRecorder(dir.resolve("empty.rwxr"))
        try {
            assertFailsWith<ExecutionException> { empty.finishAsync().get(3, TimeUnit.SECONDS) }
            assertFalse(Files.exists(empty.path))
        } finally { empty.close(); empty.stoppedAsync().get(3, TimeUnit.SECONDS) }
    }
}
