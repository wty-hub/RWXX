package com.corrodinggames.rts.gameFramework.graphics

import kotlin.test.*

class TextureImmutablePixelsTest {
    private fun texture() = Texture().apply { p = 2; q = 2; updateCenter() }

    private fun pixels() = intArrayOf(0xff123456.toInt(), 0xff654321.toInt(),
        0xff2468ac.toInt(), 0xffabcdef.toInt())

    @Test
    fun `read only access shares pixels while a single pixel edit detaches before release`() {
        val texture = texture()
        val shared = pixels()
        val original = shared.copyOf()
        var releases = 0
        texture.setImmutableArgbPixels(shared) { releases++ }
        val revision = texture.getPixelRevision()
        assertSame(shared, texture.argbPixelsRef)
        assertEquals(original[3], texture.a(1, 1))
        val readCopy = assertNotNull(texture.argbPixelsCopy)
        assertNotSame(shared, readCopy)
        readCopy.fill(0)
        assertContentEquals(original, shared)
        assertEquals(0, releases, "read-only access must retain the texture owner")

        texture.a(1, 0, 0xffa04020.toInt())
        assertEquals(1, releases, "the first write must relinquish the shared owner")
        assertNotSame(shared, texture.argbPixelsRef)
        assertContentEquals(original, shared, "a legacy edit must not modify an old frame's pixels")
        assertContentEquals(original.copyOf().apply { this[1] = 0xffa04020.toInt() }, texture.argbPixelsRef)
        assertEquals(revision + 1, texture.getPixelRevision())
        texture.a(0, 1, 0xff203040.toInt())
        texture.o()
        texture.o()
        assertEquals(1, releases, "writes after detaching and repeated release must not release twice")
    }

    @Test
    fun `both editable array entry points detach and commit an independent snapshot`() {
        for (openWithJ in listOf(false, true)) {
            val texture = texture()
            val shared = pixels()
            val original = shared.copyOf()
            var releases = 0
            texture.setImmutableArgbPixels(shared) { releases++; shared.fill(0) }
            val editable = if (openWithJ) {
                texture.j()
                assertNotNull(texture.j)
            } else {
                assertNotNull(texture.editablePixels())
            }
            assertNotSame(shared, editable)
            editable[0] = 0x80102030.toInt()
            assertContentEquals(original, shared)
            assertEquals(0, releases, "opening edits must keep committed shared contents alive until replacement")
            val expected = editable.copyOf()
            texture.p()
            assertEquals(1, releases)
            editable.fill(0xff998877.toInt())
            texture.r()
            assertContentEquals(expected, texture.argbPixelsRef, "commit must detach from the externally editable array")
            assertTrue(texture.f(), "commit must preserve the edited alpha")
            texture.o()
            assertEquals(1, releases)
        }
    }

    @Test
    fun `normal setter copies its current shared input before the old owner can recycle it`() {
        val texture = texture()
        val shared = pixels()
        val original = shared.copyOf()
        var releases = 0
        texture.setImmutableArgbPixels(shared) { releases++; shared.fill(0) }
        texture.setCommittedArgbPixels(assertNotNull(texture.argbPixelsRef))
        assertEquals(1, releases)
        assertContentEquals(IntArray(4), shared, "the retired owner may immediately recycle its array")
        assertNotSame(shared, texture.argbPixelsRef)
        assertContentEquals(original, texture.argbPixelsRef, "defensive registration must copy before recycling the source")
        assertFalse(texture.f(), "alpha classification must use the detached snapshot")

        val mutableCaller = pixels().apply { this[3] = 0x00203040 }
        val next = mutableCaller.copyOf()
        texture.setCommittedArgbPixels(mutableCaller)
        mutableCaller.fill(0)
        assertContentEquals(next, texture.argbPixelsRef)
        assertTrue(texture.f())
        texture.o()
        assertEquals(1, releases)
    }

    @Test
    fun `immutable replacement and release relinquish each independent owner once`() {
        val texture = texture()
        val first = pixels()
        val second = pixels().apply { this[0] = 0x80204060.toInt() }
        var firstReleases = 0
        var secondReleases = 0
        texture.setImmutableArgbPixels(first) { firstReleases++ }
        texture.j()
        texture.setImmutableArgbPixels(second) { secondReleases++ }
        assertEquals(1, firstReleases)
        assertEquals(0, secondReleases)
        assertNull(texture.j, "replacement must discard stale editable pixels")
        assertSame(second, texture.argbPixelsRef)
        assertTrue(texture.f())
        texture.o()
        texture.o()
        assertEquals(1, firstReleases)
        assertEquals(1, secondReleases)
        assertNull(texture.argbPixelsRef, "released shared contents must not retain a recycled array")
    }

    @Test
    fun `a retained owner permits replacing shared contents with the same array`() {
        val texture = texture()
        val shared = pixels()
        val original = shared.copyOf()
        var owners = 0
        var releases = 0
        fun retain(): () -> Unit {
            owners++
            return {
                releases++
                owners--
                if (owners == 0) shared.fill(0)
            }
        }
        texture.setImmutableArgbPixels(shared, retain())
        texture.setImmutableArgbPixels(shared, retain())
        assertEquals(1, owners)
        assertEquals(1, releases)
        assertSame(shared, texture.argbPixelsRef)
        assertContentEquals(original, texture.argbPixelsRef, "the new owner must be retained before replacing the old one")
        texture.o()
        texture.o()
        assertEquals(0, owners)
        assertEquals(2, releases)
        assertNull(texture.argbPixelsRef)
    }

    @Test
    fun `snapshot invalidation releases shared contents and preserves lazy loader behavior`() {
        val texture = InvalidatableTexture().apply { p = 2; q = 2 }
        var releases = 0
        var loads = 0
        val loaded = pixels().apply { this[1] = 0x80102030.toInt() }
        texture.setImmutableArgbPixels(pixels()) { releases++ }
        texture.j()
        texture.invalidate { loads++; loaded }
        assertEquals(1, releases)
        assertEquals(0, loads)
        assertNull(texture.j)
        assertContentEquals(loaded, texture.argbPixelsCopy)
        assertEquals(1, loads)
        assertSame(loaded, texture.argbPixelsRef)
        assertTrue(texture.f())
        texture.invalidate()
        assertNull(texture.argbPixelsRef)
        texture.o()
        assertEquals(1, releases)
        assertEquals(1, loads)
    }

    @Test
    fun `clone resized copy and a surviving editable copy do not retain the shared owner`() {
        val texture = texture()
        val shared = pixels()
        val original = shared.copyOf()
        var releases = 0
        texture.setImmutableArgbPixels(shared) { releases++; shared.fill(0) }
        val clone = texture.clone()
        val resized = texture.a(3, 3, true)
        assertNotSame(shared, clone.argbPixelsRef)
        assertNotSame(shared, resized.argbPixelsRef)
        assertEquals(0, releases)
        clone.a(0, 0, 0xff998877.toInt())
        assertContentEquals(original, shared)
        val resizedExpected = intArrayOf(original[0], original[1], 0,
            original[2], original[3], 0, 0, 0, 0)
        assertContentEquals(resizedExpected, resized.argbPixelsRef)
        val editable = assertNotNull(texture.editablePixels())
        texture.o()
        texture.o()
        assertEquals(1, releases)
        assertContentEquals(original, editable, "a detached editable array may outlive shared owner release")
        assertContentEquals(resizedExpected, resized.argbPixelsRef)
        assertEquals(0xff998877.toInt(), clone.a(0, 0))
        clone.o()
        resized.o()
        assertEquals(1, releases, "derived mutable textures must not inherit the source release callback")
    }

    private class InvalidatableTexture : Texture() {
        fun invalidate(loader: (() -> IntArray?)? = null) = invalidateArgbPixelSnapshot(loader)
    }
}
