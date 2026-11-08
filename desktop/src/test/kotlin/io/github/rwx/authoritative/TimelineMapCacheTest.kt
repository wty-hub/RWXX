package io.github.rwx.authoritative

import io.github.rwx.net.timeline.*
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineMapCacheTest {
    private fun map(terrain: Byte = 0): TimelineRecord {
        fun n(id: Int, value: Long) = TimelineMessages.numberField(id, value, true)
        fun b(id: Int, vararg value: Byte) = TimelineMessages.bytesField(id, TimelineBytes(value))
        return TimelineRecord(listOf(n(1, 1), n(2, 1), n(3, 1), n(4, 20), n(5, 20), b(6, terrain),
            n(7, 1), b(8, 0, 0), b(9, 0, 0), b(10, 0)))
    }
    private fun name(key: TimelineBytes) = key.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) } + ".rwxmap"

    @Test fun `persist only canonical map and verify its key on another cache instance`() {
        val directory = Files.createTempDirectory("timeline-cache-").toFile()
        try {
            val map = map(); val key = TimelineCanonical.mapKey(map)
            TimelineMapCache(directory).store(map)
            assertEquals(TimelineBinary.encodeRecord(map), TimelineBytes(directory.resolve(name(key)).readBytes()))
            assertEquals(map, TimelineMapCache(directory).load(key))
            assertNull(TimelineMapCache(directory).load(TimelineBytes(ByteArray(31))))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `corruption wrong content key and old framed content never count as cache hits`() {
        val directory = Files.createTempDirectory("timeline-cache-").toFile()
        try {
            val map = map(); val key = TimelineCanonical.mapKey(map); val file = directory.resolve(name(key))
            for (invalid in listOf(TimelineBinary.encodeRecord(map(1)).toByteArray(), byteArrayOf(0, 0, 0, 9, 82, 87, 88, 65, 0, 0, 0, 6, 2),
                TimelineBinary.encodeRecord(map).toByteArray().dropLast(1).toByteArray())) {
                file.writeBytes(invalid)
                assertNull(TimelineMapCache(directory).load(key))
                assertFalse(file.exists())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `zero limit clears only owned cache entries and never saves a new map`() {
        val directory = Files.createTempDirectory("timeline-cache-").toFile()
        try {
            val map = map(); val key = TimelineCanonical.mapKey(map)
            TimelineMapCache(directory).store(map)
            directory.resolve("notes.txt").writeText("keep")
            directory.resolve("foreign.rwxmap").writeText("keep")
            val disabled = TimelineMapCache(directory, 0)
            assertNull(disabled.load(key)); disabled.store(map)
            assertFalse(directory.resolve(name(key)).exists())
            assertEquals("keep", directory.resolve("notes.txt").readText())
            assertEquals("keep", directory.resolve("foreign.rwxmap").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `count limit evicts older maps and preserves successfully written map`() {
        val directory = Files.createTempDirectory("timeline-cache-").toFile()
        try {
            val first = map(); val second = map(1); val cache = TimelineMapCache(directory, 1)
            cache.store(first); cache.store(second)
            assertNull(cache.load(TimelineCanonical.mapKey(first)))
            assertEquals(second, cache.load(TimelineCanonical.mapKey(second)))
            assertEquals(1, directory.listFiles()!!.count { it.name.endsWith(".rwxmap") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun `symlink entry does not read or touch its external target`() {
        val directory = Files.createTempDirectory("timeline-cache-").toFile()
        val external = Files.createTempFile("timeline-cache-external-", ".txt")
        try {
            val map = map(); val key = TimelineCanonical.mapKey(map)
            Files.writeString(external, "unchanged")
            Files.setLastModifiedTime(external, FileTime.fromMillis(1000))
            val link = directory.toPath().resolve(name(key))
            Files.createSymbolicLink(link, external)
            assertNull(TimelineMapCache(directory).load(key))
            assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(external))
            assertEquals("unchanged", Files.readString(external))
            assertEquals(1000L, Files.getLastModifiedTime(external).toMillis())
        } finally { directory.deleteRecursively(); Files.deleteIfExists(external) }
    }
}
