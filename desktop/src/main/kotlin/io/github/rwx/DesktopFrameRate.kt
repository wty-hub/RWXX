package io.github.rwx

import com.corrodinggames.rts.gameFramework.SettingsEngine

/**
 * Benchmark override for the desktop render loop. `RWX_DESKTOP_TARGET_FPS` wins over the older
 * `RWX_SLICK_TARGET_FPS` so existing benchmark scripts keep working.
 */
internal val desktopTargetFrameRateOverride: Int? =
    (System.getenv("RWX_DESKTOP_TARGET_FPS") ?: System.getenv("RWX_SLICK_TARGET_FPS"))
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

/**
 * Target frame rate for the desktop render loop. Auto uses a 300 FPS limit; other frame rates
 * can be selected explicitly (or requested by a benchmark override).
 *
 * Kool cannot rely on the swapchain as its only throttle: on macOS that is MoltenVK's FIFO present
 * mode, which caps the whole game at the display refresh rate and ignores the in-game maximum frame
 * rate setting.
 */
internal fun desktopTargetFrameRate(
    settings: SettingsEngine = SettingsEngine.getInstance(),
    environmentOverride: Int? = desktopTargetFrameRateOverride,
): Int = resolveDesktopTargetFrameRate(
    maxFrameRate = settings.maxFrameRate,
    environmentOverride = environmentOverride,
    batterySaving = settings.batterySaving,
    vsync = settings.renderVsync,
    refreshRate = DesktopWindowActivity.state.refreshRate,
)

internal fun resolveDesktopTargetFrameRate(
    maxFrameRate: Int,
    environmentOverride: Int?,
    batterySaving: Boolean = false,
    vsync: Boolean = false,
    refreshRate: Int = 0,
): Int {
    val target = environmentOverride?.takeIf { it > 0 }
        ?: if (batterySaving) 30 else
            SettingsEngine.normalizeMaxFrameRate(maxFrameRate).takeIf { it > 0 } ?: DEFAULT_TARGET_FPS
    // Pace CPU frame production too when VSync bounds actual presentation to the current monitor.
    return if (vsync) minOf(target, refreshRate.takeIf { it > 0 } ?: 60) else target
}

/**
 * Avoid producing hundreds of discarded CPU frames behind a lower presentation limit. Keep the
 * original normal loop cadence as a floor even at 30 FPS or in the background: networking, input
 * and replay updates still run independently of presentation, with a real elapsed-time delta.
 * The existing battery-saving option retains its original engine cadence.
 */
internal fun desktopEngineFramePeriodNanos(
    targetFrameRate: Int,
    highRefreshRate: Boolean,
    batterySaving: Boolean,
): Long = when {
    batterySaving -> 32_258_064L
    !highRefreshRate -> 16_393_442L
    else -> 1_000_000_000L / targetFrameRate.coerceIn(61, 300)
}

/** Software pacing also honours vertical sync when the GL canvas swaps without waiting. */
internal fun desktopFramePeriodNanos(targetFrameRate: Int, vsync: Boolean, refreshRate: Int): Long {
    val fps = if (vsync) minOf(targetFrameRate, refreshRate.takeIf { it > 0 } ?: 60) else targetFrameRate
    return 1_000_000_000L / fps.coerceAtLeast(1)
}

/** Slow frames start a new interval instead of causing a burst of catch-up frames. */
internal fun desktopNextFrameDelayNanos(frameStartNanos: Long, nowNanos: Long, periodNanos: Long): Long =
    (frameStartNanos + periodNanos - nowNanos).coerceAtLeast(0L)

/** Correct small wake-up drift; a missed interval starts a fresh schedule, without catch-up ticks. */
internal fun desktopScheduledFrameStartNanos(scheduledNanos: Long, actualNanos: Long, periodNanos: Long): Long =
    if (periodNanos > 0 && actualNanos >= scheduledNanos && actualNanos - scheduledNanos < periodNanos / 2)
        scheduledNanos else actualNanos

private const val DEFAULT_TARGET_FPS = 300
