package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.forEachUpdated
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class KoolFrozenPixelMeshReuseTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }
    private val viewport = KoolCanvasViewport(100, 100)

    private fun command(
        id: KoolCanvasTextureId,
        alpha: Boolean = false,
        state: KoolCanvasState = KoolCanvasState.Default,
        x: Float = 10f,
    ) = KoolCanvasCommand.DrawTexture(
        KoolCanvasTextureRef(id, 2, 2, hasAlpha = alpha),
        KoolCanvasRect.fromSize(2f, 2f), KoolCanvasRect(x, 10f, x + 2f, 12f),
        KoolCanvasPaint.Default, state,
    )

    private fun freeze(store: KoolCanvasCpuTextureStore, command: KoolCanvasCommand.DrawTexture,
                       color: Int, sequence: Long = 1): FrameEnvelope {
        store.registerArgb(command.texture.id, 2, 2, IntArray(4) { color }, alphaBleed = false)
        return store.freezeFrame(KoolCanvasFrame(viewport, listOf(command)), sequence, 1, 0, 0)
    }

    private fun visible(scene: Scene): List<Mesh<*>> =
        scene.children.filterIsInstance<Mesh<*>>().filter { it.isVisible }.sortedBy { it.drawGroupId }

    private fun boundTexture(mesh: Mesh<*>): Texture2d? = when (val shader = mesh.shader) {
        is KoolCanvasTextureShader -> shader.colorMap
        is KoolCanvasInstancedTextureShader -> shader.colorMap
        is KoolCanvasAffineInstancedTextureShader -> shader.colorMap
        else -> error("Unexpected texture shader: $shader")
    }

    private inline fun withInstalled(vararg frames: FrameEnvelope, action: () -> Unit) {
        val installed = frames.map { FrozenCanvasGpuResources.install(it.resourceLease) }
        try { action() } finally {
            installed.reversed().forEach { it.close() }
            frames.forEach { it.close() }
            KoolCanvasTextureRegistry.releaseRetiredTextures()
            KoolCanvasTextureRegistry.releaseRetiredTextures()
        }
    }

    @Test
    fun `new pixel versions reuse actual meshes and shaders in every ordinary texture path`() {
        val paths = listOf(
            false to KoolCanvasState.Default,
            true to KoolCanvasState.Default,
            false to KoolCanvasState(transform = KoolCanvasTransform.Identity.translate(3f, 1f)),
            true to KoolCanvasState(transform = KoolCanvasTransform.Identity.translate(3f, 1f)),
            false to KoolCanvasState(clip = KoolCanvasRect.fromSize(100f, 100f)),
        )
        for ((index, path) in paths.withIndex()) {
            val store = KoolCanvasCpuTextureStore()
            val draw = command(KoolCanvasTextureId("reuse-cell-$index"), path.first, path.second)
            val first = freeze(store, draw, 0xff123456.toInt())
            val second = freeze(store, draw, 0xff654321.toInt(), 2)
            val firstRef = (first.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture
            val secondRef = (second.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture
            assertNotEquals(firstRef.id, secondRef.id)
            assertEquals(firstRef.frozenPixelIdentity, secondRef.frozenPixelIdentity)
            withInstalled(first, second) {
                val scene = Scene("reuse-path-$index")
                val renderer = KoolCanvasFrameRenderer()
                renderer.render(scene, first.frame)
                val mesh = visible(scene).single()
                val shader = mesh.shader
                val oldTexture = assertNotNull(boundTexture(mesh))
                renderer.render(scene, second.frame)
                assertSame(mesh, visible(scene).single(), "path $index must keep its mesh")
                assertSame(shader, mesh.shader, "path $index must keep the shader/pipeline owner")
                assertNotSame(oldTexture, boundTexture(mesh))
                assertTrue(assertNotNull(boundTexture(mesh)).name.contains(secondRef.id.value))
            }
        }
    }

    @Test
    fun `two historical versions in one frame use distinct actual meshes and bindings`() {
        val store = KoolCanvasCpuTextureStore()
        val draw = command(KoolCanvasTextureId("two-versions-cell"))
        val first = freeze(store, draw, 0xff123456.toInt())
        val second = freeze(store, draw.copy(destination = KoolCanvasRect(30f, 10f, 32f, 12f)),
            0xff654321.toInt(), 2)
        withInstalled(first, second) {
            val scene = Scene("two-versions")
            val renderer = KoolCanvasFrameRenderer()
            renderer.render(scene, KoolCanvasFrame(viewport, first.frame.commands + second.frame.commands))
            val meshes = visible(scene)
            assertEquals(2, meshes.size)
            assertNotSame(meshes[0].shader, meshes[1].shader)
            assertNotSame(boundTexture(meshes[0]), boundTexture(meshes[1]))
            assertEquals(listOf(1, 1), meshes.map { it.instances!!.numInstances })
            val versions = (first.frame.commands + second.frame.commands).map {
                (it as KoolCanvasCommand.DrawTexture).texture.id.value
            }
            assertTrue(assertNotNull(boundTexture(meshes[0])).name.contains(versions[0]))
            assertTrue(assertNotNull(boundTexture(meshes[1])).name.contains(versions[1]))

            // Slot zero may change versions next frame, while the unused slot becomes invisible.
            renderer.render(scene, second.frame)
            assertSame(meshes[0], visible(scene).single())
            assertTrue(assertNotNull(boundTexture(meshes[0])).name.contains(versions[1]))
        }
    }

    @Test
    fun `recording owner is part of the explicit stable identity`() {
        val draw = command(KoolCanvasTextureId("identical-logical-cell"))
        val first = freeze(KoolCanvasCpuTextureStore(), draw, 0xff123456.toInt())
        val second = freeze(KoolCanvasCpuTextureStore(), draw, 0xff654321.toInt())
        val firstRef = (first.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture
        val secondRef = (second.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture
        assertNotEquals(firstRef.frozenPixelIdentity, secondRef.frozenPixelIdentity)
        withInstalled(first, second) {
            val scene = Scene("different-recording-owners")
            val renderer = KoolCanvasFrameRenderer()
            renderer.render(scene, first.frame)
            val oldMesh = visible(scene).single()
            renderer.render(scene, second.frame)
            assertNotSame(oldMesh, visible(scene).single())
        }
    }

    @Test
    fun `version-looking ordinary ids do not acquire a stable identity by parsing`() {
        val ids = listOf(KoolCanvasTextureId("ordinary/cpu-1-version-10"),
            KoolCanvasTextureId("ordinary/cpu-1-version-11"))
        try {
            ids.forEach { KoolCanvasTextureRegistry.registerArgb(it, 2, 2, IntArray(4) { -1 }, false) }
            val scene = Scene("ordinary-revision-looking-textures")
            val renderer = KoolCanvasFrameRenderer()
            renderer.render(scene, KoolCanvasFrame(viewport, listOf(command(ids[0]))))
            val oldMesh = visible(scene).single()
            renderer.render(scene, KoolCanvasFrame(viewport, listOf(command(ids[1]))))
            assertNotSame(oldMesh, visible(scene).single())
        } finally {
            ids.forEach(KoolCanvasTextureRegistry::unregister)
            KoolCanvasTextureRegistry.releaseRetiredTextures()
            KoolCanvasTextureRegistry.releaseRetiredTextures()
        }
    }

    @Test
    fun `ordered old new old versions preserve separate batches and bindings`() {
        val store = KoolCanvasCpuTextureStore()
        val draw = command(KoolCanvasTextureId("ordered-version-cell"), alpha = true)
        val first = freeze(store, draw, 0x80123456.toInt())
        val second = freeze(store, draw, 0x80654321.toInt(), 2)
        withInstalled(first, second) {
            val scene = Scene("ordered-two-version-batches")
            val renderer = KoolCanvasFrameRenderer()
            renderer.render(scene, first.frame)
            val firstMesh = visible(scene).single()
            renderer.render(scene, KoolCanvasFrame(viewport,
                first.frame.commands + second.frame.commands + first.frame.commands))
            val meshes = visible(scene)
            assertEquals(3, meshes.size)
            assertSame(firstMesh, meshes[0])
            assertEquals(3, meshes.map { it.drawGroupId }.distinct().size)
            assertSame(boundTexture(meshes[0]), boundTexture(meshes[2]))
            assertNotSame(boundTexture(meshes[0]), boundTexture(meshes[1]))
            assertEquals(listOf(1, 1, 1), meshes.map { it.instances!!.numInstances })
        }
    }

    @Test
    fun `mesh rebinding retains old immutable pixels and texture until the retirement fence`() {
        val store = KoolCanvasCpuTextureStore()
        val draw = command(KoolCanvasTextureId("retired-version-cell"))
        val first = freeze(store, draw, 0xff123456.toInt())
        val second = freeze(store, draw, 0xff654321.toInt(), 2)
        val firstId = (first.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        val secondId = (second.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        val releases = mutableListOf<() -> Unit>()
        val host = KoolCanvasSceneHost()
        host.setGpuRetirementSink { releases += it }
        val scene = host.createScene()
        fun renderChosen() { scene.onRenderScene.forEachUpdated { callback -> runBlocking { callback.onRenderScene() } } }
        fun completeFence() {
            while (releases.isNotEmpty()) {
                val completed = releases.toList()
                releases.clear()
                completed.forEach { it() }
            }
        }
        try {
            host.submit(first.retain())
            renderChosen()
            val mesh = visible(scene).single()
            val oldTexture = assertNotNull(boundTexture(mesh))
            host.submit(second.retain())
            renderChosen()
            assertSame(mesh, visible(scene).single())
            val newTexture = assertNotNull(boundTexture(mesh))
            assertNotSame(oldTexture, newTexture)
            assertFalse(oldTexture.isReleased)
            assertContentEquals(IntArray(4) { 0xff123456.toInt() },
                assertNotNull(KoolCanvasTextureRegistry.argbImageView(firstId)).pixels)
            assertTrue(releases.isNotEmpty())
            completeFence()
            assertNull(KoolCanvasTextureRegistry.argbImageView(firstId))
            KoolCanvasTextureRegistry.releaseRetiredTextures()
            KoolCanvasTextureRegistry.releaseRetiredTextures()
            assertTrue(oldTexture.isReleased)
            assertFalse(newTexture.isReleased)
            assertSame(newTexture, boundTexture(mesh))
            assertContentEquals(IntArray(4) { 0xff654321.toInt() },
                assertNotNull(KoolCanvasTextureRegistry.argbImageView(secondId)).pixels)
            val frozen = first.resourceLease.resources()[firstId] as FrozenCanvasResource.Pixels
            assertContentEquals(IntArray(4) { 0xff123456.toInt() }, frozen.image.pixels)
        } finally {
            scene.release()
            completeFence()
            host.setGpuRetirementSink(null)
            first.close()
            second.close()
            KoolCanvasTextureRegistry.releaseRetiredTextures()
            KoolCanvasTextureRegistry.releaseRetiredTextures()
        }
    }
}
