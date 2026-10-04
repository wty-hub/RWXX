package io.github.rwx.benchmark

import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitMovementType
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs

/** Opt-in local benchmark fixture. Production sessions and network games never inject units. */
object VanillaBattleBenchmark {
    enum class Mode { Combat, Idle, Moving }
    enum class Mix(val argument: String) { LandAir("land-air"), SeaAir("sea-air"), All("all");
        fun accepts(movement: UnitMovementType): Boolean = when (this) {
            LandAir -> movement != UnitMovementType.WATER && movement != UnitMovementType.NONE && movement != UnitMovementType.BUILDING
            SeaAir -> movement == UnitMovementType.WATER || movement == UnitMovementType.AIR || movement == UnitMovementType.HOVER || movement == UnitMovementType.OVER_CLIFF_WATER
            All -> movement != UnitMovementType.NONE && movement != UnitMovementType.BUILDING
        }
    }

    data class Options(val units: Int, val mix: Mix, val selected: Boolean, val output: File,
        val warmupSeconds: Int = 30, val sampleSeconds: Int = 30, val repetitions: Int = 3,
        val mode: Mode = Mode.Combat, val teams: Int = 2, val recordReplay: String? = null,
        val palette: Set<String>? = null, val fogEnabled: Boolean? = null)

    private val options: Options? by lazy { options(System.getenv()) }
    private var initializedMap: Any? = null
    private var startedAt = 0L
    private var previousFrameAt = 0L
    private var sampleStart = 0L
    private var repetition = 0
    private val frameIntervals = mutableListOf<Long>()
    private var minimumUnits = Int.MAX_VALUE
    private var minimumVisible = Int.MAX_VALUE
    private var projectileFrames = 0
    private var lastAttackTick = -1
    private var recordingStopped = false

    fun options(environment: Map<String, String>): Options? {
        val raw = environment["RWX_BENCHMARK_UNITS"]?.takeIf(String::isNotBlank) ?: return null
        val count = raw.toIntOrNull()
        require(count in listOf(500, 661, 1000, 2000)) { "RWX_BENCHMARK_UNITS must be 500, 661, 1000 or 2000" }
        val mixArgument = environment["RWX_BENCHMARK_MIX"] ?: "all"
        val mix = Mix.entries.firstOrNull { it.argument == mixArgument }
            ?: error("RWX_BENCHMARK_MIX must be land-air, sea-air or all")
        val mode = when (environment["RWX_BENCHMARK_MODE"] ?: "combat") {
            "combat" -> Mode.Combat
            "idle" -> Mode.Idle
            "moving" -> Mode.Moving
            else -> error("RWX_BENCHMARK_MODE must be combat, idle or moving")
        }
        val teams = environment["RWX_BENCHMARK_TEAMS"]?.toIntOrNull() ?: 2
        require(teams in 2..15) { "RWX_BENCHMARK_TEAMS must be 2..15" }
        val fog = when (environment["RWX_BENCHMARK_FOG"]) {
            null -> null
            "off" -> false
            "on" -> true
            else -> error("RWX_BENCHMARK_FOG must be off or on")
        }
        val record = environment["RWX_BENCHMARK_RECORD_REPLAY"]?.takeIf { it.isNotBlank() }
        require(record == null || (record.endsWith(".replay") && '/' !in record && '\\' !in record)) {
            "Benchmark recording must be a replay file name"
        }
        return Options(count!!, mix, environment["RWX_BENCHMARK_SELECTED"] == "1",
            File(environment["RWX_BENCHMARK_OUTPUT"] ?: "build/rwx-benchmark/${count}-${mix.argument}-${mode.name.lowercase()}.ndjson"),
            mode = mode, teams = teams, recordReplay = record,
            palette = environment["RWX_BENCHMARK_PALETTE"]?.split(',')?.map { it.trim() }?.toSet(), fogEnabled = fog)
    }

    /** Invoke on the unique engine owner, immediately before its normal gameLoop. */
    fun onFrame(engine: GameEngine) {
        val options = options ?: return
        if (!engine.hasLoadedLevel || engine.tileMap == null) return
        check(!engine.networkEngine.networkGameActive && !engine.replayEngine.j()) {
            "Benchmark injection is only allowed in a local map, never multiplayer or a replay"
        }
        if (initializedMap !== engine.tileMap) {
            setup(engine, options)
            initializedMap = engine.tileMap
            startedAt = System.nanoTime()
            previousFrameAt = startedAt
            repetition = 0
            sampleStart = 0L
            resetWindow()
        }
        // Commands use the normal controller; surviving units receive ordinary attack-move orders.
        if (options.mode == Mode.Combat && engine.currentTick >= lastAttackTick + 300) {
            issueAttackMoves(engine)
            lastAttackTick = engine.currentTick
        }
        if (options.mode == Mode.Moving && engine.currentTick >= lastAttackTick + 300) {
            issueMoves(engine)
            lastAttackTick = engine.currentTick
        }
        val now = System.nanoTime()
        if (options.recordReplay != null && !recordingStopped && now - startedAt >= 110 * SECOND) {
            engine.replayEngine.e()
            recordingStopped = true
            append(options.output, buildJsonObject {
                put("kind", "recording-finished"); put("gameTimeMillis", engine.gameTimeMillis)
                put("tick", engine.currentTick); put("path", engine.replayEngine.a(options.recordReplay, false).absolutePath)
            })
        }
        val interval = now - previousFrameAt
        previousFrameAt = now
        if (now - startedAt < options.warmupSeconds * SECOND || repetition >= options.repetitions) return
        if (sampleStart == 0L) sampleStart = now
        val units = GameObject.fastGameObjectList.filterIsInstance<BaseUnit>().filter { !it.isDead && !it.isDestroyed }
        frameIntervals += interval
        minimumUnits = minOf(minimumUnits, units.size)
        minimumVisible = minOf(minimumVisible, units.count { it.shouldDraw })
        if (GameObject.fastGameObjectList.any { it is com.corrodinggames.rts.game.Projectile }) projectileFrames++
        if (now - sampleStart >= options.sampleSeconds * SECOND) {
            val sorted = frameIntervals.sorted()
            val average = frameIntervals.average()
            append(options.output, buildJsonObject {
                put("kind", "engine-window"); put("repetition", ++repetition)
                put("sampleStartNanos", sampleStart); put("sampleEndNanos", now)
                put("requestedUnits", options.units); put("minimumLivingUnits", minimumUnits)
                put("mode", options.mode.name.lowercase())
                put("minimumVisibleUnits", minimumVisible); put("framesWithProjectiles", projectileFrames)
                put("freshEngineFrames", frameIntervals.size); put("engineFps", SECOND / average)
                put("engineIntervalP95Ms", percentile(sorted, .95) / 1e6)
                put("engineIntervalP99Ms", percentile(sorted, .99) / 1e6)
                put("eligible2000CombatWindow", minimumUnits >= 2000 && projectileFrames == frameIntervals.size)
                put("eligible2000IdleWindow", options.mode == Mode.Idle && minimumUnits >= 2000)
                put("presentation144FpsStatus", "requires renderer timings; engine rates alone are insufficient")
                put("tick", engine.currentTick)
            })
            sampleStart = now
            resetWindow()
        }
    }

    fun setup(engine: GameEngine, options: Options) {
        check(!engine.networkEngine.networkGameActive && !engine.replayEngine.j())
        check(CustomUnitConfig.activeConfigs.none { it.modInfo != null }) { "Disable external unit mods for a vanilla benchmark" }
        val entries = VanillaUnitCatalog.entries()
        val palette = entries.filter { it.active && !it.internal && !it.building &&
            (options.palette == null || it.sourceId in options.palette || it.effectiveId in options.palette) }
            .distinctBy { it.effectiveId }.mapNotNull { entry ->
                val prototype = BaseUnit.bG[entry.effectiveType] as? BaseUnit ?: return@mapNotNull null
                entry.takeIf { prototype is OrderableUnit && prototype.moveSpeed > 0f && options.mix.accepts(prototype.movementType) }
            }
        check(palette.isNotEmpty()) { "No vanilla movable types match this scenario" }
        // Validate terrain before changing the map, so unsuitable scenarios fail cleanly.
        val tileMap = engine.tileMap
        val centerX = tileMap.worldWidth / 2f
        val centerY = tileMap.worldHeight / 2f
        val points = palette.map { BaseUnit.bG[it.effectiveType] as BaseUnit }.map { it.movementType }.distinct()
            .associateWith { movement ->
                val result = buildList {
                    for (y in 2 until tileMap.tileCountY - 2) for (x in 2 until tileMap.tileCountX - 2) {
                        if (!engine.pathfindingEngine.isTileBlockedForMovement(movement, x, y)) {
                            add(Position((x + .5f) * tileMap.tileWorldSizeX, (y + .5f) * tileMap.tileWorldSizeY))
                        }
                    }
                }.sortedWith(compareBy<Position> { abs(it.x - centerX) + abs(it.y - centerY) }.thenBy { it.y }.thenBy { it.x })
                check(result.isNotEmpty()) { "Map has no valid terrain for $movement" }
                result
            }
        for (unit in GameObject.fastGameObjectList.filterIsInstance<BaseUnit>().toList()) unit.removeFromGame()
        GameObject.dL()
        options.fogEnabled?.let { enabled ->
            tileMap.fogEnabled = enabled
            tileMap.fogPeriodicMaintenanceEnabled = enabled
            engine.networkEngine.roomSettings.fogMode = if (enabled) 2 else 0
            tileMap.invalidateFogDisplay()
        }
        val teams = initializeScriptedTeams(engine, options.teams, options.mode != Mode.Combat)
        engine.playerTeam = teams.first()
        engine.settingsEngine.highRefreshRate = true // Existing outer throttle option; no simulation-step change.
        engine.settingsEngine.batterySaving = false
        val represented = linkedMapOf<String, Int>()
        val nextPosition = mutableMapOf<UnitMovementType, Int>()
        repeat(options.units) { index ->
            val entry = palette[index % palette.size]
            val unit = entry.effectiveType.a()
            val movement = unit.movementType
            val pointIndex = nextPosition[movement] ?: 0
            val candidates = checkNotNull(points[movement])
            val position = candidates[pointIndex % candidates.size]
            nextPosition[movement] = pointIndex + 1
            unit.posX = position.x; unit.posY = position.y
            // Alternate each complete palette, so every type appears in both factions.
            val teamIndex = when (options.mode) {
                Mode.Idle -> 0
                Mode.Moving -> index % teams.size
                Mode.Combat -> (index / palette.size) % teams.size
            }
            unit.h(if (teamIndex == 0) 0f else 180f)
            unit.setUnitTeam(teams[teamIndex])
            unit.isActive = true
            unit.n()
            PlayerTeam.c(unit)
            engine.unitSpatialIndex.a(unit)
            if (options.selected && teamIndex == 0) engine.gameUI.selectUnit(unit)
            represented[entry.effectiveId] = (represented[entry.effectiveId] ?: 0) + 1
        }
        GameObject.dL()
        engine.setViewpoint(centerX - engine.halfVisibleWorldWidth, centerY - engine.halfVisibleWorldHeight)
        lastAttackTick = -300
        options.recordReplay?.let { engine.replayEngine.d(it) }
        if (options.mode == Mode.Combat) issueAttackMoves(engine)
        if (options.mode == Mode.Moving) issueMoves(engine)
        lastAttackTick = engine.currentTick
        options.output.parentFile?.mkdirs()
        options.output.writeText("")
        append(options.output, buildJsonObject {
            put("kind", "scenario"); put("requestedUnits", options.units); put("mix", options.mix.argument)
            put("mode", options.mode.name.lowercase())
            put("teams", options.teams); put("recordReplay", options.recordReplay)
            put("fogEnabled", tileMap.fogEnabled)
            put("fogPeriodicMaintenanceEnabled", tileMap.fogPeriodicMaintenanceEnabled)
            put("selected", options.selected); put("map", engine.currentMapPath)
            put("viewportWidth", engine.screenWidth); put("viewportHeight", engine.screenHeight)
            put("catalog", VanillaUnitCatalog.report())
            put("initialRepresentedTypes", JsonObject(represented.mapValues { JsonPrimitive(it.value) }))
            put("unitRules", "normal constructors, damage, collision and command controller; no invulnerability or replenishment")
            put("teamRules", if (options.mode == Mode.Combat) "two scripted human factions; normal map fog initialization"
                else if (options.mode == Mode.Moving) "one friendly faction; periodic ordinary movement orders; normal simulation and fog"
                else "one friendly faction; no issued orders; normal simulation and map fog initialization")
        })
    }

    internal fun initializeScriptedTeams(engine: GameEngine, count: Int = 2, allied: Boolean = false): List<GameTeam> {
        // Empty original AI slots still create random strategic zones. Scripted fixtures remove
        // those slots normally; production map loading and AI random sources remain unchanged.
        for (id in 0 until PlayerTeam.TEAM_NEUTRAL) PlayerTeam.k(id)?.removeFromTeamRegistry()
        if (count > PlayerTeam.TEAM_NEUTRAL) PlayerTeam.setMaxTeamId(count, false)
        return List(count) { GameTeam(it) }.also { teams ->
            teams.forEachIndexed { index, team ->
                team.teamColorId = if (allied) 0 else index % 2
                team.credits = 100000.0; team.teamName = "Benchmark ${index + 1}"
                val map = engine.tileMap
                if (map.fogEnabled) {
                    team.fogOfWarWidth = map.tileCountX; team.fogOfWarHeight = map.tileCountY
                    team.fogOfWarData = Array(map.tileCountX) { ByteArray(map.tileCountY) { 10 } }
                }
            }
        }
    }

    private fun issueAttackMoves(engine: GameEngine) {
        val living = GameObject.fastGameObjectList.filterIsInstance<OrderableUnit>().filter { !it.isDead && !it.isDestroyed }
        for (teamId in 0 until PlayerTeam.TEAM_NEUTRAL) {
            val team = PlayerTeam.k(teamId) ?: continue
            val own = living.filter { it.team === team }
            val enemy = living.firstOrNull { it.team != null && team.c(it.team) } ?: continue
            if (own.isNotEmpty()) engine.commandController.createCommandForTeam(team).apply {
                own.forEach(::addUnitToCommand)
                setAttackMoveTarget(enemy.posX, enemy.posY)
            }
        }
    }

    private fun issueMoves(engine: GameEngine) {
        val direction = if ((engine.currentTick / 300) % 2 == 0) 1f else -1f
        val map = engine.tileMap
        for (unit in BaseUnit.getGlobalUnitList()) {
            if (unit !is OrderableUnit || unit.isDead || unit.isDestroyed || unit.team == null) continue
            val team = unit.team
            engine.commandController.createCommandForTeam(team).apply {
                addUnitToCommand(unit)
                setMoveTarget((unit.posX + direction * 450f).coerceIn(50f, map.worldWidth - 50f),
                    (unit.posY + direction * 300f).coerceIn(50f, map.worldHeight - 50f))
            }
        }
    }

    private fun append(file: File, objectValue: JsonObject) { file.appendText(objectValue.toString() + "\n") }
    private fun percentile(sorted: List<Long>, p: Double) = sorted[((sorted.size - 1) * p).toInt().coerceIn(sorted.indices)]
    private fun resetWindow() { frameIntervals.clear(); minimumUnits = Int.MAX_VALUE; minimumVisible = Int.MAX_VALUE; projectileFrames = 0 }
    private data class Position(val x: Float, val y: Float)
    private const val SECOND = 1_000_000_000L
}
