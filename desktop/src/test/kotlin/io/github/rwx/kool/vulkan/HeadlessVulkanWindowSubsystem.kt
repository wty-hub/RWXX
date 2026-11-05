package io.github.rwx.kool.vulkan

import de.fabmax.kool.DragAndDropListener
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.ScaleChangeListener
import de.fabmax.kool.WindowCapabilities
import de.fabmax.kool.WindowCloseListener
import de.fabmax.kool.WindowFlags
import de.fabmax.kool.WindowFlagsListener
import de.fabmax.kool.WindowResizeListener
import de.fabmax.kool.input.CursorMode
import de.fabmax.kool.input.CursorShape
import de.fabmax.kool.input.PlatformInput
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.platform.ClientApi
import de.fabmax.kool.platform.GlWindowCallbacks
import de.fabmax.kool.platform.KoolWindowJvm
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.platform.WindowSubsystem
import de.fabmax.kool.util.BufferedList
import de.fabmax.kool.util.WindowTitleHoverHandler
import kotlinx.coroutines.runBlocking
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.EXTHeadlessSurface.VK_EXT_HEADLESS_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.EXTHeadlessSurface.vkCreateHeadlessSurfaceEXT
import org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VkHeadlessSurfaceCreateInfoEXT
import org.lwjgl.vulkan.VkInstance
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * A memory-only window adapter. The native target is VK_EXT_headless_surface, with no GLFW, AWT
 * toolkit, NSApplication, NSWindow, NSView, or CAMetalLayer created by this adapter.
 *
 * Keep Kool's real Vulkan backend, command buffers, swapchain fences, uploads and readbacks intact.
 * Missing headless-surface support fails in instance creation; there is no windowed or GL fallback.
 */
internal class HeadlessVulkanWindowSubsystem(
    private val frameObserver: WindowlessFrameObserver? = null,
) : WindowSubsystem {
    private var context: Lwjgl3Context? = null
    private var memoryWindow: MemoryWindow? = null
    @Volatile private var closeRequested = false

    override val isCloseRequested: Boolean get() = closeRequested
    override val input: PlatformInput = object : PlatformInput {
        override fun setCursorMode(cursorMode: CursorMode) = Unit
        override fun applyCursorShape(cursorShape: CursorShape) = Unit
    }

    override fun onEarlyInit() = Unit
    override fun onBackendCreated(ctx: Lwjgl3Context) = Unit

    override fun queryRequiredVkExtensions(): List<String> =
        listOf(VK_KHR_SURFACE_EXTENSION_NAME, VK_EXT_HEADLESS_SURFACE_EXTENSION_NAME)

    override fun createWindow(
        clientApi: ClientApi,
        glCallbacks: GlWindowCallbacks?,
        ctx: Lwjgl3Context,
    ): KoolWindowJvm {
        check(clientApi == ClientApi.UNMANAGED && glCallbacks == null) { "Only headless Vulkan is supported" }
        check(memoryWindow == null) { "A headless Vulkan window already exists" }
        context = ctx
        return MemoryWindow(ctx.config.windowSize, ctx.config.windowTitle).also { memoryWindow = it }
    }

    override fun runRenderLoop() {
        val ctx = checkNotNull(context)
        check(KoolSystem.getContextOrNull() === ctx) { "Headless Vulkan context is not installed" }
        try {
            runBlocking {
                while (!closeRequested) {
                    frameObserver?.beforeFrame()
                    var frameFailure: Throwable? = null
                    try {
                        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                            WindowlessKoolBridge.renderFrame(ctx, continuation)
                        }
                    } catch (error: Throwable) {
                        frameFailure = error
                        throw error
                    } finally {
                        try { frameObserver?.afterFrame(frameFailure) }
                        catch (observerFailure: Throwable) {
                            if (frameFailure != null) frameFailure.addSuppressed(observerFailure)
                            else throw observerFailure
                        }
                    }
                }
            }
        } finally {
            // The standard shutdown releases scenes, executes delayed releases and idles the real GPU.
            WindowlessKoolBridge.shutdown(this)
        }
    }

    private inner class MemoryWindow(initialSize: Vec2i, initialTitle: String) : KoolWindowJvm {
        override val parentScreenScale: Float = 1f
        override var positionInScreen: Vec2i = Vec2i(0, 0)
        override var sizeOnScreen: Vec2i = initialSize
        override var renderResolutionFactor: Float = 1f
        override val framebufferSize: Vec2i get() = sizeOnScreen
        override val size: Vec2i get() = sizeOnScreen
        override val renderScale: Float = 1f
        override var title: String = initialTitle
        override val flags: WindowFlags = WindowFlags()
        override val capabilities: WindowCapabilities = WindowCapabilities.NONE
        override val isMouseOverWindow: Boolean = false

        override val resizeListeners = BufferedList<WindowResizeListener>()
        override val scaleChangeListeners = BufferedList<ScaleChangeListener>()
        override val flagListeners = BufferedList<WindowFlagsListener>()
        override val closeListeners = BufferedList<WindowCloseListener>()
        override val dragAndDropListeners = BufferedList<DragAndDropListener>()
        override var windowTitleHoverHandler = WindowTitleHoverHandler()

        override fun close() {
            closeRequested = true
        }

        override fun pollEvents() = Unit
        override fun swapBuffers(): Unit = error("OpenGL is not supported by the headless Vulkan adapter")

        override fun createVulkanSurface(instance: VkInstance): Long = MemoryStack.stackPush().use { stack ->
            val info = VkHeadlessSurfaceCreateInfoEXT.calloc(stack).`sType$Default`()
            val handle = stack.mallocLong(1)
            val result = vkCreateHeadlessSurfaceEXT(instance, info, null, handle)
            check(result == VK_SUCCESS) { "vkCreateHeadlessSurfaceEXT failed: $result" }
            handle[0]
        }

        override fun destroyVulkanSurface(surface: Long, instance: VkInstance) {
            vkDestroySurfaceKHR(instance, surface, null)
        }
    }
}
