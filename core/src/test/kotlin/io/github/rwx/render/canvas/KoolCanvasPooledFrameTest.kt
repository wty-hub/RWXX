package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolCanvasPooledFrameTest {
    private fun draw(id: KoolCanvasTextureId) = KoolCanvasCommand.DrawTexture(
        KoolCanvasTextureRef(id, 1, 1), KoolCanvasRect.fromSize(1f, 1f),
        KoolCanvasRect.fromSize(1f, 1f), KoolCanvasPaint.Default, KoolCanvasState.Default)

    private fun KoolCanvasCpuTextureStore.freeze(sequence: Long, ids: List<KoolCanvasTextureId>) =
        freezeFrame(KoolCanvasFrame(KoolCanvasViewport(100, 100), ids.map(::draw)), sequence, 1, 42, 3)

    private fun KoolCanvasCpuTextureStore.freeze(sequence: Long, id: KoolCanvasTextureId) = freeze(sequence, listOf(id))

    @Test
    fun sharedSourceAliasesAreRetainedOncePerFrozenLease() {
        val pool = KoolCanvasPixelPool()
        val store = KoolCanvasCpuTextureStore(pool, true)
        val firstId = KoolCanvasTextureId("pooled-alias-first")
        val secondId = KoolCanvasTextureId("pooled-alias-second")
        val raster = pool.borrow(1)
        val pixels = raster.pixels
        pixels[0] = 0xff123456.toInt()
        store.registerPooledArgb(firstId, 1, 1, raster)
        store.registerPooledArgb(secondId, 1, 1, raster)
        assertEquals(3, raster.allocation.ownerCount)
        val frame = store.freeze(1, listOf(firstId, firstId, secondId))
        assertEquals(2, frame.resourceLease.resourceCount)
        assertEquals(4, raster.allocation.ownerCount) // Two resources, one allocation pin.
        raster.close()
        store.unregister(firstId)
        store.unregister(secondId)
        assertEquals(1, raster.allocation.ownerCount)
        assertEquals(0, pool.stats().retainedArrays)
        frame.resourceLease.resources().values.forEach {
            assertSame(pixels, (it as FrozenCanvasResource.Pixels).image.pixels)
        }
        frame.close()
        assertEquals(0, raster.allocation.ownerCount)
        val reused = pool.borrow(1)
        assertSame(pixels, reused.pixels)
        reused.close()
        store.close()
    }

    @Test
    fun gpuInstallPinsPooledPixelsThroughFenceEvenAfterFrontAndSourcesClose() {
        val pool = KoolCanvasPixelPool()
        val store = KoolCanvasCpuTextureStore(pool, true)
        val id = KoolCanvasTextureId("pooled-fence")
        val raster = pool.borrow(1)
        val pixels = raster.pixels
        pixels[0] = 0xffabcdef.toInt()
        val legacyTextureOwner = raster.retain()
        store.registerPooledArgb(id, 1, 1, raster)
        val frame = store.freeze(1, id)
        val versionId = (frame.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        val installed = FrozenCanvasGpuResources.install(frame.resourceLease)
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            raster.close()
            frame.close() // The installed resource must not rely on this front owner remaining open.
            val replacement = pool.borrow(1)
            replacement.pixels[0] = 0xff987654.toInt()
            store.registerPooledArgb(id, 1, 1, replacement)
            replacement.close()
            legacyTextureOwner.close()
            assertEquals(1, raster.allocation.ownerCount)
            assertFalse(frame.resourceLease.isReleased)
            assertSame(pixels, KoolCanvasTextureRegistry.argbImageView(versionId)!!.pixels)
            KoolCanvasGpuRetirement.retire { installed.close() }
            assertEquals(1, pending.size)
            val concurrent = pool.borrow(1)
            assertNotSame(pixels, concurrent.pixels)
            concurrent.close()
            store.close() // Sources/pool may end while the installed lease still owns its pixels.
            assertEquals(0xffabcdef.toInt(), KoolCanvasTextureRegistry.argbImageView(versionId)!!.pixels[0])
            pending.removeAt(0).invoke()
            assertTrue(frame.resourceLease.isReleased)
            assertNull(KoolCanvasTextureRegistry.argbImageView(versionId))
            assertEquals(0, pool.stats().activeAllocations)
            assertEquals(0, pool.stats().retainedArrays)
            installed.close() // The install owner itself is idempotent.
        } finally {
            pending.forEach { it() }
            KoolCanvasGpuRetirement.install(null)
            installed.close()
            frame.close()
            legacyTextureOwner.close()
            raster.close()
            store.close()
        }
    }

    @Test
    fun overwrittenMailboxFrameReturnsOnlyItsOwnRetiredRaster() {
        val pool = KoolCanvasPixelPool()
        val store = KoolCanvasCpuTextureStore(pool, true)
        val mailbox = LatestFrameMailbox()
        val id = KoolCanvasTextureId("pooled-mailbox")
        fun publish(sequence: Long): Pair<FrameEnvelope, IntArray> {
            val raster = pool.borrow(1)
            val pixels = raster.pixels
            pixels[0] = sequence.toInt()
            store.registerPooledArgb(id, 1, 1, raster)
            raster.close()
            return store.freeze(sequence, id).also(mailbox::publish) to pixels
        }
        val (first, firstPixels) = publish(1)
        assertSame(first, mailbox.poll())
        val (dropped, droppedPixels) = publish(2)
        val (latest, latestPixels) = publish(3)
        assertTrue(dropped.resourceLease.isReleased)
        assertEquals(2, pool.stats().activeAllocations) // Shown old raster plus latest source/lease.
        val reused = pool.borrow(1)
        assertSame(droppedPixels, reused.pixels)
        assertNotSame(firstPixels, reused.pixels)
        assertNotSame(latestPixels, reused.pixels)
        reused.close()
        mailbox.close()
        assertTrue(latest.resourceLease.isReleased)
        store.unregister(id)
        assertEquals(1, pool.stats().activeAllocations)
        first.close()
        assertEquals(0, pool.stats().activeAllocations)
        store.close()
    }

    @Test
    fun failedGpuInstallationRollsBackRegistrationAndExtraLeasePin() {
        val pool = KoolCanvasPixelPool()
        val store = KoolCanvasCpuTextureStore(pool, true)
        val ids = listOf(KoolCanvasTextureId("pooled-install-first"), KoolCanvasTextureId("pooled-install-second"))
        ids.forEachIndexed { index, id ->
            val raster = pool.borrow(1)
            raster.pixels[0] = index + 1
            store.registerPooledArgb(id, 1, 1, raster)
            raster.close()
        }
        val frame = store.freeze(1, ids)
        val versionIds = frame.resourceLease.resources().keys.toList()
        ids.forEach(store::unregister)
        var attempted = 0
        val failure = IllegalStateException("injected install failure")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            FrozenCanvasGpuResources.install(frame.resourceLease) { id, resource ->
                if (++attempted == 2) throw failure
                val image = (resource as FrozenCanvasResource.Pixels).image
                KoolCanvasTextureRegistry.installFrozenImage(id, image, false)
            }
        })
        versionIds.forEach { assertNull(KoolCanvasTextureRegistry.argbImageView(it)) }
        assertFalse(frame.resourceLease.isReleased)
        assertEquals(2, pool.stats().activeAllocations)
        val retry = FrozenCanvasGpuResources.install(frame.resourceLease)
        versionIds.forEach { assertTrue(KoolCanvasTextureRegistry.argbImageView(it) != null) }
        frame.close()
        assertFalse(frame.resourceLease.isReleased)
        retry.close()
        assertTrue(frame.resourceLease.isReleased)
        assertEquals(0, pool.stats().activeAllocations)
        versionIds.forEach { assertNull(KoolCanvasTextureRegistry.argbImageView(it)) }
        store.close()
    }

    @Test
    fun alphaBleedingAndInvalidRegistrationNeverClaimTheCallerArray() {
        val pool = KoolCanvasPixelPool()
        val store = KoolCanvasCpuTextureStore(pool, true)
        val id = KoolCanvasTextureId("pooled-alpha-bleed")
        val raster = pool.borrow(2)
        raster.pixels[0] = 0xff123456.toInt()
        store.registerPooledArgb(id, 2, 1, raster, alphaBleed = true)
        assertEquals(1, raster.allocation.ownerCount)
        assertNotSame(raster.pixels, store.argbImageView(id)!!.pixels)
        val frame = store.freeze(1, id)
        assertNull((frame.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels).pixelOwner)
        assertFailsWith<IllegalArgumentException> { store.registerPooledArgb(id, 1, 1, raster) }
        assertEquals(1, raster.allocation.ownerCount)
        assertEquals(2, store.argbImageView(id)!!.width)
        store.registerPooledArgb(id, 0, 1, raster)
        assertNull(store.argbImageView(id))
        raster.close()
        assertEquals(0, pool.stats().activeAllocations)
        frame.close()
        store.close()
    }

    @Test
    fun commonTargetPreallocationRunsOnceAndDisabledModeNeverBorrows() {
        val pool = KoolCanvasPixelPool()
        val store = KoolCanvasCpuTextureStore(pool, true)
        store.preallocateTargetPixels(2, 2)
        store.preallocateTargetPixels(1, 4) // Exact array size is the pool key, not aspect ratio.
        assertEquals(48, pool.stats().retainedArrays)
        assertEquals(48L, pool.stats().preallocatedArrays)
        val raster = store.borrowTargetPixels(2, 2)!!
        assertEquals(48L, pool.stats().allocations)
        assertEquals(0L, pool.stats().misses)
        assertEquals(1L, pool.stats().reusedArrays)
        raster.close()
        store.clearIdlePixelPool()
        store.preallocateTargetPixels(2, 2)
        assertEquals(0, pool.stats().retainedArrays) // Trim does not re-run factory preallocation.
        assertTrue(store.pixelPoolDiagnostics().contains("preallocatedArrays=48"))
        store.close()

        val disabledPool = KoolCanvasPixelPool()
        val disabled = KoolCanvasCpuTextureStore(disabledPool, false)
        disabled.preallocateTargetPixels(2, 2)
        assertNull(disabled.borrowTargetPixels(2, 2))
        assertEquals(0L, disabledPool.stats().allocations)
        assertTrue(disabled.pixelPoolDiagnostics().startsWith("enabled=false"))
        disabled.close()
    }
}
