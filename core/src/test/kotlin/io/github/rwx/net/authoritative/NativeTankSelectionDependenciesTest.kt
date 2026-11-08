package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.UnitContainer
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.timeline.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** No Room scheduling or shared capture is enabled by this passive local prerequisite. */
class NativeTankSelectionDependenciesTest {
    private class CallbackTank(config: CustomUnitConfig) : CustomUnit(true, config) {
        var onRead: ((String) -> Unit)? = null
        override fun r(): CustomUnitConfig? { onRead?.invoke("type"); return unitConfig }
        override fun getUpgradeLevel(): Int { onRead?.invoke("tech"); return unitConfig.techLevel }
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> {
            onRead?.invoke("actions")
            @Suppress("UNCHECKED_CAST")
            return super.getAvailableActions() as ArrayList<AbstractUnitAction>
        }
    }

    private class World(val engine: GameLogic, val own: GameTeam, val other: GameTeam,
                        val config: CustomUnitConfig, val stock: StockUnitCatalog,
                        val closure: StockActionClosure, val map: EnginePublicMapSnapshot) {
        var statusReads = 0
        var poisonStatus = false
        fun timeline() = EngineTimelineProjector(engine, own, map, stock, closure) {
            statusReads++
            check(!poisonStatus) { "The qualification probe evaluated status" }
            MatchStatus()
        }
        fun actor(x: Float, callback: Boolean = false): CustomUnit =
            (if (callback) CallbackTank(config) else CustomUnit(true, config)).apply {
                team = own; buildProgress = 1f; currentHealth = 100f; maxHealth = 100f
                posX = x; posY = 100f; posZ = 0f; rotationSpeed = 0f
            }.also { BaseUnit.getGlobalUnitList().add(it) }
        fun key(observation: TimelineObservation, x: Float): Long =
            observation.units.single { it.position.x == (x * 256).toLong() }.key
        fun select(timeline: EngineTimelineProjector, vararg keys: Long) {
            val out = ByteArrayOutputStream()
            TimelineBinary.writeVUInt(out, keys.size.toLong())
            keys.indices.forEach { TimelineBinary.writeVUInt(out, it + 1L) }
            val frame = timelineFrame(6, tn(1, 1, true), tn(2, 1), tn(3, 1), tn(4, 1),
                tb(5, TimelineBytes(out.toByteArray())))
            TimelineMessages.validate(frame, true)
            val receipt = TimelineReceipt(1, 1, 1, engine.currentTick * 1024L,
                System.nanoTime(), LongArray(keys.size) { it + 1L }, longArrayOf())
            assertTrue(timeline.apply(TimelineRequest(0, 1, frame, receipt),
                { wire -> keys.getOrNull((wire - 1).toInt()) }, { null }))
        }
        fun publisherBytes(observation: TimelineObservation): Pair<ByteArray, TimelineBytes> {
            val record = EngineTimelineProjector.mapRecord(engine, map)
            val context = TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }),
                TimelineCanonical.mapKey(record), record, 0, 60)
            val publisher = TimelinePublisher(context)
            publisher.observe(observation)
            val state = assertNotNull(publisher.publish(0))
            val bytes = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
            return bytes to TimelineCanonical.digest(state)
        }
    }

    private fun withWorld(block: World.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null); GameEngine.isNonAndroidVersion = true
        try {
            // Config/logic static initialization needs a fully installed engine and network owner.
            val engine = GameLogic().apply { networkEngine = NetworkEngine() }
            val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
            val previousTeams = registry.get(null) as Array<*>
            val previousConfigs = CustomUnitConfig.activeConfigs
            val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
            val world = BaseUnit.getGlobalUnitList()
            val previousWorld = world.toList()
            try {
                world.clear(); registry.set(null, arrayOfNulls<PlayerTeam>(previousTeams.size))
                CustomUnitConfig.activeConfigs = arrayListOf(); CustomUnitConfig.unitTypeOverrides.clear()
                engine.commandController = CommandController(); engine.replayEngine = ReplayEngine()
                engine.networkEngine.roomSettings.fogMode = 2
                engine.tileMap = PublicMapFixture.flat(16, 16).apply {
                    fogEnabled = true; fogPeriodicMaintenanceEnabled = true
                }
                val own = GameTeam(0).apply {
                    teamColorId = 0; credits = 1000.0; fogOfWarData = Array(16) { ByteArray(16) { 10 } }
                }
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
                CustomUnitConfig::class.java.getDeclaredField("specialActionLists").apply { isAccessible = true }
                    .set(config, Array(3) { UnitContainer() })
                CustomUnitConfig.activeConfigs.add(config)
                CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = config
                val stock = StockUnitCatalog.capture(engine)
                val closure = StockActionClosure.capture(engine, stock)
                engine.currentTick = 1
                World(engine, own, other, config, stock, closure,
                    EnginePublicMapSnapshot.capture(engine.tileMap)).block()
            } finally {
                world.clear(); previousWorld.forEach { world.add(it) }
                CustomUnitConfig.activeConfigs = previousConfigs
                CustomUnitConfig.unitTypeOverrides.clear(); CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
                registry.set(null, previousTeams)
            }
        } finally {
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun projection(timeline: EngineTimelineProjector) =
        field(timeline, "projection").get(timeline) as EngineViewProjector
    private fun catalog(timeline: EngineTimelineProjector) =
        field(projection(timeline), "actionCatalog").get(projection(timeline)) as EngineActionCatalog

    @Test fun `real SELECT before Owner materialization includes its private actor without side effects`() = withWorld {
        val actor = actor(100f)
        val timeline = timeline()
        val first = timeline.observe()
        select(timeline, key(first, 100f))
        assertTrue(catalog(timeline).hasNoRetainedActionDependencies(), "C6 has not created a panel Owner yet")
        assertFalse(timeline.hasEmptyLocalObservationDependencies())
        val reads = statusReads
        val world = BaseUnit.getGlobalUnitList()
        val iterator = world.iterator()
        poisonStatus = true
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            val descriptor = assertNotNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
            repeat(3) { assertTrue(descriptor.stillCurrent()) }
            assertSame(actor, iterator.next(), "An empty world flush would invalidate this iterator")
            assertEquals(reads, statusReads)
            assertTrue(catalog(timeline).hasNoRetainedActionDependencies())
            descriptor.close(); assertFalse(descriptor.stillCurrent())
        } finally { scope.close(); poisonStatus = false }
        assertEquals(listOf(key(first, 100f)), timeline.observe().selectedUnitKeys)
    }

    @Test fun `unknown freshly selected receiver is rejected before any virtual getter`() = withWorld {
        val actor = actor(100f, callback = true) as CallbackTank
        val timeline = timeline(); val first = timeline.observe()
        select(timeline, key(first, 100f))
        assertTrue(catalog(timeline).hasNoRetainedActionDependencies())
        var reads = 0
        actor.onRead = { reads++; error("Unknown selected actor: $it") }
        poisonStatus = true
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try { assertNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope)); assertEquals(0, reads) }
        finally { scope.close(); actor.onRead = null; poisonStatus = false }
        assertEquals(listOf(key(first, 100f)), timeline.observe().selectedUnitKeys)
    }

    @Test fun `multi selection falls back as a whole and preserves every selected member`() = withWorld {
        actor(100f); val unknown = actor(160f, callback = true) as CallbackTank
        val timeline = timeline(); val first = timeline.observe()
        val keys = longArrayOf(key(first, 100f), key(first, 160f))
        select(timeline, *keys)
        var reads = 0; unknown.onRead = { reads++; error("Unknown multi selected actor: $it") }
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try { assertNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope)); assertEquals(0, reads) }
        finally { scope.close(); unknown.onRead = null }
        assertEquals(keys.toList(), timeline.observe().selectedUnitKeys)
    }

    @Test fun `new clean or empty selection never erases retained unknown catalog actors`() = withWorld {
        actor(100f); val unknown = actor(160f, callback = true) as CallbackTank
        val timeline = timeline(); val first = timeline.observe()
        select(timeline, key(first, 160f)); timeline.observe()
        assertFalse(catalog(timeline).hasNoRetainedActionDependencies())
        select(timeline, key(first, 100f))
        var reads = 0; unknown.onRead = { reads++; error("Retained actor getter: $it") }
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            assertNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
            select(timeline)
            assertNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
            assertEquals(0, reads)
            assertFalse(catalog(timeline).hasNoRetainedActionDependencies(), "Query cannot revoke old roots")
        } finally { scope.close(); unknown.onRead = null }
    }

    @Test fun `selection change backing change and owner change permanently invalidate local descriptors`() = withWorld {
        val actor = actor(100f); actor(160f)
        val timeline = timeline(); val first = timeline.observe()
        select(timeline, key(first, 100f))
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            val changedSelection = assertNotNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
            select(timeline, key(first, 160f))
            assertFalse(changedSelection.stillCurrent())
            select(timeline, key(first, 100f)); assertFalse(changedSelection.stillCurrent())
            val changedOwner = assertNotNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
            actor.team = other; assertFalse(changedOwner.stillCurrent()); actor.team = own
            assertFalse(changedOwner.stillCurrent())
            val changedConfig = assertNotNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
            actor.factoryUnitConfig = config; assertFalse(changedConfig.stillCurrent()); actor.factoryUnitConfig = null
            assertFalse(changedConfig.stillCurrent())
            assertNull(projection(timeline).nativeSyntheticTankObservationDependenciesOrNull(Long.MAX_VALUE, scope))
            assertNull(projection(timeline).nativeSyntheticTankObservationDependenciesOrNull(0, scope))
        } finally { scope.close() }
    }

    @Test fun `qualification changes neither full authorized bytes nor complete canonical hash`() = withWorld {
        actor(100f)
        val timeline = timeline(); val initial = timeline.observe()
        select(timeline, key(initial, 100f))
        val before = timeline.observe()
        assertTrue(before.actions.isNotEmpty(), "Materialize real native synthetic action capabilities")
        val bytes = publisherBytes(before)
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            val descriptor = assertNotNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
            repeat(3) { assertTrue(descriptor.stillCurrent()) }
            descriptor.close()
        } finally { scope.close() }
        val after = timeline.observe()
        assertEquals(before.units, after.units); assertEquals(before.actions, after.actions)
        assertEquals(before.selectedUnitKeys, after.selectedUnitKeys); assertEquals(before.fog, after.fog)
        val afterBytes = publisherBytes(after)
        assertContentEquals(bytes.first, afterBytes.first)
        assertEquals(bytes.second, afterBytes.second)
    }

    @Test fun `scope owner engine and explicit boundary disposal remain mandatory`() = withWorld {
        actor(100f)
        val timeline = timeline(); val initial = timeline.observe(); select(timeline, key(initial, 100f))
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        val descriptor = assertNotNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
        val failure = AtomicReference<Throwable?>()
        thread { failure.set(runCatching { descriptor.stillCurrent() }.exceptionOrNull()) }.join()
        assertIs<IllegalStateException>(failure.get())
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        instance.set(null, null)
        try {
            assertFailsWith<IllegalStateException> { descriptor.stillCurrent() }
            descriptor.close() // Cleanup does not require the replaced engine to still be installed.
        } finally { instance.set(null, engine) }
        assertFalse(descriptor.stillCurrent())
        val fresh = assertNotNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
        config.ignoreMoveOrders = true; config.ignoreMoveOrders = false
        assertTrue(fresh.stillCurrent(), "Current-field probes cannot discover an unobserved A-B-A callback")
        fresh.close(); assertFalse(fresh.stillCurrent())
        scope.close()
        assertNull(timeline.nativeSyntheticTankObservationDependenciesOrNull(scope))
    }
}
