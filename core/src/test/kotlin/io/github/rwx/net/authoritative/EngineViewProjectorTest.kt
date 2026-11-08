package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.FogRevealer
import com.corrodinggames.rts.game.units.UnitCommandType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.actions.PlaceBuildingAction
import com.corrodinggames.rts.game.units.actions.QueueUnitAction
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.game.units.custom.price.UnitPrice
import com.corrodinggames.rts.game.units.land.TankUnit
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.Command
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.graphics.Texture
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.timeline.EngineTimelineProjector
import io.github.rwx.net.timeline.TimelineCanonical
import io.github.rwx.net.timeline.TimelineProfile
import io.github.rwx.net.timeline.TimelinePublisher
import io.github.rwx.net.timeline.TimelineTlvValue
import kotlin.concurrent.thread
import kotlin.test.*

class EngineViewProjectorTest {
    private val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val registryField = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }

    private class TestTank : TankUnit(true) {
        var visibleToEnemies = true
        var acceptsOrders = true
        var sightRadius = 2
        var sightReads = 0
        var level = 1
        var reportedType: UnitTypeEnum? = null
        var onControlRead: (() -> Unit)? = null
        val actions = arrayListOf<AbstractUnitAction>()
        // Unit construction normally requires loaded graphics. This fixture only exercises simulation state.
        override fun a(texture: Texture?, frames: Int) { T(20); U(20) }
        override fun isVisibleToEnemies(): Boolean = visibleToEnemies
        override fun r(): UnitTypeEnum = reportedType ?: UnitTypeEnum.tank
        override fun canNotBeGivenOrdersByPlayer(): Boolean { onControlRead?.invoke(); return !acceptsOrders }
        override fun s(): Int { sightReads++; return sightRadius }
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> = actions
        override fun getUpgradeLevel(): Int = level
    }

    private class TestCustom(config: CustomUnitConfig) : CustomUnit(true, config) {
        override fun a(config: CustomUnitConfig, initial: Boolean, convert: Boolean) {
            unitConfig = config
            y = config.unitStats
        }
    }

    private class World(val engine: GameLogic, val player: GameTeam, val enemy: GameTeam) {
        val map: TileMap get() = engine.tileMap
        fun unit(owner: PlayerTeam, x: Float = 10f, y: Float = 10f): TestTank = TestTank().apply {
            team = owner
            posX = x; posY = y
            objectId = 50_000 + BaseUnit.getGlobalUnitList().size.toLong()
            BaseUnit.getGlobalUnitList().add(this)
        }
        fun projector() = EngineViewProjector(engine, player)
        fun actionProjector(): EngineViewProjector = EngineViewProjector(engine, player,
            frozenStockCatalog = StockUnitCatalog.capture(engine)).also { engine.currentTick = 1 }
    }

    private fun withWorld(block: World.() -> Unit) {
        val previousEngine = instanceField.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instanceField.set(null, null)
        GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        val previousTeams = registryField.get(null) as Array<*>
        val previousUnits = BaseUnit.getGlobalUnitList().toList()
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        val previousConfigs = CustomUnitConfig.activeConfigs
        CustomUnitConfig.activeConfigs = arrayListOf()
        CustomUnitConfig.unitTypeOverrides.clear()
        registryField.set(null, arrayOfNulls<PlayerTeam>(previousTeams.size))
        BaseUnit.getGlobalUnitList().clear()
        try {
            engine.apply {
                networkEngine = NetworkEngine()
                networkEngine.roomSettings.fogMode = 2
                commandController = CommandController()
                replayEngine = ReplayEngine()
                tileMap = TileMap().apply {
                    tileCountX = 4; tileCountY = 3
                    tileWorldSizeX = 20; tileWorldSizeY = 20
                    tileScaleX = .05f; tileScaleY = .05f
                    fogEnabled = true; fogPeriodicMaintenanceEnabled = true
                    groundLayer = MapLayer(this, "ground", tileCountX, tileCountY)
                    registerUniqueTile(MapTile::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance())
                }
            }
            val player = GameTeam(0).apply {
                teamColorId = 0; credits = 1234.0
                fogOfWarData = Array(4) { ByteArray(3) { 10 } }
                fogOfWarData[0][0] = 0
            }
            val enemy = GameTeam(1).apply { teamColorId = 1; credits = 9999.0 }
            engine.playerTeam = player
            World(engine, player, enemy).block()
        } finally {
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            CustomUnitConfig.activeConfigs = previousConfigs
            BaseUnit.getGlobalUnitList().clear()
            BaseUnit.getGlobalUnitList().addAll(previousUnits)
            registryField.set(null, previousTeams)
            instanceField.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    @Test fun `hidden changes do not change the permitted view and disclosed bytes are detached`() = withWorld {
        val own = unit(player)
        own.rotationSpeed = 93f
        val hidden = unit(enemy, 70f, 50f)
        val projector = projector()
        val first = projector.snapshot()
        hidden.posX = 40f; hidden.currentHealth = 1f; hidden.isDead = true
        enemy.credits = 7.0
        val second = projector.snapshot()
        assertEquals(first.units, second.units)
        assertContentEquals(first.fog, second.fog)
        assertEquals(1234.0, second.credits)
        assertEquals(93f, second.units.single().heading)
        own.objectId += 100_000
        assertEquals(second.units.single().handle, projector.snapshot().units.single().handle)
        first.fog.fill(0)
        assertEquals(10, player.fogOfWarData[3][2].toInt())
        assertEquals(second.fog[2 * 4 + 3], projector.snapshot().fog[2 * 4 + 3])
    }

    @Test fun `snapshot fog stays detached across owner advances and external array mutations`() = withWorld {
        val scout = unit(player)
        unit(enemy)
        unit(enemy, 70f, 50f)
        val projector = projector()
        val first = projector.snapshot()
        val initialFog = first.fog.copyOf()
        val second = projector.dynamicSnapshot()
        assertNotSame(first.fog, second.fog)
        assertContentEquals(initialFog, second.fog)
        // A downstream consumer owns its flattened snapshot. Neither write may touch private LOS.
        thread { first.fog.fill(0); second.fog.fill(10) }.join()
        val unchanged = projector.snapshot()
        assertContentEquals(initialFog, unchanged.fog)
        assertEquals(first.units, unchanged.units)
        val keptFog = unchanged.fog.copyOf()
        scout.posX = 70f; scout.posY = 50f
        engine.currentTick++
        val advanced = projector.dynamicSnapshot()
        assertContentEquals(keptFog, unchanged.fog, "A later owner calculation cannot refog an older sample")
        assertTrue(first.fog.all { it == 0.toByte() })
        assertTrue(second.fog.all { it == 10.toByte() })
        assertEquals(5, advanced.fog[0].toInt(), "Old exploration is retained without retaining current sight")
        assertTrue(advanced.fog[11] < 5)
        assertEquals(listOf(70f), advanced.units.filter { !it.controllable }.map { it.x })
        assertEquals(10, player.fogOfWarData[3][2].toInt(), "Independent LOS never mutates native simulation fog")
        val terminalFog = advanced.fog.copyOf()
        advanced.fog.fill(0)
        enemy.isTeamDefeatedTech = true
        val terminal = projector.snapshot()
        assertContentEquals(terminalFog, terminal.fog, "The private terminal copy is still independent")
        terminal.fog.fill(10)
        assertContentEquals(terminalFog, projector.dynamicSnapshot().fog)
    }

    @Test fun `orders recompute sight within the same tick instead of trusting published or mutated fog`() = withWorld {
        val scout = unit(player)
        val enemyUnit = unit(enemy)
        val projector = projector()
        val view = projector.dynamicSnapshot()
        val actor = view.units.single { it.controllable }.handle
        val target = view.units.single { !it.controllable }.handle
        val attack = ViewUnitOrder(1, view.sequence, OrderKind.ATTACK_UNIT, listOf(actor), CommandTarget.Unit(target))
        scout.posX = 70f; scout.posY = 50f
        view.fog.fill(0)
        assertFalse(projector.apply(attack), "Even unchanged tick and a fresh receipt require current sight")
        scout.posX = 10f; scout.posY = 10f
        view.fog.fill(10)
        assertTrue(projector.apply(attack.copy(sequence = 2)), "Current sight does not depend on a client's copy")
        assertSame(enemyUnit, engine.commandController.pendingCommands.single().unitCommand.targetUnit)
        scout.posX = 70f; scout.posY = 50f
        assertFalse(projector.apply(attack.copy(sequence = 3)))
        assertEquals(1, engine.commandController.pendingCommands.size)
    }

    @Test fun `own control and panels do not explore while current foreign target checks do`() = withWorld {
        engine.networkEngine.roomSettings.revealedMap = false
        val actor = unit(player)
        val target = unit(enemy)
        val projector = actionProjector()
        val first = projector.snapshot()
        val owner = first.units.single { it.controllable }.handle
        val targetHandle = first.units.single { !it.controllable }.handle
        assertEquals(10.toByte(), first.fog[11])
        val reads = actor.sightReads
        actor.posX = 70f; actor.posY = 50f
        val panel = projector.actionPanel(ViewActionPanelQuery(1, first.sequence, owner))
        val patrol = panel.actions.single { it.kind == ViewActionKind.PATROL }
        for ((index, kind) in listOf(OrderKind.MOVE, OrderKind.ATTACK_MOVE, OrderKind.STOP).withIndex()) {
            assertTrue(projector.apply(ViewUnitOrder(index + 1L, first.sequence, kind, listOf(owner),
                if (kind == OrderKind.STOP) CommandTarget.None else CommandTarget.Point(50f, 30f))))
        }
        assertTrue(projector.applyAction(ViewActionCommand(4, first.sequence, panel.panelSequence,
            owner, patrol.handle, CommandTarget.Point(50f, 30f))))
        assertEquals(reads, actor.sightReads, "Own control neither observes terrain nor runs unrelated sight getters")
        actor.posX = 10f; actor.posY = 10f
        val unchanged = projector.snapshot()
        assertContentEquals(first.fog, unchanged.fog, "An unpublished temporary position cannot gain exploration from own queries")

        actor.posX = 70f; actor.posY = 50f
        target.posX = 70f; target.posY = 50f
        val beforeTarget = actor.sightReads
        assertTrue(projector.apply(ViewUnitOrder(5, unchanged.sequence, OrderKind.ATTACK_UNIT,
            listOf(owner), CommandTarget.Unit(targetHandle))))
        assertTrue(actor.sightReads > beforeTarget)
        assertSame(target, engine.commandController.pendingCommands.last().unitCommand.targetUnit)
        actor.posX = 10f; actor.posY = 10f
        val afterTarget = projector.snapshot()
        assertEquals(5.toByte(), afterTarget.fog[11], "A real current-target sight check retains its authorized exploration")
        assertTrue(afterTarget.units.none { it.handle == targetHandle })
    }

    @Test fun `own controls still require native fog shape and complete current actor fields`() = withWorld {
        val actor = unit(player)
        val projector = actionProjector()
        val view = projector.snapshot()
        val owner = view.units.single().handle
        var sequence = 0L
        var query = 0L
        fun panel() = projector.actionPanel(ViewActionPanelQuery(++query, view.sequence, owner))
        fun stop() = projector.apply(ViewUnitOrder(++sequence, view.sequence, OrderKind.STOP, listOf(owner)))
        val shape = player.fogOfWarData
        for (bad in listOf<Array<ByteArray>?>(null, Array(3) { ByteArray(3) },
            Array(4) { ByteArray(if (it == 2) 2 else 3) })) {
            val selected = panel()
            val patrol = selected.actions.single { it.kind == ViewActionKind.PATROL }
            player.fogOfWarData = bad
            assertFalse(stop())
            assertTrue(panel().actions.isEmpty())
            assertFalse(projector.applyAction(ViewActionCommand(++sequence, view.sequence, selected.panelSequence,
                owner, patrol.handle, CommandTarget.Point(30f, 10f))))
            player.fogOfWarData = shape
        }
        val health = actor.currentHealth
        val maxHealth = actor.maxHealth
        val mutations = listOf<Pair<() -> Unit, () -> Unit>>(
            { actor.currentHealth = Float.NaN } to { actor.currentHealth = health },
            { actor.maxHealth = 0f } to { actor.maxHealth = maxHealth },
            { actor.posZ = Float.POSITIVE_INFINITY } to { actor.posZ = 0f },
            { actor.buildProgress = Float.NaN } to { actor.buildProgress = 1f },
            { actor.level = 4 } to { actor.level = 1 },
            { actor.reportedType = UnitTypeEnum.editorOrBuilder } to { actor.reportedType = null },
            { actor.team = enemy } to { actor.team = player },
        )
        for ((change, restore) in mutations) {
            change()
            assertFalse(stop())
            assertTrue(panel().actions.isEmpty())
            restore()
        }
        assertTrue(stop())
        assertEquals(1, engine.commandController.pendingCommands.size)
    }

    @Test fun `own control callbacks cannot turn a null fog check into foreign authority`() = withWorld {
        val actor = unit(player)
        val target = unit(player, 30f, 10f)
        val projector = actionProjector()
        val view = projector.snapshot()
        val owner = view.units.single { it.x == actor.posX }.handle
        val targetHandle = view.units.single { it.x == target.posX }.handle
        actor.onControlRead = { actor.onControlRead = null; actor.team = enemy }
        assertFalse(projector.apply(ViewUnitOrder(1, view.sequence, OrderKind.STOP, listOf(owner))))
        actor.team = player
        actor.onControlRead = { actor.onControlRead = null; actor.team = enemy }
        assertTrue(projector.actionPanel(ViewActionPanelQuery(1, view.sequence, owner)).actions.isEmpty())
        actor.team = player
        val panel = projector.actionPanel(ViewActionPanelQuery(2, view.sequence, owner))
        val guard = panel.actions.single { it.kind == ViewActionKind.GUARD }
        val reads = actor.sightReads
        // This target was owned at the decision to skip LOS, then a native callback changes it.
        actor.onControlRead = { actor.onControlRead = null; target.team = enemy }
        assertFalse(projector.applyAction(ViewActionCommand(2, view.sequence, panel.panelSequence,
            owner, guard.handle, CommandTarget.Unit(targetHandle))))
        assertEquals(reads, actor.sightReads)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `own control still permanently rejects a changed catalog after restoration`() = withWorld {
        val actor = unit(player)
        val projector = actionProjector()
        val view = projector.snapshot()
        val owner = view.units.single().handle
        val panel = projector.actionPanel(ViewActionPanelQuery(1, view.sequence, owner))
        val patrol = panel.actions.single { it.kind == ViewActionKind.PATROL }
        val registry = CustomUnitConfig.activeConfigs
        val reads = actor.sightReads
        CustomUnitConfig.activeConfigs = arrayListOf()
        assertFalse(projector.apply(ViewUnitOrder(1, view.sequence, OrderKind.STOP, listOf(owner))))
        CustomUnitConfig.activeConfigs = registry
        assertTrue(projector.actionPanel(ViewActionPanelQuery(2, view.sequence, owner)).actions.isEmpty())
        assertFalse(projector.applyAction(ViewActionCommand(2, view.sequence, panel.panelSequence,
            owner, patrol.handle, CommandTarget.Point(30f, 10f))))
        assertFailsWith<IllegalStateException> { projector.snapshot() }
        assertEquals(reads, actor.sightReads)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `action validation remains owner bound and reads current subject eligibility`() = withWorld {
        val actorUnit = unit(player)
        val guardedUnit = unit(player, 30f, 10f)
        val projector = actionProjector()
        val view = projector.dynamicSnapshot()
        val actor = view.units.single { it.x == actorUnit.posX }.handle
        val target = view.units.single { it.x == guardedUnit.posX }.handle
        val panel = projector.actionPanel(ViewActionPanelQuery(1, view.sequence, actor))
        val guard = panel.actions.single { it.kind == ViewActionKind.GUARD }
        val intent = ViewActionCommand(1, view.sequence, panel.panelSequence, actor, guard.handle,
            CommandTarget.Unit(target))
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        thread { failure.set(runCatching { projector.applyAction(intent) }.exceptionOrNull()) }.join()
        assertIs<IllegalStateException>(failure.get())
        assertTrue(projector.applyAction(intent), "A rejected foreign-thread call cannot consume the owner's sequence")
        guardedUnit.transportContainer = actorUnit
        view.fog.fill(0)
        assertFalse(projector.applyAction(intent.copy(sequence = 2)), "Published handles cannot authorize hidden cargo")
        guardedUnit.transportContainer = null
        view.fog.fill(10)
        assertTrue(projector.applyAction(intent.copy(sequence = 3)))
        actorUnit.acceptsOrders = false
        assertFalse(projector.applyAction(intent.copy(sequence = 4)), "Present control still revokes old capabilities")
        assertEquals(2, engine.commandController.pendingCommands.size)
        assertTrue(engine.commandController.pendingCommands.all { it.unitCommand.commandType == UnitCommandType.guard })
    }

    @Test fun `losing vision revokes handles and reacquisition allocates a new handle`() = withWorld {
        val scout = unit(player)
        unit(enemy)
        val projector = projector()
        val first = projector.snapshot()
        val own = first.units.single { it.controllable }.handle
        val enemyHandle = first.units.single { !it.controllable }.handle
        scout.posX = 70f; scout.posY = 50f
        assertFalse(projector.apply(ViewUnitOrder(1, first.sequence, OrderKind.ATTACK_UNIT, listOf(own), target = CommandTarget.Unit(enemyHandle))))
        assertEquals(1, projector.snapshot().units.size)
        scout.posX = 10f; scout.posY = 10f
        val recovered = projector.snapshot()
        assertNotEquals(enemyHandle, recovered.units.single { !it.controllable }.handle)
        assertFalse(projector.apply(ViewUnitOrder(2, recovered.sequence, OrderKind.ATTACK_UNIT, listOf(own), target = CommandTarget.Unit(enemyHandle))))
    }

    @Test fun `transported hidden malformed and noninteractive objects are excluded`() = withWorld {
        val carrier = unit(player)
        unit(enemy).transportContainer = carrier
        unit(enemy).parentEntity = carrier
        unit(enemy).visibleToEnemies = false
        unit(enemy, -1f)
        unit(enemy, Float.NaN)
        unit(enemy).maxHealth = Float.NaN
        BaseUnit.getGlobalUnitList().add(FogRevealer(true).apply { team = enemy; posX = 10f; posY = 10f })
        assertEquals(listOf(0), projector().snapshot().units.map { it.teamId })
    }

    @Test fun `event subjects use only the preceding authorized snapshot on the engine owner`() = withWorld {
        val own = unit(player)
        val hidden = unit(enemy, 70f, 50f)
        val cargo = unit(enemy).apply { transportContainer = own }
        val projector = projector()
        assertTrue(projector.eventSubjects().isEmpty())
        projector.snapshot()
        assertEquals(listOf(own), projector.eventSubjects().toList())
        hidden.posX = 10f; hidden.posY = 10f
        cargo.transportContainer = null
        // New world visibility, including cargo teardown, cannot enlarge the old grant.
        assertEquals(listOf(own), projector.eventSubjects().toList())
        assertFailsWith<UnsupportedOperationException> { (projector.eventSubjects() as MutableCollection<BaseUnit>).clear() }
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        thread { failure.set(runCatching { projector.eventSubjects() }.exceptionOrNull()) }.join()
        assertIs<IllegalStateException>(failure.get())
        projector.snapshot()
        assertEquals(setOf(own, hidden, cargo), projector.eventSubjects().toSet())
    }

    @Test fun `fog and terrain use row major indexing without exporting map entities`() = withWorld {
        unit(player, 50f, 30f)
        player.fogOfWarData[2][1] = 4
        val tile = MapTile::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        tile.isWater = true; tile.isResourcePool = true
        map.groundLayer.setTileAt(2, 1, tile, false)
        val projector = projector()
        val snapshot = projector.snapshot()
        assertEquals(1, snapshot.fog[6].toInt())
        assertEquals(4, player.fogOfWarData[2][1].toInt())
        assertEquals(5, snapshot.terrain[6].toInt())
        snapshot.terrain.fill(15)
        assertEquals(5, projector.snapshot().terrain[6].toInt())
        assertEquals(1, snapshot.units.size)
    }

    @Test fun `reconnected and terminal projectors reuse the original public map baseline`() = withWorld {
        engine.tileMap = PublicMapFixture.create()
        unit(player)
        val baseline = EnginePublicMapSnapshot.capture(map)
        val initial = EngineViewProjector(engine, player, baseline).snapshot()
        map.pathingOverrideLayer.setTileAt(0, 0, null, false)
        map.groundLayer.getTileAt(1, 1).tilesetLocalIndex = 4
        val reconnected = EngineViewProjector(engine, player, baseline).snapshot()
        assertContentEquals(initial.terrain, reconnected.terrain)
        assertContentEquals(initial.mapVisual!!.groundTileIds, reconnected.mapVisual!!.groundTileIds)
        assertContentEquals(initial.mapVisual!!.decorationTileIds, reconnected.mapVisual!!.decorationTileIds)
        enemy.isTeamDefeatedTech = true
        val terminal = EngineViewProjector(engine, player, baseline).snapshot()
        assertEquals(MatchPhase.FINISHED, terminal.matchStatus.phase)
        assertTrue(terminal.units.isEmpty())
        assertTrue(terminal.fog.all { it.toInt() == 10 })
        assertContentEquals(initial.terrain, terminal.terrain)
        assertContentEquals(initial.mapVisual!!.groundTileIds, terminal.mapVisual!!.groundTileIds)
        assertContentEquals(initial.mapVisual!!.decorationTileIds, terminal.mapVisual!!.decorationTileIds)
    }

    @Test fun `dynamic samples omit only static arrays and retain real command capabilities`() = withWorld {
        engine.tileMap = PublicMapFixture.create()
        unit(player)
        unit(enemy)
        unit(enemy, 70f, 50f)
        val baseline = EnginePublicMapSnapshot.capture(map)
        val stock = StockUnitCatalog.capture(engine)
        val projector = EngineViewProjector(engine, player, baseline, stock)
        engine.currentTick = 1
        val full = projector.snapshot()
        val dynamic = projector.dynamicSnapshot()
        assertEquals(full.sequence + 1, dynamic.sequence)
        assertTrue(dynamic.terrain.isEmpty())
        assertNull(dynamic.mapVisual)
        assertEquals(12, full.terrain.size)
        assertNotNull(full.mapVisual)
        assertContentEquals(full.fog, dynamic.fog)
        // Byte-array equality is referential; replace only the deliberately omitted static fields,
        // sequence and the already content-compared fog to compare every remaining authorized field.
        assertEquals(full, dynamic.copy(sequence = full.sequence, fog = full.fog,
            terrain = full.terrain, mapVisual = full.mapVisual))
        assertEquals(2, dynamic.units.size)
        val own = dynamic.units.single { it.controllable }.handle
        val panel = projector.actionPanel(ViewActionPanelQuery(1, dynamic.sequence, own))
        val patrol = panel.actions.single { it.kind == ViewActionKind.PATROL }
        assertTrue(projector.applyAction(ViewActionCommand(1, dynamic.sequence, panel.panelSequence,
            own, patrol.handle, CommandTarget.Point(50f, 30f), queued = true)))
        assertTrue(projector.apply(ViewUnitOrder(2, full.sequence, OrderKind.STOP, listOf(own))))
        assertFalse(projector.apply(ViewUnitOrder(2, dynamic.sequence, OrderKind.STOP, listOf(own))))
        assertEquals(UnitCommandType.patrol, engine.commandController.pendingCommands[0].unitCommand.commandType)
        assertTrue(engine.commandController.pendingCommands[1].clearExistingOrders)
        assertTrue(engine.commandController.pendingCommands.all { it.team === player && it.sourceTeam === player })
        full.terrain.fill(15)
        full.mapVisual!!.groundTileIds.fill(0)
        full.mapVisual!!.decorationTileIds.fill(0)
        val laterFull = projector.snapshot()
        assertContentEquals(baseline.terrainCopy(), laterFull.terrain)
        assertContentEquals(baseline.visualCopy()!!.groundTileIds, laterFull.mapVisual!!.groundTileIds)
        assertContentEquals(baseline.visualCopy()!!.decorationTileIds, laterFull.mapVisual!!.decorationTileIds)
    }

    @Test fun `dynamic samples preserve terminal privacy owner and frozen map guards`() = withWorld {
        engine.tileMap = PublicMapFixture.create()
        unit(player)
        unit(enemy, 70f, 50f)
        val projector = EngineViewProjector(engine, player, EnginePublicMapSnapshot.capture(map))
        val initial = projector.dynamicSnapshot()
        val authorizedFog = initial.fog.copyOf()
        initial.fog.fill(0)
        val ownerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        thread { ownerFailure.set(runCatching { projector.dynamicSnapshot() }.exceptionOrNull()) }.join()
        assertIs<IllegalStateException>(ownerFailure.get())
        enemy.isTeamDefeatedTech = true
        player.fogOfWarData.forEach { it.fill(0) }
        player.credits = 0.0
        val terminal = projector.dynamicSnapshot()
        val terminalFull = projector.snapshot()
        assertEquals(MatchPhase.FINISHED, terminal.matchStatus.phase)
        assertTrue(terminal.units.isEmpty())
        assertTrue(terminal.terrain.isEmpty())
        assertNull(terminal.mapVisual)
        assertContentEquals(authorizedFog, terminal.fog)
        assertEquals(initial.credits, terminal.credits)
        assertContentEquals(terminal.fog, terminalFull.fog)
        assertEquals(terminalFull, terminal.copy(sequence = terminalFull.sequence, fog = terminalFull.fog,
            terrain = terminalFull.terrain, mapVisual = terminalFull.mapVisual))
        assertFalse(projector.apply(ViewUnitOrder(1, initial.sequence, OrderKind.STOP,
            listOf(initial.units.single().handle))))
        engine.tileMap = PublicMapFixture.create()
        assertFailsWith<IllegalStateException> { projector.dynamicSnapshot() }
    }

    @Test fun `timeline reuses equal immutable fog over ticks without suppressing other observations`() = withWorld {
        engine.tileMap = PublicMapFixture.create()
        val own = unit(player)
        val baseline = EnginePublicMapSnapshot.capture(map)
        val stock = StockUnitCatalog.capture(engine)
        val closure = StockActionClosure.capture(engine, stock)
        val bridge = EngineTimelineProjector(engine, player, baseline, stock, closure, { EngineMatchStatus.current() })
        val publisher = TimelinePublisher(engineTimelineTestContext(engine, baseline))
        engine.currentTick = 1
        val first = bridge.observe()
        val firstFog = first.fog.toByteArray()
        publisher.observe(first)
        val initial = assertNotNull(publisher.publish(0))
        val initialDigest = TimelineCanonical.digest(initial)
        repeat(4) { index ->
            engine.currentTick++
            player.credits += 1.0
            own.currentHealth = 90f - index
            val next = bridge.observe()
            assertSame(first.fog, next.fog)
            assertTrue(next.time > first.time)
            assertNotEquals(first.creditsQ, next.creditsQ)
            publisher.observe(next)
        }
        val latest = assertNotNull(publisher.publish(200_000_000))
        assertNotEquals(initialDigest, TimelineCanonical.digest(latest), "Time and changed fields still participate in full-state checks")
        assertEquals(initialDigest, TimelineCanonical.digest(initial), "A later observation cannot mutate an older committed library")
        assertContentEquals(firstFog, TimelineProfile.fogPage(latest, 0, latest.header.observedThrough))
        assertContentEquals(firstFog, first.fog.toByteArray())
    }

    @Test fun `timeline movement and sight changes create new fog while prior observations stay detached`() = withWorld {
        engine.tileMap = PublicMapFixture.create()
        val own = unit(player)
        val baseline = EnginePublicMapSnapshot.capture(map)
        val stock = StockUnitCatalog.capture(engine)
        val closure = StockActionClosure.capture(engine, stock)
        val bridge = EngineTimelineProjector(engine, player, baseline, stock, closure, { EngineMatchStatus.current() })
        engine.currentTick = 1
        val first = bridge.observe()
        val firstFog = first.fog.toByteArray()
        first.fog.toByteArray().fill(10)
        own.posX = 70f; own.posY = 50f
        engine.currentTick++
        val moved = bridge.observe()
        val movedFog = moved.fog.toByteArray()
        assertNotSame(first.fog, moved.fog)
        assertFalse(firstFog.contentEquals(movedFog))
        assertContentEquals(firstFog, first.fog.toByteArray())
        own.sightRadius = 4
        engine.currentTick++
        val revealed = bridge.observe()
        assertNotSame(moved.fog, revealed.fog)
        assertFalse(movedFog.contentEquals(revealed.fog.toByteArray()))
        moved.fog.toByteArray().fill(0)
        assertContentEquals(movedFog, moved.fog.toByteArray())
        assertContentEquals(firstFog, first.fog.toByteArray())
        engine.currentTick++
        assertSame(revealed.fog, bridge.observe().fog)
    }

    @Test fun `timeline terminal fog and final fence stay immutable with or without a prior running observation`() {
        for (observeRunning in listOf(false, true)) withWorld {
            engine.tileMap = PublicMapFixture.create()
            unit(player)
            val baseline = EnginePublicMapSnapshot.capture(map)
            val stock = StockUnitCatalog.capture(engine)
            val closure = StockActionClosure.capture(engine, stock)
            val bridge = EngineTimelineProjector(engine, player, baseline, stock, closure, { EngineMatchStatus.current() })
            assertFailsWith<IllegalStateException> { bridge.finalObservationFence() }
            engine.currentTick = 1
            val running = if (observeRunning) bridge.observe() else null
            enemy.isTeamDefeatedTech = true
            engine.currentTick++
            val terminal = bridge.observe()
            assertTrue(terminal.units.isEmpty())
            if (running == null) assertTrue(terminal.fog.toByteArray().all { it == 10.toByte() })
            else assertSame(running.fog, terminal.fog)
            val bytes = terminal.fog.toByteArray()
            terminal.fog.toByteArray().fill(0)
            val fence = bridge.finalObservationFence()
            assertEquals(terminal.time + 1, fence.time)
            assertSame(terminal.fog, fence.fog)
            assertContentEquals(bytes, terminal.fog.toByteArray())
            assertContentEquals(bytes, fence.fog.toByteArray())
            assertFailsWith<IllegalStateException> { bridge.observe() }
        }
    }

    @Test fun `timeline captures dynamic samples while its independent initial map remains complete`() = withWorld {
        engine.tileMap = PublicMapFixture.create()
        unit(player)
        val baseline = EnginePublicMapSnapshot.capture(map)
        val stock = StockUnitCatalog.capture(engine)
        val closure = StockActionClosure.capture(engine, stock)
        val bridge = EngineTimelineProjector(engine, player, baseline, stock, closure, { EngineMatchStatus.current() })
        val storedView = EngineTimelineProjector::class.java.getDeclaredField("view").apply { isAccessible = true }
        val mapRecord = EngineTimelineProjector.mapRecord(engine, baseline)
        fun mapBytes(id: Int) = (mapRecord[id]!!.value as TimelineTlvValue.Bytes).value.toByteArray()
        assertContentEquals(baseline.terrainCopy(), mapBytes(6))
        assertEquals(24, mapBytes(8).size)
        assertEquals(24, mapBytes(9).size)
        engine.currentTick = 1
        val observed = bridge.observe()
        val dynamic = storedView.get(bridge) as PlayerViewSnapshot
        assertTrue(dynamic.terrain.isEmpty())
        assertNull(dynamic.mapVisual)
        assertContentEquals(dynamic.fog, observed.fog.toByteArray())
        assertEquals(dynamic.units.map { it.handle }, observed.units.map { it.key })
        val full = EngineViewProjector(engine, player, baseline, stock, closure).snapshot()
        assertEquals(12, full.terrain.size)
        assertNotNull(full.mapVisual)
        assertContentEquals(full.fog, dynamic.fog)
        enemy.isTeamDefeatedTech = true
        engine.currentTick++
        val terminal = bridge.observe()
        val terminalView = storedView.get(bridge) as PlayerViewSnapshot
        assertTrue(terminalView.terrain.isEmpty())
        assertNull(terminalView.mapVisual)
        assertTrue(terminal.units.isEmpty())
        assertEquals(terminal.time + 1, bridge.finalObservationFence().time)
        assertContentEquals(baseline.terrainCopy(), mapBytes(6))
    }

    @Test fun `missing fog and wrong modes fail closed including commands`() = withWorld {
        unit(player)
        val projector = projector()
        val view = projector.snapshot()
        val intent = ViewUnitOrder(1, view.sequence, OrderKind.STOP, listOf(view.units.single().handle))
        player.fogOfWarData = null
        assertFailsWith<IllegalStateException> { projector.snapshot() }
        assertFalse(projector.apply(intent))
        player.fogOfWarData = Array(4) { ByteArray(3) }
        engine.networkEngine.networkGameActive = true
        assertFailsWith<IllegalStateException> { projector.snapshot() }
        assertFalse(projector.apply(intent.copy(sequence = 2)))
        engine.networkEngine.networkGameActive = false
        map.fogPeriodicMaintenanceEnabled = false
        assertFailsWith<IllegalStateException> { projector.snapshot() }
        map.fogPeriodicMaintenanceEnabled = true
        player.fogOfWarData[3] = byteArrayOf(0)
        assertFailsWith<IllegalStateException> { projector.snapshot() }
    }

    @Test fun `unused native fog values cannot alter v7 transactions full digest or live targeting`() = withWorld {
        engine.tileMap = PublicMapFixture.create()
        val scout = unit(player)
        val targetUnit = unit(enemy)
        unit(enemy, 70f, 50f)
        val baseline = EnginePublicMapSnapshot.capture(map)
        val stock = StockUnitCatalog.capture(engine)
        val closure = StockActionClosure.capture(engine, stock)
        fun stream() = EngineTimelineTestStream(EngineTimelineProjector(engine, player, baseline, stock,
            closure, { EngineMatchStatus.current() }), engineTimelineTestContext(engine, baseline))
        val clean = stream()
        val altered = stream()
        fun same(a: EngineTimelineTestStream.Result, b: EngineTimelineTestStream.Result) {
            assertContentEquals(a.wire, b.wire, "Native fog values cannot affect actual v7 transactions")
            assertContentEquals(a.canonical, b.canonical)
            assertEquals(TimelineCanonical.digest(a.state), TimelineCanonical.digest(b.state))
        }
        same(clean.capture(0), altered.capture(0))
        val commands = EngineViewProjector(engine, player, baseline, stock, closure)
        var commandSequence = 0L
        for ((round, value) in listOf(0, -1, 11, 127).withIndex()) {
            engine.currentTick += 6
            targetUnit.currentHealth -= 1f
            val now = (round + 1) * 100_000_000L
            player.fogOfWarData.forEach { it.fill(10) }
            val expected = clean.capture(now)
            player.fogOfWarData.forEach { it.fill(value.toByte()) }
            same(expected, altered.capture(now))
            val view = commands.dynamicSnapshot()
            assertTrue(view.fog.all { it.toInt() in 0..10 }, "Only generated legal fog reaches a snapshot")
            assertEquals(2, view.units.size)
            val own = view.units.single { it.controllable }.handle
            val target = view.units.single { !it.controllable }.handle
            fun attack() = ViewUnitOrder(++commandSequence, view.sequence, OrderKind.ATTACK_UNIT,
                listOf(own), CommandTarget.Unit(target))
            assertTrue(commands.apply(attack()))
            scout.posX = 70f; scout.posY = 50f
            assertFalse(commands.apply(attack()), "Raw value $value cannot grant a currently hidden target")
            scout.posX = 10f; scout.posY = 10f
        }
        assertEquals(4, engine.commandController.pendingCommands.size)
        assertTrue(engine.commandController.pendingCommands.all { it.unitCommand.targetUnit === targetUnit })
    }

    @Test fun `point orders into fog are queued through the real command controller and bound to the seat`() = withWorld {
        val own = unit(player)
        unit(enemy)
        val projector = projector()
        val snapshot = projector.snapshot()
        val ownHandle = snapshot.units.single { it.controllable }.handle
        for ((index, kind) in listOf(OrderKind.MOVE, OrderKind.ATTACK_MOVE, OrderKind.STOP).withIndex()) {
            val point = if (kind == OrderKind.STOP) 0f to 0f else 70f to 50f
            assertTrue(projector.apply(ViewUnitOrder(index + 1L, snapshot.sequence, kind, listOf(ownHandle), if (kind == OrderKind.STOP) CommandTarget.None else CommandTarget.Point(point.first, point.second))))
        }
        val commands = engine.commandController.pendingCommands
        assertEquals(3, commands.size)
        assertTrue(commands.all { it.team === player && it.sourceTeam === player })
        val selected = Command::class.java.getDeclaredField("selectedUnits").apply { isAccessible = true }.get(commands[0]) as List<*>
        assertSame(own, selected.single())
        assertEquals(UnitCommandType.move, commands[0].unitCommand.commandType)
        assertEquals(UnitCommandType.attackMove, commands[1].unitCommand.commandType)
        assertEquals(70f, commands[0].unitCommand.targetX)
        assertTrue(commands[2].clearExistingOrders)
    }

    @Test fun `replays forged selections stale snapshots and invalid points cannot queue commands`() = withWorld {
        unit(player)
        unit(enemy)
        val projector = projector()
        val view = projector.snapshot()
        val own = view.units.single { it.controllable }.handle
        val enemyHandle = view.units.single { !it.controllable }.handle
        fun intent(sequence: Long, handles: List<Long> = listOf(own), x: Float = 50f, viewSequence: Long = view.sequence) =
            ViewUnitOrder(sequence, viewSequence, OrderKind.MOVE, handles, CommandTarget.Point(x, 30f))
        assertFalse(projector.apply(intent(1, listOf(enemyHandle))))
        assertFalse(projector.apply(intent(2, listOf(own, own))))
        assertFalse(projector.apply(intent(3, x = 80f)))
        assertFalse(projector.apply(intent(4, x = Float.NaN)))
        assertFalse(projector.apply(intent(5, viewSequence = view.sequence + 1)))
        assertTrue(projector.apply(intent(6)))
        assertFalse(projector.apply(intent(6)))
        engine.currentTick = 181
        assertFalse(projector.apply(intent(7)))
        assertEquals(1, engine.commandController.pendingCommands.size)
    }

    @Test fun `orders require positive sequences and exact typed targets including the origin`() = withWorld {
        unit(player)
        val projector = projector()
        val view = projector.snapshot()
        val handle = view.units.single().handle
        val move = ViewUnitOrder(0, view.sequence, OrderKind.MOVE, listOf(handle), CommandTarget.Point(0f, 0f))
        assertFalse(projector.apply(move))
        assertFalse(projector.apply(move.copy(sequence = 1, target = CommandTarget.None)))
        assertFalse(projector.apply(move.copy(sequence = 2, kind = OrderKind.STOP)))
        assertFalse(projector.apply(move.copy(sequence = 3, kind = OrderKind.ATTACK_UNIT)))
        assertFalse(projector.apply(move.copy(sequence = 4, target = CommandTarget.Unit(handle))))
        assertTrue(projector.apply(move.copy(sequence = 5)))
        assertEquals(1, engine.commandController.pendingCommands.size)
        assertEquals(0f, engine.commandController.pendingCommands.single().unitCommand.targetX)
    }

    @Test fun `commands require both current control eligibility and current target visibility`() = withWorld {
        val ownUnit = unit(player)
        val target = unit(enemy)
        val projector = projector()
        val view = projector.snapshot()
        val own = view.units.single { it.controllable }.handle
        val targetHandle = view.units.single { !it.controllable }.handle
        val attack = ViewUnitOrder(1, view.sequence, OrderKind.ATTACK_UNIT, listOf(own), target = CommandTarget.Unit(targetHandle))
        assertTrue(projector.apply(attack))
        assertSame(target, engine.commandController.pendingCommands.single().unitCommand.targetUnit)
        target.posX = 70f
        assertFalse(projector.apply(attack.copy(sequence = 2)))
        ownUnit.acceptsOrders = false
        assertFalse(projector.apply(ViewUnitOrder(3, view.sequence, OrderKind.STOP, listOf(own))))
    }

    @Test fun `view access from another thread is rejected`() = withWorld {
        val projector = projector()
        var failure: Throwable? = null
        thread { failure = runCatching { projector.snapshot() }.exceptionOrNull() }.join()
        assertIs<IllegalStateException>(failure)
    }

    @Test fun `legacy defeat fog reset never grants an all map spectator view`() = withWorld {
        unit(player)
        unit(enemy, 70f, 50f)
        val projector = projector()
        val view = projector.snapshot()
        player.fogOfWarData.forEach { it.fill(0) }
        player.isTeamWipedOut = true
        val terminal = projector.snapshot()
        assertEquals(MatchPhase.FINISHED, terminal.matchStatus.phase)
        assertEquals(listOf(1), terminal.matchStatus.winnerTeamIds)
        assertTrue(terminal.units.isEmpty())
        assertContentEquals(view.fog, terminal.fog)
        assertFalse(projector.apply(ViewUnitOrder(1, view.sequence, OrderKind.STOP, listOf(view.units.single().handle))))
        player.isTeamWipedOut = false
        player.isTeamDefeatedTech = true
        assertEquals(terminal.matchStatus, projector.snapshot().matchStatus)
        player.isTeamDefeatedTech = false
        player.isTeamVictory = true
        assertEquals(terminal.matchStatus, projector.snapshot().matchStatus)
    }

    @Test fun `scout leaving vision immediately hides the enemy without waiting for legacy fog maintenance`() = withWorld {
        val scout = unit(player)
        unit(enemy)
        val projector = projector()
        assertEquals(2, projector.snapshot().units.size)
        scout.posX = 70f; scout.posY = 50f
        assertEquals(0, player.fogOfWarData[0][0].toInt())
        assertEquals(1, projector.snapshot().units.size)
        assertEquals(0, player.fogOfWarData[0][0].toInt(), "Projection must not mutate simulation fog")
    }

    @Test fun `legacy fog matrix changes alone cannot broaden this seats view`() = withWorld {
        unit(player)
        unit(enemy, 70f, 50f)
        val projector = projector()
        val before = projector.snapshot()
        player.fogOfWarData.forEach { it.fill(0) }
        val after = projector.snapshot()
        assertEquals(before.units, after.units)
        assertContentEquals(before.fog, after.fog)
    }

    @Test fun `build and production require current server action capabilities and resources`() = withWorld {
        val builder = unit(player)
        builder.actions += PlaceBuildingAction(UnitTypeEnum.landFactory)
        val factory = unit(player, 30f, 10f)
        factory.actions += QueueUnitAction(UnitTypeEnum.tank)
        val projector = actionProjector()
        player.credits = 10_000.0
        val view = projector.snapshot()
        val builderHandle = view.units.single { it.x == 10f }.handle
        val factoryHandle = view.units.single { it.x == 30f }.handle
        fun panel(query: Long, owner: Long) = projector.actionPanel(ViewActionPanelQuery(query, view.sequence, owner))
        val builders = panel(1, builderHandle)
        val build = ViewActionCommand(1, view.sequence, builders.panelSequence, builderHandle,
            builders.actions.single { it.kind == ViewActionKind.BUILD }.handle, CommandTarget.Point(0f, 0f))
        assertTrue(projector.applyAction(build), "A point at the origin remains distinct from no target")
        val factories = panel(2, factoryHandle)
        val produce = ViewActionCommand(2, view.sequence, factories.panelSequence, factoryHandle,
            factories.actions.single { it.kind == ViewActionKind.PRODUCE }.handle)
        assertTrue(projector.applyAction(produce))
        assertEquals(UnitTypeEnum.landFactory, engine.commandController.pendingCommands[0].unitCommand.buildUnitType)
        assertEquals(factory.actions.single().queueId, engine.commandController.pendingCommands[1].actionId)
        assertFalse(projector.applyAction(produce.copy(sequence = 3, target = CommandTarget.Point(0f, 0f))),
            "A zero point cannot authorize a no-target action")
        assertFalse(projector.applyAction(produce.copy(sequence = 4, unitHandle = builderHandle)))
        val renewedFactory = panel(3, factoryHandle)
        player.credits = 0.0
        assertFalse(projector.applyAction(produce.copy(sequence = 5, panelSequence = renewedFactory.panelSequence,
            actionHandle = renewedFactory.actions.single { it.kind == ViewActionKind.PRODUCE }.handle)))
        val renewed = panel(4, builderHandle)
        assertFalse(projector.applyAction(build.copy(sequence = 6, panelSequence = renewed.panelSequence,
            actionHandle = renewed.actions.single { it.kind == ViewActionKind.BUILD }.handle)))
        assertEquals(2, engine.commandController.pendingCommands.size)
    }

    @Test fun `stock turret and extractor capabilities use frozen effective types and resource checks`() = withWorld {
        val turret = CustomUnitConfig().apply {
            name = "c_turret_t1"; configPath = "assets/units/turrets/turret_t1.ini"
            price = UnitPrice.a(500)
        }
        val extractor = CustomUnitConfig().apply {
            name = "extractorT1"; configPath = "assets/units/extractor/extractor.ini"
            price = UnitPrice.a(700)
        }
        CustomUnitConfig.activeConfigs.addAll(listOf(turret, extractor))
        CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.turret] = turret
        CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.extractor] = extractor
        val builder = unit(player)
        builder.actions += PlaceBuildingAction(UnitTypeEnum.turret)
        builder.actions += PlaceBuildingAction(UnitTypeEnum.extractor)
        val projector = actionProjector()
        val view = projector.snapshot()
        val handle = view.units.single().handle
        val panel = projector.actionPanel(ViewActionPanelQuery(1, view.sequence, handle))
        fun order(sequence: Long, type: String) = ViewActionCommand(sequence, view.sequence,
            panel.panelSequence, handle, panel.actions.single { it.producedTypeKey == type }.handle,
            CommandTarget.Point(50f, 30f))
        val turretOrder = order(1, "turret")
        val extractorOrder = order(2, "extractor")
        assertTrue(projector.applyAction(turretOrder))
        assertTrue(projector.applyAction(extractorOrder))
        assertSame(turret, engine.commandController.pendingCommands[0].unitCommand.buildUnitType)
        assertSame(extractor, engine.commandController.pendingCommands[1].unitCommand.buildUnitType)
        player.credits = 499.0
        assertFalse(projector.applyAction(turretOrder.copy(sequence = 3)))
        assertFalse(projector.applyAction(extractorOrder.copy(sequence = 4)))
        player.credits = 10_000.0
        CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.turret] = CustomUnitConfig().apply {
            name = turret.name; configPath = turret.configPath; price = turret.price
        }
        assertFalse(projector.applyAction(turretOrder.copy(sequence = 5)))
        extractor.configPath = "mods/extractor.ini"
        assertFalse(projector.applyAction(extractorOrder.copy(sequence = 6)))
        assertEquals(2, engine.commandController.pendingCommands.size)
    }

    @Test fun `action receipts work with a newer complete view and share ordinary command sequences`() = withWorld {
        unit(player)
        val projector = actionProjector()
        val first = projector.snapshot()
        val owner = first.units.single().handle
        val panel = projector.actionPanel(ViewActionPanelQuery(1, first.sequence, owner))
        val patrol = panel.actions.single { it.kind == ViewActionKind.PATROL }
        val newer = projector.snapshot()
        assertNotEquals(first.sequence, newer.sequence)
        val intent = ViewActionCommand(1, newer.sequence, panel.panelSequence, owner, patrol.handle,
            target = CommandTarget.Point(50f, 30f), queued = true)
        assertTrue(projector.applyAction(intent))
        assertEquals(UnitCommandType.patrol, engine.commandController.pendingCommands.single().unitCommand.commandType)
        assertFalse(projector.apply(ViewUnitOrder(1, newer.sequence, OrderKind.STOP, listOf(owner))))
        assertTrue(projector.apply(ViewUnitOrder(2, newer.sequence, OrderKind.STOP, listOf(owner))))
        assertFalse(projector.applyAction(intent.copy(sequence = 2)))
        assertEquals(2, engine.commandController.pendingCommands.size)
    }

    @Test fun `selection refresh preserves capabilities but clear and ownership changes revoke them`() = withWorld {
        val actor = unit(player)
        val projector = actionProjector()
        val view = projector.snapshot()
        val owner = view.units.single().handle
        val request = ViewActionPanelQuery(1, view.sequence, owner)
        val first = projector.actionPanel(request)
        val second = projector.actionPanel(request.copy(querySequence = 2))
        assertEquals(first.actions.map { it.handle }, second.actions.map { it.handle })
        val patrol = first.actions.single { it.kind == ViewActionKind.PATROL }
        fun intent(sequence: Long) = ViewActionCommand(sequence, view.sequence,
            first.panelSequence, owner, patrol.handle, CommandTarget.Point(30f, 10f))
        assertEquals(0, projector.actionPanel(request.copy(querySequence = 3, unitHandle = 0)).unitHandle)
        assertFalse(projector.applyAction(intent(1)))
        val third = projector.actionPanel(request.copy(querySequence = 4))
        assertTrue(third.actions.none { it.handle == patrol.handle })
        actor.team = enemy
        assertFalse(projector.applyAction(intent(2).copy(panelSequence = third.panelSequence,
            actionHandle = third.actions.single { it.kind == ViewActionKind.PATROL }.handle)))
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `empty panel queries preserve catalog ordering and clear without observing the world`() = withWorld {
        val actor = unit(player)
        val projector = actionProjector()
        val view = projector.dynamicSnapshot()
        val owner = view.units.single().handle
        fun query(sequence: Long, handle: Long) = ViewActionPanelQuery(sequence, view.sequence, handle)
        val selected = projector.actionPanel(query(1, owner))
        val patrol = selected.actions.single { it.kind == ViewActionKind.PATROL }
        fun intent(sequence: Long, panel: PlayerActionPanel = selected) = ViewActionCommand(sequence,
            view.sequence, panel.panelSequence, owner,
            panel.actions.single { it.kind == ViewActionKind.PATROL }.handle, CommandTarget.Point(30f, 10f))
        var reads = actor.sightReads
        val oldClear = projector.actionPanel(query(1, 0))
        assertEquals(selected.panelSequence + 1, oldClear.panelSequence)
        assertEquals(0, oldClear.unitHandle)
        assertTrue(oldClear.actions.isEmpty())
        assertEquals(reads, actor.sightReads)
        assertTrue(projector.applyAction(intent(1)), "An old clear query cannot revoke a newer selection")
        reads = actor.sightReads
        val nativeFog = player.fogOfWarData
        player.fogOfWarData = null
        val clear = projector.actionPanel(query(2, 0))
        assertEquals(oldClear.panelSequence + 1, clear.panelSequence)
        assertEquals(0, clear.unitHandle)
        assertTrue(clear.actions.isEmpty())
        assertEquals(reads, actor.sightReads, "A non-authorizing clear needs no native fog or LOS calculation")
        assertTrue(projector.actionPanel(query(3, owner)).actions.isEmpty(), "A nonempty query still requires ready fog")
        player.fogOfWarData = nativeFog
        assertFalse(projector.applyAction(intent(2)))
        val renewed = projector.actionPanel(query(4, owner))
        assertTrue(renewed.actions.none { it.handle == patrol.handle })
        assertEquals(reads, actor.sightReads, "A nonempty own panel validates control without observing terrain")
        actor.acceptsOrders = false
        assertTrue(projector.actionPanel(query(5, owner)).actions.isEmpty(), "Nonempty queries check present control")
        actor.acceptsOrders = true
        assertFalse(projector.applyAction(intent(3, renewed)), "Observed control loss permanently revokes old actions")
        val last = projector.actionPanel(query(6, owner))
        assertTrue(projector.applyAction(intent(4, last)))
        assertEquals(2, engine.commandController.pendingCommands.size)
    }

    @Test fun `action queries never authorize foreign unknown stale or pregame units`() = withWorld {
        val actor = unit(player)
        unit(enemy, 25f, 10f)
        val stock = StockUnitCatalog.capture(engine)
        val projector = EngineViewProjector(engine, player, frozenStockCatalog = stock)
        val pregame = projector.snapshot()
        val ownHandle = pregame.units.single { it.teamId == 0 }.handle
        assertTrue(projector.actionPanel(ViewActionPanelQuery(1, pregame.sequence, ownHandle)).actions.isEmpty())
        engine.currentTick = 1
        val view = projector.snapshot()
        val foreign = view.units.single { it.teamId == 1 }.handle
        for ((query, handle) in listOf(foreign, actor.objectId, Long.MAX_VALUE).withIndex()) {
            val result = projector.actionPanel(ViewActionPanelQuery(query + 2L, view.sequence, handle))
            assertEquals(0, result.unitHandle)
            assertTrue(result.actions.isEmpty())
        }
        engine.currentTick = 182
        assertTrue(projector.actionPanel(ViewActionPanelQuery(5, view.sequence, ownHandle)).actions.isEmpty())
    }

    @Test fun `world and action receipt expiration are independent`() = withWorld {
        unit(player)
        val projector = actionProjector()
        val old = projector.snapshot()
        val owner = old.units.single().handle
        val first = projector.actionPanel(ViewActionPanelQuery(1, old.sequence, owner))
        engine.currentTick = 182
        val current = projector.snapshot()
        val action = first.actions.single { it.kind == ViewActionKind.PATROL }
        val intent = ViewActionCommand(1, current.sequence, first.panelSequence, owner, action.handle, CommandTarget.Point(30f, 10f))
        assertFalse(projector.applyAction(intent), "A fresh world does not renew an old action receipt")
        val second = projector.actionPanel(ViewActionPanelQuery(2, current.sequence, owner))
        assertFalse(projector.applyAction(intent.copy(sequence = 2, viewSequence = old.sequence,
            panelSequence = second.panelSequence,
            actionHandle = second.actions.single { it.kind == ViewActionKind.PATROL }.handle)),
            "A fresh action panel does not renew an old world receipt")
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `native upgrade revokes selected owner capabilities before the next snapshot`() = withWorld {
        val actor = unit(player)
        val projector = actionProjector()
        val view = projector.snapshot()
        val owner = view.units.single().handle
        val first = projector.actionPanel(ViewActionPanelQuery(1, view.sequence, owner))
        val action = first.actions.single { it.kind == ViewActionKind.PATROL }
        actor.level = 2
        assertFalse(projector.applyAction(ViewActionCommand(1, view.sequence,
            first.panelSequence, owner, action.handle, CommandTarget.Point(30f, 10f))))
        val second = projector.actionPanel(ViewActionPanelQuery(2, view.sequence, owner))
        assertTrue(second.actions.none { it.handle == action.handle })
        assertEquals(2, projector.snapshot().units.single().upgradeLevel)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `observed transport or control loss permanently revokes an old panel before the next snapshot`() = withWorld {
        val actor = unit(player)
        val carrier = unit(player, 30f, 10f)
        val projector = actionProjector()
        val view = projector.snapshot()
        val owner = view.units.single { it.x == actor.posX }.handle
        fun request(query: Long) = projector.actionPanel(ViewActionPanelQuery(query, view.sequence, owner))
        val first = request(1)
        val action = first.actions.single { it.kind == ViewActionKind.PATROL }
        val intent = ViewActionCommand(1, view.sequence, first.panelSequence, owner, action.handle, CommandTarget.Point(30f, 10f))
        actor.transportContainer = carrier
        assertFalse(projector.applyAction(intent))
        actor.transportContainer = null
        assertFalse(projector.applyAction(intent.copy(sequence = 2)), "Unloading cannot revive an observed revoked capability")
        val second = request(2)
        val secondIntent = intent.copy(sequence = 3, panelSequence = second.panelSequence,
            actionHandle = second.actions.single { it.kind == ViewActionKind.PATROL }.handle)
        actor.team = enemy
        assertFalse(projector.applyAction(secondIntent))
        actor.team = player
        assertFalse(projector.applyAction(secondIntent.copy(sequence = 4)), "Regaining control requires a new panel")
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `entity action targets require current disclosure and exact target mode`() = withWorld {
        val actor = unit(player)
        val target = unit(player, 30f, 10f)
        val projector = actionProjector()
        val view = projector.snapshot()
        val owner = view.units.single { it.x == actor.posX }.handle
        val targetHandle = view.units.single { it.x == target.posX }.handle
        val panel = projector.actionPanel(ViewActionPanelQuery(1, view.sequence, owner))
        val guard = panel.actions.single { it.kind == ViewActionKind.GUARD }
        val intent = ViewActionCommand(1, view.sequence, panel.panelSequence,
            owner, guard.handle, target = CommandTarget.Unit(targetHandle))
        target.transportContainer = actor
        assertFalse(projector.applyAction(intent))
        target.transportContainer = null
        assertFalse(projector.applyAction(intent.copy(sequence = 2, target = CommandTarget.Unit(target.objectId))))
        assertFalse(projector.applyAction(intent.copy(sequence = 3, target = CommandTarget.Point(10f, 0f))))
        assertTrue(projector.applyAction(intent.copy(sequence = 4)))
        assertEquals(UnitCommandType.guard, engine.commandController.pendingCommands.single().unitCommand.commandType)
    }

    @Test fun `observed action removal cannot revive an old handle when the same object returns`() = withWorld {
        val actor = unit(player)
        val build = PlaceBuildingAction(UnitTypeEnum.landFactory)
        actor.actions += build
        val projector = actionProjector()
        val view = projector.snapshot()
        val owner = view.units.single().handle
        val panel = projector.actionPanel(ViewActionPanelQuery(1, view.sequence, owner))
        val action = panel.actions.single { it.kind == ViewActionKind.BUILD }
        val intent = ViewActionCommand(1, view.sequence, panel.panelSequence, owner, action.handle, CommandTarget.Point(30f, 10f))
        actor.actions.clear()
        assertFalse(projector.applyAction(intent))
        actor.actions += build
        assertFalse(projector.applyAction(intent.copy(sequence = 2)))
        val renewed = projector.actionPanel(ViewActionPanelQuery(2, view.sequence, owner))
        assertTrue(renewed.actions.none { it.handle == action.handle })
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `three seat match continues after one elimination`() = withWorld {
        val third = GameTeam(2)
        player.isTeamDefeated = true
        val running = EngineMatchStatus.current(listOf(0, 1, 2))
        assertEquals(MatchPhase.RUNNING, running.phase)
        assertEquals(listOf(0), running.defeatedTeamIds)
        third.isTeamDefeated = true
        val finished = EngineMatchStatus.current(listOf(0, 1, 2))
        assertEquals(MatchPhase.FINISHED, finished.phase)
        assertEquals(listOf(1), finished.winnerTeamIds)
        assertEquals(listOf(0, 2), finished.defeatedTeamIds)
    }

    @Test fun `eliminated seat loses current observation while the other seats continue`() = withWorld {
        GameTeam(2)
        val own = unit(player)
        unit(enemy, 25f, 10f)
        val projection = EngineViewProjector(engine, player, statusProvider = { EngineMatchStatus.current(listOf(0, 1, 2)) })
        val before = projection.snapshot()
        assertTrue(before.units.isNotEmpty())
        player.isTeamDefeated = true
        own.posX = 65f
        player.fogOfWarData = Array(4) { ByteArray(3) { 0 } }
        val after = projection.snapshot()
        assertEquals(MatchPhase.RUNNING, after.matchStatus.phase)
        assertEquals(listOf(0), after.matchStatus.defeatedTeamIds)
        assertTrue(after.units.isEmpty())
        assertContentEquals(before.fog, after.fog)
    }

    @Test fun `unreviewed unit visibility cannot execute world logic or grant vision even in native fixture mode`() = withWorld {
        var reads = 0
        val predicate = object : LogicBoolean() {
            override fun read(unit: com.corrodinggames.rts.game.units.OrderableUnit): Boolean {
                reads++
                error("Unit visibility must not evaluate hidden-world logic")
            }
            override fun getMatchFailReasonForPlayer(unit: com.corrodinggames.rts.game.units.OrderableUnit): String =
                error("Unit visibility must not format a dynamic explanation")
        }
        val config = CustomUnitConfig().apply {
            name = "c_tank"; configPath = "assets/units/tanks/tank.ini"
            isVisible = predicate
            unitStats.fogOfWarSightRange = 15
        }
        CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = config
        val opaque = TestCustom(config).apply {
            team = player; posX = 10f; posY = 10f; buildProgress = 1f
            currentHealth = 100f; maxHealth = 100f
        }
        unit(enemy, 25f, 10f)
        for (revealedMap in listOf(false, true)) {
            engine.networkEngine.roomSettings.revealedMap = revealedMap
            val projector = projector()
            val baseline = projector.snapshot()
            BaseUnit.getGlobalUnitList().add(opaque)
            val view = projector.snapshot()
            assertTrue(view.units.isEmpty())
            assertContentEquals(baseline.fog, view.fog,
                "Rejected visibility cannot add sight to the public initial exploration state")
            val initialFog = if (revealedMap) 5 else 10
            assertTrue(view.fog.all { it.toInt() == initialFog })
            assertEquals(0, reads)
            BaseUnit.getGlobalUnitList().remove(opaque)
        }
    }
}
