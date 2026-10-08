package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import kotlin.test.*

class KoolMapMeshSlotReuseTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }
    private val viewport = KoolCanvasViewport(512, 512)
    private val texture = Texture2d(TexFormat.RGBA, name = "immutable-map-source")
    private val otherTexture = Texture2d(TexFormat.RGBA, name = "different-immutable-map-source")
    private val store = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
        override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter): Texture2d =
            if (texture.id.value.startsWith("other-")) otherTexture else this@KoolMapMeshSlotReuseTest.texture
        override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? = null
    }

    private fun renderer(candidate: Boolean, map: Boolean = true) = KoolCanvasFrameRenderer(store,
        meshIdentityIgnoresRecordingOwner = map, meshRetentionRenders = 8,
        useSpriteAtlas = false, reuseMapMeshSlots = candidate)
    private fun visible(scene: Scene) = scene.children.filterIsInstance<Mesh<*>>()
        .filter { it.isVisible }.sortedBy { it.drawGroupId }
    private fun batch(id: String, count: Int, paint: KoolCanvasPaint = KoolCanvasPaint.Default,
                      state: KoolCanvasState = KoolCanvasState.Default): List<KoolCanvasCommand> {
        val recorder = KoolCanvasCommandBuffer(textureBatches = true)
        val ref = KoolCanvasTextureRef(KoolCanvasTextureId(id), 32, 32, hasAlpha = true)
        if (state.clip != null) recorder.clip(state.clip)
        repeat(count) { i -> recorder.drawTexture(ref,
            KoolCanvasRect((i % 24) * 20f, (i / 24) * 12f, (i % 24) * 20f + 20.5f, (i / 24) * 12f + 12f),
            paint = paint, source = KoolCanvasRect(1f, 2f, 21f, 14f)) }
        return recorder.snapshot().commands
    }
    private fun snapshot(scene: Scene) = visible(scene).map { mesh ->
        listOf(mesh.drawGroupId, mesh.shader!!::class.simpleName,
            when (val shader = mesh.shader) {
                is KoolCanvasInstancedTextureShader -> shader.colorMap
                is KoolCanvasAffineInstancedTextureShader -> shader.colorMap
                else -> null
            },
            mesh.instances?.let { instances ->
                val data = instances.instanceData
                FloatArray(data.limit * data.strideBytes / 4) { data.buffer.getFloat32(it * 4) }.toList()
            }, mesh.geometry.numIndices, mesh.geometry.numVertices)
    }

    @Test fun compatibleUnusedMapMeshKeepsItsGrownBufferAndNeverCombinesSeparateRuns() {
        val scene = Scene("map-recycled-capacity")
        val candidate = renderer(true)
        candidate.render(scene, KoolCanvasFrame(viewport, batch("source-v1", 700)))
        val first = visible(scene).single()
        val capacity = first.instances!!.maxInstances
        assertTrue(capacity >= 700)
        candidate.render(scene, KoolCanvasFrame(viewport, batch("source-v2", 3)))
        assertSame(first, visible(scene).single())
        assertEquals(capacity, first.instances!!.maxInstances)
        assertEquals(3, first.instances!!.numInstances)
        val barrier = KoolCanvasCommand.DrawRect(KoolCanvasRect(2f, 2f, 6f, 6f), KoolCanvasPaint.Default,
            KoolCanvasState.Default)
        candidate.render(scene, KoolCanvasFrame(viewport,
            batch("source-v3", 2) + barrier + batch("source-v4", 4)))
        val meshes = visible(scene).filter { it.instances != null }
        assertEquals(2, meshes.size)
        assertNotSame(meshes[0], meshes[1])
        assertEquals(listOf(2, 4), meshes.map { it.instances!!.numInstances })
        assertEquals(2L, candidate.reusedMapMeshSlotCount())
    }

    @Test fun exactGeometryOrderClipAndAlphaSemanticsMatchControlThroughRekeying() {
        val control = renderer(false); val candidate = renderer(true)
        val left = Scene("map-rekey-control"); val right = Scene("map-rekey-candidate")
        repeat(18) { frame ->
            val commands = batch("source-$frame-a", 5 + frame,
                state = KoolCanvasState(clip = KoolCanvasRect(4f, 5f, 300f, 250f))) +
                batch("other-$frame-b", 3, KoolCanvasPaint.DefaultNearest) +
                batch("source-$frame-c", 7, KoolCanvasPaint.Default.copy(blendMode = KoolCanvasBlendMode.Source)) +
                batch("source-$frame-d", 2)
            val recorded = KoolCanvasFrame(viewport, commands)
            control.render(left, recorded); candidate.render(right, recorded)
            assertEquals(snapshot(left), snapshot(right), "frame $frame")
        }
        assertTrue(candidate.reusedMapMeshSlotCount() > 0)
        assertTrue(candidate.cachedMeshCount() < control.cachedMeshCount())
    }

    @Test fun independentBindingsRebindButSamplerBlendAndRootRendererPreventIncompatibleReuse() {
        val scene = Scene("map-rekey-binding-isolation")
        val candidate = renderer(true)
        candidate.render(scene, KoolCanvasFrame(viewport, batch("source-a", 2)))
        val first = visible(scene).single()
        candidate.render(scene, KoolCanvasFrame(viewport, batch("other-b", 2)))
        assertSame(first, visible(scene).single())
        assertSame(otherTexture, assertIs<KoolCanvasInstancedTextureShader>(first.shader).colorMap)
        for ((id, paint) in listOf("source-c" to KoolCanvasPaint.DefaultNearest,
            "source-d" to KoolCanvasPaint.Default.copy(blendMode = KoolCanvasBlendMode.Source))) {
            candidate.render(scene, KoolCanvasFrame(viewport, batch(id, 2, paint)))
            assertNotSame(first, visible(scene).single())
        }
        assertEquals(1L, candidate.reusedMapMeshSlotCount())
        val root = renderer(true, map = false)
        val rootScene = Scene("root-never-rekeyed")
        root.render(rootScene, KoolCanvasFrame(viewport, batch("root-a", 1)))
        val rootMesh = visible(rootScene).single()
        root.render(rootScene, KoolCanvasFrame(viewport, batch("root-b", 1)))
        assertNotSame(rootMesh, visible(rootScene).single())
        assertEquals(0L, root.reusedMapMeshSlotCount())
    }
}
