package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimelineRecordStreamTest {
    @Test fun `owned fixed writer and arbitrary stream preserve all nested record bytes`() {
        val nested = TimelineRecord(listOf(
            TimelineField(1, false, TimelineTlvValue.Text("地图🛰️é")),
            TimelineField(2, false, TimelineTlvValue.Bytes(TimelineBytes(byteArrayOf(0, -1, -128)))),
            TimelineField(3, false, TimelineTlvValue.Opaque(240, TimelineBytes(byteArrayOf(9, 8))))))
        val record = TimelineRecord(listOf(
            TimelineField(1, true, TimelineTlvValue.Bool(true)),
            TimelineField(2, false, TimelineTlvValue.U32(0xffff_ffffL)),
            TimelineField(3, false, TimelineTlvValue.U64(Long.MAX_VALUE)),
            TimelineField(4, false, TimelineTlvValue.I64(Long.MIN_VALUE)),
            TimelineField(5, false, TimelineTlvValue.Record(nested)),
            TimelineField(6, false, TimelineTlvValue.Records(listOf(TimelineRecord.EMPTY, nested)))))
        for (value in listOf(TimelineRecord.EMPTY, nested, record)) {
            val body = TimelineBinary.encodeRecord(value).toByteArray()
            val expected = TimelineBinary.encodeVUInt(body.size.toLong()).toByteArray() + body
            val ordinary = ByteArrayOutputStream()
            TimelineBinary.writeVRecord(ordinary as OutputStream, value)
            assertContentEquals(expected, ordinary.toByteArray())
            val owned = ByteArrayOutputStream()
            TimelineBinary.writeVRecord(DataOutputStream(owned), value)
            assertContentEquals(expected, owned.toByteArray())
            assertEquals(value, TimelineBinary.decodeRecord(body))
        }
    }

    @Test fun `both record paths reject malformed nested input before writing the length`() {
        val invalid = listOf(
            TimelineRecord(listOf(TimelineField(1, false, TimelineTlvValue.U32(-1)))),
            TimelineRecord(listOf(TimelineField(1, false, TimelineTlvValue.Text("\uD800")))),
            TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.Opaque(240, TimelineBytes.EMPTY)))),
            (1..TimelineProtocol.MAX_DEPTH).fold(TimelineRecord(listOf(TimelineField(1, false, TimelineTlvValue.Bool(true))))) { child, _ ->
                TimelineRecord(listOf(TimelineField(1, false, TimelineTlvValue.Record(child))))
            })
        for (value in invalid) {
            val ordinary = ByteArrayOutputStream()
            assertFailsWith<ProtocolException> { TimelineBinary.writeVRecord(ordinary as OutputStream, value) }
            assertEquals(0, ordinary.size())
            val owned = ByteArrayOutputStream()
            assertFailsWith<ProtocolException> { TimelineBinary.writeVRecord(DataOutputStream(owned), value) }
            assertEquals(0, owned.size())
        }
    }

    @Test fun `shared record writer preserves output budget across successive records`() {
        val record = TimelineRecord(listOf(TimelineField(1, false, TimelineTlvValue.U64(17))))
        val size = TimelineBinary.recordSize(record) + 1
        val bytes = ByteArrayOutputStream()
        val bounded = TimelineBinary.LimitedOutput(bytes, (size * 2).toLong())
        val writer = DataOutputStream(bounded)
        repeat(2) { TimelineBinary.writeVRecord(writer, record) }
        assertEquals((size * 2).toLong(), bounded.count)
        assertFailsWith<ProtocolException> { TimelineBinary.writeVRecord(writer, record) }
        assertEquals((size * 2).toLong(), bounded.count)
        assertEquals(size * 2, bytes.size())
    }
}
