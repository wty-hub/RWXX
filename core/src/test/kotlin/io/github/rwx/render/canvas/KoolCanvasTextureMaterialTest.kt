package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import kotlin.test.*

class KoolCanvasTextureMaterialTest {
    @Test fun `map slots share materials until each renderer retires its binding`() {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        val id = KoolCanvasTextureId("shared-map-material")
        val first = Texture2d(name = "shared-map-first")
        val replacement = Texture2d(name = "shared-map-replacement")
        var sourceA = first
        fun store(source: () -> Texture2d) = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
            override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter) = source()
            override fun frame(id: KoolCanvasTextureId): KoolCanvasFrame? = null
        }
        val pool = KoolCanvasInstancedTextureMaterials()
        val a = KoolCanvasFrameRenderer(textureStore = store { sourceA }, reuseInstancedTextureShaders = true,
            meshRetentionRenders = 0).also { it.shareInstancedTextureMaterials(pool) }
        val b = KoolCanvasFrameRenderer(textureStore = store { first }, reuseInstancedTextureShaders = true,
            meshRetentionRenders = 0).also { it.shareInstancedTextureMaterials(pool) }
        val nodes = listOf(Node("map-slot-a"), Node("map-slot-b"))
        fun frame(left: Float) = KoolCanvasFrame(KoolCanvasViewport(16, 16), listOf(
            KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, 4, 4), KoolCanvasRect(0f, 0f, 4f, 4f),
                KoolCanvasRect(left, 0f, left + 4f, 4f), KoolCanvasPaint.Default, KoolCanvasState.Default)))
        fun shader(node: Node) = node.children.filterIsInstance<Mesh<*>>().single { it.isVisible }.shader as KoolCanvasInstancedTextureShader
        try {
            a.renderInto(nodes[0], OrthographicCamera(), frame(0f)) {}
            b.renderInto(nodes[1], OrthographicCamera(), frame(8f)) {}
            val old = shader(nodes[0])
            assertSame(old, shader(nodes[1]))
            assertEquals(1, pool.size)
            sourceA = replacement
            a.renderInto(nodes[0], OrthographicCamera(), frame(0f)) {}
            assertNotSame(old, shader(nodes[0]))
            assertSame(replacement, shader(nodes[0]).colorMap)
            assertSame(first, old.colorMap)
            assertSame(old, shader(nodes[1]))
            assertEquals(2, pool.size)
            a.releaseCachedMeshes(nodes[0])
            assertEquals(1, pool.size, "Another slot still owns the old material")
            b.releaseCachedMeshes(nodes[1])
            assertEquals(0, pool.size)
        } finally {
            a.releaseCachedMeshes(nodes[0]); b.releaseCachedMeshes(nodes[1])
            nodes.forEach { it.release() }; first.release(); replacement.release()
        }
    }

    @Test fun `ordered meshes share one immutable material while texture replacement preserves old bindings`() {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        val firstId = KoolCanvasTextureId("material-a")
        val secondId = KoolCanvasTextureId("material-b")
        val first = Texture2d(name = "first-image")
        val replacement = Texture2d(name = "replacement-image")
        val second = Texture2d(name = "second-image")
        val textures = mutableMapOf(firstId to first, secondId to second)
        val store = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
            override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter) = checkNotNull(textures[texture.id])
            override fun frame(id: KoolCanvasTextureId): KoolCanvasFrame? = null
        }
        val renderer = KoolCanvasFrameRenderer(textureStore = store, reuseInstancedTextureShaders = true)
        val node = Node("material-test")
        val camera = OrthographicCamera()
        val white = KoolCanvasPaint(blendMode = KoolCanvasBlendMode.Source)
        fun texture(id: KoolCanvasTextureId, left: Float) = KoolCanvasCommand.DrawTexture(
            KoolCanvasTextureRef(id, 4, 4), KoolCanvasRect(0f, 0f, 4f, 4f),
            KoolCanvasRect(left, 0f, left + 4f, 4f), white, KoolCanvasState.Default)
        val frame = KoolCanvasFrame(KoolCanvasViewport(16, 16), listOf(
            texture(firstId, 0f),
            KoolCanvasCommand.DrawRect(KoolCanvasRect(0f, 8f, 4f, 12f),
                KoolCanvasPaint.Default.copy(alphaMultiplier = 0.5f), KoolCanvasState.Default),
            texture(firstId, 4f), texture(secondId, 8f)))
        fun meshes() = node.children.filterIsInstance<Mesh<*>>().filter { it.isVisible && it.shader is KoolCanvasInstancedTextureShader }
        try {
            renderer.renderInto(node, camera, frame) {}
            val draws = meshes().sortedBy { it.drawGroupId }
            assertEquals(3, draws.size)
            val oldMaterial = draws[0].shader as KoolCanvasInstancedTextureShader
            val secondMaterial = draws[2].shader as KoolCanvasInstancedTextureShader
            assertSame(oldMaterial, draws[1].shader)
            assertNotSame(oldMaterial, secondMaterial)
            assertSame(first, oldMaterial.colorMap)
            assertSame(second, secondMaterial.colorMap)
            assertTrue(draws.zipWithNext().all { (a, b) -> a.drawGroupId < b.drawGroupId })
            textures[firstId] = replacement
            renderer.renderInto(node, camera, frame) {}
            val changed = meshes().sortedBy { it.drawGroupId }
            assertSame(replacement, (changed[0].shader as KoolCanvasInstancedTextureShader).colorMap)
            assertSame(changed[0].shader, changed[1].shader)
            assertSame(first, oldMaterial.colorMap, "An old in-flight material must never be rebound in place")
            assertSame(secondMaterial, changed[2].shader)
            assertSame(second, secondMaterial.colorMap)
        } finally { renderer.releaseCachedMeshes(node); node.release(); first.release(); replacement.release(); second.release() }
    }
}
