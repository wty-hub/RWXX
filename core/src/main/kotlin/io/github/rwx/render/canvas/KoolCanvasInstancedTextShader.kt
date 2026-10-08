package io.github.rwx.render.canvas

// MSDF fragment evaluation follows the bundled Kool 0.19.0 MsdfUiShader (MIT).

import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.modules.ksl.blocks.mvpMatrix
import de.fabmax.kool.modules.ksl.lang.*
import de.fabmax.kool.pipeline.BlendMode
import de.fabmax.kool.pipeline.CullMethod
import de.fabmax.kool.pipeline.PipelineConfig
import de.fabmax.kool.scene.vertexAttrib
import de.fabmax.kool.scene.instanceAttrib
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.util.MemoryLayout
import de.fabmax.kool.util.Struct
import de.fabmax.kool.math.Vec2f

internal class KoolCanvasInstancedTextShader(
    model: Model = Model(),
    pipelineCfg: PipelineConfig = PipelineConfig(
        cullMethod = CullMethod.NO_CULLING,
        blendMode = BlendMode.BLEND_PREMULTIPLIED_ALPHA
    )
) : KslShader(model, pipelineCfg) {
    var fontMap by texture2d("tFontMap")
    var pxRangeScale by uniform1f("uPxRange", 1f)
    var labelGeometry by texture2d("tLabelGeometry")
    var labelDimensions by uniform2f("uLabelDimensions", Vec2f(256f, 1f))

    class Model(private val labels: Boolean = false) : KslProgram(if (labels) "Msdf UI2 Labels" else "Msdf UI2 Glyphs") {
        init {
            val fgColor = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            val glowColor = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            val msdfProps = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            val clipBounds = interStageFloat4(interpolation = KslInterStageInterpolation.Flat)
            val screenPos = interStageFloat2()
            val uv = interStageFloat2()

            vertexStage {
                main {
                    val corner = vertexAttrib(VertexLayouts.Position.position)
                    val origin = instanceAttrib(KoolCanvasGlyphInstanceLayout.originAxisX)
                    val axis = instanceAttrib(KoolCanvasGlyphInstanceLayout.axisY)
                    val coords = instanceAttrib(KoolCanvasGlyphInstanceLayout.uv)
                    val position = float2Var()
                    fgColor.input set instanceAttrib(KoolCanvasGlyphInstanceLayout.tint)
                    glowColor.input set instanceAttrib(KoolCanvasGlyphInstanceLayout.glow)
                    msdfProps.input set instanceAttrib(KoolCanvasGlyphInstanceLayout.props)
                    clipBounds.input set instanceAttrib(KoolCanvasGlyphInstanceLayout.clip)
                    if (labels) {
                        val dimensions = uniformFloat2("uLabelDimensions")
                        val index = float1Var(coords.z + corner.x)
                        val row = float1Var(floor(index / dimensions.x))
                        val geometry = float4Var(sampleTexture(texture2d("tLabelGeometry"),
                            float2Value((index - row * dimensions.x + .5f.const) / dimensions.x,
                                (row + .5f.const) / dimensions.y), 0f.const))
                        val x = float1Var(geometry.x + origin.w)
                        val y = float1Var(geometry.y + axis.w)
                        position set float2Value(origin.x * x + origin.y * y + origin.z - coords.x,
                            coords.y - (axis.x * x + axis.y * y + axis.z))
                        `if` (corner.x ge coords.w) { position set float2Value((-1e6f).const, (-1e6f).const) }
                        uv.input set geometry.zw
                    } else {
                        position set origin.xy + origin.zw * corner.x + axis.xy * corner.y
                        uv.input set float2Value(coords.x + (coords.z - coords.x) * corner.x,
                            coords.y + (coords.w - coords.y) * corner.y)
                    }
                    screenPos.input set position
                    outPosition set mvpMatrix().matrix * float4Value(position, 0f.const, 1f.const)
                }
            }

            fragmentStage {
                val median3 = functionFloat1("median") {
                    val p = paramFloat3("p")
                    body {
                        max(min(p.x, p.y), min(max(p.x, p.y), p.z))
                    }
                }

                val computeOpacity = functionFloat1("computeOpacity") {
                    val msdf = paramFloat3("msdf")
                    val props = paramFloat4("props")

                    body {
                        val sd = float1Var(median3(msdf))
                        val dist = float1Var(sd - 0.5f.const + props.y)

                        // branch-less version of "if (dist > cutoff) dist = 2.0 * cutoff - dist"
                        val p = step(props.z, dist)
                        dist set dist + p * 2f.const * (props.z - dist)

                        val screenPxDistance = float1Var(props.x * dist)
                        clamp(screenPxDistance + 0.5f.const, 0f.const, 1f.const)
                    }
                }

                main {
                    val fontMap = texture2d("tFontMap")

                    `if` (any(screenPos.output lt clipBounds.output.xy) or
                            any(screenPos.output gt clipBounds.output.zw)) {
                        discard()

                    }.`else` {
                        val msdfVals = float4Var(sampleTexture(fontMap, uv.output, 0f.const))
                        val color = float4Var(fgColor.output)
                        val pxRange = float1Var(msdfProps.output.x * uniformFloat1("uPxRange"))
                        val weight = msdfProps.output.y

                        // sample regular sdf map (stored in texture alpha channel)
                        val dist = float1Var(msdfVals.a - 0.5f.const + weight)
                        val screenPxDistance = float1Var(pxRange * dist)
                        val sdfOpa = float1Var(clamp(screenPxDistance + 0.5f.const, 0f.const, 1f.const))

                        // sample multi-sdf map (stored in texture rgb channels)
                        val msdfOpa = float1Var(computeOpacity(msdfVals.rgb, msdfProps.output))

                        // use sdf for small fonts and msdf for large fonts
                        val wMsdf = float1Var(smoothStep(5f.const, 10f.const, pxRange))
                        color.a *= sdfOpa * (1f.const - wMsdf) + msdfOpa * wMsdf

                        val glow = float4Var(glowColor.output)
                        glow.a *= smoothStep(0f.const, 1f.const, msdfVals.a * 1.5f.const) * (1f.const - color.a)

                        colorOutput(color.rgb * color.a + glow.rgb * glow.a, color.a + glow.a)
                    }
                }
            }
        }
    }
}

internal object KoolCanvasGlyphInstanceLayout : Struct("CanvasGlyphInstance", MemoryLayout.TightlyPacked) {
    val originAxisX = float4("glyphOriginAxisX")
    val axisY = float4("glyphAxisY")
    val uv = float4("glyphUv")
    val tint = float4("glyphTint")
    val glow = float4("glyphGlow")
    val props = float4("glyphProps")
    val clip = float4("glyphClip")
}
