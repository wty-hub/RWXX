package io.github.rwx.render.canvas

/** Hysteresis depends only on published display counts, never on simulation detail flags. */
internal class KoolCanvasAdaptiveVisuals {
    var simplifiedWaypoints = false
        private set
    var simplifiedShadows = false
        private set
    private var waypointUnitIds: Set<Long> = emptySet()

    fun prepare(frame: KoolCanvasFrame) {
        val stats = frame.visualStats
        if (stats == null || !stats.adaptiveBattleVisuals) {
            simplifiedWaypoints = false
            simplifiedShadows = false
            waypointUnitIds = emptySet()
            return
        }
        if (stats.selectedUnits > 256) simplifiedWaypoints = true
        else if (stats.selectedUnits < 192) simplifiedWaypoints = false
        if (stats.visibleUnits > 512) simplifiedShadows = true
        else if (stats.visibleUnits < 384) simplifiedShadows = false
        waypointUnitIds = if (simplifiedWaypoints) frame.commands.asSequence()
            .mapNotNull { it.stateOrNull() }
            .filter { it.drawRole == KoolCanvasDrawRole.Waypoint && it.semanticUnitId >= 0 }
            .map { it.semanticUnitId }.distinct().sorted().take(8).toSet() else emptySet()
    }

    fun shouldDraw(command: KoolCanvasCommand): Boolean {
        val state = command.stateOrNull() ?: return true
        return when (state.drawRole) {
            KoolCanvasDrawRole.Waypoint -> !simplifiedWaypoints || state.semanticUnitId < 0 || state.semanticUnitId in waypointUnitIds
            KoolCanvasDrawRole.UnitShadow -> !simplifiedShadows
            else -> true
        }
    }
}

internal fun KoolCanvasCommand.stateOrNull(): KoolCanvasState? = when (this) {
    is KoolCanvasCommand.DrawTexture -> state
    is KoolCanvasCommand.DrawTextureBatch -> state
    is KoolCanvasCommand.DrawTextureRepeat -> state
    is KoolCanvasCommand.DrawRect -> state
    is KoolCanvasCommand.DrawRectBatch -> state
    is KoolCanvasCommand.DrawFogBatch -> state
    is KoolCanvasCommand.DrawLine -> state
    is KoolCanvasCommand.DrawCircle -> state
    is KoolCanvasCommand.DrawText -> state
    is KoolCanvasCommand.Clear -> null
}
