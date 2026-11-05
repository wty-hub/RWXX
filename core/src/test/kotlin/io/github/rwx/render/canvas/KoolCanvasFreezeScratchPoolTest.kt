package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolCanvasFreezeScratchPoolTest {
    private fun draw(ref: KoolCanvasTextureRef) = KoolCanvasCommand.DrawTexture(
        ref, KoolCanvasRect.fromSize(1f, 1f), KoolCanvasRect.fromSize(1f, 1f),
        KoolCanvasPaint.Default, KoolCanvasState.Default)

    private fun frame(command: KoolCanvasCommand) =
        KoolCanvasFrame(KoolCanvasViewport(8, 8), listOf(command))

    private fun KoolCanvasCpuTextureStore.freeze(ref: KoolCanvasTextureRef, sequence: Long = 1) =
        freezeFrame(frame(draw(ref)), sequence, 1, 0, 0)

    @Test
    fun enabledPoolReusesOnlyTemporaryTablesAndKeepsOldLeasePixels() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, true)
        val id = KoolCanvasTextureId("scratch-reuse")
        try {
            store.registerArgb(id, 1, 1, intArrayOf(7), false)
            val old = store.freeze(KoolCanvasTextureRef(id, 1, 1))
            repeat(12) { index ->
                store.registerArgb(id, 1, 1, intArrayOf(10 + index), false)
                val next = store.freeze(KoolCanvasTextureRef(id, 1, 1), index + 2L)
                next.close()
            }
            assertEquals(12L, store.freezeScratchPoolStats().reused)
            assertEquals(0, store.freezeScratchPoolStats().active)
            assertEquals(7, (old.resourceLease.resources()[
                (old.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            ] as FrozenCanvasResource.Pixels).image.pixels[0])
            old.close()
        } finally {
            store.close()
        }
    }

    @Test
    fun overflowCompletesFrameButScratchIsDiscarded() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, true)
        val id = KoolCanvasTextureId("scratch-overflow")
        try {
            store.registerArgb(id, 1, 1, intArrayOf(9), false)
            val commands = List(8193) { draw(KoolCanvasTextureRef(id, 1, 1)) }
            val packet = store.freezeFrame(KoolCanvasFrame(KoolCanvasViewport(8, 8), commands), 1, 1, 0, 0)
            try {
                assertEquals(8193, packet.frame.commands.size)
                assertTrue(store.freezeScratchPoolStats().peakMemoEntries >= 8193)
                assertEquals(0, store.freezeScratchPoolStats().idle)
            } finally {
                packet.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun disabledModeDoesNotBorrowScratch() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, false)
        val id = KoolCanvasTextureId("scratch-disabled")
        try {
            store.registerArgb(id, 1, 1, intArrayOf(3), false)
            val packet = store.freeze(KoolCanvasTextureRef(id, 1, 1))
            packet.close()
            val stats = store.freezeScratchPoolStats()
            assertFalse(stats.enabled)
            assertEquals(0L, stats.borrows)
            assertEquals(0, stats.idle)
        } finally {
            store.close()
        }
    }

    @Test
    fun sameRecordedFrameResolvesChangedLeafAfterScratchReuse() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, true, reuseFrozenContent = false)
        val leaf = KoolCanvasTextureId("scratch-frame-leaf")
        val target = KoolCanvasTextureId("scratch-frame-target")
        val recorded = frame(draw(KoolCanvasTextureRef(target, 8, 8)))
        val packets = mutableListOf<FrameEnvelope>()
        fun frozenLeaf(packet: FrameEnvelope): KoolCanvasTextureId {
            val targetId = (packet.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            val nested = (packet.resourceLease.resources().getValue(targetId) as FrozenCanvasResource.Frame).frame
            return (nested.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        }
        try {
            store.registerArgb(leaf, 1, 1, intArrayOf(7), false)
            store.registerFrame(target, frame(draw(KoolCanvasTextureRef(leaf, 1, 1))))
            val old = store.freezeFrame(recorded, 1, 1, 0, 0).also(packets::add)
            val oldLeaf = frozenLeaf(old)
            store.registerArgb(leaf, 1, 1, intArrayOf(9), false)
            // Reuse both the root recording and the unchanged nested target recording objects.
            val next = store.freezeFrame(recorded, 2, 1, 0, 0).also(packets::add)
            val nextLeaf = frozenLeaf(next)
            assertEquals(1L, store.freezeScratchPoolStats().reused)
            assertNotSame(old.frame, next.frame)
            assertNotEquals(oldLeaf, nextLeaf)
            assertEquals(7, (old.resourceLease.resources().getValue(oldLeaf) as FrozenCanvasResource.Pixels).image.pixels[0])
            assertEquals(9, (next.resourceLease.resources().getValue(nextLeaf) as FrozenCanvasResource.Pixels).image.pixels[0])
        } finally {
            packets.forEach(FrameEnvelope::close)
            store.close()
        }
    }

    @Test
    fun failedFreezeReleasesScratchBeforeNextTraversal() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, true, reuseFrozenContent = false)
        val leaf = KoolCanvasTextureId("scratch-failed-leaf")
        val target = KoolCanvasTextureId("scratch-failed-target")
        try {
            store.registerArgb(leaf, 1, 1, intArrayOf(7), false)
            store.registerFrame(target, frame(draw(KoolCanvasTextureRef(leaf, 1, 1))))
            val command = draw(KoolCanvasTextureRef(target, 8, 8))
            val broken = KoolCanvasFrame(KoolCanvasViewport(8, 8), object : AbstractList<KoolCanvasCommand>() {
                override val size = 2
                override fun get(index: Int): KoolCanvasCommand =
                    if (index == 0) command else error("Recording failed after resolving a nested frame")
            })
            assertFailsWith<IllegalStateException> { store.freezeFrame(broken, 1, 1, 0, 0) }
            assertEquals(0, store.freezeScratchPoolStats().active)
            store.registerArgb(leaf, 1, 1, intArrayOf(9), false)
            val next = store.freezeFrame(frame(command), 2, 1, 0, 0)
            try {
                assertEquals(1L, store.freezeScratchPoolStats().reused)
                assertEquals(9, next.resourceLease.resources().values
                    .filterIsInstance<FrozenCanvasResource.Pixels>().single().image.pixels[0])
            } finally {
                next.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun releasedFrameMemoIsEmptyWhenReborrowed() {
        val pool = KoolCanvasFreezeScratchPool(true)
        val scratch = checkNotNull(pool.borrow())
        val recorded = KoolCanvasFrame(KoolCanvasViewport(8, 8), emptyList())
        val frozen = recorded.copy()
        scratch.frozenFrames[recorded] = frozen
        pool.release(scratch)
        assertTrue(scratch.frozenFrames.isEmpty(), "Idle tables must release both recorded and published frames")
        val reused = checkNotNull(pool.borrow())
        try {
            assertSame(scratch, reused)
            assertTrue(reused.frozenFrames.isEmpty())
        } finally {
            pool.release(reused)
            pool.close()
        }
    }

    @Test
    fun oversizedFrameMemoIsClearedAndDiscarded() {
        val pool = KoolCanvasFreezeScratchPool(true)
        val scratch = checkNotNull(pool.borrow())
        // Equal-valued recordings are still distinct traversal nodes, so every entry must count.
        repeat(KoolCanvasFreezeScratchPool.MAX_MEMO_ENTRIES + 1) {
            val recorded = KoolCanvasFrame(KoolCanvasViewport(8, 8), emptyList())
            scratch.frozenFrames[recorded] = recorded.copy()
        }
        pool.release(scratch)
        assertTrue(scratch.frozenFrames.isEmpty())
        assertEquals(KoolCanvasFreezeScratchPool.MAX_MEMO_ENTRIES + 1, pool.stats().peakMemoEntries)
        assertEquals(1L, pool.stats().discarded)
        assertEquals(0, pool.stats().idle)
        val replacement = checkNotNull(pool.borrow())
        try {
            assertNotSame(scratch, replacement)
        } finally {
            pool.release(replacement)
            pool.close()
        }
    }

    @Test
    fun reentrantBorrowAndCloseKeepActiveFrameMemosIndependent() {
        val pool = KoolCanvasFreezeScratchPool(true)
        val outer = checkNotNull(pool.borrow())
        val recorded = KoolCanvasFrame(KoolCanvasViewport(8, 8), emptyList())
        val frozen = recorded.copy()
        outer.frozenFrames[recorded] = frozen
        val inner = checkNotNull(pool.borrow())
        assertNotSame(outer, inner)
        inner.frozenFrames[recorded] = recorded
        pool.release(inner)
        assertSame(frozen, outer.frozenFrames[recorded])
        assertTrue(inner.frozenFrames.isEmpty())
        pool.close()
        assertSame(frozen, outer.frozenFrames[recorded], "Closing the pool cannot clear an active traversal")
        pool.release(outer)
        assertTrue(outer.frozenFrames.isEmpty())
        assertEquals(0, pool.stats().active)
        assertEquals(0, pool.stats().idle)
        assertEquals(1L, pool.stats().discarded)
    }

    @Test
    fun closeRejectsFurtherBorrowAndClearsIdleTables() {
        val pool = KoolCanvasFreezeScratchPool(true)
        val scratch = checkNotNull(pool.borrow())
        scratch.resolvedIds[KoolCanvasTextureId("x")] = KoolCanvasTextureId("y")
        val recorded = KoolCanvasFrame(KoolCanvasViewport(8, 8), emptyList())
        scratch.frozenFrames[recorded] = recorded.copy()
        pool.release(scratch)
        assertEquals(1, pool.stats().idle)
        assertTrue(scratch.frozenFrames.isEmpty())
        pool.close()
        assertEquals(0, pool.stats().idle)
        assertFailsWith<IllegalStateException> { pool.borrow() }
    }
}
