package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

class TimelinePackedPrimitivesTest {
    private val f = TimelineTestFixture

    private fun source(): TimelineState {
        val store = TimelineStore(f.context())
        val tail = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(20))))
        val ops = f.root() + f.unit(1) + f.unit(2) + listOf(
            TimelineOperation.Declare(0, 65536, TimelineMode.PULSE, TimelineValueType.SINT, false, eventHighWater = 2, metadata = tail),
            TimelineOperation.Pulse(0, 65536, 0, 0, 20,
                listOf(TimelinePulse(0, 1, TimelineValue.SInt(7)), TimelinePulse(10, 2, TimelineValue.SInt(8)))),
            TimelineOperation.Declare(0, 65537, TimelineMode.STEP, TimelineValueType.BLOB, false),
            f.step(0, 65537, TimelineValue.Blob(f.bytes(1, 2, 3))),
            TimelineOperation.Declare(0, 65538, TimelineMode.LINEAR, TimelineValueType.VEC3, false),
            TimelineOperation.Close(2, 20, TimelineCloseKind.ENDED))
        return f.commit(store, ops, observed = 20, highWater = 2)
    }

    private fun count(state: TimelineState): Long = state.objects.values.sumOf { obj ->
        obj.curves.values.sumOf { it.segments.size.toLong() + it.events.size }
    }
    private fun field(name: String) = TimelinePackedLibrary::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun pages(library: TimelinePackedLibrary): Array<ByteArray> = field("pages").get(library) as Array<ByteArray>
    private fun canonical(state: TimelineState) = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()

    @Test fun `complete actual scan counts pulse optional and closed records while keeping validate void ABI`() {
        val state = source()
        val library = TimelinePackedLibrary.pack(state, 128)
        val stats = library.validatedStats()
        assertEquals(24L, count(state))
        assertEquals(count(state), stats.records)
        assertEquals(1, stats.activeUnits)
        assertEquals(3, stats.objectCount)
        assertEquals(stats, library.validatedStats())
        assertEquals(Void.TYPE, TimelinePackedLibrary::class.java.getDeclaredMethod("validate").returnType)
        library.validate()
    }

    @Test fun `same header cannot cache record counts or validation of actual replacement pages`() {
        val state = source()
        val first = TimelinePackedLibrary.pack(state, 128)
        val initial = first.validatedStats()
        val root = state.objects.getValue(0)
        val old = root.curves.getValue(65537)
        val segment = old.segments.single() as TimelineSegment.Step
        val changed = state.copy(objects = state.objects + (0L to root.copy(curves = root.curves + (65537 to old.copy(
            segments = listOf(segment.copy(hi = 10), segment.copy(lo = 10)))))))
        val replacement = TimelinePackedLibrary.pack(changed, 128)
        // Fault injection changes every physical record/directory array while leaving the
        // context/header identity untouched. No public mutable page API is introduced.
        for (name in listOf("pages", "handles", "objectPages", "offsets", "lengths"))
            field(name).set(first, field(name).get(replacement))
        assertSame(state.header, first.header)
        assertEquals(initial.records + 1, first.validatedStats().records)
        assertEquals(24L, initial.records, "The earlier primitive result is immutable")
        val last = pages(first).last()
        assertEquals(1, last.last().toInt(), "Last native UNIT record is its true control BOOL")
        last[last.lastIndex] = 2
        assertFailsWith<ProtocolException> { first.validatedStats() }
        assertFailsWith<ProtocolException> { first.validate() }
    }

    @Test fun `format lookup consumes malformed trailing records even after finding an early attribute`() {
        val state = source()
        val library = TimelinePackedLibrary.pack(state, 128)
        for ((handle, obj) in state.objects) for ((id, curve) in obj.curves)
            assertEquals(curve.format, library.curveFormat(handle, id))
        assertNull(library.curveFormat(999, 1))
        assertNull(library.curveFormat(-1, 1))
        assertNull(library.curveFormat(1, 999))
        assertNull(library.curveFormat(1, 0))
        assertEquals(TimelineCurveFormat(TimelineMode.LINEAR, TimelineValueType.VEC3), library.curveFormat(0, 65538),
            "An empty declaration still has a format")
        val last = pages(library).last()
        assertEquals(1, last.last().toInt())
        last[last.lastIndex] = 2 // UNIT 2 attr 9, after requested attr 1.
        assertFailsWith<ProtocolException> { library.curveFormat(2, 1) }
        assertFailsWith<ProtocolException> { library.curveFormat(2, 999) }
    }

    @Test fun `map key and directory faults are not hidden by summary or format shortcuts`() {
        val state = source()
        val badMap = TimelinePackedLibrary.pack(state)
        field("context").set(badMap, state.context.copy(mapKey = TimelineBytes(ByteArray(32) { 9 })))
        assertFailsWith<ProtocolException> { badMap.validatedStats() }
        val badIdentity = TimelinePackedLibrary.pack(state, 128)
        val handles = field("handles").get(badIdentity) as LongArray
        handles[2] = 9
        assertFailsWith<ProtocolException> { badIdentity.curveFormat(9, 1) }
        assertFailsWith<ProtocolException> { badIdentity.validatedStats() }
        val badLength = TimelinePackedLibrary.pack(state, 128)
        val lengths = field("lengths").get(badLength) as IntArray
        lengths[2]--
        assertFailsWith<ProtocolException> { badLength.curveFormat(2, 1) }
        assertFailsWith<ProtocolException> { badLength.validatedStats() }
    }

    @Test fun `packed and public Map formats preserve complete operation bytes declaration overlay retain close and reset`() {
        val typedStore = TimelineStore(f.context())
        val original = f.commit(typedStore, f.root() + f.unit(1) + f.unit(2), observed = 20, sealed = 5, highWater = 2)
        val packed = TimelineState.fromPacked(TimelinePackedLibrary.pack(original, 128))
        val formats = original.objects.values.flatMap { obj -> obj.curves.values.map {
            TimelineCurveKey(obj.handle, it.attributeId) to it.format
        } }.toMap()
        val ops = listOf(
            TimelineOperation.Retain(5),
            TimelineOperation.Declare(1, 65536, TimelineMode.STEP, TimelineValueType.BLOB, false),
            f.step(1, 65536, TimelineValue.Blob(f.bytes(7, 9)), from = 5),
            f.step(1, 3, TimelineValue.SInt(60), from = 5, revision = 1),
            TimelineOperation.Close(2, 20, TimelineCloseKind.ENDED))
        val publicBytes = TimelineBinary.encodeOperations(ops, formats)
        val packedBytes = TimelineBinary.encodeOperationsFromState(ops, packed)
        assertEquals(publicBytes, packedBytes)
        assertEquals(ops, TimelineBinary.decodeOperations(publicBytes, formats))
        assertEquals(ops, TimelineBinary.decodeOperationsFromState(packedBytes, packed))
        val header = f.header(ops, original, observed = 21, sealed = 5, from = 5, control = 2, highWater = 2)
        val fromPacked = TimelineStore.fromCommitted(packed)
        val a = typedStore.apply(header, publicBytes)
        val b = fromPacked.apply(header, packedBytes)
        assertContentEquals(canonical(a), canonical(b))
        assertEquals(TimelineCanonical.digest(a), TimelineCanonical.digest(b))

        // RESET explicitly has no previous format lookup, even with an existing committed state.
        val resetOps = f.root() + f.unit(1)
        val resetBytes = TimelineBinary.encodeOperationsFromState(resetOps, null)
        assertEquals(TimelineBinary.encodeOperations(resetOps), resetBytes)
        assertEquals(resetOps, TimelineBinary.decodeOperationsFromState(resetBytes, null))
        val reset = f.header(resetOps, a, observed = 22, control = 3, reset = true, highWater = 1)
        val resetA = typedStore.apply(reset, resetBytes)
        val resetB = fromPacked.apply(reset, resetBytes)
        assertContentEquals(canonical(resetA), canonical(resetB))
        assertEquals(TimelineCanonical.digest(resetA), TimelineCanonical.digest(resetB))
    }
}
