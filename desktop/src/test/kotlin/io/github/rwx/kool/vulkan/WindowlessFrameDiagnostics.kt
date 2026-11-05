package io.github.rwx.kool.vulkan

import java.io.File
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface WindowlessFrameObserver {
    fun beforeFrame()
    fun afterFrame(failure: Throwable?)
}

/** Scalars supplied by the test harness; no envelope, scene, lease or resource graph is retained. */
internal class WindowlessFrameState {
    var generated = 0L
    var adopted = 0L
    var tick = -1
    var phase = 0 // 0 startup, 1 warmup, 2 sample, 3 drain
}

/**
 * One event surrounds a real renderFrame call. JFR's begin/end timestamps fall inside their
 * corresponding before/after nanoTime brackets; they are not assumed equal to nanoTime.
 */
@Name("rwx.WindowlessGpuFrame")
@Label("RWX windowless GPU diagnostic frame")
@StackTrace(false)
internal class WindowlessGpuFrameEvent : Event() {
    @JvmField var frameIndex = 0L
    @JvmField var wallBeginNanos = -1L
    @JvmField var wallEndNanos = -1L
    @JvmField var wallDurationNanos = -1L
    @JvmField var eventBeginBeforeNanos = -1L
    @JvmField var eventBeginAfterNanos = -1L
    @JvmField var eventEndBeforeNanos = -1L
    @JvmField var eventEndAfterNanos = -1L
    @JvmField var epochBeginReadBeforeNanos = -1L
    @JvmField var epochBeginMillis = -1L
    @JvmField var epochBeginReadAfterNanos = -1L
    @JvmField var epochEndReadBeforeNanos = -1L
    @JvmField var epochEndMillis = -1L
    @JvmField var epochEndReadAfterNanos = -1L
    @JvmField var threadBegin = -1L
    @JvmField var threadEnd = -1L
    @JvmField var commitThreadId = -1L
    @JvmField var cpuBeginNanos = -1L
    @JvmField var cpuEndNanos = -1L
    @JvmField var cpuDurationNanos = -1L
    @JvmField var cpuValid = false
    @JvmField var nativeOrdinalBefore = -1L
    @JvmField var nativeOrdinalAfter = -1L
    @JvmField var generatedBefore = -1L
    @JvmField var generatedAfter = -1L
    @JvmField var adoptedBefore = -1L
    @JvmField var adoptedAfter = -1L
    @JvmField var tickBefore = -1L
    @JvmField var tickAfter = -1L
    @JvmField var phaseBefore = -1L
    @JvmField var phaseAfter = -1L
    @JvmField var failure = false
    @JvmField var metadataValid = false
    @JvmField var jfrEnabled = false
}

/**
 * TEST ONLY. Hooks are serially paired, but the render coroutine may resume on another thread.
 * There is no per-frame file IO, growing collection or asynchronous writer queue. A failed
 * beforeFrame (including overflow) must not be followed by afterFrame for that attempted frame.
 */
internal class WindowlessFrameDiagnostics(
    private val capacity: Int,
    private val snapshot: (WindowlessFrameState) -> Unit,
    private val nativeOrdinal: () -> Long,
) : WindowlessFrameObserver {
    private enum class Column(val jsonName: String, val boolean: Boolean = false) {
        WALL_BEGIN("wallBeginNanos"), WALL_END("wallEndNanos"), WALL_DURATION("wallDurationNanos"),
        EVENT_BEGIN_BEFORE("eventBeginBeforeNanos"), EVENT_BEGIN_AFTER("eventBeginAfterNanos"),
        EVENT_END_BEFORE("eventEndBeforeNanos"), EVENT_END_AFTER("eventEndAfterNanos"),
        EPOCH_BEGIN_BEFORE("epochBeginReadBeforeNanos"), EPOCH_BEGIN("epochBeginMillis"),
        EPOCH_BEGIN_AFTER("epochBeginReadAfterNanos"), EPOCH_END_BEFORE("epochEndReadBeforeNanos"),
        EPOCH_END("epochEndMillis"), EPOCH_END_AFTER("epochEndReadAfterNanos"),
        THREAD_BEGIN("threadBegin"), THREAD_END("threadEnd"), COMMIT_THREAD("commitThreadId"),
        CPU_BEGIN("cpuBeginNanos"), CPU_END("cpuEndNanos"), CPU_DURATION("cpuDurationNanos"),
        CPU_VALID("cpuValid", true), NATIVE_BEFORE("nativeOrdinalBefore"), NATIVE_AFTER("nativeOrdinalAfter"),
        GENERATED_BEFORE("generatedBefore"), GENERATED_AFTER("generatedAfter"),
        ADOPTED_BEFORE("adoptedBefore"), ADOPTED_AFTER("adoptedAfter"),
        TICK_BEFORE("tickBefore"), TICK_AFTER("tickAfter"),
        PHASE_BEFORE("phaseBefore"), PHASE_AFTER("phaseAfter"),
        FAILURE("failure", true), METADATA_VALID("metadataValid", true),
        JFR_ENABLED("jfrEnabled", true), JFR_COMMIT_ATTEMPTED("jfrCommitAttempted", true),
    }

    init { require(capacity > 0) { "Windowless timing capacity must be positive" } }

    private val columns = Array(Column.entries.size) { LongArray(capacity) { -1L } }
    private val beginState = WindowlessFrameState()
    private val endState = WindowlessFrameState()
    private val cpuClock = ManagementFactory.getThreadMXBean()
    private val cpuSupported = runCatching { cpuClock.isCurrentThreadCpuTimeSupported }.getOrDefault(false)
    @Volatile private var recordedFrames = 0
    @Volatile private var activeIndex = -1
    private var activeEvent: WindowlessGpuFrameEvent? = null
    private var overflowed = false
    private var timingsWritten = false

    private fun set(column: Column, row: Int, value: Long) { columns[column.ordinal][row] = value }
    private fun get(column: Column, row: Int): Long = columns[column.ordinal][row]

    private fun currentCpuNanos(): Long {
        if (!cpuSupported) return -1L
        return runCatching {
            if (cpuClock.isThreadCpuTimeEnabled) cpuClock.currentThreadCpuTime else -1L
        }.getOrDefault(-1L)
    }

    private fun captureState(state: WindowlessFrameState) {
        state.generated = 0L; state.adopted = 0L; state.tick = -1; state.phase = 0
        snapshot(state)
        check(state.phase in 0..3) { "Unknown windowless diagnostic phase: ${state.phase}" }
    }

    override fun beforeFrame() {
        val wallBegin = System.nanoTime()
        check(activeIndex < 0) { "Windowless timing hooks overlap or are not paired" }
        val row = recordedFrames
        if (row >= capacity) {
            overflowed = true
            error("Windowless timing capacity exceeded ($capacity); no rows were overwritten")
        }
        check(!timingsWritten) { "Cannot record after writing windowless timings" }
        val event = WindowlessGpuFrameEvent()
        set(Column.WALL_BEGIN, row, wallBegin)
        set(Column.THREAD_BEGIN, row, Thread.currentThread().threadId())
        set(Column.EPOCH_BEGIN_BEFORE, row, System.nanoTime())
        set(Column.EPOCH_BEGIN, row, System.currentTimeMillis())
        set(Column.EPOCH_BEGIN_AFTER, row, System.nanoTime())
        captureState(beginState)
        set(Column.GENERATED_BEFORE, row, beginState.generated)
        set(Column.ADOPTED_BEFORE, row, beginState.adopted)
        set(Column.TICK_BEFORE, row, beginState.tick.toLong())
        set(Column.PHASE_BEFORE, row, beginState.phase.toLong())
        set(Column.NATIVE_BEFORE, row, nativeOrdinal())
        set(Column.CPU_BEGIN, row, currentCpuNanos())
        set(Column.JFR_ENABLED, row, if (event.isEnabled) 1L else 0L)
        set(Column.EVENT_BEGIN_BEFORE, row, System.nanoTime())
        event.begin()
        set(Column.EVENT_BEGIN_AFTER, row, System.nanoTime())
        activeEvent = event
        activeIndex = row
    }

    override fun afterFrame(failure: Throwable?) {
        val wallEnd = System.nanoTime()
        val threadEnd = Thread.currentThread().threadId()
        val cpuEnd = currentCpuNanos()
        val row = activeIndex
        check(row >= 0) { "afterFrame called without a successful beforeFrame" }
        val event = checkNotNull(activeEvent)
        set(Column.WALL_END, row, wallEnd)
        set(Column.WALL_DURATION, row, wallEnd - get(Column.WALL_BEGIN, row))
        set(Column.THREAD_END, row, threadEnd)
        set(Column.EPOCH_END_BEFORE, row, System.nanoTime())
        set(Column.EPOCH_END, row, System.currentTimeMillis())
        set(Column.EPOCH_END_AFTER, row, System.nanoTime())
        var diagnosticFailure: Throwable? = null
        try {
            captureState(endState)
            set(Column.GENERATED_AFTER, row, endState.generated)
            set(Column.ADOPTED_AFTER, row, endState.adopted)
            set(Column.TICK_AFTER, row, endState.tick.toLong())
            set(Column.PHASE_AFTER, row, endState.phase.toLong())
            set(Column.NATIVE_AFTER, row, nativeOrdinal())
            set(Column.METADATA_VALID, row, 1L)
        } catch (error: Throwable) {
            diagnosticFailure = error
            set(Column.METADATA_VALID, row, 0L)
        }
        set(Column.FAILURE, row, if (failure != null || diagnosticFailure != null) 1L else 0L)
        set(Column.EVENT_END_BEFORE, row, System.nanoTime())
        event.end()
        set(Column.EVENT_END_AFTER, row, System.nanoTime())
        val commitThread = Thread.currentThread().threadId()
        set(Column.COMMIT_THREAD, row, commitThread)
        val cpuBegin = get(Column.CPU_BEGIN, row)
        val cpuValid = get(Column.THREAD_BEGIN, row) == threadEnd && threadEnd == commitThread &&
            cpuBegin >= 0L && cpuEnd >= cpuBegin
        set(Column.CPU_VALID, row, if (cpuValid) 1L else 0L)
        set(Column.CPU_BEGIN, row, if (cpuValid) cpuBegin else -1L)
        set(Column.CPU_END, row, if (cpuValid) cpuEnd else -1L)
        set(Column.CPU_DURATION, row, if (cpuValid) cpuEnd - cpuBegin else -1L)
        populate(event, row)
        try {
            set(Column.JFR_COMMIT_ATTEMPTED, row, 1L)
            event.commit() // An attempt is not proof that a recording retained this event.
        } finally {
            recordedFrames = row + 1
            activeEvent = null
            activeIndex = -1
        }
        diagnosticFailure?.let { throw it }
    }

    private fun populate(event: WindowlessGpuFrameEvent, row: Int) {
        event.frameIndex = row.toLong() + 1L
        event.wallBeginNanos = get(Column.WALL_BEGIN, row)
        event.wallEndNanos = get(Column.WALL_END, row)
        event.wallDurationNanos = get(Column.WALL_DURATION, row)
        event.eventBeginBeforeNanos = get(Column.EVENT_BEGIN_BEFORE, row)
        event.eventBeginAfterNanos = get(Column.EVENT_BEGIN_AFTER, row)
        event.eventEndBeforeNanos = get(Column.EVENT_END_BEFORE, row)
        event.eventEndAfterNanos = get(Column.EVENT_END_AFTER, row)
        event.epochBeginReadBeforeNanos = get(Column.EPOCH_BEGIN_BEFORE, row)
        event.epochBeginMillis = get(Column.EPOCH_BEGIN, row)
        event.epochBeginReadAfterNanos = get(Column.EPOCH_BEGIN_AFTER, row)
        event.epochEndReadBeforeNanos = get(Column.EPOCH_END_BEFORE, row)
        event.epochEndMillis = get(Column.EPOCH_END, row)
        event.epochEndReadAfterNanos = get(Column.EPOCH_END_AFTER, row)
        event.threadBegin = get(Column.THREAD_BEGIN, row)
        event.threadEnd = get(Column.THREAD_END, row)
        event.commitThreadId = get(Column.COMMIT_THREAD, row)
        event.cpuBeginNanos = get(Column.CPU_BEGIN, row)
        event.cpuEndNanos = get(Column.CPU_END, row)
        event.cpuDurationNanos = get(Column.CPU_DURATION, row)
        event.cpuValid = get(Column.CPU_VALID, row) == 1L
        event.nativeOrdinalBefore = get(Column.NATIVE_BEFORE, row)
        event.nativeOrdinalAfter = get(Column.NATIVE_AFTER, row)
        event.generatedBefore = get(Column.GENERATED_BEFORE, row)
        event.generatedAfter = get(Column.GENERATED_AFTER, row)
        event.adoptedBefore = get(Column.ADOPTED_BEFORE, row)
        event.adoptedAfter = get(Column.ADOPTED_AFTER, row)
        event.tickBefore = get(Column.TICK_BEFORE, row)
        event.tickAfter = get(Column.TICK_AFTER, row)
        event.phaseBefore = get(Column.PHASE_BEFORE, row)
        event.phaseAfter = get(Column.PHASE_AFTER, row)
        event.failure = get(Column.FAILURE, row) == 1L
        event.metadataValid = get(Column.METADATA_VALID, row) == 1L
        event.jfrEnabled = get(Column.JFR_ENABLED, row) == 1L
    }

    /** Writes only completed hook pairs. A killed process cannot manufacture a completed file. */
    fun write(output: File) {
        check(activeIndex < 0) { "Cannot write while a windowless frame is active" }
        check(!timingsWritten) { "Windowless timings were already written" }
        Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
            for (row in 0 until recordedFrames) {
                val json = buildJsonObject {
                    put("frameIndex", row.toLong() + 1L)
                    for (column in Column.entries) {
                        val value = get(column, row)
                        if (column.boolean) put(column.jsonName, value == 1L) else put(column.jsonName, value)
                    }
                }
                writer.appendLine(json.toString())
            }
        }
        timingsWritten = true
    }

    fun summary(): JsonObject {
        var failed = 0; var cpuValid = 0; var jfrEnabled = 0; var metadataInvalid = 0
        var phaseCrossings = 0; var sampleBeginFrames = 0; var sampleEndFrames = 0
        var wallTotal = 0L; var cpuTotal = 0L
        for (row in 0 until recordedFrames) {
            if (get(Column.FAILURE, row) == 1L) failed++
            if (get(Column.CPU_VALID, row) == 1L) { cpuValid++; cpuTotal += get(Column.CPU_DURATION, row) }
            if (get(Column.JFR_ENABLED, row) == 1L) jfrEnabled++
            if (get(Column.METADATA_VALID, row) != 1L) metadataInvalid++
            if (get(Column.PHASE_BEFORE, row) != get(Column.PHASE_AFTER, row)) phaseCrossings++
            if (get(Column.PHASE_BEFORE, row) == 2L) sampleBeginFrames++
            if (get(Column.PHASE_AFTER, row) == 2L) sampleEndFrames++
            wallTotal += get(Column.WALL_DURATION, row)
        }
        return buildJsonObject {
            put("kind", "test-only-windowless-frame-diagnostics")
            put("jfrEvent", "rwx.WindowlessGpuFrame")
            put("capacity", capacity); put("recordedFrames", recordedFrames)
            put("overflowed", overflowed); put("unpairedFrame", activeIndex >= 0)
            put("failedFrames", failed); put("metadataInvalidFrames", metadataInvalid)
            put("cpuTimeSupported", cpuSupported); put("cpuValidFrames", cpuValid)
            put("cpuInvalidFrames", recordedFrames - cpuValid); put("validThreadCpuNanosTotal", cpuTotal)
            put("jfrEnabledFrames", jfrEnabled); put("jfrEventsRetainedVerified", false)
            put("timingsWritten", timingsWritten); put("phaseBoundaryCrossingFrames", phaseCrossings)
            put("phaseCodes", buildJsonObject {
                put("0", "startup"); put("1", "warmup"); put("2", "sample"); put("3", "drain")
            })
            put("sampleFramesByBeginPhase", sampleBeginFrames); put("sampleFramesByEndPhase", sampleEndFrames)
            put("phaseSemantics", "Phase fields describe the harness state/next work at each snapshot, not a completed-frame label; crossing warmup or generation-stop boundaries changes phase within a frame")
            put("sampleSelection", "Use generatedAfter > requestedWarmupFrames && generatedAfter <= requestedWarmupFrames + requestedSampleFrames && generatedAfter > generatedBefore; do not use phaseAfter alone")
            put("recordedWallDurationNanosTotal", wallTotal)
            if (recordedFrames > 0) {
                put("firstWallBeginNanos", get(Column.WALL_BEGIN, 0))
                put("lastWallEndNanos", get(Column.WALL_END, recordedFrames - 1))
            }
            put("storage", "Fixed primitive columns; no growing queue, per-frame file IO, retained Throwable or scene/resource graph")
            put("completion", "Rows contain completed before/after hook pairs after renderFrame returned or threw; failed calls are marked. No row is synthesized for an interrupted in-flight frame. Timings are written only after the loop; a killed process may leave no timings file")
            put("epochBracket", "Each epochMillis read occurs within its matching nanoTime before/after bounds; epoch milliseconds remain quantized and may jump")
            put("jfrBracket", "JFR begin timestamp is bracketed by eventBeginBefore/AfterNanos; end timestamp by eventEndBefore/AfterNanos. Actual recording retention and correspondence require post-run validation")
            put("cpuSemantics", "Current-thread CPU only across matching begin/end/commit thread IDs; unsupported, disabled, negative, regressing or migrated samples have cpuValid=false and CPU values -1")
            put("scope", "Full renderFrame callback including fixed 60Hz pacing and observer overhead; not screen/native FPS, exclusive GPU time, production owner-loop timing or an original-client comparison")
        }
    }
}
