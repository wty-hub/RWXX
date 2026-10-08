package io.github.rwx.kool.vulkan

import de.fabmax.kool.pipeline.backend.vk.ImageVk
import org.lwjgl.vulkan.VK13.*
import kotlin.test.Test
import kotlin.test.assertEquals

class VulkanSampledImageStagesTest {
    @Test fun `sampled images synchronize vertex and fragment reads in both directions`() {
        val stages = VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
        assertEquals(stages, ImageVk.srcStageMaskForLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))
        assertEquals(stages, ImageVk.dstStageMaskForLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))
        assertEquals(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, ImageVk.dstStageMaskForLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL))
    }
}
