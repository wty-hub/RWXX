package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlin.test.*

class TimelineEmptyListsTest {
    @Test fun `empty typed lists share only owned storage and nonempty lists still copy`() {
        val fields = ArrayList<TimelineField>()
        val records = ArrayList<TimelineRecord>()
        val segments = ArrayList<TimelineSegment>()
        val events = ArrayList<TimelinePulse>()
        val record = TimelineRecord(fields)
        val nested = TimelineTlvValue.Records(records)
        val curve = TimelineCurve(1, TimelineMode.LINEAR, TimelineValueType.VEC3, true,
            segments = segments, events = events)
        assertSame<Any>(record.fields, nested.values)
        assertSame<Any>(record.fields, curve.segments)
        assertSame<Any>(record.fields, curve.events)
        assertTrue(record.fields is java.util.RandomAccess)

        fields.add(TimelineField(1, false, TimelineTlvValue.U32(1)))
        records.add(TimelineRecord(fields))
        segments.add(TimelineSegment.Interpolated(0, 1, false, 0, 1,
            TimelineValue.Vec3(0, 0, 0), TimelineValue.Vec3(1, 1, 1)))
        events.add(TimelinePulse(0, 1, TimelineValue.UInt(1)))
        assertTrue(record.fields.isEmpty())
        assertTrue(nested.values.isEmpty())
        assertTrue(curve.segments.isEmpty())
        assertTrue(curve.events.isEmpty())

        val copied = TimelineRecord(fields)
        val captured = fields.single()
        fields[0] = TimelineField(2, false, TimelineTlvValue.U32(2))
        fields.clear()
        assertEquals(listOf(captured), copied.fields)
        assertNotSame(record.fields, copied.fields)
    }

    @Test fun `shared empty list rejects mutators exactly like a fresh Java unmodifiable wrapper`() {
        val original: List<String> = Collections.unmodifiableList(ArrayList<String>())
        val shared = frozenList(emptyList<String>())
        // Both casts must succeed; rejection belongs to the Java collection mutator.
        val baseline = original as MutableList<String>
        val candidate = shared as MutableList<String>
        val operations: List<(MutableList<String>) -> Unit> = listOf(
            { it.add("value"); Unit },
            { it.addAll(emptyList()); Unit },
            { it.remove("absent"); Unit },
            { it.removeAll(emptyList()); Unit },
            { it.retainAll(emptyList()); Unit },
            { it.clear() },
            { it[0] = "value" },
            { it.removeAt(0); Unit },
            { it.iterator().remove() },
            { it.listIterator().add("value") },
            { it.subList(0, 0).clear() })
        for (operation in operations) {
            val old = assertFails { operation(baseline) }
            val actual = assertFails { operation(candidate) }
            assertTrue(old is UnsupportedOperationException)
            assertEquals<Class<*>>(old.javaClass, actual.javaClass)
            assertTrue(candidate.isEmpty())
        }
    }

    @Test fun `empty canonical containers keep zero counts and cannot change through source lists`() {
        val fields = ArrayList<TimelineField>()
        val children = ArrayList<TimelineRecord>()
        val empty = TimelineRecord(fields)
        val list = TimelineRecord(listOf(TimelineField(1, false, TimelineTlvValue.Records(children))))
        assertContentEquals(byteArrayOf(), TimelineBinary.encodeRecord(empty).toByteArray())
        assertContentEquals(byteArrayOf(0, 1, 0, 8, 0, 0, 0, 4, 0, 0, 0, 0),
            TimelineBinary.encodeRecord(list).toByteArray())
        assertContentEquals(byteArrayOf(0), TimelineBinary.encodeOperations(emptyList()).toByteArray())

        val f = TimelineTestFixture
        val store = TimelineStore(f.context())
        val source = f.commit(store, f.root() + f.unit())
        val unit = source.objects.getValue(1)
        val segments = ArrayList<TimelineSegment>()
        val pulses = ArrayList<TimelinePulse>()
        val optional = TimelineCurve(65536, TimelineMode.STEP, TimelineValueType.SINT, false,
            metadata = empty, segments = segments, events = pulses)
        val state = source.copy(objects = source.objects + (1L to unit.copy(curves = unit.curves + (65536 to optional))))
        TimelineProfile.validate(state)
        val beforeBytes = canonical(state)
        val digest = TimelineCanonical.digest(state)
        val size = TimelineCanonical.persistentSize(state)
        fields.add(TimelineField(1, false, TimelineTlvValue.Text("changed")))
        children.add(TimelineRecord(fields))
        segments.add(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.SInt(1)))
        pulses.add(TimelinePulse(0, 1, TimelineValue.SInt(1)))
        assertContentEquals(byteArrayOf(), TimelineBinary.encodeRecord(empty).toByteArray())
        assertContentEquals(byteArrayOf(0, 1, 0, 8, 0, 0, 0, 4, 0, 0, 0, 0),
            TimelineBinary.encodeRecord(list).toByteArray())
        assertContentEquals(beforeBytes, canonical(state))
        assertEquals(digest, TimelineCanonical.digest(state))
        assertEquals(size, TimelineCanonical.persistentSize(state))
        TimelineProfile.validate(state)
    }

    private fun canonical(state: TimelineState): ByteArray = ByteArrayOutputStream().let {
        TimelineCanonical.write(state, it)
        it.toByteArray()
    }
}
