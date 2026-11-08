package io.github.rwx.net.timeline

import kotlin.random.Random
import kotlin.test.*

class TimelineEncoderOrderingTest {
    private val f = TimelineTestFixture

    private fun reordered(state: TimelineState, seed: Int): TimelineState {
        val random = Random(seed)
        val objects = state.objects.entries.shuffled(random).associate { (handle, obj) ->
            handle to obj.copy(curves = obj.curves.entries.shuffled(random).associate { it.toPair() })
        }
        return state.copy(objects = objects)
    }

    @Test fun `RESET bytes use natural order regardless of immutable map insertion order`() {
        val source = TimelineStore(f.context())
        val state = f.commit(source, f.root() + (1L..8L).flatMap { f.unit(it) }, sealed = 5, highWater = 8)
        fun encode(desired: TimelineState) = assertNotNull(TimelineEncoder(clock = { 0 }).encode(
            TimelineSendCursor(desired.context, 7, 1, null, true), desired))
        val expected = encode(state)
        repeat(12) { seed ->
            val shuffled = reordered(state, seed)
            val publication = encode(shuffled)
            assertEquals(expected.header, publication.header)
            assertContentEquals(expected.operations.toByteArray(), publication.operations.toByteArray())
            assertEquals(TimelineEncoder.resetOperations(state), TimelineEncoder.resetOperations(shuffled))
            val received = TimelineStore(state.context).apply(publication.header, publication.operations)
            assertEquals(TimelineCanonical.digest(received), TimelineCanonical.digest(
                TimelineStore(state.context).apply(expected.header, expected.operations)))
        }
    }

    @Test fun `UPDATE bytes preserve modification creation declaration and closure order`() {
        val source = TimelineStore(f.context())
        val initial = f.commit(source, f.root() + (1L..3L).flatMap { f.unit(it) }, sealed = 5, highWater = 3)
        val desired = f.commit(source, listOf(
            f.step(1, 3, TimelineValue.SInt(40), 5, revision = 1),
            TimelineOperation.Declare(1, 65536, TimelineMode.STEP, TimelineValueType.SINT, false),
            f.step(1, 65536, TimelineValue.SInt(22), 5),
            TimelineOperation.Close(2, 7, TimelineCloseKind.ENDED)) + f.unit(4, from = 5),
            observed = 11, sealed = 5, highWater = 4, control = 2)

        fun encode(before: TimelineState, target: TimelineState): Pair<TimelinePublication, TimelineState> {
            val encoder = TimelineEncoder(clock = { 0 })
            val receiver = TimelineStore(initial.context)
            val reset = assertNotNull(encoder.encode(TimelineSendCursor(initial.context, 7, 1, null, true), before))
            val committed = receiver.apply(reset.header, reset.operations)
            val update = assertNotNull(encoder.encode(TimelineSendCursor(initial.context, 7, 1, committed, false), target))
            return update to receiver.apply(update.header, update.operations)
        }
        val (expected, state) = encode(initial, desired)
        repeat(12) { seed ->
            val (publication, received) = encode(reordered(initial, seed), reordered(desired, seed + 30))
            assertEquals(expected.header, publication.header)
            assertContentEquals(expected.operations.toByteArray(), publication.operations.toByteArray())
            assertEquals(TimelineCanonical.persistentSize(state), TimelineCanonical.persistentSize(received))
            assertEquals(TimelineCanonical.digest(state), TimelineCanonical.digest(received))
            assertEquals(TimelineValue.SInt(40), TimelineProfile.sample(received, 1, 3, 10))
            assertEquals(TimelineValue.SInt(22), TimelineProfile.sample(received, 1, 65536, 10))
            assertNull(TimelineProfile.sample(received, 2, 1, 7))
            assertNotNull(TimelineProfile.sample(received, 4, 1, 5))
        }
    }
}
