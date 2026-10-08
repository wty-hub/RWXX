package io.github.rwx.render.canvas

import de.fabmax.kool.math.Vec2f
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.util.Float32Buffer

/** Render-owner cache of font-local vertices. Every uploaded generation is immutable until retired.
 * A whole label then needs one small instance, rather than four new vertices per character. */
internal class KoolCanvasLabelGeometry(private val capacity: Int = 65_536) {
    init { require(capacity in 16..1_048_576 && capacity % 4 == 0) }
    private val owner = nextOwner.incrementAndGet()
    internal data class Slice(val firstVertex: Int, val vertexCount: Int)
    private data class Entry(val template: KoolCanvasTextTemplates.Template, var slice: Slice, var lastUsed: Long)
    private val entries = java.util.IdentityHashMap<KoolCanvasTextTemplates.Template, Entry>()
    private val vertices = FloatArray(capacity * 4)
    private var cursor = 0
    private var dirty = false
    private var serial = 0L
    var texture: Texture2d? = null
        private set
    var dimensions = Vec2f(256f, 1f)
        private set

    fun beginFrame(frame: Long) {
        if (cursor < capacity * 3 / 4 && entries.size < 4096) return
        // Compact before any of this frame's instances are written. Current-frame offsets can never
        // change midway through recording; obsolete GPU images remain held by the fence retirement.
        val retained = entries.values.sortedByDescending { it.lastUsed }
        entries.clear(); cursor = 0
        for (entry in retained) {
            if (frame - entry.lastUsed > 60 || cursor + entry.slice.vertexCount > capacity / 2 || entries.size >= 2048) continue
            entry.slice = append(entry.template)
            entries[entry.template] = entry
        }
        dirty = true
    }

    fun slice(template: KoolCanvasTextTemplates.Template, frame: Long): Slice? {
        entries[template]?.let { it.lastUsed = frame; return it.slice }
        if (template.vertexCount == 0 || template.vertexCount > 512 ||
            cursor + template.vertexCount > capacity || entries.size >= 4096) return null
        return append(template).also { entries[template] = Entry(template, it, frame) }
    }

    private fun append(template: KoolCanvasTextTemplates.Template): Slice {
        val result = Slice(cursor, template.vertexCount)
        repeat(template.vertexCount) { index ->
            val source = index * KoolCanvasTextTemplates.Template.STRIDE
            val destination = (cursor + index) * 4
            vertices[destination] = template.vertices[source]
            vertices[destination + 1] = template.vertices[source + 1]
            vertices[destination + 2] = template.vertices[source + 3]
            vertices[destination + 3] = template.vertices[source + 4]
        }
        cursor += template.vertexCount
        dirty = true
        return result
    }

    fun finishFrame(): Boolean {
        if (!dirty) return false
        val width = 256; val height = maxOf(1, (cursor + width - 1) / width)
        val data = Float32Buffer(width * height * 4)
        repeat(width * height * 4) { data[it] = if (it < cursor * 4) vertices[it] else 0f }
        val name = "rwx-label-geometry-$owner-${++serial}"
        val old = texture
        texture = Texture2d(data = BufferedImageData2d(data, width, height, TexFormat.RGBA_F32, name),
            mipMapping = MipMapping.Off, samplerSettings = SamplerSettings().clamped().nearest().noAnisotropy(), name = name)
        dimensions = Vec2f(width.toFloat(), height.toFloat())
        dirty = false
        old?.let { KoolCanvasGpuRetirement.retire { it.release() } }
        return true
    }

    fun clear() {
        entries.clear(); cursor = 0; dirty = false
        texture?.let { KoolCanvasGpuRetirement.retire { it.release() } }; texture = null
    }

    internal fun snapshot() = Triple(cursor, entries.size, serial)
    companion object { private val nextOwner = java.util.concurrent.atomic.AtomicLong() }
}
