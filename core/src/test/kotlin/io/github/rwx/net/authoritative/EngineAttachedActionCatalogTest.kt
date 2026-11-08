package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.actions.ActionId
import com.corrodinggames.rts.game.units.actions.PlaceBuildingAction
import com.corrodinggames.rts.game.units.buildings.Projectile
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.UnitTypeReference
import com.corrodinggames.rts.game.units.custom.hooks.AttachmentSlotDefinition
import com.corrodinggames.rts.game.units.custom.logic.CustomAction
import com.corrodinggames.rts.game.units.custom.logic.CustomActionDef
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBooleanGameFunctions.HasFlagBoolean
import com.corrodinggames.rts.game.units.custom.price.UnitPrice
import com.corrodinggames.rts.gameFramework.Command
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import java.util.IdentityHashMap
import kotlin.test.*

class EngineAttachedActionCatalogTest {
    private class Actor(config: CustomUnitConfig) : CustomUnit(true, config) {
        val actions = arrayListOf<AbstractUnitAction>()
        val children = IdentityHashMap<AttachmentSlotDefinition, OrderableUnit>()
        override fun a(config: CustomUnitConfig, initial: Boolean, convert: Boolean) { unitConfig = config; y = config.unitStats }
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> = actions
        override fun validateActionId(id: ActionId?): AbstractUnitAction? = actions.firstOrNull { it.actionId === id }
        override fun a(slot: AttachmentSlotDefinition): OrderableUnit? = children[slot]
    }
    private class Poison : LogicBoolean() {
        var reads = 0
        override fun read(unit: OrderableUnit): Boolean { reads++; error("Proxy evaluated hidden world condition") }
        override fun getMatchFailReasonForPlayer(unit: OrderableUnit): String = error("Proxy formatted dynamic reason")
    }
    private class World(val engine: GameLogic, val own: GameTeam, val configs: Map<String, CustomUnitConfig>, stock: StockUnitCatalog) {
        val catalog = EngineActionCatalog(engine, own, stock)
        val parent = actor("modularSpider").apply { objectId = 777; unitFlags = 2 }
        val child = actor("modularSpider_emptySlot").apply { objectId = 999 }
        val slot = AttachmentSlotDefinition().apply {
            javaClass.getDeclaredField("a").apply { isAccessible = true }.setShort(this, 0)
            javaClass.getDeclaredField("b").apply { isAccessible = true }.set(this, "unitSlot1")
            N = HasFlagBoolean().apply { id(1) }; o = true
        }
        val build = PlaceBuildingAction(UnitTypeEnum.landFactory)
        val upgrade = CustomAction(CustomActionDef().apply {
            name = "cupgrade"; price = UnitPrice.a(100)
            convertTo = reference(configs.getValue("modularSpider_smallgunturret"))
        }, null)
        // Parser assigns displayName from [action_cancel]; name remains null without an explicit id.
        val cancelConstruction = CustomAction(CustomActionDef().apply {
            name = null; displayName = "cancel"; id = 2; price = UnitPrice.a(0)
        }, null)
        private var query = 0L
        init {
            parent.unitConfig.attachmentSlotDefinitions.add(slot)
            bind(child)
            child.actions.addAll(listOf(build, upgrade, cancelConstruction))
        }
        fun actor(key: String) = Actor(configs.getValue(key)).apply {
            team = own; buildProgress = 1f; currentHealth = 100f; maxHealth = 100f; posX = 10f; posY = 10f
        }
        fun bind(actor: Actor) { parent.children[slot] = actor; actor.parentEntity = parent; actor.attachmentData = slot }
        fun panel() = catalog.panel(ViewActionPanelQuery(++query, 1, PARENT_HANDLE), parent)
        fun module(panel: PlayerActionPanel, kind: ViewActionKind) = panel.actions.single {
            it.kind == kind && it.presentationKey.startsWith("module.slot1.")
        }
        fun intent(panel: PlayerActionPanel, action: ViewAction, cancel: Boolean = false) = ViewActionCommand(
            1, panel.viewSequence, panel.panelSequence, PARENT_HANDLE, action.handle,
            target = if (action.kind == ViewActionKind.BUILD && !cancel) CommandTarget.Point(100f, 100f)
                else CommandTarget.None, cancel = cancel)
        fun selected(command: Command): List<OrderableUnit> {
            @Suppress("UNCHECKED_CAST")
            return (Command::class.java.getDeclaredField("selectedUnits").apply { isAccessible = true }.get(command)
                as List<OrderableUnit>).toList()
        }
    }

    private fun withWorld(block: World.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val oldEngine = instance.get(null)
        val oldPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null); GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        val oldRegistry = CustomUnitConfig.activeConfigs
        val oldOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        val teams = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val oldTeams = teams.get(null) as Array<*>
        CustomUnitConfig.activeConfigs = arrayListOf(); CustomUnitConfig.unitTypeOverrides.clear()
        teams.set(null, arrayOfNulls<PlayerTeam>(oldTeams.size))
        try {
            engine.networkEngine = NetworkEngine(); engine.commandController = CommandController(); engine.replayEngine = ReplayEngine()
            engine.tileMap = TileMap().apply { tileCountX = 100; tileCountY = 100; tileWorldSizeX = 20; tileWorldSizeY = 20 }
            val names = setOf("modularSpider", "modularSpider_emptySlot", "modularSpider_smallgunturret")
            val configs = StockUnitManifest.bundled.filter { it.name in names }.associate { spec -> spec.name to
                CustomUnitConfig().apply {
                    name = spec.name; configPath = "assets/${spec.path}"; sourceFilePath = configPath
                    resourceLoadPath = configPath; configHash = spec.name.hashCode()
                }.also { CustomUnitConfig.activeConfigs.add(it) }
            }
            assertEquals(names, configs.keys)
            val team = GameTeam(0).apply { teamColorId = 0; credits = 100_000.0 }
            engine.playerTeam = team
            World(engine, team, configs, StockUnitCatalog.capture(engine)).block()
        } finally {
            teams.set(null, oldTeams); CustomUnitConfig.activeConfigs = oldRegistry
            CustomUnitConfig.unitTypeOverrides.clear(); CustomUnitConfig.unitTypeOverrides.putAll(oldOverrides)
            instance.set(null, oldEngine); GameEngine.isNonAndroidVersion = oldPlatform
        }
    }

    @Test fun `parent receipt exposes private child actions and native commands target only that child`() = withWorld {
        @Suppress("UNCHECKED_CAST")
        (child.dx() as MutableList<Projectile>).add(Projectile().apply { j = upgrade.actionId; launchDelay = 1 })
        val panel = panel()
        assertEquals(PARENT_HANDLE, panel.unitHandle)
        for ((kind, cancel) in listOf(ViewActionKind.BUILD to false, ViewActionKind.UPGRADE to false, ViewActionKind.UPGRADE to true)) {
            val action = module(panel, kind)
            assertTrue(catalog.execute(parent, intent(panel, action, cancel), null))
            val command = engine.commandController.pendingCommands.last()
            assertEquals(listOf(child), selected(command))
            assertSame(own, command.team); assertSame(own, command.sourceTeam)
            assertEquals(cancel, command.stopCurrentAction)
        }
        val buildAction = module(panel, ViewActionKind.BUILD)
        assertFalse(catalog.execute(parent, intent(panel, buildAction).copy(unitHandle = child.objectId), null))
        assertEquals(3, engine.commandController.pendingCommands.size)
    }

    @Test fun `changing selection or any attachment identity permanently revokes old handles even after restoration`() {
        for (change in listOf("selection", "instance", "conversion", "factory", "upgrade", "cargo", "death")) withWorld {
            val first = panel()
            val previous = module(first, ViewActionKind.BUILD)
            val restore: () -> Unit = when (change) {
                "selection" -> { parent.unitFlags = 1 shl 2; { parent.unitFlags = 2 } }
                "instance" -> {
                    val replacement = actor("modularSpider_emptySlot").apply { actions.addAll(child.actions) }
                    bind(replacement); { bind(child) }
                }
                "conversion" -> {
                    child.unitConfig = configs.getValue("modularSpider_smallgunturret");
                    { child.unitConfig = configs.getValue("modularSpider_emptySlot") }
                }
                "factory" -> { child.factoryUnitConfig = configs.getValue("modularSpider_smallgunturret"); { child.factoryUnitConfig = null } }
                "upgrade" -> { child.unitConfig.techLevel++; { child.unitConfig.techLevel-- } }
                "cargo" -> { child.transportContainer = parent; { child.transportContainer = null } }
                else -> { child.isDead = true; { child.isDead = false } }
            }
            catalog.revokeUnless { it === parent }
            restore()
            val refreshed = panel()
            val replacement = module(refreshed, ViewActionKind.BUILD)
            assertNotEquals(previous.handle, replacement.handle, change)
            assertFalse(catalog.execute(parent, intent(first, previous), null), change)
            assertTrue(catalog.execute(parent, intent(refreshed, replacement), null), change)
            assertEquals(listOf(child), selected(engine.commandController.pendingCommands.single()), change)
        }
    }

    @Test fun `incomplete module exposes only its reviewed construction cancellation through the parent`() = withWorld {
        assertNull(cancelConstruction.actionDef.name)
        assertEquals("cancel", cancelConstruction.actionDef.displayName)
        child.buildProgress = 0.4f
        child.unitConfig.isUnselectable = true
        val panel = panel()
        val module = panel.actions.filter { it.presentationKey.startsWith("module.slot1.") }
        assertEquals(1, module.size)
        assertEquals(ViewActionKind.TOGGLE, module.single().kind)
        assertTrue(module.single().presentationKey.endsWith(".cancelConstruction"))
        assertTrue(catalog.execute(parent, intent(panel, module.single()), null))
        val command = engine.commandController.pendingCommands.single()
        assertSame(cancelConstruction.actionId, command.actionId)
        assertEquals(listOf(child), selected(command))
    }

    @Test fun `an explicit cancel action id cannot authorize an unrelated unfinished module action`() = withWorld {
        child.buildProgress = 0.4f
        val first = panel()
        val previous = module(first, ViewActionKind.TOGGLE)
        child.actions.remove(cancelConstruction)
        child.actions += CustomAction(CustomActionDef().apply {
            name = "ccancel"; displayName = "unreviewedSection"; price = UnitPrice.a(0)
        }, null)
        assertTrue(panel().actions.none { it.presentationKey.startsWith("module.") })
        assertFalse(catalog.execute(parent, intent(first, previous), null))
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `no selection and malicious slot predicates produce no module actions and never evaluate world logic`() = withWorld {
        val first = panel()
        val previous = module(first, ViewActionKind.BUILD)
        parent.unitFlags = 0
        assertTrue(panel().actions.none { it.presentationKey.startsWith("module.") })
        parent.unitFlags = 2
        val poison = Poison()
        slot.N = poison
        assertTrue(panel().actions.none { it.presentationKey.startsWith("module.") })
        assertFalse(catalog.execute(parent, intent(first, previous), null))
        assertEquals(0, poison.reads)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    private companion object {
        const val PARENT_HANDLE = 42L
        fun reference(type: UnitType) = UnitTypeReference().apply {
            UnitTypeReference::class.java.getDeclaredField("unitType").apply { isAccessible = true }.set(this, type)
        }
    }
}
