package io.github.rwx.net.timeline

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.net.authoritative.*
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode

/** Engine-owner boundary. Everything returned here is detached, authorized presentation data. */
class EngineTimelineProjector(
    private val engine: GameEngine,
    private val team: PlayerTeam,
    publicMap: EnginePublicMapSnapshot,
    stock: StockUnitCatalog,
    actions: StockActionClosure,
    status: () -> MatchStatus,
) {
    private val owner = Thread.currentThread()
    private val projection = EngineViewProjector(engine, team, publicMap, stock, actions, status, receiptLimit = 1)
    private var view: PlayerViewSnapshot? = null
    private var panel: PlayerActionPanel? = null
    private var selection = emptyList<Long>()
    private var query = 0L
    private var command = 0L
    private var finishedAt: Long? = null
    private var lastObservation: TimelineObservation? = null
    private var finalFenceEmitted = false
    private var nativeRoomSource: TimelineRoomRosterSource? = null
    private var observationBatch: NativeObservationBatch? = null

    /** Called only by the closed native-room factory's owner pass. Generic status stays ordinary. */
    internal fun observeInBatch(batch: NativeObservationBatch,
                                events: List<TimelineObservedEvent> = emptyList()): TimelineObservation {
        checkOwner(); check(observationBatch == null)
        batch.beginGrant(selection.size <= 1)
        var success = false
        try {
            if (nativeRoomSource == null || !batch.belongsTo(nativeRoomSource)) {
                batch.fallback()
                return observe(events).also { success = true }
            }
            observationBatch = batch
            return observe(events).also { success = true }
        } finally {
            observationBatch = null
            batch.finishGrant(success)
        }
    }

    /**
     * Empty-selection prerequisite for this grant only, not a certificate for a shared world read.
     * Old catalog owners/capabilities/receipts must also be absent; they may reference units that
     * are no longer in the world. False is observational and does not clear or revoke any state.
     */
    internal fun hasEmptyLocalObservationDependencies(): Boolean {
        checkOwner()
        return selection.isEmpty() && projection.hasNoRetainedActionDependencies()
    }

    /**
     * Includes the current private Timeline selection, even before the next panel creates Owner.
     * Multi-selection falls back as a whole; no member is dropped or interpreted as an empty panel.
     * This is one local prerequisite, not a world-purity or current-authority certificate.
     */
    internal fun nativeSyntheticTankObservationDependenciesOrNull(
        lookup: StockUnitCatalog.NativeSightLookup,
    ): NativeObservationDependencies? = NativeObservationDependencies.capture(this, lookup)

    internal class NativeObservationDependencies private constructor(
        private val projection: EngineTimelineProjector,
        private val selection: List<Long>,
        private val view: EngineViewProjector.NativeObservationDependencies,
    ) : AutoCloseable {
        private val owner = Thread.currentThread()
        private var valid = true

        fun stillCurrent(): Boolean {
            projection.checkOwner()
            check(GameEngine.getInstance() === projection.engine) { "Engine projection instance changed" }
            if (!valid) return false
            if (projection.selection !== selection || !view.stillCurrent()) valid = false
            return valid
        }

        override fun close() {
            check(Thread.currentThread() === owner) { "Native observation descriptors require the engine owner" }
            valid = false
            view.close()
        }

        companion object {
            fun capture(projection: EngineTimelineProjector,
                        lookup: StockUnitCatalog.NativeSightLookup): NativeObservationDependencies? {
                projection.checkOwner()
                check(GameEngine.getInstance() === projection.engine) { "Engine projection instance changed" }
                val selection = projection.selection
                if (selection.size > 1) return null
                val view = projection.projection.nativeSyntheticTankObservationDependenciesOrNull(
                    selection.singleOrNull(), lookup) ?: return null
                return NativeObservationDependencies(projection, selection, view)
            }
        }
    }

    /** Capture after every simulation step; publication and file/socket work happen elsewhere. */
    fun observe(events: List<TimelineObservedEvent> = emptyList()): TimelineObservation {
        checkOwner()
        check(!finalFenceEmitted) { "The final observation fence was already emitted" }
        val current = observationBatch?.takeIf { it.isEligible() }?.let { projection.dynamicSnapshotInBatch(it, selection.singleOrNull()) }
            ?: projection.dynamicSnapshot()
        view = current
        val time = current.tick.toLong() * TimelineProtocol.SUBDIVISIONS
        if (current.matchStatus.phase == MatchPhase.FINISHED && finishedAt == null) finishedAt = time
        val controlled = current.units.filter { it.controllable }.mapTo(HashSet()) { it.handle }
        selection = selection.filter { it in controlled }
        check(query < Long.MAX_VALUE) { "Selection query sequence exhausted" }
        panel = projection.actionPanel(ViewActionPanelQuery(++query, current.sequence,
            selection.singleOrNull() ?: 0))
        // This one-slot memo lives only inside this detached observation. Native callbacks
        // have finished; equal raw Float bits must use the identical original decimal result.
        var headingValid = false
        var headingBits = 0
        var headingQuantized = 0L
        val units = current.units.map { unit ->
            TimelineObservedUnit(unit.handle,
                TimelineValue.Vec3(position(unit.x, current.mapWidth.toLong() * current.tileWidth),
                    position(unit.y, current.mapHeight.toLong() * current.tileHeight), quantize(unit.height, 256)),
                run {
                    val bits = unit.heading.toRawBits()
                    if (!headingValid || headingBits != bits) {
                        headingQuantized = quantize(((unit.heading.toDouble() % 360 + 360) % 360), 65536.0 / 360) % 65536
                        headingBits = bits
                        headingValid = true
                    }
                    headingQuantized
                },
                quantize(unit.health, 256), quantize(unit.maxHealth, 256),
                progress(unit.buildProgress), unit.teamId, unit.typeName, unit.upgradeLevel, unit.controllable)
        }
        val observedActions = panel!!.actions.map { action ->
            TimelineObservedAction(action.handle, ability(action), when (action.readiness) {
                ActionReadiness.BLOCKED -> 0; ActionReadiness.READY -> 1; ActionReadiness.UNKNOWN -> 2
            },
                if (action.queuedCount < 0) UNKNOWN_COUNT else action.queuedCount.toLong(),
                if (action.stockpileCount < 0) UNKNOWN_COUNT else action.stockpileCount.toLong(),
                action.progress.takeIf { it >= 0f }?.let(::progress), action.canCancel)
        }
        // The owner still computes current authorized fog. Reuse only after comparing every
        // byte with the preceding immutable observation, never by tick, receipt or array identity.
        val fog = lastObservation?.fog?.takeIf { it.contentEquals(current.fog) } ?: TimelineBytes(current.fog)
        return TimelineObservation(time, quantize(current.credits, 1024),
            match(current.matchStatus, finishedAt ?: 0), fog, units,
            selectedUnitKey = selection.singleOrNull(), actions = observedActions, events = events, selectedUnitKeys = selection)
            .also { lastObservation = it }
    }

    /** Freeze the preceding authorized view before the engine starts this step. */
    fun beginStepEvents(grant: TimelineEngineEvents.Grant) {
        checkOwner()
        val previous = checkNotNull(lastObservation) { "Capture the initial authorized observation first" }
        val status = checkNotNull(view).matchStatus
        if (status.phase == MatchPhase.FINISHED || team.teamId in status.defeatedTeamIds) return
        grant.authorizeStep(previous.fog, projection.eventSubjects())
    }

    /** Offer the terminal observation first, then this exclusive fence, then finish the session. */
    fun finalObservationFence(): TimelineObservation {
        checkOwner()
        check(!finalFenceEmitted) { "The final observation fence was already emitted" }
        val last = checkNotNull(lastObservation) { "No terminal observation has been captured" }
        check(finishedAt == last.time && view?.matchStatus?.phase == MatchPhase.FINISHED) { "Capture the fixed terminal observation first" }
        check(last.time < TimelineProtocol.MAX_TIME) { "Final observation time exhausted" }
        finalFenceEmitted = true
        return TimelineObservation(last.time + 1, last.creditsQ, last.matchStatus, last.fog, last.units,
            last.selectedUnitKey, last.actions, emptyList(), last.selectedUnitKeys)
    }

    /** Transport has checked its receipt; the engine still rechecks present ownership and sight. */
    fun apply(request: TimelineRequest, unitKey: (Long) -> Long?, actionKey: (Long) -> Long?): Boolean {
        checkOwner()
        if (request.teamId != team.teamId) return false
        val current = view ?: return false
        val frame = request.frame
        val r = frame.body
        val wireSelection = TimelineMessages.selection(r.bytes(if (frame.type == 6) 5 else 7), frame.type == 6)
        val selected = wireSelection.map { unitKey(it) ?: return false }
        if (frame.type == 6) {
            val controlled = current.units.filter { it.controllable }.mapTo(HashSet()) { it.handle }
            selection = if (selected.all { it in controlled }) selected else emptyList()
            return true
        }
        if (frame.type != 5) return false
        val t = r.record(9)
        val target = when (t.number(1)) {
            0L -> CommandTarget.None
            1L -> CommandTarget.Point((t.number(2).toDouble() / 256).toFloat(), (t.number(3).toDouble() / 256).toFloat())
            2L -> CommandTarget.Unit(unitKey(t.number(4)) ?: return false)
            else -> return false
        }
        check(command < Long.MAX_VALUE) { "Command sequence exhausted" }
        val sequence = ++command
        val flags = r.number(10)
        return when (r.number(6)) {
            1L, 2L, 3L, 4L -> projection.apply(ViewUnitOrder(sequence, current.sequence,
                OrderKind.entries[(r.number(6) - 1).toInt()], selected, target), queued = flags and 1L != 0L)
            5L -> {
                val activePanel = panel ?: return false
                val action = actionKey(r.number(8)) ?: return false
                if (selected.singleOrNull() != activePanel.unitHandle) return false
                projection.applyAction(ViewActionCommand(sequence, current.sequence, activePanel.panelSequence,
                    activePanel.unitHandle, action, target, flags and 1L != 0L, flags and 2L != 0L))
            }
            else -> false
        }
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Engine projection requires the owner thread" }

    companion object {
        /** Closed roster/status binding; no arbitrary status callback is marked as pure. */
        internal fun forNativeRoom(engine: GameEngine, team: PlayerTeam, publicMap: EnginePublicMapSnapshot,
                                   stock: StockUnitCatalog, actions: StockActionClosure,
                                   source: TimelineRoomRosterSource): EngineTimelineProjector {
            check(source.hasNativeProvenance)
            return EngineTimelineProjector(engine, team, publicMap, stock, actions, source::current)
                .also { it.nativeRoomSource = source }
        }

        // Profile queue/stockpile sentinel; zero is a known empty queue.
        private const val UNKNOWN_COUNT = -1L

        fun mapRecord(engine: GameEngine, map: EnginePublicMapSnapshot): TimelineRecord {
            val dimensions = engine.tileMap
            val terrain = map.terrainCopy()
            val visual = checkNotNull(map.visualCopy()) { "A public visual map is required" }
            fun plane(values: IntArray): TimelineBytes {
                val out = ByteArray(values.size * 2)
                values.forEachIndexed { i, value -> out[i * 2] = (value ushr 8).toByte(); out[i * 2 + 1] = value.toByte() }
                return TimelineBytes(out)
            }
            val pools = ByteArrayOutputStream()
            TimelineBinary.writeVUInt(pools, terrain.count { it.toInt() and 4 != 0 }.toLong())
            terrain.forEachIndexed { i, value -> if (value.toInt() and 4 != 0) {
                TimelineBinary.writeVUInt(pools, (i % dimensions.tileCountX).toLong())
                TimelineBinary.writeVUInt(pools, (i / dimensions.tileCountX).toLong())
            } }
            return TimelineRecord(listOf(n(1, 1), n(2, dimensions.tileCountX.toLong()), n(3, dimensions.tileCountY.toLong()),
                n(4, dimensions.tileWorldSizeX.toLong()), n(5, dimensions.tileWorldSizeY.toLong()),
                b(6, TimelineBytes(terrain)), n(7, 1), b(8, plane(visual.groundTileIds)),
                b(9, plane(visual.decorationTileIds)), b(10, TimelineBytes(pools.toByteArray())))).also(TimelineBinary::validateMapRecord)
        }

        internal fun ability(action: ViewAction): TimelineRecord {
            val kind = when (action.kind) {
                ViewActionKind.BUILD -> 1; ViewActionKind.PRODUCE -> 2; ViewActionKind.UPGRADE -> 3
                ViewActionKind.REPAIR -> 4; ViewActionKind.RECLAIM -> 5; ViewActionKind.RALLY -> 6
                ViewActionKind.LOAD_INTO, ViewActionKind.LOAD_UP -> 7
                ViewActionKind.UNLOAD -> 8; ViewActionKind.STOP_UNLOAD -> 9
                ViewActionKind.TARGET_GROUND -> 10; ViewActionKind.PATROL -> 11; ViewActionKind.GUARD -> 12
                ViewActionKind.TOGGLE, ViewActionKind.ATTACK_MODE -> 13
            }
            val queueable = action.kind in setOf(ViewActionKind.BUILD, ViewActionKind.REPAIR, ViewActionKind.RECLAIM,
                ViewActionKind.LOAD_INTO, ViewActionKind.LOAD_UP, ViewActionKind.GUARD, ViewActionKind.PATROL)
            return TimelineRecord(buildList {
                add(n(1, kind.toLong())); add(n(2, action.targetMode.ordinal.toLong()))
                add(TimelineMessages.boolField(3, queueable)); add(TimelineMessages.textField(4, action.presentationKey))
                add(TimelineMessages.textField(5, action.producedTypeKey))
                add(i(6, action.upfrontCost?.let { it.credits.toLong() * 1024 } ?: -1))
                add(n(7, 0))
                action.upfrontCost?.let { add(TimelineField(9, false, TimelineTlvValue.Record(cost(it)))) }
                action.streamingCost?.let { add(TimelineField(10, false, TimelineTlvValue.Record(cost(it)))) }
            })
        }

        private fun cost(cost: ViewActionCost) = TimelineRecord(listOf(i(1, cost.credits.toLong() * 1024),
            i(2, quantize(cost.energy.toDouble(), 1024)), i(3, quantize(cost.hp.toDouble(), 1024)),
            i(4, quantize(cost.shield.toDouble(), 1024)), i(5, cost.ammo.toLong() * 1024)))

        private fun match(status: MatchStatus, finishedAt: Long): TimelineBytes {
            val out = ByteArrayOutputStream()
            out.write(if (status.phase == MatchPhase.FINISHED) 1 else 0)
            TimelineBinary.writeVUInt(out, finishedAt)
            for (ids in listOf(status.winnerTeamIds, status.defeatedTeamIds)) {
                TimelineBinary.writeVUInt(out, ids.size.toLong())
                ids.sorted().forEach { TimelineBinary.writeVUInt(out, it.toLong()) }
            }
            return TimelineBytes(out.toByteArray())
        }
        private fun position(value: Float, size: Long): Long {
            require(value.isFinite() && value >= 0f && value.toDouble() < size)
            return quantize(value, 256).coerceAtMost(size * 256 - 1)
        }
        private fun progress(value: Float): Long {
            require(value.isFinite() && value in 0f..1f)
            return quantize(value, 65535)
        }
        private fun quantize(value: Float, scale: Int): Long {
            // Only these two scales have the decimal-half-boundary proof for a widened Float.
            // In particular, other powers of two cannot be admitted by generalizing this guard.
            if ((scale == 256 || scale == 65535) && value >= -16777216f && value <= 16777216f) {
                // At most 24 + 16 significant bits: this product is exact in Double.
                val magnitude = kotlin.math.abs(value.toDouble()) * scale
                val integral = magnitude.toLong()
                val rounded = integral + if (magnitude - integral.toDouble() >= 0.5) 1 else 0
                return if (value < 0f) -rounded else rounded
            }
            // Includes nonfinite values, large Float values and every other scale. Keep the
            // existing validation, decimal conversion, exception and exact-Long behavior.
            return quantize(value.toDouble(), scale)
        }
        private fun quantize(value: Double, scale: Int) = quantize(value, scale.toDouble())
        private fun quantize(value: Double, scale: Double): Long {
            require(value.isFinite())
            return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(scale)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }
        private fun n(id: Int, value: Long) = TimelineMessages.numberField(id, value, true)
        private fun b(id: Int, value: TimelineBytes) = TimelineMessages.bytesField(id, value)
        private fun i(id: Int, value: Long) = TimelineField(id, true, TimelineTlvValue.I64(value))
    }
}
