package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolAdaptiveCellRasterTest {
    private val width = 512
    private val height = 512
    private val id = KoolCanvasTextureId("adaptive-source")

    private class RasterFixture(val store: KoolCanvasCpuTextureStore = KoolCanvasCpuTextureStore()) : AutoCloseable {
        val engine = KoolGraphicsEngine(textureStore = store)
        private val serial = KoolGraphicsEngine::class.java.declaredMethods.single { it.name == "rasterizeFrameCommands" }
            .apply { isAccessible = true }
        private val adaptive = KoolGraphicsEngine::class.java.declaredMethods.single { it.name == "rasterizeCellStripesWithPolicy" }
            .apply { isAccessible = true }
        private val selection = KoolGraphicsEngine::class.java.declaredMethods.single { it.name == "adaptiveCellRasterHasEnoughTextureWork" }
            .apply { isAccessible = true }
        fun serial(frame: KoolCanvasFrame, pixels: IntArray): IntArray? =
            serial.invoke(engine, frame, pixels, frame.viewport.width, frame.viewport.height,
                emptySet<KoolCanvasTextureId>(), null, false) as IntArray?
        fun adaptive(frame: KoolCanvasFrame, pixels: IntArray): IntArray? =
            adaptive.invoke(engine, frame, pixels, frame.viewport.width, frame.viewport.height, null, false, true) as IntArray?
        fun selected(frame: KoolCanvasFrame, sources: Map<KoolCanvasTextureId, KoolCanvasArgbImage>): Boolean =
            selection.invoke(engine, frame, sources, frame.viewport.width, frame.viewport.height) as Boolean
        override fun close() = store.close()
    }

    private fun frame(commands: List<KoolCanvasCommand>) = KoolCanvasFrame(KoolCanvasViewport(width, height), commands)
    private fun draw(sourceWidth: Int, sourceHeight: Int, paint: KoolCanvasPaint, state: KoolCanvasState = KoolCanvasState.Default) =
        KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, sourceWidth, sourceHeight, hasAlpha = true),
            KoolCanvasRect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat()),
            KoolCanvasRect(0f, 0f, width.toFloat(), height.toFloat()), paint, state)

    @Test fun `adaptive effective mode requires both explicit opt ins`() {
        assertFalse(KoolGraphicsEngine.Companion.adaptiveCellRasterEnabled(emptyMap()))
        assertFalse(KoolGraphicsEngine.Companion.adaptiveCellRasterEnabled(mapOf("RWX_PARALLEL_CELL_RASTER" to "1")))
        assertFalse(KoolGraphicsEngine.Companion.adaptiveCellRasterEnabled(mapOf("RWX_ADAPTIVE_CELL_RASTER" to "1")))
        assertFalse(KoolGraphicsEngine.Companion.adaptiveCellRasterEnabled(mapOf(
            "RWX_PARALLEL_CELL_RASTER" to "0", "RWX_ADAPTIVE_CELL_RASTER" to "1")))
        assertTrue(KoolGraphicsEngine.Companion.adaptiveCellRasterEnabled(mapOf(
            "RWX_PARALLEL_CELL_RASTER" to "1", "RWX_ADAPTIVE_CELL_RASTER" to "1")))
    }

    @Test fun `empty clear and fog fill cells decline whole cell stripes without touching the target`() {
        RasterFixture().use { raster ->
            for (commands in listOf<List<KoolCanvasCommand>>(
                emptyList(),
                listOf(KoolCanvasCommand.Clear(KoolCanvasColor(0), KoolCanvasBlendMode.Clear)),
                listOf(KoolCanvasCommand.Clear(KoolCanvasColor(0xff203040.toInt()), KoolCanvasBlendMode.Source),
                    KoolCanvasCommand.DrawRect(KoolCanvasRect(0f, 0f, 512f, 512f),
                        KoolCanvasPaint(color = KoolCanvasColor(0x80304050.toInt()), blendMode = KoolCanvasBlendMode.SourceOver), KoolCanvasState.Default)),
            )) {
                val frame = frame(commands)
                val original = IntArray(width * height) { 0x69302010 + it % 31 }
                val actual = original.copyOf()
                assertFalse(raster.selected(frame, emptyMap()))
                assertNull(raster.adaptive(frame, actual))
                assertContentEquals(original, actual, "a policy decline must occur before any target writes")
                assertSame(actual, raster.serial(frame, actual))
            }
        }
    }

    @Test fun `exact opaque and source row copies stay serial including clipped copies and premultiplied sources`() {
        RasterFixture().use { raster ->
            for (premultiplied in listOf(false, true)) {
                val pixels = IntArray(width * height) { 0xff000000.toInt() or (it and 0xffffff) }
                if (premultiplied) raster.store.registerPremultipliedArgb(id, width, height, pixels)
                else raster.store.registerArgb(id, width, height, pixels, false)
                val sources = mapOf(id to assertNotNull(raster.store.argbImageView(id)))
                for (blend in listOf(KoolCanvasBlendMode.Source, KoolCanvasBlendMode.SourceOver)) {
                    for (clip in listOf(null, KoolCanvasRect(17f, 31f, 491f, 477f))) {
                        val frame = frame(listOf(draw(width, height,
                            KoolCanvasPaint(textureFilter = KoolCanvasTextureFilter.Nearest, blendMode = blend), KoolCanvasState(clip = clip))))
                        assertFalse(raster.selected(frame, sources), "blend=$blend premultiplied=$premultiplied clip=$clip")
                        val actual = IntArray(width * height) { 0x70302010 }
                        val original = actual.copyOf()
                        assertNull(raster.adaptive(frame, actual))
                        assertContentEquals(original, actual)
                        val expected = original.copyOf()
                        raster.serial(frame, expected); raster.serial(frame, actual)
                        assertContentEquals(expected, actual)
                    }
                }
            }
            raster.store.registerArgb(id, width, height, IntArray(width * height) { 0x80302010.toInt() }, false)
            assertFalse(raster.selected(frame(listOf(draw(width, height,
                KoolCanvasPaint(textureFilter = KoolCanvasTextureFilter.Nearest, blendMode = KoolCanvasBlendMode.Source)))),
                mapOf(id to assertNotNull(raster.store.argbImageView(id)))))
        }
    }

    @Test fun `target clipping excludes small sampled work but a full sampled or translucent cell selects stripes exactly`() {
        RasterFixture().use { raster ->
            val source = IntArray(23 * 19) { index -> ((index % 4) * 85 shl 24) or (index * 97 and 0xffffff) }
            raster.store.registerArgb(id, 23, 19, source, false)
            val sources = mapOf(id to assertNotNull(raster.store.argbImageView(id)))
            val small = frame(listOf(draw(23, 19, KoolCanvasPaint.Default,
                KoolCanvasState(clip = KoolCanvasRect(0f, 0f, 16f, 16f)))))
            assertFalse(raster.selected(small, sources))
            assertNull(raster.adaptive(small, IntArray(width * height)))
            for (filter in KoolCanvasTextureFilter.entries) {
                val frame = frame(listOf(
                    KoolCanvasCommand.Clear(KoolCanvasColor(0x79304050), KoolCanvasBlendMode.Source),
                    draw(23, 19, KoolCanvasPaint(textureFilter = filter, blendMode = KoolCanvasBlendMode.SourceOver))))
                assertTrue(raster.selected(frame, sources))
                if (KoolGraphicsEngine.Companion.RasterWorkerPool.workerCount > 1) {
                    val expected = IntArray(width * height); val actual = IntArray(width * height)
                    raster.serial(frame, expected)
                    assertSame(actual, raster.adaptive(frame, actual))
                    assertContentEquals(expected, actual, "filter=$filter")
                }
            }
            // An integer 1:1 source-over command needs real compositing when its pixels have alpha.
            raster.store.registerArgb(id, width, height, IntArray(width * height) { 0x80302010.toInt() }, false)
            assertTrue(raster.selected(frame(listOf(draw(width, height, KoolCanvasPaint.DefaultNearest))),
                mapOf(id to assertNotNull(raster.store.argbImageView(id)))))
        }
    }

    @Test fun `many small tile commands aggregate their clipped sampling work`() {
        RasterFixture().use { raster ->
            raster.store.registerArgb(id, 8, 8, IntArray(64) { 0x80402010.toInt() }, false)
            val sources = mapOf(id to assertNotNull(raster.store.argbImageView(id)))
            val reference = KoolCanvasTextureRef(id, 8, 8, hasAlpha = true)
            val commands = (0 until 256 step 32).flatMap { y -> (0 until 256 step 32).map { x ->
                KoolCanvasCommand.DrawTexture(reference, KoolCanvasRect(0f, 0f, 8f, 8f),
                    KoolCanvasRect(x.toFloat(), y.toFloat(), x + 32f, y + 32f),
                    KoolCanvasPaint(textureFilter = KoolCanvasTextureFilter.Linear), KoolCanvasState.Default)
            }}
            assertFalse(raster.selected(frame(commands.take(1)), sources))
            assertTrue(raster.selected(frame(commands), sources))
        }
    }

    @Test fun `adaptive fast copy fallback closes every pooled source lease`() {
        if (KoolGraphicsEngine.Companion.RasterWorkerPool.workerCount <= 1) return
        val pool = KoolCanvasPixelPool(maxArraysPerSize = 1)
        val store = KoolCanvasCpuTextureStore(pool, true)
        RasterFixture(store).use { raster ->
            val owner = pool.borrow(width * height)
            try {
                owner.pixels.fill(0xff123456.toInt())
                store.registerPooledArgb(id, width, height, owner)
                val command = draw(width, height, KoolCanvasPaint.DefaultNearest)
                val actual = IntArray(width * height) { 73 }
                assertNull(raster.adaptive(frame(listOf(command)), actual))
                assertTrue(actual.all { it == 73 })
                owner.close(); store.unregister(id)
                assertEquals(1, pool.stats().retainedArrays, "declining adaptive policy must close the prepared raster lease")
            } finally {
                owner.close()
            }
        }
    }
}
