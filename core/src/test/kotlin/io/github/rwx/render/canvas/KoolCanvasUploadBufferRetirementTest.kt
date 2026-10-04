package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.Uint8Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolCanvasUploadBufferRetirementTest {
    private fun register(id: KoolCanvasTextureId, width: Int, height: Int, pixel: Int = 0xff123456.toInt()) {
        KoolCanvasTextureRegistry.registerArgb(id, width, height, IntArray(width * height) { pixel }, false)
    }

    private fun resolve(id: KoolCanvasTextureId, width: Int, height: Int): Texture2d =
        KoolCanvasTextureRegistry.resolve(KoolCanvasTextureRef(id, width, height), KoolCanvasTextureFilter.Nearest)

    private fun upload(texture: Texture2d) = (texture.uploadData as BufferedImageData2d).data as Uint8Buffer

    private fun drain(pending: MutableList<() -> Unit>) {
        while (pending.isNotEmpty()) pending.removeAt(0).invoke()
    }

    @Test
    fun versionedTexturesKeepTheirPayloadUntilFenceAndThenReuseItAcrossIds() {
        KoolCanvasTextureRegistry.invalidateContextResources()
        val ids = (1..3).map { KoolCanvasTextureId("upload-pool-version-$it") }
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            register(ids[0], 97, 11)
            val oldTexture = resolve(ids[0], 97, 11)
            val oldBuffer = upload(oldTexture)
            KoolCanvasTextureRegistry.unregister(ids[0])
            assertFalse(oldTexture.isReleased)
            assertSame(oldBuffer, upload(oldTexture)) // Still-pending ImageData owns the source.
            register(ids[1], 97, 11)
            val newer = resolve(ids[1], 97, 11)
            assertNotSame(oldBuffer, upload(newer))
            assertEquals(1, pending.size)
            pending.removeAt(0).invoke()
            assertTrue(oldTexture.isReleased)
            assertNull(oldTexture.uploadData)
            register(ids[2], 97, 11)
            val thirdBuffer = upload(resolve(ids[2], 97, 11))
            if (KoolCanvasTextureRegistry.uploadBufferPoolStats().enabled) assertSame(oldBuffer, thirdBuffer)
            else assertNotSame(oldBuffer, thirdBuffer)
            assertTrue(KoolCanvasTextureRegistry.uploadBufferPoolDiagnostics().contains("idleCapacityBytes="))
        } finally {
            ids.forEach(KoolCanvasTextureRegistry::unregister)
            drain(pending)
            KoolCanvasGpuRetirement.install(null)
        }
    }

    @Test
    fun nonVersionedRefreshKeepsTwoSlotsAndNewestPendingPayload() {
        KoolCanvasTextureRegistry.invalidateContextResources()
        val id = KoolCanvasTextureId("upload-pool-mutable-two-slots")
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            register(id, 101, 7)
            val texture = resolve(id, 101, 7)
            val first = upload(texture)
            texture.uploadData = null
            register(id, 101, 7, 0xff654321.toInt())
            val second = upload(texture)
            assertNotSame(first, second)
            register(id, 101, 7, 0xffa04020.toInt())
            assertSame(second, upload(texture)) // Replacing a not-yet-consumed upload uses its slot.
            assertEquals(0xa0.toUByte(), second[0])
            texture.uploadData = null
            register(id, 101, 7)
            assertSame(first, upload(texture))
            assertEquals(0, pending.size)
            KoolCanvasTextureRegistry.unregister(id)
            assertEquals(1, pending.size)
            assertFalse(texture.isReleased)
            drain(pending)
            assertTrue(texture.isReleased)
            assertNull(texture.uploadData)
        } finally {
            KoolCanvasTextureRegistry.unregister(id)
            drain(pending)
            KoolCanvasGpuRetirement.install(null)
        }
    }

    @Test
    fun resizeRetiresTheDisplacedSlotAfterReplacingUploadData() {
        KoolCanvasTextureRegistry.invalidateContextResources()
        val ids = (1..3).map { KoolCanvasTextureId("upload-pool-resize-$it") }
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            register(ids[0], 37, 11)
            val texture = resolve(ids[0], 37, 11)
            val previous = upload(texture)
            register(ids[0], 41, 11, 0xff654321.toInt())
            val resized = upload(texture)
            assertNotSame(previous, resized)
            assertEquals(0x12.toUByte(), previous[0])
            assertEquals(0x65.toUByte(), resized[0])
            assertEquals(1, pending.size)
            register(ids[1], 37, 11)
            assertNotSame(previous, upload(resolve(ids[1], 37, 11)))
            pending.removeAt(0).invoke()
            assertFalse(texture.isReleased) // Only its obsolete host slot was retired.
            register(ids[2], 37, 11)
            val recycled = upload(resolve(ids[2], 37, 11))
            if (KoolCanvasTextureRegistry.uploadBufferPoolStats().enabled) assertSame(previous, recycled)
            else assertNotSame(previous, recycled)
        } finally {
            ids.forEach(KoolCanvasTextureRegistry::unregister)
            drain(pending)
            KoolCanvasGpuRetirement.install(null)
        }
    }

    @Test
    fun contextInvalidationNeverRecoversOldRetiredHostStorage() {
        KoolCanvasTextureRegistry.invalidateContextResources()
        val oldId = KoolCanvasTextureId("upload-pool-old-context")
        val newId = KoolCanvasTextureId("upload-pool-new-context")
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            register(oldId, 113, 13)
            val oldTexture = resolve(oldId, 113, 13)
            val oldBuffer = upload(oldTexture)
            KoolCanvasTextureRegistry.unregister(oldId)
            assertEquals(1, pending.size)
            KoolCanvasTextureRegistry.invalidateContextResources()
            pending.removeAt(0).invoke()
            assertFalse(oldTexture.isReleased) // The old native context owns its GPU handles.
            assertEquals(0, KoolCanvasTextureRegistry.uploadBufferPoolStats().retainedBuffers)
            register(newId, 113, 13)
            assertNotSame(oldBuffer, upload(resolve(newId, 113, 13)))
        } finally {
            KoolCanvasTextureRegistry.unregister(oldId)
            KoolCanvasTextureRegistry.unregister(newId)
            drain(pending)
            KoolCanvasGpuRetirement.install(null)
        }
    }
}
