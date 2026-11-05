package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class KoolCanvasAtlasSlotKeysTest {
    private class Fixture(images: Map<String, Int>) : AutoCloseable {
        private val delegate = KoolCanvasCpuTextureStore()
        val reads = mutableListOf<String>()
        val atlas = KoolCanvasSpriteAtlas(object : KoolCanvasTextureStore by delegate {
            override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? {
                reads += id.value
                return images[id.value]?.let { color -> KoolCanvasArgbImage(2, 2, IntArray(4) { color }) }
            }
        }, pageSize = 16, maxPages = 1, incrementalUploads = false, prepareNestedFrames = false)

        override fun close() {
            atlas.clear()
            delegate.close()
        }
    }

    private fun id(value: String) = KoolCanvasTextureId(String(value.toCharArray()))

    private fun draw(id: KoolCanvasTextureId) = KoolCanvasCommand.DrawTexture(
        KoolCanvasTextureRef(id, 2, 2), KoolCanvasRect(0f, 0f, 2f, 2f),
        KoolCanvasRect(0f, 0f, 4f, 4f), KoolCanvasPaint.DefaultNearest, KoolCanvasState.Default)

    private fun rgba(slot: KoolCanvasSpriteAtlas.Slot): List<Int> {
        val offset = (slot.y * slot.page.size + slot.x) * 4
        return List(4) { slot.page.rgbaPixels[offset + it].toInt() and 255 }
    }

    @Test fun `equal ids with separately allocated strings reuse the same slot without reading pixels again`() {
        val first = id("equal-source/cpu-9-v11")
        Fixture(mapOf(first.value to 0xff123456.toInt())).use { fixture ->
            val slot = assertNotNull(fixture.atlas.slot(draw(first)))
            val retainedPixels = slot.page.rgbaPixels.copyOf()
            repeat(16) {
                val equal = id(first.value)
                assertNotSame(first.value, equal.value)
                assertEquals(first, equal)
                assertSame(slot, fixture.atlas.slotForValidatedTexture(draw(equal).texture))
                assertSame(slot, fixture.atlas.slot(draw(equal)))
            }
            assertEquals(listOf(first.value), fixture.reads)
            assertContentEquals(retainedPixels, slot.page.rgbaPixels)
        }
    }

    @Test fun `colliding string hashes keep separate slot identities and pixels`() {
        val first = id("Aa")
        val second = id("BB")
        assertEquals(first.value.hashCode(), second.value.hashCode())
        assertNotEquals(first, second)
        Fixture(mapOf(first.value to 0xff123456.toInt(), second.value to 0xffa1b2c3.toInt())).use { fixture ->
            val a = assertNotNull(fixture.atlas.slot(draw(first)))
            val b = assertNotNull(fixture.atlas.slot(draw(second)))
            assertNotSame(a, b)
            assertSame(a.page, b.page)
            assertNotEquals(a.x to a.y, b.x to b.y)
            assertEquals(listOf(0x12, 0x34, 0x56, 0xff), rgba(a))
            assertEquals(listOf(0xa1, 0xb2, 0xc3, 0xff), rgba(b))
            assertSame(a, fixture.atlas.slot(draw(id("Aa"))))
            assertSame(b, fixture.atlas.slot(draw(id("BB"))))
            assertEquals(listOf(first.value, second.value), fixture.reads)
        }
    }

    @Test fun `full version keys remain separate and pruning or clear invalidates each cached slot`() {
        val first = id("legacy-texture-7/cpu-1-v20/gpu-3")
        val second = id("legacy-texture-7/cpu-1-v21/gpu-3")
        Fixture(mapOf(first.value to 0xff102030.toInt(), second.value to 0xff405060.toInt())).use { fixture ->
            fixture.atlas.prepare(listOf(draw(first), draw(second)))
            val a = assertNotNull(fixture.atlas.slot(draw(first)))
            val b = assertNotNull(fixture.atlas.slot(draw(second)))
            val oldPixels = a.page.rgbaPixels.copyOf()
            assertNotSame(a, b)
            assertEquals(listOf(0x10, 0x20, 0x30, 0xff), rgba(a))
            assertEquals(listOf(0x40, 0x50, 0x60, 0xff), rgba(b))
            assertEquals(listOf(first.value, second.value), fixture.reads)

            repeat(61) { fixture.atlas.prepare(emptyList()) }
            val afterPrune = assertNotNull(fixture.atlas.slot(draw(id(first.value))))
            assertNotSame(a, afterPrune)
            assertNotSame(a.page, afterPrune.page)
            assertContentEquals(oldPixels, a.page.rgbaPixels)

            fixture.atlas.clear()
            val afterClear = assertNotNull(fixture.atlas.slot(draw(id(second.value))))
            assertNotSame(b, afterClear)
            assertNotSame(afterPrune.page, afterClear.page)
            assertEquals(listOf(0x40, 0x50, 0x60, 0xff), rgba(afterClear))
            assertContentEquals(oldPixels, a.page.rgbaPixels)
            assertEquals(listOf(first.value, second.value, first.value, second.value), fixture.reads)
        }
    }
}
