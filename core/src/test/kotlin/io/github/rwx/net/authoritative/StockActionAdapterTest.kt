package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitCommandType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.actions.ActionId
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.PlaceBuildingAction
import com.corrodinggames.rts.game.units.actions.QueueUnitAction
import com.corrodinggames.rts.game.units.buildings.Projectile
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.TurretConfig
import com.corrodinggames.rts.game.units.custom.logic.ConfigurableCustomAction
import com.corrodinggames.rts.game.units.custom.logic.CustomAction
import com.corrodinggames.rts.game.units.custom.logic.CustomActionDef
import com.corrodinggames.rts.game.units.custom.logic.actions.SpawnUnitAction
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.game.units.custom.price.UnitPrice
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.timeline.*
import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong
import kotlin.test.*

class StockActionAdapterTest {
    private class Actor(config: CustomUnitConfig) : CustomUnit(true, config) {
        val actions = arrayListOf<AbstractUnitAction>()
        var loadTarget: BaseUnit? = null
        // Keep engine queue/command semantics, without loading textures or parsing INI files.
        override fun a(config: CustomUnitConfig, initial: Boolean, convert: Boolean) {
            unitConfig = config
            y = config.unitStats
        }
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> = actions
        override fun validateActionId(id: ActionId?): AbstractUnitAction? = actions.firstOrNull { it.actionId === id }
        override fun d(target: BaseUnit?, ignoreTeam: Boolean): Boolean = target != null && target === loadTarget
        override fun a(action: AbstractUnitAction, x: Float, y: Float): Boolean =
            error("The adapter invoked CustomUnit's UI-dependent target checker")
    }

    private class PoisonCondition : LogicBoolean() {
        var reads = 0
        var hiddenValue = false
        override fun read(unit: OrderableUnit): Boolean { reads++; return hiddenValue }
        override fun getMatchFailReasonForPlayer(unit: OrderableUnit): String = error("A dynamic explanation reached the view")
    }

    private class World(val engine: GameLogic, val own: GameTeam, val enemy: GameTeam,
        val config: CustomUnitConfig, val stock: StockUnitCatalog) {
        val adapter = StockActionAdapter(engine, stock)
        fun actor(team: PlayerTeam = own) = Actor(config).apply {
            this.team = team; posX = 10f; posY = 10f
            currentHealth = 50f; maxHealth = 100f; buildProgress = 1f
        }
        fun custom(name: String = "cfixture", condition: LogicBoolean? = null) = CustomAction(CustomActionDef().apply {
            this.name = name; price = UnitPrice.a(10); isActive = condition
        }, null)
        fun intent(entry: StockActionAdapter.Entry, target: Long = 0, queued: Boolean = false, cancel: Boolean = false) =
            ViewActionCommand(1, 1, 1, 100, 101, target = if (target == 0L) CommandTarget.None else CommandTarget.Unit(target), queued = queued, cancel = cancel)
    }

    private fun withWorld(ownerName: String = "mechFactory", block: World.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null); GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        // CustomUnitConfig initializes logic builtins that construct dummy units and need an engine.
        val previousRegistry = CustomUnitConfig.activeConfigs
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        CustomUnitConfig.activeConfigs = arrayListOf(); CustomUnitConfig.unitTypeOverrides.clear()
        val teams = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val previousTeams = teams.get(null) as Array<*>
        teams.set(null, arrayOfNulls<PlayerTeam>(previousTeams.size))
        try {
            engine.networkEngine = NetworkEngine()
            engine.commandController = CommandController()
            engine.replayEngine = ReplayEngine()
            engine.tileMap = TileMap().apply {
                tileCountX = 100; tileCountY = 100; tileWorldSizeX = 20; tileWorldSizeY = 20
            }
            val spec = StockUnitManifest.bundled.first { it.name == ownerName }
            val config = CustomUnitConfig().apply {
                name = spec.name; configPath = "assets/${spec.path}"; sourceFilePath = configPath
                resourceLoadPath = configPath; configHash = 123
                canRepairUnits = true; canRepairBuildings = true; canReclaimResources = true
                maxTransportingUnits = 5; canAttack = true
            }
            CustomUnitConfig.activeConfigs.add(config)
            spec.replaces?.let { CustomUnitConfig.unitTypeOverrides[it] = config }
            val own = GameTeam(0).apply { teamColorId = 0; credits = 100_000.0 }
            val enemy = GameTeam(1).apply { teamColorId = 1; credits = 100_000.0 }
            engine.playerTeam = own
            World(engine, own, enemy, config, StockUnitCatalog.capture(engine)).block()
        } finally {
            teams.set(null, previousTeams)
            CustomUnitConfig.activeConfigs = previousRegistry
            CustomUnitConfig.unitTypeOverrides.clear(); CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    /** Use the production ability mapping and a complete, profile-validated v7 ACTION state. */
    private fun actionTimelineBytes(views: List<ViewAction>): ByteArray {
        val context = TimelineTestFixture.context()
        val publisher = TimelinePublisher(context)
        val unit = TimelineObservedUnit(100, TimelineValue.Vec3(256, 512, 0), 0, 50L * 256,
            100L * 256, 65535, 0, "mechFactory", 1, true)
        val actions = views.map { action -> TimelineObservedAction(action.handle,
            EngineTimelineProjector.ability(action), when (action.readiness) {
                ActionReadiness.BLOCKED -> 0; ActionReadiness.READY -> 1; ActionReadiness.UNKNOWN -> 2
            }, action.queuedCount.toLong(), action.stockpileCount.toLong(),
            action.progress.takeIf { it >= 0f }?.let { (it.toDouble() * 65535).roundToLong() }, action.canCancel)
        }
        publisher.observe(TimelineObservation(0, 100L * 1024, TimelineTestFixture.running().value,
            TimelineBytes(ByteArray(4)), listOf(unit), selectedUnitKey = 100, actions = actions))
        val state = assertNotNull(publisher.publish(0))
        TimelineProfile.validate(state)
        assertEquals(views.size, state.objects.values.count { it.kind == TimelineObjectKind.ACTION })
        val ops = TimelineEncoder.resetOperations(state)
        val bytes = TimelineBinary.encodeOperations(ops)
        val header = state.header.copy(kind = TimelineTransactionKind.RESET, epoch = 1, revision = 1,
            baseRevision = 0, operationBytes = bytes.size.toLong(), operationSha256 = TimelineCanonical.sha256(bytes))
        val rebuilt = TimelineStore(context).apply(header, bytes)
        assertEquals(TimelineCanonical.digest(state), TimelineCanonical.digest(rebuilt))
        return ByteArrayOutputStream().also { TimelineCanonical.write(rebuilt, it) }.toByteArray()
    }

    @Test fun `same ActionId and structural equality never replace the original capability`() = withWorld {
        val actor = actor()
        val first = PlaceBuildingAction(UnitTypeEnum.landFactory)
        val replacement = PlaceBuildingAction(UnitTypeEnum.landFactory)
        assertEquals(first, replacement)
        actor.actions += first
        val entry = adapter.describe(actor).single { it.kind == ViewActionKind.BUILD }
        actor.actions[0] = replacement
        val newEntry = adapter.describe(actor).single { it.kind == ViewActionKind.BUILD }
        assertFalse(entry.sameCapability(newEntry))
        assertFalse(adapter.execute(actor, entry, intent(entry).copy(target = CommandTarget.Point(40f, 40f)), null))
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `hidden conditions never affect action wire bytes or execute during view construction`() = withWorld {
        val actor = actor()
        val hidden = PoisonCondition()
        val action = custom(condition = hidden)
        action.actionDef.isVisible = hidden
        action.actionDef.isLocked = hidden
        actor.actions += action
        val entry = adapter.describe(actor).first { it.action === action }
        fun bytes(): ByteArray {
            val view = adapter.view(actor, entry, 11)
            assertEquals(ActionReadiness.UNKNOWN, view.readiness)
            return actionTimelineBytes(listOf(view))
        }
        val first = bytes()
        hidden.hiddenValue = true; enemy.credits = 1.0
        assertContentEquals(first, bytes())
        assertEquals(0, hidden.reads)
        action.actionDef.isVisible = LogicBoolean.falseBoolean
        assertTrue(adapter.describe(actor).none { it.action === action })
        assertEquals(0, hidden.reads)
    }

    @Test fun `ordinary production wrappers also keep unreviewed dynamic readiness unknown`() = withWorld {
        val actor = actor()
        val hidden = PoisonCondition()
        val action = QueueUnitAction(UnitTypeEnum.tank).apply {
            unitAction = ConfigurableCustomAction().apply { enabledCondition = hidden; highlightCondition = hidden }
        }
        actor.actions += action
        val entry = adapter.describe(actor).first { it.action === action }
        assertEquals(ActionReadiness.UNKNOWN, adapter.view(actor, entry, 1).readiness)
        assertEquals(0, hidden.reads)
        (action.unitAction as ConfigurableCustomAction).enabledCondition = LogicBoolean.falseBoolean
        assertTrue(adapter.describe(actor).none { it.action === action })
    }

    @Test fun `queued actions and stockpiles are separate and cancel remains possible without funds`() = withWorld {
        val actor = actor()
        val production = QueueUnitAction(UnitTypeEnum.tank)
        actor.actions += production
        @Suppress("UNCHECKED_CAST")
        (actor.dx() as MutableList<Projectile>).add(Projectile().apply { j = production.actionId; launchDelay = 2 })
        own.credits = 0.0
        val entry = adapter.describe(actor).first { it.action === production }
        val state = adapter.view(actor, entry, 1)
        assertEquals(2, state.queuedCount)
        assertEquals(-1, state.stockpileCount)
        assertTrue(state.canCancel)
        assertEquals(ActionReadiness.BLOCKED, state.readiness)
        assertFalse(adapter.execute(actor, entry, intent(entry, cancel = true).copy(target = CommandTarget.Point(0f, 0f)), null))
        assertFalse(adapter.execute(actor, entry, intent(entry, cancel = true).copy(target = CommandTarget.Unit(10)), actor))
        assertTrue(adapter.execute(actor, entry, intent(entry, cancel = true), null))
        val command = engine.commandController.pendingCommands.single()
        assertTrue(command.stopCurrentAction)
        assertSame(production.queueId, command.actionId)

        val stockpile = custom("cstockpile", PoisonCondition()).apply {
            actionDef.price = UnitPrice().apply { f = 1 }; actionDef.displayRemainingStockpile = true
        }
        actor.actions += stockpile; actor.ammo = 7
        val stockpileEntry = adapter.describe(actor).first { it.action === stockpile }
        val stockpileView = adapter.view(actor, stockpileEntry, 2)
        assertEquals(0, stockpileView.queuedCount)
        assertEquals(7, stockpileView.stockpileCount)
        assertFalse(stockpileView.canCancel)
        assertFalse(adapter.execute(actor, stockpileEntry, intent(stockpileEntry, cancel = true), null))
    }

    @Test fun `repair reclaim guard and both transport directions reject unauthorized or meaningless targets`() = withWorld {
        val actor = actor()
        val friendly = actor()
        val hostile = actor(enemy)
        for (kind in listOf(ViewActionKind.REPAIR, ViewActionKind.RECLAIM, ViewActionKind.GUARD,
            ViewActionKind.LOAD_INTO, ViewActionKind.LOAD_UP)) {
            val entry = adapter.describe(actor).first { it.kind == kind }
            assertFalse(adapter.execute(actor, entry, intent(entry, target = 10), hostile))
            assertFalse(adapter.execute(actor, entry, intent(entry, target = 10), actor))
            assertFalse(adapter.execute(actor, entry, intent(entry, target = 10).copy(target = CommandTarget.Point(5f, 0f)), friendly))
            assertTrue(engine.commandController.pendingCommands.isEmpty())
        }
        val repair = adapter.describe(actor).first { it.kind == ViewActionKind.REPAIR }
        friendly.currentHealth = friendly.maxHealth
        assertFalse(adapter.execute(actor, repair, intent(repair, target = 10), friendly))
        friendly.currentHealth = 10f
        assertTrue(adapter.execute(actor, repair, intent(repair, target = 10), friendly))
        assertEquals(UnitCommandType.repair, engine.commandController.pendingCommands.last().unitCommand.commandType)
        engine.commandController.pendingCommands.clear()

        val into = adapter.describe(actor).first { it.kind == ViewActionKind.LOAD_INTO }
        val up = adapter.describe(actor).first { it.kind == ViewActionKind.LOAD_UP }
        actor.loadTarget = friendly
        assertFalse(adapter.execute(actor, into, intent(into, target = 10), friendly))
        assertTrue(adapter.execute(actor, up, intent(up, target = 10), friendly))
        assertEquals(UnitCommandType.loadUp, engine.commandController.pendingCommands.last().unitCommand.commandType)
        friendly.loadTarget = actor
        assertTrue(adapter.execute(actor, into, intent(into, target = 10), friendly))
        assertEquals(UnitCommandType.loadInto, engine.commandController.pendingCommands.last().unitCommand.commandType)
    }

    @Test fun `queued mode is limited to documented target orders and malformed inputs never enqueue`() = withWorld {
        val actor = actor()
        val attackMode = adapter.describe(actor).first { it.kind == ViewActionKind.ATTACK_MODE }
        assertFalse(adapter.execute(actor, attackMode, intent(attackMode).copy(sequence = 0), null))
        assertFalse(adapter.execute(actor, attackMode, intent(attackMode).copy(target = CommandTarget.Point(0f, 0f)), null))
        assertFalse(adapter.execute(actor, attackMode, intent(attackMode, queued = true), null))
        assertFalse(adapter.execute(actor, attackMode, intent(attackMode).copy(panelSequence = 0), null))
        assertTrue(adapter.execute(actor, attackMode, intent(attackMode), null))
        assertEquals(1, engine.commandController.pendingCommands.size)
        assertEquals(attackMode.attackMode, engine.commandController.pendingCommands.single().attackMode)
        engine.commandController.pendingCommands.clear()
        val patrol = adapter.describe(actor).first { it.kind == ViewActionKind.PATROL }
        assertFalse(adapter.execute(actor, patrol, intent(patrol), null), "A point action cannot omit its point")
        for (point in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 2000f)) {
            assertFalse(adapter.execute(actor, patrol, intent(patrol).copy(target = CommandTarget.Point(point, 10f)), null))
        }
        assertTrue(adapter.execute(actor, patrol, intent(patrol, queued = true).copy(target = CommandTarget.Point(1900f, 1900f)), null))
        assertTrue(engine.commandController.pendingCommands.single().isQueued)
        assertEquals(UnitCommandType.patrol, engine.commandController.pendingCommands.single().unitCommand.commandType)
    }

    @Test fun `unclosed spawn handlers are explicitly reported and panel capacity never silently truncates`() = withWorld {
        val actor = actor()
        val unclosed = custom().apply { actionDef.logicActions.add(SpawnUnitAction()) }
        actor.actions += unclosed
        assertTrue(adapter.describe(actor).none { it.action === unclosed })
        assertEquals("Unclosed production or triggered-action references", adapter.unsupportedActions().single().reason)
        actor.actions.clear()
        repeat(129) { actor.actions += custom("ccapacity$it") }
        assertFailsWith<IllegalStateException> { adapter.describe(actor) }
        assertTrue(adapter.unsupportedActions().any { "128" in it.reason })
    }

    @Test fun `ground orders use public range boundaries without invoking the UI checker or demanding vision`() = withWorld {
        config.turrets = arrayOf(TurretConfig().apply {
            effectiveRangeSquared = 10_000f; limitingMinRangeSquared = 400f
        })
        val actor = actor()
        val action = custom().apply {
            actionDef.queueType = ActionType.targetGround
            actionDef.fireTurretAtGroundIndex = 0
        }
        actor.actions += action
        val entry = adapter.describe(actor).first { it.action === action }
        assertEquals(ViewActionKind.TARGET_GROUND, entry.kind)
        for (x in listOf(29f, 111f)) {
            assertFalse(adapter.execute(actor, entry, intent(entry).copy(target = CommandTarget.Point(x, 10f)), null))
        }
        for (x in listOf(30f, 110f)) {
            assertTrue(adapter.execute(actor, entry, intent(entry).copy(target = CommandTarget.Point(x, 10f)), null))
        }
        assertEquals(2, engine.commandController.pendingCommands.size)
        assertTrue(engine.commandController.pendingCommands.all { it.actionId === action.actionId })
        actor.buildProgress = 0.5f
        assertFalse(adapter.execute(actor, entry, intent(entry).copy(target = CommandTarget.Point(30f, 10f)), null))
    }

    @Test fun `noncancellable custom toggles remain executable while buildings gain no travel actions`() = withWorld {
        val actor = actor()
        val toggle = custom().apply { actionDef.canPlayerCancel = false; actionDef.queueType = ActionType.none }
        actor.actions += toggle
        val entry = adapter.describe(actor).first { it.action === toggle }
        assertEquals(ViewActionKind.TOGGLE, entry.kind)
        assertTrue(adapter.execute(actor, entry, intent(entry), null))
        config.isBuildingUnit = true
        assertTrue(actor.canExecuteMovementCommands(), "CustomUnit's broad command flag alone also permits buildings")
        assertTrue(adapter.describe(actor).none { it.kind in setOf(ViewActionKind.PATROL, ViewActionKind.GUARD, ViewActionKind.LOAD_INTO) })
    }

    @Test fun `reviewed sections get closed semantic labels without exporting native action ids`() {
        data class Case(val owner: String, val section: String, val type: ActionType, val suffix: String,
            val ammoProduction: Boolean = false)
        val cases = (1..6).map { Case("modularSpider", "selectSlot$it", ActionType.none, "selectSlot$it") } + listOf(
            Case("modularSpider_nonEmpty", "selectSlotBuild", ActionType.none, "selectBuildSlot"),
            Case("modularSpider_emptySlot", "cancel", ActionType.popupQueue, "cancelConstruction"),
            Case("nukeLauncherC", "launchNuke", ActionType.targetGround, "launchNuke"),
            Case("nukeLauncherC", "buildNuke", ActionType.popupQueue, "buildNuke", ammoProduction = true),
            Case("antiNukeLauncherC", "buildAntiNuke", ActionType.popupQueue, "buildAntiNuke", ammoProduction = true),
            Case("modularSpider_blink", "blink", ActionType.targetGround, "blink"),
        )
        for (case in cases) withWorld(case.owner) {
            val actor = actor()
            val action = CustomAction(CustomActionDef().apply {
                name = "cPRIVATE_NATIVE_ID"; displayName = case.section; price = UnitPrice.a(0)
                queueType = case.type
                if (case.ammoProduction) addResources = UnitPrice().apply { f = 1 }
            }, null)
            actor.actions += action
            val entry = adapter.describe(actor).single { it.action === action }
            val view = adapter.view(actor, entry, 1)
            assertTrue(view.presentationKey.endsWith(".${case.suffix}"), case.toString())
            assertFalse("PRIVATE_NATIVE_ID" in view.presentationKey)
        }
    }

    @Test fun `unknown section text remains private and known section on wrong owner has no special meaning`() = withWorld {
        val actor = actor()
        val poison = PoisonCondition()
        actor.actions += CustomAction(CustomActionDef().apply {
            name = "cPRIVATE_NATIVE_ID"; displayName = "PRIVATE_SECTION_PATH_secret"
            price = UnitPrice.a(0); isActive = poison
        }, null)
        actor.actions += CustomAction(CustomActionDef().apply {
            name = null; id = 1; displayName = "selectSlot1"; price = UnitPrice.a(0)
        }, null)
        val views = adapter.describe(actor).filter { it.action != null }.mapIndexed { index, entry ->
            adapter.view(actor, entry, index + 1L)
        }
        assertEquals(listOf("authority.action.toggle.mechFactory.1", "authority.action.toggle.mechFactory.2"),
            views.map { it.presentationKey })
        val bytes = actionTimelineBytes(views)
        assertFalse("PRIVATE_" in bytes.toString(Charsets.ISO_8859_1))
        assertEquals(0, poison.reads)
    }

    @Test fun `modular parent forms cannot use child cancellation while incomplete`() {
        for (owner in listOf("modularSpider", "modularSpider_nonEmpty")) withWorld(owner) {
            val actor = actor().apply { buildProgress = 0.5f }
            actor.actions += CustomAction(CustomActionDef().apply {
                name = null; displayName = "cancel"; price = UnitPrice.a(0)
            }, null)
            assertTrue(adapter.describe(actor).isEmpty(), owner)
            assertTrue(engine.commandController.pendingCommands.isEmpty())
        }
    }
}
