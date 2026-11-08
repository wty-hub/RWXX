package io.github.rwx.net.authoritative

import com.corrodinggames.rts.gameFramework.ReplayFogState
import kotlin.random.Random
import kotlin.test.*

/** The independent full-scan replay fog remains the byte oracle. Reflection only checks invariants. */
class OwnedSeatVisionBitsTest {
    private val wordsField = OwnedSeatVision.Grid::class.java.getDeclaredField("nonzeroWords").apply { isAccessible = true }
    private val activeField = OwnedSeatVision.Grid::class.java.getDeclaredField("indexActive").apply { isAccessible = true }
    private val areaField = OwnedSeatVision.Grid::class.java.getDeclaredField("scannedArea").apply { isAccessible = true }
    private val countField = OwnedSeatVision.Grid::class.java.getDeclaredField("visibleCount").apply { isAccessible = true }
    private val visibleField = OwnedSeatVision.Grid::class.java.getDeclaredField("visible").apply { isAccessible = true }

    private class PairOfVisions(val width: Int, val height: Int, revealed: Boolean = false) {
        val owned = OwnedSeatVision().also { it.configure(width, height, revealed) }
        val oracle = ReplayFogState().also { it.configure(width, height, revealed); it.ensureTeam(0) }
        fun begin() { owned.beginFrame(); oracle.beginFrame(true) }
        fun reveal(x: Float, y: Float, radius: Int) { owned.reveal(x, y, radius); oracle.reveal(0, x, y, radius) }
        fun expected() = ByteArray(width * height) { index -> oracle.get(0)[index % width][index / width] }
    }

    private fun invariants(grid: OwnedSeatVision.Grid) {
        val bytes = grid.copyBytes()
        val words = wordsField.get(grid) as LongArray
        val wordsPerColumn = (grid.height + 63) / 64
        assertEquals(grid.width * wordsPerColumn, words.size)
        val belowFive = bytes.count { it < 5 }
        if (activeField.getBoolean(grid)) {
            for (x in 0 until grid.width) for (y in 0 until wordsPerColumn * 64) {
                val set = words[x * wordsPerColumn + y / 64] and (1L shl (y % 64)) != 0L
                val expected = y < grid.height && bytes[y * grid.width + x] != 0.toByte()
                if (set != expected) fail("Nonzero bit differs at ($x,$y), height=${grid.height}")
            }
        }
        val count = countField.getInt(grid)
        val indices = visibleField.get(grid) as IntArray
        assertEquals(belowFive, count, "Exactly the current <5 cells must be refogged")
        assertTrue(count <= indices.size && indices.size <= bytes.size)
        val seen = BooleanArray(bytes.size)
        for (position in 0 until count) {
            val index = indices[position]
            assertTrue(index in bytes.indices)
            assertFalse(seen[index], "A cell may enter the refog list only once")
            assertTrue(bytes[index] < 5)
            seen[index] = true
        }
    }

    private fun compare(pair: PairOfVisions, label: String = "") {
        val grid = pair.owned.grid()
        assertContentEquals(pair.expected(), grid.copyBytes(), label)
        for (y in 0 until pair.height) for (x in 0 until pair.width) {
            val expected = pair.oracle.get(0)[x][y]
            if (expected != grid.valueAt(x, y)) fail("$label valueAt differs at ($x,$y)")
        }
        invariants(grid)
    }

    private fun activate(pair: PairOfVisions, x: Float, y: Float, radius: Int) {
        repeat(4096) {
            if (activeField.getBoolean(pair.owned.grid())) return
            pair.reveal(x, y, radius)
        }
        assertTrue(activeField.getBoolean(pair.owned.grid()), "Overlapping actual scans must reach the indexed path")
    }

    @Test fun `first and last masks include bit 63 and exclude all padding across word boundaries`() {
        for (height in intArrayOf(1, 2, 63, 64, 65, 127, 128, 129, 191, 192, 193)) {
            for (revealed in listOf(false, true)) {
                val pair = PairOfVisions(3, height, revealed)
                compare(pair, "initial height=$height revealed=$revealed")
                val ys = intArrayOf(0, 1, 62, 63, 64, 65, 126, 127, 128, height - 1)
                for (y in ys) for (radius in intArrayOf(1, 2, 3, 5, 65)) {
                    pair.begin()
                    activate(pair, 1f, 0f, 2)
                    pair.reveal(1f, y.toFloat(), radius)
                    compare(pair, "height=$height y=$y radius=$radius")
                    pair.reveal(1.25f, y + .5f, radius)
                    compare(pair, "fractional word boundary")
                }
            }
        }
    }

    @Test fun `dense zero words refog and same boundary moving circles preserve all actual cells`() {
        val pair = PairOfVisions(17, 257)
        val random = Random(0x71_6401)
        repeat(36) { frame ->
            pair.begin()
            compare(pair, "refog frame=$frame")
            repeat(18) { circle ->
                val x = random.nextFloat() * 21 - 2
                val y = random.nextFloat() * 263 - 3
                val radius = random.nextInt(1, 90)
                pair.reveal(x, y, radius)
                if (circle % 5 == 0) pair.reveal(x, y, radius)
            }
            compare(pair, "random dense frame=$frame")
            pair.reveal(8f, 128f, 1024)
            compare(pair, "all zero frame=$frame")
            assertTrue(pair.owned.grid().copyBytes().all { it == 0.toByte() })
            val detached = pair.owned.grid().copyBytes()
            repeat(24) { pair.reveal(8f, (it * 11).toFloat(), 13) }
            compare(pair, "repeated zero words")
            pair.begin()
            compare(pair, "zero words become nonzero immediately")
            assertTrue(pair.owned.grid().copyBytes().all { it == 5.toByte() })
            pair.reveal(8f, 63f, 2)
            pair.reveal(8f, 64f, 2)
            pair.reveal(8f, 127f, 2)
            pair.reveal(8f, 128f, 2)
            compare(pair, "fresh bit-63 and bit-0 writes without advancing any tick")
            assertTrue(detached.all { it == 0.toByte() })
            pair.owned.grid().copyBytes().fill(127)
            compare(pair, "copy mutation cannot alter cells or bitmap")
            pair.begin(); pair.begin()
            compare(pair, "repeat refog cannot retain stale visible indices")
        }
    }

    @Test fun `nonfinite points and extreme radius overflow retain native empty ranges and NaN conversion`() {
        val pair = PairOfVisions(5, 129)
        val points = listOf(0f to 0f, -0f to -0f, 2.5f to 63.5f, 2.5f to 64f, -1.25f to 127.75f,
            Float.NaN to 0f, 0f to Float.NaN, Float.NaN to Float.NaN,
            Float.POSITIVE_INFINITY to Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY to Float.POSITIVE_INFINITY,
            Float.MAX_VALUE to 63f, -Float.MAX_VALUE to 64f, Float.MIN_VALUE to -Float.MIN_VALUE)
        for (radius in intArrayOf(Int.MIN_VALUE, -1, 0, 1, 2, 3, 63, 64, 65, Int.MAX_VALUE)) {
            for ((x, y) in points) {
                pair.begin()
                activate(pair, 2f, 64f, 2)
                pair.reveal(x, y, radius)
                compare(pair, "radius=$radius point=($x,$y)")
                pair.reveal(x, y, radius)
                compare(pair, "repeat exceptional bounds")
            }
        }
    }

    @Test fun `replacement reset and borrowed old grids own independent bits and refog lists`() {
        val pair = PairOfVisions(7, 129)
        activate(pair, 3f, 63f, 25)
        val old = pair.owned.grid()
        val snapshot = old.copyBytes()
        val snapshotExpected = snapshot.copyOf()
        pair.owned.configure(7, 129, false)
        assertSame(old, pair.owned.grid())
        pair.owned.configure(5, 65, true)
        val fresh = pair.owned.grid()
        assertNotSame(old, fresh)
        assertNotSame(wordsField.get(old), wordsField.get(fresh))
        val freshBytes = fresh.copyBytes()
        // A reader already bound to the old immutable Grid cannot be redirected by configure.
        old.refog(); pair.oracle.beginFrame(true)
        repeat(32) { old.reveal(3.25f, 64.5f, 18); pair.oracle.reveal(0, 3.25f, 64.5f, 18) }
        assertTrue(activeField.getBoolean(old))
        assertFalse(activeField.getBoolean(fresh))
        assertContentEquals(pair.expected(), old.copyBytes())
        assertContentEquals(freshBytes, fresh.copyBytes())
        invariants(old); invariants(fresh)
        val oldBeforeReset = old.copyBytes()
        pair.owned.reset()
        pair.owned.beginFrame(); pair.owned.reveal(Float.NaN, Float.NaN, 100)
        assertFailsWith<IllegalStateException> { pair.owned.grid() }
        assertContentEquals(oldBeforeReset, old.copyBytes())
        assertContentEquals(freshBytes, fresh.copyBytes())
        pair.owned.configure(7, 129, false)
        val replacement = pair.owned.grid()
        assertNotSame(old, replacement)
        assertTrue(replacement.copyBytes().all { it == 10.toByte() })
        invariants(replacement)
        assertTrue(snapshot.any { it == 0.toByte() }, "Detached old snapshots retain their actual earlier view")
        assertContentEquals(snapshotExpected, snapshot)
        assertContentEquals(oldBeforeReset, old.copyBytes())
    }

    @Test fun `bitmap storage is bounded by configured dimensions and full refog cannot duplicate cells`() {
        val pair = PairOfVisions(257, 257)
        repeat(3) {
            pair.reveal(128f, 128f, 1024)
            repeat(12) { pair.reveal(128f, 128f, 1024) }
            compare(pair, "full tracker capacity")
            assertEquals(257 * 257, countField.getInt(pair.owned.grid()))
            pair.begin()
            compare(pair, "complete visible-list restoration")
            assertEquals(0, countField.getInt(pair.owned.grid()))
        }
        // Both extreme legal aspect ratios still have finite exact capacity, without one object per column.
        val owner = OwnedSeatVision()
        for ((width, height) in listOf(1 to 1_048_576, 1_048_576 to 1, 1024 to 1024)) {
            owner.configure(width, height, false)
            val grid = owner.grid()
            val words = wordsField.get(grid) as LongArray
            assertEquals(width * ((height + 63) / 64), words.size)
            assertTrue(words.size <= 1_048_576)
            assertTrue(grid.copyBytes().all { it == 10.toByte() })
            assertFalse(activeField.getBoolean(grid), "New grids need no bitmap build before overlapping work")
        }
        assertFailsWith<IllegalArgumentException> { owner.configure(1025, 1024, false) }
        assertFailsWith<IllegalArgumentException> { owner.configure(1, 0, false) }
    }

    @Test fun `sparse frames avoid the index while overlap activates once and refog never updates stale words`() {
        val pair = PairOfVisions(512, 512)
        pair.begin()
        assertFalse(activeField.getBoolean(pair.owned.grid()))
        for (y in intArrayOf(64, 192, 320, 448)) for (x in intArrayOf(64, 192, 320, 448)) pair.reveal(x.toFloat(), y.toFloat(), 11)
        compare(pair, "sparse nonoverlapping circles")
        assertTrue(areaField.getLong(pair.owned.grid()) >= 4096L, "The overlap ratio, not only the minimum work gate, rejects sparse indexing")
        assertFalse(activeField.getBoolean(pair.owned.grid()), "Unique sight should not pay the full-word index setup")
        activate(pair, 80f, 80f, 11)
        compare(pair, "overlap activates a correct actual index")
        val grid = pair.owned.grid()
        val areaAtActivation = areaField.getLong(grid)
        val activeWords = (wordsField.get(grid) as LongArray).copyOf()
        repeat(10) { pair.reveal(80f, 80f, 11) }
        assertEquals(areaAtActivation, areaField.getLong(grid), "An active frame does not restart the activation heuristic")
        compare(pair, "repeated indexed reveal")
        pair.begin()
        assertFalse(activeField.getBoolean(grid))
        assertEquals(0L, areaField.getLong(grid))
        assertContentEquals(activeWords, wordsField.get(grid) as LongArray, "Refog deliberately performs no division or bit writes")
        // These cells had clear bits in the preceding frame. Inactive reveal must read the
        // actual refogged 5 values instead of using the now-stale zero membership.
        pair.reveal(80f, 80f, 11)
        assertFalse(activeField.getBoolean(grid))
        compare(pair, "stale clear bits cannot suppress a fresh scalar reveal")
        activate(pair, 80f, 80f, 11)
        compare(pair, "each new frame can independently reactivate")
        pair.begin(); pair.begin()
        assertFalse(activeField.getBoolean(grid)); assertEquals(0L, areaField.getLong(grid))
        compare(pair, "repeated beginFrame")
    }

    @Test fun `activation rebuild keeps partial visibility nonzero and the workload counter saturates without affecting bytes`() {
        val pair = PairOfVisions(5, 129)
        pair.reveal(2.25f, 63.5f, 2)
        assertTrue(pair.owned.grid().copyBytes().any { it.toInt() in 1..4 })
        activate(pair, 2.25f, 63.5f, 2)
        compare(pair, "partial visibility must retain its nonzero bits")
        assertTrue(pair.owned.grid().copyBytes().any { it.toInt() in 1..4 })
        pair.begin()
        areaField.setLong(pair.owned.grid(), Long.MAX_VALUE - 1)
        pair.reveal(2f, 64f, 2)
        assertEquals(Long.MAX_VALUE, areaField.getLong(pair.owned.grid()))
        assertFalse(activeField.getBoolean(pair.owned.grid()), "Threshold crossing alone must not build an unused index")
        pair.reveal(2f, 64f, 2)
        assertTrue(activeField.getBoolean(pair.owned.grid()))
        compare(pair, "counter saturation selects work only, never changes fog")
        pair.begin()
        assertEquals(0L, areaField.getLong(pair.owned.grid()))
        pair.reveal(Float.MAX_VALUE, Float.MAX_VALUE, Int.MAX_VALUE)
        compare(pair, "Int overflow bounds retain the original empty-range behavior")
    }

    @Test fun `reaching the full frame threshold alone does not build until the next valid reveal needs the index`() {
        val pair = PairOfVisions(65, 65)
        pair.begin()
        repeat(4) { pair.reveal(32f, 32f, 1024) }
        val grid = pair.owned.grid()
        assertEquals(4L * 65 * 65, areaField.getLong(grid))
        assertEquals(65 * 65, countField.getInt(grid))
        assertFalse(activeField.getBoolean(grid), "A frame ending after four full circles must remain scalar")
        compare(pair, "four full circles, no unused bitmap build")
        pair.reveal(32f, 32f, 0)
        pair.reveal(-10_000f, -10_000f, 1)
        assertFalse(activeField.getBoolean(grid), "Empty/invalid bounds do not need an index")
        assertEquals(4L * 65 * 65, areaField.getLong(grid))
        pair.reveal(32f, 32f, 1024)
        assertTrue(activeField.getBoolean(grid), "The fifth valid circle now consumes the prepared opportunity")
        compare(pair, "first actual indexed use remains the same byte oracle")
        pair.begin()
        assertFalse(activeField.getBoolean(grid)); assertEquals(0L, areaField.getLong(grid))
        compare(pair, "next frame carries no pending index activation")
    }
}
