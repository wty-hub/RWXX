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

/** Real closed-room observations compared against the unchanged ordinary path. */
class NativeObservationBatchTest {
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

    private class CallbackTeam(id: Int) : GameTeam(id) {
        var onSpectatorRead: (() -> Unit)? = null
        override fun isSpectatorTeamColor(): Boolean {
            onSpectatorRead?.invoke()
            return super.isSpectatorTeamColor()
        }
    }

    private class World(val engine: GameLogic, val own: GameTeam, val other: GameTeam,
                        val config: CustomUnitConfig, val stock: StockUnitCatalog,
                        val closure: StockActionClosure, val map: EnginePublicMapSnapshot) {
        val roster = TimelineRoomRosterSource.nativeSlots()
        fun native() = EngineTimelineProjector.forNativeRoom(engine, own, map, stock, closure, roster)
        fun native(team: GameTeam) = EngineTimelineProjector.forNativeRoom(engine, team, map, stock, closure, roster)
        fun batch() = NativeObservationBatch(engine, stock, closure, roster)
        var statusReads = 0
        var poisonStatus = false
        fun timeline() = EngineTimelineProjector(engine, own, map, stock, closure) {
            statusReads++
            check(!poisonStatus) { "The qualification probe evaluated status" }
            MatchStatus()
        }
        fun actor(x: Float, callback: Boolean = false, owner: GameTeam = own): CustomUnit =
            (if (callback) CallbackTank(config) else CustomUnit(true, config)).apply {
                team = owner; buildProgress = 1f; currentHealth = 100f; maxHealth = 100f
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
        fun publisherBytes(observation: TimelineObservation, team: Int = 0): Pair<ByteArray, TimelineBytes> {
            val record = EngineTimelineProjector.mapRecord(engine, map)
            val context = TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }),
                TimelineCanonical.mapKey(record), record, team, 60)
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
            val previousNeutral = PlayerTeam.TEAM_NEUTRAL; val previousEnemies = PlayerTeam.TEAM_ENEMIES
            val previousConfigs = CustomUnitConfig.activeConfigs
            val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
            val world = BaseUnit.getGlobalUnitList()
            val previousWorld = world.toList()
            try {
                world.clear(); PlayerTeam.setMaxTeamId(64, false)
                registry.set(null, arrayOfNulls<PlayerTeam>(PlayerTeam.TEAM_ENEMIES))
                CustomUnitConfig.activeConfigs = arrayListOf(); CustomUnitConfig.unitTypeOverrides.clear()
                engine.commandController = CommandController(); engine.replayEngine = ReplayEngine()
                engine.networkEngine.roomSettings.fogMode = 2
                engine.tileMap = PublicMapFixture.flat(16, 16).apply {
                    fogEnabled = true; fogPeriodicMaintenanceEnabled = true
                }
                val own = GameTeam(0).apply {
                    teamColorId = 0; credits = 1000.0; fogOfWarData = Array(16) { ByteArray(16) { 10 } }
                }
                val other = GameTeam(1).apply { teamColorId = 1; credits = 1000.0; fogOfWarData = Array(16) { ByteArray(16) { 10 } } }
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
                PlayerTeam.TEAM_NEUTRAL = previousNeutral; PlayerTeam.TEAM_ENEMIES = previousEnemies
            }
        } finally {
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    private fun World.compare(expected: TimelineObservation, actual: TimelineObservation, team: Int = 0) {
        assertEquals(expected.units, actual.units); assertEquals(expected.actions, actual.actions)
        assertEquals(expected.fog, actual.fog); assertEquals(expected.selectedUnitKeys, actual.selectedUnitKeys)
        assertEquals(expected.events, actual.events); assertEquals(expected.time, actual.time)
        assertEquals(expected.matchStatus, actual.matchStatus); assertEquals(expected.creditsQ, actual.creditsQ)
        val a = publisherBytes(expected, team); val b = publisherBytes(actual, team)
        assertContentEquals(a.first, b.first); assertEquals(a.second, b.second)
    }

    @Test fun `native grants share one capture and preserve separate fog units handles and complete canonical state`() = withWorld {
        actor(60f); actor(80f, owner = other); actor(280f, owner = other)
        val first = native(); val second = native(other); val third = native()
        val expected = listOf(first.observe(), second.observe(), third.observe())
        assertNotEquals(expected[0].fog, expected[1].fog)
        assertFalse(expected[0].units.any { it.position.x == 280L * 256 })
        batch().use { batch ->
            compare(expected[0], first.observeInBatch(batch))
            compare(expected[1], second.observeInBatch(batch), 1)
            compare(expected[2], third.observeInBatch(batch))
            assertEquals(NativeObservationBatch.Statistics(1, 2, 0), batch.statistics())
        }
    }

    @Test fun `exact command center builder and custom unit fields match the ordinary full native projection`() = withWorld {
        actor(60f)
        listOf(com.corrodinggames.rts.game.units.buildings.CommandCenter(true),
            com.corrodinggames.rts.game.units.land.BuilderUnit(true)).forEachIndexed { index, unit ->
            unit.team = if (index == 0) own else other; unit.posX = 180f + 40 * index; unit.posY = 150f
            unit.posZ = 0f; unit.rotationSpeed = 45f; unit.currentHealth = 90f; unit.maxHealth = 100f; unit.buildProgress = 1f
            BaseUnit.getGlobalUnitList().add(unit)
        }
        val first = native(); val next = native(other); val a = first.observe(); val b = next.observe()
        batch().use { batch ->
            compare(a, first.observeInBatch(batch)); compare(b, next.observeInBatch(batch), 1)
            assertEquals(NativeObservationBatch.Statistics(1, 1, 0), batch.statistics())
        }
    }

    @Test fun `hidden native changes do not enter authorized bytes or private fog in fresh owner groups`() = withWorld {
        actor(60f); val hidden = actor(280f, owner = other)
        val projection = native()
        val before = batch().use { projection.observeInBatch(it) }
        assertFalse(before.units.any { it.ownerTeam == 1 })
        hidden.currentHealth = 12f; hidden.maxHealth = 400f; hidden.rotationSpeed = 277f
        hidden.posX = 260f; hidden.posZ = 50f; hidden.buildProgress = .25f
        actor(290f, owner = other)
        val after = batch().use { projection.observeInBatch(it) }
        compare(before, after)
    }

    @Test fun `real single selection synthetic capabilities remain identical while facts are reused`() = withWorld {
        actor(60f); actor(80f)
        val first = native(); val second = native()
        for (projection in listOf(first, second)) { val initial = projection.observe(); select(projection, key(initial, 60f)) }
        val a = first.observe(); val b = second.observe()
        assertTrue(a.actions.isNotEmpty()); assertTrue(b.actions.isNotEmpty())
        batch().use { batch ->
            compare(a, first.observeInBatch(batch)); compare(b, second.observeInBatch(batch))
            assertEquals(NativeObservationBatch.Statistics(1, 1, 0), batch.statistics())
        }
    }

    @Test fun `old unknown actor outside world breaks group before callbacks and following grant recaptures`() = withWorld {
        actor(60f); val target = actor(80f, owner = other)
        val old = actor(120f, callback = true) as CallbackTank
        val first = native(); val middle = native(); val last = native()
        val original = middle.observe(); select(middle, key(original, 120f)); middle.observe()
        BaseUnit.getGlobalUnitList().remove(old)
        val trace = arrayListOf<String>()
        old.onRead = { trace.add(it); target.posX = 280f }
        batch().use { batch ->
            val before = first.observeInBatch(batch)
            assertTrue(before.units.any { it.ownerTeam == 1 && it.position.x == 80L * 256 }); assertTrue(trace.isEmpty())
            middle.observeInBatch(batch)
            assertTrue(trace.isNotEmpty(), "The original retained-owner callbacks must execute")
            val after = last.observeInBatch(batch)
            assertFalse(after.units.any { it.ownerTeam == 1 }); compare(last.observe(), after)
            assertEquals(NativeObservationBatch.Statistics(2, 0, 1), batch.statistics())
        }
    }

    @Test fun `unknown world class uses ordinary getters once with exactly the ordinary trace`() = withWorld {
        actor(60f); val unknown = actor(80f, callback = true, owner = other) as CallbackTank
        val projection = native(); val trace = arrayListOf<String>(); unknown.onRead = { trace.add(it) }
        val expected = projection.observe(); val original = trace.toList(); trace.clear()
        batch().use { batch ->
            compare(expected, projection.observeInBatch(batch)); assertEquals(original, trace)
            assertEquals(NativeObservationBatch.Statistics(0, 0, 1), batch.statistics())
        }
    }

    @Test fun `pending remove equals executes only at the old flush and invalidates earlier facts`() = withWorld {
        val scout = actor(60f); val target = actor(80f, owner = other)
        val first = native(); val next = native()
        val world = BaseUnit.getGlobalUnitList()
        fun queueRemoval(trace: MutableList<Any?>) = world.b(object {
            override fun equals(other: Any?): Boolean { trace.add(other); target.posX = 280f; return false }
            override fun hashCode(): Int = 0
        })
        val ordinaryTrace = arrayListOf<Any?>()
        queueRemoval(ordinaryTrace)
        assertTrue(ordinaryTrace.isEmpty(), "Queueing cannot run equals")
        val expected = next.observe()
        assertEquals(2, ordinaryTrace.size)
        assertSame(scout, ordinaryTrace[0]); assertSame(target, ordinaryTrace[1])
        target.posX = 80f
        batch().use { batch ->
            first.observeInBatch(batch)
            val groupedTrace = arrayListOf<Any?>(); queueRemoval(groupedTrace)
            assertTrue(groupedTrace.isEmpty(), "The qualification probe cannot run equals")
            val actual = next.observeInBatch(batch)
            assertEquals(ordinaryTrace.size, groupedTrace.size)
            ordinaryTrace.indices.forEach { assertSame(ordinaryTrace[it], groupedTrace[it], "Original remove visit order") }
            assertFalse(actual.units.any { it.ownerTeam == 1 }); compare(expected, actual)
            assertEquals(NativeObservationBatch.Statistics(1, 0, 1), batch.statistics())
        }
    }

    @Test fun `malformed native fields fall back before moving a nullable getter ahead of an original exclusion`() = withWorld {
        actor(60f); val dead = actor(80f, owner = other).apply { isDead = true; y = null }
        val projection = native(); val expected = projection.observe()
        batch().use { batch ->
            compare(expected, projection.observeInBatch(batch))
            assertEquals(NativeObservationBatch.Statistics(0, 0, 1), batch.statistics())
        }
        dead.y = config.unitStats
    }

    @Test fun `generic arbitrary status cannot borrow native source identity or skip original calls`() = withWorld {
        actor(60f); val generic = timeline(); val expected = generic.observe(); val reads = statusReads
        batch().use { batch ->
            compare(expected, generic.observeInBatch(batch)); assertEquals(reads + 1, statusReads)
            assertEquals(NativeObservationBatch.Statistics(0, 0, 1), batch.statistics())
        }
    }

    @Test fun `old held team subclass cannot restore its registry slot and mutate world under cached native facts`() = withWorld {
        actor(60f)
        val held = CallbackTeam(2).apply {
            teamColorId = 2; credits = 1000.0; fogOfWarData = Array(16) { ByteArray(16) { 10 } }
        }
        val projection = EngineTimelineProjector.forNativeRoom(engine, held, map, stock, closure, roster)
        val replacement = GameTeam(2).apply {
            teamColorId = 2; credits = 1000.0; fogOfWarData = Array(16) { ByteArray(16) { 10 } }
        }
        val target = actor(80f, owner = replacement)
        val field = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val slots = field.get(null) as Array<PlayerTeam?>
        val trace = arrayListOf<String>()
        held.onSpectatorRead = {
            trace.add("held.spectator:registered=${PlayerTeam.k(2) === held}:owner=${target.team === held}:x=${target.posX.toRawBits()}")
            // The old ready predicate invokes this before testing registry identity.
            slots[2] = held; target.team = held; target.posX = 140f
        }
        assertSame(replacement, PlayerTeam.k(2))
        val expected = projection.observe()
        val ordinaryTrace = trace.toList(); assertTrue(ordinaryTrace.isNotEmpty())
        assertSame(held, PlayerTeam.k(2))
        assertTrue(expected.units.any { it.ownerTeam == 2 && it.controllable && it.position.x == 140L * 256 })
        // Recreate exactly the deceptive all-native registry/world seen by the first grant.
        slots[2] = replacement; target.team = replacement; target.posX = 80f; trace.clear()
        val first = native()
        batch().use { batch ->
            first.observeInBatch(batch)
            assertTrue(trace.isEmpty(), "The first native grant never invokes the unregistered held team")
            assertEquals(NativeObservationBatch.Statistics(1, 0, 0), batch.statistics())
            val actual = projection.observeInBatch(batch)
            assertSame(held, PlayerTeam.k(2)); assertSame(held, target.team)
            assertEquals(ordinaryTrace, trace, "Fallback retains each original getter invocation in order")
            compare(expected, actual, 2)
            assertEquals(NativeObservationBatch.Statistics(1, 0, 1), batch.statistics())
        }
    }

    @Test fun `unknown registry backing invalidates facts before ordinary ready validation without speculative collection reads`() = withWorld {
        actor(60f); val first = native(); val next = native()
        batch().use { batch ->
            first.observeInBatch(batch)
            var reads = 0
            val original = CustomUnitConfig.activeConfigs
            CustomUnitConfig.activeConfigs = object : ArrayList<CustomUnitConfig>(original) {
                override val size: Int get() { reads++; return super.size }
                override fun get(index: Int): CustomUnitConfig { reads++; return super.get(index) }
            }
            try {
                assertFailsWith<IllegalStateException> { next.observeInBatch(batch) }
                assertEquals(0, reads, "The old registry identity check rejects before reading this replacement")
                assertEquals(NativeObservationBatch.Statistics(1, 0, 1), batch.statistics())
            } finally { CustomUnitConfig.activeConfigs = original }
        }
    }

    @Test fun `terminal outcome after an explicit mutation boundary keeps the original frozen authorized result`() = withWorld {
        actor(60f); actor(80f, owner = other)
        val projection = native(); val initial = projection.observe()
        batch().use { batch ->
            compare(initial, projection.observeInBatch(batch))
            batch.fallback() // The Room never carries a group across an engine/command mutation.
            own.isTeamDefeated = true
            val terminal = projection.observeInBatch(batch)
            assertTrue(terminal.units.isEmpty()); compare(projection.observe(), terminal)
            assertEquals(NativeObservationBatch.Statistics(1, 0, 1), batch.statistics())
        }
    }

    @Test fun `ordinary empty world flush still invalidates iterators and batch remains confined to owner`() = withWorld {
        actor(60f); val projection = native(); val iterator = BaseUnit.getGlobalUnitList().iterator()
        batch().use { batch ->
            projection.observeInBatch(batch)
            assertFailsWith<java.util.ConcurrentModificationException> { iterator.next() }
            val failure = AtomicReference<Throwable?>()
            thread { failure.set(runCatching { batch.statistics() }.exceptionOrNull()) }.join()
            assertIs<IllegalStateException>(failure.get())
        }
    }

    @Test fun `private visible-cell enumeration is exact and unique across refog overlaps and partial falloff`() {
        val vision = OwnedSeatVision(); vision.configure(37, 29, false)
        repeat(4) { phase ->
            vision.beginFrame(); repeat(10) { vision.reveal(7.25f + it, 12.5f, 8 + phase) }
            val actual = arrayListOf<Pair<Int, Int>>()
            vision.grid().forEachVisibleCell { x, y -> actual.add(x to y) }
            val expected = buildSet { for (x in 0 until 37) for (y in 0 until 29) if (vision.grid().valueAt(x, y) < 5) add(x to y) }
            assertEquals(expected, actual.toSet()); assertEquals(expected.size, actual.size)
        }
        vision.beginFrame(); val actual = arrayListOf<Pair<Int, Int>>()
        vision.grid().forEachVisibleCell { x, y -> actual.add(x to y) }; assertTrue(actual.isEmpty())
    }
    @Test fun `sparse actual visibility uses buckets and dense visibility uses rows with the same complete state`() {
        for (radius in listOf(2, 15)) withWorld {
            val scout = actor(60f)
            scout.y = com.corrodinggames.rts.game.units.custom.UnitStats(false).apply {
                fogOfWarSightRange = radius; isVisibleToEnemies = true
            }
            repeat(120) { index -> actor(180f + index % 6 * 20f, owner = other).posY = 60f + index / 6 % 12 * 20f }
            val first = native(); val next = native(); val expectedA = first.observe(); val expectedB = next.observe()
            val visible = expectedA.fog.toByteArray().count { it < 5 }
            if (radius == 2) assertTrue(visible < 121) else assertTrue(visible >= 121)
            batch().use { batch ->
                compare(expectedA, first.observeInBatch(batch)); compare(expectedB, next.observeInBatch(batch))
                assertEquals(NativeObservationBatch.Statistics(1, 1, 0), batch.statistics())
            }
        }
    }

    @Test fun `visible count follows exact current private cells and returns to zero on refog`() {
        val vision = OwnedSeatVision(); vision.configure(31, 23, false)
        assertEquals(0, vision.grid().visibleCellCount)
        repeat(3) { phase ->
            vision.beginFrame(); repeat(10) { vision.reveal(it + .5f, 10.25f, phase + 5) }
            val bytes = vision.grid().copyBytes()
            assertEquals(bytes.count { it < 5 }, vision.grid().visibleCellCount)
        }
        vision.beginFrame(); assertEquals(0, vision.grid().visibleCellCount)
    }

}
