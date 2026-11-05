package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import kotlin.test.*

class KoolCanvasActiveMeshResetTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    private val viewport = KoolCanvasViewport(64, 64)
    private val emptyFrame = KoolCanvasFrame(viewport, emptyList())

    private class Fixture(store: KoolCanvasTextureStore, activeOnly: Boolean,
                          retention: Long = 60, mapSlots: Boolean = false) {
        val renderer = KoolCanvasFrameRenderer(textureStore = store, useSpriteAtlas = false,
            reuseInstancedTextureShaders = false, meshRetentionRenders = retention,
            meshIdentityIgnoresRecordingOwner = mapSlots, reuseMapMeshSlots = mapSlots).apply {
                configureActiveMeshResetForTest(activeOnly)
            }
        val nodes = mutableListOf(Node("active-reset"))
        val camera = OrthographicCamera()
        val node get() = nodes.last()
        fun render(frame: KoolCanvasFrame) = renderer.renderInto(node, camera, frame) {}
        fun switchNode() { nodes += Node("active-reset-next") }
        fun visible() = node.children.filterIsInstance<Mesh<*>>().filter { it.isVisible }.sortedBy { it.drawGroupId }
        fun close() {
            renderer.releaseCachedMeshes(node)
            nodes.forEach { it.release() }
        }
    }

    private data class Draw(val group: Int, val shader: String, val texture: Texture2d?,
                            val vertices: List<Int>, val indices: List<Int>, val instances: List<Int>)

    private fun picture(fixture: Fixture) = fixture.visible().map { mesh ->
        val vertices = mesh.geometry.vertexData
        val instances = mesh.instances
        val texture = when (val shader = mesh.shader) {
            is KoolCanvasInstancedTextureShader -> shader.colorMap
            is KoolCanvasAffineInstancedTextureShader -> shader.colorMap
            is KoolCanvasTextureShader -> shader.colorMap
            else -> null
        }
        Draw(mesh.drawGroupId, checkNotNull(mesh.shader)::class.java.name, texture,
            List(mesh.geometry.numVertices * vertices.strideBytes / 4) { vertices.buffer.getInt32(it * 4) },
            List(mesh.geometry.numIndices) { mesh.geometry.indices[it] },
            if (instances == null) emptyList() else List(instances.numInstances * instances.instanceData.strideBytes / 4) {
                instances.instanceData.buffer.getInt32(it * 4)
            })
    }

    private fun store(source: () -> Texture2d) = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
        override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter) = source()
        override fun frame(id: KoolCanvasTextureId): KoolCanvasFrame? = null
        override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? = null
    }

    private fun texture(id: String = "active-reset-source", x: Float = 0f) = KoolCanvasCommand.DrawTexture(
        KoolCanvasTextureRef(KoolCanvasTextureId(id), 8, 8), KoolCanvasRect(0f, 0f, 8f, 8f),
        KoolCanvasRect(x, 2f, x + 8f, 10f), KoolCanvasPaint(blendMode = KoolCanvasBlendMode.Source),
        KoolCanvasState.Default)

    private fun mixedFrame(x: Float) = KoolCanvasFrame(viewport, listOf(texture(x = x),
        KoolCanvasCommand.DrawRect(KoolCanvasRect(x + 2f, 4f, x + 16f, 20f),
            KoolCanvasPaint(color = KoolCanvasColor(0x8000ff00.toInt())),
            KoolCanvasState(clip = KoolCanvasRect(3f, 5f, 30f, 18f))), texture(x = x + 16f)))

    @Test fun `active meshes clear once while idle then rebuild dirty ordered geometry and bindings`() {
        val first = Texture2d(name = "active-reset-first")
        val replacement = Texture2d(name = "active-reset-replacement")
        var source = first
        val textureStore = store { source }
        val control = Fixture(textureStore, false)
        val candidate = Fixture(textureStore, true)
        try {
            for (fixture in listOf(control, candidate)) fixture.render(mixedFrame(1f))
            assertEquals(picture(control), picture(candidate))
            val original = candidate.visible()
            assertTrue(original.any { it.instances == null } && original.any { it.instances != null })
            assertTrue(original.zipWithNext().all { (a, b) -> a.drawGroupId < b.drawGroupId })
            val primitive = original.first { it.instances == null }
            val instanced = original.first { it.instances != null }
            val oldInstancesModCount = instanced.instances!!.modCount.count
            for (fixture in listOf(control, candidate)) fixture.render(emptyFrame)
            assertTrue(candidate.visible().isEmpty())
            assertTrue(original.all { if (it.instances == null) it.geometry.isEmpty() else it.instances!!.numInstances == 0 })
            val idleGeometryModCount = primitive.geometry.modCount.count
            repeat(3) {
                control.render(emptyFrame); candidate.render(emptyFrame)
                assertEquals(picture(control), picture(candidate))
                assertTrue(candidate.visible().isEmpty())
                assertEquals(idleGeometryModCount, primitive.geometry.modCount.count,
                    "Already empty retained geometry must not be dirtied by another idle reset")
            }
            source = replacement
            control.render(mixedFrame(9f)); candidate.render(mixedFrame(9f))
            assertEquals(picture(control), picture(candidate))
            assertEquals(original.toSet(), candidate.visible().toSet())
            assertTrue(primitive.geometry.modCount.count > idleGeometryModCount)
            assertTrue(instanced.instances!!.modCount.count > oldInstancesModCount)
            assertTrue(candidate.visible().filter { it.instances != null }.all {
                (it.shader as KoolCanvasInstancedTextureShader).colorMap === replacement
            })
            // A new draw node invalidates the old used sets before any current-node mesh exists.
            control.switchNode(); candidate.switchNode()
            control.render(mixedFrame(4f)); candidate.render(mixedFrame(4f))
            assertEquals(picture(control), picture(candidate))
            assertTrue(candidate.visible().none { it in original })
        } finally { control.close(); candidate.close(); first.release(); replacement.release() }
    }

    @Test fun `map slot rekeying keeps the current used key resettable after empty frames`() {
        val source = Texture2d(name = "active-reset-map")
        val control = Fixture(store { source }, false, mapSlots = true)
        val candidate = Fixture(store { source }, true, mapSlots = true)
        try {
            var mesh: Mesh<*>? = null
            for (id in listOf("map-version-a", "map-version-b", "map-version-c")) {
                val frame = KoolCanvasFrame(viewport, listOf(texture(id)))
                control.render(frame); candidate.render(frame)
                assertEquals(picture(control), picture(candidate))
                val current = candidate.visible().single()
                if (mesh == null) mesh = current else assertSame(mesh, current)
                assertEquals(1, current.instances!!.numInstances)
                repeat(2) {
                    control.render(emptyFrame); candidate.render(emptyFrame)
                    assertEquals(picture(control), picture(candidate))
                    assertFalse(current.isVisible)
                    assertEquals(0, current.instances!!.numInstances)
                }
            }
            assertTrue(candidate.renderer.reusedMapMeshSlotCount() > 0)
        } finally { control.close(); candidate.close(); source.release() }
    }

    @Test fun `prune and explicit cache release still retire every mesh through the sink`() {
        val source = Texture2d(name = "active-reset-retirement")
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            for (activeOnly in listOf(false, true)) {
                val fixture = Fixture(store { source }, activeOnly, retention = 0)
                try {
                    fixture.render(mixedFrame(0f))
                    val pruned = fixture.visible()
                    fixture.render(emptyFrame)
                    assertEquals(0, fixture.renderer.cachedMeshCount())
                    assertTrue(fixture.node.children.isEmpty())
                    assertEquals(pruned.size, pending.size)
                    assertTrue(pruned.none { it.isReleased })
                    while (pending.isNotEmpty()) pending.removeAt(0).invoke()
                    assertTrue(pruned.all { it.isReleased })
                    fixture.render(mixedFrame(2f))
                    val released = fixture.visible()
                    assertTrue(released.none { it in pruned })
                    fixture.renderer.releaseCachedMeshes(fixture.node)
                    assertEquals(0, fixture.renderer.cachedMeshCount())
                    assertTrue(fixture.node.children.isEmpty())
                    assertEquals(released.size, pending.size)
                    assertTrue(released.none { it.isReleased })
                    while (pending.isNotEmpty()) pending.removeAt(0).invoke()
                    assertTrue(released.all { it.isReleased })
                    // Explicit release leaves no cache entry for stale used keys to reset.
                    fixture.render(mixedFrame(3f))
                    assertTrue(fixture.visible().isNotEmpty())
                } finally {
                    fixture.close()
                    while (pending.isNotEmpty()) pending.removeAt(0).invoke()
                }
            }
        } finally {
            while (pending.isNotEmpty()) pending.removeAt(0).invoke()
            KoolCanvasGpuRetirement.install(null); source.release()
        }
    }
}
