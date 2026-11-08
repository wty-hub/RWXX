package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.units.AttackMode
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitAction
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.actions.*
import com.corrodinggames.rts.game.units.air.AmphibiousJet
import com.corrodinggames.rts.game.units.air.Dropship
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface
import com.corrodinggames.rts.game.units.buildings.NukeLauncher
import com.corrodinggames.rts.game.units.buildings.RepairBay
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.logic.ConfigurableCustomAction
import com.corrodinggames.rts.game.units.custom.logic.CustomAction
import com.corrodinggames.rts.game.units.custom.logic.CustomActionDef
import com.corrodinggames.rts.game.units.custom.logic.actions.AttachmentAction
import com.corrodinggames.rts.game.units.custom.logic.actions.SpawnUnitAction
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.game.units.custom.price.UnitPrice
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.game.units.land.HovercraftUnit
import com.corrodinggames.rts.game.units.sea.AttackSubmarine
import com.corrodinggames.rts.game.units.sea.BuilderShip
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.utility.GameViewUtils
import io.github.rwx.geometry.PointF
import java.util.Locale

/** Server owner-thread adapter. Receipts, visibility and selected-owner authorization are external. */
class StockActionAdapter(private val engine: GameEngine, private val stock: StockUnitCatalog,
    private val closure: StockActionClosure? = null) {
    data class Entry(
        val key: String,
        val kind: ViewActionKind,
        val targetMode: ActionTargetMode,
        val producedTypeKey: String,
        val action: AbstractUnitAction? = null,
        val variant: Int = 1,
        val attackMode: AttackMode? = null,
        internal val ownerType: UnitType? = null,
        internal val factoryType: CustomUnitConfig? = null,
        internal val producedType: UnitType? = null,
        internal val convertedType: UnitType? = null,
        internal val temporaryType: UnitType? = null,
        internal val actionId: ActionId? = action?.actionId,
        internal val queueId: ActionId? = action?.queueId,
        internal val policy: UnitAction? = action?.unitAction,
        internal val definition: CustomActionDef? = (action as? CustomAction)?.actionDef,
        internal val closureToken: Any? = null,
        internal val nativeType: ActionType? = action?.actionType,
    ) {
        /** AbstractUnitAction.equals compares IDs; it must never grant a replacement object authority. */
        fun sameCapability(other: Entry): Boolean = key == other.key && kind == other.kind &&
            targetMode == other.targetMode && producedTypeKey == other.producedTypeKey && variant == other.variant &&
            attackMode == other.attackMode && action === other.action && actionId === other.actionId &&
            queueId === other.queueId && ownerType === other.ownerType && factoryType === other.factoryType &&
            producedType === other.producedType && convertedType === other.convertedType &&
            temporaryType === other.temporaryType && policy === other.policy && definition === other.definition &&
            closureToken === other.closureToken && nativeType == other.nativeType
    }

    /** Explicit audit output; never sent to the player and never contains native ActionId text. */
    data class UnsupportedAction(val actorKey: String, val ordinal: Int, val actionClass: String, val reason: String)
    private val unsupported = LinkedHashSet<UnsupportedAction>()
    fun unsupportedActions(): List<UnsupportedAction> = unsupported.toList()

    fun describe(actor: OrderableUnit): List<Entry> {
        stock.assertValid()
        closure?.assertValid()
        if (actor.isDead || actor.isDestroyed || actor.canNotBeGivenOrdersByPlayer() || actor.transportContainer != null ||
            !actor.buildProgress.isFinite() || actor.buildProgress !in 0f..1f) return emptyList()
        val ownerType = actor.r()
        val ownerKey = stock.keyFor(ownerType) ?: return emptyList()
        val factoryType = (actor as? CustomUnit)?.factoryUnitConfig
        if (factoryType != null && stock.keyFor(factoryType) == null) return emptyList()
        val entries = ArrayList<Entry>()
        actor.availableActions.forEachIndexed { index, value ->
            val action = value as? AbstractUnitAction ?: return@forEachIndexed
            val ordinal = index + 1
            if (!reviewedActionClass(action)) {
                unsupported += UnsupportedAction(ownerKey, ordinal, action.javaClass.name, "Unreviewed action implementation")
                return@forEachIndexed
            }
            if (!reviewedPolicy(action.unitAction)) {
                unsupported += UnsupportedAction(ownerKey, ordinal, action.javaClass.name, "Unreviewed action policy")
                return@forEachIndexed
            }
            if (staticallyHidden(action)) return@forEachIndexed
            if (actor.buildProgress < 1f && !incompleteModuleCancellation(actor, action)) return@forEachIndexed
            val custom = action as? CustomAction
            val definition = custom?.actionDef
            val closureToken = if (custom != null && closure != null) closure.validatedToken(custom) else null
            if (definition != null && if (closure != null) closureToken == null else !closedCustomAction(definition)) {
                unsupported += UnsupportedAction(ownerKey, ordinal, action.javaClass.name, "Unclosed production or triggered-action references")
                return@forEachIndexed
            }
            val produced = if (action.actionType == ActionType.placeBuilding) action.attachedUnitType else action.unitType
            val converted = definition?.convertTo?.c()
            val temporary = definition?.whenBuildingTemporarilyConvertTo?.c()
            val producedKey = produced?.let(stock::keyFor)
            if (produced != null && (producedKey == null || stock.typeFor(producedKey) !== produced)) {
                unsupported += UnsupportedAction(ownerKey, ordinal, action.javaClass.name, "Production type is outside effective stock identities")
                return@forEachIndexed
            }
            val kind = classify(actor, action, produced, converted)
            if (kind == null) {
                unsupported += UnsupportedAction(ownerKey, ordinal, action.javaClass.name, "No authoritative command mapping for this action")
                return@forEachIndexed
            }
            val variant = if (kind == ViewActionKind.BUILD) action.queueSize else 1
            if (variant !in 1..3 || (kind == ViewActionKind.BUILD && produced == null)) {
                unsupported += UnsupportedAction(ownerKey, ordinal, action.javaClass.name, "Unsupported build variant or missing type")
                return@forEachIndexed
            }
            val presentation = semanticSuffix(ownerKey, action, kind) ?: ordinal.toString()
            entries += Entry("${kind.name.lowercase(Locale.ROOT)}.$ownerKey.$presentation", kind, targetMode(kind),
                producedKey ?: converted?.let(stock::keyFor).orEmpty(), action, variant, ownerType = ownerType,
                factoryType = factoryType, producedType = produced, convertedType = converted, temporaryType = temporary,
                closureToken = closureToken)
        }
        fun synthetic(kind: ViewActionKind, mode: AttackMode? = null) {
            if (actor.buildProgress < 1f) return
            if (entries.any { it.kind == kind && it.attackMode == mode }) return
            val suffix = mode?.name ?: "command"
            entries += Entry("${kind.name.lowercase(Locale.ROOT)}.$ownerKey.$suffix", kind, targetMode(kind), "",
                attackMode = mode, ownerType = ownerType, factoryType = factoryType)
        }
        val config = (actor as? CustomUnit)?.unitConfig
        val repair = canRepair(actor)
        if (repair) synthetic(ViewActionKind.REPAIR)
        if (repair || config?.canReclaimResources == true) synthetic(ViewActionKind.RECLAIM)
        if (actor.canTransportUnits()) synthetic(ViewActionKind.LOAD_UP)
        if (canTravel(actor)) {
            synthetic(ViewActionKind.LOAD_INTO)
            synthetic(ViewActionKind.GUARD)
            synthetic(ViewActionKind.PATROL)
        }
        if (actor.canAttack()) {
            for (mode in listOf(AttackMode.onlyInRange, AttackMode.guardArea, AttackMode.aggressive,
                AttackMode.holdFire, AttackMode.returnFire)) synthetic(ViewActionKind.ATTACK_MODE, mode)
        }
        if (entries.size > ViewLimits.MAX_ACTIONS_PER_PANEL) {
            unsupported += UnsupportedAction(ownerKey, 0, actor.javaClass.name, "Action panel exceeds 128 entries; paging is required")
            throw IllegalStateException("Standard action panel exceeds the supported capacity")
        }
        return entries
    }

    fun view(actor: OrderableUnit, entry: Entry, handle: Long): ViewAction = viewImpl(actor, entry, handle, closureValidated = false)

    /** Only for a synchronous owner-thread panel built immediately after describe, without mutation/yield. */
    internal fun viewAfterDescribe(actor: OrderableUnit, entry: Entry, handle: Long): ViewAction =
        viewImpl(actor, entry, handle, closureValidated = true)

    private fun viewImpl(actor: OrderableUnit, entry: Entry, handle: Long, closureValidated: Boolean): ViewAction {
        require(handle > 0) { "Action handles must be positive" }
        check(entryIdentityCurrent(actor, entry, closureValidated)) { "Action capability changed" }
        val action = entry.action
        val queue = actor as? FactoryQueueInterface
        val count = if (action is PopupQueueAction && queue != null) queue.a(entry.actionId, false) else -1
        val current = if (action is PopupQueueAction) queue?.dw() else null
        val progress = if (current?.j === entry.actionId && current != null && current.m.isFinite()) {
            current.m.coerceIn(0f, 1f)
        } else -1f
        val readiness = if (action != null && !safeReadiness(action)) ActionReadiness.UNKNOWN else {
            if ((actor.buildProgress < 1f && !incompleteModuleCancellation(actor, action)) ||
                !canStartWithoutTarget(actor, entry)) ActionReadiness.BLOCKED else ActionReadiness.READY
        }
        // Never use getActiveCount: CustomAction overloads it with a stockpile approximation.
        val stockpile = if (action is CustomAction && action.actionDef.displayRemainingStockpile &&
            action.actionDef.price.f > 0 && action.actionDef.price.b == 0 && action.actionDef.price.c == 0f &&
            action.actionDef.price.d == 0f && action.actionDef.price.e == 0f) {
            actor.ammo / action.actionDef.price.f
        } else -1
        return ViewAction(handle, entry.kind, entry.targetMode, "authority.action.${entry.key}", entry.producedTypeKey,
            action?.price?.let(::cost), action?.additionalCost?.let(::cost), readiness,
            boundedCount(count), boundedCount(stockpile), progress,
            action is PopupQueueAction && count > 0 && action.canPlayerCancel(actor, true))
    }

    fun execute(actor: OrderableUnit, entry: Entry, intent: ViewActionCommand, target: BaseUnit?): Boolean {
        stock.assertValid()
        if (actor.isDead || actor.isDestroyed || actor.canNotBeGivenOrdersByPlayer() || actor.transportContainer != null ||
            !validShape(entry, intent, target)) return false
        if (describe(actor).none(entry::sameCapability)) return false
        val action = entry.action
        if (action != null && (actor.validateActionId(entry.actionId) !== action ||
            action.actionId !== entry.actionId || action.queueId !== entry.queueId)) return false
        if (intent.cancel) {
            val queue = actor as? FactoryQueueInterface ?: return false
            if (action !is PopupQueueAction || !action.canPlayerCancel(actor, true) || queue.a(entry.actionId, false) <= 0) return false
        } else {
            // Stock modular attachment cancellation is a real queued action available while incomplete.
            // Its visibility/activation is evaluated here, never exported as a world-sensitive boolean.
            if (actor.buildProgress < 1f && !incompleteModuleCancellation(actor, action)) return false
            if (!canStartWithoutTarget(actor, entry)) return false
            when (entry.kind) {
                ViewActionKind.BUILD -> {
                    if (entry.producedType == null || !validPoint(intent.x, intent.y)) return false
                    if (actor.findActionForUnitTypeWithQueueSize(entry.producedType, entry.variant, false) !== action) return false
                }
                ViewActionKind.RALLY -> if (actor !is FactoryQueueInterface || !validPoint(intent.x, intent.y)) return false
                ViewActionKind.REPAIR -> {
                    if (target == null || !validOwnTarget(actor, target) || target.resourceRate != 0f ||
                        (target.buildProgress >= 1f && target.currentHealth >= target.maxHealth) || !actor.canRepairTarget(target)) return false
                }
                ViewActionKind.RECLAIM -> {
                    if (target == null || target === actor || target.transportContainer != null ||
                        target.isDead || target.isDestroyed || (target.team !== actor.team && !(target.resourceRate > 0f && target.team.teamId < 0)) ||
                        !actor.canReclaimTarget(target)) return false
                }
                ViewActionKind.LOAD_INTO -> if (target == null || !validOwnTarget(actor, target) ||
                    !canTravel(actor) || !target.d(actor, false)) return false
                ViewActionKind.LOAD_UP -> if (target == null || !validOwnTarget(actor, target) || !actor.d(target, false)) return false
                ViewActionKind.GUARD -> if (target == null || !validOwnTarget(actor, target) || !canTravel(actor)) return false
                ViewActionKind.PATROL -> if (!canTravel(actor) || !validPoint(intent.x, intent.y)) return false
                ViewActionKind.TARGET_GROUND -> if (!validPoint(intent.x, intent.y) || !groundAllowed(actor, entry, intent.x, intent.y)) return false
                ViewActionKind.ATTACK_MODE -> if (!actor.canAttack() || entry.attackMode == null) return false
                else -> Unit
            }
        }
        val command = engine.commandController.createCommand()
        command.team = actor.team
        command.sourceTeam = actor.team
        command.createdTick = engine.gameTimeMillis
        command.isQueued = intent.queued
        command.addUnitToCommand(actor)
        if (intent.cancel) {
            command.setActionId(entry.queueId)
            command.stopCurrentAction = true
        } else when (entry.kind) {
            ViewActionKind.BUILD -> command.setBuildTarget(intent.x, intent.y, entry.producedType, entry.variant)
            ViewActionKind.RALLY -> command.setRallyPoint(PointF(intent.x, intent.y))
            ViewActionKind.REPAIR -> command.setRepairTarget(target)
            ViewActionKind.RECLAIM -> command.setReclaimTarget(target)
            ViewActionKind.LOAD_INTO -> command.setLoadIntoTarget(target)
            ViewActionKind.LOAD_UP -> command.setLoadUpTarget(target)
            ViewActionKind.GUARD -> command.setGuardTarget(target)
            ViewActionKind.PATROL -> command.setPatrolTarget(intent.x, intent.y)
            ViewActionKind.ATTACK_MODE -> command.setAttackMode(entry.attackMode)
            ViewActionKind.TARGET_GROUND -> command.setActionTarget(entry.actionId, PointF(intent.x, intent.y), null)
            ViewActionKind.PRODUCE, ViewActionKind.UPGRADE, ViewActionKind.UNLOAD,
            ViewActionKind.STOP_UNLOAD, ViewActionKind.TOGGLE -> command.setActionId(entry.queueId)
        }
        if (!command.prepareAndValidateCommand()) return false
        engine.commandController.pendingCommands.add(command)
        return true
    }

    private fun validShape(entry: Entry, intent: ViewActionCommand, target: BaseUnit?): Boolean {
        if (intent.sequence <= 0 || intent.viewSequence <= 0 || intent.panelSequence <= 0 || intent.unitHandle <= 0 ||
            intent.actionHandle <= 0 || !intent.x.isFinite() || !intent.y.isFinite() || intent.targetHandle < 0) return false
        if (intent.cancel) return intent.target === CommandTarget.None && target == null && !intent.queued
        if (intent.queued && entry.kind !in QUEUABLE_TARGETS) return false
        return when (entry.targetMode) {
            ActionTargetMode.NONE -> intent.target === CommandTarget.None && target == null
            ActionTargetMode.POINT -> intent.target is CommandTarget.Point && target == null
            ActionTargetMode.VISIBLE_UNIT -> intent.target is CommandTarget.Unit && intent.targetHandle > 0 && target != null
        }
    }

    private fun canStartWithoutTarget(actor: OrderableUnit, entry: Entry): Boolean = entry.action?.let {
        !it.isNotAvailable(actor) && it.b(actor) && it.canAfford(actor, false)
    } ?: true

    /** View construction never regenerates all descriptors for every row in the same panel. */
    private fun entryIdentityCurrent(actor: OrderableUnit, entry: Entry, closureValidated: Boolean): Boolean {
        if (actor.isDead || actor.isDestroyed || actor.canNotBeGivenOrdersByPlayer() ||
            actor.transportContainer != null || stock.keyFor(actor.r()) == null ||
            actor.r() !== entry.ownerType || (actor as? CustomUnit)?.factoryUnitConfig !== entry.factoryType) return false
        if (!actor.buildProgress.isFinite() || actor.buildProgress !in 0f..1f ||
            (actor.buildProgress < 1f && !incompleteModuleCancellation(actor, entry.action))) return false
        val action = entry.action ?: return when (entry.kind) {
            ViewActionKind.REPAIR -> canRepair(actor)
            ViewActionKind.RECLAIM -> canRepair(actor) || (actor as? CustomUnit)?.unitConfig?.canReclaimResources == true
            ViewActionKind.LOAD_UP -> actor.canTransportUnits()
            ViewActionKind.LOAD_INTO, ViewActionKind.GUARD, ViewActionKind.PATROL -> canTravel(actor)
            ViewActionKind.ATTACK_MODE -> actor.canAttack() && entry.attackMode != null
            else -> false
        }
        if (actor.availableActions.none { it === action } || action.actionId !== entry.actionId ||
            action.queueId !== entry.queueId || action.unitAction !== entry.policy || action.actionType != entry.nativeType ||
            staticallyHidden(action)) return false
        val produced = if (entry.kind == ViewActionKind.BUILD) action.attachedUnitType else action.unitType
        if (produced !== entry.producedType || (entry.kind == ViewActionKind.BUILD && action.queueSize != entry.variant)) return false
        val custom = action as? CustomAction
        if (custom != null) {
            if (custom.actionDef !== entry.definition || custom.actionDef.convertTo?.c() !== entry.convertedType ||
                custom.actionDef.whenBuildingTemporarilyConvertTo?.c() !== entry.temporaryType) return false
            if (closure != null) return entry.closureToken != null &&
                (if (closureValidated) closure.validatedToken(custom) else closure.token(custom)) === entry.closureToken
            if (!closedCustomAction(custom.actionDef)) return false
        }
        return true
    }

    private fun validOwnTarget(actor: OrderableUnit, target: BaseUnit): Boolean = target !== actor &&
        target.team === actor.team && !target.isDead && !target.isDestroyed && target.transportContainer == null

    private fun canTravel(actor: OrderableUnit): Boolean = actor.canExecuteMovementCommands() && actor.aS()

    private fun canRepair(actor: OrderableUnit): Boolean = actor is BuilderUnit || actor is BuilderShip || actor is RepairBay ||
        (actor as? CustomUnit)?.unitConfig?.let { it.canRepairBuildings || it.canRepairUnits } == true

    private fun validPoint(x: Float, y: Float): Boolean {
        val map = engine.tileMap ?: return false
        return x.isFinite() && y.isFinite() && x >= 0f && y >= 0f &&
            x.toDouble() < map.tileCountX.toDouble() * map.tileWorldSizeX &&
            y.toDouble() < map.tileCountY.toDouble() * map.tileWorldSizeY
    }

    /** Equivalent to CustomUnit's public distance/terrain checks, without its UI rejection paths. */
    private fun groundAllowed(actor: OrderableUnit, entry: Entry, x: Float, y: Float): Boolean {
        val distance = (actor.posX.toDouble() - x).let { it * it } + (actor.posY.toDouble() - y).let { it * it }
        if (!distance.isFinite()) return false
        if (actor is NukeLauncher) return distance >= 202_500.0
        val custom = actor as? CustomUnit ?: return false
        val definition = (entry.action as? CustomAction)?.actionDef ?: return false
        val index = definition.fireTurretAtGroundIndex ?: return false
        if (definition.fireTurretAtGroundOffset != null || index !in custom.unitConfig.turrets.indices) return false
        val turret = custom.unitConfig.turrets[index]
        if (!turret.effectiveRangeSquared.isFinite() || !turret.limitingMinRangeSquared.isFinite() ||
            distance > turret.effectiveRangeSquared || distance < turret.limitingMinRangeSquared) return false
        val terrain = definition.fireTurretAtGroundTerrainFilter
        return terrain == null || !GameViewUtils.a(x, y, terrain)
    }

    private fun classify(actor: OrderableUnit, action: AbstractUnitAction, produced: UnitType?, converted: UnitType?): ViewActionKind? {
        if (action === HovercraftUnit.i || action === Dropship.q) return ViewActionKind.UNLOAD
        if (action === HovercraftUnit.j || action === Dropship.r) return ViewActionKind.STOP_UNLOAD
        return when (action.actionType) {
            ActionType.placeBuilding -> ViewActionKind.BUILD
            ActionType.setRally -> ViewActionKind.RALLY
            ActionType.repairTarget -> ViewActionKind.REPAIR
            ActionType.reclaimTarget -> ViewActionKind.RECLAIM
            ActionType.guardUnit -> ViewActionKind.GUARD
            ActionType.patrol -> ViewActionKind.PATROL
            ActionType.targetGround -> if (action is CustomAction || actor is NukeLauncher) ViewActionKind.TARGET_GROUND else null
            ActionType.popupQueue -> when {
                converted != null -> ViewActionKind.UPGRADE
                produced != null || actor is NukeLauncher || action.javaClass.name.endsWith("AntiNukeLauncher\$2") ||
                    (action is CustomAction && (action.actionDef.addResources?.f ?: 0) > 0) -> ViewActionKind.PRODUCE
                action is CustomAction -> ViewActionKind.TOGGLE
                else -> ViewActionKind.UPGRADE
            }
            ActionType.none -> when {
                action is CustomAction && converted != null -> ViewActionKind.UPGRADE
                action is CustomAction && (produced != null || (action.actionDef.addResources?.f ?: 0) > 0) -> ViewActionKind.PRODUCE
                action is CustomAction || action === AmphibiousJet.y || action === AmphibiousJet.z ||
                    action === AttackSubmarine.j || action === AttackSubmarine.k -> ViewActionKind.TOGGLE
                else -> null
            }
            else -> null
        }
    }

    private fun incompleteModuleCancellation(actor: OrderableUnit, action: AbstractUnitAction?): Boolean =
        actor is CustomUnit && isStockModule(stock.keyFor(actor.unitConfig)) &&
            action is CustomAction && action.actionDef.displayName == "cancel"

    /**
     * These are presentation aliases for reviewed, static stock sections, not action identifiers.
     * In particular, the parser leaves name null unless an INI section has an explicit id.
     * The action and definition still pass the frozen closure/identity checks before use.
     */
    private fun semanticSuffix(ownerKey: String, action: AbstractUnitAction, kind: ViewActionKind): String? {
        val section = (action as? CustomAction)?.actionDef?.displayName
        if (ownerKey == "modularSpider" || ownerKey == "modularSpider_nonEmpty") {
            if (kind == ViewActionKind.TOGGLE) when (section) {
                "selectSlot1" -> return "selectSlot1"
                "selectSlot2" -> return "selectSlot2"
                "selectSlot3" -> return "selectSlot3"
                "selectSlot4" -> return "selectSlot4"
                "selectSlot5" -> return "selectSlot5"
                "selectSlot6" -> return "selectSlot6"
                "selectSlotBuild" -> return "selectBuildSlot"
            }
            if (kind == ViewActionKind.TARGET_GROUND && section == "blinkQueued") return "blink"
        }
        if (isStockModule(ownerKey)) {
            if (kind == ViewActionKind.TOGGLE && section == "cancel") return "cancelConstruction"
            if (kind == ViewActionKind.UPGRADE && section == "reclaim") return "reclaimModule"
        }
        if (ownerKey == "modularSpider_blink" && kind == ViewActionKind.TARGET_GROUND && section == "blink") return "blink"
        if (ownerKey == "NukeLaucher") {
            if (kind == ViewActionKind.PRODUCE && (action !is CustomAction || section == "buildNuke")) return "buildNuke"
            if (kind == ViewActionKind.TARGET_GROUND && (action !is CustomAction || section == "launchNuke")) return "launchNuke"
        }
        if (ownerKey == "AntiNukeLaucher" || ownerKey == "modularSpider_antinuke") {
            if (kind == ViewActionKind.PRODUCE && (action !is CustomAction || section == "buildAntiNuke")) return "buildAntiNuke"
        }
        if (ownerKey == "modularSpider_antinuke" && kind == ViewActionKind.TOGGLE) when (section) {
            "isEnabled" -> return "disableAntiNuke"
            "isDisabled" -> return "enableAntiNuke"
        }
        return null
    }

    // The stock catalog has already matched the immutable manifest and config identity.
    private fun isStockModule(key: String?): Boolean =
        key != null && key.startsWith("modularSpider_") && key != "modularSpider_nonEmpty"

    private fun closedCustomAction(definition: CustomActionDef): Boolean {
        for (reference in listOf(definition.convertTo, definition.guiBuildUnit, definition.whenBuildingTemporarilyConvertTo)) {
            if (reference != null && (reference.c() == null || stock.keyFor(reference.c()) == null)) return false
        }
        // These compiled references need an independently reviewed read-only closure accessor.
        // Do not guess from names, invoke scripts/spawns, or silently claim support for modules.
        if (definition.alsoTriggerAction != null || definition.alsoQueueAction != null || definition.whenBuildingTriggerAction != null) return false
        if (definition.logicActions.any { it is SpawnUnitAction || it is AttachmentAction }) return false
        return true
    }

    private fun staticallyHidden(action: AbstractUnitAction): Boolean =
        (action as? CustomAction)?.actionDef?.isVisible === LogicBoolean.falseBoolean ||
            (action.unitAction as? ConfigurableCustomAction)?.enabledCondition === LogicBoolean.falseBoolean

    private fun safeReadiness(action: AbstractUnitAction): Boolean {
        val policy = action.unitAction
        if (policy is ConfigurableCustomAction && listOf(policy.enabledCondition, policy.highlightCondition,
            policy.highlightColorCondition, policy.iconCondition).any { !constant(it) }) return false
        val definition = (action as? CustomAction)?.actionDef ?: return true
        return listOf(definition.isVisible, definition.isActive, definition.isLocked, definition.isLockedAlt,
            definition.isLockedAlt2, definition.requireConditional).all(::constant)
    }

    private fun constant(logic: LogicBoolean?): Boolean = logic == null || logic === LogicBoolean.trueBoolean || logic === LogicBoolean.falseBoolean
    private fun reviewedPolicy(policy: UnitAction): Boolean = policy.javaClass == UnitAction::class.java || policy.javaClass == ConfigurableCustomAction::class.java
    private fun reviewedActionClass(action: AbstractUnitAction): Boolean = action.javaClass in DIRECT_ACTION_CLASSES ||
        action.javaClass.name in NATIVE_ACTION_CLASSES
    private fun cost(price: UnitPrice): ViewActionCost {
        check(price.c.isFinite() && price.d.isFinite() && price.e.isFinite()) { "Invalid standard action price" }
        return ViewActionCost(price.b, price.c, price.d, price.e, price.f)
    }
    private fun boundedCount(value: Int): Int = if (value in 0..65_536) value else -1

    private companion object {
        val QUEUABLE_TARGETS = setOf(ViewActionKind.BUILD, ViewActionKind.REPAIR, ViewActionKind.RECLAIM,
            ViewActionKind.LOAD_INTO, ViewActionKind.LOAD_UP, ViewActionKind.GUARD, ViewActionKind.PATROL)
        val DIRECT_ACTION_CLASSES = setOf(PlaceBuildingAction::class.java, QueueUnitAction::class.java, CustomAction::class.java,
            SetRallyAction::class.java, RepairTargetAction::class.java, ReclaimTargetAction::class.java,
            GuardUnitAction::class.java, PatrolAction::class.java)
        val NATIVE_ACTION_CLASSES = buildSet {
            val root = "com.corrodinggames.rts.game.units."
            for (name in listOf("LandFactoryUpgradeAction", "AirFactoryUpgradeT2", "SeaFactoryT2Upgrade")) add(root + "buildings." + name)
            for ((name, count) in mapOf("PowerFabricator" to 2, "ResourceExtractor" to 2, "LaserDefense" to 1,
                "ExperimentalLandFactory" to 1, "SupplyDepot" to 1, "NukeLauncher" to 2, "AntiNukeLauncher" to 2,
                "turrets.TurretFactory" to 4, "turrets.AntiAirTurret" to 1)) {
                for (index in 1..count) add(root + "buildings." + name + "$" + index)
            }
            for (name in listOf("land.HovercraftUnit", "air.Dropship", "air.AmphibiousJet", "sea.AttackSubmarine")) {
                for (index in 1..2) add(root + name + "$" + index)
            }
        }
        fun targetMode(kind: ViewActionKind): ActionTargetMode = when (kind) {
            ViewActionKind.BUILD, ViewActionKind.RALLY, ViewActionKind.PATROL, ViewActionKind.TARGET_GROUND -> ActionTargetMode.POINT
            ViewActionKind.REPAIR, ViewActionKind.RECLAIM, ViewActionKind.LOAD_INTO, ViewActionKind.LOAD_UP, ViewActionKind.GUARD -> ActionTargetMode.VISIBLE_UNIT
            else -> ActionTargetMode.NONE
        }
    }
}
