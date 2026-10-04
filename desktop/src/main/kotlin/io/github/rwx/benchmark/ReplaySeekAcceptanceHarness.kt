package io.github.rwx.benchmark

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.ReplayTimelineIndex
import com.corrodinggames.rts.gameFramework.file.FileHelper
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import io.github.rwx.diagnostics.SimulationCompatibilityTrace
import io.github.rwx.headless.HeadlessGameSession
import io.github.rwx.render.canvas.*
import com.corrodinggames.rts.gameFramework.ui.GameInterfaceRenderer
import com.corrodinggames.rts.gameFramework.ui.ReplayVisionControls
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Real replay simulation acceptance, run with RWX_REPLAY_SEEK_OUTPUT and desktop:headless. */
object ReplaySeekAcceptanceHarness {
    private data class State(val tick: Int, val time: Int, val digest: String, val sections: Map<String, String>)

    private fun captureFog(engine: GameEngine): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (team in engine.replayEngine.fogDisplayTeams) {
            digest.update(team.teamId.toString().toByteArray())
            engine.replayEngine.getFogDisplayData(team.teamId)?.forEach(digest::update)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun capture(engine: GameEngine): State {
        val digest = MessageDigest.getInstance("SHA-256")
        val sections = linkedMapOf<String, String>()
        fun section(name: String, bytes: ByteArray) {
            digest.update(bytes)
            sections[name] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        val trace = SimulationCompatibilityTrace.capture(engine)
        trace.sections.filter { it.name.startsWith("object-") || it.name.endsWith("command-order") }
            .forEach { section(it.name, it.bytes) }
        for (id in 0 until PlayerTeam.TEAM_NEUTRAL) PlayerTeam.k(id)?.let { team ->
            val stream = GameOutputStream()
            val pingTime = team.teamLastPingTime
            try {
                // Loaders replace this wall-clock connection timestamp; it is not simulation state.
                team.teamLastPingTime = 0
                team.a(stream) // includes resources and complete fog state
            } finally { team.teamLastPingTime = pingTime }
            section("team-$id", stream.toByteArray())
        }
        digest.update(engine.globalSeed.toString().toByteArray())
        digest.update(engine.networkEngine.currentStepRate.toRawBits().toString().toByteArray())
        return State(engine.currentTick, engine.gameTimeMillis,
            digest.digest().joinToString("") { "%02x".format(it) }, sections)
    }

    fun run(session: HeadlessGameSession, engine: GameEngine, name: String, output: File): Boolean {
        val cases = mutableListOf<JsonObject>()
        fun waitIndex() {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (engine.replayEngine.isIndexing && System.nanoTime() < deadline) Thread.sleep(5)
            check(engine.replayEngine.durationMillis >= 0) { engine.replayEngine.timelineError ?: "Index timeout" }
        }
        fun normalUntil(target: Int) {
            val deadline = System.nanoTime() + 120_000_000_000L
            while (engine.gameTimeMillis < target && !engine.replayEngine.isPlaybackEnded) {
                check(System.nanoTime() < deadline) { "Playback timeout at ${engine.gameTimeMillis}" }
                // Extra delta accumulates and can advance two ticks at the target boundary.
                engine.gameLoop(engine.networkEngine.currentStepRate, 16)
            }
        }
        fun seek(target: Int) {
            engine.replayEngine.requestSeek(target)
            val deadline = System.nanoTime() + 120_000_000_000L
            while (engine.replayEngine.isSeeking) {
                check(System.nanoTime() < deadline) { "Seek timeout at ${engine.gameTimeMillis}" }
                engine.gameLoop(1f, 16)
            }
            check(engine.replayEngine.seekError == null) { engine.replayEngine.seekError ?: "Seek failed" }
        }
        var failure: String? = null
        try {
            waitIndex()
            val duration = engine.replayEngine.durationMillis
            val index = FileHelper.openFile(engine.replayEngine.a(name, false)).use { ReplayTimelineIndex.scan(it) { false } }
            val start = engine.replayEngine.startTimeMillis
            val targets = (listOf(start + 15_000, start + 35_000, start + 65_000) + index.checkpoints.map { it.timeMillis() + 2000 })
                .distinct().sorted().filter { it + 1000 < duration }
            check(targets.isNotEmpty()) { "Replay too short for acceptance" }
            val baseline = mutableMapOf<Int, State>()
            val continuation = mutableMapOf<Int, State>()
            val baselineFog = mutableMapOf<Int, String>()
            val continuationFog = mutableMapOf<Int, String>()
            val replay = engine.replayEngine
            val visionTeams = replay.fogDisplayTeams
            check(visionTeams.size >= 2) { "Need two player slots for vision acceptance" }
            check(!replay.isFogDisplayEnabled)
            val initialState = capture(engine)
            val observer = engine.playerTeam
            val fogRule = engine.tileMap.fogEnabled
            replay.setFogDisplayEnabled(true)
            check(replay.fogDisplayTeamId == visionTeams.first().teamId)
            replay.setFogDisplayTeamId(visionTeams[1].teamId)
            val selectedId = replay.fogDisplayTeamId
            check(engine.tileMap.fogDisplayData === replay.getFogDisplayData(selectedId))
            replay.setFogDisplayEnabled(false)
            check(!engine.tileMap.isFogDisplayEnabled)
            replay.setFogDisplayEnabled(true)
            check(replay.fogDisplayTeamId == selectedId)
            check(capture(engine) == initialState && engine.playerTeam === observer && engine.tileMap.fogEnabled == fogRule)
            cases += buildJsonObject { put("fogSwitchPreservesSimulation", true); put("visionPlayers", visionTeams.size) }
            for (target in targets) {
                normalUntil(target)
                baseline[target] = capture(engine)
                baselineFog[target] = captureFog(engine)
                normalUntil(target + 1000)
                continuation[target] = capture(engine)
                continuationFog[target] = captureFog(engine)
            }
            session.openReplay(name)
            waitIndex()
            check(!replay.isFogDisplayEnabled && replay.fogDisplayTeamId == -1)
            replay.setFogDisplayTeamId(selectedId)
            replay.setFogDisplayEnabled(true)
            for (target in targets.reversed()) {
                engine.gameSpeed = 0f
                engine.gameLoop(0f, 16) // settle the loader viewport without advancing simulation
                val cameraX = engine.viewpointX
                val cameraY = engine.viewpointY
                seek(target)
                check(replay.isFogDisplayEnabled && replay.fogDisplayTeamId == selectedId)
                check(engine.tileMap.fogDisplayData === replay.getFogDisplayData(selectedId))
                check(captureFog(engine) == baselineFog[target]) { "Realtime fog history differs after seek to $target" }
                val actual = capture(engine)
                val matched = actual == baseline[target]
                cases += buildJsonObject {
                    put("targetMillis", target); put("matchesContinuous", matched)
                    put("fogMatchesContinuous", true)
                    put("expectedTick", baseline[target]!!.tick); put("actualTick", actual.tick)
                    put("expectedDigest", baseline[target]!!.digest); put("actualDigest", actual.digest)
                }
                check(matched) { "Seek state differs at $target: " + baseline[target]!!.sections.keys
                    .filter { baseline[target]!!.sections[it] != actual.sections[it] }.joinToString() }
                check(engine.gameSpeed == 0f) { "Paused playback resumed after seek" }
                check(engine.viewpointX == cameraX && engine.viewpointY == cameraY) { "Seek moved camera: $cameraX,$cameraY / ${engine.viewpointX},${engine.viewpointY}" }
                engine.gameSpeed = 1f
                normalUntil(target + 1000)
                val continued = capture(engine)
                val expectedContinuation = continuation.getValue(target)
                check(continued == expectedContinuation) {
                    "Continuation differs after $target: expected tick ${expectedContinuation.tick} / ${expectedContinuation.time}ms, " +
                        "actual tick ${continued.tick} / ${continued.time}ms; " + expectedContinuation.sections.keys
                            .filter { expectedContinuation.sections[it] != continued.sections[it] }.joinToString()
                }
                check(captureFog(engine) == continuationFog[target]) { "Realtime fog continuation differs after $target" }
            }
            engine.gameSpeed = 4f
            engine.replayEngine.requestSeek(start + 15_000)
            engine.replayEngine.requestSeek(start + 35_000) // latest request replaces the old target
            while (engine.replayEngine.isSeeking) engine.gameLoop(1f, 16)
            check(engine.gameSpeed == 4f)
            check(capture(engine) == baseline[start + 35_000])
            check(captureFog(engine) == baselineFog[start + 35_000])
            seek(0)
            check(engine.gameTimeMillis <= start + engine.networkEngine.currentStepRate * 17 + 1)
            cases += buildJsonObject { put("startBoundary", true); put("replacementAndSpeed", true) }
            // Reach the actual EOF, then check that simulation freezes and the timeline survives.
            engine.gameSpeed = 1f
            seek(Int.MAX_VALUE)
            repeat(3) { engine.gameLoop(1f, 16) }
            check(engine.replayEngine.isPlaybackEnded)
            val end = capture(engine)
            repeat(3) { engine.gameLoop(1f, 16) }
            check(end == capture(engine)) { "Simulation continued after replay EOF" }
            check(engine.replayEngine.j() && engine.gameSpeed == 0f)
            seek(targets.first())
            check(capture(engine) == baseline[targets.first()])
            check(captureFog(engine) == baselineFog[targets.first()])
            cases += buildJsonObject { put("endBoundaryAndRewind", true) }
            replay.setFogDisplayEnabled(false)
            seek(targets.last())
            check(!replay.isFogDisplayEnabled && replay.fogDisplayTeamId == selectedId)
            check(capture(engine) == baseline[targets.last()]) { "Disabled-fog seek changed simulation" }
            engine.gameSpeed = 1f
            normalUntil(targets.last() + 1000)
            check(capture(engine) == continuation[targets.last()]) { "Disabled-fog continuation changed simulation" }
            cases += buildJsonObject { put("disabledFogSeekAndContinuation", true) }
            seek(targets.first())
            replay.setFogDisplayEnabled(true)
            // Exercise the shared HUD through the same canvas command stream used by desktop backends.
            val oldGraphics = engine.renderGraphicsEngine
            val oldWidth = engine.screenWidth.toInt()
            val oldHeight = engine.screenHeight.toInt()
            val hudMethod = GameInterfaceRenderer::class.java.getDeclaredMethod("a", Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val hudSizes = listOf(320 to 240, 1280 to 720, 1920 to 1080)
            val visionControls = GameInterfaceRenderer::class.java.getDeclaredField("replayVision")
                .apply { isAccessible = true }.get(engine.gameUI.interfaceRenderer) as ReplayVisionControls
            val visionLayout = GameInterfaceRenderer::class.java.getDeclaredMethod("replayVisionLayout").apply { isAccessible = true }
            try {
                val graphics = KoolGraphicsEngine(KoolCanvasCpuTextureStore())
                engine.renderGraphicsEngine = graphics
                // Exercise real terrain and minimap fog branches using the shared command backend.
                val map = engine.tileMap
                fun terrainFog(): List<KoolCanvasCommand> {
                    graphics.beginFrame(800, 600)
                    map.groundLayer.renderLayerRegion(graphics, 0f, 0f, 0f, 0f, map.worldWidth, map.worldHeight,
                        .1f, .1f, true, true, true)
                    return graphics.snapshot().commands.toList()
                }
                val minimap = engine.minimap
                val miniGraphicsField = minimap.javaClass.getDeclaredField("fogGraphics").apply { isAccessible = true }
                val miniGraphics = miniGraphicsField.get(minimap)
                val drawFog = minimap.javaClass.getDeclaredMethod("drawFog", Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType).apply { isAccessible = true }
                fun minimapFog(): List<KoolCanvasCommand> {
                    graphics.beginFrame(800, 600)
                    miniGraphicsField.set(minimap, graphics)
                    try { drawFog.invoke(minimap, 0f, 1f) } finally { miniGraphicsField.set(minimap, miniGraphics) }
                    return graphics.snapshot().commands.toList()
                }
                val beforeFogDrawing = capture(engine)
                replay.setFogDisplayEnabled(false)
                check(terrainFog().isEmpty()) { "Fog still draws when disabled" }
                val noFogMinimap = minimapFog()
                data class FogViewFrame(
                    val teamId: Int,
                    val gridDigest: String,
                    val terrain: List<KoolCanvasCommand>,
                    val minimap: List<KoolCanvasCommand>,
                )
                val fogViewTeams = (visionTeams.distinctBy { it.teamColorId } + visionTeams)
                    .distinctBy { it.teamId }.take(2)
                val fogFrames = fogViewTeams.map { team ->
                    replay.setFogDisplayTeamId(team.teamId)
                    replay.setFogDisplayEnabled(true)
                    val actualTeamId = replay.fogDisplayTeamId
                    check(actualTeamId == team.teamId) {
                        "Fog view selected player $actualTeamId instead of requested player ${team.teamId}"
                    }
                    val data = checkNotNull(replay.fogDisplayData) { "Missing fog grid for selected player $actualTeamId" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    data.forEach(digest::update)
                    val gridDigest = digest.digest().joinToString("") { "%02x".format(it) }
                    check(data.any { col -> col.any { it == 0.toByte() } }) { "No live unit sight for $actualTeamId" }
                    val terrain = terrainFog()
                    val mini = minimapFog()
                    check(terrain.isNotEmpty()) { "Terrain fog commands are empty for selected player $actualTeamId" }
                    check(mini != noFogMinimap) { "Minimap fog commands match disabled fog for selected player $actualTeamId" }
                    minimap.updateElementPositions()
                    check(com.corrodinggames.rts.game.units.BaseUnit.bE.all {
                        it.isDead || it.transportContainer != null || !it.c_() || it.u() || it.hasMinimapPosition
                    }) { "Replay vision hid a minimap unit for selected player $actualTeamId" }
                    FogViewFrame(actualTeamId, gridDigest, terrain, mini)
                }
                check(fogFrames.size == 2) { "Expected two selected fog views, got ${fogFrames.size}" }
                val distinctFogGridCount = fogFrames.map { it.gridDigest }.distinct().size
                // Allies can share an identical grid; player slots are not independent vision coverage.
                if (distinctFogGridCount > 1) {
                    val first = fogFrames[0]
                    val second = fogFrames[1]
                    check(first.terrain != second.terrain) {
                        "Different fog grids for players ${first.teamId}/${second.teamId} produced identical terrain commands"
                    }
                    check(first.minimap != second.minimap) {
                        "Different fog grids for players ${first.teamId}/${second.teamId} produced identical minimap commands"
                    }
                }
                replay.setFogDisplayTeamId(selectedId)
                check(capture(engine) == beforeFogDrawing) { "Fog drawing changed simulation" }
                cases += buildJsonObject {
                    put("realtimeTerrainAndMinimapVisionSwitch", true); put("allMinimapUnitsVisible", true)
                    put("simulationMapFogEnabled", map.fogEnabled)
                    put("testedFogViewCount", fogFrames.size)
                    put("distinctFogGridCount", distinctFogGridCount)
                    put("fogViewTeamIds", JsonArray(fogFrames.map { JsonPrimitive(it.teamId) }))
                }
                for ((width, height) in hudSizes) {
                    engine.updateWindowResolution(width, height)
                    val layout = visionLayout.invoke(engine.gameUI.interfaceRenderer) as ReplayVisionControls.Layout
                    if (!visionControls.isOpen) {
                        val button = layout.button()
                        visionControls.handle(layout, true, button.left() + 1, button.top() + 1, 0, visionTeams.size, true) {}
                        visionControls.handle(layout, false, button.left() + 1, button.top() + 1, 0, visionTeams.size, true) {}
                    }
                    graphics.beginFrame(width, height)
                    hudMethod.invoke(engine.gameUI.interfaceRenderer, 0f, true)
                    val frame = graphics.snapshot()
                    val texts = frame.commands.filterIsInstance<KoolCanvasCommand.DrawText>()
                    check(texts.any { it.text == "−10s" } && texts.any { it.text == "+10s" })
                    check(texts.any { " / " in it.text })
                    check(texts.any { it.text == com.corrodinggames.rts.gameFramework.local.Locale.get("replay.vision.menu") })
                    check(texts.any { com.corrodinggames.rts.gameFramework.local.Locale.get("replay.vision.showFog") in it.text })
                    check(frame.commands.filterIsInstance<KoolCanvasCommand.DrawRect>().all {
                        it.rect.left.isFinite() && it.rect.right.isFinite() && it.rect.top.isFinite() && it.rect.bottom.isFinite()
                    })
                }
                engine.setKeyState(111, true)
                try { hudMethod.invoke(engine.gameUI.interfaceRenderer, 0f, true) }
                finally { engine.setKeyState(111, false) }
                check(!visionControls.isOpen && !engine.gameUI.isDraggingSelection) { "Escape opened the game menu" }
            } finally {
                visionControls.reset()
                engine.renderGraphicsEngine = oldGraphics
                engine.updateWindowResolution(oldWidth, oldHeight)
            }
            cases += buildJsonObject { put("sharedHudRenderSizes", JsonArray(hudSizes.map { JsonPrimitive("${it.first}x${it.second}") })) }
            // Check the ordinary save round trip before allowing synthetic replay checkpoints.
            // Even with clock/RNG/rate sidebands restored, the object serialization must be exact.
            val snapshotState = capture(engine)
            val snapshot = checkNotNull(session.captureMapSnapshot())
            val seed = engine.globalSeed
            val rate = engine.networkEngine.currentStepRate
            session.restoreSnapshot(snapshot)
            engine.currentTick = snapshotState.tick
            engine.gameTimeMillis = snapshotState.time
            engine.globalSeed = seed
            engine.networkEngine.applyChangedSetup(rate, "snapshot-cache-probe")
            val restoredState = capture(engine)
            engine.gameSpeed = 1f
            normalUntil(targets.first() + 1000)
            val snapshotContinuationMatches = capture(engine) == continuation[targets.first()]
            cases += buildJsonObject {
                put("syntheticSnapshotRoundTripMatches", snapshotState == restoredState)
                put("syntheticSnapshotContinuationMatches", snapshotContinuationMatches)
                put("syntheticCacheEnabled", false)
                put("differentSnapshotSections", JsonArray(snapshotState.sections.keys
                    .filter { snapshotState.sections[it] != restoredState.sections[it] }.map(::JsonPrimitive)))
            }
            session.openReplay(name)
            waitIndex()
            engine.replayEngine.requestSeek(duration)
            engine.replayEngine.e()
            check(!engine.replayEngine.isSeeking)
            check(!replay.isFogDisplayEnabled && replay.fogDisplayTeamId == -1 && !visionControls.isOpen)
            cases += buildJsonObject { put("exitCancelsSeek", true) }
        } catch (error: Throwable) {
            failure = error.toString()
            error.printStackTrace()
        }
        output.parentFile?.mkdirs()
        output.writeText(buildJsonObject {
            put("replay", name); put("passed", failure == null)
            put("cases", JsonArray(cases)); failure?.let { put("error", it) }
        }.toString() + "\n")
        return failure == null
    }
}
