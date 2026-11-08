package io.github.rwx.authoritative

import io.github.rwx.net.authoritative.ActionReadiness
import io.github.rwx.net.authoritative.ActionTargetMode
import io.github.rwx.net.authoritative.MatchPhase
import io.github.rwx.net.authoritative.MatchStatus
import io.github.rwx.net.authoritative.PlayerActionPanel
import io.github.rwx.net.authoritative.PlayerViewSnapshot
import io.github.rwx.net.authoritative.PublicMapDefinition
import io.github.rwx.net.authoritative.PublicMapVisual
import io.github.rwx.net.authoritative.ViewAction
import io.github.rwx.net.authoritative.ViewActionCost
import io.github.rwx.net.authoritative.ViewActionKind
import io.github.rwx.net.authoritative.ViewUnit
import io.github.rwx.net.timeline.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Exact fixed-point costs. Null components are unknown, never free. */
internal data class TimelineDisplayCost(val credits: Long?, val energy: Long?, val hp: Long?,
                                        val shield: Long?, val ammo: Long?)

/** Presentation metadata only. The caller must resolve commands against the current LIVE receipt. */
internal data class TimelineDisplayAction(
    val handle: Long, val subjectHandle: Long, val panelHandle: Long,
    val kind: Int, val targetMode: ActionTargetMode, val queueable: Boolean,
    val titleKey: String, val producedTypeKey: String, val creditsCost: Long?, val hotkeySlot: Int,
    val upfrontCost: TimelineDisplayCost?, val streamingCost: TimelineDisplayCost?,
    val readiness: ActionReadiness, val queuedCount: Int, val stockpileCount: Int,
    val progress: Float, val canCancel: Boolean,
    /** Null when the presentation enum cannot express the v7 meaning without guessing. */
    val displayAction: ViewAction?,
)

internal data class TimelineDisplayEffect(
    val observationHandle: Long, val eventId: Long, val time: Long, val duration: Long,
    val age: Long, val kind: Int, val x: Float, val y: Float, val height: Float,
    val seed: Long, val presentationKey: String?,
)

internal data class TimelineDisplayFrame(
    val snapshot: PlayerViewSnapshot,
    val map: PublicMapDefinition,
    val selection: List<Long>,
    val panelHandle: Long?,
    val panel: PlayerActionPanel?,
    val actions: List<TimelineDisplayAction>,
    val effects: List<TimelineDisplayEffect>,
    val unavailableUnitHandles: List<Long>,
    val readOnly: Boolean,
    val streamId: Long, val epoch: Long, val revision: Long, val controlEpoch: Long,
    val time: Long, val observedThrough: Long, val finishedAt: Long?,
    /** The immutable library actually sampled, independent of playback permission or UI sequence. */
    val sourceMode: TimelineStreamMode,
    val retainedFrom: Long, val retainedUntil: Long, val sealedThrough: Long,
)

/**
 * Pure presentation sampling of a validated, committed timeline. No engine, transport, clocks,
 * legacy codec, asset parser or command execution is involved. Inputs remain immutable.
 *
 * displaySequence/querySequence are local UI correlations supplied by the caller, never v7
 * receipts. Every render sample needs a fresh displaySequence when used by the canvas renderer;
 * that renderer's extra motion interpolation must be bypassed for these already sampled poses.
 */
internal object TimelineViewAdapter {
    fun map(context: TimelineContext): PublicMapDefinition {
        val r = context.mapRecord
        val width = r.number(2).toInt(); val height = r.number(3).toInt()
        val cells = Math.multiplyExact(width, height)
        fun layer(id: Int): IntArray {
            val bytes = r.bytes(id).toByteArray()
            require(bytes.size == cells * 2) { "Display map layer size differs" }
            return IntArray(cells) { index -> ((bytes[index * 2].toInt() and 255) shl 8) or (bytes[index * 2 + 1].toInt() and 255) }
        }
        return PublicMapDefinition(width, height, r.number(4).toInt(), r.number(5).toInt(),
            r.bytes(6).toByteArray(), PublicMapVisual(r.number(7).toInt(), layer(8), layer(9)))
    }

    /** Null is an unavailable time, not a fabricated empty world or a clamped neighboring frame. */
    fun sample(state: TimelineState, time: Long, displaySequence: Long,
               querySequence: Long = 0, displayOnly: Boolean = false): TimelineDisplayFrame? {
        require(time in 0..TimelineProtocol.MAX_TIME && displaySequence > 0 && querySequence >= 0)
        val h = state.header
        if (time < h.retainedFrom || time >= h.retainedUntil) return null
        val credits = (TimelineProfile.sample(state, 0, 1, time) as? TimelineValue.SInt)?.value ?: return null
        val statusBytes = (TimelineProfile.sample(state, 0, 2, time) as? TimelineValue.Blob)?.value ?: return null
        val selectionBytes = (TimelineProfile.sample(state, 0, 3, time) as? TimelineValue.Blob)?.value ?: return null
        val status = status(statusBytes)
        val readOnly = displayOnly || state.context.mode == TimelineStreamMode.HISTORY
        val selected = TimelineBinary.decodeSelection(selectionBytes)
        val terminal = status.first.phase == MatchPhase.FINISHED || state.context.teamId in status.first.defeatedTeamIds
        val map = map(state.context)
        val fog = ByteArray(map.width * map.height) { 10 }
        for (page in 0 until (fog.size + 255) / 256) TimelineProfile.fogPage(state, page, time).copyInto(fog, page * 256)
        val units = ArrayList<ViewUnit>()
        val missing = ArrayList<Long>()
        for (obj in state.objects.values.sortedBy { it.handle }) {
            if (obj.kind != TimelineObjectKind.UNIT || !active(obj, time)) continue
            val value = unit(state, obj, time, readOnly || terminal)
            if (value == null) missing.add(obj.handle) else units.add(value)
        }
        val panels = state.objects.values.filter { it.kind == TimelineObjectKind.PANEL &&
            (TimelineProfile.sample(state, it.handle, 1, time) as? TimelineValue.Bool)?.value == true }
        require(panels.size <= 1) { "Multiple sampled panels" }
        val panelObject = panels.singleOrNull()?.takeIf { selected == listOf(it.descriptor.number(2)) &&
            units.any { unit -> unit.handle == it.descriptor.number(2) } }
        val actions = if (panelObject == null) emptyList() else state.objects.values.asSequence()
            .filter { it.kind == TimelineObjectKind.ACTION && active(it, time) && it.descriptor.number(3) == panelObject.handle }
            .sortedBy { it.handle }.map { action(state, it, time) }.toList()
        val panel = panelObject?.let { PlayerActionPanel(querySequence, it.handle, displaySequence,
            state.context.teamId, it.descriptor.number(2), actions.mapNotNull { action -> action.displayAction }) }
        val snapshot = PlayerViewSnapshot(displaySequence, (time / TimelineProtocol.SUBDIVISIONS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            state.context.teamId, credits.toDouble() / 1024.0, map.width, map.height, map.tileWidth, map.tileHeight,
            fog, units, map.terrain.copyOf(), status.first, map.visual?.detached())
        return TimelineDisplayFrame(snapshot, map, selected, panelObject?.handle, panel, actions,
            effects(state, time), missing, readOnly, h.streamId, h.epoch, h.revision, h.controlEpoch,
            time, h.observedThrough, status.second, state.context.mode,
            h.retainedFrom, h.retainedUntil, h.sealedThrough)
    }

    private fun unit(state: TimelineState, obj: TimelineObject, time: Long, disabled: Boolean): ViewUnit? {
        fun value(id: Int) = TimelineProfile.sample(state, obj.handle, id, time)
        val position = value(1) as? TimelineValue.Vec3 ?: return null
        val heading = (value(2) as? TimelineValue.UInt)?.value ?: return null
        val health = (value(3) as? TimelineValue.SInt)?.value ?: return null
        val maximum = (value(4) as? TimelineValue.SInt)?.value ?: return null
        val progress = (value(5) as? TimelineValue.UInt)?.value ?: return null
        val team = (value(6) as? TimelineValue.SInt)?.value ?: return null
        val appearance = (value(7) as? TimelineValue.Blob)?.value ?: return null
        val tech = (value(8) as? TimelineValue.UInt)?.value ?: return null
        val controllable = (value(9) as? TimelineValue.Bool)?.value ?: return null
        return ViewUnit(obj.handle, text(appearance), team.toInt(), coordinate(position.x), coordinate(position.y),
            coordinate(position.z), (heading.toDouble() * 360.0 / 65536.0).toFloat(), coordinate(health), coordinate(maximum),
            (progress.toDouble() / 65535.0).toFloat(), controllable && !disabled && team == state.context.teamId.toLong(), tech.toInt())
    }

    private fun action(state: TimelineState, obj: TimelineObject, time: Long): TimelineDisplayAction {
        val r = obj.descriptor.record(4)
        val kind = r.number(1).toInt()
        val target = when (r.number(2)) { 0L -> ActionTargetMode.NONE; 1L -> ActionTargetMode.POINT
            2L -> ActionTargetMode.VISIBLE_UNIT; else -> error("Unknown action target") }
        fun sample(id: Int) = TimelineProfile.sample(state, obj.handle, id, time)
        val readiness = when ((sample(1) as? TimelineValue.UInt)?.value) {
            0L -> ActionReadiness.BLOCKED; 1L -> ActionReadiness.READY; else -> ActionReadiness.UNKNOWN
        }
        val queued = (sample(2) as? TimelineValue.SInt)?.value?.toInt() ?: -1
        val stock = (sample(3) as? TimelineValue.SInt)?.value?.toInt() ?: -1
        val progress = (sample(4) as? TimelineValue.UInt)?.value?.let { (it.toDouble() / 65535).toFloat() } ?: -1f
        val cancel = (sample(5) as? TimelineValue.Bool)?.value ?: false
        val credits = r.number(6).takeUnless { it == -1L }
        val upfront = cost(r, 9)
        val streaming = cost(r, 10)
        val display = displayKind(kind, target)?.let { ViewAction(obj.handle, it, target, r.text(4), r.text(5),
            displayCost(upfront), displayCost(streaming), readiness, queued, stock, progress, cancel) }
        return TimelineDisplayAction(obj.handle, obj.descriptor.number(2), obj.descriptor.number(3), kind, target,
            r.boolean(3), r.text(4), r.text(5), credits, r.number(7).toInt(), upfront, streaming,
            readiness, queued, stock, progress, cancel, display)
    }

    private fun cost(ability: TimelineRecord, id: Int): TimelineDisplayCost? {
        val r = (ability[id]?.value as? TimelineTlvValue.Record)?.value ?: return null
        fun component(id: Int) = r.number(id).takeUnless { it == -1L }
        return TimelineDisplayCost(component(1), component(2), component(3), component(4), component(5))
    }

    private fun displayCost(cost: TimelineDisplayCost?): ViewActionCost? {
        if (cost == null) return null
        fun integer(value: Long?): Int? = value?.takeIf { it % 1024 == 0L && it / 1024 <= Int.MAX_VALUE }?.let { (it / 1024).toInt() }
        val credits = integer(cost.credits) ?: return null
        val ammo = integer(cost.ammo) ?: return null
        // Legacy numeric costs cannot distinguish one unknown component from a free component.
        return ViewActionCost(credits, cost.energy?.let { (it.toDouble() / 1024).toFloat() } ?: return null,
            cost.hp?.let { (it.toDouble() / 1024).toFloat() } ?: return null,
            cost.shield?.let { (it.toDouble() / 1024).toFloat() } ?: return null, ammo)
    }

    private fun displayKind(kind: Int, target: ActionTargetMode): ViewActionKind? = when (kind) {
        1 -> ViewActionKind.BUILD; 2 -> ViewActionKind.PRODUCE; 3 -> ViewActionKind.UPGRADE
        4 -> ViewActionKind.REPAIR; 5 -> ViewActionKind.RECLAIM; 6 -> ViewActionKind.RALLY
        7 -> when (target) {
            ActionTargetMode.NONE -> ViewActionKind.LOAD_UP
            ActionTargetMode.VISIBLE_UNIT -> ViewActionKind.LOAD_INTO
            ActionTargetMode.POINT -> null
        }
        8 -> ViewActionKind.UNLOAD; 9 -> ViewActionKind.STOP_UNLOAD; 10 -> ViewActionKind.TARGET_GROUND
        11 -> ViewActionKind.PATROL; 12 -> ViewActionKind.GUARD; 13 -> ViewActionKind.TOGGLE
        // The remaining richer v7 kinds stay available in TimelineDisplayAction without relabelling.
        else -> null
    }

    private fun effects(state: TimelineState, time: Long): List<TimelineDisplayEffect> = buildList {
        for (obj in state.objects.values.sortedBy { it.handle }) {
            if (obj.kind != TimelineObjectKind.VISUAL) continue
            val curve = obj.curves[1] ?: continue
            val events = curve.events
            val minimum = (time - curve.maxTailTime).coerceAtLeast(0)
            var low = 0; var high = events.size
            while (low < high) { val mid = (low + high).ushr(1); if (events[mid].time < minimum) low = mid + 1 else high = mid }
            for (index in low until events.size) {
                val event = events[index]
                if (event.time > time) break
                val r = TimelineBinary.decodeRecord((event.value as TimelineValue.Blob).value)
                val duration = r.number(3)
                val age = time - event.time
                if (if (duration == 0L) age != 0L else age >= duration) continue
                val position = TimelineBinary.Cursor(r.bytes(2))
                val x = position.vsint(); val y = position.vsint(); val z = position.vsint(); position.finish()
                add(TimelineDisplayEffect(obj.handle, event.eventId, event.time, duration, age, r.number(1).toInt(),
                    coordinate(x), coordinate(y), coordinate(z), r.number(4), (r[5]?.value as? TimelineTlvValue.Text)?.value))
            }
        }
    }.sortedWith(compareBy<TimelineDisplayEffect> { it.time }.thenBy { it.observationHandle }.thenBy { it.eventId })

    private fun status(bytes: TimelineBytes): Pair<MatchStatus, Long?> {
        val cursor = TimelineBinary.Cursor(bytes)
        val phase = cursor.u8(); val finished = cursor.vuint()
        fun teams(): List<Int> {
            val count = cursor.vuint(); require(count <= 64)
            return List(count.toInt()) { cursor.vuint().toInt() }
        }
        val winners = teams(); val defeated = teams(); cursor.finish()
        return MatchStatus(if (phase == 0) MatchPhase.RUNNING else MatchPhase.FINISHED, winners, defeated) to finished.takeIf { phase == 1 }
    }
    private fun coordinate(value: Long): Float = (value.toDouble() / 256.0).toFloat()
    private fun active(obj: TimelineObject, time: Long) = obj.observedFrom <= time && (obj.close?.let { time < it.until } ?: true)
    private fun text(bytes: TimelineBytes): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
}
