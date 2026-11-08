package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.PlayerTeam

enum class MatchPhase { RUNNING, FINISHED }

/** Public match outcome, calculated by the authority rather than supplied by a client. */
data class MatchStatus(
    val phase: MatchPhase = MatchPhase.RUNNING,
    val winnerTeamIds: List<Int> = emptyList(),
    val defeatedTeamIds: List<Int> = emptyList(),
)

/** Read-only outcome for the room's admitted human seats. */
object EngineMatchStatus {
    fun current(seatIds: Collection<Int> = listOf(0, 1)): MatchStatus {
        require(seatIds.isNotEmpty() && seatIds.size <= 64 && seatIds.toSet().size == seatIds.size && seatIds.all { it in 0..63 })
        val teams = seatIds.sorted().map { id ->
            checkNotNull(PlayerTeam.k(id)) { "Match has no seat $id" }.also {
                check(!it.isSpectatorTeamColor) { "Match seat $id is a spectator" }
            }
        }
        val defeated = teams.filter(::isDefeated).map { it.teamId }
        if (teams.size > 1 && teams.size - defeated.size <= 1) return MatchStatus(
            MatchPhase.FINISHED, teams.map { it.teamId }.filterNot { it in defeated }, defeated,
        )
        val winners = teams.filter { it.isTeamAlliedVictory }.map { it.teamId }
        return if (winners.isEmpty()) MatchStatus(defeatedTeamIds = defeated) else MatchStatus(
            MatchPhase.FINISHED, winners, teams.map { it.teamId }.filterNot { it in winners },
        )
    }

    private fun isDefeated(team: PlayerTeam): Boolean = team.isTeamWipedOut || team.isTeamDefeatedTech
        || team.isTeamDefeated || team.isTeamVictory // Legacy isTeamVictory actually means surrendered.
}
