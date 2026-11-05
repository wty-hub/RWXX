package io.github.rwx.kool

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import de.fabmax.kool.KoolSystem
import io.github.rwx.*
import io.github.rwx.compatibility.GameCompatibilityProbe
import io.github.rwx.di.coreModule
import io.github.rwx.headless.HeadlessGraphicsEngine
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.model.SettingsModel
import kotlinx.serialization.json.*
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Opt-in network acceptance entry point, never a JUnit test. The existing original_compatibility.py
 * can launch this class instead of the desktop GUI. It uses the actual desktop session, owner loop,
 * interval context and delta adapter, with original networking/lockstep/checksums unchanged.
 *
 * Required: isolated -Dlaunch.dir and -Duser.home, explicit -Drwx.assetsDir,
 * RWX_COMPAT_PROBE_PORT, RWX_ORIGINAL_INTEGER_MS_GAME_DELTA=1.
 * Optional: --clock-evidence=/new/directory/inside/launch.dir.
 *
 * No window, KoolContext, GPU or audio backend is created. This deliberately skips draw(), so its
 * results cover simulation/network compatibility, never desktop rendering or performance.
 */
object DesktopClockNetworkPeer {
    private val clockField = KoolDesktopGameSession::class.java.getDeclaredField("gameLoopDelta").apply { isAccessible = true }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        try { run(args) }
        catch (error: Throwable) {
            error.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        require(System.getenv("RWX_ORIGINAL_INTEGER_MS_GAME_DELTA") == "1") { "Explicit integer clock policy is required" }
        require(!System.getenv("RWX_COMPAT_PROBE_PORT").isNullOrBlank()) { "RWX_COMPAT_PROBE_PORT is required" }
        require(args.size <= 1 && args.all { it.startsWith("--clock-evidence=") }) { "Use only --clock-evidence=new-directory" }
        val root = DesktopClockAcceptanceSupport.requireIsolation()
        val output = args.singleOrNull()?.substringAfter('=')?.let(::File) ?: root.resolve("clock-evidence")
        require(output.canonicalFile.toPath().startsWith(root.toPath())) { "Clock evidence must be inside isolated launch.dir" }
        output.parentFile.mkdirs()
        Files.createDirectory(output.toPath()) // Preserve earlier evidence; no overwrite/retry.
        val bridge = DesktopClockAcceptanceSupport.boot()
        val session = KoolDesktopGameSession(bridge.storage)
        val closed = AtomicBoolean()
        val writer = output.resolve("samples.ndjson").bufferedWriter()
        fun sample(): JsonObject = session.submitSessionTask {
            val engine = GameEngine.getInstance()
            val clock = clockField.get(session) as DesktopGameLoopDelta
            check(clock.originalIntegerMillis) { "The loaded actual desktop adapter is not using integer milliseconds" }
            check(clock.missingOwnerFallbacks == 0L) { "The actual desktop adapter lost its owner interval" }
            check(KoolSystem.getContextOrNull() == null && GameEngine.isHeadlessMode) { "A rendering context was introduced" }
            buildJsonObject {
                put("kind", "actual-desktop-clock-network-sample")
                put("ownerThread", Thread.currentThread().name); put("ownerMonotonicNs", System.nanoTime())
                put("tick", engine?.currentTick ?: -1); put("mapLoaded", engine?.hasLoadedLevel == true)
                put("networkActive", engine?.networkEngine?.networkGameActive == true)
                put("host", engine?.networkEngine?.isServer == true)
                put("originalIntegerMillis", clock.originalIntegerMillis)
                put("originalOwnerCalls", clock.originalOwnerCalls); put("missingOwnerFallbacks", clock.missingOwnerFallbacks)
                put("explicitCalls", clock.explicitCalls); put("lastSource", clock.source.name)
                put("clientChecksumPasses", engine?.networkEngine?.desyncPassCount ?: 0)
                put("desyncErrors", engine?.networkEngine?.desyncCount ?: 0)
                put("resyncs", engine?.networkEngine?.resyncSendOrReceiveCount ?: 0)
                put("stepRate", engine?.networkEngine?.currentStepRate ?: 0f)
                put("checksumIntervalFrames", engine?.networkEngine?.checksumIntervalFrames ?: 0)
                put("headless", true); put("koolContextPresent", false); put("audioBackend", "NoopPlatformAudio")
            }
        }.get(30, TimeUnit.SECONDS)
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching {
                val last = sample()
                output.resolve("final-clock.json").writeText(JsonObject(last + mapOf(
                    "actualOwnerCallsObserved" to JsonPrimitive(last["originalOwnerCalls"]!!.jsonPrimitive.long > 0),
                    "networkAcceptance" to JsonPrimitive("Use the runner's full-duration, both-direction original-peer result; this clock record alone does not establish interoperability")
                )).toString() + "\n")
            }.onFailure { output.resolve("shutdown-error.txt").writeText(it.stackTraceToString()) }
            runCatching { writer.close() }
            runCatching { session.close() }
        }
        Runtime.getRuntime().addShutdownHook(Thread(::close, "RWX-clock-acceptance-shutdown"))
        try {
            val engine = session.preload(KoolCanvasViewport(1280, 720))
            check(engine is com.corrodinggames.rts.game.GameLogic)
            checkNotNull(GameCompatibilityProbe.install(session)).use {
                output.resolve("manifest.json").writeText(buildJsonObject {
                    put("kind", "actual-desktop-clock-original-peer-acceptance")
                    put("explicitClockEnvironment", "1"); put("actualSession", session.javaClass.name)
                    put("actualEngine", engine.javaClass.name); put("engineCodeSource", engine.javaClass.protectionDomain.codeSource.location.toString())
                    put("headless", true); put("audioBackend", "NoopPlatformAudio")
                    put("scope", "Actual desktop owner+adapter and unchanged simulation/socket protocol. Rendering skipped; no window/GPU/performance evidence.")
                }.toString() + "\n")
                while (!closed.get()) {
                    writer.append(sample().toString()).append('\n'); writer.flush()
                    Thread.sleep(1000)
                }
            }
        } finally { close() }
    }
}

/** Shared bootstrap for opt-in clock acceptance mains only. */
internal object DesktopClockAcceptanceSupport {
    fun requireIsolation(): File {
        val root = File(requireNotNull(System.getProperty("launch.dir")) { "Isolated -Dlaunch.dir is required" }).canonicalFile
        require(root.isAbsolute && root != File(".").canonicalFile && root != File("/").canonicalFile)
        val home = File(System.getProperty("user.home")).canonicalFile
        require(home != root && home.toPath().startsWith(root.toPath())) { "-Duser.home must be a child of isolated launch.dir" }
        val assets = File(requireNotNull(System.getProperty("rwx.assetsDir")) { "Explicit real -Drwx.assetsDir is required" }).canonicalFile
        require(assets.isDirectory && DesktopPlatformStorage.resolveAssetRoot().canonicalFile == assets)
        require(!root.toPath().startsWith(assets.toPath()) && !assets.toPath().startsWith(root.toPath())) { "Assets and writable runtime must be separate" }
        return root
    }

    fun boot(): DesktopPlatformBridge {
        check(KoolSystem.getContextOrNull() == null)
        configureDesktopLogging()
        HeadlessMain::class.java.getDeclaredMethod("configureHeadlessPlatform").apply { isAccessible = true }.invoke(HeadlessMain)
        val bridge = DesktopPlatformBridge(audio = NoopPlatformAudio)
        val bindings = module {
            single<PlatformBridge> { bridge }; single<PlatformStorage> { bridge.storage }
            single<PreferenceStorage> { bridge.preferenceStorage }; single<AppMetadata> { bridge.appMetadata }
            single<AppLogger> { bridge.logger }; single<CrashReporter> { bridge.crashReporter }
            single<GraphicsEngine> { HeadlessGraphicsEngine(bridge.storage) }
        }
        GlobalContext.startKoin { modules(coreModule, bindings) }
        bridge.storage.createDirectories()
        SettingsEngine.getInstance().save()
        val repository = GlobalContext.get().get<GameSettingsRepository>()
        SettingsModel().also { repository.loadInto(it); repository.saveFrom(it) }
        SettingsEngine.getInstance().apply {
            enableSounds = false; masterVolume = 0f; gameVolume = 0f; interfaceVolume = 0f; musicVolume = 0f
            save()
        }
        LocaleSettings.initialize()
        return bridge
    }
}
