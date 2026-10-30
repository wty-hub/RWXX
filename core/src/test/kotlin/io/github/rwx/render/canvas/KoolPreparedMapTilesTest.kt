package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.GamePaint
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import kotlin.test.*

class KoolPreparedMapTilesTest {
    private fun textureDraws(frame: KoolCanvasFrame): List<KoolCanvasCommand.DrawTexture> = buildList {
        for (command in frame.commands) when (command) {
            is KoolCanvasCommand.DrawTexture -> add(command)
            is KoolCanvasCommand.DrawTextureBatch -> command.quads.forEachDraw(command.texture, command.paint, command.state) { add(it) }
            else -> Unit
        }
    }

    private fun cell(root: KoolGraphicsEngine): KoolGraphicsEngine =
        (root.b(root.b(128, 128, true), RenderTargetMode.GPU_TARGET) as KoolGraphicsEngine)
            .also { it.beginFrame(128, 128) }

    @Test fun `prepared and ordinary recording preserve coordinates state alpha and interleaving`() {
        val store = KoolCanvasCpuTextureStore()
        try {
            val root = KoolGraphicsEngine(textureStore = store)
            val control = cell(root)
            val candidate = cell(root)
            val opaque = root.a(20, 20, false).apply { j = IntArray(400) { -1 } }
            val alpha = root.a(20, 20, true).apply { j = IntArray(400) { 0x804488cc.toInt() } }
            val paint = GamePaint().apply { a(true) }
            val writer = assertNotNull(candidate.prepareMapTextureDraw(paint, true))
            val source = Rect(1, 2, 18, 19)
            for (scale in listOf(0.2f, 0.35f, 0.49f, 0.5f, 0.99f, 1f, 1.5f)) {
                for (engine in listOf(control, candidate)) {
                    engine.a(true)
                    engine.a(Rect(-3, 1, 108, 119))
                }
                for (texture in listOf(opaque, alpha, opaque)) {
                    // Preserve MapLayer's multiplication, subtraction and one-sided rounding.
                    val left = (3 * (20f * scale)) + 0f
                    val right = ((3 + 1) * (20f * scale)) + 0.5f * scale
                    val destination = RectF((left - 1.25f).toInt().toFloat(), -2.5f, right - 1.25f, 40.75f)
                    control.a(texture, source, destination, paint)
                    assertTrue(writer.draw(texture, source, destination))
                }
                // A non-atlas draw and a fog draw remain between the same tile draws.
                for (engine in listOf(control, candidate)) {
                    engine.f()
                    engine.a(opaque, source, RectF(11f, 13f, 27f, 31f), null)
                    engine.beginDrawRole(GraphicsEngine.DRAW_ROLE_MAP_FOG, -1)
                    engine.a(Rect(2, 4, 10, 12), GamePaint().apply { b(0x80000000.toInt()) })
                    engine.endDrawRole()
                    engine.b(2f, 3f)
                }
            }
            writer.close()
            assertEquals(control.snapshot(), candidate.snapshot())
        } finally { store.close() }
    }

    @Test fun `scope owns paint snapshot next scope reads mutations and closed or reset scope declines`() {
        val store = KoolCanvasCpuTextureStore()
        try {
            val root = KoolGraphicsEngine(textureStore = store)
            val engine = cell(root)
            val texture = root.a(8, 8, true).apply { j = IntArray(64) { -1 } }
            val source = Rect(0, 0, 8, 8)
            val destination = RectF(0f, 0f, 8f, 8f)
            val paint = GamePaint()
            val first = assertNotNull(engine.prepareMapTextureDraw(paint, true))
            assertTrue(first.draw(texture, source, destination))
            first.close()
            paint.n = 0x804488cc.toInt() // Direct legacy fields bypass stateRevision.
            paint.a(true)
            val second = assertNotNull(engine.prepareMapTextureDraw(paint, true))
            assertTrue(second.draw(texture, source, destination))
            second.close()
            val commands = textureDraws(engine.snapshot())
            assertEquals(-1, commands[0].paint.color.argb)
            assertEquals(0x804488cc.toInt(), commands[1].paint.color.argb)
            assertEquals(KoolCanvasTextureFilter.Linear, commands[1].paint.textureFilter)
            val count = engine.snapshot().commands.size
            assertFalse(first.draw(texture, source, destination))
            assertEquals(count, engine.snapshot().commands.size)
            val stale = assertNotNull(engine.prepareMapTextureDraw(paint, true))
            engine.beginFrame(128, 128)
            assertFalse(stale.draw(texture, source, destination))
            assertTrue(engine.snapshot().commands.isEmpty())
        } finally { store.close() }
    }

    @Test fun `each draw commits pending atlas changes and preserves older frozen pixels`() {
        val store = KoolCanvasCpuTextureStore()
        try {
            val root = KoolGraphicsEngine(textureStore = store)
            val atlas = root.b(8, 8, true)
            val atlasGraphics = root.b(atlas, RenderTargetMode.IMMEDIATE)
            val engine = cell(root)
            val writer = assertNotNull(engine.prepareMapTextureDraw(null, true))
            val source = Rect(0, 0, 8, 8)
            val destination = RectF(0f, 0f, 8f, 8f)
            atlasGraphics.b(0xffff0000.toInt())
            val revisionBefore = atlas.e
            assertTrue(writer.draw(atlas, source, destination))
            assertEquals(revisionBefore + 1, atlas.e)
            val first = store.freezeFrame(engine.snapshot(), 1, 1, 0, 0)
            try {
                atlasGraphics.b(0xff0000ff.toInt())
                val revision = atlas.e
                assertTrue(writer.draw(atlas, source, destination))
                assertEquals(revision + 1, atlas.e)
                val second = store.freezeFrame(engine.snapshot(), 2, 1, 0, 0)
                try {
                    val oldRef = textureDraws(first.frame).single().texture
                    val newRef = textureDraws(second.frame).last().texture
                    assertNotEquals(oldRef.id, newRef.id)
                    assertEquals(0xffff0000.toInt(), assertIs<FrozenCanvasResource.Pixels>(first.resourceLease.resources()[oldRef.id]).image.pixels[0])
                    assertEquals(0xff0000ff.toInt(), assertIs<FrozenCanvasResource.Pixels>(second.resourceLease.resources()[newRef.id]).image.pixels[0])
                } finally { second.close() }
            } finally { first.close() }
            writer.close()
        } finally { store.close() }
    }

    @Test fun `scope is opt in GPU only and unsupported effects retain ordinary path`() {
        val store = KoolCanvasCpuTextureStore()
        try {
            val root = KoolGraphicsEngine(textureStore = store)
            assertNull(root.prepareMapTextureDraw(null, true))
            val cpu = root.b(root.b(8, 8, true), RenderTargetMode.IMMEDIATE) as KoolGraphicsEngine
            assertNull(cpu.prepareMapTextureDraw(null, true))
            val engine = cell(root)
            assertNull(engine.prepareMapTextureDraw(null, false))
            val paint = GamePaint().apply {
                setDisplacementEffect(KoolDisplacementEffect().apply { configure(root.a(8, 8, true), 1f) })
            }
            assertNull(engine.prepareMapTextureDraw(paint, true))
            val writer = assertNotNull(engine.prepareMapTextureDraw(null, true))
            assertFalse(writer.draw(null, Rect(0, 0, 1, 1), RectF(0f, 0f, 1f, 1f)))
            assertTrue(engine.snapshot().commands.isEmpty())
            writer.close()
        } finally { store.close() }
    }

    @Test fun `primitive writer matches ordinary batching through clips fog transforms and boundaries`() {
        for (batches in listOf(false, true)) {
            val control = KoolCanvasCommandBuffer(textureBatches = batches, fogBatches = true)
            val candidate = KoolCanvasCommandBuffer(textureBatches = batches, fogBatches = true)
            val texture = KoolCanvasTextureRef(KoolCanvasTextureId("prepared-coordinates"), 32, 32)
            fun draw(source: KoolCanvasRect, destination: KoolCanvasRect, paint: KoolCanvasPaint = KoolCanvasPaint.Default) {
                control.drawTexture(texture, destination, paint, source)
                candidate.drawTextureCoordinates(texture, source.left, source.top, source.right, source.bottom,
                    destination.left, destination.top, destination.right, destination.bottom, paint)
            }
            val source = KoolCanvasRect(1f, 2f, 13f, 21f)
            repeat(4100) { draw(source, KoolCanvasRect(it.toFloat(), -0f, it + 10.5f, 20f)) }
            for (buffer in listOf(control, candidate)) buffer.clip(KoolCanvasRect(-5f, 3f, 24f, 25f))
            draw(source, KoolCanvasRect(-1f, 2f, 14f, 20f))
            for (buffer in listOf(control, candidate)) buffer.setDrawRole(KoolCanvasDrawRole.MapFog)
            draw(source, KoolCanvasRect(0f, 0f, 20f, 20f), KoolCanvasPaint.Default.copy(color = KoolCanvasColor(0x80000000.toInt())))
            for (buffer in listOf(control, candidate)) {
                buffer.setDrawRole(KoolCanvasDrawRole.Generic)
                buffer.translate(0.5f, -0.5f)
            }
            draw(source, KoolCanvasRect(0f, 0f, 0f, 20f))
            draw(source, KoolCanvasRect(Float.NaN, 0f, 10f, 20f))
            draw(source, KoolCanvasRect(20f, 0f, 0f, 20f))
            assertEquals(control.snapshot(), candidate.snapshot(), "batching=$batches")
            assertEquals(control.signature, candidate.signature, "batching=$batches")
        }
    }
}
