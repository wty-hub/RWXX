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

    /**
     * Count and duration of actually replayed frames, always maintained.
     *
     * The owner loop advances far more often than a frame is rendered (measured: ~610 owner iterations
     * per second against ~120 rendered frames), and its per-frame trace has one row per iteration. Without
     * this, a row cannot be told apart from a non-rendering iteration, so a trace-derived frame budget is
     * wrong by a large factor - which is exactly what stalled the renderer-side analysis.
     */
    @Volatile
    var sequence: Long = 0L
        private set

    @Volatile
    var lastReplayNanos: Long = 0L
        private set

    /**
     * Nanoseconds from the end of the previous replay to the start of this one.
     *
     * The distinguishing measurement for the frame budget: with a non-blocking single-slot mailbox the
     * render thread either waits for the owner to publish (starved) or blocks somewhere after the replay
     * call (present/swapchain/fence). Both look like "frame rate lower than the work", so the gap has to be
     * measured rather than inferred.
     */
    @Volatile
    var lastReplayGapNanos: Long = 0L
        private set

    private var lastReplayEndNanos = 0L

    /**
     * Gaps either side of the replay call, measured in the host callback rather than inferred.
     *
     * The inter-replay gap dominates the render thread's time (measured 62.5% of the span against 36.2%
     * for the replay itself, with a p99 of 181ms and a maximum of 732ms), but a single gap number cannot
     * say whether the stall happens *before* the replay is entered - the mailbox had nothing to render, so
     * the render loop was waiting - or *after* it returns, in the host's scene render, present or swapchain.
     * Splitting it at the callback boundary is what makes the tail actionable.
     */
    @Volatile
    var lastCallbackEntryGapNanos: Long = 0L
        private set

    @Volatile
    var lastCallbackExitGapNanos: Long = 0L
        private set

    private var lastCallbackExitNanos = 0L

    /** Called at the top of the host's render callback, before any work for this frame. */
    fun callbackEntered() {
        val now = System.nanoTime()
        lastCallbackEntryGapNanos =
            if (lastCallbackExitNanos == 0L) 0L else now - lastCallbackExitNanos
    }

    /** Called once the frame's replay and presentation are done. */
    fun callbackExited() {
        lastCallbackExitNanos = System.nanoTime()
    }

    fun record(nanos: Long) {
        val now = System.nanoTime()
        sequence++
        lastReplayNanos = nanos
        lastReplayGapNanos = if (lastReplayEndNanos == 0L) 0L else (now - nanos) - lastReplayEndNanos
        lastReplayEndNanos = now
        if (System.getenv("RWX_CANVAS_PERF") != "1") return
        total += nanos
        peak = maxOf(peak, nanos)
        count++
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

    /**
     * True only for a host that owns a live kool backend and can therefore create offscreen attachments.
     *
     * Deliberately independent of the retirement sink: the failure-path tests install their own sink, so a
     * sink-based gate would not keep them out of this path, and their assertions depend on how much work a
     * single render callback performs. Only real-context callers set this.
     */
    private var gpuOffscreenPassesAvailable = false
    private var presentationTracker: CanvasFramePresentationTracker? = null
    private val presentationOwner = Any()
    @Volatile
    private var useEnvelope = false

    /**
     * GPU offscreen targets (`RenderTargetMode.GPU_TARGET`) render into their own pass instead of CPU
     * pixels. They are installed from the frame install path so the same frame renders and samples
     * them, exactly like the CPU targets they replace.
     */
    private val gpuTargetPasses = KoolCanvasGpuTargetPasses()
    private val gpuTargetInstaller: (KoolCanvasTextureId, FrozenCanvasResource.GpuTarget) -> Unit =
        { id, target -> gpuTargetPasses.install(id, target) }
    private val gpuTargetReleaser: (KoolCanvasTextureId) -> Unit = gpuTargetPasses::releaseVersion

    /** Bounded offscreen-target accounting for diagnostics; null until a scene is configured. */
    fun gpuTargetProfile(): String? = activeScene?.let { gpuTargetPasses.profile() }

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
    /** Marks this host as backed by a real kool context; see [gpuOffscreenPassesAvailable]. */
    fun setGpuOffscreenPassesAvailable(available: Boolean) {
        gpuOffscreenPassesAvailable = available
    }

    fun setGpuRetirementSink(sink: ((() -> Unit) -> Unit)?) {
        retirementSink = sink
        KoolCanvasGpuRetirement.install(sink)
    }

    /**
     * Optional companion to [setGpuRetirementSink] reporting how many fence-scoped releases are queued.
     * Kept as its own setter so a trailing lambda can never bind to it by mistake.
     */
    fun setGpuRetirementPendingCounter(counter: (() -> Int)?) {
        KoolCanvasGpuRetirement.installPendingCounter(counter)
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
        // Offscreen targets need a live kool backend: their passes create attachment images on the
        // first draw. Headless hosts and store-only tests must not pay for, or depend on, that.
        // Only the user-facing switch gates this today. `gpuOffscreenPassesAvailable` exists for the
        // future default-on step, but gating on it now kept the offscreen path inactive in the benchmark
        // entry point (measured: gpuTargets[created=0] while the switch read true), so it is not required
        // until every entry point reports its backend.
        if (KoolGraphicsEngine.GPU_RENDER_TARGETS_ENABLED && gpuOffscreenPassesAvailable) {
            gpuTargetPasses.attach(scene)
            FrozenCanvasGpuResources.gpuTargetInstaller = gpuTargetInstaller
            FrozenCanvasGpuResources.gpuTargetReleaser = gpuTargetReleaser
        }
        scene.onRelease {
            CanvasFramePresentation.clear(presentationOwner)
            presentationTracker?.clear()
            mailbox.close()
            retireCurrent()
            // Only clear the process-wide hooks if they are still ours; a scene swap configures the
            // replacement before the old scene is released.
            if (FrozenCanvasGpuResources.gpuTargetInstaller === gpuTargetInstaller) {
                FrozenCanvasGpuResources.gpuTargetInstaller = null
                FrozenCanvasGpuResources.gpuTargetReleaser = null
            }
            gpuTargetPasses.close()
            activeScene = null
            renderedEnvelope = null
        }
        scene.onRenderScene += OnRenderScene {
            KoolCanvasRenderProbe.callbackEntered()
            // Frame start: release passes whose fence completed earlier, before kool builds this
            // frame's pass list, and publish the counters the A/B harness reads.
            gpuTargetPasses.beginFrame()
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
                    // Diagnostic only: the reuse tracker measures text keys across frames, so it needs a
                    // frame boundary rather than a pass boundary. No-op unless the profile is enabled.
                    KoolCanvasCommandProfile.endFrame()
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
            KoolCanvasRenderProbe.callbackExited()
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
