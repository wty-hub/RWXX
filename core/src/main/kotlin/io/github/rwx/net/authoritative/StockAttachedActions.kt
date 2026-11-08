package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.hooks.AttachmentSlotDefinition
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBooleanGameFunctions.HasFlagBoolean
import com.corrodinggames.rts.gameFramework.GameEngine

/** Audited modular-spider selection proxy. It grants no new entity visibility or global control. */
class StockAttachedActions(private val engine: GameEngine, private val stock: StockUnitCatalog) {
    data class Operator internal constructor(
        val parent: CustomUnit,
        val actor: CustomUnit,
        val slotIndex: Int,
        val slot: AttachmentSlotDefinition,
        internal val parentConfig: CustomUnitConfig,
        internal val parentFactoryConfig: CustomUnitConfig?,
        internal val actorConfig: CustomUnitConfig,
        internal val actorFactoryConfig: CustomUnitConfig?,
        internal val parentUpgrade: Int,
        internal val actorUpgrade: Int,
        internal val team: PlayerTeam,
        internal val selection: HasFlagBoolean,
    ) {
        fun sameOperator(other: Operator): Boolean = parent === other.parent && actor === other.actor &&
            slotIndex == other.slotIndex && slot === other.slot && parentConfig === other.parentConfig &&
            parentFactoryConfig === other.parentFactoryConfig && actorConfig === other.actorConfig &&
            actorFactoryConfig === other.actorFactoryConfig && parentUpgrade == other.parentUpgrade &&
            actorUpgrade == other.actorUpgrade && team === other.team && selection === other.selection
    }

    fun selected(parent: OrderableUnit): List<Operator> {
        check(GameEngine.getInstance() === engine) { "Attachment proxy belongs to another engine" }
        stock.assertValid()
        val spider = parent as? CustomUnit ?: return emptyList()
        if (spider.isDead || spider.isDestroyed || spider.canNotBeGivenOrdersByPlayer() ||
            !spider.buildProgress.isFinite() || spider.buildProgress < 1f ||
            spider.transportContainer != null || spider.parentEntity != null) return emptyList()
        if (stock.keyFor(spider.unitConfig) !in PARENT_KEYS) return emptyList()
        val parentFactory = spider.factoryUnitConfig
        if (parentFactory != null && stock.keyFor(parentFactory) !in PARENT_KEYS) return emptyList()
        val slots = spider.unitConfig.attachmentSlotDefinitions
        val result = ArrayList<Operator>()
        for (slotValue in slots) {
            val slot = slotValue as? AttachmentSlotDefinition ?: continue
            if (slot.javaClass != AttachmentSlotDefinition::class.java || !slot.o) continue
            val name = slot.b() ?: continue
            if (name.length != 9 || !name.startsWith("unitSlot")) continue
            val index = name.last().digitToIntOrNull()?.takeIf { it in 1..6 } ?: continue
            val position = slot.a().toInt()
            if (position !in 0 until slots.size || slots[position] !== slot) continue
            val predicate = slot.N
            if (predicate?.javaClass != HasFlagBoolean::class.java) continue
            val flag = predicate as HasFlagBoolean
            val mask = 1 shl index
            if (flag.flagId != index || flag.flagMask != mask || spider.unitFlags and mask == 0) continue
            val child = spider.a(slot) as? CustomUnit ?: continue
            if (child === spider || child.isDead || child.isDestroyed || child.canNotBeGivenOrdersByPlayer() ||
                child.transportContainer != null || child.team !== spider.team ||
                child.parentEntity !== spider || child.attachmentData !== slot ||
                !child.buildProgress.isFinite() || child.buildProgress !in 0f..1f) continue
            if (!moduleKey(stock.keyFor(child.unitConfig))) continue
            val childFactory = child.factoryUnitConfig
            if (childFactory != null && !moduleKey(stock.keyFor(childFactory))) continue
            result += Operator(spider, child, index, slot, spider.unitConfig, parentFactory,
                child.unitConfig, childFactory, spider.upgradeLevel, child.upgradeLevel, spider.team, flag)
        }
        // A malformed duplicate binding must not create two independent capabilities for one slot.
        if (result.map { it.slotIndex }.distinct().size != result.size ||
            result.indices.any { index -> (0 until index).any { result[it].actor === result[index].actor } }) return emptyList()
        return result.sortedBy { it.slotIndex }
    }

    fun current(operator: Operator): Boolean = selected(operator.parent).any(operator::sameOperator)

    private fun moduleKey(key: String?): Boolean = key != null && key.startsWith("modularSpider_") && key !in PARENT_KEYS

    private companion object { val PARENT_KEYS = setOf("modularSpider", "modularSpider_nonEmpty") }
}
