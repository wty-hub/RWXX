package io.github.rwx

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.authoritative.AuthoritativeRoomCertificate
import io.github.rwx.net.authoritative.AuthoritativeTls
import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.system.exitProcess

/** Parent is a view-only TLS client process. Engine fixtures live only in the owned child main. */
object TimelineFullChainAcceptanceMain {
    @JvmStatic fun main(args: Array<String>) {
        check(System.getenv("RWX_RUN_TIMELINE_FULLCHAIN") == "1") { "Explicit full-chain acceptance opt-in is required" }
        val options = FullChainSupport.options(args)
        check(!Files.exists(options.output)) { "Evidence destination already exists" }
        Files.createDirectories(options.output.parent)
        System.setProperty("java.awt.headless", "true")
        val started = System.nanoTime(); val done = AtomicBoolean(); val claimed = AtomicBoolean()
        val current = AtomicReference<Run?>(); val reports = ConcurrentLinkedQueue<JsonObject>()
        val totalSeconds = options.scenarios.size * (options.deadlineSeconds + 20L) + 10L
        fun report(error: String?) {
            if (!claimed.compareAndSet(false, true)) return
            FullChainSupport.writeNew(options.output, buildJsonObject {
                put("passed", error == null && reports.size == options.scenarios.size && reports.all { it.getValue("passed").jsonPrimitive.boolean })
                put("protocolVersion", TimelineProtocol.VERSION); put("applicationVersion", AppMetadata.VERSION_NAME)
                put("scope", "Full pipeline target: 64 human allied seats,65536 real native stock units,real independent TLS clients,production Room/Session/Server,whole-library1Hz verification,online archive and read-only history. No GUI,WAN or combat claim. Any unmet stage is FAIL,not partial acceptance.")
                put("parentHasNoGameEngine", GameEngine.getInstance() == null)
                put("parentPid", ProcessHandle.current().pid()); put("parentMaxHeapBytes", Runtime.getRuntime().maxMemory())
                put("childHeapLimitBytes", 4L * 1024 * 1024 * 1024); put("scenariosSequential", true)
                put("requiredMeasuredTicks", options.ticks); put("absoluteDeadlineSeconds", totalSeconds)
                put("elapsedSeconds", (System.nanoTime() - started) / 1e9); put("scenarios", JsonArray(reports.toList()))
                error?.let { put("error", it.take(16_384)) }
            })
        }
        Thread({
            FullChainSupport.waitUntil(started + TimeUnit.SECONDS.toNanos(totalSeconds))
            if (!done.get()) {
                runCatching { report("Absolute parent deadline; incomplete stages are not accepted") }
                current.get()?.emergencyStop(); Runtime.getRuntime().halt(124)
            }
        }, "fullchain-parent-deadline").apply { isDaemon = true; start() }
        Thread({
            FullChainSupport.waitUntil(started + TimeUnit.SECONDS.toNanos(totalSeconds + 2))
            Runtime.getRuntime().halt(124) // Independent of cleanup, report I/O and shutdown hooks.
        }, "fullchain-parent-hard-stop").apply { isDaemon = true; start() }
        var topError: Throwable? = null
        try {
            check(Runtime.getRuntime().maxMemory() <= 2L * 1024 * 1024 * 1024) { "Parent heap exceeds2GiB" }
            check(GameEngine.getInstance() == null) { "Parent must not initialize the engine" }
            for (scenario in options.scenarios) {
                val run = Run(options, scenario); current.set(run)
                var error: Throwable? = null
                try { run.execute() } catch (failure: Throwable) { error = failure; runCatching { run.failureEvidence(failure) } }
                finally { try { run.cleanup() } catch (failure: Throwable) {
                    if (error == null) error = failure else error!!.addSuppressed(failure)
                } }
                reports.add(run.report(error)); current.set(null)
                // Release the previous scenario's client libraries before constructing the next
                // independent fixture. This is outside all measured native tick intervals.
                System.gc()
            }
        } catch (error: Throwable) { topError = error }
        report(topError?.stackTraceToString()); done.set(true)
        val passed = topError == null && reports.size == options.scenarios.size && reports.all { it.getValue("passed").jsonPrimitive.boolean }
        println("TimelineFullChainAcceptance passed=$passed output=${options.output}")
        exitProcess(if (passed) 0 else 2)
    }

    private class Run(private val options: FullChainSupport.Options, private val scenario: TimelineFullChainLayout.Scenario) {
        private val started = System.nanoTime()
        val directory = Files.createTempDirectory(options.output.parent, "fullchain-${scenario.name.lowercase()}-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        private val child = AtomicReference<Process?>(); private val clients = ConcurrentLinkedQueue<TimelineClient>()
        private val monitored = ArrayList<TimelineClient>(); private val subscriptions = ConcurrentLinkedQueue<AutoCloseable>()
        private val forced = AtomicBoolean(); private val stage = AtomicReference("setup")
        private val logBytes = AtomicLong(); private val logExceeded = AtomicBoolean()
        private var logThread: Thread? = null
        private val deadline = started + TimeUnit.SECONDS.toNanos(options.deadlineSeconds.toLong())
        private val workDeadline = deadline - TimeUnit.SECONDS.toNanos(25)
        private val random = SecureRandom()
        private fun secret() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private val tokens = (0 until 64).associateWith { secret() }; private val password = secret()
        private val cache = AtomicReference<TimelineRecord?>(); private val checks = linkedMapOf<String, Boolean>()
        private val peers = ArrayList<Probe>(); private val archiveReports = ArrayList<JsonObject>()
        private var childReady: JsonObject? = null; private var measurement: JsonObject? = null
        private var eofExit: Int? = null; private var eofSeconds: Double? = null; private var historyReport: JsonObject? = null
        private var peakHeap = 0L; private var peakDirect = 0L; private var measuredStart = 0L; private var measuredCpu = 0L
        private var measuredSeconds = 0.0
        private val visibilityReports = ArrayList<JsonObject>()
        private val releaseReader = CountDownLatch(1)
        private val slowReport = AtomicReference<JsonObject?>(); private val slowError = AtomicReference<Throwable?>()
        private val slowReleasedTime = AtomicLong(-1)
        private var nextInput = 0L
        private var movementStarted = false
        private data class Probe(val client: TimelineClient, val handle: Long, val initial: TimelineValue.Vec3,
                                 val targets: List<Pair<Long, Long>>, var sequence: Long = 0, var changes: Long = 0,
                                 var previous: TimelineValue.Vec3 = initial, var displacement: Double = 0.0,
                                 var queuedCommands: Long = 0, var staleRefused: Long = 0,
                                 var before: TimelineWireStatistics = client.wireStatistics())

        fun execute() {
            privateFile("seats.properties", tokens.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }.toByteArray())
            val cert = AuthoritativeRoomCertificate.generate(password.toCharArray())
            val pin = AuthoritativeTls.fingerprint(cert.certificate)
            privateFile("server.p12", cert.pkcs12); privateFile("tls-password.txt", password.toByteArray())
            val java = ProcessHandle.current().info().command().orElseThrow()
            val command = mutableListOf(java, "-Djava.awt.headless=true", "-Xms128m", "-Xmx4g",
                "-XX:MaxDirectMemorySize=256m", "-XX:MaxMetaspaceSize=384m", "-XX:ReservedCodeCacheSize=128m")
            System.getProperty("rwx.assetsDir")?.let { command.add("-Drwx.assetsDir=$it") }
            command.addAll(listOf("-cp", System.getProperty("java.class.path"), TimelineFullChainServerMain::class.java.name,
                "--directory=$directory", "--scenario=${scenario.name}", "--ticks=${options.ticks}", "--deadline-seconds=${options.deadlineSeconds}"))
            child.set(ProcessBuilder(command).redirectErrorStream(true).start())
            logThread = Thread({
                try {
                    Files.newOutputStream(directory.resolve("child.log"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        child.get()!!.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                if (logBytes.get() > 16L * 1024 * 1024 - count) {
                                    logExceeded.set(true); child.get()?.destroyForcibly(); break
                                }
                                output.write(buffer, 0, count); logBytes.addAndGet(count.toLong())
                            }
                        }
                    }
                } catch (_: Exception) { if (child.get()?.isAlive == true) { logExceeded.set(true); child.get()?.destroyForcibly() } }
            }, "fullchain-owned-log").apply { isDaemon = true; start() }
            verify("separateOwnedEngineProcess", child.get()!!.pid() != ProcessHandle.current().pid())
            await("fixture boot and production listener") { Files.exists(directory.resolve("ready.json")) }
            childReady = FullChainSupport.read(directory.resolve("ready.json"))
            verify("real65536InitialUnits", childReady!!.getValue("fixtureUnits").jsonPrimitive.int == 65_536)
            verify("default30SecondLiveWindow", childReady!!.getValue("liveWindowSeconds").jsonPrimitive.int == 30)
            val port = childReady!!.getValue("port").jsonPrimitive.int
            val probeCoordinates = childReady!!.getValue("networkOnlyProbePositions").jsonArray.associate {
                val value = it.jsonObject
                value.getValue("teamId").jsonPrimitive.int to (value.getValue("xQ").jsonPrimitive.long to value.getValue("yQ").jsonPrimitive.long)
            }
            for (team in 0 until 64) {
                stage.set("join-seat-$team")
                check(workDeadline - System.nanoTime() >= TimeUnit.SECONDS.toNanos(11)) { "Insufficient absolute handshake budget" }
                val client = TimelineClient("127.0.0.1", port, "Full-chain $team", tokens.getValue(team), pin,
                    mapResolver = { key -> cache.get()?.takeIf { TimelineCanonical.mapKey(it) == key } }, onMapReceived = { cache.set(it) })
                clients.add(client); monitored.add(client)
                // Max approved stream budget is explicit: this avoids a1MiB/s default masking
                // a healthy large checkpoint behind the10s absolute transfer deadline.
                client.openTimeline(1, budgetBytesPerSecond = 16_777_216)
                await("seat$team initial full library") { live(client) != null }
                val state = checkNotNull(live(client)); val (x, y) = probeCoordinates.getValue(team)
                val actor = FullChainSupport.exactProbe(state, x, y) ?: run {
                    FullChainSupport.writeNew(directory.resolve("probe-binding-team-$team.json"), FullChainSupport.probeDiagnostic(state, x, y))
                    error("Seat$team has no unique exact disclosed own network-only probe; inspect probe-binding-team-$team.json")
                }
                val point = TimelineProfile.sample(state, actor.handle, 1, state.header.observedThrough) as TimelineValue.Vec3
                val targets = listOf(x + 64 * 256 to y, x to y + 64 * 256).filter { (tx, ty) ->
                    val map = state.context.mapRecord; tx >= 0 && ty >= 0 && tx < map.number(2) * map.number(4) * 256 &&
                        ty < map.number(3) * map.number(5) * 256
                }
                check(targets.size == 2)
                val probe = Probe(client, actor.handle, point, targets); peers.add(probe)
                client.select(FullChainSupport.select(state, actor.handle, 1))
            }
            verify("parentHasNoEngineAfter64Joins", GameEngine.getInstance() == null)
            verify("oneMapDownload63PinnedCacheHits", monitored.sumOf { it.wireStatistics().mapInitializations } == 1L &&
                monitored.sumOf { it.wireStatistics().mapCacheHits } == 63L)
            // Recheck each already-bound disclosure handle; a nearby replacement is never
            // accepted as identity. Native idle ticks have continued throughout all joins.
            for (probe in peers) {
                val state = checkNotNull(live(probe.client))
                val current = TimelineProfile.sample(state, probe.handle, 1, state.header.observedThrough) as? TimelineValue.Vec3
                if (current == null || current.x != probe.initial.x || current.y != probe.initial.y) {
                    FullChainSupport.writeNew(directory.resolve("probe-recheck-team-${probe.client.teamId}.json"),
                        FullChainSupport.probeDiagnostic(state, probe.initial.x, probe.initial.y, probe.handle))
                    error("Bound seat${probe.client.teamId} probe moved beforeSTART; inspect probe-recheck diagnostic")
                }
            }
            verify("all64BoundProbesStillAtExactBirthBeforeStart", true)
            val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            peers.forEach { it.before = it.client.wireStatistics() }
            measuredStart = System.nanoTime(); measuredCpu = os?.processCpuTime ?: 0L
            armSlowReader()
            child.get()!!.outputStream.apply { write("START\n".toByteArray()); flush() }
            movementStarted = true
            await("600 native measured ticks and all library checks") {
                sendMoves(); sampleMovement()
                Files.exists(directory.resolve("measurement.json")) && monitored.all { client -> client.wireStatistics().let { s ->
                    val baseline = peers.single { p -> p.client === client }.before
                    s.stateChecks - baseline.stateChecks >= 10 && s.stateAcks - baseline.stateAcks >= 10
                } }
            }
            measuredSeconds = (System.nanoTime() - measuredStart) / 1e9
            measuredCpu = (os?.processCpuTime ?: measuredCpu) - measuredCpu
            measurement = FullChainSupport.read(directory.resolve("measurement.json"))
            verify("atLeast600MeasuredRealTicks", measurement!!.getValue("measuredTicks").jsonPrimitive.int >= 600)
            val accepted = measurement!!.getValue("sessions").jsonArray.map { it.jsonObject }
            verify("all64EngineAcceptedCommandsAndSelections", accepted.size == 64 &&
                accepted.map { it.getValue("teamId").jsonPrimitive.int }.toSet().size == 64 && accepted.all {
                    it.getValue("acceptedCommands").jsonPrimitive.long > 0 && it.getValue("acceptedSelections").jsonPrimitive.long > 0 })
            verify("allPeersZeroDigestMismatchAndRestore", monitored.all { it.wireStatistics().let { s -> s.stateMismatches == 0L && s.restores == 0L } })
            verify("all64NetworkOnlyActorsActuallyMove", peers.all { it.queuedCommands >= 3 && it.changes > 0 && it.displacement > .25 })
            await("slow application reader recovered") { slowReport.get() != null &&
                (live(monitored.last())?.header?.observedThrough ?: -1) > slowReleasedTime.get() }
            verify("otherPeersAdvancedDuringSlowReader", slowReport.get()!!.getValue("othersAdvanced").jsonPrimitive.boolean)
            monitored.forEach { visibilityReports.add(FullChainSupport.visibility(checkNotNull(live(it)))) }
            history(monitored.last())
            subscriptions.forEach { it.close() }; clients.forEach { it.close() }; monitored.clear()
            val process = child.get()!!; verify("ownedChildAliveBeforeEof", process.isAlive)
            stage.set("owner-eof-and-archives")
            val begin = System.nanoTime(); process.outputStream.close()
            check(process.waitFor(minOf(TimeUnit.SECONDS.toNanos(17), (deadline - System.nanoTime()).coerceAtLeast(0)), TimeUnit.NANOSECONDS)) {
                "Owned engine child failed to stop within17s ofEOF"
            }
            eofSeconds = (System.nanoTime() - begin) / 1e9; eofExit = process.exitValue()
            val files = Files.list(directory.resolve("archives")).use { stream -> stream.filter { it.fileName.toString().endsWith(".rwxr") }.sorted().toList() }
            verify("all64CompletedWholeGrantArchives", files.size == 64)
            for (path in files) {
                check(System.nanoTime() < deadline) { "Archive inspection deadline exceeded" }
                TimelineArchive.open(path).use { archive ->
                    check(archive.readOnly && archive.index.isNotEmpty())
                    val first = archive.index.first().from; val last = archive.index.last().until - 1
                    for (time in listOf(first, last).distinct()) {
                        val state = checkNotNull(archive.seek(time)).state
                        check(state.context.mode == TimelineStreamMode.HISTORY && state.header.controlEpoch == 0L)
                        check(TimelineProfile.sample(state, 0, 1, time) != null)
                    }
                    archiveReports.add(buildJsonObject {
                        put("teamId", archive.context.teamId); put("bytes", Files.size(path)); put("slices", archive.index.size)
                        put("from", first); put("until", last + 1); put("firstAndLastSeekSucceeded", true)
                    })
                }
            }
            verify("archiveTeamsUnique", archiveReports.map { it.getValue("teamId").jsonPrimitive.int }.toSet().size == 64)
            verify("normalOwnedChildExit", eofExit == 0)
            stage.set("complete")
        }

        private fun history(client: TimelineClient) {
            val from = client.serverHello.number(8); val until = from + client.ticksPerSecond * 1024L / 2
            await("sealed online history") { live(client)!!.header.sealedThrough >= until + client.ticksPerSecond * 1024L }
            val ends = ConcurrentLinkedQueue<TimelineFrame>()
            val subscription = client.observeFrames { if (it.type == 112 && ends.size < 8) ends.add(it) }; subscriptions.add(subscription)
            client.openTimeline(2, TimelineStreamMode.HISTORY, from, until, 16_777_216)
            await("online archived HISTORY without stoppingLIVE") { client.latestStates().values.any { it.context.mode == TimelineStreamMode.HISTORY } }
            val state = client.latestStates().values.single { it.context.mode == TimelineStreamMode.HISTORY }
            check(state.context.teamId == client.teamId && state.context.accessId == client.serverHello.bytes(12))
            check(state.header.retainedFrom == from && state.header.retainedUntil == until && state.header.controlEpoch == 0L)
            await("HISTORY verified and ended") { ends.any { it.body.number(1) == state.header.streamId && it.body.number(3) == 2L } }
            historyReport = buildJsonObject { put("teamId", client.teamId); put("from", from); put("until", until); put("completed", true); put("readOnly", true) }
        }

        private fun armSlowReader() {
            val slow = monitored.last(); val others = listOf(0, 8, 16, 24, 32, 40, 48, 56).map { monitored[it] }
            val claimed = AtomicBoolean()
            subscriptions.add(slow.observeLatest(emitCurrent = false) { state ->
                if (state.context.mode == TimelineStreamMode.LIVE && claimed.compareAndSet(false, true)) try {
                    val before = others.map { checkNotNull(live(it)).header.observedThrough to it.wireStatistics().stateAcks }
                    val start = System.nanoTime(); releaseReader.await(4, TimeUnit.SECONDS)
                    val duration = (System.nanoTime() - start) / 1e9
                    val after = others.map { checkNotNull(live(it)).header.observedThrough to it.wireStatistics().stateAcks }
                    slowReleasedTime.set(checkNotNull(live(slow)).header.observedThrough)
                    slowReport.set(buildJsonObject {
                        put("kind", "application-reader-hold"); put("teamId", slow.teamId); put("actualHoldSeconds", duration)
                        put("othersAdvanced", duration >= 3.8 && before.zip(after).all { (a, b) -> b.first > a.first && b.second > a.second })
                        put("tcpZeroWindowClaimed", false)
                        put("otherPeers", JsonArray(others.mapIndexed { index, client -> buildJsonObject {
                            put("teamId", client.teamId); put("beforeObservedThrough", before[index].first); put("afterObservedThrough", after[index].first)
                            put("beforeAcks", before[index].second); put("afterAcks", after[index].second)
                        } }))
                    })
                } catch (error: Throwable) { slowError.set(error) }
            })
        }

        private fun live(client: TimelineClient) = client.latestStates().values.singleOrNull { it.context.mode == TimelineStreamMode.LIVE }
        private fun sendMoves() {
            if (!movementStarted) return
            val now = System.nanoTime(); if (now < nextInput) return
            nextInput = now + TimeUnit.SECONDS.toNanos(1)
            for (probe in peers) {
                val state = live(probe.client) ?: continue
                val target = probe.targets[(probe.sequence % probe.targets.size).toInt()]
                try {
                    probe.client.sendCommand(FullChainSupport.move(state, probe.handle, ++probe.sequence, target.first, target.second))
                    probe.queuedCommands++
                } catch (_: TimelineStaleInputException) {
                    // Refuse this captured authorization. Never refresh/retry the old frame;
                    // the next ordinary1s input samples the then-current authorized library.
                    probe.staleRefused++
                }
            }
        }
        private fun sampleMovement() {
            for (probe in peers) {
                val state = live(probe.client) ?: continue
                val point = TimelineProfile.sample(state, probe.handle, 1, state.header.observedThrough) as? TimelineValue.Vec3 ?: continue
                if (point != probe.previous) probe.changes++
                probe.previous = point; probe.displacement = maxOf(probe.displacement,
                    hypot((point.x - probe.initial.x).toDouble(), (point.y - probe.initial.y).toDouble()) / 256)
            }
        }
        private fun await(name: String, condition: () -> Boolean) {
            stage.set(name)
            while (true) {
                check(System.nanoTime() < workDeadline) { "Absolute work deadline during$name" }
                check(!logExceeded.get()) { "Owned child log exceeded16MiB or failed to drain" }
                check(slowError.get() == null) { "Slow-reader fixture failed:${slowError.get()}" }
                check(child.get()?.isAlive == true) { "Owned engine child exited during$name; inspect child.log and child-result.json" }
                monitored.forEach { check(it.isConnected && it.failure == null) { "Seat${it.teamId} failed during$name: ${it.failure}" } }
                peakHeap = maxOf(peakHeap, ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
                peakDirect = maxOf(peakDirect, ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java)
                    .filter { it.name == "direct" }.sumOf { it.memoryUsed })
                if (condition()) return
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
            }
        }
        private fun verify(name: String, ok: Boolean) { checks[name] = ok; check(ok) { "Check failed:$name" } }
        private fun privateFile(name: String, bytes: ByteArray) = directory.resolve(name).also {
            Files.write(it, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-------"))
        }
        fun emergencyStop() {
            forced.set(true)
            releaseReader.countDown()
            // Only this Process object was created by this Run; no global process lookup/kill.
            child.get()?.takeIf { it.isAlive }?.destroyForcibly()
            clients.forEach { runCatching { it.close() } }
        }
        fun failureEvidence(error: Throwable) = FullChainSupport.writeNew(directory.resolve("incomplete.json"), buildJsonObject {
            put("passed", false); put("completed", false); put("scenario", scenario.name); put("stage", stage.get())
            put("seatsConnected", clients.size); put("errorKind", error.javaClass.name)
            put("ownedChildPid", child.get()?.pid()); put("cleanupPending", true)
        })
        fun cleanup() {
            releaseReader.countDown()
            subscriptions.forEach { runCatching { it.close() } }; clients.forEach { runCatching { it.close() } }
            child.get()?.takeIf { it.isAlive }?.let { process ->
                runCatching { process.outputStream.close() }
                if (!process.waitFor(minOf(TimeUnit.SECONDS.toNanos(17), (deadline - System.nanoTime()).coerceAtLeast(0)), TimeUnit.NANOSECONDS)) {
                    forced.set(true); process.destroy()
                    if (!process.waitFor(250, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); process.waitFor(250, TimeUnit.MILLISECONDS) }
                }
            }
            check(child.get()?.isAlive != true) { "Owned child survived cleanup" }
            logThread?.join(1000)
            check(GameEngine.getInstance() == null) { "Parent initialized an engine" }
        }
        fun report(error: Throwable?): JsonObject = buildJsonObject {
            val childFinal = directory.resolve("child-result.json").takeIf(Files::exists)?.let { runCatching { FullChainSupport.read(it) }.getOrNull() }
            put("passed", error == null && !forced.get() && eofExit == 0 && childFinal?.get("passed")?.jsonPrimitive?.boolean == true)
            put("completed", stage.get() == "complete"); put("scenario", scenario.name); put("stage", stage.get())
            put("directory", directory.toString()); put("elapsedSeconds", (System.nanoTime() - started) / 1e9)
            put("requiredSeats", 64); put("requiredRealUnits", 65_536); put("seatsConnected", clients.size)
            put("streamBudgetBytesPerSecond", 16_777_216); put("usesDefaultSessionLimits", true)
            put("movementStartsOnlyAfterAllExactBindings", movementStarted)
            put("scopeDoesNotProveLateJoinDuringMovementOrCombat", true)
            put("parentSampledPeakHeapUsedBytes", peakHeap); put("parentSampledPeakDirectBytes", peakDirect)
            put("parentMeasuredProcessCpuNanos", measuredCpu); put("parentMeasurementWallSeconds", measuredSeconds)
            put("actualPrivateVisibility", JsonArray(visibilityReports))
            slowReport.get()?.let { put("slowReader", it); put("slowReaderRecovered", slowReleasedTime.get() >= 0 && checks["otherPeersAdvancedDuringSlowReader"] == true) }
            put("checks", JsonObject(checks.mapValues { JsonPrimitive(it.value) }))
            childReady?.let { put("childReady", it) }; measurement?.let { put("measurement", it) }; childFinal?.let { put("childFinal", it) }
            historyReport?.let { put("onlineHistory", it) }; put("archives", JsonArray(archiveReports))
            put("peers", JsonArray(peers.map { p -> buildJsonObject {
                put("teamId", p.client.teamId); put("commandAttempts", p.sequence); put("commandsQueued", p.queuedCommands)
                put("staleRefused", p.staleRefused); put("queuedIsNotWireWriteOrEngineAcceptance", true); put("observedPositionChanges", p.changes)
                put("maxDisplacementWorld", p.displacement); put("before", FullChainSupport.wire(p.before)); put("after", FullChainSupport.wire(p.client.wireStatistics()))
            } }))
            put("ownerEofExitCode", eofExit); put("ownerEofWaitSeconds", eofSeconds); put("cleanupForced", forced.get())
            put("childLogBytes", logBytes.get()); put("childLogLimitBytes", 16L * 1024 * 1024); put("childLogLimitExceeded", logExceeded.get())
            error?.let { e ->
                var text = e.stackTraceToString().take(16_384).replace(password, "[redacted]")
                tokens.values.forEach { text = text.replace(it, "[redacted]") }; put("error", text)
            }
        }
    }
}

/** Shared test-only serialization/measurement helpers, independent of any engine instance. */
internal object FullChainSupport {
    /** Owner-confined exact tail, separately labelled; never replaces the formal600 tick sample. */
    class OwnerStepTail(private val capacity: Int = 1024) {
        private data class Step(val tick: Int, val scope: String, val engineNanos: Long, val roomNanos: Long,
                                val engineCpuNanos: Long?, val roomCpuNanos: Long?)
        private val values = arrayOfNulls<Step>(capacity.also { require(it in 1..1024) })
        private var total = 0L
        fun add(tick: Int, scope: String, engineNanos: Long, roomNanos: Long,
                engineCpuNanos: Long? = null, roomCpuNanos: Long? = null) {
            require(scope in setOf("ADMISSION", "MEASURED", "POST_MEASUREMENT") && engineNanos >= 0 && roomNanos >= engineNanos)
            require((engineCpuNanos == null && roomCpuNanos == null) ||
                (engineCpuNanos != null && roomCpuNanos != null && engineCpuNanos >= 0 && roomCpuNanos >= engineCpuNanos))
            values[(total % capacity).toInt()] = Step(tick, scope, engineNanos, roomNanos, engineCpuNanos, roomCpuNanos); total++
        }
        fun snapshot() = buildJsonObject {
            val count = minOf(total, capacity.toLong()).toInt()
            val steps = List(count) { offset -> checkNotNull(values[((total - count + offset) % capacity).toInt()]) }
            put("tailScope", "Last completed owner steps only; ADMISSION includes room admission/projection work; excludes boot/cleanup. Tail statistics are not the formal600tick performance gate.")
            put("capacity", capacity); put("completedSteps", total); put("retainedSteps", count); put("droppedCount", total - count)
            put("doesNotReplaceFormal600TickAcceptance", true)
            put("cpuScope", "Current engine-owner thread only; excludes other workers. Wall minus CPU can include scheduling, safepoints and waits.")
            put("steps", buildJsonArray { steps.forEach { step -> add(buildJsonObject {
                put("tick", step.tick); put("scope", step.scope); put("nativeAdvanceNanos", step.engineNanos); put("wholeRoomNanos", step.roomNanos)
                if (step.engineCpuNanos != null && step.roomCpuNanos != null) {
                    put("nativeOwnerCpuNanos", step.engineCpuNanos); put("wholeRoomOwnerCpuNanos", step.roomCpuNanos)
                }
            }) } })
            put("byScope", buildJsonArray { steps.groupBy { it.scope }.forEach { (scope, group) -> add(buildJsonObject {
                put("scope", scope); put("nativeAdvance", timing(group.map { it.engineNanos }.toLongArray(), group.size))
                put("wholeRoom", timing(group.map { it.roomNanos }.toLongArray(), group.size))
                val cpu = group.filter { it.engineCpuNanos != null && it.roomCpuNanos != null }
                put("ownerCpuSamples", cpu.size)
                if (cpu.isNotEmpty()) {
                    put("nativeOwnerCpu", timing(cpu.map { checkNotNull(it.engineCpuNanos) }.toLongArray(), cpu.size))
                    put("wholeRoomOwnerCpu", timing(cpu.map { checkNotNull(it.roomCpuNanos) }.toLongArray(), cpu.size))
                }
            }) } })
        }
    }
    data class Options(val output: Path, val scenarios: List<TimelineFullChainLayout.Scenario>, val ticks: Int, val deadlineSeconds: Int)
    fun options(args: Array<String>): Options {
        val fields = linkedMapOf<String, String>()
        args.forEach { argument ->
            require(argument.startsWith("--") && '=' in argument)
            val key = argument.substring(2).substringBefore('=')
            require(key in setOf("output", "scenario", "ticks", "deadline-seconds") && key !in fields)
            fields[key] = argument.substringAfter('=')
        }
        val scenario = when (fields["scenario"] ?: "both") {
            "both" -> TimelineFullChainLayout.Scenario.entries
            "regional" -> listOf(TimelineFullChainLayout.Scenario.REGIONAL)
            "interleaved" -> listOf(TimelineFullChainLayout.Scenario.INTERLEAVED)
            else -> error("Invalid scenario")
        }
        return Options(Path.of(requireNotNull(fields["output"])).toAbsolutePath().normalize(), scenario,
            (fields["ticks"]?.toInt() ?: 600).also { require(it in 600..3600) },
            (fields["deadline-seconds"]?.toInt() ?: 180).also { require(it in 60..600) })
    }
    fun writeNew(path: Path, value: JsonObject) {
        // A hard link publishes a fully written regular file atomically and fails if the
        // destination exists. Readers never observe an incomplete ready/measurement JSON.
        val temporary = Files.createTempFile(path.parent, ".fullchain-", ".json")
        try {
            Files.writeString(temporary, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value) + "\n", StandardOpenOption.WRITE)
            Files.createLink(path, temporary)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun read(path: Path): JsonObject { check(Files.size(path) <= 4 * 1024 * 1024); return Json.parseToJsonElement(Files.readString(path)).jsonObject }
    fun waitUntil(deadline: Long) { while (System.nanoTime() < deadline) LockSupport.parkNanos((deadline - System.nanoTime()).coerceAtMost(TimeUnit.MILLISECONDS.toNanos(50))) }
    fun percentile(values: LongArray, count: Int, fraction: Double): Double =
        if (count == 0) Double.POSITIVE_INFINITY else values.copyOf(count).sortedArray()[(ceil(count * fraction).toInt() - 1).coerceIn(0, count - 1)].toDouble()
    fun timing(values: LongArray, count: Int) = buildJsonObject {
        put("samples", count)
        if (count > 0) { put("p50Millis", percentile(values, count, .5) / 1e6); put("p95Millis", percentile(values, count, .95) / 1e6)
            put("maxMillis", values.take(count).max() / 1e6); put("over16Point667Millis", values.take(count).count { it > 1_000_000_000.0 / 60 }) }
    }
    fun limits(l: TimelineSessionLimits) = buildJsonObject {
        put("liveWindowSeconds", l.liveWindowSeconds); put("observationCount", l.observationCount); put("observationBytes", l.observationBytes)
        put("archiveCount", l.archiveCount); put("archiveBytes", l.archiveBytes); put("historyRequests", l.historyRequests)
        put("historyStateBytes", l.historyStateBytes); put("historyBytes", l.historyBytes); put("operationTimeoutMillis", l.operationTimeoutMillis)
        put("all64ConfiguredReservationCeilingsBytes", 64L * (l.observationBytes + l.archiveBytes + l.historyBytes))
        put("reservationCeilingsAreNotPreallocatedAndDoNotReplaceProcessHeapLimits", true)
    }
    fun sessions(values: Map<Int, TimelineRoomSessionStatistics>) = buildJsonArray {
        values.toSortedMap().values.forEach { grant -> add(buildJsonObject {
            val s = grant.statistics
            put("teamId", grant.teamId); put("joinedAt", grant.joinedAt); put("processedThrough", grant.processedThrough)
            put("archivedUntil", grant.archivedUntil); put("accepting", grant.accepting); put("failure", grant.failure)
            put("acceptedCommands", grant.acceptedCommands); put("acceptedSelections", grant.acceptedSelections)
            put("offered", s.offeredObservations); put("processed", s.processedObservations); put("publications", s.publications); put("archivedSlices", s.archivedSlices)
            put("pendingObservations", s.pendingObservations); put("pendingObservationBytes", s.pendingObservationBytes)
            put("pendingArchives", s.pendingArchives); put("pendingArchiveBytes", s.pendingArchiveBytes)
            put("historyReservations", s.historyReservations); put("reservedHistoryBytes", s.reservedHistoryBytes)
            put("peakPendingObservations", s.peakPendingObservations); put("peakPendingObservationBytes", s.peakPendingObservationBytes)
            put("peakPendingArchives", s.peakPendingArchives); put("peakPendingArchiveBytes", s.peakPendingArchiveBytes)
            put("peakHistoryReservations", s.peakHistoryReservations); put("peakReservedHistoryBytes", s.peakReservedHistoryBytes)
        }) }
    }
    fun wire(s: TimelineWireStatistics) = buildJsonObject {
        put("receivedBytes", s.receivedBytes); put("writtenBytes", s.writtenBytes); put("mapInitializations", s.mapInitializations); put("mapCacheHits", s.mapCacheHits)
        put("transactions", s.transactions); put("stateChecks", s.stateChecks); put("stateAcks", s.stateAcks); put("stateMismatches", s.stateMismatches)
        put("stateDigestNanos", s.stateDigestNanos); put("restores", s.restores)
    }
    fun authentication(s: TimelineAuthenticationStatistics) = buildJsonObject {
        put("acceptedAttempts", s.acceptedAttempts); put("installedPeers", s.installedPeers); put("firstHellosWritten", s.firstHellosWritten)
        put("failedBeforeFirstHello", s.failedBeforeFirstHello); put("activeAttempts", s.activeAttempts)
        put("retainedFailureCapacity", s.retainedFailureCapacity); put("droppedFailures", s.droppedFailures)
        put("failures", buildJsonArray { s.failures.forEach { failure -> add(buildJsonObject {
            put("attemptId", failure.attemptId); put("phase", failure.phase.name); put("matchedTeamId", failure.matchedTeamId)
            put("elapsedMillis", failure.elapsedMillis); put("ownerFutureDone", failure.ownerFutureDone)
            put("ownerFutureCancelled", failure.ownerFutureCancelled); put("reason", failure.reason.name); put("exceptionClass", failure.exceptionClass)
        }) } })
    }
    fun peerClosures(s: TimelinePeerClosureStatistics) = buildJsonObject {
        put("closedAfterHello", s.closedAfterHello); put("retainedCapacity", s.retainedCapacity); put("droppedClosures", s.droppedClosures)
        put("closures", buildJsonArray { s.closures.forEach { closure -> add(buildJsonObject {
            put("teamId", closure.teamId); put("generation", closure.generation); put("reason", closure.reason.name)
            put("exceptionClass", closure.exceptionClass); put("elapsedMillis", closure.elapsedMillis)
            put("peerQueuedCountAtCause", closure.peerQueuedCountAtCause); put("ownerQueueSizeAtRecord", closure.ownerQueueSizeAtRecord)
            put("wire", wire(closure.wire))
        }) } })
    }
    private fun ownProbeCandidates(state: TimelineState): Sequence<Pair<TimelineObject, TimelineValue.Vec3>> {
        val time = state.header.observedThrough
        return state.objects.values.asSequence().filter { obj -> obj.kind == TimelineObjectKind.UNIT &&
            TimelineProfile.sample(state, obj.handle, 6, time) == TimelineValue.SInt(state.context.teamId.toLong()) &&
            TimelineProfile.sample(state, obj.handle, 9, time) == TimelineValue.Bool(true) &&
            (TimelineProfile.sample(state, obj.handle, 7, time) as? TimelineValue.Blob)?.value
                ?.toByteArray()?.toString(Charsets.UTF_8) == "tank"
        }.mapNotNull { obj -> (TimelineProfile.sample(state, obj.handle, 1, time) as? TimelineValue.Vec3)?.let { obj to it } }
    }
    /** Exact static identity only. Nearest candidates are diagnostics, never an identity fallback. */
    fun exactProbe(state: TimelineState, x: Long, y: Long): TimelineObject? = ownProbeCandidates(state)
        .filter { (_, point) -> point.x == x && point.y == y }.take(2).toList().singleOrNull()?.first

    fun probeDiagnostic(state: TimelineState, x: Long, y: Long, expectedHandle: Long? = null) = buildJsonObject {
        data class Near(val handle: Long, val point: TimelineValue.Vec3, val distance: Double)
        val nearest = ArrayList<Near>(4)
        ownProbeCandidates(state).forEach { (obj, point) ->
            nearest.add(Near(obj.handle, point, hypot((point.x - x).toDouble(), (point.y - y).toDouble()) / 256))
            nearest.sortWith(compareBy<Near> { it.distance }.thenBy { it.handle })
            if (nearest.size > 3) nearest.removeAt(3)
        }
        put("passed", false); put("teamId", state.context.teamId); put("streamId", state.header.streamId)
        put("epoch", state.header.epoch); put("revision", state.header.revision); put("observedThrough", state.header.observedThrough)
        put("expectedBirthXQ", x); put("expectedBirthYQ", y); put("expectedDisclosureHandle", expectedHandle)
        put("nearestAreDiagnosticOnly", true)
        put("nearest3ActiveControllableOwnTanks", buildJsonArray { nearest.forEach { candidate -> add(buildJsonObject {
            put("disclosureHandle", candidate.handle); put("xQ", candidate.point.x); put("yQ", candidate.point.y)
            put("distanceWorld", candidate.distance)
        }) } })
    }
    fun visibility(state: TimelineState) = buildJsonObject {
        val time = state.header.observedThrough; val map = state.context.mapRecord
        val width = map.number(2).toInt(); val height = map.number(3).toInt()
        val worldWidth = width.toLong() * map.number(4) * 256; val worldHeight = height.toLong() * map.number(5) * 256
        val regions = sortedSetOf<Int>(); var activeUnits = 0; var ownedUnits = 0
        state.objects.values.forEach { obj ->
            if (obj.kind != TimelineObjectKind.UNIT || (obj.close?.until?.let { time >= it } == true)) return@forEach
            activeUnits++
            if (TimelineProfile.sample(state, obj.handle, 6, time) == TimelineValue.SInt(state.context.teamId.toLong())) {
                ownedUnits++
                (TimelineProfile.sample(state, obj.handle, 1, time) as? TimelineValue.Vec3)?.let { p ->
                    regions.add(((p.y * 8 / worldHeight).toInt().coerceIn(0, 7) * 8) + (p.x * 8 / worldWidth).toInt().coerceIn(0, 7))
                }
            }
        }
        var zero = 0; var explored = 0
        repeat((width * height + 255) / 256) { page -> TimelineProfile.fogPage(state, page, time).forEach { value ->
            if (value.toInt() == 0) zero++; if (value.toInt() < 10) explored++
        } }
        put("teamId", state.context.teamId); put("observedThrough", time); put("activeDisclosedUnits", activeUnits)
        put("ownedUnits", ownedUnits); put("ownedSpatialRegions", JsonArray(regions.map(::JsonPrimitive)))
        put("mapCells", width * height); put("currentlyVisibleCells", zero); put("exploredCells", explored)
        put("persistentBytes", TimelineCanonical.persistentSize(state))
    }
    private fun n(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
    private fun selection(handle: Long) = TimelineBytes(ByteArrayOutputStream().apply {
        TimelineBinary.writeVUInt(this, 1); TimelineBinary.writeVUInt(this, handle)
    }.toByteArray())
    fun select(s: TimelineState, handle: Long, sequence: Long) = TimelineFrame(6, TimelineRecord(listOf(
        n(1, s.header.streamId, true), n(2, s.header.epoch), n(3, sequence), n(4, s.header.revision), TimelineMessages.bytesField(5, selection(handle)))))
    fun move(s: TimelineState, handle: Long, sequence: Long, x: Long, y: Long): TimelineFrame {
        val target = TimelineRecord(listOf(n(1, 1, true), TimelineField(2, true, TimelineTlvValue.I64(x)), TimelineField(3, true, TimelineTlvValue.I64(y))))
        return TimelineFrame(5, TimelineRecord(listOf(n(1, s.header.streamId, true), n(2, s.header.epoch), n(3, sequence), n(4, s.header.revision),
            n(5, s.header.controlEpoch), n(6, 1, true), TimelineMessages.bytesField(7, selection(handle)), n(8, 0), TimelineMessages.recordField(9, target), n(10, 0, true))))
    }
}
