package io.github.rwx.app

import io.github.rwx.session.InGameMenuOptionSnapshot
import io.github.rwx.ui.AppScreen

/** Handles both navigation keys and queued engine requests on the UI thread. */
internal class InGamePauseController(
    private val currentScreen: () -> AppScreen,
    private val isModalOverlayOpen: () -> Boolean,
    private val isGameLoading: () -> Boolean,
    private val requestMenuOptions: ((Result<List<InGameMenuOptionSnapshot>>) -> Unit) -> Unit,
    private val updateMenu: (List<InGameMenuOptionSnapshot>) -> Unit,
    private val navigateTo: (AppScreen) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private var pendingRequest: Any? = null

    fun open() {
        // A queued engine request may arrive after leaving the match or after opening a dialog.
        // Opening is idempotent: another request while paused must never resume the game.
        if (!canOpen() || pendingRequest != null) return
        val request = Any()
        pendingRequest = request
        requestMenuOptions { result ->
            // requestSessionTask delivers this completion on the UI thread. The match or a modal
            // may have changed while its engine owner captured the menu, so check again here.
            if (pendingRequest !== request) return@requestMenuOptions
            pendingRequest = null
            if (!canOpen()) return@requestMenuOptions
            result.onSuccess { options ->
                updateMenu(options)
                navigateTo(AppScreen.Paused)
            }.onFailure(onFailure)
        }
    }

    fun onScreenChanged(screen: AppScreen) {
        if (screen != AppScreen.InGame) pendingRequest = null
    }

    private fun canOpen(): Boolean =
        currentScreen() == AppScreen.InGame && !isModalOverlayOpen() && !isGameLoading()
}
