package io.github.rwx.render.canvas

import de.fabmax.kool.util.MsdfFont
import java.util.Collections

/** CPU frame packets carry font version tokens, never MsdfFont / Texture2d instances. */
internal data class KoolCanvasFontSnapshot(val baseVersion: Long?, val keyedVersions: Map<String, Long>) {
    val versions: Set<Long> = (keyedVersions.values + listOfNotNull(baseVersion)).toSet()
}

object KoolCanvasFontRegistry {
    private data class Metrics(val lineHeight: Float, val advances: Map<Char, Float>, val digitAdvance: Float)
    private data class Publication(val snapshot: KoolCanvasFontSnapshot, val base: Metrics?, val keyed: Map<String, Metrics>)
    private data class FontOwner(val font: MsdfFont, var references: Int = 1)
    private val gate = Any()
    private val renderFonts = mutableMapOf<Long, FontOwner>()
    private var nextVersion = 0L
    @Volatile
    private var publication = Publication(KoolCanvasFontSnapshot(null, emptyMap()), null, emptyMap())
    private val renderSnapshot = ThreadLocal<KoolCanvasFontSnapshot?>()

    val base: MsdfFont get() = fontForVersion(publication.snapshot.baseVersion) ?: defaultFont()

    /** Called by the frontend font loader; CPU metrics are detached before atomic publication. */
    fun installBaseFont(font: MsdfFont) = synchronized(gate) {
        val version = register(font)
        val current = publication
        publish(Publication(current.snapshot.copy(baseVersion = version), metrics(font), current.keyed))
    }

    fun installFont(key: String, font: MsdfFont) = synchronized(gate) {
        val version = register(font)
        val current = publication
        publish(Publication(current.snapshot.copy(keyedVersions = Collections.unmodifiableMap(current.snapshot.keyedVersions + (key to version))),
            current.base, Collections.unmodifiableMap(current.keyed + (key to metrics(font)))))
    }

    fun clearInstalledFonts() = synchronized(gate) {
        publish(Publication(publication.snapshot.copy(keyedVersions = emptyMap()), publication.base, emptyMap()))
    }

    /** Renderer only. Derived fonts have private scale fields; the shared font owner is never scaled. */
    fun font(sizePts: Float, key: String? = null): MsdfFont {
        return selectedFont(key).derive(sizePts.coerceAtLeast(1f))
    }

    /** Cache belongs to one renderer; public font() continues to return a private mutable view. */
    internal fun font(sizePts: Float, key: String?, views: KoolCanvasRenderFonts): MsdfFont =
        views.font(selectedFont(key), sizePts.coerceAtLeast(1f))

    private fun selectedFont(key: String?): MsdfFont {
        val snapshot = renderSnapshot.get() ?: publication.snapshot
        return fontForVersion(key?.let(snapshot.keyedVersions::get) ?: snapshot.baseVersion) ?: defaultFont()
    }

    /** Pins font versions once per CPU resource lease. This only changes CPU reference counters. */
    internal fun snapshot(): KoolCanvasFontSnapshot = synchronized(gate) {
        publication.snapshot.also { snapshot -> snapshot.versions.forEach { checkNotNull(renderFonts[it]).references++ } }
    }

    internal fun releaseSnapshot(snapshot: KoolCanvasFontSnapshot) = synchronized(gate) {
        snapshot.versions.forEach { version ->
            val owner = checkNotNull(renderFonts[version])
            check(--owner.references >= 0)
            if (owner.references == 0) renderFonts.remove(version)
        }
        // Atlas textures are externally owned by UIFonts / the Kool context, not by a packet.
        // Dropping a token never releases or mutates a GPU object from the recording thread.
    }

    internal fun <T> withSnapshot(snapshot: KoolCanvasFontSnapshot, block: () -> T): T {
        val previous = renderSnapshot.get()
        renderSnapshot.set(snapshot)
        return try { block() } finally { renderSnapshot.set(previous) }
    }

    /** CPU metrics only. No default font initialization or GPU handle access is possible here. */
    fun lineHeight(sizePts: Float, key: String? = null): Float {
        val size = sizePts.coerceAtLeast(1f)
        val current = publication
        val metrics = key?.let(current.keyed::get) ?: current.base
        return metrics?.let { (it.lineHeight * size).coerceAtLeast(size) } ?: size
    }

    fun textWidth(text: String, sizePts: Float, key: String? = null): Float {
        if (text.isEmpty()) return 0f
        val size = sizePts.coerceAtLeast(1f)
        val current = publication
        val metrics = key?.let(current.keyed::get) ?: current.base ?: return text.length * size * FALLBACK_GLYPH_WIDTH
        var line = 0f
        var longest = 0f
        for (char in text) {
            if (char == '\n') { longest = maxOf(longest, line); line = 0f }
            else line += (if (char.isDigit()) metrics.digitAdvance else metrics.advances[char] ?: 0f) * size
        }
        longest = maxOf(longest, line)
        return longest.takeIf { it > 0f } ?: (text.length * size)
    }

    private fun register(font: MsdfFont): Long {
        val version = ++nextVersion
        renderFonts[version] = FontOwner(font.copy())
        return version
    }
    private fun publish(next: Publication) {
        val removed = publication.snapshot.versions - next.snapshot.versions
        publication = next
        removed.forEach { version ->
            val owner = checkNotNull(renderFonts[version])
            if (--owner.references == 0) renderFonts.remove(version)
        }
    }
    private fun metrics(font: MsdfFont): Metrics = Metrics(font.data.meta.metrics.lineHeight,
        Collections.unmodifiableMap(font.data.glyphMap.mapValues { it.value.advance }), font.data.maxWidthDigit?.advance ?: 0f)
    private fun fontForVersion(version: Long?): MsdfFont? = synchronized(gate) { version?.let { renderFonts[it]?.font } }
    private fun defaultFont(): MsdfFont = runCatching { MsdfFont.DEFAULT_FONT }
        .getOrElse { error("Kool default font is unavailable before Kool runtime initialization") }
    private const val FALLBACK_GLYPH_WIDTH = 0.6f
}
