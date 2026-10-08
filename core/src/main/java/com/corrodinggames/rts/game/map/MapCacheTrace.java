package com.corrodinggames.rts.game.map;

import com.corrodinggames.rts.gameFramework.GameEngine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in map-cache events, using the same monotonic clock as engine/presentation traces. */
final class MapCacheTrace {
    private static final MapCacheTrace INSTANCE = open();
    private final BufferedWriter writer;
    private final ThreadMXBean cpuClock;
    private volatile boolean enabled;

    private MapCacheTrace(BufferedWriter writer, ThreadMXBean cpuClock) {
        this.writer = writer;
        this.cpuClock = cpuClock;
        this.enabled = writer != null;
    }

    static boolean isEnabled() { return INSTANCE.enabled; }

    static CellSpan beginCell(LayerBufferManager manager, int i, int j) {
        try { return new CellSpan(manager, i, j, System.nanoTime(), INSTANCE.cpuTime()); }
        catch (RuntimeException ignored) { INSTANCE.close(); return null; }
    }

    static void endCell(CellSpan span, boolean completed) {
        if (span == null) return;
        long end = System.nanoTime();
        long cpuEnd = INSTANCE.cpuTime();
        long cpuNanos = span.cpuStart >= 0 && cpuEnd >= span.cpuStart ? cpuEnd - span.cpuStart : -1;
        INSTANCE.write(span, end, cpuNanos, "cell", "", 0, completed ? 1 : 0);
    }

    static void recordGrid(String event, LayerBufferManager manager, String axis, int direction) {
        try {
            long now = System.nanoTime();
            INSTANCE.write(new CellSpan(manager, -1, -1, now, -1), now, -1, event, axis, direction, -1);
        } catch (RuntimeException ignored) { INSTANCE.close(); }
    }

    /**
     * One `renderPendingRedraws` call.
     *
     * The zoom step rescales {@code cellWorldStepSize}, and every cell is re-scattered onto new world
     * coordinates, so a single zoom change can invalidate the whole 5x5 grid. This row states how many
     * cells one call actually rasterised and how long the call took, which is the quantity the cell
     * policy has to keep bounded.
     */
    static void recordRedrawBatch(boolean visibleOnly, boolean offscreenOnly, int renderedCount,
                                  int redrawsSinceLastCall, long elapsedNanos, int step, int gridCells) {
        if (!INSTANCE.enabled) return;
        try {
            long now = System.nanoTime();
            String mode = offscreenOnly ? "offscreen" : (visibleOnly ? "visible" : "all");
            INSTANCE.writeBatch(now - elapsedNanos, now, renderedCount, redrawsSinceLastCall, elapsedNanos,
                    step, gridCells, mode);
        } catch (RuntimeException ignored) { INSTANCE.close(); }
    }

    /**
     * One display frame's cell-invalidation demand.
     *
     * A fog change marks a 3x3 tile neighbourhood per changed tile, and `cellWorldStepSize` is
     * `cellInnerBufferPixelSize / renderScale`, so at low zoom each cell covers fewer world units and the
     * same fog activity marks more cells. This row states the demand independently of how it was served.
     */
    /**
     * One visible cell's per-frame block in the draw loop.
     *
     * This block always runs for a visible cell; `redrawn` distinguishes the frames where it also
     * rasterised the cell. The difference is what a redraw actually costs against the per-frame floor.
     */
    static void recordVisibleCell(boolean redrawn, long elapsedNanos, int step, float renderScale) {
        if (!INSTANCE.enabled) return;
        try {
            long now = System.nanoTime();
            INSTANCE.writeVisibleCell(now - elapsedNanos, now, redrawn, elapsedNanos, step, renderScale);
        } catch (RuntimeException ignored) { INSTANCE.close(); }
    }

    /**
     * A coverage assertion row: does the grid still cover the visible world?
     *
     * `cellWorldExtent = cellBufferPixelSize / renderScale` and each cell is composited one-to-one with
     * the screen, so `gridCells * cellWorldStepSize` must be at least the visible world extent. A
     * render-scale floor that is higher than the zoom makes each cell cover less world, so the grid can
     * stop covering the viewport -- the map then renders zoomed in and the missing cells cost nothing,
     * which a frame-rate measurement rewards. This row makes that failure explicit.
     */
    static void recordCoverage(int gridCells, int step, float renderScale, float visibleWorldWidth,
                               float visibleWorldHeight, float zoom) {
        if (!INSTANCE.enabled) return;
        try {
            long now = System.nanoTime();
            INSTANCE.writeCoverage(gridCells, step, renderScale, visibleWorldWidth, visibleWorldHeight, zoom, now);
        } catch (RuntimeException ignored) { INSTANCE.close(); }
    }

    private synchronized void writeCoverage(int gridCells, int step, float renderScale, float visibleWorldWidth,
                                            float visibleWorldHeight, float zoom, long now) {
        if (!enabled) return;
        try {
            long span = (long) gridCells * step;
            boolean coversX = span >= visibleWorldWidth;
            boolean coversY = span >= visibleWorldHeight;
            writer.append(Long.toString(now)).append(',').append(Long.toString(now)).append(",0,")
                    .append("coverage").append(',')
                    .append(Integer.toString(coversX && coversY ? 1 : 0)).append(',')
                    .append(Integer.toString(coversX ? 1 : 0)).append(',').append(Integer.toString(coversY ? 1 : 0))
                    .append(",0,0.0,0.0,0,0,")
                    .append(Integer.toString(step)).append(',').append(Float.toString(renderScale)).append(",0,cover,")
                    .append(Long.toString(span)).append(',').append(Float.toString(visibleWorldWidth)).append(',')
                    .append(Float.toString(visibleWorldHeight)).append(',').append(Float.toString(zoom))
                    .append(",0,0,0,0,0,0,0.0,0.0,0,0").append('\n');
        } catch (IOException | RuntimeException error) {
            close();
        }
    }

    private synchronized void writeVisibleCell(long start, long end, boolean redrawn, long elapsedNanos,
                                               int step, float renderScale) {
        if (!enabled) return;
        try {
            writer.append(Long.toString(start)).append(',').append(Long.toString(end)).append(',')
                    .append(Long.toString(elapsedNanos)).append(',').append("visibleCell").append(',')
                    .append(redrawn ? "1" : "0").append(",0,0,0,0.0,0.0,0,0,")
                    .append(Integer.toString(step)).append(',').append(Float.toString(renderScale)).append(",0,x,")
                    .append(redrawn ? "redrawn" : "cached").append(",0,0,0,0,0,")
                    .append(redrawn ? "1" : "0").append(",0,0,0,0.0,0.0,0,0").append('\n');
        } catch (IOException | RuntimeException error) {
            close();
        }
    }

    static void recordInvalidationFrame(int invalidations, long elapsedNanos, int step, int gridCells, int renderScale) {        if (!INSTANCE.enabled) return;
        try {
            long now = System.nanoTime();
            INSTANCE.writeInvalidation(now - elapsedNanos, now, invalidations, elapsedNanos, step, gridCells, renderScale);
        } catch (RuntimeException ignored) { INSTANCE.close(); }
    }

    private synchronized void writeInvalidation(long start, long end, int invalidations, long elapsedNanos,
                                                int step, int gridCells, int renderScale) {
        if (!enabled) return;
        try {
            writer.append(Long.toString(start)).append(',').append(Long.toString(end)).append(',')
                    .append(Long.toString(elapsedNanos)).append(',').append("invalidateFrame").append(',')
                    .append(Integer.toString(invalidations)).append(",0,0,0,0.0,0.0,0,0,")
                    .append(Integer.toString(step)).append(",0.0,0,frame,")
                    .append(Integer.toString(renderScale)).append(',').append(Integer.toString(gridCells))
                    .append(",0,0,0,0,0,").append(Integer.toString(invalidations))
                    .append(",0,0,0,0.0,0.0,0,0").append('\n');
        } catch (IOException | RuntimeException error) {
            close();
        }
    }

    private synchronized void writeBatch(long start, long end, int renderedCount, int redrawsSinceLastCall,
                                         long elapsedNanos, int step, int gridCells, String mode) {
        if (!enabled) return;
        try {
            // Reuses the cell row shape so the existing analyzers keep working: `direction` carries the
            // batch size and `axis` the mode string.
            writer.append(Long.toString(start)).append(',').append(Long.toString(end)).append(',')
                    .append(Long.toString(elapsedNanos)).append(',').append("redrawBatch").append(',')
                    .append(Integer.toString(renderedCount)).append(',').append(Integer.toString(redrawsSinceLastCall)).append(',')
                    .append("0,0,0.0,0.0,0,0,")
                    .append(Integer.toString(step)).append(",0.0,0,").append(mode).append(',')
                    .append(Integer.toString(renderedCount)).append(',').append(Integer.toString(gridCells))
                    .append(",0,0,0,0,0,").append(Integer.toString(renderedCount))
                    .append(",0,0,0,0.0,0.0,0,0").append('\n');
        } catch (IOException | RuntimeException error) {
            close();
        }
    }

    private long cpuTime() {
        if (cpuClock == null) return -1;
        try { return cpuClock.getCurrentThreadCpuTime(); }
        catch (RuntimeException ignored) { return -1; }
    }

    private synchronized void write(CellSpan span, long end, long cpuNanos,
                                    String event, String axis, int direction, int completed) {
        if (!enabled) return;
        try {
            writer.append(Long.toString(span.start)).append(',').append(Long.toString(end)).append(',')
                    .append(Long.toString(cpuNanos)).append(',').append(event).append(',')
                    .append(Integer.toString(span.i)).append(',').append(Integer.toString(span.j)).append(',')
                    .append(Integer.toString(span.worldLeft)).append(',').append(Integer.toString(span.worldTop)).append(',')
                    .append(Float.toString(span.cameraX)).append(',').append(Float.toString(span.cameraY)).append(',')
                    .append(Integer.toString(span.originX)).append(',').append(Integer.toString(span.originY)).append(',')
                    .append(Integer.toString(span.step)).append(',').append(Float.toString(span.scale)).append(',')
                    .append(Integer.toString(span.tick)).append(',').append(axis).append(',')
                    .append(Integer.toString(direction)).append(',').append(Integer.toString(span.gridCells)).append(',')
                    .append(Integer.toString(span.screenLeft)).append(',').append(Integer.toString(span.screenTop)).append(',')
                    .append(Integer.toString(span.screenRight)).append(',').append(Integer.toString(span.screenBottom)).append(',')
                    .append(Integer.toString(span.visibleIntersection)).append(',').append(Integer.toString(completed)).append(',')
                    .append(Integer.toString(span.cameraXInt)).append(',').append(Integer.toString(span.cameraYInt)).append(',')
                    .append(Float.toString(span.visibleWorldWidth)).append(',').append(Float.toString(span.visibleWorldHeight)).append(',')
                    .append(Integer.toString(span.needsRedraw)).append(',').append(Integer.toString(span.smoothFade)).append('\n');
        } catch (IOException | RuntimeException error) {
            // Diagnostic failures must not replace an exception from cell rendering.
            close();
        }
    }

    private synchronized void close() {
        if (!enabled) return;
        enabled = false;
        try { writer.close(); } catch (IOException | RuntimeException ignored) { }
    }

    private static MapCacheTrace open() {
        BufferedWriter output = null;
        try {
            String destination = System.getenv("RWX_MAP_CACHE_TRACE");
            if (destination == null || destination.isBlank()) return new MapCacheTrace(null, null);
            Path path = Path.of(destination);
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            output = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            output.write("startNanos,endNanos,cpuNanos,event,cellI,cellJ,worldLeft,worldTop,cameraX,cameraY,originX,originY,step,scale,tick,axis,direction,gridCells,screenLeft,screenTop,screenRight,screenBottom,visibleIntersection,completed,cameraXInt,cameraYInt,visibleWorldWidth,visibleWorldHeight,needsRedraw,smoothFade\n");
            ThreadMXBean clock = null;
            try {
                ThreadMXBean candidate = ManagementFactory.getThreadMXBean();
                if (candidate.isCurrentThreadCpuTimeSupported() && candidate.isThreadCpuTimeEnabled()) clock = candidate;
            } catch (RuntimeException ignored) { }
            MapCacheTrace trace = new MapCacheTrace(output, clock);
            Runtime.getRuntime().addShutdownHook(new Thread(trace::close, "RWX_MAP_CACHE_TRACE-close"));
            return trace;
        } catch (IOException | RuntimeException error) {
            if (output != null) {
                try { output.close(); } catch (IOException | RuntimeException ignored) { }
            }
            System.err.println("RWX_MAP_CACHE_TRACE disabled: " + error);
            return new MapCacheTrace(null, null);
        }
    }

    static final class CellSpan {
        final long start, cpuStart;
        final int i, j, worldLeft, worldTop, originX, originY, step, tick, gridCells;
        final int screenLeft, screenTop, screenRight, screenBottom, visibleIntersection;
        final int cameraXInt, cameraYInt, needsRedraw, smoothFade;
        final float cameraX, cameraY, scale, visibleWorldWidth, visibleWorldHeight;

        CellSpan(LayerBufferManager manager, int i, int j, long start, long cpuStart) {
            GameEngine engine = GameEngine.getInstance();
            this.start = start;
            this.cpuStart = cpuStart;
            this.i = i;
            this.j = j;
            originX = manager.gridOriginWorldX;
            originY = manager.gridOriginWorldY;
            step = manager.cellWorldStepSize;
            scale = manager.renderScale;
            gridCells = manager.gridCellsPerAxis;
            worldLeft = i < 0 ? -1 : originX + i * step;
            worldTop = j < 0 ? -1 : originY + j * step;
            cameraX = engine.viewpointX;
            cameraY = engine.viewpointY;
            cameraXInt = engine.viewpointXInt;
            cameraYInt = engine.viewpointYInt;
            visibleWorldWidth = engine.visibleWorldWidth;
            visibleWorldHeight = engine.visibleWorldHeight;
            tick = engine.currentTick;
            LayerBufferCell cell = i >= 0 && j >= 0 ? manager.gridCells[i][j] : null;
            needsRedraw = cell == null ? -1 : cell.needsRedraw ? 1 : 0;
            smoothFade = cell == null ? -1 : cell.enableSmoothFade ? 1 : 0;
            screenLeft = cell == null ? -1 : cell.screenDstRect.a;
            screenTop = cell == null ? -1 : cell.screenDstRect.b;
            screenRight = cell == null ? -1 : cell.screenDstRect.c;
            screenBottom = cell == null ? -1 : cell.screenDstRect.d;
            float screenScale = engine.zoom / scale;
            if (Math.abs(screenScale - 1.0f) < 1.0E-4f) screenScale = 1.0f;
            visibleIntersection = cell == null ? -1 : screenRight > screenLeft && screenBottom > screenTop
                    && screenRight > 0 && screenBottom > 0
                    && screenLeft < engine.currentScreenWidthPixels / screenScale + 2.0f
                    && screenTop < engine.currentScreenHeightPixels / screenScale + 2.0f ? 1 : 0;
        }
    }
}
