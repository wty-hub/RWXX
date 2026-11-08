package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

/** Sparse staging must behave like a complete private map throughout a transaction. */
class TimelineStoreOverlayTest {
    private val f = TimelineTestFixture

    @Test fun `repeated existing and newly declared splices read staged revisions and preserve untouched curves`() {
        val store = initial()
        val before = store.current!!
        val operations = listOf(
            f.step(1, 3, TimelineValue.SInt(20), 5, revision = 1),
            f.step(1, 3, TimelineValue.SInt(30), 7, revision = 2),
            TimelineOperation.Declare(1, 65536, TimelineMode.STEP, TimelineValueType.SINT, false),
            f.step(1, 65536, TimelineValue.SInt(11), 5),
            f.step(1, 65536, TimelineValue.SInt(22), 8, revision = 1))
        val after = applyRaw(store, operations)
        val curves = after.objects.getValue(1).curves
        assertEquals(10, curves.size)
        assertEquals(3L, curves.getValue(3).curveRevision)
        assertEquals(2L, curves.getValue(65536).curveRevision)
        assertEquals(TimelineValue.SInt(80), TimelineProfile.sample(after, 1, 3, 4))
        assertEquals(TimelineValue.SInt(20), TimelineProfile.sample(after, 1, 3, 6))
        assertEquals(TimelineValue.SInt(30), TimelineProfile.sample(after, 1, 3, 10))
        assertEquals(TimelineValue.SInt(11), TimelineProfile.sample(after, 1, 65536, 7))
        assertEquals(TimelineValue.SInt(22), TimelineProfile.sample(after, 1, 65536, 10))
        for (id in (1..9).filter { it != 3 }) assertSame(before.objects.getValue(1).curves.getValue(id), curves.getValue(id))
        assertEquals(TimelineValue.SInt(80), TimelineProfile.sample(before, 1, 3, 10))
        assertFalse(before.objects.getValue(1).curves.containsKey(65536))

        val duplicate = listOf(TimelineOperation.Declare(1, 65537, TimelineMode.STEP, TimelineValueType.SINT, false),
            TimelineOperation.Declare(1, 65537, TimelineMode.STEP, TimelineValueType.SINT, false))
        assertFailsWith<ProtocolException> { applyRaw(store, duplicate) }
        assertSame(after, store.current)
        val staleRevision = listOf(f.step(1, 3, TimelineValue.SInt(40), 5, revision = 3),
            f.step(1, 3, TimelineValue.SInt(50), 7, revision = 3))
        assertFailsWith<ProtocolException> { applyRaw(store, staleRevision) }
        assertSame(after, store.current)
    }

    @Test fun `Close transforms untouched replacements additions and pulse events exactly once`() {
        val store = TimelineStore(f.context())
        val extra = listOf(
            TimelineOperation.Declare(1, 65536, TimelineMode.STEP, TimelineValueType.SINT, false),
            f.step(1, 65536, TimelineValue.SInt(12)),
            TimelineOperation.Declare(1, 65537, TimelineMode.PULSE, TimelineValueType.UINT, false, 2,
                TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(0))))),
            TimelineOperation.Pulse(1, 65537, 0, 0, 10, listOf(
                TimelinePulse(6, 1, TimelineValue.UInt(1)), TimelinePulse(9, 2, TimelineValue.UInt(2)))))
        val before = applyRaw(store, f.root() + f.unit() + extra)
        val operations = listOf(
            f.step(1, 3, TimelineValue.SInt(20), 5, revision = 1),
            TimelineOperation.Declare(1, 65538, TimelineMode.STEP, TimelineValueType.SINT, false),
            f.step(1, 65538, TimelineValue.SInt(14), 5),
            TimelineOperation.Close(1, 8, TimelineCloseKind.ENDED))
        val after = applyRaw(store, operations, control = 2)
        val unit = after.objects.getValue(1)
        assertEquals(12, unit.curves.size)
        assertEquals(TimelineClose(8, TimelineCloseKind.ENDED), unit.close)
        for (curve in unit.curves.values) {
            assertTrue(curve.segments.all { it.hi <= 8 })
            assertTrue(curve.events.all { it.time < 8 })
            assertEquals(when (curve.attributeId) { 3 -> 2L; else -> 1L }, curve.curveRevision)
        }
        assertEquals(listOf(6L), unit.curves.getValue(65537).events.map { it.time })
        assertEquals(2L, unit.curves.getValue(65537).eventHighWater)
        assertEquals(TimelineValue.SInt(20), TimelineProfile.sample(after, 1, 3, 7))
        assertNull(TimelineProfile.sample(after, 1, 3, 8))
        val original = before.objects.getValue(1)
        assertNull(original.close)
        assertEquals(listOf(6L, 9L), original.curves.getValue(65537).events.map { it.time })
        assertTrue(original.curves.getValue(3).segments.all { it.hi == Long.MAX_VALUE })
    }

    @Test fun `new attribute accounting enforces capacity without counting replacements twice`() {
        val store = initial()
        val originalCount = store.current!!.objects.getValue(1).curves.size
        val declarations = (0 until TimelineProtocol.MAX_ATTRIBUTES - originalCount).map {
            TimelineOperation.Declare(1, 65536 + it, TimelineMode.STEP, TimelineValueType.SINT, false)
        }
        val full = applyRaw(store, declarations + listOf(
            f.step(1, 3, TimelineValue.SInt(20), 5, revision = 1),
            f.step(1, 3, TimelineValue.SInt(30), 7, revision = 2)))
        assertEquals(TimelineProtocol.MAX_ATTRIBUTES, full.objects.getValue(1).curves.size)
        assertEquals(3L, full.objects.getValue(1).curves.getValue(3).curveRevision)
        val overflow = listOf(f.step(1, 3, TimelineValue.SInt(40), 5, revision = 3),
            TimelineOperation.Declare(1, 70000, TimelineMode.STEP, TimelineValueType.SINT, false))
        assertFailsWith<ProtocolException> { applyRaw(store, overflow) }
        assertSame(full, store.current)
        assertEquals(TimelineValue.SInt(30), TimelineProfile.sample(full, 1, 3, 10))
    }

    @Test fun `Retain followed by editing copies the retained base and matches a complete map oracle`() {
        val store = initial()
        val before = store.current!!
        val operations = listOf(TimelineOperation.Retain(3), f.step(1, 3, TimelineValue.SInt(20), 5, revision = 1))
        val after = applyRaw(store, operations, from = 3)
        val expectedObjects = before.objects.mapValues { (handle, obj) ->
            obj.copy(curves = obj.curves.mapValues { (id, curve) ->
                if (handle == 1L && id == 3) curve.copy(curveRevision = 2, segments = listOf(
                    TimelineSegment.Step(3, 5, false, TimelineValue.SInt(80)),
                    TimelineSegment.Step(5, Long.MAX_VALUE, false, TimelineValue.SInt(20))))
                else curve.copy(segments = curve.segments.map { it.clipped(3, it.hi) })
            })
        }
        val expected = TimelineState(before.context, after.header, expectedObjects)
        TimelineProfile.validate(expected, before)
        assertEquals(expected, after)
        assertContentEquals(canonical(expected), canonical(after))
        assertEquals(TimelineCanonical.persistentSize(expected), TimelineCanonical.persistentSize(after))
        assertEquals(TimelineCanonical.digest(expected), TimelineCanonical.digest(after))
        for (obj in after.objects.values) for (curve in obj.curves.values) assertTrue(curve.segments.all { it.lo >= 3 })
        assertTrue(before.objects.getValue(1).curves.getValue(3).segments.all { it.lo == 0L })
    }

    @Test fun `uncommitted failed and forked edits cannot mutate previously frozen maps`() {
        val store = initial()
        val before = store.current!!
        val beforeBytes = canonical(before)
        val first = prepareRaw(store, listOf(f.step(1, 3, TimelineValue.SInt(20), 5, revision = 1)))
        val second = prepareRaw(store, listOf(f.step(1, 3, TimelineValue.SInt(40), 5, revision = 1)))
        assertSame(before, store.current)
        assertEquals(TimelineValue.SInt(20), TimelineProfile.sample(first.state, 1, 3, 10))
        assertEquals(TimelineValue.SInt(40), TimelineProfile.sample(second.state, 1, 3, 10))
        first.commit()
        assertFailsWith<ProtocolException> { second.commit() }
        assertSame(first.state, store.current)

        // Both operations are valid edits; removing required current position fails Profile.
        val invalid = listOf(f.step(1, 3, TimelineValue.SInt(90), 5, revision = 2),
            TimelineOperation.Splice(1, 1, 1, 5, Long.MAX_VALUE, emptyList()))
        assertFailsWith<ProtocolException> { prepareRaw(store, invalid) }
        assertSame(first.state, store.current)
        assertEquals(TimelineValue.SInt(20), TimelineProfile.sample(first.state, 1, 3, 10))
        assertEquals(TimelineValue.SInt(40), TimelineProfile.sample(second.state, 1, 3, 10))

        val fork = store.fork()
        val forked = applyRaw(fork, listOf(f.step(1, 3, TimelineValue.SInt(50), 5, revision = 2)))
        val next = applyRaw(store, listOf(f.step(1, 3, TimelineValue.SInt(60), 5, revision = 2)))
        assertEquals(TimelineValue.SInt(50), TimelineProfile.sample(forked, 1, 3, 10))
        assertEquals(TimelineValue.SInt(60), TimelineProfile.sample(next, 1, 3, 10))
        assertEquals(TimelineValue.SInt(20), TimelineProfile.sample(first.state, 1, 3, 10))
        assertContentEquals(beforeBytes, canonical(before))
    }

    @Test fun `owned curve edits are copied and match the public defensive copy`() {
        val before = initial().current!!
        val original = before.objects.getValue(1)
        val health = original.curves.getValue(3)
        val replacement = health.copy(curveRevision = health.curveRevision + 1,
            segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.SInt(20))))
        val addition = health.copy(attributeId = 65536, criticalAttribute = false)
        val edits = linkedMapOf(3 to replacement, 65536 to addition)
        val frozen = original.copyWithCurveEdits(original.close, edits)
        val expected = original.copy(curves = LinkedHashMap(original.curves).apply { putAll(edits) })
        assertEquals(expected, frozen)
        assertEquals(original.curves.keys.toList() + 65536, frozen.curves.keys.toList())
        assertSame(original.curves.getValue(1), frozen.curves.getValue(1))

        fun withObject(value: TimelineObject) = before.copy(objects = LinkedHashMap(before.objects).apply { put(1, value) })
        val frozenState = withObject(frozen)
        val expectedState = withObject(expected)
        val bytes = canonical(frozenState)
        assertContentEquals(canonical(expectedState), bytes)
        assertEquals(TimelineCanonical.persistentSize(expectedState), TimelineCanonical.persistentSize(frozenState))
        assertEquals(TimelineCanonical.digest(expectedState), TimelineCanonical.digest(frozenState))

        edits[3] = health
        edits.clear()
        edits[65537] = addition.copy(attributeId = 65537)
        assertSame(replacement, frozen.curves.getValue(3))
        assertSame(addition, frozen.curves.getValue(65536))
        assertFalse(frozen.curves.containsKey(65537))
        assertSame(health, original.curves.getValue(3))
        assertFalse(original.curves.containsKey(65536))
        assertContentEquals(bytes, canonical(frozenState))
    }

    @Test fun `public constructor copy and owned edits expose no writable map or entry`() {
        val curve = TimelineCurve(3, TimelineMode.STEP, TimelineValueType.SINT, true)
        val constructorInput = linkedMapOf(3 to curve)
        val original = TimelineObject(7, TimelineObjectKind.UNIT, 0, curves = constructorInput)
        constructorInput[3] = curve.copy(curveRevision = 9)
        constructorInput.clear()
        assertSame(curve, original.curves.getValue(3))

        val replacement = curve.copy(curveRevision = 1)
        val copyInput = linkedMapOf(3 to replacement)
        val copied = original.copy(curves = copyInput)
        copyInput.clear()
        assertSame(replacement, copied.curves.getValue(3))

        for (obj in listOf(original, copied, original.copyWithCurveEdits(null, emptyMap()))) {
            val expected = obj.curves.getValue(3)
            @Suppress("UNCHECKED_CAST")
            val mutable = obj.curves as MutableMap<Int, TimelineCurve>
            assertFailsWith<UnsupportedOperationException> { mutable[4] = curve }
            assertFailsWith<UnsupportedOperationException> { mutable.clear() }
            val entry = mutable.entries.first()
            assertFailsWith<UnsupportedOperationException> { entry.setValue(curve.copy(curveRevision = 10)) }
            assertEquals(listOf(3), obj.curves.keys.toList())
            assertSame(expected, obj.curves.getValue(3))
        }
    }

    private fun initial() = TimelineStore(f.context()).also { applyRaw(it, f.root() + f.unit()) }

    private fun applyRaw(store: TimelineStore, operations: List<TimelineOperation>, from: Long = 0,
                         control: Long = store.current?.header?.controlEpoch ?: 1): TimelineState {
        val header = f.header(operations, store.current, sealed = 5, from = from, control = control)
        return store.apply(header, encoded(operations, store.current))
    }

    private fun prepareRaw(store: TimelineStore, operations: List<TimelineOperation>): TimelineStore.Prepared {
        val header = f.header(operations, store.current, sealed = 5)
        return store.prepare(header, encoded(operations, store.current))
    }

    private fun encoded(operations: List<TimelineOperation>, previous: TimelineState?): TimelineBytes {
        val formats = previous?.objects?.values?.flatMap { obj -> obj.curves.values.map {
            TimelineCurveKey(obj.handle, it.attributeId) to it.format
        } }?.toMap() ?: emptyMap()
        return TimelineBinary.encodeOperations(operations, formats)
    }

    private fun canonical(state: TimelineState): ByteArray = ByteArrayOutputStream().let {
        TimelineCanonical.write(state, it)
        it.toByteArray()
    }
}
