package io.github.rwx.kool

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineOwnerLoopTest {
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
    @Test fun `rapid key and pointer transitions reach separate original loops in FIFO order`() = forEachPacingMode { legacy, legacyShort ->
        var keyDown = false
        var pointerDown = false
        var pointerX = 0
        val observed = mutableListOf<Triple<Boolean, Boolean, Int>>()
        val executed = mutableListOf<String>()
        val loop = EngineOwnerLoop({ 1_000_000L }, {
            synchronized(observed) { observed += Triple(keyDown, pointerDown, pointerX) }
        }, { throw it }, legacyPacing = legacy, legacyShortOwnerPark = legacyShort)
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

    @Test fun `clock reset excludes serialized loading time from next simulation delta`() = forEachPacingMode { legacy, legacyShort ->
        val samples = mutableListOf<Float>()
        val loop = EngineOwnerLoop({ 1_000_000L }, { synchronized(samples) { samples += it } }, { throw it },
            legacyPacing = legacy, legacyShortOwnerPark = legacyShort)
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

    @Test fun `close drains accepted operations and rejects later operations`() = forEachPacingMode { legacy, legacyShort ->
        val loop = EngineOwnerLoop({ 1_000_000L }, {}, { throw it }, legacyPacing = legacy, legacyShortOwnerPark = legacyShort)
        val values = (0 until 500).map { loop.submit { it } }
        loop.close()
        assertEquals((0 until 500).toList(), values.map { it.get(5, TimeUnit.SECONDS) })
        assertTrue(loop.submit { 42 }.isCompletedExceptionally)
    }

    @Test fun `pacing period is sampled once after each tick across refresh changes`() = forEachPacingMode { legacy, legacyShort ->
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
        }, { throw it }, legacyPacing = legacy, legacyShortOwnerPark = legacyShort)
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val snapshot = loop.call { Triple(ticks, periodCalls, events.toList()) }
            assertEquals(snapshot.first, snapshot.second, "Waiting must not resample the settings callback")
            assertEquals(List(snapshot.first) { listOf("tick", "period") }.flatten(), snapshot.third)
        } finally { loop.close() }
    }

    private inline fun forEachPacingMode(test: (Boolean, Boolean) -> Unit) {
        test(true, false) // Original full park.
        test(false, true) // Version 13 hybrid, including tiny coarse parks.
        test(false, false) // Hybrid with the minimum coarse-park guard.
    }
}
