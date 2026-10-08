package io.github.rwx.kool.vulkan

import de.fabmax.kool.pipeline.backend.stats.BackendStats
import io.github.rwx.render.canvas.CanvasStageTrace
import java.io.File
import java.util.Locale

/** Native-stage counters supplement engine / Canvas timing; emitted only for successful submissions. */
internal object VulkanBackendMetrics {
    private val target = System.getenv("RWX_VK_METRICS")?.takeIf { it.isNotBlank() }
    private val stageTrace = CanvasStageTrace.fromEnvironment("RWX_VK_TRACE")
    val enabled: Boolean get() = target != null || stageTrace.enabled
    fun stageStart(): Long = stageTrace.start()
    fun stageEnd(stage: String, start: Long, value0: Long = -1, value1: Long = -1, value2: Long = -1) =
        stageTrace.record(stage, start, value0, value1, value2)
    private var frameOrdinal = 0L
    private var start = System.nanoTime()
    private var frames = 0
    private var bufferBytes = 0L
    private var textureBytes = 0L
    private var uploadNanos = 0L
    private var gpuNanos = 0L
    private var drawCommands = 0L
    private var previousAllocations = 0L
    private var fenceWaitNanos = 0L
    private var acquireNanos = 0L
    private var presentNanos = 0L
    private var submitNanos = 0L
    private val fenceSubmissions = mutableMapOf<Long, Long>()

    inline fun measureSubmit(fence: Long, call: () -> Int): Int {
        return measureNative("submit", call) { submitNanos += it }.also { result ->
            // Record the exact native submission argument, before any swapchain index advances.
            if (stageTrace.enabled && result == 0 && fence != 0L) fenceSubmissions[fence] = frameOrdinal + 1
        }
    }

    inline fun measureFenceWait(fence: Long, call: () -> Int): Int {
        if (!enabled) return call()
        val started = System.nanoTime()
        var result = Int.MIN_VALUE
        return try { call().also { result = it } } finally {
            val ended = System.nanoTime()
            fenceWaitNanos += ended - started
            stageTrace.recordCompleted("fence", started, ended, result.toLong(), frameOrdinal + 1,
                fenceSubmissions[fence] ?: -1L)
        }
    }

    inline fun measureAcquire(call: () -> Int): Int {
        return measureNative("acquire", call) { acquireNanos += it }
    }

    inline fun measurePresent(call: () -> Int): Int {
        return measureNative("present", call) { presentNanos += it }
    }

    private inline fun measureNative(stage: String, call: () -> Int, accumulate: (Long) -> Unit): Int {
        if (!enabled) return call()
        val started = System.nanoTime()
        var result = Int.MIN_VALUE
        return try { call().also { result = it } } finally {
            val ended = System.nanoTime()
            accumulate(ended - started)
            stageTrace.recordCompleted(stage, started, ended, result.toLong(), frameOrdinal + 1)
        }
    }

    fun submitted(state: VulkanUploadState) {
        frameOrdinal++
        if (stageTrace.enabled) {
            // Diagnostic linkage: the fence row names the submission that it is actually waiting for.
            stageTrace.record("submission-work", stageTrace.start(), frameOrdinal,
                BackendStats.numDrawCommands.toLong(), BackendStats.pipelines.size.toLong())
            // Observed backend GPU query, which may belong to an earlier in-flight frame.
            stageTrace.record("submission-observed-gpu", stageTrace.start(), frameOrdinal,
                state.backend.frameGpuTime.inWholeNanoseconds, BackendStats.totalBufferSize)
        }
        val traceStart = stageTrace.start()
        stageTrace.record("submission-summary", traceStart, state.bufferUploadBytes, state.textureUploadBytes, state.uploadNanos)
        val output = target ?: return
        frames++
        bufferBytes += state.bufferUploadBytes
        textureBytes += state.textureUploadBytes
        uploadNanos += state.uploadNanos
        gpuNanos += state.backend.frameGpuTime.inWholeNanoseconds
        drawCommands += BackendStats.numDrawCommands
        val now = System.nanoTime()
        val seconds = (now - start) / 1e9
        if (seconds < 5) return
        val line = String.format(Locale.ROOT,
            "{\"seconds\":%.3f,\"submittedFrames\":%d,\"uploadCpuAvgMs\":%.4f,\"gpuAvgMs\":%.4f,\"drawCommandsAvg\":%.2f,\"bufferUploadBytes\":%d,\"textureUploadBytes\":%d,\"stagingAllocations\":%d,\"stagingResidentBytes\":%d,\"retirementCallbacks\":%d,\"pipelineCount\":%d,\"gpuBufferBytes\":%d,\"gpuTextureBytes\":%d,\"framebufferWidth\":%d,\"framebufferHeight\":%d,\"sampleCount\":%d,\"presentMode\":%d,\"targetFps\":%d,\"fenceWaitAvgMs\":%.4f,\"acquireAvgMs\":%.4f,\"queuePresentAvgMs\":%.4f,\"queueSubmitAvgMs\":%.4f}",
            seconds, frames, uploadNanos.toDouble() / frames / 1e6, gpuNanos.toDouble() / frames / 1e6,
            drawCommands.toDouble() / frames, bufferBytes, textureBytes, state.allocationCount - previousAllocations,
            state.residentStagingBytes, state.retirement.pendingCount, BackendStats.pipelines.size,
            BackendStats.totalBufferSize, BackendStats.totalTextureSize,
            state.backend.swapchain.width, state.backend.swapchain.height,
            state.backend.swapchain.numSamples,
            VulkanFrameLifecycle.presentMode, state.backend.ctx.maxFrameRate,
            fenceWaitNanos.toDouble() / frames / 1e6, acquireNanos.toDouble() / frames / 1e6,
            presentNanos.toDouble() / frames / 1e6, submitNanos.toDouble() / frames / 1e6)
        if (output == "1") println("RWXVulkanMetrics $line") else File(output).appendText(line + "\n")
        previousAllocations = state.allocationCount
        frames = 0; bufferBytes = 0; textureBytes = 0; uploadNanos = 0; gpuNanos = 0; drawCommands = 0
        fenceWaitNanos = 0; acquireNanos = 0; presentNanos = 0; submitNanos = 0; start = now
    }
}
