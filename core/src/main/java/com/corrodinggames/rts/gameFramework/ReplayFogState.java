package com.corrodinggames.rts.gameFramework;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Replay-only line of sight and exploration history. Never touches player simulation arrays. */
public final class ReplayFogState {
    private int width, height;
    private boolean revealed;
    private final Map<Integer, byte[][]> teams = new HashMap<>();
    private byte[] visionStamp = new byte[0];
    private byte[][][] recipientGrids = new byte[0][][];

    public record Snapshot(int width, int height, boolean revealed, Map<Integer, byte[][]> teams) {}

    public void reset() { width = height = 0; teams.clear(); Arrays.fill(recipientGrids, null); }

    public void configure(int width, int height, boolean revealed) {
        if (this.width == width && this.height == height && this.revealed == revealed) return;
        reset();
        this.width = width;
        this.height = height;
        this.revealed = revealed;
    }

    public byte[][] get(int teamId) { return teams.get(teamId); }

    public byte[][] ensureTeam(int teamId) {
        if (width <= 0 || height <= 0) return null;
        return teams.computeIfAbsent(teamId, ignored -> {
            byte[][] data = new byte[width][height];
            for (byte[] column : data) Arrays.fill(column, revealed ? (byte) 5 : (byte) 10);
            return data;
        });
    }

    public void beginFrame(boolean refog) {
        if (!refog) return;
        for (byte[][] data : teams.values()) {
            for (byte[] column : data) {
                for (int y = 0; y < column.length; y++) if (column[y] < 5) column[y] = 5;
            }
        }
    }

    /** Same tile-distance falloff used by TileMap's unit vision circles. */
    public void reveal(int teamId, float tileX, float tileY, int radius) {
        byte[][] data = ensureTeam(teamId);
        if (data == null || radius <= 0) return;
        float outer = (float) radius * radius;
        float inner = (float) (radius - 3) * (radius - 3);
        float falloff = 10 / (outer - inner);
        // Small custom sight ranges must still reveal their center.
        if (radius < 3) { inner = 0; falloff = 10 / outer; }
        int extent = Math.max(1, radius - 1);
        int cx = (int) tileX, cy = (int) tileY;
        for (int x = Math.max(0, cx - extent); x <= Math.min(width - 1, cx + extent); x++) {
            for (int y = Math.max(0, cy - extent); y <= Math.min(height - 1, cy + extent); y++) {
                float dx = tileX - x, dy = tileY - y;
                float distance = dx * dx + dy * dy;
                if (distance > outer) continue;
                byte value = distance <= inner ? 0 : (byte) Math.min(10, (distance - inner) * falloff);
                if (value < data[x][y]) data[x][y] = value;
            }
        }
    }

    /** Compute one unit's falloff once, then merge it into each allied exploration grid. */
    public void revealAll(int[] teamIds, float tileX, float tileY, int radius) {
        if (teamIds.length == 1) { reveal(teamIds[0], tileX, tileY, radius); return; }
        if (teamIds.length == 0) return;
        if (recipientGrids.length < teamIds.length) recipientGrids = new byte[teamIds.length][][];
        for (int i = 0; i < teamIds.length; i++) recipientGrids[i] = ensureTeam(teamIds[i]);
        if (width <= 0 || height <= 0 || radius <= 0) return;
        int extent = Math.max(1, radius - 1), cx = (int) tileX, cy = (int) tileY;
        int left = Math.max(0, cx - extent), right = Math.min(width - 1, cx + extent);
        int top = Math.max(0, cy - extent), bottom = Math.min(height - 1, cy + extent);
        if (left > right || top > bottom) return;
        int rows = bottom - top + 1;
        int cells = (right - left + 1) * rows;
        if (visionStamp.length < cells) visionStamp = new byte[cells];
        float outer = (float) radius * radius;
        float inner = (float) (radius - 3) * (radius - 3);
        float falloff = 10 / (outer - inner);
        if (radius < 3) { inner = 0; falloff = 10 / outer; }
        int offset = 0;
        for (int x = left; x <= right; x++) for (int y = top; y <= bottom; y++) {
            float dx = tileX - x, dy = tileY - y;
            float distance = dx * dx + dy * dy;
            visionStamp[offset++] = distance > outer ? -1
                    : distance <= inner ? 0 : (byte) Math.min(10, (distance - inner) * falloff);
        }
        for (int i = 0; i < teamIds.length; i++) {
            byte[][] data = recipientGrids[i];
            offset = 0;
            for (int x = left; x <= right; x++) {
                byte[] column = data[x];
                for (int y = top; y <= bottom; y++) {
                    byte value = visionStamp[offset++];
                    if (value >= 0 && value < column[y]) column[y] = value;
                }
            }
        }
    }

    public static byte[][] copy(byte[][] source) {
        if (source == null) return null;
        byte[][] copy = new byte[source.length][];
        for (int x = 0; x < source.length; x++) copy[x] = source[x].clone();
        return copy;
    }

    public Snapshot snapshot() {
        Map<Integer, byte[][]> data = new HashMap<>();
        teams.forEach((id, grid) -> data.put(id, copy(grid)));
        return new Snapshot(width, height, revealed, data);
    }

    public void restore(Snapshot snapshot) {
        configure(snapshot.width(), snapshot.height(), snapshot.revealed());
        teams.clear();
        Arrays.fill(recipientGrids, null);
        snapshot.teams().forEach((id, grid) -> teams.put(id, copy(grid)));
    }
}
