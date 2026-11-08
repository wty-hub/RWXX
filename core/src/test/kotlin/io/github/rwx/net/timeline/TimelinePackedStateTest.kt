package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.*

class TimelinePackedStateTest {
    private val f = TimelineTestFixture

    private fun source(units: Int = 3): TimelineState = TimelineStore(f.context()).let { store ->
        f.commit(store, f.root() + (1L..units.toLong()).flatMap { f.unit(it) }, observed = 20, highWater = units.toLong())
    }
    private fun canonical(state: TimelineState) = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
    private fun persistent(state: TimelineState) = ByteArrayOutputStream().also { TimelineCanonical.writePersistent(state, it) }.toByteArray()
    private fun field(library: TimelinePackedLibrary, name: String): Any =
        TimelinePackedLibrary::class.java.getDeclaredField(name).apply { isAccessible = true }.get(library)
    @Suppress("UNCHECKED_CAST")
    private fun pages(library: TimelinePackedLibrary) = field(library, "pages") as Array<ByteArray>
    private fun row(library: TimelinePackedLibrary, handle: Long): Int =
        (field(library, "handles") as LongArray).binarySearch(handle).also { assertTrue(it >= 0) }
    private fun objectPage(library: TimelinePackedLibrary, handle: Long): Pair<ByteArray, Int> {
        val index = row(library, handle)
        return pages(library)[(field(library, "objectPages") as IntArray)[index]] to
            (field(library, "offsets") as IntArray)[index]
    }

    @Test fun `packed view preserves model equality sampling and every canonical byte without a decoded graph`() {
        val original = source()
        val typed = original.copy(objects = original.objects.entries.reversed().associate { (key, obj) ->
            key to obj.copy(curves = obj.curves.entries.reversed().associate { it.toPair() })
        })
        val library = TimelinePackedLibrary.pack(typed, 128)
        val packed = TimelineState.fromPacked(library)
        assertEquals(typed, packed)
        assertEquals(packed, typed)
        assertEquals(typed.hashCode(), packed.hashCode())
        assertEquals(typed.objects.hashCode(), packed.objects.hashCode())
        assertEquals(typed.objects.keys.sorted(), packed.objects.keys.toList())
        assertContentEquals(canonical(typed), canonical(packed))
        assertContentEquals(persistent(typed), persistent(packed))
        assertEquals(TimelineCanonical.persistentSize(typed), TimelineCanonical.persistentSize(packed))
        assertEquals(TimelineCanonical.digest(typed), TimelineCanonical.digest(packed))
        for (handle in typed.objects.keys) for (attribute in typed.objects.getValue(handle).curves.keys) {
            for (time in listOf(0L, 10L, 20L, 21L))
                assertEquals(TimelineProfile.sample(typed, handle, attribute, time), TimelineProfile.sample(packed, handle, attribute, time))
        }
        assertNull(packed.objects[999])
        assertEquals(typed.objects.getValue(1), packed.objects.entries.single { it.key == 1L }.value)
        assertNotSame(packed.objects.getValue(1), packed.objects.getValue(1), "No decoded-object cache is retained")
        assertSame(library, packed.packedLibrary)
        assertSame(library.context, packed.context)
        assertSame(library.header, packed.header)
        for (name in listOf("typedContext", "typedHeader"))
            assertNull(TimelineState::class.java.getDeclaredField(name).apply { isAccessible = true }.get(packed))
    }

    @Test fun `directory-only traversal never decodes a value and direct get checks actual handle`() {
        val library = TimelinePackedLibrary.pack(source(), 128)
        val packed = TimelineState.fromPacked(library)
        val lengths = field(library, "lengths") as IntArray
        val rootLength = lengths[0]
        try {
            lengths[0] = 0 // any attempt to decode ROOT now fails, including hidden eager reads
            assertEquals(4, packed.objects.size)
            assertTrue(packed.objects.containsKey(0))
            assertEquals(listOf(0L, 1L, 2L, 3L), packed.objects.keys.toList())
            assertEquals(listOf(0L, 1L, 2L, 3L), packed.objects.entries.map { it.key })
            assertEquals(1L, packed.objects.getValue(1).handle)
            assertFailsWith<ProtocolException> { packed.objects.getValue(0) }
            assertFailsWith<ProtocolException> { TimelineCanonical.digest(packed) }
        } finally { lengths[0] = rootLength }

        val first = row(library, 1); val second = row(library, 2)
        val pageIds = field(library, "objectPages") as IntArray
        val offsets = field(library, "offsets") as IntArray
        val old = intArrayOf(pageIds[first], offsets[first], lengths[first])
        try {
            pageIds[first] = pageIds[second]; offsets[first] = offsets[second]; lengths[first] = lengths[second]
            assertTrue(packed.objects.containsKey(1))
            assertFailsWith<ProtocolException> { packed.objects.getValue(1) }
        } finally { pageIds[first] = old[0]; offsets[first] = old[1]; lengths[first] = old[2] }
        assertEquals(1L, packed.objects.getValue(1).handle)
    }

    @Test fun `map keys values entries and even no-op mutators keep Java unmodifiable behavior`() {
        val packed = TimelineState.fromPacked(TimelinePackedLibrary.pack(source()))
        val map = packed.objects as MutableMap<Long, TimelineObject>
        val value = map.getValue(1)
        assertFailsWith<UnsupportedOperationException> { map[1] = value }
        assertFailsWith<UnsupportedOperationException> { map.remove(999) }
        assertFailsWith<UnsupportedOperationException> { map.putAll(emptyMap()) }
        assertFailsWith<UnsupportedOperationException> { map.clear() }
        assertFailsWith<UnsupportedOperationException> { (map.keys as MutableSet<Long>).remove(999) }
        assertFailsWith<UnsupportedOperationException> { (map.values as MutableCollection<TimelineObject>).clear() }
        val iterator = map.entries.iterator()
        val entry = iterator.next()
        assertFailsWith<UnsupportedOperationException> { entry.setValue(value) }
        assertFailsWith<UnsupportedOperationException> { iterator.remove() }
        assertEquals(4, packed.objects.size)
    }

    @Test fun `public construction and copy still snapshot input and defer invalid-state rejection`() {
        val original = source()
        val objects = LinkedHashMap(original.objects)
        val state = TimelineState(original.context, original.header, objects)
        objects.clear()
        assertEquals(original, state)
        val mutableCopy = LinkedHashMap(original.objects)
        val copied = state.copy(objects = mutableCopy)
        mutableCopy.clear()
        assertEquals(original, copied)
        assertNull(copied.packedLibrary)

        val malformed = TimelineState(original.context.copy(teamId = 70), original.header.copy(epoch = 0), emptyMap())
        val malformedCopy = malformed.copy()
        assertEquals(malformed, malformedCopy)
        assertFailsWith<ProtocolException> { TimelineCanonical.digest(malformedCopy) }
        val packed = TimelineState.fromPacked(TimelinePackedLibrary.pack(original))
        val invalidFromPackedCopy = packed.copy(header = packed.header.copy(epoch = 0), objects = emptyMap())
        assertTrue(invalidFromPackedCopy.objects.isEmpty())
        assertNull(invalidFromPackedCopy.packedLibrary)
        assertFailsWith<ProtocolException> { TimelineCanonical.digest(invalidFromPackedCopy) }
    }

    @Test fun `actual record mutation changes every canonical path while a public copy is isolated`() {
        val typed = source()
        val library = TimelinePackedLibrary.pack(typed, 128)
        val packed = TimelineState.fromPacked(library)
        val copied = packed.copy()
        assertNull(copied.packedLibrary)
        val before = TimelineCanonical.digest(packed)
        val cursor = assertNotNull(library.objectCursor(0))
        cursor.objectHeader(); cursor.curveHeader(60)
        cursor.vuint(); cursor.vuint(); cursor.bool()
        val (bytes, offset) = objectPage(library, 0)
        val at = offset + cursor.position
        val old = bytes[at]
        assertEquals(0xc8.toByte(), old) // ZigZag(100) first byte
        try {
            bytes[at] = 0xca.toByte() // legal 101, with the same storage length
            val root = typed.objects.getValue(0)
            val credits = root.curves.getValue(1)
            val changedRoot = root.copy(curves = root.curves + (1 to credits.copy(segments = listOf(
                (credits.segments.single() as TimelineSegment.Step).copy(value = TimelineValue.SInt(101))))))
            val expected = typed.copy(objects = typed.objects + (0L to changedRoot))
            assertEquals(expected, packed)
            assertNotEquals(before, TimelineCanonical.digest(packed))
            assertContentEquals(canonical(expected), canonical(packed))
            assertContentEquals(persistent(expected), persistent(packed))
            assertEquals(TimelineCanonical.persistentSize(expected), TimelineCanonical.persistentSize(packed))
            assertEquals(typed, copied)
            assertEquals(before, TimelineCanonical.digest(copied))
        } finally { bytes[at] = old }
        assertEquals(before, TimelineCanonical.digest(packed))
        TimelineCanonical.write(packed, object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) { bytes.fill(0) }
        })
        assertEquals(before, TimelineCanonical.digest(packed))
    }

    @Test fun `packed bridge still runs full profile admission after primitive structure validation`() {
        val library = TimelinePackedLibrary.pack(source(), 128)
        val cursor = assertNotNull(library.objectCursor(1))
        val obj = cursor.objectHeader()
        var typeOffset = -1
        repeat(obj.curveCount) {
            val start = cursor.position
            val curve = cursor.curveHeader(60)
            if (curve.attributeId == 3) typeOffset = start + 2 // one-byte id, then mode, then type
            repeat(curve.recordCount) { cursor.segment(curve.mode, curve.valueType) }
        }
        assertTrue(typeOffset >= 0)
        val (bytes, offset) = objectPage(library, 1)
        val at = offset + typeOffset
        val old = bytes[at]
        assertEquals(TimelineValueType.SINT.code.toByte(), old)
        try {
            bytes[at] = TimelineValueType.UINT.code.toByte()
            library.validate() // canonical STEP/UINT is valid, registered health/UINT is not
            assertFailsWith<ProtocolException> { TimelineState.fromPacked(library) }
        } finally { bytes[at] = old }
        TimelineProfile.validate(TimelineState.fromPacked(library))
    }

    @Test fun `header-only COW views read their own library and full hashing rechecks actual static map`() {
        val store = TimelineStore(f.context())
        val first = f.commit(store, f.root() + f.unit(), observed = 20)
        val owner = TimelinePackedLibrary.Owner(first, 128)
        val old = TimelineState.fromPacked(owner.current)
        val oldDigest = TimelineCanonical.digest(old)
        val next = f.commit(store, emptyList(), observed = 21)
        val prepared = owner.prepare(next)
        val changed = TimelineState.fromPacked(prepared.state)
        assertSame(old.header, first.header)
        assertSame(changed.header, next.header)
        assertEquals(owner.current.pageCount, prepared.state.sharedPagesWith(owner.current))
        assertNotEquals(oldDigest, TimelineCanonical.digest(changed))
        prepared.commit()
        assertEquals(oldDigest, TimelineCanonical.digest(old))

        val terrain = (changed.context.mapRecord[6]!!.value as TimelineTlvValue.Bytes).value
        val storage = TimelineBytes::class.java.getDeclaredField("storage").apply { isAccessible = true }.get(terrain) as ByteArray
        val original = storage[0]
        try {
            storage[0] = 1 // legal terrain bit, but its pinned map key must no longer validate
            assertFailsWith<ProtocolException> { TimelineCanonical.digest(changed) }
            assertFailsWith<ProtocolException> { TimelineCanonical.persistentSize(changed) }
        } finally { storage[0] = original }
        assertEquals(TimelineCanonical.digest(next), TimelineCanonical.digest(changed))
    }
}
