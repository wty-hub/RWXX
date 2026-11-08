package io.github.rwx.net.timeline

import io.github.rwx.net.authoritative.AuthoritativeTls
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/** A legal LIVE input lost a race with a newer local authorization epoch; nothing was queued. */
class TimelineStaleInputException internal constructor(message: String) : IOException(message)

/** v7-only permitted timeline receiver. No engine, asset scripts or gameplay prediction is loaded. */
class TimelineClient(
    host: String,
    port: Int,
    playerName: String,
    seatToken: String,
    expectedFingerprint: String? = null,
    private val mapResolver: (TimelineBytes) -> TimelineRecord? = { null },
    private val onMapReceived: (TimelineRecord) -> Unit = {},
) : AutoCloseable {
    private val tls = expectedFingerprint?.let {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "Expected a lowercase SHA-256 certificate fingerprint" }
        AuthoritativeTls.clientContext(it)
    }
    private val socket = tls?.socketFactory?.createSocket() ?: Socket()
    private val counters = TimelineCounters()
    private val commandsWritten = AtomicLong(); private val selectionsWritten = AtomicLong()
    private val stopped = AtomicBoolean(); private val failed = AtomicReference<IOException?>()
    private val streams = ConcurrentHashMap<Long, Stream>()
    private val lock = Any()
    private val observers = CopyOnWriteArrayList<StateObserver>()
    private val frameObservers = CopyOnWriteArrayList<(TimelineFrame) -> Unit>()
    private val outgoing = ArrayBlockingQueue<Outgoing>(64)
    private val deferredOpen = CopyOnWriteArrayList<TimelineFrame>()
    private val openRequests = ConcurrentHashMap<Long, TimelineFrame>()
    private val pendingOpenSince = ConcurrentHashMap<Long, Long>()
    private val mapReady = AtomicBoolean(); private val mapStarted = AtomicLong()
    private val lastInput = AtomicLong(System.nanoTime())
    private var offeredKey: TimelineBytes? = null; private var offeredSize = 0L
    private var pinnedMap: TimelineRecord? = null; private var mapBuffer: ByteArrayOutputStream? = null
    private var requestedCache = false; private var streamHighWater = 0L; private var requestHighWater = 0L
    private var lastCheckId = 0L
    private val pingNonce = AtomicLong()
    private val pings = ConcurrentHashMap<Long, Long>()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "RWXX-timeline-client-watchdog").apply { isDaemon = true } }
    private val mapSaver = Executors.newSingleThreadExecutor { Thread(it, "RWXX-timeline-map-save").apply { isDaemon = true } }
    private val wire: TimelineSocket
    private val reader: Thread
    private val writer: Thread
    @Volatile internal var timeoutMillis = 10_000L
    @Volatile internal var heartbeatMillis = 1_000L
    @Volatile internal var beforeCommandWrite: (() -> Unit)? = null

    val serverHello: TimelineRecord
    val teamId: Int
    val ticksPerSecond: Int
    val isConnected: Boolean get() = !stopped.get()
    val failure: IOException? get() = failed.get()
    val isMapReady: Boolean get() = !stopped.get() && mapReady.get()
    val isSynchronizing: Boolean get() = synchronized(lock) {
        stopped.get() || !mapReady.get() ||
            openRequests.values.any { it.body.number(2) == TimelineStreamMode.LIVE.code.toLong() } ||
            streams.values.any { it.mode == TimelineStreamMode.LIVE && it.latest.get() == null }
    }
    fun wireStatistics(): TimelineWireStatistics = counters.snapshot()
    /** Counts complete flushed C5/C6 frames, not enqueue, server acceptance, ACKs, or UI attempts. */
    fun inputWriteStatistics(): TimelineInputWriteStatistics =
        TimelineInputWriteStatistics(commandsWritten.get(), selectionsWritten.get())
    /** Accepted verified HISTORY completion only; never reconstruct evidence from a later state. */
    fun completedHistory(streamId: Long): TimelineHistoryEndReceipt? = synchronized(lock) {
        if (stopped.get()) null else streams[streamId]?.completedHistory
    }
    fun latest(streamId: Long): TimelineState? = if (stopped.get()) null else streams[streamId]?.latest?.get()
    fun latestStates(): Map<Long, TimelineState> = if (stopped.get()) emptyMap() else frozenMap(streams.mapNotNull { (id, stream) -> stream.latest.get()?.let { id to it } }.toMap())

    init {
        val deadline = watchdog.schedule({ runCatching { socket.close() } }, 10, TimeUnit.SECONDS)
        try {
            require(port in 1..65535)
            socket.connect(InetSocketAddress(host, port), 5000)
            if (tls == null && !socket.inetAddress.isLoopbackAddress) throw IOException("Remote timeline connections require a pinned TLS fingerprint")
            socket.tcpNoDelay = true; socket.soTimeout = 10_000
            if (socket is SSLSocket) socket.startHandshake() // Authentication bytes are sent only after pin validation.
            wire = TimelineSocket(socket, counters)
            wire.write(timelineFrame(1, tn(1, 1, true), TimelineMessages.textField(2, playerName), TimelineMessages.textField(3, seatToken)), true)
            val hello = wire.read(false)
            if (hello.type == 102) throw IOException(hello.body.text(2))
            protocolRequire(hello.type == 101, "Expected ServerHello")
            serverHello = hello.body; teamId = serverHello.number(2).toInt(); ticksPerSecond = serverHello.number(3).toInt()
            socket.soTimeout = 0; deadline.cancel(false); mapStarted.set(System.nanoTime())
        } catch (error: Exception) {
            runCatching { socket.close() }; watchdog.shutdownNow(); mapSaver.shutdownNow(); throw error
        }
        reader = Thread(::read, "RWXX-timeline-client-reader").apply { isDaemon = true }
        writer = Thread(::write, "RWXX-timeline-client-writer").apply { isDaemon = true }
        watchdog.scheduleAtFixedRate(::expire, 100, 100, TimeUnit.MILLISECONDS)
        reader.start(); writer.start()
    }

    /** Safe before MapComplete: opens are deferred without blocking the map response writer. */
    fun openTimeline(requestId: Long, mode: TimelineStreamMode = TimelineStreamMode.LIVE,
                     from: Long = 0, until: Long = 0, budgetBytesPerSecond: Long = 1_048_576) = synchronized(lock) {
        if (stopped.get()) throw IOException("Timeline connection is closed", failure)
        protocolRequire(requestId > requestHighWater, "Open request ID must advance")
        val frame = timelineFrame(3, tn(1, requestId, true), tn(2, mode.code.toLong(), true), tn(3, from), tn(4, until), tn(5, budgetBytesPerSecond, true))
        TimelineMessages.validate(frame, true)
        protocolRequire(openRequests.size < 16, "Too many pending opens")
        requestHighWater = requestId; openRequests[requestId] = frame; pendingOpenSince[requestId] = System.nanoTime()
        if (mapReady.get()) enqueue(frame) else deferredOpen.add(frame)
    }
    fun closeTimeline(streamId: Long) = synchronized(lock) {
        streams[streamId]?.let { stream -> stream.closing = true; if (stream.mode == TimelineStreamMode.LIVE) stream.latest.set(null) }
        enqueue(timelineFrame(4, tn(1, streamId, true)))
    }
    fun setBudget(streamId: Long, bytesPerSecond: Long) = enqueue(timelineFrame(10, tn(1, streamId, true), tn(2, bytesPerSecond, true)))
    fun sendCommand(frame: TimelineFrame) { protocolRequire(frame.type == 5, "Expected Command"); enqueueInput(frame) }
    fun select(frame: TimelineFrame) { protocolRequire(frame.type == 6, "Expected Select"); enqueueInput(frame) }

    private fun enqueueInput(frame: TimelineFrame) = synchronized(lock) {
        TimelineMessages.validate(frame, true)
        val stream = streams[frame.body.number(1)] ?: throw IOException("LIVE stream is unavailable")
        protocolRequire(stream.mode == TimelineStreamMode.LIVE, "Input does not refer to a LIVE stream")
        if (stream.closing || stream.ended) throw IOException("LIVE stream is closing")
        val state = stream.latest.get() ?: throw IOException("LIVE stream is synchronizing")
        val epoch = frame.body.number(2)
        if (epoch < state.header.epoch) throw TimelineStaleInputException("Input LIVE epoch is stale")
        protocolRequire(epoch == state.header.epoch, "Input refers to a future LIVE epoch")
        if (frame.type == 5) {
            val control = frame.body.number(5)
            if (control < state.header.controlEpoch) throw TimelineStaleInputException("Input control epoch is stale")
            protocolRequire(control == state.header.controlEpoch, "Input refers to a future control epoch")
        }
        enqueue(frame, state.header.controlEpoch)
    }

    /** Callbacks run without the state lock; close never waits for a listener or its I/O. */
    fun observeLatest(emitCurrent: Boolean = true, observer: (TimelineState) -> Unit): AutoCloseable {
        val subscription = StateObserver(observer)
        val current = synchronized(lock) {
            if (!stopped.get()) { observers.add(subscription); if (emitCurrent) latestStates().values.toList() else emptyList() }
            else emptyList()
        }
        current.forEach { emit(subscription, it) }
        return AutoCloseable { observers.remove(subscription) }
    }
    private fun emit(subscription: StateObserver, state: TimelineState) = synchronized(subscription) {
        if (stopped.get() || !observers.contains(subscription)) return@synchronized
        val identity = state.header.epoch to state.header.revision
        val before = subscription.seen[state.header.streamId]
        if (before != null && (before.first > identity.first || (before.first == identity.first && before.second >= identity.second))) return@synchronized
        subscription.seen[state.header.streamId] = identity
        while (subscription.seen.size > 4) subscription.seen.remove(checkNotNull(subscription.seen.keys.minOrNull()))
        try { subscription.consumer(state) } catch (_: Exception) { observers.remove(subscription) }
    }
    fun observeFrames(observer: (TimelineFrame) -> Unit): AutoCloseable {
        frameObservers.add(observer)
        return AutoCloseable { frameObservers.remove(observer) }
    }

    private fun enqueue(frame: TimelineFrame, controlEpoch: Long? = null) {
        if (stopped.get()) throw IOException("Timeline connection is closed", failure)
        TimelineMessages.validate(frame, true)
        protocolRequire(TimelineBinary.recordSize(frame.body) + 8 <= TimelineProtocol.MAX_FRAME_PAYLOAD, "Outgoing frame exceeds limit")
        if (!outgoing.offer(Outgoing(frame, controlEpoch))) throw IOException("Timeline input queue exceeded").also(::fail)
    }

    private fun read() {
        try {
            while (!stopped.get()) {
                val frame = wire.read(false); lastInput.set(System.nanoTime())
                var published: TimelineState? = null
                var frameEvent = false
                when (frame.type) {
                    101 -> throw ProtocolException("Repeated ServerHello")
                    102 -> throw ProtocolException(frame.body.text(2))
                    103 -> offer(frame.body)
                    104 -> mapChunk(frame.body)
                    105 -> mapComplete(frame.body)
                    109 -> {
                        protocolRequire(mapReady.get(), "State check before map completion")
                        check(frame.body); frameEvent = true
                    }
                    else -> synchronized(lock) {
                        if (stopped.get()) return@synchronized
                        protocolRequire(mapReady.get(), "Timeline state before map completion")
                        when (frame.type) {
                            110 -> status(frame.body)
                            106 -> {
                                val header = TimelineMessages.header(frame.body)
                                val stream = known(header.streamId) ?: throw ProtocolException("Begin for ended stream")
                                protocolRequire(!stream.ended, "Begin for ended stream")
                                val epoch = stream.receiver.epoch
                                if (header.epoch >= epoch) {
                                    stream.clearVerification()
                                    if (header.kind == TimelineTransactionKind.RESET && stream.latest.get() != null) {
                                        stream.receiver.requireReset(); stream.latest.set(null)
                                    }
                                    stream.receiver.begin(header)
                                }
                            }
                            107 -> known(frame.body.number(1))?.let { stream ->
                                if (!oldEpoch(stream, frame.body.number(2))) stream.receiver.chunk(frame)
                            }
                            108 -> known(frame.body.number(1))?.let { stream ->
                                if (!oldEpoch(stream, frame.body.number(2))) {
                                    val wasWaiting = stream.receiver.waitingReset
                                    stream.clearVerification()
                                    val state = stream.receiver.commit(frame)
                                    if (state == null) {
                                        stream.latest.set(null)
                                        if (!wasWaiting) restore(stream)
                                    } else {
                                        counters.transactions.incrementAndGet(); stream.initialWaitingAt.set(0)
                                        if (!stream.closing && !stopped.get()) { stream.latest.set(state); published = state }
                                    }
                                }
                            }
                            112 -> {
                                val stream = known(frame.body.number(1)) ?: return@synchronized
                                // An ignored old End must not be presented to UI observers as an
                                // accepted completion of a newer library on the same stream.
                                if (oldEpoch(stream, frame.body.number(2))) return@synchronized
                                val reason = frame.body.number(3)
                                protocolRequire(frame.body.number(2) == stream.receiver.epoch.coerceAtLeast(1), "StreamEnd future epoch")
                                if (reason == 1L || reason == 3L) {
                                    stream.clearVerification(); stream.receiver.abort()
                                } else {
                                    val state = stream.latest.get()
                                    protocolRequire(stream.receiver.header == null && state != null, "StreamEnd before complete transaction")
                                    if (reason == 2L) {
                                        val h = checkNotNull(state).header
                                        protocolRequire(stream.mode == TimelineStreamMode.HISTORY && !stream.receiver.waitingReset &&
                                            stream.verifiedState == VerifiedState(h.epoch, h.revision) &&
                                            frame.body.number(2) == h.epoch && state.context.acceptedFrom == h.retainedFrom &&
                                            state.context.acceptedUntil == h.retainedUntil,
                                            "History ended without the verified current complete state")
                                        // End fields 4/5 describe global archive availability, not
                                        // this stream's accepted window. Preserve their wire meaning.
                                        stream.completedHistory = TimelineHistoryEndReceipt(h.streamId, h.epoch, h.revision,
                                            h.retainedFrom, h.retainedUntil)
                                    }
                                }
                                stream.ended = true; stream.closing = true; stream.initialWaitingAt.set(0)
                                if (stream.mode == TimelineStreamMode.LIVE) stream.latest.set(null)
                            }
                            111 -> {
                                val nonce = frame.body.number(1)
                                protocolRequire(nonce <= pingNonce.get(), "Pong references unsent nonce")
                                pings.remove(nonce)?.let { protocolRequire(it == frame.body.number(2), "Pong stamp differs") }
                            }
                        }
                        frameEvent = true
                    }
                }
                published?.let { state -> for (observer in observers) emit(observer, state) }
                if (frameEvent) for (observer in frameObservers) {
                    if (stopped.get()) break
                    try { observer(frame) } catch (_: Exception) { frameObservers.remove(observer) }
                }
            }
        } catch (error: IOException) { fail(error) }
        catch (error: RuntimeException) { fail(IOException("Invalid timeline state", error)) }
    }

    private fun offer(r: TimelineRecord) {
        protocolRequire(offeredKey == null && !mapReady.get(), "Repeated map offer")
        val key = r.bytes(1); offeredKey = key; offeredSize = r.number(2)
        val cached = runCatching { mapResolver(key)?.also { protocolRequire(TimelineCanonical.mapKey(it) == key, "Cache digest differs") } }.getOrNull()
        pinnedMap = cached; requestedCache = cached != null
        if (cached == null) mapBuffer = ByteArrayOutputStream(minOf(offeredSize.toInt(), 65536))
        enqueue(timelineFrame(2, tb(1, key), TimelineMessages.boolField(2, cached != null)))
    }
    private fun mapChunk(r: TimelineRecord) {
        val buffer = mapBuffer ?: throw ProtocolException("Unexpected map bytes")
        val data = r.bytes(3)
        protocolRequire(r.bytes(1) == offeredKey && r.number(2) == buffer.size().toLong() &&
            data.size <= offeredSize - buffer.size(), "Invalid map chunk")
        data.writeTo(buffer)
    }
    private fun mapComplete(r: TimelineRecord) {
        protocolRequire(!mapReady.get() && r.bytes(1) == offeredKey, "Unexpected MapComplete")
        val map = if (requestedCache) checkNotNull(pinnedMap).also { counters.hits.incrementAndGet() } else {
            val data = checkNotNull(mapBuffer)
            protocolRequire(data.size().toLong() == offeredSize, "Map incomplete")
            TimelineBinary.decodeRecord(data.toByteArray()).also {
                protocolRequire(TimelineCanonical.mapKey(it) == offeredKey, "Received map digest differs"); counters.maps.incrementAndGet()
            }
        }
        pinnedMap = map; mapBuffer = null
        synchronized(lock) { mapReady.set(true); mapStarted.set(0); deferredOpen.forEach(::enqueue); deferredOpen.clear() }
        if (!requestedCache) mapSaver.execute { runCatching { onMapReceived(map) } }
    }

    private fun status(r: TimelineRecord) {
        val request = openRequests.remove(r.number(1)) ?: throw ProtocolException("Unknown/duplicate timeline status")
        pendingOpenSince.remove(r.number(1))
        protocolRequire(r.number(4) == request.body.number(2), "Status mode differs")
        if (r.number(2) != 0L) return
        val id = r.number(3)
        protocolRequire(id > streamHighWater, "Stream ID reused")
        val mode = if (r.number(4) == 1L) TimelineStreamMode.LIVE else TimelineStreamMode.HISTORY
        if (mode == TimelineStreamMode.HISTORY) protocolRequire(r.number(5) == request.body.number(3) && r.number(6) == request.body.number(4), "History window changed")
        protocolRequire(streams.values.count { !it.ended } < 4 && (mode != TimelineStreamMode.LIVE || streams.values.none { !it.ended && it.mode == mode }), "Too many accepted streams")
        // An ended history is a bounded local cache entry, not an indefinitely growing server ID ledger.
        while (streams.size >= 4) {
            val ended = streams.entries.filter { it.value.ended }.minByOrNull { it.key } ?: throw ProtocolException("Timeline cache capacity exceeded")
            streams.remove(ended.key)
        }
        streamHighWater = id
        val context = TimelineContext(serverHello.bytes(5), serverHello.bytes(12), checkNotNull(offeredKey), checkNotNull(pinnedMap), teamId, ticksPerSecond, mode,
            maxPredictionTime = serverHello.number(9), joinedAt = serverHello.number(13), historyFrom = serverHello.number(8),
            acceptedFrom = r.number(5).takeIf { mode == TimelineStreamMode.HISTORY }, acceptedUntil = r.number(6).takeIf { mode == TimelineStreamMode.HISTORY })
        streams[id] = Stream(mode, TimelineReceiver(TimelineStore(context)))
    }

    private fun known(id: Long): Stream? = streams[id].also { protocolRequire(it != null || id <= streamHighWater, "Unknown stream ID") }
    private fun oldEpoch(stream: Stream, epoch: Long): Boolean = epoch < stream.receiver.epoch
    private fun restore(stream: Stream) {
        stream.clearVerification()
        val h = stream.receiver.store.current?.header
        val pending = stream.receiver.header
        val id = h?.streamId ?: pending?.streamId ?: streams.entries.first { it.value === stream }.key
        enqueue(timelineFrame(9, tn(1, id, true), tn(2, h?.epoch ?: 1), tn(3, h?.revision ?: 0), tn(4, 1, true)))
        counters.restores.incrementAndGet()
    }
    private fun check(r: TimelineRecord) {
        protocolRequire(r.number(4) > lastCheckId, "Check ID did not advance"); lastCheckId = r.number(4)
        val stream = known(r.number(1)) ?: return
        if (r.number(2) < stream.receiver.epoch) return
        protocolRequire(!stream.ended && stream.receiver.header == null, "Check during transaction or after end")
        val state = stream.receiver.store.current
        if (state != null && r.number(2) < state.header.epoch) return
        if (state != null && !stream.receiver.waitingReset) {
            protocolRequire(r.number(2) == state.header.epoch && r.number(3) == state.header.revision, "Check does not describe current state")
        } else {
            protocolRequire(r.number(2) == stream.receiver.epoch.coerceAtLeast(1) &&
                r.number(3) >= (state?.header?.revision ?: 1), "Check does not describe a known recovering epoch")
        }
        protocolRequire(r.number(2) > stream.checkedEpoch || (r.number(2) == stream.checkedEpoch && r.number(3) >= stream.checkedRevision), "Checked revision regressed")
        stream.checkedEpoch = r.number(2); stream.checkedRevision = r.number(3)
        counters.checks.incrementAndGet()
        val digest = if (state == null || stream.receiver.waitingReset) TimelineBytes.EMPTY else {
            val started = System.nanoTime(); TimelineCanonical.digest(state).also { counters.digestNanos.addAndGet(System.nanoTime() - started) }
        }
        if (digest.size == 0 || !TimelineCanonical.matches(digest, r.bytes(5))) {
            if (digest.size != 0) counters.mismatches.incrementAndGet()
            stream.clearVerification()
            stream.receiver.requireReset(r.number(2)); stream.latest.set(null)
        } else {
            stream.receiver.verified()
            val h = checkNotNull(state).header
            stream.verifiedState = VerifiedState(h.epoch, h.revision)
        }
        enqueue(timelineFrame(8, tn(1, r.number(1), true), tn(2, r.number(2)), tn(3, r.number(3)), tn(4, r.number(4)),
            tn(5, if (digest.size == 0) 1L else 0L, true), tb(6, digest)))
    }

    private fun write() {
        try {
            while (!stopped.get()) {
                val item = outgoing.poll(heartbeatMillis, TimeUnit.MILLISECONDS)
                val frame = item?.frame ?: run {
                    val nonce = pingNonce.incrementAndGet(); val stamp = System.nanoTime().coerceAtLeast(0)
                    pings[nonce] = stamp
                    while (pings.size > 32) pings.remove(checkNotNull(pings.keys.minOrNull()))
                    timelineFrame(7, tn(1, nonce), tn(2, stamp))
                }
                if (item?.controlEpoch != null) {
                    beforeCommandWrite?.invoke()
                    val stream = streams[frame.body.number(1)] ?: continue
                    if (stream.closing || stream.ended) continue
                    val current = stream.latest.get() ?: continue
                    if (current.header.epoch != frame.body.number(2) || current.header.controlEpoch != item.controlEpoch) continue
                }
                wire.write(frame, true)
                when (frame.type) {
                    5 -> commandsWritten.incrementAndGet()
                    6 -> selectionsWritten.incrementAndGet()
                    8 -> counters.acks.incrementAndGet()
                }
            }
        } catch (error: IOException) { fail(error) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun expire() {
        if (stopped.get()) return
        val now = System.nanoTime(); val timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val expired = (mapStarted.get().let { it != 0L && now - it >= timeout }) || now - lastInput.get() >= timeout ||
            (wire.writingSince.get().let { it != 0L && now - it >= timeout }) || pendingOpenSince.values.any { now - it >= timeout } || streams.values.any {
                val r = it.receiver
                (it.initialWaitingAt.get().let { started -> started != 0L && now - started >= timeout }) ||
                    (r.startedAt != 0L && now - r.startedAt >= timeout) || (r.recoveryStarted != 0L && now - r.recoveryStarted >= timeout)
            }
        if (expired) fail(IOException("Timeline transport deadline exceeded"))
    }
    private fun fail(error: IOException) { if (!stopped.get()) failed.compareAndSet(null, error); close() }
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { socket.close() }; outgoing.clear(); writer.interrupt(); watchdog.shutdownNow(); mapSaver.shutdownNow()
        mapReady.set(false)
        streams.values.forEach { it.latest.set(null) }; streams.clear()
        observers.clear(); frameObservers.clear(); deferredOpen.clear(); openRequests.clear(); pendingOpenSince.clear(); pings.clear()
    }
    private class StateObserver(val consumer: (TimelineState) -> Unit) {
        val seen = HashMap<Long, Pair<Long, Long>>()
    }
    private data class Outgoing(val frame: TimelineFrame, val controlEpoch: Long?)
    private data class VerifiedState(val epoch: Long, val revision: Long)
    private class Stream(val mode: TimelineStreamMode, val receiver: TimelineReceiver) {
        val latest = AtomicReference<TimelineState?>()
        val initialWaitingAt = AtomicLong(System.nanoTime())
        var checkedEpoch = 0L
        var checkedRevision = 0L
        @Volatile var closing = false
        var verifiedState: VerifiedState? = null
        var completedHistory: TimelineHistoryEndReceipt? = null
        @Volatile var ended = false
        fun clearVerification() { verifiedState = null; completedHistory = null }
    }
}
