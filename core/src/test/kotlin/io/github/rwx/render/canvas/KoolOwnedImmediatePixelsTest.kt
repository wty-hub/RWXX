package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import io.github.rwx.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolOwnedImmediatePixelsTest {
    @Test
    fun `ordinary mutable registration detaches while owned registration adopts the raster array`() {
        val store = KoolCanvasCpuTextureStore()
        val mutableId = KoolCanvasTextureId("mutable-pixels")
        val ownedId = KoolCanvasTextureId("owned-pixels")
        val mutable = intArrayOf(0xff123456.toInt(), 0xff654321.toInt())
        val original = mutable.copyOf()
        store.registerArgb(mutableId, 2, 1, mutable, alphaBleed = false)
        val detached = assertNotNull(store.argbImageView(mutableId)).pixels
        assertNotSame(mutable, detached)
        mutable.fill(0)
        assertContentEquals(original, detached, "ordinary registration must detach mutable caller pixels")

        val owned = original.copyOf()
        store.registerOwnedArgb(ownedId, 2, 1, owned, alphaBleed = false)
        assertSame(owned, assertNotNull(store.argbImageView(ownedId)).pixels)
    }

    @Test
    fun `owned alpha bleeding still copies and preserves source alpha`() {
        val store = KoolCanvasCpuTextureStore()
        val id = KoolCanvasTextureId("owned-alpha-pixels")
        val pixels = intArrayOf(0xff2468ac.toInt(), 0x00ffffff, 0x00000000, 0x00112233)
        val original = pixels.copyOf()
        store.registerOwnedArgb(id, 2, 2, pixels, alphaBleed = true)
        val image = assertNotNull(store.argbImageView(id))
        assertNotSame(pixels, image.pixels)
        assertContentEquals(original, pixels, "bleeding must not write into the raster result")
        assertContentEquals(intArrayOf(0xff2468ac.toInt(), 0x002468ac, 0x002468ac, 0x002468ac), image.pixels)
    }

    @Test
    fun `immediate adoption isolates legacy edits and old leases across redraw and release`() {
        val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val texture = root.b(4, 3, true)
        val target = root.b(texture, RenderTargetMode.IMMEDIATE)
        var packet: FrameEnvelope? = null
        try {
            root.beginFrame(4, 3)
            root.b(texture, 0f, 0f, null)
            val frame = root.snapshot()
            val id = (frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            target.a(0, KoolCanvasBlendMode.Clear)
            target.a(Rect(0, 0, 4, 3), KoolPaint().apply { setColor(0xff123456.toInt()) })
            target.p()
            val adopted = assertNotNull(store.argbImageView(id)).pixels
            assertSame(texture.argbPixelsRef, adopted, "pooled raster and legacy texture share read-only committed pixels")
            val original = adopted.copyOf()
            root.beginFrame(4, 3)
            root.b(texture, 0f, 0f, null)
            assertSame(adopted, assertNotNull(store.argbImageView(id)).pixels, "root must reuse the target's matching registration")
            val completed = store.freezeFrame(frame, 1L, 1L, 1, 1L)
            packet = completed

            texture.a(0, 0, 0xffe0a030.toInt())
            assertNotSame(texture.argbPixelsRef, adopted, "legacy edits must detach before writing")
            assertContentEquals(original, adopted, "legacy pixel edits must not modify the adopted array")
            target.a(0, KoolCanvasBlendMode.Clear)
            target.a(Rect(0, 0, 4, 3), KoolPaint().apply { setColor(0xff654321.toInt()) })
            target.p()
            val next = assertNotNull(store.argbImageView(id)).pixels
            assertNotSame(adopted, next, "redraw must allocate its own writable result")
            assertContentEquals(IntArray(12) { 0xff654321.toInt() }, next)
            target.q()
            texture.o()
            assertNull(store.argbImageView(id))
            val frozen = completed.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
            assertSame(adopted, frozen.image.pixels)
            assertContentEquals(original, frozen.image.pixels, "old lease must retain immutable adopted pixels after disposal")
        } finally {
            packet?.close()
            target.q()
            texture.o()
        }
    }

    @Test
    fun `immediate registration reuse detects edits alpha changes unregister and external replacement`() {
        val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val texture = root.b(2, 2, true)
        val target = root.b(texture, RenderTargetMode.IMMEDIATE)
        fun draw(): KoolCanvasTextureId {
            root.beginFrame(2, 2)
            root.b(texture, 0f, 0f, null)
            return (root.snapshot().commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        }
        try {
            target.b(0xff123456.toInt())
            target.p()
            val id = draw()
            val original = assertNotNull(store.argbImageView(id)).pixels
            draw()
            assertSame(original, assertNotNull(store.argbImageView(id)).pixels, "unchanged target must not register twice")

            texture.a(0, 0, 0x80123456.toInt())
            draw()
            val edited = assertNotNull(store.argbImageView(id)).pixels
            assertNotSame(original, edited, "legacy edits must register a detached image")
            assertContentEquals(assertNotNull(texture.argbPixelsRef), edited)

            texture.j = intArrayOf(0xff203040.toInt(), 0x00ffffff, 0, 0)
            texture.p()
            texture.r()
            draw()
            val committedEdit = assertNotNull(store.argbImageView(id)).pixels
            assertContentEquals(intArrayOf(0xff203040.toInt(), 0x00ffffff, 0, 0), committedEdit)
            texture.alphaBleedRequired = true
            draw()
            val bled = assertNotNull(store.argbImageView(id)).pixels
            assertNotSame(committedEdit, bled, "alpha-bleed changes must invalidate registration reuse")
            assertContentEquals(intArrayOf(0xff203040.toInt(), 0x00203040, 0x00203040, 0x00203040), bled)
            texture.alphaBleedRequired = false
            draw()
            assertContentEquals(committedEdit, assertNotNull(store.argbImageView(id)).pixels)

            store.unregister(id)
            draw()
            assertContentEquals(committedEdit, assertNotNull(store.argbImageView(id)).pixels, "unregister must force restoration")
            store.registerArgb(id, 2, 2, IntArray(4) { 0xffba9876.toInt() }, alphaBleed = false)
            draw()
            assertContentEquals(committedEdit, assertNotNull(store.argbImageView(id)).pixels, "external replacement must not satisfy cached image identity")

            // Rendering the original pixels again must also restore Texture/store contents after
            // legacy edits, even when the raster result equals a previous commit.
            target.b(0xff123456.toInt())
            target.p()
            draw()
            assertContentEquals(IntArray(4) { 0xff123456.toInt() }, assertNotNull(store.argbImageView(id)).pixels)
            assertContentEquals(IntArray(4) { 0xff123456.toInt() }, texture.argbPixelsRef)
            target.q()
            texture.o()
            assertNull(store.argbImageView(id))
        } finally {
            target.q()
            texture.o()
        }
    }

    @Test
    fun `unsupported immediate fallback matches a recorded target and survives root draws until a legacy edit`() {
        val seed = 0xff123456.toInt()
        val clear = 0xff654321.toInt()
        val edit = 0x80102030.toInt()
        val immediateStore = KoolCanvasCpuTextureStore()
        val recordedStore = KoolCanvasCpuTextureStore()
        val immediateRoot = KoolGraphicsEngine(textureStore = immediateStore)
        val recordedRoot = KoolGraphicsEngine(textureStore = recordedStore)
        val immediateTexture = immediateRoot.b(4, 3, true)
        val recordedTexture = recordedRoot.b(4, 3, true)
        val immediate = immediateRoot.b(immediateTexture, RenderTargetMode.IMMEDIATE)
        val recorded = recordedRoot.b(recordedTexture, RenderTargetMode.DEFAULT)
        fun drawImmediate(): KoolCanvasFrame {
            immediateRoot.beginFrame(4, 3)
            immediateRoot.b(immediateTexture, 0f, 0f, null)
            return immediateRoot.snapshot()
        }
        fun drawRecorded(): KoolCanvasFrame {
            recordedRoot.beginFrame(4, 3)
            recordedRoot.b(recordedTexture, 0f, 0f, null)
            return recordedRoot.snapshot()
        }
        fun id(frame: KoolCanvasFrame) = (frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        var pixelLease: FrameEnvelope? = null
        var frameLease: FrameEnvelope? = null
        try {
            immediate.b(seed)
            immediate.p()
            recorded.b(seed)
            recorded.p()
            val seedFrame = drawImmediate()
            val immediateId = id(seedFrame)
            val recordedId = id(drawRecorded())
            val seedPixels = assertNotNull(immediateStore.argbImageView(immediateId)).pixels
            val oldPixels = immediateStore.freezeFrame(seedFrame, 1, 1, 0, 0)
            pixelLease = oldPixels

            for (target in listOf(immediate, recorded)) {
                target.b(clear)
                target.a(0f, 0f, 4f, 3f, KoolPaint().apply { setColor(0xffe08020.toInt()) })
                target.p()
            }
            assertNull(immediateStore.argbImageView(immediateId), "unsupported commands must publish a Frame")
            assertEquals(assertNotNull(recordedStore.frame(recordedId)), assertNotNull(immediateStore.frame(immediateId)))
            var rootFrame = seedFrame
            repeat(2) {
                drawRecorded()
                rootFrame = drawImmediate()
                val fallback = assertNotNull(immediateStore.frame(immediateId), "root draw must retain the fallback Frame")
                assertEquals(assertNotNull(recordedStore.frame(recordedId)), fallback, "IMMEDIATE fallback must match the independent recorded path")
                assertTrue(fallback.commands.any { command -> command is KoolCanvasCommand.DrawLine })
                assertNull(immediateStore.argbImageView(immediateId), "stale seed pixels must not replace the fallback")
            }
            val oldFrame = immediateStore.freezeFrame(rootFrame, 2, 1, 0, 0)
            frameLease = oldFrame
            val frozenFallback = oldFrame.resourceLease.resources().values.single() as FrozenCanvasResource.Frame
            val fallbackCommands = frozenFallback.frame.commands.toList()

            // The empty flush performed by root draw must not relabel this edit as a Frame commit.
            immediateTexture.a(0, 0, edit)
            drawImmediate()
            assertNull(immediateStore.frame(immediateId), "a real legacy pixel edit must invalidate the Frame registration")
            val expected = IntArray(12) { seed }.apply { this[0] = edit }
            assertContentEquals(expected, assertNotNull(immediateStore.argbImageView(immediateId)).pixels)
            immediate.b(clear)
            immediate.p()
            repeat(2) {
                drawImmediate()
                assertNull(immediateStore.frame(immediateId), "empty flush must not restore an obsolete unsupported fallback")
                assertContentEquals(IntArray(12) { clear }, assertNotNull(immediateStore.argbImageView(immediateId)).pixels)
            }
            immediate.q()
            immediateTexture.o()
            assertNull(immediateStore.frame(immediateId))
            assertNull(immediateStore.argbImageView(immediateId))
            val frozenPixels = oldPixels.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
            assertSame(seedPixels, frozenPixels.image.pixels)
            assertContentEquals(IntArray(12) { seed }, frozenPixels.image.pixels, "old pixel lease must remain immutable")
            assertEquals(fallbackCommands, frozenFallback.frame.commands, "old fallback lease must survive replacement and release")
        } finally {
            frameLease?.close()
            pixelLease?.close()
            immediate.q()
            recorded.q()
            immediateTexture.o()
            recordedTexture.o()
        }
    }

    @Test
    fun `other texture stores retain the ordinary registration path`() {
        val delegate = KoolCanvasCpuTextureStore()
        var registeredInput: IntArray? = null
        val otherStore = object : KoolCanvasTextureStore by delegate {
            override fun registerArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray, alphaBleed: Boolean) {
                registeredInput = argbPixels
                delegate.registerArgb(id, width, height, argbPixels, alphaBleed)
            }
        }
        val root = KoolGraphicsEngine(textureStore = otherStore)
        val texture = root.b(2, 2, true)
        val target = root.b(texture, RenderTargetMode.IMMEDIATE)
        try {
            root.beginFrame(2, 2)
            root.b(texture, 0f, 0f, null)
            val id = (root.snapshot().commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            target.b(0xff18324a.toInt())
            target.p()
            val result = assertNotNull(registeredInput)
            val registered = assertNotNull(delegate.argbImageView(id)).pixels
            assertNotSame(result, registered, "other stores must receive ordinary registerArgb")
            assertContentEquals(result, registered)
        } finally {
            target.q()
            texture.o()
        }
    }
}
