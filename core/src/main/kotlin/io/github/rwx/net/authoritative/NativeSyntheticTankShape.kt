package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.AttackMode
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitContainer
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.custom.AuthoritativeContentAccess
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig

/**
 * Narrow, callback-free description of an actual synthetic-only tank panel receiver.
 * Construction requires a live, already-qualified catalog scope. It never accepts a config/name
 * supplied as proof. It is not visibility/control authority or a whole-world purity certificate.
 */
internal class NativeSyntheticTankShape private constructor(
    private val stock: StockUnitCatalog,
    private val lookup: StockUnitCatalog.NativeSightLookup,
    private val actor: CustomUnit,
    private val team: PlayerTeam,
    private val config: CustomUnitConfig,
    private val lists: Array<*>,
    private val container: UnitContainer,
    private val actions: java.util.ArrayList<*>,
    private val tech: Int,
    private val flags: Flags,
) {
    private data class Flags(
        val ignoreMoveOrders: Boolean, val isBuildingUnit: Boolean, val isUnselectable: Boolean,
        val cannotOrder: Boolean, val repairUnits: Boolean, val repairBuildings: Boolean,
        val reclaim: Boolean, val transportCount: Int, val attack: Boolean,
    ) {
        fun matches(config: CustomUnitConfig): Boolean =
            ignoreMoveOrders == config.ignoreMoveOrders && isBuildingUnit == config.isBuildingUnit &&
                isUnselectable == config.isUnselectable && cannotOrder == config.canNotBeGivenOrdersByPlayer &&
                repairUnits == config.canRepairUnits && repairBuildings == config.canRepairBuildings &&
                reclaim == config.canReclaimResources && transportCount == config.maxTransportingUnits &&
                attack == config.canAttack
    }

    fun stillCurrent(): Boolean {
        if (lookup.nativeTankIdentityFor(stock) !== config || !actorShape(actor, team, config) ||
            config.techLevel != tech || !flags.matches(config)) return false
        val currentLists = AuthoritativeContentAccess.actionLists(config)?.identity() as? Array<*> ?: return false
        if (currentLists !== lists || tech !in 1..currentLists.size || currentLists[tech - 1] !== container ||
            container.javaClass !== UnitContainer::class.java) return false
        val currentActions = container.a ?: return false
        return currentActions === actions && currentActions.javaClass === java.util.ArrayList::class.java &&
            currentActions.isEmpty()
    }

    fun matchesOwner(type: UnitType, factory: CustomUnitConfig?, upgrade: Int): Boolean =
        type === config && factory == null && upgrade == tech

    fun matchesSyntheticEntry(entry: StockActionAdapter.Entry): Boolean {
        if (entry.action != null || entry.actionId != null || entry.queueId != null || entry.policy != null ||
            entry.definition != null || entry.closureToken != null || entry.nativeType != null ||
            entry.producedType != null || entry.convertedType != null || entry.temporaryType != null ||
            entry.ownerType !== config || entry.factoryType != null || entry.variant != 1 ||
            entry.producedTypeKey.isNotEmpty()) return false
        val repair = flags.repairUnits || flags.repairBuildings
        val travel = !flags.ignoreMoveOrders && !flags.isBuildingUnit
        val expectedTarget = when (entry.kind) {
            ViewActionKind.REPAIR -> if (repair) ActionTargetMode.VISIBLE_UNIT else return false
            ViewActionKind.RECLAIM -> if (repair || flags.reclaim) ActionTargetMode.VISIBLE_UNIT else return false
            ViewActionKind.LOAD_UP -> if (flags.transportCount > 0) ActionTargetMode.VISIBLE_UNIT else return false
            ViewActionKind.LOAD_INTO, ViewActionKind.GUARD -> if (travel) ActionTargetMode.VISIBLE_UNIT else return false
            ViewActionKind.PATROL -> if (travel) ActionTargetMode.POINT else return false
            ViewActionKind.ATTACK_MODE -> {
                if (!flags.attack || entry.attackMode != AttackMode.onlyInRange &&
                    entry.attackMode != AttackMode.guardArea && entry.attackMode != AttackMode.aggressive &&
                    entry.attackMode != AttackMode.holdFire && entry.attackMode != AttackMode.returnFire) return false
                ActionTargetMode.NONE
            }
            else -> return false
        }
        return entry.targetMode == expectedTarget &&
            (entry.kind == ViewActionKind.ATTACK_MODE || entry.attackMode == null)
    }

    companion object {
        fun capture(stock: StockUnitCatalog, lookup: StockUnitCatalog.NativeSightLookup,
                    candidate: OrderableUnit, team: PlayerTeam): NativeSyntheticTankShape? {
            val config = lookup.nativeTankIdentityFor(stock) ?: return null
            if (candidate.javaClass !== CustomUnit::class.java) return null
            val actor = candidate as CustomUnit
            if (!actorShape(actor, team, config)) return null
            val tech = config.techLevel
            if (tech !in 1..3) return null
            // The bridge reads raw fields. Check the backing before size/get on any List receiver.
            val lists = AuthoritativeContentAccess.actionLists(config)?.identity() as? Array<*> ?: return null
            if (tech !in 1..lists.size) return null
            val container = lists[tech - 1] as? UnitContainer ?: return null
            if (container.javaClass !== UnitContainer::class.java) return null
            val actions = container.a ?: return null
            if (actions.javaClass !== java.util.ArrayList::class.java || !actions.isEmpty()) return null
            val flags = Flags(config.ignoreMoveOrders, config.isBuildingUnit, config.isUnselectable,
                config.canNotBeGivenOrdersByPlayer, config.canRepairUnits, config.canRepairBuildings,
                config.canReclaimResources, config.maxTransportingUnits, config.canAttack)
            return NativeSyntheticTankShape(stock, lookup, actor, team, config, lists, container, actions, tech, flags)
        }

        private fun actorShape(actor: CustomUnit, team: PlayerTeam, config: CustomUnitConfig): Boolean =
            actor.javaClass === CustomUnit::class.java && config.javaClass === CustomUnitConfig::class.java &&
                team.javaClass === GameTeam::class.java && actor.team === team && actor.unitConfig === config &&
                actor.factoryUnitConfig == null && actor.transportContainer == null && actor.parentEntity == null &&
                actor.attachmentData == null && !actor.isDead && !actor.isDestroyed &&
                actor.buildProgress.isFinite() && actor.buildProgress == 1f &&
                !config.canNotBeGivenOrdersByPlayer && !config.isUnselectable
    }
}
