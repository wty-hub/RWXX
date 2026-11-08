package io.github.rwx.net.timeline

import java.io.OutputStream
import java.io.BufferedOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Full retained-state hashing. No dirty-field cache or render sample participates. */
object TimelineCanonical {
    private val domain = "RWXT-STATE-7\u0000".toByteArray(Charsets.US_ASCII)
    private val sink = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    fun digest(state: TimelineState): TimelineBytes {
        val sha = MessageDigest.getInstance("SHA-256")
        val out = BufferedOutputStream(DigestOutputStream(sink, sha), 65536)
        write(state, out)
        out.flush()
        return TimelineBytes(sha.digest().copyOf(16))
    }

    fun sha256(bytes: TimelineBytes): TimelineBytes {
        val sha = MessageDigest.getInstance("SHA-256")
        bytes.writeTo(DigestOutputStream(sink, sha))
        return TimelineBytes(sha.digest())
    }

    fun mapKey(record: TimelineRecord): TimelineBytes {
        TimelineBinary.validateMapRecord(record)
        val sha = MessageDigest.getInstance("SHA-256")
        val out = BufferedOutputStream(DigestOutputStream(sink, sha), 65536)
        TimelineBinary.writeRecord(record, out)
        out.flush()
        return TimelineBytes(sha.digest())
    }

    fun matches(a: TimelineBytes, b: TimelineBytes): Boolean =
        a.size == b.size && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Writes section 10.1 in order without constructing a full-state byte array. */
    fun write(state: TimelineState, output: OutputStream) {
        state.packedLibrary?.let { it.writeCanonical(output); return }
        validateContext(state.context)
        val context = state.context
        output.write(domain.copyOf())
        context.matchId.writeTo(output)
        context.accessId.writeTo(output)
        context.mapKey.writeTo(output)
        TimelineBinary.validateMapRecord(context.mapRecord)
        TimelineBinary.writeVRecord(output, context.mapRecord)
        writePersistent(state, output)
    }

    /** Section 10.1 items 3–5 only; the map and fixed identity prefix are separate. */
    fun persistentSize(state: TimelineState): Long =
        state.packedLibrary?.persistentSize() ?: writePersistentBounded(state, sink)

    fun writePersistent(state: TimelineState, output: OutputStream) {
        state.packedLibrary?.let { it.writePersistent(output); return }
        writePersistentBounded(state, output)
    }

    // Model maps own immutable LinkedHashMap copies. Most production publications already
    // insert natural-order keys, so preserve that traversal without rebuilding a TreeMap.
    // Never infer order from map type/comparator, and never cache validation or digest results.
    // Callers pass only the model's frozen maps, never mutable transaction overlays.
    internal fun orderedObjects(values: Map<Long, TimelineObject>): Set<Map.Entry<Long, TimelineObject>> {
        val entries = values.entries
        if (entries.size < 2) return entries
        var first = true
        var previous = 0L
        for (entry in entries) {
            val key = entry.key
            if (!first && key <= previous) return values.toSortedMap().entries
            first = false
            previous = key
        }
        return entries
    }

    internal fun orderedCurves(values: Map<Int, TimelineCurve>): Set<Map.Entry<Int, TimelineCurve>> {
        val entries = values.entries
        if (entries.size < 2) return entries
        var first = true
        var previous = 0
        for (entry in entries) {
            val key = entry.key
            if (!first && key <= previous) return values.toSortedMap().entries
            first = false
            previous = key
        }
        return entries
    }

    private fun validateContext(context: TimelineContext) {
        protocolRequire(context.matchId.size == 16 && context.accessId.size == 16 && context.mapKey.size == 32, "Invalid context identity size")
        protocolRequire(context.profileId == 1 && context.teamId in 0..63 && context.ticksPerSecond in 1..1000, "Invalid timeline context")
        protocolRequire(context.maxPredictionTime in 1..context.ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS, "Invalid prediction bound")
    }

    private fun writePersistentBounded(state: TimelineState, output: OutputStream): Long {
        validateContext(state.context)
        val context = state.context
        val header = state.header
        protocolRequire(header.streamId in 1..TimelineProtocol.MAX_U32 && header.epoch > 0 && header.revision > 0, "Invalid state identity")
        protocolRequire(header.observedThrough in 0..TimelineProtocol.MAX_TIME && header.sealedThrough in 0..header.observedThrough &&
            header.retainedFrom in 0..header.sealedThrough && header.retainedUntil > header.retainedFrom, "Invalid state watermarks")
        protocolRequire(if (context.mode == TimelineStreamMode.LIVE) header.controlEpoch > 0 && header.retainedUntil > header.observedThrough
            else header.controlEpoch == 0L && header.retainedUntil <= header.sealedThrough, "Invalid stream mode watermarks")
        protocolRequire(header.objectHighWater >= 0, "Invalid object high water")
        protocolRequire(state.objects.size <= TimelineProtocol.MAX_OBJECTS && state.objects[0]?.kind == TimelineObjectKind.ROOT, "Invalid object set")
        val out = TimelineBinary.LimitedOutput(output, TimelineProtocol.MAX_BLOB_BYTES.toLong())
        fun unsigned(value: Long) = TimelineBinary.writeVUInt(out, value)
        unsigned(context.profileId.toLong()); unsigned(context.teamId.toLong()); unsigned(context.ticksPerSecond.toLong())
        unsigned(header.streamId); unsigned(header.epoch); unsigned(header.revision)
        unsigned(header.observedThrough); unsigned(header.sealedThrough); unsigned(header.retainedFrom)
        unsigned(header.retainedUntil); unsigned(header.controlEpoch); unsigned(header.objectHighWater)
        unsigned(state.objects.size.toLong())
        var totalRecords = 0L
        var activeUnits = 0
        for ((handle, item) in orderedObjects(state.objects)) {
            protocolRequire(handle == item.handle && handle in 0..header.objectHighWater &&
                (handle == 0L) == (item.kind == TimelineObjectKind.ROOT), "Invalid object identity")
            protocolRequire(item.observedFrom in 0..header.observedThrough, "Invalid observation start")
            if (item.kind == TimelineObjectKind.UNIT && (item.close == null || item.close.until > header.observedThrough)) {
                activeUnits++
                protocolRequire(activeUnits <= TimelineProtocol.MAX_UNITS, "Too many active units")
            }
            TimelineBinary.validateDescriptor(item.kind, item.descriptor)
            unsigned(handle); out.write(item.kind.code); unsigned(item.observedFrom)
            out.write(if (item.close == null) 0 else 1)
            item.close?.let { close ->
                protocolRequire(handle != 0L && close.until in item.observedFrom..header.observedThrough, "Invalid observation close")
                unsigned(close.until); out.write(close.kind.code)
            }
            TimelineBinary.writeVRecord(out, item.descriptor)
            protocolRequire(item.curves.size <= if (item.kind == TimelineObjectKind.ROOT) TimelineProtocol.MAX_ROOT_ATTRIBUTES else TimelineProtocol.MAX_ATTRIBUTES, "Too many attributes")
            unsigned(item.curves.size.toLong())
            for ((attributeId, curve) in orderedCurves(item.curves)) {
                protocolRequire(attributeId == curve.attributeId && attributeId > 0 && curve.curveRevision >= 0 && curve.eventHighWater >= 0, "Invalid curve identity")
                TimelineBinary.validateFormat(curve.mode, curve.valueType)
                TimelineBinary.validateMetadata(curve.mode, curve.metadata, context.ticksPerSecond)
                unsigned(attributeId.toLong()); out.write(curve.mode.code); out.write(curve.valueType.code)
                out.write(if (curve.criticalAttribute) 1 else 0)
                unsigned(curve.eventHighWater)
                TimelineBinary.writeVRecord(out, curve.metadata)
                unsigned(curve.curveRevision)
                if (curve.mode == TimelineMode.PULSE) {
                    protocolRequire(curve.segments.isEmpty(), "PULSE contains segments")
                    unsigned(curve.events.size.toLong())
                    totalRecords += curve.events.size
                    protocolRequire(totalRecords <= TimelineProtocol.MAX_CURVE_RECORDS, "Too many curve records")
                    var previousTime = -1L
                    var previousId = -1L
                    val ids = HashSet<Long>()
                    for (event in curve.events) {
                        protocolRequire(event.eventId <= curve.eventHighWater && ids.add(event.eventId) &&
                            (event.time > previousTime || event.time == previousTime && event.eventId > previousId), "Invalid pulse order or high water")
                        protocolRequire(event.time >= item.observedFrom && event.time <= header.observedThrough &&
                            (item.close == null || event.time < item.close.until), "Pulse outside observation")
                        previousTime = event.time; previousId = event.eventId
                        TimelineBinary.writePulse(out, curve.valueType, event)
                    }
                } else {
                    protocolRequire(curve.eventHighWater == 0L && curve.events.isEmpty(), "Non-PULSE has event state")
                    unsigned(curve.segments.size.toLong())
                    totalRecords += curve.segments.size
                    protocolRequire(totalRecords <= TimelineProtocol.MAX_CURVE_RECORDS, "Too many curve records")
                    var previousEnd = item.observedFrom
                    for (segment in curve.segments) {
                        protocolRequire(segment.lo >= previousEnd && (item.close == null || segment.hi <= item.close.until), "Segments overlap or escape observation")
                        previousEnd = segment.hi
                        TimelineBinary.writeSegment(out, curve.mode, curve.valueType, segment)
                    }
                }
            }
        }
        return out.count
    }
}
