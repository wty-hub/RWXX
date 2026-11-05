package io.github.rwx.app

import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.CoreUiEvent
import io.github.rwx.ui.CoreUiEventQueue
import io.github.rwx.ui.ScreenNavigator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleRoomCloseControllerTest {
    @Test
    fun `network closure returns to lobby before showing the reason from every screen`() {
        CoreUiEventQueue.drain {}
        val uiThread = Thread.currentThread()
        for (screen in AppScreen.entries) {
            val navigator = ScreenNavigator(screen)
            var startPending = true
            var joinPending = true
            var roomOpen = true
            val messages = mutableListOf<String>()
            val controller = BattleRoomCloseController(
                cancelPendingStart = { startPending = false },
                cancelPendingJoin = { joinPending = false },
                closeRoom = { roomOpen = false },
                exitToLobby = {
                    assertTrue(!startPending && !joinPending && !roomOpen)
                    navigator.navigateTo(gameExitDestination(isMultiplayer = true))
                },
                showMessage = {
                    assertEquals(uiThread, Thread.currentThread())
                    assertEquals(AppScreen.Multiplayer, navigator.current)
                    messages += it
                },
            )
            Thread { CoreUiEventQueue.requestBattleRoomClosed("Kicked", "Kicked by host") }
                .apply { start(); join() }

            assertEquals(screen, navigator.current)
            assertTrue(messages.isEmpty())
            CoreUiEventQueue.drain { event ->
                if (event is CoreUiEvent.BattleRoomClosed) controller.handle(event.reason, event.message)
            }

            assertEquals(AppScreen.Multiplayer, navigator.current)
            assertEquals(listOf("Kicked\nKicked by host"), messages)
            controller.handle(null, null)
            assertEquals(AppScreen.Multiplayer, navigator.current)
            assertEquals(1, messages.size)
        }
    }
}
