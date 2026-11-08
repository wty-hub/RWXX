package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Structural codec. The store separately validates authorization, receipts and watermarks. */
object TimelineBinary {
    private val utf8 = Charsets.UTF_8

    // These definitions are constant. Inputs are still validated on every call.
    private val emptySchema: Map<Int, TimelineFieldRule> = emptyMap()
    private val pulseMetadataSchema = mapOf(1 to TimelineFieldRule(3, true, true))
    private val ordinaryDescriptorSchema = mapOf(1 to TimelineFieldRule(5, false, false))
    private val panelDescriptorSchema = mapOf(1 to TimelineFieldRule(5, false, false),
        2 to TimelineFieldRule(3, true, true), 5 to TimelineFieldRule(6, true, true))
    private val actionDescriptorSchema = mapOf(1 to TimelineFieldRule(5, false, false),
        2 to TimelineFieldRule(3, true, true), 3 to TimelineFieldRule(3, true, true),
        4 to TimelineFieldRule(7, true, true))
    private val abilitySchema = mapOf(1 to TimelineFieldRule(2, true, true), 2 to TimelineFieldRule(2, true, true),
        3 to TimelineFieldRule(1, true, true), 4 to TimelineFieldRule(5, true, true), 5 to TimelineFieldRule(5, true, true),
        6 to TimelineFieldRule(4, true, true), 7 to TimelineFieldRule(2, true, true),
        9 to TimelineFieldRule(7, false, false), 10 to TimelineFieldRule(7, false, false))
    private val abilityCostSchema = (1..5).associateWith { TimelineFieldRule(4, true, true) }
    private val mapSchema = (1..10).associateWith {
        TimelineFieldRule(if (it == 6 || it == 8 || it == 9 || it == 10) 6 else 2, true, true)
    }

    /** Bounded cursor for profile-specific packed BLOBs. No mutable bytes escape. */
    class Cursor(bytes: TimelineBytes) {
        private val reader = Reader(bytes.also {
            protocolRequire(it.size <= TimelineProtocol.MAX_BLOB_BYTES, "Blob too large")
        }.toByteArray())
        val remaining: Int get() = reader.remaining
        fun u8(): Int = reader.u8()
        fun u32(): Long = reader.u32()
        fun u64(): Long = reader.u64()
        fun bool(): Boolean = reader.bool()
        fun vuint(): Long = readVUInt(reader)
        fun vsint(): Long = readVSInt(reader)
        fun bytes(count: Int): TimelineBytes = TimelineBytes(reader.bytes(count))
        fun vbytes(max: Int = TimelineProtocol.MAX_CHUNK_BYTES): TimelineBytes = readVBytes(reader, max)
        fun finish() = reader.finish()
    }

    internal class Reader(private val bytes: ByteArray, private var at: Int = 0, private val end: Int = bytes.size) {
        val remaining: Int get() = end - at
        fun u8(): Int { protocolRequire(remaining >= 1, "Truncated value"); return bytes[at++].toInt() and 255 }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun i64(): Long { var value = 0L; repeat(8) { value = (value shl 8) or u8().toLong() }; return value }
        fun u64(): Long = i64().also { protocolRequire(it >= 0, "Unsigned integer exceeds signed range") }
        fun bool(): Boolean = when (u8()) { 0 -> false; 1 -> true; else -> throw ProtocolException("Invalid boolean") }
        fun length(max: Int): Int = u32().also {
            protocolRequire(it <= max && it <= remaining, "Container length exceeds bounds")
        }.toInt()
        fun slice(count: Int): Reader {
            protocolRequire(count >= 0 && count <= remaining, "Truncated container")
            return Reader(bytes, at, at + count).also { at += count }
        }
        fun bytes(count: Int): ByteArray {
            protocolRequire(count >= 0 && count <= remaining, "Truncated bytes")
            return bytes.copyOfRange(at, at + count).also { at += count }
        }
        fun finish() = protocolRequire(remaining == 0, "Trailing bytes")
    }

    internal class LimitedOutput(private val destination: OutputStream, private val limit: Long) : OutputStream() {
        var count = 0L; private set
        private fun charge(size: Int) {
            protocolRequire(size >= 0 && count <= limit - size, "Encoded size limit exceeded")
            count += size
        }
        override fun write(value: Int) { charge(1); destination.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { charge(length); destination.write(bytes, offset, length) }
    }

    private fun textBytes(text: String): ByteArray {
        protocolRequire(text.none { it.code < 0x20 || it.code in 0x7f..0x9f }, "Control character in text")
        return try {
            val encoded = utf8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (e: java.nio.charset.CharacterCodingException) { throw ProtocolException("Invalid UTF-8", e) }
    }

    private fun readText(bytes: ByteArray): String {
        val text = try {
            utf8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) { throw ProtocolException("Invalid UTF-8", e) }
        textBytes(text)
        return text
    }

    fun validateRecord(record: TimelineRecord) { recordSize(record) }

    fun recordSize(record: TimelineRecord): Int = recordSize(record, 1)

    private fun recordSize(record: TimelineRecord, depth: Int): Int {
        protocolRequire(depth in 1..TimelineProtocol.MAX_DEPTH, "Record nesting limit exceeded")
        protocolRequire(record.fields.size <= TimelineProtocol.MAX_FIELDS, "Too many record fields")
        var previous = 0
        var size = 0L
        for (index in record.fields.indices) {
            val field = record.fields[index]
            protocolRequire(field.id in 1..32767 && field.id > previous, "Field ids must strictly increase")
            previous = field.id
            val valueSize: Long = when (val value = field.value) {
                is TimelineTlvValue.Bool -> 1
                is TimelineTlvValue.U32 -> { protocolRequire(value.value in 0..TimelineProtocol.MAX_U32, "Invalid u32"); 4 }
                is TimelineTlvValue.U64 -> { protocolRequire(value.value >= 0, "Invalid u64"); 8 }
                is TimelineTlvValue.I64 -> 8
                is TimelineTlvValue.Text -> textBytes(value.value).size.toLong()
                is TimelineTlvValue.Bytes -> value.value.size.toLong()
                is TimelineTlvValue.Record -> recordSize(value.value, depth + 1).toLong()
                is TimelineTlvValue.Records -> {
                    protocolRequire(value.values.size <= TimelineProtocol.MAX_RECORDS, "Too many records")
                    var total = 4L
                    for (index in value.values.indices) {
                        total += 4L + recordSize(value.values[index], depth + 1)
                        protocolRequire(total <= TimelineProtocol.MAX_BLOB_BYTES, "Record list too large")
                    }
                    total
                }
                is TimelineTlvValue.Opaque -> {
                    protocolRequire(value.kind in 9..255 && !field.critical, "Unknown critical or invalid value kind")
                    value.value.size.toLong()
                }
            }
            size += 8 + valueSize
            protocolRequire(size <= TimelineProtocol.MAX_BLOB_BYTES, "Record too large")
        }
        return size.toInt()
    }

    fun encodeRecord(record: TimelineRecord): TimelineBytes {
        val output = ByteArrayOutputStream(minOf(recordSize(record), 65536))
        writeRecord(record, output)
        return TimelineBytes(output.toByteArray())
    }

    fun writeRecord(record: TimelineRecord, output: OutputStream) {
        recordSize(record)
        writeRecordUnchecked(record, DataOutputStream(output))
    }

    private fun writeRecordUnchecked(record: TimelineRecord, out: DataOutputStream) {
        for (index in record.fields.indices) {
            val field = record.fields[index]
            out.writeShort(field.id); out.writeByte(if (field.critical) 1 else 0); out.writeByte(field.value.kind)
            when (val value = field.value) {
                is TimelineTlvValue.Bool -> { out.writeInt(1); out.writeBoolean(value.value) }
                is TimelineTlvValue.U32 -> { out.writeInt(4); out.writeInt(value.value.toInt()) }
                is TimelineTlvValue.U64 -> { out.writeInt(8); out.writeLong(value.value) }
                is TimelineTlvValue.I64 -> { out.writeInt(8); out.writeLong(value.value) }
                is TimelineTlvValue.Text -> { val bytes = textBytes(value.value); out.writeInt(bytes.size); out.write(bytes) }
                is TimelineTlvValue.Bytes -> { out.writeInt(value.value.size); value.value.writeTo(out) }
                is TimelineTlvValue.Record -> { out.writeInt(recordSize(value.value)); writeRecordUnchecked(value.value, out) }
                is TimelineTlvValue.Records -> {
                    var size = 4L
                    for (index in value.values.indices) size += 4L + recordSize(value.values[index])
                    out.writeInt(size.toInt())
                    out.writeInt(value.values.size)
                    for (index in value.values.indices) {
                        val item = value.values[index]
                        out.writeInt(recordSize(item)); writeRecordUnchecked(item, out)
                    }
                }
                is TimelineTlvValue.Opaque -> { out.writeInt(value.value.size); value.value.writeTo(out) }
            }
        }
    }

    fun decodeRecord(bytes: TimelineBytes): TimelineRecord {
        protocolRequire(bytes.size <= TimelineProtocol.MAX_BLOB_BYTES, "Record too large")
        return decodeRecord(bytes.toByteArray())
    }
    fun decodeRecord(bytes: ByteArray): TimelineRecord {
        protocolRequire(bytes.size <= TimelineProtocol.MAX_BLOB_BYTES, "Record too large")
        return readRecord(Reader(bytes), 1)
    }

    private fun readRecord(reader: Reader, depth: Int): TimelineRecord {
        protocolRequire(depth in 1..TimelineProtocol.MAX_DEPTH, "Record nesting limit exceeded")
        val fields = ArrayList<TimelineField>()
        var previous = 0
        while (reader.remaining > 0) {
            protocolRequire(fields.size < TimelineProtocol.MAX_FIELDS, "Too many record fields")
            val id = reader.u16()
            protocolRequire(id in 1..32767 && id > previous, "Field ids must strictly increase")
            previous = id
            val flags = reader.u8()
            protocolRequire(flags in 0..1, "Invalid field flags")
            val kind = reader.u8()
            protocolRequire(kind > 0, "Invalid value kind")
            val body = reader.slice(reader.length(TimelineProtocol.MAX_BLOB_BYTES))
            val value: TimelineTlvValue = when (kind) {
                1 -> TimelineTlvValue.Bool(body.bool())
                2 -> TimelineTlvValue.U32(body.u32())
                3 -> TimelineTlvValue.U64(body.u64())
                4 -> TimelineTlvValue.I64(body.i64())
                5 -> TimelineTlvValue.Text(readText(body.bytes(body.remaining)))
                6 -> TimelineTlvValue.Bytes(TimelineBytes(body.bytes(body.remaining)))
                7 -> TimelineTlvValue.Record(readRecord(body, depth + 1))
                8 -> {
                    val count = body.u32()
                    protocolRequire(count <= TimelineProtocol.MAX_RECORDS && count <= body.remaining / 4, "Invalid record count")
                    val records = ArrayList<TimelineRecord>(count.toInt())
                    repeat(count.toInt()) { records.add(readRecord(body.slice(body.length(TimelineProtocol.MAX_BLOB_BYTES)), depth + 1)) }
                    TimelineTlvValue.Records(records)
                }
                else -> {
                    protocolRequire(flags == 0, "Unknown critical value kind")
                    TimelineTlvValue.Opaque(kind, TimelineBytes(body.bytes(body.remaining)))
                }
            }
            body.finish()
            fields.add(TimelineField(id, flags == 1, value))
        }
        return TimelineRecord(fields)
    }

    fun validateSchema(record: TimelineRecord, schema: Map<Int, TimelineFieldRule>) {
        validateRecord(record)
        for (index in record.fields.indices) {
            val field = record.fields[index]
            val rule = schema[field.id]
            if (rule == null) { protocolRequire(!field.critical, "Unknown critical field"); continue }
            protocolRequire(field.value.kind == rule.kind, "Known field has wrong kind")
            protocolRequire(rule.critical == null || rule.critical == field.critical, "Incorrect critical flag")
            rule.nested?.let { nested ->
                when (val value = field.value) {
                    is TimelineTlvValue.Record -> validateSchema(value.value, nested)
                    is TimelineTlvValue.Records -> for (index in value.values.indices) validateSchema(value.values[index], nested)
                    else -> throw ProtocolException("Nested schema requires record kind")
                }
            }
        }
        for ((id, rule) in schema) protocolRequire(!rule.required || findField(record, id) != null, "Missing required field")
    }

    private fun findField(record: TimelineRecord, id: Int): TimelineField? {
        for (index in record.fields.indices) {
            val field = record.fields[index]
            if (field.id == id) return field
        }
        return null
    }

    /** A generic frame; message schemas and directions are checked by the message layer. */
    fun encodeFrame(frame: TimelineFrame): TimelineBytes {
        val output = ByteArrayOutputStream()
        writeFrame(output, frame)
        return TimelineBytes(output.toByteArray())
    }

    fun writeFrame(output: OutputStream, frame: TimelineFrame) {
        protocolRequire(frame.type in 1..65535, "Invalid message type")
        val size = recordSize(frame.body).toLong() + 8
        protocolRequire(size <= TimelineProtocol.MAX_FRAME_PAYLOAD, "Frame too large")
        val out = DataOutputStream(output)
        out.writeInt(size.toInt()); out.writeInt(TimelineProtocol.MAGIC)
        out.writeShort(TimelineProtocol.VERSION); out.writeShort(frame.type)
        writeRecordUnchecked(frame.body, out)
    }

    fun decodeFrame(bytes: TimelineBytes): TimelineFrame {
        protocolRequire(bytes.size <= TimelineProtocol.MAX_FRAME_PAYLOAD + 4, "Frame too large")
        return decodeFrame(bytes.toByteArray())
    }
    fun decodeFrame(bytes: ByteArray): TimelineFrame {
        protocolRequire(bytes.size <= TimelineProtocol.MAX_FRAME_PAYLOAD + 4, "Frame too large")
        val reader = Reader(bytes)
        val length = reader.u32()
        protocolRequire(length in 8L..TimelineProtocol.MAX_FRAME_PAYLOAD && length == reader.remaining.toLong(), "Invalid frame length")
        protocolRequire(reader.u32() == TimelineProtocol.MAGIC.toLong(), "Invalid frame magic")
        protocolRequire(reader.u16() == TimelineProtocol.VERSION, "Unsupported wire version")
        val type = reader.u16()
        protocolRequire(type > 0, "Invalid message type")
        return TimelineFrame(type, readRecord(reader, 1))
    }

    fun readFrame(input: InputStream): TimelineFrame {
        val source = DataInputStream(input)
        val length = source.readInt().toLong() and 0xffff_ffffL
        protocolRequire(length in 8L..TimelineProtocol.MAX_FRAME_PAYLOAD, "Invalid frame length")
        val bytes = ByteArray(length.toInt() + 4)
        ByteBuffer.wrap(bytes).putInt(length.toInt())
        source.readFully(bytes, 4, length.toInt())
        return decodeFrame(bytes)
    }

    fun writeVUInt(out: OutputStream, value: Long) {
        protocolRequire(value >= 0, "Invalid vuint")
        var remaining = value
        while (remaining >= 128) { out.write(((remaining and 127) or 128).toInt()); remaining = remaining ushr 7 }
        out.write(remaining.toInt())
    }
    internal fun readVUInt(reader: Reader): Long {
        var result = 0L
        repeat(9) { index ->
            val byte = reader.u8()
            result = result or ((byte and 127).toLong() shl (index * 7))
            if (byte and 128 == 0) {
                protocolRequire(index == 0 || byte != 0, "Redundant vuint")
                return result
            }
        }
        throw ProtocolException("vuint exceeds 63 bits")
    }
    fun writeVSInt(out: OutputStream, value: Long) {
        protocolRequire(value in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT, "Invalid vsint")
        writeVUInt(out, (value shl 1) xor (value shr 63))
    }
    internal fun readVSInt(reader: Reader): Long { val value = readVUInt(reader); return (value ushr 1) xor -(value and 1) }
    fun readVUInt(input: InputStream): Long {
        var result = 0L
        repeat(9) { index ->
            val byte = input.read()
            protocolRequire(byte >= 0, "Truncated vuint")
            result = result or ((byte and 127).toLong() shl (index * 7))
            if (byte and 128 == 0) {
                protocolRequire(index == 0 || byte != 0, "Redundant vuint")
                return result
            }
        }
        throw ProtocolException("vuint exceeds 63 bits")
    }
    fun readVSInt(input: InputStream): Long { val value = readVUInt(input); return (value ushr 1) xor -(value and 1) }
    fun encodeVUInt(value: Long): TimelineBytes = encoded { writeVUInt(it, value) }
    fun decodeVUInt(bytes: TimelineBytes): Long = exact(bytes) { readVUInt(it) }
    fun encodeVSInt(value: Long): TimelineBytes = encoded { writeVSInt(it, value) }
    fun decodeVSInt(bytes: TimelineBytes): Long = exact(bytes) { readVSInt(it) }

    private fun encoded(max: Int = TimelineProtocol.MAX_BLOB_BYTES, block: (OutputStream) -> Unit): TimelineBytes {
        val output = ByteArrayOutputStream()
        block(LimitedOutput(output, max.toLong()))
        return TimelineBytes(output.toByteArray())
    }
    private fun <T> exact(bytes: TimelineBytes, block: (Reader) -> T): T {
        protocolRequire(bytes.size <= TimelineProtocol.MAX_BLOB_BYTES, "Blob too large")
        val reader = Reader(bytes.toByteArray())
        return block(reader).also { reader.finish() }
    }
    private fun readVBytes(reader: Reader, max: Int): TimelineBytes {
        val size = readVUInt(reader)
        protocolRequire(size <= max && size <= reader.remaining, "vbytes exceeds bounds")
        return TimelineBytes(reader.bytes(size.toInt()))
    }
    internal fun writeVBytes(out: OutputStream, bytes: TimelineBytes) { writeVUInt(out, bytes.size.toLong()); bytes.writeTo(out) }
    internal fun writeVRecord(out: OutputStream, record: TimelineRecord) { writeVUInt(out, recordSize(record).toLong()); writeRecord(record, out) }

    fun validateValue(type: TimelineValueType, value: TimelineValue) {
        protocolRequire(value.type == type, "Curve value has wrong type")
        when (value) {
            is TimelineValue.SInt -> protocolRequire(value.value in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT, "Invalid signed curve value")
            is TimelineValue.Vec3 -> protocolRequire(value.x in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT &&
                value.y in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT &&
                value.z in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT, "Invalid vector")
            is TimelineValue.UInt -> protocolRequire(value.value >= 0, "Invalid unsigned curve value")
            is TimelineValue.Bool -> Unit
            is TimelineValue.Blob -> protocolRequire(value.value.size <= TimelineProtocol.MAX_CHUNK_BYTES, "Curve blob too large")
        }
    }
    internal fun writeValue(out: OutputStream, type: TimelineValueType, value: TimelineValue) {
        validateValue(type, value)
        when (value) {
            is TimelineValue.SInt -> writeVSInt(out, value.value)
            is TimelineValue.Vec3 -> { writeVSInt(out, value.x); writeVSInt(out, value.y); writeVSInt(out, value.z) }
            is TimelineValue.UInt -> writeVUInt(out, value.value)
            is TimelineValue.Bool -> out.write(if (value.value) 1 else 0)
            is TimelineValue.Blob -> writeVBytes(out, value.value)
        }
    }
    private fun readValue(reader: Reader, type: TimelineValueType): TimelineValue = when (type) {
        TimelineValueType.SINT -> TimelineValue.SInt(readVSInt(reader))
        TimelineValueType.VEC3 -> TimelineValue.Vec3(readVSInt(reader), readVSInt(reader), readVSInt(reader))
        TimelineValueType.UINT -> TimelineValue.UInt(readVUInt(reader))
        TimelineValueType.BOOL -> TimelineValue.Bool(reader.bool())
        TimelineValueType.BLOB -> TimelineValue.Blob(readVBytes(reader, TimelineProtocol.MAX_CHUNK_BYTES))
    }
    fun encodeValue(type: TimelineValueType, value: TimelineValue): TimelineBytes = encoded { writeValue(it, type, value) }
    fun decodeValue(type: TimelineValueType, bytes: TimelineBytes): TimelineValue = exact(bytes) { readValue(it, type) }

    fun validateFormat(mode: TimelineMode, type: TimelineValueType) {
        protocolRequire(mode != TimelineMode.LINEAR || type == TimelineValueType.SINT ||
            type == TimelineValueType.VEC3 || type == TimelineValueType.UINT, "LINEAR requires numeric values")
        protocolRequire(mode != TimelineMode.ANGLE || type == TimelineValueType.UINT, "ANGLE requires UINT")
    }
    fun validateSegment(mode: TimelineMode, type: TimelineValueType, segment: TimelineSegment) {
        validateFormat(mode, type)
        protocolRequire(mode != TimelineMode.PULSE, "PULSE has no segments")
        protocolRequire(segment.lo in 0..TimelineProtocol.MAX_TIME && segment.hi > segment.lo, "Invalid segment interval")
        when (segment) {
            is TimelineSegment.Step -> { protocolRequire(mode == TimelineMode.STEP, "Wrong segment kind"); validateValue(type, segment.value) }
            is TimelineSegment.Interpolated -> {
                protocolRequire(mode == TimelineMode.LINEAR || mode == TimelineMode.ANGLE, "Wrong segment kind")
                protocolRequire(segment.anchor0Time in 0..segment.lo && segment.anchor0Time < segment.anchor1Time && segment.hi <= segment.anchor1Time, "Invalid interpolation anchors")
                validateValue(type, segment.value0); validateValue(type, segment.value1)
                protocolRequire(segment.anchor1Time != TimelineProtocol.FOREVER || segment.value0 == segment.value1, "FOREVER requires a constant segment")
                if (mode == TimelineMode.ANGLE) protocolRequire(
                    (segment.value0 as TimelineValue.UInt).value in 0..65535 && (segment.value1 as TimelineValue.UInt).value in 0..65535, "Angle outside one turn")
            }
        }
    }
    internal fun writeSegment(out: OutputStream, mode: TimelineMode, type: TimelineValueType, segment: TimelineSegment) {
        validateSegment(mode, type, segment)
        writeVUInt(out, segment.lo); writeVUInt(out, segment.hi); out.write(if (segment.predicted) 1 else 0)
        when (segment) {
            is TimelineSegment.Step -> writeValue(out, type, segment.value)
            is TimelineSegment.Interpolated -> {
                writeVUInt(out, segment.anchor0Time); writeVUInt(out, segment.anchor1Time)
                writeValue(out, type, segment.value0); writeValue(out, type, segment.value1)
            }
        }
    }
    private fun readSegment(reader: Reader, mode: TimelineMode, type: TimelineValueType): TimelineSegment {
        validateFormat(mode, type)
        protocolRequire(mode != TimelineMode.PULSE, "PULSE has no segments")
        val lo = readVUInt(reader); val hi = readVUInt(reader); val flags = reader.u8()
        protocolRequire(flags in 0..1, "Invalid segment flags")
        val segment = if (mode == TimelineMode.STEP) TimelineSegment.Step(lo, hi, flags == 1, readValue(reader, type))
        else TimelineSegment.Interpolated(lo, hi, flags == 1, readVUInt(reader), readVUInt(reader), readValue(reader, type), readValue(reader, type))
        validateSegment(mode, type, segment)
        return segment
    }
    fun encodeSegment(mode: TimelineMode, type: TimelineValueType, segment: TimelineSegment): TimelineBytes = encoded { writeSegment(it, mode, type, segment) }
    fun decodeSegment(mode: TimelineMode, type: TimelineValueType, bytes: TimelineBytes): TimelineSegment = exact(bytes) { readSegment(it, mode, type) }

    fun validatePulse(type: TimelineValueType, event: TimelinePulse) {
        protocolRequire(event.time in 0..TimelineProtocol.MAX_TIME && event.eventId > 0, "Invalid pulse time or id")
        validateValue(type, event.value)
    }
    internal fun writePulse(out: OutputStream, type: TimelineValueType, event: TimelinePulse) {
        validatePulse(type, event); writeVUInt(out, event.time); writeVUInt(out, event.eventId); writeValue(out, type, event.value)
    }
    private fun readPulse(reader: Reader, type: TimelineValueType): TimelinePulse =
        TimelinePulse(readVUInt(reader), readVUInt(reader), readValue(reader, type)).also { validatePulse(type, it) }
    fun encodePulse(type: TimelineValueType, event: TimelinePulse): TimelineBytes = encoded { writePulse(it, type, event) }
    fun decodePulse(type: TimelineValueType, bytes: TimelineBytes): TimelinePulse = exact(bytes) { readPulse(it, type) }

    fun sample(mode: TimelineMode, type: TimelineValueType, segment: TimelineSegment, time: Long): TimelineValue? {
        validateSegment(mode, type, segment)
        protocolRequire(time in 0..TimelineProtocol.MAX_TIME, "Invalid sample time")
        if (time < segment.lo || time >= segment.hi) return null
        if (segment is TimelineSegment.Step) return segment.value
        segment as TimelineSegment.Interpolated
        if (segment.value0 == segment.value1) return segment.value0
        val span = segment.anchor1Time - segment.anchor0Time
        val elapsed = time - segment.anchor0Time
        val before = span - elapsed
        if (mode == TimelineMode.ANGLE) {
            val a = (segment.value0 as TimelineValue.UInt).value
            val b = (segment.value1 as TimelineValue.UInt).value
            var delta = Math.floorMod(b - a, 65536L)
            if (delta > 32768) delta -= 65536
            return TimelineValue.UInt(Math.floorMod(interpolate(a, a + delta, before, elapsed, span), 65536L))
        }
        return when (val a = segment.value0) {
            is TimelineValue.SInt -> TimelineValue.SInt(interpolate(a.value, (segment.value1 as TimelineValue.SInt).value, before, elapsed, span))
            is TimelineValue.UInt -> TimelineValue.UInt(interpolate(a.value, (segment.value1 as TimelineValue.UInt).value, before, elapsed, span))
            is TimelineValue.Vec3 -> (segment.value1 as TimelineValue.Vec3).let { b -> TimelineValue.Vec3(
                interpolate(a.x, b.x, before, elapsed, span), interpolate(a.y, b.y, before, elapsed, span),
                interpolate(a.z, b.z, before, elapsed, span)) }
            else -> throw ProtocolException("Non-numeric interpolation")
        }
    }

    private fun interpolate(a: Long, b: Long, before: Long, elapsed: Long, span: Long): Long {
        if (a == b || elapsed == 0L) return a
        return try {
            // Round the whole weighted value, never the delta: negative half values differ.
            val numerator = Math.addExact(Math.multiplyExact(a, before), Math.multiplyExact(b, elapsed))
            roundRatio(numerator, span)
        } catch (_: ArithmeticException) {
            roundRatio(BigInteger.valueOf(a) * BigInteger.valueOf(before) +
                BigInteger.valueOf(b) * BigInteger.valueOf(elapsed), BigInteger.valueOf(span)).longValueExact()
        }
    }

    private fun roundRatio(n: Long, d: Long): Long {
        val quotient = n / d
        val remainder = n % d
        // |remainder| < d <= Long.MAX_VALUE, including when n is Long.MIN_VALUE.
        val magnitude = if (remainder < 0) -remainder else remainder
        return if (magnitude >= d - magnitude) quotient + (if (n < 0) -1L else 1L) else quotient
    }

    private fun roundRatio(n: BigInteger, d: BigInteger): BigInteger {
        val parts = n.abs().divideAndRemainder(d)
        val magnitude = if (parts[1].shiftLeft(1) >= d) parts[0] + BigInteger.ONE else parts[0]
        return if (n.signum() < 0) -magnitude else magnitude
    }

    fun validateMetadata(mode: TimelineMode, metadata: TimelineRecord, ticksPerSecond: Int? = null) {
        validateSchema(metadata, if (mode == TimelineMode.PULSE) pulseMetadataSchema else emptySchema)
        if (mode != TimelineMode.PULSE) protocolRequire(findField(metadata, 1) == null, "Only PULSE can have maxTailTime")
        else {
            val max = (ticksPerSecond ?: 1000).toLong() * TimelineProtocol.SUBDIVISIONS * 10
            protocolRequire((findField(metadata, 1)!!.value as TimelineTlvValue.U64).value in 0..max, "Invalid maxTailTime")
        }
    }

    fun validateDescriptor(kind: TimelineObjectKind, descriptor: TimelineRecord) {
        val schema = when (kind) {
            TimelineObjectKind.PANEL -> panelDescriptorSchema
            TimelineObjectKind.ACTION -> actionDescriptorSchema
            else -> ordinaryDescriptorSchema
        }
        validateSchema(descriptor, schema)
        for (id in 2..5) protocolRequire(schema.containsKey(id) || findField(descriptor, id) == null, "Descriptor field forbidden for object kind")
        findField(descriptor, 1)?.let { protocolRequire(textBytes((it.value as TimelineTlvValue.Text).value).size <= 128, "Presentation key too long") }
        for (id in 2..3) findField(descriptor, id)?.let { protocolRequire((it.value as TimelineTlvValue.U64).value > 0, "Invalid descriptor reference") }
        findField(descriptor, 4)?.let { validateAbility((it.value as TimelineTlvValue.Record).value) }
        findField(descriptor, 5)?.let { decodeSelection((it.value as TimelineTlvValue.Bytes).value) }
    }

    fun decodeSelection(bytes: TimelineBytes): List<Long> = exact(bytes) { reader ->
        val count = readVUInt(reader)
        protocolRequire(count <= 256 && count <= reader.remaining, "Invalid selection count")
        val result = ArrayList<Long>(count.toInt())
        var last = 0L
        repeat(count.toInt()) { val handle = readVUInt(reader); protocolRequire(handle > last, "Selection must strictly increase"); result.add(handle); last = handle }
        frozenList(result)
    }

    private fun validateAbility(record: TimelineRecord) {
        validateSchema(record, abilitySchema)
        fun u32(id: Int) = (record[id]!!.value as TimelineTlvValue.U32).value
        protocolRequire(u32(1) in 1..24 && u32(2) in 0..2 && u32(7) in 0..9, "Invalid ability enumeration")
        for (id in 4..5) {
            val text = (record[id]!!.value as TimelineTlvValue.Text).value
            protocolRequire(text.length in (if (id == 4) 1 else 0)..128 && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.-" }, "Invalid ability key")
        }
        protocolRequire((record[6]!!.value as TimelineTlvValue.I64).value >= -1, "Invalid ability cost")
        for (id in 9..10) record[id]?.let { field ->
            val cost = (field.value as TimelineTlvValue.Record).value
            validateSchema(cost, abilityCostSchema)
            for (component in 1..5) protocolRequire((cost[component]!!.value as TimelineTlvValue.I64).value >= -1,
                "Invalid ability resource cost")
            if (id == 9) protocolRequire(cost[1]!!.value == record[6]!!.value, "Ability credits cost differs")
        }
    }

    fun validateMapRecord(record: TimelineRecord) {
        validateSchema(record, mapSchema)
        fun u32(id: Int) = (record[id]!!.value as TimelineTlvValue.U32).value
        fun bytes(id: Int) = (record[id]!!.value as TimelineTlvValue.Bytes).value
        val width = u32(2); val height = u32(3)
        protocolRequire(u32(1) == 1L && u32(7) == 1L && width in 1..1024 && height in 1..1024 &&
            u32(4) in 1..4096 && u32(5) in 1..4096, "Invalid map dimensions or catalog")
        val cells = (width * height).toInt()
        protocolRequire(bytes(6).size == cells && bytes(8).size == cells * 2 && bytes(9).size == cells * 2, "Invalid map plane size")
        val terrain = bytes(6)
        val ground = bytes(8)
        val decoration = bytes(9)
        repeat(cells) { index ->
            protocolRequire(terrain[index].toInt() and 0xf0 == 0, "Unknown terrain flags")
            val groundTile = ((ground[index * 2].toInt() and 255) shl 8) or (ground[index * 2 + 1].toInt() and 255)
            val decorationTile = ((decoration[index * 2].toInt() and 255) shl 8) or (decoration[index * 2 + 1].toInt() and 255)
            protocolRequire(groundTile <= 234 && decorationTile <= 234, "Invalid terrain catalog id")
        }
        exact(bytes(10)) { reader ->
            val count = readVUInt(reader)
            protocolRequire(count <= 1_048_576 && count <= reader.remaining / 2, "Invalid resource pool count")
            var previous = -1L
            repeat(count.toInt()) {
                val x = readVUInt(reader); val y = readVUInt(reader)
                protocolRequire(x < width && y < height && y * width + x > previous, "Invalid resource pool order or coordinates")
                previous = y * width + x
            }
        }
    }

    private fun attribute(value: Long): Int { protocolRequire(value in 1..Int.MAX_VALUE.toLong(), "Invalid attribute id"); return value.toInt() }
    private fun ordinaryTime(value: Long): Long = value.also { protocolRequire(it in 0..TimelineProtocol.MAX_TIME, "Invalid ordinary time") }
    private inline fun <reified T : Enum<T>> enumCode(code: Int, crossinline getCode: (T) -> Int): T =
        enumValues<T>().firstOrNull { getCode(it) == code } ?: throw ProtocolException("Unknown enumeration")

    private fun interface FormatLookup {
        fun find(handle: Long, attributeId: Int): TimelineCurveFormat?
    }

    /** Public Map calls retain a defensive snapshot for each original codec pass. */
    private class MapFormats(values: Map<TimelineCurveKey, TimelineCurveFormat>) : FormatLookup {
        private val values = HashMap(values)
        override fun find(handle: Long, attributeId: Int) = values[TimelineCurveKey(handle, attributeId)]
    }

    /** The base is one immutable committed state, never a mutable Store or a cross-call cache. */
    private class StateFormats(private val state: TimelineState?) : FormatLookup {
        override fun find(handle: Long, attributeId: Int): TimelineCurveFormat? {
            val base = state ?: return null
            val packed = base.packedLibrary
            return if (packed != null) packed.curveFormat(handle, attributeId)
                else base.objects[handle]?.curves?.get(attributeId)?.format
        }
    }

    private class DeclaredFormats(private val base: FormatLookup) {
        private var declarations: HashMap<TimelineCurveKey, TimelineCurveFormat>? = null
        fun find(handle: Long, attributeId: Int): TimelineCurveFormat? =
            declarations?.get(TimelineCurveKey(handle, attributeId)) ?: base.find(handle, attributeId)

        fun declare(handle: Long, attributeId: Int, format: TimelineCurveFormat) {
            protocolRequire(base.find(handle, attributeId) == null, "Duplicate curve declaration")
            val local = declarations ?: HashMap<TimelineCurveKey, TimelineCurveFormat>().also { declarations = it }
            protocolRequire(local.putIfAbsent(TimelineCurveKey(handle, attributeId), format) == null, "Duplicate curve declaration")
        }
    }

    fun decodeOperations(bytes: TimelineBytes, initialFormats: Map<TimelineCurveKey, TimelineCurveFormat> = emptyMap()): List<TimelineOperation> =
        parseOperations(bytes, { MapFormats(initialFormats) }, false)

    internal fun decodeOperationsFromState(bytes: TimelineBytes, base: TimelineState?): List<TimelineOperation> =
        parseOperations(bytes, { StateFormats(base) }, false)

    /** Check bounded syntax while a base is unavailable; never yields an applicable transaction. */
    fun validateOperationEnvelope(bytes: TimelineBytes) { parseOperations(bytes, { StateFormats(null) }, true) }

    private fun parseOperations(bytes: TimelineBytes, initialFormats: () -> FormatLookup, envelopeOnly: Boolean): List<TimelineOperation> = exact(bytes) { reader ->
        val count = readVUInt(reader)
        protocolRequire(count <= TimelineProtocol.MAX_OPERATIONS && count <= reader.remaining / 2, "Invalid operation count")
        val formats = DeclaredFormats(initialFormats())
        val operations = ArrayList<TimelineOperation>(if (envelopeOnly) 0 else minOf(count.toInt(), 4096))
        var missing = false
        repeat(count.toInt()) {
            val opcode = reader.u8()
            protocolRequire(opcode in 1..6, "Unknown operation")
            val size = readVUInt(reader)
            protocolRequire(size <= TimelineProtocol.MAX_OPERATION_BYTES && size <= reader.remaining, "Operation body exceeds bounds")
            val body = reader.slice(size.toInt())
            val operation: TimelineOperation? = when (opcode) {
                1 -> {
                    val handle = readVUInt(body)
                    val kind = enumCode<TimelineObjectKind>(body.u8()) { it.code }
                    protocolRequire((handle == 0L) == (kind == TimelineObjectKind.ROOT), "Invalid ROOT identity")
                    val from = ordinaryTime(readVUInt(body))
                    val descriptor = decodeRecord(readVBytes(body, TimelineProtocol.MAX_OPERATION_BYTES))
                    validateDescriptor(kind, descriptor)
                    TimelineOperation.Create(handle, kind, from, descriptor)
                }
                2 -> {
                    val handle = readVUInt(body); val id = attribute(readVUInt(body))
                    val mode = enumCode<TimelineMode>(body.u8()) { it.code }
                    val type = enumCode<TimelineValueType>(body.u8()) { it.code }
                    val flags = body.u8(); protocolRequire(flags in 0..1, "Invalid curve flags")
                    val highWater = readVUInt(body)
                    protocolRequire(mode == TimelineMode.PULSE || highWater == 0L, "Non-pulse event high water")
                    val metadata = decodeRecord(readVBytes(body, TimelineProtocol.MAX_OPERATION_BYTES))
                    validateFormat(mode, type); validateMetadata(mode, metadata)
                    formats.declare(handle, id, TimelineCurveFormat(mode, type))
                    TimelineOperation.Declare(handle, id, mode, type, flags == 1, highWater, metadata)
                }
                3, 4 -> {
                    val handle = readVUInt(body); val id = attribute(readVUInt(body)); val expected = readVUInt(body)
                    protocolRequire(expected < Long.MAX_VALUE, "Curve revision would overflow")
                    val from = ordinaryTime(readVUInt(body)); val until = readVUInt(body)
                    protocolRequire(until > from, "Invalid splice interval")
                    val records = readVUInt(body)
                    protocolRequire(records <= TimelineProtocol.MAX_SPLICE_RECORDS && records <= body.remaining / (if (opcode == 3) 4 else 3), "Invalid splice count")
                    val format = formats.find(handle, id)
                    if (format == null) {
                        missing = true
                        if (records == 0L) body.finish() else body.slice(body.remaining)
                        null
                    } else if (opcode == 3) {
                        protocolRequire(format.mode != TimelineMode.PULSE, "Segment splice on PULSE")
                        val segments = ArrayList<TimelineSegment>(records.toInt())
                        var end = from
                        repeat(records.toInt()) {
                            val segment = readSegment(body, format.mode, format.valueType)
                            protocolRequire(segment.lo >= end && segment.hi <= until, "Overlapping or out-of-range segments")
                            end = segment.hi; segments.add(segment)
                        }
                        TimelineOperation.Splice(handle, id, expected, from, until, segments)
                    } else {
                        protocolRequire(format.mode == TimelineMode.PULSE, "Pulse splice on non-PULSE")
                        val events = ArrayList<TimelinePulse>(records.toInt()); val ids = HashSet<Long>()
                        var lastTime = -1L; var lastId = -1L
                        repeat(records.toInt()) {
                            val event = readPulse(body, format.valueType)
                            protocolRequire(event.time in from until until && (event.time > lastTime || event.time == lastTime && event.eventId > lastId) && ids.add(event.eventId), "Unordered, repeated or out-of-range events")
                            lastTime = event.time; lastId = event.eventId; events.add(event)
                        }
                        TimelineOperation.Pulse(handle, id, expected, from, until, events)
                    }
                }
                5 -> {
                    val handle = readVUInt(body); protocolRequire(handle > 0, "Cannot close ROOT")
                    TimelineOperation.Close(handle, ordinaryTime(readVUInt(body)), enumCode<TimelineCloseKind>(body.u8()) { it.code })
                }
                else -> TimelineOperation.Retain(ordinaryTime(readVUInt(body)))
            }
            body.finish()
            if (!envelopeOnly && operation != null) operations.add(operation)
        }
        reader.finish()
        if (missing && !envelopeOnly) throw MissingBase()
        frozenList(operations)
    }

    fun encodeOperations(operations: List<TimelineOperation>, initialFormats: Map<TimelineCurveKey, TimelineCurveFormat> = emptyMap()): TimelineBytes =
        encodeOperationsUsing(operations) { MapFormats(initialFormats) }

    internal fun encodeOperationsFromState(operations: List<TimelineOperation>, base: TimelineState?): TimelineBytes =
        encodeOperationsUsing(operations) { StateFormats(base) }

    private fun encodeOperationsUsing(operations: List<TimelineOperation>, initialFormats: () -> FormatLookup): TimelineBytes {
        protocolRequire(operations.size <= TimelineProtocol.MAX_OPERATIONS, "Too many operations")
        val formats = DeclaredFormats(initialFormats())
        val bytes = encoded { out ->
            writeVUInt(out, operations.size.toLong())
            for (operation in operations) {
                val opcode = when (operation) {
                    is TimelineOperation.Create -> 1; is TimelineOperation.Declare -> 2; is TimelineOperation.Splice -> 3
                    is TimelineOperation.Pulse -> 4; is TimelineOperation.Close -> 5; is TimelineOperation.Retain -> 6
                }
                val body = encoded(TimelineProtocol.MAX_OPERATION_BYTES) { target ->
                    when (operation) {
                        is TimelineOperation.Create -> { writeVUInt(target, operation.handle); target.write(operation.kind.code); writeVUInt(target, operation.observedFrom); writeVRecord(target, operation.descriptor) }
                        is TimelineOperation.Declare -> {
                            writeVUInt(target, operation.handle); writeVUInt(target, operation.attributeId.toLong())
                            target.write(operation.mode.code); target.write(operation.valueType.code); target.write(if (operation.criticalAttribute) 1 else 0)
                            writeVUInt(target, operation.eventHighWater); writeVRecord(target, operation.metadata)
                            formats.declare(operation.handle, operation.attributeId, TimelineCurveFormat(operation.mode, operation.valueType))
                        }
                        is TimelineOperation.Splice -> {
                            writeVUInt(target, operation.handle); writeVUInt(target, operation.attributeId.toLong()); writeVUInt(target, operation.expectedRevision)
                            writeVUInt(target, operation.from); writeVUInt(target, operation.until); writeVUInt(target, operation.segments.size.toLong())
                            val format = formats.find(operation.handle, operation.attributeId) ?: throw MissingBase()
                            operation.segments.forEach { writeSegment(target, format.mode, format.valueType, it) }
                        }
                        is TimelineOperation.Pulse -> {
                            writeVUInt(target, operation.handle); writeVUInt(target, operation.attributeId.toLong()); writeVUInt(target, operation.expectedRevision)
                            writeVUInt(target, operation.from); writeVUInt(target, operation.until); writeVUInt(target, operation.events.size.toLong())
                            val format = formats.find(operation.handle, operation.attributeId) ?: throw MissingBase()
                            protocolRequire(format.mode == TimelineMode.PULSE, "Pulse splice on non-PULSE")
                            operation.events.forEach { writePulse(target, format.valueType, it) }
                        }
                        is TimelineOperation.Close -> { writeVUInt(target, operation.handle); writeVUInt(target, operation.untilTime); target.write(operation.closeKind.code) }
                        is TimelineOperation.Retain -> writeVUInt(target, operation.newFrom)
                    }
                }
                out.write(opcode); writeVBytes(out, body)
            }
        }
        // One validation path for sender and receiver, including operation order.
        parseOperations(bytes, initialFormats, false)
        return bytes
    }
}
