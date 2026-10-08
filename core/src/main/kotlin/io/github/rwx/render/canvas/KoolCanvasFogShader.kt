package io.github.rwx.render.canvas

import de.fabmax.kool.KoolSystem
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.modules.ksl.blocks.mvpMatrix
import de.fabmax.kool.modules.ksl.lang.*
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.scene.*
import de.fabmax.kool.util.*

internal object KoolCanvasFogInstanceLayout : Struct("CanvasFogInstance", MemoryLayout.TightlyPacked) {
    val rect = float4("instattr_rect")
    val uv = float4("instattr_uv")
    val mask = float4("instattr_mask")
}

internal class KoolCanvasFogShader : KslShader(Model(), PipelineConfig(
    blendMode = BlendMode.BLEND_MULTIPLY_ALPHA, cullMethod = CullMethod.NO_CULLING,
    depthTest = DepthCompareOp.ALWAYS, isWriteDepth = false,
)) {
    var colorMap: Texture2d? by texture2d("uFogMap", white)
    private class Model : KslProgram("Canvas Ordered Fog Shader") {
        init {
            val texCoords = interStageFloat2()
            val mask = interStageFloat2()
            vertexStage {
                main {
                    val corner = vertexAttrib(VertexLayouts.Position.position)
                    val rect = instanceAttrib(KoolCanvasFogInstanceLayout.rect)
                    val uv = instanceAttrib(KoolCanvasFogInstanceLayout.uv)
                    val props = instanceAttrib(KoolCanvasFogInstanceLayout.mask)
                    texCoords.input set float2Value(uv.x + (uv.z - uv.x) * corner.x,
                        uv.y + (uv.w - uv.y) * corner.y)
                    mask.input set props.xy
                    outPosition set mvpMatrix().matrix * float4Value(
                        rect.x + (rect.z - rect.x) * corner.x,
                        rect.y + (rect.w - rect.y) * corner.y, 0f.const, 1f.const)
                }
            }
            fragmentStage {
                main {
                    val alpha = sampleTexture(texture2d("uFogMap"), texCoords.output).a
                    colorOutput(float3Value(0f.const, 0f.const, 0f.const),
                        mask.output.x * (1f.const + (alpha - 1f.const) * mask.output.y))
                }
            }
        }
    }
    companion object {
        private val white = SingleColorTexture(Color.WHITE).also { texture ->
            KoolSystem.getContextOrNull()?.onShutdown?.plusAssign { texture.release() }
        }
    }
}
