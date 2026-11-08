package io.github.rwx.net.timeline

import io.github.rwx.net.authoritative.AuthoritativeTls
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.test.*

class TimelineTlsTransportTest {
    @Test fun `v7 pinned TLS completes before sending its seat credential`() {
        val (tls, fingerprint) = fixture
        server(tls).use { server ->
            TimelineClient("127.0.0.1", server.localPort, "TLS player", TOKEN, expectedFingerprint = fingerprint).use { client ->
                assertEquals(0, client.teamId); assertTrue(client.isConnected)
                assertEquals(setOf(0), server.connectedTeamIds())
            }
        }
    }

    @Test fun `wrong TLS pin cannot authenticate or allocate a v7 seat`() {
        server(fixture.first).use { server ->
            assertFailsWith<IOException> { TimelineClient("127.0.0.1", server.localPort, "TLS player", TOKEN, expectedFingerprint = "00".repeat(32)) }
            assertTrue(server.connectedTeamIds().isEmpty())
        }
    }

    private fun server(tls: SSLContext) = TimelineServer(InetAddress.getByName("127.0.0.1"), 0,
        mapOf(TOKEN to TimelineSeat(0, TimelineBytes(ByteArray(16)))), TimelineTestFixture.map(),
        TimelineBytes(ByteArray(16)), TimelineFeedFactory { null }, tlsContext = tls)

    private companion object {
        val TOKEN = "e".repeat(32)
        val fixture: Pair<SSLContext, String> by lazy {
            val dir = Files.createTempDirectory("rwxx-timeline-tls-").toFile()
            try {
                val store = dir.resolve("ephemeral.p12")
                val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
                val process = ProcessBuilder(keytool.absolutePath, "-genkeypair", "-alias", "timeline-test", "-keyalg", "EC",
                    "-groupname", "secp256r1", "-dname", "CN=RWXX timeline test", "-validity", "2", "-storetype", "PKCS12",
                    "-keystore", store.path, "-storepass", "fixture-only", "-noprompt").redirectErrorStream(true).start()
                try {
                    check(process.waitFor(20, TimeUnit.SECONDS)) { "TLS fixture key generation timed out" }
                    val diagnostic = process.inputStream.bufferedReader().readText()
                    check(process.exitValue() == 0) { "TLS fixture failed: $diagnostic" }
                } finally { process.destroyForcibly() }
                val password = "fixture-only".toCharArray()
                try {
                    val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, password) } }
                    val fingerprint = AuthoritativeTls.fingerprint(keys.getCertificate("timeline-test") as X509Certificate)
                    val context = store.inputStream().use { AuthoritativeTls.serverContext(it, password) }
                    context to fingerprint
                } finally { password.fill('\u0000') }
            } finally { dir.deleteRecursively() }
        }
    }
}
