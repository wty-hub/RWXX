package io.github.rwx.kool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.render.canvas.KoolPaint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUiScaleTest {
    @Test
    fun `Retina doubles HUD and fonts while keeping framebuffer and camera zoom`() = withEngine { engine ->
        val viewport = KoolCanvasViewport(2936, 1912)
        applyDesktopViewport(engine, viewport, 1f)
        val paint = KoolPaint()
        engine.updatePaintTextSize(paint, 16f)
        val center = engine.viewpointX + engine.halfVisibleWorldWidth to
            engine.viewpointY + engine.halfVisibleWorldHeight
        val zoom = engine.zoom
        val tick = engine.currentTick

        assertTrue(applyDesktopViewport(engine, viewport, 2f))

        assertEquals(2f, engine.screenScale)
        assertEquals(500f, engine.sidebarWidth)
        assertEquals(32f, paint.k())
        assertEquals(2936f, engine.screenWidth)
        assertEquals(1912f, engine.screenHeight)
        assertEquals(zoom, engine.zoom)
        assertEquals(center, engine.viewpointX + engine.halfVisibleWorldWidth to
            engine.viewpointY + engine.halfVisibleWorldHeight)
        assertEquals(tick, engine.currentTick)
        assertFalse(applyDesktopViewport(engine, viewport, 2f), "Stable DPI must not relayout every frame")
    }

    @Test
    fun `paused HUD follows monitor changes with unchanged framebuffer and saved user scale`() = withEngine { engine ->
        val viewport = KoolCanvasViewport(2560, 1600)
        engine.isPaused = true
        engine.isStopped = true
        engine.settingsEngine.uiRenderScale = 1.25f
        engine.densityScaleMultiplier = 0.8f
        val paint = KoolPaint()
        engine.updatePaintTextSize(paint, 16f)

        for (density in listOf(2f, 1.5f, 1f, 2f)) {
            assertTrue(applyDesktopViewport(engine, viewport, density))
            assertEquals(density, engine.screenScale, 0.0001f)
            assertEquals(16f * density, paint.k(), 0.0001f)
            assertEquals(1.25f, engine.settingsEngine.uiRenderScale, "DPI must not overwrite the preference")
        }
    }

    @Test
    fun `fractional display density respects manual scale and render surface scale`() = withEngine { engine ->
        val viewport = KoolCanvasViewport(1920, 1080)
        engine.settingsEngine.uiRenderScale = 1.5f
        engine.renderSurfaceScale = 0.75f
        applyDesktopViewport(engine, viewport, 1.25f)
        assertEquals(1.875f, engine.screenScale)
        assertEquals(0.75f, engine.renderSurfaceScale)

        engine.settingsEngine.uiRenderScale = 0.75f
        assertTrue(applyDesktopViewport(engine, viewport, 1.25f))
        assertEquals(0.9375f, engine.screenScale)
    }

    @Test
    fun `invalid display metrics fall back without shrinking HUD to zero`() = withEngine { engine ->
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            applyDesktopViewport(engine, KoolCanvasViewport(1280, 720), density)
            assertEquals(1f, engine.screenScale)
        }
        applyDesktopViewport(engine, KoolCanvasViewport(0, 0), 2f)
        assertEquals(1f, engine.screenWidth)
        assertEquals(1f, engine.screenHeight)
        assertTrue(engine.screenScale.isFinite())
    }

    private fun withEngine(block: (GameLogic) -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = instance.get(null)
        instance.set(null, null)
        try {
            val engine = GameLogic()
            engine.settingsEngine = SettingsEngine::class.java.getDeclaredConstructor().run {
                isAccessible = true
                newInstance()
            }
            engine.densityScaleRaw = 1f
            engine.settingsEngine.renderDensity = 1f
            engine.settingsEngine.renderDoubleScale = false
            engine.settingsEngine.uiRenderScale = 1f
            engine.zoom = 1.5f
            engine.targetZoom = 1.5f
            block(engine)
        } finally {
            instance.set(null, previous)
        }
    }
}
