package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertEquals

class MaxFrameRateTest {
    @Test
    fun `owner deadlines default on and explicit overrides preserve the combined pacing option`() {
        assertEquals(true, resolveStableOwnerFrameDeadlines(null, null))
        assertEquals(false, resolveStableOwnerFrameDeadlines("0", null))
        assertEquals(false, resolveStableOwnerFrameDeadlines(null, "0"))
        assertEquals(true, resolveStableOwnerFrameDeadlines(null, "1"))
        assertEquals(false, resolveStableOwnerFrameDeadlines("0", "1"))
        assertEquals(true, resolveStableOwnerFrameDeadlines("1", "0"))
        assertEquals(false, resolveStableOwnerFrameDeadlines("invalid", "1"))
    }

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
            assertEquals(fps, resolveDesktopTargetFrameRate(fps, environmentOverride = null))
        }
    }

    @Test
    fun `auto and unsupported settings use the default 300 fps limit`() {
        for (value in listOf(0, 75, -1, 999)) {
            assertEquals(300, resolveDesktopTargetFrameRate(value, environmentOverride = null))
        }
    }

    @Test
    fun `battery saving caps presentation as well as engine work`() {
        for (value in listOf(0, 30, 60, 120, 144, 240, 300)) {
            assertEquals(30, resolveDesktopTargetFrameRate(value, environmentOverride = null, batterySaving = true))
        }
    }

    @Test
    fun `environment override has the highest priority`() {
        assertEquals(75, resolveDesktopTargetFrameRate(60, environmentOverride = 75))
        assertEquals(300, resolveDesktopTargetFrameRate(0, environmentOverride = 300, batterySaving = true))
        assertEquals(300, resolveDesktopTargetFrameRate(0, environmentOverride = 0))
    }

    @Test
    fun `engine production follows manual high refresh without starving low fps games`() {
        for (fps in listOf(120, 144, 240, 300)) {
            assertEquals(1_000_000_000L / fps, desktopEngineFramePeriodNanos(fps, true, false))
        }
        for (fps in listOf(15, 30, 60)) {
            assertEquals(16_393_442L, desktopEngineFramePeriodNanos(fps, true, false))
        }
        assertEquals(3_333_333L, desktopEngineFramePeriodNanos(1000, true, false))
        assertEquals(16_393_442L, desktopEngineFramePeriodNanos(300, false, false))
        assertEquals(32_258_064L, desktopEngineFramePeriodNanos(300, true, true))
    }

    @Test
    fun `vertical sync caps CPU production to the current display as well as presentation`() {
        for (refresh in listOf(60, 120, 144)) {
            val target = resolveDesktopTargetFrameRate(300, null, vsync = true, refreshRate = refresh)
            assertEquals(refresh, target)
            assertEquals(1_000_000_000L / maxOf(61, refresh), desktopEngineFramePeriodNanos(target, true, false))
        }
        assertEquals(30, resolveDesktopTargetFrameRate(30, null, vsync = true, refreshRate = 144))
        assertEquals(60, resolveDesktopTargetFrameRate(300, null, vsync = true, refreshRate = 0))
        assertEquals(300, resolveDesktopTargetFrameRate(300, null, vsync = false, refreshRate = 60))
    }
}
