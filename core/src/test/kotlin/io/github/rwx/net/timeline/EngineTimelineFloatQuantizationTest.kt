package io.github.rwx.net.timeline

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.*

class EngineTimelineFloatQuantizationTest {
    private val companion = EngineTimelineProjector.Companion
    private val floatQuantize = method("quantize", Float::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    private val doubleIntQuantize = method("quantize", Double::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    private val doubleQuantize = method("quantize", Double::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!)
    private val position = method("position", Float::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!)
    private val progress = method("progress", Float::class.javaPrimitiveType!!)

    private fun method(name: String, vararg types: Class<*>): Method = companion.javaClass
        .getDeclaredMethod(name, *types).apply { isAccessible = true }
    private fun invoke(method: Method, vararg arguments: Any): Long = try {
        method.invoke(companion, *arguments) as Long
    } catch (failure: InvocationTargetException) { throw failure.targetException }

    private fun oracle(value: Double, scale: Double): Long {
        require(value.isFinite())
        return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(scale))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
    }
    private data class Outcome(val value: Long? = null, val failure: String? = null)
    private fun outcome(block: () -> Long): Outcome = try { Outcome(value = block()) }
        catch (failure: RuntimeException) { Outcome(failure = failure.javaClass.name) }
    private fun compare(value: Float, scale: Int) {
        val expected = outcome { oracle(value.toDouble(), scale.toDouble()) }
        val actual = outcome { invoke(floatQuantize, value, scale) }
        if (expected != actual) fail("Float bits=${value.toRawBits().toUInt().toString(16)} scale=$scale expected=$expected actual=$actual")
    }

    @Test fun `random raw float encodings preserve BigDecimal results and exception classes`() {
        val random = Random(0x25_1107)
        repeat(60_000) {
            val value = Float.fromBits(random.nextInt())
            compare(value, 256)
            compare(value, 65535)
        }
    }

    @Test fun `every admitted float exponent includes signed boundary and mantissa samples`() {
        val random = Random(0x16_5535)
        for (exponent in 0..151) {
            val mantissas = intArrayOf(0, 1, 2, 0x3fffff, 0x400000, 0x7ffffe, 0x7fffff,
                random.nextInt(0x800000), random.nextInt(0x800000))
            for (mantissa in mantissas) for (sign in intArrayOf(0, Int.MIN_VALUE)) {
                val value = Float.fromBits(sign or (exponent shl 23) or mantissa)
                compare(value, 256); compare(value, 65535)
            }
        }
    }

    @Test fun `true halves and both adjacent floats preserve half away rounding`() {
        val random = Random(0x512_655)
        fun neighbors(value: Float, scale: Int) {
            for (signed in floatArrayOf(value, -value)) {
                compare(Math.nextDown(signed), scale)
                compare(signed, scale)
                compare(Math.nextUp(signed), scale)
            }
        }
        for (numerator in intArrayOf(1, 3, 511, 513, 65535, 0x7fffff, 0xfffffd, 0xffffff))
            neighbors(numerator.toFloat() / 512f, 256)
        for (integer in intArrayOf(0, 1, 31, 65535, 0x3fffff, 0x7fffff))
            neighbors(integer.toFloat() + .5f, 65535)
        repeat(2048) {
            neighbors((random.nextInt(0x800000) * 2 + 1).toFloat() / 512f, 256)
            neighbors(random.nextInt(0x800000).toFloat() + .5f, 65535)
        }
    }

    @Test fun `signed zero tiny values bounds nonfinite and huge float fallbacks are unchanged`() {
        val bound = 16777216f
        val values = floatArrayOf(0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE,
            Float.fromBits(0x00800000), -Float.fromBits(0x00800000),
            Math.nextDown(bound), bound, Math.nextUp(bound), -Math.nextDown(bound), -bound, -Math.nextUp(bound),
            1e20f, -1e20f, Float.MAX_VALUE, -Float.MAX_VALUE,
            Float.NaN, Float.fromBits(0xffc00001.toInt()), Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (value in values) for (scale in intArrayOf(256, 65535)) compare(value, scale)
        assertEquals(0L, invoke(floatQuantize, -0f, 256))
        assertFailsWith<IllegalArgumentException> { invoke(floatQuantize, Float.NaN, 256) }
        assertFailsWith<ArithmeticException> { invoke(floatQuantize, Float.MAX_VALUE, 65535) }
    }

    @Test fun `other scales and both old Double overloads retain decimal behavior including a binary tie counterexample`() {
        val counterexample = Float.fromBits(0x437ffffd)
        assertEquals(255.9999542236328125, counterexample.toDouble())
        assertEquals(8388606L, oracle(counterexample.toDouble(), 32768.0))
        assertEquals(8388607L, kotlin.math.floor(counterexample.toDouble() * 32768.0 + .5).toLong())
        assertEquals(8388606L, invoke(floatQuantize, counterexample, 32768))
        val random = Random(0x32768)
        for (scale in intArrayOf(0, -1, 1, 1024, 32768, 65536, Int.MIN_VALUE, Int.MAX_VALUE)) {
            repeat(512) { compare(Float.fromBits(random.nextInt()), scale) }
        }
        for (value in doubleArrayOf(-0.0, .5, -.5, 1.0 / 512, counterexample.toDouble(),
            Math.nextDown(.5), Math.nextUp(.5), 16777216.000000004, 1e30)) {
            for (scale in intArrayOf(256, 65535, 1024, 32768)) assertEquals(
                outcome { oracle(value, scale.toDouble()) }, outcome { invoke(doubleIntQuantize, value, scale) })
            for (scale in doubleArrayOf(65536.0 / 360, .125, 1024.0)) assertEquals(
                outcome { oracle(value, scale) }, outcome { invoke(doubleQuantize, value, scale) })
        }
    }

    @Test fun `position clamp and progress validation remain on their original public observation domains`() {
        for (value in floatArrayOf(0f, -0f, .5f / 256, Math.nextDown(40f), 39.999f)) assertEquals(
            oracle(value.toDouble(), 256.0).coerceAtMost(40 * 256L - 1), invoke(position, value, 40L))
        for (value in floatArrayOf(0f, -0f, Float.MIN_VALUE, Math.nextDown(.5f), .5f, Math.nextUp(.5f), 1f))
            assertEquals(oracle(value.toDouble(), 65535.0), invoke(progress, value))
        for (value in floatArrayOf(-Float.MIN_VALUE, Math.nextUp(1f), Float.NaN, Float.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { invoke(progress, value) }
        for (value in floatArrayOf(-Float.MIN_VALUE, 40f, Float.NaN, Float.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { invoke(position, value, 40L) }
    }

    private data class Publication(val wire: ByteArray, val canonical: ByteArray, val digest: TimelineBytes)
    private fun stream(fast: Boolean): List<Publication> {
        val fixture = TimelineTestFixture
        val context = fixture.context()
        val publisher = TimelinePublisher(context)
        val store = TimelineStore(context)
        val receiver = TimelineReceiver(store)
        var clock = 0L
        val encoder = TimelineEncoder(clock = { clock })
        val values = floatArrayOf(0f, 1f / 512, Math.nextDown(.5f), .5f, Math.nextUp(.5f), 1.5f, 39.999f)
        val progressValues = floatArrayOf(0f, Math.nextDown(.5f), .5f, Math.nextUp(.5f), 1f)
        fun q(value: Float, scale: Int) = if (fast) invoke(floatQuantize, value, scale) else oracle(value.toDouble(), scale.toDouble())
        val results = ArrayList<Publication>()
        repeat(14) { step ->
            clock = step * 200_000_000L
            val rawX = values[step % values.size]
            val x = if (fast) invoke(position, rawX, 40L) else q(rawX, 256).coerceAtMost(40 * 256L - 1)
            val unit = TimelineObservedUnit(77,
                TimelineValue.Vec3(x, q(1f + step / 512f, 256), q(-.5f - step / 512f, 256)),
                oracle(((step * 33.3) % 360 + 360) % 360, 65536.0 / 360) % 65536,
                q(200f - step - 1f / 512, 256), q(1024.5f, 256),
                q(progressValues[step % progressValues.size], 65535), 0, "tank", 1, true)
            publisher.observe(TimelineObservation(step * 1024L, oracle(1000.0 + step / 3.0, 1024.0),
                (fixture.running() as TimelineValue.Blob).value, fixture.bytes(0, 0, 0, 0), listOf(unit)))
            val source = assertNotNull(publisher.publish(clock))
            val old = store.current
            val publication = assertNotNull(encoder.encode(TimelineSendCursor(context, 1, old?.header?.epoch ?: 1,
                old, old == null), source))
            val header = publication.header
            val frames = ArrayList<TimelineFrame>()
            frames += header.beginFrame()
            val bytes = publication.operations.toByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + TimelineProtocol.MAX_CHUNK_BYTES)
                frames += timelineFrame(107, tn(1, header.streamId, true), tn(2, header.epoch), tn(3, header.revision),
                    tn(4, offset.toLong()), tb(5, TimelineBytes(bytes.copyOfRange(offset, end))))
                offset = end
            }
            frames += timelineFrame(108, tn(1, header.streamId, true), tn(2, header.epoch), tn(3, header.revision))
            val wire = ByteArrayOutputStream().also { output -> frames.forEach {
                TimelineMessages.validate(it, fromClient = false)
                TimelineBinary.writeFrame(output, it)
            } }.toByteArray()
            val input = ByteArrayInputStream(wire)
            repeat(frames.size) {
                val frame = TimelineBinary.readFrame(input)
                TimelineMessages.validate(frame, fromClient = false)
                when (frame.type) {
                    106 -> receiver.begin(TimelineMessages.header(frame.body))
                    107 -> receiver.chunk(frame)
                    108 -> assertNotNull(receiver.commit(frame))
                    else -> error("Unexpected transaction")
                }
            }
            assertEquals(0, input.available())
            val state = assertNotNull(store.current)
            TimelineProfile.validate(state)
            val canonical = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
            results += Publication(wire, canonical, TimelineCanonical.digest(state))
        }
        return results
    }

    @Test fun `quantized observations preserve actual v7 transaction frames receiver state and full digest`() {
        val old = stream(false)
        val fast = stream(true)
        assertEquals(old.size, fast.size)
        old.indices.forEach { index ->
            assertContentEquals(old[index].wire, fast[index].wire, "Wire publication $index")
            assertContentEquals(old[index].canonical, fast[index].canonical, "Canonical publication $index")
            assertEquals(old[index].digest, fast[index].digest, "Digest publication $index")
        }
    }
}
