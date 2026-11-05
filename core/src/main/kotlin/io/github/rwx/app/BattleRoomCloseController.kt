package io.github.rwx.app

/** Handles network-initiated room closure even when the room's game or an overlay is visible. */
internal class BattleRoomCloseController(
    private val cancelPendingStart: () -> Unit,
    private val cancelPendingJoin: () -> Unit,
    private val closeRoom: () -> Unit,
    private val exitToLobby: () -> Unit,
    private val showMessage: (String) -> Unit,
) {
    fun handle(reason: String?, message: String?) {
        // Invalidate acknowledgements before leaving so an outstanding start cannot reopen play.
        cancelPendingStart()
        cancelPendingJoin()
        closeRoom()
        exitToLobby()
        listOfNotNull(reason, message).joinToString("\n").takeIf { it.isNotBlank() }?.let(showMessage)
    }
}
