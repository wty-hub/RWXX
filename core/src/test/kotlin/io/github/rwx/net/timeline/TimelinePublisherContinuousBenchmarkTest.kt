package io.github.rwx.net.timeline

import kotlin.random.Random
import kotlin.test.*

class TimelinePublisherContinuousBenchmarkTest {
    private val benchmark = TimelinePublisherContinuousBenchmark

    @Test fun `longest fixture keeps all native profile ranges legal without stopping progress`() {
        for (index in listOf(0, 999, 9999, 65535)) for (tick in listOf(0, 120, 480, 600, 1800)) {
            val unit = benchmark.unit(index, tick)
            assertTrue(unit.position.x in 0 until 256L * 20 * 256)
            assertTrue(unit.position.y in 0 until 256L * 20 * 256)
            assertTrue(unit.headingQ in 0..65535)
            assertTrue(unit.progressQ in 0..65535)
            if (tick > 0) assertEquals(16L, unit.progressQ - benchmark.unit(index, tick - 1).progressQ)
        }
        assertEquals(38799L, benchmark.unit(9999, 1800).progressQ)
        assertFailsWith<IllegalArgumentException> { benchmark.parse(arrayOf("--units=1000", "--units=1000")) }
        assertFailsWith<IllegalArgumentException> { benchmark.parse(arrayOf("--seconds=31")) }
        assertFailsWith<IllegalArgumentException> { benchmark.parse(arrayOf("--unknown=1")) }
    }

    @Test fun `independent scalar reader agrees with exact sampler including full-ratio ties and overflow fallback`() {
        val random = Random(701)
        repeat(1000) { index ->
            val angle = index % 3 == 0
            val end = if (index % 2 == 0) Long.MAX_VALUE - 1 else random.nextLong(2, 1_000_000)
            val first = if (angle) random.nextLong(65536) else random.nextLong(Long.MAX_VALUE)
            val last = if (angle) random.nextLong(65536) else random.nextLong(Long.MAX_VALUE)
            val segment = TimelineSegment.Interpolated(0, end, false, 0, end,
                TimelineValue.UInt(first), TimelineValue.UInt(last))
            val mode = if (angle) TimelineMode.ANGLE else TimelineMode.LINEAR
            for (time in listOf(0L, end / 2, end - 1)) {
                val expected = TimelineBinary.sample(mode, TimelineValueType.UINT, segment, time) as TimelineValue.UInt
                assertEquals(expected.value, benchmark.scalar(segment, mode, time))
            }
        }
        val negativeHalf = TimelineSegment.Interpolated(0, 2, false, 0, 2,
            TimelineValue.Vec3(-1, -3, -5), TimelineValue.Vec3(0, 0, 0))
        assertEquals(-1L, benchmark.scalar(negativeHalf, TimelineMode.LINEAR, 1, 0))
        assertEquals(-2L, benchmark.scalar(negativeHalf, TimelineMode.LINEAR, 1, 1))
        assertEquals(-3L, benchmark.scalar(negativeHalf, TimelineMode.LINEAR, 1, 2))
        val angleHalf = TimelineSegment.Interpolated(0, 2, false, 0, 2, TimelineValue.UInt(0), TimelineValue.UInt(65535))
        assertEquals(65535L, benchmark.scalar(angleHalf, TimelineMode.ANGLE, 1))
    }

    @Test fun `sealed-prefix check detects byte-meaningful reanchoring even when sampled values match`() {
        val f = TimelineTestFixture
        val store = TimelineStore(f.context())
        val before = f.commit(store, f.root() + f.unit(), sealed = 5)
        val next = f.commit(store, emptyList(), observed = 11, sealed = 6)
        benchmark.assertSealedPrefix(before, next)
        val obj = next.objects.getValue(1)
        val curve = obj.curves.getValue(1)
        val original = curve.segments.single() as TimelineSegment.Interpolated
        val reanchored = curve.copy(segments = listOf(original.copy(hi = 10, anchor1Time = 10), original.copy(lo = 10)))
        val changed = next.copy(objects = next.objects + (1L to obj.copy(curves = obj.curves + (1 to reanchored))))
        assertEquals(TimelineProfile.sample(next, 1, 1, 2), TimelineProfile.sample(changed, 1, 1, 2))
        assertFailsWith<IllegalStateException> { benchmark.assertSealedPrefix(before, changed) }
        val afterSeal = curve.copy(segments = listOf(original.copy(hi = 5), original.copy(lo = 5,
            value0 = TimelineValue.Vec3(512, 512, -1), value1 = TimelineValue.Vec3(512, 512, -1))))
        benchmark.assertSealedPrefix(before, next.copy(objects = next.objects + (1L to obj.copy(curves = obj.curves + (1 to afterSeal)))))
    }
}
