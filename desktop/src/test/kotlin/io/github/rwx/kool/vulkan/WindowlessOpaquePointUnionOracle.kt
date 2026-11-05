package io.github.rwx.kool.vulkan

import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.backend.vk.ImageVk
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.scene.OnRenderScene
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.FrontendScope
import de.fabmax.kool.util.Uint8Buffer
import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import io.github.rwx.render.canvas.FrameEnvelope
import io.github.rwx.render.canvas.KoolCanvasBlendMode
import io.github.rwx.render.canvas.KoolCanvasCommand
import io.github.rwx.render.canvas.KoolCanvasCpuTextureStore
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasFrameRenderer
import io.github.rwx.render.canvas.KoolCanvasSceneHost
import io.github.rwx.render.canvas.KoolCanvasTextureRegistry
import io.github.rwx.render.canvas.KoolGraphicsEngine
import io.github.rwx.render.canvas.KoolPaint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Explicit standalone correctness oracle, never discovered as a JUnit test or used to measure FPS.
 * Both controls are recorded through the real GraphicsEngine array API, frozen, and installed by
 * the production SceneHost. The caller must impose an outer 70-second process deadline because a
 * coroutine timeout cannot interrupt a native fence wait. No physical input, window or audio API is used.
 */
object WindowlessOpaquePointUnionOracle {
    private const val POINTS = 576
    private val configure = internalMethod("configureUnionOpaquePointsForTest", 1)
    private val counters = internalMethod("opaquePointUnionCountersForTest", 0)

    private fun internalMethod(name: String, parameters: Int): Method =
        KoolGraphicsEngine::class.java.declaredMethods.single {
            it.name.substringBefore('$') == name && it.parameterCount == parameters
        }.apply { isAccessible = true }

    private fun invoke(method: Method, receiver: KoolGraphicsEngine, vararg arguments: Any): Any? =
        try { method.invoke(receiver, *arguments) }
        catch (failure: InvocationTargetException) { throw failure.targetException }

    private data class UnionCounts(val calls: Long, val inputPoints: Long, val outputRects: Long)
    private fun counts(engine: KoolGraphicsEngine): UnionCounts {
        val values = invoke(counters, engine) as? List<*> ?: error("Union counters are not a List")
        check(values.size == 3) { "Unexpected union counter schema: $values" }
        fun number(index: Int) = (values[index] as? Number)?.toLong() ?: error("Invalid union counter: $values")
        return UnionCounts(number(0), number(1), number(2))
    }

    private fun paint(argb: Int = 0xff00ff00.toInt(), side: Float = 3f) =
        KoolPaint().apply { b(argb); a(side) }

    /** The same nine integer positions, each repeated64 times, as a small dense minimap cluster. */
    private fun cluster(x: Int, y: Int) = FloatArray(POINTS * 2) { scalar ->
        val point = scalar / 2
        if (scalar % 2 == 0) (x + point % 3).toFloat() else (y + (point / 3) % 3).toFloat()
    }

    private data class Case(
        val label: String,
        val acceptedCalls: Long,
        val acceptedInputPoints: Long = acceptedCalls * POINTS,
        val draw: (KoolGraphicsEngine) -> Unit,
    )

    private fun cases(width: Int, height: Int): List<Case> {
        val dense = cluster(7, 9)
        val negative = cluster(-2, -2)
        val edge = cluster(width - 3, height - 3)
        val teamA = cluster(6, 7)
        val teamB = cluster(8, 9)
        return (1..8).filter { it in setOf(1, 2, 3, 8) }.map { side ->
            Case("dense-side$side", 1) { it.a(dense, 0, dense.size, paint(side = side.toFloat())) }
        } + listOf(
            Case("negative-edge-side3", 1) { it.a(negative, 0, negative.size, paint()) },
            Case("bottom-right-edge-side8", 1) { it.a(edge, 0, edge.size, paint(side = 8f)) },
            Case("two-team-order-with-blue-barrier", 2) {
                it.a(teamA, 0, teamA.size, paint(0xff00ff00.toInt()))
                it.a(RectF(7f, 7f, 9f, 25f), paint(0xff0000ff.toInt()))
                it.a(teamB, 0, teamB.size, paint(0xffff0000.toInt()))
            },
            Case("half-alpha-whole-call-fallback", 0) {
                it.a(dense, 0, dense.size, paint(0x8000ff00.toInt()))
            },
            Case("alpha254-whole-call-fallback", 0) {
                it.a(dense, 0, dense.size, paint(0xfe00ff00.toInt()))
            },
            Case("clip-whole-call-fallback", 0) {
                it.a(Rect(8, 10, 11, 13))
                it.a(dense, 0, dense.size, paint())
            },
            Case("fractional-translation-whole-call-fallback", 0) {
                it.b(.5f, 1.25f)
                it.a(dense, 0, dense.size, paint())
            },
        )
    }

    private data class Recording(val frame: KoolCanvasFrame, val counts: UnionCounts, val rects: Int)

    private fun record(store: KoolCanvasCpuTextureStore, width: Int, height: Int, case: Case, enabled: Boolean): Recording {
        val graphics = KoolGraphicsEngine(textureStore = store)
        invoke(configure, graphics, enabled)
        graphics.beginFrame(width, height)
        // Deliberately transparent so the comparison also checks the resulting alpha and edge samples.
        graphics.a(0, KoolCanvasBlendMode.Source)
        case.draw(graphics)
        val frame = graphics.snapshot()
        check(frame.commands.all { it is KoolCanvasCommand.Clear || it is KoolCanvasCommand.DrawRect }) {
            "${case.label}: another batching candidate changed this point-only oracle"
        }
        return Recording(frame, counts(graphics), frame.commands.count { it is KoolCanvasCommand.DrawRect })
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.contentEquals(arrayOf("--opaque-point-union"))) {
            "Usage: WindowlessOpaquePointUnionOracle --opaque-point-union"
        }
        require(System.getenv("RWX_RUN_WINDOWLESS_OPAQUE_POINT_UNION_ORACLE") == "1") {
            "Set RWX_RUN_WINDOWLESS_OPAQUE_POINT_UNION_ORACLE=1"
        }
        require(System.getenv("RWX_GPU_MAP_CELL_ORACLE_MSAA") == "4") { "This oracle requires production MSAA4" }
        // The candidate is toggled only on fresh recorder instances; all other proposed paths stay off.
        listOf("RWX_UNION_OPAQUE_POINTS", "RWX_PACKED_ROOT_TEXTURE_COMMANDS", "RWX_DIRECT_RECT_VERTEX_WRITES",
            "RWX_DIRECT_SPRITE_INSTANCE_WRITES", "RWX_LAZY_CANVAS_SIGNATURE", "RWX_REUSE_PAINT_SNAPSHOTS",
            "RWX_MAP_TEXTURE_BATCHES", "RWX_MAP_RECT_BATCHES", "RWX_MAP_FOG_BATCHES",
            "RWX_MAP_NODE_MODEL_UPDATES_ONLY", "RWX_RESET_ONLY_ACTIVE_CANVAS_MESHES",
            "RWX_AVOID_TEXTURE_PAINT_COPIES", "RWX_REUSE_INSTANCED_TEXTURE_SHADERS",
            "RWX_REUSE_MAP_MESH_SLOTS", "RWX_SHARE_MAP_TEXTURE_MATERIALS", "RWX_DIRECT_TARGET_TEXTURES").forEach {
            require(System.getenv(it) == "0") { "$it=0 is required for isolated instance OFF/ON comparison" }
        }
        runBlocking {
            val context = WindowlessKoolGpuOracle.createWindowlessContext(Vec2i(64, 64))
            val result = CompletableDeferred<Unit>()
            val deadline = System.nanoTime() + 60_000_000_000L
            context.onRender += {
                if (!result.isCompleted && System.nanoTime() >= deadline) {
                    result.completeExceptionally(IllegalStateException("Opaque-point union GPU oracle timed out"))
                    context.close()
                }
            }
            FrontendScope.launch {
                try {
                    withTimeout(55_000L) { verify(context) }
                    result.complete(Unit)
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    result.completeExceptionally(failure)
                } finally { context.close() }
            }
            try { context.run() }
            catch (failure: Throwable) { result.completeExceptionally(failure) }
            result.await()
        }
    }

    private suspend fun verify(context: Lwjgl3Context) {
        check(context.backend is RenderBackendVk)
        val ordinal = VulkanBackendMetrics.javaClass.getDeclaredField("frameOrdinal").apply { isAccessible = true }
        var cycles = 0L
        context.onRender += { cycles++ }
        var totalPairs = 0
        var totalPixels = 0L
        var acceptedCalls = 0L
        var acceptedInputPoints = 0L
        var emittedRects = 0L
        var actualFenceCheckpoints = 0L
        var retirementRequested = 0L
        var retirementCompleted = 0L

        for ((width, height) in listOf(64 to 64, 63 to 47)) {
            val store = KoolCanvasCpuTextureStore()
            val host = KoolCanvasSceneHost(KoolCanvasFrameRenderer(), "opaque-point-union-$width-$height")
            host.setGpuOffscreenPassesAvailable(true)
            host.setGpuRetirementSink { release ->
                retirementRequested++
                VulkanFrameLifecycle.retainUntilFrameComplete { retirementCompleted++; release() }
            }
            host.setGpuRetirementPendingCounter(VulkanFrameLifecycle::pendingRetirements)
            val scene = host.createScene()
            val pass = OffscreenPass2d(scene, AttachmentConfig {
                addColor(TexFormat.RGBA, ClearColorFill(Color(0f, 0f, 0f, 0f)))
                noDepth()
            }, Vec2i(width, height), "opaque-point-union-readback-$width-$height", numSamples = 4).apply {
                isReleaseDrawNode = false
                isMirrorY = false
            }
            check(pass.numSamples == 4) { "The actual readback pass must use MSAA4, not only the main swapchain" }
            // Download the original attachment with TRANSFER_SRC usage, as in the existing Sprite oracle.
            val copy = pass.copyOutput(isCopyColor = true, isCopyDepth = false)
            val output = checkNotNull(pass.colorTexture)
            scene.addOffscreenPass(pass)
            scene.onRenderScene += OnRenderScene {
                pass.camera = scene.camera
                pass.colorAttachments.single().clearColor = scene.clearColor
            }
            var borrowedResourcesReleased = false
            scene.onRelease {
                VulkanFrameLifecycle.retainUntilFrameComplete {
                    pass.impl.release()
                    copy.release()
                    output.release()
                    store.close()
                    KoolCanvasTextureRegistry.releaseRetiredTextures()
                    KoolCanvasTextureRegistry.releaseRetiredTextures()
                    borrowedResourcesReleased = true
                }
            }
            context.scenes += scene
            var sequence = 0L

            suspend fun checkpoint() {
                val completed = CompletableDeferred<Unit>()
                VulkanFrameLifecycle.retainUntilFrameComplete { completed.complete(Unit) }
                completed.await()
                actualFenceCheckpoints++
            }

            suspend fun image(packet: FrameEnvelope): IntArray {
                val expected = packet.frame
                val before = ordinal.getLong(VulkanBackendMetrics)
                host.submit(packet) // Transfers ownership to the ordinary mailbox and installer.
                while (host.currentFrame() !== expected) delay(5L)
                val until = cycles + 2
                while (cycles < until || output.gpuTexture == null || ordinal.getLong(VulkanBackendMetrics) <= before)
                    delay(5L)
                checkpoint() // A real successful native submission, then its last-use fence.
                val format = (output.gpuTexture as ImageVk).format
                check(format == VK_FORMAT_R8G8B8A8_UNORM || format == VK_FORMAT_B8G8R8A8_UNORM) {
                    "Unexpected readback image format: $format"
                }
                val data = output.download()
                check(data.width == width && data.height == height)
                val bytes = data.data as Uint8Buffer
                return IntArray(width * height * 4) { index ->
                    val channel = index % 4
                    val source = if (format == VK_FORMAT_B8G8R8A8_UNORM && channel < 3)
                        index - channel + 2 - channel else index
                    bytes[source].toInt()
                }
            }

            for (case in cases(width, height)) {
                val control = record(store, width, height, case, false)
                val candidate = record(store, width, height, case, true)
                check(control.counts == UnionCounts(0, 0, 0)) { "${case.label}: OFF accepted a union" }
                check(candidate.counts.calls == case.acceptedCalls &&
                    candidate.counts.inputPoints == case.acceptedInputPoints) {
                    "${case.label}: expected actual union calls=${case.acceptedCalls}/points=${case.acceptedInputPoints}, got ${candidate.counts}"
                }
                if (case.acceptedCalls > 0) {
                    check(candidate.counts.outputRects > 0 &&
                        candidate.counts.outputRects * 2 < candidate.counts.inputPoints &&
                        candidate.rects * 2 < control.rects) {
                        "${case.label}: candidate did not actually reduce the real recorded point rectangles"
                    }
                } else {
                    check(candidate.counts == UnionCounts(0, 0, 0) && control.frame == candidate.frame) {
                        "${case.label}: fallback changed the legacy recording"
                    }
                }
                val off = image(store.freezeFrame(control.frame, ++sequence, 1, 0, 0))
                val on = image(store.freezeFrame(candidate.frame, ++sequence, 1, 0, 0))
                check(off.indices.any { it % 4 == 3 && off[it] != 0 }) { "${case.label}: control rendered no visible pixels" }
                if (case.label == "two-team-order-with-blue-barrier") {
                    // These untouched interiors must survive all three ordered draws. An empty or
                    // single-color readback cannot accidentally certify the team/barrier order case.
                    for (rgb in listOf(intArrayOf(0, 255, 0), intArrayOf(0, 0, 255), intArrayOf(255, 0, 0))) {
                        check((0 until width * height).any { pixel ->
                            val at = pixel * 4
                            off[at] == rgb[0] && off[at + 1] == rgb[1] && off[at + 2] == rgb[2] && off[at + 3] == 255
                        }) { "${case.label}: a real team/barrier color did not render: ${rgb.toList()}" }
                    }
                }
                val mismatch = off.indices.firstOrNull { off[it] != on[it] }
                check(mismatch == null) {
                    val scalar = checkNotNull(mismatch)
                    "${case.label} ${width}x$height exact RGBA mismatch x=${scalar / 4 % width} " +
                        "y=${scalar / 4 / width} channel=${scalar % 4} off=${off[scalar]} on=${on[scalar]}"
                }
                totalPairs++
                totalPixels += width.toLong() * height
                acceptedCalls += candidate.counts.calls
                acceptedInputPoints += candidate.counts.inputPoints
                emittedRects += candidate.counts.outputRects
                println("RWXOpaquePointUnionGpuOracle case=${case.label} viewport=${width}x$height " +
                    "exactMaxDelta=0 rgbaPixels=${width * height} offRects=${control.rects} onRects=${candidate.rects} " +
                    "actualUnionCalls=${candidate.counts.calls} inputPoints=${candidate.counts.inputPoints} " +
                    "outputRects=${candidate.counts.outputRects} instanceControl=off-on readbackSamples=${pass.numSamples} " +
                    "frozen=true sceneHost=true fenced=true")
            }
            context.scenes -= scene
            var closed = false
            VulkanFrameLifecycle.retainUntilFrameComplete { scene.release(); closed = true }
            while (!closed || !borrowedResourcesReleased || retirementRequested != retirementCompleted) delay(5L)
        }
        check(totalPairs == 22 && acceptedCalls == 16L && acceptedInputPoints == 16L * POINTS)
        check(actualFenceCheckpoints == totalPairs * 2L)
        check(retirementRequested > 0 && retirementRequested == retirementCompleted)
        println("RWXOpaquePointUnionGpuOracle success=true pairs=$totalPairs rgbaPixels=$totalPixels exactMaxDelta=0 " +
            "actualUnionCalls=$acceptedCalls inputPoints=$acceptedInputPoints outputRects=$emittedRects " +
            "fenceCheckpoints=$actualFenceCheckpoints retirementsRequested=$retirementRequested " +
            "retirementsCompleted=$retirementCompleted actualFenceRelease=true msaa=4 " +
            "windowAdapter=memory headless=true productionRecorder=true frozen=true sceneHost=true")
    }
}
