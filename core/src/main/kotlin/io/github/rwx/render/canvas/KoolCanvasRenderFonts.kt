package io.github.rwx.render.canvas

import de.fabmax.kool.util.MsdfFont

/** Renderer-private derived views. Never published to the game owner or another renderer. */
internal class KoolCanvasRenderFonts(private val capacity: Int = 64) {
    private class Entry(val source: MsdfFont, val size: Float, val view: MsdfFont)
    private val entries = arrayOfNulls<Entry>(capacity)
    private var cursor = 0
    private val reportReuse = System.getenv("RWX_FRAME_METRICS") != null
    internal var hits = 0L
        private set

    init { require(capacity > 0) }

    fun font(source: MsdfFont, size: Float): MsdfFont {
        for (entry in entries) if (entry != null && entry.source === source && entry.size == size) {
            // derive() deliberately resets scale to 1 in Kool. Do not inherit source.scale.
            check(entry.view.scale == 1f) { "A renderer-private font view was mutated" }
            hits++
            if (hits == 1L && reportReuse) println("RWXPreparedCanvasText reuseConfirmed=true")
            return entry.view
        }
        val derived = source.derive(size)
        entries[cursor] = Entry(source, size, derived)
        cursor = (cursor + 1) % capacity
        return derived
    }

    fun clear() { entries.fill(null); cursor = 0 }
}
