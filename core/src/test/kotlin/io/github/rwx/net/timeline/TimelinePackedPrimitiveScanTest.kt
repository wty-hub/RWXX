package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

class TimelinePackedPrimitiveScanTest {
    private fun compareSegment(mode: TimelineMode, type: TimelineValueType, expected: TimelineSegment) {
        val bytes = TimelineBinary.encodeSegment(mode, type, expected).toByteArray()
        val typed = TimelinePackedCursor.copyOf(bytes)
        val primitive = TimelinePackedCursor.copyOf(bytes)
        bytes.fill(0)
        assertEquals(expected, typed.segment(mode, type)); typed.finish()
        primitive.scanSegment(mode, type); primitive.finish()
        val restored = if (mode == TimelineMode.STEP) TimelineSegment.Step(primitive.recordLo,
            primitive.recordHi, primitive.recordPredicted, primitive.scannedValue(0))
        else TimelineSegment.Interpolated(primitive.recordLo, primitive.recordHi, primitive.recordPredicted,
            primitive.recordAnchor0, primitive.recordAnchor1, primitive.scannedValue(0), primitive.scannedValue(1))
        assertEquals(expected, restored)
        assertEquals(typed.position, primitive.position)
    }

    @Test fun `primitive scans preserve every value kind and exact clipped and FOREVER record fields`() {
        val families = listOf(
            TimelineValueType.SINT to listOf(TimelineValue.SInt(TimelineProtocol.MIN_SINT), TimelineValue.SInt(0), TimelineValue.SInt(TimelineProtocol.MAX_SINT)),
            TimelineValueType.VEC3 to listOf(TimelineValue.Vec3(TimelineProtocol.MIN_SINT, 0, TimelineProtocol.MAX_SINT)),
            TimelineValueType.UINT to listOf(TimelineValue.UInt(0), TimelineValue.UInt(Long.MAX_VALUE)),
            TimelineValueType.BOOL to listOf(TimelineValue.Bool(false), TimelineValue.Bool(true)),
            TimelineValueType.BLOB to listOf(TimelineValue.Blob(TimelineBytes.EMPTY), TimelineValue.Blob(TimelineBytes(ByteArray(65536) { it.toByte() }))))
        for ((type, values) in families) for (value in values) {
            compareSegment(TimelineMode.STEP, type, TimelineSegment.Step(3, Long.MAX_VALUE, false, value))
            if (type in listOf(TimelineValueType.SINT, TimelineValueType.VEC3, TimelineValueType.UINT)) {
                compareSegment(TimelineMode.LINEAR, type, TimelineSegment.Interpolated(3, 8, true, 0, 10, value, values[0]))
                compareSegment(TimelineMode.LINEAR, type, TimelineSegment.Interpolated(3, Long.MAX_VALUE, false,
                    0, Long.MAX_VALUE, value, value))
            }
        }
        // Structural STEP parsing preserves the bit; profile validation separately rejects prediction.
        compareSegment(TimelineMode.STEP, TimelineValueType.SINT, TimelineSegment.Step(0, 1, true, TimelineValue.SInt(-1)))
        compareSegment(TimelineMode.STEP, TimelineValueType.UINT, TimelineSegment.Step(TimelineProtocol.MAX_TIME,
            Long.MAX_VALUE, false, TimelineValue.UInt(0)))
        compareSegment(TimelineMode.ANGLE, TimelineValueType.UINT, TimelineSegment.Interpolated(3, 8, false, 0, 10,
            TimelineValue.UInt(65530), TimelineValue.UInt(32762)))
        compareSegment(TimelineMode.ANGLE, TimelineValueType.UINT, TimelineSegment.Interpolated(3, Long.MAX_VALUE, false,
            0, Long.MAX_VALUE, TimelineValue.UInt(65535), TimelineValue.UInt(65535)))
    }

    @Test fun `one cursor consumes alternating record kinds without borrowing stale values or mutable source bytes`() {
        val a = TimelineSegment.Interpolated(2, 8, true, 0, 10, TimelineValue.Vec3(1, -2, 3), TimelineValue.Vec3(4, 5, -6))
        val b = TimelineSegment.Step(10, 20, false, TimelineValue.Blob(TimelineBytes(byteArrayOf(3, 5, 7))))
        val c = TimelinePulse(20, 19, TimelineValue.Bool(true))
        val encoded = TimelineBinary.encodeSegment(TimelineMode.LINEAR, TimelineValueType.VEC3, a).toByteArray() +
            TimelineBinary.encodeSegment(TimelineMode.STEP, TimelineValueType.BLOB, b).toByteArray() +
            TimelineBinary.encodePulse(TimelineValueType.BOOL, c).toByteArray()
        val reader = TimelinePackedCursor.copyOf(encoded); encoded.fill(0)
        reader.scanSegment(TimelineMode.LINEAR, TimelineValueType.VEC3)
        assertEquals(a.value1, reader.scannedValue(1)); assertTrue(reader.recordPredicted)
        reader.scanSegment(TimelineMode.STEP, TimelineValueType.BLOB)
        val frozenBlob = reader.scannedValue(0)
        assertEquals(b.value, frozenBlob); assertEquals(0L, reader.recordAnchor0); assertEquals(0L, reader.recordEventId)
        assertFailsWith<ProtocolException> { reader.scannedValue(1) }
        reader.scanPulse(TimelineValueType.BOOL)
        assertEquals(c.time, reader.recordLo); assertEquals(c.eventId, reader.recordEventId)
        assertEquals(c.value, reader.scannedValue(0)); assertFalse(reader.recordPredicted)
        assertEquals(0L, reader.recordHi); assertEquals(0L, reader.recordAnchor1)
        assertFailsWith<ProtocolException> { reader.scannedValue(1) }
        assertEquals(b.value, frozenBlob, "An explicitly materialized compatibility value owns its bytes")
        reader.finish()
    }

    @Test fun `owned-page slices keep byte bounds and explicitly materialized blobs own their values`() {
        val payload = TimelineValue.Blob(TimelineBytes(byteArrayOf(11, 23, 37)))
        val value = TimelineSegment.Step(3, 8, false, payload)
        val bytes = TimelineBinary.encodeSegment(TimelineMode.STEP, TimelineValueType.BLOB, value).toByteArray()
        val page = ByteArray(7 + bytes.size + 9) { 127 }
        bytes.copyInto(page, 7)
        val typed = TimelinePackedCursor.ownedPage(page, 7, bytes.size)
        val primitive = TimelinePackedCursor.ownedPage(page, 7, bytes.size)
        assertEquals(value, typed.segment(TimelineMode.STEP, TimelineValueType.BLOB)); typed.finish()
        primitive.scanSegment(TimelineMode.STEP, TimelineValueType.BLOB); primitive.finish()
        assertEquals(bytes.size, primitive.position)
        val frozen = primitive.scannedValue(0)
        assertEquals(payload, frozen)
        page[7 + bytes.size - 1] = 41
        assertEquals(payload, frozen, "The compatibility adapter cannot expose borrowed library bytes")
        val next = TimelinePackedCursor.ownedPage(page, 7, bytes.size)
        next.scanSegment(TimelineMode.STEP, TimelineValueType.BLOB); next.finish()
        assertEquals(TimelineValue.Blob(TimelineBytes(byteArrayOf(11, 23, 41))), next.scannedValue(0))
        assertFailsWith<ProtocolException> { TimelinePackedCursor.ownedPage(page, 7, bytes.size - 1)
            .scanSegment(TimelineMode.STEP, TimelineValueType.BLOB) }
    }

    private fun rawSegment(lo: Long, hi: Long, flags: Int = 0, anchors: Pair<Long, Long>? = null,
                           values: ByteArray = byteArrayOf(0)): ByteArray = ByteArrayOutputStream().also { out ->
        TimelineBinary.writeVUInt(out, lo); TimelineBinary.writeVUInt(out, hi); out.write(flags)
        anchors?.let { TimelineBinary.writeVUInt(out, it.first); TimelineBinary.writeVUInt(out, it.second) }
        out.write(values)
    }.toByteArray()

    @Test fun `malformed and truncated segment bytes reject at the same cursor validation boundary`() {
        data class Case(val mode: TimelineMode, val type: TimelineValueType, val bytes: ByteArray)
        val uints = TimelineBinary.encodeVUInt(3).toByteArray() + TimelineBinary.encodeVUInt(5).toByteArray()
        val cases = listOf(
            Case(TimelineMode.STEP, TimelineValueType.UINT, rawSegment(1, 1)),
            Case(TimelineMode.STEP, TimelineValueType.UINT, rawSegment(Long.MAX_VALUE, Long.MAX_VALUE)),
            Case(TimelineMode.STEP, TimelineValueType.UINT, rawSegment(0, 1, flags = 2)),
            Case(TimelineMode.STEP, TimelineValueType.BOOL, rawSegment(0, 1, values = byteArrayOf(2))),
            Case(TimelineMode.STEP, TimelineValueType.BLOB, rawSegment(0, 1, values = TimelineBinary.encodeVUInt(65537).toByteArray())),
            Case(TimelineMode.LINEAR, TimelineValueType.UINT, rawSegment(1, 4, anchors = 2L to 5L, values = uints)),
            Case(TimelineMode.LINEAR, TimelineValueType.UINT, rawSegment(1, 4, anchors = 0L to 3L, values = uints)),
            Case(TimelineMode.LINEAR, TimelineValueType.UINT, rawSegment(1, 4, anchors = 0L to Long.MAX_VALUE, values = uints)),
            Case(TimelineMode.ANGLE, TimelineValueType.UINT, rawSegment(1, 4, anchors = 0L to 5L,
                values = TimelineBinary.encodeVUInt(65536).toByteArray() + byteArrayOf(0))),
            Case(TimelineMode.LINEAR, TimelineValueType.BOOL, rawSegment(1, 4)),
            Case(TimelineMode.PULSE, TimelineValueType.UINT, byteArrayOf(0)),
            Case(TimelineMode.STEP, TimelineValueType.UINT, rawSegment(0, 1, values = byteArrayOf(0x80.toByte(), 0))))
        for (case in cases) {
            val a = assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(case.bytes).segment(case.mode, case.type) }
            val b = assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(case.bytes).scanSegment(case.mode, case.type) }
            assertEquals(a.message, b.message)
        }
        val valid = TimelineBinary.encodeSegment(TimelineMode.LINEAR, TimelineValueType.VEC3,
            TimelineSegment.Interpolated(3, 8, false, 0, 10, TimelineValue.Vec3(1, 2, 3), TimelineValue.Vec3(4, 5, 6))).toByteArray()
        for (length in 0 until valid.size) {
            val bytes = valid.copyOf(length)
            assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(bytes).segment(TimelineMode.LINEAR, TimelineValueType.VEC3) }
            assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(bytes).scanSegment(TimelineMode.LINEAR, TimelineValueType.VEC3) }
        }
    }

    @Test fun `primitive pulse scan preserves all payload types and rejects malformed identities and lengths`() {
        val values = listOf<TimelineValue>(TimelineValue.SInt(TimelineProtocol.MIN_SINT),
            TimelineValue.Vec3(1, -2, TimelineProtocol.MAX_SINT), TimelineValue.UInt(Long.MAX_VALUE),
            TimelineValue.Bool(false), TimelineValue.Blob(TimelineBytes(ByteArray(65536) { (it * 31).toByte() })))
        for (value in values) {
            val event = TimelinePulse(TimelineProtocol.MAX_TIME, Long.MAX_VALUE, value)
            val bytes = TimelineBinary.encodePulse(value.type, event).toByteArray()
            val typed = TimelinePackedCursor.copyOf(bytes); val primitive = TimelinePackedCursor.copyOf(bytes)
            assertEquals(event, typed.pulse(value.type)); typed.finish()
            primitive.scanPulse(value.type); primitive.finish()
            assertEquals(event.time, primitive.recordLo); assertEquals(event.eventId, primitive.recordEventId)
            assertEquals(event.value, primitive.scannedValue(0)); assertEquals(typed.position, primitive.position)
        }
        val malformed = listOf(
            TimelineBinary.encodeVUInt(Long.MAX_VALUE).toByteArray() + byteArrayOf(1, 0),
            byteArrayOf(0, 0, 0),
            byteArrayOf(0, 1, 2),
            byteArrayOf(0, 1, 0x80.toByte(), 0))
        for ((index, bytes) in malformed.withIndex()) {
            val type = if (index == 2) TimelineValueType.BOOL else TimelineValueType.UINT
            val a = assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(bytes).pulse(type) }
            val b = assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(bytes).scanPulse(type) }
            assertEquals(a.message, b.message)
        }
    }
}
