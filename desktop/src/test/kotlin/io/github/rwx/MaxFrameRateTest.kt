package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertEquals

class MaxFrameRateTest {
    @Test
    fun `deadline retains a slightly late phase but resets after a missed interval`() {
        val period = 3_333_333L
        val deadline = 100_000_000L
        assertEquals(deadline, desktopScheduledFrameStartNanos(deadline, deadline + 100_000, period))
        assertEquals(deadline + period, desktopScheduledFrameStartNanos(deadline, deadline + period, period))
        assertEquals(deadline + period / 2, desktopScheduledFrameStartNanos(deadline, deadline + period / 2, period))
        assertEquals(deadline - 1, desktopScheduledFrameStartNanos(deadline, deadline - 1, period))
        assertEquals(deadline + 10, desktopScheduledFrameStartNanos(deadline, deadline + 10, 0))
    }

    @Test
    fun `manual choice sets the desktop target frame rate`() {
        for (fps in listOf(30, 60, 120, 144, 240, 300)) {
            assertEquals(fps, resolveDesktopTargetFrameRate(fps, highRefreshRate = true, environmentOverride = null))
        }
    }

    /**
     * The unpaced fallback used to be 120 unless the high-refresh setting was on, which put the software
     * pacer below what the game can actually do: the original build reaches ~228 frames/s on the benchmark
     * replay, so RWX was measuring its own cap instead of the workload
     * (`docs/original-benchmark.md` §6.7 round 49). Both paths now default to the same 300 ceiling, and the
     * high-refresh flag no longer changes the fallback.
     */
    @Test
    fun `auto defaults to the same ceiling whether or not high refresh is set`() {
        assertEquals(300, resolveDesktopTargetFrameRate(0, highRefreshRate = false, environmentOverride = null))
        assertEquals(300, resolveDesktopTargetFrameRate(0, highRefreshRate = true, environmentOverride = null))
        assertEquals(300, resolveDesktopTargetFrameRate(75, highRefreshRate = true, environmentOverride = null))
    }

    @Test
    fun `environment override has the highest priority`() {
        assertEquals(75, resolveDesktopTargetFrameRate(60, highRefreshRate = false, environmentOverride = 75))
    }
}
