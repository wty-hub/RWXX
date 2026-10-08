package io.github.rwx.kool.vulkan

import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.map.TileAtlasCache
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import com.corrodinggames.rts.gameFramework.graphics.Texture
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.createContext
import de.fabmax.kool.math.Vec2f
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.BlendMode
import de.fabmax.kool.pipeline.CullMethod
import de.fabmax.kool.pipeline.DepthCompareOp
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.PipelineConfig
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.scene.geometry.IndexedVertexList
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.FrontendScope
import de.fabmax.kool.util.Uint8Buffer
import de.fabmax.kool.util.Viewport
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.MsdfFontData
import de.fabmax.kool.util.MsdfMeta
import de.fabmax.kool.util.MsdfAtlasInfo
import de.fabmax.kool.util.MsdfMetrics
import de.fabmax.kool.util.MsdfGlyph
import de.fabmax.kool.util.MsdfRect
import de.fabmax.kool.pipeline.SingleColorTexture
import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.MipMapping
import de.fabmax.kool.pipeline.SamplerSettings
import io.github.rwx.render.canvas.KoolCanvasFontRegistry
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.render.canvas.KoolCanvasRect
import io.github.rwx.render.canvas.KoolCanvasPoint
import io.github.rwx.render.canvas.KoolCanvasPaint
import io.github.rwx.render.canvas.KoolCanvasColor
import io.github.rwx.render.canvas.KoolCanvasState
import io.github.rwx.render.canvas.KoolCanvasTransform
import io.github.rwx.geometry.Rect
import io.github.rwx.render.canvas.FrameEnvelope
import io.github.rwx.render.canvas.KoolCanvasBlendMode
import io.github.rwx.render.canvas.KoolCanvasCommand
import io.github.rwx.render.canvas.KoolCanvasCommandBuffer
import io.github.rwx.render.canvas.KoolCanvasDrawRole
import com.corrodinggames.rts.game.map.LayerBufferManager
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.ui.GameUI
import io.github.rwx.render.canvas.KoolCanvasCpuTextureStore
import io.github.rwx.render.canvas.KoolCanvasFrameRenderer
import io.github.rwx.render.canvas.KoolCanvasSceneHost
import io.github.rwx.render.canvas.KoolCanvasTextureFilter
import io.github.rwx.render.canvas.KoolCanvasTextureId
import io.github.rwx.render.canvas.KoolCanvasTextureRef
import io.github.rwx.render.canvas.KoolCanvasTextureRegistry
import io.github.rwx.render.canvas.KoolCanvasTextureStore
import io.github.rwx.render.canvas.KoolCanvasTextureShader
import io.github.rwx.render.canvas.KoolGraphicsEngine
import io.github.rwx.render.canvas.KoolPaint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Functional GPU check for `RenderTargetMode.GPU_TARGET` (the map layer-buffer cells).
 *
 * The cell commands are replayed once into a real offscreen pass instead of being rasterised into a
 * CPU `IntArray`. This oracle draws a deliberately asymmetric canvas pattern through the production
 * path - engine commit, frame freeze, host install, offscreen pass - samples the pass attachment back
 * 1:1 and compares every texel against values computed here, not against another rasteriser. A wrong
 * Y orientation, a lost clear, a broken blend or a stale content version therefore cannot pass by
 * agreeing with itself.
 *
 * The readback pass is part of the host scene's pass list rather than a background pass: sampling
 * another pass's attachment needs kool's own pass ordering and image-layout handling.
 *
 * Run it in its own JVM with `./gradlew :desktop:runGpuMapCellOracle`. Readback waits for the queue,
 * so this must never be part of a performance measurement.
 * `RWX_GPU_MAP_CELL_ORACLE_SINGLE=1` stops after the first content version, which bisects a
 * driver-level failure between the first readback and the version-update path.
 */
object KoolCanvasGpuMapCellOracle {
    private val realMap = System.getenv("RWX_REAL_MAP_CELL_ORACLE") == "1"
    private var realLayer: MapLayer? = null
    private var realAtlas: Texture? = null
    private var fogVariant = 0
    private const val WIDTH = 32
    private const val HEIGHT = 24
    private const val TOLERANCE = 3

    /**
     * 4 matches the production desktop default and the working sprite-atlas oracle control.
     * `RWX_GPU_MAP_CELL_ORACLE_MSAA=1` reproduces the single-sample combination, which is what
     * previously crashed this driver.
     */
    private val MSAA_SAMPLES = System.getenv("RWX_GPU_MAP_CELL_ORACLE_MSAA")?.toIntOrNull() ?: 4

    /** A cell content variant: one clear colour and one top-band colour, everything else identical. */
    private data class Content(val clear: Int, val topBand: Int)

    private val FIRST = Content(clear = 0xff101820.toInt(), topBand = 0xffff0000.toInt())
    private val SECOND = Content(clear = 0xff204060.toInt(), topBand = 0xffc86400.toInt())

    private const val BLUE = 0xff0000ff.toInt()
    private const val GREEN = 0xff00ff00.toInt()
    private const val HALF_WHITE = 0x80ffffff.toInt()
    private const val STAMP = 0xff3366cc.toInt()

    /** The top and bottom bands differ, so a vertical flip cannot pass. */
    private val TOP_BAND = 0 until 3
    private val BOTTOM_BAND = (HEIGHT - 3) until HEIGHT
    private val GREEN_RECT = Rect(4, 8, 12, 16)
    private val BLENDED_RECT = Rect(8, 12, 16, 20)
    private val SECOND_BLENDED_RECT = Rect(12, 16, 20, 22)
    private val STAMP_RECT = Rect(20, 4, 24, 8)

    @JvmStatic
    fun main(args: Array<String>) {
        require(System.getenv("RWX_RUN_VULKAN_GPU_MAP_CELL_ORACLE") == "1") {
            "Set RWX_RUN_VULKAN_GPU_MAP_CELL_ORACLE=1 to run the standalone GPU map cell check"
        }
        require(System.getenv("RWX_GPU_MAP_CELL_TARGETS") == "1") {
            "Set RWX_GPU_MAP_CELL_TARGETS=1: the check must exercise the GPU target path"
        }
        runBlocking {
            val context = createContext(
                KoolConfigJvm(
                    windowTitle = "RWX GPU map cell oracle",
                    windowSize = Vec2i(128, 128),
                    renderBackend = RenderBackendVk,
                    useOpenGlFallback = false,
                    showWindowOnStart = false,
                    asyncSceneUpdate = false,
                    numSamples = MSAA_SAMPLES,
                    isVsync = false,
                    maxFrameRate = 60,
                ),
            ) as Lwjgl3Context
            val result = CompletableDeferred<Unit>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            context.onRender += {
                if (!result.isCompleted && System.nanoTime() >= deadline) {
                    result.completeExceptionally(IllegalStateException("GPU map cell check timed out"))
                    context.close()
                }
            }
            FrontendScope.launch {
                try {
                    withTimeout(55_000L) { verify(context) }
                    result.complete(Unit)
                } catch (failure: Throwable) {
                    // Print immediately: the render loop can abort before the deferred is awaited.
                    failure.printStackTrace()
                    result.completeExceptionally(failure)
                } finally {
                    context.close()
                }
            }
            context.run()
            result.await()
        }
    }
    private suspend fun verify(context: Lwjgl3Context) {
        check(context.backend is RenderBackendVk)
        val store = KoolCanvasCpuTextureStore()
        // The replay renderer resolves GPU textures on the render thread, so it must use the default
        // render-thread registry. Only the engine-side recording path uses this store.
        val host = KoolCanvasSceneHost(KoolCanvasFrameRenderer(), "gpu-map-cell-oracle")
        // Retirement through the frame fence, exactly like the production desktop session: a
        // superseded content version must stay valid until the frame that sampled it completed.
        host.setGpuOffscreenPassesAvailable(true)
    host.setGpuRetirementSink { release -> VulkanFrameLifecycle.retainUntilFrameComplete(release) }
        val scene = host.createScene()
        context.scenes += scene

        var cycles = 0L
        context.onRender += { cycles++ }
        suspend fun waitCycles(count: Int) {
            val until = cycles + count
            while (cycles < until) delay(5L)
        }
        suspend fun awaitCondition(label: String, predicate: () -> Boolean) {
            val until = cycles + 120
            while (!predicate() && cycles < until) delay(5L)
            check(predicate()) { label + " did not settle within 120 render cycles" }
        }

        bisectRendererMeshes(context, scene, store)
        waitCycles(2)
        if (System.getenv("RWX_GPU_TEXT_ORACLE") == "1") verifyTextInstances(scene)
        if (System.getenv("RWX_GPU_TEXT_SHADER_ORACLE") == "1") verifyTextInstances(scene, sharedShaders = true)
        if (System.getenv("RWX_GPU_FOG_ORACLE") == "1") verifyFogInstances(scene)
        if (System.getenv("RWX_GPU_SPRITE_APPEND_ORACLE") == "1") verifySpriteAppends(scene)

        val root = KoolGraphicsEngine(textureStore = store)
        val target = root.b(WIDTH, HEIGHT, true)
        val cell = root.b(target, RenderTargetMode.GPU_TARGET)
        val stamp = root.a(4, 4, true).apply { j = IntArray(16) { STAMP }; p() }
        if (realMap) {
            val engine = GameLogic()
            engine.replayEngine = ReplayEngine()
            GameEngine.isNonAndroidVersion = true
            engine.renderGraphicsEngine = root
            val tileConstructor = MapTile::class.java.getDeclaredConstructor().apply { isAccessible = true }
            val map = TileMap().apply {
                tileCountX = WIDTH / 4; tileCountY = HEIGHT / 4
                tileWorldSizeX = 4; tileWorldSizeY = 4
                tileScaleX = .25f; tileScaleY = .25f
                fogEnabled = false
                uniqueTiles = arrayOf(tileConstructor.newInstance().apply { atlasSlotIndex = 0 },
                    tileConstructor.newInstance().apply { atlasSlotIndex = 1 })
            }
            engine.tileMap = map
            engine.playerTeam = GameTeam(0, false).apply {
                fogOfWarData = Array(map.tileCountX) { ByteArray(map.tileCountY) }
            }
            val atlas = root.a(44, 22, true).apply {
                j = IntArray(44 * 22) { if (it % 44 < 22) FIRST.topBand else BLUE }; p()
            }
            realAtlas = atlas
            val cache = TileAtlasCache(1f, false, root).apply { atlasTexture = atlas }
            TileMap.fogTileAtlasCacheFullScale = cache
            TileMap.fogTileAtlasCacheHalfScale = cache
            realLayer = MapLayer(map, "ground", map.tileCountX, map.tileCountY).apply {
                for (x in 0 until widthTiles) for (y in 0 until heightTiles)
                    tileIds[x * heightTiles + y] = ((x + y) % 2).toShort()
            }
            map.groundLayer = realLayer
        }

        var checks = 0
        var sequence = 0L
        val envelopes = mutableListOf<FrameEnvelope>()

        try {
            drawContent(cell, stamp, FIRST)
            cell.p()
            val first = present(root, store, host, target, ++sequence)
            envelopes += first.second
            // Kool needs more than one frame before a newly created mesh is live; sample after the
            // target has finished its render frames plus a margin.
            waitCycles(8)
            if (System.getenv("RWX_GPU_MAP_CELL_ORACLE_NO_READBACK") == "1") {
                // Bisects a driver-level failure: the production target pass renders without any
                // readback pass existing at all.
                waitCycles(90)
                println("RWXGpuMapCellOracle target-pass-only survived cycles=" + cycles)
                return
            }
            checks += compare(scene, host, first.first, FIRST, "first-version")

            if (System.getenv("RWX_GPU_MAP_CELL_ORACLE_SINGLE") != "1") {
                // A second content version must render its own image, and the first one must retire
                // only after the fence while never turning into a replay frame.
                drawContent(cell, stamp, SECOND)
                cell.p()
                val second = present(root, store, host, target, ++sequence)
                envelopes += second.second
                waitCycles(3)
                check(second.first != first.first) { "changed cell content must publish a new content version" }
                checks += compare(scene, host, second.first, SECOND, "second-version")
                awaitCondition("retired version registration") {
                    !KoolCanvasTextureRegistry.isGpuTargetTexture(first.first)
                }
                checks++
                if (realMap) {
                    // Normal terrain, partial fog, full fog, an observer change and fog disabled.
                    // Smooth-edge coverage is a separate fixture; these exact flat regions isolate
                    // the independent rectangle blend and cache invalidation path.
                    for (variant in 1..3) {
                        fogVariant = variant
                        drawContent(cell, stamp, SECOND)
                        cell.p()
                        val fogFrame = present(root, store, host, target, ++sequence)
                        envelopes += fogFrame.second
                        waitCycles(3)
                        checks += compare(scene, host, fogFrame.first, SECOND, "fog-variant-" + variant)
                    }
                    if (System.getenv("RWX_GPU_MAP_GRID_ORACLE") == "1") verifyGrid(root, store, host, scene)
                }
            }

            println(
                "RWXGpuMapCellOracle success checks=" + checks + " width=" + WIDTH + " height=" + HEIGHT +
                    " tolerance=" + TOLERANCE + " retirement=fenced",
            )
        } finally {
            envelopes.forEach { it.close() }
            cell.q()
            target.o()
            stamp.o()
            realAtlas?.o()
            // No explicit scene release: the process exits right after, and releasing the scene while
            // the render loop still collects it aborts the backend thread with "already released".
            store.close()
        }
    }

    private suspend fun verifyGrid(root: KoolGraphicsEngine, store: KoolCanvasCpuTextureStore,
        host: KoolCanvasSceneHost, scene: Scene) {
        val engine = GameEngine.getInstance()
        val previousManager = TileMap.layerBufferManager
        val previousDesktop = GameEngine.isJavaDesktopVersion
        val width = 640; val height = 480
        val map = realLayer!!.tileMap
        val layer = MapLayer(map, "ground", 128, 96).apply {
            for (x in 0 until widthTiles) for (y in 0 until heightTiles)
                tileIds[x * heightTiles + y] = ((x / 3 + y / 2) % 2).toShort()
        }
        map.tileCountX = 128; map.tileCountY = 96
        map.tileWorldSizeX = 8; map.tileWorldSizeY = 8
        map.tileScaleX = .125f; map.tileScaleY = .125f
        map.groundLayer = layer; map.mapLayers.clear(); map.mapLayers.add(layer)
        engine.playerTeam.fogOfWarData = Array(128) { ByteArray(96) }
        map.fogOfWarCurrent = null; map.fogOfWarNext = null
        map.ensureFogCacheAllocated()
        map.fogOfWarCurrent.forEach { it.fill(0) }; map.fogOfWarNext.forEach { it.fill(0) }
        engine.settingsEngine = SettingsEngine.getInstance()
        engine.gameUI = GameUI()
        GameEngine.isJavaDesktopVersion = true
        val managers = listOf(LayerBufferManager(), LayerBufferManager())
        val targets = List(2) { root.b(width, height, true) }
        val surfaces = targets.map { root.b(it, RenderTargetMode.GPU_TARGET) as KoolGraphicsEngine }
        val enabled = LayerBufferManager::class.java.getDeclaredField("zoomGenerationEnabled").apply { isAccessible = true }
        val grid = LayerBufferManager::class.java.getDeclaredField("gridCells").apply { isAccessible = true }
        val budget = LayerBufferManager::class.java.getDeclaredField("zoomRedrawBudgetNanos").apply { isAccessible = true }
        fun visibleDirty(manager: LayerBufferManager): Boolean {
            val columns = grid.get(manager) as Array<*>
            return columns.any { column -> (column as Array<*>).any { value ->
                val cell = value as com.corrodinggames.rts.game.map.LayerBufferCell
                cell.screenDstRect.a().not() && cell.needsRedraw
            } }
        }
        data class Pose(val x: Float, val y: Float, val zoom: Float, val fog: Boolean = false, val updateAtlas: Boolean = false)
        val poses = listOf(Pose(360f, 320f, 1f), Pose(320f, 200f, 1f), Pose(320f, 200f, .94f),
            Pose(320f, 200f, .82f), Pose(320f, 200f, .93f), Pose(990f, 754f, 1f),
            Pose(0f, 0f, .9f), Pose(500f, 500f, .9f, fog = true),
            Pose(500f, 500f, .9f, updateAtlas = true))
        val envelopes = mutableListOf<FrameEnvelope>()
        var sequence = 100L
        var checks = 0
        var previousGenerationSamples = 0
        try {
            poses.forEachIndexed { index, pose ->
                engine.currentScreenWidthPixels = width.toFloat(); engine.currentScreenHeightPixels = height.toFloat()
                engine.visibleWorldWidth = width / pose.zoom; engine.viewpointWidth = width / pose.zoom
                engine.visibleWorldHeight = height / pose.zoom
                engine.zoom = pose.zoom; engine.targetZoom = pose.zoom
                engine.viewpointX = pose.x; engine.viewpointY = pose.y
                engine.viewpointXSnapped = pose.x; engine.viewpointYSnapped = pose.y
                engine.viewpointXInt = pose.x.toInt(); engine.viewpointYInt = pose.y.toInt()
                engine.screenClipRect.a(0, 0, width, height)
                map.fogEnabled = pose.fog
                if (pose.fog) for (x in 0 until 128) for (y in 0 until 96)
                    engine.playerTeam.fogOfWarData[x][y] = (if ((x + y) % 3 == 0) 10 else 5).toByte()
                if (pose.updateAtlas) realAtlas!!.apply {
                    j = IntArray(44 * 22) { if (it % 44 < 22) 0xff00cc88.toInt() else 0xff8800cc.toInt() }; p()
                }
                for (arm in 0..1) {
                    TileMap.layerBufferManager = managers[arm]
                    engine.renderGraphicsEngine = surfaces[arm]
                    if (index == 0) {
                        managers[arm].bindGraphicsBackend(surfaces[arm])
                        enabled.setBoolean(managers[arm], arm == 1)
                        managers[arm].update()
                    }
                    if (pose.fog || pose.updateAtlas) managers[arm].invalidateFogDisplay()
                    if (arm == 0) managers[arm].updateGridParams()
                    fun draw() {
                        surfaces[arm].beginFrame(width, height)
                        surfaces[arm].b(0xff102030.toInt())
                        managers[arm].setRenderScale(1f)
                        surfaces[arm].p()
                    }
                    // Force an exhausted recording budget for the first zoom frame only. The small
                    // oracle map can otherwise finish all cells in <2 ms and never sample old targets.
                    if (arm == 1 && index >= 2) budget.setLong(managers[arm], 0L)
                    draw()
                    budget.setLong(managers[arm], 2_000_000L)
                    if (arm == 1 && !pose.fog && !pose.updateAtlas) {
                        val previousField = LayerBufferManager::class.java.getDeclaredField("previousZoomGeneration")
                            .apply { isAccessible = true }
                        val generation = previousField.get(managers[arm])
                        val active = generation?.javaClass?.getDeclaredField("active")?.apply { isAccessible = true }
                        println("RWXGpuMapGridOracle candidate pose=$index generation=${generation != null} active=${generation != null && active!!.getBoolean(generation)} dirty=${visibleDirty(managers[arm])}")
                        if (generation != null) println("RWXGpuMapGridOracle metadata " +
                            generation.javaClass.declaredFields.filter { it.name in listOf("originX", "originY", "step", "pixels", "scale", "valid") }
                                .joinToString { field -> field.isAccessible = true; val value = field.get(generation)
                                    field.name + "=" + (if (value is Array<*>) value.joinToString { (it as BooleanArray).joinToString("") { if (it) "1" else "0" } } else value) })
                        if (generation != null && active!!.getBoolean(generation)) {
                            val published = present(root, store, host, targets[arm], ++sequence)
                            envelopes += published.second
                            delay(200L)
                            val pixels = downloadFull(scene, KoolCanvasTextureRegistry.resolve(
                                KoolCanvasTextureRef(published.first, width, height), KoolCanvasTextureFilter.Nearest), width, height)
                            val holes = pixels.indices.count { pixel ->
                                val worldX = pose.x + (pixel % width + .5f) / pose.zoom
                                val worldY = pose.y + (pixel / width + .5f) / pose.zoom
                                worldX in 2f..1022f && worldY in 2f..766f &&
                                    (pixels[pixel] == 0xff102030.toInt() || pixels[pixel] == 0xff000000.toInt())
                            }
                            check(holes == 0) { "old generation has map holes pose=$index pixels=$holes" }
                            previousGenerationSamples++
                            checks += pixels.size
                        }
                    }
                    delay(65L)
                    // Observe an unchanged ordinary zoom request for 50 ms, then drain only visible
                    // pending cells through the actual cache composition path.
                    val deadline = System.nanoTime() + 250_000_000L
                    draw()
                    while (visibleDirty(managers[arm]) && System.nanoTime() < deadline) {
                        delay(5L); draw()
                    }
                    check(!visibleDirty(managers[arm])) { "visible map grid missed 250 ms deadline pose=$index arm=$arm" }
                }
                // Integer raster origins affect fog boundary rounding. Use the candidate's *current*
                // world grid for the independent full redraw reference; never copy its cached images.
                // This compares sampling and final composition without conflating different raster phases.
                TileMap.layerBufferManager = managers[0]
                engine.renderGraphicsEngine = surfaces[0]
                for (name in listOf("gridOriginWorldX", "gridOriginWorldY")) {
                    val field = LayerBufferManager::class.java.getDeclaredField(name).apply { isAccessible = true }
                    field.setInt(managers[0], field.getInt(managers[1]))
                }
                managers[0].invalidateAllCells()
                surfaces[0].beginFrame(width, height); surfaces[0].b(0xff102030.toInt())
                managers[0].setRenderScale(1f); surfaces[0].p()
                val pictures = targets.map { target ->
                    val published = present(root, store, host, target, ++sequence)
                    envelopes += published.second
                    delay(200L)
                    check(KoolCanvasTextureRegistry.isGpuTargetTexture(published.first))
                    val attachment = KoolCanvasTextureRegistry.resolve(
                        KoolCanvasTextureRef(published.first, width, height), KoolCanvasTextureFilter.Nearest)
                    downloadFull(scene, attachment, width, height)
                }
                val mismatches = pictures[0].indices.count { !channelsClose(pictures[0][it], pictures[1][it]) }
                if (mismatches != 0) println("RWXGpuGridMismatch pose=$index " + pictures[0].indices.filter {
                    !channelsClose(pictures[0][it], pictures[1][it]) }.take(12).joinToString {
                    "(${"%d,%d".format(it % width, it / width)})=" + Integer.toHexString(pictures[0][it]) + "/" + Integer.toHexString(pictures[1][it]) })
                check(mismatches == 0) { "map grid pose=$index mismatches=$mismatches/${pictures[0].size}" }
                check(pictures[0].any { it != 0xff102030.toInt() }) { "grid reference produced no map" }
                checks += pictures[0].size
            }
            check(previousGenerationSamples > 0) { "grid fixture did not exercise a live previous generation" }
            println("RWXGpuMapGridOracle success checks=$checks poses=${poses.size} previousGenerationSamples=$previousGenerationSamples stableNanos=50000000 visibleDeadlineNanos=250000000")
        } finally {
            envelopes.forEach { it.close() }
            managers.forEach { it.releaseLayerBuffers() }
            surfaces.forEach { it.q() }; targets.forEach { it.o() }
            TileMap.layerBufferManager = previousManager
            GameEngine.isJavaDesktopVersion = previousDesktop
            engine.renderGraphicsEngine = root
        }
    }

    private suspend fun verifySpriteAppends(scene: Scene) {
        val prefix = "sprite-append-oracle-"
        val stores = listOf(false, true).map { atlas -> object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
            override fun staticArgbImage(id: KoolCanvasTextureId) =
                if (atlas && id.value.startsWith(prefix)) KoolCanvasTextureRegistry.argbImageView(id) else null
        } }
        val nodes = listOf(Node("sprite-control"), Node("sprite-append"))
        val cameras = nodes.map { OrthographicCamera(it.name) }
        val renderers = stores.map { KoolCanvasFrameRenderer(textureStore = it) }
        val passes = nodes.mapIndexed { index, node -> OffscreenPass2d(node,
            AttachmentConfig.singleColorNoDepth(TexFormat.RGBA), Vec2i(WIDTH, HEIGHT), "gpu-sprite-$index").apply {
                camera = cameras[index]; isMirrorY = false; viewport = Viewport(0, 0, WIDTH, HEIGHT)
            }.also { scene.addOffscreenPass(it) } }
        val before = VulkanUploads.atlasRegionBytes
        try {
            var checks = 0
            val sprites = mutableListOf<KoolCanvasTextureRef>()
            for (version in 0 until 12) {
                val size = 3 + version % 3
                val sprite = KoolCanvasTextureRef(KoolCanvasTextureId(prefix + version), size, size)
                KoolCanvasTextureRegistry.registerArgb(sprite.id, size, size, IntArray(size * size) {
                    ((96 + (it * 19 + version * 13) % 160) shl 24) or
                        (((it * 43 + version * 31) % 256) shl 16) or
                        (((it * 17 + version * 59) % 256) shl 8) or ((it * 73 + version * 11) % 256)
                }, alphaBleed = false)
                sprites += sprite
                val buffer = KoolCanvasCommandBuffer(KoolCanvasViewport(WIDTH, HEIGHT))
                buffer.clear(KoolCanvasColor(0xff203040.toInt()))
                sprites.forEachIndexed { index, ref ->
                    val x = (index % 4) * 8f; val y = (index / 4) * 8f
                    for (filter in KoolCanvasTextureFilter.entries) buffer.drawTexture(ref,
                        KoolCanvasRect(x + .3f, y + .2f, x + 7.3f, y + 7.2f),
                        KoolCanvasPaint.Default.copy(textureFilter = filter, alphaMultiplier = .7f),
                        KoolCanvasRect(0f, 0f, ref.width.toFloat(), ref.height.toFloat()))
                }
                val direct = buffer.snapshot()
                val frame = if (version % 2 == 1) {
                    val id = KoolCanvasTextureId("sprite-nested-$version")
                    KoolCanvasTextureRegistry.registerFrame(id, direct)
                    KoolCanvasFrame(direct.viewport, listOf(direct.commands.first(),
                        KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, WIDTH, HEIGHT),
                            KoolCanvasRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                            KoolCanvasRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                            KoolCanvasPaint.Default.copy(alphaMultiplier = .8f),
                            KoolCanvasState(clip = KoolCanvasRect(1f, 1f, 30f, 23f),
                                transform = KoolCanvasTransform.Identity.scale(.95f, .9f).rotate(2f)))))
                } else direct
                renderers.forEachIndexed { index, renderer -> renderer.renderInto(nodes[index], cameras[index], frame) {
                    passes[index].colorAttachments.single().clearColor = it
                } }
                check(nodes[1].children.filterIsInstance<Mesh<*>>().any {
                    it.isVisible && it.name.startsWith("rwx-canvas-sprites-") }) {
                    "sprite version=$version bypassed the atlas, including projected frames"
                }
                delay(100L)
                val reference = downloadFull(scene, checkNotNull(passes[0].colorTexture))
                val actual = downloadFull(scene, checkNotNull(passes[1].colorTexture))
                val bad = reference.indices.filter { !channelsClose(actual[it], reference[it]) }
                check(reference.count { it != 0xff203040.toInt() } > 20)
                check(bad.isEmpty()) { "sprite version=$version mismatches=${bad.size}: " + bad.take(8).joinToString {
                    "${it % WIDTH},${it / WIDTH}=${Integer.toHexString(reference[it])}/${Integer.toHexString(actual[it])}" } }
                checks += reference.size
            }
            val bytes = VulkanUploads.atlasRegionBytes - before
            check(bytes > 0) { "The append oracle only exercised full-image fallback" }
            check(bytes < 100_000) { "Small sprites uploaded unexpectedly large regions: $bytes" }
            println("RWXGpuSpriteAppendOracle success checks=$checks versions=12 appendedBytes=$bytes")
        } finally {
            passes.forEach { it.isEnabled = false; scene.removeOffscreenPass(it) }
        }
    }

    private suspend fun verifyFogInstances(scene: Scene) {
        val ref = KoolCanvasTextureRef(KoolCanvasTextureId("fog-mixed-oracle"), 8, 8)
        val nodes = listOf(Node("fog-control"), Node("fog-batched"))
        val cameras = listOf(OrthographicCamera("fog-control"), OrthographicCamera("fog-batched"))
        val renderers = listOf(KoolCanvasFrameRenderer(reuseInstancedTextureShaders = false), KoolCanvasFrameRenderer())
        val passes = nodes.mapIndexed { index, node -> OffscreenPass2d(node,
            AttachmentConfig.singleColorNoDepth(TexFormat.RGBA), Vec2i(WIDTH, HEIGHT), "gpu-fog-$index").apply {
                camera = cameras[index]; isMirrorY = false; viewport = Viewport(0, 0, WIDTH, HEIGHT)
            }.also { scene.addOffscreenPass(it) } }
        try {
            var checks = 0
            for (version in 0..5) {
                // Nonuniform alpha and deliberately unrelated RGB: black fog only samples alpha.
                KoolCanvasTextureRegistry.registerArgb(ref.id, 8, 8,
                    IntArray(64) { (((it * 17 + version * 39) % 256) shl 24) or 0x39a7e5 }, alphaBleed = false)
                val frames = listOf(false, true).map { batching ->
                    val buffer = KoolCanvasCommandBuffer(KoolCanvasViewport(WIDTH, HEIGHT), fogBatches = batching)
                    buffer.clear(KoolCanvasColor(0xff6080a0.toInt()))
                    buffer.setDrawRole(KoolCanvasDrawRole.MapFog)
                    val black = KoolCanvasPaint.Default.copy(color = KoolCanvasColor(0x7d000000))
                    if (version == 2) buffer.clip(KoolCanvasRect(5f, 3f, 21f, 19f))
                    repeat(12) { i ->
                        val destination = KoolCanvasRect(i * 1.5f, i % 5 + 1f, i * 1.5f + 11f, i % 5 + 15f)
                        buffer.drawRect(destination, black.copy(alphaMultiplier = .5f + version * .08f))
                        if (version != 3) buffer.drawTexture(ref, destination,
                            black.copy(textureFilter = if (version == 1) KoolCanvasTextureFilter.Nearest else KoolCanvasTextureFilter.Linear),
                            KoolCanvasRect(1f, 2f, 7f, 8f))
                    }
                    buffer.setDrawRole(KoolCanvasDrawRole.Generic)
                    buffer.drawRect(KoolCanvasRect(12f, 6f, 16f, 17f),
                        black.copy(color = KoolCanvasColor(0x80ff0000.toInt())))
                    val recorded = buffer.snapshot()
                    if (batching) check(recorded.commands.any { it is KoolCanvasCommand.DrawFogBatch })
                    // Projected target fallback, with both rotation and outer opacity.
                    if (version < 4) recorded else {
                        val id = KoolCanvasTextureId("fog-projected-$batching")
                        KoolCanvasTextureRegistry.registerFrame(id, recorded)
                        KoolCanvasFrame(recorded.viewport, listOf(KoolCanvasCommand.Clear(KoolCanvasColor(0xff6080a0.toInt())),
                            KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, WIDTH, HEIGHT),
                                KoolCanvasRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                                KoolCanvasRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                                KoolCanvasPaint.Default.copy(alphaMultiplier = .7f),
                                KoolCanvasState(transform = KoolCanvasTransform.Identity.scale(.9f, .8f).rotate(3f)))))
                    }
                }
                renderers.forEachIndexed { index, renderer -> renderer.renderInto(nodes[index], cameras[index], frames[index]) {
                    passes[index].colorAttachments.single().clearColor = it
                } }
                delay(250L)
                val reference = downloadFull(scene, checkNotNull(passes[0].colorTexture))
                val actual = downloadFull(scene, checkNotNull(passes[1].colorTexture))
                check(reference.count { it != 0xff6080a0.toInt() } > 30) { "fog reference rendered no masks" }
                val mismatches = reference.indices.count { !channelsClose(actual[it], reference[it]) }
                if (mismatches != 0) println("RWXGpuFogMismatch " + reference.indices.filter {
                    !channelsClose(actual[it], reference[it]) }.take(12).joinToString { index ->
                    "(${"%d,%d".format(index % WIDTH, index / WIDTH)})=" +
                        Integer.toHexString(reference[index]) + "/" + Integer.toHexString(actual[index]) })
                check(mismatches == 0) { "fog version=$version mismatches=$mismatches/${reference.size}" }
                checks += reference.size
            }
            println("RWXGpuFogOracle success checks=$checks versions=6 tolerance=$TOLERANCE")
        } finally {
            passes.forEach { it.isEnabled = false; scene.removeOffscreenPass(it) }
        }
    }

    private suspend fun verifyTextInstances(scene: Scene, sharedShaders: Boolean = false,
        labels: Boolean = System.getenv("RWX_GPU_TEXT_LABEL_ORACLE") == "1") {
        val previous = KoolCanvasFontRegistry.base
        val pixels = Uint8Buffer(16 * 8 * 4)
        repeat(16 * 8) { pixel -> repeat(4) { channel ->
            pixels[pixel * 4 + channel] = (64 + (pixel * 29 + channel * 11) % 192).toUByte() } }
        val atlas = Texture2d(BufferedImageData2d(pixels, 16, 8, TexFormat.RGBA, "text-oracle-glyphs"),
            mipMapping = MipMapping.Off, samplerSettings = SamplerSettings().clamped().linear().noAnisotropy())
        val data = MsdfFontData(atlas, MsdfMeta(MsdfAtlasInfo("msdf", 4f, 8f, 16, 8, "bottom"),
            "gpu-text-oracle", MsdfMetrics(1f, 1.2f, 1f, 0f, 0f, .05f),
            "AB0123456789".mapIndexed { index, character ->
                val x = (index % 4) * 4f; val y = (index / 4) * 2f
                MsdfGlyph(character.code, .6f, MsdfRect(0f, 0f, .5f, 1f), MsdfRect(x, y, x + 4, y + 2)) }))
        val nodes = listOf(Node("text-control"), Node("text-instanced"))
        val cameras = listOf(OrthographicCamera("text-control"), OrthographicCamera("text-instanced"))
        val renderers = listOf(KoolCanvasFrameRenderer(instanceTextGlyphs = false, reuseTextShaders = false, instanceTextLabels = false, prepareText = false, reuseTextMeshKeys = false),
            KoolCanvasFrameRenderer(instanceTextGlyphs = !sharedShaders && !labels, reuseTextShaders = sharedShaders,
                instanceTextLabels = labels))
        if (labels) {
            // A deliberately small atlas forces offset compaction and cache fallback across live
            // GPU generations, rather than only exercising the first immutable upload.
            val cacheClass = Class.forName("io.github.rwx.render.canvas.KoolCanvasLabelGeometry")
            val field = KoolCanvasFrameRenderer::class.java.getDeclaredField("labelGeometry").apply { isAccessible = true }
            field.set(renderers[1], cacheClass.getConstructor(Int::class.javaPrimitiveType).newInstance(128))
        }
        val passes = nodes.mapIndexed { index, node -> OffscreenPass2d(node,
            AttachmentConfig.singleColorNoDepth(TexFormat.RGBA), Vec2i(WIDTH, HEIGHT), "gpu-text-$index").apply {
                camera = cameras[index]; isMirrorY = false; viewport = Viewport(0, 0, WIDTH, HEIGHT)
            }.also { scene.addOffscreenPass(it) } }
        try {
            var checks = 0
            val versions = if (labels) 12 else 4
            for (version in 0 until versions) {
                KoolCanvasFontRegistry.installBaseFont(MsdfFont(data, italic = if (version == 1) .3f else 0f))
                val commands = buildList {
                    add(KoolCanvasCommand.Clear(KoolCanvasColor(0xff203040.toInt())))
                    // Change the first label's draw group while reusing its projected material.
                    // Static instance geometry must not make it retain an earlier frame's order.
                    if (version % 4 == 3) add(KoolCanvasCommand.DrawRect(
                        KoolCanvasRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                        KoolCanvasPaint.Default.copy(color = KoolCanvasColor(0xff203040.toInt())), KoolCanvasState.Default))
                    addAll(listOf(
                    KoolCanvasCommand.DrawText(if (version % 2 == 0) "AB1${version % 10}" else "12AB${version % 10}", KoolCanvasPoint(3.4f + version % 4, 13.7f),
                        KoolCanvasPaint.Default.copy(textSize = 8f, color = KoolCanvasColor(0x80ffffff.toInt())),
                        KoolCanvasState(clip = KoolCanvasRect(4f, 2f, 22f, 18f))),
                    KoolCanvasCommand.DrawRect(KoolCanvasRect(12f, 6f, 16f, 17f),
                        KoolCanvasPaint.Default.copy(color = KoolCanvasColor(0x80ff0000.toInt())), KoolCanvasState.Default),
                    KoolCanvasCommand.DrawText("9BA", KoolCanvasPoint(5f, 21f),
                        KoolCanvasPaint.Default.copy(textSize = 9f, color = KoolCanvasColor(0xff00ff00.toInt())),
                        KoolCanvasState(transform = KoolCanvasTransform.Identity.scale(.8f, .9f).rotate(4f))),
                    ))
                }
                val direct = KoolCanvasFrame(KoolCanvasViewport(WIDTH, HEIGHT), commands)
                val frame = if (labels && version >= 4 && version % 2 == 1) {
                    val id = KoolCanvasTextureId("text-label-projected")
                    KoolCanvasTextureRegistry.registerFrame(id, direct)
                    KoolCanvasFrame(direct.viewport, listOf(commands.first(),
                        KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, WIDTH, HEIGHT),
                            KoolCanvasRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                            KoolCanvasRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                            KoolCanvasPaint.Default.copy(alphaMultiplier = .75f),
                            KoolCanvasState(transform = KoolCanvasTransform.Identity.scale(.95f, .9f).rotate(3f)))))
                } else direct
                renderers.forEachIndexed { index, renderer -> renderer.renderInto(nodes[index], cameras[index], frame) {
                    passes[index].colorAttachments.single().clearColor = it
                } }
                delay(250L)
                val reference = downloadFull(scene, checkNotNull(passes[0].colorTexture))
                val actual = downloadFull(scene, checkNotNull(passes[1].colorTexture))
                val mismatches = reference.indices.count { !channelsClose(actual[it], reference[it]) }
                check(reference.count { it != 0xff203040.toInt() } > 30) { "text reference rendered no glyphs" }
                check(mismatches == 0) { "text version=$version mismatches=$mismatches/${reference.size}" }
                checks += reference.size
            }
            println("RWXGpuTextOracle success checks=$checks versions=$versions tolerance=$TOLERANCE sharedShaders=$sharedShaders labels=$labels")
        } finally {
            KoolCanvasFontRegistry.installBaseFont(previous)
            passes.forEach { it.isEnabled = false; scene.removeOffscreenPass(it) }
            val release = KoolCanvasFrameRenderer::class.java.declaredMethods.single {
                it.name.startsWith("releaseCachedMeshes") && it.parameterTypes.contentEquals(arrayOf(Node::class.java))
            }.apply { isAccessible = true }
            renderers.forEachIndexed { index, renderer -> release.invoke(renderer, nodes[index]) }
        }
    }
    /**
     * Draws the pattern into the cell. Every region has an unambiguous expected colour: an opaque
     * clear, two opposite colour bands, an opaque rect, a 50 percent SourceOver rect on top of it, and
     * a sampled 4x4 texture.
     */
    private fun drawContent(cell: GraphicsEngine, stamp: Texture, content: Content) {
        if (realMap) {
            realAtlas!!.apply {
                j = IntArray(44 * 22) { if (it % 44 < 22) content.topBand else BLUE }; p()
            }
            cell.b(content.clear)
            val map = realLayer!!.tileMap
            map.fogEnabled = fogVariant in 1..2
            val fog = GameEngine.getInstance().playerTeam.fogOfWarData
            for (x in 0 until map.tileCountX) for (y in 0 until map.tileCountY) {
                fog[x][y] = fogValue(x, y).toByte()
            }
            map.ensureFogCacheAllocated()
            map.fogOfWarCurrent.forEach { it.fill(0) }
            map.fogOfWarNext.forEach { it.fill(0) }
            realLayer!!.renderLayerRegion(cell, 0f, 0f, 0f, 0f,
                WIDTH.toFloat(), HEIGHT.toFloat(), 1f, 1f, true, false, false)
            realLayer!!.renderLayerRegion(cell, 0f, 0f, 0f, 0f,
                WIDTH.toFloat(), HEIGHT.toFloat(), 1f, 1f, true, true, true)
            // Overlay after terrain: verifies the packed path respects the primitive ordering barrier.
            cell.k()
            cell.a(Rect(0, 0, 18, HEIGHT))
            cell.a(BLENDED_RECT, paint(HALF_WHITE, KoolCanvasBlendMode.SourceOver))
            cell.a(SECOND_BLENDED_RECT, paint(HALF_WHITE, KoolCanvasBlendMode.SourceOver))
            cell.l()
            return
        }
        cell.b(content.clear)
        cell.a(Rect(0, TOP_BAND.first, WIDTH, TOP_BAND.last + 1), paint(content.topBand, KoolCanvasBlendMode.Source))
        cell.a(Rect(0, BOTTOM_BAND.first, WIDTH, BOTTOM_BAND.last + 1), paint(BLUE, KoolCanvasBlendMode.Source))
        cell.a(GREEN_RECT, paint(GREEN, KoolCanvasBlendMode.Source))
        cell.a(BLENDED_RECT, paint(HALF_WHITE, KoolCanvasBlendMode.SourceOver))
        // An explicit source rect: a null source rect records no DrawTexture, which silently skipped
        // the texture-sampling case entirely.
        // Independent 1-pixel quads exercise compact batching without changing the analytic image.
        // Clip an expanded final column so the batch path must adjust UVs and coverage correctly.
        cell.k()
        cell.a(STAMP_RECT)
        for (y in STAMP_RECT.b until STAMP_RECT.d) for (x in STAMP_RECT.a until STAMP_RECT.c) {
            cell.b(stamp, Rect(0, 0, 4, 4), Rect(x, y, x + 1, y + 1), null)
        }
        cell.b(stamp, Rect(0, 0, 4, 4), Rect(STAMP_RECT.c - 1, STAMP_RECT.b, STAMP_RECT.c + 2, STAMP_RECT.d), null)
        cell.l()
    }

    /**
     * Draws the target into a fresh parent frame, freezes it and hands it to the render thread.
     *
     * The check that the version is not a published replay frame is the load-bearing one: a published
     * frame would send the renderer down the per-sampling-point command expansion instead of binding
     * the pass attachment, which is the quadratic path this target type exists to avoid.
     */
    private fun present(
        root: KoolGraphicsEngine,
        store: KoolCanvasCpuTextureStore,
        host: KoolCanvasSceneHost,
        target: Texture,
        sequence: Long,
    ): Pair<KoolCanvasTextureId, FrameEnvelope> {
        root.beginFrame(WIDTH, HEIGHT)
        root.b(target, 0f, 0f, null)
        // The version id only exists after the freeze rewrites texture references; sampling the
        // pre-freeze snapshot would give the logical target id instead.
        val envelope = store.freezeFrame(root.snapshot(), sequence, 1L, sequence.toInt(), 1L)
        val versionId = envelope.frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().single().texture.id
        check(store.frame(versionId) == null) {
            "GPU target version " + versionId.value + " was published as a replay frame"
        }
        host.submit(envelope)
        return versionId to envelope
    }

    /**
     * Bisection: renders an ordinary canvas frame through the plain replay renderer into a private
     * offscreen pass and reads it back.
     *
     * This separates "the replay renderer's meshes do not rasterise" from "the production GPU target
     * slot/registry/install plumbing is at fault": the pass here is created, enabled and read back by
     * the oracle itself, with no version registry involved.
     */
    private suspend fun bisectRendererMeshes(
        context: Lwjgl3Context,
        scene: Scene,
        store: KoolCanvasCpuTextureStore,
    ) {
        val frameEngine = KoolGraphicsEngine(textureStore = store)
        frameEngine.beginFrame(WIDTH, HEIGHT)
        frameEngine.a(Rect(0, 0, WIDTH, HEIGHT), paint(GREEN, KoolCanvasBlendMode.Source))
        val frame = frameEngine.snapshot()

        val node = Node("rwx-bisect-node")
        val camera = OrthographicCamera("rwx-bisect-camera")
        val renderer = KoolCanvasFrameRenderer()
        var clearColour: de.fabmax.kool.pipeline.ClearColor =
            de.fabmax.kool.pipeline.ClearColorFill(Color.BLACK)
        renderer.renderInto(node, camera, frame) { clearColour = it }

        val pass = OffscreenPass2d(
            node,
            AttachmentConfig.singleColorNoDepth(TexFormat.RGBA),
            Vec2i(WIDTH, HEIGHT),
            "rwx-bisect-pass",
        ).apply {
            this.camera = camera
            viewport = Viewport(0, 0, WIDTH, HEIGHT)
            colorAttachments.single().clearColor = clearColour
            isMirrorY = false
        }
        val copy = pass.copyOutput(isCopyColor = true, isCopyDepth = false)
        scene.addOffscreenPass(pass)
        try {
            val output = checkNotNull(pass.colorTexture)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (output.gpuTexture == null) {
                check(System.nanoTime() < deadline) { "bisect attachment never reached the GPU" }
                delay(5L)
            }
            delay(200L)
            val bytes = output.download().data as Uint8Buffer
            val pixels = IntArray(WIDTH * HEIGHT) { index ->
                val offset = index * 4
                ((bytes[offset + 3].toInt() and 255) shl 24) or
                    ((bytes[offset].toInt() and 255) shl 16) or
                    ((bytes[offset + 1].toInt() and 255) shl 8) or
                    (bytes[offset + 2].toInt() and 255)
            }
            println(
                "RWXGpuMapCellOracle bisect rendererMesh commands=" + frame.commands.size +
                    " nodeChildren=" + node.children.size +
                    " top=" + topColours(pixels),
            )
        } finally {
            scene.removeOffscreenPass(pass)
            pass.release()
            copy.release()
        }
    }

    /** Samples a whole target version 1:1 through a readback pass and checks every texel. */
    private suspend fun compare(
        scene: Scene,
        host: KoolCanvasSceneHost,
        versionId: KoolCanvasTextureId,
        content: Content,
        label: String,
    ): Int {
        val texture = KoolCanvasTextureRegistry.resolve(
            KoolCanvasTextureRef(versionId, WIDTH, HEIGHT),
            KoolCanvasTextureFilter.Nearest,
        )
        println(
            "RWXGpuMapCellOracle " + label + " diagnostics registered=" +
                KoolCanvasTextureRegistry.isGpuTargetTexture(versionId) +
                " imageCreated=" + (texture.gpuTexture != null) +
                " " + (host.gpuTargetProfile() ?: "no-profile"),
        )
        check(texture.gpuTexture != null) { label + ": pass attachment has no GPU image" }
        val pixels = downloadFull(scene, texture)
        var maxDelta = 0
        var mismatches = 0
        val samples = StringBuilder()
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val expected = expectedPixel(x, y, content)
                val actual = pixels[y * WIDTH + x]
                var differs = false
                for (shift in intArrayOf(16, 8, 0, 24)) {
                    val delta = abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255))
                    if (delta > maxDelta) maxDelta = delta
                    if (delta > TOLERANCE) differs = true
                }
                if (differs) {
                    mismatches++
                    // First few mismatches with coordinates: enough to tell a blend/order problem from
                    // a wrong analytic expectation.
                    if (mismatches <= 8) {
                        samples.append(" (").append(x).append(',').append(y).append(")=")
                            .append(hex(actual)).append("!=").append(hex(expected))
                    }
                }
            }
        }
        // Report the whole picture before asserting: a fail-fast check on the first texel cannot tell a
        // missing draw from an offset or flipped one, which is exactly the distinction needed here.
        println(
            "RWXGpuMapCellOracle " + label + " actualTop=" + topColours(pixels) +
                " expectedTop=" + topColours(IntArray(WIDTH * HEIGHT) { i -> expectedPixel(i % WIDTH, i / WIDTH, content) }) +
                " mismatches=" + mismatches + "/" + (WIDTH * HEIGHT) + " maxChannelDelta=" + maxDelta +
                " corners=[" + hex(pixels[0]) + "," + hex(pixels[WIDTH - 1]) + "," +
                hex(pixels[(HEIGHT - 1) * WIDTH]) + "," + hex(pixels[WIDTH * HEIGHT - 1]) + "]" +
                " firstMismatches=" + samples,
        )
        check(mismatches == 0) {
            label + ": " + mismatches + " of " + (WIDTH * HEIGHT) + " texels differ (maxChannelDelta=" +
                maxDelta + ")"
        }
        // Orientation is asserted separately, so a systematic flip cannot hide inside a tolerance.
        check(channelsClose(pixels[0], expectedPixel(0, 0, content))) {
            label + " top-left: actual=" + hex(pixels[0]) + " expected=" + hex(expectedPixel(0, 0, content))
        }
        val bottomLeft = pixels[(HEIGHT - 1) * WIDTH]
        check(channelsClose(bottomLeft, expectedPixel(0, HEIGHT - 1, content))) {
            label + " bottom-left: actual=" + hex(bottomLeft) +
                " expected=" + hex(expectedPixel(0, HEIGHT - 1, content))
        }
        check(pixels[0] != bottomLeft) { label + ": top and bottom bands must differ" }
        println("RWXGpuMapCellOracle " + label + " texels=" + (WIDTH * HEIGHT) + " maxChannelDelta=" + maxDelta)
        return WIDTH * HEIGHT
    }

    private fun channelsClose(actual: Int, expected: Int): Boolean =
        intArrayOf(0, 8, 16, 24).all { shift ->
            abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= TOLERANCE
        }

    /** Most frequent colours first, so a summary shows what the image actually contains. */
    private fun topColours(pixels: IntArray): String =
        pixels.toList().groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(5)
            .joinToString("|") { hex(it.key) + "x" + it.value }

    /** Analytic reference. Computed here, not produced by any rasteriser. */
    private fun expectedPixel(x: Int, y: Int, content: Content): Int {
        if (realMap) {
            var pixel = if ((x / 4 + y / 4) % 2 == 0) content.topBand else BLUE
            if (fogVariant in 1..2) pixel = when (fogValue(x / 4, y / 4)) {
                10 -> 0xff000000.toInt()
                5 -> sourceOver(0x7d000000, pixel)
                else -> pixel
            }
            if (contains(BLENDED_RECT, x, y)) pixel = sourceOver(HALF_WHITE, pixel)
            if (x < 18 && contains(SECOND_BLENDED_RECT, x, y)) pixel = sourceOver(HALF_WHITE, pixel)
            return pixel
        }
        var pixel = content.clear
        if (y in TOP_BAND) pixel = content.topBand
        if (y in BOTTOM_BAND) pixel = BLUE
        if (contains(GREEN_RECT, x, y)) pixel = GREEN
        if (contains(STAMP_RECT, x, y)) pixel = STAMP
        if (contains(BLENDED_RECT, x, y)) pixel = sourceOver(HALF_WHITE, pixel)
        return pixel
    }

    private fun fogValue(x: Int, y: Int): Int = when ((x + y + fogVariant) % 3) {
        1 -> 5
        2 -> 10
        else -> 0
    }

    private fun contains(rect: Rect, x: Int, y: Int): Boolean =
        x >= rect.a && x < rect.c && y >= rect.b && y < rect.d

    /** Standard 8-bit SourceOver; close enough to both rasterisers for tolerance 3. */
    private fun sourceOver(source: Int, destination: Int): Int {
        val sourceAlpha = (source ushr 24) and 255
        val destinationAlpha = (destination ushr 24) and 255
        val alpha = (sourceAlpha + ((destinationAlpha * (255 - sourceAlpha) + 127) / 255)).coerceAtMost(255)
        fun channel(shift: Int): Int {
            val src = (source ushr shift) and 255
            val dst = (destination ushr shift) and 255
            val contribution = dst * (((255 - sourceAlpha) * destinationAlpha + 127) / 255)
            return ((src * sourceAlpha + contribution) / alpha.coerceAtLeast(1)).coerceIn(0, 255)
        }
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    /**
     * Copies the whole attachment 1:1 through a disabled-blend pass. Positions use the canvas
     * convention (top = halfHeight - destination.top), so texel (x, y) of the result is texel (x, y)
     * of the attachment and an orientation mistake shows up as a mismatch.
     */
    internal suspend fun downloadFull(scene: Scene, texture: Texture2d, width: Int = WIDTH, height: Int = HEIGHT): IntArray {
        val halfWidth = width * 0.5f
        val halfHeight = height * 0.5f
        val geometry = IndexedVertexList(VertexLayouts.PositionNormalTexCoordColor)
        geometry.addVertex(Vec3f(-halfWidth, halfHeight, 0f), color = Color.WHITE, texCoord = Vec2f(0f, 0f))
        geometry.addVertex(Vec3f(halfWidth, halfHeight, 0f), color = Color.WHITE, texCoord = Vec2f(1f, 0f))
        geometry.addVertex(Vec3f(halfWidth, -halfHeight, 0f), color = Color.WHITE, texCoord = Vec2f(1f, 1f))
        geometry.addVertex(Vec3f(-halfWidth, -halfHeight, 0f), color = Color.WHITE, texCoord = Vec2f(0f, 1f))
        geometry.addIndices(0, 1, 2, 0, 2, 3)
        val mesh = Mesh(geometry, name = "gpu-map-cell-oracle-quad").apply {
            shader = KoolCanvasTextureShader(
                PipelineConfig(
                    blendMode = BlendMode.DISABLED,
                    cullMethod = CullMethod.NO_CULLING,
                    depthTest = DepthCompareOp.ALWAYS,
                    isWriteDepth = false,
                ),
            ).apply { colorMap = texture }
        }
        val pass = OffscreenPass2d(
            Node("gpu-map-cell-oracle-root").apply { addNode(mesh) },
            AttachmentConfig.singleColorNoDepth(TexFormat.RGBA),
            Vec2i(width, height),
            "gpu-map-cell-oracle-readback-" + (readbackSerial++),
        ).apply {
            camera = OrthographicCamera().apply {
                left = -halfWidth
                right = halfWidth
                bottom = -halfHeight
                top = halfHeight
                isKeepAspectRatio = false
                setupCamera(position = Vec3f(0f, 0f, 10f), lookAt = Vec3f.ZERO)
            }
        }
        val copy = pass.copyOutput(isCopyColor = true, isCopyDepth = false)
        scene.addOffscreenPass(pass)
        return try {
            val output = checkNotNull(pass.colorTexture)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (output.gpuTexture == null) {
                check(System.nanoTime() < deadline) { "readback attachment never reached the GPU" }
                delay(5L)
            }
            val image = output.download()
            val bytes = image.data as Uint8Buffer
            IntArray(width * height) { index ->
                val offset = index * 4
                ((bytes[offset + 3].toInt() and 255) shl 24) or
                    ((bytes[offset].toInt() and 255) shl 16) or
                    ((bytes[offset + 1].toInt() and 255) shl 8) or
                    (bytes[offset + 2].toInt() and 255)
            }
        } finally {
            scene.removeOffscreenPass(pass)
            pass.release()
            copy.release()
        }
    }

    private fun paint(color: Int, blend: KoolCanvasBlendMode): KoolPaint = KoolPaint().apply {
        setColor(color)
        a(blend)
    }

    private fun hex(value: Int): String = "0x" + value.toUInt().toString(16).padStart(8, '0')

    private var readbackSerial = 0
}
