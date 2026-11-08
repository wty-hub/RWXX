package io.github.rwx

import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.Utility
import io.github.rwx.net.authoritative.*
import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.hypot
import kotlin.system.exitProcess

/** Test-only owner of the actual engine. No spawn switch is added to the production CLI. */
object TimelineFullChainServerMain {
    private const val SECOND = 1_000_000_000L
    private const val FIXTURE_SEED = 0x72575837
    @JvmStatic fun main(args: Array<String>) {
        check(System.getenv("RWX_RUN_TIMELINE_FULLCHAIN") == "1") { "Explicit full-chain acceptance opt-in is required" }
        val fields = args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }
        check(args.all { it.startsWith("--") && '=' in it } && fields.size == args.size &&
            fields.keys == setOf("directory", "scenario", "ticks", "deadline-seconds")) { "Invalid child arguments" }
        val directory = Path.of(fields.getValue("directory")).toAbsolutePath().normalize()
        val scenario = TimelineFullChainLayout.Scenario.valueOf(fields.getValue("scenario"))
        val ticks = fields.getValue("ticks").toInt().also { require(it in 600..3600) }
        val seconds = fields.getValue("deadline-seconds").toLong().also { require(it in 60..600) }
        check(!Files.exists(directory.resolve("child-result.json")))
        System.setProperty("java.awt.headless", "true")
        System.setProperty("launch.dir", Files.createDirectory(directory.resolve("child-state")).toString())
        System.setProperty("user.home", Files.createDirectory(directory.resolve("child-home")).toString())
        val started = System.nanoTime(); val done = AtomicBoolean(); val stage = AtomicReference("boot")
        val result = AtomicReference<JsonObject>(buildJsonObject { put("passed", false); put("completed", false) })
        val reportClaimed = AtomicBoolean()
        fun save(error: String?): Boolean {
            if (!reportClaimed.compareAndSet(false, true)) return false
            val value = buildJsonObject {
                result.get().forEach { (key, value) -> put(key, value) }
                put("passed", error == null && result.get()["passed"]?.jsonPrimitive?.boolean == true)
                put("stage", stage.get()); put("elapsedSeconds", (System.nanoTime() - started) / 1e9)
                put("serverPid", ProcessHandle.current().pid()); put("maxHeapBytes", Runtime.getRuntime().maxMemory())
                error?.let { put("error", it.take(16_384)) }
            }
            FullChainSupport.writeNew(directory.resolve("child-result.json"), value)
            return value.getValue("passed").jsonPrimitive.boolean
        }
        Thread({
            FullChainSupport.waitUntil(started + seconds * SECOND)
            if (!done.get()) { runCatching { save("Absolute child deadline exceeded during ${stage.get()}") }; Runtime.getRuntime().halt(124) }
        }, "fullchain-child-deadline").apply { isDaemon = true; start() }
        Thread({
            FullChainSupport.waitUntil(started + (seconds + 2) * SECOND)
            Runtime.getRuntime().halt(124) // Also bounds a shutdown hook blocked after exitProcess.
        }, "fullchain-child-hard-stop").apply { isDaemon = true; start() }
        var failure: Throwable? = null
        try { run(directory, scenario, ticks, stage, result) } catch (error: Throwable) { failure = error; error.printStackTrace(System.err) }
        val passed = runCatching { save(failure?.stackTraceToString()) }.getOrDefault(false)
        done.set(true)
        exitProcess(if (passed) 0 else 2)
    }

    private fun run(directory: Path, scenario: TimelineFullChainLayout.Scenario, ticks: Int,
                    stage: AtomicReference<String>, result: AtomicReference<JsonObject>) {
        check(Runtime.getRuntime().maxMemory() <= 4L * 1024 * 1024 * 1024) { "Child heap exceeds4GiB" }
        val engine = AuthoritativeDesktopMain.boot(TimelineDesktopServer.LARGE_ARENA_MAP)
        engine.globalSeed = FIXTURE_SEED
        engine.networkEngine.roomSettings.randomSeed = FIXTURE_SEED
        Utility.resetSharedRandomSeed()
        val stock = StockUnitCatalog.capture(engine); val map = EnginePublicMapSnapshot.capture(engine.tileMap)
        val tankType = checkNotNull(stock.typeFor("tank")); val baseType = checkNotNull(stock.typeFor("commandCenter"))
        val tankRadius = checkNotNull(BaseUnit.bG[tankType] as? BaseUnit).radius
        val baseRadius = checkNotNull(BaseUnit.bG[baseType] as? BaseUnit).radius
        val tileMap = engine.tileMap; val terrain = map.terrainCopy()
        stage.set("static-layout")
        val layout = TimelineFullChainLayout.plan(tileMap.tileCountX, tileMap.tileCountY, tileMap.tileWorldSizeX,
            tileMap.tileWorldSizeY, terrain, tankRadius, baseRadius, scenario)
        for (unit in GameObject.fastGameObjectList.filterIsInstance<BaseUnit>().toList()) unit.removeFromGame()
        GameObject.dL()
        for (id in 0 until PlayerTeam.TEAM_NEUTRAL) PlayerTeam.k(id)?.removeFromTeamRegistry()
        PlayerTeam.setMaxTeamId(64, false)
        val teams = List(64) { id -> GameTeam(id).apply {
            teamName = "Full-chain $id"; teamColorId = 0; credits = 100_000.0
            fogOfWarWidth = tileMap.tileCountX; fogOfWarHeight = tileMap.tileCountY
            fogOfWarData = Array(fogOfWarWidth) { ByteArray(fogOfWarHeight) { 10 } }
        } }
        engine.playerTeam = teams.first(); engine.networkEngine.localPlayerTeam = teams.first()
        val tanks = ArrayList<OrderableUnit>(65_472)
        fun place(unit: BaseUnit, p: TimelineFullChainLayout.Point, team: Int) {
            unit.posX = p.x; unit.posY = p.y; unit.setUnitTeam(teams[team]); unit.isActive = true
            // Layout checks radii and pair clearance. Construction checks terrain here;
            // every subsequent movement step keeps native collision enabled.
            check((unit as? OrderableUnit)?.canPlaceAtCurrentPositionWithIgnoreUnits(true, teams[team]) != false) {
                "Native placement rejected team$team at ${p.x},${p.y}"
            }
            unit.n(); PlayerTeam.c(unit); engine.unitSpatialIndex.a(unit)
        }
        stage.set("create-real-native-fixture")
        layout.bases.forEachIndexed { i, p -> place(baseType.a(), p, i) }
        layout.tanks.forEach { p ->
            val unit = tankType.a() as? OrderableUnit ?: error("Stock tank is not orderable")
            check(unit.moveSpeed > 0f); place(unit, p.point, p.teamId); tanks.add(unit)
        }
        GameObject.dL(); PlayerTeam.g(0f)
        check(livingUnits() == 65_536 && tanks.size == 65_472)
        check(PlayerTeam.getTeams().none { it is com.corrodinggames.rts.game.ai.AIController })
        val initialX = FloatArray(tanks.size) { tanks[it].posX }; val initialY = FloatArray(tanks.size) { tanks[it].posY }
        val probes = tanks.groupBy { it.team.teamId }.mapValues { (_, values) ->
            values.minWith(compareBy<OrderableUnit> { it.posX }.thenBy { it.posY })
        }
        val probeSet = probes.values.toSet()
        // Ordinary native commands establish the bulk moving fixture. Network probes below are
        // separate; the report never claims these65k commands arrived through the wire.
        stage.set("enqueue-native-moves")
        val destinations = tanks.map { unit -> TimelineScaleAcceptanceMain.movementTarget(unit.posX, unit.posY, tileMap.tileCountX,
                tileMap.tileCountY, tileMap.tileWorldSizeX, tileMap.tileWorldSizeY, terrain)
        }
        var moveWaves = 0
        fun queueMoves(outbound: Boolean) {
            tanks.forEachIndexed { index, unit ->
                if (unit in probeSet) return@forEachIndexed
                val command = engine.commandController.createCommand()
                command.team = unit.team; command.sourceTeam = unit.team; command.createdTick = engine.gameTimeMillis
                command.addUnitToCommand(unit)
                command.setMoveTarget(if (outbound) destinations[index].x else initialX[index],
                    if (outbound) destinations[index].y else initialY[index])
                check(command.prepareAndValidateCommand()); engine.commandController.pendingCommands.add(command)
            }
            moveWaves++
        }
        val credentials = Properties().apply { Files.newInputStream(directory.resolve("seats.properties")).use(::load) }
        val random = SecureRandom()
        val seats = (0 until 64).associate { id -> credentials.getProperty(id.toString()) to
            TimelineSeat(id, TimelineBytes(ByteArray(16).also(random::nextBytes))) }
        check(seats.size == 64 && seats.keys.all { it != null })
        val limits = TimelineSessionLimits() // Deliberately retain and report every production default.
        val room = TimelineEngineRoom.withNativeRoster(engine, seats.values, directory.resolve("archives"),
            prepareTeam = { TimelinePreparedTeam(teams[it]) }, limits = limits)
        val password = Files.readString(directory.resolve("tls-password.txt")).toCharArray()
        val tls = Files.newInputStream(directory.resolve("server.p12")).use { AuthoritativeTls.serverContext(it, password) }
        password.fill('\u0000')
        val server = TimelineServer(InetAddress.getLoopbackAddress(), 0, seats, room.mapRecord, room.matchId,
            room, serverTime = { room.serverTime }, historyUntil = room::historyUntil, tlsContext = tls, admissionProvider = room)
        FullChainSupport.writeNew(directory.resolve("ready.json"), buildJsonObject {
            put("port", server.localPort); put("pid", ProcessHandle.current().pid()); put("fixtureUnits", livingUnits())
            put("scenario", scenario.name); put("liveWindowSeconds", limits.liveWindowSeconds)
            put("globalSeed", engine.globalSeed); put("roomRandomSeed", engine.networkEngine.roomSettings.randomSeed)
            put("sharedRandomSeedReset", true)
            put("limits", FullChainSupport.limits(limits)); put("map", TimelineDesktopServer.LARGE_ARENA_MAP)
            put("nativeTankRadius", tankRadius); put("nativeBaseRadius", baseRadius); put("spacingWorld", layout.spacing)
            put("networkOnlyProbePositions", buildJsonArray { probes.toSortedMap().forEach { (team, unit) -> add(buildJsonObject {
                put("teamId", team); put("xQ", (unit.posX * 256).toLong()); put("yQ", (unit.posY * 256).toLong())
            }) } })
        })
        val controls = ArrayBlockingQueue<String>(4); val running = AtomicBoolean(true)
        Thread({
            try { System.`in`.bufferedReader().use { reader ->
                while (true) { val line = reader.readLine() ?: break
                    if (line == "START" && controls.offer(line)) continue
                    if (line == "STOP") break
                    error("Unexpected or excessive parent control")
                }
            } } catch (_: Exception) { } finally { running.set(false) }
        }, "fullchain-owner-stdin").apply { isDaemon = true; start() }
        val engineNanos = LongArray(ticks); val roomNanos = LongArray(ticks)
        val ownerTail = FullChainSupport.OwnerStepTail()
        var samples = 0; var startTick = -1; var completedMeasurement = false
        var startCpu = 0L; var measuredCpu = 0L; var measurementSeconds = 0.0; var measuredAt = 0L
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val ownerCpuClock = ManagementFactory.getThreadMXBean()
        runCatching {
            if (ownerCpuClock.isCurrentThreadCpuTimeSupported && !ownerCpuClock.isThreadCpuTimeEnabled)
                ownerCpuClock.isThreadCpuTimeEnabled = true
        }
        fun ownerCpu(): Long? = if (ownerCpuClock.isCurrentThreadCpuTimeSupported && ownerCpuClock.isThreadCpuTimeEnabled)
            ownerCpuClock.currentThreadCpuTime.takeIf { it >= 0 } else null
        var peakHeap = 0L; var peakDirect = 0L; var next = System.nanoTime()
        var cleanupError: Throwable? = null
        var archived = false; var stopped = false
        fun metrics(): JsonObject = buildJsonObject {
            put("completed", completedMeasurement); put("scenario", scenario.name); put("requiredMeasuredTicks", ticks)
            put("globalSeed", engine.globalSeed); put("roomRandomSeed", engine.networkEngine.roomSettings.randomSeed)
            put("sharedRandomSeedReset", true)
            put("measuredTicks", samples); put("startTick", startTick); put("lastTick", engine.currentTick)
            put("realLivingUnits", livingUnits()); put("engineAdvance", FullChainSupport.timing(engineNanos, samples))
            put("wholeRoomStep", FullChainSupport.timing(roomNanos, samples)); put("measurementSeconds", measurementSeconds)
            put("measuredProcessCpuNanos", measuredCpu); put("sampledPeakHeapUsedBytes", peakHeap); put("sampledPeakDirectBytes", peakDirect)
            put("maxHeapBytes", Runtime.getRuntime().maxMemory()); put("limits", FullChainSupport.limits(limits))
            put("sessions", FullChainSupport.sessions(room.sessionStatistics()))
            put("nativeObservation", room.observationStatistics().let { counters -> buildJsonObject {
                put("captures", counters.captures); put("reusedPasses", counters.reusedPasses)
                put("fallbackPasses", counters.fallbackPasses)
            } })
            put("authentication", FullChainSupport.authentication(server.authenticationStatistics()))
            put("peerClosures", FullChainSupport.peerClosures(server.peerClosureStatistics()))
            put("ownerStepTail", ownerTail.snapshot())
            val moved = IntArray(64); tanks.forEachIndexed { index, tank ->
                if (hypot((tank.posX - initialX[index]).toDouble(), (tank.posY - initialY[index]).toDouble()) > .25) moved[tank.team.teamId]++
            }
            put("movedTanksPerSeat", JsonArray(moved.map(::JsonPrimitive)))
            put("nativeFixtureMoveWaves", moveWaves); put("nativeFixtureReorderPeriodTicks", 120)
            put("networkOnlyProbeUnitsExcludedFromNativeFixtureCommands", probeSet.size)
            put("bulkMovementStartsOnlyAfterAll64ExactBindings", true)
            put("scopeDoesNotProveLateJoinDuringMovementOrCombat", true)
            put("scope", "64 human allied seats, ordinary stock moving tanks; native collision, fog, pathfinding and damage remain enabled. No combat capacity claim. Bulk fixture moves are owner-created; client probes use real v7 commands.")
            put("nativeFogEnabled", tileMap.fogEnabled); put("nativeRefogEnabled", tileMap.fogPeriodicMaintenanceEnabled)
            put("constructionNativeCheckIgnoresUnitScan", true); put("staticLayoutEnforcesUnitClearance", true)
            put("archiveFinishCompleted", archived); put("workersStopped", stopped)
            put("passed", completedMeasurement && samples >= 600 && livingUnits() == 65_536 && moved.all { it > 0 } &&
                FullChainSupport.percentile(engineNanos, samples, .95) <= SECOND / 60.0 &&
                FullChainSupport.percentile(roomNanos, samples, .95) <= SECOND / 60.0 &&
                room.sessionStatistics().size == 64 && room.sessionStatistics().values.all {
                    it.failure == null && it.acceptedCommands > 0 && it.acceptedSelections > 0 })
        }
        try {
            stage.set("live-admission")
            while (running.get()) {
                if (controls.poll() == "START") {
                    check(startTick == -1 && room.sessionStatistics().size == 64) { "START requires exactly64 admitted grants" }
                    startTick = engine.currentTick; measuredAt = System.nanoTime(); startCpu = os?.processCpuTime ?: 0
                    stage.set("measure600-real-ticks")
                }
                val record = startTick >= 0 && samples < ticks
                var elapsed = 0L; var engineCpu: Long? = null
                val roomCpuStart = ownerCpu(); val beforeRoom = System.nanoTime()
                room.runStep(server) {
                    // Include fixture command preparation in wholeRoomStep; engineAdvance below
                    // measures only the actual native tick. Reorder ordinary moves every2s of
                    // simulation so slow admission does not turn the workload into a quiet world.
                    if (record && samples % 120 == 0) queueMoves(samples / 120 % 2 == 0)
                    val cpuStart = ownerCpu()
                    val before = System.nanoTime(); AuthoritativeDesktopMain.advance(engine); elapsed = System.nanoTime() - before
                    engineCpu = cpuStart?.let { start -> ownerCpu()?.let { it - start } }
                }
                val whole = System.nanoTime() - beforeRoom
                val roomCpu = roomCpuStart?.let { start -> ownerCpu()?.let { it - start } }
                ownerTail.add(engine.currentTick, if (record) "MEASURED" else if (startTick < 0) "ADMISSION" else "POST_MEASUREMENT",
                    elapsed, whole, engineCpu?.takeIf { roomCpu != null }, roomCpu?.takeIf { engineCpu != null })
                check(!room.finished) { "Fixture unexpectedly reached terminal state" }
                if (record) {
                    engineNanos[samples] = elapsed; roomNanos[samples] = whole; samples++
                    if (samples == ticks) {
                        completedMeasurement = true; measurementSeconds = (System.nanoTime() - measuredAt) / 1e9
                        measuredCpu = (os?.processCpuTime ?: startCpu) - startCpu
                        result.set(metrics()); FullChainSupport.writeNew(directory.resolve("measurement.json"), result.get())
                        stage.set("history-and-client-drain")
                    }
                }
                peakHeap = maxOf(peakHeap, ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
                peakDirect = maxOf(peakDirect, ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java)
                    .filter { it.name == "direct" }.sumOf { it.memoryUsed })
                val sessions = room.sessionStatistics()
                if (engine.currentTick % 30 == 0 && !completedMeasurement) result.set(buildJsonObject {
                    put("passed", false); put("completed", false); put("scenario", scenario.name)
                    put("measuredTicks", samples); put("lastTick", engine.currentTick); put("admittedGrants", sessions.size)
                    put("engineAdvance", FullChainSupport.timing(engineNanos, samples)); put("wholeRoomStep", FullChainSupport.timing(roomNanos, samples))
                    put("sessions", FullChainSupport.sessions(sessions)); put("limits", FullChainSupport.limits(limits))
                    put("authentication", FullChainSupport.authentication(server.authenticationStatistics()))
                    put("peerClosures", FullChainSupport.peerClosures(server.peerClosureStatistics()))
                    put("ownerStepTail", ownerTail.snapshot())
                })
                check(sessions.values.all { it.failure == null && it.accepting }) { "A grant failed: ${sessions.values.firstOrNull { it.failure != null }}" }
                check(livingUnits() == 65_536) { "Actual native unit count changed" }
                next += SECOND / 60
                if (next < System.nanoTime() - SECOND) next = System.nanoTime()
                LockSupport.parkNanos((next - System.nanoTime()).coerceAtLeast(0))
            }
        } finally {
            stage.set("owner-finally")
            server.close()
            try { room.finishRecordingsAsync().get(10, TimeUnit.SECONDS); archived = true }
            catch (error: Throwable) { cleanupError = error }
            room.close()
            try { room.stoppedAsync().get(5, TimeUnit.SECONDS); stopped = true }
            catch (error: Throwable) { if (cleanupError == null) cleanupError = error else cleanupError!!.addSuppressed(error) }
            result.set(metrics())
        }
        cleanupError?.let { throw it }
        check(completedMeasurement) { "Parent EOF before600 measured ticks completed" }
        stage.set("complete")
    }

    private fun livingUnits() = GameObject.fastGameObjectList.count {
        it is BaseUnit && !it.isDead && !it.isDestroyed && it.currentHealth > 0f
    }
}
