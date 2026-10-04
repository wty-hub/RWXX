package com.corrodinggames.rts.game.map

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapCacheGridResizeTest {
    private fun required(current: Int, width: Int, height: Int, bufferPixels: Int, extra: Int = 1): Int =
        LayerBufferManager.fixedLayerBufferGridCells(current, max(width, height) + 3, bufferPixels, extra)

    @Test
    fun fixedPolicyGrowsFromStartupViewportToFullScreenAndNeverShrinks() {
        for (bufferPixels in listOf(512, 508)) {
            assertEquals(5, required(0, 1031, 720, bufferPixels))
            assertEquals(6, required(5, 1920, 1080, bufferPixels))
            assertEquals(10, required(6, 3840, 2160, bufferPixels))
            assertEquals(10, required(10, 1031, 720, bufferPixels))
            assertEquals(6, required(0, 1080, 1920, bufferPixels))
            assertEquals(11, required(10, 4096, 2160, bufferPixels))
            assertEquals(11, required(11, 3840, 2160, bufferPixels))
        }
    }

    @Test
    fun fixedPolicyKeepsExtraCellsAtPaddedWholeCellBoundary() {
        for (bufferPixels in listOf(512, 508)) {
            for (wholeCells in listOf(2, 3, 4, 8)) {
                val threshold = wholeCells * bufferPixels - 3
                assertEquals(wholeCells + 2, required(0, threshold - 1, 1, bufferPixels))
                assertEquals(wholeCells + 3, required(0, threshold, 1, bufferPixels))
            }
            assertEquals(5, required(0, 1920, 1080, bufferPixels, extra = 0))
            assertEquals(5, required(0, 1920, 1080, bufferPixels, extra = -1))
        }
    }

    @Test
    fun viewportGrowthRetainsExistingCellTargetsAndLeavesOnlyNewSlotsUnallocated() {
        for (bufferPixels in listOf(512, 508)) {
            assertStorageGrowth(bufferPixels)
        }
    }

    private fun assertStorageGrowth(bufferPixels: Int) {
        val step = bufferPixels - 4
        val manager = LayerBufferManager().apply {
            gridCellsPerAxis = required(0, 1031, 720, bufferPixels)
            gridOriginWorldX = 860
            gridOriginWorldY = 652
            cellWorldStepSize = step
            cellWorldExtent = bufferPixels
            gridCells = Array(gridCellsPerAxis) { i -> Array(gridCellsPerAxis) { j ->
                LayerBufferCell(this, i, j).apply {
                    needsRedraw = i == 2 && j == 3
                    enableSmoothFade = false
                    preRendered = true
                }
            } }
        }
        val original = manager.gridCells.map { it.toList() }
        for ((width, height) in listOf(1920 to 1080, 3840 to 2160)) {
            val oldSize = manager.gridCellsPerAxis
            val existing = manager.gridCells.map { it.toList() }
            assertTrue(manager.growGridStorage(required(oldSize, width, height, bufferPixels)))
            for (i in 0 until manager.gridCellsPerAxis) for (j in 0 until manager.gridCellsPerAxis) {
                if (i < oldSize && j < oldSize) assertSame(existing[i][j], manager.gridCells[i][j])
                else assertNull(manager.gridCells[i][j])
            }
            // Stand in for the allocator used by resizeBufferGrid; no render backend is needed.
            for (i in 0 until manager.gridCellsPerAxis) for (j in 0 until manager.gridCellsPerAxis) {
                if (manager.gridCells[i][j] == null) manager.gridCells[i][j] = LayerBufferCell(manager, i, j)
            }
        }
        val grownStorage = manager.gridCells
        assertFalse(manager.growGridStorage(required(manager.gridCellsPerAxis, 1031, 720, bufferPixels)))
        assertSame(grownStorage, manager.gridCells)
        assertEquals(10, manager.gridCellsPerAxis)
        for (i in 0 until 5) for (j in 0 until 5) {
            val cell = manager.gridCells[i][j]
            assertSame(original[i][j], cell)
            assertEquals(860 + i * step, cell.worldLeft)
            assertEquals(652 + j * step, cell.worldTop)
            assertEquals(i == 2 && j == 3, cell.needsRedraw)
            assertTrue(cell.preRendered)
        }
        // The full resize deliberately keeps its existing updateGridParams invalidation after this storage step.
    }
}
