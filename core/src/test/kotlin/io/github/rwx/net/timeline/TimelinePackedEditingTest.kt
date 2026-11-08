package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

class TimelinePackedEditingTest {
    private val f = TimelineTestFixture
    private fun source(units: Int = 8): TimelineStore = TimelineStore(f.context()).also {
        f.commit(it, f.root() + (1L..units.toLong()).flatMap { handle -> f.unit(handle) }, observed = 20, highWater = units.toLong())
    }
    private fun edits(previous: TimelineState, next: TimelineState): Map<Long, TimelineObject> =
        next.objects.filter { (handle, obj) -> previous.objects[handle] != obj }
    private fun removed(previous: TimelineState, next: TimelineState): Set<Long> = previous.objects.keys - next.objects.keys
    private fun bytes(state: TimelineState): ByteArray = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
    private fun verify(expected: TimelineState, actual: TimelineState) {
        assertEquals(expected, actual)
        assertContentEquals(bytes(expected), bytes(actual))
        assertEquals(TimelineCanonical.persistentSize(expected), TimelineCanonical.persistentSize(actual))
        assertEquals(TimelineCanonical.digest(expected), TimelineCanonical.digest(actual))
        val library = assertNotNull(actual.packedLibrary)
        library.validate()
        assertTrue(library.pageCount <= library.objectCount)
        val table = ByteArrayOutputStream().also { library.writeObjectTable(it) }.size()
        assertEquals(table.toLong() - TimelineBinary.encodeVUInt(library.objectCount.toLong()).size, library.recordBytes)
        assertEquals(library.objectCount * 20L, library.directoryBytes)
    }

    @Test fun `header-only and sparse same-object edits preserve untouched pages and exact committed bytes`() {
        val store = source()
        val initial = store.current!!
        val owner = TimelinePackedLibrary.Owner(initial, 128)
        val first = owner.current
        val heartbeat = f.commit(store, emptyList(), observed = 21)
        val headerOnly = owner.prepareObjectEdits(heartbeat.header, emptyMap())
        assertEquals(first.pageCount, headerOnly.state.sharedPagesWith(first))
        verify(heartbeat, TimelineState.fromPacked(headerOnly.state))
        headerOnly.commit()
        val old = owner.current
        val next = f.commit(store, listOf(
            f.step(1, 3, TimelineValue.SInt(60), 10, revision = 1),
            f.step(1, 3, TimelineValue.SInt(40), 15, revision = 2)), observed = 22)
        val candidate = owner.prepareObjectEdits(next.header, edits(heartbeat, next))
        assertEquals(old.pageCount - 1, candidate.state.sharedPagesWith(old))
        verify(next, TimelineState.fromPacked(candidate.state))
        assertEquals(3L, candidate.state.objectValue(1)!!.curves.getValue(3).curveRevision)
        assertSame(old, owner.current)
        candidate.commit()
        assertSame(candidate.state, owner.current)
    }

    @Test fun `growing and shrinking records rewrite affected pages without dead ranges or repacking later pages`() {
        val store = source(24)
        var previous = store.current!!
        val owner = TimelinePackedLibrary.Owner(previous, 1024)
        val initial = owner.current
        val declaration = TimelineOperation.Declare(0, 65536, TimelineMode.STEP, TimelineValueType.BLOB, false)
        repeat(12) { round ->
            val value = TimelineValue.Blob(TimelineBytes(ByteArray(if (round % 2 == 0) 3500 else 2) { (round + it).toByte() }))
            val revision = previous.objects.getValue(0).curves[65536]?.curveRevision ?: 0
            val ops = (if (round == 0) listOf(declaration) else emptyList()) + f.step(0, 65536, value, revision = revision)
            val next = f.commit(store, ops, observed = 20L + round)
            val before = owner.current
            val prepared = owner.prepareObjectEdits(next.header, edits(previous, next))
            verify(next, TimelineState.fromPacked(prepared.state))
            assertTrue(prepared.state.sharedPagesWith(before) >= before.pageCount - 1)
            prepared.commit()
            previous = next
        }
        assertContentEquals(bytes(source(24).current!!), ByteArrayOutputStream().also { initial.writeCanonical(it) }.toByteArray())
        assertFalse(TimelinePackedLibrary::class.java.declaredFields.any {
            it.type == TimelinePackedLibrary::class.java || it.type == TimelineState::class.java ||
                it.type == TimelineObject::class.java || Map::class.java.isAssignableFrom(it.type)
        })
    }

    @Test fun `creates declarations pulse and retained carry-in match real Store operations`() {
        val store = source(3)
        var previous = store.current!!
        val owner = TimelinePackedLibrary.Owner(previous, 256)
        fun commit(ops: List<TimelineOperation>, observed: Long, sealed: Long = 0, from: Long = 0,
                   control: Long = previous.header.controlEpoch, high: Long = previous.header.objectHighWater) {
            val next = f.commit(store, ops, observed = observed, sealed = sealed, from = from, control = control, highWater = high)
            val prepared = owner.prepareObjectEdits(next.header, edits(previous, next), removed(previous, next))
            verify(next, TimelineState.fromPacked(prepared.state))
            prepared.commit(); previous = next
        }
        val metadata = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(30))))
        commit(listOf(TimelineOperation.Declare(0, 65536, TimelineMode.PULSE, TimelineValueType.SINT, false, metadata = metadata),
            TimelineOperation.Pulse(0, 65536, 0, 0, 20, listOf(TimelinePulse(0, 1, TimelineValue.SInt(7)),
                TimelinePulse(10, 2, TimelineValue.SInt(8))))), 20)
        commit(listOf(TimelineOperation.Close(1, 10, TimelineCloseKind.ENDED)), 20, sealed = 20, control = 2)
        commit(listOf(TimelineOperation.Retain(20)), 21, sealed = 20, from = 20)
        assertFalse(owner.current.contains(1))
        assertEquals(listOf(0L, 10L), owner.current.objectValue(0)!!.curves.getValue(65536).events.map { it.time })
        commit(f.unit(4, from = 21), 21, sealed = 20, from = 20, high = 4)
        assertTrue(owner.current.contains(4))
        commit(emptyList(), 31, sealed = 30, from = 20)
        commit(listOf(TimelineOperation.Retain(30)), 31, sealed = 30, from = 30)
        assertEquals(listOf(10L), owner.current.objectValue(0)!!.curves.getValue(65536).events.map { it.time })
    }

    @Test fun `private edits and stale or failed preparations cannot mutate or retain an old committed graph`() {
        val store = source()
        val initial = store.current!!
        val owner = TimelinePackedLibrary.Owner(initial, 128)
        val old = owner.current
        val digest = old.digest()
        val next = f.commit(store, listOf(f.step(1, 3, TimelineValue.SInt(60), 10, revision = 1)), observed = 21)
        val mutable = LinkedHashMap(edits(initial, next))
        val prepared = owner.prepareObjectEdits(next.header, mutable)
        val competing = owner.prepareObjectEdits(next.header, mutable)
        mutable.clear()
        verify(next, TimelineState.fromPacked(prepared.state))
        prepared.commit()
        assertFailsWith<ProtocolException> { competing.commit() }
        assertFailsWith<ProtocolException> { prepared.commit() }
        assertEquals(digest, old.digest())
        val current = owner.current
        val unit = next.objects.getValue(1)
        val hp = unit.curves.getValue(4)
        val bad = unit.copy(curves = unit.curves + (4 to hp.copy(curveRevision = hp.curveRevision + 1,
            segments = listOf((hp.segments.single() as TimelineSegment.Step).copy(value = TimelineValue.SInt(0))))))
        val badOps = listOf(f.step(1, 4, TimelineValue.SInt(0), revision = hp.curveRevision))
        val badHeader = f.header(badOps, next, observed = 22, highWater = next.header.objectHighWater)
        assertFailsWith<ProtocolException> { owner.prepareObjectEdits(badHeader, mapOf(1L to bad)) }
        assertSame(current, owner.current)
        verify(next, TimelineState.fromPacked(current))
    }

    @Test fun `header context high-water identity and removal errors fail without a partial candidate`() {
        val store = source(2)
        val initial = store.current!!
        val library = TimelinePackedLibrary.pack(initial)
        val base = TimelineState.fromPacked(library)
        val header = f.header(emptyList(), initial, observed = 21, highWater = 2)
        val digest = library.digest()
        for (bad in listOf(header.copy(epoch = 2), header.copy(baseRevision = 0), header.copy(revision = 9),
            header.copy(observedThrough = 19), header.copy(controlEpoch = 0), header.copy(kind = TimelineTransactionKind.RESET),
            header.copy(objectHighWater = 3), header.copy(retainedFrom = 1, sealedThrough = 1))) {
            assertFails { library.prepareObjectEdits(base, bad, emptyMap()) }
        }
        val equalButDifferentBase = TimelineState.fromPacked(TimelinePackedLibrary.pack(initial))
        assertFailsWith<ProtocolException> { library.prepareObjectEdits(equalButDifferentBase, header, emptyMap()) }
        assertFailsWith<ProtocolException> { library.prepareObjectEdits(base, header, emptyMap(), setOf(1)) }
        assertFailsWith<ProtocolException> { library.prepareObjectEdits(base, header, emptyMap(), setOf(99)) }
        assertFailsWith<ProtocolException> { library.prepareObjectEdits(base, header, mapOf(1L to initial.objects.getValue(2))) }
        assertFailsWith<ProtocolException> { library.prepareObjectEdits(base, header, mapOf(1L to initial.objects.getValue(1)), setOf(1)) }
        val changedIdentity = initial.objects.getValue(1).copy(observedFrom = 1)
        assertFailsWith<ProtocolException> { library.prepareObjectEdits(base, header, mapOf(1L to changedIdentity)) }
        assertEquals(digest, library.digest())
    }

    @Test fun `complete profile still detects control changes and sealed record edits preserve exact anchors`() {
        val store = source(2)
        val initial = store.current!!
        val library = TimelinePackedLibrary.pack(initial)
        val owner = initial.objects.getValue(1)
        val permission = owner.curves.getValue(9)
        val changed = owner.copy(curves = owner.curves + (9 to permission.copy(curveRevision = 2,
            segments = listOf((permission.segments.single() as TimelineSegment.Step).copy(value = TimelineValue.Bool(false))))))
        val ops = listOf(f.step(1, 9, TimelineValue.Bool(false), revision = 1))
        val header = f.header(ops, initial, observed = 21, highWater = 2)
        assertFailsWith<ProtocolException> { library.prepareObjectEdits(TimelineState.fromPacked(library), header, mapOf(1L to changed)) }
        val next = f.commit(store, ops, observed = 21, control = 2)
        val candidate = library.prepareObjectEdits(TimelineState.fromPacked(library), next.header, mapOf(1L to changed))
        verify(next, candidate)

        val sealed = f.commit(store, emptyList(), observed = 22, sealed = 15)
        val packed = TimelinePackedLibrary.pack(sealed)
        val health = sealed.objects.getValue(2).curves.getValue(3)
        val forged = sealed.objects.getValue(2).copy(curves = sealed.objects.getValue(2).curves + (3 to health.copy(
            curveRevision = 2, segments = listOf((health.segments.single() as TimelineSegment.Step).copy(value = TimelineValue.SInt(79))))))
        val update = f.header(emptyList(), sealed, observed = 23, sealed = 15, highWater = 2)
        assertFailsWith<ProtocolException> { packed.prepareObjectEdits(TimelineState.fromPacked(packed), update, mapOf(2L to forged)) }
        val position = sealed.objects.getValue(2).curves.getValue(1)
        val originalSegment = position.segments.single() as TimelineSegment.Interpolated
        val reanchored = sealed.objects.getValue(2).copy(curves = sealed.objects.getValue(2).curves + (1 to position.copy(
            curveRevision = 2, segments = listOf(originalSegment.copy(hi = Long.MAX_VALUE - 1, anchor1Time = Long.MAX_VALUE - 1)))))
        assertFailsWith<ProtocolException> {
            packed.prepareObjectEdits(TimelineState.fromPacked(packed), update, mapOf(2L to reanchored))
        }
        val valid = f.commit(store, listOf(f.step(2, 3, TimelineValue.SInt(79), from = 15, revision = 1)), observed = 23, sealed = 15)
        verify(valid, packed.prepareObjectEdits(TimelineState.fromPacked(packed), valid.header, edits(sealed, valid)))
    }
}
