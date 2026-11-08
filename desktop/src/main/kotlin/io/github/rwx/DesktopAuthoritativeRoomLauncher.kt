package io.github.rwx

import io.github.rwx.authoritative.AuthoritativeRoomCertificate
import io.github.rwx.net.timeline.TimelineInvitation
import io.github.rwx.net.authoritative.AuthoritativeTls
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

internal fun authoritativeRoomsDirectory(localDirectory: File, portableStateDirectory: String?): File =
    File(portableStateDirectory?.let(::File) ?: localDirectory, "authoritative-rooms")

/** The ordinary menu launches a separate view-only process, even if its old engine is already warm. */
internal class DesktopAuthoritativeRoomLauncher(
    private val directory: File,
    private val command: (List<String>, File) -> List<String> = { args, state -> AuthoritativeProcessCommand.current(args, state) },
    private val start: (List<String>, File) -> Process = { args, log ->
        ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
    },
    private val readyTimeoutMillis: Long = 60_000,
    private val mapCacheLimit: () -> Int = { io.github.rwx.settings.AuthoritativeMapCacheSettings.DEFAULT_LIMIT },
) : AuthoritativeRoomLauncher {
    override fun host(endpoint: String, playerName: String): AuthoritativeHostLaunch {
        val (host, port) = TimelineInvitation.endpoint(endpoint)
        val name = checkedName(playerName)
        val room = newRoomDirectory()
        val tokens = listOf(secret(), secret())
        val password = secret().toCharArray()
        try {
            val certificate = AuthoritativeRoomCertificate.generate(password)
            val store = privateFile(room, "server.p12", certificate.pkcs12)
            val passwordFile = privateFile(room, "tls-password.txt", String(password).toByteArray())
            val seats = privateFile(room, "seats.properties", "0=${tokens[0]}\n1=${tokens[1]}\n".toByteArray())
            val tokenFile = privateFile(room, "host.token", tokens[0].toByteArray())
            val invitation = TimelineInvitation(host, port, AuthoritativeTls.fingerprint(certificate.certificate), tokens[1])
            privateFile(room, "guest.invite", invitation.encode().toByteArray())
            launch(room, listOf("--authority-host", "--seats=$seats", "--token-file=$tokenFile", "--port=$port",
                "--bind=${if (':' in host) "::" else "0.0.0.0"}", "--name=$name", "--tls-keystore=$store", "--tls-password-file=$passwordFile",
                "--record-view=${room.resolve("own.rwxr")}"))
            return AuthoritativeHostLaunch(invitation.encode())
        } finally { password.fill('\u0000') }
    }

    override fun join(invitation: String, playerName: String) {
        val invite = TimelineInvitation.parse(invitation)
        val name = checkedName(playerName)
        val room = newRoomDirectory()
        val token = privateFile(room, "seat.token", invite.token.toByteArray())
        launch(room, listOf("--authority-client", "--connect=${invite.host}", "--port=${invite.port}",
            "--token-file=$token", "--name=$name", "--server-fingerprint=${invite.fingerprint}",
            "--record-view=${room.resolve("own.rwxr")}"))
    }

    private fun launch(room: Path, arguments: List<String>) {
        val ready = room.resolve("ready")
        val process = start(command(arguments + listOf("--ready-file=$ready",
            "--map-cache-dir=${File(directory.parentFile, "cache/authoritative-maps-v7").absolutePath}",
            "--map-cache-limit=${io.github.rwx.settings.AuthoritativeMapCacheSettings.normalize(mapCacheLimit())}"), room.toFile()), room.resolve("client.log").toFile())
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMillis)
            while (process.isAlive && System.nanoTime() < deadline) {
                if (Files.exists(ready) && Files.readString(ready) == "ready\n") return
                Thread.sleep(50)
            }
            error("The new-protocol window did not start. See ${room.resolve("client.log")}")
        } catch (error: Exception) {
            stopAuthoritativeProcess(process)
            throw error
        }
    }

    private fun newRoomDirectory(): Path {
        Files.createDirectories(directory.toPath())
        val parent = directory.toPath()
        return if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
            Files.createTempDirectory(parent, "room-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else Files.createTempDirectory(parent, "room-").also(::ownerOnlyAcl)
    }

    private fun privateFile(directory: Path, name: String, bytes: ByteArray): Path {
        val file = directory.resolve(name)
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else Files.createFile(file).also(::ownerOnlyAcl)
        Files.write(file, bytes, StandardOpenOption.WRITE)
        return file
    }

    private fun ownerOnlyAcl(path: Path) {
        val view = checkNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java)) {
            "Private room credentials require a filesystem with owner permissions"
        }
        view.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
            .setPermissions(AclEntryPermission.entries.toSet()).build())
    }

    private fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    private fun checkedName(name: String): String {
        val value = name.trim()
        require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 128 && value.none { it.isISOControl() }) {
            "Enter a player name of at most 128 UTF-8 bytes"
        }
        return value
    }
}
