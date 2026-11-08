package io.github.rwx.authoritative

import io.github.rwx.net.authoritative.ViewUnitOrder
import io.github.rwx.net.authoritative.ViewActionCommand
import io.github.rwx.net.timeline.*
import kotlin.test.*

class TimelineViewConnectionTest {
    private data class Open(val id: Long, val mode: TimelineStreamMode, val from: Long, val until: Long, val budget: Long)
    private val second = 60L * 1024
    private fun n(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
    private fun b(id: Int, bytes: ByteArray) = TimelineMessages.bytesField(id, TimelineBytes(bytes))
    private fun map() = TimelineRecord(listOf(n(1, 1, true), n(2, 1, true), n(3, 1, true), n(4, 20, true), n(5, 20, true),
        b(6, byteArrayOf(0)), n(7, 1, true), b(8, byteArrayOf(0, 1)), b(9, byteArrayOf(0, 0)), b(10, byteArrayOf(0))))
    private fun context(): TimelineContext {
        val map = map()
        return TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }), TimelineCanonical.mapKey(map), map, 0, 60)
    }
    private fun state(observed: Long = second, from: Long = 0, abilities: Boolean = false): TimelineState {
        val publisher = TimelinePublisher(context(), 120)
        val unit = TimelineObservedUnit(11, TimelineValue.Vec3(2560, 2560, 0), 0, 25600, 25600, 65535, 0, "tank", 1, true)
        val ability = TimelineRecord(listOf(n(1, 2, true), n(2, 0, true), TimelineMessages.boolField(3, true),
            TimelineMessages.textField(4, "produce.builder"), TimelineMessages.textField(5, "builder"),
            TimelineField(6, true, TimelineTlvValue.I64(1024)), n(7, 1, true)))
        fun observation(time: Long) = TimelineObservation(time, 10240, TimelineBytes(byteArrayOf(0, 0, 0, 0)),
            TimelineBytes(byteArrayOf(0)), listOf(unit), if (abilities) 11 else null,
            if (abilities) listOf(TimelineObservedAction(22, ability, 1, 0, -1, null, true)) else emptyList())
        publisher.observe(observation(from))
        if (observed != from) publisher.observe(observation(observed))
        return assertNotNull(publisher.publish(0))
    }
    private fun history(from: Long, until: Long, stream: Long): TimelineState {
        val source = state(until, from)
        return TimelineArchive.sliceState(source, source.context.copy(mode = TimelineStreamMode.HISTORY,
            acceptedFrom = from, acceptedUntil = until), from, until).let {
            it.copy(header = it.header.copy(streamId = stream))
        }
    }

    private inner class FakeTransport : TimelineViewTransport {
        override val hello = TimelineRecord(listOf(n(1, 1, true), n(2, 0, true), n(3, 60, true), n(4, 1024, true),
            b(5, ByteArray(16)), b(6, ByteArray(16) { 2 }), n(7, 0), n(8, 0), n(9, second), n(10, 120, true),
            n(11, 0, true), b(12, ByteArray(16) { 1 }), n(13, 0), n(14, 1, true)))
        override var connected = true
        override var synchronizing = false
        override val failureMessage: String? = null
        val states = HashMap<Long, TimelineState>()
        val opens = ArrayList<Open>()
        val closes = ArrayList<Long>()
        val completions = HashMap<Long, TimelineHistoryEndReceipt>()
        val commands = ArrayList<TimelineFrame>()
        val selections = ArrayList<TimelineFrame>()
        var observer: ((TimelineFrame) -> Unit)? = null
        override fun latest(streamId: Long) = states[streamId]
        override fun completedHistory(streamId: Long) = completions[streamId]
        override fun observeFrames(observer: (TimelineFrame) -> Unit): AutoCloseable {
            this.observer = observer
            return AutoCloseable { this.observer = null }
        }
        override fun open(requestId: Long, mode: TimelineStreamMode, from: Long, until: Long, budget: Long) {
            opens.add(Open(requestId, mode, from, until, budget))
        }
        override fun closeStream(streamId: Long) { closes.add(streamId) }
        override fun select(frame: TimelineFrame) { TimelineMessages.validate(frame, true); selections.add(frame) }
        override fun command(frame: TimelineFrame) { TimelineMessages.validate(frame, true); commands.add(frame) }
        override fun close() { connected = false; states.clear(); completions.clear() }
        fun accept(request: Open, stream: Long, state: TimelineState, archivedUntil: Long = state.header.sealedThrough) {
            states[stream] = state.copy(header = state.header.copy(streamId = stream), context = state.context.copy(
                acceptedFrom = request.from.takeIf { request.mode == TimelineStreamMode.HISTORY },
                acceptedUntil = request.until.takeIf { request.mode == TimelineStreamMode.HISTORY }))
            observer?.invoke(TimelineFrame(110, TimelineRecord(listOf(n(1, request.id, true), n(2, 0, true), n(3, stream, true),
                n(4, request.mode.code.toLong(), true), n(5, if (request.mode == TimelineStreamMode.LIVE) state.header.retainedFrom else request.from),
                n(6, if (request.mode == TimelineStreamMode.LIVE) state.header.retainedUntil else request.until), n(7, request.budget, true), n(8, 0), n(9, archivedUntil)))))
        }
        fun end(stream: Long, reason: Long = 2, epoch: Long = states.getValue(stream).header.epoch,
                acceptedByCore: Boolean = true, globalUntil: Long = 600 * second) {
            val state = states.getValue(stream)
            if (acceptedByCore && reason == 2L && epoch == state.header.epoch && state.context.mode == TimelineStreamMode.HISTORY)
                completions[stream] = TimelineHistoryEndReceipt(stream, epoch, state.header.revision,
                    state.header.retainedFrom, state.header.retainedUntil)
            observer?.invoke(TimelineFrame(112, TimelineRecord(listOf(n(1, stream, true), n(2, epoch),
                n(3, reason, true), n(4, 0), n(5, globalUntil)))))
        }
    }

    @Test fun `commands use current v7 receipts and historical display sequences never become wire revisions`() {
        val transport = FakeTransport()
        var now = 0L
        TimelineViewConnection(transport, { now }).use { source ->
            val original = state(abilities = true)
            transport.accept(transport.opens.single(), 1, original)
            var shown = assertNotNull(source.latestTimelineFrame())
            repeat(10) { shown = assertNotNull(source.latestTimelineFrame()) }
            assertTrue(shown.snapshot.sequence > original.header.revision)
            transport.states[1] = original.copy(header = original.header.copy(revision = 2))
            assertTrue(source.sendCommand(0, shown.snapshot.sequence, 3, listOf(1), TimelineUiTarget.None))
            val command = transport.commands.single()
            assertEquals(2L, command.body.number(4)); assertEquals(1L, command.body.number(3))
            assertEquals(original.header.controlEpoch, command.body.number(5))
            val action = shown.actions.single()
            assertTrue(source.sendCommand(0, shown.snapshot.sequence, 5, listOf(1), TimelineUiTarget.None,
                action.handle, cancel = true))
            transport.states[1] = original.copy(header = original.header.copy(epoch = 2, controlEpoch = original.header.controlEpoch + 1))
            assertFalse(source.sendCommand(0, shown.snapshot.sequence, 3, listOf(1), TimelineUiTarget.None))
            shown = assertNotNull(source.latestTimelineFrame())
            source.playback.pause(nowNanos = now)
            val pausedLive = assertNotNull(source.latestTimelineFrame())
            assertTrue(pausedLive.readOnly)
            assertEquals(TimelineStreamMode.LIVE, pausedLive.sourceMode)
            assertNull(source.completedHistoryForPane(0))
            assertFalse(source.sendCommand(0, shown.snapshot.sequence, 3, listOf(1), TimelineUiTarget.None))
            source.playback.followLive(); transport.synchronizing = true
            shown = assertNotNull(source.latestTimelineFrame())
            assertTrue(shown.readOnly)
            assertFalse(source.sendCommand(0, shown.snapshot.sequence, 3, listOf(1), TimelineUiTarget.None))
            transport.synchronizing = false; transport.states.clear()
            assertNull(source.latestTimelineFrame())
            assertFalse(source.sendCommand(0, shown.snapshot.sequence, 3, listOf(1), TimelineUiTarget.None))
        }
        assertFalse(transport.connected); assertNull(transport.observer)
    }

    @Test fun `selection coalesces rapid UI changes without issuing unit commands`() {
        val transport = FakeTransport()
        var now = 0L
        TimelineViewConnection(transport, { now }).use { source ->
            transport.accept(transport.opens.single(), 1, state())
            val shown = assertNotNull(source.latestTimelineFrame())
            source.selectionChanged(listOf(1))
            now = 10_000_000; source.selectionChanged(emptyList())
            now = 20_000_000; source.selectionChanged(listOf(1))
            now = 30_000_000; source.selectionChanged(emptyList())
            assertEquals(1, transport.selections.size)
            now = 200_000_000; source.latestTimelineFrame()
            assertEquals(2, transport.selections.size)
            assertEquals(emptyList(), TimelineBinary.decodeSelection(transport.selections.last().body.bytes(5)))
            assertTrue(transport.commands.isEmpty())
        }
    }

    @Test fun `history pages are bounded readonly and stale windows cannot replace a later seek`() {
        val transport = FakeTransport()
        var now = 0L
        TimelineViewConnection(transport, { now }).use { source ->
            val live = state(600 * second, 480 * second)
            transport.accept(transport.opens.single(), 1, live)
            assertNotNull(source.latestTimelineFrame())
            source.playback.seek(0, 10 * second, now)
            assertNull(source.latestTimelineFrame())
            val first = transport.opens.last()
            assertEquals(TimelineStreamMode.HISTORY, first.mode)
            assertEquals(0L, first.from); assertEquals(120 * second, first.until)
            repeat(20) { source.latestTimelineFrame() }
            assertEquals(2, transport.opens.size)
            transport.accept(first, 2, history(first.from, first.until, 2), 600 * second)
            val receivedHistory = assertNotNull(source.latestTimelineFrame())
            assertEquals(TimelineStreamMode.HISTORY, receivedHistory.sourceMode)
            assertNull(source.completedHistoryForPane(0), "A received history is not complete before StreamEnd")
            transport.end(2)
            val previous = assertNotNull(source.latestTimelineFrame())
            assertTrue(previous.readOnly); assertFalse(previous.snapshot.units.single().controllable)
            assertEquals(TimelineStreamMode.HISTORY, previous.sourceMode)
            val complete = assertNotNull(source.completedHistoryForPane(0))
            assertEquals(2L, complete.streamId); assertEquals(previous.epoch, complete.epoch)
            assertEquals(previous.revision, complete.revision)
            assertEquals(first.from, complete.acceptedFrom); assertEquals(first.until, complete.acceptedUntil)
            source.playback.seek(0, 121 * second, now)
            assertNull(source.latestTimelineFrame())
            assertNull(source.completedHistoryForPane(0), "A replaced binding cannot supply old completion evidence")
            val secondPage = transport.opens.last()
            assertEquals(120 * second, secondPage.from); assertEquals(240 * second, secondPage.until)
            source.playback.seek(0, 370 * second, now)
            assertNull(source.latestTimelineFrame())
            assertEquals(3, transport.opens.size) // One pending page per pane, even during repeated seeks.
            transport.accept(secondPage, 3, history(secondPage.from, secondPage.until, 3), 600 * second)
            assertNull(source.latestTimelineFrame())
            assertTrue(3L in transport.closes)
            val last = transport.opens.last()
            assertEquals(360 * second, last.from); assertEquals(480 * second, last.until)
            transport.accept(last, 4, history(last.from, last.until, 4), 600 * second)
            val frame = assertNotNull(source.latestTimelineFrame())
            assertEquals(370 * second, frame.time); assertEquals(4L, frame.streamId)
            assertFalse(source.sendCommand(0, frame.snapshot.sequence, 3, listOf(1), TimelineUiTarget.None))
            assertTrue(transport.opens.filter { it.mode == TimelineStreamMode.HISTORY }.all { it.until - it.from <= 120 * second })
        }
    }

    @Test fun `four-pane history requests respect the four active stream budget and close releases listeners`() {
        val transport = FakeTransport()
        val source = TimelineViewConnection(transport, { 0L })
        transport.accept(transport.opens.single(), 1, state(600 * second, 480 * second))
        assertNotNull(source.latestTimelineFrame())
        val ids = listOf(source.playback.addPane(10 * second), source.playback.addPane(130 * second), source.playback.addPane(250 * second))
        ids.forEach { assertNull(source.samplePane(it, 0)) }
        assertEquals(4, transport.opens.size)
        source.playback.seek(0, 370 * second, 0)
        assertNull(source.latestTimelineFrame())
        assertEquals(4, transport.opens.size)
        val request = transport.opens[1]
        transport.accept(request, 2, history(request.from, request.until, 2), 600 * second)
        transport.end(2)
        assertNotNull(source.samplePane(ids[0], 0))
        assertNull(source.latestTimelineFrame())
        assertEquals(5, transport.opens.size) // Completed HISTORY released a network stream slot.
        source.close(); source.close()
        assertFalse(source.isConnected); assertNull(source.latestTimelineFrame()); assertNull(transport.observer)
        assertTrue(transport.states.isEmpty())
    }

    @Test fun `incomplete history recovery and cancelled stream never resurrect an old sampled window`() {
        val transport = FakeTransport()
        TimelineViewConnection(transport, { 0L }).use { source ->
            transport.accept(transport.opens.single(), 1, state(600 * second, 480 * second))
            source.latestTimelineFrame()
            source.playback.seek(0, 10 * second, 0)
            assertNull(source.latestTimelineFrame())
            val request = transport.opens.last()
            val history = history(request.from, request.until, 2)
            transport.accept(request, 2, history, 600 * second)
            assertNotNull(source.latestTimelineFrame())
            transport.states.remove(2) // The receiver invalidates its current state during restoration.
            assertNull(source.latestTimelineFrame())
            assertEquals(2, transport.opens.size) // The original bounded restore can complete.
            transport.states[2] = history.copy(header = history.header.copy(epoch = 2))
            assertNotNull(source.latestTimelineFrame())
            transport.end(2, 3)
            assertNull(source.latestTimelineFrame())
            assertNull(source.completedHistoryForPane(0), "Cancelled HISTORY is never a completed archive read")
            assertEquals(2, transport.opens.size)
        }
    }
    @Test fun `old epoch End and stale completion cannot certify a newly received HISTORY identity`() {
        val transport = FakeTransport()
        TimelineViewConnection(transport, { 0L }).use { source ->
            transport.accept(transport.opens.single(), 1, state(600 * second, 480 * second))
            source.latestTimelineFrame(); source.playback.seek(0, 10 * second, 0)
            assertNull(source.latestTimelineFrame())
            val request = transport.opens.last()
            transport.accept(request, 2, history(request.from, request.until, 2), 600 * second)
            val first = assertNotNull(source.latestTimelineFrame())
            assertEquals(1L, first.epoch)
            val state1 = transport.states.getValue(2)
            transport.states[2] = state1.copy(header = state1.header.copy(epoch = 2))
            transport.end(2, epoch = 1, acceptedByCore = false)
            val second = assertNotNull(source.latestTimelineFrame())
            assertEquals(2L, second.epoch); assertEquals(TimelineStreamMode.HISTORY, second.sourceMode)
            assertNull(source.completedHistoryForPane(0))
            // Even a same-epoch raw End event is not trusted without the core's accepted stamp.
            transport.end(2, epoch = 2, acceptedByCore = false)
            assertNotNull(source.latestTimelineFrame()); assertNull(source.completedHistoryForPane(0))
            transport.end(2, epoch = 2)
            assertNotNull(source.latestTimelineFrame())
            val completion = assertNotNull(source.completedHistoryForPane(0))
            assertEquals(2L, completion.epoch); assertEquals(second.revision, completion.revision)

            // Defensive cache invalidation: even if a faulty transport leaves an old receipt,
            // a newly supplied revision must not keep the prior completed library or evidence.
            val state2 = transport.states.getValue(2)
            transport.states[2] = state2.copy(header = state2.header.copy(revision = state2.header.revision + 1))
            val revised = assertNotNull(source.latestTimelineFrame())
            assertEquals(second.revision + 1, revised.revision)
            assertNull(source.completedHistoryForPane(0))
            transport.end(2, epoch = 2, acceptedByCore = false)
            assertNotNull(source.latestTimelineFrame()); assertNull(source.completedHistoryForPane(0))
            transport.end(2, epoch = 2)
            assertNotNull(source.latestTimelineFrame())
            assertEquals(revised.revision, assertNotNull(source.completedHistoryForPane(0)).revision)
            source.playback.followLive()
            assertNull(source.completedHistoryForPane(0))
            assertEquals(TimelineStreamMode.LIVE, assertNotNull(source.latestTimelineFrame()).sourceMode)
        }
    }

}
