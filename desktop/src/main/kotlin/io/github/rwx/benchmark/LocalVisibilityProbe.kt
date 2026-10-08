package io.github.rwx.benchmark

import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Diagnostic owner-only death stimulus; actual unit death / defeat / fog update use native rules. */
internal class LocalVisibilityProbe(private val deathSeconds: Int) {
    internal data class Snapshot(val tick: Int, val atNanos: Long, val living: Int, val wiped: Boolean,
        val observer: Boolean, val fogHash: Int, val hiddenFogCells: Int)
    @Volatile private var snapshots = emptyList<Snapshot>()
    private var nextObservation = 0L
    private var deathRequested = false

    fun snapshotAt(tick: Int): Snapshot? = snapshots.lastOrNull { it.tick <= tick }

    fun observe(engine: GameEngine, now: Long, started: Long, output: File) {
        val team = checkNotNull(engine.playerTeam)
        val shouldRequestDeath = !deathRequested && now - started >= deathSeconds * 1_000_000_000L
        if (now < nextObservation && !shouldRequestDeath && team.isTeamWipedOut == snapshots.lastOrNull()?.wiped) return
        nextObservation = now + 1_000_000_000L
        var living = 0
        val units = BaseUnit.bE.a()
        for (index in 0 until BaseUnit.bE.b) {
            val unit = units[index]
            if (unit.team === team && !unit.isDead && !unit.isDestroyed) living++
        }
        var hash = 1; var hidden = 0
        team.fogOfWarData?.forEach { column -> column?.forEach { value ->
            hash = 31 * hash + value
            if (value.toInt() != 0) hidden++
        } }
        val snapshot = Snapshot(engine.currentTick, now, living, team.isTeamWipedOut, team.isTeamObserver, hash, hidden)
        // Detached owner observations only: the renderer must never inspect live units or fog data.
        snapshots = (snapshots.takeLast(119) + snapshot).toList()
        output.appendText(buildJsonObject {
            put("kind", "player-visibility"); put("tick", snapshot.tick); put("atNanos", now)
            put("livingPlayerUnits", living); put("playerWipedOut", snapshot.wiped)
            put("playerObserver", snapshot.observer); put("fogHash", hash); put("hiddenFogCells", hidden)
            put("diagnosticOnly", true)
        }.toString() + "\n")
        if (shouldRequestDeath) {
            check(living > 0 && !team.isTeamWipedOut) { "Player was already defeated before death diagnostic" }
            for (index in 0 until BaseUnit.bE.b) {
                val unit = units[index]
                if (unit.team === team && !unit.isDead && !unit.isDestroyed) unit.markForDeath()
            }
            deathRequested = true
            output.appendText(buildJsonObject {
                put("kind", "player-death-request"); put("tick", engine.currentTick); put("atNanos", now)
                put("markedUnits", living); put("diagnosticOnly", true)
                put("rules", "Set health through native markForDeath; ordinary update performs death, defeat and fog invalidation")
            }.toString() + "\n")
        }
    }
}
