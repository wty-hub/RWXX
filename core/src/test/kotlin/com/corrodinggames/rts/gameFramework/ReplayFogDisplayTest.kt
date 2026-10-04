package com.corrodinggames.rts.gameFramework

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.ai.AIController
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.UnitTypeEnum
import kotlin.test.*

class ReplayFogDisplayTest {
    private val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val registryField = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
    private fun withReplay(block: (GameLogic, ReplayEngine) -> Unit) {
        val previousEngine = instanceField.get(null)
        instanceField.set(null, null)
        val engine = GameLogic()
        val previousTeams = registryField.get(null) as Array<*>
        registryField.set(null, arrayOfNulls<PlayerTeam>(previousTeams.size))
        try {
            val replay = ReplayEngine()
            engine.replayEngine = replay
            engine.tileMap = TileMap()
            replay.P = true
            ReplayEngine::class.java.getDeclaredField("isReplaying").apply { isAccessible = true }.setBoolean(replay, true)
            block(engine, replay)
        } finally {
            instanceField.set(null, previousEngine)
            registryField.set(null, previousTeams)
        }
    }

    @Test fun `disabled display skips grid allocation and resumes immediately at the same paused tick`() = withReplay { engine, replay ->
        GameTeam(0)
        engine.tileMap.tileCountX = 4
        engine.tileMap.tileCountY = 4
        engine.currentTick = 7
        replay.updateRealtimeFog(engine)
        assertNull(replay.getFogDisplayData(0))
        replay.setFogDisplayEnabled(true)
        val grid = replay.getFogDisplayData(0)
        assertNotNull(grid)
        grid[0][0] = 0
        replay.setFogDisplayEnabled(false)
        engine.currentTick++
        replay.updateRealtimeFog(engine)
        assertEquals(0, grid[0][0].toInt(), "Disabled display must not even refog its cached grid")
        replay.setFogDisplayEnabled(true)
        assertEquals(5, grid[0][0].toInt(), "Re-enabling at the same tick refreshes visibility immediately")
        assertTrue(engine.tileMap.isFogDisplayEnabled)
    }

    @Test fun `selectable slots retain AI and defeated players and exclude spectators and neutral`() = withReplay { _, replay ->
        GameTeam(4).teamName = "four"
        GameTeam(0).isTeamWipedOut = true
        val previousTypes = UnitTypeEnum.ae
        try {
            UnitTypeEnum.ae = arrayListOf()
            AIController(2)
        } finally { UnitTypeEnum.ae = previousTypes }
        GameTeam(1).teamColorId = -3
        assertFalse(ReplayEngine.isFogDisplayCandidate(null))
        assertFalse(ReplayEngine.isFogDisplayCandidate(PlayerTeam.TEAM_ALL))
        assertEquals(listOf(0, 2, 4), replay.fogDisplayTeams.map { it.teamId })
    }

    @Test fun `fog toggle selects first remembers player and resolves new objects after reload`() = withReplay { engine, replay ->
        val first = GameTeam(0)
        val second = GameTeam(2)
        engine.playerTeam = PlayerTeam.TEAM_ALL
        first.fogOfWarData = arrayOf(byteArrayOf(10))
        second.fogOfWarData = arrayOf(byteArrayOf(0))
        engine.tileMap.tileCountX = 1
        engine.tileMap.tileCountY = 1
        replay.updateRealtimeFog(engine)
        assertFalse(replay.isFogDisplayEnabled)
        assertFalse(engine.tileMap.isFogDisplayEnabled)
        replay.setFogDisplayEnabled(true)
        assertEquals(0, replay.fogDisplayTeamId)
        assertNotSame(first.fogOfWarData, engine.tileMap.fogDisplayData)
        assertSame(replay.getFogDisplayData(0), engine.tileMap.fogDisplayData)
        replay.setFogDisplayTeamId(2)
        replay.setFogDisplayEnabled(false)
        replay.setFogDisplayEnabled(true)
        assertEquals(2, replay.fogDisplayTeamId)
        val replacement = GameTeam(2)
        replacement.fogOfWarData = arrayOf(byteArrayOf(5))
        assertSame(replacement, replay.fogDisplayTeam)
        assertNotSame(replacement.fogOfWarData, engine.tileMap.fogDisplayData)
        assertSame(replay.getFogDisplayData(2), engine.tileMap.fogDisplayData)
        assertSame(PlayerTeam.TEAM_ALL, engine.playerTeam)
        assertTrue(engine.tileMap.fogEnabled)
        assertEquals(10, first.fogOfWarData[0][0].toInt())
        replay.e()
        assertFalse(replay.isFogDisplayEnabled)
        assertEquals(-1, replay.fogDisplayTeamId)
    }

    @Test fun `realtime fog needs no stored player data or active simulation fog and ignores seeking edits`() = withReplay { engine, replay ->
        replay.setFogDisplayEnabled(true)
        assertFalse(replay.isFogDisplayEnabled)
        val team = GameTeam(0)
        replay.setFogDisplayEnabled(true)
        assertTrue(replay.isFogDisplayEnabled)
        assertFalse(engine.tileMap.isFogDisplayEnabled)
        engine.tileMap.tileCountX = 1
        engine.tileMap.tileCountY = 1
        replay.updateRealtimeFog(engine)
        assertNull(team.fogOfWarData)
        assertTrue(engine.tileMap.isFogDisplayEnabled)
        engine.tileMap.fogEnabled = false
        assertTrue(engine.tileMap.isFogDisplayEnabled)
        ReplayEngine::class.java.getDeclaredField("restoringSeek").apply { isAccessible = true }.setBoolean(replay, true)
        replay.setFogDisplayEnabled(false)
        assertTrue(replay.isFogDisplayEnabled)
        replay.e() // internal rewind reload must retain presentation state
        assertTrue(replay.isFogDisplayEnabled)
        assertEquals(0, replay.fogDisplayTeamId)
        assertFalse(engine.tileMap.fogEnabled)
    }

    @Test fun `refog invalidates previously clear edge masks and their neighbors`() = withReplay { engine, _ ->
        val map = engine.tileMap
        map.tileCountX = 4
        map.tileCountY = 4
        map.ensureFogCacheAllocated()
        map.fogOfWarCurrent.forEach { it.fill(0) }
        map.fogOfWarNext.forEach { it.fill(0) }
        val previous = Array(4) { ByteArray(4) }
        val current = Array(4) { ByteArray(4) }
        current[1][1] = 5
        map.updateRealtimeFogDisplay(previous, current)
        for (x in 0..2) for (y in 0..2) {
            assertEquals(127, map.fogOfWarCurrent[x][y].toInt())
            assertEquals(127, map.fogOfWarNext[x][y].toInt())
        }
        assertEquals(0, map.fogOfWarNext[3][3].toInt())
    }
}
