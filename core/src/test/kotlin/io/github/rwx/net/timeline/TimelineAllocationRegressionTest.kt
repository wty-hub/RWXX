package io.github.rwx.net.timeline

import java.io.OutputStream
import kotlin.random.Random
import kotlin.test.*

/** Allocation shortcuts must preserve the byte grammar and the transaction trust boundary. */
class TimelineAllocationRegressionTest {
    private val f = TimelineTestFixture

    @Test fun `field lookup retains first match before record validation`() {
        val first = TimelineField(7, false, TimelineTlvValue.Text("first"))
        val duplicate = TimelineField(7, false, TimelineTlvValue.Text("second"))
        val low = TimelineField(1, true, TimelineTlvValue.U32(3))
        val fields = mutableListOf(first, low, duplicate)
        val record = TimelineRecord(fields)
        fields.clear()
        assertSame(first, record[7])
        assertSame(low, record[1])
        assertNull(record[2])
        assertNull(TimelineRecord.EMPTY[1])
        assertSame(first, TimelineRecord(listOf(first))[7])
        assertFailsWith<ProtocolException> { TimelineBinary.validateRecord(record) }
        val ordered = TimelineRecord(listOf(low, first))
        assertEquals(ordered, TimelineBinary.decodeRecord(TimelineBinary.encodeRecord(ordered)))
        assertSame(first, ordered[7])
    }

    @Test fun `packed sizes match actual writer at every byte boundary and random 63bit values`() {
        val unsigned = linkedSetOf(0L, 1L, 127L, 128L, Long.MAX_VALUE)
        for (shift in 7..56 step 7) {
            val boundary = 1L shl shift
            unsigned.add(boundary - 1); unsigned.add(boundary); unsigned.add(boundary + 1)
        }
        val signed = linkedSetOf(TimelineProtocol.MIN_SINT, TimelineProtocol.MIN_SINT + 1,
            -1L, 0L, 1L, TimelineProtocol.MAX_SINT - 1, TimelineProtocol.MAX_SINT)
        for (shift in 6..55 step 7) {
            val boundary = 1L shl shift
            for (delta in -1L..1L) { signed.add(boundary + delta); signed.add(-boundary + delta) }
        }
        val random = Random(419_731)
        repeat(10_000) {
            unsigned.add(random.nextLong() ushr 1)
            signed.add(random.nextLong() shr 1)
        }
        for (value in unsigned) assertEquals(count { TimelineBinary.writeVUInt(it, value) },
            TimelineEncoder.varSize(value), "Unsigned size for $value")
        for (value in signed) assertEquals(count { TimelineBinary.writeVSInt(it, value) },
            TimelineEncoder.signedSize(value), "Signed size for $value")
    }

    @Test fun `packed size calculation rejects the same out of range values as encoding`() {
        for (value in listOf(-1L, Long.MIN_VALUE)) {
            assertFailsWith<ProtocolException> { TimelineEncoder.varSize(value) }
            assertFailsWith<ProtocolException> { count { TimelineBinary.writeVUInt(it, value) } }
        }
        for (value in listOf(TimelineProtocol.MIN_SINT - 1, TimelineProtocol.MAX_SINT + 1,
            Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertFailsWith<ProtocolException> { TimelineEncoder.signedSize(value) }
            assertFailsWith<ProtocolException> { count { TimelineBinary.writeVSInt(it, value) } }
        }
    }

    @Test fun `shared curves still publish control and watermarks through the real store`() {
        val peer = peer()
        val before = peer.store.current!!
        val desired = before.copy(header = before.header.copy(observedThrough = 11, sealedThrough = 6,
            retainedUntil = 12, controlEpoch = before.header.controlEpoch + 1))
        assertSame(before.objects.getValue(1).curves.getValue(3), desired.objects.getValue(1).curves.getValue(3))
        val publication = assertNotNull(peer.encoder.encode(peer.cursor(), desired))
        assertTrue(peer.operations(publication).isEmpty())
        val after = peer.store.apply(publication.header, publication.operations)
        assertEquals(11L, after.header.observedThrough)
        assertEquals(6L, after.header.sealedThrough)
        assertEquals(2L, after.header.controlEpoch)
        assertNotEquals(TimelineCanonical.digest(before), TimelineCanonical.digest(after))
        assertEquals(TimelineCanonical.persistentSize(before), TimelineCanonical.persistentSize(after))
    }

    @Test fun `shared curves do not skip object close or subsequent retention`() {
        val boundedUnit = f.unit(team = 1).map { operation ->
            if (operation is TimelineOperation.Splice) TimelineOperation.Splice(operation.handle,
                operation.attributeId, operation.expectedRevision, operation.from, 10,
                operation.segments.map { it.clipped(it.lo, 10) }) else operation
        }
        val peer = peer(f.root() + boundedUnit, observed = 9)
        val before = peer.store.current!!
        val oldUnit = before.objects.getValue(1)
        val desired = before.copy(header = before.header.copy(observedThrough = 10, sealedThrough = 10,
            retainedUntil = 11, controlEpoch = 2), objects = before.objects +
            (1L to oldUnit.copy(close = TimelineClose(10, TimelineCloseKind.ENDED))))
        for (id in 1..9) assertSame(oldUnit.curves.getValue(id), desired.objects.getValue(1).curves.getValue(id))
        TimelineProfile.validate(desired, before)
        val publication = assertNotNull(peer.encoder.encode(peer.cursor(), desired))
        assertEquals(listOf(TimelineOperation.Close(1, 10, TimelineCloseKind.ENDED)), peer.operations(publication))
        val closed = peer.store.apply(publication.header, publication.operations)
        assertNotNull(TimelineProfile.sample(closed, 1, 1, 9))
        assertNull(TimelineProfile.sample(closed, 1, 1, 10))

        val source = TimelineStore.fromCommitted(closed)
        val retained = f.commit(source, listOf(TimelineOperation.Retain(10)), observed = 11, sealed = 10,
            from = 10, control = 2)
        val retention = assertNotNull(peer.encoder.encode(peer.cursor(), retained))
        assertIs<TimelineOperation.Retain>(peer.operations(retention).first())
        val after = peer.store.apply(retention.header, retention.operations)
        assertEquals(setOf(0L), after.objects.keys)
        assertEquals(10L, after.header.retainedFrom)
        assertEquals(2L, after.header.controlEpoch)
    }

    @Test fun `identity shortcut cannot confirm a predicted interval or change attribute metadata`() {
        val forecast = TimelineOperation.Splice(1, 1, 1, 10, 20, listOf(TimelineSegment.Interpolated(10, 20, true,
            10, 20, TimelineValue.Vec3(256, 512, -1), TimelineValue.Vec3(512, 512, -1))))
        val peer = peer(f.root() + f.unit() + forecast, until = 20)
        val before = peer.store.current!!
        val invalidSeal = before.copy(header = before.header.copy(observedThrough = 15, sealedThrough = 15))
        val publication = assertNotNull(peer.encoder.encode(peer.cursor(), invalidSeal))
        assertTrue(peer.operations(publication).isEmpty())
        assertFailsWith<ProtocolException> { peer.store.prepare(publication.header, publication.operations) }
        assertSame(before, peer.store.current)

        val unit = before.objects.getValue(1)
        val changed = unit.curves.getValue(3).copy(metadata = TimelineRecord(listOf(
            TimelineField(9, false, TimelineTlvValue.Text("changed")))))
        val changedMetadata = before.copy(objects = before.objects + (1L to unit.copy(curves = unit.curves + (3 to changed))))
        assertFailsWith<ProtocolException> { peer.encoder.encode(peer.cursor(), changedMetadata) }
        assertSame(before, peer.store.current)
    }

    private fun count(write: (OutputStream) -> Unit): Long {
        var count = 0L
        write(object : OutputStream() { override fun write(value: Int) { count++ } })
        return count
    }

    private class Peer(val encoder: TimelineEncoder, val store: TimelineStore) {
        fun cursor() = TimelineSendCursor(store.context, 1, 1, store.current, store.current == null)
        fun operations(publication: TimelinePublication): List<TimelineOperation> {
            val formats = store.current!!.objects.values.flatMap { obj -> obj.curves.values.map {
                TimelineCurveKey(obj.handle, it.attributeId) to it.format
            } }.toMap()
            return TimelineBinary.decodeOperations(publication.operations, formats)
        }
    }

    private fun peer(operations: List<TimelineOperation> = f.root() + f.unit(), observed: Long = 10,
        until: Long = observed + 1): Peer {
        val source = TimelineStore(f.context())
        val desired = f.commit(source, operations, observed = observed, sealed = 5, until = until)
        val peer = Peer(TimelineEncoder(clock = { 0 }), TimelineStore(source.context))
        val first = assertNotNull(peer.encoder.encode(peer.cursor(), desired))
        peer.store.apply(first.header, first.operations)
        return peer
    }
}
