package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.gameFramework.GameEngine
import java.security.SecureRandom

/** One connection's selected-unit capabilities. Native action IDs never leave this object. */
internal class EngineActionCatalog(
    private val engine: GameEngine,
    private val team: PlayerTeam,
    private val stock: StockUnitCatalog,
    closure: StockActionClosure? = null,
) {
    private val adapter = StockActionAdapter(engine, stock, closure)
    private val attachments = StockAttachedActions(engine, stock)
    private var selected: Owner? = null
    private var nextHandle = SecureRandom().nextLong().ushr(2) + 1
    private var panelSequence = 0L
    private var lastQuerySequence = -1L
    private val capabilities = linkedMapOf<Long, Capability>()
    private val receipts = ArrayDeque<Receipt>()

    private class Owner(val actor: OrderableUnit, val handle: Long) {
        private val type: UnitType = actor.r()
        private val factory: CustomUnitConfig? = (actor as? CustomUnit)?.factoryUnitConfig
        private val upgrade = actor.upgradeLevel
        fun matchesNativeShape(shape: NativeSyntheticTankShape): Boolean = shape.matchesOwner(type, factory, upgrade)
        fun isCurrent(candidate: OrderableUnit, candidateHandle: Long): Boolean =
            candidate === actor && candidateHandle == handle && candidate.r() === type &&
                (candidate as? CustomUnit)?.factoryUnitConfig === factory && candidate.upgradeLevel == upgrade
    }

    private class Receipt(val sequence: Long, val tick: Int, val owner: Owner, val handles: Set<Long>)

    private class Capability(val actor: OrderableUnit, val entry: StockActionAdapter.Entry,
        val operator: StockAttachedActions.Operator? = null) {
        fun sameCapability(other: Capability): Boolean = actor === other.actor && entry.sameCapability(other.entry) &&
            if (operator == null) other.operator == null else other.operator?.let(operator::sameOperator) == true
    }

    /**
     * Instantaneous grant-local prerequisite, called only by the engine owner.
     * Receipt owners and attached operators can outlive both selection and world membership.
     * Do not inspect those receivers, validate the catalog, or revoke anything in this query.
     * A true result says nothing about world/registry/status callback purity.
     */
    internal fun hasNoRetainedActionDependencies(): Boolean =
        selected == null && capabilities.isEmpty() && receipts.isEmpty()

    /**
     * Catalog roots only. A new Timeline selection not yet represented by Owner must be qualified
     * separately by the future grant delegate. No validation/getter/revocation is performed here.
     */
    internal fun nativeSyntheticTankDependenciesOrNull(lookup: StockUnitCatalog.NativeSightLookup): NativeTankDependencies? =
        NativeTankDependencies.capture(this, lookup)

    /**
     * Owner-scope opaque descriptor. Matching fields cannot detect unobserved A -> B -> A changes:
     * close it before every callback or world mutation boundary, even if current fields still match.
     * Detection of any mismatch permanently invalidates this descriptor, without changing catalog.
     */
    internal class NativeTankDependencies private constructor(
        private val catalog: EngineActionCatalog,
        private val lookup: StockUnitCatalog.NativeSightLookup,
        private val selected: Owner?,
        private val capabilities: List<Pair<Long, Capability>>,
        private val receipts: List<Receipt>,
        private val shapes: java.util.IdentityHashMap<OrderableUnit, NativeSyntheticTankShape>,
    ) : AutoCloseable {
        private val owner = Thread.currentThread()
        private var valid = true

        fun stillCurrent(): Boolean {
            // This enforces owner/engine/scope provenance even for an empty dependency set.
            if (lookup.nativeTankIdentityFor(catalog.stock) == null) valid = false
            if (!valid) return false
            if (catalog.selected !== selected || catalog.capabilities.size != capabilities.size ||
                catalog.receipts.size != receipts.size) return invalidate()
            val currentCapabilities = catalog.capabilities.entries.iterator()
            for ((handle, capability) in capabilities) {
                val current = currentCapabilities.next()
                if (current.key != handle || current.value !== capability) return invalidate()
            }
            val currentReceipts = catalog.receipts.iterator()
            for (receipt in receipts) if (currentReceipts.next() !== receipt) return invalidate()
            for (shape in shapes.values) if (!shape.stillCurrent()) return invalidate()
            if (selected != null && !selected.matchesNativeShape(checkNotNull(shapes[selected.actor]))) return invalidate()
            for (receipt in receipts) {
                if (!receipt.owner.matchesNativeShape(checkNotNull(shapes[receipt.owner.actor]))) return invalidate()
            }
            for ((_, capability) in capabilities) {
                if (capability.operator != null || !checkNotNull(shapes[capability.actor])
                        .matchesSyntheticEntry(capability.entry)) return invalidate()
            }
            return true
        }

        private fun invalidate(): Boolean { valid = false; return false }
        override fun close() {
            check(Thread.currentThread() === owner) { "Native dependency descriptors require the engine owner" }
            valid = false
        }

        companion object {
            fun capture(catalog: EngineActionCatalog, lookup: StockUnitCatalog.NativeSightLookup): NativeTankDependencies? {
                if (lookup.nativeTankIdentityFor(catalog.stock) == null) return null
                val selected = catalog.selected
                val capabilities = catalog.capabilities.entries.map { it.key to it.value }
                val receipts = catalog.receipts.toList()
                val shapes = java.util.IdentityHashMap<OrderableUnit, NativeSyntheticTankShape>()
                fun shape(actor: OrderableUnit): NativeSyntheticTankShape? = shapes[actor] ?:
                    NativeSyntheticTankShape.capture(catalog.stock, lookup, actor, catalog.team)?.also { shapes[actor] = it }
                if (selected != null && !selected.matchesNativeShape(shape(selected.actor) ?: return null)) return null
                for (receipt in receipts) {
                    if (!receipt.owner.matchesNativeShape(shape(receipt.owner.actor) ?: return null)) return null
                }
                for ((_, capability) in capabilities) {
                    if (capability.operator != null || !(shape(capability.actor) ?: return null)
                            .matchesSyntheticEntry(capability.entry)) return null
                }
                return NativeTankDependencies(catalog, lookup, selected, capabilities, receipts, shapes)
            }
        }
    }

    fun clear() {
        selected = null
        capabilities.clear()
        receipts.clear()
    }

    fun revokeUnless(authorized: (BaseUnit) -> Boolean) {
        val owner = selected ?: return
        if (!owner.isCurrent(owner.actor, owner.handle) || !authorized(owner.actor)) {
            clear()
            return
        }
        val currentOperators = attachments.selected(owner.actor)
        capabilities.entries.removeAll { (_, capability) -> capability.operator?.let { previous ->
            currentOperators.none(previous::sameOperator)
        } == true }
    }

    /** The caller has already checked the request's world receipt and current ownership/visibility. */
    fun panel(request: ViewActionPanelQuery, actor: OrderableUnit?): PlayerActionPanel {
        check(panelSequence < Long.MAX_VALUE) { "Action panel sequence exhausted" }
        panelSequence++
        fun empty(): PlayerActionPanel = PlayerActionPanel(
            request.querySequence, panelSequence, request.viewSequence, team.teamId, 0, emptyList())
        if (request.querySequence <= lastQuerySequence) return empty()
        lastQuerySequence = request.querySequence
        if (actor == null || request.unitHandle <= 0) {
            clear()
            return empty()
        }
        if (selected?.isCurrent(actor, request.unitHandle) != true) {
            clear()
            selected = Owner(actor, request.unitHandle)
        }
        val owner = checkNotNull(selected)
        val entries = describe(actor)
        check(entries.size <= ViewLimits.MAX_ACTIONS_PER_PANEL) { "Standard action panel is too large" }
        // Removed capabilities are permanently revoked. A later identical-looking action gets a new handle.
        capabilities.entries.removeAll { (_, previous) -> entries.none(previous::sameCapability) }
        val current = linkedMapOf<Long, Capability>()
        for (entry in entries) {
            check(current.values.none(entry::sameCapability)) { "Ambiguous standard action capability" }
            val handle = capabilities.entries.singleOrNull { entry.sameCapability(it.value) }?.key ?: allocateHandle()
            current[handle] = entry
        }
        capabilities.clear()
        capabilities.putAll(current)
        val actions = current.map { (handle, capability) ->
            val action = adapter.viewAfterDescribe(capability.actor, capability.entry, handle)
            capability.operator?.let { action.copy(presentationKey = "module.slot${it.slotIndex}.${action.presentationKey}") } ?: action
        }
        receipts.addLast(Receipt(panelSequence, engine.currentTick, owner, current.keys.toSet()))
        while (receipts.size > MAX_RECENT_PANELS) receipts.removeFirst()
        return PlayerActionPanel(request.querySequence, panelSequence, request.viewSequence,
            team.teamId, request.unitHandle, actions)
    }

    /** World-view freshness and current target disclosure are checked independently by the projector. */
    fun execute(actor: OrderableUnit, intent: ViewActionCommand, target: BaseUnit?): Boolean {
        if (intent.sequence <= 0 || intent.viewSequence <= 0 || intent.panelSequence <= 0 ||
            intent.unitHandle <= 0 || intent.actionHandle <= 0) return false
        val owner = selected ?: return false
        if (!owner.isCurrent(actor, intent.unitHandle)) {
            clear()
            return false
        }
        val receipt = receipts.firstOrNull { it.sequence == intent.panelSequence } ?: return false
        if (receipt.owner !== owner || intent.actionHandle !in receipt.handles ||
            engine.currentTick.toLong() - receipt.tick !in 0..MAX_PANEL_AGE_TICKS.toLong()) return false
        val entry = capabilities[intent.actionHandle] ?: return false
        // Upgrade, conversion or replacement can alter available actions between panel publication and input.
        val current = describe(actor).singleOrNull(entry::sameCapability) ?: run {
            capabilities.remove(intent.actionHandle)
            return false
        }
        if (intent.cancel) {
            if (intent.target !== CommandTarget.None || target != null || intent.queued) return false
        } else when (current.entry.targetMode) {
            ActionTargetMode.NONE -> if (intent.target !== CommandTarget.None || target != null) return false
            ActionTargetMode.POINT -> if (intent.target !is CommandTarget.Point || target != null) return false
            ActionTargetMode.VISIBLE_UNIT -> if (intent.target !is CommandTarget.Unit || target == null || intent.targetHandle <= 0L) return false
        }
        if (intent.queued && current.entry.kind !in QUEUED_KINDS) return false
        return adapter.execute(current.actor, current.entry, intent, target)
    }

    private fun describe(actor: OrderableUnit): List<Capability> = buildList {
        adapter.describe(actor).forEach { add(Capability(actor, it)) }
        for (operator in attachments.selected(actor)) {
            adapter.describe(operator.actor).forEach { add(Capability(operator.actor, it, operator)) }
        }
    }

    private fun allocateHandle(): Long {
        check(nextHandle < Long.MAX_VALUE) { "Action handle space exhausted" }
        return nextHandle++
    }

    private companion object {
        const val MAX_RECENT_PANELS = 16
        const val MAX_PANEL_AGE_TICKS = 180
        val QUEUED_KINDS = setOf(ViewActionKind.BUILD, ViewActionKind.REPAIR, ViewActionKind.RECLAIM,
            ViewActionKind.LOAD_INTO, ViewActionKind.LOAD_UP, ViewActionKind.GUARD, ViewActionKind.PATROL)
    }
}
