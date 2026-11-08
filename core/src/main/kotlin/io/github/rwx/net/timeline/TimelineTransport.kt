package io.github.rwx.net.timeline

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** Room admission is decided outside transport; never accept a client-supplied team or access ID. */
data class TimelineSeat(
    val teamId: Int,
    val accessId: TimelineBytes,
    val joinedAt: Long = 0,
    val historyFrom: Long = joinedAt,
    val admission: Int = 1,
)

/**
 * TLS/token verification is complete before this is called. Only enqueue a bounded owner request;
 * do not initialize the engine here. The server may cancel the returned, attempt-owned future.
 * Owners must skip cancelled requests and clean up their own work if complete() returns false.
 * Cancellation handlers must also be nonblocking and must never call the engine.
 */
fun interface TimelineAdmissionProvider {
    fun admit(reservedSeat: TimelineSeat): CompletableFuture<TimelineSeat>
}

/** Immutable input to a per-connection encoder. Called only by that peer's network writer. */
data class TimelineSendCursor(
    val context: TimelineContext,
    val streamId: Long,
    val epoch: Long,
    val previous: TimelineState?,
    val reset: Boolean,
)

/** Header/operation bytes describe one atomic revision, not an engine snapshot or legacy packet. */
data class TimelinePublication(val header: TimelineHeader, val operations: TimelineBytes)

/** The newest authorized window no longer shares a safe UPDATE base with this connection. */
class TimelineResetRequired(message: String = "A fresh timeline checkpoint is required") : IOException(message)

data class TimelineStreamRequest(
    val teamId: Int,
    val connectionGeneration: Long,
    val requestId: Long,
    val streamId: Long,
    val context: TimelineContext,
    val budgetBytesPerSecond: Long,
)

/**
 * Read only immutable, already-authorized published data. This method must be non-blocking and
 * must never call the engine. Null means no new revision is ready. A requested RESET must be
 * complete and independently decodable. The encoder bases every UPDATE on cursor.previous.
 * Archive I/O should complete asynchronously before publishing immutable data to this feed.
 * close never calls the engine; cleanup runs only on this peer's writer, not the owner/watchdog.
 */
interface TimelineFeed : AutoCloseable {
    fun next(cursor: TimelineSendCursor): TimelinePublication?
    override fun close() = Unit
}

fun interface TimelineFeedFactory {
    /** Null rejects an unavailable history interval. This callback must not call the engine. */
    fun open(request: TimelineStreamRequest): TimelineFeed?
}

/** Small immutable receipt. Never retains another complete historical 64 MiB library. */
class TimelineReceipt internal constructor(val epoch: Long, val revision: Long, val controlEpoch: Long,
    val observedThrough: Long, internal val sentAtNanos: Long, units: LongArray, actions: LongArray) {
    private val units = units.copyOf()
    private val actions = actions.copyOf()
    fun containsUnit(handle: Long): Boolean = units.binarySearch(handle) >= 0
    fun containsAction(handle: Long): Boolean = actions.binarySearch(handle) >= 0
    internal val bytes: Long get() = 64L + 8L * (units.size + actions.size)
}

data class TimelineRequest(val teamId: Int, val connectionGeneration: Long, val frame: TimelineFrame,
    val receipt: TimelineReceipt)

data class TimelineWireStatistics(
    val receivedBytes: Long = 0,
    val writtenBytes: Long = 0,
    val mapInitializations: Long = 0,
    val mapCacheHits: Long = 0,
    val transactions: Long = 0,
    val stateChecks: Long = 0,
    val stateAcks: Long = 0,
    val stateMismatches: Long = 0,
    val stateDigestNanos: Long = 0,
    val restores: Long = 0,
)

internal fun timelineFrame(type: Int, vararg fields: TimelineField) = TimelineFrame(type, TimelineRecord(fields.toList()))
internal fun tn(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
internal fun tb(id: Int, value: TimelineBytes) = TimelineMessages.bytesField(id, value)

internal class TimelineCounters {
    val received = AtomicLong(); val written = AtomicLong(); val maps = AtomicLong(); val hits = AtomicLong()
    val transactions = AtomicLong(); val checks = AtomicLong(); val acks = AtomicLong()
    val mismatches = AtomicLong(); val digestNanos = AtomicLong(); val restores = AtomicLong()
    fun snapshot() = TimelineWireStatistics(received.get(), written.get(), maps.get(), hits.get(), transactions.get(),
        checks.get(), acks.get(), mismatches.get(), digestNanos.get(), restores.get())
}
