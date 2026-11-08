package io.github.rwx.net.timeline

import java.lang.management.ManagementFactory

/** Offline synthetic publisher benchmark. No engine, native window, socket, or graphics initialization. */
object TimelinePublisherBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { argument ->
            require(argument.startsWith("--") && '=' in argument) { "Expected --name=value" }
            argument.substring(2).substringBefore('=') to argument.substringAfter('=')
        }
        require(options.keys.all { it in setOf("units", "seconds", "deadline-ms", "warmup") })
        val counts = (options["units"] ?: "1000,10000,65536").split(',').map { it.toInt().also { n -> require(n in 1..65536) } }
        val seconds = (options["seconds"] ?: "2").toInt().also { require(it in 1..10) }
        val deadline = (options["deadline-ms"] ?: "120000").toLong().also { require(it in 1000..600000) } * 1_000_000
        val warmup = (options["warmup"] ?: "true").toBooleanStrict()
        println(json(linkedMapOf("kind" to "timeline-publisher-benchmark", "scope" to "synthetic-authorized-state-only",
            "wireVersion" to TimelineProtocol.VERSION, "javaVersion" to System.getProperty("java.version"),
            "processors" to Runtime.getRuntime().availableProcessors(), "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            "ticksPerSecond" to 60, "simulationSeconds" to seconds, "warmup" to warmup)))
        if (warmup) runCase(128, 1, deadline, false)
        for (count in counts) {
            val result = runCase(count, seconds, deadline, true)
            println(json(result))
            if (result["success"] != true) kotlin.system.exitProcess(2)
        }
    }

    private fun runCase(count: Int, seconds: Int, deadline: Long, progress: Boolean): Map<String, Any?> {
        val clock = System.nanoTime()
        val thread = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (thread?.isThreadAllocatedMemorySupported == true && !thread.isThreadAllocatedMemoryEnabled)
            thread.isThreadAllocatedMemoryEnabled = true
        fun allocated(): Long? = if (thread?.isThreadAllocatedMemoryEnabled == true)
            thread.getThreadAllocatedBytes(Thread.currentThread().id).takeIf { it >= 0 } else null
        val allocatedBefore = allocated()
        var ticks = 0
        var observationsNanos = 0L
        var publicationsNanos = 0L
        var snapshots = 0
        var operationBytes = 0L
        var heapPeak = usedHeap()
        var last: TimelineState? = null
        val result = linkedMapOf<String, Any?>("kind" to "result", "units" to count, "seconds" to seconds)
        try {
            val context = context()
            val publisher = TimelinePublisher(context)
            val fog = TimelineBytes(ByteArray(256 * 256))
            val status = TimelineBytes(byteArrayOf(0, 0, 0, 0))
            for (tick in 0..seconds * 60) {
                if (System.nanoTime() - clock > deadline) throw IllegalStateException("Synthetic case exceeded its wall deadline")
                val units = List(count) { index ->
                    TimelineObservedUnit(index.toLong() + 1,
                        TimelineValue.Vec3(1024L + index % 256 * 2048 + tick * 8, 1024L + index / 256 * 2048, 0),
                        (index.toLong() * 97 + tick * 8) % 65536, 25600, 25600,
                        (index % 10000).toLong() + tick * 100, 0, "tank", 1, true)
                }
                val observation = TimelineObservation(tick * 1024L, 1_000_000, status, fog, units)
                val observeStart = System.nanoTime()
                publisher.observe(observation)
                observationsNanos += System.nanoTime() - observeStart
                if (tick % 6 == 0) {
                    val publishStart = System.nanoTime()
                    val state = publisher.publish(tick * 1_000_000_000L / 60)
                    publicationsNanos += System.nanoTime() - publishStart
                    if (state != null) { snapshots++; operationBytes += state.header.operationBytes; last = state }
                }
                ticks++
                heapPeak = maxOf(heapPeak, usedHeap())
                if (progress && tick % 30 == 0) println(json(linkedMapOf("kind" to "progress", "units" to count,
                    "tick" to tick, "elapsedMillis" to (System.nanoTime() - clock) / 1_000_000.0,
                    "publications" to snapshots, "heapUsedBytes" to usedHeap())))
            }
            val state = checkNotNull(last)
            val sizeStart = System.nanoTime()
            val persistentBytes = TimelineCanonical.persistentSize(state)
            val sizeNanos = System.nanoTime() - sizeStart
            val hashStart = System.nanoTime()
            val digest = TimelineCanonical.digest(state)
            val hashNanos = System.nanoTime() - hashStart
            result["success"] = true
            result["persistentBytes"] = persistentBytes
            result["persistentSizeMillis"] = sizeNanos / 1_000_000.0
            result["fullHashMillis"] = hashNanos / 1_000_000.0
            result["fullHashIncludesStaticMap"] = true
            result["digestHex"] = digest.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
            result["objects"] = state.objects.size
            result["curves"] = state.objects.values.sumOf { it.curves.size.toLong() }
            result["segments"] = state.objects.values.sumOf { obj -> obj.curves.values.sumOf { it.segments.size.toLong() } }
            result["pulses"] = state.objects.values.sumOf { obj -> obj.curves.values.sumOf { it.events.size.toLong() } }
            result["observedThrough"] = state.header.observedThrough
            result["sealedThrough"] = state.header.sealedThrough
        } catch (failure: Exception) {
            result["success"] = false
            result["failureClass"] = failure.javaClass.simpleName
            result["failure"] = failure.message ?: "unspecified"
        }
        result["completedObservations"] = ticks
        result["publications"] = snapshots
        result["operationBytesTotal"] = operationBytes
        result["observeMillis"] = observationsNanos / 1_000_000.0
        result["publishMillis"] = publicationsNanos / 1_000_000.0
        result["elapsedMillis"] = (System.nanoTime() - clock) / 1_000_000.0
        result["heapUsedPeakBytes"] = maxOf(heapPeak, usedHeap())
        result["allocatedBytes"] = allocated()?.let { after -> allocatedBefore?.let { after - it } }
        return result
    }

    private fun context(): TimelineContext {
        val count = 256 * 256
        val record = TimelineRecord(listOf(
            TimelineField(1, true, TimelineTlvValue.U32(1)), TimelineField(2, true, TimelineTlvValue.U32(256)),
            TimelineField(3, true, TimelineTlvValue.U32(256)), TimelineField(4, true, TimelineTlvValue.U32(20)),
            TimelineField(5, true, TimelineTlvValue.U32(20)), TimelineField(6, true, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(count)))),
            TimelineField(7, true, TimelineTlvValue.U32(1)), TimelineField(8, true, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(count * 2)))),
            TimelineField(9, true, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(count * 2)))),
            TimelineField(10, true, TimelineTlvValue.Bytes(TimelineBytes(byteArrayOf(0))))))
        return TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }),
            TimelineCanonical.mapKey(record), record, 0, 60)
    }

    private fun usedHeap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
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
