package io.github.rwx.net.timeline

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

enum class TimelineAuthenticationPhase { ACCEPTED, TLS, CLIENT_HELLO, TOKEN, OWNER_ADMISSION, INSTALL, FIRST_SERVER_HELLO }
enum class TimelineAuthenticationFailureReason {
    ACCEPT_CAPACITY, AUTH_DEADLINE, PEER_DEADLINE, REJECTED, IO_EXCEPTION, RUNTIME_EXCEPTION, SERVER_CLOSED, CLOSED_BEFORE_HELLO,
}
data class TimelineAuthenticationFailure(
    val attemptId: Long,
    val phase: TimelineAuthenticationPhase,
    val matchedTeamId: Int?,
    val elapsedMillis: Long,
    val ownerFutureDone: Boolean?,
    val ownerFutureCancelled: Boolean?,
    val reason: TimelineAuthenticationFailureReason,
    val exceptionClass: String?,
)
data class TimelineAuthenticationStatistics(
    val acceptedAttempts: Long,
    val installedPeers: Long,
    val firstHellosWritten: Long,
    val failedBeforeFirstHello: Long,
    val activeAttempts: Int,
    val retainedFailureCapacity: Int,
    val droppedFailures: Long,
    val failures: List<TimelineAuthenticationFailure>,
)
enum class TimelinePeerCloseReason {
    PEER_INPUT_QUEUE_LIMIT, OWNER_INPUT_QUEUE_LIMIT, COMMAND_RATE_OR_SEQUENCE, SELECTION_RATE_OR_SEQUENCE,
    INPUT_FRAME_RATE_LIMIT, CONTROL_QUEUE_LIMIT, PROTOCOL_ERROR, REMOTE_EOF, READ_IO, WRITE_IO,
    WRITER_INTERRUPTED, FEED_FAILURE, INPUT_TIMEOUT, WRITE_TIMEOUT, MAP_TIMEOUT, TRANSFER_TIMEOUT,
    RECOVERY_TIMEOUT, STATE_ACK_TIMEOUT, SERVER_SHUTDOWN, REQUESTED_DISCONNECT, UNCLASSIFIED_CLOSE,
}
data class TimelinePeerClosure(
    val teamId: Int,
    val generation: Long,
    val reason: TimelinePeerCloseReason,
    val exceptionClass: String?,
    val elapsedMillis: Long,
    val peerQueuedCountAtCause: Int,
    val ownerQueueSizeAtRecord: Int,
    val wire: TimelineWireStatistics,
)
data class TimelinePeerClosureStatistics(val closedAfterHello: Long, val retainedCapacity: Int,
                                        val droppedClosures: Long, val closures: List<TimelinePeerClosure>)

/** Independent v7 listener. Feed/encoder and socket work never run on the simulation owner. */
class TimelineServer(
    bindAddress: InetAddress,
    port: Int,
    seats: Map<String, TimelineSeat>,
    val mapRecord: TimelineRecord,
    val matchId: TimelineBytes,
    private val feedFactory: TimelineFeedFactory,
    private val serverTime: () -> Long = { 0L },
    private val historyUntil: (Int) -> Long = { serverTime() },
    private val ticksPerSecond: Int = 60,
    tlsContext: SSLContext? = null,
    private val admissionProvider: TimelineAdmissionProvider? = null,
) : AutoCloseable {
    private val seatTokens = ConcurrentHashMap<String, SeatGrant>().apply {
        seats.forEach { (token, seat) -> put(token, newGrant(token, seat)) }
    }
    private val map = TimelineBinary.encodeRecord(mapRecord)
    private val mapKey = TimelineCanonical.mapKey(mapRecord)
    private val stopped = AtomicBoolean()
    private val peers = ConcurrentHashMap<Int, Peer>()
    // Closed writers retain one bounded cleanup slot until their own feed cleanup returns.
    private val retiring = ConcurrentHashMap<Long, Peer>()
    private val accepted = ConcurrentHashMap<Socket, AuthenticationAttempt>()
    private val authenticationSequence = AtomicLong()
    private val installedPeers = AtomicLong(); private val firstHellosWritten = AtomicLong()
    private val authenticationLock = Any()
    private val authenticationFailures = ArrayDeque<TimelineAuthenticationFailure>()
    private var failedBeforeFirstHello = 0L; private var droppedAuthenticationFailures = 0L
    private val peerClosureLock = Any()
    private val peerClosures = ArrayDeque<TimelinePeerClosure>()
    private var closedAfterHello = 0L; private var droppedPeerClosures = 0L
    private val requests = ArrayBlockingQueue<Queued>(4096)
    private val generation = AtomicLong()
    private val listener = tlsContext?.serverSocketFactory?.createServerSocket() ?: ServerSocket()
    private val admissionLock = Any()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "RWXX-timeline-watchdog").apply { isDaemon = true } }
    private val acceptor: Thread
    @Volatile internal var timeoutMillis = 10_000L
    @Volatile internal var checkIntervalMillis = 1_000L
    @Volatile internal var beforeCommitWrite: (() -> Unit)? = null
    val localPort: Int get() = listener.localPort
    internal val pendingRequestCount: Int get() = requests.size

    init {
        try {
            require(bindAddress.isLoopbackAddress || tlsContext != null) { "Remote timeline listeners require TLS" }
            require(port in 0..65535 && seats.size in 1..64 && seats.values.map { it.teamId }.toSet().size == seats.size)
            require(seats.keys.all { it.matches(Regex("[A-Za-z0-9_-]{32,128}")) })
            require(matchId.size == 16 && ticksPerSecond in 1..1000)
            seats.values.forEach { require(it.teamId in 0..63 && it.accessId.size == 16 && it.joinedAt >= 0 && it.historyFrom >= it.joinedAt && it.admission in 1..2) }
            listener.reuseAddress = true; listener.bind(InetSocketAddress(bindAddress, port))
        } catch (error: Exception) { listener.close(); watchdog.shutdownNow(); throw error }
        acceptor = Thread(::accept, "RWXX-timeline-accept").apply { isDaemon = true; start() }
        watchdog.scheduleAtFixedRate(::expire, 100, 100, TimeUnit.MILLISECONDS)
    }

    /** Room owner publishes admission only; transfer/reassignment must explicitly disconnect an old peer. */
    fun authorizeSeat(token: String, seat: TimelineSeat) {
        require(token.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        require(seat.teamId in 0..63 && seat.accessId.size == 16 && seat.joinedAt >= 0 &&
            seat.historyFrom >= seat.joinedAt && seat.admission in 1..2)
        val replaced = synchronized(admissionLock) {
            require(!stopped.get()) { "Server is closed" }
            val replacing = seatTokens.containsKey(token) || seatTokens.values.any { it.seat.teamId == seat.teamId }
            require(replacing || seatTokens.size < 64) { "Seat registry is full" }
            val removed = buildList {
                val iterator = seatTokens.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key == token || entry.value.seat.teamId == seat.teamId) add(entry.value)
                }
            }
            seatTokens.entries.removeIf { it.key != token && it.value.seat.teamId == seat.teamId }
            seatTokens[token] = newGrant(token, seat)
            removed
        }
        replaced.forEach { it.pending?.cancel() }
    }
    fun revokeSeat(token: String) { synchronized(admissionLock) { seatTokens.remove(token) }?.pending?.cancel() }
    fun connectedTeamIds(): Set<Int> = concurrentSnapshot(peers.keys).toSet()
    fun connectionGeneration(teamId: Int): Long? = peers[teamId]?.generation
    fun signal(teamId: Int, connectionGeneration: Long? = null) {
        peers[teamId]?.takeIf { connectionGeneration == null || it.generation == connectionGeneration }?.wake()
    }
    fun disconnect(teamId: Int, connectionGeneration: Long? = null) {
        peers[teamId]?.takeIf { connectionGeneration == null || it.generation == connectionGeneration }?.let {
            it.markClose(TimelinePeerCloseReason.REQUESTED_DISCONNECT); it.close()
        }
    }
    fun committedState(teamId: Int, streamId: Long, connectionGeneration: Long? = null): TimelineState? =
        peers[teamId]?.takeIf { connectionGeneration == null || it.generation == connectionGeneration }?.streams?.get(streamId)?.committed?.get()
    fun wireStatistics(teamId: Int): TimelineWireStatistics? = peers[teamId]?.counters?.snapshot()

    /** Bounded immutable metadata only; never records credentials, message bodies or error text. */
    fun authenticationStatistics(): TimelineAuthenticationStatistics = synchronized(authenticationLock) {
        TimelineAuthenticationStatistics(authenticationSequence.get(), installedPeers.get(), firstHellosWritten.get(),
            failedBeforeFirstHello, accepted.size, 64, droppedAuthenticationFailures, frozenList(authenticationFailures.toList()))
    }
    fun peerClosureStatistics(): TimelinePeerClosureStatistics = synchronized(peerClosureLock) {
        TimelinePeerClosureStatistics(closedAfterHello, 64, droppedPeerClosures, frozenList(peerClosures.toList()))
    }

    /** Owner thread only. No callback is invoked by the socket reader or writer. */
    fun drainRequests(consume: (TimelineRequest) -> Unit) {
        repeat(256) {
            val queued = requests.poll() ?: return
            queued.peer.queued.decrementAndGet()
            val request = queued.request
            val stream = queued.peer.streams[request.frame.body.number(1)]
            val currentState = stream?.committed?.get()
            val current = currentState?.header
            if (peers[request.teamId] === queued.peer && !queued.peer.closed.get() && stream != null && !stream.synchronizing.get() &&
                current != null && current.epoch == request.receipt.epoch && current.controlEpoch == request.receipt.controlEpoch &&
                System.nanoTime() - request.receipt.sentAtNanos <= TimeUnit.SECONDS.toNanos(2) &&
                stillDisclosed(request.frame, checkNotNull(currentState))) consume(request)
        }
    }

    private fun stillDisclosed(frame: TimelineFrame, state: TimelineState): Boolean {
        fun active(handle: Long, kind: TimelineObjectKind): Boolean = state.objects[handle]?.let {
            it.kind == kind && it.close == null && it.observedFrom <= state.header.observedThrough
        } == true
        val r = frame.body
        if (TimelineMessages.selection(r.bytes(if (frame.type == 5) 7 else 5), frame.type == 6).any { !active(it, TimelineObjectKind.UNIT) }) return false
        if (frame.type == 5) {
            if (r.number(8) != 0L && !active(r.number(8), TimelineObjectKind.ACTION)) return false
            val target = r.record(9)
            if (target.number(1) == 2L && !active(target.number(4), TimelineObjectKind.UNIT)) return false
        }
        return true
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        accepted.values.forEach { it.reason.compareAndSet(null, TimelineAuthenticationFailureReason.SERVER_CLOSED) }
        runCatching { listener.close() }; concurrentSnapshot(peers.values).forEach { it.markClose(TimelinePeerCloseReason.SERVER_SHUTDOWN); it.close() }
        concurrentSnapshot(seatTokens.values).forEach { it.pending?.cancel() }
        concurrentSnapshot(accepted.keys).forEach { runCatching { it.close() } }; requests.clear(); watchdog.shutdownNow()
        if (Thread.currentThread() !== acceptor) acceptor.join(1000)
    }

    private fun accept() {
        while (!stopped.get()) {
            val socket = try { listener.accept() } catch (_: IOException) { break }
            val attempt = AuthenticationAttempt(authenticationSequence.incrementAndGet(), System.nanoTime())
            if (accepted.size >= 80) {
                attempt.reason.set(TimelineAuthenticationFailureReason.ACCEPT_CAPACITY)
                recordAuthenticationFailure(attempt); socket.close(); continue
            }
            accepted[socket] = attempt
            Thread({ authenticate(socket, attempt) }, "RWXX-timeline-reader").apply { isDaemon = true; start() }
        }
    }

    private fun authenticate(socket: Socket, attempt: AuthenticationAttempt) {
        var peer: Peer? = null
        var admission: PendingAdmission? = null
        var chosen: SeatGrant? = null
        try {
            val acceptedAt = accepted[socket]?.startedAt ?: return
            socket.tcpNoDelay = true; socket.soTimeout = 10_000; socket.sendBufferSize = 64 * 1024
            attempt.phase = TimelineAuthenticationPhase.TLS
            if (socket is SSLSocket) socket.startHandshake()
            val counters = TimelineCounters(); val wire = TimelineSocket(socket, counters)
            attempt.phase = TimelineAuthenticationPhase.CLIENT_HELLO
            val hello = wire.read(true)
            protocolRequire(hello.type == 1, "Expected ClientHello")
            val supplied = hello.body.text(3).toByteArray(Charsets.US_ASCII)
            var selectedToken: String? = null
            var selectedGrant: SeatGrant? = null
            attempt.phase = TimelineAuthenticationPhase.TOKEN
            seatTokens.forEach { (token, grant) -> if (MessageDigest.isEqual(grant.token, supplied)) { selectedToken = token; selectedGrant = grant } }
            selectedGrant?.let { attempt.matchedTeam.set(it.seat.teamId) }
            synchronized(admissionLock) {
                val grant = selectedGrant
                val selected = grant?.seat
                if (grant != null && selected != null && selectedToken?.let { seatTokens[it] } === grant && !stopped.get() && !socket.isClosed &&
                    System.nanoTime() - acceptedAt < TimeUnit.MILLISECONDS.toNanos(timeoutMillis) &&
                    !peers.containsKey(selected.teamId) && retiring.size < 64 &&
                    retiring.values.count { it.seat.teamId == selected.teamId } < 2) {
                    chosen = grant
                    val resolved = grant.resolved
                    if (resolved != null) {
                        peer = installPeer(grant, resolved, socket, wire, counters, attempt)
                    } else if (grant.pending == null) {
                        admission = PendingAdmission(socket).also { grant.pending = it }
                    }
                }
            }
            val waiting = admission
            if (waiting != null) {
                val grant = checkNotNull(chosen)
                attempt.phase = TimelineAuthenticationPhase.OWNER_ADMISSION
                val future = checkNotNull(admissionProvider).admit(grant.seat)
                attempt.future.set(future)
                waiting.attach(future)
                val resolved = awaitAdmission(waiting, future, acceptedAt, attempt)
                protocolRequire(resolved.teamId == grant.seat.teamId && resolved.accessId == grant.seat.accessId &&
                    resolved.joinedAt in 0..TimelineProtocol.MAX_TIME && resolved.historyFrom in resolved.joinedAt..TimelineProtocol.MAX_TIME,
                    "Admission changed reserved identity or returned invalid bounds")
                synchronized(admissionLock) {
                    if (!waiting.cancelled.get() && !stopped.get() && !socket.isClosed &&
                        selectedToken?.let { seatTokens[it] } === grant && grant.pending === waiting &&
                        !peers.containsKey(resolved.teamId) && retiring.size < 64 &&
                        retiring.values.count { it.seat.teamId == resolved.teamId } < 2 &&
                        System.nanoTime() - acceptedAt < TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) {
                        grant.resolved = resolved.copy(admission = 1)
                        grant.pending = null
                        peer = installPeer(grant, checkNotNull(grant.resolved), socket, wire, counters, attempt)
                    }
                }
            }
            val authenticated = peer
            if (authenticated == null) {
                attempt.reason.compareAndSet(null, if (System.nanoTime() - acceptedAt >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
                    TimelineAuthenticationFailureReason.AUTH_DEADLINE else TimelineAuthenticationFailureReason.REJECTED)
                wire.write(timelineFrame(102, tn(1, 2, true), TimelineMessages.textField(2, "Unable to join this room")), false)
                return
            }
            accepted.remove(socket); socket.soTimeout = 0
            authenticated.startWriter()
            authenticated.read()
        } catch (error: IOException) {
            peer?.markClose(when (error) {
                is ProtocolException -> TimelinePeerCloseReason.PROTOCOL_ERROR
                is java.io.EOFException -> TimelinePeerCloseReason.REMOTE_EOF
                else -> TimelinePeerCloseReason.READ_IO
            }, error.javaClass.name)
            attempt.failed(TimelineAuthenticationFailureReason.IO_EXCEPTION, error)
            // There is no fallback parser, legacy packet or engine callback on this path.
        } catch (error: RuntimeException) {
            peer?.markClose(TimelinePeerCloseReason.PROTOCOL_ERROR, error.javaClass.name)
            attempt.failed(TimelineAuthenticationFailureReason.RUNTIME_EXCEPTION, error)
            // A rejected/failed owner request closes only this authentication attempt.
        } finally {
            // Capture before this finally cancels its own outstanding owner future.
            attempt.captureFutureStatus()
            admission?.let { pending ->
                if (peer == null) pending.cancel()
                synchronized(admissionLock) { if (chosen?.pending === pending) chosen?.pending = null }
            }
            peer?.close(); accepted.remove(socket); runCatching { socket.close() }
            peer?.recordClosure()
            // An installed peer's writer owns the first-Hello result, avoiding a race with
            // this reader's EOF. No I/O/callback is performed when recording this metadata.
            if (peer?.writerStarted?.get() != true) recordAuthenticationFailure(attempt)
        }
    }

    /** Called under admissionLock only; no user callback or engine operation. */
    private fun installPeer(grant: SeatGrant, resolved: TimelineSeat, socket: Socket, wire: TimelineSocket,
                            counters: TimelineCounters, attempt: AuthenticationAttempt): Peer {
        attempt.phase = TimelineAuthenticationPhase.INSTALL
        val seat = if (admissionProvider == null) resolved else resolved.copy(admission = if (grant.connected) 2 else 1)
        grant.connected = true
        return Peer(seat, generation.incrementAndGet(), socket, wire, counters, attempt).also {
            peers[seat.teamId] = it; installedPeers.incrementAndGet()
        }
    }

    private fun awaitAdmission(pending: PendingAdmission, future: CompletableFuture<TimelineSeat>, acceptedAt: Long,
                               attempt: AuthenticationAttempt): TimelineSeat {
        while (true) {
            if (stopped.get() || pending.cancelled.get() || pending.socket.isClosed) throw IOException("Admission was cancelled")
            val remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - acceptedAt)
            if (remaining <= 0) {
                attempt.reason.compareAndSet(null, TimelineAuthenticationFailureReason.AUTH_DEADLINE)
                throw IOException("Admission deadline exceeded")
            }
            try { return future.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS) }
            catch (_: TimeoutException) { /* Poll only this attempt's cancellation; never renew the deadline. */ }
            catch (error: ExecutionException) { throw IOException("Admission was rejected", error.cause) }
            catch (error: CancellationException) { throw IOException("Admission was cancelled", error) }
            catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Admission was interrupted", error) }
        }
    }

    private fun expire() {
        val now = System.nanoTime(); val timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        accepted.forEach { (socket, attempt) -> if (now - attempt.startedAt >= timeout) {
            attempt.reason.compareAndSet(null, TimelineAuthenticationFailureReason.AUTH_DEADLINE)
            runCatching { socket.close() }
        } }
        peers.values.forEach { peer ->
            val reason = if (now - peer.lastInput.get() >= timeout) TimelinePeerCloseReason.INPUT_TIMEOUT else peer.timeoutReason(now, timeout)
            if (reason != null) {
                peer.markClose(reason)
                peer.markAuthenticationDeadline(); peer.close()
            }
        }
    }

    private inner class Peer(val seat: TimelineSeat, val generation: Long, val socket: Socket,
                             val wire: TimelineSocket, val counters: TimelineCounters, private val authentication: AuthenticationAttempt) {
        val writerStarted = AtomicBoolean()
        private val createdAt = System.nanoTime()
        private val closeCause = AtomicReference<PeerCloseCause?>()
        private val closureRecorded = AtomicBoolean()
        val closed = AtomicBoolean(); val queued = AtomicInteger(); val lastInput = AtomicLong(System.nanoTime())
        val streams = ConcurrentHashMap<Long, Stream>()
        private val controls = ArrayBlockingQueue<TimelineFrame>(32)
        private val mapHave = AtomicInteger(-1)
        private val mapReady = AtomicBoolean(); private val mapStarted = AtomicLong()
        private val wakeup = Object(); private val sentLock = Any()
        @Volatile private var streamHighWater = 0L
        private var lastRequest = 0L; private var lastNonce = 0L
        private var lastCommand = 0L; private var lastSelect = 0L; private var checkId = 0L
        private val session = TimelineBytes(ByteArray(16).also(SecureRandom()::nextBytes))
        fun wake() = synchronized(wakeup) { wakeup.notifyAll() }
        fun markAuthenticationDeadline() {
            if (!authentication.helloWritten.get()) authentication.reason.compareAndSet(null, TimelineAuthenticationFailureReason.PEER_DEADLINE)
        }
        fun timeoutReason(now: Long, timeout: Long): TimelinePeerCloseReason? {
            if (wire.writingSince.get().let { it != 0L && now - it >= timeout }) return TimelinePeerCloseReason.WRITE_TIMEOUT
            if (mapStarted.get().let { it != 0L && now - it >= timeout }) return TimelinePeerCloseReason.MAP_TIMEOUT
            return streams.values.firstNotNullOfOrNull { it.timeoutReason(now, timeout) }
        }
        fun markClose(reason: TimelinePeerCloseReason, exceptionClass: String? = null, queuedAtCause: Int = queued.get()) {
            closeCause.compareAndSet(null, PeerCloseCause(reason, exceptionClass, System.nanoTime(), queuedAtCause))
        }
        private fun requirePeer(condition: Boolean, reason: TimelinePeerCloseReason, message: String) {
            if (!condition) {
                val error = ProtocolException(message); markClose(reason, error.javaClass.name); throw error
            }
        }
        fun recordClosure() {
            if (!authentication.helloWritten.get() || !closed.get() || !closureRecorded.compareAndSet(false, true)) return
            val cause = closeCause.get() ?: PeerCloseCause(TimelinePeerCloseReason.UNCLASSIFIED_CLOSE, null, System.nanoTime(), queued.get())
            val value = TimelinePeerClosure(seat.teamId, generation, cause.reason, cause.exceptionClass,
                TimeUnit.NANOSECONDS.toMillis((cause.at - createdAt).coerceAtLeast(0)), cause.queued,
                requests.size, counters.snapshot())
            synchronized(peerClosureLock) {
                closedAfterHello++
                if (peerClosures.size == 64) { peerClosures.removeFirst(); droppedPeerClosures++ }
                peerClosures.addLast(value)
            }
        }

        fun read() {
            var window = System.nanoTime(); var frames = 0; var commands = 0
            val selections = ArrayDeque<Long>()
            while (!closed.get() && !stopped.get()) {
                val frame = wire.read(true); val now = System.nanoTime(); lastInput.set(now)
                if (now - window >= TimeUnit.SECONDS.toNanos(1)) { window = now; frames = 0; commands = 0 }
                requirePeer(++frames <= 120, TimelinePeerCloseReason.INPUT_FRAME_RATE_LIMIT, "Input frame rate exceeded")
                if (frame.type != 2 && frame.type != 7) synchronized(sentLock) { protocolRequire(mapReady.get(), "Map handshake incomplete") }
                when (frame.type) {
                    1 -> throw ProtocolException("Repeated ClientHello")
                    2 -> {
                        protocolRequire(mapStarted.get() != 0L && frame.body.bytes(1) == mapKey && mapHave.compareAndSet(-1, if (frame.body.boolean(2)) 1 else 0), "Unexpected MapHave")
                        wake()
                    }
                    7 -> {
                        protocolRequire(frame.body.number(1) > lastNonce, "Ping nonce did not advance")
                        lastNonce = frame.body.number(1); control(frame)
                    }
                    3 -> { protocolRequire(frame.body.number(1) > lastRequest, "Open request ID did not advance"); lastRequest = frame.body.number(1); control(frame) }
                    4, 10 -> control(frame)
                    8 -> acknowledge(frame)
                    9 -> synchronized(sentLock) {
                        stream(frame.body.number(1))?.let { s ->
                            protocolRequire(frame.body.number(3) <= (s.committed.get()?.header?.revision ?: 0), "Restore claims a future revision")
                            s.requestReset(frame.body.number(2), now)
                        }
                    }
                    5, 6 -> {
                        if (frame.type == 5) { requirePeer(++commands <= 60 && frame.body.number(3) > lastCommand,
                            TimelinePeerCloseReason.COMMAND_RATE_OR_SEQUENCE, "Invalid command sequence/rate"); lastCommand = frame.body.number(3) }
                        else {
                            while (selections.isNotEmpty() && now - selections.first >= TimeUnit.SECONDS.toNanos(1)) selections.removeFirst()
                            requirePeer(selections.size < 8 && frame.body.number(3) > lastSelect,
                                TimelinePeerCloseReason.SELECTION_RATE_OR_SEQUENCE, "Invalid selection sequence/rate")
                            lastSelect = frame.body.number(3); selections.addLast(now)
                        }
                        authorize(frame, now)?.let { receipt ->
                            val count = queued.incrementAndGet()
                            if (count > 64 || !requests.offer(Queued(this, TimelineRequest(seat.teamId, generation, frame, receipt)))) {
                                val error = ProtocolException("Owner request queue exceeded")
                                markClose(if (count > 64) TimelinePeerCloseReason.PEER_INPUT_QUEUE_LIMIT else TimelinePeerCloseReason.OWNER_INPUT_QUEUE_LIMIT,
                                    error.javaClass.name, count)
                                queued.decrementAndGet(); throw error
                            }
                        }
                    }
                }
            }
        }

        private fun control(frame: TimelineFrame) { requirePeer(controls.offer(frame), TimelinePeerCloseReason.CONTROL_QUEUE_LIMIT, "Control queue exceeded"); wake() }
        private fun stream(id: Long): Stream? = streams[id].also { protocolRequire(it != null || id <= streamHighWater, "Unknown stream ID") }
        private fun authorize(frame: TimelineFrame, now: Long): TimelineReceipt? = synchronized(sentLock) {
            val r = frame.body; val s = stream(r.number(1)) ?: return@synchronized null
            val current = s.committed.get() ?: return@synchronized null
            if (s.context.mode != TimelineStreamMode.LIVE || s.synchronizing.get() || r.number(2) != current.header.epoch) return@synchronized null
            val receipt = s.receipts.firstOrNull { it.revision == r.number(4) && it.epoch == r.number(2) } ?: return@synchronized null
            if (now - receipt.sentAtNanos > TimeUnit.SECONDS.toNanos(2) || receipt.controlEpoch != current.header.controlEpoch) return@synchronized null
            val latest = s.receipts.lastOrNull() ?: return@synchronized null
            val units = TimelineMessages.selection(r.bytes(if (frame.type == 5) 7 else 5), frame.type == 6)
            if (units.any { !receipt.containsUnit(it) || !latest.containsUnit(it) }) return@synchronized null
            if (frame.type == 5) {
                if (r.number(5) != current.header.controlEpoch) return@synchronized null
                if (r.number(8) != 0L && (!receipt.containsAction(r.number(8)) || !latest.containsAction(r.number(8)))) return@synchronized null
                val target = r.record(9)
                if (target.number(1) == 2L && (!receipt.containsUnit(target.number(4)) || !latest.containsUnit(target.number(4)))) return@synchronized null
            }
            receipt
        }

        private fun acknowledge(frame: TimelineFrame) = synchronized(sentLock) {
            val r = frame.body; val s = stream(r.number(1)) ?: return@synchronized
            val current = s.committed.get()?.header ?: throw ProtocolException("ACK before state")
            if (r.number(2) < current.epoch) return@synchronized
            protocolRequire(r.number(2) == current.epoch && r.number(4) <= checkId, "ACK references future epoch/check")
            val expected = s.checks.remove(r.number(4)) ?: run {
                protocolRequire(streams.values.none { it !== s && it.checks.containsKey(r.number(4)) }, "ACK references another stream")
                return@synchronized
            }
            protocolRequire(expected.revision == r.number(3), "ACK revision differs")
            if (r.number(5) == 0L && TimelineCanonical.matches(expected.digest, r.bytes(6))) {
                s.unconfirmedSince.set(0); s.failures = 0
                if (s.context.mode == TimelineStreamMode.HISTORY) s.historyConfirmed.set(true)
            } else { counters.mismatches.incrementAndGet(); s.requestReset(current.epoch, System.nanoTime(), implicit = true) }
            wake()
        }

        fun startWriter() {
            val writer = Thread({
            try {
                authentication.phase = TimelineAuthenticationPhase.FIRST_SERVER_HELLO
                val now = serverTime()
                wire.write(timelineFrame(101, tn(1, 1, true), tn(2, seat.teamId.toLong(), true), tn(3, ticksPerSecond.toLong(), true), tn(4, 1024, true),
                    tb(5, matchId), tb(6, session), tn(7, now), tn(8, seat.historyFrom), tn(9, ticksPerSecond.toLong() * 1024), tn(10, 30, true), tn(11, 100, true),
                    tb(12, seat.accessId), tn(13, seat.joinedAt), tn(14, seat.admission.toLong(), true)), false)
                authentication.helloWritten.set(true); firstHellosWritten.incrementAndGet()
                mapStarted.set(System.nanoTime())
                wire.write(timelineFrame(103, tb(1, mapKey), tn(2, map.size.toLong())), false)
                while (mapHave.get() == -1 && !closed.get()) synchronized(wakeup) { wakeup.wait(10) }
                if (closed.get()) return@Thread
                if (mapHave.get() == 0) {
                    val bytes = map.toByteArray(); var at = 0
                    while (at < bytes.size && !closed.get()) {
                        val end = minOf(at + TimelineProtocol.MAX_CHUNK_BYTES, bytes.size)
                        wire.write(timelineFrame(104, tb(1, mapKey), tn(2, at.toLong()), tb(3, TimelineBytes(bytes.copyOfRange(at, end)))), false); at = end
                    }
                }
                synchronized(sentLock) {
                    wire.write(timelineFrame(105, tb(1, mapKey)), false); mapReady.set(true); mapStarted.set(0)
                }
                while (!closed.get() && !stopped.get()) {
                    repeat(32) { controls.poll()?.let(::handleControl) }
                    var progress = false
                    for (s in concurrentSnapshot(streams.values).sortedBy { it.id }) {
                        if (s.historyConfirmed.get()) { end(s, 2); progress = true; continue }
                        progress = s.turn() || progress
                    }
                    if (!progress) synchronized(wakeup) { if (!closed.get()) wakeup.wait(2) }
                }
            } catch (error: IOException) {
                markClose(if (error is ProtocolException) TimelinePeerCloseReason.PROTOCOL_ERROR else TimelinePeerCloseReason.WRITE_IO, error.javaClass.name)
                authentication.failed(TimelineAuthenticationFailureReason.IO_EXCEPTION, error)
            }
            catch (error: InterruptedException) { markClose(TimelinePeerCloseReason.WRITER_INTERRUPTED, error.javaClass.name); Thread.currentThread().interrupt() }
            catch (error: RuntimeException) {
                markClose(TimelinePeerCloseReason.PROTOCOL_ERROR, error.javaClass.name)
                authentication.failed(TimelineAuthenticationFailureReason.RUNTIME_EXCEPTION, error)
            }
            finally {
                close()
                recordClosure()
                authentication.captureFutureStatus(); recordAuthenticationFailure(authentication)
                try { concurrentSnapshot(streams.values).forEach { runCatching { it.feed.close() } }; streams.clear() }
                finally { retiring.remove(generation, this) }
            }
            }, "RWXX-timeline-writer-${seat.teamId}").apply { isDaemon = true }
            writer.start(); writerStarted.set(true)
        }

        private fun handleControl(frame: TimelineFrame) {
            val r = frame.body
            when (frame.type) {
                7 -> wire.write(timelineFrame(111, tn(1, r.number(1)), tn(2, r.number(2)), tn(3, serverTime())), false)
                3 -> {
                    val mode = if (r.number(2) == 1L) TimelineStreamMode.LIVE else TimelineStreamMode.HISTORY
                    val lo = r.number(3); val hi = r.number(4)
                    val unavailable = mode == TimelineStreamMode.HISTORY && (lo < seat.historyFrom || hi > historyUntil(seat.teamId) || hi - lo > ticksPerSecond.toLong() * 1024 * 120)
                    if (streams.size >= 4 || (mode == TimelineStreamMode.LIVE && streams.values.any { it.context.mode == mode }) || unavailable) {
                        status(r.number(1), if (unavailable) 1 else 2, 0, mode, 0, 0, 0); return
                    }
                    protocolRequire(streamHighWater < TimelineProtocol.MAX_U32, "Stream IDs exhausted")
                    val id = ++streamHighWater
                    val context = TimelineContext(matchId, seat.accessId, mapKey, mapRecord, seat.teamId, ticksPerSecond, mode,
                        joinedAt = seat.joinedAt, historyFrom = seat.historyFrom,
                        acceptedFrom = lo.takeIf { mode == TimelineStreamMode.HISTORY }, acceptedUntil = hi.takeIf { mode == TimelineStreamMode.HISTORY })
                    val request = TimelineStreamRequest(seat.teamId, generation, r.number(1), id, context, r.number(5))
                    val feed = try { feedFactory.open(request) }
                    catch (error: Exception) { markClose(TimelinePeerCloseReason.FEED_FAILURE, error.javaClass.name); throw error }
                    if (feed == null) { status(r.number(1), 1, 0, mode, 0, 0, 0); return }
                    streams[id] = Stream(request, feed)
                }
                4 -> stream(r.number(1))?.let { end(it, 1) }
                10 -> stream(r.number(1))?.let { it.budget = r.number(2) }
            }
        }

        private fun status(request: Long, code: Int, id: Long, mode: TimelineStreamMode, lo: Long, hi: Long, budget: Long) =
            wire.write(timelineFrame(110, tn(1, request, true), tn(2, code.toLong(), true), tn(3, id, true), tn(4, mode.code.toLong(), true),
                tn(5, lo), tn(6, hi), tn(7, budget, true), tn(8, seat.historyFrom), tn(9, historyUntil(seat.teamId))), false)
        private fun end(s: Stream, reason: Long) {
            val epoch = s.sendingEpoch()
            wire.write(timelineFrame(112, tn(1, s.id, true), tn(2, epoch), tn(3, reason, true), tn(4, seat.historyFrom), tn(5, historyUntil(seat.teamId))), false)
            streams.remove(s.id, s); s.feed.close()
        }

        inner class Stream(val request: TimelineStreamRequest, val feed: TimelineFeed) {
            val id = request.streamId; val context = request.context
            val committed = AtomicReference<TimelineState?>(); val synchronizing = AtomicBoolean(true)
            val unconfirmedSince = AtomicLong(); val historyConfirmed = AtomicBoolean()
            val receipts = ArrayDeque<TimelineReceipt>(); val checks = linkedMapOf<Long, Expected>()
            private val store = TimelineStore(context)
            private val reset = AtomicBoolean(true); private val recoverySince = AtomicLong(System.nanoTime())
            private val transferSince = AtomicLong(); private var transfer: Sending? = null
            var failures = 0; private var lastRestore = Long.MIN_VALUE; private var lastCheck = 0L
            private var historyCheckSent = false; private var announced = false
            var budget = request.budgetBytesPerSecond; private var nextWriteAt = 0L
            fun sendingEpoch(): Long = transfer?.publication?.header?.epoch ?: committed.get()?.header?.epoch ?: 1L
            fun timeoutReason(now: Long, timeout: Long): TimelinePeerCloseReason? {
                val recovery = recoverySince.get(); val transfer = transferSince.get(); val unconfirmed = unconfirmedSince.get()
                if (recovery != 0L && now - recovery >= timeout) return TimelinePeerCloseReason.RECOVERY_TIMEOUT
                if (transfer != 0L && now - transfer >= timeout) return TimelinePeerCloseReason.TRANSFER_TIMEOUT
                if (unconfirmed != 0L && now - unconfirmed >= timeout) return TimelinePeerCloseReason.STATE_ACK_TIMEOUT
                return null
            }

            fun requestReset(epoch: Long, now: Long, implicit: Boolean = false) = synchronized(sentLock) {
                val current = committed.get()?.header ?: return@synchronized
                if (epoch < current.epoch) return@synchronized
                protocolRequire(epoch == current.epoch, "Restore future epoch")
                if (synchronizing.get()) return@synchronized
                if (!implicit && lastRestore != Long.MIN_VALUE && now - lastRestore < TimeUnit.SECONDS.toNanos(1)) return@synchronized
                protocolRequire(++failures <= 3, "Repeated restoration requests")
                lastRestore = now; synchronizing.set(true); reset.set(true); checks.clear(); receipts.clear()
                if (recoverySince.get() == 0L) recoverySince.set(now)
                counters.restores.incrementAndGet(); wake()
            }

            fun turn(): Boolean {
                val now = System.nanoTime()
                if (now < nextWriteAt) return false
                var sending = transfer
                if (sending == null) {
                    if (check(now)) return true
                    val old = committed.get(); val isReset = reset.get()
                    if (isReset && old?.header?.epoch == Long.MAX_VALUE) { end(this, 3); return true }
                    val cursor = TimelineSendCursor(context, id, if (isReset) (old?.header?.epoch ?: 0) + 1 else checkNotNull(old).header.epoch, old, isReset)
                    val publication = try { feed.next(cursor) } catch (required: TimelineResetRequired) {
                        if (isReset) throw ProtocolException("Feed cannot provide required RESET", required)
                        requestReset(checkNotNull(old).header.epoch, now, true); return true
                    }
                    catch (error: Exception) { markClose(TimelinePeerCloseReason.FEED_FAILURE, error.javaClass.name); throw error }
                    if (publication != null) {
                        val h = publication.header
                        protocolRequire(h.streamId == id && h.epoch == cursor.epoch &&
                            (h.kind == TimelineTransactionKind.RESET) == isReset, "Feed returned an unexpected stream identity/kind")
                        val prepared = store.prepare(h, publication.operations)
                        if (!announced) { status(request.requestId, 0, id, context.mode, h.retainedFrom, h.retainedUntil, budget); announced = true }
                        sending = Sending(publication, prepared); transfer = sending; transferSince.set(now)
                    } else return check(now)
                }
                val active = checkNotNull(sending)
                val h = active.publication.header
                val frame = when {
                    !active.begun -> h.beginFrame().also { active.begun = true }
                    active.offset < active.publication.operations.size -> {
                        val quantum = minOf(TimelineProtocol.MAX_CHUNK_BYTES, maxOf(256, (budget / 100).toInt()))
                        val start = active.offset; val end = minOf(start + quantum, active.publication.operations.size)
                        active.offset = end
                        timelineFrame(107, tn(1, id, true), tn(2, h.epoch), tn(3, h.revision), tn(4, start.toLong()),
                            tb(5, TimelineBytes(active.operations.copyOfRange(start, end))))
                    }
                    else -> timelineFrame(108, tn(1, id, true), tn(2, h.epoch), tn(3, h.revision))
                }
                if (frame.type == 108) {
                    beforeCommitWrite?.invoke()
                    synchronized(sentLock) {
                        wire.write(frame, false); active.prepared.commit(); committed.set(active.prepared.state)
                        addReceipt(active.prepared.state)
                        if (h.kind == TimelineTransactionKind.RESET) {
                            synchronizing.set(false); reset.set(false); recoverySince.set(0); historyCheckSent = false; lastCheck = now
                        }
                        counters.transactions.incrementAndGet(); transfer = null; transferSince.set(0)
                        if (lastCheck == 0L) lastCheck = now
                    }
                } else wire.write(frame, false)
                nextWriteAt = System.nanoTime() + (12L + TimelineBinary.recordSize(frame.body)) * 1_000_000_000L / budget
                return true
            }

            private fun addReceipt(state: TimelineState) {
                val h = state.header
                val active = state.objects.values.filter { it.close == null && it.observedFrom <= h.observedThrough }
                val receipt = TimelineReceipt(h.epoch, h.revision, h.controlEpoch, h.observedThrough, System.nanoTime(),
                    active.filter { it.kind == TimelineObjectKind.UNIT }.map { it.handle }.sorted().toLongArray(),
                    active.filter { it.kind == TimelineObjectKind.ACTION }.map { it.handle }.sorted().toLongArray())
                receipts.addLast(receipt)
                while (receipts.size > 32 || receipts.sumOf { it.bytes } > 16L * 1024 * 1024 ||
                    (receipts.isNotEmpty() && receipt.sentAtNanos - receipts.first.sentAtNanos > TimeUnit.SECONDS.toNanos(2))) receipts.removeFirst()
            }

            private fun check(now: Long): Boolean {
                val state = committed.get() ?: return false
                if (synchronizing.get() || (context.mode == TimelineStreamMode.HISTORY && historyCheckSent) ||
                    (context.mode == TimelineStreamMode.LIVE && now - lastCheck < TimeUnit.MILLISECONDS.toNanos(checkIntervalMillis))) return false
                val started = System.nanoTime(); val digest = TimelineCanonical.digest(state)
                counters.digestNanos.addAndGet(System.nanoTime() - started)
                synchronized(sentLock) {
                    if (synchronizing.get()) return false
                    protocolRequire(checkId < Long.MAX_VALUE, "Check IDs exhausted")
                    val sequence = ++checkId
                    checks[sequence] = Expected(state.header.revision, digest)
                    while (checks.size > 32) checks.remove(checks.keys.first())
                    if (unconfirmedSince.get() == 0L) unconfirmedSince.set(now)
                    wire.write(timelineFrame(109, tn(1, id, true), tn(2, state.header.epoch), tn(3, state.header.revision), tn(4, sequence), tb(5, digest)), false)
                    counters.checks.incrementAndGet(); lastCheck = now; historyCheckSent = true
                }
                return true
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            // A reader may notice stopped before server.close reaches its peer snapshot.
            markClose(if (stopped.get()) TimelinePeerCloseReason.SERVER_SHUTDOWN else TimelinePeerCloseReason.UNCLASSIFIED_CLOSE)
            if (!authentication.helloWritten.get()) authentication.reason.compareAndSet(null,
                if (stopped.get()) TimelineAuthenticationFailureReason.SERVER_CLOSED else TimelineAuthenticationFailureReason.CLOSED_BEFORE_HELLO)
            synchronized(admissionLock) { retiring[generation] = this; peers.remove(seat.teamId, this) }
            runCatching { socket.close() }; wake(); controls.clear()
        }
    }

    private data class PeerCloseCause(val reason: TimelinePeerCloseReason, val exceptionClass: String?, val at: Long, val queued: Int)

    /** CHM iterators are weakly consistent; Collection.size cannot select a safe single-item path. */
    private fun <T> concurrentSnapshot(values: Iterable<T>): List<T> = buildList {
        val iterator = values.iterator()
        while (iterator.hasNext()) add(iterator.next())
    }

    private fun recordAuthenticationFailure(attempt: AuthenticationAttempt) {
        if (attempt.helloWritten.get() || !attempt.recorded.compareAndSet(false, true)) return
        val futureStatus = attempt.futureStatus.get()
        val failure = TimelineAuthenticationFailure(attempt.id, attempt.phase, attempt.matchedTeam.get().takeIf { it >= 0 },
            TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - attempt.startedAt).coerceAtLeast(0)),
            futureStatus?.first, futureStatus?.second,
            attempt.reason.get() ?: TimelineAuthenticationFailureReason.CLOSED_BEFORE_HELLO, attempt.exceptionClass.get())
        synchronized(authenticationLock) {
            failedBeforeFirstHello++
            if (authenticationFailures.size == 64) { authenticationFailures.removeFirst(); droppedAuthenticationFailures++ }
            authenticationFailures.addLast(failure)
        }
    }
    private class AuthenticationAttempt(val id: Long, val startedAt: Long) {
        @Volatile var phase = TimelineAuthenticationPhase.ACCEPTED
        val matchedTeam = AtomicInteger(-1)
        val future = AtomicReference<CompletableFuture<TimelineSeat>?>()
        val futureStatus = AtomicReference<Pair<Boolean, Boolean>?>()
        val reason = AtomicReference<TimelineAuthenticationFailureReason?>()
        val exceptionClass = AtomicReference<String?>()
        val helloWritten = AtomicBoolean(); val recorded = AtomicBoolean()
        fun captureFutureStatus() {
            future.get()?.let { value ->
                val cancelled = value.isCancelled
                futureStatus.compareAndSet(null, (cancelled || value.isDone) to cancelled)
            }
        }
        fun failed(reason: TimelineAuthenticationFailureReason, error: Exception) {
            if (helloWritten.get()) return
            this.reason.compareAndSet(null, reason); exceptionClass.compareAndSet(null, error.javaClass.name)
        }
    }

    private fun newGrant(token: String, seat: TimelineSeat) = SeatGrant(token.toByteArray(Charsets.US_ASCII), seat,
        if (admissionProvider == null) seat else null)
    private class SeatGrant(val token: ByteArray, val seat: TimelineSeat, var resolved: TimelineSeat?) {
        @Volatile var pending: PendingAdmission? = null
        var connected = false
    }
    private class PendingAdmission(val socket: Socket) {
        val cancelled = AtomicBoolean()
        private val future = AtomicReference<CompletableFuture<TimelineSeat>?>()
        fun attach(value: CompletableFuture<TimelineSeat>) { future.set(value); if (cancelled.get()) value.cancel(false) }
        fun cancel() {
            cancelled.set(true); runCatching { socket.close() }; future.get()?.cancel(false)
        }
    }
    private data class Queued(val peer: Peer, val request: TimelineRequest)
    private data class Expected(val revision: Long, val digest: TimelineBytes)
    private class Sending(val publication: TimelinePublication, val prepared: TimelineStore.Prepared) {
        val operations = publication.operations.toByteArray()
        var begun = false; var offset = 0
    }
}
