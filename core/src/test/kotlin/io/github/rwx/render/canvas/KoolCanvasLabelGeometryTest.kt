package io.github.rwx.render.canvas

import kotlin.test.*

class KoolCanvasLabelGeometryTest {
    private fun template(vertices: Int) = KoolCanvasTextTemplates.Template(
        FloatArray(vertices * 5) { it / 10f }, IntArray(vertices / 4 * 6))

    @Test fun `a slice remains stable until the next frame and cache hits do not append`() {
        val cache = KoolCanvasLabelGeometry(128)
        val first = template(12); val second = template(8)
        cache.beginFrame(1)
        val firstSlice = cache.slice(first, 1)!!
        assertEquals(0, firstSlice.firstVertex)
        assertEquals(12, cache.slice(second, 1)!!.firstVertex)
        assertEquals(firstSlice, cache.slice(first, 1))
        assertEquals(20, cache.snapshot().first)
        assertEquals(2, cache.snapshot().second)
    }

    @Test fun `full storage falls back and next frame compacts within its bound`() {
        val cache = KoolCanvasLabelGeometry(128)
        val templates = List(32) { template(4) }
        cache.beginFrame(1)
        templates.forEach { assertNotNull(cache.slice(it, 1)) }
        assertNull(cache.slice(template(4), 1))
        assertEquals(128, cache.snapshot().first)
        cache.beginFrame(2)
        assertEquals(64, cache.snapshot().first)
        repeat(16) { assertNotNull(cache.slice(template(4), 2)) }
        assertEquals(128, cache.snapshot().first)
        assertNull(cache.slice(template(4), 2))
    }

    @Test fun `expired geometry is discarded before recording offsets of a new frame`() {
        val cache = KoolCanvasLabelGeometry(32)
        repeat(6) { assertNotNull(cache.slice(template(4), 1)) }
        cache.beginFrame(62)
        assertEquals(0, cache.snapshot().first)
        assertEquals(0, cache.snapshot().second)
        assertEquals(0, cache.slice(template(8), 62)!!.firstVertex)
    }

    @Test fun `oversized or empty labels preserve the ordinary fallback path`() {
        val cache = KoolCanvasLabelGeometry()
        assertNull(cache.slice(template(0), 1))
        assertNull(cache.slice(template(516), 1))
        assertEquals(0, cache.snapshot().first)
    }
}
