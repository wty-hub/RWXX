package io.github.rwx

import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameObject
import io.github.rwx.net.authoritative.*
import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.system.exitProcess

/** Isolated real-engine moving workload. It does not run client connections or claim room capacity. */
object TimelineScaleAcceptanceMain {
    internal data class Options(val units: Int, val seats: Int, val ticks: Int, val deadlineSeconds: Int,
                                val projection: String, val mapPath: String, val output: Path)
    internal data class Point(val x: Float, val y: Float)
    internal data class Layout(val bases: List<Point>, val tanks: List<Point>, val availableTankSlots: Int, val spacing: Int)
    private data class Phase(val elapsed: Long, val cpu: Long?, val allocated: Long?)
    private const val SECOND = 1_000_000_000L
    private const val BUDGET = SECOND / 60.0

    internal fun options(args: Array<String>): Options {
        val fields = LinkedHashMap<String, String>()
        for (arg in args) {
            require(arg.startsWith("--") && '=' in arg) { "Expected --name=value" }
            val name = arg.substring(2, arg.indexOf('='))
            require(name in setOf("units", "seats", "ticks", "deadline-seconds", "projection", "map", "output") && name !in fields) { "Unknown or duplicate option" }
            fields[name] = arg.substringAfter('=')
        }
        val count = fields["units"]?.toInt() ?: 1000
        val seats = fields["seats"]?.toInt() ?: 2
        val ticks = fields["ticks"]?.toInt() ?: 120
        val seconds = fields["deadline-seconds"]?.toInt() ?: 120
        val projection = fields["projection"] ?: "none"
        require(seats in 2..64 && count in seats * 2..65_536 && ticks in 1..10_000 && seconds in 1..600)
        require(projection in setOf("none", "one-seat", "publisher"))
        val mapPath = when (fields["map"] ?: "small-island") {
            "small-island" -> AuthoritativeDesktopMain.DEFAULT_MAP
            "arena" -> TimelineDesktopServer.ARENA_MAP
            "large-arena" -> TimelineDesktopServer.LARGE_ARENA_MAP
            else -> throw IllegalArgumentException("Only approved small-island, arena or large-arena maps may be used")
        }
        val output = Path.of(requireNotNull(fields["output"]) { "--output is required" }).toAbsolutePath().normalize()
        return Options(count, seats, ticks, seconds, projection, mapPath, output)
    }

    @JvmStatic fun main(args: Array<String>) {
        require(System.getenv("RWX_RUN_TIMELINE_SCALE_ACCEPTANCE") == "1") { "Explicit isolated engine acceptance opt-in is required" }
        val options = options(args)
        require(!Files.exists(options.output)) { "Evidence destination already exists" }
        Files.createDirectories(options.output.parent)
        val metrics = Metrics(options)
        val reported = AtomicBoolean()
        fun report(error: String?): Boolean {
            if (!reported.compareAndSet(false, true)) return false
            val result = metrics.report(error)
            Files.writeString(options.output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result) + "\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            return result["passed"]!!.jsonPrimitive.boolean
        }
        // The second daemon bounds even a stalled evidence write or a blocked JVM shutdown hook.
        Thread({
            waitUntil(metrics.started + options.deadlineSeconds * SECOND)
            if (!reported.get()) {
                try { report("Wall deadline exceeded during ${metrics.stage.get()}") }
                finally { Runtime.getRuntime().halt(124) }
            }
        }, "timeline-scale-deadline").apply { isDaemon = true; start() }
        Thread({
            waitUntil(metrics.started + options.deadlineSeconds * SECOND + 2 * SECOND)
            Runtime.getRuntime().halt(124)
        }, "timeline-scale-hard-stop").apply { isDaemon = true; start() }
        Thread({
            while (!reported.get()) { metrics.sampleHeap(); LockSupport.parkNanos(10_000_000) }
        }, "timeline-scale-heap-sampler").apply { isDaemon = true; start() }
        var error: String? = null
        try {
            System.setProperty("java.awt.headless", "true")
            val runtime = Files.createTempDirectory(options.output.parent, "scale-runtime-")
            System.setProperty("launch.dir", runtime.toString())
            System.setProperty("user.home", Files.createDirectory(runtime.resolve("home")).toString())
            run(options, metrics)
        } catch (failure: Throwable) {
            error = "${failure.javaClass.name}: ${failure.message}"
            failure.printStackTrace(System.err)
        }
        val passed = try { report(error) } catch (failure: Throwable) { failure.printStackTrace(System.err); false }
        println("TimelineScaleAcceptance passed=$passed output=${options.output}")
        // The native engine owns pathfinder workers; returning from main is insufficient.
        exitProcess(if (passed) 0 else 2)
    }

    private fun run(options: Options, m: Metrics) {
        val engine = m.phase("boot") { AuthoritativeDesktopMain.boot(options.mapPath) }
        check(!engine.networkEngine.networkGameActive && !engine.replayEngine.j())
        val stock = m.phase("stock-capture") { StockUnitCatalog.capture(engine) }
        val map = m.phase("public-map") { EnginePublicMapSnapshot.capture(engine.tileMap) }
        val tankType = checkNotNull(stock.typeFor("tank"))
        val baseType = checkNotNull(stock.typeFor("commandCenter"))
        val tankPrototype = checkNotNull(BaseUnit.bG[tankType] as? BaseUnit)
        val basePrototype = checkNotNull(BaseUnit.bG[baseType] as? BaseUnit)
        val tileMap = engine.tileMap
        val terrain = map.terrainCopy()
        val layout = m.phase("static-layout") { plan(tileMap.tileCountX, tileMap.tileCountY, tileMap.tileWorldSizeX,
            tileMap.tileWorldSizeY, terrain, options.seats, options.units, tankPrototype.radius, basePrototype.radius) }
        m.details.set(buildJsonObject {
            put("map", options.mapPath); put("mapWidth", tileMap.tileCountX); put("mapHeight", tileMap.tileCountY)
            put("tankSpacingWorld", layout.spacing); put("availableStaticTankSlots", layout.availableTankSlots)
            put("tankNativeType", tankType.unitName); put("baseNativeType", baseType.unitName)
            put("scenario", "Human allied seats, all stock tanks receive ordinary move orders; normal collision, pathfinding, fog and unit update remain enabled. No combat claim.")
        })
        val teams = m.phase("replace-map-fixture") {
            for (unit in GameObject.fastGameObjectList.filterIsInstance<BaseUnit>().toList()) unit.removeFromGame()
            GameObject.dL()
            for (id in 0 until PlayerTeam.TEAM_NEUTRAL) PlayerTeam.k(id)?.removeFromTeamRegistry()
            PlayerTeam.setMaxTeamId(maxOf(10, options.seats), false)
            List(options.seats) { id -> GameTeam(id).apply {
                teamName = "Scale ${id + 1}"; teamColorId = 0; credits = 100_000.0
                fogOfWarWidth = tileMap.tileCountX; fogOfWarHeight = tileMap.tileCountY
                fogOfWarData = Array(fogOfWarWidth) { ByteArray(fogOfWarHeight) { 10 } }
            } }.also {
                engine.playerTeam = it.first(); engine.networkEngine.localPlayerTeam = it.first()
            }
        }
        val tanks = ArrayList<OrderableUnit>(options.units - options.seats)
        m.phase("create-live-units") {
            fun place(unit: BaseUnit, point: Point, team: PlayerTeam) {
                unit.posX = point.x; unit.posY = point.y
                unit.setUnitTeam(team); unit.isActive = true
                unit.n(); PlayerTeam.c(unit); engine.unitSpatialIndex.a(unit)
                m.created.incrementAndGet()
            }
            layout.bases.forEachIndexed { i, point -> place(baseType.a(), point, teams[i]) }
            layout.tanks.forEachIndexed { i, point ->
                val tank = tankType.a() as? OrderableUnit ?: error("The audited tank is not orderable")
                check(tank.moveSpeed > 0f) { "The audited tank cannot move" }
                place(tank, point, teams[i % teams.size]); tanks.add(tank)
            }
            GameObject.dL(); PlayerTeam.g(0f)
        }
        check(PlayerTeam.getTeams().none { it is com.corrodinggames.rts.game.ai.AIController })
        m.actual.set(livingUnits())
        check(m.actual.get() == options.units && tanks.size == options.units - options.seats) { "Live engine unit count differs from requested count" }
        val initialX = FloatArray(tanks.size) { tanks[it].posX }
        val initialY = FloatArray(tanks.size) { tanks[it].posY }
        m.phase("enqueue-native-moves") {
            for (unit in tanks) {
                val target = movementTarget(unit.posX, unit.posY, tileMap.tileCountX, tileMap.tileCountY,
                    tileMap.tileWorldSizeX, tileMap.tileWorldSizeY, terrain)
                val command = engine.commandController.createCommand()
                command.team = unit.team; command.sourceTeam = unit.team; command.createdTick = engine.gameTimeMillis
                command.addUnitToCommand(unit); command.setMoveTarget(target.x, target.y)
                check(command.prepareAndValidateCommand()) { "Native movement command was rejected" }
                engine.commandController.pendingCommands.add(command)
            }
        }
        var projector: EngineTimelineProjector? = null
        var publisher: TimelinePublisher? = null
        if (options.projection != "none") m.phase("initialize-one-seat-projection") {
            val actions = StockActionClosure.capture(engine, stock)
            projector = EngineTimelineProjector(engine, teams.first(), map, stock, actions) { EngineMatchStatus.current(teams.map { it.teamId }) }
            val initial = projector!!.observe()
            m.recordDiagnostics.observe(initial.units.size)
            check(initial.units.count { it.ownerTeam == 0 } >= 2) { "Initial authorized seat has no playable units" }
            if (options.projection == "publisher") {
                val record = EngineTimelineProjector.mapRecord(engine, map)
                publisher = TimelinePublisher(TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }),
                    TimelineCanonical.mapKey(record), record, 0, 60))
                publisher!!.observe(initial)
                publisher!!.publish(0)?.let { state ->
                    m.recordDiagnostics.published(state, engine.currentTick)
                    m.recordDiagnostics.capture(state, engine.currentTick)
                }
            }
        }
        try {
            m.startTick.set(engine.currentTick)
            m.stage.set("simulate")
            repeat(options.ticks) { index ->
                val tick = engine.currentTick
                m.measure("engine", index) { AuthoritativeDesktopMain.advance(engine) }
                check(engine.currentTick == tick + 1) { "Engine advance did not perform exactly one real tick" }
                projector?.let { projection ->
                    val observation = m.measure("projection", index) { projection.observe() }
                    m.recordDiagnostics.observe(observation.units.size)
                    check(observation.units.any { it.ownerTeam == 0 }) { "Seat became terminal or lost its active world projection" }
                    publisher?.let { p ->
                        m.measure("publisher-observe", index) { p.observe(observation) }
                        if ((index + 1) % 6 == 0) {
                            m.measure("publisher-publish", index) { p.publish((index + 1L) * SECOND / 60) }
                                ?.let { state -> m.recordDiagnostics.published(state, engine.currentTick) }
                        }
                        if ((index + 1) % 60 == 0) p.latest?.let { state ->
                            m.recordDiagnostics.capture(state, engine.currentTick)
                        }
                        if ((index + 1) % 60 == 0) p.latest?.let { state -> m.measure("full-canonical-hash", index) {
                            m.lastDigest.set(TimelineCanonical.digest(state).toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
                        } }
                    }
                }
                m.measure("world-count-verification", index) {
                    m.actual.set(livingUnits())
                    check(m.actual.get() == options.units) { "Real living unit count changed at tick ${engine.currentTick}" }
                }
                m.lastTick.set(engine.currentTick); m.completed.incrementAndGet(); m.sampleHeap()
                if ((index + 1) % 30 == 0) println("TimelineScaleAcceptance progress ticks=${index + 1}/${options.ticks} units=${m.actual.get()}")
            }
            m.phase("verify-real-movement") {
                val moved = IntArray(options.seats)
                val owned = IntArray(options.seats)
                tanks.forEachIndexed { i, unit ->
                    owned[unit.team.teamId]++
                    if (hypot((unit.posX - initialX[i]).toDouble(), (unit.posY - initialY[i]).toDouble()) > .25) moved[unit.team.teamId]++
                }
                m.movement.set(buildJsonArray { teams.forEach { team -> add(buildJsonObject {
                    put("team", team.teamId); put("commandCenters", 1); put("movableTanks", owned[team.teamId]); put("movedTanks", moved[team.teamId])
                }) } })
                check(moved.all { it > 0 }) { "At least one seat's native move commands produced no actual displacement" }
            }
            publisher?.latest?.let { state -> m.phase("final-canonical-measure") {
                m.persistentBytes.set(TimelineCanonical.persistentSize(state))
                m.lastDigest.set(TimelineCanonical.digest(state).toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
            } }
            m.stage.set("complete")
        } finally {
            // A failed publish leaves p.latest at the preceding committed library. Count
            // that library, never a partly rebuilt candidate. The watchdog can only report
            // the last completed count if the owner itself is stalled during this scan.
            publisher?.latest?.let { state -> m.recordDiagnostics.capture(state, engine.currentTick, replaceSameSecond = true) }
        }
    }

    private fun livingUnits() = GameObject.fastGameObjectList.count { it is BaseUnit && !it.isDead && !it.isDestroyed && it.currentHealth > 0f }
    internal fun movementTarget(x: Float, y: Float, width: Int, height: Int, tileWidth: Int, tileHeight: Int, terrain: ByteArray): Point {
        for (distance in intArrayOf(80, 40, 20)) for ((dx, dy) in arrayOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) {
            val target = Point(x + dx * distance, y + dy * distance)
            if (target.x < 0 || target.y < 0 || target.x >= width * tileWidth || target.y >= height * tileHeight) continue
            var clear = true
            for (step in 1..8) {
                val tx = ((x + (target.x - x) * step / 8) / tileWidth).toInt()
                val ty = ((y + (target.y - y) * step / 8) / tileHeight).toInt()
                if (tx !in 0 until width || ty !in 0 until height || terrain[ty * width + tx].toInt() != 0) { clear = false; break }
            }
            if (clear) return target
        }
        error("Static placement has no nearby walkable move target")
    }

    /** Public-terrain-only layout, with no overlapping unit-coordinate reuse when capacity is insufficient. */
    internal fun plan(width: Int, height: Int, tileWidth: Int, tileHeight: Int, terrain: ByteArray,
                      seats: Int, units: Int, tankRadius: Float, baseRadius: Float): Layout {
        require(width in 1..1024 && height in 1..1024 && tileWidth in 1..4096 && tileHeight in 1..4096)
        require(terrain.size == width * height && terrain.all { it.toInt() in 0..15 })
        require(seats in 2..64 && units in seats * 2..65_536 && tankRadius.isFinite() && baseRadius.isFinite() && tankRadius > 0 && baseRadius > 0)
        val prefix = IntArray((width + 1) * (height + 1))
        for (y in 0 until height) for (x in 0 until width) {
            val at = (y + 1) * (width + 1) + x + 1
            prefix[at] = (if (terrain[y * width + x].toInt() == 0) 0 else 1) + prefix[at - 1] + prefix[at - width - 1] - prefix[at - width - 2]
        }
        fun clear(p: Point, radius: Float): Boolean {
            if (p.x - radius < 0 || p.y - radius < 0) return false
            val x0 = ((p.x - radius) / tileWidth).toInt(); val x1 = ((p.x + radius) / tileWidth).toInt() + 1
            val y0 = ((p.y - radius) / tileHeight).toInt(); val y1 = ((p.y + radius) / tileHeight).toInt() + 1
            if (x1 > width || y1 > height) return false
            return prefix[y1 * (width + 1) + x1] - prefix[y0 * (width + 1) + x1] - prefix[y1 * (width + 1) + x0] + prefix[y0 * (width + 1) + x0] == 0
        }
        val baseGap = ceil(baseRadius * 2 + 2 * maxOf(tileWidth, tileHeight)).toInt()
        val candidates = ArrayList<Point>()
        for (y in baseGap / 2 until height * tileHeight step baseGap) for (x in baseGap / 2 until width * tileWidth step baseGap)
            Point(x.toFloat(), y.toFloat()).takeIf { clear(it, baseRadius + maxOf(tileWidth, tileHeight)) }?.let(candidates::add)
        check(candidates.size >= seats) { "placementCapacity: only ${candidates.size} nonoverlapping command-center patches for $seats seats" }
        val bases = ArrayList<Point>(seats)
        val distances = DoubleArray(candidates.size) { Double.POSITIVE_INFINITY }
        repeat(seats) {
            val index = distances.indices.maxBy { distances[it] }
            val chosen = candidates[index]; bases.add(chosen); distances[index] = -1.0
            for (i in candidates.indices) if (distances[i] >= 0) {
                val p = candidates[i]; val dx = p.x.toDouble() - chosen.x; val dy = p.y.toDouble() - chosen.y
                distances[i] = minOf(distances[i], dx * dx + dy * dy)
            }
        }
        val spacing = ceil(tankRadius * 2 + 4).toInt()
        val slots = ArrayList<Point>()
        val exclusion = baseRadius + tankRadius + maxOf(tileWidth, tileHeight)
        for (y in spacing / 2 until height * tileHeight step spacing) for (x in spacing / 2 until width * tileWidth step spacing) {
            val point = Point(x.toFloat(), y.toFloat())
            if (clear(point, tankRadius + 1) && bases.none { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) < exclusion }) slots.add(point)
        }
        val needed = units - seats
        check(slots.size >= needed) { "placementCapacity: only ${slots.size} nonoverlapping stock tank slots for $needed tanks" }
        return Layout(bases, List(needed) { slots[it.toLong().times(slots.size).div(needed).toInt()] }, slots.size, spacing)
    }

    /** Test-harness counters only. Stored reports contain numbers/JSON, never library references. */
    internal class PublisherRecordDiagnostics {
        private val initialDisclosed = AtomicInteger(-1)
        private val lastDisclosed = AtomicInteger(-1)
        private val peakDisclosed = AtomicInteger()
        private val latestPublication = AtomicReference<JsonObject?>(null)
        private val latestCount = AtomicReference<JsonObject?>(null)
        private val scanNanos = AtomicLong()
        private val peakRecords = AtomicLong()
        private val peakPayloadBytes = AtomicLong()
        private val lock = Any()
        private val samples = LinkedHashMap<Long, JsonObject>()

        fun observe(count: Int) {
            require(count >= 0)
            initialDisclosed.compareAndSet(-1, count)
            lastDisclosed.set(count)
            peakDisclosed.accumulateAndGet(count) { a, b -> maxOf(a, b) }
        }

        fun published(state: TimelineState, nativeTick: Int) {
            latestPublication.set(buildJsonObject {
                put("nativeTick", nativeTick); put("epoch", state.header.epoch)
                put("revision", state.header.revision); put("observedThrough", state.header.observedThrough)
            })
        }

        fun capture(state: TimelineState, nativeTick: Int, replaceSameSecond: Boolean = false) {
            val second = state.header.observedThrough / (state.context.ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS)
            if (!replaceSameSecond && synchronized(lock) { samples.containsKey(second) }) return
            val started = System.nanoTime()
            data class Count(val kind: TimelineObjectKind, val attributeId: Int,
                             var curves: Long = 0, var segments: Long = 0, var predicted: Long = 0,
                             var events: Long = 0, var payload: Long = 0)
            val byKind = Array(TimelineObjectKind.entries.size) { sortedMapOf<Int, Count>() }
            var activeUnits = 0L
            var retainedUnits = 0L
            for (obj in state.objects.values) {
                if (obj.kind == TimelineObjectKind.UNIT) {
                    retainedUnits++
                    val close = obj.close
                    if (close == null || close.until > state.header.observedThrough) activeUnits++
                }
                val byAttribute = byKind[obj.kind.ordinal]
                for (curve in obj.curves.values) {
                    val count = byAttribute.getOrPut(curve.attributeId) { Count(obj.kind, curve.attributeId) }
                    count.curves++; count.segments += curve.segments.size
                    count.events += curve.events.size
                    for (segment in curve.segments) {
                        if (segment.predicted) count.predicted++
                        count.payload += segmentPayloadBytes(segment)
                    }
                    for (event in curve.events) count.payload += unsignedBytes(event.time) + unsignedBytes(event.eventId) + valuePayloadBytes(event.value)
                }
            }
            val counts = byKind.flatMap { it.values }
            val records = counts.sumOf { it.segments + it.events }
            val payload = counts.sumOf { it.payload }
            peakRecords.accumulateAndGet(records) { a, b -> maxOf(a, b) }
            peakPayloadBytes.accumulateAndGet(payload) { a, b -> maxOf(a, b) }
            val publication = latestPublication.get()
            val value = buildJsonObject {
                put("simulationSecond", second); put("countedAtNativeTick", nativeTick)
                if (publication != null && publication["revision"]?.jsonPrimitive?.long == state.header.revision &&
                    publication["epoch"]?.jsonPrimitive?.long == state.header.epoch)
                    put("publicationNativeTick", publication.getValue("nativeTick"))
                put("epoch", state.header.epoch); put("revision", state.header.revision)
                put("observedThrough", state.header.observedThrough); put("sealedThrough", state.header.sealedThrough)
                put("retainedFrom", state.header.retainedFrom); put("retainedUntil", state.header.retainedUntil)
                put("activeDisclosedUnits", activeUnits); put("retainedUnitObservations", retainedUnits)
                put("objects", state.objects.size); put("curves", counts.sumOf { it.curves })
                put("segments", counts.sumOf { it.segments }); put("predictedSegments", counts.sumOf { it.predicted })
                put("events", counts.sumOf { it.events }); put("curveRecords", records)
                put("recordPayloadBytesEstimated", payload)
                put("attributes", buildJsonObject { counts.forEach { count ->
                    put("${count.kind.name.lowercase()}.${count.attributeId}", buildJsonObject {
                    put("curves", count.curves); put("segments", count.segments); put("predictedSegments", count.predicted)
                    put("events", count.events); put("recordPayloadBytesEstimated", count.payload)
                }) } })
            }
            synchronized(lock) {
                // CLI caps the run at 10,000 ticks (167 distinct seconds including zero).
                check(second in samples || samples.size < 168) { "Bounded record trace exceeded" }
                samples[second] = value
            }
            latestCount.set(value)
            scanNanos.addAndGet(System.nanoTime() - started)
        }

        fun report(): JsonObject = buildJsonObject {
            put("scope", "One authorized seat; latest successfully committed publisher libraries only. Counts are exact; payload byte lengths are a model-based estimate, not heap, wire traffic or complete persistent size.")
            put("byteEstimateExcludes", "Object and curve declarations, descriptors, metadata, headers, map, transactions and framing; includes every segment/event value and its record fields.")
            put("unitAttributeNames", buildJsonObject {
                put("1", "position"); put("2", "heading"); put("3", "health"); put("4", "maxHealth")
                put("5", "buildProgress"); put("6", "ownerTeam"); put("7", "appearance")
                put("8", "upgradeLevel"); put("9", "controllable")
            })
            if (initialDisclosed.get() >= 0) {
                put("initialDisclosedUnits", initialDisclosed.get()); put("lastDisclosedUnits", lastDisclosed.get())
                put("peakDisclosedUnits", peakDisclosed.get())
            }
            put("sampling", "At most one report per simulated second, including initial; final/ordinary failure replaces that second. Hard timeout may expose an older completed count, whose publication and count ticks remain explicit.")
            put("diagnosticScanNanos", scanNanos.get())
            put("diagnosticTimingScope", "Extra diagnostic work is excluded from existing measured owner phase arrays, but included in overall wall deadline and heap samples.")
            put("peakCountedCurveRecords", peakRecords.get()); put("peakEstimatedRecordPayloadBytes", peakPayloadBytes.get())
            latestPublication.get()?.let { put("lastSuccessfulPublication", it) }
            latestCount.get()?.let { put("lastCountedSuccessfulLibrary", it) }
            put("perSimulationSecond", synchronized(lock) { buildJsonArray { samples.values.forEach(::add) } })
        }

        private fun unsignedBytes(value: Long): Long {
            require(value >= 0)
            var n = value; var bytes = 1L
            while (n >= 128) { bytes++; n = n ushr 7 }
            return bytes
        }
        private fun signedBytes(value: Long) = unsignedBytes((value shl 1) xor (value shr 63))
        private fun valuePayloadBytes(value: TimelineValue): Long = when (value) {
            is TimelineValue.SInt -> signedBytes(value.value)
            is TimelineValue.UInt -> unsignedBytes(value.value)
            is TimelineValue.Vec3 -> signedBytes(value.x) + signedBytes(value.y) + signedBytes(value.z)
            is TimelineValue.Bool -> 1
            is TimelineValue.Blob -> unsignedBytes(value.value.size.toLong()) + value.value.size
        }
        private fun segmentPayloadBytes(segment: TimelineSegment): Long =
            unsignedBytes(segment.lo) + unsignedBytes(segment.hi) + 1 + when (segment) {
                is TimelineSegment.Step -> valuePayloadBytes(segment.value)
                is TimelineSegment.Interpolated -> unsignedBytes(segment.anchor0Time) + unsignedBytes(segment.anchor1Time) +
                    valuePayloadBytes(segment.value0) + valuePayloadBytes(segment.value1)
            }
    }

    private class Metrics(val options: Options) {
        val started = System.nanoTime()
        val stage = AtomicReference("starting")
        val completed = AtomicInteger(); val created = AtomicInteger(); val actual = AtomicInteger()
        val startTick = AtomicInteger(); val lastTick = AtomicInteger()
        val peakHeap = AtomicLong(); val persistentBytes = AtomicLong()
        val lastDigest = AtomicReference<String?>(null)
        val recordDiagnostics = PublisherRecordDiagnostics()
        val details = AtomicReference<JsonObject>(buildJsonObject {})
        val movement = AtomicReference<JsonArray>(buildJsonArray {})
        private val thread = ManagementFactory.getThreadMXBean().also { if (it.isThreadCpuTimeSupported) it.isThreadCpuTimeEnabled = true }
        private val allocations = (thread as? com.sun.management.ThreadMXBean)?.takeIf { it.isThreadAllocatedMemorySupported }
            ?.also { it.isThreadAllocatedMemoryEnabled = true }
        private val ownerId = Thread.currentThread().id
        private val os = ManagementFactory.getOperatingSystemMXBean()
        private val process = os as? com.sun.management.OperatingSystemMXBean
        private val processCpuStart = process?.processCpuTime
        private val phases = ConcurrentHashMap<String, Phase>()
        private val names = listOf("engine", "projection", "publisher-observe", "publisher-publish", "full-canonical-hash", "world-count-verification")
        private val elapsed = names.associateWith { LongArray(options.ticks) }
        private val cpu = names.associateWith { LongArray(options.ticks) }
        private val allocated = names.associateWith { LongArray(options.ticks) }
        private fun ownerCpu() = if (thread.isThreadCpuTimeSupported) thread.getThreadCpuTime(ownerId).takeIf { it >= 0 } else null
        private fun ownerAllocated() = allocations?.getThreadAllocatedBytes(ownerId)?.takeIf { it >= 0 }
        fun <T> phase(name: String, operation: () -> T): T {
            stage.set(name)
            val start = System.nanoTime(); val beforeCpu = ownerCpu(); val beforeAllocation = ownerAllocated()
            try { return operation() } finally {
                phases[name] = Phase(System.nanoTime() - start, beforeCpu?.let { ownerCpu()?.minus(it) }, beforeAllocation?.let { ownerAllocated()?.minus(it) })
                sampleHeap()
            }
        }
        fun <T> measure(name: String, index: Int, operation: () -> T): T {
            val start = System.nanoTime(); val beforeCpu = ownerCpu(); val beforeAllocation = ownerAllocated()
            try { return operation() } finally {
                elapsed.getValue(name)[index] = System.nanoTime() - start
                cpu.getValue(name)[index] = beforeCpu?.let { ownerCpu()?.minus(it) } ?: -1
                allocated.getValue(name)[index] = beforeAllocation?.let { ownerAllocated()?.minus(it) } ?: -1
            }
        }
        fun sampleHeap() { val used = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used; peakHeap.accumulateAndGet(used) { a, b -> maxOf(a, b) } }
        fun report(error: String?): JsonObject {
            val count = completed.get()
            val frame = LongArray(count) { i -> names.filterNot { it == "world-count-verification" }.sumOf { elapsed.getValue(it)[i] } }
            val sorted = frame.sorted()
            val realtime = count == options.ticks && sorted.isNotEmpty() && percentile(sorted, .95) <= BUDGET
            return buildJsonObject {
                put("passed", error == null && realtime); put("completed", error == null && count == options.ticks)
                put("engineOnly", options.projection == "none"); put("fullProtocolCapacityValidated", false)
                put("scope", "Real stock units in a headless engine-only moving fixture; optional one-seat projection/publisher is measured separately. No socket, multi-client, combat, GUI or Internet acceptance.")
                put("applicationVersion", AppMetadata.VERSION_NAME); put("protocolVersion", TimelineProtocol.VERSION)
                put("requestedUnits", options.units); put("seats", options.seats); put("requestedTicks", options.ticks)
                put("requestedMap", options.mapPath)
                put("completedTicks", count); put("createdUnits", created.get()); put("actualLivingUnits", actual.get())
                put("startTick", startTick.get()); put("lastTick", lastTick.get()); put("lastStage", stage.get())
                put("wallSeconds", (System.nanoTime() - started) / 1e9); put("deadlineSeconds", options.deadlineSeconds); put("hardHaltGraceSeconds", 2)
                put("projectionMode", options.projection); put("measuredOwnerP95Within60HzBudget", realtime)
                put("ownerBudgetMillis", BUDGET / 1e6)
                if (frame.isNotEmpty()) {
                    put("measuredOwnerP50Ms", percentile(sorted, .50) / 1e6)
                    put("measuredOwnerP95Ms", percentile(sorted, .95) / 1e6)
                    put("measuredOwnerMaxMs", sorted.last() / 1e6)
                    put("ownerStepsPerMeasuredSecond", count * 1e9 / frame.sum())
                }
                put("heapPeakSampledBytes", peakHeap.get()); put("maxHeapBytes", Runtime.getRuntime().maxMemory())
                process?.let { put("physicalMemoryBytes", it.totalMemorySize); put("processCpuNanos", it.processCpuTime - (processCpuStart ?: 0)) }
                put("osName", os.name); put("osArch", os.arch); put("osVersion", os.version); put("availableProcessors", os.availableProcessors)
                put("javaVersion", System.getProperty("java.version")); put("javaVendor", System.getProperty("java.vendor")); put("javaVm", System.getProperty("java.vm.name"))
                put("allocationScope", "Measured owner thread only; process CPU also includes native engine worker threads")
                put("details", details.get()); put("movement", movement.get()); put("persistentBytes", persistentBytes.get())
                put("publisherRecordDiagnostics", recordDiagnostics.report())
                lastDigest.get()?.let { put("lastFullCanonicalDigest", it) }
                error?.let { put("error", it) }
                put("phases", buildJsonObject { phases.toSortedMap().forEach { (name, p) -> put(name, buildJsonObject {
                    put("elapsedMs", p.elapsed / 1e6); p.cpu?.let { put("ownerCpuMs", it / 1e6) }; p.allocated?.let { put("ownerAllocatedBytes", it) }
                }) } })
                put("perTick", buildJsonObject { names.forEach { name ->
                    val values = elapsed.getValue(name).take(count).filter { it > 0 }.sorted()
                    put(name, buildJsonObject {
                        put("samples", values.size); put("totalMs", values.sum() / 1e6)
                        if (values.isNotEmpty()) { put("p50Ms", percentile(values, .50) / 1e6); put("p95Ms", percentile(values, .95) / 1e6); put("maxMs", values.last() / 1e6) }
                        if (thread.isThreadCpuTimeSupported) put("ownerCpuMs", cpu.getValue(name).take(count).filter { it >= 0 }.sum() / 1e6)
                        if (allocations != null) put("ownerAllocatedBytes", allocated.getValue(name).take(count).filter { it >= 0 }.sum())
                    })
                } })
            }
        }
    }

    private fun percentile(sorted: List<Long>, fraction: Double) = sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]
    private fun waitUntil(deadline: Long) { while (System.nanoTime() < deadline) LockSupport.parkNanos(deadline - System.nanoTime()) }
}
