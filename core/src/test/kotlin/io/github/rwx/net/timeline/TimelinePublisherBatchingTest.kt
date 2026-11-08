package io.github.rwx.net.timeline

import kotlin.math.abs
import kotlin.test.*

class TimelinePublisherBatchingTest {
    private val f = TimelineTestFixture
    private val second = 60L * 1024
    private val still = TimelineObservedUnit(77, TimelineValue.Vec3(256, 512, -1), 65000,
        25600, 25600, 0, 0, "tank", 1, true)
    private fun unit(tick: Long) = still.copy(position = TimelineValue.Vec3(256 + tick, 512, -1),
        headingQ = (65000 + tick * 64) % 65536, progressQ = tick)
    private fun observation(tick: Long, value: TimelineObservedUnit = unit(tick)) =
        TimelineObservation(tick * 1024, 1000, (f.running() as TimelineValue.Blob).value, f.bytes(0, 0, 0, 0), listOf(value))
    private fun nanos(tick: Long) = tick * 1_000_000_000L / 60
    private fun prefix(curve: TimelineCurve, from: Long, until: Long) = curve.segments.mapNotNull {
        val lo = maxOf(from, it.lo); val hi = minOf(until, it.hi)
        if (lo < hi) it.clipped(lo, hi) else null
    }

    @Test fun `thirty five seconds of continuous motion keep bounded batch records and exact sealed anchors`() {
        val publisher = TimelinePublisher(f.context())
        var previous: TimelineState? = null
        var publications = 0
        var maximumUnitRecords = 0
        for (tick in 0L..2100L) {
            publisher.observe(observation(tick))
            if (tick % 6 != 0L) continue
            val state = publisher.publish(nanos(tick)) ?: continue
            publications++
            assertTrue(state.header.observedThrough - state.header.sealedThrough < second * 5)
            val obj = state.objects.getValue(1)
            maximumUnitRecords = maxOf(maximumUnitRecords, obj.curves.values.sumOf { it.segments.size })
            for (id in listOf(1, 2, 5)) {
                val curve = obj.curves.getValue(id)
                assertTrue(curve.segments.size <= 8, "Attribute $id retained too many stable-motion segments")
                curve.segments.filter { it.predicted }.forEach {
                    it as TimelineSegment.Interpolated
                    assertTrue(it.lo >= state.header.sealedThrough)
                    assertTrue(it.anchor1Time <= state.header.observedThrough + second)
                }
                previous?.let { old ->
                    assertEquals(prefix(old.objects.getValue(1).curves.getValue(id), state.header.retainedFrom, old.header.sealedThrough),
                        prefix(curve, state.header.retainedFrom, old.header.sealedThrough), "Sealed anchors changed")
                }
            }
            previous = state
        }
        val state = assertNotNull(publisher.publishConfirmed(nanos(2100)))
        val beforeFinish = assertNotNull(previous)
        for (id in listOf(1, 2, 5)) assertEquals(
            prefix(beforeFinish.objects.getValue(1).curves.getValue(id), state.header.retainedFrom, beforeFinish.header.sealedThrough),
            prefix(state.objects.getValue(1).curves.getValue(id), state.header.retainedFrom, beforeFinish.header.sealedThrough))
        assertEquals(35 * second, state.header.observedThrough)
        assertEquals(5 * second, state.header.retainedFrom)
        maximumUnitRecords = maxOf(maximumUnitRecords, state.objects.getValue(1).curves.values.sumOf { it.segments.size })
        assertTrue(maximumUnitRecords <= 30)
        assertTrue(publications < 80, "Stable curves should not require all 351 publication slots")
        assertTrue(publisher.retainedRawSampleCount <= 3L * 362)
        for (tick in 300L..2100L) {
            val expected = unit(tick)
            assertEquals(expected.position, TimelineProfile.sample(state, 1, 1, tick * 1024))
            assertEquals(TimelineValue.UInt(expected.headingQ), TimelineProfile.sample(state, 1, 2, tick * 1024))
            assertEquals(TimelineValue.UInt(expected.progressQ), TimelineProfile.sample(state, 1, 5, tick * 1024))
        }
        TimelineCanonical.digest(state)
    }

    @Test fun `frozen observations seal on a separate batch deadline without advancing observation time`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observation(0, still)); publisher.publish(0)
        publisher.observe(observation(1, still))
        val unsealed = assertNotNull(publisher.publish(1_000_000_000))
        assertEquals(0L, unsealed.header.sealedThrough)
        assertNull(publisher.publish(4_800_000_000))
        val sealed = assertNotNull(publisher.publish(4_900_000_000))
        assertEquals(unsealed.header.observedThrough, sealed.header.observedThrough)
        assertEquals(1024L, sealed.header.sealedThrough)
        assertEquals(unsealed.objects, sealed.objects)
        assertNotEquals(TimelineCanonical.digest(unsealed), TimelineCanonical.digest(sealed))
        assertNull(publisher.publish(10_000_000_000))
        assertSame(sealed, publisher.latest)
    }

    @Test fun `balanced fitting has a deterministic n log n bound on adversarial changing observations`() {
        val publisher = TimelinePublisher(f.context())
        val count = 1025
        for (tick in 0 until count) {
            publisher.observe(observation(tick.toLong(), still.copy(position = TimelineValue.Vec3(256 + (tick % 2) * 512L, 512, -1))))
            if (tick == 0) publisher.publish(0)
        }
        assertEquals(count.toLong() + 6, publisher.retainedRawSampleCount)
        val before = publisher.fittingSampleChecks
        val state = assertNotNull(publisher.publish(nanos(count - 1L)))
        var levels = 0; var capacity = 1
        while (capacity < count) { capacity *= 2; levels++ }
        val work = publisher.fittingSampleChecks - before
        assertTrue(work > 0 && work <= 4L * count * (levels + 1), "Unexpected fit work: $work")
        for (tick in 0 until count) {
            val actual = TimelineProfile.sample(state, 1, 1, tick * 1024L) as TimelineValue.Vec3
            assertTrue(abs(actual.x - (256 + (tick % 2) * 512L)) <= 64)
        }
        assertEquals((count - 1) * 1024L, state.header.sealedThrough)
    }

    @Test fun `nonmonotone endpoint feasibility and negative half-angle rounding use the original exact sampler`() {
        val publisher = TimelinePublisher(f.context())
        for ((tick, progress) in listOf(0L, 0L, 3L, 3L).withIndex()) {
            publisher.observe(observation(tick.toLong(), still.copy(progressQ = progress)))
            if (tick == 0) publisher.publish(0)
        }
        val state = assertNotNull(publisher.publish(100_000_000))
        val history = state.objects.getValue(1).curves.getValue(5).segments.filter { !it.predicted }
        assertEquals(1, history.size, "A failed shorter endpoint must not hide a valid longer fit")
        assertEquals(TimelineValue.UInt(1), TimelineProfile.sample(state, 1, 5, 1024))
        assertEquals(TimelineValue.UInt(2), TimelineProfile.sample(state, 1, 5, 2048))

        for ((end, middle) in listOf(65353L to 65444L, 32768L to 16384L)) {
            val angle = TimelinePublisher(f.context())
            angle.observe(observation(0, still.copy(headingQ = 0))); angle.publish(0)
            angle.observe(observation(1, still.copy(headingQ = end)))
            val after = assertNotNull(angle.publish(100_000_000))
            assertEquals(TimelineValue.UInt(middle), TimelineProfile.sample(after, 1, 2, 512))
        }
    }

    @Test fun `same-time replacements refit only the unsealed run and preserve its genuine predecessor`() {
        val publisher = TimelinePublisher(f.context())
        for (tick in 0L..300L) {
            publisher.observe(observation(tick, still))
            if (tick % 6 == 0L) publisher.publish(nanos(tick))
        }
        val sealed = publisher.latest!!
        assertEquals(5 * second - second / 10, sealed.header.sealedThrough)
        for (tick in 301L..540L) publisher.observe(observation(tick, still))
        val moved = still.copy(position = TimelineValue.Vec3(4096, 8192, -1024))
        repeat(3) { publisher.observe(observation(540, moved)); publisher.observe(observation(540, still)) }
        publisher.observe(observation(540, moved))
        val after = assertNotNull(publisher.publish(nanos(540)))
        assertEquals(sealed.header.sealedThrough, after.header.sealedThrough)
        assertEquals(prefix(sealed.objects.getValue(1).curves.getValue(1), 0, sealed.header.sealedThrough),
            prefix(after.objects.getValue(1).curves.getValue(1), 0, sealed.header.sealedThrough))
        assertEquals(still.position, TimelineProfile.sample(after, 1, 1, 539 * 1024L))
        assertEquals(TimelineValue.Vec3(2176, 4352, -513), TimelineProfile.sample(after, 1, 1, 539 * 1024L + 512))
        assertEquals(moved.position, TimelineProfile.sample(after, 1, 1, 540 * 1024L))
    }

    @Test fun `terminal fence and explicit finish seal all observed facts without fabricating future time`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observation(0)); publisher.publish(0)
        val finish = 100L * 1024
        val status = (f.finished(finish) as TimelineValue.Blob).value
        fun terminal(time: Long) = TimelineObservation(time, 1000, status, f.bytes(0, 0, 0, 0), listOf(unit(100)))
        publisher.observe(terminal(finish))
        val atFinish = assertNotNull(publisher.publish(2_000_000_000))
        assertEquals(finish, atFinish.header.sealedThrough)
        publisher.observe(terminal(finish + 1))
        val fence = assertNotNull(publisher.publish(2_200_000_000))
        assertEquals(finish + 1, fence.header.sealedThrough)
        assertEquals(finish + 1, fence.header.observedThrough)
        assertEquals(TimelineValue.Blob(status), TimelineProfile.sample(fence, 0, 2, finish))
        assertFailsWith<ProtocolException> { publisher.publishConfirmed(2_100_000_000) }

        val stopping = TimelinePublisher(f.context())
        stopping.observe(observation(0)); stopping.publish(0)
        stopping.observe(observation(3))
        val completed = assertNotNull(stopping.publishConfirmed(1))
        assertEquals(3072L, completed.header.observedThrough)
        assertEquals(3072L, completed.header.sealedThrough)
        assertTrue(completed.objects.values.flatMap { it.curves.values }.flatMap { it.segments }.none { it.predicted && it.lo < 3072 })
    }
}
