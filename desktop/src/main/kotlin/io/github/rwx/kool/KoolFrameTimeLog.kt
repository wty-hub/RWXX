package io.github.rwx.kool

import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.PerformanceProfiler
import io.github.rwx.logger
import io.github.rwx.render.canvas.CanvasFrameMetrics
import java.io.BufferedWriter
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Frame-time statistics for the Kool desktop renderer, enabled with `RWX_PERF_LOG=1` (log) or
 * `RWX_PERF_LOG=/path/file` (append to a file). `RWX_ENGINE_FRAME_TRACE=/path/file.csv`
 * additionally enables a buffered per-frame trace and can be used without the window log.
 *
 * Measures the independent engine owner's frame interval, legacy game work (`update` + `draw`),
 * visible layer buffer redraw and the command-buffer snapshot handed to the Kool canvas.
 */
internal class KoolFrameTimeLog private constructor(private val output: File?, private val windowLogEnabled: Boolean,
    traceOutput: File?) : AutoCloseable {
    private val traceWriter: BufferedWriter? = traceOutput?.let { file ->
        file.parentFile?.mkdirs()
        file.bufferedWriter().also { writer ->
            writer.write("frameStartNanos,frameEndNanos,epochMillisAtEnd,intervalNanos,workNanos,updateNanos,drawNanos,layerRedrawNanos,snapshotNanos,tick,cameraX,cameraY,zoom,ownerCpuNanos,visiblePendingRedraws,renderedFrame,replayNanos,replayGapNanos,callbackEntryGapNanos,callbackExitGapNanos")
            writer.newLine()
        }
    }
    private val intervals = LongArray(MAX_SAMPLES)
    private val works = LongArray(MAX_SAMPLES)
    private val updates = LongArray(MAX_SAMPLES)
    private val draws = LongArray(MAX_SAMPLES)
    private val layers = LongArray(MAX_SAMPLES)
    private val snapshots = LongArray(MAX_SAMPLES)
    private var count = 0
    private var lastFrameStart = 0L
    private var phaseStart = 0L
    private var windowStart = System.nanoTime()
    private var lastReplaySequence = 0L
    private val cpuClock = if (traceWriter != null) ManagementFactory.getThreadMXBean().takeIf {
        it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled
    } else null
    private var cpuStart = -1L
    private val clockSampler = if (traceWriter != null) KoolJfrClockSampler() else null

    init {
        PerformanceProfiler.frameTimingEnabled = true
    }

    /** Called at the top of an engine-owner game frame, before any engine work. */
    fun beginFrame() {
        val now = System.nanoTime()
        clockSampler?.sample(now)
        if (lastFrameStart != 0L && count < MAX_SAMPLES) {
            intervals[count] = now - lastFrameStart
        }
        lastFrameStart = now
        phaseStart = now
        cpuStart = cpuClock?.currentThreadCpuTime ?: -1L
    }

    fun endGameWork() {
        val slot = slot()
        works[slot] = lap()
        val profiler = GameEngine.getInstance()?.performanceProfiler
        if (profiler != null) {
            updates[slot] = profiler.updateNanos
            draws[slot] = profiler.drawNanos
            profiler.takeFrameTimings()
        }
    }

    fun endLayerRedraw() {
        layers[slot()] = lap()
    }

    fun endSnapshot() {
        val slot = slot()
        snapshots[slot] = lap()
        traceWriter?.let { writer ->
            val epochMillisAtEnd = System.currentTimeMillis()
            val engine = GameEngine.getInstance()
            // Was this owner iteration actually replayed, and how long did the replay take? The owner loop
            // advances far more often than a frame is rendered (measured ~610 against ~120 per second), so
            // without these two columns a row cannot be told apart from a non-rendering iteration and any
            // frame budget derived from this file is wrong by a large factor.
            val replaySequence = CanvasFrameMetrics.replaySequence
            val renderedNow = if (replaySequence != lastReplaySequence) 1 else 0
            lastReplaySequence = replaySequence
            writer.append(lastFrameStart.toString()).append(',').append(phaseStart.toString()).append(',')
                .append(epochMillisAtEnd.toString()).append(',').append(intervals[slot].toString()).append(',')
                .append(works[slot].toString()).append(',').append(updates[slot].toString()).append(',')
                .append(draws[slot].toString()).append(',').append(layers[slot].toString()).append(',')
                .append(snapshots[slot].toString()).append(',').append((engine?.currentTick ?: -1).toString()).append(',')
                .append((engine?.viewpointX ?: Float.NaN).toString()).append(',')
                .append((engine?.viewpointY ?: Float.NaN).toString()).append(',')
                .append((engine?.zoom ?: Float.NaN).toString()).append(',')
                .append(if (cpuStart >= 0L) (cpuClock!!.currentThreadCpuTime - cpuStart).toString() else "-1")
                .append(',').append(if (engine?.hasLoadedLevel == true && engine.tileMap != null &&
                    TileMap.layerBufferManager.hasVisiblePendingRedraws()) "1" else "0")
                .append(',').append(renderedNow.toString())
                .append(',').append(if (renderedNow == 1) CanvasFrameMetrics.lastReplayNanos.toString() else "-1")
                .append(',').append(if (renderedNow == 1) CanvasFrameMetrics.lastReplayGapNanos.toString() else "-1")
                .append(',').append(if (renderedNow == 1) CanvasFrameMetrics.lastCallbackEntryGapNanos.toString() else "-1")
                .append(',').append(if (renderedNow == 1) CanvasFrameMetrics.lastCallbackExitGapNanos.toString() else "-1")
            writer.newLine()
        }
        if (count < MAX_SAMPLES) count++
        val now = System.nanoTime()
        if (now - windowStart >= WINDOW_NANOS) {
            if (windowLogEnabled) report(now) else {
                count = 0
                windowStart = now
            }
        }
    }

    override fun close() {
        traceWriter?.close()
    }

    private fun slot(): Int = count.coerceAtMost(MAX_SAMPLES - 1)

    private fun lap(): Long {
        val now = System.nanoTime()
        val elapsed = now - phaseStart
        phaseStart = now
        return elapsed
    }

    private fun report(now: Long) {
        val seconds = (now - windowStart) / 1e9
        val engine = GameEngine.getInstance()
        val line = buildString {
            append("frames=").append(count)
            append(" fps=").append("%.1f".format(count / seconds))
            append(" tick=").append(engine?.currentTick ?: -1)
            append(" units=").append(BaseUnit.bE.size)
            append(" interval[").append(stats(intervals, count - 1)).append(']')
            append(" work[").append(stats(works, count)).append(']')
            append(" update[").append(stats(updates, count)).append(']')
            append(" draw[").append(stats(draws, count)).append(']')
            append(" layers[").append(stats(layers, count)).append(']')
            append(" snapshot[").append(stats(snapshots, count)).append(']')
        }
        val file = output
        if (file != null) {
            runCatching { file.appendText("${System.currentTimeMillis()} $line\n") }
        } else {
            logger.info("RWXPerf") { line }
        }
        count = 0
        windowStart = now
    }

    private fun stats(values: LongArray, n: Int): String {
        if (n <= 0) return "-"
        val sorted = values.copyOf(n).also { it.sort() }
        fun ms(nanos: Long) = "%.2f".format(nanos / 1e6)
        fun pct(p: Double) = sorted[((n - 1) * p).toInt()]
        return "avg=${ms(sorted.sum() / n)} p50=${ms(pct(0.5))} p95=${ms(pct(0.95))} p99=${ms(pct(0.99))} max=${ms(sorted[n - 1])}"
    }

    companion object {
        private const val MAX_SAMPLES = 4096
        private const val WINDOW_NANOS = 5_000_000_000L

        fun fromEnvironment(): KoolFrameTimeLog? {
            val value = System.getenv("RWX_PERF_LOG")?.takeIf { it.isNotBlank() }
            val trace = System.getenv("RWX_ENGINE_FRAME_TRACE")?.takeIf { it.isNotBlank() }?.let(::File)
            if (value == null && trace == null) return null
            val file = value?.takeUnless { it == "1" || it.equals("true", ignoreCase = true) }?.let(::File)
            return KoolFrameTimeLog(file, value != null, trace)
        }
    }
}
