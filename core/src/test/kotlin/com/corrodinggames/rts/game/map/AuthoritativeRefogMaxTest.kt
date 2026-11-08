package com.corrodinggames.rts.game.map

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.ui.Minimap
import java.util.IdentityHashMap
import kotlin.test.*

/** Staging candidate only. All assertions invoke actual native advanceFogVisibility, without GUI. */
class AuthoritativeRefogMaxTest {
    private val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
    private val fogField = PlayerTeam::class.java.getField("fogOfWarData")

    private fun withEngine(block: (GameLogic) -> Unit) {
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion; val previousHeadless = GameEngine.isHeadlessMode
        val previousTeams = registry.get(null); val previousNeutral = PlayerTeam.TEAM_NEUTRAL; val previousEnemies = PlayerTeam.TEAM_ENEMIES
        val previousObjects = GameObject.fastGameObjectList.toList(); val previousBuffers = TileMap.layerBufferManager
        instance.set(null, null); GameEngine.isNonAndroidVersion = true; GameEngine.isHeadlessMode = true
        try {
            val engine = GameLogic()
            PlayerTeam.TEAM_NEUTRAL = 10; PlayerTeam.TEAM_ENEMIES = 10 + PlayerTeam.TEAM_ALL_NEUTRAL
            registry.set(null, arrayOfNulls<PlayerTeam>(PlayerTeam.TEAM_ENEMIES)); GameObject.fastGameObjectList.clear()
            engine.networkEngine = NetworkEngine(); engine.replayEngine = ReplayEngine(); engine.minimap = Minimap()
            block(engine)
        } finally {
            TileMap.layerBufferManager = previousBuffers
            GameObject.fastGameObjectList.clear(); previousObjects.forEach { GameObject.fastGameObjectList.add(it) }
            registry.set(null, previousTeams); PlayerTeam.TEAM_NEUTRAL = previousNeutral; PlayerTeam.TEAM_ENEMIES = previousEnemies
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = previousPlatform; GameEngine.isHeadlessMode = previousHeadless
        }
    }

    private fun prepare(engine: GameLogic, width: Int, height: Int, fogs: List<Array<ByteArray?>?>,
                        optimized: Boolean = true): Pair<TileMap, List<GameTeam>> {
        registry.set(null, arrayOfNulls<PlayerTeam>(PlayerTeam.TEAM_ENEMIES))
        engine.currentTick = 0; engine.networkEngine.networkGameActive = false; engine.replayEngine.P = false
        val map = TileMap().apply {
            tileCountX = width; tileCountY = height; tileWorldSizeX = 20; tileWorldSizeY = 20; tileScaleX = .05f; tileScaleY = .05f
            fogEnabled = true; fogPeriodicMaintenanceEnabled = true
        }
        engine.tileMap = map
        val teams = fogs.mapIndexed { index, fog -> GameTeam(index).apply {
            teamColorId = 0; fogOfWarWidth = width; fogOfWarHeight = height; fogField.set(this, fog)
        } }
        engine.playerTeam = teams.first(); engine.networkEngine.localPlayerTeam = teams.first()
        engine.minimap.isFogRefreshPending = false
        if (optimized) map.disableDisplayCachesForAuthoritativeHeadless()
        return map to teams
    }

    /** Original signed comparison and iteration order, with no shape normalization or eager accesses. */
    private fun reference(fog: Array<ByteArray?>?, width: Int, height: Int) {
        for (x in 0 until width) for (y in 0 until height) {
            if (fog!![x]!![y] < 5) fog[x]!![y] = 5
        }
    }
    private fun cloneFog(source: Array<ByteArray?>?): Array<ByteArray?>? {
        val copies = IdentityHashMap<ByteArray, ByteArray>()
        return source?.map { column -> column?.let { copies.getOrPut(it) { it.copyOf() } } }?.toTypedArray()
    }
    private fun assertFog(expected: Array<ByteArray?>?, actual: Array<ByteArray?>?, label: String) {
        if (expected == null || actual == null) { assertEquals(expected, actual, label); return }
        assertEquals(expected.size, actual.size, label)
        expected.indices.forEach { x -> assertContentEquals(expected[x], actual[x], "$label column$x") }
    }
    private fun failureFamily(error: Throwable?): Class<*>? = when (error) {
        null -> null
        is NullPointerException -> NullPointerException::class.java
        is ArrayIndexOutOfBoundsException -> ArrayIndexOutOfBoundsException::class.java
        else -> error.javaClass
    }

    @Test fun `native headless refog matches all 256 signed byte inputs without touching display buffers`() = withEngine { engine ->
        val initial = Array<ByteArray?>(16) { x -> ByteArray(16) { y -> (x * 16 + y - 128).toByte() } }
        val expected = cloneFog(initial); reference(expected, 16, 16)
        val actual = cloneFog(initial); val (map, _) = prepare(engine, 16, 16, listOf(actual))
        map.advanceFogVisibility(261f)
        assertFog(expected, actual, "all signed bytes")
        assertNull(map.fogOfWarCurrent); assertNull(map.fogOfWarNext); assertFalse(engine.minimap.isFogRefreshPending)
        for (value in -128..127) assertEquals(if (value < 5) 5 else value, actual!![(value + 128) / 16]!![(value + 128) % 16].toInt())
    }

    @Test fun `native malformed and empty dimensions preserve original exception and prefix writes`() = withEngine { engine ->
        data class Case(val name: String, val width: Int, val height: Int, val make: () -> Array<ByteArray?>?)
        val cases = listOf(
            Case("zero width null outer", 0, 4) { null },
            Case("negative width null outer", -1, 4) { null },
            Case("zero height null outer", 3, 0) { null },
            Case("negative height null outer", 3, -1) { null },
            Case("zero height empty outer", 4, 0) { emptyArray<ByteArray?>() },
            Case("positive null outer", 2, 3) { null },
            Case("short outer after valid columns", 3, 3) { arrayOf<ByteArray?>(byteArrayOf(0, 6, -128), byteArrayOf(4, 10, 127)) },
            Case("null later column", 2, 4) { arrayOf<ByteArray?>(byteArrayOf(0, 6, -1, 127), null) },
            Case("short later column", 2, 4) { arrayOf<ByteArray?>(byteArrayOf(0, 6, -1, 127), byteArrayOf(0, 6)) },
            Case("empty first column", 2, 3) { arrayOf<ByteArray?>(byteArrayOf(), byteArrayOf(0, 1, 2)) },
            Case("long column trailing bytes stay untouched", 1, 3) { arrayOf<ByteArray?>(byteArrayOf(0, 6, -128, 0, 1, 2)) },
            Case("aliased short columns", 2, 4) { byteArrayOf(0, 6, 4).let { arrayOf<ByteArray?>(it, it) } },
        )
        cases.forEach { case ->
            val initial = case.make(); val expected = cloneFog(initial); val actual = cloneFog(initial)
            val referenceFailure = runCatching { reference(expected, case.width, case.height) }.exceptionOrNull()
            val (map, _) = prepare(engine, case.width, case.height, listOf(actual))
            val nativeFailure = runCatching { map.advanceFogVisibility(261f) }.exceptionOrNull()
            assertEquals(failureFamily(referenceFailure), failureFamily(nativeFailure), case.name)
            assertFog(expected, actual, case.name)
            assertNull(map.fogOfWarCurrent); assertNull(map.fogOfWarNext)
        }
    }

    @Test fun `native refog observes fresh raw cells and replacements while retaining exact threshold and aliases`() = withEngine { engine ->
        fun graph(): Pair<Array<ByteArray?>, Array<ByteArray?>> {
            val shared = byteArrayOf(0, 4, 5, 10, -128, 127)
            return arrayOf<ByteArray?>(shared, byteArrayOf(6, 0, 10, 4, 127, -1), shared) to
                arrayOf<ByteArray?>(byteArrayOf(0, 5, 10, 11, -1, 4), shared, shared)
        }
        val (first, second) = graph(); val (expectedFirst, expectedSecond) = graph()
        val (map, teams) = prepare(engine, 3, 6, listOf(first, second))
        map.advanceFogVisibility(260f); map.advanceFogVisibility(0f)
        assertFog(expectedFirst, first, "strict260 threshold"); assertFog(expectedSecond, second, "second alias graph")
        map.advanceFogVisibility(1f); reference(expectedFirst, 3, 6); reference(expectedSecond, 3, 6)
        assertFog(expectedFirst, first, "first refog"); assertFog(expectedSecond, second, "second refog")
        assertSame(first[0], first[2]); assertSame(first[0], second[1]); assertSame(second[1], second[2])
        for (value in listOf(-128, -1, 0, 4, 5, 10, 127)) {
            first[0]!![2] = value.toByte(); expectedFirst[0]!![2] = value.toByte()
            map.advanceFogVisibility(260f)
            assertFog(expectedFirst, first, "no early refog after raw write$value")
            map.advanceFogVisibility(.25f); reference(expectedFirst, 3, 6); reference(expectedSecond, 3, 6)
            assertFog(expectedFirst, first, "current raw bytes$value"); assertFog(expectedSecond, second, "cross-team alias$value")
        }
        val replacement = Array<ByteArray?>(3) { x -> ByteArray(6) { y -> ((x + y) % 3 - 1).toByte() } }
        val expectedReplacement = cloneFog(replacement)
        fogField.set(teams[0], replacement)
        map.advanceFogVisibility(261f); reference(expectedReplacement, 3, 6); reference(expectedSecond, 3, 6)
        assertSame(replacement, teams[0].fogOfWarData)
        assertFog(expectedReplacement, replacement, "replacement outer and columns"); assertFog(expectedSecond, second, "remaining old aliases")
    }

    @Test fun `ordinary display path retains invalidation while both native paths have identical fog bytes`() = withEngine { engine ->
        data class Result(val fog: Array<ByteArray?>?, val dirty: Boolean, val maskChanged: Boolean, val minimap: Boolean)
        fun run(optimized: Boolean): Result {
            val fog = Array<ByteArray?>(16) { x -> ByteArray(16) { y -> (x * 16 + y - 128).toByte() } }
            val (map, _) = prepare(engine, 16, 16, listOf(fog), optimized = false)
            val buffers = LayerBufferManager().apply {
                gridCellsPerAxis = 4; cellWorldStepSize = 200; cellWorldExtent = 200; invCellWorldStepSize = 1f / 200
                gridCells = Array(4) { x -> Array(4) { y -> LayerBufferCell(this, x, y).apply {
                    needsRedraw = false; enableSmoothFade = false; preRendered = true
                } } }
            }
            TileMap.layerBufferManager = buffers
            map.ensureFogCacheAllocated(); map.fogOfWarCurrent.forEach { it.fill(3) }; map.fogOfWarNext.forEach { it.fill(3) }
            if (optimized) map.disableDisplayCachesForAuthoritativeHeadless()
            map.advanceFogVisibility(261f)
            return Result(fog, buffers.gridCells.any { column -> column.any { it.needsRedraw || it.enableSmoothFade } },
                map.fogOfWarCurrent.any { column -> column.any { it != 3.toByte() } } || map.fogOfWarNext.any { column -> column.any { it != 3.toByte() } },
                engine.minimap.isFogRefreshPending)
        }
        val legacy = run(false); val headless = run(true)
        assertFog(legacy.fog, headless.fog, "default display path vs explicit headless")
        assertTrue(legacy.dirty); assertTrue(legacy.maskChanged); assertTrue(legacy.minimap)
        assertFalse(headless.dirty); assertFalse(headless.maskChanged); assertFalse(headless.minimap)
    }
}
