package com.corrodinggames.rts.game.map

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.ui.Minimap
import kotlin.random.Random
import kotlin.test.*

/** Calls the native reveal, team sharing and periodic refog algorithms; no renderer or simulated fog mock. */
class AuthoritativeHeadlessFogCacheTest {
    private val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }

    private fun withEngine(block: (GameLogic) -> Unit) {
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        val previousHeadless = GameEngine.isHeadlessMode
        instance.set(null, null); GameEngine.isNonAndroidVersion = true; GameEngine.isHeadlessMode = true
        val engine = GameLogic()
        val previousTeams = registry.get(null)
        val previousObjects = GameObject.fastGameObjectList.toList()
        val previousBuffers = TileMap.layerBufferManager
        registry.set(null, arrayOfNulls<PlayerTeam>((previousTeams as Array<*>).size))
        GameObject.fastGameObjectList.clear()
        try {
            engine.networkEngine = NetworkEngine()
            engine.replayEngine = ReplayEngine()
            engine.minimap = Minimap()
            engine.tileMap = newMap()
            block(engine)
        } finally {
            TileMap.layerBufferManager = previousBuffers
            GameObject.fastGameObjectList.clear(); previousObjects.forEach { GameObject.fastGameObjectList.add(it) }
            registry.set(null, previousTeams); instance.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform; GameEngine.isHeadlessMode = previousHeadless
        }
    }

    private fun newMap() = TileMap().apply {
        tileCountX = 32; tileCountY = 24
        tileWorldSizeX = 20; tileWorldSizeY = 20; tileScaleX = .05f; tileScaleY = .05f
        fogEnabled = true; fogPeriodicMaintenanceEnabled = true
    }

    private fun teams(engine: GameLogic): List<GameTeam> = List(3) { id -> GameTeam(id).apply {
        teamColorId = if (id < 2) 0 else 1
        fogOfWarWidth = 32; fogOfWarHeight = 24
        fogOfWarData = Array(32) { ByteArray(24) { 10 } }
    } }.also { engine.playerTeam = it[0] }

    private fun buffers(): LayerBufferManager = LayerBufferManager().apply {
        gridCellsPerAxis = 4; cellWorldStepSize = 200; cellWorldExtent = 200; invCellWorldStepSize = 1f / 200
        gridCells = Array(4) { x -> Array(4) { y -> LayerBufferCell(this, x, y).apply {
            needsRedraw = false; enableSmoothFade = false; preRendered = true
        } } }
        TileMap.layerBufferManager = this
    }

    @Test fun `headless alone retains legacy maintenance and explicit opt in rejects incompatible modes`() = withEngine { engine ->
        val map = engine.tileMap
        assertFalse(map.isAuthoritativeHeadlessDisplayCachesDisabled)
        map.ensureFogCacheAllocated()
        assertNotNull(map.fogOfWarCurrent, "Ordinary headless and replay runs must not implicitly skip display work")
        GameEngine.isHeadlessMode = false
        assertFailsWith<IllegalStateException> { map.disableDisplayCachesForAuthoritativeHeadless() }
        GameEngine.isHeadlessMode = true
        engine.networkEngine.networkGameActive = true
        assertFailsWith<IllegalStateException> { map.disableDisplayCachesForAuthoritativeHeadless() }
        engine.networkEngine.networkGameActive = false
        engine.currentTick = 1
        assertFailsWith<IllegalStateException> { map.disableDisplayCachesForAuthoritativeHeadless() }
        engine.currentTick = 0
        engine.replayEngine.P = true
        ReplayEngine::class.java.getDeclaredField("isReplaying").apply { isAccessible = true }.setBoolean(engine.replayEngine, true)
        assertFailsWith<IllegalStateException> { map.disableDisplayCachesForAuthoritativeHeadless() }
        engine.replayEngine.P = false
        assertFailsWith<IllegalStateException> { newMap().disableDisplayCachesForAuthoritativeHeadless() }
        assertFalse(map.isAuthoritativeHeadlessDisplayCachesDisabled)
        map.disableDisplayCachesForAuthoritativeHeadless()
        assertTrue(map.isAuthoritativeHeadlessDisplayCachesDisabled)
        assertFalse(newMap().isAuthoritativeHeadlessDisplayCachesDisabled, "The opt-in is map-local, not a static replay/legacy setting")
    }

    @Test fun `native reveal sharing falloff target visibility and 260 tick refog are byte identical`() = withEngine { engine ->
        data class Result(val states: List<ByteArray>, val dirty: Boolean, val masksChanged: Boolean, val minimapPending: Boolean)
        fun run(disabled: Boolean): Result {
            engine.tileMap = newMap()
            val map = engine.tileMap
            val teams = teams(engine)
            val buffers = buffers()
            engine.minimap.isFogRefreshPending = false
            map.ensureFogCacheAllocated()
            map.fogOfWarCurrent.forEach { it.fill(3) }; map.fogOfWarNext.forEach { it.fill(3) }
            if (disabled) map.disableDisplayCachesForAuthoritativeHeadless()
            val states = ArrayList<ByteArray>()
            fun capture() {
                states += ByteArray(32 * 24 * teams.size) { index ->
                    val team = teams[index / (32 * 24)]
                    val x = index % (32 * 24) / 24; val y = index % 24
                    val value = team.fogOfWarData[x][y]
                    assertEquals(value < 5, map.isTileVisibleForTeam(x, y, team))
                    assertEquals(value < 5, map.isWorldPointVisibleForTeam(x * 20f + 10, y * 20f + 10, team))
                    value
                }
            }
            map.updateFogVisibilityForTeamsAtWorldPoint(200f, 200f, 7, teams[0], true)
            assertEquals(0, teams[0].fogOfWarData[10][10].toInt())
            assertEquals(0, teams[1].fogOfWarData[10][10].toInt(), "Native allied vision propagation remains enabled")
            assertEquals(10, teams[2].fogOfWarData[10][10].toInt())
            capture()
            map.advanceFogVisibility(261f)
            assertEquals(5, teams[0].fogOfWarData[10][10].toInt(), "Periodic logical refog is still executed")
            assertEquals(10, teams[2].fogOfWarData[10][10].toInt())
            capture()
            val random = Random(812)
            repeat(80) { iteration ->
                val team = teams[if (iteration % 3 == 0) 2 else 0]
                map.updateFogVisibilityForTeamsAtWorldPoint(random.nextInt(-30, 700).toFloat(),
                    random.nextInt(-30, 540).toFloat(), random.nextInt(1, 18), team, iteration % 2 == 0)
                if (iteration % 9 == 0) map.advanceFogVisibility(261f)
                capture()
            }
            return Result(states, buffers.gridCells.any { column -> column.any { it.needsRedraw || it.enableSmoothFade } },
                map.fogOfWarCurrent.any { column -> column.any { it != 3.toByte() } } ||
                    map.fogOfWarNext.any { column -> column.any { it != 3.toByte() } }, engine.minimap.isFogRefreshPending)
        }
        val legacy = run(false); val headless = run(true)
        assertEquals(legacy.states.size, headless.states.size)
        legacy.states.indices.forEach { assertContentEquals(legacy.states[it], headless.states[it], "Native fog diverged after operation $it") }
        assertTrue(legacy.dirty); assertTrue(legacy.masksChanged); assertTrue(legacy.minimapPending)
        assertFalse(headless.dirty); assertFalse(headless.masksChanged); assertFalse(headless.minimapPending)
    }

    @Test fun `opt in does not allocate masks while native fog still reveals and refogs`() = withEngine { engine ->
        val team = teams(engine)[0]
        val map = engine.tileMap
        val buffers = buffers()
        map.disableDisplayCachesForAuthoritativeHeadless()
        map.ensureFogCacheAllocated()
        map.updateFogVisibilityForTeamCircle(200f, 200f, 7, team, true)
        assertEquals(0, team.fogOfWarData[10][10].toInt())
        map.advanceFogVisibility(261f)
        assertEquals(5, team.fogOfWarData[10][10].toInt())
        assertNull(map.fogOfWarCurrent); assertNull(map.fogOfWarNext)
        assertTrue(buffers.gridCells.all { column -> column.all { !it.needsRedraw && !it.enableSmoothFade } })
    }

    @Test fun `display only invalidation entrypoints remain inert after opt in`() = withEngine { engine ->
        teams(engine)
        val map = engine.tileMap
        val buffers = buffers()
        map.ensureFogCacheAllocated()
        map.fogOfWarCurrent.forEach { it.fill(3) }; map.fogOfWarNext.forEach { it.fill(4) }
        map.disableDisplayCachesForAuthoritativeHeadless()
        map.invalidateLayerCellAt(3, 4)
        map.invalidateLayerCellsAround(3, 4)
        map.resetFogToInvisible()
        map.invalidateFogDisplay()
        map.invalidateAllLayerCells()
        map.updateRealtimeFogDisplay(Array(32) { ByteArray(24) }, Array(32) { ByteArray(24) { 5 } })
        assertTrue(map.fogOfWarCurrent.all { column -> column.all { it == 3.toByte() } })
        assertTrue(map.fogOfWarNext.all { column -> column.all { it == 4.toByte() } })
        assertTrue(buffers.gridCells.all { column -> column.all { !it.needsRedraw && !it.enableSmoothFade } })
        assertFalse(engine.minimap.isFogRefreshPending)
    }
}
