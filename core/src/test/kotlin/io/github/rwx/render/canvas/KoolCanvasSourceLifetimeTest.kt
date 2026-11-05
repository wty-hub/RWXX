package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KoolCanvasSourceLifetimeTest {
    private fun frame(id: KoolCanvasTextureId) = KoolCanvasFrame(KoolCanvasViewport(8, 8), listOf(
        KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, 1, 1),
            KoolCanvasRect.fromSize(1f, 1f), KoolCanvasRect.fromSize(1f, 1f),
            KoolCanvasPaint.Default, KoolCanvasState.Default)))

    private fun pixel(packet: FrameEnvelope) = packet.resourceLease.resources().entries
        .single { it.value is FrozenCanvasResource.Pixels }

    @Test
    fun `reusing a source id cannot alias an older GPU registration still in flight`() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, reuseFrozenContent = false)
        val id = KoolCanvasTextureId("recreated-in-flight-source")
        val packets = mutableListOf<FrameEnvelope>()
        val registrations = mutableListOf<AutoCloseable>()
        try {
            store.registerArgb(id, 1, 1, intArrayOf(7), false)
            val old = store.freezeFrame(frame(id), 1, 1, 0, 0).also(packets::add)
            registrations += FrozenCanvasGpuResources.install(old.resourceLease)
            store.unregister(id)
            store.registerArgb(id, 1, 1, intArrayOf(9), false)
            val next = store.freezeFrame(frame(id), 2, 1, 0, 0).also(packets::add)
            assertNotEquals(pixel(old).key, pixel(next).key,
                "Unregister cannot recycle a version while an older frame may still sample it")
            registrations += FrozenCanvasGpuResources.install(next.resourceLease)
            assertEquals(7, KoolCanvasTextureRegistry.argbImageView(pixel(old).key)!!.pixels[0])
            assertEquals(9, KoolCanvasTextureRegistry.argbImageView(pixel(next).key)!!.pixels[0])
            // Retiring the newer frame first must not release the old frame's registration.
            registrations.removeLast().close()
            assertEquals(7, KoolCanvasTextureRegistry.argbImageView(pixel(old).key)!!.pixels[0])
        } finally {
            registrations.reversed().forEach(AutoCloseable::close)
            packets.forEach(FrameEnvelope::close)
            store.close()
        }
    }

    @Test
    fun `cached parent detects an unregistered descendant returning under the same logical id`() {
        for (gpu in listOf(false, true)) {
            val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, reuseFrozenContent = true)
            val leaf = KoolCanvasTextureId("recreated-cached-leaf-$gpu")
            val parent = KoolCanvasTextureId("recreated-cached-parent-$gpu")
            val packets = mutableListOf<FrameEnvelope>()
            try {
                store.registerArgb(leaf, 1, 1, intArrayOf(7), false)
                if (gpu) store.registerGpuTargetFrame(parent, frame(leaf), 8, 8)
                else store.registerFrame(parent, frame(leaf))
                val recording = frame(parent)
                val old = store.freezeFrame(recording, 1, 1, 0, 0).also(packets::add)
                val cached = store.freezeFrame(recording, 2, 1, 0, 0).also(packets::add)
                assertEquals(pixel(old).key, pixel(cached).key)
                store.unregister(leaf)
                store.registerArgb(leaf, 1, 1, intArrayOf(9), false)
                val next = store.freezeFrame(recording, 3, 1, 0, 0).also(packets::add)
                assertEquals(9, (pixel(next).value as FrozenCanvasResource.Pixels).image.pixels[0],
                    "A cached parent must invalidate even if its own recording object did not change")
                assertNotEquals(pixel(old).key, pixel(next).key)
                assertEquals(7, (pixel(old).value as FrozenCanvasResource.Pixels).image.pixels[0])
            } finally {
                packets.forEach(FrameEnvelope::close)
                store.close()
            }
        }
    }
}
