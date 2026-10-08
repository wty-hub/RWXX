package io.github.rwx.render.canvas

/** Explicit display-only semantics. Unknown and mod drawing stays [Generic]. */
enum class KoolCanvasDrawRole { Generic, SelectionRing, Waypoint, UnitShadow, PerformanceHud, MapFog }

data class KoolCanvasState(
    val transform: KoolCanvasTransform = KoolCanvasTransform.Identity,
    val clip: KoolCanvasRect? = null,
    val renderTarget: KoolCanvasRenderTargetId? = null,
    val drawRole: KoolCanvasDrawRole = KoolCanvasDrawRole.Generic,
    val semanticUnitId: Long = -1L,
) {
    companion object {
        val Default: KoolCanvasState = KoolCanvasState()
    }
}

sealed interface KoolCanvasCommand {
    data class DrawFogBatch(
        val texture: KoolCanvasTextureRef?,
        val masks: KoolCanvasFogBatch,
        val filter: KoolCanvasTextureFilter,
        val state: KoolCanvasState,
        val paint: KoolCanvasPaint = KoolCanvasPaint.Default,
    ) : KoolCanvasCommand
    data class DrawRectBatch(
        val rects: KoolCanvasRectBatch,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand
    /** Ordered independent quads with one material; geometry is immutable across frame publication. */
    data class DrawTextureBatch(
        val texture: KoolCanvasTextureRef,
        val quads: KoolCanvasTextureBatch,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand

    data class Clear(
        val color: KoolCanvasColor,
        val blendMode: KoolCanvasBlendMode = KoolCanvasBlendMode.SourceOver,
        val renderTarget: KoolCanvasRenderTargetId? = null,
    ) : KoolCanvasCommand

    data class DrawTexture(
        val texture: KoolCanvasTextureRef,
        val source: KoolCanvasRect,
        val destination: KoolCanvasRect,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand {
        val sourceIsFullTexture: Boolean =
            source.left == 0f &&
                    source.top == 0f &&
                    source.right == texture.widthFloat &&
                    source.bottom == texture.heightFloat
        val sourceU0: Float = if (sourceIsFullTexture) 0f else source.left * texture.inverseSafeWidth
        val sourceV0: Float = if (sourceIsFullTexture) 0f else source.top * texture.inverseSafeHeight
        val sourceU1: Float = if (sourceIsFullTexture) 1f else source.right * texture.inverseSafeWidth
        val sourceV1: Float = if (sourceIsFullTexture) 1f else source.bottom * texture.inverseSafeHeight
    }

    /**
     * One texture source rect drawn [repeat] times across [destination], side by side horizontally.
     *
     * Terrain is issued one draw per tile, and adjacent tiles routinely share a tile image: measured on a
     * low-zoom run, a horizontal run averages 9.40 tiles (max 74) and folding runs into repeats removes
     * about 38.6% of the terrain commands. A repeat cannot be expressed by stretching one source rect -
     * that samples whatever sits beside it in the atlas page and tears the map - so the tiling has to be a
     * property of the command, expanded into [repeat] quads when the renderer builds geometry.
     */
    data class DrawTextureRepeat(
        val texture: KoolCanvasTextureRef,
        val source: KoolCanvasRect,
        val destination: KoolCanvasRect,
        val repeat: Int,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand {
        init {
            require(repeat >= 1) { "Texture repeat count must be at least 1" }
        }
    }

    data class DrawRect(
        val rect: KoolCanvasRect,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand

    data class DrawLine(
        val start: KoolCanvasPoint,
        val end: KoolCanvasPoint,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand

    data class DrawCircle(
        val center: KoolCanvasPoint,
        val radius: Float,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand {
        init {
            require(radius >= 0f) { "Circle radius must be non-negative" }
        }
    }

    data class DrawText(
        val text: String,
        val baseline: KoolCanvasPoint,
        val paint: KoolCanvasPaint,
        val state: KoolCanvasState,
    ) : KoolCanvasCommand
}

data class KoolCanvasFrame(
    val viewport: KoolCanvasViewport,
    val commands: List<KoolCanvasCommand>,
    val visualStats: KoolCanvasVisualStats? = null,
)

data class KoolCanvasVisualStats(
    val selectedUnits: Int,
    val visibleUnits: Int,
    val adaptiveBattleVisuals: Boolean = false,
)

fun interface KoolCanvasRenderer {
    fun render(frame: KoolCanvasFrame)
}
