package io.github.rwx.net.timeline

import kotlin.test.*

/** A writer may poll a committed source hundreds of times between real publications. */
class TimelineEncoderPollingTest {
    private val f = TimelineTestFixture
    private class Clock(var now: Long = 0)
    private class Peer(val source: TimelineState, val clock: Clock = Clock()) {
        val encoder = TimelineEncoder(clock = { clock.now })
        val store = TimelineStore(source.context)
        fun cursor(previous: TimelineState? = store.current, reset: Boolean = previous == null,
                   context: TimelineContext = store.context, epoch: Long = if (reset) (previous?.header?.epoch ?: 0) + 1 else previous!!.header.epoch) =
            TimelineSendCursor(context, 7, epoch, previous, reset)
        fun initial(): TimelineState {
            val reset = assertNotNull(encoder.encode(cursor(), source))
            return store.apply(reset.header, reset.operations)
        }
        fun formats() = store.current!!.objects.values.flatMap { obj -> obj.curves.values.map {
            TimelineCurveKey(obj.handle, it.attributeId) to it.format
        } }.toMap()
    }

    private fun source(units: Int = 1): TimelineState {
        val store = TimelineStore(f.context())
        return f.commit(store, f.root() + (1..units).flatMap { f.unit(it.toLong()) }, sealed = 5,
            highWater = units.toLong())
    }

    @Test fun `polling large unchanged source after real commit never enters diff`() {
        val peer = Peer(source(1024))
        val committed = peer.initial()
        assertNotSame(peer.source, committed)
        assertNotSame(peer.source.objects.getValue(1).curves.getValue(3), committed.objects.getValue(1).curves.getValue(3))
        val digest = TimelineCanonical.digest(committed)
        repeat(500) {
            peer.clock.now = it * 1_000_000L
            assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        }
        assertEquals(0L, peer.encoder.diffPasses)
        assertSame(committed, peer.store.current)
        assertEquals(digest, TimelineCanonical.digest(committed))
    }

    @Test fun `LIVE heartbeat still emits decoded and validated UPDATE at one second`() {
        val peer = Peer(source())
        val before = peer.initial()
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        peer.clock.now = 999_999_999
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        assertEquals(0L, peer.encoder.diffPasses)
        peer.clock.now = 1_000_000_000
        val heartbeat = assertNotNull(peer.encoder.encode(peer.cursor(), peer.source))
        assertEquals(1L, peer.encoder.diffPasses)
        assertEquals(TimelineTransactionKind.UPDATE, heartbeat.header.kind)
        assertEquals(2L, heartbeat.header.revision)
        assertEquals(before.header.observedThrough, heartbeat.header.observedThrough)
        assertEquals(before.header.sealedThrough, heartbeat.header.sealedThrough)
        assertTrue(TimelineBinary.decodeOperations(heartbeat.operations, peer.formats()).isEmpty())
        val after = peer.store.apply(heartbeat.header, heartbeat.operations)
        assertNotEquals(TimelineCanonical.digest(before), TimelineCanonical.digest(after))
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        peer.clock.now = 1_999_999_999
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        assertEquals(1L, peer.encoder.diffPasses)
        peer.clock.now = 2_000_000_000
        assertNotNull(peer.encoder.encode(peer.cursor(), peer.source))
        assertEquals(2L, peer.encoder.diffPasses)
    }

    @Test fun `preparation without commit never installs polling identity cache`() {
        val peer = Peer(source())
        val first = assertNotNull(peer.encoder.encode(peer.cursor(), peer.source))
        val prepared = peer.store.prepare(first.header, first.operations)
        assertNull(peer.store.current)
        val repeatedReset = assertNotNull(peer.encoder.encode(peer.cursor(), peer.source))
        assertEquals(first, repeatedReset)
        prepared.commit()
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))

        val changed = peer.source.copy(header = peer.source.header.copy(observedThrough = 11, retainedUntil = 12, controlEpoch = 2))
        val next = assertNotNull(peer.encoder.encode(peer.cursor(), changed))
        val notCommitted = peer.store.prepare(next.header, next.operations)
        val passes = peer.encoder.diffPasses
        val repeatedUpdate = assertNotNull(peer.encoder.encode(peer.cursor(), changed))
        assertEquals(passes + 1, peer.encoder.diffPasses)
        assertEquals(next, repeatedUpdate)
        notCommitted.commit()
        assertNull(peer.encoder.encode(peer.cursor(), changed))
        assertEquals(passes + 1, peer.encoder.diffPasses)
        assertEquals(2L, peer.store.current!!.header.controlEpoch)
    }

    @Test fun `equal source or cursor copies still traverse diff instead of trusting headers`() {
        val peer = Peer(source())
        val committed = peer.initial()
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        val sourceCopy = peer.source.copy()
        assertEquals(peer.source, sourceCopy)
        assertNotSame(peer.source, sourceCopy)
        assertNull(peer.encoder.encode(peer.cursor(), sourceCopy))
        assertEquals(1L, peer.encoder.diffPasses)
        assertNull(peer.encoder.encode(peer.cursor(), sourceCopy))
        assertEquals(2L, peer.encoder.diffPasses, "An uncommitted equivalent source cannot install the cache")
        val cursorCopy = committed.copy()
        assertEquals(committed, cursorCopy)
        assertNull(peer.encoder.encode(peer.cursor(previous = cursorCopy), peer.source))
        assertEquals(3L, peer.encoder.diffPasses)
        assertNull(peer.encoder.encode(peer.cursor(context = committed.context.copy()), peer.source))
        assertEquals(4L, peer.encoder.diffPasses)
        assertNull(peer.encoder.encode(peer.cursor(epoch = committed.header.epoch + 1), peer.source))
        assertEquals(5L, peer.encoder.diffPasses)
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        assertEquals(5L, peer.encoder.diffPasses)
    }

    @Test fun `changed source content with the same header is diffed and reaches validation`() {
        val peer = Peer(source())
        peer.initial()
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        val unit = peer.source.objects.getValue(1)
        val health = unit.curves.getValue(3).copy(segments = listOf(
            TimelineSegment.Step(0, 5, false, TimelineValue.SInt(80)),
            TimelineSegment.Step(5, Long.MAX_VALUE, false, TimelineValue.SInt(40))))
        val changed = peer.source.copy(objects = peer.source.objects + (1L to unit.copy(curves = unit.curves + (3 to health))))
        assertEquals(peer.source.header, changed.header)
        val publication = assertNotNull(peer.encoder.encode(peer.cursor(), changed))
        assertEquals(1L, peer.encoder.diffPasses)
        val operations = TimelineBinary.decodeOperations(publication.operations, peer.formats())
        assertEquals(1, operations.filterIsInstance<TimelineOperation.Splice>().size)
        val after = peer.store.apply(publication.header, publication.operations)
        assertEquals(TimelineValue.SInt(80), TimelineProfile.sample(after, 1, 3, 4))
        assertEquals(TimelineValue.SInt(40), TimelineProfile.sample(after, 1, 3, 10))
    }

    @Test fun `recovery RESET bypasses polling cache and revokes controls`() {
        val peer = Peer(source())
        val before = peer.initial()
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        val reset = assertNotNull(peer.encoder.encode(peer.cursor(reset = true), peer.source))
        assertEquals(TimelineTransactionKind.RESET, reset.header.kind)
        assertEquals(before.header.epoch + 1, reset.header.epoch)
        assertEquals(before.header.controlEpoch + 1, reset.header.controlEpoch)
        val after = peer.store.apply(reset.header, reset.operations)
        assertEquals(1L, after.header.revision)
        assertNull(peer.encoder.encode(peer.cursor(), peer.source))
        assertEquals(0L, peer.encoder.diffPasses)
        val foreign = peer.source.copy(context = peer.source.context.copy(teamId = 1))
        assertFailsWith<ProtocolException> { peer.encoder.encode(peer.cursor(), foreign) }
    }

    @Test fun `pending equal revision with different prepared header is not confirmed`() {
        val peer = Peer(source())
        val first = assertNotNull(peer.encoder.encode(peer.cursor(), peer.source))
        val newer = peer.source.copy(header = peer.source.header.copy(observedThrough = 11, retainedUntil = 12))
        val abandoned = assertNotNull(peer.encoder.encode(peer.cursor(), newer))
        assertEquals(first.header.epoch, abandoned.header.epoch)
        assertEquals(first.header.revision, abandoned.header.revision)
        assertNotEquals(first.header, abandoned.header)
        peer.store.apply(first.header, first.operations)
        assertNotNull(peer.encoder.encode(peer.cursor(), newer))
        assertEquals(1L, peer.encoder.diffPasses)
    }

    @Test fun `committed HISTORY source has no periodic heartbeat or repeated diff`() {
        val state = source()
        val history = state.copy(context = state.context.copy(mode = TimelineStreamMode.HISTORY, acceptedFrom = 0, acceptedUntil = 10),
            header = state.header.copy(sealedThrough = 10, retainedUntil = 10, controlEpoch = 0))
        val peer = Peer(history)
        peer.initial()
        assertNull(peer.encoder.encode(peer.cursor(), history))
        for (second in listOf(1L, 10L, 3600L)) {
            peer.clock.now = second * 1_000_000_000
            assertNull(peer.encoder.encode(peer.cursor(), history))
        }
        assertEquals(0L, peer.encoder.diffPasses)
        assertEquals(0L, peer.store.current!!.header.controlEpoch)
    }
}
