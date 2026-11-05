package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import com.corrodinggames.rts.gameFramework.graphics.Texture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class KoolSharedTextureRegistrationTest {
    private class CountingStore(val delegate: KoolCanvasCpuTextureStore) : KoolCanvasTextureStore by delegate {
        val argbRegistrations = mutableMapOf<KoolCanvasTextureId, Int>()
        val premultipliedRegistrations = mutableMapOf<KoolCanvasTextureId, Int>()

        override fun registerArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray,
                                  alphaBleed: Boolean) {
            argbRegistrations[id] = (argbRegistrations[id] ?: 0) + 1
            delegate.registerArgb(id, width, height, argbPixels, alphaBleed)
        }

        override fun registerPremultipliedArgb(id: KoolCanvasTextureId, width: Int, height: Int,
                                             argbPixels: IntArray) {
            premultipliedRegistrations[id] = (premultipliedRegistrations[id] ?: 0) + 1
            delegate.registerPremultipliedArgb(id, width, height, argbPixels)
        }
    }

    private fun draw(graphics: KoolGraphicsEngine, source: Texture): KoolCanvasTextureId {
        graphics.beginFrame(8, 8)
        graphics.b(source, 0f, 0f, null)
        return when (val command = graphics.snapshot().commands.single()) {
            is KoolCanvasCommand.DrawTexture -> command.texture.id
            is KoolCanvasCommand.DrawTextureBatch -> {
                assertEquals(1, command.quads.size, "The fixture records exactly one source draw")
                command.texture.id
            }
            else -> error("Expected one texture draw, got $command")
        }
    }

    @Test
    fun `root detached engine and cells register each shared sprite revision once`() {
        val store = KoolCanvasCpuTextureStore()
        val counted = CountingStore(store)
        val root = KoolGraphicsEngine(counted, shareTextureRegistrations = true)
        val source = root.b(2, 1, true).apply { setCommittedArgbPixels(intArrayOf(7, 8)) }
        val targets = List(3) { root.b(8, 8, true) }
        val children = targets.map { root.b(it, RenderTargetMode.GPU_TARGET) as KoolGraphicsEngine }
        val detached = root.b(null as Texture?) as KoolGraphicsEngine
        val engines = listOf(root, detached) + children
        val packets = mutableListOf<FrameEnvelope>()
        try {
            val id = draw(root, source)
            val old = store.freezeFrame(root.snapshot(), 1, 1, 0, 0).also(packets::add)
            repeat(3) { engines.forEach { draw(it, source) } }
            assertEquals(1, counted.argbRegistrations[id], "Sibling engines share one published pixel revision")
            source.setCommittedArgbPixels(intArrayOf(9, 10))
            // A child, rather than the engine that created the texture, publishes this update first.
            repeat(3) { engines.reversed().forEach { draw(it, source) } }
            assertEquals(2, counted.argbRegistrations[id], "Each real edit needs only one new registration")
            val next = store.freezeFrame(root.snapshot(), 2, 1, 0, 0).also(packets::add)
            val oldPixels = old.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
            val nextPixels = next.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
            assertContentEquals(intArrayOf(7, 8), oldPixels.image.pixels)
            assertContentEquals(intArrayOf(9, 10), nextPixels.image.pixels)
            source.o()
            assertNull(store.argbImageView(id))
        } finally {
            packets.forEach(FrameEnvelope::close)
            children.forEach { it.q() }
            source.o()
            targets.forEach { it.o() }
            store.close()
        }
    }

    @Test
    fun `shared stamps distinguish alpha bleed premultiplication and dimensions`() {
        val store = KoolCanvasCpuTextureStore()
        val counted = CountingStore(store)
        val root = KoolGraphicsEngine(counted, shareTextureRegistrations = true)
        val sibling = root.b(null as Texture?) as KoolGraphicsEngine
        val source = root.b(2, 1, true).apply { setCommittedArgbPixels(intArrayOf(0xff123456.toInt(), 0)) }
        try {
            val id = draw(root, source)
            val original = assertNotNull(store.argbImageView(id))
            source.alphaBleedRequired = true
            draw(sibling, source)
            assertEquals(2, counted.argbRegistrations[id])
            val bled = assertNotNull(store.argbImageView(id))
            assertNotSame(original, bled)
            assertContentEquals(intArrayOf(0xff123456.toInt(), 0x00123456), bled.pixels)
            draw(root, source)
            assertEquals(2, counted.argbRegistrations[id])
            source.alphaBleedRequired = false
            draw(root, source)
            assertEquals(3, counted.argbRegistrations[id])
            assertContentEquals(original.pixels, assertNotNull(store.argbImageView(id)).pixels)
            source.setPremultipliedAlpha(true)
            draw(sibling, source)
            draw(root, source)
            assertEquals(1, counted.premultipliedRegistrations[id])
            source.p = 1
            source.q = 2
            draw(root, source)
            draw(sibling, source)
            assertEquals(2, counted.premultipliedRegistrations[id])
            assertEquals(1, assertNotNull(store.argbImageView(id)).width)
            assertEquals(2, assertNotNull(store.argbImageView(id)).height)
        } finally {
            source.o()
            store.close()
        }
    }

    @Test
    fun `siblings keep immediate pixels current without replacing recorded frame fallbacks`() {
        val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(store, shareTextureRegistrations = true)
        val sibling = root.b(null as Texture?) as KoolGraphicsEngine
        val source = root.b(2, 1, true)
        val target = root.b(source, RenderTargetMode.IMMEDIATE) as KoolGraphicsEngine
        try {
            target.b(0xff123456.toInt())
            target.p()
            val id = draw(root, source)
            repeat(3) { draw(sibling, source); draw(root, source) }
            assertContentEquals(IntArray(2) { 0xff123456.toInt() }, assertNotNull(store.argbImageView(id)).pixels)
            source.a(0, 0, 0xff654321.toInt())
            draw(sibling, source)
            draw(root, source)
            assertContentEquals(intArrayOf(0xff654321.toInt(), 0xff123456.toInt()),
                assertNotNull(store.argbImageView(id)).pixels)
            // A CPU-unsupported draw uses the existing recorded Frame fallback, whose pixels
            // must not be replaced by a sibling's cached editable pixel registration.
            target.beginFrame(2, 1)
            target.a("fallback", 0f, 0f, null)
            target.p()
            val recorded = assertNotNull(store.frame(id))
            draw(sibling, source)
            draw(root, source)
            assertEquals(recorded, store.frame(id))
        } finally {
            target.q()
            source.o()
            store.close()
        }
    }

    @Test
    fun `disabled sharing preserves separate registration stamps across engines`() {
        val store = KoolCanvasCpuTextureStore()
        val counted = CountingStore(store)
        val root = KoolGraphicsEngine(counted, shareTextureRegistrations = false)
        val detached = root.b(null as Texture?) as KoolGraphicsEngine
        val destination = root.b(8, 8, true)
        val target = root.b(destination, RenderTargetMode.GPU_TARGET) as KoolGraphicsEngine
        val engines = listOf(root, detached, target)
        val source = root.b(2, 1, true).apply { setCommittedArgbPixels(intArrayOf(7, 8)) }
        try {
            val id = draw(root, source)
            repeat(3) { engines.forEach { draw(it, source) } }
            assertEquals(engines.size, counted.argbRegistrations[id],
                "The control retains one registration stamp per engine")
            source.setCommittedArgbPixels(intArrayOf(9, 10))
            repeat(3) { engines.reversed().forEach { draw(it, source) } }
            assertEquals(engines.size * 2, counted.argbRegistrations[id])
            assertContentEquals(intArrayOf(9, 10), assertNotNull(store.argbImageView(id)).pixels)
        } finally {
            target.q()
            source.o()
            destination.o()
            store.close()
        }
    }

    @Test
    fun `alpha bleed changes invalidate ordinary stamps while sharing is disabled`() {
        val store = KoolCanvasCpuTextureStore()
        val counted = CountingStore(store)
        val root = KoolGraphicsEngine(counted, shareTextureRegistrations = false)
        val source = root.b(2, 1, true).apply { setCommittedArgbPixels(intArrayOf(0xff123456.toInt(), 0)) }
        try {
            val id = draw(root, source)
            source.alphaBleedRequired = true
            draw(root, source)
            assertEquals(2, counted.argbRegistrations[id])
            assertContentEquals(intArrayOf(0xff123456.toInt(), 0x00123456),
                assertNotNull(store.argbImageView(id)).pixels)
            source.alphaBleedRequired = false
            draw(root, source)
            assertEquals(3, counted.argbRegistrations[id])
            assertContentEquals(intArrayOf(0xff123456.toInt(), 0), assertNotNull(store.argbImageView(id)).pixels)
        } finally {
            source.o()
            store.close()
        }
    }
}
