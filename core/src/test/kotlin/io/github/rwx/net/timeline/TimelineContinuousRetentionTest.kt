package io.github.rwx.net.timeline

import java.nio.file.Files
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class TimelineContinuousRetentionTest {
    private val f = TimelineTestFixture
    private val second = 60L * 1024
    private val count = 12

    private fun unit(index: Int, tick: Int): TimelineObservedUnit {
        val phase = tick % 2400
        val distance = if (phase <= 1200) phase else 2400 - phase
        return TimelineObservedUnit(index + 1L, TimelineValue.Vec3(256L + index * 64 + distance * 2,
            256L + index * 128, -1), (index * 97L + tick * 64L) % 65536,
            25600, 25600, (index + tick * 16L) % 65536, 0, "tank", 1, true)
    }

    /** Several full LIVE-window turnovers; synthetic time is deliberately not a wall-clock benchmark. */
    @Test fun `two minutes of changing curves keep bounded raw payload and independently seek all archived history`() {
        val directory = Files.createTempDirectory("timeline-continuous-retention-")
        try {
            val publisher = TimelinePublisher(f.context())
            val handles = HashMap<Long, Int>()
            var archivedThrough = 0L
            var peakRaw = 0L
            var peakPayloadLongs = 0L
            val path = directory.resolve("two-minutes.rwxr")
            TimelineArchive.Writer(path, publisher.context).use { writer ->
                fun commit(state: TimelineState) {
                    for (obj in state.objects.values) if (obj.kind == TimelineObjectKind.UNIT)
                        handles[obj.handle] = assertNotNull(publisher.unitKey(obj.handle)).toInt() - 1
                    if (state.header.sealedThrough > archivedThrough) {
                        assertTrue(archivedThrough >= state.header.retainedFrom, "Unarchived history left LIVE")
                        writer.append(state, archivedThrough, state.header.sealedThrough)
                        archivedThrough = state.header.sealedThrough
                    }
                    assertTrue(state.header.observedThrough - state.header.sealedThrough < 5 * second)
                }
                for (tick in 0..7200) {
                    publisher.observe(TimelineObservation(tick * 1024L, 1000,
                        (f.running() as TimelineValue.Blob).value, f.bytes(0, 0, 0, 0),
                        List(count) { unit(it, tick) }))
                    peakRaw = maxOf(peakRaw, publisher.retainedRawSampleCount)
                    peakPayloadLongs = maxOf(peakPayloadLongs, publisher.retainedRawStorageLongCount)
                    if (tick % 6 == 0) publisher.publish(tick * 1_000_000_000L / 60)?.let(::commit)
                }
                commit(assertNotNull(publisher.publishConfirmed(120_000_000_000L)))
                assertEquals(90 * second, assertNotNull(publisher.latest).header.retainedFrom)
                assertEquals(120 * second, archivedThrough)
                assertEquals(count, handles.size)
                assertTrue(peakRaw <= count * 3L * 362, "Raw samples grew across LIVE window turnovers: $peakRaw")
                // Eight primitive longs per unit/tick (position + heading + progress), allowing
                // the partial head/tail and page rounding. This is not a JVM live-heap assertion.
                assertTrue(peakPayloadLongs <= count * 8L * 448, "Primitive payload grew without bound: $peakPayloadLongs")
                val first = assertNotNull(writer.seekCommitted(0))
                assertEquals(TimelineStreamMode.HISTORY, first.state.context.mode)
                assertTrue(first.readOnly)
                writer.finish()
            }
            TimelineArchive.open(path).use { reader ->
                assertTrue(reader.readOnly)
                assertEquals(0L, reader.index.first().from)
                assertEquals(120 * second, reader.index.last().until)
                for ((left, right) in reader.index.zipWithNext()) assertEquals(left.until, right.from)
                // Every actual input tick is checked against its independent trajectory, after
                // the original LIVE state has discarded three quarters of the game.
                var verifiedTicks = 0
                for (entry in reader.index) {
                    val slice = assertNotNull(reader.seek(entry.from))
                    TimelineCanonical.digest(slice.state)
                    val firstTick = ((entry.from + 1023) / 1024).toInt()
                    val lastTick = ((entry.until - 1) / 1024).toInt()
                    for (tick in firstTick..lastTick) {
                        verify(slice, handles, tick)
                        verifiedTicks++
                    }
                }
                assertEquals(7200, verifiedTicks)
                // Random seeks and reverse traversal use independent file reads, including both
                // sides of each fractional sealing boundary. There is no resident full replay.
                for (entry in reader.index.asReversed()) for (time in listOf(entry.from, entry.until - 1))
                    assertNotNull(reader.seek(time))
                val random = Random(0x7A120)
                repeat(64) {
                    val tick = random.nextInt(7200)
                    verify(assertNotNull(reader.seek(tick * 1024L)), handles, tick)
                }
                assertNull(reader.seek(120 * second))
            }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun verify(slice: TimelineArchive.ReadOnlySlice, handles: Map<Long, Int>, tick: Int) {
        for ((handle, index) in handles) {
            val expected = unit(index, tick)
            val time = tick * 1024L
            val position = assertIs<TimelineValue.Vec3>(slice.sample(handle, 1, time))
            assertTrue(abs(position.x - expected.position.x) <= 64)
            assertTrue(abs(position.y - expected.position.y) <= 64)
            assertTrue(abs(position.z - expected.position.z) <= 64)
            val heading = assertIs<TimelineValue.UInt>(slice.sample(handle, 2, time)).value
            val delta = Math.floorMod(expected.headingQ - heading, 65536L)
            assertTrue(abs(if (delta > 32768) delta - 65536 else delta) <= 182)
            val progress = assertIs<TimelineValue.UInt>(slice.sample(handle, 5, time)).value
            assertTrue(abs(progress - expected.progressQ) <= 1)
        }
    }
}
