package io.github.rwx.authoritative

import io.github.rwx.net.authoritative.*
import io.github.rwx.net.timeline.TimelineProtocol
import io.github.rwx.render.canvas.*
import io.github.rwx.session.GameCameraSnapshot
import java.awt.event.KeyEvent
import java.security.MessageDigest
import kotlin.math.*

/** Display/input state for one permitted view. No engine, map parser, unit factory or mod hooks. */
internal class AuthoritativeCanvasRenderer(
    private val assets: AuthoritativeVisualAssets,
    private val sendIntent: (ViewUnitOrder) -> Unit,
    private val sendAction: (ViewActionCommand) -> Unit = {},
    private val selectionChanged: (List<Long>) -> Unit = {},
) {
    private var view: PlayerViewSnapshot? = null
    private var cameraInitialized = false
    private var timelineEffects = emptyList<TimelineDisplayEffect>()
    private val displayedPositions = mutableMapOf<Long, KoolCanvasPoint>()
    private var viewport = KoolCanvasViewport(1280, 720)
    private var density = 1f
    private var centerX = 0f
    private var centerY = 0f
    private var zoom = 1.5f
    private val selected = linkedSetOf<Long>()
    private var sequence = 0L
    private var attackMove = false
    private var pointer = KoolCanvasPoint(0f, 0f)
    private var selectionStart: KoolCanvasPoint? = null
    private var addSelection = false
    private var panStart: KoolCanvasPoint? = null
    private var connected = true
    private var readOnly = false
    private var sourceLabel = "Connected"
    private var error: String? = null
    private var notice = "Select your units to issue orders"
    private var buttons = emptyList<ActionButton>()
    private var synchronizing = false
    private var panelOwner = 0L
    private var panelOwnerType: String? = null
    private var panelOwnerUpgradeLevel: Int? = null
    private var panel: PanelReceipt? = null
    private var pendingAction: PendingAction? = null
    private var actionPage = 0
    private var drawnActionPage = -1
    private var drawnPanelSequence = 0L
    private var lastRenderAt = 0L
    private var actionSendCount = 0
    private var actionCancelSendCount = 0
    private val buffer = KoolCanvasCommandBuffer()
    private val renderedTerrainTextures = mutableSetOf<KoolCanvasTextureId>()

    val viewSequence: Long get() = view?.sequence ?: -1
    val tick: Int get() = view?.tick ?: 0
    val selectedHandles: Set<Long> get() = selected.toSet()
    var renderedGroundCells: Int = 0
        private set
    var renderedDecorationCells: Int = 0
        private set
    val terrainTextures: Int get() = renderedTerrainTextures.size

    /** Evidence about the permitted DTO and this frame's actual atlas commands; no engine reads. */
    fun mapVisualProfile(): AuthoritativeMapVisualProfile = view?.let { AuthoritativeMapVisualProfile.from(it) }
        ?: AuthoritativeMapVisualProfile()

    /** Read-only acceptance geometry; input still enters the exact public pointer/key handlers. */
    fun actionProbeUi(): AuthoritativeActionProbeUi {
        val snapshot = view
        val commandCenter = snapshot?.units?.firstOrNull {
            it.controllable && it.teamId == snapshot?.teamId && it.typeName == "commandCenter" && it.buildProgress >= 1f
        }
        val centerPoint = commandCenter?.let(::displayedPoint)?.takeIf { worldBounds().contains(it.x, it.y) }
        val receipt = panel?.value
        val action = receipt?.actions?.singleOrNull {
            it.kind == ViewActionKind.PRODUCE && it.producedTypeKey == "builder" && it.targetMode == ActionTargetMode.NONE
        }
        val row = if (receipt == null || action == null) null else action.let { entry ->
            val index = receipt.actions.indexOf(entry)
            val button = buttons.singleOrNull { !it.cancel && it.actionHandle == entry.handle && it.panelSequence == receipt.panelSequence }
            AuthoritativeActionProbeUi.Builder(receipt.unitHandle, entry.handle, index / actionsPerPage(),
                KeyEvent.VK_1 + index % actionsPerPage(), button?.bounds, button?.enabled == true,
                entry.queuedCount, entry.canCancel, entry.upfrontCost?.credits)
        }
        return AuthoritativeActionProbeUi(connected, readOnly, commandsAvailable(), snapshot?.tick ?: 0,
            snapshot?.sequence ?: -1, snapshot?.credits ?: 0.0, commandCenter?.handle ?: 0,
            centerPoint, commandCenter != null && selected.size == 1 && commandCenter.handle in selected,
            receipt?.panelSequence ?: 0, drawnPanelSequence, drawnActionPage, row, actionSendCount, actionCancelSendCount)
    }

    fun cameraSnapshot(revision: Long, viewportRevision: Long): GameCameraSnapshot {
        val rect = worldBounds()
        return GameCameraSnapshot(revision, viewportRevision, viewport,
            centerX - rect.width / zoom / 2, centerY - rect.height / zoom / 2, zoom, generation = 1L,
            sidebarWidth = 248 * density)
    }

    fun updateView(next: PlayerViewSnapshot, @Suppress("UNUSED_PARAMETER") nowNanos: Long = System.nanoTime()) {
        if (next.sequence <= (view?.sequence ?: -1)) return
        val first = !cameraInitialized
        view = next
        selected.retainAll(next.units.filter { it.controllable }.map { it.handle }.toSet())
        if (first) { home(); cameraInitialized = true }
        if (next.matchStatus.phase == MatchPhase.FINISHED) { selected.clear(); cancelModes() }
        synchronizePanelOwner()
    }

    fun updateTimelineFrame(next: TimelineDisplayFrame, nowNanos: Long = System.nanoTime()) {
        updateView(next.snapshot, nowNanos)
        timelineEffects = next.effects
        val value = next.panel
        if (next.readOnly || value == null || value.unitHandle != selectedActionOwner()) {
            clearActionPanel()
            return
        }
        updateActionPanel(value, nowNanos)
    }

    fun clearTimelineFrame() {
        view = null
        timelineEffects = emptyList()
        displayedPositions.clear()
        selected.clear()
        clearActionPanel()
        cancelInput()
    }

    fun setViewport(next: KoolCanvasViewport, scale: Float = 1f) {
        viewport = next
        density = scale.coerceIn(1f, 3f)
    }

    fun connectionState(isConnected: Boolean, failure: String? = null, displayOnly: Boolean = false,
                        label: String = "Connected", isSynchronizing: Boolean = false) {
        connected = isConnected; error = failure; readOnly = displayOnly; sourceLabel = label
        synchronizing = isSynchronizing
        if (displayOnly || synchronizing) cancelModes()
        if (synchronizing) selected.clear()
        synchronizePanelOwner()
    }

    fun render(nowNanos: Long = System.nanoTime()): KoolCanvasFrame {
        maintainActionPanel(nowNanos)
        renderedGroundCells = 0
        renderedDecorationCells = 0
        renderedTerrainTextures.clear()
        buffer.beginFrame(viewport)
        buffer.clear(color(0xff111821.toInt()))
        val snapshot = view
        if (snapshot != null) drawWorld(snapshot, nowNanos)
        drawHud(snapshot)
        return buffer.snapshot()
    }

    fun pointerDown(x: Float, y: Float, button: Int, shift: Boolean = false) {
        pointer = KoolCanvasPoint(x, y)
        val hudButton = buttons.lastOrNull { it.bounds.contains(x, y) }
        if (button == 1 && hudButton != null) {
            if (hudButton.enabled) when {
                hudButton.pageDelta != 0 -> changeActionPage(hudButton.pageDelta)
                hudButton.actionHandle != 0L -> if (hudButton.panelSequence == panel?.value?.panelSequence)
                    activateAction(hudButton.actionHandle, hudButton.cancel, shift)
                else -> keyPressed(hudButton.key, shift = shift)
            }
            return
        }
        if (button == 3 && pendingAction != null) { cancelModes(); notice = "Action targeting cancelled"; return }
        if (!worldBounds().contains(x, y)) return
        if (button == 2) { panStart = pointer; return }
        val point = worldPoint(x, y) ?: return
        pendingAction?.let { pending ->
            if (button == 1) {
                val target = currentAction(pending.actionHandle)
                when (target?.targetMode) {
                    ActionTargetMode.POINT -> if (issueAction(pending.actionHandle, point.x, point.y, queued = pending.queued || shift)) cancelModes()
                    ActionTargetMode.VISIBLE_UNIT -> {
                        val unit = nearest(x, y)
                        if (unit == null) notice = "Choose a currently disclosed unit"
                        else if (issueAction(pending.actionHandle, target = unit.handle, queued = pending.queued || shift)) cancelModes()
                    }
                    else -> { pendingAction = null; notice = "Action is no longer available" }
                }
            }
            return
        }
        if (attackMove && (button == 1 || button == 3)) {
            issue(OrderKind.ATTACK_MOVE, point.x, point.y)
            cancelModes()
            return
        }
        when (button) {
            1 -> { selectionStart = pointer; addSelection = shift }
            3 -> {
                val snapshot = view ?: return
                val target = nearest(x, y)?.takeIf { !it.controllable && it.teamId != snapshot.teamId }
                if (target != null) issue(OrderKind.ATTACK_UNIT, target = target.handle)
                else issue(OrderKind.MOVE, point.x, point.y)
            }
        }
    }

    fun pointerMoved(x: Float, y: Float) {
        pointer = KoolCanvasPoint(x, y)
        panStart?.let { previous ->
            centerX -= (x - previous.x) / zoom
            centerY -= (y - previous.y) / zoom
            panStart = pointer
            clampCamera()
        }
    }

    fun pointerUp(x: Float, y: Float, button: Int) {
        pointerMoved(x, y)
        if (button == 2) { panStart = null; return }
        if (button != 1) return
        val start = selectionStart ?: return
        selectionStart = null
        if (!addSelection) selected.clear()
        if (hypot((x - start.x).toDouble(), (y - start.y).toDouble()) > 6 * density) {
            val rect = KoolCanvasRect(min(start.x, x), min(start.y, y), max(start.x, x), max(start.y, y))
            view?.units?.filter { it.controllable && displayedPoint(it).let { p -> rect.contains(p.x, p.y) } }
                ?.take(ViewLimits.MAX_SELECTED_UNITS - selected.size)?.forEach { selected += it.handle }
        } else nearest(x, y)?.takeIf { it.controllable }?.let { unit ->
            if (unit.handle in selected) selected.remove(unit.handle)
            else if (selected.size < ViewLimits.MAX_SELECTED_UNITS) selected += unit.handle
        }
        notice = "Selected ${selected.size} unit(s)"
        synchronizePanelOwner()
    }

    fun cancelInput() { selectionStart = null; panStart = null; cancelModes() }

    fun keyPressed(key: Int, control: Boolean = false, shift: Boolean = false) {
        if (key == KeyEvent.VK_PAGE_UP || key == KeyEvent.VK_PAGE_DOWN) {
            changeActionPage(if (key == KeyEvent.VK_PAGE_UP) -1 else 1); return
        }
        if (key in KeyEvent.VK_1..KeyEvent.VK_9) {
            if (drawnPanelSequence != panel?.value?.panelSequence || drawnActionPage != actionPage) return
            buttons.singleOrNull { it.actionHandle != 0L && !it.cancel && it.key == key &&
                it.panelSequence == drawnPanelSequence }?.let {
                activateAction(it.actionHandle, cancel = control, queued = shift)
            }
            return
        }
        if (readOnly && key in setOf(KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_F, KeyEvent.VK_T, KeyEvent.VK_E, KeyEvent.VK_P, KeyEvent.VK_B) &&
            !(key == KeyEvent.VK_A && control)) { notice = "Replay: orders are disabled"; return }
        when (key) {
            KeyEvent.VK_A -> if (control) {
                selected.clear()
                view?.units?.filter { it.controllable }?.take(ViewLimits.MAX_SELECTED_UNITS)?.forEach { selected += it.handle }
                cancelModes()
                synchronizePanelOwner()
            } else if (selected.isNotEmpty()) { cancelModes(); attackMove = true; notice = "Click an attack-move destination" }
            KeyEvent.VK_S -> { cancelModes(); issue(OrderKind.STOP) }
            KeyEvent.VK_F -> actionShortcut(ViewActionKind.BUILD, "landFactory", shift)
            KeyEvent.VK_T -> actionShortcut(ViewActionKind.BUILD, "turret", shift)
            KeyEvent.VK_E -> actionShortcut(ViewActionKind.BUILD, "extractor", shift)
            KeyEvent.VK_P -> actionShortcut(ViewActionKind.PRODUCE, "tank", shift)
            KeyEvent.VK_B -> actionShortcut(ViewActionKind.PRODUCE, "builder", shift)
            KeyEvent.VK_ESCAPE -> { selected.clear(); cancelInput(); synchronizePanelOwner(); notice = "Selection cleared" }
            KeyEvent.VK_H -> home()
            KeyEvent.VK_HOME -> fitMap()
            KeyEvent.VK_LEFT -> { centerX -= 120 / zoom; clampCamera() }
            KeyEvent.VK_RIGHT -> { centerX += 120 / zoom; clampCamera() }
            KeyEvent.VK_UP -> { centerY -= 120 / zoom; clampCamera() }
            KeyEvent.VK_DOWN -> { centerY += 120 / zoom; clampCamera() }
        }
    }

    fun wheel(x: Float, y: Float, rotation: Int) {
        if (x >= worldBounds().right) { if (panel != null) changeActionPage(rotation); return }
        val before = worldPoint(x, y) ?: return
        zoom = (zoom * 1.18.pow(-rotation.toDouble()).toFloat()).coerceIn(.2f * density, 5f * density)
        val after = worldPoint(x, y) ?: return
        centerX += before.x - after.x
        centerY += before.y - after.y
        clampCamera()
    }

    fun screenPoint(x: Float, y: Float): KoolCanvasPoint {
        val rect = worldBounds()
        return KoolCanvasPoint((x - centerX) * zoom + (rect.left + rect.right) / 2,
            (y - centerY) * zoom + (rect.top + rect.bottom) / 2)
    }

    private fun worldPoint(x: Float, y: Float): KoolCanvasPoint? {
        val snapshot = view ?: return null
        val rect = worldBounds()
        val worldX = (x - (rect.left + rect.right) / 2) / zoom + centerX
        val worldY = (y - (rect.top + rect.bottom) / 2) / zoom + centerY
        return KoolCanvasPoint(worldX, worldY).takeIf { worldX >= 0 && worldY >= 0 &&
            worldX < snapshot.mapWidth.toFloat() * snapshot.tileWidth && worldY < snapshot.mapHeight.toFloat() * snapshot.tileHeight }
    }

    private fun worldBounds() = KoolCanvasRect(12 * density, 76 * density,
        (viewport.width - 248 * density).coerceAtLeast(100 * density), (viewport.height - 32 * density).coerceAtLeast(120 * density))

    private fun clampCamera() {
        view?.let { centerX = centerX.coerceIn(0f, it.mapWidth.toFloat() * it.tileWidth); centerY = centerY.coerceIn(0f, it.mapHeight.toFloat() * it.tileHeight) }
    }

    private fun home() {
        val snapshot = view ?: return
        val own = snapshot.units.filter { it.teamId == snapshot.teamId }
        centerX = own.map { it.x }.average().takeIf { it.isFinite() }?.toFloat() ?: snapshot.mapWidth * snapshot.tileWidth / 2f
        centerY = own.map { it.y }.average().takeIf { it.isFinite() }?.toFloat() ?: snapshot.mapHeight * snapshot.tileHeight / 2f
        zoom = 1.5f * density
        clampCamera()
    }

    private fun fitMap() {
        val snapshot = view ?: return
        centerX = snapshot.mapWidth * snapshot.tileWidth / 2f
        centerY = snapshot.mapHeight * snapshot.tileHeight / 2f
        zoom = min(worldBounds().width / (snapshot.mapWidth * snapshot.tileWidth),
            worldBounds().height / (snapshot.mapHeight * snapshot.tileHeight)) * .95f
    }

    private fun nearest(x: Float, y: Float): ViewUnit? = view?.units?.minByOrNull {
        displayedPoint(it).let { p -> hypot((p.x - x).toDouble(), (p.y - y).toDouble()) }
    }?.takeIf { displayedPoint(it).let { p -> hypot((p.x - x).toDouble(), (p.y - y).toDouble()) } < 18 * density }

    private fun displayedPoint(unit: ViewUnit) = displayedPositions[unit.handle] ?: screenPoint(unit.x, unit.y)

    private fun cancelModes() { attackMove = false; pendingAction = null }

    private fun commandsAvailable(): Boolean = connected && !readOnly && !synchronizing &&
        view?.let { it.matchStatus.phase == MatchPhase.RUNNING &&
            it.teamId !in it.matchStatus.defeatedTeamIds } == true

    private fun selectedActionOwner(): Long = if (commandsAvailable()) selected.singleOrNull()?.takeIf { handle ->
        view?.units?.any { it.handle == handle && it.controllable } == true
    } ?: 0L else 0L

    private fun synchronizePanelOwner() {
        val owner = selectedActionOwner()
        val unit = view?.units?.firstOrNull { it.handle == owner }
        val type = unit?.typeName
        if (owner == panelOwner && type == panelOwnerType && unit?.upgradeLevel == panelOwnerUpgradeLevel) return
        panelOwner = owner
        panelOwnerType = type
        panelOwnerUpgradeLevel = unit?.upgradeLevel
        panel = null; pendingAction = null; actionPage = 0; drawnPanelSequence = 0L; drawnActionPage = -1
        buttons = buttons.filter { it.actionHandle == 0L && it.pageDelta == 0 }
    }

    private fun fresh(receipt: PanelReceipt, now: Long): Boolean {
        val snapshot = view ?: return false
        return receipt.value.unitHandle == selectedActionOwner() && receipt.value.teamId == snapshot.teamId &&
            receipt.value.viewSequence == snapshot.sequence &&
            now - receipt.receivedAt <= 3_000_000_000L
    }

    private fun maintainActionPanel(now: Long) {
        lastRenderAt = now
        synchronizePanelOwner()
        if (commandsAvailable()) selectionChanged(selected.sorted())
    }

    /** Current sampled UI frame only; this is not a network panel request/reply decoder. */
    fun updateActionPanel(next: PlayerActionPanel, nowNanos: Long = System.nanoTime()) {
        if (!commandsAvailable()) return
        synchronizePanelOwner()
        val snapshot = view ?: return
        if (next.viewSequence != snapshot.sequence || next.teamId != snapshot.teamId ||
            next.unitHandle == 0L || next.unitHandle != panelOwner) return
        val previousPending = pendingAction
        panel = PanelReceipt(next, nowNanos)
        actionPage = actionPage.coerceIn(0, actionPageCount() - 1)
        if (previousPending != null) {
            val replacement = next.actions.singleOrNull { ActionIdentity.from(it) == previousPending.identity }
                ?.takeIf { it.readiness != ActionReadiness.BLOCKED }
            pendingAction = replacement?.let { previousPending.copy(actionHandle = it.handle) }
            if (replacement == null) notice = "Targeted action changed; choose it again"
        }
    }

    fun clearActionPanel() {
        panel = null
        pendingAction = null
        drawnPanelSequence = 0
        drawnActionPage = -1
    }

    private fun currentAction(handle: Long): ViewAction? = panel?.takeIf { commandsAvailable() && fresh(it, lastRenderAt) }
        ?.value?.actions?.singleOrNull { it.handle == handle }

    private fun actionShortcut(kind: ViewActionKind, type: String, queued: Boolean) {
        val action = panel?.value?.actions?.singleOrNull { it.kind == kind && it.producedTypeKey == type }
        if (action == null) notice = "This action is not in the current panel"
        else activateAction(action.handle, queued = queued)
    }

    private fun activateAction(handle: Long, cancel: Boolean = false, queued: Boolean = false) {
        val action = currentAction(handle) ?: run { notice = "Actions are no longer current"; return }
        if (cancel) { if (action.canCancel) issueAction(handle, cancel = true); return }
        if (action.readiness == ActionReadiness.BLOCKED) { notice = "Action is blocked"; return }
        cancelModes()
        when (action.targetMode) {
            ActionTargetMode.NONE -> issueAction(handle)
            ActionTargetMode.POINT, ActionTargetMode.VISIBLE_UNIT -> {
                pendingAction = PendingAction(handle, ActionIdentity.from(action), queued && action.kind in QUEUED_ACTIONS)
                notice = if (action.targetMode == ActionTargetMode.POINT) "Choose ground for ${actionLabel(action)}; right-click cancels"
                    else "Choose a disclosed unit for ${actionLabel(action)}; right-click cancels"
            }
        }
    }

    private fun issueAction(handle: Long, x: Float = 0f, y: Float = 0f, target: Long = 0,
                            queued: Boolean = false, cancel: Boolean = false): Boolean {
        val snapshot = view ?: return false
        val receipt = panel ?: return false
        val action = currentAction(handle) ?: return false
        if (cancel) { if (!action.canCancel || x != 0f || y != 0f || target != 0L) return false }
        else {
            if (action.readiness == ActionReadiness.BLOCKED) return false
            when (action.targetMode) {
                ActionTargetMode.NONE -> if (x != 0f || y != 0f || target != 0L) return false
                ActionTargetMode.POINT -> if (target != 0L || !x.isFinite() || !y.isFinite() || x < 0 || y < 0 ||
                    x >= snapshot.mapWidth.toFloat() * snapshot.tileWidth || y >= snapshot.mapHeight.toFloat() * snapshot.tileHeight) return false
                ActionTargetMode.VISIBLE_UNIT -> if (x != 0f || y != 0f || snapshot.units.none { it.handle == target }) return false
            }
        }
        return try {
            sendAction(ViewActionCommand(++sequence, snapshot.sequence, receipt.value.panelSequence,
                receipt.value.unitHandle, handle, when {
                    cancel || action.targetMode == ActionTargetMode.NONE -> CommandTarget.None
                    action.targetMode == ActionTargetMode.POINT -> CommandTarget.Point(x, y)
                    else -> CommandTarget.Unit(target)
                }, queued && !cancel && action.kind in QUEUED_ACTIONS, cancel))
            actionSendCount++
            if (cancel) actionCancelSendCount++
            notice = if (cancel) "Cancellation requested" else "Requested ${actionLabel(action)}"
            true
        } catch (_: Exception) { notice = "Action request failed"; false }
    }

    private fun actionsPerPage() = ((viewport.height / density - 292) / 62).toInt().coerceIn(1, 9)
    private fun actionPageCount() = max(1, ((panel?.value?.actions?.size ?: 0) + actionsPerPage() - 1) / actionsPerPage())
    private fun changeActionPage(delta: Int) { actionPage = (actionPage + delta).coerceIn(0, actionPageCount() - 1) }

    private fun issue(kind: OrderKind, x: Float = 0f, y: Float = 0f, target: Long = 0): Boolean {
        val snapshot = view ?: return false
        if (!connected) { notice = "Disconnected: orders are disabled"; return false }
        if (synchronizing) { notice = "Synchronizing: orders are disabled"; return false }
        if (readOnly) { notice = "Replay: orders are disabled"; return false }
        if (snapshot.matchStatus.phase == MatchPhase.FINISHED) { notice = "Match finished: orders are disabled"; return false }
        if (selected.isEmpty()) { notice = "Select your units first"; return false }
        return try {
            val destination = when (kind) {
                OrderKind.MOVE, OrderKind.ATTACK_MOVE -> CommandTarget.Point(x, y)
                OrderKind.ATTACK_UNIT -> CommandTarget.Unit(target)
                OrderKind.STOP -> CommandTarget.None
            }
            sendIntent(ViewUnitOrder(++sequence, snapshot.sequence, kind, selected.toList(), destination))
            true
        } catch (failure: Exception) { notice = "Order failed: ${failure.message ?: "connection unavailable"}"; false }
    }

    private fun drawWorld(snapshot: PlayerViewSnapshot, now: Long) {
        val rect = worldBounds()
        buffer.save(); buffer.clip(rect)
        buffer.translate((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
        buffer.scale(zoom, zoom); buffer.translate(-centerX, -centerY)
        val lowX = floor((centerX - rect.width / zoom / 2) / snapshot.tileWidth).toInt().coerceAtLeast(0)
        val highX = ceil((centerX + rect.width / zoom / 2) / snapshot.tileWidth).toInt().coerceAtMost(snapshot.mapWidth)
        val lowY = floor((centerY - rect.height / zoom / 2) / snapshot.tileHeight).toInt().coerceAtLeast(0)
        val highY = ceil((centerY + rect.height / zoom / 2) / snapshot.tileHeight).toInt().coerceAtMost(snapshot.mapHeight)
        val visual = snapshot.mapVisual?.takeIf { it.catalogVersion == PublicTerrainCatalog.VERSION &&
            it.groundTileIds.size.toLong() == snapshot.mapWidth.toLong() * snapshot.mapHeight &&
            it.decorationTileIds.size == it.groundTileIds.size }
        // Submit the complete Ground and Items layers before any unit or fog command.
        for (y in lowY until highY) for (x in lowX until highX) {
            val id = visual?.groundTileIds?.get(y * snapshot.mapWidth + x)
            val sprite = id?.let(assets::terrainTile)
            if (sprite != null) {
                buffer.drawTexture(sprite.texture, tileBounds(snapshot, x, y), KoolCanvasPaint.DefaultNearest, sprite.source)
                renderedGroundCells++
                renderedTerrainTextures += sprite.texture.id
            }
            else if (id != 0) drawDebugTerrain(snapshot, x, y)
        }
        if (visual != null) for (y in lowY until highY) for (x in lowX until highX) {
            assets.terrainTile(visual.decorationTileIds[y * snapshot.mapWidth + x])?.let { sprite ->
                buffer.drawTexture(sprite.texture, tileBounds(snapshot, x, y),
                    KoolCanvasPaint.DefaultNearest, sprite.source)
                renderedDecorationCells++
                renderedTerrainTextures += sprite.texture.id
            }
        }
        displayedPositions.clear()
        for (unit in snapshot.units.sortedWith(compareBy<ViewUnit> { it.height > 0 }.thenBy { it.y })) {
            val x = unit.x
            val y = unit.y
            displayedPositions[unit.handle] = screenPoint(x, y - unit.height)
            val teamColor = when (unit.teamId) {
                0 -> 0xff16b84e.toInt(); 1 -> 0xffdf3b32.toInt()
                in 2..63 -> java.awt.Color.HSBtoRGB(((unit.teamId - 2) * .61803398875 % 1).toFloat(), .72f, .90f)
                else -> 0xffa0a7ae.toInt()
            }
            val sprite = assets.unit(unit.typeName, teamColor, unit.upgradeLevel)
            if (unit.handle in selected) buffer.drawCircle(KoolCanvasPoint(x, y),
                max(sprite?.width ?: 18f, sprite?.height ?: 18f) / 2 + 4, paint(0xff94f1a7.toInt()).copy(style = KoolCanvasPaintStyle.Stroke, strokeWidth = 1.5f / zoom))
            if (sprite == null) buffer.drawCircle(KoolCanvasPoint(x, y - unit.height), 8f, paint(0xffdddddd.toInt()))
            else {
                sprite.shadow?.takeIf { assets.shadowVisible(sprite, unit.height, unit.buildProgress) }?.let { shadow ->
                    buffer.save(); buffer.translate(x + sprite.shadowOffsetX, y + sprite.shadowOffsetY)
                    if (sprite.rotatesWithHeading) buffer.rotate(unit.heading + sprite.headingOffsetDegrees)
                    buffer.drawTexture(shadow, KoolCanvasRect(-sprite.bodyPivotX, -sprite.bodyPivotY,
                        sprite.width - sprite.bodyPivotX, sprite.height - sprite.bodyPivotY),
                        KoolCanvasPaint.Default, sprite.shadowSource ?: shadow.fullRect)
                    buffer.restore()
                }
                buffer.save(); buffer.translate(x, y - unit.height)
                if (sprite.rotatesWithHeading) buffer.rotate(unit.heading + sprite.headingOffsetDegrees)
                buffer.drawTexture(sprite.body, KoolCanvasRect(-sprite.bodyPivotX, -sprite.bodyPivotY,
                    sprite.width - sprite.bodyPivotX, sprite.height - sprite.bodyPivotY),
                    KoolCanvasPaint.Default.copy(alphaMultiplier = .3f + .7f * unit.buildProgress), sprite.bodySource)
                buffer.restore()
                for (part in sprite.fixedParts) {
                    buffer.save(); buffer.translate(x, y - unit.height)
                    // Only the audited static rest pose is available, never an invented aim angle.
                    buffer.rotate(unit.heading + sprite.headingOffsetDegrees)
                    buffer.translate(part.offsetX, part.offsetY); buffer.rotate(part.angle)
                    buffer.drawTexture(part.texture, KoolCanvasRect(-part.pivotX, -part.pivotY,
                        part.width - part.pivotX, part.height - part.pivotY), KoolCanvasPaint.Default, part.source)
                    buffer.restore()
                }
                sprite.turret?.let { turret ->
                    buffer.save(); buffer.translate(x + sprite.turretOffsetX, y - unit.height + sprite.turretOffsetY)
                    buffer.rotate(unit.heading + sprite.headingOffsetDegrees)
                    buffer.drawTexture(turret, KoolCanvasRect(-sprite.turretPivotX, -sprite.turretPivotY,
                        sprite.turretWidth - sprite.turretPivotX, sprite.turretHeight - sprite.turretPivotY),
                        KoolCanvasPaint.Default, sprite.turretSource ?: turret.fullRect)
                    buffer.restore()
                }
            }
            if (unit.handle in selected || unit.buildProgress < 1f) {
                val bar = max(22f, sprite?.width ?: 22f)
                val bottom = y + (sprite?.height ?: 18f) / 2 + 5
                buffer.drawRect(KoolCanvasRect(x - bar / 2, bottom, x + bar / 2, bottom + 3), paint(0xff20252b.toInt()))
                buffer.drawRect(KoolCanvasRect(x - bar / 2, bottom, x - bar / 2 + bar * (unit.health / unit.maxHealth).coerceIn(0f, 1f), bottom + 3), paint(0xff64dc8a.toInt()))
                if (unit.buildProgress < 1f) buffer.drawRect(KoolCanvasRect(x - bar / 2, bottom + 4, x - bar / 2 + bar * unit.buildProgress, bottom + 7), paint(0xff65aaff.toInt()))
            }
        }
        for (effect in timelineEffects) {
            val fraction = if (effect.duration == 0L) 0f else effect.age.toFloat() / effect.duration
            val tint = when (effect.kind) { 1 -> 0xffffec91.toInt(); 2 -> 0xffffad59.toInt(); else -> 0xffed7953.toInt() }
            buffer.drawCircle(KoolCanvasPoint(effect.x, effect.y - effect.height), 3f + fraction * 12f,
                paint(tint).copy(alphaMultiplier = 1f - fraction,
                    style = KoolCanvasPaintStyle.Stroke, strokeWidth = 2f / zoom))
        }
        // Fog is a display mask; the server already omitted unauthorized entities and references.
        for (y in lowY until highY) for (x in lowX until highX) {
            val fog = snapshot.fog[y * snapshot.mapWidth + x].toInt()
            if (fog >= 5) buffer.drawRect(KoolCanvasRect(x * snapshot.tileWidth.toFloat(), y * snapshot.tileHeight.toFloat(),
                (x + 1) * snapshot.tileWidth.toFloat(), (y + 1) * snapshot.tileHeight.toFloat()),
                paint(if (fog >= 10) 0xee000000.toInt() else 0x99000000.toInt()))
        }
        buffer.restore()
        selectionStart?.let { start -> buffer.drawRect(KoolCanvasRect(min(start.x, pointer.x), min(start.y, pointer.y),
            max(start.x, pointer.x), max(start.y, pointer.y)), paint(0xff80e39b.toInt()).copy(style = KoolCanvasPaintStyle.Stroke, strokeWidth = density)) }
        if (pendingAction?.identity?.targetMode == ActionTargetMode.POINT && rect.contains(pointer.x, pointer.y)) {
            buffer.drawRect(KoolCanvasRect(pointer.x - 18 * density, pointer.y - 18 * density, pointer.x + 18 * density, pointer.y + 18 * density),
                paint(0xffffd477.toInt()).copy(style = KoolCanvasPaintStyle.Stroke, strokeWidth = 2 * density))
        }
    }

    private fun tileBounds(snapshot: PlayerViewSnapshot, x: Int, y: Int) = KoolCanvasRect(
        x * snapshot.tileWidth.toFloat(), y * snapshot.tileHeight.toFloat(),
        (x + 1) * snapshot.tileWidth.toFloat(), (y + 1) * snapshot.tileHeight.toFloat())

    private fun drawDebugTerrain(snapshot: PlayerViewSnapshot, x: Int, y: Int) {
        val terrain = snapshot.terrain[y * snapshot.mapWidth + x].toInt()
        val base = when { terrain and 4 != 0 -> 0xffaa943e.toInt(); terrain and 2 != 0 -> 0xff716a60.toInt()
            terrain and 1 != 0 -> 0xff285b7f.toInt(); else -> 0xff49683d.toInt() }
        buffer.drawRect(tileBounds(snapshot, x, y), paint(base))
    }

    private fun drawHud(snapshot: PlayerViewSnapshot?) {
        buffer.drawRect(KoolCanvasRect(0f, 0f, viewport.width.toFloat(), 68 * density), paint(0xff1d2935.toInt()))
        label("RWXX ${io.github.rwx.AppMetadata.VERSION_NAME}  |  Timeline protocol ${TimelineProtocol.VERSION}", 16f, 24f, 0xfff2f5fa.toInt())
        val state = when {
            readOnly && !connected -> sourceLabel + (error?.let { ": $it" } ?: "")
            !connected -> "Disconnected: ${error ?: "view is no longer updating"}"
            error != null -> "$sourceLabel: $error"
            synchronizing -> "Synchronizing player view: orders are disabled"
            snapshot == null -> "$sourceLabel: waiting for server view"
            else -> "$sourceLabel | Seat ${snapshot.teamId}   Tick ${snapshot.tick}   Credits ${snapshot.credits.toInt()}   Selected ${selected.size}   Zoom ${"%.1f".format(zoom / density)}x"
        }
        label(state, 16f, 48f, if (connected) 0xff8de3ad.toInt() else 0xffff8c84.toInt())
        val sidebarX = viewport.width / density - 232
        buffer.drawRect(KoolCanvasRect(sidebarX * density, 76 * density, viewport.width.toFloat() - 12 * density, viewport.height - 32 * density), paint(0xff1c2732.toInt()))
        val selectedUnit = snapshot?.units?.singleOrNull { it.handle in selected }
        label(selectedUnit?.let { "${it.typeName}  ${(it.buildProgress * 100).roundToInt()}%" } ?: "Unit commands", sidebarX + 12, 100f)
        buttons = dynamicButtons(sidebarX)
        buttons.forEach { button ->
            buffer.drawRect(button.bounds, paint(if (button.enabled) 0xff334858.toInt() else 0xff26313b.toInt()))
            val row = button.actionHandle != 0L && !button.cancel
            val compact = button.bounds.height < 26 * density
            val textSize = (if (compact) 10 else if (row) 12 else 13) * density
            buffer.drawText(elide(button.label, button.bounds.width - 12 * density, textSize),
                KoolCanvasPoint(button.bounds.left + 6 * density, button.bounds.top + (if (compact) 14 else if (row) 16 else 20) * density),
                KoolCanvasPaint(color = color(if (button.enabled) 0xffedf4fa.toInt() else 0xff84929d.toInt()), textSize = textSize))
            if (row) {
                buffer.drawText(elide(button.detail, button.bounds.width - 12 * density, 10 * density),
                    KoolCanvasPoint(button.bounds.left + 6 * density, button.bounds.top + 31 * density),
                    KoolCanvasPaint(color = color(0xffd2c498.toInt()), textSize = 10 * density))
                buffer.drawText(elide(button.status, button.bounds.width - 72 * density, 10 * density),
                    KoolCanvasPoint(button.bounds.left + 6 * density, button.bounds.top + 47 * density),
                    KoolCanvasPaint(color = color(0xffaebfcd.toInt()), textSize = 10 * density))
            }
        }
        val help = listOf("F5 live | F6 pause | F7 reverse", "[ / ]: seek 10s | F8 play",
            "Ctrl+N: new view | Tab: switch", "1-9: action | Ctrl+1-9: cancel")
        help.forEachIndexed { index, text -> label(text, sidebarX + 10, viewport.height / density - 78 + index * 12, 0xffa5b7c7.toInt(), 10f) }
        drawActionTooltip()
        val outcome = snapshot?.matchStatus?.takeIf { it.phase == MatchPhase.FINISHED }?.let {
            when { it.winnerTeamIds.isEmpty() -> "Match finished: draw"; snapshot.teamId in it.winnerTeamIds -> "Victory"; else -> "Defeat" }
        }
        val bottom = outcome ?: notice
        buffer.drawText(elide(bottom, viewport.width - 24 * density, 13 * density), KoolCanvasPoint(12 * density, viewport.height - 10 * density),
            KoolCanvasPaint(color = color(if (outcome != null) 0xffffd477.toInt() else 0xffd0dde7.toInt()), textSize = 13 * density))
        if (outcome != null) {
            val rect = worldBounds()
            buffer.drawRect(KoolCanvasRect(rect.left, rect.top, rect.right, rect.top + 62 * density), paint(0xdd152432.toInt()))
            buffer.drawText(outcome, KoolCanvasPoint((rect.left + rect.right) / 2, rect.top + 40 * density),
                KoolCanvasPaint(color = color(0xffffd477.toInt()), textSize = 28 * density, textAlign = KoolCanvasTextAlign.Center))
        }
    }

    private fun label(text: String, x: Float, y: Float, argb: Int = 0xffd6e0e9.toInt(), size: Float = 14f) {
        buffer.drawText(elide(text, viewport.width - x * density - 12 * density, size * density), KoolCanvasPoint(x * density, y * density),
            KoolCanvasPaint(color = color(argb), textSize = size * density))
    }

    private fun elide(text: String, maxWidth: Float, size: Float): String {
        if (KoolCanvasFontRegistry.textWidth(text, size) <= maxWidth) return text
        var end = text.length
        while (end > 0 && KoolCanvasFontRegistry.textWidth(text.take(end) + "...", size) > maxWidth) end--
        return text.take(end) + "..."
    }

    private fun dynamicButtons(sidebarX: Float): List<ActionButton> = buildList {
        val left = (sidebarX + 10) * density
        val right = viewport.width - 22 * density
        val middle = (left + right) / 2
        add(ActionButton(KeyEvent.VK_A, KoolCanvasRect(left, 116 * density, middle - 2 * density, 145 * density), commandsAvailable() && selected.isNotEmpty(), "A  Attack-move"))
        add(ActionButton(KeyEvent.VK_S, KoolCanvasRect(middle + 2 * density, 116 * density, right, 145 * density), commandsAvailable() && selected.isNotEmpty(), "S  Stop"))
        val receipt = panel
        drawnPanelSequence = receipt?.value?.panelSequence ?: 0L
        drawnActionPage = -1
        if (receipt == null) {
            label(when { readOnly -> "Replay: actions unavailable"; !connected -> "Disconnected"; panelOwner == 0L -> "Select one of your units"; else -> "Waiting for current actions" }, sidebarX + 10, 170f, size = 12f)
            return@buildList
        }
        actionPage = actionPage.coerceIn(0, actionPageCount() - 1)
        drawnActionPage = actionPage
        label("Actions ${receipt.value.actions.size} | Page ${actionPage + 1}/${actionPageCount()}", sidebarX + 10, 170f, size = 12f)
        val start = actionPage * actionsPerPage()
        receipt.value.actions.drop(start).take(actionsPerPage()).forEachIndexed { index, action ->
            val top = (180 + index * 62) * density
            val ready = commandsAvailable() && fresh(receipt, lastRenderAt)
            val status = buildList {
                add(when (action.readiness) { ActionReadiness.READY -> "Ready"; ActionReadiness.BLOCKED -> "Blocked"; ActionReadiness.UNKNOWN -> "Unknown" })
                if (action.queuedCount >= 0) add("Q ${action.queuedCount}")
                if (action.stockpileCount >= 0) add("Stock ${action.stockpileCount}")
                if (action.progress >= 0) add("${(action.progress * 100).roundToInt()}%")
            }.joinToString(" | ")
            add(ActionButton(KeyEvent.VK_1 + index, KoolCanvasRect(left, top, right, top + 58 * density),
                ready && action.readiness != ActionReadiness.BLOCKED, "${index + 1}  ${actionLabel(action)}",
                actionHandle = action.handle, panelSequence = receipt.value.panelSequence, detail = actionCost(action), status = status))
            if (action.canCancel) add(ActionButton(0,
                KoolCanvasRect(right - 62 * density, top + 35 * density, right - 3 * density, top + 55 * density),
                ready, "Cancel", actionHandle = action.handle, panelSequence = receipt.value.panelSequence, cancel = true))
        }
        val top = viewport.height - 121 * density
        add(ActionButton(0, KoolCanvasRect(left, top, middle - 2 * density, top + 26 * density),
            actionPage > 0, "Previous", pageDelta = -1))
        add(ActionButton(0, KoolCanvasRect(middle + 2 * density, top, right, top + 26 * density),
            actionPage + 1 < actionPageCount(), "Next", pageDelta = 1))
    }

    internal fun actionLabel(action: ViewAction): String {
        val module = MODULE_PREFIX.find(action.presentationKey)
        val prefix = module?.let { "Slot ${it.groupValues[1]} / " }.orEmpty()
        val key = if (module == null) action.presentationKey else action.presentationKey.removeRange(module.range)
        val suffix = key.substringAfterLast('.')
        val semantic = SEMANTIC_LABELS[suffix] ?: SELECT_SLOT.matchEntire(suffix)?.let {
            "Select slot ${it.groupValues[1]}"
        }
        if (semantic != null) return prefix + semantic
        val ordinal = key.split('.').lastOrNull { it.toIntOrNull() != null }?.let { " #$it" }.orEmpty()
        val type = action.producedTypeKey.takeIf { it.isNotEmpty() }?.let(::keyLabel)
        return prefix + when (action.kind) {
            ViewActionKind.BUILD -> type?.let { "Build $it" } ?: "Build$ordinal"
            ViewActionKind.PRODUCE -> type?.let { "Produce $it" } ?: "Produce$ordinal"
            ViewActionKind.UPGRADE -> type?.let { "Upgrade $it" } ?: "Upgrade$ordinal"
            ViewActionKind.RALLY -> "Set rally point"
            ViewActionKind.REPAIR -> "Repair"
            ViewActionKind.RECLAIM -> "Reclaim"
            ViewActionKind.LOAD_INTO -> "Enter transport"
            ViewActionKind.LOAD_UP -> "Load passenger"
            ViewActionKind.GUARD -> "Guard unit"
            ViewActionKind.PATROL -> "Patrol"
            ViewActionKind.ATTACK_MODE -> ATTACK_MODE_LABELS[suffix] ?: "Attack mode$ordinal"
            ViewActionKind.UNLOAD -> "Unload"
            ViewActionKind.STOP_UNLOAD -> "Stop unloading"
            ViewActionKind.TARGET_GROUND -> "Ground action$ordinal"
            ViewActionKind.TOGGLE -> "Action$ordinal"
        }
    }

    private fun keyLabel(key: String): String = key.removePrefix("action.").removePrefix("stock.")
        .replace(KEY_WORD_BOUNDARY, "$1 $2").replace('_', ' ').replace('.', ' ').replace('-', ' ')
        .trim().replaceFirstChar { it.uppercaseChar() }

    private fun actionCost(action: ViewAction): String {
        fun cost(value: ViewActionCost?): String = value?.let {
            buildList {
                if (it.credits != 0) add("${it.credits}cr")
                if (it.energy != 0f) add("${it.energy.roundToInt()} energy")
                if (it.hp != 0f) add("${it.hp.roundToInt()} HP")
                if (it.shield != 0f) add("${it.shield.roundToInt()} shield")
                if (it.ammo != 0) add("${it.ammo} ammo")
            }.joinToString(", ").ifEmpty { "Free" }
        } ?: "Unknown"
        return "Cost ${cost(action.upfrontCost)}" + if (action.streamingCost != null) "; stream ${cost(action.streamingCost)}" else ""
    }

    /** Long costs and queue values remain inspectable even when a compact row must elide them. */
    private fun drawActionTooltip() {
        val button = buttons.lastOrNull { it.actionHandle != 0L && it.bounds.contains(pointer.x, pointer.y) } ?: return
        val action = currentAction(button.actionHandle) ?: return
        val rect = worldBounds()
        val width = min(rect.width - 12 * density, 500 * density).coerceAtLeast(40 * density)
        val size = 11 * density
        val texts = listOf(actionLabel(action), actionCost(action),
            "${action.readiness.name.lowercase().replaceFirstChar { it.uppercaseChar() }} | Queue ${if (action.queuedCount < 0) "unknown" else action.queuedCount} | Stock ${if (action.stockpileCount < 0) "unknown" else action.stockpileCount}" +
                if (action.progress >= 0) " | Progress ${(action.progress * 100).roundToInt()}%" else "")
        val lines = texts.flatMap { text ->
            val result = mutableListOf<String>()
            var rest = text
            while (rest.isNotEmpty()) {
                var count = rest.length
                while (count > 1 && KoolCanvasFontRegistry.textWidth(rest.take(count), size) > width - 12 * density) count--
                result += rest.take(count); rest = rest.drop(count).trimStart()
            }
            result
        }
        val top = (rect.bottom - (lines.size * 15 + 12) * density).coerceAtLeast(rect.top)
        buffer.drawRect(KoolCanvasRect(rect.left + 6 * density, top, rect.left + 6 * density + width, rect.bottom), paint(0xef172632.toInt()))
        lines.forEachIndexed { index, text -> buffer.drawText(text,
            KoolCanvasPoint(rect.left + 12 * density, top + (index * 15 + 17) * density),
            KoolCanvasPaint(color = color(0xffe4edf5.toInt()), textSize = size)) }
    }

    private data class PanelReceipt(val value: PlayerActionPanel, val receivedAt: Long)
    private data class ActionIdentity(val kind: ViewActionKind, val targetMode: ActionTargetMode, val presentationKey: String, val producedTypeKey: String) {
        companion object { fun from(action: ViewAction) = ActionIdentity(action.kind, action.targetMode, action.presentationKey, action.producedTypeKey) }
    }
    private data class PendingAction(val actionHandle: Long, val identity: ActionIdentity, val queued: Boolean)
    private data class ActionButton(val key: Int, val bounds: KoolCanvasRect, val enabled: Boolean, val label: String,
        val actionHandle: Long = 0, val panelSequence: Long = 0, val cancel: Boolean = false, val pageDelta: Int = 0,
        val detail: String = "", val status: String = "")
    private companion object {
        val KEY_WORD_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        val MODULE_PREFIX = Regex("^module\\.slot([1-9][0-9]{0,2})\\.")
        val SELECT_SLOT = Regex("selectSlot([1-6])")
        val SEMANTIC_LABELS = mapOf("selectBuildSlot" to "Select build slot", "cancelConstruction" to "Cancel construction",
            "reclaimModule" to "Reclaim module", "buildNuke" to "Build nuke", "launchNuke" to "Launch nuke",
            "buildAntiNuke" to "Build anti-nuke", "blink" to "Blink", "enableAntiNuke" to "Enable anti-nuke",
            "disableAntiNuke" to "Disable anti-nuke")
        val ATTACK_MODE_LABELS = mapOf("onlyInRange" to "Attack: in range", "guardArea" to "Attack: guard area",
            "aggressive" to "Attack: aggressive", "holdFire" to "Hold fire", "returnFire" to "Return fire")
        val QUEUED_ACTIONS = setOf(ViewActionKind.BUILD, ViewActionKind.REPAIR, ViewActionKind.RECLAIM,
            ViewActionKind.LOAD_INTO, ViewActionKind.LOAD_UP, ViewActionKind.GUARD, ViewActionKind.PATROL)
    }
    private fun KoolCanvasRect.contains(x: Float, y: Float) = x >= left && y >= top && x < right && y < bottom
    private fun color(argb: Int) = KoolCanvasColor(argb)
    private fun paint(argb: Int) = KoolCanvasPaint(color = color(argb))
}

/** Hashes use the row-major wire integers in big-endian order, independent of native byte order. */
internal data class AuthoritativeMapVisualProfile(
    val catalogVersion: Int = 0,
    val groundCells: Int = 0,
    val decorationCells: Int = 0,
    val resourcePools: Int = 0,
    val groundSha256: String = "",
    val decorationSha256: String = "",
) {
    companion object {
        fun from(view: PlayerViewSnapshot): AuthoritativeMapVisualProfile {
            val visual = view.mapVisual
            return AuthoritativeMapVisualProfile(
                catalogVersion = visual?.catalogVersion ?: 0,
                groundCells = visual?.groundTileIds?.count { it != 0 } ?: 0,
                decorationCells = visual?.decorationTileIds?.count { it != 0 } ?: 0,
                resourcePools = view.terrain.count { it.toInt() and 4 != 0 },
                groundSha256 = visual?.groundTileIds?.let(::hash) ?: "",
                decorationSha256 = visual?.decorationTileIds?.let(::hash) ?: "",
            )
        }

        private fun hash(ids: IntArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(4)
            for (id in ids) {
                bytes[0] = (id ushr 24).toByte(); bytes[1] = (id ushr 16).toByte()
                bytes[2] = (id ushr 8).toByte(); bytes[3] = id.toByte()
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
