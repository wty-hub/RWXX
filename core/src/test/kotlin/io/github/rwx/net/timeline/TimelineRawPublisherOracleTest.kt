package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

class TimelineRawPublisherOracleTest {
    private val f = TimelineTestFixture
    private val running = (f.running() as TimelineValue.Blob).value
    private fun bytes(state: TimelineState) = ByteArrayOutputStream().let { out -> TimelineCanonical.write(state, out); out.toByteArray() }
    private fun nanos(tick: Long) = tick * 1_000_000_000L / 60
    private val ability = TimelineRecord(listOf(
        TimelineField(1, true, TimelineTlvValue.U32(2)), TimelineField(2, true, TimelineTlvValue.U32(0)),
        TimelineField(3, true, TimelineTlvValue.Bool(false)), TimelineField(4, true, TimelineTlvValue.Text("action.tank")),
        TimelineField(5, true, TimelineTlvValue.Text("tank")), TimelineField(6, true, TimelineTlvValue.I64(100)),
        TimelineField(7, true, TimelineTlvValue.U32(1))))

    private fun observation(tick: Long, correction: Boolean = false): TimelineObservation {
        val units = (0..3).filter { it != 3 || (tick / 100) % 3 != 1L }.map { index ->
            val phase = tick % 120
            val motion = when { phase < 30 -> 0L; phase < 60 -> (phase - 29) * 24; phase < 90 -> 720L; else -> (120 - phase) * 24 }
            val foreign = index == 2 && (tick / 250) % 2 == 1L
            TimelineObservedUnit(index + 77L, TimelineValue.Vec3(512 + index * 1536L + motion + if (correction) 80 else 0,
                512 + index * 1024L + motion / 2, -motion),
                when { index == 0 && tick == 0L -> 0; index == 0 && tick == 1L -> 65535; else -> (index * 8000L + tick * 128) % 65536 },
                25600 - tick % 17, 25600, (tick % 200) * 255, if (foreign) 1 else 0,
                if (index == 2 && (tick / 250) % 2 == 1L) "builder" else "tank", 1 + ((tick / 400) % 2).toInt(), !foreign)
        }
        val selected = (tick / 70) % 3 != 2L
        val action = TimelineObservedAction(910, ability, if (tick % 29 == 0L) 0 else 1,
            tick % 3, -1, if (tick % 41 in 20L..25L) null else (tick % 170) * 300, tick % 3 != 0L)
        val events = if (tick % 137 == 0L) listOf(TimelineObservedEvent(tick + 1000, tick * 1024,
            TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U32(1)),
                TimelineField(2, true, TimelineTlvValue.Bytes(ByteArrayOutputStream().let { out ->
                    listOf(512L, 512L, 0L).forEach { TimelineBinary.writeVSInt(out, it) }; TimelineBytes(out.toByteArray())
                })), TimelineField(3, true, TimelineTlvValue.U64(2048)), TimelineField(4, true, TimelineTlvValue.U32(7)))), 2048)) else emptyList()
        return TimelineObservation(tick * 1024, 10000 + tick, running, f.bytes(0, 0, 0, if (tick % 90 < 45) 5 else 10),
            units, if (selected) 77L else null, if (selected) listOf(action) else emptyList(), events)
    }

    @Test fun `all mixed publications match the frozen v23 deque oracle byte for byte across seals retention and finish`() {
        val publisher = TimelinePublisher(f.context())
        val oracle = TimelineRawPublisherOracle(f.context())
        var first: TimelineState? = null; var firstBytes: ByteArray? = null
        var comparisons = 0
        fun compare(actual: TimelineState?, expected: TimelineState?) {
            assertEquals(expected == null, actual == null)
            if (actual != null && expected != null) {
                assertEquals(expected.header, actual.header)
                assertContentEquals(bytes(expected), bytes(actual))
                assertEquals(TimelineCanonical.digest(expected), TimelineCanonical.digest(actual))
                assertEquals(TimelineCanonical.persistentSize(expected), TimelineCanonical.persistentSize(actual))
                assertEquals(oracle.fittingSampleChecks, publisher.fittingSampleChecks)
                comparisons++
                if (first == null) { first = actual; firstBytes = bytes(actual) }
            }
        }
        for (tick in 0L..2160L) {
            val input = observation(tick)
            oracle.observe(input); publisher.observe(input)
            if (tick > 0 && tick % 113 == 0L) repeat(3) {
                val correction = observation(tick, true)
                oracle.observe(correction); publisher.observe(correction)
                oracle.observe(input); publisher.observe(input)
            }
            assertEquals(oracle.retainedRawSampleCount, publisher.retainedRawSampleCount)
            if (tick % 6 == 0L) compare(publisher.publish(nanos(tick)), oracle.publish(nanos(tick)))
        }
        val ending = observation(2160)
        val finish = 2160L * 1024
        val status = (f.finished(finish) as TimelineValue.Blob).value
        fun terminal(time: Long) = TimelineObservation(time, ending.creditsQ, status, ending.fog, ending.units)
        oracle.observe(terminal(finish)); publisher.observe(terminal(finish))
        compare(publisher.publish(nanos(2160) + 200_000_000), oracle.publish(nanos(2160) + 200_000_000))
        oracle.observe(terminal(finish + 1)); publisher.observe(terminal(finish + 1))
        compare(publisher.publishConfirmed(nanos(2160) + 400_000_000), oracle.publishConfirmed(nanos(2160) + 400_000_000))
        assertTrue(comparisons > 100)
        assertTrue(publisher.latest!!.header.retainedFrom > 0)
        assertEquals(finish + 1, publisher.latest!!.header.sealedThrough)
        assertContentEquals(assertNotNull(firstBytes), bytes(assertNotNull(first)))
    }

    @Test fun `raw capacity failure remains identical and old committed bytes survive failed observations`() {
        val publisher = TimelinePublisher(f.context()); val oracle = TimelineRawPublisherOracle(f.context())
        fun input(tick: Long) = TimelineObservation(tick * 1024, 1000, running, f.bytes(0, 0, 0, 0), listOf(
            TimelineObservedUnit(77, TimelineValue.Vec3(256 + (tick % 2) * 512, 512, -1), 65000,
                25600, 25600, 0, 0, "tank", 1, true)))
        oracle.observe(input(0)); publisher.observe(input(0))
        val initial = publisher.publish(0)!!; val initialBytes = bytes(initial)
        assertContentEquals(bytes(oracle.publish(0)!!), initialBytes)
        for (tick in 1L until 4096) { oracle.observe(input(tick)); publisher.observe(input(tick)) }
        assertEquals(oracle.retainedRawSampleCount, publisher.retainedRawSampleCount)
        val actual = assertFailsWith<ProtocolException> { publisher.observe(input(4096)) }
        val expected = assertFailsWith<ProtocolException> { oracle.observe(input(4096)) }
        assertEquals(expected.message, actual.message)
        assertEquals(oracle.retainedRawSampleCount, publisher.retainedRawSampleCount)
        assertContentEquals(initialBytes, bytes(initial))
    }
}
