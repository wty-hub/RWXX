package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.buildings.CommandCenter
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import java.security.SecureRandom
import java.util.IdentityHashMap

/**
 * A connection's projection of a server-owned world. Construct and call on the engine owner thread.
 * This intentionally never invokes the save, replay, unit-serialization or legacy network paths.
 */
class EngineViewProjector(private val engine: GameEngine, private val team: PlayerTeam,
    frozenPublicMap: EnginePublicMapSnapshot? = null,
    private val frozenStockCatalog: StockUnitCatalog? = null,
    private val frozenStockActions: StockActionClosure? = null,
    private val statusProvider: () -> MatchStatus = { EngineMatchStatus.current() },
    private val receiptLimit: Int = MAX_RECENT_VIEWS) {
    private val ownerThread = Thread.currentThread()
    private val handlesByUnit = IdentityHashMap<BaseUnit, Long>()
    private val unitsByHandle = HashMap<Long, BaseUnit>()
    private val recentViews = ArrayDeque<ViewReceipt>()
    private var nextHandle = SecureRandom().nextLong().ushr(2) + 1
    private var sequence = 0L
    private var lastIntentSequence = 0L
    private var publicMap = frozenPublicMap
    private val currentVision = OwnedSeatVision()
    private var terminalStatus: MatchStatus? = null
    private var eliminated = false
    private var lastPublishedFog: ByteArray? = null
    private var lastPublishedCredits: Double? = null
    private val actionCatalog = frozenStockCatalog?.let { EngineActionCatalog(engine, team, it, frozenStockActions) }
    private var emptyPanelSequence = 0L
    private var observationBatch: NativeObservationBatch? = null
    private var batchSelectedHandle: Long? = null

    internal fun dynamicSnapshotInBatch(batch: NativeObservationBatch, selected: Long?): PlayerViewSnapshot {
        checkOwner(); check(observationBatch == null)
        observationBatch = batch; batchSelectedHandle = selected
        try {
            // Registry eligibility does not qualify this held receiver: an old subclass getter
            // can restore itself into its slot before the ordinary identity check. Break the
            // group before that getter, then let the original path invoke it in its old order.
            if (team.javaClass !== GameTeam::class.java) batch.fallback()
            batch.beforeReady()
            return dynamicSnapshot()
        } finally { observationBatch = null; batchSelectedHandle = null }
    }

    init { require(receiptLimit in 1..MAX_RECENT_VIEWS) }

    private data class ViewReceipt(val sequence: Long, val tick: Int, val handles: Set<Long>)
    private data class DisclosedUnit(val source: BaseUnit, val view: ViewUnit)

    /** Borrowed owner-only identities from the last authorized snapshot; no world scan. */
    fun eventSubjects(): Collection<BaseUnit> {
        checkOwner()
        return java.util.Collections.unmodifiableCollection(unitsByHandle.values)
    }

    /** Grant-local only: no world flush, status evaluation, or retained actor callback. */
    internal fun hasNoRetainedActionDependencies(): Boolean {
        checkOwner()
        return actionCatalog?.hasNoRetainedActionDependencies() != false
    }

    /**
     * Qualify a private, already-disclosed selection together with every retained catalog root.
     * This does not validate visibility/control, flush the world, evaluate status, or create Owner.
     * The caller must dispose this descriptor before any callback or world mutation boundary.
     */
    internal fun nativeSyntheticTankObservationDependenciesOrNull(
        selectedHandle: Long?, lookup: StockUnitCatalog.NativeSightLookup,
    ): NativeObservationDependencies? = NativeObservationDependencies.capture(this, selectedHandle, lookup)

    internal class NativeObservationDependencies private constructor(
        private val projection: EngineViewProjector,
        private val sequence: Long,
        private val selectedHandle: Long?,
        private val selectedActor: OrderableUnit?,
        private val selectedShape: NativeSyntheticTankShape?,
        private val catalog: EngineActionCatalog.NativeTankDependencies,
    ) : AutoCloseable {
        private val owner = Thread.currentThread()
        private var valid = true

        fun stillCurrent(): Boolean {
            projection.checkOwner()
            if (!valid) return false
            if (projection.sequence != sequence ||
                selectedHandle != null && projection.unitsByHandle[selectedHandle] !== selectedActor ||
                selectedShape?.stillCurrent() == false || !catalog.stillCurrent()) {
                valid = false
            }
            return valid
        }

        override fun close() {
            // Cleanup must remain possible when a callback replaced the global engine instance.
            check(Thread.currentThread() === owner) { "Native observation descriptors require the engine owner" }
            valid = false
            catalog.close()
        }

        companion object {
            fun capture(projection: EngineViewProjector, selectedHandle: Long?,
                        lookup: StockUnitCatalog.NativeSightLookup): NativeObservationDependencies? {
                projection.checkOwner()
                val stock = projection.frozenStockCatalog ?: return null
                val catalog = projection.actionCatalog?.nativeSyntheticTankDependenciesOrNull(lookup) ?: return null
                val actor: OrderableUnit?
                val shape: NativeSyntheticTankShape?
                if (selectedHandle == null) {
                    actor = null; shape = null
                } else {
                    if (selectedHandle <= 0) { catalog.close(); return null }
                    actor = projection.unitsByHandle[selectedHandle] as? OrderableUnit
                    shape = actor?.let { NativeSyntheticTankShape.capture(stock, lookup, it, projection.team) }
                    if (shape == null) { catalog.close(); return null }
                }
                return NativeObservationDependencies(projection, projection.sequence, selectedHandle, actor, shape, catalog)
            }
        }
    }

    fun snapshot(): PlayerViewSnapshot = capture(includeStaticMap = true)

    /**
     * Owner-only authorized sample for a consumer that already owns the frozen public map.
     * Terrain is empty and mapVisual is null; dynamic fields and command receipts are unchanged.
     */
    fun dynamicSnapshot(): PlayerViewSnapshot = capture(includeStaticMap = false)

    private fun capture(includeStaticMap: Boolean): PlayerViewSnapshot {
        checkOwner()
        val map = requireReadyWorld()
        val status = matchStatus()
        if (status.phase == MatchPhase.FINISHED || eliminated) return terminalSnapshot(map, status, includeStaticMap)
        val fog = permittedFog(map)
        // The second flush remains outside the scope: a deferred remove can invoke a mod's equals.
        // Revalidate after that flush; this token never crosses a status/action/command callback.
        observationBatch?.beforeFlush()
        val world = BaseUnit.getGlobalUnitList()
        val lookup = if (frozenStockCatalog != null && (observationBatch?.hasKnownWorld(world) == true || world.all { unit ->
            unit != null && (unit.javaClass === CustomUnit::class.java ||
                unit.javaClass === CommandCenter::class.java || unit.javaClass === BuilderUnit::class.java)
        })) frozenStockCatalog.openNativeSightLookupOrNull() else null
        val visible = try {
            val facts = observationBatch?.forDisclosure(world)
            if (lookup != null && facts != null) facts.disclose(team, fog).map { DisclosedUnit(it.source, it.view) }
            else world.mapNotNull { unit ->
                if (lookup != null && cullHiddenNativeUnit(unit, map, fog, lookup)) null
                else if (!mayDisclose(unit, map, fog, lookup)) null else DisclosedUnit(unit, ViewUnit(
                    0, checkNotNull(standardTypeName(unit.r(), lookup)), unit.team.teamId,
                    unit.posX, unit.posY, unit.posZ, unit.rotationSpeed,
                    unit.currentHealth, unit.maxHealth, unit.buildProgress, mayControl(unit),
                    (unit as? OrderableUnit)?.upgradeLevel ?: 1,
                ))
            }
        } finally {
            // Exact native u/t/control/tech/visibility getters and final PlayerTeam.c read only fields
            // or constants. Do not keep this lookup for handle processing, revokeUnless or callers.
            lookup?.close()
        }
        val present = java.util.Collections.newSetFromMap(IdentityHashMap<BaseUnit, Boolean>())
        visible.forEach { present.add(it.source) }
        val old = handlesByUnit.entries.iterator()
        while (old.hasNext()) {
            val entry = old.next()
            if (!present.contains(entry.key)) {
                unitsByHandle.remove(entry.value)
                old.remove()
            }
        }
        // Hidden removals can reorder the engine's global list. Assign new capabilities using
        // only already-authorized public values; never let that list order or global IDs decide.
        // Fully equal public records are indistinguishable in this frame, regardless of tie order.
        visible.filter { it.source !in handlesByUnit }.sortedWith(PUBLIC_UNIT_ORDER).forEach { disclosed ->
            val handle = allocateHandle()
            handlesByUnit[disclosed.source] = handle
            unitsByHandle[handle] = disclosed.source
        }
        val units = visible.map { it.view.copy(handle = checkNotNull(handlesByUnit[it.source])) }.sortedBy { it.handle }
        revokeInvalidActions(map)
        check(sequence < Long.MAX_VALUE) { "View sequence exhausted" }
        sequence++
        recentViews.addLast(ViewReceipt(sequence, engine.currentTick, unitsByHandle.keys.toSet()))
        while (recentViews.size > receiptLimit) recentViews.removeFirst()
        val fogBytes = fog.copyBytes()
        lastPublishedFog = fogBytes.copyOf()
        lastPublishedCredits = team.credits
        val frozenMap = publicMap(map)
        return PlayerViewSnapshot(
            sequence, engine.currentTick, team.teamId, team.credits,
            map.tileCountX, map.tileCountY, map.tileWorldSizeX, map.tileWorldSizeY,
            fogBytes, units, if (includeStaticMap) frozenMap.terrainCopy() else NO_STATIC_TERRAIN,
            status, if (includeStaticMap) frozenMap.visualCopy() else null,
        )
    }

    /** The outcome is permanent for this connection, even if legacy state is later reset. */
    fun matchStatus(): MatchStatus {
        checkOwner()
        terminalStatus?.let { return it }
        return statusProvider().also { status ->
            if (status.phase == MatchPhase.FINISHED || team.teamId in status.defeatedTeamIds) {
                if (status.phase == MatchPhase.FINISHED) terminalStatus = status
                eliminated = true
                handlesByUnit.clear()
                unitsByHandle.clear()
                recentViews.clear()
                currentVision.reset()
                actionCatalog?.clear()
            }
        }
    }

    private fun terminalSnapshot(map: TileMap, status: MatchStatus, includeStaticMap: Boolean): PlayerViewSnapshot {
        check(sequence < Long.MAX_VALUE) { "View sequence exhausted" }
        sequence++
        // Elimination clears the legacy fog. Do not read it, or recompute vision from survivors.
        // A connection opened after the outcome has no prior authorized history to replay.
        val fog = lastPublishedFog?.copyOf() ?: ByteArray(map.tileCountX * map.tileCountY) { 10 }
        val frozenMap = publicMap(map)
        return PlayerViewSnapshot(sequence, engine.currentTick, team.teamId, lastPublishedCredits ?: team.credits,
            map.tileCountX, map.tileCountY, map.tileWorldSizeX, map.tileWorldSizeY,
            fog, emptyList(), if (includeStaticMap) frozenMap.terrainCopy() else NO_STATIC_TERRAIN,
            status, if (includeStaticMap) frozenMap.visualCopy() else null)
    }

    /** Invalid commands have one generic result; they never report whether an unknown target exists. */
    fun apply(intent: ViewUnitOrder, queued: Boolean = false): Boolean {
        checkOwner()
        if (intent.sequence <= lastIntentSequence) return false
        lastIntentSequence = intent.sequence
        val map = runCatching { requireReadyWorld() }.getOrNull() ?: return false
        if (matchStatus().phase == MatchPhase.FINISHED) return false
        if (runCatching { requireFog(map) }.isFailure) return false
        val view = recentViews.firstOrNull { it.sequence == intent.viewSequence } ?: return false
        val age = engine.currentTick.toLong() - view.tick.toLong()
        if (age !in 0..MAX_VIEW_AGE_TICKS.toLong()) return false
        if (intent.unitHandles.isEmpty() || intent.unitHandles.size > ViewLimits.MAX_SELECTED_UNITS
            || intent.unitHandles.toSet().size != intent.unitHandles.size
            || !intent.x.isFinite() || !intent.y.isFinite()) return false
        val validTargetKind = when (intent.kind) {
            OrderKind.MOVE, OrderKind.ATTACK_MOVE -> intent.target is CommandTarget.Point
            OrderKind.STOP -> intent.target === CommandTarget.None
            OrderKind.ATTACK_UNIT -> intent.target is CommandTarget.Unit
        }
        if (!validTargetKind) return false
        val targetForSight = if (intent.kind == OrderKind.ATTACK_UNIT) {
            if (intent.targetHandle !in view.handles) return false
            unitsByHandle[intent.targetHandle] ?: return false
        } else null
        // Own control does not observe terrain. Only an actual foreign target needs current LOS;
        // never borrow a published grid or carry authorization across another request.
        val fog = if (targetForSight != null && targetForSight.team !== team)
            runCatching { permittedFog(map) }.getOrNull() ?: return false else null
        revokeInvalidActions(map)
        val selected = ArrayList<OrderableUnit>(intent.unitHandles.size)
        for (handle in intent.unitHandles) {
            if (handle !in view.handles) return false
            val unit = unitsByHandle[handle] ?: return false
            if (!mayDisclose(unit, map, null) || !mayControl(unit)) return false
            selected.add(unit as OrderableUnit)
        }
        val target = if (intent.kind == OrderKind.ATTACK_UNIT) {
            if (intent.targetHandle !in view.handles) return false
            val unit = unitsByHandle[intent.targetHandle] ?: return false
            if (!mayDisclose(unit, map, fog) || !team.c(unit.team) || unit.isUnselectableAsTarget) return false
            unit
        } else {
            if (intent.targetHandle != 0L) return false
            null
        }
        // Point orders remain legal in unexplored/hidden terrain. Visibility applies to entity handles.
        val pointOrder = intent.target is CommandTarget.Point
        if (pointOrder
            && !pointInMap(intent.x, intent.y, map)) return false
        if (!pointOrder && (intent.x != 0f || intent.y != 0f)) return false

        val command = engine.commandController.createCommand()
        command.team = team
        command.sourceTeam = team
        command.createdTick = engine.gameTimeMillis
        command.isQueued = queued
        selected.forEach(command::addUnitToCommand)
        when (intent.kind) {
            OrderKind.MOVE -> command.setMoveTarget(intent.x, intent.y)
            OrderKind.ATTACK_MOVE -> command.setAttackMoveTarget(intent.x, intent.y)
            OrderKind.STOP -> command.setClearExistingOrders()
            OrderKind.ATTACK_UNIT -> command.setAttackTarget(target)
        }
        if (!command.prepareAndValidateCommand()) return false
        engine.commandController.pendingCommands.add(command)
        return true
    }

    /** Queries authorize one currently controlled unit, independently of the command sequence. */
    fun actionPanel(request: ViewActionPanelQuery): PlayerActionPanel {
        checkOwner()
        val catalog = actionCatalog
        if (catalog == null) {
            check(emptyPanelSequence < Long.MAX_VALUE) { "Action panel sequence exhausted" }
            return PlayerActionPanel(request.querySequence, ++emptyPanelSequence,
                request.viewSequence, team.teamId, 0, emptyList())
        }
        // Clearing a selection cannot grant an entity or action capability. Keep the catalog's
        // sequence/replay and revocation semantics without inspecting or rebuilding world sight.
        if (request.unitHandle == 0L) return catalog.panel(request, null)
        val map = runCatching { requireReadyWorld().also(::requireFog) }.getOrNull()
        val view = freshView(request.viewSequence)
        if (map != null) revokeInvalidActions(map)
        // Selection exposes only a currently controlled owner and grants no terrain exploration.
        val actor = if (map == null || view == null || engine.currentTick <= 0 ||
            matchStatus().phase == MatchPhase.FINISHED || request.unitHandle !in view.handles) null
        else unitsByHandle[request.unitHandle]?.takeIf { mayControl(it) && mayDisclose(it, map, null) } as? OrderableUnit
        return catalog.panel(request, actor)
    }

    /** An action receipt and a current world receipt authorize different things; neither replaces the other. */
    fun applyAction(intent: ViewActionCommand): Boolean {
        checkOwner()
        if (intent.sequence <= lastIntentSequence) return false
        lastIntentSequence = intent.sequence
        val catalog = actionCatalog ?: return false
        val map = runCatching { requireReadyWorld() }.getOrNull() ?: return false
        if (engine.currentTick <= 0 || matchStatus().phase == MatchPhase.FINISHED) return false
        if (runCatching { requireFog(map) }.isFailure) return false
        val view = freshView(intent.viewSequence) ?: return false
        if (intent.unitHandle !in view.handles || !pointInMap(intent.x, intent.y, map)) return false
        val targetForSight = if (intent.targetHandle == 0L) null else {
            if (intent.targetHandle !in view.handles) return false
            unitsByHandle[intent.targetHandle] ?: return false
        }
        val fog = if (targetForSight != null && targetForSight.team !== team)
            runCatching { permittedFog(map) }.getOrNull() ?: return false else null
        revokeInvalidActions(map)
        val actor = unitsByHandle[intent.unitHandle]?.takeIf { mayControl(it) && mayDisclose(it, map, null) }
            as? OrderableUnit ?: return false
        val target = if (intent.targetHandle == 0L) null else {
            if (intent.targetHandle !in view.handles) return false
            unitsByHandle[intent.targetHandle]?.takeIf { mayDisclose(it, map, fog) } ?: return false
        }
        return catalog.execute(actor, intent, target)
    }

    private fun freshView(sequence: Long): ViewReceipt? = recentViews.firstOrNull { it.sequence == sequence }
        ?.takeIf { engine.currentTick.toLong() - it.tick in 0..MAX_VIEW_AGE_TICKS.toLong() }

    private fun revokeInvalidActions(map: TileMap) {
        actionCatalog?.revokeUnless { unit ->
            handlesByUnit.containsKey(unit) && mayControl(unit) && mayDisclose(unit, map, null)
        }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === ownerThread) { "Player views require the engine owner thread" }
        check(GameEngine.getInstance() === engine) { "Player view belongs to another engine" }
    }

    private fun requireReadyWorld(): TileMap {
        check(team.teamId in 0..63 && !team.isSpectatorTeamColor && PlayerTeam.k(team.teamId) === team) {
            "Player view has no current playable seat"
        }
        check(!engine.networkEngine.networkGameActive) { "Authoritative views cannot use legacy lockstep" }
        check(engine.networkEngine.roomSettings.fogMode == 2 && !engine.networkEngine.roomSettings.sharedControl) {
            "Authoritative prototype requires dynamic fog and no shared control"
        }
        val map = checkNotNull(engine.tileMap) { "Map is not ready" }
        publicMap?.requireMap(map)
        check(map.fogEnabled && map.fogPeriodicMaintenanceEnabled) { "Dynamic fog is not ready" }
        check(map.tileCountX > 0 && map.tileCountY > 0
            && map.tileCountX.toLong() * map.tileCountY <= MAX_MAP_CELLS
            && map.tileWorldSizeX > 0 && map.tileWorldSizeY > 0) { "Invalid map dimensions" }
        check(team.credits.isFinite()) { "Invalid player resources" }
        frozenStockCatalog?.assertValid()
        frozenStockActions?.assertValid()
        return map
    }

    private fun requireFog(map: TileMap) {
        val fog = checkNotNull(team.fogOfWarData) { "Player fog is not ready" }
        // Native cells are not authorization inputs: only the separately rebuilt currentVision
        // supplies disclosure. Check readiness, not unused cell values or legacy reveal state.
        check(fog.size == map.tileCountX && fog.all { column ->
            column != null && column.size == map.tileCountY
        }) { "Player fog is incomplete" }
    }

    /** Borrowed only until this owner's next fog calculation; private callers synchronously read it. */
    private fun permittedFog(map: TileMap): OwnedSeatVision.Grid {
        requireFog(map)
        // Keep exploration earned by this seat, never the legacy all-map reveal after defeat/surrender.
        currentVision.configure(map.tileCountX, map.tileCountY, engine.networkEngine.roomSettings.revealedMap)
        currentVision.beginFrame()
        // This call flushes deferred world operations and may invoke equals on a removed mod object.
        // Keep this same final native list: flush and the getClass-only prescan precede validation.
        observationBatch?.beforeFlush()
        val world = BaseUnit.getGlobalUnitList()
        val sightLookup = if (frozenStockCatalog != null && (observationBatch?.hasKnownWorld(world) == true || world.all { unit ->
            unit != null && (unit.javaClass === CustomUnit::class.java ||
                unit.javaClass === CommandCenter::class.java || unit.javaClass === BuilderUnit::class.java)
        })) frozenStockCatalog.openNativeSightLookupOrNull() else null
        try {
            val facts = observationBatch?.forFog(world, map, sightLookup, this, batchSelectedHandle)
            if (facts != null) facts.reveal(team, currentVision)
            else for (unit in world) {
                if (unit !is OrderableUnit) continue
                // Preserve the original nonnull r() read even for a foreign malformed native unit.
                // Only the validated exact-class scope may skip its immutable association lookup.
                // Unknown classes/containers retain their original type-before-team fallback.
                val typeName = if (sightLookup != null) {
                    val type: UnitType = unit.r()
                    if (unit.team !== team) continue
                    sightLookup.keyFor(type)
                } else standardTypeName(unit.r())
                if (typeName == null || unit.team !== team
                    || unit.isDead || unit.isDestroyed || unit.transportContainer != null || unit.parentEntity != null
                    || (unit is CustomUnit && unit.unitConfig.isVisible != null)
                    || !pointInMap(unit.posX, unit.posY, map)) continue
                // Exact CustomUnit.r/s, CommandCenter.r/s and BuilderUnit.r/inherited s are pure
                // field/constant reads. Do not add status, actions, world flushes or unknown getters here.
                // Vanilla OrderableUnit.c(boolean) also grants vision while under construction.
                // FogRevealer is included by the fallback, but never in the disclosed entity list.
                val radius = if (unit is CustomUnit && unit.buildProgress < 1f && unit.unitConfig.fogOfWarSightRangeWhileNotBuilt >= 0)
                    unit.unitConfig.fogOfWarSightRangeWhileNotBuilt else unit.s()
                if (radius > 0) currentVision.reveal(
                    unit.posX / map.tileWorldSizeX, unit.posY / map.tileWorldSizeY, radius)
            }
        } finally {
            sightLookup?.close()
        }
        // Never expose this grid in a snapshot, receipt, event scope or callback retained by a caller.
        // Capture copies it into a new array before returning; later calls still recompute all sight.
        return currentVision.grid()
    }

    /** A negative-only prefilter inside the exact-class, callback-free capture scope. */
    private fun cullHiddenNativeUnit(unit: BaseUnit, map: TileMap, fog: OwnedSeatVision.Grid,
                                    lookup: StockUnitCatalog.NativeSightLookup): Boolean {
        // Keep the original type rejection/null-type failure before inspecting other fields.
        if (standardTypeName(unit.r(), lookup) == null) return false
        val owner = unit.team ?: return false
        if (owner.teamId < -2 || owner === team || !pointInMap(unit.posX, unit.posY, map)) return false
        // Enemy visibility can dereference y after earlier exclusions. Re-run the whole original
        // predicate for malformed stats so dead/cargo/NaN cases keep their original short circuit.
        if (unit is CustomUnit && unit.y == null) return false
        val x = (unit.posX / map.tileWorldSizeX).toInt()
        val y = (unit.posY / map.tileWorldSizeY).toInt()
        // Do not introduce a fog exception before the original physical/value checks.
        if (x !in 0 until fog.width || y !in 0 until fog.height) return false
        return fog.valueAt(x, y) >= 5
    }

    private fun mayDisclose(unit: BaseUnit, map: TileMap, fog: OwnedSeatVision.Grid?,
        lookup: StockUnitCatalog.NativeSightLookup? = null): Boolean {
        if (standardTypeName(unit.r(), lookup) == null || unit.team == null || unit.team.teamId < -2
            || unit.isDead || unit.isDestroyed || unit.u() || unit.t()
            || unit.transportContainer != null || unit.parentEntity != null
            || unit.attachmentData != null || !pointInMap(unit.posX, unit.posY, map)
            || !unit.posZ.isFinite() || !unit.rotationSpeed.isFinite()
            || !unit.currentHealth.isFinite() || unit.currentHealth < 0f
            || !unit.maxHealth.isFinite() || unit.maxHealth <= 0f
            || !unit.buildProgress.isFinite() || unit.buildProgress !in 0f..1f) return false
        if (unit is OrderableUnit && unit.upgradeLevel !in 1..3) return false
        // Audited stock has no unit-level visibility expressions. Never turn an arbitrary world
        // predicate into entity membership, even when a fixture uses the limited native catalog.
        if (unit is CustomUnit && unit.unitConfig.isVisible != null) return false
        if (unit.team === team) return true
        // A callback may have changed an owner to another team. A control-only check cannot
        // promote that object to a visible foreign target without an actual sight calculation.
        val currentFog = fog ?: return false
        if (team.c(unit.team) && !unit.isVisibleToEnemies) return false
        val x = (unit.posX / map.tileWorldSizeX).toInt()
        val y = (unit.posY / map.tileWorldSizeY).toInt()
        // BaseUnit.d(team) reads legacy fog, which can be revealed by another team's surrender.
        // Vanilla units have no per-team override; only this seat's independent sight authorizes disclosure.
        return currentFog.valueAt(x, y) < 5
    }

    private fun mayControl(unit: BaseUnit): Boolean = unit is OrderableUnit && unit.team === team
        && unit.buildProgress >= 1f && !unit.canNotBeGivenOrdersByPlayer() && !unit.u() && !unit.t()
        && unit.team === team

    private fun standardTypeName(type: UnitType): String? = standardTypeName(type, null)

    private fun standardTypeName(type: UnitType, lookup: StockUnitCatalog.NativeSightLookup?): String? {
        if (lookup != null) return lookup.keyFor(type)
        frozenStockCatalog?.let { return it.keyFor(type) }
        // Native-only fixtures have no custom content authority. All production stock identities
        // come from the frozen catalog, shared with action capability validation.
        return (type as? UnitTypeEnum)?.name
    }

    private fun pointInMap(x: Float, y: Float, map: TileMap): Boolean = x.isFinite() && y.isFinite()
        && x >= 0f && y >= 0f && x.toDouble() < map.tileCountX.toDouble() * map.tileWorldSizeX
        && y.toDouble() < map.tileCountY.toDouble() * map.tileWorldSizeY

    private fun allocateHandle(): Long {
        check(nextHandle < Long.MAX_VALUE) { "View handle space exhausted" }
        return nextHandle++
    }

    private fun publicMap(map: TileMap): EnginePublicMapSnapshot =
        publicMap ?: EnginePublicMapSnapshot.captureTerrainOnly(map).also { publicMap = it }

    private companion object {
        val NO_STATIC_TERRAIN = ByteArray(0)
        val PUBLIC_UNIT_ORDER = compareBy<DisclosedUnit> { it.view.typeName }
            .thenBy { it.view.teamId }
            .thenBy { it.view.x }.thenBy { it.view.y }
            .thenBy { it.view.height }.thenBy { it.view.heading }
            .thenBy { it.view.health }.thenBy { it.view.maxHealth }
            .thenBy { it.view.buildProgress }.thenBy { it.view.upgradeLevel }
            .thenBy { it.view.controllable }
        const val MAX_MAP_CELLS = 1024 * 1024
        const val MAX_RECENT_VIEWS = 32
        const val MAX_VIEW_AGE_TICKS = 180
    }
}
