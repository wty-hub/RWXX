package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

class TimelinePackedCursorTest {
    @Test fun `canonical integer endpoints use copied input and reject overlong or truncated representations`() {
        for (value in listOf(0L, 127L, 128L, Long.MAX_VALUE)) {
            val input = TimelineBinary.encodeVUInt(value).toByteArray()
            val cursor = TimelinePackedCursor.copyOf(input)
            input.fill(0)
            assertEquals(value, cursor.vuint()); cursor.finish()
        }
        for (value in listOf(TimelineProtocol.MIN_SINT, -1L, 0L, 1L, TimelineProtocol.MAX_SINT)) {
            val cursor = TimelinePackedCursor.copyOf(TimelineBinary.encodeVSInt(value).toByteArray())
            assertEquals(value, cursor.vsint()); cursor.finish()
        }
        for (bytes in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0x80.toByte(), 0), ByteArray(10) { 0x80.toByte() }))
            assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(bytes).vuint() }
        assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(byteArrayOf(2)).bool() }
        val huge = TimelineBinary.encodeVUInt(TimelineProtocol.MAX_CHUNK_BYTES + 1L).toByteArray()
        assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(huge).value(TimelineValueType.BLOB) }
    }

    @Test fun `clipped anchors angle half turn and pulse bytes decode without retaining mutable input`() {
        val segment = TimelineSegment.Interpolated(3, 8, false, 0, 10,
            TimelineValue.UInt(65530), TimelineValue.UInt(32762))
        val raw = TimelineBinary.encodeSegment(TimelineMode.ANGLE, TimelineValueType.UINT, segment).toByteArray()
        val cursor = TimelinePackedCursor.copyOf(raw)
        val actual = cursor.segment(TimelineMode.ANGLE, TimelineValueType.UINT)
        cursor.finish()
        assertEquals(segment, actual)
        assertEquals(TimelineValue.UInt(16378), TimelineBinary.sample(TimelineMode.ANGLE, TimelineValueType.UINT, actual, 5))
        val pulse = TimelinePulse(TimelineProtocol.MAX_TIME, Long.MAX_VALUE, TimelineValue.SInt(TimelineProtocol.MIN_SINT))
        val eventCursor = TimelinePackedCursor.copyOf(TimelineBinary.encodePulse(TimelineValueType.SINT, pulse).toByteArray())
        assertEquals(pulse, eventCursor.pulse(TimelineValueType.SINT)); eventCursor.finish()
        val invalid = TimelineBinary.encodeVUInt(0).toByteArray() + TimelineBinary.encodeVUInt(0).toByteArray() + byteArrayOf(0)
        assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(invalid).segment(TimelineMode.STEP, TimelineValueType.UINT) }
    }

    @Test fun `optional opaque TLV roundtrips but critical unknown and trailing bytes fail`() {
        val record = TimelineRecord(listOf(TimelineField(14, false, TimelineTlvValue.Opaque(254, TimelineBytes(byteArrayOf(1, 2))))))
        val encoded = ByteArrayOutputStream().also { TimelineBinary.writeVRecord(it, record) }.toByteArray()
        val cursor = TimelinePackedCursor.copyOf(encoded)
        assertEquals(record, cursor.record()); cursor.finish()
        val critical = encoded.copyOf().also { it[3] = 1 }
        assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(critical).record() }
        val trailing = TimelinePackedCursor.copyOf(encoded + 0)
        assertEquals(record, trailing.record())
        assertFailsWith<ProtocolException> { trailing.finish() }
        val impossibleCount = ByteArrayOutputStream().also {
            TimelineBinary.writeVUInt(it, 1); it.write(1); TimelineBinary.writeVUInt(it, 0)
            it.write(0); TimelineBinary.writeVUInt(it, 0); TimelineBinary.writeVUInt(it, Long.MAX_VALUE)
        }.toByteArray()
        assertFailsWith<ProtocolException> { TimelinePackedCursor.copyOf(impossibleCount).objectHeader() }
    }
}
