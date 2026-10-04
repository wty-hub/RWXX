package io.github.rwx.kool.vulkan

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolContext
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.platform.WindowSubsystem
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.util.LongHash
import java.lang.reflect.Proxy
import kotlin.test.*

/** Uses the actual loaded Mesh API, without starting a window, backend or native pipeline. */
class KoolMeshPipelineOwnershipTest {
    private class Shader : DrawShader("pipeline-ownership-test") {
        override fun createPipeline(mesh: Mesh<*>, instances: MeshInstanceList<*>?, ctx: KoolContext) =
            DrawPipeline(name, PipelineConfig(), VertexLayout(emptyList(), mesh.geometry.primitiveType),
                BindGroupLayouts(
                    BindGroupLayout(0, BindGroupScope.VIEW, emptyList(), "view"),
                    BindGroupLayout(1, BindGroupScope.PIPELINE, emptyList(), "pipeline"),
                    BindGroupLayout(2, BindGroupScope.MESH, emptyList(), "mesh"),
                )) { object : ShaderCode { override val hash = LongHash(34L) } }
    }

    @Test fun `new uncaptured Mesh protects the shared pipeline when the last old captured Mesh retires`() = withTestContext { context ->
        val shader = Shader()
        val old = Mesh(VertexLayouts.PositionNormalColor).apply { this.shader = shader }
        val next = Mesh(VertexLayouts.PositionNormalColor).apply { this.shader = shader }
        val pipeline = checkNotNull(old.getOrCreatePipeline(context))
        // Existing captureData registration is retained; the new Mesh has not captured yet.
        pipeline.addUser(old)
        var releases = 0
        pipeline.onRelease { releases++ }
        try {
            assertSame(pipeline, next.getOrCreatePipeline(context))
            old.release()
            assertFalse(pipeline.isReleased, "A collected Mesh owns its pipeline before captureData")
            assertSame(pipeline, shader.createdPipeline)
            assertSame(pipeline, next.getOrCreatePipeline(context))
            next.release()
            assertTrue(pipeline.isReleased)
            assertNull(shader.createdPipeline)
            assertEquals(1, releases)
        } finally { old.release(); next.release() }
    }

    @Test fun `cached and hidden Mesh acquisition is idempotent and shader change and Scene close balance ownership`() = withTestContext { context ->
        val firstShader = Shader()
        val nextShader = Shader()
        val mesh = Mesh(VertexLayouts.PositionNormalColor).apply { shader = firstShader; isVisible = false }
        val first = checkNotNull(mesh.getOrCreatePipeline(context))
        val usersField = DrawPipeline::class.java.getDeclaredField("users").apply { isAccessible = true }
        val scene = Scene("pipeline-ownership-close")
        scene.addNode(mesh)
        try {
            repeat(8) { assertSame(first, mesh.getOrCreatePipeline(context)) }
            assertEquals(setOf(mesh), usersField.get(first))
            mesh.shader = nextShader
            assertTrue(first.isReleased)
            assertNull(firstShader.createdPipeline)
            val next = checkNotNull(mesh.getOrCreatePipeline(context))
            assertEquals(setOf(mesh), usersField.get(next))
            scene.release()
            assertTrue(next.isReleased)
            assertNull(nextShader.createdPipeline)
            assertTrue(mesh.isReleased)
            val noShader = Mesh(VertexLayouts.PositionNormalColor)
            try { assertNull(noShader.getOrCreatePipeline(context)) } finally { noShader.release() }
        } finally { scene.release(); mesh.release() }
    }

    private fun withTestContext(block: (Lwjgl3Context) -> Unit) {
        // Same constructor-only context seam as KoolFrameRateLimitTest. No backend is started.
        val subsystem = Proxy.newProxyInstance(WindowSubsystem::class.java.classLoader,
            arrayOf(WindowSubsystem::class.java)) { _, method, _ ->
            when (method.name) {
                "isCloseRequested" -> false
                else -> error("Unexpected subsystem call: ${method.name}")
            }
        } as WindowSubsystem
        val config = KoolConfigJvm(windowSubsystem = subsystem)
        val configField = KoolSystem::class.java.getDeclaredField("initConfig").apply { isAccessible = true }
        val initializedField = KoolSystem::class.java.getDeclaredField("isInitialized").apply { isAccessible = true }
        val previousConfig = configField.get(null)
        val previousInitialized = initializedField.getBoolean(null)
        try {
            if (!previousInitialized) KoolSystem.initialize(config)
            val context = Lwjgl3Context::class.java.getDeclaredConstructor(KoolConfigJvm::class.java).run {
                isAccessible = true
                newInstance(config)
            }
            // Scene creates its ScreenPass from KoolSystem.config too, so keep initialization
            // alive for the complete API regression, not only this constructor.
            block(context)
        } finally {
            configField.set(null, previousConfig)
            initializedField.setBoolean(null, previousInitialized)
        }
    }
}
