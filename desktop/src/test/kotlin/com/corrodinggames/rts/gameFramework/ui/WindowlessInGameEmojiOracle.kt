package com.corrodinggames.rts.gameFramework.ui

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
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
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.Uint8Buffer
import io.github.rwx.DesktopEmojiRasterizer
import io.github.rwx.kool.vulkan.VulkanBackendMetrics
import io.github.rwx.kool.vulkan.VulkanFrameLifecycle
import io.github.rwx.kool.vulkan.WindowlessKoolGpuOracle
import io.github.rwx.render.canvas.*
import io.github.rwx.ui.emoji.EmojiRasterizerBridge
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.lwjgl.vulkan.VK10.*
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.system.exitProcess

/**
 * Explicit TEST main, not JUnit: real MessageManager recording and production Vulkan offscreen
 * rendering/readback. No map, network, display manager, OS screenshot, Swing/GLFW host or fallback.
 * The caller MUST enforce an outer 70-second process deadline; native waits are not cancellable.
 * This does not certify a complete multiplayer game window or delivery of a network chat message.
 */
object WindowlessInGameEmojiOracle {
    private const val WIDTH = 1200
    private const val HEIGHT = 160
    private const val SIZE = 48
    private const val BACKGROUND = -15460580 // 0xff14171c
    private val clusters = listOf("🍎", "🇨🇳", "🎮", "🔥", "⚔️", "🏆", "‼️", "↔️", "Ⓜ️")

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        var output: File? = null
        val report = linkedMapOf<String, JsonElement>()
        var failure: Throwable? = null
        try {
            require(System.getenv("RWX_RUN_WINDOWLESS_IN_GAME_EMOJI_ORACLE") == "1") { "Explicit emoji oracle opt-in is required" }
            require(System.getenv("RWX_RUN_WINDOWLESS_VULKAN_ORACLE") == "1") { "The audited windowless Vulkan opt-in is required" }
            require(System.getenv("RWX_GPU_MAP_CELL_ORACLE_MSAA") == "4") { "Production MSAA4 is required" }
            require(args.size == 2 && args.all { it.startsWith("--") && '=' in it }) { "Use --assets=ABSOLUTE_ASSETS --output=NEW_DIRECTORY" }
            val values = args.associate { it.substring(2).substringBefore('=') to it.substringAfter('=') }
            require(values.keys == setOf("assets", "output")) { "Only --assets and --output are supported" }
            val assets = File(checkNotNull(values["assets"])).canonicalFile
            require(assets.isDirectory && File(assets, "font/NotoSansCJKsc-Regular.json").isFile) { "Explicit complete real assets are required" }
            val destination = File(checkNotNull(values["output"])).canonicalFile
            destination.parentFile.mkdirs()
            Files.createDirectory(destination.toPath())
            output = destination
            check(GraphicsEnvironment.isHeadless())
            runBlocking {
                val context = WindowlessKoolGpuOracle.createWindowlessContext(Vec2i(WIDTH, HEIGHT), assets.path)
                val done = CompletableDeferred<Unit>()
                FrontendScope.launch {
                    try {
                        withTimeout(55_000L) { verify(context, destination, report) }
                        done.complete(Unit)
                    } catch (error: Throwable) {
                        done.completeExceptionally(error)
                    } finally { context.close() }
                }
                try { context.run() } catch (error: Throwable) { done.completeExceptionally(error) }
                done.await()
            }
        } catch (error: Throwable) {
            failure = error
            error.printStackTrace(System.err)
        }
        report["passed"] = JsonPrimitive(failure == null)
        report["scope"] = JsonPrimitive("Real MessageManager recording, frozen packet, production SceneHost and windowless Vulkan MSAA4 offscreen readback; not a complete multiplayer game window or network chat delivery test")
        report["headless"] = JsonPrimitive(true)
        report["windowAdapter"] = JsonPrimitive("memory/VK_EXT_headless_surface")
        report["osScreenshotUsed"] = JsonPrimitive(false)
        failure?.let { report["error"] = JsonPrimitive(it.stackTraceToString()) }
        output?.takeIf { it.isDirectory }?.resolve("result.json")?.writeText(JsonObject(report).toString() + "\n")
        println("RWXWindowlessInGameEmojiOracle passed=${failure == null} output=${output?.path}")
        exitProcess(if (failure == null) 0 else 1)
    }

    private data class Source(val cluster: String, val command: KoolCanvasCommand.DrawTexture,
        val width: Int, val height: Int, val pixels: IntArray)

    private suspend fun verify(context: Lwjgl3Context, output: File, report: MutableMap<String, JsonElement>) {
        val backend = context.backend as? RenderBackendVk ?: error("A real Vulkan backend is required")
        val oldFont = KoolCanvasFontRegistry.base
        val oldRasterizer = EmojiRasterizerBridge.currentRasterizer()
        val font = MsdfFont("font/NotoSansCJKsc-Regular").getOrThrow()
        val rasterizer = DesktopEmojiRasterizer()
        val store = KoolCanvasCpuTextureStore()
        val renderer = KoolCanvasFrameRenderer()
        val host = KoolCanvasSceneHost(renderer, "windowless-message-manager-emoji")
        var retirementRequested = 0
        var retirementCompleted = 0
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
        }, Vec2i(WIDTH, HEIGHT), "windowless-emoji-gpu-readback", numSamples = 4).apply {
            isReleaseDrawNode = false
            isMirrorY = false
        }
        check(pass.numSamples == 4)
        val copy = pass.copyOutput(isCopyColor = true, isCopyDepth = false)
        val attachment = checkNotNull(pass.colorTexture)
        scene.addOffscreenPass(pass)
        scene.onRenderScene += OnRenderScene {
            pass.camera = scene.camera
            pass.colorAttachments.single().clearColor = scene.clearColor
        }
        val ordinal = VulkanBackendMetrics.javaClass.getDeclaredField("frameOrdinal").apply { isAccessible = true }
        var cycles = 0L
        context.onRender += { cycles++ }
        var packet: FrameEnvelope? = null
        var submitted = false
        var retained: FrameEnvelope? = null
        var pendingFailure: Throwable? = null
        try {
            KoolCanvasFontRegistry.installBaseFont(font)
            EmojiRasterizerBridge.install(rasterizer)
            val graphics = KoolGraphicsEngine(textureStore = store)
            graphics.beginFrame(WIDTH, HEIGHT)
            graphics.a(BACKGROUND, KoolCanvasBlendMode.Source)
            withMessageManager(graphics) { manager ->
                manager.textPaint.b(SIZE.toFloat())
                manager.addMessage("Player🍎", "chat 🇨🇳 🎮 🔥 ⚔️ 🏆 ‼️ ↔️ Ⓜ️")
                manager.draw(0f, 105)
            }
            val frame = graphics.snapshot()
            val commands = frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>()
            check(commands.size == clusters.size) { "Expected nine real emoji images, got ${commands.size}" }
            check(frame.commands.filterIsInstance<KoolCanvasCommand.DrawText>().joinToString("") { it.text }.startsWith("Player"))
            val sourceDiagnostics = mutableListOf<JsonObject>()
            val sources = commands.zip(clusters).map { (command, cluster) ->
                val rasterSize = command.paint.textSize.roundToInt().coerceIn(12, 96)
                val original = checkNotNull(rasterizer.rasterize(cluster, rasterSize)) { "Real platform rasterizer returned no $cluster" }
                val image = checkNotNull(store.argbImageView(command.texture.id))
                val pixels = IntArray(original.width * original.height) { pixel ->
                    val at = pixel * 4
                    ((original.rgba[at + 3].toInt() and 255) shl 24) or
                        ((original.rgba[at].toInt() and 255) shl 16) or
                        ((original.rgba[at + 1].toInt() and 255) shl 8) or (original.rgba[at + 2].toInt() and 255)
                }
                val common = 0 until min(image.pixels.size, pixels.size)
                val alphaMismatches = common.count { image.pixels[it] ushr 24 != pixels[it] ushr 24 }
                val visibleMismatches = common.count { pixels[it] ushr 24 != 0 && image.pixels[it] != pixels[it] }
                val transparentRgbAdjustments = common.count { pixels[it] ushr 24 == 0 && image.pixels[it] != pixels[it] }
                val firstDifference = common.firstOrNull { image.pixels[it] != pixels[it] }
                sourceDiagnostics += buildJsonObject {
                    put("cluster", cluster); put("commandPaintTextSize", command.paint.textSize); put("rasterRequestedSize", rasterSize)
                    put("textureWidth", command.texture.width); put("textureHeight", command.texture.height)
                    put("registeredWidth", image.width); put("registeredHeight", image.height)
                    put("independentWidth", original.width); put("independentHeight", original.height)
                    put("destinationLeft", command.destination.left); put("destinationTop", command.destination.top)
                    put("destinationWidth", command.destination.width); put("destinationHeight", command.destination.height)
                    put("alphaMismatches", alphaMismatches); put("visiblePixelMismatches", visibleMismatches)
                    put("transparentRgbAdjustments", transparentRgbAdjustments)
                    put("firstPixelDelta", firstDifference?.let { index -> buildJsonObject {
                        put("index", index); put("x", index % original.width); put("y", index / original.width)
                        put("independentArgb", "0x" + pixels[index].toUInt().toString(16).padStart(8, '0'))
                        put("registeredArgb", "0x" + image.pixels[index].toUInt().toString(16).padStart(8, '0'))
                    } } ?: JsonNull)
                }
                report["sourceRasterDiagnostics"] = JsonArray(sourceDiagnostics.toList())
                // registerArgb deliberately bleeds neighboring RGB into alpha-zero texels for
                // linear filtering. Every alpha and every nontransparent color must remain exact;
                // only invisible RGB may differ. GPU opaque-point checks below still use the
                // independent original raster, never the modified registration as their oracle.
                check(image.width == original.width && image.height == original.height &&
                    image.pixels.size == pixels.size && alphaMismatches == 0 && visibleMismatches == 0) {
                    "MessageManager's visible texture differs from independent $cluster: " + sourceDiagnostics.last()
                }
                check(command.paint.color.argb == -1 && command.paint.alphaMultiplier == 1f)
                check(command.destination.left >= 0 && command.destination.right <= WIDTH &&
                    command.destination.top >= 0 && command.destination.bottom <= HEIGHT)
                Source(cluster, command, original.width, original.height, pixels)
            }
            val frozen = store.freezeFrame(frame, 1, 1, 0, 0)
            packet = frozen
            retained = frozen.retain()
            check(frozen.resourceLease.resourceCount == clusters.size)
            commands.forEach { store.unregister(it.texture.id) }
            check(commands.all { store.argbImageView(it.texture.id) == null })
            context.scenes += scene
            val before = ordinal.getLong(VulkanBackendMetrics)
            host.submit(frozen)
            submitted = true
            while (host.currentFrame() !== frozen.frame) delay(5)
            val after = cycles + 2
            while (cycles < after || attachment.gpuTexture == null || ordinal.getLong(VulkanBackendMetrics) <= before) delay(5)
            val checkpoint = CompletableDeferred<Unit>()
            VulkanFrameLifecycle.retainUntilFrameComplete { checkpoint.complete(Unit) }
            checkpoint.await()
            val format = (attachment.gpuTexture as ImageVk).format
            check(format == VK_FORMAT_R8G8B8A8_UNORM || format == VK_FORMAT_B8G8R8A8_UNORM)
            val data = attachment.download()
            check(data.width == WIDTH && data.height == HEIGHT)
            val bytes = data.data as Uint8Buffer
            val pixels = IntArray(WIDTH * HEIGHT) { pixel ->
                val at = pixel * 4
                val red = bytes[at + if (format == VK_FORMAT_B8G8R8A8_UNORM) 2 else 0].toInt() and 255
                val green = bytes[at + 1].toInt() and 255
                val blue = bytes[at + if (format == VK_FORMAT_B8G8R8A8_UNORM) 0 else 2].toInt() and 255
                ((bytes[at + 3].toInt() and 255) shl 24) or (red shl 16) or (green shl 8) or blue
            }
            val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH)
            val png = output.resolve("gpu-chat.png")
            check(ImageIO.write(image, "png", png))
            val rows = sources.map { verifySource(it, pixels) }
            report["emoji"] = JsonArray(rows)
            report["emojiCount"] = JsonPrimitive(sources.size)
            report["image"] = JsonPrimitive(png.absolutePath)
            report["imageSha256"] = JsonPrimitive(sha256(png.readBytes()))
            report["backend"] = JsonPrimitive("Vulkan")
            report["msaa"] = JsonPrimitive(pass.numSamples)
            report["realSubmissionBeforeReadback"] = JsonPrimitive(ordinal.getLong(VulkanBackendMetrics) > before)
            report["realFenceCheckpoint"] = JsonPrimitive(true)
            report["sourceEntriesRemovedBeforeUpload"] = JsonPrimitive(true)
            report["frozenResourceCount"] = JsonPrimitive(frozen.resourceLease.resourceCount)
        } catch (error: Throwable) {
            pendingFailure = error
        } finally {
            try {
                withContext(NonCancellable) {
                    // Remove the scene and let BufferedList stop selecting it before a real idle.
                    context.scenes -= scene
                    val removed = cycles + 2
                    withTimeout(8_000L) { while (cycles < removed) delay(5) }
                    VulkanFrameLifecycle.checkedDeviceIdle(vkDeviceWaitIdle(backend.device.vkDevice))
                    scene.release()
                    retained?.close()
                    if (!submitted) packet?.close()
                    // This standalone test owns the root renderer. Scene/packet teardown releases
                    // leases and meshes, but not its private sprite atlas. Invoke that owner's
                    // existing clear operation only after real idle, without changing production.
                    val atlas = KoolCanvasFrameRenderer::class.java.getDeclaredField("spriteAtlas")
                        .apply { isAccessible = true }.get(renderer)
                    atlas.javaClass.declaredMethods.single {
                        it.name.substringBefore('$') == "clear" && it.parameterCount == 0
                    }.apply { isAccessible = true }.invoke(atlas)
                    report["rendererSpriteAtlasExplicitlyCleared"] = JsonPrimitive(true)
                    // Scene release can queue nested frame/native retirements. Each flush is backed
                    // by a successful real idle; no old GPU reader is declared complete by fiat.
                    repeat(8) {
                        VulkanFrameLifecycle.checkedDeviceIdle(vkDeviceWaitIdle(backend.device.vkDevice))
                        VulkanFrameLifecycle.deviceIdle(backend, destroying = false)
                        KoolCanvasTextureRegistry.releaseRetiredTextures()
                    }
                    pass.impl.release(); copy.release(); attachment.release()
                    store.close()
                    KoolCanvasFontRegistry.installBaseFont(oldFont)
                    font.data.map.release()
                    val releasePump = cycles + 6
                    withTimeout(8_000L) { while (cycles < releasePump) delay(5) }
                    VulkanFrameLifecycle.checkedDeviceIdle(vkDeviceWaitIdle(backend.device.vkDevice))
                    VulkanFrameLifecycle.deviceIdle(backend, destroying = false)
                    KoolCanvasTextureRegistry.releaseRetiredTextures()
                    check(VulkanFrameLifecycle.pendingRetirements() == 0)
                    check(packet?.resourceLease?.isReleased != false)
                    check(retirementRequested == retirementCompleted)
                    report["frameLeaseReleased"] = JsonPrimitive(true)
                    report["retirementsRequested"] = JsonPrimitive(retirementRequested)
                    report["retirementsCompleted"] = JsonPrimitive(retirementCompleted)
                    report["pendingRetirements"] = JsonPrimitive(0)
                    report["ownedFontReleased"] = JsonPrimitive(true)
                    report["delayedReleaseFramesPumped"] = JsonPrimitive(6)
                }
            } catch (cleanup: Throwable) {
                val previous = pendingFailure
                if (previous == null) pendingFailure = cleanup else if (previous !== cleanup) previous.addSuppressed(cleanup)
            } finally {
                EmojiRasterizerBridge.uninstall(rasterizer)
                oldRasterizer?.let(EmojiRasterizerBridge::install)
            }
        }
        pendingFailure?.let { throw it }
    }

    private fun verifySource(source: Source, gpu: IntArray): JsonObject {
        val rect = source.command.destination
        val sourceColorPixels = source.pixels.count { pixel ->
            val rgb = intArrayOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255)
            pixel ushr 24 > 16 && rgb.max() - rgb.min() > 24
        }
        var visible = 0
        var colorful = 0
        var compared = 0
        var maxDelta = 0
        for (y in ceil(rect.top + 2).toInt() until floor(rect.bottom - 2).toInt()) {
            for (x in ceil(rect.left + 2).toInt() until floor(rect.right - 2).toInt()) {
                val actual = gpu[y * WIDTH + x]
                if (rgbDelta(actual, BACKGROUND) > 20) visible++
                val rgb = intArrayOf(actual ushr 16 and 255, actual ushr 8 and 255, actual and 255)
                if (rgb.max() - rgb.min() > 24 && rgbDelta(actual, BACKGROUND) > 24) colorful++
                val sx = ((x + .5f - rect.left) / rect.width * source.width - .5f).coerceIn(0f, source.width - 1f)
                val sy = ((y + .5f - rect.top) / rect.height * source.height - .5f).coerceIn(0f, source.height - 1f)
                val left = floor(sx).toInt(); val top = floor(sy).toInt()
                val neighbors = intArrayOf(source.pixels[top * source.width + left],
                    source.pixels[top * source.width + min(left + 1, source.width - 1)],
                    source.pixels[min(top + 1, source.height - 1) * source.width + left],
                    source.pixels[min(top + 1, source.height - 1) * source.width + min(left + 1, source.width - 1)])
                if (neighbors.any { it ushr 24 < 250 }) continue
                val fx = sx - left; val fy = sy - top
                fun sample(shift: Int): Float {
                    val c = neighbors.map { (it ushr shift and 255).toFloat() }
                    return (c[0] * (1 - fx) + c[1] * fx) * (1 - fy) + (c[2] * (1 - fx) + c[3] * fx) * fy
                }
                val alpha = sample(24) / 255f
                var delta = 0
                for (shift in listOf(16, 8, 0)) {
                    val expected = sample(shift) * alpha + (BACKGROUND ushr shift and 255) * (1 - alpha)
                    delta = max(delta, abs((actual ushr shift and 255) - expected.roundToInt()))
                }
                maxDelta = max(maxDelta, delta)
                compared++
            }
        }
        check(visible > 20 && (sourceColorPixels == 0 || colorful > 0)) {
            "${source.cluster}: GPU ROI is blank or lost source color: visible=$visible sourceColor=$sourceColorPixels gpuColor=$colorful"
        }
        if (source.cluster == "🍎" || source.cluster == "🇨🇳") {
            check(sourceColorPixels > 20 && colorful > 20) {
                "${source.cluster}: colored anchor became monochrome: sourceColor=$sourceColorPixels gpuColor=$colorful"
            }
        }
        check(compared >= 8 && maxDelta <= 16) {
            "${source.cluster}: GPU differs from original opaque raster points: samples=$compared maxRgbDelta=$maxDelta"
        }
        return buildJsonObject {
            put("cluster", source.cluster); put("sourceWidth", source.width); put("sourceHeight", source.height)
            put("left", rect.left); put("top", rect.top); put("right", rect.right); put("bottom", rect.bottom)
            put("visibleGpuPixels", visible); put("colorfulGpuPixels", colorful)
            put("sourceColorPixels", sourceColorPixels); put("gpuColorPixels", colorful)
            put("opaqueRasterSamplesCompared", compared); put("maximumRgbDelta", maxDelta); put("allowedRgbDelta", 16)
        }
    }

    private fun rgbDelta(a: Int, b: Int): Int = listOf(16, 8, 0).maxOf { shift -> abs((a ushr shift and 255) - (b ushr shift and 255)) }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun withMessageManager(graphics: KoolGraphicsEngine, block: (MessageManager) -> Unit) {
        val singleton = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        singleton.set(null, null)
        try {
            val engine = GameLogic()
            engine.screenScale = 1f
            engine.gameUI = GameUI()
            engine.settingsEngine = SettingsEngine::class.java.getDeclaredConstructor().run { isAccessible = true; newInstance() }
            engine.settingsEngine.showPlayerChatInGame = true
            engine.renderGraphicsEngine = graphics
            block(MessageManager(engine, engine.gameUI))
        } finally { singleton.set(null, previous) }
    }
}
