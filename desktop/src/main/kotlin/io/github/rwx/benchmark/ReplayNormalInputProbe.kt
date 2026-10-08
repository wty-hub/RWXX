package io.github.rwx.benchmark

import de.fabmax.kool.KoolContext
import io.github.rwx.session.GameSession

/** Injects at the same session boundary as the desktop UI; no direct game/camera field writes. */
internal object ReplayNormalInputProbe {
    var activatingDrag = false
        private set
    fun install(context: KoolContext, session: () -> GameSession) {
        if (System.getenv("RWX_NORMAL_INPUT_PROBE") != "1") return
        var previousStep = -1L
        context.onRender += {
            val start = ReplayPanBenchmark.startedAtNanos
            if (start != 0L && ReplayPanBenchmark.completedAtNanos == 0L) {
                val step = (System.nanoTime() - start) / 250_000_000L
                if (step != previousStep) {
                    previousStep = step
                    val game = session()
                    when ((step % 16).toInt()) {
                        0 -> { game.movePointer(640f, 360f); game.submitPointer(640f, 360f, true, 3) }
                        // GameUI's first displacement activates dragging and resets its anchor;
                        // the following displacement actually moves the camera. Record both.
                        1 -> {
                            activatingDrag = true
                            try { game.submitPointer(680f, 380f, true, 3) }
                            finally { activatingDrag = false }
                        }
                        2 -> game.submitPointer(610f, 335f, true, 3)
                        3 -> game.submitPointer(640f, 360f, false, 3)
                        4 -> game.submitKey(22, true)
                        5 -> game.submitKey(22, false)
                        6 -> game.submitKey(21, true)
                        7 -> game.submitKey(21, false)
                        8 -> game.submitKey(20, true)
                        9 -> game.submitKey(20, false)
                        10 -> game.submitKey(19, true)
                        11 -> game.submitKey(19, false)
                        12 -> game.submitMouseWheel(-120)
                        14 -> game.submitMouseWheel(120)
                    }
                }
            }
        }
    }
}
