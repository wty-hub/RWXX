package com.corrodinggames.rts.game.map

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import kotlin.math.floor
import kotlin.math.max
import kotlin.test.*

class MapCacheExperimentalCellSizeTest {
    private val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousEngine: Any? = null
    private var previousPlatform = false
    private lateinit var engine: GameLogic

    @BeforeTest fun setUpEngine() {
        previousEngine = instanceField.get(null)
        previousPlatform = GameEngine.isNonAndroidVersion
        instanceField.set(null, null)
        GameEngine.isNonAndroidVersion = true
        try {
            engine = GameLogic().apply { tileMap = TileMap() }
        } catch (failure: Throwable) {
            restoreEngine()
            throw failure
        }
    }

    @AfterTest fun restoreEngine() {
        instanceField.set(null, previousEngine)
        GameEngine.isNonAndroidVersion = previousPlatform
    }

    private fun required(size: Int, width: Int, height: Int, current: Int = 0) =
        LayerBufferManager.fixedLayerBufferGridCells(current, max(width, height) + 3, size, 1)

    private fun grid(size: Int, width: Int = 1920, height: Int = 1080): LayerBufferManager = LayerBufferManager().apply {
        cellBufferPixelSize = size
        cellInnerBufferPixelSize = size - 4
        gridCellsPerAxis = required(size, width, height)
        gridCells = Array(gridCellsPerAxis) { x -> Array(gridCellsPerAxis) { y -> LayerBufferCell(this, x, y) } }
        updateGridParams()
    }

    private fun makeClean(manager: LayerBufferManager) {
        manager.gridCells.forEach { column -> column.forEach {
            it.needsRedraw = false
            it.enableSmoothFade = false
            it.preRendered = true
        } }
    }

    private fun assertViewportCovered(manager: LayerBufferManager, cameraX: Int, cameraY: Int, width: Float, height: Float) {
        // Check the union of actual cell world rectangles, independently of the sizing formula.
        val first = manager.gridCells[0][0].getWorldBoundsRect()
        val last = manager.gridCells.last().last().getWorldBoundsRect()
        assertTrue(first.a <= cameraX && first.b <= cameraY)
        assertTrue(last.c >= cameraX + width && last.d >= cameraY + height)
        for (x in 1 until manager.gridCellsPerAxis) {
            val previous = manager.gridCells[x - 1][0].getWorldBoundsRect()
            val next = manager.gridCells[x][0].getWorldBoundsRect()
            assertTrue(previous.c >= next.a, "Horizontal cell gap at $x")
        }
        for (y in 1 until manager.gridCellsPerAxis) {
            val previous = manager.gridCells[0][y - 1].getWorldBoundsRect()
            val next = manager.gridCells[0][y].getWorldBoundsRect()
            assertTrue(previous.d >= next.b, "Vertical cell gap at $y")
        }
    }

    @Test fun `experimental sizes retain startup fullscreen portrait and four K targets through growth`() {
        val cases = listOf(Triple(256, listOf(7, 10, 18), 252), Triple(384, listOf(5, 8, 13), 380), Triple(512, listOf(5, 6, 10), 508))
        engine.zoom = 1f
        engine.viewpointXInt = 860
        engine.viewpointYInt = 652
        for ((size, expectedCounts, expectedStep) in cases) {
            val manager = grid(size, 1031, 720)
            assertEquals(expectedCounts[0], manager.gridCellsPerAxis)
            assertEquals(expectedStep, manager.cellWorldStepSize)
            assertEquals(size, manager.cellWorldExtent)
            for ((index, viewport) in listOf(1920 to 1080, 3840 to 2160).withIndex()) {
                val old = manager.gridCells.map { it.toList() }
                val oldCount = manager.gridCellsPerAxis
                val nextCount = required(size, viewport.first, viewport.second, oldCount)
                assertEquals(expectedCounts[index + 1], nextCount)
                assertTrue(manager.growGridStorage(nextCount))
                for (x in 0 until nextCount) for (y in 0 until nextCount) {
                    if (x < oldCount && y < oldCount) assertSame(old[x][y], manager.gridCells[x][y])
                    else {
                        assertNull(manager.gridCells[x][y])
                        manager.gridCells[x][y] = LayerBufferCell(manager, x, y)
                    }
                }
                manager.updateGridParams()
                assertViewportCovered(manager, 860, 652, viewport.first.toFloat(), viewport.second.toFloat())
            }
            val retained = manager.gridCells
            assertFalse(manager.growGridStorage(required(size, 1031, 720, manager.gridCellsPerAxis)))
            assertSame(retained, manager.gridCells)
            assertEquals(expectedCounts[1], required(size, 1080, 1920))
        }
    }

    @Test fun `render scale is capped at one and preload never causes opposite rollover at boundaries`() {
        for (size in listOf(256, 384, 512)) for (zoom in listOf(.25f, .3f, .5f, .7f, 1f, 1.3f, 2f)) {
            engine.zoom = zoom
            val manager = grid(size)
            val expectedScale = minOf(zoom, 1f)
            assertEquals(expectedScale, manager.renderScale)
            assertEquals(((size - 4) / expectedScale).toInt(), manager.cellWorldStepSize)
            assertTrue(manager.cellWorldStepSize >= 252, "Actual renderScale must not shrink below the 180-world fog neighborhood")
            val width = 1920f / zoom
            val height = 1080f / zoom
            val step = manager.cellWorldStepSize
            val cells = manager.gridCellsPerAxis
            val marginX = LayerBufferManager.boundedScrollPreloadMargin(256, cells, step, width)
            val marginY = LayerBufferManager.boundedScrollPreloadMargin(256, cells, step, height)
            for (origin in listOf(-2 * step, 0, 3 * step)) {
                manager.gridOriginWorldX = origin
                manager.gridOriginWorldY = origin
                val rightBoundary = floor(origin + cells * step - width - 4 - marginX).toInt()
                val cameraY = origin + 1 + marginY
                for (camera in rightBoundary - 2..rightBoundary + 2) {
                    if (camera + width + 4 + marginX > origin + cells * step) {
                        assertFalse(camera - 1 - marginX < origin + step, "Opposite rollover size=$size zoom=$zoom")
                    }
                }
                assertTrue(manager.scrollGridToViewport(rightBoundary + 1, cameraY, width, height, marginX, marginY))
                val movedOrigin = manager.gridOriginWorldX
                assertViewportCovered(manager, rightBoundary + 1, cameraY, width, height)
                repeat(3) {
                    assertTrue(manager.scrollGridToViewport(rightBoundary + 1, cameraY, width, height, marginX, marginY))
                    assertEquals(movedOrigin, manager.gridOriginWorldX, "Same viewport must not scroll back")
                }
            }
        }
    }

    @Test fun `multi cell diagonal rolls keep overlapping world coordinates and whole grid jumps request reset`() {
        engine.zoom = 1f
        for (size in listOf(256, 384, 512)) {
            val manager = grid(size)
            makeClean(manager)
            val step = manager.cellWorldStepSize
            val cells = manager.gridCellsPerAxis
            manager.gridOriginWorldX = 0
            manager.gridOriginWorldY = 0
            val original = manager.gridCells.map { it.toList() }
            val originalPositions = original.flatten().associateWith { it.worldLeft to it.worldTop }
            val width = 1920f
            val height = 1080f
            val mx = LayerBufferManager.boundedScrollPreloadMargin(256, cells, step, width)
            val my = LayerBufferManager.boundedScrollPreloadMargin(256, cells, step, height)
            val cameraX = (cells * step - width - 4 - mx).toInt() + 2 * step + 1
            val cameraY = -2 * step + 1 + my
            assertTrue(manager.scrollGridToViewport(cameraX, cameraY, width, height, mx, my))
            assertEquals(3 * step, manager.gridOriginWorldX)
            assertEquals(-2 * step, manager.gridOriginWorldY)
            for (x in 0 until cells) for (y in 0 until cells) {
                val cell = manager.gridCells[x][y]
                if (x < cells - 3 && y >= 2) {
                    assertSame(original[x + 3][y - 2], cell)
                    assertEquals(originalPositions[cell], cell.worldLeft to cell.worldTop)
                    assertFalse(cell.needsRedraw)
                } else assertTrue(cell.needsRedraw)
            }
            val jumped = grid(size)
            jumped.gridOriginWorldX = 0
            jumped.gridOriginWorldY = 0
            assertFalse(jumped.scrollGridToViewport(3 * cells * step, 3 * cells * step, width, height, mx, my))
            assertEquals((cells - 1) * step, jumped.gridOriginWorldX)
            assertEquals((cells - 1) * step, jumped.gridOriginWorldY)
        }
    }

    @Test fun `tile fog invalidation covers visible interiors across positive negative origins edges and corners`() {
        for (size in listOf(256, 384, 512)) for (zoom in listOf(.25f, .7f, 1f, 2f)) {
            engine.zoom = zoom
            val manager = grid(size)
            val scale = manager.renderScale.toDouble()
            val step = manager.cellWorldStepSize
            for (tileSize in listOf(20, 60)) {
                engine.tileMap.tileWorldSizeX = tileSize
                engine.tileMap.tileWorldSizeY = tileSize
                for (origin in listOf(-step + 20, step - 20)) {
                    manager.gridOriginWorldX = origin
                    manager.gridOriginWorldY = origin
                    val boundary = origin + 2 * step
                    val nearTile = floor(boundary.toDouble() / tileSize).toInt()
                    for (tileX in nearTile - 2..nearTile + 2) for (tileY in nearTile - 2..nearTile + 2) {
                        makeClean(manager)
                        manager.invalidateTileArea(tileX, tileY, false)
                        val left = tileX * tileSize.toDouble()
                        val top = tileY * tileSize.toDouble()
                        val right = left + tileSize
                        val bottom = top + tileSize
                        var intersecting = 0
                        for (column in manager.gridCells) for (cell in column) {
                            // Independent oracle: the changed tile intersects pixels displayed from this cell.
                            val cellLeft = cell.worldLeft + 1.0 / scale
                            val cellTop = cell.worldTop + 1.0 / scale
                            val cellRight = cell.worldLeft + (size - 2.0) / scale
                            val cellBottom = cell.worldTop + (size - 2.0) / scale
                            if (left < cellRight && right > cellLeft && top < cellBottom && bottom > cellTop) {
                                intersecting++
                                assertTrue(cell.needsRedraw, "Fog tile missed size=$size zoom=$zoom tileSize=$tileSize origin=$origin tile=$tileX,$tileY cell=${cell.gridX},${cell.gridY}")
                            }
                        }
                        assertTrue(intersecting > 0)
                        assertTrue(manager.gridCells.sumOf { column -> column.count { it.needsRedraw } } <= 4,
                            "An existing three-tile neighborhood fits within two cells per axis")
                    }
                }
            }
        }
    }
}
