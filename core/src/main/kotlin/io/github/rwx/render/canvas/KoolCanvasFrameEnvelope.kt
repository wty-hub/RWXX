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
    internal fun install(sink: ((() -> Unit) -> Unit)?) { this.sink = sink }
    fun retire(release: () -> Unit) { sink?.invoke(release) ?: release() }
}

internal sealed interface FrozenCanvasResource {
    data class Pixels(
        val image: KoolCanvasArgbImage,
        val isStatic: Boolean,
        val pixelOwner: KoolCanvasPixelPool.PixelOwner? = null,
    ) : FrozenCanvasResource
    data class Asset(val path: String, val encodedBytes: ByteArray) : FrozenCanvasResource
    data class Frame(val frame: KoolCanvasFrame) : FrozenCanvasResource
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
) : KoolCanvasTextureStore, KoolCanvasFrameSnapshotRetainer,
    KoolCanvasTextureRevisionStore, KoolCanvasFrameTextureRevisionStore, AutoCloseable {
    constructor() : this(KoolCanvasPixelPool(), System.getenv("RWX_CANVAS_PIXEL_POOL") != "0")

    private data class Source(val versionId: KoolCanvasTextureId, val resource: FrozenCanvasResource) {
        var frozenFrame: KoolCanvasFrame? = null
        var frozenVersionId: KoolCanvasTextureId? = null
    }
    private val sources = mutableMapOf<KoolCanvasTextureId, Source>()
    private val retainedFrames = mutableMapOf<KoolCanvasTextureId, Int>()
    private val ownerId = NEXT_OWNER.incrementAndGet()
    private val freezeScratchPool = KoolCanvasFreezeScratchPool(freezeScratchPoolEnabled)
    private val freezeScratchMetrics = if (FREEZE_SCRATCH_DIAGNOSTICS)
        CanvasFrameMetrics.FreezeScratchRecord(ownerId, freezeScratchPool) else null
    private var revision = 0
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

    @Synchronized
    override fun retainFrameSnapshot(id: KoolCanvasTextureId) {
        val resource = sources[id]?.resource
        if (resource is FrozenCanvasResource.Frame || resource is FrozenCanvasResource.Pixels) {
            retainedFrames[id] = (retainedFrames[id] ?: 0) + 1
        }
    }

    @Synchronized
    override fun unregister(id: KoolCanvasTextureId) {
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
        try {
            val resources = linkedMapOf<KoolCanvasTextureId, FrozenCanvasResource>()
            val visiting = scratch?.visiting ?: mutableSetOf<KoolCanvasTextureId>()
            val resolvedIds = scratch?.resolvedIds ?: mutableMapOf<KoolCanvasTextureId, KoolCanvasTextureId>()
            val frozenReferences = scratch?.frozenReferences ?: IdentityHashMap<KoolCanvasTextureRef, KoolCanvasTextureRef>()
            lateinit var freeze: (KoolCanvasFrame) -> KoolCanvasFrame
            fun texture(ref: KoolCanvasTextureRef): KoolCanvasTextureRef {
                frozenReferences[ref]?.let { return it }
                val source = sources[ref.id]
                val resource = source?.resource
                val id = source?.versionId ?: KoolCanvasTextureId("${ref.id.value}/cpu-$ownerId-missing")
                val pixelIdentity = (resource as? FrozenCanvasResource.Pixels)?.let {
                    KoolCanvasFrozenPixelIdentity(ownerId, ref.id)
                }
                resolvedIds[id]?.let { resolvedId ->
                    return ref.copy(id = resolvedId, frozenPixelIdentity = pixelIdentity).also {
                        if (resource != null && resource !== FrozenCanvasResource.Missing) frozenReferences[ref] = it
                    }
                }
                if (id in visiting) {
                    val cycleId = KoolCanvasTextureId("${id.value}/cycle")
                    resources[cycleId] = FrozenCanvasResource.Missing
                    return ref.copy(id = cycleId, frozenPixelIdentity = null)
                }
                visiting += id
                val resolvedId = if (resource is FrozenCanvasResource.Frame) {
                    val frozen = freeze(resource.frame)
                    if (source.frozenFrame != frozen) {
                        source.frozenFrame = frozen
                        source.frozenVersionId = KoolCanvasTextureId("${id.value}/frozen-${++frozenFrameSerial}")
                    }
                    checkNotNull(source.frozenVersionId).also { resources[it] = FrozenCanvasResource.Frame(frozen) }
                } else {
                    resources[id] = resource ?: FrozenCanvasResource.Missing
                    id
                }
                visiting -= id
                resolvedIds[id] = resolvedId
                return ref.copy(id = resolvedId, frozenPixelIdentity = pixelIdentity).also {
                    if (resource != null && resource !== FrozenCanvasResource.Missing) frozenReferences[ref] = it
                }
            }
            fun paint(paint: KoolCanvasPaint): KoolCanvasPaint {
                val displacement = paint.textureEffect as? KoolCanvasTextureEffect.Displacement ?: return paint
                return paint.copy(textureEffect = displacement.copy(screenBase = texture(displacement.screenBase)))
            }
            freeze = { source ->
                source.copy(commands = Collections.unmodifiableList(source.commands.map { command ->
                    when (command) {
                        is KoolCanvasCommand.DrawTexture -> command.copy(texture = texture(command.texture), paint = paint(command.paint))
                        is KoolCanvasCommand.DrawRect -> command.copy(paint = paint(command.paint))
                        is KoolCanvasCommand.DrawLine -> command.copy(paint = paint(command.paint))
                        is KoolCanvasCommand.DrawCircle -> command.copy(paint = paint(command.paint))
                        is KoolCanvasCommand.DrawText -> command.copy(paint = paint(command.paint))
                        is KoolCanvasCommand.Clear -> command
                    }
                }))
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
        } finally {
            freezeScratchPool.release(scratch)
            freezeScratchMetrics?.let(CanvasFrameMetrics::freezeScratchPool)
        }
    }

    @Synchronized
    private fun put(id: KoolCanvasTextureId, resource: FrozenCanvasResource) {
        check(!closed) { "CPU texture store is closed" }
        val next = Source(KoolCanvasTextureId("${id.value}/cpu-$ownerId-version-${revision + 1}"), resource)
        val previous = sources.put(id, next)
        revision++
        (previous?.resource as? FrozenCanvasResource.Pixels)?.pixelOwner?.close()
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
        val FREEZE_SCRATCH_DIAGNOSTICS = System.getenv("RWX_FRAME_METRICS") != null ||
            System.getenv("RWX_FRAME_TRACE") != null || System.getenv("RWX_ENGINE_FRAME_TRACE") != null
    }
}

/** Render-thread resource owners; fence retirement retains old versions until their last reader. */
internal object FrozenCanvasGpuResources {
    private val owners = mutableMapOf<KoolCanvasTextureId, Int>()
    private val resourceInstaller: (KoolCanvasTextureId, FrozenCanvasResource) -> Unit = ::installResource

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
                    KoolCanvasTextureRegistry.unregister(id)
                } else owners[id] = remaining
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }
}
