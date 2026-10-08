package io.github.rwx.render.canvas

/** Source xyxy followed by destination xyxy. No mutable storage escapes a published batch. */
class KoolCanvasTextureBatch private constructor(private val coordinates: FloatArray) {
    val size: Int get() = coordinates.size / STRIDE
    private val contentHash = coordinates.contentHashCode()
    internal fun coordinate(quad: Int, component: Int): Float = coordinates[quad * STRIDE + component]

    internal inline fun forEachDraw(
        texture: KoolCanvasTextureRef,
        paint: KoolCanvasPaint,
        state: KoolCanvasState,
        draw: (KoolCanvasCommand.DrawTexture) -> Unit,
    ) {
        for (quad in 0 until size) draw(KoolCanvasCommand.DrawTexture(
            texture,
            KoolCanvasRect(coordinate(quad, 0), coordinate(quad, 1), coordinate(quad, 2), coordinate(quad, 3)),
            KoolCanvasRect(coordinate(quad, 4), coordinate(quad, 5), coordinate(quad, 6), coordinate(quad, 7)),
            paint, state,
        ))
    }

    override fun hashCode(): Int = contentHash
    override fun equals(other: Any?): Boolean = this === other ||
        (other is KoolCanvasTextureBatch && contentHash == other.contentHash && coordinates.contentEquals(other.coordinates))

    companion object {
        internal const val STRIDE = 8
        internal fun copyOf(coordinates: FloatArray, count: Int): KoolCanvasTextureBatch =
            KoolCanvasTextureBatch(coordinates.copyOf(count * STRIDE))
    }
}
