package com.corrodinggames.rts.gameFramework.graphics

import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.render.canvas.KoolCanvasBlendMode
import io.github.rwx.render.canvas.KoolPaint
import java.io.File
import java.io.InputStream
import java.util.concurrent.locks.Lock

enum class RenderTargetMode {
    DEFAULT,
    IMMEDIATE,

    /**
     * Offscreen target whose contents are only ever sampled by later draws and never read back as
     * CPU pixels. A backend with real GPU render targets implements it as an offscreen pass
     * (render once, sample many times); every other backend treats it exactly like [IMMEDIATE].
     *
     * This is the display-only opt-in for the map layer buffer cells: the original engine keeps its
     * cell cache semantics, but the cell is rasterised by the GPU instead of the CPU.
     */
    GPU_TARGET,
    ;

    /** True when the target may legally be rasterised into CPU-readable pixels. */
    val requiresCpuPixels: Boolean get() = this == IMMEDIATE
}

data class GraphicsBackendCapabilities(
    val fixedLayerBufferPixelSize: Int = 0,
    val extraLayerBufferCells: Int = 0,
    val layerBufferScrollPreloadWorldMargin: Int = 0,
    val clearLayerBuffersBeforeCopy: Boolean = false,
    val supportsLayerBufferPreRendering: Boolean = true,
    val supportsSmoothFogLayerBuffers: Boolean = true,
    val requiresFogAtlasLock: Boolean = false,
    val requiresImageTintColorFilter: Boolean = false,
    /** The backend can render a [RenderTargetMode.GPU_TARGET] into a sampleable GPU image. */
    val supportsGpuRenderTargets: Boolean = false,
)

fun interface DrawTimeOperation {
    fun draw(graphicsEngine: GraphicsEngine)
}

interface GraphicsEngine {
    /** Recording backends may copy display geometry here; legacy backends keep their original path. */
    fun captureHudLayout(engine: GameEngine) {}
    companion object {
        const val DRAW_ROLE_GENERIC: Int = 0
        const val DRAW_ROLE_SELECTION_RING: Int = 1
        const val DRAW_ROLE_WAYPOINT: Int = 2
        const val DRAW_ROLE_UNIT_SHADOW: Int = 3
        const val DRAW_ROLE_PERFORMANCE_HUD: Int = 4
        const val DRAW_ROLE_MAP_FOG: Int = 5
    }

    /** Optional display-only semantic scopes; legacy backends keep their original drawing. */
    fun beginDrawRole(role: Int, unitId: Long) {}
    fun endDrawRole() {}

    fun backendCapabilities(): GraphicsBackendCapabilities = GraphicsBackendCapabilities()

    /** Layer buffers may render into their owned cell target instead of a shared scratch target. */
    fun prefersDirectLayerBufferRendering(): Boolean = false

    /** Legacy backends may rely on eager collection when replacing a loaded level's resources. */
    fun requestsEagerGcOnLevelReload(): Boolean = true

    fun supportsShaderEffects(): Boolean = false

    fun supportsPostProcessing(): Boolean = supportsShaderEffects()

    fun supportsTeamShaders(): Boolean = supportsShaderEffects()

    /**
     * Draws [source] tiled [repeat] times side by side across the horizontal span of [destination].
     *
     * Terrain tiles are issued one draw each and adjacent tiles routinely share a tile image: a horizontal
     * run averages 9.40 tiles on a low-zoom run, so tiling one source rect across the run removes about
     * 38.6% of the terrain commands. The default body keeps every other backend correct by falling back to
     * one draw per tile.
     */
    fun a(texture: Texture?, source: Rect?, destination: RectF?, paint: KoolPaint?, repeat: Int) {
        if (texture == null || source == null || destination == null || repeat <= 1) {
            a(texture, source, destination, paint)
            return
        }
        val tileWidth = (destination.c - destination.a) / repeat
        for (tile in 0 until repeat) {
            val left = destination.a + tileWidth * tile
            val right = if (tile == repeat - 1) destination.c else destination.a + tileWidth * (tile + 1)
            a(texture, source, RectF(left, destination.b, right, destination.d), paint)
        }
    }

    fun b(texture: Texture?): GraphicsEngine

    fun b(texture: Texture?, mode: RenderTargetMode): GraphicsEngine = b(texture)

    fun a(lock: Lock?)

    fun b(lock: Lock?)

    fun a(operation: DrawTimeOperation?) {
        operation?.draw(this)
    }

    fun a(i: Int): Texture

    fun a(i: Int, z: Boolean): Texture

    fun a(inputStream: InputStream?, z: Boolean): Texture

    fun a(i: Int, i2: Int, z: Boolean): Texture

    fun b(i: Int, i2: Int, z: Boolean): Texture

    fun a(texture: Texture?, f: Float, f2: Float, f3: Float, paint: KoolPaint?)

    fun a(texture: Texture?, rect: Rect?, f: Float, f2: Float, f3: Float, paint: KoolPaint?)

    fun a(texture: Texture?, rect: Rect?, rect2: Rect?, paint: KoolPaint?)

    fun a(texture: Texture?, rect: Rect?, rectF: RectF?, paint: KoolPaint?)

    fun a(texture: Texture?, f: Float, f2: Float, paint: KoolPaint?)

    fun a(texture: Texture?, f: Float, f2: Float, paint: KoolPaint?, f3: Float, f4: Float)

    fun b(texture: Texture?, f: Float, f2: Float, paint: KoolPaint?)

    fun b(texture: Texture?, rect: Rect?, rect2: Rect?, paint: KoolPaint?)

    fun a(rect: Rect?, paint: KoolPaint?)

    fun a(texture: Texture?, rect: Rect?, paint: KoolPaint?)

    fun a(texture: Texture?, rect: Rect?, paint: KoolPaint?, i: Int, i2: Int, i3: Int, i4: Int)

    fun a(texture: Texture?, rectF: RectF?, paint: KoolPaint?, f: Float, f2: Float, i: Int, i2: Int)

    fun b(i: Int)

    fun a(i: Int, mode: KoolCanvasBlendMode?)

    fun a(str: String?, f: Float, f2: Float, paint: KoolPaint?, paint2: KoolPaint?, f3: Float)

    fun a(str: String?, f: Float, f2: Float, paint: KoolPaint?)

    fun b(rect: Rect?, paint: KoolPaint?)

    fun a(z: Boolean)

    fun f()

    fun a(rectF: RectF?, paint: KoolPaint?)

    fun c(rect: Rect?, paint: KoolPaint?)

    fun a(rect: Rect?)

    fun a(rectF: RectF?)

    fun a(f: Float, f2: Float, f3: Float, paint: KoolPaint?)

    fun b(f: Float, f2: Float, f3: Float, paint: KoolPaint?)

    fun a(fArr: FloatArray?, i: Int, i2: Int, paint: KoolPaint?)

    fun i()

    fun j()

    fun k()

    fun l()

    fun a(f: Float, f2: Float, f3: Float)

    fun a(f: Float, f2: Float)

    fun a(f: Float, f2: Float, f3: Float, f4: Float)

    fun b(f: Float, f2: Float)

    fun a(f: Float, f2: Float, f3: Float, f4: Float, paint: KoolPaint?)

    fun m(): Int

    fun n(): Int

    fun a(i: Int, i2: Int)

    fun o()

    fun p()

    fun q()

    fun a(shaderProgram: ShaderProgram?)

    fun a(str: String?, paint: KoolPaint?): Int

    fun b(str: String?, paint: KoolPaint?): Int

    fun r(): Texture

    fun a(texture: Texture?, file: File?)
}
