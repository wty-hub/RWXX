package io.github.rwx.net.timeline

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Representation candidate, deliberately not installed in the production Store/Publisher.
 * pack/prepare still validate a typed input library; only context/header and owned record bytes
 * survive construction. This is not a replacement for operation/authorization validation.
 */
internal class TimelinePackedLibrary private constructor(
    val context: TimelineContext,
    val header: TimelineHeader,
    val targetPageBytes: Int,
    private val pages: Array<ByteArray>,
    private val handles: LongArray,
    private val objectPages: IntArray,
    private val offsets: IntArray,
    private val lengths: IntArray,
) {
    val objectCount: Int get() = handles.size
    val pageCount: Int get() = pages.size
    val recordBytes: Long get() = pages.sumOf { it.size.toLong() }
    val directoryBytes: Long get() = handles.size.toLong() * (8 + 4 + 4 + 4)

    fun contains(handle: Long): Boolean = handles.binarySearch(handle) >= 0
    fun objectCursor(handle: Long): TimelinePackedCursor? {
        val index = handles.binarySearch(handle)
        if (index < 0) return null
        protocolRequire(objectPages[index] in pages.indices, "Invalid packed directory page")
        return TimelinePackedCursor.ownedPage(pages[objectPages[index]], offsets[index], lengths[index])
    }
    fun objectValue(handle: Long): TimelineObject? = objectCursor(handle)?.objectValue(context.ticksPerSecond)?.also {
        protocolRequire(it.handle == handle, "Packed lookup returned a different object")
    }

    /** Directory-backed view. It never retains decoded objects, curves, or entries. */
    fun objectMap(): Map<Long, TimelineObject> = Collections.unmodifiableMap(ObjectMap(this))

    private class ObjectMap(private val library: TimelinePackedLibrary) : AbstractMap<Long, TimelineObject>() {
        override val size: Int get() = library.objectCount
        override fun containsKey(key: Long): Boolean = library.contains(key)
        override fun get(key: Long): TimelineObject? = library.objectValue(key)
        override val keys: Set<Long> = object : AbstractSet<Long>() {
            override val size: Int get() = library.objectCount
            override fun contains(element: Long): Boolean = library.contains(element)
            override fun iterator(): Iterator<Long> = object : Iterator<Long> {
                private var index = 0
                override fun hasNext(): Boolean = index < library.objectCount
                override fun next(): Long {
                    if (!hasNext()) throw NoSuchElementException()
                    return library.handles[index++]
                }
            }
        }
        override val entries: Set<Map.Entry<Long, TimelineObject>> = object : AbstractSet<Map.Entry<Long, TimelineObject>>() {
            override val size: Int get() = library.objectCount
            override fun iterator(): Iterator<Map.Entry<Long, TimelineObject>> = object : Iterator<Map.Entry<Long, TimelineObject>> {
                private var index = 0
                override fun hasNext(): Boolean = index < library.objectCount
                override fun next(): Map.Entry<Long, TimelineObject> {
                    if (!hasNext()) throw NoSuchElementException()
                    return ObjectEntry(library, library.handles[index++])
                }
            }
        }
    }

    private class ObjectEntry(private val library: TimelinePackedLibrary, override val key: Long) : Map.Entry<Long, TimelineObject> {
        override val value: TimelineObject get() = library.objectValue(key)
            ?: throw ProtocolException("Packed directory lost an object")
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && key == other.key && value == other.value
        override fun hashCode(): Int = key.hashCode() xor value.hashCode()
        override fun toString(): String = "$key=$value"
    }

    /** No cached digest, validation result, decoded graph or parent-library chain. */
    fun digest(): TimelineBytes {
        val sha = MessageDigest.getInstance("SHA-256")
        val output = BufferedOutputStream(DigestOutputStream(sink, sha), 65536)
        writeCanonical(output); output.flush()
        return TimelineBytes(sha.digest().copyOf(16))
    }

    fun writeCanonical(output: OutputStream) {
        validate()
        output.write(domain.copyOf())
        context.matchId.writeTo(output); context.accessId.writeTo(output); context.mapKey.writeTo(output)
        TimelineBinary.writeVRecord(output, context.mapRecord)
        writePersistentValidated(output)
    }

    /** Writes the exact object-count and object-record suffix of section 10.1. */
    fun writeObjectTable(output: OutputStream) {
        validate()
        TimelineBinary.writeVUInt(output, objectCount.toLong())
        writePages(output)
    }

    fun persistentSize(): Long {
        validate()
        return writePersistentValidated(sink)
    }

    fun writePersistent(output: OutputStream) {
        validate()
        writePersistentValidated(output)
    }

    private fun writePersistentValidated(output: OutputStream): Long {
        val out = TimelineBinary.LimitedOutput(output, TimelineProtocol.MAX_BLOB_BYTES.toLong())
        writePersistentHeader(out)
        writePages(out)
        return out.count
    }

    private fun writePersistentHeader(out: OutputStream) {
        fun number(value: Long) = TimelineBinary.writeVUInt(out, value)
        number(context.profileId.toLong()); number(context.teamId.toLong()); number(context.ticksPerSecond.toLong())
        number(header.streamId); number(header.epoch); number(header.revision)
        number(header.observedThrough); number(header.sealedThrough); number(header.retainedFrom)
        number(header.retainedUntil); number(header.controlEpoch); number(header.objectHighWater)
        number(objectCount.toLong())
    }

    private fun writePages(output: OutputStream) {
        // An arbitrary OutputStream may retain or mutate its array argument.
        val scratch = ByteArray(minOf(8192L, recordBytes).toInt())
        for (page in pages) {
            var at = 0
            while (at < page.size) {
                val count = minOf(scratch.size, page.size - at)
                page.copyInto(scratch, 0, at, at + count)
                output.write(scratch, 0, count)
                at += count
            }
        }
    }

    /** Re-read every actual record; directory entries cannot hide, duplicate or truncate bytes. */
    fun validate() { validatedStats() }

    /** Transaction-local scan results, never cached or inferred from a revision/header. */
    data class ValidationStats(val records: Long, val activeUnits: Int, val objectCount: Int)

    fun validatedStats(): ValidationStats {
        TimelineProfile.validateContext(context)
        protocolRequire(header.streamId in 1..TimelineProtocol.MAX_U32 && header.epoch > 0 && header.revision > 0,
            "Invalid packed state identity")
        protocolRequire(header.observedThrough in 0..TimelineProtocol.MAX_TIME && header.sealedThrough in 0..header.observedThrough &&
            header.retainedFrom in 0..header.sealedThrough && header.retainedUntil > header.retainedFrom,
            "Invalid packed state watermarks")
        protocolRequire(if (context.mode == TimelineStreamMode.LIVE) header.controlEpoch > 0 && header.retainedUntil > header.observedThrough
            else header.controlEpoch == 0L && header.retainedUntil <= header.sealedThrough, "Invalid packed stream watermarks")
        protocolRequire(header.objectHighWater >= 0 && objectCount in 1..TimelineProtocol.MAX_OBJECTS,
            "Invalid packed object count or high water")
        protocolRequire(objectPages.size == objectCount && offsets.size == objectCount && lengths.size == objectCount &&
            pages.size in 1..objectCount && targetPageBytes in MIN_PAGE_BYTES..DEFAULT_PAGE_BYTES,
            "Invalid packed directory shape")
        var payload = 0L
        for (page in pages) {
            protocolRequire(page.isNotEmpty() && page.size <= TimelineProtocol.MAX_BLOB_BYTES, "Invalid packed page size")
            payload += page.size
            protocolRequire(payload <= TimelineProtocol.MAX_BLOB_BYTES, "Packed library exceeds capacity")
        }
        val prefix = TimelineBinary.LimitedOutput(sink, TimelineProtocol.MAX_BLOB_BYTES.toLong())
        writePersistentHeader(prefix)
        protocolRequire(prefix.count <= TimelineProtocol.MAX_BLOB_BYTES - payload, "Packed persistent state exceeds capacity")
        var row = 0
        var previousHandle = -1L
        var activeUnits = 0
        var records = 0L
        for ((pageIndex, page) in pages.withIndex()) {
            val reader = TimelinePackedCursor.ownedPage(page)
            var pageObjects = 0
            while (reader.remaining > 0) {
                val start = reader.position
                val obj = reader.objectHeader()
                protocolRequire(row < objectCount && obj.handle > previousHandle && obj.handle <= header.objectHighWater &&
                    (obj.handle == 0L) == (obj.kind == TimelineObjectKind.ROOT) && (row != 0 || obj.handle == 0L),
                    "Invalid packed object order or identity")
                protocolRequire(obj.observedFrom in 0..header.observedThrough, "Invalid packed observation start")
                obj.close?.let { protocolRequire(obj.handle != 0L && it.until in obj.observedFrom..header.observedThrough,
                    "Invalid packed observation close") }
                if (obj.kind == TimelineObjectKind.UNIT && (obj.close == null || obj.close.until > header.observedThrough)) {
                    activeUnits++
                    protocolRequire(activeUnits <= TimelineProtocol.MAX_UNITS, "Too many packed active units")
                }
                var previousId = 0
                repeat(obj.curveCount) {
                    val curve = reader.curveHeader(context.ticksPerSecond)
                    protocolRequire(curve.attributeId > previousId, "Invalid packed attribute order")
                    previousId = curve.attributeId
                    records += curve.recordCount
                    protocolRequire(records <= TimelineProtocol.MAX_CURVE_RECORDS, "Too many packed records")
                    if (curve.mode == TimelineMode.PULSE) {
                        var previousTime = -1L; var previousIdAtTime = -1L
                        val ids = LongArray(curve.recordCount)
                        repeat(curve.recordCount) { index ->
                            reader.scanPulse(curve.valueType)
                            val time = reader.recordLo; val eventId = reader.recordEventId
                            protocolRequire(eventId <= curve.eventHighWater &&
                                (time > previousTime || time == previousTime && eventId > previousIdAtTime),
                                "Invalid packed pulse order or high water")
                            protocolRequire(time >= obj.observedFrom && time <= header.observedThrough &&
                                (obj.close == null || time < obj.close.until), "Packed pulse outside observation")
                            previousTime = time; previousIdAtTime = eventId; ids[index] = eventId
                        }
                        ids.sort()
                        for (index in 1 until ids.size) protocolRequire(ids[index] != ids[index - 1], "Duplicate packed event id")
                    } else {
                        var previousEnd = obj.observedFrom
                        repeat(curve.recordCount) {
                            reader.scanSegment(curve.mode, curve.valueType)
                            protocolRequire(reader.recordLo >= previousEnd && (obj.close == null || reader.recordHi <= obj.close.until),
                                "Packed segments overlap or escape observation")
                            previousEnd = reader.recordHi
                        }
                    }
                }
                protocolRequire(handles[row] == obj.handle && objectPages[row] == pageIndex && offsets[row] == start &&
                    lengths[row] == reader.position - start, "Packed directory does not describe actual records")
                previousHandle = obj.handle; row++; pageObjects++
            }
            reader.finish()
            protocolRequire(page.size <= targetPageBytes || pageObjects == 1, "Oversize packed page is not one object")
        }
        protocolRequire(row == objectCount, "Packed directory contains missing records")
        return ValidationStats(records, activeUnits, row)
    }

    /**
     * Read a single object's format without materializing its object/curve/list graph.
     * Finding the requested header does not skip validation of later records. Individual
     * cursor record scans read and validate every value without materializing its typed graph.
     */
    fun curveFormat(handle: Long, attributeId: Int): TimelineCurveFormat? {
        val reader = objectCursor(handle) ?: return null
        val obj = reader.objectHeader()
        protocolRequire(obj.handle == handle, "Packed format lookup returned a different object")
        var previousId = 0
        var records = 0L
        var result: TimelineCurveFormat? = null
        repeat(obj.curveCount) {
            val curve = reader.curveHeader(context.ticksPerSecond)
            protocolRequire(curve.attributeId > previousId, "Unordered packed attribute")
            previousId = curve.attributeId
            records += curve.recordCount
            protocolRequire(records <= TimelineProtocol.MAX_CURVE_RECORDS, "Too many packed records")
            if (curve.attributeId == attributeId) result = TimelineCurveFormat(curve.mode, curve.valueType)
            if (curve.mode == TimelineMode.PULSE) repeat(curve.recordCount) { reader.scanPulse(curve.valueType) }
            else repeat(curve.recordCount) { reader.scanSegment(curve.mode, curve.valueType) }
        }
        reader.finish()
        return result
    }

    /** Read-only identity diagnostic. Sharing compares actual bytes; no revision/hash shortcut. */
    fun sharedPagesWith(other: TimelinePackedLibrary): Int {
        val identities = IdentityHashMap<ByteArray, Boolean>()
        other.pages.forEach { identities[it] = true }
        return pages.count { identities.containsKey(it) }
    }

    /**
     * Representation-only UPDATE builder. Callers still have to validate actual operations;
     * these object edits are not a substitute for Store's splice/revision/authorization rules.
     * Only changed objects are encoded. The final view passes the complete profile gate.
     */
    fun prepareObjectEdits(previous: TimelineState, header: TimelineHeader,
                           replacements: Map<Long, TimelineObject>, removed: Set<Long> = emptySet()): TimelineState =
        prepareObjectEdits(previous, header, TimelineSortedObjectEdits.snapshot(replacements), removed)

    /** The owned provider lazily decodes Retain-only changes, never an entire edited object graph. */
    fun prepareObjectEdits(previous: TimelineState, header: TimelineHeader,
                           edits: TimelineSortedObjectEdits, removed: Set<Long> = emptySet()): TimelineState {
        protocolRequire(previous.packedLibrary === this, "Packed edit base differs from its view")
        protocolRequire(header.kind == TimelineTransactionKind.UPDATE, "Packed edits require UPDATE")
        validate()
        TimelineStore.validatePackedHeader(previous, header)
        if (header.retainedFrom > previous.header.retainedFrom)
            protocolRequire(header.retainedFrom <= previous.header.sealedThrough, "Packed retain passed old seal")
        protocolRequire(edits.size <= TimelineProtocol.MAX_OBJECTS && removed.size <= objectCount,
            "Too many packed edits")
        val deleted = HashSet(removed)
        protocolRequire(deleted.size <= objectCount, "Too many packed edits")
        for (handle in deleted) {
            protocolRequire(contains(handle) && !edits.contains(handle), "Unknown or conflicting packed removal")
            val old = checkNotNull(objectValue(handle))
            val close = old.close
            val tail = old.curves.values.maxOfOrNull { it.maxTailTime } ?: 0
            protocolRequire(handle != 0L && close != null && header.retainedFrom > previous.header.retainedFrom &&
                close.until <= Long.MAX_VALUE - tail && close.until + tail <= header.retainedFrom,
                "Packed removal discarded retained observation")
        }
        var added = 0
        var highWater = previous.header.objectHighWater
        var previousEditHandle = -1L
        for (index in 0 until edits.size) {
            val handle = edits.handleAt(index)
            val obj = edits.valueAt(index)
            protocolRequire(handle > previousEditHandle, "Packed edits are not strictly ordered")
            previousEditHandle = handle
            protocolRequire(handle == obj.handle && handle >= 0, "Packed edit key differs from object")
            val old = objectValue(handle)
            if (old == null) {
                protocolRequire(handle > highWater && obj.observedFrom >= previous.header.sealedThrough,
                    "Packed edit reused an observation identity")
                highWater = handle; added++
            } else validateReplacement(old, obj, previous.header.sealedThrough, header.retainedFrom)
        }
        protocolRequire(highWater == header.objectHighWater, "Packed edit high water mismatch")
        val count = objectCount - deleted.size + added
        protocolRequire(count in 1..TimelineProtocol.MAX_OBJECTS, "Packed edited object count exceeds limit")
        val newHandles = LongArray(count); val newPageIds = IntArray(count)
        val newOffsets = IntArray(count); val newLengths = IntArray(count)
        val newPages = ArrayList<ByteArray>()
        var buffer = ByteArrayOutputStream(targetPageBytes)
        var row = 0; var payload = 0L

        fun addPage(bytes: ByteArray) {
            payload += bytes.size
            protocolRequire(payload <= TimelineProtocol.MAX_BLOB_BYTES, "Packed edits exceed capacity")
            newPages.add(bytes)
        }
        fun flush() {
            if (buffer.size() == 0) return
            addPage(buffer.toByteArray())
            buffer = ByteArrayOutputStream(targetPageBytes)
        }
        fun append(handle: Long, bytes: ByteArray, offset: Int, length: Int) {
            protocolRequire(row < count, "Packed edit directory overflow")
            if (buffer.size() > 0 && length > targetPageBytes - buffer.size()) flush()
            newHandles[row] = handle; newPageIds[row] = newPages.size
            newOffsets[row] = buffer.size(); newLengths[row] = length
            row++
            if (length > targetPageBytes) {
                // Edits use a fresh owned array; unchanged subranges must be copied, never
                // lending a partial page or retaining bytes belonging to removed records.
                addPage(bytes.copyOfRange(offset, offset + length))
            } else buffer.write(bytes, offset, length)
        }
        var nextIndex = 0
        fun nextHandle(): Long? = if (nextIndex < edits.size) edits.handleAt(nextIndex) else null
        fun consumeEdit() {
            val handle = edits.handleAt(nextIndex)
            val obj = edits.valueAt(nextIndex)
            protocolRequire(obj.handle == handle, "Packed edit key differs from object")
            val bytes = objectRecord(obj)
            append(handle, bytes, 0, bytes.size)
            nextIndex++
        }
        var oldRow = 0
        for (pageIndex in pages.indices) {
            val first = oldRow
            while (oldRow < objectCount && objectPages[oldRow] == pageIndex) oldRow++
            val until = oldRow
            val low = handles[first]; val high = handles[until - 1]
            while (nextHandle()?.let { it < low } == true) consumeEdit()
            val affected = nextHandle()?.let { it <= high } == true || (first until until).any { handles[it] in deleted }
            if (!affected) {
                flush()
                val newPage = newPages.size
                addPage(pages[pageIndex])
                for (old in first until until) {
                    protocolRequire(row < count, "Packed edit directory overflow")
                    newHandles[row] = handles[old]; newPageIds[row] = newPage
                    newOffsets[row] = offsets[old]; newLengths[row] = lengths[old]; row++
                }
                continue
            }
            for (old in first until until) {
                val handle = handles[old]
                while (nextHandle()?.let { it < handle } == true) consumeEdit()
                if (nextHandle() == handle) consumeEdit()
                else if (handle !in deleted) append(handle, pages[pageIndex], offsets[old], lengths[old])
            }
        }
        while (nextHandle() != null) consumeEdit()
        flush()
        protocolRequire(row == count, "Packed edit directory contains missing records")
        val candidate = TimelinePackedLibrary(context, header, targetPageBytes, newPages.toTypedArray(),
            newHandles, newPageIds, newOffsets, newLengths)
        // No previous library, mutable edits, typed object graph, or parent-page chain is
        // retained by candidate. Unchanged pages may share only their immutable byte[].
        return TimelineState.fromPacked(candidate, previous)
    }

    private fun validateReplacement(old: TimelineObject, replacement: TimelineObject, sealed: Long, retained: Long) {
        protocolRequire(old.handle == replacement.handle && old.kind == replacement.kind &&
            old.observedFrom == replacement.observedFrom && old.descriptor == replacement.descriptor,
            "Packed edit changed immutable observation identity")
        protocolRequire(old.close == null || old.close == replacement.close, "Packed edit reopened an observation")
        if (old.close == null) replacement.close?.let {
            protocolRequire(it.until >= sealed, "Packed edit closed sealed history")
        }
        protocolRequire(old.curves.keys.all { it in replacement.curves }, "Packed edit removed a declaration")
        for ((id, curve) in replacement.curves) {
            val before = old.curves[id]
            if (before != null) {
                protocolRequire(before.mode == curve.mode && before.valueType == curve.valueType &&
                    before.criticalAttribute == curve.criticalAttribute && before.metadata == curve.metadata &&
                    curve.curveRevision >= before.curveRevision && curve.eventHighWater >= before.eventHighWater,
                    "Packed edit changed a declaration or regressed identity")
            }
            fun prefix(segments: List<TimelineSegment>): List<TimelineSegment> = segments.mapNotNull { segment ->
                val lo = maxOf(retained, segment.lo); val hi = minOf(sealed, segment.hi)
                if (lo < hi) segment.clipped(lo, hi) else null
            }
            fun events(source: TimelineCurve?): List<TimelinePulse> {
                if (source == null) return emptyList()
                return source.events.filter {
                    protocolRequire(it.time <= Long.MAX_VALUE - source.maxTailTime, "Packed event tail overflow")
                    it.time < sealed && (it.time >= retained || it.time + source.maxTailTime > retained)
                }
            }
            protocolRequire(prefix(before?.segments ?: emptyList()) == prefix(curve.segments) && events(before) == events(curve),
                "Packed edit changed sealed records")
        }
    }

    /** Candidate-only representation owner, not a wire transaction/authorization processor. */
    class Owner(initial: TimelineState, targetPageBytes: Int = DEFAULT_PAGE_BYTES) {
        private val thread = Thread.currentThread()
        var current: TimelinePackedLibrary = pack(initial, targetPageBytes)
            private set
        fun prepare(next: TimelineState): Prepared {
            check(Thread.currentThread() === thread) { "Packed owner used by another thread" }
            protocolRequire(next.context == current.context, "Packed preparation crossed context")
            val base = current
            return Prepared(this, base, build(next, base.targetPageBytes, base))
        }
        fun prepareObjectEdits(header: TimelineHeader, replacements: Map<Long, TimelineObject>,
                               removed: Set<Long> = emptySet()): Prepared {
            check(Thread.currentThread() === thread) { "Packed owner used by another thread" }
            val base = current
            val previous = TimelineState.fromPacked(base)
            val candidate = base.prepareObjectEdits(previous, header, replacements, removed)
            return Prepared(this, base, checkNotNull(candidate.packedLibrary))
        }
        class Prepared internal constructor(private val owner: Owner, private var base: TimelinePackedLibrary?,
                                            val state: TimelinePackedLibrary) {
            private var committed = false
            fun commit(): TimelinePackedLibrary {
                check(Thread.currentThread() === owner.thread) { "Packed owner used by another thread" }
                if (committed || owner.current !== base) {
                    base = null
                    throw ProtocolException("Packed preparation is stale or already committed")
                }
                owner.current = state; committed = true; base = null
                return state
            }
        }
    }

    companion object {
        const val DEFAULT_PAGE_BYTES = 65536
        private const val MIN_PAGE_BYTES = 128
        private val domain = "RWXT-STATE-7\u0000".toByteArray(Charsets.US_ASCII)
        private val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }

        fun pack(state: TimelineState, targetPageBytes: Int = DEFAULT_PAGE_BYTES): TimelinePackedLibrary =
            build(state, targetPageBytes, null)

        private fun build(state: TimelineState, target: Int, previous: TimelinePackedLibrary?): TimelinePackedLibrary {
            protocolRequire(target in MIN_PAGE_BYTES..DEFAULT_PAGE_BYTES, "Invalid packed target page size")
            TimelineProfile.validateContext(state.context)
            TimelineProfile.validate(state)
            TimelineCanonical.persistentSize(state)
            previous?.validate()
            val count = state.objects.size
            val handles = LongArray(count); val pageIds = IntArray(count)
            val offsets = IntArray(count); val lengths = IntArray(count)
            val pages = ArrayList<ByteArray>()
            var buffer = ByteArrayOutputStream(target)
            var firstRow = 0; var row = 0; var payload = 0L

            fun retainOrOwn(page: ByteArray) {
                payload += page.size
                protocolRequire(payload <= TimelineProtocol.MAX_BLOB_BYTES, "Packed records exceed capacity")
                val oldRow = previous?.handles?.binarySearch(handles[firstRow]) ?: -1
                val shared = if (previous != null && oldRow >= 0 && previous.offsets[oldRow] == 0) {
                    val candidate = previous.pages[previous.objectPages[oldRow]]
                    candidate.takeIf { it.contentEquals(page) }
                } else null
                pages.add(shared ?: page)
                firstRow = row
            }
            fun flush() {
                if (buffer.size() == 0) return
                retainOrOwn(buffer.toByteArray())
                buffer = ByteArrayOutputStream(target)
            }

            for ((handle, obj) in TimelineCanonical.orderedObjects(state.objects)) {
                val bytes = objectRecord(obj)
                if (buffer.size() > 0 && bytes.size > target - buffer.size()) flush()
                handles[row] = handle; pageIds[row] = pages.size
                offsets[row] = buffer.size(); lengths[row] = bytes.size
                if (bytes.size > target) {
                    row++
                    retainOrOwn(bytes)
                } else {
                    buffer.write(bytes); row++
                }
            }
            flush()
            return TimelinePackedLibrary(state.context, state.header, target, pages.toTypedArray(),
                handles, pageIds, offsets, lengths).also { it.validate() }
        }

        private fun objectRecord(obj: TimelineObject): ByteArray {
            val bytes = ByteArrayOutputStream()
            val out = TimelineBinary.LimitedOutput(bytes, TimelineProtocol.MAX_BLOB_BYTES.toLong())
            TimelineBinary.writeVUInt(out, obj.handle); out.write(obj.kind.code)
            TimelineBinary.writeVUInt(out, obj.observedFrom)
            out.write(if (obj.close == null) 0 else 1)
            obj.close?.let { TimelineBinary.writeVUInt(out, it.until); out.write(it.kind.code) }
            TimelineBinary.writeVRecord(out, obj.descriptor)
            TimelineBinary.writeVUInt(out, obj.curves.size.toLong())
            for ((id, curve) in TimelineCanonical.orderedCurves(obj.curves)) {
                TimelineBinary.writeVUInt(out, id.toLong()); out.write(curve.mode.code); out.write(curve.valueType.code)
                out.write(if (curve.criticalAttribute) 1 else 0)
                TimelineBinary.writeVUInt(out, curve.eventHighWater); TimelineBinary.writeVRecord(out, curve.metadata)
                TimelineBinary.writeVUInt(out, curve.curveRevision)
                if (curve.mode == TimelineMode.PULSE) {
                    TimelineBinary.writeVUInt(out, curve.events.size.toLong())
                    curve.events.forEach { TimelineBinary.writePulse(out, curve.valueType, it) }
                } else {
                    TimelineBinary.writeVUInt(out, curve.segments.size.toLong())
                    curve.segments.forEach { TimelineBinary.writeSegment(out, curve.mode, curve.valueType, it) }
                }
            }
            return bytes.toByteArray()
        }
    }
}
