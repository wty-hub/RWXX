package io.github.rwx.render.canvas

/** Literal-order black masks. Storage is copied once and never exposed to the recording owner. */
class KoolCanvasFogBatch private constructor(private val values: FloatArray) {
    val size: Int get() = values.size / STRIDE
    private val contentHash = values.contentHashCode()
    internal fun coordinate(index: Int, component: Int) = values[index * STRIDE + component]
    override fun hashCode() = contentHash
    override fun equals(other: Any?) = other is KoolCanvasFogBatch && values.contentEquals(other.values)

    internal inline fun forEachDraw(texture: KoolCanvasTextureRef?, filter: KoolCanvasTextureFilter,
        state: KoolCanvasState, paint: KoolCanvasPaint, draw: (KoolCanvasCommand) -> Unit) {
        for (i in 0 until size) {
            val destination = KoolCanvasRect(coordinate(i, 0), coordinate(i, 1), coordinate(i, 2), coordinate(i, 3))
            val alpha = (coordinate(i, 10).toInt() * paint.color.alpha) / 255
            val maskPaint = paint.copy(color = KoolCanvasColor(alpha shl 24),
                alphaMultiplier = coordinate(i, 11) * paint.alphaMultiplier,
                textureFilter = filter)
            if (coordinate(i, 9) == 0f) draw(KoolCanvasCommand.DrawRect(destination, maskPaint, state))
            else draw(KoolCanvasCommand.DrawTexture(checkNotNull(texture),
                KoolCanvasRect(coordinate(i, 4), coordinate(i, 5), coordinate(i, 6), coordinate(i, 7)),
                destination, maskPaint, state))
        }
    }

    companion object {
        internal const val STRIDE = 12
        internal fun copyOf(values: FloatArray, size: Int) = KoolCanvasFogBatch(values.copyOf(size * STRIDE))
    }
}
