package io.github.rwx.ui.host

import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.scene.Scene
import io.github.rwx.i18n.I18n
import io.github.rwx.ui.ResponsiveContentWidth
import io.github.rwx.ui.ResponsiveViewportHeight
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.component.*
import io.github.rwx.ui.model.*

class MultiplayerSceneHost(
    private val model: SettingsModel = SettingsModel(),
    private val onAction: (MultiplayerAction) -> Unit = {},
) {
    private val rooms = mutableStateListOf<MultiplayerRoomItem>()
    private val statusText = mutableStateOf("")
    private val lobbyKind = mutableStateOf(MultiplayerLobbyKind.Original)

    fun updateRooms(
        rooms: List<MultiplayerRoomItem>,
        statusText: String = "",
        lobbyKind: MultiplayerLobbyKind = this.lobbyKind.value,
    ) {
        this.lobbyKind.value = lobbyKind
        this.statusText.value = statusText
        this.rooms.replaceAllIncrementally(rooms)
    }

    fun dispatch(action: MultiplayerAction) = onAction(action)

    fun createScene(): Scene = UiScene(MULTIPLAYER_SCENE_NAME) {
        addPanelSurface(PanelStyle.Menu, "multiplayer-panel", model, showBackdropLabels = false) { theme ->
            val metrics = multiplayerLayoutMetrics()
            MultiplayerRoomList(
                model = MultiplayerRoomListModel(
                    title = I18n.multiplayer.title(),
                    lobbyKind = lobbyKind.use(),
                    rooms = rooms.use(),
                    statusText = statusText.use(),
                ),
                theme = theme,
                contentWidth = metrics.contentWidth,
                viewportHeight = metrics.viewportHeight,
                actions = MultiplayerRoomListActions(
                    onJoinRoom = { dispatch(MultiplayerAction.JoinRoom(it)) },
                    onBack = { dispatch(MultiplayerAction.Back) },
                    onRefresh = { dispatch(MultiplayerAction.Refresh) },
                    onSwitchLobby = { dispatch(MultiplayerAction.SwitchLobby(it)) },
                    onHostGame = { dispatch(MultiplayerAction.HostGame) },
                    onJoinDirect = { dispatch(MultiplayerAction.JoinDirect) },
                    onRejoinLastGame = { dispatch(MultiplayerAction.RejoinLastGame) },
                    onConfigure = { dispatch(MultiplayerAction.ConfigurePlayerName) },
                ),
            )
        }
    }

    companion object {
        const val MULTIPLAYER_SCENE_NAME: String = "multiplayer"
    }
}

private data class MultiplayerLayoutMetrics(
    val contentWidth: Dp,
    val viewportHeight: Dp,
)

private fun UiScope.multiplayerLayoutMetrics(): MultiplayerLayoutMetrics {
    val contentWidth = ResponsiveContentWidth(
        defaultWidth = UiTheme.Layout.multiplayerRoomRowWidth,
        minWidth = UiTheme.Layout.multiplayerMinContentWidth,
        maxWidth = UiTheme.Layout.multiplayerMaxContentWidth,
    )
    val compact = contentWidth.value < MULTIPLAYER_COMPACT_WIDTH_DP
    val controlRow = UiTheme.Layout.menuButtonHeight.value + UiTheme.Spacing.sm.value
    val filterRows = if (compact) 3f else 1f
    val extraFooterRows = if (compact) 4f else 1f // compact lobby/footer rows, or the return button row
    return MultiplayerLayoutMetrics(
        contentWidth = contentWidth,
        viewportHeight = ResponsiveViewportHeight(
            defaultHeight = UiTheme.Layout.scrollViewportHeight,
            minHeight = UiTheme.Layout.multiplayerMinViewportHeight,
            maxHeight = UiTheme.Layout.multiplayerMaxViewportHeight,
            verticalChrome = Dp(176f + filterRows * controlRow +
                extraFooterRows * UiTheme.Layout.menuButtonHeight.value + MULTIPLAYER_STATUS_HEIGHT.value),
        ),
    )
}
