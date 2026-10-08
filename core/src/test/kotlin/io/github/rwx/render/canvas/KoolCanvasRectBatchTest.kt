package io.github.rwx.render.canvas

import kotlin.test.*

class KoolCanvasRectBatchTest {
    private val rect = KoolCanvasRect(1f, 2f, 9.5f, 12f)
    private val translucent = KoolCanvasPaint.Default.copy(color = KoolCanvasColor(0x80000000.toInt()))

    @Test fun `overlaps and clip preserve each rectangle and paint order`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        buffer.drawRect(rect, translucent)
        buffer.drawRect(rect.copy(left = 3f), translucent)
        buffer.clip(KoolCanvasRect(4f, 0f, 8f, 15f))
        buffer.drawRect(rect, translucent)
        buffer.drawRect(rect, translucent.copy(blendMode = KoolCanvasBlendMode.Source))
        val commands = buffer.snapshot().commands.map { assertIs<KoolCanvasCommand.DrawRectBatch>(it) }
        assertEquals(listOf(2, 1, 1), commands.map { it.rects.size })
        assertNull(commands[0].state.clip)
        assertEquals(3f, commands[0].rects.coordinate(1, 0))
        assertEquals(9.5f, commands[0].rects.coordinate(1, 2))
        assertEquals(4f, commands[1].state.clip!!.left)
        assertEquals(KoolCanvasBlendMode.Source, commands[2].paint.blendMode)
    }

    @Test fun `published rectangles survive scratch reuse and growth`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        buffer.drawRect(rect, translucent)
        val first = assertIs<KoolCanvasCommand.DrawRectBatch>(buffer.snapshot().commands.single())
        buffer.beginFrame(KoolCanvasViewport(100, 100))
        repeat(5000) { buffer.drawRect(rect.copy(left = it.toFloat(), right = it + 1f), translucent) }
        assertEquals(listOf(4096, 904), buffer.snapshot().commands.map { assertIs<KoolCanvasCommand.DrawRectBatch>(it).rects.size })
        assertEquals(1f, first.rects.coordinate(0, 0))
        assertEquals(9.5f, first.rects.coordinate(0, 2))
    }

    @Test fun `different primitive and texture kinds cannot cross batch boundaries`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        val texture = KoolCanvasTextureRef(KoolCanvasTextureId("rect-test"), 8, 8)
        buffer.drawRect(rect, translucent)
        buffer.drawTexture(texture, rect)
        buffer.drawRect(rect, translucent)
        buffer.drawLine(KoolCanvasPoint(1f, 1f), KoolCanvasPoint(2f, 2f), translucent)
        buffer.drawRect(rect, translucent)
        assertEquals(listOf("DrawRectBatch", "DrawTextureBatch", "DrawRectBatch", "DrawLine", "DrawRectBatch"),
            buffer.snapshot().commands.map { it::class.simpleName })
    }

    @Test fun `stroke transform and non-generic semantics stay in general path`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        buffer.drawRect(rect, translucent.copy(style = KoolCanvasPaintStyle.Stroke))
        buffer.translate(1f, 2f)
        buffer.drawRect(rect, translucent)
        buffer.beginFrame(KoolCanvasViewport(100, 100))
        buffer.setDrawRole(KoolCanvasDrawRole.UnitShadow)
        buffer.drawRect(rect, translucent)
        assertIs<KoolCanvasCommand.DrawRect>(buffer.snapshot().commands.single())
    }

    @Test fun `clear and frame reset flush or discard pending rectangles correctly`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        buffer.drawRect(rect, translucent)
        val beforeClear = buffer.signature
        assertNotEquals(0, beforeClear)
        buffer.clear(KoolCanvasColor.Transparent)
        assertIs<KoolCanvasCommand.Clear>(buffer.snapshot().commands.single())
        buffer.drawRect(rect, translucent)
        buffer.clear(KoolCanvasColor.Transparent, KoolCanvasBlendMode.ClearAlpha)
        assertEquals(3, buffer.snapshot().commands.size)
        buffer.drawRect(rect, translucent)
        buffer.beginFrame(KoolCanvasViewport(100, 100))
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.signature)
    }
}
