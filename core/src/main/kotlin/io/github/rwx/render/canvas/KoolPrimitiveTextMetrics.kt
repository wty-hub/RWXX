package io.github.rwx.render.canvas

import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.MsdfFontData
import de.fabmax.kool.util.MsdfGlyph
import de.fabmax.kool.util.TextMetrics
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/** Renderer-thread-only lookup for the immutable glyph maps published with font versions. */
internal class KoolPrimitiveTextMetrics {
    // A value retains glyphs only, never its weak font-data key or an atlas texture.
    private val glyphLookups = WeakHashMap<MsdfFontData, GlyphLookup>()

    fun textDimensions(font: MsdfFont, text: String, enforceSameWidthDigits: Boolean = true): TextMetrics {
        val result = TextMetrics()
        val data = font.data
        val lookup = glyphLookups.getOrPut(data) { GlyphLookup(data.glyphMap) }
        val emScale = font.scale * font.sizePts
        var lineWidth = 0f
        // Preserve Kool 0.19.0 MsdfFont.textDimensions arithmetic, including width at newlines.
        result.baselineWidth = 0f
        result.height = font.lineHeight
        result.yBaseline = data.meta.metrics.ascender * emScale
        result.numLines = 1
        result.ascentPx = data.meta.metrics.ascender * emScale
        result.descentPx = data.meta.metrics.descender * emScale

        for (i in text.indices) {
            val c = text[i]
            if (c == '\n') {
                result.baselineWidth = max(result.width, lineWidth)
                result.height += font.lineHeight
                result.numLines++
                lineWidth = 0f
            } else {
                val glyph = if (c.isDigit() && enforceSameWidthDigits) {
                    data.maxWidthDigit
                } else {
                    lookup[c]
                } ?: continue
                lineWidth += glyph.advance * emScale
            }
        }
        result.baselineWidth = max(result.width, lineWidth)
        result.paddingStart = min(0f, font.italic) * emScale
        result.paddingEnd = max(0f, font.italic) * emScale
        return result
    }

    private class GlyphLookup(glyphMap: Map<Char, MsdfGlyph>) {
        private val pages = arrayOfNulls<Array<MsdfGlyph?>>(256)

        init {
            // Use the finished map: it already resolved duplicate and truncated Unicode keys.
            for ((char, glyph) in glyphMap) {
                val pageIndex = char.code ushr 8
                val page = pages[pageIndex] ?: arrayOfNulls<MsdfGlyph>(256).also { pages[pageIndex] = it }
                page[char.code and 255] = glyph
            }
        }

        operator fun get(char: Char): MsdfGlyph? = pages[char.code ushr 8]?.get(char.code and 255)
    }
}
