package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.Uint8Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolCanvasUploadBufferReentryTest {
    private fun resolve(id: KoolCanvasTextureId, width: Int = 2, height: Int = 2,
        filter: KoolCanvasTextureFilter = KoolCanvasTextureFilter.Nearest): Texture2d =
        KoolCanvasTextureRegistry.resolve(KoolCanvasTextureRef(id, width, height), filter)

    private fun upload(texture: Texture2d) = (texture.uploadData as BufferedImageData2d).data as Uint8Buffer

    private fun register(id: KoolCanvasTextureId, width: Int, height: Int, pixel: Int = 0xff123456.toInt()) {
        KoolCanvasTextureRegistry.registerArgb(id, width, height, IntArray(width * height) { pixel }, false)
    }

    @Test
    fun retiringAnInstalledVersionMaySynchronouslyCloseAnotherInstalledVersion() {
        KoolCanvasTextureRegistry.invalidateContextResources()
        val store = KoolCanvasCpuTextureStore()
        val ids = listOf(KoolCanvasTextureId("upload-reentry-first"), KoolCanvasTextureId("upload-reentry-second"))
        val frames = ids.mapIndexed { index, id ->
            store.registerArgb(id, 2, 2, IntArray(4) { index + 1 }, false)
            val rect = KoolCanvasRect.fromSize(2f, 2f)
            val draw = KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, 2, 2), rect, rect,
                KoolCanvasPaint.Default, KoolCanvasState.Default)
            store.freezeFrame(KoolCanvasFrame(KoolCanvasViewport(10, 10), listOf(draw)), index.toLong(), 1, 1, 1)
        }
        val installed = frames.map { FrozenCanvasGpuResources.install(it.resourceLease) }
        val versions = frames.map { it.resourceLease.resources().keys.single() }
        val textures = versions.map { resolve(it) }
        var drainedOtherVersion = false
        KoolCanvasGpuRetirement.install { release ->
            if (!drainedOtherVersion) {
                drainedOtherVersion = true
                // This is the same reentry as a completed Vulkan fence draining other GPU leases.
                installed[1].close()
            }
            release()
        }
        try {
            installed[0].close()
            assertTrue(drainedOtherVersion)
            textures.forEach { assertTrue(it.isReleased); assertNull(it.uploadData) }
            versions.forEach { assertNull(KoolCanvasTextureRegistry.argbImageView(it)) }
        } finally {
            KoolCanvasGpuRetirement.install(null)
            installed.forEach { it.close() }
            frames.forEach { it.close() }
            store.close()
        }
    }

    @Test
    fun synchronousRetirementRegistrationOfTheSameIdKeepsItsNewSlotMetadata() {
        KoolCanvasTextureRegistry.invalidateContextResources()
        val id = KoolCanvasTextureId("upload-reentry-register-same-id")
        register(id, 17, 5)
        val old = resolve(id, 17, 5)
        var replacement: Texture2d? = null
        var registeredAgain = false
        KoolCanvasGpuRetirement.install { release ->
            if (!registeredAgain) {
                registeredAgain = true
                register(id, 17, 5, 0xff654321.toInt())
                replacement = resolve(id, 17, 5)
            }
            release()
        }
        try {
            KoolCanvasTextureRegistry.unregister(id)
            val current = checkNotNull(replacement)
            assertTrue(old.isReleased)
            assertNotSame(old, current)
            assertSame(current, resolve(id, 17, 5))
            assertFalse(current.isReleased)
            register(id, 17, 5, 0xffa04020.toInt())
            assertSame(current, resolve(id, 17, 5))
            assertEquals(0xa0.toUByte(), upload(current)[0]) // Refresh still finds this new owner's slots.
        } finally {
            KoolCanvasGpuRetirement.install(null)
            KoolCanvasTextureRegistry.unregister(id)
        }
    }

    @Test
    fun displacedSlotRetirementCanRemoveAnotherCacheDuringMultiFilterRefresh() {
        KoolCanvasTextureRegistry.invalidateContextResources()
        val id = KoolCanvasTextureId("upload-reentry-resize-filters")
        val otherId = KoolCanvasTextureId("upload-reentry-resize-other")
        register(id, 19, 5)
        val nearest = resolve(id, 19, 5)
        val linear = resolve(id, 19, 5, KoolCanvasTextureFilter.Linear)
        register(otherId, 19, 5)
        val other = resolve(otherId, 19, 5)
        var unregisteredOther = false
        KoolCanvasGpuRetirement.install { release ->
            if (!unregisteredOther) {
                unregisteredOther = true
                KoolCanvasTextureRegistry.unregister(otherId)
            }
            release()
        }
        try {
            register(id, 23, 5, 0xff654321.toInt())
            assertTrue(unregisteredOther)
            assertTrue(other.isReleased)
            assertFalse(nearest.isReleased)
            assertFalse(linear.isReleased)
            listOf(nearest, linear).forEach { texture ->
                assertEquals(23, (texture.uploadData as BufferedImageData2d).width)
                assertEquals(0x65.toUByte(), upload(texture)[0])
            }
        } finally {
            KoolCanvasGpuRetirement.install(null)
            KoolCanvasTextureRegistry.unregister(id)
            KoolCanvasTextureRegistry.unregister(otherId)
        }
    }

    @Test
    fun reentrantFailedResizeKeepsTheNewlyDisplacedPendingPayloadOwned() {
        KoolCanvasTextureRegistry.invalidateContextResources()
        val id = KoolCanvasTextureId("slick-complete-frame")
        register(id, 19, 5)
        val texture = resolve(id, 19, 5)
        var reentered = false
        KoolCanvasGpuRetirement.install { release ->
            if (!reentered) {
                reentered = true
                val failure = IllegalStateException("injected reentrant packing failure")
                KoolCanvasTextureRegistry.setCompleteFramePixelPacker { _, _, _ -> throw failure }
                try {
                    assertSame(failure, assertFailsWith<IllegalStateException> { register(id, 29, 5) })
                } finally {
                    KoolCanvasTextureRegistry.setCompleteFramePixelPacker(null)
                }
            }
            release()
        }
        try {
            register(id, 23, 5)
            assertTrue(reentered)
            // The failed inner replacement owns its new slot, while this still-pending payload
            // owns the buffer displaced by that inner resize. The outer retirement cannot drop it.
            assertEquals(23, (texture.uploadData as BufferedImageData2d).width)
            assertEquals(2, KoolCanvasTextureRegistry.uploadBufferPoolStats().activeBuffers)
            KoolCanvasTextureRegistry.unregister(id)
            assertEquals(0, KoolCanvasTextureRegistry.uploadBufferPoolStats().activeBuffers)
        } finally {
            KoolCanvasTextureRegistry.setCompleteFramePixelPacker(null)
            KoolCanvasGpuRetirement.install(null)
            KoolCanvasTextureRegistry.unregister(id)
        }
    }
}
