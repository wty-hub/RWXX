package io.github.rwx.kool.vulkan

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.createContext
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.pipeline.backend.vk.ImageVk
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.OnRenderScene
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.FrontendScope
import de.fabmax.kool.util.SyncedScope
import de.fabmax.kool.util.Uint8Buffer
import io.github.rwx.render.canvas.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VK10.vkWaitForFences
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/** Standalone native lifetime / readback check, never used in performance measurement. */
object VulkanSpriteAtlasOracle {
    @JvmStatic fun main(args: Array<String>) {
        require(System.getenv("RWX_RUN_VULKAN_SPRITE_ATLAS_ORACLE") == "1") {
            "Set RWX_RUN_VULKAN_SPRITE_ATLAS_ORACLE=1 to run this standalone check"
        }
        runBlocking {
            val context = createContext(KoolConfigJvm(windowTitle = "RWX Vulkan Sprite Atlas oracle",
                windowSize = Vec2i(64, 64), renderBackend = RenderBackendVk, useOpenGlFallback = false,
                showWindowOnStart = false, asyncSceneUpdate = false, numSamples = 4,
                isVsync = false, maxFrameRate = 60)) as Lwjgl3Context
            val result = CompletableDeferred<Unit>()
            val deadline = System.nanoTime() + 120_000_000_000L
            context.onRender += {
                if (!result.isCompleted && System.nanoTime() >= deadline) {
                    result.completeExceptionally(IllegalStateException("Sprite Atlas oracle timed out"))
                    context.close()
                }
            }
            FrontendScope.launch {
                try { withTimeout(110_000L) { verify(context) }; result.complete(Unit) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
                finally { context.close() }
            }
            try { context.run() }
            catch (failure: Throwable) { result.completeExceptionally(failure) }
            result.await()
        }
    }

    private suspend fun verify(context: Lwjgl3Context) {
        check(context.backend is RenderBackendVk)
        val store = KoolCanvasCpuTextureStore()
        // This is the ordinary root renderer. No child map renderer or custom Atlas is used.
        val renderer = KoolCanvasFrameRenderer()
        val host = KoolCanvasSceneHost(renderer, "sprite-atlas-oracle")
        var requestedRetirements = 0L
        var completedRetirements = 0L
        // Only the explicit standalone seam case delays already fence-safe retirement until
        // Synced (after collect, before capture). Normal oracle and production keep their sink.
        val pipelineSeam = System.getenv("RWX_RUN_VULKAN_SPRITE_ATLAS_PIPELINE_SEAM") == "1"
        var holdRetirements = false
        val heldRetirements = ArrayList<() -> Unit>()
        host.setGpuRetirementSink { release ->
            requestedRetirements++
            val countedRelease = {
                completedRetirements++
                release()
            }
            if (holdRetirements) heldRetirements += countedRelease
            else VulkanFrameLifecycle.retainUntilFrameComplete(countedRelease)
        }
        val scene = host.createScene()
        val readbackPass = OffscreenPass2d(scene, AttachmentConfig {
            addColor(TexFormat.RGBA, ClearColorFill(Color(0f, 0f, 0f, 0f)))
            noDepth()
        }, Vec2i(64, 64), "sprite-atlas-oracle-compositor").apply {
            isReleaseDrawNode = false
            isMirrorY = false
        }
        // Download the ORIGINAL attachment. The persistent copy adds TRANSFER_SRC usage;
        // the copy destination has only TRANSFER_DST and is not downloaded.
        val copy = readbackPass.copyOutput(isCopyColor = true, isCopyDepth = false)
        val output = checkNotNull(readbackPass.colorTexture)
        scene.addOffscreenPass(readbackPass)
        scene.onRenderScene += OnRenderScene {
            readbackPass.camera = scene.camera
            readbackPass.colorAttachments.single().clearColor = scene.clearColor
        }
        context.scenes += scene
        var cycles = 0L
        context.onRender += { cycles++ }
        var sequence = 0L
        var checks = 0
        var stage = "startup"
        val meshPipelineField = Mesh::class.java.getDeclaredField("pipeline").apply { isAccessible = true }
        val observedTextures = Collections.newSetFromMap(IdentityHashMap<Texture2d, Boolean>())
        val observedImages = Collections.newSetFromMap(IdentityHashMap<ImageVk, Boolean>())
        val observedPipelines = Collections.newSetFromMap(IdentityHashMap<DrawPipeline, Boolean>())
        fun sprites() = scene.children.filterIsInstance<Mesh<*>>()
            .filter { it.isVisible && it.name.startsWith("rwx-canvas-sprites-") }
            .sortedBy { it.drawGroupId }
        fun checkBindings(label: String) {
            for (mesh in sprites()) {
                val shader = checkNotNull(mesh.shader)
                val pipeline = meshPipelineField.get(mesh) as? DrawPipeline
                val created = shader.createdPipeline
                if (pipeline == null || pipeline !== created || pipeline.isReleased) {
                    println("RWXVulkanSpriteAtlasOracle bindingFailure stage=$stage label=$label mesh=${mesh.name} " +
                        "meshPipeline=${System.identityHashCode(pipeline)} shaderPipeline=${System.identityHashCode(created)} " +
                        "meshPipelineReleased=${pipeline?.isReleased} shaderPipelineReleased=${created?.isReleased}")
                }
                check(pipeline != null && pipeline === created && !pipeline.isReleased) {
                    "$stage $label: Sprite Mesh / Shader pipeline identity mismatch: ${mesh.name}"
                }
                val front = shader.texture2d("spriteMap").get()
                val captured = pipeline.capturedPipelineData.bufferedBindings
                    .filterIsInstance<BindGroupData.Texture2dBindingData>().single().texture
                val native = captured?.gpuTexture as? ImageVk
                if (captured == null || front !== captured || captured.isReleased || native?.isReleased != false) {
                    println("RWXVulkanSpriteAtlasOracle bindingFailure stage=$stage label=$label mesh=${mesh.name} " +
                        "pipeline=${System.identityHashCode(pipeline)} capturedGroup=${System.identityHashCode(pipeline.capturedPipelineData)} " +
                        "front=${front?.name} frontIdentity=${System.identityHashCode(front)} " +
                        "captured=${captured?.name} capturedIdentity=${System.identityHashCode(captured)} " +
                        "textureReleased=${captured?.isReleased} image=$native imageReleased=${native?.isReleased}")
                }
                check(captured != null && front === captured && !captured.isReleased && native?.isReleased == false) {
                    "$stage $label: Captured Sprite Atlas texture is stale or released: ${mesh.name}"
                }
                check(captured.name.startsWith("rwx-sprite-atlas-")) { "Sprite bypassed the real Atlas" }
                observedTextures += captured
                observedImages += checkNotNull(native)
                observedPipelines += pipeline
            }
        }
        // Runs in native encode after successful acquire + fence callbacks, before actual binds.
        // The check and failure diagnostics are Oracle-local and do not mutate production data.
        readbackPass.defaultView.onSetupView { checkBindings("before-readback-bind") }
        scene.mainRenderPass.defaultView.onSetupView { checkBindings("before-screen-bind") }

        suspend fun waitCycles(count: Int = 2) {
            val until = cycles + count
            while (cycles < until || output.gpuTexture == null) delay(5L)
        }
        suspend fun awaitState(label: String, predicate: () -> Boolean) {
            val until = cycles + 180
            while (!predicate() && cycles < until) delay(5L)
            check(predicate()) { "$stage: $label did not finish within 180 native render cycles" }
        }
        suspend fun presentEnvelope(packet: FrameEnvelope) {
            val expected = packet.frame
            host.submit(packet)
            while (host.currentFrame() !== expected) delay(5L)
            waitCycles()
            checkBindings("after-presentation")
        }
        fun frame(commands: List<KoolCanvasCommand> = emptyList()) = KoolCanvasFrame(KoolCanvasViewport(64, 64),
            listOf(KoolCanvasCommand.Clear(KoolCanvasColor(0xff000000.toInt()))) + commands)
        suspend fun present(commands: List<KoolCanvasCommand> = emptyList()) {
            presentEnvelope(store.freezeFrame(frame(commands), ++sequence, 1, 0, 0))
        }
        suspend fun image(): IntArray {
            val format = (output.gpuTexture as ImageVk).format
            val data = output.download()
            check(data.width == 64 && data.height == 64)
            val bytes = data.data as Uint8Buffer
            return IntArray(64 * 64 * 4) { index ->
                val channel = index % 4
                val source = if (format == VK_FORMAT_B8G8R8A8_UNORM && channel < 3)
                    index - channel + 2 - channel else index
                bytes[source].toInt()
            }
        }
        fun assertPixel(data: IntArray, x: Int, y: Int, rgba: IntArray, label: String) {
            val actual = IntArray(4) { data[(y * 64 + x) * 4 + it] }
            check((0..3).all { abs(actual[it] - rgba[it]) <= 3 }) {
                "$stage $label ($x,$y): actual=${actual.toList()} expected=${rgba.toList()}"
            }
            checks++
        }
        fun register(id: String, width: Int = 2, height: Int = 2, pixels: IntArray): KoolCanvasTextureId {
            val key = KoolCanvasTextureId(id)
            // Normal detached static image path: the packet installs these immutable pixels
            // with isStatic=true. No Oracle-owned Texture2d is injected into the Atlas.
            store.registerArgb(key, width, height, pixels, true)
            return key
        }
        fun solid(id: String, argb: Int, size: Int = 2) = register(id, size, size, IntArray(size * size) { argb })
        fun sprite(id: KoolCanvasTextureId, filter: KoolCanvasTextureFilter = KoolCanvasTextureFilter.Nearest,
                   dest: KoolCanvasRect = KoolCanvasRect(0f, 0f, 16f, 16f), size: Int = 2) =
            KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, size, size, hasAlpha = true),
                KoolCanvasRect.fromSize(size.toFloat(), size.toFloat()), dest,
                KoolCanvasPaint(textureFilter = filter), KoolCanvasState.Default)
        fun barrier() = KoolCanvasCommand.DrawRect(KoolCanvasRect(0f, 0f, 4f, 4f),
            KoolCanvasPaint(color = KoolCanvasColor(0x800000ff.toInt())), KoolCanvasState.Default)
        val red = solid("atlas-oracle-red", 0xffff0000.toInt())
        val green = solid("atlas-oracle-green", 0xff00ff00.toInt())
        val pattern = register("atlas-oracle-filter-pattern", pixels =
            intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt()))
        fun shared(filter: KoolCanvasTextureFilter = KoolCanvasTextureFilter.Nearest) = listOf(
            sprite(red, filter), barrier(), sprite(green, filter, KoolCanvasRect(4f, 0f, 12f, 12f)))
        fun withFilters() = shared() + listOf(
            sprite(pattern, KoolCanvasTextureFilter.Nearest, KoolCanvasRect(32f, 0f, 40f, 8f)),
            sprite(pattern, KoolCanvasTextureFilter.Linear, KoolCanvasRect(40f, 0f, 48f, 8f)))

        // Keep all resources alive until a completed native last-use fence after detachment.
        // The offscreen pass borrows Scene; Scene.release must not release it a second time.
        scene.onRelease { VulkanFrameLifecycle.retainUntilFrameComplete {
            readbackPass.impl.release()
            copy.release()
            output.release()
            store.close()
            KoolCanvasTextureRegistry.releaseRetiredTextures()
            KoolCanvasTextureRegistry.releaseRetiredTextures()
        } }

        stage = "shared-material"
        val seamLinear = sprite(red, KoolCanvasTextureFilter.Linear, KoolCanvasRect(16f, 0f, 32f, 16f))
        val seamNear = sprite(green, KoolCanvasTextureFilter.Nearest)
        val initial = store.freezeFrame(frame(if (pipelineSeam) listOf(seamLinear, seamNear) else shared()), ++sequence, 1, 0, 0)
        val retainedInitial = initial.retain()
        try {
            presentEnvelope(initial)
            if (pipelineSeam) {
                stage = "uncaptured-shared-pipeline-retirement"
                val oldNearMesh = sprites().single { it.shader!!.texture2d("spriteMap").get()!!.name.endsWith("-Nearest") }
                val sharedShader = oldNearMesh.shader!!
                val sharedPipeline = meshPipelineField.get(oldNearMesh) as DrawPipeline
                check(!sharedPipeline.isReleased)
                // Near A remains an old captured user. The Linear material keeps this page
                // alive, while precisely 60 new renderer binds make A eligible on the next.
                repeat(60) { present(listOf(seamLinear)) }
                check(!oldNearMesh.isReleased && !sharedPipeline.isReleased)
                val packet = store.freezeFrame(frame(listOf(seamNear, seamLinear)), ++sequence, 1, 0, 0)
                val expected = packet.frame
                val seamFinished = CompletableDeferred<Unit>()
                holdRetirements = true
                host.submit(packet)
                SyncedScope.launch {
                    try {
                        check(host.currentFrame() === expected) { "Critical envelope was not collected before Synced" }
                        val newNearMesh = sprites().single { it.shader === sharedShader }
                        check(newNearMesh !== oldNearMesh) { "Expected new Near batch0, not old captured batch1" }
                        check(meshPipelineField.get(newNearMesh) === sharedPipeline)
                        check(heldRetirements.isNotEmpty()) { "TTL pruning did not request a retirement" }
                        val state = VulkanFrameLifecycle.stateFor(context.backend as RenderBackendVk)
                        val fenceField = VulkanUploadState::class.java.getDeclaredField("lastSubmittedFence").apply { isAccessible = true }
                        val fence = fenceField.getLong(state)
                        check(fence != 0L)
                        check(vkWaitForFences((context.backend as RenderBackendVk).device.vkDevice, fence, true,
                            5_000_000_000L) == VK_SUCCESS) { "Latest actual native submission did not complete" }
                        holdRetirements = false
                        val pending = heldRetirements.toList().also { heldRetirements.clear() }
                        // This is the normal success-fence fast path, deliberately invoked in
                        // the real collect -> capture seam. No fake acquire or GPU completion.
                        VulkanFrameLifecycle.retainUntilFrameComplete { pending.forEach { it() } }
                        check(oldNearMesh.isReleased) { "The old Mesh was not actually released" }
                        println("RWXVulkanSpriteAtlasOracle pipelineSeam oldMeshReleased=${oldNearMesh.isReleased} " +
                            "pipelineReleased=${sharedPipeline.isReleased} newMeshPipelineSame=${meshPipelineField.get(newNearMesh) === sharedPipeline} " +
                            "shaderPipelineSame=${sharedShader.createdPipeline === sharedPipeline}")
                        check(!sharedPipeline.isReleased && sharedShader.createdPipeline === sharedPipeline) {
                            "Releasing the last old captured Mesh released a pipeline already held by a new collected Mesh"
                        }
                        seamFinished.complete(Unit)
                    } catch (failure: Throwable) {
                        seamFinished.completeExceptionally(failure)
                    }
                }
                seamFinished.await()
                waitCycles()
                checkBindings("after-uncaptured-pipeline-seam")
                assertPixel(image(), 3, 3, intArrayOf(0, 255, 0, 255), "new Near batch survives old last-user retirement")
                repeat(61) { present() }
                awaitState("all seam Atlas owners and pipelines retired") {
                    observedTextures.all { it.isReleased } && observedImages.all { it.isReleased } && observedPipelines.all { it.isReleased }
                }
                awaitState("all seam fence callbacks") { requestedRetirements == completedRetirements }
                context.scenes -= scene
                var closed = false
                VulkanFrameLifecycle.retainUntilFrameComplete { scene.release(); closed = true }
                awaitState("seam scene close") { closed }
                println("RWXVulkanSpriteAtlasOracle seamSuccess checks=$checks rootAtlas=true newUncapturedMesh=true " +
                    "realNativeFence=true actualFenceRelease=true retirementsRequested=$requestedRetirements " +
                    "retirementsCompleted=$completedRetirements")
                return
            }
            val firstMeshes = sprites()
            check(firstMeshes.size == 2 && firstMeshes[0].shader === firstMeshes[1].shader) {
                "Expected two actual Sprite batches sharing one page/filter/blend Shader"
            }
            check(firstMeshes.map { it.drawGroupId }.distinct().size == 2)
            val initialImage = image()
            assertPixel(initialImage, 2, 2, intArrayOf(127, 0, 128, 255), "primitive barrier over red")
            assertPixel(initialImage, 6, 2, intArrayOf(0, 255, 0, 255), "last green batch wins")
            assertPixel(initialImage, 14, 2, intArrayOf(255, 0, 0, 255), "first red batch outside later geometry")
            val firstOwner = firstMeshes.first().shader!!.texture2d("spriteMap").get()!!
            val firstNative = firstOwner.gpuTexture as ImageVk

            stage = "dirty-page-growth"
            val added = solid("atlas-oracle-new-sprite", 0xff804020.toInt())
            present(shared() + sprite(added, dest = KoolCanvasRect(16f, 0f, 32f, 16f)))
            val afterGrowth = sprites()
            check(afterGrowth.size == 2 && afterGrowth[0].shader === afterGrowth[1].shader)
            val grownOwner = afterGrowth.first().shader!!.texture2d("spriteMap").get()!!
            check(grownOwner !== firstOwner)
            assertPixel(image(), 20, 3, intArrayOf(128, 64, 32, 255), "new sprite in dirty page")
            awaitState("old dirty-page native image release") { firstOwner.isReleased && firstNative.isReleased }
            check(!grownOwner.isReleased && !grownOwner.gpuTexture!!.isReleased)
            checks++

            stage = "simultaneous-filters"
            present(withFilters())
            val both = sprites().map { it.shader!!.texture2d("spriteMap").get()!! }.distinct()
            check(both.any { it.name.endsWith("-Nearest") } && both.any { it.name.endsWith("-Linear") })
            val filtered = image()
            assertPixel(filtered, 35, 3, intArrayOf(255, 0, 0, 255), "Nearest red texel")
            assertPixel(filtered, 43, 3, intArrayOf(159, 96, 0, 255), "Linear inter-texel color")

            stage = "unused-filter-after-growth"
            val oldNear = both.single { it.name.endsWith("-Nearest") }
            val oldNearNative = oldNear.gpuTexture as ImageVk
            val next = solid("atlas-oracle-linear-only-growth", 0xff4080c0.toInt())
            present(shared(KoolCanvasTextureFilter.Linear) +
                sprite(next, KoolCanvasTextureFilter.Linear, KoolCanvasRect(16f, 0f, 32f, 16f)))
            awaitState("unused Nearest owner release") { oldNear.isReleased && oldNearNative.isReleased }
            present(withFilters())
            assertPixel(image(), 35, 3, intArrayOf(255, 0, 0, 255), "Nearest batch returns after old owner release")

            stage = "shared-shader-batch-retention"
            present(List(130) { listOf(sprite(red, dest = KoolCanvasRect(0f, 32f, 8f, 40f)), barrier()) }.flatten())
            val manyBatches = sprites()
            check(manyBatches.size == 130 && manyBatches.all { it.shader === manyBatches.first().shader })
            check(manyBatches.map { it.drawGroupId }.distinct().size == 130)
            assertPixel(image(), 3, 35, intArrayOf(255, 0, 0, 255), "130 separated batches share one Shader")

            stage = "growth-and-material-order-stress"
            // The old 130 batches become unused while the SAME material remains active. More
            // than 60 new packets exercises their actual fence pruning during page replacement.
            repeat(65) { index ->
                val color = 0xff000000.toInt() or (((32 + index * 11) and 255) shl 16) or
                    (((210 - index * 7) and 255) shl 8) or ((50 + index * 5) and 255)
                val key = solid("atlas-oracle-growth-$index", color)
                val commands = if (index % 2 == 0) withFilters() else
                    listOf(sprite(pattern, KoolCanvasTextureFilter.Linear, KoolCanvasRect(40f, 0f, 48f, 8f))) + shared()
                present(commands + sprite(key, dest = KoolCanvasRect(16f, 0f, 32f, 16f)))
                val data = image()
                assertPixel(data, 20, 3, intArrayOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255, 255),
                    "growth $index fresh texels")
                assertPixel(data, 6, 2, intArrayOf(0, 255, 0, 255), "growth $index shared material order")
            }

            stage = "second-atlas-page"
            val largeA = solid("atlas-oracle-large-a", 0xff00ffff.toInt(), 1536)
            val largeB = solid("atlas-oracle-large-b", 0xffff00ff.toInt(), 1536)
            present(withFilters() + listOf(
                sprite(largeA, dest = KoolCanvasRect(0f, 16f, 16f, 32f), size = 1536),
                sprite(largeB, dest = KoolCanvasRect(16f, 16f, 32f, 32f), size = 1536)))
            val owners = sprites().map { it.shader!!.texture2d("spriteMap").get()!! }.distinct()
            val pages = owners.map { it.name.removePrefix("rwx-sprite-atlas-").substringBefore('-') }.distinct()
            check(pages.size >= 2) { "The two large sources did not force an additional actual Atlas page" }
            val large = image()
            assertPixel(large, 3, 20, intArrayOf(0, 255, 255, 255), "large sprite in first page")
            assertPixel(large, 20, 20, intArrayOf(255, 0, 255, 255), "large sprite in second page")

            stage = "repeated-frame-envelope"
            val repeatPacket = store.freezeFrame(frame(withFilters()), ++sequence, 1, 0, 0)
            val repeatOwner = repeatPacket.retain()
            try {
                presentEnvelope(repeatPacket)
                val stableTextures = sprites().map { it.shader!!.texture2d("spriteMap").get()!! }
                val beforeRepeat = image()
                waitCycles(8)
                presentEnvelope(repeatOwner.retain())
                waitCycles(8)
                check(stableTextures == sprites().map { it.shader!!.texture2d("spriteMap").get()!! })
                check(beforeRepeat.contentEquals(image())) { "Repeating the same immutable packet changed Atlas pixels" }
                checks++
            } finally { repeatOwner.close() }

            stage = "idle-sixty-one-new-envelopes"
            val beforeIdleTextures = observedTextures.toList()
            val beforeIdleImages = observedImages.toList()
            val beforeIdlePipelines = observedPipelines.toList()
            val completedBeforeIdle = completedRetirements
            // Atlas age advances on NEW renderer frames, not repeated native presentations.
            repeat(61) { present() }
            check(sprites().isEmpty())
            awaitState("expired Atlas owners and native images") {
                beforeIdleTextures.all { it.isReleased } && beforeIdleImages.all { it.isReleased } &&
                    beforeIdlePipelines.all { it.isReleased }
            }
            check(completedRetirements > completedBeforeIdle)
            checks++
            assertPixel(image(), 6, 2, intArrayOf(0, 0, 0, 255), "idle scene cleared")

            stage = "old-frozen-envelope-after-expiry"
            solid("atlas-oracle-red", 0xffffff00.toInt())
            // This old packet retains detached RED even after the logical source changed.
            presentEnvelope(retainedInitial.retain())
            val returned = image()
            assertPixel(returned, 14, 2, intArrayOf(255, 0, 0, 255), "old packet re-enters rebuilt Atlas")
            assertPixel(returned, 6, 2, intArrayOf(0, 255, 0, 255), "old packet retains green batch")
            present(shared())
            assertPixel(image(), 14, 2, intArrayOf(255, 255, 0, 255), "new source version after old packet")

            stage = "final-atlas-release"
            repeat(61) { present() }
            awaitState("all observed Atlas GPU owners retired") {
                observedTextures.all { it.isReleased } && observedImages.all { it.isReleased } &&
                    observedPipelines.all { it.isReleased }
            }
            awaitState("Oracle fence callbacks") { requestedRetirements == completedRetirements }
            check(requestedRetirements > 0)
            checks++
            context.scenes -= scene // Frontend before collection, including immediate fence path.
            var closed = false
            VulkanFrameLifecycle.retainUntilFrameComplete { scene.release(); closed = true }
            awaitState("scene close after actual last-use fence") { closed }
            println("RWXVulkanSpriteAtlasOracle success checks=$checks rootAtlas=true sharedMaterialBatches=130 growthFrames=65 " +
                "dirtyPages=true filters=true multiplePages=true repeatedEnvelope=true idleExpiryFrames=61 " +
                "frozenSourceLease=true actualFenceRelease=true " +
                "observedTextureOwners=${observedTextures.size} observedNativeImages=${observedImages.size} " +
                "retirementsRequested=$requestedRetirements retirementsCompleted=$completedRetirements")
        } finally { retainedInitial.close() }
    }
}
