package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.Texture2d
import io.github.rwx.session.GameCameraSnapshot
import java.util.Collections
import java.util.IdentityHashMap
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A completed CPU frame. Each owner has its own closeable reference to the resource lease. */
class FrameEnvelope(
    val sequence: Long,
    val generation: Long,
    val simulationTick: Int,
    val viewportRevision: Long,
    val frame: KoolCanvasFrame,
    val resourceLease: KoolCanvasResourceLease,
    val camera: GameCameraSnapshot? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    /** Call only while holding an existing owner reference. */
    fun retain(): FrameEnvelope {
        check(!closed.get()) { "Cannot retain a released frame" }
        resourceLease.retain()
        return try {
            FrameEnvelope(sequence, generation, simulationTick, viewportRevision, frame, resourceLease, camera)
        } catch (failure: Throwable) {
            resourceLease.close()
            throw failure
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) resourceLease.close()
    }
}

/** publish transfers ownership; poll atomically transfers it to the consumer. */
class LatestFrameMailbox : AutoCloseable {
    private val pending = AtomicReference<FrameEnvelope?>()
    private val gate = Any()
    private var closed = false
    fun publish(frame: FrameEnvelope) {
        synchronized(gate) {
            if (closed) frame.close() else pending.getAndSet(frame)?.close()
        }
    }
    fun poll(): FrameEnvelope? = pending.getAndSet(null)
    fun clear() { pending.getAndSet(null)?.close() }
    override fun close() {
        synchronized(gate) { closed = true; clear() }
    }
}

/** Render-owned caches share the desktop Vulkan fence retirement policy when installed. */
object KoolCanvasGpuRetirement {
    @Volatile
    private var sink: ((() -> Unit) -> Unit)? = null
    internal val hasRetirementSink: Boolean get() = sink != null

    /**
     * How many fence-scoped releases the backend is still holding, as reported by the desktop layer.
     *
     * A queued release keeps whatever it closes reachable 鈥?including a mesh's geometry buffers 鈥?so a
     * queue that grows without draining looks exactly like a leak in JVM direct memory.
     */
    @Volatile
    private var pendingCounter: (() -> Int)? = null

    internal fun install(sink: ((() -> Unit) -> Unit)?) {
        this.sink = sink
    }

    internal fun installPendingCounter(counter: (() -> Int)?) {
        pendingCounter = counter
    }

    fun retire(release: () -> Unit) { sink?.invoke(release) ?: release() }

    fun pendingRetirementCount(): Int = pendingCounter?.invoke() ?: 0
}

internal sealed interface FrozenCanvasResource {
    data class Pixels(
        val image: KoolCanvasArgbImage,
        val isStatic: Boolean,
        val pixelOwner: KoolCanvasPixelPool.PixelOwner? = null,
    ) : FrozenCanvasResource
    data class Asset(val path: String, val encodedBytes: ByteArray) : FrozenCanvasResource
    data class Frame(val frame: KoolCanvasFrame) : FrozenCanvasResource

    /**
     * A recorded offscreen target that the render thread must materialise as a real GPU offscreen
     * render pass instead of CPU pixels.
     *
     * [logicalId] is the stable target identity (one map cell) while the lease keys this resource by
     * its *content* version, so a frame that is still in flight keeps sampling the image it was
     * built from. Render-thread owners pool passes per [logicalId] and retire a version only once
     * the frame that sampled it has completed on the GPU.
     */
    data class GpuTarget(
        val logicalId: KoolCanvasTextureId,
        val frame: KoolCanvasFrame,
        val width: Int,
        val height: Int,
    ) : FrozenCanvasResource

    data object Missing : FrozenCanvasResource
}

/** CPU data is immutable for the life of this lease; no legacy pixel array is stored here. */
class KoolCanvasResourceLease internal constructor(
    resources: Map<KoolCanvasTextureId, FrozenCanvasResource>,
    internal val fonts: KoolCanvasFontSnapshot,
    pixelDedup: IdentityHashMap<KoolCanvasPixelPool.Allocation, Boolean>? = null,
) : AutoCloseable {
    private val references = AtomicInteger(1)
    private var ownedResources: Map<KoolCanvasTextureId, FrozenCanvasResource>? =
        Collections.unmodifiableMap(resources.toMap())
    private var ownedPixels: List<KoolCanvasPixelPool.PixelOwner>? = retainPixels(resources, pixelDedup)
    val isReleased: Boolean get() = references.get() == 0
    val resourceCount: Int get() = synchronized(this) { ownedResources?.size ?: 0 }

    internal fun retain() {
        while (true) {
            val count = references.get()
            check(count > 0) { "Cannot retain expired frame resources" }
            check(count < Int.MAX_VALUE) { "Too many frame resource owners" }
            if (references.compareAndSet(count, count + 1)) return
        }
    }

    internal fun resources(): Map<KoolCanvasTextureId, FrozenCanvasResource> = synchronized(this) {
        checkNotNull(ownedResources) { "Expired frame resources" }
    }

    override fun close() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "Unbalanced frame resource lease" }
        if (remaining == 0) {
            val pixels = synchronized(this) {
                ownedResources = null
                ownedPixels.also { ownedPixels = null }
            }
            try {
                pixels?.forEach { it.close() }
            } finally {
                KoolCanvasFontRegistry.releaseSnapshot(fonts)
            }
        }
    }

    private fun retainPixels(resources: Map<KoolCanvasTextureId, FrozenCanvasResource>,
                             pixelDedup: IdentityHashMap<KoolCanvasPixelPool.Allocation, Boolean>?): List<KoolCanvasPixelPool.PixelOwner> {
        val seen = pixelDedup ?: IdentityHashMap()
        val retained = ArrayList<KoolCanvasPixelPool.PixelOwner>(resources.size)
        try {
            for (resource in resources.values) {
                val owner = (resource as? FrozenCanvasResource.Pixels)?.pixelOwner ?: continue
                if (seen.put(owner.allocation, true) == null) retained.add(owner.retain())
            }
            return retained
        } catch (failure: Throwable) {
            retained.forEach { it.close() }
            throw failure
        }
    }
}

/**
 * Engine-thread recording store. Registration never constructs, uploads or releases a Texture2d.
 * Pixel ownership is detached at registration, once per real pixel revision, not once per frame.
 */
class KoolCanvasCpuTextureStore internal constructor(
    private val pixelPool: KoolCanvasPixelPool,
    internal val rasterPixelPoolEnabled: Boolean,
    freezeScratchPoolEnabled: Boolean = System.getenv("RWX_CANVAS_FREEZE_SCRATCH_POOL") != "0",
    private val reuseFrozenContent: Boolean = REUSE_ENABLED,
    private val reuseFrozenTextureReferences: Boolean = System.getenv("RWX_REUSE_FROZEN_TEXTURE_REFERENCES") == "1",
) : KoolCanvasTextureStore, KoolCanvasFrameSnapshotRetainer,
    KoolCanvasTextureRevisionStore, KoolCanvasFrameTextureRevisionStore, AutoCloseable {
    constructor() : this(KoolCanvasPixelPool(), System.getenv("RWX_CANVAS_PIXEL_POOL") != "0")

    private var packedRootTextureCommands = System.getenv("RWX_PACKED_ROOT_TEXTURE_COMMANDS") == "1"

    @JvmSynthetic
    internal fun configurePackedRootTextureCommandsForTest(enabled: Boolean) {
        check(sources.isEmpty() && !closed) { "Configure root packing before registration" }
        packedRootTextureCommands = enabled
    }

    private data class Source(val versionId: KoolCanvasTextureId, val resource: FrozenCanvasResource) {
        // CPU metadata only. One entry per source; no pixels, native handles or frame leases are cached.
        var recordedReference: KoolCanvasTextureRef? = null
        var frozenReference: KoolCanvasTextureRef? = null
        var meshIdentity: KoolCanvasFrozenPixelIdentity? = null
        var frozenFrame: KoolCanvasFrame? = null
        var frozenVersionId: KoolCanvasTextureId? = null
        /**
         * Which [versionId] the cached [frozenFrame] was frozen from, plus every source id the frozen
         * content depends on.
         *
         * A cell's frozen content is sampled by every frame that shows the cell, and a replay that pans
         * across a 5x5 grid samples each cell from hundreds of frames. The per-call identity memo in
         * [freezeFrame] cannot help across calls, so the traversal re-ran every time: a low-zoom run
         * froze 237,773,257 command objects. Reusing the result turns those re-traversals into a
         * comparison.
         *
         * The dependency list is what makes the reuse *sound*: a retained frame target keeps its own
         * version when a sprite it draws is re-registered, so the target's version alone cannot tell that
         * the frozen content went stale (`KoolCanvasFrameEnvelopeTest` covers exactly that case).
         */
        var frozenSourceVersionId: KoolCanvasTextureId? = null
        var frozenDependencies: List<Pair<KoolCanvasTextureId, KoolCanvasTextureId>>? = null
        /** Complete immutable resource closure, restored into each new lease on a cache hit. */
        var frozenResources: Map<KoolCanvasTextureId, FrozenCanvasResource>? = null
    }
    private var referenceReuseReported = false
    private fun frozenReference(ref: KoolCanvasTextureRef, id: KoolCanvasTextureId, source: Source?,
        identity: KoolCanvasFrozenPixelIdentity?): KoolCanvasTextureRef {
        if (!reuseFrozenTextureReferences || source == null || source.resource === FrozenCanvasResource.Missing)
            return ref.copy(id = id, frozenPixelIdentity = identity)
        val cached = source.frozenReference
        if (source.recordedReference == ref && cached?.id == id && cached.frozenPixelIdentity == identity) {
            if (!referenceReuseReported && System.getenv("RWX_FRAME_METRICS") != null) {
                referenceReuseReported = true
                println("RWXFrozenTextureReferences reuseConfirmed=true")
            }
            return cached
        }
        return ref.copy(id = id, frozenPixelIdentity = identity).also {
            source.recordedReference = ref; source.frozenReference = it
        }
    }

    private val sources = mutableMapOf<KoolCanvasTextureId, Source>()
    private val retainedFrames = mutableMapOf<KoolCanvasTextureId, Int>()
    private val ownerId = NEXT_OWNER.incrementAndGet()
    private val freezeScratchPool = KoolCanvasFreezeScratchPool(freezeScratchPoolEnabled)
    private val freezeScratchMetrics = if (FREEZE_SCRATCH_DIAGNOSTICS)
        CanvasFrameMetrics.FreezeScratchRecord(ownerId, freezeScratchPool) else null
    private var revision = 0
    // A logical id can be unregistered and registered again while an old frame still owns it.
    // Allocate versions across the entire store so no source lifetime can reuse an in-flight id.
    private var sourceVersionSerial = 0L
    private var putCalls = 0L
    private var freezeDiagnosticFrames = 0L
    private val traceSourceChurn = System.getenv("RWX_SOURCE_CHURN_TRACE") == "1"
    private var putGpuTargets = 0L
    private var putFrames = 0L
    private var putPixels = 0L
    private var putOthers = 0L
    private var putSameHits = 0L
    private var frozenFrameSerial = 0L
    private val preallocatedPixelSizes = mutableSetOf<Int>()
    private var closed = false
    override val textureRevision: Int get() = revision
    override val frameTextureRevision: Int get() = revision

    internal fun freezeScratchPoolStats() = freezeScratchPool.stats()

    override fun register(id: KoolCanvasTextureId, texture: Texture2d): Nothing =
        error("GPU textures cannot be registered by the engine recording thread")

    override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter): Nothing =
        error("GPU textures can only be resolved on the render thread")

    override fun registerArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray, alphaBleed: Boolean) {
        if (width <= 0 || height <= 0) return unregister(id)
        requireCompletePixels(width, height, argbPixels)
        val pixels = if (alphaBleed) KoolCanvasTextureRegistry.bleedTransparentRgb(width, height, argbPixels)
            else argbPixels.copyOf(width * height)
        put(id, FrozenCanvasResource.Pixels(KoolCanvasArgbImage(width, height, pixels), alphaBleed))
    }

    /**
     * Transfers write ownership of a newly allocated raster result to this store. The caller may
     * retain a read-only reference, but must never mutate the array after registration. Normal
     * legacy registration still detaches mutable pixels; alpha bleeding still produces a copy.
     */
    internal fun registerOwnedArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray, alphaBleed: Boolean) {
        if (width <= 0 || height <= 0) return unregister(id)
        requireCompletePixels(width, height, argbPixels)
        val pixels = if (alphaBleed) KoolCanvasTextureRegistry.bleedTransparentRgb(width, height, argbPixels)
            else argbPixels
        put(id, FrozenCanvasResource.Pixels(KoolCanvasArgbImage(width, height, pixels), alphaBleed))
    }

    /** Caller keeps its borrow; registration takes a separate immutable source owner. */
    @Synchronized
    internal fun registerPooledArgb(
        id: KoolCanvasTextureId,
        width: Int,
        height: Int,
        owner: KoolCanvasPixelPool.PixelOwner,
        alphaBleed: Boolean = false,
    ) {
        if (width <= 0 || height <= 0) return unregister(id)
        val pixels = owner.pixels
        require(pixels.size == targetPixelCount(width, height)) { "Pooled raster must have exactly the target pixel count" }
        if (alphaBleed) {
            // Bleeding creates detached pixels, so that result must not claim ownership of this array.
            registerArgb(id, width, height, pixels, true)
            return
        }
        val sourceOwner = owner.retain()
        try {
            put(id, FrozenCanvasResource.Pixels(KoolCanvasArgbImage(width, height, pixels), false, sourceOwner))
        } catch (failure: Throwable) {
            sourceOwner.close()
            throw failure
        }
    }

    /**
     * Pins the current immutable pixel version under a separate dependency id. Registrations
     * detach mutable legacy arrays or transfer a fresh raster result, so freezing an offscreen
     * dependency does not need another pixel copy or another alpha conversion. Pooled sources
     * give the snapshot its own owner; replacing either id cannot recycle the other one's data.
     */
    @Synchronized
    internal fun snapshotPixels(sourceId: KoolCanvasTextureId, snapshotId: KoolCanvasTextureId): KoolCanvasArgbImage? {
        check(!closed) { "CPU texture store is closed" }
        require(sourceId != snapshotId) { "Pixel snapshots require a separate id" }
        val source = sources[sourceId]?.resource as? FrozenCanvasResource.Pixels ?: return null
        val owner = source.pixelOwner?.retain()
        try {
            put(snapshotId, if (owner == null) source else source.copy(pixelOwner = owner))
        } catch (failure: Throwable) {
            owner?.close()
            throw failure
        }
        return source.image
    }

    /** Pins only current immutable pixels for a synchronous raster job, without freezing commands. */
    @Synchronized
    internal fun acquireRasterPixelSources(ids: Collection<KoolCanvasTextureId>): RasterPixelSources? {
        check(!closed) { "CPU texture store is closed" }
        val images = LinkedHashMap<KoolCanvasTextureId, KoolCanvasArgbImage>(ids.size)
        val allocations = IdentityHashMap<KoolCanvasPixelPool.Allocation, Boolean>()
        val retained = ArrayList<KoolCanvasPixelPool.PixelOwner>()
        try {
            for (id in ids) {
                val source = sources[id]?.resource as? FrozenCanvasResource.Pixels
                if (source == null) {
                    retained.forEach { it.close() }
                    return null
                }
                images[id] = source.image
                val owner = source.pixelOwner
                if (owner != null && allocations.put(owner.allocation, true) == null) retained.add(owner.retain())
            }
            return RasterPixelSources(images, retained)
        } catch (failure: Throwable) {
            retained.forEach { it.close() }
            throw failure
        }
    }

    internal class RasterPixelSources internal constructor(
        val images: Map<KoolCanvasTextureId, KoolCanvasArgbImage>,
        private val owners: List<KoolCanvasPixelPool.PixelOwner>,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) owners.forEach { it.close() }
        }
    }

    /** Returned storage is not cleared; a fresh raster must clear it before writing. */
    @Synchronized
    internal fun borrowTargetPixels(width: Int, height: Int): KoolCanvasPixelPool.PixelOwner? {
        check(!closed) { "CPU texture store is closed" }
        return if (rasterPixelPoolEnabled) pixelPool.borrow(targetPixelCount(width, height)) else null
    }

    /** Called by the generated map-target factory, once for each common exact target size. */
    @Synchronized
    internal fun preallocateTargetPixels(width: Int, height: Int) {
        check(!closed) { "CPU texture store is closed" }
        if (!rasterPixelPoolEnabled) return
        val size = targetPixelCount(width, height)
        if (!preallocatedPixelSizes.add(size)) return
        try {
            pixelPool.preallocate(size, 48)
        } catch (failure: Throwable) {
            preallocatedPixelSizes.remove(size)
            throw failure
        }
    }

    internal fun rasterPixelPoolStats(): KoolCanvasPixelPool.Stats = pixelPool.stats()

    /** One read-only snapshot for opt-in shutdown metrics; no per-frame logging or source access. */
    fun pixelPoolDiagnostics(): String {
        val stats = pixelPool.stats()
        return "enabled=$rasterPixelPoolEnabled retainedBytes=${stats.retainedBytes} " +
            "retainedArrays=${stats.retainedArrays} activeAllocations=${stats.activeAllocations} " +
            "allocations=${stats.allocations} misses=${stats.misses} reusedArrays=${stats.reusedArrays} " +
            "preallocatedArrays=${stats.preallocatedArrays}"
    }

    /** Thread-safe trimming without touching mutable sources when an owner may still be stopping. */
    fun clearIdlePixelPool() { pixelPool.clear() }

    override fun registerOpaqueArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray) {
        if (width <= 0 || height <= 0) return unregister(id)
        requireCompletePixels(width, height, argbPixels)
        val pixels = IntArray(width * height) { argbPixels[it] or 0xff000000.toInt() }
        put(id, FrozenCanvasResource.Pixels(KoolCanvasArgbImage(width, height, pixels), false))
    }

    override fun registerPremultipliedArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray) {
        if (width <= 0 || height <= 0) return unregister(id)
        requireCompletePixels(width, height, argbPixels)
        val pixels = KoolCanvasTextureRegistry.sanitizePremultipliedRgb(width, height, argbPixels)
        put(id, FrozenCanvasResource.Pixels(KoolCanvasArgbImage(width, height, pixels, true), false))
    }

    override fun registerAsset(id: KoolCanvasTextureId, assetPath: String) {
        val path = assetPath.removePrefix("assets/").replace('\\', '/')
        val bytes = sequenceOf(File(assetPath), File("assets", path))
            .firstOrNull { it.isFile }?.let { runCatching(it::readBytes).getOrNull() }
        registerAssetSnapshot(id, path, bytes)
    }

    @Synchronized
    override fun registerAssetSnapshot(id: KoolCanvasTextureId, assetPath: String, encodedBytes: ByteArray?) {
        val path = assetPath.removePrefix("assets/").replace('\\', '/')
        val previous = sources[id]?.resource as? FrozenCanvasResource.Asset
        if (encodedBytes == null) {
            if (sources[id]?.resource !== FrozenCanvasResource.Missing) put(id, FrozenCanvasResource.Missing)
        } else if (previous?.path != path || !previous.encodedBytes.contentEquals(encodedBytes)) {
            put(id, FrozenCanvasResource.Asset(path, encodedBytes.copyOf()))
        }
    }

    override fun registerFrame(id: KoolCanvasTextureId, frame: KoolCanvasFrame) {
        put(id, FrozenCanvasResource.Frame(frame.copy(commands = frame.commands.toList())))
    }

    /**
     * Records an offscreen target whose contents are only ever sampled by later draws. The Kool
     * backend turns it into a GPU offscreen pass; every other backend falls back to the interface
     * default, which rasterises it as an ordinary frame texture.
     */
    override fun registerGpuTargetFrame(
        id: KoolCanvasTextureId,
        frame: KoolCanvasFrame,
        width: Int,
        height: Int,
    ) {
        require(width > 0 && height > 0) { "Invalid GPU render target size" }
        put(id, FrozenCanvasResource.GpuTarget(id, frame.copy(commands = frame.commands.toList()), width, height))
    }

    @Synchronized
    override fun retainFrameSnapshot(id: KoolCanvasTextureId) {
        val resource = sources[id]?.resource
        if (resource is FrozenCanvasResource.Frame || resource is FrozenCanvasResource.Pixels ||
            resource is FrozenCanvasResource.GpuTarget
        ) {
            retainedFrames[id] = (retainedFrames[id] ?: 0) + 1
        }
    }

    @Synchronized
    override fun unregister(id: KoolCanvasTextureId) {
        UNREGISTER_CALLS.incrementAndGet()
        retainedFrames[id]?.let { count ->
            if (count <= 1) retainedFrames.remove(id) else retainedFrames[id] = count - 1
            return
        }
        sources.remove(id)?.let { source ->
            revision++
            (source.resource as? FrozenCanvasResource.Pixels)?.pixelOwner?.close()
        }
    }

    override fun frame(id: KoolCanvasTextureId): KoolCanvasFrame? =
        (sources[id]?.resource as? FrozenCanvasResource.Frame)?.frame
    override fun argbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? = argbImageView(id)?.copyPixels()
    override fun argbImageView(id: KoolCanvasTextureId): KoolCanvasArgbImage? =
        (sources[id]?.resource as? FrozenCanvasResource.Pixels)?.image
    override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? =
        (sources[id]?.resource as? FrozenCanvasResource.Pixels)?.takeIf { it.isStatic }?.image

    /** Optional accounting only. Resource/dependency state must never be stored in this holder. */
    private class FreezeDiagnostics {
        var commands = 0
        var resolved = 0
        var repeats = 0
        var recursions = 0
        var texNanos = 0L
        var copyNanos = 0L
        var compareNanos = 0L
        var contentChanged = 0
        var contentSame = 0
        var drawTexture = 0
        var sameRef = 0
        var newRef = 0
        var versionReuse = 0
        var rejectMissing = 0L
        var versionMismatch = 0L
        var mismatchSamples = 0
        var noVersionId = 0L
        var noFrame = 0L
        var noDependencies = 0L
        val heavy = HashMap<Pair<Int, Int>, Long>()
        val offscreenSizes = HashMap<Int, Long>()
        val offscreenKinds = HashMap<String, Long>()
        val volumePasses = HashMap<String, Long>()
        val volumeCommands = HashMap<String, Long>()
        val missingIds = HashMap<String, Long>()
        val sourceSeen = HashMap<Int, Int>()
        val rejectChanged = HashMap<String, Long>()
        val nestedKeys = HashMap<String, Long>()
        val nestedViewports = HashMap<String, Long>()
    }

    /** A reference contributes the same resolved dependencies only once per frame traversal. */
    private class FreezeDependencyMemo {
        var scope = Any()
        private val seen = IdentityHashMap<KoolCanvasTextureRef, Any>()
        fun note(reference: KoolCanvasTextureRef): Boolean = seen.put(reference, scope) !== scope
    }

    @Synchronized
    fun freezeFrame(
        frame: KoolCanvasFrame,
        sequence: Long,
        generation: Long,
        simulationTick: Int,
        viewportRevision: Long,
        camera: GameCameraSnapshot? = null,
    ): FrameEnvelope {
        check(!closed) { "CPU texture store is closed" }
        val freezeStart = CanvasRenderStageTrace.start()
        val scratch = freezeScratchPool.borrow()
        val traceFreezeDetail = CanvasRenderStageTrace.enabled
        // This memo belongs to one traversal, never to a published frame or a cross-frame cache.
        val frozenFrames = scratch?.frozenFrames ?: IdentityHashMap<KoolCanvasFrame, KoolCanvasFrame>()
        val freezeDiagnosticFrame = if (traceFreezeDetail) ++freezeDiagnosticFrames else 0L
        val freezeDetailDependencies = ArrayList<Pair<KoolCanvasTextureId, KoolCanvasTextureId>>()
        val dependencyMemo = if (reuseFrozenContent) FreezeDependencyMemo() else null
        // Canvas stages and the reuse sidecar are the consumers of detailed accounting. Ordinary
        // FRAME_METRICS only consumes the independent scratch-pool stats in finally below.
        // Attempts/hits remain unconditional: the existing full-freeze verifier reads them directly.
        val detail = if (traceFreezeDetail || REUSE_TRACE_ENABLED) FreezeDiagnostics() else null
        fun snapshotDependencies(from: Int): List<Pair<KoolCanvasTextureId, KoolCanvasTextureId>> =
            freezeDetailDependencies.subList(from, freezeDetailDependencies.size).distinctBy { it.first }

        fun isFrozenContentCurrent(source: Source): Boolean {
            // A hit restores the entire validated transitive graph, not just a command list.
            // RWX_FROZEN_CONTENT_REUSE=0 keeps the complete traversal available as a control path.
            if (!reuseFrozenContent) return false
            val closure = source.frozenResources ?: return false
            // Missing/cyclic references depend on the current traversal context and are not reusable.
            if (closure.values.any { it === FrozenCanvasResource.Missing }) return false
            // Process-wide outcome counters. The whole-frame reuse never hit (classic freezes 6,358 times
            // a run while only ~32 cells and ~2.8 recordings/s exist), so the reason has to be named
            // rather than inferred: a per-call counter cannot answer a cross-call question.
            REUSE_ATTEMPTS.incrementAndGet()
            if (source.frozenSourceVersionId != source.versionId) {
                if (detail != null) REUSE_VERSION_BASE.incrementAndGet()
                if (traceFreezeDetail && detail != null) {
                    detail.versionMismatch++
                    if (detail.mismatchSamples < 3) {
                        detail.mismatchSamples++
                        println("[RWX canvas] vMismatch stored=" + source.frozenSourceVersionId?.value +
                                " live=" + source.versionId.value +
                                " srcIdentity=" + System.identityHashCode(source) +
                                " seenCount=" + (detail.sourceSeen[System.identityHashCode(source)] ?: 0) +
                                " sourcesSize=" + sources.size)
                    }
                    detail.sourceSeen[System.identityHashCode(source)] =
                        (detail.sourceSeen[System.identityHashCode(source)] ?: 0) + 1
                }
                reuseReport()
                return false
            }
            if (source.frozenVersionId == null) {
                if (detail != null) REUSE_NO_VERSION.incrementAndGet()
                reuseReport()
                if (traceFreezeDetail && detail != null) detail.noVersionId++
                return false
            }
            if (source.frozenFrame == null) {
                if (detail != null) REUSE_NO_FRAME.incrementAndGet()
                reuseReport()
                if (traceFreezeDetail && detail != null) detail.noFrame++
                return false
            }
            val dependencies = source.frozenDependencies
            if (dependencies == null) {
                if (detail != null) REUSE_NO_DEPS.incrementAndGet()
                reuseReport()
                if (traceFreezeDetail && detail != null) detail.noDependencies++
                return false
            }
            if (detail != null) {
                REUSE_DEPS_CHECKS.incrementAndGet()
                REUSE_DEPS_SUM.addAndGet(dependencies.size.toLong())
                REUSE_DEPS_MAX.accumulateAndGet(dependencies.size.toLong(), ::maxOf)
            }
            for ((dependencyId, versionId) in dependencies) {
                val live = sources[dependencyId]
                if (live == null) {
                    if (detail != null) REUSE_DEP_MISSING.incrementAndGet()
                    reuseReport()
                    // Name the ids that keep going missing. A dependency that is gone at every check is
                    // transient by construction, and a cache that validates it can never hit.
                    if (detail != null) REUSE_MISSING_IDS.merge(dependencyId.value.takeLast(30), 1L, Long::plus)
                    if (traceFreezeDetail && detail != null) {
                        detail.rejectMissing++
                        detail.missingIds[dependencyId.value.takeLast(34)] =
                            (detail.missingIds[dependencyId.value.takeLast(34)] ?: 0L) + 1
                    }
                    return false
                }
                if (live.versionId != versionId) {
                    if (detail != null) REUSE_DEP_CHANGED.incrementAndGet()
                    reuseReport()
                    // Name the dependencies whose version moved between freeze and check. If these dominate
                    // during camera motion, the cache is being rebuilt every frame and the cost is churn
                    // rather than miss; if they are absent, the cache is holding and the cost lies in what
                    // the cached result hands to the GPU layer.
                    if (detail != null) REUSE_CHANGED_IDS.merge(dependencyId.value.takeLast(30), 1L, Long::plus)
                    if (traceFreezeDetail && detail != null) detail.rejectChanged[dependencyId.value.takeLast(28)] =
                        (detail.rejectChanged[dependencyId.value.takeLast(28)] ?: 0L) + 1
                    return false
                }
            }
            REUSE_HITS.incrementAndGet()
            reuseReport()
            return true
        }
        try {
            val resources = linkedMapOf<KoolCanvasTextureId, FrozenCanvasResource>()
            val visiting = scratch?.visiting ?: mutableSetOf<KoolCanvasTextureId>()
            val resolvedIds = scratch?.resolvedIds ?: mutableMapOf<KoolCanvasTextureId, KoolCanvasTextureId>()
            val frozenReferences = scratch?.frozenReferences ?: IdentityHashMap<KoolCanvasTextureRef, KoolCanvasTextureRef>()
            fun resourceClosure(frame: KoolCanvasFrame): Map<KoolCanvasTextureId, FrozenCanvasResource> {
                val closure = linkedMapOf<KoolCanvasTextureId, FrozenCanvasResource>()
                val pending = ArrayDeque<KoolCanvasTextureId>()
                fun commands(frame: KoolCanvasFrame) {
                    for (command in frame.commands) {
                        val paint = when (command) {
                            is KoolCanvasCommand.DrawTexture -> { pending.addLast(command.texture.id); command.paint }
                            is KoolCanvasCommand.DrawTextureRepeat -> { pending.addLast(command.texture.id); command.paint }
                            is KoolCanvasCommand.DrawTextureBatch -> { pending.addLast(command.texture.id); command.paint }
                            is KoolCanvasCommand.DrawFogBatch -> { command.texture?.let { pending.addLast(it.id) }; command.paint }
                            is KoolCanvasCommand.DrawRectBatch -> command.paint
                            is KoolCanvasCommand.DrawRect -> command.paint
                            is KoolCanvasCommand.DrawLine -> command.paint
                            is KoolCanvasCommand.DrawCircle -> command.paint
                            is KoolCanvasCommand.DrawText -> command.paint
                            is KoolCanvasCommand.Clear -> null
                        }
                        (paint?.textureEffect as? KoolCanvasTextureEffect.Displacement)?.let {
                            pending.addLast(it.screenBase.id)
                        }
                    }
                }
                commands(frame)
                while (pending.isNotEmpty()) {
                    val id = pending.removeLast()
                    if (id in closure) continue
                    val resource = checkNotNull(resources[id]) { "Frozen resource closure is incomplete: ${id.value}" }
                    closure[id] = resource
                    when (resource) {
                        is FrozenCanvasResource.Frame -> commands(resource.frame)
                        is FrozenCanvasResource.GpuTarget -> commands(resource.frame)
                        else -> Unit
                    }
                }
                return Collections.unmodifiableMap(closure)
            }
            fun noteResolvedDependencies(ref: KoolCanvasTextureRef, source: Source?) {
                if (reuseFrozenContent && source != null) {
                    // Same resolved ref, same traversal: the earlier contribution is already in
                    // this target's dependency interval. Nested/sibling traversals have new tokens.
                    if (dependencyMemo != null && !dependencyMemo.note(ref)) return
                    freezeDetailDependencies += ref.id to source.versionId
                    // A memo hit still contributes its descendants to its enclosing target's cache.
                    source.frozenDependencies?.let(freezeDetailDependencies::addAll)
                }
            }
            lateinit var freeze: (KoolCanvasFrame) -> KoolCanvasFrame
            fun texture(ref: KoolCanvasTextureRef): KoolCanvasTextureRef {
                frozenReferences[ref]?.let {
                    if (reuseFrozenContent) noteResolvedDependencies(ref, sources[ref.id])
                    return it
                }
                val source = sources[ref.id]
                val resource = source?.resource
                val id = source?.versionId ?: KoolCanvasTextureId("${ref.id.value}/cpu-$ownerId-missing")
                // A stable identity for every *recorded* texture, keyed by the recording owner and the
                // logical id instead of the content version, so the renderer can reuse one mesh per
                // logical texture and rebind the image. Tying this to `Pixels` alone left every other
                // recorded texture (nested frames and GPU targets, which no longer take a CPU pixel
                // snapshot, plus assets) with a version-keyed mesh identity. A GPU target *replays* its
                // frame for every content version, so each version then created a fresh mesh: a measured
                // fog-enabled run churned ~18,900 meshes until their geometry buffers exhausted the JVM
                // direct-memory limit. The CPU raster path never replays, which is why only this path paid.
                val meshIdentity = if (resource == null || resource === FrozenCanvasResource.Missing) {
                    null
                } else {
                    if (reuseFrozenTextureReferences) checkNotNull(source).meshIdentity
                        ?: KoolCanvasFrozenPixelIdentity(ownerId, ref.id).also { source.meshIdentity = it }
                    else KoolCanvasFrozenPixelIdentity(ownerId, ref.id)
                }
                resolvedIds[id]?.let { resolvedId ->
                    noteResolvedDependencies(ref, source)
                    return frozenReference(ref, resolvedId, source, meshIdentity).also {
                        if (resource != null && resource !== FrozenCanvasResource.Missing) frozenReferences[ref] = it
                    }
                }
                if (id in visiting) {
                    val cycleId = KoolCanvasTextureId("${id.value}/cycle")
                    resources[cycleId] = FrozenCanvasResource.Missing
                    return ref.copy(id = cycleId, frozenPixelIdentity = null)
                }
                visiting += id
                // Recording the raw source ids this freeze reads, so a later reuse can tell whether any of
                // them was re-registered. `resource.frame` is a Frame or GpuTarget; its own commands read
                // further sources, which the recursive freeze records into the same list.
                val dependenciesBefore = freezeDetailDependencies.size
                // Keyed by the *base* id (`ref.id`), never the versioned composite: `id` above is
                // `"<base>/cpu-<owner>-v<n>"` for a frozen source, and `sources` is keyed by the base id.
                // Recording the composite made every dependency lookup miss, so whole-frame reuse was
                // rejected 95% of the time by `depMissing` and never hit once (measured: `hits=0` over
                // 70 000 attempts, with the "missing" ids all being versioned strings).
                if (reuseFrozenContent && source != null) freezeDetailDependencies += ref.id to source.versionId
                if (traceFreezeDetail && detail != null && source != null &&
                    (resource is FrozenCanvasResource.Frame || resource is FrozenCanvasResource.GpuTarget)
                ) {
                    val identity = System.identityHashCode(source)
                    FROZEN_SOURCE_IDENTITIES.add(identity)
                    RESOLVED_TOTAL.incrementAndGet()
                    if (source.frozenSourceVersionId != null) RESOLVED_WITH_STORED_VERSION.incrementAndGet()
                    if (RESOLVED_TOTAL.get() % 50000L == 0L) {
                        println("[RWX canvas] sourceReuse resolved=" + RESOLVED_TOTAL.get() +
                                " distinctSources=" + FROZEN_SOURCE_IDENTITIES.size +
                                " withStoredVersion=" + RESOLVED_WITH_STORED_VERSION.get() +
                                " versionReuse=" + detail.versionReuse)
                    }
                }
                if (traceFreezeDetail && detail != null &&
                    (resource is FrozenCanvasResource.Frame || resource is FrozenCanvasResource.GpuTarget)) {
                    // Who owns the expensive nested frames: kind, shape and the source id's tail, so the
                    // registration path that keeps re-freezing a 512x512 layer-buffer cell is named
                    // instead of guessed.
                    val kind = if (resource is FrozenCanvasResource.Frame) "Frame" else "GpuTarget"
                    val shape = source?.versionId?.value?.let { runCatching { it.substringAfterLast("/cpu-").substringBefore("-version-") }.getOrNull() } ?: "?"
                    val key = kind + "|" + shape
                    detail.nestedKeys[key] = (detail.nestedKeys[key] ?: 0L) + 1
                    val vp = if (resource is FrozenCanvasResource.Frame) resource.frame.viewport else (resource as FrozenCanvasResource.GpuTarget).frame.viewport
                    val vpKey = kind + " " + vp.width + "x" + vp.height
                    detail.nestedViewports[vpKey] = (detail.nestedViewports[vpKey] ?: 0L) + 1
                }
                val resolvedId = when (resource) {
                    is FrozenCanvasResource.Frame -> {
                        if (source != null && isFrozenContentCurrent(source)) {
                            if (detail != null) detail.versionReuse++
                            resources.putAll(checkNotNull(source.frozenResources))
                            freezeDetailDependencies.addAll(checkNotNull(source.frozenDependencies))
                            checkNotNull(source.frozenVersionId).also { resources[it] = FrozenCanvasResource.Frame(checkNotNull(source.frozenFrame)) }
                        } else {
                        val frozen = freeze(resource.frame)
                        if (source.frozenFrame != frozen) {
                            source.frozenFrame = frozen
                            source.frozenVersionId = KoolCanvasTextureId("${id.value}/frozen-${++frozenFrameSerial}")
                        }
                        source.frozenSourceVersionId = source.versionId
                        if (reuseFrozenContent) {
                            source.frozenDependencies = snapshotDependencies(dependenciesBefore)
                            source.frozenResources = resourceClosure(frozen)
                        }
                        checkNotNull(source.frozenVersionId).also { resources[it] = FrozenCanvasResource.Frame(frozen) }
                        }
                    }

                    is FrozenCanvasResource.GpuTarget -> {
                        // Same content-version stability as a frame texture, but the render thread
                        // turns the version into its own offscreen pass image instead of pixels.
                        if (source != null && isFrozenContentCurrent(source)) {
                            if (detail != null) detail.versionReuse++
                            resources.putAll(checkNotNull(source.frozenResources))
                            freezeDetailDependencies.addAll(checkNotNull(source.frozenDependencies))
                            checkNotNull(source.frozenVersionId).also {
                                resources[it] = FrozenCanvasResource.GpuTarget(
                                    resource.logicalId, checkNotNull(source.frozenFrame), resource.width, resource.height)
                            }
                        } else {
                        val gpuFreezeStart = if (traceFreezeDetail) System.nanoTime() else 0L
                        val frozen = freeze(resource.frame)
                        if (gpuFreezeStart != 0L && detail != null) detail.copyNanos += System.nanoTime() - gpuFreezeStart
                        // Only *draw content* decides the version. `visualStats` carries selected/visible
                        // unit counts for the adaptive-visual logic, and units crossing the view edge change
                        // it every tick; treating that as new content gave every live cell a fresh version
                        // (and a full pass install plus mesh rebuild) per tick - measured at ~57 installs/s
                        // during a continuous zoom, which is the dominant recurring cost of this path.
                        val previous = source.frozenFrame
                        val compareStart = if (traceFreezeDetail) System.nanoTime() else 0L
                        val contentChanged = previous == null || previous.commands != frozen.commands ||
                            previous.viewport != frozen.viewport
                        if (compareStart != 0L && detail != null) {
                            detail.compareNanos += System.nanoTime() - compareStart
                            if (contentChanged) detail.contentChanged++ else detail.contentSame++
                        }
                        if (contentChanged) {
                            source.frozenVersionId = KoolCanvasTextureId("${id.value}/gpu-${++frozenFrameSerial}")
                        }
                        // The newest recording is kept either way, so the next comparison is against it.
                        source.frozenFrame = frozen
                        source.frozenSourceVersionId = source.versionId
                        if (reuseFrozenContent) {
                            source.frozenDependencies = snapshotDependencies(dependenciesBefore)
                            source.frozenResources = resourceClosure(frozen)
                        }
                        checkNotNull(source.frozenVersionId).also {
                            resources[it] = FrozenCanvasResource.GpuTarget(
                                resource.logicalId, frozen, resource.width, resource.height)
                        }
                        }
                    }

                    else -> {
                        resources[id] = resource ?: FrozenCanvasResource.Missing
                        id
                    }
                }
                visiting -= id
                resolvedIds[id] = resolvedId
                return frozenReference(ref, resolvedId, source, meshIdentity).also {
                    if (resource != null && resource !== FrozenCanvasResource.Missing) frozenReferences[ref] = it
                }
            }
            fun paint(paint: KoolCanvasPaint): KoolCanvasPaint {
                val displacement = paint.textureEffect as? KoolCanvasTextureEffect.Displacement ?: return paint
                return paint.copy(textureEffect = displacement.copy(screenBase = texture(displacement.screenBase)))
            }
            freeze = { source ->
                if (detail != null) detail.recursions++
                val alreadyFrozen = frozenFrames[source]
                if (alreadyFrozen != null) {
                    if (detail != null) detail.repeats++
                    alreadyFrozen
                } else {
                val previousDependencyScope = dependencyMemo?.scope
                if (dependencyMemo != null) dependencyMemo.scope = Any()
                try {
                if (traceFreezeDetail && detail != null) {
                    val viewport = source.viewport.width to source.viewport.height
                    detail.heavy[viewport] = (detail.heavy[viewport] ?: 0L) + source.commands.size
                    // Total command volume per viewport class: this is what says whether the cost lives in
                    // the one main frame or in the many offscreen cell frames.
                    val classKey = if (source.viewport.width <= 600 && source.viewport.height <= 600)
                        "offscreen" else "main"
                    detail.volumePasses[classKey] = (detail.volumePasses[classKey] ?: 0L) + 1
                    detail.volumeCommands[classKey] =
                        (detail.volumeCommands[classKey] ?: 0L) + source.commands.size
                    // What an offscreen (layer-buffer) frame actually contains: the nested sizes and the
                    // command-kind mix decide whether the cost is "many cells" or "one huge cell".
                    if (source.viewport.width <= 600 && source.viewport.height <= 600) {
                        val bucket = source.commands.size / 1000 * 1000
                        detail.offscreenSizes[bucket] = (detail.offscreenSizes[bucket] ?: 0L) + 1
                        for (command in source.commands) {
                            val kind = when (command) {
                                is KoolCanvasCommand.DrawTexture -> "tex"
                                is KoolCanvasCommand.DrawTextureRepeat -> "texRep"
                                is KoolCanvasCommand.DrawRect -> "rect"
                                is KoolCanvasCommand.DrawText -> "text"
                                is KoolCanvasCommand.DrawLine -> "line"
                                is KoolCanvasCommand.DrawCircle -> "circle"
                                else -> "clear"
                            }
                            detail.offscreenKinds[kind] = (detail.offscreenKinds[kind] ?: 0L) + 1
                        }
                    }
                }
                val copyStart = if (traceFreezeDetail) System.nanoTime() else 0L
                fun copyCommand(command: KoolCanvasCommand): KoolCanvasCommand = when (command) {
                        is KoolCanvasCommand.DrawTexture -> {
                            val texStart = if (traceFreezeDetail) System.nanoTime() else 0L
                            val resolved = texture(command.texture)
                            if (texStart != 0L && detail != null) detail.texNanos += System.nanoTime() - texStart
                            if (detail != null) {
                                val sameId = resolved.id == command.texture.id
                                val samePaint = resolved === command.texture ||
                                    (resolved.id == command.texture.id && resolved.frozenPixelIdentity == command.texture.frozenPixelIdentity)
                                if (sameId) detail.resolved++
                                if (samePaint) detail.sameRef++ else detail.newRef++
                            }
                            if (detail != null) detail.drawTexture++
                            command.copy(texture = resolved, paint = paint(command.paint)).also { if (detail != null) detail.commands++ }
                        }
                        is KoolCanvasCommand.DrawTextureRepeat -> {
                            val texStart = if (traceFreezeDetail) System.nanoTime() else 0L
                            val resolved = texture(command.texture)
                            if (texStart != 0L && detail != null) detail.texNanos += System.nanoTime() - texStart
                            if (detail != null) detail.drawTexture++
                            command.copy(texture = resolved, paint = paint(command.paint)).also { if (detail != null) detail.commands++ }
                        }
                        is KoolCanvasCommand.DrawTextureBatch ->
                            command.copy(texture = texture(command.texture), paint = paint(command.paint))
                        is KoolCanvasCommand.DrawRect -> {
                            val frozenPaint = paint(command.paint)
                            (if (SHARE_IMMUTABLE_COMMANDS && frozenPaint === command.paint) command
                            else command.copy(paint = frozenPaint)).also { if (detail != null) detail.commands++ }
                        }
                        is KoolCanvasCommand.DrawRectBatch -> command.copy(paint = paint(command.paint))
                        is KoolCanvasCommand.DrawFogBatch -> command.copy(texture = command.texture?.let { texture(it) },
                            paint = paint(command.paint))
                        is KoolCanvasCommand.DrawLine -> {
                            val frozenPaint = paint(command.paint)
                            (if (SHARE_IMMUTABLE_COMMANDS && frozenPaint === command.paint) command
                            else command.copy(paint = frozenPaint)).also { if (detail != null) detail.commands++ }
                        }
                        is KoolCanvasCommand.DrawCircle -> {
                            val frozenPaint = paint(command.paint)
                            (if (SHARE_IMMUTABLE_COMMANDS && frozenPaint === command.paint) command
                            else command.copy(paint = frozenPaint)).also { if (detail != null) detail.commands++ }
                        }
                        is KoolCanvasCommand.DrawText -> {
                            val frozenPaint = paint(command.paint)
                            (if (SHARE_IMMUTABLE_COMMANDS && frozenPaint === command.paint) command
                            else command.copy(paint = frozenPaint)).also { if (detail != null) detail.commands++ }
                        }
                        is KoolCanvasCommand.Clear -> command.also { if (detail != null) detail.commands++ }
                    }
                val copiedCommands = if (packedRootTextureCommands && source === frame) {
                    val builder = KoolCanvasPackedTextureCommands.Builder(source.commands.size)
                    // Only static root images enter the palette. Other commands use the untouched
                    // resolver, including nested frames, GPU targets, cycles and missing resources.
                    val palette = IdentityHashMap<KoolCanvasTextureRef, Pair<KoolCanvasTextureRef, Int>>()
                    val rejected = IdentityHashMap<KoolCanvasTextureRef, Boolean>()
                    for (command in source.commands) {
                        val draw = command as? KoolCanvasCommand.DrawTexture
                        val paletteEntry = if (draw != null && KoolCanvasPackedTextureCommands.canPack(draw)) {
                            palette[draw.texture] ?: if (rejected[draw.texture] == null) {
                                val live = sources[draw.texture.id]?.resource as? FrozenCanvasResource.Pixels
                                if (live?.isStatic == true && live.image.width == draw.texture.width &&
                                    live.image.height == draw.texture.height && !live.image.premultipliedAlpha) {
                                    val texStart = if (traceFreezeDetail) System.nanoTime() else 0L
                                    val frozen = texture(draw.texture)
                                    if (texStart != 0L && detail != null) detail.texNanos += System.nanoTime() - texStart
                                    (frozen to builder.textureIndex(frozen)).also { palette[draw.texture] = it }
                                } else { rejected[draw.texture] = true; null }
                            } else null
                        } else null
                        if (draw != null && paletteEntry != null) {
                            val resolved = paletteEntry.first
                            if (detail != null) {
                                if (resolved.id == draw.texture.id) detail.resolved++
                                if (resolved === draw.texture || (resolved.id == draw.texture.id &&
                                        resolved.frozenPixelIdentity == draw.texture.frozenPixelIdentity)) detail.sameRef++
                                else detail.newRef++
                            }
                            if (detail != null) detail.drawTexture++
                            builder.add(draw, paletteEntry.second)
                        } else builder.addLegacy(copyCommand(command))
                    }
                    builder.build()
                } else Collections.unmodifiableList(source.commands.map(::copyCommand))
                val copied = source.copy(commands = copiedCommands).also { frozen -> frozenFrames[source] = frozen }
                if (copyStart != 0L && detail != null) detail.copyNanos += System.nanoTime() - copyStart
                copied
                } finally {
                    if (dependencyMemo != null) dependencyMemo.scope = checkNotNull(previousDependencyScope)
                }
                }
            }
            val immutableFrame = freeze(frame)
            val fonts = KoolCanvasFontRegistry.snapshot()
            val lease = try {
                KoolCanvasResourceLease(resources, fonts, scratch?.seenPixels)
            } catch (failure: Throwable) {
                KoolCanvasFontRegistry.releaseSnapshot(fonts)
                throw failure
            }
            return try {
                FrameEnvelope(sequence, generation, simulationTick, viewportRevision, immutableFrame, lease, camera)
            } catch (failure: Throwable) {
                lease.close()
                throw failure
            }
                .also { CanvasRenderStageTrace.record("engine-freeze", freezeStart, frame.commands.size.toLong(), resources.size.toLong()) }
                .also {
                    if (traceFreezeDetail && detail != null) {
                        CanvasRenderStageTrace.record("freeze-detail", freezeStart, detail.commands.toLong(),
                            detail.resolved.toLong(), frame.commands.size.toLong())
                        CanvasRenderStageTrace.record("freeze-repeat", freezeStart, detail.recursions.toLong(),
                            detail.repeats.toLong())
                        CanvasRenderStageTrace.record("freeze-split", freezeStart, detail.texNanos / 1000,
                            detail.copyNanos / 1000, detail.compareNanos / 1000)
                        CanvasRenderStageTrace.record("freeze-content", freezeStart,
                            detail.contentChanged.toLong(), detail.contentSame.toLong())
                        CanvasRenderStageTrace.record("freeze-refs", freezeStart,
                            detail.drawTexture.toLong(), detail.sameRef.toLong(),
                            detail.newRef.toLong())
                        CanvasRenderStageTrace.record("freeze-version-reuse", freezeStart,
                            detail.versionReuse.toLong(), detail.recursions.toLong())
                        // Where the 15x recursion actually comes from: which nested viewports dominate.
                        // Measured on a low-zoom pan: 512x512 (the layer-buffer cells) hold 159 M of the
                        // 170 M frozen command objects against 11 M for the 1280x720 main frame.
                        for ((viewport, commands) in detail.heavy.entries.sortedByDescending { it.value }.take(4)) {
                            CanvasRenderStageTrace.record("freeze-heavy", freezeStart,
                                viewport.first.toLong(), viewport.second.toLong(), commands)
                        }
                        CanvasRenderStageTrace.record("freeze-reject", freezeStart, detail.rejectMissing)
                        for ((nested, count) in detail.nestedViewports.entries.sortedByDescending { it.value }.take(6)) {
                            CanvasRenderStageTrace.record("freeze-nested-vp", freezeStart, count, 0L,
                                nested.hashCode().toLong())
                        }
                        for ((nested, count) in detail.nestedKeys.entries.sortedByDescending { it.value }.take(6)) {
                            CanvasRenderStageTrace.record("freeze-nested-kind", freezeStart, count, 0L,
                                nested.hashCode().toLong())
                        }
                        for ((dependency, rejects) in detail.rejectChanged.entries.sortedByDescending { it.value }.take(6)) {
                            CanvasRenderStageTrace.record("freeze-reject-dep", freezeStart, rejects, 0L,
                                dependency.hashCode().toLong())
                        }
                        if (freezeDiagnosticFrame <= 3) {
                            println("[RWX canvas] freezeReject missing=" + detail.rejectMissing + " top=" +
                                detail.rejectChanged.entries.sortedByDescending { it.value }.take(3)
                                    .joinToString { it.key + "x" + it.value })
                        }
                        if (freezeDiagnosticFrame <= 3 || freezeDiagnosticFrame % 150 == 0L) {
                            println("[RWX canvas] freezeNested #" + freezeDiagnosticFrame + " viewports=" +
                                detail.nestedViewports.entries.sortedByDescending { it.value }.take(2)
                                    .joinToString { it.key + "x" + it.value } +
                                " reuseTerms[vMismatch=" + detail.versionMismatch + " noVer=" + detail.noVersionId +
                                " noFrame=" + detail.noFrame + " noDeps=" + detail.noDependencies +
                                " depMissing=" + detail.rejectMissing + " depChanged=" + detail.rejectChanged.values.sum() +
                                " versionReuse=" + detail.versionReuse + "]" +
                                " missingTop=" + detail.missingIds.entries.sortedByDescending { it.value }.take(2)
                                    .joinToString { it.key + "x" + it.value } +
                                " offscreenSizes=" + detail.offscreenSizes.entries.sortedByDescending { it.value }.take(3)
                                    .joinToString { it.key.toString() + "x" + it.value } +
                                " offscreenKinds=" + detail.offscreenKinds.entries.sortedByDescending { it.value }.take(4)
                                    .joinToString { it.key + "x" + it.value } +
                                " volume=" + detail.volumePasses.entries.joinToString { (kind, passes) ->
                                    kind + ":passes=" + passes + ",commands=" + (detail.volumeCommands[kind] ?: 0L)
                                })
                        }
                    }
                }
        } finally {
            freezeScratchPool.release(scratch)
            freezeScratchMetrics?.let(CanvasFrameMetrics::freezeScratchPool)
        }
    }

    @Synchronized
    private fun put(id: KoolCanvasTextureId, resource: FrozenCanvasResource) {
        check(!closed) { "CPU texture store is closed" }
        // Content-aware registration. A re-commit that produces identical content must keep the previous
        // `Source` *and its versionId*: the version is what everything downstream keys on, and it used to
        // reflect only "was registered", never "did the content change". That made
        // `Source.isFrozenContentCurrent` always false, so a 512x512 layer-buffer cell -- 93.4% of all
        // frozen command objects -- was re-frozen in full on every frame even though its recording was
        // unchanged (measured `freeze-content`: 100 728 same against 3 134 changed). Keeping the version
        // stable is what lets the dependency check and the incremental freeze actually hit.
        val previousSource = sources[id]
        if (traceSourceChurn) {
            // Counted unconditionally: the earlier "put fires once" reading came from a counter that only
            // ran inside the content-aware branch, which was itself the thing under test. Diagnostic only,
            // behind `RWX_SOURCE_CHURN_TRACE`, because the counters and the periodic print cost real work on
            // a path that runs for every registered texture.
            putCalls++
            val globalPuts = PUT_CALLS.incrementAndGet()
            if (globalPuts % 50000L == 0L) {
                println("[RWX canvas] sourceChurn puts=" + globalPuts + " unregisters=" + UNREGISTER_CALLS.get() +
                        " storePuts=" + putCalls + " sameHits=" + putSameHits)
            }
            when (resource) {
                is FrozenCanvasResource.GpuTarget -> putGpuTargets++
                is FrozenCanvasResource.Frame -> putFrames++
                is FrozenCanvasResource.Pixels -> putPixels++
                else -> putOthers++
            }
            if (putCalls % 20000L == 0L) {
                println("[RWX canvas] putStats calls=" + putCalls + " gpuTarget=" + putGpuTargets +
                        " frame=" + putFrames + " pixels=" + putPixels + " other=" + putOthers +
                        " sameHits=" + putSameHits)
            }
        }
        if (CONTENT_AWARE_REGISTRATION && previousSource != null && sameContent(previousSource.resource, resource)) {
            // The new resource's owners are redundant with the ones already held.
            if (traceSourceChurn) putSameHits++
            (resource as? FrozenCanvasResource.Pixels)?.pixelOwner?.close()
            return
        }
        check(sourceVersionSerial < Long.MAX_VALUE) { "Canvas source versions exhausted" }
        val next = Source(KoolCanvasTextureId("${id.value}/cpu-$ownerId-v${++sourceVersionSerial}"), resource)
        val previous = sources.put(id, next)
        revision++
        (previous?.resource as? FrozenCanvasResource.Pixels)?.pixelOwner?.close()
    }

    private fun sameContent(previous: FrozenCanvasResource, next: FrozenCanvasResource): Boolean = when {
        previous === next -> true
        previous is FrozenCanvasResource.Frame && next is FrozenCanvasResource.Frame ->
            previous.frame == next.frame
        previous is FrozenCanvasResource.GpuTarget && next is FrozenCanvasResource.GpuTarget ->
            previous.logicalId == next.logicalId && previous.width == next.width &&
                previous.height == next.height && previous.frame == next.frame
        previous is FrozenCanvasResource.Pixels && next is FrozenCanvasResource.Pixels ->
            previous.image === next.image && previous.isStatic == next.isStatic
        previous is FrozenCanvasResource.Asset && next is FrozenCanvasResource.Asset ->
            // The path alone is not the content: a reloaded asset keeps its path but owns a new byte
            // version, and the asset test requires that reload to be visible.
            previous.path == next.path && previous.encodedBytes.contentEquals(next.encodedBytes)
        else -> false
    }

    /** Source owners can end before frames retire; their leases independently protect the pixels. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        sources.values.forEach { (it.resource as? FrozenCanvasResource.Pixels)?.pixelOwner?.close() }
        sources.clear()
        retainedFrames.clear()
        preallocatedPixelSizes.clear()
        revision++
        freezeScratchPool.close()
        pixelPool.close()
    }

    private fun targetPixelCount(width: Int, height: Int): Int {
        val count = width.toLong() * height
        require(width > 0 && height > 0 && count <= Int.MAX_VALUE / Int.SIZE_BYTES) { "Invalid raster target size" }
        return count.toInt()
    }

    private fun requireCompletePixels(width: Int, height: Int, pixels: IntArray) {
        val count = width.toLong() * height
        require(count <= Int.MAX_VALUE / 4L && pixels.size >= count) { "Incomplete or oversized ARGB image" }
    }

    private companion object {
        val NEXT_OWNER = AtomicLong()
        private val SHARE_IMMUTABLE_COMMANDS = System.getenv("RWX_SHARE_IMMUTABLE_COMMANDS") != "0"

        /**
         * Diagnostic: whole-frame reuse outcomes, process wide.
         *
         * Written to a sidecar file rather than only the canvas trace: an earlier attempt printed through
         * the trace and could not distinguish "no hits" from "the row was never emitted", which cost
         * several rounds. `attempts - hits` is the re-freeze work that the cache failed to avoid.
         */
        val REUSE_ATTEMPTS = AtomicLong()
        val REUSE_HITS = AtomicLong()
        val REUSE_VERSION_BASE = AtomicLong()
        val REUSE_NO_VERSION = AtomicLong()
        val REUSE_NO_FRAME = AtomicLong()
        val REUSE_NO_DEPS = AtomicLong()
        val REUSE_DEP_MISSING = AtomicLong()
        val REUSE_DEP_CHANGED = AtomicLong()
        val REUSE_REPORTS = AtomicLong()
        /** Diagnostic: which dependency ids are missing at check time, most frequent first. */
        val REUSE_MISSING_IDS = java.util.concurrent.ConcurrentHashMap<String, Long>()
        /** Diagnostic: which dependency ids changed version at check time. */
        val REUSE_CHANGED_IDS = java.util.concurrent.ConcurrentHashMap<String, Long>()
        /** Diagnostic: size of the dependency lists being walked - the cache's per-check price. */
        val REUSE_DEPS_MAX = AtomicLong()
        val REUSE_DEPS_SUM = AtomicLong()
        val REUSE_DEPS_CHECKS = AtomicLong()
        val REUSE_TRACE_ENABLED = System.getenv("RWX_REUSE_TRACE") == "1"
        /** Whole-frame reuse is default-on; `RWX_FROZEN_CONTENT_REUSE=0` disables it. */
        val REUSE_ENABLED = (System.getenv("RWX_FROZEN_CONTENT_REUSE") != "0").also {
            println("[RWX canvas] frozenContentReuse=$it")
        }

        fun reuseReport() {
            if (!REUSE_TRACE_ENABLED) return
            val attempts = REUSE_ATTEMPTS.get()
            if (attempts == 0L || attempts % 5000L != 0L) return
            val path = System.getenv("RWX_MAP_CACHE_TRACE") ?: return
            if (path.isEmpty()) return
            runCatching {
                java.io.File(path + ".reuse").appendText(
                    "attempts=" + attempts + " hits=" + REUSE_HITS.get() +
                        " versionBase=" + REUSE_VERSION_BASE.get() +
                        " noVersion=" + REUSE_NO_VERSION.get() +
                        " noFrame=" + REUSE_NO_FRAME.get() +
                        " noDeps=" + REUSE_NO_DEPS.get() +
                        " depMissing=" + REUSE_DEP_MISSING.get() +
                        " depChanged=" + REUSE_DEP_CHANGED.get() +
                        " topMissing=" + REUSE_MISSING_IDS.entries
                            .sortedByDescending { it.value }.take(4)
                            .joinToString(",") { it.key + ":" + it.value } +
                        " topChanged=" + REUSE_CHANGED_IDS.entries
                            .sortedByDescending { it.value }.take(4)
                            .joinToString(",") { it.key + ":" + it.value } +
                        " depsMax=" + REUSE_DEPS_MAX.get() + " depsSum=" + REUSE_DEPS_SUM.get() +
                        " depsChecks=" + REUSE_DEPS_CHECKS.get() + "\n")
            }
        }
        /** Diagnostic: total `put`/`unregister` calls across every store, to expose Source churn. */
        val PUT_CALLS = AtomicLong()
        val UNREGISTER_CALLS = AtomicLong()
        /**
         * Diagnostic: Source identities that have been resolved by a freeze anywhere in the process, plus
         * how many resolved Sources already had `frozenSourceVersionId` set. If reuse never fires, either
         * every Source is new (identities grow one-to-one with resolutions) or the assignment is lost.
         */
        val FROZEN_SOURCE_IDENTITIES = java.util.Collections.synchronizedSet(HashSet<Int>())
        val RESOLVED_WITH_STORED_VERSION = AtomicLong()
        val RESOLVED_TOTAL = AtomicLong()
        val FREEZE_SCRATCH_DIAGNOSTICS = System.getenv("RWX_FRAME_METRICS") != null ||
            System.getenv("RWX_FRAME_TRACE") != null || System.getenv("RWX_ENGINE_FRAME_TRACE") != null
        /**
         * Keep a re-commit that reproduces identical content on the same source version.
         *
         * `RWX_LEGACY_SOURCE_VERSIONING=1` restores the old unconditional `revision++` so the two can be
         * compared on the same build.
         */
        val CONTENT_AWARE_REGISTRATION = System.getenv("RWX_LEGACY_SOURCE_VERSIONING") != "1"
    }
}

/** Render-thread resource owners; fence retirement retains old versions until their last reader. */
internal object FrozenCanvasGpuResources {
    private val owners = mutableMapOf<KoolCanvasTextureId, Int>()
    private val resourceInstaller: (KoolCanvasTextureId, FrozenCanvasResource) -> Unit = ::installResource

    /**
     * Render-thread owner for [FrozenCanvasResource.GpuTarget], installed by the Kool canvas scene
     * host. While it is absent (headless runs, tests, non-Kool backends) a GPU target cannot be
     * materialised, so the recording side must not produce one.
     */
    @Volatile
    internal var gpuTargetInstaller: ((KoolCanvasTextureId, FrozenCanvasResource.GpuTarget) -> Unit)? = null

    /**
     * Drops the render-thread owner of one GPU target content version once no lease references it.
     * Called before [KoolCanvasTextureRegistry.unregister], which still owns the registry keys.
     */
    @Volatile
    internal var gpuTargetReleaser: ((KoolCanvasTextureId) -> Unit)? = null

    fun install(lease: KoolCanvasResourceLease): AutoCloseable = install(lease, resourceInstaller)

    @Synchronized
    internal fun install(
        lease: KoolCanvasResourceLease,
        installer: (KoolCanvasTextureId, FrozenCanvasResource) -> Unit,
    ): AutoCloseable {
        val installedIds = ArrayList<KoolCanvasTextureId>(lease.resourceCount)
        val closed = AtomicBoolean()
        val handle = AutoCloseable {
            if (closed.compareAndSet(false, true)) try {
                synchronized(this) { releaseInstalled(installedIds) }
            } finally {
                lease.close()
            }
        }
        lease.retain() // GPU registration owns CPU pixels independently of the front frame.
        try {
            val resources = lease.resources()
            resources.forEach { (id, resource) ->
                val oldOwners = owners[id] ?: 0
                owners[id] = oldOwners + 1
                installedIds.add(id)
                if (oldOwners == 0) installer(id, resource)
            }
        } catch (failure: Throwable) {
            try {
                releaseInstalled(installedIds)
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            try {
                lease.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        return handle
    }

    private fun installResource(id: KoolCanvasTextureId, resource: FrozenCanvasResource) {
        when (resource) {
            is FrozenCanvasResource.Pixels -> KoolCanvasTextureRegistry.installFrozenImage(id, resource.image, resource.isStatic)
            is FrozenCanvasResource.Asset -> {
                // Decode only the version pinned in this packet. Never reopen a mod path
                // after reload or when a delayed render finally consumes the frame.
                val decoded = KoolGraphicsEngine.readPngImage(resource.encodedBytes)
                    ?: KoolGraphicsEngine.readPlatformImage(resource.encodedBytes, resource.path)
                if (decoded != null) KoolCanvasTextureRegistry.registerArgb(
                    id, decoded.width, decoded.height, decoded.argbPixels)
            }
            is FrozenCanvasResource.Frame -> KoolCanvasTextureRegistry.registerFrame(id, resource.frame)
            is FrozenCanvasResource.GpuTarget -> checkNotNull(gpuTargetInstaller) {
                "GPU target installed without a render-thread owner"
            }.invoke(id, resource)
            FrozenCanvasResource.Missing -> Unit
        }
    }

    private fun releaseInstalled(ids: List<KoolCanvasTextureId>) {
        var failure: Throwable? = null
        ids.forEach { id ->
            try {
                val remaining = checkNotNull(owners[id]) { "Unbalanced GPU resource registration" } - 1
                if (remaining == 0) {
                    owners.remove(id)
                    gpuTargetReleaser?.invoke(id)
                    KoolCanvasTextureRegistry.unregister(id)
                } else owners[id] = remaining
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }
}
