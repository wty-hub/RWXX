package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class KoolRasterPixelSourcesLeaseTest {
    @Test fun `raster lease deduplicates aliases and keeps pooled pixels pinned through replacement and unregister`() {
        val pool = KoolCanvasPixelPool(maxArraysPerSize = 1)
        val store = KoolCanvasCpuTextureStore(pool, true)
        val original = KoolCanvasTextureId("raster-original")
        val alias = KoolCanvasTextureId("raster-alias")
        val owner = pool.borrow(8)
        owner.pixels.fill(0xff123456.toInt())
        store.registerPooledArgb(original, 4, 2, owner)
        store.snapshotPixels(original, alias)
        val lease = assertNotNull(store.acquireRasterPixelSources(listOf(original, alias, original)))
        try {
            assertEquals(2, lease.images.size)
            assertSame(owner.pixels, lease.images.getValue(original).pixels)
            assertSame(lease.images.getValue(original), lease.images.getValue(alias))
            owner.close()
            store.registerArgb(original, 4, 2, IntArray(8) { 0xff654321.toInt() }, false)
            store.unregister(alias)
            assertEquals(0, pool.stats().retainedArrays)
            assertContentEquals(IntArray(8) { 0xff123456.toInt() }, lease.images.getValue(alias).pixels)
            lease.close()
            assertEquals(1, pool.stats().retainedArrays)
            lease.close()
            assertEquals(1, pool.stats().retainedArrays)
        } finally {
            lease.close(); owner.close(); store.close()
        }
    }

    @Test fun `missing or nonpixel sources release partial acquisitions before declining raster`() {
        val pool = KoolCanvasPixelPool(maxArraysPerSize = 1)
        val store = KoolCanvasCpuTextureStore(pool, true)
        val pixels = KoolCanvasTextureId("pixels-before-missing")
        val absent = KoolCanvasTextureId("missing-after-pixels")
        val frame = KoolCanvasTextureId("frame-after-pixels")
        val owner = pool.borrow(8)
        store.registerPooledArgb(pixels, 4, 2, owner)
        store.registerFrame(frame, KoolCanvasFrame(KoolCanvasViewport(4, 2), emptyList()))
        try {
            assertNull(store.acquireRasterPixelSources(listOf(pixels, absent)))
            assertNull(store.acquireRasterPixelSources(listOf(pixels, frame)))
            owner.close(); store.unregister(pixels)
            assertEquals(1, pool.stats().retainedArrays, "failed preparation must not retain a hidden raster owner")
        } finally {
            owner.close(); store.close()
        }
    }

    @Test fun `a failed stripe keeps pinned sources until every admitted writer has exited`() {
        val workerCount = KoolGraphicsEngine.Companion.RasterWorkerPool.workerCount
        if (workerCount <= 1) return
        val pool = KoolCanvasPixelPool(maxArraysPerSize = 1)
        val store = KoolCanvasCpuTextureStore(pool, true)
        val id = KoolCanvasTextureId("failed-stripe-pinned-source")
        val owner = pool.borrow(8)
        owner.pixels.fill(73)
        store.registerPooledArgb(id, 4, 2, owner)
        val lease = assertNotNull(store.acquireRasterPixelSources(listOf(id)))
        val allStarted = CountDownLatch(workerCount)
        val firstFailed = CountDownLatch(1)
        val workersCanFinish = CountDownLatch(1)
        val callerFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val engine = KoolGraphicsEngine(textureStore = store)
        val dispatcher = KoolGraphicsEngine::class.java.getDeclaredMethod("processRasterRows",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            Function2::class.java).apply { isAccessible = true }
        val block: (Int, Int) -> Unit = { rowStart, _ ->
            assertTrue(KoolGraphicsEngine.Companion.RasterWorkerPool.insideWorker.get())
            allStarted.countDown()
            assertTrue(allStarted.await(10, TimeUnit.SECONDS))
            if (rowStart == 0) {
                firstFailed.countDown()
                throw IllegalStateException("deliberate stripe failure")
            }
            workersCanFinish.await()
            assertEquals(73, lease.images.getValue(id).pixels[0])
        }
        val caller = Thread {
            try {
                lease.use { dispatcher.invoke(engine, 0, 512, 512, block) }
            } catch (error: InvocationTargetException) {
                failure.set(error.targetException)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                callerFinished.countDown()
            }
        }.apply { isDaemon = true }
        try {
            caller.start()
            assertTrue(firstFailed.await(10, TimeUnit.SECONDS))
            owner.close(); store.unregister(id)
            assertFalse(callerFinished.await(100, TimeUnit.MILLISECONDS), "failed job must wait for other admitted writers")
            assertEquals(0, pool.stats().retainedArrays, "source pixels must stay pinned while a writer reads them")
            workersCanFinish.countDown()
            assertTrue(callerFinished.await(10, TimeUnit.SECONDS))
            assertTrue(failure.get() is IllegalStateException)
            assertEquals(1, pool.stats().retainedArrays)
        } finally {
            workersCanFinish.countDown(); caller.join(10_000)
            lease.close(); owner.close(); store.close()
        }
    }
}
