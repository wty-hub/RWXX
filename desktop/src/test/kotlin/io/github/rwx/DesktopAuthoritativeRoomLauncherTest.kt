package io.github.rwx

import io.github.rwx.authoritative.AuthoritativeRoomCertificate
import io.github.rwx.net.authoritative.*
import io.github.rwx.net.timeline.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopAuthoritativeRoomLauncherTest {
    @Test fun `normal installations store room secrets in user data while explicit portable paths stay isolated`() {
        val user = File("/user-writable/data")
        assertEquals(File(user, "authoritative-rooms"), authoritativeRoomsDirectory(user, null))
        assertEquals(File("/isolated-state/authoritative-rooms"), authoritativeRoomsDirectory(user, "/isolated-state"))
    }
    @Test fun `room certificate works with the exact pinned TLS handshake using public JRE APIs`() {
        val password = "temporary-room-test-password".toCharArray()
        val generated = AuthoritativeRoomCertificate.generate(password)
        val keys = KeyStore.getInstance("PKCS12").apply { load(generated.pkcs12.inputStream(), password) }
        val cert = keys.getCertificate("room") as X509Certificate
        cert.checkValidity()
        cert.verify(cert.publicKey)
        assertEquals("EC", cert.publicKey.algorithm)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertTrue(cert.serialNumber.signum() > 0 && cert.serialNumber.toByteArray().size <= 20)
        val tls = AuthoritativeTls.serverContext(generated.pkcs12.inputStream(), password)
        val token = "ephemeral-room-handshake-" + "a".repeat(32)
        fun n(id: Int, value: Long) = TimelineMessages.numberField(id, value, true)
        fun b(id: Int, vararg value: Byte) = TimelineMessages.bytesField(id, TimelineBytes(value))
        val map = TimelineRecord(listOf(n(1, 1), n(2, 1), n(3, 1), n(4, 20), n(5, 20), b(6, 0),
            n(7, 1), b(8, 0, 0), b(9, 0, 0), b(10, 0)))
        TimelineServer(InetAddress.getLoopbackAddress(), 0,
            mapOf(token to TimelineSeat(0, TimelineBytes(ByteArray(16)))), map,
            TimelineBytes(ByteArray(16)), TimelineFeedFactory { null }, tlsContext = tls).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "room test", token,
                expectedFingerprint = AuthoritativeTls.fingerprint(cert)).use { client ->
                assertEquals(0, client.teamId)
                assertTrue(client.isConnected)
            }
            assertFailsWith<java.io.IOException> {
                TimelineClient("127.0.0.1", server.localPort, "wrong pin", token,
                    expectedFingerprint = "00".repeat(32))
            }
        }
        password.fill('\u0000')
    }

    @Test fun `menu host generates separate private seats and a pinned guest invitation without argv secrets`() = temporary { root ->
        var argv = emptyList<String>()
        val launcher = DesktopAuthoritativeRoomLauncher(root, command = { args, _ -> args }, start = { args, _ ->
            argv = args
            File(option(args, "ready-file")).writeText("ready\n")
            DummyProcess()
        })
        val result = launcher.host("127.0.0.1:5124", "玩家🍎")
        val invite = TimelineInvitation.parse(result.invitation)
        val seats = AuthoritativeDesktopMain.readSeats(option(argv, "seats"))
        val hostToken = File(option(argv, "token-file")).readText()
        assertEquals(0, seats[hostToken])
        assertEquals(1, seats[invite.token])
        assertNotEquals(hostToken, invite.token)
        assertTrue("--authority-host" in argv && "--bind=0.0.0.0" in argv)
        assertEquals("16", option(argv, "map-cache-limit"))
        assertTrue(argv.none { hostToken in it || invite.token in it || result.invitation in it })
        assertFalse(result.toString().contains(invite.token))
        val password = File(option(argv, "tls-password-file")).readText().toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply { File(option(argv, "tls-keystore")).inputStream().use { load(it, password) } }
        assertEquals(invite.fingerprint, AuthoritativeTls.fingerprint(keys.getCertificate("room") as X509Certificate))
        password.fill('\u0000')
        val room = File(option(argv, "seats")).parentFile.toPath()
        if (Files.getFileStore(room).supportsFileAttributeView("posix")) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(room)))
            for (name in listOf("seats.properties", "host.token", "guest.invite", "tls-password.txt", "server.p12")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(room.resolve(name))))
            }
        }
        launcher.host("[::1]:5124", "IPv6Host")
        assertTrue("--bind=::" in argv, "An IPv6 invitation must have an IPv6 listener")
    }

    @Test fun `menu join preserves the server pin and stores only its own token in a private file`() = temporary { root ->
        var argv = emptyList<String>()
        val launcher = DesktopAuthoritativeRoomLauncher(root, command = { args, _ -> args }, start = { args, _ ->
            argv = args
            File(option(args, "ready-file")).writeText("ready\n")
            DummyProcess()
        })
        val invite = TimelineInvitation("::1", 5124, "a1".repeat(32), "guest-seat-" + "z".repeat(32))
        launcher.join(invite.encode(), "Player")
        assertTrue("--authority-client" in argv && "--connect=::1" in argv)
        assertTrue("--server-fingerprint=${invite.fingerprint}" in argv)
        assertEquals(invite.token, File(option(argv, "token-file")).readText())
        assertTrue(argv.none { invite.token in it })
        assertEquals("16", option(argv, "map-cache-limit"))
        assertFalse(File(option(argv, "token-file")).parentFile.resolve("seats.properties").exists())
        val before = root.listFiles()!!.size
        assertFailsWith<IllegalArgumentException> { launcher.join("invalid", "Player") }
        assertEquals(before, root.listFiles()!!.size, "Invalid invitations must fail before creating a room or starting anything")
    }

    @Test fun `room launches read the latest configured capacity and share cache across rooms`() = temporary { root ->
        var limit = 32
        val calls = mutableListOf<List<String>>()
        val launcher = DesktopAuthoritativeRoomLauncher(root, command = { args, _ -> args },
            mapCacheLimit = { limit }, start = { args, _ ->
                calls += args
                File(option(args, "ready-file")).writeText("ready\n")
                DummyProcess()
            })
        val invite = TimelineInvitation("127.0.0.1", 5124, "a1".repeat(32), "guest-seat-" + "z".repeat(32))
        launcher.join(invite.encode(), "Player")
        limit = 0
        launcher.join(invite.encode(), "Player")
        assertEquals(listOf("32", "0"), calls.map { option(it, "map-cache-limit") })
        assertEquals(1, calls.map { option(it, "map-cache-dir") }.toSet().size)
        assertNotEquals(File(option(calls[0], "token-file")).parent, File(option(calls[1], "token-file")).parent)
    }

    @Test fun `trimmed runtimes reuse the native launcher while development preserves JVM module options`() = temporary { root ->
        val native = root.resolve("RWXX").apply { writeText("test placeholder"); setExecutable(true) }
        val state = root.resolve("private state")
        val args = listOf("--authority-server", "--port=5124")
        val packaged = AuthoritativeProcessCommand.build(root.resolve("trimmed-runtime"), "unused", native.path, args, state)
        assertEquals(listOf(native.absolutePath) + args + "--state-dir=${state.absolutePath}", packaged)
        assertFailsWith<IllegalArgumentException> {
            AuthoritativeProcessCommand.build(root.resolve("trimmed-runtime"), "unused", native.path, args, state, listOf("-javaagent:test"))
        }
        val javaHome = root.resolve("jdk")
        javaHome.resolve("bin").mkdirs()
        javaHome.resolve("bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            .apply { writeText("test placeholder"); setExecutable(true) }
        val development = AuthoritativeProcessCommand.build(javaHome, "class path with spaces", native.path, args, state)
        assertTrue(development.any { it == "--enable-native-access=ALL-UNNAMED" })
        assertTrue("class path with spaces" in development)
        assertTrue(AuthoritativeDesktopMain::class.java.name in development)
        assertEquals("--state-dir=${state.absolutePath}", development.last())
        javaHome.resolve("bin/java").delete()
        javaHome.resolve("bin/java.exe").delete()
        val javaw = javaHome.resolve("bin/javaw.exe").apply { writeText("test placeholder"); setExecutable(true) }
        val launch4j = AuthoritativeProcessCommand.build(javaHome, "wrapped-app.exe", native.path, args, state)
        assertEquals(javaw.absolutePath, launch4j.first())
        assertTrue("-cp" in launch4j && "wrapped-app.exe" in launch4j,
            "Launch4j's javaw-only runtime still requires a JVM classpath and main class")
    }

    private fun option(args: List<String>, key: String) = args.single { it.startsWith("--$key=") }.substringAfter('=')
    private fun temporary(action: (File) -> Unit) {
        val root = Files.createTempDirectory("rwxx-menu-room-test-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }

    private class DummyProcess : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue(): Int = throw IllegalThreadStateException()
        override fun destroy() {}
        override fun isAlive() = true
    }
}
