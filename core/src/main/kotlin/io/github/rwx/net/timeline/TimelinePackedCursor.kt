package io.github.rwx.net.timeline

import java.io.InputStream

/** Bounded reader over an owned record page. It never exposes its backing array. */
internal class TimelinePackedCursor private constructor(
    private val storage: ByteArray,
    private val start: Int,
    private val end: Int,
) : InputStream() {
    private var at = start
    val position: Int get() = at - start
    val remaining: Int get() = end - at

    init { protocolRequire(start >= 0 && end >= start && end <= storage.size, "Invalid packed cursor bounds") }

    override fun read(): Int = if (at == end) -1 else storage[at++].toInt() and 255
    fun u8(): Int = read().also { protocolRequire(it >= 0, "Truncated packed record") }
    fun bool(): Boolean = when (u8()) {
        0 -> false
        1 -> true
        else -> throw ProtocolException("Invalid packed boolean")
    }
    fun vuint(): Long = TimelineBinary.readVUInt(this)
    fun vsint(): Long = TimelineBinary.readVSInt(this)
    fun finish() = protocolRequire(remaining == 0, "Trailing packed bytes")

    fun bytes(count: Int): TimelineBytes {
        protocolRequire(count >= 0 && count <= remaining, "Truncated packed bytes")
        return TimelineBytes(storage.copyOfRange(at, at + count)).also { at += count }
    }

    fun record(): TimelineRecord {
        val size = vuint()
        protocolRequire(size <= TimelineProtocol.MAX_BLOB_BYTES && size <= remaining, "Packed record length exceeds bounds")
        if (size == 0L) return TimelineRecord.EMPTY
        val decoded = TimelineBinary.decodeRecord(storage.copyOfRange(at, at + size.toInt()))
        at += size.toInt()
        return decoded
    }

    fun value(type: TimelineValueType): TimelineValue = when (type) {
        TimelineValueType.SINT -> TimelineValue.SInt(vsint())
        TimelineValueType.VEC3 -> TimelineValue.Vec3(vsint(), vsint(), vsint())
        TimelineValueType.UINT -> TimelineValue.UInt(vuint())
        TimelineValueType.BOOL -> TimelineValue.Bool(bool())
        TimelineValueType.BLOB -> {
            val count = vuint()
            protocolRequire(count <= TimelineProtocol.MAX_CHUNK_BYTES && count <= remaining, "Packed value exceeds bounds")
            TimelineValue.Blob(bytes(count.toInt()))
        }
    }

    fun segment(mode: TimelineMode, type: TimelineValueType): TimelineSegment {
        TimelineBinary.validateFormat(mode, type)
        protocolRequire(mode != TimelineMode.PULSE, "PULSE has no packed segment")
        val lo = vuint(); val hi = vuint(); val predicted = bool()
        val result = if (mode == TimelineMode.STEP) TimelineSegment.Step(lo, hi, predicted, value(type))
        else TimelineSegment.Interpolated(lo, hi, predicted, vuint(), vuint(), value(type), value(type))
        TimelineBinary.validateSegment(mode, type, result)
        return result
    }

    fun pulse(type: TimelineValueType): TimelinePulse = TimelinePulse(vuint(), vuint(), value(type))
        .also { TimelineBinary.validatePulse(type, it) }

    // Borrowed record fields. Each primitive scan consumes current bytes and overwrites them;
    // the caller must finish reading these fields before the next record on this cursor.
    var recordLo = 0L
        private set
    var recordHi = 0L
        private set
    var recordPredicted = false
        private set
    var recordAnchor0 = 0L
        private set
    var recordAnchor1 = 0L
        private set
    var recordEventId = 0L
        private set
    private var recordType: TimelineValueType? = null
    private var recordValues = 0
    private var x0 = 0L; private var y0 = 0L; private var z0 = 0L
    private var x1 = 0L; private var y1 = 0L; private var z1 = 0L
    private var blob0 = 0; private var blobLength0 = 0
    private var blob1 = 0; private var blobLength1 = 0

    private fun scanValue(type: TimelineValueType, slot: Int) {
        var x = 0L; var y = 0L; var z = 0L
        var blob = 0; var length = 0
        when (type) {
            TimelineValueType.SINT -> x = vsint()
            TimelineValueType.VEC3 -> { x = vsint(); y = vsint(); z = vsint() }
            TimelineValueType.UINT -> x = vuint()
            TimelineValueType.BOOL -> x = if (bool()) 1 else 0
            TimelineValueType.BLOB -> {
                val count = vuint()
                protocolRequire(count <= TimelineProtocol.MAX_CHUNK_BYTES && count <= remaining, "Packed value exceeds bounds")
                blob = at; length = count.toInt(); at += length
            }
        }
        // readVUInt/readVSInt and bool already enforce the exact scalar domains used
        // by validateValue. The BLOB length above retains its original 65,536-byte bound.
        if (slot == 0) { x0 = x; y0 = y; z0 = z; blob0 = blob; blobLength0 = length }
        else { x1 = x; y1 = y; z1 = z; blob1 = blob; blobLength1 = length }
    }

    private fun sameScannedValues(type: TimelineValueType): Boolean = when (type) {
        TimelineValueType.VEC3 -> x0 == x1 && y0 == y1 && z0 == z1
        TimelineValueType.BLOB -> blobLength0 == blobLength1 && java.util.Arrays.mismatch(
            storage, blob0, blob0 + blobLength0, storage, blob1, blob1 + blobLength1) == -1
        else -> x0 == x1
    }

    /** Reads and validates the actual segment without allocating a Segment/Value graph. */
    fun scanSegment(mode: TimelineMode, type: TimelineValueType) {
        TimelineBinary.validateFormat(mode, type)
        protocolRequire(mode != TimelineMode.PULSE, "PULSE has no packed segment")
        recordType = type; recordValues = if (mode == TimelineMode.STEP) 1 else 2
        recordLo = vuint(); recordHi = vuint(); recordPredicted = bool()
        recordEventId = 0; recordAnchor0 = 0; recordAnchor1 = 0
        if (mode != TimelineMode.STEP) { recordAnchor0 = vuint(); recordAnchor1 = vuint() }
        scanValue(type, 0)
        if (mode != TimelineMode.STEP) scanValue(type, 1)
        protocolRequire(recordLo in 0..TimelineProtocol.MAX_TIME && recordHi > recordLo, "Invalid segment interval")
        if (mode != TimelineMode.STEP) {
            protocolRequire(recordAnchor0 in 0..recordLo && recordAnchor0 < recordAnchor1 && recordHi <= recordAnchor1,
                "Invalid interpolation anchors")
            protocolRequire(recordAnchor1 != TimelineProtocol.FOREVER || sameScannedValues(type), "FOREVER requires a constant segment")
            if (mode == TimelineMode.ANGLE) protocolRequire(x0 in 0..65535 && x1 in 0..65535, "Angle outside one turn")
        }
    }

    /** Reads and validates the actual event without allocating a Pulse/Value graph. */
    fun scanPulse(type: TimelineValueType) {
        recordType = type; recordValues = 1
        recordLo = vuint(); recordEventId = vuint()
        recordHi = 0; recordPredicted = false; recordAnchor0 = 0; recordAnchor1 = 0
        scanValue(type, 0)
        protocolRequire(recordLo in 0..TimelineProtocol.MAX_TIME && recordEventId > 0, "Invalid pulse time or id")
    }

    /** Explicit compatibility adapter; validation paths above do not call it. */
    fun scannedValue(slot: Int): TimelineValue {
        protocolRequire(slot in 0 until recordValues, "No scanned value in this slot")
        val x = if (slot == 0) x0 else x1
        return when (checkNotNull(recordType)) {
            TimelineValueType.SINT -> TimelineValue.SInt(x)
            TimelineValueType.VEC3 -> TimelineValue.Vec3(x, if (slot == 0) y0 else y1, if (slot == 0) z0 else z1)
            TimelineValueType.UINT -> TimelineValue.UInt(x)
            TimelineValueType.BOOL -> TimelineValue.Bool(x != 0L)
            TimelineValueType.BLOB -> {
                val offset = if (slot == 0) blob0 else blob1
                val length = if (slot == 0) blobLength0 else blobLength1
                TimelineValue.Blob(TimelineBytes(storage.copyOfRange(offset, offset + length)))
            }
        }
    }

    data class ObjectHeader(val handle: Long, val kind: TimelineObjectKind, val observedFrom: Long,
                            val close: TimelineClose?, val descriptor: TimelineRecord, val curveCount: Int)
    data class CurveHeader(val attributeId: Int, val mode: TimelineMode, val valueType: TimelineValueType,
                           val critical: Boolean, val eventHighWater: Long, val metadata: TimelineRecord,
                           val revision: Long, val recordCount: Int)

    fun objectHeader(): ObjectHeader {
        val handle = vuint()
        val kind = when (u8()) {
            0 -> TimelineObjectKind.ROOT
            1 -> TimelineObjectKind.UNIT
            2 -> TimelineObjectKind.PANEL
            3 -> TimelineObjectKind.ACTION
            4 -> TimelineObjectKind.VISUAL
            else -> throw ProtocolException("Invalid packed object kind")
        }
        val observedFrom = vuint()
        val close = if (!bool()) null else {
            val until = vuint()
            val kind = when (u8()) {
                1 -> TimelineCloseKind.ENDED
                2 -> TimelineCloseKind.DESTROYED
                else -> throw ProtocolException("Invalid packed close kind")
            }
            TimelineClose(until, kind)
        }
        val descriptor = record()
        TimelineBinary.validateDescriptor(kind, descriptor)
        val count = vuint()
        val maximum = if (kind == TimelineObjectKind.ROOT) TimelineProtocol.MAX_ROOT_ATTRIBUTES else TimelineProtocol.MAX_ATTRIBUTES
        protocolRequire(count <= maximum && count <= remaining, "Packed curve count exceeds bounds")
        return ObjectHeader(handle, kind, observedFrom, close, descriptor, count.toInt())
    }

    fun curveHeader(ticksPerSecond: Int): CurveHeader {
        val id = vuint()
        protocolRequire(id in 1..Int.MAX_VALUE.toLong(), "Invalid packed attribute id")
        val mode = when (u8()) {
            1 -> TimelineMode.STEP
            2 -> TimelineMode.LINEAR
            3 -> TimelineMode.ANGLE
            4 -> TimelineMode.PULSE
            else -> throw ProtocolException("Invalid packed curve mode")
        }
        val type = when (u8()) {
            1 -> TimelineValueType.SINT
            2 -> TimelineValueType.VEC3
            3 -> TimelineValueType.UINT
            4 -> TimelineValueType.BOOL
            5 -> TimelineValueType.BLOB
            else -> throw ProtocolException("Invalid packed value type")
        }
        TimelineBinary.validateFormat(mode, type)
        val critical = bool(); val highWater = vuint(); val metadata = record(); val revision = vuint()
        TimelineBinary.validateMetadata(mode, metadata, ticksPerSecond)
        protocolRequire(mode == TimelineMode.PULSE || highWater == 0L, "Non-PULSE has packed event state")
        val count = vuint()
        protocolRequire(count <= TimelineProtocol.MAX_CURVE_RECORDS && count <= remaining, "Packed record count exceeds bounds")
        return CurveHeader(id.toInt(), mode, type, critical, highWater, metadata, revision, count.toInt())
    }

    /** Explicit on-demand adapter; no decoded object is cached by a library or cursor. */
    fun objectValue(ticksPerSecond: Int): TimelineObject {
        val obj = objectHeader()
        val curves = LinkedHashMap<Int, TimelineCurve>()
        var previousId = 0
        repeat(obj.curveCount) {
            val curve = curveHeader(ticksPerSecond)
            protocolRequire(curve.attributeId > previousId, "Unordered packed attribute")
            previousId = curve.attributeId
            val segments = if (curve.mode != TimelineMode.PULSE)
                List(curve.recordCount) { segment(curve.mode, curve.valueType) } else emptyList()
            val events = if (curve.mode == TimelineMode.PULSE)
                List(curve.recordCount) { pulse(curve.valueType) } else emptyList()
            curves[curve.attributeId] = TimelineCurve(curve.attributeId, curve.mode, curve.valueType, curve.critical,
                curve.eventHighWater, curve.metadata, curve.revision, segments, events)
        }
        finish()
        return TimelineObject(obj.handle, obj.kind, obj.observedFrom, obj.descriptor, obj.close, curves)
    }

    companion object {
        /** General callers supply copied input. Only the library may lend its already-owned page. */
        fun copyOf(bytes: ByteArray): TimelinePackedCursor {
            protocolRequire(bytes.size <= TimelineProtocol.MAX_BLOB_BYTES, "Packed input too large")
            return TimelinePackedCursor(bytes.copyOf(), 0, bytes.size)
        }
        internal fun ownedPage(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): TimelinePackedCursor {
            protocolRequire(offset >= 0 && length >= 0 && offset <= bytes.size - length, "Invalid packed slice")
            return TimelinePackedCursor(bytes, offset, offset + length)
        }
    }
}
