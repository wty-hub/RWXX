package io.github.rwx

import io.github.rwx.net.authoritative.EnginePublicMapSnapshot
import io.github.rwx.net.authoritative.StockActionClosure
import io.github.rwx.net.authoritative.StockUnitCatalog
import io.github.rwx.net.timeline.TimelineProtocol
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Explicit test-only real simulation harness; never dispatched by the application launcher. */
object TimelineNativeAcceptanceMain {
    @JvmStatic fun main(args: Array<String>) {
        check(System.getenv("RWX_RUN_TIMELINE_NATIVE_ACCEPTANCE") == "1") {
            "Explicit native timeline acceptance opt-in is required"
        }
        require(args.size == 1 && args.single().startsWith("--output=")) { "Expected --output=NEW_JSON" }
        val output = File(args.single().substringAfter('=')).absoluteFile
        require(!Files.exists(output.toPath())) { "Evidence destination already exists" }
        Files.createDirectories(output.parentFile.toPath())
        val fixtures = Files.createDirectory(output.parentFile.toPath().resolve(output.nameWithoutExtension + "-fixtures"))
        System.setProperty("launch.dir", fixtures.resolve("runtime").toString())
        System.setProperty("user.home", Files.createDirectory(fixtures.resolve("home")).toString())
        System.setProperty("java.awt.headless", "true")
        val started = System.nanoTime()
        val finished = AtomicBoolean()
        val evidenceClaimed = AtomicBoolean()
        val watchdog = Thread({
            try { Thread.sleep(180_000) } catch (_: InterruptedException) { return@Thread }
            if (finished.get()) return@Thread
            // A second guard must still terminate if even writing timeout evidence blocks.
            Thread({
                try { Thread.sleep(2_000) } catch (_: InterruptedException) { }
                Runtime.getRuntime().halt(124)
            }, "RWXX-native-acceptance-hard-stop").apply { isDaemon = true; start() }
            val timeout = buildJsonObject {
                put("passed", false); put("completed", false); put("timedOut", true)
                put("timeoutSeconds", 180); put("applicationVersion", AppMetadata.VERSION_NAME)
                put("protocolVersion", TimelineProtocol.VERSION)
                put("scope", "Test-only native action/upgrade acceptance did not complete; no compatibility, GUI, Internet or capacity acceptance is implied")
            }
            if (evidenceClaimed.compareAndSet(false, true)) runCatching {
                Files.writeString(output.toPath(), timeout.toString() + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            }.onFailure { it.printStackTrace(System.err) }
            System.err.println("TimelineNativeAcceptance timed out after 180s; incomplete, halting this test JVM")
            Runtime.getRuntime().halt(124)
        }, "RWXX-native-acceptance-deadline").apply { isDaemon = true; start() }
        val reports = linkedMapOf<String, JsonElement>()
        val failures = mutableListOf<Throwable>()
        var completed = false
        var startTick: Int? = null
        var endTick: Int? = null
        try {
            val engine = AuthoritativeDesktopMain.boot(AuthoritativeDesktopMain.DEFAULT_MAP)
            val map = EnginePublicMapSnapshot.capture(engine.tileMap)
            // These catalogs must be frozen once, before any simulation or fixture is created.
            val stock = StockUnitCatalog.capture(engine)
            val actions = StockActionClosure.capture(engine, stock)
            startTick = engine.currentTick
            fun runHelper(name: String, run: (File) -> Map<String, Boolean>) {
                val evidence = fixtures.resolve("$name.json").toFile()
                try {
                    val checks = run(evidence)
                    check(checks.isNotEmpty() && checks.values.all { it }) { "$name did not complete all checks" }
                    val result = Json.parseToJsonElement(evidence.readText()).jsonObject
                    check(result["passed"]?.jsonPrimitive?.boolean == true) { "$name reported incomplete acceptance" }
                    reports[name] = result
                } catch (error: Throwable) {
                    failures += error
                    reports[name] = if (evidence.isFile) runCatching { Json.parseToJsonElement(evidence.readText()) }
                        .getOrElse { buildJsonObject { put("passed", false); put("error", error.stackTraceToString()) } }
                    else buildJsonObject { put("passed", false); put("error", error.stackTraceToString()) }
                }
            }
            runHelper("actions") { TimelineNativeActionAcceptance.run(engine, map, stock, actions, it) }
            // Keep an independent result even if the action fixture failed and cleaned up.
            runHelper("upgrades") { TimelineNativeUpgradeAcceptance.run(engine, map, stock, actions, it) }
            endTick = engine.currentTick
            completed = reports.size == 2 && failures.isEmpty()
        } catch (error: Throwable) { failures += error }
        val result = buildJsonObject {
            put("passed", completed && failures.isEmpty()); put("completed", completed)
            put("applicationVersion", AppMetadata.VERSION_NAME); put("protocolVersion", TimelineProtocol.VERSION)
            put("scope", "Test-only headless native action/upgrade effects, plus one actual v7 timeline selection/capability/RALLY roundtrip and the resulting produced-unit waypoint. No GUI, Internet or capacity claim.")
            put("startTick", startTick); put("endTick", endTick)
            put("elapsedSeconds", (System.nanoTime() - started) / 1e9)
            put("results", JsonObject(reports))
            put("errors", JsonArray(failures.map { JsonPrimitive(it.stackTraceToString()) }))
        }
        check(evidenceClaimed.compareAndSet(false, true)) { "Native acceptance exceeded its absolute deadline" }
        Files.writeString(output.toPath(), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result) + "\n",
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        finished.set(true)
        watchdog.interrupt()
        failures.forEach { it.printStackTrace(System.err) }
        println("TimelineNativeAcceptance passed=$completed output=${output.path}")
        // The test-only harness owns this JVM, including engine pathfinding worker threads.
        exitProcess(if (completed && failures.isEmpty()) 0 else 1)
    }
}
