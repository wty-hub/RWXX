package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class KoolCanvasPixelPoolTest {
    @Test
    fun arrayCannotBeReusedWhileAnotherOwnerStillReadsIt() {
        val pool = KoolCanvasPixelPool(maxRetainedBytes = 64, maxArraysPerSize = 4)
        val source = pool.borrow(4)
        val pixels = source.pixels
        pixels[0] = 42
        val frozen = source.retain()
        source.close()
        source.close() // Each owner closes at most once even when cleanup paths overlap.
        val concurrent = pool.borrow(4)
        assertNotSame(pixels, concurrent.pixels)
        assertEquals(42, frozen.pixels[0])
        frozen.close()
        val reused = pool.borrow(4)
        assertSame(pixels, reused.pixels)
        assertFailsWith<IllegalStateException> { frozen.retain() }
        assertFailsWith<IllegalStateException> { source.pixels }
        concurrent.close()
        reused.close()
        assertEquals(0, pool.stats().activeAllocations)
        pool.close()
    }

    @Test
    fun preallocationHonorsExactSizeAndIdleCapacity() {
        val pool = KoolCanvasPixelPool(maxRetainedBytes = 32, maxArraysPerSize = 2)
        assertEquals(2, pool.preallocate(4, 10))
        assertEquals(0, pool.preallocate(4, 1))
        assertEquals(0, pool.preallocate(8, 1))
        val small = pool.borrow(4)
        assertEquals(4, small.pixels.size)
        val large = pool.borrow(8)
        assertEquals(8, large.pixels.size)
        assertEquals(16L, pool.stats().retainedBytes)
        small.close()
        large.close() // Oversized return cannot exceed the retained byte budget.
        assertEquals(32L, pool.stats().retainedBytes)
        assertEquals(2, pool.stats().retainedArrays)
        assertEquals(3L, pool.stats().allocations)
        assertEquals(1L, pool.stats().misses)
        assertEquals(1L, pool.stats().reusedArrays)
        assertEquals(2L, pool.stats().preallocatedArrays)
        pool.close()
    }

    @Test
    fun clearAndCloseDoNotResurrectBorrowedStorage() {
        val pool = KoolCanvasPixelPool(maxRetainedBytes = 64, maxArraysPerSize = 4)
        val beforeClear = pool.borrow(4)
        pool.preallocate(4, 1)
        pool.clear()
        beforeClear.close()
        assertEquals(0, pool.stats().retainedArrays)
        val beforeClose = pool.borrow(4)
        val reader = beforeClose.retain()
        pool.close()
        beforeClose.close()
        assertEquals(4, reader.pixels.size)
        reader.close()
        assertEquals(0, pool.stats().activeAllocations)
        assertEquals(0L, pool.stats().retainedBytes)
        assertFailsWith<IllegalStateException> { pool.borrow(4) }
        assertFailsWith<IllegalStateException> { pool.preallocate(4, 1) }
    }
}
