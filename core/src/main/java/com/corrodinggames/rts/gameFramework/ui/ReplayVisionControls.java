package com.corrodinggames.rts.gameFramework.ui;

import java.util.function.IntConsumer;

/** Shared input/geometry for the replay presentation menu; independent of timeline indexing. */
public final class ReplayVisionControls {
    public static final int TOGGLE_FOG = -1;
    private boolean open, wasDown, captured;
    private int pressedAction = Integer.MIN_VALUE;
    private int firstRow;

    public record Box(float left, float top, float width, float height) {
        public boolean contains(float x, float y) {
            return x >= left && x <= left + width && y >= top && y <= top + height;
        }
    }

    public record Layout(Box button, Box panel, float rowHeight, int visibleRows) {
        public Box toggle() { return new Box(panel.left(), panel.top(), panel.width(), rowHeight); }
        public Box row(int visibleIndex) {
            return new Box(panel.left(), panel.top() + (2 + visibleIndex) * rowHeight, panel.width(), rowHeight);
        }
        public Box previous() {
            return new Box(panel.left(), panel.top() + panel.height() - rowHeight, panel.width() / 2, rowHeight);
        }
        public Box next() {
            return new Box(panel.left() + panel.width() / 2, previous().top(), panel.width() / 2, rowHeight);
        }
    }

    public static Layout layout(float width, float height, float scale, float buttonTop, float iconWidth, int teams) {
        float unit = Math.max(.5f, Math.min(scale, Math.min(width / 320, height / 240)));
        float row = 30 * unit;
        float buttonWidth = 64 * unit;
        Box button = new Box(Math.max(4, Math.min(width - buttonWidth - 4, width / 2 + iconWidth + 12 * unit)),
                Math.max(4, Math.min(height - 5 * row - 12, buttonTop)), buttonWidth, row);
        float panelWidth = Math.max(1, Math.min(width - 8, 280 * unit));
        float panelTop = button.top() + row + 4;
        int visible = Math.max(1, Math.min(teams, (int) ((height - panelTop - 4) / row) - 3));
        float panelHeight = (visible + 3) * row;
        Box panel = new Box(Math.max(4, Math.min(width - panelWidth - 4, button.left())),
                panelTop, panelWidth, panelHeight);
        return new Layout(button, panel, row, visible);
    }

    public boolean isOpen() { return open; }
    public int getFirstRow() { return firstRow; }
    public boolean contains(Layout layout, float x, float y) {
        return captured || layout.button().contains(x, y) || open;
    }
    public boolean close() {
        boolean wasOpen = open;
        open = false;
        return wasOpen;
    }
    public void reset() { open = wasDown = captured = false; firstRow = 0; }

    private int actionAt(Layout layout, float x, float y, int count) {
        if (layout.button().contains(x, y)) return -2;
        if (!open || !layout.panel().contains(x, y)) return -5;
        if (layout.toggle().contains(x, y)) return TOGGLE_FOG;
        if (layout.previous().contains(x, y)) return -3;
        if (layout.next().contains(x, y)) return -4;
        for (int row = 0; row < layout.visibleRows() && firstRow + row < count; row++) {
            if (layout.row(row).contains(x, y)) return firstRow + row;
        }
        return Integer.MIN_VALUE;
    }

    public boolean handle(Layout layout, boolean down, float x, float y, int wheel, int count,
                          boolean enabled, IntConsumer action) {
        firstRow = Math.max(0, Math.min(firstRow, Math.max(0, count - layout.visibleRows())));
        boolean consumed = captured;
        if (open && wheel != 0) {
            firstRow = Math.max(0, Math.min(count - layout.visibleRows(), firstRow + (wheel < 0 ? 1 : -1)));
            consumed = true;
        }
        if (down && !wasDown && contains(layout, x, y)) {
            captured = consumed = true;
            pressedAction = actionAt(layout, x, y, count);
        }
        if (captured && !down) {
            if (pressedAction == actionAt(layout, x, y, count)) {
                switch (pressedAction) {
                    case -2 -> open = !open;
                    case -3 -> firstRow = Math.max(0, firstRow - layout.visibleRows());
                    case -4 -> firstRow = Math.max(0, Math.min(count - layout.visibleRows(), firstRow + layout.visibleRows()));
                    case -5 -> open = false;
                    default -> { if (enabled && pressedAction != Integer.MIN_VALUE) action.accept(pressedAction); }
                }
            }
            captured = false;
        }
        wasDown = down;
        return consumed || open;
    }
}
