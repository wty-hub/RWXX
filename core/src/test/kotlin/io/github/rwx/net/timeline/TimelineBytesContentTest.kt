package io.github.rwx.net.timeline

import kotlin.test.*

class TimelineBytesContentTest {
    @Test fun `content comparison reads every byte and length without changing value equality`() {
        val input = byteArrayOf(0, 5, 10, 3, 7)
        val owned = TimelineBytes(input)
        assertTrue(owned.contentEquals(input))
        assertTrue(TimelineBytes.EMPTY.contentEquals(byteArrayOf()))
        assertFalse(owned.contentEquals(input.copyOf(input.size - 1)))
        assertFalse(owned.contentEquals(input + 0.toByte()))
        for (index in input.indices) {
            val changed = input.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertFalse(owned.contentEquals(changed), "Mismatch at byte $index must not be skipped")
        }
        val equalOwned = TimelineBytes(input)
        assertEquals(owned, equalOwned)
        assertEquals(owned.hashCode(), equalOwned.hashCode())
        assertFalse(owned.equals(input), "A named array comparison does not change equals to cross types")
    }

    @Test fun `comparison neither exposes storage nor retains mutable caller arrays`() {
        val input = byteArrayOf(0, 5, 10)
        val owned = TimelineBytes(input)
        val original = input.copyOf()
        val candidate = input.copyOf()
        assertTrue(owned.contentEquals(candidate))
        assertContentEquals(original, candidate, "Comparison does not mutate its argument")
        input.fill(9)
        candidate.fill(8)
        owned.toByteArray().fill(7)
        assertTrue(owned.contentEquals(original))
        assertFalse(owned.contentEquals(input))
        assertFalse(owned.contentEquals(candidate))
        assertContentEquals(original, owned.toByteArray())
    }
}
