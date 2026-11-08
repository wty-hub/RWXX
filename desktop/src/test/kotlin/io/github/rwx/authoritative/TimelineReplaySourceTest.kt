package io.github.rwx.authoritative

import io.github.rwx.net.authoritative.ViewUnitOrder
import io.github.rwx.net.authoritative.ViewActionCommand
import io.github.rwx.net.authoritative.CommandTarget
import io.github.rwx.net.authoritative.OrderKind
import io.github.rwx.net.timeline.*
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TimelineReplaySourceTest {
    private fun n(id: Int, value: Long) = TimelineMessages.numberField(id, value, true)
    private fun b(id: Int, bytes: ByteArray) = TimelineMessages.bytesField(id, TimelineBytes(bytes))
    private fun state(): TimelineState {
        val map = TimelineRecord(listOf(n(1, 1), n(2, 1), n(3, 1), n(4, 20), n(5, 20), b(6, byteArrayOf(0)),
            n(7, 1), b(8, byteArrayOf(0, 1)), b(9, byteArrayOf(0, 0)), b(10, byteArrayOf(0))))
        val context = TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }), TimelineCanonical.mapKey(map), map, 0, 60)
        val publisher = TimelinePublisher(context)
        fun observation(time: Long, x: Long) = TimelineObservation(time, 10240, TimelineBytes(byteArrayOf(0, 0, 0, 0)),
            TimelineBytes(byteArrayOf(0)), listOf(TimelineObservedUnit(1, TimelineValue.Vec3(x, 256, 0), 0,
                25600, 25600, 65535, 0, "tank", 1, true)))
        publisher.observe(observation(0, 256)); publisher.observe(observation(100, 512))
        assertNotNull(publisher.publish(0))
        // Disk replay fixtures require sealed history, including this short final interval.
        return assertNotNull(publisher.publish(5_000_000_000))
    }
    private fun write(path: Path, windows: List<Pair<Long, Long>> = listOf(0L to 40L, 40L to 100L)) {
        val state = state()
        TimelineArchive.Writer(path, state.context).use { writer ->
            windows.forEach { writer.append(state, it.first, it.second) }; writer.finish()
        }
    }
    private inline fun temporary(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("timeline-replay-")
        try { block(directory) }
        finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
    private fun frame(source: TimelineReplaySource, pane: Int = 0, time: Long = 0): TimelineDisplayFrame {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            source.samplePane(pane, time)?.let { return it }
            check(source.failureMessage == null) { source.failureMessage.orEmpty() }
            check(System.nanoTime() < deadline) { "Replay frame timed out" }
            Thread.sleep(2)
        }
    }
    private class Access(path: Path) : TimelineReplayAccess {
        private val reader = TimelineArchive.open(path)
        override val context get() = reader.context
        override val index get() = reader.index
        @Volatile var beforeRead: ((Long) -> Unit)? = null
        val reads = AtomicInteger(); val closes = AtomicInteger()
        override fun seek(time: Long): TimelineState? { reads.incrementAndGet(); beforeRead?.invoke(time); return reader.seek(time)?.state }
        override fun close() { reader.close(); closes.incrementAndGet() }
    }

    @Test fun `offline read only playback seeks both directions and never invokes any input path`() = temporary { dir ->
        val path = dir.resolve("game.rwxr"); write(path)
        val access = Access(path)
        val source = TimelineReplaySource(access, speed = 0.3, clock = { 0L })
        try {
            source.playback.pause(nowNanos = 0)
            val zero = frame(source)
            assertTrue(source.readOnly); assertTrue(zero.readOnly); assertFalse(zero.snapshot.units.single().controllable)
            assertEquals(7, source.protocolVersion)
            source.playback.seek(0, 75, 0)
            assertNull(source.latestTimelineFrame())
            val forward = frame(source)
            assertEquals(75L, forward.time)
            source.playback.seek(0, 25, 0)
            assertNull(source.latestTimelineFrame())
            val reverse = frame(source)
            source.playback.seek(0, 75, 0)
            val repeated = frame(source)
            assertEquals(forward.snapshot.units, repeated.snapshot.units)
            assertTrue(reverse.snapshot.units.single().x < forward.snapshot.units.single().x)
            assertFailsWith<IllegalStateException> { source.selectionChanged(emptyList()) }
            assertFailsWith<IllegalStateException> { source.send(ViewUnitOrder(1, 1, OrderKind.STOP, listOf(1), CommandTarget.None)) }
            assertFailsWith<IllegalStateException> { source.sendAction(ViewActionCommand(1, 1, 1, 1, 1, CommandTarget.None)) }
            source.playback.seek(0, 1000, 0)
            assertEquals(99L, frame(source).time)
            assertTrue(source.playback.position(0, 0).paused)
        } finally { source.close(); source.stoppedAsync().get(3, TimeUnit.SECONDS) }
        assertEquals(1, access.closes.get()); assertNull(source.latestTimelineFrame())
    }

    @Test fun `archive gaps remain unavailable and are not replaced by the preceding picture`() = temporary { dir ->
        val path = dir.resolve("gaps.rwxr"); write(path, listOf(10L to 40L, 60L to 100L))
        val source = TimelineReplaySource(Access(path), clock = { 0L })
        try {
            source.playback.pause(nowNanos = 0)
            assertEquals(10L, frame(source).time); assertEquals(2, source.gapCount)
            source.playback.seek(0, 50, 0)
            assertNull(source.latestTimelineFrame()); assertNull(source.latestSnapshot()); assertNull(source.latestActionPanel())
            assertEquals(0, source.residentWindows())
            source.playback.seek(0, 75, 0)
            assertEquals(75L, frame(source).time)
            source.playback.seek(0, 0, 0)
            assertNull(source.latestTimelineFrame())
        } finally { source.close(); source.stoppedAsync().get(3, TimeUnit.SECONDS) }
    }

    @Test fun `a late disk result is discarded and at most four current pane windows stay resident`() = temporary { dir ->
        val path = dir.resolve("generation.rwxr"); write(path)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val access = Access(path)
        access.beforeRead = { if (it < 40) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
        val source = TimelineReplaySource(access, clock = { 0L })
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            source.playback.pause(nowNanos = 0); source.playback.seek(0, 75, 0)
            repeat(10) { assertNull(source.latestTimelineFrame()) }
            assertTrue(source.pendingLoads() <= 1)
            release.countDown()
            assertEquals(75L, frame(source).time)
            assertEquals(2, access.reads.get())
            val other = listOf(source.playback.addPane(10), source.playback.addPane(30), source.playback.addPane(90))
            other.forEach { frame(source, it) }
            assertEquals(4, source.residentWindows()); assertTrue(source.pendingLoads() <= 4)
            source.playback.removePane(other[0]); source.samplePane(0, 0)
            assertEquals(3, source.residentWindows())
        } finally { release.countDown(); source.close(); source.stoppedAsync().get(3, TimeUnit.SECONDS) }
    }

    @Test fun `close is nonblocking during a stuck read and stopped reports actual cleanup`() = temporary { dir ->
        val path = dir.resolve("blocked.rwxr"); write(path)
        val access = Access(path)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        access.beforeRead = {
            entered.countDown()
            while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
        }
        val source = TimelineReplaySource(access, clock = { 0L })
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            source.close()
            assertFalse(source.isConnected); assertNull(source.latestTimelineFrame()); assertFalse(source.stoppedAsync().isDone)
            assertEquals(0, source.residentWindows())
        } finally { release.countDown(); source.close(); source.stoppedAsync().get(3, TimeUnit.SECONDS) }
        assertEquals(1, access.closes.get())
    }

    @Test fun `only complete container3 wire7 archives open and constructor errors close their reader`() = temporary { dir ->
        val path = dir.resolve("valid.rwxr"); write(path)
        val old = dir.resolve("old.rwxr"); Files.copy(path, old)
        RandomAccessFile(old.toFile(), "rw").use { it.seek(6); it.writeShort(6) }
        assertFailsWith<ProtocolException> { TimelineReplaySource(old.toFile()) }
        val truncated = dir.resolve("truncated.rwxr"); Files.write(truncated, Files.readAllBytes(path).dropLast(1).toByteArray())
        assertFailsWith<ProtocolException> { TimelineReplaySource(truncated.toFile()) }
        val access = Access(path)
        assertFailsWith<IllegalArgumentException> { TimelineReplaySource(access, speed = 0.1) }
        assertEquals(1, access.closes.get())
        val empty = dir.resolve("empty.rwxr")
        TimelineArchive.Writer(empty, state().context).use { it.finish() }
        assertFailsWith<IOException> { TimelineReplaySource(empty.toFile()) }
    }
}
