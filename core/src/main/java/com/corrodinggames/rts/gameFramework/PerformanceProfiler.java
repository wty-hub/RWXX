package com.corrodinggames.rts.gameFramework;

import com.corrodinggames.rts.game.units.custom.logicBooleans.VariableScope;
import io.github.rwx.geometry.Rect;
import io.github.rwx.render.canvas.KoolPaint;
import io.github.rwx.render.canvas.CanvasStageTrace;

/* JADX INFO: renamed from: com.corrodinggames.rts.gameFramework.br */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/gameFramework/br.class */
public final class PerformanceProfiler {

    /* JADX INFO: renamed from: a */
    GameEngine gameEngine;

    /* JADX INFO: renamed from: c */
    public static int dataCapacity = 40;
    public int b = 0;
    public int d = 0;
    ProfilerData e = new ProfilerData(this);
    KoolPaint f = new KoolPaint();
    Rect g = new Rect();
    int h = -1;

    public PerformanceProfiler(GameEngine gameEngine) {
        this.gameEngine = gameEngine;
    }

    public static final long a() {
        return System.nanoTime();
    }

    public static final float a(long j) {
        return (System.nanoTime() - j) / 1000000.0f;
    }

    public static final double a(long j, long j2) {
        return (j2 - j) / 1000000.0d;
    }

    public static final void a(String str, long j) {
        GameEngine.log(str + VariableScope.nullOrMissingString + a(a(j)));
    }

    /** Enabled by the desktop frame-time log; only reads the clock, never touches game state. */
    public static volatile boolean frameTimingEnabled = false;

    private long updateStartNanos;
    private long drawStartNanos;
    private static final CanvasStageTrace sectionTrace = CanvasStageTrace.Companion.fromEnvironment("RWX_ENGINE_SECTION_TRACE");
    private final long[] sectionStarts = sectionTrace.getEnabled() ? new long[ProfilerSection.values().length] : null;

    /** Nanoseconds spent in the update and draw sections since the last {@link #takeFrameTimings()}. */
    public long updateNanos;
    public long drawNanos;

    public final void a(ProfilerSection profilerSection) {
        if (!frameTimingEnabled) {
            return;
        }
        if (sectionStarts != null) {
            sectionStarts[profilerSection.ordinal()] = System.nanoTime();
        }
        if (profilerSection == ProfilerSection.update) {
            this.updateStartNanos = System.nanoTime();
        } else if (profilerSection == ProfilerSection.draw) {
            this.drawStartNanos = System.nanoTime();
        }
    }

    public final void b(ProfilerSection profilerSection) {
        if (!frameTimingEnabled) {
            return;
        }
        if (sectionStarts != null) {
            int index = profilerSection.ordinal();
            long start = sectionStarts[index];
            sectionStarts[index] = 0;
            long end = System.nanoTime();
            // Keep the trace small and leave ordinary gameplay entirely uninstrumented.
            if (start != 0 && end - start >= 2_000_000L) {
                sectionTrace.recordCompleted(profilerSection.name(), start, end, -1L, -1L, -1L);
            }
        }
        if (profilerSection == ProfilerSection.update && this.updateStartNanos != 0) {
            this.updateNanos += System.nanoTime() - this.updateStartNanos;
            this.updateStartNanos = 0;
        } else if (profilerSection == ProfilerSection.draw && this.drawStartNanos != 0) {
            this.drawNanos += System.nanoTime() - this.drawStartNanos;
            this.drawStartNanos = 0;
        }
    }

    public final void takeFrameTimings() {
        this.updateNanos = 0;
        this.drawNanos = 0;
    }

    public static final String a(double d) {
        return VariableScope.nullOrMissingString + Utility.padString(d, 3) + "ms";
    }

    public static final String b(double d) {
        return VariableScope.nullOrMissingString + (d / 1000000.0d) + "ms";
    }

    public final void b() {
    }

    public final void c() {
    }

    public final void a(boolean z, boolean z2) {
    }
}
