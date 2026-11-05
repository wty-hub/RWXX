package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class KoolCanvasFrozenDependencyScopeTest {
    private fun draw(ref: KoolCanvasTextureRef, paint: KoolCanvasPaint = KoolCanvasPaint.Default) =
        KoolCanvasCommand.DrawTexture(ref, KoolCanvasRect.fromSize(1f, 1f),
            KoolCanvasRect.fromSize(1f, 1f), paint, KoolCanvasState.Default)

    private fun frame(commands: List<KoolCanvasCommand>) = KoolCanvasFrame(KoolCanvasViewport(8, 8), commands)

    @Test
    fun `alternating shared references keep each sibling parent and displacement dependency current`() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, reuseFrozenContent = true)
        val leaves = List(3) { KoolCanvasTextureId("scope-leaf-$it") }
        val refs = leaves.map { KoolCanvasTextureRef(it, 1, 1) }
        val inner = KoolCanvasTextureId("scope-inner")
        val outer = KoolCanvasTextureId("scope-outer")
        val sibling = KoolCanvasTextureId("scope-sibling")
        val packets = mutableListOf<FrameEnvelope>()
        val expected = intArrayOf(11, 22, 33)
        fun pixels(packet: FrameEnvelope) = packet.resourceLease.resources().values
            .filterIsInstance<FrozenCanvasResource.Pixels>().map { it.image.pixels[0] }.sorted()
        fun freeze(recording: KoolCanvasFrame): FrameEnvelope =
            store.freezeFrame(recording, packets.size + 1L, 1, 0, 0).also(packets::add)
        fun target(packet: FrameEnvelope, index: Int) =
            packet.resourceLease.resources().getValue((packet.frame.commands[index] as KoolCanvasCommand.DrawTexture).texture.id)
                as FrozenCanvasResource.Frame
        try {
            leaves.forEachIndexed { index, id -> store.registerArgb(id, 1, 1, intArrayOf(expected[index]), false) }
            val alternating = List(64) { draw(refs[it % 2]) }
            store.registerFrame(inner, frame(alternating))
            val displacement = KoolCanvasPaint(textureEffect = KoolCanvasTextureEffect.Displacement(refs[2], 1f))
            store.registerFrame(outer, frame(listOf(draw(refs[0]), draw(KoolCanvasTextureRef(inner, 8, 8))) +
                List(64) { draw(refs[it % 2], displacement) }))
            store.registerFrame(sibling, frame(alternating))
            // A root-level hit must not suppress the same ref's contribution inside any child.
            val root = frame(listOf(draw(refs[0]), draw(KoolCanvasTextureRef(outer, 8, 8)),
                draw(KoolCanvasTextureRef(sibling, 8, 8)), draw(refs[0])))
            val old = freeze(root)
            val cached = freeze(root)
            assertSame(target(old, 1).frame, target(cached, 1).frame)
            for (index in leaves.indices) {
                expected[index] += 100
                store.registerArgb(leaves[index], 1, 1, intArrayOf(expected[index]), false)
                // Resolve siblings in the opposite order, then request just the outer cached graph.
                freeze(frame(root.commands.asReversed()))
                val next = freeze(frame(listOf(draw(KoolCanvasTextureRef(outer, 8, 8)))))
                assertEquals(expected.sorted(), pixels(next), "Every changed leaf must invalidate the outer cache")
                assertEquals(listOf(11, 22, 33), pixels(old), "Published resources remain independent")
                val stable = freeze(frame(listOf(draw(KoolCanvasTextureRef(outer, 8, 8)))))
                assertSame(target(next, 0).frame, target(stable, 0).frame)
            }
        } finally {
            packets.forEach(FrameEnvelope::close)
            store.close()
        }
    }
}
