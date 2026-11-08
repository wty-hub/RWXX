package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.units.UnitContainer
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.actions.PlaceBuildingAction
import com.corrodinggames.rts.game.units.actions.QueueUnitAction
import com.corrodinggames.rts.game.units.custom.*
import com.corrodinggames.rts.game.units.custom.AuthoritativeContentAccess as Access
import com.corrodinggames.rts.game.units.custom.hooks.AttachmentSlotDefinition
import com.corrodinggames.rts.game.units.custom.hooks.AuthoritativeAttachmentAccess
import com.corrodinggames.rts.game.units.custom.logic.CustomAction
import com.corrodinggames.rts.game.units.custom.logic.CustomActionDef
import com.corrodinggames.rts.game.units.custom.logic.LogicAction
import com.corrodinggames.rts.game.units.custom.logic.actions.*
import com.corrodinggames.rts.game.units.custom.condition.StoredResourceEntry
import com.corrodinggames.rts.game.units.custom.condition.StoredResources
import com.corrodinggames.rts.game.units.custom.price.UnitPrice
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBooleanGameFunctions.HasFlagBoolean
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Identity closure of already linked, bundled server content. Capture before simulation starts.
 * This authorizes content references, NOT targets, readiness, dynamic UI text, or entity disclosure.
 * No names are resolved and no logic conditions, actions, spawners or UI getters are executed.
 * [assertValid] belongs at every owner update boundary. Mutation permanently revokes this capture.
 */
class StockActionClosure private constructor(
    private val engine: GameEngine,
    private val stock: StockUnitCatalog,
    private val owner: Thread,
    private val nodes: IdentityHashMap<Any, Node>,
    private val checks: List<() -> Boolean>,
    private val validatorBackingProbes: List<() -> Boolean>,
    private val validatorReceiversKnown: Boolean,
    private val reports: List<Failure>,
    val configCount: Int,
) {
    data class Failure(val location: String, val reason: String)
    private class Node(val value: Any, val location: String) {
        val dependencies = ArrayList<Node>()
        var allowed = true
    }
    private var invalidated = false

    fun failures(): List<Failure> = reports

    /**
     * Qualifies only this closure's privately recorded check lambdas, NOT stock.assertValid,
     * the action graph, dynamic conditions, readiness, world capture or authorization.
     * The stock registry needs its separate exact-shape gate. This method neither invokes
     * validation nor revokes anything: a changed/null known backing must still fail at the
     * original assertValid call site. Unknown backing/receiver shapes return false without
     * invoking their size/get/iterator/equals/getters. Never retain a true result across a
     * callback, mutation, owner yield or another scope; always run complete assertValid.
     */
    internal fun hasKnownValidatorBackings(): Boolean {
        checkOwner()
        return !invalidated && validatorReceiversKnown && validatorBackingProbes.all { it() }
    }

    fun assertValid() {
        checkOwner()
        if (!invalidated) {
            try {
                stock.assertValid()
                if (!checks.all { it() }) invalidated = true
            } catch (failure: RuntimeException) {
                invalidated = true
                throw IllegalStateException("Standard action closure changed after capture", failure)
            }
        }
        check(!invalidated) { "Standard action closure changed after capture" }
    }

    fun allows(action: CustomAction): Boolean = token(action) != null

    /** Stable, opaque capability identity; never serialize this token or native action identifiers. */
    fun token(action: CustomAction): Any? {
        checkOwner()
        if (invalidated) return null
        try { assertValid() } catch (_: IllegalStateException) { return null }
        return validatedToken(action)
    }

    /**
     * O(1) lookup for the adapter's synchronous owner-thread pass immediately after [assertValid].
     * The caller must not run the simulation, mutate content, or yield between validation and use.
     * General callers must use [token], which also checks same-tick content mutations.
     */
    internal fun validatedToken(action: CustomAction): Any? {
        checkOwner()
        if (invalidated) return null
        return nodes[action]?.takeIf { it.allowed }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Stock action closure requires the engine owner thread" }
        check(GameEngine.getInstance() === engine) { "Stock action closure belongs to another engine" }
    }

    companion object {
        private const val MAX_NODES = 16_384
        private const val MAX_EDGES = 65_536
        private const val MAX_SEQUENCE = 4_096

        // Only final/direct source operations: Object.getClass, reference-array instanceof,
        // and identity. No List method or element equality is executed during qualification.
        private fun knownSequenceBacking(source: Any?): Boolean = source == null ||
            source.javaClass === java.util.ArrayList::class.java ||
            source.javaClass === FastArrayList::class.java || source is Array<*>

        fun capture(engine: GameEngine, stock: StockUnitCatalog): StockActionClosure {
            check(GameEngine.getInstance() === engine && engine.currentTick == 0) {
                "Capture standard action closure on the engine owner before the first simulation tick"
            }
            stock.assertValid()
            return Builder(engine, stock).build()
        }
    }

    private class Builder(val engine: GameEngine, val stock: StockUnitCatalog) {
        val nodes = IdentityHashMap<Any, Node>()
        val pending = ArrayDeque<Node>()
        val checks = ArrayList<() -> Boolean>()
        val validatorBackingProbes = ArrayList<() -> Boolean>()
        var validatorReceiversKnown = true
        val reports = ArrayList<Failure>()
        var configs: List<CustomUnitConfig> = emptyList()
        var edges = 0

        fun build(): StockActionClosure {
            configs = stock.entries.mapNotNull { it.type as? CustomUnitConfig }
            for (config in configs) node(config, "unit:${stock.keyFor(config)}")
            while (pending.isNotEmpty()) inspect(pending.removeFirst())
            // Monotone fixed point handles trigger cycles without recursion or executing a trigger.
            var changed: Boolean
            do {
                changed = false
                for (item in nodes.values) if (item.allowed && item.dependencies.any { !it.allowed }) {
                    item.allowed = false
                    changed = true
                }
            } while (changed)
            return StockActionClosure(engine, stock, Thread.currentThread(), nodes, checks,
                Collections.unmodifiableList(validatorBackingProbes.toList()), validatorReceiversKnown,
                Collections.unmodifiableList(reports.toList()), configs.size).also { it.assertValid() }
        }

        fun node(value: Any, location: String): Node = nodes[value] ?: run {
            check(nodes.size < MAX_NODES) { "Standard action closure exceeds node limit" }
            Node(value, location).also { nodes[value] = it; pending.addLast(it) }
        }

        fun edge(parent: Node, value: Any?, suffix: String) {
            if (value == null) return
            check(++edges <= MAX_EDGES) { "Standard action closure exceeds edge limit" }
            parent.dependencies += node(value, "${parent.location}/$suffix")
        }

        fun fail(node: Node, reason: String) {
            node.allowed = false
            reports += Failure(node.location, reason)
        }

        /** Freeze slot identity including null slots; changing to another approved object still revokes. */
        fun refs(node: Node, label: String, read: () -> Array<Any?>, follow: (Any?) -> Boolean = { true }) {
            val original = read()
            check(original.size <= MAX_SEQUENCE)
            checks += {
                val current = read()
                current.size == original.size && original.indices.all { current[it] === original[it] }
            }
            original.forEachIndexed { index, value -> if (follow(value)) edge(node, value, "$label[$index]") }
        }

        /** A single identity slot is re-read on every check, without a temporary one-item array. */
        fun ref(node: Node, label: String, read: () -> Any?, follow: (Any?) -> Boolean = { true }) {
            val original = read()
            checks += { read() === original }
            if (follow(original)) edge(node, original, "$label[0]")
        }

        /** Only primitive values, immutable strings and enum constants; never localized/dynamic getters. */
        fun values(read: () -> Array<Any?>) {
            val original = read()
            checks += { read().contentEquals(original) }
        }

        fun sequence(node: Node, label: String, required: Boolean = false, dependency: Boolean = true,
            read: () -> Access.Sequence?) {
            val original = read()
            if (original == null) {
                validatorBackingProbes += { knownSequenceBacking(read()?.identity()) }
                checks += { read() == null }
                if (required) fail(node, "$label is not linked")
                return
            }
            check(original.size() <= MAX_SEQUENCE) { "Standard action closure sequence is too large: $label" }
            val identity = original.identity()
            // Source identity is immutable in final Sequence; current source is re-read
            // from a concrete Java field accessor for every qualification, never cached.
            val capturedShapeKnown = knownSequenceBacking(identity)
            validatorBackingProbes += { capturedShapeKnown && knownSequenceBacking(read()?.identity()) }
            val items = Array(original.size()) { original.get(it) }
            checks += {
                val current = read()
                current != null && current.identity() === identity && current.size() == items.size &&
                    items.indices.all { current.get(it) === items[it] }
            }
            items.forEachIndexed { index, item ->
                if (dependency) edge(node, item, "$label[$index]")
                else if (item != null) this.node(item, "${node.location}/$label[$index]")
            }
        }

        fun inspect(n: Node) {
            when (val value = n.value) {
                is CustomUnitConfig -> config(n, value)
                is UnitTypeEnum -> if (stock.keyFor(value) == null) fail(n, "Type is outside the frozen stock catalog")
                is UnitTypeReference -> reference(n, value)
                is UnitContainer -> sequence(n, "actions", true) { Access.actions(value) }
                is CustomAction -> {
                    if (value.javaClass != CustomAction::class.java) fail(n, "Unreviewed custom action subclass")
                    refs(n, "action", { arrayOf(value.actionDef, value.unitTypeReference) })
                    ref(n, "policy", { value.unitAction }, { false })
                }
                is CustomActionDef -> definition(n, value)
                is CustomUnitActionHandler -> {
                    if (value.javaClass != CustomUnitActionHandler::class.java) fail(n, "Unreviewed action handler subclass")
                    sequence(n, "linkedActions", true) { Access.linkedActions(value) }
                    Access.linkedActions(value)?.let { actions ->
                        if ((0 until actions.size()).any { actions.get(it)?.javaClass != CustomAction::class.java })
                            fail(n, "Linked trigger contains an unreviewed action implementation")
                    }
                }
                is UnitSpawner -> {
                    if (value.javaClass != UnitSpawner::class.java) fail(n, "Unreviewed unit spawner subclass")
                    sequence(n, "spawns") { Access.spawns(value) }
                }
                is SpawnConfig -> {
                    if (value.javaClass != SpawnConfig::class.java) fail(n, "Unreviewed spawn configuration subclass")
                    refs(n, "spawn", { Access.spawnEdges(value) }, ::isContentEdge)
                    if (Access.spawnType(value) == null) fail(n, "Spawn type is not linked")
                }
                is AttachmentSlotDefinition -> {
                    if (value.javaClass != AttachmentSlotDefinition::class.java) {
                        fail(n, "Unreviewed attachment slot subclass")
                        // Its a()/b() are virtual even though this denied node still has checks.
                        validatorReceiversKnown = false
                    }
                    refs(n, "attachment", { arrayOf(AuthoritativeAttachmentAccess.onCreate(value), value.N) }, ::isContentEdge)
                    values { arrayOf(value.a(), value.b(), value.o) }
                    val predicate = value.N
                    if (predicate?.javaClass == HasFlagBoolean::class.java) {
                        val flag = predicate as HasFlagBoolean
                        values { arrayOf(flag.flagId, flag.flagMask) }
                    }
                }
                is CustomProjectileTemplate -> projectile(n, value)
                is UnitSpawnList -> {
                    if (value.javaClass != UnitSpawnList::class.java) fail(n, "Unreviewed projectile spawn list subclass")
                    sequence(n, "projectileSpawns") { Access.projectileSpawns(value) }
                }
                is UnitSpawnEntry -> {
                    if (value.javaClass != UnitSpawnEntry::class.java) fail(n, "Unreviewed projectile spawn entry subclass")
                    ref(n, "projectileReference", { Access.projectileType(value) })
                    if (Access.projectileType(value) == null) fail(n, "Projectile spawn reference is not linked")
                }
                is CustomUnitCondition -> refs(n, "condition", { Access.conditionEdges(value) }, ::isContentEdge)
                is CustomEventBinding -> refs(n, "event", { Access.eventEdges(value) }, ::isContentEdge)
                is UnitPrice -> {
                    if (value.javaClass != UnitPrice::class.java) fail(n, "Unreviewed price implementation")
                    values { arrayOf(value.b, value.c.toRawBits(), value.d.toRawBits(), value.e.toRawBits(),
                        value.f, value.g, value.h, value.i, value.j) }
                    ref(n, "customResourcePrice", { value.k })
                }
                is StoredResources -> sequence(n, "resourceAmounts", true) { Access.sequence(value.b) }
                is StoredResourceEntry -> {
                    ref(n, "resourceIdentity", { value.a }, { false })
                    values { arrayOf(value.b.toRawBits()) }
                }
                is LogicAction -> logic(n, value)
                is AbstractUnitAction -> {
                    // These exact implementations' type getters only return their stored field.
                    if (value.javaClass == QueueUnitAction::class.java || value.javaClass == PlaceBuildingAction::class.java)
                        ref(n, "production", { value.unitType })
                    // Other built-in actions have no compiled content edge; adapter separately audits commands.
                }
                else -> fail(n, "Unknown compiled content node: ${value.javaClass.name}")
            }
        }

        fun config(n: Node, value: CustomUnitConfig) {
            if (value.javaClass != CustomUnitConfig::class.java || stock.keyFor(value) == null) {
                fail(n, "Configuration is outside the frozen stock identities")
                return
            }
            sequence(n, "references", true) { Access.references(value) }
            // Capture every menu action, but a denied manual action does not poison unrelated menu entries.
            // Lifecycle/production edges below remain dependencies of producing this configuration.
            sequence(n, "definitions", true, false) { Access.definitions(value) }
            sequence(n, "handlers", true, false) { Access.handlers(value) }
            sequence(n, "actionLists", true, false) { Access.actionLists(value) }
            sequence(n, "projectiles", true) { Access.projectiles(value) }
            sequence(n, "projectilesById") { Access.sequence(value.projectileTemplatesById) }
            sequence(n, "attachments", true) { Access.sequence(value.attachmentSlotDefinitions) }
            sequence(n, "conditions", true) { Access.conditions(value) }
            sequence(n, "frameConditions") { Access.frameConditions(value) }
            sequence(n, "fourFrameConditions") { Access.fourFrameConditions(value) }
            sequence(n, "eightFrameConditions") { Access.eightFrameConditions(value) }
            sequence(n, "events", true) { Access.events(value) }
            ref(n, "deathSpawn", { value.unitsSpawnedOnDeath })
        }

        fun reference(n: Node, value: UnitTypeReference) {
            if (value.javaClass !in setOf(UnitTypeReference::class.java, CustomUnitTypeReference::class.java,
                    CustomUnitProjectileReference::class.java)) fail(n, "Unreviewed type reference implementation")
            val type = Access.resolvedType(value)
            ref(n, "resolvedType", { Access.resolvedType(value) })
            if (type == null || stock.keyFor(type) == null) fail(n, "Type reference is unlinked or outside the frozen stock catalog")
            if (value is CustomUnitProjectileReference) {
                ref(n, "resolvedProjectile", { Access.resolvedProjectile(value) })
                if (Access.resolvedProjectile(value) == null) fail(n, "Projectile reference is not linked")
            }
        }

        /** One capture, followed by direct field reads; no per-check array or primitive boxing. */
        private class DefinitionSnapshot(value: CustomActionDef) {
            private val name: String? = value.name
            private val displayName: String? = value.displayName
            private val stringId: String? = value.stringId
            private val id = value.id
            private val queueType = value.queueType
            private val actionType = value.actionType
            private val buildType: String? = value.buildType
            private val displayType = value.displayType
            private val aiUse = value.aiUse
            private val addToBuildQueue = value.addToBuildQueue
            private val extraLagHidingInUI = value.extraLagHidingInUI
            private val isAlsoViewableByAllies = value.isAlsoViewableByAllies
            private val isAlsoViewableByEnemies = value.isAlsoViewableByEnemies
            private val hideInBuildMenu = value.hideInBuildMenu
            private val highPriorityQueue = value.highPriorityQueue
            private val onlyOneUnitAtATime = value.onlyOneUnitAtATime
            private val canPlayerCancel = value.canPlayerCancel
            private val allowMultipleInQueue = value.allowMultipleInQueue
            private val alwaysSinglePress = value.alwaysSinglePress
            private val hideQueueInterface = value.hideQueueInterface
            private val convertToKeepCurrentTags = value.convertToKeepCurrentTags
            private val buildSpeedBits = value.buildSpeed.toRawBits()
            private val buildSpeedIgnoreFactorySpeedModifiers = value.buildSpeedIgnoreFactorySpeedModifiers
            private val whenBuildingCannotMove = value.whenBuildingCannotMove
            private val techLevel = value.techLevel
            private val forceNano = value.forceNano
            private val displayRemainingStockpile = value.displayRemainingStockpile
            private val posBits = value.pos.toRawBits()
            private val rotatePresent: Boolean
            private val rotateBits: Int
            private val whenBuildingRotateToOrBackwards = value.whenBuildingRotateToOrBackwards
            private val whenBuildingRotateToWaitTillRotated = value.whenBuildingRotateToWaitTillRotated
            private val whenBuildingRotateToAimAtActionTarget = value.whenBuildingRotateToAimAtActionTarget
            private val energyPresent: Boolean
            private val energyBits: Int
            private val fireTurretAtGroundIndex: Int? = value.fireTurretAtGroundIndex
            private val fireTurretAtGroundCount = value.fireTurretAtGroundCount
            private val fireTurretAtGroundTerrainFilter = value.fireTurretAtGroundTerrainFilter

            init {
                val rotate = value.whenBuildingRotateTo
                rotatePresent = rotate != null
                rotateBits = rotate?.toRawBits() ?: 0
                val energy = value.addEnergy
                energyPresent = energy != null
                energyBits = energy?.toRawBits() ?: 0
            }

            fun matches(value: CustomActionDef): Boolean {
                val rotate = value.whenBuildingRotateTo
                val energy = value.addEnergy
                return value.name == name && value.displayName == displayName && value.stringId == stringId && value.id == id &&
                    value.queueType == queueType && value.actionType == actionType && value.buildType == buildType &&
                    value.displayType == displayType && value.aiUse == aiUse && value.addToBuildQueue == addToBuildQueue &&
                    value.extraLagHidingInUI == extraLagHidingInUI && value.isAlsoViewableByAllies == isAlsoViewableByAllies &&
                    value.isAlsoViewableByEnemies == isAlsoViewableByEnemies && value.hideInBuildMenu == hideInBuildMenu &&
                    value.highPriorityQueue == highPriorityQueue && value.onlyOneUnitAtATime == onlyOneUnitAtATime &&
                    value.canPlayerCancel == canPlayerCancel && value.allowMultipleInQueue == allowMultipleInQueue &&
                    value.alwaysSinglePress == alwaysSinglePress && value.hideQueueInterface == hideQueueInterface &&
                    value.convertToKeepCurrentTags == convertToKeepCurrentTags && value.buildSpeed.toRawBits() == buildSpeedBits &&
                    value.buildSpeedIgnoreFactorySpeedModifiers == buildSpeedIgnoreFactorySpeedModifiers &&
                    value.whenBuildingCannotMove == whenBuildingCannotMove && value.techLevel == techLevel &&
                    value.forceNano == forceNano && value.displayRemainingStockpile == displayRemainingStockpile &&
                    value.pos.toRawBits() == posBits && (rotate != null) == rotatePresent &&
                    (rotate == null || rotate.toRawBits() == rotateBits) &&
                    value.whenBuildingRotateToOrBackwards == whenBuildingRotateToOrBackwards &&
                    value.whenBuildingRotateToWaitTillRotated == whenBuildingRotateToWaitTillRotated &&
                    value.whenBuildingRotateToAimAtActionTarget == whenBuildingRotateToAimAtActionTarget &&
                    (energy != null) == energyPresent && (energy == null || energy.toRawBits() == energyBits) &&
                    value.fireTurretAtGroundIndex == fireTurretAtGroundIndex &&
                    value.fireTurretAtGroundCount == fireTurretAtGroundCount &&
                    value.fireTurretAtGroundTerrainFilter == fireTurretAtGroundTerrainFilter
            }
        }

        fun definition(n: Node, value: CustomActionDef) {
            if (value.javaClass != CustomActionDef::class.java) fail(n, "Unreviewed custom action definition subclass")
            val snapshot = DefinitionSnapshot(value)
            checks += { snapshot.matches(value) }
            refs(n, "definition", { arrayOf(value.convertTo, value.guiBuildUnit, value.aiConsiderSameAsBuilding,
                value.whenBuildingTemporarilyConvertTo, value.alsoTriggerAction, value.alsoQueueAction,
                value.whenBuildingTriggerAction, value.fireTurretAtGroundProjectile) })
            refs(n, "prices", { arrayOf(value.price, value.streamingCost, value.addResources,
                value.addResourcesScaledByAIHandicaps) })
            refs(n, "conditions", { arrayOf(value.isActive, value.isVisible, value.isLocked, value.isLockedAlt,
                value.isLockedAlt2, value.requireConditional, value.alsoTriggerOrQueueActionCondition,
                value.alsoTriggerOrQueueActionTarget, value.alsoTriggerActionRepeat, value.fireTurretAtGroundTarget) }, { false })
            sequence(n, "logicActions", true) { Access.sequence(value.logicActions) }
        }

        fun projectile(n: Node, value: CustomProjectileTemplate) {
            if (value.javaClass != CustomProjectileTemplate::class.java) fail(n, "Unreviewed projectile implementation")
            refs(n, "projectile", { arrayOf(value.customUnitConfig, value.spawnUnit, value.spawnProjectilesOnCreate,
                value.spawnProjectilesOnExplode, value.spawnProjectilesOnEndOfLife) })
            val owner = value.customUnitConfig
            if (owner == null) {
                // Parser 2086-2092 creates a per-config fallback (not a singleton) when there is no
                // projectile section. It registers it twice but does not assign customUnitConfig.
                val fallbackOwner = configs.singleOrNull { config ->
                    stock.keyFor(config) in PARSER_FALLBACK_KEYS && parserFallback(value, config)
                }
                if (fallbackOwner != null) {
                    // Explicit dependency declaration for this additional checker. The
                    // ordinary frozen owner's config sequence already covers this backing;
                    // this redundant probe keeps that local dependency visible to review.
                    // Access.projectiles is a static direct Java field read.
                    val capturedBackingKnown = knownSequenceBacking(Access.projectiles(fallbackOwner)?.identity())
                    validatorBackingProbes += { capturedBackingKnown && knownSequenceBacking(Access.projectiles(fallbackOwner)?.identity()) }
                    checks += { parserFallback(value, fallbackOwner) }
                    edge(n, fallbackOwner, "registeredFallbackOwner")
                    return
                }
            }
            if (owner == null || stock.keyFor(owner) == null ||
                Access.projectiles(owner)?.let { list -> (0 until list.size()).any { list.get(it) === value } } != true)
                fail(n, "Projectile is not registered on its frozen stock owner")
        }

        /** Exact registered fallback shape; a free-standing or spawning null-owner template is rejected. */
        fun parserFallback(value: CustomProjectileTemplate, owner: CustomUnitConfig): Boolean {
            val registered = Access.projectiles(owner) ?: return false
            val indexed = owner.projectileTemplatesById ?: return false
            return value.javaClass == CustomProjectileTemplate::class.java && value.customUnitConfig == null &&
                registered.size() == 1 && registered.get(0) === value && indexed.size == 1 && indexed[0] === value &&
                value.projectileId == 0 && value.projectileName == "1" && value.b == 10 && value.c == 0 &&
                value.spawnUnit == null && value.spawnProjectilesOnCreate == null &&
                value.spawnProjectilesOnExplode == null && value.spawnProjectilesOnEndOfLife == null &&
                value.trailEffect == null && value.effectOnCreate == null && value.explodeEffect == null &&
                value.explodeEffectOnShield == null && value.be == null && value.bf == null && value.bg == null &&
                value.unloadUpToXUnitsFromSource == 0 && !value.teleportSource && !value.convertHitToSourceTeam &&
                value.renderExtensionId == null && value.renderExtensionVariant == null &&
                value.projectileObserverId == null && value.projectileObserverVariant == null
        }

        fun logic(n: Node, value: LogicAction) {
            // Exact classes only: unknown logic must be explicitly reported, never silently authorized.
            if (value.javaClass !in REVIEWED_LOGIC) {
                fail(n, "Unreviewed logic handler: ${value.javaClass.name}")
                return
            }
            when (value) {
                is SpawnUnitAction -> refs(n, "spawnAction", { arrayOf(value.produceUnits, value.spawnUnits) })
                is AttachmentAction -> {
                    ref(n, "attachmentAction", { value.addNewUnits })
                    sequence(n, "attachmentSlots") { Access.sequence(value.onlyOnSlots) }
                }
                is TransportAction -> ref(n, "transportSpawn", { value.addUnitsIntoTransport })
                is WaypointAction -> refs(n, "waypoint", { arrayOf(value.waypointUnitType,
                    value.triggerActionIfFailed, value.triggerActionIfMatched) })
                is TakeResourcesAction -> refs(n, "resourceTriggers", { arrayOf(value.takeResources_triggerActionIfAnyCollected,
                    value.takeResources_triggerActionIfNoneCollected, value.takeResources_triggerActionForEach) })
                is UnitStatsAction -> ref(n, "cooldownActions", { AuthoritativeLogicAccess.cooldownActions(value) })
            }
        }

        fun isContentEdge(value: Any?): Boolean = value is UnitType || value is UnitTypeReference ||
            value is AbstractUnitAction || value is UnitSpawner || value is CustomUnitActionHandler || value is UnitPrice

        companion object {
            // These bundled configurations have no projectile section after their stock inheritance.
            // This is deliberately not permission for arbitrary null-owner projectile templates.
            private val PARSER_FALLBACK_KEYS = setOf("creditsCrates", "crystal_mid", "extractor", "extractorT2",
                "extractorT3", "extractorT3_overclocked", "extractorT3_reinforced", "flare_10s", "laboratory",
                "mechFactory", "mechFactoryT2", "modularSpider_emptySlot", "modularSpider_fabricator",
                "modularSpider_fabricatorT2", "modularSpider_laserdefense", "modularSpider_shieldGen",
                "modularSpider_speed", "modularSpider_speedIncomplete", "outpostT1", "outpostT2", "spyDrone")
            private val REVIEWED_LOGIC = setOf(SpawnUnitAction::class.java, AttachmentAction::class.java,
                TransportAction::class.java, WaypointAction::class.java, TakeResourcesAction::class.java,
                UnitStatsAction::class.java, AnimationAction::class.java, ConvertResourceAction::class.java,
                ResourceAction::class.java, MemoryAction::class.java, TagAction::class.java,
                MessageAction::class.java, SendMessageAction::class.java)
        }
    }
}
