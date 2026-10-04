package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoolCanvasRectRasterTest {
    @Test
    fun `half pixel edges include left and top and exclude right and bottom`() {
        RasterFixture().use { raster ->
            val color = 0x7d603020
            for (left in listOf(Math.nextDown(1.5f), 1.5f, Math.nextUp(1.5f))) {
                for (right in listOf(Math.nextDown(7.5f), 7.5f, Math.nextUp(7.5f))) {
                    val command = rect(KoolCanvasRect(left, 2.5f, right, 6.5f), color, KoolCanvasBlendMode.Source)
                    val initial = seed()
                    val expected = initial.copyOf()
                    for (y in 2..5) {
                        for (x in 2..6) expected[x + y * WIDTH] = color
                        if (left <= 1.5f) expected[1 + y * WIDTH] = color
                        if (right > 7.5f) expected[7 + y * WIDTH] = color
                    }
                    val actual = initial.copyOf()
                    assertTrue(raster.draw(command, actual))
                    assertContentEquals(expected, actual, "half pixel boundaries left=$left right=$right")
                    assertContentEquals(seed(), initial, "the independent expected image leaves its seed unchanged")
                }
            }
        }
    }

    @Test
    fun `disjoint clips are handled without touching any pixels`() {
        RasterFixture().use { raster ->
            val initial = seed()
            for (clip in listOf(KoolCanvasRect(20f, 20f, 24f, 24f), KoolCanvasRect(8f, 1f, 10f, 7f))) {
                val command = rect(KoolCanvasRect(1f, 1f, 8f, 7f), state = KoolCanvasState(clip = clip))
                val actual = initial.copyOf()
                assertTrue(raster.draw(command, actual))
                assertContentEquals(initial, actual, "a disjoint or touching clip must not redraw the unclipped rectangle")
            }
        }
    }

    @Test
    fun `overlapping clips offscreen geometry and reflected axes retain known coverage`() {
        RasterFixture().use { raster ->
            val color = 0xff603020.toInt()
            val cases = listOf(
                Triple(rect(KoolCanvasRect(1f, 1f, 8f, 7f), state = KoolCanvasState(clip = KoolCanvasRect(2f, 2f, 6f, 5f))),
                    2..5, 2..4),
                Triple(rect(KoolCanvasRect(-5f, -4f, 2f, 3f)), 0..1, 0..2),
                Triple(rect(KoolCanvasRect(1f, 1f, 4f, 3f), state = KoolCanvasState(transform =
                    KoolCanvasTransform(scaleX = -1f, translateX = 9f, translateY = 1f))), 5..7, 2..3),
            )
            for ((command, columns, rows) in cases) {
                val expected = seed()
                for (y in rows) for (x in columns) expected[x + y * WIDTH] = color
                val actual = seed()
                assertTrue(raster.draw(command, actual))
                assertContentEquals(expected, actual, "coverage for ${command.state}")
            }
        }
    }

    @Test
    fun `source preserves transparent RGB and source over uses effective alpha`() {
        RasterFixture().use { raster ->
            val destination = KoolCanvasRect(0f, 0f, 2f, 2f)
            val cases = listOf(
                rect(destination, 0x00603020, KoolCanvasBlendMode.Source) to 0x00603020,
                rect(destination, 0xff603020.toInt()) to 0xff603020.toInt(),
                rect(destination, 0xff603020.toInt()).let { it.copy(paint = it.paint.copy(alphaMultiplier = 0.5f)) }
                    to 0x80603020.toInt(),
            )
            for ((command, color) in cases) {
                val actual = IntArray(WIDTH * HEIGHT)
                val expected = IntArray(WIDTH * HEIGHT)
                for (y in 0..1) for (x in 0..1) expected[x + y * WIDTH] = color
                assertTrue(raster.draw(command, actual))
                assertContentEquals(expected, actual, "${command.paint.blendMode} alpha=${command.paint.alphaMultiplier}")
            }
            val actual = seed()
            val expected = actual.copyOf()
            for (y in 0..1) for (x in 0..1) expected[x + y * WIDTH] = expected[x + y * WIDTH] and 0x00ffffff
            assertTrue(raster.draw(rect(destination, blend = KoolCanvasBlendMode.ClearAlpha), actual))
            assertContentEquals(expected, actual, "alpha-only clears retain RGB outside and inside their geometry")
        }
    }

    @Test
    fun `unsupported stroke and other render targets leave the original pixels untouched`() {
        RasterFixture().use { raster ->
            val command = rect(KoolCanvasRect(1f, 1f, 8f, 7f))
            for (unsupported in listOf(
                command.copy(paint = command.paint.copy(style = KoolCanvasPaintStyle.Stroke)),
                command.copy(state = KoolCanvasState(renderTarget = KoolCanvasRenderTargetId("other"))),
            )) {
                val actual = seed()
                assertFalse(raster.draw(unsupported, actual))
                assertContentEquals(seed(), actual)
            }
        }
    }

    private class RasterFixture : AutoCloseable {
        private val store = KoolCanvasCpuTextureStore()
        private val engine = KoolGraphicsEngine(textureStore = store)
        private val rasterize = KoolGraphicsEngine::class.java.declaredMethods
            .single { it.name == "rasterizeRectCommand" }.apply { isAccessible = true }

        fun draw(command: KoolCanvasCommand.DrawRect, pixels: IntArray): Boolean =
            rasterize.invoke(engine, command, pixels, WIDTH, HEIGHT) as Boolean

        override fun close() = store.close()
    }

    private companion object {
        const val WIDTH = 11
        const val HEIGHT = 9

        fun rect(
            destination: KoolCanvasRect,
            color: Int = 0xff603020.toInt(),
            blend: KoolCanvasBlendMode = KoolCanvasBlendMode.SourceOver,
            state: KoolCanvasState = KoolCanvasState.Default,
        ) = KoolCanvasCommand.DrawRect(destination,
            KoolCanvasPaint(color = KoolCanvasColor(color), blendMode = blend), state)

        fun seed(): IntArray = IntArray(WIDTH * HEIGHT) { index ->
            val alpha = when (index % 4) { 0 -> 0; 1 -> 63; 2 -> 128; else -> 255 }
            (alpha shl 24) or ((index * 0x010203) and 0x00ffffff)
        }
    }
}
