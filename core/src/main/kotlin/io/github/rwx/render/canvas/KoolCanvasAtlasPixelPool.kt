package io.github.rwx.render.canvas

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap
import de.fabmax.kool.util.Uint8Buffer
import de.fabmax.kool.util.Uint8BufferImpl

/** Render-thread-only immutable CPU/upload-page sharing; texture and GPU ownership stay independent. */
internal class KoolCanvasAtlasPixelPool(
    // Recycle only unpublished duplicate pages; 0 retains fresh allocation / full-array COW.
    private val reusePageScratch: Boolean = System.getenv("RWX_REUSE_MAP_ATLAS_SCRATCH") != "0",
) : AutoCloseable {
    private class Entry(val hash: Int, pixels: ByteArray, queue: ReferenceQueue<ByteArray>) :
        WeakReference<ByteArray>(pixels, queue)

    private val queue = ReferenceQueue<ByteArray>()
    private val pages = mutableMapOf<Int, MutableList<Entry>>()
    private val uploads = WeakHashMap<ByteArray, WeakReference<ByteBuffer>>()
    // Only an unpublished loser of an exact intern match may enter this slot. Canonical pages,
    // including pages retired from an atlas, can still have readers and must never be recycled.
    private var pageScratch: ByteArray? = null
    // All bytes at and beyond this conservative prefix are already zero.
    private var scratchDirtyPrefixBytes = 0
    private var closed = false
    val scratchRetainedBytes: Int get() = pageScratch?.size ?: 0
    var pageBufferAllocations = 0L
        private set
    var scratchReuseHits = 0L
        private set
    var scratchRecycleHits = 0L
        private set
    var scratchResetBytes = 0L
        private set
    var pageCopyBytes = 0L
        private set
    var blankPageRequests = 0L
        private set
    var pageCopyRequests = 0L
        private set
    var internRequests = 0L
        private set
    var reuseHits = 0L
        private set
    var uploadRequests = 0L
        private set
    var uploadReuseHits = 0L
        private set

    fun blankPagePixels(byteCount: Int): ByteArray {
        blankPageRequests++
        return pageStorage(byteCount)
    }

    /** A shorter bound is valid only when its entire suffix is known zero; generic callers copy all. */
    fun copyPagePixels(pixels: ByteArray, sourcePrefixBytes: Int = pixels.size): ByteArray {
        require(sourcePrefixBytes in 0..pixels.size)
        pageCopyRequests++
        if (!reusePageScratch || closed) {
            pageBufferAllocations++
            pageCopyBytes += pixels.size
            return pixels.copyOf()
        }
        // Clear the donor tail from the OLD source prefix. A later rectangular blit need not
        // overwrite every column in the larger prefix it declares for its impending write.
        return pageStorage(pixels.size, overwrittenPrefixBytes = sourcePrefixBytes).also {
            pixels.copyInto(it, endIndex = sourcePrefixBytes)
            pageCopyBytes += sourcePrefixBytes
        }
    }

    private fun pageStorage(byteCount: Int, overwrittenPrefixBytes: Int = 0): ByteArray {
        val scratch = pageScratch
        val dirtyPrefixBytes = scratchDirtyPrefixBytes
        pageScratch = null
        scratchDirtyPrefixBytes = 0
        if (reusePageScratch && !closed && scratch?.size == byteCount) {
            scratchReuseHits++
            if (dirtyPrefixBytes > overwrittenPrefixBytes) {
                scratch.fill(0, overwrittenPrefixBytes, dirtyPrefixBytes)
                scratchResetBytes += dirtyPrefixBytes - overwrittenPrefixBytes
            }
            return scratch
        }
        pageBufferAllocations++
        return ByteArray(byteCount)
    }

    /** Only Page may return its former exclusive buffer after intern() matched it exactly and the
     * Page has switched to immutable canonical storage. Generic intern() never consumes its input. */
    fun recycleDuplicatePage(pixels: ByteArray, canonical: ByteArray, dirtyPrefixBytes: Int = pixels.size) {
        require(dirtyPrefixBytes in 0..pixels.size)
        if (reusePageScratch && !closed && canonical !== pixels && pageScratch == null &&
            pixels.size <= MAX_SCRATCH_BYTES) {
            pageScratch = pixels
            scratchDirtyPrefixBytes = dirtyPrefixBytes
            scratchRecycleHits++
        }
    }

    override fun close() {
        closed = true
        pageScratch = null
        scratchDirtyPrefixBytes = 0
    }

    /** Each consumer owns its cursor; neither consumers nor the pool can rewrite published bytes. */
    fun uploadView(canonicalPixels: ByteArray): Uint8Buffer {
        uploadRequests++
        val root = uploads[canonicalPixels]?.get()?.also {
            uploadReuseHits++
            if (uploadReuseHits == 1L) println("RWXAtlasPixelUploads reuseConfirmed=true bytes=${canonicalPixels.size}")
        } ?: run {
            val buffer = ByteBuffer.allocateDirect(canonicalPixels.size).order(ByteOrder.nativeOrder())
            buffer.put(canonicalPixels); buffer.flip()
            uploads[canonicalPixels] = WeakReference(buffer)
            buffer
        }
        return Uint8BufferImpl(root.asReadOnlyBuffer().order(ByteOrder.nativeOrder()))
    }

    /** Callers must copy before modifying the returned page, including on the first insertion. */
    fun intern(pixels: ByteArray): ByteArray = intern(pixels, pixels.contentHashCode())

    /** The fingerprint selects candidates only; exact bytes still decide whether storage is shared.
     * Different fingerprints for equal bytes may miss reuse, but cannot merge different pixels. */
    fun intern(pixels: ByteArray, fingerprint: Int): ByteArray {
        drainCollected()
        internRequests++
        val bucket = pages.getOrPut(fingerprint) { mutableListOf() }
        val iterator = bucket.iterator()
        while (iterator.hasNext()) {
            val existing = iterator.next().get()
            if (existing == null) iterator.remove()
            else if (existing.contentEquals(pixels)) {
                reuseHits++
                if (reuseHits == 1L) println("RWXAtlasPixelSharing reuseConfirmed=true bytes=${pixels.size}")
                return existing
            }
        }
        bucket += Entry(fingerprint, pixels, queue)
        return pixels
    }

    private fun drainCollected() {
        while (true) {
            val entry = queue.poll() as? Entry ?: return
            pages[entry.hash]?.let { bucket ->
                bucket.remove(entry)
                if (bucket.isEmpty()) pages.remove(entry.hash)
            }
        }
    }

    private companion object {
        const val MAX_SCRATCH_BYTES = 2048 * 2048 * 4
    }
}
