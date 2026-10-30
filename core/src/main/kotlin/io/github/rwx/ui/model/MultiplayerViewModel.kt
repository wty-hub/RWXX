package io.github.rwx.ui.model

import io.github.rwx.i18n.I18n
import io.github.rwx.ui.AppScreen

data class MultiplayerRoomItem(
    val roomId: String,
    val hostName: String,
    val mapName: String,
    val playersLabel: String,
    val versionLabel: String,
    val requiresPassword: Boolean,
    val hasMods: Boolean,
    val transportLabel: String,
    val stateLabel: String,
    val joinAddress: String = roomId,
    val rejoinAddress: String? = null,
    val originalServerId: String? = null,
    val requiresJoinInput: Boolean = false,
    val joinInputHint: String = "",
    val infoText: String = "",
    val currentPlayers: Int = -1,
    val maxPlayers: Int = 0,
) {
    // Discovery values remain unchanged for protocol handling and room filtering.
    val localizedStateLabel: String
        get() = when (stateLabel.trim().lowercase()) {
            "battleroom" -> I18n.multiplayer.filterStatus.waiting()
            "ingame" -> I18n.multiplayer.filterStatus.inGame()
            "chat" -> I18n.multiplayer.state.chat()
            "locked" -> I18n.multiplayer.state.locked()
            "unknown", "" -> I18n.multiplayer.unknown()
            else -> stateLabel
        }

    val localizedTransportLabel: String
        get() = when (transportLabel.lowercase()) {
            "lan" -> I18n.multiplayer.transport.lan()
            "dedicated" -> I18n.multiplayer.transport.dedicated()
            "internet" -> I18n.multiplayer.transport.internet()
            "relay" -> I18n.multiplayer.transport.relay()
            else -> transportLabel
        }
}

enum class MultiplayerStatusFilter {
    All,
    Waiting,
    InGame,
    Other,
    ;

    override fun toString(): String = when (this) {
        All -> I18n.multiplayer.filterStatus.all()
        Waiting -> I18n.multiplayer.filterStatus.waiting()
        InGame -> I18n.multiplayer.filterStatus.inGame()
        Other -> I18n.multiplayer.filterStatus.other()
    }
}

enum class MultiplayerSlotFilter {
    All,
    Open,
    ;

    override fun toString(): String = when (this) {
        All -> I18n.multiplayer.filterSlots.all()
        Open -> I18n.multiplayer.filterSlots.`open`()
    }
}

enum class MultiplayerAccessFilter {
    All,
    Open,
    Password,
    ;

    override fun toString(): String = when (this) {
        All -> I18n.multiplayer.filterAccess.all()
        Open -> I18n.multiplayer.filterAccess.`open`()
        Password -> I18n.multiplayer.filterAccess.password()
    }
}

enum class MultiplayerModFilter {
    All,
    None,
    Required,
    ;

    override fun toString(): String = when (this) {
        All -> I18n.multiplayer.filterMods.all()
        None -> I18n.multiplayer.filterMods.none()
        Required -> I18n.multiplayer.filterMods.required()
    }
}

object MultiplayerRoomBrowser {
    fun visibleRooms(
        rooms: List<MultiplayerRoomItem>,
        query: String,
        status: MultiplayerStatusFilter,
        slots: MultiplayerSlotFilter,
        access: MultiplayerAccessFilter,
        mods: MultiplayerModFilter,
    ): List<MultiplayerRoomItem> {
        val trimmedQuery = query.trim()
        return rooms.filter { room ->
            matchesQuery(room, trimmedQuery) &&
                    matchesStatus(room, status) &&
                    matchesSlots(room, slots) &&
                    matchesAccess(room, access) &&
                    matchesMods(room, mods)
        }
    }

    private fun matchesQuery(room: MultiplayerRoomItem, query: String): Boolean {
        if (query.isBlank()) return true
        return room.hostName.contains(query, ignoreCase = true) ||
                room.mapName.contains(query, ignoreCase = true)
    }

    private fun matchesStatus(room: MultiplayerRoomItem, status: MultiplayerStatusFilter): Boolean {
        val state = room.stateLabel.trim().lowercase()
        return when (status) {
            MultiplayerStatusFilter.All -> true
            MultiplayerStatusFilter.Waiting -> state == "battleroom"
            MultiplayerStatusFilter.InGame -> state == "ingame"
            MultiplayerStatusFilter.Other -> state != "battleroom" && state != "ingame"
        }
    }

    private fun matchesSlots(room: MultiplayerRoomItem, slots: MultiplayerSlotFilter): Boolean =
        when (slots) {
            MultiplayerSlotFilter.All -> true
            MultiplayerSlotFilter.Open -> room.currentPlayers >= 0 && room.currentPlayers < room.maxPlayers
        }

    private fun matchesAccess(room: MultiplayerRoomItem, access: MultiplayerAccessFilter): Boolean =
        when (access) {
            MultiplayerAccessFilter.All -> true
            MultiplayerAccessFilter.Open -> !room.requiresPassword
            MultiplayerAccessFilter.Password -> room.requiresPassword
        }

    private fun matchesMods(room: MultiplayerRoomItem, mods: MultiplayerModFilter): Boolean =
        when (mods) {
            MultiplayerModFilter.All -> true
            MultiplayerModFilter.None -> !room.hasMods
            MultiplayerModFilter.Required -> room.hasMods
        }
}

enum class MultiplayerLobbyKind {
    Original,
    P2P,
    ;

    val label: String
        get() = when (this) {
            Original -> I18n.multiplayer.lobby.original()
            P2P -> I18n.multiplayer.lobby.p2p()
        }
}


data class MultiplayerRoomListModel(
    val title: String,
    val lobbyKind: MultiplayerLobbyKind,
    val rooms: List<MultiplayerRoomItem>,
    val statusText: String = "",
)

/** Actions the user can take on the multiplayer room-list screen. */
sealed interface MultiplayerAction {
    /** Navigate back to the main menu. */
    data object Back : MultiplayerAction

    /** Refresh the room list. */
    data object Refresh : MultiplayerAction

    /** Switch between the original RW lobby and RWX's P2P lobby. */
    data class SwitchLobby(val lobbyKind: MultiplayerLobbyKind) : MultiplayerAction

    /** Host a game in the active lobby. */
    data object HostGame : MultiplayerAction

    /** Join by a manually-entered address / room code in the active lobby. */
    data object JoinDirect : MultiplayerAction

    data object RejoinLastGame : MultiplayerAction

    /** Configure the player name used by multiplayer sessions. */
    data object ConfigurePlayerName : MultiplayerAction

    /** Join a specific room by its ID. */
    data class JoinRoom(val roomId: String) : MultiplayerAction
}

/** Outcome produced by [MultiplayerNavigation] for each [MultiplayerAction]. */
sealed interface MultiplayerOutcome {
    data class Navigate(val screen: AppScreen) : MultiplayerOutcome
    data object RefreshRequested : MultiplayerOutcome
    data class SwitchLobby(val lobbyKind: MultiplayerLobbyKind) : MultiplayerOutcome
    data object HostGameRequested : MultiplayerOutcome
    data object JoinDirectRequested : MultiplayerOutcome
    data object RejoinLastGameRequested : MultiplayerOutcome
    data object ConfigurePlayerNameRequested : MultiplayerOutcome
    data class JoinRoom(val roomId: String) : MultiplayerOutcome
}

/** Pure function that maps each [MultiplayerAction] to its [MultiplayerOutcome]. */
object MultiplayerNavigation {
    fun outcomeFor(action: MultiplayerAction): MultiplayerOutcome = when (action) {
        MultiplayerAction.Back -> MultiplayerOutcome.Navigate(AppScreen.MainMenu)
        MultiplayerAction.Refresh -> MultiplayerOutcome.RefreshRequested
        is MultiplayerAction.SwitchLobby -> MultiplayerOutcome.SwitchLobby(action.lobbyKind)
        MultiplayerAction.HostGame -> MultiplayerOutcome.HostGameRequested
        MultiplayerAction.JoinDirect -> MultiplayerOutcome.JoinDirectRequested
        MultiplayerAction.RejoinLastGame -> MultiplayerOutcome.RejoinLastGameRequested
        MultiplayerAction.ConfigurePlayerName -> MultiplayerOutcome.ConfigurePlayerNameRequested
        is MultiplayerAction.JoinRoom -> MultiplayerOutcome.JoinRoom(action.roomId)
    }
}
