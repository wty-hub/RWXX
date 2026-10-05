package io.github.rwx.render.canvas

import java.util.IdentityHashMap

/** Only temporary lookup tables are lent. Caller supplies the recording store's monitor. */
internal class KoolCanvasFreezeScratchPool(val enabled: Boolean) : AutoCloseable {
    internal class Scratch {
        val visiting = HashSet<KoolCanvasTextureId>()
        val resolvedIds = HashMap<KoolCanvasTextureId, KoolCanvasTextureId>()
        val frozenReferences = IdentityHashMap<KoolCanvasTextureRef, KoolCanvasTextureRef>()
        val seenPixels = IdentityHashMap<KoolCanvasPixelPool.Allocation, Boolean>()
        var visitingPeak = 0
            private set
        var borrowed = false

        fun noteVisiting() { visitingPeak = maxOf(visitingPeak, visiting.size) }

        fun clear() {
            visiting.clear()
            resolvedIds.clear()
            frozenReferences.clear()
            seenPixels.clear()
            visitingPeak = 0
        }
    }

    internal data class Stats(
        val enabled: Boolean, val borrows: Long, val reused: Long, val created: Long,
        val discarded: Long, val idle: Int, val active: Int, val peakMemoEntries: Int,
        val peakVisitingEntries: Int, val peakSeenAllocations: Int, val closed: Boolean,
    )

    private var idleScratch: Scratch? = null
    @Volatile private var closed = false
    @Volatile private var borrows = 0L
    @Volatile private var reused = 0L
    @Volatile private var created = 0L
    @Volatile private var discarded = 0L
    @Volatile private var active = 0
    @Volatile private var idle = 0
    @Volatile private var peakMemoEntries = 0
    @Volatile private var peakVisitingEntries = 0
    @Volatile private var peakSeenAllocations = 0

    fun borrow(): Scratch? {
        check(!closed) { "Freeze scratch pool is closed" }
        if (!enabled) return null
        val scratch = idleScratch?.also {
            idleScratch = null
            idle = 0
            reused++
        } ?: Scratch().also { created++ }
        check(!scratch.borrowed)
        scratch.borrowed = true
        borrows++
        active++
        return scratch
    }

    fun release(scratch: Scratch?) {
        if (scratch == null) return
        check(scratch.borrowed) { "Unbalanced freeze scratch borrow" }
        val memo = maxOf(scratch.resolvedIds.size, scratch.frozenReferences.size)
        val visiting = maxOf(scratch.visitingPeak, scratch.visiting.size)
        val seen = scratch.seenPixels.size
        peakMemoEntries = maxOf(peakMemoEntries, memo)
        peakVisitingEntries = maxOf(peakVisitingEntries, visiting)
        peakSeenAllocations = maxOf(peakSeenAllocations, seen)
        // Overflow affects retention only: the current frame has already resolved completely.
        val retain = !closed && idleScratch == null && memo <= MAX_MEMO_ENTRIES &&
            visiting <= MAX_VISITING_ENTRIES && seen <= MAX_SEEN_ALLOCATIONS
        scratch.clear()
        scratch.borrowed = false
        active--
        if (retain) {
            idleScratch = scratch
            idle = 1
        } else discarded++
    }

    /** Optional five-second metrics may read these monotonic counters from another thread. */
    fun stats() = Stats(enabled, borrows, reused, created, discarded, idle, active,
        peakMemoEntries, peakVisitingEntries, peakSeenAllocations, closed)

    override fun close() {
        closed = true
        idleScratch?.clear()
        idleScratch = null
        idle = 0
        // A reentrant close cannot lend an active table. Its outer finally discards it.
    }

    companion object {
        const val MAX_MEMO_ENTRIES = 8192
        const val MAX_VISITING_ENTRIES = 1024
        const val MAX_SEEN_ALLOCATIONS = 2048
    }
}
