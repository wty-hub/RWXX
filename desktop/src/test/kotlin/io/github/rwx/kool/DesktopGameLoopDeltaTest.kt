package io.github.rwx.kool

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.test.*

class DesktopGameLoopDeltaTest {
    private fun withInterval(start: Long, end: Long, action: (EngineOwnerFrameTiming.Interval) -> Unit) {
        val interval = EngineOwnerFrameTiming.attachOwner()
        try {
            interval.begin(start, end)
            action(interval)
        } finally { EngineOwnerFrameTiming.detachOwner(interval) }
        assertNull(EngineOwnerFrameTiming.current())
    }

    @Test fun `integer timestamps carry fractions across millisecond boundaries including negative epochs`() {
        assertEquals(1, DesktopGameLoopDelta.originalMillisBetween(999_999L, 1_000_001L))
        assertEquals(0, DesktopGameLoopDelta.originalMillisBetween(1_000_001L, 1_999_999L))
        assertEquals(1, DesktopGameLoopDelta.originalMillisBetween(-1L, 0L))
        assertEquals(1, DesktopGameLoopDelta.originalMillisBetween(-1_000_001L, -1_000_000L))
        assertEquals(0, DesktopGameLoopDelta.originalMillisBetween(-999_999L, -1L))
        // A per-frame floor(end-start) would lose every one of these sub-ms intervals.
        val times = listOf(100_000L, 400_000L, 800_000L, 1_100_000L, 1_700_000L, 2_200_000L)
        assertEquals(listOf(0, 0, 1, 0, 1), times.zipWithNext { a, b ->
            DesktopGameLoopDelta.originalMillisBetween(a, b)
        })
    }

    @Test fun `mixed short and long exact intervals conserve elapsed whole milliseconds without float accumulation`() {
        var current = -8_000_000_123L
        val firstMillis = Math.floorDiv(current, 1_000_000L)
        var total = 0L
        repeat(10_000) { index ->
            val next = current + when (index % 4) {
                0 -> 333_333L
                1 -> 8_333_333L
                2 -> 137_123_456L
                else -> 3_600_000_000_000L
            }
            total += DesktopGameLoopDelta.originalMillisBetween(current, next)
            current = next
        }
        assertEquals(Math.floorDiv(current, 1_000_000L) - firstMillis, total)
    }

    @Test fun `original policy uses the same integer interval for both parameters and retains core long delta clamp`() {
        val resolver = DesktopGameLoopDelta(originalIntegerMillis = true)
        withInterval(900_000L, 17_200_000L) {
            resolver.resolve(.0163f, allowOwnerInterval = true)
            assertEquals(17, resolver.millisDelta)
            assertEquals((17 * .060000002f).toRawBits(), resolver.speedDelta.toRawBits())
            assertEquals(DesktopGameLoopDelta.Source.OriginalOwnerMillis, resolver.source)
        }
        withInterval(17_200_000L, 137_400_000L) {
            resolver.resolve(.1202f, allowOwnerInterval = true)
            assertEquals(120, resolver.millisDelta)
            assertEquals((120 * .060000002f).toRawBits(), resolver.speedDelta.toRawBits())
            assertTrue(resolver.speedDelta > 3f, "Original GameLogic keeps responsibility for its own clamp")
        }
        assertEquals(2L, resolver.originalOwnerCalls)
        assertEquals(0L, resolver.missingOwnerFallbacks)
    }

    @Test fun `disabled policy and missing context preserve every legacy Float bit and report fallback`() {
        val legacy = DesktopGameLoopDelta(originalIntegerMillis = false)
        val enabled = DesktopGameLoopDelta(originalIntegerMillis = true)
        val inputs = listOf(-.01f, -0f, 0f, .0009f, .008333333f, .017123457f, .125f)
        withInterval(0L, 999_000_000L) {
            for (delta in inputs) {
                legacy.resolve(delta, allowOwnerInterval = true)
                assertEquals((delta * 60f).coerceIn(0f, 3f).toRawBits(), legacy.speedDelta.toRawBits())
                assertEquals((delta * 1000f).roundToInt().coerceAtLeast(0), legacy.millisDelta)
                assertEquals(DesktopGameLoopDelta.Source.LegacyFloat, legacy.source)
            }
        }
        enabled.resolve(.017123457f, allowOwnerInterval = true)
        assertEquals((.017123457f * 60f).toRawBits(), enabled.speedDelta.toRawBits())
        assertEquals(17, enabled.millisDelta)
        assertEquals(1L, enabled.missingOwnerFallbacks)
        assertEquals(DesktopGameLoopDelta.Source.MissingOwnerFallback, enabled.source)
    }

    @Test fun `paused load first frame and explicit seek deltas never consume a live nonzero owner interval`() {
        val resolver = DesktopGameLoopDelta(originalIntegerMillis = true)
        withInterval(123_000_000L, 9_123_000_000L) {
            // prepareFrameAfterBackgroundLoad explicitly opts out; paused drawWorldOnly(0)
            // and core GameSession seek/menu stepping do not use the realtime adapter.
            resolver.resolve(0f, allowOwnerInterval = false)
            assertEquals(0f, resolver.speedDelta)
            assertEquals(0, resolver.millisDelta)
            resolver.resolve(.02f, allowOwnerInterval = false)
            assertEquals((.02f * 60f).toRawBits(), resolver.speedDelta.toRawBits())
            assertEquals(20, resolver.millisDelta)
            resolver.resolve(0f, allowOwnerInterval = true)
            assertEquals(0f, resolver.speedDelta, "An explicitly zero first tick must remain zero")
            assertEquals(0, resolver.millisDelta)
        }
        assertEquals(0L, resolver.originalOwnerCalls)
        assertEquals(3L, resolver.explicitCalls)
        assertEquals(0L, resolver.missingOwnerFallbacks)
    }

    private data class Sample(val start: Long, val end: Long, val delta: Float, val identity: Int)

    @Test fun `worker exposes exact original endpoints only during callback and never changes legacy Float delta`() {
        val samples = LinkedBlockingQueue<Sample>()
        val failures = AtomicReference<Throwable?>()
        val loop = EngineOwnerLoop({ 1_000_000L }, { delta ->
            val timing = assertNotNull(EngineOwnerFrameTiming.current())
            samples += Sample(timing.startNanos, timing.endNanos, delta, System.identityHashCode(timing))
        }, { failures.set(it) })
        try {
            assertNull(EngineOwnerFrameTiming.current(), "Timing must be isolated to the worker thread")
            val captured = List(8) { assertNotNull(samples.poll(5, TimeUnit.SECONDS)) }
            assertEquals(captured.first().start, captured.first().end)
            assertEquals(1, captured.map { it.identity }.toSet().size, "The worker should reuse one context")
            for (sample in captured) {
                val legacy = ((sample.end - sample.start).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
                assertEquals(legacy.toRawBits(), sample.delta.toRawBits())
            }
            assertTrue(captured.zipWithNext().all { (a, b) -> a.end == b.start })
            assertNull(loop.call { EngineOwnerFrameTiming.current() }, "Queued work is outside the realtime callback")
            assertNull(failures.get())
        } finally { loop.close() }
    }

    @Test fun `clock reset excludes serialized load time from exact endpoint interval without catchup`() {
        val initial = CountDownLatch(1)
        val afterReset = LinkedBlockingQueue<Sample>()
        val resetArmed = java.util.concurrent.atomic.AtomicBoolean()
        val failures = AtomicReference<Throwable?>()
        val loop = EngineOwnerLoop({ TimeUnit.SECONDS.toNanos(30) }, { delta ->
            val timing = assertNotNull(EngineOwnerFrameTiming.current())
            if (resetArmed.get()) afterReset += Sample(timing.startNanos, timing.endNanos, delta, 0)
            initial.countDown()
        }, { failures.set(it) })
        try {
            assertTrue(initial.await(5, TimeUnit.SECONDS))
            loop.call {
                assertNull(EngineOwnerFrameTiming.current())
                Thread.sleep(40)
                resetArmed.set(true)
                loop.resetClock()
            }
            val first = assertNotNull(afterReset.poll(5, TimeUnit.SECONDS))
            assertEquals(first.start, first.end)
            assertEquals(0f, first.delta)
            assertEquals(0, DesktopGameLoopDelta.originalMillisBetween(first.start, first.end))
            assertNull(failures.get())
        } finally { loop.close() }
    }

    @Test fun `failed callback clears context before error handler or queued recovery`() {
        val failed = CountDownLatch(1)
        val retained = AtomicReference<EngineOwnerFrameTiming.Interval?>()
        val errorHadContext = java.util.concurrent.atomic.AtomicBoolean(true)
        val loop = EngineOwnerLoop({ TimeUnit.SECONDS.toNanos(30) }, {
            retained.set(assertNotNull(EngineOwnerFrameTiming.current()))
            throw IllegalStateException("expected callback failure")
        }, {
            errorHadContext.set(EngineOwnerFrameTiming.current() != null)
            failed.countDown()
        })
        try {
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertFalse(errorHadContext.get())
            assertNull(loop.call { EngineOwnerFrameTiming.current() })
            val timing = assertNotNull(retained.get())
            assertFalse(timing.active)
            assertEquals(0L, timing.startNanos)
            assertEquals(0L, timing.endNanos)
        } finally { loop.close() }
    }
}
