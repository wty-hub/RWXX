package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.land.TankUnit
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.graphics.Texture
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.timeline.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class EngineMatchLifecycleTest {
    private class TestTank : TankUnit(true) {
        override fun a(texture: Texture?, frames: Int) { T(20); U(20) }
    }

    private class Match(val engine: GameLogic, val zero: GameTeam, val one: GameTeam) {
        fun projector(team: GameTeam = zero) = EngineViewProjector(engine, team)
        private val publicMap by lazy { EnginePublicMapSnapshot.capture(engine.tileMap) }
        private val stock by lazy { StockUnitCatalog.capture(engine) }
        private val closure by lazy { StockActionClosure.capture(engine, stock) }
        fun timeline(access: Int, joinedAt: Long = 0) = EngineTimelineTestStream(
            EngineTimelineProjector(engine, zero, publicMap, stock, closure, { EngineMatchStatus.current() }),
            engineTimelineTestContext(engine, publicMap, access, joinedAt))
    }

    private fun withMatch(block: Match.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null)
        GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        val previousTeams = registry.get(null) as Array<*>
        val previousUnits = BaseUnit.getGlobalUnitList().toList()
        val previousConfigs = CustomUnitConfig.activeConfigs
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        CustomUnitConfig.activeConfigs = arrayListOf()
        CustomUnitConfig.unitTypeOverrides.clear()
        registry.set(null, arrayOfNulls<PlayerTeam>(previousTeams.size))
        BaseUnit.getGlobalUnitList().clear()
        try {
            engine.networkEngine = NetworkEngine().apply { roomSettings.fogMode = 2 }
            engine.commandController = CommandController()
            engine.replayEngine = ReplayEngine()
            engine.tileMap = PublicMapFixture.flat(32, 32).apply {
                tileCountX = 32; tileCountY = 32
                tileWorldSizeX = 20; tileWorldSizeY = 20
                tileScaleX = .05f; tileScaleY = .05f
                fogEnabled = true; fogPeriodicMaintenanceEnabled = true
            }
            fun team(id: Int) = GameTeam(id).apply {
                teamColorId = id
                credits = 2000.0
                fogOfWarData = Array(32) { ByteArray(32) { 10 } }
            }
            val zero = team(0)
            val one = team(1)
            engine.playerTeam = zero
            for ((index, owner) in listOf(zero, one).withIndex()) BaseUnit.getGlobalUnitList().add(TestTank().apply {
                team = owner; posX = 5f + index * 10; posY = 5f
            })
            Match(engine, zero, one).block()
        } finally {
            BaseUnit.getGlobalUnitList().clear()
            previousUnits.forEach { BaseUnit.getGlobalUnitList().add(it) }
            CustomUnitConfig.activeConfigs = previousConfigs
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            registry.set(null, previousTeams)
            instance.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    @Test fun `either seat losing ends the two player match independently of the local engine perspective`() {
        val defeatFlags: List<(GameTeam) -> Unit> = listOf(
            { it.isTeamWipedOut = true }, { it.isTeamDefeatedTech = true },
            { it.isTeamDefeated = true }, { it.isTeamVictory = true },
        )
        for (defeat in defeatFlags) for (loserId in 0..1) withMatch {
            engine.playerTeam = if (loserId == 0) one else zero
            defeat(if (loserId == 0) zero else one)
            assertEquals(MatchStatus(MatchPhase.FINISHED, listOf(1 - loserId), listOf(loserId)), EngineMatchStatus.current())
        }
    }

    @Test fun `simultaneous defeat is a draw and the real allied victory flag identifies a winner`() = withMatch {
        assertEquals(MatchStatus(), EngineMatchStatus.current())
        zero.isTeamAlliedVictory = true
        assertEquals(MatchStatus(MatchPhase.FINISHED, listOf(0), listOf(1)), EngineMatchStatus.current())
        zero.isTeamAlliedVictory = false
        zero.isTeamWipedOut = true
        one.isTeamWipedOut = true
        assertEquals(MatchStatus(MatchPhase.FINISHED, emptyList(), listOf(0, 1)), EngineMatchStatus.current())
    }

    @Test fun `terminal views remove entities freeze prior disclosure and permanently reject both seats commands`() = withMatch {
        val winner = projector(zero)
        val loser = projector(one)
        val beforeWinner = winner.snapshot()
        val beforeLoser = loser.snapshot()
        assertEquals(2, beforeWinner.units.size)
        one.isTeamWipedOut = true
        zero.fogOfWarData.forEach { it.fill(0) }
        one.fogOfWarData = null // Legacy terminal mutation must not be consulted for a result view.
        zero.credits = 9000.0
        BaseUnit.getGlobalUnitList().forEach { it.posX = 600f; it.currentHealth = 1f }
        val afterWinner = winner.snapshot()
        val afterLoser = loser.snapshot()
        assertTrue(afterWinner.units.isEmpty())
        assertTrue(afterLoser.units.isEmpty())
        assertEquals(afterWinner.matchStatus, afterLoser.matchStatus)
        assertEquals(listOf(0), afterWinner.matchStatus.winnerTeamIds)
        assertContentEquals(beforeWinner.fog, afterWinner.fog)
        assertContentEquals(beforeLoser.fog, afterLoser.fog)
        assertEquals(beforeWinner.credits, afterWinner.credits)
        assertFalse(winner.apply(ViewUnitOrder(1, beforeWinner.sequence, OrderKind.STOP,
            listOf(beforeWinner.units.single { it.controllable }.handle))))
        assertFalse(loser.apply(ViewUnitOrder(1, beforeLoser.sequence, OrderKind.STOP,
            listOf(beforeLoser.units.single { it.controllable }.handle))))
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        afterWinner.fog.fill(0)
        one.isTeamWipedOut = false
        assertEquals(MatchPhase.FINISHED, winner.matchStatus().phase)
        assertContentEquals(beforeWinner.fog, winner.snapshot().fog)
    }

    @Test fun `new terminal connections receive only public results and no inherited vision`() = withMatch {
        one.isTeamVictory = true // Surrender, despite the legacy name.
        zero.fogOfWarData.forEach { it.fill(0) }
        one.fogOfWarData.forEach { it.fill(0) }
        for (team in listOf(zero, one)) {
            val projector = projector(team)
            val result = projector.snapshot()
            assertEquals(MatchStatus(MatchPhase.FINISHED, listOf(0), listOf(1)), result.matchStatus)
            assertTrue(result.units.isEmpty())
            assertTrue(result.fog.all { it.toInt() == 10 })
            assertFalse(projector.apply(ViewUnitOrder(1, result.sequence, OrderKind.MOVE, listOf(1), CommandTarget.Point(10f, 10f))))
        }
    }

    @Test fun `new grants revoke old private capabilities while v7 terminal history ends at the exclusive fence`() = withMatch {
        val first = timeline(access = 2)
        val firstState = first.capture(0).state
        val second = timeline(access = 3)
        val secondInitial = second.capture(0)
        val secondState = secondInitial.state
        val controlled = secondState.objects.values.single { it.kind == TimelineObjectKind.UNIT &&
            TimelineProfile.sample(secondState, it.handle, 9, 0) == TimelineValue.Bool(true) }.handle
        assertNotEquals(first.context.accessId, second.context.accessId)
        assertNotEquals(first.publisher.unitKey(controlled), second.publisher.unitKey(controlled),
            "A fresh grant must not reuse engine authorization handles")
        val stop = stopRequest(secondState, controlled)
        assertFalse(second.projection.apply(stop, first.publisher::unitKey, first.publisher::actionKey),
            "A preceding grant's private capability cannot control the replacement grant")
        assertFalse(second.projection.apply(stop.copy(teamId = 1), second.publisher::unitKey, second.publisher::actionKey))
        assertTrue(second.projection.apply(stop, second.publisher::unitKey, second.publisher::actionKey))
        assertEquals(1, engine.commandController.pendingCommands.size)
        engine.commandController.pendingCommands.clear()

        engine.currentTick = 6
        val finishedAt = engine.currentTick.toLong() * TimelineProtocol.SUBDIVISIONS
        one.isTeamWipedOut = true
        zero.fogOfWarData.forEach { it.fill(0) }
        one.fogOfWarData = null
        zero.credits = 99_999.0
        BaseUnit.getGlobalUnitList().forEach { it.posX = 600f; it.currentHealth = 1f }
        val terminal = second.capture(100_000_000L)
        assertEquals(finishedAt, terminal.state.header.observedThrough)
        assertTrue(terminal.state.header.controlEpoch > secondState.header.controlEpoch)
        val historyHandles = secondState.objects.values.filter { it.kind == TimelineObjectKind.UNIT }.map { it.handle }
        assertEquals(2, historyHandles.size)
        for (handle in historyHandles) {
            assertEquals(TimelineClose(finishedAt, TimelineCloseKind.ENDED), terminal.state.objects[handle]!!.close)
            assertEquals(TimelineProfile.sample(secondState, handle, 1, 0), TimelineProfile.sample(terminal.state, handle, 1, 0))
            assertNull(TimelineProfile.sample(terminal.state, handle, 1, finishedAt))
        }
        val fenceObservation = second.projection.finalObservationFence()
        assertEquals(finishedAt + 1, fenceObservation.time)
        // A fence changes only O/S; production's idle flush waits for the metadata heartbeat.
        val fence = second.publish(fenceObservation, 1_100_000_000L)
        assertEquals(finishedAt + 1, fence.state.header.observedThrough)
        assertEquals(finishedAt + 1, fence.state.header.sealedThrough)
        val status = assertIs<TimelineValue.Blob>(TimelineProfile.sample(fence.state, 0, 2, finishedAt)).value
        val cursor = TimelineBinary.Cursor(status)
        assertEquals(1, cursor.u8())
        assertEquals(finishedAt, cursor.vuint())
        assertEquals(1L, cursor.vuint()); assertEquals(0L, cursor.vuint())
        assertEquals(1L, cursor.vuint()); assertEquals(1L, cursor.vuint()); cursor.finish()
        for (page in 0 until 4) assertContentEquals(TimelineProfile.fogPage(secondState, page, 0),
            TimelineProfile.fogPage(fence.state, page, finishedAt))
        assertFalse(second.projection.apply(stopRequest(fence.state, controlled),
            second.publisher::unitKey, second.publisher::actionKey))
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        assertFailsWith<IllegalStateException> { second.projection.finalObservationFence() }
        assertFalse(firstState.context.accessId == fence.state.context.accessId)

        // Rejoining after the match creates a new grant, not access to a preceding grant's history.
        val afterMatch = timeline(access = 4, joinedAt = finishedAt)
        val fresh = afterMatch.capture(300_000_000L)
        assertTrue(fresh.state.objects.values.none { it.kind == TimelineObjectKind.UNIT })
        assertNull(TimelineProfile.sample(fresh.state, controlled, 1, 0))
        for (page in 0 until 4) assertTrue(TimelineProfile.fogPage(fresh.state, page, finishedAt).all { it.toInt() == 10 })
        assertEquals(status, assertIs<TimelineValue.Blob>(TimelineProfile.sample(fresh.state, 0, 2, finishedAt)).value)
        val freshFence = afterMatch.publish(afterMatch.projection.finalObservationFence(), 1_300_000_000L)
        assertEquals(finishedAt + 1, freshFence.state.header.sealedThrough)
        assertTrue(freshFence.state.objects.values.none { it.kind == TimelineObjectKind.UNIT })
        assertFalse(afterMatch.projection.apply(stopRequest(freshFence.state, controlled),
            second.publisher::unitKey, second.publisher::actionKey))
        assertEquals(16, TimelineCanonical.digest(freshFence.state).size)
    }

    private fun stopRequest(state: TimelineState, handle: Long): TimelineRequest {
        val h = state.header
        val selection = ByteArrayOutputStream().also {
            TimelineBinary.writeVUInt(it, 1); TimelineBinary.writeVUInt(it, handle)
        }
        val frame = timelineFrame(5, tn(1, h.streamId, true), tn(2, h.epoch), tn(3, 1),
            tn(4, h.revision), tn(5, h.controlEpoch), tn(6, 3, true), tb(7, TimelineBytes(selection.toByteArray())),
            tn(8, 0), TimelineMessages.recordField(9, TimelineRecord(listOf(tn(1, 0, true)))), tn(10, 0, true))
        TimelineMessages.validate(frame, fromClient = true)
        val bytes = ByteArrayOutputStream().also { TimelineBinary.writeFrame(it, frame) }.toByteArray()
        val decoded = TimelineBinary.readFrame(ByteArrayInputStream(bytes))
        TimelineMessages.validate(decoded, fromClient = true)
        return TimelineRequest(0, 2, decoded, TimelineReceipt(h.epoch, h.revision, h.controlEpoch,
            h.observedThrough, 0, longArrayOf(handle), longArrayOf()))
    }
}
