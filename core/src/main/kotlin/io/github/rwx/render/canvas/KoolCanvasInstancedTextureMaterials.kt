package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.BlendMode

/** Owned by one GPU target manager; each renderer holds one reference per immutable material. */
internal class KoolCanvasInstancedTextureMaterials {
    data class Key(val texture: Texture2d, val blend: BlendMode, val premultiplied: Boolean,
        val multipliesRgbByAlpha: Boolean)
    private class Entry(val shader: KoolCanvasInstancedTextureShader, var owners: Int)
    private val entries = HashMap<Key, Entry>()
    val size: Int get() = entries.size

    fun acquire(key: Key, create: () -> KoolCanvasInstancedTextureShader): KoolCanvasInstancedTextureShader {
        val entry = entries.getOrPut(key) { Entry(create(), 0) }
        entry.owners++
        return entry.shader
    }

    fun release(key: Key) {
        val entry = checkNotNull(entries[key]) { "Unbalanced map material ownership" }
        check(entry.owners > 0)
        if (--entry.owners == 0) entries.remove(key)
        // Mesh/pipeline ownership and GPU fences retain in-flight bindings. Removing a CPU lookup
        // entry never mutates a shader or releases a native pipeline or texture.
    }
}
