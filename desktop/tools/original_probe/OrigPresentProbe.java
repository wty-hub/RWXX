package io.github.rwx.probe;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Records one line per presented frame of the original desktop build.
 *
 * The original has no RWX frame tracing, so the visible cadence is captured at the one call the game
 * makes for every presented frame: {@code org.lwjgl.opengl.Display.update()}. The output format matches
 * RWX's own {@code RWX_FRAME_TRACE} (`acceptedPresentNanos,sequence`), so the same analyzer computes the
 * same "fresh picture interval" tail for both.
 */
public final class OrigPresentProbe {
    /**
     * Batch writes; normal benchmark shutdown flushes the remaining rows in its shutdown hook.
     */
    private static final int FLUSH_EVERY = 128;

    private static BufferedWriter writer;
    private static long sequence;
    private static int pending;
    private static boolean failed;
    private static volatile boolean traceClosed;

    private OrigPresentProbe() {
    }

    /**
     * Starts a replay from inside the JVM so the measurement needs no mouse or keyboard input at all.
     *
     * `-Drwx.orig.replay=<path>` names the file and `-Drwx.orig.autostart.seconds=<n>` (default 45) waits for
     * the game to reach its menu first. The call chain is the one the game's own replay list uses:
     * `com.corrodinggames.rts.gameFramework.l.a.a(java.io.File)`, found by decompiling the libRocket binding
     * (`librocket/scripts/Root$9`) that the "load battle replay" tab invokes.
     */
    private static final long AUTO_START_NANOS =
        Long.getLong("rwx.orig.autostart.seconds", 45L) * 1_000_000_000L;
    private static final long STARTED_AT = System.nanoTime();

    /** Set by the agent's premain so the auto-start scan can enumerate the classes the engine loaded. */
    public static java.lang.instrument.Instrumentation instrumentation;

    /** The libRocket Root instance, captured by the agent the first time any of its methods runs. */
    private static volatile Object rootInstance;
    private static boolean autoStartAttempted = false;

    private static void maybeAutoStartReplay() {
        if (autoStartAttempted || AUTO_START_NANOS <= 0L) {
            return;
        }
        if (System.nanoTime() - STARTED_AT < AUTO_START_NANOS) {
            return;
        }
        autoStartAttempted = true;
        String path = System.getProperty("rwx.orig.replay");
        if (path == null || path.isEmpty()) {
            System.err.println("[rwx-probe] no -Drwx.orig.replay, leaving the menu alone");
            return;
        }
        // The libRocket binding that the "load battle replay" panel uses is Root$10 -> `ba.e(String)`.
        // (Root$9 is the *Share* button: `ba.a(String,boolean)` then `l.a.a(File)`, which only logs
        // "abstract shareFile" and never starts playback - measured, not guessed.)
        String name = System.getProperty("rwx.orig.replayname");
        if (name == null || name.isEmpty()) {
            name = new java.io.File(path).getName().replaceAll("\\.replay$", "");
        }
        try {
            Object done = startReplay(name);
            if (done == null) {
                System.err.println("[rwx-probe] no replay entry point reached for: " + name);
            } else {
                System.err.println("[rwx-probe] auto-start replay: " + done);
            }
        } catch (Throwable failure) {
            System.err.println("[rwx-probe] auto-start failed: " + failure);
        }
    }

    /**
     * Finds the replay entry point without knowing the obfuscated singleton layout: every static field of the
     * candidate holder classes is inspected, and the first object that either exposes `loadReplay(String)`
     * (libRocket `Root`, the panel handler) or is an instance of `ba` (whose `e(String)` is what the panel's
     * play button invokes) is used. Returns a description of what was called, or null.
     */
    private static String startReplay(String name) throws Exception {
        // Mirrors RWX's own path (GameSession.loadReplay): engine.replayEngine.loadReplay(name), where the
        // real source is `check(engine.replayEngine.loadReplay(replayName))`. Reached here by structure
        // instead of names: GameEngine is `gameFramework.l` (it exposes the static singleton getter B()),
        // `l.cb` is its ReplayEngine field, and `ba.c(String): boolean` is the only public instance method
        // that takes one String and returns boolean - RWX's loadReplay shape. RWX runs this on a background
        // loader thread, so a fresh thread is used here too rather than the render callback.
        Class<?> engineClass = Class.forName("com.corrodinggames.rts.gameFramework.l");
        Object engine = engineClass.getMethod("B").invoke(null);
        if (engine == null) {
            System.err.println("[rwx-probe] GameEngine singleton (l.B()) returned null");
            return null;
        }
        Object replayEngine = engineClass.getField("cb").get(engine);
        if (replayEngine == null) {
            System.err.println("[rwx-probe] GameEngine.replayEngine (l.cb) is null");
            return null;
        }
        // Verified pinned Root.loadReplay bytecode: clear bv, call cb.c(name) exactly once,
        // then resumeNonMenu only on success. No duplicate load or six-second callback sleep.
        Object root = rootInstance;
        if (root == null) throw new IllegalStateException("Root instance not captured");
        root.getClass().getMethod("loadReplay", String.class).invoke(root, name);
        if (!Boolean.TRUE.equals(replayEngine.getClass().getMethod("j").invoke(replayEngine)))
            throw new IllegalStateException("original replay load failed");
        root.getClass().getMethod("closePopup").invoke(root);
        panStartNanos = System.nanoTime();
        initialTick = engineClass.getField("bx").getInt(engine);
        initialGameTime = engineClass.getField("by").getInt(engine);
        zoomEnabled = !OrigNormalInputProbe.ENABLED && (cameraMode.equals("zoom") || cameraMode.equals("pan-zoom"));
        System.err.println("[rwx-probe] camera drive: mode=" + cameraMode + " startNanos=" + panStartNanos);
        System.err.println("[rwx-probe] simulation initialTick=" + initialTick + " initialGameTimeMillis=" + initialGameTime);
        new Thread(new Runnable() { public void run() {
            try { Thread.sleep(Long.getLong("rwx.orig.measure.seconds", 60L) * 1000L); }
            catch (InterruptedException interrupted) { return; }
            System.exit(0);
        }}, "rwx-original-benchmark-exit").start();
        return "GameEngine.replayEngine.loadReplay(" + name + ")";
    }

    /** Breadth-ordered field walk for the first object of the wanted type, bounded by [depth]. */
    private static Object findInstance(Object start, Class<?> wanted, int depth,
            java.util.IdentityHashMap<Object, Boolean> seen) {
        if (start == null || depth < 0 || seen.put(start, Boolean.TRUE) != null) {
            return null;
        }
        java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<Object>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Object current = queue.poll();
            for (Class<?> type = current.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    Object value;
                    try {
                        field.setAccessible(true);
                        value = field.get(current);
                    } catch (Throwable unreadable) {
                        continue;
                    }
                    if (value == null || seen.put(value, Boolean.TRUE) != null) {
                        continue;
                    }
                    if (wanted.isInstance(value)) {
                        return value;
                    }
                    if (depth > 1 && value.getClass().getName().startsWith("com.corrodinggames")) {
                        queue.add(value);
                    }
                }
            }
            depth--;
        }
        return null;
    }

    /** Called by the agent from every instance method of the libRocket Root class. */
    public static void onRootEnter(Object instance) {
        if (rootInstance == null && instance != null
                && instance.getClass().getName().startsWith("com.corrodinggames")) {
            rootInstance = instance;
            System.err.println("[rwx-probe] captured Root instance: " + instance.getClass().getName());
        }
    }

    /**
     * Camera pan that mirrors RWX's replay benchmark so both sides can be compared on the same scenario.
     *
     * `l.a(float, float)` is the engine's `setViewpoint` (proved by bytecode: it stores the arguments into
     * the viewpoint fields cy/cz), and `l.B()` is the GameEngine singleton. The displacement is the very same
     * piecewise triangle RWX uses, and the zoom cycle is left out on purpose - RWX is run with
     * `--camera-mode pan`, whose default zoom mode is `none`, which keeps this side free of any unknown
     * obfuscated zoom field. Runs on the present thread, like the replay load, because the engine needs the
     * GL context there.
     */
    private static final long PAN_PERIOD_NANOS = Long.getLong("rwx.orig.pan.period.seconds", 20L) * 1_000_000_000L;
    /** RWX clamps its pan displacement with `coerceIn(0f, PAN_AMPLITUDE)`; this is that constant. */
    private static final float PAN_AMPLITUDE = 800f;
    /** RWX's `RWX_REPLAY_PAN_ZOOM_PERIOD_SECONDS` default. */
    private static final long ZOOM_PERIOD_NANOS = Long.getLong("rwx.orig.zoom.period.seconds", 4L) * 1_000_000_000L;
    private static final String cameraMode = System.getProperty("rwx.orig.camera.mode", "pan-zoom");
    private static float zoomMinimum = Float.parseFloat(System.getProperty("rwx.orig.zoom.min", "0.35"));
    private static float zoomMaximum = Float.parseFloat(System.getProperty("rwx.orig.zoom.max", "1.5"));
    private static boolean zoomEnabled = false;
    private static long panStartNanos = 0L;
    private static float panBaseX = 0f;
    private static float panBaseY = 0f;
    private static boolean panPrimed = false;
    private static float measuredMinX = Float.POSITIVE_INFINITY, measuredMaxX = Float.NEGATIVE_INFINITY;
    private static float measuredMinY = Float.POSITIVE_INFINITY, measuredMaxY = Float.NEGATIVE_INFINITY;
    private static float measuredMinZoom = Float.POSITIVE_INFINITY, measuredMaxZoom = Float.NEGATIVE_INFINITY;
    private static int measuredWidth, measuredHeight;
    private static int initialTick, initialGameTime, sampleStartTick = -1, sampleStartGameTime;
    private static int sampleEndTick, sampleEndGameTime;
    private static boolean measuredFogEnabled, measuredFogDataPresent;

    /**
     * Drives the camera through the same pan *and* zoom as RWX's bench so the two can be compared on
     * identical motion.
     *
     * Both motions are transcribed from `ReplayPanBenchmark`:
     * - pan: the same piecewise triangle over a 20 s period, with the displacement clamped to the same
     *   800 world-unit amplitude RWX uses (this used to be 300 here, which is not comparable);
     * - zoom: the same smooth `1 -> minimum -> maximum -> 1` cosine cycle over 4 s, constrained to the
     *   same UI bounds `GameInterfaceRenderer` applies: minimum = min(screenW/worldW, screenH/worldH),
     *   maximum = 4.6.
     *
     * The zoom value is written to the engine's own `targetZoom`, which is exactly the field the game's
     * own zoom input and the replay session use, so the ordinary game loop applies it - no mouse, no
     * keyboard, no window activation.
     */
    private static void driveCameraIfActive() {
        if (panStartNanos == 0L) {
            return;
        }
        long now = System.nanoTime();
        if (now < panStartNanos) {
            return;
        }
        try {
            Class<?> engineClass = Class.forName("com.corrodinggames.rts.gameFramework.l");
            Object engine = engineClass.getMethod("B").invoke(null);
            if (engine == null) {
                return;
            }
            // tileMap is `bL`; the world extent comes from its public accessors.
            Object tileMap = engineClass.getField("bL").get(engine);
            if (tileMap == null) {
                return;
            }
            // World extent: `TileMap.getWorldWidth/getWorldHeight` are obfuscated to `i()`/`j()`
            // (JADX: "renamed from: i" / "renamed from: j"), both returning float.
            float worldWidth = ((Number) tileMap.getClass().getMethod("i").invoke(tileMap)).floatValue();
            float worldHeight = ((Number) tileMap.getClass().getMethod("j").invoke(tileMap)).floatValue();
            if (!panPrimed) {
                java.lang.reflect.Field x = engineClass.getField("cy");
                java.lang.reflect.Field y = engineClass.getField("cz");
                panBaseX = x.getFloat(engine);
                panBaseY = y.getFloat(engine);
                panPrimed = true;
                Class<?> display = Class.forName("org.lwjgl.opengl.Display");
                measuredWidth = ((Number) display.getMethod("getWidth").invoke(null)).intValue();
                measuredHeight = ((Number) display.getMethod("getHeight").invoke(null)).intValue();
                System.err.println("[rwx-probe] measured viewport " + measuredWidth + "x" + measuredHeight);
                measuredFogEnabled = tileMap.getClass().getField("E").getBoolean(tileMap);
                Object team = engineClass.getField("bs").get(engine);
                measuredFogDataPresent = team != null && team.getClass().getField("N").get(team) != null;
                System.err.println("[rwx-probe] fog evidence enabled=" + measuredFogEnabled + " data=" + measuredFogDataPresent);
                System.err.println("[rwx-probe] pan base " + panBaseX + "," + panBaseY
                        + " world " + worldWidth + "x" + worldHeight);
                if (OrigNormalInputProbe.ENABLED) {
                    engineClass.getField("cV").setFloat(engine, 1f);
                    engineClass.getMethod("a", float.class, float.class).invoke(engine,
                        (worldWidth - engineClass.getField("cE").getFloat(engine)) / 2f,
                        (worldHeight - engineClass.getField("cB").getFloat(engine)) / 2f);
                }
            }
            float zoom = 1f;
            if (zoomEnabled) {
                // Same bounds as RWX's constrainedTargetZoom: minimum from the world extent, maximum 4.6.
                float densityScale = engineClass.getField("cY").getFloat(engine);
                float minZoom = Math.min(1280f / worldWidth, 720f / worldHeight) / densityScale;
                double zoomPhase = ((now - panStartNanos) % ZOOM_PERIOD_NANOS) / (double) ZOOM_PERIOD_NANOS;
                zoom = Math.max(minZoom, Math.min(Math.min(4.6f / densityScale, 4.6f),
                    cycleZoom(zoomPhase, zoomMinimum, zoomMaximum)));
                // The engine's own zoom field: the ordinary game loop applies this exactly as it would a
                // player's zoom input, and the replay session writes it too.
                engineClass.getField("cV").setFloat(engine, zoom);
            }
            // Visible world extent at the requested zoom; the pan clamp below uses it, like RWX does.
            float actualZoom = engineClass.getField("cX").getFloat(engine);
            long warmup = Long.getLong("rwx.orig.warmup.seconds", 20L) * 1_000_000_000L;
            if (now >= panStartNanos + warmup) {
                int tick = engineClass.getField("bx").getInt(engine);
                int gameTime = engineClass.getField("by").getInt(engine);
                if (sampleStartTick < 0) { sampleStartTick = tick; sampleStartGameTime = gameTime; }
                sampleEndTick = tick; sampleEndGameTime = gameTime;
                float cameraX = engineClass.getField("cy").getFloat(engine);
                float cameraY = engineClass.getField("cz").getFloat(engine);
                measuredMinX = Math.min(measuredMinX, cameraX); measuredMaxX = Math.max(measuredMaxX, cameraX);
                measuredMinY = Math.min(measuredMinY, cameraY); measuredMaxY = Math.max(measuredMaxY, cameraY);
                measuredMinZoom = Math.min(measuredMinZoom, actualZoom); measuredMaxZoom = Math.max(measuredMaxZoom, actualZoom);
            }
            float visibleWidth = engineClass.getField("cE").getFloat(engine);
            float visibleHeight = engineClass.getField("cB").getFloat(engine);
            double phase = ((now - panStartNanos) % PAN_PERIOD_NANOS) / (double) PAN_PERIOD_NANOS;
            float displacement = phase < .25 ? (float) (4.0 * phase)
                : phase < .75 ? (float) (2.0 - 4.0 * phase)
                : (float) (4.0 * phase - 4.0);
            if (cameraMode.equals("static") || cameraMode.equals("zoom")) displacement = 0f;
            // RWX: amplitude(worldExtent, visibleExtent) = ((world - visible) / 2).coerceIn(0, 800).
            float amplitude = Math.max(0f, Math.min((worldWidth - visibleWidth) / 2f, PAN_AMPLITUDE));
            float amplitudeY = Math.max(0f, Math.min((worldHeight - visibleHeight) / 2f, PAN_AMPLITUDE));
            if (OrigNormalInputProbe.ENABLED) return;
            engineClass.getMethod("a", float.class, float.class)
                .invoke(engine, (worldWidth - visibleWidth) / 2f + amplitude * displacement,
                    (worldHeight - visibleHeight) / 2f + amplitudeY * displacement);
        } catch (Throwable failure) {
            System.err.println("[rwx-probe] pan failed: " + failure);
            panStartNanos = 0L;
        }
    }

    /** RWX's `cycleZoom`: a smooth 1 -> minimum -> maximum -> 1 cosine cycle. */
    private static float cycleZoom(double phase, float minimum, float maximum) {
        float ordinary = Math.max(minimum, Math.min(1f, maximum));
        float start;
        float end;
        double progress;
        if (phase < .25) {
            start = ordinary;
            end = minimum;
            progress = phase * 4.0;
        } else if (phase < .75) {
            start = minimum;
            end = maximum;
            progress = (phase - .25) * 2.0;
        } else {
            start = maximum;
            end = ordinary;
            progress = (phase - .75) * 4.0;
        }
        double blend = (1.0 - Math.cos(progress * Math.PI)) / 2.0;
        return (float) Math.max(minimum, Math.min(maximum, start + (end - start) * blend));
    }

    /**
     * Suppresses the map's opening briefing without touching the mouse or keyboard.
     *
     * RWX's source shows the trigger: `if (this.showIntro) { showIntro = false; if (introText != null)
     * gameEngine.showMessageBox("Briefing", introText); }` in MissionEngine, so nulling the intro text makes
     * the box never appear - far safer than flipping the other mission booleans. Obfuscated mapping, all
     * established from the jar: MissionEngine is `gameFramework.n.f`, `GameEngine.missionEngine` is `l.ce`,
     * and that class's two String fields are `s` and `t`. Throttled to every 30th frame because the replay
     * load rebuilds mission state.
     */
    private static int suppressTick = 0;

    private static void suppressBriefingIfActive() {
        if ((suppressTick++ % 30) != 0) {
            return;
        }
        try {
            Class<?> engineClass = Class.forName("com.corrodinggames.rts.gameFramework.l");
            Object engine = engineClass.getMethod("B").invoke(null);
            if (engine == null) {
                return;
            }
            Object mission = engineClass.getField("ce").get(engine);
            if (mission == null) {
                return;
            }
            for (String name : new String[] {"s", "t"}) {
                try {
                    // These fields are package-private, so getField (public only) can never find them and
                    // the suppression would silently do nothing - measured: the Briefing box still appeared.
                    java.lang.reflect.Field field = mission.getClass().getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(mission, null);
                } catch (Throwable ignored) {
                    // Not every mission state exposes both strings; the other still suppresses the box.
                }
            }
        } catch (Throwable failure) {
            System.err.println("[rwx-probe] briefing suppress failed: " + failure);
        }
    }

    public static synchronized void onUpdate(String tag) {
        if (traceClosed) return;
        // Display.update() delegates to update(boolean); drive the next camera state once.
        if (!"(Z)V".equals(tag)) return;
        maybeAutoStartReplay();
        suppressBriefingIfActive();
        driveCameraIfActive();
        if (OrigNormalInputProbe.ENABLED) OrigNormalInputProbe.drive(panStartNanos);
    }

    /** Record after the native swap returned, analogous to RWX's accepted queue-present hook. */
    public static synchronized void onPresent(String tag) {
        if (traceClosed || !"(Z)V".equals(tag)) return;
        long nanos = System.nanoTime();
        if (writer == null) {
            open();
        }
        if (writer == null) {
            return;
        }
        sequence++;
        if (OrigNormalInputProbe.ENABLED) OrigNormalInputProbe.presented(nanos, sequence);
        try {
            writer.write(Long.toString(nanos));
            writer.write(',');
            writer.write(Long.toString(sequence));
            writer.write(',');
            writer.write(tag);
            writer.write('\n');
        } catch (IOException error) {
            System.err.println("[rwx-probe] trace write failed: " + error);
            failed = true;
            return;
        }
        if (++pending >= FLUSH_EVERY) {
            flush();
        }
    }

    private static void open() {
        String path = System.getProperty("rwx.orig.trace");
        if (path == null || path.isEmpty()) {
            failed = true;
            return;
        }
        try {
            writer = new BufferedWriter(new FileWriter(path, false), 1 << 16);
            writer.write("acceptedPresentNanos,sequence,overload\n");
            writer.flush();
            System.err.println("[rwx-probe] trace opened: " + path);
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    synchronized (OrigPresentProbe.class) {
                        traceClosed = true;
                        flush();
                        close();
                        System.err.println("[rwx-probe] camera evidence viewport=" + measuredWidth + "x" + measuredHeight
                            + " spanX=" + (measuredMaxX - measuredMinX) + " spanY=" + (measuredMaxY - measuredMinY)
                            + " zoomMin=" + measuredMinZoom + " zoomMax=" + measuredMaxZoom);
                        System.err.println("[rwx-probe] simulation evidence initialTick=" + initialTick
                            + " initialGameTimeMillis=" + initialGameTime + " sampleStartTick=" + sampleStartTick
                            + " sampleStartGameTimeMillis=" + sampleStartGameTime + " sampleEndTick=" + sampleEndTick
                            + " sampleEndGameTimeMillis=" + sampleEndGameTime);
                    }
                }
            }, "rwx-orig-trace-flush"));
        } catch (IOException error) {
            System.err.println("[rwx-probe] trace open failed: " + error);
            failed = true;
        }
    }

    private static void flush() {
        if (writer == null || failed) {
            return;
        }
        try {
            writer.flush();
            pending = 0;
        } catch (IOException error) {
            System.err.println("[rwx-probe] trace flush failed: " + error);
            failed = true;
        }
    }

    private static void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // The trace is a diagnostic; a failed close must not affect the game.
        }
        writer = null;
    }
}
