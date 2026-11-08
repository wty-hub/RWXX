package io.github.rwx.app

import io.github.rwx.mod.registry.UiRegistry

import de.fabmax.kool.KoolContext
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.util.ApplicationScope
import com.corrodinggames.rts.gameFramework.SettingsEngine
import io.github.rwx.i18n.I18n
import io.github.rwx.input.KoolKeyCodeMapping
import io.github.rwx.logger
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameSession
import io.github.rwx.ui.*
import io.github.rwx.ui.component.PlatformTextInputBridge
import io.github.rwx.ui.model.BattleRoomAction
import io.github.rwx.ui.model.LevelSelectMode
import io.github.rwx.ui.model.MainMenuConditions
import io.github.rwx.ui.model.ReplaySelectViewModel
import io.github.rwx.ui.model.ResourceBrowserType
import io.github.rwx.ui.model.SettingsAction
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

const val RWX_KOOL_GAME_FRAME_READY_MARKER: String = "RWX_GAME_FRAME_READY"
private val ioSupervisor = SupervisorJob(ApplicationScope.job)

fun launchOnIO(name: String?=null,
               start: CoroutineStart = CoroutineStart.DEFAULT,
               exceptionHandler: (CoroutineContext, Throwable)->Unit={ _, t-> logger.error(t) },
               block: suspend CoroutineScope.() -> Unit): Job {
    val effectiveName = name ?: "IO-${System.identityHashCode(block)}"
    val ctx=ioSupervisor+Dispatchers.IO+ CoroutineName(effectiveName)+CoroutineExceptionHandler(exceptionHandler)
    return ApplicationScope.launch(ctx, start, block)
}
fun <T> asyncOnIO(
    name: String? = null,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): Deferred<T> {
    val effectiveName = name ?: "IO-${System.identityHashCode(block)}"
    val ctx = ioSupervisor + Dispatchers.IO + CoroutineName(effectiveName)
    return ApplicationScope.async(ctx, start, block)
}
class AppSession internal constructor(
    val navigator: ScreenNavigator,
    private val onQuit: () -> Unit,
    private val onBack: () -> Unit,
    private val releaseInputOnHostFocusLost: () -> Unit,
) {
    fun navigateBack() = onBack()

    fun isFinishLoading(): Boolean =
        navigator.current != AppScreen.Loading

    fun quit() = onQuit()

    /** Releases input forwarded into the game when the host window loses focus. */
    fun onHostFocusLost() = releaseInputOnHostFocusLost()
}

fun installApp(
    context: KoolContext,
    options: AppOptions = AppOptions(),
    onQuit: () -> Unit = { context.window.close() },
    configureCanvasHost: (io.github.rwx.render.canvas.KoolCanvasSceneHost) -> Unit = {},
    configureGameSession: (GameSession) -> Unit = {},
): AppSession {
    val bootstrap = createAppBootstrap(context, options)
    val platformBridge = bootstrap.platformBridge
    val appMetadata = bootstrap.appMetadata
    val gameSession = bootstrap.gameSession
    configureGameSession(gameSession)
    val menuBackgroundSession = bootstrap.menuBackgroundSession
    val modRepository = bootstrap.modRepository
    val resourceBrowserRepository = bootstrap.resourceBrowserRepository
    val settingsRepository = bootstrap.settingsRepository
    if (gameSession.usesIndependentEngineLoop) {
        settingsRepository.engineExecutor = { action -> gameSession.submitSessionTask(action); Unit }
    }
    val previousModUiExecutor = UiRegistry.engineExecutor
    val modUiExecutor: (() -> Unit) -> Unit = { action -> gameSession.submitSessionTask(action); Unit }
    UiRegistry.engineExecutor = modUiExecutor
    val actions = bootstrap.actions
    val settingsModel = bootstrap.settingsModel
    val koolCanvasSceneHost = bootstrap.koolCanvasSceneHost
    configureCanvasHost(koolCanvasSceneHost)
    context.onShutdown += {
        try { gameSession.close() }
        finally {
            if (UiRegistry.engineExecutor === modUiExecutor) {
                // A bounded owner join can leave accepted work finishing; never reopen inline access.
                UiRegistry.engineExecutor = if (gameSession.usesIndependentEngineLoop) {
                    { _ -> error("Game session is closed") }
                } else previousModUiExecutor
            }
        }
    }
    val koolCanvasScene = bootstrap.koolCanvasScene
    val loadingSceneHost = bootstrap.loadingSceneHost
    val mainMenuSceneHost = bootstrap.mainMenuSceneHost
    val pauseSceneHost = bootstrap.pauseSceneHost
    val levelSelectSceneHost = bootstrap.levelSelectSceneHost
    val levelSelectViewModelFactory = bootstrap.levelSelectViewModelFactory
    val replaySelectSceneHost = bootstrap.replaySelectSceneHost
    val settingsSceneHost = bootstrap.settingsSceneHost
    settingsSceneHost.configureEngineOwnership(gameSession)
    val multiplayerSceneHost = bootstrap.multiplayerSceneHost
    val modsSceneHost = bootstrap.modsSceneHost
    val resourceBrowserSceneHost = bootstrap.resourceBrowserSceneHost
    val loadingDialogSceneHost = bootstrap.loadingDialogSceneHost
    val battleRoomSceneHost = bootstrap.battleRoomSceneHost
    val modWindowSceneHost = bootstrap.modWindowSceneHost
    val dialogSceneHost = bootstrap.dialogSceneHost

    var lastExternalGameFrame: KoolCanvasFrame? = null
    val startupTargetScreen = if (options.initialScreen == AppScreen.Loading) {
        AppScreen.MainMenu
    } else {
        options.initialScreen
    }
    lateinit var navigator: ScreenNavigator
    lateinit var pendingStartController: PendingStartController

    fun rwGameViewport(): KoolCanvasViewport {
        val windowSize = context.window.size
        return KoolCanvasViewport(
            width = if (windowSize.x > 1) windowSize.x else 1280,
            height = if (windowSize.y > 1) windowSize.y else 720,
        )
    }

    val screenPresenter = ScreenPresenter(
        bootstrap = bootstrap,
        viewport = ::rwGameViewport,
    )

    val warmupController = WarmupController(
        gameSession = gameSession,
        menuBackgroundSession = menuBackgroundSession,
        loadingSceneHost = loadingSceneHost,
        navigateTo = { screen -> navigator.navigateTo(screen) },
        clearExternalFrame = { lastExternalGameFrame = null },
        isMenuBackgroundDemoEnabled = { settingsModel.showMainMenuBackgroundDemo.value },
    )

    val dialogController = DialogController(
        platformBridge = platformBridge,
        appMetadata = appMetadata,
        dialogSceneHost = dialogSceneHost,
    )

    val battleRoomController = BattleRoomController(
        gameSession = gameSession,
        levelSelectViewModelFactory = levelSelectViewModelFactory,
        sceneHost = battleRoomSceneHost,
        initialMode = options.levelSelectMode ?: LevelSelectMode.Skirmish,
        showUnavailableDialog = dialogController::showUnavailable,
    )

    fun refreshMainMenu() {
        mainMenuSceneHost.updateItems(
            MainMenuConditions(
                canResume = gameSession.canResume(),
                isDesktop = options.isDesktop,
            )
        )
    }

    pendingStartController = PendingStartController(
        gameSession = gameSession,
        dialogSceneHost = dialogSceneHost,
        refreshMainMenu = ::refreshMainMenu,
        navigateTo = { screen -> navigator.navigateTo(screen) },
    )

    val gameLaunchController = GameLaunchController(
        gameSession = gameSession,
        battleRoomController = battleRoomController,
        warmupController = warmupController,
        pendingStartController = pendingStartController,
        dialogSceneHost = dialogSceneHost,
        viewport = ::rwGameViewport,
        currentScreen = { navigator.current },
        navigateTo = { screen -> navigator.navigateTo(screen) },
    )

    val multiplayerLobbyController = MultiplayerLobbyController(
        gameSession = gameSession,
        sceneHost = multiplayerSceneHost,
    )
    val inGameDialogController = InGameDialogController(
        gameSession = gameSession,
        koolCanvasScene = koolCanvasScene,
        dialogSceneHost = dialogSceneHost,
        loadingDialogSceneHost = loadingDialogSceneHost,
        currentScreen = { navigator.current },
        viewport = ::rwGameViewport,
        showUnavailableDialog = dialogController::showUnavailable
    )

    val mapController = MapController(
        gameSession = gameSession,
        koolCanvasScene = koolCanvasScene,
        storage = { platformBridge?.storage },
        viewport = ::rwGameViewport,
        pendingStartMapPath = pendingStartController::currentMapPath,
        setPendingStart = pendingStartController::set,
        navigateToInGame = { navigator.navigateTo(AppScreen.InGame) },
        currentBattleRoomSnapshotForJoin = battleRoomController::currentSnapshot,
        showUnavailableDialog = dialogController::showUnavailable,
        showDialogOverGame = inGameDialogController::showDialogOverGame,
        hideDialog = dialogSceneHost::hide,
    )

    val battleRoomJoinController = BattleRoomJoinController(
        gameSession = gameSession,
        loadingDialogSceneHost = loadingDialogSceneHost,
        onStarted = battleRoomController::markJoinedRoomStarted,
        onConnected = { snapshot ->
            battleRoomController.updateConnectedRoom(snapshot)
            navigator.navigateTo(AppScreen.BattleRoom)
        },
        onConnectedRoom = { room ->
            gameSession.requestSessionTask({
                SettingsEngine.getInstance()?.apply {
                    lastMultiplayerRoom = room.encode()
                    save()
                }
            }) { result ->
                result.onFailure { error -> logger.warn(error) { "Unable to remember multiplayer room" } }
            }
        },
        onFailed = dialogController::showUnavailable,
    )
    val multiplayerConnectionController = MultiplayerConnectionController(
        gameSession = gameSession,
        lobbyController = multiplayerLobbyController,
        battleRoomJoinController = battleRoomJoinController,
        dialogSceneHost = dialogSceneHost,
        selectHostMap = battleRoomController::selectedOrDefaultMap,
        onHostPreparing = battleRoomController::prepareHostRoom,
        updateBattleRoomFromNetwork = battleRoomController::updateFromNetwork,
        navigateToBattleRoom = { navigator.navigateTo(AppScreen.BattleRoom) },
        showUnavailableDialog = dialogController::showUnavailable,
    )
    val battleRoomAdminController = BattleRoomAdminController(
        gameSession = gameSession,
        dialogSceneHost = dialogSceneHost,
        updateBattleRoomFromNetwork = battleRoomController::updateFromNetwork,
        showUnavailableDialog = dialogController::showUnavailable,
    )
    val authoritativeRoomController = AuthoritativeRoomController(
        launcher = { platformBridge?.authoritativeRooms },
        initialPlayerName = gameSession::currentMultiplayerPlayerName,
        showDialog = dialogSceneHost::show,
    )
    val battleRoomLaunchController = BattleRoomLaunchController(
        gameSession = gameSession,
        storage = { platformBridge?.storage },
        viewport = ::rwGameViewport,
        currentScreen = { navigator.current },
        showStartNewGameDialog = gameLaunchController::showStartNewGameDialog,
        enterRwGame = gameLaunchController::enterRwGame,
        clearPendingRwStartState = warmupController::clear,
        clearPendingStartState = pendingStartController::clear,
        setPendingStartState = pendingStartController::set,
        navigateToInGame = { navigator.navigateTo(AppScreen.InGame) },
        showUnavailableDialog = dialogController::showUnavailable,
    )

    val externalGameController = ExternalGameController(
        gameSession = gameSession,
        refreshMainMenu = ::refreshMainMenu,
        navigateTo = { screen -> navigator.navigateTo(screen) },
    )

    gameSession.setInGameMenuCallbacks(object : InGameMenuCallbacks {
        override fun requestPause() {
            inGameDialogController.requestInGameKoolOverlay { CoreUiEventQueue.requestInGamePause() }
        }

        override fun requestSettings() {
            inGameDialogController.requestInGameKoolOverlay { CoreUiEventQueue.requestInGameSettings() }
        }

        override fun requestSave() {
            inGameDialogController.requestInGameKoolOverlay { CoreUiEventQueue.requestInGameSave() }
        }

        override fun requestExportMap() {
            inGameDialogController.requestInGameKoolOverlay { CoreUiEventQueue.requestInGameExportMap() }
        }

        override fun requestMapList() {
            inGameDialogController.requestInGameKoolOverlay { CoreUiEventQueue.requestInGameMapList() }
        }

        override fun shouldShowMapList(): Boolean =
            mapController.canShowMapList()

        override fun requestChat(teamOnly: Boolean) {
            inGameDialogController.requestInGameKoolOverlay { CoreUiEventQueue.requestInGameChat(teamOnly) }
        }

        override fun requestPlayerList() {
            inGameDialogController.requestInGameKoolOverlay { CoreUiEventQueue.requestInGamePlayerList() }
        }

        override fun requestSurrender() {
            inGameDialogController.requestInGameKoolOverlay { CoreUiEventQueue.requestInGameSurrender() }
        }

        override fun requestExit() {
            inGameDialogController.requestInGameKoolOverlay { CoreUiEventQueue.requestInGameExit() }
        }

        override fun requestBattleRoomRefresh() {
            CoreUiEventQueue.requestBattleRoomRefresh()
        }

        override fun requestReturnToBattleRoom() {
            CoreUiEventQueue.requestInGameReturnToBattleRoom()
        }
    })

    val modsController = ModsController(
        modRepository = modRepository,
        gameSession = gameSession,
        sceneHost = modsSceneHost,
        loadingDialogSceneHost = loadingDialogSceneHost,
        dialogSceneHost = dialogSceneHost,
        onModsReloaded = {
            levelSelectViewModelFactory.invalidateCaches()
        },
    )

    val sessionActions = SessionActions(
        gameSession = gameSession,
        warmupController = warmupController,
        pendingStartController = pendingStartController,
        settingsSceneHost = settingsSceneHost,
        settingsRepository = settingsRepository,
        settingsModel = settingsModel,
        modsController = modsController,
        currentScreen = { navigator.current },
        navigateTo = { screen -> navigator.navigateTo(screen) },
        refreshMainMenu = ::refreshMainMenu,
        onQuit = onQuit,
    )
    val gameReadyController = GameReadyController(
        gameLaunchController = gameLaunchController,
        exitRwGameToMainMenu = sessionActions::exitRwGameToMainMenu,
        autoReturnMainMenuAfterGameReady = options.autoReturnMainMenuAfterGameReady,
        autoStartSecondBattleRoom = options.autoStartBattleRoomTwice
    )

    val resourceBrowserController = ResourceBrowserController(
        repository = resourceBrowserRepository,
        sceneHost = resourceBrowserSceneHost,
        loadingDialogSceneHost = loadingDialogSceneHost,
        dialogSceneHost = dialogSceneHost,
        showUnavailableDialog = dialogController::showUnavailable,
        onDownloaded = { type ->
            when (type) {
                ResourceBrowserType.Mod -> {
                    modsController.reloadAvailableAndRefresh()
                    // A downloaded mod may bundle maps; drop cached built-in lists so they appear.
                    levelSelectViewModelFactory.invalidateCaches()
                }

                ResourceBrowserType.Map -> {
                    levelSelectSceneHost.updateMaps(LevelSelectMode.CustomMaps)
                }
            }
        },
    )

    val screenLifecycleController = ScreenLifecycleController(
        screenPresenter = screenPresenter,
        externalFrame = { lastExternalGameFrame },
        actions = actions,
        battleRoomController = battleRoomController,
        battleRoomLaunchController = battleRoomLaunchController,
        replaySelectSceneHost = replaySelectSceneHost,
        multiplayerLobbyController = multiplayerLobbyController,
        modsController = modsController,
        resourceBrowserController = resourceBrowserController,
        modWindowSceneHost = modWindowSceneHost,
        settingsRepository = settingsRepository,
        settingsModel = settingsModel,
        refreshMainMenu = ::refreshMainMenu,
        autoStartSinglePlayerFromUi = options.autoStartSinglePlayerFromUi,
        autoStartBattleRoom = options.autoStartBattleRoom,
    )
    val pauseController = InGamePauseController(
        currentScreen = { navigator.current },
        isModalOverlayOpen = {
            dialogSceneHost.isShowing || loadingDialogSceneHost.isShowing || PlatformTextInputBridge.isEditing()
        },
        isGameLoading = { pendingStartController.isPending },
        requestMenuOptions = { onComplete ->
            gameSession.requestSessionTask({ gameSession.captureInGameMenuOptions() }, onComplete)
        },
        updateMenu = pauseSceneHost::updateItems,
        navigateTo = { screen -> navigator.navigateTo(screen) },
        onFailure = { error ->
            logger.warn(error) { "Unable to capture in-game pause menu" }
            dialogController.showUnavailable(I18n.common.actionUnavailable())
        },
    )
    navigator = ScreenNavigator(
        initialScreen = AppScreen.Loading,
        onScreenChanged = { screen ->
            pauseController.onScreenChanged(screen)
            screenLifecycleController.onScreenChanged(screen)
        },
    )
    lateinit var inputController: InputController
    val session = AppSession(
        navigator = navigator,
        onQuit = onQuit,
        onBack = {
            if (screenHandlesOwnBackNavigation(navigator.current)) {
                // The page knows where it was opened from and saves its own state on the way out.
                actions.settings(SettingsAction.Back)
            } else when (backActionForScreen(navigator.current, gameSession.rendersIntoKoolCanvas)) {
                BackNavigationAction.Pause -> {
                    pauseController.open()
                }

                BackNavigationAction.ShowExitDialog -> {
                    inGameDialogController.showExitGameDialog(sessionActions::exitRwGame)
                }

                BackNavigationAction.MainMenu -> {
                    sessionActions.saveCurrentScreenStateBeforeMainMenu()
                    navigator.navigateTo(AppScreen.MainMenu)
                }

                BackNavigationAction.InGame -> navigator.navigateTo(AppScreen.InGame)
                BackNavigationAction.CloseBattleRoom -> actions.battleRoom(BattleRoomAction.Back)
            }
        },
        releaseInputOnHostFocusLost = { inputController.resetOnHostFocusLost() },
    )
    StartupController(
        battleRoomController = battleRoomController,
        warmupController = warmupController,
    ).start(startupTargetScreen)

    ActionRouter(
        actions = actions,
        navigator = navigator,
        platformBridge = platformBridge,
        settingsRepository = settingsRepository,
        settingsModel = settingsModel,
        applyDisplaySettings = gameSession::applyDisplaySettings,
        levelSelectSceneHost = levelSelectSceneHost,
        battleRoomController = battleRoomController,
        multiplayerLobbyController = multiplayerLobbyController,
        multiplayerConnectionController = multiplayerConnectionController,
        authoritativeRoomController = authoritativeRoomController,
        battleRoomAdminController = battleRoomAdminController,
        battleRoomLaunchController = battleRoomLaunchController,
        modsController = modsController,
        resourceBrowserController = resourceBrowserController,
        resumeRwGame = gameLaunchController::resumeRwGame,
        enterRwGame = gameLaunchController::enterRwGame,
        enterReplay = gameLaunchController::enterReplay,
        enterSavedGame = gameLaunchController::enterSavedGame,
        quitApp = sessionActions::quitApp,
        showAboutDialog = dialogController::showAboutDialog,
        clearPendingStartState = pendingStartController::clear,
        openInGameSettings = sessionActions::openInGameSettings,
        openMainMenuSettings = sessionActions::openMainMenuSettings,
        settingsBackTarget = { sessionActions.settingsBackTarget },
        showSaveGameDialog = inGameDialogController::showSaveGameDialog,
        showExitGameDialog = inGameDialogController::showExitGameDialog,
        showInGameChatDialog = inGameDialogController::showInGameChatDialog,
        showMultiplayerPlayerList = inGameDialogController::showInGamePlayerListDialog,
        requestInGameSurrender = sessionActions::requestInGameSurrender,
        exitRwGame = sessionActions::exitRwGame,
        selectLegacyPauseMenuOption = { menuId ->
            gameSession.requestSessionTask({ gameSession.selectInGameMenuOption(menuId) }) { result ->
                result.onFailure { error ->
                    logger.warn(error) { "Unable to select in-game menu option: $menuId" }
                    dialogController.showUnavailable(I18n.common.actionUnavailable())
                }
            }
        },
        showUnavailableDialog = dialogController::showUnavailable,
    ).install()

    val dismissDialog = {
        val dismissed = dialogSceneHost.dismissIfShowing()
        if (dismissed) inGameDialogController.onDialogDismissed()
        dismissed
    }
    PlatformTextInputBridge.onEscape = { dismissDialog() }
    PlatformTextInputBridge.onSubmitKey = { key ->
        val androidKey = when (key) {
            KeyboardInput.KEY_ENTER -> KoolKeyCodeMapping.ANDROID_ENTER
            KeyboardInput.KEY_NP_ENTER -> KoolKeyCodeMapping.ANDROID_NUMPAD_ENTER
            else -> null
        }
        if (androidKey != null) gameSession.suppressKeyUntilRelease(androidKey)
    }
    PlatformTextInputBridge.onKeyReleased = { key ->
        val androidKey = when (key) {
            KeyboardInput.KEY_ENTER -> KoolKeyCodeMapping.ANDROID_ENTER
            KeyboardInput.KEY_NP_ENTER -> KoolKeyCodeMapping.ANDROID_NUMPAD_ENTER
            KeyboardInput.KEY_SHIFT_LEFT -> KoolKeyCodeMapping.ANDROID_SHIFT_LEFT
            KeyboardInput.KEY_SHIFT_RIGHT -> KoolKeyCodeMapping.ANDROID_SHIFT_RIGHT
            else -> null
        }
        if (androidKey != null) gameSession.submitKey(androidKey, false)
    }
    inputController = InputController(
        gameSession = gameSession,
        currentScreen = { navigator.current },
        pointerScale = {
            pointerToGameScale(
                parentScreenScale = context.window.parentScreenScale,
                usesLogicalPointerCoordinates = gameSession.usesLogicalPointerCoordinates,
            )
        },
        navigateBack = session::navigateBack,
        dismissDialog = dismissDialog,
        isModalOverlayOpen = { dialogSceneHost.isShowing || loadingDialogSceneHost.isShowing },
        pointerViewport = ::rwGameViewport,
    ).also { it.install() }
    screenPresenter.apply(navigator.current, lastExternalGameFrame)

    val battleRoomCloseController = BattleRoomCloseController(
        cancelPendingStart = battleRoomLaunchController::cancelPendingStart,
        cancelPendingJoin = battleRoomJoinController::handleBattleRoomClosed,
        closeRoom = { battleRoomController.closeRoom() },
        exitToLobby = { sessionActions.exitRwGame(isMultiplayer = true) },
        showMessage = dialogController::showUnavailable,
    )
    FrameLoopInstaller(
        context = context,
        gameSession = gameSession,
        screenPresenter = screenPresenter,
        warmupController = warmupController,
        koolCanvasSceneHost = koolCanvasSceneHost,
        currentScreen = { navigator.current },
        lastExternalFrame = { lastExternalGameFrame },
        setLastExternalFrame = { frame -> lastExternalGameFrame = frame },
        multiplayerLobbyController = multiplayerLobbyController,
        battleRoomController = battleRoomController,
        battleRoomLaunchController = battleRoomLaunchController,
        resourceBrowserController = resourceBrowserController,
        inGameDialogController = inGameDialogController,
        mapController = mapController,
        sessionActions = sessionActions,
        battleRoomJoinController = battleRoomJoinController,
        pendingStartController = pendingStartController,
        externalGameController = externalGameController,
        modsController = modsController,
        inputController = inputController,
        gameReadyController = gameReadyController,
        openInGamePauseMenu = pauseController::open,
        refreshModWindow = modWindowSceneHost::refresh,
        onBattleRoomClosed = battleRoomCloseController::handle,
    ).install()

    StartupFinalizer(
        bootstrap = bootstrap,
        inputController = inputController,
        currentScreen = { navigator.current },
        multiplayerLobbyController = multiplayerLobbyController,
        resourceBrowserController = resourceBrowserController,
    ).finish()
    options.joinServer?.let { address ->
        multiplayerConnectionController.joinOriginalServer(address, roomLabel = address)
    }
    options.replay?.let { name ->
        val replays = ReplaySelectViewModel().items()
        val replay = replays.firstOrNull { it.fileName == name } ?: replays.firstOrNull { it.fileName.contains(name) }
        if (replay != null) {
            gameLaunchController.enterReplay(replay, startNew = true)
        } else {
            logger.warn { "Replay not found for --replay=$name" }
        }
    }
    return session
}
