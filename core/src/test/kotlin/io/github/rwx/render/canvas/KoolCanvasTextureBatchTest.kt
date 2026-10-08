package io.github.rwx.render.canvas

import kotlin.test.*

class KoolCanvasTextureBatchTest {
    private val texture = KoolCanvasTextureRef(KoolCanvasTextureId("batch-test"), 32, 16)
    private val source = KoolCanvasRect(3f, 2f, 11f, 10f)
    private fun destination(x: Float) = KoolCanvasRect(x, 0f, x + 8.5f, 8f)

    @Test fun `independent overlapping tiles retain exact source and destination coordinates`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        repeat(1000) { buffer.drawTexture(texture, destination(it * 8f), source = source) }
        val command = assertIs<KoolCanvasCommand.DrawTextureBatch>(buffer.snapshot().commands.single())
        assertEquals(1000, command.quads.size)
        for (index in 0 until 1000) {
            assertEquals(3f, command.quads.coordinate(index, 0))
            assertEquals(11f, command.quads.coordinate(index, 2))
            assertEquals(index * 8f, command.quads.coordinate(index, 4))
            assertEquals(index * 8f + 8.5f, command.quads.coordinate(index, 6))
        }
    }

    @Test fun `published geometry survives recorder growth and reuse`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        buffer.drawTexture(texture, destination(10f))
        val first = assertIs<KoolCanvasCommand.DrawTextureBatch>(buffer.snapshot().commands.single())
        buffer.beginFrame(KoolCanvasViewport(100, 100))
        repeat(5000) { buffer.drawTexture(texture, destination(it.toFloat())) }
        val next = buffer.snapshot()
        assertEquals(2, next.commands.size)
        assertEquals(4096, assertIs<KoolCanvasCommand.DrawTextureBatch>(next.commands[0]).quads.size)
        assertEquals(10f, first.quads.coordinate(0, 4))
        assertEquals(18.5f, first.quads.coordinate(0, 6))
    }

    @Test fun `clip paint and geometry barriers preserve recording order`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        buffer.drawTexture(texture, destination(0f))
        buffer.clip(KoolCanvasRect(2f, 2f, 6f, 6f))
        buffer.drawTexture(texture, destination(8f))
        buffer.drawRect(destination(16f), KoolCanvasPaint.Default)
        buffer.drawTexture(texture, destination(24f), paint = KoolCanvasPaint.Default.copy(blendMode = KoolCanvasBlendMode.Source))
        val commands = buffer.snapshot().commands
        assertEquals(4, commands.size)
        assertNull(assertIs<KoolCanvasCommand.DrawTextureBatch>(commands[0]).state.clip)
        assertEquals(KoolCanvasRect(2f, 2f, 6f, 6f), assertIs<KoolCanvasCommand.DrawTextureBatch>(commands[1]).state.clip)
        assertIs<KoolCanvasCommand.DrawRectBatch>(commands[2])
        assertEquals(KoolCanvasBlendMode.Source, assertIs<KoolCanvasCommand.DrawTextureBatch>(commands[3]).paint.blendMode)
    }

    @Test fun `unsupported tint transforms and reversed quads keep general rendering semantics`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        buffer.drawTexture(texture, destination(0f), paint = KoolCanvasPaint.Default.copy(color = KoolCanvasColor(0x80ff0000.toInt())))
        buffer.drawTexture(texture, KoolCanvasRect(8f, 0f, 0f, 8f))
        buffer.translate(1f, 2f)
        buffer.drawTexture(texture, destination(0f))
        assertTrue(buffer.snapshot().commands.all { it is KoolCanvasCommand.DrawTexture })
    }

    @Test fun `full clear discards pending batch and alpha clear preserves it`() {
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        buffer.drawTexture(texture, destination(0f))
        assertFalse(buffer.isEmpty)
        buffer.clear(KoolCanvasColor.Transparent)
        assertIs<KoolCanvasCommand.Clear>(buffer.snapshot().commands.single())
        buffer.drawTexture(texture, destination(8f))
        buffer.clear(KoolCanvasColor.Transparent, KoolCanvasBlendMode.ClearAlpha)
        assertEquals(3, buffer.snapshot().commands.size)
    }

    @Test fun `frozen batches pin texture versions while sharing immutable geometry`() {
        val store = KoolCanvasCpuTextureStore()
        val buffer = KoolCanvasCommandBuffer(textureBatches = true)
        store.registerOpaqueArgb(texture.id, 32, 16, IntArray(512) { 0xffff0000.toInt() })
        buffer.drawTexture(texture, destination(0f))
        val recording = buffer.snapshot()
        val first = store.freezeFrame(recording, 1, 1, 0, 0)
        try {
            val firstBatch = assertIs<KoolCanvasCommand.DrawTextureBatch>(first.frame.commands.single())
            assertSame(assertIs<KoolCanvasCommand.DrawTextureBatch>(recording.commands.single()).quads, firstBatch.quads)
            store.registerOpaqueArgb(texture.id, 32, 16, IntArray(512) { 0xff0000ff.toInt() })
            val second = store.freezeFrame(recording, 2, 1, 0, 0)
            try {
                assertNotEquals(firstBatch.texture.id, assertIs<KoolCanvasCommand.DrawTextureBatch>(second.frame.commands.single()).texture.id)
                assertEquals(0xffff0000.toInt(), assertIs<FrozenCanvasResource.Pixels>(first.resourceLease.resources()[firstBatch.texture.id]).image.pixels[0])
            } finally { second.close() }
        } finally { first.close(); store.close() }
    }
}
