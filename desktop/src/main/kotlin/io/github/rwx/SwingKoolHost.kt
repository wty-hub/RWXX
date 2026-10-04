package io.github.rwx

import com.corrodinggames.rts.gameFramework.SettingsEngine
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.platform.swing.KoolGlCanvas
import de.fabmax.kool.platform.swing.SwingWindowSubsystem
import de.fabmax.kool.util.FrontendScope
import io.github.rwx.KoolDesktopMain.getKoin
import io.github.rwx.app.launchOnIO
import io.github.rwx.ui.component.PlatformTextInputBridge
import io.github.rwx.ui.emoji.EmojiRasterizerBridge
import kotlinx.coroutines.launch
import org.lwjgl.opengl.awt.GLData
import java.awt.*
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.system.exitProcess

/**
 * The single desktop window. Kool owns the only GL surface: the game and the UI are both drawn
 * through the Kool canvas command stream, so there is no second AWT OpenGL canvas and no overlay
 * window to keep in sync.
 */
class SwingKoolHost private constructor(
    val koolCanvas: Canvas,
    val windowSubsystem: PacedSwingWindowSubsystem,
    private val startupFullscreen: Boolean,
) : PlatformFilePickerHost {
    private val panel = JPanel(null)
    private val frame = JFrame(windowTitle())
    private var macFullscreen: MacNativeFullscreen? = null
    @Volatile private var requestedFullscreen: Boolean? = null
    private val fullscreenSurfaceRecovery = AtomicBoolean(false)
    // The first Metal surface is also attached after AWT's initial layout has completed.
    private var fullscreenLayoutRefreshPending = isMacHost()
    private var fullscreenShortcutDown = false
    private val textInputController: DesktopTextInputController
    private val emojiRasterizer = DesktopEmojiRasterizer()
    val windowSize: Vec2i
        get() {
            val width = panel.width.takeIf { it > 0 } ?: panel.preferredSize.width
            val height = panel.height.takeIf { it > 0 } ?: panel.preferredSize.height
            return Vec2i(width.coerceAtLeast(1), height.coerceAtLeast(1))
        }
    private var initialContentFitPending = true
    private var applyingInitialContentFit = false
    private val closeRequested = AtomicBoolean(false)
    @Volatile
    private var hostFocusLostHandler: (() -> Unit)? = null
    private var pointerCursor: Cursor? = null
    private val keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val koolTypedControlCharacterFilter = KeyEventDispatcher { event ->
        if (macFullscreen != null && event.component?.let { SwingUtilities.getWindowAncestor(it) } === frame &&
            event.keyCode == KeyEvent.VK_F) {
            if (event.id == KeyEvent.KEY_RELEASED && fullscreenShortcutDown) {
                fullscreenShortcutDown = false
                true
            } else if (event.id == KeyEvent.KEY_PRESSED && event.isControlDown && event.isMetaDown) {
                if (!fullscreenShortcutDown) {
                    val settings = SettingsEngine.getInstance()
                    settings.slick2dFullScreen = !settings.slick2dFullScreen
                    settings.save()
                    syncFullscreen(settings.slick2dFullScreen)
                }
                fullscreenShortcutDown = true
                true
            } else false
        } else event.source === koolCanvas && shouldSuppressKoolTypedCharacter(event)
    }

    init {
        val preferredWindowSize = initialWindowSize(startupFullscreen && !isMacHost())
        panel.background = Color.BLACK
        panel.preferredSize = Dimension(preferredWindowSize.x, preferredWindowSize.y)
        panel.minimumSize = Dimension(800, 600)
        panel.isOpaque = true
        koolCanvas.name = KOOL_CARD
        koolCanvas.background = Color.BLACK
        koolCanvas.isFocusable = true
        koolCanvas.focusTraversalKeysEnabled = false
        koolCanvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                focusVisibleCanvas()
            }
        })
        koolCanvas.ignoreRepaint = true
        keyboardFocusManager.addKeyEventDispatcher(koolTypedControlCharacterFilter)
        textInputController = DesktopTextInputController(
            // The hidden IME target must live in the same native window as the focused canvas.
            editorHost = frame.layeredPane,
            activateEditorWindow = {},
            setEditorHasFocus = { hasFocus ->
                koolCanvas.isFocusable = !hasFocus
            },
            restoreFocus = {
                restoreCanvasKeyboard()
                focusVisibleCanvas()
            },
            returnKeysToCanvas = {
                restoreCanvasKeyboard()
                focusVisibleCanvas()
            },
        )
        PlatformTextInputBridge.install(textInputController)
        EmojiRasterizerBridge.install(emojiRasterizer)
        panel.add(koolCanvas, KOOL_CARD)
        panel.setComponentZOrder(koolCanvas, 0)
        panel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                resizeCanvases()
                fitInitialContentSize()
            }
        })

        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.isUndecorated = (startupFullscreen && !isMacHost()) || System.getenv("RWX_BENCHMARK_UNITS") != null
        frame.background = Color.BLACK
        frame.contentPane.background = Color.BLACK
        frame.rootPane.background = Color.BLACK
        frame.layout = BorderLayout()
        frame.minimumSize = Dimension(800, 600)
        frame.add(panel, BorderLayout.CENTER)
        if (isMacHost()) {
            macFullscreen = MacNativeFullscreen(frame,
                onTransitionStarted = { fullscreen ->
                    logger.info { "macOS native fullscreen transition started: fullscreen=$fullscreen" }
                    requestedFullscreen = fullscreen
                    val settings = SettingsEngine.getInstance()
                    settings.slick2dFullScreen = fullscreen
                    settings.save()
                },
                onTransitionCompleted = {
                    logger.info { "macOS native fullscreen transition completed; restoring Vulkan surface" }
                    frame.validate()
                    resizeCanvases()
                    logger.info { "macOS fullscreen layout: frame=${frame.width}x${frame.height} " +
                        "panel=${panel.width}x${panel.height} canvas=${koolCanvas.width}x${koolCanvas.height}" }
                    fullscreenSurfaceRecovery.set(true)
                    focusVisibleCanvas()
                    SwingUtilities.invokeLater { focusVisibleCanvas() }
                },
            )
        }
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                requestClose()
            }
        })
        frame.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent) {
                fullscreenShortcutDown = false
                hostFocusLostHandler?.invoke()
            }

            override fun windowGainedFocus(e: WindowEvent) {
                focusVisibleCanvas()
            }
        })
        frame.pack()
        if (startupFullscreen && macFullscreen == null) {
            applyStartupFullscreen()
        } else {
            frame.setLocationRelativeTo(null)
        }
        frame.isVisible = true
        resizeCanvases()
        fitInitialContentSize()
        val configuration = frame.graphicsConfiguration
        logger.info { "RWX window: bounds=${frame.bounds} canvas=${koolCanvas.width}x${koolCanvas.height} " +
            "displayBounds=${configuration.bounds} scale=${configuration.defaultTransform.scaleX}x${configuration.defaultTransform.scaleY} " +
            "screenInsets=${Toolkit.getDefaultToolkit().getScreenInsets(configuration)}" }
        showKool()
    }

    /** The game world is drawn into the Kool canvas, which is the only presented surface. */
    fun showGame() {
        runOnEdt {
            resizeCanvases()
            koolCanvas.isVisible = true
            if (!PlatformTextInputBridge.isEditing()) {
                koolCanvas.requestFocusInWindow()
            }
        }
    }

    /** Menus are drawn through the same Kool canvas; nothing has to be swapped in or out. */
    fun showKool() {
        runOnEdt {
            resizeCanvases()
            koolCanvas.isVisible = true
            setInGamePointerCursorActive(false)
            koolCanvas.requestFocusInWindow()
        }
    }

    fun requestClose() {
        // Provided Swing canvases have a no-op KoolWindow.close(), so desktop exit closes the subsystem directly.
        if (!closeRequested.compareAndSet(false, true)) return
        hostFocusLostHandler = null
        val bridge=getKoin().get<PlatformBridge>()
        bridge.filePickerHost=null
        keyboardFocusManager.removeKeyEventDispatcher(koolTypedControlCharacterFilter)
        runOnEdt { macFullscreen?.close() }
        PlatformTextInputBridge.uninstall(textInputController)
        EmojiRasterizerBridge.uninstall(emojiRasterizer)
        textInputController.dispose()
        launchOnIO("shutdown") {
            windowSubsystem.close {
                SwingUtilities.invokeLater {
                    frame.dispose()
                    exitProcess(0)
                }
            }
        }
    }

    fun setHostFocusLostHandler(handler: (() -> Unit)?) {
        hostFocusLostHandler = handler
    }

    /** Called by the render owner; native Cocoa requests and layout belong to the EDT. */
    fun syncFullscreen(fullscreen: Boolean) {
        if (macFullscreen == null || requestedFullscreen == fullscreen) return
        requestedFullscreen = fullscreen
        runOnEdt {
            if (!closeRequested.get()) {
                macFullscreen?.request(checkNotNull(requestedFullscreen))
            }
        }
    }

    /** Native fullscreen can replace the JAWT layer even if the canvas dimensions stay equal. */
    fun recoverFullscreenSurface(context: de.fabmax.kool.platform.Lwjgl3Context) {
        val backend = context.backend as? de.fabmax.kool.pipeline.backend.vk.RenderBackendVk ?: return
        if (fullscreenLayoutRefreshPending) {
            fullscreenLayoutRefreshPending = false
            if (!closeRequested.get()) {
                MacMetalLayerLayout.refresh(koolCanvas)
                logger.info { "macOS fullscreen native layer bounds refreshed: ${koolCanvas.width}x${koolCanvas.height}" }
            }
        }
        if (fullscreenSurfaceRecovery.getAndSet(false)) {
            backend.recreateSurface()
            fullscreenLayoutRefreshPending = true
        }
    }

    /** Opt-in acceptance probe. The provided canvas controls the unmanaged Vulkan loop's visibility. */
    internal fun scheduleVisibilityProbe(context: de.fabmax.kool.platform.Lwjgl3Context) {
        val hideAfter = System.getenv("RWX_DEBUG_HIDE_AFTER_SECONDS")?.toLongOrNull()?.takeIf { it > 0 }
            ?.coerceAtMost(3600) ?: return
        val hideDuration = System.getenv("RWX_DEBUG_HIDE_SECONDS")?.toLongOrNull()?.coerceIn(1, 60) ?: 10
        val timer = java.util.Timer("RWX-window-visibility-probe", true)
        context.onShutdown += { timer.cancel() }
        val windowClass = context.window.javaClass.name
        val subsystemClass = context.windowSubsystem.javaClass.name
        logger.info { "RWX visibility probe scheduled: window=$windowClass subsystem=$subsystemClass " +
            "hideAfterSeconds=$hideAfter hiddenSeconds=$hideDuration at ${System.nanoTime()}" }
        var previousCanvasVisible = true
        var previousFrameVisible = true
        io.github.rwx.debug.WindowVisibilityProbeSchedule(
            after = { delayMillis, task ->
                timer.schedule(object : java.util.TimerTask() { override fun run() = task() }, delayMillis)
            },
            onWindowOwner = { task -> SwingUtilities.invokeLater(task) },
            setVisible = { visible ->
                check(SwingUtilities.isEventDispatchThread())
                if (!visible) {
                    previousCanvasVisible = koolCanvas.isVisible
                    previousFrameVisible = frame.isVisible
                    // Hiding only JFrame leaves CanvasWrapper.flags.isVisible true. Its component
                    // listener must see the actual render canvas hide before the Vulkan loop stops.
                    koolCanvas.isVisible = false
                    frame.isVisible = false
                } else {
                    frame.isVisible = previousFrameVisible
                    koolCanvas.isVisible = previousCanvasVisible
                    focusVisibleCanvas()
                }
                logger.info { "RWX visibility probe: visible=$visible window=$windowClass subsystem=$subsystemClass " +
                    "canvasVisible=${koolCanvas.isVisible} canvasShowing=${koolCanvas.isShowing} " +
                    "frameVisible=${frame.isVisible} at ${System.nanoTime()}" }
            },
            isClosed = closeRequested::get,
            cancel = timer::cancel,
            requested = { visible -> logger.info { "RWX visibility probe requested: visible=$visible at ${System.nanoTime()}" } },
        ).start(hideAfter * 1000, hideDuration * 1000)
    }

    override fun openFilePicker(
        title: String,
        allowedExtensions: Set<String>,
        allowDirectories: Boolean,
        onResult: (PlatformFileSelection?) -> Unit,
    ) {
        runOnEdt {
            val selection = runCatching {
                val extensions = allowedExtensions
                    .map { it.trim().removePrefix(".") }
                    .filter { it.isNotEmpty() }
                    .sorted()
                    .toTypedArray()
                val chooser = JFileChooser().apply {
                    dialogTitle = title
                    fileSelectionMode = if (allowDirectories) {
                        JFileChooser.FILES_AND_DIRECTORIES
                    } else {
                        JFileChooser.FILES_ONLY
                    }
                    isMultiSelectionEnabled = false
                    if (extensions.isNotEmpty()) {
                        val extensionList = extensions.joinToString { ".$it" }
                        fileFilter = FileNameExtensionFilter("Supported files ($extensionList)", *extensions)
                        isAcceptAllFileFilterUsed = false
                    }
                }
                chooser.takeIf { it.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION }
                    ?.selectedFile
                    ?.absoluteFile
                    ?.let { file -> PlatformFileSelection(path = file.path) }
            }.getOrNull()
            FrontendScope.launch { onResult(selection) }
        }
    }

    private fun resizeCanvases() {
        val width = panel.width.coerceAtLeast(1)
        val height = panel.height.coerceAtLeast(1)
        panel.revalidate()
        panel.doLayout()
        koolCanvas.setBounds(0, 0, width, height)
    }

    private fun restoreCanvasKeyboard() {
        koolCanvas.isFocusable = true
    }

    private fun focusVisibleCanvas() {
        if (PlatformTextInputBridge.isEditing() && textInputController.ownsCaret) {
            return
        }
        if (!isApplicationActive()) return
        koolCanvas.requestFocusInWindow()
    }

    private fun isApplicationActive(): Boolean = frame.isActive

    private fun fitInitialContentSize() {
        if (startupFullscreen || macFullscreen?.isFullscreenOrTransitioning == true) return
        if (!initialContentFitPending || applyingInitialContentFit || !frame.isShowing) return
        val insets = frame.insets
        if (!frame.isUndecorated && insets.top == 0 && insets.left == 0 && insets.bottom == 0 && insets.right == 0) return
        val configuration = frame.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val targetBounds = initialWindowedFrameBounds(panel.preferredSize, insets,
            configuration.bounds, Toolkit.getDefaultToolkit().getScreenInsets(configuration))
        if (frame.bounds == targetBounds) {
            initialContentFitPending = false
            return
        }

        applyingInitialContentFit = true
        try {
            val contentWidth = (targetBounds.width - insets.left - insets.right).coerceAtLeast(1)
            val contentHeight = (targetBounds.height - insets.top - insets.bottom).coerceAtLeast(1)
            panel.preferredSize = Dimension(contentWidth, contentHeight)
            panel.minimumSize = Dimension(minOf(800, contentWidth), minOf(600, contentHeight))
            frame.minimumSize = Dimension(minOf(800, targetBounds.width), minOf(600, targetBounds.height))
            frame.bounds = targetBounds
            frame.validate()
            resizeCanvases()
            // Initial layout is now fitted. Later user resizes keep the user's chosen dimensions.
            initialContentFitPending = false
        } finally {
            applyingInitialContentFit = false
        }
    }

    fun setInGamePointerCursorActive(active: Boolean) {
        runOnEdt {
            val cursorCanvas = koolCanvas as? PointerCursorCanvas
            cursorCanvas?.inGamePointerCursorActive = active
            if (active) {
                applyPointerCursor(koolCanvas)
            } else {
                koolCanvas.cursor = Cursor.getDefaultCursor()
            }
        }
    }

    private fun applyPointerCursor(vararg canvases: Canvas) {
        val pointerCursor = pointerCursor ?: createPointerCursor()?.also { pointerCursor = it } ?: return
        canvases.forEach { canvas ->
            (canvas as? PointerCursorCanvas)?.pointerCursor = pointerCursor
            canvas.cursor = pointerCursor
        }
    }

    private fun createPointerCursor(): Cursor? =
        runCatching {
            val imageFile = DesktopPlatformStorage.resolveAssetRoot().resolve("drawable/pointer.png")
            val image = ImageIO.read(imageFile) ?: return null
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "rwx-pointer")
        }.getOrNull()

    private fun applyStartupFullscreen() {
        val bounds = fullscreenBounds()
        frame.extendedState = JFrame.MAXIMIZED_BOTH
        frame.bounds = bounds
        panel.preferredSize = Dimension(bounds.width, bounds.height)
        panel.setSize(bounds.width, bounds.height)
    }

    companion object {
        val DEFAULT_WINDOW_SIZE = Vec2i(1280, 720)
        private const val KOOL_CARD = "kool"

        fun create(
            fullscreen: Boolean,
            useOpenGl: Boolean,
        ): SwingKoolHost {
            if (!SwingUtilities.isEventDispatchThread()) {
                lateinit var host: SwingKoolHost
                SwingUtilities.invokeAndWait {
                    host = create(fullscreen, useOpenGl)
                }
                return host
            }
            System.setProperty("org.lwjgl.opengl.contextAPI", "native")
            val koolCanvas = if (useOpenGl) {
                KoolGlCanvas(
                    GLData().apply {
                        // The Kool canvas is the only surface: it must never be translucent over
                        // another canvas, and the swapchain owns the window's alpha.
                        alphaSize = 0
                        depthSize = 24
                        stencilSize = 8
                        samples = 4
                        // A vsync swap would block while holding the AWT lock; frames are paced
                        // by PacedSwingWindowSubsystem instead.
                        swapInterval = 0
                    },
                )
            } else {
                PointerCursorCanvas()
            }
            val subsystem = PacedSwingWindowSubsystem(
                SwingWindowSubsystem(
                    providedCanvas = koolCanvas,
                    makeFocusable = true,
                ),
            )
            return SwingKoolHost(
                koolCanvas = koolCanvas,
                windowSubsystem = subsystem,
                startupFullscreen = fullscreen,
            )
        }

        private fun windowTitle(): String =
            System.getProperty("rwx.windowTitle")
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv("RWX_WINDOW_TITLE")?.takeIf { it.isNotBlank() }
                ?: "RWX Game"

        private fun isMacHost(): Boolean = System.getProperty("os.name").startsWith("mac", ignoreCase = true)

        private fun runOnEdt(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) {
                action()
            } else {
                SwingUtilities.invokeLater(action)
            }
        }

        private fun initialWindowSize(fullscreen: Boolean): Vec2i {
            val requestedWidth = System.getenv("RWX_WINDOW_WIDTH")?.toIntOrNull()
            val requestedHeight = System.getenv("RWX_WINDOW_HEIGHT")?.toIntOrNull()
            if (requestedWidth != null && requestedHeight != null) {
                require(requestedWidth in 800..8192 && requestedHeight in 600..8192) { "Invalid diagnostic window size" }
                return Vec2i(requestedWidth, requestedHeight)
            }
            if (!fullscreen) return DEFAULT_WINDOW_SIZE
            val bounds = fullscreenBounds()
            return Vec2i(bounds.width.coerceAtLeast(800), bounds.height.coerceAtLeast(600))
        }

        private fun fullscreenBounds() =
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
                .bounds

    }
}

internal fun shouldSuppressKoolTypedCharacter(event: KeyEvent): Boolean =
    event.id == KeyEvent.KEY_TYPED && event.keyChar.isISOControl()

private class PointerCursorCanvas : Canvas() {
    var pointerCursor: Cursor? = null
    var inGamePointerCursorActive: Boolean = false

    override fun setCursor(cursor: Cursor?) {
        val effectiveCursor = if (inGamePointerCursorActive && cursor?.type == Cursor.DEFAULT_CURSOR) {
            pointerCursor ?: cursor
        } else {
            cursor
        }
        // Swing input reapplies its cursor each frame. AWT still enters the native cursor manager
        // for an unchanged cursor, so avoid that work once this canvas has an explicit cursor.
        if (isCursorSet && super.getCursor() === effectiveCursor) return
        super.setCursor(effectiveCursor)
    }
}
