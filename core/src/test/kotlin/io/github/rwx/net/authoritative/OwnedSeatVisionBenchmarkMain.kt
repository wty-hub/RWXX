package io.github.rwx.net.authoritative

import java.io.BufferedWriter
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.exitProcess

/** Standalone private-fog microbenchmark. No engine, native display, network, test fixture or JUnit. */
object OwnedSeatVisionBenchmarkMain {
    private const val WIDTH = 512
    private const val HEIGHT = 512
    private data class Scenario(val name: String, val layout: String, val circles: Int, val radius: Int)
    private val scenarios = listOf(
        Scenario("clustered-r11", "clustered", 1024, 11), Scenario("spread-r11", "spread", 1024, 11),
        Scenario("clustered-r30", "clustered", 1024, 30), Scenario("spread-r30", "spread", 1024, 30),
        Scenario("sparse-r11", "disjoint", 32, 11), Scenario("sparse-r30", "disjoint", 32, 30),
        Scenario("full-r1024", "full", 4, 1024),
    ).associateBy { it.name }

    private data class Options(val scenario: Scenario, val frames: Int, val warmup: Int,
                               val seed: Int, val deadlineSeconds: Int, val label: String, val output: Path?) {
        companion object {
            fun parse(args: Array<String>): Options {
                val fields = LinkedHashMap<String, String>()
                for (argument in args) {
                    require(argument.startsWith("--") && '=' in argument) { "Expected --name=value" }
                    val key = argument.substring(2).substringBefore('=')
                    require(fields.put(key, argument.substringAfter('=')) == null) { "Duplicate option: $key" }
                }
                require(fields.keys.all { it in setOf("scenario", "frames", "warmup-frames", "seed", "deadline-seconds", "label", "output") })
                val scenario = scenarios[fields["scenario"] ?: "clustered-r11"] ?: error("Unknown scenario")
                val frames = (fields["frames"] ?: "40").toInt().also { require(it == 40 || it == 120) }
                val warmup = (fields["warmup-frames"] ?: "8").toInt().also { require(it in 0..40) }
                val deadline = (fields["deadline-seconds"] ?: "15").toInt().also { require(it in 1..20) }
                val label = (fields["label"] ?: "unspecified").also { require(it.length in 1..64 && it.none { c -> c.code < 32 }) }
                return Options(scenario, frames, warmup, (fields["seed"] ?: "332305").toInt(), deadline, label,
                    fields["output"]?.let { Path.of(it).toAbsolutePath().normalize() })
            }
        }
    }

    private class Sink(path: Path?) : AutoCloseable {
        private val file: BufferedWriter? = path?.let {
            it.parent?.let { parent -> Files.createDirectories(parent) }
            Files.newBufferedWriter(it, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        @Synchronized fun emit(values: Map<String, Any?>) {
            val line = json(values)
            println(line)
            file?.apply { write(line); newLine(); flush() }
        }
        @Synchronized override fun close() { file?.close() }
    }

    private class Counters {
        private val owner = Thread.currentThread().id
        private val bean = ManagementFactory.getThreadMXBean()
        private val allocated = (bean as? com.sun.management.ThreadMXBean)?.takeIf { it.isThreadAllocatedMemorySupported }
        init {
            if (bean.isThreadCpuTimeSupported) runCatching { bean.isThreadCpuTimeEnabled = true }
            allocated?.let { counter -> runCatching { counter.isThreadAllocatedMemoryEnabled = true } }
        }
        fun cpu(): Long? = if (bean.isThreadCpuTimeSupported && bean.isThreadCpuTimeEnabled)
            bean.getThreadCpuTime(owner).takeIf { it >= 0 } else null
        fun allocated(): Long? = allocated?.let { counter ->
            if (counter.isThreadAllocatedMemoryEnabled) counter.getThreadAllocatedBytes(owner).takeIf { it >= 0 } else null
        }
    }

    private class Samples(size: Int) {
        val wall = LongArray(size)
        val cpu = LongArray(size) { -1 }
        val allocated = LongArray(size) { -1 }
        fun <T> measure(index: Int, counters: Counters, operation: () -> T): T {
            val beforeCpu = counters.cpu(); val beforeAllocated = counters.allocated()
            val start = System.nanoTime()
            try { return operation() } finally {
                wall[index] = System.nanoTime() - start
                cpu[index] = beforeCpu?.let { before -> counters.cpu()?.minus(before) } ?: -1
                allocated[index] = beforeAllocated?.let { before -> counters.allocated()?.minus(before) } ?: -1
            }
        }
        fun report(prefix: String, count: Int, result: MutableMap<String, Any?>) {
            val times = wall.copyOf(count).sortedArray()
            result["${prefix}WallMs"] = times.sum() / 1e6
            result["${prefix}P50Ms"] = percentile(times, .50) / 1e6
            result["${prefix}P95Ms"] = percentile(times, .95) / 1e6
            result["${prefix}MaxMs"] = (times.lastOrNull() ?: 0) / 1e6
            result["${prefix}OwnerCpuMs"] = cpu.take(count).takeIf { it.all { n -> n >= 0 } }?.sum()?.div(1e6)
            result["${prefix}OwnerAllocatedBytes"] = allocated.take(count).takeIf { it.all { n -> n >= 0 } }?.sum()
        }
    }

    private class Trace(val scenario: Scenario, frames: Int, seed: Int) {
        val xs = FloatArray(frames * scenario.circles)
        val ys = FloatArray(xs.size)
        val sha256: String
        init {
            val order = IntArray(scenario.circles) { it }
            val random = Random(seed)
            for (at in order.lastIndex downTo 1) {
                val other = random.nextInt(at + 1)
                val value = order[at]; order[at] = order[other]; order[other] = value
            }
            for (frame in 0 until frames) for (index in order.indices) {
                val unit = order[index]
                val baseX: Float
                val baseY: Float
                when (scenario.layout) {
                    "clustered" -> { baseX = 224f + (unit % 32) * 2; baseY = 224f + (unit / 32) * 2 }
                    "spread" -> { baseX = 8f + (unit % 32) * 16; baseY = 8f + (unit / 32) * 16 }
                    "disjoint" -> { baseX = 32f + (unit % 8) * 64; baseY = 32f + (unit / 8) * 64 }
                    "full" -> { baseX = 248f + (unit % 2) * 16; baseY = 248f + (unit / 2) * 16 }
                    else -> error("Unknown layout")
                }
                val at = frame * scenario.circles + index
                xs[at] = baseX + ((frame + unit % 17) % 9 - 4) * .25f
                ys[at] = baseY + ((frame * 3 + unit % 13) % 9 - 4) * .25f
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(scenario.name.toByteArray(Charsets.UTF_8))
            for (value in intArrayOf(WIDTH, HEIGHT, frames, scenario.circles, scenario.radius, seed)) updateInt(digest, value)
            for (index in xs.indices) { updateInt(digest, xs[index].toRawBits()); updateInt(digest, ys[index].toRawBits()) }
            sha256 = hex(digest.digest())
        }
        fun reveal(vision: OwnedSeatVision, frame: Int) {
            val start = frame * scenario.circles
            val end = start + scenario.circles
            for (index in start until end) vision.reveal(xs[index], ys[index], scenario.radius)
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val options = try { Options.parse(args) } catch (failure: Exception) {
            System.err.println("Invalid benchmark options: ${failure.message}")
            exitProcess(2)
        }
        val sink = try { Sink(options.output) } catch (failure: Exception) {
            System.err.println("Cannot create benchmark output: ${failure.javaClass.simpleName}")
            exitProcess(2)
        }
        val completed = AtomicInteger()
        val stage = AtomicReference("starting")
        val finished = AtomicBoolean()
        val processFinished = AtomicBoolean()
        val started = System.nanoTime()
        val metadata = linkedMapOf<String, Any?>(
            "kind" to "private-owned-vision-benchmark", "scope" to "one private grid; no engine, grants, network or realtime capacity claim",
            "label" to options.label, "scenario" to options.scenario.name, "layout" to options.scenario.layout,
            "width" to WIDTH, "height" to HEIGHT, "circlesPerFrame" to options.scenario.circles,
            "radius" to options.scenario.radius, "frames" to options.frames, "warmupFrames" to options.warmup,
            "seed" to options.seed, "deadlineSeconds" to options.deadlineSeconds,
            "javaVersion" to System.getProperty("java.version"), "javaVm" to System.getProperty("java.vm.name"),
            "osName" to System.getProperty("os.name"), "osArch" to System.getProperty("os.arch"),
            "processors" to Runtime.getRuntime().availableProcessors(), "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            "ownedClassSha256" to classSha(OwnedSeatVision::class.java),
            "gridClassSha256" to classSha(OwnedSeatVision.Grid::class.java),
            "harnessClassSha256" to classSha(OwnedSeatVisionBenchmarkMain::class.java),
            "ownedCodeSource" to OwnedSeatVision::class.java.protectionDomain?.codeSource?.location?.toString(),
            "harnessCodeSource" to OwnedSeatVisionBenchmarkMain::class.java.protectionDomain?.codeSource?.location?.toString(),
        )
        val watchdog = thread(isDaemon = true, name = "owned-vision-benchmark-deadline") {
            try {
                val remaining = options.deadlineSeconds * 1_000_000_000L - (System.nanoTime() - started)
                if (remaining > 0) Thread.sleep((remaining + 999_999) / 1_000_000)
                if (!processFinished.get()) {
                    // A blocked output sink must not extend the watchdog indefinitely.
                    thread(isDaemon = true, name = "owned-vision-benchmark-hard-halt") {
                        Thread.sleep(250)
                        Runtime.getRuntime().halt(2)
                    }
                    try {
                        if (finished.compareAndSet(false, true)) sink.emit(linkedMapOf("kind" to "result", "success" to false,
                            "error" to "absolute wall deadline exceeded", "completedFrames" to completed.get(), "stage" to stage.get()))
                    }
                    finally { Runtime.getRuntime().halt(2) }
                }
            } catch (_: InterruptedException) { }
        }
        var exit = 2
        try {
            sink.emit(metadata)
            val report = run(options, completed, stage)
            report["totalWallMs"] = (System.nanoTime() - started) / 1e6
            if (finished.compareAndSet(false, true)) { sink.emit(report); exit = if (report["success"] == true) 0 else 2 }
        } catch (failure: Exception) {
            if (finished.compareAndSet(false, true)) sink.emit(linkedMapOf("kind" to "result", "success" to false,
                "errorClass" to failure.javaClass.simpleName, "error" to failure.message,
                "completedFrames" to completed.get(), "stage" to stage.get()))
        } finally {
            sink.close()
            processFinished.set(true)
            watchdog.interrupt()
        }
        exitProcess(exit)
    }

    private fun run(options: Options, completed: AtomicInteger, stage: AtomicReference<String>): MutableMap<String, Any?> {
        val counters = Counters()
        stage.set("precompute-identical-trace")
        val trace = Trace(options.scenario, options.frames, options.seed)
        val vision = OwnedSeatVision()
        vision.configure(WIDTH, HEIGHT, false)
        val warmup = Samples(1)
        var warmupDigest: ByteArray? = null
        stage.set("warmup")
        warmup.measure(0, counters) {
            repeat(options.warmup) { frame ->
                vision.beginFrame()
                trace.reveal(vision, frame % options.frames)
                warmupDigest = MessageDigest.getInstance("SHA-256").digest(vision.grid().copyBytes())
            }
        }
        stage.set("reset-before-measurement")
        val setup = Samples(1)
        setup.measure(0, counters) { vision.reset(); vision.configure(WIDTH, HEIGHT, false) }
        val refog = Samples(options.frames)
        val reveal = Samples(options.frames)
        val copy = Samples(options.frames)
        val hashing = Samples(options.frames)
        val frameWall = LongArray(options.frames)
        val summary = MessageDigest.getInstance("SHA-256")
        var lastBytes = ByteArray(0)
        var lastHash = ByteArray(0)
        val gcBefore = gc()
        val beforeCpu = counters.cpu(); val beforeAllocated = counters.allocated()
        val measuredStart = System.nanoTime()
        for (frame in 0 until options.frames) {
            val frameStart = System.nanoTime()
            stage.set("refog")
            refog.measure(frame, counters) { vision.beginFrame() }
            stage.set("reveal")
            reveal.measure(frame, counters) { trace.reveal(vision, frame) }
            stage.set("copyBytes")
            lastBytes = copy.measure(frame, counters) { vision.grid().copyBytes() }
            stage.set("full-copy-sha256")
            lastHash = hashing.measure(frame, counters) { MessageDigest.getInstance("SHA-256").digest(lastBytes) }
            updateInt(summary, frame); summary.update(lastHash)
            frameWall[frame] = System.nanoTime() - frameStart
            completed.incrementAndGet()
        }
        val elapsed = System.nanoTime() - measuredStart
        val ownerCpu = beforeCpu?.let { before -> counters.cpu()?.minus(before) }
        val ownerAllocated = beforeAllocated?.let { before -> counters.allocated()?.minus(before) }
        val gcAfter = gc()
        stage.set("validate-actual-final-bytes")
        val byteCounts = LongArray(11)
        for (value in lastBytes) {
            require(value.toInt() in 0..10) { "Private fog produced an illegal byte" }
            byteCounts[value.toInt()]++
        }
        val sorted = frameWall.sortedArray()
        val result = linkedMapOf<String, Any?>(
            "kind" to "result", "success" to true, "scenario" to options.scenario.name,
            "traceSha256" to trace.sha256, "completedFrames" to completed.get(),
            "measuredRevealCalls" to options.frames.toLong() * options.scenario.circles,
            "measuredWallMs" to elapsed / 1e6, "measuredOwnerCpuMs" to ownerCpu?.div(1e6),
            "measuredOwnerAllocatedBytes" to ownerAllocated,
            "frameP50Ms" to percentile(sorted, .50) / 1e6, "frameP95Ms" to percentile(sorted, .95) / 1e6,
            "frameMaxMs" to sorted.last() / 1e6,
            "warmupFinalCopySha256" to warmupDigest?.let(::hex),
            "finalCopySha256" to hex(lastHash), "allFrameCopiesSha256" to hex(summary.digest()),
            "actualSnapshotBytes" to lastBytes.size,
            "actualZeroCells" to byteCounts[0], "actualBelowFiveCells" to byteCounts.take(5).sum(),
            "actualFiveToTenCells" to byteCounts.drop(5).sum(),
            "gcCollectionsDuringMeasurement" to (gcAfter.first - gcBefore.first),
            "gcMillisDuringMeasurement" to (gcAfter.second - gcBefore.second),
            "heapUsedAfterMeasurement" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()),
            "allocationScope" to "owner thread; whole measurement includes copies, digests and instrumentation",
        )
        byteCounts.indices.forEach { result["actualByte$it"] = byteCounts[it] }
        warmup.report("warmup", 1, result); setup.report("freshGridSetup", 1, result)
        refog.report("refog", options.frames, result); reveal.report("reveal", options.frames, result)
        copy.report("copyBytes", options.frames, result); hashing.report("fullCopySha256", options.frames, result)
        stage.set("complete")
        return result
    }

    private fun gc(): Pair<Long, Long> = ManagementFactory.getGarbageCollectorMXBeans().let { beans ->
        beans.sumOf { it.collectionCount.coerceAtLeast(0) } to beans.sumOf { it.collectionTime.coerceAtLeast(0) }
    }
    private fun percentile(values: LongArray, fraction: Double): Long =
        if (values.isEmpty()) 0 else values[(kotlin.math.ceil(values.size * fraction).toInt() - 1).coerceIn(values.indices)]
    private fun updateInt(digest: MessageDigest, value: Int) {
        digest.update((value ushr 24).toByte()); digest.update((value ushr 16).toByte())
        digest.update((value ushr 8).toByte()); digest.update(value.toByte())
    }
    private fun classSha(type: Class<*>): String = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")
        .use { stream -> requireNotNull(stream) { "Missing class bytes" }; hex(MessageDigest.getInstance("SHA-256").digest(stream.readBytes())) }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(values: Map<String, Any?>): String = values.entries.joinToString(",", "{", "}") { (key, value) ->
        "\"${escape(key)}\":" + when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            else -> "\"${escape(value.toString())}\""
        }
    }
    private fun escape(value: String) = buildString { value.forEach { character -> when (character) {
        '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
    } } }
}
