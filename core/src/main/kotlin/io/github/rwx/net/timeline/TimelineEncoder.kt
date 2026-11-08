package io.github.rwx.net.timeline

import java.lang.ref.WeakReference

/** Builds connection-local transactions from an immutable, already-authorized timeline publication. */
class TimelineEncoder(private val clock: () -> Long = System::nanoTime,
                      private val heartbeatNanos: Long = 1_000_000_000L) {
    private data class Pending(val header: TimelineHeader, val sourceControl: Long,
                               val source: WeakReference<TimelineState>)
    private var pending: Pending? = null
    private var sourceControlAtCommit = 0L
    private var lastCommitAt: Long? = null
    private var committedSource: WeakReference<TimelineState>? = null
    private var committedCursor: WeakReference<TimelineState>? = null
    internal var diffPasses = 0L
        private set

    init { require(heartbeatNanos > 0) }

    /** Called by one connection's writer, never by the simulation owner. */
    fun encode(cursor: TimelineSendCursor, desired: TimelineState): TimelinePublication? {
        val now = clock()
        val previous = cursor.previous
        protocolRequire(cursor.context.matchId == desired.context.matchId && cursor.context.accessId == desired.context.accessId &&
            cursor.context.mapKey == desired.context.mapKey && cursor.context.teamId == desired.context.teamId &&
            cursor.context.ticksPerSecond == desired.context.ticksPerSecond, "Publication crossed its authorization context")
        pending?.takeIf { previous != null && previous.header == it.header }?.let {
                sourceControlAtCommit = it.sourceControl
                lastCommitAt = now
                committedSource = it.source
                committedCursor = WeakReference(checkNotNull(previous))
                pending = null
            }
        val reset = cursor.reset || previous == null
        if (reset) { committedSource = null; committedCursor = null }
        // A feed is polled much more often than its source changes. Only an actual committed
        // result establishes this identity pair; prepared/abandoned transactions cannot do so.
        // Weak references avoid retaining an extra historical source library for a slow peer.
        if (!reset && pending == null && previous != null && cursor.streamId == previous.header.streamId &&
            cursor.epoch == previous.header.epoch && cursor.context === previous.context &&
            committedSource?.get() === desired && committedCursor?.get() === previous &&
            (cursor.context.mode == TimelineStreamMode.HISTORY ||
                lastCommitAt?.let { now - it < heartbeatNanos } == true)) return null
        if (!reset && desired.header.retainedFrom > previous!!.header.sealedThrough) throw TimelineResetRequired()
        val control = if (cursor.context.mode == TimelineStreamMode.HISTORY) 0 else when {
            previous == null -> maxOf(1, desired.header.controlEpoch)
            reset -> increment(previous.header.controlEpoch)
            else -> {
                protocolRequire(desired.header.controlEpoch >= sourceControlAtCommit, "Source control generation regressed")
                val delta = desired.header.controlEpoch - sourceControlAtCommit
                protocolRequire(previous.header.controlEpoch <= Long.MAX_VALUE - delta, "Control epoch exhausted")
                previous.header.controlEpoch + delta
            }
        }
        val operations = if (reset) resetOperations(desired) else updateOperations(previous!!, desired)
        val source = desired.header
        if (!reset && operations.isEmpty() && source.observedThrough == previous!!.header.observedThrough &&
            source.sealedThrough == previous.header.sealedThrough && source.retainedFrom == previous.header.retainedFrom &&
            source.retainedUntil == previous.header.retainedUntil && control == previous.header.controlEpoch &&
            source.objectHighWater == previous.header.objectHighWater &&
            (cursor.context.mode == TimelineStreamMode.HISTORY ||
                lastCommitAt?.let { now - it < heartbeatNanos } == true)) return null
        val bytes = TimelineBinary.encodeOperationsFromState(operations, if (reset) null else previous)
        val header = TimelineHeader(cursor.streamId, cursor.epoch,
            if (reset) TimelineTransactionKind.RESET else TimelineTransactionKind.UPDATE,
            if (reset) 1 else increment(previous!!.header.revision), if (reset) 0 else previous!!.header.revision,
            source.observedThrough, source.sealedThrough, source.retainedFrom, source.retainedUntil,
            control, bytes.size.toLong(), TimelineCanonical.sha256(bytes), source.objectHighWater)
        pending = Pending(header, source.controlEpoch, WeakReference(desired))
        return TimelinePublication(header, bytes)
    }

    private fun updateOperations(previous: TimelineState, desired: TimelineState): List<TimelineOperation> {
        diffPasses++
        val operations = ArrayList<TimelineOperation>()
        var base = previous
        if (desired.header.retainedFrom > previous.header.retainedFrom) {
            val retain = TimelineOperation.Retain(desired.header.retainedFrom)
            val bytes = TimelineBinary.encodeOperationsFromState(listOf(retain), previous)
            val header = previous.header.copy(kind = TimelineTransactionKind.UPDATE,
                baseRevision = previous.header.revision, revision = increment(previous.header.revision),
                retainedFrom = desired.header.retainedFrom, operationBytes = bytes.size.toLong(), operationSha256 = TimelineCanonical.sha256(bytes))
            base = TimelineStore.fromCommitted(previous).prepare(header, bytes).state
            operations.add(retain)
        }
        protocolRequire(desired.header.observedThrough >= previous.header.observedThrough &&
            desired.header.sealedThrough >= previous.header.sealedThrough &&
            desired.header.objectHighWater >= previous.header.objectHighWater, "Source timeline regressed")
        protocolRequire(base.objects.keys.all { it in desired.objects }, "Source silently discarded retained observations")
        val from = maxOf(desired.header.retainedFrom, previous.header.sealedThrough)
        for ((handle, target) in TimelineCanonical.orderedObjects(desired.objects)) {
            val old = base.objects[handle]
            if (old == null) {
                protocolRequire(handle > previous.header.objectHighWater && target.observedFrom >= previous.header.sealedThrough,
                    "Late or reused source observation")
                operations.addAll(createOperations(target, reset = false))
                continue
            }
            protocolRequire(old.kind == target.kind && old.observedFrom == target.observedFrom && old.descriptor == target.descriptor,
                "Source changed immutable observation identity")
            protocolRequire(old.close == null || old.close == target.close, "Source reopened a closed observation")
            protocolRequire(old.curves.keys.all { it in target.curves }, "Source silently discarded an attribute")
            for ((id, curve) in TimelineCanonical.orderedCurves(target.curves)) {
                val before = old.curves[id]
                if (before == null) {
                    operations.add(TimelineOperation.Declare(handle, id, curve.mode, curve.valueType, curve.criticalAttribute, 0, curve.metadata))
                    appendCurve(operations, handle, curve, 0, reset = false)
                    continue
                }
                protocolRequire(before.mode == curve.mode && before.valueType == curve.valueType &&
                    before.criticalAttribute == curve.criticalAttribute && before.metadata == curve.metadata,
                    "Source changed an attribute declaration")
                // Immutable shared curves cannot need a diff. This only avoids temporary
                // suffixes; the emitted transaction still passes Store/Profile validation.
                if (before === curve) continue
                if (curve.mode == TimelineMode.PULSE) {
                    if (before.events.isEmpty() && curve.events.isEmpty()) continue
                    val prior = before.events.filter { it.time >= from }
                    val after = curve.events.filter { it.time >= from }
                    if (prior != after) {
                        // A full checkpoint safely restores arbitrary histories. Normal live pulses are
                        // chronological; a large/backdated correction is never truncated to fit a splice.
                        if (after.size > TimelineProtocol.MAX_SPLICE_RECORDS || after.sumOf { pulseSize(curve.valueType, it) } > RECORD_BYTE_BUDGET) throw TimelineResetRequired()
                        val known = prior.associateBy { it.eventId }
                        var high = before.eventHighWater
                        for (event in after) if (known[event.eventId] != event) {
                            if (event.eventId <= high) throw TimelineResetRequired()
                            high = event.eventId
                        }
                        operations.add(TimelineOperation.Pulse(handle, id, before.curveRevision, from, Long.MAX_VALUE, after))
                    }
                } else {
                    // Connection-local revisions can differ while the complete function
                    // is identical. Avoid clipping/copying its common one-record body.
                    if ((before.segments.isEmpty() && curve.segments.isEmpty()) ||
                        (before.segments.size == 1 && curve.segments.size == 1 &&
                            before.segments[0] == curve.segments[0])) continue
                    val prior = suffix(before.segments, from)
                    val after = suffix(curve.segments, from)
                    if (prior != after) {
                        if (after.size > TimelineProtocol.MAX_SPLICE_RECORDS || after.sumOf { segmentSize(it) } > RECORD_BYTE_BUDGET) throw TimelineResetRequired()
                        // Both complete suffixes are empty beyond their last endpoint. Bounding
                        // the edit there removes the same records with a shorter wire endpoint.
                        // A constant/infinite tail still selects FOREVER; equal empty suffixes
                        // never enter this branch. Segment bodies and curve revisions are unchanged.
                        val until = maxOf(prior.lastOrNull()?.hi ?: from, after.lastOrNull()?.hi ?: from)
                        operations.add(TimelineOperation.Splice(handle, id, before.curveRevision, from, until, after))
                    }
                }
            }
            if (old.close == null) target.close?.let { operations.add(TimelineOperation.Close(handle, it.until, it.kind)) }
        }
        return frozenList(operations)
    }

    companion object {
        private const val RECORD_BYTE_BUDGET = TimelineProtocol.MAX_OPERATION_BYTES - 6 * 9L
        /** Independent RESET operations; source curve revisions are intentionally not copied. */
        fun resetOperations(state: TimelineState): List<TimelineOperation> = buildList {
            for ((_, obj) in TimelineCanonical.orderedObjects(state.objects)) addAll(createOperations(obj, reset = true))
        }

        private fun createOperations(obj: TimelineObject, reset: Boolean): List<TimelineOperation> = buildList {
            add(TimelineOperation.Create(obj.handle, obj.kind, obj.observedFrom, obj.descriptor))
            for ((id, curve) in TimelineCanonical.orderedCurves(obj.curves)) {
                add(TimelineOperation.Declare(obj.handle, id, curve.mode, curve.valueType, curve.criticalAttribute,
                    if (reset) curve.eventHighWater else 0, curve.metadata))
                appendCurve(this, obj.handle, curve, 0, reset)
            }
            obj.close?.let { add(TimelineOperation.Close(obj.handle, it.until, it.kind)) }
        }

        private fun appendCurve(out: MutableList<TimelineOperation>, handle: Long, curve: TimelineCurve,
                                initialRevision: Long, reset: Boolean) {
            var revision = initialRevision
            if (curve.mode == TimelineMode.PULSE) {
                var index = 0
                var high = 0L
                while (index < curve.events.size) {
                    var end = index
                    var bytes = 0L
                    while (end < curve.events.size) {
                        var groupEnd = end + 1
                        while (groupEnd < curve.events.size && curve.events[groupEnd].time == curve.events[end].time) groupEnd++
                        val groupBytes = curve.events.subList(end, groupEnd).sumOf { pulseSize(curve.valueType, it) }
                        protocolRequire(groupEnd - end <= TimelineProtocol.MAX_SPLICE_RECORDS && groupBytes <= RECORD_BYTE_BUDGET,
                            "Pulse time group exceeds restoring operation capacity")
                        if (groupEnd - index > TimelineProtocol.MAX_SPLICE_RECORDS || bytes + groupBytes > RECORD_BYTE_BUDGET) break
                        bytes += groupBytes
                        end = groupEnd
                    }
                    protocolRequire(end > index, "Pulse cannot fit a restoring operation")
                    val events = curve.events.subList(index, end)
                    if (!reset) for (event in events) {
                        if (event.eventId <= high) throw TimelineResetRequired("Pulse requires a restoring checkpoint")
                        high = event.eventId
                    }
                    out.add(TimelineOperation.Pulse(handle, curve.attributeId, revision++, events.first().time,
                        increment(events.last().time), events))
                    index = end
                }
            } else {
                var index = 0
                while (index < curve.segments.size) {
                    var end = index
                    var bytes = 0L
                    while (end < curve.segments.size && end - index < TimelineProtocol.MAX_SPLICE_RECORDS) {
                        val amount = segmentSize(curve.segments[end])
                        protocolRequire(amount <= RECORD_BYTE_BUDGET, "Segment cannot fit a restoring operation")
                        if (bytes + amount > RECORD_BYTE_BUDGET) break
                        bytes += amount
                        end++
                    }
                    val segments = curve.segments.subList(index, end)
                    out.add(TimelineOperation.Splice(handle, curve.attributeId, revision++, segments.first().lo,
                        segments.last().hi, segments))
                    index = end
                }
            }
        }

        private fun pulseSize(type: TimelineValueType, pulse: TimelinePulse): Long =
            varSize(pulse.time) + varSize(pulse.eventId) + valueSize(pulse.value)

        private fun segmentSize(segment: TimelineSegment): Long = varSize(segment.lo) + varSize(segment.hi) + 1 + when (segment) {
            is TimelineSegment.Step -> valueSize(segment.value)
            is TimelineSegment.Interpolated -> varSize(segment.anchor0Time) + varSize(segment.anchor1Time) +
                valueSize(segment.value0) + valueSize(segment.value1)
        }

        private fun valueSize(value: TimelineValue): Long = when (value) {
            is TimelineValue.SInt -> signedSize(value.value)
            is TimelineValue.UInt -> varSize(value.value)
            is TimelineValue.Vec3 -> signedSize(value.x) + signedSize(value.y) + signedSize(value.z)
            is TimelineValue.Bool -> 1L
            is TimelineValue.Blob -> varSize(value.value.size.toLong()) + value.value.size
        }

        internal fun varSize(value: Long): Long {
            protocolRequire(value >= 0, "Invalid vuint")
            var remaining = value
            var size = 1L
            while (remaining >= 128) { remaining = remaining ushr 7; size++ }
            return size
        }

        internal fun signedSize(value: Long): Long {
            protocolRequire(value in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT, "Invalid vsint")
            return varSize((value shl 1) xor (value shr 63))
        }

        /** Compare unsealed functions without mutating the canonical, already sent records. */
        private fun suffix(segments: List<TimelineSegment>, from: Long): List<TimelineSegment> {
            val result = ArrayList<TimelineSegment>()
            for (original in segments) {
                if (original.hi <= from) continue
                val next = if (original.lo < from) original.clipped(from, original.hi) else original
                val last = result.lastOrNull()
                if (last != null && last.hi == next.lo && last.predicted == next.predicted && sameFormula(last, next)) {
                    result[result.lastIndex] = last.clipped(last.lo, next.hi)
                } else result.add(next)
            }
            return result
        }

        private fun sameFormula(a: TimelineSegment, b: TimelineSegment): Boolean = when {
            a is TimelineSegment.Step && b is TimelineSegment.Step -> a.value == b.value
            a is TimelineSegment.Interpolated && b is TimelineSegment.Interpolated ->
                a.anchor0Time == b.anchor0Time && a.anchor1Time == b.anchor1Time && a.value0 == b.value0 && a.value1 == b.value1
            else -> false
        }
        private fun increment(value: Long): Long {
            protocolRequire(value in 0 until Long.MAX_VALUE, "Timeline counter exhausted")
            return value + 1
        }
    }
}
