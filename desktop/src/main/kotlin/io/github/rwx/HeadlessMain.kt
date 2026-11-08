package io.github.rwx

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.InputController
import com.corrodinggames.rts.gameFramework.MusicManager
import com.corrodinggames.rts.gameFramework.NullMusicFactory
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.ui.Message
import com.corrodinggames.rts.gameFramework.ui.MessageManager
import io.github.rwx.diagnostics.GameStateTrace
import io.github.rwx.benchmark.VanillaBattleBenchmark
import io.github.rwx.benchmark.ReplaySeekAcceptanceHarness
import io.github.rwx.benchmark.DriverParityHarness
import io.github.rwx.benchmark.VanillaUnitSmokeHarness
import io.github.rwx.benchmark.OwnerSessionAcceptanceHarness
import io.github.rwx.diagnostics.SimulationCompatibilityTrace
import io.github.rwx.di.coreModule
import io.github.rwx.headless.HeadlessGameSession
import io.github.rwx.headless.HeadlessGraphicsEngine
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.model.ReplaySelectViewModel
import io.github.rwx.ui.model.SettingsModel
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs a replay or a single-player map on the existing game loop without a window or OpenGL.
 *
 * ```
 * --replay=<name> | --map=<path>
 * --ticks=N          required with --map; optional cap for a replay
 * --checksum=<file>  per-tick checksum log
 * ```
 */
object HeadlessMain {
    private const val REPLAY_PREFIX = "--replay="
    private const val MAP_PREFIX = "--map="
    private const val TICKS_PREFIX = "--ticks="
    private const val CHECKSUM_PREFIX = "--checksum="
    private const val PARITY_PREFIX = "--driver-parity="
    private const val HEADLESS_FLAG = "--headless"
    private const val STEP_CROSS_EPSILON = 0.001f
    private const val FRAME_MILLIS = 16
    private const val EXIT_OK = 0
    private const val EXIT_FAILURE = 1
    private const val EXIT_REPLAY_READ = 2
    private const val UNEXPECTED_REPLAY_END = "Replay ended (unexpected)"

    private val replayStoppedField = ReplayEngine::class.java.getDeclaredField("isStopped").apply {
        isAccessible = true
    }
    private val messagesField = MessageManager::class.java.getDeclaredField("messages").apply {
        isAccessible = true
    }
    private val messageTextField = Message::class.java.getDeclaredField("text").apply {
        isAccessible = true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val code = try {
            run(parseArgs(args))
        } catch (error: Throwable) {
            System.err.println("Headless run failed: ${error.message}")
            error.printStackTrace(System.err)
            EXIT_FAILURE
        }
        GameStateTrace.close()
        SimulationCompatibilityTrace.close()
        exitProcess(code)
    }

    internal fun parseArgs(args: Array<String>): HeadlessOptions {
        var replay: String? = null
        var map: String? = null
        var ticks: Int? = null
        var checksum: String? = null
        var parity: String? = null
        args.forEach { arg ->
            when {
                arg == HEADLESS_FLAG -> Unit
                arg.startsWith(REPLAY_PREFIX) -> {
                    replay = arg.removePrefix(REPLAY_PREFIX).trim().also {
                        require(it.isNotEmpty()) { "Replay name must not be empty" }
                    }
                }
                arg.startsWith(MAP_PREFIX) -> {
                    map = arg.removePrefix(MAP_PREFIX).trim().also {
                        require(it.isNotEmpty()) { "Map path must not be empty" }
                    }
                }
                arg.startsWith(TICKS_PREFIX) -> {
                    val raw = arg.removePrefix(TICKS_PREFIX).trim()
                    ticks = raw.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("Tick count must be a positive integer: $raw")
                }
                arg.startsWith(CHECKSUM_PREFIX) -> {
                    checksum = arg.removePrefix(CHECKSUM_PREFIX).trim().also {
                        require(it.isNotEmpty()) { "Checksum path must not be empty" }
                    }
                }
                arg.startsWith(PARITY_PREFIX) -> parity = arg.removePrefix(PARITY_PREFIX).trim().also {
                    require(it.isNotEmpty()) { "Parity report path must not be empty" }
                }
                else -> throw IllegalArgumentException("Unsupported headless argument: $arg")
            }
        }
        if ((replay == null) == (map == null)) {
            throw IllegalArgumentException("Pass exactly one of --replay= or --map=")
        }
        if (map != null && ticks == null) {
            throw IllegalArgumentException("--ticks=N is required with --map=")
        }
        require(parity == null || map != null) { "--driver-parity requires a map, not replay playback" }
        return HeadlessOptions(
            replay = replay,
            map = map,
            ticks = ticks,
            checksum = checksum?.let(::File),
            parity = parity?.let(::File),
        )
    }

    private fun run(options: HeadlessOptions): Int {
        options.checksum?.let(GameStateTrace::start)
        configureDesktopLogging()
        configureHeadlessPlatform()
        GlobalContext.startKoin {
            modules(coreModule, headlessModule)
        }

        val koin = GlobalContext.get()
        (koin.get<CrashReporter>() as? FileCrashReporter)
            ?.installAsDefaultUncaughtExceptionHandler()
        val storage = koin.get<PlatformStorage>()
        storage.createDirectories()
        SettingsEngine.getInstance().save()
        val settings = SettingsModel()
        koin.get<GameSettingsRepository>().loadInto(settings)
        koin.get<GameSettingsRepository>().saveFrom(settings)
        LocaleSettings.initialize()
        System.getenv("RWX_OWNER_SESSION_STRESS_OUTPUT")?.takeIf(String::isNotBlank)?.let { path ->
            check(options.map != null) { "Owner session stress requires --map" }
            return if (OwnerSessionAcceptanceHarness.run(storage, options.map, File(path))) EXIT_OK else EXIT_FAILURE
        }
        val graphics = koin.get<GraphicsEngine>()
        GameEngine.graphicsEngine = graphics

        val session = HeadlessGameSession()
        val engine = session.boot(graphics)
        val replayName = options.replay
        if (replayName != null) {
            val resolved = resolveReplayName(replayName)
            session.openReplay(resolved)
            System.getenv("RWX_REPLAY_SEEK_OUTPUT")?.takeIf(String::isNotBlank)?.let { path ->
                return if (ReplaySeekAcceptanceHarness.run(session, engine, resolved, File(path))) EXIT_OK else EXIT_FAILURE
            }
        } else {
            session.openMap(options.map!!)
            System.getenv("RWX_VANILLA_SMOKE_OUTPUT")?.takeIf(String::isNotBlank)?.let {
                val matched = VanillaUnitSmokeHarness.run(engine, File(it))
                println("Vanilla constructor/draw smoke: $matched; $it")
                return if (matched) EXIT_OK else EXIT_FAILURE
            }
            VanillaBattleBenchmark.onFrame(engine)
        }

        options.parity?.let { output ->
            val snapshot = checkNotNull(session.captureMapSnapshot()) { "Unable to capture parity initial state" }
            // The legacy save loader deliberately replaces this wall-clock connection timestamp.
            // Restore the fixture's initial value in both drivers rather than ignoring save bytes.
            val initialPingTimes = (0 until PlayerTeam.TEAM_NEUTRAL).mapNotNull { id -> PlayerTeam.k(id)?.let { id to it.teamLastPingTime } }.toMap()
            val initialGlobalSeed = engine.globalSeed
            val result = DriverParityHarness.compare(DriverParityHarness.defaultTape(options.ticks!!),
                { session.restoreSnapshot(snapshot).also {
                    initialPingTimes.forEach { (id, timestamp) -> PlayerTeam.k(id)?.teamLastPingTime = timestamp }
                    // Single-player map initialization chooses a new globalSeed before the
                    // save payload restores room settings. Restore this fixture sideband too.
                    it.globalSeed = initialGlobalSeed
                } }, output)
            println("Driver parity: ${result.json()}")
            var replayMatched = true
            if (System.getenv("RWX_PARITY_RECORD_REPLAY") == "1") {
                session.restoreSnapshot(snapshot)
                initialPingTimes.forEach { (id, timestamp) -> PlayerTeam.k(id)?.teamLastPingTime = timestamp }
                engine.globalSeed = initialGlobalSeed
                val replayFileName = "rwx-driver-parity-${System.nanoTime()}.replay"
                engine.replayEngine.d(replayFileName)
                check(engine.replayEngine.k()) { "Unable to record parity replay" }
                try {
                    DriverParityHarness.advanceTape(engine, DriverParityHarness.replayRecordingTape(maxOf(240, options.ticks * 3)))
                } finally { engine.replayEngine.e() }
                val replayResult = DriverParityHarness.compare(DriverParityHarness.replayPlaybackTape(options.ticks), {
                    session.openReplay(replayFileName)
                    initialPingTimes.forEach { (id, timestamp) -> PlayerTeam.k(id)?.teamLastPingTime = timestamp }
                    engine.gameSpeed = 1f
                    engine
                }, File(output.path + ".replay.json"), "recorded-replay-step-negotiation-and-pause")
                println("Replay driver parity: ${replayResult.json()}")
                replayMatched = replayResult.matched
                engine.replayEngine.e()
            }
            return if (result.matched && replayMatched) EXIT_OK else EXIT_FAILURE
        }

        val code = advance(engine, options.ticks, replay = replayName != null)
        val label = replayName?.let { "replay '$it'" } ?: "map '${options.map}'"
        println("Headless $label finished at tick ${engine.currentTick}")
        options.checksum?.let { println("Checksum: ${it.absolutePath}") }
        return code
    }

    private fun resolveReplayName(query: String): String {
        val replays = ReplaySelectViewModel().items()
        val replay = replays.firstOrNull { it.fileName == query }
            ?: replays.firstOrNull { it.fileName.contains(query) }
            ?: throw IllegalArgumentException("Replay not found: $query")
        return replay.replayName
    }

    private fun advance(engine: GameEngine, ticks: Int?, replay: Boolean): Int {
        while (true) {
            if (ticks != null && engine.currentTick >= ticks) {
                return EXIT_OK
            }
            if (replay && replayFinished(engine)) {
                return if (unexpectedReplayEnd(engine)) EXIT_REPLAY_READ else EXIT_OK
            }
            val stepRate = engine.networkEngine.getCurrentStepRate().coerceAtLeast(0.1f)
            engine.gameLoop(stepRate + STEP_CROSS_EPSILON, FRAME_MILLIS)
        }
    }

    private fun replayFinished(engine: GameEngine): Boolean {
        val replay = engine.replayEngine ?: return false
        return replayStoppedField.getBoolean(replay) || !replay.i()
    }

    private fun unexpectedReplayEnd(engine: GameEngine): Boolean {
        val manager = engine.gameUI?.messageManager ?: return false
        val messages = messagesField.get(manager) as? Iterable<*> ?: return false
        return messages.any { message -> messageTextField.get(message) == UNEXPECTED_REPLAY_END }
    }

    internal fun configureHeadlessPlatform() {
        GameEngine.isMenuBackgroundDisabled = true
        GameEngine.isNonAndroidVersion = true
        GameEngine.isDesktopInitialized = true
        GameEngine.isJavaDesktopVersion = true
        GameEngine.isPCOrIOSVersion = true
        GameEngine.isHeadlessMode = true
        GameEngine.externalGameLoopDriver = true
        InputController.b = DesktopInputHandler()
        MusicManager.musicFactory = NullMusicFactory()
    }
}

internal data class HeadlessOptions(
    val replay: String?,
    val map: String?,
    val ticks: Int?,
    val checksum: File?,
    val parity: File? = null,
)

internal val headlessModule = module {
    single<PlatformBridge> { DesktopPlatformBridge(audio = NoopPlatformAudio) }
    single<PlatformStorage> { get<PlatformBridge>().storage }
    single<PreferenceStorage> { get<PlatformBridge>().preferenceStorage }
    single<AppMetadata> { get<PlatformBridge>().appMetadata }
    single<AppLogger> { get<PlatformBridge>().logger }
    single<CrashReporter> { get<PlatformBridge>().crashReporter }
    single<GraphicsEngine> { HeadlessGraphicsEngine(get()) }
}
