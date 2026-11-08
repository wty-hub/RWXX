package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.timeline.*
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.*

/** Exercises the real native-world -> detached view -> observation boundary, not a private quantizer. */
class EngineTimelineHeadingSlotTest {
    private class CallbackBuilder : BuilderUnit(true) {
        var onActions: (() -> Unit)? = null
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> {
            onActions?.invoke()
            return arrayListOf()
        }
    }

    private class World(val engine: GameLogic, val own: GameTeam, val units: List<BuilderUnit>,
                        val bridge: EngineTimelineProjector, val publicMap: EnginePublicMapSnapshot)

    private fun withWorld(callback: Boolean = false, block: World.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val oldEngine = instance.get(null)
        val oldPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null)
        GameEngine.isNonAndroidVersion = true
        // Native custom statics require an engine before they are first touched.
        val engine = GameLogic()
        val oldTeams = registry.get(null) as Array<*>
        val oldUnits = BaseUnit.getGlobalUnitList().toList()
        val oldConfigs = CustomUnitConfig.activeConfigs
        val oldOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        BaseUnit.getGlobalUnitList().clear()
        CustomUnitConfig.activeConfigs = arrayListOf()
        CustomUnitConfig.unitTypeOverrides.clear()
        registry.set(null, arrayOfNulls<PlayerTeam>(oldTeams.size))
        try {
            engine.networkEngine = NetworkEngine()
            engine.networkEngine.roomSettings.fogMode = 2
            engine.networkEngine.roomSettings.revealedMap = false
            engine.commandController = CommandController()
            engine.replayEngine = ReplayEngine()
            engine.tileMap = PublicMapFixture.create()
            val own = GameTeam(0).apply {
                teamColorId = 0; credits = 1234.0
                fogOfWarData = Array(4) { ByteArray(3) { 10 } }
            }
            GameTeam(1).apply { teamColorId = 1 }
            engine.playerTeam = own
            val units = List(24) { index ->
                (if (callback && index == 0) CallbackBuilder() else BuilderUnit(true)).apply {
                    team = own; posX = 5f + index * 2; posY = 10f
                    rotationSpeed = 17.125f
                    BaseUnit.getGlobalUnitList().add(this)
                }
            }
            val map = EnginePublicMapSnapshot.capture(engine.tileMap)
            val stock = StockUnitCatalog.capture(engine)
            val closure = StockActionClosure.capture(engine, stock)
            val bridge = EngineTimelineProjector(engine, own, map, stock, closure, { MatchStatus() })
            engine.currentTick = 1
            World(engine, own, units, bridge, map).block()
        } finally {
            BaseUnit.getGlobalUnitList().clear()
            oldUnits.forEach { BaseUnit.getGlobalUnitList().add(it) }
            CustomUnitConfig.activeConfigs = oldConfigs
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides.putAll(oldOverrides)
            registry.set(null, oldTeams)
            instance.set(null, oldEngine)
            GameEngine.isNonAndroidVersion = oldPlatform
        }
    }

    private val storedView = EngineTimelineProjector::class.java.getDeclaredField("view").apply { isAccessible = true }
    private fun current(bridge: EngineTimelineProjector): PlayerViewSnapshot = storedView.get(bridge) as PlayerViewSnapshot
    private fun oracle(heading: Float): Long {
        val normalized = (heading.toDouble() % 360 + 360) % 360
        require(normalized.isFinite())
        return BigDecimal.valueOf(normalized).multiply(BigDecimal.valueOf(65536.0 / 360))
            .setScale(0, RoundingMode.HALF_UP).longValueExact() % 65536
    }
    private fun verify(bridge: EngineTimelineProjector, observation: TimelineObservation) {
        val expected = current(bridge).units.associate { it.handle to oracle(it.heading) }
        assertEquals(expected, observation.units.associate { it.key to it.headingQ })
    }
    private fun expectedObservation(bridge: EngineTimelineProjector, observation: TimelineObservation): TimelineObservation {
        val headings = current(bridge).units.associate { it.handle to oracle(it.heading) }
        return TimelineObservation(observation.time, observation.creditsQ, observation.matchStatus, observation.fog,
            observation.units.map { it.copy(headingQ = headings.getValue(it.key)) }, observation.selectedUnitKey,
            observation.actions, observation.events, observation.selectedUnitKeys)
    }

    @Test fun `real observation preserves repeated alternating boundary and random finite heading decimals`() = withWorld {
        val values = arrayListOf(0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, 360f, -360f,
            Math.nextDown(360f), Math.nextUp(360f), Float.MAX_VALUE, -Float.MAX_VALUE,
            16777216f, -16777216f, 1e20f, -1e20f)
        for (step in listOf(0, 1, 32767, 65535)) {
            val half = ((step + .5) * 360 / 65536).toFloat()
            values += listOf(Math.nextDown(half), half, Math.nextUp(half), -half)
        }
        val random = Random(0x71_65536)
        while (values.size < 64) {
            val next = Float.fromBits(random.nextInt())
            if (next.isFinite()) values += next
        }
        var preceding: TimelineObservation? = null
        var oldHeadings: List<Long>? = null
        values.forEachIndexed { round, heading ->
            units.forEachIndexed { index, unit ->
                unit.rotationSpeed = when (round % 3) {
                    0 -> heading // Hits after the first detached unit.
                    1 -> if (index % 2 == 0) heading else -heading // Alternating misses, including +/-0.
                    else -> if (index / 3 % 2 == 0) heading else 91.875f // Short repeated runs.
                }
            }
            // Intentionally keep the same tick: no cache may persist across observation calls.
            val observed = bridge.observe()
            assertEquals(units.size, observed.units.size)
            verify(bridge, observed)
            preceding?.let { assertEquals(oldHeadings, it.units.map { unit -> unit.headingQ }) }
            preceding = observed
            oldHeadings = observed.units.map { it.headingQ }
        }
    }

    @Test fun `panel callbacks cannot mutate detached headings and the next same tick capture sees native changes`() = withWorld(callback = true) {
        val initial = bridge.observe()
        val selected = current(bridge).units.single { it.x == units.first().posX }.handle
        val packedSelection = ByteArrayOutputStream().also {
            TimelineBinary.writeVUInt(it, 1); TimelineBinary.writeVUInt(it, selected)
        }
        val frame = timelineFrame(6, tn(1, 1, true), tn(2, 1), tn(3, 1), tn(4, 1),
            tb(5, TimelineBytes(packedSelection.toByteArray())))
        TimelineMessages.validate(frame, fromClient = true)
        val receipt = TimelineReceipt(1, 1, 1, initial.time, 0, longArrayOf(selected), longArrayOf())
        assertTrue(bridge.apply(TimelineRequest(0, 1, frame, receipt), { if (it == selected) selected else null }, { null }))
        var callbacks = 0
        val actor = units.first() as CallbackBuilder
        actor.onActions = { callbacks++; units.forEach { it.rotationSpeed = -12345.625f } }
        val captured = bridge.observe()
        assertTrue(callbacks > 0, "The real selected actor action API must execute")
        assertTrue(units.all { it.rotationSpeed == -12345.625f })
        assertTrue(current(bridge).units.all { it.heading.toRawBits() == 17.125f.toRawBits() })
        verify(bridge, captured)
        assertEquals(setOf(oracle(17.125f)), captured.units.map { it.headingQ }.toSet())
        actor.onActions = null
        val next = bridge.observe()
        verify(bridge, next)
        assertEquals(setOf(oracle(-12345.625f)), next.units.map { it.headingQ }.toSet())
        assertEquals(initial.units.map { it.headingQ }, captured.units.map { it.headingQ })
    }

    @Test fun `native invalid headings stay undisclosed and cannot reuse a neighboring valid cached value`() = withWorld {
        val first = bridge.observe()
        assertEquals(24, first.units.size)
        for (bad in floatArrayOf(Float.NaN, Float.fromBits(0xffc00001.toInt()), Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            units[0].rotationSpeed = bad
            units[12].rotationSpeed = bad
            val next = bridge.observe()
            assertEquals(22, next.units.size)
            verify(bridge, next)
            assertTrue(current(bridge).units.none { it.x == units[0].posX || it.x == units[12].posX })
        }
        units[0].rotationSpeed = -0f; units[12].rotationSpeed = 0f
        val recovered = bridge.observe()
        assertEquals(24, recovered.units.size)
        verify(bridge, recovered)
        assertEquals(24, first.units.size, "Previously detached observations remain intact")
    }

    @Test fun `real observe headings match decimal oracle through complete v7 frames and full canonical checks`() = withWorld {
        val context = engineTimelineTestContext(engine, publicMap)
        val actual = EngineTimelineTestStream(bridge, context)
        val expected = EngineTimelineTestStream(bridge, context)
        val headings = floatArrayOf(0f, -0f, 17.125f, -12345.625f, Math.nextDown(360f),
            Math.nextUp(360f), Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE,
            (32767.5 * 360 / 65536).toFloat(), -720.5f)
        headings.forEachIndexed { round, heading ->
            units.forEachIndexed { index, unit -> unit.rotationSpeed =
                if (round % 2 == 0 || index / 4 % 2 == 0) heading else 91.875f }
            own.credits += 1 // Ensure a real authorized change even for equal normalized +/-0.
            engine.currentTick = round + 1
            val observation = bridge.observe()
            verify(bridge, observation)
            val a = actual.publish(observation, round * 1_000_000_000L)
            val b = expected.publish(expectedObservation(bridge, observation), round * 1_000_000_000L)
            TimelineProfile.validate(a.state)
            TimelineProfile.validate(b.state)
            assertContentEquals(b.wire, a.wire, "V7 frames $round")
            assertContentEquals(b.canonical, a.canonical, "All retained canonical fields $round")
            assertEquals(TimelineCanonical.digest(b.state), TimelineCanonical.digest(a.state), "Full digest $round")
        }
    }
}
