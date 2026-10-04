package io.github.rwx.render.canvas

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KoolCanvasRasterWorkerLifecycleTest {
    @Test
    fun interruptedCallerKeepsPooledPixelsUntilItsWorkersStop() {
        if (KoolGraphicsEngine.Companion.RasterWorkerPool.workerCount <= 1) return
        val engine = KoolGraphicsEngine(textureStore = KoolCanvasCpuTextureStore())
        val method = KoolGraphicsEngine::class.java.getDeclaredMethod(
            "processRasterRows", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Function2::class.java,
        ).apply { isAccessible = true }
        val pool = KoolCanvasPixelPool(maxArraysPerSize = 1)
        val owner = pool.borrow(512 * 512)
        val pixels = owner.pixels
        val workerStarted = CountDownLatch(1)
        val workersCanFinish = CountDownLatch(1)
        val callerFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val interruptRestored = AtomicBoolean()
        val block: (Int, Int) -> Unit = { rowStart, _ ->
            workerStarted.countDown()
            workersCanFinish.await()
            pixels[rowStart * 512] = 7
        }
        val caller = Thread {
            try {
                method.invoke(engine, 0, 512, 512, block)
            } catch (throwable: InvocationTargetException) {
                failure.set(throwable.cause ?: throwable)
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted)
                owner.close()
                callerFinished.countDown()
            }
        }.apply { isDaemon = true }
        try {
            caller.start()
            assertTrue(workerStarted.await(10, TimeUnit.SECONDS))
            caller.interrupt()
            assertFalse(callerFinished.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, pool.stats().retainedArrays)
            workersCanFinish.countDown()
            assertTrue(callerFinished.await(10, TimeUnit.SECONDS))
            assertIs<InterruptedException>(failure.get())
            assertTrue(interruptRestored.get())
            assertEquals(1, pool.stats().retainedArrays)
        } finally {
            workersCanFinish.countDown()
            caller.join(10_000)
            owner.close()
            pool.close()
        }
    }
}
