package io.github.rwx.render.canvas

import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Diagnostic boundary events, not GPU completion or physical scan-out. No hot-path file I/O. */
internal object CanvasFrameLifecycleTrace {
    private val path = System.getenv("RWX_FRAME_LIFECYCLE_TRACE")?.takeIf(String::isNotBlank)
    private val rows = path?.let {
        CanvasFrameLifecycleBuffer(System.getenv("RWX_FRAME_LIFECYCLE_EVENTS")?.toIntOrNull()
            ?.coerceIn(16, 1_048_576) ?: 262_144)
    }
    private val writer = path?.let {
        File(it).absoluteFile.also { file -> file.parentFile?.mkdirs() }.toPath().let { file ->
            Files.newBufferedWriter(file, Charsets.UTF_8, CREATE_NEW, WRITE)
        }
    }
    val enabled: Boolean get() = rows != null

    init {
        if (rows != null) Runtime.getRuntime().addShutdownHook(Thread(::close, "RWX-frame-lifecycle-close"))
    }

    fun produced(generation: Long, sequence: Long) = rows?.produced(generation, sequence, System.nanoTime())
    fun chosen(generation: Long, sequence: Long) = rows?.chosen(generation, sequence, System.nanoTime())
    fun nativeBackend() = rows?.nativeBackend()
    fun submitted(generation: Long, sequence: Long, submission: Long, accepted: Boolean) =
        rows?.submitted(generation, sequence, submission, accepted, System.nanoTime())
    fun presented(generation: Long, sequence: Long, now: Long) = rows?.presented(generation, sequence, now)
    fun close() { if (writer != null) rows?.closeTo(writer) }
}

/** Fixed storage; overflow is retained as an explicit invalidation marker rather than wrapping. */
internal class CanvasFrameLifecycleBuffer(private val capacity: Int) {
    init { require(capacity > 0 && capacity <= 1_048_576) }
    private val data = LongArray(capacity * WIDTH)
    private var count = 0
    private var dropped = 0L
    private var lastSubmission = -1L
    private var nativeBackend = false
    private var closed = false

    @Synchronized fun produced(generation: Long, sequence: Long, now: Long) = record(PRODUCED, generation, sequence, -1, now)
    @Synchronized fun chosen(generation: Long, sequence: Long, now: Long) {
        if (closed) return
        lastSubmission = -1L
        record(CHOSEN, generation, sequence, -1, now)
    }
    @Synchronized fun nativeBackend() { if (!closed) nativeBackend = true }
    @Synchronized fun submitted(generation: Long, sequence: Long, submission: Long, accepted: Boolean, now: Long) {
        if (closed) return
        nativeBackend = true
        if (!accepted) return
        lastSubmission = submission
        record(SUBMITTED, generation, sequence, submission, now)
    }
    @Synchronized fun presented(generation: Long, sequence: Long, now: Long) =
        record(PRESENTED, generation, sequence, lastSubmission, now)

    private fun record(event: Long, generation: Long, sequence: Long, submission: Long, now: Long) {
        if (closed) return
        if (count == capacity) { dropped++; return }
        val offset = count++ * WIDTH
        data[offset] = now; data[offset + 1] = event; data[offset + 2] = generation
        data[offset + 3] = sequence; data[offset + 4] = submission
    }

    /** Only shutdown serializes numbers and writes/flushes the file; calls after close are ignored. */
    @Synchronized fun closeTo(writer: Writer) {
        if (closed) return
        closed = true
        writer.use { output ->
            output.write("eventNanos,event,generation,sequence,submission,recordedEvents,droppedEvents,capacity,nativeBackend\n")
            repeat(count) { index ->
                val offset = index * WIDTH
                output.write("${data[offset]},${NAMES[data[offset + 1].toInt()]},${data[offset + 2]},${data[offset + 3]},${data[offset + 4]},,,,\n")
            }
            output.write("${System.nanoTime()},trace-complete,-1,-1,-1,$count,$dropped,$capacity,$nativeBackend\n")
        }
    }

    private companion object {
        const val WIDTH = 5
        const val PRODUCED = 0L
        const val CHOSEN = 1L
        const val SUBMITTED = 2L
        const val PRESENTED = 3L
        val NAMES = arrayOf("produced", "chosen", "submitted", "presented")
    }
}
