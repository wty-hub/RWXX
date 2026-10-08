package io.github.rwx.kool.vulkan

import org.lwjgl.vulkan.VK10.*
import kotlin.test.Test
import kotlin.test.assertEquals

class VulkanBufferAllocationDiagnosticsTest {
    @Test fun classifiesExactStagingAndUniformUsageWithoutMislabelingTransfersAsStaging() {
        assertEquals("staging-buffer-create", VulkanFrameLifecycle.bufferAllocationStage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT))
        assertEquals("uniform-buffer-create", VulkanFrameLifecycle.bufferAllocationStage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT))
        assertEquals("vertex-buffer-create", VulkanFrameLifecycle.bufferAllocationStage(
            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT))
        assertEquals("index-buffer-create", VulkanFrameLifecycle.bufferAllocationStage(
            VK_BUFFER_USAGE_INDEX_BUFFER_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT))
        assertEquals("other-buffer-create", VulkanFrameLifecycle.bufferAllocationStage(
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT))
    }
}
