package io.github.rwx.render.canvas

import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.MsdfFontData
import de.fabmax.kool.util.TextMetrics
import kotlin.math.round

/** Conservative viewport rejection using the same glyph quads and rounded origin as MeshBuilder. */
internal class KoolCanvasTextVisibility(private val capacity: Int = 2048) {
    private data class Key(var data: MsdfFontData, var size: Float, var scale: Float,
                           var italic: Float, var text: String)
    internal data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float,
                               val baselineWidth: Float)
    private val entries = LinkedHashMap<Key, Bounds>(128, .75f, true)
    private var probe: Key? = null
    internal val size get() = entries.size

    init { require(capacity > 0) }

    fun clear() { entries.clear(); probe = null }

    /** Store only bounds, not vertex arrays, atlas slices or meshes for invisible labels. */
    internal fun bounds(font: MsdfFont, text: String): Bounds {
        val key = (probe ?: Key(font.data, font.sizePts, font.scale, font.italic, text).also { probe = it }).apply {
            data = font.data; size = font.sizePts; scale = font.scale; italic = font.italic; this.text = text
        }
        entries[key]?.let { return it }
        var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        val scale = font.scale * font.sizePts
        var advanced = 0f; var previous = 0
        // MeshBuilder.renderMsdfFont defaults: same-width digits, downward Y, rounded baseline.
        // Preserve its float operation order, including italic overhang and the final yBot + h.
        for (character in text) {
            val glyph = font.data.glyphMap[character] ?: continue
            var advance = glyph.advance
            if (character.isDigit()) {
                val delta = (font.data.maxWidthDigit?.advance ?: advance) - advance
                advance += delta * .5f
                advanced += delta * .5f
            } else {
                font.data.kerning[(previous shl 16) or character.code]?.let { advanced += it }
            }
            previous = character.code
            val yTop = -glyph.planeBounds.top * scale
            val yBottom = -glyph.planeBounds.bottom * scale
            val height = yTop - yBottom
            val x = (advanced + glyph.planeBounds.left) * scale
            val width = (glyph.planeBounds.right - glyph.planeBounds.left) * scale
            left = minOf(left, x, x + width, x - height * font.italic, x + width - height * font.italic)
            right = maxOf(right, x, x + width, x - height * font.italic, x + width - height * font.italic)
            top = minOf(top, yBottom, yBottom + height)
            bottom = maxOf(bottom, yBottom, yBottom + height)
            advanced += advance
        }
        val bounds = Bounds(left, top, right, bottom, font.textDimensions(text, TextMetrics()).baselineWidth)
        if (entries.size == capacity) entries.iterator().let { it.next(); it.remove() }
        entries[key.copy()] = bounds
        return bounds
    }

    fun outside(font: MsdfFont, command: KoolCanvasCommand.DrawText,
                viewport: KoolCanvasViewport): Boolean {
        val bounds = bounds(font, command.text)
        val originX = round(when (command.paint.textAlign) {
            KoolCanvasTextAlign.Left -> command.baseline.x
            KoolCanvasTextAlign.Center -> command.baseline.x - bounds.baselineWidth * .5f
            KoolCanvasTextAlign.Right -> command.baseline.x - bounds.baselineWidth
        })
        val originY = round(command.baseline.y)
        val transform = command.state.transform
        var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        for (corner in 0..3) {
            val x = originX + if (corner and 1 == 0) bounds.left else bounds.right
            val y = originY + if (corner and 2 == 0) bounds.top else bounds.bottom
            val tx = transform.scaleX * x + transform.skewX * y + transform.translateX
            val ty = transform.skewY * x + transform.scaleY * y + transform.translateY
            if (!tx.isFinite() || !ty.isFinite()) return false
            left = minOf(left, tx); right = maxOf(right, tx)
            top = minOf(top, ty); bottom = maxOf(bottom, ty)
        }
        // Keep a full pixel at every edge for float rounding and raster sample positions. Clip tests
        // remain in the original drawing path; italic/glow coverage is already inside these quads.
        return right < -1f || bottom < -1f || left > viewport.width + 1f || top > viewport.height + 1f
    }
}
