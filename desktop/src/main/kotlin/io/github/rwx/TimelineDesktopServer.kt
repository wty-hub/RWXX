package io.github.rwx

import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import io.github.rwx.net.authoritative.EnginePublicMapSnapshot
import io.github.rwx.net.authoritative.StockUnitCatalog
import io.github.rwx.net.timeline.*
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import javax.net.ssl.SSLContext
import kotlin.math.abs
import kotlin.math.min

/** v7 room owner. No engine operation is performed by transport, stdin or shutdown callbacks. */
internal object TimelineDesktopServer {
    const val ARENA_MAP = "maps/skirmish/[p2]RWXX Timeline Arena.tmx"
    const val LARGE_ARENA_MAP = "maps/skirmish/[p2]RWXX Timeline Large Arena.tmx"
    val APPROVED_MAPS: Set<String> = setOf(AuthoritativeDesktopMain.DEFAULT_MAP, ARENA_MAP, LARGE_ARENA_MAP)

    internal fun chooseMap(options: AuthorityOptions, configuredSeats: Int): String {
        require(configuredSeats in 2..64)
        val defaultMap = when (configuredSeats) {
            2 -> AuthoritativeDesktopMain.DEFAULT_MAP
            in 3..16 -> ARENA_MAP
            else -> LARGE_ARENA_MAP
        }
        return (options.values["map"] ?: defaultMap)
            .also { require(it in APPROVED_MAPS) { "Map is not approved for a timeline room" } }
    }
    internal fun initialStarts(map: String): List<TimelineSpawnTile> = when (map) {
        AuthoritativeDesktopMain.DEFAULT_MAP -> listOf(TimelineSpawnTile(49, 83), TimelineSpawnTile(46, 18))
        ARENA_MAP -> listOf(TimelineSpawnTile(8, 8), TimelineSpawnTile(119, 119))
        LARGE_ARENA_MAP -> listOf(TimelineSpawnTile(8, 8), TimelineSpawnTile(503, 503))
        else -> throw IllegalArgumentException("Map is not approved for a timeline room")
    }

    fun run(options: AuthorityOptions, boot: (String) -> GameEngine,
            readSeats: (String) -> Map<String, Int>, tls: () -> Pair<SSLContext, String>?) {
        val credentials = readSeats(options.required("seats"))
        require(credentials.values.containsAll(listOf(0, 1)) && credentials.values.toSet().size == credentials.size &&
            credentials.values.all { it in 0..63 }) { "Seats 0 and 1 are required; optional distinct seats are 2..63" }
        if (System.getProperty("launch.dir") == null)
            System.setProperty("launch.dir", File(File(options.required("seats")).absoluteFile.parentFile, "server-state").path)
        val address = InetAddress.getByName(options.values["bind"] ?: "127.0.0.1")
        val security = tls()
        require(address.isLoopbackAddress || security != null) { "Non-loopback timeline servers require TLS" }
        val tickLimit = options.values["ticks"]?.toInt()?.also { require(it > 0) }
        val archives = File(options.values["archive-dir"] ?: File(System.getProperty("launch.dir"),
            "timeline-archives/${UUID.randomUUID()}").path).absoluteFile.toPath()
        val mapOptions = options.copy(values = options.values + ("map" to chooseMap(options, credentials.size)))
        val engine = boot(mapOptions.map)
        PlayerTeam.setMaxTeamId(64, false)
        check((0..1).all { PlayerTeam.k(it) is GameTeam && !PlayerTeam.k(it).isSpectatorTeamColor }) {
            "Initial seats must be existing human teams"
        }
        val map = engine.tileMap
        val publicMap = EnginePublicMapSnapshot.capture(map)
        val stock = StockUnitCatalog.capture(engine)
        // Initial anchors are declared by the selected approved public map.
        // Runtime placement never reads a unit, visibility, pathfinder occupancy or combat state.
        val spawns = TimelineStaticSpawns.plan(map.tileCountX, map.tileCountY, publicMap.terrainCopy(),
            protectedStarts = initialStarts(mapOptions.map))
        val random = SecureRandom()
        val seats = credentials.mapValues { (_, id) -> TimelineSeat(id, TimelineBytes(ByteArray(16).also(random::nextBytes))) }
        val running = AtomicBoolean(true)
        val ownerStopped = CountDownLatch(1)
        val room = TimelineEngineRoom.withNativeRoster(engine, seats.values, archives,
            prepareTeam = { id -> prepareTeam(engine, id, stock, spawns) })
        var server: TimelineServer? = null
        var shutdown: Thread? = null
        var failure: Throwable? = null
        fun recordFailure(stage: String, cause: Throwable) {
            val error = IOException("Timeline $stage failed", cause)
            System.err.println("${error.message}: ${cause.message}")
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
        try {
            val listener = TimelineServer(address, options.port, seats, room.mapRecord, room.matchId, room,
                serverTime = { room.serverTime }, historyUntil = room::historyUntil,
                tlsContext = security?.first, admissionProvider = room)
            server = listener
            shutdown = Thread({
                running.set(false); listener.close()
                // The owner alone finishes archives and closes the room. Keep JVM shutdown from
                // killing that finally block as soon as this signaling hook returns.
                try {
                    if (!ownerStopped.await(16, TimeUnit.SECONDS))
                        System.err.println("Timeline owner shutdown deadline exceeded; recordings may be incomplete")
                } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }, "timeline-server-shutdown")
            Runtime.getRuntime().addShutdownHook(shutdown)
            if (options.values["owner-stdin"] == "true") observeAuthoritativeOwner(System.`in`) {
                running.set(false); listener.close()
            }
            println("Timeline server listening on ${listener.localPort}; protocol ${TimelineProtocol.VERSION}")
            security?.let { println("Server certificate SHA-256: ${it.second}") }
            println("Timeline archive directory: $archives")
            println("Map: ${mapOptions.map}; ${credentials.size} configured seats, ${spawns.size} static optional spawn patches")
            println("Large-room and 65,536-unit runtime capacity have not been validated")
            var nextStep = System.nanoTime()
            var announcedResult = false
            while (running.get() && (tickLimit == null || engine.currentTick < tickLimit)) {
                room.runStep(listener) { AuthoritativeDesktopMain.advance(engine) }
                if (room.finished && !announcedResult) {
                    announcedResult = true
                    println("Match finished; the listener remains available for existing-grant reconnects and history")
                }
                nextStep += if (room.finished) 100_000_000L else 16_666_667L
                val remaining = nextStep - System.nanoTime()
                if (remaining > 0) LockSupport.parkNanos(remaining)
                else if (remaining < -1_000_000_000L) nextStep = System.nanoTime()
                if (Thread.currentThread().isInterrupted) running.set(false)
            }
        } catch (error: Throwable) { failure = error }
        finally {
            running.set(false)
            val interrupted = Thread.interrupted()
            try {
                try { server?.close() } catch (error: Throwable) { recordFailure("listener shutdown", error) }
                // allOf starts every grant's drain concurrently; this is one room-wide deadline.
                try { room.finishRecordingsAsync().get(10, TimeUnit.SECONDS) }
                catch (error: Throwable) { recordFailure("archive completion", error) }
                finally { try { room.close() } catch (error: Throwable) { recordFailure("room shutdown", error) } }
                try { room.stoppedAsync().get(5, TimeUnit.SECONDS) }
                catch (error: Throwable) { recordFailure("worker shutdown", error) }
            } finally {
                ownerStopped.countDown()
                shutdown?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        failure?.let { throw it }
    }

    private fun prepareTeam(engine: GameEngine, id: Int, stock: StockUnitCatalog,
                            spawns: Map<Int, TimelineSpawnPatch>): TimelinePreparedTeam {
        PlayerTeam.k(id)?.let { existing ->
            check(id in 0..1 && existing is GameTeam && !existing.isSpectatorTeamColor) { "Seat $id is already occupied" }
            return TimelinePreparedTeam(existing) // Existing teams and units are never rolled back.
        }
        require(id in 2..63) { "Initial map seat $id is missing" }
        val spawn = checkNotNull(spawns[id]) { "The public map has no reserved land patch for seat $id" }
        stock.assertValid()
        val commandCenter = checkNotNull(stock.typeFor("commandCenter"))
        val builder = checkNotNull(stock.typeFor("builder"))
        val created = ArrayList<BaseUnit>(2)
        val team = GameTeam(id)
        fun rollback() {
            for (unit in created.asReversed()) unit.removeFromGame()
            GameObject.dL()
            if (PlayerTeam.k(id) === team) team.removeFromTeamRegistry()
            PlayerTeam.g(0f)
        }
        try {
            team.teamName = "Player ${id + 1}"; team.teamColorId = id; team.credits = 4000.0
            team.fogOfWarWidth = engine.tileMap.tileCountX; team.fogOfWarHeight = engine.tileMap.tileCountY
            team.fogOfWarData = Array(team.fogOfWarWidth) { ByteArray(team.fogOfWarHeight) { 10 } }
            fun place(unit: BaseUnit, point: TimelineSpawnTile) {
                created.add(unit)
                unit.posX = (point.x + .5f) * engine.tileMap.tileWorldSizeX
                unit.posY = (point.y + .5f) * engine.tileMap.tileWorldSizeY
                unit.setUnitTeam(team); unit.isActive = true
                unit.n(); PlayerTeam.c(unit); engine.unitSpatialIndex.a(unit)
                if (unit is OrderableUnit) unit.c(false)
            }
            place(commandCenter.a(), spawn.commandCenter)
            place(builder.a(), spawn.builder)
            GameObject.dL(); PlayerTeam.g(0f)
            return TimelinePreparedTeam(team, ::rollback)
        } catch (error: Throwable) {
            try { rollback() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
}

internal data class TimelineSpawnTile(val x: Int, val y: Int)
internal data class TimelineSpawnPatch(val commandCenter: TimelineSpawnTile, val builder: TimelineSpawnTile)

/** Pure row-major public-terrain planner. The same seat always receives the same reserved patch. */
internal object TimelineStaticSpawns {
    const val SPACING = 9
    fun plan(width: Int, height: Int, terrain: ByteArray, protectedStarts: List<TimelineSpawnTile> = emptyList()): Map<Int, TimelineSpawnPatch> {
        require(width > 0 && height > 0 && width.toLong() * height == terrain.size.toLong())
        require(terrain.all { (it.toInt() and 255) <= 15 }) { "Unknown public terrain bits" }
        require(protectedStarts.all { it.x in 0 until width && it.y in 0 until height }) { "Invalid protected start" }
        val candidates = ArrayList<TimelineSpawnTile>()
        for (y in 2 until height - 3) for (x in 2 until width - 5) {
            var land = true
            loop@ for (dy in -2..3) for (dx in -2..5)
                if (terrain[(y + dy) * width + x + dx].toInt() != 0) { land = false; break@loop }
            if (land) candidates.add(TimelineSpawnTile(x, y))
        }
        val prior = ArrayList(protectedStarts)
        val result = linkedMapOf<Int, TimelineSpawnPatch>()
        for (id in 2..63) {
            var chosen: TimelineSpawnTile? = null; var best = -1L
            for (candidate in candidates) {
                if (prior.any { abs(it.x - candidate.x) < SPACING && abs(it.y - candidate.y) < SPACING }) continue
                var distance = Long.MAX_VALUE
                for (anchor in prior) {
                    val dx = candidate.x.toLong() - anchor.x; val dy = candidate.y.toLong() - anchor.y
                    distance = min(distance, dx * dx + dy * dy)
                }
                if (distance > best) { best = distance; chosen = candidate }
            }
            val point = chosen ?: break
            prior.add(point); result[id] = TimelineSpawnPatch(point, TimelineSpawnTile(point.x + 4, point.y))
        }
        return result.toMap()
    }
}
