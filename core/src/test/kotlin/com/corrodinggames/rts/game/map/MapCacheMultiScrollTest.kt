package com.corrodinggames.rts.game.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapCacheMultiScrollTest {
    private fun grid(): LayerBufferManager = LayerBufferManager().apply {
        gridCellsPerAxis = 5
        cellWorldStepSize = 100
        cellWorldExtent = 104
        gridOriginWorldX = 0
        gridOriginWorldY = 0
        gridCells = Array(5) { i -> Array(5) { j ->
            LayerBufferCell(this, i, j).apply {
                needsRedraw = false
                enableSmoothFade = false
                preRendered = true
            }
        } }
    }

    @Test
    fun diagonalMultiScrollRetainsWorldCoordinatesAndExistingDirtyState() {
        val manager = grid()
        val original = manager.gridCells.map { it.toList() }
        original[3][1].needsRedraw = true
        assertTrue(manager.scrollGridToViewport(450, -220, 180f, 180f, 20, 20))
        assertEquals(200, manager.gridOriginWorldX)
        assertEquals(-300, manager.gridOriginWorldY)
        var retained = 0
        for (i in 0 until 5) for (j in 0 until 5) {
            val cell = manager.gridCells[i][j]
            if (i < 3 && j >= 3) {
                val oldI = i + 2
                val oldJ = j - 3
                assertSame(original[oldI][oldJ], cell)
                assertEquals(oldI * 100, cell.worldLeft)
                assertEquals(oldJ * 100, cell.worldTop)
                assertEquals(oldI == 3 && oldJ == 1, cell.needsRedraw)
                assertTrue(cell.preRendered)
                retained++
            } else {
                assertTrue(cell.needsRedraw)
                assertFalse(cell.preRendered)
            }
        }
        assertEquals(6, retained)
    }

    @Test
    fun reversalMovesActualCellsBackWithoutMakingRecycledContentClean() {
        val manager = grid()
        val original = manager.gridCells.map { it.toList() }
        assertTrue(manager.scrollGridToViewport(450, 220, 180f, 180f, 20, 20))
        assertTrue(manager.scrollGridToViewport(30, 220, 180f, 180f, 20, 20))
        assertEquals(0, manager.gridOriginWorldX)
        for (i in 0 until 5) for (j in 0 until 5) {
            val cell = manager.gridCells[i][j]
            assertSame(original[i][j], cell)
            assertEquals(i * 100, cell.worldLeft)
            assertEquals(j * 100, cell.worldTop)
            assertEquals(i < 2, cell.needsRedraw)
        }
    }

    @Test
    fun maximumOverlappingShiftKeepsOneColumnAndWholeGridJumpRequestsReset() {
        val manager = grid()
        val kept = manager.gridCells[4].toList()
        assertTrue(manager.scrollGridToViewport(690, 220, 180f, 180f, 20, 20))
        assertEquals(400, manager.gridOriginWorldX)
        for (j in 0 until 5) {
            assertSame(kept[j], manager.gridCells[0][j])
            assertEquals(400, manager.gridCells[0][j].worldLeft)
            assertFalse(manager.gridCells[0][j].needsRedraw)
        }
        val jumped = grid()
        assertFalse(jumped.scrollGridToViewport(900, 220, 180f, 180f, 20, 20))
        assertEquals(400, jumped.gridOriginWorldX) // At most N-1 actual scrolls, then caller resets.
    }
}
