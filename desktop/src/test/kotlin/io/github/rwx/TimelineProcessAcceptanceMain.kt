package io.github.rwx

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.system.exitProcess

/** Explicit four-seat production-entry smoke test; the parent never boots a simulation. */
object TimelineProcessAcceptanceMain {
    @JvmStatic fun main(args: Array<String>) {
        require(System.getenv("RWX_RUN_TIMELINE_PROCESS_ACCEPTANCE") == "1") { "Explicit process acceptance opt-in is required" }
        require(args.size == 1 && args.single().startsWith("--output=")) { "Use --output=NEW_JSON_FILE" }
        val output = Path.of(args.single().substringAfter('=')).toAbsolutePath().normalize()
        require(!Files.exists(output)) { "Evidence destination already exists" }
        Files.createDirectories(output.parent)
        System.setProperty("java.awt.headless", "true")
        var run: Run? = null
        var error: Throwable? = null
        try { run = Run(output); run.execute() } catch (failure: Throwable) { error = failure }
        finally {
            try { run?.cleanup() } catch (failure: Throwable) {
                if (error == null) error = failure else if (error !== failure) error!!.addSuppressed(failure)
            }
        }
        val result = run?.report(error) ?: buildJsonObject {
            put("passed", false); put("stage", "private run initialization")
            put("error", error?.javaClass?.name ?: "Initialization failed")
        }
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result) + "\n",
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        println("TimelineProcessAcceptance passed=${error == null} output=$output")
        exitProcess(if (error == null) 0 else 1)
    }

    private class Run(private val output: Path) {
        private val started = System.nanoTime()
        private val netemEnabled = System.getenv("RWX_RUN_TIMELINE_NETEM_PHASE") == "1"
        private val totalSeconds = if (netemEnabled) 90L else 60L
        private val deadline = started + TimeUnit.SECONDS.toNanos(totalSeconds)
        private val workDeadline = deadline - TimeUnit.SECONDS.toNanos(17)
        private val directory = privateDirectory(output.parent, output.fileName.toString().substringBeforeLast('.') + "-state-")
        private val serverLog = directory.resolve("server.log")
        private val archives = directory.resolve("server-archives")
        private val random = SecureRandom()
        private val tokens = listOf(0, 1, 2, 63).associateWith {
            ByteArray(32).also(random::nextBytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }
        private val checks = linkedMapOf<String, Boolean>()
        private val seats = mutableListOf<JsonObject>()
        private val archiveReports = mutableListOf<JsonObject>()
        private val allClients = mutableListOf<TimelineClient>()
        private val subscriptions = mutableListOf<AutoCloseable>()
        private val cachedMap = AtomicReference<TimelineRecord?>()
        private val monitored = mutableListOf<TimelineClient>()
        private val previousTimes = mutableMapOf<TimelineClient, Long>()
        private val grants = mutableMapOf<Int, TimelineRecord>()
        private var child: Process? = null
        private var recorder: TimelineViewRecorder? = null
        private var recordingFinished: CompletableFuture<TimelineViewRecorder.Result>? = null
        private var recordingResult: TimelineViewRecorder.Result? = null
        private var reconnect: JsonObject? = null
        private var history: JsonObject? = null
        private var netemPhase: JsonObject? = null
        private var eofExitCode: Int? = null
        private var eofWaitSeconds: Double? = null
        private var cleanupForced = false
        private var port = 0

        fun execute() {
            verify("parentHasNoGameEngineBeforeLaunch", GameEngine.getInstance() == null)
            val seatFile = directory.resolve("seats.properties")
            Files.writeString(seatFile, tokens.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" },
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            privateFile(seatFile)
            val stateDir = directory.resolve("server-state").toFile().also { check(it.mkdir()) }
            port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val command = AuthoritativeProcessCommand.current(listOf("--authority-server", "--owner-stdin=true", "--ticks=${if (netemEnabled) 3600 else 900}",
                "--bind=127.0.0.1", "--port=$port", "--seats=$seatFile", "--archive-dir=$archives"), stateDir,
                listOf("-Djava.awt.headless=true"))
            child = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(serverLog.toFile()).start()
            verify("serverIsIndependentProcess", child!!.pid() != ProcessHandle.current().pid())
            await("production listener readiness") {
                Files.exists(serverLog) && Files.readString(serverLog).contains("Timeline server listening on $port;")
            }
            verify("defaultMapIsArenaForFourSeats", Files.readString(serverLog).contains("Map: ${TimelineDesktopServer.ARENA_MAP};"))

            val early = connect(0)
            recorder = TimelineViewRecorder(directory.resolve("client-seat-0.rwxr"))
            val recordingSubscription = early.observeLatest { state ->
                if (state.context.mode == TimelineStreamMode.LIVE) recorder!!.record(state)
            }
            subscriptions.add(recordingSubscription)
            early.openTimeline(1)
            await("first seat LIVE state and map cache") { live(early) != null && cachedMap.get() != null }
            inspectSeat(early, 0, 0)
            val firstTime = live(early)!!.header.observedThrough
            await("world advances with only seat zero connected") { live(early)!!.header.observedThrough >= firstTime + 30 * 1024 }
            verify("worldAdvancesBeforeOtherSeatsJoin", true)
            var previousJoin = early.serverHello.number(13)
            for (team in listOf(1, 2, 63)) {
                val before = live(early)!!.header.observedThrough
                val client = connect(team)
                client.openTimeline(1)
                await("seat $team LIVE state") { live(client) != null }
                inspectSeat(client, team, before)
                verify("seat${team}JoinedAfterPreviousGrant", client.serverHello.number(13) > previousJoin)
                previousJoin = client.serverHello.number(13)
                verify("seat${team}MapCacheHit", client.wireStatistics().mapCacheHits > 0)
                val after = live(early)!!.header.observedThrough
                await("world advances after seat $team joins") { live(early)!!.header.observedThrough >= after + 30 * 1024 }
            }
            verify("fourDistinctAccessGrants", grants.values.map { it.bytes(12) }.toSet().size == 4)
            if (netemEnabled) runNetemPhase()

            val otherBefore = monitored.filter { it !== early }.associateWith { live(it)!!.header.observedThrough }
            recordingSubscription.close(); subscriptions.remove(recordingSubscription)
            recordingFinished = recorder!!.finishAsync()
            monitored.remove(early); early.close()
            await("other peers continue during disconnect") {
                otherBefore.all { (client, time) -> live(client)!!.header.observedThrough >= time + 30 * 1024 }
            }
            val restored = connect(0)
            restored.openTimeline(1)
            await("same-token restored LIVE state") { live(restored) != null }
            val original = grants.getValue(0); val resumed = restored.serverHello
            verify("reconnectRestoresExistingAdmission", resumed.number(14) == 2L)
            verify("reconnectPreservesGrantBoundary", resumed.bytes(12) == original.bytes(12) &&
                resumed.number(13) == original.number(13) && resumed.number(8) == original.number(8))
            verify("reconnectChangesSession", resumed.bytes(6) != original.bytes(6))
            verify("reconnectPreservesMatch", resumed.bytes(5) == original.bytes(5))
            verify("reconnectMapCacheHit", restored.wireStatistics().mapCacheHits > 0)
            validateState(live(restored)!!, resumed)
            reconnect = buildJsonObject {
                put("admission", resumed.number(14)); put("joinedAt", resumed.number(13)); put("historyFrom", resumed.number(8))
                put("sessionChanged", true); put("accessPreserved", true); put("mapCacheHits", restored.wireStatistics().mapCacheHits)
            }

            val late = monitored.single { it.teamId == 63 }
            val from = late.serverHello.number(8)
            val until = from + late.ticksPerSecond * 1024L / 2
            await("late seat has a sealed history window") { live(late)!!.header.sealedThrough >= until + late.ticksPerSecond * 1024L }
            val statuses = ConcurrentLinkedQueue<TimelineFrame>()
            val statusSubscription = late.observeFrames { frame ->
                if (frame.type == 110 || frame.type == 112) {
                    if (statuses.size < 16) statuses.offer(frame)
                }
            }
            subscriptions.add(statusSubscription)
            late.openTimeline(2, TimelineStreamMode.HISTORY, from, until)
            await("late seat HISTORY commit") { late.latestStates().values.any { it.context.mode == TimelineStreamMode.HISTORY } }
            val historical = late.latestStates().values.single { it.context.mode == TimelineStreamMode.HISTORY }
            validateState(historical, late.serverHello)
            verify("historyIsReadOnly", historical.context.mode == TimelineStreamMode.HISTORY && historical.header.controlEpoch == 0L)
            verify("historyOnlyOwnGrantWindow", historical.context.accessId == late.serverHello.bytes(12) &&
                historical.header.retainedFrom == from && historical.header.retainedUntil == until && from >= late.serverHello.number(13))
            // This validly-shaped Select must be refused locally for the HISTORY stream.
            val select = TimelineFrame(6, TimelineRecord(listOf(TimelineMessages.numberField(1, historical.header.streamId, true),
                TimelineMessages.numberField(2, historical.header.epoch), TimelineMessages.numberField(3, 1),
                TimelineMessages.numberField(4, historical.header.revision), TimelineMessages.bytesField(5, TimelineBytes(byteArrayOf(0))))))
            verify("historyRejectsControlInput", runCatching { late.select(select) }.isFailure)
            await("history stream completes") { statuses.any { it.type == 112 && it.body.number(1) == historical.header.streamId && it.body.number(3) == 2L } }
            history = buildJsonObject { put("teamId", 63); put("from", from); put("until", until); put("readOnly", true); put("completed", true) }
            await("checks acknowledged by every connected peer") { monitored.all { it.wireStatistics().stateAcks > 0 } }
            verify("noPeerStateMismatch", monitored.all { it.wireStatistics().stateMismatches == 0L })
            verify("parentHasNoGameEngineAfterNetworking", GameEngine.getInstance() == null)

            val owned = checkNotNull(child)
            verify("serverStillRunningBeforeOwnerEof", owned.isAlive)
            val eofStart = System.nanoTime()
            owned.outputStream.close()
            check(owned.waitFor(minOf(TimeUnit.SECONDS.toNanos(17), remaining()), TimeUnit.NANOSECONDS)) { "Owned server did not stop within owner EOF deadline" }
            eofWaitSeconds = (System.nanoTime() - eofStart) / 1e9
            eofExitCode = owned.exitValue()
            verify("ownerPipeEofStopsServerNormally", eofExitCode == 0)
            // Server disconnect is now expected; no further progress assertions use these clients.
            monitored.clear()
            recordingResult = recordingFinished!!.get(remaining(), TimeUnit.NANOSECONDS)
            recorder!!.stoppedAsync().get(remaining(), TimeUnit.NANOSECONDS)
            val recorded = recordingResult!!
            verify("clientRecordingContainsCommittedSlices", recorded.sliceCount > 0)
            inspectArchive(recorded.path, grants.getValue(0), "client")
            val serverFiles = Files.list(archives).use { stream -> stream.filter { it.fileName.toString().endsWith(".rwxr") }.toList() }
            verify("serverArchivedAllFourGrants", serverFiles.size == 4)
            for (file in serverFiles.sorted()) {
                val team = TimelineArchive.open(file).use { it.context.teamId }
                inspectArchive(file, grants.getValue(team), "server")
            }
            verify("parentHasNoGameEngineAfterArchives", GameEngine.getInstance() == null)
        }

        private data class MovingPeer(val client: TimelineClient, val handle: Long, val initial: TimelineValue.Vec3,
            val targets: List<Pair<Long, Long>>, val before: JsonObject, var sent: Long = 0,
            var positionChanges: Long = 0, var previousX: Long = initial.x, var previousY: Long = initial.y,
            var maxDisplacement: Double = 0.0)

        private fun runNetemPhase() {
            verify("netemBeginsWithFourEstablishedConnections", monitored.size == 4 && monitored.all { it.isMapReady && live(it) != null })
            phaseMessage("ready")
            awaitPhaseInstruction("START")
            val peers = monitored.map { client ->
                val state = checkNotNull(live(client))
                val builder = state.objects.values.firstOrNull { unit ->
                    unit.kind == TimelineObjectKind.UNIT && (unit.close?.until?.let { state.header.observedThrough < it } ?: true) &&
                        (TimelineProfile.sample(state, unit.handle, 6, state.header.observedThrough) as? TimelineValue.SInt)?.value == client.teamId.toLong() &&
                        (TimelineProfile.sample(state, unit.handle, 9, state.header.observedThrough) as? TimelineValue.Bool)?.value == true &&
                        (TimelineProfile.sample(state, unit.handle, 7, state.header.observedThrough) as? TimelineValue.Blob)?.value
                            ?.toByteArray()?.toString(Charsets.UTF_8) == "builder"
                } ?: error("No disclosed controllable builder for the established data phase")
                val position = checkNotNull(TimelineProfile.sample(state, builder.handle, 1, state.header.observedThrough) as? TimelineValue.Vec3)
                MovingPeer(client, builder.handle, position, nearbyTargets(state.context.mapRecord, position), peerStatistics(client))
            }
            val connectionsBefore = allClients.size
            val hold = ReaderHold(peers.last().client, peers.dropLast(1).map { it.client })
            try {
            val phaseStart = System.nanoTime()
            var nextSend = phaseStart
            while (System.nanoTime() - phaseStart < TimeUnit.SECONDS.toNanos(10)) {
                check(System.nanoTime() < workDeadline) { "Established data phase exceeded the work deadline" }
                checkPeers("established netem data phase")
                val now = System.nanoTime()
                if (now - phaseStart >= TimeUnit.SECONDS.toNanos(2)) hold.arm()
                if (now >= nextSend) {
                    for (peer in peers) {
                        val state = checkNotNull(live(peer.client)) { "Established LIVE state became unavailable" }
                        val destination = peer.targets[(peer.sent % peer.targets.size).toInt()]
                        peer.client.sendCommand(move(state, peer.handle, ++peer.sent, destination.first, destination.second))
                    }
                    // Never catch up by bursting multiple overdue rounds after a scheduling pause.
                    nextSend = now + TimeUnit.SECONDS.toNanos(1)
                }
                peers.forEach(::observeMovement)
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
                check(!Thread.currentThread().isInterrupted) { "Established data phase interrupted" }
            }
            checkPeers("established netem data phase completion")
            peers.forEach(::observeMovement)
            val duration = (System.nanoTime() - phaseStart) / 1e9
            val clients = peers.map { peer -> buildJsonObject {
                put("teamId", peer.client.teamId); put("before", peer.before); put("after", peerStatistics(peer.client))
                put("commandsSent", peer.sent); put("observedPositionChanges", peer.positionChanges)
                put("maxDisplacementWorld", peer.maxDisplacement); put("movementObserved", peer.maxDisplacement > 0.0)
            } }
            val slowReader = hold.report()
            netemPhase = buildJsonObject {
                put("durationSeconds", duration); put("connections", 4); put("clients", JsonArray(clients))
                put("slowReader", slowReader)
                put("connectionsChanged", allClients.size - connectionsBefore)
                put("transportFailures", peers.count { it.client.failure != null || !it.client.isConnected })
                put("mapContentKeyHex", checkNotNull(live(peers.first().client)).context.mapKey.toByteArray()
                    .joinToString("") { "%02x".format(it.toInt() and 255) })
                put("scope", "Established loopback TCP connections; sent commands are not server acknowledgements. Movement is measured from authorized received position samples.")
            }
            // Always release the external worker to collect counters and remove its owned qdisc,
            // even when the measurements below subsequently fail the acceptance conditions.
            phaseMessage("end", netemPhase)
            awaitPhaseInstruction("CLEAN")
            verify("netemEstablishedPhaseAtLeastTenSeconds", duration >= 10.0)
            verify("netemReaderHeldForFourSeconds", slowReader.getValue("actualHoldSeconds").jsonPrimitive.double >= 3.9 &&
                slowReader.getValue("completed").jsonPrimitive.boolean)
            verify("netemOtherPeersAdvanceDuringReaderHold", slowReader.getValue("othersAdvanced").jsonPrimitive.boolean)
            verify("netemSlowReaderRecovers", slowReader.getValue("recovered").jsonPrimitive.boolean)
            for ((index, peer) in peers.withIndex()) {
                val before = peer.before
                val after = clients[index].getValue("after").jsonObject
                fun delta(key: String) = after.getValue(key).jsonPrimitive.long - before.getValue(key).jsonPrimitive.long
                verify("netemSeat${peer.client.teamId}ReceivesAndWritesData", delta("receivedBytes") > 0 && delta("writtenBytes") > 0)
                verify("netemSeat${peer.client.teamId}ChecksAndAcksAdvance", delta("stateChecks") > 0 && delta("stateAcks") > 0)
                verify("netemSeat${peer.client.teamId}NoStateMismatch", delta("stateMismatches") == 0L)
                verify("netemSeat${peer.client.teamId}WorldAdvances", delta("observedThrough") > 0)
                verify("netemSeat${peer.client.teamId}ActuallyMoves", peer.sent >= 9 && peer.maxDisplacement > 0.0)
            }
            } finally { hold.close() }
        }
        private inner class ReaderHold(private val slow: TimelineClient, private val others: List<TimelineClient>) : AutoCloseable {
            private val armed = AtomicBoolean()
            private val claimed = AtomicBoolean()
            private val release = CountDownLatch(1)
            private val done = CountDownLatch(1)
            private val entered = AtomicLong()
            private val finished = AtomicLong()
            private val failure = AtomicReference<String?>()
            private val slowBefore = AtomicReference<JsonObject?>()
            private val slowAtRelease = AtomicReference<JsonObject?>()
            private val othersBefore = AtomicReference<Map<Int, JsonObject>?>(null)
            private val othersAfter = AtomicReference<Map<Int, JsonObject>?>(null)
            private val subscription = slow.observeLatest(emitCurrent = false) { state ->
                if (state.context.mode == TimelineStreamMode.LIVE && armed.get() && claimed.compareAndSet(false, true)) {
                    try {
                        slowBefore.set(peerStatistics(slow))
                        othersBefore.set(others.associate { it.teamId to peerStatistics(it) })
                        entered.set(System.nanoTime())
                        // Bounded application-reader pause: the socket writer/heartbeats continue.
                        // This does not claim that TCP receive buffers become full or advertise zero windows.
                        release.await(4, TimeUnit.SECONDS)
                        othersAfter.set(others.associate { it.teamId to peerStatistics(it) })
                        slowAtRelease.set(peerStatistics(slow))
                    } catch (error: Throwable) {
                        failure.set(error.javaClass.simpleName)
                        if (error is InterruptedException) Thread.currentThread().interrupt()
                    } finally { finished.set(System.nanoTime()); done.countDown() }
                }
            }
            fun arm() { armed.set(true) }
            fun report(): JsonObject {
                val before = othersBefore.get().orEmpty()
                val after = othersAfter.get().orEmpty()
                val resumed = peerStatistics(slow)
                fun advances(a: JsonObject?, b: JsonObject?): Boolean = a != null && b != null &&
                    b.getValue("observedThrough").jsonPrimitive.long > a.getValue("observedThrough").jsonPrimitive.long &&
                    b.getValue("stateAcks").jsonPrimitive.long > a.getValue("stateAcks").jsonPrimitive.long
                val complete = done.count == 0L && failure.get() == null
                val from = entered.get(); val until = finished.get()
                return buildJsonObject {
                    put("kind", "application-reader-hold"); put("teamId", slow.teamId); put("requestedHoldSeconds", 4)
                    put("completed", complete)
                    put("actualHoldSeconds", if (from > 0 && until >= from) (until - from) / 1e9 else 0.0)
                    put("othersAdvanced", complete && others.size == 3 && others.all { advances(before[it.teamId], after[it.teamId]) })
                    put("recovered", complete && advances(slowAtRelease.get(), resumed))
                    put("tcpZeroWindowClaimed", false)
                    put("otherClients", JsonArray(others.map { client -> buildJsonObject {
                        put("teamId", client.teamId)
                        before[client.teamId]?.let { put("before", it) }; after[client.teamId]?.let { put("after", it) }
                    } }))
                    slowBefore.get()?.let { put("slowBefore", it) }; slowAtRelease.get()?.let { put("slowAtRelease", it) }
                    put("slowAfter", resumed); failure.get()?.let { put("failureKind", it) }
                }
            }
            override fun close() { release.countDown(); subscription.close() }
        }
        private fun phaseMessage(phase: String, statistics: JsonObject? = null) {
            val message = buildJsonObject {
                put("kind", "rwxx-netem-phase"); put("phase", phase); put("connections", 4)
                statistics?.let { put("dataStats", it) }
            }
            println(Json.encodeToString(JsonObject.serializer(), message)); System.out.flush()
        }
        private fun awaitPhaseInstruction(expected: String) {
            val response = CompletableFuture<String>()
            Thread({
                try {
                    val text = StringBuilder()
                    while (true) {
                        val next = System.`in`.read()
                        check(next >= 0) { "Network phase control pipe closed" }
                        if (next == 10) break
                        check(next in 32..126 && text.length < 8) { "Invalid network phase instruction" }
                        text.append(next.toChar())
                    }
                    response.complete(text.toString())
                } catch (error: Throwable) { response.completeExceptionally(error) }
            }, "timeline-netem-phase-control").apply { isDaemon = true; start() }
            while (!response.isDone) {
                check(System.nanoTime() < workDeadline) { "Network phase instruction deadline exceeded" }
                checkPeers("network phase control wait")
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
                check(!Thread.currentThread().isInterrupted) { "Network phase instruction interrupted" }
            }
            check(response.get(remaining(), TimeUnit.NANOSECONDS) == expected) { "Unexpected network phase instruction" }
        }
        private fun peerStatistics(client: TimelineClient): JsonObject {
            val state = checkNotNull(live(client)); val stats = client.wireStatistics()
            return buildJsonObject {
                put("observedThrough", state.header.observedThrough); put("tick", state.header.observedThrough / 1024)
                put("receivedBytes", stats.receivedBytes); put("writtenBytes", stats.writtenBytes)
                put("stateChecks", stats.stateChecks); put("stateAcks", stats.stateAcks)
                put("stateMismatches", stats.stateMismatches); put("restores", stats.restores)
                put("transactions", stats.transactions); put("mapCacheHits", stats.mapCacheHits)
            }
        }
        private fun observeMovement(peer: MovingPeer) {
            val state = checkNotNull(live(peer.client)) { "Established LIVE state became unavailable" }
            val point = TimelineProfile.sample(state, peer.handle, 1, state.header.observedThrough) as? TimelineValue.Vec3
                ?: error("Established builder position is no longer disclosed")
            if (point.x != peer.previousX || point.y != peer.previousY) peer.positionChanges++
            peer.previousX = point.x; peer.previousY = point.y
            val dx = (point.x - peer.initial.x).toDouble() / 256
            val dy = (point.y - peer.initial.y).toDouble() / 256
            peer.maxDisplacement = maxOf(peer.maxDisplacement, Math.hypot(dx, dy))
        }
        private fun nearbyTargets(map: TimelineRecord, point: TimelineValue.Vec3): List<Pair<Long, Long>> {
            val width = map.number(2); val height = map.number(3)
            val tileX = map.number(4) * 256; val tileY = map.number(5) * 256
            val terrain = map.bytes(6).toByteArray()
            val offsets = listOf(32L * 256 to 0L, -32L * 256 to 0L, 0L to 32L * 256, 0L to -32L * 256)
            val targets = offsets.map { (x, y) -> point.x + x to point.y + y }.filter { (x, y) ->
                x >= 0 && y >= 0 && x < width * tileX && y < height * tileY &&
                    (terrain[((y / tileY) * width + x / tileX).toInt()].toInt() and 3) == 0
            }
            check(targets.size >= 2) { "No two adjacent public land targets for builder movement" }
            return targets.take(2)
        }
        private fun move(state: TimelineState, handle: Long, sequence: Long, x: Long, y: Long): TimelineFrame {
            fun n(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
            val selection = ByteArrayOutputStream().apply { TimelineBinary.writeVUInt(this, 1); TimelineBinary.writeVUInt(this, handle) }
            val target = TimelineRecord(listOf(n(1, 1, true), TimelineField(2, true, TimelineTlvValue.I64(x)),
                TimelineField(3, true, TimelineTlvValue.I64(y))))
            return TimelineFrame(5, TimelineRecord(listOf(n(1, state.header.streamId, true), n(2, state.header.epoch), n(3, sequence),
                n(4, state.header.revision), n(5, state.header.controlEpoch), n(6, 1, true),
                TimelineMessages.bytesField(7, TimelineBytes(selection.toByteArray())), n(8, 0),
                TimelineMessages.recordField(9, target), n(10, 0, true))))
        }

        private fun connect(team: Int): TimelineClient {
            check(workDeadline - System.nanoTime() > TimeUnit.SECONDS.toNanos(10)) { "Insufficient handshake deadline" }
            return TimelineClient("127.0.0.1", port, "Process seat $team", tokens.getValue(team),
                mapResolver = { offered -> cachedMap.get()?.takeIf { TimelineCanonical.mapKey(it) == offered } },
                onMapReceived = { cachedMap.set(it) }).also { allClients.add(it); monitored.add(it) }
        }
        private fun live(client: TimelineClient): TimelineState? = client.latestStates().values.singleOrNull { it.context.mode == TimelineStreamMode.LIVE }
        private fun inspectSeat(client: TimelineClient, team: Int, beforeRequest: Long) {
            val hello = client.serverHello; val state = checkNotNull(live(client))
            verify("seat${team}FirstAdmission", client.teamId == team && hello.number(14) == 1L)
            verify("seat${team}GrantStartsAfterRequestLowerBound", hello.number(13) >= beforeRequest && hello.number(8) >= hello.number(13))
            validateState(state, hello)
            verify("seat${team}HasOwnedUnits", state.objects.values.any { it.kind == TimelineObjectKind.UNIT &&
                (TimelineProfile.sample(state, it.handle, 6, state.header.observedThrough) as? TimelineValue.SInt)?.value == team.toLong() })
            grants[team] = hello
            seats.add(buildJsonObject {
                put("teamId", team); put("requestLowerBound", beforeRequest); put("joinedAt", hello.number(13)); put("historyFrom", hello.number(8))
                put("firstObservedThrough", state.header.observedThrough); put("subjectCount", state.objects.size)
                put("earliestSubjectFrom", state.objects.values.minOf { it.observedFrom }); put("admission", hello.number(14))
                put("mapCacheHits", client.wireStatistics().mapCacheHits)
            })
        }
        private fun validateState(state: TimelineState, hello: TimelineRecord) {
            check(state.context.joinedAt == hello.number(13) && state.context.historyFrom == hello.number(8) &&
                state.context.accessId == hello.bytes(12) && state.context.matchId == hello.bytes(5)) { "State grant disagrees with admission" }
            check(state.objects.values.all { it.observedFrom >= state.context.joinedAt }) { "A subject predates its grant" }
            check(state.header.retainedFrom >= state.context.historyFrom) { "State retained pre-grant history" }
        }
        private fun inspectArchive(path: Path, hello: TimelineRecord, source: String) {
            checkTime()
            TimelineArchive.open(path).use { archive ->
                check(archive.readOnly && archive.context.accessId == hello.bytes(12) && archive.context.joinedAt == hello.number(13) &&
                    archive.context.historyFrom == hello.number(8) && archive.context.matchId == hello.bytes(5)) { "Archive grant differs from admission" }
                check(archive.index.isNotEmpty()) { "Archive has no committed slices" }
                check(archive.index.all { it.from >= archive.context.historyFrom && it.until > it.from }) { "Archive contains an invalid grant range" }
                for (entry in listOf(archive.index.first(), archive.index.last()).distinct()) {
                    val slice = checkNotNull(archive.seek(entry.from))
                    check(slice.readOnly && slice.state.context.mode == TimelineStreamMode.HISTORY && slice.state.header.controlEpoch == 0L)
                    validateState(slice.state, hello)
                }
                archiveReports.add(buildJsonObject {
                    put("source", source); put("path", path.toString()); put("teamId", archive.context.teamId)
                    put("joinedAt", archive.context.joinedAt); put("historyFrom", archive.context.historyFrom)
                    put("slices", archive.index.size); put("from", archive.index.first().from); put("until", archive.index.last().until)
                    put("bytes", Files.size(path)); put("readOnly", true); put("boundarySeeksVerified", true)
                })
            }
        }
        private fun verify(name: String, passed: Boolean) { checks[name] = passed; check(passed) { "Check failed: $name" } }
        private fun checkTime() { check(System.nanoTime() < deadline) { "Total ${totalSeconds}-second deadline exceeded" } }
        private fun remaining(): Long { checkTime(); return (deadline - System.nanoTime()).coerceAtLeast(1) }
        private fun await(stage: String, ready: () -> Boolean) {
            while (true) {
                check(System.nanoTime() < workDeadline) { "Work deadline exceeded: $stage" }
                checkPeers(stage)
                if (ready()) return
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
                check(!Thread.currentThread().isInterrupted) { "Process acceptance interrupted" }
            }
        }
        private fun checkPeers(stage: String) {
            check(child?.isAlive == true) { "Owned server exited during $stage; inspect server.log" }
            for (client in monitored) {
                check(client.failure == null && client.isConnected) { "Peer ${client.teamId} disconnected during $stage" }
                live(client)?.let { state ->
                    val previous = previousTimes.put(client, state.header.observedThrough)
                    check(previous == null || state.header.observedThrough >= previous) { "Peer time moved backwards" }
                }
            }
        }
        fun cleanup() {
            subscriptions.forEach { runCatching { it.close() } }
            allClients.forEach { runCatching { it.close() } }
            recorder?.close()
            val process = child
            if (process != null && process.isAlive) {
                runCatching { process.outputStream.close() }
                val wait = minOf(TimeUnit.SECONDS.toNanos(17), (deadline - System.nanoTime() - TimeUnit.SECONDS.toNanos(1)).coerceAtLeast(0))
                if (!process.waitFor(wait, TimeUnit.NANOSECONDS)) {
                    cleanupForced = true; process.destroy()
                    val grace = minOf(TimeUnit.MILLISECONDS.toNanos(300), (deadline - System.nanoTime()).coerceAtLeast(0))
                    if (!process.waitFor(grace, TimeUnit.NANOSECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(minOf(TimeUnit.MILLISECONDS.toNanos(300), (deadline - System.nanoTime()).coerceAtLeast(0)), TimeUnit.NANOSECONDS)
                    }
                }
            }
            checks["ownedServerStopped"] = process?.isAlive != true
            checks["parentHasNoGameEngineAfterCleanup"] = GameEngine.getInstance() == null
            check(checks.getValue("ownedServerStopped")) { "Owned server cleanup failed" }
            check(checks.getValue("parentHasNoGameEngineAfterCleanup")) { "Parent initialized the engine" }
        }
        fun report(failure: Throwable?): JsonObject = buildJsonObject {
            put("passed", failure == null); put("protocolVersion", TimelineProtocol.VERSION); put("applicationVersion", AppMetadata.VERSION_NAME)
            put("scope", "Four configured seats (0, 1, 2, 63), one independent production headless server process and real loopback clients. Covers admission, continuing simulation, reconnect, map cache, own-grant history and sealed recording. No GUI, 64-seat capacity, 65,536-unit, performance or Internet claim.")
            put("elapsedSeconds", (System.nanoTime() - started) / 1e9); put("parentPid", ProcessHandle.current().pid())
            put("netemPhaseEnabled", netemEnabled); put("totalDeadlineSeconds", totalSeconds)
            netemPhase?.let { put("netemPhase", it) }
            child?.let { put("serverPid", it.pid()) }; put("directory", directory.toString()); put("serverLog", serverLog.toString())
            put("checks", JsonObject(checks.mapValues { JsonPrimitive(it.value) })); put("seats", JsonArray(seats))
            reconnect?.let { put("reconnect", it) }; history?.let { put("history", it) }; put("archives", JsonArray(archiveReports))
            eofExitCode?.let { put("ownerEofExitCode", it) }; eofWaitSeconds?.let { put("ownerEofWaitSeconds", it) }
            put("cleanupForced", cleanupForced); put("credentialsInArgv", false)
            recordingResult?.let { result -> put("clientRecording", buildJsonObject {
                put("path", result.path.toString()); put("slices", result.sliceCount); put("gapCount", result.gapCount)
                put("partial", result.partial); put("unsealedTail", result.unsealedTail)
                result.firstTime?.let { put("from", it) }; result.untilTime?.let { put("until", it) }
            }) }
            failure?.let { issue ->
                var text = issue.stackTraceToString()
                tokens.values.forEach { token -> text = text.replace(token, "[redacted]") }
                put("error", text)
            }
        }
    }
    private fun privateDirectory(parent: Path, prefix: String): Path = try {
        Files.createTempDirectory(parent, prefix, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } catch (_: UnsupportedOperationException) { Files.createTempDirectory(parent, prefix) }
    private fun privateFile(path: Path) {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
        catch (_: UnsupportedOperationException) { /* The enclosing fresh directory remains private to this run. */ }
    }
}
