package com.corrodinggames.rts.gameFramework.ui;

import java.util.function.IntConsumer;

/** Shared geometry and input for replay transport, independent of timeline metadata. */
public final class ReplayPlaybackControls {
    public static final int SLOWER = -1;
    public static final int PLAY_PAUSE = 0;
    public static final int FASTER = 1;

    public record Box(float left, float top, float width, float height) {
        public boolean contains(float x, float y) {
            return x >= left && x <= left + width && y >= top && y <= top + height;
        }
    }

    public record Layout(Box slower, Box playPause, Box faster, Box speed, Box vision) {
        public float unit() { return playPause.height() / 30; }
        public float bottom() { return playPause.top() + playPause.height(); }
    }

    private boolean wasDown;
    private boolean captured;
    private int pressedAction = Integer.MIN_VALUE;

    public static Layout layout(float viewportWidth, float scale, float top) {
        float unit = Math.max(.5f, Math.min(scale, (viewportWidth - 16) / 308));
        float left = (viewportWidth - 308 * unit) / 2;
        float height = 30 * unit;
        return new Layout(new Box(left, top, 52 * unit, height),
                new Box(left + 58 * unit, top, 68 * unit, height),
                new Box(left + 132 * unit, top, 52 * unit, height),
                new Box(left + 190 * unit, top, 48 * unit, height),
                new Box(left + 244 * unit, top, 64 * unit, height));
    }

    public boolean contains(Layout layout, float x, float y) {
        return captured || actionAt(layout, x, y) != Integer.MIN_VALUE;
    }

    public void reset() {
        wasDown = captured = false;
        pressedAction = Integer.MIN_VALUE;
    }

    private int actionAt(Layout layout, float x, float y) {
        if (layout.slower().contains(x, y)) return SLOWER;
        if (layout.playPause().contains(x, y)) return PLAY_PAUSE;
        if (layout.faster().contains(x, y)) return FASTER;
        return Integer.MIN_VALUE;
    }

    public boolean handle(Layout layout, boolean down, float x, float y, boolean enabled, IntConsumer action) {
        boolean consumed = captured;
        if (down && !wasDown) {
            pressedAction = actionAt(layout, x, y);
            if (pressedAction != Integer.MIN_VALUE) captured = consumed = true;
        }
        if (captured && !down) {
            if (enabled && pressedAction == actionAt(layout, x, y)) action.accept(pressedAction);
            captured = false;
            pressedAction = Integer.MIN_VALUE;
        }
        wasDown = down;
        return consumed;
    }
}
