package io.github.rwx.render.canvas

import io.github.rwx.geometry.Rect
import kotlin.test.*

class KoolPaintSnapshotTest {
    @Test
    fun `freeze detaches mutable command container while retaining immutable values`() {
        val paint = KoolCanvasPaint(textSize = 23f)
        val commands = mutableListOf<KoolCanvasCommand>(
            KoolCanvasCommand.DrawRect(KoolCanvasRect.fromSize(8f, 8f), paint, KoolCanvasState.Default),
            KoolCanvasCommand.DrawText("immutable", KoolCanvasPoint(2f, 6f), paint, KoolCanvasState.Default))
        val original = commands.toList()
        val store = KoolCanvasCpuTextureStore()
        store.freezeFrame(KoolCanvasFrame(KoolCanvasViewport(8, 8), commands), 1, 1, 0, 0).use { frame ->
            commands.clear()
            assertEquals(original, frame.frame.commands)
            assertFailsWith<UnsupportedOperationException> { (frame.frame.commands as MutableList).clear() }
            if (System.getenv("RWX_SHARE_IMMUTABLE_COMMANDS") == "1") for (index in original.indices)
                assertSame(original[index], frame.frame.commands[index])
        }
        store.close()
    }

    @Test
    fun `frozen paints survive direct field edits and overridden legacy getters`() {
        var overriddenColor = 0xff335577.toInt()
        val paint = object : KoolPaint() { override fun e() = overriddenColor }
        val store = KoolCanvasCpuTextureStore()
        val engine = KoolGraphicsEngine(textureStore = store)
        fun record(): FrameEnvelope {
            engine.beginFrame(8, 8)
            engine.a(Rect(0, 0, 8, 8), paint)
            return store.freezeFrame(engine.snapshot(), 1, 1, 0, 0)
        }
        val old = record()
        val oldPaint = (old.frame.commands.single() as KoolCanvasCommand.DrawRect).paint
        record().use { repeated ->
            val repeatedPaint = (repeated.frame.commands.single() as KoolCanvasCommand.DrawRect).paint
            assertEquals(oldPaint, repeatedPaint)
            if (System.getenv("RWX_REUSE_PAINT_SNAPSHOTS") == "1") assertSame(oldPaint, repeatedPaint)
        }
        val revision = paint.getStateRevision()
        paint.q = 27f; paint.p = KoolPaint.Align.RIGHT; paint.m = KoolPaint.Style.STROKE; paint.o = 4f
        overriddenColor = 0xffcc8844.toInt()
        assertEquals(revision, paint.getStateRevision(), "direct writes and getters bypass revision")
        record().use { changed ->
            val actual = (changed.frame.commands.single() as KoolCanvasCommand.DrawRect).paint
            assertEquals(overriddenColor, actual.color.argb)
            assertEquals(27f, actual.textSize); assertEquals(KoolCanvasTextAlign.Right, actual.textAlign)
            assertEquals(KoolCanvasPaintStyle.Stroke, actual.style); assertEquals(4f, actual.strokeWidth)
        }
        assertEquals(0xff335577.toInt(), oldPaint.color.argb)
        assertEquals(16f, oldPaint.textSize); assertEquals(KoolCanvasPaintStyle.Fill, oldPaint.style)
        old.close(); store.close()
    }
}
