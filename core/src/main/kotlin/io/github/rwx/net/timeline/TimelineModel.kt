package io.github.rwx.net.timeline

import java.io.OutputStream
import java.util.Collections

/** Owns its bytes. Neither construction nor reads expose a mutable alias. */
class TimelineBytes(bytes: ByteArray) {
    private val storage = bytes.copyOf()
    val size: Int get() = storage.size
    operator fun get(index: Int): Byte = storage[index]
    /** Compare all actual bytes without retaining the caller array or exposing owned storage. */
    fun contentEquals(other: ByteArray): Boolean = storage.contentEquals(other)
    fun toByteArray(): ByteArray = storage.copyOf()
    internal fun writeTo(output: OutputStream) {
        // An arbitrary OutputStream may retain or modify its byte[] argument.
        // Keep the owned array private while still streaming large map planes.
        val scratch = ByteArray(minOf(storage.size, 8192))
        var offset = 0
        while (offset < storage.size) {
            val count = minOf(scratch.size, storage.size - offset)
            storage.copyInto(scratch, 0, offset, offset + count)
            output.write(scratch, 0, count)
            offset += count
        }
    }
    override fun equals(other: Any?): Boolean = other is TimelineBytes && storage.contentEquals(other.storage)
    override fun hashCode(): Int = storage.contentHashCode()
    override fun toString(): String = "TimelineBytes(size=$size)"
    companion object { val EMPTY = TimelineBytes(ByteArray(0)) }
}

// The backing list is created here and never exposed. Keep the Java unmodifiable
// wrapper so even no-op mutators retain the same rejection behavior as nonempty lists.
private val frozenEmptyList: List<Nothing> = Collections.unmodifiableList(ArrayList<Nothing>())

internal fun <T> frozenList(values: Collection<T>): List<T> =
    if (values.isEmpty()) frozenEmptyList else Collections.unmodifiableList(ArrayList(values))
internal fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))

sealed interface TimelineTlvValue {
    val kind: Int
    data class Bool(val value: Boolean) : TimelineTlvValue { override val kind = 1 }
    data class U32(val value: Long) : TimelineTlvValue { override val kind = 2 }
    data class U64(val value: Long) : TimelineTlvValue { override val kind = 3 }
    data class I64(val value: Long) : TimelineTlvValue { override val kind = 4 }
    data class Text(val value: String) : TimelineTlvValue { override val kind = 5 }
    data class Bytes(val value: TimelineBytes) : TimelineTlvValue { override val kind = 6 }
    data class Record(val value: TimelineRecord) : TimelineTlvValue { override val kind = 7 }
    class Records(values: List<TimelineRecord>) : TimelineTlvValue {
        override val kind = 8
        val values = frozenList(values)
        override fun equals(other: Any?) = other is Records && values == other.values
        override fun hashCode() = values.hashCode()
    }
    data class Opaque(override val kind: Int, val value: TimelineBytes) : TimelineTlvValue
}

data class TimelineField(val id: Int, val critical: Boolean, val value: TimelineTlvValue)

class TimelineRecord(fields: List<TimelineField> = emptyList()) {
    val fields: List<TimelineField> = frozenList(fields)
    operator fun get(id: Int): TimelineField? {
        // Construction also accepts records awaiting validation. Preserve first-match lookup
        // for those records instead of assuming their field IDs have already been sorted.
        for (index in fields.indices) {
            val field = fields[index]
            if (field.id == id) return field
        }
        return null
    }
    override fun equals(other: Any?): Boolean = other is TimelineRecord && fields == other.fields
    override fun hashCode(): Int = fields.hashCode()
    override fun toString(): String = "TimelineRecord(fields=${fields.size})"
    companion object { val EMPTY = TimelineRecord() }
}

data class TimelineFieldRule(val kind: Int, val required: Boolean = false,
                             val critical: Boolean? = null, val nested: Map<Int, TimelineFieldRule>? = null)
data class TimelineFrame(val type: Int, val body: TimelineRecord)

enum class TimelineMode(val code: Int) { STEP(1), LINEAR(2), ANGLE(3), PULSE(4) }
enum class TimelineValueType(val code: Int) { SINT(1), VEC3(2), UINT(3), BOOL(4), BLOB(5) }
enum class TimelineObjectKind(val code: Int) { ROOT(0), UNIT(1), PANEL(2), ACTION(3), VISUAL(4) }
enum class TimelineCloseKind(val code: Int) { ENDED(1), DESTROYED(2) }
enum class TimelineStreamMode(val code: Int) { LIVE(1), HISTORY(2) }
enum class TimelineTransactionKind(val code: Int) { RESET(1), UPDATE(2) }

sealed interface TimelineValue {
    val type: TimelineValueType
    data class SInt(val value: Long) : TimelineValue { override val type = TimelineValueType.SINT }
    data class Vec3(val x: Long, val y: Long, val z: Long) : TimelineValue { override val type = TimelineValueType.VEC3 }
    data class UInt(val value: Long) : TimelineValue { override val type = TimelineValueType.UINT }
    data class Bool(val value: Boolean) : TimelineValue { override val type = TimelineValueType.BOOL }
    data class Blob(val value: TimelineBytes) : TimelineValue { override val type = TimelineValueType.BLOB }
}

sealed interface TimelineSegment {
    val lo: Long
    val hi: Long
    val predicted: Boolean
    fun clipped(lo: Long, hi: Long): TimelineSegment
    data class Step(override val lo: Long, override val hi: Long, override val predicted: Boolean,
                    val value: TimelineValue) : TimelineSegment {
        override fun clipped(lo: Long, hi: Long): Step = copy(lo = lo, hi = hi)
    }
    data class Interpolated(override val lo: Long, override val hi: Long, override val predicted: Boolean,
                            val anchor0Time: Long, val anchor1Time: Long,
                            val value0: TimelineValue, val value1: TimelineValue) : TimelineSegment {
        override fun clipped(lo: Long, hi: Long): Interpolated = copy(lo = lo, hi = hi)
    }
}

data class TimelinePulse(val time: Long, val eventId: Long, val value: TimelineValue)
data class TimelineClose(val until: Long, val kind: TimelineCloseKind)
data class TimelineCurveKey(val handle: Long, val attributeId: Int)
data class TimelineCurveFormat(val mode: TimelineMode, val valueType: TimelineValueType)

class TimelineCurve(
    val attributeId: Int,
    val mode: TimelineMode,
    val valueType: TimelineValueType,
    val criticalAttribute: Boolean,
    val eventHighWater: Long = 0,
    val metadata: TimelineRecord = TimelineRecord.EMPTY,
    val curveRevision: Long = 0,
    segments: List<TimelineSegment> = emptyList(),
    events: List<TimelinePulse> = emptyList(),
) {
    val segments = frozenList(segments)
    val events = frozenList(events)
    val maxTailTime: Long get() = (metadata[1]?.value as? TimelineTlvValue.U64)?.value ?: 0
    val format: TimelineCurveFormat get() = TimelineCurveFormat(mode, valueType)
    fun copy(attributeId: Int = this.attributeId, mode: TimelineMode = this.mode,
             valueType: TimelineValueType = this.valueType, criticalAttribute: Boolean = this.criticalAttribute,
             eventHighWater: Long = this.eventHighWater, metadata: TimelineRecord = this.metadata,
             curveRevision: Long = this.curveRevision, segments: List<TimelineSegment> = this.segments,
             events: List<TimelinePulse> = this.events) =
        TimelineCurve(attributeId, mode, valueType, criticalAttribute, eventHighWater, metadata, curveRevision, segments, events)
    override fun equals(other: Any?): Boolean = other is TimelineCurve && attributeId == other.attributeId &&
        mode == other.mode && valueType == other.valueType && criticalAttribute == other.criticalAttribute &&
        eventHighWater == other.eventHighWater && metadata == other.metadata && curveRevision == other.curveRevision &&
        segments == other.segments && events == other.events
    override fun hashCode(): Int = listOf(attributeId, mode, valueType, criticalAttribute, eventHighWater,
        metadata, curveRevision, segments, events).hashCode()
}

class TimelineObject private constructor(
    val handle: Long,
    val kind: TimelineObjectKind,
    val observedFrom: Long,
    val descriptor: TimelineRecord,
    val close: TimelineClose?,
    val curves: Map<Int, TimelineCurve>,
    @Suppress("UNUSED_PARAMETER") owned: Unit,
) {
    constructor(handle: Long, kind: TimelineObjectKind, observedFrom: Long,
                descriptor: TimelineRecord = TimelineRecord.EMPTY, close: TimelineClose? = null,
                curves: Map<Int, TimelineCurve> = emptyMap()) :
        this(handle, kind, observedFrom, descriptor, close, frozenMap(curves), Unit)

    fun copy(handle: Long = this.handle, kind: TimelineObjectKind = this.kind, observedFrom: Long = this.observedFrom,
             descriptor: TimelineRecord = this.descriptor, close: TimelineClose? = this.close,
             curves: Map<Int, TimelineCurve> = this.curves) = TimelineObject(handle, kind, observedFrom, descriptor, close, curves)

    /** Copies into a new owned map; neither the caller's edits nor a mutable map escapes. */
    internal fun copyWithCurveEdits(close: TimelineClose?, edits: Map<Int, TimelineCurve>): TimelineObject {
        val merged = LinkedHashMap(curves)
        merged.putAll(edits)
        return TimelineObject(handle, kind, observedFrom, descriptor, close, Collections.unmodifiableMap(merged), Unit)
    }

    override fun equals(other: Any?): Boolean = other is TimelineObject && handle == other.handle && kind == other.kind &&
        observedFrom == other.observedFrom && descriptor == other.descriptor && close == other.close && curves == other.curves
    override fun hashCode(): Int = listOf(handle, kind, observedFrom, descriptor, close, curves).hashCode()
}

data class TimelineHeader(
    val streamId: Long,
    val epoch: Long,
    val kind: TimelineTransactionKind,
    val revision: Long,
    val baseRevision: Long,
    val observedThrough: Long,
    /** Exclusive: times strictly below this watermark are sealed. */
    val sealedThrough: Long,
    val retainedFrom: Long,
    val retainedUntil: Long,
    val controlEpoch: Long,
    val operationBytes: Long,
    val operationSha256: TimelineBytes,
    val objectHighWater: Long,
)

data class TimelineContext(
    val matchId: TimelineBytes,
    val accessId: TimelineBytes,
    val mapKey: TimelineBytes,
    val mapRecord: TimelineRecord,
    val teamId: Int,
    val ticksPerSecond: Int,
    val mode: TimelineStreamMode = TimelineStreamMode.LIVE,
    val maxPredictionTime: Long = ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS,
    val profileId: Int = 1,
    val joinedAt: Long = 0,
    val historyFrom: Long = 0,
    val acceptedFrom: Long? = null,
    val acceptedUntil: Long? = null,
)

class TimelineState private constructor(
    private val typedContext: TimelineContext?,
    private val typedHeader: TimelineHeader?,
    internal val packedLibrary: TimelinePackedLibrary?,
    val objects: Map<Long, TimelineObject>,
) {
    // Keep the public constructor/copy's defensive snapshot and deferred-validation
    // boundary. In particular, a caller may still construct an invalid typed state.
    constructor(context: TimelineContext, header: TimelineHeader, objects: Map<Long, TimelineObject>) :
        this(context, header, null, frozenMap(objects))

    val context: TimelineContext get() = packedLibrary?.context ?: checkNotNull(typedContext)
    val header: TimelineHeader get() = packedLibrary?.header ?: checkNotNull(typedHeader)

    fun copy(context: TimelineContext = this.context, header: TimelineHeader = this.header,
             objects: Map<Long, TimelineObject> = this.objects) = TimelineState(context, header, objects)
    override fun equals(other: Any?): Boolean = other is TimelineState && context == other.context &&
        header == other.header && objects == other.objects
    override fun hashCode(): Int = listOf(context, header, objects).hashCode()

    companion object {
        /** Internal representation bridge, never a raw-byte or network admission path. */
        internal fun fromPacked(library: TimelinePackedLibrary): TimelineState = fromPacked(library, null)

        internal fun fromPacked(library: TimelinePackedLibrary, previous: TimelineState?): TimelineState {
            library.validate()
            protocolRequire(previous == null || previous.context == library.context, "Packed view crossed context")
            val state = TimelineState(null, null, library, library.objectMap())
            // The primitive library scan is canonical-structure validation, not the
            // profile/authorization gate. Keep that complete gate on the actual view.
            TimelineProfile.validate(state, previous)
            return state
        }
    }
}

sealed interface TimelineOperation {
    data class Create(val handle: Long, val kind: TimelineObjectKind, val observedFrom: Long,
                      val descriptor: TimelineRecord = TimelineRecord.EMPTY) : TimelineOperation
    data class Declare(val handle: Long, val attributeId: Int, val mode: TimelineMode,
                       val valueType: TimelineValueType, val criticalAttribute: Boolean,
                       val eventHighWater: Long = 0, val metadata: TimelineRecord = TimelineRecord.EMPTY) : TimelineOperation
    class Splice(val handle: Long, val attributeId: Int, val expectedRevision: Long,
                 val from: Long, val until: Long, segments: List<TimelineSegment>) : TimelineOperation {
        val segments = frozenList(segments)
        override fun equals(other: Any?): Boolean = other is Splice && handle == other.handle && attributeId == other.attributeId &&
            expectedRevision == other.expectedRevision && from == other.from && until == other.until && segments == other.segments
        override fun hashCode() = listOf(handle, attributeId, expectedRevision, from, until, segments).hashCode()
    }
    class Pulse(val handle: Long, val attributeId: Int, val expectedRevision: Long,
                val from: Long, val until: Long, events: List<TimelinePulse>) : TimelineOperation {
        val events = frozenList(events)
        override fun equals(other: Any?): Boolean = other is Pulse && handle == other.handle && attributeId == other.attributeId &&
            expectedRevision == other.expectedRevision && from == other.from && until == other.until && events == other.events
        override fun hashCode() = listOf(handle, attributeId, expectedRevision, from, until, events).hashCode()
    }
    data class Close(val handle: Long, val untilTime: Long, val closeKind: TimelineCloseKind) : TimelineOperation
    data class Retain(val newFrom: Long) : TimelineOperation
}
