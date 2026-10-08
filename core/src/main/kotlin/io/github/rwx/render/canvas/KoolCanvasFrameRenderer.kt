package io.github.rwx.render.canvas

import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.Vec2f
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.Vec4f
import de.fabmax.kool.modules.ksl.KslUnlitShader
import de.fabmax.kool.modules.ui2.MsdfUiShader
import de.fabmax.kool.modules.ui2.UiTextVertexLayout
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.scene.*
import de.fabmax.kool.scene.geometry.IndexedVertexList
import de.fabmax.kool.scene.geometry.MeshBuilder
import de.fabmax.kool.scene.geometry.TextProps
import de.fabmax.kool.scene.geometry.Usage
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.MsdfFontData
import de.fabmax.kool.util.TextMetrics
import kotlin.math.*
import de.fabmax.kool.pipeline.BlendMode as KoolBlendMode


class KoolCanvasFrameRenderer(
    private val textureStore: KoolCanvasTextureStore = KoolCanvasTextureRegistry,
    private val renderingFrameTextureIds: MutableSet<KoolCanvasTextureId> = mutableSetOf(),
    private val performanceRates: () -> CanvasFrameRateSample? = CanvasFrameMetrics::snapshot,
    private val reuseTextMeshes: Boolean = System.getenv("RWX_DISABLE_TEXT_MESH_REUSE") != "1",
    private val reuseTextMeshKeys: Boolean = System.getenv("RWX_REUSE_TEXT_MESH_KEYS") == "1",
    private val prepareText: Boolean = System.getenv("RWX_PREPARE_CANVAS_TEXT") == "1",
    private val primitiveTextMetrics: Boolean = System.getenv("RWX_PRIMITIVE_TEXT_METRICS") == "1",
    private val reuseTextGeometry: Boolean = System.getenv("RWX_TEXT_GEOMETRY_TEMPLATES") == "1",
    private val instanceTextGlyphs: Boolean = System.getenv("RWX_INSTANCED_TEXT_GLYPHS") == "1",
    private val reuseTextShaders: Boolean = System.getenv("RWX_TEXT_SHADER_REUSE") == "1",
    private val instanceTextLabels: Boolean = System.getenv("RWX_INSTANCED_TEXT_LABELS") == "1",
    private val projectedSpriteAtlas: Boolean = System.getenv("RWX_PROJECTED_SPRITE_ATLAS") == "1",
    /**
     * How many render calls a mesh stays cached after its last use.
     *
     * The root canvas alternates content between frames, so it needs a grace period. An offscreen GPU
     * target replays a complete frame per render call and needs none: with the default grace period, a
     * measured fog-enabled run retained 17,287 offscreen meshes whose geometry buffers held ~2 GB of JVM
     * direct memory and aborted with `OutOfMemoryError: Cannot reserve ... direct buffer memory`.
     */
    private val meshRetentionRenders: Long = 60,
    /**
     * Drops the recording owner from the frozen mesh identity.
     *
     * An offscreen GPU target renders one cell per render call in its own renderer, and the slot carrying
     * that renderer is handed to different cells over time. Keeping the owner in the identity therefore
     * recreated a whole mesh set per (slot, cell) pair 閳?a measured fog-enabled run accumulated ~17,000
     * cached meshes holding ~2 GB of JVM direct memory. Distinct versions within one render are still
     * separated by the identity's per-render slot, so dropping the owner cannot merge two bindings.
     */
    private val meshIdentityIgnoresRecordingOwner: Boolean = false,
    private val reuseInstancedTextureShaders: Boolean = REUSE_INSTANCED_TEXTURE_SHADERS,
    /** An independent pixel oracle can bypass atlas preparation and sample source textures. */
    private val useSpriteAtlas: Boolean = true,
    /** Only GPU-target renderers can recycle compatible empty meshes between fence-protected slots. */
    private val reuseMapMeshSlots: Boolean = System.getenv("RWX_REUSE_MAP_MESH_SLOTS") == "1",
) {
    private val renderFonts = if (prepareText) KoolCanvasRenderFonts() else null
    private val textMetrics = if (primitiveTextMetrics) KoolPrimitiveTextMetrics() else null
    private val textTemplates = if (reuseTextGeometry || instanceTextGlyphs || instanceTextLabels) KoolCanvasTextTemplates(reuseLookups = prepareText) else null
    private val labelGeometry = if (instanceTextLabels) KoolCanvasLabelGeometry() else null
    private var attachedScene: Node? = null
    private val primitiveMeshes = linkedMapOf<PrimitiveMeshKey, Mesh<VertexLayouts.PositionNormalColor>>()
    private val usedPrimitiveMeshKeys = mutableSetOf<PrimitiveMeshKey>()
    private val textureMeshes = linkedMapOf<TextureMeshKey, TextureMeshEntry>()
    private val frameTextureMeshIdentities = mutableMapOf<KoolCanvasTextureId, TextureMeshIdentity>()
    private val frameFrozenPixelVersionCounts = mutableMapOf<KoolCanvasFrozenPixelIdentity, Int>()
    private val reuseFrozenPixelMeshes = System.getenv("RWX_DISABLE_FROZEN_PIXEL_MESH_REUSE") != "1"
    private val usedTextureMeshKeys = mutableSetOf<TextureMeshKey>()
    private val instancedTextureMeshes = linkedMapOf<TextureMeshKey, InstancedTextureMeshEntry>()
    private val instancedTextureShaders = mutableMapOf<KoolCanvasInstancedTextureMaterials.Key, KoolCanvasInstancedTextureShader>()
    private var sharedInstancedTextureMaterials: KoolCanvasInstancedTextureMaterials? = null
    private var mapMeshSlotReused = 0L
    internal fun reusedMapMeshSlotCount(): Long = mapMeshSlotReused
    internal fun shareInstancedTextureMaterials(materials: KoolCanvasInstancedTextureMaterials) {
        check(reuseInstancedTextureShaders && attachedScene == null && instancedTextureShaders.isEmpty())
        sharedInstancedTextureMaterials = materials
    }
    private fun clearInstancedTextureMaterials() {
        sharedInstancedTextureMaterials?.let { pool -> instancedTextureShaders.keys.forEach(pool::release) }
        instancedTextureShaders.clear()
    }
    private val usedInstancedTextureMeshKeys = mutableSetOf<TextureMeshKey>()
    private val affineInstancedTextureMeshes = linkedMapOf<TextureMeshKey, AffineInstancedTextureMeshEntry>()
    private val usedAffineInstancedTextureMeshKeys = mutableSetOf<TextureMeshKey>()
    private val teamColorTextureMeshes = linkedMapOf<TeamColorTextureMeshKey, TeamColorTextureMeshEntry>()
    private val usedTeamColorTextureMeshKeys = mutableSetOf<TeamColorTextureMeshKey>()
    private val displacementTextureMeshes = linkedMapOf<DisplacementTextureMeshKey, DisplacementTextureMeshEntry>()
    private val usedDisplacementTextureMeshKeys = mutableSetOf<DisplacementTextureMeshKey>()
    private var textMeshProbe: TextMeshKey? = null
    private var textKeyReuseConfirmed = false
    private val textMeshes = linkedMapOf<TextMeshKey, TextMeshEntry>()
    private val usedTextMeshKeys = mutableSetOf<TextMeshKey>()
    private data class TextMaterialKey(val fontData: MsdfFontData, val blend: CanvasRenderBlend, val instanced: Boolean,
        val labels: Boolean = false)
    private val textShaders = mutableMapOf<TextMaterialKey, DrawShader>()
    private val textMeshMetricsEnabled = System.getenv("RWX_FRAME_METRICS") != null
    private var textMeshCreated = 0L
    private var textMeshExactHits = 0L
    private var textMeshReused = 0L
    private var textMeshPruned = 0L
    private var textMeshScanCandidates = 0L
    private var textMeshPeak = 0

    internal data class TextMeshCacheSnapshot(
        val created: Long, val exactHits: Long, val reused: Long, val pruned: Long,
        val scanCandidates: Long, val live: Int, val peak: Int,
    )

    internal fun textMeshCacheSnapshot() = TextMeshCacheSnapshot(textMeshCreated, textMeshExactHits,
        textMeshReused, textMeshPruned, textMeshScanCandidates, textMeshes.size, textMeshPeak)

    init {
        if (textMeshMetricsEnabled) println("RWXTextMeshReuse enabled=$reuseTextMeshes")
        println("RWXPrimitiveTextMetrics enabled=$primitiveTextMetrics")
    }
    private val performanceHudEntries = mutableListOf<Pair<KoolCanvasCommand.DrawText, TextMeshEntry>>()
    private var performanceHudSample: CanvasFrameRateSample? = null
    private var activeBatchIndex = -1
    private var activeBatchKey: CanvasBatchKey? = null
    private var activeOrderingSegment = 0
    private var pendingOrderingSegmentAdvance = false
    private var projectedOrderingSegmentAdvances = 0
    private var frameProjectionDepth = 0
    private var projectedFrameTextureExpansions = 0
    private var nextFrameProjectionBatchId = 0
    private var activeFrameProjectionBatchId: Int? = null
    private var lastPrimitiveMeshKey: PrimitiveMeshKey? = null
    private var lastPrimitiveMesh: Mesh<VertexLayouts.PositionNormalColor>? = null
    private var lastTextureMeshKey: TextureMeshKey? = null
    private var lastTextureMeshEntry: TextureMeshEntry? = null
    private var lastInstancedTextureMeshKey: TextureMeshKey? = null
    private var lastInstancedTextureMeshEntry: InstancedTextureMeshEntry? = null
    private var alternateInstancedTextureMeshKey: TextureMeshKey? = null
    private var alternateInstancedTextureMeshEntry: InstancedTextureMeshEntry? = null
    private var lastAffineInstancedTextureMeshKey: TextureMeshKey? = null
    private var lastAffineInstancedTextureMeshEntry: AffineInstancedTextureMeshEntry? = null
    private var alternateAffineInstancedTextureMeshKey: TextureMeshKey? = null
    private var alternateAffineInstancedTextureMeshEntry: AffineInstancedTextureMeshEntry? = null
    private var lastResolvedTextureRef: KoolCanvasTextureRef? = null
    private var lastResolvedTextureFilter: KoolCanvasTextureFilter? = null
    private var lastResolvedTexture: Texture2d? = null
    private var alternateResolvedTextureRef: KoolCanvasTextureRef? = null
    private var alternateResolvedTextureFilter: KoolCanvasTextureFilter? = null
    private var alternateResolvedTexture: Texture2d? = null
    private val textureRevisionStore = textureStore as? KoolCanvasTextureRevisionStore
    private var currentTextureRevision = Int.MIN_VALUE
    private val resolvedTextureCacheRefs = arrayOfNulls<KoolCanvasTextureRef>(RESOLVED_TEXTURE_CACHE_SIZE)
    private val resolvedTextureCacheFilters = arrayOfNulls<KoolCanvasTextureFilter>(RESOLVED_TEXTURE_CACHE_SIZE)
    private val resolvedTextureCacheTextures = arrayOfNulls<Texture2d>(RESOLVED_TEXTURE_CACHE_SIZE)
    private var nextResolvedTextureCacheSlot = 0
    private var resolvedTextureCacheCount = 0
    private var resolvedTextureCacheRevision = Int.MIN_VALUE
    private val frameTextureRevisionStore = textureStore as? KoolCanvasFrameTextureRevisionStore
    private var currentFrameTextureRevision = Int.MIN_VALUE
    private val frameTextureCacheIds = arrayOfNulls<KoolCanvasTextureId>(FRAME_TEXTURE_CACHE_SIZE)
    private val frameTextureCacheFrames = arrayOfNulls<KoolCanvasFrame>(FRAME_TEXTURE_CACHE_SIZE)
    private var nextFrameTextureCacheSlot = 0
    private val missingFrameTextureCacheIds = arrayOfNulls<KoolCanvasTextureId>(FRAME_TEXTURE_MISS_CACHE_SIZE)
    private var nextMissingFrameTextureCacheSlot = 0
    private var missingFrameTextureCacheCount = 0
    private var missingFrameTextureCacheRevision = Int.MIN_VALUE
    private var lastMissingFrameTextureId: KoolCanvasTextureId? = null
    private var alternateMissingFrameTextureId: KoolCanvasTextureId? = null
    private var lastFrameTextureId: KoolCanvasTextureId? = null
    private var lastFrameTexture: KoolCanvasFrame? = null
    private var lastUsedPrimitiveMeshKey: PrimitiveMeshKey? = null
    private var lastUsedTextureMeshKey: TextureMeshKey? = null
    private var lastUsedInstancedTextureMeshKey: TextureMeshKey? = null
    private var lastUsedAffineInstancedTextureMeshKey: TextureMeshKey? = null
    private var lastUsedTeamColorTextureMeshKey: TeamColorTextureMeshKey? = null
    private var lastUsedDisplacementTextureMeshKey: DisplacementTextureMeshKey? = null
    private var lastUsedTextMeshKey: TextMeshKey? = null
    private val renderColorCachePaints = arrayOfNulls<KoolCanvasPaint>(RENDER_COLOR_CACHE_SIZE)
    private val renderColorCacheColors = arrayOfNulls<Color>(RENDER_COLOR_CACHE_SIZE)
    private var nextRenderColorCacheSlot = 0
    private var lastRenderColorPaint: KoolCanvasPaint? = null
    private var lastRenderColor: Color? = null
    private var currentViewportHalfWidth = 0f
    private var currentViewportHalfHeight = 0f
    private var meshPruneRequired = false
    /** Reused across every glyph vertex; the text customizer runs once per vertex of every label. */
    private val textVertexScratch = MutableVec3f()
    private val legacyTextVertexAllocation: Boolean =
        System.getenv("RWX_LEGACY_TEXT_VERTEX_ALLOCATION") == "1"
    private val textureRunTextureIds = arrayOfNulls<KoolCanvasTextureId>(TEXTURE_RUN_CACHE_SIZE)
    private val textureRunFilters = arrayOfNulls<KoolCanvasTextureFilter>(TEXTURE_RUN_CACHE_SIZE)
    private val textureRunBlends = arrayOfNulls<CanvasRenderBlend>(TEXTURE_RUN_CACHE_SIZE)
    private val textureRunEntries = arrayOfNulls<InstancedTextureMeshEntry>(TEXTURE_RUN_CACHE_SIZE)
    private val primitiveShaders = mutableMapOf<CanvasRenderBlend, KslUnlitShader>()
    private val spriteAtlas = KoolCanvasSpriteAtlas(textureStore,
        prepareNestedFrames = projectedSpriteAtlas || System.getenv("RWX_INCREMENTAL_SPRITE_ATLAS") == "1")
    private val spriteMeshes = linkedMapOf<SpriteMeshKey, SpriteMeshEntry>()
    private val spriteShaders = mutableMapOf<SpriteMaterialKey, KoolCanvasSpriteShader>()
    private val usedSpriteMeshKeys = mutableSetOf<SpriteMeshKey>()
    private var lastSpriteMeshKey: SpriteMeshKey? = null
    private var lastSpriteMeshEntry: SpriteMeshEntry? = null
    private val meshLastUsed = java.util.IdentityHashMap<Mesh<*>, Long>()
    private var renderSequence = 0L
    private val adaptiveVisuals = KoolCanvasAdaptiveVisuals()
    private val ringMeshes = linkedMapOf<Int, RingMeshEntry>()
    private val usedRingMeshKeys = mutableSetOf<Int>()
    private data class FogMeshKey(val texture: KoolCanvasTextureId?, val filter: KoolCanvasTextureFilter, val ordinal: Int)
    private data class FogMeshEntry(val mesh: Mesh<VertexLayouts.Position>,
        val instances: MeshInstanceList<KoolCanvasFogInstanceLayout>, val shader: KoolCanvasFogShader)
    private val fogMeshes = linkedMapOf<FogMeshKey, FogMeshEntry>()
    private val usedFogMeshKeys = mutableSetOf<FogMeshKey>()
    private var ringShader: KoolCanvasRingShader? = null
    private data class RingMeshEntry(val mesh: Mesh<VertexLayouts.Position>, val instances: MeshInstanceList<KoolCanvasRingInstanceLayout>)

    private data class SpriteMaterialKey(val pageSerial: Int, val filter: KoolCanvasTextureFilter, val blend: CanvasRenderBlend)
    private data class SpriteMeshKey(val batchIndex: Int, val material: SpriteMaterialKey)
    private data class SpriteMeshEntry(
        val mesh: Mesh<VertexLayouts.Position>,
        val instances: MeshInstanceList<KoolCanvasSpriteInstanceLayout>,
        val shader: KoolCanvasSpriteShader,
    )

    private fun createPrimitiveShader(renderBlend: CanvasRenderBlend): KslUnlitShader = primitiveShaders.getOrPut(renderBlend) { KslUnlitShader {
        color { vertexColor() }
        pipeline {
            blendMode = renderBlend.primitiveBlendMode
            cullMethod = CullMethod.NO_CULLING
            depthTest = DepthCompareOp.ALWAYS
            isWriteDepth = false
        }
    } }

    private fun createTextureShader(
        texture: Texture2d,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): KoolCanvasTextureShader =
        KoolCanvasTextureShader(
            PipelineConfig(
                blendMode = renderBlend.textureBlendMode(premultipliedAlpha),
                cullMethod = CullMethod.NO_CULLING,
                depthTest = DepthCompareOp.ALWAYS,
                isWriteDepth = false,
            ),
            premultipliedAlpha = premultipliedAlpha,
            multipliesRgbByAlpha = renderBlend == CanvasRenderBlend.Additive,
        ).apply {
            colorMap = texture
        }

    private fun createInstancedTextureShader(
        texture: Texture2d,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): KoolCanvasInstancedTextureShader {
        val material = if (reuseInstancedTextureShaders) KoolCanvasInstancedTextureMaterials.Key(texture,
            renderBlend.textureBlendMode(premultipliedAlpha), premultipliedAlpha, renderBlend == CanvasRenderBlend.Additive) else null
        material?.let { instancedTextureShaders[it]?.let { shader -> return shader } }
        val create = { KoolCanvasInstancedTextureShader(
            PipelineConfig(
                blendMode = renderBlend.textureBlendMode(premultipliedAlpha),
                cullMethod = CullMethod.NO_CULLING,
                depthTest = DepthCompareOp.ALWAYS,
                isWriteDepth = false,
            ),
            premultipliedAlpha = premultipliedAlpha,
            multipliesRgbByAlpha = renderBlend == CanvasRenderBlend.Additive,
        ).apply {
            colorMap = texture
        } }
        val shader = if (material != null && sharedInstancedTextureMaterials != null)
            sharedInstancedTextureMaterials!!.acquire(material, create) else create()
        material?.let { instancedTextureShaders[it] = shader }
        return shader
    }

    private fun createAffineInstancedTextureShader(
        texture: Texture2d,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): KoolCanvasAffineInstancedTextureShader =
        KoolCanvasAffineInstancedTextureShader(
            PipelineConfig(
                blendMode = renderBlend.textureBlendMode(premultipliedAlpha),
                cullMethod = CullMethod.NO_CULLING,
                depthTest = DepthCompareOp.ALWAYS,
                isWriteDepth = false,
            ),
            premultipliedAlpha = premultipliedAlpha,
            multipliesRgbByAlpha = renderBlend == CanvasRenderBlend.Additive,
        ).apply {
            colorMap = texture
        }

    private data class TextureMeshEntry(
        var texture: Texture2d,
        val mesh: Mesh<VertexLayouts.PositionNormalTexCoordColor>,
        val shader: KoolCanvasTextureShader,
    )

    private data class InstancedTextureMeshEntry(
        var texture: Texture2d,
        val mesh: Mesh<VertexLayouts.Position>,
        var shader: KoolCanvasInstancedTextureShader,
        var batchKey: CanvasBatchKey.Texture,
        val instances: MeshInstanceList<KoolCanvasTextureInstanceLayout>,
    )

    private data class AffineInstancedTextureMeshEntry(
        var texture: Texture2d,
        val mesh: Mesh<VertexLayouts.Position>,
        val shader: KoolCanvasAffineInstancedTextureShader,
        var batchKey: CanvasBatchKey.AffineTexture,
        val instances: MeshInstanceList<KoolCanvasAffineTextureInstanceLayout>,
    )

    private data class TeamColorTextureMeshEntry(
        var texture: Texture2d,
        val mesh: Mesh<VertexLayouts.PositionNormalTexCoordColor>,
        val shader: KoolTeamColorShader,
    )

    private data class DisplacementTextureMeshEntry(
        var displacementMap: Texture2d,
        var screenBase: Texture2d,
        val mesh: Mesh<VertexLayouts.PositionNormalTexCoordColor>,
        val shader: KoolDisplacementShader,
    )

    private data class PrimitiveMeshKey(
        val batchIndex: Int?,
        val orderingSegment: Int?,
        val projectionBatchId: Int?,
        val renderBlend: CanvasRenderBlend,
        val layer: PrimitiveMeshLayer,
    )

    private sealed interface TextureMeshIdentity {
        data class Version(val textureId: KoolCanvasTextureId) : TextureMeshIdentity
        data class FrozenPixels(val identity: KoolCanvasFrozenPixelIdentity, val frameVersionSlot: Int) : TextureMeshIdentity
    }

    private data class TextureMeshKey(
        val batchIndex: Int?,
        val orderingSegment: Int?,
        val projectionBatchId: Int?,
        val textureIdentity: TextureMeshIdentity,
        val textureFilter: KoolCanvasTextureFilter,
        val renderBlend: CanvasRenderBlend,
        val premultipliedAlpha: Boolean,
    )

    private data class TeamColorTextureMeshKey(
        val batchIndex: Int?,
        val orderingSegment: Int?,
        val projectionBatchId: Int?,
        val textureId: KoolCanvasTextureId,
        val textureFilter: KoolCanvasTextureFilter,
        val renderBlend: CanvasRenderBlend,
        val effect: KoolCanvasTextureEffect.TeamColor,
    )

    private data class DisplacementTextureMeshKey(
        val batchIndex: Int?,
        val orderingSegment: Int?,
        val projectionBatchId: Int?,
        val textureId: KoolCanvasTextureId,
        val textureFilter: KoolCanvasTextureFilter,
        val renderBlend: CanvasRenderBlend,
        val effect: KoolCanvasTextureEffect.Displacement,
    )

    private data class TextMeshEntry(
        val fontData: MsdfFontData,
        val renderBlend: CanvasRenderBlend,
        val mesh: Mesh<*>,
        val builder: MeshBuilder<UiTextVertexLayout>?,
        val instances: MeshInstanceList<KoolCanvasGlyphInstanceLayout>? = null,
        var labelVertexCapacity: Int = 0,
        var cacheKey: TextMeshKey? = null,
    )

    private data class TextMeshKey(
        var batchIndex: Int?,
        var orderingSegment: Int?,
        var projectionBatchId: Int?,
        var fontData: MsdfFontData,
        var renderBlend: CanvasRenderBlend,
        var typefaceKey: String?,
        var performanceHud: Boolean,
        var instanced: Boolean,
        var labelVertexCapacity: Int = 0,
    )

    private sealed interface CanvasBatchKey {
        data class Primitive(
            val renderBlend: CanvasRenderBlend,
            val layer: PrimitiveMeshLayer,
        ) : CanvasBatchKey

        data class Texture(
            val textureId: KoolCanvasTextureId,
            val textureFilter: KoolCanvasTextureFilter,
            val renderBlend: CanvasRenderBlend,
            val premultipliedAlpha: Boolean,
        ) : CanvasBatchKey

        data class AffineTexture(
            val textureId: KoolCanvasTextureId,
            val textureFilter: KoolCanvasTextureFilter,
            val renderBlend: CanvasRenderBlend,
            val premultipliedAlpha: Boolean,
        ) : CanvasBatchKey

        data class TeamColorTexture(
            val textureId: KoolCanvasTextureId,
            val textureFilter: KoolCanvasTextureFilter,
            val renderBlend: CanvasRenderBlend,
            val effect: KoolCanvasTextureEffect.TeamColor,
        ) : CanvasBatchKey

        data class DisplacementTexture(
            val textureId: KoolCanvasTextureId,
            val textureFilter: KoolCanvasTextureFilter,
            val renderBlend: CanvasRenderBlend,
            val effect: KoolCanvasTextureEffect.Displacement,
        ) : CanvasBatchKey

        data class Text(
            val fontData: MsdfFontData,
            val renderBlend: CanvasRenderBlend,
            val typefaceKey: String?,
            val performanceHud: Boolean,
            val labelVertexCapacity: Int = 0,
        ) : CanvasBatchKey

        data class Sprite(val material: SpriteMaterialKey) : CanvasBatchKey
        data object Ring : CanvasBatchKey
        data class Fog(val texture: KoolCanvasTextureId?, val filter: KoolCanvasTextureFilter) : CanvasBatchKey
    }

    /**
     * Replays [frame] into the root canvas scene.
     *
     * The scene owns the camera and the clear colour, so this is the only place that has to touch
     * scene-specific state; see [renderInto] for GPU offscreen targets, which are plain nodes.
     */
    fun render(scene: Scene, frame: KoolCanvasFrame) {
        val camera = (scene.camera as? OrthographicCamera)
            ?: OrthographicCamera("rwx-kool-canvas-camera").also { scene.camera = it }
        scene.clearColor = ClearColorFill(KoolCanvasColor.Transparent.toKoolColor())
        renderSurface(scene, camera, frame) { color -> scene.clearColor = color }
    }

    /**
     * Replays [frame] into a plain [node] with an explicit [camera].
     *
     * A GPU offscreen target (see `KoolCanvasGpuTargetPasses`) must not use a nested [Scene] as its
     * draw node: that crashes the Vulkan driver on Windows/Intel in `vkCmdEndRenderingKHR` even when
     * the pass draws nothing at all. [setClearColor] receives the clear colour the frame's leading
     * `Clear` command asks for, which the caller applies to the pass attachment.
     */
    fun renderInto(
        node: Node,
        camera: OrthographicCamera,
        frame: KoolCanvasFrame,
        setClearColor: (ClearColor) -> Unit,
    ) {
        setClearColor(ClearColorFill(KoolCanvasColor.Transparent.toKoolColor()))
        renderSurface(node, camera, frame, setClearColor)
    }

    private fun renderSurface(
        scene: Node,
        camera: OrthographicCamera,
        frame: KoolCanvasFrame,
        setClearColor: (ClearColor) -> Unit,
    ) {
        val prepareStart = CanvasRenderStageTrace.start()
        resetSceneCachesIfNeeded(scene)
        frameTextureMeshIdentities.clear()
        frameFrozenPixelVersionCounts.clear()
        performanceHudEntries.clear()
        performanceHudSample = performanceRates()
        renderSequence++
        labelGeometry?.beginFrame(renderSequence)
        adaptiveVisuals.prepare(frame)
        if (useSpriteAtlas) spriteAtlas.prepare(frame.commands)
        CanvasRenderStageTrace.record("canvas-prepare", prepareStart, frame.commands.size.toLong())
        // Which passes exist. The measured interaction cost is dominated by the count of passes per frame
        // (1.21 at rest against 2.57 while moving) while commands per pass barely change, so the question
        // is *which* passes appear during motion: the one big main frame, or the many small offscreen
        // cell/target frames.
        CanvasRenderStageTrace.recordCompleted(
            "canvas-pass-shape", 0L, 0L,
            frame.viewport.width.toLong(), frame.viewport.height.toLong(), frame.commands.size.toLong())
        val resetStart = CanvasRenderStageTrace.start()
        configureCamera(camera, frame.viewport)
        currentTextureRevision = textureRevisionStore?.textureRevision ?: Int.MIN_VALUE
        currentFrameTextureRevision = frameTextureRevisionStore?.frameTextureRevision ?: Int.MIN_VALUE
        activeBatchIndex = -1
        activeBatchKey = null
        lastSpriteMeshKey = null
        lastSpriteMeshEntry = null
        materialBatchOrdinals.clear()
        ordinalMemoBatchIndex = Int.MIN_VALUE
        ordinalMemoValue = -1
        activeOrderingSegment = 0
        pendingOrderingSegmentAdvance = false
        projectedOrderingSegmentAdvances = 0
        projectedFrameTextureExpansions = 0
        nextFrameProjectionBatchId = 0
        activeFrameProjectionBatchId = null
        meshPruneRequired = false
        lastPrimitiveMeshKey = null
        lastPrimitiveMesh = null
        lastTextureMeshKey = null
        lastTextureMeshEntry = null
        lastInstancedTextureMeshKey = null
        lastInstancedTextureMeshEntry = null
        alternateInstancedTextureMeshKey = null
        alternateInstancedTextureMeshEntry = null
        lastAffineInstancedTextureMeshKey = null
        lastAffineInstancedTextureMeshEntry = null
        alternateAffineInstancedTextureMeshKey = null
        alternateAffineInstancedTextureMeshEntry = null
        if (textureRevisionStore == null) {
            clearResolvedTextureCache()
        }
        clearFrameTextureCache()
        lastUsedPrimitiveMeshKey = null
        lastUsedTextureMeshKey = null
        lastUsedInstancedTextureMeshKey = null
        lastUsedAffineInstancedTextureMeshKey = null
        lastUsedTeamColorTextureMeshKey = null
        lastUsedDisplacementTextureMeshKey = null
        lastUsedTextMeshKey = null
        usedPrimitiveMeshKeys.clear()
        usedTextureMeshKeys.clear()
        usedInstancedTextureMeshKeys.clear()
        usedAffineInstancedTextureMeshKeys.clear()
        usedTeamColorTextureMeshKeys.clear()
        usedDisplacementTextureMeshKeys.clear()
        usedTextMeshKeys.clear()
        usedSpriteMeshKeys.clear()
        usedRingMeshKeys.clear()
        usedFogMeshKeys.clear()
        for (entry in fogMeshes.values) {
            entry.instances.clear()
            entry.mesh.isVisible = false
        }
        for (entry in spriteMeshes.values) {
            entry.instances.clear()
            entry.mesh.isVisible = false
        }
        for (entry in ringMeshes.values) {
            entry.instances.clear()
            entry.mesh.isVisible = false
        }

        for (mesh in primitiveMeshes.values) {
            mesh.geometry.clear()
            mesh.isVisible = false
        }
        for (entry in textureMeshes.values) {
            entry.mesh.geometry.clear()
            entry.mesh.isVisible = false
        }
        for (entry in instancedTextureMeshes.values) {
            entry.instances.clear()
            entry.mesh.isVisible = false
        }
        for (entry in affineInstancedTextureMeshes.values) {
            entry.instances.clear()
            entry.mesh.isVisible = false
        }
        for (entry in teamColorTextureMeshes.values) {
            entry.mesh.geometry.clear()
            entry.mesh.isVisible = false
        }
        for (entry in displacementTextureMeshes.values) {
            entry.mesh.geometry.clear()
            entry.mesh.isVisible = false
        }
        for (entry in textMeshes.values) {
            entry.builder?.clear()
            entry.instances?.clear()
            entry.mesh.isVisible = false
        }

        val viewport = frame.viewport
        currentViewportHalfWidth = viewport.width * 0.5f
        currentViewportHalfHeight = viewport.height * 0.5f
        val commands = frame.commands
        CanvasRenderStageTrace.record("canvas-reset", resetStart)
        val commandsStart = CanvasRenderStageTrace.start()
        var commandIndex = 0
        // Diagnostic only: split each pass into fixed-size chunks. If the cost per command rises from the
        // first chunk of one pass to its last, the extra cost is a working-set/locality effect inside a
        // single pass; if it stays flat, the extra cost of a large pass sits in fixed per-pass work.
        var chunkMark = 0
        var chunkStart = commandsStart
        while (commandIndex < commands.size) {
            if (commandIndex - chunkMark >= 512) {
                CanvasRenderStageTrace.record("canvas-chunk", chunkStart, (commandIndex - chunkMark).toLong(), chunkMark.toLong())
                chunkStart = CanvasRenderStageTrace.start()
                chunkMark = commandIndex
            }
            val command = commands[commandIndex]
            // Diagnostic only: attribute loop time to the step that spends it. Summed across the run this
            // says whether `canvas-commands` is dominated by the visibility test, by the type dispatch, or
            // by texture resolution inside the draw path - the three candidates that decide whether the
            // fixed per-command cost can be lowered at all.
            val split = loopSplitNanos
            val loopStart = if (split != null) System.nanoTime() else 0L
            val visible = adaptiveVisuals.shouldDraw(command)
            if (split != null) split[LOOP_VISIBILITY] += System.nanoTime() - loopStart
            if (!visible) {
                commandIndex++
                continue
            }
            val dispatchStart = if (split != null) System.nanoTime() else 0L
            val timingKind = if (KoolCanvasCommandTiming.enabled) KoolCanvasCommandTiming.kindOf(command) else -1
            val timingStart = if (timingKind >= 0) System.nanoTime() else 0L
            try {
            try {
            when (command) {
                is KoolCanvasCommand.DrawFogBatch -> if (command.state.renderTarget == null) {
                    addFogBatch(scene, viewport, command)
                }
                is KoolCanvasCommand.DrawRectBatch -> if (command.state.renderTarget == null) {
                    addRectBatch(scene, viewport, command)
                }
                is KoolCanvasCommand.DrawTextureBatch -> if (command.state.renderTarget == null) {
                    addTextureBatch(scene, viewport, command)
                }
                is KoolCanvasCommand.DrawTexture -> if (command.state.renderTarget == null) {
                    if (KoolCanvasCommandTiming.enabled) recordTextureRunRejection(command)
                    val runStart = if (split != null) System.nanoTime() else 0L
                    if (addAtlasSprite(scene, viewport, command)) {
                        if (split != null) split[LOOP_TEXTURE_ATLAS] += System.nanoTime() - runStart
                        commandIndex++
                        continue
                    }
                    if (split != null) split[LOOP_TEXTURE_ATLAS] += System.nanoTime() - runStart
                    val runProbeStart = if (split != null) System.nanoTime() else 0L
                    val textureRunLength = addUnorderedDefaultInstancedTextureRun(
                        scene = scene,
                        viewport = viewport,
                        commands = commands,
                        startIndex = commandIndex,
                        firstCommand = command,
                    )
                    if (split != null) split[LOOP_TEXTURE_RUN] += System.nanoTime() - runProbeStart
                    if (textureRunLength > 0) {
                        // Diagnostic only: how many commands one batched run covered. Comparing this against
                        // the pass command total gives the batching coverage whose collapse explains why pass
                        // tails cost ~11x more per command than pass middles.
                        CanvasRenderStageTrace.record("canvas-run", CanvasRenderStageTrace.start(), textureRunLength.toLong())
                        commandIndex += textureRunLength
                        continue
                    } else {
                        val drawStart = if (split != null) System.nanoTime() else 0L
                        val framePrimitiveGeometry = addFrameTexture(scene, viewport, command)
                        if (framePrimitiveGeometry == null) {
                            addTexture(scene, viewport, command)
                        }
                        if (split != null) split[LOOP_TEXTURE_DRAW] += System.nanoTime() - drawStart
                    }
                }

                is KoolCanvasCommand.DrawTextureRepeat -> if (command.state.renderTarget == null) {
                    // Expand the repeat into single-tile draws and let the normal texture machinery place
                    // them. Batching the expansion itself would mean teaching the sprite-atlas and
                    // instanced-run paths about repeats; issuing N plain draws keeps one code path correct
                    // and still removes the per-tile command objects, which is where the saving is.
                    val repeatStart = if (split != null) System.nanoTime() else 0L
                    val tileWidth = (command.destination.width / command.repeat).coerceAtLeast(0f)
                    for (tile in 0 until command.repeat) {
                        val tileDestination = KoolCanvasRect(
                            left = command.destination.left + tileWidth * tile,
                            top = command.destination.top,
                            right = if (tile == command.repeat - 1) command.destination.right
                            else command.destination.left + tileWidth * (tile + 1),
                            bottom = command.destination.bottom,
                        )
                        val expanded = KoolCanvasCommand.DrawTexture(
                            texture = command.texture,
                            source = command.source,
                            destination = tileDestination,
                            paint = command.paint,
                            state = command.state,
                        )
                        if (!addAtlasSprite(scene, viewport, expanded)) {
                            val runLength = addUnorderedDefaultInstancedTextureRun(
                                scene = scene,
                                viewport = viewport,
                                commands = java.util.Collections.singletonList(expanded),
                                startIndex = 0,
                                firstCommand = expanded,
                            )
                            if (runLength == 0) {
                                if (addFrameTexture(scene, viewport, expanded) == null) {
                                    addTexture(scene, viewport, expanded)
                                }
                            }
                        }
                    }
                    if (split != null) split[LOOP_TEXTURE_DRAW] += System.nanoTime() - repeatStart
                }

                is KoolCanvasCommand.DrawRect -> if (command.state.renderTarget == null) {
                    val drawStart = if (split != null) System.nanoTime() else 0L
                    val rectRunLength = addFilledIdentityPrimitiveRectRun(
                        scene = scene,
                        viewport = viewport,
                        commands = commands,
                        startIndex = commandIndex,
                        firstCommand = command,
                    )
                    if (rectRunLength > 0) {
                        CanvasRenderStageTrace.record("canvas-run", CanvasRenderStageTrace.start(), rectRunLength.toLong())
                        if (split != null) split[LOOP_PRIMITIVE] += System.nanoTime() - drawStart
                        commandIndex += rectRunLength
                        continue
                    } else {
                        addRect(scene, viewport, command)
                    }
                    if (split != null) split[LOOP_PRIMITIVE] += System.nanoTime() - drawStart
                }

                is KoolCanvasCommand.DrawLine -> if (command.state.renderTarget == null) {
                    val drawStart = if (split != null) System.nanoTime() else 0L
                    addLine(scene, viewport, command)
                    if (split != null) split[LOOP_PRIMITIVE] += System.nanoTime() - drawStart
                }

                is KoolCanvasCommand.DrawCircle -> if (command.state.renderTarget == null) {
                    val drawStart = if (split != null) System.nanoTime() else 0L
                    if (!addSelectionRing(scene, viewport, command)) addCircle(scene, viewport, command)
                    if (split != null) split[LOOP_PRIMITIVE] += System.nanoTime() - drawStart
                }

                is KoolCanvasCommand.DrawText -> if (command.state.renderTarget == null) {
                    val drawStart = if (split != null) System.nanoTime() else 0L
                    addText(scene, viewport, command)
                    if (split != null) split[LOOP_TEXT] += System.nanoTime() - drawStart
                }

                is KoolCanvasCommand.Clear -> if (command.renderTarget == null) {
                    val drawStart = if (split != null) System.nanoTime() else 0L
                    setClearColor(ClearColorFill(command.toClearColor()))
                    if (split != null) split[LOOP_PRIMITIVE] += System.nanoTime() - drawStart
                }
            }
            } finally {
                if (split != null) split[LOOP_DISPATCH] += System.nanoTime() - dispatchStart
            }
            } finally {
                if (timingKind >= 0) KoolCanvasCommandTiming.record(timingKind, System.nanoTime() - timingStart)
            }
            if (split != null) split[LOOP_OTHER] += System.nanoTime() - loopStart
            commandIndex++
        }
        CanvasRenderStageTrace.record("canvas-chunk", chunkStart, (commandIndex - chunkMark).toLong(), chunkMark.toLong())
        CanvasRenderStageTrace.record("canvas-commands", commandsStart, commands.size.toLong())
        // Game-side tile-draw counters ride the canvas trace: the benchmark force-kills the process, so a
        // shutdown hook would never flush them.
        com.corrodinggames.rts.game.map.MapLayer.reportTileRunsIfTracing(diagnosticFrameSequence++)
        // Diagnostic only: loop-step attribution rides the canvas trace, because the benchmark force-kills
        // the game process and a shutdown hook would never flush it. Cumulative, so the last row wins.
        if (loopSplitNanos != null) {
            val split = loopSplitNanos!!
            val suffix = "@" + loopSplitInstance
            for (index in 0 until LOOP_SPLIT_COUNT) {
                CanvasRenderStageTrace.recordCompleted(LOOP_SPLIT_STAGES[index] + suffix, 0L, 0L, split[index])
            }
        }
        val finishStart = CanvasRenderStageTrace.start()
        for (mesh in primitiveMeshes.values) {
            mesh.isVisible = !mesh.geometry.isEmpty()
        }
        for (entry in textureMeshes.values) {
            entry.mesh.isVisible = !entry.mesh.geometry.isEmpty()
        }
        for (entry in instancedTextureMeshes.values) {
            val instances = entry.instances
            val hasInstances = instances.numInstances != 0
            if (hasInstances) {
                instances.incrementModCount()
            }
            entry.mesh.isVisible = hasInstances
        }
        for (entry in affineInstancedTextureMeshes.values) {
            val instances = entry.instances
            val hasInstances = instances.numInstances != 0
            if (hasInstances) {
                instances.incrementModCount()
            }
            entry.mesh.isVisible = hasInstances
        }
        for (entry in teamColorTextureMeshes.values) {
            entry.mesh.isVisible = !entry.mesh.geometry.isEmpty()
        }
        for (entry in displacementTextureMeshes.values) {
            entry.mesh.isVisible = !entry.mesh.geometry.isEmpty()
        }
        for (entry in textMeshes.values) {
            val instances = entry.instances
            entry.mesh.isVisible = if (instances == null) !entry.mesh.geometry.isEmpty() else instances.numInstances != 0
            if (entry.mesh.isVisible) instances?.incrementModCount()
        }
        for (entry in spriteMeshes.values) {
            entry.mesh.isVisible = entry.instances.numInstances != 0
            if (entry.mesh.isVisible) entry.instances.incrementModCount()
        }
        for (entry in ringMeshes.values) {
            entry.mesh.isVisible = entry.instances.numInstances != 0
            if (entry.mesh.isVisible) entry.instances.incrementModCount()
        }
        pruneUnusedMeshes(scene)
        labelGeometry?.let { atlas ->
            val stage = CanvasRenderStageTrace.start()
            if (atlas.finishFrame()) for ((material, shader) in textShaders) if (material.labels) {
                (shader as KoolCanvasInstancedTextShader).labelGeometry = atlas.texture
                shader.labelDimensions = atlas.dimensions
            }
            if (CanvasRenderStageTrace.enabled) {
                val state = atlas.snapshot()
                CanvasRenderStageTrace.record("canvas-label-geometry", stage, state.first.toLong(), state.second.toLong(), state.third)
            }
        }
        for (entry in fogMeshes.values) {
            entry.mesh.isVisible = entry.instances.numInstances != 0
            if (entry.mesh.isVisible) entry.instances.incrementModCount()
        }
        if (textMeshMetricsEnabled) CanvasFrameMetrics.textMeshCache(textMeshCreated, textMeshExactHits,
            textMeshReused, textMeshPruned, textMeshScanCandidates, textMeshes.size, textMeshPeak)
        (textureStore as? KoolCanvasRetiredTextureReleaser)?.releaseRetiredTextures()
        CanvasRenderStageTrace.record("canvas-finish", finishStart)
    }

    private fun resetSceneCachesIfNeeded(scene: Node) {
        if (attachedScene !== scene) {
            primitiveMeshes.clear()
            primitiveShaders.clear()
            usedPrimitiveMeshKeys.clear()
            textureMeshes.clear()
            usedTextureMeshKeys.clear()
            instancedTextureMeshes.clear()
            clearInstancedTextureMaterials()
            usedInstancedTextureMeshKeys.clear()
            affineInstancedTextureMeshes.clear()
            usedAffineInstancedTextureMeshKeys.clear()
            teamColorTextureMeshes.clear()
            usedTeamColorTextureMeshKeys.clear()
            displacementTextureMeshes.clear()
            usedDisplacementTextureMeshKeys.clear()
            textMeshes.clear()
            textMeshProbe = null
            textShaders.clear()
            renderFonts?.clear()
            textTemplates?.clear()
            labelGeometry?.clear()
            usedTextMeshKeys.clear()
            spriteMeshes.clear()
            spriteShaders.clear()
            usedSpriteMeshKeys.clear()
            ringMeshes.clear()
            fogMeshes.clear()
            usedFogMeshKeys.clear()
            usedRingMeshKeys.clear()
            ringShader = null
            meshLastUsed.clear()
            lastPrimitiveMeshKey = null
            lastPrimitiveMesh = null
            lastTextureMeshKey = null
            lastTextureMeshEntry = null
            lastInstancedTextureMeshKey = null
            lastInstancedTextureMeshEntry = null
            alternateInstancedTextureMeshKey = null
            alternateInstancedTextureMeshEntry = null
            lastAffineInstancedTextureMeshKey = null
            lastAffineInstancedTextureMeshEntry = null
            alternateAffineInstancedTextureMeshKey = null
            alternateAffineInstancedTextureMeshEntry = null
            clearResolvedTextureCache()
            clearFrameTextureCache()
            lastUsedPrimitiveMeshKey = null
            lastUsedTextureMeshKey = null
            lastUsedInstancedTextureMeshKey = null
            lastUsedAffineInstancedTextureMeshKey = null
            lastUsedTeamColorTextureMeshKey = null
            lastUsedDisplacementTextureMeshKey = null
            lastUsedTextMeshKey = null
            attachedScene = scene
        }
    }

    /** Cached meshes across every mesh map; growth here means retention, flat means churn. */
    internal fun cachedMeshCount(): Int =
        primitiveMeshes.size + textureMeshes.size + instancedTextureMeshes.size +
            affineInstancedTextureMeshes.size + teamColorTextureMeshes.size +
            displacementTextureMeshes.size + textMeshes.size + spriteMeshes.size + ringMeshes.size + fogMeshes.size

    /**
     * Per-map cache sizes, so the memory report names the map that grows instead of the total only.
     *
     * The total alone could not distinguish "one map leaks" from "many maps each hold a bounded set".
     */
    internal fun cachedMeshBreakdown(): String =
        "primitive=${primitiveMeshes.size} texture=${textureMeshes.size} " +
            "instanced=${instancedTextureMeshes.size} affine=${affineInstancedTextureMeshes.size} " +
            "teamColor=${teamColorTextureMeshes.size} displacement=${displacementTextureMeshes.size} " +
            "text=${textMeshes.size} sprite=${spriteMeshes.size} ring=${ringMeshes.size} fog=${fogMeshes.size}"

    /**
     * Drops every cached mesh of an offscreen target that has gone idle.
     *
     * The per-frame grace period already bounds a rendering slot; releasing on retire also bounds the
     * pooled slots, whose caches would otherwise sit in direct memory until they are reused.
     */
    internal fun releaseCachedMeshes(scene: Node) {
        pruneUnusedMeshMap(scene, primitiveMeshes, emptySet(), force = true) { it }
        pruneUnusedMeshMap(scene, textureMeshes, emptySet(), force = true) { it.mesh }
        pruneUnusedMeshMap(scene, instancedTextureMeshes, emptySet(), force = true) { it.mesh }
        clearInstancedTextureMaterials()
        pruneUnusedMeshMap(scene, affineInstancedTextureMeshes, emptySet(), force = true) { it.mesh }
        pruneUnusedMeshMap(scene, teamColorTextureMeshes, emptySet(), force = true) { it.mesh }
        pruneUnusedMeshMap(scene, displacementTextureMeshes, emptySet(), force = true) { it.mesh }
        pruneUnusedMeshMap(scene, textMeshes, emptySet(), force = true) { it.mesh }
        textShaders.clear()
        labelGeometry?.clear()
        spriteAtlas.clear()
        pruneUnusedMeshMap(scene, spriteMeshes, emptySet(), force = true) { it.mesh }
        pruneUnusedMeshMap(scene, ringMeshes, emptySet(), force = true) { it.mesh }
        pruneUnusedMeshMap(scene, fogMeshes, emptySet(), force = true) { it.mesh }
        spriteShaders.keys.removeAll { material -> spriteMeshes.keys.none { it.material == material } }
        meshLastUsed.clear()
    }

    /** One cached-mesh bucket by name, summed across slots by the memory diagnostics. */
    internal fun cachedMeshBucket(name: String): Int = when (name) {
        "primitive" -> primitiveMeshes.size
        "texture" -> textureMeshes.size
        "instanced" -> instancedTextureMeshes.size
        "affine" -> affineInstancedTextureMeshes.size
        "teamColor" -> teamColorTextureMeshes.size
        "displacement" -> displacementTextureMeshes.size
        "text" -> textMeshes.size
        "sprite" -> spriteMeshes.size
        "ring" -> ringMeshes.size
        "fog" -> fogMeshes.size
        else -> 0
    }
    /**
     * Ordinal of each material's batch runs within the current render call.
     *
     * `activeBatchIndex` counts *material switches*, so a batch's index is "how many switches preceded
     * it" 鈥?a value that changes as soon as the content changes, which invalidates every mesh key on the
     * next render call. Measured on a fog-enabled run: each cell replay created ~907 meshes (against ~56
     * without fog), because the fog overlay reorders the batch sequence. Keying a mesh by the ordinal of
     * its material's Nth batch keeps the key stable while the global index continues to drive
     * `drawGroupId`, which is what actually has to preserve draw order.
     */
    private val materialBatchOrdinals = mutableMapOf<Any?, Int>()
    private var ordinalMemoBatchIndex = Int.MIN_VALUE
    private var ordinalMemoValue = -1

    private fun materialBatchOrdinal(batchIndex: Int, material: Any?): Int {
        if (batchIndex == ordinalMemoBatchIndex) return ordinalMemoValue
        val ordinal = materialBatchOrdinals.getOrDefault(material, 0)
        materialBatchOrdinals[material] = ordinal + 1
        ordinalMemoBatchIndex = batchIndex
        ordinalMemoValue = ordinal
        return ordinal
    }

    private fun pruneUnusedMeshes(scene: Node) {
        pruneUnusedMeshMap(scene, primitiveMeshes, usedPrimitiveMeshKeys) { it }
        pruneUnusedMeshMap(scene, textureMeshes, usedTextureMeshKeys) { it.mesh }
        pruneUnusedMeshMap(scene, instancedTextureMeshes, usedInstancedTextureMeshKeys) { it.mesh }
        if (reuseInstancedTextureShaders && instancedTextureShaders.isNotEmpty()) {
            val liveShaders = instancedTextureMeshes.values.mapTo(mutableSetOf()) { it.shader }
            instancedTextureShaders.entries.removeAll {
                if (it.value !in liveShaders) { sharedInstancedTextureMaterials?.release(it.key); true } else false
            }
        }
        pruneUnusedMeshMap(scene, affineInstancedTextureMeshes, usedAffineInstancedTextureMeshKeys) { it.mesh }
        pruneUnusedMeshMap(scene, teamColorTextureMeshes, usedTeamColorTextureMeshKeys) { it.mesh }
        pruneUnusedMeshMap(scene, displacementTextureMeshes, usedDisplacementTextureMeshKeys) { it.mesh }
        val previousTextMeshCount = textMeshes.size
        pruneUnusedMeshMap(scene, textMeshes, usedTextMeshKeys) { it.mesh }
        textMeshPruned += previousTextMeshCount - textMeshes.size
        if (previousTextMeshCount != textMeshes.size) textShaders.keys.removeAll { material ->
            textMeshes.keys.none { it.fontData === material.fontData && it.renderBlend == material.blend &&
                it.instanced == material.instanced && (it.labelVertexCapacity != 0) == material.labels }
        }
        pruneUnusedMeshMap(scene, spriteMeshes, usedSpriteMeshKeys) { it.mesh }
        pruneUnusedMeshMap(scene, ringMeshes, usedRingMeshKeys) { it.mesh }
        pruneUnusedMeshMap(scene, fogMeshes, usedFogMeshKeys) { it.mesh }
        spriteShaders.keys.removeAll { material -> spriteMeshes.keys.none { it.material == material } }
    }

    private fun markPrimitiveMeshKeyUsed(key: PrimitiveMeshKey) {
        if (lastUsedPrimitiveMeshKey != key) {
            usedPrimitiveMeshKeys += key
            lastUsedPrimitiveMeshKey = key
        }
    }

    private fun markTextureMeshKeyUsed(key: TextureMeshKey) {
        if (lastUsedTextureMeshKey != key) {
            usedTextureMeshKeys += key
            lastUsedTextureMeshKey = key
        }
    }

    private fun markInstancedTextureMeshKeyUsed(key: TextureMeshKey) {
        if (lastUsedInstancedTextureMeshKey != key) {
            usedInstancedTextureMeshKeys += key
            lastUsedInstancedTextureMeshKey = key
        }
    }

    private fun markAffineInstancedTextureMeshKeyUsed(key: TextureMeshKey) {
        if (lastUsedAffineInstancedTextureMeshKey != key) {
            usedAffineInstancedTextureMeshKeys += key
            lastUsedAffineInstancedTextureMeshKey = key
        }
    }

    private fun markTeamColorTextureMeshKeyUsed(key: TeamColorTextureMeshKey) {
        if (lastUsedTeamColorTextureMeshKey != key) {
            usedTeamColorTextureMeshKeys += key
            lastUsedTeamColorTextureMeshKey = key
        }
    }

    private fun markDisplacementTextureMeshKeyUsed(key: DisplacementTextureMeshKey) {
        if (lastUsedDisplacementTextureMeshKey != key) {
            usedDisplacementTextureMeshKeys += key
            lastUsedDisplacementTextureMeshKey = key
        }
    }

    private fun markTextMeshKeyUsed(key: TextMeshKey) {
        if (lastUsedTextMeshKey != key) {
            usedTextMeshKeys += key
            lastUsedTextMeshKey = key
        }
    }

    private fun <K, V> pruneUnusedMeshMap(
        scene: Node,
        meshes: MutableMap<K, V>,
        usedKeys: Set<K>,
        force: Boolean = false,
        meshOf: (V) -> Mesh<*>,
    ) {
        val iterator = meshes.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            val mesh = meshOf(entry)
            if (key in usedKeys) {
                meshLastUsed[mesh] = renderSequence
            } else if (force || renderSequence - (meshLastUsed[mesh] ?: renderSequence) > meshRetentionRenders ||
                meshes.size > 4096
            ) {
                scene.removeNode(mesh)
                KoolCanvasGpuRetirement.retire { mesh.release() }
                meshLastUsed.remove(mesh)
                iterator.remove()
            }
        }
    }

    private fun addAtlasSprite(scene: Node, viewport: KoolCanvasViewport, command: KoolCanvasCommand.DrawTexture): Boolean {
        if (!useSpriteAtlas) return false
        if (frameProjectionDepth != 0 && !projectedSpriteAtlas || command.paint.isRenderNoOp) return false
        val projected = frameProjectionDepth != 0
        // Projected texture geometry clips in source coordinates before applying its transform.
        // Applying an additional fragment clip changes rotated edge coverage, so use the identical
        // destination / UV crop and let the viewport clip the resulting geometry.
        val geometryClip = if (projected) command.state.clipForGeometry() else null
        var d = command.destination
        var source = command.source
        if (geometryClip != null) {
            val left = maxOf(d.boundsLeft, geometryClip.boundsLeft)
            val top = maxOf(d.boundsTop, geometryClip.boundsTop)
            val right = minOf(d.boundsRight, geometryClip.boundsRight)
            val bottom = minOf(d.boundsBottom, geometryClip.boundsBottom)
            if (right <= left || bottom <= top) return false
            val cropped = KoolCanvasRect(if (d.width >= 0f) left else right,
                if (d.height >= 0f) top else bottom, if (d.width >= 0f) right else left,
                if (d.height >= 0f) bottom else top)
            source = KoolCanvasRect(source.left + source.width * ((cropped.left - d.left) / d.width),
                source.top + source.height * ((cropped.top - d.top) / d.height),
                source.left + source.width * ((cropped.right - d.left) / d.width),
                source.top + source.height * ((cropped.bottom - d.top) / d.height))
            d = cropped
        }
        val slot = spriteAtlas.slot(command) ?: return false
        val activeMaterial = (activeBatchKey as? CanvasBatchKey.Sprite)?.material
        val material = activeMaterial?.takeIf {
            it.pageSerial == slot.page.serial && it.filter == command.paint.textureFilter && it.blend == command.paint.renderBlend
        } ?: SpriteMaterialKey(slot.page.serial, command.paint.textureFilter, command.paint.renderBlend)
        if (activeMaterial !== material) {
            if (pendingOrderingSegmentAdvance) advanceOrderingSegmentIfAllowed()
            activeBatchIndex++
            activeBatchKey = CanvasBatchKey.Sprite(material)
        }
        val cachedKey = lastSpriteMeshKey
        val key = cachedKey?.takeIf { it.batchIndex == activeBatchIndex && it.material == material }
            ?: SpriteMeshKey(activeBatchIndex, material)
        val entry = if (key === cachedKey) checkNotNull(lastSpriteMeshEntry) else spriteMeshes.getOrPut(key) {
            val shader = spriteShaders.getOrPut(material) {
                KoolCanvasSpriteShader(PipelineConfig(
                    blendMode = material.blend.textureBlendMode,
                    cullMethod = CullMethod.NO_CULLING, depthTest = DepthCompareOp.ALWAYS, isWriteDepth = false,
                ), additive = material.blend == CanvasRenderBlend.Additive)
            }
            val instances = MeshInstanceList(KoolCanvasSpriteInstanceLayout, initialSize = ORDERED_TEXTURE_INSTANCE_INITIAL_SIZE)
            val mesh = Mesh(IndexedVertexList(VertexLayouts.Position, initialSize = QUAD_STORAGE_SIZE, usage = Usage.STATIC), instances,
                name = "rwx-canvas-sprites-${material.pageSerial}-$activeBatchIndex")
            mesh.shader = shader
            mesh.isOpaque = false
            mesh.geometry.addVertex { set(it.position, 0f, 0f, 0f) }
            mesh.geometry.addVertex { set(it.position, 1f, 0f, 0f) }
            mesh.geometry.addVertex { set(it.position, 1f, 1f, 0f) }
            mesh.geometry.addVertex { set(it.position, 0f, 1f, 0f) }
            mesh.geometry.addTriIndices(0, 1, 2)
            mesh.geometry.addTriIndices(0, 2, 3)
            SpriteMeshEntry(mesh, instances, shader)
        }
        if (key !== cachedKey) {
            lastSpriteMeshKey = key
            lastSpriteMeshEntry = entry
            entry.shader.colorMap = spriteAtlas.texture(slot.page, material.filter)
            if (usedSpriteMeshKeys.add(key)) placeBatchNode(scene, entry.mesh, activeBatchIndex)
        }
        val t = command.state.transform
        // Preserve map()'s arithmetic order while writing instance scalars directly.
        val x0 = t.scaleX * d.left + t.skewX * d.top + t.translateX
        val y0 = t.skewY * d.left + t.scaleY * d.top + t.translateY
        val xx = t.scaleX * d.right + t.skewX * d.top + t.translateX
        val yx = t.skewY * d.right + t.scaleY * d.top + t.translateY
        val xy = t.scaleX * d.left + t.skewX * d.bottom + t.translateX
        val yy = t.skewY * d.left + t.scaleY * d.bottom + t.translateY
        val tint = command.paint.toRenderColor()
        val effect = command.paint.textureEffect as? KoolCanvasTextureEffect.TeamColor
        val mode = effect?.mode?.let { it.ordinal + 1 }?.toFloat() ?: 0f
        val team = effect?.color?.toKoolColor() ?: Color.WHITE
        val clip = command.state.clip
        val halfWidth = viewport.width * 0.5f
        val halfHeight = viewport.height * 0.5f
        entry.instances.addInstances(1) { data ->
            data.put { layout ->
                set(layout.originAxisX, x0 - currentViewportHalfWidth, currentViewportHalfHeight - y0,
                    xx - x0, -(yx - y0))
                set(layout.axisYModeAmount, xy - x0, -(yy - y0), mode, effect?.amount ?: 0f)
                set(layout.uv, (slot.x + source.left) / slot.page.size, (slot.y + source.top) / slot.page.size,
                    (slot.x + source.right) / slot.page.size, (slot.y + source.bottom) / slot.page.size)
                set(layout.tint, tint.r, tint.g, tint.b, tint.a)
                set(layout.team, team.r, team.g, team.b, team.a)
                if (projected) set(layout.clip, -1e20f, -1e20f, 1e20f, 1e20f)
                else set(layout.clip, (clip?.left ?: 0f) - halfWidth,
                        halfHeight - (clip?.bottom ?: viewport.height.toFloat()),
                        (clip?.right ?: viewport.width.toFloat()) - halfWidth, halfHeight - (clip?.top ?: 0f))
            }
        }
        markOrderingBarrier()
        return true
    }

    private fun addSelectionRing(scene: Node, viewport: KoolCanvasViewport, command: KoolCanvasCommand.DrawCircle): Boolean {
        if (command.state.drawRole != KoolCanvasDrawRole.SelectionRing || command.paint.style != KoolCanvasPaintStyle.Stroke ||
            command.paint.blendMode != KoolCanvasBlendMode.SourceOver || command.paint.textureEffect != null ||
            command.radius <= 0f || command.paint.isRenderNoOp) return false
        val t = command.state.transform
        val scale = hypot(t.scaleX, t.skewY)
        if (!scale.isFinite() || scale <= 0f || abs(scale - hypot(t.skewX, t.scaleY)) > 0.0001f ||
            abs(t.scaleX * t.skewX + t.skewY * t.scaleY) > 0.0001f) return false
        if (activeBatchKey != CanvasBatchKey.Ring) {
            if (pendingOrderingSegmentAdvance) advanceOrderingSegmentIfAllowed()
            activeBatchIndex++
            activeBatchKey = CanvasBatchKey.Ring
        }
        val entry = ringMeshes.getOrPut(activeBatchIndex) {
            val shader = ringShader ?: KoolCanvasRingShader(PipelineConfig(
                blendMode = KoolBlendMode.BLEND_MULTIPLY_ALPHA, cullMethod = CullMethod.NO_CULLING,
                depthTest = DepthCompareOp.ALWAYS, isWriteDepth = false,
            )).also { ringShader = it }
            val instances = MeshInstanceList(KoolCanvasRingInstanceLayout, initialSize = 128)
            val mesh = Mesh(IndexedVertexList(VertexLayouts.Position, initialSize = QUAD_STORAGE_SIZE, usage = Usage.STATIC), instances,
                name = "rwx-canvas-selection-rings-$activeBatchIndex")
            mesh.shader = shader
            mesh.isOpaque = false
            mesh.geometry.addVertex { set(it.position, 0f, 0f, 0f) }
            mesh.geometry.addVertex { set(it.position, 1f, 0f, 0f) }
            mesh.geometry.addVertex { set(it.position, 1f, 1f, 0f) }
            mesh.geometry.addVertex { set(it.position, 0f, 1f, 0f) }
            mesh.geometry.addTriIndices(0, 1, 2)
            mesh.geometry.addTriIndices(0, 2, 3)
            RingMeshEntry(mesh, instances)
        }
        if (usedRingMeshKeys.add(activeBatchIndex)) placeBatchNode(scene, entry.mesh, activeBatchIndex)
        val center = t.map(command.center)
        val radius = command.radius * scale
        val halfStroke = command.paint.strokeWidth.coerceAtLeast(1f) * scale * 0.5f
        val extent = radius + halfStroke + 1f
        val color = command.paint.toRenderColor()
        val clip = command.state.clip.toWorldClip(viewport)
        entry.instances.addInstance { layout ->
            set(layout.rect, center.x - extent - currentViewportHalfWidth,
                currentViewportHalfHeight - center.y + extent, center.x + extent - currentViewportHalfWidth,
                currentViewportHalfHeight - center.y - extent)
            set(layout.props, extent, radius, halfStroke, 0f)
            set(layout.color, color.r, color.g, color.b, color.a)
            set(layout.clip, clip)
        }
        markOrderingBarrier()
        return true
    }

    private fun ensurePrimitiveMesh(
        scene: Node,
        renderBlend: CanvasRenderBlend,
        layer: PrimitiveMeshLayer,
        ordered: Boolean,
    ): Mesh<VertexLayouts.PositionNormalColor> {
        val batchIndex = batchIndexForPrimitive(renderBlend, layer)
        // The ordinal is memoized, but asking for it three times still costs three map probes per call
        // and two of the results are discarded by `takeIf`; probe once and derive all three.
        val batchOrdinal = materialBatchOrdinal(batchIndex, activeBatchKey)
        val keyBatchIndex = batchOrdinal.takeIf { ordered }
        val keyOrderingSegment = batchOrdinal.takeIf { !ordered }
        val keyProjectionBatchId = batchOrdinal.takeIf { !ordered }
        val cachedKey = lastPrimitiveMeshKey
        if (cachedKey != null &&
            cachedKey.batchIndex == keyBatchIndex &&
            cachedKey.orderingSegment == keyOrderingSegment &&
            cachedKey.projectionBatchId == keyProjectionBatchId &&
            cachedKey.renderBlend == renderBlend &&
            cachedKey.layer == layer
        ) {
            markPrimitiveMeshKeyUsed(cachedKey)
            val cachedMesh = lastPrimitiveMesh!!
            placeBatchNodeIfFirstUse(scene, cachedMesh, batchIndex)
            return cachedMesh
        }
        val key = PrimitiveMeshKey(
            batchIndex = keyBatchIndex,
            orderingSegment = keyOrderingSegment,
            projectionBatchId = keyProjectionBatchId,
            renderBlend = renderBlend,
            layer = layer,
        )
        markPrimitiveMeshKeyUsed(key)
        val existing = primitiveMeshes[key]
        if (existing != null) {
            lastPrimitiveMeshKey = key
            lastPrimitiveMesh = existing
            placeBatchNodeIfFirstUse(scene, existing, batchIndex)
            return existing
        }

        val mesh = Mesh(
            geometry = IndexedVertexList(VertexLayouts.PositionNormalColor, usage = Usage.DYNAMIC),
            name = "rwx-kool-canvas-primitives${renderBlend.meshNameSuffix}${layer.meshNameSuffix}",
        ).apply {
            shader = createPrimitiveShader(renderBlend)
            isOpaque = false
            isVisible = false
        }
        placeBatchNode(scene, mesh, batchIndex)
        lastPrimitiveMeshKey = key
        lastPrimitiveMesh = mesh
        meshPruneRequired = true
        return mesh.also { primitiveMeshes[key] = it }
    }

    private fun rememberFrozenPixelMeshIdentity(texture: KoolCanvasTextureRef) {
        if (!reuseFrozenPixelMeshes) return
        val identity = texture.frozenPixelIdentity ?: return
        if (texture.id in frameTextureMeshIdentities) return
        val meshScope = if (meshIdentityIgnoresRecordingOwner) {
            KoolCanvasFrozenPixelIdentity(recordingOwnerId = 0L, logicalTextureId = identity.logicalTextureId)
        } else {
            identity
        }
        // A frame can intentionally sample two historical versions of the same logical texture.
        // They need independent shaders/bindings even though later frames can reuse each slot.
        val slot = frameFrozenPixelVersionCounts[meshScope] ?: 0
        frameFrozenPixelVersionCounts[meshScope] = slot + 1
        frameTextureMeshIdentities[texture.id] = TextureMeshIdentity.FrozenPixels(meshScope, slot)
    }

    private fun meshTextureIdentity(id: KoolCanvasTextureId): TextureMeshIdentity =
        frameTextureMeshIdentities.getOrPut(id) { TextureMeshIdentity.Version(id) }

    private fun ensureTextureMesh(
        scene: Node,
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        texture: Texture2d,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
        ordered: Boolean,
    ): TextureMeshEntry {
        val textureIdentity = meshTextureIdentity(textureId)
        val batchIndex = batchIndexForTexture(textureId, textureFilter, renderBlend, premultipliedAlpha)
        // The ordinal is memoized, but asking for it three times still costs three map probes per call
        // and two of the results are discarded by `takeIf`; probe once and derive all three.
        val batchOrdinal = materialBatchOrdinal(batchIndex, activeBatchKey)
        val keyBatchIndex = batchOrdinal.takeIf { ordered }
        val keyOrderingSegment = batchOrdinal.takeIf { !ordered }
        val keyProjectionBatchId = batchOrdinal.takeIf { !ordered }
        val cachedKey = lastTextureMeshKey
        if (cachedKey != null &&
            cachedKey.batchIndex == keyBatchIndex &&
            cachedKey.orderingSegment == keyOrderingSegment &&
            cachedKey.projectionBatchId == keyProjectionBatchId &&
            cachedKey.textureIdentity == textureIdentity &&
            cachedKey.textureFilter == textureFilter &&
            cachedKey.renderBlend == renderBlend &&
            cachedKey.premultipliedAlpha == premultipliedAlpha
        ) {
            markTextureMeshKeyUsed(cachedKey)
            val cachedEntry = lastTextureMeshEntry!!
            if (cachedEntry.texture !== texture) {
                cachedEntry.texture = texture
                cachedEntry.shader.colorMap = texture
            }
            placeBatchNodeIfFirstUse(scene, cachedEntry.mesh, batchIndex)
            return cachedEntry
        }
        val key = TextureMeshKey(
            batchIndex = keyBatchIndex,
            orderingSegment = keyOrderingSegment,
            projectionBatchId = keyProjectionBatchId,
            textureIdentity = textureIdentity,
            textureFilter = textureFilter,
            renderBlend = renderBlend,
            premultipliedAlpha = premultipliedAlpha,
        )
        markTextureMeshKeyUsed(key)
        val existing = textureMeshes[key]
        if (existing != null) {
            if (existing.texture !== texture) {
                existing.texture = texture
                existing.shader.colorMap = texture
            }
            lastTextureMeshKey = key
            lastTextureMeshEntry = existing
            placeBatchNodeIfFirstUse(scene, existing.mesh, batchIndex)
            return existing
        }

        val shader = createTextureShader(texture, renderBlend, premultipliedAlpha)
        val mesh = Mesh(
            geometry = IndexedVertexList(VertexLayouts.PositionNormalTexCoordColor, usage = Usage.DYNAMIC),
            name = "rwx-kool-canvas-texture-${textureId.value}${renderBlend.meshNameSuffix}",
        ).apply {
            this.shader = shader
            isOpaque = false
            isVisible = false
        }
        placeBatchNode(scene, mesh, batchIndex)
        lastTextureMeshKey = key
        lastTextureMeshEntry = TextureMeshEntry(texture, mesh, shader)
        meshPruneRequired = true
        return lastTextureMeshEntry!!.also {
            textureMeshes[key] = it
        }
    }

    /**
     * A GPU target slot is reclaimed only after its attachment's frame fence. At the start of this
     * render all retained instance lists are cleared. Re-keying a still-unused compatible mesh keeps
     * its CPU capacity, native geometry and shader bindings, instead of creating all three again.
     * Two runs used in this render never share a mesh; the new draw group still determines order.
     * A shared material requires the identical resolved texture. An independent shader may use the
     * existing version-rebinding path; no other current mesh can observe its binding change.
     */
    private inline fun <E> takeUnusedMapMesh(
        meshes: MutableMap<TextureMeshKey, E>, usedKeys: Set<TextureMeshKey>, key: TextureMeshKey,
        texture: Texture2d, canRebindTexture: Boolean, textureOf: (E) -> Texture2d, meshOf: (E) -> Mesh<*>,
    ): E? {
        if (!reuseMapMeshSlots || !meshIdentityIgnoresRecordingOwner || texture.isReleased) return null
        val iterator = meshes.iterator()
        while (iterator.hasNext()) {
            val (oldKey, entry) = iterator.next()
            if (oldKey in usedKeys || oldKey.textureFilter != key.textureFilter ||
                oldKey.renderBlend != key.renderBlend || oldKey.premultipliedAlpha != key.premultipliedAlpha ||
                (!canRebindTexture && textureOf(entry) !== texture)) continue
            val mesh = meshOf(entry)
            if (mesh.isVisible || mesh.isReleased || mesh.instances?.numInstances != 0) continue
            iterator.remove()
            meshes[key] = entry
            meshLastUsed[mesh] = renderSequence
            if (mapMeshSlotReused++ == 0L && textMeshMetricsEnabled)
                println("RWXMapMeshSlots reuseConfirmed=true")
            return entry
        }
        return null
    }

    private fun ensureInstancedTextureMesh(
        scene: Node,
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        texture: Texture2d,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
        ordered: Boolean,
    ): InstancedTextureMeshEntry {
        val textureIdentity = meshTextureIdentity(textureId)
        if (!ordered) {
            return ensureUnorderedInstancedTextureMesh(
                scene,
                textureId,
                textureFilter,
                texture,
                renderBlend,
                premultipliedAlpha,
            )
        }
        val batchIndex = batchIndexForTexture(textureId, textureFilter, renderBlend, premultipliedAlpha)
        // The ordinal is memoized, but asking for it three times still costs three map probes per call
        // and two of the results are discarded by `takeIf`; probe once and derive all three.
        val batchOrdinal = materialBatchOrdinal(batchIndex, activeBatchKey)
        val keyBatchIndex = batchOrdinal.takeIf { ordered }
        val keyOrderingSegment = batchOrdinal.takeIf { !ordered }
        val keyProjectionBatchId = batchOrdinal.takeIf { !ordered }
        val cachedKey = lastInstancedTextureMeshKey
        if (cachedKey != null &&
            cachedKey.batchIndex == keyBatchIndex &&
            cachedKey.orderingSegment == keyOrderingSegment &&
            cachedKey.projectionBatchId == keyProjectionBatchId &&
            cachedKey.textureIdentity == textureIdentity &&
            cachedKey.textureFilter == textureFilter &&
            cachedKey.renderBlend == renderBlend &&
            cachedKey.premultipliedAlpha == premultipliedAlpha
        ) {
            markInstancedTextureMeshKeyUsed(cachedKey)
            val cachedEntry = lastInstancedTextureMeshEntry!!
            updateInstancedTextureEntry(cachedEntry, texture)
            placeInstancedBatchNodeIfFirstUse(scene, cachedEntry.mesh, batchIndex)
            return cachedEntry
        }
        val key = TextureMeshKey(
            batchIndex = keyBatchIndex,
            orderingSegment = keyOrderingSegment,
            projectionBatchId = keyProjectionBatchId,
            textureIdentity = textureIdentity,
            textureFilter = textureFilter,
            renderBlend = renderBlend,
            premultipliedAlpha = premultipliedAlpha,
        )
        markInstancedTextureMeshKeyUsed(key)
        val existing = instancedTextureMeshes[key] ?: takeUnusedMapMesh(instancedTextureMeshes,
            usedInstancedTextureMeshKeys, key, texture, !reuseInstancedTextureShaders, { it.texture }, { it.mesh })
        if (existing != null) {
            updateInstancedTextureEntry(existing, texture)
            lastInstancedTextureMeshKey = key
            lastInstancedTextureMeshEntry = existing
            placeInstancedBatchNodeIfFirstUse(scene, existing.mesh, batchIndex)
            return existing
        }

        val batchKey = CanvasBatchKey.Texture(textureId, textureFilter, renderBlend, premultipliedAlpha)
        val shader = createInstancedTextureShader(texture, renderBlend, premultipliedAlpha)
        val instances =
            MeshInstanceList(KoolCanvasTextureInstanceLayout, initialSize = ORDERED_TEXTURE_INSTANCE_INITIAL_SIZE)
        val mesh = createInstancedTextureMesh(
            textureId,
            renderBlend,
            shader,
            instances,
        )
        placeBatchNode(scene, mesh, batchIndex)
        lastInstancedTextureMeshKey = key
        lastInstancedTextureMeshEntry = InstancedTextureMeshEntry(texture, mesh, shader, batchKey, instances)
        meshPruneRequired = true
        return lastInstancedTextureMeshEntry!!.also {
            instancedTextureMeshes[key] = it
        }
    }

    private fun ensureUnorderedInstancedTextureMesh(
        scene: Node,
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        texture: Texture2d,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): InstancedTextureMeshEntry {
        val textureIdentity = meshTextureIdentity(textureId)
        if (pendingOrderingSegmentAdvance) {
            val matchesActiveBatch =
                activeTextureBatchMatches(textureId, textureFilter, renderBlend, premultipliedAlpha)
            if (!matchesActiveBatch) {
                // Diagnostic only: an ordering-segment advance starts a new batch group, which breaks the
                // batching run that would otherwise cover the following commands. Counting these is how the
                // "heterogeneous pass tails are 11x more expensive" measurement gets attributed.
                val advanceStart = CanvasRenderStageTrace.start()
                advanceOrderingSegmentIfAllowed()
                CanvasRenderStageTrace.record("segment-advance", advanceStart)
            }
        }
        val keyOrderingSegment = activeOrderingSegment
        val keyProjectionBatchId = activeFrameProjectionBatchId
        val cachedKey = lastInstancedTextureMeshKey
        if (cachedKey.matchesUnorderedTexture(
                keyOrderingSegment,
                keyProjectionBatchId,
                textureIdentity,
                textureFilter,
                renderBlend,
                premultipliedAlpha,
            )
        ) {
            val cachedEntry = lastInstancedTextureMeshEntry!!
            if (cachedEntry.batchKey.textureId != textureId) {
                cachedEntry.batchKey = CanvasBatchKey.Texture(textureId, textureFilter, renderBlend, premultipliedAlpha)
            }
            val batchIndex = activateTextureBatch(cachedEntry.batchKey)
            markInstancedTextureMeshKeyUsed(cachedKey!!)
            updateInstancedTextureEntry(cachedEntry, texture)
            placeInstancedBatchNodeIfFirstUse(scene, cachedEntry.mesh, batchIndex)
            return cachedEntry
        }
        val alternateKey = alternateInstancedTextureMeshKey
        if (alternateKey.matchesUnorderedTexture(
                keyOrderingSegment,
                keyProjectionBatchId,
                textureIdentity,
                textureFilter,
                renderBlend,
                premultipliedAlpha,
            )
        ) {
            val alternateEntry = alternateInstancedTextureMeshEntry!!
            alternateInstancedTextureMeshKey = lastInstancedTextureMeshKey
            alternateInstancedTextureMeshEntry = lastInstancedTextureMeshEntry
            lastInstancedTextureMeshKey = alternateKey
            lastInstancedTextureMeshEntry = alternateEntry
            if (alternateEntry.batchKey.textureId != textureId) {
                alternateEntry.batchKey = CanvasBatchKey.Texture(textureId, textureFilter, renderBlend, premultipliedAlpha)
            }
            val batchIndex = activateTextureBatch(alternateEntry.batchKey)
            markInstancedTextureMeshKeyUsed(alternateKey!!)
            updateInstancedTextureEntry(alternateEntry, texture)
            placeInstancedBatchNodeIfFirstUse(scene, alternateEntry.mesh, batchIndex)
            return alternateEntry
        }

        val key = TextureMeshKey(
            batchIndex = null,
            orderingSegment = keyOrderingSegment,
            projectionBatchId = keyProjectionBatchId,
            textureIdentity = textureIdentity,
            textureFilter = textureFilter,
            renderBlend = renderBlend,
            premultipliedAlpha = premultipliedAlpha,
        )
        val existing = instancedTextureMeshes[key] ?: takeUnusedMapMesh(instancedTextureMeshes,
            usedInstancedTextureMeshKeys, key, texture, !reuseInstancedTextureShaders, { it.texture }, { it.mesh })
        if (existing != null) {
            alternateInstancedTextureMeshKey = lastInstancedTextureMeshKey
            alternateInstancedTextureMeshEntry = lastInstancedTextureMeshEntry
            lastInstancedTextureMeshKey = key
            lastInstancedTextureMeshEntry = existing
            if (existing.batchKey.textureId != textureId) {
                existing.batchKey = CanvasBatchKey.Texture(textureId, textureFilter, renderBlend, premultipliedAlpha)
            }
            val batchIndex = activateTextureBatch(existing.batchKey)
            markInstancedTextureMeshKeyUsed(key)
            updateInstancedTextureEntry(existing, texture)
            placeInstancedBatchNodeIfFirstUse(scene, existing.mesh, batchIndex)
            return existing
        }

        val batchKey = CanvasBatchKey.Texture(textureId, textureFilter, renderBlend, premultipliedAlpha)
        val batchIndex = activateTextureBatch(batchKey)
        val shader = createInstancedTextureShader(texture, renderBlend, premultipliedAlpha)
        val instances =
            MeshInstanceList(KoolCanvasTextureInstanceLayout, initialSize = UNORDERED_TEXTURE_INSTANCE_INITIAL_SIZE)
        val mesh = createInstancedTextureMesh(
            textureId,
            renderBlend,
            shader,
            instances,
        )
        placeBatchNode(scene, mesh, batchIndex)
        val entry = InstancedTextureMeshEntry(texture, mesh, shader, batchKey, instances)
        alternateInstancedTextureMeshKey = lastInstancedTextureMeshKey
        alternateInstancedTextureMeshEntry = lastInstancedTextureMeshEntry
        lastInstancedTextureMeshKey = key
        lastInstancedTextureMeshEntry = entry
        markInstancedTextureMeshKeyUsed(key)
        meshPruneRequired = true
        instancedTextureMeshes[key] = entry
        return entry
    }

    private fun TextureMeshKey?.matchesUnorderedTexture(
        orderingSegment: Int,
        projectionBatchId: Int?,
        textureIdentity: TextureMeshIdentity,
        textureFilter: KoolCanvasTextureFilter,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): Boolean =
        this != null &&
                batchIndex == null &&
                this.orderingSegment == orderingSegment &&
                this.projectionBatchId == projectionBatchId &&
                this.textureIdentity == textureIdentity &&
                this.textureFilter == textureFilter &&
                this.renderBlend == renderBlend &&
                this.premultipliedAlpha == premultipliedAlpha

    private fun activeTextureBatchMatches(
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): Boolean {
        val current = activeBatchKey as? CanvasBatchKey.Texture ?: return false
        return current.textureId == textureId &&
                current.textureFilter == textureFilter &&
                current.renderBlend == renderBlend &&
                current.premultipliedAlpha == premultipliedAlpha
    }

    private fun activateTextureBatch(batchKey: CanvasBatchKey.Texture): Int {
        if (activeBatchKey !== batchKey && activeBatchKey != batchKey) {
            activeBatchIndex++
            activeBatchKey = batchKey
        }
        return activeBatchIndex
    }

    private fun updateInstancedTextureEntry(entry: InstancedTextureMeshEntry, texture: Texture2d) {
        if (entry.texture !== texture) {
            entry.texture = texture
            if (reuseInstancedTextureShaders) {
                // A pooled material's texture is immutable. Rebind this mesh to a different material;
                // mutating the shared shader would change every other mesh still using the old image.
                entry.shader = createInstancedTextureShader(texture, entry.batchKey.renderBlend, entry.batchKey.premultipliedAlpha)
                entry.mesh.shader = entry.shader
            } else entry.shader.colorMap = texture
        }
    }

    private fun createInstancedTextureMesh(
        textureId: KoolCanvasTextureId,
        renderBlend: CanvasRenderBlend,
        shader: KoolCanvasInstancedTextureShader,
        instances: MeshInstanceList<KoolCanvasTextureInstanceLayout>,
    ): Mesh<VertexLayouts.Position> =
        Mesh(
            geometry = IndexedVertexList(VertexLayouts.Position, initialSize = QUAD_STORAGE_SIZE, usage = Usage.STATIC),
            instances = instances,
            name = "rwx-kool-canvas-texture-${textureId.value}${renderBlend.meshNameSuffix}",
        ).apply {
            this.shader = shader
            isOpaque = false
            isVisible = false
            geometry.addVertex { layout ->
                set(layout.position, 0f, 0f, 0f)
            }
            geometry.addVertex { layout ->
                set(layout.position, 1f, 0f, 0f)
            }
            geometry.addVertex { layout ->
                set(layout.position, 1f, 1f, 0f)
            }
            geometry.addVertex { layout ->
                set(layout.position, 0f, 1f, 0f)
            }
            geometry.addTriIndices(0, 1, 2)
            geometry.addTriIndices(0, 2, 3)
        }

    private fun ensureAffineInstancedTextureMesh(
        scene: Node,
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        texture: Texture2d,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
        ordered: Boolean,
    ): AffineInstancedTextureMeshEntry {
        val textureIdentity = meshTextureIdentity(textureId)
        if (!ordered) {
            return ensureUnorderedAffineInstancedTextureMesh(
                scene,
                textureId,
                textureFilter,
                texture,
                renderBlend,
                premultipliedAlpha,
            )
        }
        val batchIndex = batchIndexForAffineTexture(textureId, textureFilter, renderBlend, premultipliedAlpha)
        // The ordinal is memoized, but asking for it three times still costs three map probes per call
        // and two of the results are discarded by `takeIf`; probe once and derive all three.
        val batchOrdinal = materialBatchOrdinal(batchIndex, activeBatchKey)
        val keyBatchIndex = batchOrdinal.takeIf { ordered }
        val keyOrderingSegment = batchOrdinal.takeIf { !ordered }
        val keyProjectionBatchId = batchOrdinal.takeIf { !ordered }
        val cachedKey = lastAffineInstancedTextureMeshKey
        if (cachedKey != null &&
            cachedKey.batchIndex == keyBatchIndex &&
            cachedKey.orderingSegment == keyOrderingSegment &&
            cachedKey.projectionBatchId == keyProjectionBatchId &&
            cachedKey.textureIdentity == textureIdentity &&
            cachedKey.textureFilter == textureFilter &&
            cachedKey.renderBlend == renderBlend &&
            cachedKey.premultipliedAlpha == premultipliedAlpha
        ) {
            markAffineInstancedTextureMeshKeyUsed(cachedKey)
            val cachedEntry = lastAffineInstancedTextureMeshEntry!!
            updateAffineInstancedTextureEntry(cachedEntry, texture)
            placeInstancedBatchNodeIfFirstUse(scene, cachedEntry.mesh, batchIndex)
            return cachedEntry
        }
        val key = TextureMeshKey(
            batchIndex = keyBatchIndex,
            orderingSegment = keyOrderingSegment,
            projectionBatchId = keyProjectionBatchId,
            textureIdentity = textureIdentity,
            textureFilter = textureFilter,
            renderBlend = renderBlend,
            premultipliedAlpha = premultipliedAlpha,
        )
        markAffineInstancedTextureMeshKeyUsed(key)
        val existing = affineInstancedTextureMeshes[key] ?: takeUnusedMapMesh(affineInstancedTextureMeshes,
            usedAffineInstancedTextureMeshKeys, key, texture, true, { it.texture }, { it.mesh })
        if (existing != null) {
            updateAffineInstancedTextureEntry(existing, texture)
            lastAffineInstancedTextureMeshKey = key
            lastAffineInstancedTextureMeshEntry = existing
            placeInstancedBatchNodeIfFirstUse(scene, existing.mesh, batchIndex)
            return existing
        }

        val batchKey = CanvasBatchKey.AffineTexture(textureId, textureFilter, renderBlend, premultipliedAlpha)
        val shader = createAffineInstancedTextureShader(texture, renderBlend, premultipliedAlpha)
        val instances =
            MeshInstanceList(
                KoolCanvasAffineTextureInstanceLayout,
                initialSize = ORDERED_TEXTURE_INSTANCE_INITIAL_SIZE
            )
        val mesh = createAffineInstancedTextureMesh(
            textureId,
            renderBlend,
            shader,
            instances,
        )
        placeBatchNode(scene, mesh, batchIndex)
        lastAffineInstancedTextureMeshKey = key
        lastAffineInstancedTextureMeshEntry =
            AffineInstancedTextureMeshEntry(texture, mesh, shader, batchKey, instances)
        meshPruneRequired = true
        return lastAffineInstancedTextureMeshEntry!!.also {
            affineInstancedTextureMeshes[key] = it
        }
    }

    private fun ensureUnorderedAffineInstancedTextureMesh(
        scene: Node,
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        texture: Texture2d,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): AffineInstancedTextureMeshEntry {
        val textureIdentity = meshTextureIdentity(textureId)
        if (pendingOrderingSegmentAdvance) {
            val matchesActiveBatch =
                activeAffineTextureBatchMatches(textureId, textureFilter, renderBlend, premultipliedAlpha)
            if (!matchesActiveBatch) {
                // Diagnostic only: an ordering-segment advance starts a new batch group, which breaks the
                // batching run that would otherwise cover the following commands. Counting these is how the
                // "heterogeneous pass tails are 11x more expensive" measurement gets attributed.
                val advanceStart = CanvasRenderStageTrace.start()
                advanceOrderingSegmentIfAllowed()
                CanvasRenderStageTrace.record("segment-advance", advanceStart)
            }
        }
        val keyOrderingSegment = activeOrderingSegment
        val keyProjectionBatchId = activeFrameProjectionBatchId
        val cachedKey = lastAffineInstancedTextureMeshKey
        if (cachedKey.matchesUnorderedTexture(
                keyOrderingSegment,
                keyProjectionBatchId,
                textureIdentity,
                textureFilter,
                renderBlend,
                premultipliedAlpha,
            )
        ) {
            val cachedEntry = lastAffineInstancedTextureMeshEntry!!
            if (cachedEntry.batchKey.textureId != textureId) {
                cachedEntry.batchKey = CanvasBatchKey.AffineTexture(textureId, textureFilter, renderBlend, premultipliedAlpha)
            }
            val batchIndex = activateAffineTextureBatch(cachedEntry.batchKey)
            markAffineInstancedTextureMeshKeyUsed(cachedKey!!)
            updateAffineInstancedTextureEntry(cachedEntry, texture)
            placeInstancedBatchNodeIfFirstUse(scene, cachedEntry.mesh, batchIndex)
            return cachedEntry
        }
        val alternateKey = alternateAffineInstancedTextureMeshKey
        if (alternateKey.matchesUnorderedTexture(
                keyOrderingSegment,
                keyProjectionBatchId,
                textureIdentity,
                textureFilter,
                renderBlend,
                premultipliedAlpha,
            )
        ) {
            val alternateEntry = alternateAffineInstancedTextureMeshEntry!!
            alternateAffineInstancedTextureMeshKey = lastAffineInstancedTextureMeshKey
            alternateAffineInstancedTextureMeshEntry = lastAffineInstancedTextureMeshEntry
            lastAffineInstancedTextureMeshKey = alternateKey
            lastAffineInstancedTextureMeshEntry = alternateEntry
            if (alternateEntry.batchKey.textureId != textureId) {
                alternateEntry.batchKey = CanvasBatchKey.AffineTexture(textureId, textureFilter, renderBlend, premultipliedAlpha)
            }
            val batchIndex = activateAffineTextureBatch(alternateEntry.batchKey)
            markAffineInstancedTextureMeshKeyUsed(alternateKey!!)
            updateAffineInstancedTextureEntry(alternateEntry, texture)
            placeInstancedBatchNodeIfFirstUse(scene, alternateEntry.mesh, batchIndex)
            return alternateEntry
        }

        val key = TextureMeshKey(
            batchIndex = null,
            orderingSegment = keyOrderingSegment,
            projectionBatchId = keyProjectionBatchId,
            textureIdentity = textureIdentity,
            textureFilter = textureFilter,
            renderBlend = renderBlend,
            premultipliedAlpha = premultipliedAlpha,
        )
        val existing = affineInstancedTextureMeshes[key] ?: takeUnusedMapMesh(affineInstancedTextureMeshes,
            usedAffineInstancedTextureMeshKeys, key, texture, true, { it.texture }, { it.mesh })
        if (existing != null) {
            alternateAffineInstancedTextureMeshKey = lastAffineInstancedTextureMeshKey
            alternateAffineInstancedTextureMeshEntry = lastAffineInstancedTextureMeshEntry
            lastAffineInstancedTextureMeshKey = key
            lastAffineInstancedTextureMeshEntry = existing
            if (existing.batchKey.textureId != textureId) {
                existing.batchKey = CanvasBatchKey.AffineTexture(textureId, textureFilter, renderBlend, premultipliedAlpha)
            }
            val batchIndex = activateAffineTextureBatch(existing.batchKey)
            markAffineInstancedTextureMeshKeyUsed(key)
            updateAffineInstancedTextureEntry(existing, texture)
            placeInstancedBatchNodeIfFirstUse(scene, existing.mesh, batchIndex)
            return existing
        }

        val batchKey = CanvasBatchKey.AffineTexture(textureId, textureFilter, renderBlend, premultipliedAlpha)
        val batchIndex = activateAffineTextureBatch(batchKey)
        val shader = createAffineInstancedTextureShader(texture, renderBlend, premultipliedAlpha)
        val instances =
            MeshInstanceList(KoolCanvasAffineTextureInstanceLayout, initialSize = UNORDERED_TEXTURE_INSTANCE_INITIAL_SIZE)
        val mesh = createAffineInstancedTextureMesh(
            textureId,
            renderBlend,
            shader,
            instances,
        )
        placeBatchNode(scene, mesh, batchIndex)
        val entry = AffineInstancedTextureMeshEntry(texture, mesh, shader, batchKey, instances)
        alternateAffineInstancedTextureMeshKey = lastAffineInstancedTextureMeshKey
        alternateAffineInstancedTextureMeshEntry = lastAffineInstancedTextureMeshEntry
        lastAffineInstancedTextureMeshKey = key
        lastAffineInstancedTextureMeshEntry = entry
        markAffineInstancedTextureMeshKeyUsed(key)
        meshPruneRequired = true
        affineInstancedTextureMeshes[key] = entry
        return entry
    }

    private fun activeAffineTextureBatchMatches(
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): Boolean {
        val current = activeBatchKey as? CanvasBatchKey.AffineTexture ?: return false
        return current.textureId == textureId &&
                current.textureFilter == textureFilter &&
                current.renderBlend == renderBlend &&
                current.premultipliedAlpha == premultipliedAlpha
    }

    private fun activateAffineTextureBatch(batchKey: CanvasBatchKey.AffineTexture): Int {
        if (activeBatchKey !== batchKey && activeBatchKey != batchKey) {
            activeBatchIndex++
            activeBatchKey = batchKey
        }
        return activeBatchIndex
    }

    private fun updateAffineInstancedTextureEntry(entry: AffineInstancedTextureMeshEntry, texture: Texture2d) {
        if (entry.texture !== texture) {
            entry.texture = texture
            entry.shader.colorMap = texture
        }
    }

    private fun createAffineInstancedTextureMesh(
        textureId: KoolCanvasTextureId,
        renderBlend: CanvasRenderBlend,
        shader: KoolCanvasAffineInstancedTextureShader,
        instances: MeshInstanceList<KoolCanvasAffineTextureInstanceLayout>,
    ): Mesh<VertexLayouts.Position> =
        Mesh(
            geometry = IndexedVertexList(VertexLayouts.Position, initialSize = QUAD_STORAGE_SIZE, usage = Usage.STATIC),
            instances = instances,
            name = "rwx-kool-canvas-texture-${textureId.value}${renderBlend.meshNameSuffix}",
        ).apply {
            this.shader = shader
            isOpaque = false
            isVisible = false
            geometry.addVertex { layout ->
                set(layout.position, 0f, 0f, 0f)
            }
            geometry.addVertex { layout ->
                set(layout.position, 1f, 0f, 0f)
            }
            geometry.addVertex { layout ->
                set(layout.position, 1f, 1f, 0f)
            }
            geometry.addVertex { layout ->
                set(layout.position, 0f, 1f, 0f)
            }
            geometry.addTriIndices(0, 1, 2)
            geometry.addTriIndices(0, 2, 3)
        }

    private fun ensureTeamColorTextureMesh(
        scene: Node,
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        texture: Texture2d,
        effect: KoolCanvasTextureEffect.TeamColor,
        renderBlend: CanvasRenderBlend,
        ordered: Boolean,
    ): TeamColorTextureMeshEntry {
        val batchIndex = batchIndexFor(CanvasBatchKey.TeamColorTexture(textureId, textureFilter, renderBlend, effect))
        val key = TeamColorTextureMeshKey(
            batchIndex = batchIndex.takeIf { ordered },
            orderingSegment = activeOrderingSegment.takeIf { !ordered },
            projectionBatchId = activeFrameProjectionBatchId.takeIf { !ordered },
            textureId = textureId,
            textureFilter = textureFilter,
            renderBlend = renderBlend,
            effect = effect,
        )
        markTeamColorTextureMeshKeyUsed(key)
        val existing = teamColorTextureMeshes[key]
        if (existing != null) {
            if (existing.texture !== texture) {
                existing.texture = texture
                existing.shader.colorMap = texture
            }
            placeBatchNodeIfFirstUse(scene, existing.mesh, batchIndex)
            return existing
        }

        val shader = createTeamColorShader(effect, renderBlend).apply {
            colorMap = texture
        }
        val mesh = Mesh(
            geometry = IndexedVertexList(VertexLayouts.PositionNormalTexCoordColor, usage = Usage.DYNAMIC),
            name = "rwx-kool-canvas-team-color-${textureId.value}-${effect.mode}${renderBlend.meshNameSuffix}",
        ).apply {
            this.shader = shader
            isOpaque = false
            isVisible = false
        }
        placeBatchNode(scene, mesh, batchIndex)
        meshPruneRequired = true
        return TeamColorTextureMeshEntry(texture, mesh, shader).also {
            teamColorTextureMeshes[key] = it
        }
    }

    private fun ensureDisplacementTextureMesh(
        scene: Node,
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        displacementMap: Texture2d,
        screenBase: Texture2d,
        effect: KoolCanvasTextureEffect.Displacement,
        renderBlend: CanvasRenderBlend,
        viewport: KoolCanvasViewport,
        ordered: Boolean,
    ): DisplacementTextureMeshEntry {
        val batchIndex =
            batchIndexFor(CanvasBatchKey.DisplacementTexture(textureId, textureFilter, renderBlend, effect))
        val key = DisplacementTextureMeshKey(
            batchIndex = batchIndex.takeIf { ordered },
            orderingSegment = activeOrderingSegment.takeIf { !ordered },
            projectionBatchId = activeFrameProjectionBatchId.takeIf { !ordered },
            textureId = textureId,
            textureFilter = textureFilter,
            renderBlend = renderBlend,
            effect = effect,
        )
        markDisplacementTextureMeshKeyUsed(key)
        val existing = displacementTextureMeshes[key]
        if (existing != null) {
            if (existing.displacementMap !== displacementMap) {
                existing.displacementMap = displacementMap
                existing.shader.displacementMap = displacementMap
            }
            if (existing.screenBase !== screenBase) {
                existing.screenBase = screenBase
                existing.shader.screenBase = screenBase
            }
            existing.shader.viewportSize = Vec2f(viewport.width.toFloat(), viewport.height.toFloat())
            placeBatchNodeIfFirstUse(scene, existing.mesh, batchIndex)
            return existing
        }

        val shader = createDisplacementShader(effect, renderBlend, viewport).apply {
            this.displacementMap = displacementMap
            this.screenBase = screenBase
        }
        val mesh = Mesh(
            geometry = IndexedVertexList(VertexLayouts.PositionNormalTexCoordColor, usage = Usage.DYNAMIC),
            name = "rwx-kool-canvas-displacement-${textureId.value}${renderBlend.meshNameSuffix}",
        ).apply {
            this.shader = shader
            isOpaque = false
            isVisible = false
        }
        placeBatchNode(scene, mesh, batchIndex)
        meshPruneRequired = true
        return DisplacementTextureMeshEntry(displacementMap, screenBase, mesh, shader).also {
            displacementTextureMeshes[key] = it
        }
    }

    private fun createDisplacementShader(
        effect: KoolCanvasTextureEffect.Displacement,
        renderBlend: CanvasRenderBlend,
        viewport: KoolCanvasViewport,
    ): KoolDisplacementShader {
        val pipeline = PipelineConfig(
            blendMode = renderBlend.textureBlendMode,
            cullMethod = CullMethod.NO_CULLING,
            depthTest = DepthCompareOp.ALWAYS,
            isWriteDepth = false,
        )
        return KoolDisplacementShader(pipeline).apply {
            offsetBy = effect.offsetBy
            viewportSize = Vec2f(viewport.width.toFloat(), viewport.height.toFloat())
        }
    }

    private fun createTeamColorShader(
        effect: KoolCanvasTextureEffect.TeamColor,
        renderBlend: CanvasRenderBlend,
    ): KoolTeamColorShader {
        val pipeline = PipelineConfig(
            blendMode = renderBlend.textureBlendMode,
            cullMethod = CullMethod.NO_CULLING,
            depthTest = DepthCompareOp.ALWAYS,
            isWriteDepth = false,
        )
        return KoolTeamColorShader(
            mode = effect.mode,
            pipelineConfig = pipeline,
            multipliesRgbByAlpha = renderBlend == CanvasRenderBlend.Additive,
        ).apply {
            teamColor = effect.color.toVec4f()
            teamColorAmount = effect.amount
        }
    }

    private fun ensureTextMesh(
        scene: Node,
        font: MsdfFont,
        renderBlend: CanvasRenderBlend,
        typefaceKey: String?,
        ordered: Boolean,
        performanceHud: Boolean = false,
        instanced: Boolean = false,
        labelVertexCapacity: Int = 0,
    ): TextMeshEntry {
        // All lengths share the original material/order key. Grow its static index geometry only
        // when a longer label first appears; separate length buckets multiply draws and pipelines.
        val labelKind = if (labelVertexCapacity != 0) 1 else 0
        val batchIndex = if (reuseTextMeshKeys) batchIndexForText(font.data, renderBlend, typefaceKey, performanceHud, labelKind)
            else batchIndexFor(CanvasBatchKey.Text(font.data, renderBlend, typefaceKey, performanceHud, labelKind))
        val lookup = if (reuseTextMeshKeys && textMeshProbe != null) checkNotNull(textMeshProbe).apply {
            this.batchIndex = batchIndex.takeIf { ordered }
            orderingSegment = activeOrderingSegment.takeIf { !ordered }
            projectionBatchId = activeFrameProjectionBatchId.takeIf { !ordered }
            fontData = font.data; this.renderBlend = renderBlend; this.typefaceKey = typefaceKey
            this.performanceHud = performanceHud; this.instanced = instanced; this.labelVertexCapacity = labelKind
        } else TextMeshKey(
            batchIndex = batchIndex.takeIf { ordered },
            orderingSegment = activeOrderingSegment.takeIf { !ordered },
            projectionBatchId = activeFrameProjectionBatchId.takeIf { !ordered },
            fontData = font.data,
            renderBlend = renderBlend,
            typefaceKey = typefaceKey,
            performanceHud = performanceHud,
            instanced = instanced,
            labelVertexCapacity = labelKind,
        )
        if (reuseTextMeshKeys) textMeshProbe = lookup
        val existing = textMeshes[lookup]
        if (existing != null) {
            // Only detached canonical keys enter used-key sets. The mutable probe is never stored.
            markTextMeshKeyUsed(checkNotNull(existing.cacheKey))
            textMeshExactHits++
            if (reuseTextMeshKeys && !textKeyReuseConfirmed && textMeshMetricsEnabled) {
                textKeyReuseConfirmed = true
                println("RWXTextMeshKeys reuseConfirmed=true")
            }
            // Instance meshes keep their static vertices between frames. Their first use is
            // indicated by an empty instance list, so geometry.isEmpty() cannot update ordering.
            if (existing.instances != null) placeInstancedBatchNodeIfFirstUse(scene, existing.mesh, batchIndex)
            else placeBatchNodeIfFirstUse(scene, existing.mesh, batchIndex)
            return existing
        }

        val key = if (reuseTextMeshKeys) lookup.copy() else lookup
        markTextMeshKeyUsed(key)
        reusableTextMesh(key)?.let { existingEntry ->
            if (existingEntry.instances != null) placeInstancedBatchNodeIfFirstUse(scene, existingEntry.mesh, batchIndex)
            else placeBatchNodeIfFirstUse(scene, existingEntry.mesh, batchIndex)
            return existingEntry
        }

        if (instanced) {
            val instances = MeshInstanceList(KoolCanvasGlyphInstanceLayout, initialSize = 16)
            val shader = textShader(font.data, renderBlend, true, labelVertexCapacity != 0)
            val mesh = Mesh(geometry = IndexedVertexList(VertexLayouts.Position, initialSize = maxOf(4, labelVertexCapacity)), instances = instances,
                name = "rwx-kool-canvas-text-instanced-${font.data.meta.name}").apply {
                this.shader = shader
                isOpaque = false; isVisible = false; isCastingShadow = false
                if (labelVertexCapacity == 0) {
                    geometry.addVertex { layout -> set(layout.position, 0f, 0f, 0f) }
                    geometry.addVertex { layout -> set(layout.position, 1f, 0f, 0f) }
                    geometry.addVertex { layout -> set(layout.position, 0f, 1f, 0f) }
                    geometry.addVertex { layout -> set(layout.position, 1f, 1f, 0f) }
                    geometry.addTriIndices(3, 1, 0); geometry.addTriIndices(2, 3, 0)
                } else {
                    repeat(labelVertexCapacity) { vertex -> geometry.addVertex { layout ->
                        set(layout.position, vertex.toFloat(), 0f, 0f) } }
                    for (vertex in 0 until labelVertexCapacity step 4) {
                        geometry.addTriIndices(vertex + 3, vertex + 1, vertex)
                        geometry.addTriIndices(vertex + 2, vertex + 3, vertex)
                    }
                }
            }
            placeBatchNode(scene, mesh, batchIndex)
            meshPruneRequired = true
            return TextMeshEntry(font.data, renderBlend, mesh, null, instances, labelVertexCapacity).also {
                it.cacheKey = key
                textMeshes[key] = it; textMeshCreated++; textMeshPeak = maxOf(textMeshPeak, textMeshes.size)
            }
        }

        val shader = textShader(font.data, renderBlend, false)
        val mesh = Mesh(
            geometry = IndexedVertexList(UiTextVertexLayout, usage = Usage.DYNAMIC),
            name = "rwx-kool-canvas-text-${font.data.meta.name}-${typefaceKey ?: "default"}${renderBlend.meshNameSuffix}",
        ).apply {
            this.shader = shader
            isOpaque = false
            isVisible = false
            isCastingShadow = false
        }
        val builder = MeshBuilder(mesh.geometry).apply {
            isInvertFaceOrientation = true
        }
        placeBatchNode(scene, mesh, batchIndex)
        meshPruneRequired = true
        return TextMeshEntry(font.data, renderBlend, mesh, builder).also {
            it.cacheKey = key
            textMeshes[key] = it
            textMeshCreated++
            textMeshPeak = maxOf(textMeshPeak, textMeshes.size)
        }
    }

    private fun textShader(fontData: MsdfFontData, renderBlend: CanvasRenderBlend, instanced: Boolean, labels: Boolean = false): DrawShader {
        val key = TextMaterialKey(fontData, renderBlend, instanced, labels)
        if (reuseTextShaders || labels) textShaders[key]?.let { return it }
        val pipeline = PipelineConfig(cullMethod = CullMethod.NO_CULLING, blendMode = renderBlend.textBlendMode)
        val shader = if (instanced) KoolCanvasInstancedTextShader(KoolCanvasInstancedTextShader.Model(labels), pipeline).apply {
            fontMap = fontData.map
            if (labels) { labelGeometry = this@KoolCanvasFrameRenderer.labelGeometry?.texture
                labelDimensions = this@KoolCanvasFrameRenderer.labelGeometry!!.dimensions }
        }
            else MsdfUiShader(pipelineCfg = pipeline).apply { fontMap = fontData.map }
        if (reuseTextShaders || labels) textShaders[key] = shader
        return shader
    }

    private fun reusableTextMesh(key: TextMeshKey): TextMeshEntry? {
        if (!reuseTextMeshes || key.batchIndex == null || key.orderingSegment != null || key.projectionBatchId != null) return null
        val iterator = textMeshes.iterator()
        while (iterator.hasNext()) {
            val (previousKey, entry) = iterator.next()
            textMeshScanCandidates++
            if (previousKey in usedTextMeshKeys || previousKey.batchIndex == null ||
                previousKey.orderingSegment != null || previousKey.projectionBatchId != null ||
                previousKey.fontData !== key.fontData || previousKey.renderBlend != key.renderBlend ||
                previousKey.typefaceKey != key.typefaceKey || previousKey.performanceHud != key.performanceHud ||
                previousKey.instanced != key.instanced || previousKey.labelVertexCapacity != key.labelVertexCapacity) continue
            // Geometry was cleared at frame start. Keep its shader/atlas owner and change only placement.
            iterator.remove()
            entry.cacheKey = key
            textMeshes[key] = entry
            textMeshReused++
            meshPruneRequired = true
            return entry
        }
        return null
    }

    private fun batchIndexForText(fontData: MsdfFontData, renderBlend: CanvasRenderBlend,
        typefaceKey: String?, performanceHud: Boolean, labelKind: Int): Int {
        val current = activeBatchKey as? CanvasBatchKey.Text
        val matches = current != null && current.fontData === fontData && current.renderBlend == renderBlend &&
            current.typefaceKey == typefaceKey && current.performanceHud == performanceHud && current.labelVertexCapacity == labelKind
        if (pendingOrderingSegmentAdvance && !matches) advanceOrderingSegmentIfAllowed()
        if (!matches) {
            activeBatchIndex++
            activeBatchKey = CanvasBatchKey.Text(fontData, renderBlend, typefaceKey, performanceHud, labelKind)
        }
        return activeBatchIndex
    }

    private fun batchIndexFor(key: CanvasBatchKey): Int {
        if (pendingOrderingSegmentAdvance && activeBatchKey != key) {
            if (frameProjectionDepth == 0 || projectedOrderingSegmentAdvances < MaxProjectedOrderingSegmentAdvances) {
                activeOrderingSegment++
                if (frameProjectionDepth > 0) {
                    projectedOrderingSegmentAdvances++
                }
            }
            pendingOrderingSegmentAdvance = false
        }
        if (activeBatchKey != key) {
            activeBatchIndex++
            activeBatchKey = key
        }
        return activeBatchIndex
    }

    private fun batchIndexForPrimitive(renderBlend: CanvasRenderBlend, layer: PrimitiveMeshLayer): Int {
        val current = activeBatchKey as? CanvasBatchKey.Primitive
        val matchesCurrent = current != null && current.renderBlend == renderBlend && current.layer == layer
        if (pendingOrderingSegmentAdvance && !matchesCurrent) {
            advanceOrderingSegmentIfAllowed()
        }
        if (!matchesCurrent) {
            activeBatchIndex++
            activeBatchKey = CanvasBatchKey.Primitive(renderBlend, layer)
        }
        return activeBatchIndex
    }

    private fun batchIndexForTexture(
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): Int {
        val current = activeBatchKey as? CanvasBatchKey.Texture
        val matchesCurrent = current != null &&
                current.textureId == textureId &&
                current.textureFilter == textureFilter &&
                current.renderBlend == renderBlend &&
                current.premultipliedAlpha == premultipliedAlpha
        if (pendingOrderingSegmentAdvance && !matchesCurrent) {
            advanceOrderingSegmentIfAllowed()
        }
        if (!matchesCurrent) {
            activeBatchIndex++
            activeBatchKey = CanvasBatchKey.Texture(textureId, textureFilter, renderBlend, premultipliedAlpha)
        }
        return activeBatchIndex
    }

    private fun batchIndexForAffineTexture(
        textureId: KoolCanvasTextureId,
        textureFilter: KoolCanvasTextureFilter,
        renderBlend: CanvasRenderBlend,
        premultipliedAlpha: Boolean,
    ): Int {
        val current = activeBatchKey as? CanvasBatchKey.AffineTexture
        val matchesCurrent = current != null &&
                current.textureId == textureId &&
                current.textureFilter == textureFilter &&
                current.renderBlend == renderBlend &&
                current.premultipliedAlpha == premultipliedAlpha
        if (pendingOrderingSegmentAdvance && !matchesCurrent) {
            advanceOrderingSegmentIfAllowed()
        }
        if (!matchesCurrent) {
            activeBatchIndex++
            activeBatchKey = CanvasBatchKey.AffineTexture(textureId, textureFilter, renderBlend, premultipliedAlpha)
        }
        return activeBatchIndex
    }

    private fun advanceOrderingSegmentIfAllowed() {
        if (frameProjectionDepth == 0 || projectedOrderingSegmentAdvances < MaxProjectedOrderingSegmentAdvances) {
            activeOrderingSegment++
            if (frameProjectionDepth > 0) {
                projectedOrderingSegmentAdvances++
            }
        }
        pendingOrderingSegmentAdvance = false
    }

    private fun placeBatchNodeIfFirstUse(scene: Node, mesh: Mesh<*>, batchIndex: Int) {
        if (!mesh.isVisible && mesh.geometry.isEmpty()) {
            placeBatchNode(scene, mesh, batchIndex)
        }
    }

    private fun placeInstancedBatchNodeIfFirstUse(scene: Node, mesh: Mesh<*>, batchIndex: Int) {
        if (!mesh.isVisible && mesh.instances?.numInstances == 0) {
            placeBatchNode(scene, mesh, batchIndex)
        }
    }

    private fun placeBatchNode(scene: Node, mesh: Mesh<*>, batchIndex: Int) {
        // Kool orders draw groups before traversing meshes. Changing a group is O(1), unlike
        // searching and splicing the scene's children for every sprite batch.
        mesh.drawGroupId = batchIndex + 1
        if (mesh.parent !== scene) {
            scene.addNode(mesh)
        }
    }

    private fun markOrderingBarrier() {
        pendingOrderingSegmentAdvance = true
    }

    private fun configureCamera(camera: OrthographicCamera, viewport: KoolCanvasViewport) {
        val halfWidth = viewport.width * 0.5f
        val halfHeight = viewport.height * 0.5f
        camera.left = -halfWidth
        camera.right = halfWidth
        camera.bottom = -halfHeight
        camera.top = halfHeight
        camera.isKeepAspectRatio = false
        camera.isClipToViewport = false
        camera.setupCamera(
            position = Vec3f(0f, 0f, 100f),
            lookAt = Vec3f(0f, 0f, 0f),
        )
    }

    /**
     * Diagnostic: which condition keeps a texture command out of the instanced run.
     *
     * The run is what turns N consecutive same-texture draws into one command, so the first failing
     * condition names the reason terrain tiles are issued one by one (measured: ~6 570 draws per cell,
     * and lowering that count 3.5x changed no wall clock).
     */
    private fun recordTextureRunRejection(command: KoolCanvasCommand.DrawTexture) {
        val reason = when {
            !command.paint.canJoinInstancedTextureRun() -> "paint"
            command.state.renderTarget != null -> "renderTarget"
            !command.state.clip.isRedundantFor(command.destination) -> "clip"
            command.state.transform !== KoolCanvasTransform.Identity -> "transform"
            command.source.hasZeroArea -> "sourceArea"
            command.destination.hasZeroArea -> "destinationArea"
            command.texture.hasAlpha -> "hasAlpha"
            command.texture.requiresOrderedAlpha -> "orderedAlpha"
            command.texture.premultipliedAlpha -> "premultiplied"
            else -> "eligible"
        }
        KoolCanvasCommandTiming.countRunReason(reason)
    }

    private fun addFogBatch(scene: Node, viewport: KoolCanvasViewport, command: KoolCanvasCommand.DrawFogBatch): Boolean {
        val ref = command.texture
        if (command.state.transform !== KoolCanvasTransform.Identity || command.paint != KoolCanvasPaint.Default ||
            (ref != null && resolveFrameTexture(ref.id) != null)) {
            var added = false
            command.masks.forEachDraw(ref, command.filter, command.state, command.paint) {
                added = when (it) {
                    is KoolCanvasCommand.DrawRect -> addRect(scene, viewport, it)
                    is KoolCanvasCommand.DrawTexture -> addFrameTexture(scene, viewport, it) ?: addTexture(scene, viewport, it)
                    else -> error("Unexpected fog mask")
                } || added
            }
            return added
        }
        val material = CanvasBatchKey.Fog(ref?.id, command.filter)
        val batchIndex = batchIndexFor(material)
        val key = FogMeshKey(ref?.id, command.filter, materialBatchOrdinal(batchIndex, material))
        val entry = fogMeshes.getOrPut(key) {
            val instances = MeshInstanceList(KoolCanvasFogInstanceLayout, initialSize = 256)
            val shader = KoolCanvasFogShader()
            val mesh = Mesh(IndexedVertexList(VertexLayouts.Position, initialSize = QUAD_STORAGE_SIZE, usage = Usage.STATIC), instances,
                name = "rwx-canvas-fog-${key.ordinal}")
            mesh.shader = shader
            mesh.isOpaque = false
            mesh.geometry.addVertex { set(it.position, 0f, 0f, 0f) }
            mesh.geometry.addVertex { set(it.position, 1f, 0f, 0f) }
            mesh.geometry.addVertex { set(it.position, 1f, 1f, 0f) }
            mesh.geometry.addVertex { set(it.position, 0f, 1f, 0f) }
            mesh.geometry.addTriIndices(0, 1, 2)
            mesh.geometry.addTriIndices(0, 2, 3)
            FogMeshEntry(mesh, instances, shader)
        }
        if (ref != null) entry.shader.colorMap = resolveTexture(ref, command.filter)
        if (usedFogMeshKeys.add(key)) placeBatchNode(scene, entry.mesh, batchIndex)
        val clip = command.state.clip
        val masks = command.masks
        var added = false
        for (i in 0 until masks.size) {
            val x0 = masks.coordinate(i, 0); val y0 = masks.coordinate(i, 1)
            val x1 = masks.coordinate(i, 2); val y1 = masks.coordinate(i, 3)
            val left = maxOf(x0, clip?.boundsLeft ?: x0)
            val top = maxOf(y0, clip?.boundsTop ?: y0)
            val right = minOf(x1, clip?.boundsRight ?: x1)
            val bottom = minOf(y1, clip?.boundsBottom ?: y1)
            if (right <= left || bottom <= top) continue
            val sx = masks.coordinate(i, 4); val sy = masks.coordinate(i, 5)
            val sw = masks.coordinate(i, 6) - sx; val sh = masks.coordinate(i, 7) - sy
            val inverseWidth = ref?.inverseSafeWidth ?: 1f
            val inverseHeight = ref?.inverseSafeHeight ?: 1f
            entry.instances.addInstance { layout ->
                set(layout.rect, left - currentViewportHalfWidth, currentViewportHalfHeight - top,
                    right - currentViewportHalfWidth, currentViewportHalfHeight - bottom)
                set(layout.uv, (sx + sw * ((left - x0) / (x1 - x0))) * inverseWidth,
                    (sy + sh * ((top - y0) / (y1 - y0))) * inverseHeight,
                    (sx + sw * ((right - x0) / (x1 - x0))) * inverseWidth,
                    (sy + sh * ((bottom - y0) / (y1 - y0))) * inverseHeight)
                set(layout.mask, masks.coordinate(i, 8), masks.coordinate(i, 9), 0f, 0f)
            }
            added = true
        }
        if (added) markOrderingBarrier()
        return added
    }

    private fun addTextureBatch(
        scene: Node,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawTextureBatch,
    ): Boolean {
        val ref = command.texture
        val paint = command.paint
        if (command.state.transform !== KoolCanvasTransform.Identity || paint.textureEffect != null ||
            paint.color != KoolCanvasColor.White || paint.alphaMultiplier != 1f ||
            (paint.blendMode != KoolCanvasBlendMode.SourceOver && paint.blendMode != KoolCanvasBlendMode.Source) ||
            resolveFrameTexture(ref.id) != null
        ) {
            var added = false
            command.quads.forEachDraw(ref, paint, command.state) {
                added = (addFrameTexture(scene, viewport, it) ?: addTexture(scene, viewport, it)) || added
            }
            return added
        }
        rememberFrozenPixelMeshIdentity(ref)
        val orderingBarrier = paint.requiresTextureOrderingBarrier(ref)
        val entry = ensureInstancedTextureMesh(scene, ref.id, paint.textureFilter,
            resolveTexture(ref, paint.textureFilter), paint.renderBlend, ref.premultipliedAlpha,
            ordered = frameProjectionDepth == 0 && orderingBarrier)
        val clip = command.state.clip
        var added = false
        for (quad in 0 until command.quads.size) {
            val q = command.quads
            val originalLeft = q.coordinate(quad, 4)
            val originalTop = q.coordinate(quad, 5)
            val originalRight = q.coordinate(quad, 6)
            val originalBottom = q.coordinate(quad, 7)
            val left = if (clip == null) originalLeft else maxOf(originalLeft, clip.boundsLeft)
            val top = if (clip == null) originalTop else maxOf(originalTop, clip.boundsTop)
            val right = if (clip == null) originalRight else minOf(originalRight, clip.boundsRight)
            val bottom = if (clip == null) originalBottom else minOf(originalBottom, clip.boundsBottom)
            if (right <= left || bottom <= top) continue
            val sourceLeft = q.coordinate(quad, 0)
            val sourceTop = q.coordinate(quad, 1)
            val sourceWidth = q.coordinate(quad, 2) - sourceLeft
            val sourceHeight = q.coordinate(quad, 3) - sourceTop
            val width = originalRight - originalLeft
            val height = originalBottom - originalTop
            entry.instances.addTextureInstance(
                left - currentViewportHalfWidth, currentViewportHalfHeight - top,
                right - currentViewportHalfWidth, currentViewportHalfHeight - bottom,
                (sourceLeft + sourceWidth * ((left - originalLeft) / width)) * ref.inverseSafeWidth,
                (sourceTop + sourceHeight * ((top - originalTop) / height)) * ref.inverseSafeHeight,
                (sourceLeft + sourceWidth * ((right - originalLeft) / width)) * ref.inverseSafeWidth,
                (sourceTop + sourceHeight * ((bottom - originalTop) / height)) * ref.inverseSafeHeight,
            )
            added = true
        }
        if (added && orderingBarrier) markOrderingBarrier()
        return added
    }

    private fun addTexture(
        scene: Node,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawTexture,
    ): Boolean {
        KoolCanvasCommandTiming.beginCommand("tex:" + command.texture.id.value)
        val paint = command.paint
        if (paint.isRenderNoOp) return false
        if (command.source.hasZeroArea || command.destination.hasZeroArea) return false
        val textureRef = command.texture
        rememberFrozenPixelMeshIdentity(textureRef)
        val textureFilter = paint.textureFilter
        val defaultTexturePaint = paint.isDefaultTexturePaint
        if (command.canUseUnorderedDefaultInstancedTextureQuad(defaultTexturePaint)) {
            KoolCanvasCommandTiming.step("instancedUnordered")
            val texture = resolveTexture(textureRef, textureFilter)
            val entry = ensureInstancedTextureMesh(
                scene,
                textureRef.id,
                textureFilter,
                texture,
                CanvasRenderBlend.Alpha,
                premultipliedAlpha = false,
                ordered = false,
            )
            return addInstancedTextureQuad(entry.instances, viewport, command)
        }
        KoolCanvasCommandTiming.step("resolveTexture")
        val texture = resolveTexture(textureRef, textureFilter)
        val effect = paint.textureEffect
        val renderBlend = paint.renderBlend
        val orderingBarrier = paint.requiresTextureOrderingBarrier(textureRef)
        val ordered = frameProjectionDepth == 0 && orderingBarrier
        if (effect == null && command.canUseInstancedTextureQuad(defaultTexturePaint)) {
            KoolCanvasCommandTiming.step("instanced")
            val entry = ensureInstancedTextureMesh(
                scene,
                textureRef.id,
                textureFilter,
                texture,
                renderBlend,
                textureRef.premultipliedAlpha,
                ordered,
            )
            return addInstancedTextureQuad(entry.instances, viewport, command).also { added ->
                if (added && orderingBarrier) {
                    markOrderingBarrier()
                }
            }
        }
        if (effect == null && command.canUseAffineInstancedTextureQuad(defaultTexturePaint)) {
            KoolCanvasCommandTiming.step("instancedAffine")
            val entry = ensureAffineInstancedTextureMesh(
                scene,
                textureRef.id,
                textureFilter,
                texture,
                renderBlend,
                textureRef.premultipliedAlpha,
                ordered,
            )
            return addAffineInstancedTextureQuad(entry.instances, viewport, command).also { added ->
                if (added && orderingBarrier) {
                    markOrderingBarrier()
                }
            }
        }
        KoolCanvasCommandTiming.step("geometry:mesh")
        val geometry = when (effect) {
            is KoolCanvasTextureEffect.TeamColor ->
                ensureTeamColorTextureMesh(
                    scene,
                    textureRef.id,
                    textureFilter,
                    texture,
                    effect,
                    renderBlend,
                    ordered,
                ).mesh.geometry

            is KoolCanvasTextureEffect.Displacement -> {
                val screenBase = resolveShaderTexture(scene, effect.screenBase, textureFilter)
                ensureDisplacementTextureMesh(
                    scene = scene,
                    textureId = textureRef.id,
                    textureFilter = textureFilter,
                    displacementMap = texture,
                    screenBase = screenBase,
                    effect = effect,
                    renderBlend = renderBlend,
                    viewport = viewport,
                    ordered = ordered,
                ).mesh.geometry
            }

            null -> ensureTextureMesh(
                scene,
                textureRef.id,
                textureFilter,
                texture,
                renderBlend,
                textureRef.premultipliedAlpha,
                ordered,
            ).mesh.geometry
        }
        return addTextureQuad(geometry, viewport, command).also { added ->
            if (added && orderingBarrier) {
                markOrderingBarrier()
            }
        }
    }

    private fun addUnorderedDefaultInstancedTextureRun(
        scene: Node,
        viewport: KoolCanvasViewport,
        commands: List<KoolCanvasCommand>,
        startIndex: Int,
        firstCommand: KoolCanvasCommand.DrawTexture,
    ): Int {
        if (!firstCommand.canUseUnorderedDefaultInstancedTextureRun()) return 0
        var runEntryCount = 0
        var commandIndex = startIndex
        while (commandIndex < commands.size) {
            val command = commands[commandIndex] as? KoolCanvasCommand.DrawTexture ?: break
            if (!adaptiveVisuals.shouldDraw(command)) break
            if (!command.canUseUnorderedDefaultInstancedTextureRun()) break

            val textureRef = command.texture
            rememberFrozenPixelMeshIdentity(textureRef)
            val textureId = textureRef.id
            val textureFilter = command.paint.textureFilter
            // The run carries the blend mode, not just the texture. `ensureInstancedTextureMesh` already
            // keys its mesh on `renderBlend` (and `CanvasRenderBlend.Source` disables blending), so an
            // opaque terrain tile drawn under the direct-blit `Source` fast path gets its own instanced
            // layer instead of being forced through the alpha-blended one.
            val renderBlend = command.paint.renderBlend
            var entry: InstancedTextureMeshEntry? = null
            for (entryIndex in 0 until runEntryCount) {
                if (textureRunTextureIds[entryIndex] == textureId &&
                    textureRunFilters[entryIndex] == textureFilter &&
                    textureRunBlends[entryIndex] == renderBlend
                ) {
                    entry = textureRunEntries[entryIndex]
                    break
                }
            }
            if (entry == null) {
                if (runEntryCount >= TEXTURE_RUN_CACHE_SIZE) break
                if (resolveFrameTexture(textureId) != null) break
                val texture = resolveTexture(textureRef, textureFilter)
                entry = ensureInstancedTextureMesh(
                    scene,
                    textureId,
                    textureFilter,
                    texture,
                    renderBlend,
                    premultipliedAlpha = false,
                    ordered = false,
                )
                textureRunTextureIds[runEntryCount] = textureId
                textureRunFilters[runEntryCount] = textureFilter
                textureRunBlends[runEntryCount] = renderBlend
                textureRunEntries[runEntryCount] = entry
                runEntryCount++
            }
            addInstancedTextureQuad(entry.instances, viewport, command)
            commandIndex++
        }
        for (entryIndex in 0 until runEntryCount) {
            textureRunTextureIds[entryIndex] = null
            textureRunFilters[entryIndex] = null
            textureRunBlends[entryIndex] = null
            textureRunEntries[entryIndex] = null
        }
        return commandIndex - startIndex
    }

    private fun KoolCanvasCommand.DrawTexture.canUseUnorderedDefaultInstancedTextureRun(): Boolean {
        val paint = paint
        val texture = texture
        return paint.canJoinInstancedTextureRun() &&
                state.renderTarget == null &&
                // A clip only changes the result when it actually cuts the quad. The instanced run cannot
                // express per-command clipping, so accept the command when its destination already lies
                // inside the clip: the clip is then a no-op for it. Measured on a low-zoom pan, `clip`
                // rejected ~2.6 M texture commands and `eligible` was only 0.18% of all of them.
                state.clip.isRedundantFor(destination) &&
                state.transform === KoolCanvasTransform.Identity &&
                !source.hasZeroArea &&
                !destination.hasZeroArea &&
                !texture.hasAlpha &&
                !texture.requiresOrderedAlpha &&
                !texture.premultipliedAlpha
    }

    /** True when `clip` cuts nothing off [rect], so a renderer that ignores it still matches. */
    private fun KoolCanvasRect?.isRedundantFor(rect: KoolCanvasRect): Boolean {
        if (this == null) return true
        if (!INSTANCED_RUN_CLIP_WHEN_INSIDE) return false
        if (rect.isEmpty || isEmpty) return false
        return rect.boundsLeft >= boundsLeft && rect.boundsTop >= boundsTop &&
                rect.boundsRight <= boundsRight && rect.boundsBottom <= boundsBottom
    }

    /**
     * Only paint fields unused by a texture draw may differ from the canonical default.
     * The instanced shader has no tint input: accepting a black or translucent paint would discard
     * its RGB / alpha and incorrectly render fog masks as untinted full-opacity textures.
     */
    private fun KoolCanvasPaint.canJoinInstancedTextureRun(): Boolean {
        if (!INSTANCED_RUN_RELAXED_PAINT) return isCanonicalDefaultTexturePaint
        if (color != KoolCanvasColor.White) return false
        // `Source` is deliberately excluded. It is what `withDirectBlitTextureBlend` assigns to an opaque
        // texture drawn under an active direct blit, and the run builds an Alpha-blend instanced mesh, so
        // accepting it would render those draws as alpha blends. Measured after relaxing the colour
        // requirement, `Source`-blend paints are the remaining 80% of rejections (paint 16.28 M of
        // 17.89 M texture commands), which is why this relaxation alone does not grow run coverage:
        // supporting them needs a second instanced mesh layer for the `Source` pipeline.
        return textureEffect == null &&
                (blendMode == KoolCanvasBlendMode.SourceOver || blendMode == KoolCanvasBlendMode.Source) &&
                alphaMultiplier == 1f
    }

    private fun KoolCanvasCommand.DrawTexture.canUseInstancedTextureQuad(defaultTexturePaint: Boolean): Boolean =
        defaultTexturePaint &&
                state.clip == null &&
                state.transform === KoolCanvasTransform.Identity

    private fun KoolCanvasCommand.DrawTexture.canUseAffineInstancedTextureQuad(defaultTexturePaint: Boolean): Boolean =
        defaultTexturePaint &&
                state.clip == null &&
                state.transform !== KoolCanvasTransform.Identity

    private fun KoolCanvasCommand.DrawTexture.canUseUnorderedDefaultInstancedTextureQuad(
        defaultTexturePaint: Boolean,
    ): Boolean =
        defaultTexturePaint &&
                state.clip == null &&
                state.transform === KoolCanvasTransform.Identity &&
                !texture.hasAlpha &&
                !texture.requiresOrderedAlpha &&
                !texture.premultipliedAlpha

    private val KoolCanvasPaint.isDefaultTexturePaint: Boolean
        get() =
            this === KoolCanvasPaint.Default ||
                    this === KoolCanvasPaint.DefaultNearest ||
                    this == KoolCanvasPaint.Default ||
                    this == KoolCanvasPaint.DefaultNearest

    private val KoolCanvasPaint.isCanonicalDefaultTexturePaint: Boolean
        get() =
            this === KoolCanvasPaint.Default ||
                    this === KoolCanvasPaint.DefaultNearest

    private fun addInstancedTextureQuad(
        instances: MeshInstanceList<KoolCanvasTextureInstanceLayout>,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawTexture,
    ): Boolean {
        val destination = command.destination
        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val left = destination.left - viewportHalfWidth
        val top = viewportHalfHeight - destination.top
        val right = destination.right - viewportHalfWidth
        val bottom = viewportHalfHeight - destination.bottom
        if (command.sourceIsFullTexture) {
            instances.addDefaultTextureInstance(left, top, right, bottom)
        } else {
            instances.addTextureInstance(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                u0 = command.sourceU0,
                v0 = command.sourceV0,
                u1 = command.sourceU1,
                v1 = command.sourceV1,
            )
        }
        return true
    }

    private fun addAffineInstancedTextureQuad(
        instances: MeshInstanceList<KoolCanvasAffineTextureInstanceLayout>,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawTexture,
    ): Boolean {
        val destination = command.destination

        val u0: Float
        val v0: Float
        val u1: Float
        val v1: Float
        if (command.sourceIsFullTexture) {
            u0 = 0f
            v0 = 0f
            u1 = 1f
            v1 = 1f
        } else {
            u0 = command.sourceU0
            v0 = command.sourceV0
            u1 = command.sourceU1
            v1 = command.sourceV1
        }

        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val transform = command.state.transform
        val p0x = transform.scaleX * destination.left + transform.skewX * destination.top + transform.translateX
        val p0y = transform.skewY * destination.left + transform.scaleY * destination.top + transform.translateY
        val p1x = transform.scaleX * destination.right + transform.skewX * destination.top + transform.translateX
        val p1y = transform.skewY * destination.right + transform.scaleY * destination.top + transform.translateY
        val p3x = transform.scaleX * destination.left + transform.skewX * destination.bottom + transform.translateX
        val p3y = transform.skewY * destination.left + transform.scaleY * destination.bottom + transform.translateY

        instances.addAffineTextureInstance(
            originX = p0x - viewportHalfWidth,
            originY = viewportHalfHeight - p0y,
            axisXx = p1x - p0x,
            axisXy = -(p1y - p0y),
            axisYx = p3x - p0x,
            axisYy = -(p3y - p0y),
            u0 = u0,
            v0 = v0,
            u1 = u1,
            v1 = v1,
        )
        return true
    }

    private fun MeshInstanceList<KoolCanvasTextureInstanceLayout>.addDefaultTextureInstance(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val data = instanceData
        if (data.limit >= data.capacity) {
            addInstances(1) { buffer ->
                buffer.writeTextureInstance(left, top, right, bottom, 0f, 0f, 1f, 1f)
            }
            return
        }
        data.writeTextureInstance(left, top, right, bottom, 0f, 0f, 1f, 1f)
    }

    private fun MeshInstanceList<KoolCanvasTextureInstanceLayout>.addTextureInstance(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
    ) {
        val data = instanceData
        if (data.limit >= data.capacity) {
            addInstances(1) { buffer ->
                buffer.writeTextureInstance(left, top, right, bottom, u0, v0, u1, v1)
            }
            return
        }
        data.writeTextureInstance(left, top, right, bottom, u0, v0, u1, v1)
    }

    private fun de.fabmax.kool.util.StructBuffer<KoolCanvasTextureInstanceLayout>.writeTextureInstance(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
    ) {
        val index = limit
        limit = index + 1
        val offset = index * strideBytes
        val rectOffset = offset + KoolCanvasTextureInstanceLayout.rect.byteOffset
        buffer.setFloat32(rectOffset, left)
        buffer.setFloat32(rectOffset + 4, top)
        buffer.setFloat32(rectOffset + 8, right)
        buffer.setFloat32(rectOffset + 12, bottom)
        val uvOffset = offset + KoolCanvasTextureInstanceLayout.uv.byteOffset
        buffer.setFloat32(uvOffset, u0)
        buffer.setFloat32(uvOffset + 4, v0)
        buffer.setFloat32(uvOffset + 8, u1)
        buffer.setFloat32(uvOffset + 12, v1)
    }

    private fun MeshInstanceList<KoolCanvasAffineTextureInstanceLayout>.addAffineTextureInstance(
        originX: Float,
        originY: Float,
        axisXx: Float,
        axisXy: Float,
        axisYx: Float,
        axisYy: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
    ) {
        val data = instanceData
        if (data.limit >= data.capacity) {
            addInstances(1) { buffer ->
                buffer.writeAffineTextureInstance(originX, originY, axisXx, axisXy, axisYx, axisYy, u0, v0, u1, v1)
            }
            return
        }
        data.writeAffineTextureInstance(originX, originY, axisXx, axisXy, axisYx, axisYy, u0, v0, u1, v1)
    }

    private fun de.fabmax.kool.util.StructBuffer<KoolCanvasAffineTextureInstanceLayout>.writeAffineTextureInstance(
        originX: Float,
        originY: Float,
        axisXx: Float,
        axisXy: Float,
        axisYx: Float,
        axisYy: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
    ) {
        val index = limit
        limit = index + 1
        val offset = index * strideBytes
        val originAxisXOffset = offset + KoolCanvasAffineTextureInstanceLayout.originAxisX.byteOffset
        buffer.setFloat32(originAxisXOffset, originX)
        buffer.setFloat32(originAxisXOffset + 4, originY)
        buffer.setFloat32(originAxisXOffset + 8, axisXx)
        buffer.setFloat32(originAxisXOffset + 12, axisXy)
        val axisYOffset = offset + KoolCanvasAffineTextureInstanceLayout.axisY.byteOffset
        buffer.setFloat32(axisYOffset, axisYx)
        buffer.setFloat32(axisYOffset + 4, axisYy)
        buffer.setFloat32(axisYOffset + 8, 0f)
        buffer.setFloat32(axisYOffset + 12, 0f)
        val uvOffset = offset + KoolCanvasAffineTextureInstanceLayout.uv.byteOffset
        buffer.setFloat32(uvOffset, u0)
        buffer.setFloat32(uvOffset + 4, v0)
        buffer.setFloat32(uvOffset + 8, u1)
        buffer.setFloat32(uvOffset + 12, v1)
    }

    private fun resolveShaderTexture(
        scene: Node,
        texture: KoolCanvasTextureRef,
        filter: KoolCanvasTextureFilter,
    ): Texture2d {
        return resolveTexture(texture, filter)
    }

    private fun resolveTexture(
        texture: KoolCanvasTextureRef,
        filter: KoolCanvasTextureFilter,
    ): Texture2d {
        val revisionStore = textureRevisionStore
        if (revisionStore != null) {
            val revision = currentTextureRevision
            if (resolvedTextureCacheRevision != revision) {
                clearResolvedTextureCache(revision)
            }
        }
        val cachedTexture = lastResolvedTexture
        if (cachedTexture != null &&
            lastResolvedTextureRef == texture &&
            lastResolvedTextureFilter == filter
        ) {
            return cachedTexture
        }
        val alternateTexture = alternateResolvedTexture
        if (alternateTexture != null &&
            alternateResolvedTextureRef == texture &&
            alternateResolvedTextureFilter == filter
        ) {
            alternateResolvedTextureRef = lastResolvedTextureRef
            alternateResolvedTextureFilter = lastResolvedTextureFilter
            alternateResolvedTexture = cachedTexture
            lastResolvedTextureRef = texture
            lastResolvedTextureFilter = filter
            lastResolvedTexture = alternateTexture
            return alternateTexture
        }

        for (index in 0 until resolvedTextureCacheCount) {
            if (resolvedTextureCacheRefs[index] == texture &&
                resolvedTextureCacheFilters[index] == filter
            ) {
                return resolvedTextureCacheTextures[index]!!.also { resolved ->
                    alternateResolvedTextureRef = lastResolvedTextureRef
                    alternateResolvedTextureFilter = lastResolvedTextureFilter
                    alternateResolvedTexture = cachedTexture
                    lastResolvedTextureRef = texture
                    lastResolvedTextureFilter = filter
                    lastResolvedTexture = resolved
                }
            }
        }

        val resolved = textureStore.resolve(texture, filter)
        val cacheSlot = nextResolvedTextureCacheSlot
        resolvedTextureCacheRefs[cacheSlot] = texture
        resolvedTextureCacheFilters[cacheSlot] = filter
        resolvedTextureCacheTextures[cacheSlot] = resolved
        if (resolvedTextureCacheCount < RESOLVED_TEXTURE_CACHE_SIZE) {
            resolvedTextureCacheCount++
        }
        nextResolvedTextureCacheSlot = (cacheSlot + 1) % RESOLVED_TEXTURE_CACHE_SIZE
        alternateResolvedTextureRef = lastResolvedTextureRef
        alternateResolvedTextureFilter = lastResolvedTextureFilter
        alternateResolvedTexture = cachedTexture
        lastResolvedTextureRef = texture
        lastResolvedTextureFilter = filter
        lastResolvedTexture = resolved
        return resolved
    }

    private fun clearResolvedTextureCache(revision: Int = resolvedTextureCacheRevision) {
        for (index in 0 until RESOLVED_TEXTURE_CACHE_SIZE) {
            resolvedTextureCacheRefs[index] = null
            resolvedTextureCacheFilters[index] = null
            resolvedTextureCacheTextures[index] = null
        }
        nextResolvedTextureCacheSlot = 0
        resolvedTextureCacheCount = 0
        resolvedTextureCacheRevision = revision
        lastResolvedTextureRef = null
        lastResolvedTextureFilter = null
        lastResolvedTexture = null
        alternateResolvedTextureRef = null
        alternateResolvedTextureFilter = null
        alternateResolvedTexture = null
    }

    private fun resolveFrameTexture(textureId: KoolCanvasTextureId): KoolCanvasFrame? {
        if (lastFrameTextureId == textureId) {
            return lastFrameTexture
        }
        val revisionStore = frameTextureRevisionStore
        if (revisionStore != null) {
            val revision = currentFrameTextureRevision
            if (missingFrameTextureCacheRevision != revision) {
                clearMissingFrameTextureCache(revision)
            } else if (isKnownMissingFrameTexture(textureId)) {
                lastFrameTextureId = textureId
                lastFrameTexture = null
                return null
            }
        }
        for (index in 0 until FRAME_TEXTURE_CACHE_SIZE) {
            if (frameTextureCacheIds[index] == textureId) {
                return frameTextureCacheFrames[index].also { frame ->
                    lastFrameTextureId = textureId
                    lastFrameTexture = frame
                }
            }
        }
        val frame = textureStore.frame(textureId)
        val cacheSlot = nextFrameTextureCacheSlot
        frameTextureCacheIds[cacheSlot] = textureId
        frameTextureCacheFrames[cacheSlot] = frame
        nextFrameTextureCacheSlot = (cacheSlot + 1) % FRAME_TEXTURE_CACHE_SIZE
        lastFrameTextureId = textureId
        lastFrameTexture = frame
        if (frame == null && revisionStore != null) {
            rememberMissingFrameTexture(textureId)
        }
        return frame
    }

    private fun isKnownMissingFrameTexture(textureId: KoolCanvasTextureId): Boolean {
        val lastMissing = lastMissingFrameTextureId
        if (lastMissing == textureId) {
            return true
        }
        val alternateMissing = alternateMissingFrameTextureId
        if (alternateMissing == textureId) {
            alternateMissingFrameTextureId = lastMissing
            lastMissingFrameTextureId = textureId
            return true
        }
        for (index in 0 until missingFrameTextureCacheCount) {
            if (missingFrameTextureCacheIds[index] == textureId) {
                alternateMissingFrameTextureId = lastMissing
                lastMissingFrameTextureId = textureId
                return true
            }
        }
        return false
    }

    private fun rememberMissingFrameTexture(textureId: KoolCanvasTextureId) {
        if (lastMissingFrameTextureId == textureId) {
            return
        }
        alternateMissingFrameTextureId = lastMissingFrameTextureId
        lastMissingFrameTextureId = textureId
        val cacheSlot = nextMissingFrameTextureCacheSlot
        missingFrameTextureCacheIds[cacheSlot] = textureId
        if (missingFrameTextureCacheCount < FRAME_TEXTURE_MISS_CACHE_SIZE) {
            missingFrameTextureCacheCount++
        }
        nextMissingFrameTextureCacheSlot = (cacheSlot + 1) % FRAME_TEXTURE_MISS_CACHE_SIZE
    }

    private fun clearMissingFrameTextureCache(revision: Int) {
        for (index in 0 until FRAME_TEXTURE_MISS_CACHE_SIZE) {
            missingFrameTextureCacheIds[index] = null
        }
        nextMissingFrameTextureCacheSlot = 0
        missingFrameTextureCacheCount = 0
        missingFrameTextureCacheRevision = revision
        lastMissingFrameTextureId = null
        alternateMissingFrameTextureId = null
    }

    private fun clearFrameTextureCache() {
        for (index in 0 until FRAME_TEXTURE_CACHE_SIZE) {
            frameTextureCacheIds[index] = null
            frameTextureCacheFrames[index] = null
        }
        nextFrameTextureCacheSlot = 0
        lastFrameTextureId = null
        lastFrameTexture = null
    }

    private fun addFrameTexture(
        scene: Node,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawTexture,
    ): Boolean? {
        if (activeFrameProjectionBatchId != null &&
            projectedFrameTextureExpansions >= MAX_PROJECTED_FRAME_TEXTURE_EXPANSIONS
        ) {
            return false
        }
        val sourceFrame = resolveFrameTexture(command.texture.id) ?: return null
        if (command.paint.isRenderNoOp || command.source.hasZeroArea || command.destination.hasZeroArea) {
            return false
        }
        if (frameProjectionDepth >= MAX_FRAME_TEXTURE_PROJECTION_DEPTH) {
            return false
        }
        if (command.texture.id in renderingFrameTextureIds) {
            return false
        }

        if (!renderingFrameTextureIds.add(command.texture.id)) {
            return false
        }
        val previousProjectionBatchId = activeFrameProjectionBatchId
        val ownsProjectionBatch = previousProjectionBatchId == null
        if (ownsProjectionBatch) {
            activeFrameProjectionBatchId = nextFrameProjectionBatchId++
        }
        val previousProjectedOrderingSegmentAdvances = projectedOrderingSegmentAdvances
        val previousProjectedFrameTextureExpansions = projectedFrameTextureExpansions
        if (ownsProjectionBatch) {
            projectedOrderingSegmentAdvances = 0
            projectedFrameTextureExpansions = 0
        } else {
            projectedFrameTextureExpansions++
        }
        try {
            frameProjectionDepth++
            return addProjectedFrameTextureCommands(scene, viewport, command, sourceFrame)
        } finally {
            frameProjectionDepth--
            if (ownsProjectionBatch) {
                activeFrameProjectionBatchId = previousProjectionBatchId
                projectedOrderingSegmentAdvances = previousProjectedOrderingSegmentAdvances
                projectedFrameTextureExpansions = previousProjectedFrameTextureExpansions
            }
            renderingFrameTextureIds.remove(command.texture.id)
        }
    }

    private fun addProjectedFrameTextureCommands(
        scene: Node,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawTexture,
        sourceFrame: KoolCanvasFrame,
    ): Boolean {
        val frameClip = command.frameSourceClip(sourceFrame) ?: return false
        val clippedDestination = command.source.mapSubRectTo(command.destination, frameClip)
        val placementTransform = command.source.toDestinationTransform(command.destination)
        var addedPrimitiveGeometry = false
        sourceFrame.commands.forEach { nestedCommand ->
            when (nestedCommand) {
                is KoolCanvasCommand.DrawFogBatch -> {
                    if (nestedCommand.state.renderTarget != null) return@forEach
                    val projectedState = nestedCommand.state.projected(command.state, placementTransform, frameClip)
                        ?: return@forEach
                    addedPrimitiveGeometry = addFogBatch(scene, viewport, nestedCommand.copy(state = projectedState,
                        paint = nestedCommand.paint.withOuterPaint(command.paint))) || addedPrimitiveGeometry
                }
                is KoolCanvasCommand.DrawRectBatch -> {
                    if (nestedCommand.state.renderTarget != null) return@forEach
                    val projectedState = nestedCommand.state.projected(command.state, placementTransform, frameClip)
                        ?: return@forEach
                    addedPrimitiveGeometry = addRectBatch(scene, viewport,
                        nestedCommand.copy(paint = nestedCommand.paint.withOuterPaint(command.paint),
                            state = projectedState)) || addedPrimitiveGeometry
                }
                is KoolCanvasCommand.DrawTextureBatch -> {
                    if (nestedCommand.state.renderTarget != null) return@forEach
                    val projectedState = nestedCommand.state.projected(command.state, placementTransform, frameClip)
                        ?: return@forEach
                    val projectedPaint = nestedCommand.paint.withOuterPaint(command.paint)
                    addedPrimitiveGeometry = addTextureBatch(scene, viewport,
                        nestedCommand.copy(paint = projectedPaint, state = projectedState)) || addedPrimitiveGeometry
                }
                is KoolCanvasCommand.Clear -> {
                    if (nestedCommand.renderTarget != null) return@forEach
                    addedPrimitiveGeometry = addRect(
                        scene,
                        viewport,
                        KoolCanvasCommand.DrawRect(
                            rect = clippedDestination,
                            paint = KoolCanvasPaint(
                                color = nestedCommand.color,
                                blendMode = nestedCommand.blendMode,
                            ).withOuterPaint(command.paint),
                            state = command.state,
                        ),
                    ) || addedPrimitiveGeometry
                }

                is KoolCanvasCommand.DrawTexture -> {
                    if (nestedCommand.state.renderTarget != null) return@forEach
                    val projectedState =
                        nestedCommand.state.projected(command.state, placementTransform, frameClip) ?: return@forEach
                    val projected = nestedCommand.copy(
                        state = projectedState,
                        paint = nestedCommand.paint.withOuterPaint(command.paint),
                    )
                    val framePrimitiveGeometry = addFrameTexture(scene, viewport, projected)
                    if (framePrimitiveGeometry == null) {
                        addedPrimitiveGeometry = (projectedSpriteAtlas && addAtlasSprite(scene, viewport, projected) ||
                            addTexture(scene, viewport, projected)) || addedPrimitiveGeometry
                    } else {
                        addedPrimitiveGeometry = framePrimitiveGeometry || addedPrimitiveGeometry
                    }
                }

                is KoolCanvasCommand.DrawTextureRepeat -> {
                    if (nestedCommand.state.renderTarget != null) return@forEach
                    val projectedState =
                        nestedCommand.state.projected(command.state, placementTransform, frameClip) ?: return@forEach
                    val projectedPaint = nestedCommand.paint.withOuterPaint(command.paint)
                    // A repeat carries one source rect drawn N times side by side, so it expands into N
                    // single-tile draws here. Each keeps the *unstretched* source rect, which is what makes
                    // this differ from the round-24 merge that stretched one rect and tore the map.
                    val tileWidth = (nestedCommand.destination.width / nestedCommand.repeat).coerceAtLeast(0f)
                    for (tile in 0 until nestedCommand.repeat) {
                        val tileDestination = KoolCanvasRect(
                            left = nestedCommand.destination.left + tileWidth * tile,
                            top = nestedCommand.destination.top,
                            right = if (tile == nestedCommand.repeat - 1) nestedCommand.destination.right
                            else nestedCommand.destination.left + tileWidth * (tile + 1),
                            bottom = nestedCommand.destination.bottom,
                        )
                        val projected = KoolCanvasCommand.DrawTexture(
                            texture = nestedCommand.texture,
                            source = nestedCommand.source,
                            destination = tileDestination,
                            paint = projectedPaint,
                            state = projectedState,
                        )
                        val framePrimitiveGeometry = addFrameTexture(scene, viewport, projected)
                        if (framePrimitiveGeometry == null) {
                            addedPrimitiveGeometry = (projectedSpriteAtlas && addAtlasSprite(scene, viewport, projected) ||
                                addTexture(scene, viewport, projected)) || addedPrimitiveGeometry
                        } else {
                            addedPrimitiveGeometry = framePrimitiveGeometry || addedPrimitiveGeometry
                        }
                    }
                }

                is KoolCanvasCommand.DrawRect -> {
                    if (nestedCommand.state.renderTarget != null) return@forEach
                    val projectedState =
                        nestedCommand.state.projected(command.state, placementTransform, frameClip) ?: return@forEach
                    addedPrimitiveGeometry = addRect(
                        scene,
                        viewport,
                        nestedCommand.copy(
                            state = projectedState,
                            paint = nestedCommand.paint.withOuterPaint(command.paint),
                        ),
                    ) || addedPrimitiveGeometry
                }

                is KoolCanvasCommand.DrawLine -> {
                    if (nestedCommand.state.renderTarget != null) return@forEach
                    val projectedState =
                        nestedCommand.state.projected(command.state, placementTransform, frameClip) ?: return@forEach
                    addedPrimitiveGeometry = addLine(
                        scene,
                        viewport,
                        nestedCommand.copy(
                            state = projectedState,
                            paint = nestedCommand.paint.withOuterPaint(command.paint),
                        ),
                    ) || addedPrimitiveGeometry
                }

                is KoolCanvasCommand.DrawCircle -> {
                    if (nestedCommand.state.renderTarget != null) return@forEach
                    val projectedState =
                        nestedCommand.state.projected(command.state, placementTransform, frameClip) ?: return@forEach
                    addedPrimitiveGeometry = addCircle(
                        scene,
                        viewport,
                        nestedCommand.copy(
                            state = projectedState,
                            paint = nestedCommand.paint.withOuterPaint(command.paint),
                        ),
                    ) || addedPrimitiveGeometry
                }

                is KoolCanvasCommand.DrawText -> {
                    if (nestedCommand.state.renderTarget != null) return@forEach
                    val projectedState =
                        nestedCommand.state.projected(command.state, placementTransform, frameClip) ?: return@forEach
                    addText(
                        scene,
                        viewport,
                        nestedCommand.copy(
                            state = projectedState,
                            paint = nestedCommand.paint.withOuterPaint(command.paint),
                        ),
                    )
                    addedPrimitiveGeometry = true
                }
            }
        }
        return addedPrimitiveGeometry
    }

    private fun addTextureQuad(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalTexCoordColor>,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawTexture,
    ): Boolean {
        val destination = command.destination
        val source = command.source
        if (destination.hasZeroArea || source.hasZeroArea) return false

        var destinationLeft = destination.left
        var destinationTop = destination.top
        var destinationRight = destination.right
        var destinationBottom = destination.bottom
        var sourceLeft = source.left
        var sourceTop = source.top
        var sourceRight = source.right
        var sourceBottom = source.bottom
        val clip = command.state.clipForGeometry()
        if (clip != null) {
            val clippedBoundsLeft = maxOf(destination.boundsLeft, clip.boundsLeft)
            val clippedBoundsTop = maxOf(destination.boundsTop, clip.boundsTop)
            val clippedBoundsRight = minOf(destination.boundsRight, clip.boundsRight)
            val clippedBoundsBottom = minOf(destination.boundsBottom, clip.boundsBottom)
            if (clippedBoundsRight <= clippedBoundsLeft || clippedBoundsBottom <= clippedBoundsTop) {
                return false
            }

            destinationLeft = if (destination.width >= 0f) clippedBoundsLeft else clippedBoundsRight
            destinationTop = if (destination.height >= 0f) clippedBoundsTop else clippedBoundsBottom
            destinationRight = if (destination.width >= 0f) clippedBoundsRight else clippedBoundsLeft
            destinationBottom = if (destination.height >= 0f) clippedBoundsBottom else clippedBoundsTop

            val leftRatio = (destinationLeft - destination.left) / destination.width
            val topRatio = (destinationTop - destination.top) / destination.height
            val rightRatio = (destinationRight - destination.left) / destination.width
            val bottomRatio = (destinationBottom - destination.top) / destination.height
            sourceLeft = source.left + source.width * leftRatio
            sourceTop = source.top + source.height * topRatio
            sourceRight = source.left + source.width * rightRatio
            sourceBottom = source.top + source.height * bottomRatio
        }

        val u0: Float
        val v0: Float
        val u1: Float
        val v1: Float
        if (sourceLeft == 0f &&
            sourceTop == 0f &&
            sourceRight == command.texture.widthFloat &&
            sourceBottom == command.texture.heightFloat
        ) {
            u0 = 0f
            v0 = 0f
            u1 = 1f
            v1 = 1f
        } else {
            u0 = sourceLeft * command.texture.inverseSafeWidth
            v0 = sourceTop * command.texture.inverseSafeHeight
            u1 = sourceRight * command.texture.inverseSafeWidth
            v1 = sourceBottom * command.texture.inverseSafeHeight
        }
        val color = command.paint.toRenderColor()
        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val transform = command.state.transform
        val transformIsIdentity = transform === KoolCanvasTransform.Identity

        val i0 = geometry.addCanvasTextureVertex(
            x = destinationLeft,
            y = destinationTop,
            u = u0,
            v = v0,
            viewportHalfWidth = viewportHalfWidth,
            viewportHalfHeight = viewportHalfHeight,
            transform = transform,
            transformIsIdentity = transformIsIdentity,
            color = color,
        )
        val i1 = geometry.addCanvasTextureVertex(
            x = destinationRight,
            y = destinationTop,
            u = u1,
            v = v0,
            viewportHalfWidth = viewportHalfWidth,
            viewportHalfHeight = viewportHalfHeight,
            transform = transform,
            transformIsIdentity = transformIsIdentity,
            color = color,
        )
        val i2 = geometry.addCanvasTextureVertex(
            x = destinationRight,
            y = destinationBottom,
            u = u1,
            v = v1,
            viewportHalfWidth = viewportHalfWidth,
            viewportHalfHeight = viewportHalfHeight,
            transform = transform,
            transformIsIdentity = transformIsIdentity,
            color = color,
        )
        val i3 = geometry.addCanvasTextureVertex(
            x = destinationLeft,
            y = destinationBottom,
            u = u0,
            v = v1,
            viewportHalfWidth = viewportHalfWidth,
            viewportHalfHeight = viewportHalfHeight,
            transform = transform,
            transformIsIdentity = transformIsIdentity,
            color = color,
        )
        geometry.addTriIndices(i0, i1, i2)
        geometry.addTriIndices(i0, i2, i3)
        return true
    }

    private fun addText(
        scene: Node,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawText,
    ): Boolean {
        if (command.text.isEmpty() || command.paint.isRenderNoOp) {
            return false
        }
        val ordered = command.paint.requiresOrderedTextBatch()
        val orderingBarrier = command.paint.requiresTextOrderingBarrier()
        val performanceHud = command.state.drawRole == KoolCanvasDrawRole.PerformanceHud && frameProjectionDepth == 0

        val timing = KoolCanvasCommandTiming.enabled
        val fontStart = if (timing) System.nanoTime() else 0L
        val font = renderFonts?.let { KoolCanvasFontRegistry.font(command.paint.textSize, command.paint.typefaceKey, it) }
            ?: KoolCanvasFontRegistry.font(command.paint.textSize, command.paint.typefaceKey)
        val labelTemplate = if (instanceTextLabels && !performanceHud && '\n' !in command.text)
            textTemplates!!.geometry(font, command.text) else null
        val labelSlice = labelTemplate?.let { labelGeometry!!.slice(it, renderSequence) }
        val labelCapacity = labelSlice?.vertexCount?.let { count ->
            var capacity = 4; while (capacity < count) capacity *= 2; capacity
        } ?: 0
        val entry = ensureTextMesh(
            scene,
            font,
            command.paint.renderBlend,
            command.paint.typefaceKey,
            ordered,
            performanceHud,
            instanced = labelCapacity != 0 || (instanceTextGlyphs && !performanceHud && '\n' !in command.text && frameProjectionDepth == 0),
            labelVertexCapacity = labelCapacity,
        )
        if (timing) KoolCanvasCommandTiming.record(KoolCanvasCommandTiming.textFontLookup(),
            System.nanoTime() - fontStart)
        if (performanceHud) performanceHudEntries += command to entry
        val displayed = if (performanceHud) CanvasPerformanceHud.command(command, performanceHudSample) else command
        return writeText(entry, font, viewport, displayed,
            if (prepareText) labelTemplate else null, if (prepareText) labelSlice else null).also { added ->
            if (added && orderingBarrier) markOrderingBarrier()
        }
    }

    /** Refresh the one display-only counter mesh while keeping repeated world batches intact. */
    fun refreshPerformanceHud(viewport: KoolCanvasViewport): Boolean {
        val sample = performanceRates()
        if (sample === performanceHudSample || performanceHudEntries.isEmpty()) return false
        performanceHudSample = sample
        for ((command, entry) in performanceHudEntries) {
            val font = renderFonts?.let { KoolCanvasFontRegistry.font(command.paint.textSize, command.paint.typefaceKey, it) }
            ?: KoolCanvasFontRegistry.font(command.paint.textSize, command.paint.typefaceKey)
            entry.builder?.clear()
            entry.mesh.isVisible = writeText(entry, font, viewport, CanvasPerformanceHud.command(command, sample))
        }
        return true
    }

    private fun writeText(
        entry: TextMeshEntry,
        font: MsdfFont,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawText,
        preparedTemplate: KoolCanvasTextTemplates.Template? = null,
        preparedSlice: KoolCanvasLabelGeometry.Slice? = null,
    ): Boolean {
        val timing = KoolCanvasCommandTiming.enabled
        val metricsStart = if (timing) System.nanoTime() else 0L
        val template = preparedTemplate ?: if (prepareText && '\n' !in command.text)
            textTemplates?.geometry(font, command.text) else null
        val metrics = template?.metrics ?: textMetrics?.textDimensions(font, command.text)
            ?: font.textDimensions(command.text, TextMetrics())
        if (timing) KoolCanvasCommandTiming.record(KoolCanvasCommandTiming.textMetrics(),
            System.nanoTime() - metricsStart)
        val clipStart = if (timing) System.nanoTime() else 0L
        val origin = alignedTextOrigin(command, metrics)
        if (!command.state.intersectsClip(textBounds(origin, metrics))) {
            if (timing) KoolCanvasCommandTiming.record(KoolCanvasCommandTiming.textClipOrigin(),
                System.nanoTime() - clipStart)
            return false
        }

        val builder = entry.builder
        val clipBounds = command.state.clip.toWorldClip(viewport)
        val transform = command.state.transform
        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val identityTransform = transform === KoolCanvasTransform.Identity

        entry.instances?.let { instances ->
            val instanceTemplate = template ?: checkNotNull(textTemplates).geometry(font, command.text)
            val color = command.paint.toRenderColor()
            val glow = font.glowColor ?: Color(0f, 0f, 0f, 0f)
            val pxRange = font.scale * font.sizePts / font.data.meta.atlas.size * font.data.meta.atlas.distanceRange
            val originX = round(origin.x); val originY = round(origin.y)
            if (entry.labelVertexCapacity != 0) {
                val slice = preparedSlice ?: checkNotNull(labelGeometry!!.slice(instanceTemplate, renderSequence))
                if (slice.vertexCount > entry.labelVertexCapacity) {
                    @Suppress("UNCHECKED_CAST")
                    val geometry = entry.mesh.geometry as IndexedVertexList<VertexLayouts.Position>
                    var capacity = entry.labelVertexCapacity
                    while (capacity < slice.vertexCount) capacity *= 2
                    for (vertex in entry.labelVertexCapacity until capacity) geometry.addVertex { layout ->
                        set(layout.position, vertex.toFloat(), 0f, 0f) }
                    for (vertex in entry.labelVertexCapacity until capacity step 4) {
                        geometry.addTriIndices(vertex + 3, vertex + 1, vertex)
                        geometry.addTriIndices(vertex + 2, vertex + 3, vertex)
                    }
                    entry.labelVertexCapacity = capacity
                }
                instances.addInstance { layout ->
                    set(layout.originAxisX, transform.scaleX, transform.skewX, transform.translateX, originX)
                    set(layout.axisY, transform.skewY, transform.scaleY, transform.translateY, originY)
                    set(layout.uv, viewportHalfWidth, viewportHalfHeight, slice.firstVertex.toFloat(), slice.vertexCount.toFloat())
                    set(layout.tint, color.r, color.g, color.b, color.a)
                    set(layout.glow, glow.r, glow.g, glow.b, glow.a)
                    set(layout.props, pxRange, font.weight, font.cutoff, 0f)
                    set(layout.clip, clipBounds)
                }
                return true
            }
            val v = instanceTemplate.vertices
            for (vertex in 0 until instanceTemplate.vertexCount step 4) {
                val offset = vertex * KoolCanvasTextTemplates.Template.STRIDE
                val x = v[offset] + originX; val y = v[offset + 1] + originY
                val axisX = v[offset + 5] - v[offset]
                val axisY = v[offset + 11] - v[offset + 1]
                val skewX = v[offset + 10] - v[offset]
                val mappedX = if (identityTransform) x else transform.scaleX * x + transform.skewX * y + transform.translateX
                val mappedY = if (identityTransform) y else transform.skewY * x + transform.scaleY * y + transform.translateY
                instances.addInstance { layout ->
                    set(layout.originAxisX, mappedX - viewportHalfWidth, viewportHalfHeight - mappedY,
                        transform.scaleX * axisX, -transform.skewY * axisX)
                    set(layout.axisY, transform.scaleX * skewX + transform.skewX * axisY,
                        -(transform.skewY * skewX + transform.scaleY * axisY), 0f, 0f)
                    set(layout.uv, v[offset + 3], v[offset + 4], v[offset + 8], v[offset + 14])
                    set(layout.tint, color.r, color.g, color.b, color.a)
                    set(layout.glow, glow.r, glow.g, glow.b, glow.a)
                    set(layout.props, pxRange, font.weight, font.cutoff, 0f)
                    set(layout.clip, clipBounds)
                }
            }
            return true
        }

        checkNotNull(builder)
        val previousColor = builder.color
        val previousCustomizer = builder.vertexCustomizer

        // Multiline builder translations add the rounded baseline before each line offset. Folding
        // these into one cached position changes float addition order; keep that path bit-identical.
        textTemplates?.takeIf { '\n' !in command.text }?.let { templates ->
            val template = template ?: templates.geometry(font, command.text)
            val color = command.paint.toRenderColor()
            val glow = font.glowColor ?: Color(0f, 0f, 0f, 0f)
            val pxRange = font.scale * font.sizePts / font.data.meta.atlas.size * font.data.meta.atlas.distanceRange
            val base = builder.geometry.numVertices
            val originX = round(origin.x)
            val originY = round(origin.y)
            val geometry = builder.geometry
            for (vertex in 0 until template.vertexCount) {
                val offset = vertex * KoolCanvasTextTemplates.Template.STRIDE
                val x = template.vertices[offset] + originX
                val y = template.vertices[offset + 1] + originY
                val mappedX = if (identityTransform) x else transform.scaleX * x + transform.skewX * y + transform.translateX
                val mappedY = if (identityTransform) y else transform.skewY * x + transform.scaleY * y + transform.translateY
                geometry.addVertex { layout ->
                    set(layout.position, mappedX - viewportHalfWidth, viewportHalfHeight - mappedY, template.vertices[offset + 2])
                    set(layout.texCoord, template.vertices[offset + 3], template.vertices[offset + 4])
                    set(layout.color, color.r, color.g, color.b, color.a)
                    set(layout.msdfProps, pxRange, font.weight, font.cutoff, 0f)
                    set(layout.glowColor, glow.r, glow.g, glow.b, glow.a)
                    set(layout.clip, clipBounds)
                }
            }
            for (index in template.indices.indices step 3) geometry.addTriIndices(
                base + template.indices[index], base + template.indices[index + 1], base + template.indices[index + 2])
            return true
        }

        builder.color = command.paint.toRenderColor()
        if (legacyTextVertexAllocation) {
            // Controlled A/B: the pre-optimisation customizer, which allocated a MutableVec3f and a
            // KoolCanvasPoint per glyph vertex. RWX_LEGACY_TEXT_VERTEX_ALLOCATION=1 restores it.
            builder.vertexCustomizer = { layout ->
                val scratch = MutableVec3f()
                get(layout.position, scratch)
                val mapped = command.state.transform.map(KoolCanvasPoint(scratch.x, scratch.y))
                set(layout.position, mapped.x - viewportHalfWidth, viewportHalfHeight - mapped.y, scratch.z)
                set(layout.clip, clipBounds)
            }
        } else {
            // The customizer runs once per glyph vertex (measured: 114 M calls per run with four vertices
            // and a 3-5 character label). Captured `val`s keep this closure allocation-free and the mapping
            // inlined, because allocating a MutableVec3f and a KoolCanvasPoint per vertex dominated the text
            // path (8.4 us per text against 2.8 us per texture).
            builder.vertexCustomizer = { layout ->
                get(layout.position, textVertexScratch)
                val x = textVertexScratch.x
                val y = textVertexScratch.y
                val mappedX =
                    if (identityTransform) x else transform.scaleX * x + transform.skewX * y + transform.translateX
                val mappedY =
                    if (identityTransform) y else transform.skewY * x + transform.scaleY * y + transform.translateY
                set(
                    layout.position,
                    mappedX - viewportHalfWidth,
                    viewportHalfHeight - mappedY,
                    textVertexScratch.z,
                )
                set(layout.clip, clipBounds)
            }
        }
        if (timing) KoolCanvasCommandTiming.record(KoolCanvasCommandTiming.textClipOrigin(),
            System.nanoTime() - clipStart)

        val buildStart = if (timing) System.nanoTime() else 0L
        KoolCanvasCommandTiming.beginCommand("text:" + command.text)
        return try {
            builder.text(
                TextProps(font).apply {
                    text = command.text
                    this.origin.set(origin.x, origin.y, 0f)
                    isYAxisUp = false
                },
            )
            true
        } finally {
            builder.vertexCustomizer = previousCustomizer
            builder.color = previousColor
            if (timing) KoolCanvasCommandTiming.record(KoolCanvasCommandTiming.textMeshBuild(),
                System.nanoTime() - buildStart)
        }
    }

    private fun alignedTextOrigin(
        command: KoolCanvasCommand.DrawText,
        metrics: TextMetrics,
    ): KoolCanvasPoint {
        val width = metrics.baselineWidth
        val x = when (command.paint.textAlign) {
            KoolCanvasTextAlign.Center -> command.baseline.x - width * 0.5f
            KoolCanvasTextAlign.Right -> command.baseline.x - width
            KoolCanvasTextAlign.Left -> command.baseline.x
        }
        return KoolCanvasPoint(x, command.baseline.y)
    }

    private fun textBounds(origin: KoolCanvasPoint, metrics: TextMetrics): KoolCanvasRect =
        KoolCanvasRect(
            left = origin.x + metrics.paddingStart,
            top = origin.y - metrics.ascentPx,
            right = origin.x + metrics.baselineWidth + metrics.paddingEnd,
            bottom = origin.y - metrics.descentPx,
        )

    private fun addRectBatch(
        scene: Node,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawRectBatch,
    ): Boolean {
        val paint = command.paint
        if (paint.isRenderNoOp) return false
        if (command.state.transform !== KoolCanvasTransform.Identity || paint.style != KoolCanvasPaintStyle.Fill) {
            var added = false
            command.rects.forEachDraw(paint, command.state) { added = addRect(scene, viewport, it) || added }
            return added
        }
        val geometry = ensurePrimitiveMesh(scene, paint.renderBlend, paint.primitiveMeshLayer,
            paint.requiresOrderedPrimitiveBatch()).geometry
        val color = paint.toRenderColor()
        val clip = command.state.clip
        val halfWidth = currentViewportHalfWidth
        val halfHeight = currentViewportHalfHeight
        var added = false
        for (rect in 0 until command.rects.size) {
            val r = command.rects
            val left = if (clip == null) r.coordinate(rect, 0) else maxOf(r.coordinate(rect, 0), clip.boundsLeft)
            val top = if (clip == null) r.coordinate(rect, 1) else maxOf(r.coordinate(rect, 1), clip.boundsTop)
            val right = if (clip == null) r.coordinate(rect, 2) else minOf(r.coordinate(rect, 2), clip.boundsRight)
            val bottom = if (clip == null) r.coordinate(rect, 3) else minOf(r.coordinate(rect, 3), clip.boundsBottom)
            if (right <= left || bottom <= top) continue
            val i0 = geometry.addCanvasVertexIdentity(left, top, halfWidth, halfHeight, color)
            val i1 = geometry.addCanvasVertexIdentity(right, top, halfWidth, halfHeight, color)
            val i2 = geometry.addCanvasVertexIdentity(right, bottom, halfWidth, halfHeight, color)
            val i3 = geometry.addCanvasVertexIdentity(left, bottom, halfWidth, halfHeight, color)
            geometry.addTriIndices(i0, i1, i2)
            geometry.addTriIndices(i0, i2, i3)
            added = true
        }
        if (added && paint.requiresPrimitiveOrderingBarrier()) markOrderingBarrier()
        return added
    }

    private fun addRect(
        scene: Node,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawRect,
    ): Boolean {
        if (command.paint.isRenderNoOp) return false
        val ordered = command.paint.requiresOrderedPrimitiveBatch()
        val orderingBarrier = command.paint.requiresPrimitiveOrderingBarrier()
        return addRect(
            ensurePrimitiveMesh(
                scene,
                command.paint.renderBlend,
                command.paint.primitiveMeshLayer,
                ordered,
            ).geometry,
            viewport,
            command,
        ).also { added ->
            if (added && orderingBarrier) {
                markOrderingBarrier()
            }
        }
    }

    private fun addFilledIdentityPrimitiveRectRun(
        scene: Node,
        viewport: KoolCanvasViewport,
        commands: List<KoolCanvasCommand>,
        startIndex: Int,
        firstCommand: KoolCanvasCommand.DrawRect,
    ): Int {
        if (!firstCommand.canUseFilledIdentityPrimitiveRectRun()) return 0
        val geometry = ensurePrimitiveMesh(
            scene,
            CanvasRenderBlend.Alpha,
            PrimitiveMeshLayer.Default,
            ordered = false,
        ).geometry
        var hasOrderingBarrier = false
        var commandIndex = startIndex
        while (commandIndex < commands.size) {
            val command = commands[commandIndex] as? KoolCanvasCommand.DrawRect ?: break
            if (!adaptiveVisuals.shouldDraw(command)) break
            if (!command.canUseFilledIdentityPrimitiveRectRun()) break
            geometry.addFilledRectIdentityUnchecked(command.rect, command.paint)
            hasOrderingBarrier = hasOrderingBarrier || command.paint.requiresPrimitiveOrderingBarrier()
            commandIndex++
        }
        if (hasOrderingBarrier) {
            markOrderingBarrier()
        }
        return commandIndex - startIndex
    }

    private fun KoolCanvasCommand.DrawRect.canUseFilledIdentityPrimitiveRectRun(): Boolean {
        val paint = paint
        return !paint.isRenderNoOp &&
                paint.style == KoolCanvasPaintStyle.Fill &&
                paint.renderBlend == CanvasRenderBlend.Alpha &&
                state.renderTarget == null &&
                state.clip == null &&
                state.transform === KoolCanvasTransform.Identity &&
                !rect.isEmpty
    }

    private fun addRect(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawRect,
    ): Boolean {
        var added = false
        if (command.paint.style != KoolCanvasPaintStyle.Stroke) {
            added = addFilledRect(geometry, viewport, command.rect, command.paint, command.state) || added
        }
        if (command.paint.style != KoolCanvasPaintStyle.Fill) {
            added = addStrokeRect(geometry, viewport, command.rect, command.paint, command.state) || added
        }
        return added
    }

    private fun addFilledRect(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        viewport: KoolCanvasViewport,
        rect: KoolCanvasRect,
        paint: KoolCanvasPaint,
        state: KoolCanvasState,
    ): Boolean {
        if (state.clip == null && state.transform === KoolCanvasTransform.Identity) {
            return addFilledRectIdentity(geometry, viewport, rect, paint)
        }
        val clippedRect = state.clipForGeometry()?.let { rect.intersect(it) } ?: rect
        if (clippedRect.isEmpty) return false

        val color = paint.toRenderColor()
        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val transform = state.transform
        val transformIsIdentity = transform === KoolCanvasTransform.Identity
        val i0 = geometry.addCanvasVertex(
            clippedRect.left,
            clippedRect.top,
            viewportHalfWidth,
            viewportHalfHeight,
            transform,
            transformIsIdentity,
            color,
        )
        val i1 = geometry.addCanvasVertex(
            clippedRect.right,
            clippedRect.top,
            viewportHalfWidth,
            viewportHalfHeight,
            transform,
            transformIsIdentity,
            color,
        )
        val i2 = geometry.addCanvasVertex(
            clippedRect.right,
            clippedRect.bottom,
            viewportHalfWidth,
            viewportHalfHeight,
            transform,
            transformIsIdentity,
            color,
        )
        val i3 = geometry.addCanvasVertex(
            clippedRect.left,
            clippedRect.bottom,
            viewportHalfWidth,
            viewportHalfHeight,
            transform,
            transformIsIdentity,
            color,
        )
        geometry.addTriIndices(i0, i1, i2)
        geometry.addTriIndices(i0, i2, i3)
        return true
    }

    private fun addFilledRectIdentity(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        viewport: KoolCanvasViewport,
        rect: KoolCanvasRect,
        paint: KoolCanvasPaint,
    ): Boolean {
        if (rect.isEmpty) return false

        val color = paint.toRenderColor()
        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val i0 = geometry.addCanvasVertexIdentity(rect.left, rect.top, viewportHalfWidth, viewportHalfHeight, color)
        val i1 = geometry.addCanvasVertexIdentity(rect.right, rect.top, viewportHalfWidth, viewportHalfHeight, color)
        val i2 = geometry.addCanvasVertexIdentity(rect.right, rect.bottom, viewportHalfWidth, viewportHalfHeight, color)
        val i3 = geometry.addCanvasVertexIdentity(rect.left, rect.bottom, viewportHalfWidth, viewportHalfHeight, color)
        geometry.addTriIndices(i0, i1, i2)
        geometry.addTriIndices(i0, i2, i3)
        return true
    }

    private fun IndexedVertexList<VertexLayouts.PositionNormalColor>.addFilledRectIdentityUnchecked(
        rect: KoolCanvasRect,
        paint: KoolCanvasPaint,
    ) {
        val color = paint.toRenderColor()
        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val i0 = addCanvasVertexIdentity(rect.left, rect.top, viewportHalfWidth, viewportHalfHeight, color)
        val i1 = addCanvasVertexIdentity(rect.right, rect.top, viewportHalfWidth, viewportHalfHeight, color)
        val i2 = addCanvasVertexIdentity(rect.right, rect.bottom, viewportHalfWidth, viewportHalfHeight, color)
        val i3 = addCanvasVertexIdentity(rect.left, rect.bottom, viewportHalfWidth, viewportHalfHeight, color)
        addTriIndices(i0, i1, i2)
        addTriIndices(i0, i2, i3)
    }

    private fun addStrokeRect(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        viewport: KoolCanvasViewport,
        rect: KoolCanvasRect,
        paint: KoolCanvasPaint,
        state: KoolCanvasState,
    ): Boolean {
        if (rect.isEmpty || !state.intersectsClip(rect.grow(paint.strokeWidth * 0.5f))) return false

        val top = addThickLine(
            geometry = geometry,
            viewport = viewport,
            start = KoolCanvasPoint(rect.left, rect.top),
            end = KoolCanvasPoint(rect.right, rect.top),
            paint = paint,
            state = state,
        )
        val right = addThickLine(
            geometry = geometry,
            viewport = viewport,
            start = KoolCanvasPoint(rect.right, rect.top),
            end = KoolCanvasPoint(rect.right, rect.bottom),
            paint = paint,
            state = state,
        )
        val bottom = addThickLine(
            geometry = geometry,
            viewport = viewport,
            start = KoolCanvasPoint(rect.right, rect.bottom),
            end = KoolCanvasPoint(rect.left, rect.bottom),
            paint = paint,
            state = state,
        )
        val left = addThickLine(
            geometry = geometry,
            viewport = viewport,
            start = KoolCanvasPoint(rect.left, rect.bottom),
            end = KoolCanvasPoint(rect.left, rect.top),
            paint = paint,
            state = state,
        )
        return top || right || bottom || left
    }

    private fun addLine(
        scene: Node,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawLine,
    ): Boolean {
        if (command.paint.isRenderNoOp) return false
        val ordered = command.paint.requiresOrderedPrimitiveBatch()
        val orderingBarrier = command.paint.requiresPrimitiveOrderingBarrier()
        return addLine(
            ensurePrimitiveMesh(
                scene,
                command.paint.renderBlend,
                command.paint.primitiveMeshLayer,
                ordered,
            ).geometry,
            viewport,
            command,
        ).also { added ->
            if (added && orderingBarrier) {
                markOrderingBarrier()
            }
        }
    }

    private fun addLine(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawLine,
    ): Boolean {
        if (command.state.clip != null) {
            val halfWidth = command.paint.strokeWidth.coerceAtLeast(1f) * 0.5f
            val bounds = KoolCanvasRect(
                left = minOf(command.start.x, command.end.x) - halfWidth,
                top = minOf(command.start.y, command.end.y) - halfWidth,
                right = maxOf(command.start.x, command.end.x) + halfWidth,
                bottom = maxOf(command.start.y, command.end.y) + halfWidth,
            )
            if (!command.state.intersectsClip(bounds)) return false
        }
        return addThickLine(geometry, viewport, command.start, command.end, command.paint, command.state)
    }

    private fun addCircle(
        scene: Node,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawCircle,
    ): Boolean {
        if (command.paint.isRenderNoOp) return false
        val ordered = command.paint.requiresOrderedPrimitiveBatch()
        val orderingBarrier = command.paint.requiresPrimitiveOrderingBarrier()
        return addCircle(
            ensurePrimitiveMesh(
                scene,
                command.paint.renderBlend,
                command.paint.primitiveMeshLayer,
                ordered,
            ).geometry,
            viewport,
            command,
        ).also { added ->
            if (added && orderingBarrier) {
                markOrderingBarrier()
            }
        }
    }

    private fun addCircle(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        viewport: KoolCanvasViewport,
        command: KoolCanvasCommand.DrawCircle,
    ): Boolean {
        if (command.state.clip != null) {
            val strokeHalfWidth = command.paint.strokeWidth.coerceAtLeast(1f) * 0.5f
            val bounds = KoolCanvasRect(
                left = command.center.x - command.radius - strokeHalfWidth,
                top = command.center.y - command.radius - strokeHalfWidth,
                right = command.center.x + command.radius + strokeHalfWidth,
                bottom = command.center.y + command.radius + strokeHalfWidth,
            )
            if (!command.state.intersectsClip(bounds)) return false
        }

        var added = false
        if (command.paint.style != KoolCanvasPaintStyle.Stroke && command.radius > 0f) {
            added = addFilledCircle(
                geometry = geometry,
                viewport = viewport,
                center = command.center,
                radius = command.radius,
                paint = command.paint,
                state = command.state,
            ) || added
        }
        if (command.paint.style != KoolCanvasPaintStyle.Fill) {
            added = addCircleStroke(
                geometry = geometry,
                viewport = viewport,
                center = command.center,
                radius = command.radius,
                paint = command.paint,
                state = command.state,
            ) || added
        }
        return added
    }

    private fun addThickLine(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        viewport: KoolCanvasViewport,
        start: KoolCanvasPoint,
        end: KoolCanvasPoint,
        paint: KoolCanvasPaint,
        state: KoolCanvasState,
    ): Boolean {
        val transform = state.transform
        val transformIsIdentity = transform === KoolCanvasTransform.Identity
        val mappedStart = if (transformIsIdentity) {
            start
        } else {
            transform.map(start)
        }
        val mappedEnd = if (transformIsIdentity) {
            end
        } else {
            transform.map(end)
        }
        val dx = mappedEnd.x - mappedStart.x
        val dy = mappedEnd.y - mappedStart.y
        val length = sqrt(dx * dx + dy * dy)
        val halfWidth = paint.strokeWidth.coerceAtLeast(1f) * 0.5f
        if (length == 0f) {
            return addFilledCircle(
                geometry,
                viewport,
                mappedStart,
                halfWidth,
                paint,
                state.copy(transform = KoolCanvasTransform.Identity)
            )
        }

        val normalX = -dy / length * halfWidth
        val normalY = dx / length * halfWidth
        val color = paint.toRenderColor()
        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val i0 = geometry.addMappedCanvasVertex(
            mappedStart.x + normalX,
            mappedStart.y + normalY,
            viewportHalfWidth,
            viewportHalfHeight,
            color,
        )
        val i1 = geometry.addMappedCanvasVertex(
            mappedEnd.x + normalX,
            mappedEnd.y + normalY,
            viewportHalfWidth,
            viewportHalfHeight,
            color,
        )
        val i2 = geometry.addMappedCanvasVertex(
            mappedEnd.x - normalX,
            mappedEnd.y - normalY,
            viewportHalfWidth,
            viewportHalfHeight,
            color,
        )
        val i3 = geometry.addMappedCanvasVertex(
            mappedStart.x - normalX,
            mappedStart.y - normalY,
            viewportHalfWidth,
            viewportHalfHeight,
            color,
        )
        geometry.addTriIndices(i0, i1, i2)
        geometry.addTriIndices(i0, i2, i3)
        return true
    }

    private fun addFilledCircle(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        viewport: KoolCanvasViewport,
        center: KoolCanvasPoint,
        radius: Float,
        paint: KoolCanvasPaint,
        state: KoolCanvasState,
    ): Boolean {
        if (radius <= 0f) return false

        val color = paint.toRenderColor()
        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val transform = state.transform
        val transformIsIdentity = transform === KoolCanvasTransform.Identity
        val centerIndex = geometry.addCanvasVertex(
            center.x,
            center.y,
            viewportHalfWidth,
            viewportHalfHeight,
            transform,
            transformIsIdentity,
            color,
        )
        val segments = circleSegmentCount(radius)
        var previous = geometry.addCircleVertex(
            center,
            radius,
            0,
            segments,
            viewportHalfWidth,
            viewportHalfHeight,
            transform,
            transformIsIdentity,
            color,
        )
        for (segment in 1..segments) {
            val next = geometry.addCircleVertex(
                center,
                radius,
                segment,
                segments,
                viewportHalfWidth,
                viewportHalfHeight,
                transform,
                transformIsIdentity,
                color,
            )
            geometry.addTriIndices(centerIndex, previous, next)
            previous = next
        }
        return true
    }

    private fun addCircleStroke(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        viewport: KoolCanvasViewport,
        center: KoolCanvasPoint,
        radius: Float,
        paint: KoolCanvasPaint,
        state: KoolCanvasState,
    ): Boolean {
        val halfWidth = paint.strokeWidth.coerceAtLeast(1f) * 0.5f
        val outerRadius = radius + halfWidth
        val innerRadius = max(0f, radius - halfWidth)
        if (innerRadius == 0f) {
            return addFilledCircle(geometry, viewport, center, outerRadius, paint, state)
        }

        val color = paint.toRenderColor()
        val segments = circleSegmentCount(outerRadius)
        val viewportHalfWidth = currentViewportHalfWidth
        val viewportHalfHeight = currentViewportHalfHeight
        val transform = state.transform
        val transformIsIdentity = transform === KoolCanvasTransform.Identity
        val mappedCenter = if (transformIsIdentity) center else transform.map(center)
        var previousOuter = geometry.addCircleStrokeVertex(
            center,
            mappedCenter,
            radius,
            halfWidth,
            0,
            segments,
            viewportHalfWidth,
            viewportHalfHeight,
            transform,
            transformIsIdentity,
            color,
            outer = true,
        )
        var previousInner = geometry.addCircleStrokeVertex(
            center,
            mappedCenter,
            radius,
            halfWidth,
            0,
            segments,
            viewportHalfWidth,
            viewportHalfHeight,
            transform,
            transformIsIdentity,
            color,
            outer = false,
        )
        for (segment in 1..segments) {
            val nextOuter =
                geometry.addCircleStrokeVertex(
                    center,
                    mappedCenter,
                    radius,
                    halfWidth,
                    segment,
                    segments,
                    viewportHalfWidth,
                    viewportHalfHeight,
                    transform,
                    transformIsIdentity,
                    color,
                    outer = true,
                )
            val nextInner =
                geometry.addCircleStrokeVertex(
                    center,
                    mappedCenter,
                    radius,
                    halfWidth,
                    segment,
                    segments,
                    viewportHalfWidth,
                    viewportHalfHeight,
                    transform,
                    transformIsIdentity,
                    color,
                    outer = false,
                )
            geometry.addTriIndices(previousOuter, nextOuter, nextInner)
            geometry.addTriIndices(previousOuter, nextInner, previousInner)
            previousOuter = nextOuter
            previousInner = nextInner
        }
        return true
    }

    private fun circleSegmentCount(radius: Float): Int =
        ceil(radius / 6f).toInt().coerceIn(16, 96)

    private fun IndexedVertexList<VertexLayouts.PositionNormalColor>.addCircleVertex(
        center: KoolCanvasPoint,
        radius: Float,
        segment: Int,
        segmentCount: Int,
        viewportHalfWidth: Float,
        viewportHalfHeight: Float,
        transform: KoolCanvasTransform,
        transformIsIdentity: Boolean,
        color: Color,
    ): Int {
        val unit = circleUnitCache(segmentCount)
        return addCanvasVertex(
            x = center.x + unit.first[segment] * radius,
            y = center.y + unit.second[segment] * radius,
            viewportHalfWidth = viewportHalfWidth,
            viewportHalfHeight = viewportHalfHeight,
            transform = transform,
            transformIsIdentity = transformIsIdentity,
            color = color,
        )
    }

    private fun IndexedVertexList<VertexLayouts.PositionNormalColor>.addCircleStrokeVertex(
        center: KoolCanvasPoint,
        mappedCenter: KoolCanvasPoint,
        radius: Float,
        halfWidth: Float,
        segment: Int,
        segmentCount: Int,
        viewportHalfWidth: Float,
        viewportHalfHeight: Float,
        transform: KoolCanvasTransform,
        transformIsIdentity: Boolean,
        color: Color,
        outer: Boolean,
    ): Int {
        val unit = circleUnitCache(segmentCount)
        val localX = center.x + unit.first[segment] * radius
        val localY = center.y + unit.second[segment] * radius
        val mappedX: Float
        val mappedY: Float
        if (transformIsIdentity) {
            mappedX = localX
            mappedY = localY
        } else {
            mappedX = transform.scaleX * localX + transform.skewX * localY + transform.translateX
            mappedY = transform.skewY * localX + transform.scaleY * localY + transform.translateY
        }
        var normalX = mappedX - mappedCenter.x
        var normalY = mappedY - mappedCenter.y
        val normalLength = sqrt(normalX * normalX + normalY * normalY)
        if (normalLength > 0f) {
            normalX /= normalLength
            normalY /= normalLength
        } else {
            normalX = unit.first[segment]
            normalY = unit.second[segment]
        }
        val width = if (outer) halfWidth else -halfWidth
        return addMappedCanvasVertex(
            x = mappedX + normalX * width,
            y = mappedY + normalY * width,
            viewportHalfWidth = viewportHalfWidth,
            viewportHalfHeight = viewportHalfHeight,
            color = color,
        )
    }

    private fun circleUnitCache(segmentCount: Int): Pair<FloatArray, FloatArray> {
        val cached = CIRCLE_UNIT_CACHE[segmentCount]
        if (cached != null) {
            return cached
        }
        val xs = FloatArray(segmentCount + 1)
        val ys = FloatArray(segmentCount + 1)
        for (segment in 0..segmentCount) {
            val angle = (segment.toFloat() / segmentCount) * (PI.toFloat() * 2f)
            xs[segment] = cos(angle)
            ys[segment] = sin(angle)
        }
        return (xs to ys).also { CIRCLE_UNIT_CACHE[segmentCount] = it }
    }

    private fun IndexedVertexList<VertexLayouts.PositionNormalColor>.addCanvasVertex(
        x: Float,
        y: Float,
        viewport: KoolCanvasViewport,
        transform: KoolCanvasTransform,
        color: Color,
    ): Int =
        addCanvasVertex(
            x = x,
            y = y,
            viewportHalfWidth = currentViewportHalfWidth,
            viewportHalfHeight = currentViewportHalfHeight,
            transform = transform,
            transformIsIdentity = transform === KoolCanvasTransform.Identity,
            color = color,
        )

    private fun IndexedVertexList<VertexLayouts.PositionNormalColor>.addCanvasVertex(
        x: Float,
        y: Float,
        viewportHalfWidth: Float,
        viewportHalfHeight: Float,
        transform: KoolCanvasTransform,
        transformIsIdentity: Boolean,
        color: Color,
    ): Int {
        val mappedX: Float
        val mappedY: Float
        if (transformIsIdentity) {
            mappedX = x
            mappedY = y
        } else {
            mappedX = transform.scaleX * x + transform.skewX * y + transform.translateX
            mappedY = transform.skewY * x + transform.scaleY * y + transform.translateY
        }
        return addVertex { layout ->
            set(layout.position, mappedX - viewportHalfWidth, viewportHalfHeight - mappedY, 0f)
            set(layout.normal, 0f, 0f, 1f)
            set(layout.color, color.r, color.g, color.b, color.a)
        }
    }

    private fun IndexedVertexList<VertexLayouts.PositionNormalColor>.addCanvasVertexIdentity(
        x: Float,
        y: Float,
        viewportHalfWidth: Float,
        viewportHalfHeight: Float,
        color: Color,
    ): Int =
        addMappedCanvasVertex(x, y, viewportHalfWidth, viewportHalfHeight, color)

    private fun IndexedVertexList<VertexLayouts.PositionNormalColor>.addMappedCanvasVertex(
        x: Float,
        y: Float,
        viewportHalfWidth: Float,
        viewportHalfHeight: Float,
        color: Color,
    ): Int =
        addVertex { layout ->
            set(layout.position, x - viewportHalfWidth, viewportHalfHeight - y, 0f)
            set(layout.normal, 0f, 0f, 1f)
            set(layout.color, color.r, color.g, color.b, color.a)
        }

    private fun IndexedVertexList<VertexLayouts.PositionNormalTexCoordColor>.addCanvasTextureVertex(
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        viewportHalfWidth: Float,
        viewportHalfHeight: Float,
        transform: KoolCanvasTransform,
        transformIsIdentity: Boolean,
        color: Color,
    ): Int {
        val mappedX: Float
        val mappedY: Float
        if (transformIsIdentity) {
            mappedX = x
            mappedY = y
        } else {
            mappedX = transform.scaleX * x + transform.skewX * y + transform.translateX
            mappedY = transform.skewY * x + transform.scaleY * y + transform.translateY
        }
        return addVertex { layout ->
            set(layout.position, mappedX - viewportHalfWidth, viewportHalfHeight - mappedY, 0f)
            set(layout.normal, 0f, 0f, 1f)
            set(layout.texCoord, u, v)
            set(layout.color, color.r, color.g, color.b, color.a)
        }
    }

    private fun KoolCanvasState.intersectsClip(bounds: KoolCanvasRect): Boolean =
        clipForGeometry()?.let { bounds.boundsIntersect(it) != null } ?: true

    private fun KoolCanvasState.clipForGeometry(): KoolCanvasRect? {
        val currentClip = clip ?: return null
        val currentTransform = transform
        if (frameProjectionDepth > 0 || currentTransform === KoolCanvasTransform.Identity) {
            return currentClip
        }
        return currentTransform.inverted()?.mapRect(currentClip) ?: currentClip
    }

    private fun KoolCanvasState.projected(
        outerState: KoolCanvasState,
        placementTransform: KoolCanvasTransform,
        frameClip: KoolCanvasRect,
    ): KoolCanvasState? {
        val localFrameClip = transform.inverted()?.mapRect(frameClip) ?: return null
        val projectedClip = clip?.boundsIntersect(localFrameClip) ?: localFrameClip
        return copy(
            transform = outerState.transform
                .multiply(placementTransform)
                .multiply(transform),
            clip = projectedClip,
        )
    }

    private fun KoolCanvasRect.toDestinationTransform(destination: KoolCanvasRect): KoolCanvasTransform =
        KoolCanvasTransform.Identity
            .translate(destination.left, destination.top)
            .scale(destination.width / width, destination.height / height)
            .translate(-left, -top)

    private fun KoolCanvasCommand.DrawTexture.frameSourceClip(frame: KoolCanvasFrame): KoolCanvasRect? {
        val frameBounds = KoolCanvasRect.fromSize(frame.viewport.width.toFloat(), frame.viewport.height.toFloat())
        var frameClip = source.boundsIntersect(frameBounds) ?: return null
        state.clip?.let { clip ->
            val clippedDestinationBounds = destination.boundsIntersect(clip) ?: return null
            val clippedDestination = destination.orientBounds(clippedDestinationBounds)
            frameClip = frameClip.boundsIntersect(destination.mapSubRectTo(source, clippedDestination)) ?: return null
        }
        return frameClip
    }

    private fun KoolCanvasRect.mapSubRectTo(target: KoolCanvasRect, subRect: KoolCanvasRect): KoolCanvasRect {
        val scaleX = target.width / width
        val scaleY = target.height / height
        return KoolCanvasRect(
            left = target.left + (subRect.left - left) * scaleX,
            top = target.top + (subRect.top - top) * scaleY,
            right = target.left + (subRect.right - left) * scaleX,
            bottom = target.top + (subRect.bottom - top) * scaleY,
        )
    }

    private fun KoolCanvasTransform.inverted(): KoolCanvasTransform? {
        val determinant = (scaleX * scaleY) - (skewX * skewY)
        if (abs(determinant) < 0.000001f) {
            return null
        }
        val inverseScaleX = scaleY / determinant
        val inverseSkewX = -skewX / determinant
        val inverseSkewY = -skewY / determinant
        val inverseScaleY = scaleX / determinant
        return KoolCanvasTransform(
            scaleX = inverseScaleX,
            skewY = inverseSkewY,
            skewX = inverseSkewX,
            scaleY = inverseScaleY,
            translateX = -(inverseScaleX * translateX + inverseSkewX * translateY),
            translateY = -(inverseSkewY * translateX + inverseScaleY * translateY),
        )
    }

    private fun KoolCanvasTransform.mapRect(rect: KoolCanvasRect): KoolCanvasRect {
        val p0 = map(KoolCanvasPoint(rect.left, rect.top))
        val p1 = map(KoolCanvasPoint(rect.right, rect.top))
        val p2 = map(KoolCanvasPoint(rect.right, rect.bottom))
        val p3 = map(KoolCanvasPoint(rect.left, rect.bottom))
        return KoolCanvasRect(
            left = minOf(p0.x, p1.x, p2.x, p3.x),
            top = minOf(p0.y, p1.y, p2.y, p3.y),
            right = maxOf(p0.x, p1.x, p2.x, p3.x),
            bottom = maxOf(p0.y, p1.y, p2.y, p3.y),
        )
    }

    private fun KoolCanvasRect?.toWorldClip(viewport: KoolCanvasViewport): Vec4f {
        val halfWidth = viewport.width * 0.5f
        val halfHeight = viewport.height * 0.5f
        val rect = this ?: KoolCanvasRect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat())
        return Vec4f(
            rect.left - halfWidth,
            halfHeight - rect.bottom,
            rect.right - halfWidth,
            halfHeight - rect.top,
        )
    }

    private fun KoolCanvasRect.grow(amount: Float): KoolCanvasRect = KoolCanvasRect(
        left = left - amount,
        top = top - amount,
        right = right + amount,
        bottom = bottom + amount,
    )

    private val KoolCanvasPaint.renderBlend: CanvasRenderBlend
        get() = when (blendMode) {
            KoolCanvasBlendMode.Add -> CanvasRenderBlend.Additive
            KoolCanvasBlendMode.Clear,
            KoolCanvasBlendMode.ClearAlpha,
            KoolCanvasBlendMode.Source -> CanvasRenderBlend.Source

            else -> CanvasRenderBlend.Alpha
        }

    private val KoolCanvasPaint.primitiveMeshLayer: PrimitiveMeshLayer
        get() = PrimitiveMeshLayer.Default

    private val KoolCanvasPaint.isRenderNoOp: Boolean
        get() = blendMode == KoolCanvasBlendMode.Destination || alphaMultiplier <= 0f

    private val KoolCanvasPaint.effectiveAlpha: Float
        get() = (color.alpha / 255f) * alphaMultiplier

    private fun KoolCanvasPaint.requiresOrderedPrimitiveBatch(): Boolean =
        frameProjectionDepth == 0 && renderBlend != CanvasRenderBlend.Alpha

    private fun KoolCanvasPaint.requiresOrderedTextBatch(): Boolean =
        frameProjectionDepth == 0 && requiresTextOrderingBarrier()

    private fun KoolCanvasPaint.requiresPrimitiveOrderingBarrier(): Boolean =
        renderBlend != CanvasRenderBlend.Alpha || effectiveAlpha < 0.999f

    private fun KoolCanvasPaint.requiresTextureOrderingBarrier(texture: KoolCanvasTextureRef): Boolean =
        renderBlend != CanvasRenderBlend.Alpha ||
                effectiveAlpha < 0.999f ||
                texture.hasAlpha ||
                texture.requiresOrderedAlpha ||
                texture.premultipliedAlpha

    private fun KoolCanvasPaint.requiresTextOrderingBarrier(): Boolean =
        renderBlend != CanvasRenderBlend.Alpha || effectiveAlpha < 0.999f || frameProjectionDepth == 0

    private fun KoolCanvasPaint.toRenderColor(): Color {
        val directCachedPaint = lastRenderColorPaint
        if (directCachedPaint === this) {
            return lastRenderColor!!
        }

        for (index in 0 until RENDER_COLOR_CACHE_SIZE) {
            if (renderColorCachePaints[index] == this) {
                val cachedColor = renderColorCacheColors[index]!!
                lastRenderColorPaint = this
                lastRenderColor = cachedColor
                return cachedColor
            }
        }

        val color = if (blendMode == KoolCanvasBlendMode.Clear || blendMode == KoolCanvasBlendMode.ClearAlpha) {
            KoolCanvasColor.Transparent.toKoolColor()
        } else {
            color.toKoolColor(alphaMultiplier)
        }
        val cacheSlot = nextRenderColorCacheSlot
        renderColorCachePaints[cacheSlot] = this
        renderColorCacheColors[cacheSlot] = color
        nextRenderColorCacheSlot = (cacheSlot + 1) % RENDER_COLOR_CACHE_SIZE
        lastRenderColorPaint = this
        lastRenderColor = color
        return color
    }

    private fun KoolCanvasCommand.Clear.toClearColor(): Color =
        if (blendMode == KoolCanvasBlendMode.Clear || blendMode == KoolCanvasBlendMode.ClearAlpha) {
            KoolCanvasColor.Transparent.toKoolColor()
        } else {
            color.toKoolColor()
        }

    private fun KoolCanvasColor.toVec4f(): Vec4f =
        Vec4f(red / 255f, green / 255f, blue / 255f, alpha / 255f)

    private fun KoolCanvasPaint.withOuterPaint(outer: KoolCanvasPaint): KoolCanvasPaint =
        copy(
            color = color.multipliedBy(outer.color),
            alphaMultiplier = (alphaMultiplier * outer.alphaMultiplier).coerceIn(0f, 1f),
            blendMode = projectedBlendMode(outer),
            textureEffect = textureEffect ?: outer.textureEffect,
        )

    private fun KoolCanvasPaint.projectedBlendMode(outer: KoolCanvasPaint): KoolCanvasBlendMode =
        when {
            outer.blendMode != KoolCanvasBlendMode.SourceOver -> outer.blendMode
            blendMode == KoolCanvasBlendMode.Source ||
                    blendMode == KoolCanvasBlendMode.Clear ||
                    blendMode == KoolCanvasBlendMode.ClearAlpha ->
                KoolCanvasBlendMode.SourceOver

            else -> blendMode
        }

    private fun KoolCanvasColor.multipliedBy(outer: KoolCanvasColor): KoolCanvasColor =
        KoolCanvasColor(
            ((multiplyChannel(alpha, outer.alpha) and 0xff) shl 24) or
                    ((multiplyChannel(red, outer.red) and 0xff) shl 16) or
                    ((multiplyChannel(green, outer.green) and 0xff) shl 8) or
                    (multiplyChannel(blue, outer.blue) and 0xff),
        )

    private fun multiplyChannel(source: Int, multiplier: Int): Int = (source * multiplier) / 255

    private fun CanvasRenderBlend.textureBlendMode(premultipliedAlpha: Boolean): KoolBlendMode =
        if (this == CanvasRenderBlend.Alpha && premultipliedAlpha) {
            KoolBlendMode.BLEND_PREMULTIPLIED_ALPHA
        } else {
            textureBlendMode
        }

    private enum class CanvasRenderBlend(
        val meshNameSuffix: String,
        val primitiveBlendMode: KoolBlendMode,
        val textureBlendMode: KoolBlendMode,
        val textBlendMode: KoolBlendMode,
    ) {
        Alpha(
            meshNameSuffix = "",
            primitiveBlendMode = KoolBlendMode.BLEND_MULTIPLY_ALPHA,
            textureBlendMode = KoolBlendMode.BLEND_MULTIPLY_ALPHA,
            textBlendMode = KoolBlendMode.BLEND_PREMULTIPLIED_ALPHA,
        ),
        Additive(
            meshNameSuffix = "-additive",
            primitiveBlendMode = KoolBlendMode.BLEND_ADDITIVE,
            textureBlendMode = KoolBlendMode.BLEND_ADDITIVE,
            textBlendMode = KoolBlendMode.BLEND_ADDITIVE,
        ),
        Source(
            meshNameSuffix = "-source",
            primitiveBlendMode = KoolBlendMode.DISABLED,
            textureBlendMode = KoolBlendMode.DISABLED,
            textBlendMode = KoolBlendMode.DISABLED,
        ),
    }

    private enum class PrimitiveMeshLayer(val meshNameSuffix: String) {
        Default(""),
    }

    private var diagnosticFrameSequence = 0L

    /**
     * Diagnostic: nanoseconds spent in each step of the command loop, so the fixed per-command cost can be
     * attributed instead of guessed. `LOOP_OTHER` is the self time of the loop itself (indexing, bounds,
     * `commandIndex++`), i.e. the residue once every named step is removed.
     */
    private val loopSplitNanos = if (System.getenv("RWX_LOOP_SPLIT") == "1") LongArray(LOOP_SPLIT_COUNT) else null
    /**
     * The split counters are cumulative per renderer instance, and every offscreen target builds its own
     * renderer, so rows from different instances must stay distinguishable or they cannot be aggregated.
     */
    private val loopSplitInstance = if (loopSplitNanos != null) System.identityHashCode(loopSplitNanos) else 0
    private val loopSplitTiming: Boolean get() = loopSplitNanos != null

    internal fun reportLoopSplit() {
        val split = loopSplitNanos ?: return
        val names = arrayOf(
            "visibility", "dispatch", "textureAtlas", "textureRun", "textureDraw",
            "primitive", "text", "other",
        )
        val total = split.sum().coerceAtLeast(1L)
        println("[RWX canvas] loopSplit " + names.indices.joinToString(" ") { index ->
            names[index] + "=" + (split[index] / 1_000_000) + "ms(" +
                ((split[index] * 1000 / total) / 10.0) + "%)"
        } + " totalMs=" + (total / 1_000_000))
    }

    private companion object {
        val REUSE_INSTANCED_TEXTURE_SHADERS = (System.getenv("RWX_REUSE_INSTANCED_TEXTURE_SHADERS") == "1").also {
            println("[RWX canvas] reuseInstancedTextureShaders=$it")
        }
        // Four fixed vertices and six indices need eight entries, not Kool's 1024-entry default.
        // Keep the old allocation as the control until independent memory / pixel gates pass.
        val QUAD_STORAGE_SIZE = (if (System.getenv("RWX_COMPACT_QUAD_STORAGE") == "1") 8 else 1024).also {
            println("[RWX canvas] quadStorageVertices=$it")
        }
        /**
         * Let paints that the instanced texture shader can reproduce join the batched run.
         *
         * `RWX_INSTANCED_RUN_RELAXED_PAINT=0` restores the previous identity check.
         */
        val INSTANCED_RUN_RELAXED_PAINT = System.getenv("RWX_INSTANCED_RUN_RELAXED_PAINT") != "0"
        const val LOOP_VISIBILITY = 0
        const val LOOP_DISPATCH = 1
        const val LOOP_TEXTURE_ATLAS = 2
        const val LOOP_TEXTURE_RUN = 3
        const val LOOP_TEXTURE_DRAW = 4
        const val LOOP_PRIMITIVE = 5
        const val LOOP_TEXT = 6
        const val LOOP_OTHER = 7
        const val LOOP_SPLIT_COUNT = 8
        val LOOP_SPLIT_STAGES = arrayOf(
            "loop-visibility", "loop-dispatch", "loop-textureAtlas", "loop-textureRun",
            "loop-textureDraw", "loop-primitive", "loop-text", "loop-other",
        )
        /** `RWX_INSTANCED_RUN_CLIP_INSIDE=0` restores the previous "no clip at all" requirement. */
        val INSTANCED_RUN_CLIP_WHEN_INSIDE = System.getenv("RWX_INSTANCED_RUN_CLIP_INSIDE") != "0"
        const val MaxProjectedOrderingSegmentAdvances = 128
        const val MAX_FRAME_TEXTURE_PROJECTION_DEPTH: Int = 8
        const val MAX_PROJECTED_FRAME_TEXTURE_EXPANSIONS: Int = 192
        const val RESOLVED_TEXTURE_CACHE_SIZE: Int = 16
        const val FRAME_TEXTURE_CACHE_SIZE: Int = 4
        const val FRAME_TEXTURE_MISS_CACHE_SIZE: Int = 64
        const val RENDER_COLOR_CACHE_SIZE: Int = 8
        const val TEXTURE_RUN_CACHE_SIZE: Int = 16
        const val ORDERED_TEXTURE_INSTANCE_INITIAL_SIZE: Int = 128
        const val UNORDERED_TEXTURE_INSTANCE_INITIAL_SIZE: Int = 8192
        val CIRCLE_UNIT_CACHE: Array<Pair<FloatArray, FloatArray>?> = arrayOfNulls(97)
    }
}
