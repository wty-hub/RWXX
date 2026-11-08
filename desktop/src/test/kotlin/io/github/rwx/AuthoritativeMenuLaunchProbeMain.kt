package io.github.rwx

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.net.timeline.TimelineInvitation
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in real launcher fixture. Its controller must own and keep a reviewed virtual display alive. */
object AuthoritativeMenuLaunchProbeMain {
    private const val QUIET = "[preferences]\nenableSounds=\"false\"\nmasterVolume=\"0.0\"\nmusicVolume=\"0.0\"\ngameVolume=\"0.0\"\ninterfaceVolume=\"0.0\"\n"
    @JvmStatic fun main(args: Array<String>) {
        val options = args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("jar", "agent", "bounds", "state-root", "output", "running"))
        val output = File(options.getValue("output"))
        val running = File(options.getValue("running"))
        val jar = File(options.getValue("jar")).absolutePath
        val agent = File(options.getValue("agent")).absolutePath
        val bounds = options.getValue("bounds")
        require(bounds.split(',').size == 4 && bounds.split(',').all { it.toIntOrNull() != null })
        data class Child(val role: String, val process: Process, val state: File, val argv: List<String>,
            val started: String = process.info().startInstant().map(Instant::toString).orElse(""))
        val children = mutableListOf<Child>()
        val ownershipLock = Any()
        val stopping = AtomicBoolean(false)
        var server: ProcessHandle? = null
        var serverStarted = ""
        var failure: String? = null
        var noCredentialsInArgv = false
        var completed = false
        fun clients() = buildJsonArray {
            children.forEach { child -> add(buildJsonObject {
                put("role", child.role); put("pid", child.process.pid())
                put("startInstant", child.started)
                put("argv", buildJsonArray { child.argv.forEach { add(it) } })
                put("stateDir", child.state.absolutePath); put("log", child.state.resolve("client.log").absolutePath)
                put("recording", child.state.resolve("own.rwxr").absolutePath)
                put("canvas", child.state.resolve("canvas.ndjson").absolutePath)
                put("windowProbe", child.state.resolve("background-window.ndjson").absolutePath)
                if (!child.process.isAlive) put("exitCode", child.process.exitValue())
            }) }
        }
        fun writeRunning() {
            val text = buildJsonObject {
                put("clients", clients())
                server?.let { put("serverPid", it.pid()); put("serverStartInstant", serverStarted)
                    put("serverStateDir", children.single { child -> child.role == "host" }.state.resolve("server").absolutePath) }
            }.toString()
            val pending = File(running.path + ".tmp")
            pending.writeText(text)
            Files.move(pending.toPath(), running.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        val launcher = DesktopAuthoritativeRoomLauncher(File(options.getValue("state-root")),
            command = { arguments, state ->
                AuthoritativeProcessCommand.build(File(System.getProperty("java.home")), jar, null,
                    arguments + "--home-dir=${state.resolve("home")}", state,
                    listOf("-javaagent:$agent", "-Dapple.awt.UIElement=true", "-Djava.awt.headless=false",
                        "-Drwx.background.display.bounds=$bounds", "-Drwx.background.target.fps=120",
                        "-Drwx.background.observer.output=${state.resolve("background-window.ndjson")}",
                        "-Drwx.kool.backend=vulkan"))
            },
            start = { command, log -> synchronized(ownershipLock) {
                check(!stopping.get()) { "The fixture is shutting down" }
                val role = if ("--authority-host" in command) "host" else "guest"
                val state = log.parentFile
                state.resolve("home").mkdirs()
                state.resolve("preferences.toml").writeText(QUIET)
                state.resolve("startup-preferences.toml").writeText(QUIET)
                state.resolve("server").mkdirs()
                state.resolve("server/preferences.toml").writeText(QUIET)
                val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).apply {
                    environment()["RWX_AUTHORITY_CANVAS_PROBE"] = state.resolve("canvas.ndjson").absolutePath
                    environment()["RWX_DESKTOP_TARGET_FPS"] = "120"
                    environment()["RWX_DEBUG_AUTO_EXIT_SECONDS"] = if (role == "host") "20" else "10"
                }.start()
                children += Child(role, process, state, command)
                writeRunning()
                process
            } }, readyTimeoutMillis = 45_000)
        fun stopOwned() {
            // Retain Process / ProcessHandle identities, never signal a stale process group.
            val owned = synchronized(ownershipLock) { stopping.set(true); children.toList() }
            val descendants = owned.flatMap { it.process.descendants().toList() }.filter { handle ->
                handle.info().arguments().orElse(emptyArray()).contains("--authority-server")
            } + listOfNotNull(server)
            owned.filter { it.process.isAlive }.forEach { it.process.destroy() }
            descendants.filter { it.isAlive }.forEach { it.destroy() }
            owned.forEach { if (it.process.isAlive && !it.process.waitFor(8, TimeUnit.SECONDS)) it.process.destroyForcibly() }
            descendants.forEach { handle ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (handle.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
                if (handle.isAlive) handle.destroyForcibly()
            }
        }
        val hook = Thread({ runCatching(::stopOwned) }, "authority-menu-probe-cleanup")
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            check(GameEngine.getInstance() == null)
            val port = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
            val result = launcher.host("127.0.0.1:$port", "VirtualHost")
            val host = children.single { it.role == "host" }
            server = host.process.descendants().toList().single { handle ->
                handle.info().arguments().orElse(emptyArray()).contains("--authority-server")
            }
            serverStarted = server!!.info().startInstant().map(Instant::toString).orElse("")
            writeRunning()
            val invitation = TimelineInvitation.parse(result.invitation)
            launcher.join(result.invitation, "VirtualGuest")
            val hostToken = host.argv.single { it.startsWith("--token-file=") }.substringAfter('=').let(::File).readText()
            noCredentialsInArgv = children.all { child -> child.argv.none { hostToken in it || invitation.token in it || result.invitation in it } }
            check(noCredentialsInArgv)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (children.any { it.process.isAlive } && System.nanoTime() < deadline) Thread.sleep(50)
            check(children.size == 2 && children.all { !it.process.isAlive && it.process.exitValue() == 0 })
            val serverDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
            while (server!!.isAlive && System.nanoTime() < serverDeadline) Thread.sleep(20)
            check(!server!!.isAlive) { "Host closed without stopping its server" }
            check(GameEngine.getInstance() == null)
            completed = true
        } catch (error: Exception) { failure = error.javaClass.simpleName + ": " + error.message }
        finally {
            stopOwned()
            Runtime.getRuntime().removeShutdownHook(hook)
            output.writeText(buildJsonObject {
                put("passed", completed && failure == null); put("noGameEngine", GameEngine.getInstance() == null)
                put("credentialsInArgv", !noCredentialsInArgv); put("serverStopped", server?.isAlive == false)
                server?.let { put("serverPid", it.pid()); put("serverStartInstant", serverStarted)
                    put("serverStateDir", children.single { child -> child.role == "host" }.state.resolve("server").absolutePath) }
                put("clients", clients()); failure?.let { put("error", it) }
            }.toString() + "\n")
        }
        check(completed) { "Menu launcher probe failed; see its report" }
    }
}
