package io.github.rwx.net.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimelineMessagesTest {
    private fun u(id: Int, value: Long) = TimelineMessages.numberField(id, value)
    private fun n(id: Int, value: Long) = TimelineMessages.numberField(id, value, u32 = true)
    private fun b(id: Int, count: Int) = TimelineMessages.bytesField(id, TimelineBytes(ByteArray(count)))
    private fun bytes(id: Int, vararg data: Byte) = TimelineMessages.bytesField(id, TimelineBytes(data))
    private fun t(id: Int, value: String) = TimelineMessages.textField(id, value)
    private fun r(vararg fields: TimelineField) = TimelineRecord(fields.toList())
    private fun frame(type: Int, vararg fields: TimelineField) = TimelineFrame(type, r(*fields))
    private fun replace(frame: TimelineFrame, id: Int, field: TimelineField) = frame.copy(body = TimelineRecord(
        frame.body.fields.map { if (it.id == id) field else it }))
    private val none = r(n(1, 0))
    private val point = r(n(1, 1), TimelineField(2, true, TimelineTlvValue.I64(256)), TimelineField(3, true, TimelineTlvValue.I64(512)))
    private val unit = r(n(1, 2), u(4, 3))
    private fun order(kind: Long = 1, target: TimelineRecord = point, flags: Long = 0, action: Long = 0) = frame(5,
        n(1, 1), u(2, 1), u(3, 1), u(4, 1), u(5, 1), n(6, kind), bytes(7, 1, 1), u(8, action),
        TimelineMessages.recordField(9, target), n(10, flags))

    private fun examples(): List<TimelineFrame> = listOf(
        frame(1, n(1, 1), t(2, "玩家"), t(3, "A".repeat(32))),
        frame(2, b(1, 32), TimelineMessages.boolField(2, true)),
        frame(3, n(1, 1), n(2, 1), u(3, 0), u(4, 0), n(5, 131072)), frame(4, n(1, 1)), order(),
        frame(6, n(1, 1), u(2, 1), u(3, 1), u(4, 1), bytes(5, 0)), frame(7, u(1, 1), u(2, 123)),
        frame(8, n(1, 1), u(2, 1), u(3, 1), u(4, 1), n(5, 0), b(6, 16)),
        frame(9, n(1, 1), u(2, 1), u(3, 0), n(4, 1)), frame(10, n(1, 1), n(2, 131072)),
        frame(101, n(1, 1), n(2, 0), n(3, 60), n(4, 1024), b(5, 16), b(6, 16), u(7, 61440), u(8, 0),
            u(9, 61440), n(10, 30), n(11, 100), b(12, 16), u(13, 0), n(14, 2)),
        frame(102, n(1, 1), t(2, "Unsupported profile")), frame(103, b(1, 32), u(2, 1024)),
        frame(104, b(1, 32), u(2, 0), b(3, 32)), frame(105, b(1, 32)),
        frame(106, n(1, 1), u(2, 1), n(3, 1), u(4, 1), u(5, 0), u(6, 61440), u(7, 61440), u(8, 0),
            u(9, 61441), u(10, 1), u(11, 1), b(12, 32), u(13, 0)),
        frame(107, n(1, 1), u(2, 1), u(3, 1), u(4, 0), bytes(5, 0)),
        frame(108, n(1, 1), u(2, 1), u(3, 1)), frame(109, n(1, 1), u(2, 1), u(3, 1), u(4, 1), b(5, 16)),
        frame(110, n(1, 1), n(2, 0), n(3, 1), n(4, 1), u(5, 0), u(6, 61441), n(7, 131072), u(8, 0), u(9, 61440)),
        frame(111, u(1, 1), u(2, 123), u(3, 61440)), frame(112, n(1, 1), u(2, 1), n(3, 1), u(4, 0), u(5, 61440)),
    )

    @Test fun `all twenty two messages round trip with their declared direction`() {
        val examples = examples()
        assertEquals(22, examples.size)
        examples.forEach { original ->
            val decoded = TimelineBinary.decodeFrame(TimelineBinary.encodeFrame(original))
            assertEquals(original, decoded)
            TimelineMessages.validate(decoded, decoded.type < 100)
            assertFailsWith<ProtocolException> { TimelineMessages.validate(decoded, decoded.type >= 100) }
        }
    }

    @Test fun `unknown types mandatory fields and criticality fail closed`() {
        assertFailsWith<ProtocolException> { TimelineMessages.validate(frame(99), true) }
        assertFailsWith<ProtocolException> { TimelineMessages.validate(frame(7, u(1, 1)), true) }
        assertFailsWith<ProtocolException> { TimelineMessages.validate(frame(7, u(1, 1), u(2, 2).copy(critical = false)), true) }
        assertFailsWith<ProtocolException> { TimelineMessages.validate(frame(7, u(1, 1), u(2, 2), u(3, 3)), true) }
        TimelineMessages.validate(frame(7, u(1, 1), u(2, 2), TimelineField(3, false, TimelineTlvValue.Opaque(250, TimelineBytes(byteArrayOf(7))))), true)
    }

    @Test fun `names tokens and welcome authorization are checked`() {
        val hello = examples().first()
        listOf(replace(hello, 2, t(2, "\u3000")), replace(hello, 3, t(3, "short")), replace(hello, 1, n(1, 2)),
            replace(hello, 2, t(2, "a\u0000b"))).forEach { bad ->
            assertFailsWith<ProtocolException> { TimelineMessages.validate(bad, true) }
        }
        val welcome = examples().first { it.type == 101 }
        for (bad in listOf(replace(welcome, 13, u(13, 1)), replace(welcome, 12, b(12, 15)),
            replace(welcome, 4, n(4, 1000)), replace(welcome, 9, u(9, 61441)))) {
            assertFailsWith<ProtocolException> { TimelineMessages.validate(bad, false) }
        }
    }

    @Test fun `live requests cannot smuggle historical ranges or invalid budgets`() {
        val live = examples().first { it.type == 3 }
        listOf(replace(live, 3, u(3, 1)), replace(live, 5, n(5, 16383)), replace(live, 5, n(5, 16777217))).forEach {
            assertFailsWith<ProtocolException> { TimelineMessages.validate(it, true) }
        }
        TimelineMessages.validate(frame(3, n(1, 2), n(2, 2), u(3, 10), u(4, 20), n(5, 16384)), true)
        assertFailsWith<ProtocolException> { TimelineMessages.validate(frame(3, n(1, 2), n(2, 2), u(3, 20), u(4, 20), n(5, 16384)), true) }
    }

    @Test fun `command target tags and cancel semantics are unambiguous`() {
        listOf(order(3, none), order(4, unit), order(5, none, action = 10), order(5, none, flags = 2, action = 10),
            order(2, point, flags = 1)).forEach { TimelineMessages.validate(it, true) }
        listOf(order(3, point), order(4, none), order(1, unit), order(5, none), order(1, action = 9),
            order(5, point, flags = 2, action = 10), order(5, none, flags = 3, action = 10), order(3, none, flags = 1),
            order(1, point, flags = 4)).forEach {
            assertFailsWith<ProtocolException> { TimelineMessages.validate(it, true) }
        }
    }

    @Test fun `selection cannot contain zero duplicate overlong or trailing identities`() {
        for (bad in listOf(byteArrayOf(1, 0), byteArrayOf(2, 1, 1), byteArrayOf(1, 0x81.toByte(), 0), byteArrayOf(0, 1))) {
            assertFailsWith<ProtocolException> { TimelineMessages.selection(TimelineBytes(bad), true) }
        }
        assertFailsWith<ProtocolException> { TimelineMessages.selection(TimelineBytes(byteArrayOf(0)), false) }
        assertEquals(listOf(1L, 128L), TimelineMessages.selection(TimelineBytes(byteArrayOf(2, 1, 0x80.toByte(), 1)), false))
    }

    @Test fun `optional target fields cannot cross target branches`() {
        val optionalCoordinate = TimelineField(2, false, TimelineTlvValue.I64(0))
        val optionalUnit = TimelineField(4, false, TimelineTlvValue.U64(1))
        for (bad in listOf(order(3, r(n(1, 0), optionalCoordinate)),
            order(1, TimelineRecord(point.fields + optionalUnit)), order(4, r(n(1, 2), optionalCoordinate, u(4, 1))))) {
            assertFailsWith<ProtocolException> { TimelineMessages.validate(bad, true) }
        }
        val extension = TimelineField(5, false, TimelineTlvValue.Bytes(TimelineBytes.EMPTY))
        TimelineMessages.validate(order(3, r(n(1, 0), extension)), true)
    }

    @Test fun `missing state ack has no forged digest`() {
        val ack = examples().first { it.type == 8 }
        TimelineMessages.validate(replace(replace(ack, 5, n(5, 1)), 6, b(6, 0)), true)
        assertFailsWith<ProtocolException> { TimelineMessages.validate(replace(ack, 5, n(5, 1)), true) }
        assertFailsWith<ProtocolException> { TimelineMessages.validate(replace(ack, 5, n(5, 2)), true) }
    }

    @Test fun `chunk sizes offsets fixed digests and transaction identities are bounded`() {
        val chunk = examples().first { it.type == 107 }
        for (bad in listOf(replace(chunk, 5, b(5, 0)), replace(chunk, 5, b(5, 65537)),
            replace(chunk, 4, u(4, 67108864)), replace(chunk, 2, u(2, 0)))) {
            assertFailsWith<ProtocolException> { TimelineMessages.validate(bad, false) }
        }
        val begin = examples().first { it.type == 106 }
        assertEquals(TimelineTransactionKind.RESET, TimelineMessages.header(begin.body).kind)
        listOf(replace(begin, 4, u(4, 2)), replace(begin, 12, b(12, 31)), replace(begin, 11, u(11, 0))).forEach {
            assertFailsWith<ProtocolException> { TimelineMessages.validate(it, false) }
        }
        TimelineMessages.validate(replace(replace(replace(begin, 3, n(3, 2)), 4, u(4, 2)), 5, u(5, 1)), false)
    }
}
