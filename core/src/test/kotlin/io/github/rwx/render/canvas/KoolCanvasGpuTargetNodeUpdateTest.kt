package io.github.rwx.render.canvas

import de.fabmax.kool.*
import de.fabmax.kool.math.Mat4f
import de.fabmax.kool.math.Vec3i
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.pipeline.backend.RenderBackend
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import kotlin.test.*

class KoolCanvasGpuTargetNodeUpdateTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    private class Context : KoolContext() {
        override val backend: RenderBackend get() = error("No backend in node-update regression")
        override val window: KoolWindow get() = error("No window in node-update regression")
        override fun run() = error("No render loop in node-update regression")
        override fun openUrl(url: String, sameWindow: Boolean) = error("No URL in node-update regression")
        override fun getSysInfos() = emptyList<String>()
    }
    private class Pass(node: Node) : RenderPass(1, MipMode.Single, "node-update-contract") {
        override val colorAttachments = emptyList<RenderPassColorAttachment>()
        override val depthAttachment: RenderPassDepthAttachment? = null
        override val dimensions = Vec3i(64, 64, 1)
        override val views = listOf(View("node-update-view", node, OrthographicCamera()))
        override fun doRelease() = Unit
    }
    private val viewport = KoolCanvasViewport(64, 64)
    private fun rect() = KoolCanvasCommand.DrawRect(KoolCanvasRect(2f, 3f, 20f, 22f),
        KoolCanvasPaint(color = KoolCanvasColor(0xff00ff00.toInt())), KoolCanvasState.Default)

    @Test fun `node model candidate is strictly opt in`() {
        for (value in listOf(null, "", "0", "true", "2")) assertFalse(resolveMapNodeModelUpdatesOnly(value))
        assertTrue(resolveMapNodeModelUpdatesOnly("1"))
    }

    @Test fun `matrix-only replay initializes later cold meshes and preserves clean models`() {
        val source = Texture2d(name = "node-update-source")
        val store = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
            override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter) = source
            override fun frame(id: KoolCanvasTextureId): KoolCanvasFrame? = null
            override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? = null
        }
        val renderer = KoolCanvasFrameRenderer(textureStore = store, useSpriteAtlas = false,
            reuseInstancedTextureShaders = false, reuseMapMeshSlots = false)
        val node = Node("model-only-cell"); val camera = OrthographicCamera()
        try {
            renderer.renderInto(node, camera, KoolCanvasFrame(viewport, listOf(rect()))) {}
            val primitive = node.children.filterIsInstance<Mesh<*>>().single()
            assertNull(primitive.instances)
            assertEquals(0, primitive.modelMatrixData.modCount.count)
            updateGpuTargetModelMatricesAfterReplay(node, true)
            val rootCount = node.modelMatrixData.modCount.count
            val primitiveCount = primitive.modelMatrixData.modCount.count
            assertTrue(rootCount > 0 && primitiveCount > 0)
            assertEquals(Mat4f.IDENTITY, primitive.modelMatF)
            assertTrue(node.bounds.isEmpty && primitive.bounds.isEmpty)
            val texture = KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(KoolCanvasTextureId("model-only"), 8, 8),
                KoolCanvasRect(0f, 0f, 8f, 8f), KoolCanvasRect(4f, 6f, 12f, 14f), KoolCanvasPaint(), KoolCanvasState.Default)
            val frame = KoolCanvasFrame(viewport, listOf(rect(), texture))
            renderer.renderInto(node, camera, frame) {}
            val late = node.children.filterIsInstance<Mesh<*>>().single { it.instances != null }
            assertEquals(0, late.modelMatrixData.modCount.count)
            updateGpuTargetModelMatricesAfterReplay(node, true)
            assertTrue(late.modelMatrixData.modCount.count > 0)
            assertEquals(Mat4f.IDENTITY, late.modelMatF)
            assertEquals(rootCount, node.modelMatrixData.modCount.count)
            assertEquals(primitiveCount, primitive.modelMatrixData.modCount.count)
            val lateCount = late.modelMatrixData.modCount.count
            renderer.renderInto(node, camera, frame) {}
            updateGpuTargetModelMatricesAfterReplay(node, true)
            assertEquals(lateCount, late.modelMatrixData.modCount.count)
            assertTrue(node.children.all { it.bounds.isEmpty })
        } finally { renderer.releaseCachedMeshes(node); node.release(); source.release() }
    }

    @Test fun `default path leaves callbacks and bounds to the normal node update`() {
        val node = Node("normal-cell"); val camera = OrthographicCamera()
        val renderer = KoolCanvasFrameRenderer(useSpriteAtlas = false, reuseInstancedTextureShaders = false, reuseMapMeshSlots = false)
        val pass = Pass(node); var callbacks = 0
        node.onUpdate { callbacks++ }
        try {
            renderer.renderInto(node, camera, KoolCanvasFrame(viewport, listOf(rect()))) {}
            val mesh = node.children.filterIsInstance<Mesh<*>>().single()
            // Canvas replay writes raw vertex data; explicitly provide content bounds so this
            // default-path check can distinguish normal Node.update's bounds aggregation.
            mesh.updateGeometryBounds()
            assertTrue(pass.views.single().isUpdateDrawNode)
            updateGpuTargetModelMatricesAfterReplay(node, resolveMapNodeModelUpdatesOnly(null))
            assertEquals(0, callbacks)
            assertEquals(0, mesh.modelMatrixData.modCount.count)
            assertTrue(node.bounds.isEmpty)
            node.update(RenderPass.UpdateEvent(pass.views.single(), Context()))
            assertEquals(1, callbacks)
            assertTrue(mesh.modelMatrixData.modCount.count > 0)
            assertEquals(Mat4f.IDENTITY, mesh.modelMatF)
            assertFalse(node.bounds.isEmpty)
            assertFalse(mesh.bounds.isEmpty)
        } finally { renderer.releaseCachedMeshes(node); pass.release(); node.release() }
    }
}
