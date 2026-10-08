package io.github.rwx.render.canvas

import de.fabmax.kool.KoolContext
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.modules.ksl.lang.KslProgram
import de.fabmax.kool.modules.ksl.blocks.mvpMatrix
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.pipeline.backend.RenderBackend
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.geometry.PrimitiveType
import de.fabmax.kool.util.Struct
import java.util.WeakHashMap

/** Immutable CPU descriptions only. Every shader still creates its own pipeline and binding data. */
internal object KoolCanvasShaderTemplates {
    private val requested = (System.getenv("RWX_CACHE_CANVAS_SHADER_TEMPLATES") == "1").also {
        if (System.getenv("RWX_FRAME_METRICS") != null) println("RWXCanvasShaderTemplates enabled=$it")
    }
    private val templateOverride = ThreadLocal<Boolean?>()
    val enabledForNewShader: Boolean get() = templateOverride.get() ?: requested
    private data class Key(val kind: String, val premultiplied: Boolean, val multipliesRgbByAlpha: Boolean,
        val config: PipelineConfig, val vertices: Struct, val instances: Struct?, val primitive: PrimitiveType)
    private data class Template(val vertices: VertexLayout, val bindings: BindGroupLayouts, val code: ShaderCode)
    private val contexts = WeakHashMap<RenderBackend, LinkedHashMap<Key, Template>>()
    private var hits = 0L
    private var created = 0L
    init {
        if (System.getenv("RWX_FRAME_METRICS") != null) Runtime.getRuntime().addShutdownHook(Thread({
            println("RWXCanvasShaderTemplates totals created=$created hits=$hits")
        }, "rwx-shader-template-summary"))
    }

    fun <T> withTemplates(enabled: Boolean, block: () -> T): T {
        val previous = templateOverride.get()
        templateOverride.set(enabled)
        return try { block() } finally { if (previous == null) templateOverride.remove() else templateOverride.set(previous) }
    }

    /** Cached shaders only need their own MVP listener and texture binding, not a new shader AST. */
    fun program(name: String, create: () -> KslProgram): KslProgram =
        if (enabledForNewShader) KslProgram(name).apply { mvpMatrix() } else create()

    private class Builder(program: KslProgram, config: PipelineConfig) : KslShader(program, config) {
        fun build(mesh: Mesh<*>, instances: MeshInstanceList<*>?, context: KoolContext) =
            super.createPipeline(mesh, instances, context)
    }

    fun build(config: PipelineConfig, mesh: Mesh<*>, instances: MeshInstanceList<*>?, context: KoolContext,
        create: () -> KslProgram): DrawPipeline = Builder(create(), config).build(mesh, instances, context)

    fun pipeline(enabled: Boolean, kind: String, premultiplied: Boolean, multipliesRgbByAlpha: Boolean,
        config: PipelineConfig, mesh: Mesh<*>, instances: MeshInstanceList<*>?, context: KoolContext,
        create: () -> DrawPipeline): DrawPipeline {
        if (!enabled) return create()
        val start = CanvasRenderStageTrace.start()
        val key = Key(kind, premultiplied, multipliesRgbByAlpha, config,
            mesh.geometry.layout, instances?.layout, mesh.geometry.primitiveType)
        val cache = contexts.getOrPut(context.backend) { LinkedHashMap() }
        val template = cache[key]
        if (template != null) {
            // DrawPipeline constructs fresh per-pipeline binding storage. Its native resources continue
            // to use the existing mesh ownership / retirement fences; no native handle is shared here.
            hits++
            return DrawPipeline(kind, config, template.vertices, template.bindings) { template.code }.also {
                CanvasRenderStageTrace.record("shader-template-hit", start)
            }
        }
        return create().also { pipeline ->
            if (cache.size >= 64) cache.remove(cache.keys.first())
            cache[key] = Template(pipeline.vertexLayout, pipeline.bindGroupLayouts, pipeline.shaderCode)
            created++
            CanvasRenderStageTrace.record("shader-template-miss", start)
        }
    }
}
