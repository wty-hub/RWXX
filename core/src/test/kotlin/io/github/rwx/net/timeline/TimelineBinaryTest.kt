package io.github.rwx.net.timeline

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TimelineBinaryTest {
    private fun bytes(hex: String) = TimelineBytes(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    private fun hex(value: TimelineBytes) = value.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(vararg fields: TimelineField) = TimelineRecord(fields.toList())

    @Test fun `all fixed TLV values and unknown optional bytes preserve exact canonical bytes`() {
        val sampleRecord = record(
            TimelineField(1, true, TimelineTlvValue.Bool(false)),
            TimelineField(2, false, TimelineTlvValue.U32(0xffff_ffffL)),
            TimelineField(3, false, TimelineTlvValue.U64(Long.MAX_VALUE)),
            TimelineField(4, false, TimelineTlvValue.I64(Long.MIN_VALUE)),
            TimelineField(5, false, TimelineTlvValue.Text("地图🛰️é")),
            TimelineField(6, false, TimelineTlvValue.Bytes(bytes("00ff80"))),
            TimelineField(7, false, TimelineTlvValue.Record(TimelineRecord.EMPTY)),
            TimelineField(8, false, TimelineTlvValue.Records(listOf(TimelineRecord.EMPTY))),
            TimelineField(9, false, TimelineTlvValue.Opaque(240, bytes("deadcafe"))),
        )
        val encoded = TimelineBinary.encodeRecord(sampleRecord)
        assertEquals(sampleRecord, TimelineBinary.decodeRecord(encoded))
        assertEquals(encoded, TimelineBinary.encodeRecord(TimelineBinary.decodeRecord(encoded)))
        TimelineBinary.validateSchema(record(sampleRecord.fields.last()), emptyMap())
        assertFailsWith<ProtocolException> { TimelineBinary.encodeRecord(record(sampleRecord.fields.last().copy(critical = true))) }
        assertFailsWith<ProtocolException> { TimelineBinary.validateSchema(record(sampleRecord.fields.first()), emptyMap()) }
        assertFailsWith<ProtocolException> { TimelineBinary.validateSchema(record(sampleRecord.fields.first()), mapOf(1 to TimelineFieldRule(2))) }
    }

    @Test fun `immutable bytes records and private state collections have no caller aliases`() {
        val mutable = byteArrayOf(1, 2, 3)
        val frozen = TimelineBytes(mutable)
        mutable[0] = 9
        frozen.toByteArray()[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), frozen.toByteArray())
        val hostileOutput = object : java.io.OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) { bytes.fill(9, offset, offset + length) }
        }
        frozen.writeTo(hostileOutput)
        assertContentEquals(byteArrayOf(1, 2, 3), frozen.toByteArray())
        val fields = mutableListOf(TimelineField(1, false, TimelineTlvValue.Bytes(frozen)))
        val record = TimelineRecord(fields)
        fields.clear()
        assertEquals(1, record.fields.size)
        assertFailsWith<UnsupportedOperationException> { (record.fields as MutableList<TimelineField>).clear() }
        val segments = mutableListOf<TimelineSegment>(TimelineSegment.Step(0, 10, false, TimelineValue.SInt(7)))
        val curve = TimelineCurve(1, TimelineMode.STEP, TimelineValueType.SINT, true, segments = segments)
        segments.clear()
        assertEquals(1, curve.segments.size)
        assertFailsWith<UnsupportedOperationException> { (curve.segments as MutableList<TimelineSegment>).clear() }
    }

    @Test fun `canonical integers cover 63bit boundaries and reject redundant and overflowing forms`() {
        val unsigned = mapOf(0L to "00", 127L to "7f", 128L to "8001", Long.MAX_VALUE to "ffffffffffffffff7f")
        unsigned.forEach { (value, expected) ->
            assertEquals(expected, hex(TimelineBinary.encodeVUInt(value)))
            assertEquals(value, TimelineBinary.decodeVUInt(bytes(expected)))
            assertEquals(value, TimelineBinary.readVUInt(ByteArrayInputStream(bytes(expected).toByteArray())))
        }
        val signed = mapOf(0L to "00", -1L to "01", 1L to "02", TimelineProtocol.MIN_SINT to "ffffffffffffffff7f", TimelineProtocol.MAX_SINT to "feffffffffffffff7f")
        signed.forEach { (value, expected) ->
            assertEquals(expected, hex(TimelineBinary.encodeVSInt(value)))
            assertEquals(value, TimelineBinary.decodeVSInt(bytes(expected)))
        }
        for (invalid in listOf("", "80", "8000", "8100", "ffffffffffffffffff00", "80808080808080808001", "0000")) {
            assertFailsWith<ProtocolException>(invalid) { TimelineBinary.decodeVUInt(bytes(invalid)) }
        }
        assertFailsWith<ProtocolException> { TimelineBinary.encodeVUInt(-1) }
        assertFailsWith<ProtocolException> { TimelineBinary.encodeVSInt(TimelineProtocol.MIN_SINT - 1) }
        assertFailsWith<ProtocolException> { TimelineBinary.encodeVSInt(TimelineProtocol.MAX_SINT + 1) }
    }

    @Test fun `frames reject old versions and capacity before body allocation and consume exactly one frame`() {
        val payload = TimelineProtocol.MAX_FRAME_PAYLOAD - 16
        val frame = TimelineFrame(101, record(TimelineField(1, false, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(payload))))))
        val encoded = TimelineBinary.encodeFrame(frame)
        assertEquals(TimelineProtocol.MAX_FRAME_PAYLOAD + 4, encoded.size)
        assertEquals(frame, TimelineBinary.decodeFrame(encoded))
        assertFailsWith<ProtocolException> { TimelineBinary.encodeFrame(frame.copy(body = record(TimelineField(1, false, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(payload + 1))))))) }
        assertFailsWith<ProtocolException> { TimelineBinary.readFrame(ByteArrayInputStream(byteArrayOf(0x7f, -1, -1, -1))) }
        val empty = TimelineBinary.encodeFrame(TimelineFrame(1, TimelineRecord.EMPTY)).toByteArray()
        for (version in listOf(4, 5, 6, 8)) assertFailsWith<ProtocolException> {
            TimelineBinary.decodeFrame(empty.copyOf().also { ByteBuffer.wrap(it).putShort(8, version.toShort()) })
        }
        assertFailsWith<ProtocolException> { TimelineBinary.decodeFrame(empty + byteArrayOf(0)) }
        val stream = ByteArrayInputStream(empty + empty)
        assertEquals(1, TimelineBinary.readFrame(stream).type)
        assertEquals(empty.size, stream.available())
        assertEquals(1, TimelineBinary.readFrame(stream).type)
        assertEquals(0, stream.available())
    }

    @Test fun `record bounds utf8 fixed widths and duplicate ids fail closed`() {
        var nested = record(TimelineField(1, false, TimelineTlvValue.Bool(true)))
        repeat(7) { nested = record(TimelineField(1, false, TimelineTlvValue.Record(nested))) }
        TimelineBinary.decodeRecord(TimelineBinary.encodeRecord(nested))
        assertFailsWith<ProtocolException> { TimelineBinary.encodeRecord(record(TimelineField(1, false, TimelineTlvValue.Record(nested)))) }
        val fields = (1..256).map { TimelineField(it, false, TimelineTlvValue.Bool(false)) }
        assertEquals(256, TimelineBinary.decodeRecord(TimelineBinary.encodeRecord(TimelineRecord(fields))).fields.size)
        assertFailsWith<ProtocolException> { TimelineBinary.encodeRecord(TimelineRecord(fields + TimelineField(257, false, TimelineTlvValue.Bool(false)))) }
        for (malformed in listOf("000100010000000102", "00010003000000088000000000000000", "0001000500000002c080", "0001000500000003eda080", "000100060fffffff", "000100080000000400010001")) {
            assertFailsWith<ProtocolException>(malformed) { TimelineBinary.decodeRecord(bytes(malformed)) }
        }
        for (text in listOf("\u0000", "\n", "\u007f", "\u0085", "\ud800")) {
            assertFailsWith<ProtocolException> { TimelineBinary.encodeRecord(record(TimelineField(1, false, TimelineTlvValue.Text(text)))) }
        }
        assertFailsWith<ProtocolException> { TimelineBinary.encodeRecord(TimelineRecord(listOf(fields[1], fields[0]))) }
        assertFailsWith<ProtocolException> { TimelineBinary.encodeRecord(TimelineRecord(listOf(fields[0], fields[0]))) }
    }

    @Test fun `whole rational interpolation half turn and clipping do not change sealed samples`() {
        val negative = TimelineSegment.Interpolated(0, 2, false, 0, 2, TimelineValue.SInt(-1), TimelineValue.SInt(0))
        assertEquals(TimelineValue.SInt(-1), TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.SINT, negative, 1))
        val original = TimelineSegment.Interpolated(0, 100, true, 0, 100,
            TimelineValue.Vec3(0, -100, 200), TimelineValue.Vec3(100, 100, -200))
        val clipped = original.clipped(20, 60)
        assertEquals(0L, clipped.anchor0Time)
        assertEquals(100L, clipped.anchor1Time)
        for (time in 20L until 60) assertEquals(TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.VEC3, original, time),
            TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.VEC3, clipped, time))
        assertNull(TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.VEC3, clipped, 60))
        for ((a, b, expected) in listOf(Triple(0L, 32768L, 16384L), Triple(32768L, 0L, 49152L), Triple(65530L, 6L, 0L), Triple(0L, 65535L, 65535L))) {
            val angle = TimelineSegment.Interpolated(0, 2, false, 0, 2, TimelineValue.UInt(a), TimelineValue.UInt(b))
            assertEquals(TimelineValue.UInt(expected), TimelineBinary.sample(TimelineMode.ANGLE, TimelineValueType.UINT, angle, 1))
        }
        val end = TimelineProtocol.MAX_TIME
        val huge = TimelineSegment.Interpolated(end - 10, end, false, end - 10, end,
            TimelineValue.SInt(TimelineProtocol.MIN_SINT), TimelineValue.SInt(TimelineProtocol.MAX_SINT))
        assertEquals(TimelineValue.SInt(-1), TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.SINT, huge, end - 5))
        assertFailsWith<ProtocolException> { TimelineBinary.encodeSegment(TimelineMode.LINEAR, TimelineValueType.SINT, huge.copy(hi = Long.MAX_VALUE, anchor1Time = Long.MAX_VALUE)) }
        assertFailsWith<ProtocolException> { TimelineBinary.encodePulse(TimelineValueType.UINT, TimelinePulse(0, 0, TimelineValue.UInt(0))) }
        assertFailsWith<ProtocolException> { TimelineBinary.encodePulse(TimelineValueType.UINT, TimelinePulse(Long.MAX_VALUE, 1, TimelineValue.UInt(0))) }
        val forever = TimelineSegment.Interpolated(0, Long.MAX_VALUE, false, 0, Long.MAX_VALUE, TimelineValue.UInt(7), TimelineValue.UInt(7))
        assertEquals(TimelineValue.UInt(7), TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.UINT, forever, TimelineProtocol.MAX_TIME))
    }

    @Test fun `operation declarations affect later typed splices and missing base is distinct from malformed data`() {
        val ops = listOf(
            TimelineOperation.Create(0, TimelineObjectKind.ROOT, 0),
            TimelineOperation.Declare(0, 1, TimelineMode.STEP, TimelineValueType.SINT, true),
            TimelineOperation.Splice(0, 1, 0, 0, 100, listOf(TimelineSegment.Step(0, 100, false, TimelineValue.SInt(-3)))),
            TimelineOperation.Create(1, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(1, 65536, TimelineMode.PULSE, TimelineValueType.BLOB, false, 7,
                record(TimelineField(1, true, TimelineTlvValue.U64(10)))),
            TimelineOperation.Pulse(1, 65536, 0, 0, 100, listOf(TimelinePulse(20, 7, TimelineValue.Blob(bytes("0102"))))),
            TimelineOperation.Close(1, 100, TimelineCloseKind.ENDED),
            TimelineOperation.Retain(30),
        )
        val encoded = TimelineBinary.encodeOperations(ops)
        assertEquals(ops, TimelineBinary.decodeOperations(encoded))
        TimelineBinary.validateOperationEnvelope(encoded)
        val format = mapOf(TimelineCurveKey(0, 1) to TimelineCurveFormat(TimelineMode.STEP, TimelineValueType.SINT))
        val onlySplice = TimelineBinary.encodeOperations(listOf(ops[2]), format)
        assertFailsWith<MissingBase> { TimelineBinary.decodeOperations(onlySplice) }
        TimelineBinary.validateOperationEnvelope(onlySplice)
        assertEquals(listOf(ops[2]), TimelineBinary.decodeOperations(onlySplice, format))
        // A missing base must not hide a later unknown opcode.
        val malformed = onlySplice.toByteArray().also { it[0] = 2 } + byteArrayOf(99, 0)
        assertFailsWith<ProtocolException> { TimelineBinary.decodeOperations(TimelineBytes(malformed)) }
        assertFailsWith<ProtocolException> { TimelineBinary.validateOperationEnvelope(TimelineBytes(malformed)) }
        assertFailsWith<ProtocolException> { TimelineBinary.encodeOperations(ops + ops[1]) }
    }

    @Test fun `malformed operation count length order and blob capacities reject before allocation`() {
        for (malformed in listOf("818040", "01078080808000", "01018080808000", "010000", "0106020100")) {
            assertFailsWith<ProtocolException> { TimelineBinary.validateOperationEnvelope(bytes(malformed)) }
        }
        val format = mapOf(TimelineCurveKey(2, 1) to TimelineCurveFormat(TimelineMode.STEP, TimelineValueType.UINT))
        assertFailsWith<ProtocolException> { TimelineBinary.encodeOperations(listOf(TimelineOperation.Splice(2, 1, 0, 0, 20,
            listOf(TimelineSegment.Step(0, 15, false, TimelineValue.UInt(0)), TimelineSegment.Step(10, 20, false, TimelineValue.UInt(1))))), format) }
        assertFailsWith<ProtocolException> { TimelineBinary.encodeValue(TimelineValueType.BLOB, TimelineValue.Blob(TimelineBytes(ByteArray(65537)))) }
        assertEquals(65539, TimelineBinary.encodeValue(TimelineValueType.BLOB, TimelineValue.Blob(TimelineBytes(ByteArray(65536)))).size)
        val tooMany = TimelineOperation.Splice(2, 1, 0, 0, 4097, (0L..4096).map { TimelineSegment.Step(it, it + 1, false, TimelineValue.UInt(0)) })
        assertFailsWith<ProtocolException> { TimelineBinary.encodeOperations(listOf(tooMany), format) }
    }

    private fun mapRecord(terrain: Byte = 0): TimelineRecord = TimelineRecord((1..10).map { id ->
        val value: TimelineTlvValue = when (id) {
            4, 5 -> TimelineTlvValue.U32(20)
            6 -> TimelineTlvValue.Bytes(TimelineBytes(byteArrayOf(terrain)))
            8 -> TimelineTlvValue.Bytes(bytes("0001"))
            9 -> TimelineTlvValue.Bytes(bytes("0000"))
            10 -> TimelineTlvValue.Bytes(bytes("00"))
            else -> TimelineTlvValue.U32(1)
        }
        TimelineField(id, true, value)
    })

    private fun state(): TimelineState {
        val map = mapRecord()
        val context = TimelineContext(TimelineBytes(ByteArray(16) { it.toByte() }), TimelineBytes(ByteArray(16) { (it + 16).toByte() }),
            TimelineCanonical.mapKey(map), map, 0, 60)
        val header = TimelineHeader(1, 1, TimelineTransactionKind.RESET, 1, 0, 100, 50, 0, 200, 1, 1,
            TimelineCanonical.sha256(bytes("00")), 2)
        val root = TimelineObject(0, TimelineObjectKind.ROOT, 0, curves = mapOf(1 to
            TimelineCurve(1, TimelineMode.STEP, TimelineValueType.SINT, true, curveRevision = 1,
                segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.SInt(1024))))))
        val unit = TimelineObject(1, TimelineObjectKind.UNIT, 0,
            record(TimelineField(1, false, TimelineTlvValue.Text("tank"))), curves = linkedMapOf(
                8 to TimelineCurve(8, TimelineMode.STEP, TimelineValueType.UINT, true, curveRevision = 3,
                    segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.UInt(1)))),
                7 to TimelineCurve(7, TimelineMode.STEP, TimelineValueType.BLOB, true, curveRevision = 2,
                    segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.Blob(TimelineBytes("tank".toByteArray()))))),
                1 to TimelineCurve(1, TimelineMode.LINEAR, TimelineValueType.VEC3, true, curveRevision = 1,
                    segments = listOf(TimelineSegment.Interpolated(0, 100, false, 0, 100, TimelineValue.Vec3(0, 0, 0), TimelineValue.Vec3(100, 100, 10)))),
            ))
        val visual = TimelineObject(2, TimelineObjectKind.VISUAL, 0, curves = mapOf(65536 to
            TimelineCurve(65536, TimelineMode.PULSE, TimelineValueType.BLOB, false, 4,
                record(TimelineField(1, true, TimelineTlvValue.U64(100)), TimelineField(9, false, TimelineTlvValue.Opaque(240, bytes("deadcafe")))),
                curveRevision = 2, events = listOf(TimelinePulse(20, 2, TimelineValue.Blob(bytes("0102"))), TimelinePulse(50, 3, TimelineValue.Blob(bytes("03")))))))
        return TimelineState(context, header, linkedMapOf(2L to visual, 0L to root, 1L to unit))
    }

    @Test fun `complete streaming state hash includes map history identity watermarks tails and opaque state`() {
        val original = state()
        val initial = TimelineCanonical.digest(original)
        assertEquals(16, initial.size)
        val full = ByteArrayOutputStream().also { TimelineCanonical.write(original, it) }.toByteArray()
        assertContentEquals("RWXT-STATE-7\u0000".toByteArray(Charsets.US_ASCII), full.copyOfRange(0, 13))
        assertEquals(TimelineBytes(java.security.MessageDigest.getInstance("SHA-256").digest(full).copyOf(16)), initial)
        val persistent = ByteArrayOutputStream().also { TimelineCanonical.writePersistent(original, it) }.size()
        assertEquals(persistent.toLong(), TimelineCanonical.persistentSize(original))
        val variants = mutableListOf(
            original.copy(context = original.context.copy(mapRecord = mapRecord(1))),
            original.copy(context = original.context.copy(accessId = TimelineBytes(ByteArray(16)))),
            original.copy(header = original.header.copy(objectHighWater = 3)),
            original.copy(header = original.header.copy(sealedThrough = 51)),
            original.copy(header = original.header.copy(revision = 2)),
            original.copy(header = original.header.copy(controlEpoch = 2)),
        )
        fun replaceCurve(handle: Long, id: Int, replacement: TimelineCurve): TimelineState {
            val item = original.objects.getValue(handle)
            return original.copy(objects = original.objects + (handle to item.copy(curves = item.curves + (id to replacement))))
        }
        val tier = original.objects.getValue(1).curves.getValue(8)
        variants += replaceCurve(1, 8, tier.copy(segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.UInt(2)))))
        variants += replaceCurve(1, 8, tier.copy(curveRevision = 4))
        val appearance = original.objects.getValue(1).curves.getValue(7)
        variants += replaceCurve(1, 7, appearance.copy(segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.Blob(bytes("626f74"))))))
        val pulse = original.objects.getValue(2).curves.getValue(65536)
        variants += replaceCurve(2, 65536, pulse.copy(eventHighWater = 5))
        variants += replaceCurve(2, 65536, pulse.copy(events = pulse.events.dropLast(1)))
        variants += original.copy(objects = original.objects + (2L to original.objects.getValue(2).copy(close = TimelineClose(100, TimelineCloseKind.ENDED))))
        variants += replaceCurve(2, 65536, pulse.copy(metadata = record(TimelineField(1, true, TimelineTlvValue.U64(101)),
            TimelineField(9, false, TimelineTlvValue.Opaque(240, bytes("deadcaff"))))))
        for (variant in variants) assertFalse(TimelineCanonical.matches(initial, TimelineCanonical.digest(variant)))
        assertEquals(initial, TimelineCanonical.digest(original.copy(objects = original.objects.toSortedMap())))
        assertEquals(initial, TimelineCanonical.digest(original))
        // This constant is independently calculated from the public grammar in Python.
        assertEquals(177L, TimelineCanonical.persistentSize(original))
        assertEquals(365, full.size)
        assertEquals("d9d58a53f9d767f20b481f6cc3a24bed", hex(initial))
    }

    @Test fun `public foundation vectors decode expected values and reencode identical bytes`() {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "docs/protocol/v7/vectors.json") }.firstOrNull { it.isFile }
            ?: error("Public v7 vectors not found")
        val vectors = Json.parseToJsonElement(file.readText()).jsonObject.getValue("vectors").jsonArray
        var checked = 0
        for (item in vectors) {
            val vector = item.jsonObject
            val name = vector.getValue("name").jsonPrimitive.content
            val accepted = vector.getValue("accepted").jsonPrimitive.boolean
            fun verify() {
                val data = bytes(vector["hex"]?.jsonPrimitive?.content ?: "")
                val operation = vector.getValue("operation").jsonPrimitive.content
                var output = data
                val actual: JsonElement = when (operation) {
                    "frame" -> TimelineBinary.decodeFrame(data).let { frame -> output = TimelineBinary.encodeFrame(frame)
                        JsonObject(mapOf("messageType" to JsonPrimitive(frame.type), "fields" to jsonRecord(frame.body))) }
                    "tlv", "schema" -> TimelineBinary.decodeRecord(data).let { record ->
                        if (operation == "schema") TimelineBinary.validateSchema(record, vector.getValue("schema").jsonObject.mapKeys { it.key.toInt() }.mapValues { (_, value) ->
                            val rule = value.jsonObject; TimelineFieldRule(rule.getValue("kind").jsonPrimitive.int,
                                rule["required"]?.jsonPrimitive?.booleanOrNull ?: false, rule["critical"]?.jsonPrimitive?.booleanOrNull)
                        })
                        output = TimelineBinary.encodeRecord(record); jsonRecord(record)
                    }
                    "uleb128" -> TimelineBinary.decodeVUInt(data).let { output = TimelineBinary.encodeVUInt(it); JsonPrimitive(it.toString()) }
                    "zigzag63" -> TimelineBinary.decodeVSInt(data).let { output = TimelineBinary.encodeVSInt(it); JsonPrimitive(it.toString()) }
                    "value" -> valueType(vector).let { type -> TimelineBinary.decodeValue(type, data).let { output = TimelineBinary.encodeValue(type, it); jsonValue(it) } }
                    "segment", "sample" -> {
                        val mode = TimelineMode.entries.firstOrNull { it.code == vector.getValue("mode").jsonPrimitive.int } ?: throw ProtocolException("Unknown mode")
                        val type = valueType(vector); val segment = TimelineBinary.decodeSegment(mode, type, data)
                        output = TimelineBinary.encodeSegment(mode, type, segment)
                        if (operation == "sample") TimelineBinary.sample(mode, type, segment, vector.getValue("time").jsonPrimitive.content.toLong())?.let(::jsonValue) ?: JsonNull
                        else jsonSegment(segment)
                    }
                    "pulse" -> valueType(vector).let { type -> TimelineBinary.decodePulse(type, data).let { pulse ->
                        output = TimelineBinary.encodePulse(type, pulse)
                        JsonObject(mapOf("time" to JsonPrimitive(pulse.time.toString()), "eventId" to JsonPrimitive(pulse.eventId.toString()), "value" to jsonValue(pulse.value)))
                    } }
                    "encodeValue" -> throw ProtocolException("Floating JSON values have no v7 integer representation")
                    else -> error("Unhandled vector operation")
                }
                assertEquals(vector["expected"], actual, name)
                assertEquals(data, output, name)
            }
            if (accepted) verify() else assertFailsWith<ProtocolException>(name) { verify() }
            checked++
        }
        assertEquals(140, checked)
    }

    private fun valueType(vector: JsonObject): TimelineValueType = TimelineValueType.entries.firstOrNull {
        it.code == vector.getValue("valueType").jsonPrimitive.int
    } ?: throw ProtocolException("Unknown value type")

    private fun jsonRecord(record: TimelineRecord): JsonArray = JsonArray(record.fields.map { field ->
        val value: JsonElement = when (val value = field.value) {
            is TimelineTlvValue.Bool -> JsonPrimitive(value.value)
            is TimelineTlvValue.U32 -> JsonPrimitive(value.value.toString())
            is TimelineTlvValue.U64 -> JsonPrimitive(value.value.toString())
            is TimelineTlvValue.I64 -> JsonPrimitive(value.value.toString())
            is TimelineTlvValue.Text -> JsonPrimitive(value.value)
            is TimelineTlvValue.Bytes -> JsonObject(mapOf("hex" to JsonPrimitive(hex(value.value))))
            is TimelineTlvValue.Opaque -> JsonObject(mapOf("hex" to JsonPrimitive(hex(value.value))))
            is TimelineTlvValue.Record -> jsonRecord(value.value)
            is TimelineTlvValue.Records -> JsonArray(value.values.map(::jsonRecord))
        }
        JsonObject(mapOf("id" to JsonPrimitive(field.id), "flags" to JsonPrimitive(if (field.critical) 1 else 0), "kind" to JsonPrimitive(field.value.kind), "value" to value))
    })
    private fun jsonValue(value: TimelineValue): JsonElement = when (value) {
        is TimelineValue.SInt -> JsonPrimitive(value.value.toString())
        is TimelineValue.UInt -> JsonPrimitive(value.value.toString())
        is TimelineValue.Vec3 -> JsonArray(listOf(value.x, value.y, value.z).map { JsonPrimitive(it.toString()) })
        is TimelineValue.Bool -> JsonPrimitive(value.value)
        is TimelineValue.Blob -> JsonObject(mapOf("hex" to JsonPrimitive(hex(value.value))))
    }
    private fun jsonSegment(segment: TimelineSegment): JsonObject {
        val result = mutableMapOf<String, JsonElement>("lo" to JsonPrimitive(segment.lo.toString()), "hi" to JsonPrimitive(segment.hi.toString()), "predicted" to JsonPrimitive(segment.predicted))
        when (segment) {
            is TimelineSegment.Step -> result["value"] = jsonValue(segment.value)
            is TimelineSegment.Interpolated -> {
                result["anchor0Time"] = JsonPrimitive(segment.anchor0Time.toString()); result["anchor1Time"] = JsonPrimitive(segment.anchor1Time.toString())
                result["value0"] = jsonValue(segment.value0); result["value1"] = jsonValue(segment.value1)
            }
        }
        return JsonObject(result)
    }
}
