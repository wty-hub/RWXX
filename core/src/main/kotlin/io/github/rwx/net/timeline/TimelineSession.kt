package io.github.rwx.net.timeline

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Per-grant limits; counts and estimated bytes include the item currently being processed. */
data class TimelineSessionLimits(
    val liveWindowSeconds: Int = 30,
    val observationCount: Int = 128,
    val observationBytes: Long = 128L * 1024 * 1024,
    val archiveCount: Int = 4,
    val archiveBytes: Long = 128L * 1024 * 1024,
    val historyRequests: Int = 4,
    val historyStateBytes: Long = TimelineProtocol.MAX_BLOB_BYTES.toLong(),
    val historyBytes: Long = 128L * 1024 * 1024,
    val operationTimeoutMillis: Long = 10_000,
) {
    init {
        require(liveWindowSeconds in 2..120 && observationCount in 1..4096 && archiveCount in 1..128 && historyRequests in 1..64)
        require(observationBytes > 0 && archiveBytes > 0 && historyStateBytes in 1..TimelineProtocol.MAX_BLOB_BYTES.toLong())
        require(historyBytes >= historyStateBytes && operationTimeoutMillis > 0)
    }
}

data class TimelineSessionStatistics(
    val offeredObservations: Long,
    val processedObservations: Long,
    val publications: Long,
    val archivedSlices: Long,
    val pendingObservations: Int,
    val pendingObservationBytes: Long,
    val pendingArchives: Int,
    val pendingArchiveBytes: Long,
    val historyReservations: Int,
    val reservedHistoryBytes: Long,
    val peakPendingObservations: Int = 0,
    val peakPendingObservationBytes: Long = 0,
    val peakPendingArchives: Int = 0,
    val peakPendingArchiveBytes: Long = 0,
    val peakHistoryReservations: Int = 0,
    val peakReservedHistoryBytes: Long = 0,
)

/**
 * One authorized grant's engine-independent publication and whole-game recording lifecycle.
 * offer() never encodes, hashes, waits for disk/network, or invokes a callback. Failure is explicit:
 * the owner must stop offering this grant when false is returned, and inspect failure.
 * onPublished runs only on the publisher worker and must be a nonblocking signal (for example,
 * TimelineServer.signal). Disk stalls, slow sockets and history loading cannot block the owner.
 */
class TimelineSession(
    val context: TimelineContext,
    val archivePath: Path,
    val limits: TimelineSessionLimits = TimelineSessionLimits(),
    private val onPublished: () -> Unit = {},
) : TimelineFeedFactory, AutoCloseable {
    private data class Publication(val state: TimelineState, val units: Map<Long, Long>, val actions: Map<Long, Long>)
    private data class ObservationWork(val value: TimelineObservation, val reservation: Budget.Reservation)
    private data class ArchiveWork(val state: TimelineState, val from: Long, val until: Long, val reservation: Budget.Reservation)
    private val publisher = TimelinePublisher(context, limits.liveWindowSeconds)
    private val published = AtomicReference<Publication?>()
    private val error = AtomicReference<IOException?>()
    private val closed = AtomicBoolean(); private val finishing = AtomicBoolean(); private val publisherDone = AtomicBoolean()
    private val intakeLock = Any()
    private val observations = ArrayBlockingQueue<ObservationWork>(limits.observationCount)
    private val archives = ArrayBlockingQueue<ArchiveWork>(limits.archiveCount)
    private val historyJobs = ArrayBlockingQueue<HistoryFeed>(limits.historyRequests)
    private val observationBudget = Budget(limits.observationCount, limits.observationBytes)
    private val archiveBudget = Budget(limits.archiveCount, limits.archiveBytes)
    private val historyBudget = Budget(limits.historyRequests, limits.historyBytes)
    private val historyFeeds = ConcurrentHashMap.newKeySet<HistoryFeed>()
    private val writer = AtomicReference<TimelineArchive.Writer?>()
    private val archiveFrom = AtomicLong(context.historyFrom); private val archiveUntil = AtomicLong(context.historyFrom)
    private val offered = AtomicLong(); private val processed = AtomicLong(); private val publications = AtomicLong(); private val archived = AtomicLong()
    private val observed = AtomicLong(-1)
    private val publisherBusy = AtomicLong(); private val archiveBusy = AtomicLong(); private val historyBusy = AtomicLong()
    private val completion = CompletableFuture<Path>()
    private val termination = CompletableFuture<Unit>()
    private val activeWorkers = AtomicInteger(3)
    private val publisherThread: Thread
    private val archiveThread: Thread
    private val historyThread: Thread
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "RWXX-timeline-session-watchdog").apply { isDaemon = true } }
    @Volatile internal var beforeObserve: (() -> Unit)? = null
    @Volatile internal var beforeArchiveAppend: (() -> Unit)? = null
    @Volatile internal var beforeHistoryRead: (() -> Unit)? = null
    @Volatile internal var beforeOfferEnqueue: (() -> Unit)? = null // Deterministic admission/close race fixture only.
    internal val workersStopped: Boolean get() = activeWorkers.get() == 0
    val latest: TimelineState? get() = if (closed.get() || failure != null) null else published.get()?.state
    val failure: IOException? get() = error.get()
    val isAccepting: Boolean get() = !closed.get() && !finishing.get() && failure == null
    val processedThrough: Long get() = observed.get()
    val archivedFrom: Long get() = archiveFrom.get()
    val archivedUntil: Long get() = archiveUntil.get()
    fun unitKey(handle: Long): Long? = if (closed.get() || failure != null) null else published.get()?.units?.get(handle)
    fun actionKey(handle: Long): Long? = if (closed.get() || failure != null) null else published.get()?.actions?.get(handle)
    fun statistics(): TimelineSessionStatistics {
        val o = observationBudget.snapshot(); val a = archiveBudget.snapshot(); val h = historyBudget.snapshot()
        return TimelineSessionStatistics(offered.get(), processed.get(), publications.get(), archived.get(),
            o.count, o.bytes, a.count, a.bytes, h.count, h.bytes,
            o.peakCount, o.peakBytes, a.peakCount, a.peakBytes, h.peakCount, h.peakBytes)
    }

    init {
        protocolRequire(context.mode == TimelineStreamMode.LIVE, "Session requires one LIVE authorization grant")
        publisherThread = Thread(::publishLoop, "RWXX-timeline-publisher-${context.teamId}").apply { isDaemon = true }
        archiveThread = Thread(::archiveLoop, "RWXX-timeline-archive-${context.teamId}").apply { isDaemon = true }
        historyThread = Thread(::historyLoop, "RWXX-timeline-history-${context.teamId}").apply { isDaemon = true }
        publisherThread.start(); archiveThread.start(); historyThread.start()
        watchdog.scheduleAtFixedRate(::expire, 25, 25, TimeUnit.MILLISECONDS)
    }

    fun offer(observation: TimelineObservation): Boolean {
        if (!isAccepting) return false
        val bytes = estimateObservationBytes(observation)
        return synchronized(intakeLock) {
            if (!isAccepting) return@synchronized false
            beforeOfferEnqueue?.invoke()
            val reservation = observationBudget.reserve(bytes)
            if (reservation == null) { fail(IOException("Timeline observation queue exceeded count or byte budget")); return@synchronized false }
            if (!observations.offer(ObservationWork(observation, reservation))) {
                reservation.close(); fail(IOException("Timeline observation queue exceeded")); return@synchronized false
            }
            offered.incrementAndGet(); true
        }
    }

    /** Drain accepted facts and archive only their actually sealed prefix, then publish atomically. */
    fun finishAsync(): CompletableFuture<Path> {
        synchronized(intakeLock) { finishing.set(true) }
        return completion.copy()
    }

    /** Completes after all three workers release their own files and pending reservations. */
    fun stoppedAsync(): CompletableFuture<Unit> = termination.copy()

    override fun open(request: TimelineStreamRequest): TimelineFeed? {
        if (closed.get() || failure != null || !sameGrant(request.context)) return null
        if (request.context.mode == TimelineStreamMode.LIVE) return object : TimelineFeed {
            private val encoder = TimelineEncoder()
            private val ended = AtomicBoolean()
            override fun next(cursor: TimelineSendCursor): TimelinePublication? {
                checkHealthy()
                if (ended.get()) return null
                return latest?.let { encoder.encode(cursor, it) }
            }
            override fun close() { ended.set(true) }
        }
        val from = request.context.acceptedFrom ?: return null
        val until = request.context.acceptedUntil ?: return null
        if (from < maxOf(context.historyFrom, archivedFrom) || from >= until || until > archivedUntil ||
            until - from > second * 120) return null
        // Reserving a worst-case state before loading avoids temporarily pinning N*64 MiB windows.
        val reservation = historyBudget.reserve(limits.historyStateBytes) ?: return null
        val feed = HistoryFeed(from, until, reservation)
        historyFeeds.add(feed)
        if (!historyJobs.offer(feed)) { feed.close(); return null }
        return feed
    }

    private val second: Long get() = context.ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS
    private fun sameGrant(other: TimelineContext) = context.matchId == other.matchId && context.accessId == other.accessId &&
        context.mapKey == other.mapKey && context.mapRecord == other.mapRecord && context.teamId == other.teamId &&
        context.ticksPerSecond == other.ticksPerSecond && context.profileId == other.profileId &&
        context.joinedAt == other.joinedAt && context.historyFrom == other.historyFrom
    private fun checkHealthy() { failure?.let { throw IOException("Timeline grant failed", it) }; if (closed.get()) throw IOException("Timeline session is closed") }

    private fun publishLoop() {
        var archivePosition: Long? = null
        var originTime: Long? = null; val originClock = System.nanoTime(); var publicationClock = originClock
        var lastWallClock = originClock
        try {
            while (!closed.get() && failure == null) {
                val item = observations.poll(10, TimeUnit.MILLISECONDS)
                publisherBusy.set(System.nanoTime())
                try {
                    if (item != null) {
                        beforeObserve?.invoke()
                        publisher.observe(item.value); processed.incrementAndGet(); observed.set(item.value.time)
                        if (originTime == null) originTime = item.value.time
                        val elapsed = item.value.time - checkNotNull(originTime)
                        val seconds = elapsed / second; val remainder = elapsed % second
                        publicationClock = maxOf(publicationClock, Math.addExact(originClock,
                            Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), remainder * 1_000_000_000L / second)))
                    }
                    // Accelerated simulation may put the publication clock ahead of wall time.
                    // Continue advancing it during idle flushes; do not wait for wall time to catch up.
                    val wallClock = System.nanoTime()
                    publicationClock = Math.addExact(publicationClock, maxOf(0, wallClock - lastWallClock))
                    lastWallClock = wallClock
                    val publication = if (finishing.get() && observations.isEmpty()) publisher.publishConfirmed(publicationClock)
                        else publisher.publish(publicationClock)
                    publication?.let { state ->
                        val units = state.objects.keys.mapNotNull { h -> publisher.unitKey(h)?.let { h to it } }.toMap()
                        val actions = state.objects.keys.mapNotNull { h -> publisher.actionKey(h)?.let { h to it } }.toMap()
                        published.set(Publication(state, frozenMap(units), frozenMap(actions))); publications.incrementAndGet()
                        if (archivePosition == null) {
                            archivePosition = state.header.retainedFrom
                            archiveFrom.set(checkNotNull(archivePosition)); archiveUntil.set(checkNotNull(archivePosition))
                        }
                        var position = checkNotNull(archivePosition)
                        protocolRequire(position >= state.header.retainedFrom, "Unarchived facts fell outside the LIVE retention window")
                        if (state.header.sealedThrough - position >= second) {
                            // One sealing batch is one bounded independent window. Splitting a
                            // five-second batch into five full libraries can overflow a healthy
                            // four-item archive queue before its disk worker gets scheduled.
                            // Preserve the complete batch, including its sub-second tail:
                            // rounding down can leave facts behind a short LIVE retention window.
                            val until = state.header.sealedThrough
                            enqueueArchive(state, position, until); position = until
                        }
                        archivePosition = position
                        onPublished()
                    }
                    if (finishing.get() && observations.isEmpty() && (latest?.header?.observedThrough ?: -1) == observed.get()) {
                        val state = latest
                        // A finish request can arrive after the normal publication above.
                        // Drain one more owner iteration rather than discard the unsealed tail.
                        if (state != null && state.header.sealedThrough < state.header.observedThrough) continue
                        if (state != null && archivePosition != null && state.header.sealedThrough > checkNotNull(archivePosition))
                            enqueueArchive(state, checkNotNull(archivePosition), state.header.sealedThrough)
                        publisherDone.set(true); return
                    }
                } finally { item?.reservation?.close(); publisherBusy.set(0) }
            }
        } catch (e: InterruptedException) { if (!closed.get() && failure == null) fail(IOException("Timeline publisher interrupted", e)); Thread.currentThread().interrupt() }
        catch (e: Exception) { fail(asIo("Timeline publication failed", e)) }
        finally {
            observations.forEach { it.reservation.close() }; observations.clear()
            failure?.let { completion.completeExceptionally(it) }
            if (failure != null) runCatching { onPublished() }
            workerStopped()
        }
    }

    private fun enqueueArchive(source: TimelineState, from: Long, until: Long) {
        val slice = TimelineArchive.sliceState(source, context, from, until)
        val bytes = TimelineCanonical.persistentSize(slice)
        val reservation = archiveBudget.reserve(bytes) ?: throw IOException("Timeline archive queue exceeded count or byte budget")
        if (!archives.offer(ArchiveWork(slice, from, until, reservation))) {
            reservation.close(); throw IOException("Timeline archive queue exceeded")
        }
    }

    private fun archiveLoop() {
        var recording: TimelineArchive.Writer? = null
        try {
            archiveBusy.set(System.nanoTime()); recording = TimelineArchive.Writer(archivePath, context); writer.set(recording); archiveBusy.set(0)
            while (!closed.get() && failure == null) {
                val item = archives.poll(10, TimeUnit.MILLISECONDS)
                if (item != null) {
                    archiveBusy.set(System.nanoTime())
                    try {
                        beforeArchiveAppend?.invoke(); recording.append(item.state, item.from, item.until)
                        archiveUntil.set(item.until); archived.incrementAndGet()
                    } finally { item.reservation.close(); archiveBusy.set(0) }
                } else if (publisherDone.get()) {
                    archiveBusy.set(System.nanoTime()); recording.finish(); archiveBusy.set(0)
                    completion.complete(archivePath); return
                }
            }
        } catch (e: InterruptedException) { if (!closed.get() && failure == null) fail(IOException("Timeline archive interrupted", e)); Thread.currentThread().interrupt() }
        catch (e: Exception) { fail(asIo("Timeline archive failed", e)) }
        finally {
            archiveBusy.set(0); archives.forEach { it.reservation.close() }; archives.clear()
            recording?.close()
            failure?.let { completion.completeExceptionally(it) }
            if (closed.get() && !completion.isDone) completion.completeExceptionally(IOException("Timeline session closed before archive completion"))
            workerStopped()
        }
    }

    private inner class HistoryFeed(val from: Long, val until: Long, val reservation: Budget.Reservation) : TimelineFeed {
        private val encoder = TimelineEncoder(); private val state = AtomicReference<TimelineState?>()
        private val ended = AtomicBoolean(); private val loadError = AtomicReference<IOException?>()
        private val loadingLock = Any(); private var loading = false
        fun load() {
            synchronized(loadingLock) { if (ended.get()) { release(); return }; loading = true }
            try {
                beforeHistoryRead?.invoke()
                val slice = checkNotNull(writer.get()).readCommitted(from, until, limits.historyStateBytes)
                    ?: throw IOException("Requested history is not continuously committed")
                if (!ended.get()) state.set(slice.state)
            } catch (e: Exception) { loadError.set(asIo("Timeline history read failed", e)) }
            finally { synchronized(loadingLock) { loading = false; if (ended.get()) { state.set(null); release() } } }
        }
        override fun next(cursor: TimelineSendCursor): TimelinePublication? {
            checkHealthy(); loadError.get()?.let { throw it }
            if (ended.get()) return null
            return state.get()?.let { encoder.encode(cursor, it) }
        }
        override fun close() = synchronized(loadingLock) { ended.set(true); state.set(null); if (!loading) release(); Unit }
        private fun release() { historyFeeds.remove(this); reservation.close() }
    }

    private fun historyLoop() {
        try {
            while (!closed.get() && failure == null) {
                val job = historyJobs.poll(50, TimeUnit.MILLISECONDS) ?: continue
                historyBusy.set(System.nanoTime())
                try { job.load() } finally { historyBusy.set(0) }
            }
        } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        finally { historyBusy.set(0); historyFeeds.toList().forEach { it.close() }; historyJobs.clear(); workerStopped() }
    }

    private fun expire() {
        if (closed.get() || failure != null) return
        val now = System.nanoTime(); val limit = TimeUnit.MILLISECONDS.toNanos(limits.operationTimeoutMillis)
        if (listOf(publisherBusy.get(), archiveBusy.get(), historyBusy.get()).any { it != 0L && now - it >= limit })
            fail(IOException("Timeline session worker deadline exceeded"))
    }
    private fun workerStopped() { if (activeWorkers.decrementAndGet() == 0) termination.complete(Unit) }
    private fun fail(cause: IOException) {
        val changed = synchronized(intakeLock) { !closed.get() && error.compareAndSet(null, cause) }
        if (changed) {
            published.set(null)
            publisherThread.interrupt(); archiveThread.interrupt(); historyThread.interrupt()
        }
    }
    override fun close() {
        synchronized(intakeLock) { if (!closed.compareAndSet(false, true)) return }
        published.set(null); watchdog.shutdownNow()
        publisherThread.interrupt(); archiveThread.interrupt(); historyThread.interrupt()
        historyFeeds.toList().forEach { it.close() }
    }

    companion object {
        /** Conservative allocation estimate, with no codec encoding, hash, I/O or engine access. */
        fun estimateObservationBytes(value: TimelineObservation): Long {
            var size = 256L + value.fog.size + value.matchStatus.size + value.selectedUnitKeys.size * 16L
            value.units.forEach { size += 512L + it.appearanceKey.length * 6L }
            value.actions.forEach { size += 512L + recordEstimate(it.ability) }
            value.events.forEach { size += 256L + recordEstimate(it.effect) }
            return size
        }
        private fun recordEstimate(record: TimelineRecord): Long = 64L + record.fields.sumOf { field -> 64L + when (val value = field.value) {
            is TimelineTlvValue.Bytes -> value.value.size.toLong()
            is TimelineTlvValue.Text -> value.value.length * 6L
            is TimelineTlvValue.Record -> recordEstimate(value.value)
            is TimelineTlvValue.Records -> value.values.sumOf(::recordEstimate)
            is TimelineTlvValue.Opaque -> value.value.size.toLong()
            else -> 16L
        } }
        private fun asIo(message: String, error: Exception) = if (error is IOException) error else IOException(message, error)
    }

    private class Budget(private val countLimit: Int, private val byteLimit: Long) {
        private var count = 0; private var bytes = 0L
        private var peakCount = 0; private var peakBytes = 0L
        data class Usage(val count: Int, val bytes: Long, val peakCount: Int, val peakBytes: Long)
        @Synchronized fun reserve(amount: Long): Reservation? {
            if (amount < 0 || count >= countLimit || amount > byteLimit - bytes) return null
            count++; bytes += amount
            peakCount = maxOf(peakCount, count); peakBytes = maxOf(peakBytes, bytes)
            return Reservation(this, amount)
        }
        @Synchronized fun snapshot() = Usage(count, bytes, peakCount, peakBytes)
        @Synchronized private fun release(amount: Long) { count--; bytes -= amount }
        class Reservation(private val owner: Budget, private val bytes: Long) : AutoCloseable {
            private val released = AtomicBoolean()
            override fun close() { if (released.compareAndSet(false, true)) owner.release(bytes) }
        }
    }
}
