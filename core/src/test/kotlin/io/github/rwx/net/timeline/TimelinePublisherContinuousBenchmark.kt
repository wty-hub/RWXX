package io.github.rwx.net.timeline

import java.lang.management.ManagementFactory
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.system.exitProcess

/** One independent, synthetic continuous-motion case. All times are reported with verification separated. */
object TimelinePublisherContinuousBenchmark {
    private const val SECOND = 60L * 1024
    internal data class Settings(val units: Int, val seconds: Int, val deadlineMillis: Long, val expectFinalSealed: Boolean)
    internal fun parse(args: Array<String>): Settings {
        val options = LinkedHashMap<String, String>()
        for (argument in args) {
            require(argument.startsWith("--") && '=' in argument) { "Expected --name=value" }
            val name = argument.substring(2).substringBefore('=')
            require(name in setOf("units", "seconds", "deadline-ms", "expect-final-sealed") && name !in options) { "Unknown or repeated option" }
            options[name] = argument.substringAfter('=')
        }
        return Settings((options["units"] ?: "1000").toInt().also { require(it in setOf(1000, 10000, 65536)) },
            (options["seconds"] ?: "8").toInt().also { require(it in setOf(2, 8, 10, 30)) },
            (options["deadline-ms"] ?: "120000").toLong().also { require(it in 1000..600000) },
            (options["expect-final-sealed"] ?: "false").toBooleanStrict())
    }

    @JvmStatic fun main(args: Array<String>) {
        val settings = parse(args)
        val start = System.nanoTime()
        val finished = AtomicBoolean()
        // The separate Python launcher is the external deadline. This independent daemon
        // is a second stop even when the publisher is stuck inside one large operation.
        Thread({
            Thread.sleep(settings.deadlineMillis + 2000)
            if (!finished.get()) Runtime.getRuntime().halt(124)
        }, "timeline-continuous-hard-deadline").apply { isDaemon = true; start() }
        val heapPeak = AtomicLong(usedHeap())
        val memorySampler = Thread({
            while (!finished.get()) {
                heapPeak.accumulateAndGet(usedHeap(), ::maxOf)
                try { Thread.sleep(10) } catch (_: InterruptedException) { return@Thread }
            }
        }, "timeline-continuous-heap-sampler").apply { isDaemon = true; start() }
        var success = false
        try {
            emit(linkedMapOf("kind" to "ready", "scope" to "synthetic-authorized-publisher-only",
                "units" to settings.units, "seconds" to settings.seconds, "ticksPerSecond" to 60,
                "wireVersion" to TimelineProtocol.VERSION, "deadlineMillis" to settings.deadlineMillis,
                "expectFinalSealed" to settings.expectFinalSealed,
                "maxHeapBytes" to Runtime.getRuntime().maxMemory(), "javaVersion" to System.getProperty("java.version"),
                "publisherBinding" to artifactBinding(), "trajectory" to "position +8Q, heading +8Q, progress +16Q per tick",
                "heapScope" to "sampled whole JVM, including current and one prior state needed for sealed-prefix proof",
                "hashSchedule" to "one complete actual authorized-state SHA per simulation second; not wall-clock 1 Hz"))
            success = runCase(settings, start, heapPeak)
        } finally { finished.set(true); memorySampler.interrupt() }
        exitProcess(if (success) 0 else 2)
    }

    private class Counters {
        private val bean = ManagementFactory.getThreadMXBean()
        private val allocations = bean as? com.sun.management.ThreadMXBean
        init {
            if (bean.isCurrentThreadCpuTimeSupported && !bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
            if (allocations?.isThreadAllocatedMemorySupported == true && !allocations.isThreadAllocatedMemoryEnabled)
                allocations.isThreadAllocatedMemoryEnabled = true
        }
        fun cpu() = if (bean.isCurrentThreadCpuTimeSupported) bean.currentThreadCpuTime.takeIf { it >= 0 } else null
        fun allocated() = if (allocations?.isThreadAllocatedMemoryEnabled == true)
            allocations.getThreadAllocatedBytes(Thread.currentThread().id).takeIf { it >= 0 } else null
    }
    private class Phase(val counters: Counters) {
        var wallNanos = 0L; var cpuNanos = 0L; var allocatedBytes = 0L; var calls = 0L
        fun <T> measure(block: () -> T): T {
            val wall = System.nanoTime(); val cpu = counters.cpu(); val allocated = counters.allocated()
            try { return block() } finally {
                calls++; wallNanos += System.nanoTime() - wall
                cpu?.let { old -> counters.cpu()?.let { cpuNanos += it - old } }
                allocated?.let { old -> counters.allocated()?.let { allocatedBytes += it - old } }
            }
        }
        fun report() = linkedMapOf("calls" to calls, "wallMillis" to wallNanos / 1_000_000.0,
            "cpuMillis" to if (counters.cpu() == null) null else cpuNanos / 1_000_000.0,
            "allocatedBytes" to if (counters.allocated() == null) null else allocatedBytes)
    }

    private fun runCase(settings: Settings, start: Long, heapPeak: AtomicLong): Boolean {
        val measurementStart = System.nanoTime()
        val counters = Counters(); val startCpu = counters.cpu(); val startAllocated = counters.allocated()
        val build = Phase(counters); val observe = Phase(counters); val publish = Phase(counters)
        val verifyPrefix = Phase(counters); val fullHash = Phase(counters); val inspect = Phase(counters)
        val finish = Phase(counters); val verifyAll = Phase(counters)
        val deadline = start + settings.deadlineMillis * 1_000_000
        fun checkDeadline() { check(System.nanoTime() < deadline) { "Continuous benchmark exceeded absolute deadline" } }
        val publisher = TimelinePublisher(context())
        val fog = TimelineBytes(ByteArray(256 * 256)); val status = TimelineBytes(byteArrayOf(0, 0, 0, 0))
        var previous: TimelineState? = null
        var completedTicks = -1; var publications = 0L; var operationBytes = 0L
        var rawPeak = 0L; var maximumRecords = 0L; var maximumPersistent = 0L
        var prefixTransitions = 0L; var nonemptyPrefixTransitions = 0L
        var verifiedSamples = 0L; var simulationSecondHashPasses = 0L
        var finalDigest: TimelineBytes? = null; var finalStatistics: Map<String, Any?>? = null
        var failure: String? = null; var failureClass: String? = null
        fun accept(state: TimelineState) {
            val old = previous
            if (old != null) verifyPrefix.measure {
                assertSealedPrefix(old, state); prefixTransitions++
                if (old.header.sealedThrough > maxOf(old.header.retainedFrom, state.header.retainedFrom)) nonemptyPrefixTransitions++
            }
            previous = state; publications++; operationBytes += state.header.operationBytes
        }
        try {
            for (tick in 0..settings.seconds * 60) {
                checkDeadline()
                val observation = build.measure {
                    TimelineObservation(tick * 1024L, 1_000_000, status, fog,
                        List(settings.units) { unit(it, tick) })
                }
                observe.measure { publisher.observe(observation) }
                completedTicks = tick
                if (tick % 6 == 0) {
                    inspect.measure { rawPeak = maxOf(rawPeak, publisher.retainedRawSampleCount) }
                    publish.measure { publisher.publish(tick * 1_000_000_000L / 60) }?.let(::accept)
                }
                if (tick % 60 == 0) {
                    val current = checkNotNull(previous)
                    // A real complete SHA pass each simulated second, even when no new
                    // state was published. No cached digest or incremental checksum.
                    val digest = fullHash.measure { TimelineCanonical.digest(current) }
                    simulationSecondHashPasses++
                    val statistics = inspect.measure { statistics(current) }
                    maximumRecords = maxOf(maximumRecords, statistics.getValue("records") as Long)
                    maximumPersistent = maxOf(maximumPersistent, statistics.getValue("persistentBytes") as Long)
                    heapPeak.accumulateAndGet(usedHeap(), ::maxOf)
                    emit(linkedMapOf("kind" to "sample", "tick" to tick, "elapsedMillis" to elapsed(start),
                        "publications" to publications, "operationBytesTotal" to operationBytes,
                        "rawSamples" to publisher.retainedRawSampleCount, "sampledRawSamplesPeak" to rawPeak,
                        "heapUsedBytes" to usedHeap(), "heapPeakSampledBytes" to heapPeak.get(),
                        "simulationSecondHashPasses" to simulationSecondHashPasses, "digestHex" to hex(digest), "state" to statistics))
                }
            }
            checkDeadline()
            // Public API shared with the old artifact: advance only the injected wall
            // clock, not observation time. Older policies need not seal a matching
            // prediction here; report that distinction without changing the trajectory.
            finish.measure { publisher.publish(settings.seconds * 1_000_000_000L + 5_000_000_000L) }?.let(::accept)
            val final = checkNotNull(previous)
            check(final.header.observedThrough == settings.seconds * SECOND)
            if (settings.expectFinalSealed) check(final.header.sealedThrough == final.header.observedThrough) {
                "Expected policy to seal the final observed range after the clock-only flush"
            }
            check(final.header.retainedFrom == 0L) { "Fixture no longer retains every measured observation" }
            verifyAll.measure {
                for ((handle, obj) in final.objects) if (obj.kind == TimelineObjectKind.UNIT) {
                    checkDeadline()
                    val key = checkNotNull(publisher.unitKey(handle)); check(key in 1..settings.units.toLong())
                    verifyUnitSamples(obj, key.toInt() - 1, settings.seconds * 60)
                    verifiedSamples += (settings.seconds * 60L + 1) * 3
                }
                check(verifiedSamples == settings.units * (settings.seconds * 60L + 1) * 3)
            }
            finalDigest = fullHash.measure { TimelineCanonical.digest(final) }
            finalStatistics = inspect.measure { statistics(final) }
        } catch (problem: Exception) {
            failure = problem.message ?: "unspecified"; failureClass = problem.javaClass.simpleName
        }
        var finalStatisticsFailure: String? = null
        if (finalStatistics == null) (publisher.latest ?: previous)?.let { lastCommitted ->
            try { finalStatistics = inspect.measure { statistics(lastCommitted) } }
            catch (problem: Exception) { finalStatisticsFailure = problem.javaClass.simpleName }
        }
        finalStatistics?.let {
            maximumRecords = maxOf(maximumRecords, it.getValue("records") as Long)
            maximumPersistent = maxOf(maximumPersistent, it.getValue("persistentBytes") as Long)
        }
        heapPeak.accumulateAndGet(usedHeap(), ::maxOf)
        emit(linkedMapOf("kind" to "result", "success" to (failure == null), "units" to settings.units,
            "seconds" to settings.seconds, "completedTick" to completedTicks, "failureClass" to failureClass,
            "failure" to failure, "publications" to publications, "operationBytesTotal" to operationBytes,
            "operationBytesScope" to "actual publisher transaction bodies, not TCP/frame bytes",
            "sampledRawSamplesPeak" to rawPeak, "sampledMaximumRecords" to maximumRecords,
            "sampledMaximumPersistentBytes" to maximumPersistent, "heapPeakSampledBytes" to heapPeak.get(),
            "elapsedMillis" to elapsed(start), "measurementMillis" to elapsed(measurementStart),
            "startupMillis" to (measurementStart - start) / 1_000_000.0, "ownerCpuMillis" to startCpu?.let { before -> counters.cpu()?.let { (it - before) / 1_000_000.0 } },
            "ownerAllocatedBytes" to startAllocated?.let { before -> counters.allocated()?.minus(before) },
            "prefixTransitionsChecked" to prefixTransitions, "nonemptySealedPrefixTransitions" to nonemptyPrefixTransitions,
            "observationAttributeSamplesChecked" to verifiedSamples,
            "simulationSecondHashPasses" to simulationSecondHashPasses, "totalFullHashPasses" to fullHash.calls,
            "hashSchedule" to "simulation seconds, not wall-clock seconds", "digestHex" to finalDigest?.let(::hex),
            "finalState" to finalStatistics, "finalStateStatisticsFailure" to finalStatisticsFailure,
            "fullySealedObservedRange" to (publisher.latest?.let { it.header.sealedThrough == it.header.observedThrough }),
            "expectFinalSealed" to settings.expectFinalSealed,
            "phases" to linkedMapOf("constructObservations" to build.report(), "observe" to observe.report(),
                "publish" to publish.report(), "sealedPrefixVerification" to verifyPrefix.report(),
                "fullStateHash" to fullHash.report(), "inspectStatistics" to inspect.report(),
                "finalPublicClockFlush" to finish.report(), "allObservedSamplesVerification" to verifyAll.report())))
        return failure == null
    }

    internal fun unit(index: Int, tick: Int) = TimelineObservedUnit(index.toLong() + 1,
        TimelineValue.Vec3(1024L + index % 256 * 2048L + tick * 8L, 1024L + index / 256 * 2048L, 0),
        (index.toLong() * 97 + tick * 8L) % 65536, 25600, 25600,
        index % 10000 + tick * 16L, 0, "tank", 1, true)

    internal fun scalar(segment: TimelineSegment, mode: TimelineMode, time: Long, component: Int = 0): Long {
        require(time >= segment.lo && time < segment.hi)
        fun value(value: TimelineValue) = when (value) {
            is TimelineValue.UInt -> value.value
            is TimelineValue.Vec3 -> when (component) { 0 -> value.x; 1 -> value.y; 2 -> value.z; else -> error("Component") }
            else -> error("Unregistered synthetic value")
        }
        if (segment is TimelineSegment.Step) return value(segment.value)
        segment as TimelineSegment.Interpolated
        val first = value(segment.value0); val last = value(segment.value1)
        if (first == last) return first
        val delta = if (mode == TimelineMode.ANGLE) angleDelta(first, last) else last - first
        val denominator = segment.anchor1Time - segment.anchor0Time
        val offset = time - segment.anchor0Time
        val rounded = try {
            val numerator = Math.addExact(Math.multiplyExact(first, denominator), Math.multiplyExact(delta, offset))
            val quotient = numerator / denominator; val remainder = numerator % denominator
            // Compare |remainder| with the ceiling half without abs(Long.MIN_VALUE).
            if (abs(remainder) >= denominator / 2 + denominator % 2) quotient + if (numerator < 0) -1 else 1 else quotient
        } catch (_: ArithmeticException) {
            val n = BigInteger.valueOf(first) * BigInteger.valueOf(denominator) + BigInteger.valueOf(delta) * BigInteger.valueOf(offset)
            val d = BigInteger.valueOf(denominator); val qr = n.abs().divideAndRemainder(d)
            val magnitude = qr[0] + if (qr[1].shiftLeft(1) >= d) BigInteger.ONE else BigInteger.ZERO
            (if (n.signum() < 0) -magnitude else magnitude).longValueExact()
        }
        return if (mode == TimelineMode.ANGLE) Math.floorMod(rounded, 65536L) else rounded
    }

    internal fun verifyUnitSamples(obj: TimelineObject, index: Int, ticks: Int) {
        for (attribute in intArrayOf(1, 2, 5)) {
            val curve = obj.curves.getValue(attribute); var at = 0
            for (tick in 0..ticks) {
                val time = tick * 1024L
                while (at < curve.segments.size && curve.segments[at].hi <= time) at++
                check(at < curve.segments.size) { "Missing observation in final curve" }
                val segment = curve.segments[at]
                if (attribute == 1) {
                    check(abs(scalar(segment, curve.mode, time) - (1024L + index % 256 * 2048L + tick * 8L)) <= 64)
                    check(abs(scalar(segment, curve.mode, time, 1) - (1024L + index / 256 * 2048L)) <= 64)
                    check(abs(scalar(segment, curve.mode, time, 2)) <= 64)
                } else if (attribute == 2) {
                    check(abs(angleDelta(scalar(segment, curve.mode, time), (index.toLong() * 97 + tick * 8L) % 65536)) <= 182)
                } else check(abs(scalar(segment, curve.mode, time) - (index % 10000 + tick * 16L)) <= 1)
            }
        }
    }

    internal fun assertSealedPrefix(old: TimelineState, next: TimelineState) {
        val from = maxOf(old.header.retainedFrom, next.header.retainedFrom); val until = old.header.sealedThrough
        check(next.header.sealedThrough >= until && next.header.observedThrough >= old.header.observedThrough)
        if (from >= until) return
        for ((handle, obj) in old.objects) {
            val current = next.objects.getValue(handle)
            check(obj.kind == current.kind && obj.observedFrom == current.observedFrom && obj.descriptor == current.descriptor && obj.close == current.close)
            for ((id, curve) in obj.curves) {
                val other = current.curves.getValue(id)
                check(curve.mode == other.mode && curve.valueType == other.valueType && curve.metadata == other.metadata && curve.criticalAttribute == other.criticalAttribute)
                check(curve.events.isEmpty() && other.events.isEmpty()) // This fixture emits no pulses.
                var a = 0; var b = 0
                while (a < curve.segments.size && curve.segments[a].hi <= from) a++
                while (b < other.segments.size && other.segments[b].hi <= from) b++
                while (a < curve.segments.size && curve.segments[a].lo < until) {
                    check(b < other.segments.size)
                    val left = curve.segments[a++]; val right = other.segments[b++]
                    check(maxOf(from, left.lo) == maxOf(from, right.lo) && minOf(until, left.hi) == minOf(until, right.hi))
                    check(left.predicted == right.predicted)
                    when (left) {
                        is TimelineSegment.Step -> check(right is TimelineSegment.Step && left.value == right.value)
                        is TimelineSegment.Interpolated -> check(right is TimelineSegment.Interpolated &&
                            left.anchor0Time == right.anchor0Time && left.anchor1Time == right.anchor1Time &&
                            left.value0 == right.value0 && left.value1 == right.value1)
                    }
                }
                check(b == other.segments.size || other.segments[b].lo >= until)
            }
        }
    }

    private fun statistics(state: TimelineState): Map<String, Any?> {
        var curves = 0L; var segments = 0L; var pulses = 0L; var predicted = 0L
        val attributes = sortedMapOf<Int, Long>()
        for (obj in state.objects.values) for ((id, curve) in obj.curves) {
            curves++; segments += curve.segments.size; pulses += curve.events.size
            predicted += curve.segments.count { it.predicted }
            if (obj.kind == TimelineObjectKind.UNIT) attributes[id] = (attributes[id] ?: 0) + curve.segments.size + curve.events.size
        }
        return linkedMapOf("observedThrough" to state.header.observedThrough, "sealedThrough" to state.header.sealedThrough,
            "retainedFrom" to state.header.retainedFrom, "objects" to state.objects.size, "curves" to curves,
            "segments" to segments, "pulses" to pulses, "records" to segments + pulses, "predicted" to predicted,
            "unitAttributeRecords" to attributes.mapKeys { it.key.toString() }, "persistentBytes" to TimelineCanonical.persistentSize(state))
    }
    private fun context(): TimelineContext {
        val cells = 256 * 256
        val record = TimelineRecord(listOf(
            TimelineField(1, true, TimelineTlvValue.U32(1)), TimelineField(2, true, TimelineTlvValue.U32(256)),
            TimelineField(3, true, TimelineTlvValue.U32(256)), TimelineField(4, true, TimelineTlvValue.U32(20)),
            TimelineField(5, true, TimelineTlvValue.U32(20)), TimelineField(6, true, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(cells)))),
            TimelineField(7, true, TimelineTlvValue.U32(1)), TimelineField(8, true, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(cells * 2)))),
            TimelineField(9, true, TimelineTlvValue.Bytes(TimelineBytes(ByteArray(cells * 2)))),
            TimelineField(10, true, TimelineTlvValue.Bytes(TimelineBytes(byteArrayOf(0))))))
        return TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }), TimelineCanonical.mapKey(record), record, 0, 60)
    }
    private fun artifactBinding(): Map<String, Any?> {
        val url = TimelinePublisher::class.java.protectionDomain.codeSource?.location
        val path = url?.takeIf { it.protocol == "file" }?.let { Path.of(it.toURI()) }
        val digest = if (path != null && Files.isRegularFile(path)) Files.newInputStream(path).use { input ->
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        } else null
        return linkedMapOf("location" to url?.toString(), "sha256" to digest, "directoryNotHashed" to (path != null && Files.isDirectory(path)))
    }
    private fun angleDelta(a: Long, b: Long): Long = Math.floorMod(b - a, 65536L).let { if (it > 32768) it - 65536 else it }
    private fun usedHeap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000.0
    private fun hex(bytes: TimelineBytes) = bytes.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun emit(values: Map<String, Any?>) { println(json(values)); System.out.flush() }
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> buildString { append('"'); value.toString().forEach { c -> when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        } }; append('"') }
    }
}
