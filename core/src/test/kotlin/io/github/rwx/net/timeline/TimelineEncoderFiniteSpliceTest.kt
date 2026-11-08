package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

class TimelineEncoderFiniteSpliceTest {
    private val f = TimelineTestFixture
    private val forever = TimelineProtocol.FOREVER
    private val optional = 65536

    private class Peer(context: TimelineContext, initial: TimelineState) {
        val encoder = TimelineEncoder(clock = { 0L })
        val store = TimelineStore(context)
        init {
            val reset = checkNotNull(encoder.encode(TimelineSendCursor(context, 1, 1, null, true), initial))
            store.apply(reset.header, reset.operations)
        }
        fun cursor() = TimelineSendCursor(store.context, 1, 1, store.current, false)
    }

    private fun point(time: Long, delta: Long = 0) = TimelineValue.Vec3(256 + time * 4 + delta, 512, -1)
    private fun line(lo: Long, hi: Long, predicted: Boolean = false, delta: Long = 0) =
        TimelineSegment.Interpolated(lo, hi, predicted, lo, hi, point(lo, delta), point(hi, delta))
    private fun unsigned(lo: Long, hi: Long, delta: Long = 0) = TimelineSegment.Interpolated(lo, hi, false, lo, hi,
        TimelineValue.UInt(lo + delta), TimelineValue.UInt(hi + delta))
    private fun finiteSource(end: Long = 20, seal: Long = 5): TimelineStore = TimelineStore(f.context()).also { source ->
        f.commit(source, f.root() + f.unit() + TimelineOperation.Splice(1, 1, 1, 0, forever,
            listOf(line(0, 10), line(10, end, true))), observed = 10, sealed = seal, until = 10 + 60L * 1024 + 1)
    }
    private fun optionalSource(segments: List<TimelineSegment>): TimelineStore = TimelineStore(f.context()).also { source ->
        f.commit(source, f.root() + f.unit() + listOf(
            TimelineOperation.Declare(0, optional, TimelineMode.LINEAR, TimelineValueType.UINT, false),
            TimelineOperation.Splice(0, optional, 0, 0, forever, segments)), observed = 20, sealed = 5)
    }
    private fun revise(source: TimelineStore, segments: List<TimelineSegment>, handle: Long = 1, attribute: Int = 1,
                       seal: Long = source.current!!.header.sealedThrough, retain: Long = 0): TimelineState {
        val before = checkNotNull(source.current)
        val edits = buildList<TimelineOperation> {
            if (retain > before.header.retainedFrom) add(TimelineOperation.Retain(retain))
            add(TimelineOperation.Splice(handle, attribute, before.objects.getValue(handle).curves.getValue(attribute).curveRevision,
                seal, forever, segments))
        }
        return f.commit(source, edits, observed = before.header.observedThrough + 1, sealed = seal, from = retain, until = before.header.observedThrough + 60L * 1024 + 2)
    }
    private fun canonical(state: TimelineState) = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
    private fun decode(publication: TimelinePublication, base: TimelineState) = TimelineBinary.decodeOperationsFromState(publication.operations, base)
    private fun reheader(header: TimelineHeader, bytes: TimelineBytes) = header.copy(operationBytes = bytes.size.toLong(), operationSha256 = TimelineCanonical.sha256(bytes))

    /** The old encoder's only changed field is until=FOREVER. Apply that exact reference. */
    private fun compare(peer: Peer, desired: TimelineState, expectedUntil: Long): TimelineState {
        val base = checkNotNull(peer.store.current)
        val publication = assertNotNull(peer.encoder.encode(peer.cursor(), desired))
        val operations = decode(publication, base)
        val splice = operations.filterIsInstance<TimelineOperation.Splice>().single()
        assertEquals(maxOf(base.header.sealedThrough, desired.header.retainedFrom), splice.from)
        assertEquals(expectedUntil, splice.until)
        val referenceOps = operations.map { if (it is TimelineOperation.Splice)
            TimelineOperation.Splice(it.handle, it.attributeId, it.expectedRevision, it.from, forever, it.segments) else it }
        val referenceBytes = TimelineBinary.encodeOperationsFromState(referenceOps, base)
        val referenceStore = TimelineStore.fromCommitted(base)
        val reference = referenceStore.apply(reheader(publication.header, referenceBytes), referenceBytes)
        val prepared = peer.store.prepare(publication.header, publication.operations)
        assertSame(base, peer.store.current, "Preparation must not publish")
        assertEquals(reference.objects, prepared.state.objects, "Every object, raw segment and curveRevision must match")
        assertContentEquals(canonical(reference), canonical(prepared.state))
        assertEquals(TimelineCanonical.digest(reference), TimelineCanonical.digest(prepared.state))
        val expectedSaving = f.compact(forever).size - f.compact(expectedUntil).size
        assertEquals(expectedSaving, referenceBytes.size - publication.operations.size)
        prepared.commit()
        return prepared.state
    }

    @Test fun `two finite prediction tails bound the edit at the later new tail`() {
        val source = finiteSource()
        val peer = Peer(source.context, source.current!!)
        val desired = revise(source, listOf(line(5, 10, delta = 32), line(10, 25, true, 32)))
        val received = compare(peer, desired, 25)
        val splicePrefix = received.objects.getValue(1).curves.getValue(1).segments.first()
        assertEquals(line(0, 10).copy(hi = 5), splicePrefix)
        assertEquals(2L, received.objects.getValue(1).curves.getValue(1).curveRevision)
    }

    @Test fun `a shorter new tail deletes the complete remaining old prediction`() {
        val source = finiteSource()
        val peer = Peer(source.context, source.current!!)
        val desired = revise(source, listOf(line(5, 10, delta = 16), line(10, 15, true, 16)))
        val received = compare(peer, desired, 20)
        assertNotNull(TimelineProfile.sample(received, 1, 1, 14))
        assertNull(TimelineProfile.sample(received, 1, 1, 15))
        assertNull(TimelineProfile.sample(received, 1, 1, 19))
    }

    @Test fun `finite replacement preserves gaps without a hold or a leftover island`() {
        val source = optionalSource(listOf(unsigned(0, 7), unsigned(12, 20)))
        val peer = Peer(source.context, source.current!!)
        val desired = revise(source, listOf(unsigned(5, 7), unsigned(14, 18, 3)), 0, optional)
        val received = compare(peer, desired, 20)
        assertNull(TimelineProfile.sample(received, 0, optional, 10))
        assertNull(TimelineProfile.sample(received, 0, optional, 19))
        assertNotNull(TimelineProfile.sample(received, 0, optional, 16))
    }

    @Test fun `empty replacement removes the old suffix and keeps an empty declaration`() {
        val source = optionalSource(listOf(unsigned(0, 7), unsigned(12, 20)))
        val peer = Peer(source.context, source.current!!)
        val desired = revise(source, emptyList(), 0, optional)
        val received = compare(peer, desired, 20)
        assertNull(TimelineProfile.sample(received, 0, optional, 5))
        assertEquals(listOf(unsigned(0, 7).copy(hi = 5)), received.objects.getValue(0).curves.getValue(optional).segments)
    }

    @Test fun `an initially empty curve can acquire a finite suffix while equal empties emit no splice`() {
        val source = optionalSource(emptyList())
        val peer = Peer(source.context, source.current!!)
        val desired = revise(source, listOf(unsigned(5, 12)), 0, optional)
        compare(peer, desired, 12)
        val emptySource = optionalSource(emptyList())
        val emptyPeer = Peer(emptySource.context, emptySource.current!!)
        val advance = f.commit(emptySource, emptyList(), observed = 21, sealed = 5)
        val publication = assertNotNull(emptyPeer.encoder.encode(emptyPeer.cursor(), advance))
        assertTrue(decode(publication, emptyPeer.store.current!!).isEmpty())
    }

    @Test fun `an infinite old or new constant tail keeps FOREVER unchanged`() {
        val infinite = TimelineStore(f.context()).also { f.commit(it, f.root() + f.unit(), sealed = 5) }
        val firstPeer = Peer(infinite.context, infinite.current!!)
        compare(firstPeer, revise(infinite, listOf(line(5, 10), line(10, 20, true))), forever)
        val finite = finiteSource()
        val secondPeer = Peer(finite.context, finite.current!!)
        val value = point(5)
        compare(secondPeer, revise(finite, listOf(TimelineSegment.Interpolated(5, forever, false, 5, forever, value, value))), forever)
    }

    @Test fun `Retain is applied before bounding the suffix and an edit can start at old seal`() {
        val source = finiteSource(seal = 8)
        val peer = Peer(source.context, source.current!!)
        val desired = revise(source, listOf(line(8, 10, delta = 24), line(10, 25, true, 24)), seal = 8, retain = 6)
        val received = compare(peer, desired, 25)
        assertEquals(6L, received.header.retainedFrom)
        assertEquals(8L, received.header.sealedThrough)
        val prefix = received.objects.getValue(1).curves.getValue(1).segments.first()
        assertEquals(line(0, 10).copy(lo = 6, hi = 8), prefix)
    }

    @Test fun `a later failed operation cannot publish finite tail removal or consume the encoder base`() {
        val source = finiteSource()
        val peer = Peer(source.context, source.current!!)
        val desired = revise(source, listOf(line(5, 10, delta = 16), line(10, 15, true, 16)))
        val before = peer.store.current!!
        val beforeBytes = canonical(before)
        val publication = assertNotNull(peer.encoder.encode(peer.cursor(), desired))
        val invalid = decode(publication, before) + TimelineOperation.Close(999, 11, TimelineCloseKind.ENDED)
        val invalidBytes = TimelineBinary.encodeOperationsFromState(invalid, before)
        assertFailsWith<ProtocolException> { peer.store.apply(reheader(publication.header, invalidBytes), invalidBytes) }
        assertSame(before, peer.store.current)
        assertContentEquals(beforeBytes, canonical(peer.store.current!!))
        assertNotNull(TimelineProfile.sample(before, 1, 1, 19))
        compare(peer, desired, 20)
        assertNull(peer.encoder.encode(peer.cursor(), desired))
    }
}
