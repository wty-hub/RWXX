package io.github.rwx

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.authoritative.AuthoritativeRoomCertificate
import io.github.rwx.net.authoritative.AuthoritativeTls
import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.ServerSocket
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
import kotlin.system.exitProcess

/** Opt-in real production-entry capacity check. This parent owns clients, never an engine. */
object TimelineSeatCapacityAcceptanceMain {
    private const val SEATS = 64
    private const val MAX_HEAP = 2L * 1024 * 1024 * 1024
    private const val DEADLINE_SECONDS = 120L

    @JvmStatic fun main(args: Array<String>) {
        require(System.getenv("RWX_RUN_TIMELINE_SEAT_CAPACITY") == "1") { "Explicit seat capacity opt-in is required" }
        require(args.size == 1 && args.single().startsWith("--output=")) { "Use --output=NEW_JSON_FILE" }
        val output = Path.of(args.single().substringAfter('=')).toAbsolutePath().normalize()
        require(!Files.exists(output)) { "Evidence destination already exists" }
        Files.createDirectories(output.parent)
        System.setProperty("java.awt.headless", "true")
        val started = System.nanoTime()
        val owner = AtomicReference<Run?>(); val finished = AtomicBoolean(); val evidenceClaimed = AtomicBoolean()
        val watchdog = Thread({
            try { Thread.sleep(DEADLINE_SECONDS * 1000) } catch (_: InterruptedException) { return@Thread }
            if (finished.get()) return@Thread
            Thread({
                try { Thread.sleep(2000) } catch (_: InterruptedException) { }
                Runtime.getRuntime().halt(124)
            }, "timeline-seat-capacity-hard-stop").apply { isDaemon = true; start() }
            owner.get()?.emergencyStop()
            if (evidenceClaimed.compareAndSet(false, true)) runCatching { write(output, buildJsonObject {
                put("passed", false); put("timedOut", true); put("deadlineSeconds", DEADLINE_SECONDS)
                put("protocolVersion", TimelineProtocol.VERSION); put("scope", "Incomplete 64-seat test; no capacity acceptance")
                owner.get()?.let { put("directory", it.directory.toString()); it.child.get()?.let { p -> put("serverPid", p.pid()) } }
            }) }
            Runtime.getRuntime().halt(124)
        }, "timeline-seat-capacity-deadline").apply { isDaemon = true; start() }
        var error: Throwable? = null
        try { Run(output, started).also { owner.set(it) }.execute() } catch (failure: Throwable) { error = failure }
        finally { try { owner.get()?.cleanup() } catch (failure: Throwable) {
            if (error == null) error = failure else if (error !== failure) error!!.addSuppressed(failure)
        } }
        check(evidenceClaimed.compareAndSet(false, true)) { "Deadline already claimed the evidence destination" }
        val result = owner.get()?.report(error) ?: buildJsonObject {
            put("passed", false); put("errorKind", error?.javaClass?.name ?: "Initialization failed")
        }
        write(output, result)
        finished.set(true); watchdog.interrupt()
        val passed = result.getValue("passed").jsonPrimitive.boolean
        println("TimelineSeatCapacityAcceptance passed=$passed output=$output")
        exitProcess(if (passed) 0 else 1)
    }

    private class Run(output: Path, private val started: Long) {
        val directory: Path = Files.createTempDirectory(output.parent, "timeline-64-seats-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val child = AtomicReference<Process?>()
        private val deadline = started + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)
        private val workDeadline = deadline - TimeUnit.SECONDS.toNanos(30)
        private val random = SecureRandom()
        private fun secret() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private val tokens = (0 until SEATS).associateWith { secret() }
        private val password = secret()
        private val clients = ConcurrentLinkedQueue<TimelineClient>()
        private val monitored = mutableListOf<TimelineClient>()
        private val subscriptions = ConcurrentLinkedQueue<AutoCloseable>()
        private val releaseReader = CountDownLatch(1)
        private val forced = AtomicBoolean()
        private val cachedMap = AtomicReference<TimelineRecord?>()
        private val checks = linkedMapOf<String, Boolean>()
        private val grants = linkedMapOf<Int, TimelineRecord>()
        private val seatReports = mutableListOf<JsonObject>()
        private val archiveReports = mutableListOf<JsonObject>()
        private val previousTimes = mutableMapOf<TimelineClient, Long>()
        private val serverLog = directory.resolve("server.log")
        private val archives = directory.resolve("archives")
        private var fingerprint = ""
        private var port = 0
        private var eofExit: Int? = null
        private var eofSeconds: Double? = null
        private var slowReport: JsonObject? = null
        private var reconnectReport: JsonObject? = null
        private var historyReport: JsonObject? = null

        fun execute() {
            verify("parentHasNoGameEngine", GameEngine.getInstance() == null)
            verify("parentHeapAtMost2GiB", Runtime.getRuntime().maxMemory() <= MAX_HEAP)
            verify("allSeatTokensUnique", tokens.values.toSet().size == SEATS)
            val seats = privateFile("seats.properties", tokens.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }.toByteArray())
            val certificate = AuthoritativeRoomCertificate.generate(password.toCharArray())
            fingerprint = AuthoritativeTls.fingerprint(certificate.certificate)
            val store = privateFile("server.p12", certificate.pkcs12)
            val passwordFile = privateFile("tls-password.txt", password.toByteArray())
            val stateDir = Files.createDirectory(directory.resolve("server-state")).toFile()
            port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val command = AuthoritativeProcessCommand.current(listOf("--authority-server", "--owner-stdin=true",
                "--bind=127.0.0.1", "--port=$port", "--seats=$seats", "--archive-dir=$archives",
                "--home-dir=${directory.resolve("server-home")}",
                "--tls-keystore=$store", "--tls-password-file=$passwordFile"), stateDir,
                listOf("-Djava.awt.headless=true", "-Xms128m", "-Xmx2g"))
            child.set(ProcessBuilder(command).redirectErrorStream(true).redirectOutput(serverLog.toFile()).start())
            verify("separateServerProcess", child.get()!!.pid() != ProcessHandle.current().pid())
            await("server ready") { Files.exists(serverLog) && Files.readString(serverLog).contains("Timeline server listening on $port;") }
            verify("productionLargeArenaMap", Files.readString(serverLog).contains("Map: ${TimelineDesktopServer.LARGE_ARENA_MAP};"))
            verify("serverUsesPinnedTls", Files.readString(serverLog).contains("Server certificate SHA-256: $fingerprint"))
            val early = join(0, 0)
            await("first map saved") { cachedMap.get() != null }
            val firstTime = live(early)!!.header.observedThrough
            await("world advances before late seats") { live(early)!!.header.observedThrough >= firstTime + 30 * 1024 }
            for (batch in (1 until SEATS).chunked(8)) {
                val lower = live(early)!!.header.observedThrough
                batch.forEach { team -> join(team, lower) }
                await("world advances after batch ${batch.last()}") { live(early)!!.header.observedThrough > lower }
            }
            verify("all64AccessGrantsUnique", grants.values.map { it.bytes(12) }.toSet().size == SEATS)
            verify("exactlyOneInitialMapDownload", monitored.sumOf { it.wireStatistics().mapInitializations } == 1L)
            verify("other63InitialMapCacheHits", monitored.sumOf { it.wireStatistics().mapCacheHits } == 63L)
            verify("all64DefaultNativePairsAlive", monitored.all { ownedTypes(it) == listOf("builder", "commandCenter") })
            await("five complete checks and acknowledgements on all64 peers") {
                monitored.all { it.wireStatistics().let { s -> s.stateChecks >= 5 && s.stateAcks >= 5 } }
            }
            verify("allInitialPeersHaveZeroMismatchAndRestore", monitored.all(::healthyDigest))
            slowReader()
            // Preserve the original 64-connection evidence before the intentional reconnect.
            monitored.forEach { c -> seatReports.add(buildJsonObject {
                put("teamId", c.teamId); put("joinedAt", c.serverHello.number(13)); put("historyFrom", c.serverHello.number(8))
                put("accessIdHex", c.serverHello.bytes(12).toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
                put("ownedTypes", JsonArray(ownedTypes(c).map(::JsonPrimitive))); put("statistics", stats(c))
            }) }
            val beforeDisconnect = live(monitored.last())!!.header.observedThrough
            monitored.remove(early); early.close()
            await("other peers continue after seat0 disconnect") { live(monitored.last())!!.header.observedThrough > beforeDisconnect }
            val resumed = connect(0); resumed.openTimeline(1)
            await("same grant reconnect LIVE") { live(resumed) != null }
            val old = grants.getValue(0); val hello = resumed.serverHello
            verify("reconnectPreservesGrant", hello.number(14) == 2L && hello.bytes(12) == old.bytes(12) &&
                hello.number(13) == old.number(13) && hello.number(8) == old.number(8) && hello.bytes(5) == old.bytes(5))
            verify("reconnectChangesSession", hello.bytes(6) != old.bytes(6))
            validateState(live(resumed)!!, hello)
            reconnectReport = buildJsonObject { put("teamId", 0); put("admission", 2); put("accessAndHistoryPreserved", true); put("sessionChanged", true) }
            history(monitored.single { it.teamId == 63 })
            await("reconnected peer five checks") { resumed.wireStatistics().let { it.stateChecks >= 5 && it.stateAcks >= 5 } }
            verify("allRemainingPeersHaveZeroMismatchAndRestore", monitored.all(::healthyDigest))
            verify("still64LiveConnections", monitored.size == SEATS && monitored.all { live(it) != null })
            verify("still128OwnedNativeUnits", monitored.all { ownedTypes(it) == listOf("builder", "commandCenter") })

            subscriptions.forEach { it.close() }; releaseReader.countDown()
            clients.forEach { it.close() }; monitored.clear()
            val process = child.get()!!
            verify("serverAliveBeforeOwnerEof", process.isAlive)
            val eof = System.nanoTime(); process.outputStream.close()
            check(process.waitFor(minOf(TimeUnit.SECONDS.toNanos(17), remaining()), TimeUnit.NANOSECONDS)) { "Owner EOF did not stop server within17s" }
            eofSeconds = (System.nanoTime() - eof) / 1e9; eofExit = process.exitValue()
            verify("ownerEofNormalExit0", eofExit == 0 && !forced.get())
            val files = Files.list(archives).use { it.filter { p -> p.fileName.toString().endsWith(".rwxr") }.sorted().toList() }
            verify("all64GrantArchivesPresent", files.size == SEATS)
            for (file in files) inspectArchive(file)
            verify("all64ArchivedTeamsUnique", archiveReports.map { it.getValue("teamId").jsonPrimitive.int }.toSet().size == SEATS)
            verify("parentStillHasNoGameEngine", GameEngine.getInstance() == null)
        }

        private fun join(team: Int, lower: Long): TimelineClient {
            val client = connect(team); client.openTimeline(1)
            await("seat$team LIVE") { live(client) != null }
            val hello = client.serverHello
            verify("seat${team}AdmissionAndBoundary", client.teamId == team && hello.number(14) == 1L &&
                hello.number(13) >= lower && hello.number(8) == hello.number(13))
            if (team > 0) verify("seat${team}JoinsLaterThanFirst", hello.number(13) > grants.getValue(0).number(13))
            validateState(live(client)!!, hello)
            verify("seat${team}NativeStartingPair", ownedTypes(client) == listOf("builder", "commandCenter"))
            grants[team] = hello
            return client
        }
        private fun connect(team: Int): TimelineClient {
            check(workDeadline - System.nanoTime() > TimeUnit.SECONDS.toNanos(10)) { "Insufficient remaining handshake budget" }
            return TimelineClient("127.0.0.1", port, "Capacity seat $team", tokens.getValue(team), fingerprint,
                mapResolver = { key -> cachedMap.get()?.takeIf { TimelineCanonical.mapKey(it) == key } },
                onMapReceived = { cachedMap.set(it) }).also { clients.add(it); monitored.add(it) }
        }
        private fun live(client: TimelineClient) = client.latestStates().values.singleOrNull { it.context.mode == TimelineStreamMode.LIVE }
        private fun ownedTypes(client: TimelineClient): List<String> {
            val state = checkNotNull(live(client)); val time = state.header.observedThrough
            return state.objects.values.filter { it.kind == TimelineObjectKind.UNIT && (it.close?.until?.let { end -> time < end } ?: true) &&
                (TimelineProfile.sample(state, it.handle, 6, time) as? TimelineValue.SInt)?.value == client.teamId.toLong() }
                .map { (TimelineProfile.sample(state, it.handle, 7, time) as TimelineValue.Blob).value.toByteArray().toString(Charsets.UTF_8) }.sorted()
        }
        private fun validateState(state: TimelineState, hello: TimelineRecord) {
            check(state.context.accessId == hello.bytes(12) && state.context.matchId == hello.bytes(5) &&
                state.context.joinedAt == hello.number(13) && state.context.historyFrom == hello.number(8)) { "State crossed its grant" }
            check(state.header.retainedFrom >= state.context.historyFrom && state.objects.values.all { it.observedFrom >= state.context.joinedAt }) { "State contains pre-grant observations" }
        }
        private fun healthyDigest(client: TimelineClient) = client.wireStatistics().let { it.stateMismatches == 0L && it.restores == 0L }
        private data class Progress(val time: Long, val acks: Long)
        private fun progress(c: TimelineClient) = Progress(checkNotNull(live(c)).header.observedThrough, c.wireStatistics().stateAcks)
        private fun advanced(before: Progress, after: Progress) = after.time > before.time && after.acks > before.acks

        private fun slowReader() {
            val slow = monitored.single { it.teamId == 63 }
            val others = listOf(0, 1, 8, 16, 24, 32, 40, 56).map { id -> monitored.single { it.teamId == id } }
            val claimed = AtomicBoolean(); val done = CountDownLatch(1); val entered = AtomicLong(); val ended = AtomicLong()
            val before = AtomicReference<List<Progress>>(); val after = AtomicReference<List<Progress>>()
            val slowRelease = AtomicReference<Progress>(); val failure = AtomicReference<Throwable?>()
            val subscription = slow.observeLatest(emitCurrent = false) { state ->
                if (state.context.mode == TimelineStreamMode.LIVE && claimed.compareAndSet(false, true)) try {
                    before.set(others.map(::progress)); entered.set(System.nanoTime())
                    releaseReader.await(4, TimeUnit.SECONDS)
                    after.set(others.map(::progress)); slowRelease.set(progress(slow))
                } catch (error: Throwable) { failure.set(error) }
                finally { ended.set(System.nanoTime()); done.countDown() }
            }
            subscriptions.add(subscription)
            try {
                await("4s application-reader hold") { done.count == 0L }
                check(failure.get() == null) { "Slow-reader observer failed" }
                verify("eightOtherPeersAdvanceDuringReaderHold", before.get().zip(after.get()).all { advanced(it.first, it.second) })
                verify("readerHeldAtLeast3Point8Seconds", ended.get() - entered.get() >= TimeUnit.MILLISECONDS.toNanos(3800))
                await("slow reader catches up after release") { advanced(slowRelease.get(), progress(slow)) }
                slowReport = buildJsonObject {
                    put("kind", "application-reader-hold"); put("teamId", 63); put("actualHoldSeconds", (ended.get() - entered.get()) / 1e9)
                    put("otherTeamsAdvanced", JsonArray(others.map { JsonPrimitive(it.teamId) })); put("recovered", true)
                    put("otherClients", JsonArray(others.mapIndexed { index, client -> buildJsonObject {
                        put("teamId", client.teamId); put("before", progressJson(before.get()[index])); put("after", progressJson(after.get()[index]))
                    } }))
                    put("slowAtRelease", progressJson(slowRelease.get())); put("slowAfter", progressJson(progress(slow)))
                    put("connectionsChangedDuringHold", 0); put("tcpZeroWindowClaimed", false)
                }
            } finally { releaseReader.countDown(); subscription.close(); subscriptions.remove(subscription) }
        }

        private fun history(client: TimelineClient) {
            val from = client.serverHello.number(8); val until = from + client.ticksPerSecond * 1024L / 2
            await("sealed own history") { live(client)!!.header.sealedThrough >= until + client.ticksPerSecond * 1024L }
            val ends = ConcurrentLinkedQueue<TimelineFrame>()
            val subscription = client.observeFrames { if (it.type == 112 && ends.size < 8) ends.add(it) }
            subscriptions.add(subscription)
            client.openTimeline(2, TimelineStreamMode.HISTORY, from, until)
            await("own HISTORY window") { client.latestStates().values.any { it.context.mode == TimelineStreamMode.HISTORY } }
            val state = client.latestStates().values.single { it.context.mode == TimelineStreamMode.HISTORY }
            validateState(state, client.serverHello)
            verify("historyReadOnlyExactOwnWindow", state.header.controlEpoch == 0L && state.header.retainedFrom == from && state.header.retainedUntil == until)
            val select = TimelineFrame(6, TimelineRecord(listOf(TimelineMessages.numberField(1, state.header.streamId, true),
                TimelineMessages.numberField(2, state.header.epoch), TimelineMessages.numberField(3, 1),
                TimelineMessages.numberField(4, state.header.revision), TimelineMessages.bytesField(5, TimelineBytes(byteArrayOf(0))))))
            verify("historyRejectsSelect", runCatching { client.select(select) }.isFailure)
            await("HISTORY acknowledged and ended") { ends.any { it.body.number(1) == state.header.streamId && it.body.number(3) == 2L } }
            historyReport = buildJsonObject { put("teamId", 63); put("from", from); put("until", until); put("readOnly", true); put("completed", true) }
        }

        private fun inspectArchive(path: Path) {
            remaining()
            TimelineArchive.open(path).use { archive ->
                val hello = grants.getValue(archive.context.teamId)
                check(archive.readOnly && archive.index.isNotEmpty()) { "Archive has no complete sealed slices" }
                check(archive.context.accessId == hello.bytes(12) && archive.context.historyFrom == hello.number(8) &&
                    archive.context.joinedAt == hello.number(13)) { "Archive grant mismatch" }
                check(archive.index.all { it.from >= archive.context.historyFrom && it.until > it.from })
                val times = listOf(archive.index.first().from, archive.index.last().until - 1).distinct()
                for (time in times) {
                    remaining(); val slice = checkNotNull(archive.seek(time)); validateState(slice.state, hello)
                    check(slice.readOnly && slice.state.context.mode == TimelineStreamMode.HISTORY && slice.state.header.controlEpoch == 0L)
                    check(TimelineProfile.sample(slice.state, 0, 1, time) != null) { "Boundary sample missing ROOT credits" }
                }
                archiveReports.add(buildJsonObject {
                    put("teamId", archive.context.teamId); put("path", path.toString()); put("slices", archive.index.size)
                    put("from", archive.index.first().from); put("until", archive.index.last().until); put("boundarySamples", times.size); put("readOnly", true)
                })
            }
        }
        private fun stats(c: TimelineClient) = buildJsonObject {
            val s = c.wireStatistics(); put("observedThrough", live(c)?.header?.observedThrough)
            put("receivedBytes", s.receivedBytes); put("writtenBytes", s.writtenBytes); put("transactions", s.transactions)
            put("stateChecks", s.stateChecks); put("stateAcks", s.stateAcks); put("stateMismatches", s.stateMismatches); put("restores", s.restores)
            put("mapInitializations", s.mapInitializations); put("mapCacheHits", s.mapCacheHits)
        }
        private fun progressJson(p: Progress) = buildJsonObject { put("observedThrough", p.time); put("stateAcks", p.acks) }
        private fun await(stage: String, condition: () -> Boolean) {
            while (true) {
                check(System.nanoTime() < workDeadline) { "Work deadline exceeded: $stage" }
                check(child.get()?.isAlive == true) { "Owned server exited during $stage; inspect server.log" }
                for (client in monitored) {
                    check(client.failure == null && client.isConnected) { "Seat${client.teamId} failed during $stage" }
                    live(client)?.let { state ->
                        val previous = previousTimes.put(client, state.header.observedThrough)
                        check(previous == null || state.header.observedThrough >= previous) { "Peer time regressed" }
                    }
                }
                if (condition()) return
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
            }
        }
        private fun remaining(): Long = (deadline - System.nanoTime()).also { check(it > 0) { "Absolute120s deadline exceeded" } }
        private fun verify(name: String, passed: Boolean) { checks[name] = passed; check(passed) { "Check failed: $name" } }
        private fun privateFile(name: String, bytes: ByteArray): Path = directory.resolve(name).also {
            Files.write(it, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-------"))
        }
        fun emergencyStop() {
            forced.set(true); releaseReader.countDown()
            child.get()?.takeIf { it.isAlive }?.let { process -> runCatching {
                process.destroyForcibly(); process.waitFor(500, TimeUnit.MILLISECONDS)
            } }
            clients.forEach { runCatching { it.close() } }
        }
        fun cleanup() {
            releaseReader.countDown(); subscriptions.forEach { runCatching { it.close() } }; clients.forEach { runCatching { it.close() } }
            child.get()?.takeIf { it.isAlive }?.let { process ->
                runCatching { process.outputStream.close() }
                val time = minOf(TimeUnit.SECONDS.toNanos(17), (deadline - System.nanoTime() - TimeUnit.SECONDS.toNanos(1)).coerceAtLeast(0))
                if (!process.waitFor(time, TimeUnit.NANOSECONDS)) {
                    forced.set(true); process.destroy()
                    if (!process.waitFor(250, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); process.waitFor(250, TimeUnit.MILLISECONDS) }
                }
            }
            verify("ownedServerStopped", child.get()?.isAlive != true)
            verify("parentHasNoGameEngineAfterCleanup", GameEngine.getInstance() == null)
        }
        fun report(error: Throwable?) = buildJsonObject {
            put("passed", error == null && !forced.get()); put("protocolVersion", TimelineProtocol.VERSION); put("applicationVersion", AppMetadata.VERSION_NAME)
            put("scope", "Acceptance target: 64 distinct seats, 128 default native commandCenter/builder units, 64 simultaneous real pinned-TLS loopback clients and one separate production headless server. No 65,536-unit full-pipeline, GUI, WAN, frame-rate or latency acceptance claim.")
            put("requiredSeats", SEATS); put("requiredNativeUnits", SEATS * 2)
            put("observedNativeUnitsAtSample", seatReports.sumOf { it.getValue("ownedTypes").jsonArray.size })
            put("samplePhase", "afterReaderHoldBeforeReconnect")
            put("elapsedSeconds", (System.nanoTime() - started) / 1e9); put("deadlineSeconds", DEADLINE_SECONDS)
            put("parentPid", ProcessHandle.current().pid()); child.get()?.let { put("serverPid", it.pid()) }
            put("parentMaxHeapBytes", Runtime.getRuntime().maxMemory()); put("serverMaxHeapOptionBytes", MAX_HEAP)
            put("directory", directory.toString()); put("serverLog", serverLog.toString()); put("credentialsInArgv", false)
            put("checks", JsonObject(checks.mapValues { JsonPrimitive(it.value) })); put("seats", JsonArray(seatReports)); put("archives", JsonArray(archiveReports))
            slowReport?.let { put("slowReader", it) }; reconnectReport?.let { put("reconnect", it) }; historyReport?.let { put("history", it) }
            put("ownerEofExitCode", eofExit); put("ownerEofWaitSeconds", eofSeconds); put("cleanupForced", forced.get())
            error?.let { e ->
                var message = e.stackTraceToString().replace(password, "[redacted]")
                tokens.values.forEach { message = message.replace(it, "[redacted]") }
                put("error", message)
            }
        }
    }
    private fun write(path: Path, value: JsonObject) = Files.writeString(path,
        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value) + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
}
