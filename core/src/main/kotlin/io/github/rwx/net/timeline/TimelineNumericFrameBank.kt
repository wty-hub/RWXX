package io.github.rwx.net.timeline

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded owner-detached numeric frames. This is NOT a visibility proof, raw-history
 * bank, fitted curve, or network input. A future Room adapter must establish its
 * callback-free capture boundary before calling this API.
 *
 * Tokens identify one completed capture, including same-time revisions. A bank is
 * one opaque world epoch; create a new bank for another world. Entity ordinals are
 * private server identities, allocated here and never inferred from native IDs.
 */
internal class TimelineNumericFrameBank(
    private val widthQ: Long,
    private val heightQ: Long,
    val limits: Limits = Limits(),
) : AutoCloseable {
    data class Limits(
        val maxFrames: Int = 8,
        val maxBytes: Long = 32L * 1024 * 1024,
        val maxReaders: Int = 64,
        val maxLeasesPerReader: Int = 2,
    ) {
        init {
            require(maxFrames in 1..4096 && maxBytes > 0)
            require(maxReaders in 1..64 && maxLeasesPerReader in 1..2)
        }
    }

    sealed interface EntityToken { val ordinal: Long }
    sealed interface FrameToken { val serial: Long; val time: Long; val rowCount: Int }

    interface Capture : AutoCloseable {
        fun add(entity: EntityToken, x: Long, y: Long, z: Long, heading: Long, progress: Long)
        fun commit(): FrameToken
        override fun close()
    }

    interface Reader : AutoCloseable {
        /** The first lease call binds this reader to its one consumer thread. */
        fun lease(token: FrameToken): Lease
        override fun close()
    }

    /**
     * Revocable scalar access, NOT a transaction snapshot held alive by the reader.
     * Every read may throw [Expired]. If expiry interrupts a multi-field operation,
     * the caller must discard the ENTIRE partial observation/fit. It must neither
     * continue on a new token nor splice in a fallback source. No arrays, iterators,
     * frame objects or callbacks capable of retaining the columns are exported.
     */
    interface Lease : AutoCloseable {
        val token: FrameToken
        val rowCount: Int
        fun entityOrdinal(index: Int): Long
        fun x(index: Int): Long
        fun y(index: Int): Long
        fun z(index: Int): Long
        fun heading(index: Int): Long
        fun progress(index: Int): Long
        override fun close()
    }

    class Expired : IllegalStateException("Numeric frame lease expired")
    class Capacity(message: String) : IllegalStateException(message)

    data class Usage(val committedFrames: Int, val captureInProgress: Boolean,
        val payloadBytes: Long, val accountedBytes: Long, val readers: Int, val leases: Int)

    private class Epoch
    private class Entity(val epoch: Epoch, override val ordinal: Long) : EntityToken {
        var alive = true
        var captureSerial = 0L
    }
    private class Token(val epoch: Epoch, override val serial: Long,
        override val time: Long, override val rowCount: Int) : FrameToken

    // Exactly six primitive columns: private entity ordinal and five numeric values.
    // Arrays are created by Capture, filled only by its owner, then transferred once.
    private class Columns(rows: Int) {
        val entity = LongArray(rows)
        val x = LongArray(rows)
        val y = LongArray(rows)
        val z = LongArray(rows)
        val heading = LongArray(rows)
        val progress = LongArray(rows)
    }
    private class Slot(val token: Token, columns: Columns) {
        val data = AtomicReference<Columns?>(columns)
    }

    private val owner = Thread.currentThread()
    private val epoch = Epoch()
    private val gate = Any()
    private val frames = LinkedHashMap<Long, Slot>()
    private val readers = HashSet<ReaderImpl>()
    private val entities = HashSet<Entity>()
    private var activeCapture: CaptureImpl? = null
    private var nextEntity = 0L
    private var nextSerial = 0L
    private var lastCommittedTime = -1L
    private var bytes = 0L
    private var closed = false

    init {
        require(widthQ in 1..TimelineProtocol.MAX_SINT && heightQ in 1..TimelineProtocol.MAX_SINT)
    }

    /** Owner lifecycle operations are forbidden inside an unfinished capture. */
    fun newEntity(): EntityToken {
        checkOwner()
        synchronized(gate) {
            checkOpen(); check(activeCapture == null) { "Entity lifecycle changed during capture" }
            if (entities.size >= TimelineProtocol.MAX_UNITS) throw Capacity("Numeric entity capacity exceeded")
            check(nextEntity != Long.MAX_VALUE) { "Numeric entity identity exhausted" }
            return Entity(epoch, ++nextEntity).also(entities::add)
        }
    }

    fun retire(entity: EntityToken) {
        checkOwner()
        synchronized(gate) {
            checkOpen(); check(activeCapture == null) { "Entity lifecycle changed during capture" }
            val owned = entity as? Entity
            require(owned != null && owned.epoch === epoch && owned.alive && entities.remove(owned)) { "Invalid entity lifecycle" }
            owned.alive = false
        }
    }

    /**
     * Reserves the complete columns before allocation. A capacity failure or abort
     * leaves committed frames intact. The owner may discardBefore and retry.
     * maxFrames bounds committed frames; the one in-progress capture additionally
     * counts against maxBytes. Same time is legal but receives a new serial.
     */
    fun beginFrame(time: Long, rowCount: Int): Capture {
        checkOwner()
        synchronized(gate) {
            checkOpen(); check(activeCapture == null) { "A numeric capture is already in progress" }
            require(time in 0..TimelineProtocol.MAX_TIME && time >= lastCommittedTime) { "Invalid numeric frame time" }
            require(rowCount in 0..TimelineProtocol.MAX_UNITS) { "Invalid numeric row count" }
            val charge = accountedBytes(rowCount)
            if (charge > limits.maxBytes - bytes) throw Capacity("Numeric frame byte capacity exceeded")
            check(nextSerial != Long.MAX_VALUE) { "Numeric frame serial exhausted" }
            val token = Token(epoch, ++nextSerial, time, rowCount)
            // Allocate while no other owner operation can change the reservation.
            val capture = CaptureImpl(token, Columns(rowCount))
            bytes += charge
            activeCapture = capture
            return capture
        }
    }

    fun openReader(): Reader {
        checkOwner()
        synchronized(gate) {
            checkOpen()
            if (readers.size >= limits.maxReaders) throw Capacity("Numeric reader capacity exceeded")
            return ReaderImpl().also(readers::add)
        }
    }

    /** Expiry never waits for consumers. An old token cannot be redirected to another slot. */
    fun discardBefore(serialExclusive: Long) {
        checkOwner(); require(serialExclusive >= 0)
        synchronized(gate) {
            checkOpen()
            val iterator = frames.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key >= serialExclusive) break
                expire(entry.value); iterator.remove()
            }
        }
    }

    fun usage(): Usage {
        checkOwner()
        synchronized(gate) {
            val rows = frames.values.sumOf { it.token.rowCount.toLong() } + (activeCapture?.token?.rowCount ?: 0)
            return Usage(frames.size, activeCapture != null, rows * BYTES_PER_ROW, bytes,
                readers.size, readers.sumOf { it.leases.size })
        }
    }

    override fun close() {
        checkOwner()
        synchronized(gate) {
            if (closed) return
            closed = true
            activeCapture?.abortLocked()
            frames.values.forEach(::expire); frames.clear()
            readers.toList().forEach { it.closeLocked() }
            entities.forEach { it.alive = false }; entities.clear()
            check(bytes == 0L)
        }
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Numeric frame bank requires its owner" }
    private fun checkOpen() = check(!closed) { "Numeric frame bank is closed" }
    private fun expire(slot: Slot) {
        if (slot.data.getAndSet(null) != null) bytes -= accountedBytes(slot.token.rowCount)
    }

    private inner class CaptureImpl(val token: Token, columns: Columns) : Capture {
        private var columns: Columns? = columns
        private var position = 0
        private var done = false

        override fun add(entity: EntityToken, x: Long, y: Long, z: Long, heading: Long, progress: Long) {
            checkOwner()
            synchronized(gate) {
                checkOpen(); check(!done && activeCapture === this) { "Numeric capture is no longer active" }
                require(position < token.rowCount) { "Numeric capture has too many rows" }
                val owned = entity as? Entity
                require(owned != null && owned.epoch === epoch && owned.alive && owned in entities) { "Foreign or retired numeric entity" }
                require(owned.captureSerial != token.serial) { "Duplicate numeric entity in frame" }
                require(x in 0 until widthQ && y in 0 until heightQ && z in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT &&
                    heading in 0L..65535L && progress in 0L..65535L) { "Invalid quantized numeric values" }
                val data = checkNotNull(columns)
                data.entity[position] = owned.ordinal; data.x[position] = x; data.y[position] = y
                data.z[position] = z; data.heading[position] = heading; data.progress[position] = progress
                owned.captureSerial = token.serial
                position++
            }
        }

        override fun commit(): FrameToken {
            checkOwner()
            synchronized(gate) {
                checkOpen(); check(!done && activeCapture === this) { "Numeric capture is no longer active" }
                check(position == token.rowCount) { "Numeric capture is incomplete" }
                frames[token.serial] = Slot(token, checkNotNull(columns))
                columns = null; done = true; activeCapture = null
                lastCommittedTime = token.time
                while (frames.size > limits.maxFrames) {
                    val oldest = frames.entries.iterator()
                    val slot = oldest.next().value; expire(slot); oldest.remove()
                }
                return token
            }
        }

        override fun close() { checkOwner(); synchronized(gate) { abortLocked() } }
        fun abortLocked() {
            if (done) return
            check(activeCapture === this)
            columns = null; done = true; activeCapture = null
            bytes -= accountedBytes(token.rowCount)
        }
    }

    private inner class ReaderImpl : Reader {
        private val consumer = AtomicReference<Thread?>()
        private var readerClosed = false
        val leases = HashSet<LeaseImpl>() // Only accessed under gate; bounded by Limits.

        fun checkConsumer() = check(consumer.get() === Thread.currentThread()) { "Numeric reader belongs to another consumer" }

        override fun lease(token: FrameToken): Lease {
            consumer.compareAndSet(null, Thread.currentThread()); checkConsumer()
            synchronized(gate) {
                if (closed || readerClosed) throw Expired()
                val issued = token as? Token
                require(issued != null && issued.epoch === epoch) { "Foreign numeric frame epoch" }
                val slot = frames[issued.serial]?.takeIf { it.token === issued && it.data.get() != null } ?: throw Expired()
                if (leases.size >= limits.maxLeasesPerReader) throw Capacity("Numeric reader lease capacity exceeded")
                return LeaseImpl(this, slot).also(leases::add)
            }
        }

        override fun close() { synchronized(gate) { closeLocked() } }
        fun closeLocked() {
            if (readerClosed) return
            readerClosed = true
            leases.forEach { it.released.set(true) }; leases.clear(); readers.remove(this)
        }
    }

    private inner class LeaseImpl(private val reader: ReaderImpl, private val slot: Slot) : Lease {
        val released = AtomicBoolean()
        override val token: FrameToken get() = slot.token
        private fun data(): Columns {
            reader.checkConsumer()
            if (released.get()) throw Expired()
            return slot.data.get() ?: throw Expired()
        }
        private fun data(index: Int): Columns {
            val data = data()
            require(index in 0 until slot.token.rowCount) { "Numeric row index outside frame" }
            return data
        }
        override val rowCount: Int get() { data(); return slot.token.rowCount }
        override fun entityOrdinal(index: Int): Long = data(index).entity[index]
        override fun x(index: Int): Long = data(index).x[index]
        override fun y(index: Int): Long = data(index).y[index]
        override fun z(index: Int): Long = data(index).z[index]
        override fun heading(index: Int): Long = data(index).heading[index]
        override fun progress(index: Int): Long = data(index).progress[index]
        override fun close() {
            synchronized(gate) {
                if (released.compareAndSet(false, true)) reader.leases.remove(this)
            }
        }
    }

    companion object {
        const val BYTES_PER_ROW = 6L * Long.SIZE_BYTES
        // Fixed accounting allowance for six array headers and frame/capture/slot
        // descriptors. This is a stable admission budget, NOT a measured JVM heap size.
        const val FRAME_ALLOWANCE_BYTES = 512L
        fun accountedBytes(rows: Int): Long {
            require(rows in 0..TimelineProtocol.MAX_UNITS)
            return rows.toLong() * BYTES_PER_ROW + FRAME_ALLOWANCE_BYTES
        }
    }
}
