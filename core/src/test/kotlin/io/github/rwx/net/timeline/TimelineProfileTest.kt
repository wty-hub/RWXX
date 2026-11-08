package io.github.rwx.net.timeline

import kotlin.test.*

class TimelineProfileTest {
    private val f = TimelineTestFixture

    private fun panel(handle: Long = 2, subject: Long = 1, from: Long = 0): List<TimelineOperation> = listOf(
        TimelineOperation.Create(handle, TimelineObjectKind.PANEL, from, TimelineRecord(listOf(
            TimelineField(2, true, TimelineTlvValue.U64(subject)),
            TimelineField(5, true, TimelineTlvValue.Bytes(f.compact(1, subject)))))),
        TimelineOperation.Declare(handle, 1, TimelineMode.STEP, TimelineValueType.BOOL, true),
        f.step(handle, 1, TimelineValue.Bool(true), from))

    private fun action(handle: Long = 3, subject: Long = 1, panel: Long = 2, from: Long = 0): List<TimelineOperation> {
        val ability = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U32(2)),
            TimelineField(2, true, TimelineTlvValue.U32(0)), TimelineField(3, true, TimelineTlvValue.Bool(false)),
            TimelineField(4, true, TimelineTlvValue.Text("action.tank")), TimelineField(5, true, TimelineTlvValue.Text("tank")),
            TimelineField(6, true, TimelineTlvValue.I64(100)), TimelineField(7, true, TimelineTlvValue.U32(1))))
        return listOf(TimelineOperation.Create(handle, TimelineObjectKind.ACTION, from, TimelineRecord(listOf(
            TimelineField(2, true, TimelineTlvValue.U64(subject)), TimelineField(3, true, TimelineTlvValue.U64(panel)),
            TimelineField(4, true, TimelineTlvValue.Record(ability))))),
            TimelineOperation.Declare(handle, 1, TimelineMode.STEP, TimelineValueType.UINT, true),
            f.step(handle, 1, TimelineValue.UInt(1), from))
    }

    @Test fun `map resource pools must exactly match public resource bits`() {
        val correct = f.map(byteArrayOf(0, 4, 0, 0), f.compact(1, 1, 0))
        TimelineStore(f.context(correct))
        val wrong = f.map(byteArrayOf(4, 0, 0, 0), f.compact(1, 1, 0))
        assertFailsWith<ProtocolException> { TimelineStore(f.context(wrong)) }
        assertFailsWith<ProtocolException> { TimelineStore(f.context().copy(mapKey = TimelineBytes(ByteArray(32)))) }
    }

    @Test fun `required attributes cannot be missing or develop gaps`() {
        val store = TimelineStore(f.context())
        assertFailsWith<ProtocolException> { f.commit(store, f.root() + TimelineOperation.Create(1, TimelineObjectKind.UNIT, 0)) }
        assertNull(store.current)
        val state = f.commit(store, f.root() + f.unit())
        val gap = TimelineOperation.Splice(1, 1, 1, 3, 4, emptyList())
        assertFailsWith<ProtocolException> { f.commit(store, listOf(gap)) }
        assertSame(state, store.current)
    }

    @Test fun `neutral teams remain visible but cannot enter own selection or action panel`() {
        for (team in listOf(-2L, -1L, 1L)) {
            val store = TimelineStore(f.context())
            val state = f.commit(store, f.root() + f.unit(team = team))
            assertEquals(TimelineValue.SInt(team), TimelineProfile.sample(state, 1, 6, 10))
            val select = f.step(0, 3, TimelineValue.Blob(f.compact(1, 1)), 10, revision = 1)
            assertFailsWith<ProtocolException> { f.commit(store, listOf(select), control = 2) }
            assertSame(state, store.current)
        }
    }

    @Test fun `closing selected unit requires explicit selection and capability closure in same transaction`() {
        val store = TimelineStore(f.context())
        val before = f.commit(store, f.root(selection = f.compact(1, 1)) + f.unit() + panel() + action(), highWater = 3)
        assertFailsWith<ProtocolException> { f.commit(store, listOf(TimelineOperation.Close(1, 5, TimelineCloseKind.ENDED)), highWater = 3, control = 2) }
        assertSame(before, store.current)
        val closes = listOf(f.step(0, 3, TimelineValue.Blob(f.compact(0)), 5, revision = 1),
            TimelineOperation.Close(1, 5, TimelineCloseKind.ENDED), TimelineOperation.Close(2, 5, TimelineCloseKind.ENDED),
            TimelineOperation.Close(3, 5, TimelineCloseKind.ENDED))
        assertFailsWith<ProtocolException> { f.commit(store, closes, highWater = 3) }
        val closed = f.commit(store, closes, sealed = 10, highWater = 3, control = 2)
        assertNotNull(TimelineProfile.sample(closed, 1, 1, 4))
        assertNull(TimelineProfile.sample(closed, 1, 1, 5))
        assertEquals(1L, closed.objects.getValue(2).curves.getValue(1).curveRevision)
        val retained = f.commit(store, listOf(TimelineOperation.Retain(5)), sealed = 10, from = 5, highWater = 3)
        assertEquals(setOf(0L), retained.objects.keys)
    }

    @Test fun `action cannot borrow another subject or a dangling panel`() {
        for (actions in listOf(action(panel = 99), action(subject = 9))) {
            val store = TimelineStore(f.context())
            val ops = f.root(selection = f.compact(1, 1)) + f.unit() + panel() + actions
            assertFailsWith<ProtocolException> { f.commit(store, ops, highWater = 3) }
        }
    }

    @Test fun `independent HISTORY panel validates within slice without requiring current selection outside it`() {
        val context = f.context().copy(mode = TimelineStreamMode.HISTORY, acceptedFrom = 3, acceptedUntil = 8)
        val store = TimelineStore(context)
        val full = f.root(selection = f.compact(1, 1), from = 3) + f.unit(from = 3) + panel(from = 3) + action(from = 3)
        val trims = full.filterIsInstance<TimelineOperation.Declare>().map {
            TimelineOperation.Splice(it.handle, it.attributeId, 1, 8, Long.MAX_VALUE, emptyList())
        }
        val ops = full + trims
        val state = store.apply(f.header(ops, observed = 10, sealed = 8, from = 3, until = 8, highWater = 3, control = 0), ops)
        assertEquals(TimelineValue.Bool(true), TimelineProfile.sample(state, 2, 1, 7))
        assertNull(TimelineProfile.sample(state, 2, 1, 10))
    }

    @Test fun `unknown optional state persists and changes full hash but cannot predict or claim reserved ids`() {
        fun ops(id: Int, predicted: Boolean, secretByte: Int): List<TimelineOperation> = f.root() + f.unit() + listOf(
            TimelineOperation.Declare(1, id, TimelineMode.LINEAR, TimelineValueType.UINT, false,
                metadata = TimelineRecord(listOf(TimelineField(9, false, TimelineTlvValue.Opaque(99, f.bytes(secretByte)))))),
            TimelineOperation.Splice(1, id, 0, 0, 11, listOf(TimelineSegment.Interpolated(0, 11, predicted, 0, 11,
                TimelineValue.UInt(7), TimelineValue.UInt(7)))))
        val a = f.commit(TimelineStore(f.context()), ops(65536, false, 1))
        val b = f.commit(TimelineStore(f.context()), ops(65536, false, 2))
        assertNotEquals(TimelineCanonical.digest(a), TimelineCanonical.digest(b))
        assertEquals(1, (a.objects.getValue(1).curves.getValue(65536).metadata[9]!!.value as TimelineTlvValue.Opaque).value[0].toInt())
        assertFailsWith<ProtocolException> { f.commit(TimelineStore(f.context()), ops(65536, true, 1)) }
        assertFailsWith<ProtocolException> { f.commit(TimelineStore(f.context()), ops(100, false, 1)) }
    }

    @Test fun `ordinary nonconstant curves cannot disguise future predictions and sensitive step cannot predict`() {
        val future = TimelineOperation.Splice(1, 1, 1, 10, 20, listOf(TimelineSegment.Interpolated(10, 20, false, 10, 20,
            TimelineValue.Vec3(256, 512, 0), TimelineValue.Vec3(512, 512, 0))))
        assertFailsWith<ProtocolException> { f.commit(TimelineStore(f.context()), f.root() + f.unit() + future) }
        val resource = TimelineOperation.Splice(0, 1, 1, 0, Long.MAX_VALUE,
            listOf(TimelineSegment.Step(0, Long.MAX_VALUE, true, TimelineValue.SInt(100))))
        assertFailsWith<ProtocolException> { f.commit(TimelineStore(f.context()), f.root() + f.unit() + resource) }
        val futureResource = f.step(0, 1, TimelineValue.SInt(200), 20, revision = 1)
        assertFailsWith<ProtocolException> { f.commit(TimelineStore(f.context()), f.root() + f.unit() + futureResource) }
        assertFailsWith<ProtocolException> { f.commit(TimelineStore(f.context()), f.root(status = f.finished(5)) + f.unit()) }
    }

    @Test fun `fog absence means unknown and shortest page encoding is required`() {
        val store = TimelineStore(f.context())
        val initial = f.commit(store, f.root() + f.unit())
        assertContentEquals(byteArrayOf(10, 10, 10, 10), TimelineProfile.fogPage(initial, 0, 5))
        val declare = TimelineOperation.Declare(0, 4096, TimelineMode.STEP, TimelineValueType.BLOB, true)
        val raw = f.step(0, 4096, TimelineValue.Blob(f.bytes(0, 0, 0, 0, 0)))
        assertFailsWith<ProtocolException> { f.commit(store, listOf(declare, raw)) }
        val rle = f.step(0, 4096, TimelineValue.Blob(f.bytes(1, 1, 4, 0)))
        val withFog = f.commit(store, listOf(declare, rle))
        assertContentEquals(ByteArray(4), TimelineProfile.fogPage(withFog, 0, 5))
        val remove = TimelineOperation.Splice(0, 4096, 1, 5, 6, emptyList())
        val gap = f.commit(store, listOf(remove))
        assertContentEquals(byteArrayOf(10, 10, 10, 10), TimelineProfile.fogPage(gap, 0, 5))
        assertFailsWith<ProtocolException> { f.commit(store,
            listOf(TimelineOperation.Declare(0, 4097, TimelineMode.STEP, TimelineValueType.BLOB, true))) }
    }

    @Test fun `sealed terminal survives reset and cannot leave current capabilities`() {
        val store = TimelineStore(f.context())
        val finished = f.commit(store, f.root(status = f.finished(0)) + f.unit(), sealed = 5)
        val running = f.root() + f.unit()
        assertFailsWith<ProtocolException> { store.apply(f.header(running, finished, sealed = 5, reset = true, control = 2), running) }
        assertSame(finished, store.current)
        val terminalPanel = f.root(status = f.finished(0), selection = f.compact(1, 1)) + f.unit() + panel() + action()
        assertFailsWith<ProtocolException> { f.commit(TimelineStore(f.context()), terminalPanel, highWater = 3) }
    }

    @Test fun `seek and reverse sampling never resurrect closed observation or mutate committed digest`() {
        val ops = f.root() + f.unit(team = 1) + TimelineOperation.Close(1, 5, TimelineCloseKind.ENDED)
        val store = TimelineStore(f.context())
        val state = f.commit(store, ops, sealed = 5)
        val before = TimelineCanonical.digest(state)
        for (time in listOf(10L, 4L, 0L, 8L, 3L, 5L, 4L)) {
            val sample = TimelineProfile.sample(state, 1, 1, time)
            assertEquals(time < 5, sample != null)
            assertEquals(before, TimelineCanonical.digest(state))
        }
        assertSame(state, store.current)
        assertEquals(TimelineCloseKind.ENDED, state.objects.getValue(1).close!!.kind)
    }

    @Test fun `same time pulse group must fit one restoring operation as well as record count`() {
        val tail = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(60000))))
        val store = TimelineStore(f.context())
        val state = f.commit(store, f.root() + listOf(TimelineOperation.Create(1, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(1, 65536, TimelineMode.PULSE, TimelineValueType.BLOB, false, 0, tail)))
        val original = state.objects.getValue(1)
        val payload = TimelineValue.Blob(TimelineBytes(ByteArray(65536)))
        fun withEvents(count: Int): TimelineState {
            val curve = original.curves.getValue(65536).copy(eventHighWater = count.toLong(),
                events = (1L..count.toLong()).map { TimelinePulse(5, it, payload) })
            return state.copy(objects = state.objects + (1L to original.copy(curves = mapOf(65536 to curve))))
        }
        TimelineProfile.validate(withEvents(15))
        val tooLarge = withEvents(17)
        assertFailsWith<ProtocolException> { TimelineProfile.validate(tooLarge) }
        assertFailsWith<ProtocolException> { TimelineStore.fromCommitted(tooLarge) }
    }
}
