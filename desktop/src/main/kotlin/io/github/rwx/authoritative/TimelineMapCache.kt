package io.github.rwx.authoritative

import io.github.rwx.net.timeline.TimelineBinary
import io.github.rwx.net.timeline.TimelineBytes
import io.github.rwx.net.timeline.TimelineCanonical
import io.github.rwx.net.timeline.TimelineProtocol
import io.github.rwx.net.timeline.TimelineRecord
import io.github.rwx.settings.AuthoritativeMapCacheSettings
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime

/** v7 canonical public-map records only. The namespace must be separate from earlier wire caches. */
internal class TimelineMapCache(
    private val directory: File,
    private val maxMaps: Int = AuthoritativeMapCacheSettings.DEFAULT_LIMIT,
) {
    init { require(maxMaps in 0..AuthoritativeMapCacheSettings.MAX_LIMIT) }

    @Synchronized fun load(contentKey: TimelineBytes): TimelineRecord? {
        if (contentKey.size != 32) return null
        if (maxMaps == 0) { prune(); return null }
        val key = hex(contentKey)
        val path = directory.toPath().resolve(key + SUFFIX)
        val result = runCatching {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS))
            require(Files.size(path) in 1L..TimelineProtocol.MAX_BLOB_BYTES.toLong())
            val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(TimelineProtocol.MAX_BLOB_BYTES + 1) }
            require(bytes.size <= TimelineProtocol.MAX_BLOB_BYTES)
            val record = TimelineBinary.decodeRecord(bytes)
            require(TimelineCanonical.mapKey(record) == contentKey)
            record
        }.getOrNull()
        if (result == null) runCatching { Files.deleteIfExists(path) }
        else runCatching { Files.setAttribute(path, "basic:lastModifiedTime", FileTime.fromMillis(System.currentTimeMillis()), NOFOLLOW_LINKS) }
        prune(key.takeIf { result != null })
        return result
    }

    @Synchronized fun store(map: TimelineRecord) {
        if (maxMaps == 0) { prune(); return }
        val key = hex(TimelineCanonical.mapKey(map))
        val bytes = TimelineBinary.encodeRecord(map)
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), "timeline-map-", ".tmp")
        try {
            Files.write(temporary, bytes.toByteArray())
            val destination = directory.toPath().resolve(key + SUFFIX)
            try { Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, destination, REPLACE_EXISTING) }
            prune(key)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun entries(): List<Path> = runCatching {
        Files.newDirectoryStream(directory.toPath()).use { stream -> stream.filter {
            val name = it.fileName.toString()
            name.endsWith(SUFFIX) && KEY.matches(name.removeSuffix(SUFFIX))
        }.toList() }
    }.getOrDefault(emptyList())

    private fun prune(preferredKey: String? = null) {
        entries().sortedWith(compareByDescending<Path> { it.fileName.toString() == preferredKey?.plus(SUFFIX) }
            .thenByDescending { runCatching { Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis() }.getOrDefault(0) }
            .thenBy { it.fileName.toString() }).drop(maxMaps).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun hex(bytes: TimelineBytes) = bytes.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object { const val SUFFIX = ".rwxmap"; val KEY = Regex("[0-9a-f]{64}") }
}
