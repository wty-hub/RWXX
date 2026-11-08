package io.github.rwx.net.timeline

import java.util.Collections
import java.util.PriorityQueue
import java.util.TreeSet

/** Transaction-private lazy RetainFrom view; no decoded world graph or cached validation. */
internal class TimelinePackedRetention private constructor(
    private val base: TimelinePackedLibrary,
    val from: Long,
    private val handles: LongArray,
    private val changed: BooleanArray,
    private val removed: BooleanArray,
    val records: Long,
    val objectCount: Int,
) {
    fun isRemoved(handle: Long): Boolean = handles.binarySearch(handle).let { it >= 0 && removed[it] }
    fun isChanged(handle: Long): Boolean = handles.binarySearch(handle).let { it >= 0 && changed[it] && !removed[it] }
    fun changedHandles(): Sequence<Long> = handles.indices.asSequence().filter { changed[it] && !removed[it] }.map { handles[it] }
    fun removedHandles(): Set<Long> {
        val result = HashSet<Long>()
        for (row in handles.indices) if (removed[row]) result.add(handles[row])
        return Collections.unmodifiableSet(result)
    }

    fun retainedObject(handle: Long): TimelineObject? {
        val row = handles.binarySearch(handle)
        if (row < 0 || removed[row]) return null
        val original = base.objectValue(handle) ?: throw ProtocolException("Retained object disappeared")
        if (!changed[row]) return original
        val edits = LinkedHashMap<Int, TimelineCurve>()
        for ((id, curve) in original.curves) {
            val clips = curve.segments.any { it.lo < from }
            val dropsEvents = curve.events.any { event ->
                protocolRequire(event.time <= Long.MAX_VALUE - curve.maxTailTime, "Event tail overflow")
                event.time < from && event.time + curve.maxTailTime <= from
            }
            if (!clips && !dropsEvents) continue
            val segments = if (!clips) curve.segments else curve.segments.filter { it.hi > from }.map {
                if (it.lo < from) it.clipped(from, it.hi) else it
            }
            val events = if (!dropsEvents) curve.events else curve.events.filter { event ->
                protocolRequire(event.time <= Long.MAX_VALUE - curve.maxTailTime, "Event tail overflow")
                event.time >= from || event.time + curve.maxTailTime > from
            }
            // Revisions/high waters/metadata/anchors remain exactly the original values.
            edits[id] = curve.copy(segments = segments, events = events)
        }
        return original.copyWithCurveEdits(original.close, edits)
    }

    companion object {
        fun scan(state: TimelineState, from: Long): TimelinePackedRetention {
            val base = state.packedLibrary ?: throw ProtocolException("Packed retention requires a packed base")
            protocolRequire(from > state.header.retainedFrom && from <= state.header.sealedThrough, "Invalid RetainFrom")
            base.validatedStats() // Full actual scan; the following work never trusts header counts.
            val handles = state.objects.keys.toLongArray() // The packed map's keys read only its directory.
            val count = handles.size
            val changed = BooleanArray(count)
            val removed = BooleanArray(count)
            val expired = BooleanArray(count)
            val retainedRecords = IntArray(count)
            val incoming = IntArray(count)
            val firstEdge = IntArray(count) { -1 }
            val secondEdge = IntArray(count) { -1 }
            var total = 0L

            fun edge(target: Long): Int {
                val index = handles.binarySearch(target)
                protocolRequire(index >= 0, "Dangling retained descriptor reference")
                incoming[index]++
                return index
            }

            for (row in handles.indices) {
                val reader = base.objectCursor(handles[row]) ?: throw ProtocolException("Missing retained object")
                val obj = reader.objectHeader()
                protocolRequire(obj.handle == handles[row], "Retained directory identity differs")
                fun reference(id: Int): Long = (obj.descriptor[id]?.value as? TimelineTlvValue.U64)?.value
                    ?: throw ProtocolException("Invalid retained descriptor reference")
                when (obj.kind) {
                    TimelineObjectKind.PANEL -> firstEdge[row] = edge(reference(2))
                    TimelineObjectKind.ACTION -> {
                        firstEdge[row] = edge(reference(2))
                        secondEdge[row] = edge(reference(3))
                    }
                    else -> Unit
                }
                var maximumTail = 0L
                var remaining = 0
                var previousId = 0
                repeat(obj.curveCount) {
                    val curve = reader.curveHeader(state.context.ticksPerSecond)
                    protocolRequire(curve.attributeId > previousId, "Unordered retained attribute")
                    previousId = curve.attributeId
                    val tail = (curve.metadata[1]?.value as? TimelineTlvValue.U64)?.value ?: 0L
                    maximumTail = maxOf(maximumTail, tail)
                    if (curve.mode == TimelineMode.PULSE) repeat(curve.recordCount) {
                        val event = reader.pulse(curve.valueType)
                        protocolRequire(event.time <= Long.MAX_VALUE - tail, "Event tail overflow")
                        if (event.time >= from || event.time + tail > from) remaining++ else changed[row] = true
                    } else repeat(curve.recordCount) {
                        val segment = reader.segment(curve.mode, curve.valueType)
                        if (segment.hi > from) remaining++
                        if (segment.lo < from) changed[row] = true
                    }
                }
                reader.finish()
                retainedRecords[row] = remaining
                total += remaining
                obj.close?.let { close ->
                    protocolRequire(close.until <= Long.MAX_VALUE - maximumTail, "Observation tail overflow")
                    expired[row] = obj.handle != 0L && close.until + maximumTail <= from
                }
            }
            // Directory rows are ordered by handle, so this is the same natural-handle
            // queue as the original Retain GC. Keep both edges, including multiplicity.
            val eligible = PriorityQueue<Int>()
            for (row in handles.indices) if (expired[row] && incoming[row] == 0) eligible.add(row)
            var survivors = count
            fun release(target: Int) {
                if (target < 0) return
                incoming[target]--
                if (incoming[target] == 0 && expired[target] && !removed[target]) eligible.add(target)
            }
            while (eligible.isNotEmpty()) {
                val row = eligible.remove()
                if (removed[row]) continue
                removed[row] = true; survivors--; total -= retainedRecords[row]
                release(firstEdge[row]); release(secondEdge[row])
            }
            protocolRequire(survivors >= 1 && total in 0..TimelineProtocol.MAX_CURVE_RECORDS.toLong(), "Invalid retained totals")
            // The larger per-scan counts/edges/queue become unreachable here. This plan
            // retains only immutable base bytes, scalar totals and bounded row markers.
            return TimelinePackedRetention(base, from, handles, changed, removed, total, survivors)
        }
    }
}

/** Owned stable provider: callers cannot supply mutable arrays or side-effecting callbacks. */
internal class TimelineSortedObjectEdits private constructor(
    private val handles: LongArray,
    private val replacements: Map<Long, TimelineObject>,
    private val retained: TimelinePackedRetention?,
) {
    val size: Int get() = handles.size
    fun contains(handle: Long): Boolean = handles.binarySearch(handle) >= 0
    fun handleAt(index: Int): Long = handles[index]
    fun valueAt(index: Int): TimelineObject {
        val handle = handles[index]
        return replacements[handle] ?: retained?.retainedObject(handle)
            ?: throw ProtocolException("Sorted edit has no object")
    }

    companion object {
        fun snapshot(replacements: Map<Long, TimelineObject>, retained: TimelinePackedRetention? = null): TimelineSortedObjectEdits {
            protocolRequire(replacements.size <= TimelineProtocol.MAX_OBJECTS, "Too many packed edits")
            val owned = frozenMap(replacements)
            val keys = TreeSet<Long>()
            keys.addAll(owned.keys)
            retained?.changedHandles()?.forEach { keys.add(it) }
            protocolRequire(keys.size <= TimelineProtocol.MAX_OBJECTS, "Too many packed edits")
            return TimelineSortedObjectEdits(keys.toLongArray(), owned, retained)
        }
    }
}
