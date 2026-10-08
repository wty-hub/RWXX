package io.github.rwx.render.canvas

/**
 * Lets the game-side map layer report its tile-draw counters into the canvas stage trace.
 *
 * The counters have to travel this way because the benchmark force-kills the game process, so a shutdown
 * hook never runs and its output is lost. The stage trace is already written to a file the harness reads.
 */
object CanvasStageTraceBridge {
    /** `@JvmStatic` so the Java-side map layer can call this without reaching through `INSTANCE`. */
    @JvmStatic
    fun recordTileRuns(visitedTiles: Long, draws: Long, mergedRuns: Long) {
        if (!CanvasRenderStageTrace.enabled) return
        val now = System.nanoTime()
        CanvasRenderStageTrace.recordCompleted(
            "map-tile-runs",
            now,
            now,
            visitedTiles,
            draws,
            mergedRuns,
        )
    }

    /** Which draw path the visited tiles took: atlas (mergeable) or per-tile `renderTile`. */
    @JvmStatic
    fun recordTileRunPaths(nonAtlasTiles: Long, reportCalls: Long) {
        if (!CanvasRenderStageTrace.enabled) return
        val now = System.nanoTime()
        CanvasRenderStageTrace.recordCompleted("map-tile-paths", now, now, nonAtlasTiles, reportCalls)
    }

    /**
     * Run-length histogram for the horizontal merge.
     *
     * `tiles - draws` is the command count a working repeat/tile primitive would remove, so this decides
     * whether building that primitive is worth its cross-layer cost before any of it is written.
     */
    @JvmStatic
    fun recordTileRunHistogram(
        visitedTiles: Long,
        draws: Long,
        mergedRuns: Long,
        mergedLengthSum: Long,
        maxLength: Long,
    ) {
        if (!CanvasRenderStageTrace.enabled) return
        val now = System.nanoTime()
        // `recordCompleted` carries three values, so the histogram is split across two rows. Having the
        // primitive pay off at all depends on `visitedTiles - draws`, which is the first row's value0.
        CanvasRenderStageTrace.recordCompleted(
            "map-tile-run-histogram", now, now, visitedTiles - draws, draws, mergedRuns)
        CanvasRenderStageTrace.recordCompleted(
            "map-tile-run-lengths", now, now, mergedLengthSum, maxLength, 0L)
    }
}
