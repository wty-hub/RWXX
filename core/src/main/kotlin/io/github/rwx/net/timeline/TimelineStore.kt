package io.github.rwx.net.timeline

import java.security.MessageDigest

/** A single stream's committed state. A rejected transaction never changes [current]. */
class TimelineStore private constructor(val context: TimelineContext, trustedContext: Boolean) {
    constructor(context: TimelineContext) : this(context, false)
    private var experimentalPacked = false
    var current: TimelineState? = null
        private set

    init { if (!trustedContext) TimelineProfile.validateContext(context) }

    /** Shares only immutable committed values; subsequent transactions have private staging maps. */
    fun fork(): TimelineStore = TimelineStore(context, true).also {
        it.current = current
        it.experimentalPacked = experimentalPacked
    }

    class Prepared internal constructor(private val owner: TimelineStore, private var base: TimelineState?,
                                        val state: TimelineState) {
        private var committed = false
        fun commit() {
            if (committed || owner.current !== base) {
                // A stale attempt is consumed. Keep the public candidate readable, but
                // do not retain its obsolete base through a failed Prepared object.
                base = null; committed = true
                throw ProtocolException("Prepared transaction is stale or already committed")
            }
            owner.current = state
            committed = true
            base = null
        }
    }

    fun prepare(header: TimelineHeader, operations: List<TimelineOperation>): Prepared =
        Prepared(this, current, fork().apply(header, operations))

    fun prepare(header: TimelineHeader, operationBytes: TimelineBytes): Prepared =
        Prepared(this, current, fork().apply(header, operationBytes))

    companion object {
        /** Opt-in representation experiment; production constructors keep the existing typed path. */
        internal fun experimentalPacked(state: TimelineState): TimelineStore {
            protocolRequire(state.packedLibrary != null, "Experimental Store requires a packed committed state")
            return fromCommitted(state).also { it.experimentalPacked = true }
        }

        /** Representation builders reuse the same header gate; this does not apply operations. */
        internal fun validatePackedHeader(previous: TimelineState, header: TimelineHeader) {
            TimelineStore(previous.context, true).validateHeader(header, previous)
        }

        /** Internal producers may fork a validated snapshot; network input must still use apply/prepare. */
        fun fromCommitted(state: TimelineState): TimelineStore {
            val store = TimelineStore(state.context)
            TimelineProfile.validate(state)
            TimelineCanonical.persistentSize(state)
            store.current = state
            return store
        }
    }

    fun apply(header: TimelineHeader, operations: List<TimelineOperation>): TimelineState {
        val bytes = TimelineBinary.encodeOperationsFromState(operations, formatBase(header))
        verifyBytes(header, bytes)
        return applyValidated(header, operations)
    }

    fun apply(header: TimelineHeader, operationBytes: TimelineBytes): TimelineState {
        verifyBytes(header, operationBytes)
        return applyValidated(header, TimelineBinary.decodeOperationsFromState(operationBytes, formatBase(header)))
    }

    private fun formatBase(header: TimelineHeader): TimelineState? =
        if (header.kind == TimelineTransactionKind.RESET) null else current

    private fun verifyBytes(header: TimelineHeader, bytes: TimelineBytes) {
        protocolRequire(header.operationBytes in 1..TimelineProtocol.MAX_BLOB_BYTES.toLong(), "Operation size out of range")
        protocolRequire(bytes.size.toLong() == header.operationBytes, "Operation size mismatch")
        protocolRequire(header.operationSha256.size == 32 && MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()), header.operationSha256.toByteArray()),
            "Operation digest mismatch")
    }

    private class EditingObject(val original: TimelineObject) {
        var close = original.close
        private val changed = LinkedHashMap<Int, TimelineCurve>()
        private var addedCount = 0
        val curveCount: Int get() = original.curves.size + addedCount
        fun getCurve(id: Int): TimelineCurve? = changed[id] ?: original.curves[id]
        fun containsCurve(id: Int): Boolean = changed.containsKey(id) || original.curves.containsKey(id)
        fun putCurve(id: Int, value: TimelineCurve) {
            if (!original.curves.containsKey(id) && !changed.containsKey(id)) addedCount++
            changed[id] = value
        }
        fun replaceCurves(transform: (TimelineCurve) -> TimelineCurve) {
            // Traverse the immutable base before creating the edits iterator. The second
            // pass only replaces values of newly declared keys, never changing its key set.
            for ((id, curve) in original.curves) changed[id] = transform(changed[id] ?: curve)
            for (entry in changed.entries) {
                if (!original.curves.containsKey(entry.key)) entry.setValue(transform(entry.value))
            }
        }
        fun freeze() = original.copyWithCurveEdits(close, changed)
    }

    /** Only created/actually edited objects are retained here; base lookups decode on demand. */
    private class SparseObjects(private val base: TimelineState) {
        val replacements = LinkedHashMap<Long, TimelineObject>()
        private var addedCount = 0
        private var retention: TimelinePackedRetention? = null
        val size: Int get() = (retention?.objectCount ?: base.objects.size) + addedCount
        fun contains(handle: Long): Boolean = replacements.containsKey(handle) ||
            (retention?.isRemoved(handle) != true && base.objects.containsKey(handle))
        fun get(handle: Long): TimelineObject? {
            replacements[handle]?.let { return it }
            val retained = retention
            return if (retained != null) retained.retainedObject(handle) else base.objects[handle]
        }
        fun create(handle: Long, value: TimelineObject) {
            replacements[handle] = value
            addedCount++
        }
        fun retain(from: Long): Long {
            check(retention == null && replacements.isEmpty()) { "Retain must precede sparse edits" }
            val result = TimelinePackedRetention.scan(base, from)
            retention = result
            return result.records
        }
        fun finalEdits(): TimelineSortedObjectEdits = TimelineSortedObjectEdits.snapshot(replacements, retention)
        fun removedHandles(): Set<Long> = retention?.removedHandles() ?: emptySet()
    }

    private fun applyValidated(header: TimelineHeader, operations: List<TimelineOperation>): TimelineState {
        val previous = current
        validateHeader(header, previous)
        protocolRequire(operations.size <= TimelineProtocol.MAX_OPERATIONS, "Too many operations")
        val reset = header.kind == TimelineTransactionKind.RESET
        val oldSeal = if (reset) 0L else previous!!.header.sealedThrough
        val needsRetain = !reset && header.retainedFrom > previous!!.header.retainedFrom
        val packedBase = previous?.packedLibrary
        val sparse = if (experimentalPacked && !reset && packedBase != null)
            SparseObjects(checkNotNull(previous)) else null
        // RESET retains the original temporary typed implementation in this batch.
        // A sparse-path failure never switches to that path or skips validation.
        val objects = if (sparse == null) LinkedHashMap(if (reset) emptyMap() else previous!!.objects) else null
        val edited = HashMap<Long, EditingObject>()
        val restoredEvents = HashMap<TimelineCurveKey, MutableSet<Long>>()
        var stagedRecordCount = if (sparse != null) checkNotNull(packedBase).validatedStats().records else
            checkNotNull(objects).values.sumOf { obj -> obj.curves.values.sumOf { it.segments.size.toLong() + it.events.size } }
        var assignedHighWater = if (reset) header.objectHighWater else previous!!.header.objectHighWater
        protocolRequire(operations.count { it is TimelineOperation.Retain } == if (needsRetain) 1 else 0,
            "Invalid RetainFrom count")
        if (needsRetain) protocolRequire(operations.firstOrNull() is TimelineOperation.Retain, "RetainFrom must be first")

        fun contains(handle: Long): Boolean = if (sparse != null) sparse.contains(handle) else checkNotNull(objects).containsKey(handle)
        fun objectCount(): Int = if (sparse != null) sparse.size else checkNotNull(objects).size
        fun edit(handle: Long): EditingObject = edited.getOrPut(handle) {
            val original = if (sparse != null) sparse.get(handle) else checkNotNull(objects)[handle]
            EditingObject(original ?: throw ProtocolException("Unknown observation"))
        }
        fun editRange(from: Long, until: Long) {
            protocolRequire(from in 0..TimelineProtocol.MAX_TIME && until > from, "Invalid edit interval")
            if (!reset) protocolRequire(from >= header.retainedFrom && from >= oldSeal, "Editing clipped or sealed history")
        }
        fun nextRevision(curve: TimelineCurve, expected: Long): Long {
            protocolRequire(expected == curve.curveRevision && expected < Long.MAX_VALUE, "Curve revision mismatch")
            return expected + 1
        }
        fun accountCurve(old: TimelineCurve, replacement: TimelineCurve): TimelineCurve {
            stagedRecordCount += replacement.segments.size.toLong() + replacement.events.size - old.segments.size - old.events.size
            protocolRequire(stagedRecordCount <= TimelineProtocol.MAX_CURVE_RECORDS, "Too many staged curve records")
            return replacement
        }

        for ((index, operation) in operations.withIndex()) when (operation) {
            is TimelineOperation.Create -> {
                protocolRequire(operation.handle >= 0 && !contains(operation.handle), "Duplicate or invalid observation")
                protocolRequire((operation.handle == 0L) == (operation.kind == TimelineObjectKind.ROOT), "Invalid ROOT handle")
                protocolRequire(operation.observedFrom in context.joinedAt..header.observedThrough, "Invalid observation start")
                if (!reset) protocolRequire(operation.observedFrom >= oldSeal, "Observation starts in sealed history")
                if (operation.handle != 0L) {
                    if (reset) protocolRequire(operation.handle <= header.objectHighWater, "Handle exceeds restored high water")
                    else {
                        protocolRequire(operation.handle > assignedHighWater, "Observation handle reused or unordered")
                        assignedHighWater = operation.handle
                    }
                }
                TimelineBinary.validateDescriptor(operation.kind, operation.descriptor)
                protocolRequire(objectCount() < TimelineProtocol.MAX_OBJECTS, "Too many observations")
                val created = TimelineObject(operation.handle, operation.kind, operation.observedFrom, operation.descriptor)
                if (sparse != null) sparse.create(operation.handle, created) else checkNotNull(objects)[operation.handle] = created
            }
            is TimelineOperation.Declare -> {
                val obj = edit(operation.handle)
                protocolRequire(operation.attributeId > 0 && !obj.containsCurve(operation.attributeId), "Duplicate attribute")
                val maximum = if (obj.original.kind == TimelineObjectKind.ROOT) TimelineProtocol.MAX_ROOT_ATTRIBUTES else TimelineProtocol.MAX_ATTRIBUTES
                protocolRequire(obj.curveCount < maximum, "Too many attributes")
                protocolRequire(operation.eventHighWater >= 0 &&
                    (operation.mode == TimelineMode.PULSE || operation.eventHighWater == 0L), "Invalid event high water")
                if (!reset) protocolRequire(operation.eventHighWater == 0L, "New event high water is not zero")
                TimelineBinary.validateMetadata(operation.mode, operation.metadata, context.ticksPerSecond)
                obj.putCurve(operation.attributeId, TimelineCurve(operation.attributeId, operation.mode, operation.valueType,
                    operation.criticalAttribute, operation.eventHighWater, operation.metadata))
            }
            is TimelineOperation.Splice -> {
                val obj = edit(operation.handle)
                val curve = obj.getCurve(operation.attributeId) ?: throw ProtocolException("Undeclared curve")
                protocolRequire(curve.mode != TimelineMode.PULSE, "Pulse curve requires PulseSplice")
                val revision = nextRevision(curve, operation.expectedRevision)
                editRange(operation.from, operation.until)
                protocolRequire(operation.segments.size <= TimelineProtocol.MAX_SPLICE_RECORDS, "Too many segments")
                var end = operation.from
                operation.segments.forEach { segment ->
                    TimelineBinary.validateSegment(curve.mode, curve.valueType, segment)
                    protocolRequire(segment.lo >= end && segment.hi <= operation.until, "Overlapping or out of range segment")
                    end = segment.hi
                }
                val result = ArrayList<TimelineSegment>(curve.segments.size + operation.segments.size + 1)
                curve.segments.forEach { old ->
                    if (old.hi <= operation.from || old.lo >= operation.until) result.add(old)
                    else {
                        if (old.lo < operation.from) result.add(old.clipped(old.lo, operation.from))
                        if (old.hi > operation.until) result.add(old.clipped(operation.until, old.hi))
                    }
                }
                result.addAll(operation.segments)
                result.sortBy { it.lo }
                obj.putCurve(operation.attributeId, accountCurve(curve, curve.copy(curveRevision = revision, segments = result)))
            }
            is TimelineOperation.Pulse -> {
                val obj = edit(operation.handle)
                val curve = obj.getCurve(operation.attributeId) ?: throw ProtocolException("Undeclared curve")
                protocolRequire(curve.mode == TimelineMode.PULSE, "Not a pulse curve")
                val revision = nextRevision(curve, operation.expectedRevision)
                editRange(operation.from, operation.until)
                protocolRequire(operation.events.size <= TimelineProtocol.MAX_SPLICE_RECORDS, "Too many events")
                val originalInRange = curve.events.filter { it.time >= operation.from && it.time < operation.until }.associateBy { it.eventId }
                val outside = curve.events.filter { it.time < operation.from || it.time >= operation.until }
                val outsideIds = outside.mapTo(HashSet()) { it.eventId }
                val restoreIds = if (reset) restoredEvents.getOrPut(TimelineCurveKey(operation.handle, operation.attributeId)) { HashSet() } else null
                var highWater = curve.eventHighWater
                var prior: TimelinePulse? = null
                operation.events.forEach { event ->
                    TimelineBinary.validatePulse(curve.valueType, event)
                    protocolRequire(event.time >= operation.from && event.time < operation.until && event.time <= header.observedThrough,
                        "Event outside authorized time")
                    protocolRequire(prior == null || event.time > prior!!.time ||
                        (event.time == prior!!.time && event.eventId > prior!!.eventId), "Unordered events")
                    protocolRequire(!outsideIds.contains(event.eventId), "Event ID exists outside splice")
                    if (reset) protocolRequire(event.eventId <= highWater && restoreIds!!.add(event.eventId), "Invalid restored event ID")
                    else if (originalInRange[event.eventId] != event) {
                        protocolRequire(event.eventId > highWater, "Event ID reused")
                        highWater = event.eventId
                    }
                    prior = event
                }
                val result = ArrayList<TimelinePulse>(outside.size + operation.events.size)
                result.addAll(outside)
                result.addAll(operation.events)
                result.sortWith(compareBy<TimelinePulse> { it.time }.thenBy { it.eventId })
                obj.putCurve(operation.attributeId, accountCurve(curve, curve.copy(curveRevision = revision, eventHighWater = highWater, events = result)))
            }
            is TimelineOperation.Close -> {
                val obj = edit(operation.handle)
                protocolRequire(operation.handle != 0L && obj.close == null, "Observation already closed or ROOT")
                protocolRequire(operation.untilTime in obj.original.observedFrom..header.observedThrough && operation.untilTime >= oldSeal,
                    "Invalid close time")
                obj.close = TimelineClose(operation.untilTime, operation.closeKind)
                obj.replaceCurves { curve -> accountCurve(curve, curve.copy(
                    segments = curve.segments.filter { it.lo < operation.untilTime }.map {
                        if (it.hi > operation.untilTime) it.clipped(it.lo, operation.untilTime) else it
                    }, events = curve.events.filter { it.time < operation.untilTime })) }
            }
            is TimelineOperation.Retain -> {
                protocolRequire(index == 0 && needsRetain && operation.newFrom == header.retainedFrom && operation.newFrom <= oldSeal,
                    "Invalid RetainFrom")
                if (sparse != null) stagedRecordCount = sparse.retain(operation.newFrom)
                else {
                    val full = checkNotNull(objects)
                    retain(full, operation.newFrom)
                    stagedRecordCount = full.values.sumOf { obj -> obj.curves.values.sumOf { it.segments.size.toLong() + it.events.size } }
                }
            }
        }
        protocolRequire(assignedHighWater == header.objectHighWater, "Object high water mismatch")
        val candidate = if (sparse != null) {
            edited.forEach { (handle, value) -> sparse.replacements[handle] = value.freeze() }
            // Only this fully validated operation loop may supply the replacement plan.
            // The representation builder adds full Profile(previous) and actual page/size checks.
            checkNotNull(packedBase).prepareObjectEdits(checkNotNull(previous), header, sparse.finalEdits(), sparse.removedHandles())
        } else {
            val full = checkNotNull(objects)
            edited.forEach { (handle, value) -> full[handle] = value.freeze() }
            val typed = TimelineState(context, header, full)
            TimelineProfile.validate(typed, previous)
            protocolRequire(TimelineCanonical.persistentSize(typed) <= TimelineProtocol.MAX_BLOB_BYTES,
                "Persistent timeline exceeds limit")
            if (experimentalPacked) TimelineState.fromPacked(TimelinePackedLibrary.pack(typed), previous) else typed
        }
        current = candidate
        return candidate
    }

    private fun validateHeader(header: TimelineHeader, previous: TimelineState?) {
        protocolRequire(header.streamId in 1..TimelineProtocol.MAX_U32 && header.epoch > 0 && header.revision > 0 &&
            header.baseRevision >= 0 && header.objectHighWater >= 0, "Invalid stream identity")
        protocolRequire(header.retainedFrom >= context.historyFrom && header.retainedFrom <= header.sealedThrough &&
            header.sealedThrough <= header.observedThrough && header.observedThrough <= TimelineProtocol.MAX_TIME &&
            header.retainedUntil > header.retainedFrom, "Invalid timeline watermarks")
        val maxUntil = if (header.observedThrough > TimelineProtocol.MAX_TIME - context.maxPredictionTime)
            TimelineProtocol.FOREVER else header.observedThrough + context.maxPredictionTime + 1
        protocolRequire(header.retainedUntil <= maxUntil, "Prediction window exceeds limit")
        if (context.mode == TimelineStreamMode.LIVE) {
            protocolRequire(header.controlEpoch > 0 && header.retainedUntil > header.observedThrough, "Invalid LIVE window")
        } else {
            protocolRequire(header.controlEpoch == 0L && header.retainedUntil <= header.sealedThrough, "Invalid HISTORY window")
            context.acceptedFrom?.let { protocolRequire(header.retainedFrom == it, "HISTORY lower bound changed") }
            context.acceptedUntil?.let { protocolRequire(header.retainedUntil == it, "HISTORY upper bound changed") }
        }
        if (header.kind == TimelineTransactionKind.RESET) {
            protocolRequire(header.revision == 1L && header.baseRevision == 0L, "Invalid RESET revision")
            if (previous == null) {
                protocolRequire(header.epoch == 1L, "Initial epoch must be one")
                context.acceptedFrom?.let { protocolRequire(header.retainedFrom == it, "Initial lower bound changed") }
                context.acceptedUntil?.let { protocolRequire(header.retainedUntil == it, "Initial upper bound changed") }
            } else {
                protocolRequire(header.streamId == previous.header.streamId && previous.header.epoch < Long.MAX_VALUE &&
                    header.epoch == previous.header.epoch + 1, "RESET epoch did not advance exactly once")
                if (context.mode == TimelineStreamMode.LIVE)
                    protocolRequire(header.controlEpoch > previous.header.controlEpoch, "RESET control epoch did not advance")
            }
        } else {
            protocolRequire(header.baseRevision < Long.MAX_VALUE && header.revision == header.baseRevision + 1, "Invalid UPDATE revision")
            if (previous == null) throw MissingBase()
            protocolRequire(header.streamId == previous.header.streamId && header.epoch == previous.header.epoch, "UPDATE stream identity changed")
            if (header.baseRevision != previous.header.revision) throw MissingBase()
            protocolRequire(header.observedThrough >= previous.header.observedThrough && header.sealedThrough >= previous.header.sealedThrough &&
                header.retainedFrom >= previous.header.retainedFrom && header.controlEpoch >= previous.header.controlEpoch &&
                header.objectHighWater >= previous.header.objectHighWater, "Timeline watermark regressed")
        }
    }

    private fun retain(objects: MutableMap<Long, TimelineObject>, from: Long) {
        objects.replaceAll { _, obj -> obj.copy(curves = obj.curves.mapValues { (_, curve) ->
            curve.copy(segments = curve.segments.filter { it.hi > from }.map {
                if (it.lo < from) it.clipped(from, it.hi) else it
            }, events = curve.events.filter { event ->
                protocolRequire(event.time <= Long.MAX_VALUE - curve.maxTailTime, "Event tail overflow")
                event.time >= from || event.time + curve.maxTailTime > from
            })
        }) }
        // The descriptor graph is limited to ACTION -> PANEL/UNIT and PANEL -> UNIT.
        // A reference-count queue reaches the same fixed point without quadratic rescans.
        val references = HashMap<Long, Int>()
        val edges = HashMap<Long, List<Long>>()
        objects.values.forEach { obj ->
            val targets = TimelineProfile.references(obj)
            edges[obj.handle] = targets
            targets.forEach { references[it] = (references[it] ?: 0) + 1 }
        }
        fun expired(obj: TimelineObject): Boolean {
            val close = obj.close ?: return false
            val tail = obj.curves.values.maxOfOrNull { it.maxTailTime } ?: 0L
            protocolRequire(close.until <= Long.MAX_VALUE - tail, "Observation tail overflow")
            return obj.handle != 0L && close.until + tail <= from
        }
        val eligible = java.util.PriorityQueue<Long>()
        objects.values.forEach { if (expired(it) && (references[it.handle] ?: 0) == 0) eligible.add(it.handle) }
        while (eligible.isNotEmpty()) {
            val handle = eligible.remove()
            if (objects.remove(handle) == null) continue
            edges[handle].orEmpty().forEach { target ->
                references[target] = references.getValue(target) - 1
                val obj = objects[target]
                if (obj != null && references[target] == 0 && expired(obj)) eligible.add(target)
            }
        }
    }
}
