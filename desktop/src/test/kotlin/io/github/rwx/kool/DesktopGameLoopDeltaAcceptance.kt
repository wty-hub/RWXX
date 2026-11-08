package io.github.rwx.kool

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.network.GameStateChecksum
import io.github.rwx.benchmark.DriverParityHarness
import io.github.rwx.diagnostics.SimulationCompatibilityTrace
import io.github.rwx.headless.HeadlessGameSession
import kotlinx.serialization.json.*
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Opt-in correctness main; never runs as JUnit. Compare the actual desktop delta adapter against
 * independent original integer-millisecond entry parameters on the actual GameLogic engine.
 * Both paths restore the same complete save/replay and receive the same ordered command inputs.
 * This checks every serialized state section at each outer frame and actual simulation tick,
 * original network-checksum fields and complete executed-command payload/timing. No values are
 * normalized out of the comparison. Existing save-loader wall-clock/RNG sidebands are restored to
 * the same initial fixture values before either path, as in DriverParityHarness.
 *
 * --map=maps/... --output=/new/directory --frames=240
 * Requires isolated launch.dir/user.home and real rwx.assetsDir; no window/GPU/audio is created.
 * Network sockets/peer sync require DesktopClockNetworkPeer and the original compatibility runner.
 */
object DesktopGameLoopDeltaAcceptance {
    private data class Frame(val start: Long, val end: Long, val input: List<(GameEngine) -> Unit>)
    private data class Checksum(val tick: Int, val total: Long, val fields: List<Pair<String, Long>>)
    private data class Run(val frames: List<SimulationCompatibilityTrace.State>,
        val ticks: List<SimulationCompatibilityTrace.State>, val checksums: List<Checksum>,
        val commands: List<SimulationCompatibilityTrace.ExecutedCommand>, val ownerCalls: Long,
        val fallbacks: Long, val explicitCalls: Long)
    private class InitialFixtureMismatch(val difference: String) : IllegalStateException(difference)

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val code = try { run(args); 0 } catch (error: Throwable) {
            error.printStackTrace(System.err); 1
        }
        SimulationCompatibilityTrace.observe(null, null)
        SimulationCompatibilityTrace.close()
        exitProcess(code) // Legacy pathfinding/replay worker lifetime is process-scoped.
    }

    private fun run(args: Array<String>) {
        val values = linkedMapOf<String, String>()
        args.forEach {
            require(it.startsWith("--") && '=' in it)
            val key = it.substring(2).substringBefore('=')
            require(key in setOf("map", "output", "frames") && values.put(key, it.substringAfter('=')) == null)
        }
        val map = requireNotNull(values["map"]) { "--map is required" }
        require(map.startsWith("maps/") || map.startsWith("/SD/mods/maps/"))
        val frames = values["frames"]?.toInt() ?: 240
        require(frames in 90..600)
        val root = DesktopClockAcceptanceSupport.requireIsolation()
        val output = File(requireNotNull(values["output"]) { "--output is required" }).absoluteFile
        require(output.canonicalFile.toPath().startsWith(root.toPath())) { "Output must be in isolated launch.dir" }
        output.parentFile.mkdirs(); Files.createDirectory(output.toPath())
        DesktopClockAcceptanceSupport.boot()
        val session = HeadlessGameSession()
        val engine = session.boot(GlobalContext.get().get<GraphicsEngine>())
        check(engine is com.corrodinggames.rts.game.GameLogic && GameEngine.isHeadlessMode)
        session.openMap(map)
        check(!engine.networkEngine.networkGameActive && !engine.replayEngine.j())
        val snapshot = checkNotNull(session.captureMapSnapshot())
        val seed = engine.globalSeed
        val cameraX = engine.viewpointX
        val cameraY = engine.viewpointY
        val pings = (0 until PlayerTeam.TEAM_NEUTRAL).mapNotNull { id -> PlayerTeam.k(id)?.let { id to it.teamLastPingTime } }.toMap()
        fun restoreSidebands() { pings.forEach { (id, time) -> PlayerTeam.k(id)?.teamLastPingTime = time } }
        fun restoreMap(): GameEngine = session.restoreSnapshot(snapshot).also {
            it.globalSeed = seed; restoreSidebands()
            // The legacy loader centers using viewpointWidth while the save writes the center
            // using visibleWorldWidth. With a desktop sidebar these differ, so repeated restores
            // drift by half a sidebar. The command tape intentionally uses the live viewpoint;
            // restore the original fixture camera through the public setter before BOTH paths.
            // Its complete bytes remain part of the initial-state and all later comparisons.
            it.setViewpoint(cameraX, cameraY)
        }
        val reports = mutableListOf<JsonObject>()
        try {
            reports += compare("saved-map", tape(DriverParityHarness.defaultTape(frames)), ::restoreMap, output)
            check(reports.last()["passed"]!!.jsonPrimitive.boolean) { "Saved-map parity failed; stop before recording/replaying another mode" }
            // Record a real replay with normal queued orders and the original system step-rate
            // command. No ReplayEngine accumulator, command bytes or simulation steps are edited.
            restoreMap()
            val replayName = "clock-adapter-acceptance-${System.nanoTime()}.replay"
            engine.replayEngine.d(replayName)
            check(engine.replayEngine.k()) { "Cannot record the acceptance replay" }
            try { advanceReference(engine, tape(DriverParityHarness.replayRecordingTape(maxOf(600, frames * 3)))) }
            finally { engine.replayEngine.e() }
            val replayFile = GlobalContext.get().get<io.github.rwx.PlatformStorage>().replaysDir.file.resolve(replayName)
            check(replayFile.isFile && replayFile.length() > 0)
            reports += compare("recorded-replay-pause-speed-and-step-rate",
                tape(DriverParityHarness.replayPlaybackTape(frames)), {
                    session.openReplay(replayName)
                    restoreSidebands(); engine.gameSpeed = 1f
                    check(engine.replayEngine.j() && !engine.replayEngine.isPlaybackEnded)
                    engine
                }, output)
            output.resolve("result.json").writeText(buildJsonObject {
                put("kind", "actual-desktop-delta-engine-parity")
                put("passed", reports.all { it["passed"]!!.jsonPrimitive.boolean })
                put("framesPerMode", frames); put("map", map); put("replaySha256", sha256(replayFile.readBytes()))
                put("checks", JsonArray(reports)); put("performanceEvidence", false)
                put("scope", "Actual GameLogic/save/replay and actual DesktopGameLoopDelta/context with a deterministic endpoint tape. Original integer parameters are independently computed. No network socket or desktop rendering acceptance.")
                put("sidebandsRestoredBeforeBothPaths", "Original fixture globalSeed and camera viewpoint through setViewpoint for map restore; original fixture teamLastPingTime for map/replay loaders. No captured state bytes are omitted or normalized.")
                put("mapFixtureCameraXBits", cameraX.toRawBits()); put("mapFixtureCameraYBits", cameraY.toRawBits())
                put("runtimeDefaultPolicyChanged", false)
            }.toString() + "\n")
            check(reports.all { it["passed"]!!.jsonPrimitive.boolean }) { "Clock adapter diverged; retain the first differing state and raw traces" }
        } finally { engine.replayEngine.e(); session.close() }
    }

    private fun tape(input: List<DriverParityHarness.RecordedFrame>): List<Frame> {
        // Positive absolute timestamps allow an independently simple integer-division oracle.
        // Include ordinary high-refresh fractions, a long stalled frame, and zero first/reset
        // intervals. Negative epoch arithmetic is separately covered by the resolver unit tests.
        val intervals = longArrayOf(3_333_333, 8_333_333, 6_944_444, 13_333_333, 16_666_667, 4_000_000, 33_000_000, 120_200_000)
        var previous = 12_345_678_901L
        return input.mapIndexed { index, source ->
            val next = previous + if (index == 0 || index % 79 == 0) 0L else intervals[index % intervals.size]
            Frame(previous, next, source.input).also { previous = next }
        }
    }

    private fun referenceParameters(frame: Frame): Pair<Float, Int> {
        val millis = (frame.end / 1_000_000L - frame.start / 1_000_000L).toInt()
        return (millis * .060000002f) to millis
    }

    private fun advanceReference(engine: GameEngine, tape: List<Frame>) {
        tape.forEach { frame ->
            frame.input.forEach { it(engine) }
            val (speed, millis) = referenceParameters(frame)
            engine.gameLoop(speed, millis)
        }
    }

    private fun compare(mode: String, tape: List<Frame>, reset: () -> GameEngine, output: File): JsonObject {
        val expected = collect(tape, reset, output.resolve("$mode-original-integer.ndjson"), adapter = false)
        val actual = try {
            collect(tape, reset, output.resolve("$mode-desktop-adapter.ndjson"), adapter = true,
                expectedInitial = expected.frames.first())
        } catch (error: InitialFixtureMismatch) {
            return buildJsonObject {
                put("mode", mode); put("passed", false); put("initialFixtureMatched", false)
                put("firstDifference", "fixture initial state before any clock input: ${error.difference}")
                put("candidateFramesAdvanced", 0)
            }.also { output.resolve("$mode-result.json").writeText(it.toString() + "\n") }
        }
        val initialDifference = expected.frames.first().firstDifference(actual.frames.first())
        var difference: String? = initialDifference?.let { "fixture initial state before any clock input: $it" }
        fun states(label: String, left: List<SimulationCompatibilityTrace.State>, right: List<SimulationCompatibilityTrace.State>) {
            if (difference != null) return
            if (left.size != right.size) { difference = "$label count ${left.size}/${right.size}"; return }
            for (index in left.indices) left[index].firstDifference(right[index])?.let {
                difference = "$label $index: $it"; return
            }
        }
        states("frame", expected.frames, actual.frames)
        states("tick", expected.ticks, actual.ticks)
        if (difference == null && expected.checksums != actual.checksums) difference = "original GameStateChecksum fields/tick order"
        if (difference == null) {
            if (expected.commands.size != actual.commands.size) difference = "executed command count"
            else for (index in expected.commands.indices) {
                val a = expected.commands[index]; val b = actual.commands[index]
                if (a.tick != b.tick || a.createdTick != b.createdTick || a.scheduledTick != b.scheduledTick || !a.bytes.contentEquals(b.bytes)) {
                    difference = "executed command $index complete payload or scheduling"; break
                }
            }
        }
        val enough = actual.ticks.isNotEmpty() && actual.commands.isNotEmpty() && actual.ownerCalls > 0 && actual.fallbacks == 0L
        return buildJsonObject {
            put("mode", mode); put("passed", difference == null && enough)
            put("initialFixtureMatched", initialDifference == null)
            difference?.let { put("firstDifference", it) }
            put("frames", tape.size); put("frameStates", actual.frames.size); put("tickStates", actual.ticks.size)
            put("executedCommands", actual.commands.size); put("originalOwnerCalls", actual.ownerCalls)
            put("missingOwnerFallbacks", actual.fallbacks); put("explicitCalls", actual.explicitCalls)
            put("nonemptyEngineAndCommandEvidence", enough)
        }.also { output.resolve("$mode-result.json").writeText(it.toString() + "\n") }
    }

    private fun collect(tape: List<Frame>, reset: () -> GameEngine, trace: File, adapter: Boolean,
        expectedInitial: SimulationCompatibilityTrace.State? = null): Run {
        val engine = reset()
        val ticks = mutableListOf<SimulationCompatibilityTrace.State>()
        val frames = mutableListOf<SimulationCompatibilityTrace.State>()
        val commands = mutableListOf<SimulationCompatibilityTrace.ExecutedCommand>()
        val checksums = mutableListOf<Checksum>()
        val clock = DesktopGameLoopDelta(originalIntegerMillis = true)
        val context = if (adapter) EngineOwnerFrameTiming.attachOwner() else null
        val checksum = GameStateChecksum()
        fun checksum(): Checksum {
            checksum.computeChecksums()
            return Checksum(engine.currentTick, checksum.totalChecksum, checksum.fields.map { it.label to it.value })
        }
        trace.bufferedWriter().use { writer ->
            fun state(kind: String, index: Int, value: SimulationCompatibilityTrace.State) {
                val sum = checksum()
                checksums += sum
                writer.append(buildJsonObject {
                    put("kind", kind); put("index", index); put("tick", value.tick)
                    put("checksumTotal", sum.total)
                    put("checksumFields", JsonObject(sum.fields.associate { it.first to JsonPrimitive(it.second) }))
                    put("sections", JsonObject(value.sections.associate { it.name to JsonPrimitive(Base64.getEncoder().encodeToString(it.bytes)) }))
                }.toString()).append('\n')
            }
            SimulationCompatibilityTrace.observe({ value -> ticks += value; state("tick", ticks.lastIndex, value) }, { value ->
                commands += value
                writer.append(buildJsonObject {
                    put("kind", "command"); put("tick", value.tick); put("scheduledTick", value.scheduledTick); put("createdTick", value.createdTick)
                    put("serialized", Base64.getEncoder().encodeToString(value.bytes))
                }.toString()).append('\n')
            })
            try {
                SimulationCompatibilityTrace.capture(engine).also {
                    frames += it; state("initial", -1, it)
                    expectedInitial?.firstDifference(it)?.let { difference -> throw InitialFixtureMismatch(difference) }
                }
                tape.forEachIndexed { index, frame ->
                    frame.input.forEach { it(engine) }
                    val (expectedSpeed, expectedMillis) = referenceParameters(frame)
                    val (speed, millis) = if (adapter) {
                        checkNotNull(context).begin(frame.start, frame.end)
                        try {
                            val suppliedFloat = ((frame.end - frame.start) / 1_000_000_000.0).toFloat()
                            clock.resolve(suppliedFloat, allowOwnerInterval = true)
                            check(clock.speedDelta.toRawBits() == expectedSpeed.toRawBits() && clock.millisDelta == expectedMillis) {
                                "Actual adapter parameters diverged at frame $index"
                            }
                            clock.speedDelta to clock.millisDelta
                        } finally { context.clear() }
                    } else expectedSpeed to expectedMillis
                    writer.append(buildJsonObject {
                        put("kind", "input"); put("frame", index); put("startNanos", frame.start); put("endNanos", frame.end)
                        put("speedRawBits", speed.toRawBits()); put("millis", millis)
                        put("adapterSource", if (adapter) clock.source.name else "independent-original-integer")
                    }.toString()).append('\n')
                    engine.gameLoop(speed, millis)
                    SimulationCompatibilityTrace.capture(engine).also { frames += it; state("frame", index, it) }
                }
            } finally {
                SimulationCompatibilityTrace.observe(null, null)
                context?.let(EngineOwnerFrameTiming::detachOwner)
            }
        }
        return Run(frames, ticks, checksums, commands, clock.originalOwnerCalls, clock.missingOwnerFallbacks, clock.explicitCalls)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
