package io.github.rwx

import de.fabmax.kool.platform.Lwjgl3Context
import java.util.concurrent.locks.LockSupport
import io.github.rwx.kool.vulkan.VulkanBackendMetrics

private val guardedRenderPark = System.getenv("RWX_GUARD_SHORT_RENDER_PARK") == "1"
private val stableFrameDeadlines = System.getenv("RWX_STABLE_FRAME_DEADLINES") == "1"
private val waitForFreshFrames = System.getenv("RWX_WAIT_FOR_FRESH_FRAME") == "1"
private val postInputFrameWait = System.getenv("RWX_POST_INPUT_FRAME_WAIT") == "1"
@Volatile private var frameAvailabilityProbe: (() -> Boolean)? = null
private var reportedFreshFrameWait = false

internal fun installKoolFrameAvailability(probe: (() -> Boolean)?) {
    frameAvailabilityProbe = probe
}

private fun waitForFreshFrame(context: Lwjgl3Context, period: Long) {
    if (!waitForFreshFrames || postInputFrameWait || period <= 0L) return
    val probe = frameAvailabilityProbe ?: return
    if (probe()) return
    if (!reportedFreshFrameWait) {
        reportedFreshFrameWait = true
        println("RWXFreshFrameWait active=true maxWaitNanos=${minOf(period / 2, 1_500_000L)}")
    }
    val traceStart = VulkanBackendMetrics.stageStart()
    val budget = minOf(period / 2, 1_500_000L)
    val deadline = System.nanoTime() + budget
    try {
        while (!context.windowSubsystem.isCloseRequested && System.nanoTime() < deadline && !probe()) {
            Thread.onSpinWait()
        }
    } finally { VulkanBackendMetrics.stageEnd("fresh-frame-wait", traceStart, budget) }
}

/** Version-checked Kool adapter. This only schedules presentation; engine delta remains wall-clock time. */
fun checkKoolFrameRateLimits(context: Lwjgl3Context, previousStart: Long): Long {
    val focusedPeriod = if (context.maxFrameRate > 0) 1_000_000_000L / context.maxFrameRate else 0L
    val unfocusedPeriod = if (context.windowNotFocusedFrameRate > 0)
        1_000_000_000L / context.windowNotFocusedFrameRate else focusedPeriod
    val now = System.nanoTime()
    val initiallyFocused = context.window.flags.isFocused || context.window.isMouseOverWindow
    val requestedStart = previousStart + if (initiallyFocused) focusedPeriod else unfocusedPeriod
    val waited = now < requestedStart
    if (waited) waitForKoolFrame(context, previousStart + focusedPeriod, previousStart + unfocusedPeriod)
    waitForFreshFrame(context, if (context.window.flags.isFocused || context.window.isMouseOverWindow)
        focusedPeriod else unfocusedPeriod)
    val actualStart = System.nanoTime()
    if (!stableFrameDeadlines || !waited) return actualStart
    val focused = context.window.flags.isFocused || context.window.isMouseOverWindow
    val period = if (focused) focusedPeriod else unfocusedPeriod
    return desktopScheduledFrameStartNanos(previousStart + period, actualStart, period)
}

/** Called by the Kool overlay: yield the CPU during the coarse part of a frame wait. */
fun waitForKoolFrame(context: Lwjgl3Context, untilFocused: Long, untilUnfocused: Long) {
    val traceStart = VulkanBackendMetrics.stageStart()
    val requested = if (traceStart != 0L) maxOf(untilFocused, untilUnfocused) - traceStart else 0L
    var parked = 0L
    var parks = 0L
    try {
    while (!context.windowSubsystem.isCloseRequested) {
        val window = context.window
        val focused = window.flags.isFocused || window.isMouseOverWindow
        val remaining = (if (focused) untilFocused else untilUnfocused) - System.nanoTime()
        if (remaining <= 0L) return
        if (remaining > if (guardedRenderPark) 1_500_000L else 100_000L) {
            // Poll again at least every 2 ms so focus changes and close requests stay responsive.
            val parkStart = if (traceStart != 0L) System.nanoTime() else 0L
            LockSupport.parkNanos(minOf(remaining - (if (guardedRenderPark) 1_500_000L else 100_000L), 2_000_000L))
            if (traceStart != 0L) { parked += System.nanoTime() - parkStart; parks++ }
            window.pollEvents()
        } else {
            Thread.onSpinWait()
        }
    }
    } finally {
        VulkanBackendMetrics.stageEnd("frame-pacing-wait", traceStart, requested, parked, parks)
    }
}
