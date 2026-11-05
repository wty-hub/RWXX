package io.github.rwx.kool.vulkan

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.NativeAssetLoader
import de.fabmax.kool.createContext
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.util.FrontendScope
import io.github.rwx.render.canvas.KoolCanvasSceneHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties
import org.lwjgl.vulkan.VkExtensionProperties
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Test-only launcher that reuses the production pixel oracle with a genuinely windowless target.
 * It is never discovered as a JUnit test. All modes require an explicit launch guard.
 * This runner deliberately has no Steam, Swing-host, or production-desktop-main dependency.
 * The caller must enforce a process deadline: coroutine timeouts cannot interrupt native fence waits.
 */
object WindowlessKoolGpuOracle {
    private const val AUDITED_MOLTENVK_SHA1 = "529d3f0cf2f50e8df3d0dd2ec5fe8b9230a037a0"
    private const val MOLTENVK_RESOURCE = "macos/arm64/org/lwjgl/vulkan/libMoltenVK.dylib"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args.single() in setOf("--probe", "--map-cell", "--sprite-atlas")) {
            "Usage: WindowlessKoolGpuOracle --probe | --map-cell | --sprite-atlas"
        }
        prepareAuditedNative()
        if (args.single() == "--map-cell") {
            require(System.getenv("RWX_GPU_MAP_CELL_TARGETS") == "1") { "Set RWX_GPU_MAP_CELL_TARGETS=1" }
            require(System.getenv("RWX_GPU_MAP_CELL_ORACLE_NO_READBACK") != "1") { "The pixel oracle requires GPU readback" }
            require(System.getenv("RWX_GPU_MAP_CELL_ORACLE_SINGLE") != "1") { "The pixel oracle requires both content versions" }
        }
        if (args.single() == "--probe") {
            println("RWXWindowlessVulkanProbe extensions=${instanceExtensions().sorted().joinToString(",")}")
            return
        }
        if (args.single() == "--sprite-atlas") {
            require(System.getenv("RWX_RUN_VULKAN_SPRITE_ATLAS_ORACLE") == "1") {
                "Set RWX_RUN_VULKAN_SPRITE_ATLAS_ORACLE=1"
            }
            require(System.getenv("RWX_GPU_MAP_CELL_ORACLE_MSAA").let { it == null || it == "4" }) {
                "The existing Sprite Atlas oracle requires MSAA4"
            }
            runSpriteAtlasOracle()
        } else runMapCellOracle()
    }

    private val auditedNative: Path by lazy { extractAuditedNative() }

    private fun prepareAuditedNative() {
        require(System.getenv("RWX_RUN_WINDOWLESS_VULKAN_ORACLE") == "1") { "Windowless Vulkan execution is not enabled" }
        require(System.getProperty("os.name").lowercase().contains("mac")) { "Only the audited macOS path is enabled" }
        require(System.getProperty("os.arch") in setOf("aarch64", "arm64")) { "Only the audited arm64 native is enabled" }
        require(System.getenv("MVK_CONFIG_SWITCH_SYSTEM_GPU") == "0") { "Set MVK_CONFIG_SWITCH_SYSTEM_GPU=0" }
        require(System.getenv("MVK_CONFIG_AUTO_GPU_CAPTURE_SCOPE") == "0") { "Set MVK_CONFIG_AUTO_GPU_CAPTURE_SCOPE=0" }
        System.setProperty("java.awt.headless", "true")
        // An absolute override pins the verified bytes and forbids SDK/driver fallback or a same-name library.
        System.setProperty("org.lwjgl.vulkan.libname", auditedNative.toString())
        Thread.currentThread().priority = Thread.MIN_PRIORITY
    }

    /** Test callers share the audited native, memory-only adapter, fixed pacing and fail-closed backend. */
    internal suspend fun createWindowlessContext(size: Vec2i = Vec2i(128, 128), assets: String = "assets",
        frameObserver: WindowlessFrameObserver? = null): Lwjgl3Context {
        prepareAuditedNative()
        val extensions = instanceExtensions()
        require("VK_KHR_surface" in extensions && "VK_EXT_headless_surface" in extensions) {
            "The audited native does not advertise the required headless-surface extensions"
        }
        return createContext(KoolConfigJvm(
            defaultAssetLoader = NativeAssetLoader(assets),
            windowTitle = "RWX windowless GPU oracle",
            windowSize = size,
            windowSubsystem = HeadlessVulkanWindowSubsystem(frameObserver),
            renderBackend = RenderBackendVk,
            useOpenGlFallback = false,
            showWindowOnStart = false,
            asyncSceneUpdate = false,
            numSamples = System.getenv("RWX_GPU_MAP_CELL_ORACLE_MSAA")?.toIntOrNull() ?: 4,
            isVsync = false,
            maxFrameRate = 60,
            windowNotFocusedFrameRate = 60,
        ))
    }

    private fun extractAuditedNative(): Path {
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(MOLTENVK_RESOURCE)) {
            "The audited bundled MoltenVK native is missing"
        }.use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == AUDITED_MOLTENVK_SHA1) { "MoltenVK binary differs from the reviewed headless implementation: $actual" }
        val directory = Files.createTempDirectory("rwx-windowless-moltenvk-")
        directory.toFile().deleteOnExit()
        return directory.resolve("libMoltenVK.dylib").also { file ->
            Files.write(file, bytes)
            file.toFile().deleteOnExit()
        }.toAbsolutePath()
    }

    private fun instanceExtensions(): Set<String> = MemoryStack.stackPush().use { stack ->
        val count = stack.mallocInt(1)
        check(vkEnumerateInstanceExtensionProperties(null as String?, count, null) == VK_SUCCESS)
        val properties = VkExtensionProperties.calloc(count[0], stack)
        check(vkEnumerateInstanceExtensionProperties(null as String?, count, properties) == VK_SUCCESS)
        (0 until count[0]).mapTo(mutableSetOf()) { properties[it].extensionNameString() }
    }

    private fun runMapCellOracle() = runBlocking {
        val context = createWindowlessContext()
        val result = CompletableDeferred<Unit>()
        val deadline = System.nanoTime() + 60_000_000_000L
        context.onRender += {
            if (!result.isCompleted && System.nanoTime() >= deadline) {
                result.completeExceptionally(IllegalStateException("Windowless GPU oracle timed out"))
                context.close()
            }
        }
        FrontendScope.launch {
            try {
                withTimeout(55_000L) { verifyExistingOracle(context) }
                result.complete(Unit)
            } catch (failure: Throwable) {
                failure.printStackTrace()
                result.completeExceptionally(failure)
            } finally {
                context.close()
            }
        }
        context.run()
        result.await()
        println("RWXWindowlessVulkanOracle success=true windowAdapter=memory headless=true submissionOnly=true frameLimitHz=60 surface=VK_EXT_headless_surface")
    }

    private suspend fun verifyExistingOracle(context: Lwjgl3Context): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val oracle = KoolCanvasGpuMapCellOracle
            val verify = oracle.javaClass.getDeclaredMethod("verify", Lwjgl3Context::class.java, Continuation::class.java)
                .apply { isAccessible = true }
            try {
                verify.invoke(oracle, context, continuation)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }

    /** Observe selected immutable packets without using List.get/iterator or changing the oracle. */
    private class PackedSpriteObservation {
        private val packedClass = Class.forName("io.github.rwx.render.canvas.KoolCanvasPackedTextureCommands")
        private val packedSize = packedClass.declaredMethods.single {
            it.name.substringBefore('$') == "getPackedSize" && it.parameterCount == 0
        }.apply { isAccessible = true }
        private val materialized = packedClass.declaredMethods.single {
            it.name.substringBefore('$') == "materializedCount" && it.parameterCount == 0
        }.apply { isAccessible = true }
        private val hosts = Collections.newSetFromMap(IdentityHashMap<KoolCanvasSceneHost, Boolean>())
        private val lists = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var selectedObservations = 0L
            private set
        var packedObservations = 0L
            private set
        var distinctPackedRows = 0L
            private set
        var maximumPackedRows = 0
            private set

        @Synchronized
        fun observe(context: Lwjgl3Context) {
            for (scene in context.scenes) {
                if (scene.name != "sprite-atlas-oracle") continue
                // The existing configure callback owns its Host. Read that capture only; do not
                // update BufferedList callbacks, move scenes or install an alternate renderer.
                for (callback in scene.onRenderScene) for (field in callback.javaClass.declaredFields) {
                    if (field.type != KoolCanvasSceneHost::class.java) continue
                    field.isAccessible = true
                    val host = field.get(callback) as? KoolCanvasSceneHost ?: continue
                    hosts += host
                    val commands = host.currentFrame().commands
                    selectedObservations++
                    if (!packedClass.isInstance(commands)) continue
                    packedObservations++
                    val rows = (packedSize.invoke(commands) as Number).toInt()
                    check(rows > 0) { "Observed a packed list without packed rows" }
                    maximumPackedRows = maxOf(maximumPackedRows, rows)
                    if (lists.add(commands)) distinctPackedRows += rows
                    check((materialized.invoke(commands) as Number).toInt() == 0) {
                        "Existing Sprite oracle selected a packed frame that materialized legacy DrawTexture objects"
                    }
                }
            }
        }

        @Synchronized
        fun assertCompleted(packedExpected: Boolean) {
            check(hosts.isNotEmpty() && selectedObservations > 0) {
                "No actual Sprite Atlas Host/selected-frame observations; cannot certify packed replay"
            }
            if (packedExpected) check(lists.isNotEmpty() && maximumPackedRows > 0) {
                "RWX_PACKED_ROOT_TEXTURE_COMMANDS=1 did not produce an observed packed frame"
            } else check(lists.isEmpty()) { "Packed frames appeared while the candidate was disabled" }
            // Retained old envelopes may re-enter after atlas expiry. Recheck all observed list
            // identities after the complete native pixel/fence oracle, not only their first visit.
            for (commands in lists) check((materialized.invoke(commands) as Number).toInt() == 0) {
                "A previously selected packed frame was decoded during a later Sprite oracle stage"
            }
        }

        @Synchronized
        fun metadata(): String = "\"hostCount\":${hosts.size},\"selectedFrameObservations\":$selectedObservations," +
            "\"packedSelectionObservations\":$packedObservations,\"distinctPackedLists\":${lists.size}," +
            "\"sumDistinctPackedRows\":$distinctPackedRows,\"maximumPackedRows\":$maximumPackedRows," +
            "\"materializedPackedRows\":0"
    }

    private fun runSpriteAtlasOracle() = runBlocking {
        val observation = PackedSpriteObservation()
        val context = createWindowlessContext(Vec2i(64, 64))
        val result = CompletableDeferred<Unit>()
        val packedExpected = System.getenv("RWX_PACKED_ROOT_TEXTURE_COMMANDS") == "1"
        val deadline = System.nanoTime() + 120_000_000_000L
        context.onRender += {
            if (!result.isCompleted) try {
                observation.observe(context)
                if (System.nanoTime() >= deadline) {
                    result.completeExceptionally(IllegalStateException("Windowless Sprite Atlas oracle timed out"))
                    context.close()
                }
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
                context.close()
            }
        }
        FrontendScope.launch {
            try {
                withTimeout(110_000L) { verifyExistingSpriteOracle(context) }
                observation.assertCompleted(packedExpected)
                result.complete(Unit)
            } catch (failure: Throwable) {
                failure.printStackTrace()
                result.completeExceptionally(failure)
            } finally { context.close() }
        }
        try { context.run() }
        catch (failure: Throwable) { result.completeExceptionally(failure) }
        result.await()
        println("RWXWindowlessSpriteAtlasOracle {\"success\":true,\"existingOracleCompleted\":true," +
            "\"windowAdapter\":\"memory\",\"headless\":true,\"submissionOnly\":true," +
            "\"surface\":\"VK_EXT_headless_surface\",\"width\":64,\"height\":64,\"samples\":4," +
            "\"frameLimitHz\":60,\"deadlineSeconds\":120,\"coroutineTimeoutSeconds\":110," +
            "\"packedExpected\":$packedExpected,${observation.metadata()}}")
    }

    private suspend fun verifyExistingSpriteOracle(context: Lwjgl3Context): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val oracle = VulkanSpriteAtlasOracle
            val verify = oracle.javaClass.getDeclaredMethod("verify", Lwjgl3Context::class.java, Continuation::class.java)
                .apply { isAccessible = true }
            try { verify.invoke(oracle, context, continuation) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
}
