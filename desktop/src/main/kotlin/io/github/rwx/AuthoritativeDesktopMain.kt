package io.github.rwx

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import io.github.rwx.di.coreModule
import io.github.rwx.headless.HeadlessGameSession
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.net.authoritative.*
import io.github.rwx.net.timeline.*
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.model.SettingsModel
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.InetAddress
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import kotlin.system.exitProcess

/** Explicit experimental entry points, entirely separate from legacy network sessions. */
object AuthoritativeDesktopMain {
    val MODES = setOf("--authority-server", "--authority-client", "--authority-host", "--authority-replay")
    internal fun handlesArguments(args: Array<String>): Boolean = args.any { it.startsWith("--authority-") }
    internal const val DEFAULT_MAP = "maps/skirmish/[p2]Small_Island (2p).tmx"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val options = AuthorityOptions.parse(args)
            options.values["state-dir"]?.let { System.setProperty("launch.dir", File(it).absolutePath) }
            options.values["home-dir"]?.let { path ->
                val home = File(path).absoluteFile
                check(home.isDirectory || home.mkdirs()) { "Unable to create the isolated user directory" }
                System.setProperty("user.home", home.path)
            }
            when (options.mode) {
                "--authority-server" -> { runServer(options); exitProcess(0) }
                "--authority-client" -> openClient(options)
                "--authority-host" -> host(options)
                "--authority-replay" -> replay(options)
            }
        } catch (error: Exception) {
            System.err.println("Authoritative protocol failed: ${error.message}")
            error.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    internal fun boot(mapPath: String): GameEngine {
        require(mapPath in TimelineDesktopServer.APPROVED_MAPS) { "Unsupported timeline map" }
        System.setProperty("java.awt.headless", "true")
        configureDesktopLogging()
        HeadlessMain.configureHeadlessPlatform()
        GlobalContext.startKoin { modules(coreModule, headlessModule) }
        val koin = GlobalContext.get()
        koin.get<PlatformStorage>().createDirectories()
        SettingsEngine.getInstance().save()
        val settings = SettingsModel()
        koin.get<GameSettingsRepository>().loadInto(settings)
        LocaleSettings.initialize()
        val graphics = koin.get<GraphicsEngine>()
        GameEngine.graphicsEngine = graphics
        val session = HeadlessGameSession()
        val engine = session.boot(graphics)
        // Room rules must be installed before loading so initial fog is never shared or absent.
        engine.networkEngine.roomSettings.fogMode = 2
        engine.networkEngine.roomSettings.sharedControl = false
        session.openMap(mapPath)
        check(!engine.networkEngine.networkGameActive) { "Legacy networking must be disabled" }
        engine.tileMap.disableDisplayCachesForAuthoritativeHeadless()
        engine.tileMap.enableAuthoritativeHeadlessZeroFogRows()
        engine.formationEngine.enableAuthoritativeHeadlessSingleUnitFormation()
        engine.tileMap.fogEnabled = true
        engine.tileMap.fogPeriodicMaintenanceEnabled = true
        val units = BaseUnit.getGlobalUnitList().toList()
        check(units.none { it is CustomUnit }) { "Custom units are not supported by the experimental protocol" }
        check(units.none { it.team != null && it.team.teamId >= 2 }) { "Only seats 0 and 1 are supported" }
        val credits = (0..1).associateWith { PlayerTeam.k(it)?.credits ?: 4000.0 }
        // Local skirmish loading installs AIController instances. Replace them before any tick;
        // human network seats must never be driven by a local AI.
        for (id in 0 until PlayerTeam.TEAM_NEUTRAL) PlayerTeam.k(id)?.removeFromTeamRegistry()
        val teams = (0..1).associateWith { id -> GameTeam(id).apply {
            teamName = "Player ${id + 1}"
            teamColorId = id
            this.credits = credits.getValue(id)
            fogOfWarWidth = engine.tileMap.tileCountX
            fogOfWarHeight = engine.tileMap.tileCountY
            fogOfWarData = Array(fogOfWarWidth) { ByteArray(fogOfWarHeight) { 10 } }
        } }
        for (unit in units) {
            teams[unit.team?.teamId]?.let(unit::setUnitTeam)
        }
        engine.playerTeam = teams.getValue(0)
        engine.networkEngine.localPlayerTeam = teams.getValue(0)
        for (unit in units.filterIsInstance<OrderableUnit>()) {
            if (!unit.isDead) unit.c(false)
        }
        PlayerTeam.g(0f)
        check(PlayerTeam.getTeams().none { it is com.corrodinggames.rts.game.ai.AIController })
        engine.isStopped = false
        engine.isPaused = false
        return engine
    }

    private fun runServer(options: AuthorityOptions) = TimelineDesktopServer.run(options, ::boot, ::readSeats) {
        serverTls(options)
    }

    internal fun advance(engine: GameEngine) {
        engine.gameLoop(engine.networkEngine.getCurrentStepRate().coerceAtLeast(.1f) + .001f, 16)
    }

    private fun openClient(options: AuthorityOptions, onClose: () -> Unit = {}) {
        check(!java.awt.GraphicsEnvironment.isHeadless()) { "The graphical client requires a desktop" }
        val client = connect(options)
        showClient(client, options, onClose)
    }

    private fun showClient(client: TimelineClient, options: AuthorityOptions, onClose: () -> Unit) {
        fun ready() {
            options.values["ready-file"]?.let { path ->
                java.nio.file.Files.writeString(File(path).toPath(), "ready\n",
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
            }
        }
        val closed = AtomicBoolean(false)
        var recorder: TimelineViewRecorder? = null
        var observation: AutoCloseable? = null
        try {
            options.values["record-view"]?.let { path ->
                val file = File(path).absoluteFile
                file.parentFile?.mkdirs()
                recorder = TimelineViewRecorder(file.toPath())
                observation = client.observeLatest { state ->
                    if (state.context.mode == TimelineStreamMode.LIVE) recorder?.record(state)
                }
            }
        } catch (error: Exception) { client.close(); onClose(); throw error }
        fun cleanup() {
            if (closed.compareAndSet(false, true)) {
                try {
                    observation?.close()
                    client.close()
                    recorder?.let { recording ->
                        try {
                            val result = recording.finishAsync().get(10, java.util.concurrent.TimeUnit.SECONDS)
                            if (result.partial) System.err.println("Timeline recording contains gaps or an unsealed tail")
                        } catch (failure: Exception) {
                            System.err.println("Timeline recording incomplete: ${failure.message}")
                        }
                    }
                } finally { onClose() }
            }
        }
        val shutdown = Thread({ runCatching(::cleanup) }, "authority-client-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            val network = io.github.rwx.authoritative.TimelineViewConnection(client)
            repeat((options.values["views"]?.toInt() ?: 1) - 1) {
                network.playback.addPane(network.playback.position(0, System.nanoTime()).time)
            }
            val source = object : io.github.rwx.authoritative.TimelinePlaybackSource by network {
                override val failureMessage: String? get() = recorder?.failure?.message ?: network.failureMessage
                override val label: String get() = when {
                    recorder?.failure != null -> "Connected | recording stopped"
                    recorder != null -> "${network.label} | recording"
                    else -> network.label
                }
            }
            io.github.rwx.authoritative.AuthoritativeKoolClient.run(source, ::cleanup, ::ready)
        } finally {
            cleanup()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
    }

    private fun replay(options: AuthorityOptions) {
        check(!java.awt.GraphicsEnvironment.isHeadless()) { "Graphical view playback requires a desktop" }
        val speed = (options.values["speed"] ?: "1").toDouble()
        val source = io.github.rwx.authoritative.TimelineReplaySource(File(options.required("replay-file")), speed)
        repeat((options.values["views"]?.toInt() ?: 1) - 1) {
            source.playback.addPane(source.playback.position(0, System.nanoTime()).time)
        }
        val shutdown = Thread({ source.close() }, "authority-replay-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try { io.github.rwx.authoritative.AuthoritativeKoolClient.run(source) }
        finally { source.close(); runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) } }
    }

    private fun connect(options: AuthorityOptions): TimelineClient {
        val token = File(options.required("token-file")).readText().trim()
        require(token.isNotEmpty()) { "Token file is empty" }
        val host = options.values["connect"] ?: "127.0.0.1"
        val fingerprint = options.values["server-fingerprint"]
        require(InetAddress.getByName(host).isLoopbackAddress || fingerprint != null) { "Remote clients require --server-fingerprint" }
        val cache = io.github.rwx.authoritative.TimelineMapCache(options.values["map-cache-dir"]?.let(::File)
            ?: File(System.getProperty("launch.dir")?.let(::File) ?: DesktopPlatformStorage.defaultLocalBaseDir(),
                "cache/authoritative-maps-v7"),
            options.values["map-cache-limit"]?.toInt() ?: io.github.rwx.settings.AuthoritativeMapCacheSettings.DEFAULT_LIMIT)
        return TimelineClient(host, options.port, options.values["name"] ?: "Player", token,
            fingerprint, mapResolver = cache::load, onMapReceived = cache::store)
    }

    private fun host(options: AuthorityOptions) {
        check(!java.awt.GraphicsEnvironment.isHeadless()) { "The tactical host requires a graphical desktop" }
        // A child JVM owns the complete world. The client process never boots GameEngine.
        readSeats(options.required("seats"))
        options.required("token-file")
        if (System.getProperty("launch.dir") == null) {
            System.setProperty("launch.dir", File(File(options.required("seats")).absoluteFile.parentFile, "host-state").absolutePath)
        }
        val serverArguments = mutableListOf("--authority-server", "--map=${options.map}", "--port=${options.port}",
            "--seats=${File(options.required("seats")).absolutePath}", "--bind=${options.values["bind"] ?: "127.0.0.1"}",
            "--owner-stdin=true")
        for (key in listOf("tls-keystore", "tls-password-file")) {
            options.values[key]?.let { serverArguments += "--$key=${File(it).absolutePath}" }
        }
        options.values["home-dir"]?.let { serverArguments += "--home-dir=${File(it, "server").absolutePath}" }
        val command = AuthoritativeProcessCommand.current(serverArguments, File(System.getProperty("launch.dir"), "server"))
        val tls = serverTls(options)
        val bind = InetAddress.getByName(options.values["bind"] ?: "127.0.0.1")
        val localHost = when {
            !bind.isAnyLocalAddress -> bind.hostAddress
            bind is java.net.Inet6Address -> "::1"
            else -> "127.0.0.1"
        }
        var localOptions = options.copy(values = options.values + ("connect" to (options.values["connect"] ?: localHost)))
        if (tls != null) localOptions = localOptions.copy(values = localOptions.values + ("server-fingerprint" to tls.second))
        val processGate = Any()
        var ownedServer: Process? = null
        var stopping = false
        fun stopServer() {
            val process = synchronized(processGate) { stopping = true; ownedServer }
            process?.let(::stopAuthoritativeProcess)
        }
        val shutdown = Thread(::stopServer, "authority-host-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            val process = synchronized(processGate) {
                check(!stopping) { "The host is shutting down" }
                // Leave stdin as a private pipe; an OS-level parent exit closes its write end.
                ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start().also { ownedServer = it }
            }
            var client: TimelineClient? = null
            val deadline = System.nanoTime() + 60_000_000_000L
            while (client == null && process.isAlive && System.nanoTime() < deadline) {
                try { client = connect(localOptions) }
                catch (_: java.io.IOException) { Thread.sleep(100) }
            }
            val connected = checkNotNull(client) { "Local authoritative server did not start" }
            showClient(connected, localOptions) {
                stopServer()
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            }
        } catch (error: Exception) {
            stopServer()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            throw error
        }
    }

    internal fun readSeats(path: String): Map<String, Int> {
        val properties = Properties().apply { File(path).inputStream().use(::load) }
        require(properties.size in 2..64 && properties.containsKey("0") && properties.containsKey("1") &&
            properties.keys.all { it.toString().toIntOrNull() in 0..63 }) {
            "Seat file must contain seats 0 and 1, with optional seats 2..63"
        }
        val seats = properties.entries.associate { (id, token) -> token.toString().trim() to id.toString().toInt() }
        require(seats.size == properties.size && seats.values.toSet().size == properties.size && seats.keys.all { it.length in 32..128 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' } }) {
            "Use distinct random seat tokens of 32..128 ASCII letters/digits/-/_"
        }
        return seats
    }

    private fun serverTls(options: AuthorityOptions): Pair<SSLContext, String>? {
        val store = options.values["tls-keystore"]
        val passwordFile = options.values["tls-password-file"]
        require((store == null) == (passwordFile == null)) { "Provide both --tls-keystore and --tls-password-file" }
        if (store == null) return null
        val password = File(passwordFile!!).readText().trimEnd('\r', '\n').toCharArray()
        try {
            val keys = KeyStore.getInstance("PKCS12").apply { File(store).inputStream().use { load(it, password) } }
            val alias = keys.aliases().toList().single { keys.isKeyEntry(it) }
            val certificate = keys.getCertificate(alias) as X509Certificate
            val context = File(store).inputStream().use { AuthoritativeTls.serverContext(it, password) }
            return context to AuthoritativeTls.fingerprint(certificate)
        } finally { password.fill('\u0000') }
    }
}

internal data class AuthorityOptions(val mode: String, val values: Map<String, String>) {
    val map: String get() = values["map"] ?: AuthoritativeDesktopMain.DEFAULT_MAP
    val port: Int get() = (values["port"] ?: "5124").toInt().also { require(it in 1..65535) }
    fun required(key: String): String = requireNotNull(values[key]) { "Missing --$key=..." }

    companion object {
        fun parse(args: Array<String>): AuthorityOptions {
            val modes = args.filter { it in AuthoritativeDesktopMain.MODES }
            require(modes.size == 1) { "Choose exactly one --authority-server/client/host/replay mode" }
            val values = linkedMapOf<String, String>()
            val allowed = setOf("map", "port", "bind", "seats", "token-file", "connect", "name", "ticks", "output",
                "tls-keystore", "tls-password-file", "server-fingerprint", "record-view", "replay-file", "speed",
                "state-dir", "home-dir", "ready-file", "owner-stdin", "map-cache-dir", "map-cache-limit", "archive-dir", "views")
            for (arg in args.filterNot { it in AuthoritativeDesktopMain.MODES }) {
                require(arg.startsWith("--") && '=' in arg) { "Invalid argument: $arg" }
                val key = arg.substring(2).substringBefore('=')
                val value = arg.substringAfter('=')
                require(key in allowed && value.isNotEmpty() && values.put(key, value) == null) { "Unknown or duplicate argument: $key" }
            }
            require(values["record-view"] == null || modes.single() in setOf("--authority-client", "--authority-host")) { "Only connected player clients can record their views" }
            values["map-cache-limit"]?.let {
                require(it.toIntOrNull() in 0..io.github.rwx.settings.AuthoritativeMapCacheSettings.MAX_LIMIT) {
                    "Map cache limit must be between 0 and 256"
                }
            }
            require((values["map-cache-dir"] == null && values["map-cache-limit"] == null) || modes.single() in setOf("--authority-client", "--authority-host")) {
                "Only connected player clients use a map cache"
            }
            require(values["ready-file"] == null || modes.single() in setOf("--authority-client", "--authority-host")) {
                "Only connected player clients report a ready window"
            }
            require(values["owner-stdin"] == null || modes.single() == "--authority-server" && values["owner-stdin"] == "true") {
                "An owner pipe is only supported by server mode"
            }
            require(values["archive-dir"] == null || modes.single() == "--authority-server") {
                "Only server mode accepts an archive directory"
            }
            values["views"]?.let {
                require(it.toIntOrNull() in 1..4 && modes.single() in setOf("--authority-client", "--authority-host", "--authority-replay")) {
                    "Client and replay windows support between one and four views"
                }
            }
            if (modes.single() == "--authority-replay") {
                require(values["replay-file"] != null) { "Replay requires --replay-file" }
                val speed = (values["speed"] ?: "1").toDouble()
                require(speed.isFinite() && speed in .25..8.0) { "Replay speed must be between 0.25 and 8" }
            } else require(values["replay-file"] == null && values["speed"] == null) { "Replay options require --authority-replay" }
            return AuthorityOptions(modes.single(), values).also { it.port }
        }
    }
}
