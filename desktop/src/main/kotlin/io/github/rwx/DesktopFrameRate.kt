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
 * Target frame rate for the desktop render loop, resolved from the settings `maxFrameRate` /
 * `highRefreshRate` and then the environment override.
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
    highRefreshRate = settings.highRefreshRate,
    environmentOverride = environmentOverride,
)

internal fun resolveDesktopTargetFrameRate(
    maxFrameRate: Int,
    highRefreshRate: Boolean,
    environmentOverride: Int?,
): Int = environmentOverride
    ?: SettingsEngine.normalizeMaxFrameRate(maxFrameRate).takeIf { it > 0 }
    ?: legacyDesktopTargetFrameRate(highRefreshRate)

/**
 * Fallback when neither the environment override nor the game's `maxFrameRate` setting applies.
 *
 * 300 rather than 120: the software pacer throttles the whole loop, and the original build reaches about
 * 228 frames/s on this replay, so a 120 default made RWX's own interaction measurements a comparison
 * against a cap rather than against the workload (`docs/original-benchmark.md` §6.7 round 49). Pacing
 * stays in place so a fast machine does not spin; it just no longer sits below what the game can do.
 */
internal fun legacyDesktopTargetFrameRate(highRefreshRate: Boolean): Int = MAX_TARGET_FPS

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

/**
 * Ceiling for the software pacer in both the standard and high-refresh paths.
 *
 * Kept as one constant so the two paths cannot drift: the distinction used to cap the common path at 120,
 * which measured RWX against its own cap rather than against the workload.
 */
private const val MAX_TARGET_FPS = 300
