package io.github.rwx.kool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.DesktopRendererMode
import io.github.rwx.PlatformStorage
import io.github.rwx.app.launchOnIO
import io.github.rwx.input.MultiTouchPointerState
import io.github.rwx.logger
import io.github.rwx.platform.CoreGameView
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.render.canvas.*
import io.github.rwx.session.*
import io.github.rwx.ui.model.BattleRoomPlayer
import io.github.rwx.ui.model.MapEntry
import com.corrodinggames.rts.game.units.BaseUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import io.github.rwx.session.GameSession
import io.github.rwx.session.GameSessionRendererProfile
import io.github.rwx.ui.BattleRoomUiBridge
import io.github.rwx.ui.InGameMenuController
import kotlin.math.roundToInt

/** The complete legacy loop records immutable CPU frames on its unique owner thread. */
internal class KoolDesktopGameSession(
    private val storage: PlatformStorage,
) : GameSession() {
    override val rendererMode: RendererMode = DesktopRendererMode.Kool
    override val usesLogicalPointerCoordinates: Boolean = false

    override val usesIndependentEngineLoop: Boolean = true
    private val cpuTextures = KoolCanvasCpuTextureStore()
    private val graphicsEngine = KoolGraphicsEngine(
        textureStore = cpuTextures,
        assetBytes = storage::readAssetBytes,
    )
    private val mailbox = LatestFrameMailbox()
    override val canvasPresentationTracker = CanvasFramePresentationTracker()
    @Volatile private var selectedEnvelope: FrameEnvelope? = null
    @Volatile private var newestPublishedCamera: GameCameraSnapshot? = null
    @Volatile private var livePresentationRequested = false
    private val postInputFrameWait = System.getenv("RWX_POST_INPUT_FRAME_WAIT") == "1"
    private val inputPublication = InputPublicationFence()
    private var reportedPostInputWait = false
    private var sequence = 0L
    private var viewportRevision = 0L
    private var nextUiPublication = 0L
    private var drainLayerBuffers = false
    @Volatile private var loopFailure: Throwable? = null
    private val requestedViewport = AtomicReference<KoolCanvasViewport?>()
    private val uiState = AtomicReference(UiState())
    private val owner by lazy {
        EngineOwnerLoop(
            periodNanos = {
                val settings = gameEngine?.settingsEngine
                when { settings?.batterySaving == true -> 32_258_064L
                    settings?.highRefreshRate == true -> 3_333_333L
                    else -> 16_393_442L }
            },
            tick = ::engineFrame,
            onFailure = { error ->
                loopFailure = error
                logger.error(error) { "RWX engine owner stopped after a failed loop" }
            },
        )
    }

    private data class UiState(
        val canResume: Boolean = false,
        val mapLoaded: Boolean = false,
        val ready: Boolean = false,
        val mapPath: String? = null,
        val mapName: String? = null,
        val liveRoom: Boolean = false,
        val room: BattleRoomSnapshot? = null,
        val networkActive: Boolean = false,
        val players: List<BattleRoomPlayer> = emptyList(),
        val chat: List<MultiplayerChatSnapshot> = emptyList(),
        val exitInfo: RunningMultiplayerExitInfo? = null,
        val playerName: String = "Player",
        val startedConnection: Boolean = false,
        val portals: List<String> = emptyList(),
        val localPlayer: String? = null,
        val p2pGame: Boolean = false,
        val extraMapEntries: List<MapEntry> = emptyList(),
    )

    override fun <T> submitSessionTask(action: () -> T): CompletableFuture<T> = owner.submit {
        action().also { publishUiState(force = true) }
    }

    protected override fun <T> executeEngineTask(label: String, action: () -> T): T = owner.call {
        try { action() } finally { owner.resetClock(); publishUiState(force = true) }
    }

    protected override fun <T> runEngineCommand(label: String, command: (GameEngine) -> T?): T? =
        owner.call { super.runEngineCommand(label, command).also { publishUiState(force = true) } }

    protected override fun postEngineCommand(label: String, command: (GameEngine) -> Unit) {
        owner.submit { super.runEngineCommand(label, command); publishUiState(force = true) }
    }

    override fun updateMenuMusic(deltaSeconds: Float) {
        // Audio ticks do not change UI state or the custom map catalogue.
        owner.submit {
            super.runEngineCommand("menu music") { engine ->
                engine.musicManager?.update(deltaSeconds.toGameSpeedDelta())
            }
        }
    }

    override fun canResume(): Boolean = if (owner.isOwner) super.canResume() else
        uiState.get().canResume && !loadState.asyncMapLoadInProgress
    override fun isMapLoaded(mapPath: String?): Boolean = if (owner.isOwner) super.isMapLoaded(mapPath) else
        uiState.get().let { it.mapLoaded && (mapPath == null || mapPath == it.mapPath) } && !loadState.asyncMapLoadInProgress
    override fun isReadyForDisplay(mapPath: String?): Boolean = if (owner.isOwner) super.isReadyForDisplay(mapPath) else
        uiState.get().ready && isMapLoaded(mapPath)
    override fun currentMapDisplayName(): String? = if (owner.isOwner) super.currentMapDisplayName() else uiState.get().mapName
    override fun isBattleRoomLive(): Boolean = if (owner.isOwner) super.isBattleRoomLive() else uiState.get().liveRoom
    override fun currentBattleRoom(refreshNetworkStatus: Boolean): BattleRoomSnapshot? =
        if (owner.isOwner) super.currentBattleRoom(refreshNetworkStatus) else uiState.get().room
    override fun isNetworkMultiplayerActive(): Boolean = if (owner.isOwner) super.isNetworkMultiplayerActive() else uiState.get().networkActive
    override fun multiplayerPlayerList(): List<BattleRoomPlayer> = if (owner.isOwner) super.multiplayerPlayerList() else uiState.get().players
    override fun multiplayerChatHistory(): List<MultiplayerChatSnapshot> = if (owner.isOwner) super.multiplayerChatHistory() else uiState.get().chat
    override fun runningMultiplayerExitInfo(): RunningMultiplayerExitInfo? = if (owner.isOwner) super.runningMultiplayerExitInfo() else uiState.get().exitInfo
    override fun currentMultiplayerPlayerName(): String = if (owner.isOwner) super.currentMultiplayerPlayerName() else uiState.get().playerName
    override fun hasActiveStartedGameConnection(): Boolean = if (owner.isOwner) super.hasActiveStartedGameConnection() else uiState.get().startedConnection
    override fun portalTargetMapIds(): List<String> = if (owner.isOwner) super.portalTargetMapIds() else uiState.get().portals
    override fun localPlayerId(): String? = if (owner.isOwner) super.localPlayerId() else uiState.get().localPlayer
    override fun isP2pNetworkGame(): Boolean = if (owner.isOwner) super.isP2pNetworkGame() else uiState.get().p2pGame
    override fun extraCustomMapEntries(): List<MapEntry> = if (owner.isOwner) super.extraCustomMapEntries() else uiState.get().extraMapEntries
    override fun mapLoadError(mapPath: String?): Throwable? = super.mapLoadError(mapPath) ?: loopFailure?.takeIf {
        mapPath == null || mapPath == loadState.runningMapPath
    }

    private fun publishUiState(force: Boolean = false) {
        check(owner.isOwner)
        val now = System.nanoTime()
        if (!force && now < nextUiPublication) return
        nextUiPublication = now + 100_000_000L
        uiState.set(UiState(
            super.canResume(), super.isMapLoaded(runningMapPath()), super.isReadyForDisplay(runningMapPath()),
            runningMapPath(), super.currentMapDisplayName(), super.isBattleRoomLive(),
            super.currentBattleRoom(false), super.isNetworkMultiplayerActive(),
            super.multiplayerPlayerList(), super.multiplayerChatHistory(),
            super.runningMultiplayerExitInfo(), super.currentMultiplayerPlayerName(), super.hasActiveStartedGameConnection(),
            super.portalTargetMapIds(), super.localPlayerId(), super.isP2pNetworkGame(),
            if (force) super.extraCustomMapEntries() else uiState.get().extraMapEntries,
        ))
    }

    override fun discardRunningGame() { owner.submit {
        loopFailure = null
        super.discardRunningGame()
        mailbox.publish(cpuTextures.freezeFrame(KoolCanvasFrame(appliedViewport, emptyList()),
            ++sequence, loadState.mapLoadGeneration, gameEngine?.currentTick ?: 0, viewportRevision))
        owner.resetClock(); publishUiState(true)
    } }
    override fun preload(viewport: KoolCanvasViewport): GameEngine = executeEngineTask("preload") { super.preload(viewport) }
    override fun prepareMapAsync(mapPath: String?, viewport: KoolCanvasViewport) { owner.submit { super.prepareMapAsync(mapPath, viewport) } }
    override fun prepareSavedGameAsync(saveName: String, viewport: KoolCanvasViewport) { owner.submit { super.prepareSavedGameAsync(saveName, viewport) } }
    override fun prepareReplayAsync(replayName: String, viewport: KoolCanvasViewport) { owner.submit { super.prepareReplayAsync(replayName, viewport) } }
    override fun prepareMapSnapshotAsync(snapshot: MapSnapshot, viewport: KoolCanvasViewport) { owner.submit { super.prepareMapSnapshotAsync(snapshot, viewport) } }
    override fun prepareBattleRoomAsync(config: BattleRoomLaunchConfig, viewport: KoolCanvasViewport) { owner.submit { super.prepareBattleRoomAsync(config, viewport) } }
    override fun prepareEngineAsync(viewport: KoolCanvasViewport) { owner.submit { super.prepareEngineAsync(viewport) } }
    override fun requestMap(mapPath: String?) { owner.submit { super.requestMap(mapPath) } }
    override fun requestMapSnapshot(snapshot: MapSnapshot) { owner.submit { super.requestMapSnapshot(snapshot) } }
    override fun cancelBattleRoomJoin() { owner.submit { super.cancelBattleRoomJoin() } }
    override fun leaveBattleRoom() { owner.submit { super.leaveBattleRoom(); publishUiState(true) } }

    override fun cameraSnapshot(): GameCameraSnapshot? = canvasPresentationTracker.cameraSnapshot()
        ?.takeIf { it.generation == loadState.mapLoadGeneration }
    override fun currentFrameEnvelope(): FrameEnvelope? = selectedEnvelope
    override fun close() {
        canvasPresentationTracker.close()
        owner.close()
        if (System.getenv("RWX_CANVAS_PIXEL_POOL_METRICS") == "1") {
            logger.info { "RWX pixel pool: ${cpuTextures.pixelPoolDiagnostics()}" }
            logger.info { "RWX upload buffer pool: ${KoolCanvasTextureRegistry.uploadBufferPoolDiagnostics()}" }
        }
        cpuTextures.clearIdlePixelPool()
        KoolCanvasTextureRegistry.clearIdleUploadBufferPool()
        frameTimeLog?.close()
        mailbox.close()
        selectedEnvelope?.close()
        selectedEnvelope = null
    }
    private val view = KoolDesktopCoreGameView(inGameMenuController)
    private val frameTimeLog = KoolFrameTimeLog.fromEnvironment()

    private var appliedViewport = KoolCanvasViewport(0, 0)
    private var directoriesCreated = false

    @Volatile
    private var pausedForResumeBackground = false

    @Volatile
    private var resumeBackgroundFrameReady = false

    init {
        configureRendererProfile(
            GameSessionRendererProfile(
                rendersIntoKoolCanvas = true,
                acceptsKoolInput = true,
                canStartNewSessionInPlace = true,
                usesNativeSurfaceForResumeBackground = false,
            )
        )
    }

    override fun updateFrame(
        viewport: KoolCanvasViewport,
        deltaSeconds: Float,
        drainVisibleLayerBuffers: Boolean,
    ): KoolCanvasFrame {
        requestViewport(viewport)
        if (drainVisibleLayerBuffers) owner.submit { drainLayerBuffers = true }
        return currentFrame()
    }

    private fun requestViewport(viewport: KoolCanvasViewport) {
        if (viewport.width <= 0 || viewport.height <= 0) return
        if (requestedViewport.getAndSet(viewport) != viewport) owner.submit {
            lastViewport = viewport
            activeEngineLocked()?.let { applyViewport(it, viewport) }
        }
    }

    override fun currentFrame(): KoolCanvasFrame {
        if (postInputFrameWait && livePresentationRequested && !pausedForResumeBackground && loopFailure == null) {
            val requested = inputPublication.currentRequest()
            if (!hasUnpresentedFrame() || !inputPublication.isPublished(requested)) {
                if (!reportedPostInputWait) {
                    reportedPostInputWait = true
                    println("RWXPostInputFrameWait active=true maxWaitNanos=1500000")
                }
                val start = io.github.rwx.kool.vulkan.VulkanBackendMetrics.stageStart()
                val deadline = System.nanoTime() + 1_500_000L
                try {
                    while (System.nanoTime() < deadline && loopFailure == null &&
                        (!hasUnpresentedFrame() || !inputPublication.isPublished(requested))) Thread.onSpinWait()
                } finally { io.github.rwx.kool.vulkan.VulkanBackendMetrics.stageEnd("post-input-frame-wait", start, requested) }
            }
        }
        mailbox.poll()?.let { completed ->
            selectedEnvelope?.close()
            selectedEnvelope = completed
        }
        return selectedEnvelope?.frame ?: KoolCanvasFrame(lastViewport, emptyList())
    }

    override fun loadPendingMapNow(): KoolCanvasFrame {
        owner.submit { val engine = ensureStarted(lastViewport); loadPendingMap(engine); owner.resetClock() }
        return currentFrame()
    }

    private fun engineFrame(deltaSeconds: Float) {
        if (loopFailure != null) return
        val engine = gameEngine ?: return
        if (loadState.asyncMapLoadInProgress || modReloadInProgress) return
        if (pausedForResumeBackground) {
            if (!resumeBackgroundFrameReady) renderPausedBackgroundFrame(engine, lastViewport)
            return
        }
        // The legacy outer loop also services lobby packets and deferred tasks before a map exists.
        if (engine.hasLoadedLevel) {
            io.github.rwx.benchmark.VanillaBattleBenchmark.onFrame(engine)
            io.github.rwx.benchmark.ReplayPanBenchmark.onFrame(engine)
        }
        val viewport = appliedViewport
        if (viewport.width <= 0 || viewport.height <= 0) return
        frameTimeLog?.beginFrame()
        graphicsEngine.beginFrame(viewport.width, viewport.height)
        engine.renderGraphicsEngine = graphicsEngine
        // Units resolve their movement through `engine.tileMap`, and the outer loop still runs before a
        // map exists and after it is released. Ticking there dereferences a null map and stops the owner
        // loop ("OrderableUnit.applyPositionChange: tileMap is null"), which is what killed a measured
        // fog-enabled benchmark run; the neighbouring layer-buffer work guards on the same condition.
        if (engine.hasLoadedLevel && engine.tileMap != null) {
            runGameLoop(engine, deltaSeconds)
        }
        frameTimeLog?.endGameWork()
        if (drainLayerBuffers || (engine.hasLoadedLevel && engine.tileMap != null &&
                TileMap.layerBufferManager.hasVisiblePendingRedraws())) {
            TileMap.layerBufferManager.renderVisiblePendingRedrawsNow()
        }
        frameTimeLog?.endLayerRedraw()
        publishFrame(engine, viewport)
        frameTimeLog?.endSnapshot()
        if (engine.hasLoadedLevel && engine.tileMap != null) {
            TileMap.layerBufferManager.renderOffscreenPendingRedraws(2)
        }
        publishUiState()
    }

    private fun publishFrame(engine: GameEngine, viewport: KoolCanvasViewport) {
        var selected = 0
        var visible = 0
        val units = BaseUnit.bE.a()
        for (index in 0 until BaseUnit.bE.b) {
            val unit = units[index]
            if (unit.isSelected) selected++
            if (unit.shouldDraw) visible++
        }
        val frame = graphicsEngine.snapshot().copy(
            visualStats = KoolCanvasVisualStats(selected, visible, engine.settingsEngine.adaptiveBattleVisuals),
        )
        val serial = ++sequence
        val camera = GameCameraSnapshot(serial, viewportRevision, viewport,
            engine.viewpointXSnapped, engine.viewpointYSnapped, engine.zoom,
            loadState.mapLoadGeneration, engine.sidebarWidth,
            graphicsEngine.hudLayout?.copy(minimap = captureGameMinimapRect(engine)))
        val envelope = cpuTextures.freezeFrame(frame, serial, loadState.mapLoadGeneration,
            engine.currentTick, viewportRevision, camera)
        lastFrame = envelope.frame
        CanvasFrameMetrics.produced(envelope)
        mailbox.publish(envelope)
        newestPublishedCamera = camera
        if (postInputFrameWait) inputPublication.published()
    }

    /** Read-only publication metadata; presentation neither calls the engine nor changes its clock. */
    internal fun hasUnpresentedFrame(): Boolean {
        if (!livePresentationRequested || pausedForResumeBackground || loopFailure != null) return true
        val publication = newestPublishedCamera ?: return true
        val presented = canvasPresentationTracker.cameraSnapshot() ?: return true
        return publication.generation > presented.generation ||
            publication.generation == presented.generation && publication.revision > presented.revision
    }

    override fun setGameVisible(visible: Boolean, viewport: KoolCanvasViewport, koolOverlay: Boolean, pausedBackground: Boolean) {
        livePresentationRequested = visible && !pausedBackground
        requestViewport(viewport)
        owner.submit {
            val engine = activeEngineLocked()
            val pause = pausedBackground && engine?.hasLoadedLevel == true &&
                !engine.isMenuBackgroundMap && !engine.isNetworkGameActive()
            if (pause != pausedForResumeBackground) {
                pausedForResumeBackground = pause
                resumeBackgroundFrameReady = false
                owner.resetClock()
            }
            if (pause && !resumeBackgroundFrameReady && engine != null) renderPausedBackgroundFrame(engine, lastViewport)
        }
    }

    override fun submitPointer(screenX: Float, screenY: Float, isDown: Boolean, pointerId: Int) {
        submitPointer(screenX, screenY, isDown, pointerId, GamePointerFrameContext(cameraSnapshot()))
    }

    override fun submitPointer(
        screenX: Float, screenY: Float, isDown: Boolean, pointerId: Int, frameContext: GamePointerFrameContext,
    ) {
        val camera = frameContext.camera
        val responseKind = if (io.github.rwx.benchmark.ReplayNormalInputProbe.activatingDrag)
            "pointer/activation" else "pointer/$pointerId"
        val responseId = CanvasFrameMetrics.inputSampled(responseKind, if (isDown) 1 else 0)
        val inputTicket = if (postInputFrameWait) inputPublication.request() else 0L
        owner.submitInput("pointer", isDown) {
            if (postInputFrameWait) inputPublication.adopted(inputTicket)
            if (isDown && camera != null && camera.generation != loadState.mapLoadGeneration) return@submitInput
            val engine = gameEngine
            val screenRelative = engine?.settingsEngine?.let {
                isScreenRelativePointer(pointerId, it.mouseSupport, it.mouseOrders)
            } ?: true
            val (x, y) = mapPointer(engine, frameContext, screenX, screenY, screenRelative)
            view.submitPointer(x, y, isDown, pointerId)
            CanvasFrameMetrics.inputApplied(responseId, engine?.viewpointXSnapped ?: Float.NaN,
                engine?.viewpointYSnapped ?: Float.NaN, engine?.zoom ?: Float.NaN)
        }
    }

    override fun movePointer(screenX: Float, screenY: Float) {
        movePointer(screenX, screenY, GamePointerFrameContext(cameraSnapshot()))
    }

    override fun movePointer(screenX: Float, screenY: Float, frameContext: GamePointerFrameContext) {
        val responseId = CanvasFrameMetrics.inputSampled("pointer-move", 0)
        val inputTicket = if (postInputFrameWait) inputPublication.request() else 0L
        owner.submit {
            if (postInputFrameWait) inputPublication.adopted(inputTicket)
            val (x, y) = mapPointer(gameEngine, frameContext, screenX, screenY, screenRelative = true)
            view.movePointer(x, y)
            val engine = gameEngine
            CanvasFrameMetrics.inputApplied(responseId, engine?.viewpointXSnapped ?: Float.NaN,
                engine?.viewpointYSnapped ?: Float.NaN, engine?.zoom ?: Float.NaN)
        }
    }

    private fun mapPointer(engine: GameEngine?, frameContext: GamePointerFrameContext, x: Float, y: Float,
        screenRelative: Boolean): Pair<Float, Float> {
        val current = engine?.let { GameCameraSnapshot(0, viewportRevision, appliedViewport,
            it.viewpointXSnapped, it.viewpointYSnapped, it.zoom, loadState.mapLoadGeneration, it.sidebarWidth,
            captureGameHudLayout(it)) }
        return projectSeenPointer(frameContext.camera, current, appliedViewport, x, y,
            frameContext.surfaceViewport, screenRelative)
    }

    override fun clearInputState() {
        val inputTicket = if (postInputFrameWait) inputPublication.request() else 0L
        owner.submitInput("pointer", false) {
            if (postInputFrameWait) inputPublication.adopted(inputTicket)
            view.submitPointer(0f, 0f, false, -1)
        }
    }
    override fun submitKey(androidKeyCode: Int, isDown: Boolean) {
        val responseId = CanvasFrameMetrics.inputSampled("key/$androidKeyCode", if (isDown) 1 else 0)
        val inputTicket = if (postInputFrameWait) inputPublication.request() else 0L
        owner.submitInput("key/$androidKeyCode", isDown) {
            if (postInputFrameWait) inputPublication.adopted(inputTicket)
            val engine = gameEngine
            engine?.setKeyState(androidKeyCode, isDown)
            CanvasFrameMetrics.inputApplied(responseId, engine?.viewpointXSnapped ?: Float.NaN,
                engine?.viewpointYSnapped ?: Float.NaN, engine?.zoom ?: Float.NaN)
        }
    }

    override fun submitMouseWheel(amount: Int) {
        if (amount == 0) return
        val responseId = CanvasFrameMetrics.inputSampled("wheel", amount)
        val inputTicket = if (postInputFrameWait) inputPublication.request() else 0L
        owner.submit {
            if (postInputFrameWait) inputPublication.adopted(inputTicket)
            super.runEngineCommand("mouse wheel") { engine ->
                engine.queueMouseWheelDelta(amount)
                CanvasFrameMetrics.inputApplied(responseId, engine.viewpointXSnapped, engine.viewpointYSnapped, engine.zoom)
            }
            publishUiState(force = true)
        }
    }

    override fun prepareMenuBackgroundAsync(viewport: KoolCanvasViewport) {
        owner.submit { prepareMenuBackgroundOnOwner(viewport) }
    }

    private fun prepareMenuBackgroundOnOwner(viewport: KoolCanvasViewport) {
        val state = loadState
        if (state.menuBackgroundActive) {
            return
        }
        if (state.runningMapPath != null && uiState.get().mapLoaded) {
            return
        }
        var generation: Long? = null
        updateLoadState { current ->
            if (current.asyncMapLoadInProgress || current.menuBackgroundActive) {
                generation = null
                current
            } else {
                current.copy(
                    mapLoadGeneration = current.mapLoadGeneration + 1,
                    asyncMapLoadPath = MENU_BACKGROUND_REQUEST,
                    asyncMapLoadInProgress = true,
                    asyncMapLoadError = null,
                ).also { generation = it.mapLoadGeneration }
            }
        }
        val issuedGeneration = generation ?: return
        lastFrame = KoolCanvasFrame(viewport, emptyList())
        logger.info { "Preparing $sessionLogName RW menu background asynchronously" }
        launchOnIO("${rendererMode.id}-menu-background-loader") {
            loadMenuBackgroundInBackground(viewport, issuedGeneration)
        }
    }

    override fun isMenuBackgroundActive(): Boolean {
        val state = loadState
        return state.menuBackgroundActive ||
                (state.asyncMapLoadInProgress && state.asyncMapLoadPath == MENU_BACKGROUND_REQUEST)
    }

    override fun adoptStartedGameFromEngine(viewport: KoolCanvasViewport): Boolean =
        executeEngineTask("adopt started game") { synchronized(gameLock) {
            val engine = gameEngine ?: GameEngine.getInstance() ?: return@synchronized false
            if (engine.networkEngine?.gameHasBeenStarted != true) return@synchronized false

            lastViewport = viewport
            if (gameEngine == null) {
                gameEngine = engine
            }
            applyViewport(engine, viewport)

            // The original battleroom runs startGameCommon() before opening the game surface, and
            // there is no renderer callback equivalent to Slick's to finish that load here.
            BattleRoomUiBridge.setupGame()
            val activeMapPath = engine.networkEngine.selectedMapPath?.takeIf { it.isNotBlank() }
                ?: engine.currentMapPath?.takeIf { it.isNotBlank() }
                ?: return@synchronized false

            updateLoadState {
                it.copy(
                    pendingMapPath = null,
                    asyncMapLoadPath = null,
                    asyncMapLoadInProgress = false,
                    asyncMapLoadError = null,
                    menuBackgroundActive = false,
                    runningMapPath = activeMapPath,
                )
            }
            engine.isStopped = false
            engine.isPaused = false
            loopFailure = null
            owner.resetClock()
            true
        } }

    protected override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine =
        executeEngineTask("start engine") { synchronized(gameLock) {
            activeEngineLocked() ?: ensureRendererEngine(viewport, graphicsEngine, view).also {
                if (!directoriesCreated) {
                    directoriesCreated = true
                    storage.createDirectories()
                }
            }
        }

    }

    protected override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) {
        val width = viewport.width.coerceAtLeast(1)
        val height = viewport.height.coerceAtLeast(1)
        if (appliedViewport.width == width && appliedViewport.height == height) {
            return
        }
        engine.updateWindowResolution(width, height)
        graphicsEngine.a(width, height)
        view.onSizeChanged()
        appliedViewport = KoolCanvasViewport(width, height)
        viewportRevision++
    }

    private fun runGameLoop(engine: GameEngine, deltaSeconds: Float) {
        engine.gameLoop(
            deltaSeconds.toGameSpeedDelta(),
            (deltaSeconds * 1000f).roundToInt().coerceAtLeast(0),
        )
    }

    /**
     * Draws the world without the in-game HUD and publishes it as the frame behind the menu.
     * Runs on the engine owner, since legacy drawing can itself change engine state.
     */
    private fun renderPausedBackgroundFrame(engine: GameEngine, viewport: KoolCanvasViewport) {
        if (!engine.hasLoadedLevel || viewport.width <= 0 || viewport.height <= 0) {
            return
        }
        graphicsEngine.beginFrame(viewport.width, viewport.height)
        engine.renderGraphicsEngine = graphicsEngine
        runCatching {
            (engine as GameLogic).drawWorldOnlyThreadSafe(0f)
        }.onFailure { error ->
            logger.error(error) { "$sessionLogName paused background render failed" }
        }
        resumeBackgroundFrameReady = true
        publishFrame(engine, viewport)
    }

    protected override fun prepareFrameAfterBackgroundLoad(engine: GameEngine, viewport: KoolCanvasViewport): KoolCanvasFrame {
        loopFailure = null
        owner.resetClock()
        resumeBackgroundFrameReady = false
        graphicsEngine.beginFrame(viewport.width.coerceAtLeast(1), viewport.height.coerceAtLeast(1))
        engine.renderGraphicsEngine = graphicsEngine
        runGameLoop(engine, 0f)
        publishFrame(engine, viewport)
        return lastFrame
    }

    private fun loadMenuBackgroundInBackground(requestedViewport: KoolCanvasViewport, generation: Long) {
        val startedAt = System.nanoTime()
        var loadedCurrentRequest = false
        runCatching {
            executeEngineTask("menu background") { synchronized(gameLock) {
                if (generation != loadState.mapLoadGeneration) {
                    return@synchronized
                }
                val viewport = requestedViewport.takeIf { it.width > 0 && it.height > 0 }
                    ?: defaultPreloadViewport
                lastViewport = viewport
                val engine = ensureStarted(viewport)
                applyViewport(engine, viewport)
                engine.isStopped = true
                engine.isPaused = true
                engine.loadMenuBackground()
                if (!engine.hasLoadedLevel || !engine.isMenuBackgroundMap) {
                    error("RW menu background map did not load")
                }
                engine.targetZoom *= MENU_BACKGROUND_ZOOM_MULTIPLIER
                engine.zoom = engine.targetZoom * engine.densityZoomScale
                engine.updateWindowResolution(
                    engine.screenWidth.toInt(),
                    engine.screenHeight.toInt(),
                    engine.renderSurfaceScale,
                )
                val currentMapPath = engine.currentMapPath
                updateLoadState { current ->
                    if (current.mapLoadGeneration == generation) {
                        current.copy(runningMapPath = currentMapPath, menuBackgroundActive = true)
                            .also { loadedCurrentRequest = true }
                    } else {
                        current
                    }
                }
            }
            }
            if (loadedCurrentRequest) {
                logger.info {
                    "Prepared $sessionLogName RW menu background: ${loadState.runningMapPath ?: "<unknown>"} " +
                            "(${elapsedMs(startedAt)}ms)"
                }
            } else {
                logger.info { "Discarded stale $sessionLogName RW menu background preparation" }
            }
        }.onFailure { error ->
            if (loadState.mapLoadGeneration == generation) {
                updateLoadState { current ->
                    if (current.mapLoadGeneration == generation) {
                        current.copy(menuBackgroundActive = false, asyncMapLoadError = error)
                    } else {
                        current
                    }
                }
                logger.error(error) { "$sessionLogName RW menu background load failed" }
            }
        }.also {
            updateLoadState { current ->
                if (current.mapLoadGeneration == generation) {
                    current.copy(asyncMapLoadInProgress = false, asyncMapLoadPath = null)
                } else {
                    current
                }
            }
        }
    }

    private class KoolDesktopCoreGameView(
        private val menuController: InGameMenuController,
    ) : CoreGameView {
        private val pointerState = MultiTouchPointerState()
        private var rendering = true

        override fun pause() {
            rendering = false
        }

        // The session drives the loop itself, so the engine must always take its external-driver
        // branch; pause/resume is expressed through the session's own visibility state.
        override fun isPaused(): Boolean = true

        override fun isContinuousRendering(): Boolean = true

        override fun isRendering(): Boolean = rendering

        override fun getInGameMenuController(): InGameMenuController = menuController

        override fun onResume() {
            rendering = true
        }

        override fun getSettings(): MultiTouchPointerState = pointerState

        override fun onSizeChanged() = Unit

        override fun stopRender() {
            rendering = false
        }

        fun submitPointer(screenX: Float, screenY: Float, isDown: Boolean, pointerId: Int) {
            pointerState.processEvent(screenX, screenY, isDown, pointerId)
        }

        fun movePointer(screenX: Float, screenY: Float) {
            pointerState.setStart(screenX, screenY)
        }
    }

    private companion object {
        const val MENU_BACKGROUND_REQUEST = "<menu-background>"
        const val MENU_BACKGROUND_ZOOM_MULTIPLIER = 2f
    }
}
