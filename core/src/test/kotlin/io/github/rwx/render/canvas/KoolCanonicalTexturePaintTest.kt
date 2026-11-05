package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.GamePaint
import com.corrodinggames.rts.gameFramework.graphics.Texture
import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import kotlin.test.*

class KoolCanonicalTexturePaintTest {
    private inline fun withEngine(action: (KoolGraphicsEngine, KoolCanvasCpuTextureStore) -> Unit) {
        val store = KoolCanvasCpuTextureStore()
        try {
            action(KoolGraphicsEngine(textureStore = store).also { it.beginFrame(32, 32) }, store)
        } finally { store.close() }
    }

    private fun texture(engine: KoolGraphicsEngine, alpha: Boolean = true): Texture =
        engine.a(8, 8, alpha).apply { j = IntArray(64) { -1 } }

    private fun draw(engine: KoolGraphicsEngine, texture: Texture, paint: KoolPaint) {
        engine.a(texture, Rect(0, 0, 8, 8), RectF(1f, 2f, 9f, 10f), paint)
    }

    private fun KoolCanvasCommand.paint(): KoolCanvasPaint = when (this) {
        is KoolCanvasCommand.DrawTexture -> paint
        is KoolCanvasCommand.DrawTextureRepeat -> paint
        is KoolCanvasCommand.DrawRect -> paint
        else -> error("Unexpected command $this")
    }

    @Test fun `ordinary repeated and tiled textures retain existing white paint canonicalization`() = withEngine { engine, _ ->
        val texture = texture(engine)
        val paint = KoolPaint().apply {
            // These fields are intentionally non-default: the existing final texture
            // canonicalization discards them, whereas primitive/text recording retains them.
            m = KoolPaint.Style.STROKE; o = 7f; q = 29f; p = KoolPaint.Align.RIGHT
            setTypeface(KoolTypeface.e)
        }
        for (linear in listOf(false, true)) {
            engine.beginFrame(32, 32)
            paint.a(linear)
            draw(engine, texture, paint)
            engine.a(texture, Rect(0, 0, 8, 8), RectF(0f, 0f, 24f, 8f), paint, 3)
            engine.a(texture, Rect(0, 0, 16, 8), paint)
            val expected = if (linear) KoolCanvasPaint.Default else KoolCanvasPaint.DefaultNearest
            val commands = engine.snapshot().commands
            assertEquals(4, commands.size)
            commands.forEach { assertSame(expected, it.paint()) }
            engine.a(Rect(0, 0, 4, 4), paint)
            val primitive = engine.snapshot().commands.last().paint()
            assertEquals(KoolCanvasPaintStyle.Stroke, primitive.style)
            assertEquals(7f, primitive.strokeWidth)
            assertEquals(29f, primitive.textSize)
            assertEquals(KoolCanvasTextAlign.Right, primitive.textAlign)
            assertEquals(KoolTypeface.e.koolKey, primitive.typefaceKey)
        }
    }

    @Test fun `legacy getters execute in their original order and direct writes affect the next immutable paint`() = withEngine { engine, _ ->
        val texture = texture(engine)
        val calls = mutableListOf<String>()
        var currentColor = -1
        val paint = object : KoolPaint() {
            override fun h(): KoolColorFilter? { calls += "filter"; return null }
            override fun e(): Int { calls += "color"; return currentColor }
            override fun c(): Boolean { calls += "filtering"; return true }
            override fun d(): KoolPaint.Style { calls += "style"; return m }
            override fun g(): Float { calls += "width"; return o }
            override fun k(): Float { calls += "size"; return q }
            override fun j(): KoolPaint.Align? { calls += "align"; return p }
            override fun i(): KoolTypeface? { calls += "typeface"; return null }
        }
        calls.clear()
        val revision = paint.getStateRevision()
        draw(engine, texture, paint)
        assertEquals(listOf("filter", "color", "filtering", "style", "width", "size", "align", "typeface"), calls)
        val old = engine.snapshot().commands.single().paint()
        assertSame(KoolCanvasPaint.Default, old)
        currentColor = 0x80335577.toInt()
        paint.m = KoolPaint.Style.STROKE; paint.o = 5f; paint.q = 31f; paint.p = KoolPaint.Align.CENTER
        assertEquals(revision, paint.getStateRevision())
        draw(engine, texture, paint)
        val changed = engine.snapshot().commands.last().paint()
        assertEquals(currentColor, changed.color.argb)
        assertEquals(KoolCanvasPaintStyle.Stroke, changed.style)
        assertEquals(5f, changed.strokeWidth)
        assertEquals(31f, changed.textSize)
        assertEquals(KoolCanvasTextAlign.Center, changed.textAlign)
        assertEquals(-1, old.color.argb)
        assertEquals(KoolCanvasPaintStyle.Fill, old.style)
    }

    @Test fun `tints alpha color filters and explicit blends retain the full normalized paint`() = withEngine { engine, _ ->
        val texture = texture(engine)
        val paints = listOf(
            KoolPaint().apply { n = 0x80ffffff.toInt() },
            KoolPaint().apply { n = 0xff224466.toInt() },
            KoolPaint().apply { a(KoolCanvasBlendMode.Source) },
            KoolPaint().apply { a(KoolCanvasBlendMode.Clear) },
            KoolPaint().apply { setColorFilter(KoolMultiplyAddColorFilter(0xff888888.toInt(), 0)) },
            KoolPaint().apply { setColorFilter(KoolBlendColorFilter(0xff335577.toInt(), KoolCanvasBlendMode.Multiply)) },
        )
        for (paint in paints) {
            engine.beginFrame(32, 32)
            paint.m = KoolPaint.Style.FILL_AND_STROKE; paint.o = 3f; paint.q = 27f; paint.p = KoolPaint.Align.RIGHT
            paint.setTypeface(KoolTypeface.e)
            engine.a(Rect(0, 0, 4, 4), paint)
            draw(engine, texture, paint)
            val commands = engine.snapshot().commands
            assertEquals(commands[0].paint(), commands[1].paint())
        }
        // A filter that actually resolves to white is still canonical, just as before.
        val white = KoolPaint().apply { setColorFilter(KoolMultiplyAddColorFilter(-1, 0)) }
        engine.beginFrame(32, 32)
        draw(engine, texture, white)
        assertSame(KoolCanvasPaint.DefaultNearest, engine.snapshot().commands.single().paint())
    }

    @Test fun `direct blit changes made by a getter are observed after paint conversion`() = withEngine { engine, _ ->
        val texture = texture(engine, alpha = false)
        val paint = object : KoolPaint() {
            override fun g(): Float {
                engine.a(true)
                return 7f
            }
        }.apply { m = KoolPaint.Style.STROKE; q = 29f; p = KoolPaint.Align.RIGHT }
        draw(engine, texture, paint)
        val direct = engine.snapshot().commands.single().paint()
        assertEquals(KoolCanvasBlendMode.Source, direct.blendMode)
        assertEquals(KoolCanvasPaintStyle.Stroke, direct.style)
        assertEquals(7f, direct.strokeWidth)
        assertEquals(29f, direct.textSize)
        assertEquals(KoolCanvasTextAlign.Right, direct.textAlign)
        engine.f()
        val normal = KoolPaint().apply { m = KoolPaint.Style.STROKE; o = 7f }
        draw(engine, texture, normal)
        assertSame(KoolCanvasPaint.DefaultNearest, engine.snapshot().commands.last().paint())
        assertEquals(KoolCanvasBlendMode.Source, direct.blendMode)
    }

    @Test fun `canonical textures preserve NaN validation and accept infinite fields as before`() = withEngine { engine, _ ->
        val texture = texture(engine)
        for (widthNaN in listOf(true, false)) {
            val paint = KoolPaint().apply { if (widthNaN) o = Float.NaN else q = Float.NaN }
            assertFailsWith<IllegalArgumentException> { draw(engine, texture, paint) }
        }
        assertTrue(engine.snapshot().commands.isEmpty())
        draw(engine, texture, KoolPaint().apply { o = Float.POSITIVE_INFINITY; q = Float.POSITIVE_INFINITY })
        assertSame(KoolCanvasPaint.DefaultNearest, engine.snapshot().commands.single().paint())
    }

    @Test fun `mutable displacement continues to retain its source and the full paint in old leases`() = withEngine { engine, store ->
        val sprite = texture(engine)
        val screen = texture(engine)
        val effect = KoolDisplacementEffect().apply { configure(screen, 2f) }
        val paint = GamePaint().apply {
            m = KoolPaint.Style.STROKE; o = 4f; q = 25f
            setDisplacementEffect(effect)
        }
        draw(engine, sprite, paint)
        store.freezeFrame(engine.snapshot(), 1, 1, 0, 0).use { first ->
            val old = first.frame.commands.single().paint()
            val oldEffect = assertIs<KoolCanvasTextureEffect.Displacement>(old.textureEffect)
            assertEquals(2f, oldEffect.offsetBy)
            assertTrue(oldEffect.screenBase.id in first.resourceLease.resources())
            assertEquals(KoolCanvasPaintStyle.Stroke, old.style)
            assertEquals(4f, old.strokeWidth)
            assertEquals(25f, old.textSize)
            effect.offsetBy = 7f; paint.q = 35f
            engine.beginFrame(32, 32)
            draw(engine, sprite, paint)
            store.freezeFrame(engine.snapshot(), 2, 1, 0, 0).use { second ->
                val changed = second.frame.commands.single().paint()
                assertEquals(7f, assertIs<KoolCanvasTextureEffect.Displacement>(changed.textureEffect).offsetBy)
                assertEquals(35f, changed.textSize)
            }
            assertEquals(2f, oldEffect.offsetBy)
            assertEquals(25f, old.textSize)
        }
    }
}
