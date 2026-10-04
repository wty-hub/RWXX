package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.Uint8Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolCanvasUploadBufferUnfencedTest {
    private fun register(id: KoolCanvasTextureId, width: Int, pixel: Int = 0xff123456.toInt()) {
        KoolCanvasTextureRegistry.registerArgb(id, width, 7, IntArray(width * 7) { pixel }, false)
    }

    private fun resolve(id: KoolCanvasTextureId, width: Int) =
        KoolCanvasTextureRegistry.resolve(KoolCanvasTextureRef(id, width, 7), KoolCanvasTextureFilter.Nearest)

    private fun upload(texture: Texture2d) = (texture.uploadData as BufferedImageData2d).data as Uint8Buffer

    private fun drainOldRetirement() {
        repeat(2) { KoolCanvasTextureRegistry.releaseRetiredTextures() }
    }

    @Test
    fun withoutSinkUnregisterPreservesOldRetirementAndNeverLendsItsPayload() {
        KoolCanvasGpuRetirement.install(null)
        KoolCanvasTextureRegistry.invalidateContextResources()
        val firstId = KoolCanvasTextureId("upload-unfenced-old")
        val secondId = KoolCanvasTextureId("upload-unfenced-new")
        try {
            register(firstId, 127)
            val old = resolve(firstId, 127)
            val pending = upload(old)
            KoolCanvasTextureRegistry.unregister(firstId)
            assertFalse(old.isReleased)
            assertSame(pending, upload(old)) // An asynchronous loader may already hold this raw data.
            assertEquals(0, KoolCanvasTextureRegistry.uploadBufferPoolStats().activeBuffers)
            assertEquals(0, KoolCanvasTextureRegistry.uploadBufferPoolStats().retainedBuffers)
            register(secondId, 127, 0xff654321.toInt())
            assertNotSame(pending, upload(resolve(secondId, 127)))
            assertEquals(0x12.toUByte(), pending[0])
            KoolCanvasTextureRegistry.releaseRetiredTextures()
            assertFalse(old.isReleased)
            KoolCanvasTextureRegistry.releaseRetiredTextures()
            assertTrue(old.isReleased)
            assertEquals(0, KoolCanvasTextureRegistry.uploadBufferPoolStats().retainedBuffers)
        } finally {
            KoolCanvasTextureRegistry.unregister(firstId)
            KoolCanvasTextureRegistry.unregister(secondId)
            drainOldRetirement()
        }
    }

    @Test
    fun withoutSinkResizeDiscardsOldSlotWhileMutableRefreshStillUsesTwoSlots() {
        KoolCanvasGpuRetirement.install(null)
        KoolCanvasTextureRegistry.invalidateContextResources()
        val id = KoolCanvasTextureId("upload-unfenced-resize")
        val otherId = KoolCanvasTextureId("upload-unfenced-same-old-size")
        try {
            register(id, 131)
            val texture = resolve(id, 131)
            val first = upload(texture)
            texture.uploadData = null
            register(id, 131, 0xff654321.toInt())
            val second = upload(texture)
            assertNotSame(first, second)
            texture.uploadData = null
            register(id, 131)
            assertSame(first, upload(texture)) // The original per-texture alternating slots remain.
            register(id, 137, 0xffa04020.toInt())
            assertNotSame(first, upload(texture))
            register(otherId, 131)
            val other = upload(resolve(otherId, 131))
            assertNotSame(first, other) // Even the displaced buffer cannot be globally reused.
            assertNotSame(second, other)
            assertEquals(0, KoolCanvasTextureRegistry.uploadBufferPoolStats().retainedBuffers)
            KoolCanvasTextureRegistry.unregister(id)
            assertFalse(texture.isReleased)
            assertEquals(1, KoolCanvasTextureRegistry.uploadBufferPoolStats().activeBuffers)
        } finally {
            KoolCanvasTextureRegistry.unregister(id)
            KoolCanvasTextureRegistry.unregister(otherId)
            drainOldRetirement()
            assertEquals(0, KoolCanvasTextureRegistry.uploadBufferPoolStats().activeBuffers)
            assertEquals(0, KoolCanvasTextureRegistry.uploadBufferPoolStats().retainedBuffers)
        }
    }
}
