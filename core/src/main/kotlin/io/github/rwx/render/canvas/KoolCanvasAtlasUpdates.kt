package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.Texture2d
import java.nio.ByteBuffer

/** Immutable append to previously unused atlas texels, including its private guard pixels.
 * Published slots never move or overwrite texels sampled by an in-flight frame. */
class KoolCanvasAtlasAppend(val x: Int, val y: Int, val width: Int, val height: Int, rgba: ByteArray) {
    private val payload = rgba.copyOf()
    val byteCount: Int get() = payload.size
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0)
        require(payload.size == Math.multiplyExact(Math.multiplyExact(width, height), 4))
    }
    fun copyTo(destination: ByteBuffer) { destination.put(payload) }
}

/** Internal desktop backend bridge. Unsupported owners retain the immutable full-image fallback. */
object KoolCanvasAtlasUpdates {
    @Volatile private var sink: ((Texture2d, List<KoolCanvasAtlasAppend>) -> Boolean)? = null
    fun install(sink: ((Texture2d, List<KoolCanvasAtlasAppend>) -> Boolean)?) { this.sink = sink }
    fun append(texture: Texture2d, regions: List<KoolCanvasAtlasAppend>): Boolean = sink?.invoke(texture, regions) ?: false
}
