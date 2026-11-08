package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineArchiveTest {
    private val f = TimelineTestFixture

    private fun source(): TimelineState {
        val store = TimelineStore(f.context())
        fun tail(value: Long) = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(value))))
        val operations = f.root() + f.unit() + listOf(
            TimelineOperation.Splice(1, 1, 1, 0, 100, listOf(TimelineSegment.Interpolated(0, 100, false, 0, 100,
                TimelineValue.Vec3(0, 0, 0), TimelineValue.Vec3(5120, 2560, -100)))),
            TimelineOperation.Create(2, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(2, 65536, TimelineMode.PULSE, TimelineValueType.UINT, false, 2, tail(40)),
            TimelineOperation.Pulse(2, 65536, 0, 0, 100, listOf(TimelinePulse(30, 1, TimelineValue.UInt(7)), TimelinePulse(70, 2, TimelineValue.UInt(9)))),
            TimelineOperation.Close(2, 80, TimelineCloseKind.ENDED),
            TimelineOperation.Create(3, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(3, 65536, TimelineMode.PULSE, TimelineValueType.UINT, false, 1, tail(0)),
            TimelineOperation.Pulse(3, 65536, 0, 0, 100, listOf(TimelinePulse(50, 1, TimelineValue.UInt(11)))),
            TimelineOperation.Close(3, 51, TimelineCloseKind.ENDED),
        ) + f.unit(4, from = 10, team = 1) + TimelineOperation.Close(4, 20, TimelineCloseKind.ENDED)
        return f.commit(store, operations, observed = 100, sealed = 100, highWater = 4, control = 7)
    }

    private inline fun temporary(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("timeline-archive-test-")
        try { block(directory) }
        finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }

    private fun write(path: Path, source: TimelineState = source(), windows: List<Pair<Long, Long>> = listOf(0L to 50L, 50L to 100L)) {
        TimelineArchive.Writer(path, source.context).use { writer ->
            windows.forEach { (from, until) -> writer.append(source, from, until) }
            assertFalse(Files.exists(path), "Unfinished content must not be published as the final archive")
            writer.finish()
            assertTrue(writer.completed)
        }
    }

    @Test fun `online committed seek does not wait for a later append and never publishes unfinished path`() = temporary { directory ->
        val source = source(); val path = directory.resolve("online.rwxr")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            TimelineArchive.Writer(path, source.context).use { writer ->
                writer.append(source, 0, 50)
                assertEquals(50L, writer.committedUntil); assertEquals(1, writer.committedEntries)
                writer.beforeAppendWrite = { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
                val appending = workers.submit<TimelineArchive.IndexEntry> { writer.append(source, 50, 100) }
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val read = workers.submit<TimelineArchive.ReadOnlySlice?> { writer.seekCommitted(25) }
                    assertEquals(TimelineProfile.sample(source, 1, 1, 25), assertNotNull(read.get(2, TimeUnit.SECONDS)).sample(1, 1, 25))
                    assertNull(writer.seekCommitted(75), "Uncommitted later bytes cannot enter the readable index")
                    assertFalse(Files.exists(path))
                } finally { release.countDown() }
                appending.get(2, TimeUnit.SECONDS); writer.beforeAppendWrite = null
                assertEquals(100L, writer.committedUntil); assertEquals(2, writer.committedEntries)
                assertNotNull(writer.seekCommitted(75))
                writer.finish()
                assertNotNull(writer.seekCommitted(25), "Committed reads remain available after atomic publication")
            }
            TimelineArchive.open(path).use { assertEquals(2, it.index.size) }
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun `online selected entry is revalidated against committed digest`() = temporary { directory ->
        val source = source(); val path = directory.resolve("online-corrupt.rwxr")
        TimelineArchive.Writer(path, source.context).use { writer ->
            val entry = writer.append(source, 0, 50)
            val partial = Files.list(directory).use { paths -> paths.filter { it.fileName.toString().endsWith(".partial") }.findFirst().orElseThrow() }
            java.io.RandomAccessFile(partial.toFile(), "rw").use { file ->
                file.seek(entry.fileOffset + entry.recordBytes - 1); val value = file.read()
                file.seek(entry.fileOffset + entry.recordBytes - 1); file.write(value xor 1)
            }
            assertFailsWith<ProtocolException> { writer.seekCommitted(25) }
        }
        assertFalse(Files.exists(path))
    }

    @Test fun `online fixed history merges slice boundaries without changing anchors or duplicating carry in pulses`() = temporary { directory ->
        val source = source(); val path = directory.resolve("window.rwxr")
        TimelineArchive.Writer(path, source.context).use { writer ->
            writer.append(source, 0, 50)
            assertNull(writer.readCommitted(25, 75), "A partial requested interval is never published")
            writer.append(source, 50, 100)
            val merged = assertNotNull(writer.readCommitted(25, 95))
            assertEquals(25L, merged.from); assertEquals(95L, merged.until)
            assertEquals(0L, merged.state.header.controlEpoch)
            for (time in 25L until 95) {
                for (handle in listOf(0L, 1L, 2L, 3L, 4L)) for (id in listOf(1, 2, 3, 65536))
                    assertEquals(TimelineProfile.sample(source, handle, id, time), merged.sample(handle, id, time))
            }
            val position = merged.state.objects.getValue(1).curves.getValue(1).segments.single() as TimelineSegment.Interpolated
            assertEquals(0L, position.anchor0Time); assertEquals(100L, position.anchor1Time)
            assertEquals(25L, position.lo); assertEquals(95L, position.hi)
            val pulses = merged.state.objects.getValue(2).curves.getValue(65536)
            assertEquals(listOf(1L, 2L), pulses.events.map { it.eventId })
            assertEquals(2L, pulses.eventHighWater)
            assertFailsWith<ProtocolException> { writer.readCommitted(25, 95, maxBytes = 32) }
            assertFalse(Files.exists(path))
        }
    }

    @Test fun `online fixed history rejects index gaps and pregrant requests`() = temporary { directory ->
        val source = source(); val path = directory.resolve("gapped-online.rwxr")
        TimelineArchive.Writer(path, source.context).use { writer ->
            writer.append(source, 0, 40); writer.append(source, 60, 100)
            assertNull(writer.readCommitted(20, 80))
            assertNotNull(writer.readCommitted(60, 80))
            assertFailsWith<ProtocolException> { writer.readCommitted(-1, 20) }
            assertFailsWith<ProtocolException> { writer.readCommitted(20, 20) }
        }
    }

    @Test fun `random forward reverse and repeated seek match the authorized source without prior playback`() = temporary { directory ->
        val source = source()
        val path = directory.resolve("complete.rwxr")
        write(path, source)
        TimelineArchive.open(path).use { archive ->
            assertTrue(archive.readOnly)
            assertEquals(2, archive.index.size)
            val times = (0L until 100).shuffled(Random(7)) + (99L downTo 0L) + listOf(49L, 50L, 49L, 50L)
            for (time in times) {
                val slice = assertNotNull(archive.seek(time))
                assertTrue(slice.readOnly)
                assertEquals(TimelineStreamMode.HISTORY, slice.state.context.mode)
                assertEquals(0L, slice.state.header.controlEpoch)
                assertEquals(7L, slice.archivedControlEpoch)
                for (id in listOf(1, 2, 3, 7, 8)) assertEquals(TimelineProfile.sample(source, 1, id, time), slice.sample(1, id, time))
                assertEquals(TimelineProfile.sample(source, 0, 1, time), slice.sample(0, 1, time))
            }
            assertNull(archive.seek(100))
            val first = assertNotNull(archive.seek(25))
            val second = assertNotNull(archive.seek(75))
            val firstPosition = first.state.objects.getValue(1).curves.getValue(1)
            val secondPosition = second.state.objects.getValue(1).curves.getValue(1)
            assertEquals(1L, firstPosition.curveRevision, "Source revision 2 must be independently reset")
            assertEquals(1L, secondPosition.curveRevision)
            val clipped = secondPosition.segments.single() as TimelineSegment.Interpolated
            assertEquals(50L, clipped.lo); assertEquals(100L, clipped.hi)
            assertEquals(0L, clipped.anchor0Time); assertEquals(100L, clipped.anchor1Time)
            assertFalse(second.state.objects.containsKey(4), "Unrelated expired observations need not be copied")
        }
    }

    @Test fun `carry in tails keep closed observations and zero tail events at the new floor are retained`() = temporary { directory ->
        val path = directory.resolve("tails.rwxr")
        write(path)
        TimelineArchive.open(path).use { archive ->
            val first = assertNotNull(archive.seek(49))
            val second = assertNotNull(archive.seek(50))
            assertEquals(listOf(30L), first.state.objects.getValue(2).curves.getValue(65536).events.map { it.time })
            assertEquals(listOf(30L, 70L), second.state.objects.getValue(2).curves.getValue(65536).events.map { it.time })
            assertEquals(50L, second.state.objects.getValue(3).curves.getValue(65536).events.single().time)
        }
        val later = directory.resolve("later.rwxr")
        write(later, windows = listOf(80L to 100L))
        TimelineArchive.open(later).use { archive ->
            val slice = assertNotNull(archive.seek(90))
            val visual = slice.state.objects.getValue(2)
            assertEquals(80L, visual.close!!.until)
            assertEquals(listOf(70L), visual.curves.getValue(65536).events.map { it.time })
            assertFalse(slice.state.objects.containsKey(3))
        }
    }

    @Test fun `gaps remain unavailable and authorization starts survive the self contained header`() = temporary { directory ->
        val gaps = directory.resolve("gaps.rwxr")
        write(gaps, windows = listOf(0L to 40L, 60L to 100L))
        TimelineArchive.open(gaps).use { archive ->
            assertNotNull(archive.seek(39)); assertNull(archive.seek(40)); assertNull(archive.seek(59)); assertNotNull(archive.seek(60))
        }
        val context = f.context().copy(joinedAt = 10, historyFrom = 10)
        val store = TimelineStore(context)
        val source = f.commit(store, f.root(from = 10), observed = 100, sealed = 100, from = 10, highWater = 0, control = 3)
        val late = directory.resolve("late.rwxr")
        write(late, source, listOf(10L to 20L))
        TimelineArchive.open(late).use { archive ->
            assertEquals(context.accessId, archive.context.accessId)
            assertEquals(10L, archive.context.joinedAt); assertEquals(10L, archive.context.historyFrom)
            assertNull(archive.seek(9)); assertNotNull(archive.seek(10))
        }
        val refused = directory.resolve("before-join.rwxr")
        TimelineArchive.Writer(refused, context).use { writer ->
            assertFailsWith<ProtocolException> { writer.append(source, 0, 20) }
            assertFailsWith<ProtocolException> { writer.finish() }
        }
        assertFalse(Files.exists(refused))
    }

    @Test fun `unfinished failed and raced writers never overwrite or publish an invalid archive`() = temporary { directory ->
        val source = source()
        val aborted = directory.resolve("aborted.rwxr")
        TimelineArchive.Writer(aborted, source.context).use { it.append(source, 0, 50) }
        assertFalse(Files.exists(aborted))
        val failed = directory.resolve("failed.rwxr")
        TimelineArchive.Writer(failed, source.context).use { writer ->
            assertFailsWith<ProtocolException> { writer.append(source, 0, 101) }
            assertFailsWith<ProtocolException> { writer.finish() }
        }
        assertFalse(Files.exists(failed))
        val old = directory.resolve("old.rwxr")
        Files.write(old, byteArrayOf(1, 2, 3))
        assertFailsWith<ProtocolException> { TimelineArchive.Writer(old, source.context) }
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(old))
        val race = directory.resolve("raced.rwxr")
        TimelineArchive.Writer(race, source.context).use { writer ->
            writer.append(source, 0, 50)
            Files.write(race, byteArrayOf(9, 8, 7))
            assertFailsWith<FileAlreadyExistsException> { writer.finish() }
        }
        assertContentEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(race))
        Files.list(directory).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".partial") }) }
    }

    private data class Record(val offset: Int, val length: Int, val type: Int) {
        val payload: Int get() = offset + 5
        val end: Int get() = offset + length + 4
    }
    private fun records(bytes: ByteArray): List<Record> {
        val data = ByteBuffer.wrap(bytes)
        val mapLengthOffset = 12 + data.getInt(8)
        var at = mapLengthOffset + 8 + data.getLong(mapLengthOffset).toInt()
        val result = ArrayList<Record>()
        while (at < bytes.size) {
            val record = Record(at, data.getInt(at), bytes[at + 4].toInt() and 255)
            result.add(record); at = record.end
        }
        return result
    }
    private fun rawRecord(type: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().let { buffer ->
        DataOutputStream(buffer).apply { writeInt(payload.size + 1); writeByte(type); write(payload) }
        buffer.toByteArray()
    }
    private fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun rewriteIndex(bytes: ByteArray, transform: (List<ByteArray>) -> List<ByteArray>): ByteArray {
        val index = records(bytes).single { it.type == 2 }
        val count = ByteBuffer.wrap(bytes).getInt(index.payload)
        val rows = (0 until count).map { bytes.copyOfRange(index.payload + 4 + it * 60, index.payload + 4 + (it + 1) * 60) }
        val changed = transform(rows)
        val payload = ByteArrayOutputStream().let { buffer ->
            DataOutputStream(buffer).apply { writeInt(changed.size); changed.forEach { write(it) } }; buffer.toByteArray()
        }
        val newIndex = rawRecord(2, payload)
        val completion = ByteBuffer.allocate(40).putLong(index.offset.toLong()).put(sha(newIndex)).array()
        return bytes.copyOfRange(0, index.offset) + newIndex + rawRecord(3, completion)
    }

    @Test fun `fully rehashed indexes cannot omit duplicate reorder or point away from actual slices`() = temporary { directory ->
        val valid = directory.resolve("valid.rwxr")
        write(valid)
        val raw = Files.readAllBytes(valid)
        val transforms: List<(List<ByteArray>) -> List<ByteArray>> = listOf(
            { rows -> rows.take(1) },
            { rows -> listOf(rows[0], rows[0]) },
            { rows -> rows.reversed() },
            { rows -> rows.mapIndexed { index, row -> row.copyOf().also { if (index == 0) ByteBuffer.wrap(it).putLong(16, 0) } } },
            { rows -> rows.mapIndexed { index, row -> row.copyOf().also { if (index == 0) ByteBuffer.wrap(it).putInt(24, ByteBuffer.wrap(it).getInt(24) + 1) } } },
            { rows -> rows.mapIndexed { index, row -> row.copyOf().also { if (index == 0) it[28] = (it[28].toInt() xor 1).toByte() } } },
        )
        transforms.forEachIndexed { index, transform ->
            val malformed = directory.resolve("index-$index.rwxr")
            Files.write(malformed, rewriteIndex(raw, transform))
            assertFailsWith<ProtocolException> { TimelineArchive.open(malformed).close() }
        }
        val duplicate = directory.resolve("duplicate-index.rwxr")
        val index = records(raw).single { it.type == 2 }
        Files.write(duplicate, raw.copyOfRange(0, index.end) + raw.copyOfRange(index.offset, raw.size))
        assertFailsWith<ProtocolException> { TimelineArchive.open(duplicate).close() }
    }

    @Test fun `header map versions truncation unknown records completion and trailing bytes all fail closed`() = temporary { directory ->
        val valid = directory.resolve("valid.rwxr")
        write(valid)
        val raw = Files.readAllBytes(valid)
        val parsed = records(raw)
        val mapLengthOffset = 12 + ByteBuffer.wrap(raw).getInt(8)
        val mapOffset = mapLengthOffset + 8
        val mapLength = ByteBuffer.wrap(raw).getLong(mapLengthOffset).toInt()
        val originalMap = TimelineBinary.decodeRecord(raw.copyOfRange(mapOffset, mapOffset + mapLength))
        val changedMap = TimelineRecord(originalMap.fields.map { field ->
            if (field.id == 6) {
                val terrain = (field.value as TimelineTlvValue.Bytes).value.toByteArray().also { it[0] = 1 }
                field.copy(value = TimelineTlvValue.Bytes(TimelineBytes(terrain)))
            } else field
        })
        TimelineBinary.validateMapRecord(changedMap)
        val damagedMap = raw.copyOf().also { TimelineBinary.encodeRecord(changedMap).toByteArray().copyInto(it, mapOffset) }
        val variants = listOf(
            raw.copyOf().also { ByteBuffer.wrap(it).putShort(6, 6) },
            raw.copyOf().also { ByteBuffer.wrap(it).putShort(4, 2) },
            raw.copyOf(8), raw.copyOf(raw.size - 1), raw.copyOf(parsed.last().offset),
            raw.copyOf(parsed.single { it.type == 2 }.offset), raw + byteArrayOf(0), damagedMap,
            raw.copyOf().also { it[parsed.first().offset + 4] = 99 },
            raw.copyOf().also { ByteBuffer.wrap(it).putInt(parsed.first().offset, Int.MAX_VALUE) },
            raw.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
        )
        variants.forEachIndexed { index, bytes ->
            val malformed = directory.resolve("malformed-$index.rwxr")
            Files.write(malformed, bytes)
            assertFailsWith<ProtocolException>("variant $index") { TimelineArchive.open(malformed).close() }
        }
    }

    private fun rewriteSlice(bytes: ByteArray, change: (TimelineRecord) -> TimelineRecord): ByteArray {
        val slice = records(bytes).first { it.type == 1 }
        val payload = bytes.copyOfRange(slice.payload, slice.end)
        val changed = TimelineBinary.encodeRecord(change(TimelineBinary.decodeRecord(payload))).toByteArray()
        assertEquals(payload.size, changed.size)
        val result = bytes.copyOf()
        changed.copyInto(result, slice.payload)
        return rewriteIndex(result) { rows -> rows.mapIndexed { index, row -> row.copyOf().also { updated ->
            if (index == 0) sha(result.copyOfRange(slice.offset, slice.end)).copyInto(updated, 28)
        } } }
    }

    @Test fun `slice inner checksum is independent of index and lazy seek still runs full Store validation`() = temporary { directory ->
        val valid = directory.resolve("valid.rwxr")
        write(valid)
        val raw = Files.readAllBytes(valid)
        val badInner = rewriteSlice(raw) { record -> TimelineRecord(record.fields.map { field ->
            if (field.id == 5) {
                val bytes = (field.value as TimelineTlvValue.Bytes).value.toByteArray()
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                field.copy(value = TimelineTlvValue.Bytes(TimelineBytes(bytes)))
            } else field
        }) }
        val badInnerPath = directory.resolve("bad-inner.rwxr")
        Files.write(badInnerPath, badInner)
        assertFailsWith<ProtocolException> { TimelineArchive.open(badInnerPath).close() }

        val invalidState = rewriteSlice(raw) { record ->
            val fields = record.fields.map { if (it.id == 7) it.copy(value = TimelineTlvValue.U64(0)) else it }.toMutableList()
            fun n(id: Int) = (fields.first { it.id == id }.value as TimelineTlvValue.U64).value
            val operations = (fields.first { it.id == 5 }.value as TimelineTlvValue.Bytes).value
            val input = ByteArrayOutputStream().let { out ->
                DataOutputStream(out).apply { writeLong(n(1)); writeLong(n(2)); writeLong(n(3)); writeLong(n(4)); writeLong(n(7)); write(operations.toByteArray()) }
                out.toByteArray()
            }
            val index = fields.indexOfFirst { it.id == 6 }
            fields[index] = fields[index].copy(value = TimelineTlvValue.Bytes(TimelineBytes(sha(input))))
            TimelineRecord(fields)
        }
        val invalidStatePath = directory.resolve("bad-state.rwxr")
        Files.write(invalidStatePath, invalidState)
        TimelineArchive.open(invalidStatePath).use { reader ->
            assertFailsWith<ProtocolException> { reader.seek(25) }
        }
    }

    @Test fun `seek rechecks selected disk bytes after open instead of trusting a stale index`() = temporary { directory ->
        val path = directory.resolve("changed.rwxr")
        write(path)
        TimelineArchive.open(path).use { reader ->
            val bytes = Files.readAllBytes(path)
            val first = records(bytes).first { it.type == 1 }
            bytes[first.end - 1] = (bytes[first.end - 1].toInt() xor 1).toByte()
            Files.write(path, bytes)
            assertFailsWith<ProtocolException> { reader.seek(25) }
        }
    }
}
