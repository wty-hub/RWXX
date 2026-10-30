package com.corrodinggames.rts.gameFramework.ui

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.ui.CoreUiEvent
import io.github.rwx.ui.CoreUiEventQueue
import io.github.rwx.ui.InGameMenuController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InGamePauseBridgeTest {
    @Test
    fun `engine pause clears the legacy menu and pointer gesture before notifying the UI`() {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = instance.get(null)
        CoreUiEventQueue.drain {}
        instance.set(null, null)
        try {
            val engine = GameLogic().also { it.gameUI = GameUI() }
            val gameUi = engine.gameUI
            gameUi.isDraggingSelection = true
            gameUi.isSelectionBoxActive = true
            gameUi.isRightClickDrag = true
            gameUi.isMousePressed = true
            var notified = false
            var stateWasReset = false
            var callbackThread: Thread? = null
            CoreUiEventQueue.setOverlayRequestHandler { event ->
                if (event == CoreUiEvent.InGamePauseRequested) {
                    notified = true
                    stateWasReset = !gameUi.isDraggingSelection && !gameUi.isSelectionBoxActive &&
                        !gameUi.isRightClickDrag && !gameUi.isMousePressed
                    callbackThread = Thread.currentThread()
                }
            }

            val engineThread = Thread { InGameMenuController().requestPause(engine) }
            engineThread.start()
            engineThread.join()

            assertTrue(notified)
            assertTrue(stateWasReset, "legacy state must be cleared before publishing the request")
            assertEquals(engineThread, callbackThread)
            assertFalse(gameUi.isDraggingSelection)
            val events = mutableListOf<CoreUiEvent>()
            CoreUiEventQueue.drain(events::add)
            assertEquals(listOf<CoreUiEvent>(CoreUiEvent.InGamePauseRequested), events)
        } finally {
            CoreUiEventQueue.setOverlayRequestHandler(null)
            CoreUiEventQueue.drain {}
            instance.set(null, previous)
        }
    }

    @Test
    fun `a missing engine does not publish a pause request`() {
        CoreUiEventQueue.drain {}
        InGameMenuController().requestPause(null)
        val events = mutableListOf<CoreUiEvent>()
        CoreUiEventQueue.drain(events::add)
        assertTrue(events.isEmpty())
    }
}
