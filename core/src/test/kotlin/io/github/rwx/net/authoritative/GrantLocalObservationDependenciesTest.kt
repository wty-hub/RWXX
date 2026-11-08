package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.timeline.EngineTimelineProjector
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Eligibility alone does not authorize a world capture or certify any native callback as pure. */
class GrantLocalObservationDependenciesTest {
    private class CallbackBuilder : BuilderUnit(true) {
        var onRead: ((String) -> Unit)? = null
        private val actions = arrayListOf<AbstractUnitAction>()
        override fun r(): UnitTypeEnum { onRead?.invoke("type"); return UnitTypeEnum.builder }
        override fun getUpgradeLevel(): Int { onRead?.invoke("upgrade"); return 1 }
        override fun canNotBeGivenOrdersByPlayer(): Boolean { onRead?.invoke("control"); return false }
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> {
            onRead?.invoke("actions"); return actions
        }
    }

    private class World(val engine: GameLogic, val own: GameTeam, val stock: StockUnitCatalog,
                        val closure: StockActionClosure, val map: EnginePublicMapSnapshot) {
        fun actor() = CallbackBuilder().apply {
            team = own; buildProgress = 1f; currentHealth = 100f; maxHealth = 100f
        }
        fun timeline(status: () -> MatchStatus = { error("Eligibility evaluated match status") }) =
            EngineTimelineProjector(engine, own, map, stock, closure, status)
    }

    private fun withWorld(block: World.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null); GameEngine.isNonAndroidVersion = true
        // Color initialization of GameTeam and logic builtins requires a complete network owner.
        val engine = GameLogic().apply { networkEngine = NetworkEngine() }
        val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val previousTeams = registry.get(null) as Array<*>
        val previousConfigs = CustomUnitConfig.activeConfigs
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        val world = BaseUnit.getGlobalUnitList()
        val previousWorld = world.toList()
        world.clear()
        registry.set(null, arrayOfNulls<PlayerTeam>(previousTeams.size))
        CustomUnitConfig.activeConfigs = arrayListOf()
        CustomUnitConfig.unitTypeOverrides.clear()
        try {
            engine.commandController = CommandController()
            engine.replayEngine = ReplayEngine()
            engine.networkEngine.roomSettings.fogMode = 2
            engine.tileMap = PublicMapFixture.flat(16, 16).apply {
                fogEnabled = true; fogPeriodicMaintenanceEnabled = true
            }
            val own = GameTeam(0).apply {
                teamColorId = 0; credits = 1000.0
                fogOfWarData = Array(16) { ByteArray(16) { 10 } }
            }
            engine.playerTeam = own
            val stock = StockUnitCatalog.capture(engine)
            World(engine, own, stock, StockActionClosure.capture(engine, stock),
                EnginePublicMapSnapshot.capture(engine.tileMap)).block()
        } finally {
            world.clear(); previousWorld.forEach { world.add(it) }
            CustomUnitConfig.activeConfigs = previousConfigs
            CustomUnitConfig.unitTypeOverrides.clear(); CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            registry.set(null, previousTeams)
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun projection(timeline: EngineTimelineProjector): EngineViewProjector =
        field(timeline, "projection").get(timeline) as EngineViewProjector
    private fun catalog(projector: EngineViewProjector): EngineActionCatalog =
        field(projector, "actionCatalog").get(projector) as EngineActionCatalog

    @Test fun `selected owner capabilities and receipt owners each independently block without native reads`() = withWorld {
        val catalog = EngineActionCatalog(engine, own, stock, closure)
        assertTrue(catalog.hasNoRetainedActionDependencies())
        val actor = actor()
        val world = BaseUnit.getGlobalUnitList()
        world.add(actor)
        val panel = catalog.panel(ViewActionPanelQuery(1, 1, 100), actor)
        assertTrue(panel.actions.isNotEmpty(), "Use real synthetic capabilities and their real receipt")
        assertTrue(world.remove(actor))
        actor.onRead = { error("Eligibility invoked a removed actor: $it") }
        val selectedField = field(catalog, "selected")
        val selected = assertNotNull(selectedField.get(catalog))
        @Suppress("UNCHECKED_CAST")
        val capabilities = field(catalog, "capabilities").get(catalog) as MutableMap<Long, Any>
        @Suppress("UNCHECKED_CAST")
        val receipts = field(catalog, "receipts").get(catalog) as ArrayDeque<Any>
        val firstCapability = capabilities.values.first()
        val firstReceipt = receipts.first()
        repeat(3) { assertFalse(catalog.hasNoRetainedActionDependencies()) }
        assertSame<Any>(selected, selectedField.get(catalog))
        assertSame<Any>(firstCapability, capabilities.values.first())
        assertSame<Any>(firstReceipt, receipts.first())
        selectedField.set(catalog, null)
        assertFalse(catalog.hasNoRetainedActionDependencies(), "Cleared selection does not clear a retained capability")
        capabilities.clear()
        assertFalse(catalog.hasNoRetainedActionDependencies(), "A receipt still retains its old Owner and actor")
        receipts.clear()
        assertTrue(catalog.hasNoRetainedActionDependencies())
        selectedField.set(catalog, selected)
        assertFalse(catalog.hasNoRetainedActionDependencies(), "An Owner blocks even without actions or receipts")
        catalog.clear()
        assertTrue(catalog.hasNoRetainedActionDependencies())
    }

    @Test fun `one ineligible grant does not alter another and empty selection is insufficient`() = withWorld {
        var statusReads = 0
        val first = timeline { statusReads++; error("Eligibility called status") }
        val second = timeline { statusReads++; error("Eligibility called status") }
        assertTrue(first.hasEmptyLocalObservationDependencies())
        assertTrue(second.hasEmptyLocalObservationDependencies())
        field(first, "selection").set(first, listOf(100L))
        assertFalse(first.hasEmptyLocalObservationDependencies())
        assertTrue(second.hasEmptyLocalObservationDependencies())
        field(first, "selection").set(first, emptyList<Long>())
        val firstCatalog = catalog(projection(first))
        val actor = actor()
        firstCatalog.panel(ViewActionPanelQuery(1, 1, 100), actor)
        actor.onRead = { error("Eligibility inspected a previous owner: $it") }
        assertFalse(first.hasEmptyLocalObservationDependencies())
        assertTrue(second.hasEmptyLocalObservationDependencies())
        // Even an unusable world is not consulted by this purely local prerequisite.
        own.fogOfWarData = null
        engine.tileMap = null
        assertFalse(first.hasEmptyLocalObservationDependencies())
        assertTrue(second.hasEmptyLocalObservationDependencies())
        firstCatalog.clear()
        assertTrue(first.hasEmptyLocalObservationDependencies())
        assertEquals(0, statusReads)
    }

    @Test fun `inserting the query leaves panel callback trace sequence handles and receipts unchanged`() = withWorld {
        val catalog = EngineActionCatalog(engine, own, stock, closure)
        val actor = actor()
        val trace = arrayListOf<String>()
        actor.onRead = { trace += it }
        catalog.panel(ViewActionPanelQuery(1, 1, 100), actor)
        trace.clear()
        val before = catalog.panel(ViewActionPanelQuery(2, 1, 100), actor)
        val expectedTrace = trace.toList()
        assertTrue(expectedTrace.isNotEmpty())
        trace.clear()
        repeat(5) { assertFalse(catalog.hasNoRetainedActionDependencies()) }
        assertTrue(trace.isEmpty())
        val after = catalog.panel(ViewActionPanelQuery(3, 1, 100), actor)
        assertEquals(expectedTrace, trace)
        assertEquals(before.panelSequence + 1, after.panelSequence)
        assertEquals(before.actions, after.actions)
        @Suppress("UNCHECKED_CAST")
        val receipts = field(catalog, "receipts").get(catalog) as ArrayDeque<Any>
        assertEquals(3, receipts.size, "Queries must neither append receipts nor revoke existing ones")
    }

    @Test fun `delegates preserve owner and engine guards without touching callbacks`() = withWorld {
        val timeline = timeline()
        val view = projection(timeline)
        val failure = AtomicReference<Throwable?>()
        thread { failure.set(runCatching { timeline.hasEmptyLocalObservationDependencies() }.exceptionOrNull()) }.join()
        assertIs<IllegalStateException>(failure.get())
        thread { failure.set(runCatching { view.hasNoRetainedActionDependencies() }.exceptionOrNull()) }.join()
        assertIs<IllegalStateException>(failure.get())
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        instance.set(null, null)
        try { assertFailsWith<IllegalStateException> { timeline.hasEmptyLocalObservationDependencies() } }
        finally { instance.set(null, engine) }
        assertTrue(timeline.hasEmptyLocalObservationDependencies())
        // The optional action catalog being absent also means no retained action dependency.
        assertTrue(EngineViewProjector(engine, own).hasNoRetainedActionDependencies())
    }
}
