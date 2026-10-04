package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import com.corrodinggames.rts.gameFramework.graphics.Texture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolTextureMetadataReuseTest {
    private fun draw(graphics: KoolGraphicsEngine, texture: Texture): KoolCanvasTextureRef {
        graphics.beginFrame(8, 8)
        graphics.b(texture, 0f, 0f, null)
        return (graphics.snapshot().commands.single() as KoolCanvasCommand.DrawTexture).texture
    }

    @Test fun `unchanged references and ID strings are shared by root and offscreen draws`() {
        val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val source = root.b(2, 1, true).apply { setCommittedArgbPixels(intArrayOf(-1, -1)) }
        val destination = root.b(2, 1, true)
        val offscreen = root.b(destination, RenderTargetMode.DEFAULT) as KoolGraphicsEngine
        try {
            val reference = draw(root, source)
            repeat(20) { assertSame(reference, draw(root, source)) }
            assertSame(reference, draw(offscreen, source))
            assertSame(reference.id.value, draw(root, source).id.value)
        } finally {
            offscreen.q(); source.o(); destination.o(); store.close()
        }
    }

    @Test fun `dimensions alpha ordering premultiplication and legacy ID invalidate references without changing old frames`() {
        val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val texture = root.b(2, 1, true).apply { setCommittedArgbPixels(intArrayOf(-1, -1)) }
        try {
            val original = draw(root, texture)
            texture.p = 3; texture.q = 2
            texture.setCommittedArgbPixels(IntArray(6) { -1 })
            val resized = draw(root, texture)
            assertNotSame(original, resized)
            assertEquals(3, resized.width); assertEquals(2, resized.height)
            texture.m = true
            val alpha = draw(root, texture)
            assertTrue(alpha.hasAlpha); assertFalse(resized.hasAlpha)
            texture.requireOrderedAlpha()
            val ordered = draw(root, texture)
            assertTrue(ordered.requiresOrderedAlpha); assertFalse(alpha.requiresOrderedAlpha)
            texture.setPremultipliedAlpha(true)
            val premultiplied = draw(root, texture)
            assertTrue(premultiplied.premultipliedAlpha); assertFalse(ordered.premultipliedAlpha)
            texture.d = -987654
            val renamed = draw(root, texture)
            assertEquals("legacy-texture--987654", renamed.id.value)
            assertSame(renamed, draw(root, texture))
            assertEquals(2, original.width); assertEquals(1, original.height)
            assertFalse(original.hasAlpha); assertFalse(original.requiresOrderedAlpha); assertFalse(original.premultipliedAlpha)
            assertEquals(premultiplied.id, original.id)
        } finally {
            texture.o(); store.close()
        }
    }

    @Test fun `immediate cache hits reuse revision metadata while legacy edits preserve completed pixels`() {
        val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val texture = root.b(2, 1, true)
        val target = root.b(texture, RenderTargetMode.IMMEDIATE) as KoolGraphicsEngine
        val registrations = KoolGraphicsEngine::class.java.getDeclaredField("registeredTexturePixelRevisions")
            .apply { isAccessible = true }.get(root) as Map<*, *>
        var packet: FrameEnvelope? = null
        try {
            target.b(0xff123456.toInt()); target.p()
            val reference = draw(root, texture)
            val registration = assertNotNull(registrations[reference.id])
            repeat(20) { draw(root, texture); assertSame(registration, registrations[reference.id]) }
            val completed = store.freezeFrame(root.snapshot(), 1, 1, 0, 0)
            packet = completed
            texture.a(0, 0, 0xff654321.toInt())
            draw(root, texture)
            assertNotSame(registration, registrations[reference.id])
            assertContentEquals(intArrayOf(0xff654321.toInt(), 0xff123456.toInt()),
                assertNotNull(store.argbImageView(reference.id)).pixels)
            val frozen = completed.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
            assertContentEquals(IntArray(2) { 0xff123456.toInt() }, frozen.image.pixels)
        } finally {
            packet?.close(); target.q(); texture.o(); store.close()
        }
    }

    @Test fun `releasing backend textures clears shared metadata without changing a completed reference`() {
        val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val metadata = KoolGraphicsEngine::class.java.getDeclaredField("textureMetadata")
            .apply { isAccessible = true }.get(root) as Map<*, *>
        try {
            val texture = root.b(2, 1, true).apply { setCommittedArgbPixels(intArrayOf(-1, -1)) }
            val reference = draw(root, texture)
            assertTrue(metadata.containsKey(texture))
            texture.o()
            assertFalse(metadata.containsKey(texture))
            assertEquals(2, reference.width); assertEquals(1, reference.height)
        } finally {
            store.close()
        }
    }
}
