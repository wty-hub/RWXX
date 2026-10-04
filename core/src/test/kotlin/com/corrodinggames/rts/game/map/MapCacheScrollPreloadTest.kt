package com.corrodinggames.rts.game.map

import com.corrodinggames.rts.gameFramework.graphics.GraphicsBackendCapabilities
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapCacheScrollPreloadTest {
    @Test
    fun fullWidthViewportDoesNotScrollBothWaysAtTheSameBoundary() {
        for ((step, expectedMargin) in listOf(508 to 53, 504 to 45)) {
            val margin = LayerBufferManager.boundedScrollPreloadMargin(64, 5, step, 1920f)
            assertEquals(expectedMargin, margin)
            val overlapCamera = step + 45
            // The original 64-unit margin invalidates both columns every frame here.
            assertTrue(overlapCamera + 1920f + 4 + 64 > 5 * step)
            assertTrue(overlapCamera - 1 - 64 < step)
            assertFalse(overlapCamera + 1920f + 4 + margin > 5 * step)
            val camera = 5 * step - 1920 - 4 - margin + 1
            assertTrue(camera + 1920f + 4 + margin > 5 * step)
            assertFalse(camera - 1 - margin < step)
        }
    }

    @Test
    fun preloadBoundariesNeverOverlapAcrossViewportSizesAndZooms() {
        for (bufferPixels in listOf(512, 508)) {
            for (pixels in listOf(320, 1280, 1664, 1920, 2560, 3840, 4096)) {
                for (scale in listOf(.25f, .3f, .5f, .7f, 1f)) {
                    for ((extra, requested) in listOf(0 to 64, 0 to 256, 1 to 64, 1 to 256)) {
                        val cells = LayerBufferManager.fixedLayerBufferGridCells(0, pixels + 3, bufferPixels, extra)
                        val step = ((bufferPixels - 4) / scale).toInt()
                        val width = pixels / scale
                        val margin = LayerBufferManager.boundedScrollPreloadMargin(requested, cells, step, width)
                        assertTrue(margin in 0..requested)
                        for (origin in listOf(-2 * step, 0, 3 * step)) {
                            val right = ceil(origin + cells * step - width - 4 - margin).toInt()
                            for (camera in right - 3..right + 3) {
                                if (camera + width + 4 + margin > origin + cells * step) {
                                    assertFalse(camera - 1 - margin < origin + step,
                                        "Two-way scroll at buffer=$bufferPixels, width=$width, step=$step, margin=$margin, camera=$camera")
                                }
                            }
                            val left = origin + 1 + margin
                            for (camera in left - 3..left + 3) {
                                if (camera - 1 - margin < origin) {
                                    assertFalse(camera + width + 4 + margin > origin - step + cells * step)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun fastDragAndReversalRemainStableForFullScreenAndFourKGrids() {
        for (bufferPixels in listOf(512, 508)) {
            for ((width, expectedCells) in listOf(1920 to 6, 3840 to 10, 4096 to 11)) {
                val step = bufferPixels - 4
                val cells = LayerBufferManager.fixedLayerBufferGridCells(0, width + 3, bufferPixels, 1)
                assertEquals(expectedCells, cells)
                val marginX = LayerBufferManager.boundedScrollPreloadMargin(64, cells, step, width.toFloat())
                val marginY = LayerBufferManager.boundedScrollPreloadMargin(64, cells, step, 720f)
                assertEquals(64, marginX)
                val manager = LayerBufferManager().apply {
                    gridCellsPerAxis = cells
                    cellWorldStepSize = step
                    cellWorldExtent = bufferPixels
                    gridOriginWorldX = 0
                    gridOriginWorldY = 0
                    gridCells = Array(cells) { i -> Array(cells) { j ->
                        LayerBufferCell(this, i, j).apply {
                            needsRedraw = false
                            enableSmoothFade = false
                            preRendered = true
                        }
                    } }
                }
                val original = manager.gridCells.map { it.toList() }
                val cameraY = marginY + 1
                val forwardCamera = cells * step - width - 4 - marginX + 2 * step + 1
                repeat(3) {
                    assertTrue(manager.scrollGridToViewport(forwardCamera, cameraY, width.toFloat(), 720f, marginX, marginY))
                    assertEquals(3 * step, manager.gridOriginWorldX)
                    assertEquals(0, manager.gridOriginWorldY)
                }
                repeat(3) {
                    assertTrue(manager.scrollGridToViewport(marginX + 1, cameraY, width.toFloat(), 720f, marginX, marginY))
                    assertEquals(0, manager.gridOriginWorldX)
                    assertEquals(0, manager.gridOriginWorldY)
                }
                for (i in 0 until cells) for (j in 0 until cells) {
                    val cell = manager.gridCells[i][j]
                    val recycled = i < 3
                    assertSame(original[i][j], cell)
                    assertEquals(i * step, cell.worldLeft)
                    assertEquals(j * step, cell.worldTop)
                    assertEquals(recycled, cell.needsRedraw)
                    assertEquals(!recycled, cell.preRendered)
                }
            }
        }
    }

    @Test
    fun zeroMarginAndInsufficientSpacePreserveZeroPreload() {
        assertEquals(0, GraphicsBackendCapabilities().layerBufferScrollPreloadWorldMargin)
        for (step in listOf(508, 504)) {
            assertEquals(0, LayerBufferManager.boundedScrollPreloadMargin(0, 5, step, 1920f))
            assertEquals(0, LayerBufferManager.boundedScrollPreloadMargin(64, 4, step, 1920f))
            assertEquals(64, LayerBufferManager.boundedScrollPreloadMargin(64, 5, step, 1664f))
        }
    }
}
