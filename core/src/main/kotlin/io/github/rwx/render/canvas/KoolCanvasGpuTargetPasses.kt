package io.github.rwx.render.canvas

import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.FilterMethod
import de.fabmax.kool.pipeline.MipMapping
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.SamplerSettings
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Viewport
import java.util.concurrent.atomic.AtomicInteger

/**
 * Render-thread owner of GPU offscreen targets (`RenderTargetMode.GPU_TARGET`).
 *
 * The original engine keeps its layer-buffer cell cache; this only changes how a cell becomes an
 * image. Instead of rasterising the recorded cell commands into an `IntArray` and uploading the whole
 * cell texture again, the cell is replayed once into a kool [OffscreenPass2d] and the pass attachment
 * is sampled by the ordinary canvas replay. That is "render once, sample many times" instead of
 * "expand the cell's command list at every sampling point", which is what made the earlier
 * frame-backed attempt slower than the CPU rasteriser.
 *
 * Cost model: one offscreen render per *content change* of a cell. The engine already decides when a
 * cell is dirty (`LayerBufferManager.updateGridParams` / `renderPendingRedraws`), and every commit
 * carries a content version, so an unchanged cell is neither re-rendered nor re-uploaded. Nothing
 * here reads pixels back to the CPU, so the CPU pixel pool and the ARGB upload path leave the map
 * redraw path entirely.
 *
 * Lifetime: a lease keys every content version separately, so a frame that is still in flight keeps
 * sampling exactly the image it was built from. A superseded version returns its pass to the pool only
 * through [KoolCanvasGpuRetirement], i.e. after the frame that sampled it completed on the GPU.
 */
internal class KoolCanvasGpuTargetPasses(
    private val idlePassLimit: Int = IDLE_PASS_LIMIT,
) : AutoCloseable {
    private val directTargetTextures = System.getenv("RWX_DIRECT_TARGET_TEXTURES") == "1"
    private val sharedTextureMaterials = if (System.getenv("RWX_SHARE_MAP_TEXTURE_MATERIALS") == "1")
        KoolCanvasInstancedTextureMaterials() else null
    /**
     * One reusable offscreen render target: its own scene, replay renderer, camera and pass.
     *
     * The replay renderer keys its mesh caches by scene identity, so a slot keeps its scene for its
     * whole life and can serve any logical target of the same size without rebuilding caches from
     * scratch.
     */
    private class Slot(
        /** A plain node: a nested [Scene] as an offscreen draw node crashes the Vulkan driver. */
        val node: Node,
        val camera: OrthographicCamera,
        val renderer: KoolCanvasFrameRenderer,
        val pass: OffscreenPass2d,
        /** The pass attachment. Its image is what every sample of this version reads. */
        val colour: Texture2d,
        /** Sampler-only view of [colour]'s image; never released on its own. */
        val nearest: Texture2d,
        val width: Int,
        val height: Int,
    ) {
        var logicalId: KoolCanvasTextureId? = null

        /** Content version this slot currently serves; cleared when it is retired. */
        var versionId: KoolCanvasTextureId? = null
        var attachedScene: Scene? = null

        /**
         * Frames this content version still has to be rendered for.
         *
         * The legacy path repeats cold content to compensate for earlier missing draws. Live tracing
         * and the DrawQueue regression now establish that pruned-group retention could lose commands
         * even with ready geometry and textures. The independent single-render candidate removes this
         * compensation after the queue repair; pixel and performance gates must both pass.
         */
        var renderFramesRemaining: Int = 0

        /**
         * Frame to replay, kept for every render frame of this version.
         *
         * The replay must re-resolve its textures on each render frame: mesh/shaders cache the bound
         * `Texture2d`, and the registry retires and releases a canvas ARGB texture as soon as the engine
         * re-registers that key. Reusing frame one's resolved textures made kool bind a released texture
         * and abort the run with "Texture2d ...-501 is already released" (measured in the A/B candidate).
         */
        var recordedFrame: KoolCanvasFrame? = null

        var released: Boolean = false
            private set

        fun release() {
            if (released) return
            released = true
            // The pass and its node go away, so their meshes can go with them.
            renderer.releaseCachedMeshes(node)
            // Disable first: `Scene.collectScene` keeps iterating the previous `sortedPasses` until
            // `extraPasses` reports a mutation, so a released-but-enabled pass would be collected and
            // fail with "OffscreenPass2d ... is already released" (measured in the A/B candidate).
            pass.isEnabled = false
            attachedScene?.let { scene ->
                if (pass.parentScene === scene) scene.removeOffscreenPass(pass)
            }
            attachedScene = null
            pass.release()
            // The view borrows the attachment image, so drop the reference before the pass frees it.
            nearest.gpuTexture = null
        }
    }

    private var rootScene: Scene? = null
    private val installed = mutableMapOf<KoolCanvasTextureId, Slot>()
    private val idle = ArrayDeque<Slot>()
    private var closed = false
    private val collectionDiagnostics = if (System.getenv("RWX_REAL_SCENE_CELL_ORACLE") == "1") {
        System.getenv("RWX_REAL_SCENE_ORACLE")?.let {
            java.io.File(it, "gpu-target-collect.ndjson").apply { parentFile.mkdirs() }.bufferedWriter()
        }
    } else null

    /**
     * Versions waiting for their first render frame.
     *
     * Under a continuous zoom every live cell re-commits every tick, so rendering each new version in the
     * frame it is installed re-rasterises ~12 cells per frame and costs about 7% of the present rate
     * (measured: renderFps 239.98 -> 224.09 in the fog-off A/B). Rendering at most a few per frame trades a
     * one-frame content lag on the remaining cells for that throughput back; the attachment those cells
     * sample still holds their previous content, which is what the lag looks like on screen.
     */
    private val pendingVersionRenders = ArrayDeque<Slot>()

    private var createdPasses = 0L
    private var retiredPasses = 0L
    private var pendingRetirements = 0
    private var supportReported = false

    /**
     * Content versions handed to a pass, and offscreen frames actually rendered.
     *
     * Under a continuous zoom every live cell produces a new version per tick, so "render each version
     * once" still means re-rasterising every live cell every tick; these two counters are what tell the
     * two apart (renders per version) before any per-frame render budget is attempted.
     */
    private var installedVersions = 0L
    private var renderedFrames = 0L

    /**
     * Slots whose fence already completed and that are only disabled and detached so far.
     *
     * Releasing a pass directly from the fence callback can land between `Scene.collectScene` building
     * its pass list and collecting that pass, which aborts the frame with
     * "OffscreenPass2d ... is already released" (measured in an A/B candidate run). The actual release
     * therefore happens in [beginFrame], which the scene host calls before kool builds that list.
     */
    private val pendingReleases = ArrayDeque<Slot>()

    /** Called once per render frame, before kool collects passes. */
    fun beginFrame() {
        // The per-frame render budget was measured as a regression (pixel oracle 256/768 mismatched and the
        // present rate fell), so every version is rendered in the frame it is installed again.
        drainPendingReleases()
        publishCounters()
        reportMemoryDiagnostics()
    }

    /**
     * Opt-in direct-memory report (`RWX_CANVAS_MEMORY_DIAGNOSTICS=1`).
     *
     * A fog-enabled GPU cell run exhausted the 2 GiB direct-buffer limit while only 37 passes had ever
     * been created, so the growth is somewhere other than the target attachments. Printing the JVM's own
     * direct-memory counter next to the registry sizes points at the owner instead of guessing.
     */
    private fun reportMemoryDiagnostics() {
        if (!memoryDiagnosticsEnabled) return
        framesSinceMemoryReport++
        if (framesSinceMemoryReport < 120) return
        framesSinceMemoryReport = 0
        val pools = directBufferPools.joinToString(",") { it.name + "=" + (it.memoryUsed / (1024 * 1024)) + "MB" }
        val heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        // Live slots are exactly one of installed / idle / pending: acquired slots leave the pool and a
        // retired slot leaves both maps, so these three sets never overlap.
        val slots = installed.values + idle + pendingReleases
        println(
            "RWXCanvasMemory buffers[$pools] heapMb=$heapMb " +
                "meshes[nodes=${slots.sumOf { it.node.children.size}} " +
                "cached=${slots.sumOf { it.renderer.cachedMeshCount()}}] " +
                "meshMapsSum[" + listOf("primitive","texture","instanced","affine","teamColor","displacement","text","sprite","ring","fog").joinToString(" ") { name -> name + "=" + slots.sumOf { slot -> slot.renderer.cachedMeshBucket(name) } } + "] " +
                "retirements[pending=${KoolCanvasGpuRetirement.pendingRetirementCount()}] " +
                "gpuTargets[created=$createdPasses versions=$installedVersions rendered=$renderedFrames resolves=${KoolCanvasTextureRegistry.resolveCallCount()} live=${installed.size} " +
                "pooled=${idle.size} pending=${pendingReleases.size} retired=$retiredPasses] " +
                KoolCanvasTextureRegistry.diagnosticCounts(),
        )
    }

    /**
     * Starts at most [MAX_CELL_RENDERS_PER_FRAME] pending versions this frame.
     *
     * A slot left waiting keeps its pass disabled, so it presents the previous version's image until its
     * turn comes; that is the intended one-frame lag, not a missing frame.
     */
    private fun drainPendingVersionRenders() {
        var started = 0
        // Serve the oldest first so a cell cannot starve behind a stream of newer commits.
        while (pendingVersionRenders.isNotEmpty() && started < MAX_CELL_RENDERS_PER_FRAME) {
            val slot = pendingVersionRenders.removeFirst()
            val version = slot.versionId
            if (slot.released || version == null || installed[version] !== slot) continue
            slot.renderFramesRemaining = RENDER_FRAMES_MIN
            slot.pass.isEnabled = true
            started++
        }
    }
    private fun drainPendingReleases() {
        var releasedThisFrame = 0
        while (pendingReleases.isNotEmpty()) {
            val slot = pendingReleases.first()
            if (!closed && idle.size < idlePassLimit) {
                pendingReleases.removeFirst()
                // `logicalId` stays: it is what lets the same cell reclaim the slot whose mesh cache is
                // already warm for it (see acquireSlot).
                slot.recordedFrame = null
                idle.addLast(slot)
                continue
            }
            // Spreading the releases keeps a zoom reset from freeing a dozen 512x512 attachments in one
            // frame; the hard cap keeps a pathological stream from growing the queue without bound.
            if (!closed && releasedThisFrame >= MAX_RELEASES_PER_FRAME && pendingReleases.size <= MAX_PENDING_RELEASES) {
                return
            }
            pendingReleases.removeFirst()
            slot.release()
            retiredPasses++
            releasedThisFrame++
        }
    }

    /**
     * Publishes the counters the A/B harness requires, once per render frame.
     *
     * `desktop/tools/map_pan_builtin_comparison.py` reads `gpuMapCellCache` from every frame-metrics
     * record and refuses a run whose measured windows show no `hits` growth, so these must be refreshed
     * even while every pass is idle (sampling happens long after the single render).
     */
    fun publishCounters() {
        if (!CanvasFrameMetrics.gpuCountersEnabled) return
        CanvasFrameMetrics.gpuMapCellCache(
            created = createdPasses,
            hits = KoolCanvasTextureRegistry.gpuTargetResolveHitCount(),
            retired = retiredPasses,
            live = installed.size,
            pending = pendingRetirements,
        )
    }

    fun attach(scene: Scene) {
        rootScene = scene
        primePool(scene)
    }

    /**
     * Creates and renders the first [idlePassLimit] passes before any target can use them.
     *
     * Kool's Vulkan offscreen pass only creates its attachment images on its first `draw`, while the
     * screen pass resolves every texture binding earlier in the same submission
     * (`RenderBackendVk.renderFrame` runs `preparePipelines` for all passes before `executePasses`).
     * A target that is first sampled on the frame its pass is created would therefore bind an image
     * that does not exist yet. Priming the pool makes every pooled slot carry a valid image from the
     * start; a target that still needs a fresh pass changes cell size, which the fixed layer-buffer
     * size rules out, and then degrades to the placeholder for one frame instead of failing.
     */
    private fun primePool(scene: Scene) {
        val size = KoolLayerBufferSizing.startupPixels
        // Diagnostic override: how many passes are pre-rendered. Bisects driver failures that depend
        // on the number of offscreen render scopes recorded in one frame.
        val target = System.getenv("RWX_GPU_MAP_CELL_PRIME_SLOTS")?.toIntOrNull()?.coerceIn(0, idlePassLimit)
            ?: idlePassLimit
        while (idle.size < target) {
            val slot = createSlot(size, size)
            scene.addOffscreenPass(slot.pass)
            slot.attachedScene = scene
            // Rendered once by the next collect; `onAfterCollect` disables it again.
            slot.pass.isEnabled = true
            idle.addLast(slot)
        }
    }

    /**
     * Materialises one content version of [target]. Called from the frame install path, which runs
     * before kool collects the passes, so the same frame both renders and samples the target.
     */
    fun install(versionId: KoolCanvasTextureId, target: FrozenCanvasResource.GpuTarget) {
        val scene = rootScene ?: error("GPU target passes are not attached to a scene")
        if (closed || installed.containsKey(versionId)) return
        val slot = acquireSlot(target.logicalId, target.width, target.height)
        slot.logicalId = target.logicalId
        slot.versionId = versionId
        // The geometry is built by the pass's own per-frame update hook, so every render frame
        // re-resolves its textures instead of reusing frame one's bindings.
        // Diagnostic: an empty frame still configures the camera and clear colour but emits no
        // geometry, which separates a pass/plumbing failure from a geometry failure.
        val recorded = if (System.getenv("RWX_GPU_MAP_CELL_SKIP_GEOMETRY") == "1") {
            target.frame.copy(commands = emptyList())
        } else {
            target.frame
        }
        slot.recordedFrame = recorded
        // `parentScene` stays set after a pass is removed again, so registration is tracked on the
        // slot: a pooled pass must be re-added to the scene when it is handed out again.
        if (slot.attachedScene == null) {
            scene.addOffscreenPass(slot.pass)
            slot.attachedScene = scene
        }
        if (System.getenv("RWX_GPU_MAP_CELL_DIAGNOSTICS") == "1") {
            println(
                "RWXGpuTarget install version=" + versionId.value +
                    " logical=" + target.logicalId.value +
                    " size=" + target.width + "x" + target.height +
                    " commands=" + recorded.commands.size +
                    " types=" + recorded.commands.groupingBy { it::class.simpleName ?: "?" }.eachCount(),
            )
        }
        // `parentScene` stays set after a pass is removed again, so registration is tracked on the
        // slot: a pooled pass must be re-added to the scene when it is handed out again.
        if (slot.attachedScene == null) {
            scene.addOffscreenPass(slot.pass)
            slot.attachedScene = scene
        }
        slot.nearest.gpuTexture = slot.colour.gpuTexture
        installedVersions++
        slot.renderFramesRemaining = RENDER_FRAMES_MIN
        slot.pass.isEnabled = true
        installed[versionId] = slot
        KoolCanvasTextureRegistry.registerGpuTargetTexture(versionId, slot.colour, slot.nearest)
    }

    /**
     * Drops one content version once no lease references it. The image stays valid until the frame
     * that sampled it completed, because the slot is only reused through the fence sink.
     */
    fun releaseVersion(versionId: KoolCanvasTextureId) {
        val slot = installed.remove(versionId) ?: return
        pendingRetirements++
        KoolCanvasGpuRetirement.retire { retireSlot(slot) }
    }

    /** Offscreen render targets that are rendering or waiting for their last reader's fence. */
    fun activeSlotCount(): Int = installed.size

    private fun retireSlot(slot: Slot) {
        pendingRetirements = (pendingRetirements - 1).coerceAtLeast(0)
        // Only disable and detach here; `drainPendingReleases` performs the release at the start of a
        // later frame, so a pass can never be released while kool still has it in this frame's list.
        pendingVersionRenders.remove(slot)
        slot.pass.isEnabled = false
        slot.attachedScene?.let { scene ->
            if (slot.pass.parentScene === scene) scene.removeOffscreenPass(slot.pass)
        }
        slot.versionId = null
        slot.attachedScene = null
        // `logicalId` is deliberately kept: the slot a cell last used is the one whose mesh cache is warm
        // for that cell, and `acquireSlot` prefers it. Clearing it here made a cell land on an arbitrary
        // pooled slot, so every content version rebuilt the whole mesh set for that cell.
        pendingReleases.addLast(slot)
    }

    /**
     * Hands a slot to [logicalId].
     *
     * A cell re-commits content on every zoom/pan step, so the slot it used before is the one with a warm
     * mesh cache for exactly those textures; preferring it keeps mesh creation proportional to the number
     * of cells instead of to the number of versions. Only when no such slot is pooled does this fall back
     * to any slot of the right size.
     */
    private fun acquireSlot(logicalId: KoolCanvasTextureId, width: Int, height: Int): Slot {
        val warm = idle.indexOfFirst { it.logicalId == logicalId && it.width == width && it.height == height }
        if (warm >= 0) return idle.removeAt(warm)
        val reusable = idle.indexOfFirst { it.width == width && it.height == height }
        if (reusable >= 0) return idle.removeAt(reusable)
        return createSlot(width, height)
    }

    private fun createSlot(width: Int, height: Int): Slot {
        val node = Node("rwx-gpu-target-node")
        val camera = OrthographicCamera("rwx-gpu-target-camera")
        // A bounded retention window is what keeps the offscreen mesh caches inside the JVM direct-memory
        // limit. Measured per-slot cache size against this value: 0 -> ~10 meshes but 2 FPS (every render
        // call rebuilt everything), 60 (the root-canvas default) -> ~580 meshes, which summed to ~18,000
        // meshes and ~2 GB across 32 slots. A version renders 1-4 frames, so 8 render calls still covers a
        // couple of consecutive versions of the same cell while keeping the total near a few hundred MB.
        val renderer = KoolCanvasFrameRenderer(meshIdentityIgnoresRecordingOwner = true, meshRetentionRenders = 8,
            useSpriteAtlas = !directTargetTextures,
            reuseInstancedTextureShaders = sharedTextureMaterials != null || System.getenv("RWX_REUSE_INSTANCED_TEXTURE_SHADERS") == "1")
        sharedTextureMaterials?.let(renderer::shareInstancedTextureMaterials)
        val pass = OffscreenPass2d(
            drawNode = node,
            attachmentConfig = AttachmentConfig {
                addColor(TexFormat.RGBA, ClearColorFill(TRANSPARENT), FilterMethod.LINEAR)
                noDepth()
            },
            initialSize = Vec2i(width, height),
            name = "rwx-gpu-target-${SLOT_SERIAL.incrementAndGet()}",
        ).apply {
            // The canvas replay already folds the Y flip into the instance positions, exactly like the
            // sprite-atlas readback oracle. Mirroring again would flip the sampled cell.
            isMirrorY = false
            viewport = Viewport(0, 0, width, height)
            this.camera = camera
        }
        val colour = checkNotNull(pass.colorTexture) { "GPU target pass has no colour attachment" }
        createdPasses++
        if (!supportReported) {
            supportReported = true
            // The A/B harness only accepts a GPU map cell run whose log states real offscreen support;
            // reaching this point is the proof, because the backend created the pass attachment.
            println("RWXVulkanConfiguration gpuMapCellSupported=true singleMapCellRender=$singleRenderPerVersion shareMapTextureMaterials=${sharedTextureMaterials != null} directTargetTextures=$directTargetTextures")
        }
        val nearest = Texture2d(
            format = colour.format,
            mipMapping = MipMapping.Off,
            samplerSettings = SamplerSettings().clamped().nearest().noAnisotropy(),
            name = "${pass.name}-nearest",
        )
        val slot = Slot(node, camera, renderer, pass, colour, nearest, width, height)
        // Rebuilt on every collected frame. A pass is only collected while it still has render frames
        // left, so this re-resolves the target's textures for each of them instead of holding frame
        // one's `Texture2d` objects, which the registry may retire in the meantime.
        pass.onUpdate {
            slot.recordedFrame?.let { pending ->
                renderedFrames++
                val childrenBefore = slot.node.children.size
                slot.renderer.renderInto(slot.node, slot.camera, pending) { color ->
                    pass.colorAttachments.single().clearColor = color
                }
                // Retain the old cold-content compensation only as the controlled baseline. A net
                // child count is not proof of GPU readiness, and queue retention is repaired separately.
                if (!singleRenderPerVersion && slot.node.children.size > childrenBefore) {
                    slot.renderFramesRemaining = maxOf(slot.renderFramesRemaining, RENDER_FRAMES_ON_NEW_MESH)
                }
            }
        }
        pass.onAfterCollect {
            // One render frame per content version. `Scene.collectScene` already captured this frame's
            // pass data, so disabling here skips only the following frames.
            slot.nearest.gpuTexture = colour.gpuTexture
            collectionDiagnostics?.let { writer ->
                writer.write("{\"frame\":${de.fabmax.kool.util.Time.frameCount},\"version\":\"${slot.versionId?.value}\",\"slot\":\"${pass.name}\",\"remaining\":${slot.renderFramesRemaining},\"meshes\":[")
                var separator = ""
                for (child in node.children) {
                    val mesh = child as? Mesh<*> ?: continue
                    if (!mesh.isVisible) continue
                    val texture = when (val shader = mesh.shader) {
                        is KoolCanvasInstancedTextureShader -> shader.colorMap
                        is KoolCanvasAffineInstancedTextureShader -> shader.colorMap
                        else -> null
                    }
                    writer.write(separator + "{\"name\":\"${mesh.name}\",\"group\":${mesh.drawGroupId},\"rendered\":${mesh.isRendered},\"vertices\":${mesh.geometry.numVertices},\"instances\":${mesh.instances?.numInstances ?: -1},\"gpuGeometry\":${mesh.geometry.gpuGeometry != null},\"texture\":\"${texture?.name}\",\"loaded\":${texture?.isLoaded ?: false}}")
                    separator = ","
                }
                writer.write("]}\n")
            }
            if (System.getenv("RWX_GPU_MAP_CELL_DIAGNOSTICS") == "1") {
                println(
                    "RWXGpuTarget frame version=" + (slot.logicalId?.value ?: "-") +
                        " remaining=" + slot.renderFramesRemaining +
                        " children=" + slot.node.children.size +
                        " verts=" + slot.node.children.sumOf { (it as? Mesh<*>)?.geometry?.numVertices ?: 0 },
                )
            }
            if (System.getenv("RWX_GPU_MAP_CELL_KEEP_PASS_ENABLED") != "1") {
                if (slot.renderFramesRemaining > 0) slot.renderFramesRemaining--
                if (slot.renderFramesRemaining == 0) pass.isEnabled = false
            }
        }
        return slot
    }

    override fun close() {
        if (closed) return
        closed = true
        collectionDiagnostics?.close()
        rootScene = null
        pendingReleases.forEach { it.release() }
        pendingReleases.clear()
        installed.values.forEach { it.release() }
        installed.clear()
        idle.forEach { it.release() }
        idle.clear()
    }

    /** Opt-in diagnostics: how many offscreen targets are live, pooled and allowed to idle. */
    fun profile(): String =
        "gpuTargets[active=${installed.size} pooled=${idle.size} limit=$idlePassLimit]"

    private companion object {
        private val singleRenderPerVersion = System.getenv("RWX_SINGLE_MAP_CELL_RENDER") == "1"
        /**
         * Idle passes are the GPU counterpart of the CPU target pixel pool: bounded so a long session
         * cannot grow attachments without limit, large enough that a zoom reset reusing the same cell
         * sizes rarely has to allocate and free on every frame.
         */
        /**
         * Idle offscreen passes kept for reuse.
         *
         * Measured trade-off, both ends limited by the same grow-only JVM direct memory
         * (`direct 鈮?5 MB x passes created + 0.11 MB x meshes created`):
         *  - 32 pooled passes: 35-49 passes created, but each slot's renderer caches ~600 meshes, so
         *    ~19,000 meshes / ~2 GB total -- fast (~100 FPS) and at the memory limit;
         *  - 8 pooled passes: only ~3,000 meshes, but 369 passes created / ~1.9 GB and 8-17 FPS.
         * 32 is kept because it holds the fog-off configuration that was validated end to end.
         */
        const val IDLE_PASS_LIMIT = 32

        private val memoryDiagnosticsEnabled = System.getenv("RWX_CANVAS_MEMORY_DIAGNOSTICS") == "1"

        /**
         * Both JVM buffer pools: the direct-memory limit covers `ByteBuffer.allocateDirect` **and**
         * `FileChannel.map`, which is how Vulkan host-visible memory is usually mapped on Windows.
         */
        private val directBufferPools: List<java.lang.management.BufferPoolMXBean> =
            java.lang.management.ManagementFactory
                .getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java)

        private var framesSinceMemoryReport = 0

        /** Bounded release work per frame; see [drainPendingReleases]. */
        const val MAX_CELL_RENDERS_PER_FRAME = 6

        const val MAX_RELEASES_PER_FRAME = 4
        const val MAX_PENDING_RELEASES = 96

        /**
         * One render frame is enough when the replay only updates geometry it already created.
         *
         * The controlled legacy path retains [RENDER_FRAMES_ON_NEW_MESH] for net child growth. The
         * queue repair and single-render pixel checks invalidate the old assumption that new meshes
         * inherently need several frames to become drawable.
         */
        const val RENDER_FRAMES_MIN = 1

        /**
         * Legacy compensation retained for A/B; it is bypassed by the single-render candidate.
         */
        const val RENDER_FRAMES_ON_NEW_MESH = 4

        val TRANSPARENT: Color = Color(0f, 0f, 0f, 0f)
        val SLOT_SERIAL = AtomicInteger()
    }
}
