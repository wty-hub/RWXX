package io.github.rwx.render.canvas

import io.github.rwx.ui.emoji.EmojiRasterizer
import io.github.rwx.ui.emoji.EmojiRasterizerBridge
import io.github.rwx.ui.emoji.TextRun
import io.github.rwx.ui.emoji.containsEmoji
import io.github.rwx.ui.emoji.toTextRuns
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * CPU-side mixed-text recording for legacy game text, which does not pass through EmojiAwareText.
 * No GPU handles cross the simulation/render boundary: images use the ordinary frozen texture path.
 */
internal class KoolCanvasEmojiText(
    private val textureStore: KoolCanvasTextureStore,
    private val maximumEntries: Int = 256,
) {
    private class Key(val source: EmojiRasterizer, val emoji: String, val size: Int) {
        override fun equals(other: Any?): Boolean = other is Key &&
            source === other.source && emoji == other.emoji && size == other.size
        override fun hashCode(): Int = (31 * System.identityHashCode(source) + emoji.hashCode()) * 31 + size
    }
    private class Entry(val texture: KoolCanvasTextureRef?, var usedInFrame: Boolean = false)
    private val entries = LinkedHashMap<Key, Entry>(32, .75f, true)
    private val prefix = "canvas-emoji-${instanceSerial.incrementAndGet()}"
    private var serial = 0L

    init { require(maximumEntries > 0) }

    fun beginFrame() { entries.values.forEach { it.usedInFrame = false } }

    fun clear() {
        entries.values.forEach { it.texture?.id?.let(textureStore::unregister) }
        entries.clear()
    }

    /** Null leaves the old, allocation-free MSDF path exactly as it was. */
    fun width(text: String, size: Float, fontKey: String?): Float? {
        val source = EmojiRasterizerBridge.currentRasterizer() ?: return null
        if (!text.containsEmoji()) return null
        return text.split('\n').maxOf { line -> lineWidth(line.toTextRuns(), size, fontKey, source) }
    }

    /** Returns false when the caller should record its original DrawText command. */
    fun draw(text: String, baseline: KoolCanvasPoint, paint: KoolCanvasPaint, buffer: KoolCanvasCommandBuffer): Boolean {
        val source = EmojiRasterizerBridge.currentRasterizer() ?: return false
        if (!text.containsEmoji()) return false
        val size = paint.textSize.coerceAtLeast(1f)
        val plainPaint = paint.copy(textAlign = KoolCanvasTextAlign.Left)
        // Preserve intrinsic emoji colors while applying the original text's opacity and blend mode.
        val emojiPaint = plainPaint.copy(color = KoolCanvasColor((paint.color.alpha shl 24) or 0x00ffffff),
            style = KoolCanvasPaintStyle.Fill, textureFilter = KoolCanvasTextureFilter.Linear, textureEffect = null)
        var y = baseline.y
        for (line in text.split('\n')) {
            val runs = line.toTextRuns().map { run ->
                val value = when (run) { is TextRun.Plain -> run.value; is TextRun.Emoji -> run.value }
                val entry = if (run is TextRun.Emoji) entry(source, value, size) else null
                val texture = entry?.texture
                if (entry?.texture != null) entry.usedInFrame = true
                val advance = texture?.let { size * it.width / it.height }
                    ?: KoolCanvasFontRegistry.textWidth(value, size, paint.typefaceKey)
                PlannedRun(value, texture, advance)
            }
            val width = runs.sumOf { it.advance.toDouble() }.toFloat()
            var x = baseline.x - when (paint.textAlign) {
                KoolCanvasTextAlign.Left -> 0f
                KoolCanvasTextAlign.Center -> width / 2f
                KoolCanvasTextAlign.Right -> width
            }
            for (run in runs) {
                val texture = run.texture
                if (texture == null) {
                    buffer.drawText(run.value, KoolCanvasPoint(x, y), plainPaint)
                } else {
                    val top = y - size * .85f
                    buffer.drawTexture(texture, KoolCanvasRect(x, top, x + run.advance, top + size), emojiPaint)
                }
                x += run.advance
            }
            y += KoolCanvasFontRegistry.lineHeight(size, paint.typefaceKey)
        }
        return true
    }

    private data class PlannedRun(val value: String, val texture: KoolCanvasTextureRef?, val advance: Float)

    private fun lineWidth(runs: List<TextRun>, size: Float, fontKey: String?, source: EmojiRasterizer): Float =
        runs.sumOf { run -> when (run) {
            is TextRun.Plain -> KoolCanvasFontRegistry.textWidth(run.value, size, fontKey).toDouble()
            is TextRun.Emoji -> entry(source, run.value, size)?.texture?.let { size * it.width / it.height }?.toDouble()
                ?: KoolCanvasFontRegistry.textWidth(run.value, size, fontKey).toDouble()
        } }.toFloat()

    private fun entry(source: EmojiRasterizer, emoji: String, size: Float): Entry? {
        val rasterSize = size.roundToInt().coerceIn(12, 96)
        val key = Key(source, emoji, rasterSize)
        entries[key]?.let { return it }
        if (entries.size >= maximumEntries) {
            val iterator = entries.entries.iterator()
            var evicted = false
            while (iterator.hasNext()) {
                val previous = iterator.next().value
                // A command from this frame may not have been frozen yet. Never evict its pixels.
                if (!previous.usedInFrame) {
                    previous.texture?.id?.let(textureStore::unregister)
                    iterator.remove()
                    evicted = true
                    break
                }
            }
            if (!evicted) return null
        }
        // A broken platform font or one unsupported cluster must not abort the game's whole frame.
        val raster = runCatching { source.rasterize(emoji, rasterSize) }.getOrNull()
        val texture = raster?.takeIf { it.width in 1..512 && it.height in 1..128 &&
            it.rgba.size.toLong() == it.width.toLong() * it.height * 4 }?.let { image ->
            val pixels = IntArray(image.width * image.height) { index ->
                val offset = index * 4
                ((image.rgba[offset + 3].toInt() and 255) shl 24) or
                    ((image.rgba[offset].toInt() and 255) shl 16) or
                    ((image.rgba[offset + 1].toInt() and 255) shl 8) or
                    (image.rgba[offset + 2].toInt() and 255)
            }
            val id = KoolCanvasTextureId("$prefix-${++serial}")
            textureStore.registerArgb(id, image.width, image.height, pixels)
            KoolCanvasTextureRef(id, image.width, image.height, hasAlpha = true, requiresOrderedAlpha = true)
        }
        return Entry(texture).also { entries[key] = it }
    }

    private companion object { val instanceSerial = AtomicLong() }
}
