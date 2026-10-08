package io.github.rwx.kool

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputPublicationFenceTest {
    @Test fun requestsMustAllBeAdoptedBeforePublicationAcknowledgesThem() {
        val fence = InputPublicationFence()
        val first = fence.request()
        val second = fence.request()
        fence.adopted(second)
        fence.published()
        assertFalse(fence.isPublished(first))
        assertFalse(fence.isPublished(second))
        fence.adopted(first)
        fence.published()
        assertTrue(fence.isPublished(second))
    }
    @Test fun adoptionAloneDoesNotAcknowledgeAPicture() {
        val fence = InputPublicationFence()
        val first = fence.request()
        fence.adopted(first)
        assertFalse(fence.isPublished(first))
        fence.published()
        assertTrue(fence.isPublished(first))
        val second = fence.request()
        assertFalse(fence.isPublished(second))
        fence.published()
        assertFalse(fence.isPublished(second))
        fence.adopted(second)
        fence.adopted(first)
        fence.published()
        assertTrue(fence.isPublished(second))
    }
}
