package io.github.rwx.render.canvas

import de.fabmax.kool.math.MutableVec2f
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.modules.ui2.UiTextVertexLayout
import de.fabmax.kool.scene.geometry.IndexedVertexList
import de.fabmax.kool.scene.geometry.MeshBuilder
import de.fabmax.kool.scene.geometry.TextProps
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.MsdfFontData
import de.fabmax.kool.util.TextMetrics

/** Font-local immutable geometry; the library remains the authority for shaping and winding. */
internal class KoolCanvasTextTemplates(private val maximumVertices: Int = 32_768, private val reuseLookups: Boolean = false) {
    private data class Key(var data: MsdfFontData, var size: Float, var scale: Float,
        var italic: Float, var weight: Float, var cutoff: Float, var glow: Color?, var text: String) {
        fun set(font: MsdfFont, text: String) {
            data = font.data; size = font.sizePts; scale = font.scale; italic = font.italic
            weight = font.weight; cutoff = font.cutoff; glow = font.glowColor; this.text = text
        }
    }
    // The probe never enters the map. Stored keys are detached copies on cache misses only.
    private var probe: Key? = null
    internal class Template(val vertices: FloatArray, val indices: IntArray, internal val metrics: TextMetrics? = null) {
        val vertexCount: Int get() = vertices.size / STRIDE
        companion object { const val STRIDE = 5 }
    }
    private val templates = LinkedHashMap<Key, Template>(128, .75f, true)
    private val scratch by lazy { IndexedVertexList(UiTextVertexLayout, initialSize = 128) }
    private val builder by lazy { MeshBuilder(scratch).apply { isInvertFaceOrientation = true } }
    private val position = MutableVec3f()
    private val uv = MutableVec2f()
    private var retainedVertices = 0
    internal val size: Int get() = templates.size

    fun geometry(font: MsdfFont, text: String): Template {
        val key = if (reuseLookups) (probe ?: Key(font.data, font.sizePts, font.scale, font.italic,
            font.weight, font.cutoff, font.glowColor, text).also { probe = it }).also { it.set(font, text) }
        else Key(font.data, font.sizePts, font.scale, font.italic, font.weight, font.cutoff, font.glowColor, text)
        templates[key]?.let { return it }
        builder.clear()
        builder.text(TextProps(font).apply { this.text = text; isYAxisUp = false })
        val vertices = FloatArray(scratch.numVertices * Template.STRIDE)
        for (index in 0 until scratch.numVertices) scratch.vertexData.get(index) { layout ->
            get(layout.position, position)
            get(layout.texCoord, uv)
            val offset = index * Template.STRIDE
            vertices[offset] = position.x; vertices[offset + 1] = position.y; vertices[offset + 2] = position.z
            vertices[offset + 3] = uv.x; vertices[offset + 4] = uv.y
        }
        val template = Template(vertices, IntArray(scratch.numIndices) { scratch.indices[it] },
            if (reuseLookups) font.textDimensions(text, TextMetrics()) else null)
        if (template.vertexCount <= maximumVertices) {
            val iterator = templates.entries.iterator()
            while (iterator.hasNext() && retainedVertices + template.vertexCount > maximumVertices) {
                retainedVertices -= iterator.next().value.vertexCount
                iterator.remove()
            }
            // Empty/missing-glyph strings must not grow an otherwise vertex-bounded cache without limit.
            if (templates.size >= 2048) {
                val oldest = templates.entries.iterator()
                retainedVertices -= oldest.next().value.vertexCount
                oldest.remove()
            }
            templates[if (reuseLookups) key.copy() else key] = template
            retainedVertices += template.vertexCount
        }
        return template
    }

    fun clear() { templates.clear(); probe = null; retainedVertices = 0 }
}
