package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.util.Uint8Buffer
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import de.fabmax.kool.util.useRaw
import kotlin.test.*

class KoolCanvasAtlasPixelPoolTest {
    private val body = KoolCanvasTextureId("shared-page-body")
    private val extra = KoolCanvasTextureId("shared-page-extra")
    private fun store(colour: Int = 0x80123456.toInt()) = object : KoolCanvasTextureStore by KoolCanvasCpuTextureStore() {
        override fun staticArgbImage(id: KoolCanvasTextureId) = KoolCanvasArgbImage(2, 2,
            IntArray(4) { if (id == body) colour else 0x40a1b2c3 })
    }
    private fun command(id: KoolCanvasTextureId) = KoolCanvasCommand.DrawTexture(
        KoolCanvasTextureRef(id, 2, 2), KoolCanvasRect(0f, 0f, 2f, 2f),
        KoolCanvasRect(0f, 0f, 4f, 4f), KoolCanvasPaint.DefaultNearest, KoolCanvasState.Default)
    private fun atlas(store: KoolCanvasTextureStore, pool: KoolCanvasAtlasPixelPool?, incremental: Boolean = false) =
        KoolCanvasSpriteAtlas(store, pageSize = 16, incrementalUploads = incremental).also { atlas -> pool?.let(atlas::sharePixels) }

    /** Model Page's exclusive unpublished loser, retaining the canonical to prevent a weak-only fixture. */
    private fun retainedScratch(pool: KoolCanvasAtlasPixelPool, bytes: ByteArray, dirtyPrefix: Int): Pair<ByteArray, ByteArray> {
        val canonical = pool.intern(bytes.copyOf())
        val unpublished = bytes.copyOf()
        val matched = pool.intern(unpublished)
        assertSame(canonical, matched)
        assertNotSame(unpublished, matched)
        pool.recycleDuplicatePage(unpublished, matched, dirtyPrefixBytes = dirtyPrefix)
        return canonical to unpublished
    }

    @Test fun `only unpublished duplicate pages become blank scratch`() {
        val pool = KoolCanvasAtlasPixelPool(reusePageScratch = true)
        val first = KoolCanvasSpriteAtlas.Page(1, 16, pool)
        val second = KoolCanvasSpriteAtlas.Page(2, 16, pool)
        first.writablePixels().fill(0x7f)
        second.writablePixels().fill(0x7f)
        val canonical = first.rgbaPixels
        val unpublished = second.rgbaPixels
        first.sharePixels(); second.sharePixels()
        assertSame(canonical, first.rgbaPixels)
        assertSame(canonical, second.rgbaPixels)
        assertEquals(1024, pool.scratchRetainedBytes)

        val next = KoolCanvasSpriteAtlas.Page(3, 16, pool)
        assertSame(unpublished, next.rgbaPixels)
        assertTrue(next.rgbaPixels.all { it == 0.toByte() }, "Every byte, including unused rows, must be cleared")
        next.writablePixels()[1023] = 42
        next.sharePixels()
        assertTrue(canonical.all { it == 0x7f.toByte() }, "Reusing scratch must not mutate either canonical reader")
        assertEquals(2, pool.pageBufferAllocations)
        assertEquals(1, pool.scratchReuseHits)
        assertEquals(1, pool.scratchRecycleHits)
        assertEquals(0, pool.scratchRetainedBytes, "An intern miss is published and cannot become scratch")
        val concurrent = KoolCanvasSpriteAtlas.Page(4, 16, pool)
        assertNotSame(next.rgbaPixels, concurrent.rgbaPixels, "A borrowed buffer cannot be lent twice")
        assertTrue(concurrent.rgbaPixels.all { it == 0.toByte() })
    }

    @Test fun `copy on write overwrites whole scratch and preserves both canonical owners`() {
        val pool = KoolCanvasAtlasPixelPool(reusePageScratch = true)
        val first = KoolCanvasSpriteAtlas.Page(1, 16, pool)
        val different = KoolCanvasSpriteAtlas.Page(2, 16, pool)
        val duplicate = KoolCanvasSpriteAtlas.Page(3, 16, pool)
        val expected = ByteArray(1024) { (it * 17 + 13).toByte() }
        first.writablePixels().fill(0x7f)
        expected.copyInto(different.writablePixels())
        duplicate.writablePixels().fill(0x7f)
        val oldFirst = first.rgbaPixels
        val oldDifferent = different.rgbaPixels
        val scratch = duplicate.rgbaPixels
        first.sharePixels(); different.sharePixels(); duplicate.sharePixels()
        val writable = different.writablePixels()
        assertSame(scratch, writable)
        assertContentEquals(expected, writable)
        writable[1023] = 1
        assertContentEquals(expected, oldDifferent)
        assertTrue(oldFirst.all { it == 0x7f.toByte() })
        assertSame(oldFirst, duplicate.rgbaPixels)
    }

    @Test fun `scratch retains at most one page and is dropped on size mismatch and close`() {
        val pool = KoolCanvasAtlasPixelPool(reusePageScratch = true)
        val first = KoolCanvasSpriteAtlas.Page(1, 16, pool)
        val second = KoolCanvasSpriteAtlas.Page(2, 16, pool)
        val third = KoolCanvasSpriteAtlas.Page(3, 16, pool)
        first.sharePixels(); second.sharePixels(); third.sharePixels()
        assertEquals(1, pool.scratchRecycleHits, "A second duplicate must not grow the scratch pool")
        val smaller = KoolCanvasSpriteAtlas.Page(4, 8, pool)
        assertEquals(256, smaller.rgbaPixels.size)
        assertTrue(smaller.rgbaPixels.all { it == 0.toByte() })
        assertEquals(0, pool.scratchRetainedBytes)
        smaller.sharePixels()
        KoolCanvasSpriteAtlas.Page(5, 8, pool).sharePixels()
        assertEquals(256, pool.scratchRetainedBytes)
        pool.close(); pool.close()
        assertEquals(0, pool.scratchRetainedBytes)
        val allocations = pool.pageBufferAllocations
        KoolCanvasSpriteAtlas.Page(6, 8, pool).sharePixels()
        assertEquals(allocations + 1, pool.pageBufferAllocations)
        assertEquals(0, pool.scratchRetainedBytes, "Late use of a closed owner must not retain new scratch")
    }

    @Test fun `generic interning and disabled scratch never consume caller arrays`() {
        for (enabled in listOf(false, true)) {
            val pool = KoolCanvasAtlasPixelPool(reusePageScratch = enabled)
            val first = byteArrayOf(1, 2, 3, 4)
            val duplicate = first.copyOf()
            assertSame(first, pool.intern(first))
            assertSame(first, pool.intern(duplicate))
            assertEquals(0, pool.scratchRetainedBytes)
            assertEquals(0, pool.scratchRecycleHits)
            assertContentEquals(first, duplicate)
        }
        val disabled = KoolCanvasAtlasPixelPool(reusePageScratch = false)
        val first = KoolCanvasSpriteAtlas.Page(1, 16, disabled)
        val duplicate = KoolCanvasSpriteAtlas.Page(2, 16, disabled)
        first.sharePixels(); duplicate.sharePixels()
        assertSame(first.rgbaPixels, duplicate.rgbaPixels)
        assertEquals(0, disabled.scratchRetainedBytes)
        assertEquals(0, disabled.scratchReuseHits)
        assertEquals(0, disabled.scratchRecycleHits)
    }

    @Test fun `oversized unpublished duplicates do not expand the scratch bound`() {
        val pool = KoolCanvasAtlasPixelPool(reusePageScratch = true)
        val canonical = ByteArray(2048 * 2048 * 4 + 1)
        assertSame(canonical, pool.intern(canonical, 17))
        val duplicate = canonical.copyOf()
        val matched = pool.intern(duplicate, 17)
        assertSame(canonical, matched)
        pool.recycleDuplicatePage(duplicate, matched)
        assertEquals(0, pool.scratchRetainedBytes)
        assertEquals(0, pool.scratchRecycleHits)
    }

    @Test fun `prefix COW clears donor from old prefix before a taller narrow write leaves column holes`() {
        val pool = KoolCanvasAtlasPixelPool(reusePageScratch = true)
        val size = 16
        val stride = size * 4
        val oldPrefix = 2 * stride
        val newPrefix = 6 * stride
        val donorPrefix = 10 * stride
        val page = KoolCanvasSpriteAtlas.Page(1, size, pool)
        page.writablePixels(oldPrefix).fill(0x19, 0, oldPrefix)
        page.sharePixels()
        val oldCanonical = page.rgbaPixels
        val oldCopy = oldCanonical.copyOf()
        val (donorCanonical, donor) = retainedScratch(pool,
            ByteArray(size * stride) { if (it < donorPrefix) 0x6d else 0 }, donorPrefix)
        val donorCopy = donorCanonical.copyOf()
        val resetsBefore = pool.scratchResetBytes
        val copiesBefore = pool.pageCopyBytes

        val writable = page.writablePixels(newPrefix)
        assertSame(donor, writable)
        assertContentEquals(oldCopy, writable,
            "COW must clear donor bytes from the OLD prefix, including columns the new blit will not overwrite")
        assertEquals((donorPrefix - oldPrefix).toLong(), pool.scratchResetBytes - resetsBefore)
        assertEquals(oldPrefix.toLong(), pool.pageCopyBytes - copiesBefore)
        val expected = oldCopy.copyOf()
        for (row in 2 until 6) {
            // A new guarded rectangle occupies only the left four columns of each expanded row.
            writable.fill(0x2a, row * stride, row * stride + 4 * 4)
            expected.fill(0x2a, row * stride, row * stride + 4 * 4)
        }
        assertContentEquals(expected, page.rgbaPixels)
        for (row in 2 until 6) assertTrue(page.rgbaPixels
            .copyOfRange(row * stride + 4 * 4, (row + 1) * stride).all { it == 0.toByte() })
        assertContentEquals(oldCopy, oldCanonical, "COW cannot mutate the previously published Page")
        assertContentEquals(donorCopy, donorCanonical, "Reusing a loser cannot mutate its canonical peer")
    }

    @Test fun `tall then short shelf writes and a later row wrap preserve complete pages and guards`() {
        val ids = (0..3).map { KoolCanvasTextureId("prefix-shelf-$it") }
        val dimensions = listOf(2 to 12, 2 to 1, 2 to 1, 20 to 2)
        val images = ids.zip(dimensions).associate { (id, dimensions) ->
            val (width, height) = dimensions
            id to KoolCanvasArgbImage(width, height, IntArray(width * height) { index ->
                // Include nonzero transparent RGB so zeroing/copying must preserve every channel.
                (if (index % 2 == 0) 0 else 0x80000000.toInt()) or (0x00123456 + index)
            })
        }
        val source = object : KoolCanvasTextureStore by KoolCanvasCpuTextureStore() {
            override fun staticArgbImage(id: KoolCanvasTextureId) = images[id]
        }
        val draws = ids.map { id ->
            val image = images.getValue(id)
            KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, image.width, image.height),
                KoolCanvasRect(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                KoolCanvasRect(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                KoolCanvasPaint.DefaultNearest, KoolCanvasState.Default)
        }
        val pool = KoolCanvasAtlasPixelPool(reusePageScratch = true)
        val shared = KoolCanvasSpriteAtlas(source, pageSize = 32).also { it.sharePixels(pool) }
        val control = KoolCanvasSpriteAtlas(source, pageSize = 32)
        try {
            shared.prepare(draws.take(2)); control.prepare(draws.take(2))
            val page = shared.slot(draws[0])!!.page
            val oldCanonical = page.rgbaPixels
            val oldCopy = oldCanonical.copyOf()
            val copiesBefore = pool.pageCopyBytes
            val appended = shared.slot(draws[2])!!
            val expectedAppend = control.slot(draws[2])!!
            assertSame(page, appended.page)
            assertEquals(2, appended.y, "The short append must remain on the tall sprite's shelf")
            assertContentEquals(expectedAppend.page.rgbaPixels, page.rgbaPixels)
            assertEquals((16 * 32 * 4).toLong(), pool.pageCopyBytes - copiesBefore,
                "A short last sprite cannot lower the old tall sprite's row prefix")
            assertContentEquals(oldCopy, oldCanonical)

            shared.prepare(draws.take(3)); control.prepare(draws.take(3))
            val beforeWrap = page.rgbaPixels
            val beforeWrapCopy = beforeWrap.copyOf()
            val wrapped = shared.slot(draws[3])!!
            val expectedWrap = control.slot(draws[3])!!
            assertSame(page, wrapped.page)
            assertEquals(18, wrapped.y, "The next shelf starts after all sixteen padded rows")
            assertContentEquals(expectedWrap.page.rgbaPixels, page.rgbaPixels)
            assertContentEquals(beforeWrapCopy, beforeWrap)
            for (draw in draws) {
                val actual = shared.slot(draw)!!
                val expected = control.slot(draw)!!
                assertEquals(expected.x, actual.x); assertEquals(expected.y, actual.y)
                assertEquals(expected.uv(draw.source), actual.uv(draw.source))
            }
        } finally { shared.clear(); control.clear(); pool.close() }
    }

    @Test fun `empty prefixes stay blank while default writable access conservatively copies the last byte`() {
        val pool = KoolCanvasAtlasPixelPool(reusePageScratch = true)
        val empty = KoolCanvasSpriteAtlas.Page(1, 8, pool)
        empty.sharePixels()
        val oldEmpty = empty.rgbaPixels
        val copiesBeforeEmpty = pool.pageCopyBytes
        assertContentEquals(ByteArray(256), empty.writablePixels(0))
        assertEquals(copiesBeforeEmpty, pool.pageCopyBytes, "A genuinely empty Page needs no source copy")
        assertContentEquals(ByteArray(256), oldEmpty)

        val manual = KoolCanvasSpriteAtlas.Page(2, 8, pool)
        val expected = ByteArray(256) { (it * 11 + 3).toByte() }
        expected.copyInto(manual.writablePixels()) // Unbounded manual writes must declare fullsize by default.
        manual.sharePixels()
        val oldCanonical = manual.rgbaPixels
        val copiesBefore = pool.pageCopyBytes
        val writable = manual.writablePixels(0)
        assertContentEquals(expected, writable)
        assertEquals(256L, pool.pageCopyBytes - copiesBefore)
        writable[255] = 99
        assertContentEquals(expected, oldCanonical)
    }

    @Test fun `scratch prefix reset and copy cover zero full and shorter donor boundaries`() {
        for ((sourcePrefix, donorPrefix) in listOf(0 to 48, 16 to 48, 48 to 16, 64 to 64)) {
            val pool = KoolCanvasAtlasPixelPool(reusePageScratch = true)
            val source = ByteArray(64) { if (it < sourcePrefix) (it + 1).toByte() else 0 }
            val (canonical, scratch) = retainedScratch(pool,
                ByteArray(64) { if (it < donorPrefix) 0x73 else 0 }, donorPrefix)
            val originalCanonical = canonical.copyOf()
            val resetBefore = pool.scratchResetBytes
            val copyBefore = pool.pageCopyBytes
            val actual = pool.copyPagePixels(source, sourcePrefixBytes = sourcePrefix)
            assertSame(scratch, actual)
            assertContentEquals(source, actual, "sourcePrefix=$sourcePrefix donorPrefix=$donorPrefix")
            assertEquals(maxOf(0, donorPrefix - sourcePrefix).toLong(), pool.scratchResetBytes - resetBefore)
            assertEquals(sourcePrefix.toLong(), pool.pageCopyBytes - copyBefore)
            assertContentEquals(originalCanonical, canonical)
        }
        for (dirtyPrefix in listOf(0, 21, 64)) {
            val pool = KoolCanvasAtlasPixelPool(reusePageScratch = true)
            val (canonical, scratch) = retainedScratch(pool,
                ByteArray(64) { if (it < dirtyPrefix) 0x5d else 0 }, dirtyPrefix)
            val originalCanonical = canonical.copyOf()
            val resetBefore = pool.scratchResetBytes
            assertSame(scratch, pool.blankPagePixels(64))
            assertContentEquals(ByteArray(64), scratch)
            assertEquals(dirtyPrefix.toLong(), pool.scratchResetBytes - resetBefore)
            assertContentEquals(originalCanonical, canonical)
        }
        val generic = KoolCanvasAtlasPixelPool(reusePageScratch = true)
        val canonical = generic.intern(ByteArray(64) { 0x42 })
        val duplicate = canonical.copyOf()
        assertSame(canonical, generic.intern(duplicate))
        generic.recycleDuplicatePage(duplicate, canonical) // Generic default must clear the complete array.
        val resetBefore = generic.scratchResetBytes
        assertContentEquals(ByteArray(64), generic.blankPagePixels(64))
        assertEquals(64L, generic.scratchResetBytes - resetBefore)
        val source = ByteArray(64) { (it + 7).toByte() }
        val copyBefore = generic.pageCopyBytes
        assertContentEquals(source, generic.copyPagePixels(source))
        assertEquals(64L, generic.pageCopyBytes - copyBefore)
    }

    @Test fun `invalid prefix ranges reject before changing published pages or scratch ownership`() {
        for (enabled in listOf(false, true)) {
            val pool = KoolCanvasAtlasPixelPool(reusePageScratch = enabled)
            val page = KoolCanvasSpriteAtlas.Page(1, 4, pool)
            page.writablePixels().fill(0x17)
            page.sharePixels()
            val oldCanonical = page.rgbaPixels
            val oldCopy = oldCanonical.copyOf()
            val copyBefore = pool.pageCopyBytes
            for (invalid in listOf(-1, 65)) {
                assertFailsWith<IllegalArgumentException> { page.writablePixels(invalid) }
                assertSame(oldCanonical, page.rgbaPixels)
                assertContentEquals(oldCopy, oldCanonical)
                assertFailsWith<IllegalArgumentException> { pool.copyPagePixels(oldCanonical, sourcePrefixBytes = invalid) }
                assertFailsWith<IllegalArgumentException> {
                    pool.recycleDuplicatePage(oldCopy.copyOf(), oldCanonical, dirtyPrefixBytes = invalid)
                }
            }
            assertEquals(copyBefore, pool.pageCopyBytes)
            assertEquals(0, pool.scratchRetainedBytes)
        }
    }

    @Test fun `hash collisions cannot merge different image bytes`() {
        val pool = KoolCanvasAtlasPixelPool()
        val first = byteArrayOf(0, 31)
        val collision = byteArrayOf(1, 0)
        assertEquals(first.contentHashCode(), collision.contentHashCode())
        assertSame(first, pool.intern(first))
        assertSame(collision, pool.intern(collision))
        assertSame(first, pool.intern(first.copyOf()))
        assertSame(collision, pool.intern(collision.copyOf()))
        assertEquals(2, pool.reuseHits)

        val fingerprintPool = KoolCanvasAtlasPixelPool()
        val fingerprint = 17
        assertSame(first, fingerprintPool.intern(first, fingerprint))
        assertSame(collision, fingerprintPool.intern(collision, fingerprint))
        assertSame(first, fingerprintPool.intern(first.copyOf(), fingerprint))
        assertSame(collision, fingerprintPool.intern(collision.copyOf(), fingerprint))
        assertEquals(2, fingerprintPool.reuseHits)
    }

    @Test fun `shared upload views have independent cursors and reject writes`() {
        val pool = KoolCanvasAtlasPixelPool()
        val pixels = pool.intern(byteArrayOf(11, 22, 33, 44))
        val first = pool.uploadView(pixels)
        val second = pool.uploadView(pixels)
        assertNotSame(first, second)
        assertEquals(1, pool.uploadReuseHits)
        first.position = 2; first.limit = 3
        assertEquals(0, second.position); assertEquals(4, second.limit)
        first.useRaw { raw ->
            assertTrue(raw.isReadOnly)
            assertEquals(0, raw.position()); assertEquals(3, raw.limit())
            raw.get()
            second.useRaw { other -> assertEquals(0, other.position()); assertEquals(4, other.limit()) }
            assertEquals(1, raw.position(), "Another texture's upload cursor must not reset this upload")
        }
        assertFailsWith<ReadOnlyBufferException> { first[0] = 99u }
        assertContentEquals(byteArrayOf(11, 22, 33, 44), second.toArray())
    }

    @Test fun `equal pages from independent atlases share bytes without changing layout or guards`() {
        val pool = KoolCanvasAtlasPixelPool()
        val source = store()
        val first = atlas(source, pool)
        val second = atlas(source, pool)
        val control = atlas(source, null)
        val commands = listOf(command(body), command(extra))
        listOf(first, second, control).forEach { it.prepare(commands) }
        val a = first.slot(commands[0])!!
        val b = second.slot(commands[0])!!
        val expected = control.slot(commands[0])!!
        assertSame(a.page.rgbaPixels, b.page.rgbaPixels)
        assertNotSame(a.page, b.page)
        assertContentEquals(expected.page.rgbaPixels, a.page.rgbaPixels)
        for (draw in commands) {
            val actual = first.slot(draw)!!
            val original = control.slot(draw)!!
            assertEquals(original.uv(draw.source), actual.uv(draw.source))
            assertEquals(original.uv(KoolCanvasRect(2f, 2f, 0f, 0f)), actual.uv(KoolCanvasRect(2f, 2f, 0f, 0f)))
        }
        assertEquals(1, pool.reuseHits)
        first.clear()
        assertContentEquals(expected.page.rgbaPixels, b.page.rgbaPixels, "Clearing another atlas cannot mutate a borrowed CPU page")
        second.clear(); control.clear()
    }

    @Test fun `different texture IDs with equal pixels and layout share a page`() {
        fun uniformStore() = object : KoolCanvasTextureStore by KoolCanvasCpuTextureStore() {
            override fun staticArgbImage(id: KoolCanvasTextureId) =
                KoolCanvasArgbImage(2, 2, IntArray(4) { 0x80123456.toInt() })
        }
        val pool = KoolCanvasAtlasPixelPool()
        val first = atlas(uniformStore(), pool)
        val second = atlas(uniformStore(), pool)
        val firstDraws = (0..2).map { command(KoolCanvasTextureId("first-uniform-$it")) }
        val secondDraws = (0..2).map { command(KoolCanvasTextureId("second-uniform-$it")) }
        try {
            first.prepare(firstDraws); second.prepare(secondDraws)
            val firstPage = first.slot(firstDraws.first())!!.page
            val secondPage = second.slot(secondDraws.first())!!.page
            assertNotSame(firstPage, secondPage)
            assertSame(firstPage.rgbaPixels, secondPage.rgbaPixels,
                "Source IDs must not become part of the pixel/layout fingerprint")
            firstDraws.zip(secondDraws).forEach { (firstDraw, secondDraw) ->
                val a = first.slot(firstDraw)!!
                val b = second.slot(secondDraw)!!
                assertSame(firstPage, a.page); assertSame(secondPage, b.page)
                assertEquals(a.x, b.x); assertEquals(a.y, b.y)
                assertEquals(a.uv(firstDraw.source), b.uv(secondDraw.source))
            }
            assertEquals(1, pool.reuseHits)
        } finally {
            first.clear(); second.clear()
        }
    }

    @Test fun `same texture IDs in different stores do not alias different content`() {
        val pool = KoolCanvasAtlasPixelPool()
        val red = atlas(store(0xffff0000.toInt()), pool)
        val blue = atlas(store(0xff0000ff.toInt()), pool)
        val commands = listOf(command(body))
        red.prepare(commands); blue.prepare(commands)
        val a = red.slot(commands[0])!!.page.rgbaPixels
        val b = blue.slot(commands[0])!!.page.rgbaPixels
        assertNotSame(a, b)
        assertFalse(a.contentEquals(b))
        assertEquals(0, pool.reuseHits)
        red.clear(); blue.clear()
    }

    @Test fun `growing one shared page preserves peers and pending upload owners`() {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        for (incremental in listOf(false, true)) for (scratch in listOf(false, true)) {
            val pool = KoolCanvasAtlasPixelPool(reusePageScratch = scratch)
            val first = atlas(store(), pool, incremental)
            val second = atlas(store(), pool, incremental)
            val pending = mutableListOf<() -> Unit>()
            val appended = mutableListOf<KoolCanvasAtlasAppend>()
            KoolCanvasGpuRetirement.install { pending += it }
            KoolCanvasAtlasUpdates.install(if (incremental) { _, regions -> appended += regions; true } else null)
            try {
                val bodyDraw = command(body)
                first.prepare(listOf(bodyDraw)); second.prepare(listOf(bodyDraw))
                val firstPage = first.slot(bodyDraw)!!.page
                val secondPage = second.slot(bodyDraw)!!.page
                val oldBytes = firstPage.rgbaPixels
                assertSame(oldBytes, secondPage.rgbaPixels)
                val oldCopy = oldBytes.copyOf()
                val oldTexture = first.texture(firstPage, KoolCanvasTextureFilter.Nearest)
                val oldUpload = assertIs<Uint8Buffer>(assertIs<BufferedImageData2d>(oldTexture.uploadData).data)
                val uploaded = oldUpload.toArray()
                // Exercise both regular prepare growth and the late slot()/texture() publication.
                val extraDraw = command(extra)
                first.slot(extraDraw)
                first.texture(firstPage, KoolCanvasTextureFilter.Linear)
                assertNotSame(oldBytes, firstPage.rgbaPixels)
                assertSame(oldBytes, secondPage.rgbaPixels)
                assertContentEquals(oldCopy, oldBytes)
                assertContentEquals(uploaded, oldUpload.toArray())
                if (incremental) {
                    assertSame(oldTexture, first.texture(firstPage, KoolCanvasTextureFilter.Nearest))
                    val patch = appended.single()
                    val bytes = ByteBuffer.allocate(patch.byteCount)
                    patch.copyTo(bytes)
                    assertContentEquals(ByteArray(6 * 6 * 4) { index ->
                        intArrayOf(0xa1, 0xb2, 0xc3, 0x40)[index % 4].toByte()
                    }, bytes.array())
                    assertTrue(pending.isEmpty(), "Successful append must keep the existing upload owner live")
                }
                first.prepare(listOf(bodyDraw, extraDraw))
                second.prepare(listOf(bodyDraw, extraDraw))
                assertSame(firstPage.rgbaPixels, secondPage.rgbaPixels)
                assertTrue(pool.reuseHits >= 2)
                first.clear(); pending.toList().forEach { it() }; pending.clear()
                assertTrue(!secondPage.rgbaPixels.contentEquals(oldCopy))
                assertTrue(second.texture(secondPage, KoolCanvasTextureFilter.Nearest).uploadData != null)
            } finally {
                first.clear(); second.clear()
                pending.toList().forEach { it() }
                KoolCanvasGpuRetirement.install(null)
                KoolCanvasAtlasUpdates.install(null)
            }
        }
    }
}
