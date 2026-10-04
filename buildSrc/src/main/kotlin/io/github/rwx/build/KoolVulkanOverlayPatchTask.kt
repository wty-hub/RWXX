package io.github.rwx.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodNode
import java.util.zip.ZipFile

abstract class KoolVulkanOverlayPatchTask : DefaultTask() {
    @get:Classpath
    abstract val koolDesktopJar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun patch() {
        // Generated overlays must always start from the original dependency, never last run's patch.
        outputDirectory.get().asFile.deleteRecursively()
        val classBytes = readClass(SWAPCHAIN_CLASS_ENTRY)
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)

        val calls = classNode.methods.flatMap { method ->
            method.instructions.toArray()
                .filterIsInstance<MethodInsnNode>()
                .filter { instruction ->
                    instruction.owner == SWAPCHAIN_CREATE_INFO_OWNER &&
                            instruction.name == "compositeAlpha" &&
                            instruction.desc == "(I)L$SWAPCHAIN_CREATE_INFO_OWNER;"
                }
                .map { method to it }
        }
        check(calls.size == 1) {
            "Expected one Kool swapchain compositeAlpha call, found ${calls.size}"
        }

        val (method, call) = calls.single()
        val originalArgument = call.previous
        check(originalArgument.opcode == ICONST_1) {
            "Expected Kool 0.19.0 compositeAlpha argument to be ICONST_1"
        }
        method.instructions.insertBefore(
            originalArgument,
            InsnList().apply {
                add(VarInsnNode(ALOAD, SWAPCHAIN_SUPPORT_LOCAL_INDEX))
                add(
                    MethodInsnNode(
                        INVOKEVIRTUAL,
                        SWAPCHAIN_SUPPORT_OWNER,
                        "getCapabilities",
                        "()L$SURFACE_CAPABILITIES_OWNER;",
                        false,
                    ),
                )
                add(
                    MethodInsnNode(
                        INVOKEVIRTUAL,
                        SURFACE_CAPABILITIES_OWNER,
                        "supportedCompositeAlpha",
                        "()I",
                        false,
                    ),
                )
                add(
                    MethodInsnNode(
                        INVOKESTATIC,
                        COMPOSITE_ALPHA_SELECTOR_OWNER,
                        "select",
                        "(I)I",
                        false,
                    ),
                )
            },
        )
        method.instructions.remove(originalArgument)

        patchSwapchainAcquireTimeout(classNode)
        val present = classNode.methods.single { it.name == "presentNextImage" }
        present.instructions.insert(MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER, "beforePresent", "()V", false))
        present.instructions.toArray().filterIsInstance<MethodInsnNode>().single { it.name == "vkQueuePresentKHR" }
            .apply { owner = LIFECYCLE_OWNER; name = "measuredQueuePresent" }
        present.instructions.toArray().filter { it.opcode == IRETURN }.forEach { returned ->
            present.instructions.insertBefore(returned, InsnList().apply {
                add(InsnNode(DUP))
                add(MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER, "presented", "(Z)V", false))
            })
        }
        val presentMode = classNode.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
            .single { it.owner == SWAPCHAIN_CREATE_INFO_OWNER && it.name == "presentMode" }
        classNode.methods.single { it.instructions.contains(presentMode) }.instructions.insertBefore(presentMode, InsnList().apply {
            add(InsnNode(DUP))
            add(MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER, "selectedPresentMode", "(I)V", false))
        })

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        writeClass(SWAPCHAIN_CLASS_ENTRY, writer.toByteArray())

        patchDrawPipeline()
        patchFrameRateLimit()
        patchGrowingBufferUploads()
        patchTextureUploads()
        patchImageByteSizes()
        patchFrameLifecycle()
        patchMeshPipelineOwnership()
        patchNativePipelineCache()
        validateMappedAllocatorLayout()
        patchNativeMemoryRetirement()
        patchNativeReleaseQueue()
        patchDeviceIdleResult()
    }

    /** Register a selected pipeline's CPU owner before collect -> capture retirement can run. */
    private fun patchMeshPipelineOwnership() {
        val owner = "de/fabmax/kool/scene/Mesh"
        val pipelineOwner = "de/fabmax/kool/pipeline/DrawPipeline"
        val node = loadNode("$owner.class")
        check(node.fields.count { it.name == "pipeline" && it.desc == "L$pipelineOwner;" } == 1) {
            "Expected one Kool 0.19 Mesh cached pipeline field"
        }
        val method = node.methods.single { it.name == "getOrCreatePipeline" &&
            it.desc == "(Lde/fabmax/kool/KoolContext;Lde/fabmax/kool/scene/MeshInstanceList;)L$pipelineOwner;" }
        check(method.instructions.toArray().filterIsInstance<MethodInsnNode>().count {
            it.owner == "de/fabmax/kool/pipeline/DrawShader" && it.name == "getOrCreatePipeline"
        } == 1) { "Expected one Kool 0.19 Mesh shader pipeline selection" }
        listOf("setShader", "doRelease").forEach { name ->
            check(node.methods.single { it.name == name }.instructions.toArray()
                .filterIsInstance<MethodInsnNode>().count {
                    it.owner == pipelineOwner && it.name == "removeUser" && it.desc == "(L$owner;)V"
                } == 1) { "Expected Kool 0.19 Mesh.$name to balance its pipeline ownership" }
        }
        val returned = method.instructions.toArray().filter { it.opcode == ARETURN }
        check(returned.isNotEmpty()) { "Expected Kool 0.19 Mesh pipeline return" }
        returned.forEach { instruction ->
            // The nullable helper leaves the return value unchanged. No new bytecode branches
            // or stack-map frames: cached, newly selected and null returns all keep their shape.
            method.instructions.insertBefore(instruction, InsnList().apply {
                add(VarInsnNode(ALOAD, 0))
                add(MethodInsnNode(INVOKESTATIC, "io/github/rwx/kool/vulkan/KoolMeshPipelineOwnership",
                    "retain", "(L$pipelineOwner;L$owner;)L$pipelineOwner;", false))
            })
        }
        writeNode("$owner.class", node)
    }

    /** Replace inline per-mesh VMA staging allocations; MixedBuffer.limit is already bytes. */
    private fun patchGrowingBufferUploads() {
        val owner = "de/fabmax/kool/pipeline/backend/vk/GrowingBufferVk"
        val node = loadNode("$owner.class")
        listOf("Float32Buffer", "Int32Buffer", "MixedBuffer").forEach { type ->
            val dataOwner = "de/fabmax/kool/util/$type"
            val descriptor = "(L$dataOwner;Lorg/lwjgl/vulkan/VkCommandBuffer;)V"
            val method = node.methods.single { it.name == "writeData" && it.desc == descriptor }
            check(method.instructions.toArray().any { it.opcode == LMUL }) {
                "Expected Kool 0.19 $type upload size multiplication"
            }
            replaceBody(method, InsnList().apply {
                // Preserve Kool's growth policy, but use bytes for MixedBuffer and elements * 4 otherwise.
                add(VarInsnNode(ALOAD, 0))
                add(VarInsnNode(ALOAD, 1))
                add(MethodInsnNode(INVOKEINTERFACE, dataOwner, "getLimit", "()I", true))
                add(InsnNode(I2L))
                if (type != "MixedBuffer") {
                    add(LdcInsnNode(4L))
                    add(InsnNode(LMUL))
                }
                add(MethodInsnNode(INVOKESPECIAL, owner, "checkSize", "(J)V", false))
                add(VarInsnNode(ALOAD, 0))
                add(VarInsnNode(ALOAD, 1))
                add(VarInsnNode(ALOAD, 2))
                add(MethodInsnNode(INVOKESTATIC, UPLOAD_OWNER, "writeData", "(L$owner;L$dataOwner;Lorg/lwjgl/vulkan/VkCommandBuffer;)V", false))
                add(InsnNode(RETURN))
            })
        }
        writeNode("$owner.class", node)
    }

    /** Keep Kool's type dispatch, replace only the synchronous image transfer implementation. */
    private fun patchTextureUploads() {
        val owner = "de/fabmax/kool/pipeline/backend/vk/TextureLoaderVk"
        val node = loadNode("$owner.class")
        val descriptor = "(Lde/fabmax/kool/pipeline/Texture;ILde/fabmax/kool/pipeline/ImageData;IIIILde/fabmax/kool/pipeline/MipMapping;I)Lde/fabmax/kool/pipeline/backend/vk/ImageVk;"
        val method = node.methods.single { it.name == "loadTexture" && it.desc == descriptor }
        check(method.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name == "vkQueueWaitIdle" }) {
            "Expected synchronous Kool 0.19 texture upload"
        }
        replaceBody(method, InsnList().apply {
            add(VarInsnNode(ALOAD, 0))
            add(VarInsnNode(ALOAD, 1))
            add(VarInsnNode(ILOAD, 2))
            add(VarInsnNode(ALOAD, 3))
            (4..7).forEach { add(VarInsnNode(ILOAD, it)) }
            add(VarInsnNode(ALOAD, 8))
            add(VarInsnNode(ILOAD, 9))
            add(MethodInsnNode(INVOKESTATIC, UPLOAD_OWNER, "loadTexture", "(L$owner;" + descriptor.substring(1), false))
            add(InsnNode(ARETURN))
        })
        writeNode("$owner.class", node)
    }

    /** Keep native BGRA image statistics consistent with the four bytes actually allocated. */
    private fun patchImageByteSizes() {
        val entry = "de/fabmax/kool/pipeline/backend/vk/ImageVk.class"
        val node = loadNode(entry)
        val method = node.methods.single {
            it.name == "getBytesPerPx" && it.desc == "(Lde/fabmax/kool/pipeline/backend/vk/ImageInfo;)I"
        }
        val switch = method.instructions.toArray().filterIsInstance<LookupSwitchInsnNode>().single()
        check(VK_FORMAT_R8G8B8A8_UNORM in switch.keys && VK_FORMAT_B8G8R8A8_UNORM !in switch.keys) {
            "Expected Kool 0.19 RGBA byte-size entry without native BGRA"
        }
        val rgbaLabel = switch.labels[switch.keys.indexOf(VK_FORMAT_R8G8B8A8_UNORM)]
        val insertAt = switch.keys.indexOfFirst { it > VK_FORMAT_B8G8R8A8_UNORM }
            .let { if (it < 0) switch.keys.size else it }
        switch.keys.add(insertAt, VK_FORMAT_B8G8R8A8_UNORM)
        switch.labels.add(insertAt, rgbaLabel)
        writeNode(entry, node)
    }

    private fun patchFrameLifecycle() {
        val owner = "de/fabmax/kool/pipeline/backend/vk/PassEncoderState"
        val node = loadNode("$owner.class")
        val begin = node.methods.single { it.name == "beginFrame" && it.desc == "(Lorg/lwjgl/system/MemoryStack;)V" }
        val end = node.methods.single { it.name == "endFrame" && it.desc == "()V" }
        end.instructions.toArray().filterIsInstance<MethodInsnNode>().single { it.name == "vkQueueSubmit" }
            .apply { this.owner = LIFECYCLE_OWNER; name = "measuredQueueSubmit" }
        end.instructions.insert(MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER, "beforeSubmit", "()V", false))
        insertBeforeReturns(begin) { InsnList().apply {
            add(VarInsnNode(ALOAD, 0))
            add(MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER, "beginFrame", "(L$owner;)V", false))
        } }
        insertBeforeReturns(end) { InsnList().apply {
            add(VarInsnNode(ALOAD, 0))
            add(MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER, "submitted", "(L$owner;)V", false))
        } }
        writeNode("$owner.class", node)

        val backendOwner = "de/fabmax/kool/pipeline/backend/vk/RenderBackendVk"
        val backend = loadNode("$backendOwner.class")
        val constructor = backend.methods.single { it.name == "<init>" }
        insertBeforeReturns(constructor) { InsnList().apply {
            add(VarInsnNode(ALOAD, 0))
            add(MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER, "created", "(L$backendOwner;)V", false))
        } }
        listOf("cleanup", "recreateSwapchain").forEach { methodName ->
            val method = backend.methods.single { it.name == methodName }
            val idle = method.instructions.toArray().filterIsInstance<MethodInsnNode>().single {
                it.owner == "de/fabmax/kool/pipeline/backend/vk/Device" && it.name == "waitForIdle"
            }
            method.instructions.insert(idle, InsnList().apply {
                add(VarInsnNode(ALOAD, 0))
                add(InsnNode(if (methodName == "cleanup") ICONST_1 else ICONST_0))
                add(MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER, "deviceIdle", "(L$backendOwner;Z)V", false))
            })
        }
        writeNode("$backendOwner.class", backend)

        val queue = loadNode("de/fabmax/kool/pipeline/backend/vk/KoolVkExtensionsKt.class")
        queue.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
            .single { it.name == "vkQueueSubmit" }
            .apply { this.owner = LIFECYCLE_OWNER; name = "measuredQueueSubmit" }
        writeNode("de/fabmax/kool/pipeline/backend/vk/KoolVkExtensionsKt.class", queue)
    }

    private fun patchNativePipelineCache() {
        var patchedCalls = 0
        ZipFile(koolDesktopJar.get().asFile).use { jar ->
            jar.entries().asSequence().filter {
                it.name.startsWith("de/fabmax/kool/pipeline/backend/vk/") && it.name.endsWith(".class")
            }.forEach { entry ->
                val node = loadNode(entry.name)
                var modified = false
                node.methods.forEach { method ->
                    method.instructions.toArray().filterIsInstance<MethodInsnNode>().filter {
                        it.owner == "org/lwjgl/vulkan/VK10" &&
                            it.name in listOf("vkCreateGraphicsPipelines", "vkCreateComputePipelines")
                    }.forEach { call ->
                        check(call.desc.endsWith("Ljava/nio/LongBuffer;)I")) {
                            "Unsupported Vulkan pipeline creation overload: ${call.desc}"
                        }
                        call.owner = PIPELINE_CACHE_OWNER
                        patchedCalls++
                        modified = true
                    }
                }
                if (modified) writeNode(entry.name, node)
            }
        }
        check(patchedCalls == 2) { "Expected Kool graphics and compute pipeline creation sites, found $patchedCalls" }
    }

    /** The upload helper resolves this private allocator once, to flush non-coherent mappings. */
    private fun validateMappedAllocatorLayout() {
        check(loadNode("de/fabmax/kool/pipeline/backend/vk/MemoryManager.class").fields.any {
            it.name == "impl" && it.desc == "Lde/fabmax/kool/pipeline/backend/vk/MemoryManager\$MemManager;"
        }) { "Kool memory allocator layout changed" }
        check(loadNode("de/fabmax/kool/pipeline/backend/vk/MemoryManager\$VmaMemManager.class").fields.any {
            it.name == "allocator" && it.desc == "J"
        }) { "Kool VMA allocator layout changed" }
    }

    /** Replace render-frame-count deletion with explicit graphics-fence retirement. */
    private fun patchNativeMemoryRetirement() {
        val owner = "de/fabmax/kool/pipeline/backend/vk/MemoryManager"
        val node = loadNode("$owner.class")
        node.interfaces.add("io/github/rwx/kool/vulkan/VulkanNativeMemoryAccess")
        listOf("Buffer", "Image").forEach { type ->
            val name = "free$type"
            val descriptor = "(Lde/fabmax/kool/pipeline/backend/vk/Vk$type;I)V"
            val original = node.methods.single { it.name == name && it.desc == descriptor }
            original.name = "rwxFree${type}Immediate"
            val wrapper = MethodNode(ACC_PUBLIC or ACC_FINAL, name, descriptor, null, null)
            wrapper.instructions = InsnList().apply {
                add(VarInsnNode(ALOAD, 0))
                add(VarInsnNode(ALOAD, 1))
                add(VarInsnNode(ILOAD, 2))
                add(MethodInsnNode(INVOKESTATIC, "io/github/rwx/kool/vulkan/VulkanNativeMemoryRetirement", name,
                    "(L$owner;" + descriptor.substring(1), false))
                add(InsnNode(RETURN))
            }
            node.methods.add(wrapper)
        }
        insertBeforeReturns(node.methods.single { it.name == "doRelease" }) { InsnList().apply {
            add(VarInsnNode(ALOAD, 0))
            add(MethodInsnNode(INVOKESTATIC, "io/github/rwx/kool/vulkan/VulkanNativeMemoryRetirement",
                "allocatorReleased", "(L$owner;)V", false))
        } }
        writeNode("$owner.class", node)
    }

    private fun patchNativeReleaseQueue() {
        val owner = "de/fabmax/kool/pipeline/backend/vk/ReleaseQueue"
        val node = loadNode("$owner.class")
        val enqueue = node.methods.single { it.name == "enqueue" && it.desc == "(ILkotlin/jvm/functions/Function0;)V" }
        replaceBody(enqueue, InsnList().apply {
            add(VarInsnNode(ALOAD, 2))
            add(MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER, "retireDriverTask", "(Lkotlin/jvm/functions/Function0;)V", false))
            add(InsnNode(RETURN))
        })
        writeNode("$owner.class", node)
    }

    private fun patchDeviceIdleResult() {
        val owner = "de/fabmax/kool/pipeline/backend/vk/Device"
        val node = loadNode("$owner.class")
        val method = node.methods.single { it.name == "waitForIdle" }
        val wait = method.instructions.toArray().filterIsInstance<MethodInsnNode>().single { it.name == "vkDeviceWaitIdle" }
        val resultDiscard = generateSequence(wait.next) { it.next }.first { it.opcode >= 0 }
        check(resultDiscard.opcode == POP) { "Expected unchecked Kool device idle return value" }
        method.instructions.set(resultDiscard, MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER,
            "checkedDeviceIdle", "(I)V", false))
        writeNode("$owner.class", node)
    }

    private fun replaceBody(method: MethodNode, instructions: InsnList) {
        method.instructions = instructions
        method.tryCatchBlocks.clear()
        method.localVariables?.clear()
        method.visibleLocalVariableAnnotations?.clear()
        method.invisibleLocalVariableAnnotations?.clear()
    }

    private fun insertBeforeReturns(method: MethodNode, instructions: () -> InsnList) {
        val returns = method.instructions.toArray().filter { it.opcode == RETURN }
        check(returns.isNotEmpty()) { "No normal return in ${method.name}" }
        returns.forEach { method.instructions.insertBefore(it, instructions()) }
    }

    private fun loadNode(entry: String) = ClassNode().also { node ->
        val output = outputDirectory.file(entry).get().asFile
        ClassReader(if (output.isFile) output.readBytes() else readClass(entry)).accept(node, 0)
    }

    private fun writeNode(entry: String, node: ClassNode) {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        writeClass(entry, writer.toByteArray())
    }

    /** Kool 0.19 records the time before waiting, so fast frames can run at twice the limit. */
    private fun patchFrameRateLimit() {
        val entryName = "de/fabmax/kool/platform/Lwjgl3Context.class"
        val classNode = ClassNode()
        ClassReader(readClass(entryName)).accept(classNode, 0)
        val method = classNode.methods.single { it.name == "checkFrameRateLimits" && it.desc == "()V" }
        val stores = method.instructions.toArray().filterIsInstance<FieldInsnNode>().filter {
            it.opcode == PUTFIELD && it.name == "prevFrameTime" && it.desc == "J"
        }
        check(stores.size == 1) { "Expected one Kool frame-limit timestamp store, found ${stores.size}" }
        val store = stores.single()
        val timestamp = store.previous
        check(timestamp.opcode == LLOAD) { "Expected Kool frame-limit timestamp to be a local value" }
        method.instructions.set(timestamp, MethodInsnNode(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false))

        val delay = classNode.methods.single { it.name == "delayFrameRender" && it.desc == "(JJ)V" }
        delay.instructions.clear()
        delay.tryCatchBlocks.clear()
        delay.localVariables?.clear()
        delay.instructions.add(VarInsnNode(ALOAD, 0))
        delay.instructions.add(VarInsnNode(LLOAD, 1))
        delay.instructions.add(VarInsnNode(LLOAD, 3))
        delay.instructions.add(MethodInsnNode(INVOKESTATIC, "io/github/rwx/KoolFramePacerKt", "waitForKoolFrame",
            "(Lde/fabmax/kool/platform/Lwjgl3Context;JJ)V", false))
        delay.instructions.add(InsnNode(RETURN))

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        writeClass(entryName, writer.toByteArray())
    }

    /**
     * Bounds the swapchain image acquisition.
     *
     * Kool waits for the next swapchain image with an infinite timeout
     * (`vkAcquireNextImageKHR(..., -1, ...)`) and its Swing loop drives the game loop from the same
     * thread, so an occluded window, a sleeping display or a lost surface stops the whole game
     * instead of dropping frames. The acquire timeout becomes finite (see [ACQUIRE_TIMEOUT_NANOS])
     * and `VK_TIMEOUT` is handled like `VK_ERROR_OUT_OF_DATE_KHR` (return `false`), which makes
     * Kool skip the frame and recreate the swapchain until drawables come back.
     *
     * The fence wait before it stays infinite on purpose: it waits for our own previous submission,
     * and timing that out would let the next frame reuse a still in-flight buffer.
     */
    private fun patchSwapchainAcquireTimeout(classNode: ClassNode) {
        val method = classNode.methods.singleOrNull { it.name == "acquireNextImage" && it.desc == "()Z" }
            ?: error("Kool Swapchain.acquireNextImage was not found")
        val instructions = method.instructions.toArray()

        val wait = instructions.filterIsInstance<MethodInsnNode>().single { it.name == "vkWaitForFences" }
        wait.owner = LIFECYCLE_OWNER
        wait.name = "measuredFenceWait"
        val resultDiscard = generateSequence(wait.next) { it.next }.first { it.opcode >= 0 }
        check(resultDiscard.opcode == POP) { "Expected unchecked Kool fence wait return value" }
        method.instructions.set(resultDiscard, MethodInsnNode(INVOKESTATIC, LIFECYCLE_OWNER,
            "checkedFenceWait", "(I)V", false))
        instructions.filterIsInstance<MethodInsnNode>().single { it.name == "vkAcquireNextImageKHR" }
            .apply { owner = LIFECYCLE_OWNER; name = "measuredAcquireImage" }

        val timeouts = instructions.filterIsInstance<LdcInsnNode>().filter { it.cst == -1L }
        check(timeouts.size == 2) {
            "Expected two infinite swapchain timeouts, found ${timeouts.size}"
        }
        // First is the fence wait, second the image acquisition; only the acquisition is bounded.
        timeouts.last().cst = ACQUIRE_TIMEOUT_NANOS

        val switch = instructions.filterIsInstance<LookupSwitchInsnNode>()
            .singleOrNull { VK_ERROR_OUT_OF_DATE_KHR in it.keys }
            ?: error("Kool Swapchain.acquireNextImage switch was not found")
        check(VK_TIMEOUT !in switch.keys) {
            "Kool already handles the swapchain acquire timeout"
        }
        val skipFrameLabel = switch.labels[switch.keys.indexOf(VK_ERROR_OUT_OF_DATE_KHR)]
        val keys = switch.keys.toMutableList()
        val labels = switch.labels.toMutableList()
        val insertAt = keys.indexOfFirst { it > VK_TIMEOUT }.let { if (it < 0) keys.size else it }
        keys.add(insertAt, VK_TIMEOUT)
        labels.add(insertAt, skipFrameLabel)
        switch.keys = keys
        switch.labels = labels
    }

    private fun patchDrawPipeline() {
        val classNode = ClassNode()
        ClassReader(readClass(DRAW_PIPELINE_CLASS_ENTRY)).accept(classNode, 0)
        val blendInfo = classNode.methods.singleOrNull { method ->
            method.name == "blendInfo" &&
                method.desc ==
                "(Lorg/lwjgl/system/MemoryStack;Lde/fabmax/kool/pipeline/backend/vk/PassEncoderState;)" +
                "Lorg/lwjgl/vulkan/VkPipelineColorBlendStateCreateInfo;"
        } ?: error("Kool DrawPipelineVk blendInfo method was not found")

        val alphaFactorCalls = blendInfo.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
            .filter { instruction ->
                instruction.owner == COLOR_BLEND_ATTACHMENT_OWNER &&
                    instruction.name == "dstAlphaBlendFactor" &&
                    instruction.desc == "(I)L$COLOR_BLEND_ATTACHMENT_OWNER;"
            }
        check(alphaFactorCalls.size == 3) {
            "Expected three Kool destination alpha blend factors, found ${alphaFactorCalls.size}"
        }

        // Kool 0.19.0 replaces destination alpha for multiply/premultiplied-alpha draws.
        // Transparent texture texels then punch holes through already-rendered UI surfaces.
        alphaFactorCalls.drop(1).forEach { call ->
            val originalArgument = call.previous
            check(originalArgument.opcode == ICONST_0) {
                "Expected Kool destination alpha blend factor argument to be ICONST_0"
            }
            blendInfo.instructions.insertBefore(
                originalArgument,
                IntInsnNode(BIPUSH, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA),
            )
            blendInfo.instructions.remove(originalArgument)
        }

        // Texture.checkLoadingState normally first runs in bind(), inside an active render pass.
        // Resolve all draw scopes during preparePipelines so image transfers precede render passes.
        val updateGeometry = classNode.methods.single { it.name == "updateGeometry" }
        updateGeometry.instructions.insert(InsnList().apply {
            add(VarInsnNode(ALOAD, 1))
            add(VarInsnNode(ALOAD, 2))
            add(MethodInsnNode(INVOKESTATIC, UPLOAD_OWNER, "prepareTextures",
                "(Lde/fabmax/kool/pipeline/DrawCommand;Lde/fabmax/kool/pipeline/backend/vk/PassEncoderState;)V", false))
        })

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        writeClass(DRAW_PIPELINE_CLASS_ENTRY, writer.toByteArray())
    }

    private fun readClass(entryName: String): ByteArray = ZipFile(koolDesktopJar.get().asFile).use { jar ->
        val entry = requireNotNull(jar.getEntry(entryName)) {
            "Kool desktop JAR does not contain $entryName"
        }
        jar.getInputStream(entry).use { it.readBytes() }
    }

    private fun writeClass(entryName: String, bytes: ByteArray) {
        val outputFile = outputDirectory.file(entryName).get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeBytes(bytes)
    }

    companion object {
        private const val UPLOAD_OWNER = "io/github/rwx/kool/vulkan/VulkanUploads"
        private const val LIFECYCLE_OWNER = "io/github/rwx/kool/vulkan/VulkanFrameLifecycle"
        private const val PIPELINE_CACHE_OWNER = "io/github/rwx/kool/vulkan/VulkanPipelineCache"
        private const val SWAPCHAIN_CLASS_ENTRY =
            "de/fabmax/kool/pipeline/backend/vk/Swapchain.class"
        private const val SWAPCHAIN_CREATE_INFO_OWNER =
            "org/lwjgl/vulkan/VkSwapchainCreateInfoKHR"
        private const val SWAPCHAIN_SUPPORT_OWNER =
            "de/fabmax/kool/pipeline/backend/vk/PhysicalDevice\$SwapChainSupportDetails"
        private const val SURFACE_CAPABILITIES_OWNER =
            "org/lwjgl/vulkan/VkSurfaceCapabilitiesKHR"
        private const val COMPOSITE_ALPHA_SELECTOR_OWNER =
            "io/github/rwx/VulkanCompositeAlpha"
        private const val SWAPCHAIN_SUPPORT_LOCAL_INDEX = 3
        private const val DRAW_PIPELINE_CLASS_ENTRY =
            "de/fabmax/kool/pipeline/backend/vk/DrawPipelineVk.class"
        private const val COLOR_BLEND_ATTACHMENT_OWNER =
            "org/lwjgl/vulkan/VkPipelineColorBlendAttachmentState"
        private const val VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA = 7
        private const val VK_FORMAT_R8G8B8A8_UNORM = 37
        private const val VK_FORMAT_B8G8R8A8_UNORM = 44

        /** 100ms: long enough not to fire on a healthy compositor, short enough to keep the loop alive. */
        private const val ACQUIRE_TIMEOUT_NANOS = 100_000_000L
        private const val VK_TIMEOUT = 2
        private const val VK_ERROR_OUT_OF_DATE_KHR = -1000001004
    }
}
