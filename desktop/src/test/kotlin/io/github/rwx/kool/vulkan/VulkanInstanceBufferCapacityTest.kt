package io.github.rwx.kool.vulkan

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.VertexLayouts
import io.github.rwx.render.canvas.KoolCanvasAffineTextureInstanceLayout
import io.github.rwx.render.canvas.KoolCanvasTextureInstanceLayout
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

class VulkanInstanceBufferCapacityTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    @Test fun incrementalInstanceCountsReserveOnlyAtCpuCapacityBoundariesAndNeverChangeUploadLimits() {
        for (layout in listOf(KoolCanvasTextureInstanceLayout, KoolCanvasAffineTextureInstanceLayout)) {
            val instances = MeshInstanceList(layout, initialSize = 128)
            val stride = layout.structSize
            var nativeBytes = 128L * stride
            val reservations = mutableListOf<Long>()
            for (count in 1..600) {
                instances.addInstance { }
                val data = instances.instanceData
                val limit = data.buffer.limit
                val required = VulkanInstanceBufferCapacity.requiredCapacityBytes(nativeBytes, instances, true)
                if (required > nativeBytes) {
                    nativeBytes = required
                    reservations += required
                }
                assertEquals(count * stride, limit)
                assertEquals(limit, data.buffer.limit)
                assertEquals(count, data.limit)
                assertTrue(nativeBytes >= limit)
            }
            assertEquals(listOf(256L * stride, 512L * stride, 1024L * stride), reservations)
            val retained = instances.instanceData
            instances.clear()
            instances.addInstance { }
            assertSame(retained, instances.instanceData)
            assertEquals(nativeBytes, VulkanInstanceBufferCapacity.requiredCapacityBytes(nativeBytes, instances, true))
            assertEquals(stride, instances.instanceData.buffer.limit)
        }
    }

    @Test fun disabledCandidateAndOtherLayoutsLeaveExactNativeGrowthPolicyUntouched() {
        val texture = MeshInstanceList(KoolCanvasTextureInstanceLayout, initialSize = 128)
        repeat(129) { texture.addInstance { } }
        assertEquals(128L * 32, VulkanInstanceBufferCapacity.requiredCapacityBytes(128L * 32, texture, false))
        val other = MeshInstanceList(VertexLayouts.Position, initialSize = 256)
        assertEquals(128L, VulkanInstanceBufferCapacity.requiredCapacityBytes(128L, other, true))
    }

    @Test fun capacityMultiplicationUsesLongAndNeverShrinksExistingNativeStorage() {
        assertEquals(103079215056L, VulkanInstanceBufferCapacity.capacityBytes(Int.MAX_VALUE, 48))
        val texture = MeshInstanceList(KoolCanvasTextureInstanceLayout, initialSize = 128)
        assertEquals(1_000_000L, VulkanInstanceBufferCapacity.requiredCapacityBytes(1_000_000L, texture, true))
    }

    @Test fun actualRuntimeOverlayReservesOnlyInstancesAndRetainsOriginalUploadAndReleasePaths() {
        val growingOwner = "de/fabmax/kool/pipeline/backend/vk/GrowingBufferVk"
        val instancesOwner = "de/fabmax/kool/pipeline/backend/vk/InstancesVk"
        val helperOwner = "io/github/rwx/kool/vulkan/VulkanInstanceBufferCapacity"
        val growing = klass(growingOwner)
        assertTrue("io/github/rwx/kool/vulkan/VulkanGrowingBufferCapacityAccess" in growing.interfaces)
        val bridge = growing.methods.single { it.name == "rwxEnsureCapacity" && it.desc == "(J)V" }
        val bridgeCalls = bridge.instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(1, bridgeCalls.size)
        assertEquals(growingOwner, bridgeCalls.single().owner)
        assertEquals("checkSize", bridgeCalls.single().name)
        assertEquals(Opcodes.INVOKESPECIAL, bridgeCalls.single().opcode)
        assertEquals("(J)V", bridgeCalls.single().desc)
        val originalGrowth = growing.methods.single { it.name == "checkSize" }.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
        assertEquals(1, originalGrowth.count { it.name == "release" && it.owner.endsWith("/GpuBufferVk") })
        assertEquals(1, originalGrowth.count { it.name == "makeBuffer" && it.owner == growingOwner })

        val checkBuffers = klass(instancesOwner).methods.single { it.name == "checkBuffers" }
        val calls = checkBuffers.instructions.toArray().filterIsInstance<MethodInsnNode>()
        val routed = calls.single { it.owner == helperOwner && it.name == "writeData" }
        assertEquals(Opcodes.INVOKESTATIC, routed.opcode)
        assertEquals("(L$growingOwner;Lde/fabmax/kool/util/MixedBuffer;Lorg/lwjgl/vulkan/VkCommandBuffer;Lde/fabmax/kool/scene/MeshInstanceList;)V", routed.desc)
        assertTrue(calls.indexOfFirst { it.name == "isDirty" } < calls.indexOf(routed))
        val source = routed.previous as FieldInsnNode
        assertEquals(instancesOwner, source.owner)
        assertEquals("instances", source.name)
        assertFalse(calls.any { it.owner == growingOwner && it.name == "writeData" })
        assertFalse(klass("de/fabmax/kool/pipeline/backend/vk/GeometryVk").methods
            .flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }.any { it.owner == helperOwner })

        val upload = growing.methods.single { it.name == "writeData" && it.desc.contains("MixedBuffer") }
            .instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertTrue(upload.any { it.name == "getLimit" })
        assertFalse(upload.any { it.name == "getCapacity" })
        assertEquals(1, upload.count { it.owner == "io/github/rwx/kool/vulkan/VulkanUploads" && it.name == "writeData" })
        assertTrue(Class.forName(instancesOwner.replace('/', '.')).declaredMethods.any { it.name == "checkBuffers" })
        assertTrue(Class.forName(growingOwner.replace('/', '.')).declaredMethods.any { it.name == "rwxEnsureCapacity" })
    }

    private fun klass(owner: String): ClassNode =
        checkNotNull(javaClass.getResourceAsStream("/$owner.class")).use { stream ->
            ClassNode().also { ClassReader(stream).accept(it, 0) }
        }
}
