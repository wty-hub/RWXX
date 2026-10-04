package io.github.rwx.kool.vulkan

import de.fabmax.kool.pipeline.backend.vk.PassEncoderState
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.configJvm
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import io.github.rwx.render.canvas.CanvasFrameMetrics
import io.github.rwx.render.canvas.CanvasFramePresentation
import io.github.rwx.render.canvas.KoolCanvasTextureRegistry
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VkFormatProperties
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
import org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
import org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties
import org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkPresentInfoKHR
import org.lwjgl.vulkan.VkSubmitInfo
import org.lwjgl.vulkan.VK10.vkQueueSubmit
import org.lwjgl.vulkan.VK10.vkWaitForFences
import org.lwjgl.vulkan.KHRSwapchain.vkAcquireNextImageKHR
import org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR
import java.nio.IntBuffer
import java.util.IdentityHashMap

/** Invoked by the version-checked Kool overlay, on the native render owner thread. */
object VulkanFrameLifecycle {
    private val states = IdentityHashMap<RenderBackendVk, VulkanUploadState>()
    private var current: VulkanUploadState? = null
    private var completedShutdown = false
    internal var presentMode: Int = -1
        private set

    @JvmStatic
    fun created(backend: RenderBackendVk) {
        completedShutdown = false
        current = states.getOrPut(backend) { VulkanUploadState(backend) }
        val nativeBgraSupported = MemoryStack.stackPush().use { stack ->
            val properties = VkFormatProperties.calloc(stack)
            vkGetPhysicalDeviceFormatProperties(backend.physicalDevice.vkPhysicalDevice,
                VK_FORMAT_B8G8R8A8_UNORM, properties)
            supportsNativeBgraUploads(properties.optimalTilingFeatures(),
                // Conversion is faster, but controlled live runs have not demonstrated fewer stalls.
                // Keep this format experiment explicit until normal-game frame tails improve.
                disabled = System.getenv("RWX_NATIVE_BGRA_UPLOAD") != "1" ||
                    System.getenv("RWX_DISABLE_NATIVE_BGRA_UPLOAD") == "1")
        }
        KoolCanvasTextureRegistry.configureNativeBgraUploads(nativeBgraSupported)
        if (VulkanBackendMetrics.enabled || System.getenv("RWX_FRAME_METRICS") != null) {
            println("RWXVulkanConfiguration framebuffer=${backend.swapchain.width}x${backend.swapchain.height} " +
                "samples=${backend.swapchain.numSamples} configuredSamples=${KoolSystem.configJvm.numSamples} " +
                "presentMode=$presentMode vsync=${KoolSystem.configJvm.isVsync} " +
                "targetFps=${backend.ctx.maxFrameRate} asyncSceneUpdate=${KoolSystem.configJvm.asyncSceneUpdate} " +
                "nativeBgraUploads=$nativeBgraSupported")
        }
    }

    @JvmStatic
    fun beginFrame(encoder: PassEncoderState) {
        val state = stateFor(encoder.backend)
        current = state
        // beginFrame is called only after successful acquireNextImage's native fence wait.
        state.beginFrame(encoder)
    }

    @JvmStatic
    fun submitted(encoder: PassEncoderState) {
        val state = stateFor(encoder.backend)
        state.submitted(encoder.frameIndex)
        VulkanBackendMetrics.submitted(state)
    }

    @JvmStatic
    fun deviceIdle(backend: RenderBackendVk, destroying: Boolean) {
        states[backend]?.let { state ->
            state.deviceIdle(destroying)
            if (destroying) {
                states.remove(backend)
                if (current === state) {
                    current = null
                    completedShutdown = true
                    KoolCanvasTextureRegistry.configureNativeBgraUploads(false)
                }
            }
        }
        if (destroying) VulkanPipelineCache.release(backend.device.vkDevice)
    }

    /** Desktop injects this function as the core renderer's GPU resource retirement sink. */
    fun retainUntilFrameComplete(release: () -> Unit) {
        val state = current
        if (state != null) state.retainUntilFrameComplete(release)
        else {
            check(completedShutdown) { "Vulkan retirement sink used without a live Vulkan backend" }
            // Scene teardown can follow backend teardown. The native idle hook already completed
            // all GPU references, so remaining CPU leases may close without waiting for another frame.
            release()
        }
    }

    internal fun stateFor(backend: RenderBackendVk): VulkanUploadState =
        states.getOrPut(backend) { VulkanUploadState(backend) }

    internal fun activeCommandBuffer(backend: RenderBackendVk): VkCommandBuffer? = states[backend]?.commandBuffer

    internal fun retireNative(backend: RenderBackendVk, release: () -> Unit) {
        // Cleanup hooks remove state only after vkDeviceWaitIdle. Before the first submission,
        // or during device teardown, no queued GPU work can still reference this allocation.
        states[backend]?.retainUntilFrameComplete(release) ?: release()
    }

    @JvmStatic
    fun retireDriverTask(release: () -> Unit) {
        current?.retainUntilFrameComplete(release) ?: release()
    }

    @JvmStatic fun beforeSubmit() { VulkanDelayInjection.submit.pause() }
    @JvmStatic fun beforePresent() { VulkanDelayInjection.present.pause() }
    @JvmStatic fun selectedPresentMode(mode: Int) { presentMode = mode }
    @JvmStatic fun presented(accepted: Boolean) {
        CanvasFramePresentation.presented(accepted)
        if (accepted) CanvasFrameMetrics.presented()
    }
    @JvmStatic fun measuredFenceWait(device: VkDevice, fence: Long, waitAll: Boolean, timeout: Long): Int =
        VulkanBackendMetrics.measureFenceWait { vkWaitForFences(device, fence, waitAll, timeout) }
    @JvmStatic fun measuredAcquireImage(device: VkDevice, swapchain: Long, timeout: Long, semaphore: Long,
        fence: Long, image: IntBuffer): Int =
        VulkanBackendMetrics.measureAcquire { vkAcquireNextImageKHR(device, swapchain, timeout, semaphore, fence, image) }
    @JvmStatic fun measuredQueuePresent(queue: VkQueue, info: VkPresentInfoKHR): Int =
        VulkanBackendMetrics.measurePresent { vkQueuePresentKHR(queue, info) }
    @JvmStatic fun measuredQueueSubmit(queue: VkQueue, info: VkSubmitInfo, fence: Long): Int =
        VulkanBackendMetrics.measureSubmit { vkQueueSubmit(queue, info, fence) }
    @JvmStatic fun checkedFenceWait(result: Int) {
        check(result == VK_SUCCESS) { "Vulkan frame fence wait failed: $result" }
    }
    @JvmStatic fun checkedDeviceIdle(result: Int) {
        check(result == VK_SUCCESS) { "Vulkan device idle wait failed: $result" }
    }

    internal fun supportsNativeBgraUploads(optimalTilingFeatures: Int, disabled: Boolean): Boolean {
        val required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT or
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT or VK_FORMAT_FEATURE_TRANSFER_DST_BIT
        return !disabled && (optimalTilingFeatures and required) == required
    }
}

internal object VulkanDelayInjection {
    val submit = VulkanTestDelay.fromEnvironment("RWX_VK_SUBMIT_DELAY_MS")
    val upload = VulkanTestDelay.fromEnvironment("RWX_VK_UPLOAD_DELAY_MS")
    val present = VulkanTestDelay.fromEnvironment("RWX_VK_PRESENT_DELAY_MS")
}

/** Optional verification delay, disabled by default; capped so malformed input cannot freeze the app. */
internal class VulkanTestDelay private constructor(private val millis: Long) {
    fun pause() { if (millis > 0) Thread.sleep(millis) }
    companion object {
        fun fromEnvironment(name: String) = VulkanTestDelay(System.getenv(name)?.toLongOrNull()?.coerceIn(0, 1000) ?: 0)
    }
}
