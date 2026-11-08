package io.github.rwx.net.timeline

import io.github.rwx.authoritative.AuthoritativeRoomCertificate
import io.github.rwx.net.authoritative.AuthoritativeTls
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.system.exitProcess

/** Synthetic, engine-free real TLS test. It measures framed v7 bytes, excluding TLS/TCP/IP overhead. */
object TimelineContinuousTlsBenchmark {
    private const val SECOND = 60L * 1024
    internal const val BYTE_BUDGET = 1_048_576L // Actual production openTimeline default; not 16 MiB/s maximum.
    private data class Checked(val epoch: Long, val revision: Long, val observedThrough: Long,
                               val sealedThrough: Long, val receivedCheckCount: Long)
    internal data class Settings(val units: Int, val seconds: Int, val deadlineMillis: Long,
                                 val expectFinalSealed: Boolean, val output: Path)
    internal fun parse(args: Array<String>): Settings {
        val values = LinkedHashMap<String, String>()
        for (argument in args) {
            require(argument.startsWith("--") && '=' in argument) { "Expected --name=value" }
            val key = argument.substring(2).substringBefore('=')
            require(key in setOf("units", "seconds", "deadline-ms", "expect-final-sealed", "output") && key !in values)
            values[key] = argument.substringAfter('=')
        }
        return Settings((values["units"] ?: "1000").toInt().also { require(it in setOf(1000, 10000)) },
            (values["seconds"] ?: "10").toInt().also { require(it in setOf(10, 30)) },
            (values["deadline-ms"] ?: "120000").toLong().also { require(it in 10000..180000) },
            (values["expect-final-sealed"] ?: "false").toBooleanStrict(),
            Path.of(requireNotNull(values["output"]) { "A new --output directory is required" }).toAbsolutePath())
    }

    @JvmStatic fun main(args: Array<String>) {
        check(System.getenv("RWX_RUN_TIMELINE_TLS_CONTINUOUS") == "1") { "Explicit opt-in required" }
        check(System.getProperty("java.awt.headless") == "true") { "Headless property required" }
        val settings = parse(args)
        Files.createDirectory(settings.output) // Never reuse or overwrite another run.
        val start = System.nanoTime(); val deadline = start + settings.deadlineMillis * 1_000_000
        val done = AtomicBoolean(); val resultWritten = AtomicBoolean(); val timedOut = AtomicBoolean()
        val ownedClient = AtomicReference<TimelineClient?>(); val ownedServer = AtomicReference<TimelineServer?>()
        val progress = AtomicReference<Map<String, Any?>>(mapOf("phase" to "startup"))
        fun writeResult(result: Map<String, Any?>) {
            if (resultWritten.compareAndSet(false, true)) {
                Files.writeString(settings.output.resolve("result.json"), json(result) + "\n", StandardOpenOption.CREATE_NEW)
                emit(result)
            }
        }
        Thread({
            while (!done.get() && System.nanoTime() < deadline) LockSupport.parkNanos((deadline - System.nanoTime()).coerceAtMost(100_000_000).coerceAtLeast(1))
            if (!done.get()) {
                timedOut.set(true)
                writeResult(linkedMapOf("kind" to "result", "passed" to false, "failure" to "absolute-deadline", "last" to progress.get()))
                runCatching { ownedClient.get()?.close() }; runCatching { ownedServer.get()?.close() }
            }
        }, "tls-continuous-deadline").apply { isDaemon = true; start() }
        Thread({
            val hard = deadline + 2_000_000_000L
            while (!done.get() && System.nanoTime() < hard) LockSupport.parkNanos((hard - System.nanoTime()).coerceAtMost(100_000_000).coerceAtLeast(1))
            if (!done.get()) Runtime.getRuntime().halt(124)
        }, "tls-continuous-hard-deadline").apply { isDaemon = true; start() }
        var passed = false
        try {
            val result = runCase(settings, start, deadline, ownedClient, ownedServer) { progress.set(it) }
            check(System.nanoTime() < deadline) { "Absolute deadline exceeded before result commit" }
            ownedClient.get()?.close(); ownedServer.get()?.close()
            check(ownedClient.get()?.isConnected == false && ownedServer.get()?.connectedTeamIds()?.isEmpty() == true) { "Own resources did not close" }
            writeResult(result + mapOf("cleanup" to mapOf("clientClosed" to true, "serverPeersRemoved" to true)))
            passed = resultWritten.get() && !timedOut.get()
        } catch (error: Exception) {
            writeResult(linkedMapOf("kind" to "result", "passed" to false, "failureClass" to error.javaClass.name,
                "failure" to error.message?.take(256), "last" to progress.get(), "elapsedMillis" to elapsed(start)))
        } finally {
            runCatching { ownedClient.getAndSet(null)?.close() }; runCatching { ownedServer.getAndSet(null)?.close() }
            done.set(true)
        }
        exitProcess(if (passed) 0 else 2)
    }

    private fun runCase(settings: Settings, start: Long, deadline: Long,
                        ownedClient: AtomicReference<TimelineClient?>, ownedServer: AtomicReference<TimelineServer?>,
                        progress: (Map<String, Any?>) -> Unit): Map<String, Any?> {
        val binding = artifactBinding()
        val ready = linkedMapOf<String, Any?>("kind" to "ready", "phase" to "tls-initialization", "units" to settings.units,
            "seconds" to settings.seconds, "byteBudget" to BYTE_BUDGET, "bitBudgetPerSecond" to BYTE_BUDGET * 8,
            "productionDefaultBudget" to true, "publisherBinding" to binding, "wireVersion" to TimelineProtocol.VERSION,
            "scope" to "synthetic real TLS / no engine / application-frame byte counts")
        progress(ready); emit(ready)
        val random = SecureRandom()
        val password = hex(TimelineBytes(ByteArray(24).also(random::nextBytes))).toCharArray()
        val generated = try { AuthoritativeRoomCertificate.generate(password) } catch (error: Exception) { password.fill('\u0000'); throw error }
        val tls = try { generated.pkcs12.inputStream().use { AuthoritativeTls.serverContext(it, password) } }
            finally { password.fill('\u0000'); generated.pkcs12.fill(0) }
        val pin = AuthoritativeTls.fingerprint(generated.certificate)
        val token = hex(TimelineBytes(ByteArray(32).also(random::nextBytes)))
        val context = context()
        val publisher = TimelinePublisher(context)
        val fog = TimelineBytes(ByteArray(256 * 256)); val status = TimelineBytes(byteArrayOf(0, 0, 0, 0))
        publisher.observe(TimelineObservation(0, 1_000_000, status, fog, List(settings.units) { unit(it, 0) }))
        val latest = AtomicReference(checkNotNull(publisher.publish(0)))
        var publisherTransactions = 1L; var publisherOperationBytes = latest.get().header.operationBytes
        val sourceTime = AtomicLong()
        val feedOpens = AtomicLong()
        val factory = TimelineFeedFactory { request ->
            check(request.context.mode == TimelineStreamMode.LIVE)
            check(request.budgetBytesPerSecond == BYTE_BUDGET)
            feedOpens.incrementAndGet()
            object : TimelineFeed {
                private val encoder = TimelineEncoder() // One independent production encoder for this peer/stream.
                override fun next(cursor: TimelineSendCursor) = encoder.encode(cursor, latest.get())
            }
        }
        val server = TimelineServer(InetAddress.getByName("127.0.0.1"), 0,
            mapOf(token to TimelineSeat(0, context.accessId)), context.mapRecord, context.matchId, factory,
            serverTime = sourceTime::get, historyUntil = { sourceTime.get() }, tlsContext = tls)
        ownedServer.set(server)
        val client = TimelineClient("127.0.0.1", server.localPort, "Synthetic TLS bandwidth", token, expectedFingerprint = pin)
        ownedClient.set(client)
        var selectedAttempts = 0L; var selectedQueued = 0L; var commandAttempts = 0L; var commandQueued = 0L; var stale = 0L
        var drainedSelections = 0L; var drainedCommands = 0L
        var maximumEpoch = 0L; var observedTransactions = 0L; var lastEpoch = 0L; var lastRevision = 0L
        val observedLock = Any(); val observerFailure = AtomicReference<String?>()
        val observer = client.observeLatest { state -> synchronized(observedLock) {
            maximumEpoch = maxOf(maximumEpoch, state.header.epoch)
            if (!(state.header.epoch > lastEpoch || (state.header.epoch == lastEpoch && state.header.revision > lastRevision)))
                observerFailure.compareAndSet(null, "Non-monotonic observer identity")
            lastEpoch = state.header.epoch; lastRevision = state.header.revision; observedTransactions++
        } }
        val checked = AtomicReference<Checked?>()
        val checkObserver = client.observeFrames { frame ->
            if (frame.type == 109) client.latest(frame.body.number(1))?.header?.let { h ->
                if (h.epoch == frame.body.number(2) && h.revision == frame.body.number(3))
                    checked.set(Checked(h.epoch, h.revision, h.observedThrough, h.sealedThrough, client.wireStatistics().stateChecks))
            }
        }
        fun checkDeadline() {
            check(System.nanoTime() < deadline) { "Absolute deadline exceeded" }
            check(client.isConnected) { "Client disconnected: ${client.failure?.javaClass?.simpleName}" }
            check(server.connectedTeamIds() == setOf(0)) { "Owned peer disconnected" }
        }
        fun drain() { server.drainRequests { if (it.frame.type == 5) drainedCommands++ else if (it.frame.type == 6) drainedSelections++ } }
        fun await(label: String, localMillis: Long = 5000, condition: () -> Boolean) {
            val until = minOf(deadline, System.nanoTime() + localMillis * 1_000_000)
            while (!condition()) {
                checkDeadline(); drain(); check(System.nanoTime() < until) { "Timed out: $label" }; LockSupport.parkNanos(1_000_000)
            }
        }
        fun current(): TimelineState = checkNotNull(client.latestStates().values.singleOrNull())
        fun accept(state: TimelineState?) {
            if (state != null) { latest.set(state); publisherTransactions++; publisherOperationBytes += state.header.operationBytes; server.signal(0) }
        }
        fun stateReport(state: TimelineState) = linkedMapOf("epoch" to state.header.epoch, "revision" to state.header.revision,
            "observedThrough" to state.header.observedThrough, "sealedThrough" to state.header.sealedThrough,
            "retainedFrom" to state.header.retainedFrom, "controlEpoch" to state.header.controlEpoch)
        fun snapshot(phase: String, tick: Int): Map<String, Any?> {
            checkDeadline(); drain()
            val value = linkedMapOf<String, Any?>("phase" to phase, "nativeEngine" to false, "inputTick" to tick,
                "elapsedMillis" to elapsed(start), "client" to wire(client.wireStatistics()),
                "server" to wire(checkNotNull(server.wireStatistics(0))), "source" to stateReport(latest.get()),
                "received" to client.latestStates().values.singleOrNull()?.let(::stateReport),
                "lastChecked" to checked.get()?.let { mapOf("epoch" to it.epoch, "revision" to it.revision,
                    "observedThrough" to it.observedThrough, "sealedThrough" to it.sealedThrough,
                    "receivedCheckCount" to it.receivedCheckCount) },
                "publisherTransactions" to publisherTransactions, "publisherOperationBytes" to publisherOperationBytes,
                "selectAttempts" to selectedAttempts, "selectQueuedLocally" to selectedQueued,
                "commandAttempts" to commandAttempts, "commandsQueuedLocally" to commandQueued, "staleRefused" to stale,
                "ownerDrainedSelections" to drainedSelections, "ownerDrainedCommands" to drainedCommands)
            progress(value); return value
        }
        var selectSequence = 0L; var commandSequence = 0L
        fun inputs() {
            val state = current()
            val handle = state.objects.values.first { it.kind == TimelineObjectKind.UNIT && publisher.unitKey(it.handle) == 1L }.handle
            val selected = ByteArrayOutputStream().let { out -> TimelineBinary.writeVUInt(out, 1); TimelineBinary.writeVUInt(out, handle); TimelineBytes(out.toByteArray()) }
            val h = state.header
            selectedAttempts++
            try { client.select(frame(6, n(1, h.streamId, true), n(2, h.epoch), n(3, ++selectSequence), n(4, h.revision), b(5, selected))); selectedQueued++ }
            catch (_: TimelineStaleInputException) { stale++ }
            commandAttempts++
            val target = TimelineRecord(listOf(n(1, 1, true), TimelineField(2, true, TimelineTlvValue.I64(2048)), TimelineField(3, true, TimelineTlvValue.I64(2048))))
            try {
                client.sendCommand(frame(5, n(1, h.streamId, true), n(2, h.epoch), n(3, ++commandSequence), n(4, h.revision),
                    n(5, h.controlEpoch), n(6, 1, true), b(7, selected), n(8, 0), TimelineMessages.recordField(9, target), n(10, 0, true)))
                commandQueued++
            } catch (_: TimelineStaleInputException) { stale++ }
        }
        try {
            progress(ready + mapOf("phase" to "initial-map-and-checkpoint"))
            client.openTimeline(1) // Production default budget: no benchmark-only rate increase.
            await("initial authorized library", 9000) { client.latestStates().values.singleOrNull()?.objects?.values?.count { it.kind == TimelineObjectKind.UNIT } == settings.units }
            await("initial full check and actual ACK write") { client.wireStatistics().stateChecks >= 1 && client.wireStatistics().stateAcks >= 1 }
            val initial = snapshot("initial-map-and-checkpoint", 0)
            check(client.wireStatistics().mapInitializations == 1L && client.wireStatistics().mapCacheHits == 0L)
            val liveStart = System.nanoTime()
            progress(ready + mapOf("phase" to "steady"))
            var lateTicks = 0L; var maximumLatenessNanos = 0L
            val samples = ArrayList<Map<String, Any?>>()
            for (tick in 1..settings.seconds * 60) {
                val due = liveStart + tick * 1_000_000_000L / 60
                while (System.nanoTime() < due) { checkDeadline(); drain(); LockSupport.parkNanos(minOf(due - System.nanoTime(), 1_000_000).coerceAtLeast(1)) }
                checkDeadline(); drain()
                val lateness = (System.nanoTime() - due).coerceAtLeast(0)
                if (lateness > 16_666_667) lateTicks++
                maximumLatenessNanos = maxOf(maximumLatenessNanos, lateness)
                val time = tick * 1024L
                publisher.observe(TimelineObservation(time, 1_000_000, status, fog, List(settings.units) { unit(it, tick) }))
                sourceTime.set(time)
                if (tick % 6 == 0) accept(publisher.publish(System.nanoTime() - liveStart))
                if (tick % 60 == 0) {
                    inputs()
                    val sample = snapshot("steady", tick); samples.add(sample); emit(linkedMapOf("kind" to "sample", "sample" to sample))
                }
            }
            val inputWorkWallMillis = elapsed(liveStart)
            val beforeDrain = snapshot("steady-clock-ended", settings.seconds * 60)
            val targetTime = settings.seconds * SECOND
            val drainStarted = System.nanoTime()
            // Common freshness boundary: no additional observation, no policy-specific internal API.
            await("publisher and peer reach the same final observed time", 2000) {
                accept(publisher.publish(System.nanoTime() - liveStart))
                latest.get().header.observedThrough == targetTime && client.latestStates().values.singleOrNull()?.header?.observedThrough == targetTime
            }
            val freshnessDrainMillis = elapsed(drainStarted)
            val beforeFinal = snapshot("same-observed-time-before-final", settings.seconds * 60)
            check(current().header.retainedFrom == 0L)
            val beforeConfirmChecks = client.wireStatistics().stateChecks
            val idleStarted = System.nanoTime()
            // A real five-second idle phase: no synthetic clock jump, new observation or terminal state.
            while (System.nanoTime() - idleStarted < 5_100_000_000L) {
                checkDeadline(); drain(); accept(publisher.publish(System.nanoTime() - liveStart)); LockSupport.parkNanos(2_000_000)
            }
            val expectedSeal = latest.get().header.sealedThrough
            await("final policy publication") { client.latestStates().values.singleOrNull()?.header?.let { it.observedThrough == targetTime && it.sealedThrough == expectedSeal } == true }
            await("post-final real full SHA and actual ACK write") {
                checked.get()?.let { receipt -> receipt.observedThrough == targetTime && receipt.sealedThrough == expectedSeal &&
                    receipt.receivedCheckCount > beforeConfirmChecks && client.wireStatistics().stateAcks >= receipt.receivedCheckCount } == true
            }
            val idleConfirmationMillis = elapsed(idleStarted)
            val afterFinal = snapshot("after-real-idle-confirmation", settings.seconds * 60)
            val final = current()
            if (settings.expectFinalSealed) check(final.header.sealedThrough == targetTime) { "Candidate did not fully seal final observed time" }
            val counters = client.wireStatistics(); val serverCounters = checkNotNull(server.wireStatistics(0))
            check(counters.stateMismatches == 0L && serverCounters.stateMismatches == 0L && counters.restores == 0L && serverCounters.restores == 0L)
            check(counters.stateChecks >= settings.seconds / 2 && counters.stateAcks >= settings.seconds / 2 && serverCounters.stateChecks >= settings.seconds / 2)
            check(feedOpens.get() == 1L && drainedCommands > 0 && drainedSelections > 0)
            observer.close()
            val observed = synchronized(observedLock) { maximumEpoch to observedTransactions }
            check(observerFailure.get() == null && observed.first == 1L)
            val verificationStart = System.nanoTime()
            var checkedSamples = 0L; var unitsVerified = 0
            for (obj in final.objects.values) if (obj.kind == TimelineObjectKind.UNIT) {
                checkDeadline()
                val key = checkNotNull(publisher.unitKey(obj.handle)); check(key in 1..settings.units.toLong())
                verifyUnitSamples(obj, key.toInt() - 1, settings.seconds * 60)
                unitsVerified++; checkedSamples += (settings.seconds * 60L + 1) * 3
            }
            check(unitsVerified == settings.units)
            val result = linkedMapOf<String, Any?>("kind" to "result", "passed" to true,
                "scope" to "synthetic single-client production TLS / PeerEncoder / full SHA; no native engine or WAN claim",
                "units" to settings.units, "seconds" to settings.seconds, "ticksPerSecond" to 60, "wireVersion" to TimelineProtocol.VERSION,
                "byteBudget" to BYTE_BUDGET, "bitBudgetPerSecond" to BYTE_BUDGET * 8, "productionDefaultBudget" to true,
                "wireBytesScope" to "actual v7 length-prefixed application frames, excluding TLS/TCP/IP overhead",
                "inputScope" to "authorized MOVE/SELECT owner-drained only; synthetic trajectory is not driven by commands",
                "hashSchedule" to "actual production network 1 Hz wall clock; no test interval override or cached digest",
                "ackEvidence" to "client counts actual ACK writes; final Check epoch/revision/O/S is observed; server has no accepted-ACK counter",
                "publisherBinding" to binding, "initial" to initial, "steadySamples" to samples,
                "beforeFreshnessDrain" to beforeDrain, "beforeFinal" to beforeFinal, "afterFinal" to afterFinal,
                "steadyDelta" to stageDelta(initial, beforeDrain), "catchupDelta" to stageDelta(beforeDrain, beforeFinal),
                "sameObservedTimeDelta" to stageDelta(initial, beforeFinal), "idleConfirmationDelta" to stageDelta(beforeFinal, afterFinal),
                "targetInputDurationMillis" to settings.seconds * 1000L, "inputWorkWallMillis" to inputWorkWallMillis,
                "freshnessDrainMillis" to freshnessDrainMillis, "idleConfirmationMillis" to idleConfirmationMillis,
                "lateTicksOverOneTick" to lateTicks, "maximumTickLatenessMillis" to maximumLatenessNanos / 1_000_000.0,
                "finalObservedThrough" to final.header.observedThrough, "finalSealedThrough" to final.header.sealedThrough,
                "fullySealed" to (final.header.sealedThrough == targetTime), "observationAttributeSamplesChecked" to checkedSamples,
                "unitsVerified" to unitsVerified, "verificationMillis" to elapsed(verificationStart),
                "observedTransactions" to observed.second, "feedOpens" to feedOpens.get(), "maximumEpoch" to observed.first,
                "elapsedMillis" to elapsed(start))
            return result
        } finally { observer.close(); checkObserver.close() }
    }

    private fun wire(s: TimelineWireStatistics) = linkedMapOf("receivedBytes" to s.receivedBytes, "writtenBytes" to s.writtenBytes,
        "mapInitializations" to s.mapInitializations, "mapCacheHits" to s.mapCacheHits, "transactions" to s.transactions,
        "stateChecks" to s.stateChecks, "stateAcks" to s.stateAcks, "stateMismatches" to s.stateMismatches,
        "stateDigestNanos" to s.stateDigestNanos, "restores" to s.restores)
    private fun stageDelta(first: Map<String, Any?>, last: Map<String, Any?>): Map<String, Any?> =
        listOf("client", "server").associateWith { side ->
            @Suppress("UNCHECKED_CAST") val a = first.getValue(side) as Map<String, Long>
            @Suppress("UNCHECKED_CAST") val b = last.getValue(side) as Map<String, Long>
            a.mapValues { (key, value) -> b.getValue(key) - value }
        }
    private fun artifactBinding(): Map<String, Any?> {
        val classes = listOf(TimelinePublisher::class.java, TimelineEncoder::class.java, TimelineClient::class.java,
            TimelineServer::class.java, TimelineStore::class.java, TimelineCanonical::class.java)
        val urls = classes.map { checkNotNull(it.protectionDomain.codeSource?.location) }
        check(urls.toSet().size == 1) { "Mixed production bindings" }
        val path = Path.of(urls.first().toURI())
        check(Files.isRegularFile(path)) { "A frozen production JAR must be first on the classpath" }
        val digest = Files.newInputStream(path).use { input ->
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val read = input.read(buffer); if (read < 0) break; hash.update(buffer, 0, read) }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return mapOf("location" to path.toString(), "sha256" to digest, "classes" to classes.map { it.name })
    }
    private fun frame(type: Int, vararg fields: TimelineField) = TimelineFrame(type, TimelineRecord(fields.toList()))
    private fun n(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
    private fun b(id: Int, value: TimelineBytes) = TimelineMessages.bytesField(id, value)

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

    internal fun context(): TimelineContext {
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
