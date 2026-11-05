package io.github.rwx.benchmark

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.PlatformStorage
import io.github.rwx.diagnostics.SimulationCompatibilityTrace
import io.github.rwx.input.MultiTouchPointerState
import io.github.rwx.kool.KoolDesktopGameSession
import io.github.rwx.render.canvas.FrameEnvelope
import io.github.rwx.render.canvas.KoolCanvasViewport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Uses the actual desktop session and full CPU draw extraction without a window or GPU. */
object OwnerSessionAcceptanceHarness {
    private const val WAIT_SECONDS = 60L
    private const val TEST_KEY = 511 // Within original key storage, outside game action bindings.
    private val pointerDown = MultiTouchPointerState::class.java.getDeclaredField("isDown").apply { isAccessible = true }

    fun run(storage: PlatformStorage, mapPath: String, output: File): Boolean {
        val checks = mutableListOf<JsonObject>()
        val retained = mutableListOf<FrameEnvelope>()
        val viewport = KoolCanvasViewport(1280, 720)
        val session = KoolDesktopGameSession(storage)
        var engine: GameEngine? = null
        fun checkResult(name: String, condition: Boolean, details: JsonObject = buildJsonObject {}): Boolean {
            checks += JsonObject(details + ("name" to JsonPrimitive(name)) + ("passed" to JsonPrimitive(condition)))
            return condition
        }
        fun <T> owner(action: () -> T) = session.submitSessionTask(action).get(WAIT_SECONDS, TimeUnit.SECONDS)
        fun waitUntil(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (!condition()) {
                session.mapLoadError(mapPath)?.let { throw IllegalStateException("$label failed", it) }
                check(System.nanoTime() < deadline) { "Timed out waiting for $label" }
                Thread.sleep(5)
            }
        }
        fun packet(): FrameEnvelope {
            session.currentFrame()
            return checkNotNull(session.currentFrameEnvelope()).retain().also(retained::add)
        }
        fun nextTickDelta(after: () -> Unit): Float {
            val observed = CompletableFuture<Float>()
            owner {
                SimulationCompatibilityTrace.observe({ observed.complete((checkNotNull(engine) as GameLogic).lastDelta) }, null)
                after()
            }
            return try { observed.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            finally { owner { SimulationCompatibilityTrace.observe(null, null) } }
        }

        try {
            engine = session.preload(viewport)
            val game = checkNotNull(engine)
            session.prepareMapAsync(mapPath, viewport)
            waitUntil("initial map") { session.isMapLoaded(mapPath) }
            owner {
                game.settingsEngine.highRefreshRate = true
                // Drawing is the CPU-only Kool recorder. No Scene, Texture2d or Vulkan context
                // exists in this process; full original draw logic still runs on the owner.
                GameEngine.isHeadlessMode = false
            }
            waitUntil("complete CPU frame") {
                session.currentFrame(); session.currentFrameEnvelope()?.resourceLease?.resourceCount?.let { it > 0 } == true
            }
            val first = packet()
            val beforeStall = owner { game.currentTick }
            Thread.sleep(180)
            val afterStall = owner { game.currentTick }
            val stalledLatest = packet()
            checkResult("simulation advances with no frontend calls", afterStall > beforeStall, buildJsonObject {
                put("tickBefore", beforeStall); put("tickAfter", afterStall)
                put("firstSequence", first.sequence); put("latestSequence", stalledLatest.sequence)
                put("resources", stalledLatest.resourceLease.resourceCount)
            })
            checkResult("newest completed frame replaces undelivered frames", stalledLatest.sequence > first.sequence + 1)

            var presses = 0
            var clickDowns = 0
            var clickUps = 0
            var pointerWasDown = false
            var wheelTotal = 0
            owner {
                SimulationCompatibilityTrace.observe({
                    if (game.consumeKeyPress(TEST_KEY)) presses++
                    val pointer = game.activeGameView.getSettings()
                    val isDown = pointerDown.getBoolean(pointer)
                    if (isDown != pointerWasDown) {
                        if (isDown) clickDowns++ else clickUps++
                        pointerWasDown = isDown
                    }
                    wheelTotal += game.mouseWheelDelta
                }, null)
            }
            repeat(60) { index ->
                session.submitKey(TEST_KEY, true); session.submitKey(TEST_KEY, false)
                session.submitPointer(100f + index, 120f, true, 1)
                session.submitPointer(100f + index, 120f, false, 1)
                session.submitMouseWheel(1)
            }
            // A queue barrier means the final wheel was applied, but the original update still
            // has to observe it. Keep the observer until the next completed simulation update.
            val inputBarrierTick = owner { game.currentTick }
            waitUntil("last input update") { owner { game.currentTick > inputBarrierTick } }
            owner { SimulationCompatibilityTrace.observe(null, null) }
            checkResult("rapid presses and clicks reach actual original updates", presses == 60 && clickDowns == 60 && clickUps == 60, buildJsonObject {
                put("keyPresses", presses); put("pointerDowns", clickDowns); put("pointerUps", clickUps); put("wheelDeltaTotal", wheelTotal)
            })
            checkResult("wheel events retain accumulated original delta", wheelTotal == 60)

            val seen = packet()
            val finalViewport = KoolCanvasViewport(1600, 900)
            repeat(20) { index -> session.updateFrame(KoolCanvasViewport(1280 + index * 10, 720 + index * 5), 0f) }
            session.updateFrame(finalViewport, 0f)
            waitUntil("resized frame") {
                session.currentFrame(); session.currentFrameEnvelope()?.camera?.viewport == finalViewport
            }
            val resized = packet()
            checkResult("viewport and camera publish together", resized.viewportRevision > seen.viewportRevision &&
                resized.camera?.viewportRevision == resized.viewportRevision && resized.frame.viewport == finalViewport)

            session.setGameVisible(false, finalViewport, pausedBackground = true)
            val pauseTick = owner { game.currentTick }
            Thread.sleep(180)
            checkResult("explicit original local pause stops progression", owner { game.currentTick } == pauseTick)
            val resumeDelta = nextTickDelta { session.setGameVisible(true, finalViewport) }
            checkResult("resume excludes paused wall time", resumeDelta < .25f, buildJsonObject { put("firstSimulationDelta", resumeDelta) })
            session.setGameVisible(false, finalViewport, pausedBackground = false)
            val hiddenTick = owner { game.currentTick }
            Thread.sleep(180)
            checkResult("hidden viewport without pause continues progression", owner { game.currentTick } > hiddenTick)

            val reloadStarted = System.nanoTime()
            val reloadDelta = nextTickDelta {
                Thread.sleep(180) // Delay inside the same serialized actual mod reload operation.
                runBlocking { check(session.requestReloadMods()) }
            }
            checkResult("actual mod reload resets outer clock", reloadDelta < .25f, buildJsonObject {
                put("elapsedMs", (System.nanoTime() - reloadStarted) / 1e6); put("firstSimulationDelta", reloadDelta)
            })
            val beforeLoad = packet()
            val saved = checkNotNull(session.captureMapSnapshot())
            // Same-map preparation intentionally resumes an existing game. Exercise restoration
            // after the actual discard operation, as renderer migration/new-session flows do.
            session.discardRunningGame()
            owner { check(!session.isMapLoaded(saved.mapPath)) }
            session.prepareMapSnapshotAsync(saved, finalViewport)
            waitUntil("snapshot restore") {
                session.currentFrame()
                session.isMapLoaded(mapPath) && (session.currentFrameEnvelope()?.generation ?: 0) > beforeLoad.generation
            }
            val restored = packet()
            checkResult("serialized snapshot load publishes a new generation", restored.generation > beforeLoad.generation &&
                restored.camera?.generation == restored.generation && restored.frame.viewport == finalViewport)
            checkResult("retained packets survive load and reload", retained.all { !it.resourceLease.isReleased })

            val accepted = (0 until 500).map { index -> session.submitSessionTask { index } }
            session.close()
            checkResult("session exit completes accepted operations", accepted.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) } == (0 until 500).toList())
            checkResult("session exit rejects later operations", session.submitSessionTask { 1 }.isCompletedExceptionally)
            retained.forEach(FrameEnvelope::close)
            checkResult("session exit releases all retained CPU packet leases", retained.all { it.resourceLease.isReleased })
        } catch (error: Throwable) {
            checkResult("harness execution", false, buildJsonObject { put("error", error.toString()) })
            error.printStackTrace()
        } finally {
            engine?.let { runCatching { owner { SimulationCompatibilityTrace.observe(null, null) } } }
            runCatching(session::close)
            retained.forEach(FrameEnvelope::close)
            GameEngine.isHeadlessMode = true
        }
        output.parentFile?.mkdirs()
        output.writeText(buildJsonObject {
            put("kind", "actual-desktop-owner-session-acceptance")
            put("passed", checks.all { it["passed"]?.jsonPrimitive?.boolean == true })
            put("checks", JsonArray(checks))
            put("limitation", "CPU recording/session lifecycle only; no GPU fence or active network peer tested by this harness")
        }.toString() + "\n")
        return checks.all { it["passed"]?.jsonPrimitive?.boolean == true }
    }
}
