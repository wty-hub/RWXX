package io.github.rwx.kool.vulkan

import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.MipMapping
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.util.Uint8Buffer
import io.github.rwx.render.canvas.KoolCanvasBgraImageData
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT
import java.nio.ByteBuffer
import kotlin.test.*

class VulkanBgraUploadTest {
    private fun payload(): KoolCanvasBgraImageData {
        val data = Uint8Buffer(16)
        val bytes = byteArrayOf(0x56, 0x34, 0x12, 0xff.toByte(), 0x30, 0x20, 0x10, 0x80.toByte(),
            3, 2, 1, 0, 4, 3, 2, 1)
        bytes.forEachIndexed { index, byte -> data[index] = byte.toUByte() }
        return KoolCanvasBgraImageData(data, 4, 1, "vulkan-bgra-upload-test")
    }

    @Test
    fun `BGRA marker appends all channels including transparent rgb without touching target tail`() {
        val data = payload()
        val bytes = ByteArray(23) { 0x55 }
        val target = ByteBuffer.wrap(bytes).apply { position(3) }
        val originalPosition = data.data.position
        val originalLimit = data.data.limit
        VulkanUploads.copyImageData(data, target)
        assertEquals(19, target.position())
        assertContentEquals(byteArrayOf(0x56, 0x34, 0x12, 0xff.toByte(), 0x30, 0x20, 0x10, 0x80.toByte(),
            3, 2, 1, 0, 4, 3, 2, 1), bytes.copyOfRange(3, 19))
        assertContentEquals(ByteArray(3) { 0x55 }, bytes.copyOfRange(0, 3))
        assertContentEquals(ByteArray(4) { 0x55 }, bytes.copyOfRange(19, 23))
        assertEquals(originalPosition, data.data.position)
        assertEquals(originalLimit, data.data.limit)
    }

    @Test
    fun `only backend marker selects BGRA while ordinary RGBA retains its native format`() {
        val data = payload()
        assertEquals(VK_FORMAT_B8G8R8A8_UNORM,
            VulkanUploads.imageVkFormat(data, VK_IMAGE_TYPE_2D, 1, 1, MipMapping.Off, 0))
        assertEquals(VK_FORMAT_R8G8B8A8_UNORM,
            VulkanUploads.imageVkFormat(BufferedImageData2d(data.data, 4, 1, TexFormat.RGBA, "ordinary-rgba"),
                VK_IMAGE_TYPE_2D, 1, 1, MipMapping.Off, 0))
        assertEquals(16, VulkanUploads.checkedTextureBytes(4, 1, 1, 1, data.format))
    }

    @Test
    fun `BGRA payload rejects mipmaps and composite image types before image allocation`() {
        val data = payload()
        for ((type, depth, layers, flags) in listOf(
            listOf(VK_IMAGE_TYPE_1D, 1, 1, 0), listOf(VK_IMAGE_TYPE_3D, 2, 1, 0),
            listOf(VK_IMAGE_TYPE_2D, 1, 2, 0), listOf(VK_IMAGE_TYPE_2D, 1, 1, VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT),
        )) {
            assertFailsWith<IllegalArgumentException> {
                VulkanUploads.imageVkFormat(data, type, depth, layers, MipMapping.Off, flags)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            VulkanUploads.imageVkFormat(data, VK_IMAGE_TYPE_2D, 1, 1, MipMapping.Full, 0)
        }
    }

    @Test
    fun `native BGRA requires sampling linear filtering transfer and respects explicit optout`() {
        val features = listOf(VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT, VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT,
            VK_FORMAT_FEATURE_TRANSFER_DST_BIT)
        val all = features.reduce(Int::or)
        assertTrue(VulkanFrameLifecycle.supportsNativeBgraUploads(all, disabled = false))
        assertTrue(VulkanFrameLifecycle.supportsNativeBgraUploads(all or VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT, disabled = false))
        features.forEach { missing ->
            assertFalse(VulkanFrameLifecycle.supportsNativeBgraUploads(all and missing.inv(), disabled = false))
        }
        assertFalse(VulkanFrameLifecycle.supportsNativeBgraUploads(all, disabled = true))
    }
}
