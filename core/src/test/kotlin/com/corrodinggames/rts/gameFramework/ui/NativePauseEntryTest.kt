package com.corrodinggames.rts.gameFramework.ui

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.units.actions.AttackMoveAction
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.InputController
import com.corrodinggames.rts.gameFramework.InputHandler
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.graphics.Texture
import io.github.rwx.render.canvas.KoolPaint
import io.github.rwx.ui.CoreUiEvent
import io.github.rwx.ui.CoreUiEventQueue
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePauseEntryTest {
    @Test
    fun `HUD pause requests the app menu in normal games and replays without opening the old panel`() {
        for (replay in listOf(false, true)) withRenderer(replay) { engine, renderer ->
            engine.gameUI.isSelectionBoxActive = true
            engine.gameUI.isMousePressed = true
            engine.gameUI.selectionBoxStartX = 10f
            engine.gameUI.selectionBoxStartY = 10f

            renderer.a(0f, false)

            assertEquals(listOf<CoreUiEvent>(CoreUiEvent.InGamePauseRequested), events())
            assertFalse(engine.gameUI.isDraggingSelection)
            assertFalse(engine.gameUI.isSelectionBoxActive)
            assertFalse(engine.gameUI.isMousePressed)
            renderer.a(0f, false)
            assertTrue(events().isEmpty(), "a held pointer must not reopen the pause menu")
        }
    }

    @Test
    fun `native Escape cancels the current action before requesting pause and does not repeat while held`() =
        withRenderer { engine, renderer ->
            engine.gameUI.currentAction = AttackMoveAction()
            engine.setKeyState(111, true)
            renderer.a(0f, false)
            assertTrue(events().isEmpty())

            engine.setKeyState(111, false)
            engine.setKeyState(111, true)
            renderer.a(0f, false)
            assertEquals(listOf<CoreUiEvent>(CoreUiEvent.InGamePauseRequested), events())
            assertFalse(engine.gameUI.isDraggingSelection)
            renderer.a(0f, false)
            assertTrue(events().isEmpty())
        }

    @Test
    fun `the configured menu shortcut uses the same pause request`() = withRenderer { engine, renderer ->
        engine.settingsEngine.keyboardSupport = true
        engine.inputController = InputController()
        var pressed = true
        // Bind a different key to prove the configured action is used, independently of raw Escape.
        engine.inputController.w.a(29, 0, 0, false)
        InputController.b = object : InputHandler() {
            override fun a(keyCode: Int, modifierFlags: Int, allowExtraModifiers: Boolean) = pressed && keyCode == 29
        }
        renderer.a(0f, false)
        assertEquals(listOf<CoreUiEvent>(CoreUiEvent.InGamePauseRequested), events())
        renderer.a(0f, false)
        assertTrue(events().isEmpty())
        pressed = false
        renderer.a(0f, false)
        pressed = true
        renderer.a(0f, false)
        assertEquals(listOf<CoreUiEvent>(CoreUiEvent.InGamePauseRequested), events())
    }

    @Test
    fun `a leftover native menu flag is handed off instead of drawing the legacy panel`() =
        withRenderer { engine, renderer ->
            engine.gameUI.isDraggingSelection = true
            renderer.a(0f, false)
            assertFalse(engine.gameUI.isDraggingSelection)
            assertEquals(listOf<CoreUiEvent>(CoreUiEvent.InGamePauseRequested), events())
        }

    private fun events() = mutableListOf<CoreUiEvent>().also { CoreUiEventQueue.drain(it::add) }

    private fun withRenderer(replay: Boolean = false, block: (GameLogic, GameInterfaceRenderer) -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousInput = InputController.b
        CoreUiEventQueue.drain {}
        instance.set(null, null)
        try {
            val engine = GameLogic()
            engine.settingsEngine = SettingsEngine::class.java.getDeclaredConstructor().run {
                isAccessible = true
                newInstance()
            }
            engine.settingsEngine.keyboardSupport = false
            engine.screenScale = 1f
            engine.currentScreenWidthPixels = 800f
            engine.replayEngine = ReplayEngine().also { playback ->
                for (fieldName in listOf("P", "isReplaying")) {
                    ReplayEngine::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
                        .setBoolean(playback, replay)
                }
            }
            engine.renderGraphicsEngine = Proxy.newProxyInstance(
                GraphicsEngine::class.java.classLoader, arrayOf(GraphicsEngine::class.java),
            ) { _, method, _ ->
                check(method.returnType == Void.TYPE) { "unexpected graphics query: ${method.name}" }
                null
            } as GraphicsEngine
            engine.gameUI = GameUI().also { it.unitRangePaint = KoolPaint() }
            val renderer = object : GameInterfaceRenderer(engine, engine.gameUI) {
                override fun initializeUI() = Unit
            }
            renderer.pauseTexture = Texture().apply { p = 32; q = 32 }
            renderer.leaderboardTexture = Texture().apply { p = 32; q = 32 }
            block(engine, renderer)
        } finally {
            CoreUiEventQueue.drain {}
            InputController.b = previousInput
            instance.set(null, previousEngine)
        }
    }
}
