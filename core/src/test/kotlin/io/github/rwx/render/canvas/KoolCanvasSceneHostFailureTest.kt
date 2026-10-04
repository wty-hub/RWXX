package io.github.rwx.render.canvas

import de.fabmax.kool.scene.Scene
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.util.forEachUpdated
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolCanvasSceneHostFailureTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }
    private fun frame(pool: KoolCanvasPixelPool, sequence: Long): FrameEnvelope {
        val raster = pool.borrow(1)
        try {
            raster.pixels[0] = sequence.toInt()
            val resource = FrozenCanvasResource.Pixels(KoolCanvasArgbImage(1, 1, raster.pixels), false, raster)
            val lease = KoolCanvasResourceLease(mapOf(KoolCanvasTextureId("host-failure-$sequence") to resource),
                KoolCanvasFontRegistry.snapshot())
            return FrameEnvelope(sequence, 1, 42, 1, KoolCanvasFrame(KoolCanvasViewport(4, 4), emptyList()), lease)
        } finally {
            raster.close()
        }
    }

    private fun renderChosen(scene: Scene) {
        scene.onRenderScene.forEachUpdated { callback -> runBlocking { callback.onRenderScene() } }
    }

    @Test
    fun failedInstallReleasesPolledFrontAndItsPooledPixels() {
        val pool = KoolCanvasPixelPool()
        val next = frame(pool, 1)
        val failure = IllegalStateException("injected install failure")
        val host = KoolCanvasSceneHost(installResources = { throw failure })
        val scene = host.createScene()
        try {
            host.submit(next)
            assertSame(failure, assertFailsWith<IllegalStateException> { renderChosen(scene) })
            assertTrue(next.resourceLease.isReleased)
            assertEquals(0, pool.stats().activeAllocations)
            assertEquals(1, pool.stats().retainedArrays)
        } finally {
            scene.release()
            next.close()
            pool.close()
        }
    }

    @Test
    fun rejectingRetirementKeepsOldOwnersAndReleasesTheUnrenderedNewFrame() {
        val pool = KoolCanvasPixelPool()
        val old = frame(pool, 1)
        val next = frame(pool, 2)
        var closedInstalls = 0
        val host = KoolCanvasSceneHost(installResources = { lease ->
            lease.retain()
            val closed = AtomicBoolean()
            AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    closedInstalls++
                    lease.close()
                }
            }
        })
        val scene = host.createScene()
        val pending = mutableListOf<() -> Unit>()
        val failure = IllegalStateException("injected retirement failure")
        try {
            host.submit(old)
            renderChosen(scene)
            host.setGpuRetirementSink { release ->
                release() // Even a synchronous callback must not release before acceptance.
                pending += release
                throw failure
            }
            host.submit(next)
            assertSame(failure, assertFailsWith<IllegalStateException> { renderChosen(scene) })
            assertFalse(old.resourceLease.isReleased)
            assertTrue(next.resourceLease.isReleased)
            assertEquals(1, closedInstalls)
            assertEquals(1, pool.stats().activeAllocations)
            assertSame(old.frame, host.currentFrame())
            pending.single().invoke()
            assertFalse(old.resourceLease.isReleased)
            assertEquals(1, closedInstalls)
            host.setGpuRetirementSink(null)
            scene.release()
            assertTrue(old.resourceLease.isReleased)
            assertEquals(2, closedInstalls)
            assertEquals(0, pool.stats().activeAllocations)
        } finally {
            host.setGpuRetirementSink(null)
            scene.release()
            pending.forEach { it() }
            old.close()
            next.close()
            pool.close()
            KoolCanvasTextureRegistry.releaseRetiredTextures()
        }
    }
}
