package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.backend.GpuTexture
import de.fabmax.kool.util.forEachUpdated
import io.github.rwx.session.GameCameraSnapshot
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolCanvasSceneHostReadinessTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    @Test
    fun `cold target keeps the old picture and camera until ready without another submission`() {
        Fixture().use { fixture ->
            val old = fixture.frame(1, ready = true)
            val next = fixture.frame(2)
            fixture.host.submit(old.envelope)
            fixture.present()
            assertSame(old.envelope.camera, fixture.tracker.cameraSnapshot())

            fixture.host.submit(next.envelope)
            fixture.present()
            assertSame(old.envelope.frame, fixture.host.currentFrame())
            assertSame(old.envelope.camera, fixture.tracker.cameraSnapshot())
            assertFalse(old.envelope.resourceLease.isReleased)
            assertFalse(next.envelope.resourceLease.isReleased)
            assertFalse(KoolCanvasTextureRegistry.isGpuTargetTextureReady(next.id))

            fixture.present()
            assertSame(old.envelope.frame, fixture.host.currentFrame())
            assertEquals(listOf(old.id, next.id), fixture.installedIds)

            fixture.makeReady(next)
            fixture.present()
            assertSame(next.envelope.frame, fixture.host.currentFrame())
            assertSame(next.envelope.camera, fixture.tracker.cameraSnapshot())
            assertTrue(old.envelope.resourceLease.isReleased)
            assertFalse(next.envelope.resourceLease.isReleased)
            assertEquals(listOf(old.id, next.id), fixture.installedIds, "readiness must not reinstall resources")
        }
    }

    @Test
    fun `ready targets are shown in the callback that installs them`() {
        Fixture().use { fixture ->
            val first = fixture.frame(1, ready = true)
            fixture.host.submit(first.envelope)
            fixture.present()
            assertSame(first.envelope.frame, fixture.host.currentFrame())
            assertSame(first.envelope.camera, fixture.tracker.cameraSnapshot())

            val next = fixture.frame(2, ready = true)
            fixture.host.submit(next.envelope)
            fixture.present()
            assertSame(next.envelope.frame, fixture.host.currentFrame())
            assertSame(next.envelope.camera, fixture.tracker.cameraSnapshot())
            assertTrue(first.envelope.resourceLease.isReleased)
        }
    }

    @Test
    fun `warming frame is adopted before consuming the latest merged mailbox frame`() {
        Fixture().use { fixture ->
            val old = fixture.frame(1, ready = true)
            val warming = fixture.frame(2)
            val skipped = fixture.frame(3, ready = true)
            val latest = fixture.frame(4, ready = true)
            fixture.host.submit(old.envelope)
            fixture.present()
            fixture.host.submit(warming.envelope)
            fixture.present()

            fixture.host.submit(skipped.envelope)
            fixture.host.submit(latest.envelope)
            assertTrue(skipped.envelope.resourceLease.isReleased)
            fixture.present()
            assertSame(old.envelope.frame, fixture.host.currentFrame())
            assertEquals(listOf(old.id, warming.id), fixture.installedIds)

            fixture.makeReady(warming)
            fixture.present()
            assertSame(warming.envelope.frame, fixture.host.currentFrame())
            assertSame(warming.envelope.camera, fixture.tracker.cameraSnapshot())
            assertEquals(listOf(old.id, warming.id), fixture.installedIds)
            assertFalse(latest.envelope.resourceLease.isReleased)

            fixture.present()
            assertSame(latest.envelope.frame, fixture.host.currentFrame())
            assertSame(latest.envelope.camera, fixture.tracker.cameraSnapshot())
            assertEquals(listOf(old.id, warming.id, latest.id), fixture.installedIds)
            assertTrue(warming.envelope.resourceLease.isReleased)
        }
    }

    @Test
    fun `switching to direct frames retires the warming resources only after their fence`() {
        Fixture().use { fixture ->
            val old = fixture.frame(1, ready = true)
            val warming = fixture.frame(2)
            fixture.host.submit(old.envelope)
            fixture.present()
            fixture.host.submit(warming.envelope)
            fixture.present()
            fixture.deferRetirements()

            val direct = KoolCanvasFrame(KoolCanvasViewport(16, 12), emptyList())
            fixture.host.render(direct)
            fixture.present()
            assertEquals(direct, fixture.host.currentFrame())
            assertFalse(old.envelope.resourceLease.isReleased)
            assertFalse(warming.envelope.resourceLease.isReleased)
            assertTrue(KoolCanvasTextureRegistry.isGpuTargetTexture(warming.id))
            assertTrue(fixture.retirements.isNotEmpty())

            fixture.makeReady(warming)
            fixture.present()
            assertEquals(direct, fixture.host.currentFrame(), "a cancelled warming frame must never be adopted")
            fixture.completeRetirements()
            assertTrue(old.envelope.resourceLease.isReleased)
            assertTrue(warming.envelope.resourceLease.isReleased)
            assertFalse(KoolCanvasTextureRegistry.isGpuTargetTexture(warming.id))
            assertEquals(1, fixture.closedIds.count { it == warming.id })
        }
    }

    @Test
    fun `scene destruction retains warmed resources until fence completion and discards mailbox`() {
        Fixture().use { fixture ->
            val old = fixture.frame(1, ready = true)
            val warming = fixture.frame(2)
            val queued = fixture.frame(3, ready = true)
            fixture.host.submit(old.envelope)
            fixture.present()
            fixture.host.submit(warming.envelope)
            fixture.present()
            fixture.host.submit(queued.envelope)
            fixture.deferRetirements()

            fixture.scene.release()
            assertNull(fixture.tracker.cameraSnapshot())
            assertTrue(queued.envelope.resourceLease.isReleased)
            assertFalse(old.envelope.resourceLease.isReleased)
            assertFalse(warming.envelope.resourceLease.isReleased)
            assertTrue(KoolCanvasTextureRegistry.isGpuTargetTexture(warming.id))

            fixture.completeRetirements()
            assertTrue(old.envelope.resourceLease.isReleased)
            assertTrue(warming.envelope.resourceLease.isReleased)
            assertFalse(KoolCanvasTextureRegistry.isGpuTargetTexture(warming.id))
            assertEquals(listOf(old.id, warming.id).toSet(), fixture.closedIds.toSet())
            assertEquals(2, fixture.closedIds.size)
        }
    }

    private data class TestFrame(val envelope: FrameEnvelope, val id: KoolCanvasTextureId)

    /** Exercise presentation and ownership without a native render backend or texture uploads. */
    private class Fixture : AutoCloseable {
        private val serial = nextFixture.incrementAndGet()
        private val frames = mutableListOf<TestFrame>()
        private val readyIds = mutableSetOf<KoolCanvasTextureId>()
        private val textures = mutableMapOf<KoolCanvasTextureId, Pair<Texture2d, Texture2d>>()
        val installedIds = mutableListOf<KoolCanvasTextureId>()
        val closedIds = mutableListOf<KoolCanvasTextureId>()
        val retirements = mutableListOf<() -> Unit>()
        val tracker = CanvasFramePresentationTracker()
        val host = KoolCanvasSceneHost(installResources = ::install)
        val scene = host.createScene()

        init { host.setPresentationTracker(tracker) }

        fun frame(sequence: Long, ready: Boolean = false): TestFrame {
            val id = KoolCanvasTextureId("host-readiness-$serial-$sequence")
            val viewport = KoolCanvasViewport(8, 8)
            val commands = KoolCanvasFrame(viewport, emptyList())
            val target = FrozenCanvasResource.GpuTarget(id, commands, 8, 8)
            val lease = KoolCanvasResourceLease(mapOf(id to target), KoolCanvasFontRegistry.snapshot())
            val camera = GameCameraSnapshot(sequence, sequence, viewport, sequence * 100f, 200f, 2f, 1)
            return TestFrame(FrameEnvelope(sequence, 1, sequence.toInt(), 1, commands, lease, camera), id).also {
                frames += it
                if (ready) makeReady(it)
            }
        }

        fun makeReady(frame: TestFrame) {
            readyIds += frame.id
            textures[frame.id]?.let { (linear, nearest) ->
                val image = FakeGpuTexture()
                linear.gpuTexture = image
                nearest.gpuTexture = image
            }
        }

        fun present() {
            CanvasFramePresentation.beginFrame()
            scene.onRenderScene.forEachUpdated { callback -> runBlocking { callback.onRenderScene() } }
            CanvasFramePresentation.presented(true)
        }

        fun deferRetirements() { host.setGpuRetirementSink { retirements += it } }

        fun completeRetirements() {
            while (retirements.isNotEmpty()) {
                val completed = retirements.toList()
                retirements.clear()
                completed.forEach { it() }
            }
        }

        private fun install(lease: KoolCanvasResourceLease): AutoCloseable {
            lease.retain()
            val ids = lease.resources().keys.toList()
            ids.forEach { id ->
                val linear = Texture2d(name = "${id.value}-linear")
                val nearest = Texture2d(name = "${id.value}-nearest")
                if (id in readyIds) {
                    val image = FakeGpuTexture()
                    linear.gpuTexture = image
                    nearest.gpuTexture = image
                }
                textures[id] = linear to nearest
                KoolCanvasTextureRegistry.registerGpuTargetTexture(id, linear, nearest)
                installedIds += id
            }
            val closed = AtomicBoolean()
            return AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    try {
                        ids.forEach { id ->
                            KoolCanvasTextureRegistry.unregister(id)
                            closedIds += id
                        }
                    } finally {
                        lease.close()
                    }
                }
            }
        }

        override fun close() {
            host.setGpuRetirementSink(null)
            scene.release()
            completeRetirements()
            frames.forEach { it.envelope.close() }
            textures.forEach { (id, views) ->
                KoolCanvasTextureRegistry.unregister(id)
                views.first.release()
                views.second.release()
            }
            CanvasFramePresentation.beginFrame()
        }
    }

    private class FakeGpuTexture : GpuTexture {
        override val width: Int get() = 8
        override val height: Int get() = 8
        override val depth: Int get() = 1
        override val isReleased: Boolean get() = false
        override fun release() = Unit
    }

    private companion object {
        val nextFixture = AtomicInteger()
    }
}
