package io.github.rwx.render.canvas

import java.io.BufferedWriter
import java.io.File

/**
 * Diagnostic only: per-pass canvas command distribution with consecutive-run lengths.
 *
 * `docs/original-benchmark.md` §6 proves the only order-of-magnitude lever left is the number of canvas
 * commands per pass, but not which producer owns them. This collector answers that directly: for every
 * recorded pass it groups commands by a batch identity (kind + texture + paint signature + state shape)
 * and reports how many commands arrived in each consecutive-run bucket, so the largest "many commands,
 * tiny runs" bucket names the producer worth batching. The biggest pass sizes by volume are profiled in
 * detail because the main render pass is not the only recorded one.
 */
internal object KoolCanvasCommandProfile {
    private const val BUCKETS = 7
    private const val TRACKED_PASSES = 5
    private const val TOP_SIZE_CANDIDATES = 24
    private val bucketLabels = arrayOf("run1", "run2to4", "run5to16", "run17to64", "run65to256",
        "run257to1024", "run1025plus")

    private class Stats {
        var commands = 0L
        var runs = 0L
        val buckets = LongArray(BUCKETS)
    }

    private class PassProfile(val size: Int) {
        val kindCommands = HashMap<String, Long>()
        val kindRuns = HashMap<String, Long>()
        val textureCommands = HashMap<String, Long>()
        val signatureRuns = HashMap<String, LongArray>()
        val runBuckets = LongArray(BUCKETS)
    }

    /**
     * Text reuse across frames decides whether caching glyph geometry can pay off at all: if every draw
     * carries a distinct string at a distinct origin, a cache only adds a lookup. Counts the distinct
     * `font@text@x@y` keys seen per pass.
     */
    private class TextReuseTracker {
        val previous = HashSet<String>()
        val current = HashSet<String>()
        var draws = 0L
        var repeatDraws = 0L
        var generations = 0L

        fun note(key: String) {
            draws++
            if (key in current || key in previous) repeatDraws++
            current.add(key)
        }

        /** Called once per rendered frame: a key seen in this or the previous frame would be cacheable. */
        fun rotate() {
            generations++
            previous.clear()
            previous.addAll(current)
            current.clear()
        }
    }

    private val writer: BufferedWriter?
    /** True only for opt-in diagnostic runs; the flush hook allocates its key string, so gate it here. */
    val enabled: Boolean get() = writer != null
    private val gate = Any()
    private var passCount = 0L
    private var totalCommands = 0L
    private var largestPass = 0
    private val emptyPasses = LongArray(4)
    private val kindStats = HashMap<String, Stats>()
    private val signatureStats = HashMap<String, Stats>()
    private val sizeVolume = HashMap<Int, Long>()
    private val profiles = ArrayList<PassProfile>()
    private val offscreenFlushes = HashMap<String, Long>()
    private val offscreenFlushSizes = HashMap<String, HashMap<Int, Long>>()
    private var emptyFlushes = 0L
    private var emptyFlushNanos = 0L
    private var nonEmptyFlushes = 0L
    private var nonEmptyFlushNanos = 0L
    private var nonEmptyFlushCommands = 0L
    private val textReuse = TextReuseTracker()

    init {
        val path = System.getenv("RWX_CANVAS_COMMAND_PROFILE")?.takeIf { it.isNotBlank() }
        writer = path?.let { target ->
            File(target).also { it.parentFile?.mkdirs() }.bufferedWriter().also { out ->
                out.write("section,key,commands,runs")
                for (label in bucketLabels) out.append(',').append(label)
                out.append('\n')
                Runtime.getRuntime().addShutdownHook(Thread({ dump(out) }, "canvas-command-profile-close"))
            }
        }
        if (writer != null) println("[RWX canvas] canvasCommandProfile=" + path)
    }

    fun observe(commands: List<KoolCanvasCommand>) {
        if (writer == null) return
        synchronized(gate) {
            passCount++
            totalCommands += commands.size
            if (commands.size > largestPass) largestPass = commands.size
            emptyPasses[when {
                commands.isEmpty() -> 0
                commands.size <= 8 -> 1
                commands.size <= 64 -> 2
                else -> 3
            }]++
            val size = commands.size
            val profile = profileFor(size)
            if (commands.isEmpty()) return
            var index = 0
            while (index < commands.size) {
                val start = index
                val signature = signatureOf(commands[index])
                index++
                while (index < commands.size && signatureOf(commands[index]) == signature) index++
                val command = commands[start]
                val runLength = index - start
                val bucket = bucketOf(runLength)
                val kind = kindOf(command)
                record(kindStats, kind, runLength, bucket)
                record(signatureStats, signature, runLength, bucket)
                if (profile != null) record(profile, kind, textureIdOf(command), signature, runLength, bucket)
                val text = commands[start] as? KoolCanvasCommand.DrawText
                if (text != null) {
                    textReuse.note(text.paint.textSize.toString() + '\u0000' + text.paint.typefaceKey + '\u0000' +
                            text.text + '\u0000' + text.baseline.x + '\u0000' + text.baseline.y)
                }
            }
        }
    }

    /** Marks a frame boundary so text reuse is measured across frames rather than inside one pass. */
    fun endFrame() {
        if (writer == null) return
        synchronized(gate) { textReuse.rotate() }
    }

    /** Diagnostic hook so a host can report target-path decisions without a shutdown hook of its own. */
    fun report(block: () -> Unit) {
        if (writer == null) return
        block()
    }

    /** Called once per observed pass; kept so a caller that batches observations can still split them. */
    fun endPass(commands: List<KoolCanvasCommand>) {
        // All state is accumulated in [observe]; this hook exists for symmetry with the caller.
    }

    /**
     * A target flush that found no new commands still costs a full snapshot, and the main buffer flushes
     * every target it samples, so an empty flush is pure overhead. Counting them by target shows whether
     * the command volume comes from real content or from repeated empty commits.
     */
    fun offscreenFlush(textureId: String, commands: Int, elapsedNanos: Long) {
        if (writer == null) return
        synchronized(gate) {
            offscreenFlushes[textureId] = (offscreenFlushes[textureId] ?: 0L) + 1
            if (commands == 0) {
                emptyFlushes++
                emptyFlushNanos += elapsedNanos
            } else {
                nonEmptyFlushes++
                nonEmptyFlushNanos += elapsedNanos
                nonEmptyFlushCommands += commands
            }
            val sizes = offscreenFlushSizes.getOrPut(textureId) { HashMap() }
            sizes[commands] = (sizes[commands] ?: 0L) + 1
        }
    }
    /** Volume-descending pass sizes: the main render pass is not the only recorded one. */
    private fun profileFor(size: Int): PassProfile? {
        sizeVolume[size] = (sizeVolume[size] ?: 0L) + size
        for (profile in profiles) if (profile.size == size) return profile
        if (profiles.size < TRACKED_PASSES) {
            return PassProfile(size).also { profiles += it }
        }
        var smallest = profiles[0]
        for (profile in profiles) {
            if ((sizeVolume[profile.size] ?: 0L) < (sizeVolume[smallest.size] ?: 0L)) smallest = profile
        }
        if (sizeVolume.size > TOP_SIZE_CANDIDATES && (sizeVolume[size] ?: 0L) > (sizeVolume[smallest.size] ?: 0L)) {
            profiles.remove(smallest)
            return PassProfile(size).also { profiles += it }
        }
        return null
    }

    private fun record(
        profile: PassProfile,
        kind: String,
        textureId: String,
        signature: String,
        runLength: Int,
        bucket: Int,
    ) {
        profile.kindCommands[kind] = (profile.kindCommands[kind] ?: 0L) + runLength
        profile.kindRuns[kind] = (profile.kindRuns[kind] ?: 0L) + 1
        profile.textureCommands[textureId] = (profile.textureCommands[textureId] ?: 0L) + runLength
        val runs = profile.signatureRuns.getOrPut(signature) { LongArray(BUCKETS) }
        runs[bucket]++
        profile.runBuckets[bucket]++
    }

    private fun textureIdOf(command: KoolCanvasCommand): String = when (command) {
        is KoolCanvasCommand.DrawTexture -> command.texture.id.value
        else -> "-"
    }

    private fun record(target: HashMap<String, Stats>, key: String, runLength: Int, bucket: Int) {
        val stat = target.getOrPut(key) { Stats() }
        stat.commands += runLength
        stat.runs++
        stat.buckets[bucket]++
    }

    private fun bucketOf(runLength: Int): Int = when {
        runLength <= 1 -> 0
        runLength <= 4 -> 1
        runLength <= 16 -> 2
        runLength <= 64 -> 3
        runLength <= 256 -> 4
        runLength <= 1024 -> 5
        else -> 6
    }

    private fun kindOf(command: KoolCanvasCommand): String = when (command) {
        is KoolCanvasCommand.Clear -> "clear"
        is KoolCanvasCommand.DrawTexture -> "drawTexture"
        is KoolCanvasCommand.DrawTextureBatch -> "drawTextureBatch"
        is KoolCanvasCommand.DrawTextureRepeat -> "drawTextureRepeat"
        is KoolCanvasCommand.DrawRect -> "drawRect"
        is KoolCanvasCommand.DrawRectBatch -> "drawRectBatch"
        is KoolCanvasCommand.DrawFogBatch -> "drawFogBatch"
        is KoolCanvasCommand.DrawLine -> "drawLine"
        is KoolCanvasCommand.DrawCircle -> "drawCircle"
        is KoolCanvasCommand.DrawText -> "drawText"
    }

    private fun signatureOf(command: KoolCanvasCommand): String = when (command) {
        is KoolCanvasCommand.DrawFogBatch -> "fogBatch|n=" + command.masks.size + "|" + stateKey(command.state)
        is KoolCanvasCommand.DrawRectBatch ->
            "rectBatch|n=" + command.rects.size + "|" + paintKey(command.paint) + stateKey(command.state)
        is KoolCanvasCommand.DrawTextureBatch ->
            "texBatch|id=" + command.texture.id.value + "|n=" + command.quads.size + "|" +
                paintKey(command.paint) + stateKey(command.state)
        is KoolCanvasCommand.Clear ->
            "clear|rt=" + command.renderTarget?.value + "|blend=" + command.blendMode + "|c=" +
                    Integer.toHexString(command.color.argb)

        is KoolCanvasCommand.DrawTexture ->
            "tex|id=" + command.texture.id.value + "|a=" + command.texture.hasAlpha + "|p=" +
                    command.texture.premultipliedAlpha + "|full=" + command.sourceIsFullTexture + "|" +
                    paintKey(command.paint) + stateKey(command.state)

        is KoolCanvasCommand.DrawTextureRepeat ->
            "texRep|id=" + command.texture.id.value + "|n=" + command.repeat + "|" +
                    paintKey(command.paint) + stateKey(command.state)

        is KoolCanvasCommand.DrawRect ->
            "rect|" + paintKey(command.paint) + stateKey(command.state)

        is KoolCanvasCommand.DrawLine -> "line|" + paintKey(command.paint) + stateKey(command.state)

        is KoolCanvasCommand.DrawCircle -> "circle|" + paintKey(command.paint) + stateKey(command.state)

        is KoolCanvasCommand.DrawText ->
            "text|len=" + command.text.length + "|" + paintKey(command.paint) + stateKey(command.state)
    }

    private fun paintKey(paint: KoolCanvasPaint): String = buildString {
        append(paint.textureFilter).append('~').append(paint.blendMode).append('~')
        append(paint.style).append('~').append(Integer.toHexString(paint.color.argb)).append('~')
        append(paint.alphaMultiplier).append('~').append(paint.textureEffect?.let { it::class.simpleName })
    }

    private fun stateKey(state: KoolCanvasState): String =
        "|t=" + (state.transform === KoolCanvasTransform.Identity) + "|clip=" + (state.clip != null) +
                "|rt=" + state.renderTarget?.value + "|role=" + state.drawRole

    private fun dump(out: BufferedWriter) {
        synchronized(gate) {
            try {
                out.write("summary,passCount,$passCount,commands,$totalCommands,largestPass,$largestPass\n")
                out.write("emptySplit,size0,size1to8,size9to64,size65plus"
                        + emptyPasses.joinToString("") { "," + it } + "\n")
                for ((kind, stat) in kindStats.entries.sortedByDescending { it.value.commands }) {
                    writeRow(out, "kind", kind, stat)
                }
                for ((signature, stat) in signatureStats.entries.sortedByDescending { it.value.commands }.take(24)) {
                    writeRow(out, "signature", signature, stat)
                }
                for ((size, volume) in sizeVolume.entries.sortedByDescending { it.value }.take(8)) {
                    out.write("sizeVolume,$size,$volume\n")
                }
                for ((texture, flushes) in offscreenFlushes.entries.sortedByDescending { it.value }.take(20)) {
                    val sizes = offscreenFlushSizes[texture] ?: continue
                    val top = sizes.entries.sortedByDescending { it.value }.take(4)
                        .joinToString("|") { it.key.toString() + "x" + it.value }
                    out.write("offscreenFlush,$texture,flushes,$flushes,sizes,$top\n")
                }
                out.write("flushSplit,empty,$emptyFlushes,emptyMs,${emptyFlushNanos / 1_000_000}," +
                        "nonEmpty,$nonEmptyFlushes,nonEmptyMs,${nonEmptyFlushNanos / 1_000_000}," +
                        "nonEmptyCommands,$nonEmptyFlushCommands,emptyUs," +
                        "${if (emptyFlushes == 0L) 0 else emptyFlushNanos / emptyFlushes / 1000}," +
                        "nonEmptyUs,${if (nonEmptyFlushes == 0L) 0 else nonEmptyFlushNanos / nonEmptyFlushes / 1000}\n")
                out.write("textReuse,draws,${textReuse.draws},repeatDraws,${textReuse.repeatDraws}," +
                        "frames,${textReuse.generations},repeatPct," +
                        "${if (textReuse.draws == 0L) 0.0 else textReuse.repeatDraws * 100.0 / textReuse.draws}\n")
                for (profile in profiles.sortedByDescending { it.size }) {
                    val passes = (sizeVolume[profile.size] ?: 0L) / profile.size
                    out.write("pass,size," + profile.size + ",passes," + passes + ",runBuckets,p")
                    for (bucket in profile.runBuckets) out.append(',').append(bucket.toString())
                    out.append('\n')
                    for ((kind, count) in profile.kindCommands.entries.sortedByDescending { it.value }) {
                        out.write("pass,size," + profile.size + ",kind," + kind + ",commands," + count +
                                ",runs," + (profile.kindRuns[kind] ?: 0L) + "\n")
                    }
                    for ((texture, count) in profile.textureCommands.entries.sortedByDescending { it.value }
                        .take(14)) {
                        out.write("pass,size," + profile.size + ",texture," + texture + ",commands," + count + "\n")
                    }
                    for ((signature, runs) in profile.signatureRuns.entries.sortedByDescending { it.value[0] }
                        .take(10)) {
                        out.write("pass,size," + profile.size + ",singleton," + signature.replace(',', ';') +
                                ",totalRuns," + runs.sum() + ",singletons," + runs[0] + "\n")
                    }
                }
                out.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeRow(out: BufferedWriter, section: String, key: String, stat: Stats) {
        out.write(section)
        out.append(',').append(key.replace(',', ';'))
        out.append(',').append(stat.commands.toString())
        out.append(',').append(stat.runs.toString())
        for (bucket in stat.buckets) out.append(',').append(bucket.toString())
        out.append('\n')
    }
}
