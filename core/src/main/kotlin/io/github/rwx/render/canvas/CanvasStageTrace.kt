package io.github.rwx.render.canvas

import java.io.BufferedWriter
import java.io.File

/** Opt-in wall-clock stages, sharing System.nanoTime with engine and presentation traces. */
class CanvasStageTrace private constructor(private val writer: BufferedWriter?) : AutoCloseable {
    val enabled: Boolean get() = writer != null
    private var closed = false

    fun start(): Long = if (enabled) System.nanoTime() else 0L

    fun record(stage: String, start: Long, value0: Long = -1, value1: Long = -1, value2: Long = -1) {
        if (start == 0L) return
        recordCompleted(stage, start, System.nanoTime(), value0, value1, value2)
    }

    @Synchronized
    fun recordCompleted(stage: String, start: Long, end: Long,
        value0: Long = -1, value1: Long = -1, value2: Long = -1) {
        val output = writer ?: return
        if (closed) return
        output.append(start.toString()).append(',').append(end.toString()).append(',')
            .append(stage).append(',').append(value0.toString()).append(',')
            .append(value1.toString()).append(',').append(value2.toString()).append('\n')
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        writer?.close()
    }

    companion object {
        fun fromEnvironment(name: String): CanvasStageTrace {
            val writer = System.getenv(name)?.takeIf { it.isNotBlank() }?.let { path ->
                File(path).also { it.parentFile?.mkdirs() }.bufferedWriter().also {
                    it.write("startNanos,endNanos,stage,value0,value1,value2\n")
                }
            }
            return CanvasStageTrace(writer).also { trace ->
                if (writer != null) Runtime.getRuntime().addShutdownHook(Thread({ trace.close() }, "$name-close"))
            }
        }
    }
}

internal val CanvasRenderStageTrace = CanvasStageTrace.fromEnvironment("RWX_CANVAS_TRACE")
