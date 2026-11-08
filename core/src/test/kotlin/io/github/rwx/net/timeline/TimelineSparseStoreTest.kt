package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class TimelineSparseStoreTest {
    private val f = TimelineTestFixture

    private fun initial(units: Int = 4, sealed: Long = 5): TimelineState = TimelineStore(f.context()).let {
        f.commit(it, f.root() + (1L..units.toLong()).flatMap { handle -> f.unit(handle) },
            observed = 20, sealed = sealed, highWater = units.toLong())
    }

    private class PairStores(val normal: TimelineStore, val sparse: TimelineStore)
    private fun pair(state: TimelineState = initial()): PairStores = PairStores(TimelineStore.fromCommitted(state),
        TimelineStore.experimentalPacked(TimelineState.fromPacked(TimelinePackedLibrary.pack(state, 128))))
    private fun canonical(state: TimelineState): ByteArray = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
    private fun equal(normal: TimelineState, sparse: TimelineState) {
        assertEquals(normal, sparse)
        assertContentEquals(canonical(normal), canonical(sparse))
        assertEquals(TimelineCanonical.digest(normal), TimelineCanonical.digest(sparse))
        assertNull(normal.packedLibrary, "Default Store must continue committing its original typed representation")
        assertNotNull(sparse.packedLibrary)
    }
    private fun commit(stores: PairStores, ops: List<TimelineOperation>, observed: Long,
                       sealed: Long = stores.normal.current!!.header.sealedThrough,
                       from: Long = stores.normal.current!!.header.retainedFrom,
                       control: Long = stores.normal.current!!.header.controlEpoch,
                       high: Long = stores.normal.current!!.header.objectHighWater): TimelineState {
        val previous = stores.normal.current!!
        val header = f.header(ops, previous, observed = observed, sealed = sealed, from = from, control = control, highWater = high)
        val bytes = TimelineBinary.encodeOperationsFromState(ops, previous)
        val a = stores.normal.apply(header, bytes)
        val b = stores.sparse.apply(header, bytes)
        equal(a, b)
        return b
    }

    @Test fun `sparse updates preserve all six operation rules and actual canonical bytes without changing defaults`() {
        val stores = pair(initial(24))
        val original = stores.sparse.current!!
        val originalDigest = TimelineCanonical.digest(original)
        val first = commit(stores, listOf(
            f.step(1, 3, TimelineValue.SInt(70), from = 5, revision = 1),
            f.step(1, 3, TimelineValue.SInt(60), from = 10, revision = 2)), observed = 21)
        assertEquals(3L, first.objects.getValue(1).curves.getValue(3).curveRevision)
        assertTrue(first.packedLibrary!!.sharedPagesWith(original.packedLibrary!!) > 0)
        val tail = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(30))))
        commit(stores, listOf(
            TimelineOperation.Declare(0, 65536, TimelineMode.PULSE, TimelineValueType.SINT, false, metadata = tail),
            TimelineOperation.Pulse(0, 65536, 0, 5, 21,
                listOf(TimelinePulse(10, 1, TimelineValue.SInt(7)), TimelinePulse(20, 2, TimelineValue.SInt(8))))), observed = 22)
        commit(stores, listOf(TimelineOperation.Pulse(0, 65536, 1, 5, 21,
            listOf(TimelinePulse(10, 1, TimelineValue.SInt(7)), TimelinePulse(20, 3, TimelineValue.SInt(9))))), observed = 23)
        commit(stores, f.unit(25, from = 23), observed = 24, high = 25)
        commit(stores, listOf(TimelineOperation.Close(25, 24, TimelineCloseKind.ENDED)), observed = 25, control = 2)
        commit(stores, emptyList(), observed = 26, sealed = 20)
        assertEquals(originalDigest, TimelineCanonical.digest(original), "Old immutable pages remain unchanged")
        assertFailsWith<ProtocolException> { TimelineStore.experimentalPacked(initial()) }
    }

    @Test fun `retain and reset deliberately use original typed staging then return packed commits`() {
        val stores = pair(initial(2))
        commit(stores, listOf(TimelineOperation.Close(2, 5, TimelineCloseKind.ENDED)), observed = 21, sealed = 10, control = 2)
        val retained = commit(stores, listOf(TimelineOperation.Retain(10)), observed = 22, from = 10)
        assertFalse(2L in retained.objects)
        assertEquals(10L, retained.objects.getValue(1).curves.getValue(1).segments.first().lo)
        val ops = f.root() + f.unit(1)
        val before = stores.normal.current!!
        val header = f.header(ops, before, observed = 23, sealed = 10, control = 3, reset = true, highWater = 1)
        val bytes = TimelineBinary.encodeOperationsFromState(ops, null)
        val a = stores.normal.apply(header, bytes)
        val b = stores.sparse.apply(header, bytes)
        equal(a, b)
        assertEquals(2L, b.header.epoch)
        commit(stores, listOf(f.step(1, 3, TimelineValue.SInt(55), from = 10, revision = 1)), observed = 24)
    }

    @Test fun `later invalid operations SHA and missing base leave both committed states intact`() {
        val stores = pair()
        val beforeNormal = stores.normal.current!!
        val beforeSparse = stores.sparse.current!!
        val ops = listOf(f.step(1, 3, TimelineValue.SInt(70), from = 5, revision = 1),
            TimelineOperation.Close(999, 10, TimelineCloseKind.ENDED))
        val header = f.header(ops, beforeNormal, observed = 21, sealed = 5, highWater = 4)
        val bytes = TimelineBinary.encodeOperationsFromState(ops, beforeNormal)
        for (store in listOf(stores.normal, stores.sparse)) assertFailsWith<ProtocolException> { store.apply(header, bytes) }
        assertSame(beforeNormal, stores.normal.current)
        assertSame(beforeSparse, stores.sparse.current)
        val good = listOf(f.step(1, 3, TimelineValue.SInt(70), from = 5, revision = 1))
        val goodHeader = f.header(good, beforeNormal, observed = 21, sealed = 5, highWater = 4)
        val goodBytes = TimelineBinary.encodeOperationsFromState(good, beforeNormal)
        for (store in listOf(stores.normal, stores.sparse)) {
            assertFailsWith<ProtocolException> { store.apply(goodHeader.copy(operationSha256 = TimelineBytes(ByteArray(32))), goodBytes) }
            assertFailsWith<MissingBase> { store.apply(goodHeader.copy(baseRevision = goodHeader.baseRevision + 1,
                revision = goodHeader.revision + 1), goodBytes) }
        }
        assertSame(beforeNormal, stores.normal.current)
        assertSame(beforeSparse, stores.sparse.current)
        equal(beforeNormal, beforeSparse)
    }

    @Test fun `prepared fork keeps the experimental path and stale commits cannot publish or mutate their base`() {
        val source = initial()
        val store = pair(source).sparse
        val before = store.current!!
        val ops = listOf(f.step(1, 3, TimelineValue.SInt(70), from = 5, revision = 1))
        val header = f.header(ops, source, observed = 21, sealed = 5, highWater = 4)
        val bytes = TimelineBinary.encodeOperationsFromState(ops, source)
        val prepared = store.prepare(header, bytes)
        val competing = store.prepare(header, bytes)
        assertNotNull(prepared.state.packedLibrary)
        assertSame(before, store.current)
        val fork = store.fork()
        assertSame(before, fork.current)
        assertNotNull(fork.apply(header, bytes).packedLibrary)
        assertSame(before, store.current)
        prepared.commit()
        assertSame(prepared.state, store.current)
        assertFailsWith<ProtocolException> { competing.commit() }
        assertFailsWith<ProtocolException> { prepared.commit() }
        assertContentEquals(canonical(source), canonical(before))
    }

    @Test fun `real pulse revision identity and final control gates are not delegated to permissive page edits`() {
        val stores = pair()
        commit(stores, listOf(
            TimelineOperation.Declare(0, 65536, TimelineMode.PULSE, TimelineValueType.SINT, false,
                metadata = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(0))))),
            TimelineOperation.Pulse(0, 65536, 0, 5, 21,
                listOf(TimelinePulse(10, 1, TimelineValue.SInt(7)), TimelinePulse(20, 2, TimelineValue.SInt(8))))), observed = 22)
        val cases = listOf(
            listOf(TimelineOperation.Pulse(0, 65536, 1, 5, 21,
                listOf(TimelinePulse(10, 1, TimelineValue.SInt(99))))) to "Event ID reused",
            listOf(TimelineOperation.Pulse(0, 65536, 0, 5, 21,
                listOf(TimelinePulse(10, 1, TimelineValue.SInt(7))))) to "Curve revision mismatch",
            listOf(f.step(1, 9, TimelineValue.Bool(false), from = 5, revision = 1)) to
                "Control change did not revoke old epoch")
        for ((ops, message) in cases) {
            val previous = stores.normal.current!!
            val header = f.header(ops, previous, observed = 23, sealed = 5, highWater = 4)
            val bytes = TimelineBinary.encodeOperationsFromState(ops, previous)
            for (store in listOf(stores.normal, stores.sparse)) {
                val before = store.current
                val failure = assertFailsWith<ProtocolException> { store.apply(header, bytes) }
                assertEquals(message, failure.message)
                assertSame(before, store.current)
            }
        }
    }

    @Test fun `experimental store retains the original sequential cross thread contract`() {
        val stores = pair()
        val failure = AtomicReference<Throwable?>()
        thread(name = "sparse-store-sequential-test") {
            try { commit(stores, listOf(f.step(1, 3, TimelineValue.SInt(70), from = 5, revision = 1)), observed = 21) }
            catch (caught: Throwable) { failure.set(caught) }
        }.join()
        failure.get()?.let { throw it }
        commit(stores, listOf(f.step(1, 3, TimelineValue.SInt(60), from = 10, revision = 2)), observed = 22)
    }

    @Test fun `Receiver missing base recovery and RESET admission stay identical`() {
        val stores = pair()
        val normal = TimelineReceiver(stores.normal)
        val sparse = TimelineReceiver(stores.sparse)
        val before = stores.normal.current!!
        val bytes = TimelineBinary.encodeOperationsFromState(emptyList(), before)
        val normalHeader = f.header(emptyList(), before, observed = 21, sealed = 5, highWater = 4)
        val missing = normalHeader.copy(baseRevision = normalHeader.baseRevision + 1, revision = normalHeader.revision + 1)
        fun transfer(receiver: TimelineReceiver, header: TimelineHeader, data: TimelineBytes): TimelineState? {
            receiver.begin(header)
            receiver.chunk(timelineFrame(107, tn(1, header.streamId, true), tn(2, header.epoch), tn(3, header.revision),
                tn(4, 0), tb(5, data)))
            return receiver.commit(timelineFrame(108, tn(1, header.streamId, true), tn(2, header.epoch), tn(3, header.revision)))
        }
        assertNull(transfer(normal, missing, bytes))
        assertNull(transfer(sparse, missing, bytes))
        assertTrue(normal.waitingReset && sparse.waitingReset)
        assertEquals(normal.recoveries, sparse.recoveries)
        val resetOps = f.root() + f.unit(1)
        val reset = f.header(resetOps, before, observed = 22, control = 2, reset = true, highWater = 1)
        val resetBytes = TimelineBinary.encodeOperations(resetOps)
        equal(assertNotNull(transfer(normal, reset, resetBytes)), assertNotNull(transfer(sparse, reset, resetBytes)))
        assertFalse(normal.waitingReset || sparse.waitingReset)
    }

    @Test fun `actual two million record cap is enforced at the first growth even when a later splice would shrink it`() {
        // One shared immutable BOOL keeps this genuine capacity fixture smaller than a
        // world-sized payload. It still passes the real Profile/canonical/packed gates.
        val base = initial(1, sealed = 0)
        val existing = base.objects.values.sumOf { obj -> obj.curves.values.sumOf { it.segments.size } }
        val count = TimelineProtocol.MAX_CURVE_RECORDS - existing
        val value = TimelineValue.Bool(true)
        val segments = List(count) { index -> TimelineSegment.Step(index.toLong(), index + 1L, false, value) }
        val root = base.objects.getValue(0)
        val full = base.copy(header = base.header.copy(observedThrough = count + 1L, retainedUntil = count + 2L),
            objects = base.objects + (0L to root.copy(curves = root.curves + (65536 to TimelineCurve(65536,
                TimelineMode.STEP, TimelineValueType.BOOL, false, curveRevision = 1, segments = segments)))))
        val stores = pair(full)
        assertEquals(TimelineProtocol.MAX_CURVE_RECORDS.toLong(), stores.sparse.current!!.packedLibrary!!.validatedStats().records)
        val ops = listOf(
            f.step(0, 65536, value, from = count.toLong(), until = count + 1L, revision = 1),
            f.step(0, 65536, value, from = 0, until = count + 1L, revision = 2))
        val header = f.header(ops, full, observed = count + 1L, highWater = 1)
        val bytes = TimelineBinary.encodeOperationsFromState(ops, full)
        for (store in listOf(stores.normal, stores.sparse)) {
            val before = store.current
            val failure = assertFailsWith<ProtocolException> { store.apply(header, bytes) }
            assertEquals("Too many staged curve records", failure.message)
            assertSame(before, store.current)
        }
    }
}
