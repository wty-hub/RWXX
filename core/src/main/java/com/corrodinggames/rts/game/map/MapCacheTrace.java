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
