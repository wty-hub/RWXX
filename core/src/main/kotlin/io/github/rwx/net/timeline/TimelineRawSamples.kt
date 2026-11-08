package io.github.rwx.net.timeline

/** Borrowed only by its owner during one synchronous fitting call. Values are immutable copies. */
internal interface TimelinePointRun {
    val size: Int
    fun timeAt(index: Int): Long
    fun valueAt(index: Int): TimelineValue
    fun nearAt(index: Int, mode: TimelineMode, actual: TimelineValue): Boolean
}

/**
 * Raw observation storage, not an authorized timeline or a validation cache.
 * Four inline points avoid allocating 64-point pages for three-point constant runs.
 * Larger runs own 64-point primitive pages; no Sample/TimelineValue objects are retained.
 */
internal class TimelineRawSamples(private val type: TimelineValueType) {
    private val owner = Thread.currentThread()
    private val stride = when (type) {
        TimelineValueType.VEC3 -> 4
        TimelineValueType.UINT -> 2
        else -> throw IllegalArgumentException("Unsupported raw sample type")
    }
    private var small: LongArray? = null
    private var pages: Array<LongArray?>? = null
    private var headPage = 0
    private var headOffset = 0
    private var pageCount = 0
    private var count = 0
    private var generation = 0L

    val size: Int get() { checkOwner(); return count }
    val allocatedLongCount: Long get() {
        checkOwner()
        return small?.size?.toLong() ?: (pageCount.toLong() * PAGE_POINTS * stride)
    }
    fun isEmpty(): Boolean { checkOwner(); return count == 0 }
    fun isNotEmpty(): Boolean { checkOwner(); return count != 0 }

    /** Exactly the existing same-time replacement and three-point constant-run rule. */
    fun appendObserved(time: Long, value: TimelineValue) {
        checkOwner()
        protocolRequire(value.type == type, "Raw observation type changed")
        protocolRequire(count == 0 || time >= rawTime(count - 1), "Raw observation time regressed")
        val remaining = if (count != 0 && rawTime(count - 1) == time) count - 1 else count
        val compact = remaining >= 3 && equalValue(remaining - 1, value) &&
            equalValue(remaining - 2, value) && equalValue(remaining - 3, value)
        val beforeAppend = remaining - if (compact) 1 else 0
        protocolRequire(beforeAppend < MAX_POINTS, "Unpublished observation backlog exceeds raw sample capacity")
        mutate()
        if (compact) copyPoint(remaining - 1, remaining - 2)
        count = beforeAppend
        ensureAppendSpace()
        writePoint(count, time, value)
        count++
        releaseUnusedTail()
        if (count <= SMALL_POINTS && pages != null) compactSmall()
    }

    /** Preserve the same two-point/predecessor stopping condition as the former deque. */
    fun trimPredecessors(floor: Long) {
        checkOwner()
        var remove = 0
        while (count - remove > 2 && rawTime(remove + 1) <= floor) remove++
        if (remove == 0) return
        mutate()
        if (pages == null) {
            val data = checkNotNull(small)
            System.arraycopy(data, remove * stride, data, 0, (count - remove) * stride)
            count -= remove
            return
        }
        count -= remove
        headOffset += remove
        val directory = checkNotNull(pages)
        while (headOffset >= PAGE_POINTS) {
            directory[headPage] = null
            headPage = (headPage + 1) % directory.size
            pageCount--; headOffset -= PAGE_POINTS
        }
        // After an ordinary seal the one-second predecessor run normally fits in
        // one page. Copy at most 64 points, never the entire large retained run.
        if (count <= SMALL_POINTS) compactSmall()
        else if (count <= PAGE_POINTS) compactOnePage()
    }

    fun clear() {
        checkOwner(); mutate()
        small = null; pages = null; count = 0; headPage = 0; headOffset = 0; pageCount = 0
    }

    fun borrow(): TimelinePointRun {
        checkOwner()
        return Run(generation, count)
    }

    private inner class Run(private val expectedGeneration: Long, private val borrowedSize: Int) : TimelinePointRun {
        private fun valid() {
            checkOwner()
            check(generation == expectedGeneration) { "Raw point run outlived its owner mutation" }
        }
        private fun checkIndex(index: Int) {
            valid(); check(index in 0 until borrowedSize) { "Raw point index outside borrowed run" }
        }
        override val size: Int get() { valid(); return borrowedSize }
        override fun timeAt(index: Int): Long { checkIndex(index); return rawTime(index) }
        override fun valueAt(index: Int): TimelineValue {
            checkIndex(index)
            return when (type) {
                TimelineValueType.VEC3 -> TimelineValue.Vec3(raw(index, 1), raw(index, 2), raw(index, 3))
                TimelineValueType.UINT -> TimelineValue.UInt(raw(index, 1))
                else -> error("Unsupported raw sample type")
            }
        }
        override fun nearAt(index: Int, mode: TimelineMode, actual: TimelineValue): Boolean {
            checkIndex(index)
            return when (actual) {
                is TimelineValue.Vec3 -> type == TimelineValueType.VEC3 &&
                    kotlin.math.abs(actual.x - raw(index, 1)) <= 64 &&
                    kotlin.math.abs(actual.y - raw(index, 2)) <= 64 &&
                    kotlin.math.abs(actual.z - raw(index, 3)) <= 64
                is TimelineValue.UInt -> type == TimelineValueType.UINT && if (mode == TimelineMode.ANGLE) {
                    val difference = Math.floorMod(raw(index, 1) - actual.value, 65536L)
                    kotlin.math.abs(if (difference > 32768) difference - 65536 else difference) <= 182
                } else kotlin.math.abs(actual.value - raw(index, 1)) <= 1
                else -> false
            }
        }
    }

    private fun checkOwner() { check(Thread.currentThread() === owner) { "Raw samples belong to their observation owner" } }
    private fun mutate() { check(generation != Long.MAX_VALUE) { "Raw sample generation exhausted" }; generation++ }

    private fun rawTime(index: Int) = raw(index, 0)
    private fun raw(index: Int, component: Int): Long {
        val inline = small
        if (inline != null) return inline[index * stride + component]
        val point = headOffset + index
        val directory = checkNotNull(pages)
        val page = checkNotNull(directory[(headPage + point / PAGE_POINTS) % directory.size])
        return page[(point % PAGE_POINTS) * stride + component]
    }
    private fun setRaw(index: Int, component: Int, value: Long) {
        val inline = small
        if (inline != null) inline[index * stride + component] = value
        else {
            val point = headOffset + index
            val directory = checkNotNull(pages)
            val page = checkNotNull(directory[(headPage + point / PAGE_POINTS) % directory.size])
            page[(point % PAGE_POINTS) * stride + component] = value
        }
    }
    private fun equalValue(index: Int, value: TimelineValue): Boolean = when (value) {
        is TimelineValue.Vec3 -> raw(index, 1) == value.x && raw(index, 2) == value.y && raw(index, 3) == value.z
        is TimelineValue.UInt -> raw(index, 1) == value.value
        else -> false
    }
    private fun copyPoint(from: Int, to: Int) {
        for (component in 0 until stride) setRaw(to, component, raw(from, component))
    }
    private fun writePoint(index: Int, time: Long, value: TimelineValue) {
        setRaw(index, 0, time)
        when (value) {
            is TimelineValue.Vec3 -> { setRaw(index, 1, value.x); setRaw(index, 2, value.y); setRaw(index, 3, value.z) }
            is TimelineValue.UInt -> setRaw(index, 1, value.value)
            else -> error("Unsupported raw sample type")
        }
    }

    private fun ensureAppendSpace() {
        if (pages == null) {
            if (small == null) small = LongArray(SMALL_POINTS * stride)
            if (count < SMALL_POINTS) return
            val page = LongArray(PAGE_POINTS * stride)
            checkNotNull(small).copyInto(page, endIndex = count * stride)
            small = null; pages = arrayOf(page, null); headPage = 0; headOffset = 0; pageCount = 1
        }
        val needed = (headOffset + count) / PAGE_POINTS + 1
        if (needed <= pageCount) return
        var directory = checkNotNull(pages)
        if (needed > directory.size) {
            val replacement = arrayOfNulls<LongArray>(minOf(MAX_PAGES, maxOf(needed, directory.size * 2)))
            for (index in 0 until pageCount) replacement[index] = directory[(headPage + index) % directory.size]
            directory = replacement; pages = replacement; headPage = 0
        }
        check(needed <= MAX_PAGES)
        directory[(headPage + pageCount) % directory.size] = LongArray(PAGE_POINTS * stride)
        pageCount++
    }

    private fun releaseUnusedTail() {
        val directory = pages ?: return
        val required = (headOffset + count + PAGE_POINTS - 1) / PAGE_POINTS
        while (pageCount > required) {
            directory[(headPage + pageCount - 1) % directory.size] = null
            pageCount--
        }
    }

    private fun compactSmall() {
        val replacement = LongArray(SMALL_POINTS * stride)
        for (point in 0 until count) for (component in 0 until stride)
            replacement[point * stride + component] = raw(point, component)
        small = replacement; pages = null; headPage = 0; headOffset = 0; pageCount = 0
    }

    private fun compactOnePage() {
        val directory = checkNotNull(pages)
        if (pageCount == 1 && headOffset == 0 && directory.size == 2 && headPage == 0) return
        val first = checkNotNull(directory[headPage])
        // Every destination precedes its source in logical order. Copying forwards
        // cannot overwrite a not-yet-read point, including the second source page.
        for (point in 0 until count) for (component in 0 until stride)
            first[point * stride + component] = raw(point, component)
        pages = arrayOf(first, null); headPage = 0; headOffset = 0; pageCount = 1
    }

    companion object {
        private const val SMALL_POINTS = 4
        private const val PAGE_POINTS = 64
        private const val MAX_POINTS = 4096
        private const val MAX_PAGES = 65 // At most one partially consumed head page.
    }
}
