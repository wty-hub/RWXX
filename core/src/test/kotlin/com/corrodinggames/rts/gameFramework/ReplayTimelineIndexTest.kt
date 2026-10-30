package com.corrodinggames.rts.gameFramework

import java.io.*
import kotlin.test.*

class ReplayTimelineIndexTest {
    private fun fixture(metadata: Boolean = true): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.writeUTF("rustedWarfareReplay")
        data.writeInt(176); data.writeInt(96); data.writeUTF("1.15"); data.writeBoolean(false)
        fun block(name: String, write: (DataOutputStream) -> Unit) {
            val payload = ByteArrayOutputStream()
            write(DataOutputStream(payload))
            data.writeUTF(name); data.writeInt(payload.size()); data.write(payload.toByteArray())
        }
        block("gamesave") { it.write(ByteArray(100_000)) }
        block("rc") { it.writeInt(20); it.write(ByteArray(30)) }
        block("wait") { it.writeInt(300) }
        block("resync") {
            it.writeInt(300); it.writeInt(301); it.writeInt(10_000)
            it.writeFloat(2f); it.writeFloat(1f); it.writeInt(4); it.writeInt(42)
        }
        block("unknown") { it.write(ByteArray(75)) }
        block("end") {}
        if (metadata) block("endReplayMetaData") {
            it.writeByte(0); it.writeInt(900); it.writeInt(30_000); it.writeInt(1); it.writeInt(1)
        }
        return output.toByteArray()
    }

    @Test fun `indexes duration and authoritative resync times without interpreting payloads`() {
        val bytes = fixture()
        val index = ReplayTimelineIndex.scan(bytes.inputStream()) { false }
        assertEquals(30_000, index.durationMillis)
        assertEquals(900, index.endTick)
        assertNull(index.checkpointBefore(9_999))
        val checkpoint = assertNotNull(index.checkpointBefore(10_000))
        assertEquals(301, checkpoint.tick())
        assertEquals(1, checkpoint.commandsBefore())
        assertEquals(2f, checkpoint.stepRate())
        val data = DataInputStream(bytes.inputStream())
        data.skipNBytes(checkpoint.blockOffset())
        assertEquals("resync", data.readUTF())
        val resumed = DataInputStream(bytes.inputStream())
        resumed.skipNBytes(checkpoint.resumeOffset())
        assertEquals("unknown", resumed.readUTF())
    }

    @Test fun `rejects missing metadata corruption and cancellation`() {
        assertFailsWith<IOException> { ReplayTimelineIndex.scan(fixture(false).inputStream()) { false } }
        assertFailsWith<IOException> { ReplayTimelineIndex.scan(fixture().dropLast(2).toByteArray().inputStream()) { false } }
        assertFailsWith<InterruptedIOException> { ReplayTimelineIndex.scan(fixture().inputStream()) { true } }
        assertFailsWith<IOException> { ReplayTimelineIndex.scan(byteArrayOf(0, 9, 0).inputStream()) { false } }
    }

    @Test fun `scans complete and interrupted replay files including metadata beyond end block`() {
        // Local replays are user data and are not versioned test fixtures. Keep both complete and
        // interrupted recordings under test without assuming how many games the developer recorded.
        val directory = kotlin.io.path.createTempDirectory("replay-index-fixtures-").toFile()
        try {
            File(directory, "complete-a.replay").writeBytes(fixture())
            File(directory, "complete-b.replay").writeBytes(fixture())
            File(directory, "interrupted.replay").writeBytes(fixture(metadata = false))
            val files = directory.listFiles()!!.filter { it.extension == "replay" }
            var indexed = 0
            var incomplete = 0
            files.forEach { file ->
                try {
                    val index = file.inputStream().use { ReplayTimelineIndex.scan(it) { false } }
                    assertEquals(30_000, index.durationMillis, file.name)
                    assertEquals(900, index.endTick, file.name)
                    indexed++
                } catch (error: IOException) {
                    assertEquals("Replay has no end metadata", error.message, file.name)
                    incomplete++
                }
            }
            assertEquals(2, indexed)
            assertEquals(1, incomplete)
        } finally {
            directory.deleteRecursively()
        }
    }
}
