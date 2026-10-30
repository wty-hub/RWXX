package io.github.rwx.kool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.render.canvas.KoolCanvasViewport

internal fun normalizeDesktopDisplayScale(scale: Float): Float =
    scale.takeIf { it.isFinite() && it > 0f } ?: 1f

/**
 * The game records and receives input in framebuffer pixels. Apply the window's render scale to
 * HUD layout and tracked font sizes only; changing the canvas or pointer scale would apply DPI twice.
 * Called exclusively by the engine owner, including while the game is paused.
 */
internal fun applyDesktopViewport(
    engine: GameEngine,
    viewport: KoolCanvasViewport,
    displayScale: Float,
): Boolean {
    val width = viewport.width.coerceAtLeast(1)
    val height = viewport.height.coerceAtLeast(1)
    val density = normalizeDesktopDisplayScale(displayScale) *
        ((engine as? GameLogic)?.densityScaleMultiplier ?: 1f)
    if (engine.screenWidth == width.toFloat() && engine.screenHeight == height.toFloat() &&
        engine.densityScaleRaw == density && engine.screenScale == engine.getScreenScale()
    ) return false

    engine.densityScaleRaw = density
    engine.updateWindowResolution(width, height, engine.renderSurfaceScale)
    return true
}
