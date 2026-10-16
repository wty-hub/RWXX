package io.github.rwx.kool.vulkan

import de.fabmax.kool.pipeline.backend.vk.GrowingBufferVk
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.util.MixedBuffer
import io.github.rwx.render.canvas.KoolCanvasAffineTextureInstanceLayout
import io.github.rwx.render.canvas.KoolCanvasTextureInstanceLayout
import org.lwjgl.vulkan.VkCommandBuffer

/** Implemented by the pinned overlay; the original allocation and fence retirement path stays intact. */
interface VulkanGrowingBufferCapacityAccess {
    fun rwxEnsureCapacity(bytes: Long)
}

/** Only the compact canvas texture-instance candidate changes native buffer reservation. */
object VulkanInstanceBufferCapacity {
    private val enabled = System.getenv("RWX_COMPACT_TEXTURE_INSTANCES") == "1"

    @JvmStatic
    fun writeData(buffer: GrowingBufferVk, data: MixedBuffer, commandBuffer: VkCommandBuffer,
                  instances: MeshInstanceList<*>) {
        val capacity = requiredCapacityBytes(buffer.size, instances, enabled)
        if (capacity > buffer.size) {
            // checkSize releases the old buffer through the existing frame-fence retirement overlay.
            (buffer as Any as VulkanGrowingBufferCapacityAccess).rwxEnsureCapacity(capacity)
        }
        // A reservation never changes the source limit or copies uninitialized capacity bytes.
        buffer.writeData(data, commandBuffer)
    }

    internal fun requiredCapacityBytes(currentBytes: Long, instances: MeshInstanceList<*>, enabled: Boolean): Long {
        if (!enabled || (instances.layout !== KoolCanvasTextureInstanceLayout &&
            instances.layout !== KoolCanvasAffineTextureInstanceLayout)) return currentBytes
        return maxOf(currentBytes, capacityBytes(instances.maxInstances, instances.layout.structSize))
    }

    internal fun capacityBytes(capacity: Int, strideBytes: Int): Long =
        Math.multiplyExact(capacity.toLong(), strideBytes.toLong())
}
