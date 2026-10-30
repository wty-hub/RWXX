package io.github.rwx.app

import io.github.rwx.session.InGameMenuOptionSnapshot
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.CoreUiEvent
import io.github.rwx.ui.CoreUiEventQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InGamePauseControllerTest {
    @Test
    fun `engine requests wait for UI dispatch and duplicate requests capture the menu only once`() {
        CoreUiEventQueue.drain {}
        val fixture = Fixture()
        val uiThread = Thread.currentThread()
        Thread {
            CoreUiEventQueue.requestInGamePause()
            CoreUiEventQueue.requestInGamePause()
        }.apply { start(); join() }

        assertEquals(AppScreen.InGame, fixture.screen)
        assertTrue(fixture.pendingCaptures.isEmpty())
        CoreUiEventQueue.drain { event ->
            if (event == CoreUiEvent.InGamePauseRequested) fixture.controller.open()
        }
        assertEquals(1, fixture.pendingCaptures.size)
        assertTrue(fixture.menuUpdates.isEmpty())
        assertEquals(AppScreen.InGame, fixture.screen)

        fixture.completeCapture()

        assertEquals(AppScreen.Paused, fixture.screen)
        assertEquals(listOf(fixture.options), fixture.menuUpdates)
        assertEquals(listOf(uiThread), fixture.navigationThreads)
        fixture.controller.open()
        assertTrue(fixture.pendingCaptures.isEmpty())
        assertEquals(AppScreen.Paused, fixture.screen)
    }

    @Test
    fun `stale requests do not open pause on other screens or resume an already paused game`() {
        val fixture = Fixture()
        for (screen in AppScreen.entries.filterNot { it == AppScreen.InGame }) {
            fixture.changeScreen(screen)
            fixture.controller.open()
            assertEquals(screen, fixture.screen)
        }
        assertTrue(fixture.pendingCaptures.isEmpty())
        assertTrue(fixture.menuUpdates.isEmpty())
    }

    @Test
    fun `dialogs and map loading block pause before capture and again at completion`() {
        val fixture = Fixture()
        fixture.modalOpen = true
        fixture.controller.open()
        fixture.modalOpen = false
        fixture.loading = true
        fixture.controller.open()
        assertTrue(fixture.pendingCaptures.isEmpty())

        fixture.loading = false
        fixture.controller.open()
        fixture.modalOpen = true
        fixture.completeCapture()
        assertEquals(AppScreen.InGame, fixture.screen)
        assertTrue(fixture.menuUpdates.isEmpty())

        fixture.modalOpen = false
        fixture.controller.open()
        fixture.loading = true
        fixture.completeCapture()
        assertEquals(AppScreen.InGame, fixture.screen)
        assertTrue(fixture.menuUpdates.isEmpty())

        fixture.loading = false
        assertEquals(AppScreen.InGame, fixture.screen)
        fixture.controller.open()
        fixture.completeCapture()
        assertEquals(AppScreen.Paused, fixture.screen)
    }

    @Test
    fun `leaving and returning to the match invalidates the old capture completion`() {
        val fixture = Fixture()
        fixture.controller.open()
        val oldCompletion = fixture.pendingCaptures.removeFirst()
        fixture.changeScreen(AppScreen.MainMenu)
        fixture.changeScreen(AppScreen.InGame)
        fixture.controller.open()

        oldCompletion(Result.success(listOf(InGameMenuOptionSnapshot(18, "Old export option"))))
        assertEquals(AppScreen.InGame, fixture.screen)
        assertTrue(fixture.menuUpdates.isEmpty())
        fixture.completeCapture()
        assertEquals(AppScreen.Paused, fixture.screen)
        assertEquals(listOf(fixture.options), fixture.menuUpdates)
    }

    @Test
    fun `failed menu capture reports the failure and lets a later pause request retry`() {
        val fixture = Fixture()
        val error = IllegalStateException("engine stopped")
        fixture.controller.open()
        fixture.pendingCaptures.removeFirst()(Result.failure(error))
        assertEquals(AppScreen.InGame, fixture.screen)
        assertEquals(listOf<Throwable>(error), fixture.failures)
        assertTrue(fixture.menuUpdates.isEmpty())

        fixture.controller.open()
        fixture.completeCapture()
        assertEquals(AppScreen.Paused, fixture.screen)
    }

    private class Fixture {
        var screen = AppScreen.InGame
        var modalOpen = false
        var loading = false
        val options = listOf(InGameMenuOptionSnapshot(12, "Save"), InGameMenuOptionSnapshot(2, "Settings"))
        val pendingCaptures = ArrayDeque<(Result<List<InGameMenuOptionSnapshot>>) -> Unit>()
        val menuUpdates = mutableListOf<List<InGameMenuOptionSnapshot>>()
        val navigationThreads = mutableListOf<Thread>()
        val failures = mutableListOf<Throwable>()
        val controller = InGamePauseController(
            currentScreen = { screen },
            isModalOverlayOpen = { modalOpen },
            isGameLoading = { loading },
            requestMenuOptions = { pendingCaptures.addLast(it) },
            updateMenu = { menuUpdates += it },
            navigateTo = {
                navigationThreads += Thread.currentThread()
                changeScreen(it)
            },
            onFailure = { failures += it },
        )

        fun changeScreen(next: AppScreen) {
            screen = next
            controller.onScreenChanged(next)
        }

        fun completeCapture() = pendingCaptures.removeFirst()(Result.success(options))
    }
}
