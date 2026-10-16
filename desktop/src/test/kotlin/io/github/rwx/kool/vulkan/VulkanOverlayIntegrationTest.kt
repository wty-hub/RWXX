package io.github.rwx.kool.vulkan

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import kotlin.test.*

/** Verifies runtime classpath ordering as well as the pinned native overlay's bytecode contracts. */
class VulkanOverlayIntegrationTest {
    private fun klass(name: String): ClassNode {
        return classAt("de/fabmax/kool/pipeline/backend/vk/$name")
    }

    private fun classAt(name: String): ClassNode {
        val path = "/$name.class"
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing runtime class $path" }
        return stream.use { ClassNode().also { node -> ClassReader(it).accept(node, 0) } }
    }

    @Test
    fun `frontend stage diagnostics preserve synchronous collection and pipeline bodies`() {
        val descriptors = listOf(
            listOf("de/fabmax/kool/KoolContext", "updatePipelineData", "(Lde/fabmax/kool/FrameData;)V", "frontend-pipeline-data"),
            listOf("de/fabmax/kool/KoolContext", "syncData", "(Lde/fabmax/kool/FrameData;)V", "frontend-sync-data"),
            listOf("de/fabmax/kool/pipeline/GpuPass", "collect", "(Lde/fabmax/kool/PassData;Lde/fabmax/kool/KoolContext;)V", "scene-pass-collect"),
            listOf("de/fabmax/kool/scene/Scene", "sortGpuPassesByDependencies", "()V", "scene-pass-sort"),
        )
        val lifecycle = "io/github/rwx/kool/vulkan/VulkanFrameLifecycle"
        for ((owner, name, descriptor, stage) in descriptors) {
            val node = classAt(owner)
            val wrapper = node.methods.single { it.name == name && it.desc == descriptor }
            val original = node.methods.single { it.name == "$name\$rwxMeasured" && it.desc == descriptor }
            assertEquals(original.access, wrapper.access, "$owner.$name must preserve method visibility and dispatch")
            val calls = wrapper.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertEquals(listOf("diagnosticStageStart", original.name, "diagnosticStageEnd"), calls.map { it.name })
            assertEquals(lifecycle, calls.first().owner)
            assertEquals(lifecycle, calls.last().owner)
            assertEquals(owner, calls[1].owner)
            assertEquals(Opcodes.INVOKESPECIAL, calls[1].opcode)
            assertEquals(descriptor, calls[1].desc)
            assertEquals(listOf(stage), wrapper.instructions.toArray().filterIsInstance<LdcInsnNode>().map { it.cst })
            assertFalse(original.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.owner == lifecycle })
            // Resolving methods also asks the JVM to verify the emitted wrappers, not only ASM to read them.
            assertTrue(Class.forName(owner.replace('/', '.')).declaredMethods.any { it.name == original.name })
        }
        val collectScene = classAt("de/fabmax/kool/scene/Scene").methods.single { it.name.startsWith("collectScene") }
        assertTrue(collectScene.desc.contains("kotlin/coroutines/Continuation"))
        assertFalse(collectScene.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.owner == lifecycle },
            "Suspending callbacks must not be recorded as completed scene collections")
    }

    @Test
    fun `native BGRA image statistics use the same four byte branch as RGBA`() {
        val method = klass("ImageVk").methods.single { it.name == "getBytesPerPx" }
        val switch = method.instructions.toArray().filterIsInstance<LookupSwitchInsnNode>().single()
        val rgba = switch.labels[switch.keys.indexOf(VK_FORMAT_R8G8B8A8_UNORM)]
        assertTrue(VK_FORMAT_B8G8R8A8_UNORM in switch.keys)
        assertSame(rgba, switch.labels[switch.keys.indexOf(VK_FORMAT_B8G8R8A8_UNORM)])
        val firstInstruction = generateSequence(rgba.next) { it.next }.first { it.opcode >= 0 }
        assertEquals(Opcodes.ICONST_4, firstInstruction.opcode)
    }

    @Test
    fun `MixedBuffer uploads use bytes while numeric buffers use elements`() {
        val node = klass("GrowingBufferVk")
        val writes = node.methods.filter { it.name == "writeData" }
        assertEquals(3, writes.size)
        writes.forEach { method ->
            val instructions = method.instructions.toArray()
            assertEquals(!method.desc.contains("MixedBuffer"), instructions.any { it.opcode == Opcodes.LMUL })
            assertTrue(instructions.filterIsInstance<MethodInsnNode>().any {
                it.owner == "io/github/rwx/kool/vulkan/VulkanUploads" && it.name == "writeData"
            })
            assertFalse(instructions.filterIsInstance<MethodInsnNode>().any { it.name == "createBuffer" })
        }
    }

    @Test
    fun `texture transfers and lifecycle are installed in actual runtime classes`() {
        val load = klass("TextureLoaderVk").methods.single { it.name == "loadTexture" && it.desc.contains("MipMapping") }
        val calls = load.instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertTrue(calls.any { it.owner == "io/github/rwx/kool/vulkan/VulkanUploads" && it.name == "loadTexture" })
        assertFalse(calls.any { it.name == "vkQueueWaitIdle" })
        val encoder = klass("PassEncoderState")
        listOf("beginFrame", "endFrame").forEach { name ->
            assertTrue(encoder.methods.single { it.name == name }.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
                it.owner == "io/github/rwx/kool/vulkan/VulkanFrameLifecycle"
            })
        }
    }

    @Test
    fun `graphics pipelines use shared native cache and textures preflight before passes`() {
        val device = klass("DeviceKt")
        assertTrue(device.methods.flatMap { it.instructions.toArray().toList() }.filterIsInstance<MethodInsnNode>().any {
            it.owner == "io/github/rwx/kool/vulkan/VulkanPipelineCache" && it.name == "vkCreateGraphicsPipelines"
        })
        assertTrue(klass("DrawPipelineVk").methods.single { it.name == "updateGeometry" }.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
            it.owner == "io/github/rwx/kool/vulkan/VulkanUploads" && it.name == "prepareTextures"
        })
    }

    @Test
    fun `native memory deletion uses GPU fences instead of render frame counts`() {
        val manager = klass("MemoryManager")
        assertTrue("io/github/rwx/kool/vulkan/VulkanNativeMemoryAccess" in manager.interfaces)
        listOf("Buffer", "Image").forEach { type ->
            assertTrue(manager.methods.any { it.name == "rwxFree${type}Immediate" })
            val wrapper = manager.methods.single { it.name == "free$type" }
            assertTrue(wrapper.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
                it.owner == "io/github/rwx/kool/vulkan/VulkanNativeMemoryRetirement"
            })
        }
        val enqueue = klass("ReleaseQueue").methods.single { it.name == "enqueue" }
        assertTrue(enqueue.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
            it.owner == "io/github/rwx/kool/vulkan/VulkanFrameLifecycle" && it.name == "retireDriverTask"
        })
        assertTrue(manager.methods.single { it.name == "doRelease" }.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
            it.name == "allocatorReleased"
        })
    }

    @Test
    fun `presentation metrics only record returned presentation status`() {
        val present = klass("Swapchain").methods.single { it.name == "presentNextImage" }
        val calls = present.instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertTrue(calls.any { it.owner == "io/github/rwx/kool/vulkan/VulkanFrameLifecycle" && it.name == "presented" && it.desc == "(Z)V" })
        assertTrue(calls.any { it.name == "measuredQueuePresent" && it.desc == "(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I" })
        assertTrue(klass("KoolVkExtensionsKt").methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
            .any { it.name == "measuredQueueSubmit" && it.desc == "(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo;J)I" })
        val end = klass("PassEncoderState").methods.single { it.name == "endFrame" }
        assertFalse(end.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name == "presented" })
        assertTrue(end.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name == "measuredQueueSubmit" })
    }

    @Test
    fun `failed native waits cannot be mistaken for completed GPU work`() {
        assertFailsWith<IllegalStateException> { VulkanFrameLifecycle.checkedFenceWait(-4) }
        assertFailsWith<IllegalStateException> { VulkanFrameLifecycle.checkedDeviceIdle(-4) }
        VulkanFrameLifecycle.checkedFenceWait(0)
        val acquire = klass("Swapchain").methods.single { it.name == "acquireNextImage" }
        assertTrue(acquire.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name == "checkedFenceWait" })
        assertTrue(acquire.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name == "measuredFenceWait" })
        assertTrue(acquire.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name == "measuredAcquireImage" })
        val idle = klass("Device").methods.single { it.name == "waitForIdle" }
        assertTrue(idle.instructions.toArray().filterIsInstance<MethodInsnNode>().any { it.name == "checkedDeviceIdle" })
    }
}
