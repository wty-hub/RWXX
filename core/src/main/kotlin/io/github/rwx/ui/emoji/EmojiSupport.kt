package io.github.rwx.ui.emoji

/**
 * Shared emoji helpers for Kool UI.
 *
 * The baked CJK MSDF atlas only covers Latin + GB2312, so SMP emoji (🍎) and many dingbats
 * render as zero-width gaps. Platform rasterizers draw those glyphs into RGBA textures instead.
 */
sealed class TextRun {
    data class Plain(val value: String) : TextRun()
    data class Emoji(val value: String, val codePoints: IntArray) : TextRun() {
        override fun equals(other: Any?): Boolean =
            other is Emoji && value == other.value

        override fun hashCode(): Int = value.hashCode()
    }
}

fun String.containsEmoji(): Boolean {
    var index = 0
    while (index < length) {
        val end = emojiClusterEndExclusive(index)
        if (end != null) return true
        index += Character.charCount(codePointAt(index))
    }
    return false
}

/**
 * Splits [this] into plain text and individual emoji grapheme clusters.
 *
 * Adjacent emoji (🚀🚀, 🇨🇳🇨🇳) become separate runs so each can be drawn at the text size
 * instead of being squashed into one image slot. ZWJ sequences (👨‍👩‍👧) and flag pairs stay
 * as a single cluster.
 */
fun String.toTextRuns(): List<TextRun> {
    if (!containsEmoji()) return listOf(TextRun.Plain(this))
    val runs = mutableListOf<TextRun>()
    val plain = StringBuilder()
    var index = 0
    while (index < length) {
        val clusterEnd = emojiClusterEndExclusive(index)
        if (clusterEnd != null) {
            if (plain.isNotEmpty()) {
                runs += TextRun.Plain(plain.toString())
                plain.clear()
            }
            val value = substring(index, clusterEnd)
            runs += TextRun.Emoji(value, value.codePoints().toArray())
            index = clusterEnd
        } else {
            val cp = codePointAt(index)
            plain.appendCodePoint(cp)
            index += Character.charCount(cp)
        }
    }
    if (plain.isNotEmpty()) {
        runs += TextRun.Plain(plain.toString())
    }
    return runs
}

/**
 * Exclusive end index of an emoji grapheme cluster starting at [start], or null if [start]
 * is not the beginning of an emoji cluster.
 */
internal fun String.emojiClusterEndExclusive(start: Int): Int? {
    if (start < 0 || start >= length) return null
    val first = codePointAt(start)
    var index = start

    if (first.isRegionalIndicator()) {
        index += Character.charCount(first)
        if (index < length && codePointAt(index).isRegionalIndicator()) {
            index += Character.charCount(codePointAt(index))
        }
        return index
    }

    if (first.isKeycapBase()) {
        var cursor = index + Character.charCount(first)
        if (cursor < length && codePointAt(cursor) == VARIATION_SELECTOR_16) {
            cursor += Character.charCount(VARIATION_SELECTOR_16)
        }
        if (cursor < length && codePointAt(cursor) == COMBINING_ENCLOSING_KEYCAP) {
            return cursor + Character.charCount(COMBINING_ENCLOSING_KEYCAP)
        }
        return null
    }

    if (!first.isEmojiCodePoint()) return null

    index += Character.charCount(first)
    index = consumeEmojiModifiers(index)

    while (index < length && codePointAt(index) == ZERO_WIDTH_JOINER) {
        val afterZwj = index + Character.charCount(ZERO_WIDTH_JOINER)
        val componentEnd = emojiComponentEndExclusive(afterZwj) ?: break
        index = componentEnd
    }
    return index
}

/** One ZWJ component: RI pair, or emoji base + optional skin tone / VS16 (no further ZWJ). */
private fun String.emojiComponentEndExclusive(start: Int): Int? {
    if (start >= length) return null
    val cp = codePointAt(start)
    var index = start
    if (cp.isRegionalIndicator()) {
        index += Character.charCount(cp)
        if (index < length && codePointAt(index).isRegionalIndicator()) {
            index += Character.charCount(codePointAt(index))
        }
        return index
    }
    if (cp.isKeycapBase()) {
        var cursor = index + Character.charCount(cp)
        if (cursor < length && codePointAt(cursor) == VARIATION_SELECTOR_16) {
            cursor += Character.charCount(VARIATION_SELECTOR_16)
        }
        if (cursor < length && codePointAt(cursor) == COMBINING_ENCLOSING_KEYCAP) {
            return cursor + Character.charCount(COMBINING_ENCLOSING_KEYCAP)
        }
        return null
    }
    if (!cp.isEmojiCodePoint()) return null
    index += Character.charCount(cp)
    return consumeEmojiModifiers(index)
}

private fun String.consumeEmojiModifiers(start: Int): Int {
    var index = start
    if (index < length && codePointAt(index).isSkinTone()) {
        index += Character.charCount(codePointAt(index))
    }
    if (index < length && codePointAt(index) == VARIATION_SELECTOR_16) {
        index += Character.charCount(VARIATION_SELECTOR_16)
    }
    return index
}

fun Int.isEmojiCodePoint(): Boolean =
    this in 0x1F300..0x1FAFF || // Misc Symbols and Pictographs .. Symbols Extended-A
            this in 0x1F000..0x1F02F || // Mahjong / Domino
            this in 0x1F0A0..0x1F0FF || // Playing cards
            this in 0x1F100..0x1F1FF || // Enclosed alphanumerics / flags
            this in 0x2600..0x27BF || // Misc symbols, dingbats
            this in 0x2300..0x23FF || // Misc technical (⏳ etc.)
            this in 0x2B00..0x2BFF || // Misc arrows/symbols
            // BMP emoji bases outside the blocks above (Unicode 17 Emoji property).
            this == 0x00A9 || this == 0x00AE ||
            this == 0x203C || this == 0x2049 ||
            this == 0x2122 || this == 0x2139 ||
            this in 0x2194..0x2199 || this in 0x21A9..0x21AA ||
            this == 0x24C2 || this in 0x25AA..0x25AB ||
            this == 0x25B6 || this == 0x25C0 || this in 0x25FB..0x25FE ||
            this in 0x2934..0x2935 ||
            // Variation selectors only modify a base; they cannot start an emoji run.
            this == COMBINING_ENCLOSING_KEYCAP ||
            this == 0x3030 ||
            this == 0x303D ||
            this == 0x3297 ||
            this == 0x3299

fun Int.isEmojiJoiner(): Boolean =
    this == ZERO_WIDTH_JOINER ||
            this in 0xFE00..0xFE0F ||
            this == COMBINING_ENCLOSING_KEYCAP ||
            this.isSkinTone()

fun Int.isRegionalIndicator(): Boolean = this in 0x1F1E6..0x1F1FF

fun Int.isSkinTone(): Boolean = this in 0x1F3FB..0x1F3FF

fun Int.isKeycapBase(): Boolean =
    this == '#'.code ||
            this == '*'.code ||
            this in '0'.code..'9'.code

private const val ZERO_WIDTH_JOINER: Int = 0x200D
private const val VARIATION_SELECTOR_16: Int = 0xFE0F
private const val COMBINING_ENCLOSING_KEYCAP: Int = 0x20E3

data class EmojiRaster(
    val width: Int,
    val height: Int,
    /** Tight RGBA8 row-major pixels. */
    val rgba: ByteArray,
)

interface EmojiRasterizer {
    fun rasterize(emoji: String, sizePx: Int): EmojiRaster?
}

object EmojiRasterizerBridge {
    @Volatile
    private var rasterizer: EmojiRasterizer? = null

    fun install(rasterizer: EmojiRasterizer) {
        this.rasterizer = rasterizer
    }

    fun uninstall(rasterizer: EmojiRasterizer) {
        if (this.rasterizer === rasterizer) {
            this.rasterizer = null
        }
    }

    fun rasterize(emoji: String, sizePx: Int): EmojiRaster? =
        rasterizer?.rasterize(emoji, sizePx.coerceIn(12, 128))

    fun isAvailable(): Boolean = rasterizer != null

    /** Allows CPU canvas caches to distinguish platform rasterizers across host replacement. */
    fun currentRasterizer(): EmojiRasterizer? = rasterizer
}
