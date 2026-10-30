package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.MasterServerClient
import com.corrodinggames.rts.gameFramework.network.ServerInfo
import io.github.rwx.logger
import io.github.rwx.i18n.I18n
import io.github.rwx.map.MapMetadata
import io.github.rwx.p2p.P2PLobbyService
import io.github.rwx.p2p.P2PRoomAdvertisement
import io.github.rwx.session.GameSession
import io.github.rwx.ui.*
import io.github.rwx.ui.host.MultiplayerSceneHost
import io.github.rwx.ui.model.ModeLabel
import io.github.rwx.ui.model.MultiplayerLobbyKind
import io.github.rwx.ui.model.MultiplayerRoomItem

internal class MultiplayerLobbyController(
    private val sceneHost: MultiplayerSceneHost,
    private val gameSession: GameSession,
) {
    var activeLobbyKind: MultiplayerLobbyKind = MultiplayerLobbyKind.Original
        private set

    private var latestRooms: List<MultiplayerRoomItem> = emptyList()

    fun roomById(roomId: String): MultiplayerRoomItem? =
        latestRooms.firstOrNull { it.roomId == roomId }

    fun originalRoomByServerId(serverId: String): MultiplayerRoomItem? =
        latestRooms.firstOrNull { it.originalServerId == serverId }

    fun requestRefresh() {
        if (activeLobbyKind == MultiplayerLobbyKind.Original) {
            requestOriginalRefresh()
        } else {
            requestP2PRefresh()
        }
    }

    fun switchLobby(lobbyKind: MultiplayerLobbyKind) {
        if (activeLobbyKind == lobbyKind) {
            updateActiveRooms()
            return
        }
        activeLobbyKind = lobbyKind
        requestRefresh()
    }

    fun handleOriginalRoomListRefresh() {
        if (activeLobbyKind == MultiplayerLobbyKind.Original) {
            updateOriginalRooms()
        }
    }

    fun handleP2PRoomListRefresh() {
        if (activeLobbyKind == MultiplayerLobbyKind.P2P) {
            updateP2PRooms()
        }
    }

    private fun requestOriginalRefresh() {
        sceneHost.updateRooms(latestRooms, I18n.multiplayer.searchingRooms(), MultiplayerLobbyKind.Original)
        gameSession.requestSessionTask(action = {
            if (GameEngine.getInstance()?.networkEngine == null) return@requestSessionTask false
            MasterServerClient.loadServerListAsync {
                CoreUiEventQueue.requestOriginalRoomListRefresh()
            }
            true
        }, onComplete = { result ->
            if (activeLobbyKind != MultiplayerLobbyKind.Original) return@requestSessionTask
            result.onSuccess { available ->
                updateOriginalRooms(if (available) I18n.multiplayer.searchingRooms() else "")
            }.onFailure { error ->
                logger.warn(error) { "Original room refresh failed" }
                sceneHost.updateRooms(emptyList(), I18n.multiplayer.lobby.originalUnavailable(error.message ?: error.javaClass.simpleName),
                    MultiplayerLobbyKind.Original)
            }
        })
    }

    private fun requestP2PRefresh() {
        val lobby = P2PLobbyService.getInstance()
        gameSession.requestSessionTask(action = {
            lobby.inLobby = true
            lobby.startIfNeeded()
            lobby.requestRefresh()
        }, onComplete = { result ->
            if (activeLobbyKind != MultiplayerLobbyKind.P2P) return@requestSessionTask
            result.onSuccess { updateP2PRooms(I18n.multiplayer.searchingRooms()) }.onFailure { error ->
                logger.warn { "P2P room refresh failed: ${error.message}" }
                sceneHost.updateRooms(
                    emptyList(),
                    I18n.multiplayer.lobby.p2pUnavailable(error.message ?: error.javaClass.simpleName),
                    MultiplayerLobbyKind.P2P,
                )
            }
        })
    }

    private fun updateActiveRooms(statusText: String = "") {
        when (activeLobbyKind) {
            MultiplayerLobbyKind.Original -> updateOriginalRooms(statusText)
            MultiplayerLobbyKind.P2P -> updateP2PRooms(statusText)
        }
    }

    private fun updateP2PRooms(statusText: String = "") {
        val rooms = P2PLobbyService.getInstance().getRooms().map(::p2pRoomToMultiplayerItem)
        latestRooms = rooms
        sceneHost.updateRooms(
            rooms = rooms,
            statusText = statusText.ifBlank { if (rooms.isEmpty()) I18n.multiplayer.noRooms() else "" },
            lobbyKind = MultiplayerLobbyKind.P2P,
        )
    }

    private fun updateOriginalRooms(statusText: String = "") {
        gameSession.requestSessionTask(action = {
            if (GameEngine.getInstance()?.networkEngine == null) return@requestSessionTask null
            ServerListUiBridge.getServerList()
                .filterIsInstance<ServerInfo>()
                .map(::serverInfoToMultiplayerItem)
        }, onComplete = { result ->
            if (activeLobbyKind != MultiplayerLobbyKind.Original) return@requestSessionTask
            result.onSuccess { rooms ->
                latestRooms = rooms.orEmpty()
                sceneHost.updateRooms(latestRooms,
                    if (rooms == null) I18n.multiplayer.lobby.requiresEngine()
                    else statusText.ifBlank { if (rooms.isEmpty()) I18n.multiplayer.noRooms() else "" },
                    MultiplayerLobbyKind.Original)
            }.onFailure { error ->
                logger.warn(error) { "Original room list read failed" }
                latestRooms = emptyList()
                sceneHost.updateRooms(emptyList(), I18n.multiplayer.lobby.originalUnavailable(error.message ?: error.javaClass.simpleName),
                    MultiplayerLobbyKind.Original)
            }
        })
    }
}

internal fun p2pRoomToMultiplayerItem(room: P2PRoomAdvertisement): MultiplayerRoomItem =
    MultiplayerRoomItem(
        roomId = room.roomId.orEmpty(),
        hostName = room.createdBy ?: I18n.multiplayer.unknown(),
        mapName = room.getMapDisplayName()
            .let { if (it == "<No Map>") I18n.battleroom.noMapSelected() else it }
            .withRwxModeSuffix(room.requiredRwxFeatures),
        playersLabel = "${room.currentPlayers.coerceAtLeast(0)}/${room.maxPlayers.coerceAtLeast(0)}",
        versionLabel = room.gameVersionString?.let { "v$it" } ?: I18n.multiplayer.unknown(),
        requiresPassword = room.requiresPassword,
        hasMods = room.hasMods,
        transportLabel = when {
            !room.webrtcSignaling.isNullOrBlank() -> "WebRTC"
            room.transport.isNotBlank() -> room.transport
            else -> "P2P"
        },
        stateLabel = room.gameState ?: "unknown",
        infoText = localizeLegacyRoomText(room.getInfoText()),
        currentPlayers = room.currentPlayers,
        maxPlayers = room.maxPlayers,
    )

private fun String.withRwxModeSuffix(requiredRwxFeatures: List<String>): String =
    requiredRwxFeatures.ModeLabel()?.let { "$this - $it" } ?: this

internal fun serverInfoToMultiplayerItem(server: ServerInfo): MultiplayerRoomItem =
    MultiplayerRoomItem(
        roomId = server.getConnectDescriptor(),
        hostName = server.createdBy ?: server.publicHost ?: server.lanHost ?: I18n.multiplayer.unknown(),
        mapName = server.mapPath?.let(MapMetadata::getMapName) ?: server.serverMessage ?: I18n.battleroom.noMapSelected(),
        playersLabel = "${server.currentPlayers.coerceAtLeast(0)}/${server.maxPlayers.coerceAtLeast(0)}",
        versionLabel = server.gameVersionString?.let { if (it == "ANY") I18n.multiplayer.anyVersion() else "v$it" } ?: I18n.multiplayer.unknown(),
        requiresPassword = server.requiresPassword,
        hasMods = server.hasMods,
        transportLabel = when {
            server.isLanServer -> "LAN"
            server.isDedicatedServer -> "Dedicated"
            server.isPortOpen -> "Internet"
            else -> "Relay"
        },
        stateLabel = server.gameState ?: "unknown",
        currentPlayers = server.currentPlayers,
        maxPlayers = server.maxPlayers,
        joinAddress = server.getConnectDescriptor(),
        rejoinAddress = (if (server.isLanServer) server.lanHost else server.publicHost)
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it:${server.port}" },
        originalServerId = server.serverId?.takeIf { it.isNotBlank() },
        infoText = runCatching { localizeLegacyRoomText(server.getInfoText()) }.getOrDefault(""),
    )
