package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameModeType
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import io.github.rwx.logger
import io.github.rwx.session.BattleRoomCoreConfig
import io.github.rwx.session.BattleRoomLaunchConfig
import io.github.rwx.session.BattleRoomSnapshot
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.host.BattleRoomSceneHost
import io.github.rwx.ui.model.BattleRoomChatLine
import io.github.rwx.ui.model.LevelSelectMode
import io.github.rwx.ui.model.LevelSelectViewModelFactory
import io.github.rwx.ui.model.MapEntry

internal class BattleRoomController(
    private val gameSession: GameSession,
    private val levelSelectViewModelFactory: LevelSelectViewModelFactory,
    private val sceneHost: BattleRoomSceneHost,
    initialMode: LevelSelectMode,
    private val showUnavailableDialog: (String) -> Unit,
) {
    var selectedMode: LevelSelectMode = initialMode
    var selectedMap: MapEntry? = null
        private set
    var isSelectingMapForBattleRoom: Boolean = false
    private var returnScreen: AppScreen = AppScreen.LevelSelect
    private val chatLines = mutableListOf<BattleRoomChatLine>()
    private var roomRequestSequence = 0L

    fun selectedOrDefaultMap(): MapEntry? =
        selectedMap ?: selectDefaultMap()

    fun selectDefaultMap(): MapEntry? {
        selectedMap?.let { return it }
        return runCatching {
            val maps = levelSelectViewModelFactory.create(selectedMode).items()
            val benchmarkMap = System.getProperty("rwx.benchmark.map")?.takeIf { it.isNotBlank() }
            if (benchmarkMap == null) maps.firstOrNull()
            else requireNotNull(maps.firstOrNull { it.mapAssetPath == benchmarkMap }) {
                "Benchmark map is absent from ${selectedMode.label}: $benchmarkMap"
            }
        }.onFailure { error ->
            logger.warn(error) { "Unable to select default RW map for ${selectedMode.label}" }
        }.getOrNull()?.also { map ->
            selectedMap = map
            logger.info { "Selected default RW map: ${map.mapAssetPath}" }
        }
    }

    fun currentSnapshot(refreshNetworkStatus: Boolean = true): BattleRoomSnapshot? =
        gameSession.currentBattleRoom(refreshNetworkStatus)

    fun prepareForMap(
        map: MapEntry,
        sandbox: Boolean = false,
        returnScreen: AppScreen = AppScreen.LevelSelect,
    ) {
        selectedMap = map
        this.returnScreen = returnScreen
        val newConfig = BattleRoomLaunchConfig(
            sandbox = sandbox,
            aiPlayerCount = defaultBattleRoomAiPlayerCount(map.playerCount),
            room = BattleRoomCoreConfig(
                mapPath = map.mapAssetPath,
                options = GameRoomSettings(),
            ),
        )
        newConfig.room.options.apply {
            localBenchmarkFogMode(System.getenv())?.let { fogMode = it }
            if (map.isSavedGame) gameModeType = GameModeType.savedGame
            if (sandbox) {
                fogMode = 0
                revealedMap = true
            }
        }
        chatLines.clear()
        prepareLocalSinglePlayerRoom(newConfig)
    }

    fun prepareSandboxGame(): Boolean {
        val map = selectSandboxMap() ?: selectDefaultMap()
        if (map == null) {
            showUnavailableDialog("No sandbox map is available")
            return false
        }
        selectedMode = LevelSelectMode.Skirmish
        prepareForMap(
            map = map,
            sandbox = true,
            returnScreen = AppScreen.MainMenu,
        )
        return true
    }

    fun closeRoom(): AppScreen {
        roomRequestSequence++
        isSelectingMapForBattleRoom = false
        gameSession.requestSessionTask({ gameSession.leaveBattleRoom() }) { result ->
            result.onFailure { error -> logger.warn(error) { "Unable to leave battle room" } }
        }
        return returnScreen
    }

    fun selectBattleRoomMap(map: MapEntry) {
        val previousMap = selectedMap
        selectedMap = map
        val snapshot = currentSnapshot(refreshNetworkStatus = false)
        val currentAiCount = snapshot?.players?.count { it.isAI } ?: 0
        val targetAiCount =
            if (currentAiCount == defaultBattleRoomAiPlayerCount(playerCount = previousMap?.playerCount)) {
                defaultBattleRoomAiPlayerCount(playerCount = map.playerCount)
            } else {
                currentAiCount
            }
        gameSession.requestSessionTask(action = {
            check(gameSession.setBattleRoomMap(map.mapAssetPath, map.isSavedGame)) {
                "Game session rejected map change"
            }
            val diff = targetAiCount - currentAiCount
            when {
                diff > 0 -> gameSession.addBattleRoomAi(diff)
                diff < 0 -> snapshot?.players?.filter { it.isAI }?.sortedBy { it.spawnColorIndex }?.takeLast(-diff)
                    ?.forEach { gameSession.kickBattleRoomPlayer(it.id) }
            }
        }, onComplete = { result ->
            result.onSuccess { updateFromNetwork() }.onFailure { error ->
                logger.warn(error) { "Unable to set battle room map" }
                showUnavailableDialog("Unable to set map: ${error.message ?: error.javaClass.simpleName}")
            }
        })
        isSelectingMapForBattleRoom = false
    }

    fun prepareHostRoom(map: MapEntry) {
        chatLines.clear()
        returnScreen = AppScreen.Multiplayer
        selectedMap = map
    }

    fun markJoinedRoomStarted() {
        chatLines.clear()
        returnScreen = AppScreen.Multiplayer
    }

    fun updateConnectedRoom(snapshot: BattleRoomSnapshot?) {
        snapshot
            ?.let { sceneHost.updateRoom(it.toBattleRoomModel(previewFor(it), chatLines)) }
            ?: updateFromNetwork()
    }

    fun updateFromNetwork(refreshNetworkStatus: Boolean = true) {
        val snapshot = currentSnapshot(refreshNetworkStatus) ?: return
        sceneHost.updateRoom(snapshot.toBattleRoomModel(previewFor(snapshot), chatLines))
    }

    private fun previewFor(snapshot: BattleRoomSnapshot): String? = battleRoomMapPreview(
        mapPath = snapshot.room.mapPath,
        isSavedGame = snapshot.room.isSavedGame,
        selectedMapPath = selectedMap?.mapAssetPath,
        selectedPreviewPath = selectedMap?.previewAssetPath,
    ) { path ->
        runCatching {
            levelSelectViewModelFactory.create(selectedMode).mapEntry(path).previewAssetPath
        }.getOrNull()
    }

    fun appendChat(text: String, teamColorIndex: Int = -1) {
        val line = BattleRoomChatLine(text, teamColorIndex)
        chatLines += line
        sceneHost.appendChat(line)
    }

    private fun prepareLocalSinglePlayerRoom(config: BattleRoomLaunchConfig) {
        val requestSequence = ++roomRequestSequence
        gameSession.requestSessionTask(action = {
            config.room.options.aiDifficulty = GameEngine.getInstance()?.settingsEngine?.aiDifficulty ?: 1
            check(gameSession.enterLocalBattleRoomLive(config)) { "Game session rejected local room preparation" }
            gameSession.currentBattleRoom()
        }, onComplete = { result ->
            if (requestSequence != roomRequestSequence) return@requestSessionTask
            result.onSuccess { snapshot ->
                snapshot?.let { sceneHost.updateRoom(it.toBattleRoomModel(previewFor(it), chatLines)) }
            }.onFailure { error ->
                logger.warn(error) { "Unable to prepare battle room" }
                showUnavailableDialog("Unable to prepare battle room: ${error.message ?: error.javaClass.simpleName}")
            }
        })
    }

    private fun selectSandboxMap(): MapEntry? =
        runCatching {
            levelSelectViewModelFactory.create(LevelSelectMode.Skirmish).items()
                .firstOrNull { it.fileName.contains("Crossing Large", ignoreCase = true) }
                ?: levelSelectViewModelFactory.create(LevelSelectMode.Skirmish).items().firstOrNull()
        }.onFailure { error ->
            logger.warn(error) { "Unable to select sandbox map" }
        }.getOrNull()
}

/** Diagnostic launch configuration only; ordinary rooms keep their existing fog options. */
internal fun localBenchmarkFogMode(environment: Map<String, String>): Int? {
    if (environment["RWX_MAP_PAN_OUTPUT"].isNullOrBlank()) return null
    return when (environment["RWX_BENCHMARK_FOG"]) {
        null -> null
        "off" -> 0
        "on" -> 2
        else -> error("Local benchmark fog must be off or on")
    }
}
