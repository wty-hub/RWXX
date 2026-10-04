package io.github.rwx.render.canvas

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exact-size raster storage. Only arrays without an owner may return to the bounded idle pool. */
internal class KoolCanvasPixelPool(
    private val maxRetainedBytes: Long = 64L * 1024 * 1024,
    private val maxArraysPerSize: Int = 64,
) : AutoCloseable {
    private val idle = mutableMapOf<Int, ArrayDeque<IntArray>>()
    private var retainedBytes = 0L
    private var retainedArrays = 0
    private var activeAllocations = 0
    private var allocations = 0L
    private var misses = 0L
    private var reusedArrays = 0L
    private var preallocatedArrays = 0L
    private var generation = 0L
    private var closed = false

    init {
        require(maxRetainedBytes >= 0 && maxArraysPerSize >= 0)
    }

    /** The caller initially owns this writable raster; additional owners must treat it as immutable. */
    @Synchronized
    fun borrow(pixelCount: Int): PixelOwner {
        require(pixelCount > 0)
        check(!closed) { "Pixel pool is closed" }
        val queue = idle[pixelCount]
        val pixels = if (queue != null && queue.isNotEmpty()) {
            queue.removeFirst().also {
                retainedBytes -= pixelCount.toLong() * Int.SIZE_BYTES
                retainedArrays--
                reusedArrays++
                if (queue.isEmpty()) idle.remove(pixelCount)
            }
        } else IntArray(pixelCount).also {
            allocations++
            misses++
        }
        val allocation = Allocation(this, pixels, generation)
        val owner = PixelOwner(allocation)
        activeAllocations++
        return owner
    }

    /** Preallocate idle storage before rendering. Returns the number newly retained within capacity. */
    @Synchronized
    fun preallocate(pixelCount: Int, count: Int): Int {
        require(pixelCount > 0 && count >= 0)
        check(!closed) { "Pixel pool is closed" }
        var added = 0
        while (added < count && canRetain(pixelCount)) {
            val pixels = IntArray(pixelCount)
            idle.getOrPut(pixelCount) { ArrayDeque() }.addLast(pixels)
            retainedBytes += pixelCount.toLong() * Int.SIZE_BYTES
            retainedArrays++
            allocations++
            preallocatedArrays++
            added++
        }
        return added
    }

    /** Trim idle storage; arrays already borrowed before this trim will not refill it later. */
    @Synchronized
    fun clear() {
        generation++
        idle.clear()
        retainedBytes = 0
        retainedArrays = 0
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            clear()
        }
    }

    @Synchronized
    internal fun stats(): Stats = Stats(retainedBytes, retainedArrays, activeAllocations,
        allocations, misses, reusedArrays, preallocatedArrays)

    private fun canRetain(pixelCount: Int): Boolean =
        maxArraysPerSize > (idle[pixelCount]?.size ?: 0) &&
            pixelCount.toLong() * Int.SIZE_BYTES <= maxRetainedBytes - retainedBytes

    @Synchronized
    private fun recycle(allocation: Allocation) {
        activeAllocations--
        check(activeAllocations >= 0) { "Unbalanced pixel allocation" }
        val pixels = allocation.pixels
        if (!closed && allocation.generation == generation && canRetain(pixels.size)) {
            idle.getOrPut(pixels.size) { ArrayDeque() }.addLast(pixels)
            retainedBytes += pixels.size.toLong() * Int.SIZE_BYTES
            retainedArrays++
        }
    }

    internal data class Stats(
        val retainedBytes: Long,
        val retainedArrays: Int,
        val activeAllocations: Int,
        val allocations: Long,
        val misses: Long,
        val reusedArrays: Long,
        val preallocatedArrays: Long,
    )

    internal class Allocation internal constructor(
        private val pool: KoolCanvasPixelPool,
        internal val pixels: IntArray,
        internal val generation: Long,
    ) {
        private val references = AtomicInteger(1)
        internal val ownerCount: Int get() = references.get()

        internal fun retain() {
            while (true) {
                val count = references.get()
                check(count > 0) { "Cannot retain recycled pixels" }
                check(count < Int.MAX_VALUE) { "Too many pixel owners" }
                if (references.compareAndSet(count, count + 1)) return
            }
        }

        internal fun release() {
            val remaining = references.decrementAndGet()
            check(remaining >= 0) { "Unbalanced pixel ownership" }
            if (remaining == 0) pool.recycle(this)
        }
    }

    /** Each source, legacy texture, or frozen lease receives a separate idempotently closeable owner. */
    internal class PixelOwner internal constructor(internal val allocation: Allocation) : AutoCloseable {
        private val closed = AtomicBoolean()
        val pixels: IntArray
            get() {
                check(!closed.get()) { "Pixel owner is closed" }
                return allocation.pixels
            }

        /** Call while holding this owner. The allocation identity also deduplicates frozen leases. */
        @Synchronized
        fun retain(): PixelOwner {
            check(!closed.get()) { "Cannot retain closed pixel owner" }
            allocation.retain()
            return try {
                PixelOwner(allocation)
            } catch (failure: Throwable) {
                allocation.release()
                throw failure
            }
        }

        @Synchronized
        override fun close() {
            if (closed.compareAndSet(false, true)) allocation.release()
        }
    }
}
