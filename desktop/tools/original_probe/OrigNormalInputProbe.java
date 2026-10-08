package io.github.rwx.probe;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Diagnostic only: ordinary Slick input callbacks to the frame rendered with that camera. */
public final class OrigNormalInputProbe {
    public static final boolean ENABLED = Boolean.getBoolean("rwx.orig.normal.input");
    private static Object slick, engine;
    private static Field x, y, zoom;
    private static BufferedWriter writer;
    private static long step = -1, serial;
    private static final ArrayList<Event> pending = new ArrayList<Event>();
    private static final class Event {
        long id, sampled, applied, produced;
        String kind;
        int value;
        float beforeX, beforeY, beforeZoom, pictureX, pictureY, pictureZoom;
        boolean expectsCamera, acknowledged;
    }
    private OrigNormalInputProbe() {}

    public static void capture(Object instance) { if (slick == null) slick = instance; }

    public static void drive(long start) {
        if (!ENABLED || start == 0 || slick == null) return;
        try {
            if (engine == null) {
                Class<?> type = Class.forName("com.corrodinggames.rts.gameFramework.l");
                engine = type.getMethod("B").invoke(null);
                if (engine == null) return;
                x = type.getField("cw"); y = type.getField("cx"); zoom = type.getField("cX");
                writer = new BufferedWriter(new FileWriter(System.getProperty("rwx.orig.input.trace")), 65536);
                writer.write("stage,id,kind,value,sampledNanos,appliedNanos,producedNanos,acceptedPresentNanos,generation,sequence,cameraX,cameraY,zoom,expectsCamera\n");
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() { public void run() {
                    try { synchronized (OrigNormalInputProbe.class) { writer.close(); } }
                    catch (Exception error) { error.printStackTrace(); }
                }}, "original-input-trace-close"));
            }
            long next = (System.nanoTime() - start) / 250_000_000L;
            if (next == step) return;
            step = next;
            switch ((int) (step % 16)) {
                case 0:
                    input("pointer/move", 0, "mouseMoved", new int[] {640, 360, 640, 360});
                    input("pointer/down", 3, "mousePressed", new int[] {2, 640, 360}); break;
                // First displacement activates GameUI's drag and resets the anchor; it is still
                // retained and acknowledged, while the reverse displacement drives the camera.
                case 1: input("pointer/activation", 3, "mouseDragged", new int[] {640, 360, 680, 380}); break;
                case 2: input("pointer/drag", 3, "mouseDragged", new int[] {680, 380, 610, 335}); break;
                case 3: input("pointer/up", 3, "mouseReleased", new int[] {2, 640, 360}); break;
                case 4: key(205, 22, true); break;
                case 5: key(205, 22, false); break;
                case 6: key(203, 21, true); break;
                case 7: key(203, 21, false); break;
                case 8: key(208, 20, true); break;
                case 9: key(208, 20, false); break;
                case 10: key(200, 19, true); break;
                case 11: key(200, 19, false); break;
                case 12: input("wheel", -120, "mouseWheelMoved", new int[] {-120}); break;
                case 14: input("wheel", 120, "mouseWheelMoved", new int[] {120}); break;
                default: break;
            }
        } catch (Exception error) { throw new IllegalStateException("Original normal input probe failed", error); }
    }

    private static void key(int slickKey, int androidKey, boolean down) throws Exception {
        invoke("key/" + (down ? "down" : "up"), androidKey,
            slick.getClass().getMethod(down ? "keyPressed" : "keyReleased", int.class, char.class),
            new Object[] {slickKey, Character.valueOf('\0')});
    }
    private static void input(String kind, int value, String method, int[] arguments) throws Exception {
        Class<?>[] types = new Class<?>[arguments.length]; Object[] values = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) { types[i] = int.class; values[i] = arguments[i]; }
        invoke(kind, value, slick.getClass().getMethod(method, types), values);
    }
    private static void invoke(String kind, int value, Method method, Object[] arguments) throws Exception {
        Event event = new Event(); event.id = ++serial; event.kind = kind; event.value = value;
        event.expectsCamera = kind.equals("pointer/drag") || kind.equals("key/down") || kind.equals("wheel");
        event.sampled = System.nanoTime();
        event.beforeX = x.getFloat(engine); event.beforeY = y.getFloat(engine); event.beforeZoom = zoom.getFloat(engine);
        write("sampled", event, 0, 0);
        event.applied = System.nanoTime(); write("applied", event, 0, 0);
        method.invoke(slick, arguments);
        pending.add(event);
    }

    /** Hook after Slick render: Display's next swap still presents this immutable camera snapshot. */
    public static synchronized void rendered() {
        if (engine == null || pending.isEmpty()) return;
        try {
            long now = System.nanoTime();
            for (Event event : pending) if (event.produced == 0) {
                event.produced = now; event.pictureX = x.getFloat(engine); event.pictureY = y.getFloat(engine);
                event.pictureZoom = zoom.getFloat(engine);
            }
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    public static synchronized void presented(long now, long sequence) {
        if (writer == null) return;
        try {
            java.util.Iterator<Event> events = pending.iterator();
            while (events.hasNext()) { Event event = events.next(); if (event.produced != 0) {
                if (!event.acknowledged) { write("presented", event, now, sequence); event.acknowledged = true; }
                boolean changed = event.kind.equals("wheel") ? Math.abs(event.beforeZoom - event.pictureZoom) > 1e-5f :
                    Math.abs(event.beforeX - event.pictureX) > 1e-5f || Math.abs(event.beforeY - event.pictureY) > 1e-5f;
                if (!event.expectsCamera) events.remove();
                else if (changed) { write("camera-effect", event, now, sequence); events.remove(); }
                else if (now - event.sampled >= 200_000_000L) {
                    write("camera-effect-timeout", event, now, sequence); events.remove();
                } else event.produced = 0;
            } }
            writer.flush();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static void write(String stage, Event event, long present, long sequence) throws Exception {
        boolean picture = event.produced != 0;
        writer.write(stage + "," + event.id + "," + event.kind + "," + event.value + "," + event.sampled + "," +
            event.applied + "," + event.produced + "," + present + ",0," + sequence + "," +
            (picture ? event.pictureX : event.beforeX) + "," + (picture ? event.pictureY : event.beforeY) + "," +
            (picture ? event.pictureZoom : event.beforeZoom) + "," + event.expectsCamera + "\n");
    }
}
