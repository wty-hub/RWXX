package io.github.rwx.render.canvas

import java.lang.reflect.InvocationTargetException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoolRasterScalarMappingTest {
    @Test
    fun `rectangle scalar coordinates retain point coverage blending and handled results`() {
        RasterFixture().use { raster ->
            for (transform in transforms()) for (clip in clips()) for (destination in destinations()) {
                for (blend in listOf(KoolCanvasBlendMode.Source, KoolCanvasBlendMode.SourceOver, KoolCanvasBlendMode.ClearAlpha)) {
                    for (alpha in listOf(0.5f, 1f)) {
                        val command = KoolCanvasCommand.DrawRect(destination,
                            KoolCanvasPaint(color = KoolCanvasColor(0xff603020.toInt()), blendMode = blend, alphaMultiplier = alpha),
                            KoolCanvasState(transform = transform, clip = clip))
                        val expected = seed()
                        val actual = seed()
                        val handled = raster.oldPointRect(command, expected)
                        assertEquals(handled, raster.rect(command, actual), "rect handled $command")
                        assertContentEquals(expected, actual, "rect $command")
                    }
                }
            }
            val unsupported = KoolCanvasCommand.DrawRect(KoolCanvasRect(1f, 1f, 8f, 7f),
                KoolCanvasPaint(style = KoolCanvasPaintStyle.Stroke), KoolCanvasState.Default)
            val actual = seed()
            assertFalse(raster.rect(unsupported, actual))
            assertContentEquals(seed(), actual)
        }
    }

    @Test
    fun `generic texture scalar coordinates retain point sampling tint clipping and blending`() {
        RasterFixture().use { raster ->
            var nonfiniteFailures = 0
            for (premultiplied in listOf(false, true)) {
                raster.installSource(premultiplied)
                for (transform in transforms()) for (clip in clips()) for (destination in destinations()) {
                    for (filter in KoolCanvasTextureFilter.entries) {
                        for (blend in listOf(KoolCanvasBlendMode.Source, KoolCanvasBlendMode.SourceOver, KoolCanvasBlendMode.Add)) {
                            val command = KoolCanvasCommand.DrawTexture(raster.texture,
                                KoolCanvasRect(0.25f, 0f, 3f, 2f), destination,
                                KoolCanvasPaint(color = KoolCanvasColor(0xffc08040.toInt()), alphaMultiplier = 0.5f,
                                    textureFilter = filter, blendMode = blend), KoolCanvasState(transform = transform, clip = clip))
                            val expected = seed()
                            val actual = seed()
                            val oldResult = runCatching { raster.oldPointTexture(command, expected) }
                            val newResult = runCatching { raster.texture(command, actual) }
                            val oldFailure = oldResult.exceptionOrNull()?.unwrapReflection()
                            val newFailure = newResult.exceptionOrNull()?.unwrapReflection()
                            assertEquals(oldFailure?.javaClass, newFailure?.javaClass, "texture failure type $command")
                            if (oldFailure != null) {
                                // Existing linear sampling can reach roundToInt(NaN) for a
                                // nonfinite destination. Preserve that failure, including any
                                // pixels written before it, instead of silently dropping the case.
                                assertTrue(!destination.left.isFinite() || !destination.top.isFinite() ||
                                    !destination.right.isFinite() || !destination.bottom.isFinite(),
                                    "finite sampling must remain a handled/pixel comparison: $command")
                                assertEquals(oldFailure.message, newFailure?.message, "texture failure message $command")
                                nonfiniteFailures++
                            } else {
                                assertEquals(oldResult.getOrThrow(), newResult.getOrThrow(), "texture handled $command")
                            }
                            assertContentEquals(expected, actual, "premultiplied=$premultiplied texture $command")
                        }
                    }
                }
                val unsupported = KoolCanvasCommand.DrawTexture(raster.texture, KoolCanvasRect(0f, 0f, 3f, 2f),
                    KoolCanvasRect(1f, 1f, 8f, 7f), KoolCanvasPaint.Default,
                    KoolCanvasState(renderTarget = KoolCanvasRenderTargetId("other")))
                val actual = seed()
                assertFalse(raster.texture(unsupported, actual))
                assertContentEquals(seed(), actual)
            }
            assertTrue(nonfiniteFailures > 0, "the matrix must also exercise the old NaN sampling failure")
        }
    }

    private fun Throwable.unwrapReflection(): Throwable =
        if (this is InvocationTargetException) targetException.unwrapReflection() else this

    @Test
    fun `identity valued generic transform draws known reflected rectangle and nearest texture pixels`() {
        RasterFixture().use { raster ->
            val initial = seed()
            val actual = initial.copyOf()
            val command = KoolCanvasCommand.DrawRect(KoolCanvasRect(1f, 1f, 4f, 3f),
                KoolCanvasPaint(color = KoolCanvasColor(0x7d603020), blendMode = KoolCanvasBlendMode.Source),
                KoolCanvasState(transform = KoolCanvasTransform(scaleX = -1f, translateX = 9f, translateY = 1f)))
            assertTrue(raster.rect(command, actual))
            val expected = initial.copyOf()
            for (y in 2..3) for (x in 5..7) expected[x + y * WIDTH] = 0x7d603020
            assertContentEquals(expected, actual)

            raster.installSource(false)
            val textureCommand = KoolCanvasCommand.DrawTexture(raster.texture, KoolCanvasRect(0f, 0f, 3f, 2f),
                KoolCanvasRect(1f, 2f, 4f, 4f), KoolCanvasPaint.DefaultNearest.copy(blendMode = KoolCanvasBlendMode.Source),
                KoolCanvasState(transform = KoolCanvasTransform()))
            val textured = initial.copyOf()
            assertTrue(raster.texture(textureCommand, textured))
            val expectedTexture = initial.copyOf()
            val source = raster.source().pixels
            for (y in 0..1) for (x in 0..2) expectedTexture[x + 1 + (y + 2) * WIDTH] = source[x + y * 3]
            assertContentEquals(expectedTexture, textured, "value-equal identity must exercise the generic texture path")
        }
    }

    /** Uses the original Point mapping; sampling and blending remain the unchanged production code. */
    private class RasterFixture : AutoCloseable {
        private val store = KoolCanvasCpuTextureStore()
        private val engine = KoolGraphicsEngine(textureStore = store)
        val texture = KoolCanvasTextureRef(KoolCanvasTextureId("scalar-mapping-source"), 3, 2)
        private fun method(name: String) = KoolGraphicsEngine::class.java.declaredMethods
            .single { it.name == name }.apply { isAccessible = true }
        private val rectMethod = method("rasterizeRectCommand")
        private val textureMethod = method("rasterizeTextureCommand")
        private val clipMethod = method("clipForGeometry")
        private val inverseMethod = method("inverted")
        private val quadMethod = method("clipTextureQuad")
        private val sampleMethod = method("sampleSourceColor")
        private val tintMethod = method("tintColor")
        private val paintMethod = method("paintColor")
        private val blendMethod = method("blendRenderTargetPixel")

        fun rect(command: KoolCanvasCommand.DrawRect, pixels: IntArray): Boolean =
            rectMethod.invoke(engine, command, pixels, WIDTH, HEIGHT) as Boolean

        fun texture(command: KoolCanvasCommand.DrawTexture, pixels: IntArray): Boolean =
            textureMethod.invoke(engine, command, pixels, WIDTH, HEIGHT, emptySet<KoolCanvasTextureId>(), null) as Boolean

        fun installSource(premultiplied: Boolean) {
            val pixels = intArrayOf(0xff123456.toInt(), 0x80302010.toInt(), 0x00603020,
                0x7d406020, 0xffffffff.toInt(), 0x01101010)
            if (premultiplied) store.registerPremultipliedArgb(texture.id, 3, 2, pixels)
            else store.registerArgb(texture.id, 3, 2, pixels, false)
        }

        fun source(): KoolCanvasArgbImage = store.argbImageView(texture.id)!!

        fun oldPointRect(command: KoolCanvasCommand.DrawRect, pixels: IntArray): Boolean {
            if (command.state.renderTarget != null || command.paint.style == KoolCanvasPaintStyle.Stroke) return false
            val clip = clipMethod.invoke(engine, command.state) as KoolCanvasRect?
            val destination = if (clip == null) command.rect else command.rect.intersect(clip) ?: return true
            if (destination.isEmpty) return true
            val color = paintMethod.invoke(engine, command.paint) as Int
            return oldPointCoverage(destination, command.state.transform) { x, y, _ ->
                blendMethod.invoke(engine, pixels, x + y * WIDTH, color, command.paint.blendMode, false)
            }
        }

        fun oldPointTexture(command: KoolCanvasCommand.DrawTexture, pixels: IntArray): Boolean {
            if (command.state.renderTarget != null || command.paint.textureEffect != null) return false
            val sourceImage = store.argbImageView(command.texture.id) ?: return false
            val clip = clipMethod.invoke(engine, command.state) as KoolCanvasRect?
            val quad = quadMethod.invoke(engine, command.source, command.destination, clip) ?: return true
            val source = quad.javaClass.getDeclaredField("source").apply { isAccessible = true }.get(quad) as KoolCanvasRect
            val destination = quad.javaClass.getDeclaredField("destination").apply { isAccessible = true }.get(quad) as KoolCanvasRect
            return oldPointCoverage(destination, command.state.transform) { x, y, point ->
                val sampled = sampleMethod.invoke(engine, command, sourceImage, source, destination, point.x, point.y) as Int
                val tinted = tintMethod.invoke(engine, sampled, command.paint, sourceImage.premultipliedAlpha) as Int
                blendMethod.invoke(engine, pixels, x + y * WIDTH, tinted, command.paint.blendMode, sourceImage.premultipliedAlpha)
            }
        }

        private fun oldPointCoverage(destination: KoolCanvasRect, transform: KoolCanvasTransform,
            covered: (Int, Int, KoolCanvasPoint) -> Unit): Boolean {
            val bounds = transform.mapRectBounds(destination)
            val left = floor(bounds.boundsLeft).toInt().coerceIn(0, WIDTH)
            val top = floor(bounds.boundsTop).toInt().coerceIn(0, HEIGHT)
            val right = ceil(bounds.boundsRight).toInt().coerceIn(0, WIDTH)
            val bottom = ceil(bounds.boundsBottom).toInt().coerceIn(0, HEIGHT)
            if (left >= right || top >= bottom) return true
            val inverse = inverseMethod.invoke(engine, transform) as KoolCanvasTransform? ?: return false
            for (y in top until bottom) for (x in left until right) {
                val point = inverse.map(KoolCanvasPoint(x + 0.5f, y + 0.5f))
                if (point.x >= destination.boundsLeft && point.x < destination.boundsRight &&
                    point.y >= destination.boundsTop && point.y < destination.boundsBottom) covered(x, y, point)
            }
            return true
        }

        override fun close() = store.close()
    }

    private companion object {
        const val WIDTH = 11
        const val HEIGHT = 9

        fun seed(): IntArray = IntArray(WIDTH * HEIGHT) { index ->
            val alpha = intArrayOf(0, 63, 128, 255)[index % 4]
            (alpha shl 24) or ((index * 0x010203) and 0x00ffffff)
        }

        // A value-equal identity is deliberately a distinct object, keeping textures in the
        // generic path rather than the existing Identity singleton copy/sampling optimizations.
        fun transforms() = listOf(KoolCanvasTransform(),
            KoolCanvasTransform(skewX = 0.25f, skewY = -0.1f, translateX = 0.5f),
            KoolCanvasTransform.Identity.rotate(17f, 4f, 3f),
            KoolCanvasTransform(scaleX = -0.75f, scaleY = 1.5f, translateX = 9.125f, translateY = -1.25f),
            KoolCanvasTransform(scaleX = 0.4f, scaleY = -0.75f, translateX = 0.1f, translateY = 7.5f),
            KoolCanvasTransform(skewX = -0.0f, skewY = -0.0f, translateX = -0.0f),
            KoolCanvasTransform(scaleX = 1f, scaleY = 1f, skewX = 1f, skewY = 1f),
            KoolCanvasTransform(scaleX = Float.POSITIVE_INFINITY), KoolCanvasTransform(translateX = Float.NaN))

        fun clips() = listOf(null, KoolCanvasRect(2f, 1f, 7.5f, 6.5f), KoolCanvasRect(20f, 20f, 24f, 24f))

        fun destinations() = listOf(KoolCanvasRect(Math.nextUp(1.5f), Math.nextDown(0.5f), Math.nextDown(8.5f), 7.5f),
            KoolCanvasRect(-2.75f, -1.25f, 7.125f, 6.5f), KoolCanvasRect(1f, 1f, Float.POSITIVE_INFINITY, 7f))
    }
}
