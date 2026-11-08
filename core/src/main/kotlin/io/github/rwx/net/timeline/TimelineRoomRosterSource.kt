package io.github.rwx.net.timeline

import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import io.github.rwx.net.authoritative.EngineMatchStatus
import io.github.rwx.net.authoritative.MatchStatus

/**
 * Constructor provenance for a room's roster/status source. This does not enable
 * grouped capture and is not a world-purity or grant-authorization capability.
 */
internal sealed class TimelineRoomRosterSource {
    private val owner = Thread.currentThread()

    /** Provenance only, with no invocation of the participant/status source. */
    internal val hasNativeProvenance: Boolean get() = this is NativeSlots

    abstract fun participantIds(): Collection<Int>

    // Keep callback invocation, Collection traversal, native getters and exceptions
    // in exactly the pre-existing order. Eligibility never retries this operation.
    fun current(): MatchStatus = EngineMatchStatus.current(participantIds())

    interface Candidate {
        val participantIds: List<Int>
        val status: MatchStatus
        /** Current fields only: this cannot detect an unobserved A -> B -> A change. */
        fun stillCurrent(): Boolean
        /** Mandatory before crossing ANY callback or native-world mutation boundary. */
        fun invalidate()
    }

    fun candidateOrNull(): Candidate? {
        check(Thread.currentThread() === owner) { "Room roster eligibility requires its owner" }
        // An arbitrary callback is never inspected, executed speculatively or treated
        // as pure because it previously returned the same values.
        if (this !is NativeSlots || PlayerTeam.TEAM_ENEMIES < 64) return null
        val teams = arrayOfNulls<GameTeam>(64)
        // Class/slot checks precede every overridable team getter and Collection call.
        for (id in 0..63) {
            val team = PlayerTeam.k(id) ?: continue
            if (team.javaClass !== GameTeam::class.java || team.teamId != id) return null
            teams[id] = team as GameTeam
        }
        val colors = IntArray(64)
        val states = IntArray(64)
        val ids = ArrayList<Int>()
        for (id in 0..63) teams[id]?.let { team ->
            colors[id] = team.teamColorId
            states[id] = stateBits(team)
            if (team.teamColorId != -3) ids.add(id)
        }
        if (ids.isEmpty()) return null
        val immutableIds = frozenList(ids)
        // All team implementations are exact GameTeam and all collections below are
        // owned. The actual outcome calculation remains the existing one.
        val outcome = EngineMatchStatus.current(immutableIds)
        return NativeCandidate(owner, teams, colors, states, immutableIds, outcome.copy(
            winnerTeamIds = frozenList(outcome.winnerTeamIds), defeatedTeamIds = frozenList(outcome.defeatedTeamIds)))
    }

    private class Legacy(private val callback: () -> Collection<Int>) : TimelineRoomRosterSource() {
        override fun participantIds(): Collection<Int> = callback()
    }

    private class NativeSlots : TimelineRoomRosterSource() {
        // This is intentionally the former desktop native-roster expression. If a
        // team is unknown, normal status still invokes its getter once at each old
        // call site; candidateOrNull adds no invocation and catches no exception.
        override fun participantIds(): Collection<Int> = (0..63).filter { PlayerTeam.k(it)?.isSpectatorTeamColor == false }
    }

    private class NativeCandidate(
        private val owner: Thread,
        private val teams: Array<GameTeam?>,
        private val colors: IntArray,
        private val states: IntArray,
        override val participantIds: List<Int>,
        override val status: MatchStatus,
    ) : Candidate {
        private var invalidated = false
        private fun checkOwner() = check(Thread.currentThread() === owner) { "Room roster candidate requires its owner" }

        override fun stillCurrent(): Boolean {
            checkOwner()
            if (invalidated) return false
            if (PlayerTeam.TEAM_ENEMIES < 64) return reject()
            for (id in 0..63) {
                val current = PlayerTeam.k(id)
                val original = teams[id]
                if (current !== original) return reject()
                if (original != null && (original.teamId != id || original.teamColorId != colors[id] || stateBits(original) != states[id]))
                    return reject()
            }
            return true
        }

        override fun invalidate() { checkOwner(); invalidated = true }
        private fun reject(): Boolean { invalidated = true; return false }
    }

    companion object {
        fun legacy(callback: () -> Collection<Int>): TimelineRoomRosterSource = Legacy(callback)
        fun nativeSlots(): TimelineRoomRosterSource = NativeSlots()

        private fun stateBits(team: GameTeam): Int =
            (if (team.isTeamWipedOut) 1 else 0) or (if (team.isTeamDefeatedTech) 2 else 0) or
                (if (team.isTeamDefeated) 4 else 0) or (if (team.isTeamVictory) 8 else 0) or
                (if (team.isTeamAlliedVictory) 16 else 0)
    }
}
