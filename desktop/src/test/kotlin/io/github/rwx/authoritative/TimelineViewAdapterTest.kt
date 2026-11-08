package io.github.rwx.authoritative

import io.github.rwx.net.authoritative.ActionReadiness
import io.github.rwx.net.authoritative.MatchPhase
import io.github.rwx.net.authoritative.ViewActionKind
import io.github.rwx.net.timeline.*
import java.io.ByteArrayOutputStream
import kotlin.test.*
import kotlinx.serialization.json.*

class TimelineViewAdapterTest {
    private fun field(id: Int, value: TimelineTlvValue, critical: Boolean = true) = TimelineField(id, critical, value)
    private fun compact(vararg values: Long): TimelineBytes = ByteArrayOutputStream().let { out ->
        values.forEach { TimelineBinary.writeVUInt(out, it) }; TimelineBytes(out.toByteArray())
    }
    private fun encodedPosition(x: Long, y: Long, z: Long): TimelineBytes = ByteArrayOutputStream().let { out ->
        listOf(x, y, z).forEach { TimelineBinary.writeVSInt(out, it) }; TimelineBytes(out.toByteArray())
    }
    private fun map(): TimelineRecord = TimelineRecord(listOf(
        field(1, TimelineTlvValue.U32(1)), field(2, TimelineTlvValue.U32(2)), field(3, TimelineTlvValue.U32(2)),
        field(4, TimelineTlvValue.U32(20)), field(5, TimelineTlvValue.U32(20)),
        field(6, TimelineTlvValue.Bytes(TimelineBytes(byteArrayOf(1, 2, 4, 8)))), field(7, TimelineTlvValue.U32(1)),
        field(8, TimelineTlvValue.Bytes(TimelineBytes(byteArrayOf(0, 1, 0, 2, 0, 3, 0, 4)))),
        field(9, TimelineTlvValue.Bytes(TimelineBytes(byteArrayOf(0, 0, 0, 5, 0, 0, 0, 0)))),
        field(10, TimelineTlvValue.Bytes(compact(1, 0, 1)))))
    private fun curve(handle: Long, id: Int, value: TimelineValue, mode: TimelineMode = TimelineMode.STEP,
                      until: Long = Long.MAX_VALUE): List<TimelineOperation> = listOf(
        TimelineOperation.Declare(handle, id, mode, value.type, true),
        TimelineOperation.Splice(handle, id, 0, 0, until, listOf(if (mode == TimelineMode.STEP)
            TimelineSegment.Step(0, until, false, value)
        else TimelineSegment.Interpolated(0, until, false, 0, until, value, value))))
    private fun root(selected: Boolean = false): List<TimelineOperation> = listOf(TimelineOperation.Create(0, TimelineObjectKind.ROOT, 0)) +
        curve(0, 1, TimelineValue.SInt(5120)) +
        curve(0, 2, TimelineValue.Blob(TimelineBytes(byteArrayOf(0, 0, 0, 0)))) +
        curve(0, 3, TimelineValue.Blob(if (selected) compact(1, 1) else compact(0))) +
        curve(0, 4096, TimelineValue.Blob(TimelineBytes(byteArrayOf(0, 0, 1, 2, 10))))
    private fun unit(): List<TimelineOperation> = listOf(TimelineOperation.Create(1, TimelineObjectKind.UNIT, 0)) +
        listOf(TimelineOperation.Declare(1, 1, TimelineMode.LINEAR, TimelineValueType.VEC3, true),
            TimelineOperation.Splice(1, 1, 0, 0, 2048, listOf(TimelineSegment.Interpolated(0, 2048, false, 0, 2048,
                TimelineValue.Vec3(2560, 5120, -256), TimelineValue.Vec3(5120, 5120, 256))))) +
        curve(1, 2, TimelineValue.UInt(0), TimelineMode.ANGLE) +
        curve(1, 3, TimelineValue.SInt(-128)) + curve(1, 4, TimelineValue.SInt(25_600)) +
        curve(1, 5, TimelineValue.UInt(65535), TimelineMode.LINEAR) + curve(1, 6, TimelineValue.SInt(0)) +
        curve(1, 7, TimelineValue.Blob(TimelineBytes("tank".toByteArray()))) +
        curve(1, 8, TimelineValue.UInt(2)) + curve(1, 9, TimelineValue.Bool(true))

    private fun state(operations: List<TimelineOperation> = root() + unit(), observed: Long = 2047,
                      from: Long = 0, until: Long = observed + 1, history: Boolean = false): TimelineState {
        val map = map()
        val context = TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }),
            TimelineCanonical.mapKey(map), map, 0, 60, if (history) TimelineStreamMode.HISTORY else TimelineStreamMode.LIVE)
        val bytes = TimelineBinary.encodeOperations(operations)
        val max = operations.filterIsInstance<TimelineOperation.Create>().maxOf { it.handle }
        val h = TimelineHeader(1, 1, TimelineTransactionKind.RESET, 1, 0, observed,
            if (history) until else 0, from, until, if (history) 0 else 1, bytes.size.toLong(), TimelineCanonical.sha256(bytes), max)
        return TimelineStore(context).apply(h, bytes)
    }
    private fun frame(state: TimelineState, time: Long = 1024, sequence: Long = 1) = assertNotNull(TimelineViewAdapter.sample(state, time, sequence))

    @Test fun `exact sample becomes display coordinates and detached map fog and DTOs`() {
        val s = state(observed = 2048, until = 2049, operations = root() + stationaryAfterMotion())
        val digest = TimelineCanonical.digest(s)
        val a = frame(s)
        val unit = a.snapshot.units.single()
        assertEquals(15f, unit.x); assertEquals(20f, unit.y); assertEquals(0f, unit.height)
        assertEquals(-.5f, unit.health); assertEquals(100f, unit.maxHealth)
        assertEquals(1f, unit.buildProgress); assertEquals(2, unit.upgradeLevel); assertTrue(unit.controllable)
        assertEquals(5.0, a.snapshot.credits); assertEquals(1, a.snapshot.tick)
        assertContentEquals(byteArrayOf(0, 1, 2, 10), a.snapshot.fog)
        assertContentEquals(intArrayOf(1, 2, 3, 4), a.map.visual!!.groundTileIds)
        a.map.terrain[0] = 15; a.snapshot.terrain[1] = 15; a.snapshot.fog[0] = 10
        a.snapshot.mapVisual!!.groundTileIds[0] = 234
        val b = frame(s, sequence = 2)
        assertContentEquals(byteArrayOf(1, 2, 4, 8), b.map.terrain)
        assertEquals(0, b.snapshot.fog[0].toInt()); assertEquals(1, b.snapshot.mapVisual!!.groundTileIds[0])
        assertEquals(digest, TimelineCanonical.digest(s))
    }

    private fun stationaryAfterMotion(): List<TimelineOperation> = unit() + TimelineOperation.Splice(1, 1, 1, 2048, Long.MAX_VALUE,
        listOf(TimelineSegment.Interpolated(2048, Long.MAX_VALUE, false, 2048, Long.MAX_VALUE,
            TimelineValue.Vec3(5120, 5120, 256), TimelineValue.Vec3(5120, 5120, 256))))

    @Test fun `observation close and missing predicted attributes never reuse an earlier unit`() {
        val closed = state(root() + stationaryAfterMotion() + TimelineOperation.Close(1, 1024, TimelineCloseKind.ENDED), observed = 2048)
        assertEquals(listOf(1L), frame(closed, 1023).snapshot.units.map { it.handle })
        assertTrue(frame(closed, 1024).snapshot.units.isEmpty())
        assertNull(TimelineViewAdapter.sample(closed, 2049, 3))
        val predicted = (root() + unit()).map { op ->
            if (op is TimelineOperation.Splice && op.handle == 1L && op.attributeId == 1)
                TimelineOperation.Splice(1, 1, 0, 0, 2048, listOf((op.segments.single() as TimelineSegment.Interpolated).copy(predicted = true)))
            else if (op is TimelineOperation.Splice && op.handle == 1L && op.attributeId == 8)
                TimelineOperation.Splice(1, 8, 0, 0, 1025, listOf(TimelineSegment.Step(0, 1025, false, TimelineValue.UInt(2))))
            else op
        }
        val s = state(predicted, observed = 1024, until = 1537)
        assertTrue(frame(s, 1024).unavailableUnitHandles.isEmpty())
        val future = frame(s, 1536)
        assertTrue(future.snapshot.units.isEmpty()); assertEquals(listOf(1L), future.unavailableUnitHandles)
    }

    @Test fun `history is always readonly and repeated reverse seeks have identical sampled values`() {
        val s = state(root() + stationaryAfterMotion(), observed = 2048, until = 2048, history = true)
        val a = frame(s, 1024)
        frame(s, 1800); val b = frame(s, 1024)
        assertTrue(a.readOnly); assertFalse(a.snapshot.units.single().controllable)
        assertEquals(a.snapshot.units, b.snapshot.units); assertContentEquals(a.snapshot.fog, b.snapshot.fog)
        assertEquals(0L, a.controlEpoch); assertEquals(1024L, a.time)
        assertNull(TimelineViewAdapter.sample(s, 2048, 2))
        val live = state(root() + stationaryAfterMotion(), observed = 2048)
        assertFalse(assertNotNull(TimelineViewAdapter.sample(live, 1024, 1, displayOnly = true)).snapshot.units.single().controllable)
    }

    @Test fun `sample source identity distinguishes readonly LIVE from HISTORY without relabelling`() {
        val live = state(root() + stationaryAfterMotion(), observed = 2048)
        val paused = assertNotNull(TimelineViewAdapter.sample(live, 1024, 9001, displayOnly = true))
        assertTrue(paused.readOnly)
        assertFalse(paused.snapshot.units.single().controllable)
        assertEquals(TimelineStreamMode.LIVE, paused.sourceMode)
        assertEquals(live.header.retainedFrom, paused.retainedFrom)
        assertEquals(live.header.retainedUntil, paused.retainedUntil)
        assertEquals(live.header.sealedThrough, paused.sealedThrough)
        assertEquals(live.header.revision, paused.revision)
        assertNotEquals(paused.snapshot.sequence, paused.revision)
        assertEquals(1024L, paused.time)
        assertEquals(TimelineStreamMode.LIVE, live.context.mode)

        val history = state(root() + stationaryAfterMotion(), observed = 2048, until = 2048, history = true)
        val archived = frame(history, 1800, 9002)
        assertTrue(archived.readOnly)
        assertEquals(TimelineStreamMode.HISTORY, archived.sourceMode)
        assertEquals(0L, archived.controlEpoch)
        assertEquals(history.header.retainedFrom, archived.retainedFrom)
        assertEquals(history.header.retainedUntil, archived.retainedUntil)
        assertEquals(history.header.sealedThrough, archived.sealedThrough)
        assertEquals(1800L, archived.time)
        // Sampling another source cannot alter a previously detached frame's origin.
        assertEquals(TimelineStreamMode.LIVE, paused.sourceMode)
        assertEquals(0L, paused.sealedThrough)
    }

    @Test fun `pane probe exports actual sampled source fields rather than display sequence or readonly inference`() {
        val live = state(root() + stationaryAfterMotion(), observed = 2048)
        val sampled = assertNotNull(TimelineViewAdapter.sample(live, 1024, 9001, displayOnly = true))
        val probe = Json.parseToJsonElement("{${timelinePaneSourceProbeFields(sampled)}}").jsonObject
        assertEquals("LIVE", probe.getValue("sourceMode").jsonPrimitive.content)
        val expected = mapOf("sourceStreamId" to live.header.streamId, "sourceEpoch" to live.header.epoch,
            "sourceRevision" to live.header.revision, "sourceControlEpoch" to live.header.controlEpoch,
            "sampledTime" to 1024L, "sourceObservedThrough" to live.header.observedThrough,
            "sourceRetainedFrom" to live.header.retainedFrom, "sourceRetainedUntil" to live.header.retainedUntil,
            "sourceSealedThrough" to live.header.sealedThrough)
        assertEquals(expected.keys + "sourceMode", probe.keys)
        expected.forEach { (key, value) -> assertEquals(value, probe.getValue(key).jsonPrimitive.long, key) }

        val archived = frame(state(root() + stationaryAfterMotion(), observed = 2048, until = 2048, history = true))
        val history = Json.parseToJsonElement("{${timelinePaneSourceProbeFields(archived)}}").jsonObject
        assertEquals("HISTORY", history.getValue("sourceMode").jsonPrimitive.content)
        assertEquals(0L, history.getValue("sourceControlEpoch").jsonPrimitive.long)
        assertEquals(2048L, history.getValue("sourceSealedThrough").jsonPrimitive.long)
    }

    @Test fun `unavailable pane probe has null origin and cannot reuse a previous frame`() {
        val sampled = frame(state(root() + stationaryAfterMotion(), observed = 2048))
        val before = timelinePaneSourceProbeFields(sampled)
        val absent = Json.parseToJsonElement("{${timelinePaneSourceProbeFields(null)}}").jsonObject
        assertEquals(10, absent.size)
        assertTrue(absent.values.all { it == JsonNull })
        assertEquals(before, timelinePaneSourceProbeFields(sampled))
    }

    @Test fun `completed history probe must match this renders exact received HISTORY frame`() {
        val archived = frame(state(root() + stationaryAfterMotion(), observed = 2048, until = 2048, history = true))
        val completion = TimelineHistoryEndReceipt(archived.streamId, archived.epoch, archived.revision,
            archived.retainedFrom, archived.retainedUntil)
        val value = Json.parseToJsonElement(timelinePaneHistoryProbe(archived, completion)).jsonObject
        assertEquals(2, value.getValue("endReason").jsonPrimitive.int)
        assertEquals(archived.streamId, value.getValue("streamId").jsonPrimitive.long)
        assertEquals("null", timelinePaneHistoryProbe(null, completion))
        assertEquals("null", timelinePaneHistoryProbe(archived, null))
        for (wrong in listOf(completion.copy(streamId = completion.streamId + 1),
            completion.copy(epoch = completion.epoch + 1), completion.copy(revision = completion.revision + 1),
            completion.copy(acceptedFrom = completion.acceptedFrom + 1), completion.copy(acceptedUntil = completion.acceptedUntil + 1)))
            assertEquals("null", timelinePaneHistoryProbe(archived, wrong))
        val pausedLive = assertNotNull(TimelineViewAdapter.sample(
            state(root() + stationaryAfterMotion(), observed = 2048), 1024, 1, displayOnly = true))
        val fakeCompletion = TimelineHistoryEndReceipt(pausedLive.streamId, pausedLive.epoch, pausedLive.revision,
            pausedLive.retainedFrom, pausedLive.retainedUntil)
        assertEquals("null", timelinePaneHistoryProbe(pausedLive, fakeCompletion))
    }

    private fun cost(vararg values: Long) = TimelineRecord(values.mapIndexed { i, value -> field(i + 1, TimelineTlvValue.I64(value)) })
    private fun panel(kind: Int = 2, cost: TimelineRecord? = null, target: Long = 0,
                      streaming: TimelineRecord? = null): List<TimelineOperation> {
        val ability = TimelineRecord(listOf(field(1, TimelineTlvValue.U32(kind.toLong())), field(2, TimelineTlvValue.U32(target)),
            field(3, TimelineTlvValue.Bool(true)), field(4, TimelineTlvValue.Text("action.builder")),
            field(5, TimelineTlvValue.Text("builder")), field(6, TimelineTlvValue.I64(cost?.number(1) ?: -1)),
            field(7, TimelineTlvValue.U32(9))) + listOfNotNull(cost?.let { field(9, TimelineTlvValue.Record(it), false) },
                streaming?.let { field(10, TimelineTlvValue.Record(it), false) }))
        return listOf(TimelineOperation.Create(2, TimelineObjectKind.PANEL, 0, TimelineRecord(listOf(
            field(2, TimelineTlvValue.U64(1)), field(5, TimelineTlvValue.Bytes(compact(1, 1))))))) +
            curve(2, 1, TimelineValue.Bool(true)) + listOf(TimelineOperation.Create(3, TimelineObjectKind.ACTION, 0, TimelineRecord(listOf(
                field(2, TimelineTlvValue.U64(1)), field(3, TimelineTlvValue.U64(2)), field(4, TimelineTlvValue.Record(ability)))))) +
            curve(3, 1, TimelineValue.UInt(2)) + curve(3, 2, TimelineValue.SInt(-1)) + curve(3, 3, TimelineValue.SInt(7)) +
            curve(3, 4, TimelineValue.UInt(32768), TimelineMode.LINEAR) + curve(3, 5, TimelineValue.Bool(true))
    }

    @Test fun `panel selection exact costs and richer kinds survive without invented presentation fields`() {
        val exact = frame(state(root(true) + stationaryAfterMotion() + panel(cost = cost(2048, 512, 0, 0, 1024),
            streaming = cost(0, 1024, 0, 0, 0)), observed = 2048))
        assertEquals(listOf(1L), exact.selection); assertEquals(2L, exact.panelHandle)
        val a = exact.actions.single(); assertEquals(9, a.hotkeySlot); assertTrue(a.queueable)
        assertEquals(ActionReadiness.UNKNOWN, a.readiness); assertEquals(-1, a.queuedCount); assertEquals(7, a.stockpileCount)
        val display = assertNotNull(a.displayAction)
        assertEquals(ViewActionKind.PRODUCE, display.kind)
        val upfront = assertNotNull(display.upfrontCost)
        assertEquals(2, upfront.credits); assertEquals(.5f, upfront.energy)
        assertEquals(1f, assertNotNull(display.streamingCost).energy)
        val fractional = frame(state(root(true) + stationaryAfterMotion() + panel(cost = cost(1536, 0, 0, 0, 0)), observed = 2048))
        assertEquals(1536L, fractional.actions.single().upfrontCost!!.credits)
        assertNull(fractional.actions.single().displayAction!!.upfrontCost)
        val unknown = frame(state(root(true) + stationaryAfterMotion() + panel(), observed = 2048))
        assertNull(unknown.actions.single().creditsCost); assertNull(unknown.actions.single().upfrontCost)
        for ((target, display) in listOf(0L to ViewActionKind.LOAD_UP, 2L to ViewActionKind.LOAD_INTO)) {
            val load = frame(state(root(true) + stationaryAfterMotion() + panel(7, target = target), observed = 2048))
            assertEquals(display, load.actions.single().displayAction!!.kind)
        }
        for (kind in listOf(14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)) {
            val richer = frame(state(root(true) + stationaryAfterMotion() + panel(kind), observed = 2048))
            assertEquals(kind, richer.actions.single().kind); assertNull(richer.actions.single().displayAction)
            assertTrue(richer.panel!!.actions.isEmpty())
        }
    }

    @Test fun `continuous display sampling preserves panel input identity`() {
        val state = state(root(true) + stationaryAfterMotion() + panel(), observed = 2048)
        val first = frame(state, time = 1024, sequence = 10)
        val next = frame(state, time = 1025, sequence = 11)
        assertEquals(assertNotNull(first.panel).panelSequence, assertNotNull(next.panel).panelSequence)
        assertNotEquals(first.snapshot.sequence, next.snapshot.sequence)
        assertEquals(next.snapshot.sequence, next.panel!!.viewSequence)
    }

    @Test fun `missing fog defaults unknown terminal state disables controls and source time stays exact`() {
        val noFog = root().filterNot { it is TimelineOperation.Declare && it.attributeId == 4096 ||
            it is TimelineOperation.Splice && it.attributeId == 4096 }
        val finished = TimelineBytes(byteArrayOf(1, 0x80.toByte(), 8, 1, 0, 1, 1)) // time 1024, winner 0, defeated 1.
        val operations = noFog + stationaryAfterMotion() + TimelineOperation.Splice(0, 2, 1, 1024, Long.MAX_VALUE,
            listOf(TimelineSegment.Step(1024, Long.MAX_VALUE, false, TimelineValue.Blob(finished))))
        val time = (Int.MAX_VALUE.toLong() + 3) * 1024
        val s = state(operations, observed = time)
        val sampled = frame(s, time)
        assertEquals(time, sampled.time); assertEquals(Int.MAX_VALUE, sampled.snapshot.tick)
        assertContentEquals(byteArrayOf(10, 10, 10, 10), sampled.snapshot.fog)
        assertEquals(MatchPhase.FINISHED, sampled.snapshot.matchStatus.phase)
        assertEquals(1024L, sampled.finishedAt); assertFalse(sampled.snapshot.units.single().controllable)
    }

    private fun visual(handle: Long, time: Long, duration: Long): List<TimelineOperation> {
        val event = TimelineBinary.encodeRecord(TimelineRecord(listOf(field(1, TimelineTlvValue.U32(3)),
            field(2, TimelineTlvValue.Bytes(encodedPosition(256, 512, 128))), field(3, TimelineTlvValue.U64(duration)),
            field(4, TimelineTlvValue.U32(0xffff_ffffL)), field(5, TimelineTlvValue.Text("visible.explosion"), false))))
        return listOf(TimelineOperation.Create(handle, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(handle, 1, TimelineMode.PULSE, TimelineValueType.BLOB, true, 1,
                TimelineRecord(listOf(field(1, TimelineTlvValue.U64(duration))))),
            TimelineOperation.Pulse(handle, 1, 0, 0, 2049, listOf(TimelinePulse(time, 1, TimelineValue.Blob(event)))))
    }

    @Test fun `visual tails survive observation close and seek has no trigger side effects`() {
        val s = state(root() + stationaryAfterMotion() + visual(4, 512, 1024) +
            TimelineOperation.Close(4, 513, TimelineCloseKind.ENDED) + visual(5, 1024, 0), observed = 2048)
        val a = frame(s, 1024).effects
        assertEquals(2, a.size); assertEquals(512L, a.first().age); assertEquals(0xffff_ffffL, a.first().seed)
        assertEquals(1f, a.first().x); assertEquals(.5f, a.first().height)
        assertEquals(1, frame(s, 1025).effects.size)
        assertTrue(frame(s, 1536).effects.isEmpty())
        assertEquals(a, frame(s, 1024).effects)
    }
}
