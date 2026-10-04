package io.github.rwx.render.canvas

import de.fabmax.kool.util.Uint8Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class KoolBgraUploadPackingTest {
    @Test
    fun `bgra payload rejects invalid dimensions capacity and overflow`() {
        val bytes = Uint8Buffer(4)
        listOf(0 to 1, 1 to -1, 2 to 1, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (width, height) ->
            assertFailsWith<IllegalArgumentException> { KoolCanvasBgraImageData(bytes, width, height, "invalid") }
        }
    }

    @Test
    fun `direct bgra copy preserves every channel and alpha across bulk boundaries`() {
        val random = Random(9357)
        val source = IntArray(32_773) { random.nextInt() }
        val original = source.copyOf()
        val destination = Uint8Buffer(source.size * 4)
        packArgbPixelsToBgra(destination, source, source.size)
        for (index in source.indices) {
            val argb = source[index]
            assertEquals((argb and 0xff), destination[index * 4].toInt())
            assertEquals((argb ushr 8) and 0xff, destination[index * 4 + 1].toInt())
            assertEquals((argb ushr 16) and 0xff, destination[index * 4 + 2].toInt())
            assertEquals(argb ushr 24, destination[index * 4 + 3].toInt())
        }
        assertContentEquals(original, source)
    }

    @Test
    fun `short source clears pooled upload bytes and leaves the tail untouched`() {
        val destination = Uint8Buffer(16_390 * 4)
        packArgbPixelsToBgra(destination, IntArray(16_390) { -1 }, 16_390)
        packArgbPixelsToBgra(destination, intArrayOf(0x11223344), 16_389)
        assertContentEquals(listOf(0x44, 0x33, 0x22, 0x11), List(4) { destination[it].toInt() })
        assertTrue((4 until 16_389 * 4).all { destination[it].toInt() == 0 })
        assertTrue((16_389 * 4 until destination.capacity).all { destination[it].toInt() == 255 })
    }

    @Test
    fun `backend layout switch drops gpu cache while retaining argb source and old payload`() {
        val id = KoolCanvasTextureId("bgra-layout-switch")
        val ref = KoolCanvasTextureRef(id, 1, 1, hasAlpha = true)
        val previous = KoolCanvasTextureRegistry.nativeBgraUploadsEnabled
        try {
            KoolCanvasTextureRegistry.configureNativeBgraUploads(false)
            KoolCanvasTextureRegistry.registerArgb(id, 1, 1, intArrayOf(0x11223344), alphaBleed = false)
            val old = KoolCanvasTextureRegistry.resolve(ref, KoolCanvasTextureFilter.Nearest)
            val rgba = old.uploadData as de.fabmax.kool.pipeline.BufferedImageData2d
            KoolCanvasTextureRegistry.configureNativeBgraUploads(true)
            val next = KoolCanvasTextureRegistry.resolve(ref, KoolCanvasTextureFilter.Nearest)
            val bgra = next.uploadData as KoolCanvasBgraImageData
            assertNotSame(old, next)
            assertContentEquals(listOf(0x22, 0x33, 0x44, 0x11), List(4) { (rgba.data as Uint8Buffer)[it].toInt() })
            assertContentEquals(listOf(0x44, 0x33, 0x22, 0x11), List(4) { bgra.data[it].toInt() })
            assertContentEquals(intArrayOf(0x11223344), KoolCanvasTextureRegistry.argbImageView(id)!!.pixels)
            assertTrue(rgba.id != bgra.id)
        } finally {
            KoolCanvasTextureRegistry.unregister(id)
            KoolCanvasTextureRegistry.configureNativeBgraUploads(previous)
        }
    }
}
