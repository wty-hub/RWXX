package io.github.rwx.render.canvas

import de.fabmax.kool.*
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.pipeline.backend.RenderBackend
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.util.LongHash
import java.lang.reflect.Proxy
import kotlin.test.*

class KoolCanvasShaderTemplatesTest {
    private class Context : KoolContext() {
        var generated = 0
        override val backend = Proxy.newProxyInstance(RenderBackend::class.java.classLoader,
            arrayOf(RenderBackend::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "generateKslShader" -> {
                    val shader = arguments!![0] as de.fabmax.kool.modules.ksl.KslShader
                    assertNotNull(shader.program.vertexStage, "Only a complete model may generate the first template")
                    assertNotNull(shader.program.fragmentStage)
                    object : ShaderCode { override val hash = LongHash((++generated).toLong()) }
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments!![0]
                else -> error("Unexpected backend call: ${method.name}")
            }
        } as RenderBackend
        override val window: KoolWindow get() = error("No window in shader metadata regression")
        override fun openUrl(url: String, sameWindow: Boolean) = error("No URL in shader metadata regression")
        override fun run() = error("No render loop in shader metadata regression")
        override fun getSysInfos() = emptyList<String>()
    }

    @Test fun `cached shader descriptions preserve independent bindings and survive pipeline retirement`() {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        val context = Context()
        val firstTexture = Texture2d(name = "template-texture-first")
        val secondTexture = Texture2d(name = "template-texture-second")
        val config = PipelineConfig(blendMode = BlendMode.BLEND_PREMULTIPLIED_ALPHA)
        val meshes = mutableListOf<Mesh<*>>()
        fun make(premultiplied: Boolean = true, enabled: Boolean = true): Pair<KoolCanvasTextureShader, DrawPipeline> {
            val shader = KoolCanvasShaderTemplates.withTemplates(enabled) { KoolCanvasTextureShader(config, premultiplied) }
            val mesh = Mesh(VertexLayouts.PositionNormalTexCoordColor).also { meshes += it; it.shader = shader }
            return shader to shader.getOrCreatePipeline(mesh, context)
        }
        try {
            val (a, first) = make()
            assertNull(a.program.vertexStage, "Cached material must not rebuild the complete shader AST")
            a.colorMap = firstTexture
            first.captureBuffer()
            val (b, second) = make()
            b.colorMap = secondTexture
            second.captureBuffer()
            assertEquals(1, context.generated)
            assertNotSame(first, second)
            assertSame(first.shaderCode, second.shaderCode)
            assertSame(first.bindGroupLayouts, second.bindGroupLayouts)
            assertNotSame(first.capturedPipelineData, second.capturedPipelineData)
            val binding = first.getBindGroupItemByName("uColorMap").second.bindingIndex
            assertSame(firstTexture, first.capturedPipelineData.texture2dBindingData(binding).texture)
            assertSame(secondTexture, second.capturedPipelineData.texture2dBindingData(binding).texture)
            first.release()
            assertNull(a.createdPipeline)
            assertFalse(second.isReleased)
            assertSame(secondTexture, b.colorMap)
            val (_, third) = make()
            assertSame(first.shaderCode, third.shaderCode)
            assertEquals(1, context.generated, "Retiring a pipeline must not invalidate immutable CPU metadata")
            val (_, differentAlpha) = make(premultiplied = false)
            assertEquals(2, context.generated)
            assertNotSame(first.shaderCode, differentAlpha.shaderCode)
            val (control, _) = make(enabled = false)
            assertNotNull(control.program.vertexStage)
            assertEquals(3, context.generated, "Explicit control must run the original KSL pipeline factory")
        } finally {
            meshes.forEach { it.shader?.createdPipeline?.release(); it.release() }
            firstTexture.release(); secondTexture.release(); context.backgroundScene.release()
        }
    }
}
