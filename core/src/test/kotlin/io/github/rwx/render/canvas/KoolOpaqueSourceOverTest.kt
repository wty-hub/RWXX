package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KoolOpaqueSourceOverTest {
    @Test
    fun `opaque destination matches generic formula for every source alpha and boundary channels`() {
        BlendFixture().use { blend ->
            assertTrue(compareMatrix(blend, listOf(255)) > 20_000)
        }
    }

    @Test
    fun `transparent and translucent destinations retain their generic rounding and early returns`() {
        BlendFixture().use { blend ->
            assertTrue(compareMatrix(blend, listOf(0, 1, 63, 127, 128, 254)) > 100_000)
        }
    }

    @Test
    fun `separate channel floors and premultiplied saturation preserve exact pixels`() {
        BlendFixture().use { blend ->
            // Combining the two straight-alpha numerators would produce 1 in each channel.
            assertEquals(0xff000000.toInt(), blend.draw(0xff010101.toInt(), 0x7d010101, premultiplied = false))
            // At source alpha 125 and destination channel 255, the destination contributes 130.
            // The three premultiplied channel sums are 254, 255 and 256, so the last must saturate.
            assertEquals(0xfffeffff.toInt(), blend.draw(0xffffffff.toInt(), 0x7d7c7d7e, premultiplied = true))
            for (sourceAlpha in listOf(1, 125, 254)) {
                assertEquals(0xffffffff.toInt(), blend.draw(0xffffffff.toInt(), argb(sourceAlpha, 255, 255, 255),
                    premultiplied = true), "RGB greater than source alpha must saturate rather than wrap")
                assertEquals(0xffffffff.toInt(), blend.draw(0xffffffff.toInt(), argb(sourceAlpha, 255, 255, 255),
                    premultiplied = false), "straight-alpha white over white retains the channel maximum")
            }
            for (premultiplied in listOf(false, true)) {
                assertEquals(0xff1c3014.toInt(), blend.draw(0xff386028.toInt(), 0x7d000000, premultiplied),
                    "ordinary alpha-125 black fog over opaque terrain")
                assertEquals(0xff386028.toInt(), blend.draw(0xff386028.toInt(), 0x00ffffff, premultiplied),
                    "transparent source with nonzero RGB retains the destination")
                assertEquals(0xff123456.toInt(), blend.draw(0xff386028.toInt(), 0xff123456.toInt(), premultiplied),
                    "opaque source retains its exact bytes")
                assertEquals(0x00123456, blend.draw(0x00123456, 0x00ffffff, premultiplied),
                    "the alpha-zero early return also retains hidden destination RGB")
            }
        }
    }

    @Test
    fun `private pixel blend changes only its destination element and preserves all source over formulas`() {
        BlendFixture().use { blend ->
            for (premultiplied in listOf(false, true)) {
                val source = 0x7d806040
                for (destination in listOf(0xff386028.toInt(), 0xfe112233.toInt(), 0x00102030)) {
                    val pixels = intArrayOf(0x12345678, destination, 0x24681357)
                    blend.drawPixel(pixels, source, premultiplied)
                    assertEquals(0x12345678, pixels[0])
                    assertEquals(genericFormula(destination, source, premultiplied), pixels[1])
                    assertEquals(0x24681357, pixels[2])
                }
            }
        }
    }

    private fun compareMatrix(blend: BlendFixture, destinationAlphas: List<Int>): Int {
        var comparisons = 0
        for (sourceAlpha in 0..255) {
            val sourceChannels = listOf(0, 1, sourceAlpha - 1, sourceAlpha, sourceAlpha + 1, 127, 128, 254, 255)
                .map { it.coerceIn(0, 255) }.distinct()
            for (sourceChannel in sourceChannels) for (destinationChannel in listOf(0, 1, 126, 127, 128, 254, 255)) {
                val source = argb(sourceAlpha, sourceChannel, 255 - sourceChannel, sourceAlpha)
                for (destinationAlpha in destinationAlphas) {
                    val destination = argb(destinationAlpha, destinationChannel,
                        (destinationChannel + 127) and 255, 255 - destinationChannel)
                    for (premultiplied in listOf(false, true)) {
                        val expected = genericFormula(destination, source, premultiplied)
                        val actual = blend.draw(destination, source, premultiplied)
                        if (expected != actual) fail(
                            "premultiplied=$premultiplied source=${source.toUInt().toString(16)} " +
                                    "destination=${destination.toUInt().toString(16)} " +
                                    "expected=${expected.toUInt().toString(16)} actual=${actual.toUInt().toString(16)}",
                        )
                        comparisons++
                    }
                }
            }
        }
        return comparisons
    }

    /** The original generic integer formula, including its dynamic-alpha unpremultiplication. */
    private fun genericFormula(destination: Int, source: Int, premultiplied: Boolean): Int {
        val sourceAlpha = source ushr 24
        if (sourceAlpha == 255) return source
        if (sourceAlpha == 0) return destination
        val destinationAlpha = destination ushr 24
        if (!premultiplied && destinationAlpha == 0) return source
        val contribution = (destinationAlpha * (255 - sourceAlpha) + 127) / 255
        val outputAlpha = (sourceAlpha + contribution).coerceAtMost(255)
        if (outputAlpha == 0) return 0
        var output = outputAlpha shl 24
        for (shift in listOf(16, 8, 0)) {
            val sourceChannel = (source ushr shift) and 255
            val destinationChannel = (destination ushr shift) and 255
            val destinationPremultiplied = destinationChannel * contribution / 255
            val channelSum = if (premultiplied) {
                (sourceChannel + destinationPremultiplied).coerceAtMost(255)
            } else {
                sourceChannel * sourceAlpha / 255 + destinationPremultiplied
            }
            val channel = ((channelSum * 255 + outputAlpha / 2) / outputAlpha).coerceIn(0, 255)
            output = output or (channel shl shift)
        }
        return output
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private class BlendFixture : AutoCloseable {
        private val store = KoolCanvasCpuTextureStore()
        private val engine = KoolGraphicsEngine(textureStore = store)
        private val integer = Int::class.javaPrimitiveType!!
        private val straight = KoolGraphicsEngine::class.java.getDeclaredMethod("renderTargetSourceOver", integer, integer)
            .apply { isAccessible = true }
        private val premultiplied = KoolGraphicsEngine::class.java.getDeclaredMethod("renderTargetPremultipliedSourceOver", integer, integer)
            .apply { isAccessible = true }
        private val pixel = KoolGraphicsEngine::class.java.getDeclaredMethod("blendRenderTargetPixel", IntArray::class.java,
            integer, integer, KoolCanvasBlendMode::class.java, Boolean::class.javaPrimitiveType!!).apply { isAccessible = true }

        fun draw(destination: Int, source: Int, premultiplied: Boolean): Int =
            (if (premultiplied) this.premultiplied else straight).invoke(engine, destination, source) as Int

        fun drawPixel(pixels: IntArray, source: Int, premultiplied: Boolean) {
            pixel.invoke(engine, pixels, 1, source, KoolCanvasBlendMode.SourceOver, premultiplied)
        }

        override fun close() = store.close()
    }
}
