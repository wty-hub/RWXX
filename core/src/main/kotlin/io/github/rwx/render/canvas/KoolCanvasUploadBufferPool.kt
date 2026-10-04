package io.github.rwx.render.canvas

import de.fabmax.kool.util.Uint8Buffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Exact-byte-count host upload storage; only a retired texture may return its buffer. */
internal class KoolCanvasUploadBufferPool(
    private val enabled: Boolean = System.getenv("RWX_CANVAS_UPLOAD_BUFFER_POOL") != "0",
    private val maxRetainedBytes: Long = 64L * 1024 * 1024,
    private val maxBuffersPerSize: Int = 64,
) : AutoCloseable {
    private val idle = mutableMapOf<Int, ArrayDeque<Uint8Buffer>>()
    private var generation = 0L
    private var closed = false
    private var retainedBytes = 0L
    private var retainedBuffers = 0
    private var activeBuffers = 0
    private var allocations = 0L
    private var misses = 0L
    private var reusedBuffers = 0L
    private var preallocatedBuffers = 0L

    init { require(maxRetainedBytes >= 0 && maxBuffersPerSize >= 0) }

    @Synchronized
    fun borrow(byteCount: Int): BufferOwner {
        require(byteCount > 0)
        check(!closed) { "Upload buffer pool is closed" }
        val queue = if (enabled) idle[byteCount] else null
        val buffer = if (queue != null && queue.isNotEmpty()) {
            queue.removeFirst().also {
                retainedBytes -= byteCount
                retainedBuffers--
                reusedBuffers++
                if (queue.isEmpty()) idle.remove(byteCount)
            }
        } else Uint8Buffer(byteCount).also { allocations++; misses++ }
        buffer.position = 0
        buffer.limit = buffer.capacity
        val owner = BufferOwner(this, buffer, generation)
        activeBuffers++
        return owner
    }

    @Synchronized
    fun preallocate(byteCount: Int, count: Int): Int {
        require(byteCount > 0 && count >= 0)
        check(!closed) { "Upload buffer pool is closed" }
        if (!enabled) return 0
        var added = 0
        while (added < count && canRetain(byteCount)) {
            idle.getOrPut(byteCount) { ArrayDeque() }.addLast(Uint8Buffer(byteCount))
            retainedBytes += byteCount
            retainedBuffers++
            allocations++
            preallocatedBuffers++
            added++
        }
        return added
    }

    /** Old-context owners can close later, but none of their storage can enter the new pool. */
    @Synchronized
    fun clear() {
        generation++
        idle.clear()
        retainedBytes = 0
        retainedBuffers = 0
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            clear()
        }
    }

    @Synchronized
    fun stats() = Stats(enabled, maxRetainedBytes, maxBuffersPerSize, retainedBytes, retainedBuffers, activeBuffers,
        allocations, misses, reusedBuffers, preallocatedBuffers)

    private fun canRetain(byteCount: Int) = enabled &&
        (idle[byteCount]?.size ?: 0) < maxBuffersPerSize && byteCount <= maxRetainedBytes - retainedBytes

    @Synchronized
    private fun recycle(buffer: Uint8Buffer, borrowedGeneration: Long, allowReuse: Boolean) {
        activeBuffers--
        check(activeBuffers >= 0) { "Unbalanced upload buffer ownership" }
        if (allowReuse && !closed && borrowedGeneration == generation && canRetain(buffer.capacity)) {
            idle.getOrPut(buffer.capacity) { ArrayDeque() }.addLast(buffer)
            retainedBytes += buffer.capacity
            retainedBuffers++
        }
    }

    internal data class Stats(
        val enabled: Boolean,
        val idleCapacityBytes: Long,
        val maxBuffersPerSize: Int,
        val retainedBytes: Long,
        val retainedBuffers: Int,
        val activeBuffers: Int,
        val allocations: Long,
        val misses: Long,
        val reusedBuffers: Long,
        val preallocatedBuffers: Long,
    )

    /** This owner covers a Texture2d upload slot, including ImageData still pending consumption. */
    internal class BufferOwner internal constructor(
        private val pool: KoolCanvasUploadBufferPool,
        private val ownedBuffer: Uint8Buffer,
        private val generation: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        val buffer: Uint8Buffer get() {
            check(!closed.get()) { "Upload buffer owner is closed" }
            return ownedBuffer
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) pool.recycle(ownedBuffer, generation, allowReuse = true)
        }

        /** Drop ownership without lending memory that an unfenced uploader may still read. */
        fun discard() {
            if (closed.compareAndSet(false, true)) pool.recycle(ownedBuffer, generation, allowReuse = false)
        }
    }
}
