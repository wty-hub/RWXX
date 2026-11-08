package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.buildings.CommandCenter
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.UnitStats
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.ReplayFogState
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.timeline.EngineTimelineProjector
import io.github.rwx.net.timeline.TimelineCanonical
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Native constructors/exact classes exercise the optimized path; subclasses explicitly test fallback. */
class NativeSightScopeTest {
    private class CallbackBuilder : BuilderUnit(true) {
        var onType: (() -> Unit)? = null
        override fun r(): UnitTypeEnum { onType?.invoke(); return UnitTypeEnum.builder }
    }

    private class CallbackRegistry : ArrayList<CustomUnitConfig>() {
        var reads = 0
        var onGet: (() -> Unit)? = null
        override fun get(index: Int): CustomUnitConfig { reads++; onGet?.invoke(); return super.get(index) }
    }

    private class World(val engine: GameLogic, val own: GameTeam, val enemy: GameTeam,
                        val config: CustomUnitConfig) {
        val map get() = engine.tileMap
        fun <T : BaseUnit> add(unit: T, team: PlayerTeam = own, x: Float = 10f, y: Float = 10f): T = unit.apply {
            this.team = team; posX = x; posY = y
            BaseUnit.getGlobalUnitList().add(this)
        }
        fun stock() = StockUnitCatalog.capture(engine)
        fun projector(stock: StockUnitCatalog) = EngineViewProjector(engine, own, frozenStockCatalog = stock)
    }

    private fun withWorld(block: World.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val teamRegistry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null)
        GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        val previousTeams = teamRegistry.get(null)
        val previousWorld = BaseUnit.getGlobalUnitList().toList()
        val previousRegistry = CustomUnitConfig.activeConfigs
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        BaseUnit.getGlobalUnitList().clear()
        CustomUnitConfig.activeConfigs = arrayListOf()
        CustomUnitConfig.unitTypeOverrides.clear()
        teamRegistry.set(null, arrayOfNulls<PlayerTeam>((previousTeams as Array<*>).size))
        try {
            engine.networkEngine = NetworkEngine()
            engine.networkEngine.roomSettings.fogMode = 2
            engine.networkEngine.roomSettings.revealedMap = false
            engine.commandController = CommandController()
            engine.replayEngine = ReplayEngine()
            engine.tileMap = PublicMapFixture.flat(64, 48).apply {
                fogEnabled = true; fogPeriodicMaintenanceEnabled = true
            }
            val own = GameTeam(0).apply {
                teamColorId = 0; credits = 1000.0
                fogOfWarData = Array(64) { ByteArray(48) { 10 } }
            }
            val enemy = GameTeam(1).apply { teamColorId = 1 }
            engine.playerTeam = own
            val spec = StockUnitManifest.bundled.single { it.replaces === UnitTypeEnum.tank }
            val config = CustomUnitConfig().apply {
                name = spec.name; configPath = "assets/${spec.path}"
                sourceFilePath = configPath; resourceLoadPath = configPath
                turrets = emptyArray(); frameWidth = 20; frameHeight = 20; techLevel = 1
                unitStats.maxHp = 100; unitStats.fogOfWarSightRange = 2
                unitStats.isVisibleToEnemies = true
                fogOfWarSightRangeWhileNotBuilt = -1
            }
            CustomUnitConfig.activeConfigs.add(config)
            CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = config
            World(engine, own, enemy, config).block()
        } finally {
            BaseUnit.getGlobalUnitList().clear()
            previousWorld.forEach { BaseUnit.getGlobalUnitList().add(it) }
            CustomUnitConfig.activeConfigs = previousRegistry
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            teamRegistry.set(null, previousTeams)
            instance.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    /** Isolate this private scan to check exact callback ordering without later disclosure getters. */
    private fun World.sight(projector: EngineViewProjector): ByteArray {
        val method = EngineViewProjector::class.java.getDeclaredMethod("permittedFog", map.javaClass)
            .apply { isAccessible = true }
        return try { (method.invoke(projector, map) as OwnedSeatVision.Grid).copyBytes() }
        catch (failure: InvocationTargetException) { throw failure.cause!! }
    }

    @Test fun `scope preserves aliases but rejects expired foreign owner and foreign engine access`() = withWorld {
        val stock = stock()
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        assertEquals("tank", scope.keyFor(UnitTypeEnum.tank))
        assertEquals("tank", scope.keyFor(config))
        assertEquals("builder", scope.keyFor(UnitTypeEnum.builder))
        assertNull(scope.keyFor(CustomUnitConfig().apply { name = config.name; configPath = config.configPath }))
        assertNull(stock.openNativeSightLookupOrNull(), "Nested captures must use normal validation")
        val failure = AtomicReference<Throwable?>()
        thread { failure.set(runCatching { scope.keyFor(config) }.exceptionOrNull()) }.join()
        assertIs<IllegalStateException>(failure.get())
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        instance.set(null, null)
        try { assertFailsWith<IllegalStateException> { scope.keyFor(config) } }
        finally { instance.set(null, engine) }
        scope.close()
        assertFailsWith<IllegalStateException> { scope.keyFor(config) }
        val next = assertNotNull(stock.openNativeSightLookupOrNull())
        scope.close() // An old close must not revoke a new scope.
        assertEquals("tank", next.keyFor(config))
        next.close()
    }

    @Test fun `every scope validates current metadata and a restored registry cannot revive failure`() = withWorld {
        val stock = stock()
        assertNotNull(stock.openNativeSightLookupOrNull()).close()
        config.configHash++
        assertFailsWith<IllegalStateException> { stock.openNativeSightLookupOrNull() }
        config.configHash--
        assertFailsWith<IllegalStateException> { stock.openNativeSightLookupOrNull() }
        assertNull(stock.keyFor(UnitTypeEnum.builder))
    }

    @Test fun `unknown registry container falls back without invoking its getters during eligibility check`() = withWorld {
        val registry = CallbackRegistry().apply { add(config) }
        CustomUnitConfig.activeConfigs = registry
        val stock = stock()
        registry.reads = 0
        registry.onGet = { error("Scope eligibility must not call an unreviewed collection") }
        assertNull(stock.openNativeSightLookupOrNull())
        assertEquals(0, registry.reads)
        registry.onGet = null
        add(CustomUnit(true, config))
        val view = sight(projector(stock))
        assertTrue(registry.reads > 0, "Fallback must retain ordinary per-type registry checks")
        assertTrue(view.any { it < 5 })
    }

    @Test fun `unknown class at the end preserves whole scan getter order and transient invalidation`() = withWorld {
        val first = add(CallbackBuilder(), enemy)
        add(CustomUnit(true, config), enemy)
        val last = add(CallbackBuilder(), enemy)
        val stock = stock()
        val reads = arrayListOf<String>()
        val original = config.configHash
        first.onType = { reads += "first"; config.configHash = original + 1 }
        last.onType = { reads += "last"; config.configHash = original }
        val fog = sight(projector(stock))
        assertEquals(listOf("first", "last"), reads, "The prescan may inspect only classes, never r/s")
        assertTrue(fog.all { it == 10.toByte() })
        assertEquals(original, config.configHash)
        assertNull(stock.keyFor(config), "The intervening native unit must observe and permanently revoke the mutation")
        assertFailsWith<IllegalStateException> { stock.assertValid() }
    }

    @Test fun `world flush callbacks run before the full scope validation even with an all exact final world`() = withWorld {
        add(BuilderUnit(true))
        val stock = stock()
        val projector = projector(stock)
        val world = BaseUnit.getGlobalUnitList()
        assertTrue(BaseUnit.authoritativeWorldHasNoPendingOperations())
        var calls = 0
        world.b(object {
            override fun equals(other: Any?): Boolean { calls++; config.configHash++; return false }
            override fun hashCode(): Int = 0
        })
        repeat(2) {
            assertFalse(BaseUnit.authoritativeWorldHasNoPendingOperations())
            assertEquals(0, calls, "The prerequisite must not flush or inspect pending values")
            assertEquals(1, world.pendingOperations.size)
        }
        assertFailsWith<IllegalStateException> { projector.snapshot() }
        assertEquals(1, calls)
        assertTrue(BaseUnit.authoritativeWorldHasNoPendingOperations())
        config.configHash--
        assertFailsWith<IllegalStateException> { stock.assertValid() }
        assertNull(stock.keyFor(UnitTypeEnum.builder))
    }

    @Test fun `empty pending prerequisite leaves iterators valid while original empty flush still invalidates them`() = withWorld {
        val unit = add(BuilderUnit(true))
        val world = BaseUnit.getGlobalUnitList()
        val beforeProbe = world.iterator()
        repeat(2) { assertTrue(BaseUnit.authoritativeWorldHasNoPendingOperations()) }
        assertSame(unit, beforeProbe.next())
        val beforeFlush = world.iterator()
        BaseUnit.getGlobalUnitList()
        assertFailsWith<java.util.ConcurrentModificationException> { beforeFlush.next() }
    }

    @Test fun `pending prerequisite rejects missing or malformed queue without replacing ordinary errors`() = withWorld {
        add(BuilderUnit(true))
        val world = BaseUnit.getGlobalUnitList()
        val pending = world.pendingOperations
        try {
            world.pendingOperations = null
            assertFalse(BaseUnit.authoritativeWorldHasNoPendingOperations())
            assertFailsWith<NullPointerException> { BaseUnit.getGlobalUnitList() }
            world.pendingOperations = pending
            pending.size = -1
            assertFalse(BaseUnit.authoritativeWorldHasNoPendingOperations())
        } finally {
            pending.size = 0
            world.pendingOperations = pending
        }
        assertTrue(BaseUnit.authoritativeWorldHasNoPendingOperations())
    }

    @Test fun `status callback cannot alter an uninstantiated config after ready validation and before pure sight`() = withWorld {
        add(BuilderUnit(true))
        val stock = stock()
        val projector = EngineViewProjector(engine, own, frozenStockCatalog = stock,
            statusProvider = { config.configHash++; MatchStatus(MatchPhase.RUNNING) })
        assertFailsWith<IllegalStateException> { projector.snapshot() }
        config.configHash--
        assertFailsWith<IllegalStateException> { stock.assertValid() }
    }

    @Test fun `scope closes after native malformed state throws and same tick sight remains fresh`() = withWorld {
        val scout = add(CustomUnit(true, config))
        val stock = stock()
        val projector = projector(stock)
        val oldStats = scout.y
        scout.y = null
        assertFailsWith<NullPointerException> { sight(projector) }
        scout.y = oldStats
        assertNotNull(stock.openNativeSightLookupOrNull()).close()
        val first = sight(projector)
        scout.posX = 610f; scout.posY = 410f
        scout.y = UnitStats(false).apply { fogOfWarSightRange = 5; isVisibleToEnemies = true }
        val second = sight(projector)
        assertEquals(0, engine.currentTick)
        assertTrue(first[0] < 5)
        assertEquals(5.toByte(), second[0])
        assertTrue(first[20 * 64 + 30] >= 5)
        assertTrue(second[20 * 64 + 30] < 5)
        assertTrue(first[0] < 5, "A later refog cannot mutate a previously returned copy")
    }

    @Test fun `all three exact native classes match fallback full fog and real v7 bytes over changing observations`() = withWorld {
        val scout = add(CustomUnit(true, config))
        val target = add(CustomUnit(true, config), enemy, 30f, 10f)
        add(CommandCenter(true), own, 1210f, 910f)
        add(BuilderUnit(true), own, 1110f, 910f)
        val sentinel = CallbackBuilder().apply { team = enemy; isDead = true }
        val stock = stock()
        val closure = StockActionClosure.capture(engine, stock)
        val publicMap = EnginePublicMapSnapshot.capture(map)
        fun stream() = EngineTimelineTestStream(EngineTimelineProjector(engine, own, publicMap, stock, closure,
            { MatchStatus(MatchPhase.RUNNING) }), engineTimelineTestContext(engine, publicMap))
        val optimized = stream()
        val fallback = stream()
        val optimizedView = projector(stock)
        val fallbackView = projector(stock)
        val oracle = ReplayFogState().apply { configure(64, 48, false); ensureTeam(0) }
        repeat(4) { round ->
            engine.currentTick = round * 6
            scout.posX = 10f + round * 200f; scout.posY = 10f + round * 100f
            target.posX = scout.posX + 20f; target.posY = scout.posY
            scout.currentHealth = 100f - round
            scout.y = UnitStats(false).apply { fogOfWarSightRange = 2 + round; isVisibleToEnemies = true }
            scout.buildProgress = if (round == 2) .5f else 1f
            config.fogOfWarSightRangeWhileNotBuilt = if (round == 2) 1 else -1
            oracle.beginFrame(true)
            oracle.reveal(0, scout.posX / 20f, scout.posY / 20f,
                if (round == 2) 1 else scout.y.fogOfWarSightRange)
            oracle.reveal(0, 1210f / 20f, 910f / 20f, 20)
            oracle.reveal(0, 1110f / 20f, 910f / 20f, 15)
            val expected = ByteArray(64 * 48) { oracle.get(0)[it % 64][it / 64] }
            assertContentEquals(expected, sight(optimizedView))
            val actual = optimized.capture(round * 100_000_000L)
            BaseUnit.getGlobalUnitList().add(sentinel)
            val old = try {
                assertContentEquals(expected, sight(fallbackView))
                fallback.capture(round * 100_000_000L)
            } finally { BaseUnit.getGlobalUnitList().remove(sentinel) }
            assertContentEquals(old.wire, actual.wire, "Round $round must preserve actual v7 transaction bytes")
            assertContentEquals(old.canonical, actual.canonical)
            assertEquals(TimelineCanonical.digest(old.state), TimelineCanonical.digest(actual.state))
        }
    }

    @Test fun `foreign null configuration keeps type first failure and earlier private fog prefix in both paths`() = withWorld {
        add(CustomUnit(true, config), own, 10f, 10f)
        val malformed = add(CustomUnit(true, config), enemy, 810f, 610f)
        add(CustomUnit(true, config), own, 610f, 410f)
        val sentinel = CallbackBuilder().apply { team = enemy; isDead = true }
        val stock = stock()
        val oracle = ReplayFogState().apply { configure(64, 48, false); ensureTeam(0); beginFrame(true); reveal(0, .5f, .5f, 2) }
        val prefix = ByteArray(64 * 48) { index -> oracle.get(0)[index % 64][index / 64] }
        val field = EngineViewProjector::class.java.getDeclaredField("currentVision").apply { isAccessible = true }
        malformed.unitConfig = null
        try {
            for (fallback in listOf(false, true)) {
                val projector = projector(stock)
                if (fallback) BaseUnit.getGlobalUnitList().add(sentinel)
                try {
                    assertFailsWith<NullPointerException> { sight(projector) }
                    val actual = (field.get(projector) as OwnedSeatVision).grid().copyBytes()
                    assertContentEquals(prefix, actual, "A foreign filter may not erase the old exception or advance past it")
                    assertNotNull(stock.openNativeSightLookupOrNull()).close()
                } finally { if (fallback) BaseUnit.getGlobalUnitList().remove(sentinel) }
            }
        } finally { malformed.unitConfig = config }
    }
}
