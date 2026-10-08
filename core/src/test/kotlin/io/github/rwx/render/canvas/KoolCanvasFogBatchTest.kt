package io.github.rwx.render.canvas

import kotlin.test.*

class KoolCanvasFogBatchTest {
    private val texture = KoolCanvasTextureRef(KoolCanvasTextureId("fog-test"), 32, 16)
    private val rect = KoolCanvasRect(1f, 2f, 8.5f, 9f)
    private val black = KoolCanvasPaint.Default.copy(color = KoolCanvasColor(0x7d000000))
    private fun recorder() = KoolCanvasCommandBuffer(fogBatches = true).apply { setDrawRole(KoolCanvasDrawRole.MapFog) }

    @Test fun `alternating masks keep alpha rectangles and atlas regions in literal order`() {
        val buffer = recorder()
        repeat(1000) {
            buffer.drawRect(rect, black)
            buffer.drawTexture(texture, rect, black.copy(alphaMultiplier = .5f), KoolCanvasRect(2f, 3f, 6f, 8f))
        }
        val batch = assertIs<KoolCanvasCommand.DrawFogBatch>(buffer.snapshot().commands.single())
        assertEquals(2000, batch.masks.size)
        val expanded = mutableListOf<KoolCanvasCommand>()
        batch.masks.forEachDraw(batch.texture, batch.filter, batch.state, batch.paint) { expanded += it }
        for (pair in 0 until 1000) {
            val solid = assertIs<KoolCanvasCommand.DrawRect>(expanded[pair * 2])
            val masked = assertIs<KoolCanvasCommand.DrawTexture>(expanded[pair * 2 + 1])
            assertEquals(rect, solid.rect)
            assertEquals(rect, masked.destination)
            assertEquals(KoolCanvasRect(2f, 3f, 6f, 8f), masked.source)
            assertEquals(black.color.toKoolColor().a, solid.paint.color.toKoolColor(solid.paint.alphaMultiplier).a)
            assertEquals(black.color.toKoolColor(.5f).a, masked.paint.color.toKoolColor(masked.paint.alphaMultiplier).a)
        }
    }

    @Test fun `atlas filter clip and nonblack barriers do not reorder or merge masks`() {
        val buffer = recorder()
        buffer.drawRect(rect, black)
        buffer.drawTexture(texture, rect, black)
        buffer.drawTexture(texture, rect, black.copy(textureFilter = KoolCanvasTextureFilter.Nearest))
        buffer.clip(KoolCanvasRect(2f, 2f, 5f, 5f))
        buffer.drawRect(rect, black)
        buffer.drawRect(rect, black.copy(color = KoolCanvasColor.White))
        buffer.drawRect(rect, black)
        val commands = buffer.snapshot().commands
        assertEquals(5, commands.size)
        assertEquals(2, assertIs<KoolCanvasCommand.DrawFogBatch>(commands[0]).masks.size)
        assertEquals(KoolCanvasTextureFilter.Nearest, assertIs<KoolCanvasCommand.DrawFogBatch>(commands[1]).filter)
        assertNotNull(assertIs<KoolCanvasCommand.DrawFogBatch>(commands[2]).state.clip)
        assertIs<KoolCanvasCommand.DrawRect>(commands[3])
        assertIs<KoolCanvasCommand.DrawFogBatch>(commands[4])
    }

    @Test fun `published masks survive growth clear and recorder reuse`() {
        val buffer = recorder()
        buffer.drawRect(rect, black)
        val first = assertIs<KoolCanvasCommand.DrawFogBatch>(buffer.snapshot().commands.single())
        buffer.beginFrame(KoolCanvasViewport(100, 100))
        buffer.setDrawRole(KoolCanvasDrawRole.MapFog)
        repeat(5000) { buffer.drawRect(rect.copy(left = it.toFloat(), right = it + 1f), black) }
        assertEquals(listOf(4096, 904), buffer.snapshot().commands.map { assertIs<KoolCanvasCommand.DrawFogBatch>(it).masks.size })
        assertEquals(1f, first.masks.coordinate(0, 0))
        buffer.clear(KoolCanvasColor.Transparent)
        assertIs<KoolCanvasCommand.Clear>(buffer.snapshot().commands.single())
    }

    @Test fun `frozen fog masks pin atlas versions and share immutable numeric storage`() {
        val store = KoolCanvasCpuTextureStore()
        try {
            val buffer = recorder()
            store.registerOpaqueArgb(texture.id, 32, 16, IntArray(512) { -1 })
            buffer.drawRect(rect, black)
            buffer.drawTexture(texture, rect, black)
            val recording = buffer.snapshot()
            val first = store.freezeFrame(recording, 1, 1, 0, 0)
            try {
                val command = assertIs<KoolCanvasCommand.DrawFogBatch>(first.frame.commands.single())
                assertSame(assertIs<KoolCanvasCommand.DrawFogBatch>(recording.commands.single()).masks, command.masks)
                store.registerOpaqueArgb(texture.id, 32, 16, IntArray(512))
                val second = store.freezeFrame(recording, 2, 1, 0, 0)
                try { assertNotEquals(command.texture?.id, assertIs<KoolCanvasCommand.DrawFogBatch>(second.frame.commands.single()).texture?.id) }
                finally { second.close() }
            } finally { first.close() }
        } finally { store.close() }
    }
}
