package io.github.rwx.net.timeline

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class TimelineRawSamplesTest {
    private data class Point(val time: Long, val value: TimelineValue)
    /** Exact deque behavior before this storage-only change, including failure position. */
    private fun append(reference: ArrayDeque<Point>, time: Long, value: TimelineValue) {
        if (reference.isNotEmpty() && reference.last.time == time) reference.removeLast()
        if (reference.size >= 3 && reference.last.value == value) {
            val last = reference.removeLast(); val previous = reference.removeLast()
            if (previous.value != value || reference.last.value != value) reference.addLast(previous)
            reference.addLast(last)
        }
        protocolRequire(reference.size < 4096, "Unpublished observation backlog exceeds raw sample capacity")
        reference.addLast(Point(time, value))
    }
    private fun compare(raw: TimelineRawSamples, expected: ArrayDeque<Point>) {
        val view = raw.borrow()
        assertEquals(expected.size, view.size)
        expected.forEachIndexed { index, point ->
            assertEquals(point.time, view.timeAt(index)); assertEquals(point.value, view.valueAt(index))
        }
    }

    @Test fun `primitive pages match deque through random replacement compression trim clear and page wrap`() {
        for (type in listOf(TimelineValueType.VEC3, TimelineValueType.UINT)) {
            val random = Random(773 + type.code)
            val raw = TimelineRawSamples(type); val expected = ArrayDeque<Point>()
            var time = 0L
            repeat(12000) { step ->
                when {
                    step % 1999 == 1998 -> { raw.clear(); expected.clear() }
                    step % 137 == 136 && expected.size > 2 -> {
                        val floor = expected.elementAt(random.nextInt(expected.size)).time
                        raw.trimPredecessors(floor)
                        while (expected.size > 2 && expected.elementAt(1).time <= floor) expected.removeFirst()
                    }
                    else -> {
                        if (expected.isEmpty() || random.nextInt(4) != 0) time += random.nextLong(1, 1025)
                        val value = if (expected.isNotEmpty() && random.nextInt(3) != 0) expected.last.value
                            else if (type == TimelineValueType.UINT) TimelineValue.UInt(random.nextLong(65536))
                            else TimelineValue.Vec3(random.nextLong(10000), random.nextLong(10000), random.nextLong(-10000, 10001))
                        append(expected, time, value); raw.appendObserved(time, value)
                    }
                }
                compare(raw, expected)
                val stride = if (type == TimelineValueType.VEC3) 4 else 2
                val capacity = if (raw.size == 0) 0 else if (raw.size <= 4) 4 else ((raw.size + 126) / 64) * 64
                assertTrue(raw.allocatedLongCount <= capacity.toLong() * stride)
            }
        }
    }

    @Test fun `capacity stays 4096 while constant and same-time changes preserve real predecessors`() {
        val raw = TimelineRawSamples(TimelineValueType.UINT)
        repeat(8193) { raw.appendObserved(it.toLong(), TimelineValue.UInt(9)) }
        assertEquals(3, raw.size); assertEquals(8L, raw.allocatedLongCount)
        val run = raw.borrow()
        assertEquals(listOf(0L, 8191L, 8192L), List(run.size, run::timeAt))
        repeat(4) { raw.appendObserved(8192, TimelineValue.UInt(12)); raw.appendObserved(8192, TimelineValue.UInt(9)) }
        raw.appendObserved(8192, TimelineValue.UInt(12))
        assertEquals(listOf(9L, 9L, 12L), List(raw.size) { (raw.borrow().valueAt(it) as TimelineValue.UInt).value })

        raw.clear()
        repeat(4096) { raw.appendObserved(it.toLong(), TimelineValue.UInt(it.toLong())) }
        val full = raw.borrow()
        assertFailsWith<ProtocolException> { raw.appendObserved(4096, TimelineValue.UInt(4096)) }
        assertEquals(4096, full.size) // A failed capacity check did not mutate the borrowed bytes.
        raw.appendObserved(4095, TimelineValue.UInt(7))
        assertEquals(4096, raw.size)
        assertEquals(TimelineValue.UInt(7), raw.borrow().valueAt(4095))
        assertTrue(raw.allocatedLongCount <= 65L * 64 * 2)

        raw.clear()
        repeat(4093) { raw.appendObserved(it.toLong(), TimelineValue.UInt(it.toLong())) }
        for (time in 4093L..4096L) raw.appendObserved(time, TimelineValue.UInt(60000))
        assertEquals(4096, raw.size)
        val compressed = raw.borrow()
        assertEquals(listOf(4093L, 4095L, 4096L), (4093..4095).map(compressed::timeAt))
    }

    @Test fun `trim reclaims whole pages and compacts only a one-page predecessor run`() {
        val raw = TimelineRawSamples(TimelineValueType.VEC3)
        var base = 0L
        repeat(12) {
            raw.clear()
            for (index in 0L until 4096) raw.appendObserved(base + index, TimelineValue.Vec3(index, -index, index * 9))
            assertEquals(4096L * 4, raw.allocatedLongCount)
            raw.trimPredecessors(base + 4095 - 60)
            assertEquals(61, raw.size); assertEquals(64L * 4, raw.allocatedLongCount)
            val run = raw.borrow()
            assertEquals(base + 4035, run.timeAt(0)); assertEquals(TimelineValue.Vec3(4095, -4095, 36855), run.valueAt(60))
            // Force a partial head and then grow through the circular page directory.
            for (index in 4096L..4450L) raw.appendObserved(base + index, TimelineValue.Vec3(index, -index, index * 9))
            raw.trimPredecessors(base + 4100)
            for (index in 4451L..5000L) raw.appendObserved(base + index, TimelineValue.Vec3(index, -index, index * 9))
            val grown = raw.borrow()
            for (index in 0 until grown.size) {
                val value = grown.timeAt(index) - base
                assertEquals(TimelineValue.Vec3(value, -value, value * 9), grown.valueAt(index))
            }
            raw.trimPredecessors(Long.MAX_VALUE)
            assertEquals(2, raw.size); assertEquals(16L, raw.allocatedLongCount)
            base += 10000
        }
        raw.clear(); assertEquals(0, raw.size); assertEquals(0L, raw.allocatedLongCount)
    }

    @Test fun `borrowed runs reject owner or generation changes while materialized immutable values survive reuse`() {
        val raw = TimelineRawSamples(TimelineValueType.VEC3)
        raw.appendObserved(0, TimelineValue.Vec3(1, 2, 3)); raw.appendObserved(1, TimelineValue.Vec3(4, 5, 6))
        val run = raw.borrow(); val preserved = run.valueAt(0)
        val wrongOwner = AtomicReference<Throwable?>()
        Thread { try { run.timeAt(0) } catch (failure: Throwable) { wrongOwner.set(failure) } }.also { it.start(); it.join(1000); assertFalse(it.isAlive) }
        assertIs<IllegalStateException>(wrongOwner.get())
        raw.appendObserved(2, TimelineValue.Vec3(7, 8, 9))
        assertFailsWith<IllegalStateException> { run.size }
        assertFailsWith<IllegalStateException> { run.timeAt(0) }
        assertFailsWith<IllegalStateException> { run.valueAt(0) }
        assertFailsWith<IllegalStateException> { run.nearAt(0, TimelineMode.LINEAR, preserved) }
        raw.clear(); repeat(130) { raw.appendObserved(it.toLong(), TimelineValue.Vec3(it.toLong(), 0, 0)) }
        assertEquals(TimelineValue.Vec3(1, 2, 3), preserved)
        assertFailsWith<ProtocolException> { raw.appendObserved(131, TimelineValue.UInt(1)) }
        assertFailsWith<ProtocolException> { raw.appendObserved(1, TimelineValue.Vec3(0, 0, 0)) }
    }

    @Test fun `full logical capacity wraps a partial head without exceeding sixty five physical pages`() {
        val raw = TimelineRawSamples(TimelineValueType.UINT)
        repeat(4096) { raw.appendObserved(it.toLong(), TimelineValue.UInt(it.toLong())) }
        for (first in 1L..193L) {
            raw.trimPredecessors(first)
            raw.appendObserved(first + 4095, TimelineValue.UInt(first + 4095))
            assertEquals(4096, raw.size)
            assertEquals((if (first % 64 == 0L) 64L else 65L) * 64 * 2, raw.allocatedLongCount)
            val run = raw.borrow()
            for (index in 0 until run.size) {
                assertEquals(first + index, run.timeAt(index))
                assertEquals(TimelineValue.UInt(first + index), run.valueAt(index))
            }
        }
    }

    @Test fun `primitive near comparisons match the original integer rules including wrap and extrema`() {
        val random = Random(719)
        fun near(mode: TimelineMode, a: TimelineValue, b: TimelineValue) = when (a) {
            is TimelineValue.Vec3 -> b is TimelineValue.Vec3 && abs(a.x - b.x) <= 64 && abs(a.y - b.y) <= 64 && abs(a.z - b.z) <= 64
            is TimelineValue.UInt -> b is TimelineValue.UInt && if (mode == TimelineMode.ANGLE) {
                val delta = Math.floorMod(b.value - a.value, 65536L); abs(if (delta > 32768) delta - 65536 else delta) <= 182
            } else abs(a.value - b.value) <= 1
            else -> a == b
        }
        for (mode in listOf(TimelineMode.LINEAR, TimelineMode.ANGLE)) {
            val raw = TimelineRawSamples(TimelineValueType.UINT)
            repeat(2000) { index ->
                val stored = TimelineValue.UInt(random.nextLong(65536)); val actual = TimelineValue.UInt(random.nextLong(65536))
                raw.appendObserved(index.toLong(), stored)
                assertEquals(near(mode, actual, stored), raw.borrow().nearAt(raw.size - 1, mode, actual))
            }
        }
        val raw = TimelineRawSamples(TimelineValueType.VEC3)
        val values = listOf(TimelineProtocol.MIN_SINT, -65L, -64L, 0L, 64L, 65L, TimelineProtocol.MAX_SINT)
        for (stored in values) for (actual in values) {
            raw.clear(); val b = TimelineValue.Vec3(stored, 0, 0); val a = TimelineValue.Vec3(actual, 0, 0)
            raw.appendObserved(TimelineProtocol.MAX_TIME, b)
            assertEquals(near(TimelineMode.LINEAR, a, b), raw.borrow().nearAt(0, TimelineMode.LINEAR, a))
            assertEquals(TimelineProtocol.MAX_TIME, raw.borrow().timeAt(0))
        }
    }
}
