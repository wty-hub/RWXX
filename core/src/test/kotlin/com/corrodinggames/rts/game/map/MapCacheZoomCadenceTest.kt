package com.corrodinggames.rts.game.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapCacheZoomCadenceTest {
    @Test
    fun `steady zoom refresh has the same real cadence at sixty and three hundred hertz`() {
        for (frames in listOf(10, 20)) {
            val times = listOf(16_666_667L, 6_944_444L, 3_333_333L).map { period ->
                val cadence = LayerBufferManager.ZoomCacheCadence()
                assertFalse(cadence.shouldRefresh(0, true, true, frames))
                var firstRefresh = -1L
                for (sample in 1..200) {
                    val now = period * sample
                    if (cadence.shouldRefresh(now, true, true, frames)) {
                        firstRefresh = now
                        break
                    }
                }
                assertTrue(firstRefresh >= 0, "No refresh at period=$period, frames=$frames")
                firstRefresh
            }
            val range = if (frames == 10) 216_000_000L..235_000_000L else 383_000_000L..402_000_000L
            assertTrue(times.all { it in range }, "Refresh moved with sampling rate: $times")
            assertTrue(times.max() - times.min() <= 16_666_667L)
        }
    }

    @Test
    fun `changing zoom reference restarts stability even after a long wait`() {
        val cadence = LayerBufferManager.ZoomCacheCadence()
        for (now in listOf(0L, 100_000_000L, 200_000_000L)) {
            assertFalse(cadence.shouldRefresh(now, false, true, 10))
        }
        assertFalse(cadence.shouldRefresh(250_000_000L, true, true, 10))
        assertFalse(cadence.shouldRefresh(400_000_000L, true, true, 10))
        assertTrue(cadence.shouldRefresh(420_000_000L, true, true, 10))
    }

    @Test
    fun `a small zoom difference does not spend the pending refresh budget`() {
        val cadence = LayerBufferManager.ZoomCacheCadence()
        assertFalse(cadence.shouldRefresh(0, true, false, 10))
        assertFalse(cadence.shouldRefresh(100_000_000L, true, false, 10))
        assertFalse(cadence.shouldRefresh(200_000_000L, true, true, 10))
        assertFalse(cadence.shouldRefresh(250_000_000L, true, false, 10))
        assertFalse(cadence.shouldRefresh(300_000_000L, true, true, 10))
        assertTrue(cadence.shouldRefresh(320_000_000L, true, true, 10))
    }

    @Test
    fun `reset drops time accumulated before a grid or backend change`() {
        val cadence = LayerBufferManager.ZoomCacheCadence()
        assertFalse(cadence.shouldRefresh(0, true, true, 10))
        assertFalse(cadence.shouldRefresh(200_000_000L, true, true, 10))
        cadence.reset()
        assertFalse(cadence.shouldRefresh(10_000_000_000L, true, true, 10))
        assertFalse(cadence.shouldRefresh(10_200_000_000L, true, true, 10))
        assertTrue(cadence.shouldRefresh(10_220_000_000L, true, true, 10))
        assertFalse(cadence.shouldRefresh(10_220_000_000L, true, true, 10))
    }

    @Test
    fun `backwards and repeated timestamps cannot manufacture a refresh`() {
        val cadence = LayerBufferManager.ZoomCacheCadence()
        assertFalse(cadence.shouldRefresh(1_000_000_000L, true, true, 10))
        assertFalse(cadence.shouldRefresh(1_200_000_000L, true, true, 10))
        assertFalse(cadence.shouldRefresh(800_000_000L, true, true, 10))
        assertFalse(cadence.shouldRefresh(800_000_000L, true, true, 10))
        assertFalse(cadence.shouldRefresh(1_000_000_000L, true, true, 10))
        assertTrue(cadence.shouldRefresh(1_020_000_000L, true, true, 10))
    }
}
