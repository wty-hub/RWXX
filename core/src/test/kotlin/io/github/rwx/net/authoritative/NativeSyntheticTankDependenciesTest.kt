package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.UnitContainer
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.actions.PlaceBuildingAction
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.hooks.AttachmentSlotDefinition
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBooleanGameFunctions.HasFlagBoolean
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class NativeSyntheticTankDependenciesTest {
    private class CallbackTank(config: CustomUnitConfig) : CustomUnit(true, config) {
        var onRead: ((String) -> Unit)? = null
        // BaseUnit calls r() before CustomUnit assigns unitConfig; retain Java's nullable constructor result.
        override fun r(): CustomUnitConfig? { onRead?.invoke("type"); return unitConfig }
        override fun getUpgradeLevel(): Int { onRead?.invoke("tech"); return unitConfig.techLevel }
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> {
            onRead?.invoke("actions")
            @Suppress("UNCHECKED_CAST")
            return super.getAvailableActions() as ArrayList<AbstractUnitAction>
        }
    }

    private class PoisonActions : ArrayList<AbstractUnitAction>() {
        var reads = 0
        override val size: Int get() { reads++; error("unknown size") }
        override fun isEmpty(): Boolean { reads++; error("unknown isEmpty") }
        override fun get(index: Int): AbstractUnitAction { reads++; error("unknown get") }
        override fun iterator(): MutableIterator<AbstractUnitAction> { reads++; error("unknown iterator") }
    }

    private class World(val engine: GameLogic, val own: GameTeam, val other: GameTeam,
                        val config: CustomUnitConfig, val containers: Array<UnitContainer>, val stock: StockUnitCatalog) {
        fun actor() = CustomUnit(true, config).apply { team = own; buildProgress = 1f }
        fun catalog() = EngineActionCatalog(engine, own, stock)
        fun panel(catalog: EngineActionCatalog, actor: CustomUnit, query: Long = 1) =
            catalog.panel(ViewActionPanelQuery(query, 1, 100), actor)
    }

    private fun withWorld(block: World.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null); GameEngine.isNonAndroidVersion = true
        val engine = GameLogic().apply { networkEngine = NetworkEngine() }
        val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val previousTeams = registry.get(null) as Array<*>
        val previousConfigs = CustomUnitConfig.activeConfigs
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        val world = BaseUnit.getGlobalUnitList()
        val previousWorld = world.toList()
        world.clear()
        registry.set(null, arrayOfNulls<PlayerTeam>(previousTeams.size))
        CustomUnitConfig.activeConfigs = arrayListOf(); CustomUnitConfig.unitTypeOverrides.clear()
        try {
            engine.commandController = CommandController(); engine.replayEngine = ReplayEngine()
            engine.tileMap = PublicMapFixture.flat(16, 16)
            val own = GameTeam(0).apply { teamColorId = 0; credits = 1000.0 }
            val other = GameTeam(1).apply { teamColorId = 1 }
            engine.playerTeam = own
            val spec = StockUnitManifest.bundled.single { it.replaces === UnitTypeEnum.tank }
            val config = CustomUnitConfig().apply {
                name = spec.name; configPath = "assets/${spec.path}"
                sourceFilePath = configPath; resourceLoadPath = configPath
                turrets = emptyArray(); frameWidth = 20; frameHeight = 20; techLevel = 1
                unitStats.maxHp = 100; unitStats.fogOfWarSightRange = 2; unitStats.isVisibleToEnemies = true
                fogOfWarSightRangeWhileNotBuilt = -1; canAttack = true
            }
            val containers = Array(3) { UnitContainer() }
            CustomUnitConfig::class.java.getDeclaredField("specialActionLists").apply { isAccessible = true }
                .set(config, containers)
            CustomUnitConfig.activeConfigs.add(config)
            CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = config
            World(engine, own, other, config, containers, StockUnitCatalog.capture(engine)).block()
        } finally {
            world.clear(); previousWorld.forEach { world.add(it) }
            CustomUnitConfig.activeConfigs = previousConfigs
            CustomUnitConfig.unitTypeOverrides.clear(); CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            registry.set(null, previousTeams)
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun capabilities(catalog: EngineActionCatalog) = field(catalog, "capabilities").get(catalog) as MutableMap<Long, Any>
    @Suppress("UNCHECKED_CAST")
    private fun receipts(catalog: EngineActionCatalog) = field(catalog, "receipts").get(catalog) as ArrayDeque<Any>

    @Test fun `exact native synthetic panel is described without altering its selection receipt or output`() = withWorld {
        val actor = actor()
        val catalog = catalog()
        val before = panel(catalog, actor)
        assertTrue(before.actions.isNotEmpty())
        assertTrue(before.actions.all { it.kind == ViewActionKind.LOAD_INTO || it.kind == ViewActionKind.GUARD ||
            it.kind == ViewActionKind.PATROL || it.kind == ViewActionKind.ATTACK_MODE })
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            val descriptor = assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            repeat(3) { assertTrue(descriptor.stillCurrent()) }
            assertEquals(1, receipts(catalog).size)
            val after = panel(catalog, actor, 2)
            assertEquals(before.actions, after.actions)
            assertEquals(before.panelSequence + 1, after.panelSequence)
            assertFalse(descriptor.stillCurrent(), "A new receipt invalidates an earlier root snapshot")
            assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope)).close()
        } finally { scope.close() }
    }

    @Test fun `only the active qualified scope of this catalog can bind the frozen tank identity`() = withWorld {
        val catalog = catalog(); panel(catalog, actor())
        val otherStock = StockUnitCatalog.capture(engine)
        val foreignScope = assertNotNull(otherStock.openNativeSightLookupOrNull())
        try { assertNull(catalog.nativeSyntheticTankDependenciesOrNull(foreignScope)) }
        finally { foreignScope.close() }
        val forgedInactiveScope = stock.NativeSightLookup()
        assertNull(catalog.nativeSyntheticTankDependenciesOrNull(forgedInactiveScope))
        forgedInactiveScope.close()
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        val descriptor = assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
        scope.close()
        assertFalse(descriptor.stillCurrent())
        assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
        val next = assertNotNull(stock.openNativeSightLookupOrNull())
        try { assertFalse(descriptor.stillCurrent()); assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(next)) }
        finally { next.close() }
    }

    @Test fun `unknown removed actors in selected receipt or capability roots never execute getters`() = withWorld {
        val old = CallbackTank(config).apply { team = own; buildProgress = 1f }
        val world = BaseUnit.getGlobalUnitList(); world.add(old)
        val stale = catalog(); panel(stale, old)
        val oldReceipt = receipts(stale).single()
        val oldCapability = capabilities(stale).values.first()
        assertTrue(world.remove(old))
        old.onRead = { error("Removed receiver was invoked: $it") }
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            assertNull(stale.nativeSyntheticTankDependenciesOrNull(scope))
            field(stale, "selected").set(stale, null)
            capabilities(stale).clear()
            assertNull(stale.nativeSyntheticTankDependenciesOrNull(scope), "Old receipt alone still blocks")
            val clean = catalog(); panel(clean, actor())
            receipts(clean).addLast(oldReceipt)
            assertNull(clean.nativeSyntheticTankDependenciesOrNull(scope), "Current clean Owner does not erase old receipt actors")
            receipts(clean).removeLast()
            capabilities(clean)[Long.MAX_VALUE] = oldCapability
            assertNull(clean.nativeSyntheticTankDependenciesOrNull(scope), "Every old capability actor is checked")
            capabilities(clean).remove(Long.MAX_VALUE)
            assertNotNull(clean.nativeSyntheticTankDependenciesOrNull(scope))
        } finally { scope.close() }
    }

    @Test fun `actual action backing shape is checked before any virtual list method`() = withWorld {
        val actor = actor(); val catalog = catalog(); panel(catalog, actor)
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            val descriptor = assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            val original = containers[0].a
            val poison = PoisonActions()
            containers[0].a = poison
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            assertFalse(descriptor.stillCurrent())
            assertEquals(0, poison.reads)
            containers[0].a = original
            assertFalse(descriptor.stillCurrent(), "Detected backing replacement permanently invalidates this descriptor")
            original.add(PlaceBuildingAction(UnitTypeEnum.landFactory))
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope), "Any actual native action stays on the old path")
            original.clear()
            val listsField = CustomUnitConfig::class.java.getDeclaredField("specialActionLists").apply { isAccessible = true }
            listsField.set(config, null)
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope), "Do not enter the native null-array error branch")
            listsField.set(config, containers)
            val originalContainer = containers[0]
            containers[0] = object : UnitContainer() { }
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            containers[0] = originalContainer
            assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
        } finally { scope.close() }
    }

    @Test fun `own team owner snapshot and native bindings all remain local fail closed conditions`() = withWorld {
        val actor = actor(); val catalog = catalog(); panel(catalog, actor)
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            val descriptor = assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            actor.team = other
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope)); assertFalse(descriptor.stillCurrent())
            actor.team = own
            assertFalse(descriptor.stillCurrent())
            config.techLevel = 2
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope), "Old Owner was captured at tech 1")
            config.techLevel = 1
            actor.factoryUnitConfig = config
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            actor.factoryUnitConfig = null
            actor.attachmentData = AttachmentSlotDefinition()
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            actor.attachmentData = null
            actor.unitConfig = CustomUnitConfig().apply { name = config.name; techLevel = 1 }
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope), "Matching names cannot substitute catalog identity")
            actor.unitConfig = config
            assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
        } finally { scope.close() }
    }

    @Test fun `native action references and retained attachment operators are rejected without following them`() = withWorld {
        val actor = actor(); val catalog = catalog(); panel(catalog, actor)
        val capability = capabilities(catalog).values.first()
        val entryField = field(capability, "entry")
        val entry = entryField.get(capability) as StockActionAdapter.Entry
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            entryField.set(capability, entry.copy(action = PlaceBuildingAction(UnitTypeEnum.landFactory)))
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            entryField.set(capability, entry.copy(closureToken = Any()))
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            entryField.set(capability, entry)
            val child = CallbackTank(config).apply { team = own; onRead = { error("Operator child getter: $it") } }
            val operator = StockAttachedActions.Operator(actor, child, 1, AttachmentSlotDefinition(),
                config, null, config, null, 1, 1, own, HasFlagBoolean())
            field(capability, "operator").set(capability, operator)
            assertNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            field(capability, "operator").set(capability, null)
            assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
        } finally { scope.close() }
    }

    @Test fun `callback boundary requires explicit disposal even when native fields return to original values`() = withWorld {
        val actor = actor(); val catalog = catalog(); panel(catalog, actor)
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            val descriptor = assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            config.ignoreMoveOrders = true; config.ignoreMoveOrders = false
            assertTrue(descriptor.stillCurrent(), "An unobserved A-B-A cannot be inferred from current fields")
            descriptor.close()
            assertFalse(descriptor.stillCurrent(), "An explicit callback boundary always revokes the old descriptor")
            val fresh = assertNotNull(catalog.nativeSyntheticTankDependenciesOrNull(scope))
            val failure = AtomicReference<Throwable?>()
            thread { failure.set(runCatching { fresh.stillCurrent() }.exceptionOrNull()) }.join()
            assertIs<IllegalStateException>(failure.get())
            assertTrue(fresh.stillCurrent())
        } finally { scope.close() }
    }
}
