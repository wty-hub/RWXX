import com.corrodinggames.rts.gameFramework.graphics.GamePaint;
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode;
import com.corrodinggames.rts.gameFramework.graphics.Texture;
import io.github.rwx.geometry.Rect;
import io.github.rwx.geometry.RectF;
import io.github.rwx.render.canvas.FrameEnvelope;
import io.github.rwx.render.canvas.FrozenCanvasGpuResources;
import io.github.rwx.render.canvas.FrozenCanvasResource;
import io.github.rwx.render.canvas.KoolCanvasCpuTextureStore;
import io.github.rwx.render.canvas.KoolCanvasBlendMode;
import io.github.rwx.render.canvas.KoolCanvasCommand;
import io.github.rwx.render.canvas.KoolCanvasFrame;
import io.github.rwx.render.canvas.KoolCanvasTextureRef;
import io.github.rwx.render.canvas.KoolGraphicsEngine;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Windowless production freeze/lease benchmark. Does not create a GPU context or run simulation.
 * Compile once against the desktop fat jar, then run sequential A/B/B/A JVMs with a frozen core jar
 * FIRST on the classpath and the SAME fat jar second. Use nice, -Djava.awt.headless=true,
 * -Xms192m -Xmx192m -XX:+UseSerialGC -XX:ActiveProcessorCount=1. Clear inherited RWX_* diagnostics
 * and pin RWX_CANVAS_FREEZE_SCRATCH_POOL=1, RWX_FROZEN_CONTENT_REUSE=0 in BOTH arms.
 *
 * By default setup records a root and GPU map-cell commands once. Timed work freezes the complete
 * resource graph and closes every lease. --update-every=N additionally re-records and registers one
 * map cell every N iterations, round-robin; --source-update-every=N commits and registers a changed
 * sprite revision. Both default to 0. Updates use the continuous warmup/measurement iteration index
 * (iteration 1 is the first warmup iteration), before that iteration's freeze. Dynamic timings include
 * all selected recording/registration work; static timings keep their existing freeze/lease scope.
 * Source registration uses a scratch root recording; the original sampled root recording is reused.
 * No upload, rendering, presentation, simulation or input is measured in either mode.
 * Run both a small main frame (--cells=0) and nested map cells (e.g. --cells=32 --draws=128).
 * Concurrent user work is allowed: allocation is the primary metric; CPU/wall are screening data,
 * never an end-to-end FPS or tail-latency claim. The sink is not a cross-process correctness hash.
 */
public final class CanvasFreezeBenchmark {
    private static volatile long sink;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        int cells = 32, draws = 128, warmup = 1000, frames = 2000;
        int updateEvery = 0, sourceUpdateEvery = 0;
        for (String arg : args) {
            int value = Integer.parseInt(arg.substring(arg.indexOf('=') + 1));
            if (arg.startsWith("--cells=")) cells = value;
            else if (arg.startsWith("--draws=")) draws = value;
            else if (arg.startsWith("--warmup=")) warmup = value;
            else if (arg.startsWith("--frames=")) frames = value;
            else if (arg.startsWith("--update-every=")) updateEvery = value;
            else if (arg.startsWith("--source-update-every=")) sourceUpdateEvery = value;
            else throw new IllegalArgumentException("Unknown argument: " + arg);
        }
        if (cells < 0 || cells > 256 || draws < 1 || draws > 4096 ||
                warmup < 1 || warmup > 100_000 || frames < 1 || frames > 100_000 ||
                updateEvery < 0 || updateEvery > 100_000 || sourceUpdateEvery < 0 || sourceUpdateEvery > 100_000)
            throw new IllegalArgumentException("Invalid benchmark size");
        if (updateEvery > 0 && cells == 0)
            throw new IllegalArgumentException("--update-every requires at least one nested cell");
        requireMeasuredUpdates("--update-every", updateEvery, warmup, frames);
        requireMeasuredUpdates("--source-update-every", sourceUpdateEvery, warmup, frames);
        boolean dynamic = updateEvery > 0 || sourceUpdateEvery > 0;
        var bean = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        if (bean == null || !bean.isThreadAllocatedMemorySupported() || !bean.isCurrentThreadCpuTimeSupported())
            throw new IllegalStateException("Thread allocation and CPU counters are required");
        bean.setThreadAllocatedMemoryEnabled(true);
        bean.setThreadCpuTimeEnabled(true);
        // Recording checks for an installed render owner. Supply only that availability guard;
        // this benchmark must never install, upload or render GPU resources. Without this guard
        // cell.p() silently rasterizes to Pixels and would not benchmark recursive freezing.
        FrozenCanvasGpuResources.INSTANCE.setGpuTargetInstaller$io_github_rwx_core((id, target) -> {
            throw new AssertionError("The windowless benchmark must not install GPU resources");
        });
        try (var store = new KoolCanvasCpuTextureStore()) {
            var root = new KoolGraphicsEngine(store, path -> null, false);
            Texture sprite = root.a(16, 16, false);
            sprite.j = new int[256];
            Arrays.fill(sprite.j, 0xff336699);
            sprite.p();
            var paint = new GamePaint();
            paint.a(true);
            var source = new Rect(0, 0, 16, 16);
            var destination = new RectF();
            var cellEngines = new KoolGraphicsEngine[cells];
            root.beginFrame(1280, 720);
            for (int i = 0; i < cells; i++) {
                Texture target = root.b(128, 128, true);
                var cell = (KoolGraphicsEngine) root.b(target, RenderTargetMode.GPU_TARGET);
                cellEngines[i] = cell;
                recordCell(cell, sprite, paint, source, destination, draws, 0);
                destination.a((i & 7) * 128f, (i >>> 3) * 128f,
                        (i & 7) * 128f + 128f, (i >>> 3) * 128f + 128f);
                root.a(target, new Rect(0, 0, 128, 128), destination, paint);
            }
            for (int d = 0; d < draws; d++) {
                destination.a(d, d, d + 16f, d + 16f);
                root.a(sprite, source, destination, paint);
            }
            KoolCanvasFrame recorded = root.snapshot();
            var updates = new Updates(root, sprite, paint, source, destination, cellEngines, draws);
            // Check the graph itself, rather than inferring GPU recording from a mode flag or
            // resource count (the CPU fallback has the same number of resources).
            try (FrameEnvelope check = store.freezeFrame(recorded, 0, 1, 0, 1, null)) {
                long targets = check.getResourceLease().resources$io_github_rwx_core().values().stream()
                        .filter(resource -> resource instanceof FrozenCanvasResource.GpuTarget).count();
                long nestedCommands = check.getResourceLease().resources$io_github_rwx_core().values().stream()
                        .filter(resource -> resource instanceof FrozenCanvasResource.GpuTarget)
                        .mapToLong(resource -> ((FrozenCanvasResource.GpuTarget) resource).getFrame().getCommands().size())
                        .sum();
                if (targets != cells || nestedCommands != (long) cells * draws)
                    throw new IllegalStateException("Expected recorded GPU targets and unmerged nested draws: " +
                            targets + " targets, " + nestedCommands + " commands");
            }
            // Preflight exercises both invalidation paths outside timing, checks actual command/pixel
            // changes and versioned root references, and retains an old lease across those changes.
            if (dynamic) verifyUpdates(store, recorded, updates, updateEvery > 0, sourceUpdateEvery > 0);
            long checksum = 0;
            for (int i = 0; i < warmup; i++) {
                if (dynamic) updates.beforeFreeze(i + 1, updateEvery, sourceUpdateEvery);
                checksum += freeze(store, recorded, i, cells + 1);
            }
            sink = checksum;
            long warmupCellUpdates = updates.cellUpdates;
            long warmupSourceUpdates = updates.sourceUpdates;
            int warmupStoreRevision = store.getTextureRevision();
            // The one additional retained lease is dynamic-only. Capture and verify its contents
            // before/after the timed window; content inspection never runs inside the timed loop.
            FrameEnvelope retained = dynamic ? store.freezeFrame(recorded, -1, 1, 0, 1, null) : null;
            long wallNanos, cpuNanos, allocatedBytes;
            long thread = Thread.currentThread().threadId();
            try {
                GraphState retainedState = dynamic ? inspect(retained, updates) : null;
                long allocatedStart = bean.getThreadAllocatedBytes(thread);
                long cpuStart = bean.getCurrentThreadCpuTime();
                long wallStart = System.nanoTime();
                for (int i = 0; i < frames; i++) {
                    if (dynamic) updates.beforeFreeze(warmup + i + 1, updateEvery, sourceUpdateEvery);
                    checksum += freeze(store, recorded, warmup + i, cells + 1);
                }
                wallNanos = System.nanoTime() - wallStart;
                cpuNanos = bean.getCurrentThreadCpuTime() - cpuStart;
                allocatedBytes = bean.getThreadAllocatedBytes(thread) - allocatedStart;
                if (dynamic) {
                    try (FrameEnvelope latest = store.freezeFrame(recorded, -2, 1, 0, 1, null)) {
                        inspect(latest, updates);
                    }
                    retainedState.verifyUnchanged(retained);
                }
            } finally {
                if (retained != null) retained.close();
            }
            long measuredCellUpdates = updates.cellUpdates - warmupCellUpdates;
            long measuredSourceUpdates = updates.sourceUpdates - warmupSourceUpdates;
            if (measuredCellUpdates != measuredUpdates(updateEvery, warmup, frames) ||
                    measuredSourceUpdates != measuredUpdates(sourceUpdateEvery, warmup, frames))
                throw new IllegalStateException("Requested update cadence was not exercised exactly");
            sink = checksum;
            System.out.printf(Locale.ROOT,
                    "RWXCanvasFreezeBenchmark {\"cells\":%d,\"drawsPerFrame\":%d,\"warmup\":%d,\"frames\":%d," +
                    "\"allocatedBytes\":%d,\"bytesPerFrame\":%.3f,\"cpuNanosPerFrame\":%.3f," +
                    "\"wallNanosPerFrame\":%.3f,\"sink\":%d,\"freezeAndLeaseOnly\":%s," +
                    "\"dynamic\":%s,\"updateEvery\":%d,\"sourceUpdateEvery\":%d," +
                    "\"warmupCellUpdates\":%d,\"warmupSourceUpdates\":%d," +
                    "\"measuredCellUpdates\":%d,\"measuredSourceUpdates\":%d," +
                    "\"measuredStoreRevisionChanges\":%d," +
                    "\"measuredRerecordedDraws\":%d,\"measuredSourceRegistrationDraws\":%d," +
                    "\"cellRerecordAndRegistrationIncluded\":%s,\"sourceCommitAndRegistrationIncluded\":%s," +
                    "\"measuredCellClearCommands\":%d," +
                    "\"oldLeaseContentVerified\":%s,\"fixedRootRecordingReused\":true," +
                    "\"contentValidationOutsideTiming\":%s,\"extraRetainedLeasesDuringTiming\":%d}%n",
                    cells, draws * (cells + 1) + cells, warmup, frames, allocatedBytes,
                    (double) allocatedBytes / frames, (double) cpuNanos / frames,
                    (double) wallNanos / frames, sink, !dynamic, dynamic, updateEvery, sourceUpdateEvery,
                    warmupCellUpdates, warmupSourceUpdates, measuredCellUpdates, measuredSourceUpdates,
                    store.getTextureRevision() - warmupStoreRevision,
                    measuredCellUpdates * draws, measuredSourceUpdates,
                    updateEvery > 0, sourceUpdateEvery > 0, measuredCellUpdates, dynamic, dynamic, dynamic ? 1 : 0);
        } finally {
            FrozenCanvasGpuResources.INSTANCE.setGpuTargetInstaller$io_github_rwx_core(null);
        }
    }

    private static long measuredUpdates(int every, int warmup, int frames) {
        return every == 0 ? 0 : (warmup + frames) / every - warmup / every;
    }

    private static void requireMeasuredUpdates(String name, int every, int warmup, int frames) {
        if (every > 0 && measuredUpdates(every, warmup, frames) == 0)
            throw new IllegalArgumentException(name + " would not execute in the measured window");
    }

    private static float offset(int revision) {
        // Every allowed update has distinct content, with movement bounded below one pixel.
        return revision * 0.000001f;
    }

    private static int color(int revision) {
        return 0xff000000 | ((0x336699 + revision) & 0x00ffffff);
    }

    private static void recordCell(KoolGraphicsEngine cell, Texture sprite, GamePaint paint,
                                   Rect source, RectF destination, int draws, int revision) {
        cell.beginFrame(128, 128);
        // beginFrame only resets pending commands. GPU targets persist earlier committed contents;
        // a replacement recording must explicitly clear, otherwise p() appends the new draws.
        if (revision > 0) cell.a(0, KoolCanvasBlendMode.Clear);
        float shift = offset(revision);
        for (int d = 0; d < draws; d++) {
            float x = (d & 15) * 8f + shift, y = ((d >>> 4) & 15) * 8f;
            // Different atlas regions keep the existing repeat merger from collapsing these draws.
            source.a(d & 7, (d >>> 3) & 7, (d & 7) + 8, ((d >>> 3) & 7) + 8);
            destination.a(x, y, x + 8f, y + 8f);
            cell.a(sprite, source, destination, paint);
        }
        cell.p();
    }

    private static final class Updates {
        final KoolGraphicsEngine root;
        final Texture sprite;
        final GamePaint paint;
        final Rect source;
        final RectF destination;
        final KoolGraphicsEngine[] cells;
        final int[] revisions;
        final int draws;
        long cellUpdates, sourceUpdates;
        int sourceRevision;

        Updates(KoolGraphicsEngine root, Texture sprite, GamePaint paint, Rect source, RectF destination,
                KoolGraphicsEngine[] cells, int draws) {
            this.root = root;
            this.sprite = sprite;
            this.paint = paint;
            this.source = source;
            this.destination = destination;
            this.cells = cells;
            this.revisions = new int[cells.length];
            this.draws = draws;
        }

        void beforeFreeze(int iteration, int updateEvery, int sourceUpdateEvery) {
            if (updateEvery > 0 && iteration % updateEvery == 0) updateCell();
            if (sourceUpdateEvery > 0 && iteration % sourceUpdateEvery == 0) updateSource();
        }

        int updateCell() {
            int cell = (int) (cellUpdates % cells.length);
            recordCell(cells[cell], sprite, paint, source, destination, draws, ++revisions[cell]);
            cellUpdates++;
            return cell;
        }

        void updateSource() {
            Arrays.fill(sprite.j, color(++sourceRevision));
            sprite.p();
            // Trigger the normal pixel registration with a disposable root recording. freeze() keeps
            // using the original root frame, so descendants must follow the revised leaf themselves.
            root.beginFrame(1280, 720);
            source.a(0, 0, 16, 16);
            destination.a(0f, 0f, 16f, 16f);
            root.a(sprite, source, destination, paint);
            sourceUpdates++;
        }
    }

    /** Only used outside the measured window; inspect content, not just resource counts. */
    private static GraphState inspect(FrameEnvelope envelope, Updates expected) {
        GraphState observed = GraphState.capture(envelope);
        if (envelope.getResourceLease().getResourceCount() != expected.cells.length + 1 ||
                observed.cellOffsets.length != expected.cells.length)
            throw new IllegalStateException("Expected GPU targets and their one shared pixel source");
        float[] offsets = new float[expected.revisions.length];
        for (int i = 0; i < offsets.length; i++) offsets[i] = offset(expected.revisions[i]);
        Arrays.sort(offsets);
        if (!Arrays.equals(offsets, observed.cellOffsets))
            throw new IllegalStateException("Changed cell commands were not frozen: " + Arrays.toString(observed.cellOffsets));
        for (int pixel : observed.spritePixels) {
            if (pixel != color(expected.sourceRevision))
                throw new IllegalStateException("Changed leaf pixels were not frozen");
        }
        for (KoolCanvasFrame cell : observed.cellFrames) {
            boolean cleared = cell.getCommands().getFirst() instanceof KoolCanvasCommand.Clear;
            if (cleared && ((KoolCanvasCommand.Clear) cell.getCommands().getFirst()).getBlendMode() != KoolCanvasBlendMode.Clear)
                throw new IllegalStateException("Replacement cell recording must explicitly clear old target contents");
            if (cell.getCommands().size() != expected.draws + (cleared ? 1 : 0))
                throw new IllegalStateException("Cell rerecording changed the expected command count");
            var firstDraw = (KoolCanvasCommand.DrawTexture) cell.getCommands().get(cleared ? 1 : 0);
            if (cleared != (firstDraw.getDestination().getLeft() > 0f))
                throw new IllegalStateException("Updated cells must replace, rather than append to, old recording");
        }
        return observed;
    }

    private static void verifyUpdates(KoolCanvasCpuTextureStore store, KoolCanvasFrame recorded,
                                      Updates updates, boolean cell, boolean source) {
        try (FrameEnvelope before = store.freezeFrame(recorded, -3, 1, 0, 1, null)) {
            GraphState old = inspect(before, updates);
            if (cell) {
                int changed = updates.updateCell();
                try (FrameEnvelope after = store.freezeFrame(recorded, -4, 1, 0, 1, null)) {
                    inspect(after, updates);
                    if (rootTexture(before, changed).equals(rootTexture(after, changed)))
                        throw new IllegalStateException("Cell rerecording did not change its frozen target version");
                }
                old.verifyUnchanged(before);
            }
            if (source) {
                updates.updateSource();
                try (FrameEnvelope after = store.freezeFrame(recorded, -5, 1, 0, 1, null)) {
                    inspect(after, updates);
                    if (rootTexture(before, updates.cells.length).equals(rootTexture(after, updates.cells.length)))
                        throw new IllegalStateException("Source update did not change its frozen pixel version");
                    // All cells read this leaf without being rerecorded; their versions must follow it.
                    for (int i = 0; i < updates.cells.length; i++) {
                        if (rootTexture(before, i).equals(rootTexture(after, i)))
                            throw new IllegalStateException("Nested cell retained an obsolete source version");
                    }
                }
                old.verifyUnchanged(before);
            }
        }
        // Preflight is excluded from both warmup and measurement update counters. Revisions remain
        // the actual fixture state, while timed round-robin scheduling begins with cell zero.
        updates.cellUpdates = 0;
        updates.sourceUpdates = 0;
    }

    private static KoolCanvasTextureRef rootTexture(FrameEnvelope envelope, int command) {
        return ((KoolCanvasCommand.DrawTexture) envelope.getFrame().getCommands().get(command)).getTexture();
    }

    private static final class GraphState {
        final float[] cellOffsets;
        final List<KoolCanvasFrame> cellFrames;
        final int[] spritePixels;
        final List<KoolCanvasTextureRef> rootTextures;

        GraphState(float[] cellOffsets, List<KoolCanvasFrame> cellFrames, int[] spritePixels,
                   List<KoolCanvasTextureRef> rootTextures) {
            this.cellOffsets = cellOffsets;
            this.cellFrames = cellFrames;
            this.spritePixels = spritePixels;
            this.rootTextures = rootTextures;
        }

        static GraphState capture(FrameEnvelope envelope) {
            var cells = new ArrayList<KoolCanvasFrame>();
            int[] pixels = null;
            for (FrozenCanvasResource resource : envelope.getResourceLease().resources$io_github_rwx_core().values()) {
                if (resource instanceof FrozenCanvasResource.GpuTarget target) {
                    KoolCanvasFrame frame = target.getFrame();
                    cells.add(new KoolCanvasFrame(frame.getViewport(),
                            new ArrayList<>(frame.getCommands()), frame.getVisualStats()));
                }
                else if (resource instanceof FrozenCanvasResource.Pixels source) {
                    if (pixels != null) throw new IllegalStateException("Expected exactly one shared pixel source");
                    pixels = source.getImage().getPixels().clone();
                } else throw new IllegalStateException("Expected GPU targets or pixel resources, got " + resource);
            }
            if (pixels == null || pixels.length != 256) throw new IllegalStateException("Missing sprite pixels");
            float[] offsets = new float[cells.size()];
            for (int i = 0; i < cells.size(); i++) {
                var commands = cells.get(i).getCommands();
                offsets[i] = ((KoolCanvasCommand.DrawTexture) commands.get(
                        commands.getFirst() instanceof KoolCanvasCommand.Clear ? 1 : 0)).getDestination().getLeft();
            }
            Arrays.sort(offsets);
            var rootTextures = new ArrayList<KoolCanvasTextureRef>();
            for (KoolCanvasCommand command : envelope.getFrame().getCommands())
                rootTextures.add(((KoolCanvasCommand.DrawTexture) command).getTexture());
            return new GraphState(offsets, cells, pixels, rootTextures);
        }

        void verifyUnchanged(FrameEnvelope envelope) {
            GraphState now = capture(envelope);
            if (!Arrays.equals(cellOffsets, now.cellOffsets) || !Arrays.equals(spritePixels, now.spritePixels) ||
                    !rootTextures.equals(now.rootTextures) || !cellFrames.equals(now.cellFrames))
                throw new IllegalStateException("An old lease changed after target/source updates");
        }
    }

    private static long freeze(KoolCanvasCpuTextureStore store, KoolCanvasFrame frame, long seq, int resources) {
        try (FrameEnvelope envelope = store.freezeFrame(frame, seq, 1, 0, 1, null)) {
            if (envelope.getResourceLease().getResourceCount() != resources)
                throw new IllegalStateException("Incomplete frozen resource graph");
            return envelope.getFrame().getCommands().size() + envelope.getResourceLease().getResourceCount();
        }
    }
}
