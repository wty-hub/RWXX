package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.buildings.CommandCenter
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.utility.TransactionalArrayList
import io.github.rwx.net.timeline.TimelineRoomRosterSource
import java.util.BitSet
import java.util.IdentityHashMap

/** One synchronous Room post-advance pass; no native references may leave its owner. */
internal class NativeObservationBatch(
    private val engine: GameEngine,
    private val stock: StockUnitCatalog,
    private val actions: StockActionClosure,
    private val roster: TimelineRoomRosterSource,
) : AutoCloseable {
    data class Statistics(val captures: Long, val reusedPasses: Long, val fallbackPasses: Long)
    private val owner = Thread.currentThread()
    private var facts: NativeObservationFacts? = null
    private var grantOpen = false
    private var eligible = false
    private var qualified = false
    private var fallbackCounted = false
    private var closed = false
    private var captures = 0L
    private var reused = 0L
    private var fallbacks = 0L

    fun belongsTo(source: TimelineRoomRosterSource?): Boolean = source === roster
    fun isEligible(): Boolean { checkOwner(); return eligible }

    fun beginGrant(selectionSupported: Boolean) {
        checkOwner(); check(!closed && !grantOpen)
        grantOpen = true; eligible = selectionSupported; qualified = false; fallbackCounted = false
        if (!selectionSupported) fallback()
    }

    /** Runs BEFORE the old ready/assert/status calls, without executing an unknown receiver. */
    fun beforeReady() {
        checkOwner()
        if (!eligible) return
        if (GameEngine.getInstance() !== engine || !stock.hasKnownValidationBackings() ||
            !actions.hasKnownValidatorBackings() || roster.candidateOrNull() == null) fallback()
    }

    /** The old flush still runs immediately afterwards, including its modCount increment. */
    fun beforeFlush() {
        checkOwner()
        if (!BaseUnit.authoritativeWorldHasNoPendingOperations()) fallback()
    }

    fun hasKnownWorld(world: List<BaseUnit>): Boolean {
        checkOwner()
        return eligible && facts?.matchesWorld(world) == true
    }

    fun forFog(world: List<BaseUnit>, map: TileMap, lookup: StockUnitCatalog.NativeSightLookup?,
               projection: EngineViewProjector, selected: Long?): NativeObservationFacts? {
        checkOwner()
        if (!eligible || lookup == null) { fallback(); return null }
        // Includes an unmaterialized single selection and every old Catalog root. It does not
        // revoke, flush, run status, or invoke an unknown unit/action/container getter.
        val local = projection.nativeSyntheticTankObservationDependenciesOrNull(selected, lookup)
        if (local == null) { fallback(); return null }
        try {
            if (!local.stillCurrent()) { fallback(); return null }
            val old = facts
            if (old != null && old.matchesWorld(world)) {
                qualified = true; reused++
                return old
            }
            val captured = NativeObservationFacts.capture(world, map, lookup)
            if (captured == null) { fallback(); return null }
            facts = captured; captures++; qualified = true
            return captured
        } finally { local.close() }
    }

    fun forDisclosure(world: List<BaseUnit>): NativeObservationFacts? {
        checkOwner()
        return facts?.takeIf { eligible && qualified && it.matchesWorld(world) }
    }

    fun finishGrant(success: Boolean) {
        checkOwner(); check(grantOpen)
        if (!success || !qualified) fallback()
        grantOpen = false; eligible = false; qualified = false
    }

    fun fallback() {
        checkOwner()
        facts = null; eligible = false; qualified = false
        if (grantOpen && !fallbackCounted) { fallbacks++; fallbackCounted = true }
    }

    fun statistics(): Statistics { checkOwner(); return Statistics(captures, reused, fallbacks) }
    override fun close() { checkOwner(); facts = null; closed = true; grantOpen = false; eligible = false }
    private fun checkOwner() = check(Thread.currentThread() === owner) { "Native observations require their owner" }
}

/** Frozen native fields, team circles and spatial membership for one proven pure grant group. */
internal class NativeObservationFacts private constructor(
    private val world: List<BaseUnit>,
    private val worldSize: Int,
    private val map: TileMap,
    private val rows: Array<Row>,
    private val own: IdentityHashMap<PlayerTeam, MutableList<Row>>,
    private val heads: IntArray,
    private val next: IntArray,
) {
    private val owner = Thread.currentThread()
    private val candidates = BitSet(rows.size)
    data class Disclosed(val source: BaseUnit, val view: ViewUnit)
    private class Row(val ordinal: Int, val source: BaseUnit, val team: PlayerTeam, val type: String,
        val x: Float, val y: Float, val z: Float, val heading: Float, val health: Float,
        val maxHealth: Float, val progress: Float, val upgrade: Int, val visibleToEnemies: Boolean,
        val canDisclose: Boolean, val canControl: Boolean, val sight: Int, val inMap: Boolean)

    fun matchesWorld(candidate: List<BaseUnit>): Boolean {
        checkOwner()
        // This is an O(1) consistency check inside the closed pure path, not a mutation detector
        // usable across callbacks. The caller invalidates the entire group before such a boundary.
        return candidate === world && candidate.size == worldSize
    }

    fun reveal(team: PlayerTeam, vision: OwnedSeatVision) {
        checkOwner()
        for (row in own[team].orEmpty()) if (row.sight > 0) vision.reveal(
            row.x / map.tileWorldSizeX, row.y / map.tileWorldSizeY, row.sight)
    }

    fun disclose(team: PlayerTeam, fog: OwnedSeatVision.Grid): List<Disclosed> {
        checkOwner()
        val result = ArrayList<Disclosed>()
        if (fog.visibleCellCount >= rows.size) {
            // Dense visibility must not walk more cells than there are actual world rows.
            // The same original-ordinal order and the same actual fog predicate apply.
            for (row in rows) appendDisclosed(row, team, fog, result)
            return result
        }
        candidates.clear()
        fog.forEachVisibleCell { x, y ->
            var index = heads[y * map.tileCountX + x]
            while (index >= 0) { candidates.set(index); index = next[index] }
        }
        for (row in own[team].orEmpty()) candidates.set(row.ordinal)
        var index = candidates.nextSetBit(0)
        while (index >= 0) {
            appendDisclosed(rows[index], team, fog, result)
            index = candidates.nextSetBit(index + 1)
        }
        return result
    }

    private fun appendDisclosed(row: Row, team: PlayerTeam, fog: OwnedSeatVision.Grid,
                                result: MutableList<Disclosed>) {
        if (row.canDisclose && (row.team === team ||
                ((!team.c(row.team) || row.visibleToEnemies) &&
                    fog.valueAt((row.x / map.tileWorldSizeX).toInt(), (row.y / map.tileWorldSizeY).toInt()) < 5))) {
            result.add(Disclosed(row.source, ViewUnit(0, row.type, row.team.teamId,
                row.x, row.y, row.z, row.heading, row.health, row.maxHealth, row.progress,
                row.team === team && row.canControl, row.upgrade)))
        }
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Native facts require their owner" }

    companion object {
        fun capture(world: List<BaseUnit>, map: TileMap, lookup: StockUnitCatalog.NativeSightLookup): NativeObservationFacts? {
            if (world.javaClass !== TransactionalArrayList::class.java || world.size > 65_536) return null
            val seen = IdentityHashMap<BaseUnit, Boolean>()
            // Preflight uses fields/classes only. A malformed native receiver goes back to the
            // original short circuit, which decides if/when its nullable field is dereferenced.
            for (unit in world) {
                if (unit == null || (unit.javaClass !== CustomUnit::class.java &&
                        unit.javaClass !== CommandCenter::class.java && unit.javaClass !== BuilderUnit::class.java) ||
                    seen.put(unit, true) != null || unit.team?.javaClass !== GameTeam::class.java) return null
                if (unit is CustomUnit && (unit.unitConfig == null || unit.y == null ||
                        unit.transportContainer != null || unit.parentEntity != null || unit.attachmentData != null)) return null
            }
            val rows = arrayOfNulls<Row>(world.size)
            val own = IdentityHashMap<PlayerTeam, MutableList<Row>>()
            val heads = IntArray(map.tileCountX * map.tileCountY) { -1 }
            val next = IntArray(world.size) { -1 }
            world.forEachIndexed { index, unit ->
                val type = lookup.keyFor(unit.r()) ?: return null
                val actor = unit as OrderableUnit
                val custom = unit as? CustomUnit
                val x = unit.posX; val y = unit.posY
                val inMap = x.isFinite() && y.isFinite() && x >= 0f && y >= 0f &&
                    x.toDouble() < map.tileCountX.toDouble() * map.tileWorldSizeX &&
                    y.toDouble() < map.tileCountY.toDouble() * map.tileWorldSizeY
                val upgrade = actor.upgradeLevel
                val eligible = unit.team.teamId >= -2 && !unit.isDead && !unit.isDestroyed && !unit.u() && !unit.t() &&
                    unit.transportContainer == null && unit.parentEntity == null && unit.attachmentData == null &&
                    inMap && unit.posZ.isFinite() && unit.rotationSpeed.isFinite() &&
                    unit.currentHealth.isFinite() && unit.currentHealth >= 0f && unit.maxHealth.isFinite() &&
                    unit.maxHealth > 0f && unit.buildProgress.isFinite() && unit.buildProgress in 0f..1f &&
                    upgrade in 1..3 && (custom == null || custom.unitConfig.isVisible == null)
                val sight = if (!unit.isDead && !unit.isDestroyed && unit.transportContainer == null &&
                    unit.parentEntity == null && (custom == null || custom.unitConfig.isVisible == null) && inMap) {
                    if (custom != null && unit.buildProgress < 1f && custom.unitConfig.fogOfWarSightRangeWhileNotBuilt >= 0)
                        custom.unitConfig.fogOfWarSightRangeWhileNotBuilt else actor.s()
                } else 0
                val row = Row(index, unit, unit.team, type, x, y, unit.posZ, unit.rotationSpeed,
                    unit.currentHealth, unit.maxHealth, unit.buildProgress, upgrade, unit.isVisibleToEnemies,
                    eligible, unit.buildProgress >= 1f && !unit.canNotBeGivenOrdersByPlayer() && !unit.u() && !unit.t(), sight, inMap)
                rows[index] = row; own.getOrPut(unit.team) { ArrayList() }.add(row)
                if (eligible) {
                    val cell = (y / map.tileWorldSizeY).toInt() * map.tileCountX + (x / map.tileWorldSizeX).toInt()
                    next[index] = heads[cell]; heads[cell] = index
                }
            }
            @Suppress("UNCHECKED_CAST")
            return NativeObservationFacts(world, world.size, map, rows as Array<Row>, own, heads, next)
        }
    }
}
