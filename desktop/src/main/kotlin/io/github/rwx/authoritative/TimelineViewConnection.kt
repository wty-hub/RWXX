package io.github.rwx.authoritative

import io.github.rwx.net.authoritative.ViewUnitOrder
import io.github.rwx.net.authoritative.ViewActionCommand
import io.github.rwx.net.authoritative.CommandTarget
import io.github.rwx.net.authoritative.OrderKind
import io.github.rwx.net.authoritative.PlayerActionPanel
import io.github.rwx.net.authoritative.PlayerViewSnapshot
import io.github.rwx.net.timeline.*
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Narrow seam for headless UI tests; the production adapter uses only the v7 client. */
internal interface TimelineViewTransport : AutoCloseable {
    val hello: TimelineRecord
    val connected: Boolean
    val synchronizing: Boolean
    val failureMessage: String?
    fun latest(streamId: Long): TimelineState?
    fun observeFrames(observer: (TimelineFrame) -> Unit): AutoCloseable
    fun open(requestId: Long, mode: TimelineStreamMode, from: Long, until: Long, budget: Long)
    fun closeStream(streamId: Long)
    fun select(frame: TimelineFrame)
    fun command(frame: TimelineFrame)
    fun statistics(): TimelineWireStatistics? = null
    fun inputWriteStatistics(): TimelineInputWriteStatistics? = null
    fun completedHistory(streamId: Long): TimelineHistoryEndReceipt? = null
}

private class ClientTimelineViewTransport(private val client: TimelineClient) : TimelineViewTransport {
    override val hello get() = client.serverHello
    override val connected get() = client.isConnected
    override val synchronizing get() = client.isSynchronizing
    override val failureMessage get() = client.failure?.message
    override fun latest(streamId: Long) = client.latest(streamId)
    override fun observeFrames(observer: (TimelineFrame) -> Unit) = client.observeFrames(observer)
    override fun open(requestId: Long, mode: TimelineStreamMode, from: Long, until: Long, budget: Long) {
        client.openTimeline(requestId, mode, from, until, budget)
    }
    override fun closeStream(streamId: Long) = client.closeTimeline(streamId)
    override fun select(frame: TimelineFrame) = client.select(frame)
    override fun command(frame: TimelineFrame) = client.sendCommand(frame)
    override fun statistics() = client.wireStatistics()
    override fun inputWriteStatistics() = client.inputWriteStatistics()
    override fun completedHistory(streamId: Long) = client.completedHistory(streamId)
    override fun close() = client.close()
}

internal sealed interface TimelineUiTarget {
    data object None : TimelineUiTarget
    data class Point(val xQ: Long, val yQ: Long) : TimelineUiTarget
    data class Unit(val handle: Long) : TimelineUiTarget
}

/**
 * At most four active streams including LIVE, and one bounded window per time pane. No full-game cache,
 * engine, legacy encoder or renderer state is involved. Paused and historical panes cannot send.
 * Network callbacks only enqueue small status records; sampling never blocks the network reader.
 */
internal class TimelineViewConnection(
    private val transport: TimelineViewTransport,
    private val clock: () -> Long = System::nanoTime,
    private val byteBudget: Long = 1_048_576,
) : TimelinePlaybackSource {
    constructor(client: TimelineClient) : this(ClientTimelineViewTransport(client))
    override val teamId = transport.hello.number(2).toInt()
    override val ticksPerSecond = transport.hello.number(3).toInt()
    override val protocolVersion = TimelineProtocol.VERSION
    override val playback = TimelinePlaybackController(ticksPerSecond, transport.hello.number(8), transport.hello.number(11).toInt())
    override var activePane: Int = 0
        @Synchronized set(value) { require(value in playback.paneIds()); field = value }
    private data class Window(val from: Long, val until: Long)
    private data class Pending(val pane: Int?, val window: Window?)
    private data class Binding(val stream: Long, val window: Window, var state: TimelineState? = null,
        var completion: TimelineHistoryEndReceipt? = null) {
        val ended: Boolean get() = completion != null
    }
    private data class Selection(val handles: List<Long>, val stream: Long, val epoch: Long, val control: Long)
    private val events = ArrayBlockingQueue<TimelineFrame>(32)
    private val closed = AtomicBoolean()
    private val eventOverflow = AtomicBoolean()
    private val pending = LinkedHashMap<Long, Pending>()
    private val bindings = HashMap<Int, Binding>()
    private val activeStreams = HashSet<Long>()
    private val closingStreams = HashSet<Long>()
    private val displayed = HashMap<Int, TimelineDisplayFrame>()
    private val failedAt = HashMap<Int, Long>()
    private val commandTimes = ArrayDeque<Long>()
    private var requestSequence = 0L
    private var commandSequence = 0L
    private var selectSequence = 0L
    private var displaySequence = 0L
    private var liveStream: Long? = null
    private var lastLiveIdentity: Triple<Long, Long, Long>? = null
    private var historyFrom = playback.historyFrom
    private var historyUntil = historyFrom
    private var pendingSelection: Selection? = null
    private var lastSelection: Selection? = null
    private var lastSelectNanos: Long? = null
    private var lastFrame: TimelineDisplayFrame? = null
    private var localFailure: String? = null
    private val subscription: AutoCloseable

    init {
        require(byteBudget in 16_384..16_777_216)
        subscription = transport.observeFrames { frame ->
            if (!closed.get() && (frame.type == 110 || frame.type == 112) && !events.offer(frame)) eventOverflow.set(true)
        }
        try { open(null, null) } catch (failure: Exception) { subscription.close(); transport.close(); throw failure }
    }

    override val isConnected get() = !closed.get() && !eventOverflow.get() && transport.connected && localFailure == null
    override val failureMessage get() = localFailure ?: if (eventOverflow.get()) "Timeline UI event queue exceeded" else transport.failureMessage
    override val readOnly get() = !playback.followsLive(activePane.takeIf { it in playback.paneIds() } ?: 0)
    override val isSynchronizing get() = transport.synchronizing || lastFrame == null || !isConnected
    override val label get() = if (readOnly) "Timeline playback" else "Live timeline"
    override fun timelineWireStatistics() = transport.statistics()
    override fun inputWriteStatistics() = transport.inputWriteStatistics()

    /** Pure diagnostic read. It never pumps events, samples, opens a stream, or reads an engine. */
    @Synchronized override fun completedHistoryForPane(paneId: Int): TimelineHistoryEndReceipt? {
        if (closed.get() || paneId !in playback.paneIds() || playback.followsLive(paneId)) return null
        val binding = bindings[paneId] ?: return null
        val completion = binding.completion ?: return null
        return completion.takeIf { binding.state?.let { state -> matchesCompletion(binding, state, completion) } == true }
    }

    private fun matchesCompletion(binding: Binding, state: TimelineState, completion: TimelineHistoryEndReceipt): Boolean {
        val h = state.header
        return state.context.mode == TimelineStreamMode.HISTORY && h.controlEpoch == 0L &&
            h.streamId == binding.stream && h.streamId == completion.streamId && h.epoch == completion.epoch &&
            h.revision == completion.revision && completion.acceptedFrom == binding.window.from &&
            completion.acceptedUntil == binding.window.until && state.context.acceptedFrom == binding.window.from &&
            state.context.acceptedUntil == binding.window.until && h.retainedFrom == binding.window.from &&
            h.retainedUntil == binding.window.until
    }

    @Synchronized override fun latestTimelineFrame(): TimelineDisplayFrame? {
        if (activePane !in playback.paneIds()) activePane = 0
        return samplePane(activePane, clock()).also { lastFrame = it }
    }
    override fun latestSnapshot(): PlayerViewSnapshot? = latestTimelineFrame()?.snapshot
    @Synchronized override fun latestActionPanel(): PlayerActionPanel? = lastFrame?.panel

    @Synchronized override fun samplePane(paneId: Int, nowNanos: Long): TimelineDisplayFrame? {
        require(paneId in playback.paneIds())
        pump(nowNanos)
        if (!isConnected) return null.also { displayed.remove(paneId) }
        val live = liveStream?.let(transport::latest)
        if (live != null) {
            val identity = Triple(live.header.streamId, live.header.epoch, live.header.revision)
            if (identity != lastLiveIdentity) {
                playback.updateLive(live.header.observedThrough, live.header.retainedFrom, live.header.retainedUntil,
                    nowNanos, terminalTime(live))
                lastLiveIdentity = identity
                historyUntil = maxOf(historyUntil, live.header.sealedThrough)
            }
        }
        var position = playback.position(paneId, nowNanos)
        val state = if (position.followsLive) {
            bindings.remove(paneId)?.let { if (!it.ended) closeStream(it.stream) }
            live
        } else {
            val existing = bindings[paneId]
            // Only a verified, completed HISTORY is cached. During recovery a null current
            // receiver state must immediately hide the previous sample, even at the same time.
            val history = existing?.let { binding ->
                val received = transport.latest(binding.stream)
                val completed = binding.completion
                if (completed != null && received != null && !matchesCompletion(binding, received, completed)) {
                    // A newly received identity cannot inherit a cached earlier End. Keep only
                    // one library reference; the completion itself is five primitive fields.
                    binding.completion = null; binding.state = null
                }
                if (binding.ended) binding.state else received
            }
            when {
                history != null && position.time in history.header.retainedFrom until history.header.retainedUntil -> history
                live != null && position.time in live.header.retainedFrom until live.header.retainedUntil -> live
                else -> {
                    if (existing == null || existing.ended || position.time !in existing.window.from until existing.window.until)
                        requestHistory(paneId, position.time, nowNanos)
                    null
                }
            }
        }
        if (state == null) {
            playback.setBuffering(paneId, true, nowNanos); displayed.remove(paneId)
            return null
        }
        playback.setBuffering(paneId, false, nowNanos)
        position = playback.position(paneId, nowNanos)
        check(displaySequence < Long.MAX_VALUE) { "Display sequence exhausted" }
        val frame = TimelineViewAdapter.sample(state, position.time, ++displaySequence,
            displayOnly = !position.followsLive || transport.synchronizing)
        if (frame == null) displayed.remove(paneId) else displayed[paneId] = frame
        return frame
    }

    /** Selection is coalesced at at most six changes per second, below the server's eight limit. */
    @Synchronized override fun selectionChanged(handles: List<Long>) {
        val frame = displayed[activePane] ?: return
        val state = liveForInput(activePane, frame.snapshot.sequence) ?: return
        val selected = handles.distinct().sorted()
        if (selected.size > 256 || selected.any { handle -> frame.snapshot.units.none { it.handle == handle && it.controllable } || !controlled(state, handle) }) return
        val choice = Selection(selected, state.header.streamId, state.header.epoch, state.header.controlEpoch)
        if (choice == lastSelection) { pendingSelection = null; return }
        pendingSelection = choice
        flushSelection(clock())
    }

    @Synchronized fun sendCommand(paneId: Int, displayedSequence: Long, kind: Int, handles: List<Long>,
        target: TimelineUiTarget, actionHandle: Long = 0, queued: Boolean = false, cancel: Boolean = false): Boolean {
        pump(clock())
        val state = liveForInput(paneId, displayedSequence) ?: return false
        val frame = displayed.getValue(paneId)
        val selected = handles.distinct().sorted()
        if (selected.isEmpty() || selected.size > 256 || selected.any { handle ->
            frame.snapshot.units.none { it.handle == handle && it.controllable } || !controlled(state, handle) }) return false
        if (target is TimelineUiTarget.Unit && (frame.snapshot.units.none { it.handle == target.handle } ||
                !activeUnit(state, target.handle))) return false
        if (target is TimelineUiTarget.Point) {
            val map = state.context.mapRecord
            if (target.xQ < 0 || target.yQ < 0 || target.xQ >= map.number(2) * map.number(4) * 256 ||
                target.yQ >= map.number(3) * map.number(5) * 256) return false
        }
        if (kind == 5) {
            if (selected.size != 1 || frame.actions.none { it.handle == actionHandle && it.subjectHandle == selected.single() }) return false
            val action = state.objects[actionHandle] ?: return false
            if (action.kind != TimelineObjectKind.ACTION || !active(action, state.header.observedThrough) ||
                action.descriptor.number(2) != selected.single()) return false
            val ability = action.descriptor.record(4)
            val targetTag = when (target) { TimelineUiTarget.None -> 0L; is TimelineUiTarget.Point -> 1L; is TimelineUiTarget.Unit -> 2L }
            if ((!cancel && ability.number(2) != targetTag) || (queued && !ability.boolean(3)) ||
                (cancel && (TimelineProfile.sample(state, actionHandle, 5, state.header.observedThrough) as? TimelineValue.Bool)?.value != true)) return false
        }
        val now = clock()
        while (commandTimes.isNotEmpty() && now - commandTimes.first >= 1_000_000_000L) commandTimes.removeFirst()
        if (commandTimes.size >= 50 || commandSequence == Long.MAX_VALUE) return false
        val targetRecord = when (target) {
            TimelineUiTarget.None -> TimelineRecord(listOf(number(1, 0, true)))
            is TimelineUiTarget.Point -> TimelineRecord(listOf(number(1, 1, true), TimelineField(2, true, TimelineTlvValue.I64(target.xQ)),
                TimelineField(3, true, TimelineTlvValue.I64(target.yQ))))
            is TimelineUiTarget.Unit -> TimelineRecord(listOf(number(1, 2, true), number(4, target.handle)))
        }
        val command = TimelineFrame(5, TimelineRecord(listOf(number(1, state.header.streamId, true), number(2, state.header.epoch),
            number(3, commandSequence + 1), number(4, state.header.revision), number(5, state.header.controlEpoch), number(6, kind.toLong(), true),
            TimelineMessages.bytesField(7, selectionBytes(selected)), number(8, actionHandle), TimelineMessages.recordField(9, targetRecord),
            number(10, (if (queued) 1L else 0L) or (if (cancel) 2L else 0L), true))))
        return try {
            TimelineMessages.validate(command, true); commandSequence++; transport.command(command); commandTimes.addLast(now); true
        } catch (_: Exception) { false }
    }

    override fun send(intent: ViewUnitOrder) {
        val kind = when (intent.kind) { OrderKind.MOVE -> 1; OrderKind.ATTACK_MOVE -> 2; OrderKind.STOP -> 3; OrderKind.ATTACK_UNIT -> 4 }
        check(sendCommand(activePane, intent.viewSequence, kind, intent.unitHandles, target(intent.target))) { "Timeline command is unavailable" }
    }
    override fun sendAction(intent: ViewActionCommand) {
        check(sendCommand(activePane, intent.viewSequence, 5, listOf(intent.unitHandle), target(intent.target),
            intent.actionHandle, intent.queued, intent.cancel)) { "Timeline ability is unavailable" }
    }

    private fun liveForInput(pane: Int, sequence: Long): TimelineState? {
        if (!isConnected || transport.synchronizing || !playback.followsLive(pane)) return null
        val shown = displayed[pane]?.takeIf { it.snapshot.sequence == sequence && !it.readOnly } ?: return null
        val state = liveStream?.let(transport::latest) ?: return null
        if (state.context.mode != TimelineStreamMode.LIVE || state.header.streamId != shown.streamId ||
            state.header.epoch != shown.epoch || state.header.controlEpoch != shown.controlEpoch || terminalTime(state) != null) return null
        return state
    }
    private fun flushSelection(now: Long) {
        val selection = pendingSelection ?: return
        if (lastSelectNanos?.let { now - it < 166_666_667L } == true) return
        val state = liveStream?.let(transport::latest)
        if (state == null || !playback.followsLive(activePane) || transport.synchronizing || state.header.streamId != selection.stream ||
            state.header.epoch != selection.epoch || state.header.controlEpoch != selection.control || terminalTime(state) != null ||
            selection.handles.any { !controlled(state, it) }) { pendingSelection = null; return }
        if (selectSequence == Long.MAX_VALUE) { pendingSelection = null; return }
        val request = TimelineFrame(6, TimelineRecord(listOf(number(1, selection.stream, true), number(2, selection.epoch),
            number(3, ++selectSequence), number(4, state.header.revision), TimelineMessages.bytesField(5, selectionBytes(selection.handles)))))
        try { transport.select(request); lastSelection = selection; lastSelectNanos = now }
        catch (_: Exception) { localFailure = "Timeline selection failed" }
        pendingSelection = null
    }

    private fun pump(now: Long) {
        if (eventOverflow.get()) { localFailure = "Timeline UI event queue exceeded"; close(); return }
        while (true) {
            val event = events.poll() ?: break
            val r = event.body
            if (event.type == 110) {
                val request = pending.remove(r.number(1)) ?: continue
                historyFrom = maxOf(historyFrom, r.number(8)); historyUntil = r.number(9)
                if (r.number(2) != 0L) {
                    if (request.pane == null) localFailure = "LIVE timeline was not admitted"
                    else failedAt[request.pane] = now
                    continue
                }
                val stream = r.number(3); activeStreams.add(stream)
                if (request.pane == null) liveStream = stream
                else if (request.pane in playback.paneIds() && !playback.followsLive(request.pane))
                    bindings[request.pane] = Binding(stream, checkNotNull(request.window))
                else closeStream(stream)
            } else {
                val stream = r.number(1)
                val bindingEntry = bindings.entries.firstOrNull { it.value.stream == stream }
                val received = transport.latest(stream)
                val knownEpoch = received?.header?.epoch ?: bindingEntry?.value?.completion?.epoch ?:
                    lastLiveIdentity?.takeIf { it.first == stream }?.second
                if (knownEpoch != null && r.number(2) < knownEpoch) continue
                if (r.number(3) == 2L) {
                    val binding = bindingEntry?.value ?: continue
                    val completion = transport.completedHistory(stream) ?: continue
                    val state = received ?: continue
                    // Only the core receiver can attest that this precise library passed its
                    // full check before this End. A late End never certifies a later latest().
                    if (r.number(2) != completion.epoch || !matchesCompletion(binding, state, completion)) continue
                    binding.state = state; binding.completion = completion
                } else bindingEntry?.let { entry ->
                    bindings.remove(entry.key); displayed.remove(entry.key); failedAt[entry.key] = now
                }
                activeStreams.remove(stream); closingStreams.remove(stream)
                if (stream == liveStream) { liveStream = null; displayed.clear(); lastFrame = null }
            }
        }
        val ids = playback.paneIds()
        for (id in bindings.keys.toList()) if (id !in ids) bindings.remove(id)?.let { if (!it.ended) closeStream(it.stream) }
        for (id in displayed.keys.toList()) if (id !in ids) displayed.remove(id)
        for (id in failedAt.keys.toList()) if (id !in ids) failedAt.remove(id)
        if (localFailure != null) { close(); return }
        flushSelection(now)
    }
    private fun requestHistory(pane: Int, time: Long, now: Long) {
        if (pending.values.any { it.pane == pane } || failedAt[pane]?.let { now - it < 1_000_000_000L } == true) return
        if (time < historyFrom || time >= historyUntil) return
        val span = ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS * 120
        val from = historyFrom + (time - historyFrom) / span * span
        val until = if (from > TimelineProtocol.MAX_TIME - span) historyUntil else minOf(from + span, historyUntil)
        val window = Window(from, until)
        bindings.remove(pane)?.let { if (!it.ended) closeStream(it.stream) }
        if (activeStreams.size + pending.size >= 4) return
        try { open(pane, window) } catch (_: Exception) { localFailure = "Timeline history request failed" }
    }
    private fun open(pane: Int?, window: Window?) {
        check(requestSequence < TimelineProtocol.MAX_U32) { "Timeline request identity exhausted" }
        val id = ++requestSequence
        pending[id] = Pending(pane, window)
        try { transport.open(id, if (pane == null) TimelineStreamMode.LIVE else TimelineStreamMode.HISTORY,
            window?.from ?: 0, window?.until ?: 0, byteBudget) }
        catch (failure: Exception) { pending.remove(id); throw failure }
    }
    private fun closeStream(id: Long) {
        if (!closingStreams.add(id)) return
        try { transport.closeStream(id) } catch (_: Exception) { localFailure = "Timeline stream close failed" }
    }
    private fun controlled(state: TimelineState, handle: Long): Boolean = activeUnit(state, handle) &&
        (TimelineProfile.sample(state, handle, 6, state.header.observedThrough) as? TimelineValue.SInt)?.value == teamId.toLong() &&
        (TimelineProfile.sample(state, handle, 9, state.header.observedThrough) as? TimelineValue.Bool)?.value == true
    private fun activeUnit(state: TimelineState, handle: Long): Boolean = state.objects[handle]?.let {
        it.kind == TimelineObjectKind.UNIT && active(it, state.header.observedThrough)
    } == true
    private fun active(obj: TimelineObject, time: Long) = time >= obj.observedFrom && (obj.close?.let { time < it.until } ?: true)
    private fun terminalTime(state: TimelineState): Long? {
        val status = (TimelineProfile.sample(state, 0, 2, state.header.observedThrough) as? TimelineValue.Blob)?.value ?: return null
        val cursor = TimelineBinary.Cursor(status)
        return if (cursor.u8() == 1) cursor.vuint() else null
    }
    private fun target(target: CommandTarget): TimelineUiTarget = when (target) {
        CommandTarget.None -> TimelineUiTarget.None
        is CommandTarget.Unit -> TimelineUiTarget.Unit(target.handle)
        is CommandTarget.Point -> {
            require(target.x.isFinite() && target.y.isFinite()) { "Invalid display target" }
            TimelineUiTarget.Point(Math.round(target.x.toDouble() * 256), Math.round(target.y.toDouble() * 256))
        }
    }
    private fun number(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
    private fun selectionBytes(handles: List<Long>): TimelineBytes = ByteArrayOutputStream().let { out ->
        TimelineBinary.writeVUInt(out, handles.size.toLong()); handles.forEach { TimelineBinary.writeVUInt(out, it) }; TimelineBytes(out.toByteArray())
    }
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscription.close(); transport.close(); events.clear(); pending.clear(); bindings.clear(); displayed.clear()
        activeStreams.clear(); closingStreams.clear(); pendingSelection = null; lastSelection = null; lastFrame = null
    }
}
