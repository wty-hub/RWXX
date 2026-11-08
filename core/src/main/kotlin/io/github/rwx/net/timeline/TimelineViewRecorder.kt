package io.github.rwx.net.timeline

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Records only committed LIVE libraries already authorized for one immutable access grant. */
class TimelineViewRecorder(val path: Path, private val operationTimeoutMillis: Long = 10_000) : AutoCloseable {
    data class Result(val path: Path, val sliceCount: Long, val gapCount: Long, val partial: Boolean,
        val firstTime: Long?, val untilTime: Long?, val unsealedTail: Boolean)
    data class Statistics(val acceptedStates: Long, val coalescedStates: Long, val pendingStates: Int,
        val processingStates: Int, val sliceCount: Long, val gapCount: Long)
    private data class Inbox(val context: TimelineContext? = null, val pending: TimelineState? = null,
        val observed: Long = -1, val sealed: Long = -1, val finishing: Boolean = false)
    private val inbox = AtomicReference(Inbox())
    private val error = AtomicReference<IOException?>()
    private val completion = CompletableFuture<Result>()
    private val stopped = CompletableFuture<Unit>()
    private val accepted = AtomicLong(); private val coalesced = AtomicLong()
    private val processing = AtomicInteger(); private val slices = AtomicLong(); private val gaps = AtomicLong()
    private val busySince = AtomicLong()
    private val finishingSince = AtomicLong()
    private val worker = Thread(::run, "RWXX-timeline-view-recorder").apply { isDaemon = true }
    private val watchdog = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "RWXX-timeline-recorder-deadline").apply { isDaemon = true }
    }
    @Volatile internal var beforeProcess: (() -> Unit)? = null
    val failure: IOException? get() = error.get()

    init {
        require(operationTimeoutMillis > 0)
        worker.start()
        watchdog.scheduleAtFixedRate({
            val start = busySince.get()
            val finish = finishingSince.get()
            val now = System.nanoTime()
            val limit = TimeUnit.MILLISECONDS.toNanos(operationTimeoutMillis)
            if (!completion.isDone && ((start != 0L && now - start >= limit) || (finish != 0L && now - finish >= limit)))
                fail(IOException("Timeline recording operation deadline exceeded"))
        }, 25, 25, TimeUnit.MILLISECONDS)
    }

    /** O(1), no encoding, hashing, filesystem access, callbacks, or waiting for the writer. */
    fun record(state: TimelineState): Boolean {
        while (true) {
            if (failure != null) return false
            val old = inbox.get()
            if (old.finishing) return false
            if (!sameGrant(old.context, state.context) || state.header.observedThrough < old.observed ||
                state.header.sealedThrough < old.sealed) {
                fail(IOException("Timeline recording grant or watermarks changed")); return false
            }
            val next = Inbox(old.context ?: state.context, state, state.header.observedThrough, state.header.sealedThrough)
            if (!inbox.compareAndSet(old, next)) continue
            accepted.incrementAndGet()
            if (old.pending != null) coalesced.incrementAndGet()
            LockSupport.unpark(worker)
            return true
        }
    }

    fun statistics(): Statistics = Statistics(accepted.get(), coalesced.get(), if (inbox.get().pending == null) 0 else 1,
        processing.get(), slices.get(), gaps.get())
    fun finishAsync(): CompletableFuture<Result> {
        finishingSince.compareAndSet(0, System.nanoTime())
        while (true) {
            val old = inbox.get()
            if (old.finishing || inbox.compareAndSet(old, old.copy(finishing = true))) break
        }
        LockSupport.unpark(worker)
        return completion.copy()
    }
    fun stoppedAsync(): CompletableFuture<Unit> = stopped.copy()
    /** Request a complete drain and finish; deliberately never waits for disk or the worker. */
    override fun close() { finishAsync() }

    private fun sameGrant(first: TimelineContext?, next: TimelineContext): Boolean {
        if (next.mode != TimelineStreamMode.LIVE || next.matchId.size != 16 || next.accessId.size != 16 || next.mapKey.size != 32) return false
        if (first == null) return true
        // The receiver pins one immutable map object per connection. Identity is checked here
        // so even a map change in a coalesced-away revision cannot escape worker validation.
        return first.mapRecord === next.mapRecord && first.mapKey == next.mapKey && first.matchId == next.matchId &&
            first.accessId == next.accessId && first.teamId == next.teamId && first.ticksPerSecond == next.ticksPerSecond &&
            first.profileId == next.profileId && first.joinedAt == next.joinedAt && first.historyFrom == next.historyFrom &&
            first.maxPredictionTime == next.maxPredictionTime
    }

    private fun take(): TimelineState? {
        while (true) {
            val old = inbox.get()
            val state = old.pending ?: return null
            if (inbox.compareAndSet(old, old.copy(pending = null))) return state
        }
    }
    private fun fail(cause: IOException) {
        if (!error.compareAndSet(null, cause)) return
        while (true) {
            val old = inbox.get()
            if (inbox.compareAndSet(old, old.copy(pending = null, finishing = true))) break
        }
        completion.completeExceptionally(cause)
        worker.interrupt(); LockSupport.unpark(worker)
    }
    private fun checkHealthy() { failure?.let { throw it } }

    private fun run() {
        var writer: TimelineArchive.Writer? = null
        var privatePath: Path? = null
        var published = false
        var firstTime: Long? = null
        var untilTime: Long? = null
        var nextFrom: Long? = null
        var lastObserved = -1L
        var lastFinished: Long? = null
        val target = path.toAbsolutePath().normalize()
        try {
            while (true) {
                checkHealthy()
                val state = take()
                if (state == null) {
                    if (inbox.get().finishing) break
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25))
                    continue
                }
                processing.set(1); busySince.set(System.nanoTime())
                try {
                    beforeProcess?.invoke(); checkHealthy()
                    if (writer == null) {
                        protocolRequire(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Recording destination already exists")
                        privatePath = target.parent.resolve(".rwx-recorder-${UUID.randomUUID()}.rwxr")
                        writer = TimelineArchive.Writer(checkNotNull(privatePath), state.context)
                        nextFrom = state.context.historyFrom
                    }
                    var from = checkNotNull(nextFrom)
                    if (state.header.retainedFrom > from) {
                        gaps.incrementAndGet(); from = state.header.retainedFrom
                    }
                    val end = state.header.sealedThrough
                    val span = state.context.ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS
                    while (from < end) {
                        checkHealthy()
                        val until = if (from > TimelineProtocol.MAX_TIME - span) end else minOf(from + span, end)
                        checkNotNull(writer).append(state, from, until)
                        if (firstTime == null) firstTime = from
                        untilTime = until; slices.incrementAndGet(); from = until
                    }
                    nextFrom = from
                    lastObserved = state.header.observedThrough
                    val status = TimelineProfile.sample(state, 0, 2, lastObserved) as? TimelineValue.Blob
                    lastFinished = status?.value?.let { bytes ->
                        val cursor = TimelineBinary.Cursor(bytes)
                        if (cursor.u8() == 1) cursor.vuint() else null
                    }
                } finally { processing.set(0); busySince.set(0) }
            }
            checkHealthy()
            val archive = writer ?: throw IOException("No LIVE state was recorded")
            busySince.set(System.nanoTime())
            archive.finish(); checkHealthy()
            // A late I/O completion can only publish a private staging name until the recorder
            // has checked its deadline. Never overwrite an existing recording or expose partial bytes.
            Files.createLink(target, checkNotNull(privatePath)); published = true
            checkHealthy()
            val unsealed = untilTime == null || if (lastFinished != null) checkNotNull(untilTime) <= checkNotNull(lastFinished)
                else checkNotNull(untilTime) <= lastObserved
            val result = Result(target, slices.get(), gaps.get(), gaps.get() != 0L || unsealed, firstTime, untilTime, unsealed)
            if (!completion.complete(result)) {
                Files.deleteIfExists(target); published = false
            }
        } catch (cause: Exception) {
            fail(if (cause is IOException) cause else IOException("Timeline recording failed", cause))
            if (published) runCatching { Files.deleteIfExists(target) }
        } finally {
            busySince.set(0); processing.set(0)
            runCatching { writer?.close() }
            if (writer?.completed == true) privatePath?.let { runCatching { Files.deleteIfExists(it) } }
            watchdog.shutdownNow()
            stopped.complete(Unit)
        }
    }
}
