package io.github.rwx.net.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimelineBinaryAbilityCostTest {
    private fun field(id: Int, value: TimelineTlvValue, critical: Boolean = true) = TimelineField(id, critical, value)
    private fun cost(values: List<Long> = listOf(1536, 1024, 0, -1, 2048)) = TimelineRecord(values.mapIndexed { index, value ->
        field(index + 1, TimelineTlvValue.I64(value))
    })
    private fun ability(upfront: TimelineField? = null, streaming: TimelineField? = null) = TimelineRecord(listOf(
        field(1, TimelineTlvValue.U32(2)), field(2, TimelineTlvValue.U32(0)), field(3, TimelineTlvValue.Bool(false)),
        field(4, TimelineTlvValue.Text("produce.builder")), field(5, TimelineTlvValue.Text("builder")),
        field(6, TimelineTlvValue.I64(1536)), field(7, TimelineTlvValue.U32(1)),
    ) + listOfNotNull(upfront, streaming))
    private fun validate(ability: TimelineRecord) {
        TimelineBinary.validateDescriptor(TimelineObjectKind.ACTION, TimelineRecord(listOf(
            field(2, TimelineTlvValue.U64(1)), field(3, TimelineTlvValue.U64(2)), field(4, TimelineTlvValue.Record(ability)))))
    }

    @Test fun `optional costs preserve fractional unknown and full I64 values`() {
        validate(ability())
        val value = ability(field(9, TimelineTlvValue.Record(cost()), false),
            field(10, TimelineTlvValue.Record(cost(listOf(0, Long.MAX_VALUE, -1, 0, 1))), false))
        validate(value)
        val encoded = TimelineBinary.encodeRecord(value)
        assertEquals(value, TimelineBinary.decodeRecord(encoded))
        assertEquals(encoded, TimelineBinary.encodeRecord(TimelineBinary.decodeRecord(encoded)))
    }

    @Test fun `known optional costs still reject wrong kind criticality missing components and divergent credits`() {
        val malformed = listOf(
            field(9, TimelineTlvValue.Bytes(TimelineBytes.EMPTY), false),
            field(9, TimelineTlvValue.Record(cost()), true),
            field(9, TimelineTlvValue.Record(TimelineRecord(cost().fields.dropLast(1))), false),
            field(9, TimelineTlvValue.Record(TimelineRecord(cost().fields.map {
                if (it.id == 2) it.copy(critical = false) else it
            })), false),
            field(9, TimelineTlvValue.Record(cost(listOf(1024, 0, 0, 0, 0))), false),
            field(9, TimelineTlvValue.Record(cost(listOf(1536, -2, 0, 0, 0))), false),
            field(9, TimelineTlvValue.Record(TimelineRecord(cost().fields.map {
                if (it.id == 5) it.copy(value = TimelineTlvValue.U64(1)) else it
            })), false),
        )
        for (invalid in malformed) assertFailsWith<ProtocolException> { validate(ability(invalid)) }
        assertFailsWith<ProtocolException> {
            validate(ability(streaming = field(10, TimelineTlvValue.Record(cost(listOf(0, 0, 0, 0, -2))), false)))
        }
    }
}
