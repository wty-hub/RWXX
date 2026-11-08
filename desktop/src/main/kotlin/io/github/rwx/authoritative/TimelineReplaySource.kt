package io.github.rwx.authoritative

import io.github.rwx.net.authoritative.ViewUnitOrder
import io.github.rwx.net.authoritative.ViewActionCommand
import io.github.rwx.net.authoritative.PlayerActionPanel
import io.github.rwx.net.authoritative.PlayerViewSnapshot
import io.github.rwx.net.timeline.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal interface TimelineReplayAccess : AutoCloseable {
    val context: TimelineContext
    val index: List<TimelineArchive.IndexEntry>
    fun seek(time: Long): TimelineState?
}

private class ArchiveTimelineReplayAccess(private val reader: TimelineArchive.Reader) : TimelineReplayAccess {
    override val context get() = reader.context
    override val index get() = reader.index
    override fun seek(time: Long) = reader.seek(time)?.state
    override fun close() = reader.close()
}

/**
 * Archive v3 / wire7 only. Opening validates the complete file before a window is created;
 * subsequent seeks and closing happen on one worker. There is no network or simulation.
 */
internal class TimelineReplaySource(
    private val access: TimelineReplayAccess,
    speed: Double = 1.0,
    private val clock: () -> Long = System::nanoTime,
) : TimelinePlaybackSource {
    constructor(file: File, speed: Double = 1.0) : this(ArchiveTimelineReplayAccess(TimelineArchive.open(file.toPath())), speed)
    private data class Load(val pane: Int, val generation: Long, val entry: TimelineArchive.IndexEntry, val time: Long)
    private data class Slot(var generation: Long = 0, var entry: TimelineArchive.IndexEntry? = null,
        var state: TimelineState? = null, var pending: Load? = null, var loading: Boolean = false)
    private val lock = Any()
    private val slots = LinkedHashMap<Int, Slot>()
    private val running = AtomicBoolean(true)
    private val failure = AtomicReference<IOException?>()
    private val stopped = CompletableFuture<Unit>()
    private var sequence = 0L
    private var lastFrame: TimelineDisplayFrame? = null
    private var lastServedPane = -1
    private val worker: Thread
    override val teamId = access.context.teamId
    override val ticksPerSecond = access.context.ticksPerSecond
    override val protocolVersion = TimelineProtocol.VERSION
    override val readOnly = true
    val availableFrom: Long
    val availableUntil: Long
    val gapCount: Int
    override val playback = TimelinePlaybackController(ticksPerSecond, access.context.historyFrom, 0)
    override var activePane = 0
        set(value) { require(value in playback.paneIds()); field = value; synchronized(lock) { lastFrame = null } }
    override val isConnected get() = running.get() && failure.get() == null
    override val failureMessage get() = failure.get()?.message
    override val isSynchronizing get() = synchronized(lock) { lastFrame == null && isConnected }
    override val label get() = when {
        failure.get() != null -> "Timeline replay error"
        !running.get() -> "Timeline replay closed"
        synchronized(lock) { lastFrame == null } -> "Timeline replay: loading or unavailable interval"
        else -> "Timeline replay"
    }
    @Volatile internal var beforeSeek: (() -> Unit)? = null

    init {
        try {
            require(speed.isFinite() && speed in 0.25..8.0) { "Replay speed must be between 0.25 and 8" }
            val first = access.index.firstOrNull() ?: throw IOException("Timeline archive has no recorded interval")
            availableFrom = first.from; availableUntil = access.index.last().until
            gapCount = access.index.zipWithNext().count { (a, b) -> a.until < b.from } +
                if (availableFrom > access.context.historyFrom) 1 else 0
            val now = clock()
            playback.updateLive(availableUntil - 1, availableFrom, availableUntil, now, availableUntil - 1)
            playback.seek(0, availableFrom, now); playback.play(0, speed, now); playback.setBuffering(0, true, now)
            slots[0] = Slot(entry = first, pending = Load(0, 0, first, first.from), loading = true)
            worker = Thread(::loadLoop, "RWXX-timeline-replay-reader").apply { isDaemon = true }
            worker.start()
        } catch (cause: Exception) { access.close(); throw cause }
    }

    override fun latestTimelineFrame(): TimelineDisplayFrame? {
        if (activePane !in playback.paneIds()) activePane = 0
        val frame = samplePane(activePane, clock())
        return synchronized(lock) { (if (isConnected) frame else null).also { lastFrame = it } }
    }
    override fun latestSnapshot(): PlayerViewSnapshot? = latestTimelineFrame()?.snapshot
    override fun latestActionPanel(): PlayerActionPanel? = synchronized(lock) { lastFrame?.panel }

    override fun samplePane(paneId: Int, nowNanos: Long): TimelineDisplayFrame? {
        require(paneId in playback.paneIds())
        if (!isConnected) return null
        var position = playback.position(paneId, nowNanos)
        // The archive has an exclusive end. Reaching it pauses at its last recorded instant,
        // leaving reverse playback and explicit seeks available instead of closing the source.
        if (position.time >= availableUntil) {
            playback.seek(paneId, availableUntil - 1, nowNanos); playback.pause(paneId, nowNanos)
            position = playback.position(paneId, nowNanos)
        }
        val requested = entryAt(position.time)
        val state = synchronized(lock) {
            prune()
            val slot = slots.getOrPut(paneId) { Slot() }
            if (requested != slot.entry) {
                check(slot.generation < Long.MAX_VALUE) { "Replay seek generation exhausted" }
                slot.generation++; slot.entry = requested; slot.state = null
                slot.pending = requested?.let { Load(paneId, slot.generation, it, position.time) }
                slot.loading = requested != null
                LockSupport.unpark(worker)
            }
            if (requested == null) null else slot.state
        }
        if (state == null) {
            playback.setBuffering(paneId, true, nowNanos)
            if (paneId == activePane) synchronized(lock) { lastFrame = null }
            return null
        }
        playback.setBuffering(paneId, false, nowNanos)
        position = playback.position(paneId, nowNanos)
        val displaySequence = synchronized(lock) { check(sequence < Long.MAX_VALUE); ++sequence }
        val frame = TimelineViewAdapter.sample(state, position.time, displaySequence, displayOnly = true).takeIf { isConnected }
        if (paneId == activePane) synchronized(lock) { lastFrame = frame.takeIf { isConnected } }
        return frame
    }

    private fun entryAt(time: Long): TimelineArchive.IndexEntry? {
        val index = access.index
        var low = 0; var high = index.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val entry = index[middle]
            when { time < entry.from -> high = middle - 1; time >= entry.until -> low = middle + 1; else -> return entry }
        }
        return null
    }
    private fun prune() {
        val ids = playback.paneIds()
        for (id in slots.keys.toList()) if (id !in ids) slots.remove(id)
    }
    private fun take(): Load? = synchronized(lock) {
        prune()
        val candidates = slots.entries.filter { it.value.pending != null }
        val chosen = candidates.firstOrNull { it.key > lastServedPane } ?: candidates.firstOrNull() ?: return@synchronized null
        lastServedPane = chosen.key
        chosen.value.pending.also { chosen.value.pending = null }
    }
    private fun loadLoop() {
        try {
            while (running.get()) {
                val request = take()
                if (request == null) { LockSupport.parkNanos(25_000_000); continue }
                beforeSeek?.invoke()
                if (!running.get()) break
                val state = access.seek(request.time)
                synchronized(lock) {
                    val slot = slots[request.pane]
                    if (running.get() && slot != null && slot.generation == request.generation && slot.entry == request.entry) {
                        slot.state = state; slot.loading = false
                    }
                }
            }
        } catch (cause: Exception) {
            if (running.get()) failure.compareAndSet(null, if (cause is IOException) cause else IOException("Timeline replay read failed", cause))
        } finally {
            running.set(false)
            synchronized(lock) { slots.clear(); lastFrame = null }
            runCatching { access.close() }.onFailure {
                failure.compareAndSet(null, IOException("Timeline replay cleanup failed", it))
            }
            stopped.complete(Unit)
        }
    }

    fun stoppedAsync(): CompletableFuture<Unit> = stopped.copy()
    internal fun residentWindows(): Int = synchronized(lock) { slots.values.count { it.state != null } }
    internal fun pendingLoads(): Int = synchronized(lock) { slots.values.count { it.pending != null || it.loading } }
    override fun selectionChanged(handles: List<Long>): Nothing = error("Timeline archive playback is read-only")
    override fun sendAction(intent: ViewActionCommand): Nothing = error("Timeline archive playback is read-only")
    override fun send(intent: ViewUnitOrder): Nothing = error("Timeline archive playback is read-only")
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        synchronized(lock) { slots.clear(); lastFrame = null }
        worker.interrupt(); LockSupport.unpark(worker)
    }
}
