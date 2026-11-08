package com.corrodinggames.rts.game.map

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.mission.MissionEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.ui.Minimap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.*

/** Executes the original native circles/refog on both sides; only the explicit zero-row opt-in differs. */
class AuthoritativeZeroFogRowsTest {
    private val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
    private val replaying = ReplayEngine::class.java.getDeclaredField("isReplaying").apply { isAccessible = true }

    private fun withEngine(block: (GameLogic) -> Unit) {
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        val previousHeadless = GameEngine.isHeadlessMode
        instance.set(null, null); GameEngine.isNonAndroidVersion = true; GameEngine.isHeadlessMode = true
        val engine = GameLogic()
        val previousTeams = registry.get(null)
        val previousNeutral = PlayerTeam.TEAM_NEUTRAL
        val previousEnemies = PlayerTeam.TEAM_ENEMIES
        val previousObjects = GameObject.fastGameObjectList.toList()
        val previousBuffers = TileMap.layerBufferManager
        // NetworkEngine constructs a spectator before engine.networkEngine has been assigned.
        PlayerTeam.TEAM_NEUTRAL = 10
        PlayerTeam.TEAM_ENEMIES = 10 + PlayerTeam.TEAM_ALL_NEUTRAL
        registry.set(null, arrayOfNulls<PlayerTeam>(PlayerTeam.TEAM_ENEMIES))
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
            PlayerTeam.TEAM_NEUTRAL = previousNeutral; PlayerTeam.TEAM_ENEMIES = previousEnemies
            registry.set(null, previousTeams); instance.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform; GameEngine.isHeadlessMode = previousHeadless
        }
    }

    private fun newMap() = TileMap().apply {
        tileCountX = 32; tileCountY = 24
        tileWorldSizeX = 20; tileWorldSizeY = 20; tileScaleX = .05f; tileScaleY = .05f
        fogEnabled = true; fogPeriodicMaintenanceEnabled = true
    }

    private fun teams(engine: GameLogic) = List(4) { id -> GameTeam(id).apply {
        teamColorId = if (id == 2) 1 else 0
        isTeamSpectator = id == 3
        fogOfWarWidth = 32; fogOfWarHeight = 24
        fogOfWarData = Array(32) { ByteArray(24) { 10 } }
    } }.also { engine.playerTeam = it[0] }

    private fun prepare(engine: GameLogic, enabled: Boolean): Pair<TileMap, List<GameTeam>> {
        engine.currentTick = 0
        engine.networkEngine.networkGameActive = false
        engine.replayEngine.P = false
        engine.missionEngine = null
        engine.tileMap = newMap()
        val map = engine.tileMap
        val teams = teams(engine)
        engine.minimap.isFogRefreshPending = false
        map.disableDisplayCachesForAuthoritativeHeadless()
        if (enabled) map.enableAuthoritativeHeadlessZeroFogRows()
        return map to teams
    }

    private data class Sample(val fog: ByteArray, val visibility: BooleanArray, val cursorX: Int, val cursorY: Int)

    private fun sample(engine: GameLogic, map: TileMap, teams: List<GameTeam>): Sample {
        val cursorX = map.cursorTileX; val cursorY = map.cursorTileY
        val fog = ByteArray(teams.size * 32 * 24)
        val visibility = BooleanArray(fog.size * 2)
        for ((teamIndex, team) in teams.withIndex()) for (x in 0 until 32) for (y in 0 until 24) {
            val index = teamIndex * 32 * 24 + x * 24 + y
            fog[index] = team.fogOfWarData[x][y]
            visibility[index * 2] = map.isTileVisibleForTeam(x, y, team)
            visibility[index * 2 + 1] = map.isWorldPointVisibleForTeam(x * 20f + 10, y * 20f + 10, team)
        }
        assertNull(map.fogOfWarCurrent)
        assertNull(map.fogOfWarNext)
        assertFalse(engine.minimap.isFogRefreshPending)
        return Sample(fog, visibility, cursorX, cursorY)
    }

    private fun same(expected: List<Sample>, actual: List<Sample>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertContentEquals(expected[index].fog, actual[index].fog, "Fog differs after native operation $index")
            assertContentEquals(expected[index].visibility, actual[index].visibility, "Target visibility differs after $index")
            assertEquals(expected[index].cursorX, actual[index].cursorX)
            assertEquals(expected[index].cursorY, actual[index].cursorY)
        }
    }

    @Test fun `opt in is independent map local and restricted to the new headless owner`() = withEngine { engine ->
        val map = engine.tileMap
        assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
        assertFailsWith<IllegalStateException> { map.enableAuthoritativeHeadlessZeroFogRows() }
        map.disableDisplayCachesForAuthoritativeHeadless()
        assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled, "Display opt-out must not silently enable this candidate")
        GameEngine.isHeadlessMode = false
        assertFailsWith<IllegalStateException> { map.enableAuthoritativeHeadlessZeroFogRows() }
        GameEngine.isHeadlessMode = true
        engine.networkEngine.networkGameActive = true
        assertFailsWith<IllegalStateException> { map.enableAuthoritativeHeadlessZeroFogRows() }
        engine.networkEngine.networkGameActive = false
        engine.currentTick = 1
        assertFailsWith<IllegalStateException> { map.enableAuthoritativeHeadlessZeroFogRows() }
        engine.currentTick = 0
        engine.replayEngine.P = true; replaying.setBoolean(engine.replayEngine, true)
        assertFailsWith<IllegalStateException> { map.enableAuthoritativeHeadlessZeroFogRows() }
        engine.replayEngine.P = false
        val otherMap = newMap()
        engine.tileMap = otherMap; otherMap.disableDisplayCachesForAuthoritativeHeadless()
        engine.tileMap = map
        assertFailsWith<IllegalStateException> { otherMap.enableAuthoritativeHeadlessZeroFogRows() }
        map.enableAuthoritativeHeadlessZeroFogRows()
        assertTrue(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
        assertFalse(otherMap.isAuthoritativeHeadlessZeroFogRowsEnabled)
        val foreign = AtomicReference<Boolean>()
        thread { foreign.set(map.isAuthoritativeHeadlessZeroFogRowsEnabled) }.join()
        assertEquals(false, foreign.get())
        engine.tileMap = otherMap
        assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
        engine.tileMap = map
        engine.networkEngine.networkGameActive = true
        assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
        engine.networkEngine.networkGameActive = false
        engine.replayEngine.P = true
        assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
        engine.replayEngine.P = false
        GameEngine.isHeadlessMode = false
        assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
        GameEngine.isHeadlessMode = true
        map.tileCountY++
        assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
    }

    @Test fun `random overlaps sharing mission and periodic refog are native byte identical`() = withEngine { engine ->
        fun run(enabled: Boolean): List<Sample> {
            val (map, teams) = prepare(engine, enabled)
            val samples = ArrayList<Sample>()
            val random = Random(0x7f_01)
            repeat(120) { step ->
                if (step % 17 == 0) {
                    teams[0].isTeamVictory = step % 34 == 0
                    engine.missionEngine = if (step % 51 == 0) object : MissionEngine() {
                        override fun a() = false
                        override fun b() = false
                    } else null
                }
                val x = random.nextInt(-80, 721).toFloat()
                val y = random.nextInt(-80, 561).toFloat()
                val radius = random.nextInt(1, 26)
                val team = teams[if (step % 3 == 0) 2 else 0]
                map.updateFogVisibilityForTeamsAtWorldPoint(x, y, radius, team, step % 2 == 0)
                samples += sample(engine, map, teams)
                // Immediate overlap is the important all-zero candidate, with no saved hit result.
                map.updateFogVisibilityForTeamsAtWorldPoint(x, y, radius, team, step % 2 != 0)
                samples += sample(engine, map, teams)
                if (step % 9 == 0) {
                    map.advanceFogVisibility(261f)
                    samples += sample(engine, map, teams)
                }
            }
            return samples
        }
        same(run(false), run(true))
    }

    @Test fun `zero mixed and aliased rows always inspect current cells including between call writes`() = withEngine { engine ->
        fun run(enabled: Boolean): List<Sample> {
            val (map, teams) = prepare(engine, enabled)
            teams.forEach { team -> team.fogOfWarData.forEach { it.fill(0) } }
            teams[1].fogOfWarData = teams[0].fogOfWarData // Both outer array and every column alias.
            for (x in 1 until 32 step 2) teams[2].fogOfWarData[x] = teams[2].fogOfWarData[x - 1]
            teams[2].fogOfWarData[10] = teams[0].fogOfWarData[12] // Cross-team, different-x alias.
            val samples = ArrayList<Sample>()
            fun reveal() {
                map.updateFogVisibilityForTeamsAtWorldPoint(220f, 220f, 15, teams[0], true)
                map.updateFogVisibilityForTeamCircle(200f, 240f, 15, teams[2], false)
                samples += sample(engine, map, teams)
            }
            reveal()
            for (value in listOf(5, 10, -1, 11, 127, 0)) {
                teams[0].fogOfWarData[10][10] = value.toByte()
                teams[2].fogOfWarData[10][11] = value.toByte()
                reveal()
            }
            teams[0].fogOfWarData[10] = ByteArray(24) { 10 }
            reveal()
            teams[0].fogOfWarData = Array(32) { x -> ByteArray(24) { y -> if ((x + y) % 3 == 0) 0 else 10 } }
            reveal()
            map.advanceFogVisibility(261f)
            samples += sample(engine, map, teams)
            reveal()
            return samples
        }
        same(run(false), run(true))
    }

    @Test fun `lost runtime conditions execute the original reveal path`() = withEngine { engine ->
        fun run(enabled: Boolean): List<Sample> {
            val (map, teams) = prepare(engine, enabled)
            val samples = ArrayList<Sample>()
            fun reveal() {
                map.updateFogVisibilityForTeamCircle(200f, 200f, 8, teams[0], true)
                samples += sample(engine, map, teams)
                teams[0].fogOfWarData.forEach { it.fill(10) }
            }
            engine.networkEngine.networkGameActive = true
            assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
            reveal()
            engine.networkEngine.networkGameActive = false
            engine.replayEngine.P = true; replaying.setBoolean(engine.replayEngine, true)
            assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
            reveal()
            engine.replayEngine.P = false
            GameEngine.isHeadlessMode = false
            assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
            reveal()
            GameEngine.isHeadlessMode = true
            engine.tileMap = newMap()
            assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
            reveal()
            engine.tileMap = map
            val failure = AtomicReference<Throwable?>()
            thread { failure.set(runCatching {
                assertFalse(map.isAuthoritativeHeadlessZeroFogRowsEnabled)
                map.updateFogVisibilityForTeamCircle(200f, 200f, 8, teams[0], true)
            }.exceptionOrNull()) }.join()
            assertNull(failure.get())
            samples += sample(engine, map, teams)
            return samples
        }
        same(run(false), run(true))
    }

    @Test fun `first mismatch offsets skip only the current zero prefix inside the requested row range`() = withEngine { engine ->
        fun run(enabled: Boolean): List<Sample> {
            val (map, teams) = prepare(engine, enabled)
            val team = teams[0]
            val samples = ArrayList<Sample>()
            fun reveal() {
                // Tile center (10,10), radius4 visits y=7..13: the range does not start at zero.
                map.updateFogVisibilityForTeamCircle(200f, 200f, 4, team, true)
                assertEquals(127, team.fogOfWarData[10][6].toInt(), "A relative offset must not extend the range upward")
                assertEquals(127, team.fogOfWarData[10][14].toInt(), "Cells below the requested range remain untouched")
                samples += sample(engine, map, teams)
            }
            for (first in listOf(7, 10, 13)) for (value in listOf(-1, 5, 10, 127)) {
                team.fogOfWarData.forEach { it.fill(0) }
                team.fogOfWarData[10][6] = 127
                team.fogOfWarData[10][14] = 127
                team.fogOfWarData[10][first] = value.toByte()
                reveal()
                if (value < 0) assertEquals(-1, team.fogOfWarData[10][first].toInt(), "Negative is nonzero, not a skippable cell")
                // No native tick is advanced. A mod changes an earlier zero prefix between calls.
                for (changed in listOf(5, 10)) {
                    team.fogOfWarData[10][7] = changed.toByte()
                    team.fogOfWarData[10][8] = changed.toByte()
                    reveal()
                    assertTrue(team.fogOfWarData[10][8] < changed, "The newly nonzero prefix must be processed immediately")
                }
            }
            team.fogOfWarData.forEach { it.fill(0) }
            team.fogOfWarData[10][6] = 127
            team.fogOfWarData[10][14] = 127
            reveal() // Only out-of-range nonzero bytes: the compared range is entirely zero.
            return samples
        }
        same(run(false), run(true))
    }

    @Test fun `empty ranges and malformed native columns retain native effects and exceptions`() = withEngine { engine ->
        data class Result(val error: String?, val cells: List<List<Byte>?>, val cursorX: Int, val cursorY: Int)
        fun run(enabled: Boolean, variant: Int): Result {
            val (map, teams) = prepare(engine, enabled)
            val team = teams[0]
            when (variant) {
                0 -> team.fogOfWarData[10] = byteArrayOf(10)
                1 -> team.fogOfWarData[10] = null
                2 -> team.fogOfWarData = Array(11) { ByteArray(24) { 10 } }
            }
            val error = runCatching {
                when (variant) {
                    3 -> map.updateFogVisibilityForTeamCircle(-10_000f, -10_000f, 1, team, false)
                    4 -> map.updateFogVisibilityForTeamCircle(200f, 200f, 0, team, false)
                    5 -> map.updateFogVisibilityForTeamCircle(Float.NaN, Float.POSITIVE_INFINITY, 1, team, true)
                    6 -> map.updateFogVisibilityForTeamCircle(200f, 200f, 1, team, true)
                    else -> map.updateFogVisibilityForTeamCircle(200f, 200f, 8, team, true)
                }
            }.exceptionOrNull()
            return Result(error?.javaClass?.name, team.fogOfWarData.map { it?.toList() }, map.cursorTileX, map.cursorTileY)
        }
        repeat(7) { variant -> assertEquals(run(false, variant), run(true, variant), "Native fallback differs for $variant") }
    }
}
