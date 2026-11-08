package io.github.rwx.authoritative

import io.github.rwx.render.canvas.KoolCanvasPoint
import io.github.rwx.render.canvas.KoolCanvasRect
import java.awt.event.KeyEvent

/** Only values already used by the normal view-only UI, captured after drawing a frame. */
internal data class AuthoritativeActionProbeUi(
    val connected: Boolean = false,
    val readOnly: Boolean = false,
    val running: Boolean = false,
    val tick: Int = 0,
    val viewSequence: Long = -1,
    val credits: Double = 0.0,
    val commandCenterHandle: Long = 0,
    val commandCenterPoint: KoolCanvasPoint? = null,
    val selectedCommandCenter: Boolean = false,
    val panelSequence: Long = 0,
    val drawnPanelSequence: Long = 0,
    val drawnPage: Int = -1,
    val builder: Builder? = null,
    /** Incremented only after the normal renderer sendAction callback returns successfully. */
    val actionSends: Int = 0,
    val cancelSends: Int = 0,
) {
    data class Builder(val ownerHandle: Long, val handle: Long, val page: Int, val key: Int,
        val bounds: KoolCanvasRect?, val enabled: Boolean, val queuedCount: Int, val canCancel: Boolean,
        val creditCost: Int?)
}

/** Opt-in acceptance controller. It emits normal UI input only; it has no connection or engine API. */
internal class AuthoritativeActionProbe(requested: Boolean, readOnly: Boolean) {
    val enabled: Boolean = requested && !readOnly
    var completed = false
        private set
    var panelSeen = false
        private set
    var uiActionCount = 0
        private set
    var queueObserved = false
        private set
    var cancelObserved = false
        private set
    var failure: String? = null
        private set
    private enum class Phase { SELECT, PRODUCE, QUEUE, REFUND }
    private var phase = Phase.SELECT
    private val frames = linkedMapOf<Long, AuthoritativeActionProbeUi>()
    private var startedAt: Long? = null
    private var lastInputRevision = -1L
    private var owner = 0L
    private var creditCost = 0
    private var creditsBeforeProduction = 0.0
    private var chargedCredits = 0.0
    private var cancelTick = 0
    private var pending: Pending? = null

    sealed interface Input {
        data class Click(val point: KoolCanvasPoint) : Input
        data class Key(val key: Int, val control: Boolean = false) : Input
    }
    private data class Pending(val phase: Phase, val before: AuthoritativeActionProbeUi,
        val presented: AuthoritativeActionProbeUi)

    fun recordFrame(revision: Long, ui: AuthoritativeActionProbeUi) {
        if (!enabled) return
        frames[revision] = ui
        while (frames.size > 128) frames.remove(frames.keys.first())
    }

    /** [presentedRevision] must come from CanvasFramePresentationTracker, never the submit counter. */
    fun nextInput(current: AuthoritativeActionProbeUi, presentedRevision: Long,
        nowNanos: Long = System.nanoTime()): Input? {
        if (!enabled || completed || failure != null || pending != null || current.readOnly) return null
        val presented = frames[presentedRevision] ?: return null
        if (!current.connected || !current.running || current.tick <= 0 || !presented.connected ||
            !presented.running || presented.readOnly || presented.tick <= 0) return null
        val start = startedAt ?: nowNanos.also { startedAt = it }
        if (nowNanos - start > 10_000_000_000L) { failure = "UI action probe timed out during ${phase.name.lowercase()}"; return null }
        if (owner != 0L && (current.commandCenterHandle != owner || !current.selectedCommandCenter)) {
            failure = "UI probe selection or ownership changed"; return null
        }
        if (phase == Phase.REFUND) {
            val action = presented.builder
            if (sameDrawnAction(current, presented) && action?.queuedCount == 0 &&
                presented.tick - cancelTick in 0..120 && presented.credits - chargedCredits >= creditCost * .9) {
                cancelObserved = true; completed = true
            }
            return null
        }
        if (presentedRevision <= lastInputRevision) return null
        if (phase == Phase.SELECT) {
            if (current.commandCenterHandle == 0L || current.commandCenterHandle != presented.commandCenterHandle) return null
            val point = presented.commandCenterPoint
            if (point == null) return issue(Input.Key(KeyEvent.VK_HOME), presentedRevision)
            if (point != current.commandCenterPoint) return null
            pending = Pending(phase, current, presented)
            return issue(Input.Click(point), presentedRevision)
        }
        val action = presented.builder ?: return null
        if (!sameDrawnAction(current, presented)) return null
        if (action.page != presented.drawnPage) {
            return issue(Input.Key(if (action.page > presented.drawnPage) KeyEvent.VK_PAGE_DOWN else KeyEvent.VK_PAGE_UP), presentedRevision)
        }
        if (action.bounds == null) return null
        panelSeen = true
        when (phase) {
            Phase.PRODUCE -> {
                if (action.queuedCount != 0) { failure = "UI probe refuses to change a pre-existing builder queue"; return null }
                if (!action.enabled || action.creditCost == null || action.creditCost <= 0) return null
                pending = Pending(phase, current, presented)
                return issue(Input.Click(KoolCanvasPoint((action.bounds.left + action.bounds.right) / 2,
                    action.bounds.top + minOf(15f, action.bounds.height / 2))), presentedRevision)
            }
            Phase.QUEUE -> {
                if (action.queuedCount > 0 && action.canCancel && creditsBeforeProduction - presented.credits >= creditCost * .9) {
                    if (action.queuedCount != 1) { failure = "UI probe production delta is ambiguous"; return null }
                    queueObserved = true
                    pending = Pending(phase, current, presented)
                    return issue(Input.Key(action.key, control = true), presentedRevision)
                }
            }
            else -> Unit
        }
        return null
    }

    /** The caller applies Input using pointerDown/up or keyPressed, then reports this read-only UI. */
    fun inputResult(after: AuthoritativeActionProbeUi) {
        val sent = pending ?: return
        pending = null
        if (after.readOnly || !after.connected) return
        when (sent.phase) {
            Phase.SELECT -> if (after.selectedCommandCenter && after.commandCenterHandle == sent.presented.commandCenterHandle) {
                owner = after.commandCenterHandle; uiActionCount++; phase = Phase.PRODUCE
            }
            Phase.PRODUCE -> if (after.actionSends == sent.before.actionSends + 1 && after.cancelSends == sent.before.cancelSends) {
                creditCost = checkNotNull(sent.presented.builder?.creditCost)
                creditsBeforeProduction = sent.presented.credits
                uiActionCount++; phase = Phase.QUEUE
            }
            Phase.QUEUE -> if (after.actionSends == sent.before.actionSends + 1 && after.cancelSends == sent.before.cancelSends + 1) {
                chargedCredits = sent.presented.credits; cancelTick = sent.presented.tick
                uiActionCount++; phase = Phase.REFUND
            }
            Phase.REFUND -> Unit
        }
    }

    private fun issue(input: Input, revision: Long): Input { lastInputRevision = revision; return input }

    private fun sameDrawnAction(current: AuthoritativeActionProbeUi, presented: AuthoritativeActionProbeUi): Boolean {
        val a = current.builder ?: return false
        val b = presented.builder ?: return false
        return current.selectedCommandCenter && presented.selectedCommandCenter && a.ownerHandle == owner && b.ownerHandle == owner &&
            current.panelSequence > 0 && current.panelSequence == current.drawnPanelSequence &&
            current.panelSequence == presented.panelSequence && presented.panelSequence == presented.drawnPanelSequence &&
            current.drawnPage == presented.drawnPage && a.handle == b.handle && a.page == b.page && a.key == b.key && a.bounds == b.bounds &&
            a.queuedCount == b.queuedCount && a.canCancel == b.canCancel
    }

    fun json(): String = "{\"enabled\":$enabled,\"completed\":$completed,\"panelSeen\":$panelSeen," +
        "\"uiActionCount\":$uiActionCount,\"queueObserved\":$queueObserved,\"cancelObserved\":$cancelObserved}"
}
