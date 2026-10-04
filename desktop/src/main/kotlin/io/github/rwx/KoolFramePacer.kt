package io.github.rwx

import de.fabmax.kool.platform.Lwjgl3Context
import java.util.concurrent.locks.LockSupport

/** Called by the Kool overlay: yield the CPU during the coarse part of a frame wait. */
fun waitForKoolFrame(context: Lwjgl3Context, untilFocused: Long, untilUnfocused: Long) {
    while (!context.windowSubsystem.isCloseRequested) {
        val window = context.window
        val focused = window.flags.isFocused || window.isMouseOverWindow
        val remaining = (if (focused) untilFocused else untilUnfocused) - System.nanoTime()
        if (remaining <= 0L) return
        if (remaining > 100_000L) {
            // Poll again at least every 2 ms so focus changes and close requests stay responsive.
            LockSupport.parkNanos(minOf(remaining - 100_000L, 2_000_000L))
            window.pollEvents()
        } else {
            Thread.onSpinWait()
        }
    }
}
