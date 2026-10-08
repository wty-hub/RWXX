package io.github.rwx

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.InputController
import com.corrodinggames.rts.gameFramework.SettingsEngine
import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.NativeAssetLoader
import de.fabmax.kool.createContext
import de.fabmax.kool.pipeline.backend.BackendProvider
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.util.FrontendScope
import io.github.rwx.app.AppOptions
import io.github.rwx.app.installApp
import io.github.rwx.di.coreModule
import io.github.rwx.di.desktopModule
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.host.LoadingSceneHost
import io.github.rwx.ui.model.SettingsModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

object KoolDesktopMain : KoinComponent {
    private val logger by lazy { LoggerFactory.getLogger("Desktop") }

    @JvmStatic
    fun main(args: Array<String>) {
        if (HEADLESS_FLAG in args) {
            System.setProperty("java.awt.headless", "true")
            HeadlessMain.main(args)
            return
        }
        configureDesktopLogging()
        LinuxInputMethodBootstrap.install()
        System.setProperty(LWJGL_CONTEXT_API_PROPERTY, LWJGL_NATIVE_CONTEXT_API)
        run(args)
    }

    fun run(args: Array<String> = emptyArray()) = runBlocking {
        configureLwjglMemoryStack()
        configureLegacyDesktopPlatform()
        GlobalContext.startKoin {
            modules(coreModule, desktopModule)
        }
        (getKoin().get<CrashReporter>() as? FileCrashReporter)
            ?.installAsDefaultUncaughtExceptionHandler()
        initializeLegacyPreferences()
        LocaleSettings.initialize()

        configureKoolFramebuffer()
        val options = AppOptions.parseArgs(args, isDesktop = true)
        val renderBackend = selectedRenderBackend()
        if (renderBackend !== RenderBackendVk.Companion) {
            io.github.rwx.render.canvas.KoolCanvasTextureRegistry.configureNativeBgraUploads(false)
        }
        val fullscreenRequested = SettingsEngine.getInstance().slick2dFullScreen
        val swingHost = SwingKoolHost.create(
            fullscreen = fullscreenRequested,
            useOpenGl = renderBackend == RenderBackendGl.Companion,
        )
        val bridge=get<PlatformBridge>()
        bridge.filePickerHost=swingHost
        val context = createContext(createKoolConfig(swingHost, renderBackend))
        context.onRender += { io.github.rwx.render.canvas.CanvasFramePresentation.beginFrame() }
        io.github.rwx.benchmark.ReplayNormalInputProbe.install(context) { get<io.github.rwx.session.GameSession>() }
        swingHost.scheduleVisibilityProbe(context)
        context.onShutdown += { io.github.rwx.render.canvas.CanvasFrameMetrics.close() }
        // Kool only reads its frame-rate limits once, from the config, so keep them in step with the
        // settings screen, which re-resolves the desktop target frame rate every frame.
        context.onRender += { syncDesktopFrameRateLimit(context) }
        context.onRender += {
            swingHost.syncFullscreen(SettingsEngine.getInstance().slick2dFullScreen)
            swingHost.recoverFullscreenSurface(context)
        }
        System.getenv("RWX_DEBUG_AUTO_EXIT_SECONDS")?.toLongOrNull()?.takeIf { it > 0 }?.let { seconds ->
            val exitAt = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds)
            var requested = false
            context.onRender += {
                val completed = io.github.rwx.benchmark.ReplayPanBenchmark.completedAtNanos
                val now = System.nanoTime()
                if (!requested && (now >= exitAt || completed > 0 && now - completed >= 500_000_000L)) {
                    requested = true; swingHost.requestClose()
                }
            }
        }
        val app = KoolApplication(context)
        val loadingScene = LoadingSceneHost.createScene()
        app.ctx.addScene(loadingScene)
        FrontendScope.launch {
            installMsdfFonts()
            val session = installApp(
                context = app.ctx,
                options = options,
                onQuit = swingHost::requestClose,
                configureGameSession = { gameSession ->
                    (gameSession as? io.github.rwx.kool.KoolDesktopGameSession)?.let { independent ->
                        installKoolFrameAvailability(independent::hasUnpresentedFrame)
                        context.onShutdown += { installKoolFrameAvailability(null) }
                    }
                    io.github.rwx.compatibility.GameCompatibilityProbe.install(gameSession)?.let { probe ->
                        context.onShutdown += { probe.close() }
                    }
                },
                configureCanvasHost = { host ->
                    if (renderBackend === RenderBackendVk.Companion) {
                        host.setGpuOffscreenPassesAvailable(true)
                        host.setGpuRetirementSink(io.github.rwx.kool.vulkan.VulkanFrameLifecycle::retainUntilFrameComplete)
                        host.setGpuRetirementPendingCounter(
                            io.github.rwx.kool.vulkan.VulkanFrameLifecycle::pendingRetirements,
                        )
                        io.github.rwx.kool.vulkan.KoolCanvasRealSceneOracle.install(context, host)
                    }
                },
            )
            swingHost.setHostFocusLostHandler(session::onHostFocusLost)
            app.ctx.removeScene(loadingScene)
            while (!session.isFinishLoading()) {
                delay(50L.milliseconds)
            }
        }

        app.ctx.run()
    }

    internal fun createKoolConfig(
        swingHost: SwingKoolHost,
        renderBackend: BackendProvider,
    ): KoolConfigJvm = KoolConfigJvm(
        defaultAssetLoader = NativeAssetLoader(DesktopPlatformStorage.resolveAssetRoot().absolutePath),
        windowTitle = "RWXX",
        windowSize = swingHost.windowSize,
        renderBackend = renderBackend,
        windowSubsystem = swingHost.windowSubsystem,
        numSamples = desktopKoolMsaaSamples(),
        // Off, and this was tested rather than inherited: `asyncSceneUpdate` is Kool's mechanism for
        // overlapping frame N's presentation with frame N+1's scene work, which is exactly where this
        // renderer spends its time (measured ~62% of the render thread's span sits between replays, against
        // ~36% inside them). Kool's own default is true.
        //
        // Enabling it was rejected: the pan+zoom arms stopped completing inside the harness timeout (the
        // static arm was fine), and machine load rose from ~20% to ~70-100%. A scene-update coroutine
        // running beside the game while it mutates its own state is a correctness risk, not merely a
        // slowdown. Overlapping the two remains the right goal, but it needs a design that keeps game state
        // single-threaded.
        asyncSceneUpdate = false,
        // The host provides a different AWT canvas type for Vulkan and OpenGL. Falling back to
        // OpenGL after creating a regular Vulkan canvas cannot produce a working window.
        useOpenGlFallback = false,
        // The in-game "vertical sync" setting used to do nothing on the Kool renderer: the swapchain
        // was created with Kool's own default (FIFO, which pins the game to the display refresh rate
        // and ignores the settings' maximum frame rate). Honour the setting, and when it is off let
        // the frame-rate limiter below pace the loop like the Slick canvas does. Kool reads this when
        // it creates the swapchain, so toggling it in game applies on the next swapchain recreation.
        isVsync = SettingsEngine.getInstance().renderVsync,
        // The GL Swing host schedules frames outside the canvas lock. Kool's limiter would sleep
        // inside that lock and block input as well as the embedded game canvas.
        maxFrameRate = if (swingHost.windowSubsystem.usesExternalFramePacing) 0 else desktopTargetFrameRate(),
        // Same limit whether or not the window has focus: a stale focus flag must not silently
        // throttle a focused game, and the Slick path has no unfocused variant either.
        windowNotFocusedFrameRate = 0,
    )

    /** Applies the settings' target frame rate to Kool's frame-rate limiter. */
    internal fun syncDesktopFrameRateLimit(
        context: Lwjgl3Context,
        targetFrameRate: Int = desktopTargetFrameRate(),
    ) {
        val limit = if ((context.windowSubsystem as? PacedSwingWindowSubsystem)?.usesExternalFramePacing == true) {
            0
        } else {
            targetFrameRate
        }
        if (context.maxFrameRate != limit) {
            context.maxFrameRate = limit
        }
    }

    private fun selectedRenderBackend(): BackendProvider = resolveDesktopRenderBackend(
        System.getProperty(RENDER_BACKEND_PROPERTY) ?: System.getenv(RENDER_BACKEND_ENV),
    )

    /** Windows and macOS default to Vulkan; an explicit backend selection still takes precedence. */
    internal fun resolveDesktopRenderBackend(
        requestedBackend: String?,
        osName: String = System.getProperty("os.name"),
    ): BackendProvider {
        val backend = when (requestedBackend?.lowercase()) {
            null, "" -> if (isMacOs(osName) || osName.startsWith("Windows", ignoreCase = true)) {
                RenderBackendVk.Companion
            } else {
                RenderBackendGl.Companion
            }
            "opengl", "gl" -> RenderBackendGl.Companion
            "vulkan", "vk" -> RenderBackendVk.Companion
            "webgpu", "wgpu" -> throw IllegalArgumentException(
                "Kool WebGPU backend is not available in kool-core-desktop 0.19.0; " +
                        "available JVM backends are Vulkan and OpenGL.",
            )

            else -> throw IllegalArgumentException(
                "Unsupported Kool render backend '$requestedBackend'. Supported values: vulkan, opengl.",
            )
        }
        logger.info("Using Kool render backend: ${backend.displayName}")
        return backend
    }

    private fun isMacOs(osName: String = System.getProperty("os.name")): Boolean =
        osName.startsWith("Mac", ignoreCase = true)

    private suspend fun installMsdfFonts() {
        UiTheme.Fonts.install()
    }

    private fun configureLegacyDesktopPlatform() {
        GameEngine.isMenuBackgroundDisabled = true
        GameEngine.isNonAndroidVersion = true
        GameEngine.isDesktopInitialized = true
        GameEngine.isJavaDesktopVersion = true
        GameEngine.isPCOrIOSVersion = true
        InputController.b = DesktopInputHandler()
        ensureDesktopOpenAlMusicFactory()
    }

    private fun initializeLegacyPreferences() {
        SettingsEngine.getInstance().save()
        val model = SettingsModel()
        get<GameSettingsRepository>().loadInto(model)
        get<GameSettingsRepository>().saveFrom(model)
    }

    /** The Kool canvas is the only surface, so its framebuffer must be opaque. */
    private fun configureKoolFramebuffer() {
        System.setProperty(KOOL_TRANSPARENT_FRAMEBUFFER_PROPERTY, "false")
    }

    private const val KOOL_TRANSPARENT_FRAMEBUFFER_PROPERTY: String = "kool.transparentFramebuffer"
    private const val LWJGL_CONTEXT_API_PROPERTY: String = "org.lwjgl.opengl.contextAPI"
    private const val LWJGL_NATIVE_CONTEXT_API: String = "native"
    private const val RENDER_BACKEND_PROPERTY: String = "rwx.kool.backend"
    private const val RENDER_BACKEND_ENV: String = "RWX_KOOL_BACKEND"
    private const val HEADLESS_FLAG: String = "--headless"
}
