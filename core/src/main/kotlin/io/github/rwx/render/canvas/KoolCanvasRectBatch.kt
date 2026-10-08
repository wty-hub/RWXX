package io.github.rwx.render.canvas

/** Ordered independent xyxy rectangles. Published storage is never reused by the recorder. */
class KoolCanvasRectBatch private constructor(private val coordinates: FloatArray) {
    val size: Int get() = coordinates.size / STRIDE
    private val contentHash = coordinates.contentHashCode()
    internal fun coordinate(rect: Int, component: Int): Float = coordinates[rect * STRIDE + component]

    internal inline fun forEachDraw(
        paint: KoolCanvasPaint,
        state: KoolCanvasState,
        draw: (KoolCanvasCommand.DrawRect) -> Unit,
    ) {
        for (rect in 0 until size) draw(KoolCanvasCommand.DrawRect(
            KoolCanvasRect(coordinate(rect, 0), coordinate(rect, 1), coordinate(rect, 2), coordinate(rect, 3)),
            paint, state,
        ))
    }

    override fun hashCode(): Int = contentHash
    override fun equals(other: Any?): Boolean = this === other ||
        (other is KoolCanvasRectBatch && contentHash == other.contentHash && coordinates.contentEquals(other.coordinates))

    companion object {
        internal const val STRIDE = 4
        internal fun copyOf(coordinates: FloatArray, count: Int): KoolCanvasRectBatch =
            KoolCanvasRectBatch(coordinates.copyOf(count * STRIDE))
    }
}
