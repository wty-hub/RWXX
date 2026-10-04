package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import io.github.rwx.geometry.Rect
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Run in a dedicated JVM with RWX_PARALLEL_CELL_RASTER=1 and adaptive raster disabled. */
class KoolCellRasterTargetLeaseIntegrationTest {
    @Test
    fun `public striped target commits preserve completed packets across replacement incremental clear and legacy edits`() {
        assumeTrue(System.getenv("RWX_PARALLEL_CELL_RASTER") == "1",
            "Dedicated whole-cell integration requires RWX_PARALLEL_CELL_RASTER=1; default-off runs do not exercise stripes")
        assumeTrue(System.getenv("RWX_ADAPTIVE_CELL_RASTER") != "1",
            "This ownership integration exercises the original whole-cell policy, with adaptive raster disabled")
        assumeTrue(KoolGraphicsEngine.Companion.RasterWorkerPool.workerCount > 1,
            "Whole-cell integration needs at least two raster workers")

        val width = 256
        val height = 257
        val pool = KoolCanvasPixelPool(maxArraysPerSize = 1)
        val store = KoolCanvasCpuTextureStore(pool, true)
        val root = KoolGraphicsEngine(textureStore = store)
        val target = root.b(width, height, true)
        val graphics = root.b(target, RenderTargetMode.IMMEDIATE)
        val initial = IntArray(width * height) { 0xff000000.toInt() or ((it * 7919) and 0x00ffffff) }
        val source = root.a(width, height, true).apply { j = initial.copyOf(); p() }
        val packets = mutableListOf<FrameEnvelope>()

        fun freeze(sequence: Long): FrozenCanvasResource.Pixels {
            root.beginFrame(width, height)
            root.b(target, 0f, 0f, null)
            val packet = store.freezeFrame(root.snapshot(), sequence, 1, sequence.toInt(), 1)
            packets += packet
            return packet.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
        }

        try {
            graphics.a(0, KoolCanvasBlendMode.Clear)
            graphics.b(source, 0f, 0f, null)
            commitAndRequireStripes(graphics)
            val first = freeze(1)
            assertContentEquals(initial, first.image.pixels)

            // A full replacement must borrow another allocation while the first packet owns its pixels.
            val replacement = 0xff184080.toInt()
            graphics.a(replacement, KoolCanvasBlendMode.Source)
            graphics.a(Rect(0, 0, width, height), paint(replacement, KoolCanvasBlendMode.Source))
            commitAndRequireStripes(graphics)
            val second = freeze(2)
            val replacementPixels = IntArray(width * height) { replacement }
            assertContentEquals(replacementPixels, second.image.pixels)
            assertContentEquals(initial, first.image.pixels)

            // No leading full clear: preserve the previous pixels, clearing only a partial rectangle.
            val partial = Rect(13, 64, width - 17, height - 29)
            graphics.a(partial, paint(0, KoolCanvasBlendMode.Clear))
            commitAndRequireStripes(graphics)
            val third = freeze(3)
            val incremental = replacementPixels.copyOf().also { pixels ->
                for (y in partial.b until partial.d) {
                    pixels.fill(0, y * width + partial.a, y * width + partial.c)
                }
            }
            assertContentEquals(incremental, third.image.pixels)
            assertContentEquals(replacementPixels, second.image.pixels)
            assertContentEquals(initial, first.image.pixels)

            // The legacy write path must detach the target array retained by the third packet.
            target.a(0, 0, 0xffabcdef.toInt())
            assertEquals(0xffabcdef.toInt(), assertNotNull(target.argbPixelsRef)[0])
            assertContentEquals(incremental, third.image.pixels)
            graphics.q()
            target.o()
            source.o()
            store.close()
            assertContentEquals(initial, first.image.pixels)
            assertContentEquals(replacementPixels, second.image.pixels)
            assertContentEquals(incremental, third.image.pixels)
            assertTrue(pool.stats().activeAllocations > 0, "completed packets still own their original pooled allocations")
            packets.forEach { it.close() }
            assertEquals(0, pool.stats().activeAllocations, "no source or target owner may outlive the completed packets")
        } finally {
            packets.forEach { it.close() }
            graphics.q()
            target.o()
            source.o()
            store.close()
            pool.close()
        }
    }

    private fun commitAndRequireStripes(graphics: GraphicsEngine) {
        val previousProperty = System.getProperty(KoolGraphicsEngine.CPU_TARGET_PROFILE_PROPERTY)
        val previousOutput = System.out
        val output = ByteArrayOutputStream()
        val capture = PrintStream(output, true, Charsets.UTF_8)
        try {
            System.setProperty(KoolGraphicsEngine.CPU_TARGET_PROFILE_PROPERTY, "true")
            System.setOut(capture)
            graphics.p()
        } finally {
            System.setOut(previousOutput)
            if (previousProperty == null) System.clearProperty(KoolGraphicsEngine.CPU_TARGET_PROFILE_PROPERTY)
            else System.setProperty(KoolGraphicsEngine.CPU_TARGET_PROFILE_PROPERTY, previousProperty)
            capture.close()
        }
        val trace = output.toString(Charsets.UTF_8)
        assertTrue(trace.contains("success=true") && trace.contains("stripeRaster=true"),
            "public commit must complete using whole-cell stripes, not silently fall back: $trace")
    }

    private fun paint(color: Int, blend: KoolCanvasBlendMode): KoolPaint = KoolPaint().apply {
        setColor(color)
        a(blend)
    }
}
