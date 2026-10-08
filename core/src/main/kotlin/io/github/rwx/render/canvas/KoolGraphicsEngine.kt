package io.github.rwx.render.canvas

import com.corrodinggames.rts.R
import com.corrodinggames.rts.game.ColorMode
import com.corrodinggames.rts.gameFramework.graphics.*
import com.corrodinggames.rts.gameFramework.graphics.opengl.TextureAtlas
import com.corrodinggames.rts.gameFramework.utility.AssetInputStream
import de.fabmax.kool.Assets
import de.fabmax.kool.MimeType
import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.util.Uint8Buffer
import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import io.github.rwx.trimAssetPath
import kotlinx.coroutines.runBlocking
import java.io.*
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.Lock
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Core-side GraphicsEngine implementation that converts RW draw calls into the shared Kool canvas
 * command stream.
 */
class KoolGraphicsEngine private constructor(
    private val commandBuffer: KoolCanvasCommandBuffer = KoolCanvasCommandBuffer(),
    private val targetTexture: Texture? = null,
    private val targetMode: RenderTargetMode = RenderTargetMode.DEFAULT,
    private val textureStore: KoolCanvasTextureStore = KoolCanvasTextureRegistry,
    private val targetStates: MutableMap<Texture, KoolTargetState> = IdentityHashMap(),
    private val textureMetadata: MutableMap<Texture, TextureMetadata> = WeakHashMap(),
    private val assetBytes: (String) -> ByteArray? = ::readAssetBytesFromFileSystem,
    private val enableTextureAtlas: Boolean = false,
    /**
     * True for a [RenderTargetMode.GPU_TARGET]: the frame is replayed by the GPU render thread instead
     * of being rasterised on the CPU, so recording must not bake CPU pixel/frame snapshots of nested
     * targets into it (see [freezeTextureForOffscreenDraw]).
     */
    private val gpuReplayTarget: Boolean = false,
) : GraphicsEngine {
    override fun backendCapabilities(): GraphicsBackendCapabilities = BACKEND_CAPABILITIES

    override fun prefersDirectLayerBufferRendering(): Boolean = true

    // CPU pixels and GPU uploads have explicit owners; a resync does not need a full collection.
    override fun requestsEagerGcOnLevelReload(): Boolean = false

    /**
     * Kool has a separate team-color texture path, but does not execute the
     * legacy [ShaderProgram] post-processing contract. Keeping these
     * capabilities separate prevents the core from routing the Kool backend
     * through the original framebuffer/shader pipeline.
     */
    override fun supportsShaderEffects(): Boolean = false

    override fun supportsTeamShaders(): Boolean = true

    constructor(
        textureStore: KoolCanvasTextureStore = KoolCanvasTextureRegistry,
        assetBytes: (String) -> ByteArray? = ::readAssetBytesFromFileSystem,
        enableTextureAtlas: Boolean = false,
    ) : this(
        textureStore = textureStore,
        targetStates = IdentityHashMap(),
        assetBytes = assetBytes,
        enableTextureAtlas = enableTextureAtlas,
    )

    private var viewport = KoolCanvasViewport(0, 0)
    private val fallbackTexture = Texture().apply {
        p = 1
        q = 1
        updateCenter()
    }
    private var textureAtlas: TextureAtlas? = null
    private var generatedTextureSerial = 0
    private var lastCommittedFrameSnapshot: FrameSnapshot? = null
    /** Diagnostic: 0 = GPU offscreen pass path taken, 1 = raster/other path taken. */
    private var gpuTargetPathCounts = LongArray(2)
    /** Diagnostic: 0 = mode is not GPU_TARGET, 1 = GPU_TARGET but no installer, 2 = GPU pass taken. */
    private var gpuTargetModality = LongArray(3)
    /** Diagnostic: commits per `RenderTargetMode` ordinal (DEFAULT, IMMEDIATE, GPU_TARGET, ...). */
    private val gpuTargetModeTotals = LongArray(RenderTargetMode.entries.size)
    /** Diagnostic: commits per "WxH/MODE" key, to name which target is actually being flushed. */
    private val gpuTargetShapeKeys = HashMap<String, Long>()
    private var gpuTargetFlushCalls = 0L
    /** Diagnostic: the commit-path counters below are only maintained while the canvas trace is on. */
    private val gpuTargetDiagnostics: Boolean get() = CanvasRenderStageTrace.enabled
    /** Diagnostic: `p()` routes - 0 = no target texture, 1 = target texture. */
    private val commitRouteCounts = LongArray(2)
    private val commitRouteKeys = HashMap<String, Long>()
    private var commitRouteCalls = 0L

    /** Recorded-content signature of the last GPU-target commit, used to skip re-freezing identical content. */
    private var lastFrozenSignature: Int = 0
    private var lastFrozenViewport: KoolCanvasViewport? = null
    private var lastFrozenFrame: KoolCanvasFrame? = null
    private var lastFrozenAuxiliaryIds: List<KoolCanvasTextureId> = emptyList()
    /** Diagnostic: how often the recorded signature allowed skipping a GPU-target freeze. */
    internal var flushSkipChecks: Long = 0L
        private set
    internal fun reportGpuTargetPaths() {
        if (!CanvasRenderStageTrace.enabled) return
        if (gpuTargetPathCounts[0] == 0L && gpuTargetPathCounts[1] == 0L) return
        println("[RWX canvas] gpuTargetPathsSummary offscreenPass=" + gpuTargetPathCounts[0] +
                " rasterOrOther=" + gpuTargetPathCounts[1])
        println("[RWX canvas] gpuTargetModality modeNotGpuTarget=" + gpuTargetModality[0] +
                " gpuTargetNoInstaller=" + gpuTargetModality[1] +
                " gpuPassTaken=" + gpuTargetModality[2])
    }
    internal var flushSkipHits: Long = 0L
        private set

    /**
     * Pixels of the newest commit of an [RenderTargetMode.IMMEDIATE] target. A commit that
     * rasterizes the same pixels again does not have to convert and upload the whole target.
     */

    private val pendingFrameDependencySnapshotIds = linkedSetOf<KoolCanvasTextureId>()
    private val pendingFrameDependencySnapshots = mutableMapOf<KoolCanvasTextureId, ImmediateFrameSnapshot>()
    private val pendingPixelDependencySnapshots = mutableMapOf<KoolCanvasTextureId, ImmediatePixelSnapshot>()
    private val registeredTexturePixelRevisions = mutableMapOf<KoolCanvasTextureId, TexturePixelRegistration>()
    private val registeredImmediatePixels = mutableMapOf<KoolCanvasTextureId, RegisteredImmediatePixels>()
    private val registeredImmediateFrames = mutableMapOf<KoolCanvasTextureId, RegisteredImmediateFrame>()
    private var directBlitActive = false
    private val drawRoleStack = ArrayDeque<Pair<KoolCanvasDrawRole, Long>>()
    var hudLayout: io.github.rwx.session.GameHudLayoutSnapshot? = null
        private set

    override fun captureHudLayout(engine: com.corrodinggames.rts.gameFramework.GameEngine) {
        hudLayout = io.github.rwx.session.captureGameHudLayout(engine)
    }

    override fun beginDrawRole(role: Int, unitId: Long) {
        drawRoleStack.addLast(commandBuffer.state.drawRole to commandBuffer.state.semanticUnitId)
        commandBuffer.setDrawRole(KoolCanvasDrawRole.entries.getOrElse(role) { KoolCanvasDrawRole.Generic }, unitId)
    }

    override fun endDrawRole() {
        val previous = drawRoleStack.pollLast() ?: (KoolCanvasDrawRole.Generic to -1L)
        commandBuffer.setDrawRole(previous.first, previous.second)
    }

    fun beginFrame(width: Int, height: Int) {
        releasePendingImmediateFrameSnapshots()
        viewport = KoolCanvasViewport(width.coerceAtLeast(0), height.coerceAtLeast(0))
        commandBuffer.beginFrame(viewport)
        drawRoleStack.clear()
        hudLayout = null
    }

    fun snapshot(): KoolCanvasFrame = commandBuffer.snapshot()

    /** Order-sensitive signature of this engine's pending recording; see [KoolCanvasCommandBuffer.signature]. */
    internal val pendingCommandSignature: Int get() = commandBuffer.signature

    override fun b(texture: Texture?): GraphicsEngine = b(texture, RenderTargetMode.DEFAULT)

    override fun b(texture: Texture?, mode: RenderTargetMode): GraphicsEngine {
        if (texture == null) {
            return KoolGraphicsEngine(
                textureStore = textureStore,
                targetStates = targetStates,
                textureMetadata = textureMetadata,
                assetBytes = assetBytes,
                enableTextureAtlas = enableTextureAtlas,
            )
        }
        val offscreen = KoolGraphicsEngine(
            commandBuffer = KoolCanvasCommandBuffer(textureBatches =
                mode == RenderTargetMode.GPU_TARGET && System.getenv("RWX_MAP_TEXTURE_BATCHES") == "1",
                rectBatches = mode == RenderTargetMode.GPU_TARGET &&
                    System.getenv("RWX_MAP_TEXTURE_BATCHES") == "1" && System.getenv("RWX_MAP_RECT_BATCHES") != "0",
                fogBatches = mode == RenderTargetMode.GPU_TARGET && System.getenv("RWX_MAP_FOG_BATCHES") == "1"),
            targetTexture = texture,
            targetMode = mode,
            textureStore = textureStore,
            targetStates = targetStates,
            textureMetadata = textureMetadata,
            assetBytes = assetBytes,
            // Offscreen cells draw terrain one tile at a time (measured: ~6 570 `drawTexture` commands per
            // 512x512 cell, i.e. ~40 screen pixels per draw). Textures packed into one atlas share a single
            // canvas texture id, which is what lets the renderer's instanced run merge the consecutive
            // draws instead of issuing one command each. `RWX_OFFSCREEN_TEXTURE_ATLAS=0` restores the
            // previous behaviour.
            enableTextureAtlas = OFFSCREEN_TEXTURE_ATLAS,
            gpuReplayTarget = mode == RenderTargetMode.GPU_TARGET,
        )
        targetStates.remove(texture)?.releaseAuxiliaryTextures(textureStore)
        targetStates[texture] = KoolTargetState(offscreen, mode)
        return offscreen.apply {
            beginFrame(texture.width().coerceAtLeast(1), texture.height().coerceAtLeast(1))
        }
    }

    override fun a(lock: Lock?) {
        lock?.lock()
    }

    override fun b(lock: Lock?) {
        lock?.unlock()
    }

    override fun a(z: Boolean) {
        directBlitActive = z
    }

    override fun f() {
        directBlitActive = false
    }

    override fun a(i: Int, i2: Int) {
        viewport = KoolCanvasViewport(i.coerceAtLeast(0), i2.coerceAtLeast(0))
    }

    override fun m(): Int = viewport.width

    override fun n(): Int = viewport.height

    override fun r(): Texture = fallbackTexture

    override fun a(i: Int): Texture = a(i, true)

    override fun a(i: Int, z: Boolean): Texture {
        val drawableName = drawableNamesById[i]
        // Packaged desktop assets can live in the platform's extracted resource directory,
        // outside the working directory. Resolve through the injected loader before deciding
        // that a drawable is missing, and retain the bytes we used to select the extension.
        val asset = drawableName?.let { name ->
            imageFileNames(name).firstNotNullOfOrNull { fileName ->
                val path = "drawable/$fileName"
                readAssetBytes(path)?.let { path to it }
            }
        }
        val assetPath = asset?.first
        val imageBytes = asset?.second
        val decodedImage = asset?.let { (path, bytes) -> decodeImage(bytes, path) }
        val size = decodedImage?.let { it.width to it.height }
            ?: imageBytes
                ?.inputStream()
                ?.let(::readImageSize)
            ?: DEFAULT_TEXTURE_SIZE
        return createLegacyTexture(
            width = size.first,
            height = size.second,
            sourceKey = drawableName?.let { "drawable/$it" } ?: "drawable-id/$i",
            assetPath = assetPath,
            argbPixels = decodedImage?.argbPixels,
            encodedBytes = imageBytes,
        )
    }

    override fun a(inputStream: InputStream?, z: Boolean): Texture {
        val path = (inputStream as? AssetInputStream)?.getPath()
        val assetPath = path?.let(::assetPathForImagePath)
        val imageBytes = assetPath?.let(::readAssetBytes) ?: inputStream?.readBytes()
        val decodedImage = imageBytes?.let { bytes -> decodeImage(bytes, assetPath ?: path.orEmpty()) }
        val size = decodedImage?.let { it.width to it.height }
            ?: imageBytes?.inputStream()?.let(::readImageSize)
            ?: DEFAULT_TEXTURE_SIZE
        return createLegacyTexture(
            width = size.first,
            height = size.second,
            sourceKey = path ?: "stream/${generatedTextureSerial++}",
            assetPath = assetPath,
            argbPixels = decodedImage?.argbPixels,
            encodedBytes = imageBytes,
        )
    }

    override fun a(i: Int, i2: Int, z: Boolean): Texture = b(i, i2, z)

    override fun b(i: Int, i2: Int, z: Boolean): Texture =
        createLegacyTexture(
            width = i,
            height = i2,
            sourceKey = "generated/${i}x$i2/${generatedTextureSerial++}",
            assetPath = null,
            hasAlpha = z,
        ).also { texture ->
            if (i == BACKEND_CAPABILITIES.fixedLayerBufferPixelSize && i2 == i) {
                (textureStore as? KoolCanvasCpuTextureStore)?.preallocateTargetPixels(i, i2)
            }
            // A generated target has valid empty contents before its first commit. An empty
            // Frame draws nothing, so it cannot clear the scene underneath this pending target.
            textureStore.registerFrame(texture.toCanvasTextureId(), KoolCanvasFrame(
                KoolCanvasViewport(texture.width(), texture.height()), emptyList(),
            ))
        }

    override fun o() {
        commandBuffer.clear(KoolCanvasColor.Transparent, KoolCanvasBlendMode.ClearAlpha)
    }

    override fun p() {
        if (CanvasRenderStageTrace.enabled) {
            // `p()` is the commit entry point on the engine that owns the target. Which of the two commit
            // routes it takes, and with which mode, is exactly what decides whether a cell ever reaches the
            // GPU offscreen pass.
            commitRouteCounts[if (targetTexture == null) 0 else 1]++
            if (targetTexture != null) {
                val key = targetTexture!!.width().toString() + "x" + targetTexture!!.height() + "/" +
                    targetMode.name
                commitRouteKeys[key] = (commitRouteKeys[key] ?: 0L) + 1L
            }
            if (commitRouteCalls++ % 500L == 0L) {
                println("[RWX canvas] commitRoutes pNoTarget=" + commitRouteCounts[0] +
                        " pWithTarget=" + commitRouteCounts[1] + " top=" +
                        commitRouteKeys.entries.sortedByDescending { it.value }.take(4)
                            .joinToString(" ") { it.key + "x" + it.value })
            }
        }
        commitTargetTextureFrame()
    }

    override fun q() {
        targetTexture?.let { texture ->
            targetStates[texture]
                ?.takeIf { it.engine === this }
                ?.commitEnabled = false
        }
    }

    override fun a(shaderProgram: ShaderProgram?) = Unit

    override fun b(i: Int) {
        commandBuffer.clear(KoolCanvasColor(i))
    }

    override fun a(i: Int, mode: KoolCanvasBlendMode?) {
        commandBuffer.clear(KoolCanvasColor(i), mode ?: KoolCanvasBlendMode.SourceOver)
    }

    override fun a(rect: Rect?, paint: KoolPaint?) {
        rect?.let { commandBuffer.drawRect(it.toCanvasRect(), paint.toCanvasPaint().withDirectBlitRectBlend()) }
    }

    override fun b(rect: Rect?, paint: KoolPaint?) {
        a(rect, paint)
    }

    override fun c(rect: Rect?, paint: KoolPaint?) {
        rect?.let {
            commandBuffer.drawRect(
                KoolCanvasRect(
                    left = it.a.toFloat(),
                    top = it.b.toFloat(),
                    right = (it.a + it.c).toFloat(),
                    bottom = (it.b + it.d).toFloat(),
                ),
                paint.toCanvasPaint().withDirectBlitRectBlend(),
            )
        }
    }

    override fun a(rectF: RectF?, paint: KoolPaint?) {
        rectF?.let { commandBuffer.drawRect(it.toCanvasRect(), paint.toCanvasPaint().withDirectBlitRectBlend()) }
    }

    override fun a(rect: Rect?) {
        if (rect == null) {
            return
        }
        commandBuffer.clip(rect.toCanvasRect())
    }

    override fun a(rectF: RectF?) {
        if (rectF == null) {
            return
        }
        commandBuffer.clip(rectF.toCanvasRect())
    }

    override fun a(f: Float, f2: Float, f3: Float, paint: KoolPaint?) {
        commandBuffer.drawCircle(KoolCanvasPoint(f, f2), f3, paint.toCanvasPaint())
    }

    override fun b(f: Float, f2: Float, f3: Float, paint: KoolPaint?) {
        a(f, f2, f3, paint)
    }

    override fun a(f: Float, f2: Float, f3: Float, f4: Float, paint: KoolPaint?) {
        commandBuffer.drawLine(KoolCanvasPoint(f, f2), KoolCanvasPoint(f3, f4), paint.toCanvasPaint())
    }

    override fun a(fArr: FloatArray?, i: Int, i2: Int, paint: KoolPaint?) {
        if (fArr == null || i2 <= 0) {
            return
        }
        val end = (i + i2).coerceAtMost(fArr.size - 1)
        var index = i.coerceAtLeast(0)
        val pointPaint = paint.toCanvasPaint().copy(style = KoolCanvasPaintStyle.Fill)
        val size = pointPaint.strokeWidth.coerceAtLeast(1f)
        while (index + 1 <= end) {
            val x = fArr[index]
            val y = fArr[index + 1]
            commandBuffer.drawRect(
                KoolCanvasRect(x, y, x + size, y + size),
                pointPaint,
            )
            index += 2
        }
    }

    override fun i() {
        commandBuffer.save()
    }

    override fun j() {
        commandBuffer.restore()
    }

    override fun k() {
        commandBuffer.save()
    }

    override fun l() {
        commandBuffer.restore()
    }

    override fun a(f: Float, f2: Float, f3: Float) {
        commandBuffer.rotate(f, f2, f3)
    }

    override fun a(f: Float, f2: Float) {
        commandBuffer.scale(f, f2)
    }

    override fun a(f: Float, f2: Float, f3: Float, f4: Float) {
        commandBuffer.scale(f, f2, f3, f4)
    }

    override fun b(f: Float, f2: Float) {
        commandBuffer.translate(f, f2)
    }

    override fun a(str: String?, f: Float, f2: Float, paint: KoolPaint?) {
        commandBuffer.drawText(str.orEmpty(), KoolCanvasPoint(f, f2), paint.toCanvasPaint())
    }

    override fun a(str: String?, f: Float, f2: Float, paint: KoolPaint?, paint2: KoolPaint?, f3: Float) {
        val text = str.orEmpty()
        val width = b(text, paint)
        val height = a(text, paint)
        val left = when (paint?.textAlign()) {
            KoolPaint.Align.CENTER -> f - (width * 0.5f) - f3
            KoolPaint.Align.RIGHT -> f - width - f3
            else -> f - f3
        }
        val top = f2 - f3
        commandBuffer.drawRect(
            KoolCanvasRect(left, top, left + width + (f3 * 2f), f2 + height + f3),
            paint2.toCanvasPaint(),
        )
        a(text, f, f2 + height, paint)
    }

    override fun a(str: String?, paint: KoolPaint?): Int =
        KoolCanvasFontRegistry.lineHeight(
            sizePts = paint?.textSize()?.coerceAtLeast(1f) ?: DEFAULT_TEXT_SIZE,
            key = paint.typefaceKey(),
        ).roundToInt().coerceAtLeast(1)

    override fun b(str: String?, paint: KoolPaint?): Int =
        KoolCanvasFontRegistry.textWidth(
            text = str.orEmpty(),
            sizePts = paint?.textSize()?.coerceAtLeast(1f) ?: DEFAULT_TEXT_SIZE,
            key = paint.typefaceKey(),
        ).roundToInt().coerceAtLeast(if (str.isNullOrEmpty()) 0 else 1)

    override fun a(texture: Texture?, f: Float, f2: Float, f3: Float, paint: KoolPaint?) {
        if (texture == null) return
        k()
        a(f3 + 90f, f, f2)
        val left = f - texture.r
        val top = f2 - texture.s
        drawTexture(
            texture = texture,
            destination = KoolCanvasRect(
                left = left,
                top = top,
                right = left + texture.width(),
                bottom = top + texture.height(),
            ),
            source = texture.fullSourceRect(),
            paint = paint,
        )
        l()
    }

    override fun a(texture: Texture?, rect: Rect?, f: Float, f2: Float, f3: Float, paint: KoolPaint?) {
        if (texture == null || rect == null) return
        k()
        a(f3, f, f2)
        val halfWidth = rect.width() * 0.5f
        val halfHeight = rect.height() * 0.5f
        drawTexture(
            texture = texture,
            destination = KoolCanvasRect(
                left = f - halfWidth,
                top = f2 - halfHeight,
                right = f + halfWidth,
                bottom = f2 + halfHeight,
            ),
            source = rect.toCanvasRect(),
            paint = paint,
        )
        l()
    }

    override fun a(texture: Texture?, rect: Rect?, rect2: Rect?, paint: KoolPaint?) {
        if (texture == null || rect == null || rect2 == null) return
        drawTexture(texture, rect2.toCanvasRect(), rect.toCanvasRect(), paint)
    }

    override fun b(texture: Texture?, rect: Rect?, rect2: Rect?, paint: KoolPaint?) {
        a(texture, rect, rect2, paint)
    }

    override fun a(texture: Texture?, source: Rect?, destination: RectF?, paint: KoolPaint?, repeat: Int) {
        if (texture == null || source == null || destination == null) return
        if (repeat <= 1) {
            a(texture, source, destination, paint)
            return
        }
        drawTextureRepeat(
            texture = texture,
            source = source.toCanvasRect(),
            destination = destination.toCanvasRect(),
            repeat = repeat,
            paint = paint,
        )
    }

    override fun a(texture: Texture?, rect: Rect?, rectF: RectF?, paint: KoolPaint?) {
        if (texture == null || rect == null || rectF == null) return
        drawTexture(texture, rectF.toCanvasRect(), rect.toCanvasRect(), paint)
    }

    override fun a(texture: Texture?, f: Float, f2: Float, paint: KoolPaint?) {
        if (texture == null) return
        drawTexture(
            texture = texture,
            destination = KoolCanvasRect(
                left = f - texture.t,
                top = f2 - texture.u,
                right = f + texture.t,
                bottom = f2 + texture.u,
            ),
            source = texture.fullSourceRect(),
            paint = paint,
        )
    }

    override fun a(texture: Texture?, f: Float, f2: Float, paint: KoolPaint?, f3: Float, f4: Float) {
        if (texture == null) return
        k()
        b(f, f2)
        a(f4, f4)
        a(f3, f, f2)
        drawTexture(
            texture = texture,
            destination = KoolCanvasRect.fromSize(texture.width().toFloat(), texture.height().toFloat()),
            source = texture.fullSourceRect(),
            paint = paint,
        )
        l()
    }

    override fun b(texture: Texture?, f: Float, f2: Float, paint: KoolPaint?) {
        if (texture == null) return
        drawTexture(
            texture = texture,
            destination = KoolCanvasRect(f, f2, f + texture.width(), f2 + texture.height()),
            source = texture.fullSourceRect(),
            paint = paint,
        )
    }

    override fun a(texture: Texture?, rect: Rect?, paint: KoolPaint?) {
        if (texture == null || rect == null) return
        drawTiledTexture(
            texture = texture,
            destination = rect.toCanvasRect(),
            offsetX = 0f,
            offsetY = 0f,
            repeatInsetX = 0,
            repeatInsetY = 0,
            paint = paint,
        )
    }

    override fun a(texture: Texture?, rect: Rect?, paint: KoolPaint?, i: Int, i2: Int, i3: Int, i4: Int) {
        if (texture == null || rect == null) return
        drawTiledTexture(
            texture = texture,
            destination = rect.toCanvasRect(),
            offsetX = i.toFloat(),
            offsetY = i2.toFloat(),
            repeatInsetX = i3,
            repeatInsetY = i4,
            paint = paint,
        )
    }

    override fun a(texture: Texture?, rectF: RectF?, paint: KoolPaint?, f: Float, f2: Float, i: Int, i2: Int) {
        if (texture == null || rectF == null) return
        drawTiledTexture(
            texture = texture,
            destination = rectF.toCanvasRect(),
            offsetX = f,
            offsetY = f2,
            repeatInsetX = i,
            repeatInsetY = i2,
            paint = paint,
        )
    }

    override fun a(texture: Texture?, file: File?) {
        if (texture == null || file == null) return
        val resolvedTexture = texture.resolveForKool()
        val width = resolvedTexture.width().coerceAtLeast(1)
        val height = resolvedTexture.height().coerceAtLeast(1)
        val pixels = resolvedTexture.argbPixelsCopy
            ?: IntArray(width * height)
        file.parentFile?.mkdirs()
        file.writeBytes(encodePng(width, height, pixels))
    }

    private fun createLegacyTexture(
        width: Int,
        height: Int,
        sourceKey: String,
        assetPath: String?,
        argbPixels: IntArray? = null,
        hasAlpha: Boolean = false,
        encodedBytes: ByteArray? = null,
    ): Texture {
        val texture = KoolBackendTexture(::releaseKoolTexture).apply {
            p = width.coerceAtLeast(1)
            q = height.coerceAtLeast(1)
            m = hasAlpha || argbPixels?.any { (it ushr 24) != 0xff } == true
            setSourceName(sourceKey)
            updateCenter()
        }
        if (argbPixels != null) {
            val pixels = argbPixels.copyOf()
            // Decoded image: its transparent texels carry an arbitrary RGB, so expand opaque
            // neighbours over them once per pixel revision.
            texture.alphaBleedRequired = true
            // Team-color initialization releases the editable buffer before some units (ships)
            // derive their shadows. Keep decoded source pixels available for later CPU reads.
            texture.setCommittedArgbPixels(pixels)
            textureStore.registerArgb(texture.toCanvasTextureId(), texture.width(), texture.height(), pixels)
        } else if (assetPath != null) {
            textureStore.registerAssetSnapshot(texture.toCanvasTextureId(), assetPath, encodedBytes)
        }
        return texture
    }

    private fun readAssetBytes(assetPath: String): ByteArray? =
        assetBytes(assetPath.trimAssetPath()) ?: readAssetBytesFromFileSystem(assetPath)

    private fun decodeImage(bytes: ByteArray, sourceName: String): DecodedImage? =
        readPngImage(bytes) ?: readPlatformImage(bytes, sourceName)

    private fun flushTargetTextureFrame(): TargetTextureCommit? {
        return targetTexture?.let { texture ->
            val id = texture.toCanvasTextureId()
            // A GPU target re-records the same content on most commits: the freeze content check reported
            // `same` 100 728 against `changed` 3 134 (97%) on a low-zoom run, but that check runs *after*
            // the frozen copy, so `engine-freeze` still cost 44.8 s of a 94 s run. The recorded signature
            // makes the same decision from O(1) state before the copy.
            if (targetMode == RenderTargetMode.GPU_TARGET &&
                FrozenCanvasGpuResources.gpuTargetInstaller != null
            ) {
                flushSkipChecks++
                if (lastFrozenSignature == commandBuffer.signature &&
                    lastFrozenViewport == commandBuffer.viewport &&
                    lastFrozenFrame != null
                ) {
                    flushSkipHits++
                    if (flushSkipHits == 1L) println("[RWX canvas] gpuTargetFreezeSkip first hit after " + flushSkipChecks + " checks")
                    return@let TargetTextureCommit(id, lastFrozenAuxiliaryIds)
                }
            }
            // The caller re-flushes every sampled target on every draw, and almost all of those flushes
            // find nothing recorded (measured on the europe-15p replay: 2 264 189 of 2 275 257, costing
            // 3.0 s of snapshot time in one pan run). An empty commit takes the same branch as an empty
            // snapshot, so it can be built without copying the command list into a new frame; the
            // `registerFrame` call is kept so the stored revision still advances exactly as before.
            if (commandBuffer.isEmpty) {
                releasePendingImmediateFrameSnapshots()
                val previous = lastCommittedFrameSnapshot ?: return@let null
                if (targetMode != RenderTargetMode.GPU_TARGET ||
                    FrozenCanvasGpuResources.gpuTargetInstaller == null
                ) {
                    textureStore.registerFrame(id, previous.frame)
                    if (textureStore is KoolCanvasCpuTextureStore && targetMode == RenderTargetMode.IMMEDIATE) {
                        val published = registeredImmediateFrames[id]
                        val registeredFrame = textureStore.frame(id)
                        if (published != null && registeredFrame != null) {
                            registeredImmediateFrames[id] = published.copy(frame = WeakReference(registeredFrame))
                        }
                    }
                }
                return@let TargetTextureCommit(id, previous.auxiliaryIds)
            }
            val snapshotStart = if (KoolCanvasCommandProfile.enabled) System.nanoTime() else 0L
            val frame = snapshot()
            if (snapshotStart != 0L) {
                KoolCanvasCommandProfile.offscreenFlush(
                    id.value, frame.commands.size, System.nanoTime() - snapshotStart,
                )
            }
            if (frame.commands.isEmpty()) {
                releasePendingImmediateFrameSnapshots()
                val previous = lastCommittedFrameSnapshot ?: return@let null
                if (targetMode == RenderTargetMode.GPU_TARGET &&
                    FrozenCanvasGpuResources.gpuTargetInstaller != null
                ) {
                    // An empty re-commit means "content unchanged". Re-registering the previous frame
                    // here would replace the sampleable GPU version with a replay frame and send the
                    // renderer back to the per-sampling-point expansion this target exists to avoid.
                    return@let TargetTextureCommit(id, previous.auxiliaryIds)
                }
                textureStore.registerFrame(id, previous.frame)
                if (textureStore is KoolCanvasCpuTextureStore && targetMode == RenderTargetMode.IMMEDIATE) {
                    // An empty re-commit changes the stored Frame object, but must retain the
                    // pixel revision from the actual fallback. Legacy edits may have happened
                    // since then and must still invalidate this registration.
                    val published = registeredImmediateFrames[id]
                    val registeredFrame = textureStore.frame(id)
                    if (published != null && registeredFrame != null) {
                        registeredImmediateFrames[id] = published.copy(frame = WeakReference(registeredFrame))
                    }
                }
                return@let TargetTextureCommit(id, previous.auxiliaryIds)
            }
            // A GPU target keeps the same recorded frame but hands it to an offscreen pass instead of
            // rasterising it. Without a render-thread owner (headless runs, tests, other backends) the
            // CPU raster still applies, so the target stays correct everywhere.
            val gpuTarget = targetMode == RenderTargetMode.GPU_TARGET &&
                FrozenCanvasGpuResources.gpuTargetInstaller != null
            if (CanvasRenderStageTrace.enabled) {
                gpuTargetPathCounts[if (gpuTarget) 0 else 1]++
            }
            // Diagnostic: the GPU offscreen pass is the structural fix for the cell cost, so when it does
            // not engage, record which half of its guard failed. `modeNotGpuTarget` means the game asked
            // for a CPU target; `noInstaller` means the host never wired the render-thread installer.
            // Gated on the trace: these counters and the string key below otherwise cost work on the commit
            // path of every render target, which is not a diagnostic worth paying for by default.
            if (gpuTargetDiagnostics) {
                val modalityIndex = when {
                    targetMode != RenderTargetMode.GPU_TARGET -> 0
                    FrozenCanvasGpuResources.gpuTargetInstaller == null -> 1
                    else -> 2
                }
                gpuTargetModality[modalityIndex]++
                gpuTargetModeTotals[targetMode.ordinal]++
                gpuTargetFlushCalls++
                // Identify commits by real dimensions and mode. The cell texture is sized from
                // `cellBufferPixelSize`, so assuming 512 was wrong and hid which target these are.
                val key = texture.width().toString() + "x" + texture.height() + "/" + targetMode.name
                gpuTargetShapeKeys[key] = (gpuTargetShapeKeys[key] ?: 0L) + 1L
            }
            if (gpuTargetDiagnostics && gpuTargetFlushCalls % 500L == 0L) {
                // Rows carry this engine's identity and its own mode/size. The counters are per instance, and
                // reading one instance's numbers as the whole process is twice what sent this investigation
                // the wrong way (round 28's "500 IMMEDIATE" came from a different engine than the cells).
                val instance = System.identityHashCode(this)
                CanvasRenderStageTrace.recordCompleted(
                    "gpu-target-modality@" + instance, 0L, 0L,
                    gpuTargetModality[0], gpuTargetModality[1], gpuTargetModality[2],
                )
                CanvasRenderStageTrace.recordCompleted(
                    "gpu-target-mode@" + instance, 0L, 0L,
                    targetMode.ordinal.toLong(),
                    (targetTexture?.width() ?: 0).toLong(),
                    (targetTexture?.height() ?: 0).toLong(),
                )
            }
            // Any non-GPU target replaces the sampled content, so the skip cache must stop applying.
            if (!gpuTarget) lastFrozenFrame = null
            val raster = if (targetMode == RenderTargetMode.IMMEDIATE ||
                (targetMode == RenderTargetMode.GPU_TARGET && !gpuTarget)
            ) {
                rasterizeTargetFrame(texture, frame, visiting = setOf(id))
            } else {
                null
            }
            if (raster != null) try {
                val pixels = raster.pixels
                // The store owns this current image. A raw cached array could already have been
                // returned to the pool after an external replacement or unsupported fallback.
                val existingImage = textureStore.argbImageView(id)
                if (existingImage?.pixels?.contentEquals(pixels) == true && (textureStore !is KoolCanvasCpuTextureStore ||
                            registeredImmediatePixels[id]?.matchesTexture(texture, existingImage,
                                legacyRegistration = if (TEXTURE_METADATA_REUSE_ENABLED) null else texture.pixelRegistration()) == true)) {
                    // This commit rasterized exactly the pixels that are already on the GPU (a layer
                    // buffer cell re-commits unchanged content all the time). Re-registering them
                    // would convert the whole target to RGBA and upload it again for no reason.
                    lastCommittedFrameSnapshot = null
                    releasePendingImmediateFrameSnapshots()
                    commandBuffer.beginFrame(frame.viewport)
                    return@let TargetTextureCommit(id, emptyList())
                }
                val owner = raster.owner
                if (owner != null) {
                    val textureOwner = owner.retain()
                    texture.setImmutableArgbPixels(pixels, textureOwner::close)
                } else {
                    texture.setCommittedArgbPixels(pixels)
                }
                texture.setPremultipliedAlpha(false)
                if (textureStore is KoolCanvasCpuTextureStore) {
                    if (owner != null) {
                        textureStore.registerPooledArgb(id, texture.width().coerceAtLeast(1),
                            texture.height().coerceAtLeast(1), owner, texture.alphaBleedRequired)
                    } else {
                        textureStore.registerOwnedArgb(id, texture.width().coerceAtLeast(1),
                            texture.height().coerceAtLeast(1), pixels, texture.alphaBleedRequired)
                    }
                } else {
                    textureStore.registerArgb(
                        id = id,
                        width = texture.width().coerceAtLeast(1),
                        height = texture.height().coerceAtLeast(1),
                        argbPixels = pixels,
                        alphaBleed = texture.alphaBleedRequired,
                    )
                }
                val registration = texture.pixelRegistration()
                registeredTexturePixelRevisions[id] = registration
                lastCommittedFrameSnapshot = null
                registeredImmediateFrames.remove(id)
                if (textureStore is KoolCanvasCpuTextureStore) {
                    rememberImmediatePixels(id, registration, texture.alphaBleedRequired)
                }
                commandBuffer.beginFrame(frame.viewport)
                releasePendingImmediateFrameSnapshots()
                TargetTextureCommit(id, emptyList())
            } finally {
                raster.owner?.close()
            } else if (gpuTarget) {
                // A GPU target is replayed by the render thread, so it never needs the CPU pixel
                // snapshots that `snapshotFrameDependencies` builds: those exist so a CPU raster can
                // read a nested target's pixels, and their pin/release pairing lives in the raster
                // path. Wiring them up here left the snapshots referenced by in-flight frames but
                // unreleasable (measured: the candidate A/B run died with
                // "Texture2d .../snapshot-1172/... is already released").
                val recorded = frame.withPersistedTargetContents(lastCommittedFrameSnapshot?.frame)
                textureStore.registerGpuTargetFrame(
                    id,
                    recorded,
                    texture.width().coerceAtLeast(1),
                    texture.height().coerceAtLeast(1),
                )
                // Keep the plain recording for a later incremental commit; snapshot ids would drag the
                // raster-specific lifetime back into this path.
                lastCommittedFrameSnapshot = FrameSnapshot(recorded, emptyList())
                lastFrozenSignature = commandBuffer.signature
                lastFrozenViewport = frame.viewport
                lastFrozenFrame = recorded
                lastFrozenAuxiliaryIds = emptyList()
                pendingFrameDependencySnapshotIds.clear()
                pendingFrameDependencySnapshots.clear()
                pendingPixelDependencySnapshots.clear()
                commandBuffer.beginFrame(frame.viewport)
                TargetTextureCommit(id, emptyList())
            } else {
                val committedFrame = frame.withPersistedTargetContents(lastCommittedFrameSnapshot?.frame)
                val snapshot = snapshotFrameDependencies(committedFrame)
                val commitSnapshot = FrameSnapshot(
                    frame = snapshot.frame,
                    auxiliaryIds = (snapshot.auxiliaryIds + pendingFrameDependencySnapshotIds).distinct(),
                )
                textureStore.registerFrame(id, commitSnapshot.frame)
                if (textureStore is KoolCanvasCpuTextureStore && targetMode == RenderTargetMode.IMMEDIATE) {
                    textureStore.frame(id)?.let { registeredFrame ->
                        registeredImmediateFrames[id] = RegisteredImmediateFrame(
                            texture.pixelRegistration(), texture.alphaBleedRequired, WeakReference(registeredFrame),
                        )
                    }
                }
                lastCommittedFrameSnapshot = commitSnapshot
                pendingFrameDependencySnapshotIds.clear()
                pendingFrameDependencySnapshots.clear()
                pendingPixelDependencySnapshots.clear()
                commandBuffer.beginFrame(frame.viewport)
                TargetTextureCommit(id, commitSnapshot.auxiliaryIds)
            }
        }
    }

    private fun KoolCanvasFrame.withPersistedTargetContents(previousFrame: KoolCanvasFrame?): KoolCanvasFrame {
        if (previousFrame == null || replacesTargetContents()) {
            return this
        }
        return copy(commands = previousFrame.commands + commands)
    }

    private fun KoolCanvasFrame.replacesTargetContents(): Boolean {
        val firstCommand = commands.firstOrNull() as? KoolCanvasCommand.Clear ?: return false
        return firstCommand.renderTarget == null && firstCommand.blendMode != KoolCanvasBlendMode.ClearAlpha
    }

    private fun snapshotFrameDependencies(
        frame: KoolCanvasFrame,
        visiting: Set<KoolCanvasTextureId> = emptySet(),
        snapshotsByTexture: MutableMap<KoolCanvasTextureId, FrameDependencySnapshot> = mutableMapOf(),
        cloneExistingSnapshots: Boolean = false,
    ): FrameSnapshot {
        val auxiliaryIds = linkedSetOf<KoolCanvasTextureId>()
        val frozenCommands = frame.commands.map { command ->
            if (command !is KoolCanvasCommand.DrawTexture || command.texture.id in visiting) {
                command
            } else {
                val nestedFrame = textureStore.frame(command.texture.id)
                if (nestedFrame == null) {
                    if (command.texture.id.isFrameSnapshotId && textureStore is KoolCanvasCpuTextureStore &&
                        textureStore.argbImageView(command.texture.id) != null) {
                        auxiliaryIds += command.texture.id
                    }
                    return@map command
                }
                if (command.texture.id.isFrameSnapshotId && !cloneExistingSnapshots) {
                    auxiliaryIds += command.texture.id
                    auxiliaryIds += collectFrameSnapshotIds(
                        frame = nestedFrame,
                        visiting = visiting + command.texture.id,
                        collected = linkedSetOf(command.texture.id),
                    )
                    command
                } else {
                    val dependencySnapshot = snapshotsByTexture.getOrPut(command.texture.id) {
                        val nestedSnapshot = snapshotFrameDependencies(
                            frame = nestedFrame,
                            visiting = visiting + command.texture.id,
                            snapshotsByTexture = snapshotsByTexture,
                            cloneExistingSnapshots = cloneExistingSnapshots,
                        )
                        val snapshotId = command.texture.id.snapshotId()
                        textureStore.registerFrame(snapshotId, nestedSnapshot.frame)
                        FrameDependencySnapshot(
                            textureId = snapshotId,
                            auxiliaryIds = nestedSnapshot.auxiliaryIds + snapshotId,
                        )
                    }
                    auxiliaryIds += dependencySnapshot.auxiliaryIds
                    command.copy(texture = command.texture.copy(id = dependencySnapshot.textureId))
                }
            }
        }
        return FrameSnapshot(
            frame = frame.copy(commands = frozenCommands),
            auxiliaryIds = auxiliaryIds.toList(),
        )
    }

    private fun collectFrameSnapshotIds(
        frame: KoolCanvasFrame,
        visiting: Set<KoolCanvasTextureId>,
        collected: LinkedHashSet<KoolCanvasTextureId> = linkedSetOf(),
        remainingDepth: Int = MAX_FRAME_SNAPSHOT_DEPENDENCY_DEPTH,
    ): List<KoolCanvasTextureId> {
        if (remainingDepth <= 0) {
            return emptyList()
        }
        frame.commands.forEach { command ->
            if (command !is KoolCanvasCommand.DrawTexture || command.texture.id in visiting) {
                return@forEach
            }
            val nestedFrame = textureStore.frame(command.texture.id)
            val isPixelSnapshot = textureStore is KoolCanvasCpuTextureStore &&
                textureStore.argbImageView(command.texture.id) != null
            if (command.texture.id.isFrameSnapshotId && (nestedFrame != null || isPixelSnapshot) &&
                collected.add(command.texture.id) && nestedFrame != null) {
                collectFrameSnapshotIds(
                    frame = nestedFrame,
                    visiting = visiting + command.texture.id,
                    collected = collected,
                    remainingDepth = remainingDepth - 1,
                )
            }
        }
        return collected.toList()
    }

    private data class RasterizedTargetPixels(val pixels: IntArray, val owner: KoolCanvasPixelPool.PixelOwner? = null)

    private fun rasterizeTargetFrame(
        texture: Texture,
        frame: KoolCanvasFrame,
        visiting: Set<KoolCanvasTextureId> = emptySet(),
    ): RasterizedTargetPixels? {
        val width = texture.width().coerceAtLeast(1)
        val height = texture.height().coerceAtLeast(1)
        // A full replacement discards the previous target pixels before drawing any geometry.
        // Avoid resolving and copying them, including the extra copy used to normalize the size.
        val replacesContents = frame.replacesTargetContents()
        val owner = (textureStore as? KoolCanvasCpuTextureStore)?.borrowTargetPixels(width, height)
        try {
            val pixels = if (owner != null) {
                // A leading full clear initializes even dirty pooled storage. For incremental
                // updates, copying the previous pixels initializes their prefix; only a missing
                // tail needs zeroing before alpha-only clears or geometry can read it.
                owner.pixels.also { destination ->
                    if (!replacesContents) {
                        val previous = texture.argbPixelsRef
                        val copied = minOf(previous?.size ?: 0, destination.size)
                        previous?.copyInto(destination, endIndex = copied)
                        if (copied < destination.size) destination.fill(0, copied, destination.size)
                    }
                }
            } else if (replacesContents) {
                IntArray(width * height)
            } else {
                texture.argbPixelsCopy?.copyOf(width * height) ?: IntArray(width * height)
            }
            val profile = CpuTargetProfile.createIfEnabled(texture, frame, width, height)
            val startedAt = if (profile != null) System.nanoTime() else 0L
            val result = if (PARALLEL_CELL_RASTER_ENABLED) {
                rasterizeCellStripes(frame, pixels, width, height, profile, owner == null && replacesContents)
                    ?: rasterizeFrameCommands(frame, pixels, width, height, visiting, profile,
                        zeroInitializedPixels = owner == null && replacesContents)
            } else rasterizeFrameCommands(frame, pixels, width, height, visiting, profile,
                zeroInitializedPixels = owner == null && replacesContents)
            profile?.log(System.nanoTime() - startedAt, result != null)
            if (result != null) return RasterizedTargetPixels(result, owner)
        } catch (error: Throwable) {
            owner?.close()
            throw error
        }
        owner?.close()
        return null
    }

    private fun rasterizeFrameToImage(
        id: KoolCanvasTextureId,
        frame: KoolCanvasFrame,
        width: Int,
        height: Int,
        visiting: Set<KoolCanvasTextureId>,
    ): KoolCanvasArgbImage? {
        if (id in visiting) {
            return textureStore.argbImageView(id)
        }
        val pixels = IntArray(width.coerceAtLeast(1) * height.coerceAtLeast(1))
        return rasterizeFrameCommands(
            frame = frame,
            pixels = pixels,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            visiting = visiting + id,
            profile = null,
        )?.let {
            KoolCanvasArgbImage(
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                pixels = it,
                premultipliedAlpha = false,
            )
        }
    }

    /** Resolve and pin on the owner; each worker executes the original sequence on disjoint rows. */
    private fun rasterizeCellStripes(
        frame: KoolCanvasFrame,
        pixels: IntArray,
        width: Int,
        height: Int,
        profile: CpuTargetProfile?,
        zeroInitializedPixels: Boolean,
    ): IntArray? = rasterizeCellStripesWithPolicy(
        frame, pixels, width, height, profile, zeroInitializedPixels, ADAPTIVE_CELL_RASTER_ENABLED,
    )

    private fun rasterizeCellStripesWithPolicy(
        frame: KoolCanvasFrame,
        pixels: IntArray,
        width: Int,
        height: Int,
        profile: CpuTargetProfile?,
        zeroInitializedPixels: Boolean,
        adaptive: Boolean,
    ): IntArray? {
        if (RasterWorkerPool.insideWorker.get() || RasterWorkerPool.workerCount <= 1 ||
            width.toLong() * height < PARALLEL_RASTER_MIN_PIXELS ||
            height < RasterWorkerPool.workerCount * PARALLEL_RASTER_MIN_ROWS_PER_WORKER) return null
        val store = textureStore as? KoolCanvasCpuTextureStore ?: return null
        val sourceIds = linkedSetOf<KoolCanvasTextureId>()
        for (command in frame.commands) {
            when (command) {
                is KoolCanvasCommand.Clear -> if (command.renderTarget != null) return null
                is KoolCanvasCommand.DrawTexture -> {
                    if (command.state.renderTarget != null || command.paint.textureEffect != null ||
                        !command.state.isFiniteRasterState() || !command.source.isFiniteRect() ||
                        !command.destination.isFiniteRect() || !command.paint.alphaMultiplier.isFinite()) return null
                    sourceIds += command.texture.id
                }
                is KoolCanvasCommand.DrawRect -> {
                    if (command.state.renderTarget != null || command.paint.style == KoolCanvasPaintStyle.Stroke ||
                        !command.state.isFiniteRasterState() || !command.rect.isFiniteRect() ||
                        !command.paint.alphaMultiplier.isFinite()) return null
                }
                else -> return null
            }
        }
        // Clear/fill-only cells already use cheap array/row operations. Avoid even source
        // preparation for them when choosing whether a whole-cell dispatch is worthwhile.
        if (adaptive && sourceIds.isEmpty()) return null
        val sourceLease = store.acquireRasterPixelSources(sourceIds) ?: return null
        sourceLease.use { sources ->
            if (adaptive && !adaptiveCellRasterHasEnoughTextureWork(frame, sources.images, width, height)) return null
            val sourceImages = arrayOfNulls<KoolCanvasArgbImage>(frame.commands.size)
            for (index in frame.commands.indices) {
                val command = frame.commands[index] as? KoolCanvasCommand.DrawTexture ?: continue
                sourceImages[index] = sources.images.getValue(command.texture.id)
            }
            val profiles = if (profile != null) arrayOfNulls<CpuTargetProfile>(RasterWorkerPool.workerCount) else null
            val stripeRows = (height + RasterWorkerPool.workerCount - 1) / RasterWorkerPool.workerCount
            processRasterRows(0, height, width) { rowStart, rowEnd ->
                val stripeProfile = profile?.newStripeProfile()
                profiles?.set(rowStart / stripeRows, stripeProfile)
                rasterizeFrameStripe(frame, sourceImages, pixels, width, height, rowStart, rowEnd, stripeProfile, zeroInitializedPixels)
            }
            if (profile != null) profile.mergeStripeProfiles(profiles!!.filterNotNull(), frame.commands.size)
        }
        return pixels
    }

    /** Predict only texture work: fills and exact row copies need no whole-cell dispatch. */
    private fun adaptiveCellRasterHasEnoughTextureWork(
        frame: KoolCanvasFrame,
        sources: Map<KoolCanvasTextureId, KoolCanvasArgbImage>,
        width: Int,
        height: Int,
    ): Boolean {
        var work = 0L
        for (command in frame.commands) {
            if (command !is KoolCanvasCommand.DrawTexture) continue
            val quad = clipTextureQuad(command.source, command.destination, command.state.clipForGeometry()) ?: continue
            val source = quad.source
            val destination = quad.destination
            val transform = command.state.transform
            val bounds = transform.mapRectBounds(destination)
            val left = floor(bounds.boundsLeft).toInt().coerceIn(0, width)
            val top = floor(bounds.boundsTop).toInt().coerceIn(0, height)
            val right = ceil(bounds.boundsRight).toInt().coerceIn(0, width)
            val bottom = ceil(bounds.boundsBottom).toInt().coerceIn(0, height)
            if (left >= right || top >= bottom) continue
            val image = sources.getValue(command.texture.id)
            if (transform === KoolCanvasTransform.Identity && textureRowCopyIsSupported(
                    command, image, source, destination, left, top, right, bottom,
                )) {
                if (command.paint.blendMode == KoolCanvasBlendMode.Source) continue
                val sourceLeft = source.left.toInt() + (left - destination.left.toInt())
                val sourceTop = source.top.toInt() + (top - destination.top.toInt())
                if (image.isOpaque(sourceLeft, sourceTop, right - left, bottom - top)) continue
            }
            // Linear sampling fetches four texels; nearest sampling and translucent row
            // compositing read one. This is a dispatch heuristic, never a pixel shortcut.
            val weight = if (command.paint.textureFilter == KoolCanvasTextureFilter.Linear) 4L else 1L
            work += (right - left).toLong() * (bottom - top) * weight
            if (work >= ADAPTIVE_CELL_RASTER_MIN_TEXTURE_WORK) return true
        }
        return false
    }

    private fun KoolCanvasRect.isFiniteRect(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    private fun KoolCanvasState.isFiniteRasterState(): Boolean {
        val t = transform
        if (!t.scaleX.isFinite() || !t.scaleY.isFinite() || !t.skewX.isFinite() || !t.skewY.isFinite() ||
            !t.translateX.isFinite() || !t.translateY.isFinite() || clip?.isFiniteRect() == false) return false
        val determinant = t.scaleX * t.scaleY - t.skewX * t.skewY
        return determinant.isFinite() && abs(determinant) >= .000001f
    }

    private fun rasterizeFrameStripe(
        frame: KoolCanvasFrame,
        sourceImages: Array<KoolCanvasArgbImage?>,
        pixels: IntArray,
        width: Int,
        height: Int,
        rowStart: Int,
        rowEnd: Int,
        profile: CpuTargetProfile?,
        zeroInitializedPixels: Boolean,
    ) {
        var firstCommandIndex = 0
        while (firstCommandIndex < frame.commands.size) {
            val clear = frame.commands[firstCommandIndex] as? KoolCanvasCommand.Clear ?: break
            if (clear.renderTarget != null || clear.blendMode == KoolCanvasBlendMode.ClearAlpha) break
            firstCommandIndex++
        }
        if (firstCommandIndex > 0) {
            val clear = frame.commands[firstCommandIndex - 1] as KoolCanvasCommand.Clear
            val color = if (clear.blendMode == KoolCanvasBlendMode.Clear) 0 else clear.color.argb
            if (color != 0 || !zeroInitializedPixels) pixels.fill(color, rowStart * width, rowEnd * width)
            profile?.let { it.clearCommands += firstCommandIndex }
        }
        for (index in firstCommandIndex until frame.commands.size) {
            when (val command = frame.commands[index]) {
                is KoolCanvasCommand.Clear -> {
                    profile?.let { it.clearCommands++ }
                    if (command.blendMode == KoolCanvasBlendMode.ClearAlpha) {
                        for (offset in rowStart * width until rowEnd * width) pixels[offset] = pixels[offset] and 0x00ffffff
                    } else {
                        val color = if (command.blendMode == KoolCanvasBlendMode.Clear) 0 else command.color.argb
                        pixels.fill(color, rowStart * width, rowEnd * width)
                    }
                }
                is KoolCanvasCommand.DrawTexture -> check(rasterizeResolvedTextureCommand(command, sourceImages[index]!!,
                    pixels, width, height, profile, rowStart, rowEnd)) { "Prechecked texture stripe was unsupported" }
                is KoolCanvasCommand.DrawRect -> {
                    profile?.let { it.rectCommands++ }
                    check(rasterizeRectStripe(command, pixels, width, height, rowStart, rowEnd)) {
                        "Prechecked rectangle stripe was unsupported"
                    }
                }
                else -> error("Prechecked stripe contained unsupported geometry")
            }
        }
    }

    private fun rasterizeFrameCommands(
        frame: KoolCanvasFrame,
        pixels: IntArray,
        width: Int,
        height: Int,
        visiting: Set<KoolCanvasTextureId>,
        profile: CpuTargetProfile? = null,
        zeroInitializedPixels: Boolean = false,
    ): IntArray? {
        profile?.totalCommands = frame.commands.size
        var firstCommandIndex = 0
        // Consecutive leading full clears replace every pixel even in dirty pooled storage.
        // Stop at geometry, another target, or an alpha-only clear, which must preserve RGB.
        while (firstCommandIndex < frame.commands.size) {
            val clear = frame.commands[firstCommandIndex] as? KoolCanvasCommand.Clear ?: break
            if (clear.renderTarget != null || clear.blendMode == KoolCanvasBlendMode.ClearAlpha) break
            firstCommandIndex++
        }
        if (firstCommandIndex > 0) {
            val clear = frame.commands[firstCommandIndex - 1] as KoolCanvasCommand.Clear
            val color = if (clear.blendMode == KoolCanvasBlendMode.Clear) 0 else clear.color.argb
            if (color != 0) fillRasterPixels(pixels, color)
            else if (!zeroInitializedPixels) pixels.fill(0)
            // Profiling counts recorded commands, including clears whose writes were omitted.
            profile?.let { it.clearCommands += firstCommandIndex }
        }
        for (commandIndex in firstCommandIndex until frame.commands.size) {
            val command = frame.commands[commandIndex]
            when (command) {
                is KoolCanvasCommand.Clear -> {
                    profile?.let { it.clearCommands += 1 }
                    if (command.renderTarget != null) return null
                    when (command.blendMode) {
                        KoolCanvasBlendMode.Clear -> fillRasterPixels(pixels, 0)
                        KoolCanvasBlendMode.ClearAlpha -> {
                            processRasterRows(0, height, width) { rowStart, rowEnd ->
                                var index = rowStart * width
                                val end = rowEnd * width
                                while (index < end) {
                                    pixels[index] = pixels[index] and 0x00ffffff
                                    index += 1
                                }
                            }
                        }

                        else -> fillRasterPixels(pixels, command.color.argb)
                    }
                }

                is KoolCanvasCommand.DrawTexture -> {
                    if (!rasterizeTextureCommand(command, pixels, width, height, visiting, profile)) {
                        return null
                    }
                }

                is KoolCanvasCommand.DrawRect -> {
                    profile?.let { it.rectCommands += 1 }
                    if (!rasterizeRectCommand(command, pixels, width, height)) {
                        return null
                    }
                }

                is KoolCanvasCommand.DrawLine,
                is KoolCanvasCommand.DrawCircle,
                is KoolCanvasCommand.DrawText -> {
                    return null
                }

                // The CPU raster paths expand one command into one quad and have no repeat handling yet.
                // Bailing out keeps the raster correct (the caller falls back to the frame-texture path)
                // rather than drawing a single stretched quad, which is the tearing bug of round 24.
                is KoolCanvasCommand.DrawTextureRepeat -> {
                    return null
                }
                is KoolCanvasCommand.DrawTextureBatch -> {
                    var success = true
                    command.quads.forEachDraw(command.texture, command.paint, command.state) {
                        if (success) success = rasterizeTextureCommand(it, pixels, width, height, visiting, profile)
                    }
                    if (!success) return null
                }
                is KoolCanvasCommand.DrawRectBatch -> {
                    var success = true
                    command.rects.forEachDraw(command.paint, command.state) {
                        if (success) success = rasterizeRectCommand(it, pixels, width, height)
                    }
                    if (!success) return null
                }
                is KoolCanvasCommand.DrawFogBatch -> {
                    var success = true
                    command.masks.forEachDraw(command.texture, command.filter, command.state, command.paint) {
                        if (success) success = when (it) {
                            is KoolCanvasCommand.DrawRect -> rasterizeRectCommand(it, pixels, width, height)
                            is KoolCanvasCommand.DrawTexture -> rasterizeTextureCommand(it, pixels, width, height, visiting, profile)
                            else -> error("Unexpected fog mask")
                        }
                    }
                    if (!success) return null
                }
            }
        }
        return pixels
    }

    private fun rasterizeTextureCommand(
        command: KoolCanvasCommand.DrawTexture,
        targetPixels: IntArray,
        targetWidth: Int,
        targetHeight: Int,
        visiting: Set<KoolCanvasTextureId>,
        profile: CpuTargetProfile? = null,
    ): Boolean {
        if (command.state.renderTarget != null || command.paint.textureEffect != null) {
            return false
        }
        profile?.recordTextureCommand(command)
        val sourceImage = textureStore.argbImageView(command.texture.id)
            ?: textureStore.frame(command.texture.id)?.let { frame ->
                rasterizeFrameToImage(
                    id = command.texture.id,
                    frame = frame,
                    width = command.texture.width,
                    height = command.texture.height,
                    visiting = visiting,
                )
            }
            ?: return false
        return rasterizeResolvedTextureCommand(command, sourceImage, targetPixels, targetWidth, targetHeight,
            profile, 0, targetHeight, recordProfile = false)
    }

    private fun rasterizeResolvedTextureCommand(
        command: KoolCanvasCommand.DrawTexture,
        sourceImage: KoolCanvasArgbImage,
        targetPixels: IntArray,
        targetWidth: Int,
        targetHeight: Int,
        profile: CpuTargetProfile?,
        rowStart: Int,
        rowEnd: Int,
        recordProfile: Boolean = true,
    ): Boolean {
        // Common map tiles have the identity transform and cannot affect other row stripes.
        // Reject before allocating clipped quads and X sampling arrays for those commands.
        if ((rowStart > 0 || rowEnd < targetHeight) && command.state.transform === KoolCanvasTransform.Identity &&
            (ceil(command.destination.boundsBottom).toInt() <= rowStart || floor(command.destination.boundsTop).toInt() >= rowEnd)) return true
        if (recordProfile) profile?.recordTextureCommand(command)
        val textureQuad =
            clipTextureQuad(command.source, command.destination, command.state.clipForGeometry()) ?: return true
        val source = textureQuad.source
        val destination = textureQuad.destination
        val transform = command.state.transform
        val targetBounds = transform.mapRectBounds(destination)
        val left = floor(targetBounds.boundsLeft).toInt().coerceIn(0, targetWidth)
        val top = floor(targetBounds.boundsTop).toInt().coerceIn(0, targetHeight).coerceAtLeast(rowStart)
        val right = ceil(targetBounds.boundsRight).toInt().coerceIn(0, targetWidth)
        val bottom = ceil(targetBounds.boundsBottom).toInt().coerceIn(0, targetHeight).coerceAtMost(rowEnd)
        if (left >= right || top >= bottom) return true

        if (transform === KoolCanvasTransform.Identity && copyTextureRowsIfPossible(
                command = command,
                sourceImage = sourceImage,
                source = source,
                destination = destination,
                targetPixels = targetPixels,
                targetWidth = targetWidth,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
        ) {
            profile?.let { it.fastTextureCopies += 1 }
            return true
        }

        profile?.let {
            it.sampledTextureDraws += 1
            it.sampledPixels += ((right - left) * (bottom - top)).toLong()
        }
        if (transform === KoolCanvasTransform.Identity) {
            rasterizeAxisAlignedTextureCommand(
                command = command,
                sourceImage = sourceImage,
                source = source,
                destination = destination,
                targetPixels = targetPixels,
                targetWidth = targetWidth,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
            return true
        }
        val inverseTransform = transform.inverted() ?: return false
        processRasterRows(top, bottom, right - left) { rowStart, rowEnd ->
            for (y in rowStart until rowEnd) {
                for (x in left until right) {
                    val localX: Float
                    val localY: Float
                    if (RASTER_SCALAR_MAPPING_ENABLED) {
                        val pixelX = x + 0.5f
                        val pixelY = y + 0.5f
                        // Keep Transform.map's Float multiplication and addition order.
                        localX = inverseTransform.scaleX * pixelX + inverseTransform.skewX * pixelY + inverseTransform.translateX
                        localY = inverseTransform.skewY * pixelX + inverseTransform.scaleY * pixelY + inverseTransform.translateY
                    } else {
                        val localPoint = inverseTransform.map(KoolCanvasPoint(x + 0.5f, y + 0.5f))
                        localX = localPoint.x
                        localY = localPoint.y
                    }
                    if (!destination.contains(localX, localY)) {
                        continue
                    }
                    val sourceColor =
                        sampleSourceColor(command, sourceImage, source, destination, localX, localY)
                    blendRenderTargetPixel(
                        pixels = targetPixels,
                        index = x + (y * targetWidth),
                        sourceColor = tintColor(sourceColor, command.paint, sourceImage.premultipliedAlpha),
                        blendMode = command.paint.blendMode,
                        sourcePremultipliedAlpha = sourceImage.premultipliedAlpha,
                    )
                }
            }
        }
        return true
    }

    private fun rasterizeAxisAlignedTextureCommand(
        command: KoolCanvasCommand.DrawTexture,
        sourceImage: KoolCanvasArgbImage,
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        targetPixels: IntArray,
        targetWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        when (command.paint.textureFilter) {
            KoolCanvasTextureFilter.Nearest -> rasterizeAxisAlignedNearestTextureCommand(
                command = command,
                sourceImage = sourceImage,
                source = source,
                destination = destination,
                targetPixels = targetPixels,
                targetWidth = targetWidth,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )

            KoolCanvasTextureFilter.Linear -> rasterizeAxisAlignedLinearTextureCommand(
                command = command,
                sourceImage = sourceImage,
                source = source,
                destination = destination,
                targetPixels = targetPixels,
                targetWidth = targetWidth,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
        }
    }

    private fun rasterizeAxisAlignedNearestTextureCommand(
        command: KoolCanvasCommand.DrawTexture,
        sourceImage: KoolCanvasArgbImage,
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        targetPixels: IntArray,
        targetWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val width = right - left
        val xSamples = IntArray(width)
        val xEnabled = BooleanArray(width)
        for (offset in 0 until width) {
            val pixelCenter = left + offset + 0.5f
            if (pixelCenter >= destination.boundsLeft && pixelCenter < destination.boundsRight) {
                xEnabled[offset] = true
                xSamples[offset] = sampleNearestCoordinate(
                    pixelCenter = pixelCenter,
                    destinationStart = destination.left,
                    destinationSize = destination.width,
                    sourceStart = source.left,
                    sourceSize = source.width,
                ).coerceIn(0, sourceImage.width - 1)
            }
        }
        processRasterRows(top, bottom, width) { rowStart, rowEnd ->
            for (y in rowStart until rowEnd) {
                val yCenter = y + 0.5f
                if (yCenter < destination.boundsTop || yCenter >= destination.boundsBottom) {
                    continue
                }
                val sourceY = sampleNearestCoordinate(
                    pixelCenter = yCenter,
                    destinationStart = destination.top,
                    destinationSize = destination.height,
                    sourceStart = source.top,
                    sourceSize = source.height,
                ).coerceIn(0, sourceImage.height - 1)
                val sourceRow = sourceY * sourceImage.width
                var targetIndex = left + (y * targetWidth)
                for (offset in 0 until width) {
                    if (xEnabled[offset]) {
                        val sourceColor = sourceImage.pixels[sourceRow + xSamples[offset]]
                        blendRenderTargetPixel(
                            pixels = targetPixels,
                            index = targetIndex,
                            sourceColor = tintColor(sourceColor, command.paint, sourceImage.premultipliedAlpha),
                            blendMode = command.paint.blendMode,
                            sourcePremultipliedAlpha = sourceImage.premultipliedAlpha,
                        )
                    }
                    targetIndex += 1
                }
            }
        }
    }

    private fun rasterizeAxisAlignedLinearTextureCommand(
        command: KoolCanvasCommand.DrawTexture,
        sourceImage: KoolCanvasArgbImage,
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        targetPixels: IntArray,
        targetWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val width = right - left
        val x0Samples = IntArray(width)
        val x1Samples = IntArray(width)
        val xAmounts = FloatArray(width)
        val xEnabled = BooleanArray(width)
        for (offset in 0 until width) {
            val pixelCenter = left + offset + 0.5f
            if (pixelCenter >= destination.boundsLeft && pixelCenter < destination.boundsRight) {
                xEnabled[offset] = true
                val sourceX = sampleLinearCoordinate(
                    pixelCenter = pixelCenter,
                    destinationStart = destination.left,
                    destinationSize = destination.width,
                    sourceStart = source.left,
                    sourceSize = source.width,
                )
                val x0 = floor(sourceX).toInt()
                x0Samples[offset] = x0.coerceIn(0, sourceImage.width - 1)
                x1Samples[offset] = (x0 + 1).coerceIn(0, sourceImage.width - 1)
                xAmounts[offset] = sourceX - x0
            }
        }
        processRasterRows(top, bottom, width) { rowStart, rowEnd ->
            for (y in rowStart until rowEnd) {
                val yCenter = y + 0.5f
                if (yCenter < destination.boundsTop || yCenter >= destination.boundsBottom) {
                    continue
                }
                val sourceY = sampleLinearCoordinate(
                    pixelCenter = yCenter,
                    destinationStart = destination.top,
                    destinationSize = destination.height,
                    sourceStart = source.top,
                    sourceSize = source.height,
                )
                val y0 = floor(sourceY).toInt()
                val y1 = y0 + 1
                val y0Row = y0.coerceIn(0, sourceImage.height - 1) * sourceImage.width
                val y1Row = y1.coerceIn(0, sourceImage.height - 1) * sourceImage.width
                val yAmount = sourceY - y0
                var targetIndex = left + (y * targetWidth)
                for (offset in 0 until width) {
                    if (xEnabled[offset]) {
                        val c00 = sourceImage.pixels[y0Row + x0Samples[offset]]
                        val c10 = sourceImage.pixels[y0Row + x1Samples[offset]]
                        val c01 = sourceImage.pixels[y1Row + x0Samples[offset]]
                        val c11 = sourceImage.pixels[y1Row + x1Samples[offset]]
                        val topColor = interpolateColor(c00, c10, xAmounts[offset])
                        val bottomColor = interpolateColor(c01, c11, xAmounts[offset])
                        val sourceColor = interpolateColor(topColor, bottomColor, yAmount)
                        blendRenderTargetPixel(
                            pixels = targetPixels,
                            index = targetIndex,
                            sourceColor = tintColor(sourceColor, command.paint, sourceImage.premultipliedAlpha),
                            blendMode = command.paint.blendMode,
                            sourcePremultipliedAlpha = sourceImage.premultipliedAlpha,
                        )
                    }
                    targetIndex += 1
                }
            }
        }
    }

    private fun copyTextureRowsIfPossible(
        command: KoolCanvasCommand.DrawTexture,
        sourceImage: KoolCanvasArgbImage,
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        targetPixels: IntArray,
        targetWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        if (!textureRowCopyIsSupported(command, sourceImage, source, destination, left, top, right, bottom)) return false

        val sourceLeft = source.left.toInt() + (left - destination.left.toInt())
        val sourceTop = source.top.toInt() + (top - destination.top.toInt())
        val copyWidth = right - left
        val copyHeight = bottom - top
        when (command.paint.blendMode) {
            KoolCanvasBlendMode.Source -> {
                copyTextureRows(
                    sourceImage,
                    targetPixels,
                    targetWidth,
                    sourceLeft,
                    sourceTop,
                    left,
                    top,
                    copyWidth,
                    copyHeight
                )
            }

            KoolCanvasBlendMode.SourceOver -> {
                if (sourceImage.isOpaque(sourceLeft, sourceTop, copyWidth, copyHeight)) {
                    copyTextureRows(
                        sourceImage,
                        targetPixels,
                        targetWidth,
                        sourceLeft,
                        sourceTop,
                        left,
                        top,
                        copyWidth,
                        copyHeight
                    )
                } else {
                    blendSourceOverTextureRows(
                        sourceImage = sourceImage,
                        targetPixels = targetPixels,
                        targetWidth = targetWidth,
                        sourceLeft = sourceLeft,
                        sourceTop = sourceTop,
                        targetLeft = left,
                        targetTop = top,
                        width = copyWidth,
                        height = copyHeight,
                    )
                }
            }

            else -> return false
        }
        return true
    }

    /** Shared exact eligibility; callers distinguish opaque copying from alpha compositing. */
    private fun textureRowCopyIsSupported(
        command: KoolCanvasCommand.DrawTexture,
        sourceImage: KoolCanvasArgbImage,
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        if (command.paint.textureFilter != KoolCanvasTextureFilter.Nearest ||
            command.paint.color.argb != -0x1 ||
            command.paint.alphaMultiplier != 1f ||
            source.width <= 0f ||
            source.height <= 0f ||
            destination.width <= 0f ||
            destination.height <= 0f ||
            !source.left.isWholePixel() ||
            !source.top.isWholePixel() ||
            !source.right.isWholePixel() ||
            !source.bottom.isWholePixel() ||
            !destination.left.isWholePixel() ||
            !destination.top.isWholePixel() ||
            !destination.right.isWholePixel() ||
            !destination.bottom.isWholePixel() ||
            source.width != destination.width ||
            source.height != destination.height
        ) {
            return false
        }

        val sourceLeft = source.left.toInt() + (left - destination.left.toInt())
        val sourceTop = source.top.toInt() + (top - destination.top.toInt())
        val copyWidth = right - left
        val copyHeight = bottom - top
        if (sourceLeft < 0 ||
            sourceTop < 0 ||
            sourceLeft + copyWidth > sourceImage.width ||
            sourceTop + copyHeight > sourceImage.height
        ) {
            return false
        }

        return command.paint.blendMode == KoolCanvasBlendMode.Source || command.paint.blendMode == KoolCanvasBlendMode.SourceOver
    }

    private fun copyTextureRows(
        sourceImage: KoolCanvasArgbImage,
        targetPixels: IntArray,
        targetWidth: Int,
        sourceLeft: Int,
        sourceTop: Int,
        targetLeft: Int,
        targetTop: Int,
        width: Int,
        height: Int,
    ) {
        processRasterRows(0, height, width) { rowStart, rowEnd ->
            for (row in rowStart until rowEnd) {
                sourceImage.pixels.copyInto(
                    destination = targetPixels,
                    destinationOffset = targetLeft + ((targetTop + row) * targetWidth),
                    startIndex = sourceLeft + ((sourceTop + row) * sourceImage.width),
                    endIndex = sourceLeft + ((sourceTop + row) * sourceImage.width) + width,
                )
            }
        }
    }

    private fun blendSourceOverTextureRows(
        sourceImage: KoolCanvasArgbImage,
        targetPixels: IntArray,
        targetWidth: Int,
        sourceLeft: Int,
        sourceTop: Int,
        targetLeft: Int,
        targetTop: Int,
        width: Int,
        height: Int,
    ) {
        processRasterRows(0, height, width) { rowStart, rowEnd ->
            for (row in rowStart until rowEnd) {
                var sourceIndex = sourceLeft + ((sourceTop + row) * sourceImage.width)
                var targetIndex = targetLeft + ((targetTop + row) * targetWidth)
                val sourceEnd = sourceIndex + width
                while (sourceIndex < sourceEnd) {
                    val sourceColor = sourceImage.pixels[sourceIndex]
                    val sourceAlpha = sourceColor.alphaComponent
                    if (sourceAlpha == 255) {
                        targetPixels[targetIndex] = sourceColor
                    } else if (sourceAlpha != 0) {
                        targetPixels[targetIndex] = if (sourceImage.premultipliedAlpha) {
                            renderTargetPremultipliedSourceOver(targetPixels[targetIndex], sourceColor)
                        } else {
                            renderTargetSourceOver(targetPixels[targetIndex], sourceColor)
                        }
                    }
                    sourceIndex += 1
                    targetIndex += 1
                }
            }
        }
    }

    private fun clipTextureQuad(
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        clip: KoolCanvasRect?,
    ): TextureQuad? {
        if (source.hasZeroArea || destination.hasZeroArea) return null
        val clippedDestination = if (clip != null) {
            val clippedBounds = destination.boundsIntersect(clip) ?: return null
            destination.orientBounds(clippedBounds)
        } else {
            destination
        }
        if (clippedDestination.hasZeroArea) return null

        val leftRatio = (clippedDestination.left - destination.left) / destination.width
        val topRatio = (clippedDestination.top - destination.top) / destination.height
        val rightRatio = (clippedDestination.right - destination.left) / destination.width
        val bottomRatio = (clippedDestination.bottom - destination.top) / destination.height
        val clippedSource = KoolCanvasRect(
            left = source.left + source.width * leftRatio,
            top = source.top + source.height * topRatio,
            right = source.left + source.width * rightRatio,
            bottom = source.top + source.height * bottomRatio,
        )
        return TextureQuad(clippedSource, clippedDestination)
    }

    private fun sampleSourceColor(
        command: KoolCanvasCommand.DrawTexture,
        sourceImage: KoolCanvasArgbImage,
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        x: Float,
        y: Float,
    ): Int =
        when (command.paint.textureFilter) {
            KoolCanvasTextureFilter.Nearest -> {
                val sourceX = sampleNearestCoordinate(
                    pixelCenter = x,
                    destinationStart = destination.left,
                    destinationSize = destination.width,
                    sourceStart = source.left,
                    sourceSize = source.width,
                ).coerceIn(0, sourceImage.width - 1)
                val sourceY = sampleNearestCoordinate(
                    pixelCenter = y,
                    destinationStart = destination.top,
                    destinationSize = destination.height,
                    sourceStart = source.top,
                    sourceSize = source.height,
                ).coerceIn(0, sourceImage.height - 1)
                sourceImage.pixels[sourceX + (sourceY * sourceImage.width)]
            }

            KoolCanvasTextureFilter.Linear -> sampleLinearColor(command, sourceImage, source, destination, x, y)
        }

    private fun sampleLinearColor(
        command: KoolCanvasCommand.DrawTexture,
        sourceImage: KoolCanvasArgbImage,
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        x: Float,
        y: Float,
    ): Int {
        val sourceX = sampleLinearCoordinate(
            pixelCenter = x,
            destinationStart = destination.left,
            destinationSize = destination.width,
            sourceStart = source.left,
            sourceSize = source.width,
        )
        val sourceY = sampleLinearCoordinate(
            pixelCenter = y,
            destinationStart = destination.top,
            destinationSize = destination.height,
            sourceStart = source.top,
            sourceSize = source.height,
        )
        val x0 = floor(sourceX).toInt()
        val y0 = floor(sourceY).toInt()
        val x1 = x0 + 1
        val y1 = y0 + 1
        val tx = sourceX - x0
        val ty = sourceY - y0
        val c00 = sourceImage.pixelClamped(x0, y0)
        val c10 = sourceImage.pixelClamped(x1, y0)
        val c01 = sourceImage.pixelClamped(x0, y1)
        val c11 = sourceImage.pixelClamped(x1, y1)
        val top = interpolateColor(c00, c10, tx)
        val bottom = interpolateColor(c01, c11, tx)
        return interpolateColor(top, bottom, ty)
    }

    private fun rasterizeRectCommand(
        command: KoolCanvasCommand.DrawRect,
        targetPixels: IntArray,
        targetWidth: Int,
        targetHeight: Int,
    ): Boolean = rasterizeRectStripe(command, targetPixels, targetWidth, targetHeight, 0, targetHeight)

    private fun rasterizeRectStripe(
        command: KoolCanvasCommand.DrawRect,
        targetPixels: IntArray,
        targetWidth: Int,
        targetHeight: Int,
        rowStart: Int,
        rowEnd: Int,
    ): Boolean {
        if (command.state.renderTarget != null ||
            command.paint.style == KoolCanvasPaintStyle.Stroke
        ) {
            return false
        }
        val clip = command.state.clipForGeometry()
        val destination = if (clip == null) command.rect else command.rect.intersect(clip) ?: return true
        if (destination.isEmpty) return true
        val transform = command.state.transform
        val targetBounds = transform.mapRectBounds(destination)
        val left = floor(targetBounds.boundsLeft).toInt().coerceIn(0, targetWidth)
        val top = floor(targetBounds.boundsTop).toInt().coerceIn(0, targetHeight).coerceAtLeast(rowStart)
        val right = ceil(targetBounds.boundsRight).toInt().coerceIn(0, targetWidth)
        val bottom = ceil(targetBounds.boundsBottom).toInt().coerceIn(0, targetHeight).coerceAtMost(rowEnd)
        if (left >= right || top >= bottom) return true
        val color = paintColor(command.paint)
        val inverseTransform = transform.inverted() ?: return false
        processRasterRows(top, bottom, right - left) { rowStart, rowEnd ->
            for (y in rowStart until rowEnd) {
                for (x in left until right) {
                    val localX: Float
                    val localY: Float
                    if (RASTER_SCALAR_MAPPING_ENABLED) {
                        val pixelX = x + 0.5f
                        val pixelY = y + 0.5f
                        localX = inverseTransform.scaleX * pixelX + inverseTransform.skewX * pixelY + inverseTransform.translateX
                        localY = inverseTransform.skewY * pixelX + inverseTransform.scaleY * pixelY + inverseTransform.translateY
                    } else {
                        val localPoint = inverseTransform.map(KoolCanvasPoint(x + 0.5f, y + 0.5f))
                        localX = localPoint.x
                        localY = localPoint.y
                    }
                    if (!destination.contains(localX, localY)) {
                        continue
                    }
                    blendRenderTargetPixel(targetPixels, x + (y * targetWidth), color, command.paint.blendMode)
                }
            }
        }
        return true
    }

    private fun fillRasterPixels(pixels: IntArray, color: Int) {
        processRasterRows(0, pixels.size, 1) { start, end ->
            pixels.fill(color, start, end)
        }
    }

    private fun processRasterRows(
        top: Int,
        bottom: Int,
        rowWidth: Int,
        block: (Int, Int) -> Unit,
    ) {
        val rowCount = bottom - top
        if (rowCount <= 0 || rowWidth <= 0) {
            return
        }
        val workerCount = RasterWorkerPool.workerCount
        val pixelCount = rowCount * rowWidth
        if (RasterWorkerPool.insideWorker.get() || workerCount <= 1 ||
            rowCount < workerCount * PARALLEL_RASTER_MIN_ROWS_PER_WORKER ||
            pixelCount < PARALLEL_RASTER_MIN_PIXELS
        ) {
            block(top, bottom)
            return
        }

        val taskCount = minOf(workerCount, rowCount)
        val rowsPerTask = (rowCount + taskCount - 1) / taskCount
        val latch = CountDownLatch(taskCount)
        val failure = AtomicReference<Throwable?>()
        for (taskIndex in 0 until taskCount) {
            val rowStart = top + taskIndex * rowsPerTask
            val rowEnd = minOf(bottom, rowStart + rowsPerTask)
            // A submission failure can race a worker starting. Cancel only a task that has not
            // started; otherwise its finally must signal completion before its pixels are freed.
            val taskState = AtomicInteger(0)
            try {
                RasterWorkerPool.executor.execute {
                    if (!taskState.compareAndSet(0, 1)) return@execute
                    val previousWorkerState = RasterWorkerPool.insideWorker.get()
                    RasterWorkerPool.insideWorker.set(true)
                    try {
                        if (failure.get() == null) {
                            block(rowStart, rowEnd)
                        }
                    } catch (throwable: Throwable) {
                        failure.compareAndSet(null, throwable)
                    } finally {
                        RasterWorkerPool.insideWorker.set(previousWorkerState)
                        taskState.set(3)
                        latch.countDown()
                    }
                }
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
                if (taskState.compareAndSet(0, 2)) latch.countDown()
                for (unsubmitted in taskIndex + 1 until taskCount) latch.countDown()
                break
            }
        }
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (throwable: InterruptedException) {
                interrupted = true
                failure.compareAndSet(null, throwable)
            }
        }
        // The caller may return pooled storage only after every admitted writer has stopped.
        if (interrupted) Thread.currentThread().interrupt()
        failure.get()?.let { throw it }
    }

    private fun sampleNearestCoordinate(
        pixelCenter: Float,
        destinationStart: Float,
        destinationSize: Float,
        sourceStart: Float,
        sourceSize: Float,
    ): Int {
        if (destinationSize == 0f) return sourceStart.toInt()
        val t = (pixelCenter - destinationStart) / destinationSize
        return floor(sourceStart + (t * sourceSize)).toInt()
    }

    private fun sampleLinearCoordinate(
        pixelCenter: Float,
        destinationStart: Float,
        destinationSize: Float,
        sourceStart: Float,
        sourceSize: Float,
    ): Float {
        if (destinationSize == 0f) return sourceStart
        val t = (pixelCenter - destinationStart) / destinationSize
        return sourceStart + (t * sourceSize) - 0.5f
    }

    private fun KoolCanvasRect.contains(x: Float, y: Float): Boolean =
        x >= boundsLeft && x < boundsRight && y >= boundsTop && y < boundsBottom

    private fun KoolCanvasTransform.inverted(): KoolCanvasTransform? {
        if (this === KoolCanvasTransform.Identity) return this
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

    private fun KoolCanvasState.clipForGeometry(): KoolCanvasRect? {
        val currentClip = clip ?: return null
        val currentTransform = transform
        if (currentTransform === KoolCanvasTransform.Identity) {
            return currentClip
        }
        return currentTransform.inverted()?.mapRectBounds(currentClip) ?: currentClip
    }

    private fun KoolCanvasArgbImage.pixelClamped(x: Int, y: Int): Int {
        val clampedX = x.coerceIn(0, width - 1)
        val clampedY = y.coerceIn(0, height - 1)
        return pixels[clampedX + (clampedY * width)]
    }

    private fun KoolCanvasArgbImage.isOpaque(left: Int, top: Int, width: Int, height: Int): Boolean {
        for (row in 0 until height) {
            val rowStart = left + ((top + row) * this.width)
            for (index in rowStart until rowStart + width) {
                if (pixels[index].alphaComponent != 255) {
                    return false
                }
            }
        }
        return true
    }

    private fun interpolateColor(left: Int, right: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val alpha = interpolateChannelFloat(left.alphaComponent, right.alphaComponent, t)
        if (alpha <= 0f) {
            return 0
        }
        return argb(
            alpha = alpha.roundToInt().coerceIn(0, 255),
            red = interpolatePremultipliedChannel(
                left = left.redComponent,
                leftAlpha = left.alphaComponent,
                right = right.redComponent,
                rightAlpha = right.alphaComponent,
                amount = t,
                alpha = alpha,
            ),
            green = interpolatePremultipliedChannel(
                left = left.greenComponent,
                leftAlpha = left.alphaComponent,
                right = right.greenComponent,
                rightAlpha = right.alphaComponent,
                amount = t,
                alpha = alpha,
            ),
            blue = interpolatePremultipliedChannel(
                left = left.blueComponent,
                leftAlpha = left.alphaComponent,
                right = right.blueComponent,
                rightAlpha = right.alphaComponent,
                amount = t,
                alpha = alpha,
            ),
        )
    }

    private fun interpolatePremultipliedChannel(
        left: Int,
        leftAlpha: Int,
        right: Int,
        rightAlpha: Int,
        amount: Float,
        alpha: Float,
    ): Int {
        val premultipliedLeft = left * leftAlpha
        val premultipliedRight = right * rightAlpha
        val premultiplied = premultipliedLeft + ((premultipliedRight - premultipliedLeft) * amount)
        return (premultiplied / alpha).roundToInt().coerceIn(0, 255)
    }

    private fun interpolateChannelFloat(left: Int, right: Int, amount: Float): Float =
        left + ((right - left) * amount)

    private fun tintColor(sourceColor: Int, paint: KoolCanvasPaint, premultipliedAlpha: Boolean = false): Int {
        val tint = paint.color.argb
        val tintAlpha = multiplyChannel(tint.alphaComponent, (paint.alphaMultiplier * 255f).roundToInt())
        val alpha = multiplyChannel(sourceColor.alphaComponent, tintAlpha)
        if (premultipliedAlpha) {
            return argb(
                alpha = alpha,
                red = multiplyChannel(multiplyChannel(sourceColor.redComponent, tint.redComponent), tintAlpha),
                green = multiplyChannel(multiplyChannel(sourceColor.greenComponent, tint.greenComponent), tintAlpha),
                blue = multiplyChannel(multiplyChannel(sourceColor.blueComponent, tint.blueComponent), tintAlpha),
            )
        }
        return argb(
            alpha = alpha,
            red = multiplyChannel(sourceColor.redComponent, tint.redComponent),
            green = multiplyChannel(sourceColor.greenComponent, tint.greenComponent),
            blue = multiplyChannel(sourceColor.blueComponent, tint.blueComponent),
        )
    }

    private fun paintColor(paint: KoolCanvasPaint): Int {
        val color = paint.color.argb
        return argb(
            alpha = multiplyChannel(color.alphaComponent, (paint.alphaMultiplier * 255f).roundToInt()),
            red = color.redComponent,
            green = color.greenComponent,
            blue = color.blueComponent,
        )
    }

    private fun blendRenderTargetPixel(
        pixels: IntArray,
        index: Int,
        sourceColor: Int,
        blendMode: KoolCanvasBlendMode,
        sourcePremultipliedAlpha: Boolean = false,
    ) {
        pixels[index] = when (blendMode) {
            KoolCanvasBlendMode.Clear -> 0
            KoolCanvasBlendMode.ClearAlpha -> pixels[index] and 0x00ffffff
            KoolCanvasBlendMode.Source -> sourceColor
            KoolCanvasBlendMode.Destination -> pixels[index]
            KoolCanvasBlendMode.Add -> addColors(pixels[index], sourceColor, sourcePremultipliedAlpha)
            else -> if (sourcePremultipliedAlpha) {
                renderTargetPremultipliedSourceOver(pixels[index], sourceColor)
            } else {
                renderTargetSourceOver(pixels[index], sourceColor)
            }
        }
    }

    private fun renderTargetPremultipliedSourceOver(destinationColor: Int, sourceColor: Int): Int {
        val sourceAlpha = sourceColor.alphaComponent
        if (sourceAlpha == 255) return sourceColor
        if (sourceAlpha == 0) return destinationColor
        val destinationAlpha = destinationColor.alphaComponent
        if (destinationAlpha == 0) {
            return argb(
                alpha = sourceAlpha,
                red = unpremultiplyChannel(sourceColor.redComponent, sourceAlpha),
                green = unpremultiplyChannel(sourceColor.greenComponent, sourceAlpha),
                blue = unpremultiplyChannel(sourceColor.blueComponent, sourceAlpha),
            )
        }
        val inverseAlpha = 255 - sourceAlpha
        if (OPAQUE_SOURCE_OVER_FAST_PATH_ENABLED && destinationAlpha == 255) {
            // An opaque destination keeps output alpha at 255, so unpremultiplication is
            // the identity. Keep saturation for premultiplied inputs whose RGB exceeds alpha.
            return argb(
                alpha = 255,
                red = (sourceColor.redComponent + multiplyChannel(destinationColor.redComponent, inverseAlpha)).coerceAtMost(255),
                green = (sourceColor.greenComponent + multiplyChannel(destinationColor.greenComponent, inverseAlpha)).coerceAtMost(255),
                blue = (sourceColor.blueComponent + multiplyChannel(destinationColor.blueComponent, inverseAlpha)).coerceAtMost(255),
            )
        }
        val destinationContributionAlpha = multiplyChannelRounded(destinationAlpha, inverseAlpha)
        val outAlpha = (sourceAlpha + destinationContributionAlpha).coerceAtMost(255)
        if (outAlpha == 0) return 0
        return argb(
            alpha = outAlpha,
            red = unpremultiplyChannel(
                (sourceColor.redComponent + multiplyChannel(
                    destinationColor.redComponent,
                    destinationContributionAlpha
                ))
                    .coerceAtMost(255),
                outAlpha,
            ),
            green = unpremultiplyChannel(
                (sourceColor.greenComponent + multiplyChannel(
                    destinationColor.greenComponent,
                    destinationContributionAlpha
                ))
                    .coerceAtMost(255),
                outAlpha,
            ),
            blue = unpremultiplyChannel(
                (sourceColor.blueComponent + multiplyChannel(
                    destinationColor.blueComponent,
                    destinationContributionAlpha
                ))
                    .coerceAtMost(255),
                outAlpha,
            ),
        )
    }

    private fun renderTargetSourceOver(destinationColor: Int, sourceColor: Int): Int {
        val sourceAlpha = sourceColor.alphaComponent
        if (sourceAlpha == 255) return sourceColor
        if (sourceAlpha == 0) return destinationColor
        val destinationAlpha = destinationColor.alphaComponent
        if (destinationAlpha == 0) return sourceColor
        val inverseAlpha = 255 - sourceAlpha
        if (OPAQUE_SOURCE_OVER_FAST_PATH_ENABLED && destinationAlpha == 255) {
            // Retain both channel floors separately; combining the numerators changes pixels.
            return argb(
                alpha = 255,
                red = multiplyChannel(sourceColor.redComponent, sourceAlpha) + multiplyChannel(destinationColor.redComponent, inverseAlpha),
                green = multiplyChannel(sourceColor.greenComponent, sourceAlpha) + multiplyChannel(destinationColor.greenComponent, inverseAlpha),
                blue = multiplyChannel(sourceColor.blueComponent, sourceAlpha) + multiplyChannel(destinationColor.blueComponent, inverseAlpha),
            )
        }
        val destinationContributionAlpha = multiplyChannelRounded(destinationAlpha, inverseAlpha)
        val outAlpha = (sourceAlpha + destinationContributionAlpha).coerceAtMost(255)
        if (outAlpha == 0) return 0
        return argb(
            alpha = outAlpha,
            red = unpremultiplyChannel(
                multiplyChannel(sourceColor.redComponent, sourceAlpha) +
                        multiplyChannel(destinationColor.redComponent, destinationContributionAlpha),
                outAlpha,
            ),
            green = unpremultiplyChannel(
                multiplyChannel(sourceColor.greenComponent, sourceAlpha) +
                        multiplyChannel(destinationColor.greenComponent, destinationContributionAlpha),
                outAlpha,
            ),
            blue = unpremultiplyChannel(
                multiplyChannel(sourceColor.blueComponent, sourceAlpha) +
                        multiplyChannel(destinationColor.blueComponent, destinationContributionAlpha),
                outAlpha,
            ),
        )
    }

    private fun unpremultiplyChannel(premultiplied: Int, alpha: Int): Int =
        if (alpha <= 0) {
            0
        } else {
            ((premultiplied * 255) + (alpha / 2)) / alpha
        }.coerceIn(0, 255)

    private fun multiplyChannelRounded(source: Int, multiplier: Int): Int =
        ((source * multiplier) + 127) / 255

    private fun addColors(destinationColor: Int, sourceColor: Int, sourcePremultipliedAlpha: Boolean): Int {
        val sourceAlpha = sourceColor.alphaComponent
        val sourceRed = sourceColor.redComponent.premultiplyForAdd(sourceAlpha, sourcePremultipliedAlpha)
        val sourceGreen = sourceColor.greenComponent.premultiplyForAdd(sourceAlpha, sourcePremultipliedAlpha)
        val sourceBlue = sourceColor.blueComponent.premultiplyForAdd(sourceAlpha, sourcePremultipliedAlpha)
        return argb(
            alpha = (destinationColor.alphaComponent + sourceAlpha).coerceAtMost(255),
            red = (destinationColor.redComponent + sourceRed).coerceAtMost(255),
            green = (destinationColor.greenComponent + sourceGreen).coerceAtMost(255),
            blue = (destinationColor.blueComponent + sourceBlue).coerceAtMost(255),
        )
    }

    private fun Int.premultiplyForAdd(alpha: Int, premultipliedAlpha: Boolean): Int =
        if (premultipliedAlpha) this else multiplyChannel(this, alpha)

    private fun commitTargetTextureFrame() {
        if (targetTexture == null) {
            flushTargetTextureFrame()
        } else {
            flushPendingTarget(targetTexture)
        }
    }

    private fun flushPendingTarget(texture: Texture) {
        val state = targetStates[texture] ?: return
        if (!state.commitEnabled || state.committing) {
            return
        }
        state.committing = true
        try {
            texture.e++
            val commit = state.engine.flushTargetTextureFrame() ?: return
            val nextAuxiliaryIds = commit.auxiliaryIds.toSet()
            state.activeAuxiliaryIds
                .filterNot(nextAuxiliaryIds::contains)
                .forEach(textureStore::unregister)
            state.activeAuxiliaryIds.clear()
            state.activeAuxiliaryIds += commit.auxiliaryIds
        } finally {
            state.committing = false
        }
    }

    private fun releaseKoolTexture(texture: Texture) {
        val id = texture.toCanvasTextureId()
        targetStates.remove(texture)?.let { state ->
            state.engine.releasePendingImmediateFrameSnapshots()
            state.releaseAuxiliaryTextures(textureStore)
            state.engine.registeredImmediatePixels.remove(id)
            state.engine.registeredImmediateFrames.remove(id)
        }
        registeredTexturePixelRevisions.remove(id)
        registeredImmediatePixels.remove(id)
        registeredImmediateFrames.remove(id)
        textureStore.unregister(id)
        textureMetadata.remove(texture)
    }

    /**
     * One texture source rect tiled [repeat] times across [destination].
     *
     * Two candidate causes for the black runs it produces have been tested and **both disproven**:
     *
     *  - missing `toAtlasDraw` remap: adding it changed the black share from 37% to 33% (baseline with the
     *    caller's merge off is 0.9%), so the source rect was not the problem;
     *  - double advancement of the caller's row loop: gating the merge off the fog pass changed 37% to 30%.
     *
     * What is established: the **covered area is correct** (drawing only the first tile of each run makes
     * the black share worse, 58% against 37%), so the geometry is right and the wrong thing is the sampled
     * content or the per-tile expansion. The caller stays disabled until that is pinned down.
     */
    private fun drawTextureRepeat(
        texture: Texture,
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        repeat: Int,
        paint: KoolPaint?,
    ) {
        flushPendingTarget(texture)
        val resolvedTexture = texture.resolveForKool()
        registerTexturePixels(resolvedTexture)
        val textureRef = freezeTextureForOffscreenDraw(resolvedTexture, resolvedTexture.toCanvasTextureRef())
        val canvasPaint = paint.toCanvasPaint()
        val texturePaint = canvasPaint
            .withDirectBlitTextureBlend(resolvedTexture)
            .asCanonicalTexturePaint()
        commandBuffer.drawTextureRepeat(
            texture = textureRef,
            source = source,
            destination = destination,
            repeat = repeat,
            paint = texturePaint,
            state = commandBuffer.state,
        )
    }

    private fun drawTexture(
        texture: Texture,
        destination: KoolCanvasRect,
        source: KoolCanvasRect,
        paint: KoolPaint?,
    ) {
        flushPendingTarget(texture)
        val atlasDraw = texture.toAtlasDraw(destination, source)
        val drawTexture = atlasDraw?.texture ?: texture
        val drawDestination = atlasDraw?.destination ?: destination
        val drawSource = atlasDraw?.source ?: source
        val teamColorEffect = (drawTexture as? TeamColorTexture)?.toCanvasTeamColorEffect()
        val resolvedTexture = if (teamColorEffect != null) {
            drawTexture.sourceTexture()
        } else {
            drawTexture.resolveForKool()
        }
        registerTexturePixels(resolvedTexture)
        val textureRef = freezeTextureForOffscreenDraw(resolvedTexture, resolvedTexture.toCanvasTextureRef())
        val canvasPaint = paint.toCanvasPaint()
        val texturePaint = KoolCanvasPaintOperations.textureEffect(canvasPaint, teamColorEffect, AVOID_TEXTURE_PAINT_COPIES)
            .withDirectBlitTextureBlend(resolvedTexture)
            .asCanonicalTexturePaint()
        commandBuffer.drawTexture(
            texture = textureRef,
            destination = drawDestination,
            paint = texturePaint,
            source = drawSource,
        )
    }

    private fun Texture.toAtlasDraw(
        destination: KoolCanvasRect,
        source: KoolCanvasRect,
    ): AtlasDraw? {
        val atlas = textureAtlasFor(this) ?: return null
        val region = atlas.a(this) ?: return null
        var drawLeft = destination.left
        var drawTop = destination.top
        var drawRight = destination.right
        var drawBottom = destination.bottom
        var sourceLeft = source.left
        var sourceTop = source.top
        var sourceRight = source.right
        var sourceBottom = source.bottom

        if (sourceLeft < 0f) {
            drawLeft += -sourceLeft
            sourceLeft = 0f
        }
        if (sourceTop < 0f) {
            drawTop += -sourceTop
            sourceTop = 0f
        }
        if (sourceRight > region.d) {
            drawRight += -(region.d - sourceRight)
            sourceRight = region.d
        }
        if (sourceBottom > region.e) {
            drawBottom += -(region.e - sourceBottom)
            sourceBottom = region.e
        }

        return AtlasDraw(
            texture = region.a ?: return null,
            destination = KoolCanvasRect(drawLeft, drawTop, drawRight, drawBottom),
            source = KoolCanvasRect(
                sourceLeft + region.b,
                sourceTop + region.c,
                sourceRight + region.b,
                sourceBottom + region.c,
            ),
        )
    }

    private fun textureAtlasFor(texture: Texture): TextureAtlas? {
        if (!enableTextureAtlas || targetTexture != null || texture is TeamColorTexture) {
            return null
        }
        if (texture.m() >= ATLAS_MAX_TEXTURE_WIDTH || texture.l() >= ATLAS_MAX_TEXTURE_HEIGHT) {
            return null
        }
        return textureAtlas ?: TextureAtlas(ATLAS_WIDTH, ATLAS_HEIGHT, this).also {
            textureAtlas = it
        }
    }

    private fun drawTiledTexture(
        texture: Texture,
        destination: KoolCanvasRect,
        offsetX: Float,
        offsetY: Float,
        repeatInsetX: Int,
        repeatInsetY: Int,
        paint: KoolPaint?,
    ) {
        if (destination.isEmpty) return
        val textureWidth = texture.width()
        val textureHeight = texture.height()
        if (textureWidth <= 0 || textureHeight <= 0) return

        val stepX = textureWidth - repeatInsetX
        val stepY = textureHeight - repeatInsetY
        if (stepX <= 0 || stepY <= 0) return

        val normalizedOffsetX = offsetX.modPositive(textureWidth.toFloat())
        val normalizedOffsetY = offsetY.modPositive(textureHeight.toFloat())
        val teamColorEffect = (texture as? TeamColorTexture)?.toCanvasTeamColorEffect()
        val resolvedTexture = if (teamColorEffect != null) {
            texture.sourceTexture()
        } else {
            texture.resolveForKool()
        }
        registerTexturePixels(resolvedTexture)
        val textureRef = freezeTextureForOffscreenDraw(resolvedTexture, resolvedTexture.toCanvasTextureRef())
        val basePaint = paint.toCanvasPaint()
        val canvasPaint = KoolCanvasPaintOperations.textureEffect(basePaint, teamColorEffect, AVOID_TEXTURE_PAINT_COPIES)
            .withDirectBlitTextureBlend(resolvedTexture)
            .asCanonicalTexturePaint()
        var tileLeft = destination.left - normalizedOffsetX
        val firstTileTop = destination.top - normalizedOffsetY
        var tileCount = 0

        while (tileLeft < destination.right) {
            var tileTop = firstTileTop
            while (tileTop < destination.bottom) {
                if (++tileCount > TILE_IMAGE_LIMIT) return
                var tileWidth = (destination.right - tileLeft).coerceAtMost(textureWidth.toFloat())
                var tileHeight = (destination.bottom - tileTop).coerceAtMost(textureHeight.toFloat())
                if (tileWidth <= 0f || tileHeight <= 0f) break

                var sourceLeft = 0f
                var sourceTop = 0f
                var drawLeft = tileLeft
                var drawTop = tileTop
                val drawRight = tileLeft + tileWidth
                val drawBottom = tileTop + tileHeight

                if (drawLeft < destination.left) {
                    val delta = destination.left - drawLeft
                    sourceLeft += delta
                    drawLeft += delta
                }
                if (drawTop < destination.top) {
                    val delta = destination.top - drawTop
                    sourceTop += delta
                    drawTop += delta
                }

                tileWidth -= sourceLeft
                tileHeight -= sourceTop
                if (tileWidth > 0f && tileHeight > 0f) {
                    commandBuffer.drawTexture(
                        texture = textureRef,
                        destination = KoolCanvasRect(drawLeft, drawTop, drawRight, drawBottom),
                        paint = canvasPaint,
                        source = KoolCanvasRect(sourceLeft, sourceTop, sourceLeft + tileWidth, sourceTop + tileHeight),
                    )
                }
                tileTop += stepY
            }
            tileLeft += stepX
        }
    }

    private fun freezeTextureForOffscreenDraw(
        texture: Texture,
        textureRef: KoolCanvasTextureRef
    ): KoolCanvasTextureRef {
        // A GPU-replay target is never rasterised on the CPU, so it must not bake pixel/frame snapshots
        // of the nested targets it samples into its recorded commands. The frame lease already pins the
        // exact source pixel revision, while the snapshot registers/unregisters those pixels on a
        // lifetime that the CPU raster path owns; following it here made kool bind an already released
        // texture inside the cell pass (measured: the A/B candidate died with
        // "Texture2d .../snapshot-N/... is already released").
        if (gpuReplayTarget) return textureRef
        val targetId = targetTexture?.toCanvasTextureId() ?: return textureRef
        val sourceId = textureRef.id
        if (sourceId == targetId) return textureRef

        val sourceFrame = textureStore.frame(sourceId)
        if (sourceFrame != null) {
            pendingFrameDependencySnapshots[sourceId]?.takeIf { it.frame === sourceFrame }?.let { snapshot ->
                return textureRef.copy(id = snapshot.textureId)
            }
            retainExistingFrameSnapshotDependencies(sourceFrame, visiting = setOf(sourceId))
            val snapshot = snapshotFrameDependencies(sourceFrame, visiting = setOf(sourceId))
            val snapshotId = sourceId.snapshotId()
            textureStore.registerFrame(snapshotId, snapshot.frame)
            val activeIds = targetTexture?.let(targetStates::get)?.activeAuxiliaryIds ?: emptySet()
            snapshot.auxiliaryIds.filterNotTo(pendingFrameDependencySnapshotIds, activeIds::contains)
            pendingFrameDependencySnapshotIds += snapshotId
            pendingFrameDependencySnapshots[sourceId] = ImmediateFrameSnapshot(sourceFrame, snapshotId)
            return textureRef.copy(id = snapshotId)
        }

        if (targetStates[texture]?.mode == null || targetStates[texture]?.mode == RenderTargetMode.DEFAULT) {
            return textureRef
        }
        val legacyRegistration = if (TEXTURE_METADATA_REUSE_ENABLED) null else texture.pixelRegistration()
        val sourceImage = if (textureStore is KoolCanvasCpuTextureStore) textureStore.argbImageView(sourceId) else null
        pendingPixelDependencySnapshots[sourceId]?.takeIf {
            (if (legacyRegistration != null) it.registration == legacyRegistration else it.registration.matchesTexture(texture)) &&
                it.sourceImage === sourceImage
        }?.let { snapshot ->
            return textureRef.copy(id = snapshot.textureId)
        }
        val registration = legacyRegistration ?: texture.pixelRegistration()
        if (IMMUTABLE_PIXEL_SNAPSHOT_REUSE_ENABLED && textureStore is KoolCanvasCpuTextureStore) {
            val snapshotId = sourceId.snapshotId()
            textureStore.snapshotPixels(sourceId, snapshotId)?.let { image ->
                pendingFrameDependencySnapshotIds += snapshotId
                pendingPixelDependencySnapshots[sourceId] = ImmediatePixelSnapshot(registration, snapshotId, image)
                return textureRef.copy(id = snapshotId)
            }
        }
        val ownedPixels = texture.argbPixelsCopy
        // Without the bleed pass the store keeps the array it is handed, so fall back to a copy
        // when the pixels would otherwise alias an image the store already owns.
        val pixels = ownedPixels ?: textureStore.argbImageView(sourceId)?.pixels?.copyOf() ?: return textureRef
        val snapshotId = sourceId.snapshotId()
        if (texture.usesPremultipliedAlpha()) {
            textureStore.registerPremultipliedArgb(snapshotId, registration.width, registration.height, pixels)
        } else {
            textureStore.registerArgb(
                snapshotId,
                registration.width,
                registration.height,
                pixels,
                alphaBleed = texture.alphaBleedRequired,
            )
        }
        pendingFrameDependencySnapshotIds += snapshotId
        pendingPixelDependencySnapshots[sourceId] = ImmediatePixelSnapshot(registration, snapshotId, sourceImage)
        return textureRef.copy(id = snapshotId)
    }

    private fun retainExistingFrameSnapshotDependencies(
        frame: KoolCanvasFrame,
        visiting: Set<KoolCanvasTextureId>,
        remainingDepth: Int = MAX_FRAME_SNAPSHOT_DEPENDENCY_DEPTH,
    ) {
        if (remainingDepth <= 0) {
            return
        }
        val retainer = textureStore as? KoolCanvasFrameSnapshotRetainer ?: return
        frame.commands.forEach { command ->
            if (command !is KoolCanvasCommand.DrawTexture || command.texture.id in visiting) {
                return@forEach
            }
            val nestedFrame = textureStore.frame(command.texture.id)
            val isPixelSnapshot = textureStore is KoolCanvasCpuTextureStore &&
                textureStore.argbImageView(command.texture.id) != null
            if (command.texture.id.isFrameSnapshotId && (nestedFrame != null || isPixelSnapshot) &&
                command.texture.id !in pendingFrameDependencySnapshotIds &&
                command.texture.id !in (targetTexture?.let(targetStates::get)?.activeAuxiliaryIds ?: emptySet())) {
                retainer.retainFrameSnapshot(command.texture.id)
                pendingFrameDependencySnapshotIds += command.texture.id
            }
            if (nestedFrame != null) {
                retainExistingFrameSnapshotDependencies(
                    frame = nestedFrame,
                    visiting = visiting + command.texture.id,
                    remainingDepth = remainingDepth - 1,
                )
            }
        }
    }

    private fun releasePendingImmediateFrameSnapshots() {
        pendingFrameDependencySnapshotIds.forEach(textureStore::unregister)
        pendingFrameDependencySnapshotIds.clear()
        pendingFrameDependencySnapshots.clear()
        pendingPixelDependencySnapshots.clear()
    }

    private fun registerTexturePixels(texture: Texture) {
        val id = texture.toCanvasTextureId()
        val width = texture.width().coerceAtLeast(1)
        val height = texture.height().coerceAtLeast(1)
        val legacyRegistration = if (TEXTURE_METADATA_REUSE_ENABLED) null else texture.pixelRegistration(width, height)
        val immediateTarget = if (textureStore is KoolCanvasCpuTextureStore) {
            targetStates[texture]?.takeIf { it.mode == RenderTargetMode.IMMEDIATE }
        } else null
        if (immediateTarget != null) {
            val frame = textureStore.frame(id)
            val publishedFrame = registeredImmediateFrames[id]
                ?.takeIf { it.matchesTexture(texture, frame, width, height, legacyRegistration) }
                ?: immediateTarget.engine.registeredImmediateFrames[id]
                    ?.takeIf { it.matchesTexture(texture, frame, width, height, legacyRegistration) }
            if (publishedFrame != null) {
                registeredTexturePixelRevisions[id] = legacyRegistration ?: publishedFrame.registration
                registeredImmediateFrames[id] = publishedFrame
                return
            }
            val image = textureStore.argbImageView(id)
            val published = registeredImmediatePixels[id]
                ?.takeIf { it.matchesTexture(texture, image, width, height, legacyRegistration) }
                ?: immediateTarget.engine.registeredImmediatePixels[id]
                    ?.takeIf { it.matchesTexture(texture, image, width, height, legacyRegistration) }
            if (published != null) {
                registeredTexturePixelRevisions[id] = legacyRegistration ?: published.registration
                registeredImmediatePixels[id] = published
                return
            }
        } else if (if (legacyRegistration != null) registeredTexturePixelRevisions[id] == legacyRegistration
            else registeredTexturePixelRevisions[id]?.matchesTexture(texture, width, height) == true) {
            return
        }
        // The store converts and uploads these pixels itself and the texture keeps owning the array,
        // so hand over the live array instead of cloning every changed texture on every frame.
        val pixels = texture.argbPixelsRef ?: return
        val registration = legacyRegistration ?: texture.pixelRegistration(width, height)
        if (texture.usesPremultipliedAlpha()) {
            textureStore.registerPremultipliedArgb(id, width, height, pixels)
        } else {
            textureStore.registerArgb(id, width, height, pixels, alphaBleed = texture.alphaBleedRequired)
        }
        registeredTexturePixelRevisions[id] = registration
        registeredImmediateFrames.remove(id)
        if (immediateTarget != null) rememberImmediatePixels(id, registration, texture.alphaBleedRequired)
    }

    private fun rememberImmediatePixels(id: KoolCanvasTextureId, registration: TexturePixelRegistration, alphaBleed: Boolean) {
        val image = textureStore.argbImageView(id) ?: return
        registeredImmediatePixels[id] = RegisteredImmediatePixels(registration, alphaBleed, WeakReference(image))
    }

    private fun Texture.resolveForKool(): Texture =
        (this as? TeamColorTexture)?.resolveSourceTexture() ?: this

    private fun TeamColorTexture.toCanvasTeamColorEffect(): KoolCanvasTextureEffect.TeamColor =
        KoolCanvasTextureEffect.TeamColor(
            mode = when (colorMode) {
                ColorMode.hueAdd -> KoolCanvasTeamColorMode.HueAdd
                ColorMode.hueShift -> KoolCanvasTeamColorMode.HueShift
                else -> KoolCanvasTeamColorMode.PureGreen
            },
            color = KoolCanvasColor(teamColor),
            amount = teamColorAmount,
        )

    private fun KoolDisplacementEffect.toCanvasDisplacementEffect(): KoolCanvasTextureEffect.Displacement? {
        val baseTexture = screenBase ?: return null
        registerTexturePixels(baseTexture.resolveForKool())
        return KoolCanvasTextureEffect.Displacement(
            screenBase = baseTexture.resolveForKool().toCanvasTextureRef(),
            offsetBy = offsetBy,
        )
    }

    private fun Texture.toCanvasTextureRef(): KoolCanvasTextureRef {
        if (!TEXTURE_METADATA_REUSE_ENABLED) {
            return KoolCanvasTextureRef(toCanvasTextureId(), width().coerceAtLeast(0), height().coerceAtLeast(0),
                f(), requiresOrderedAlpha(), usesPremultipliedAlpha())
        }
        val metadata = canvasMetadata()
        val width = width().coerceAtLeast(0)
        val height = height().coerceAtLeast(0)
        val hasAlpha = f()
        val orderedAlpha = requiresOrderedAlpha()
        val premultiplied = usesPremultipliedAlpha()
        metadata.reference?.let { reference ->
            if (reference.width == width && reference.height == height && reference.hasAlpha == hasAlpha &&
                reference.requiresOrderedAlpha == orderedAlpha && reference.premultipliedAlpha == premultiplied) {
                return reference
            }
        }
        return KoolCanvasTextureRef(metadata.id, width, height, hasAlpha, orderedAlpha, premultiplied)
            .also { metadata.reference = it }
    }

    private fun KoolCanvasPaint.withDirectBlitTextureBlend(texture: Texture): KoolCanvasPaint {
        if (!directBlitActive ||
            blendMode != KoolCanvasBlendMode.SourceOver ||
            color.alpha != 255 ||
            texture.f()
        ) {
            return this
        }
        return copy(blendMode = KoolCanvasBlendMode.Source)
    }

    private fun KoolCanvasPaint.asCanonicalTexturePaint(): KoolCanvasPaint {
        if (color != KoolCanvasColor.White ||
            alphaMultiplier != 1f ||
            blendMode != KoolCanvasBlendMode.SourceOver ||
            textureEffect != null
        ) {
            return this
        }
        return when (textureFilter) {
            KoolCanvasTextureFilter.Nearest -> KoolCanvasPaint.DefaultNearest
            KoolCanvasTextureFilter.Linear -> KoolCanvasPaint.Default
        }
    }

    private fun KoolCanvasPaint.withDirectBlitRectBlend(): KoolCanvasPaint {
        if (!directBlitActive ||
            blendMode != KoolCanvasBlendMode.SourceOver ||
            color.alpha != 255
        ) {
            return this
        }
        return copy(blendMode = KoolCanvasBlendMode.Source)
    }

    private fun Texture.toCanvasTextureId(): KoolCanvasTextureId =
        if (TEXTURE_METADATA_REUSE_ENABLED) canvasMetadata().id else KoolCanvasTextureId("legacy-texture-$d")

    private fun Texture.canvasMetadata(): TextureMetadata {
        textureMetadata[this]?.let { metadata ->
            if (metadata.legacyId == d) return metadata
        }
        return TextureMetadata(d, KoolCanvasTextureId("legacy-texture-$d"))
            .also { textureMetadata[this] = it }
    }

    private fun Texture.pixelRegistration(
        width: Int = width().coerceAtLeast(1),
        height: Int = height().coerceAtLeast(1),
    ): TexturePixelRegistration =
        TexturePixelRegistration(
            width = width,
            height = height,
            // Only real pixel edits count here. `e` also advances on every commit
            // (`flushPendingTarget`), so folding it in made an unchanged 512x512 layer buffer cell
            // look dirty on every frame and re-ran the ARGB -> RGBA conversion and the texture
            // upload for it, once per cell and frame.
            pixelRevision = getPixelRevision(),
            premultipliedAlpha = usesPremultipliedAlpha(),
        )

    private fun Texture.fullSourceRect(): KoolCanvasRect =
        KoolCanvasRect.fromSize(width().toFloat(), height().toFloat())

    private fun Rect.toCanvasRect(): KoolCanvasRect =
        KoolCanvasRect(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat())

    private fun RectF.toCanvasRect(): KoolCanvasRect =
        KoolCanvasRect(a, b, c, d)

    private fun KoolPaint?.toCanvasPaint(): KoolCanvasPaint {
        if (this == null) {
            return KoolCanvasPaint.DefaultNearest
        }
        val colorFilter = colorFilter()
        val color = color().applyKoolColorFilter(colorFilter)
        val textureEffect = (this as? GamePaint)?.displacementEffect()?.toCanvasDisplacementEffect()
        val textureFilter = if (legacyTextureFilteringEnabled()) {
            KoolCanvasTextureFilter.Linear
        } else {
            KoolCanvasTextureFilter.Nearest
        }
        val style = when (style()) {
            KoolPaint.Style.STROKE -> KoolCanvasPaintStyle.Stroke
            KoolPaint.Style.FILL_AND_STROKE -> KoolCanvasPaintStyle.FillAndStroke
            else -> KoolCanvasPaintStyle.Fill
        }
        val width = strokeWidth().coerceAtLeast(1f)
        val blend = getBlendMode() ?: colorFilter.legacyKoolBlendMode() ?: KoolCanvasBlendMode.SourceOver
        val size = textSize().coerceAtLeast(1f)
        val align = when (textAlign()) {
            KoolPaint.Align.CENTER -> KoolCanvasTextAlign.Center
            KoolPaint.Align.RIGHT -> KoolCanvasTextAlign.Right
            else -> KoolCanvasTextAlign.Left
        }
        val typeface = typefaceKey()
        if (REUSE_PAINT_SNAPSHOTS) canvasSnapshot?.let { previous ->
            // Public legacy fields and overridden getters bypass stateRevision. Compare every
            // normalized recorded value, including effects, instead of trusting that revision.
            if (previous.color.argb == color && previous.style == style && previous.strokeWidth == width &&
                previous.textureFilter == textureFilter && previous.blendMode == blend &&
                previous.textSize == size && previous.textAlign == align && previous.typefaceKey == typeface &&
                previous.textureEffect == textureEffect) return previous
        }
        return KoolCanvasPaint(
            color = KoolCanvasColor(color),
            alphaMultiplier = 1f,
            style = style,
            strokeWidth = width,
            textureFilter = textureFilter,
            blendMode = blend,
            textSize = size,
            textAlign = align,
            typefaceKey = typeface,
            textureEffect = textureEffect,
        ).also { if (REUSE_PAINT_SNAPSHOTS) canvasSnapshot = it }
    }

    private fun KoolPaint.legacyTextureFilteringEnabled(): Boolean =
        c()

    private fun KoolPaint?.typefaceKey(): String? = this?.typeface()?.koolKey

    private fun multiplyChannel(source: Int, multiply: Int): Int = (source * multiply) / 255

    private val Int.alphaComponent: Int get() = (this ushr 24) and 0xff

    private val Int.redComponent: Int get() = (this ushr 16) and 0xff

    private val Int.greenComponent: Int get() = (this ushr 8) and 0xff

    private val Int.blueComponent: Int get() = this and 0xff

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha and 0xff) shl 24) or
                ((red and 0xff) shl 16) or
                ((green and 0xff) shl 8) or
                (blue and 0xff)

    private fun Float.modPositive(divisor: Float): Float {
        if (this == 0f) return 0f
        var value = this % divisor
        if (value < 0f) {
            value += divisor
        }
        return value
    }

    private fun Float.isWholePixel(): Boolean = this == roundToInt().toFloat()

    private data class AtlasDraw(
        val texture: Texture,
        val destination: KoolCanvasRect,
        val source: KoolCanvasRect,
    )

    private data class TextureQuad(
        val source: KoolCanvasRect,
        val destination: KoolCanvasRect,
    )

    private data class FrameSnapshot(
        val frame: KoolCanvasFrame,
        val auxiliaryIds: List<KoolCanvasTextureId>,
    )

    private data class FrameDependencySnapshot(
        val textureId: KoolCanvasTextureId,
        val auxiliaryIds: List<KoolCanvasTextureId>,
    )

    private data class ImmediateFrameSnapshot(
        val frame: KoolCanvasFrame,
        val textureId: KoolCanvasTextureId,
    )

    private data class ImmediatePixelSnapshot(
        val registration: TexturePixelRegistration,
        val textureId: KoolCanvasTextureId,
        val sourceImage: KoolCanvasArgbImage?,
    )

    private data class TargetTextureCommit(
        val textureId: KoolCanvasTextureId,
        val auxiliaryIds: List<KoolCanvasTextureId>,
    )

    private data class TexturePixelRegistration(
        val width: Int,
        val height: Int,
        val pixelRevision: Int,
        val premultipliedAlpha: Boolean,
    ) {
        fun matchesTexture(texture: Texture, width: Int = texture.width().coerceAtLeast(1),
            height: Int = texture.height().coerceAtLeast(1)): Boolean =
            this.width == width && this.height == height && pixelRevision == texture.getPixelRevision() &&
                premultipliedAlpha == texture.usesPremultipliedAlpha()
    }

    // Values never retain their weak Texture key. Shared offscreen engines keep one ID string and
    // immutable draw reference per live texture; changes publish new metadata without mutating
    // references already held by frames or leases. Legacy d remains mutable and is checked above.
    private class TextureMetadata(val legacyId: Int, val id: KoolCanvasTextureId) {
        var reference: KoolCanvasTextureRef? = null
    }

    private data class RegisteredImmediatePixels(
        val registration: TexturePixelRegistration,
        val alphaBleed: Boolean,
        val image: WeakReference<KoolCanvasArgbImage>,
    ) {
        fun matchesTexture(texture: Texture, image: KoolCanvasArgbImage?, width: Int = texture.width().coerceAtLeast(1),
            height: Int = texture.height().coerceAtLeast(1), legacyRegistration: TexturePixelRegistration? = null): Boolean =
            image != null && (if (legacyRegistration != null) registration == legacyRegistration
                else registration.matchesTexture(texture, width, height)) &&
                alphaBleed == texture.alphaBleedRequired && this.image.get() === image
    }

    private data class RegisteredImmediateFrame(
        val registration: TexturePixelRegistration,
        val alphaBleed: Boolean,
        val frame: WeakReference<KoolCanvasFrame>,
    ) {
        fun matchesTexture(texture: Texture, frame: KoolCanvasFrame?, width: Int = texture.width().coerceAtLeast(1),
            height: Int = texture.height().coerceAtLeast(1), legacyRegistration: TexturePixelRegistration? = null): Boolean =
            frame != null && (if (legacyRegistration != null) registration == legacyRegistration
                else registration.matchesTexture(texture, width, height)) &&
                alphaBleed == texture.alphaBleedRequired && this.frame.get() === frame
    }

    private class CpuTargetProfile(
        private val texture: Texture,
        private val width: Int,
        private val height: Int,
    ) {
        var totalCommands: Int = 0
        var clearCommands: Int = 0
        var textureCommands: Int = 0
        var rectCommands: Int = 0
        var linearTextureCommands: Int = 0
        var nearestTextureCommands: Int = 0
        var fastTextureCopies: Int = 0
        var sampledTextureDraws: Int = 0
        var sampledPixels: Long = 0L
        private var transformedTextureCommands: Int = 0
        private var tintedTextureCommands: Int = 0
        private var sourceBlendTextureCommands: Int = 0
        private var sourceOverTextureCommands: Int = 0
        private var stripeRaster = false

        fun newStripeProfile(): CpuTargetProfile = CpuTargetProfile(texture, width, height)

        fun mergeStripeProfiles(profiles: List<CpuTargetProfile>, commands: Int) {
            stripeRaster = true
            totalCommands = commands
            clearCommands = profiles.sumOf { it.clearCommands }
            textureCommands = profiles.sumOf { it.textureCommands }
            rectCommands = profiles.sumOf { it.rectCommands }
            linearTextureCommands = profiles.sumOf { it.linearTextureCommands }
            nearestTextureCommands = profiles.sumOf { it.nearestTextureCommands }
            fastTextureCopies = profiles.sumOf { it.fastTextureCopies }
            sampledTextureDraws = profiles.sumOf { it.sampledTextureDraws }
            sampledPixels = profiles.sumOf { it.sampledPixels }
            transformedTextureCommands = profiles.sumOf { it.transformedTextureCommands }
            tintedTextureCommands = profiles.sumOf { it.tintedTextureCommands }
            sourceBlendTextureCommands = profiles.sumOf { it.sourceBlendTextureCommands }
            sourceOverTextureCommands = profiles.sumOf { it.sourceOverTextureCommands }
        }

        fun recordTextureCommand(command: KoolCanvasCommand.DrawTexture) {
            textureCommands += 1
            when (command.paint.textureFilter) {
                KoolCanvasTextureFilter.Linear -> linearTextureCommands += 1
                KoolCanvasTextureFilter.Nearest -> nearestTextureCommands += 1
            }
            if (command.state.transform !== KoolCanvasTransform.Identity) {
                transformedTextureCommands += 1
            }
            if (command.paint.color.argb != -0x1 || command.paint.alphaMultiplier != 1f) {
                tintedTextureCommands += 1
            }
            when (command.paint.blendMode) {
                KoolCanvasBlendMode.Source -> sourceBlendTextureCommands += 1
                KoolCanvasBlendMode.SourceOver -> sourceOverTextureCommands += 1
                else -> Unit
            }
        }

        fun log(elapsedNanos: Long, success: Boolean) {
            val elapsedMillis = java.lang.String.format(java.util.Locale.US, "%.3f", elapsedNanos / 1_000_000.0)
            println(
                "RWX_KOOL_CPU_TARGET_PROFILE:" +
                        " texture=${texture.sourceName() ?: texture.toString()}" +
                        " size=${width}x${height}" +
                        " success=$success" +
                        " elapsedMs=$elapsedMillis" +
                        " commands=$totalCommands" +
                        " clear=$clearCommands" +
                        " texture=$textureCommands" +
                        " rect=$rectCommands" +
                        " linearTexture=$linearTextureCommands" +
                        " nearestTexture=$nearestTextureCommands" +
                        " fastCopy=$fastTextureCopies" +
                        " sampled=$sampledTextureDraws" +
                        " sampledPixels=$sampledPixels" +
                        " transformedTexture=$transformedTextureCommands" +
                        " tintedTexture=$tintedTextureCommands" +
                        " sourceBlend=$sourceBlendTextureCommands" +
                        " sourceOverBlend=$sourceOverTextureCommands" +
                        " stripeRaster=$stripeRaster",
            )
        }

        companion object {
            fun createIfEnabled(texture: Texture, frame: KoolCanvasFrame, width: Int, height: Int): CpuTargetProfile? {
                if (!java.lang.Boolean.getBoolean(CPU_TARGET_PROFILE_PROPERTY) || frame.commands.isEmpty()) {
                    return null
                }
                return CpuTargetProfile(texture, width, height)
            }
        }
    }

    internal companion object {
        private val AVOID_TEXTURE_PAINT_COPIES = System.getenv("RWX_AVOID_TEXTURE_PAINT_COPIES") == "1"
        private val REUSE_PAINT_SNAPSHOTS = System.getenv("RWX_REUSE_PAINT_SNAPSHOTS") == "1"
        /**
         * Pack offscreen-cell textures into one atlas so consecutive tile draws share a texture id.
         *
         * `RWX_OFFSCREEN_TEXTURE_ATLAS=0` restores the previous per-texture behaviour.
         */
        internal val OFFSCREEN_TEXTURE_ATLAS = System.getenv("RWX_OFFSCREEN_TEXTURE_ATLAS") != "0"
        private val PARALLEL_CELL_RASTER_ENABLED = (System.getenv("RWX_PARALLEL_CELL_RASTER") == "1")
            .also { println("[RWX canvas] parallelCellRaster=$it") }
        private val ADAPTIVE_CELL_RASTER_ENABLED = adaptiveCellRasterEnabled(System.getenv())
            .also { println("[RWX canvas] adaptiveCellRaster=$it") }
        private val TEXTURE_METADATA_REUSE_ENABLED = (System.getenv("RWX_DISABLE_TEXTURE_METADATA_REUSE") != "1")
            .also { println("[RWX canvas] textureMetadataReuse=$it") }

        /**
         * Map cells become real GPU offscreen passes instead of CPU-rasterised bitmaps.
         *
         * Accepted switches, in precedence order:
         *  - `RWX_GPU_MAP_CELL_TARGETS=0` forces the legacy CPU raster path and wins over everything,
         *    so the verified A/B comparison stays available;
         *  - `RWX_GPU_MAP_CELL_TARGETS=1`, `rwx.kool.gpuMapCellTargets=true`, or the existing
         *    `RWX_GPU_MAP_CELL_CACHE=1` used by `desktop/tools/map_pan_replay.py --gpu-map-cell-cache`.
         */
        internal val GPU_RENDER_TARGETS_ENABLED = gpuRenderTargetsEnabled(
            System.getenv(), System.getProperty("rwx.kool.gpuMapCellTargets"),
        ).also {
            println("[RWX canvas] gpuMapCellTargets=$it")
            if (it) {
                // Verified pixel-exact by :desktop:runGpuMapCellOracle, but the performance A/B and the
                // long-run memory check are still open (see docs/gpu-map-cell-targets.md).
                println("[RWX canvas] WARNING: gpuMapCellTargets overridden by " + "RWX_GPU_MAP_CELL_TARGETS/RWX_GPU_MAP_CELL_CACHE")
            }
        }

        internal fun gpuRenderTargetsEnabled(environment: Map<String, String>, property: String?): Boolean {
            // Default off. Flipping this to `return true` is not a one-line change: hosts that live
            // outside a Vulkan context (the scene-host failure-path tests, and any headless use) then
            // enter the GPU target path and break, so they have to opt out first. The measured results
            // behind the decision are in docs/gpu-map-cell-targets.md.
            if (environment["RWX_GPU_MAP_CELL_TARGETS"] == "0") return false
            if (environment["RWX_GPU_MAP_CELL_TARGETS"] == "1") return true
            if (environment["RWX_GPU_MAP_CELL_CACHE"] == "1") return true
            if (property == "true") return true
            if (property == "false") return false
            // Default on. Activation additionally requires the host to report a live kool backend
            // (setGpuOffscreenPassesAvailable, wired in the DI host factory).
            return true
        }

        private val IMMUTABLE_PIXEL_SNAPSHOT_REUSE_ENABLED =
            System.getenv("RWX_DISABLE_IMMUTABLE_PIXEL_SNAPSHOT_REUSE") != "1"
        private val RASTER_SCALAR_MAPPING_ENABLED = System.getenv("RWX_DISABLE_RASTER_SCALAR_MAPPING") != "1"
        private val OPAQUE_SOURCE_OVER_FAST_PATH_ENABLED =
            System.getenv("RWX_DISABLE_OPAQUE_SOURCE_OVER_FAST_PATH") != "1"
        val BACKEND_CAPABILITIES = GraphicsBackendCapabilities(
            fixedLayerBufferPixelSize = KoolLayerBufferSizing.startupPixels,
            extraLayerBufferCells = 1,
            layerBufferScrollPreloadWorldMargin = 256,
            clearLayerBuffersBeforeCopy = true,
            supportsLayerBufferPreRendering = false,
            supportsSmoothFogLayerBuffers = false,
            requiresFogAtlasLock = false,
            requiresImageTintColorFilter = false,
            // Map cells render into a real offscreen pass; see KoolCanvasGpuTargetPasses.
            supportsGpuRenderTargets = GPU_RENDER_TARGETS_ENABLED,
        )
        val FRAME_SNAPSHOT_SERIAL = AtomicInteger()
        const val CPU_TARGET_PROFILE_PROPERTY: String = "rwx.kool.cpuTargetProfile"
        const val DEFAULT_TEXT_SIZE: Float = 16f
        const val IMAGE_HEADER_READ_LIMIT: Int = 64 * 1024
        const val TILE_IMAGE_LIMIT: Int = 2000
        const val ATLAS_WIDTH: Int = 1024
        const val ATLAS_HEIGHT: Int = 1024
        const val ATLAS_MAX_TEXTURE_WIDTH: Int = 450
        const val ATLAS_MAX_TEXTURE_HEIGHT: Int = 100
        const val MAX_FRAME_SNAPSHOT_DEPENDENCY_DEPTH: Int = 64
        const val PARALLEL_RASTER_MIN_PIXELS: Int = 64 * 1024
        const val ADAPTIVE_CELL_RASTER_MIN_TEXTURE_WORK: Long = 4L * PARALLEL_RASTER_MIN_PIXELS
        const val PARALLEL_RASTER_MIN_ROWS_PER_WORKER: Int = 16
        const val PNG_COLOR_GRAYSCALE: Int = 0
        const val PNG_COLOR_RGB: Int = 2
        const val PNG_COLOR_INDEXED: Int = 3
        const val PNG_COLOR_GRAYSCALE_ALPHA: Int = 4
        const val PNG_COLOR_RGBA: Int = 6
        internal fun adaptiveCellRasterEnabled(environment: Map<String, String>): Boolean =
            environment["RWX_PARALLEL_CELL_RASTER"] == "1" && environment["RWX_ADAPTIVE_CELL_RASTER"] == "1"
        val DEFAULT_TEXTURE_SIZE: Pair<Int, Int> = 64 to 64
        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )

        object RasterWorkerPool {
            internal val insideWorker: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
            private val workerSerial = AtomicInteger()
            val workerCount: Int = System.getProperty("rwx.koolRasterThreads")
                ?.toIntOrNull()
                ?.coerceIn(1, 8)
                ?: (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
            val executor: ExecutorService = Executors.newFixedThreadPool(workerCount) { runnable ->
                Thread(runnable, "rwx-kool-raster-${workerSerial.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }

        fun KoolCanvasTextureId.snapshotId(): KoolCanvasTextureId =
            KoolCanvasTextureId("$value/snapshot-${FRAME_SNAPSHOT_SERIAL.incrementAndGet()}")

        val KoolCanvasTextureId.isFrameSnapshotId: Boolean
            get() = value.contains("/snapshot-")

        data class DecodedImage(
            val width: Int,
            val height: Int,
            val argbPixels: IntArray,
        )

        val drawableNamesById: Map<Int, String> by lazy {
            runCatching {
                R.drawable::class.java.fields.associate { field ->
                    field.getInt(null) to field.name
                }
            }.getOrDefault(emptyMap())
        }

        fun drawableAssetPath(drawableName: String): String? =
            imageFileNames(drawableName)
                .map { fileName -> "drawable/$fileName" }
                .firstOrNull(::assetExists)

        fun assetPathForImagePath(path: String): String? {
            val normalized = path.replace('\\', '/')
            val candidates = mutableListOf<String>()
            if (normalized.startsWith("assets/")) {
                candidates += normalized.removePrefix("assets/")
            }
            if (!normalized.startsWith("/")) {
                candidates += normalized
            }

            val file = File(normalized)
            if (file.isFile) {
                val assetsDir = File("assets").canonicalFile
                val canonicalFile = file.canonicalFile
                if (canonicalFile.path.startsWith(assetsDir.path + File.separator)) {
                    candidates += canonicalFile.relativeTo(assetsDir).invariantSeparatorsPath
                }
            }

            val fileName = normalized.substringAfterLast('/')
            candidates += "drawable/$fileName"
            return candidates.firstOrNull(::assetExists)
        }

        fun assetExists(assetPath: String): Boolean = assetFile(assetPath) != null

        fun readAssetBytesFromFileSystem(assetPath: String): ByteArray? =
            assetFile(assetPath)?.readBytes()

        fun assetFile(assetPath: String): File? {
            val normalized = assetPath.removePrefix("assets/").replace('\\', '/')
            return assetRootCandidates()
                .map { root -> File(root, normalized) }
                .firstOrNull(File::isFile)
        }

        fun assetRootCandidates(): Sequence<File> {
            val cwd = File(System.getProperty("user.dir")).absoluteFile
            return generateSequence(cwd) { it.parentFile }
                .map { File(it, "assets") }
                .filter(File::isDirectory)
        }

        fun encodePng(width: Int, height: Int, argbPixels: IntArray): ByteArray {
            val safeWidth = width.coerceAtLeast(1)
            val safeHeight = height.coerceAtLeast(1)
            val raw = ByteArrayOutputStream((safeWidth * 4 + 1) * safeHeight)
            for (y in 0 until safeHeight) {
                raw.write(0)
                for (x in 0 until safeWidth) {
                    val argb = argbPixels.getOrElse(x + (y * safeWidth)) { 0 }
                    raw.write((argb ushr 16) and 0xff)
                    raw.write((argb ushr 8) and 0xff)
                    raw.write(argb and 0xff)
                    raw.write((argb ushr 24) and 0xff)
                }
            }

            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { output ->
                output.write(raw.toByteArray())
            }

            return ByteArrayOutputStream().apply {
                write(PNG_SIGNATURE)
                writePngChunk(
                    type = "IHDR",
                    data = ByteArrayOutputStream().apply {
                        writeIntBigEndian(safeWidth)
                        writeIntBigEndian(safeHeight)
                        write(8)
                        write(PNG_COLOR_RGBA)
                        write(0)
                        write(0)
                        write(0)
                    }.toByteArray(),
                )
                writePngChunk("IDAT", compressed.toByteArray())
                writePngChunk("IEND", byteArrayOf())
            }.toByteArray()
        }

        fun readPngImage(bytes: ByteArray): DecodedImage? {
            if (!bytes.isPngHeader()) return null
            var offset = 8
            var width = 0
            var height = 0
            var bitDepth = 0
            var colorType = -1
            var interlaceMethod = 0
            var palette: IntArray? = null
            var paletteAlpha: IntArray? = null
            var transparentGray: Int? = null
            var transparentRgb: IntArray? = null
            val idat = ByteArrayOutputStream()

            while (offset + 8 <= bytes.size) {
                val length = readIntBigEndian(bytes, offset)
                if (length < 0 || offset + 12L + length > bytes.size) return null
                val chunkDataOffset = offset + 8
                val chunkType = bytes.decodeToString(offset + 4, offset + 8)
                when (chunkType) {
                    "IHDR" -> {
                        if (length < 13) return null
                        width = readIntBigEndian(bytes, chunkDataOffset)
                        height = readIntBigEndian(bytes, chunkDataOffset + 4)
                        bitDepth = bytes[chunkDataOffset + 8].toUnsignedInt()
                        colorType = bytes[chunkDataOffset + 9].toUnsignedInt()
                        val compressionMethod = bytes[chunkDataOffset + 10].toUnsignedInt()
                        val filterMethod = bytes[chunkDataOffset + 11].toUnsignedInt()
                        interlaceMethod = bytes[chunkDataOffset + 12].toUnsignedInt()
                        if (
                            width <= 0 ||
                            height <= 0 ||
                            compressionMethod != 0 ||
                            filterMethod != 0 ||
                            interlaceMethod != 0
                        ) {
                            return null
                        }
                    }

                    "PLTE" -> {
                        if (length % 3 != 0) return null
                        palette = IntArray(length / 3) { index ->
                            val base = chunkDataOffset + (index * 3)
                            argb(
                                alpha = 255,
                                red = bytes[base].toUnsignedInt(),
                                green = bytes[base + 1].toUnsignedInt(),
                                blue = bytes[base + 2].toUnsignedInt(),
                            )
                        }
                    }

                    "tRNS" -> {
                        when (colorType) {
                            PNG_COLOR_GRAYSCALE -> if (length >= 2) {
                                transparentGray = readUnsignedShortBigEndian(bytes, chunkDataOffset) and 0xff
                            }

                            PNG_COLOR_RGB -> if (length >= 6) {
                                transparentRgb = intArrayOf(
                                    readUnsignedShortBigEndian(bytes, chunkDataOffset) and 0xff,
                                    readUnsignedShortBigEndian(bytes, chunkDataOffset + 2) and 0xff,
                                    readUnsignedShortBigEndian(bytes, chunkDataOffset + 4) and 0xff,
                                )
                            }

                            PNG_COLOR_INDEXED -> {
                                paletteAlpha = IntArray(length) { index ->
                                    bytes[chunkDataOffset + index].toUnsignedInt()
                                }
                            }
                        }
                    }

                    "IDAT" -> idat.write(bytes, chunkDataOffset, length)
                    "IEND" -> break
                }
                offset += 12 + length
            }

            if (bitDepth != 8) return null
            val channels = pngChannelCount(colorType) ?: return null
            if (width > Int.MAX_VALUE / channels || width * channels > Int.MAX_VALUE / height) return null
            val rowSize = width * channels
            val inflated = inflatePngData(idat.toByteArray(), expectedSize = (rowSize + 1) * height) ?: return null
            return decodePngRows(
                inflated = inflated,
                width = width,
                height = height,
                channels = channels,
                colorType = colorType,
                palette = palette,
                paletteAlpha = paletteAlpha,
                transparentGray = transparentGray,
                transparentRgb = transparentRgb,
            )
        }

        /**
         * Keep the CPU source used by IMMEDIATE targets in step with the platform texture loader.
         * The hand-written PNG path is intentionally kept above for the common case; platform
         * decoders cover JPEG, progressive JPEG and PNG variants outside that subset.
         */
        fun readPlatformImage(bytes: ByteArray, sourceName: String): DecodedImage? =
            readAndroidBitmap(bytes)
                ?: readJvmImage(bytes)
                ?: runCatching {
                    val encoded = Uint8Buffer(bytes.size)
                    bytes.forEachIndexed { index, value -> encoded[index] = value.toUByte() }
                    val image = runBlocking {
                        Assets.loadImageFromBuffer(
                            texData = encoded,
                            mimeType = MimeType.forFileName(sourceName),
                            format = TexFormat.RGBA,
                        )
                    } as? BufferedImageData2d ?: return@runCatching null
                    val rgba = image.data as? Uint8Buffer ?: return@runCatching null
                    val pixelCount = image.width * image.height
                    if (image.width <= 0 || image.height <= 0 || rgba.capacity < pixelCount * 4) {
                        return@runCatching null
                    }
                    val argbPixels = IntArray(pixelCount)
                    for (index in 0 until pixelCount) {
                        val offset = index * 4
                        argbPixels[index] = argb(
                            alpha = rgba[offset + 3].toInt() and 0xff,
                            red = rgba[offset].toInt() and 0xff,
                            green = rgba[offset + 1].toInt() and 0xff,
                            blue = rgba[offset + 2].toInt() and 0xff,
                        )
                    }
                    DecodedImage(image.width, image.height, argbPixels)
                }.getOrNull()

        /**
         * Match the legacy Android loader for formats outside the hand-written PNG path. Reflection
         * keeps this common module free of an Android compile dependency.
         */
        private fun readAndroidBitmap(bytes: ByteArray): DecodedImage? = runCatching {
            val bitmapFactory = Class.forName("android.graphics.BitmapFactory")
            val bitmap = bitmapFactory
                .getMethod(
                    "decodeByteArray",
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                .invoke(null, bytes, 0, bytes.size)
                ?: return@runCatching null
            val bitmapClass = bitmap.javaClass
            val width = bitmapClass.getMethod("getWidth").invoke(bitmap) as Int
            val height = bitmapClass.getMethod("getHeight").invoke(bitmap) as Int
            if (width <= 0 || height <= 0) return@runCatching null
            val pixels = IntArray(width * height)
            bitmapClass.getMethod(
                "getPixels",
                IntArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(bitmap, pixels, 0, width, 0, 0, width, height)
            runCatching { bitmapClass.getMethod("recycle").invoke(bitmap) }
            DecodedImage(width, height, pixels)
        }.getOrNull()

        private fun readJvmImage(bytes: ByteArray): DecodedImage? = runCatching {
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val image = imageIo
                .getMethod("read", InputStream::class.java)
                .invoke(null, ByteArrayInputStream(bytes))
                ?: return@runCatching null
            val imageClass = image.javaClass
            val width = imageClass.getMethod("getWidth").invoke(image) as Int
            val height = imageClass.getMethod("getHeight").invoke(image) as Int
            if (width <= 0 || height <= 0) return@runCatching null
            val pixels = IntArray(width * height)
            imageClass.getMethod(
                "getRGB",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                IntArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(image, 0, 0, width, height, pixels, 0, width)
            DecodedImage(width, height, pixels)
        }.getOrNull()

        fun readImageSize(inputStream: InputStream): Pair<Int, Int>? {
            val stream = if (inputStream.markSupported()) inputStream else BufferedInputStream(inputStream)
            stream.mark(IMAGE_HEADER_READ_LIMIT)
            return try {
                val header = ByteArray(24)
                if (stream.readFully(header) < header.size) {
                    null
                } else if (header.isPngHeader()) {
                    readIntBigEndian(header, 16) to readIntBigEndian(header, 20)
                } else {
                    stream.reset()
                    if (stream.read() == 0xff && stream.read() == 0xd8) {
                        readJpegSize(stream)
                    } else {
                        null
                    }
                }
            } finally {
                runCatching { stream.reset() }
            }
        }

        fun imageFileNames(nameWithoutExtension: String): List<String> =
            listOf(
                "$nameWithoutExtension.png",
                "$nameWithoutExtension.9.png",
                "$nameWithoutExtension.jpg",
                "$nameWithoutExtension.jpeg",
            )

        fun readJpegSize(inputStream: InputStream): Pair<Int, Int>? {
            while (true) {
                val prefix = inputStream.read()
                if (prefix < 0) return null
                if (prefix != 0xff) continue

                var marker = inputStream.read()
                while (marker == 0xff) {
                    marker = inputStream.read()
                }
                if (marker < 0) return null
                if (marker == 0xd9 || marker == 0xda) return null
                if (marker == 0x01 || marker in 0xd0..0xd7) continue

                val length = inputStream.readUnsignedShort()
                if (length < 2) return null
                if (isJpegStartOfFrame(marker)) {
                    if (length < 7 || inputStream.read() < 0) return null
                    val height = inputStream.readUnsignedShort()
                    val width = inputStream.readUnsignedShort()
                    return if (width > 0 && height > 0) width to height else null
                }
                if (!inputStream.skipFully(length - 2)) return null
            }
        }

        fun isJpegStartOfFrame(marker: Int): Boolean =
            marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc

        fun inflatePngData(compressed: ByteArray, expectedSize: Int): ByteArray? {
            val inflater = Inflater()
            return try {
                inflater.setInput(compressed)
                val output = ByteArray(expectedSize)
                var total = 0
                while (!inflater.finished() && total < expectedSize) {
                    val count = inflater.inflate(output, total, expectedSize - total)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        total += count
                    }
                }
                if (total == expectedSize) output else null
            } catch (_: RuntimeException) {
                null
            } finally {
                inflater.end()
            }
        }

        fun decodePngRows(
            inflated: ByteArray,
            width: Int,
            height: Int,
            channels: Int,
            colorType: Int,
            palette: IntArray?,
            paletteAlpha: IntArray?,
            transparentGray: Int?,
            transparentRgb: IntArray?,
        ): DecodedImage? {
            val rowSize = width * channels
            val pixels = IntArray(width * height)
            var inputOffset = 0
            var previous = ByteArray(rowSize)
            var current = ByteArray(rowSize)
            for (y in 0 until height) {
                if (inputOffset >= inflated.size) return null
                val filter = inflated[inputOffset++].toUnsignedInt()
                if (inputOffset + rowSize > inflated.size) return null
                for (x in 0 until rowSize) {
                    val raw = inflated[inputOffset + x].toUnsignedInt()
                    val left = if (x >= channels) current[x - channels].toUnsignedInt() else 0
                    val up = previous[x].toUnsignedInt()
                    val upLeft = if (x >= channels) previous[x - channels].toUnsignedInt() else 0
                    val value = when (filter) {
                        0 -> raw
                        1 -> raw + left
                        2 -> raw + up
                        3 -> raw + ((left + up) / 2)
                        4 -> raw + paethPredictor(left, up, upLeft)
                        else -> return null
                    }
                    current[x] = (value and 0xff).toByte()
                }
                inputOffset += rowSize
                for (x in 0 until width) {
                    pixels[x + (y * width)] = pngPixelToArgb(
                        row = current,
                        x = x,
                        colorType = colorType,
                        palette = palette,
                        paletteAlpha = paletteAlpha,
                        transparentGray = transparentGray,
                        transparentRgb = transparentRgb,
                    ) ?: return null
                }
                val swap = previous
                previous = current
                current = swap
            }
            return DecodedImage(width, height, pixels)
        }

        fun pngPixelToArgb(
            row: ByteArray,
            x: Int,
            colorType: Int,
            palette: IntArray?,
            paletteAlpha: IntArray?,
            transparentGray: Int?,
            transparentRgb: IntArray?,
        ): Int? =
            when (colorType) {
                PNG_COLOR_GRAYSCALE -> {
                    val gray = row[x].toUnsignedInt()
                    argb(
                        alpha = if (transparentGray == gray) 0 else 255,
                        red = gray,
                        green = gray,
                        blue = gray,
                    )
                }

                PNG_COLOR_RGB -> {
                    val base = x * 3
                    val red = row[base].toUnsignedInt()
                    val green = row[base + 1].toUnsignedInt()
                    val blue = row[base + 2].toUnsignedInt()
                    val alpha = if (
                        transparentRgb != null &&
                        transparentRgb[0] == red &&
                        transparentRgb[1] == green &&
                        transparentRgb[2] == blue
                    ) {
                        0
                    } else {
                        255
                    }
                    argb(alpha, red, green, blue)
                }

                PNG_COLOR_INDEXED -> {
                    val index = row[x].toUnsignedInt()
                    val rgb = palette?.getOrNull(index) ?: return null
                    val alpha = paletteAlpha?.getOrNull(index) ?: 255
                    (rgb and 0x00ffffff) or ((alpha and 0xff) shl 24)
                }

                PNG_COLOR_GRAYSCALE_ALPHA -> {
                    val base = x * 2
                    val gray = row[base].toUnsignedInt()
                    argb(
                        alpha = row[base + 1].toUnsignedInt(),
                        red = gray,
                        green = gray,
                        blue = gray,
                    )
                }

                PNG_COLOR_RGBA -> {
                    val base = x * 4
                    argb(
                        alpha = row[base + 3].toUnsignedInt(),
                        red = row[base].toUnsignedInt(),
                        green = row[base + 1].toUnsignedInt(),
                        blue = row[base + 2].toUnsignedInt(),
                    )
                }

                else -> null
            }

        fun pngChannelCount(colorType: Int): Int? =
            when (colorType) {
                PNG_COLOR_GRAYSCALE -> 1
                PNG_COLOR_RGB -> 3
                PNG_COLOR_INDEXED -> 1
                PNG_COLOR_GRAYSCALE_ALPHA -> 2
                PNG_COLOR_RGBA -> 4
                else -> null
            }

        fun paethPredictor(left: Int, up: Int, upLeft: Int): Int {
            val estimate = left + up - upLeft
            val leftDistance = kotlin.math.abs(estimate - left)
            val upDistance = kotlin.math.abs(estimate - up)
            val upLeftDistance = kotlin.math.abs(estimate - upLeft)
            return when {
                leftDistance <= upDistance && leftDistance <= upLeftDistance -> left
                upDistance <= upLeftDistance -> up
                else -> upLeft
            }
        }

        fun ByteArray.isPngHeader(): Boolean =
            size >= 24 &&
                    (this[0].toInt() and 0xff) == 0x89 &&
                    (this[1].toInt() and 0xff) == 0x50 &&
                    (this[2].toInt() and 0xff) == 0x4e &&
                    (this[3].toInt() and 0xff) == 0x47 &&
                    (this[4].toInt() and 0xff) == 0x0d &&
                    (this[5].toInt() and 0xff) == 0x0a &&
                    (this[6].toInt() and 0xff) == 0x1a &&
                    (this[7].toInt() and 0xff) == 0x0a

        fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                    (bytes[offset + 3].toInt() and 0xff)

        fun readUnsignedShortBigEndian(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or
                    (bytes[offset + 1].toInt() and 0xff)

        fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
            ((alpha and 0xff) shl 24) or
                    ((red and 0xff) shl 16) or
                    ((green and 0xff) shl 8) or
                    (blue and 0xff)

        fun Byte.toUnsignedInt(): Int = toInt() and 0xff

        fun InputStream.readFully(buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val read = read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            return total
        }

        fun InputStream.readUnsignedShort(): Int {
            val high = read()
            val low = read()
            return if (high < 0 || low < 0) {
                -1
            } else {
                (high shl 8) or low
            }
        }

        fun InputStream.skipFully(byteCount: Int): Boolean {
            var remaining = byteCount.toLong()
            while (remaining > 0L) {
                val skipped = skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                } else if (read() < 0) {
                    return false
                } else {
                    remaining--
                }
            }
            return true
        }

        fun ByteArrayOutputStream.writePngChunk(type: String, data: ByteArray) {
            val typeBytes = type.encodeToByteArray()
            writeIntBigEndian(data.size)
            write(typeBytes)
            write(data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            writeIntBigEndian(crc.value.toInt())
        }

        fun ByteArrayOutputStream.writeIntBigEndian(value: Int) {
            write((value ushr 24) and 0xff)
            write((value ushr 16) and 0xff)
            write((value ushr 8) and 0xff)
            write(value and 0xff)
        }
    }
}

private class KoolTargetState(
    val engine: KoolGraphicsEngine,
    val mode: RenderTargetMode,
) {
    val activeAuxiliaryIds = linkedSetOf<KoolCanvasTextureId>()
    var commitEnabled: Boolean = true
    var committing: Boolean = false

    fun releaseAuxiliaryTextures(textureStore: KoolCanvasTextureStore) {
        activeAuxiliaryIds.forEach(textureStore::unregister)
        activeAuxiliaryIds.clear()
    }
}

private class KoolBackendTexture(
    private val releaseAction: (Texture) -> Unit,
) : Texture() {
    private var released = false

    override fun o() {
        if (!released) {
            released = true
            releaseAction(this)
        }
        super.o()
    }

    override fun clone(): Texture =
        KoolBackendTexture(releaseAction).also { texture ->
            copyTextureSettingsTo(texture)
            argbPixelsCopy?.let { pixels ->
                texture.j = pixels
            }
        }

    override fun a(width: Int, height: Int, copyPixels: Boolean): Texture =
        KoolBackendTexture(releaseAction).also { texture ->
            copyTextureSettingsTo(texture)
            texture.p = width
            texture.q = height
            texture.updateCenter()
            if (copyPixels) {
                val pixels = IntArray(width * height)
                val copyWidth = minOf(width, p)
                val copyHeight = minOf(height, q)
                for (x in 0 until copyWidth) {
                    for (y in 0 until copyHeight) {
                        pixels[x + (y * width)] = a(x, y)
                    }
                }
                texture.j = pixels
                texture.m = pixels.any { (it ushr 24) != 0xff }
            }
        }
}
