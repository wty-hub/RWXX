package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

internal object TimelineTestFixture {
    fun bytes(vararg values: Int) = TimelineBytes(ByteArray(values.size) { values[it].toByte() })
    fun compact(vararg values: Long): TimelineBytes = ByteArrayOutputStream().let { out ->
        values.forEach { TimelineBinary.writeVUInt(out, it) }; TimelineBytes(out.toByteArray())
    }
    fun map(terrain: ByteArray = ByteArray(4), pools: TimelineBytes = compact(0)): TimelineRecord = TimelineRecord(listOf(
        TimelineField(1, true, TimelineTlvValue.U32(1)), TimelineField(2, true, TimelineTlvValue.U32(2)),
        TimelineField(3, true, TimelineTlvValue.U32(2)), TimelineField(4, true, TimelineTlvValue.U32(20)),
        TimelineField(5, true, TimelineTlvValue.U32(20)), TimelineField(6, true, TimelineTlvValue.Bytes(TimelineBytes(terrain))),
        TimelineField(7, true, TimelineTlvValue.U32(1)), TimelineField(8, true, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(8)))),
        TimelineField(9, true, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(8)))), TimelineField(10, true, TimelineTlvValue.Bytes(pools))))
    fun context(map: TimelineRecord = map()) = TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }),
        TimelineCanonical.mapKey(map), map, 0, 60)
    fun running() = TimelineValue.Blob(bytes(0, 0, 0, 0))
    fun finished(time: Long = 5) = TimelineValue.Blob(ByteArrayOutputStream().let { out ->
        out.write(1); TimelineBinary.writeVUInt(out, time); out.write(1); out.write(0); out.write(1); out.write(1)
        TimelineBytes(out.toByteArray())
    })
    fun step(handle: Long, id: Int, value: TimelineValue, from: Long = 0, until: Long = Long.MAX_VALUE,
             revision: Long = 0): TimelineOperation.Splice = TimelineOperation.Splice(handle, id, revision, from, until,
        listOf(TimelineSegment.Step(from, until, false, value)))
    fun constant(handle: Long, id: Int, value: TimelineValue, from: Long = 0, until: Long = Long.MAX_VALUE,
                 revision: Long = 0): TimelineOperation.Splice = TimelineOperation.Splice(handle, id, revision, from, until,
        listOf(TimelineSegment.Interpolated(from, until, false, from, until, value, value)))
    fun root(status: TimelineValue = running(), selection: TimelineBytes = compact(0), from: Long = 0): List<TimelineOperation> = listOf(
        TimelineOperation.Create(0, TimelineObjectKind.ROOT, from),
        TimelineOperation.Declare(0, 1, TimelineMode.STEP, TimelineValueType.SINT, true), step(0, 1, TimelineValue.SInt(100), from),
        TimelineOperation.Declare(0, 2, TimelineMode.STEP, TimelineValueType.BLOB, true), step(0, 2, status, from),
        TimelineOperation.Declare(0, 3, TimelineMode.STEP, TimelineValueType.BLOB, true), step(0, 3, TimelineValue.Blob(selection), from))
    fun unit(handle: Long = 1, from: Long = 0, team: Long = 0): List<TimelineOperation> {
        val values = listOf(TimelineValue.Vec3(256, 512, -1), TimelineValue.UInt(65000), TimelineValue.SInt(80),
            TimelineValue.SInt(100), TimelineValue.UInt(65535), TimelineValue.SInt(team),
            TimelineValue.Blob(TimelineBytes("tank".toByteArray())), TimelineValue.UInt(1), TimelineValue.Bool(team == 0L))
        return buildList {
            add(TimelineOperation.Create(handle, TimelineObjectKind.UNIT, from))
            values.forEachIndexed { index, value ->
                val id = index + 1
                val mode = when (id) { 1, 5 -> TimelineMode.LINEAR; 2 -> TimelineMode.ANGLE; else -> TimelineMode.STEP }
                add(TimelineOperation.Declare(handle, id, mode, value.type, true))
                add(if (mode == TimelineMode.STEP) step(handle, id, value, from) else constant(handle, id, value, from))
            }
        }
    }
    fun header(ops: List<TimelineOperation>, previous: TimelineState? = null, observed: Long = 10, sealed: Long = 0,
               from: Long = 0, until: Long = observed + 1, highWater: Long = 1, control: Long = previous?.header?.controlEpoch ?: 1,
               reset: Boolean = previous == null, epoch: Long = if (reset) (previous?.header?.epoch ?: 0) + 1 else previous!!.header.epoch): TimelineHeader {
        val formats = if (reset) emptyMap() else previous!!.objects.values.flatMap { obj ->
            obj.curves.values.map { TimelineCurveKey(obj.handle, it.attributeId) to it.format }
        }.toMap()
        val data = TimelineBinary.encodeOperations(ops, formats)
        return TimelineHeader(1, epoch, if (reset) TimelineTransactionKind.RESET else TimelineTransactionKind.UPDATE,
            if (reset) 1 else previous!!.header.revision + 1, if (reset) 0 else previous!!.header.revision,
            observed, sealed, from, until, control, data.size.toLong(), TimelineCanonical.sha256(data), highWater)
    }
    fun commit(store: TimelineStore, ops: List<TimelineOperation>, observed: Long = 10, sealed: Long = 0,
               from: Long = 0, until: Long = observed + 1, highWater: Long = store.current?.header?.objectHighWater ?: 1,
               control: Long = store.current?.header?.controlEpoch ?: 1): TimelineState =
        store.apply(header(ops, store.current, observed, sealed, from, until, highWater, control), ops)
}

class TimelineStoreTest {
    private val f = TimelineTestFixture

    @Test fun `valid raw RESET and empty UPDATE publish complete immutable states`() {
        val store = TimelineStore(f.context())
        val ops = f.root() + f.unit()
        val header = f.header(ops, sealed = 5)
        val state = store.apply(header, TimelineBinary.encodeOperations(ops))
        assertSame(state, store.current)
        assertEquals(TimelineValue.Vec3(256, 512, -1), TimelineProfile.sample(state, 1, 1, 10))
        val next = f.commit(store, emptyList(), observed = 11, sealed = 6)
        assertEquals(2, next.header.revision)
        assertEquals(1, state.header.revision)
        assertEquals(state.objects, next.objects)
        assertNotEquals(TimelineCanonical.digest(state), TimelineCanonical.digest(next))
    }

    @Test fun `late invalid operation cannot publish preceding valid edits`() {
        val store = TimelineStore(f.context())
        val before = f.commit(store, f.root() + f.unit())
        val ops = listOf(f.step(1, 3, TimelineValue.SInt(20), 5, revision = 1),
            TimelineOperation.Close(999, 10, TimelineCloseKind.ENDED))
        assertFailsWith<ProtocolException> { f.commit(store, ops) }
        assertSame(before, store.current)
        assertEquals(TimelineValue.SInt(80), TimelineProfile.sample(before, 1, 3, 10))
        val wrongHash = f.header(emptyList(), before).copy(operationSha256 = TimelineBytes(ByteArray(32)))
        assertFailsWith<ProtocolException> { store.apply(wrongHash, emptyList()) }
        assertSame(before, store.current)
    }

    @Test fun `missing base is distinct and epoch recovery strictly advances and revokes controls`() {
        val store = TimelineStore(f.context())
        val first = f.commit(store, f.root() + f.unit())
        val badBase = f.header(emptyList(), first).copy(baseRevision = 5, revision = 6)
        assertFailsWith<MissingBase> { store.apply(badBase, emptyList()) }
        assertSame(first, store.current)
        val reset = f.root() + f.unit()
        assertFailsWith<ProtocolException> { store.apply(f.header(reset, first, reset = true), reset) }
        val newHeader = f.header(reset, first, reset = true, control = 2)
        assertFailsWith<ProtocolException> { store.apply(newHeader.copy(epoch = 3), reset) }
        val next = store.apply(newHeader, reset)
        assertEquals(2, next.header.epoch)
        assertEquals(1, next.header.revision)
    }

    @Test fun `splice preserves sealed prefix anchors and rejects edits across old seal`() {
        val store = TimelineStore(f.context())
        val ops = f.root() + f.unit() + TimelineOperation.Splice(1, 1, 1, 0, 10,
            listOf(TimelineSegment.Interpolated(0, 10, false, 0, 10, TimelineValue.Vec3(0, 0, 0), TimelineValue.Vec3(2560, 0, 0))))
        val before = f.commit(store, ops, sealed = 5)
        val update = listOf(f.constant(1, 1, TimelineValue.Vec3(1280, 0, 0), 5, revision = 2))
        val after = f.commit(store, update, sealed = 5)
        val prefix = after.objects.getValue(1).curves.getValue(1).segments.first() as TimelineSegment.Interpolated
        assertEquals(5, prefix.hi)
        assertEquals(10, prefix.anchor1Time)
        for (time in 0L..4L) assertEquals(TimelineProfile.sample(before, 1, 1, time), TimelineProfile.sample(after, 1, 1, time))
        assertFailsWith<ProtocolException> { f.commit(store, listOf(f.constant(1, 1, TimelineValue.Vec3(0, 0, 0), 4, revision = 3)), sealed = 5) }
        assertSame(after, store.current)
    }

    @Test fun `prediction must be explicitly confirmed before advancing seal`() {
        val store = TimelineStore(f.context())
        val prediction = TimelineOperation.Splice(1, 1, 1, 10, 20, listOf(TimelineSegment.Interpolated(10, 20, true, 10, 20,
            TimelineValue.Vec3(256, 512, 0), TimelineValue.Vec3(512, 512, 0))))
        f.commit(store, f.root() + f.unit() + prediction, observed = 10, sealed = 5, until = 20)
        assertFailsWith<ProtocolException> { f.commit(store, emptyList(), observed = 15, sealed = 11, until = 20) }
        val confirm = TimelineOperation.Splice(1, 1, 2, 10, 20, listOf(TimelineSegment.Interpolated(10, 20, false, 10, 20,
            TimelineValue.Vec3(256, 512, 0), TimelineValue.Vec3(512, 512, 0))))
        val after = f.commit(store, listOf(confirm), observed = 20, sealed = 20)
        assertFalse(after.objects.getValue(1).curves.getValue(1).segments.any { it.predicted })
    }

    @Test fun `RESET can restore historical close below new seal and retains authorized history`() {
        val store = TimelineStore(f.context())
        val ops = f.root() + f.unit(team = 1) + TimelineOperation.Close(1, 4, TimelineCloseKind.ENDED)
        val state = f.commit(store, ops, sealed = 10)
        assertNotNull(TimelineProfile.sample(state, 1, 1, 3))
        assertNull(TimelineProfile.sample(state, 1, 1, 4))
        assertEquals(1, state.objects.getValue(1).curves.getValue(1).curveRevision)
        val updated = f.commit(store, listOf(TimelineOperation.Retain(4)), observed = 11, sealed = 10, from = 4)
        assertFalse(updated.objects.containsKey(1))
        assertEquals(1, updated.header.objectHighWater)
        assertFailsWith<ProtocolException> { f.commit(store, f.unit(1, 10), observed = 11, sealed = 10, from = 4) }
    }

    @Test fun `RESET restores more than one pulse batch without lowering high water`() {
        val store = TimelineStore(f.context())
        val tail = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(10000))))
        val events = (1L..5000L).map { TimelinePulse(it, it, TimelineValue.UInt(it)) }
        val ops = f.root() + listOf(TimelineOperation.Create(1, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(1, 65536, TimelineMode.PULSE, TimelineValueType.UINT, false, 7000, tail),
            TimelineOperation.Pulse(1, 65536, 0, 1, 4097, events.take(4096)),
            TimelineOperation.Pulse(1, 65536, 1, 4097, 5001, events.drop(4096)))
        val state = f.commit(store, ops, observed = 5000, sealed = 0)
        val curve = state.objects.getValue(1).curves.getValue(65536)
        assertEquals(5000, curve.events.size)
        assertEquals(7000, curve.eventHighWater)
        assertEquals(2, curve.curveRevision)
        val remove = TimelineOperation.Pulse(1, 65536, 2, 1, 2, emptyList())
        val removed = f.commit(store, listOf(remove), observed = 5000)
        val resurrect = TimelineOperation.Pulse(1, 65536, 3, 1, 2, listOf(events.first()))
        assertFailsWith<ProtocolException> { f.commit(store, listOf(resurrect), observed = 5000) }
        assertSame(removed, store.current)
    }

    @Test fun `carry in pulses keep closed identity until declared tail expires without revision change`() {
        val store = TimelineStore(f.context())
        val tail = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(5))))
        val ops = f.root() + listOf(TimelineOperation.Create(1, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(1, 65536, TimelineMode.PULSE, TimelineValueType.UINT, false, 1, tail),
            TimelineOperation.Pulse(1, 65536, 0, 0, 10, listOf(TimelinePulse(8, 1, TimelineValue.UInt(7)))),
            TimelineOperation.Close(1, 9, TimelineCloseKind.ENDED))
        f.commit(store, ops, observed = 20, sealed = 20)
        val retained = f.commit(store, listOf(TimelineOperation.Retain(10)), observed = 20, sealed = 20, from = 10)
        val curve = retained.objects.getValue(1).curves.getValue(65536)
        assertEquals(8, curve.events.single().time)
        assertEquals(1, curve.curveRevision)
        val empty = f.commit(store, listOf(TimelineOperation.Retain(13)), observed = 20, sealed = 20, from = 13)
        assertTrue(empty.objects.getValue(1).curves.getValue(65536).events.isEmpty())
        val collected = f.commit(store, listOf(TimelineOperation.Retain(14)), observed = 20, sealed = 20, from = 14)
        assertFalse(collected.objects.containsKey(1))
    }

    @Test fun `same pulse may be re listed unchanged but cannot be changed under old id`() {
        val store = TimelineStore(f.context())
        val tail = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(5))))
        val event = TimelinePulse(4, 1, TimelineValue.UInt(7))
        val ops = f.root() + listOf(TimelineOperation.Create(1, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(1, 65536, TimelineMode.PULSE, TimelineValueType.UINT, false, 1, tail),
            TimelineOperation.Pulse(1, 65536, 0, 0, 10, listOf(event)))
        f.commit(store, ops)
        f.commit(store, listOf(TimelineOperation.Pulse(1, 65536, 1, 0, 10, listOf(event))))
        val changed = TimelineOperation.Pulse(1, 65536, 2, 0, 10, listOf(event.copy(value = TimelineValue.UInt(8))))
        assertFailsWith<ProtocolException> { f.commit(store, listOf(changed)) }
    }

    @Test fun `network HISTORY is sealed exact accepted window and never gets current controls`() {
        val context = f.context().copy(mode = TimelineStreamMode.HISTORY, acceptedFrom = 3, acceptedUntil = 8)
        val store = TimelineStore(context)
        val ops = f.root(from = 3) + f.unit(from = 3)
        val header = f.header(ops, observed = 10, sealed = 8, from = 3, until = 8, control = 0)
        assertFailsWith<ProtocolException> { store.apply(header.copy(controlEpoch = 1), ops) }
        val result = store.apply(header, ops)
        assertNotNull(TimelineProfile.sample(result, 1, 1, 7))
        assertNull(TimelineProfile.sample(result, 1, 1, 8))
    }

    @Test fun `prepared transactions publish only once after successful writer commit`() {
        val store = TimelineStore(f.context())
        val ops = f.root() + f.unit()
        val prepared = store.prepare(f.header(ops), ops)
        assertNull(store.current)
        prepared.commit()
        assertSame(prepared.state, store.current)
        assertFailsWith<ProtocolException> { prepared.commit() }
        val before = store.current!!
        val first = store.prepare(f.header(emptyList(), before, observed = 11), emptyList())
        val second = store.prepare(f.header(emptyList(), before, observed = 12), emptyList())
        first.commit()
        assertFailsWith<ProtocolException> { second.commit() }
        assertSame(first.state, store.current)
        val fork = TimelineStore.fromCommitted(first.state)
        f.commit(fork, emptyList(), observed = 13)
        assertSame(first.state, store.current)
        assertNotSame(store.current, fork.current)
    }

    @Test fun `carry in event cannot be edited before retained lower bound`() {
        val store = TimelineStore(f.context())
        val tail = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(100))))
        val ops = f.root() + listOf(TimelineOperation.Create(1, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(1, 65536, TimelineMode.PULSE, TimelineValueType.UINT, false, 1, tail),
            TimelineOperation.Pulse(1, 65536, 0, 0, 10, listOf(TimelinePulse(8, 1, TimelineValue.UInt(7)))))
        f.commit(store, ops, observed = 20, sealed = 20)
        val retained = f.commit(store, listOf(TimelineOperation.Retain(10)), observed = 20, sealed = 20, from = 10)
        assertFailsWith<ProtocolException> {
            f.commit(store, listOf(TimelineOperation.Pulse(1, 65536, 1, 8, 9, emptyList())), observed = 20, sealed = 20, from = 10)
        }
        assertSame(retained, store.current)
    }

    @Test fun `instant event exactly at retained floor survives until floor passes it`() {
        val store = TimelineStore(f.context())
        val tail = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(0))))
        val ops = f.root() + listOf(TimelineOperation.Create(1, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(1, 65536, TimelineMode.PULSE, TimelineValueType.UINT, false, 1, tail),
            TimelineOperation.Pulse(1, 65536, 0, 0, 10, listOf(TimelinePulse(5, 1, TimelineValue.UInt(7)))),
            TimelineOperation.Close(1, 6, TimelineCloseKind.ENDED))
        f.commit(store, ops, sealed = 10)
        val floor = f.commit(store, listOf(TimelineOperation.Retain(5)), sealed = 10, from = 5)
        assertEquals(5L, floor.objects.getValue(1).curves.getValue(65536).events.single().time)
        val passed = f.commit(store, listOf(TimelineOperation.Retain(6)), sealed = 10, from = 6)
        assertFalse(passed.objects.containsKey(1))
    }
}
