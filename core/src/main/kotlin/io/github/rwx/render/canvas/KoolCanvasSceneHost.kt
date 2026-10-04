package io.github.rwx.render.canvas

import de.fabmax.kool.scene.OnRenderScene
import de.fabmax.kool.scene.Scene
import io.github.rwx.logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Frame statistics for the canvas replay itself, enabled with `RWX_CANVAS_PERF=1`.
 *
 * [KoolCanvasFrameRenderer.render] turns the game's command stream into meshes and instance buffers
 * and runs inside Kool's scene render, i.e. after the game session returned from `updateFrame`.
 * Neither `RWX_PERF_LOG` (which measures the game loop) nor the Slick frame log can attribute time
 * to it, so a frame that spends all its budget here still looks like "0.2ms of work".
 */
internal object KoolCanvasRenderProbe {
    private var total = 0L
    private var peak = 0L
    private var count = 0
    private var windowStart = System.nanoTime()

    fun record(nanos: Long) {
        if (System.getenv("RWX_CANVAS_PERF") != "1") return
        total += nanos
        peak = maxOf(peak, nanos)
        count++
        val now = System.nanoTime()
        if (now - windowStart >= WINDOW_NANOS) {
            if (count > 0) {
                logger.info("RWXPerf") {
                    "canvas frames=$count replay[avg=%.2fms peak=%.2fms]".format(
                        total.toDouble() / count / 1e6,
                        peak / 1e6,
                    )
                }
            }
            total = 0
            peak = 0
            count = 0
            windowStart = now
        }
    }

    private const val WINDOW_NANOS = 2_000_000_000L
}

class KoolCanvasSceneHost(
    private val frameRenderer: KoolCanvasFrameRenderer = KoolCanvasFrameRenderer(),
    private val sceneName: String = DEFAULT_SCENE_NAME,
    private val installResources: (KoolCanvasResourceLease) -> AutoCloseable = FrozenCanvasGpuResources::install,
) : KoolCanvasRenderer {
    private var activeScene: Scene? = null
    private val mailbox = LatestFrameMailbox()
    private var currentEnvelope: FrameEnvelope? = null
    private var installedResources: AutoCloseable? = null
    private var renderedEnvelope: FrameEnvelope? = null
    private var retirementSink: ((() -> Unit) -> Unit)? = null
    private var presentationTracker: CanvasFramePresentationTracker? = null
    private val presentationOwner = Any()
    @Volatile
    private var useEnvelope = false

    @Volatile
    private var latestFrame: KoolCanvasFrame = EmptyFrame

    val scene: Scene?
        get() = activeScene

    fun createScene(): Scene = Scene(sceneName).also(::configure)

    override fun render(frame: KoolCanvasFrame) {
        latestFrame = frame.copy(commands = frame.commands.toList())
        useEnvelope = false
    }

    /** Takes ownership of [envelope]; unused pending frames are released immediately. */
    fun submit(envelope: FrameEnvelope) {
        mailbox.publish(envelope)
        useEnvelope = true
    }

    /** Set on the render thread. Vulkan supplies a callback tied to successful fence completion. */
    fun setGpuRetirementSink(sink: ((() -> Unit) -> Unit)?) {
        retirementSink = sink
        KoolCanvasGpuRetirement.install(sink)
    }

    fun setPresentationTracker(tracker: CanvasFramePresentationTracker?) { presentationTracker = tracker }

    fun currentFrame(): KoolCanvasFrame = currentEnvelope?.frame ?: latestFrame

    private fun retireCurrent() {
        val envelope = currentEnvelope ?: return
        val installed = installedResources
        // Until the fence sink accepts retirement, keep the old front and GPU owners in place.
        // A sink may invoke or enqueue the callback and then throw; that callback must stay inert.
        val retirementState = AtomicInteger(0) // Pending, accepted, requested, released, rejected.
        val releaseResources: () -> Unit = {
            try {
                installed?.close()
            } finally {
                envelope.close()
            }
        }
        val release: () -> Unit = {
            if (!retirementState.compareAndSet(0, 2) && retirementState.compareAndSet(1, 3)) {
                releaseResources()
            }
        }
        try {
            retirementSink?.invoke(release) ?: release()
        } catch (failure: Throwable) {
            retirementState.set(4)
            throw failure
        }
        currentEnvelope = null
        installedResources = null
        if (!retirementState.compareAndSet(0, 1) && retirementState.compareAndSet(2, 3)) {
            releaseResources()
        }
    }

    private fun configure(scene: Scene) {
        activeScene = scene
        scene.onRelease {
            CanvasFramePresentation.clear(presentationOwner)
            presentationTracker?.clear()
            mailbox.close()
            retireCurrent()
            activeScene = null
            renderedEnvelope = null
        }
        scene.onRenderScene += OnRenderScene {
            var replayed = false
            if (!useEnvelope) {
                mailbox.clear()
                retireCurrent()
            }
            mailbox.poll()?.let { next ->
                // Install first: shared versions retain an owner while the previous GPU lease retires.
                val installStart = CanvasRenderStageTrace.start()
                var installed: AutoCloseable? = null
                var adopted = false
                try {
                    installed = installResources(next.resourceLease)
                    retireCurrent()
                    currentEnvelope = next
                    installedResources = installed
                    adopted = true
                    CanvasRenderStageTrace.record("envelope-install", installStart, next.sequence, next.generation)
                } catch (failure: Throwable) {
                    if (!adopted) {
                        // This new frame has never reached the renderer, so it needs no GPU fence.
                        try {
                            installed?.close()
                        } catch (cleanup: Throwable) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                        try {
                            next.close()
                        } catch (cleanup: Throwable) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                    }
                    throw failure
                }
            }
            val envelope = currentEnvelope
            if (envelope != null) {
                CanvasFrameMetrics.chosen(envelope.sequence, envelope.generation)
                if (renderedEnvelope !== envelope) {
                    val startedAt = System.nanoTime()
                    KoolCanvasFontRegistry.withSnapshot(envelope.resourceLease.fonts) {
                        frameRenderer.render(scene, envelope.frame.withSurfaceClear())
                    }
                    renderedEnvelope = envelope
                    replayed = true
                    KoolCanvasRenderProbe.record(System.nanoTime() - startedAt)
                    if (CanvasRenderStageTrace.enabled) CanvasRenderStageTrace.record(
                        "canvas-new", startedAt, envelope.sequence, envelope.generation, envelope.frame.commands.size.toLong())
                }
                CanvasFramePresentation.chosen(presentationOwner, presentationTracker, envelope.sequence, envelope.generation, envelope.camera)
            } else {
                CanvasFramePresentation.clear(presentationOwner)
                val startedAt = System.nanoTime()
                frameRenderer.render(scene, latestFrame)
                replayed = true
                KoolCanvasRenderProbe.record(System.nanoTime() - startedAt)
            }
            // Repeated presentation still drains textures already proven GPU-idle by the sink.
            if (!replayed) {
                val repeatStart = CanvasRenderStageTrace.start()
                if (envelope != null) KoolCanvasFontRegistry.withSnapshot(envelope.resourceLease.fonts) {
                    frameRenderer.refreshPerformanceHud(envelope.frame.viewport)
                }
                KoolCanvasTextureRegistry.releaseRetiredTextures()
                CanvasRenderStageTrace.record("canvas-repeat", repeatStart, envelope?.sequence ?: -1,
                    envelope?.generation ?: -1)
            }
        }
    }

    companion object {
        const val DEFAULT_SCENE_NAME: String = "kool-canvas"
        val EmptyFrame: KoolCanvasFrame = KoolCanvasFrame(KoolCanvasViewport(0, 0), emptyList())
    }
}

private fun KoolCanvasFrame.withSurfaceClear(): KoolCanvasFrame =
    if (commands.any { it is KoolCanvasCommand.Clear && it.renderTarget == null }) this
    else copy(commands = listOf(KoolCanvasCommand.Clear(KoolCanvasColor(0xff000000.toInt()), KoolCanvasBlendMode.Source)) + commands)
