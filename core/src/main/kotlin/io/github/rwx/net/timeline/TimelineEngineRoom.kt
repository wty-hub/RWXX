package io.github.rwx.net.timeline

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.net.authoritative.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TimelinePreparedTeam(val team: PlayerTeam, val rollback: () -> Unit = {})

/** Actual owner-pass counters, detached from all engine objects and safe to read off-owner. */
data class TimelineRoomObservationStatistics(val captures: Long = 0, val reusedPasses: Long = 0,
                                              val fallbackPasses: Long = 0)

/** Immutable diagnostics of a grant's publication workers; contains no native engine objects. */
data class TimelineRoomSessionStatistics(
    val teamId: Int,
    val accessId: TimelineBytes,
    val joinedAt: Long,
    val statistics: TimelineSessionStatistics,
    val processedThrough: Long,
    val archivedUntil: Long,
    val accepting: Boolean,
    val failure: String?,
    val acceptedCommands: Long = 0,
    val acceptedSelections: Long = 0,
)

/** Engine-owned room coordinator. Admission and feed calls from network threads never read the engine. */
class TimelineEngineRoom(
    private val engine: GameEngine,
    reservedSeats: Collection<TimelineSeat>,
    private val archiveDirectory: Path,
    private val prepareTeam: (Int) -> TimelinePreparedTeam,
    private val participatingTeams: () -> Collection<Int>,
    private val limits: TimelineSessionLimits = TimelineSessionLimits(),
) : TimelineFeedFactory, TimelineAdmissionProvider, AutoCloseable {
    private val owner = Thread.currentThread()
    // Existing constructors always retain their arbitrary callback semantics.
    // Only the explicit factory below supplies a closed native roster source.
    private var rosterSource = TimelineRoomRosterSource.legacy(participatingTeams)
    private val stopped = AtomicBoolean()
    private val intakeLock = Any()
    private val time = AtomicLong(engine.currentTick.toLong() * 1024)
    private val reserved = reservedSeats.associateBy { it.teamId }
    private data class Admission(val seat: TimelineSeat, val result: CompletableFuture<TimelineSeat>)
    private class Grant(val seat: TimelineSeat, val projection: EngineTimelineProjector, val session: TimelineSession,
                        val events: TimelineEngineEvents.Grant) {
        var failureReported = false
        val acceptedCommands = AtomicLong()
        val acceptedSelections = AtomicLong()
    }
    private val requests = ArrayBlockingQueue<Admission>(64)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<TimelineSeat>>()
    private val grants = ConcurrentHashMap<Int, Grant>()
    private val sessions = TimelineRoomSessions()
    private val publicMap = EnginePublicMapSnapshot.capture(engine.tileMap)
    private val stock = StockUnitCatalog.capture(engine)
    private val actions = StockActionClosure.capture(engine, stock)
    val mapRecord = EngineTimelineProjector.mapRecord(engine, publicMap)
    val matchId = TimelineBytes(ByteArray(16).also(SecureRandom()::nextBytes))
    private val mapKey = TimelineCanonical.mapKey(mapRecord)
    private val events = TimelineEngineEvents(engine.tileMap.tileCountX, engine.tileMap.tileCountY,
        engine.tileMap.tileWorldSizeX, engine.tileMap.tileWorldSizeY, 60)
    private val nativeRegistration: AutoCloseable
    @Volatile private var result: MatchStatus? = null
    @Volatile private var observationStatistics = TimelineRoomObservationStatistics()
    fun observationStatistics(): TimelineRoomObservationStatistics = observationStatistics
    val serverTime: Long get() = time.get()
    val finished: Boolean get() = result != null

    init {
        require(reservedSeats.size in 1..64 && reserved.size == reservedSeats.size)
        require(reserved.values.all { it.teamId in 0..63 && it.accessId.size == 16 })
        check(!engine.networkEngine.networkGameActive) { "Timeline rooms cannot attach to legacy lockstep" }
        Files.createDirectories(archiveDirectory)
        nativeRegistration = TimelineNativeEvents.install(events)
    }

    override fun admit(reservedSeat: TimelineSeat): CompletableFuture<TimelineSeat> = synchronized(intakeLock) {
        if (stopped.get() || reserved[reservedSeat.teamId]?.accessId != reservedSeat.accessId)
            return@synchronized CompletableFuture.failedFuture(IOException("Seat admission is unavailable"))
        grants[reservedSeat.teamId]?.let { return@synchronized CompletableFuture.completedFuture(it.seat) }
        pending[reservedSeat.teamId]?.takeUnless { it.isDone }?.let { return@synchronized it }
        if (!sessions.canReserve()) return@synchronized CompletableFuture.failedFuture(IOException("Session cleanup budget exceeded"))
        val future = CompletableFuture<TimelineSeat>()
        if (!requests.offer(Admission(reservedSeat, future))) return@synchronized CompletableFuture.failedFuture(IOException("Seat admission queue exceeded"))
        pending[reservedSeat.teamId] = future
        future
    }

    override fun open(request: TimelineStreamRequest): TimelineFeed? = grants[request.teamId]?.session?.open(request)
    fun historyUntil(teamId: Int): Long = grants[teamId]?.session?.archivedUntil ?: serverTime
    fun finishRecordingsAsync(): CompletableFuture<Void> = CompletableFuture.allOf(
        *grants.values.map { it.session.finishAsync() }.toTypedArray())
    fun stoppedAsync(): CompletableFuture<Void> = sessions.stoppedAsync()

    /** Read-only worker counters, safe off the simulation owner; no projection or world reads. */
    fun sessionStatistics(): Map<Int, TimelineRoomSessionStatistics> = frozenMap(grants.entries.sortedBy { it.key }.associate { (id, grant) ->
        id to TimelineRoomSessionStatistics(id, grant.seat.accessId, grant.seat.joinedAt,
            grant.session.statistics(), grant.session.processedThrough, grant.session.archivedUntil,
            grant.session.isAccepting, grant.session.failure?.let { "${it.javaClass.simpleName}: ${it.message}" },
            grant.acceptedCommands.get(), grant.acceptedSelections.get())
    })

    /** Call once per owner iteration, before advancing the world. It never waits for a client. */
    fun runStep(server: TimelineServer, advance: () -> Unit) {
        checkOwner()
        for (turn in 0 until 4) {
            val admission = requests.poll() ?: break
            if (admission.result.isDone || stopped.get()) continue
            var session: TimelineSession? = null
            var eventGrant: TimelineEngineEvents.Grant? = null
            var setup: TimelinePreparedTeam? = null
            var tracked: TimelineRoomSessions.Reservation? = null
            var committed = false
            try {
                check(!finished) { "The match has finished" }
                // Reserve before creating teams or threads. Cancelled admissions may retain an
                // independently bounded cleanup slot until their own I/O has actually stopped.
                tracked = checkNotNull(sessions.reserve()) { "Session cleanup budget exceeded" }
                setup = prepareTeam(admission.seat.teamId)
                val team = setup.team
                check(team.teamId == admission.seat.teamId && PlayerTeam.k(team.teamId) === team && !team.isSpectatorTeamColor)
                val joined = engine.currentTick.toLong() * 1024
                val seat = admission.seat.copy(joinedAt = joined, historyFrom = joined, admission = 1)
                val context = TimelineContext(matchId, seat.accessId, mapKey, mapRecord, seat.teamId, 60,
                    TimelineStreamMode.LIVE, joinedAt = joined, historyFrom = joined)
                val name = seat.accessId.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
                session = TimelineSession(context, archiveDirectory.resolve("seat-${seat.teamId}-$name.rwxr"), limits,
                    onPublished = { server.signal(seat.teamId) })
                tracked.attach(session.stoppedAsync(), session::close)
                val projection = if (rosterSource.hasNativeProvenance)
                    EngineTimelineProjector.forNativeRoom(engine, team, publicMap, stock, actions, rosterSource)
                else EngineTimelineProjector(engine, team, publicMap, stock, actions) { status() }
                check(session.offer(projection.observe())) { "Initial observation was refused" }
                eventGrant = events.openGrant(joined)
                val grant = Grant(seat, projection, session, eventGrant)
                val installed = synchronized(intakeLock) {
                    if (!stopped.get() && !admission.result.isDone) {
                        grants[seat.teamId] = grant
                        true
                    } else false
                }
                if (installed && !admission.result.complete(seat)) grants.remove(seat.teamId, grant)
                committed = grants[seat.teamId] === grant
                if (committed) tracked.promote()
                if (!committed) { eventGrant.close(); session.close() }
            } catch (error: Exception) {
                eventGrant?.close()
                session?.close()
                admission.result.completeExceptionally(IOException("Seat admission failed", error))
            } finally {
                try {
                    if (!committed) {
                        tracked?.close()
                        setup?.rollback?.invoke()
                    }
                }
                finally { pending.remove(admission.seat.teamId, admission.result) }
            }
        }
        server.drainRequests { request ->
            val grant = grants[request.teamId] ?: return@drainRequests
            if (!grant.failureReported && grant.session.failure == null && grant.events.failure == null && !finished &&
                grant.projection.apply(request, grant.session::unitKey, grant.session::actionKey)) {
                when (request.frame.type) {
                    5 -> grant.acceptedCommands.incrementAndGet()
                    6 -> grant.acceptedSelections.incrementAndGet()
                }
            }
        }
        if (finished || stopped.get()) return
        val start = engine.currentTick.toLong() * TimelineProtocol.SUBDIVISIONS
        val end = start + TimelineProtocol.SUBDIVISIONS
        events.beginStep(start, end)
        try {
            for ((team, grant) in grants.toSortedMap()) {
                if (grant.failureReported) continue
                try {
                    if (grant.session.failure != null) throw IOException("Timeline grant failed", grant.session.failure)
                    grant.projection.beginStepEvents(grant.events)
                } catch (error: Exception) { stopGrant(server, team, grant, error) }
            }
            advance()
        } finally { events.endStep() }
        check(engine.currentTick.toLong() * TimelineProtocol.SUBDIVISIONS == end) { "Room advance must perform exactly one simulation tick" }
        time.set(end)
        val terminal = status().takeIf { it.phase == MatchPhase.FINISHED }
        val batch = if (rosterSource.hasNativeProvenance) NativeObservationBatch(engine, stock, actions, rosterSource) else null
        try {
            for ((team, grant) in grants.toSortedMap()) {
                if (grant.failureReported) continue
                try {
                    if (grant.session.failure != null) throw IOException("Timeline grant failed", grant.session.failure)
                    check(grant.session.offer(batch?.let { grant.projection.observeInBatch(it, grant.events.drain()) }
                        ?: grant.projection.observe(grant.events.drain()))) { "Observation admission was refused" }
                    if (terminal != null) {
                        check(grant.session.offer(grant.projection.finalObservationFence())) { "Final observation fence was refused" }
                        grant.session.finishAsync()
                    }
                } catch (error: Exception) {
                    batch?.fallback()
                    stopGrant(server, team, grant, error)
                }
            }
        } finally {
            batch?.let {
                val current = it.statistics()
                val old = observationStatistics
                observationStatistics = TimelineRoomObservationStatistics(old.captures + current.captures,
                    old.reusedPasses + current.reusedPasses, old.fallbackPasses + current.fallbackPasses)
                it.close()
            }
        }
        if (terminal != null) result = terminal
    }

    private fun status() = result ?: rosterSource.current()

    /** Roster provenance only; no grouped numeric capture is enabled by this seam. */
    internal fun nativeRosterCandidateOrNull(): TimelineRoomRosterSource.Candidate? {
        checkOwner()
        return if (result == null) rosterSource.candidateOrNull() else null
    }
    private fun checkOwner() = check(Thread.currentThread() === owner) { "Room updates require the engine owner thread" }
    private fun stopGrant(server: TimelineServer, team: Int, grant: Grant, error: Exception) {
        server.disconnect(team)
        grant.events.close()
        grant.session.close()
        if (!grant.failureReported) {
            grant.failureReported = true
            System.err.println("Timeline seat $team stopped: ${error.message}")
        }
    }

    override fun close() {
        checkOwner()
        val waiting = synchronized(intakeLock) {
            if (!stopped.compareAndSet(false, true)) return
            requests.clear()
            pending.values.toList().also { pending.clear() }
        }
        nativeRegistration.close()
        waiting.forEach { it.cancel(false) }
        grants.values.forEach { it.events.close(); it.session.close() }
        sessions.close()
    }

    companion object {
        /**
         * Explicit native-slot roster source, preserving the old per-grant capture
         * order. Unknown team classes still use the ordinary status callbacks.
         * Only qualified native observation groups share owner-local facts; all other grants retain their ordinary pass.
         */
        fun withNativeRoster(engine: GameEngine, reservedSeats: Collection<TimelineSeat>, archiveDirectory: Path,
            prepareTeam: (Int) -> TimelinePreparedTeam, limits: TimelineSessionLimits = TimelineSessionLimits()): TimelineEngineRoom {
            val source = TimelineRoomRosterSource.nativeSlots()
            return TimelineEngineRoom(engine, reservedSeats, archiveDirectory, prepareTeam, source::participantIds, limits)
                .also { it.rosterSource = source }
        }
    }
}

/** Engine-free bookkeeping: at most 64 formal sessions plus 64 creating/retiring sessions. */
internal class TimelineRoomSessions(private val formalLimit: Int = 64, private val extraLimit: Int = 64) : AutoCloseable {
    internal class Entry {
        val done = CompletableFuture<Void>()
        var formal = false
        var attached = false
        var closing = false
        var closeIssued = false
        var close: (() -> Unit)? = null
    }
    private val lock = Any()
    private val entries = HashSet<Entry>()
    private var formal = 0; private var extra = 0; private var closed = false
    init { require(formalLimit > 0 && extraLimit > 0) }
    fun canReserve(): Boolean = synchronized(lock) { !closed && extra < extraLimit }
    fun counts(): Pair<Int, Int> = synchronized(lock) { formal to extra }
    fun reserve(): Reservation? = synchronized(lock) {
        if (closed || extra >= extraLimit) return@synchronized null
        val entry = Entry(); entries.add(entry); extra++
        Reservation(entry)
    }
    fun stoppedAsync(): CompletableFuture<Void> = synchronized(lock) {
        CompletableFuture.allOf(*entries.map { it.done }.toTypedArray())
    }
    inner class Reservation internal constructor(private val entry: Entry) : AutoCloseable {
        fun attach(stopped: CompletableFuture<*>, close: () -> Unit) {
            val closeNow = synchronized(lock) {
                check(entry in entries && !entry.attached) { "Session reservation is already released or attached" }
                entry.attached = true; entry.close = close
                if (closed || entry.closing) { entry.closeIssued = true; true } else false
            }
            stopped.whenComplete { _, _ ->
                // This callback may run on a Session worker. Only reclaim metadata; no engine,
                // rollback, resource close or simulation is performed here.
                synchronized(lock) {
                    if (entries.remove(entry)) { if (entry.formal) formal-- else extra-- }
                }
                entry.done.complete(null)
            }
            if (closeNow) close()
        }
        fun promote() = synchronized(lock) {
            if (entry in entries && !entry.formal) {
                check(formal < formalLimit) { "Formal session budget exceeded" }
                entry.formal = true; extra--; formal++
            }
        }
        override fun close() { closeEntry(entry, abandonUnattached = true) }
    }
    private fun closeEntry(entry: Entry, abandonUnattached: Boolean) {
        var released = false
        val close = synchronized(lock) {
            if (entry !in entries) return@synchronized null
            entry.closing = true
            if (!entry.attached && abandonUnattached) {
                entries.remove(entry); if (entry.formal) formal-- else extra--; released = true
                null
            } else if (entry.attached && !entry.closeIssued) { entry.closeIssued = true; entry.close } else null
        }
        if (released) entry.done.complete(null)
        close?.invoke()
    }
    override fun close() {
        val all = synchronized(lock) { closed = true; entries.toList() }
        all.forEach { closeEntry(it, abandonUnattached = false) }
    }
}
