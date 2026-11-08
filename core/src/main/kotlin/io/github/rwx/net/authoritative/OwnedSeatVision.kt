package io.github.rwx.net.authoritative

/**
 * Single-owner derived sight, never a native team array. Every reveal uses the current circle.
 * Readers can borrow a fixed grid or obtain a defensive row-major copy, never mutable storage.
 */
internal class OwnedSeatVision {
    private var current: Grid? = null

    fun configure(width: Int, height: Int, revealed: Boolean) {
        val old = current
        if (old != null && old.width == width && old.height == height && old.revealed == revealed) return
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_CELLS) { "Invalid private vision dimensions" }
        current = Grid(width, height, revealed)
    }

    fun reset() { current = null }
    fun beginFrame() { current?.refog() }
    fun reveal(tileX: Float, tileY: Float, radius: Int) { current?.reveal(tileX, tileY, radius) }
    fun grid(): Grid = checkNotNull(current) { "Private vision is not configured" }

    /** Configuration and storage identity never change, even if a nested call replaces the controller's grid. */
    class Grid internal constructor(val width: Int, val height: Int, val revealed: Boolean) {
        private val cells = ByteArray(width * height) { if (revealed) 5 else 10 }
        // Only an active index describes current cells. Each frame starts on the original
        // byte scan; overlapping work may justify one private bitmap rebuild for that frame.
        private val wordsPerColumn = (height + 63) ushr 6
        private val nonzeroWords = LongArray(width * wordsPerColumn)
        private var indexActive = false
        private var scannedArea = 0L
        private var visible = IntArray(minOf(cells.size, 256))
        private var visibleCount = 0

        fun valueAt(x: Int, y: Int): Byte {
            if (x < 0 || x >= width || y < 0 || y >= height) throw IndexOutOfBoundsException("Private vision coordinates")
            return cells[y * width + x]
        }

        fun copyBytes(): ByteArray = cells.copyOf()

        /** Exact current cells below 5; maintained by the same unique visibility tracker. */
        internal val visibleCellCount: Int get() = visibleCount

        /** Primitive coordinates of this frame's actual private cells below 5, exactly once. */
        internal fun forEachVisibleCell(visit: (Int, Int) -> Unit) {
            for (position in 0 until visibleCount) {
                val index = visible[position]
                visit(index % width, index / width)
            }
        }

        internal fun refog() {
            indexActive = false
            scannedArea = 0L
            // Exactly the cells currently below 5 are recorded; each can enter this list only once.
            // The inactive bitmap is deliberately untouched: refog stays the original direct loop.
            for (index in 0 until visibleCount) cells[visible[index]] = 5
            visibleCount = 0
        }

        internal fun reveal(tileX: Float, tileY: Float, radius: Int) {
            if (radius <= 0) return
            // Preserve ReplayFogState's float expressions, bounds and x/y visit order exactly.
            val outer = radius.toFloat() * radius
            var inner = (radius - 3).toFloat() * (radius - 3)
            var falloff = 10 / (outer - inner)
            if (radius < 3) { inner = 0f; falloff = 10 / outer }
            val extent = Math.max(1, radius - 1)
            val cx = tileX.toInt(); val cy = tileY.toInt()
            // Retain the original Int arithmetic, including its overflow/empty-range behavior.
            val left = Math.max(0, cx - extent); val right = Math.min(width - 1, cx + extent)
            val top = Math.max(0, cy - extent); val bottom = Math.min(height - 1, cy + extent)
            if (left > right || top > bottom) return
            // Build only when a subsequent valid reveal can use the index. A frame ending
            // exactly at the workload threshold pays no otherwise-unused setup cost.
            if (!indexActive && visibleCount > 0 && scannedArea >= 4096L && scannedArea >= 4L * visibleCount) activateIndex()
            if (!indexActive) {
                // Original actual-byte reveal, without bit work in sparse/non-overlapping frames.
                for (x in left..right) {
                    for (y in top..bottom) {
                        val index = y * width + x
                        val previous = cells[index]
                        if (previous == 0.toByte()) continue
                        val dx = tileX - x; val dy = tileY - y
                        val distance = dx * dx + dy * dy
                        if (distance > outer) continue
                        val value: Byte = if (distance <= inner) 0 else Math.min(10f, (distance - inner) * falloff).toInt().toByte()
                        if (value < previous) {
                            if (previous >= 5 && value < 5) recordVisible(index)
                            cells[index] = value
                        }
                    }
                }
                val area = (right.toLong() - left + 1) * (bottom.toLong() - top + 1)
                scannedArea = if (scannedArea > Long.MAX_VALUE - area) Long.MAX_VALUE else scannedArea + area
                return
            }
            val firstWord = top ushr 6; val lastWord = bottom ushr 6
            val firstMask = -1L shl (top and 63)
            val lastMask = -1L ushr (63 - (bottom and 63))
            for (x in left..right) {
                for (columnWord in firstWord..lastWord) {
                    val word = x * wordsPerColumn + columnWord
                    var candidates = nonzeroWords[word]
                    if (columnWord == firstWord) candidates = candidates and firstMask
                    if (columnWord == lastWord) candidates = candidates and lastMask
                    while (candidates != 0L) {
                        val bit = java.lang.Long.numberOfTrailingZeros(candidates)
                        candidates = candidates and (candidates - 1)
                        val y = (columnWord shl 6) + bit
                        val index = y * width + x
                        val previous = cells[index]
                        // The bitmap never substitutes for the actual private cell value.
                        if (previous == 0.toByte()) continue
                        val dx = tileX - x; val dy = tileY - y
                        val distance = dx * dx + dy * dy
                        if (distance > outer) continue
                        val value: Byte = if (distance <= inner) 0 else Math.min(10f, (distance - inner) * falloff).toInt().toByte()
                        if (value < previous) {
                            if (previous >= 5 && value < 5) recordVisible(index)
                            cells[index] = value
                            if (value == 0.toByte()) nonzeroWords[word] = nonzeroWords[word] and (1L shl bit).inv()
                        }
                    }
                }
            }
        }

        private fun activateIndex() {
            // At frame start every actual cell was >=5. All new zeros are therefore in this
            // frame's unique <5 tracker; values 1..4 remain nonzero and keep their set bits.
            java.util.Arrays.fill(nonzeroWords, -1L)
            val tail = height and 63
            if (tail != 0) {
                val mask = -1L ushr (64 - tail)
                for (x in 0 until width) nonzeroWords[x * wordsPerColumn + wordsPerColumn - 1] = mask
            }
            for (position in 0 until visibleCount) {
                val index = visible[position]
                if (cells[index] == 0.toByte()) {
                    val y = index / width
                    val x = index % width
                    val word = x * wordsPerColumn + (y ushr 6)
                    nonzeroWords[word] = nonzeroWords[word] and (1L shl (y and 63)).inv()
                }
            }
            indexActive = true
        }

        private fun recordVisible(index: Int) {
            if (visibleCount == visible.size) {
                check(visibleCount < cells.size) { "Duplicate private visibility entry" }
                visible = visible.copyOf(minOf(cells.size, maxOf(1, visible.size * 2)))
            }
            visible[visibleCount++] = index
        }
    }

    private companion object { const val MAX_CELLS = 1_048_576 }
}
