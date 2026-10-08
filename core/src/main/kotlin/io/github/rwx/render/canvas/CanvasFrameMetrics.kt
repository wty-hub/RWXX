package io.github.rwx.render.canvas

import io.github.rwx.logger
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Accepted queue presents, completed engine pictures and actually displayed new pictures differ. */
data class CanvasFrameRateSample(
    val seconds: Double,
    val acceptedPresentFps: Double,
    val producedSnapshotHz: Double,
    val freshSnapshotHz: Double,
    val simulationTicksPerSecond: Double,
    val repeatRatio: Double,
    val p95Ms: Double = 0.0,
    val p99Ms: Double = 0.0,
)

/** Small counters always run; detailed intervals and file output remain opt-in. */
object CanvasFrameMetrics {
    private val trace = System.getenv("RWX_FRAME_TRACE")?.takeIf { it.isNotBlank() }?.let { path ->
        File(path).also { it.absoluteFile.parentFile?.mkdirs() }.bufferedWriter().apply {
            write("acceptedPresentNanos,generation,sequence\n")
        }
    }
    private val target = System.getenv("RWX_FRAME_METRICS")?.takeIf { it.isNotBlank() }
        ?: if (trace != null) "1" else null
    // Keep the diagnostic file open and buffered, like the presentation trace. Reopening and
    // flushing it on the render owner every five seconds can manufacture a measurement stall.
    private val metricsWriter = target?.takeUnless { it == "1" }?.let { path ->
        File(path).also { it.absoluteFile.parentFile?.mkdirs() }.bufferedWriter(bufferSize = 65_536)
    }
    private val hud = CanvasFrameRateCounter(System.nanoTime())
    private val diagnostics = target?.let { CanvasFrameRateCounter(System.nanoTime(), collectIntervals = true) }
    private var chosenSequence = -1L
    private var chosenGeneration = -1L
    @Volatile private var published: CanvasFrameRateSample? = null
    private var textCacheRecorded = false
    private var textCacheCreated = 0L
    private var textCacheExactHits = 0L
    private var textCacheReused = 0L
    private var textCachePruned = 0L
    private var textCacheScanCandidates = 0L
    private var textCacheLive = 0
    private var textCachePeak = 0
    internal class FreezeScratchRecord(val owner: Long, val pool: KoolCanvasFreezeScratchPool)
    @Volatile private var freezeScratchRecorded: FreezeScratchRecord? = null
    private var gpuCellRecorded = false
    private var gpuCellCreated = 0L
    private var gpuCellHits = 0L
    private var gpuCellRetired = 0L
    private var gpuCellLive = 0
    private var gpuCellPending = 0

    internal fun freezeScratchPool(record: FreezeScratchRecord) {
        if (diagnostics != null) freezeScratchRecorded = record
    }

    /**
     * Count of actually replayed frames, and the duration of the most recent one.
     *
     * Exposed because the engine-owner loop advances far more often than a frame is rendered (measured
     * ~610 owner iterations per second against ~120 rendered frames), so a host tracing owner iterations
     * cannot tell a rendering row from a non-rendering one without this. Its per-frame trace needs it to
     * derive a meaningful frame budget.
     */
    val replaySequence: Long get() = KoolCanvasRenderProbe.sequence

    val lastReplayNanos: Long get() = KoolCanvasRenderProbe.lastReplayNanos

    /** Gap between the end of one replay and the start of the next; see the probe. */
    val lastReplayGapNanos: Long get() = KoolCanvasRenderProbe.lastReplayGapNanos

    /** Gap before the replay was entered: the render loop had nothing to render. */
    val lastCallbackEntryGapNanos: Long get() = KoolCanvasRenderProbe.lastCallbackEntryGapNanos

    /** Gap after the previous replay returned: scene render, present and swapchain. */
    val lastCallbackExitGapNanos: Long get() = KoolCanvasRenderProbe.lastCallbackExitGapNanos

    /**
     * GPU offscreen target counters, published once per render frame.
     *
     * `desktop/tools/map_pan_builtin_comparison.py` requires these in every frame-metrics record and
     * refuses a run whose measured windows show no `hits` growth, so a GPU map cell path that never
     * samples its own images cannot be reported as a passing A/B.
     */
    internal fun gpuMapCellCache(created: Long, hits: Long, retired: Long, live: Int, pending: Int) {
        if (diagnostics == null) return
        gpuCellRecorded = true
        gpuCellCreated = created
        gpuCellHits = hits
        gpuCellRetired = retired
        gpuCellLive = live
        gpuCellPending = pending
    }

    fun snapshot(): CanvasFrameRateSample? = published

    /** True when per-sample counters are being written; avoids hot-path work in unmeasured runs. */
    internal val gpuCountersEnabled: Boolean get() = diagnostics != null

    /** Render-thread counters; the existing diagnostic sample writes them without per-mesh I/O. */
    internal fun textMeshCache(created: Long, exactHits: Long, reused: Long, pruned: Long,
                               scanCandidates: Long, live: Int, peak: Int) {
        if (diagnostics == null) return
        textCacheRecorded = true
        textCacheCreated = created
        textCacheExactHits = exactHits
        textCacheReused = reused
        textCachePruned = pruned
        textCacheScanCandidates = scanCandidates
        textCacheLive = live
        textCachePeak = peak
    }

    @Synchronized fun produced(envelope: FrameEnvelope) {
        CanvasInputResponseTrace.produced(envelope)
        hud.produced(envelope.simulationTick, envelope.generation)
        diagnostics?.produced(envelope.simulationTick, envelope.generation)
    }

    @Synchronized fun chosen(sequence: Long, generation: Long) {
        chosenSequence = sequence; chosenGeneration = generation
    }

    fun inputSampled(kind: String, value: Int): Long = CanvasInputResponseTrace.sampled(kind, value)
    fun inputApplied(id: Long, x: Float, y: Float, zoom: Float) = CanvasInputResponseTrace.applied(id, x, y, zoom)

    /** Invoked after an accepted Vulkan present or a visible OpenGL swap, including repeated snapshots. */
    @JvmStatic @Synchronized fun presented() {
        if (chosenSequence < 0) return
        val now = System.nanoTime()
        CanvasInputResponseTrace.presented(chosenSequence, chosenGeneration, now)
        hud.presented(chosenSequence, chosenGeneration, now)
        hud.sample(now, 1_000_000_000L)?.let { published = it }
        val counter = diagnostics ?: return
        trace?.write("$now,$chosenGeneration,$chosenSequence\n")
        counter.presented(chosenSequence, chosenGeneration, now)
        val sample = counter.sample(now, 5_000_000_000L) ?: return
        val textCache = if (!textCacheRecorded) "" else
            ",\"textMeshCache\":{\"created\":$textCacheCreated,\"exactHits\":$textCacheExactHits,\"reused\":$textCacheReused,\"pruned\":$textCachePruned,\"scanCandidates\":$textCacheScanCandidates,\"live\":$textCacheLive,\"peak\":$textCachePeak}"
        val freezeScratch = freezeScratchRecorded?.let { record -> record.pool.stats().let {
            ",\"freezeScratchPool\":{\"owner\":${record.owner},\"enabled\":${it.enabled}," +
                "\"borrows\":${it.borrows},\"reused\":${it.reused},\"created\":${it.created},\"discarded\":${it.discarded}," +
                "\"idle\":${it.idle},\"active\":${it.active},\"peakMemoEntries\":${it.peakMemoEntries}," +
                "\"peakVisitingEntries\":${it.peakVisitingEntries},\"peakSeenAllocations\":${it.peakSeenAllocations},\"closed\":${it.closed}}"
        } } ?: ""
        val gpuCells = if (!gpuCellRecorded) "" else
            ",\"gpuMapCellCache\":{\"created\":$gpuCellCreated,\"hits\":$gpuCellHits," +
                "\"retired\":$gpuCellRetired,\"live\":$gpuCellLive,\"pending\":$gpuCellPending}"
        val line = "{\"sampleNanos\":$now,\"seconds\":%.3f,\"acceptedPresentFps\":%.2f,\"presentFps\":%.2f,\"producedSnapshotHz\":%.2f,\"freshSnapshotHz\":%.2f,\"simulationTicksPerSecond\":%.2f,\"repeatRatio\":%.5f,\"p95Ms\":%.3f,\"p99Ms\":%.3f$textCache$freezeScratch$gpuCells}".format(
            Locale.ROOT, sample.seconds, sample.acceptedPresentFps, sample.acceptedPresentFps,
            sample.producedSnapshotHz, sample.freshSnapshotHz, sample.simulationTicksPerSecond,
            sample.repeatRatio, sample.p95Ms, sample.p99Ms)
        if (target == "1") logger.info("RWXFrameMetrics") { line }
        else metricsWriter?.apply { write(line); newLine() }
    }

    @JvmStatic @Synchronized fun close() {
        CanvasInputResponseTrace.close()
        try { trace?.close() } finally { metricsWriter?.close() }
    }
}

/** Callers provide synchronization; deterministic clock input makes the rate contract testable. */
internal class CanvasFrameRateCounter(startNanos: Long, collectIntervals: Boolean = false) {
    private var produced = 0L
    private var simulatedTicks = 0L
    private var previousProducedTick = -1
    private var producedGeneration = -1L
    private var lastSequence = -1L
    private var lastGeneration = -1L
    private var presented = 0L
    private var fresh = 0L
    private var windowStart = startNanos
    private var previousPresentation = -1L
    private val intervals = if (collectIntervals) LongArray(16_384) else null
    private var intervalCount = 0

    fun produced(tick: Int, generation: Long) {
        produced++
        if (generation == producedGeneration && previousProducedTick >= 0) {
            simulatedTicks += (tick - previousProducedTick).coerceAtLeast(0)
        }
        previousProducedTick = tick
        producedGeneration = generation
    }

    fun presented(sequence: Long, generation: Long, now: Long) {
        if (previousPresentation >= 0 && intervals != null && intervalCount < intervals.size) {
            intervals[intervalCount++] = now - previousPresentation
        }
        previousPresentation = now
        presented++
        if (lastSequence != sequence || lastGeneration != generation) fresh++
        lastSequence = sequence
        lastGeneration = generation
    }

    fun sample(now: Long, minimumNanos: Long): CanvasFrameRateSample? {
        if (now - windowStart < minimumNanos || presented == 0L) return null
        val seconds = (now - windowStart) / 1e9
        val sorted = intervals?.copyOf(intervalCount)?.also { it.sort() }
        fun percentile(p: Double) = if (sorted == null || sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()] / 1e6
        val result = CanvasFrameRateSample(seconds, presented / seconds, produced / seconds, fresh / seconds,
            simulatedTicks / seconds, (presented - fresh).toDouble() / presented, percentile(.95), percentile(.99))
        produced = 0
        simulatedTicks = 0
        presented = 0
        fresh = 0
        intervalCount = 0
        windowStart = now
        return result
    }
}

/** Only the explicitly tagged original FPS draw is replaced; arbitrary unit or UI text is untouched. */
internal object CanvasPerformanceHud {    fun command(command: KoolCanvasCommand.DrawText, sample: CanvasFrameRateSample?): KoolCanvasCommand.DrawText {
        if (command.state.drawRole != KoolCanvasDrawRole.PerformanceHud) return command
        val text = if (sample == null) "${command.text} (engine)" else
            "render ${sample.acceptedPresentFps.roundToInt()} FPS / new ${sample.freshSnapshotHz.roundToInt()}/s / " +
                "engine ${sample.producedSnapshotHz.roundToInt()}/s / repeat ${(sample.repeatRatio * 100).roundToInt()}%"
        return command.copy(text = text)
    }
}
