package io.github.rwx.net.timeline

import kotlin.math.abs
import kotlin.test.*

class TimelinePublisherCompactionTest {
    private val f = TimelineTestFixture
    private val still = TimelineObservedUnit(77, TimelineValue.Vec3(256, 512, -1),
        65520, 25600, 25600, 0, 0, "tank", 1, true)

    private fun observe(tick: Long, unit: TimelineObservedUnit? = still,
                        action: TimelineObservedAction? = null) = TimelineObservation(
        tick * 1024, 1000, (f.running() as TimelineValue.Blob).value, f.bytes(0, 0, 0, 0),
        unit?.let { listOf(it) } ?: emptyList(), action?.let { 77L },
        action?.let { listOf(it) } ?: emptyList())

    @Test fun `long exact constant runs stay bounded but changing samples retain the capacity limit`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observe(0))
        publisher.publish(0)
        for (tick in 1L..8192L) publisher.observe(observe(tick))
        assertEquals(9L, publisher.retainedRawSampleCount)
        // Compression must not bypass validation of the next complete observation.
        assertFailsWith<ProtocolException> { publisher.observe(observe(8193, still.copy(headingQ = 65536))) }
        assertEquals(9L, publisher.retainedRawSampleCount)
        val state = assertNotNull(publisher.publish(200_000_000_000))
        assertMotion(state, 1, 8192, still)
        assertMotion(state, 1, state.header.retainedFrom / 1024, still)

        val changing = TimelinePublisher(f.context())
        for (tick in 0L until 4096L) changing.observe(observe(tick,
            still.copy(position = TimelineValue.Vec3(256 + (tick % 2) * 512, 512, -1))))
        assertEquals(4096L + 3 + 3, changing.retainedRawSampleCount)
        assertFailsWith<ProtocolException> { changing.observe(observe(4096)) }
    }

    @Test fun `same-time corrections preserve the last distinct-time predecessor of a constant run`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observe(0))
        val first = publisher.publish(0)!!
        val firstDigest = TimelineCanonical.digest(first)
        for (tick in 1L..120L) publisher.observe(observe(tick))
        val changed = still.copy(position = TimelineValue.Vec3(4096, 8192, -1024), headingQ = 30000, progressQ = 65535)
        repeat(4) {
            publisher.observe(observe(120, changed))
            publisher.observe(observe(120, still))
        }
        publisher.observe(observe(120, changed))
        val state = publisher.publish(2_000_000_000)!!
        for (tick in 0L..119L) assertMotion(state, 1, tick, still)
        assertMotion(state, 1, 120, changed)
        assertEquals(TimelineValue.Vec3(2176, 4352, -513),
            TimelineProfile.sample(state, 1, 1, 119 * 1024L + 512))
        assertEquals(firstDigest, TimelineCanonical.digest(first))
    }

    @Test fun `stop start reverse wrap and half-turn preserve observed errors and sealed history`() {
        fun at(tick: Long): TimelineObservedUnit = when {
            tick < 30 -> still
            tick < 60 -> (tick - 29).let { n -> still.copy(
                position = TimelineValue.Vec3(256 + n * 32, 512 + n * 16, -1 - n),
                headingQ = (65520 + n * 128) % 65536, progressQ = n * 1000) }
            tick < 90 -> at(59)
            tick < 120 -> (tick - 89).let { n -> still.copy(
                position = TimelineValue.Vec3(1216 - n * 24, 992 - n * 8, -31 + n),
                headingQ = Math.floorMod(3824 - n * 128, 65536L), progressQ = 30000 - n * 1000) }
            tick < 150 -> at(119)
            else -> at(119).copy(position = TimelineValue.Vec3(9000, 9000, -1000),
                headingQ = Math.floorMod(at(119).headingQ + 32768, 65536L), progressQ = 65535)
        }
        val publisher = TimelinePublisher(f.context())
        var previous: TimelineState? = null
        for (tick in 0L..420L) {
            publisher.observe(observe(tick, at(tick)))
            if (tick % 6 != 0L) continue
            val state = publisher.publish(tick * 1_000_000_000L / 60) ?: continue
            for (observed in 0L..state.header.observedThrough / 1024) assertMotion(state, 1, observed, at(observed))
            previous?.let { old ->
                assertTrue(state.header.sealedThrough >= old.header.sealedThrough)
                for (point in 0L until old.header.sealedThrough step 512) for (id in listOf(1, 2, 5)) {
                    assertEquals(TimelineProfile.sample(old, 1, id, point), TimelineProfile.sample(state, 1, id, point))
                }
            }
            previous = state
        }
        assertNotNull(previous)
    }

    @Test fun `raw interpolation at a seal inside a compacted run preserves its constant value`() {
        val publisher = TimelinePublisher(f.context())
        for (tick in 0L..360L) {
            publisher.observe(observe(tick, still.copy(headingQ = (60000 + tick * 64) % 65536)))
            if (tick % 6 == 0L) publisher.publish(tick * 1_000_000_000L / 60)
        }
        val before = publisher.latest!!
        assertTrue(before.header.sealedThrough > 0)
        assertTrue(before.header.sealedThrough < 359 * 1024)
        val moved = still.copy(position = TimelineValue.Vec3(4096, 8192, -1024),
            headingQ = (60000 + 361 * 64) % 65536)
        publisher.observe(observe(361, moved))
        val state = publisher.publish(6_200_000_000)!!
        for (point in before.header.sealedThrough until 361 * 1024L step 512) {
            // The last half tick is the genuine transition between observations 360 and 361.
            if (point <= 360 * 1024) assertEquals(still.position, TimelineProfile.sample(state, 1, 1, point))
        }
        assertMotion(state, 1, 361, moved)
    }

    @Test fun `compacted samples close without crossing hidden time and retention removes the old identity`() {
        val publisher = TimelinePublisher(f.context(), liveWindowSeconds = 1)
        publisher.observe(observe(0))
        val initial = publisher.publish(0)!!
        val digest = TimelineCanonical.digest(initial)
        for (tick in 1L..179L) publisher.observe(observe(tick))
        publisher.observe(observe(180, null))
        val closed = publisher.publish(3_000_000_000)!!
        assertEquals(0L, publisher.retainedRawSampleCount)
        assertEquals(still.position, TimelineProfile.sample(closed, 1, 1, 179 * 1024L + 512))
        assertNull(TimelineProfile.sample(closed, 1, 1, 180 * 1024L))
        assertNull(publisher.unitKey(1))

        publisher.observe(observe(181, still.copy(position = TimelineValue.Vec3(1024, 1024, -1))))
        val returned = publisher.publish(3_200_000_000)!!
        assertEquals(77L, publisher.unitKey(2))
        assertEquals(181 * 1024L, returned.objects.getValue(2).observedFrom)
        assertNull(TimelineProfile.sample(returned, 2, 1, 180 * 1024L))
        for (tick in 182L..302L) publisher.observe(observe(tick,
            still.copy(position = TimelineValue.Vec3(1024, 1024, -1))))
        val retained = publisher.publish(5_200_000_000)!!
        assertFalse(retained.objects.containsKey(1))
        assertMotion(retained, 2, 302, still.copy(position = TimelineValue.Vec3(1024, 1024, -1)))
        assertTrue(publisher.retainedRawSampleCount <= 9)
        assertEquals(digest, TimelineCanonical.digest(initial))
    }

    @Test fun `nullable action progress clears a compacted run and resumes after its real gap`() {
        val ability = TimelineRecord(listOf(
            TimelineField(1, true, TimelineTlvValue.U32(2)), TimelineField(2, true, TimelineTlvValue.U32(0)),
            TimelineField(3, true, TimelineTlvValue.Bool(false)), TimelineField(4, true, TimelineTlvValue.Text("action.tank")),
            TimelineField(5, true, TimelineTlvValue.Text("tank")), TimelineField(6, true, TimelineTlvValue.I64(100)),
            TimelineField(7, true, TimelineTlvValue.U32(1))))
        val action = TimelineObservedAction(91, ability, 1, 0, -1, 100, false)
        val publisher = TimelinePublisher(f.context())
        for (tick in 0L..20L) publisher.observe(observe(tick, action = action))
        val first = publisher.publish(0)!!
        val handle = first.objects.values.single { it.kind == TimelineObjectKind.ACTION }.handle
        assertEquals(12L, publisher.retainedRawSampleCount)
        publisher.observe(observe(21, action = action.copy(progressQ = null)))
        val gap = publisher.publish(200_000_000)!!
        assertEquals(9L, publisher.retainedRawSampleCount)
        assertEquals(TimelineValue.UInt(100), TimelineProfile.sample(gap, handle, 4, 20 * 1024L + 512))
        assertNull(TimelineProfile.sample(gap, handle, 4, 21 * 1024L))
        publisher.observe(observe(22, action = action.copy(progressQ = 200)))
        val resumed = publisher.publish(400_000_000)!!
        assertEquals(10L, publisher.retainedRawSampleCount)
        assertNull(TimelineProfile.sample(resumed, handle, 4, 21 * 1024L + 512))
        assertEquals(TimelineValue.UInt(200), TimelineProfile.sample(resumed, handle, 4, 22 * 1024L))
    }

    private fun assertMotion(state: TimelineState, handle: Long, tick: Long, expected: TimelineObservedUnit) {
        val point = tick * 1024
        val position = TimelineProfile.sample(state, handle, 1, point) as TimelineValue.Vec3
        assertTrue(abs(position.x - expected.position.x) <= 64, "x at $tick: $position vs ${expected.position}")
        assertTrue(abs(position.y - expected.position.y) <= 64, "y at $tick: $position vs ${expected.position}")
        assertTrue(abs(position.z - expected.position.z) <= 64, "z at $tick: $position vs ${expected.position}")
        val angle = (TimelineProfile.sample(state, handle, 2, point) as TimelineValue.UInt).value
        val angleError = Math.floorMod(angle - expected.headingQ + 32768, 65536L) - 32768
        assertTrue(abs(angleError) <= 182, "heading at $tick: $angle vs ${expected.headingQ}")
        val progress = (TimelineProfile.sample(state, handle, 5, point) as TimelineValue.UInt).value
        assertTrue(abs(progress - expected.progressQ) <= 1, "progress at $tick: $progress vs ${expected.progressQ}")
    }
}
