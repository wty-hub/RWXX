package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class TimelinePackedRetainTest {
    private val f = TimelineTestFixture
    private class Stores(val typed: TimelineStore, val packed: TimelineStore)
    private fun initial(ops: List<TimelineOperation> = f.root() + f.unit(), high: Long = 1,
                        observed: Long = 30, sealed: Long = 20): TimelineState =
        f.commit(TimelineStore(f.context()), ops, observed = observed, sealed = sealed, highWater = high)
    private fun stores(source: TimelineState = initial()): Stores = Stores(TimelineStore.fromCommitted(source),
        TimelineStore.experimentalPacked(TimelineState.fromPacked(TimelinePackedLibrary.pack(source, 128))))
    private fun bytes(state: TimelineState): ByteArray = ByteArrayOutputStream().also {
        TimelineCanonical.write(state, it)
    }.toByteArray()
    private fun same(a: TimelineState, b: TimelineState) {
        assertEquals(a, b)
        assertContentEquals(bytes(a), bytes(b))
        assertEquals(TimelineCanonical.digest(a), TimelineCanonical.digest(b))
        TimelineProfile.validate(a); TimelineProfile.validate(b)
        assertNull(a.packedLibrary); assertNotNull(b.packedLibrary)
    }
    private fun commit(stores: Stores, ops: List<TimelineOperation>, from: Long,
                       observed: Long = stores.typed.current!!.header.observedThrough + 1,
                       sealed: Long = stores.typed.current!!.header.sealedThrough,
                       high: Long = stores.typed.current!!.header.objectHighWater,
                       control: Long = stores.typed.current!!.header.controlEpoch): TimelineState {
        val before = stores.typed.current!!
        val header = f.header(ops, before, observed = observed, sealed = sealed, from = from, highWater = high, control = control)
        val operations = TimelineBinary.encodeOperationsFromState(ops, before)
        val typed = stores.typed.apply(header, operations)
        val packed = stores.packed.apply(header, operations)
        same(typed, packed)
        return packed
    }
    private fun tail(value: Long) = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(value))))
    private fun pulse(handle: Long, id: Int, times: List<Long>, maxTail: Long): List<TimelineOperation> = listOf(
        TimelineOperation.Declare(handle, id, TimelineMode.PULSE, TimelineValueType.UINT, false,
            times.size.toLong(), tail(maxTail)),
        TimelineOperation.Pulse(handle, id, 0, 0, 31, times.mapIndexed { index, time ->
            TimelinePulse(time, index + 1L, TimelineValue.UInt(index + 10L))
        }))

    @Test fun `retention preserves original interpolation anchors gaps metadata revisions and pulse floor semantics`() {
        val opaque = TimelineRecord(listOf(TimelineField(9, false, TimelineTlvValue.Opaque(99, f.bytes(1, 2, 3)))))
        val linear = listOf(
            TimelineSegment.Interpolated(0, 4, false, 0, 20, TimelineValue.UInt(0), TimelineValue.UInt(100)),
            TimelineSegment.Interpolated(6, 20, false, 0, 20, TimelineValue.UInt(0), TimelineValue.UInt(100)))
        val angle = listOf(TimelineSegment.Interpolated(0, 20, false, 0, 20,
            TimelineValue.UInt(65000), TimelineValue.UInt(100)))
        val ops = f.root() + f.unit() + listOf(
            TimelineOperation.Declare(0, 65536, TimelineMode.STEP, TimelineValueType.BOOL, false),
            TimelineOperation.Splice(0, 65536, 0, 0, Long.MAX_VALUE, listOf(
                TimelineSegment.Step(0, 4, false, TimelineValue.Bool(false)),
                TimelineSegment.Step(4, 10, false, TimelineValue.Bool(true)),
                TimelineSegment.Step(10, Long.MAX_VALUE, false, TimelineValue.Bool(false)))),
            TimelineOperation.Declare(0, 65537, TimelineMode.LINEAR, TimelineValueType.UINT, false, metadata = opaque),
            TimelineOperation.Splice(0, 65537, 0, 0, 20, linear),
            TimelineOperation.Declare(0, 65538, TimelineMode.ANGLE, TimelineValueType.UINT, false),
            TimelineOperation.Splice(0, 65538, 0, 0, 20, angle)) +
            pulse(0, 65539, listOf(7, 8, 9), 0) + pulse(0, 65540, listOf(3, 4, 8), 5)
        val source = initial(ops)
        val pair = stores(source)
        val oldPacked = pair.packed.current!!
        val oldBytes = bytes(oldPacked)
        val plan = TimelinePackedRetention.scan(oldPacked, 8)
        val state = commit(pair, listOf(TimelineOperation.Retain(8)), 8)
        assertEquals(state.packedLibrary!!.validatedStats().records, plan.records)
        val curves = state.objects.getValue(0).curves
        assertEquals(listOf(8L, 10L), curves.getValue(65536).segments.map { it.lo })
        val clipped = curves.getValue(65537).segments.single() as TimelineSegment.Interpolated
        assertEquals(8L, clipped.lo); assertEquals(20L, clipped.hi)
        assertEquals(0L, clipped.anchor0Time); assertEquals(20L, clipped.anchor1Time)
        assertEquals(opaque, curves.getValue(65537).metadata)
        for (id in 65536..65540) {
            assertEquals(source.objects.getValue(0).curves.getValue(id).curveRevision, curves.getValue(id).curveRevision)
            assertEquals(source.objects.getValue(0).curves.getValue(id).eventHighWater, curves.getValue(id).eventHighWater)
        }
        for (time in 8L..19L) for (id in listOf(65537, 65538))
            assertEquals(TimelineProfile.sample(source, 0, id, time), TimelineProfile.sample(state, 0, id, time))
        assertNull(TimelineProfile.sample(state, 0, 65537, 20))
        assertEquals(listOf(8L, 9L), curves.getValue(65539).events.map { it.time })
        assertEquals(listOf(4L, 8L), curves.getValue(65540).events.map { it.time })
        assertContentEquals(oldBytes, bytes(oldPacked))
        val empty = commit(pair, listOf(TimelineOperation.Retain(20)), 20)
        assertTrue(empty.objects.getValue(0).curves.getValue(65540).events.isEmpty())
        assertEquals(3L, empty.objects.getValue(0).curves.getValue(65540).eventHighWater)
        val reused = listOf(TimelineOperation.Pulse(0, 65540, 1, 20, 21,
            listOf(TimelinePulse(20, 1, TimelineValue.UInt(10)))))
        val reusedHeader = f.header(reused, pair.typed.current, observed = 33, sealed = 20, from = 20)
        val encoded = TimelineBinary.encodeOperationsFromState(reused, pair.typed.current)
        for (store in listOf(pair.typed, pair.packed)) {
            val before = store.current
            assertEquals("Event ID reused", assertFailsWith<ProtocolException> { store.apply(reusedHeader, encoded) }.message)
            assertSame(before, store.current)
        }
    }

    private fun panel() = listOf(
        TimelineOperation.Create(2, TimelineObjectKind.PANEL, 0, TimelineRecord(listOf(
            TimelineField(2, true, TimelineTlvValue.U64(1)),
            TimelineField(5, true, TimelineTlvValue.Bytes(f.compact(1, 1)))))),
        TimelineOperation.Declare(2, 1, TimelineMode.STEP, TimelineValueType.BOOL, true),
        f.step(2, 1, TimelineValue.Bool(true)))
    private fun action(): List<TimelineOperation> {
        val ability = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U32(2)),
            TimelineField(2, true, TimelineTlvValue.U32(0)), TimelineField(3, true, TimelineTlvValue.Bool(false)),
            TimelineField(4, true, TimelineTlvValue.Text("action.tank")), TimelineField(5, true, TimelineTlvValue.Text("tank")),
            TimelineField(6, true, TimelineTlvValue.I64(100)), TimelineField(7, true, TimelineTlvValue.U32(1))))
        return listOf(TimelineOperation.Create(3, TimelineObjectKind.ACTION, 0, TimelineRecord(listOf(
            TimelineField(2, true, TimelineTlvValue.U64(1)), TimelineField(3, true, TimelineTlvValue.U64(2)),
            TimelineField(4, true, TimelineTlvValue.Record(ability))))),
            TimelineOperation.Declare(3, 1, TimelineMode.STEP, TimelineValueType.UINT, true),
            f.step(3, 1, TimelineValue.UInt(1)),
            // An empty declaration still delays collection of the action and both parents.
            TimelineOperation.Declare(3, 65536, TimelineMode.PULSE, TimelineValueType.UINT, false, metadata = tail(10)))
    }

    @Test fun `empty tail declaration retains descriptor parents then natural reference GC removes the entire chain`() {
        val ops = f.root(selection = f.compact(1, 1)) + f.unit() + panel() + action() + listOf(
            f.step(0, 3, TimelineValue.Blob(f.compact(0)), from = 5, revision = 1),
            TimelineOperation.Close(1, 5, TimelineCloseKind.ENDED),
            TimelineOperation.Close(2, 5, TimelineCloseKind.ENDED),
            TimelineOperation.Close(3, 5, TimelineCloseKind.ENDED))
        val pair = stores(initial(ops, high = 3))
        val kept = commit(pair, listOf(TimelineOperation.Retain(5)), 5)
        assertEquals(setOf(0L, 1L, 2L, 3L), kept.objects.keys)
        assertTrue(kept.objects.getValue(3).curves.getValue(65536).events.isEmpty())
        assertEquals(0L, kept.objects.getValue(3).curves.getValue(65536).curveRevision)
        commit(pair, listOf(TimelineOperation.Retain(14)), 14)
        val collected = commit(pair, listOf(TimelineOperation.Retain(15)), 15)
        assertEquals(setOf(0L), collected.objects.keys)
        assertEquals(3L, collected.header.objectHighWater)
    }

    @Test fun `same transaction observes trimmed records and handles then preserves close and create ordering`() {
        val source = initial(f.root() + f.unit() + f.unit(2) +
            TimelineOperation.Close(2, 5, TimelineCloseKind.ENDED), high = 2)
        val pair = stores(source)
        val ops = listOf(TimelineOperation.Retain(10),
            f.step(1, 3, TimelineValue.SInt(60), from = 20, revision = 1),
            TimelineOperation.Close(1, 25, TimelineCloseKind.ENDED)) + f.unit(3, from = 25)
        val state = commit(pair, ops, from = 10, high = 3, control = 2)
        assertEquals(setOf(0L, 1L, 3L), state.objects.keys)
        val health = state.objects.getValue(1).curves.getValue(3)
        assertEquals(listOf(10L, 20L), health.segments.map { it.lo })
        assertEquals(25L, health.segments.last().hi)
        assertEquals(2L, health.curveRevision)
        assertEquals(TimelineValue.SInt(60), TimelineProfile.sample(state, 1, 3, 24))
    }

    @Test fun `after retain unknown removed handles reused identities and old revisions fail atomically`() {
        val source = initial(f.root() + f.unit() + f.unit(2) +
            TimelineOperation.Close(2, 5, TimelineCloseKind.ENDED), high = 2)
        val cases = listOf(
            listOf(TimelineOperation.Retain(10), f.step(2, 3, TimelineValue.SInt(60), 20, revision = 1)),
            // Reach Store's reused-handle gate before any duplicate declaration codec gate.
            listOf(TimelineOperation.Retain(10), TimelineOperation.Create(2, TimelineObjectKind.UNIT, 20)),
            listOf(TimelineOperation.Retain(10), f.step(1, 3, TimelineValue.SInt(60), 20, revision = 0)))
        for (ops in cases) {
            val pair = stores(source)
            val header = f.header(ops, source, observed = 31, sealed = 20, from = 10, highWater = 2)
            val encoded = TimelineBinary.encodeOperationsFromState(ops, source)
            val messages = listOf(pair.typed, pair.packed).map { store ->
                val before = store.current!!; val digest = TimelineCanonical.digest(before)
                val failure = assertFailsWith<ProtocolException> { store.apply(header, encoded) }
                assertSame(before, store.current); assertEquals(digest, TimelineCanonical.digest(store.current!!))
                failure.message
            }
            assertEquals(messages[0], messages[1])
        }
    }

    @Test fun `retention only provider owns stable handles edits and reuses untouched object pages`() {
        val source = initial(f.root() + (1L..24L).flatMap { f.unit(it, from = 10) }, high = 24)
        val packed = TimelineState.fromPacked(TimelinePackedLibrary.pack(source, 128))
        val plan = TimelinePackedRetention.scan(packed, 10)
        assertEquals(25, plan.objectCount)
        assertEquals(24 * 9L + 3, plan.records)
        assertEquals(listOf(0L), plan.changedHandles().toList())
        val edited = linkedMapOf(0L to plan.retainedObject(0)!!)
        val provider = TimelineSortedObjectEdits.snapshot(edited, plan)
        val expected = provider.valueAt(0)
        edited.clear()
        assertEquals(1, provider.size); assertEquals(0L, provider.handleAt(0))
        assertEquals(expected, provider.valueAt(0))
        assertNull(plan.retainedObject(999)); assertFalse(plan.isChanged(1)); assertFalse(plan.isRemoved(1))
        val pair = Stores(TimelineStore.fromCommitted(source), TimelineStore.experimentalPacked(packed))
        val result = commit(pair, listOf(TimelineOperation.Retain(10)), 10)
        assertTrue(result.packedLibrary!!.sharedPagesWith(packed.packedLibrary!!) > 0)
    }

    @Test fun `valid transaction may exceed sixty four MiB transiently before its final shrink`() {
        val blob = TimelineValue.Blob(TimelineBytes(ByteArray(65536) { (it * 17).toByte() }))
        val source = initial(f.root() + f.unit() + listOf(
            TimelineOperation.Declare(0, 65536, TimelineMode.STEP, TimelineValueType.BLOB, false),
            f.step(0, 65536, blob)), observed = 3000, sealed = 5)
        val pair = stores(source)
        val splits = 1025
        assertTrue((splits + 1L) * blob.value.size > TimelineProtocol.MAX_BLOB_BYTES)
        val ops = buildList {
            add(TimelineOperation.Retain(2))
            repeat(splits) { index ->
                val from = 7L + index * 2
                add(TimelineOperation.Splice(0, 65536, index + 1L, from, from + 1, emptyList()))
            }
            add(f.step(0, 65536, blob, from = 5, revision = splits + 1L))
        }
        val state = commit(pair, ops, from = 2)
        assertEquals(2, state.objects.getValue(0).curves.getValue(65536).segments.size)
        assertEquals(splits + 2L, state.objects.getValue(0).curves.getValue(65536).curveRevision)
        assertTrue(state.packedLibrary!!.persistentSize() < 200_000)
    }

    @Test fun `prepared clears obsolete base after success or stale failure without losing its readable candidate`() {
        for (experimental in listOf(false, true)) {
            val pair = stores()
            val store = if (experimental) pair.packed else pair.typed
            val before = store.current!!
            val ops = listOf(TimelineOperation.Retain(10))
            val header = f.header(ops, before, observed = 31, sealed = 20, from = 10)
            val data = TimelineBinary.encodeOperationsFromState(ops, before)
            val first = store.prepare(header, data)
            val stale = store.prepare(header, data)
            val field = TimelineStore.Prepared::class.java.getDeclaredField("base").apply { isAccessible = true }
            assertSame(before, field.get(first)); assertSame(before, field.get(stale))
            val firstState = first.state; val staleState = stale.state
            first.commit()
            assertNull(field.get(first)); assertSame(firstState, first.state); assertSame(firstState, store.current)
            val message = assertFailsWith<ProtocolException> { stale.commit() }.message
            assertEquals("Prepared transaction is stale or already committed", message)
            assertNull(field.get(stale)); assertSame(staleState, stale.state); assertSame(firstState, store.current)
            assertEquals(message, assertFailsWith<ProtocolException> { first.commit() }.message)
            assertEquals(message, assertFailsWith<ProtocolException> { stale.commit() }.message)
            assertContentEquals(bytes(firstState), bytes(staleState))
        }
    }

    @Test fun `retained experimental fork keeps sequential cross thread and final profile checks`() {
        val pair = stores()
        val error = AtomicReference<Throwable?>()
        thread(name = "packed-retain-sequential-test") {
            try { commit(pair, listOf(TimelineOperation.Retain(10)), 10) }
            catch (failure: Throwable) { error.set(failure) }
        }.join()
        error.get()?.let { throw it }
        val before = pair.packed.current!!
        val fork = pair.packed.fork()
        val ops = listOf(f.step(1, 3, TimelineValue.SInt(50), from = 20, revision = 1))
        val header = f.header(ops, before, observed = 32, sealed = 20, from = 10)
        val state = fork.apply(header, TimelineBinary.encodeOperationsFromState(ops, before))
        assertNotNull(state.packedLibrary); assertSame(before, pair.packed.current)
        val invalid = listOf(TimelineOperation.Retain(15),
            f.step(1, 9, TimelineValue.Bool(false), from = 20, revision = 1))
        val invalidHeader = f.header(invalid, before, observed = 32, sealed = 20, from = 15)
        assertEquals("Control change did not revoke old epoch", assertFailsWith<ProtocolException> {
            pair.packed.apply(invalidHeader, TimelineBinary.encodeOperationsFromState(invalid, before))
        }.message)
        assertSame(before, pair.packed.current)
    }
}
