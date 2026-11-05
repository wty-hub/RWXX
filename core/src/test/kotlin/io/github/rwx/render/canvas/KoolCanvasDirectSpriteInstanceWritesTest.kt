package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.Scene
import kotlin.test.*

class KoolCanvasDirectSpriteInstanceWritesTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    private fun append(instances: MeshInstanceList<KoolCanvasSpriteInstanceLayout>, v: FloatArray, direct: Boolean) {
        if (direct) KoolCanvasSpriteInstanceWriter.append(instances,
            v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7],
            v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15],
            v[16], v[17], v[18], v[19], v[20], v[21], v[22], v[23])
        else instances.addInstances(1) { buffer ->
            buffer.put { layout ->
                set(layout.originAxisX, v[0], v[1], v[2], v[3])
                set(layout.axisYModeAmount, v[4], v[5], v[6], v[7])
                set(layout.uv, v[8], v[9], v[10], v[11])
                set(layout.tint, v[12], v[13], v[14], v[15])
                set(layout.team, v[16], v[17], v[18], v[19])
                set(layout.clip, v[20], v[21], v[22], v[23])
            }
        }
    }
    private fun bits(instances: MeshInstanceList<*>): List<Int> {
        val data = instances.instanceData
        return List(data.limit * data.strideBytes / 4) { data.buffer.getInt32(it * 4) }
    }
    private fun scalars(seed: Int) = FloatArray(24) { index ->
        when (index) {
            0 -> -0f
            3 -> Float.fromBits(0x7fc01234)
            7 -> Float.NEGATIVE_INFINITY
            14 -> Float.MIN_VALUE
            else -> (seed * 37 - index * 13).toFloat() / 7f
        }
    }

    @Test fun all24RawFloatBitsSurviveGrowthClearAndRefill() {
        val control = MeshInstanceList(KoolCanvasSpriteInstanceLayout, initialSize = 2)
        val candidate = MeshInstanceList(KoolCanvasSpriteInstanceLayout, initialSize = 2)
        val initial = candidate.instanceData
        assertEquals(24 * 4, initial.strideBytes)
        repeat(19) { index ->
            val values = scalars(index)
            append(control, values, false); append(candidate, values, true)
            assertEquals(control.maxInstances, candidate.maxInstances)
            assertEquals(control.numInstances, candidate.numInstances)
            assertEquals(bits(control), bits(candidate), "append $index")
        }
        assertNotSame(initial, candidate.instanceData, "must exercise Kool's real growth path")
        val grown = candidate.instanceData
        control.clear(); candidate.clear()
        assertEquals(0, candidate.numInstances)
        assertEquals(0, candidate.instanceData.buffer.limit)
        repeat(5) { index ->
            val values = scalars(100 + index)
            append(control, values, false); append(candidate, values, true)
        }
        assertSame(grown, candidate.instanceData)
        assertEquals(bits(control), bits(candidate))
        assertEquals(control.instanceData.buffer.limit, candidate.instanceData.buffer.limit)
    }

    @Test fun finishMarksDirectWritesDirtyAndKoolStillOwnsGrowthAndFixedCapacityFailure() {
        val candidate = MeshInstanceList(KoolCanvasSpriteInstanceLayout, initialSize = 1)
        val clean = candidate.modCount.count
        append(candidate, scalars(1), true)
        assertFalse(candidate.modCount.isDirty(clean), "spare-capacity writes are published by finish, not per sprite")
        candidate.incrementModCount() // Same operation as the existing renderer sprite finish pass.
        assertTrue(candidate.modCount.isDirty(clean))
        val beforeGrow = candidate.modCount.count
        append(candidate, scalars(2), true)
        assertTrue(candidate.modCount.isDirty(beforeGrow), "growth must retain addInstances mod-count semantics")
        val beforeClear = candidate.modCount.count
        candidate.clear()
        assertTrue(candidate.modCount.isDirty(beforeClear))
        val afterClear = candidate.modCount.count
        candidate.clear()
        assertEquals(afterClear, candidate.modCount.count, "empty clear remains a no-op")
        append(candidate, scalars(3), true)
        candidate.incrementModCount()
        assertTrue(candidate.modCount.isDirty(afterClear), "refill must be visible to instance upload")

        val controlFixed = MeshInstanceList(KoolCanvasSpriteInstanceLayout, initialSize = 1, isResizable = false)
        val candidateFixed = MeshInstanceList(KoolCanvasSpriteInstanceLayout, initialSize = 1, isResizable = false)
        append(controlFixed, scalars(1), false); append(candidateFixed, scalars(1), true)
        assertFailsWith<IllegalStateException> { append(controlFixed, scalars(2), false) }
        assertFailsWith<IllegalStateException> { append(candidateFixed, scalars(2), true) }
        assertEquals(1, candidateFixed.numInstances)
        assertEquals(bits(controlFixed), bits(candidateFixed), "failed growth must not partially append")
    }

    private val viewport = KoolCanvasViewport(640, 480)
    private val images = mapOf(
        KoolCanvasTextureId("direct-sprite-a") to KoolCanvasArgbImage(8, 8, IntArray(64) { 0xff40a060.toInt() }),
        KoolCanvasTextureId("direct-sprite-b") to KoolCanvasArgbImage(8, 8, IntArray(64) { 0x80ff8040.toInt() }),
    )
    private val nestedId = KoolCanvasTextureId("direct-sprite-nested-frame")
    private var nestedFrame: KoolCanvasFrame? = null
    private val store = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
        override fun staticArgbImage(id: KoolCanvasTextureId) = images[id]
        override fun frame(id: KoolCanvasTextureId) = if (id == nestedId) nestedFrame else null
    }
    private fun renderer(direct: Boolean, projected: Boolean) = KoolCanvasFrameRenderer(store,
        projectedSpriteAtlas = projected).apply { configureDirectSpriteInstanceWritesForTest(direct) }
    private fun visible(scene: Scene) = scene.children.filterIsInstance<Mesh<*>>()
        .filter { it.isVisible }.sortedBy { it.drawGroupId }
    private fun snapshot(scene: Scene) = visible(scene).map { mesh ->
        listOf(mesh.drawGroupId, mesh.shader!!::class.simpleName, mesh.geometry.numIndices, mesh.geometry.numVertices,
            mesh.instances?.numInstances, mesh.instances?.maxInstances, mesh.instances?.let(::bits))
    }
    private fun frame(count: Int): KoolCanvasFrame {
        val commands = mutableListOf<KoolCanvasCommand>()
        repeat(count) { index ->
            val transform = KoolCanvasTransform.Identity.translate((index % 20).toFloat(), (index / 20).toFloat())
                .rotate((index % 17 - 8).toFloat() * 13f, 12f, 7f).scale(if (index % 3 == 0) -1f else 1.2f, .75f)
            val effect = if (index % 4 == 0) null else KoolCanvasTextureEffect.TeamColor(
                KoolCanvasTeamColorMode.entries[index % 3], KoolCanvasColor(0xff12a5e3.toInt()), .37f)
            val paint = KoolCanvasPaint(color = KoolCanvasColor(0x9faf6821.toInt()), alphaMultiplier = .63f,
                textureFilter = if (index % 5 == 0) KoolCanvasTextureFilter.Nearest else KoolCanvasTextureFilter.Linear,
                blendMode = if (index % 11 == 0) KoolCanvasBlendMode.Add else KoolCanvasBlendMode.SourceOver,
                textureEffect = effect)
            val state = KoolCanvasState(transform = transform,
                clip = if (index % 2 == 0) KoolCanvasRect(3.25f, 4.5f, 601.75f, 451.25f) else null)
            commands += KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(images.keys.elementAt(index % 2), 8, 8),
                KoolCanvasRect(1f, 2f, 7f, 8f), KoolCanvasRect(10.5f, 11.25f, 29.5f, 31.75f), paint, state)
            if (index % 73 == 0) commands += KoolCanvasCommand.DrawRect(KoolCanvasRect(4f, 6f, 17f, 20f),
                KoolCanvasPaint.Default.copy(alphaMultiplier = .4f), KoolCanvasState.Default)
        }
        // A long single-material run also exceeds the sprite mesh's initial instance capacity.
        if (count > 0) repeat(257) { index ->
            commands += KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(images.keys.first(), 8, 8),
                KoolCanvasRect.fromSize(8f, 8f), KoolCanvasRect(index.toFloat(), 30f, index + 8f, 38f),
                KoolCanvasPaint.Default, KoolCanvasState.Default)
        }
        return KoolCanvasFrame(viewport, commands)
    }

    @Test fun realAtlasReplayPreservesMixedTransformsTeamColorClipsOrderingAndDirtyPublication() {
        for (projected in listOf(false, true)) {
            val control = renderer(false, projected); val candidate = renderer(true, projected)
            val left = Scene("sprite-direct-control-$projected"); val right = Scene("sprite-direct-candidate-$projected")
            for (count in listOf(300, 17, 0, 600)) {
                val oldCounts = right.children.filterIsInstance<Mesh<*>>().mapNotNull { mesh ->
                    mesh.instances?.let { mesh to it.modCount.count }
                }.toMap()
                val frame = frame(count)
                nestedFrame = frame
                val actualFrame = if (!projected) frame else KoolCanvasFrame(viewport, listOf(
                    KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(nestedId, viewport.width, viewport.height),
                        KoolCanvasRect.fromSize(viewport.width.toFloat(), viewport.height.toFloat()),
                        KoolCanvasRect.fromSize(viewport.width.toFloat(), viewport.height.toFloat()),
                        KoolCanvasPaint.Default, KoolCanvasState.Default)))
                control.render(left, actualFrame); candidate.render(right, actualFrame)
                assertEquals(snapshot(left), snapshot(right), "projected=$projected count=$count")
                visible(right).filter { it.shader is KoolCanvasSpriteShader }.forEach { mesh ->
                    val instances = mesh.instances!!
                    assertTrue(instances.modCount.isDirty(oldCounts[mesh] ?: 0), "finish publishes every live sprite list")
                    assertEquals(instances.numInstances * 96, instances.instanceData.buffer.limit)
                }
                if (count > 0) assertTrue(visible(right).any { it.shader is KoolCanvasSpriteShader && it.instances!!.numInstances >= 257 })
            }
        }
    }
}
