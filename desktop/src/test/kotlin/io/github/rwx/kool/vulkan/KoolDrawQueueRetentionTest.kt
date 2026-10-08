package io.github.rwx.kool.vulkan

import de.fabmax.kool.math.Vec3i
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.PerspectiveCamera
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.util.LongHash
import kotlin.test.*

/** Exercises the loaded queue and real draw commands without a GPU or window. */
class KoolDrawQueueRetentionTest {
    @Test fun `a group returning after an empty frame is still visited and recycled`() {
        val pass = object : RenderPass(1, MipMode.Single, "queue-retention-test") {
            override val colorAttachments = emptyList<RenderPassColorAttachment>()
            override val depthAttachment: RenderPassDepthAttachment? = null
            override val dimensions = Vec3i(8, 8, 1)
            override val views = listOf(View("view", Node("node"), PerspectiveCamera()))
            override fun doRelease() {}
        }
        val mesh = Mesh(VertexLayouts.Position)
        val pipeline = DrawPipeline("queue-test", PipelineConfig(),
            VertexLayout(emptyList(), mesh.geometry.primitiveType),
            BindGroupLayouts(
                BindGroupLayout(0, BindGroupScope.VIEW, emptyList(), "view"),
                BindGroupLayout(1, BindGroupScope.PIPELINE, emptyList(), "pipeline"),
                BindGroupLayout(2, BindGroupScope.MESH, emptyList(), "mesh"),
            )) { object : ShaderCode { override val hash = LongHash(42L) } }
        val queue = DrawQueue()
        try {
            queue.reset(pass.views.single())
            queue.drawGroupId = 1
            val first = queue.addMesh(mesh, pipeline)
            assertEquals(listOf(first), visited(queue))
            queue.reset(pass.views.single()) // Recycle first, leaving group 1 empty.
            queue.reset(pass.views.single()) // Prune the empty group.
            queue.drawGroupId = 1
            val returning = queue.addMesh(mesh, pipeline)
            assertEquals(listOf(returning), visited(queue), "A retained group hint must not reference a pruned queue")
            queue.reset(pass.views.single())
            queue.drawGroupId = 9
            val recycled = queue.addMesh(mesh, pipeline)
            assertSame(returning, recycled, "A returning command must remain owned by the queue's recycle pool")
            assertEquals(listOf(recycled), visited(queue))
        } finally { mesh.release(); pipeline.release(); pass.release() }
    }

    private fun visited(queue: DrawQueue) = buildList { queue.forEach { add(it) } }
}
