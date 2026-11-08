package io.github.rwx.net.timeline

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TimelineBinarySamplingTest {
    private fun scalar(a: Long, b: Long, end: Long, time: Long, unsigned: Boolean = false): Long {
        val segment = TimelineSegment.Interpolated(0, end, false, 0, end,
            if (unsigned) TimelineValue.UInt(a) else TimelineValue.SInt(a),
            if (unsigned) TimelineValue.UInt(b) else TimelineValue.SInt(b))
        return when (val sample = TimelineBinary.sample(TimelineMode.LINEAR,
            if (unsigned) TimelineValueType.UINT else TimelineValueType.SINT, segment, time)) {
            is TimelineValue.SInt -> sample.value
            is TimelineValue.UInt -> sample.value
            else -> error("Expected scalar sample")
        }
    }

    /** Independent arbitrary-precision reference: anchor plus an exact rational displacement. */
    private fun reference(a: Long, b: Long, start: Long, end: Long, time: Long): Long {
        val denominator = BigInteger.valueOf(end).subtract(BigInteger.valueOf(start))
        val numerator = BigInteger.valueOf(a).multiply(denominator).add(
            BigInteger.valueOf(b).subtract(BigInteger.valueOf(a))
                .multiply(BigInteger.valueOf(time).subtract(BigInteger.valueOf(start))))
        val result = numerator.divideAndRemainder(denominator)
        return (if (result[1].abs().multiply(BigInteger.TWO) >= denominator)
            result[0].add(BigInteger.valueOf(numerator.signum().toLong())) else result[0]).longValueExact()
    }

    @Test fun `whole-value rounding handles signed ties exact minimum numerator and overflow cancellation`() {
        for (a in -5L..5L) for (b in -5L..5L) for (end in 1L..11L) for (time in 0L until end) {
            assertEquals(reference(a, b, 0, end, time), scalar(a, b, end, time))
        }
        assertEquals(-1L, scalar(-1, 0, 2, 1))
        assertEquals(1L, scalar(1, 0, 2, 1))
        // a * (end - time) is exactly Long.MIN_VALUE; abs(numerator) would overflow.
        val minimum = TimelineProtocol.MIN_SINT
        assertEquals(reference(minimum, 0, 0, 3, 1), scalar(minimum, 0, 3, 1))
        // Both products fit but their sum does not; the rounded unsigned result still fits.
        assertEquals(Long.MAX_VALUE - 1, scalar(Long.MAX_VALUE, Long.MAX_VALUE - 2, 2, 1, true))
        // Individual products overflow although they nearly cancel in the exact numerator.
        assertEquals(-1L, scalar(minimum, TimelineProtocol.MAX_SINT, TimelineProtocol.MAX_TIME,
            TimelineProtocol.MAX_TIME / 2))
        assertEquals(reference(0, 1, 0, TimelineProtocol.MAX_TIME, TimelineProtocol.MAX_TIME / 2),
            scalar(0, 1, TimelineProtocol.MAX_TIME, TimelineProtocol.MAX_TIME / 2))
    }

    @Test fun `deterministic random scalar vector and angle samples equal independent big integer reference`() {
        val random = Random(0x735120)
        repeat(1500) { iteration ->
            val start: Long
            val end: Long
            when (iteration % 3) {
                0 -> { start = random.nextLong(0, 1_000_000); end = start + random.nextLong(1, 100_000) }
                1 -> { start = 0; end = random.nextLong(1, TimelineProtocol.FOREVER) }
                else -> { end = TimelineProtocol.MAX_TIME; start = random.nextLong(end - 1_000_000, end) }
            }
            val time = random.nextLong(start, end)
            fun nextSigned() = if (iteration % 3 == 0) random.nextLong(-1_000_000, 1_000_001) else random.nextLong() shr 1
            fun nextUnsigned() = if (iteration % 3 == 0) random.nextLong(0, 1_000_001) else random.nextLong() ushr 1
            val a = TimelineValue.Vec3(nextSigned(), nextSigned(), nextSigned())
            val b = TimelineValue.Vec3(nextSigned(), nextSigned(), nextSigned())
            val vector = TimelineSegment.Interpolated(start, end, iteration % 2 == 0, start, end, a, b)
            val expected = TimelineValue.Vec3(reference(a.x, b.x, start, end, time),
                reference(a.y, b.y, start, end, time), reference(a.z, b.z, start, end, time))
            assertEquals(expected, TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.VEC3, vector, time))
            assertEquals(expected, TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.VEC3,
                vector.clipped(time, time + 1), time))
            assertNull(TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.VEC3,
                vector.clipped(time, time + 1), time + 1))

            val signed = vector.copy(value0 = TimelineValue.SInt(a.x), value1 = TimelineValue.SInt(b.x))
            assertEquals(TimelineValue.SInt(expected.x), TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.SINT, signed, time))
            val ua = nextUnsigned(); val ub = nextUnsigned()
            val unsigned = vector.copy(value0 = TimelineValue.UInt(ua), value1 = TimelineValue.UInt(ub))
            assertEquals(TimelineValue.UInt(reference(ua, ub, start, end, time)),
                TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.UINT, unsigned, time))

            val angleA = random.nextLong(65536); val angleB = random.nextLong(65536)
            var difference = angleB - angleA
            if (difference <= -32768) difference += 65536
            if (difference > 32768) difference -= 65536
            val angle = vector.copy(value0 = TimelineValue.UInt(angleA), value1 = TimelineValue.UInt(angleB))
            val rounded = reference(angleA, angleA + difference, start, end, time)
            val wrapped = BigInteger.valueOf(rounded).mod(BigInteger.valueOf(65536)).longValueExact()
            assertEquals(TimelineValue.UInt(wrapped), TimelineBinary.sample(TimelineMode.ANGLE, TimelineValueType.UINT, angle, time))
        }
    }

    @Test fun `half turns clipping and constant forever preserve endpoints and exclusive gaps`() {
        for (end in listOf(2L, 10L, TimelineProtocol.MAX_TIME)) {
            for (a in listOf(0L, 1L, 32767L, 32768L, 65535L)) {
                val b = (a + 32768) % 65536
                val segment = TimelineSegment.Interpolated(0, end, false, 0, end,
                    TimelineValue.UInt(a), TimelineValue.UInt(b))
                for (time in listOf(0L, end / 2, end - 1)) {
                    val expected = Math.floorMod(reference(a, a + 32768, 0, end, time), 65536L)
                    assertEquals(TimelineValue.UInt(expected), TimelineBinary.sample(TimelineMode.ANGLE, TimelineValueType.UINT,
                        segment.clipped(time, time + 1), time))
                }
            }
        }
        val constant = TimelineSegment.Interpolated(20, TimelineProtocol.FOREVER, false, 0,
            TimelineProtocol.FOREVER, TimelineValue.UInt(Long.MAX_VALUE), TimelineValue.UInt(Long.MAX_VALUE))
        assertEquals(TimelineValue.UInt(Long.MAX_VALUE), TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.UINT,
            constant, TimelineProtocol.MAX_TIME))
        assertNull(TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.UINT, constant, 19))
        assertFailsWith<ProtocolException> {
            TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.UINT, constant, TimelineProtocol.FOREVER)
        }
        assertFailsWith<ProtocolException> {
            TimelineBinary.sample(TimelineMode.LINEAR, TimelineValueType.UINT,
                constant.copy(value1 = TimelineValue.UInt(Long.MAX_VALUE - 1)), 20)
        }
    }

    @Test fun `allocation-free value and format checks still reject every forbidden combination`() {
        for (mode in TimelineMode.entries) for (type in TimelineValueType.entries) {
            val allowed = when (mode) {
                TimelineMode.LINEAR -> type == TimelineValueType.SINT || type == TimelineValueType.VEC3 || type == TimelineValueType.UINT
                TimelineMode.ANGLE -> type == TimelineValueType.UINT
                else -> true
            }
            if (allowed) TimelineBinary.validateFormat(mode, type)
            else assertFailsWith<ProtocolException> { TimelineBinary.validateFormat(mode, type) }
        }
        val limit = TimelineProtocol.MAX_SINT
        TimelineBinary.validateValue(TimelineValueType.VEC3, TimelineValue.Vec3(TimelineProtocol.MIN_SINT, 0, limit))
        for (invalid in listOf(TimelineValue.Vec3(limit + 1, 0, 0), TimelineValue.Vec3(0, limit + 1, 0),
            TimelineValue.Vec3(0, 0, limit + 1), TimelineValue.Vec3(TimelineProtocol.MIN_SINT - 1, 0, 0),
            TimelineValue.Vec3(0, TimelineProtocol.MIN_SINT - 1, 0), TimelineValue.Vec3(0, 0, TimelineProtocol.MIN_SINT - 1))) {
            assertFailsWith<ProtocolException> { TimelineBinary.validateValue(TimelineValueType.VEC3, invalid) }
        }
        assertFailsWith<ProtocolException> { TimelineBinary.validateValue(TimelineValueType.UINT, TimelineValue.SInt(0)) }
        assertFailsWith<ProtocolException> { TimelineBinary.validateValue(TimelineValueType.UINT, TimelineValue.UInt(-1)) }
    }

    @Test fun `cached definitions never cache input acceptance or cross object kinds`() {
        val unit = TimelineRecord(listOf(TimelineField(1, false, TimelineTlvValue.Text("tank"))))
        val panel = TimelineRecord(listOf(TimelineField(2, true, TimelineTlvValue.U64(1)),
            TimelineField(5, true, TimelineTlvValue.Bytes(TimelineBytes(byteArrayOf(1, 1))))))
        repeat(3) {
            TimelineBinary.validateDescriptor(TimelineObjectKind.UNIT, unit)
            TimelineBinary.validateDescriptor(TimelineObjectKind.PANEL, panel)
            assertFailsWith<ProtocolException> { TimelineBinary.validateDescriptor(TimelineObjectKind.PANEL, unit) }
            assertFailsWith<ProtocolException> { TimelineBinary.validateDescriptor(TimelineObjectKind.UNIT, panel) }
            assertFailsWith<ProtocolException> { TimelineBinary.validateDescriptor(TimelineObjectKind.UNIT,
                TimelineRecord(listOf(TimelineField(2, false, TimelineTlvValue.U64(1))))) }
            TimelineBinary.validateMetadata(TimelineMode.STEP, TimelineRecord.EMPTY)
            val pulse = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(100))))
            TimelineBinary.validateMetadata(TimelineMode.PULSE, pulse)
            assertFailsWith<ProtocolException> { TimelineBinary.validateMetadata(TimelineMode.STEP, pulse) }
            assertFailsWith<ProtocolException> { TimelineBinary.validateMetadata(TimelineMode.PULSE, TimelineRecord.EMPTY) }
        }
        // Public callers can supply a mutable schema; only private constant definitions are cached.
        val schema = mutableMapOf(1 to TimelineFieldRule(5, true, false))
        TimelineBinary.validateSchema(unit, schema)
        schema[1] = TimelineFieldRule(3, true, false)
        assertFailsWith<ProtocolException> { TimelineBinary.validateSchema(unit, schema) }
        schema.clear()
        TimelineBinary.validateSchema(unit, schema)
    }
}
