package io.github.rwx.net.timeline

import java.nio.file.Path
import kotlin.test.*

/** Pure setup/trajectory checks: the opt-in network main is never run by the unit suite. */
class TimelineContinuousTlsBenchmarkTest {
    private val benchmark = TimelineContinuousTlsBenchmark

    @Test fun `settings keep production default budget and reject an unbounded fixture`() {
        val parsed = benchmark.parse(arrayOf("--output=unused-tls-evidence"))
        assertEquals(1000, parsed.units)
        assertEquals(10, parsed.seconds)
        assertFalse(parsed.expectFinalSealed)
        assertEquals(Path.of("unused-tls-evidence").toAbsolutePath(), parsed.output)
        assertEquals(1_048_576L, benchmark.BYTE_BUDGET)
        assertFailsWith<IllegalArgumentException> { benchmark.parse(arrayOf("--output=x", "--units=65536")) }
        assertFailsWith<IllegalArgumentException> { benchmark.parse(arrayOf("--output=x", "--seconds=31")) }
        assertFailsWith<IllegalArgumentException> { benchmark.parse(arrayOf("--output=x", "--byte-budget=16777216")) }
        assertFailsWith<IllegalArgumentException> { benchmark.parse(arrayOf("--output=x", "--output=y")) }
    }

    @Test fun `tiny authorized fixture reconstructs every independently sampled observation`() {
        val publisher = TimelinePublisher(benchmark.context())
        for (tick in 0..12) {
            publisher.observe(TimelineObservation(tick * 1024L, 1_000_000, TimelineBytes(byteArrayOf(0, 0, 0, 0)),
                TimelineBytes(ByteArray(256 * 256)), List(3) { benchmark.unit(it, tick) }))
            if (tick % 6 == 0) publisher.publish(tick * 1_000_000_000L / 60)
        }
        publisher.publish(6_000_000_000L)
        val state = checkNotNull(publisher.latest)
        assertEquals(12 * 1024L, state.header.observedThrough)
        for (obj in state.objects.values) if (obj.kind == TimelineObjectKind.UNIT) {
            benchmark.verifyUnitSamples(obj, checkNotNull(publisher.unitKey(obj.handle)).toInt() - 1, 12)
        }
        val angle = TimelineSegment.Interpolated(0, 2, false, 0, 2, TimelineValue.UInt(0), TimelineValue.UInt(65535))
        assertEquals(65535L, benchmark.scalar(angle, TimelineMode.ANGLE, 1))
        val negative = TimelineSegment.Interpolated(0, 2, false, 0, 2, TimelineValue.Vec3(-1, -3, -5), TimelineValue.Vec3(0, 0, 0))
        assertEquals(-2L, benchmark.scalar(negative, TimelineMode.LINEAR, 1, 1))
    }
}
