package io.github.rwx.net.timeline

import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest

/** A self-contained, indexed, permanently read-only v7 timeline archive. */
object TimelineArchive {
    const val CONTAINER_VERSION = 3
    const val MAGIC = 0x52575852
    const val MAX_HEADER_BYTES = 65536
    const val MAX_INDEX_ENTRIES = 1_048_576
    private const val INDEX_ENTRY_BYTES = 60
    private val nullOutput = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    data class IndexEntry(val from: Long, val until: Long, val fileOffset: Long,
                          val recordBytes: Int, val sha256: TimelineBytes)

    class ReadOnlySlice internal constructor(val state: TimelineState, val archivedControlEpoch: Long) {
        val readOnly: Boolean get() = true
        val from: Long get() = state.header.retainedFrom
        val until: Long get() = state.header.retainedUntil
        fun sample(handle: Long, attributeId: Int, time: Long): TimelineValue? =
            TimelineProfile.sample(state, handle, attributeId, time)
    }

    private data class Slice(val from: Long, val until: Long, val observedThrough: Long,
                             val controlEpoch: Long, val operations: TimelineBytes,
                             val sha256: TimelineBytes, val objectHighWater: Long)

    /** close() aborts an unfinished recording; only finish() publishes the final path. */
    class Writer(val path: Path, val context: TimelineContext) : AutoCloseable {
        private val target = path.toAbsolutePath().normalize()
        private val temporary: Path
        private val channel: FileChannel
        private val out: DataOutputStream
        private val entries = ArrayList<IndexEntry>()
        private val indexLock = Any()
        @Volatile private var closed = false
        @Volatile private var failed = false
        @Volatile internal var beforeAppendWrite: (() -> Unit)? = null
        @Volatile var completed: Boolean = false
            private set

        /** Only entries whose complete bytes were written are visible to online readers. */
        val committedUntil: Long get() = synchronized(indexLock) { entries.lastOrNull()?.until ?: context.historyFrom }
        val committedEntries: Int get() = synchronized(indexLock) { entries.size }

        init {
            TimelineProfile.validateContext(context)
            protocolRequire(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Archive destination already exists")
            temporary = Files.createTempFile(target.parent, ".rwx-timeline-", ".partial")
            var opened: FileChannel? = null
            try {
                channel = FileChannel.open(temporary, StandardOpenOption.WRITE)
                opened = channel
                out = DataOutputStream(Channels.newOutputStream(channel))
                val header = headerRecord(context)
                val headerBytes = TimelineBinary.recordSize(header)
                protocolRequire(headerBytes <= MAX_HEADER_BYTES, "Archive header too large")
                out.writeInt(MAGIC); out.writeShort(CONTAINER_VERSION); out.writeShort(TimelineProtocol.VERSION)
                out.writeInt(headerBytes); TimelineBinary.writeRecord(header, out)
                out.writeLong(TimelineBinary.recordSize(context.mapRecord).toLong())
                TimelineBinary.writeRecord(context.mapRecord, out)
            } catch (failure: Throwable) {
                runCatching { opened?.close() }
                runCatching { Files.deleteIfExists(temporary) }
                throw failure
            }
        }

        /** The source is an already committed authorized state, never a game-world snapshot. */
        @Synchronized
        fun append(source: TimelineState, from: Long, until: Long): IndexEntry {
            ensureWritable()
            try {
                protocolRequire(entries.size < MAX_INDEX_ENTRIES, "Archive index entry limit exceeded")
                protocolRequire(entries.lastOrNull()?.until?.let { from >= it } != false, "Archive slices overlap or regress")
                val normalized = sliceState(source, context, from, until)
                val operations = TimelineBinary.encodeOperations(TimelineEncoder.resetOperations(normalized))
                val header = normalized.header.copy(operationBytes = operations.size.toLong(),
                    operationSha256 = TimelineCanonical.sha256(operations))
                // Prove that this slice needs no previous slice, including typed
                // declarations and reset-normalized curve revision counters.
                TimelineStore(normalized.context).apply(header, operations)
                val slice = Slice(from, until, source.header.observedThrough, source.header.controlEpoch,
                    operations, sliceDigest(from, until, source.header.observedThrough,
                        source.header.controlEpoch, source.header.objectHighWater, operations), source.header.objectHighWater)
                val payload = sliceRecord(slice)
                val offset = channel.position()
                val size = TimelineBinary.recordSize(payload)
                beforeAppendWrite?.invoke()
                val checksum = writeRecord(1, size) { TimelineBinary.writeRecord(payload, it) }
                val entry = IndexEntry(from, until, offset, size + 5, checksum)
                synchronized(indexLock) { entries.add(entry) }
                return entry
            } catch (failure: Throwable) {
                failed = true
                throw failure
            }
        }

        /** Atomically publishes by creating a new link; a raced existing path is never replaced. */
        @Synchronized
        fun finish() {
            ensureWritable()
            try {
                val indexOffset = channel.position()
                val payloadSize = 4L + entries.size.toLong() * INDEX_ENTRY_BYTES
                protocolRequire(payloadSize + 1 <= TimelineProtocol.MAX_BLOB_BYTES, "Archive index too large")
                val indexSha = writeRecord(2, payloadSize.toInt()) { target ->
                    val data = DataOutputStream(target)
                    data.writeInt(entries.size)
                    for (entry in entries) {
                        data.writeLong(entry.from); data.writeLong(entry.until); data.writeLong(entry.fileOffset)
                        data.writeInt(entry.recordBytes); entry.sha256.writeTo(data)
                    }
                }
                writeRecord(3, 40) { target ->
                    val data = DataOutputStream(target)
                    data.writeLong(indexOffset); indexSha.writeTo(data)
                }
                out.flush()
                channel.force(true)
                out.close()
                // createLink fails if target exists, including a symlink. A
                // temporary file on the same filesystem avoids rename-overwrite
                // races and exposes no incomplete archive at the final name.
                synchronized(indexLock) {
                    Files.createLink(target, temporary)
                    completed = true; closed = true
                    runCatching { Files.deleteIfExists(temporary) }
                }
            } catch (failure: Throwable) {
                failed = true
                close()
                throw failure
            }
        }

        private fun writeRecord(type: Int, payloadBytes: Int, write: (OutputStream) -> Unit): TimelineBytes {
            protocolRequire(payloadBytes >= 0 && payloadBytes.toLong() + 1 <= TimelineProtocol.MAX_BLOB_BYTES, "Archive record too large")
            val digest = MessageDigest.getInstance("SHA-256")
            val hashing = DigestOutputStream(out, digest)
            val prefix = DataOutputStream(hashing)
            prefix.writeInt(payloadBytes + 1); prefix.writeByte(type)
            val bounded = TimelineBinary.LimitedOutput(hashing, payloadBytes.toLong())
            write(bounded)
            protocolRequire(bounded.count == payloadBytes.toLong(), "Archive record length changed")
            return TimelineBytes(digest.digest())
        }

        private fun ensureWritable() = protocolRequire(!closed && !failed && !completed, "Archive writer is closed or failed")

        /**
         * Reads one immutable committed entry through a private file handle. Disk reads never take
         * the append monitor; an in-progress or blocked append cannot delay an older committed seek.
         * Call from an archive worker, never from a socket feed or simulation owner.
         */
        fun seekCommitted(time: Long): ReadOnlySlice? {
            protocolRequire(time in context.historyFrom..TimelineProtocol.MAX_TIME, "Invalid committed seek time")
            val selected = synchronized(indexLock) {
                protocolRequire(!failed && (!closed || completed), "Archive writer is closed or failed")
                val entry = findEntry(entries, time) ?: return null
                // Pin the readable inode before finish/abort can remove its private name.
                RandomAccessFile((if (completed) target else temporary).toFile(), "r") to entry
            }
            return selected.first.use { loadSlice(it, context, selected.second) }
        }

        /** Fixed, authorized HISTORY window assembled lazily from committed slices only. */
        fun readCommitted(from: Long, until: Long, maxBytes: Long = TimelineProtocol.MAX_BLOB_BYTES.toLong()): ReadOnlySlice? {
            protocolRequire(from >= context.historyFrom && from < until && until <= TimelineProtocol.MAX_TIME &&
                until - from <= context.ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS * 120,
                "Invalid committed history window")
            protocolRequire(maxBytes in 1..TimelineProtocol.MAX_BLOB_BYTES.toLong(), "Invalid history byte budget")
            val selected = synchronized(indexLock) {
                protocolRequire(!failed && (!closed || completed), "Archive writer is closed or failed")
                val list = ArrayList<IndexEntry>()
                var position = from
                while (position < until) {
                    val entry = findEntry(entries, position) ?: return null
                    protocolRequire(list.size < 4096, "History read spans too many archive entries")
                    list.add(entry); position = minOf(until, entry.until)
                }
                RandomAccessFile((if (completed) target else temporary).toFile(), "r") to list
            }
            return selected.first.use { file ->
                val merger = SliceMerger(context, from, until, maxBytes)
                for (entry in selected.second) {
                    val slice = loadSlice(file, context, entry)
                    merger.add(sliceState(slice.state, context, maxOf(from, entry.from), minOf(until, entry.until)))
                }
                merger.finish()
            }
        }

        @Synchronized
        override fun close() {
            if (!closed) {
                closed = true
                runCatching { out.close() }
            }
            synchronized(indexLock) { if (!completed) runCatching { Files.deleteIfExists(temporary) } }
        }
    }

    /** Opening verifies every record boundary and checksum, but loads slice state only on seek. */
    class Reader internal constructor(private val file: RandomAccessFile, val context: TimelineContext,
                                      entries: List<IndexEntry>) : AutoCloseable {
        val index: List<IndexEntry> = frozenList(entries)
        private var closed = false
        val readOnly: Boolean get() = true

        @Synchronized
        fun seek(time: Long): ReadOnlySlice? {
            protocolRequire(!closed, "Archive reader is closed")
            protocolRequire(time in 0..TimelineProtocol.MAX_TIME, "Invalid seek time")
            return findEntry(index, time)?.let { loadSlice(file, context, it) }
        }

        @Synchronized
        override fun close() {
            if (!closed) { closed = true; file.close() }
        }
    }

    private fun findEntry(entries: List<IndexEntry>, time: Long): IndexEntry? {
        var low = 0; var high = entries.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val entry = entries[middle]
            when { time < entry.from -> high = middle - 1; time >= entry.until -> low = middle + 1; else -> return entry }
        }
        return null
    }

    private fun loadSlice(file: RandomAccessFile, context: TimelineContext, entry: IndexEntry): ReadOnlySlice {
        file.seek(entry.fileOffset)
        val recordLength = readRecordLength(file)
        protocolRequire(recordLength.toLong() + 4 == entry.recordBytes.toLong() && file.readUnsignedByte() == 1, "Indexed slice boundary changed")
        val payload = readBytes(file, recordLength - 1)
        protocolRequire(TimelineCanonical.matches(recordDigest(recordLength, 1, payload), entry.sha256), "Indexed slice digest changed")
        val slice = readSlice(payload, context)
        protocolRequire(slice.from == entry.from && slice.until == entry.until, "Indexed slice interval changed")
        val historical = context.copy(mode = TimelineStreamMode.HISTORY, acceptedFrom = slice.from, acceptedUntil = slice.until)
        val header = TimelineHeader(1, 1, TimelineTransactionKind.RESET, 1, 0, slice.observedThrough,
            slice.until, slice.from, slice.until, 0, slice.operations.size.toLong(), TimelineCanonical.sha256(slice.operations), slice.objectHighWater)
        return ReadOnlySlice(TimelineStore(historical).apply(header, slice.operations), slice.controlEpoch)
    }

    /** Mutable only on one history worker. Bounds apply before growing each record collection. */
    private class SliceMerger(val context: TimelineContext, val from: Long, val until: Long, val maxBytes: Long) {
        private class MergedObject(val original: TimelineObject) {
            var close = original.close
            val curves = LinkedHashMap<Int, MergedCurve>()
        }
        private class MergedCurve(val original: TimelineCurve) {
            var highWater = original.eventHighWater
            val segments = ArrayList<TimelineSegment>()
            val events = LinkedHashMap<Long, TimelinePulse>()
        }
        private val objects = LinkedHashMap<Long, MergedObject>()
        private var observed = 0L; private var highWater = 0L; private var records = 0L
        private var estimatedBytes = 128L
        private fun charge(bytes: Long) {
            protocolRequire(bytes <= maxBytes - estimatedBytes, "History window exceeds byte budget")
            estimatedBytes += bytes
        }
        private fun record() { protocolRequire(++records <= TimelineProtocol.MAX_CURVE_RECORDS, "History record budget exceeded") }
        private fun encodedSize(write: (OutputStream) -> Unit): Long {
            var count = 0L
            write(object : OutputStream() {
                override fun write(value: Int) { count++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
            })
            return count
        }
        private fun segmentSize(curve: TimelineCurve, value: TimelineSegment) = encodedSize {
            TimelineBinary.writeSegment(it, curve.mode, curve.valueType, value)
        }

        fun add(state: TimelineState) {
            observed = maxOf(observed, state.header.observedThrough); highWater = maxOf(highWater, state.header.objectHighWater)
            for ((handle, obj) in state.objects) {
                var merged = objects[handle]
                if (merged == null) {
                    protocolRequire(objects.size < TimelineProtocol.MAX_OBJECTS, "History object budget exceeded")
                    charge(48L + TimelineBinary.recordSize(obj.descriptor))
                    merged = MergedObject(obj); objects[handle] = merged
                } else {
                    protocolRequire(merged.original.kind == obj.kind && merged.original.observedFrom == obj.observedFrom &&
                        merged.original.descriptor == obj.descriptor, "Archive observation identity changed")
                    protocolRequire(merged.close == null || merged.close == obj.close, "Archive observation reopened or changed close")
                    if (obj.close != null) merged.close = obj.close
                }
                for ((id, curve) in obj.curves) {
                    var target = merged.curves[id]
                    if (target == null) {
                        protocolRequire(merged.curves.size < if (handle == 0L) TimelineProtocol.MAX_ROOT_ATTRIBUTES else TimelineProtocol.MAX_ATTRIBUTES,
                            "History attribute budget exceeded")
                        charge(48L + TimelineBinary.recordSize(curve.metadata))
                        target = MergedCurve(curve); merged.curves[id] = target
                    } else protocolRequire(target.original.mode == curve.mode && target.original.valueType == curve.valueType &&
                        target.original.criticalAttribute == curve.criticalAttribute && target.original.metadata == curve.metadata,
                        "Archive attribute declaration changed")
                    target.highWater = maxOf(target.highWater, curve.eventHighWater)
                    for (segment in curve.segments) {
                        val prior = target.segments.lastOrNull()
                        protocolRequire(prior == null || prior.hi <= segment.lo, "Archive segment overlap")
                        if (prior != null && prior.hi == segment.lo && prior.clipped(segment.lo, segment.hi) == segment) {
                            val combined = prior.clipped(prior.lo, segment.hi)
                            charge(segmentSize(curve, combined) - segmentSize(curve, prior))
                            target.segments[target.segments.lastIndex] = combined
                        } else { charge(segmentSize(curve, segment)); record(); target.segments.add(segment) }
                    }
                    for (event in curve.events) {
                        val prior = target.events[event.eventId]
                        if (prior != null) protocolRequire(prior == event, "Archive pulse identity changed")
                        else {
                            charge(encodedSize { TimelineBinary.writePulse(it, curve.valueType, event) }); record()
                            target.events[event.eventId] = event
                        }
                    }
                }
            }
        }

        fun finish(): ReadOnlySlice {
            val historical = context.copy(mode = TimelineStreamMode.HISTORY, acceptedFrom = from, acceptedUntil = until)
            val values = objects.mapValues { (_, merged) -> merged.original.copy(close = merged.close,
                curves = merged.curves.mapValues { (_, curve) -> curve.original.copy(curveRevision = 0,
                    eventHighWater = curve.highWater, segments = curve.segments,
                    events = curve.events.values.sortedWith(compareBy<TimelinePulse> { it.time }.thenBy { it.eventId })) }) }
            val placeholder = TimelineBytes(byteArrayOf(0))
            val header = TimelineHeader(1, 1, TimelineTransactionKind.RESET, 1, 0, observed, until, from, until,
                0, 1, TimelineCanonical.sha256(placeholder), highWater)
            val desired = TimelineState(historical, header, values)
            TimelineProfile.validate(desired)
            protocolRequire(TimelineCanonical.persistentSize(desired) <= maxBytes, "History window exceeds byte budget")
            val bytes = TimelineBinary.encodeOperations(TimelineEncoder.resetOperations(desired))
            protocolRequire(bytes.size.toLong() <= maxBytes, "History checkpoint exceeds byte budget")
            val state = TimelineStore(historical).apply(header.copy(operationBytes = bytes.size.toLong(),
                operationSha256 = TimelineCanonical.sha256(bytes)), bytes)
            return ReadOnlySlice(state, 0)
        }
    }

    fun open(path: Path): Reader {
        val file = RandomAccessFile(path.toFile(), "r")
        try {
            protocolRequire(file.length() >= 12, "Truncated archive header")
            protocolRequire(file.readInt() == MAGIC && file.readUnsignedShort() == CONTAINER_VERSION &&
                file.readUnsignedShort() == TimelineProtocol.VERSION, "Unsupported archive format")
            val headerLength = unsignedInt(file)
            protocolRequire(headerLength in 1..MAX_HEADER_BYTES.toLong(), "Invalid archive header length")
            val header = TimelineBinary.decodeRecord(readBytes(file, headerLength.toInt()))
            val mapLength = file.readLong()
            protocolRequire(mapLength in 1..TimelineProtocol.MAX_BLOB_BYTES.toLong(), "Invalid archive map length")
            val map = TimelineBinary.decodeRecord(readBytes(file, mapLength.toInt()))
            val context = readHeader(header, map)
            TimelineProfile.validateContext(context)
            val entries = ArrayList<IndexEntry>()
            var foundIndex = false
            var indexOffset = -1L
            var indexSha: TimelineBytes? = null
            while (file.filePointer < file.length()) {
                val offset = file.filePointer
                val length = readRecordLength(file)
                val type = file.readUnsignedByte()
                when (type) {
                    1 -> {
                        protocolRequire(!foundIndex && entries.size < MAX_INDEX_ENTRIES, "Slice after index or excessive slices")
                        val payload = readBytes(file, length - 1)
                        val slice = readSlice(payload, context)
                        protocolRequire(entries.lastOrNull()?.until?.let { slice.from >= it } != false, "Unordered or overlapping slices")
                        entries.add(IndexEntry(slice.from, slice.until, offset, length + 4, recordDigest(length, type, payload)))
                    }
                    2 -> {
                        protocolRequire(!foundIndex, "Duplicate archive index")
                        foundIndex = true
                        indexOffset = offset
                        indexSha = hashRange(file, offset, length.toLong() + 4)
                        val count = unsignedInt(file)
                        protocolRequire(count <= MAX_INDEX_ENTRIES && count == entries.size.toLong() &&
                            5L + count * INDEX_ENTRY_BYTES == length.toLong(), "Index coverage or length mismatch")
                        for (expected in entries) {
                            val from = file.readLong(); val until = file.readLong(); val recordOffset = file.readLong()
                            val recordBytes = unsignedInt(file); val sha = TimelineBytes(readBytes(file, 32))
                            protocolRequire(from == expected.from && until == expected.until && recordOffset == expected.fileOffset &&
                                recordBytes == expected.recordBytes.toLong() && TimelineCanonical.matches(sha, expected.sha256), "Index does not match actual slice")
                        }
                    }
                    3 -> {
                        protocolRequire(foundIndex && length == 41, "Invalid completion marker")
                        val referencedIndex = file.readLong()
                        val sha = TimelineBytes(readBytes(file, 32))
                        protocolRequire(referencedIndex == indexOffset && TimelineCanonical.matches(sha, indexSha!!) &&
                            file.filePointer == file.length(), "Completion index mismatch or trailing bytes")
                        return Reader(file, context, entries)
                    }
                    else -> throw ProtocolException("Unknown archive record type")
                }
                protocolRequire(file.filePointer == offset + 4L + length, "Archive record boundary mismatch")
            }
            throw ProtocolException("Archive has no completion marker")
        } catch (failure: Throwable) {
            runCatching { file.close() }
            if (failure is EOFException) throw ProtocolException("Truncated archive", failure)
            throw failure
        }
    }

    /** Clip effective intervals only. Authorized interpolation anchors remain unchanged. */
    fun sliceState(source: TimelineState, archiveContext: TimelineContext, from: Long, until: Long): TimelineState {
        requireSameIdentity(source.context, archiveContext)
        TimelineProfile.validate(source)
        protocolRequire(from >= maxOf(source.header.retainedFrom, source.context.historyFrom, archiveContext.historyFrom) &&
            from < until && until <= source.header.sealedThrough && until <= source.header.observedThrough,
            "Slice is not retained and sealed")
        protocolRequire(until - from <= archiveContext.ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS * 120,
            "Archive slice exceeds 120 seconds")
        val selected = LinkedHashSet<Long>()
        selected.add(0)
        for (obj in source.objects.values) {
            val intersects = obj.observedFrom < until && (obj.close == null || obj.close.until > from)
            val tail = obj.curves.values.any { curve -> curve.events.any { event ->
                protocolRequire(event.time <= Long.MAX_VALUE - curve.maxTailTime, "Event tail overflow")
                event.time < until && (event.time >= from || event.time + curve.maxTailTime > from)
            } }
            if (intersects || tail) selected.add(obj.handle)
        }
        val pending = java.util.ArrayDeque(selected)
        while (pending.isNotEmpty()) {
            val obj = source.objects[pending.removeFirst()] ?: throw ProtocolException("Slice dependency is missing")
            for (reference in TimelineProfile.references(obj)) if (selected.add(reference)) pending.addLast(reference)
        }
        val objects = selected.sorted().associateWith { handle ->
            val original = source.objects.getValue(handle)
            val curves = original.curves.mapValues { (_, curve) -> curve.copy(
                segments = curve.segments.filter { it.lo < until && it.hi > from }.map { segment ->
                    protocolRequire(!segment.predicted, "Cannot archive an unconfirmed prediction")
                    segment.clipped(maxOf(segment.lo, from), minOf(segment.hi, until))
                },
                events = curve.events.filter { event -> event.time < until &&
                    (event.time >= from || event.time + curve.maxTailTime > from) },
            ) }
            original.copy(close = original.close?.takeIf { it.until <= until }, curves = curves)
        }
        val context = archiveContext.copy(mode = TimelineStreamMode.HISTORY, acceptedFrom = from, acceptedUntil = until)
        val header = TimelineHeader(1, 1, TimelineTransactionKind.RESET, 1, 0, source.header.observedThrough,
            until, from, until, 0, 1, TimelineCanonical.sha256(TimelineBytes(byteArrayOf(0))), source.header.objectHighWater)
        return TimelineState(context, header, objects).also { TimelineProfile.validate(it) }
    }

    private fun requireSameIdentity(a: TimelineContext, b: TimelineContext) {
        protocolRequire(a.profileId == b.profileId && a.teamId == b.teamId && a.ticksPerSecond == b.ticksPerSecond &&
            a.matchId == b.matchId && a.accessId == b.accessId && a.mapKey == b.mapKey && a.mapRecord == b.mapRecord &&
            a.joinedAt == b.joinedAt, "Archive authorization or map changed")
    }

    private fun headerRecord(context: TimelineContext) = TimelineRecord(listOf(
        field(1, TimelineTlvValue.U32(context.profileId.toLong())), field(2, TimelineTlvValue.U32(context.teamId.toLong())),
        field(3, TimelineTlvValue.U32(context.ticksPerSecond.toLong())), field(4, TimelineTlvValue.U32(TimelineProtocol.SUBDIVISIONS.toLong())),
        field(5, TimelineTlvValue.Bytes(context.matchId)), field(6, TimelineTlvValue.Bytes(context.mapKey)),
        field(7, TimelineTlvValue.Bytes(context.accessId)), field(8, TimelineTlvValue.U64(context.joinedAt)),
        field(9, TimelineTlvValue.U64(context.historyFrom)),
    ))

    private fun readHeader(header: TimelineRecord, map: TimelineRecord): TimelineContext {
        TimelineBinary.validateSchema(header, (1..9).associateWith {
            TimelineFieldRule(when (it) { in 1..4 -> 2; in 5..7 -> 6; else -> 3 }, true, true)
        })
        fun number(id: Int) = (header[id]!!.value as TimelineTlvValue.U32).value
        protocolRequire(number(1) == 1L && number(2) in 0..63 && number(3) in 1..1000 && number(4) == 1024L,
            "Invalid archive profile")
        return TimelineContext((header[5]!!.value as TimelineTlvValue.Bytes).value,
            (header[7]!!.value as TimelineTlvValue.Bytes).value, (header[6]!!.value as TimelineTlvValue.Bytes).value,
            map, number(2).toInt(), number(3).toInt(), mode = TimelineStreamMode.HISTORY,
            joinedAt = (header[8]!!.value as TimelineTlvValue.U64).value,
            historyFrom = (header[9]!!.value as TimelineTlvValue.U64).value)
    }

    private fun sliceRecord(slice: Slice) = TimelineRecord(listOf(
        field(1, TimelineTlvValue.U64(slice.from)), field(2, TimelineTlvValue.U64(slice.until)),
        field(3, TimelineTlvValue.U64(slice.observedThrough)), field(4, TimelineTlvValue.U64(slice.controlEpoch)),
        field(5, TimelineTlvValue.Bytes(slice.operations)), field(6, TimelineTlvValue.Bytes(slice.sha256)),
        field(7, TimelineTlvValue.U64(slice.objectHighWater)),
    ))

    private fun readSlice(payload: ByteArray, context: TimelineContext): Slice {
        val record = TimelineBinary.decodeRecord(payload)
        TimelineBinary.validateSchema(record, (1..7).associateWith { TimelineFieldRule(if (it in 5..6) 6 else 3, true, true) })
        fun number(id: Int) = (record[id]!!.value as TimelineTlvValue.U64).value
        val from = number(1); val until = number(2); val observed = number(3); val control = number(4); val highWater = number(7)
        val operations = (record[5]!!.value as TimelineTlvValue.Bytes).value
        val sha = (record[6]!!.value as TimelineTlvValue.Bytes).value
        protocolRequire(from >= context.historyFrom && from < until && until <= observed && observed <= TimelineProtocol.MAX_TIME &&
            until - from <= context.ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS * 120, "Invalid authorized slice interval")
        protocolRequire(operations.size in 1..TimelineProtocol.MAX_BLOB_BYTES && sha.size == 32, "Invalid slice operation bytes or digest")
        protocolRequire(TimelineCanonical.matches(sha, sliceDigest(from, until, observed, control, highWater, operations)), "Slice content digest mismatch")
        return Slice(from, until, observed, control, operations, sha, highWater)
    }

    private fun sliceDigest(from: Long, until: Long, observed: Long, control: Long, highWater: Long, operations: TimelineBytes): TimelineBytes {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = DataOutputStream(DigestOutputStream(nullOutput, digest))
        out.writeLong(from); out.writeLong(until); out.writeLong(observed); out.writeLong(control); out.writeLong(highWater)
        operations.writeTo(out)
        return TimelineBytes(digest.digest())
    }

    private fun field(id: Int, value: TimelineTlvValue) = TimelineField(id, true, value)
    private fun unsignedInt(file: RandomAccessFile): Long = file.readInt().toLong() and 0xffff_ffffL
    private fun readBytes(file: RandomAccessFile, count: Int): ByteArray {
        protocolRequire(count >= 0 && count <= TimelineProtocol.MAX_BLOB_BYTES && count.toLong() <= file.length() - file.filePointer,
            "Archive bytes exceed bounds")
        return ByteArray(count).also(file::readFully)
    }
    private fun readRecordLength(file: RandomAccessFile): Int {
        protocolRequire(file.length() - file.filePointer >= 4, "Truncated record length")
        val length = unsignedInt(file)
        protocolRequire(length in 1..TimelineProtocol.MAX_BLOB_BYTES.toLong() && length <= file.length() - file.filePointer,
            "Archive record length exceeds bounds")
        return length.toInt()
    }
    private fun recordDigest(length: Int, type: Int, payload: ByteArray): TimelineBytes {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = DataOutputStream(DigestOutputStream(nullOutput, digest))
        out.writeInt(length); out.writeByte(type); out.write(payload)
        return TimelineBytes(digest.digest())
    }
    private fun hashRange(file: RandomAccessFile, offset: Long, length: Long): TimelineBytes {
        val restore = file.filePointer
        protocolRequire(offset >= 0 && length >= 0 && offset <= file.length() - length, "Hash range exceeds file")
        val digest = MessageDigest.getInstance("SHA-256")
        val scratch = ByteArray(8192)
        try {
            file.seek(offset)
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, scratch.size.toLong()).toInt()
                file.readFully(scratch, 0, count); digest.update(scratch, 0, count); remaining -= count
            }
        } finally { file.seek(restore) }
        return TimelineBytes(digest.digest())
    }
}
