package io.github.rwx.render.canvas

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolCellRasterStripeTest {
    private val width = 256
    private val height = 257
    private fun seed() = IntArray(width * height) { 0x79302010 + it % 97 }
    private val id = KoolCanvasTextureId("stripe-source")
    private val sourceRef = KoolCanvasTextureRef(id, 23, 19, hasAlpha = true)

    private class RasterFixture : AutoCloseable {
        val store = KoolCanvasCpuTextureStore()
        val engine = KoolGraphicsEngine(textureStore = store)
        private val serial = KoolGraphicsEngine::class.java.declaredMethods.single { it.name == "rasterizeFrameCommands" }
            .apply { isAccessible = true }
        private val parallel = KoolGraphicsEngine::class.java.declaredMethods.single { it.name == "rasterizeCellStripes" }
            .apply { isAccessible = true }
        fun serial(frame: KoolCanvasFrame, pixels: IntArray, zero: Boolean = false): IntArray? =
            serial.invoke(engine, frame, pixels, frame.viewport.width, frame.viewport.height,
                emptySet<KoolCanvasTextureId>(), null, zero) as IntArray?
        fun parallel(frame: KoolCanvasFrame, pixels: IntArray, zero: Boolean = false): IntArray? =
            parallel.invoke(engine, frame, pixels, frame.viewport.width, frame.viewport.height, null, zero) as IntArray?
        override fun close() = store.close()
    }

    @Test fun `row stripes preserve overlapping tile order exact bilinear alpha tint clipping and transforms`() {
        if (KoolGraphicsEngine.Companion.RasterWorkerPool.workerCount <= 1) return
        RasterFixture().use { raster ->
            val source = IntArray(23 * 19) { index ->
                val alpha = listOf(0, 1, 128, 255)[index % 4]
                (alpha shl 24) or (((index * 17) and 255) shl 16) or (((index * 29) and 255) shl 8) or ((index * 43) and 255)
            }
            for (premultiplied in listOf(false, true)) {
                if (premultiplied) raster.store.registerPremultipliedArgb(id, 23, 19, source)
                else raster.store.registerArgb(id, 23, 19, source, false)
                for (filter in KoolCanvasTextureFilter.entries) for (blend in listOf(KoolCanvasBlendMode.SourceOver, KoolCanvasBlendMode.Source, KoolCanvasBlendMode.Add)) {
                    val commands = mutableListOf<KoolCanvasCommand>(
                        KoolCanvasCommand.Clear(KoolCanvasColor(0xff234567.toInt()), KoolCanvasBlendMode.SourceOver),
                        KoolCanvasCommand.Clear(KoolCanvasColor(0x19234567), KoolCanvasBlendMode.ClearAlpha),
                    )
                    for (y in 0 until height step 15) for (x in 0 until width step 17) {
                        commands += KoolCanvasCommand.DrawTexture(sourceRef,
                            KoolCanvasRect(.25f, .5f, 22.75f, 18.5f),
                            KoolCanvasRect(x - .25f, y + .3f, x + 21.1f, y + 19.7f),
                            KoolCanvasPaint(textureFilter = filter, blendMode = blend,
                                color = KoolCanvasColor(0xdde08040.toInt()), alphaMultiplier = .7f),
                            KoolCanvasState(clip = KoolCanvasRect(3.4f, 2.6f, 250.3f, 253.5f)))
                    }
                    for (transform in listOf(KoolCanvasTransform.Identity, KoolCanvasTransform(scaleX = .8f, scaleY = 1.2f, translateX = 5f),
                        KoolCanvasTransform(scaleX = -1f, scaleY = .75f, translateX = 252f, translateY = 21f),
                        KoolCanvasTransform(skewX = .15f, skewY = -.1f, translateY = 7f))) {
                        commands += KoolCanvasCommand.DrawTexture(sourceRef, KoolCanvasRect(23f, 0f, 0f, 19f),
                            KoolCanvasRect(5.5f, 32.25f, 196.75f, 231.5f),
                            KoolCanvasPaint(textureFilter = filter, blendMode = blend),
                            KoolCanvasState(transform = transform, clip = KoolCanvasRect(9f, 5f, 235f, 251f)))
                        commands += KoolCanvasCommand.DrawRect(KoolCanvasRect(1f, 64f, 220f, 191f),
                            KoolCanvasPaint(color = KoolCanvasColor(0x60403020), blendMode = blend), KoolCanvasState(transform = transform))
                    }
                    val frame = KoolCanvasFrame(KoolCanvasViewport(width, height), commands)
                    val expected = seed(); val actual = seed()
                    assertSame(expected, raster.serial(frame, expected))
                    assertSame(actual, raster.parallel(frame, actual))
                    assertContentEquals(expected, actual, "premultiplied=$premultiplied filter=$filter blend=$blend")
                }
            }
        }
    }

    @Test fun `partial updates and every clear type affect each row exactly once`() {
        if (KoolGraphicsEngine.Companion.RasterWorkerPool.workerCount <= 1) return
        RasterFixture().use { raster ->
            for (zero in listOf(false, true)) for (clear in KoolCanvasBlendMode.entries) {
                val frame = KoolCanvasFrame(KoolCanvasViewport(width, height), listOf(
                    KoolCanvasCommand.DrawRect(KoolCanvasRect(0f, 0f, 256f, 257f), KoolCanvasPaint(color = KoolCanvasColor(0x70304050)), KoolCanvasState.Default),
                    KoolCanvasCommand.Clear(KoolCanvasColor(0xa0204060.toInt()), clear),
                    KoolCanvasCommand.DrawRect(KoolCanvasRect(0f, 63.25f, 256f, 195.75f), KoolCanvasPaint(color = KoolCanvasColor(0x4f90b0d0)), KoolCanvasState.Default),
                ))
                val expected = if (zero) IntArray(width * height) else seed()
                val actual = expected.copyOf()
                raster.serial(frame, expected, zero)
                assertNotNull(raster.parallel(frame, actual, zero))
                assertContentEquals(expected, actual, "clear=$clear zero=$zero")
            }
            for (zero in listOf(false, true)) {
                val frame = KoolCanvasFrame(KoolCanvasViewport(width, height), listOf(
                    KoolCanvasCommand.Clear(KoolCanvasColor(0xff123456.toInt()), KoolCanvasBlendMode.Source),
                    KoolCanvasCommand.Clear(KoolCanvasColor(0), KoolCanvasBlendMode.Clear),
                    KoolCanvasCommand.DrawRect(KoolCanvasRect(0f, 0f, 256f, 257f), KoolCanvasPaint(color = KoolCanvasColor(0x80402010.toInt())), KoolCanvasState.Default),
                ))
                val expected = if (zero) IntArray(width * height) else seed()
                val actual = expected.copyOf()
                raster.serial(frame, expected, zero); raster.parallel(frame, actual, zero)
                assertContentEquals(expected, actual)
            }
        }
    }

    @Test fun `unsupported missing nested frame and nonfinite sources decline stripes before target writes`() {
        RasterFixture().use { raster ->
            val draw = KoolCanvasCommand.DrawTexture(sourceRef, KoolCanvasRect(0f, 0f, 23f, 19f),
                KoolCanvasRect(0f, 0f, 256f, 257f), KoolCanvasPaint.Default, KoolCanvasState.Default)
            val unsupported = listOf<KoolCanvasCommand>(
                draw,
                KoolCanvasCommand.DrawRect(KoolCanvasRect(0f, 0f, 256f, 257f), KoolCanvasPaint(style = KoolCanvasPaintStyle.Stroke), KoolCanvasState.Default),
                draw.copy(destination = KoolCanvasRect(Float.NaN, 0f, 256f, 257f)),
                draw.copy(state = KoolCanvasState(transform = KoolCanvasTransform(scaleX = 0f))),
                KoolCanvasCommand.Clear(KoolCanvasColor(0), KoolCanvasBlendMode.Clear, KoolCanvasRenderTargetId("other")),
            )
            for (command in unsupported) {
                val frame = KoolCanvasFrame(KoolCanvasViewport(width, height), listOf(
                    KoolCanvasCommand.Clear(KoolCanvasColor(0), KoolCanvasBlendMode.Clear), command))
                val original = seed(); val actual = original.copyOf()
                assertNull(raster.parallel(frame, actual))
                assertContentEquals(original, actual)
            }
            raster.store.registerFrame(id, KoolCanvasFrame(KoolCanvasViewport(23, 19), emptyList()))
            val actual = seed(); val original = actual.copyOf()
            assertNull(raster.parallel(KoolCanvasFrame(KoolCanvasViewport(width, height), listOf(draw)), actual))
            assertContentEquals(original, actual)
        }
    }

    @Test fun `worker sampling failure propagates after all admitted writers stop without publishing a target`() {
        if (KoolGraphicsEngine.Companion.RasterWorkerPool.workerCount <= 1) return
        RasterFixture().use { raster ->
            // CPU stores normally prevent incomplete sources. This test installs a deliberately
            // malformed immutable view to exercise a failure from inside the worker sampling loop.
            raster.store.registerArgb(id, 23, 19, IntArray(23 * 19) { -1 }, false)
            val malformed = assertNotNull(raster.store.argbImageView(id))
            KoolCanvasArgbImage::class.java.getDeclaredField("pixels").apply { isAccessible = true }
                .set(malformed, intArrayOf(-1))
            val frame = KoolCanvasFrame(KoolCanvasViewport(width, height), listOf(
                KoolCanvasCommand.DrawTexture(sourceRef, KoolCanvasRect(0f, 0f, 23f, 19f), KoolCanvasRect(0f, 0f, 256f, 257f),
                    KoolCanvasPaint.DefaultNearest, KoolCanvasState.Default)))
            val actual = seed()
            val failure = runCatching { raster.parallel(frame, actual) }.exceptionOrNull() as InvocationTargetException
            assertTrue(failure.targetException is IndexOutOfBoundsException)
            val stopped = actual.copyOf()
            // Executor barriers complete after its already admitted stripe jobs. They must observe
            // the same final target, proving a thrown sampling error did not let writers escape.
            val barriers = List(KoolGraphicsEngine.Companion.RasterWorkerPool.workerCount) {
                KoolGraphicsEngine.Companion.RasterWorkerPool.executor.submit { assertContentEquals(stopped, actual) }
            }
            barriers.forEach { it.get() }
            assertEquals(false, KoolGraphicsEngine.Companion.RasterWorkerPool.insideWorker.get())
        }
    }
}
