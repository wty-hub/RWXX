package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.backend.GpuTexture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A `RenderTargetMode.GPU_TARGET` target must look like an ordinary sampleable texture to the replay
 * renderer while never producing CPU pixels.
 *
 * The contract these tests pin down:
 *  - the commit path must not rasterise and must not publish a replay frame, because
 *    `KoolCanvasFrameRenderer.addFrameTexture` expands a published frame's command list at *every*
 *    sampling point 鈥?the quadratic path a GPU target exists to avoid;
 *  - the recorded payload must reach the render-thread owner as a [FrozenCanvasResource.GpuTarget]
 *    carrying its stable logical id and its size;
 *  - one content version is shared by every lease that references it, so an unchanged cell installs
 *    once and is released only when its last lease goes away;
 *  - the canvas filter selects the sampler view of the same attachment image.
 *
 * Rendering the pass itself needs a live kool backend and is covered by the Vulkan oracle, not here.
 */
class KoolCanvasGpuTargetTest {
    private val stores = mutableListOf<KoolCanvasCpuTextureStore>()
    private val previousInstaller = FrozenCanvasGpuResources.gpuTargetInstaller
    private val previousReleaser = FrozenCanvasGpuResources.gpuTargetReleaser

    @AfterTest
    fun restoreHooks() {
        FrozenCanvasGpuResources.gpuTargetInstaller = previousInstaller
        FrozenCanvasGpuResources.gpuTargetReleaser = previousReleaser
        stores.forEach { it.close() }
        stores.clear()
        KoolCanvasTextureRegistry.unregister(GPU_VERSION_ID)
    }

    @Test
    fun `gpu target commits reach the render thread as one shared content version`() {
        val installed = mutableListOf<Pair<KoolCanvasTextureId, FrozenCanvasResource.GpuTarget>>()
        val released = mutableListOf<KoolCanvasTextureId>()
        FrozenCanvasGpuResources.gpuTargetInstaller = { id, target -> installed += id to target }
        FrozenCanvasGpuResources.gpuTargetReleaser = { id ->
            if (installed.any { it.first == id }) released += id
        }

        val store = newStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val width = 96
        val height = 64
        val target = root.b(width, height, true)
        val cell = root.b(target, RenderTargetMode.GPU_TARGET)
        val stamp = root.a(8, 8, true).apply { j = IntArray(64) { 0xff336699.toInt() }; p() }

        cell.a(0, KoolCanvasBlendMode.Clear)
        cell.b(stamp, 0f, 0f, null)
        cell.p()
        cell.q()

        val first = freezeParent(store, root, target, width, height, sequence = 1L)
        val (versionId, gpuTarget) = first.gpuTargetResource()
        assertEquals(width, gpuTarget.width)
        assertEquals(height, gpuTarget.height)
        assertEquals(width, gpuTarget.frame.viewport.width)
        assertEquals(height, gpuTarget.frame.viewport.height)

        // Sampling must bind the pass attachment, so neither a replay frame nor CPU pixels may exist
        // for the logical target...
        assertNull(store.frame(gpuTarget.logicalId), "a GPU target must not publish a replay frame")
        assertNull(store.argbImageView(gpuTarget.logicalId), "a GPU target must not publish CPU pixels")
        assertNull(store.frame(versionId))
        // ...and the parent samples the version, not the logical target id.
        assertEquals(
            versionId,
            first.frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().single().texture.id,
        )

        val handle = FrozenCanvasGpuResources.install(first.resourceLease)
        assertEquals(1, installed.size)
        assertEquals(versionId, installed.single().first)
        assertNull(KoolCanvasTextureRegistry.frame(versionId), "GPU versions are sampled, never replayed")

        // Unchanged content keeps its version, so a second lease reuses the same pass.
        val second = freezeParent(store, root, target, width, height, sequence = 2L)
        assertEquals(versionId, second.gpuTargetResource().first, "unchanged content must keep its version")
        val secondHandle = FrozenCanvasGpuResources.install(second.resourceLease)
        assertEquals(1, installed.size, "a shared version must install its pass exactly once")

        secondHandle.close()
        assertTrue(released.isEmpty(), "a version still referenced by another lease must not be released")
        handle.close()
        assertEquals(listOf(versionId), released, "the last lease releases the version")

        first.close()
        second.close()
    }

    @Test
    fun `a superseded gpu target version stays sampleable and is released with its lease`() {
        val installed = mutableListOf<KoolCanvasTextureId>()
        val released = mutableListOf<KoolCanvasTextureId>()
        FrozenCanvasGpuResources.gpuTargetInstaller = { id, _ -> installed += id }
        FrozenCanvasGpuResources.gpuTargetReleaser = { id -> if (id in installed) released += id }

        val store = newStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val target = root.b(32, 32, true)
        val cell = root.b(target, RenderTargetMode.GPU_TARGET)
        val stamp = root.a(8, 8, true).apply { j = IntArray(64) { 0xff112233.toInt() }; p() }

        fun commit(colour: Int) {
            cell.a(colour, KoolCanvasBlendMode.Source)
            cell.b(stamp, 0f, 0f, null)
            cell.p()
        }

        commit(0xff000000.toInt())
        val first = freezeParent(store, root, target, 32, 32, sequence = 1L)
        val firstVersion = first.gpuTargetResource().first
        val firstHandle = FrozenCanvasGpuResources.install(first.resourceLease)
        assertEquals(listOf(firstVersion), installed)

        commit(0xff00ff00.toInt())
        val second = freezeParent(store, root, target, 32, 32, sequence = 2L)
        val secondVersion = second.gpuTargetResource().first
        // Whatever the second commit did, the referenced version must never become a replay frame:
        // that is the quadratic path this target type exists to avoid.
        assertNull(store.frame(secondVersion), "a superseded GPU version must not become a replay frame")
        val secondHandle = FrozenCanvasGpuResources.install(second.resourceLease)
        assertTrue(secondHandle != null)

        // The superseded version keeps its image until its own lease retires.
        firstHandle.close()
        assertEquals(listOf(firstVersion), released)
        secondHandle.close()
        assertTrue(released.contains(secondVersion), "released=$released second=$secondVersion")

        cell.q()
        first.close()
        second.close()
    }

    @Test
    fun `gpu target textures resolve per canvas filter`() {
        val registry = KoolCanvasTextureRegistry
        val linear = Texture2d(name = "gpu-target-linear")
        val nearest = Texture2d(name = "gpu-target-nearest")
        val ref = KoolCanvasTextureRef(GPU_VERSION_ID, 16, 16)
        registry.registerGpuTargetTexture(GPU_VERSION_ID, linear, nearest)
        try {
            assertTrue(registry.isGpuTargetTexture(GPU_VERSION_ID))
            // Until the backend has created the attachment image there is nothing to sample, so both
            // filters must degrade to a drawable placeholder instead of an unloaded texture.
            assertNotSame(linear, registry.resolve(ref, KoolCanvasTextureFilter.Linear))
            assertNotSame(nearest, registry.resolve(ref, KoolCanvasTextureFilter.Nearest))

            val image = FakeGpuTexture(16, 16)
            linear.gpuTexture = image
            nearest.gpuTexture = image
            assertSame(linear, registry.resolve(ref, KoolCanvasTextureFilter.Linear))
            assertSame(nearest, registry.resolve(ref, KoolCanvasTextureFilter.Nearest))
        } finally {
            registry.unregister(GPU_VERSION_ID)
        }
        assertFalse(registry.isGpuTargetTexture(GPU_VERSION_ID))
        // The logical id keeps resolving to something drawable instead of throwing.
        assertNotNull(registry.resolve(ref, KoolCanvasTextureFilter.Linear))
    }

    @Test
    fun `without a render thread owner a gpu target still commits cpu pixels`() {
        FrozenCanvasGpuResources.gpuTargetInstaller = null
        val store = newStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val target = root.b(16, 16, true)
        val cell = root.b(target, RenderTargetMode.GPU_TARGET)

        cell.a(0xff20a0ff.toInt(), KoolCanvasBlendMode.Source)
        cell.p()
        cell.q()

        val parent = freezeParent(store, root, target, 16, 16, sequence = 1L)
        val resource = parent.resourceLease.resources().values.single()
        val versionId = parent.frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().single().texture.id
        assertTrue(resource is FrozenCanvasResource.Pixels, "headless and test runs keep the CPU fallback")
        assertContentEquals(
            IntArray(16 * 16) { 0xff20a0ff.toInt() },
            (resource as FrozenCanvasResource.Pixels).image.pixels,
        )
        assertNull(store.frame(versionId), "the fallback must not publish a replay frame either")
        parent.close()
    }

    @Test
    fun `gpu render targets follow the documented switches with the hard off winning`() {
        // Default off; flipping it on additionally needs the switch assertions updated and the A/B
        // harness to disable the path on its baseline side. The feature itself is gated separately on the
        // host reporting a live kool backend (setGpuOffscreenPassesAvailable).
        assertTrue(KoolGraphicsEngine.gpuRenderTargetsEnabled(emptyMap(), null))
        assertTrue(KoolGraphicsEngine.gpuRenderTargetsEnabled(mapOf("RWX_GPU_MAP_CELL_TARGETS" to "1"), null))
        assertTrue(KoolGraphicsEngine.gpuRenderTargetsEnabled(mapOf("RWX_GPU_MAP_CELL_CACHE" to "1"), null))
        assertTrue(KoolGraphicsEngine.gpuRenderTargetsEnabled(emptyMap(), "true"))
        // The runner's switch must reach the same code path; unrelated values must not enable it.
        assertTrue(KoolGraphicsEngine.gpuRenderTargetsEnabled(mapOf("RWX_GPU_MAP_CELL_CACHE" to "0"), null))
        assertTrue(KoolGraphicsEngine.gpuRenderTargetsEnabled(mapOf("RWX_GPU_MAP_CELL_CACHE" to "yes"), null))
        // A controlled A/B needs a hard off that beats every enabling switch.
        assertFalse(
            KoolGraphicsEngine.gpuRenderTargetsEnabled(
                mapOf("RWX_GPU_MAP_CELL_TARGETS" to "0", "RWX_GPU_MAP_CELL_CACHE" to "1"),
                "true",
            ),
        )
    }

    private fun newStore(): KoolCanvasCpuTextureStore =
        KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true).also { stores += it }

    private fun freezeParent(
        store: KoolCanvasCpuTextureStore,
        root: KoolGraphicsEngine,
        target: com.corrodinggames.rts.gameFramework.graphics.Texture,
        width: Int,
        height: Int,
        sequence: Long,
    ): FrameEnvelope {
        root.beginFrame(width, height)
        root.b(target, 0f, 0f, null)
        return store.freezeFrame(root.snapshot(), sequence, 1L, sequence.toInt(), 1L)
    }

    private fun FrameEnvelope.gpuTargetResource(): Pair<KoolCanvasTextureId, FrozenCanvasResource.GpuTarget> {
        val resources = resourceLease.resources()
        val entry = resources.entries.singleOrNull { it.value is FrozenCanvasResource.GpuTarget }
            ?: error("no GPU target in lease: ${resources.map { "${it.key.value}->${it.value::class.simpleName}" }}")
        return entry.key to entry.value as FrozenCanvasResource.GpuTarget
    }

    private class FakeGpuTexture(
        override val width: Int,
        override val height: Int,
    ) : GpuTexture {
        override val depth: Int get() = 1
        override val isReleased: Boolean get() = false
        override fun release() = Unit
    }

    private companion object {
        val GPU_VERSION_ID = KoolCanvasTextureId("legacy-texture-gpu-version-test")
    }
}
