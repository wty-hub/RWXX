package io.github.rwx.benchmark

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
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
import io.github.rwx.headless.HeadlessGraphicsEngine
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.kool.KoolDesktopGameSession
import io.github.rwx.render.canvas.FrameEnvelope
import io.github.rwx.render.canvas.KoolCanvasFontRegistry
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.render.canvas.KoolGraphicsEngine
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.model.SettingsModel
import kotlinx.serialization.json.*
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.system.exitProcess

/**
 * Opt-in CPU diagnostic main, never a JUnit test. The real KoolDesktopGameSession owner runs its
 * automatic engineFrame -> gameLoop/draw -> freeze -> mailbox path. This main NEVER steps gameLoop.
 * No Scene, KoolContext, window, render host, GPU installer, upload or presentation is created.
 *
 * --output=/new/run/capture --replay=bridges-fixed.replay --warmup-seconds=20 --sample-seconds=20
 * --target-fps=120 --timeout-seconds=120 --poll-micros=1000
 *
 * Before launching a NEW JVM for each arm, stage the exact replay in launch.dir/replays and provide:
 * -Djava.awt.headless=true -Dlaunch.dir=/new/run/sandbox -Duser.home=/new/run/home
 * -Drwx.log.dir=/new/run/logs -Drwx.assetsDir=/explicit/complete/assets
 * launch.dir, user.home and rwx.log.dir must be distinct children of output.parentFile.
 * RWX_DESKTOP_TARGET_FPS=120 RWX_STABLE_OWNER_DEADLINES=0|1; leave the global
 * RWX_STABLE_FRAME_DEADLINES unset. Actual owner booleans are read back, never reflected into place.
 * RWX_REPLAY_PAN_OUTPUT=<output>/scenario.ndjson, WARMUP_SECONDS=20, SAMPLE_SECONDS=20,
 * REPETITIONS=1, CAMERA_MODE=pan-zoom, PERIOD_SECONDS=20, ZOOM_MODE=cycle,
 * ZOOM_PERIOD_SECONDS=4, ZOOM_MIN=.35, ZOOM_MAX=1.5, FOG=off (all with RWX_REPLAY_PAN_ prefix).
 * RWX_FRAME_LIFECYCLE_TRACE=<output>/producer-lifecycle.csv supplies existing bounded primitive
 * freeze-completed events. Its shutdown-only serialization is explicitly closed AFTER owner stop.
 *
 * World-draw callbacks precede HUD/effects/freeze. GameLogic.lastDelta is a simulation-update value,
 * NOT engineFrame(deltaSeconds). Only the existing lifecycle event denotes completed freeze; it
 * does not denote completed whole owner iteration, GPU execution, fresh presentation or scanout.
 * Without a real GPU installer the session naturally uses CPU raster fallback. No dummy installer
 * is installed to alter that availability. Results cannot be treated as visible-GPU-path FPS.
 */
object OwnerReplayCadenceCapture {
    private const val MAX_ROWS = 65_536
    private const val FONT = "font/NotoSansCJKsc-Regular"
    private const val REPLAY_SHA256 = "d19b132ec8866f87496b3c1c91627e7ef35a99b1627e2a9550f74c0e0e57f6d1"
    private const val SECOND = 1_000_000_000L
    private val json = Json { prettyPrint = true }

    private data class Options(val output: File, val replay: String, val warmup: Int,
        val sample: Int, val fps: Int, val timeout: Int, val pollMicros: Int)

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val code = try {
            val options = parse(args)
            val deadline = System.nanoTime() + options.timeout * SECOND
            Thread({
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) {
                        System.err.println("Owner replay CPU diagnostic deadline exceeded; invalid, halting124")
                        Runtime.getRuntime().halt(124) // Includes setup/teardown; the outer runner must also bound it.
                    }
                    LockSupport.parkNanos(minOf(remaining, 100_000_000L))
                }
            }, "owner-replay-capture-deadline").apply { isDaemon = true; start() }
            run(options, deadline)
            0
        } catch (error: Throwable) {
            System.err.println("Owner replay CPU diagnostic failed: ${error.message}")
            error.printStackTrace(System.err)
            1
        }
        // Legacy replay/pathfinder workers are process-scoped, as in the other opt-in capture mains.
        // No DesktopMain auto-exit loop is started; all reports/close checks finish before this exit.
        // Keep the daemon deadline alive during shutdown hooks too; normal JVM exit ends it.
        exitProcess(code)
    }

    private fun parse(args: Array<String>): Options {
        val values = linkedMapOf<String, String>()
        val allowed = setOf("output", "replay", "warmup-seconds", "sample-seconds", "target-fps", "timeout-seconds", "poll-micros")
        args.forEach { arg ->
            require(arg.startsWith("--") && '=' in arg) { "Expected --name=value: $arg" }
            val name = arg.substring(2).substringBefore('=')
            require(name in allowed && values.put(name, arg.substringAfter('=')) == null) { "Unknown/duplicate option: $name" }
        }
        fun number(name: String, fallback: Int, range: IntRange): Int =
            (values[name]?.toIntOrNull() ?: if (name in values) -1 else fallback).also {
                require(it in range) { "$name must be in $range" }
            }
        val replay = values["replay"] ?: "bridges-fixed.replay"
        require(replay.isNotBlank() && File(replay).name == replay && '/' !in replay && '\\' !in replay && replay !in setOf(".", ".."))
        val options = Options(File(requireNotNull(values["output"]) { "--output is required" }).canonicalFile,
            replay, number("warmup-seconds", 20, 0..60), number("sample-seconds", 20, 1..60),
            number("target-fps", 120, 61..300), number("timeout-seconds", 120, 20..180),
            number("poll-micros", 1000, 100..10_000))
        require(options.timeout >= options.warmup + options.sample + 10) { "Allow at least10s for load/teardown" }
        return options
    }

    private fun run(options: Options, deadline: Long) {
        val runRoot = options.output.parentFile.canonicalFile.toPath()
        val isolated = listOf("launch.dir", "user.home", "rwx.log.dir").map { property ->
            File(requireNotNull(System.getProperty(property)) { "Explicit isolated -D$property is required" }).canonicalFile.also {
                require(it.toPath().startsWith(runRoot) && it.toPath() != runRoot && it != options.output) {
                    "$property must be a child of the isolated run root: $runRoot"
                }
            }
        }
        require(isolated.distinct().size == isolated.size) { "Isolated home, launch and logs must be distinct" }
        val assets = File(requireNotNull(System.getProperty("rwx.assetsDir")) { "Explicit real assets required" }).canonicalFile
        require(assets.isDirectory && DesktopPlatformStorage.resolveAssetRoot().canonicalFile == assets)
        require(KoolSystem.getContextOrNull() == null) { "No KoolContext is allowed" }
        require(System.getenv("RWX_STABLE_FRAME_DEADLINES") == null) { "Global pacing flag must be unset; change owner only" }
        val ownerRequest = System.getenv("RWX_STABLE_OWNER_DEADLINES")
        require(ownerRequest in setOf("0", "1")) { "Explicit RWX_STABLE_OWNER_DEADLINES=0|1 is required" }
        require(System.getenv("RWX_DESKTOP_TARGET_FPS") == options.fps.toString()) { "Target FPS env must match CLI" }
        require(System.getenv("RWX_POST_INPUT_FRAME_WAIT") != "1") { "Collector must never wait for owner publications" }
        listOf("RWX_FRAME_TRACE", "RWX_FRAME_METRICS", "RWX_PERF_LOG", "RWX_FULL_STATE_LOG",
            "RWX_CANVAS_STAGE_TRACE", "RWX_CANVAS_TRACE", "RWX_ENGINE_FRAME_TRACE",
            "RWX_VK_TRACE", "RWX_DEBUG_AUTO_EXIT_SECONDS").forEach {
            require(System.getenv(it).isNullOrBlank()) { "$it must be absent for this CPU diagnostic" }
        }
        require(System.getenv("RWX_NORMAL_INPUT_PROBE") != "1")
        val pan = requireNotNull(ReplayPanBenchmark.options(System.getenv())) { "ReplayPanBenchmark env must be supplied" }
        require(pan.output.canonicalFile == options.output.resolve("scenario.ndjson"))
        require(pan.requireReplay && pan.cameraMode == "pan" && pan.zoomMode == "cycle" && pan.repetitions == 1)
        require(pan.warmupSeconds == options.warmup && pan.sampleSeconds == options.sample)
        require(pan.cameraPeriodSeconds == 20 && pan.zoomPeriodSeconds == 4 && pan.zoomMinimum == .35f && pan.zoomMaximum == 1.5f)
        require(pan.cameraTrace == null && pan.exportMap == null && pan.localFogMode == null && pan.playerDeathSeconds == null)
        require(System.getenv("RWX_REPLAY_PAN_FOG") in setOf(null, "off"))
        val lifecycle = File(requireNotNull(System.getenv("RWX_FRAME_LIFECYCLE_TRACE")) {
            "Existing bounded lifecycle trace is required to count actual completed freezes"
        }).canonicalFile
        require(lifecycle == options.output.resolve("producer-lifecycle.csv"))
        options.output.parentFile.mkdirs()
        Files.createDirectory(options.output.toPath()) // Refuse even an empty previous capture directory.
        try { capture(options, deadline, assets, lifecycle, ownerRequest == "1") }
        catch (error: Throwable) {
            options.output.resolve("failure.txt").writeText(error.stackTraceToString())
            throw error
        }
    }

    private fun capture(options: Options, deadline: Long, assets: File, lifecycle: File, expectedStable: Boolean) {
        configureDesktopLogging()
        HeadlessMain::class.java.getDeclaredMethod("configureHeadlessPlatform").apply { isAccessible = true }.invoke(HeadlessMain)
        val bridge = DesktopPlatformBridge(audio = NoopPlatformAudio)
        val storage = bridge.storage
        require(storage.modsDir.file.walkTopDown().none { it.isFile }) { "No custom mod files in isolated launch directory" }
        val replay = storage.replaysDir.file.resolve(options.replay).canonicalFile
        require(replay.isFile && replay.parentFile == storage.replaysDir.file.canonicalFile)
        require(sha256(replay.readBytes()) == REPLAY_SHA256) { "Wrong replay bytes" }
        val tankBytes = requireNotNull(storage.readAssetBytes("units/tanks/tank.png"))
        val tank = requireNotNull(javax.imageio.ImageIO.read(tankBytes.inputStream()))
        require((0 until tank.height).any { y -> (0 until tank.width).any { x -> tank.getRGB(x, y) ushr 24 != 0 } }) {
            "Transparent server placeholders are not real game assets"
        }
        val metaBytes = requireNotNull(storage.readAssetBytes("$FONT.json"))
        val atlasBytes = requireNotNull(storage.readAssetBytes("$FONT.png"))
        val meta = Json.decodeFromString<MsdfMeta>(metaBytes.decodeToString())
        // Metadata and detached CPU font metrics only; never load or upload this texture.
        val fontTexture = Texture2d(TexFormat.RGBA, MipMapping.Off, name = "owner-capture-font-metadata-only")
        KoolCanvasFontRegistry.installBaseFont(MsdfFont(MsdfFontData(fontTexture, meta)))
        val gpu = GpuAvailability() // Read only. In particular, do not install a dummy installer.
        check(!gpu.available()) { "A GPU resource owner was already installed" }
        val callbacks = WorldRows()
        val envelopes = EnvelopeRows()
        var session: KoolDesktopGameSession? = null
        var game: GameLogic? = null
        var previousListener: Runnable? = null
        var startedKoin = false
        var stoppedOwner = false
        var actualPolicy: JsonObject? = null
        var firstResources: JsonObject? = null
        var lastResources: JsonObject? = null
        var polls = 0L
        var repeats = 0L
        var emptyPolls = 0L
        var observedGaps = 0L
        var lastGeneration = -1L
        var lastSequence = -1L
        var failure: Throwable? = null
        fun remainingSeconds(): Long = TimeUnit.NANOSECONDS.toSeconds((deadline - System.nanoTime()).coerceAtLeast(0L)).coerceIn(1, 30)
        try {
            val bindings = module {
                single<PlatformBridge> { bridge }; single<PlatformStorage> { storage }
                single<PreferenceStorage> { bridge.preferenceStorage }; single<AppMetadata> { bridge.appMetadata }
                single<AppLogger> { bridge.logger }; single<CrashReporter> { bridge.crashReporter }
                single<GraphicsEngine> { HeadlessGraphicsEngine(get()) }
            }
            GlobalContext.startKoin { modules(coreModule, bindings) }
            startedKoin = true
            storage.createDirectories()
            SettingsEngine.getInstance().save()
            val repository = GlobalContext.get().get<GameSettingsRepository>()
            SettingsModel().also { repository.loadInto(it); repository.saveFrom(it) }
            LocaleSettings.initialize()
            write(options.output.resolve("manifest.json"), buildJsonObject {
                put("kind", "actual-owner-real-replay-cpu-producer-diagnostic"); put("diagnosticOnly", true)
                put("replay", replay.path); put("replaySha256", REPLAY_SHA256); put("requestedStartTick", 0)
                put("assetRoot", assets.path); put("fontMetadataSha256", sha256(metaBytes)); put("fontAtlasSha256", sha256(atlasBytes))
                put("tankSha256", sha256(tankBytes)); put("warmupSeconds", options.warmup); put("sampleSeconds", options.sample)
                put("targetFps", options.fps); put("pollMicros", options.pollMicros); put("rowCapacity", MAX_ROWS)
                put("javaVersion", System.getProperty("java.version")); put("osArch", System.getProperty("os.arch"))
                put("inputArguments", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
                put("helperClassSha256", sha256(checkNotNull(OwnerReplayCadenceCapture::class.java.getResourceAsStream("OwnerReplayCadenceCapture.class")).use { it.readBytes() }))
                put("sourceSha256Requested", System.getenv("RWX_OWNER_CAPTURE_SOURCE_SHA256"))
                put("gpuInstallerAvailable", false); put("dummyGpuInstallerInstalled", false)
                put("workload", "Real automatic KoolDesktopGameSession owner, CPU recorder and natural no-installer raster fallback; no hand-stepped simulation")
                put("camera", "Existing ReplayPanBenchmark owner callback; monotonic wall-clock ordinary pan20s/zoom4s .35..1.5, not frame-index tape")
            })
            val active = KoolDesktopGameSession(storage).also { session = it }
            val logic = (active.preload(KoolCanvasViewport(1280, 720)) as GameLogic).also { game = it }
            active.submitSessionTask {
                check(logic.renderGraphicsEngine is KoolGraphicsEngine)
                check(logic.worldFrameRenderedListener == null) { "Do not replace an existing game listener" }
                previousListener = logic.worldFrameRenderedListener
                logic.settingsEngine.highRefreshRate = true
                logic.settingsEngine.batterySaving = false
                logic.settingsEngine.renderVsync = false
                logic.settingsEngine.maxFrameRate = options.fps
                logic.settingsEngine.showFps = false
                logic.worldFrameRenderedListener = Runnable { callbacks.record(logic) }
                GameEngine.isHeadlessMode = false // Only enables CPU draw; java.awt remains headless.
            }.get(remainingSeconds(), TimeUnit.SECONDS)
            actualPolicy = readOwnerPolicy(active)
            check(actualPolicy["stableFrameDeadlines"]?.jsonPrimitive?.boolean == expectedStable) { "Actual owner policy did not match request" }
            check(actualPolicy["legacyPacing"]?.jsonPrimitive?.boolean == true) { "Keep the default full-park owner branch" }
            write(options.output.resolve("setup.json"), buildJsonObject {
                put("actualOwnerPolicy", checkNotNull(actualPolicy)); put("highRefreshRate", true); put("batterySaving", false)
                put("vsync", false); put("targetFps", options.fps); put("requestedPeriodNanos", SECOND / options.fps)
                put("globalStableEnvironment", System.getenv("RWX_STABLE_FRAME_DEADLINES"))
                put("ownerStableEnvironment", System.getenv("RWX_STABLE_OWNER_DEADLINES"))
                put("gpuInstallerAvailable", gpu.available()); put("javaAwtHeadless", System.getProperty("java.awt.headless"))
                put("windowActivity", "Unmodified no-window default model; not real foreground telemetry")
            })
            active.prepareReplayAsync(options.replay, KoolCanvasViewport(1280, 720))
            // This thread is a consumer only. Polling neither steps nor submits work to the owner.
            // No per-frame owner RPC, simulation serialization, JSON, graph walk or file IO here.
            while (true) {
                check(System.nanoTime() < deadline) { "Capture deadline reached" }
                active.mapLoadError(options.replay)?.let { throw IllegalStateException("Owner/replay load failed", it) }
                val before = System.nanoTime()
                active.currentFrame()
                val envelope = active.currentFrameEnvelope()
                polls++
                if (envelope == null) emptyPolls++
                else if (envelope.generation == lastGeneration && envelope.sequence == lastSequence) repeats++
                else envelope.retain().use { packet ->
                    if (packet.generation == lastGeneration) {
                        check(packet.sequence > lastSequence) { "Published sequence regressed" }
                        observedGaps += packet.sequence - lastSequence - 1
                    }
                    envelopes.record(packet, before)
                    lastGeneration = packet.generation; lastSequence = packet.sequence
                    // Once on the first nonempty packet (normally warmup); final inspection is after capture.
                    if (firstResources == null && packet.resourceLease.resourceCount > 0)
                        firstResources = inspectResources(packet)
                }
                val completed = ReplayPanBenchmark.completedAtNanos
                if (completed > 0L && System.nanoTime() - completed >= 250_000_000L) break
                LockSupport.parkNanos(options.pollMicros * 1000L)
            }
            active.currentFrameEnvelope()?.retain()?.use { lastResources = inspectResources(it) }
            check(active.isMapLoaded(options.replay)) { "Actual session never completed replay load" }
            check(ReplayPanBenchmark.startedAtNanos > 0L && ReplayPanBenchmark.completedAtNanos > 0L)
            check(KoolSystem.getContextOrNull() == null && fontTexture.gpuTexture == null && !gpu.available())
        } catch (error: Throwable) {
            failure = error
        } finally {
            val active = session
            if (active != null) {
                try {
                    active.submitSessionTask {
                        game?.let { logic ->
                            logic.worldFrameRenderedListener = previousListener
                            GameEngine.isHeadlessMode = true
                            logic.isStopped = true; logic.isPaused = true
                            logic.replayEngine.e()
                        }
                    }.get(remainingSeconds(), TimeUnit.SECONDS)
                } catch (closeError: Throwable) { if (failure == null) failure = closeError else failure!!.addSuppressed(closeError) }
                try {
                    active.close() // Main thread only; closes owner, then mailbox/selected ownership.
                    check(!readOwnerThread(active).isAlive) { "Owner thread survived session.close" }
                    stoppedOwner = true
                } catch (closeError: Throwable) { if (failure == null) failure = closeError else failure!!.addSuppressed(closeError) }
            }
            // A failed join is already invalid. Do not race an alive producer by clearing its fonts,
            // Koin or trace state; preserve failure/exit1 instead of manufacturing a valid completion.
            if (stoppedOwner || session == null) {
                try { if (lifecycle.exists()) closeLifecycle() } catch (closeError: Throwable) {
                    if (failure == null) failure = closeError else failure!!.addSuppressed(closeError)
                }
                GameEngine.isHeadlessMode = true
                KoolCanvasFontRegistry.clearInstalledFonts()
                fontTexture.release()
                if (startedKoin) GlobalContext.stopKoin()
            }
        }
        failure?.let { throw it }
        check(stoppedOwner && callbacks.count > 0 && envelopes.count > 0)
        val scenario = options.output.resolve("scenario.ndjson").readLines().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
        val setup = scenario.single { it["kind"]?.jsonPrimitive?.content == "scenario" }
        val window = scenario.single { it["kind"]?.jsonPrimitive?.content == "engine-window" }
        check(setup["initialTick"]?.jsonPrimitive?.int == 0 && setup["initialGameTimeMillis"]?.jsonPrimitive?.int == 0)
        check(setup["replaySpeed"]?.jsonPrimitive?.float == 1f && setup["replayRate"]?.jsonPrimitive?.int == 1)
        check(setup["fogDisplayEnabled"]?.jsonPrimitive?.boolean == false)
        val start = ReplayPanBenchmark.startedAtNanos + options.warmup * SECOND
        val end = start + options.sample * SECOND
        val produced = readProduced(lifecycle)
        val sample = produced.filter { it.nanos in start..end }
        check(sample.size >= 2 && sample.map { it.generation }.distinct().size == 1)
        val observed = (0 until envelopes.count).map { envelopes.generation[it] to envelopes.sequence[it] }.toHashSet()
        val sampleMissing = sample.count { (it.generation to it.sequence) !in observed }
        callbacks.write(options.output.resolve("world-draw.csv"), ReplayPanBenchmark.startedAtNanos, start, end)
        envelopes.write(options.output.resolve("observed-envelopes.csv"), start, end)
        write(options.output.resolve("result.json"), buildJsonObject {
            put("kind", "actual-owner-real-replay-cpu-producer-diagnostic"); put("validDiagnostic", true)
            put("actualOwnerPolicy", checkNotNull(actualPolicy)); put("sessionOwnerStopped", stoppedOwner)
            put("initialScenario", setup); put("engineWindow", window)
            put("sampleStartNanos", start); put("sampleEndNanos", end); put("sampleSeconds", options.sample)
            put("allCompletedFreezeEvents", produced.size); put("sampleCompletedFreezeEvents", sample.size)
            put("sampleCompletedFreezeHz", sample.size.toDouble() / options.sample)
            put("sampleFreezeIntervals", intervalStats(produced, start, end))
            put("worldDraw", callbacks.summary(start, end))
            put("pollCalls", polls); put("emptyPolls", emptyPolls); put("repeatedEnvelopePolls", repeats)
            put("uniqueObservedEnvelopesAllPhases", envelopes.count); put("observedSameGenerationSequenceGapsAllPhases", observedGaps)
            put("sampleProducedButNotObserved", sampleMissing)
            put("sampleProducedObserved", sample.size - sampleMissing)
            put("firstResources", checkNotNull(firstResources)); put("lastResources", checkNotNull(lastResources))
            put("gpuInstallerAvailable", gpu.available()); put("dummyGpuInstallerInstalled", false)
            put("gpuExecution", false); put("presentationMeasurement", false); put("physicalWindow", false)
            put("limitations", JsonArray(listOf(
                "Real automatic owner CPU producer with natural raster fallback; not the GPU-target recording/execution workload used by the visible product.",
                "Lifecycle produced timestamps follow successful freeze and precede mailbox.publish; later offscreen redraw/UI-publication work remains in the owner iteration.",
                "World-draw hook precedes HUD/effects/freeze. Callback intervals and lastSimulationDelta are not the exact outer engineFrame delta argument.",
                "Only lifecycle events count all completed freezes; collector polls/unique observations are not producer counts. Latest mailbox may overwrite unobserved packets.",
                "No GPU, fence, accepted/fresh-present FPS, OS foreground, physical visibility or scanout evidence.",
                "Listener clocks, bounded traces, temporary retains, consumer polling, logging and CPU fallback perturb the workload; compare independent JVM arms with identical instrumentation.",
                "First/last resource closures are inspected, not every graph. No same-bytes pixels or simulation compatibility claim is made."
            ).map(::JsonPrimitive)))
        })
        println("Owner replay CPU diagnostic complete: ${options.output}; sample freezes=${sample.size}; no presentation measurement")
    }

    private class WorldRows {
        val nanos = LongArray(MAX_ROWS); private val end = LongArray(MAX_ROWS)
        private val cpu = LongArray(MAX_ROWS); private val tick = IntArray(MAX_ROWS)
        private val gameTime = IntArray(MAX_ROWS); private val delta = FloatArray(MAX_ROWS)
        private val x = FloatArray(MAX_ROWS); private val y = FloatArray(MAX_ROWS)
        private val zoom = FloatArray(MAX_ROWS); private val target = FloatArray(MAX_ROWS)
        private val thread = LongArray(MAX_ROWS)
        @Volatile var count = 0; private set
        var ownerThread: Thread? = null; private set
        private val clock: ThreadMXBean = ManagementFactory.getThreadMXBean()
        private val cpuSupported = clock.isCurrentThreadCpuTimeSupported.also {
            if (it && !clock.isThreadCpuTimeEnabled) clock.isThreadCpuTimeEnabled = true
        }
        fun record(game: GameLogic) {
            val i = count
            check(i < MAX_ROWS) { "World callback buffer overflow; fail closed" }
            val current = Thread.currentThread()
            if (ownerThread == null) ownerThread = current
            check(ownerThread === current && current.name == "RWX-engine-owner") { "World draw escaped actual owner" }
            check(game.replayEngine.j() && !game.replayEngine.isSeeking && !game.replayEngine.isPlaybackEnded)
            check(game.gameSpeed == 1f && game.replayEngine.v == 1 && !game.replayEngine.isFogDisplayEnabled)
            nanos[i] = System.nanoTime(); cpu[i] = if (cpuSupported) clock.currentThreadCpuTime else -1L
            check(!cpuSupported || cpu[i] >= 0L) { "Supported CPU clock returned an invalid value" }
            thread[i] = current.threadId(); tick[i] = game.currentTick; gameTime[i] = game.gameTimeMillis
            delta[i] = game.lastDelta; x[i] = game.viewpointXSnapped; y[i] = game.viewpointYSnapped
            zoom[i] = game.zoom; target[i] = game.targetZoom; end[i] = System.nanoTime()
            count = i + 1
        }
        fun summary(start: Long, finish: Long) = buildJsonObject {
            val selected = (0 until count).filter { nanos[it] in start..finish }
            check(selected.size >= 2)
            val first = selected.first(); val last = selected.last()
            put("allCallbacks", count); put("sampleCallbacks", selected.size)
            put("firstHookNanos", nanos[first]); put("lastHookNanos", nanos[last])
            put("sameOwnerThread", thread[first] == thread[last]); put("ownerThreadId", thread[first])
            put("sampleTickFirst", tick[first]); put("sampleTickLast", tick[last])
            put("ownerCpuClockSupported", cpuSupported)
            put("ownerCpuTimingValid", cpuSupported && cpu[last] >= cpu[first] && cpu[first] >= 0L)
            put("ownerCpuNanosBetweenFirstLastHooks", if (cpuSupported) cpu[last] - cpu[first] else -1L)
            put("cpuIntervalScope", "Cumulative CPU difference over exactly the first/last hook timestamps, not nominal full sample bounds")
            put("maximumHookBodyNanos", selected.maxOf { end[it] - nanos[it] })
            put("lastSimulationDeltaMeaning", "Most recent simulation-update GameLogic.lastDelta; not outer owner elapsed deltaSeconds")
        }
        fun write(file: File, started: Long, start: Long, finish: Long) = file.bufferedWriter().use { out ->
            out.appendLine("ordinal,worldDrawHookNanos,hookEndNanos,phase,elapsedSinceCameraStartNanos,ownerThreadId,ownerCpuNanos,tick,gameTimeMillis,lastSimulationDeltaGameUnits,cameraX,cameraY,zoom,targetZoom")
            repeat(count) { i -> out.appendLine("${i + 1},${nanos[i]},${end[i]},${phase(nanos[i], start, finish)},${nanos[i] - started},${thread[i]},${cpu[i]},${tick[i]},${gameTime[i]},${delta[i]},${x[i]},${y[i]},${zoom[i]},${target[i]}") }
        }
    }

    private class EnvelopeRows {
        val generation = LongArray(MAX_ROWS); val sequence = LongArray(MAX_ROWS)
        private val before = LongArray(MAX_ROWS); private val after = LongArray(MAX_ROWS)
        private val tick = IntArray(MAX_ROWS); private val revision = LongArray(MAX_ROWS)
        private val resources = IntArray(MAX_ROWS); private val commands = IntArray(MAX_ROWS)
        private val x = FloatArray(MAX_ROWS); private val y = FloatArray(MAX_ROWS); private val zoom = FloatArray(MAX_ROWS)
        var count = 0; private set
        fun record(frame: FrameEnvelope, pollBefore: Long) {
            val i = count
            check(i < MAX_ROWS) { "Envelope observer buffer overflow; fail closed" }
            generation[i] = frame.generation; sequence[i] = frame.sequence; tick[i] = frame.simulationTick
            revision[i] = frame.viewportRevision; before[i] = pollBefore
            resources[i] = frame.resourceLease.resourceCount; commands[i] = frame.frame.commands.size
            frame.camera?.let { camera ->
                check(camera.revision == frame.sequence && camera.generation == frame.generation && camera.viewportRevision == frame.viewportRevision)
                check(camera.viewport == frame.frame.viewport)
                x[i] = camera.x; y[i] = camera.y; zoom[i] = camera.zoom
            }
            after[i] = System.nanoTime(); count = i + 1
        }
        fun write(file: File, start: Long, finish: Long) = file.bufferedWriter().use { out ->
            out.appendLine("pollBeginNanos,observationEndNanos,observationPhase,generation,sequence,simulationTick,viewportRevision,resourceCount,rootCommands,cameraX,cameraY,zoom")
            repeat(count) { i -> out.appendLine("${before[i]},${after[i]},${phase(after[i], start, finish)},${generation[i]},${sequence[i]},${tick[i]},${revision[i]},${resources[i]},${commands[i]},${x[i]},${y[i]},${zoom[i]}") }
        }
    }

    private class GpuAvailability {
        private val type = Class.forName("io.github.rwx.render.canvas.FrozenCanvasGpuResources")
        private val instance = type.getField("INSTANCE").get(null)
        private val getter = type.methods.single { it.name.startsWith("getGpuTargetInstaller$") }
        fun available(): Boolean = getter.invoke(instance) != null
    }

    private fun readOwner(session: KoolDesktopGameSession): Any {
        val field = session.javaClass.getDeclaredField("owner\$delegate").apply { isAccessible = true }
        return checkNotNull((field.get(session) as Lazy<*>).value)
    }

    private fun readOwnerThread(session: KoolDesktopGameSession): Thread = readOwner(session).let { owner ->
        owner.javaClass.getDeclaredField("thread").apply { isAccessible = true }.get(owner) as Thread
    }

    private fun readOwnerPolicy(session: KoolDesktopGameSession): JsonObject {
        val owner = readOwner(session)
        fun bool(name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(owner)
        val thread = readOwnerThread(session)
        return buildJsonObject {
            put("stableFrameDeadlines", bool("stableFrameDeadlines")); put("legacyPacing", bool("legacyPacing"))
            put("legacyShortOwnerPark", bool("legacyShortOwnerPark")); put("readback", "Read actual initialized owner fields only; no mutation")
            put("ownerThreadId", thread.threadId()); put("ownerThreadName", thread.name); put("ownerThreadAliveAtSetup", thread.isAlive)
        }
    }

    private fun inspectResources(envelope: FrameEnvelope): JsonObject {
        val resources = envelope.resourceLease.javaClass.methods.single { it.name.startsWith("resources$") }
            .invoke(envelope.resourceLease) as Map<*, *>
        val kinds = resources.values.filterNotNull().groupingBy { it.javaClass.simpleName }.eachCount()
        check((kinds["Missing"] ?: 0) == 0) { "Missing frozen resources" }
        check((kinds["GpuTarget"] ?: 0) == 0) { "No-installer CPU mode unexpectedly produced GPU targets" }
        return buildJsonObject { put("resourceCount", resources.size); put("kinds", buildJsonObject { kinds.forEach { (name, count) -> put(name, count) } }) }
    }

    private fun closeLifecycle() {
        val type = Class.forName("io.github.rwx.render.canvas.CanvasFrameLifecycleTrace")
        type.getMethod("close").invoke(type.getField("INSTANCE").get(null))
    }

    private data class Produced(val nanos: Long, val generation: Long, val sequence: Long)
    private fun readProduced(file: File): List<Produced> {
        val lines = file.readLines()
        check(lines.first() == "eventNanos,event,generation,sequence,submission,recordedEvents,droppedEvents,capacity,nativeBackend")
        val complete = lines.last().split(',')
        check(complete.size == 9 && complete[1] == "trace-complete" && complete[6] == "0" && complete[8] == "false") {
            "Incomplete/overflowed/native lifecycle trace is invalid"
        }
        val rows = lines.drop(1).dropLast(1).map { line ->
            val cells = line.split(',')
            check(cells.size == 9 && cells[1] == "produced") { "Only CPU produced events are expected" }
            Produced(cells[0].toLong(), cells[2].toLong(), cells[3].toLong())
        }
        check(rows.size == complete[5].toInt() && rows.zipWithNext().all { (a, b) -> a.nanos < b.nanos && a.sequence < b.sequence })
        return rows
    }

    private fun intervalStats(rows: List<Produced>, start: Long, end: Long) = buildJsonObject {
        val values = rows.indices.filter { it > 0 && rows[it].nanos in start..end && rows[it].generation == rows[it - 1].generation }
            .map { (rows[it].nanos - rows[it - 1].nanos) / 1e6 }.sorted()
        check(values.isNotEmpty())
        put("count", values.size); put("meanMs", values.average()); put("p50Ms", values[((values.size - 1) * .5).toInt()])
        put("p99Ms", values[((values.size - 1) * .99).toInt()]); put("maxMs", values.last())
        put("intervalRule", "Intervals ending in fixed sample bounds, including a left-boundary crossing; unfinished right age is excluded")
    }

    private fun phase(now: Long, start: Long, end: Long) = when { now < start -> "warmup-or-bootstrap"; now <= end -> "sample"; else -> "drain" }
    private fun write(file: File, value: JsonObject) = file.writeText(json.encodeToString(JsonObject.serializer(), value) + "\n")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
