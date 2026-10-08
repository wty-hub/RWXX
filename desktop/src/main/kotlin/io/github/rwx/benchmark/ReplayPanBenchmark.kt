package io.github.rwx.benchmark

import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameInputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.rwx.session.applyLoadedSinglePlayerFogRules
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.PI

/** Opt-in map-redraw measurement on a real replay; never changes units or simulation rules. */
object ReplayPanBenchmark {
    data class Options(val output: File, val warmupSeconds: Int = 20, val sampleSeconds: Int = 20,
        val repetitions: Int = 2, val requireReplay: Boolean = true, val cameraPeriodSeconds: Int = 20,
        val cameraMode: String = "pan", val zoomMode: String = "none", val zoomPeriodSeconds: Int = 4,
        val zoomMinimum: Float = .35f, val zoomMaximum: Float = 1.5f, val cameraTrace: File? = null,
        val exportMap: File? = null, val localFogMode: Int? = null, val playerDeathSeconds: Int? = null)

    fun options(environment: Map<String, String>): Options? {
        val replayOutput = environment["RWX_REPLAY_PAN_OUTPUT"]?.takeIf(String::isNotBlank)
        val mapOutput = environment["RWX_MAP_PAN_OUTPUT"]?.takeIf(String::isNotBlank)
        require(replayOutput == null || mapOutput == null) { "Choose one replay or local-map pan output" }
        val output = replayOutput ?: mapOutput ?: return null
        val exportMap = environment["RWX_EXPORT_REPLAY_MAP"]?.takeIf(String::isNotBlank)?.let(::File)
        require(exportMap == null || replayOutput != null) { "Map export requires a local replay benchmark" }
        val requestedMode = environment["RWX_REPLAY_PAN_CAMERA_MODE"] ?: "pan"
        require(requestedMode in setOf("pan", "jump", "edge-jump", "pan-zoom", "static")) { "Camera mode must be pan, jump, edge-jump, pan-zoom or static" }
        val mode = if (requestedMode == "pan-zoom") "pan" else requestedMode
        val zoomMode = environment["RWX_REPLAY_PAN_ZOOM_MODE"] ?: if (requestedMode == "pan-zoom") "cycle" else "none"
        require(zoomMode == "none" || zoomMode == "cycle") { "Zoom mode must be none or cycle" }
        require(requestedMode != "pan-zoom" || zoomMode == "cycle") { "pan-zoom requires cycle zoom" }
        fun number(name: String, default: Int, minimum: Int = 1): Int {
            val raw = environment[name] ?: return default
            val value = raw.toIntOrNull()
            require(value != null && value >= minimum) { "$name must be an integer >= $minimum" }
            return value
        }
        fun zoomNumber(name: String, default: Float): Float {
            val value = environment[name]?.toFloatOrNull() ?: if (name !in environment) default else Float.NaN
            require(value.isFinite() && value > 0f) { "$name must be a finite number > 0" }
            return value
        }
        val zoomMinimum = zoomNumber("RWX_REPLAY_PAN_ZOOM_MIN", .35f)
        val zoomMaximum = zoomNumber("RWX_REPLAY_PAN_ZOOM_MAX", 1.5f)
        require(zoomMaximum > zoomMinimum) { "Zoom maximum must exceed minimum" }
        val localFogMode = if (mapOutput != null) when (environment["RWX_BENCHMARK_FOG"]) {
            null -> null; "off" -> 0; "on" -> 2
            else -> error("Local benchmark fog must be off or on")
        } else null
        val death = environment["RWX_BENCHMARK_PLAYER_DEATH_SECONDS"]?.let {
            require(mapOutput != null && environment["RWX_BENCHMARK_UNITS"].isNullOrBlank() && localFogMode == 2) {
                "Player-death diagnostic requires an ordinary local map with fog on, without injected units"
            }
            number("RWX_BENCHMARK_PLAYER_DEATH_SECONDS", 20)
        }
        return Options(File(output), number("RWX_REPLAY_PAN_WARMUP_SECONDS", 20, 0),
            number("RWX_REPLAY_PAN_SAMPLE_SECONDS", 20), number("RWX_REPLAY_PAN_REPETITIONS", 2), replayOutput != null,
            number("RWX_REPLAY_PAN_PERIOD_SECONDS", 20), mode, zoomMode,
            number("RWX_REPLAY_PAN_ZOOM_PERIOD_SECONDS", 4), zoomMinimum, zoomMaximum,
            environment["RWX_REPLAY_PAN_CAMERA_TRACE"]?.takeIf(String::isNotBlank)?.let(::File), exportMap, localFogMode, death)
    }

    /** Diagnostic export only. Preserve the loaded map stream's position; never rewrite the replay. */
    internal fun exportMap(stream: GameInputStream, file: File): Pair<Int, String> {
        val input = stream.activeInputStream
        check(input.markSupported()) { "Embedded replay map must support reset" }
        val remaining = input.available()
        input.reset()
        val bytes = try { input.readAllBytes() } finally {
            input.reset()
            // The same reset anchor is used by GameSaver when copying this embedded map.
            // Restore the original offset after the temporary read, including on read failure.
            val length = input.available()
            check(remaining <= length) { "Embedded map stream changed during export" }
            input.skipNBytes((length - remaining).toLong())
        }
        file.parentFile?.mkdirs()
        Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return bytes.size to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val options by lazy { options(System.getenv()) }
    private val visibilityProbe by lazy { options?.playerDeathSeconds?.let(::LocalVisibilityProbe) }
    private var initializedMap: Any? = null
    @Volatile private var startedAt = 0L
    val startedAtNanos: Long get() = startedAt
    private val normalInputProbe = System.getenv("RWX_NORMAL_INPUT_PROBE") == "1"
    private var previousFrameAt = 0L
    private var sampleStart = 0L
    private var sampleFirstFrameAt = 0L
    private var sampleStartTick = 0
    private var sampleStartGameTimeMillis = 0
    private var repetition = 0
    @Volatile var completedAtNanos: Long = 0L
        private set
    private val frameIntervals = mutableListOf<Long>()
    private var minimumUnits = Int.MAX_VALUE
    private var maximumUnits = 0
    private var minimumVisible = Int.MAX_VALUE
    private var movingUnitFrames = 0
    private var projectileFrames = 0
    private var maximumMovingUnits = 0
    private var sampledEdgeMask = 0
    private var cameraMinimumX = Float.POSITIVE_INFINITY
    private var cameraMaximumX = Float.NEGATIVE_INFINITY
    private var cameraMinimumY = Float.POSITIVE_INFINITY
    private var cameraMaximumY = Float.NEGATIVE_INFINITY
    private var actualZoomMinimum = Float.POSITIVE_INFINITY
    private var actualZoomMaximum = Float.NEGATIVE_INFINITY
    private var cameraTrace: BufferedWriter? = null

    /** Called on the engine owner immediately before the ordinary game loop. */
    fun onFrame(engine: GameEngine) {
        val options = options ?: return
        val panPeriodNanos = options.cameraPeriodSeconds * SECOND
        if (!engine.hasLoadedLevel || engine.tileMap == null) return
        if (options.requireReplay) {
            if (!engine.replayEngine.j() || engine.replayEngine.isSeeking || engine.replayEngine.isPlaybackEnded) return
            check(engine.gameSpeed == 1f && engine.replayEngine.v == 1) { "Replay pan comparison requires 1x playback" }
        } else {
            check(!engine.networkEngine.networkGameActive && !engine.replayEngine.j()) { "Local-map pan requires a local game" }
            check(engine.gameSpeed == 1f) { "Local-map pan requires 1x simulation" }
        }
        val now = System.nanoTime()
        if (startedAt == 0L) {
            options.localFogMode?.let { mode ->
                // The native local map loader uses map metadata; explicitly apply the requested
                // room rules before warmup. The ordinary replay / production path is untouched.
                engine.networkEngine.roomSettings.fogMode = mode
                engine.applyLoadedSinglePlayerFogRules()
            }
            if (System.getenv("RWX_REPLAY_PAN_FOG") == "on" && options.requireReplay) {
                engine.replayEngine.setFogDisplayEnabled(true)
            }
            initializedMap = engine.tileMap
            startedAt = now
            previousFrameAt = now
            sampleStart = startedAt + options.warmupSeconds * SECOND
            repetition = 0
            if (normalInputProbe) {
                require(options.cameraMode == "static" && options.zoomMode == "none") {
                    "Normal input probe must not run with a scripted camera"
                }
                engine.setViewpoint((engine.tileMap.worldWidth - engine.viewpointWidth) / 2f,
                    (engine.tileMap.worldHeight - engine.visibleWorldHeight) / 2f)
                engine.clampCameraPosition()
            }
            resetWindow()
            options.output.parentFile?.mkdirs()
            options.output.writeText("")
            options.cameraTrace?.let { trace ->
                trace.parentFile?.mkdirs()
                cameraTrace = trace.bufferedWriter(bufferSize = 64 * 1024).apply {
                    write("atNanos,tick,zoomPhaseSeconds,requestedTargetZoom,observedTargetZoom,actualZoom,densityZoomScale,viewpointX,viewpointY,viewpointWidth,visibleWorldHeight\n")
                }
            }
            val launchArguments = ProcessHandle.current().info().arguments().orElse(emptyArray())
            append(options.output, buildJsonObject {
                put("kind", "scenario"); put("cameraMode", options.cameraMode); put("replaySuccess", engine.replayEngine.j())
                put("normalInputProbe", normalInputProbe)
                put("localMapFixture", !options.requireReplay)
                put("injectedUnitFixture", !options.requireReplay && !System.getenv("RWX_BENCHMARK_UNITS").isNullOrBlank())
                put("playerDeathDiagnosticSeconds", options.playerDeathSeconds)
                put("roomFogMode", engine.networkEngine.roomSettings.fogMode)
                put("fogDisplayEnabled", engine.replayEngine.isFogDisplayEnabled)
                put("mapFogEnabled", engine.tileMap.fogEnabled)
                put("mapFogPeriodicMaintenanceEnabled", engine.tileMap.fogPeriodicMaintenanceEnabled)
                put("replayPath", launchArguments.firstOrNull { it.startsWith("--replay=") }?.removePrefix("--replay=") ?: "")
                put("replayVersion", engine.replayEngine.replayVersion); put("replaySpeed", engine.gameSpeed)
                put("replayRate", engine.replayEngine.v); put("map", engine.currentMapPath)
                put("initialTick", engine.currentTick); put("initialGameTimeMillis", engine.gameTimeMillis)
                put("startedAtNanos", startedAt)
                put("warmupSeconds", options.warmupSeconds); put("sampleSeconds", options.sampleSeconds)
                put("repetitions", options.repetitions); put("cameraPeriodSeconds", options.cameraPeriodSeconds)
                put("zoomMode", options.zoomMode); put("zoomPeriodSeconds", options.zoomPeriodSeconds)
                put("zoomRequestedMinimum", options.zoomMinimum); put("zoomRequestedMaximum", options.zoomMaximum)
                put("zoomRules", if (options.zoomMode == "cycle")
                    "Smooth targetZoom cycle 1 -> minimum -> maximum -> 1; constrained to ordinary UI bounds; ordinary game loop applies density scale and camera update"
                    else "Preserve ordinary replay zoom; no benchmark zoom writes")
                put("cameraTraceEnabled", options.cameraTrace != null)
                put("cameraTraceObservation", "Owner observation before the ordinary game loop: actualZoom is the last applied zoom; requestedTargetZoom is the next constrained request")
                put("cameraRequestedAmplitude", PAN_AMPLITUDE)
                put("cameraTargetSpanX", 2f * amplitude(engine.tileMap.worldWidth, engine.viewpointWidth))
                put("cameraTargetSpanY", 2f * amplitude(engine.tileMap.worldHeight, engine.visibleWorldHeight))
                put("viewportWidth", engine.screenWidth); put("viewportHeight", engine.screenHeight)
                put("actualScreenWidthPixels", engine.currentScreenWidthPixels)
                put("actualScreenHeightPixels", engine.currentScreenHeightPixels)
                put("zoom", engine.zoom); put("worldWidth", engine.tileMap.worldWidth); put("worldHeight", engine.tileMap.worldHeight)
                put("cameraRules", if (options.cameraMode == "edge-jump")
                    "Visit all four true map corners every ${options.cameraPeriodSeconds} s; diagnostic redraw coverage, not mouse input latency"
                    else if (options.cameraMode == "jump")
                    "Alternate opposite diagonal endpoints every ${options.cameraPeriodSeconds} s; map-redraw benchmark, not mouse input latency"
                    else "${options.cameraPeriodSeconds} s monotonic wall-clock diagonal out-and-back path around map center, limited to map and viewport; map-redraw benchmark, not mouse input latency")
                put("simulationRules", if (options.requireReplay)
                    "original replay at 1x; no injected units, commands or simulation changes; tick bounds recorded for comparison"
                    else if (!System.getenv("RWX_BENCHMARK_UNITS").isNullOrBlank())
                        "local unit fixture at 1x; ordinary movement/combat rules; tick bounds and live units recorded"
                    else "ordinary local map and AI at 1x; map units retained; no injected unit fixture; tick bounds recorded")
            })
            options.exportMap?.let { destination ->
                val stream = checkNotNull(engine.remoteMapStream) { "Replay does not contain an embedded map" }
                val (bytes, digest) = exportMap(stream, destination)
                append(options.output, buildJsonObject {
                    put("kind", "map-export"); put("path", destination.absolutePath)
                    put("bytes", bytes); put("sha256", digest); put("diagnosticOnly", true)
                })
            }
        }
        if (initializedMap !== engine.tileMap) {
            // Recorded resync blocks replace TileMap during the same replay. Keep the
            // original wall-clock windows and path phase across those reloads.
            initializedMap = engine.tileMap
            append(options.output, buildJsonObject {
                put("kind", "map-reload"); put("atNanos", now)
                put("tick", engine.currentTick); put("gameTimeMillis", engine.gameTimeMillis)
                put("worldWidth", engine.tileMap.worldWidth); put("worldHeight", engine.tileMap.worldHeight)
            })
        }
        val zoomPeriodNanos = options.zoomPeriodSeconds * SECOND
        val zoomPhase = ((now - startedAt) % zoomPeriodNanos).toDouble() / zoomPeriodNanos
        val observedTargetZoom = engine.targetZoom
        val requestedTargetZoom = if (options.zoomMode == "cycle") {
            constrainedTargetZoom(cycleZoom(zoomPhase, options.zoomMinimum, options.zoomMaximum),
                engine.currentScreenWidthPixels, engine.currentScreenHeightPixels,
                engine.tileMap.worldWidth, engine.tileMap.worldHeight, engine.densityZoomScale)
                .also { engine.targetZoom = it }
        } else observedTargetZoom
        if (repetition < options.repetitions) {
            cameraTrace?.apply {
                write("$now,${engine.currentTick},${zoomPhase * options.zoomPeriodSeconds},$requestedTargetZoom,$observedTargetZoom,${engine.zoom},${engine.densityZoomScale},${engine.viewpointX},${engine.viewpointY},${engine.viewpointWidth},${engine.visibleWorldHeight}\n")
            }
        }
        val phase = ((now - startedAt) % panPeriodNanos).toDouble() / panPeriodNanos
        val displacement = if (options.cameraMode == "jump") {
            if (((now - startedAt) / panPeriodNanos) % 2L == 0L) -1f else 1f
        } else if (options.cameraMode == "static") {
            // No scripted camera motion: this is the configuration that can be compared with the original build
            // playing the same replay with its own camera, because the original cannot be scripted without
            // taking over the mouse and keyboard.
            0f
        } else when {
            phase < .25 -> 4.0 * phase
            phase < .75 -> 2.0 - 4.0 * phase
            else -> 4.0 * phase - 4.0
        }.toFloat()
        val map = engine.tileMap
        if (!normalInputProbe) {
            if (options.cameraMode == "edge-jump") {
                val corner = (((now - startedAt) / panPeriodNanos) % 4).toInt()
                engine.setViewpoint(edgePosition(map.worldWidth, engine.viewpointWidth, corner == 1 || corner == 2),
                    edgePosition(map.worldHeight, engine.visibleWorldHeight, corner >= 2))
            } else engine.setViewpoint((map.worldWidth - engine.viewpointWidth) / 2f + amplitude(map.worldWidth, engine.viewpointWidth) * displacement,
                (map.worldHeight - engine.visibleWorldHeight) / 2f + amplitude(map.worldHeight, engine.visibleWorldHeight) * displacement)
            engine.clampCameraPosition()
        }
        visibilityProbe?.observe(engine, now, startedAt, options.output)
        val interval = now - previousFrameAt
        previousFrameAt = now
        if (now < sampleStart || repetition >= options.repetitions) return
        if (sampleFirstFrameAt == 0L) {
            sampleFirstFrameAt = now
            sampleStartTick = engine.currentTick
            sampleStartGameTimeMillis = engine.gameTimeMillis
        }
        if (interval > 0L) frameIntervals += interval
        if (options.cameraMode == "edge-jump") {
            val corner = (((now - startedAt) / panPeriodNanos) % 4).toInt()
            val x = edgePosition(map.worldWidth, engine.viewpointWidth, corner == 1 || corner == 2)
            val y = edgePosition(map.worldHeight, engine.visibleWorldHeight, corner >= 2)
            if (kotlin.math.abs(engine.viewpointX - x) < 1f && kotlin.math.abs(engine.viewpointY - y) < 1f)
                sampledEdgeMask = sampledEdgeMask or (1 shl corner)
        }
        cameraMinimumX = minOf(cameraMinimumX, engine.viewpointX)
        cameraMaximumX = maxOf(cameraMaximumX, engine.viewpointX)
        cameraMinimumY = minOf(cameraMinimumY, engine.viewpointY)
        cameraMaximumY = maxOf(cameraMaximumY, engine.viewpointY)
        actualZoomMinimum = minOf(actualZoomMinimum, engine.zoom)
        actualZoomMaximum = maxOf(actualZoomMaximum, engine.zoom)
        var living = 0
        var visible = 0
        var moving = 0
        val units = BaseUnit.bE.a()
        for (index in 0 until BaseUnit.bE.b) {
            val unit = units[index]
            if (!unit.isDead && !unit.isDestroyed) {
                living++
                if (unit.shouldDraw) visible++
                if (unit.isMoving) moving++
            }
        }
        minimumUnits = minOf(minimumUnits, living)
        maximumUnits = maxOf(maximumUnits, living)
        minimumVisible = minOf(minimumVisible, visible)
        if (moving > 0) movingUnitFrames++
        maximumMovingUnits = maxOf(maximumMovingUnits, moving)
        if (com.corrodinggames.rts.gameFramework.GameObject.fastGameObjectList.any { it is com.corrodinggames.rts.game.Projectile }) projectileFrames++
        if (now - sampleStart < options.sampleSeconds * SECOND || frameIntervals.isEmpty()) return
        val sorted = frameIntervals.sorted()
        append(options.output, buildJsonObject {
            put("kind", "engine-window"); put("repetition", ++repetition); put("cameraMode", options.cameraMode)
            put("sampleStartNanos", sampleStart); put("sampleFirstFrameNanos", sampleFirstFrameAt); put("sampleEndNanos", now)
            put("requestedSampleEndNanos", sampleStart + options.sampleSeconds * SECOND)
            put("sampleStartTick", sampleStartTick); put("sampleEndTick", engine.currentTick)
            put("sampleStartGameTimeMillis", sampleStartGameTimeMillis); put("sampleEndGameTimeMillis", engine.gameTimeMillis)
            put("replaySpeed", engine.gameSpeed); put("replayRate", engine.replayEngine.v)
            put("minimumLivingUnits", minimumUnits); put("maximumLivingUnits", maximumUnits); put("minimumVisibleUnits", minimumVisible)
            put("freshEngineFrames", frameIntervals.size); put("engineFps", SECOND / frameIntervals.average())
            put("framesWithMovingUnits", movingUnitFrames); put("maximumMovingUnits", maximumMovingUnits)
            put("framesWithProjectiles", projectileFrames)
            put("engineIntervalP95Ms", percentile(sorted, .95) / 1e6); put("engineIntervalP99Ms", percentile(sorted, .99) / 1e6)
            put("cameraMinimumX", cameraMinimumX); put("cameraMaximumX", cameraMaximumX)
            put("cameraMinimumY", cameraMinimumY); put("cameraMaximumY", cameraMaximumY)
            put("sampledEdgeMask", sampledEdgeMask)
            put("cameraSpanX", cameraMaximumX - cameraMinimumX); put("cameraSpanY", cameraMaximumY - cameraMinimumY)
            put("cameraPeriodSeconds", options.cameraPeriodSeconds)
            put("zoomMode", options.zoomMode); put("zoomPeriodSeconds", options.zoomPeriodSeconds)
            put("actualZoomMinimum", actualZoomMinimum); put("actualZoomMaximum", actualZoomMaximum)
            put("actualZoomEnd", engine.zoom); put("requestedTargetZoomEnd", requestedTargetZoom)
            put("zoomStartPhaseSeconds", ((sampleStart - startedAt) % zoomPeriodNanos).toDouble() / SECOND)
            put("zoomEndPhaseSeconds", ((now - startedAt) % zoomPeriodNanos).toDouble() / SECOND)
            put("cameraStartPhaseSeconds", ((sampleStart - startedAt) % panPeriodNanos).toDouble() / SECOND)
            put("cameraEndPhaseSeconds", ((now - startedAt) % panPeriodNanos).toDouble() / SECOND)
            put("presentationStatus", "requires renderer trace; engine frames alone do not measure fresh presentations")
        })
        // Nominal boundaries avoid frame-rate-dependent phase drift between baseline and candidate.
        sampleStart += options.sampleSeconds * SECOND
        resetWindow()
        if (repetition >= options.repetitions) {
            completedAtNanos = now
            cameraTrace?.close()
            cameraTrace = null
        }
    }

    /** A complete smooth cycle starts and ends at ordinary zoom 1, limited to the requested range. */
    internal fun cycleZoom(phase: Double, minimum: Float, maximum: Float): Float {
        val ordinary = 1f.coerceIn(minimum, maximum)
        val start: Float
        val end: Float
        val progress: Double
        when {
            phase < .25 -> { start = ordinary; end = minimum; progress = phase * 4.0 }
            phase < .75 -> { start = minimum; end = maximum; progress = (phase - .25) * 2.0 }
            else -> { start = maximum; end = ordinary; progress = (phase - .75) * 4.0 }
        }
        val blend = (1.0 - cos(progress * PI)) / 2.0
        return (start + (end - start) * blend).toFloat().coerceIn(minimum, maximum)
    }

    /** Match GameInterfaceRenderer's upper-then-lower bounds, without writing the applied zoom. */
    internal fun constrainedTargetZoom(request: Float, screenWidth: Float, screenHeight: Float,
        worldWidth: Float, worldHeight: Float, densityScale: Float): Float {
        val minimum = minOf(screenWidth / worldWidth, screenHeight / worldHeight) / densityScale
        val maximum = minOf(4.6f / densityScale, 4.6f)
        return request.coerceAtMost(maximum).coerceAtLeast(minimum)
    }

    internal fun visibilityAt(tick: Int): LocalVisibilityProbe.Snapshot? = visibilityProbe?.snapshotAt(tick)

    internal fun edgePosition(world: Float, visible: Float, far: Boolean): Float =
        if (visible >= world) (world - visible) / 2f else if (far) world - visible else 0f

    private fun amplitude(worldExtent: Float, visibleExtent: Float) = ((worldExtent - visibleExtent) / 2f).coerceIn(0f, PAN_AMPLITUDE)
    private fun append(file: File, value: JsonObject) = file.appendText(value.toString() + "\n")
    private fun percentile(sorted: List<Long>, p: Double) = sorted[((sorted.size - 1) * p).toInt().coerceIn(sorted.indices)]
    private fun resetWindow() {
        sampledEdgeMask = 0
        frameIntervals.clear(); sampleFirstFrameAt = 0L; minimumUnits = Int.MAX_VALUE; maximumUnits = 0; minimumVisible = Int.MAX_VALUE
        cameraMinimumX = Float.POSITIVE_INFINITY; cameraMaximumX = Float.NEGATIVE_INFINITY
        cameraMinimumY = Float.POSITIVE_INFINITY; cameraMaximumY = Float.NEGATIVE_INFINITY
        actualZoomMinimum = Float.POSITIVE_INFINITY; actualZoomMaximum = Float.NEGATIVE_INFINITY
        movingUnitFrames = 0; projectileFrames = 0; maximumMovingUnits = 0
    }
    private const val SECOND = 1_000_000_000L
    private const val PAN_AMPLITUDE = 800f
}
