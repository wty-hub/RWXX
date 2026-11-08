package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Insertion order is not part of canonical state; ordered traversal must retain every byte. */
class TimelineCanonicalOrderingTest {
    @Test fun `reverse and shuffled object and attribute insertion preserve complete canonical bytes`() {
        val original = state()
        val full = full(original)
        val persistent = persistent(original)
        val digest = TimelineCanonical.digest(original)
        val random = Random(917_432)
        val variants = listOf(reorder(original) { it.reversed() }) + List(64) {
            reorder(original) { values -> values.shuffled(random) }
        }
        for ((index, candidate) in variants.withIndex()) {
            assertContentEquals(full, full(candidate), "Complete canonical input differs for permutation $index")
            assertContentEquals(persistent, persistent(candidate), "Persistent canonical input differs for permutation $index")
            assertEquals(digest, TimelineCanonical.digest(candidate), "Digest differs for permutation $index")
            assertEquals(persistent.size.toLong(), TimelineCanonical.persistentSize(candidate), "Size differs for permutation $index")
        }
    }

    @Test fun `empty and singleton attribute maps preserve sorted and unsorted object traversal`() {
        val original = state()
        val simple = original.copy(objects = original.objects.mapValues { (_, obj) ->
            obj.copy(curves = if (obj.handle % 2L == 0L) emptyMap() else obj.curves.entries.take(1).associate { it.toPair() })
        })
        val reverse = reorder(simple) { it.reversed() }
        assertContentEquals(full(simple), full(reverse))
        assertEquals(TimelineCanonical.digest(simple), TimelineCanonical.digest(reverse))
        assertEquals(persistent(simple).size.toLong(), TimelineCanonical.persistentSize(reverse))
    }

    @Test fun `fast and fallback traversal both retain curve identity validation`() {
        val original = state()
        val obj = original.objects.getValue(1)
        val invalid = original.copy(objects = original.objects + (1L to obj.copy(curves = obj.curves +
            (7 to obj.curves.getValue(7).copy(attributeId = 8)))))
        for (candidate in listOf(invalid, reorder(invalid) { it.reversed() })) {
            assertFailsWith<ProtocolException> { full(candidate) }
            assertFailsWith<ProtocolException> { TimelineCanonical.digest(candidate) }
            assertFailsWith<ProtocolException> { TimelineCanonical.persistentSize(candidate) }
        }
    }

    private fun reorder(state: TimelineState, order: (List<Int>) -> List<Int>): TimelineState {
        val objects = state.objects.values.toList()
        val changed = order(objects.indices.toList()).associateTo(linkedMapOf()) { index ->
            val obj = objects[index]
            val curves = obj.curves.values.toList()
            obj.handle to obj.copy(curves = order(curves.indices.toList()).associateTo(linkedMapOf()) { at ->
                val curve = curves[at]; obj.curves.entries.first { it.value === curve }.key to curve
            })
        }
        return state.copy(objects = changed)
    }
    private fun full(state: TimelineState) = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
    private fun persistent(state: TimelineState) = ByteArrayOutputStream().also { TimelineCanonical.writePersistent(state, it) }.toByteArray()

    private fun state(): TimelineState {
        fun n(id: Int, value: Long) = TimelineField(id, true, TimelineTlvValue.U32(value))
        fun b(id: Int, vararg values: Byte) = TimelineField(id, true, TimelineTlvValue.Bytes(TimelineBytes(values)))
        val map = TimelineRecord(listOf(n(1, 1), n(2, 1), n(3, 1), n(4, 20), n(5, 20), b(6, 0),
            n(7, 1), b(8, 0, 1), b(9, 0, 0), b(10, 0)))
        val context = TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }),
            TimelineCanonical.mapKey(map), map, 0, 60)
        val header = TimelineHeader(1, 1, TimelineTransactionKind.RESET, 1, 0, 100, 50, 0, 200,
            1, 1, TimelineCanonical.sha256(TimelineBytes(byteArrayOf(0))), Long.MAX_VALUE)
        val root = TimelineObject(0, TimelineObjectKind.ROOT, 0, curves = mapOf(1 to
            TimelineCurve(1, TimelineMode.STEP, TimelineValueType.SINT, true, curveRevision = 1,
                segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.SInt(1024))))))
        fun unit(handle: Long): TimelineObject = TimelineObject(handle, TimelineObjectKind.UNIT, 0,
            TimelineRecord(listOf(TimelineField(1, false, TimelineTlvValue.Text("tank")))), curves = linkedMapOf(
                1 to TimelineCurve(1, TimelineMode.LINEAR, TimelineValueType.VEC3, true, curveRevision = 1,
                    segments = listOf(TimelineSegment.Interpolated(0, 100, false, 0, 100,
                        TimelineValue.Vec3(-1, 0, 0), TimelineValue.Vec3(100, 200, 10)))),
                7 to TimelineCurve(7, TimelineMode.STEP, TimelineValueType.BLOB, true, curveRevision = 2,
                    segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.Blob(TimelineBytes("tank".toByteArray()))))),
                8 to TimelineCurve(8, TimelineMode.STEP, TimelineValueType.UINT, true, curveRevision = 3,
                    segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.UInt(1)))),
                Int.MAX_VALUE to TimelineCurve(Int.MAX_VALUE, TimelineMode.PULSE, TimelineValueType.BLOB, false, 4,
                    TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(100)),
                        TimelineField(9, false, TimelineTlvValue.Opaque(240, TimelineBytes(byteArrayOf(1, 2)))))),
                    curveRevision = 2, events = listOf(TimelinePulse(20, 2, TimelineValue.Blob(TimelineBytes(byteArrayOf(1)))),
                        TimelinePulse(50, 3, TimelineValue.Blob(TimelineBytes(byteArrayOf(2))))))
            ))
        return TimelineState(context, header, linkedMapOf(0L to root, 1L to unit(1), 4L to unit(4),
            128L to unit(128), 65536L to unit(65536), Long.MAX_VALUE to unit(Long.MAX_VALUE)))
    }
}
