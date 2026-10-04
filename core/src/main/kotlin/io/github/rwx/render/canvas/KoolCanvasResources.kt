package io.github.rwx.render.canvas

import de.fabmax.kool.Assets
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.util.Uint8Buffer
import de.fabmax.kool.util.useRaw
import io.github.rwx.logger
import java.nio.ByteOrder
import java.lang.management.ManagementFactory
import java.util.IdentityHashMap

private const val COMPLETE_SLICK_FRAME_TEXTURE_ID = "slick-complete-frame"
private const val CPU_TEXTURE_PROFILE_INTERVAL = 120
private val argbPackCpuClock = if (CanvasRenderStageTrace.enabled) ManagementFactory.getThreadMXBean().takeIf {
    it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled
} else null

@JvmInline
value class KoolCanvasTextureId(val value: String)

@JvmInline
value class KoolCanvasRenderTargetId(val value: String)

fun interface KoolCanvasTextureResolver {
    fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter): Texture2d
}

interface KoolCanvasTextureStore : KoolCanvasTextureResolver {
    fun register(id: KoolCanvasTextureId, texture: Texture2d)

    /**
     * Registers CPU ARGB pixels for [id].
     *
     * @param alphaBleed expand each opaque texel's RGB into neighbouring transparent texels so that
     *   linear filtering cannot bleed an arbitrary transparent RGB into sprite edges. Decoded
     *   images need it; backend-generated pixels (layer buffers, fog overlays, render targets) do
     *   not, and the per-update pass is O(width * height), so callers must opt in deliberately.
     *   When it is `false` the store takes ownership of [argbPixels] instead of copying it.
     */
    fun registerArgb(
        id: KoolCanvasTextureId,
        width: Int,
        height: Int,
        argbPixels: IntArray,
        alphaBleed: Boolean = true,
    )

    fun registerOpaqueArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray)
    fun registerPremultipliedArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray) {
        // Keep the historical behaviour of the fallback: bleed copies the pixels, so the caller
        // keeps ownership of [argbPixels].
        registerArgb(id, width, height, argbPixels)
    }

    fun registerAsset(id: KoolCanvasTextureId, assetPath: String)
    /** Legacy stores may load by path; detached frame stores must own these encoded bytes. */
    fun registerAssetSnapshot(id: KoolCanvasTextureId, assetPath: String, encodedBytes: ByteArray?) =
        registerAsset(id, assetPath)
    fun registerFrame(id: KoolCanvasTextureId, frame: KoolCanvasFrame)
    fun unregister(id: KoolCanvasTextureId)
    fun frame(id: KoolCanvasTextureId): KoolCanvasFrame?
    fun argbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? = null
    fun argbImageView(id: KoolCanvasTextureId): KoolCanvasArgbImage? = argbImage(id)
    /** Only decoded, immutable sprite pixels qualify for the static renderer atlas. */
    fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? = null
}

interface KoolCanvasRetiredTextureReleaser {
    fun releaseRetiredTextures()
}

interface KoolCanvasContextResourceInvalidator {
    fun invalidateContextResources()
}

interface KoolCanvasFrameTextureRevisionStore {
    val frameTextureRevision: Int
}

interface KoolCanvasTextureRevisionStore {
    val textureRevision: Int
}

interface KoolCanvasFrameSnapshotRetainer {
    fun retainFrameSnapshot(id: KoolCanvasTextureId)
}

fun interface KoolCanvasOpaquePixelPacker {
    fun pack(destination: Uint8Buffer, sourceArgb: IntArray, pixelCount: Int)
}

object KoolCanvasTextureRegistry :
    KoolCanvasTextureStore,
    KoolCanvasRetiredTextureReleaser,
    KoolCanvasContextResourceInvalidator,
    KoolCanvasFrameTextureRevisionStore,
    KoolCanvasTextureRevisionStore,
    KoolCanvasFrameSnapshotRetainer {
    private val registeredTextures = mutableMapOf<KoolCanvasTextureId, Texture2d>()
    private val registeredArgbImages = mutableMapOf<KoolCanvasTextureId, KoolCanvasArgbImage>()
    private val staticArgbIds = mutableSetOf<KoolCanvasTextureId>()
    private val registeredAssets = mutableMapOf<KoolCanvasTextureId, String>()
    private val registeredFrames = mutableMapOf<KoolCanvasTextureId, KoolCanvasFrame>()
    private val argbTextures = mutableMapOf<Pair<KoolCanvasTextureId, KoolCanvasTextureFilter>, Texture2d>()
    private val argbTextureUploadSlots = mutableMapOf<Pair<KoolCanvasTextureId, KoolCanvasTextureFilter>, Int>()
    private class ArgbUploadBuffers(val contextGeneration: Long) : AutoCloseable {
        val slots = arrayOfNulls<KoolCanvasUploadBufferPool.BufferOwner>(2)
        val displaced = ArrayList<KoolCanvasUploadBufferPool.BufferOwner>()

        override fun close() {
            slots.forEach { it?.close() }
            slots.fill(null)
            displaced.forEach { it.close() }
            displaced.clear()
        }

        fun discard() {
            slots.forEach { it?.discard() }
            slots.fill(null)
            displaced.forEach { it.discard() }
            displaced.clear()
        }
    }
    private val uploadBufferPool = KoolCanvasUploadBufferPool()
    private var contextGeneration = 0L
    private var nativeBgraUploads = false
    val nativeBgraUploadsEnabled: Boolean
        @Synchronized get() = nativeBgraUploads

    /** Called by the actual backend at context creation/destruction, never during a live frame. */
    @Synchronized
    fun configureNativeBgraUploads(enabled: Boolean) {
        if (nativeBgraUploads == enabled) return
        // Layout is part of the GPU payload identity. Retain CPU sources, discard old-context slots.
        invalidateContextResources()
        nativeBgraUploads = enabled
    }
    private val opaqueUploadBuffers = mutableMapOf<Pair<KoolCanvasTextureId, KoolCanvasTextureFilter>, ArgbUploadBuffers>()
    private val argbUploadOwners = IdentityHashMap<Texture2d, ArgbUploadBuffers>()
    private val completeFrameArgbBuffers = arrayOfNulls<IntArray>(2)
    private var completeFrameArgbSlot = -1
    private val argbTextureOwnerSerials = mutableMapOf<Pair<KoolCanvasTextureId, KoolCanvasTextureFilter>, Long>()
    private val assetTextures = mutableMapOf<Pair<KoolCanvasTextureId, KoolCanvasTextureFilter>, Texture2d>()
    private val placeholderTextures = mutableMapOf<KoolCanvasTextureFilter, Texture2d>()
    private val retiringTextures = mutableListOf<Texture2d>()
    private val releasableRetiredTextures = mutableListOf<Texture2d>()
    private val retainedFrameSnapshots = mutableMapOf<KoolCanvasTextureId, Int>()
    private var frameTextureRevisionValue = 0
    private var nextArgbTextureOwnerSerial = 0L
    private var nextAssetTextureOwnerSerial = 0L
    private var opaqueCpuSampleCount = 0
    private var opaqueCpuTotalNanos = 0L
    private var opaqueCpuPeakNanos = 0L
    private var opaqueCpuWidth = 0
    private var opaqueCpuHeight = 0
    @Volatile
    private var opaquePixelPacker: KoolCanvasOpaquePixelPacker? = null

    fun setCompleteFramePixelPacker(packer: KoolCanvasOpaquePixelPacker?) {
        opaquePixelPacker = packer
    }

    /** A read-only snapshot for opt-in shutdown diagnostics, without touching render-owned caches. */
    fun uploadBufferPoolDiagnostics(): String {
        val stats = uploadBufferPool.stats()
        return "enabled=${stats.enabled} idleCapacityBytes=${stats.idleCapacityBytes} " +
            "maxBuffersPerSize=${stats.maxBuffersPerSize} retainedBytes=${stats.retainedBytes} " +
            "retainedBuffers=${stats.retainedBuffers} activeBuffers=${stats.activeBuffers} " +
            "allocations=${stats.allocations} misses=${stats.misses} reusedBuffers=${stats.reusedBuffers} " +
            "preallocatedBuffers=${stats.preallocatedBuffers}"
    }

    internal fun uploadBufferPoolStats() = uploadBufferPool.stats()

    /** Trim idle native storage without reading or releasing any render-owned texture. */
    fun clearIdleUploadBufferPool() { uploadBufferPool.clear() }

    override val frameTextureRevision: Int
        @Synchronized get() = frameTextureRevisionValue

    override val textureRevision: Int
        @Synchronized get() = frameTextureRevisionValue

    @Synchronized
    override fun register(id: KoolCanvasTextureId, texture: Texture2d) {
        registeredTextures.put(id, texture)?.takeIf { it !== texture }?.retireTexture()
        registeredArgbImages.remove(id)
        staticArgbIds -= id
        registeredAssets.remove(id)
        registeredFrames.remove(id)
        releaseCachedTextures(id)
        frameTextureRevisionValue++
    }

    @Synchronized
    override fun registerArgb(
        id: KoolCanvasTextureId,
        width: Int,
        height: Int,
        argbPixels: IntArray,
        alphaBleed: Boolean,
    ) {
        if (width <= 0 || height <= 0) {
            unregister(id)
            return
        }
        val uploadPixels = if (alphaBleed) bleedTransparentRgb(width, height, argbPixels) else argbPixels
        registerArgbImage(id, KoolCanvasArgbImage(width, height, uploadPixels, premultipliedAlpha = false))
        if (alphaBleed) staticArgbIds += id else staticArgbIds -= id
    }

    /**
     * Registers a complete game frame. The source may be reused after this call returns; the
     * registry owns one copied pixel array until the next registration. Alpha is forced opaque
     * so readback alpha values cannot make the final composite transparent.
     */
    @Synchronized
    override fun registerOpaqueArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray) {
        if (width <= 0 || height <= 0) {
            unregister(id)
            return
        }
        val pixelCount = width.toLong() * height
        require(pixelCount <= Int.MAX_VALUE / 4L && argbPixels.size >= pixelCount) {
            "Opaque ARGB frame must fit its RGBA upload buffer and contain $pixelCount pixels"
        }
        val profileCpu = id.value == COMPLETE_SLICK_FRAME_TEXTURE_ID
        val startedAt = if (profileCpu) System.nanoTime() else 0L
        val nextSlot = if (profileCpu) (completeFrameArgbSlot + 1) % completeFrameArgbBuffers.size else -1
        val opaquePixels = if (profileCpu) {
            completeFrameArgbBuffers[nextSlot]?.takeIf { it.size == pixelCount.toInt() }
                ?: IntArray(pixelCount.toInt()).also { completeFrameArgbBuffers[nextSlot] = it }
        } else {
            IntArray(pixelCount.toInt())
        }
        for (index in opaquePixels.indices) {
            opaquePixels[index] = argbPixels[index] or 0xff000000.toInt()
        }
        registerArgbImage(id, KoolCanvasArgbImage(width, height, opaquePixels, premultipliedAlpha = false))
        if (profileCpu) completeFrameArgbSlot = nextSlot
        if (profileCpu) recordOpaqueCpuPreparation(System.nanoTime() - startedAt, width, height)
    }

    @Synchronized
    override fun registerPremultipliedArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray) {
        if (width <= 0 || height <= 0) {
            unregister(id)
            return
        }
        val image = KoolCanvasArgbImage(
            width = width,
            height = height,
            pixels = sanitizePremultipliedRgb(width, height, argbPixels),
            premultipliedAlpha = true,
        )
        registerArgbImage(id, image)
    }

    private fun registerArgbImage(id: KoolCanvasTextureId, image: KoolCanvasArgbImage) {
        staticArgbIds -= id
        registeredArgbImages[id] = image
        registeredTextures.remove(id)?.retireTexture()
        registeredAssets.remove(id)
        registeredFrames.remove(id)
        releaseCachedAssetTextures(id)
        refreshCachedArgbTextures(id, image)
        frameTextureRevisionValue++
    }

    private fun recordOpaqueCpuPreparation(elapsedNanos: Long, width: Int, height: Int) {
        if (width != opaqueCpuWidth || height != opaqueCpuHeight) {
            opaqueCpuSampleCount = 0
            opaqueCpuTotalNanos = 0L
            opaqueCpuPeakNanos = 0L
            opaqueCpuWidth = width
            opaqueCpuHeight = height
        }
        opaqueCpuSampleCount++
        opaqueCpuTotalNanos += elapsedNanos
        opaqueCpuPeakNanos = maxOf(opaqueCpuPeakNanos, elapsedNanos)
        if (opaqueCpuSampleCount == CPU_TEXTURE_PROFILE_INTERVAL) {
            val averageMicros = opaqueCpuTotalNanos / opaqueCpuSampleCount / 1_000L
            val peakMicros = opaqueCpuPeakNanos / 1_000L
            logger.info("SlickCapture") {
                "Complete-frame CPU texture preparation/queue ${width}x$height: " +
                    "avg=${averageMicros}us peak=${peakMicros}us over $opaqueCpuSampleCount frames"
            }
            opaqueCpuSampleCount = 0
            opaqueCpuTotalNanos = 0L
            opaqueCpuPeakNanos = 0L
        }
    }

    @Synchronized
    override fun registerAsset(id: KoolCanvasTextureId, assetPath: String) {
        val normalizedPath = assetPath.removePrefix("assets/").replace('\\', '/')
        if (registeredAssets[id] != normalizedPath) {
            registeredAssets[id] = normalizedPath
            registeredTextures.remove(id)?.retireTexture()
            registeredArgbImages.remove(id)
            staticArgbIds -= id
            registeredFrames.remove(id)
            releaseCachedTextures(id)
            frameTextureRevisionValue++
        }
    }

    @Synchronized
    override fun registerFrame(id: KoolCanvasTextureId, frame: KoolCanvasFrame) {
        registeredFrames[id] = frame
        registeredTextures.remove(id)?.retireTexture()
        registeredArgbImages.remove(id)
        staticArgbIds -= id
        registeredAssets.remove(id)
        releaseCachedTextures(id)
        frameTextureRevisionValue++
    }

    @Synchronized
    override fun unregister(id: KoolCanvasTextureId) {
        val retainedCount = retainedFrameSnapshots[id]
        if (retainedCount != null) {
            if (retainedCount <= 1) {
                retainedFrameSnapshots.remove(id)
            } else {
                retainedFrameSnapshots[id] = retainedCount - 1
            }
            return
        }
        registeredTextures.remove(id)?.retireTexture()
        if (id.value == COMPLETE_SLICK_FRAME_TEXTURE_ID) {
            completeFrameArgbBuffers.fill(null)
            completeFrameArgbSlot = -1
        }
        registeredArgbImages.remove(id)
        staticArgbIds -= id
        registeredAssets.remove(id)
        registeredFrames.remove(id)
        releaseCachedTextures(id)
        frameTextureRevisionValue++
    }

    @Synchronized
    override fun retainFrameSnapshot(id: KoolCanvasTextureId) {
        if (id in registeredFrames) {
            retainedFrameSnapshots[id] = (retainedFrameSnapshots[id] ?: 0) + 1
        }
    }

    @Synchronized
    override fun releaseRetiredTextures() {
        releasableRetiredTextures.forEach { it.release() }
        releasableRetiredTextures.clear()
        releasableRetiredTextures += retiringTextures
        retiringTextures.clear()
    }

    /**
     * Drops Texture2d instances owned by the current Kool context while retaining their logical
     * sources. The context owns the old GPU handles; scheduling delayed texture releases here can
     * otherwise run after a replacement EGL context has already been created.
     */
    @Synchronized
    override fun invalidateContextResources() {
        // The old context owns its textures. Drop upload owners without recycling any storage
        // which that context could still read; a late retirement callback cannot refill this pool.
        contextGeneration++
        uploadBufferPool.clear()
        opaqueUploadBuffers.values.forEach { it.discard() }
        argbUploadOwners.clear()
        registeredTextures.clear()
        argbTextures.clear()
        argbTextureUploadSlots.clear()
        opaqueUploadBuffers.clear()
        argbTextureOwnerSerials.clear()
        assetTextures.clear()
        placeholderTextures.clear()
        retiringTextures.clear()
        releasableRetiredTextures.clear()
        frameTextureRevisionValue++
    }

    @Synchronized
    override fun frame(id: KoolCanvasTextureId): KoolCanvasFrame? = registeredFrames[id]

    @Synchronized
    override fun argbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? =
        registeredArgbImages[id]?.copyPixels()

    @Synchronized
    override fun argbImageView(id: KoolCanvasTextureId): KoolCanvasArgbImage? =
        registeredArgbImages[id]

    @Synchronized
    override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? =
        registeredArgbImages[id]?.takeIf { id in staticArgbIds }

    /** Installs an already detached immutable image on the render thread without bleeding twice. */
    @Synchronized
    internal fun installFrozenImage(id: KoolCanvasTextureId, image: KoolCanvasArgbImage, isStatic: Boolean) {
        if (registeredArgbImages[id] === image) return
        registerArgbImage(id, image)
        if (isStatic) staticArgbIds += id
    }

    @Synchronized
    override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter): Texture2d =
        registeredTextures[texture.id]
            ?: registeredArgbImages[texture.id]?.let { argbTexture(texture.id, it, filter) }
            ?: registeredAssets[texture.id]?.let { assetTexture(texture.id, it, filter) }
            ?: placeholderTexture(filter)

    private fun argbTexture(
        id: KoolCanvasTextureId,
        image: KoolCanvasArgbImage,
        filter: KoolCanvasTextureFilter,
    ): Texture2d {
        val key = id to filter
        return argbTextures.getOrPut(key) {
            val ownerSerial = ++nextArgbTextureOwnerSerial
            argbTextureUploadSlots[key] = 0
            argbTextureOwnerSerials[key] = ownerSerial
            Texture2d(
                data = argbImageData(
                    id, filter, ownerSerial, 0, image.width, image.height, image.pixels,
                    reusableOpaqueUploadBuffer(key, 0, image.width, image.height),
                ),
                mipMapping = MipMapping.Off,
                samplerSettings = samplerSettings(filter),
                name = "rwx-canvas-argb-${id.value}-$filter-$ownerSerial",
            ).also { texture -> argbUploadOwners[texture] = checkNotNull(opaqueUploadBuffers[key]) }
        }
    }

    private fun assetTexture(
        id: KoolCanvasTextureId,
        assetPath: String,
        filter: KoolCanvasTextureFilter,
    ): Texture2d {
        val key = id to filter
        return assetTextures.getOrPut(key) {
            val ownerSerial = ++nextAssetTextureOwnerSerial
            val ownerName = "rwx-canvas-asset-${id.value}-$filter-$ownerSerial"
            Texture2d(
                format = TexFormat.RGBA,
                mipMapping = MipMapping.Off,
                samplerSettings = samplerSettings(filter),
                name = ownerName,
            ) {
                val image = Assets.defaultLoader.loadBufferedImage2d(assetPath, TexFormat.RGBA)
                    .getOrElse { placeholderImageData("$ownerName-missing") }
                BufferedImageData2d(
                    data = image.data,
                    width = image.width,
                    height = image.height,
                    format = image.format,
                    // The GL loader owns textures by ImageData.id. Asset pixels are content-keyed
                    // by default, but registry Texture2d owners have independent lifetimes.
                    id = ownerName,
                )
            }
        }
    }

    private fun placeholderTexture(filter: KoolCanvasTextureFilter): Texture2d =
        placeholderTextures.getOrPut(filter) {
            Texture2d(
                data = placeholderImageData("rwx-canvas-placeholder-$filter"),
                mipMapping = MipMapping.Off,
                samplerSettings = samplerSettings(filter),
                name = "rwx-canvas-placeholder-$filter",
            )
        }

    private fun releaseCachedTextures(id: KoolCanvasTextureId) {
        releaseCachedArgbTextures(id)
        releaseCachedAssetTextures(id)
    }

    private fun releaseCachedArgbTextures(id: KoolCanvasTextureId) {
        val retiring = ArrayList<Pair<Texture2d, ArgbUploadBuffers?>>()
        val iterator = argbTextures.iterator()
        while (iterator.hasNext()) {
            val (key, texture) = iterator.next()
            if (key.first == id) {
                val buffers = argbUploadOwners.remove(texture) ?: opaqueUploadBuffers[key]
                opaqueUploadBuffers.remove(key)
                retiring.add(texture to buffers)
                iterator.remove()
            }
        }
        argbTextureUploadSlots.keys.removeAll { it.first == id }
        // A failed data-only Texture2d construction may leave slots without a texture reader.
        val orphaned = opaqueUploadBuffers.iterator()
        while (orphaned.hasNext()) {
            val entry = orphaned.next()
            if (entry.key.first == id) {
                if (KoolCanvasGpuRetirement.hasRetirementSink) entry.value.close() else entry.value.discard()
                orphaned.remove()
            }
        }
        argbTextureOwnerSerials.keys.removeAll { it.first == id }
        // A completed Vulkan fence can synchronously drain other texture retirements here.
        // Detach all old entries and metadata first; callbacks may even register this id anew.
        retiring.forEach { (texture, buffers) -> retireArgbTexture(texture, buffers) }
    }

    private fun retireArgbTexture(texture: Texture2d, buffers: ArgbUploadBuffers?) {
        if (!KoolCanvasGpuRetirement.hasRetirementSink) {
            // Keep the original scene-frame retirement policy on backends without a completion
            // fence. A loader may already hold uploadData locally, so its raw memory is never lent.
            texture.retireTexture()
            buffers?.discard()
            return
        }
        val generation = buffers?.contextGeneration ?: contextGeneration
        KoolCanvasGpuRetirement.retire {
            synchronized(this) {
                try {
                    if (generation == contextGeneration) {
                        // Data-only textures have no async provider which can repopulate uploadData.
                        // Clearing a still-pending payload prevents any later uploader from reading
                        // host memory after it has been returned to the pool.
                        texture.uploadData = null
                        if (!texture.isReleased) texture.release()
                    }
                } finally {
                    buffers?.close()
                }
            }
        }
    }

    private fun releaseCachedAssetTextures(id: KoolCanvasTextureId) {
        releaseCachedTextures(assetTextures, id)
    }

    private fun releaseCachedTextures(
        textures: MutableMap<Pair<KoolCanvasTextureId, KoolCanvasTextureFilter>, Texture2d>,
        id: KoolCanvasTextureId,
    ) {
        val iterator = textures.iterator()
        while (iterator.hasNext()) {
            val (key, texture) = iterator.next()
            if (key.first == id) {
                texture.retireTexture()
                iterator.remove()
            }
        }
    }

    private fun refreshCachedArgbTextures(id: KoolCanvasTextureId, image: KoolCanvasArgbImage) {
        val updating = argbTextures.entries.filter { it.key.first == id }.map { it.key to it.value }
        updating.forEach { (key, texture) ->
            if (argbTextures[key] === texture && !texture.isReleased && registeredArgbImages[id] === image) {
                val currentSlot = argbTextureUploadSlots[key] ?: 0
                val uploadSlot = if (texture.uploadData == null) 1 - currentSlot else currentSlot
                val ownerSerial = checkNotNull(argbTextureOwnerSerials[key])
                argbTextureUploadSlots[key] = uploadSlot
                texture.uploadLazy(
                    argbImageData(
                        id, key.second, ownerSerial, uploadSlot, image.width, image.height, image.pixels,
                        reusableOpaqueUploadBuffer(key, uploadSlot, image.width, image.height),
                    )
                )
                // Resizing a slot can displace a buffer referenced by the previous uploadData.
                // Only after replacing that payload may those old slots begin fence retirement.
                retireDisplacedUploadBuffers(checkNotNull(opaqueUploadBuffers[key]))
            }
        }
    }

    private fun retireDisplacedUploadBuffers(buffers: ArgbUploadBuffers) {
        if (buffers.displaced.isEmpty()) return
        val displaced = buffers.displaced.toList()
        buffers.displaced.clear()
        if (!KoolCanvasGpuRetirement.hasRetirementSink) {
            displaced.forEach { it.discard() }
            return
        }
        try {
            KoolCanvasGpuRetirement.retire { displaced.forEach { it.close() } }
        } catch (failure: Throwable) {
            // Rejection cannot make these buffers reusable. Preserve them alongside any slots
            // displaced by a reentrant resize; a later texture retirement may close them safely.
            buffers.displaced.addAll(displaced)
            throw failure
        }
    }

    private fun Texture2d.retireTexture() {
        if (!isReleased && this !in retiringTextures && this !in releasableRetiredTextures) {
            retiringTextures += this
        }
    }

    private fun samplerSettings(filter: KoolCanvasTextureFilter): SamplerSettings =
        when (filter) {
            KoolCanvasTextureFilter.Nearest -> SamplerSettings().clamped().nearest().noAnisotropy()
            KoolCanvasTextureFilter.Linear -> SamplerSettings().clamped().linear().noAnisotropy()
        }

    private fun placeholderImageData(id: String): BufferedImageData2d {
        val pixels = Uint8Buffer(16)
        val colors = intArrayOf(
            0xffff00ff.toInt(),
            0xff202020.toInt(),
            0xff202020.toInt(),
            0xffff00ff.toInt(),
        )
        colors.forEachIndexed { index, argb ->
            val offset = index * 4
            pixels[offset] = ((argb ushr 16) and 0xff).toUByte()
            pixels[offset + 1] = ((argb ushr 8) and 0xff).toUByte()
            pixels[offset + 2] = (argb and 0xff).toUByte()
            pixels[offset + 3] = ((argb ushr 24) and 0xff).toUByte()
        }
        return BufferedImageData2d(
            data = pixels,
            width = 2,
            height = 2,
            format = TexFormat.RGBA,
            id = id,
        )
    }

    private fun argbImageData(
        id: KoolCanvasTextureId,
        filter: KoolCanvasTextureFilter,
        ownerSerial: Long,
        uploadSlot: Int,
        width: Int,
        height: Int,
        argbPixels: IntArray,
        reusableBuffer: Uint8Buffer? = null,
    ): ImageData2d {
        val packStart = CanvasRenderStageTrace.start()
        val packCpuStart = argbPackCpuClock?.currentThreadCpuTime ?: -1L
        val pixelCount = width * height
        val pixels = reusableBuffer ?: Uint8Buffer(pixelCount * 4)
        val bgra = nativeBgraUploads && id.value != COMPLETE_SLICK_FRAME_TEXTURE_ID
        val packer = opaquePixelPacker?.takeIf { id.value == COMPLETE_SLICK_FRAME_TEXTURE_ID }
        if (bgra) {
            packArgbPixelsToBgra(pixels, argbPixels, pixelCount)
        } else if (packer != null) {
            packer.pack(pixels, argbPixels, pixelCount)
        } else {
            packArgbPixelsToRgba(pixels, argbPixels, pixelCount)
        }
        CanvasRenderStageTrace.record("argb-pack", packStart, pixelCount.toLong() * 4, width.toLong(), height.toLong())
        if (packCpuStart >= 0L) CanvasRenderStageTrace.record("argb-pack-work", packStart, pixelCount.toLong(),
            argbPackCpuClock!!.currentThreadCpuTime - packCpuStart, if (bgra) 2 else 0)
        if (bgra) return KoolCanvasBgraImageData(pixels, width, height,
            "rwx-canvas-bgra-${id.value}-$filter-$ownerSerial-$uploadSlot")
        return BufferedImageData2d(
            data = pixels,
            width = width,
            height = height,
            format = TexFormat.RGBA,
            // Kool's GL loader caches GPU textures by ImageData.id. Each Texture2d owner and
            // filter needs its own key; alternating slots then bound updates for that owner.
            id = "rwx-canvas-argb-${id.value}-$filter-$ownerSerial-$uploadSlot",
        )
    }

    /**
     * The upload buffer for [key]'s [slot], reused across frames.
     *
     * Kool's backend consumes the buffer while uploading, so [refreshCachedArgbTextures] alternates
     * between two slots; reusing them keeps a few hundred kilobytes per texture out of the garbage
     * collector on every frame.
     */
    private fun reusableOpaqueUploadBuffer(
        key: Pair<KoolCanvasTextureId, KoolCanvasTextureFilter>,
        slot: Int,
        width: Int,
        height: Int,
    ): Uint8Buffer {
        val byteCount = Math.toIntExact(Math.multiplyExact(width.toLong() * height, 4L))
        require(byteCount > 0) { "Invalid RGBA upload size" }
        val buffers = opaqueUploadBuffers.getOrPut(key) { ArgbUploadBuffers(contextGeneration) }
        buffers.slots[slot]?.takeIf { it.buffer.capacity == byteCount }?.let { return it.buffer }
        val next = uploadBufferPool.borrow(byteCount)
        buffers.slots[slot]?.let { buffers.displaced.add(it) }
        buffers.slots[slot] = next
        return next.buffer
    }

    internal fun bleedTransparentRgb(width: Int, height: Int, argbPixels: IntArray): IntArray {
        val pixelCount = width * height
        val result = argbPixels.copyOf(pixelCount)
        val hasBleedRgb = BooleanArray(pixelCount)
        val queue = IntArray(pixelCount)
        var readIndex = 0
        var writeIndex = 0

        for (index in 0 until pixelCount) {
            val argb = result[index]
            if ((argb ushr 24) != 0) {
                hasBleedRgb[index] = true
                queue[writeIndex++] = index
            }
        }

        while (readIndex < writeIndex) {
            val index = queue[readIndex++]
            val x = index % width
            val y = index / width
            val rgb = result[index] and 0x00ffffff

            for (dy in -1..1) {
                val sampleY = y + dy
                if (sampleY !in 0 until height) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val sampleX = x + dx
                    if (sampleX !in 0 until width) continue

                    val sampleIndex = sampleX + (sampleY * width)
                    if (hasBleedRgb[sampleIndex] || (result[sampleIndex] ushr 24) != 0) {
                        continue
                    }

                    result[sampleIndex] = rgb
                    hasBleedRgb[sampleIndex] = true
                    queue[writeIndex++] = sampleIndex
                }
            }
        }
        return result
    }

    internal fun sanitizePremultipliedRgb(width: Int, height: Int, argbPixels: IntArray): IntArray {
        val pixelCount = width * height
        val result = argbPixels.copyOf(pixelCount)
        for (index in 0 until pixelCount) {
            if ((result[index] ushr 24) == 0) {
                result[index] = 0
            }
        }
        return result
    }
}

/**
 * Packs ARGB ints into the RGBA bytes Kool uploads.
 *
 * Writing one R/B-swapped int per pixel through a big-endian int view is much cheaper than the
 * four `Uint8Buffer` byte writes (each preceded by a bounds-checked `getOrElse`) this used to do,
 * and backend-generated render targets like the layer buffer cells re-run this pass every frame.
 */
internal fun packArgbPixelsToRgba(destination: Uint8Buffer, sourceArgb: IntArray, pixelCount: Int) {
    destination.useRaw { bytes ->
        val rgba = bytes.order(ByteOrder.BIG_ENDIAN).asIntBuffer()
        val available = minOf(pixelCount, sourceArgb.size)
        var index = 0
        while (index < available) {
            rgba.put(index, Integer.rotateLeft(sourceArgb[index], 8))
            index++
        }
        // Pixels the caller did not provide stay transparent, matching the previous behaviour of
        // reading a missing source pixel as 0.
        while (index < pixelCount) {
            rgba.put(index, 0)
            index++
        }
    }
}

/** ARGB words copied in little-endian order already have the BGRA layout Vulkan can sample. */
internal fun packArgbPixelsToBgra(destination: Uint8Buffer, sourceArgb: IntArray, pixelCount: Int) {
    destination.useRaw { bytes ->
        val bgra = bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val available = minOf(pixelCount, sourceArgb.size).coerceAtLeast(0)
        bgra.put(sourceArgb, 0, available)
        var remaining = (pixelCount - available).coerceAtLeast(0)
        while (remaining > 0) {
            val count = minOf(remaining, transparentBgraPixels.size)
            bgra.put(transparentBgraPixels, 0, count)
            remaining -= count
        }
    }
}

private val transparentBgraPixels = IntArray(16_384)

/**
 * A backend-private upload layout marker. Logical pixels remain RGBA; only the Vulkan loader
 * accepts this payload and chooses B8G8R8A8_UNORM. Ordinary assets and OpenGL use RGBA buffers.
 */
class KoolCanvasBgraImageData(
    val data: Uint8Buffer,
    override val width: Int,
    override val height: Int,
    override val id: String,
) : ImageData2d {
    override val format: TexFormat = TexFormat.RGBA
    init {
        require(width > 0 && height > 0) { "BGRA image dimensions must be positive" }
        val pixelCount = width.toLong() * height
        require(pixelCount <= Int.MAX_VALUE / 4L && data.capacity.toLong() == pixelCount * 4L) {
            "BGRA image payload must contain exactly four bytes per pixel"
        }
    }
}


data class KoolCanvasArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val premultipliedAlpha: Boolean = false,
) {
    fun copyPixels(): KoolCanvasArgbImage = copy(pixels = pixels.copyOf())
}

/** Explicit recording-store identity; pixel revision ids remain independent immutable GPU owners. */
@ConsistentCopyVisibility
data class KoolCanvasFrozenPixelIdentity internal constructor(
    val recordingOwnerId: Long,
    val logicalTextureId: KoolCanvasTextureId,
)

data class KoolCanvasTextureRef(
    val id: KoolCanvasTextureId,
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean = false,
    val requiresOrderedAlpha: Boolean = false,
    val premultipliedAlpha: Boolean = false,
    val frozenPixelIdentity: KoolCanvasFrozenPixelIdentity? = null,
) {
    init {
        require(width >= 0) { "Texture width must be non-negative" }
        require(height >= 0) { "Texture height must be non-negative" }
    }

    val widthFloat: Float = width.toFloat()
    val heightFloat: Float = height.toFloat()
    val safeWidthFloat: Float = width.coerceAtLeast(1).toFloat()
    val safeHeightFloat: Float = height.coerceAtLeast(1).toFloat()
    val inverseSafeWidth: Float = 1f / safeWidthFloat
    val inverseSafeHeight: Float = 1f / safeHeightFloat
    val fullRect: KoolCanvasRect = KoolCanvasRect.fromSize(widthFloat, heightFloat)
}

data class KoolCanvasRenderTargetRef(
    val id: KoolCanvasRenderTargetId,
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 0) { "Render target width must be non-negative" }
        require(height >= 0) { "Render target height must be non-negative" }
    }
}
