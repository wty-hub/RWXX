package io.github.rwx.kool.vulkan

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.createContext
import de.fabmax.kool.math.Vec2f
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.pipeline.backend.vk.ImageVk
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.scene.geometry.IndexedVertexList
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.FrontendScope
import de.fabmax.kool.util.Uint8Buffer
import io.github.rwx.render.canvas.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Explicit functional GPU check. Readback waits for the queue, so this never runs in performance tests. */
object VulkanBgraSamplingOracle {
    @JvmStatic
    fun main(args: Array<String>) {
        require(System.getenv("RWX_RUN_VULKAN_BGRA_ORACLE") == "1") {
            "Set RWX_RUN_VULKAN_BGRA_ORACLE=1 to run the standalone Vulkan sampling check"
        }
        runBlocking {
            val context = createContext(KoolConfigJvm(
                windowTitle = "RWX Vulkan BGRA sampling oracle", windowSize = Vec2i(64, 64),
                renderBackend = RenderBackendVk, useOpenGlFallback = false, showWindowOnStart = false,
                asyncSceneUpdate = false, numSamples = 4, isVsync = false, maxFrameRate = 60,
            )) as Lwjgl3Context
            val result = CompletableDeferred<Unit>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            context.onRender += {
                if (!result.isCompleted && System.nanoTime() >= deadline) {
                    result.completeExceptionally(IllegalStateException("Vulkan BGRA sampling check timed out"))
                    context.close()
                }
            }
            FrontendScope.launch {
                try {
                    withTimeout(25_000L) { verifySampling(context) }
                    result.complete(Unit)
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                } finally {
                    context.close()
                }
            }
            context.run()
            result.await()
        }
    }

    private suspend fun verifySampling(context: Lwjgl3Context) {
        check(context.backend is RenderBackendVk)
        check(KoolCanvasTextureRegistry.nativeBgraUploadsEnabled) {
            "Selected device did not enable native BGRA uploads; remove the legacy optout for this check"
        }
        val source = intArrayOf(0xff123456.toInt(), 0x80102030.toInt(), 0x00010203, 0x01aa3377)
        val id = KoolCanvasTextureId("vulkan-bgra-sampling-oracle")
        KoolCanvasTextureRegistry.registerArgb(id, source.size, 1, source, alphaBleed = false)
        var checks = 0
        try {
            for (filter in listOf(KoolCanvasTextureFilter.Nearest, KoolCanvasTextureFilter.Linear)) {
                val candidate = KoolCanvasTextureRegistry.resolve(KoolCanvasTextureRef(id, source.size, 1), filter)
                check(candidate.uploadData is KoolCanvasBgraImageData)
                val reference = rgbaReference(source, filter)
                try {
                    val samples = if (filter == KoolCanvasTextureFilter.Nearest) {
                        source.indices.map { ((it + 0.5f) / source.size) to channels(source[it]) }
                    } else {
                        (0 until source.lastIndex).map { index ->
                            val left = channels(source[index])
                            val right = channels(source[index + 1])
                            ((index + 1f) / source.size) to IntArray(4) { ((left[it] + right[it]) / 2f).roundToInt() }
                        }
                    }
                    for ((u, expected) in samples) {
                        val bgra = sample(context, candidate, u)
                        val rgba = sample(context, reference, u)
                        check((candidate.gpuTexture as ImageVk).format == VK_FORMAT_B8G8R8A8_UNORM)
                        for (channel in 0..3) {
                            check(abs(bgra[channel] - expected[channel]) <= 1) {
                                "$filter u=$u channel=$channel: BGRA ${bgra[channel]} != oracle ${expected[channel]}"
                            }
                            check(abs(bgra[channel] - rgba[channel]) <= 1) {
                                "$filter u=$u channel=$channel: BGRA ${bgra[channel]} != RGBA ${rgba[channel]}"
                            }
                        }
                        checks++
                    }
                } finally {
                    reference.release()
                }
            }
            println("RWXVulkanBgraOracle success checks=$checks nearest=4 linear=3 alpha=0,1,128,255")
        } finally {
            KoolCanvasTextureRegistry.unregister(id)
            // The final RGBA-reference readback waited for all earlier BGRA sampling to finish.
            // This standalone harness has no SceneHost to advance the registry's two release lists.
            KoolCanvasTextureRegistry.releaseRetiredTextures()
            KoolCanvasTextureRegistry.releaseRetiredTextures()
        }
    }

    private fun rgbaReference(source: IntArray, filter: KoolCanvasTextureFilter): Texture2d {
        val pixels = Uint8Buffer(source.size * 4)
        source.forEach { argb -> channels(argb).forEach { pixels.put(it.toByte()) } }
        return Texture2d(
            BufferedImageData2d(pixels, source.size, 1, TexFormat.RGBA, "vulkan-oracle-rgba-$filter"),
            MipMapping.Off,
            SamplerSettings().clamped().noAnisotropy().let {
                if (filter == KoolCanvasTextureFilter.Nearest) it.nearest() else it.linear()
            },
            "vulkan-oracle-reference-$filter",
        )
    }

    private suspend fun sample(context: Lwjgl3Context, texture: Texture2d, u: Float): IntArray {
        val geometry = IndexedVertexList(VertexLayouts.PositionNormalTexCoordColor)
        val uv = Vec2f(u, 0.5f)
        geometry.addVertex(Vec3f(-1f, -1f, 0f), color = Color.WHITE, texCoord = uv)
        geometry.addVertex(Vec3f(1f, -1f, 0f), color = Color.WHITE, texCoord = uv)
        geometry.addVertex(Vec3f(1f, 1f, 0f), color = Color.WHITE, texCoord = uv)
        geometry.addVertex(Vec3f(-1f, 1f, 0f), color = Color.WHITE, texCoord = uv)
        geometry.addIndices(0, 1, 2, 0, 2, 3)
        val mesh = Mesh(geometry, name = "bgra-oracle-quad").apply {
            shader = KoolCanvasTextureShader(PipelineConfig(blendMode = BlendMode.DISABLED,
                cullMethod = CullMethod.NO_CULLING, depthTest = DepthCompareOp.ALWAYS, isWriteDepth = false))
                .apply { colorMap = texture }
        }
        val pass = OffscreenPass2d(Node("bgra-oracle-root").apply { addNode(mesh) },
            AttachmentConfig.singleColorNoDepth(TexFormat.RGBA), Vec2i(1, 1), "bgra-oracle-pass").apply {
            camera = OrthographicCamera().apply {
                left = -1f; right = 1f; bottom = -1f; top = 1f
                isKeepAspectRatio = false
                setupCamera(position = Vec3f(0f, 0f, 10f), lookAt = Vec3f.ZERO)
            }
        }
        // A persistent frame copy makes the RGBA attachment a TRANSFER_SRC without changing
        // the production BGRA texture's strict mipmap-off, sampled-only upload usage.
        val copy = pass.copyOutput(isCopyColor = true, isCopyDepth = false)
        context.addBackgroundRenderPass(pass)
        try {
            val output = checkNotNull(pass.colorTexture)
            while (output.gpuTexture == null) delay(10L)
            val image = output.download()
            val bytes = image.data as Uint8Buffer
            return IntArray(4) { bytes[it].toInt() }
        } finally {
            context.removeBackgroundRenderPass(pass)
            pass.release()
            copy.release()
        }
    }

    private fun channels(argb: Int): IntArray =
        intArrayOf((argb ushr 16) and 255, (argb ushr 8) and 255, argb and 255, (argb ushr 24) and 255)
}
