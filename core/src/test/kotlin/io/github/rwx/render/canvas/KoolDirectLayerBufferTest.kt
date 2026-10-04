package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import com.corrodinggames.rts.gameFramework.graphics.Texture
import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KoolDirectLayerBufferTest {
    @Test
    fun `direct cell matches scratch copy for opaque terrain alpha edges and fog`() {
        val scenarios = listOf(
            Scene("opaque terrain", fog = false, decorations = false),
            Scene("alpha world boundary", fog = false),
            Scene("fog and scorch", fog = true),
            Scene("fractional scale", fog = true, scale = 0.75f, offset = -1.25f, linear = true),
            Scene("half atlas scale", fog = true, scale = 0.4f, offset = 0.5f, linear = true),
        )
        for (scene in scenarios) {
            CellFixture().use { copied ->
                CellFixture().use { direct ->
                    assertTrue(direct.root.prefersDirectLayerBufferRendering())
                    copied.renderThroughScratch(scene)
                    direct.renderDirect(scene)
                    assertContentEquals(copied.pixels(), direct.pixels(), scene.name)
                    assertEquals(0, direct.pixels()[0], "${scene.name}: world exterior must remain clear")
                    assertTrue(direct.pixels().any { it ushr 24 == 255 }, "${scene.name}: missing opaque terrain")
                    if (!scene.fog && scene.scale == 1f && scene.offset == 0f) {
                        assertEquals(0xff386028.toInt(), direct.pixels()[4 + 4 * CELL_SIZE], "${scene.name}: opaque terrain sample")
                    }
                    if (scene.decorations) {
                        assertTrue(direct.pixels().any { it ushr 24 in 1..254 }, "${scene.name}: missing alpha boundary")
                        if (!scene.fog && scene.scale == 1f && scene.offset == 0f) {
                            assertEquals(0x40108020, direct.pixels()[2 + 12 * CELL_SIZE], "alpha must survive at the world boundary")
                            assertEquals(0, direct.pixels()[2 + 10 * CELL_SIZE], "transparent artwork RGB must not color the boundary")
                        }
                    }
                    if (scene.fog) {
                        CellFixture().use { noFog ->
                            noFog.renderDirect(scene.copy(fog = false))
                            assertTrue(!direct.pixels().contentEquals(noFog.pixels()), "${scene.name}: fog must darken terrain")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `repeated clear and redraw match while completed cell leases retain old pixels`() {
        CellFixture().use { copied ->
            CellFixture().use { direct ->
                val first = Scene("first fog frame", fog = true)
                copied.renderThroughScratch(first)
                direct.renderDirect(first)
                val original = direct.pixels().copyOf()
                assertContentEquals(copied.pixels(), original)
                val copiedPacket = copied.freezeCell()
                val directPacket = direct.freezeCell()
                try {
                    copied.renderThroughScratch(Scene("smaller redraw", fog = false, tileCount = 1))
                    direct.renderDirect(Scene("smaller redraw", fog = false, tileCount = 1))
                    assertContentEquals(copied.pixels(), direct.pixels(), "smaller redraw")
                    val removedPixel = 26 + 26 * CELL_SIZE
                    assertTrue(original[removedPixel] ushr 24 > 0, "fixture must cover the removed terrain")
                    assertEquals(0, direct.pixels()[removedPixel], "clear must remove previous terrain and fog")

                    copied.renderThroughScratch(Scene("recycled edge cell", fog = true, offset = -6f))
                    direct.renderDirect(Scene("recycled edge cell", fog = true, offset = -6f))
                    assertContentEquals(copied.pixels(), direct.pixels(), "recycled edge cell")

                    copied.clearThroughScratch()
                    direct.clearDirect()
                    assertContentEquals(IntArray(CELL_SIZE * CELL_SIZE), copied.pixels(), "scratch clear")
                    assertContentEquals(copied.pixels(), direct.pixels(), "direct clear")
                    copied.releaseTextures()
                    direct.releaseTextures()
                    for (packet in listOf(copiedPacket, directPacket)) {
                        val resource = packet.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
                        assertContentEquals(original, resource.image.pixels, "redraw and disposal must preserve an old lease")
                    }
                } finally {
                    copiedPacket.close()
                    directPacket.close()
                }
            }
        }
    }

    private data class Scene(
        val name: String,
        val fog: Boolean,
        val decorations: Boolean = true,
        val scale: Float = 1f,
        val offset: Float = 0f,
        val linear: Boolean = false,
        val tileCount: Int = 3,
    )

    private class CellFixture : AutoCloseable {
        private val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(textureStore = store)
        private val cell = root.b(CELL_SIZE, CELL_SIZE, true)
        private val scratch = root.b(CELL_SIZE, CELL_SIZE, true)
        private val cellGraphics = root.b(cell, RenderTargetMode.IMMEDIATE)
        private val scratchGraphics = root.b(scratch, RenderTargetMode.IMMEDIATE)
        private val terrain = texture(intArrayOf(0xff386028.toInt(), 0xff719342.toInt(), 0xffa18854.toInt(), 0xff496a36.toInt()))
        // Deliberately include nonzero RGB at alpha zero, as decoded alpha artwork may contain it.
        private val decoration = texture(intArrayOf(0x00ffffff, 0x80402080.toInt(), 0x40108020, 0xffd0a050.toInt()))
        private val scorch = texture(intArrayOf(0x00202020, 0x80302010.toInt(), 0x60402010, 0x40201008))
        private val fog = texture(intArrayOf(0x00000000, 0x80000000.toInt(), 0xb0000000.toInt(), 0xff000000.toInt()))
        private var released = false

        init {
            // Both paths start with dirty cell pixels, and scratch itself also contains stale data.
            cellGraphics.b(0xffea10ca.toInt())
            cellGraphics.p()
            scratchGraphics.b(0xff1387bd.toInt())
            scratchGraphics.p()
        }

        private fun texture(pixels: IntArray): Texture = root.a(2, 2, true).apply {
            j = pixels.copyOf()
            p()
        }

        fun renderThroughScratch(scene: Scene) {
            drawScene(scratchGraphics, scene)
            cellGraphics.a(0, KoolCanvasBlendMode.Clear)
            cellGraphics.b(scratch, 0f, 0f, null)
            cellGraphics.p()
        }

        fun renderDirect(scene: Scene) {
            drawScene(cellGraphics, scene)
        }

        private fun drawScene(graphics: GraphicsEngine, scene: Scene) {
            graphics.a(0, KoolCanvasBlendMode.Clear)
            graphics.a(Rect(2, 2, CELL_SIZE - 2, CELL_SIZE - 2))
            val paint = KoolPaint().apply { setFilterBitmap(scene.linear) }
            graphics.a(true)
            for (y in 0 until scene.tileCount) {
                for (x in 0 until scene.tileCount) {
                    val left = 4f + scene.offset + x * 8f * scene.scale
                    val top = 4f + scene.offset + y * 8f * scene.scale
                    val overlap = if (scene.scale < 1f) 0.5f * scene.scale else 0f
                    graphics.a(terrain, Rect(0, 0, 2, 2), RectF(left, top,
                        left + 8f * scene.scale + overlap, top + 8f * scene.scale + overlap), paint)
                }
            }
            graphics.f()
            if (scene.decorations) {
                graphics.a(decoration, Rect(0, 0, 2, 2), RectF(2f, 10f, 6f, 14f), paint)
                graphics.a(scorch, Rect(0, 0, 2, 2), RectF(13f, 10f, 19f, 16f), paint)
            }
            if (scene.fog) {
                graphics.a(false)
                graphics.a(RectF(4f, 4f, 16f, 28f), KoolPaint().apply { setColor(0x80000000.toInt()) })
                graphics.a(fog, Rect(0, 0, 2, 2), RectF(16f, 4f, 28f, 16f), paint)
            }
            graphics.f()
            graphics.p()
        }

        fun clearThroughScratch() {
            scratchGraphics.a(0, KoolCanvasBlendMode.Clear)
            scratchGraphics.p()
            cellGraphics.a(0, KoolCanvasBlendMode.Clear)
            cellGraphics.b(scratch, 0f, 0f, null)
            cellGraphics.p()
        }

        fun clearDirect() {
            cellGraphics.a(0, KoolCanvasBlendMode.Clear)
            cellGraphics.p()
        }

        fun pixels(): IntArray = assertNotNull(cell.argbPixelsRef)

        fun freezeCell(): FrameEnvelope {
            root.beginFrame(CELL_SIZE, CELL_SIZE)
            root.b(cell, 0f, 0f, null)
            return store.freezeFrame(root.snapshot(), 1L, 1L, 1, 1L)
        }

        fun releaseTextures() {
            if (released) return
            released = true
            cellGraphics.q()
            scratchGraphics.q()
            listOf(cell, scratch, terrain, decoration, scorch, fog).forEach(Texture::o)
        }

        override fun close() = releaseTextures()
    }

    private companion object {
        const val CELL_SIZE = 32
    }
}
