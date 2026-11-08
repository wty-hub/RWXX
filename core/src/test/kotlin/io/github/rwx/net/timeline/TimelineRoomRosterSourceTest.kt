package io.github.rwx.net.timeline

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.authoritative.EngineMatchStatus
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TimelineRoomRosterSourceTest {
    private fun withTeams(block: (GameTeam, GameTeam) -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null); GameEngine.isNonAndroidVersion = true
        GameLogic().apply { networkEngine = NetworkEngine() } // Establish the complete color-paint prerequisites.
        val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val previousTeams = registry.get(null)
        val previousNeutral = PlayerTeam.TEAM_NEUTRAL
        val previousEnemies = PlayerTeam.TEAM_ENEMIES
        try {
            PlayerTeam.setMaxTeamId(64, false)
            registry.set(null, arrayOfNulls<PlayerTeam>(PlayerTeam.TEAM_ENEMIES))
            val first = GameTeam(0).apply { teamColorId = 0 }
            val second = GameTeam(1).apply { teamColorId = 1 }
            block(first, second)
        } finally {
            registry.set(null, previousTeams)
            PlayerTeam.TEAM_NEUTRAL = previousNeutral; PlayerTeam.TEAM_ENEMIES = previousEnemies
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    private fun oldNativeStatus() = EngineMatchStatus.current((0..63).filter { PlayerTeam.k(it)?.isSpectatorTeamColor == false })

    @Test fun `native source and candidate preserve old outcomes across every outcome field and roster changes`() = withTeams { first, second ->
        val source = TimelineRoomRosterSource.nativeSlots()
        fun compare() {
            val expected = oldNativeStatus()
            assertEquals(expected, source.current())
            val candidate = assertNotNull(source.candidateOrNull())
            assertEquals(expected, candidate.status)
            assertTrue(candidate.stillCurrent())
        }
        compare()
        first.isTeamWipedOut = true; compare(); first.isTeamWipedOut = false
        first.isTeamDefeatedTech = true; compare(); first.isTeamDefeatedTech = false
        first.isTeamDefeated = true; compare(); first.isTeamDefeated = false
        first.isTeamVictory = true; compare(); first.isTeamVictory = false
        first.isTeamAlliedVictory = true; compare(); first.isTeamAlliedVictory = false
        second.teamColorId = -3; compare(); second.teamColorId = 1
        GameTeam(63).apply { teamColorId = 63 }; compare()
        val candidate = assertNotNull(source.candidateOrNull())
        assertFailsWith<UnsupportedOperationException> { (candidate.participantIds as MutableList<Int>).add(62) }
        assertFailsWith<UnsupportedOperationException> { (candidate.status.winnerTeamIds as MutableList<Int>).add(62) }
        assertFailsWith<UnsupportedOperationException> { (candidate.status.defeatedTeamIds as MutableList<Int>).add(62) }
    }

    @Test fun `arbitrary roster callback and collection methods retain exact order with no speculative invocation`() = withTeams { _, _ ->
        val log = ArrayList<String>()
        val ids = listOf(0, 1)
        val callback = {
            log.add("callback")
            object : java.util.AbstractCollection<Int>() {
                override val size: Int get() { log.add("size"); return ids.size }
                override fun iterator(): MutableIterator<Int> {
                    log.add("iterator")
                    var at = 0
                    return object : MutableIterator<Int> {
                        override fun hasNext(): Boolean { log.add("hasNext"); return at < ids.size }
                        override fun next(): Int { log.add("next"); return ids[at++] }
                        override fun remove(): Unit = throw UnsupportedOperationException()
                    }
                }
            } as Collection<Int>
        }
        val expected = EngineMatchStatus.current(callback())
        val trace = log.toList(); log.clear()
        val source = TimelineRoomRosterSource.legacy(callback)
        assertNull(source.candidateOrNull())
        assertTrue(log.isEmpty())
        assertEquals(expected, source.current())
        assertEquals(trace, log)

        val failure = IllegalStateException("fixture callback failure")
        var calls = 0
        val failing = TimelineRoomRosterSource.legacy { calls++; throw failure }
        assertNull(failing.candidateOrNull()); assertEquals(0, calls)
        assertSame(failure, assertFailsWith<IllegalStateException> { failing.current() })
        assertEquals(1, calls)
    }

    private class CallbackTeam(id: Int) : GameTeam(id) {
        var callback: () -> Unit = {}
        override fun isSpectatorTeamColor(): Boolean { callback(); return super.isSpectatorTeamColor() }
    }

    @Test fun `unknown team getter is untouched by eligibility and fallback preserves side effects and exceptions`() = withTeams { _, second ->
        val source = TimelineRoomRosterSource.nativeSlots()
        val team = CallbackTeam(0).apply { teamColorId = 0 }
        val log = ArrayList<String>()
        team.callback = { log.add("spectator"); second.isTeamDefeated = true }
        assertNull(source.candidateOrNull()); assertTrue(log.isEmpty()); assertFalse(second.isTeamDefeated)
        val expected = oldNativeStatus(); val expectedLog = log.toList()
        log.clear(); second.isTeamDefeated = false
        assertNull(source.candidateOrNull()); assertTrue(log.isEmpty()); assertFalse(second.isTeamDefeated)
        assertEquals(expected, source.current()); assertEquals(expectedLog, log)

        val failure = IllegalStateException("fixture team getter failure")
        team.callback = { log.add("throw"); throw failure }
        log.clear(); assertNull(source.candidateOrNull()); assertTrue(log.isEmpty())
        assertSame(failure, assertFailsWith<IllegalStateException> { source.current() })
        assertEquals(listOf("throw"), log)
    }

    @Test fun `observed identity or field changes revoke candidate while callback boundaries require explicit invalidation`() = withTeams { first, second ->
        val source = TimelineRoomRosterSource.nativeSlots()
        val colors = assertNotNull(source.candidateOrNull())
        first.teamColorId = 2
        assertFalse(colors.stillCurrent())
        first.teamColorId = 0
        assertFalse(colors.stillCurrent(), "A detected mismatch stays revoked")

        val outcomes = assertNotNull(source.candidateOrNull())
        second.isTeamVictory = true; assertFalse(outcomes.stillCurrent())
        second.isTeamVictory = false; assertFalse(outcomes.stillCurrent())

        val identity = assertNotNull(source.candidateOrNull())
        GameTeam(1).apply { teamColorId = 1 }
        assertFalse(identity.stillCurrent())

        val noMutationDetector = assertNotNull(source.candidateOrNull())
        first.isTeamDefeated = true; first.isTeamDefeated = false
        assertTrue(noMutationDetector.stillCurrent(), "Current-field equality cannot detect an unobserved A-B-A transition")
        noMutationDetector.invalidate()
        assertFalse(noMutationDetector.stillCurrent(), "A callback boundary must discard the candidate even if all fields match")
    }

    @Test fun `native eligibility rejects malformed slot and insufficient registry bounds without unknown calls`() = withTeams { first, _ ->
        val source = TimelineRoomRosterSource.nativeSlots()
        val oldId = first.teamId
        first.teamId = 7
        assertNull(source.candidateOrNull())
        first.teamId = oldId
        val candidate = assertNotNull(source.candidateOrNull())
        val capacity = PlayerTeam.TEAM_ENEMIES
        try {
            PlayerTeam.TEAM_ENEMIES = 2
            assertNull(source.candidateOrNull())
            assertFalse(candidate.stillCurrent())
        } finally { PlayerTeam.TEAM_ENEMIES = capacity }
        val current = assertNotNull(source.candidateOrNull())
        val error = AtomicReference<Throwable?>()
        Thread {
            try {
                assertFailsWith<IllegalStateException> { source.candidateOrNull() }
                assertFailsWith<IllegalStateException> { current.stillCurrent() }
                assertFailsWith<IllegalStateException> { current.invalidate() }
            } catch (failure: Throwable) { error.set(failure) }
        }.also { it.isDaemon = true; it.start(); it.join(5000); assertFalse(it.isAlive) }
        error.get()?.let { throw AssertionError("Owner check failed", it) }
    }
}
