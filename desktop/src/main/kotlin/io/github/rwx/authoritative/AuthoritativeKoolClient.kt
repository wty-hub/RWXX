package io.github.rwx.authoritative

import com.corrodinggames.rts.gameFramework.GameEngine
import de.fabmax.kool.KoolApplication
import de.fabmax.kool.createContext
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.util.FrontendScope
import de.fabmax.kool.util.MsdfFont
import io.github.rwx.*
import io.github.rwx.net.authoritative.ViewUnitOrder
import io.github.rwx.net.authoritative.ViewActionCommand
import io.github.rwx.net.authoritative.PlayerViewSnapshot
import io.github.rwx.net.authoritative.PlayerActionPanel
import io.github.rwx.render.canvas.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.lwjgl.vulkan.VK10.vkDeviceWaitIdle
import java.awt.GraphicsEnvironment
import java.awt.event.*
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Probe fields from one already sampled frame; null never borrows another pane or render. */
internal fun timelinePaneSourceProbeFields(frame: TimelineDisplayFrame?): String =
    "\"sourceMode\":${frame?.let { "\"${it.sourceMode.name}\"" } ?: "null"}," +
        "\"sourceStreamId\":${frame?.streamId},\"sourceEpoch\":${frame?.epoch}," +
        "\"sourceRevision\":${frame?.revision},\"sourceControlEpoch\":${frame?.controlEpoch}," +
        "\"sampledTime\":${frame?.time},\"sourceObservedThrough\":${frame?.observedThrough}," +
        "\"sourceRetainedFrom\":${frame?.retainedFrom},\"sourceRetainedUntil\":${frame?.retainedUntil}," +
        "\"sourceSealedThrough\":${frame?.sealedThrough}"

/** A completed binding counts only when this same render actually sampled that HISTORY. */
internal fun timelinePaneHistoryProbe(frame: TimelineDisplayFrame?, completed: io.github.rwx.net.timeline.TimelineHistoryEndReceipt?): String {
    if (frame == null || completed == null || frame.sourceMode != io.github.rwx.net.timeline.TimelineStreamMode.HISTORY ||
        frame.streamId != completed.streamId || frame.epoch != completed.epoch || frame.revision != completed.revision ||
        frame.controlEpoch != 0L || frame.retainedFrom != completed.acceptedFrom || frame.retainedUntil != completed.acceptedUntil)
        return "null"
    return "{\"streamId\":${completed.streamId},\"epoch\":${completed.epoch},\"revision\":${completed.revision}," +
        "\"acceptedFrom\":${completed.acceptedFrom},\"acceptedUntil\":${completed.acceptedUntil},\"endReason\":2}"
}

/** Allows replay adapters and display tests to share the exact view-only presentation path. */
internal interface ViewSource : AutoCloseable {
    val teamId: Int
    val isConnected: Boolean
    val failureMessage: String?
    val isSynchronizing: Boolean get() = false
    val ticksPerSecond: Int get() = 60
    val readOnly: Boolean get() = false
    val label: String get() = "Connected"
    val protocolVersion: Int get() = io.github.rwx.net.timeline.TimelineProtocol.VERSION
    fun latestTimelineFrame(): TimelineDisplayFrame? = null
    fun selectionChanged(handles: List<Long>) {}
    fun latestSnapshot(): PlayerViewSnapshot?
    fun latestActionPanel(): PlayerActionPanel? = null
    fun timelineWireStatistics(): io.github.rwx.net.timeline.TimelineWireStatistics? = null
    fun sendAction(intent: ViewActionCommand) { error("This view source does not accept actions") }
    fun send(intent: ViewUnitOrder)
}

/** One context owns this atlas; a loader finishing during shutdown cannot publish another font. */
internal class AuthoritativeFontOwnership(
    private val publish: (MsdfFont) -> Unit,
    private val unpublish: (MsdfFont) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var owned: MsdfFont? = null

    @Synchronized
    fun accept(font: MsdfFont): Boolean {
        if (closed || owned != null) {
            if (owned !== font) font.data.map.release()
            return false
        }
        try {
            publish(font)
            owned = font
            return true
        } catch (failure: Throwable) {
            font.data.map.release()
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val font = owned ?: return
        owned = null
        try { unpublish(font) }
        finally { font.data.map.release() }
    }
}

/**
 * Kool world presentation backed only by permitted DTOs and a closed sprite catalog. Unlike the
 * ordinary desktop application this entry never installs GameSession, warmup, a map, or mods.
 */
internal object AuthoritativeKoolClient {
    fun run(connection: TimelinePlaybackSource, onClose: () -> Unit = {}, onReady: () -> Unit = {}) = runBlocking {
        val cleaned = AtomicBoolean(false)
        var window: SwingKoolHost? = null
        var store: KoolCanvasCpuTextureStore? = null
        var assets: AuthoritativeVisualAssets? = null
        var fontOwnership: AuthoritativeFontOwnership? = null
        var fontLoading: Job? = null
        fun clean() {
            if (!cleaned.compareAndSet(false, true)) return
            connection.close()
            runCatching(onClose).onFailure { System.err.println("Authority client cleanup: ${it.message}") }
        }
        try {
            check(!SwingUtilities.isEventDispatchThread()) { "Run the Kool client outside the AWT event thread" }
            check(!GraphicsEnvironment.isHeadless()) { "The Kool client requires a graphical display" }
            check(GameEngine.getInstance() == null) { "The view client requires a process without a local game simulation" }
            configureDesktopLogging()
            configureLwjglMemoryStack()
            LinuxInputMethodBootstrap.install()
            System.setProperty("org.lwjgl.opengl.contextAPI", "native")
            System.setProperty("kool.transparentFramebuffer", "false")
            if (GlobalContext.getOrNull() == null) GlobalContext.startKoin {
                // No GameSession, ModRepository, unit registry or audio device is created here.
                modules(module {
                    single<PlatformBridge> { DesktopPlatformBridge(audio = NoopPlatformAudio) }
                    single<PlatformStorage> { get<PlatformBridge>().storage }
                    single<PreferenceStorage> { get<PlatformBridge>().preferenceStorage }
                    single<AppLogger> { get<PlatformBridge>().logger }
                })
            }
            val platform = GlobalContext.get().get<PlatformBridge>()
            val backend = KoolDesktopMain.resolveDesktopRenderBackend(System.getProperty("rwx.kool.backend") ?: System.getenv("RWX_KOOL_BACKEND"))
            if (backend !== RenderBackendVk.Companion) KoolCanvasTextureRegistry.configureNativeBgraUploads(false)
            val host = SwingKoolHost.create(fullscreen = false, useOpenGl = backend === RenderBackendGl.Companion)
            window = host
            platform.filePickerHost = host
            SwingUtilities.invokeAndWait {
                SwingUtilities.getWindowAncestor(host.koolCanvas)?.addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(event: WindowEvent) { clean() }
                })
            }
            val context = createContext(KoolDesktopMain.createKoolConfig(host, backend))
            val textures = KoolCanvasCpuTextureStore()
            store = textures
            val catalog = AuthoritativeVisualAssets(textures, platform.storage::readAssetBytes)
            assets = catalog
            val timelineSource = connection
            fun createRenderer(paneId: Int) = AuthoritativeCanvasRenderer(catalog, connection::send,
                connection::sendAction, selectionChanged = { handles ->
                    if (!connection.readOnly && timelineSource.activePane == paneId)
                        connection.selectionChanged(handles)
                })
            val paneRenderers = mutableMapOf(0 to createRenderer(0))
            var renderer = paneRenderers.getValue(0)
            var paneLayouts = TimelineCanvasPanes.layout(KoolCanvasViewport(1280, 720), listOf(0))
            val actionProbe = AuthoritativeActionProbe(System.getenv("RWX_AUTHORITY_ACTION_PROBE") == "1", connection.readOnly)
            val tracker = CanvasFramePresentationTracker()
            val canvas = KoolCanvasSceneHost(sceneName = "rwx-authority-canvas")
            canvas.setPresentationTracker(tracker)
            if (backend === RenderBackendVk.Companion) {
                canvas.setGpuRetirementSink(io.github.rwx.kool.vulkan.VulkanFrameLifecycle::retainUntilFrameComplete)
                canvas.setGpuRetirementPendingCounter(io.github.rwx.kool.vulkan.VulkanFrameLifecycle::pendingRetirements)
            }
            val scene = canvas.createScene()
            context.addScene(scene)
            val previousFont = KoolCanvasFontRegistry.base
            val fonts = AuthoritativeFontOwnership(
                publish = { font ->
                    val atlas = font.data.meta.atlas
                    check(atlas.width in 1..4096 && atlas.height in 1..4096) { "Authority CJK atlas exceeds the portable texture limit" }
                    KoolCanvasFontRegistry.installBaseFont(font)
                },
                unpublish = { font ->
                    if (KoolCanvasFontRegistry.base.data === font.data) KoolCanvasFontRegistry.installBaseFont(previousFont)
                },
            )
            fontOwnership = fonts
            // Scene release precedes onShutdown. Kool then pumps delayed texture releases before
            // backend cleanup, so this owned atlas is released without freeing shared/default fonts.
            context.onShutdown += {
                fontLoading?.cancel()
                (context.backend as? RenderBackendVk)?.let { vk ->
                    // Scene release queued its last installed frame behind the submission fence.
                    // Drain it before Kool pumps delayed Texture.release() calls, not later from
                    // backend.cleanup, when that pump has already finished. One real idle wait
                    // at shutdown is required before declaring these last GPU readers complete.
                    val pending = io.github.rwx.kool.vulkan.VulkanFrameLifecycle.pendingRetirements()
                    io.github.rwx.kool.vulkan.VulkanFrameLifecycle.checkedDeviceIdle(vkDeviceWaitIdle(vk.device.vkDevice))
                    io.github.rwx.kool.vulkan.VulkanFrameLifecycle.deviceIdle(vk, destroying = false)
                    if (System.getenv("RWX_AUTHORITY_CANVAS_PROBE") != null) {
                        println("AuthorityCanvasShutdown checkedDeviceIdle=true retirements=$pending->${io.github.rwx.kool.vulkan.VulkanFrameLifecycle.pendingRetirements()}")
                    }
                }
                fonts.close()
                clean(); tracker.close(); catalog.close(); textures.close()
            }
            val inputs = ArrayBlockingQueue<() -> Unit>(256)
            var deferPaneInputs = false
            fun queue(action: () -> Unit) {
                if (!inputs.offer(action)) {
                    inputs.clear()
                    inputs.offer { paneRenderers.values.forEach { it.cancelInput() } }
                }
            }
            fun projected(x: Int, y: Int): Pair<Float, Float> =
                (x.toFloat() * context.window.size.x / host.koolCanvas.width.coerceAtLeast(1)) to
                    (y.toFloat() * context.window.size.y / host.koolCanvas.height.coerceAtLeast(1))
            fun localInput(x: Float, y: Float, focus: Boolean = false): Pair<Float, Float>? {
                val pane = if (focus) TimelineCanvasPanes.hitTest(paneLayouts, KoolCanvasPoint(x, y))
                    else paneLayouts.firstOrNull { it.paneId == timelineSource.activePane }
                pane ?: return null
                if (focus) timelineSource.activePane = pane.paneId
                renderer = paneRenderers.getOrPut(pane.paneId) { createRenderer(pane.paneId) }
                return x - pane.rect.left to y - pane.rect.top
            }
            SwingUtilities.invokeAndWait {
                host.koolCanvas.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(event: MouseEvent) {
                        val x = event.x; val y = event.y; val button = event.button; val shift = event.isShiftDown
                        queue {
                            val p = projected(x, y)
                            localInput(p.first, p.second, focus = true)?.let { renderer.pointerDown(it.first, it.second, button, shift) }
                        }
                    }
                    override fun mouseReleased(event: MouseEvent) {
                        val x = event.x; val y = event.y; val button = event.button
                        queue {
                            val p = projected(x, y)
                            localInput(p.first, p.second)?.let { renderer.pointerUp(it.first, it.second, button) }
                        }
                    }
                })
                host.koolCanvas.addMouseMotionListener(object : MouseMotionAdapter() {
                    override fun mouseMoved(event: MouseEvent) {
                        val x = event.x; val y = event.y
                        queue {
                            val p = projected(x, y)
                            localInput(p.first, p.second)?.let { renderer.pointerMoved(it.first, it.second) }
                        }
                    }
                    override fun mouseDragged(event: MouseEvent) = mouseMoved(event)
                })
                host.koolCanvas.addMouseWheelListener { event ->
                    val x = event.x; val y = event.y; val rotation = event.wheelRotation
                    queue {
                        val p = projected(x, y)
                        localInput(p.first, p.second, focus = true)?.let { renderer.wheel(it.first, it.second, rotation) }
                    }
                }
                host.koolCanvas.addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(event: KeyEvent) {
                        val code = event.keyCode; val control = event.isControlDown || event.isMetaDown; val shift = event.isShiftDown
                        queue {
                            if (!timelineKey(connection, code, control, shift))
                                renderer.keyPressed(code, control, shift)
                            else {
                                renderer.cancelInput()
                                deferPaneInputs = true
                            }
                        }
                    }
                })
            }
            host.setHostFocusLostHandler { inputs.clear(); queue { paneRenderers.values.forEach { it.cancelInput() } } }
            fontLoading = FrontendScope.launch {
                try {
                    val font = MsdfFont("font/NotoSansCJKsc-Regular").getOrElse {
                        throw IllegalStateException("Failed to load the authority CJK font", it)
                    }
                    fonts.accept(font)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    System.err.println("Authority UI font loading: ${failure.message}")
                }
            }
            val probe = System.getenv("RWX_AUTHORITY_CANVAS_PROBE")?.let(::File)?.also { it.parentFile?.mkdirs(); it.writeText("") }
            val maxFrames = System.getenv("RWX_AUTHORITY_CANVAS_MAX_FRAMES")?.toLongOrNull()?.takeIf { it > 0 }
            val exitAfter = System.getenv("RWX_DEBUG_AUTO_EXIT_SECONDS")?.toLongOrNull()?.takeIf { it > 0 }
                ?.let { System.nanoTime() + it * 1_000_000_000L }
            var frames = 0L
            var viewportRevision = 0L
            var previousViewport: KoolCanvasViewport? = null
            var closeRequested = false
            var readyPublished = false
            var probeFailureReported = false
            context.onRender += {
                CanvasFramePresentation.beginFrame()
                val size = context.window.size
                val viewport = KoolCanvasViewport(size.x.coerceAtLeast(1), size.y.coerceAtLeast(1))
                if (viewport != previousViewport) { viewportRevision++; previousViewport = viewport }
                val now = System.nanoTime()
                val ids = timelineSource.playback.paneIds()
                paneLayouts = TimelineCanvasPanes.layout(viewport, ids)
                paneRenderers.keys.retainAll(ids.toSet())
                // This render's exact samples only. Never sample again for diagnostics: doing so
                // can advance playback, request HISTORY, or associate a different library revision.
                val sampledFrames = if (probe != null) HashMap<Int, TimelineDisplayFrame?>(4) else null
                val activeFrame = timelineSource.latestTimelineFrame()
                for (pane in paneLayouts) {
                    val target = paneRenderers.getOrPut(pane.paneId) { createRenderer(pane.paneId) }
                    val timeline = if (pane.paneId == timelineSource.activePane) activeFrame else timelineSource.samplePane(pane.paneId, now)
                    sampledFrames?.put(pane.paneId, timeline)
                    target.setViewport(pane.viewport, context.window.renderScale)
                    val label = "${connection.label} | View ${pane.paneId + 1}" +
                        (timeline?.let { " | ${"%.1f".format(it.time / (connection.ticksPerSecond * 1024.0))}s" } ?: " | loading")
                    target.connectionState(connection.isConnected, connection.failureMessage,
                        timeline?.readOnly ?: !timelineSource.playback.followsLive(pane.paneId), label,
                        timeline == null || connection.isSynchronizing)
                    timeline?.let { target.updateTimelineFrame(it, now) } ?: target.clearTimelineFrame()
                }
                renderer = paneRenderers.getValue(timelineSource.activePane)
                if (actionProbe.enabled) queue {
                    // This uses renderer coordinates and the same input queue as normal events.
                    // The tracker revision belongs to a completed presentation, not this submit.
                    val input = actionProbe.nextInput(renderer.actionProbeUi(), tracker.cameraSnapshot()?.revision ?: -1)
                    when (input) {
                        is AuthoritativeActionProbe.Input.Click -> {
                            renderer.pointerDown(input.point.x, input.point.y, MouseEvent.BUTTON1)
                            renderer.pointerUp(input.point.x, input.point.y, MouseEvent.BUTTON1)
                        }
                        is AuthoritativeActionProbe.Input.Key -> renderer.keyPressed(input.key, control = input.control)
                        null -> Unit
                    }
                    if (input != null) actionProbe.inputResult(renderer.actionProbeUi())
                }
                deferPaneInputs = false
                for (input in 0 until 256) {
                    (inputs.poll() ?: break).invoke()
                    if (deferPaneInputs) break
                }
                val activeForFrame = timelineSource.activePane.takeIf { active -> paneLayouts.any { it.paneId == active } }
                    ?: paneLayouts.first().paneId
                renderer = paneRenderers.getValue(activeForFrame)
                val frame = TimelineCanvasPanes.compose(viewport,
                    paneLayouts.map { it.paneId to paneRenderers.getValue(it.paneId).render(now) }, activeForFrame)
                val number = ++frames
                if (actionProbe.enabled) actionProbe.recordFrame(number, renderer.actionProbeUi())
                canvas.submit(textures.freezeFrame(frame, number, 1L, renderer.tick, viewportRevision,
                    renderer.cameraSnapshot(number, viewportRevision)))
                host.recoverFullscreenSurface(context)
                KoolDesktopMain.syncDesktopFrameRateLimit(context)
                if (!readyPublished && tracker.cameraSnapshot() != null && renderer.viewSequence >= 0) {
                    onReady()
                    readyPublished = true
                }
                if (probe != null && (frames == 1L || frames % 30L == 0L)) {
                    val noEngine = GameEngine.getInstance() == null
                    val visual = renderer.mapVisualProfile()
                    val paneProfiles = paneLayouts.joinToString(prefix = "[", postfix = "]") { pane ->
                        val local = paneRenderers.getValue(pane.paneId)
                        "{\"paneId\":${pane.paneId},\"x\":${pane.rect.left.toInt()},\"y\":${pane.rect.top.toInt()},\"width\":${pane.viewport.width},\"height\":${pane.viewport.height},\"viewSequence\":${local.viewSequence},\"tick\":${local.tick},\"readOnly\":${local.actionProbeUi().readOnly},\"renderedGroundCells\":${local.renderedGroundCells},${timelinePaneSourceProbeFields(sampledFrames?.get(pane.paneId))},\"completedHistory\":${timelinePaneHistoryProbe(sampledFrames?.get(pane.paneId), timelineSource.completedHistoryForPane(pane.paneId))}}"
                    }
                    val renderedViews = paneLayouts.count { paneRenderers.getValue(it.paneId).let { local ->
                        local.viewSequence > 0 && local.renderedGroundCells > 0
                    } }
                    val wire = connection.timelineWireStatistics()?.let {
                        "{\"protocolVersion\":7,\"recvBytes\":${it.receivedBytes},\"writtenBytes\":${it.writtenBytes},\"mapInitializations\":${it.mapInitializations},\"mapCacheHits\":${it.mapCacheHits},\"transactions\":${it.transactions},\"restores\":${it.restores},\"stateChecks\":${it.stateChecks},\"stateAcks\":${it.stateAcks},\"stateMismatches\":${it.stateMismatches},\"stateDigestNanos\":${it.stateDigestNanos}}"
                    } ?: "null"
                    val inputWrites = timelineSource.inputWriteStatistics()?.let {
                        "{\"commandsWritten\":${it.commandsWritten},\"selectionsWritten\":${it.selectionsWritten}}"
                    } ?: "null"
                    probe.appendText("{\"submittedFrames\":$frames,\"presentedRevision\":${tracker.cameraSnapshot()?.revision ?: -1},\"viewSequence\":${renderer.viewSequence},\"tick\":${renderer.tick},\"width\":${size.x},\"height\":${size.y},\"connected\":${connection.isConnected},\"readOnly\":${connection.readOnly},\"noGameEngine\":$noEngine,\"backend\":\"${backend.displayName}\"," +
                        "\"catalogVersion\":${visual.catalogVersion},\"groundCells\":${visual.groundCells},\"decorationCells\":${visual.decorationCells},\"resourcePools\":${visual.resourcePools},\"groundSha256\":\"${visual.groundSha256}\",\"decorationSha256\":\"${visual.decorationSha256}\"," +
                        "\"renderedGroundCells\":${renderer.renderedGroundCells},\"renderedDecorationCells\":${renderer.renderedDecorationCells},\"terrainTextures\":${renderer.terrainTextures},\"viewCount\":${paneLayouts.size},\"activePane\":$activeForFrame,\"renderedViewCount\":$renderedViews,\"viewports\":$paneProfiles,\"wire\":$wire,\"inputWrites\":$inputWrites,\"actionProbe\":${actionProbe.json()}}\n")
                    check(noEngine) { "The view client initialized a game simulation" }
                }
                if (!probeFailureReported && actionProbe.failure != null) {
                    probeFailureReported = true
                    System.err.println("Authority action probe failed: ${actionProbe.failure}")
                }
                if (!closeRequested && ((maxFrames != null && frames >= maxFrames) || (exitAfter != null && System.nanoTime() >= exitAfter))) {
                    closeRequested = true; clean(); host.requestClose()
                }
            }
            KoolApplication(context).ctx.run()
        } finally {
            fontLoading?.cancel()
            fontOwnership?.close()
            clean()
            assets?.close()
            store?.close()
            window?.requestClose()
        }
    }

    private fun timelineKey(source: TimelinePlaybackSource, key: Int, control: Boolean, shift: Boolean): Boolean {
        val playback = source.playback
        val pane = source.activePane
        val now = System.nanoTime()
        when (key) {
            KeyEvent.VK_F5 -> playback.followLive(pane)
            KeyEvent.VK_F6 -> playback.pause(pane, now)
            KeyEvent.VK_F7 -> playback.play(pane, -1.0, now)
            KeyEvent.VK_F8 -> playback.play(pane, 1.0, now)
            KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET -> {
                val time = playback.position(pane, now).time
                val distance = source.ticksPerSecond.toLong() * 1024 * if (shift) 1 else 10
                val target = if (key == KeyEvent.VK_OPEN_BRACKET) (time - distance).coerceAtLeast(playback.historyFrom)
                    else if (time > io.github.rwx.net.timeline.TimelineProtocol.MAX_TIME - distance)
                        io.github.rwx.net.timeline.TimelineProtocol.MAX_TIME else time + distance
                playback.seek(pane, target, now)
            }
            KeyEvent.VK_TAB -> {
                val ids = playback.paneIds()
                source.activePane = ids[(ids.indexOf(pane) + 1) % ids.size]
            }
            KeyEvent.VK_N -> {
                if (!control) return false
                if (playback.paneIds().size < 4) source.activePane = playback.addPane(playback.position(pane, now).time)
            }
            KeyEvent.VK_W -> {
                if (!control) return false
                if (pane != 0) { source.activePane = 0; playback.removePane(pane) }
            }
            else -> return false
        }
        return true
    }
}
