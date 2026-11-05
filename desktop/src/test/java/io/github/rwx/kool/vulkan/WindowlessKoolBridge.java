package io.github.rwx.kool.vulkan;

import de.fabmax.kool.platform.Lwjgl3Context;
import de.fabmax.kool.platform.WindowSubsystem;
import de.fabmax.kool.platform.WindowSubsystemKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

/** Test-only bridge to Kool 0.19's public JVM methods hidden by Kotlin internal visibility. */
public final class WindowlessKoolBridge {
    private WindowlessKoolBridge() { }

    public static Object renderFrame(Lwjgl3Context context, Continuation<? super Unit> continuation) {
        return context.renderFrame$kool_core(continuation);
    }

    public static void shutdown(WindowSubsystem subsystem) {
        WindowSubsystemKt.shutdown(subsystem);
    }
}
