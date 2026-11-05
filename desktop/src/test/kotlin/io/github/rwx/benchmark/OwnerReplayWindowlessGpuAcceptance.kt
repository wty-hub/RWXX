package io.github.rwx.benchmark

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Mat4f
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OnRenderScene
import de.fabmax.kool.util.FrontendScope
import io.github.rwx.*
import io.github.rwx.di.coreModule
import io.github.rwx.headless.HeadlessGraphicsEngine
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.kool.KoolDesktopGameSession
import io.github.rwx.kool.vulkan.VulkanBackendMetrics
import io.github.rwx.kool.vulkan.VulkanFrameLifecycle
import io.github.rwx.kool.vulkan.WindowlessKoolGpuOracle
import io.github.rwx.kool.vulkan.WindowlessFrameObserver
import io.github.rwx.render.canvas.*
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.system.exitProcess

/**
 * TEST main: real automatic desktop owner -> immutable mailbox -> real windowless Vulkan host.
 * Only --output=NEW_DIRECTORY --replay=bridges-fixed.replay. Root supplies isolated launch/home/
 * logs/tmp/assets, bounded process execution and the ordinary ReplayPanBenchmark warm2/sample20
 * wall-clock pan20/zoom4 environment. No gameLoop/engineFrame stepping, dummy GPU installer, display
 * acknowledgment, GLFW window or input. The memory-only adapter consumes at60Hz; owner requests120.
 * Correctness only. Default strictshader mode keeps the complete shader-OFF/ON canvas gates.
 * Explicit RWX_OWNER_GPU_REFERENCE_MODE=prepared-cells covers only actual frozen map-cell attachments.
 * Neither mode covers outer App UI/display scanout.
 */
object OwnerReplayWindowlessGpuAcceptance {
    private const val REPLAY_SHA = "d19b132ec8866f87496b3c1c91627e7ef35a99b1627e2a9550f74c0e0e57f6d1"
    private const val MAX_CYCLES = 10_000
    private data class Options(val output: File, val replay: String, val referenceMode: String) {
        val preparedCells: Boolean get() = referenceMode == "prepared-cells"
    }

    @JvmStatic fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val deadline = System.nanoTime() + 90_000_000_000L
        Thread({
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) { System.err.println("Owner GPU acceptance deadline; invalid halt124"); Runtime.getRuntime().halt(124) }
                LockSupport.parkNanos(minOf(remaining, 100_000_000L))
            }
        }, "owner-windowless-gpu-deadline").apply { isDaemon = true; start() }
        val code = try { run(parse(args), deadline); 0 } catch (error: Throwable) { error.printStackTrace(); 1 }
        exitProcess(code) // Keep the daemon deadline alive through shutdown hooks too.
    }

    private fun parse(args: Array<String>): Options {
        val values = linkedMapOf<String, String>()
        args.forEach { arg ->
            require(arg.startsWith("--") && '=' in arg)
            val key = arg.substring(2).substringBefore('=')
            require(key in setOf("output", "replay") && values.put(key, arg.substringAfter('=')) == null)
        }
        val replay = requireNotNull(values["replay"])
        require(replay.isNotBlank() && File(replay).name == replay && '/' !in replay && '\\' !in replay && replay !in setOf(".", ".."))
        val mode = System.getenv("RWX_OWNER_GPU_REFERENCE_MODE") ?: "strictshader"
        require(mode in setOf("strictshader", "prepared-cells")) { "Unsupported owner GPU reference mode: $mode" }
        return Options(File(requireNotNull(values["output"])).canonicalFile, replay, mode)
    }

    private fun run(options: Options, deadline: Long) {
        require(System.getenv("RWX_RUN_OWNER_WINDOWLESS_GPU_ACCEPTANCE") == "1")
        require(System.getenv("RWX_RUN_WINDOWLESS_VULKAN_ORACLE") == "1")
        require(System.getenv("RWX_GPU_MAP_CELL_TARGETS") == "1" && System.getenv("RWX_GPU_MAP_CELL_ORACLE_MSAA") == "4")
        if (options.preparedCells) {
            require(System.getenv("RWX_MAP_NODE_MODEL_UPDATES_ONLY") == "1")
            for (flag in listOf("RWX_REUSE_INSTANCED_TEXTURE_SHADERS", "RWX_REUSE_MAP_MESH_SLOTS",
                "RWX_SHARE_MAP_TEXTURE_MATERIALS", "RWX_RESET_ONLY_ACTIVE_CANVAS_MESHES")) {
                require(System.getenv(flag) != "1") { "Prepared-cell node-update acceptance requires $flag OFF" }
            }
        } else {
            require(System.getenv("RWX_REUSE_INSTANCED_TEXTURE_SHADERS") == "1")
        }
        require(System.getenv("RWX_REAL_SCENE_CELL_ORACLE") == "1")
        require(File(checkNotNull(System.getenv("RWX_REAL_SCENE_ORACLE"))).canonicalFile == options.output)
        require(System.getenv("RWX_STABLE_OWNER_DEADLINES") == "1" && System.getenv("RWX_STABLE_FRAME_DEADLINES") == null)
        require(System.getenv("RWX_DESKTOP_TARGET_FPS") == "120")
        require(System.getenv("RWX_POST_INPUT_FRAME_WAIT") != "1" && System.getenv("RWX_NORMAL_INPUT_PROBE") != "1")
        require(System.getenv("RWX_DIRECT_TARGET_TEXTURES") != "1" && System.getenv("RWX_REUSE_MAP_ATLAS_SCRATCH") != "1")
        val pan = requireNotNull(ReplayPanBenchmark.options(System.getenv()))
        require(pan.requireReplay && pan.warmupSeconds == 2 && pan.sampleSeconds == 20 && pan.repetitions == 1)
        require(pan.cameraMode == "pan" && pan.zoomMode == "cycle" && pan.cameraPeriodSeconds == 20 && pan.zoomPeriodSeconds == 4)
        require(pan.zoomMinimum == .35f && pan.zoomMaximum == 1.5f && pan.output.canonicalFile == options.output.resolve("scenario.ndjson"))
        require(pan.cameraTrace == null && pan.exportMap == null && pan.playerDeathSeconds == null)
        require(System.getenv("RWX_REPLAY_PAN_FOG") == "off")
        val launch = File(requireNotNull(System.getProperty("launch.dir"))).canonicalFile
        require(File(System.getProperty("user.home")).canonicalFile.toPath().startsWith(launch.toPath()))
        val assets = File(requireNotNull(System.getProperty("rwx.assetsDir"))).canonicalFile
        require(assets.isDirectory && DesktopPlatformStorage.resolveAssetRoot().canonicalFile == assets)
        require(File(requireNotNull(System.getenv("RWX_FRAME_LIFECYCLE_TRACE"))).canonicalFile == options.output.resolve("producer-lifecycle.csv"))
        options.output.parentFile.mkdirs(); Files.createDirectory(options.output.toPath())
        try { capture(options, assets, deadline) } catch (error: Throwable) {
            options.output.resolve("failure.txt").writeText(error.stackTraceToString()); throw error
        }
    }

    private fun capture(options: Options, assets: File, deadline: Long) = runBlocking {
        configureDesktopLogging()
        HeadlessMain::class.java.getDeclaredMethod("configureHeadlessPlatform").apply { isAccessible = true }.invoke(HeadlessMain)
        val bridge = DesktopPlatformBridge(audio = NoopPlatformAudio); val storage = bridge.storage
        require(storage.modsDir.file.walkTopDown().none { it.isFile })
        val replay = storage.replaysDir.file.resolve(options.replay).canonicalFile
        require(replay.isFile && replay.parentFile == storage.replaysDir.file.canonicalFile && sha(replay.readBytes()) == REPLAY_SHA)
        val tankBytes = checkNotNull(storage.readAssetBytes("units/tanks/tank.png"))
        val tank = checkNotNull(javax.imageio.ImageIO.read(tankBytes.inputStream()))
        check((0 until tank.height).any { y -> (0 until tank.width).any { x -> tank.getRGB(x, y) ushr 24 != 0 } })
        val bindings = module {
            single<PlatformBridge> { bridge }; single<PlatformStorage> { storage }
            single<PreferenceStorage> { bridge.preferenceStorage }; single<AppMetadata> { bridge.appMetadata }
            single<AppLogger> { bridge.logger }; single<CrashReporter> { bridge.crashReporter }
            single<GraphicsEngine> { HeadlessGraphicsEngine(get()) }
        }
        GlobalContext.startKoin { modules(coreModule, bindings) }
        var session: KoolDesktopGameSession? = null; var game: GameEngine? = null
        var ownerStopped = false; var sessionClosed = false; var contextReturned = false
        var finalPacket: FrameEnvelope? = null; var ownerPolicy: JsonObject? = null
        var reference: JsonObject? = null; var fullCanvasReference: JsonObject? = null
        var failure: Throwable? = null; var ended = false
        var hostSubmits = 0L; var lastSubmittedSequence = -1L; var sequenceSkips = 0L
        var adoptedSequence = 0L; var terminalSequence = -1L; var fenceCompletedSequence = 0L
        val adopted = mutableSetOf<Long>(); val scheduled = mutableSetOf<Long>(); val completed = mutableSetOf<Long>()
        val rows = ArrayList<JsonObject>(); var cycles = 0; var drainCycles = 0; var coldCycles = 0
        var maxPendingRetirements = 0; var nativeStart = 0L; var previousSceneOrdinal = 0L
        var referenceStarted = false; var fontUploaded = false
        var targetInstalls = 0L; var targetRenders = 0L; var finalTargetCount = 0
        var actualMapNodePolicy: JsonObject? = null
        val ordinal = VulkanBackendMetrics.javaClass.getDeclaredField("frameOrdinal").apply { isAccessible = true }
        val fontMaps = Collections.newSetFromMap(IdentityHashMap<Texture2d, Boolean>())
        fun timed() = check(System.nanoTime() < deadline) { "Owner windowless GPU deadline exceeded" }
        fun fail(error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        fun stopOwner() {
            val active = session ?: return
            if (!ownerStopped) {
                // Call the actual existing owner lifecycle; no field writes or synthetic engine step.
                (owner(active) as AutoCloseable).close()
                check(!ownerThread(active).isAlive) { "Actual owner survived close" }
                ownerStopped = true
            }
        }
        try {
            storage.createDirectories(); SettingsEngine.getInstance().save()
            val repository = GlobalContext.get().get<GameSettingsRepository>()
            SettingsModel().also { repository.loadInto(it); repository.saveFrom(it) }; LocaleSettings.initialize()
            val context = WindowlessKoolGpuOracle.createWindowlessContext(Vec2i(1280, 720), assets.path,
                object : WindowlessFrameObserver {
                    override fun beforeFrame() = Unit
                    override fun afterFrame(failure: Throwable?) {
                        if (failure != null) {
                            fail(failure)
                            runCatching(::stopOwner).onFailure { failure.addSuppressed(it) }
                        }
                    }
                })
            val renderer = KoolCanvasFrameRenderer(); val host = KoolCanvasSceneHost(renderer)
            host.setGpuOffscreenPassesAvailable(true)
            host.setGpuRetirementSink(VulkanFrameLifecycle::retainUntilFrameComplete)
            host.setGpuRetirementPendingCounter(VulkanFrameLifecycle::pendingRetirements)
            val current = host.javaClass.getDeclaredField("currentEnvelope").apply { isAccessible = true }
            val preparing = host.javaClass.getDeclaredField("preparingEnvelope").apply { isAccessible = true }
            val targets = field(host, "gpuTargetPasses")!!
            context.onRender += { timed() }
            FrontendScope.launch {
                try {
                    UiTheme.Fonts.install()
                    fontMaps.add(UiTheme.Fonts.base.data.map); fontMaps.add(UiTheme.Fonts.titleBase.data.map)
                    context.onShutdown += { fontMaps.forEach { it.release() } }
                    // Configuring this live scene installs the REAL pass owner before any replay loads.
                    val scene = host.createScene()
                    check(gpuInstallerAvailable()) { "Real host did not install GPU target ownership" }
                    val releaseMeshes = renderer.javaClass.methods.single { it.name.startsWith("releaseCachedMeshes$") }
                    scene.onRelease {
                        // Host's earlier release hook retires resources first; drain that real fence.
                        val backend = context.backend as RenderBackendVk
                        backend.device.waitForIdle(); VulkanFrameLifecycle.deviceIdle(backend, false)
                        releaseMeshes.invoke(renderer, scene); fontMaps.forEach { it.release() }
                    }
                    context.scenes += scene
                    val active = KoolDesktopGameSession(storage).also { session = it }
                    val engine = active.preload(KoolCanvasViewport(1280, 720)).also { game = it }
                    active.submitSessionTask {
                        check(engine.renderGraphicsEngine is KoolGraphicsEngine)
                        engine.settingsEngine.highRefreshRate = true; engine.settingsEngine.batterySaving = false
                        engine.settingsEngine.renderVsync = false; engine.settingsEngine.maxFrameRate = 120
                        engine.settingsEngine.showFps = false; GameEngine.isHeadlessMode = false
                    }.get(15, TimeUnit.SECONDS)
                    ownerPolicy = policy(active)
                    check(ownerPolicy!!["stableFrameDeadlines"]!!.jsonPrimitive.boolean && ownerPolicy!!["legacyPacing"]!!.jsonPrimitive.boolean)
                    active.prepareReplayAsync(options.replay, KoolCanvasViewport(1280, 720))
                    nativeStart = ordinal.getLong(VulkanBackendMetrics); previousSceneOrdinal = nativeStart
                    context.onRender += {
                        try {
                            timed(); check(++cycles <= MAX_CYCLES)
                            active.mapLoadError(options.replay)?.let { throw IllegalStateException("Owner replay failed", it) }
                            // The previous scene can only be fence-associated after a real submission.
                            val previous = adoptedSequence
                            if (previous > 0 && ordinal.getLong(VulkanBackendMetrics) > previousSceneOrdinal && scheduled.add(previous)) {
                                VulkanFrameLifecycle.retainUntilFrameComplete {
                                    completed.add(previous); fenceCompletedSequence = maxOf(fenceCompletedSequence, previous)
                                }
                            }
                            if (!ownerStopped && ReplayPanBenchmark.completedAtNanos > 0L) {
                                stopOwner() // Quiesce first, then consume the last completed publication.
                                active.currentFrame()
                                finalPacket = checkNotNull(active.currentFrameEnvelope()).retain()
                                terminalSequence = finalPacket!!.sequence
                            }
                            if (!ownerStopped) active.currentFrame()
                            val selected = if (ownerStopped) finalPacket else active.currentFrameEnvelope()
                            if (selected != null && selected.generation > 0 && selected.sequence != lastSubmittedSequence) {
                                val counts = resourceCounts(selected); check(counts.first > 0 && counts.second == 0)
                                val packet = selected.retain(); var transferred = false
                                try { host.submit(packet); transferred = true }
                                finally { if (!transferred) packet.close() }
                                if (lastSubmittedSequence >= 0) sequenceSkips += selected.sequence - lastSubmittedSequence - 1
                                lastSubmittedSequence = selected.sequence; hostSubmits++
                            }
                            if (ownerStopped) {
                                check(++drainCycles <= 1200) { "Final GPU/reference drain exceeded bound" }
                                if (!referenceStarted && adoptedSequence == terminalSequence && fenceCompletedSequence == terminalSequence &&
                                    preparing.get(host) == null) {
                                    val front = checkNotNull(current.get(host) as FrameEnvelope?)
                                    check(front.sequence == terminalSequence && front.resourceLease === finalPacket!!.resourceLease)
                                    check(!front.resourceLease.isReleased)
                                    finalTargetCount = resourceCounts(front).first; check(finalTargetCount > 0)
                                    fontUploaded = UiTheme.Fonts.base.data.map.gpuTexture != null; check(fontUploaded)
                                    targetInstalls = field(targets, "installedVersions") as Long
                                    targetRenders = field(targets, "renderedFrames") as Long
                                    check(targetInstalls > 0 && targetRenders > 0)
                                    if (options.preparedCells) actualMapNodePolicy = preparedCellNodePolicy(targets, renderer)
                                    write(options.output.resolve("pre-reference.json"), buildJsonObject {
                                        put("kind", "automatic-owner-gpu-pre-reference"); put("correctnessOnly", true)
                                        put("performanceInvalid", true); put("atNanos", System.nanoTime())
                                        put("mode", options.referenceMode)
                                        put("referenceMode", if (options.preparedCells) "prepared-map-source-textures-only" else "prepared-map-source-textures-and-full-canvas-shader-off-on")
                                        put("shaderReuseRequested", !options.preparedCells)
                                        if (!options.preparedCells) put("fullCanvasSamples", 4)
                                        put("fullCanvasReferenceRequested", !options.preparedCells); put("exactRgbaRequired", true)
                                        actualMapNodePolicy?.let { put("actualMapNodePolicy", it) }
                                        put("actualOwnerPolicy", policy(active))
                                        put("ownerStopped", ownerStopped); put("ownerThreadAlive", ownerThread(active).isAlive)
                                        put("finalSequence", terminalSequence); put("finalTick", front.simulationTick)
                                        put("finalGeneration", front.generation); put("finalActualGpuTargetCount", finalTargetCount)
                                        put("missingResources", resourceCounts(front).second); put("sameRetainedLease", front.resourceLease === finalPacket!!.resourceLease)
                                        put("finalLeaseReleased", front.resourceLease.isReleased); put("preparingEnvelopeAbsent", preparing.get(host) == null)
                                        put("finalCameraX", front.camera?.x); put("finalCameraY", front.camera?.y); put("finalZoom", front.camera?.zoom)
                                        put("gpuTargetProfile", host.gpuTargetProfile()); put("gpuTargetInstalls", targetInstalls)
                                        put("gpuTargetRenderCallbacks", targetRenders); put("directTargetTextures", field(targets, "directTargetTextures") as Boolean)
                                        put("realGpuInstallerAvailable", gpuInstallerAvailable()); put("realFontAtlasUploaded", fontUploaded)
                                        put("submittedToHostCount", hostSubmits); put("adoptedUniqueCount", adopted.size)
                                        put("fenceCompletedUniqueCount", completed.size); put("fenceCompletedSequence", fenceCompletedSequence)
                                        put("nativeSubmissionCount", ordinal.getLong(VulkanBackendMetrics) - nativeStart)
                                        put("pendingRetirements", VulkanFrameLifecycle.pendingRetirements())
                                    })
                                    referenceStarted = true
                                    FrontendScope.launch {
                                        try {
                                            reference = RealReplayCellReference.verifyPreparedMapCells(context, host, finalPacket!!, options.output)
                                            check(reference!!["passed"]!!.jsonPrimitive.boolean)
                                            check(reference!!["expectedCells"]!!.jsonPrimitive.int == finalTargetCount &&
                                                reference!!["comparedCells"]!!.jsonPrimitive.int == finalTargetCount)
                                            check(reference!!["maxChannelDelta"]!!.jsonPrimitive.int == 0)
                                            if (!options.preparedCells) {
                                                fullCanvasReference = RealReplayFullCanvasReference.verify(context, host, finalPacket!!, options.output)
                                                check(fullCanvasReference!!["pixelsPassed"]!!.jsonPrimitive.boolean)
                                                check(!fullCanvasReference!!["passed"]!!.jsonPrimitive.boolean) // Native log closes on context shutdown.
                                            }
                                            ended = true
                                        } catch (error: Throwable) { fail(error) }
                                        finally { context.close() }
                                    }
                                }
                            }
                        } catch (error: Throwable) { fail(error); runCatching(::stopOwner); context.close() }
                    }
                    // Added after the host callback: observe actual cold/front state, never fabricate it.
                    scene.onRenderScene += OnRenderScene {
                        val front = current.get(host) as FrameEnvelope?
                        val cold = preparing.get(host) as FrameEnvelope?
                        if (front != null) { check(!front.resourceLease.isReleased); resourceCounts(front); adoptedSequence = front.sequence; adopted.add(front.sequence) }
                        if (cold != null) { check(!cold.resourceLease.isReleased); resourceCounts(cold); coldCycles++ }
                        previousSceneOrdinal = ordinal.getLong(VulkanBackendMetrics)
                        maxPendingRetirements = maxOf(maxPendingRetirements, VulkanFrameLifecycle.pendingRetirements())
                        if (cycles == 1 || cycles % 30 == 0 || ownerStopped && drainCycles < 5) {
                            check(rows.size < MAX_CYCLES)
                            rows += buildJsonObject {
                                put("cycle", cycles); put("atNanos", System.nanoTime()); put("ownerStopped", ownerStopped)
                                put("submittedToHost", hostSubmits); put("lastSubmittedSequence", lastSubmittedSequence)
                                put("adoptedSequence", adoptedSequence); put("preparingSequence", cold?.sequence)
                                put("adoptedTick", front?.simulationTick); put("adoptedCameraX", front?.camera?.x)
                                put("adoptedCameraY", front?.camera?.y); put("adoptedZoom", front?.camera?.zoom)
                                put("fenceCompletedSequence", fenceCompletedSequence); put("nativeOrdinalBeforeScene", previousSceneOrdinal)
                                put("gpuTargets", host.gpuTargetProfile()); put("pendingRetirements", VulkanFrameLifecycle.pendingRetirements())
                            }
                        }
                    }
                } catch (error: Throwable) { fail(error); runCatching(::stopOwner); context.close() }
            }
            context.run() // Existing adapter shutdown releases scenes and waits for the real device.
            contextReturned = true
            failure?.let { throw it }
            check(ended && ownerStopped && terminalSequence > 0 && fenceCompletedSequence == terminalSequence)
            check(VulkanFrameLifecycle.pendingRetirements() == 0)
            check(!gpuInstallerAvailable())
            session!!.close(); sessionClosed = true
            finalPacket!!.close(); check(finalPacket!!.resourceLease.isReleased)
            closeLifecycle()
            val lifecycle = lifecycleCounts(options.output.resolve("producer-lifecycle.csv"), terminalSequence)
            check(adopted.size > 1 && completed.isNotEmpty() && hostSubmits > 1)
            check((field(targets, "closed") as Boolean) && field(field(targets, "sharedAtlasPixels")!!, "closed") == true)
            if (!options.preparedCells) {
                fullCanvasReference = RealReplayFullCanvasReference.finishAfterContextShutdown(options.output)
                check(fullCanvasReference!!["passed"]!!.jsonPrimitive.boolean)
            }
            options.output.resolve("frames.ndjson").bufferedWriter().use { writer -> rows.forEach { writer.appendLine(it.toString()) } }
            val scenario = options.output.resolve("scenario.ndjson").readLines().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
            val setup = scenario.single { it["kind"]?.jsonPrimitive?.content == "scenario" }
            check(setup["initialTick"]!!.jsonPrimitive.int == 0 && setup["replaySpeed"]!!.jsonPrimitive.float == 1f && setup["replayRate"]!!.jsonPrimitive.int == 1)
            write(options.output.resolve("result.json"), buildJsonObject {
                put("kind", "automatic-owner-real-windowless-gpu-acceptance"); put("passed", true); put("correctnessOnly", true)
                put("mode", options.referenceMode); put("fullCanvasReferenceRequested", !options.preparedCells)
                actualMapNodePolicy?.let { put("actualMapNodePolicy", it) }
                put("performanceInvalid", true); put("screenFpsMeasurement", false); put("physicalWindow", false)
                put("surface", "VK_EXT_headless_surface"); put("consumerFrameLimitHz", 60); put("ownerTargetHz", 120)
                put("actualOwnerPolicy", checkNotNull(ownerPolicy)); put("scenario", setup)
                put("replaySha256", REPLAY_SHA); put("lifecycle", lifecycle); put("submittedToHostCount", hostSubmits)
                put("adoptedUniqueCount", adopted.size); put("fenceCompletedUniqueCount", completed.size)
                put("nativeSubmissionCount", ordinal.getLong(VulkanBackendMetrics) - nativeStart)
                put("ownerFinalPublishedSequence", terminalSequence); put("fenceCompletedSequence", fenceCompletedSequence)
                put("forwardedSequenceSkips", sequenceSkips); put("coldPreparationCycles", coldCycles)
                put("gpuTargetInstalls", targetInstalls); put("gpuTargetRenderCallbacks", targetRenders)
                put("finalActualGpuTargetCount", finalTargetCount); put("cellReference", checkNotNull(reference))
                if (!options.preparedCells) put("fullCanvasReference", checkNotNull(fullCanvasReference))
                put("realFontAtlasUploaded", fontUploaded); put("ownerStopped", ownerStopped); put("sessionClosed", sessionClosed)
                put("finalCpuLeaseReleased", finalPacket!!.resourceLease.isReleased)
                put("maxPendingFenceRetirements", maxPendingRetirements); put("pendingRetirementsAfterShutdown", 0)
                put("pixelScope", if (options.preparedCells)
                    "All actual final frozen map-cell attachments exact against independent source-texture references with normal Node.update; no full-canvas, outer App UI or scanout correctness claim"
                else "All actual final frozen map-cell attachments exact against independent source-texture references, plus complete frozen 1280x720 game canvas shader-OFF/ON MSAA4 twice with exact RGBA and real cross-mesh/native-bind proof; not outer App UI/scanout")
                put("limits", "Real independent owner with latest-frame coalescing and real60Hz headless Vulkan consumer. Selection/adoption/submission/fence counts have different meanings. No hand stepping, dummy installer or manual presentation acknowledgment; no visibleFPS/input/OS-focus equivalence claim.")
            })
        } finally {
            runCatching(::stopOwner)
            if (ownerStopped || session == null) {
                if (!sessionClosed) runCatching { session?.close(); sessionClosed = true }
                finalPacket?.close()
                if (contextReturned) runCatching(::closeLifecycle)
                game?.replayEngine?.e(); GameEngine.isHeadlessMode = true
                GlobalContext.stopKoin()
            }
        }
    }

    private fun preparedCellNodePolicy(targets: Any, rootRenderer: KoolCanvasFrameRenderer): JsonObject {
        check(field(targets, "mapNodeModelUpdatesOnly") == true)
        check(field(targets, "sharedTextureMaterials") == null)
        fun checkRenderer(renderer: Any) {
            for (name in listOf("reuseInstancedTextureShaders", "reuseMapMeshSlots", "resetOnlyActiveMeshes")) {
                check(field(renderer, name) == false) { "Prepared-cell acceptance requires actual $name=false" }
            }
        }
        checkRenderer(rootRenderer)
        val slots = (field(targets, "installed") as Map<*, *>).values.filterNotNull()
        check(slots.isNotEmpty())
        var meshes = 0; var nonInstanced = 0
        for (slot in slots) {
            check(!(field(slot, "pass") as OffscreenPass2d).isUpdateDrawNode)
            checkRenderer(checkNotNull(field(slot, "renderer")))
            val node = field(slot, "node") as Node
            check(node.bounds.isEmpty && node.modelMatF == Mat4f.IDENTITY && node.modelMatrixData.modCount.count > 0)
            for (child in node.children) {
                check(child is Mesh<*> && child.bounds.isEmpty && child.modelMatF == Mat4f.IDENTITY &&
                    child.modelMatrixData.modCount.count > 0) { "Private cell mesh lacks initialized identity model / empty bounds" }
                meshes++; if (child.instances == null) nonInstanced++
            }
        }
        check(meshes > 0)
        return buildJsonObject {
            put("mapNodeModelUpdatesOnly", true); put("installedPassesVerified", slots.size)
            put("initializedIdentityMeshes", meshes); put("nonInstancedMeshes", nonInstanced)
            put("instancedMeshes", meshes - nonInstanced); put("boundsRemainEmpty", true)
            put("shaderReuse", false); put("mapMeshReuse", false); put("sharedTextureMaterials", false); put("activeMeshReset", false)
            put("referenceDrawNodeUpdates", "Independent RealReplayCellReference pass keeps normal default true")
        }
    }

    private fun owner(session: KoolDesktopGameSession): Any = checkNotNull((session.javaClass
        .getDeclaredField("owner\$delegate").apply { isAccessible = true }.get(session) as Lazy<*>).value)
    private fun ownerThread(session: KoolDesktopGameSession) = field(owner(session), "thread") as Thread
    private fun policy(session: KoolDesktopGameSession) = buildJsonObject {
        val value = owner(session)
        put("stableFrameDeadlines", field(value, "stableFrameDeadlines") as Boolean)
        put("legacyPacing", field(value, "legacyPacing") as Boolean)
        put("threadId", ownerThread(session).threadId()); put("threadName", ownerThread(session).name)
    }
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    private fun gpuInstallerAvailable(): Boolean {
        val type = Class.forName("io.github.rwx.render.canvas.FrozenCanvasGpuResources")
        return type.methods.single { it.name.startsWith("getGpuTargetInstaller$") }.invoke(type.getField("INSTANCE").get(null)) != null
    }
    private fun resourceCounts(packet: FrameEnvelope): Pair<Int, Int> {
        val resources = packet.resourceLease.javaClass.methods.single { it.name.startsWith("resources$") }.invoke(packet.resourceLease) as Map<*, *>
        val missing = resources.values.count { it?.javaClass?.simpleName == "Missing" }; check(missing == 0)
        return resources.values.count { it?.javaClass?.simpleName == "GpuTarget" } to missing
    }
    private fun closeLifecycle() {
        val type = Class.forName("io.github.rwx.render.canvas.CanvasFrameLifecycleTrace")
        type.getMethod("close").invoke(type.getField("INSTANCE").get(null))
    }
    private fun lifecycleCounts(file: File, terminalSequence: Long) = buildJsonObject {
        val rows = file.readLines().drop(1).map { it.split(',') }; val done = rows.last()
        check(done.size == 9 && done[1] == "trace-complete" && done[6] == "0" && done[8] == "true")
        val produced = rows.filter { it[1] == "produced" }
        check(produced.isNotEmpty() && produced.last()[3].toLong() == terminalSequence)
        check(rows.dropLast(1).size == done[5].toInt())
        val nativeSubmitted = rows.filter { it[1] == "submitted" && it[3].toLong() > 0 }
        check(nativeSubmitted.any { it[3].toLong() == terminalSequence })
        put("allCompletedFreezeEvents", produced.size); put("nativeSubmittedEvents", nativeSubmitted.size)
        put("nativeSubmittedUniqueSequences", nativeSubmitted.map { it[2] to it[3] }.toSet().size)
        put("droppedEvents", 0); put("nativeBackend", true); put("finalPublishedSequence", terminalSequence)
    }
    private fun write(file: File, value: JsonObject) = file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value) + "\n")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
