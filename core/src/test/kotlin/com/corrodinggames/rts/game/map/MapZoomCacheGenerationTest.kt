package com.corrodinggames.rts.game.map

import kotlin.test.*

class MapZoomCacheGenerationTest {
    private fun generation(scale: Float = .8f): Pair<LayerBufferManager, MapZoomCacheGeneration> {
        val manager = LayerBufferManager()
        val cells = Array(3) { x -> Array(3) { y -> LayerBufferCell(manager, x, y).apply {
            needsRedraw = false; enableSmoothFade = false
        } } }
        return manager to MapZoomCacheGeneration(cells, 100, 200, 635, 512, scale, 0)
    }

    @Test fun `old geometry remains in its original world range after current grid moves`() {
        val (manager, cache) = generation()
        assertTrue(cache.covers(102.0, 202.0, 1200.0, 1400.0, .85f))
        manager.gridOriginWorldX = -2000; manager.gridOriginWorldY = 3000
        manager.renderScale = .9f; manager.cellWorldStepSize = 560
        assertTrue(cache.covers(102.0, 202.0, 1200.0, 1400.0, .85f))
        assertFalse(cache.covers(-2000.0, 3000.0, -1000.0, 4000.0, .85f))
    }

    @Test fun `world coverage rejects holes stale fog and regions outside sampled interiors`() {
        val (_, cache) = generation()
        assertFalse(cache.covers(100.0, 200.0, 105.0, 205.0, .8f))
        assertTrue(cache.covers(800.0, 900.0, 900.0, 1000.0, .8f))
        cache.invalidate(830.0, 930.0, 831.0, 931.0)
        assertFalse(cache.covers(800.0, 900.0, 900.0, 1000.0, .8f))
        assertTrue(cache.covers(102.0, 202.0, 120.0, 220.0, .8f))
        assertFalse(cache.covers(102.0, 202.0, 10000.0, 220.0, .8f))
    }

    @Test fun `scale reuse has a symmetric hard limit of fifteen percent`() {
        val (_, cache) = generation(1f)
        assertTrue(cache.compatibleScale(1.15f))
        assertTrue(cache.compatibleScale(1f / 1.15f))
        assertFalse(cache.compatibleScale(1.151f))
        assertFalse(cache.compatibleScale(1f / 1.151f))
        assertFalse(cache.compatibleScale(Float.NaN))
        assertFalse(cache.compatibleScale(0f))
    }

    @Test fun `stability is fifty milliseconds of unchanged input independent of frame frequency`() {
        for (period in listOf(1_000_000L, 5_000_000L, 10_000_000L)) {
            val stability = MapZoomCacheGeneration.Stability()
            assertFalse(stability.observe(0, .8f))
            for (now in period until 50_000_000L step period) assertFalse(stability.observe(now, .8f))
            assertTrue(stability.observe(50_000_000L, .8f))
            assertFalse(stability.observe(51_000_000L, .81f))
            assertFalse(stability.observe(100_000_000L, .81f))
            assertTrue(stability.observe(101_000_000L, .81f))
            assertFalse(stability.observe(0, .81f))
        }
    }
}
