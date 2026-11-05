package io.github.rwx.benchmark

import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.PerformanceProfiler
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.MipMapping
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.MsdfFontData
import de.fabmax.kool.util.MsdfMeta
import io.github.rwx.*
import io.github.rwx.di.coreModule
import io.github.rwx.headless.HeadlessGameSession
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.render.canvas.*
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.model.SettingsModel
import kotlinx.serialization.json.*
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * Opt-in diagnostic main, never a JUnit test. Run from an isolated launch.dir and user.home, with
 * rwx.assetsDir pointing at the complete application assets. Only the production CPU recorder runs:
 * there is no window, KoolContext, GPU installation, upload or presentation measurement.
 *
 * --replay=name.replay --output=new-directory --mode=static|pan|zoom|pan-zoom
 * --warmup-frames=30 --frames=60 --fps=60 --seek-tick=0 --replay-fog=off|on
 * --verify-full-freeze=off|on  correctness-only; requires RWX_FROZEN_CONTENT_REUSE=1
 *
 * The tape is indexed by frame, while real pacing preserves the map cache's monotonic clock policy.
 * Late frames are recorded and never dropped. This is not an FPS or official-build equivalence test.
 */
object RealReplayCanvasCapture {
    private const val FONT = "font/NotoSansCJKsc-Regular"
    private const val RETAIN_FRAMES = 3
    private val json = Json { prettyPrint = true }

    private data class Options(val replay: String, val output: File, val mode: String = "static",
        val warmup: Int = 30, val frames: Int = 60, val fps: Int = 60, val seekTick: Int = 0,
        val replayFog: Boolean = false, val verifyFullFreeze: Boolean = false)

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val code = try { run(parse(args)); 0 } catch (error: Throwable) {
            System.err.println("Real replay CPU capture failed: ${error.message}")
            error.printStackTrace(System.err)
            1
        }
        // Legacy pathfinding/replay workers are process-scoped. All capture owners close before exit.
        exitProcess(code)
    }

    private fun parse(args: Array<String>): Options {
        val values = linkedMapOf<String, String>()
        args.forEach { arg ->
            require(arg.startsWith("--") && '=' in arg) { "Expected --name=value: $arg" }
            val name = arg.substring(2).substringBefore('=')
            require(name in setOf("replay", "output", "mode", "warmup-frames", "frames", "fps", "seek-tick", "replay-fog", "verify-full-freeze")) { "Unknown option: $name" }
            require(values.put(name, arg.substringAfter('=')) == null) { "Duplicate option: $name" }
        }
        fun number(name: String, fallback: Int, range: IntRange): Int =
            (values[name]?.toIntOrNull() ?: if (name !in values) fallback else -1).also {
                require(it in range) { "$name must be in $range" }
            }
        val replay = requireNotNull(values["replay"]) { "--replay is required" }
        require(replay.isNotBlank() && File(replay).name == replay && '/' !in replay && '\\' !in replay && replay !in setOf(".", "..")) {
            "--replay must be a file name inside the isolated replays directory"
        }
        val output = File(requireNotNull(values["output"]) { "--output is required" }).absoluteFile
        val mode = values["mode"] ?: "static"
        require(mode in setOf("static", "pan", "zoom", "pan-zoom")) { "Unsupported mode: $mode" }
        val fog = values["replay-fog"] ?: "off"
        require(fog in setOf("on", "off")) { "--replay-fog must be on or off" }
        val verify = values["verify-full-freeze"] ?: "off"
        require(verify in setOf("on", "off")) { "--verify-full-freeze must be on or off" }
        return Options(replay, output, mode, number("warmup-frames", 30, 0..3600),
            number("frames", 60, 1..3600), number("fps", 60, 20..240), number("seek-tick", 0, 0..36000), fog == "on", verify == "on")
    }

    private fun run(options: Options) {
        require(!System.getProperty("launch.dir").isNullOrBlank()) { "An isolated -Dlaunch.dir is required" }
        require(!System.getProperty("rwx.assetsDir").isNullOrBlank()) { "An explicit -Drwx.assetsDir with complete real assets is required" }
        val configuredAssets = File(System.getProperty("rwx.assetsDir")).canonicalFile
        require(configuredAssets.isDirectory && DesktopPlatformStorage.resolveAssetRoot().canonicalFile == configuredAssets) {
            "rwx.assetsDir must resolve to the explicitly supplied real asset directory"
        }
        require(options.warmup + options.frames > RETAIN_FRAMES) { "At least four total frames are required to check a retained lease" }
        require(!options.verifyFullFreeze || System.getenv("RWX_FROZEN_CONTENT_REUSE") == "1") {
            "--verify-full-freeze=on requires RWX_FROZEN_CONTENT_REUSE=1"
        }
        require(KoolSystem.getContextOrNull() == null) { "This entry point must not have a KoolContext" }
        options.output.parentFile.mkdirs()
        Files.createDirectory(options.output.toPath()) // Deliberately refuses even an empty existing directory.
        try { capture(options) } catch (error: Throwable) {
            options.output.resolve("failure.txt").writeText(error.stackTraceToString())
            throw error
        }
    }

    private fun capture(options: Options) {
        configureDesktopLogging()
        HeadlessMain::class.java.getDeclaredMethod("configureHeadlessPlatform").apply { isAccessible = true }.invoke(HeadlessMain)
        val bridge = DesktopPlatformBridge(audio = NoopPlatformAudio)
        val storage = bridge.storage
        require(storage.modsDir.file.walkTopDown().none { it.isFile }) { "The isolated launch directory must not contain mod files" }
        val replayFile = storage.replaysDir.file.resolve(options.replay)
        require(replayFile.isFile && replayFile.canonicalFile.parentFile == storage.replaysDir.file.canonicalFile) { "Replay not found in isolated replays directory" }
        val tankBytes = requireNotNull(storage.readAssetBytes("units/tanks/tank.png")) { "Real unit assets are missing" }
        val tank = requireNotNull(javax.imageio.ImageIO.read(tankBytes.inputStream())) { "Invalid tank.png" }
        check((0 until tank.height).any { y -> (0 until tank.width).any { x -> tank.getRGB(x, y) ushr 24 != 0 } }) {
            "Transparent server placeholder assets are not valid for capture"
        }
        val metaBytes = requireNotNull(storage.readAssetBytes("$FONT.json")) { "Real font metadata is missing" }
        val atlasBytes = requireNotNull(storage.readAssetBytes("$FONT.png")) { "Real font atlas is missing" }
        val meta = Json.decodeFromString<MsdfMeta>(metaBytes.decodeToString())
        // Texture metadata only: no image loader, context, native graphics call, or upload is installed.
        val fontTexture = Texture2d(TexFormat.RGBA, MipMapping.Off, name = "capture-font-metadata-only")
        val font = MsdfFont(MsdfFontData(fontTexture, meta))
        KoolCanvasFontRegistry.installBaseFont(font)
        val store = KoolCanvasCpuTextureStore()
        val requestedAssets = linkedSetOf<String>()
        val resolvedAssets = linkedSetOf<String>()
        val graphics = KoolGraphicsEngine(store, { path ->
            requestedAssets += path
            storage.readAssetBytes(path).also { if (it != null) resolvedAssets += path }
        })
        val gpu = GpuRecordingGuard()
        var engine: GameEngine? = null
        var retained: FrameEnvelope? = null
        var retainedFingerprint: String? = null
        var retainedVerified = false
        var startedKoin = false
        var fullVerifier: FullFreezeVerifier? = null
        try {
            gpu.install()
            if (options.verifyFullFreeze) fullVerifier = FullFreezeVerifier(store)
            val bindings = module {
                single<PlatformBridge> { bridge }
                single<PlatformStorage> { bridge.storage }
                single<PreferenceStorage> { bridge.preferenceStorage }
                single<AppMetadata> { bridge.appMetadata }
                single<AppLogger> { bridge.logger }
                single<CrashReporter> { bridge.crashReporter }
                single<GraphicsEngine> { graphics }
            }
            GlobalContext.startKoin { modules(coreModule, bindings) }
            startedKoin = true
            storage.createDirectories()
            SettingsEngine.getInstance().save()
            val repository = GlobalContext.get().get<GameSettingsRepository>()
            SettingsModel().also { repository.loadInto(it); repository.saveFrom(it) }
            LocaleSettings.initialize()
            val session = HeadlessGameSession()
            engine = session.boot(graphics) // Must bind this backend before textures/map/minimap exist.
            session.openReplay(options.replay)
            val game = engine
            check(game.replayEngine.j() && game.hasLoadedLevel) { "Replay did not load" }
            check(game.gameSpeed == 1f && game.replayEngine.v == 1) { "Capture requires ordinary 1x replay playback" }
            game.replayEngine.setFogDisplayEnabled(options.replayFog) // Normal replay UI display switch, before vision accumulates.
            val preAdvanceStart = System.nanoTime()
            var preAdvanceFrames = 0
            while (game.currentTick < options.seekTick) {
                check(game.replayEngine.j() && !game.replayEngine.isPlaybackEnded) { "Replay ended before requested seek tick ${options.seekTick}" }
                check(System.nanoTime() - preAdvanceStart < 60_000_000_000L) { "Headless pre-advance timed out at tick ${game.currentTick}" }
                // Ordinary 1x simulation steps, with drawing still disabled; no replay seek/speed rewrite.
                game.gameLoop(1f, 17)
                preAdvanceFrames++
            }
            val preAdvanceWallNanos = System.nanoTime() - preAdvanceStart
            val captureStartTick = game.currentTick
            check(game.replayEngine.j() && !game.replayEngine.isPlaybackEnded) { "Replay ended during headless pre-advance" }
            GameEngine.isHeadlessMode = false // Only bypasses draw()'s early return; java.awt stays headless.
            val counters = CpuCounters()
            val period = 1_000_000_000L / options.fps
            val delta = (60f / options.fps).coerceIn(0f, 3f)
            val frameMillis = (1000f / options.fps).roundToInt()
            val initialZoom = game.targetZoom
            var deadline: Long
            var totalGpuTargets = 0L
            var missedDeadlines = 0
            var maxLateNanos = 0L
            var sampledFrames = 0
            var previousVersions = emptySet<Any?>()
            PerformanceProfiler.frameTimingEnabled = true
            options.output.resolve("manifest.json").writeText(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "real-replay-production-cpu-capture"); put("diagnosticOnly", true)
                put("correctnessOnly", options.verifyFullFreeze); put("performanceMeasurementsInvalid", options.verifyFullFreeze)
                put("fullFreezeVerification", if (options.verifyFullFreeze) "Candidate freezes first; same recorded frame then fully freezes with reuse disabled before background redraw; exact Frame and resource Map equals, without normalization" else "off")
                put("replay", replayFile.canonicalPath); put("replaySha256", sha256(replayFile.readBytes()))
                put("assetRoot", File(System.getProperty("rwx.assetsDir")).canonicalPath)
                put("fontMetadataSha256", sha256(metaBytes)); put("fontAtlasSha256", sha256(atlasBytes))
                put("fontGlyphs", font.data.glyphMap.size); put("tankSha256", sha256(tankBytes))
                put("mode", options.mode); put("warmupFrames", options.warmup); put("frames", options.frames); put("nominalPacingHz", options.fps)
                put("requestedSeekTick", options.seekTick); put("captureStartTick", captureStartTick)
                put("preAdvanceFrames", preAdvanceFrames); put("preAdvanceWallNanos", preAdvanceWallNanos)
                put("requestedReplayFog", options.replayFog); put("fogDisplayEnabled", game.replayEngine.isFogDisplayEnabled)
                put("fogDisplayTeamId", game.replayEngine.fogDisplayTeamId)
                put("preAdvancePolicy", "Unpaced ordinary gameLoop(1f,17) with rendering disabled, 1x replay rules; all pre-advance work excluded from capture counters")
                put("viewportWidth", game.screenWidth.toInt()); put("viewportHeight", game.screenHeight.toInt())
                put("delta", delta); put("frameMillis", frameMillis); put("replayVersion", game.replayEngine.replayVersion)
                put("cameraTape", "frame index / nominal pacing; 20-second diagonal pan and 4-second ordinary constrained zoom cycle")
                put("measurement", "Current engine thread CPU/wall/allocation by production stage; graph inspection and retained-lease checks excluded")
                put("limitations", "No GPU execution, upload, presentation, input latency, FPS, or official-build equivalence. Inspection and lease checks run outside production counters but consume wall time and allocations, perturbing later deadlines and real-time cache budgets despite a fixed tape. Rows buffer detached scalar data; JSON/IO happens after all frames. Detailed command kinds run on first/last frames only. No simulation rules, units, commands, or room/map fog rules are injected; replay fog uses its ordinary display switch.")
            }))
            val rows = ArrayList<FrameRow>(options.warmup + options.frames)
            run {
                deadline = System.nanoTime()
                val pacingStartedAt = deadline
                var previousStart: Long? = null
                repeat(options.warmup + options.frames) { index ->
                    waitUntil(deadline)
                    val start = System.nanoTime()
                    val late = (start - deadline).coerceAtLeast(0)
                    check(game.replayEngine.j() && !game.replayEngine.isPlaybackEnded) { "Replay ended before frame $index" }
                    applyCamera(game, options, index, initialZoom)
                    val beforeTick = game.currentTick
                    val revisionBefore = store.textureRevision
                    game.performanceProfiler.takeFrameTimings()
                    val updateRecord = counters.measure {
                        graphics.beginFrame(game.screenWidth.toInt(), game.screenHeight.toInt())
                        game.gameLoop(delta, frameMillis) // The game loop owns drawing; never draw again here.
                    }.timing
                    val updateWall = game.performanceProfiler.updateNanos
                    val drawWall = game.performanceProfiler.drawNanos
                    val visible = counters.measure {
                        if (TileMap.layerBufferManager.hasVisiblePendingRedraws()) TileMap.layerBufferManager.renderVisiblePendingRedrawsNow()
                    }.timing
                    var recordedForVerification: KoolCanvasFrame? = null
                    val candidateReuseBefore = fullVerifier?.reuseCounters()
                    val frozen = counters.measure {
                        val units = BaseUnit.bE.a()
                        var selected = 0; var drawn = 0
                        for (i in 0 until BaseUnit.bE.b) { if (units[i].isSelected) selected++; if (units[i].shouldDraw) drawn++ }
                        val frame = graphics.snapshot().copy(visualStats = KoolCanvasVisualStats(selected, drawn, game.settingsEngine.adaptiveBattleVisuals))
                        if (options.verifyFullFreeze) recordedForVerification = frame
                        store.freezeFrame(frame, index + 1L, 1L, game.currentTick, 1L)
                    }
                    frozen.value.use { envelope ->
                        val candidateReuse = fullVerifier?.candidateFrozen(checkNotNull(candidateReuseBefore))
                        // Candidate has frozen successfully. Verify before any background redraw can
                        // register new source versions; control work is outside every production timer.
                        val verification = fullVerifier?.let { verifier ->
                            counters.measure { verifier.verify(checkNotNull(recordedForVerification), envelope, checkNotNull(candidateReuse)) }
                        }
                        check(verification?.value?.passed != false) { "Full freeze mismatch: ${verification?.value?.difference}" }
                        val background = counters.measure { TileMap.layerBufferManager.renderOffscreenPendingRedraws(2) }
                        val workEnd = System.nanoTime()
                        val frameOverrun = (workEnd - (deadline + period)).coerceAtLeast(0)
                        if (frameOverrun > 0) missedDeadlines++
                        maxLateNanos = maxOf(maxLateNanos, late)
                        val inspected = counters.measure { // Entire diagnostic inspection is outside production-stage counters.
                            val graph = inspectGraph(envelope, index == 0 || index == options.warmup + options.frames - 1)
                            val versions = resources(envelope).keys.toSet()
                            val versionsAdded = (versions - previousVersions).size
                            val versionsRemoved = (previousVersions - versions).size
                            previousVersions = versions
                            check(graph.missing == 0) { "Frame $index contains ${graph.missing} missing resources" }
                            check(graph.gpuTargets > 0) { "Frame $index has no recorded GPU target; this is the CPU fallback, not the production command graph" }
                            totalGpuTargets += graph.gpuTargets
                            if (index == 0) {
                                retained = envelope.retain()
                                retainedFingerprint = fingerprint(checkNotNull(retained))
                            } else if (index == RETAIN_FRAMES) {
                                check(fingerprint(checkNotNull(retained)) == retainedFingerprint) { "An old retained frame changed after later production frames" }
                                retainedVerified = true
                                retained?.close(); retained = null
                            }
                            Inspection(graph, FrameState(game), versionsAdded, versionsRemoved)
                        }
                        rows += FrameRow(index, index < options.warmup, beforeTick, inspected.value,
                            index * period, deadline - pacingStartedAt, start - pacingStartedAt,
                            previousStart?.let { start - it }, late, frameOverrun, updateRecord, visible,
                            frozen.timing, background.timing, background.value, updateWall, drawWall,
                            inspected.timing, retainedVerified, index == 0 || index == RETAIN_FRAMES,
                            store.textureRevision, store.textureRevision - revisionBefore, verification?.timing, verification?.value)
                        previousStart = start
                        if (index >= options.warmup) sampledFrames++
                    }
                    // Never compress later frame intervals to catch up after a late frame.
                    deadline = maxOf(deadline + period, start + period)
                }
            }
            fullVerifier?.requireCandidateHit()
            val reporting = counters.measure {
                options.output.resolve("frames.ndjson").bufferedWriter().use { writer -> rows.forEach { writer.appendLine(it.toJson().toString()) } }
            }.timing
            val unresolvedImages = requestedAssets.filter { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") }
                .map { it.substringBeforeLast('.') }.toSet() - resolvedAssets.map { it.substringBeforeLast('.') }.toSet()
            check(unresolvedImages.isEmpty()) { "Unresolved real image assets: $unresolvedImages" }
            check(retainedVerified)
            check(KoolSystem.getContextOrNull() == null && fontTexture.gpuTexture == null && gpu.installCalls == 0)
            options.output.resolve("summary.json").writeText(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "real-replay-production-cpu-capture"); put("diagnosticOnly", true); put("completed", true)
                put("sampledFrames", sampledFrames); put("warmupFrames", options.warmup); put("lastTick", game.currentTick)
                put("requestedSeekTick", options.seekTick); put("captureStartTick", captureStartTick)
                put("preAdvanceFrames", preAdvanceFrames); put("preAdvanceWallNanos", preAdvanceWallNanos)
                put("gpuTargetResourcesAcrossFrames", totalGpuTargets); put("gpuInstallCalls", gpu.installCalls)
                put("missedDeadlines", missedDeadlines); put("maxStartLatenessNanos", maxLateNanos)
                put("retainedLeaseStableAcrossFrames", RETAIN_FRAMES); put("retainedFingerprint", retainedFingerprint)
                put("resolvedAssetPaths", resolvedAssets.size); put("unresolvedImageAssets", JsonArray(unresolvedImages.map(::JsonPrimitive)))
                put("deferredFrameReporting", reporting.toJson())
                put("correctnessOnly", options.verifyFullFreeze); put("performanceMeasurementsInvalid", options.verifyFullFreeze)
                fullVerifier?.let { put("fullFreezeVerification", it.report()) }
                put("performanceClaim", "none; CPU diagnostic only")
            }))
            println("Real replay CPU capture complete: ${options.output} ($sampledFrames diagnostic frames)")
        } finally {
            fullVerifier?.let { verifier ->
                options.output.resolve("full-freeze-verification.json").writeText(json.encodeToString(JsonObject.serializer(), verifier.report()))
            }
            retained?.close()
            engine?.replayEngine?.e()
            GameEngine.isHeadlessMode = true
            PerformanceProfiler.frameTimingEnabled = false
            gpu.close()
            store.close()
            KoolCanvasFontRegistry.clearInstalledFonts()
            fontTexture.release()
            if (startedKoin) GlobalContext.stopKoin()
        }
    }

    private fun applyCamera(game: GameEngine, options: Options, index: Int, initialZoom: Float) {
        val seconds = index.toDouble() / options.fps
        if (options.mode == "zoom" || options.mode == "pan-zoom") {
            game.targetZoom = ReplayPanBenchmark.constrainedTargetZoom(
                ReplayPanBenchmark.cycleZoom((seconds % 4) / 4, .35f, 1.5f),
                game.currentScreenWidthPixels, game.currentScreenHeightPixels,
                game.tileMap.worldWidth, game.tileMap.worldHeight, game.densityZoomScale)
        } else game.targetZoom = initialZoom
        if (options.mode == "pan" || options.mode == "pan-zoom") {
            val displacement = sin(seconds * 2 * PI / 20).toFloat()
            val spanX = ((game.tileMap.worldWidth - game.viewpointWidth) / 2f).coerceIn(0f, 600f)
            val spanY = ((game.tileMap.worldHeight - game.visibleWorldHeight) / 2f).coerceIn(0f, 600f)
            game.setViewpoint((game.tileMap.worldWidth - game.viewpointWidth) / 2f + spanX * displacement,
                (game.tileMap.worldHeight - game.visibleWorldHeight) / 2f + spanY * displacement)
            game.clampCameraPosition()
        }
    }

    private fun waitUntil(deadline: Long) {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            LockSupport.parkNanos(remaining)
            check(!Thread.interrupted()) { "Capture interrupted" }
        }
    }

    private data class Timing(val wallNanos: Long, val cpuNanos: Long, val allocatedBytes: Long) {
        fun toJson() = buildJsonObject { put("wallNanos", wallNanos); put("cpuNanos", cpuNanos); put("allocatedBytes", allocatedBytes) }
    }
    private data class Measured<T>(val value: T, val timing: Timing)
    private class CpuCounters {
        private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        private val thread = Thread.currentThread().threadId()
        init {
            check(bean.isCurrentThreadCpuTimeSupported && bean.isThreadAllocatedMemorySupported)
            bean.isThreadCpuTimeEnabled = true; bean.isThreadAllocatedMemoryEnabled = true
        }
        fun <T> measure(block: () -> T): Measured<T> {
            val allocation = bean.getThreadAllocatedBytes(thread)
            val cpu = bean.currentThreadCpuTime
            val wall = System.nanoTime()
            val value = block()
            val wallNanos = System.nanoTime() - wall
            val cpuNanos = bean.currentThreadCpuTime - cpu
            val bytes = bean.getThreadAllocatedBytes(thread) - allocation
            return Measured(value, Timing(wallNanos, cpuNanos, bytes))
        }
    }

    /** Reflection stays in this diagnostic file; core's resource ownership API remains internal. */
    private class GpuRecordingGuard : AutoCloseable {
        private val type = Class.forName("io.github.rwx.render.canvas.FrozenCanvasGpuResources")
        private val owner = type.getField("INSTANCE").get(null)
        private val setter = type.methods.single { it.name.startsWith("setGpuTargetInstaller$") }
        private val previous = type.methods.single { it.name.startsWith("getGpuTargetInstaller$") }.invoke(owner)
        var installCalls = 0
            private set
        fun install() {
            check(previous == null) { "A GPU owner is already installed" }
            val fail: (Any?, Any?) -> Unit = { _, _ -> installCalls++; error("CPU capture must never install GPU resources") }
            setter.invoke(owner, fail)
        }
        override fun close() { setter.invoke(owner, previous) }
    }

    private fun resources(envelope: FrameEnvelope): Map<*, *> = envelope.resourceLease.javaClass.methods
        .single { it.name.startsWith("resources$") }.invoke(envelope.resourceLease) as Map<*, *>
    private fun property(value: Any, name: String): Any = value.javaClass.getMethod("get$name").invoke(value)

    private data class FreezeDifference(val kind: String, val key: String, val candidateClass: String?, val controlClass: String?) {
        fun toJson() = buildJsonObject {
            put("kind", kind); put("key", key); put("candidateClass", candidateClass); put("controlClass", controlClass)
        }
    }
    private data class ReuseActivity(val attempts: Long, val hits: Long)
    private data class FreezeComparison(val passed: Boolean, val frameEqual: Boolean, val resourceMapEqual: Boolean,
        val controlReuseAttempts: Long, val restoredReuseEnabled: Boolean, val difference: FreezeDifference?, val candidateReuse: ReuseActivity) {
        fun toJson() = buildJsonObject {
            put("correctnessOnly", true); put("passed", passed); put("frameEqual", frameEqual); put("resourceMapEqual", resourceMapEqual)
            put("controlReuseAttempts", controlReuseAttempts); put("restoredReuseEnabled", restoredReuseEnabled)
            put("candidateReuseAttempts", candidateReuse.attempts); put("candidateReuseHits", candidateReuse.hits)
            difference?.let { put("firstDifference", it.toJson()) }
        }
    }
    /** This intentionally perturbs cache state and time. It can never be used for performance results. */
    private class FullFreezeVerifier(private val store: KoolCanvasCpuTextureStore) {
        private val reuseField = store.javaClass.getDeclaredField("reuseFrozenContent").apply { isAccessible = true }
        private val attempts = store.javaClass.getDeclaredField("REUSE_ATTEMPTS").apply { isAccessible = true }.get(null) as AtomicLong
        private val hits = store.javaClass.getDeclaredField("REUSE_HITS").apply { isAccessible = true }.get(null) as AtomicLong
        private var checked = 0
        private var passed = 0
        private var candidateAttempts = 0L
        private var candidateHits = 0L
        private var evidenceFailure: FreezeDifference? = null
        private var firstFailure: FreezeComparison? = null
        init { check(reuseField.getBoolean(store)) { "Candidate store must have frozen-content reuse enabled" } }

        fun reuseCounters() = ReuseActivity(attempts.get(), hits.get())
        fun candidateFrozen(before: ReuseActivity): ReuseActivity {
            val delta = ReuseActivity(attempts.get() - before.attempts, hits.get() - before.hits)
            candidateAttempts += delta.attempts; candidateHits += delta.hits
            return delta
        }
        fun requireCandidateHit() {
            if (candidateHits == 0L) evidenceFailure = FreezeDifference("candidate-reuse-not-observed", "REUSE_HITS", "0", ">0 required across the complete run")
            check(candidateHits > 0L) { "Candidate never hit frozen-content reuse; full-vs-full comparisons are insufficient evidence" }
        }

        fun verify(recorded: KoolCanvasFrame, candidate: FrameEnvelope, candidateReuse: ReuseActivity): FreezeComparison {
            checked++
            var control: FrameEnvelope? = null
            var frameEqual = false
            var mapEqual = false
            var attemptsDelta = -1L
            var difference: FreezeDifference? = null
            try {
                check(reuseField.getBoolean(store)) { "Candidate reuse flag was not restored" }
                // The candidate always freezes first. Full traversal can replace cache metadata, so
                // reversing these freezes would stop exercising the actual candidate's cached graph.
                reuseField.setBoolean(store, false)
                check(!reuseField.getBoolean(store)) { "Cannot disable final reuseFrozenContent field" }
                val attemptsBefore = attempts.get()
                control = store.freezeFrame(recorded, candidate.sequence, candidate.generation,
                    candidate.simulationTick, candidate.viewportRevision, candidate.camera)
                attemptsDelta = attempts.get() - attemptsBefore
                check(attemptsDelta == 0L) { "Control freeze still attempted reuse ($attemptsDelta); final-field override is not reliable" }
                frameEqual = candidate.frame == control.frame
                val candidateResources = resources(candidate)
                val controlResources = resources(control)
                mapEqual = candidateResources == controlResources
                if (!frameEqual) difference = frameDifference(candidate.frame, control.frame, "root")
                if (difference == null && !mapEqual) {
                    val key = candidateResources.keys.firstOrNull { it !in controlResources }
                        ?: controlResources.keys.firstOrNull { it !in candidateResources }
                        ?: candidateResources.keys.first { candidateResources[it] != controlResources[it] }
                    val left = candidateResources[key]
                    val right = controlResources[key]
                    difference = FreezeDifference("resource-map-value-or-key", key.toString(), left?.javaClass?.name, right?.javaClass?.name)
                    if (left != null && right != null && left.javaClass.simpleName in setOf("GpuTarget", "Frame") && left.javaClass == right.javaClass) {
                        val leftFrame = property(left, "Frame") as KoolCanvasFrame
                        val rightFrame = property(right, "Frame") as KoolCanvasFrame
                        if (leftFrame != rightFrame) difference = frameDifference(leftFrame, rightFrame, key.toString())
                    }
                }
            } catch (error: Throwable) {
                difference = FreezeDifference("verification-error", error.message ?: "no message", error.javaClass.name, null)
            } finally {
                try {
                    reuseField.setBoolean(store, true)
                    check(reuseField.getBoolean(store)) { "Cannot restore final reuseFrozenContent field" }
                } catch (error: Throwable) {
                    difference = FreezeDifference("restore-error", error.message ?: "no message", error.javaClass.name, null)
                } finally { control?.close() }
            }
            val restored = reuseField.getBoolean(store)
            val result = FreezeComparison(frameEqual && mapEqual && attemptsDelta == 0L && restored && difference == null,
                frameEqual, mapEqual, attemptsDelta, restored, difference, candidateReuse)
            if (result.passed) passed++ else if (firstFailure == null) firstFailure = result
            return result
        }

        private fun frameDifference(left: KoolCanvasFrame, right: KoolCanvasFrame, key: String): FreezeDifference {
            val suffix = when {
                left.viewport != right.viewport -> ".viewport"
                left.visualStats != right.visualStats -> ".visualStats"
                left.commands.size != right.commands.size -> ".commands.size"
                else -> ".commands[${left.commands.indices.firstOrNull { left.commands[it] != right.commands[it] }}]"
            }
            return FreezeDifference("frame-equals", key + suffix, left.javaClass.name, right.javaClass.name)
        }

        fun report() = buildJsonObject {
            put("correctnessOnly", true); put("performanceMeasurementsInvalid", true)
            put("checkedFrames", checked); put("passedFrames", passed); put("failedFrames", checked - passed)
            put("candidateReuseAttempts", candidateAttempts); put("candidateReuseHits", candidateHits)
            put("candidateReuseObserved", candidateHits > 0L)
            put("evidencePassed", checked > 0 && passed == checked && candidateHits > 0L && firstFailure == null && evidenceFailure == null)
            put("comparison", "Exact data-object equals for Frame (including visualStats) and complete resource Map; no normalization")
            put("controlGuard", "reuseFrozenContent=false and restored=true readback; REUSE_ATTEMPTS delta must equal zero")
            firstFailure?.let { put("firstFailure", it.toJson()) }
            evidenceFailure?.let { put("evidenceFailure", it.toJson()) }
        }
    }

    private class FrameState(game: GameEngine) {
        val tick = game.currentTick
        val gameTimeMillis = game.gameTimeMillis
        val cameraX = game.viewpointX
        val cameraY = game.viewpointY
        val targetZoom = game.targetZoom
        val actualZoom = game.zoom
        val densityScale = game.densityZoomScale
        val worldWidth = game.visibleWorldWidth
        val worldHeight = game.visibleWorldHeight
        val units = BaseUnit.bE.b
        var visibleUnits = 0
            private set
        var movingUnits = 0
            private set
        val roomFog = game.networkEngine.roomSettings.fogMode
        val displayFog = game.replayEngine.isFogDisplayEnabled
        val fogTeam = game.replayEngine.fogDisplayTeamId
        val mapFog = game.tileMap.fogEnabled
        val mapPeriodicFog = game.tileMap.fogPeriodicMaintenanceEnabled
        init {
            val list = BaseUnit.bE.a()
            for (i in 0 until units) {
                if (list[i].shouldDraw) visibleUnits++
                if (list[i].velocityX != 0f || list[i].velocityY != 0f) movingUnits++
            }
        }
    }
    private data class Inspection(val graph: Graph, val state: FrameState, val versionsAdded: Int, val versionsRemoved: Int)
    private data class FrameRow(val index: Int, val warmup: Boolean, val tickBefore: Int, val observed: Inspection,
        val nominalOffset: Long, val deadlineOffset: Long, val actualStartOffset: Long, val actualStartInterval: Long?,
        val late: Long, val overrun: Long, val updateRecord: Timing, val visible: Timing, val freeze: Timing,
        val background: Timing, val backgroundCells: Int, val updateWall: Long, val drawWall: Long,
        val inspection: Timing, val retainedVerified: Boolean, val leaseCheck: Boolean, val storeRevision: Int, val revisionChanges: Int,
        val verificationTiming: Timing?, val verification: FreezeComparison?) {
        fun toJson() = buildJsonObject {
            val state = observed.state
            put("frame", index); put("warmup", warmup); put("diagnosticOnly", true)
            put("tickBefore", tickBefore); put("tick", state.tick); put("gameTimeMillis", state.gameTimeMillis)
            put("cameraX", state.cameraX); put("cameraY", state.cameraY); put("targetZoom", state.targetZoom); put("actualZoom", state.actualZoom)
            put("densityZoomScale", state.densityScale); put("visibleWorldWidth", state.worldWidth); put("visibleWorldHeight", state.worldHeight)
            put("units", state.units); put("visibleUnits", state.visibleUnits); put("movingUnits", state.movingUnits)
            put("roomFogMode", state.roomFog); put("fogDisplayEnabled", state.displayFog); put("fogDisplayTeamId", state.fogTeam)
            put("mapFogEnabled", state.mapFog); put("mapFogPeriodicMaintenanceEnabled", state.mapPeriodicFog)
            put("nominalOffsetNanos", nominalOffset); put("deadlineOffsetNanos", deadlineOffset)
            put("actualStartOffsetNanos", actualStartOffset); put("actualStartIntervalNanos", actualStartInterval)
            put("startLatenessNanos", late); put("missedDeadline", overrun > 0); put("workOverrunNanos", overrun)
            put("updateRecord", updateRecord.toJson()); put("visibleRedraw", visible.toJson()); put("snapshotFreeze", freeze.toJson())
            put("offscreenBackground", background.toJson()); put("offscreenCellsRendered", backgroundCells)
            put("updateSectionWallNanos", updateWall); put("drawSectionWallNanos", drawWall)
            put("diagnosticInspection", inspection.toJson()); put("graph", observed.graph.toJson())
            put("retainedLeaseVerified", retainedVerified); put("retainedLeaseCheckThisFrame", leaseCheck)
            put("storeTextureRevision", storeRevision); put("storeRevisionChanges", revisionChanges)
            put("frozenResourceVersionsAdded", observed.versionsAdded); put("frozenResourceVersionsRemoved", observed.versionsRemoved)
            verification?.let { put("fullFreezeVerification", it.toJson()); put("performanceMeasurementsInvalid", true) }
            verificationTiming?.let { put("correctnessOnlyFullFreezeAndCompare", it.toJson()) }
        }
    }

    private data class Graph(val rootCommands: Int, val nestedCommands: Int, val resourceCount: Int,
        val gpuTargets: Int, val missing: Int, val pixelCount: Long, val assetBytes: Long,
        val kinds: Map<String, Int>, val commandKinds: Map<String, Int>?) {
        fun toJson() = buildJsonObject {
            put("rootCommands", rootCommands); put("nestedCommands", nestedCommands); put("resourceCount", resourceCount)
            put("gpuTargets", gpuTargets); put("missing", missing); put("pixelCount", pixelCount); put("assetBytes", assetBytes)
            put("resourceKinds", JsonObject(kinds.mapValues { JsonPrimitive(it.value) }))
            put("detailedCommandKindsDiagnostic", commandKinds != null)
            commandKinds?.let { put("commandKinds", JsonObject(it.mapValues { entry -> JsonPrimitive(entry.value) })) }
        }
    }
    private fun inspectGraph(envelope: FrameEnvelope, detailed: Boolean): Graph {
        val resources = resources(envelope)
        val kinds = sortedMapOf<String, Int>(); val commands = if (detailed) sortedMapOf<String, Int>() else null
        fun count(frame: KoolCanvasFrame) { if (commands != null) frame.commands.forEach { commands.merge(it.javaClass.simpleName, 1, Int::plus) } }
        count(envelope.frame)
        var nested = 0; var pixels = 0L; var assets = 0L
        resources.values.filterNotNull().forEach { resource ->
            val kind = resource.javaClass.simpleName
            kinds.merge(kind, 1, Int::plus)
            when (kind) {
                "GpuTarget", "Frame" -> (property(resource, "Frame") as KoolCanvasFrame).also { nested += it.commands.size; count(it) }
                "Pixels" -> pixels += (property(resource, "Image") as KoolCanvasArgbImage).pixels.size
                "Asset" -> assets += (property(resource, "EncodedBytes") as ByteArray).size
            }
        }
        return Graph(envelope.frame.commands.size, nested, resources.size, kinds["GpuTarget"] ?: 0,
            kinds["Missing"] ?: 0, pixels, assets, kinds, commands)
    }

    /** Same-lease stability check, not a cross-process equivalence hash. Includes mutable backing arrays. */
    private fun fingerprint(envelope: FrameEnvelope): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun integer(value: Int) { digest.update((value ushr 24).toByte()); digest.update((value ushr 16).toByte()); digest.update((value ushr 8).toByte()); digest.update(value.toByte()) }
        fun frame(value: KoolCanvasFrame) {
            integer(value.viewport.hashCode()); integer(value.commands.size)
            value.commands.forEach { command ->
                integer(command.hashCode())
                val batch = when (command) {
                    is KoolCanvasCommand.DrawTextureBatch -> command.quads
                    is KoolCanvasCommand.DrawRectBatch -> command.rects
                    is KoolCanvasCommand.DrawFogBatch -> command.masks
                    else -> null
                }
                batch?.javaClass?.declaredFields?.singleOrNull { it.type == FloatArray::class.java }?.apply { isAccessible = true }
                    ?.get(batch)?.let { data -> (data as FloatArray).forEach { integer(it.toRawBits()) } }
            }
        }
        frame(envelope.frame)
        resources(envelope).entries.sortedBy { it.key.toString() }.forEach { (id, raw) ->
            digest.update(id.toString().toByteArray())
            val resource = checkNotNull(raw)
            val kind = resource.javaClass.simpleName
            digest.update(kind.toByteArray())
            when (kind) {
                "GpuTarget", "Frame" -> frame(property(resource, "Frame") as KoolCanvasFrame)
                "Pixels" -> (property(resource, "Image") as KoolCanvasArgbImage).also { image ->
                    integer(image.width); integer(image.height); image.pixels.forEach(::integer)
                }
                "Asset" -> digest.update(property(resource, "EncodedBytes") as ByteArray)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
