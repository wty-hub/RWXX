package io.github.rwx.app

import io.github.rwx.PlatformStorage
import io.github.rwx.i18n.I18n
import io.github.rwx.logger
import io.github.rwx.map.LinkedMapAvailability
import io.github.rwx.map.MapLinkResolver
import io.github.rwx.p2p.FeatureIds
import io.github.rwx.p2p.MapFeatureDetector
import io.github.rwx.p2p.MultiMapCoordinator
import io.github.rwx.p2p.MultiMapAssignments
import io.github.rwx.p2p.P2PLobbyService
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.BattleRoomLaunchConfig
import io.github.rwx.session.BattleRoomSnapshot
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.BattleRoomUiBridge

internal class BattleRoomLaunchController(
    private val gameSession: GameSession,
    private val storage: () -> PlatformStorage?,
    private val viewport: () -> KoolCanvasViewport,
    private val currentScreen: () -> AppScreen,
    private val showStartNewGameDialog: (() -> Unit) -> Unit,
    private val enterRwGame: (Boolean, BattleRoomLaunchConfig?) -> Unit,
    private val clearPendingRwStartState: () -> Unit,
    private val clearPendingStartState: () -> Unit,
    private val setPendingStartState: (String, AppScreen) -> Unit,
    private val navigateToInGame: () -> Unit,
    private val showUnavailableDialog: (String) -> Unit,
) {
    private var startPending = false
    private var adoptionPending = false
    private var preparedMultiMapAssignments: MultiMapAssignments? = null
    private var startRequestSequence = 0L

    /** Engine work remains ordered; leaving the room invalidates only its later UI completion. */
    fun cancelPendingStart() {
        startRequestSequence++
        startPending = false
        adoptionPending = false
        preparedMultiMapAssignments = null
        BattleRoomUiBridge.startGamePending = false
    }

    fun startBattleRoomGame() {
        val requestSequence = startRequestSequence
        val launchConfig = gameSession.loadState.pendingRendererBattleRoomConfig
        currentOriginalMultiplayerRwxMapBlockMessage()?.let { message ->
            showUnavailableDialog(message)
            return
        }
        currentLinkedMapUnavailableMessage()?.let { message ->
            showUnavailableDialog(message)
            return
        }
        if (gameSession.currentBattleRoom()?.isNetworkMultiplayer == true) {
            P2PLobbyService.getInstance().currentMissingRequiredFeatureSummary()?.let { message ->
                showUnavailableDialog(message)
                return
            }
        }
        if (gameSession.canResume()) {
            showStartNewGameDialog {
                if (requestSequence == startRequestSequence) startPreparedBattleRoom(launchConfig)
            }
            return
        }
        startPreparedBattleRoom(launchConfig)
    }

    private fun startPreparedBattleRoom(launchConfig: BattleRoomLaunchConfig?) {
        if (startPending || adoptionPending) return
        val snapshot = gameSession.currentBattleRoom()
        // A live host room (network multiplayer OR a live local skirmish/sandbox room started at
        // entry) starts the game in place via startBattleRoomGame on the already-live server, so the
        // host's live spawn/team edits and player list are preserved. Only when there is no live
        // room do we fall back to enterRwGame, which builds the room from the static launch config.
        if (shouldStartLiveBattleRoomInPlace(
                hasLaunchConfig = launchConfig != null,
                isHost = snapshot?.isHost == true,
                isNetworkMultiplayer = snapshot?.isNetworkMultiplayer == true,
                isBattleRoomLive = gameSession.isBattleRoomLive(),
            ) && snapshot != null
        ) {
            if (!prepareP2PMultiMapAssignmentsForStart(snapshot)) {
                return
            }
            val assignments = preparedMultiMapAssignments
            preparedMultiMapAssignments = null
            val requestSequence = startRequestSequence
            startPending = true
            gameSession.requestSessionTask(
                action = {
                    assignments?.let { P2PLobbyService.getInstance().broadcastMultiMapAssignments(it) }
                    check(gameSession.startBattleRoom()) { "Game session rejected start request" }
                },
                onComplete = { result ->
                    if (requestSequence != startRequestSequence) return@requestSessionTask
                    startPending = false
                    result.onSuccess { enterStartedBattleRoomGame() }.onFailure { error ->
                        logger.warn(error) { "Unable to start RW battle room game" }
                        showUnavailableDialog("Unable to start game: ${error.message ?: error.javaClass.simpleName}")
                    }
                },
            )
            return
        }
        enterRwGame(true, launchConfig)
    }

    fun handleBattleRoomGameStarted() {
        val networkGameStarted = gameSession.hasActiveStartedGameConnection()
        when (battleRoomGameStartedAction(
            currentScreen = currentScreen(),
            rendersIntoKoolCanvas = gameSession.rendersIntoKoolCanvas,
            inProcessNetworkGameStarted = networkGameStarted,
        )) {
            BattleRoomGameStartedAction.Ignore -> {
                if (currentScreen() == AppScreen.InGame) {
                    BattleRoomUiBridge.startGamePending = false
                } else if (gameSession.currentBattleRoom()?.isNetworkMultiplayer == true) {
                    // As in the original battleroom, hold the start event until the UI can act on
                    // it (the network thread may fire it before gameHasBeenStarted is visible or
                    // while another screen is open); FrameDriver replays it every frame.
                    if (!BattleRoomUiBridge.startGamePending) {
                        logger.info { "Deferring battle room game-start callback until the UI is ready" }
                    }
                    BattleRoomUiBridge.startGamePending = true
                } else {
                    BattleRoomUiBridge.startGamePending = false
                    logger.warn {
                        "Ignoring battle room game-start callback without a started in-process network game"
                    }
                }
            }

            BattleRoomGameStartedAction.ShowExternalGame -> {
                BattleRoomUiBridge.startGamePending = false
                enterStartedBattleRoomGame()
            }

            BattleRoomGameStartedAction.LoadKoolGame -> {
                BattleRoomUiBridge.startGamePending = false
                runCatching {
                    enterStartedBattleRoomGame()
                }.onFailure { error ->
                    logger.warn(error) { "Unable to load started RW battle room game" }
                    showUnavailableDialog(
                        I18n.battleroom.unableToStartGame(error.message ?: error.javaClass.simpleName)
                    )
                }
            }
        }
    }

    /** Replays a deferred network game-start event once per frame until the UI can handle it. */
    fun driveDeferredNetworkGameStart() {
        if (!BattleRoomUiBridge.startGamePending) return
        if (gameSession.currentBattleRoom()?.isNetworkMultiplayer != true) {
            BattleRoomUiBridge.startGamePending = false
            return
        }
        handleBattleRoomGameStarted()
    }

    private fun enterStartedBattleRoomGame() {
        if (adoptionPending) return
        val mapPath = gameSession.currentBattleRoom()?.room?.mapPath?.takeIf { it.isNotBlank() }
        if (mapPath == null) {
            logger.warn { "Unable to enter started battle room without an active map" }
            showUnavailableDialog(
                I18n.battleroom.unableToStartGame("Network game is no longer active")
            )
            return
        }
        val requestedViewport = viewport()
        val requestSequence = startRequestSequence
        adoptionPending = true
        gameSession.requestSessionTask(
            action = {
                check(gameSession.adoptStartedGameFromEngine(requestedViewport)) {
                    "Renderer could not adopt the started game"
                }
            },
            onComplete = { result ->
                if (requestSequence != startRequestSequence) return@requestSessionTask
                adoptionPending = false
                result.onSuccess {
                    clearPendingRwStartState()
                    if (gameSession.isMapLoaded(mapPath)) clearPendingStartState()
                    else setPendingStartState(mapPath, mapStartFailureReturnScreen(currentScreen()))
                    navigateToInGame()
                }.onFailure { error ->
                    logger.warn(error) { "Game session rejected the started battle room game" }
                    showUnavailableDialog(I18n.battleroom.unableToStartGame(error.message ?: error.javaClass.simpleName))
                }
            },
        )
    }

    private fun currentOriginalMultiplayerRwxMapBlockMessage(): String? {
        val snapshot = gameSession.currentBattleRoom() ?: return null
        if (snapshot.room.isSavedGame) return null
        if (!snapshot.isHost || !snapshot.isNetworkMultiplayer || snapshot.rwxP2PSession) {
            return null
        }
        val requiredFeatures = MapFeatureDetector.requiredFeaturesForMap(snapshot.room.mapPath)
        if (requiredFeatures.isEmpty()) {
            return null
        }
        return "RWXX map modes require RWXX P2P multiplayer. Switch to P2P or choose a vanilla-compatible map."
    }

    private fun currentLinkedMapUnavailableMessage(): String? {
        if (gameSession.currentBattleRoom()?.room?.isSavedGame == true) {
            return null
        }
        val mapPath = gameSession.currentBattleRoom()?.room?.mapPath?.takeIf { it.isNotBlank() }
            ?: return null
        if (FeatureIds.MAP_LINKS in MapFeatureDetector.requiredFeaturesForMap(mapPath) &&
            !LinkedMapAvailability.ENABLED
        ) {
            return I18n.levelselect.linkedMaps.unavailable()
        }
        val mapStorage = storage() ?: return null
        val missing = MapLinkResolver.missingLinkedMapIds(mapStorage, mapPath)
        if (missing.isEmpty()) {
            return null
        }
        return "Linked map missing: ${missing.joinToString(", ")}"
    }

    private fun prepareP2PMultiMapAssignmentsForStart(snapshot: BattleRoomSnapshot): Boolean {
        preparedMultiMapAssignments = null
        if (snapshot.room.isSavedGame) return true
        if (!snapshot.isHost || !snapshot.isNetworkMultiplayer || !snapshot.rwxP2PSession) {
            return true
        }
        val mapPath = snapshot.room.mapPath.takeIf { it.isNotBlank() } ?: return true
        val requiredFeatures = MapFeatureDetector.requiredFeaturesForMap(mapPath)
        if (FeatureIds.MAP_LINKS !in requiredFeatures) {
            return true
        }
        if (!LinkedMapAvailability.ENABLED) {
            showUnavailableDialog(I18n.levelselect.linkedMaps.unavailable())
            return false
        }
        val mapStorage = storage()
        if (mapStorage == null) {
            showUnavailableDialog("RWXX multi-map requires local map storage.")
            return false
        }
        val plan = MultiMapCoordinator.createAssignmentPlan(mapStorage, mapPath, snapshot.players)
        if (plan.missingTargetMapIds.isNotEmpty()) {
            showUnavailableDialog("Linked map missing: ${plan.missingTargetMapIds.joinToString(", ")}")
            return false
        }
        if (!plan.hasEnoughPlayers) {
            showUnavailableDialog(
                "RWXX multi-map requires at least one non-spectator human player per map instance. " +
                        "Maps: ${plan.requiredPlayerCount}, players: ${plan.availablePlayerCount}."
            )
            return false
        }
        val assignments = plan.assignments
        if (assignments == null) {
            showUnavailableDialog("Unable to prepare RWXX multi-map assignments.")
            return false
        }
        preparedMultiMapAssignments = assignments
        return true
    }
}
