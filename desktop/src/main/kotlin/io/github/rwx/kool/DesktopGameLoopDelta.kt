package io.github.rwx.kool

import kotlin.math.roundToInt

/** Desktop-only outer-clock adapter; the original simulation and its counters remain untouched. */
internal class DesktopGameLoopDelta(
    val originalIntegerMillis: Boolean = System.getenv("RWX_ORIGINAL_INTEGER_MS_GAME_DELTA") == "1",
) {
    enum class Source { LegacyFloat, OriginalOwnerMillis, ExplicitDelta, MissingOwnerFallback }

    var speedDelta = 0f
        private set
    var millisDelta = 0
        private set
    var source = Source.LegacyFloat
        private set
    var originalOwnerCalls = 0L
        private set
    var missingOwnerFallbacks = 0L
        private set
    var explicitCalls = 0L
        private set

    fun resolve(deltaSeconds: Float, allowOwnerInterval: Boolean) {
        // Loading/paused/seek/explicit first-frame draws keep their supplied delta, even if a
        // caller happens to run inside a live owner's callback. An explicitly supplied zero is
        // never replaced with a nonzero interval.
        if (originalIntegerMillis && allowOwnerInterval && deltaSeconds != 0f) {
            val interval = EngineOwnerFrameTiming.current()
            if (interval != null) {
                millisDelta = originalMillisBetween(interval.startNanos, interval.endNanos)
                // Slick u.render passes this exact constant and the same integer milliseconds.
                // GameLogic retains its original delta clamp, replay accumulator and simulation.
                speedDelta = millisDelta * 0.060000002f
                source = Source.OriginalOwnerMillis
                originalOwnerCalls++
                return
            }
            source = Source.MissingOwnerFallback
            missingOwnerFallbacks++
            if (missingOwnerFallbacks == 1L) {
                println("RWXGameDelta fallback=missing-owner-interval legacyFloat=true")
            }
        } else if (originalIntegerMillis) {
            source = Source.ExplicitDelta
            explicitCalls++
        } else source = Source.LegacyFloat
        // Preserve the previous Float arithmetic, clamping and independent integer argument.
        speedDelta = (deltaSeconds * 60f).coerceIn(0f, 3f)
        millisDelta = (deltaSeconds * 1000f).roundToInt().coerceAtLeast(0)
    }

    companion object {
        /** Difference of absolute integer timestamps, preserving sub-ms carry across frames. */
        fun originalMillisBetween(startNanos: Long, endNanos: Long): Int =
            (Math.floorDiv(endNanos, 1_000_000L) - Math.floorDiv(startNanos, 1_000_000L)).toInt()
    }
}
