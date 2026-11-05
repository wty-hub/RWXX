package io.github.rwx.render.canvas

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Run unchanged with no diagnostics, metrics-only, Canvas trace, reuse trace, or both traces. */
class KoolCanvasFreezeDiagnosticsTest {
    private val detailedAccounting: Boolean
        get() = CanvasRenderStageTrace.enabled || System.getenv("RWX_REUSE_TRACE") == "1"

    private fun counter(name: String): Long =
        (KoolCanvasCpuTextureStore::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(null) as AtomicLong).get()

    @Suppress("UNCHECKED_CAST")
    private fun ids(name: String): Map<String, Long> =
        (KoolCanvasCpuTextureStore::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(null) as Map<String, Long>).toMap()

    private fun draw(id: KoolCanvasTextureId) = KoolCanvasCommand.DrawTexture(
        KoolCanvasTextureRef(id, 1, 1), KoolCanvasRect.fromSize(1f, 1f),
        KoolCanvasRect.fromSize(1f, 1f), KoolCanvasPaint.Default, KoolCanvasState.Default,
    )

    private fun frame(id: KoolCanvasTextureId) = KoolCanvasFrame(KoolCanvasViewport(8, 8), listOf(draw(id)))

    private fun nested(packet: FrameEnvelope): KoolCanvasFrame {
        val id = (packet.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        return (packet.resourceLease.resources().getValue(id) as FrozenCanvasResource.Frame).frame
    }

    private fun pixels(packet: FrameEnvelope): Int = packet.resourceLease.resources().values
        .filterIsInstance<FrozenCanvasResource.Pixels>().single().image.pixels.single()

    @Test
    fun `actual cache hits remain observable while auxiliary accounting follows its consumers`() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, reuseFrozenContent = true)
        val leaf = KoolCanvasTextureId("diagnostics-hit-leaf")
        val target = KoolCanvasTextureId("diagnostics-hit-target")
        val packets = mutableListOf<FrameEnvelope>()
        try {
            store.registerArgb(leaf, 1, 1, intArrayOf(0xff123456.toInt()), false)
            store.registerFrame(target, frame(leaf))
            val first = store.freezeFrame(frame(target), 1, 1, 0, 0).also(packets::add)
            val attempts = counter("REUSE_ATTEMPTS")
            val hits = counter("REUSE_HITS")
            val checks = counter("REUSE_DEPS_CHECKS")
            val sum = counter("REUSE_DEPS_SUM")
            val maximum = counter("REUSE_DEPS_MAX")
            val second = store.freezeFrame(frame(target), 2, 1, 0, 0).also(packets::add)

            // Identity is the actual reused immutable target, not merely equality of full traversals.
            assertSame(nested(first), nested(second))
            assertEquals(first.frame, second.frame)
            assertEquals(first.resourceLease.resources(), second.resourceLease.resources())
            assertEquals(attempts + 1, counter("REUSE_ATTEMPTS"))
            assertEquals(hits + 1, counter("REUSE_HITS"))
            if (detailedAccounting) {
                assertEquals(checks + 1, counter("REUSE_DEPS_CHECKS"))
                // The recorded target's own version and its leaf both belong to this closure.
                assertEquals(sum + 2, counter("REUSE_DEPS_SUM"))
                assertTrue(counter("REUSE_DEPS_MAX") >= maxOf(maximum, 2L))
            } else {
                assertEquals(checks, counter("REUSE_DEPS_CHECKS"))
                assertEquals(sum, counter("REUSE_DEPS_SUM"))
                assertEquals(maximum, counter("REUSE_DEPS_MAX"))
            }
            assertEquals(0xff123456.toInt(), pixels(first))
        } finally {
            packets.forEach(FrameEnvelope::close)
            store.close()
        }
    }

    @Test
    fun `changed and missing dependencies still reject reuse and retain old lease pixels`() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, reuseFrozenContent = true)
        val leaf = KoolCanvasTextureId("diagnostics-invalidated-leaf")
        val target = KoolCanvasTextureId("diagnostics-invalidated-target")
        val packets = mutableListOf<FrameEnvelope>()
        try {
            store.registerArgb(leaf, 1, 1, intArrayOf(17), false)
            store.registerFrame(target, frame(leaf))
            val first = store.freezeFrame(frame(target), 1, 1, 0, 0).also(packets::add)
            val changed = counter("REUSE_DEP_CHANGED")
            val changedIds = ids("REUSE_CHANGED_IDS")
            store.registerArgb(leaf, 1, 1, intArrayOf(29), false)
            val next = store.freezeFrame(frame(target), 2, 1, 0, 0).also(packets::add)
            assertNotSame(nested(first), nested(next))
            assertEquals(17, pixels(first))
            assertEquals(29, pixels(next))
            val key = leaf.value.takeLast(30)
            if (detailedAccounting) {
                assertEquals(changed + 1, counter("REUSE_DEP_CHANGED"))
                assertEquals((changedIds[key] ?: 0L) + 1, ids("REUSE_CHANGED_IDS")[key])
            } else {
                assertEquals(changed, counter("REUSE_DEP_CHANGED"))
                assertEquals(changedIds, ids("REUSE_CHANGED_IDS"))
            }

            val missing = counter("REUSE_DEP_MISSING")
            val missingIds = ids("REUSE_MISSING_IDS")
            store.unregister(leaf)
            val absent = store.freezeFrame(frame(target), 3, 1, 0, 0).also(packets::add)
            assertEquals(1, absent.resourceLease.resources().values.count { it === FrozenCanvasResource.Missing })
            assertEquals(17, pixels(first))
            assertEquals(29, pixels(next))
            if (detailedAccounting) {
                assertEquals(missing + 1, counter("REUSE_DEP_MISSING"))
                assertEquals((missingIds[key] ?: 0L) + 1, ids("REUSE_MISSING_IDS")[key])
            } else {
                assertEquals(missing, counter("REUSE_DEP_MISSING"))
                assertEquals(missingIds, ids("REUSE_MISSING_IDS"))
            }
            // Re-registering the same ID must resolve the new version, even after a Missing graph.
            store.registerArgb(leaf, 1, 1, intArrayOf(41), false)
            val restored = store.freezeFrame(frame(target), 4, 1, 0, 0).also(packets::add)
            assertEquals(41, pixels(restored))
            assertEquals(0, restored.resourceLease.resources().values.count { it === FrozenCanvasResource.Missing })
        } finally {
            packets.forEach(FrameEnvelope::close)
            store.close()
        }
    }

    @Test
    fun `full traversal control never fabricates reuse attempts or hits`() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, reuseFrozenContent = false)
        val leaf = KoolCanvasTextureId("diagnostics-control-leaf")
        val target = KoolCanvasTextureId("diagnostics-control-target")
        val packets = mutableListOf<FrameEnvelope>()
        try {
            store.registerArgb(leaf, 1, 1, intArrayOf(53), false)
            store.registerFrame(target, frame(leaf))
            val attempts = counter("REUSE_ATTEMPTS")
            val hits = counter("REUSE_HITS")
            repeat(3) { index ->
                val packet = store.freezeFrame(frame(target), index + 1L, 1, 0, 0).also(packets::add)
                assertEquals(53, pixels(packet))
                if (index > 0) {
                    assertEquals(packets.first().frame, packet.frame)
                    assertEquals(packets.first().resourceLease.resources(), packet.resourceLease.resources())
                }
            }
            assertEquals(attempts, counter("REUSE_ATTEMPTS"))
            assertEquals(hits, counter("REUSE_HITS"))
        } finally {
            packets.forEach(FrameEnvelope::close)
            store.close()
        }
    }
}
