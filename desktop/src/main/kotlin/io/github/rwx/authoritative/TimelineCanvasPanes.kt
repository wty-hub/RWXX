package io.github.rwx.authoritative

import io.github.rwx.render.canvas.*

internal data class TimelinePaneViewport(val paneId: Int, val rect: KoolCanvasRect, val viewport: KoolCanvasViewport)

/** Pure root-surface composition. Each renderer keeps its own camera and local input coordinates. */
internal object TimelineCanvasPanes {
    fun layout(viewport: KoolCanvasViewport, paneIds: List<Int>): List<TimelinePaneViewport> {
        require(paneIds.size in 1..4 && paneIds.all { it >= 0 } && paneIds.distinct().size == paneIds.size)
        val splitX = viewport.width / 2
        val splitY = viewport.height / 2
        return paneIds.mapIndexed { index, id ->
            val left = if (paneIds.size == 1 || index % 2 == 0) 0 else splitX
            val right = if (paneIds.size == 1 || index % 2 == 1) viewport.width else splitX
            val top = if (paneIds.size <= 2 || index < 2) 0 else splitY
            val bottom = if (paneIds.size <= 2 || index >= 2) viewport.height else splitY
            TimelinePaneViewport(id, KoolCanvasRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()),
                KoolCanvasViewport(right - left, bottom - top))
        }
    }

    /** Half-open edges select exactly one pane; the unused fourth cell of a three-pane layout is empty. */
    fun hitTest(panes: List<TimelinePaneViewport>, point: KoolCanvasPoint): TimelinePaneViewport? =
        if (!point.x.isFinite() || !point.y.isFinite()) null else panes.firstOrNull { contains(it.rect, point) }

    fun localPoint(pane: TimelinePaneViewport, point: KoolCanvasPoint): KoolCanvasPoint? =
        if (!point.x.isFinite() || !point.y.isFinite() || !contains(pane.rect, point)) null
        else KoolCanvasPoint(point.x - pane.rect.left, point.y - pane.rect.top)

    /**
     * All panes draw in input order, including inactive panes. Stats describe the active pane.
     * A local Clear is a clipped rectangle; it can never replace the whole surface clear color.
     * Offscreen targets are rejected instead of translating their unrelated coordinate system.
     */
    fun compose(viewport: KoolCanvasViewport, frames: List<Pair<Int, KoolCanvasFrame>>, activeId: Int): KoolCanvasFrame {
        val panes = layout(viewport, frames.map { it.first })
        require(panes.any { it.paneId == activeId }) { "Active pane is absent" }
        val commands = ArrayList<KoolCanvasCommand>()
        val surface = KoolCanvasRect.fromSize(viewport.width.toFloat(), viewport.height.toFloat())
        if (!surface.isEmpty) commands.add(KoolCanvasCommand.DrawRect(surface,
            KoolCanvasPaint(color = KoolCanvasColor(0xff080b12.toInt()), blendMode = KoolCanvasBlendMode.Source),
            KoolCanvasState(clip = surface)))
        for (index in panes.indices) {
            val pane = panes[index]
            val frame = frames[index].second
            require(frame.viewport == pane.viewport) { "Pane frame viewport differs from its assigned layout" }
            for (command in frame.commands) project(command, pane)?.let(commands::add)
        }
        val active = panes.first { it.paneId == activeId }
        if (active.viewport.width >= 2 && active.viewport.height >= 2) commands.add(KoolCanvasCommand.DrawRect(
            KoolCanvasRect(active.rect.left + 1, active.rect.top + 1, active.rect.right - 1, active.rect.bottom - 1),
            KoolCanvasPaint(color = KoolCanvasColor(0xff73baff.toInt()), style = KoolCanvasPaintStyle.Stroke, strokeWidth = 2f),
            KoolCanvasState(clip = active.rect)))
        return KoolCanvasFrame(viewport, commands.toList(), frames.first { it.first == activeId }.second.visualStats)
    }

    private fun project(command: KoolCanvasCommand, pane: TimelinePaneViewport): KoolCanvasCommand? = when (command) {
        is KoolCanvasCommand.Clear -> {
            require(command.renderTarget == null) { "Timeline panes do not support offscreen render targets" }
            if (pane.rect.isEmpty) null else KoolCanvasCommand.DrawRect(pane.rect,
                KoolCanvasPaint(color = command.color, blendMode = command.blendMode), KoolCanvasState(clip = pane.rect))
        }
        is KoolCanvasCommand.DrawTexture -> project(command.state, pane)?.let { command.copy(state = it) }
        is KoolCanvasCommand.DrawTextureRepeat -> project(command.state, pane)?.let { command.copy(state = it) }
        is KoolCanvasCommand.DrawTextureBatch -> project(command.state, pane)?.let { command.copy(state = it) }
        is KoolCanvasCommand.DrawRectBatch -> project(command.state, pane)?.let { command.copy(state = it) }
        is KoolCanvasCommand.DrawFogBatch -> project(command.state, pane)?.let { command.copy(state = it) }
        is KoolCanvasCommand.DrawRect -> project(command.state, pane)?.let { command.copy(state = it) }
        is KoolCanvasCommand.DrawLine -> project(command.state, pane)?.let { command.copy(state = it) }
        is KoolCanvasCommand.DrawCircle -> project(command.state, pane)?.let { command.copy(state = it) }
        is KoolCanvasCommand.DrawText -> project(command.state, pane)?.let { command.copy(state = it) }
    }

    private fun project(state: KoolCanvasState, pane: TimelinePaneViewport): KoolCanvasState? {
        require(state.renderTarget == null) { "Timeline panes do not support offscreen render targets" }
        if (pane.rect.isEmpty) return null
        val clip = state.clip?.let {
            require(it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite()) { "Invalid pane clip" }
            KoolCanvasRect(it.left + pane.rect.left, it.top + pane.rect.top,
                it.right + pane.rect.left, it.bottom + pane.rect.top).intersect(pane.rect) ?: return null
        } ?: pane.rect
        // Canvas clips have already been transformed into local viewport space. Transforming
        // them by the draw matrix again would move camera clips and allow adjacent-pane bleed.
        val translation = KoolCanvasTransform(translateX = pane.rect.left, translateY = pane.rect.top)
        return state.copy(transform = translation.multiply(state.transform), clip = clip)
    }

    private fun contains(rect: KoolCanvasRect, point: KoolCanvasPoint) =
        point.x >= rect.left && point.y >= rect.top && point.x < rect.right && point.y < rect.bottom
}
