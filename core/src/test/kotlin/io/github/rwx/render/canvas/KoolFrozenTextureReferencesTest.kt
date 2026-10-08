package io.github.rwx.render.canvas

import kotlin.test.*

class KoolFrozenTextureReferencesTest {
    private val id = KoolCanvasTextureId("reference-source")
    private fun frame(ref: KoolCanvasTextureRef) = KoolCanvasFrame(KoolCanvasViewport(8, 8), listOf(
        KoolCanvasCommand.DrawTexture(ref, ref.fullRect, ref.fullRect, KoolCanvasPaint.Default, KoolCanvasState.Default)))
    private fun texture(envelope: FrameEnvelope) = (envelope.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture
    private fun store() = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true,
        reuseFrozenContent = true, reuseFrozenTextureReferences = true)

    @Test fun `metadata reuse preserves leased versions and responds to changed attributes and source pixels`() {
        val store = store()
        val ref = KoolCanvasTextureRef(id, 1, 1)
        store.registerArgb(id, 1, 1, intArrayOf(0xff123456.toInt()), false)
        val first = store.freezeFrame(frame(ref), 1, 0, 1, 0)
        val second = store.freezeFrame(frame(ref), 2, 0, 2, 0)
        assertSame(texture(first), texture(second))
        assertEquals(1, second.resourceLease.resourceCount)
        val changedFlags = store.freezeFrame(frame(ref.copy(hasAlpha = !ref.hasAlpha)), 3, 0, 3, 0)
        assertNotSame(texture(first), texture(changedFlags))
        assertNotEquals(texture(first).hasAlpha, texture(changedFlags).hasAlpha)
        store.registerArgb(id, 1, 1, intArrayOf(0xffabcdef.toInt()), false)
        val changedPixels = store.freezeFrame(frame(ref), 4, 0, 4, 0)
        assertNotEquals(texture(first).id, texture(changedPixels).id)
        assertEquals(texture(first).frozenPixelIdentity, texture(changedPixels).frozenPixelIdentity)
        assertEquals(0xff123456.toInt(), (first.resourceLease.resources().getValue(texture(first).id) as FrozenCanvasResource.Pixels).image.pixels[0])
        assertEquals(0xffabcdef.toInt(), (changedPixels.resourceLease.resources().getValue(texture(changedPixels).id) as FrozenCanvasResource.Pixels).image.pixels[0])
        listOf(first, second, changedFlags, changedPixels).forEach(FrameEnvelope::close)
        store.close()
    }

    @Test fun `an enclosing target must restore descendants and invalidate references after a descendant change`() {
        for (gpu in listOf(false, true)) {
            val store = store()
            val target = KoolCanvasTextureId("reference-target-$gpu")
            val ref = KoolCanvasTextureRef(id, 1, 1)
            store.registerArgb(id, 1, 1, intArrayOf(0xff123456.toInt()), false)
            val targetFrame = frame(ref)
            if (gpu) store.registerGpuTargetFrame(target, targetFrame, 8, 8) else store.registerFrame(target, targetFrame)
            val root = frame(KoolCanvasTextureRef(target, 8, 8))
            val first = store.freezeFrame(root, 1, 0, 1, 0)
            val second = store.freezeFrame(root, 2, 0, 2, 0)
            assertSame(texture(first), texture(second))
            assertEquals(2, second.resourceLease.resourceCount)
            store.registerArgb(id, 1, 1, intArrayOf(0xffabcdef.toInt()), false)
            val changed = store.freezeFrame(root, 3, 0, 3, 0)
            assertNotEquals(texture(first).id, texture(changed).id)
            assertEquals(2, changed.resourceLease.resourceCount)
            assertEquals(0xff123456.toInt(), first.resourceLease.resources().values.filterIsInstance<FrozenCanvasResource.Pixels>().single().image.pixels[0])
            assertEquals(0xffabcdef.toInt(), changed.resourceLease.resources().values.filterIsInstance<FrozenCanvasResource.Pixels>().single().image.pixels[0])
            listOf(first, second, changed).forEach(FrameEnvelope::close)
            store.close()
        }
    }
}
