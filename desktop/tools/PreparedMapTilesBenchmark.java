import com.corrodinggames.rts.gameFramework.graphics.GamePaint;
import com.corrodinggames.rts.gameFramework.graphics.PreparedMapTextureDraw;
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode;
import com.corrodinggames.rts.gameFramework.graphics.Texture;
import io.github.rwx.geometry.Rect;
import io.github.rwx.geometry.RectF;
import io.github.rwx.render.canvas.KoolCanvasCpuTextureStore;
import io.github.rwx.render.canvas.KoolCanvasFrame;
import io.github.rwx.render.canvas.KoolGraphicsEngine;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Small, standalone production-path RECORDING benchmark. Creates no window, Kool context or GPU work.
 * Compile against the same frozen fat jar used for both control and candidate. Example (JDK 25):
 *
 * javac -cp "$BENCH_JAR" -d "$BENCH_CLASSES" desktop/tools/PreparedMapTilesBenchmark.java
 * env RWX_PREPARED_MAP_TILES=0 RWX_MAP_TEXTURE_BATCHES=0 java \
 *   -Djava.awt.headless=true -Xms128m -Xmx128m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 \
 *   -cp "$BENCH_CLASSES:$BENCH_JAR" PreparedMapTilesBenchmark \
 *   --warmup-layers=64 --measure-layers=128 --draws-per-layer=1024
 *
 * Run separate processes sequentially in 0/1/1/0 order; then repeat with texture batches fixed at 1.
 * The unset prepared-map environment follows the product default ON; controlled arms must set 0/1.
 * Use a low OS scheduling priority and a small heap; never launch windows or change application focus.
 * Do not overlap builds, GPU or end-to-end measurements. Concurrent user work makes wall-clock timing
 * uncontrolled. ProcessorCount changes JVM ergonomics, not OS affinity or an OS CPU quota.
 * All setup, input rectangles and texture pixels are shared across layers. Timed work includes each
 * beginFrame, optional prepare/close scope, production draw recording, snapshot and snapshot hash.
 * These numbers quantify this local CPU/allocation path, never display FPS or input latency.
 * snapshotHash is a dead-code-elimination sink, not a cross-JVM correctness check: enum hash codes
 * have process-local identity. The same-process differential tests establish recording equivalence.
 */
public final class PreparedMapTilesBenchmark {
    private static volatile int blackhole;

    public static void main(String[] args) {
        // Also set in the invocation so this holds before any classes could initialize AWT.
        System.setProperty("java.awt.headless", "true");
        int warmupLayers = 64;
        int measureLayers = 128;
        int drawsPerLayer = 1024;
        for (String arg : args) {
            if (arg.startsWith("--warmup-layers=")) warmupLayers = integer(arg);
            else if (arg.startsWith("--measure-layers=")) measureLayers = integer(arg);
            else if (arg.startsWith("--draws-per-layer=")) drawsPerLayer = integer(arg);
            else throw new IllegalArgumentException("Unknown argument: " + arg);
        }
        if (warmupLayers < 1 || measureLayers < 1 || drawsPerLayer < 1 ||
                warmupLayers > 100_000 || measureLayers > 100_000 || drawsPerLayer > 65_536) {
            throw new IllegalArgumentException("Layer counts must be 1..100000; draws must be 1..65536");
        }
        boolean expectedPrepared = !"0".equals(System.getenv("RWX_PREPARED_MAP_TILES"));
        boolean batches = "1".equals(System.getenv("RWX_MAP_TEXTURE_BATCHES"));
        com.sun.management.ThreadMXBean bean =
                ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        if (bean == null || !bean.isThreadAllocatedMemorySupported() || !bean.isCurrentThreadCpuTimeSupported()) {
            throw new IllegalStateException("This benchmark needs thread allocation and CPU time counters");
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        if (!bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true);
        long thread = Thread.currentThread().threadId();
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();

        try (KoolCanvasCpuTextureStore store = new KoolCanvasCpuTextureStore()) {
            KoolGraphicsEngine root = new KoolGraphicsEngine(store, path -> null, false);
            Texture target = root.b(128, 128, true);
            KoolGraphicsEngine cell = (KoolGraphicsEngine) root.b(target, RenderTargetMode.GPU_TARGET);
            Texture sourceTexture = root.a(16, 16, false);
            sourceTexture.j = new int[16 * 16];
            Arrays.fill(sourceTexture.j, 0xff336699);
            sourceTexture.p();
            GamePaint paint = new GamePaint();
            paint.a(true);
            Rect source = new Rect(1, 2, 14, 15);
            RectF destination = new RectF();

            int hash = 1;
            for (int layer = 0; layer < warmupLayers; layer++) {
                hash = 31 * hash + recordLayer(cell, sourceTexture, paint, source, destination,
                        layer, drawsPerLayer, expectedPrepared);
            }
            blackhole = hash;
            long gcBefore = collections(collectors);
            long allocatedBefore = bean.getThreadAllocatedBytes(thread);
            long cpuBefore = bean.getCurrentThreadCpuTime();
            long wallBefore = System.nanoTime();
            for (int layer = 0; layer < measureLayers; layer++) {
                hash = 31 * hash + recordLayer(cell, sourceTexture, paint, source, destination,
                        layer + warmupLayers, drawsPerLayer, expectedPrepared);
            }
            long wallNanos = System.nanoTime() - wallBefore;
            long cpuNanos = bean.getCurrentThreadCpuTime() - cpuBefore;
            long allocatedBytes = bean.getThreadAllocatedBytes(thread) - allocatedBefore;
            long gcCollections = collections(collectors) - gcBefore;
            blackhole = hash;
            long draws = (long) measureLayers * drawsPerLayer;
            // Production startup diagnostics can precede this line. Consume only this prefixed JSON.
            System.out.printf(Locale.ROOT,
                    "RWXPreparedMapTilesBenchmark {\"prepared\":%s,\"textureBatches\":%s," +
                    "\"warmupLayers\":%d,\"measureLayers\":%d,\"drawsPerLayer\":%d," +
                    "\"measuredDraws\":%d,\"allocatedBytes\":%d,\"bytesPerDraw\":%.6f," +
                    "\"cpuNanos\":%d,\"cpuNanosPerDraw\":%.6f," +
                    "\"wallNanos\":%d,\"wallNanosPerDraw\":%.6f," +
                    "\"gcCollections\":%d,\"snapshotHash\":%d," +
                    "\"scopeAndSnapshotIncluded\":true,\"recordingOnly\":true}%n",
                    expectedPrepared, batches, warmupLayers, measureLayers, drawsPerLayer,
                    draws, allocatedBytes, (double) allocatedBytes / draws,
                    cpuNanos, (double) cpuNanos / draws, wallNanos, (double) wallNanos / draws,
                    gcCollections, blackhole);
        }
    }

    private static int recordLayer(KoolGraphicsEngine cell, Texture texture, GamePaint paint,
                                   Rect source, RectF destination, int layer, int draws,
                                   boolean expectedPrepared) {
        cell.beginFrame(128, 128);
        cell.a(true); // Match the opaque ground-layer direct-blit path; checked anew on each draw.
        PreparedMapTextureDraw prepared = cell.prepareMapTextureDraw(paint);
        if ((prepared != null) != expectedPrepared) {
            throw new IllegalStateException("Public prepare API did not match RWX_PREPARED_MAP_TILES");
        }
        try {
            for (int draw = 0; draw < draws; draw++) {
                // Deterministic fractional coordinates, same values and arithmetic in both arms.
                float left = (draw & 31) * 3.5f - (layer & 7) * 0.125f;
                float top = ((draw >>> 5) & 31) * 3.5f - 0.375f;
                destination.a(left, top, left + 4.125f, top + 4.25f);
                if (prepared == null || !prepared.draw(texture, source, destination)) {
                    if (expectedPrepared) throw new IllegalStateException("Prepared draw unexpectedly declined");
                    cell.a(texture, source, destination, paint);
                }
            }
        } finally {
            if (prepared != null) prepared.close();
        }
        // No p()/GPU commit: this intentionally measures the producer's recording and snapshot only.
        KoolCanvasFrame snapshot = cell.snapshot();
        return snapshot.hashCode();
    }

    private static int integer(String arg) {
        return Integer.parseInt(arg.substring(arg.indexOf('=') + 1));
    }

    private static long collections(List<GarbageCollectorMXBean> collectors) {
        long total = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            long count = collector.getCollectionCount();
            if (count >= 0) total += count;
        }
        return total;
    }
}
