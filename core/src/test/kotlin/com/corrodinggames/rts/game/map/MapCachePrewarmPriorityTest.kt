package com.corrodinggames.rts.game.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapCachePrewarmPriorityTest {
    private fun entry(left: Double, top: Double, right: Double, bottom: Double,
                      vx: Double, vy: Double) = LayerBufferManager.offscreenEntryTime(
        left, top, right, bottom, 0.0, 0.0, 100.0, 100.0, vx, vy)

    private fun distance(left: Double, top: Double, right: Double, bottom: Double) =
        LayerBufferManager.offscreenViewportDistanceSquared(left, top, right, bottom,
            0.0, 0.0, 100.0, 100.0)

    @Test
    fun incomingEdgePrecedesCornerAndOppositeEdge() {
        val edge = entry(110.0, 20.0, 130.0, 40.0, 10.0, 10.0)
        val corner = entry(110.0, 150.0, 130.0, 170.0, 10.0, 10.0)
        val missedCorner = entry(110.0, -70.0, 130.0, -50.0, 10.0, 10.0)
        assertEquals(1.0, edge)
        assertEquals(5.0, corner)
        assertEquals(Double.POSITIVE_INFINITY, missedCorner)
        assertTrue(LayerBufferManager.comparePrewarmPriority(edge, 100.0, corner, 2600.0) < 0)
        assertTrue(LayerBufferManager.comparePrewarmPriority(corner, 2600.0, missedCorner, 2600.0) < 0)
    }

    @Test
    fun reversalChangesPriorityAndStoppingFallsBackToDistance() {
        val left = entry(-30.0, 20.0, -10.0, 40.0, -10.0, 0.0)
        val right = entry(110.0, 20.0, 130.0, 40.0, -10.0, 0.0)
        assertEquals(1.0, left)
        assertEquals(Double.POSITIVE_INFINITY, right)
        assertEquals(Double.POSITIVE_INFINITY, entry(-30.0, 20.0, -10.0, 40.0, 0.0, 0.0))
        assertEquals(Double.POSITIVE_INFINITY, entry(110.0, 110.0, 130.0, 130.0, 0.0, 0.0))
        assertEquals(100.0, distance(-30.0, 20.0, -10.0, 40.0))
        assertEquals(200.0, distance(110.0, 110.0, 130.0, 130.0))
        assertTrue(LayerBufferManager.comparePrewarmPriority(Double.POSITIVE_INFINITY, 100.0,
            Double.POSITIVE_INFINITY, 200.0) < 0)
        assertEquals(0, LayerBufferManager.comparePrewarmPriority(Double.POSITIVE_INFINITY, 100.0,
            Double.POSITIVE_INFINITY, 100.0))
    }

    @Test
    fun trajectoryMustOverlapBothAxesAtTheSameTime() {
        // Horizontal overlap ends at t=1.3, before vertical overlap starts at t=10.
        assertEquals(Double.POSITIVE_INFINITY, entry(110.0, 200.0, 130.0, 220.0, 100.0, 10.0))
        assertEquals(0.0, entry(100.0, 20.0, 130.0, 40.0, 0.0, 0.0))
        assertEquals(Double.POSITIVE_INFINITY, entry(110.0, 20.0, 130.0, 40.0, Double.NaN, 0.0))
    }
}
