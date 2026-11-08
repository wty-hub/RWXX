package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.*

class TimelinePackedLibraryTest {
    private val f = TimelineTestFixture

    private fun source(units: Int = 8): TimelineStore = TimelineStore(f.context()).also { store ->
        f.commit(store, f.root() + (1L..units.toLong()).flatMap { f.unit(it) }, observed = 20, highWater = units.toLong())
    }
    private fun canonical(state: TimelineState) = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
    private fun canonical(state: TimelinePackedLibrary) = ByteArrayOutputStream().also { state.writeCanonical(it) }.toByteArray()

    @Test fun `all actual object bytes match canonical including optional metadata pulse and arbitrary insertion order`() {
        val store = source(2)
        val metadata = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(100)),
            TimelineField(50, false, TimelineTlvValue.Opaque(241, f.bytes(1, 0, 255)))))
        val state = f.commit(store, listOf(
            TimelineOperation.Declare(0, 65536, TimelineMode.PULSE, TimelineValueType.SINT, false, metadata = metadata),
            TimelineOperation.Pulse(0, 65536, 0, 0, 11, listOf(
                TimelinePulse(0, 1, TimelineValue.SInt(TimelineProtocol.MIN_SINT)),
                TimelinePulse(10, 2, TimelineValue.SInt(TimelineProtocol.MAX_SINT))))), observed = 20)
        val descriptor = TimelineRecord(listOf(TimelineField(200, false,
            TimelineTlvValue.Record(TimelineRecord(listOf(TimelineField(9, false, TimelineTlvValue.Text("草地🌳"))))))))
        val reordered = state.copy(objects = state.objects.entries.reversed().associate { (handle, obj) ->
            handle to obj.copy(descriptor = if (handle == 2L) descriptor else obj.descriptor,
                curves = obj.curves.entries.reversed().associate { it.toPair() })
        })
        val packed = TimelinePackedLibrary.pack(reordered, 512)
        assertContentEquals(canonical(reordered), canonical(packed))
        assertEquals(TimelineCanonical.digest(reordered), packed.digest())
        assertEquals(TimelineCanonical.persistentSize(reordered), packed.persistentSize())
        for ((handle, obj) in reordered.objects) assertEquals(obj, packed.objectValue(handle))
        assertNull(packed.objectValue(900))
        val table = ByteArrayOutputStream().also { packed.writeObjectTable(it) }.toByteArray()
        assertContentEquals(table, canonical(reordered).takeLast(table.size).toByteArray())
        assertEquals(table.size.toLong() - TimelineBinary.encodeVUInt(packed.objectCount.toLong()).size, packed.recordBytes)
        assertEquals(packed.objectCount * 20L, packed.directoryBytes)
    }

    @Test fun `prepared replacement shares exact unchanged pages and cannot mutate old or failed transactions`() {
        val store = source()
        val initial = assertNotNull(store.current)
        val owner = TimelinePackedLibrary.Owner(initial, 128)
        val old = owner.current
        val oldDigest = old.digest()
        val next = f.commit(store, listOf(f.step(1, 3, TimelineValue.SInt(70), 10, revision = 1)), observed = 20)
        val prepared = owner.prepare(next)
        assertSame(old, owner.current)
        assertEquals(old.pageCount - 1, prepared.state.sharedPagesWith(old))
        assertEquals(oldDigest, old.digest())
        assertContentEquals(canonical(next), canonical(prepared.state))
        val competing = owner.prepare(next)
        assertSame(prepared.state, prepared.commit())
        assertSame(prepared.state, owner.current)
        assertFailsWith<ProtocolException> { prepared.commit() }
        assertFailsWith<ProtocolException> { competing.commit() }
        assertEquals(oldDigest, old.digest())

        val bad = next.copy(objects = next.objects + (1L to next.objects.getValue(1).copy(curves =
            next.objects.getValue(1).curves - 3)))
        assertFailsWith<ProtocolException> { owner.prepare(bad) }
        assertSame(prepared.state, owner.current)
        val later = f.commit(store, emptyList(), observed = 21)
        val headerOnly = owner.prepare(later)
        assertEquals(owner.current.pageCount, headerOnly.state.sharedPagesWith(owner.current))
        assertNotEquals(owner.current.digest(), headerOnly.state.digest(), "Actual header fields are hashed even when every page is shared")
    }

    @Test fun `oversize root uses one exact page and repeated edits or retention leave no dead records in the candidate`() {
        val store = source(5)
        val rootBlob = f.bytes(*IntArray(2048) { it and 255 })
        val big = f.commit(store, listOf(
            TimelineOperation.Declare(0, 65536, TimelineMode.STEP, TimelineValueType.BLOB, false),
            f.step(0, 65536, TimelineValue.Blob(rootBlob))), observed = 20)
        val owner = TimelinePackedLibrary.Owner(big, 128)
        val initial = owner.current
        assertEquals(big.objects.size, initial.pageCount)
        repeat(24) { index ->
            val revision = store.current!!.objects.getValue(1).curves.getValue(3).curveRevision
            val next = f.commit(store, listOf(f.step(1, 3, TimelineValue.SInt(40L + index), 10, revision = revision)), observed = 20)
            val current = owner.prepare(next).commit()
            assertContentEquals(canonical(next), canonical(current))
            assertTrue(current.pageCount <= current.objectCount)
            val table = ByteArrayOutputStream().also { current.writeObjectTable(it) }.size()
            assertEquals(table.toLong() - TimelineBinary.encodeVUInt(current.objectCount.toLong()).size, current.recordBytes)
        }
        val closed = f.commit(store, listOf(TimelineOperation.Close(1, 10, TimelineCloseKind.ENDED)), observed = 20, sealed = 20, control = 2)
        owner.prepare(closed).commit()
        val retained = f.commit(store, listOf(TimelineOperation.Retain(10)), observed = 21, sealed = 20, from = 10)
        val final = owner.prepare(retained).commit()
        assertTrue(initial.contains(1))
        assertFalse(final.contains(1))
        assertEquals(retained.objects.size, final.objectCount)
        assertContentEquals(canonical(retained), canonical(final))
        assertEquals(TimelineCanonical.digest(retained), final.digest())
    }

    @Test fun `input arrays and hostile output streams cannot retain or mutate record pages`() {
        val originalBytes = byteArrayOf(1, 2, 3)
        val callerSegments = arrayListOf<TimelineSegment>(TimelineSegment.Step(0, Long.MAX_VALUE, false,
            TimelineValue.Blob(TimelineBytes(originalBytes))))
        val original = source(1).current!!
        val extra = TimelineCurve(65536, TimelineMode.STEP, TimelineValueType.BLOB, false, segments = callerSegments)
        val callerCurves = LinkedHashMap(original.objects.getValue(0).curves).apply { put(65536, extra) }
        val callerObjects = LinkedHashMap(original.objects).apply { put(0, getValue(0).copy(curves = callerCurves)) }
        val input = original.copy(objects = callerObjects)
        val packed = TimelinePackedLibrary.pack(input, 128)
        val before = packed.digest()
        originalBytes.fill(0); callerSegments.clear(); callerCurves.clear(); callerObjects.clear()
        packed.writeCanonical(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) { bytes.fill(0) }
        })
        assertEquals(before, packed.digest())
        assertContentEquals(canonical(input), canonical(packed))
        // No typed library, object/curve graph, parent library, or digest is retained in this representation.
        assertFalse(TimelinePackedLibrary::class.java.declaredFields.any {
            it.type == TimelineState::class.java || it.type == TimelineObject::class.java || it.type == TimelineCurve::class.java ||
                it.type == TimelinePackedLibrary::class.java
        })
    }

    @Test fun `every scan validates physical record boundaries against the directory`() {
        fun damaged(field: String, change: (Any) -> Unit) {
            val packed = TimelinePackedLibrary.pack(source(2).current!!, 128)
            val target = TimelinePackedLibrary::class.java.getDeclaredField(field).apply { isAccessible = true }.get(packed)
            change(target)
            assertFailsWith<ProtocolException>(field) { packed.validate() }
            assertFailsWith<ProtocolException>(field) { packed.digest() }
        }
        damaged("handles") { (it as LongArray)[1] = 0 }
        damaged("offsets") { (it as IntArray)[1] = 1 }
        damaged("lengths") { (it as IntArray)[1]-- }
        damaged("objectPages") { (it as IntArray)[1] = Int.MAX_VALUE }
        damaged("pages") {
            @Suppress("UNCHECKED_CAST") val pages = it as Array<ByteArray>
            pages[pages.lastIndex] = pages.last().copyOf(pages.last().size + 1)
        }
    }

    @Test fun `full digest rereads actual record values rather than a cached body digest`() {
        val state = source(1).current!!
        val packed = TimelinePackedLibrary.pack(state, 128)
        val original = packed.digest()
        val cursor = assertNotNull(packed.objectCursor(0))
        cursor.objectHeader()
        assertEquals(1, cursor.curveHeader(60).attributeId)
        cursor.vuint(); cursor.vuint(); cursor.bool()
        val valueOffset = cursor.position
        @Suppress("UNCHECKED_CAST")
        val pages = TimelinePackedLibrary::class.java.getDeclaredField("pages").apply { isAccessible = true }.get(packed) as Array<ByteArray>
        val before = pages[0][valueOffset]
        assertEquals(0xc8.toByte(), before) // canonical ZigZag(100) = c8 01
        try {
            pages[0][valueOffset] = 0xca.toByte() // legal 101: corruption must change the next complete digest
            packed.validate()
            assertNotEquals(original, packed.digest())
            val value = packed.objectValue(0)!!.curves.getValue(1).segments.single() as TimelineSegment.Step
            assertEquals(TimelineValue.SInt(101), value.value)
        } finally { pages[0][valueOffset] = before }
        assertEquals(original, packed.digest())
    }
}
