package io.github.rwx.render.canvas

import java.io.File

/**
 * Diagnostic only: where the render-thread command loop actually spends its wall clock.
 *
 * `docs/original-benchmark.md` §6 shows the loop dominates a pass, but the pass command count and the
 * loop time do not scale together, so the cost is not a flat per-command fee. This split separates
 * "many cheap commands" from "few expensive commands" before any batching work is committed to.
 *
 * Measured on the static europe-15p replay with fog: `drawText` was 6.08 M of 7.8 M commands and 50.8 s
 * of the 56.9 s command loop (8.36 us per text against 2.79 us per texture), so the text path is the
 * one worth decomposing.
 */
internal object KoolCanvasCommandTiming {
    private const val KIND_TEXTURE = 0
    private const val KIND_RECT = 1
    private const val KIND_TEXT = 2
    private const val KIND_CIRCLE = 3
    private const val KIND_LINE = 4
    private const val KIND_OTHER = 5
    private const val KIND_TEXT_FONT = 6
    private const val KIND_TEXT_METRICS = 7
    private const val KIND_TEXT_CLIP_ORIGIN = 8
    private const val KIND_TEXT_BUILD = 9
    private const val KINDS = 10
    private val labels = arrayOf("drawTexture", "drawRect", "drawText", "drawCircle", "drawLine", "other",
        "textFontLookup", "textMetrics", "textClipOrigin", "textMeshBuild")

    val enabled: Boolean = System.getenv("RWX_COMMAND_TIMING")?.takeIf { it.isNotBlank() } != null
    private val nanos = LongArray(KINDS)
    private val counts = LongArray(KINDS)

    init {
        val path = System.getenv("RWX_COMMAND_TIMING")?.takeIf { it.isNotBlank() }
        if (path != null) {
            Runtime.getRuntime().addShutdownHook(Thread({
                dumpRunReasons()
            }, "canvas-run-reason-close"))
        }
        if (path != null) {
            Runtime.getRuntime().addShutdownHook(Thread({
                val builder = StringBuilder("kind,commands,totalMs,usPerCommand\n")
                for (index in 0 until KINDS) {
                    val count = counts[index]
                    builder.append(labels[index]).append(',').append(count).append(',')
                        .append(nanos[index] / 1_000_000).append(',')
                        .append(if (count == 0L) 0.0 else nanos[index].toDouble() / count / 1_000.0)
                        .append('\n')
                }
                runCatching { File(path).writeText(builder.toString()) }
            }, "canvas-command-timing-close"))
        }
    }

    fun kindOf(command: KoolCanvasCommand): Int = when (command) {
        is KoolCanvasCommand.DrawTexture -> KIND_TEXTURE
        is KoolCanvasCommand.DrawTextureBatch -> KIND_TEXTURE
        is KoolCanvasCommand.DrawTextureRepeat -> KIND_TEXTURE
        is KoolCanvasCommand.DrawRect -> KIND_RECT
        is KoolCanvasCommand.DrawRectBatch -> KIND_RECT
        is KoolCanvasCommand.DrawFogBatch -> KIND_TEXTURE
        is KoolCanvasCommand.DrawText -> KIND_TEXT
        is KoolCanvasCommand.DrawCircle -> KIND_CIRCLE
        is KoolCanvasCommand.DrawLine -> KIND_LINE
        else -> KIND_OTHER
    }

    fun record(kind: Int, elapsedNanos: Long) {
        nanos[kind] += elapsedNanos
        counts[kind]++
        if (elapsedNanos >= SLOW_COMMAND_NANOS) recordSlow(kind, elapsedNanos)
    }

    /** Diagnostic: why a texture command did not qualify for the instanced run. */
    private val runReasons = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun countRunReason(reason: String) {
        runReasons.computeIfAbsent(reason) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        if (reason != "eligible") RUN_REJECTIONS.incrementAndGet()
    }

    private val RUN_REJECTIONS = java.util.concurrent.atomic.AtomicLong()

    internal fun dumpRunReasons() {
        if (!enabled) return
        println("[RWX canvas] textureRunReasons " + runReasons.entries.sortedByDescending { it.value.get() }
            .joinToString { it.key + "=" + it.value.get() })
    }

    /** Lets the renderer attach its command-loop step attribution to this module's shutdown report. */
    fun reportLoopSplit(block: () -> Unit) {
        if (!enabled) return
        Runtime.getRuntime().addShutdownHook(Thread(block, "canvas-loop-split-report"))
    }

    /**
     * Individual commands that cost >= 5 ms. A pass during a camera pan shows single commands at
     * 100-580 us (measured), and 369 of 25_995 chunks > 10 ms held 7.3 s of the pass time, so the tail
     * is a few very expensive commands rather than the flat per-command fee.
     */
    private fun recordSlow(kind: Int, elapsedNanos: Long) {
        synchronized(slowGate) {
            slowCounts[kind]++
            slowNanos[kind] += elapsedNanos
            if (slowNanos[kind] > slowReportedNanos[kind]) {
                slowReportedNanos[kind] += 2_000_000_000L
                println("[RWX canvas] slowCommand kind=" + labels[kind] + " us=" + (elapsedNanos / 1_000) +
                        " count=" + slowCounts[kind] + " totalMs=" + (slowNanos[kind] / 1_000_000) +
                        " label=" + currentLabel + " step=" + currentStep)
            }
        }
    }

    /** What the slow command actually contained, so the caller can name the producer. */
    @Volatile
    var currentLabel: String = ""
    @Volatile
    var currentStep: String = ""

    /** The command's own identity, recorded by the renderer before it starts the sub-steps. */
    fun beginCommand(label: String) {
        if (!enabled) return
        currentLabel = label
        currentStep = ""
    }

    fun step(name: String) {
        if (!enabled) return
        currentStep = name
    }

    private const val SLOW_COMMAND_NANOS = 5_000_000L
    private val slowGate = Any()
    private val slowCounts = LongArray(KINDS)
    private val slowNanos = LongArray(KINDS)
    private val slowReportedNanos = LongArray(KINDS)

    fun textFontLookup() = KIND_TEXT_FONT
    fun textMetrics() = KIND_TEXT_METRICS
    fun textClipOrigin() = KIND_TEXT_CLIP_ORIGIN
    fun textMeshBuild() = KIND_TEXT_BUILD
}
