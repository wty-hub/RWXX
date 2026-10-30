package io.github.rwx

import com.corrodinggames.rts.gameFramework.SettingsEngine
import de.fabmax.kool.platform.ClientApi
import de.fabmax.kool.platform.GlWindowCallbacks
import de.fabmax.kool.platform.KoolWindowJvm
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.platform.WindowSubsystem
import de.fabmax.kool.platform.swing.KoolGlCanvas
import de.fabmax.kool.platform.swing.SwingWindowSubsystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.lwjgl.opengl.GL
import java.awt.GraphicsEnvironment
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Runs Kool's Swing GL render loop with frame pacing done on the EDT between frames.
 *
 * Kool's own loop re-posts a frame as soon as the previous one ends and relies on a vsync swap to
 * throttle it. That swap happens inside [KoolGlCanvas.render], which holds the JAWT drawing-surface
 * lock (the AWT lock on X11) for the whole wait, so the embedded Slick game canvas and AWT input
 * dispatch stall behind every Kool frame. The Kool canvas therefore uses swap interval 0 and this
 * loop waits for the next frame slot without holding any lock. A hidden Kool canvas is never
 * presented (see [renderWithoutPresenting]).
 */
class PacedSwingWindowSubsystem(
    private val delegate: SwingWindowSubsystem,
) : WindowSubsystem by delegate {
    val usesExternalFramePacing: Boolean
        get() = delegate.providedCanvas is KoolGlCanvas

    @Volatile
    override var isCloseRequested: Boolean = false
        private set

    private val closeSignal = CompletableDeferred<Unit>()
    private var window: KoolWindowJvm? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "rwx-kool-frame-pacer").apply { isDaemon = true }
    }

    override fun createWindow(clientApi: ClientApi, glCallbacks: GlWindowCallbacks?, ctx: Lwjgl3Context): KoolWindowJvm =
        delegate.createWindow(clientApi, glCallbacks, ctx).also { window = it }

    override fun runRenderLoop() {
        val canvas = delegate.providedCanvas as? KoolGlCanvas
        if (canvas == null) {
            delegate.runRenderLoop()
            return
        }
        SwingUtilities.invokeLater(FrameLoop(canvas))
        runBlocking { closeSignal.await() }
    }

    fun close(onClosed: () -> Unit) {
        isCloseRequested = true
        if (delegate.providedCanvas !is KoolGlCanvas) {
            delegate.close(onClosed)
            return
        }
        closeSignal.invokeOnCompletion { onClosed() }
    }

    private inner class FrameLoop(private val canvas: KoolGlCanvas) : Runnable {
        private var frameStartNanos = 0L

        override fun run() {
            frameStartNanos = System.nanoTime()
            when {
                isCloseRequested -> {
                    scheduler.shutdownNow()
                    shutdownKool()
                    closeSignal.complete(Unit)
                }
                !canvas.isValid -> {
                    // Resizing or recreating the AWT peer can make the canvas invalid for a frame.
                    // Keep the loop alive so the UI returns when the surface becomes valid again.
                    GL.setCapabilities(null)
                    scheduleNext()
                }
                else -> {
                    window?.pollEvents()
                    if (canvas.isShowing) {
                        canvas.render()
                        // KoolGlCanvas.paintGL swaps before render() returns. Hidden back-buffer
                        // rendering below deliberately has no presentation acknowledgement.
                        io.github.rwx.render.canvas.CanvasFramePresentation.presented(true)
                        io.github.rwx.render.canvas.CanvasFrameMetrics.presented()
                    } else {
                        renderWithoutPresenting(canvas)
                    }
                    scheduleNext()
                }
            }
        }

        private fun scheduleNext() {
            val delay = desktopNextFrameDelayNanos(frameStartNanos, System.nanoTime(), framePeriodNanos())
            if (delay <= 0L) {
                SwingUtilities.invokeLater(this)
            } else {
                scheduler.schedule({ SwingUtilities.invokeLater(this) }, delay, TimeUnit.NANOSECONDS)
            }
        }

        private fun framePeriodNanos(): Long {
            val settings = SettingsEngine.getInstance()
            val targetFrameRate = desktopPresentationFrameRate(desktopTargetFrameRate(settings))
            if (!settings.renderVsync) {
                return desktopFramePeriodNanos(targetFrameRate, vsync = false, refreshRate = 0)
            }
            val refreshRate = runCatching {
                (canvas.graphicsConfiguration?.device
                    ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice)
                    .displayMode.refreshRate
            }.getOrNull() ?: 0
            return desktopFramePeriodNanos(targetFrameRate, vsync = true, refreshRate = refreshRate)
        }
    }

    /**
     * While the Kool overlay window is hidden (a match without HUD overlay), swapping its buffers on
     * XWayland blocks until a timeout even with swap interval 0, holding the AWT lock the whole time.
     * Kool still has to run its frame (UI state, dispatched tasks, session updates), so the frame is
     * drawn into the back buffer and never presented.
     */
    private fun renderWithoutPresenting(canvas: KoolGlCanvas) {
        val callbacks = glCallbacksGetter.invoke(canvas) as? GlWindowCallbacks
        if (callbacks == null) {
            canvas.render()
            return
        }
        canvas.runInContext { callbacks.drawFrame() }
    }

    private val glCallbacksGetter by lazy {
        KoolGlCanvas::class.java.getMethod("getGlCallbacks\$kool_core")
    }

    /** Kool's teardown is `internal` in kool-core; it compiles to a public static method. */
    private fun shutdownKool() {
        Class.forName("de.fabmax.kool.platform.WindowSubsystemKt")
            .getMethod("shutdown", WindowSubsystem::class.java)
            .invoke(null, this)
    }
}
