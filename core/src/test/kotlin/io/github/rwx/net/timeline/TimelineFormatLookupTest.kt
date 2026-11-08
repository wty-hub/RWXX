package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

class TimelineFormatLookupTest {
    private val f = TimelineTestFixture

    private fun source(units: Int = 1, sealed: Long = 0): TimelineStore = TimelineStore(f.context()).also {
        f.commit(it, f.root() + (1L..units.toLong()).flatMap { handle -> f.unit(handle) }, observed = 20,
            sealed = sealed, highWater = units.toLong())
    }
    private fun formats(state: TimelineState) = state.objects.values.flatMap { obj -> obj.curves.values.map {
        TimelineCurveKey(obj.handle, it.attributeId) to it.format
    } }.toMap()
    private fun join(vararg singleOperation: TimelineBytes): TimelineBytes = ByteArrayOutputStream().let { out ->
        TimelineBinary.writeVUInt(out, singleOperation.size.toLong())
        singleOperation.forEach { raw ->
            val bytes = raw.toByteArray()
            assertEquals(1, bytes[0].toInt())
            out.write(bytes, 1, bytes.size - 1)
        }
        TimelineBytes(out.toByteArray())
    }

    @Test fun `all six operation kinds match the Map codec and retain real Store and digest validation`() {
        val base = source(sealed = 10).current!!
        val pulseMetadata = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(100))))
        val operations = listOf(
            TimelineOperation.Retain(5),
            f.step(1, 3, TimelineValue.SInt(70), 10, revision = 1),
            TimelineOperation.Create(2, TimelineObjectKind.VISUAL, 10),
            TimelineOperation.Declare(2, 65536, TimelineMode.STEP, TimelineValueType.SINT, false),
            f.step(2, 65536, TimelineValue.SInt(-7), 10),
            TimelineOperation.Declare(2, 65537, TimelineMode.PULSE, TimelineValueType.UINT, false, metadata = pulseMetadata),
            TimelineOperation.Pulse(2, 65537, 0, 11, 12, listOf(TimelinePulse(11, 1, TimelineValue.UInt(9)))),
            TimelineOperation.Close(2, 12, TimelineCloseKind.ENDED),
            TimelineOperation.Close(1, 12, TimelineCloseKind.ENDED))
        val oldDigest = TimelineCanonical.digest(base)
        val expected = TimelineBinary.encodeOperations(operations, formats(base))
        assertEquals(expected, TimelineBinary.encodeOperationsFromState(operations, base))
        assertEquals(operations, TimelineBinary.decodeOperationsFromState(expected, base))
        assertEquals(operations, TimelineBinary.decodeOperations(expected, formats(base)))
        val header = f.header(operations, base, observed = 20, sealed = 10, from = 5, highWater = 2, control = 2)
        val rawStore = TimelineStore.fromCommitted(base)
        val typedStore = TimelineStore.fromCommitted(base)
        val raw = rawStore.apply(header, expected)
        val typed = typedStore.apply(header, operations)
        assertEquals(TimelineCanonical.digest(raw), TimelineCanonical.digest(typed))
        assertEquals(TimelineValue.SInt(80), TimelineProfile.sample(raw, 1, 3, 9))
        assertEquals(TimelineValue.SInt(70), TimelineProfile.sample(raw, 1, 3, 11))
        assertNull(TimelineProfile.sample(raw, 1, 3, 12))
        assertEquals(TimelineValue.SInt(-7), TimelineProfile.sample(raw, 2, 65536, 11))
        assertEquals(1L, raw.objects.getValue(2).curves.getValue(65537).eventHighWater)
        assertEquals(oldDigest, TimelineCanonical.digest(base))
    }

    @Test fun `Declare is immediately visible but is local to each encode or decode transaction`() {
        val base = source().current!!
        val declaration = TimelineOperation.Declare(1, 65536, TimelineMode.STEP, TimelineValueType.SINT, false)
        val splice = f.step(1, 65536, TimelineValue.SInt(9), 10)
        val operations = listOf(declaration, splice)
        val expected = TimelineBinary.encodeOperations(operations, formats(base))
        assertEquals(expected, TimelineBinary.encodeOperationsFromState(operations, base))
        assertEquals(operations, TimelineBinary.decodeOperationsFromState(expected, base))
        assertFalse(base.objects.getValue(1).curves.containsKey(65536))
        assertFailsWith<MissingBase> { TimelineBinary.encodeOperationsFromState(listOf(splice), base) }
        val standalone = TimelineBinary.encodeOperations(listOf(splice),
            mapOf(TimelineCurveKey(1, 65536) to TimelineCurveFormat(TimelineMode.STEP, TimelineValueType.SINT)))
        assertFailsWith<MissingBase> { TimelineBinary.decodeOperationsFromState(standalone, base) }

        val existing = TimelineOperation.Declare(1, 3, TimelineMode.STEP, TimelineValueType.SINT, true)
        val existingRaw = TimelineBinary.encodeOperations(listOf(existing))
        assertFailsWith<ProtocolException> { TimelineBinary.encodeOperationsFromState(listOf(existing), base) }
        assertFailsWith<ProtocolException> { TimelineBinary.decodeOperationsFromState(existingRaw, base) }
        val one = TimelineBinary.encodeOperations(listOf(declaration))
        val duplicate = join(one, one)
        assertFailsWith<ProtocolException> { TimelineBinary.decodeOperationsFromState(duplicate, base) }
        assertFailsWith<ProtocolException> { TimelineBinary.encodeOperationsFromState(listOf(declaration, declaration), base) }
        assertFailsWith<ProtocolException> { TimelineBinary.validateOperationEnvelope(duplicate) }
    }

    @Test fun `missing bases preserve bounded envelope behavior and sender self decode still rejects malformed operations`() {
        val base = source().current!!
        val step = f.step(1, 3, TimelineValue.SInt(70), 10, revision = 1)
        val raw = TimelineBinary.encodeOperations(listOf(step), formats(base))
        assertFailsWith<MissingBase> { TimelineBinary.decodeOperationsFromState(raw, null) }
        TimelineBinary.validateOperationEnvelope(raw)
        assertFailsWith<ProtocolException> { TimelineBinary.validateOperationEnvelope(TimelineBytes(raw.toByteArray() + 0)) }
        val overflow = f.step(1, 3, TimelineValue.SInt(70), 10, revision = Long.MAX_VALUE)
        // The encoder can write this nonnegative number; its real decoder must reject the operation.
        assertFailsWith<ProtocolException> { TimelineBinary.encodeOperationsFromState(listOf(overflow), base) }
        assertFailsWith<ProtocolException> { TimelineBinary.encodeOperations(listOf(overflow), formats(base)) }
        val reset = TimelineEncoder.resetOperations(base)
        assertEquals(TimelineBinary.encodeOperations(reset), TimelineBinary.encodeOperationsFromState(reset, null))
        assertEquals(reset, TimelineBinary.decodeOperationsFromState(TimelineBinary.encodeOperations(reset), null))
        assertFailsWith<ProtocolException> { TimelineBinary.encodeOperationsFromState(reset, base) }
    }

    @Test fun `Retain and Close do not erase the codec base while the Store still rejects edits to removed objects`() {
        val source = source()
        val closed = f.commit(source, listOf(TimelineOperation.Close(1, 10, TimelineCloseKind.ENDED)),
            observed = 20, sealed = 10, control = 2)
        val revision = closed.objects.getValue(1).curves.getValue(3).curveRevision
        val operations = listOf(TimelineOperation.Retain(10), f.step(1, 3, TimelineValue.SInt(70), 10, revision = revision))
        val raw = TimelineBinary.encodeOperations(operations, formats(closed))
        assertEquals(raw, TimelineBinary.encodeOperationsFromState(operations, closed))
        assertEquals(operations, TimelineBinary.decodeOperationsFromState(raw, closed))
        val header = f.header(operations, closed, observed = 21, sealed = 10, from = 10)
        assertFailsWith<ProtocolException> { source.apply(header, raw) }
        assertSame(closed, source.current)
        val redeclare = listOf(TimelineOperation.Retain(10),
            TimelineOperation.Declare(1, 3, TimelineMode.STEP, TimelineValueType.SINT, true))
        assertFailsWith<ProtocolException> { TimelineBinary.encodeOperationsFromState(redeclare, closed) }
    }

    @Test fun `public Map arguments retain defensive snapshots and their exact JVM entry points`() {
        val base = source().current!!
        val initial = formats(base).toMutableMap()
        val before = initial.toMap()
        val operations = listOf(TimelineOperation.Declare(1, 65536, TimelineMode.STEP, TimelineValueType.UINT, false),
            f.step(1, 65536, TimelineValue.UInt(7), 10))
        val bytes = TimelineBinary.encodeOperations(operations, initial)
        assertEquals(before, initial)
        assertEquals(operations, TimelineBinary.decodeOperations(bytes, initial))
        assertEquals(before, initial)
        assertEquals(TimelineBytes::class.java,
            TimelineBinary::class.java.getMethod("encodeOperations", List::class.java, Map::class.java).returnType)
        assertEquals(List::class.java,
            TimelineBinary::class.java.getMethod("decodeOperations", TimelineBytes::class.java, Map::class.java).returnType)

        // As before, each pass snapshots the supplied Map. Mutating it during iteration
        // cannot mutate the writer's snapshot or be hidden from the later real decode.
        val changing = formats(base).toMutableMap()
        val operation = f.step(1, 3, TimelineValue.SInt(70), 10, revision = 1)
        val input = object : AbstractList<TimelineOperation>() {
            override val size = 1
            override fun get(index: Int): TimelineOperation { check(index == 0); changing.clear(); return operation }
        }
        assertFailsWith<MissingBase> { TimelineBinary.encodeOperations(input, changing) }
    }

    @Test fun `sparse state format lookup never traverses unrelated committed objects including an empty update`() {
        val state = source(1024).current!!
        val operation = f.step(1, 3, TimelineValue.SInt(70), 10, revision = 1)
        val expected = TimelineBinary.encodeOperations(listOf(operation), formats(state))
        val original = state.objects
        val requested = ArrayList<Long>()
        // Read-only instrumentation of a valid immutable map: only a whole-map traversal fails.
        // It does not change any object, curve, or return value of a keyed lookup.
        val lookupOnly = object : AbstractMap<Long, TimelineObject>() {
            override val entries: Set<Map.Entry<Long, TimelineObject>> get() = error("Codec traversed the entire committed library")
            override val size: Int get() = original.size
            override fun get(key: Long): TimelineObject? { requested.add(key); return original[key] }
        }
        val field = TimelineState::class.java.getDeclaredField("objects").apply { isAccessible = true }
        field.set(state, lookupOnly)
        try {
            assertEquals(expected, TimelineBinary.encodeOperationsFromState(listOf(operation), state))
            assertEquals(listOf(operation), TimelineBinary.decodeOperationsFromState(expected, state))
            assertTrue(requested.isNotEmpty() && requested.size <= 6 && requested.all { it == 1L })
            requested.clear()
            val empty = TimelineBinary.encodeOperationsFromState(emptyList(), state)
            assertEquals(TimelineBytes(byteArrayOf(0)), empty)
            assertTrue(TimelineBinary.decodeOperationsFromState(empty, state).isEmpty())
            assertTrue(requested.isEmpty())
        } finally { field.set(state, original) }
        TimelineProfile.validate(state)
        assertEquals(TimelineCanonical.digest(state), TimelineCanonical.digest(state.copy()))
    }
}
