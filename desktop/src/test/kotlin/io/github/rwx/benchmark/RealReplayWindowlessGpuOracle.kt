package io.github.rwx.benchmark

import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.scene.OnRenderScene
import de.fabmax.kool.util.FrontendScope
import io.github.rwx.*
import io.github.rwx.di.coreModule
import io.github.rwx.headless.HeadlessGameSession
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.kool.vulkan.VulkanFrameLifecycle
import io.github.rwx.kool.vulkan.VulkanBackendMetrics
import io.github.rwx.kool.vulkan.WindowlessKoolGpuOracle
import io.github.rwx.kool.vulkan.WindowlessFrameDiagnostics
import io.github.rwx.render.canvas.*
import io.github.rwx.session.GameCameraSnapshot
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.model.SettingsModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * Opt-in TEST main: real replay -> production recorder/freeze/host/offscreen passes -> real Vulkan.
 * --profile-only=on --replay=name.replay --output=NEW_DIRECTORY --mode=static|pan|zoom|pan-zoom
 * --warmup-frames=30 --frames=60 --seek-tick=6000 --replay-fog=on|off
 *
 * No GLFW/AWT/native window or input. Fixed real 60 Hz limiter, ordinary 1x simulation steps.
 * RWX_REAL_REPLAY_CELL_REFERENCE=1 adds correctness-only, sequential final-cell readbacks against
 * independent atlas renderers. Otherwise no readback/pixel claim. Never a screen FPS/input benchmark.
 * The caller MUST enforce an external process deadline: native fence waits cannot be interrupted.
 */
object RealReplayWindowlessGpuOracle {
    private data class Options(val replay: String, val output: File, val mode: String,
        val warmup: Int, val frames: Int, val seek: Int, val fog: Boolean)

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val code = try { run(parse(args)); 0 } catch (error: Throwable) {
            error.printStackTrace(System.err); 1
        }
        exitProcess(code) // Legacy replay/pathfinding workers are process scoped.
    }

    private fun parse(args: Array<String>): Options {
        val values = linkedMapOf<String, String>()
        args.forEach { arg ->
            require(arg.startsWith("--") && '=' in arg) { "Expected --name=value: $arg" }
            val name = arg.substring(2).substringBefore('=')
            require(name in setOf("profile-only", "replay", "output", "mode", "warmup-frames", "frames", "seek-tick", "replay-fog")) { "Unknown option: $name" }
            require(values.put(name, arg.substringAfter('=')) == null) { "Duplicate option: $name" }
        }
        require(values["profile-only"] == "on") { "--profile-only=on is required; this is never a screen/FPS benchmark" }
        fun number(name: String, default: Int, range: IntRange) =
            (values[name]?.toIntOrNull() ?: if (name !in values) default else -1).also { require(it in range) { "$name must be in $range" } }
        val replay = requireNotNull(values["replay"]) { "--replay is required" }
        require(replay.isNotBlank() && File(replay).name == replay && '/' !in replay && '\\' !in replay && replay !in setOf(".", ".."))
        val mode = values["mode"] ?: "static"
        require(mode in setOf("static", "pan", "zoom", "pan-zoom"))
        val fog = values["replay-fog"] ?: "on"
        require(fog in setOf("on", "off"))
        return Options(replay, File(requireNotNull(values["output"]) { "--output is required" }).absoluteFile, mode,
            number("warmup-frames", 30, 0..36000), number("frames", 60, 1..36000), number("seek-tick", 6000, 0..36000), fog == "on")
    }

    private fun run(options: Options) {
        require(System.getenv("RWX_RUN_REAL_REPLAY_WINDOWLESS_GPU") == "1") { "Set RWX_RUN_REAL_REPLAY_WINDOWLESS_GPU=1" }
        require(System.getenv("RWX_GPU_MAP_CELL_TARGETS") == "1") { "Set RWX_GPU_MAP_CELL_TARGETS=1; CPU target fallback is forbidden" }
        require(System.getenv("RWX_GPU_MAP_CELL_ORACLE_MSAA") in setOf(null, "4")) { "This real-scene diagnostic uses production MSAA=4" }
        val launch = File(requireNotNull(System.getProperty("launch.dir")) { "An isolated -Dlaunch.dir is required" }).canonicalFile
        require(File(System.getProperty("user.home")).canonicalFile.toPath().startsWith(launch.toPath())) { "user.home must be inside isolated launch.dir" }
        val assets = File(requireNotNull(System.getProperty("rwx.assetsDir")) { "Explicit complete real assets are required" }).canonicalFile
        require(assets.isDirectory && DesktopPlatformStorage.resolveAssetRoot().canonicalFile == assets)
        options.output.parentFile.mkdirs()
        Files.createDirectory(options.output.toPath())
        try { capture(options, assets) } catch (error: Throwable) {
            options.output.resolve("failure.txt").writeText(error.stackTraceToString()); throw error
        }
    }

    private fun capture(options: Options, assets: File) = runBlocking {
        configureDesktopLogging()
        HeadlessMain::class.java.getDeclaredMethod("configureHeadlessPlatform").apply { isAccessible = true }.invoke(HeadlessMain)
        val bridge = DesktopPlatformBridge(audio = NoopPlatformAudio)
        val storage = bridge.storage
        require(storage.modsDir.file.walkTopDown().none { it.isFile }) { "Isolated launch.dir must not contain mods" }
        val replay = storage.replaysDir.file.resolve(options.replay)
        require(replay.isFile && replay.canonicalFile.parentFile == storage.replaysDir.file.canonicalFile)
        val tankBytes = checkNotNull(storage.readAssetBytes("units/tanks/tank.png"))
        val tank = checkNotNull(javax.imageio.ImageIO.read(tankBytes.inputStream()))
        check((0 until tank.height).any { y -> (0 until tank.width).any { x -> tank.getRGB(x, y) ushr 24 != 0 } }) { "Transparent server placeholder assets are forbidden" }
        val fontPath = UiTheme.Fonts.CJK_MSDF_FONT_PATH
        val fontMeta = checkNotNull(storage.readAssetBytes("$fontPath.json"))
        val fontAtlas = checkNotNull(storage.readAssetBytes("$fontPath.png"))
        val store = KoolCanvasCpuTextureStore()
        val requested = linkedSetOf<String>()
        val resolved = linkedSetOf<String>()
        val graphics = KoolGraphicsEngine(store, { path ->
            requested += path; storage.readAssetBytes(path).also { if (it != null) resolved += path }
        })
        val bindings = module {
            single<PlatformBridge> { bridge }; single<PlatformStorage> { storage }
            single<PreferenceStorage> { bridge.preferenceStorage }; single<AppMetadata> { bridge.appMetadata }
            single<AppLogger> { bridge.logger }; single<CrashReporter> { bridge.crashReporter }
            single<GraphicsEngine> { graphics }
        }
        GlobalContext.startKoin { modules(coreModule, bindings) }
        var game: GameEngine? = null
        val rows = ArrayList<JsonObject>(options.warmup + options.frames + 120)
        val cellReferenceEnabled = System.getenv("RWX_REAL_REPLAY_CELL_REFERENCE") == "1"
        var cellReferenceStarted = false
        var cellReferenceReport: JsonObject? = null
        var failure: Throwable? = null
        var generated = 0L; var accepted = 0L; var fenceCompleted = 0L
        var preAdvanceFrames = 0; var captureStartTick = -1; var ended = false
        var fontUploaded = false; var finalTargets = ""; var totalTargetResources = 0L
        var installedVersions = 0L; var renderedTargetFrames = 0L
        val nativeOrdinal = VulkanBackendMetrics.javaClass.getDeclaredField("frameOrdinal").apply { isAccessible = true }
        var sceneSubmissionStart = 0L
        var finalSubmissions = 0L
        val generatedAt = LongArray(options.warmup + options.frames + 1)
        val frameDiagnostics = if (System.getenv("RWX_WINDOWLESS_FRAME_DIAGNOSTICS") == "1")
            WindowlessFrameDiagnostics(options.warmup + options.frames + 2400, { state ->
                state.generated = generated
                state.adopted = accepted
                state.tick = game?.currentTick ?: -1
                state.phase = when {
                    captureStartTick < 0 -> 0
                    generated < options.warmup -> 1
                    generated < options.warmup + options.frames -> 2
                    else -> 3
                }
            }, { nativeOrdinal.getLong(VulkanBackendMetrics) }) else null
        var frameDiagnosticsWriteAttempted = false
        val started = System.nanoTime()
        try {
            storage.createDirectories()
            SettingsEngine.getInstance().save()
            val repository = GlobalContext.get().get<GameSettingsRepository>()
            SettingsModel().also { repository.loadInto(it); repository.saveFrom(it) }
            LocaleSettings.initialize()
            val context = WindowlessKoolGpuOracle.createWindowlessContext(Vec2i(1280, 720), assets.path, frameDiagnostics)
            val frameRenderer = KoolCanvasFrameRenderer()
            val host = KoolCanvasSceneHost(frameRenderer)
            host.setGpuOffscreenPassesAvailable(true)
            host.setGpuRetirementSink(VulkanFrameLifecycle::retainUntilFrameComplete)
            host.setGpuRetirementPendingCounter(VulkanFrameLifecycle::pendingRetirements)
            val current = host.javaClass.getDeclaredField("currentEnvelope").apply { isAccessible = true }
            val preparing = host.javaClass.getDeclaredField("preparingEnvelope").apply { isAccessible = true }
            val targetOwner = host.javaClass.getDeclaredField("gpuTargetPasses").apply { isAccessible = true }.get(host)
            val atlasPixelPool = targetOwner.javaClass.getDeclaredField("sharedAtlasPixels").apply { isAccessible = true }.get(targetOwner)
            if (cellReferenceEnabled) check(targetOwner.javaClass.getDeclaredField("directTargetTextures")
                .apply { isAccessible = true }.getBoolean(targetOwner) xor (atlasPixelPool != null)) {
                "Cell reference requires exactly one actual direct/pixel-sharing candidate"
            }
            val targetInstalls = targetOwner.javaClass.getDeclaredField("installedVersions").apply { isAccessible = true }
            val targetRenders = targetOwner.javaClass.getDeclaredField("renderedFrames").apply { isAccessible = true }
            val maxRunNanos = 120_000_000_000L + (options.warmup + options.frames) * 1_000_000_000L
            context.onRender += {
                check(System.nanoTime() - started < maxRunNanos) { "Real replay windowless GPU diagnostic deadline exceeded" }
            }
            FrontendScope.launch {
                try {
                    UiTheme.Fonts.install() // Exact production loader; atlas bytes are decoded, then really uploaded on use.
                    // These atlases are externally owned, not owned by CPU frame font-version leases.
                    val ownedFontMaps = Collections.newSetFromMap(IdentityHashMap<Texture2d, Boolean>()).apply {
                        add(UiTheme.Fonts.base.data.map); add(UiTheme.Fonts.titleBase.data.map)
                    }
                    // Also covers a boot/pre-advance failure before this harness creates its scene.
                    context.onShutdown += { ownedFontMaps.forEach { it.release() } }
                    val session = HeadlessGameSession()
                    val engine = session.boot(graphics).also { game = it }
                    session.openReplay(options.replay)
                    check(engine.replayEngine.j() && engine.hasLoadedLevel)
                    check(engine.gameSpeed == 1f && engine.replayEngine.v == 1)
                    engine.replayEngine.setFogDisplayEnabled(options.fog)
                    val preAdvanceStart = System.nanoTime()
                    while (engine.currentTick < options.seek) {
                        check(engine.replayEngine.j() && !engine.replayEngine.isPlaybackEnded)
                        check(System.nanoTime() - preAdvanceStart < 60_000_000_000L) { "Replay pre-advance timed out" }
                        engine.gameLoop(1f, 17); preAdvanceFrames++
                    }
                    captureStartTick = engine.currentTick
                    GameEngine.isHeadlessMode = false // Original draw early-return only; AWT remains headless.
                    val initialZoom = engine.targetZoom
                    val scene = host.createScene()
                    val releaseCachedMeshes = frameRenderer.javaClass.methods.single { it.name.startsWith("releaseCachedMeshes$") }
                    // Host's earlier hook retires leases / offscreen slots first. Kool invokes these
                    // callbacks before releasing scene children, then drains delayed native releases.
                    scene.onRelease {
                        val backend = context.backend as RenderBackendVk
                        // Atlas retirement itself schedules Texture.releaseDelayed(1). Flush its
                        // outer fence now, before shutdown's five delayed-release pump iterations.
                        backend.device.waitForIdle()
                        VulkanFrameLifecycle.deviceIdle(backend, false)
                        releaseCachedMeshes.invoke(frameRenderer, scene)
                        ownedFontMaps.forEach { it.release() }
                    }
                    sceneSubmissionStart = nativeOrdinal.getLong(VulkanBackendMetrics)
                    var previousSceneOrdinal = sceneSubmissionStart
                    var cycles = 0; var drainCycles = 0
                    context.onRender += {
                        // This is the NEXT frame: lastSubmittedFence covers the previous scene callback.
                        // Registering here, before the next snapshot, never mistakes CPU adoption for GPU completion.
                        val previousAccepted = accepted
                        // A failed acquire can skip the backend submission after scene collection.
                        // The existing ordinal advances only after successful native endFrame/submit.
                        if (previousAccepted > fenceCompleted && nativeOrdinal.getLong(VulkanBackendMetrics) > previousSceneOrdinal) VulkanFrameLifecycle.retainUntilFrameComplete {
                            fenceCompleted = maxOf(fenceCompleted, previousAccepted)
                        }
                        if (generated < options.warmup + options.frames) {
                            check(engine.replayEngine.j() && !engine.replayEngine.isPlaybackEnded)
                            applyCamera(engine, options.mode, generated.toInt(), initialZoom)
                            graphics.beginFrame(engine.screenWidth.toInt(), engine.screenHeight.toInt())
                            engine.gameLoop(1f, 17) // Owns drawing. Never call draw() a second time.
                            if (TileMap.layerBufferManager.hasVisiblePendingRedraws()) TileMap.layerBufferManager.renderVisiblePendingRedrawsNow()
                            val units = BaseUnit.bE.a()
                            var selected = 0; var visible = 0
                            for (i in 0 until BaseUnit.bE.b) { if (units[i].isSelected) selected++; if (units[i].shouldDraw) visible++ }
                            val frame = graphics.snapshot().copy(visualStats = KoolCanvasVisualStats(selected, visible, engine.settingsEngine.adaptiveBattleVisuals))
                            val camera = GameCameraSnapshot(generated + 1, 1, frame.viewport, engine.viewpointX, engine.viewpointY, engine.zoom, 1)
                            val envelope = store.freezeFrame(frame, ++generated, 1, engine.currentTick, 1, camera)
                            var transferred = false
                            try {
                                val resources = envelope.resourceLease.javaClass.methods.single { it.name.startsWith("resources$") }.invoke(envelope.resourceLease) as Map<*, *>
                                check(resources.values.none { it?.javaClass?.simpleName == "Missing" }) { "Frozen frame has missing resources" }
                                val targetCount = resources.values.count { it?.javaClass?.simpleName == "GpuTarget" }
                                check(targetCount > 0) { "Frozen scene has no real GPU targets" }
                                totalTargetResources += targetCount
                                generatedAt[generated.toInt()] = System.nanoTime()
                                host.submit(envelope) // Transfers the lease; never close a successful submission here.
                                transferred = true
                            } finally { if (!transferred) envelope.close() }
                            TileMap.layerBufferManager.renderOffscreenPendingRedraws(2)
                        } else drainCycles++
                        cycles++
                        check(drainCycles <= if (cellReferenceEnabled) 1200 else 120) { "Final host/GPU adoption or cell reference exceeded its drain callback limit" }
                        if (drainCycles >= 4 && accepted == generated && fenceCompleted == generated) {
                            check(UiTheme.Fonts.base.data.map.gpuTexture != null) { "Real font atlas was never uploaded" }
                            fontUploaded = true; finalTargets = checkNotNull(host.gpuTargetProfile())
                            installedVersions = targetInstalls.getLong(targetOwner)
                            renderedTargetFrames = targetRenders.getLong(targetOwner)
                            check(installedVersions > 0 && renderedTargetFrames > 0)
                            if (cellReferenceEnabled && !cellReferenceStarted) {
                                val finalEnvelope = checkNotNull(current.get(host) as FrameEnvelope?)
                                check(finalEnvelope.sequence == generated)
                                val retained = finalEnvelope.retain()
                                cellReferenceStarted = true
                                FrontendScope.launch {
                                    try { cellReferenceReport = RealReplayCellReference.verify(context, host, retained, options.output) }
                                    catch (error: Throwable) { failure = error; error.printStackTrace(); context.close() }
                                    finally { retained.close() }
                                }
                            }
                            if (!cellReferenceEnabled || cellReferenceReport != null) { ended = true; context.close() }
                        }
                    }
                    scene.onRenderScene += OnRenderScene {
                        val front = current.get(host) as FrameEnvelope?
                        val cold = preparing.get(host) as FrameEnvelope?
                        accepted = front?.sequence ?: 0
                        previousSceneOrdinal = nativeOrdinal.getLong(VulkanBackendMetrics)
                        val keepDetailedRow = frameDiagnostics == null || cycles == 1 || cycles % 60 == 0 ||
                            (generated == options.warmup + options.frames.toLong() && drainCycles == 0) || ended
                        if (keepDetailedRow) {
                            val uploads = VulkanFrameLifecycle.stateFor(context.backend as RenderBackendVk)
                            rows += buildJsonObject {
                                put("cycle", cycles); put("warmup", generated <= options.warmup); put("draining", drainCycles > 0)
                                put("generatedSequence", generated); put("adoptedSequence", accepted); put("preparingSequence", cold?.sequence)
                                put("sequenceLag", generated - accepted); put("fenceCompletedSequence", fenceCompleted)
                                put("adoptedTick", front?.simulationTick); put("adoptedFrameAgeNanos", if (accepted > 0) System.nanoTime() - generatedAt[accepted.toInt()] else null)
                                put("adoptedCameraX", front?.camera?.x); put("adoptedCameraY", front?.camera?.y); put("adoptedZoom", front?.camera?.zoom)
                                put("gpuTargets", host.gpuTargetProfile()); put("pendingFenceRetirements", VulkanFrameLifecycle.pendingRetirements())
                                put("gpuTargetInstalls", targetInstalls.getLong(targetOwner)); put("gpuTargetRenderCallbacks", targetRenders.getLong(targetOwner))
                                put("nativeSubmissionOrdinalBeforeScene", previousSceneOrdinal)
                                put("previousSubmissionBufferUploadBytes", uploads.bufferUploadBytes); put("previousSubmissionTextureUploadBytes", uploads.textureUploadBytes)
                                put("stagingResidentBytes", uploads.residentStagingBytes); put("stagingAllocations", uploads.allocationCount)
                                put("scene", sceneState(engine))
                            }
                        }
                    }
                    context.scenes += scene
                } catch (error: Throwable) { failure = error; error.printStackTrace(); context.close() }
            }
            context.run() // Standard adapter shutdown releases scenes and really waits for device idle.
            finalSubmissions = nativeOrdinal.getLong(VulkanBackendMetrics) - sceneSubmissionStart
            failure?.let { throw it }
            check(ended && fenceCompleted == generated && fontUploaded)
            check(!cellReferenceEnabled || cellReferenceReport?.get("passed")?.jsonPrimitive?.boolean == true)
            check(VulkanFrameLifecycle.pendingRetirements() == 0) { "GPU fence retirements remained after shutdown" }
            if (atlasPixelPool != null) {
                check(targetOwner.javaClass.getDeclaredField("closed").apply { isAccessible = true }.getBoolean(targetOwner))
                check(atlasPixelPool.javaClass.getDeclaredField("closed").apply { isAccessible = true }.getBoolean(atlasPixelPool))
                check(atlasPixelPool.javaClass.getDeclaredField("pageScratch").apply { isAccessible = true }.get(atlasPixelPool) == null) {
                    "Atlas scratch remained after owner shutdown"
                }
            }
            val unresolved = requested.filter { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") }
                .map { it.substringBeforeLast('.') }.toSet() - resolved.map { it.substringBeforeLast('.') }.toSet()
            check(unresolved.isEmpty()) { "Unresolved real image assets: $unresolved" }
            frameDiagnostics?.let {
                frameDiagnosticsWriteAttempted = true
                it.write(options.output.resolve("timings.ndjson"))
            }
            options.output.resolve("frames.ndjson").bufferedWriter().use { writer -> rows.forEach { writer.appendLine(it.toString()) } }
            options.output.resolve("result.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "real-replay-production-windowless-gpu-diagnostic"); put("completed", true); put("profileOnly", !cellReferenceEnabled)
                put("correctnessOnly", cellReferenceEnabled); put("performanceInvalid", cellReferenceEnabled)
                put("screenFpsMeasurement", false); put("performanceClaim", "none")
                put("pixelCorrectnessVerified", cellReferenceReport?.get("passed")?.jsonPrimitive?.boolean == true)
                cellReferenceReport?.let { put("cellReference", it) }
                put("surface", "VK_EXT_headless_surface"); put("windowAdapter", "memory-only"); put("frameLimitHz", 60)
                put("replay", replay.canonicalPath); put("replaySha256", sha256(replay.readBytes())); put("assetRoot", assets.path)
                put("tankSha256", sha256(tankBytes)); put("fontMetadataSha256", sha256(fontMeta)); put("fontAtlasSha256", sha256(fontAtlas))
                put("fontGpuAtlasUploaded", fontUploaded); put("mode", options.mode); put("warmupFrames", options.warmup); put("frames", options.frames)
                put("requestedReplayFog", options.fog); put("fontGlyphs", UiTheme.Fonts.base.data.glyphMap.size)
                put("cameraTape", "frame index/60; 20-second diagonal pan, 4-second constrained 0.35-1.5 zoom; same tape as CPU capture")
                put("requestedSeekTick", options.seek); put("preAdvanceFrames", preAdvanceFrames); put("captureStartTick", captureStartTick)
                put("lastTick", game?.currentTick); put("generatedSequence", generated); put("adoptedSequence", accepted); put("fenceCompletedSequence", fenceCompleted)
                put("gpuTargets", finalTargets); put("gpuTargetInstalls", installedVersions); put("gpuTargetRenderCallbacks", renderedTargetFrames)
                if (atlasPixelPool != null) {
                    put("sharedAtlasPixels", true)
                    put("atlasPixelPool_closedAndScratchReleased", true)
                    for (name in listOf("internRequests", "reuseHits", "uploadRequests", "uploadReuseHits",
                        "pageBufferAllocations", "scratchReuseHits", "scratchRecycleHits", "scratchResetBytes",
                        "pageCopyBytes", "blankPageRequests", "pageCopyRequests")) put("atlasPixelPool_$name",
                        atlasPixelPool.javaClass.getDeclaredField(name).apply { isAccessible = true }.getLong(atlasPixelPool))
                }
                put("nativeSubmissionsAfterSceneInstalled", finalSubmissions)
                put("frameDiagnosticsEnabled", frameDiagnostics != null)
                frameDiagnostics?.let { put("frameDiagnostics", it.summary()) }
                put("detailedSceneRowStride", if (frameDiagnostics == null) 1 else 60)
                put("producerArchitecture", "Test-only synchronous gameLoop/draw/freeze on the render loop; no production independent EngineOwnerLoop; fixed1x steps and frame-index camera tape")
                put("recordedGpuTargetResources", totalTargetResources); put("pendingRetirementsAfterDeviceIdle", VulkanFrameLifecycle.pendingRetirements())
                put("readback", cellReferenceEnabled); put("fixtureUnitsInjected", false); put("simulationStep", "ordinary gameLoop(1f,17), 1x; one draw per step")
                put("limitations", "Fixed 60Hz true-windowless diagnostic only. Host adoption is CPU state; fenceCompletedSequence covers prior real Vulkan submissions. " +
                    (if (cellReferenceEnabled) "Correctness readback compares only the final snapshot's map-cell attachments against independent atlas renderers; performance data invalid, no final-screen validation. " else "No readback/pixel validation. ") +
                    "No screen presentation/FPS, input latency, or official-build equivalence. Diagnostic instrumentation perturbs timing. " +
                    (if (frameDiagnostics == null) "All detailed scene rows are retained, including cold-target preparation and drain callbacks."
                    else "Bounded primitive timings retain every completed renderFrame; detailed scene JSON is sampled every60cycles plus first/final generation and final drain. This synchronous test producer differs from the production independent engine owner and cannot attribute an earlier visible-run stall."))
            }))
            println("Real replay windowless GPU diagnostic complete: ${options.output}; profileOnly=${!cellReferenceEnabled} correctnessOnly=$cellReferenceEnabled screenFpsMeasurement=false")
        } finally {
            try {
                if (!frameDiagnosticsWriteAttempted) frameDiagnostics?.write(options.output.resolve("timings.ndjson"))
            }
            finally {
                game?.replayEngine?.e(); GameEngine.isHeadlessMode = true
                store.close(); KoolCanvasFontRegistry.clearInstalledFonts(); GlobalContext.stopKoin()
            }
        }
    }

    private fun sceneState(game: GameEngine) = buildJsonObject {
        val units = BaseUnit.bE.a(); var visible = 0; var moving = 0
        for (i in 0 until BaseUnit.bE.b) { if (units[i].shouldDraw) visible++; if (units[i].velocityX != 0f || units[i].velocityY != 0f) moving++ }
        put("tick", game.currentTick); put("gameTimeMillis", game.gameTimeMillis)
        put("cameraX", game.viewpointX); put("cameraY", game.viewpointY); put("targetZoom", game.targetZoom); put("actualZoom", game.zoom)
        put("densityZoomScale", game.densityZoomScale); put("visibleWorldWidth", game.visibleWorldWidth); put("visibleWorldHeight", game.visibleWorldHeight)
        put("units", BaseUnit.bE.b); put("visibleUnits", visible); put("movingUnits", moving)
        put("roomFogMode", game.networkEngine.roomSettings.fogMode); put("fogDisplayEnabled", game.replayEngine.isFogDisplayEnabled)
        put("fogDisplayTeamId", game.replayEngine.fogDisplayTeamId); put("mapFogEnabled", game.tileMap.fogEnabled)
        put("mapFogPeriodicMaintenanceEnabled", game.tileMap.fogPeriodicMaintenanceEnabled)
    }

    private fun applyCamera(game: GameEngine, mode: String, index: Int, initialZoom: Float) {
        val seconds = index.toDouble() / 60
        game.targetZoom = if (mode == "zoom" || mode == "pan-zoom") ReplayPanBenchmark.constrainedTargetZoom(
            ReplayPanBenchmark.cycleZoom((seconds % 4) / 4, .35f, 1.5f), game.currentScreenWidthPixels,
            game.currentScreenHeightPixels, game.tileMap.worldWidth, game.tileMap.worldHeight, game.densityZoomScale) else initialZoom
        if (mode == "pan" || mode == "pan-zoom") {
            val displacement = sin(seconds * 2 * PI / 20).toFloat()
            val spanX = ((game.tileMap.worldWidth - game.viewpointWidth) / 2f).coerceIn(0f, 600f)
            val spanY = ((game.tileMap.worldHeight - game.visibleWorldHeight) / 2f).coerceIn(0f, 600f)
            game.setViewpoint((game.tileMap.worldWidth - game.viewpointWidth) / 2f + spanX * displacement,
                (game.tileMap.worldHeight - game.visibleWorldHeight) / 2f + spanY * displacement)
            game.clampCameraPosition()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
