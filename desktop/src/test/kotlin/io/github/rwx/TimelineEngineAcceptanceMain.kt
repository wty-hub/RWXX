package io.github.rwx

import com.corrodinggames.rts.game.PlayerTeam
import io.github.rwx.net.authoritative.EngineMatchStatus
import io.github.rwx.net.authoritative.EnginePublicMapSnapshot
import io.github.rwx.net.authoritative.StockActionClosure
import io.github.rwx.net.authoritative.StockUnitCatalog
import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.system.exitProcess

/** Small real-engine v7 smoke test. It is not a capacity, GUI or Internet test. */
object TimelineEngineAcceptanceMain {
    @JvmStatic fun main(args: Array<String>) {
        check(System.getenv("RWX_RUN_TIMELINE_ENGINE_ACCEPTANCE") == "1") { "Explicit engine acceptance opt-in is required" }
        require(args.size == 1 && args[0].startsWith("--output="))
        val output = File(args.single().substringAfter('=')).absoluteFile
        require(!Files.exists(output.toPath())) { "Evidence destination already exists" }
        Files.createDirectories(output.parentFile.toPath())
        val runtime = Files.createTempDirectory(output.parentFile.toPath(), "engine-runtime-")
        System.setProperty("launch.dir", runtime.toString())
        System.setProperty("user.home", Files.createDirectory(runtime.resolve("home")).toString())
        System.setProperty("java.awt.headless", "true")
        var failure: Throwable? = null
        var report = buildJsonObject {}
        val started = System.nanoTime()
        try { report = run(output.parentFile.toPath().resolve("archives")) } catch (error: Throwable) { failure = error }
        val result = buildJsonObject {
            put("passed", failure == null); put("applicationVersion", AppMetadata.VERSION_NAME); put("protocolVersion", 7)
            put("scope", "Two seats on the bundled Small Island map, real headless simulation and loopback v7 sockets with asynchronous publication/archive/history workers. No capacity, GUI or Internet claim.")
            put("elapsedSeconds", (System.nanoTime() - started) / 1e9); put("result", report)
            failure?.let { put("error", it.stackTraceToString()) }
        }
        Files.writeString(output.toPath(), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result) + "\n",
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        failure?.let { it.printStackTrace(System.err); exitProcess(1) }
        println("TimelineEngineAcceptance passed=true output=${output.path}")
        // The headless engine owns pathfinding threads. This explicit harness runs in its own JVM.
        exitProcess(0)
    }

    private fun run(archiveDirectory: Path): JsonObject {
        Files.createDirectories(archiveDirectory)
        val engine = AuthoritativeDesktopMain.boot(AuthoritativeDesktopMain.DEFAULT_MAP)
        val map = EnginePublicMapSnapshot.capture(engine.tileMap)
        val stock = StockUnitCatalog.capture(engine)
        val actions = StockActionClosure.capture(engine, stock)
        val record = EngineTimelineProjector.mapRecord(engine, map)
        val key = TimelineCanonical.mapKey(record)
        val random = SecureRandom()
        fun id() = TimelineBytes(ByteArray(16).also(random::nextBytes))
        val match = id()
        val credentials = (0..1).associateWith { ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) } }
        val grants = (0..1).associateWith { TimelineSeat(it, id()) }
        val sessions = grants.mapValues { (_, grant) -> TimelineSession(TimelineContext(match, grant.accessId, key, record,
            grant.teamId, 60, TimelineStreamMode.LIVE, 61440), archiveDirectory.resolve("seat-${grant.teamId}.rwxr")) }
        val projections = (0..1).associateWith { team -> EngineTimelineProjector(engine, PlayerTeam.k(team), map, stock, actions,
            status = { EngineMatchStatus.current() }) }
        val time = AtomicLong()
        fun capture() {
            time.set(engine.currentTick.toLong() * 1024)
            projections.forEach { (team, projection) ->
                val session = sessions.getValue(team)
                check(session.offer(projection.observe())) { "Observation admission failed: ${session.failure}" }
            }
        }
        capture()
        val factory = TimelineFeedFactory { request -> sessions.getValue(request.teamId).open(request) }
        try { TimelineServer(InetAddress.getLoopbackAddress(), 0, credentials.map { (team, token) -> token to grants.getValue(team) }.toMap(),
            record, match, factory, time::get, historyUntil = { team -> sessions.getValue(team).archivedUntil }).use { server ->
            val clients = mutableListOf<TimelineClient>()
            val cached = AtomicReference<TimelineRecord?>()
            try {
                for (team in 0..1) {
                    val client = TimelineClient("127.0.0.1", server.localPort, "Engine $team", credentials.getValue(team),
                        mapResolver = { offered -> cached.get()?.takeIf { TimelineCanonical.mapKey(it) == offered } },
                        onMapReceived = { cached.set(it) })
                    clients.add(client); client.openTimeline(1)
                }
                await { clients.all { it.latestStates().isNotEmpty() } }
                val first = clients.first().latestStates().values.single()
                val actor = first.objects.values.firstOrNull { obj -> obj.kind == TimelineObjectKind.UNIT &&
                    (TimelineProfile.sample(first, obj.handle, 7, first.header.observedThrough) as? TimelineValue.Blob)?.value
                        ?.toByteArray()?.toString(Charsets.UTF_8) == "builder" }
                    ?: error("Map has no disclosed builder")
                val initial = TimelineProfile.sample(first, actor.handle, 1, first.header.observedThrough) as TimelineValue.Vec3
                val factoryUnit = first.objects.values.firstOrNull { obj -> obj.kind == TimelineObjectKind.UNIT &&
                    (TimelineProfile.sample(first, obj.handle, 7, first.header.observedThrough) as? TimelineValue.Blob)?.value
                        ?.toByteArray()?.toString(Charsets.UTF_8) == "commandCenter" } ?: error("Map has no command center")
                val initialCredits = (TimelineProfile.sample(first, 0, 1, first.header.observedThrough) as TimelineValue.SInt).value
                var moveSent = false
                var selectionSent = false
                var productionSent = false
                var applied = 0
                var acceptedSelections = 0
                var deadline = System.nanoTime()
                repeat(180) {
                    if (!moveSent && engine.currentTick > 0) {
                        val seen = clients.first().latestStates().values.single()
                        clients.first().sendCommand(move(seen, actor.handle,
                            (initial.x + 40 * 256).coerceAtMost(engine.tileMap.tileCountX.toLong() * engine.tileMap.tileWorldSizeX * 256 - 1), initial.y))
                        moveSent = true
                    }
                    if (!selectionSent && engine.currentTick >= 30) {
                        val seen = clients.first().latestStates().values.single()
                        clients.first().select(select(seen, factoryUnit.handle))
                        selectionSent = true
                    }
                    if (selectionSent && !productionSent) {
                        val seen = clients.first().latestStates().values.single()
                        val ability = seen.objects.values.firstOrNull { obj -> obj.kind == TimelineObjectKind.ACTION && obj.close == null &&
                            obj.descriptor.record(4).number(1) == 2L && obj.descriptor.record(4).text(5) == "builder" &&
                            (TimelineProfile.sample(seen, obj.handle, 1, seen.header.observedThrough) as? TimelineValue.UInt)?.value == 1L }
                        if (ability != null) {
                            clients.first().sendCommand(produce(seen, factoryUnit.handle, ability.handle))
                            productionSent = true
                        }
                    }
                    server.drainRequests { request ->
                        val session = sessions.getValue(request.teamId)
                        if (projections.getValue(request.teamId).apply(request, session::unitKey, session::actionKey)) {
                            if (request.frame.type == 6) acceptedSelections++ else applied++
                        }
                    }
                    AuthoritativeDesktopMain.advance(engine); capture()
                    clients.forEach { check(it.failure == null) { "Client failed: ${it.failure}" } }
                    deadline += 16_666_667
                    LockSupport.parkNanos((deadline - System.nanoTime()).coerceAtLeast(0))
                }
                await { clients.all { it.wireStatistics().stateAcks >= 2 } }
                check(applied == 2 && acceptedSelections == 1 && productionSent) { "Move, selection and production were not accepted exactly once" }
                val last = clients.first().latestStates().values.single()
                val position = TimelineProfile.sample(last, actor.handle, 1, last.header.observedThrough) as TimelineValue.Vec3
                check(position.x != initial.x || position.y != initial.y) { "Accepted move did not change the builder position" }
                val finalCredits = (TimelineProfile.sample(last, 0, 1, last.header.observedThrough) as TimelineValue.SInt).value
                check(finalCredits < initialCredits) { "Accepted production did not spend credits" }
                check(clients.all { it.wireStatistics().stateMismatches == 0L })
                await { cached.get() != null }
                sessions.values.forEach { session ->
                    session.finishAsync().get(10, TimeUnit.SECONDS)
                    check(session.failure == null && session.archivedUntil > 61440) { "No sealed archive interval" }
                    TimelineArchive.open(session.archivePath).use { archive ->
                        check(archive.seek(0)?.state?.objects?.values?.any { it.kind == TimelineObjectKind.UNIT } == true)
                    }
                }
                clients.first().openTimeline(2, TimelineStreamMode.HISTORY, 0, 61440)
                await { clients.first().latestStates().values.any { it.context.mode == TimelineStreamMode.HISTORY } }
                return buildJsonObject {
                    put("ticks", engine.currentTick); put("commandsApplied", applied)
                    put("selectionsApplied", acceptedSelections); put("productionRequested", productionSent)
                    put("initialCreditsQ", initialCredits); put("finalCreditsQ", finalCredits)
                    put("asyncSession", true); put("archiveSeek", true); put("networkHistory", true)
                    put("movedXQ", position.x - initial.x); put("movedYQ", position.y - initial.y)
                    put("seats", buildJsonArray { clients.forEachIndexed { team, client -> add(buildJsonObject {
                        val stats = client.wireStatistics()
                        put("team", team); put("receivedBytes", stats.receivedBytes); put("writtenBytes", stats.writtenBytes)
                        put("transactions", stats.transactions); put("stateChecks", stats.stateChecks); put("stateAcks", stats.stateAcks)
                        put("stateMismatches", stats.stateMismatches); put("stateDigestNanos", stats.stateDigestNanos)
                        put("mapInitializations", stats.mapInitializations); put("mapCacheHits", stats.mapCacheHits)
                    }) } })
                }
            } finally { clients.forEach { it.close() } }
        } } finally {
            sessions.values.forEach { it.close() }
            sessions.values.forEach { it.stoppedAsync().get(5, TimeUnit.SECONDS) }
        }
    }

    private fun move(state: TimelineState, actor: Long, x: Long, y: Long): TimelineFrame {
        fun n(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
        val selection = ByteArrayOutputStream().apply { TimelineBinary.writeVUInt(this, 1); TimelineBinary.writeVUInt(this, actor) }
        val target = TimelineRecord(listOf(n(1, 1, true), TimelineField(2, true, TimelineTlvValue.I64(x)),
            TimelineField(3, true, TimelineTlvValue.I64(y))))
        return TimelineFrame(5, TimelineRecord(listOf(n(1, state.header.streamId, true), n(2, state.header.epoch), n(3, 1),
            n(4, state.header.revision), n(5, state.header.controlEpoch), n(6, 1, true),
            TimelineMessages.bytesField(7, TimelineBytes(selection.toByteArray())), n(8, 0),
            TimelineMessages.recordField(9, target), n(10, 0, true))))
    }
    private fun select(state: TimelineState, actor: Long): TimelineFrame {
        fun n(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
        val selection = ByteArrayOutputStream().apply { TimelineBinary.writeVUInt(this, 1); TimelineBinary.writeVUInt(this, actor) }
        return TimelineFrame(6, TimelineRecord(listOf(n(1, state.header.streamId, true), n(2, state.header.epoch), n(3, 1),
            n(4, state.header.revision), TimelineMessages.bytesField(5, TimelineBytes(selection.toByteArray())))))
    }
    private fun produce(state: TimelineState, actor: Long, action: Long): TimelineFrame {
        fun n(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
        val selection = ByteArrayOutputStream().apply { TimelineBinary.writeVUInt(this, 1); TimelineBinary.writeVUInt(this, actor) }
        return TimelineFrame(5, TimelineRecord(listOf(n(1, state.header.streamId, true), n(2, state.header.epoch), n(3, 2),
            n(4, state.header.revision), n(5, state.header.controlEpoch), n(6, 5, true),
            TimelineMessages.bytesField(7, TimelineBytes(selection.toByteArray())), n(8, action),
            TimelineMessages.recordField(9, TimelineRecord(listOf(n(1, 0, true)))), n(10, 0, true))))
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Engine acceptance timed out" }
            LockSupport.parkNanos(1_000_000)
        }
    }
}
