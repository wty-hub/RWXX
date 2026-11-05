package io.github.rwx.kool.vulkan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VulkanSpriteAtlasPageIdentityTest {
    @Test fun `filter and uploaded frame do not create a different atlas page`() {
        val names = listOf("rwx-sprite-atlas-3-7-72-Nearest", "rwx-sprite-atlas-3-7-72-Linear",
            "rwx-sprite-atlas-3-7-73-Nearest")
        assertEquals(listOf(3L to 7L), names.map(VulkanSpriteAtlasOracle::atlasPageIdentity).distinct())
    }

    @Test fun `both owner and page serial distinguish actual pages`() {
        val names = listOf("rwx-sprite-atlas-3-7-72-Nearest", "rwx-sprite-atlas-3-8-72-Nearest",
            "rwx-sprite-atlas-4-7-72-Nearest")
        assertEquals(3, names.map(VulkanSpriteAtlasOracle::atlasPageIdentity).distinct().size)
    }

    @Test fun `unexpected binding names cannot satisfy page coverage`() {
        listOf("sprite-3-7-72-Nearest", "rwx-sprite-atlas-3-7-72-Nearest-extra",
            "rwx-sprite-atlas-0-7-72-Nearest", "rwx-sprite-atlas-3--7-72-Nearest",
            "rwx-sprite-atlas-3-7-72-Unknown", "rwx-sprite-atlas-3-7-999999999999999999999-Linear").forEach {
            assertFailsWith<IllegalArgumentException> { VulkanSpriteAtlasOracle.atlasPageIdentity(it) }
        }
    }
}
