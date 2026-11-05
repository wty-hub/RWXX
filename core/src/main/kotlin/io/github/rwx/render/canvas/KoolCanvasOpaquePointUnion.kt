package io.github.rwx.render.canvas

/** Exact integer-cell union for one same-paint batch of right/down expanding opaque square points. */
internal object KoolCanvasOpaquePointUnion {
    private const val MAX_COORDINATE = 1 shl 20
    private const val MAX_EXTENT = 512
    private const val MAX_OUTPUT_RECTS = 4096

    class Result(val inputPoints: Int, private val spans: IntArray) {
        val rectCount: Int get() = spans.size / 4
        fun coordinate(rect: Int, component: Int): Int = spans[rect * 4 + component]
    }

    fun isEligible(viewport: KoolCanvasViewport, state: KoolCanvasState, paint: KoolCanvasPaint, root: Boolean) =
        root && viewport.width in 1..65536 && viewport.height in 1..65536 &&
            state.transform === KoolCanvasTransform.Identity && state.clip == null &&
            state.renderTarget == null && state.drawRole == KoolCanvasDrawRole.Generic &&
            paint.color.alpha == 255 && paint.alphaMultiplier == 1f &&
            paint.blendMode == KoolCanvasBlendMode.SourceOver && paint.textureEffect == null

    /** Uses the caller's original inclusive end, including odd offsets/counts. Null emits nothing. */
    fun tryUnion(points: FloatArray, start: Int, end: Int, size: Float): Result? {
        if (start < 0 || start >= points.size || end >= points.size || end < start + 1) return null
        val pointCount = (end - start + 1) / 2
        if (pointCount < 64 || !size.isFinite()) return null
        val side = size.toInt()
        if (side !in 1..8 || side.toFloat() != size) return null
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        var index = start
        // Validate every old point before allocating coverage or producing any result.
        while (index + 1 <= end) {
            val x = points[index]; val y = points[index + 1]
            if (!validCoordinate(x) || !validCoordinate(y)) return null
            val ix = x.toInt(); val iy = y.toInt()
            minX = minOf(minX, ix); minY = minOf(minY, iy)
            maxX = maxOf(maxX, ix + side); maxY = maxOf(maxY, iy + side)
            index += 2
        }
        val width = maxX - minX; val height = maxY - minY
        if (width !in 1..MAX_EXTENT || height !in 1..MAX_EXTENT) return null
        val coverage = LongArray((width * height + 63) / 64)
        val pointMask = (1L shl side) - 1
        index = start
        while (index + 1 <= end) {
            val x = points[index].toInt() - minX; val y = points[index + 1].toInt() - minY
            for (row in y until y + side) {
                val bit = row * width + x; val word = bit ushr 6; val shift = bit and 63
                coverage[word] = coverage[word] or (pointMask shl shift)
                if (shift + side > 64) coverage[word + 1] = coverage[word + 1] or
                    ((1L shl (shift + side - 64)) - 1)
            }
            index += 2
        }
        // A bounded output also avoids large per-frame arrays for pathological sparse point clouds.
        val maxRects = minOf(MAX_OUTPUT_RECTS, (pointCount - 1) / 2)
        var coordinates = IntArray(minOf(64, maxRects) * 4)
        var count = 0
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                if (!occupied(coverage, y * width + x)) { x++; continue }
                val left = x++
                while (x < width && occupied(coverage, y * width + x)) x++
                if (count == maxRects) return null
                if ((count + 1) * 4 > coordinates.size)
                    coordinates = coordinates.copyOf(minOf(maxRects * 4, coordinates.size * 2))
                val offset = count * 4
                coordinates[offset] = minX + left; coordinates[offset + 1] = minY + y
                coordinates[offset + 2] = minX + x; coordinates[offset + 3] = minY + y + 1
                count++
            }
        }
        return Result(pointCount, coordinates.copyOf(count * 4))
    }

    private fun occupied(words: LongArray, bit: Int) = words[bit ushr 6] ushr (bit and 63) and 1L != 0L
    private fun validCoordinate(value: Float): Boolean = value.isFinite() &&
        value.toRawBits() != Int.MIN_VALUE && value >= -MAX_COORDINATE.toFloat() &&
        value <= MAX_COORDINATE.toFloat() && value.toInt().toFloat() == value
}
