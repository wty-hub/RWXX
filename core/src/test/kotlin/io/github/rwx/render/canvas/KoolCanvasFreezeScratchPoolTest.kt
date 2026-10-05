package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun closeRejectsFurtherBorrowAndClearsIdleTables() {
        val pool = KoolCanvasFreezeScratchPool(true)
        val scratch = checkNotNull(pool.borrow())
        scratch.resolvedIds[KoolCanvasTextureId("x")] = KoolCanvasTextureId("y")
        pool.release(scratch)
        assertEquals(1, pool.stats().idle)
        pool.close()
        assertEquals(0, pool.stats().idle)
        assertFailsWith<IllegalStateException> { pool.borrow() }
    }
}
