package com.corrodinggames.rts.game.map;

/** One previous grid, described in its original world coordinates, never current-grid coordinates. */
final class MapZoomCacheGeneration {
    static final float MAX_SCALE_DEVIATION = 1.15f;
    static final long STABLE_NANOS = 50_000_000L;
    static final long VISIBLE_DEADLINE_NANOS = 250_000_000L;
    final LayerBufferCell[][] cells;
    final int originX, originY, step, pixels;
    final float scale;
    final long startedAtNanos;
    final boolean[][] valid;
    boolean active = true;

    MapZoomCacheGeneration(LayerBufferCell[][] cells, int originX, int originY, int step,
                           int pixels, float scale, long now) {
        this.cells = cells;
        this.originX = originX;
        this.originY = originY;
        this.step = step;
        this.pixels = pixels;
        this.scale = scale;
        this.startedAtNanos = now;
        valid = new boolean[cells.length][];
        for (int x = 0; x < cells.length; x++) {
            valid[x] = new boolean[cells[x].length];
            for (int y = 0; y < cells[x].length; y++) {
                LayerBufferCell cell = cells[x][y];
                valid[x][y] = cell != null && !cell.needsRedraw && !cell.enableSmoothFade;
            }
        }
    }

    boolean compatibleScale(float desiredScale) {
        return Float.isFinite(desiredScale) && desiredScale > 0 && scale > 0
                && Math.max(desiredScale / scale, scale / desiredScale) <= MAX_SCALE_DEVIATION;
    }

    boolean covers(double left, double top, double right, double bottom, float desiredScale) {
        if (!active || !compatibleScale(desiredScale) || step <= 0 || cells.length == 0
                || !(right > left && bottom > top)) return false;
        double inset = 1.0 / scale;
        double extent = (pixels - 2.0) / scale;
        if (left < originX + inset || top < originY + inset
                || right > originX + (cells.length - 1L) * step + extent) return false;
        // Partition overlapping interiors at the next cell's inset. Any invalid partition is a hole.
        int firstX = Math.max(0, (int) Math.floor((left - originX - inset) / step));
        int lastX = Math.min(cells.length - 1, (int) Math.floor((Math.nextDown(right) - originX - inset) / step));
        for (int x = firstX; x <= lastX; x++) {
            if (cells[x].length == 0 || bottom > originY + (cells[x].length - 1L) * step + extent) return false;
            int firstY = Math.max(0, (int) Math.floor((top - originY - inset) / step));
            int lastY = Math.min(cells[x].length - 1, (int) Math.floor((Math.nextDown(bottom) - originY - inset) / step));
            for (int y = firstY; y <= lastY; y++) if (!valid[x][y]) return false;
        }
        return true;
    }

    void invalidate(double left, double top, double right, double bottom) {
        double extent = pixels / (double) scale;
        for (int x = 0; x < cells.length; x++) for (int y = 0; y < cells[x].length; y++) {
            double cellLeft = originX + (long) x * step, cellTop = originY + (long) y * step;
            if (left < cellLeft + extent && right > cellLeft && top < cellTop + extent && bottom > cellTop)
                valid[x][y] = false;
        }
    }

    static final class Stability {
        private int targetBits;
        private boolean sampled;
        private long changedAt;
        void reset() { sampled = false; }
        boolean observe(long now, float targetScale) {
            int bits = Float.floatToIntBits(targetScale);
            if (!sampled || bits != targetBits || now < changedAt) {
                sampled = true; targetBits = bits; changedAt = now;
                return false;
            }
            return now - changedAt >= STABLE_NANOS;
        }
    }
}
