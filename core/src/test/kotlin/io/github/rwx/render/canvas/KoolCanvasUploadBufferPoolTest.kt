package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class KoolCanvasUploadBufferPoolTest {
    @Test
    fun exactSizeReuseResetsBufferStateAndDoesNotLendAnOwnedBuffer() {
        val pool = KoolCanvasUploadBufferPool(enabled = true, maxRetainedBytes = 64, maxBuffersPerSize = 4)
        val first = pool.borrow(16)
        val buffer = first.buffer
        buffer[0] = 42.toUByte()
        buffer.position = 3
        buffer.limit = 9
        val other = pool.borrow(16)
        assertNotSame(buffer, other.buffer)
        first.close()
        first.close()
        val reused = pool.borrow(16)
        assertSame(buffer, reused.buffer)
        assertEquals(0, reused.buffer.position)
        assertEquals(16, reused.buffer.limit)
        assertEquals(42.toUByte(), reused.buffer[0])
        assertFailsWith<IllegalStateException> { first.buffer }
        other.close()
        reused.close()
        assertEquals(0, pool.stats().activeBuffers)
        assertEquals(2L, pool.stats().allocations)
        assertEquals(2L, pool.stats().misses)
        assertEquals(1L, pool.stats().reusedBuffers)
        pool.close()
    }

    @Test
    fun byteAndSizeLimitsBoundOnlyIdleStorage() {
        val pool = KoolCanvasUploadBufferPool(enabled = true, maxRetainedBytes = 32, maxBuffersPerSize = 2)
        assertEquals(2, pool.preallocate(16, 10))
        assertEquals(0, pool.preallocate(16, 1))
        assertEquals(0, pool.preallocate(8, 1))
        val small = pool.borrow(16)
        val large = pool.borrow(48)
        assertEquals(16L, pool.stats().retainedBytes)
        small.close()
        large.close()
        assertEquals(32L, pool.stats().retainedBytes)
        assertEquals(2, pool.stats().retainedBuffers)
        assertEquals(3L, pool.stats().allocations)
        assertEquals(2L, pool.stats().preallocatedBuffers)
        pool.close()
    }

    @Test
    fun generationChangeAndCloseNeverRecycleUnknownContextReaders() {
        val pool = KoolCanvasUploadBufferPool(enabled = true)
        val oldContext = pool.borrow(16)
        val oldBuffer = oldContext.buffer
        pool.preallocate(16, 1)
        pool.clear()
        oldContext.close()
        assertEquals(0, pool.stats().retainedBuffers)
        val current = pool.borrow(16)
        assertNotSame(oldBuffer, current.buffer)
        pool.close()
        assertEquals(16, current.buffer.capacity) // Closing idle storage cannot invalidate an owner.
        current.close()
        assertEquals(0, pool.stats().activeBuffers)
        assertEquals(0L, pool.stats().retainedBytes)
        assertFailsWith<IllegalStateException> { pool.borrow(16) }
    }

    @Test
    fun disabledModeRetainsNoGlobalStorage() {
        val pool = KoolCanvasUploadBufferPool(enabled = false)
        assertEquals(0, pool.preallocate(16, 48))
        val first = pool.borrow(16)
        val buffer = first.buffer
        first.close()
        val second = pool.borrow(16)
        assertNotSame(buffer, second.buffer)
        second.close()
        assertEquals(0, pool.stats().retainedBuffers)
        assertEquals(2L, pool.stats().allocations)
        assertEquals(0L, pool.stats().reusedBuffers)
        pool.close()
    }

    @Test
    fun discardedOwnerCannotReturnItsStorageThroughLaterClose() {
        val pool = KoolCanvasUploadBufferPool(enabled = true)
        val uncertain = pool.borrow(16)
        val buffer = uncertain.buffer
        uncertain.discard()
        uncertain.close()
        uncertain.discard()
        assertEquals(0, pool.stats().activeBuffers)
        assertEquals(0, pool.stats().retainedBuffers)
        val next = pool.borrow(16)
        assertNotSame(buffer, next.buffer)
        next.close()
        next.discard()
        assertEquals(1, pool.stats().retainedBuffers) // A proven retired close remains reusable.
        pool.close()
    }
}
