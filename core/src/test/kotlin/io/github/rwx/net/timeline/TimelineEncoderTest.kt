package io.github.rwx.net.timeline

import kotlin.test.*

class TimelineEncoderTest {
    private val f = TimelineTestFixture

    @Test fun `frozen live observation sends heartbeat without inventing simulation time`() {
        var now = 0L
        val encoder = TimelineEncoder(clock = { now })
        val desired = source().current!!
        val store = TimelineStore(desired.context)
        fun cursor() = TimelineSendCursor(desired.context, 1, 1, store.current, store.current == null)
        val first = assertNotNull(encoder.encode(cursor(), desired))
        store.apply(first.header, first.operations)
        assertNull(encoder.encode(cursor(), desired))
        now = 999_999_999L
        assertNull(encoder.encode(cursor(), desired))
        now = 1_000_000_000L
        val heartbeat = assertNotNull(encoder.encode(cursor(), desired))
        assertEquals(desired.header.observedThrough, heartbeat.header.observedThrough)
        assertEquals(desired.header.sealedThrough, heartbeat.header.sealedThrough)
        assertEquals(2L, heartbeat.header.revision)
        assertTrue(TimelineBinary.decodeOperations(heartbeat.operations, emptyMap()).isEmpty())
        store.apply(heartbeat.header, heartbeat.operations)
        assertNull(encoder.encode(cursor(), desired))
        now += 1_000_000_000L
        assertNotNull(encoder.encode(cursor(), desired))
    }

    private class Peer(val context: TimelineContext) {
        val encoder = TimelineEncoder()
        val store = TimelineStore(context)
        fun cursor(reset: Boolean = false): TimelineSendCursor {
            val previous = store.current
            val resetting = reset || previous == null
            return TimelineSendCursor(context, 37, if (resetting) (previous?.header?.epoch ?: 0) + 1 else previous!!.header.epoch,
                previous, resetting)
        }
        fun encode(source: TimelineState, reset: Boolean = false): TimelinePublication? = encoder.encode(cursor(reset), source)
        fun deliver(source: TimelineState, reset: Boolean = false): TimelineState {
            val publication = assertNotNull(encode(source, reset))
            val prepared = store.prepare(publication.header, publication.operations)
            prepared.commit()
            return prepared.state
        }
        fun read(publication: TimelinePublication): List<TimelineOperation> {
            val formats = if (publication.header.kind == TimelineTransactionKind.RESET) emptyMap() else
                store.current!!.objects.values.flatMap { obj -> obj.curves.values.map {
                    TimelineCurveKey(obj.handle, it.attributeId) to it.format
                } }.toMap()
            return TimelineBinary.decodeOperations(publication.operations, formats)
        }
    }

    private fun source(ops: List<TimelineOperation> = f.root() + f.unit(), observed: Long = 10,
                       sealed: Long = 0, control: Long = 1, highWater: Long = 1): TimelineStore =
        TimelineStore(f.context()).also { f.commit(it, ops, observed = observed, sealed = sealed, control = control, highWater = highWater) }

    private fun tail() = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(60000))))
    private fun pulseObject(highWater: Long = 0): List<TimelineOperation> = listOf(
        TimelineOperation.Create(1, TimelineObjectKind.VISUAL, 0),
        TimelineOperation.Declare(1, 65536, TimelineMode.PULSE, TimelineValueType.UINT, false, highWater, tail()))

    private fun assertUnitSamples(source: TimelineState, received: TimelineState, handle: Long = 1) {
        val from = maxOf(source.header.retainedFrom, received.header.retainedFrom)
        val until = minOf(source.header.observedThrough + 1, source.header.retainedUntil, received.header.retainedUntil)
        for (time in from until until) for (attribute in 1..9)
            assertEquals(TimelineProfile.sample(source, handle, attribute, time), TimelineProfile.sample(received, handle, attribute, time),
                "sample differs at time=$time attribute=$attribute")
    }

    @Test fun `initial RESET uses peer stream and renumbers source curve revisions`() {
        val source = source()
        f.commit(source, listOf(f.step(1, 3, TimelineValue.SInt(70), 5, revision = 1)), observed = 11)
        val desired = f.commit(source, listOf(f.step(1, 3, TimelineValue.SInt(60), 7, revision = 2)), observed = 12)
        assertEquals(3L, desired.objects.getValue(1).curves.getValue(3).curveRevision)
        val peer = Peer(desired.context)
        val received = peer.deliver(desired)
        assertEquals(37L, received.header.streamId)
        assertEquals(1L, received.header.epoch)
        assertEquals(1L, received.header.revision)
        assertEquals(1L, received.objects.getValue(1).curves.getValue(3).curveRevision)
        assertUnitSamples(desired, received)
        val correction = f.commit(source, listOf(f.step(1, 3, TimelineValue.SInt(40), 9, revision = 3)), observed = 13)
        val publication = assertNotNull(peer.encode(correction))
        val splice = peer.read(publication).filterIsInstance<TimelineOperation.Splice>().single()
        assertEquals(1L, splice.expectedRevision)
        val updated = peer.store.apply(publication.header, publication.operations)
        assertEquals(2L, updated.objects.getValue(1).curves.getValue(3).curveRevision)
        assertUnitSamples(correction, updated)
    }

    @Test fun `unchanged functions are suppressed but advancing watermarks still sends empty UPDATE`() {
        val source = source()
        val peer = Peer(source.context)
        val initial = peer.deliver(source.current!!)
        assertNull(peer.encode(source.current!!))
        val fragment = TimelineOperation.Splice(1, 3, 1, 3, 6,
            listOf(TimelineSegment.Step(3, 6, false, TimelineValue.SInt(80))))
        val fragmented = f.commit(source, listOf(fragment))
        assertEquals(3, fragmented.objects.getValue(1).curves.getValue(3).segments.size)
        assertNull(peer.encode(fragmented))
        assertSame(initial, peer.store.current)
        assertEquals(1, initial.objects.getValue(1).curves.getValue(3).segments.size)
        val advanced = f.commit(source, emptyList(), observed = 11, sealed = 5)
        val publication = assertNotNull(peer.encode(advanced))
        assertEquals(TimelineTransactionKind.UPDATE, publication.header.kind)
        assertTrue(peer.read(publication).isEmpty())
        val received = peer.store.apply(publication.header, publication.operations)
        assertEquals(2L, received.header.revision)
        assertEquals(initial.objects, received.objects)
        assertNotEquals(TimelineCanonical.digest(initial), TimelineCanonical.digest(received))
    }

    @Test fun `correction preserves previously sent sealed anchor bytes and samples`() {
        val moving = TimelineOperation.Splice(1, 1, 1, 0, 10,
            listOf(TimelineSegment.Interpolated(0, 10, false, 0, 10, TimelineValue.Vec3(0, 0, 0), TimelineValue.Vec3(2560, 0, 0))))
        val endpoint = f.constant(1, 1, TimelineValue.Vec3(2560, 0, 0), 10, revision = 2)
        val source = source(f.root() + f.unit() + moving + endpoint, sealed = 5)
        val peer = Peer(source.context)
        val initial = peer.deliver(source.current!!)
        val original = initial.objects.getValue(1).curves.getValue(1).segments.first() as TimelineSegment.Interpolated
        val desired = f.commit(source, listOf(f.constant(1, 1, TimelineValue.Vec3(1280, 0, 0), 5, revision = 3)), sealed = 5)
        val received = peer.deliver(desired)
        val retained = received.objects.getValue(1).curves.getValue(1).segments.first() as TimelineSegment.Interpolated
        assertEquals(original.copy(hi = 5), retained)
        for (time in 0L..4L) assertEquals(TimelineProfile.sample(initial, 1, 1, time), TimelineProfile.sample(received, 1, 1, time))
        assertUnitSamples(desired, received)
        val next = f.commit(source, emptyList(), observed = 11, sealed = 6)
        val publication = assertNotNull(peer.encode(next))
        assertTrue(peer.read(publication).isEmpty())
    }

    @Test fun `prediction confirmation is sent before new sealed watermark`() {
        val forecast = TimelineOperation.Splice(1, 1, 1, 10, 20, listOf(TimelineSegment.Interpolated(10, 20, true,
            10, 20, TimelineValue.Vec3(256, 512, 0), TimelineValue.Vec3(512, 512, 0))))
        val source = TimelineStore(f.context())
        f.commit(source, f.root() + f.unit() + forecast, observed = 10, sealed = 5, until = 20)
        val peer = Peer(source.context)
        peer.deliver(source.current!!)
        val confirmation = TimelineOperation.Splice(1, 1, 2, 10, 20, listOf(TimelineSegment.Interpolated(10, 20, false,
            10, 20, TimelineValue.Vec3(256, 512, 0), TimelineValue.Vec3(512, 512, 0))))
        val desired = f.commit(source, listOf(confirmation), observed = 20, sealed = 20)
        val publication = assertNotNull(peer.encode(desired))
        assertTrue(peer.read(publication).any { it is TimelineOperation.Splice && it.handle == 1L && it.attributeId == 1 })
        val received = peer.store.apply(publication.header, publication.operations)
        assertEquals(20L, received.header.sealedThrough)
        assertFalse(received.objects.getValue(1).curves.getValue(1).segments.any { it.predicted })
    }

    @Test fun `closure and RetainFrom collect old identities without implicit deletion`() {
        val source = source(f.root() + f.unit(team = 1))
        val peer = Peer(source.context)
        peer.deliver(source.current!!)
        val closed = f.commit(source, listOf(TimelineOperation.Close(1, 4, TimelineCloseKind.ENDED)), sealed = 10, control = 2)
        val receivedClosed = peer.deliver(closed)
        assertNotNull(TimelineProfile.sample(receivedClosed, 1, 1, 3))
        assertNull(TimelineProfile.sample(receivedClosed, 1, 1, 4))
        val desired = f.commit(source, listOf(TimelineOperation.Retain(4)), observed = 11, sealed = 10, from = 4)
        val publication = assertNotNull(peer.encode(desired))
        val operations = peer.read(publication)
        assertIs<TimelineOperation.Retain>(operations.first())
        assertEquals(1, operations.count { it is TimelineOperation.Retain })
        val collected = peer.store.apply(publication.header, publication.operations)
        assertEquals(setOf(0L), collected.objects.keys)
        assertEquals(1L, collected.header.objectHighWater)
    }

    @Test fun `observation that begins and closes between publications is not dropped`() {
        val source = source(f.root(), highWater = 0)
        val peer = Peer(source.context)
        peer.deliver(source.current!!)
        val desired = f.commit(source, f.unit(1, from = 5, team = 1) + TimelineOperation.Close(1, 6, TimelineCloseKind.ENDED),
            sealed = 10, highWater = 1, control = 2)
        val publication = assertNotNull(peer.encode(desired))
        val ops = peer.read(publication)
        assertTrue(ops.any { it is TimelineOperation.Create && it.handle == 1L })
        assertTrue(ops.any { it is TimelineOperation.Close && it.handle == 1L })
        val received = peer.store.apply(publication.header, publication.operations)
        assertNull(TimelineProfile.sample(received, 1, 1, 4))
        assertNotNull(TimelineProfile.sample(received, 1, 1, 5))
        assertNull(TimelineProfile.sample(received, 1, 1, 6))
        assertEquals(TimelineCloseKind.ENDED, received.objects.getValue(1).close!!.kind)
    }

    @Test fun `slow peer whose sealed base was overtaken must receive a new checkpoint`() {
        val source = source()
        val peer = Peer(source.context)
        val before = peer.deliver(source.current!!)
        f.commit(source, emptyList(), observed = 20, sealed = 20)
        val desired = f.commit(source, listOf(TimelineOperation.Retain(10)), observed = 21, sealed = 20, from = 10)
        assertFailsWith<TimelineResetRequired> { peer.encode(desired) }
        assertSame(before, peer.store.current)
        val received = peer.deliver(desired, reset = true)
        assertEquals(2L, received.header.epoch)
        assertEquals(1L, received.header.revision)
        assertEquals(2L, received.header.controlEpoch)
        assertNull(TimelineProfile.sample(received, 1, 1, 9))
        assertNotNull(TimelineProfile.sample(received, 1, 1, 10))
    }

    @Test fun `uncommitted publication does not advance source control accounting`() {
        val source = source(control = 10)
        val peer = Peer(source.context)
        peer.deliver(source.current!!)
        val eleven = f.commit(source, emptyList(), observed = 11, control = 11)
        val abandoned = assertNotNull(peer.encode(eleven))
        assertEquals(11L, abandoned.header.controlEpoch)
        val twelve = f.commit(source, emptyList(), observed = 12, control = 12)
        val publication = assertNotNull(peer.encode(twelve))
        assertEquals(2L, publication.header.revision)
        assertEquals(12L, publication.header.controlEpoch)
        peer.store.apply(publication.header, publication.operations)
        val thirteen = f.commit(source, emptyList(), observed = 13, control = 12)
        val subsequent = assertNotNull(peer.encode(thirteen))
        assertEquals(12L, subsequent.header.controlEpoch)
        peer.store.apply(subsequent.header, subsequent.operations)
    }

    @Test fun `recovery control offset is preserved when source capability generation advances`() {
        val source = source(control = 40)
        val peer = Peer(source.context)
        peer.deliver(source.current!!)
        val reset = peer.deliver(source.current!!, reset = true)
        assertEquals(41L, reset.header.controlEpoch)
        val desired = f.commit(source, listOf(f.step(0, 3, TimelineValue.Blob(f.compact(1, 1)), 10, revision = 1)),
            observed = 11, control = 44)
        val received = peer.deliver(desired)
        assertEquals(45L, received.header.controlEpoch)
        assertEquals(TimelineValue.Blob(f.compact(1, 1)), TimelineProfile.sample(received, 0, 3, 11))
    }

    @Test fun `RESET restores multi batch pulse histories and their high water without source revisions`() {
        val events = (1L..6000L).map { TimelinePulse(it, it, TimelineValue.UInt(it)) }
        val ops = f.root() + pulseObject(6500) + listOf(
            TimelineOperation.Pulse(1, 65536, 0, 1, 4097, events.take(4096)),
            TimelineOperation.Pulse(1, 65536, 1, 4097, 6001, events.drop(4096)))
        val source = source(ops, observed = 6000)
        val peer = Peer(source.context)
        val publication = assertNotNull(peer.encode(source.current!!))
        val batches = peer.read(publication).filterIsInstance<TimelineOperation.Pulse>()
        assertEquals(listOf(4096, 1904), batches.map { it.events.size })
        val received = peer.store.apply(publication.header, publication.operations)
        val curve = received.objects.getValue(1).curves.getValue(65536)
        assertEquals(events, curve.events)
        assertEquals(6500L, curve.eventHighWater)
        val helperOps = TimelineEncoder.resetOperations(source.current!!)
        val independent = TimelineStore(source.context)
        val rebuilt = independent.apply(f.header(helperOps, observed = 6000), helperOps)
        assertEquals(events, rebuilt.objects.getValue(1).curves.getValue(65536).events)
    }

    @Test fun `RESET pulse chunk boundaries never split one timestamp group`() {
        val events = (1L..4090L).map { TimelinePulse(it, it, TimelineValue.UInt(it)) } +
            (4091L..4110L).map { TimelinePulse(5000, it, TimelineValue.UInt(it)) } + TimelinePulse(5001, 4111, TimelineValue.UInt(1))
        val ops = f.root() + pulseObject(4111) + listOf(
            TimelineOperation.Pulse(1, 65536, 0, 1, 4091, events.take(4090)),
            TimelineOperation.Pulse(1, 65536, 1, 5000, 5002, events.drop(4090)))
        val source = source(ops, observed = 5001)
        val peer = Peer(source.context)
        val publication = assertNotNull(peer.encode(source.current!!))
        val batches = peer.read(publication).filterIsInstance<TimelineOperation.Pulse>()
        assertEquals(listOf(4090, 21), batches.map { it.events.size })
        assertTrue(batches[0].until <= batches[1].from)
        val received = peer.store.apply(publication.header, publication.operations)
        assertEquals(events, received.objects.getValue(1).curves.getValue(65536).events)
    }

    @Test fun `large unsealed pulse update requests RESET instead of truncating historical events`() {
        val source = source(f.root() + pulseObject())
        val peer = Peer(source.context)
        val before = peer.deliver(source.current!!)
        val events = (1L..5000L).map { TimelinePulse(it, it, TimelineValue.UInt(it)) }
        val desired = f.commit(source, listOf(
            TimelineOperation.Pulse(1, 65536, 0, 1, 4097, events.take(4096)),
            TimelineOperation.Pulse(1, 65536, 1, 4097, 5001, events.drop(4096))), observed = 5000)
        assertFailsWith<TimelineResetRequired> { peer.encode(desired) }
        assertSame(before, peer.store.current)
        val received = peer.deliver(desired, reset = true)
        assertEquals(events, received.objects.getValue(1).curves.getValue(65536).events)
        assertEquals(5000L, received.objects.getValue(1).curves.getValue(65536).eventHighWater)
    }

    @Test fun `backdated newly allocated pulse ids use explicit RESET when UPDATE order cannot express them`() {
        val source = source(f.root() + pulseObject())
        val peer = Peer(source.context)
        peer.deliver(source.current!!)
        f.commit(source, listOf(TimelineOperation.Pulse(1, 65536, 0, 8, 9, listOf(TimelinePulse(8, 1, TimelineValue.UInt(1))))))
        val desired = f.commit(source, listOf(TimelineOperation.Pulse(1, 65536, 1, 4, 5, listOf(TimelinePulse(4, 2, TimelineValue.UInt(2))))))
        assertFailsWith<TimelineResetRequired> { peer.encode(desired) }
        val received = peer.deliver(desired, reset = true)
        assertEquals(listOf(2L, 1L), received.objects.getValue(1).curves.getValue(65536).events.map { it.eventId })
    }

    @Test fun `byte heavy valid pulses require byte bounded RESET batches and explicit UPDATE fallback`() {
        val declaration = listOf(TimelineOperation.Create(1, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(1, 65536, TimelineMode.PULSE, TimelineValueType.BLOB, false, 0, tail()))
        val source = source(f.root() + declaration)
        val peer = Peer(source.context)
        val previous = peer.deliver(source.current!!)
        val payload = TimelineValue.Blob(TimelineBytes(ByteArray(65536) { 42 }))
        val events = (1L..17L).map { TimelinePulse(it, it, payload) }
        val additions = events.mapIndexed { index, event ->
            TimelineOperation.Pulse(1, 65536, index.toLong(), event.time, event.time + 1, listOf(event))
        }
        val desired = f.commit(source, additions, observed = 20)
        assertFailsWith<TimelineResetRequired> { peer.encode(desired) }
        assertSame(previous, peer.store.current)
        val publication = assertNotNull(peer.encode(desired, reset = true))
        val batches = peer.read(publication).filterIsInstance<TimelineOperation.Pulse>()
        assertTrue(batches.size > 1, "The logical state is valid but cannot fit in one operation")
        assertEquals(events, batches.flatMap { it.events })
        assertTrue(batches.zipWithNext().all { (a, b) -> a.until <= b.from })
        val received = peer.store.apply(publication.header, publication.operations)
        assertEquals(events, received.objects.getValue(1).curves.getValue(65536).events)
        val fresh = Peer(source.context).deliver(desired)
        assertEquals(events, fresh.objects.getValue(1).curves.getValue(65536).events)
    }

    @Test fun `large STEP correction resets without dropping any unsealed interval`() {
        val source = source()
        val peer = Peer(source.context)
        peer.deliver(source.current!!)
        val segments = (0L until 5000L).map { time ->
            TimelineSegment.Step(time, time + 1, false, TimelineValue.SInt(70 + time % 2))
        } + TimelineSegment.Step(5000, Long.MAX_VALUE, false, TimelineValue.SInt(80))
        val desired = f.commit(source, listOf(
            TimelineOperation.Splice(1, 3, 1, 0, 4096, segments.take(4096)),
            TimelineOperation.Splice(1, 3, 2, 4096, Long.MAX_VALUE, segments.drop(4096))), observed = 5000)
        assertFailsWith<TimelineResetRequired> { peer.encode(desired) }
        val received = peer.deliver(desired, reset = true)
        assertEquals(segments, received.objects.getValue(1).curves.getValue(3).segments)
        for (time in listOf(0L, 4095L, 4096L, 4999L, 5000L)) assertEquals(
            TimelineProfile.sample(desired, 1, 3, time), TimelineProfile.sample(received, 1, 3, time))
    }

    @Test fun `foreign access and team publications are rejected before emitting any transaction`() {
        val source = source()
        val desired = source.current!!
        val peer = Peer(desired.context)
        assertFailsWith<ProtocolException> { peer.encode(desired.copy(context = desired.context.copy(accessId = TimelineBytes(ByteArray(16) { 7 })))) }
        assertFailsWith<ProtocolException> { peer.encode(desired.copy(context = desired.context.copy(teamId = 1))) }
        assertNull(peer.store.current)
    }
}
