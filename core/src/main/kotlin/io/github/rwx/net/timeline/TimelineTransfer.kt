package io.github.rwx.net.timeline

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** One reader and one writer; counting is outside buffering and follows actual codec consumption. */
internal class TimelineSocket(socket: Socket, private val counters: TimelineCounters) {
    private val input = object : InputStream() {
        private val source = BufferedInputStream(socket.getInputStream())
        override fun read() = source.read().also { if (it >= 0) counters.received.incrementAndGet() }
        override fun read(bytes: ByteArray, offset: Int, length: Int) = source.read(bytes, offset, length).also {
            if (it > 0) counters.received.addAndGet(it.toLong())
        }
    }
    private val output = BufferedOutputStream(socket.getOutputStream())
    val writingSince = AtomicLong()
    fun read(fromClient: Boolean): TimelineFrame = TimelineBinary.readFrame(input).also { TimelineMessages.validate(it, fromClient) }
    fun write(frame: TimelineFrame, fromClient: Boolean) {
        TimelineMessages.validate(frame, fromClient)
        writingSince.set(System.nanoTime())
        TimelineBinary.writeFrame(output, frame); output.flush()
        counters.written.addAndGet(12L + TimelineBinary.recordSize(frame.body))
        writingSince.set(0)
    }
}

internal fun TimelineHeader.beginFrame() = timelineFrame(106,
    tn(1, streamId, true), tn(2, epoch), tn(3, kind.code.toLong(), true), tn(4, revision), tn(5, baseRevision),
    tn(6, observedThrough), tn(7, sealedThrough), tn(8, retainedFrom), tn(9, retainedUntil), tn(10, controlEpoch),
    tn(11, operationBytes), tb(12, operationSha256), tn(13, objectHighWater))

/** Bounded staging. A validated Commit is the only point that can publish a new state. */
internal class TimelineReceiver(val store: TimelineStore) {
    var header: TimelineHeader? = null; private set
    private var bytes: ByteArrayOutputStream? = null
    @Volatile var startedAt = 0L; private set
    @Volatile var waitingReset = false; private set
    @Volatile var recoveryStarted = 0L; private set
    var recoveries = 0; private set
    var epoch = 0L; private set

    fun requireReset(failedEpoch: Long = epoch) {
        protocolRequire(failedEpoch >= epoch, "Recovery epoch regressed")
        epoch = failedEpoch
        if (!waitingReset) {
            if (++recoveries > 3) throw ProtocolException("Repeated timeline restoration failed")
            waitingReset = true; recoveryStarted = System.nanoTime()
        }
    }
    fun verified() { recoveries = 0 }
    fun begin(value: TimelineHeader) {
        protocolRequire(header == null, "Transaction already staged")
        val previous = store.current?.header
        if (value.kind == TimelineTransactionKind.RESET) {
            protocolRequire(value.epoch == maxOf(previous?.epoch ?: 0, epoch) + 1, "RESET epoch must advance exactly once")
        } else {
            protocolRequire(value.epoch == maxOf(previous?.epoch ?: 0, epoch).coerceAtLeast(1), "Unexpected UPDATE epoch")
        }
        epoch = value.epoch
        header = value; bytes = ByteArrayOutputStream(minOf(value.operationBytes.toInt(), 65536)); startedAt = System.nanoTime()
    }
    fun chunk(frame: TimelineFrame) {
        val h = header ?: throw ProtocolException("Chunk without Begin")
        val r = frame.body
        identity(r, h)
        val data = r.bytes(5)
        val buffer = checkNotNull(bytes)
        protocolRequire(r.number(4) == buffer.size().toLong() && data.size <= h.operationBytes - buffer.size(), "Invalid transaction chunk offset")
        data.writeTo(buffer)
    }
    /** Null consumes a structurally valid transaction whose missing base needs one RESET. */
    fun commit(frame: TimelineFrame): TimelineState? {
        val h = header ?: throw ProtocolException("Commit without Begin")
        identity(frame.body, h)
        val data = TimelineBytes(checkNotNull(bytes).toByteArray())
        protocolRequire(data.size.toLong() == h.operationBytes && TimelineCanonical.matches(TimelineCanonical.sha256(data), h.operationSha256),
            "Transaction incomplete or operation digest differs")
        header = null; bytes = null; startedAt = 0
        if (waitingReset && h.kind != TimelineTransactionKind.RESET) {
            TimelineBinary.validateOperationEnvelope(data); return null
        }
        return try {
            store.apply(h, data).also { waitingReset = false; recoveryStarted = 0 }
        } catch (_: MissingBase) {
            TimelineBinary.validateOperationEnvelope(data); requireReset(); null
        }
    }
    fun abort() { header = null; bytes = null; startedAt = 0; waitingReset = false; recoveryStarted = 0 }
    private fun identity(record: TimelineRecord, h: TimelineHeader) = protocolRequire(
        record.number(1) == h.streamId && record.number(2) == h.epoch && record.number(3) == h.revision,
        "Transaction frame identity differs")
}
