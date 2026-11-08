package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

/** Independent public byte fixtures: no engine, sockets, assets or native UI. */
class TimelineReferenceFixturesTest {
    private fun fixture(name: String): JsonObject {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "docs/protocol/v7/$name") }.firstOrNull { it.isFile }
            ?: error("Public v7 fixture not found")
        return Json.parseToJsonElement(file.readText()).jsonObject.also { assertEquals(7, it.getValue("wireVersion").jsonPrimitive.int) }
    }
    private fun bytes(hex: String) = TimelineBytes(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.record(key: String) = TimelineBinary.decodeRecord(bytes(text(key)))
    private fun <T> byCode(code: Int, values: Iterable<T>, getCode: (T) -> Int): T = values.single { getCode(it) == code }

    @Test fun `all 22 message schemas directions and rejection fixtures agree byte for byte`() {
        val cases = fixture("message-vectors.json").getValue("vectors").jsonArray
        val kinds = HashSet<Int>()
        var accepted = 0; var rejected = 0
        for (item in cases) {
            val case = item.jsonObject; val raw = bytes(case.text("hex")); val client = case.text("direction") == "C2S"
            fun decode(): TimelineFrame = TimelineBinary.decodeFrame(raw).also { TimelineMessages.validate(it, client) }
            if (case.flag("accepted")) {
                val frame = decode()
                assertEquals(case.number("messageType").toInt(), frame.type, case.text("name"))
                assertEquals(raw, TimelineBinary.encodeFrame(frame), case.text("name"))
                kinds.add(frame.type); accepted++
            } else {
                assertFailsWith<ProtocolException>(case.text("name")) { decode() }; rejected++
            }
        }
        assertEquals((1..10).toSet() + (101..112).toSet(), kinds)
        assertEquals(24, accepted); assertEquals(59, rejected)
    }

    @Test fun `all six operation formats agree including explicit missing context envelope checks`() {
        var accepted = 0; var rejected = 0
        for (item in fixture("transaction-vectors.json").getValue("vectors").jsonArray) {
            val case = item.jsonObject; val raw = bytes(case.text("operationsHex"))
            val initial = case.getValue("initialFormats").jsonArray.associate { item ->
                val a = item.jsonArray
                TimelineCurveKey(a[0].jsonPrimitive.long, a[1].jsonPrimitive.int) to TimelineCurveFormat(
                    byCode(a[2].jsonPrimitive.int, TimelineMode.entries) { it.code },
                    byCode(a[3].jsonPrimitive.int, TimelineValueType.entries) { it.code })
            }
            val envelopeOnly = case.flag("allowMissingContext")
            fun decode(): List<TimelineOperation> = if (envelopeOnly) {
                TimelineBinary.validateOperationEnvelope(raw)
                try { TimelineBinary.decodeOperations(raw, initial) } catch (_: MissingBase) { emptyList() }
            } else TimelineBinary.decodeOperations(raw, initial)
            if (case.flag("accepted")) {
                val operations = decode()
                if (!envelopeOnly) assertEquals(raw, TimelineBinary.encodeOperations(operations, initial), case.text("name"))
                else assertFailsWith<MissingBase> { TimelineBinary.decodeOperations(raw, initial) }
                val formats = initial.toMutableMap()
                operations.filterIsInstance<TimelineOperation.Declare>().forEach {
                    formats[TimelineCurveKey(it.handle, it.attributeId)] = TimelineCurveFormat(it.mode, it.valueType)
                }
                val actual = JsonArray(formats.entries.sortedWith(compareBy({ it.key.handle }, { it.key.attributeId })).map { (key, value) ->
                    JsonArray(listOf(JsonPrimitive(key.handle.toString()), JsonPrimitive(key.attributeId.toString()),
                        JsonPrimitive(value.mode.code), JsonPrimitive(value.valueType.code)))
                })
                assertEquals(case.getValue("expectedFormats"), actual, case.text("name")); accepted++
            } else {
                // Missing context is deliberately distinct from malformed bytes, but both reject a complete decode.
                assertFailsWith<java.io.IOException>(case.text("name")) { decode() }; rejected++
            }
        }
        assertEquals(4, accepted); assertEquals(11, rejected)
    }

    @Test fun `independent complete canonical bytes sizes and digests match and complete profiles restore`() {
        val cases = fixture("state-fixtures.json").getValue("states").jsonArray
        assertEquals(3, cases.size)
        for ((index, item) in cases.withIndex()) {
            val case = item.jsonObject; val state = state(case.getValue("state").jsonObject)
            val encoded = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
            assertEquals(bytes(case.text("canonicalHex")), TimelineBytes(encoded), case.text("name"))
            assertEquals(case.number("canonicalBytes"), encoded.size.toLong())
            assertEquals(case.number("persistentBytes"), TimelineCanonical.persistentSize(state))
            assertEquals(bytes(case.text("digest128")), TimelineCanonical.digest(state))
            if (index > 0) {
                TimelineProfile.validateContext(state.context)
                TimelineProfile.validate(state)
                val operations = TimelineEncoder.resetOperations(state)
                val raw = TimelineBinary.encodeOperations(operations)
                val header = state.header.copy(operationBytes = raw.size.toLong(), operationSha256 = TimelineCanonical.sha256(raw))
                val restored = TimelineStore(state.context).apply(header, raw)
                TimelineProfile.validate(restored)
                // A RESET rebuilds curve revision counters; those counters need not equal the source library.
                assertEquals(operations, TimelineEncoder.resetOperations(restored))
                assertEquals(state.objects.keys, restored.objects.keys)
                for ((handle, source) in state.objects) {
                    val target = restored.objects.getValue(handle)
                    assertEquals(source.descriptor, target.descriptor); assertEquals(source.close, target.close)
                    assertEquals(source.observedFrom, target.observedFrom); assertEquals(source.kind, target.kind)
                    assertEquals(source.curves.keys, target.curves.keys)
                    for ((id, curve) in source.curves) {
                        val restoredCurve = target.curves.getValue(id)
                        assertEquals(curve.format, restoredCurve.format); assertEquals(curve.metadata, restoredCurve.metadata)
                        assertEquals(curve.eventHighWater, restoredCurve.eventHighWater)
                        assertEquals(curve.criticalAttribute, restoredCurve.criticalAttribute)
                        assertEquals(curve.segments, restoredCurve.segments); assertEquals(curve.events, restoredCurve.events)
                    }
                }
            }
        }
    }

    private fun state(r: JsonObject): TimelineState {
        val context = TimelineContext(bytes(r.text("matchIdHex")), bytes(r.text("accessIdHex")), bytes(r.text("mapKeyHex")),
            r.record("mapHex"), r.number("teamId").toInt(), r.number("ticksPerSecond").toInt(),
            byCode(r.number("mode").toInt(), TimelineStreamMode.entries) { it.code }, profileId = r.number("profileId").toInt())
        val empty = TimelineBinary.encodeOperations(emptyList())
        val header = TimelineHeader(r.number("streamId"), r.number("epoch"), TimelineTransactionKind.RESET,
            r.number("revision"), 0, r.number("observedThrough"), r.number("sealedThrough"), r.number("retainedFrom"),
            r.number("retainedUntil"), r.number("controlEpoch"), empty.size.toLong(), TimelineCanonical.sha256(empty), r.number("objectHighWater"))
        val objects = r.getValue("objects").jsonArray.map { item ->
            val obj = item.jsonObject
            val close = obj["close"]?.takeUnless { it is JsonNull }?.jsonObject?.let {
                TimelineClose(it.number("until"), byCode(it.number("kind").toInt(), TimelineCloseKind.entries) { it.code })
            }
            TimelineObject(obj.number("handle"), byCode(obj.number("kind").toInt(), TimelineObjectKind.entries) { it.code },
                obj.number("observedFrom"), obj.record("descriptorHex"), close,
                obj.getValue("curves").jsonArray.map { curve(it.jsonObject) }.associateBy { it.attributeId })
        }.associateBy { it.handle }
        return TimelineState(context, header, objects)
    }

    private fun curve(r: JsonObject): TimelineCurve {
        val mode = byCode(r.number("mode").toInt(), TimelineMode.entries) { it.code }
        val type = byCode(r.number("valueType").toInt(), TimelineValueType.entries) { it.code }
        val segments = r.getValue("segments").jsonArray.map { item ->
            val s = item.jsonObject
            if (mode == TimelineMode.STEP) TimelineSegment.Step(s.number("lo"), s.number("hi"), s.flag("predicted"), value(type, s.getValue("value")))
            else TimelineSegment.Interpolated(s.number("lo"), s.number("hi"), s.flag("predicted"), s.number("anchor0Time"), s.number("anchor1Time"),
                value(type, s.getValue("value0")), value(type, s.getValue("value1")))
        }
        val events = r.getValue("events").jsonArray.map { item -> item.jsonObject.let {
            TimelinePulse(it.number("time"), it.number("eventId"), value(type, it.getValue("value")))
        } }
        return TimelineCurve(r.number("attributeId").toInt(), mode, type, r.flag("critical"), r.number("eventHighWater"),
            r.record("metadataHex"), r.number("curveRevision"), segments, events)
    }

    private fun value(type: TimelineValueType, value: JsonElement): TimelineValue = when (type) {
        TimelineValueType.SINT -> TimelineValue.SInt(value.jsonPrimitive.long)
        TimelineValueType.UINT -> TimelineValue.UInt(value.jsonPrimitive.long)
        TimelineValueType.BOOL -> TimelineValue.Bool(value.jsonPrimitive.boolean)
        TimelineValueType.BLOB -> TimelineValue.Blob(bytes(value.jsonObject.text("hex")))
        TimelineValueType.VEC3 -> value.jsonArray.let { TimelineValue.Vec3(it[0].jsonPrimitive.long, it[1].jsonPrimitive.long, it[2].jsonPrimitive.long) }
    }
}
