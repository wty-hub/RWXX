package io.github.rwx.kool

import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineOwnerLoopTest {
    @Test fun `full park high refresh pacing yields the CPU with either deadline policy`() = forEachDeadlineMode { stable ->
        val threadMetrics = ManagementFactory.getThreadMXBean()
        if (!threadMetrics.isCurrentThreadCpuTimeSupported) return@forEachDeadlineMode
        if (!threadMetrics.isThreadCpuTimeEnabled) threadMetrics.isThreadCpuTimeEnabled = true
        val warmedUp = CountDownLatch(30)
        val loop = EngineOwnerLoop({ 3_333_333L }, { warmedUp.countDown() }, { throw it },
            legacyPacing = true, legacyShortOwnerPark = true, stableFrameDeadlines = stable)
        try {
            assertTrue(warmedUp.await(5, TimeUnit.SECONDS))
            val before = loop.call { System.nanoTime() to threadMetrics.currentThreadCpuTime }
            Thread.sleep(500)
            val after = loop.call { System.nanoTime() to threadMetrics.currentThreadCpuTime }
            val elapsed = after.first - before.first
            val cpu = after.second - before.second
            // Leave ample scheduling/VM overhead; the previous 1.5 ms spin used about 45% of a core.
            assertTrue(cpu < elapsed / 5 + 10_000_000L,
                "Idle owner used ${cpu / 1_000_000} ms CPU during ${elapsed / 1_000_000} ms of paced waiting")
        } finally { loop.close() }
    }

    @Test fun `full interval parking still wakes immediately for queued work and close`() = forEachDeadlineMode { stable ->
        val firstTick = CountDownLatch(1)
        val loop = EngineOwnerLoop({ TimeUnit.SECONDS.toNanos(30) }, { firstTick.countDown() }, { throw it },
            legacyPacing = true, legacyShortOwnerPark = true, stableFrameDeadlines = stable)
        try {
            assertTrue(firstTick.await(5, TimeUnit.SECONDS))
            val ownerThread = loop.submit { Thread.currentThread() }.get(1, TimeUnit.SECONDS)
            assertEquals(42, loop.submit { 42 }.get(1, TimeUnit.SECONDS))
            loop.close()
            assertTrue(!ownerThread.isAlive, "Closing the owner did not interrupt its frame wait")
        } finally { loop.close() }
    }

    @Test fun `nested owner operations do not hold the producer queue lock`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var loop: EngineOwnerLoop
        loop = EngineOwnerLoop({ 1_000_000L }, {}, { throw it })
        try {
            loop.submit { loop.submit { entered.countDown(); release.await(5, TimeUnit.SECONDS) }.join() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val submitted = CountDownLatch(1)
            val producer = Thread { loop.submit { 42 }; submitted.countDown() }.apply { start() }
            assertTrue(submitted.await(1, TimeUnit.SECONDS), "Producer blocked behind engine work")
            release.countDown(); producer.join(5000)
        } finally { release.countDown(); loop.close() }
    }
    @Test fun `rapid key and pointer transitions reach separate original loops in FIFO order`() = forEachPacingMode { legacy, legacyShort, stable ->
        var keyDown = false
        var pointerDown = false
        var pointerX = 0
        val observed = mutableListOf<Triple<Boolean, Boolean, Int>>()
        val executed = mutableListOf<String>()
        val loop = EngineOwnerLoop({ 1_000_000L }, {
            synchronized(observed) { observed += Triple(keyDown, pointerDown, pointerX) }
        }, { throw it }, legacyPacing = legacy, legacyShortOwnerPark = legacyShort, stableFrameDeadlines = stable)
        try {
            val futures = listOf(
                loop.submitInput("key/65", true) { executed += "key down"; keyDown = true },
                loop.submitInput("key/65", false) { executed += "key up"; keyDown = false },
                loop.submitInput("pointer", true) { executed += "pointer down 10"; pointerDown = true; pointerX = 10 },
                loop.submitInput("pointer", false) { executed += "pointer up 10"; pointerDown = false },
                loop.submitInput("pointer", true) { executed += "pointer down 20"; pointerDown = true; pointerX = 20 },
                loop.submitInput("pointer", false) { executed += "pointer up 20"; pointerDown = false },
            )
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            loop.call { Unit }
            val states = synchronized(observed) { observed.toList() }
            assertTrue(states.any { it.first }, "Key press overwritten: $states")
            assertTrue(states.any { it.second && it.third == 10 }, "First click overwritten: $states")
            assertTrue(states.any { it.second && it.third == 20 }, "Second click overwritten: $states")
            assertEquals(listOf("key down", "key up", "pointer down 10", "pointer up 10", "pointer down 20", "pointer up 20"),
                loop.call { executed.toList() })
            val firstClick = states.indexOfFirst { it.second && it.third == 10 }
            val secondClick = states.indexOfFirst { it.second && it.third == 20 }
            assertTrue(firstClick < secondClick && states.subList(firstClick + 1, secondClick).any { !it.second },
                "Pointer release was not observed between clicks: $states")
        } finally { loop.close() }
    }
    @Test fun `queued inputs retain order and real results while presentation stalls`() {
        val ticks = AtomicInteger()
        val ready = CountDownLatch(10)
        val received = mutableListOf<Int>()
        val loop = EngineOwnerLoop({ 1_000_000L }, { ticks.incrementAndGet(); ready.countDown() }, { throw it })
        try {
            val results = (0 until 2000).map { i -> loop.submit { received += i; i * 2 } }
            assertEquals((0 until 2000).map { it * 2 }, results.map { it.get(5, TimeUnit.SECONDS) })
            assertEquals((0 until 2000).toList(), loop.call { received.toList() })
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertTrue(ticks.get() >= 10)
        } finally { loop.close() }
    }

    @Test fun `clock reset excludes serialized loading time from next simulation delta`() = forEachPacingMode { legacy, legacyShort, stable ->
        val samples = mutableListOf<Float>()
        val loop = EngineOwnerLoop({ 1_000_000L }, { synchronized(samples) { samples += it } }, { throw it },
            legacyPacing = legacy, legacyShortOwnerPark = legacyShort, stableFrameDeadlines = stable)
        try {
            loop.call {
                Thread.sleep(80)
                synchronized(samples) { samples.clear() }
                loop.resetClock()
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (synchronized(samples) { samples.size < 3 } && System.nanoTime() < deadline) Thread.sleep(2)
            val deltas = synchronized(samples) { samples.toList() }
            assertTrue(deltas.size >= 3)
            assertEquals(0f, deltas.first(), "Load delay leaked into simulation: $deltas")
        } finally { loop.close() }
    }

    @Test fun `close drains accepted operations and rejects later operations`() = forEachPacingMode { legacy, legacyShort, stable ->
        val loop = EngineOwnerLoop({ 1_000_000L }, {}, { throw it }, legacyPacing = legacy,
            legacyShortOwnerPark = legacyShort, stableFrameDeadlines = stable)
        val values = (0 until 500).map { loop.submit { it } }
        loop.close()
        assertEquals((0 until 500).toList(), values.map { it.get(5, TimeUnit.SECONDS) })
        assertTrue(loop.submit { 42 }.isCompletedExceptionally)
    }

    @Test fun `pacing period is sampled once after each tick across refresh changes`() = forEachPacingMode { legacy, legacyShort, stable ->
        val events = mutableListOf<String>()
        val ready = CountDownLatch(6)
        var ticks = 0
        var periodCalls = 0
        val periods = listOf(1_000_000L, 3_333_333L, 10_000_000L)
        val loop = EngineOwnerLoop({
            events += "period"
            periods[periodCalls++ % periods.size]
        }, {
            events += "tick"
            ticks++
            ready.countDown()
        }, { throw it }, legacyPacing = legacy, legacyShortOwnerPark = legacyShort, stableFrameDeadlines = stable)
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val snapshot = loop.call { Triple(ticks, periodCalls, events.toList()) }
            assertEquals(snapshot.first, snapshot.second, "Waiting must not resample the settings callback")
            assertEquals(List(snapshot.first) { listOf("tick", "period") }.flatten(), snapshot.third)
        } finally { loop.close() }
    }

    @Test fun `reset while parked clears both elapsed loading time and the old deadline`() = forEachDeadlineMode { stable ->
        val samples = mutableListOf<Float>()
        val firstTick = CountDownLatch(1)
        val resetTick = CountDownLatch(1)
        val loop = EngineOwnerLoop({ TimeUnit.SECONDS.toNanos(30) }, { delta ->
            synchronized(samples) {
                samples += delta
                if (samples.size == 1) firstTick.countDown() else resetTick.countDown()
            }
        }, { throw it }, legacyPacing = true, legacyShortOwnerPark = true, stableFrameDeadlines = stable)
        try {
            assertTrue(firstTick.await(5, TimeUnit.SECONDS))
            loop.submit { Thread.sleep(80); loop.resetClock() }.get(5, TimeUnit.SECONDS)
            assertTrue(resetTick.await(1, TimeUnit.SECONDS), "Reset retained the old 30 second frame deadline")
            assertEquals(0f, synchronized(samples) { samples[1] }, "Loading time leaked through reset")
        } finally { loop.close() }
    }

    @Test fun `overrun keeps actual elapsed delta then starts a new paced interval`() = forEachDeadlineMode { stable ->
        val period = TimeUnit.MILLISECONDS.toNanos(40)
        val samples = mutableListOf<Float>()
        val ready = CountDownLatch(3)
        val loop = EngineOwnerLoop({ period }, { delta ->
            val first = synchronized(samples) {
                samples += delta
                samples.size == 1
            }
            if (first) Thread.sleep(120)
            ready.countDown()
        }, { throw it }, legacyPacing = true, legacyShortOwnerPark = true, stableFrameDeadlines = stable)
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val snapshot = synchronized(samples) { samples.take(3) }
            assertEquals(0f, snapshot.first())
            assertTrue(snapshot[1] >= 0.100f, "Overrun was replaced with a fixed delta: $snapshot")
            // Once the delayed tick starts, the missed periods must not produce immediate catch-up ticks.
            assertTrue(snapshot[2] >= 0.020f,
                "A missed interval caused a burst of catch-up ticks: $snapshot")
        } finally { loop.close() }
    }

    private inline fun forEachDeadlineMode(test: (Boolean) -> Unit) {
        test(false)
        test(true)
    }

    private inline fun forEachPacingMode(test: (Boolean, Boolean, Boolean) -> Unit) = forEachDeadlineMode { stable ->
        test(true, false, stable) // Original full park.
        test(false, true, stable) // Version 13 hybrid, including tiny coarse parks.
        test(false, false, stable) // Hybrid with the minimum coarse-park guard.
    }
}
