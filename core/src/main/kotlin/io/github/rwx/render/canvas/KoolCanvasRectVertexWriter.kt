package io.github.rwx.render.canvas

import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.scene.geometry.IndexedVertexList
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.MixedBuffer

/** Writes only the existing untransformed, unclipped filled-rectangle run's fixed vertex layout. */
internal object KoolCanvasRectVertexWriter {
    private val layout = VertexLayouts.PositionNormalColor
    private val strideBytes = layout.structSize
    private val positionOffset = layout.position.byteOffset
    private val normalOffset = layout.normal.byteOffset
    private val colorOffset = layout.color.byteOffset

    /** False leaves all geometry state untouched, so the caller retains Kool's exact growth path. */
    fun tryAppend(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>,
        rect: KoolCanvasRect,
        viewportHalfWidth: Float,
        viewportHalfHeight: Float,
        color: Color,
    ): Boolean {
        val data = geometry.vertexData
        if (geometry.layout !== layout || data.strideBytes != strideBytes || data.remaining < 4) return false
        val base = geometry.numVertices
        // The public setter extends both limits and marks one vertex modification. The remaining
        // three increments retain exactly the four addVertex modifications of the original path.
        geometry.numVertices = base + 4
        val buffer = data.buffer
        val left = rect.left - viewportHalfWidth
        val right = rect.right - viewportHalfWidth
        val top = viewportHalfHeight - rect.top
        val bottom = viewportHalfHeight - rect.bottom
        write(buffer, base * strideBytes, left, top, color)
        write(buffer, (base + 1) * strideBytes, right, top, color)
        write(buffer, (base + 2) * strideBytes, right, bottom, color)
        write(buffer, (base + 3) * strideBytes, left, bottom, color)
        geometry.incrementModCount()
        geometry.incrementModCount()
        geometry.incrementModCount()
        // Public put/get blocks may retain the shared view. Original addVertex leaves it on the
        // fourth vertex; use the public getter to preserve that cursor without internal access.
        data.get(base + 3) { }
        // Keep index growth, buffer position and its six modification increments in Kool's API.
        geometry.addTriIndices(base, base + 1, base + 2)
        geometry.addTriIndices(base, base + 2, base + 3)
        return true
    }

    private fun write(buffer: MixedBuffer, base: Int, x: Float, y: Float, color: Color) {
        var offset = base + positionOffset
        buffer.setFloat32(offset, x)
        buffer.setFloat32(offset + 4, y)
        buffer.setFloat32(offset + 8, 0f)
        offset = base + normalOffset
        buffer.setFloat32(offset, 0f)
        buffer.setFloat32(offset + 4, 0f)
        buffer.setFloat32(offset + 8, 1f)
        offset = base + colorOffset
        buffer.setFloat32(offset, color.r)
        buffer.setFloat32(offset + 4, color.g)
        buffer.setFloat32(offset + 8, color.b)
        buffer.setFloat32(offset + 12, color.a)
    }
}
