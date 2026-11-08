package com.corrodinggames.rts.gameFramework

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitCommand
import com.corrodinggames.rts.game.units.land.TankUnit
import com.corrodinggames.rts.gameFramework.graphics.Texture
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import kotlin.test.*

/** Native command/formation code, with textures omitted and deterministic registered unit fixtures. */
class AuthoritativeSingleUnitFormationTest {
    private val moveThrottleField = OrderableUnit::class.java.getDeclaredField("moveThrottle").apply { isAccessible = true }
    private class UnitFixture : TankUnit(true) {
        var mobile = true
        var joins = true
        var valid = true
        var mobilityReads = 0
        var onAssigned: ((UnitCommand) -> Unit)? = null
        override fun a(texture: Texture?, frames: Int) { T(20); U(20) }
        override fun I(): Boolean { mobilityReads++; return mobile }
        override fun bg() = joins
        override fun isValidNewWaypoint(command: UnitCommand?, warn: Boolean) = valid && super.isValidNewWaypoint(command, warn)
        override fun a(command: UnitCommand) { onAssigned?.invoke(command) }
    }

    private class World(val engine: GameLogic, val team: GameTeam) {
        val units = ArrayList<UnitFixture>()
        fun unit(registered: Boolean = true): UnitFixture = UnitFixture().apply {
            objectId = units.size + 1L; this.team = this@World.team
            posX = 100f + units.size * 20; posY = 200f
            // Same normal-unit lifecycle flags as BaseUnit(false), without registering a second
            // process-global GameObject list that is unrelated to formation enumeration.
            isHighlighted = registered
            if (registered) {
                BaseUnit.bE.add(this)
                BaseUnit.getGlobalUnitList().add(this)
            }
            units.add(this)
        }
        fun command(vararg selected: UnitFixture, queued: Boolean = false, replay: Boolean = false): Command =
            engine.commandController.createCommand().apply {
                team = this@World.team; sourceTeam = this@World.team
                isQueued = queued; isReplayCommand = replay
                setMoveTarget(700f, 600f)
                selected.forEach(::addUnitToCommand)
            }
    }

    private fun <T> withWorld(enabled: Boolean = false, block: World.() -> T): T {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val oldEngine = instance.get(null)
        val oldPlatform = GameEngine.isNonAndroidVersion
        val oldHeadless = GameEngine.isHeadlessMode
        instance.set(null, null); GameEngine.isNonAndroidVersion = true; GameEngine.isHeadlessMode = true
        val engine = GameLogic()
        val oldTeams = registry.get(null) as Array<*>
        val oldUnits = BaseUnit.getGlobalUnitList().toList()
        val oldFormationUnits = BaseUnit.bE.toList()
        registry.set(null, arrayOfNulls<PlayerTeam>(oldTeams.size))
        BaseUnit.getGlobalUnitList().clear()
        BaseUnit.bE.clear()
        try {
            engine.networkEngine = NetworkEngine()
            engine.replayEngine = ReplayEngine()
            engine.commandController = CommandController()
            engine.formationEngine = FormationEngine().apply { a() }
            engine.tileMap = TileMap().apply {
                tileCountX = 64; tileCountY = 64; tileWorldSizeX = 20; tileWorldSizeY = 20
            }
            engine.gameTimeMillis = 1234
            val team = GameTeam(0).apply { teamColorId = 0 }
            engine.playerTeam = team
            if (enabled) engine.formationEngine.enableAuthoritativeHeadlessSingleUnitFormation()
            return World(engine, team).block()
        } finally {
            BaseUnit.getGlobalUnitList().clear(); oldUnits.forEach { BaseUnit.getGlobalUnitList().add(it) }
            BaseUnit.bE.clear(); oldFormationUnits.forEach { BaseUnit.bE.add(it) }
            registry.set(null, oldTeams); instance.set(null, oldEngine)
            GameEngine.isNonAndroidVersion = oldPlatform; GameEngine.isHeadlessMode = oldHeadless
        }
    }

    private fun formationBytes(engine: FormationEngine) = GameOutputStream().let { engine.a(it); it.toByteArray() }
    private fun waypointState(waypoint: UnitCommand) = listOf(waypoint.commandType, waypoint.targetX.toRawBits(),
        waypoint.targetY.toRawBits(), waypoint.transportTarget?.formationId, waypoint.isQueued)
    private fun unitState(unit: UnitFixture): List<Any?> = listOf(unit.objectId, unit.isDead, unit.isDestroyed,
        unit.waypointSyncGroupId, unit.transportedBy?.objectId, unit.transportedUnitCount, unit.isTransportAttached,
        unit.shouldMaintainFormation, unit.formationSlotIndex, unit.inFormation, unit.isFormationMember,
        unit.transportOffsetX.toRawBits(), unit.transportOffsetY.toRawBits(), unit.formationSlotAngle.toRawBits(),
        unit.formationSlotDistanceSq.toRawBits(), unit.lastTransportPathUpdateTick, moveThrottleField.getFloat(unit).toRawBits(),
        unit.navigationAngle.toRawBits(), unit.waypointCount, (0 until unit.waypointCount).map { waypointState(unit.getWaypointAt(it)) })
    private fun groupState(group: FormationGroup): List<Any?> = listOf(group.formationId, group.isQueued,
        group.targetX.toRawBits(), group.targetY.toRawBits(), group.units.map { it.objectId })
    private data class Outcome(val groups: List<List<Any?>>, val units: List<List<Any?>>,
        val center: List<Int>, val formationCounter: List<Byte>)
    private fun World.outcome(groups: List<FormationGroup>) = Outcome(groups.map(::groupState), units.map(::unitState),
        listOf(engine.formationEngine.b.x.toRawBits(), engine.formationEngine.b.y.toRawBits()), formationBytes(engine.formationEngine).toList())

    @Test fun `explicit opt in is owner engine and map bound and absent from legacy serialization`() = withWorld {
        val formation = engine.formationEngine
        assertFalse(formation.isAuthoritativeHeadlessSingleUnitFormationEnabled)
        val before = formationBytes(formation)
        GameEngine.isHeadlessMode = false
        assertFailsWith<IllegalStateException> { formation.enableAuthoritativeHeadlessSingleUnitFormation() }
        GameEngine.isHeadlessMode = true
        engine.networkEngine.networkGameActive = true
        assertFailsWith<IllegalStateException> { formation.enableAuthoritativeHeadlessSingleUnitFormation() }
        engine.networkEngine.networkGameActive = false
        engine.currentTick = 1
        assertFailsWith<IllegalStateException> { formation.enableAuthoritativeHeadlessSingleUnitFormation() }
        engine.currentTick = 0
        engine.replayEngine.P = true
        ReplayEngine::class.java.getDeclaredField("isReplaying").apply { isAccessible = true }.setBoolean(engine.replayEngine, true)
        assertFailsWith<IllegalStateException> { formation.enableAuthoritativeHeadlessSingleUnitFormation() }
        engine.replayEngine.P = false
        assertFailsWith<IllegalStateException> { FormationEngine().enableAuthoritativeHeadlessSingleUnitFormation() }
        formation.enableAuthoritativeHeadlessSingleUnitFormation()
        assertTrue(formation.isAuthoritativeHeadlessSingleUnitFormationEnabled)
        assertContentEquals(before, formationBytes(formation))
        var anotherThreadEnabled = true
        Thread { anotherThreadEnabled = formation.isAuthoritativeHeadlessSingleUnitFormationEnabled }.apply { start(); join() }
        assertFalse(anotherThreadEnabled)
        val map = engine.tileMap
        engine.tileMap = TileMap()
        assertFalse(formation.isAuthoritativeHeadlessSingleUnitFormationEnabled)
        engine.tileMap = map
        engine.networkEngine.networkGameActive = true
        assertFalse(formation.isAuthoritativeHeadlessSingleUnitFormationEnabled)
        engine.networkEngine.networkGameActive = false
        GameEngine.isHeadlessMode = false
        assertFalse(formation.isAuthoritativeHeadlessSingleUnitFormationEnabled)
    }

    @Test fun `actual single move preserves all native formation and waypoint state without scanning unrelated units`() {
        fun run(enabled: Boolean) = withWorld(enabled) {
            val actor = unit()
            actor.formationSlotIndex = 9; actor.isTransportAttached = true; actor.formationSlotAngle = 12f
            val unrelated = List(32) { unit() }
            command(actor).executeCommand()
            val group = assertNotNull(actor.currentWaypoint.transportTarget)
            assertEquals(listOf(actor), group.units.toList())
            assertEquals(700f, group.targetX); assertEquals(600f, group.targetY)
            assertEquals(1, actor.formationSlotIndex.toInt())
            assertTrue(actor.isTransportAttached)
            outcome(listOf(group)) to unrelated.sumOf { it.mobilityReads }
        }
        val reference = run(false)
        val optimized = run(true)
        assertEquals(reference.first, optimized.first)
        assertEquals(32, reference.second)
        assertEquals(0, optimized.second)
    }

    @Test fun `queued noncurrent waypoint and exact mobility participation filters remain empty groups`() {
        for (scenario in listOf("queued", "immobile", "nonmember", "differentGroup")) {
            fun run(enabled: Boolean) = withWorld(enabled) {
                val actor = unit()
                val other = engine.formationEngine.b()
                if (scenario == "queued") other.a(actor, actor.appendMoveWaypoint(20f, 30f))
                if (scenario == "immobile") actor.mobile = false
                if (scenario == "nonmember") actor.joins = false
                var assigned: FormationGroup? = null
                actor.onAssigned = { waypoint ->
                    assigned = waypoint.transportTarget
                    if (scenario == "differentGroup") waypoint.transportTarget = other
                }
                command(actor, queued = scenario == "queued").executeCommand()
                val group = assertNotNull(assigned)
                assertTrue(group.units.isEmpty(), scenario)
                if (scenario == "queued") assertSame(other, actor.currentWaypoint.transportTarget)
                outcome(listOf(group, other))
            }
            assertEquals(run(false), run(true), scenario)
        }
    }

    @Test fun `dead removed dummy and zero valid assignments conservatively retain original enumeration`() {
        for (scenario in listOf("dead", "removed", "dummy", "invalid")) {
            fun run(enabled: Boolean) = withWorld(enabled) {
                val actor = unit(registered = scenario != "dummy")
                val unrelated = unit()
                when (scenario) {
                    "dead" -> { actor.isDead = true; BaseUnit.bE.remove(actor); BaseUnit.getGlobalUnitList().remove(actor) }
                    "removed" -> { actor.isDestroyed = true; BaseUnit.bE.remove(actor); BaseUnit.getGlobalUnitList().remove(actor) }
                    "invalid" -> actor.valid = false
                }
                command(actor).executeCommand()
                assertTrue(unrelated.mobilityReads > 0, scenario)
                outcome(listOfNotNull(actor.currentWaypoint?.transportTarget))
            }
            assertEquals(run(false), run(true), scenario)
        }
    }

    @Test fun `two assigned units retain native global order grouping and leader selection`() {
        fun run(enabled: Boolean) = withWorld(enabled) {
            val first = unit(); val second = unit(); val unrelated = unit()
            // Selection order differs from bE order; the old global enumeration must remain decisive.
            command(second, first).executeCommand()
            val group = assertNotNull(first.currentWaypoint.transportTarget)
            assertEquals(listOf(first, second), group.units.toList())
            assertTrue(unrelated.mobilityReads > 0)
            outcome(listOf(group))
        }
        assertEquals(run(false), run(true))
    }

    @Test fun `single successful assignment among rejected selections uses the same native result`() {
        fun run(enabled: Boolean) = withWorld(enabled) {
            val invalid = unit().apply { valid = false }
            val actor = unit()
            val unrelated = unit()
            command(invalid, actor).executeCommand()
            assertNull(invalid.currentWaypoint)
            val group = assertNotNull(actor.currentWaypoint.transportTarget)
            assertEquals(listOf(actor), group.units.toList())
            outcome(listOf(group)) to unrelated.mobilityReads
        }
        val reference = run(false); val optimized = run(true)
        assertEquals(reference.first, optimized.first)
        assertTrue(reference.second > 0); assertEquals(0, optimized.second)
    }

    @Test fun `later regrouping and repeat shortcut calls always scan the current whole world`() = withWorld(true) {
        val first = unit(); val second = unit(); val unrelated = unit()
        command(first).executeCommand()
        val group = assertNotNull(first.currentWaypoint.transportTarget)
        assertEquals(0, unrelated.mobilityReads)
        group.a(second, second.appendMoveWaypoint(700f, 600f))
        group.c()
        assertEquals(listOf(first, second), group.units.toList())
        assertTrue(unrelated.mobilityReads > 0)
        unrelated.mobilityReads = 0
        group.rebuildFreshSingleAssignment(first)
        assertEquals(listOf(first, second), group.units.toList())
        assertTrue(unrelated.mobilityReads > 0)
    }

    @Test fun `replay commands and runtime mode changes cannot use the headless shortcut`() {
        for (scenario in listOf("replayCommand", "network", "map", "desktop")) withWorld(true) {
            val actor = unit(); val unrelated = unit()
            when (scenario) {
                "network" -> engine.networkEngine.networkGameActive = true
                "map" -> engine.tileMap = TileMap()
                "desktop" -> GameEngine.isHeadlessMode = false
            }
            command(actor, replay = scenario == "replayCommand").executeCommand()
            assertTrue(unrelated.mobilityReads > 0, scenario)
            assertEquals(listOf(actor), actor.currentWaypoint.transportTarget.units.toList())
        }
    }
}
