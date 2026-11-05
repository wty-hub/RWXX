package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.scene.geometry.IndexedVertexList
import de.fabmax.kool.util.Color
import kotlin.test.*

class KoolCanvasDirectRectVertexWritesTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    private fun legacy(
        geometry: IndexedVertexList<VertexLayouts.PositionNormalColor>, rect: KoolCanvasRect,
        color: Color, halfWidth: Float = 32f, halfHeight: Float = 24f,
    ) {
        fun vertex(x: Float, y: Float) = geometry.addVertex { layout ->
            set(layout.position, x - halfWidth, halfHeight - y, 0f)
            set(layout.normal, 0f, 0f, 1f)
            set(layout.color, color.r, color.g, color.b, color.a)
        }
        val i0 = vertex(rect.left, rect.top); val i1 = vertex(rect.right, rect.top)
        val i2 = vertex(rect.right, rect.bottom); val i3 = vertex(rect.left, rect.bottom)
        geometry.addTriIndices(i0, i1, i2); geometry.addTriIndices(i0, i2, i3)
    }

    private fun vertices(geometry: IndexedVertexList<*>) = with(geometry.vertexData) {
        List(limit * strideBytes / 4) { buffer.getInt32(it * 4) }
    }
    private fun indices(geometry: IndexedVertexList<*>) = List(geometry.numIndices) { geometry.indices[it] }
    private fun equalGeometry(control: IndexedVertexList<*>, actual: IndexedVertexList<*>) {
        assertEquals(control.numVertices, actual.numVertices)
        assertEquals(control.numIndices, actual.numIndices)
        assertEquals(control.numPrimitives, actual.numPrimitives)
        assertEquals(control.vertexData.capacity, actual.vertexData.capacity)
        assertEquals(control.indices.capacity, actual.indices.capacity)
        assertEquals(control.vertexData.buffer.limit, actual.vertexData.buffer.limit)
        assertEquals(control.vertexData.buffer.position, actual.vertexData.buffer.position)
        assertEquals(control.modCount.count, actual.modCount.count)
        assertEquals(vertices(control), vertices(actual), "all position, normal and RGBA raw bits")
        assertEquals(indices(control), indices(actual))
    }

    @Test fun `raw writes preserve shared view cursor normal bits buffer position and index growth`() {
        // Capacity 9 with 4 existing vertices leaves room for the entire second rectangle.
        val control = IndexedVertexList(VertexLayouts.PositionNormalColor, initialSize = 9)
        val actual = IndexedVertexList(VertexLayouts.PositionNormalColor, initialSize = 9)
        assertEquals(40, actual.vertexData.strideBytes)
        assertEquals(actual.layout.structSize, actual.vertexData.strideBytes)
        val oldView = control.vertexData.get(0) { this }
        val newView = actual.vertexData.get(0) { this }
        control.vertexIt.index = 2; actual.vertexIt.index = 2
        val color = Color(-0f, Float.fromBits(0x7fc01234), Float.MIN_VALUE, Float.POSITIVE_INFINITY)
        val rect = KoolCanvasRect(-0f, Float.MIN_VALUE, 31.25f, Float.POSITIVE_INFINITY)
        repeat(2) { i ->
            if (i == 1) {
                control.vertexData.buffer.position = 12; actual.vertexData.buffer.position = 12
            }
            legacy(control, rect, color)
            assertTrue(KoolCanvasRectVertexWriter.tryAppend(actual, rect, 32f, 24f, color))
            equalGeometry(control, actual)
            assertEquals((i + 1) * 10, actual.modCount.count)
            assertEquals((i + 1) * 4 - 1, newView.index)
            assertEquals(oldView.index, newView.index)
            assertEquals(2, actual.vertexIt.index, "addVertex never updates the separate legacy vertexIt")
        }
        assertEquals(9, actual.vertexData.capacity, "capacity9 then4+4 must not grow vertices")
        assertTrue(actual.indices.capacity > 9, "exercise original per-index capacity growth")
    }

    @Test fun `short capacity is untouched before exact legacy growth clear shrink and refill`() {
        val control = IndexedVertexList(VertexLayouts.PositionNormalColor, initialSize = 5)
        val actual = IndexedVertexList(VertexLayouts.PositionNormalColor, initialSize = 5)
        val initial = actual.vertexData
        val color = Color(.17f, .31f, .89f, .67f)
        var direct = 0; var fallback = 0
        fun append(seed: Int) {
            val rect = KoolCanvasRect(seed / 7f, -seed / 13f, seed / 7f + 4.5f, -seed / 13f + 3.25f)
            val beforeBits = vertices(actual); val beforeIndices = indices(actual)
            val beforeCount = actual.modCount.count; val beforeVertices = actual.numVertices
            val needsGrowth = actual.vertexData.remaining < 4
            val wrote = KoolCanvasRectVertexWriter.tryAppend(actual, rect, 32f, 24f, color)
            if (wrote) direct++ else {
                fallback++
                assertTrue(needsGrowth)
                assertEquals(beforeCount, actual.modCount.count)
                assertEquals(beforeVertices, actual.numVertices)
                assertEquals(beforeBits, vertices(actual)); assertEquals(beforeIndices, indices(actual))
                legacy(actual, rect, color)
            }
            legacy(control, rect, color)
            equalGeometry(control, actual)
        }
        repeat(19, ::append)
        assertTrue(direct > 0); assertTrue(fallback > 0)
        assertNotSame(initial, actual.vertexData)
        control.shrinkVertices(control.numVertices - 4); actual.shrinkVertices(actual.numVertices - 4)
        control.shrinkIndices(control.numIndices - 6); actual.shrinkIndices(actual.numIndices - 6)
        append(91)
        val grown = actual.vertexData
        repeat(2) {
            val old = actual.modCount.count
            control.clear(); actual.clear()
            assertEquals(old + 1, actual.modCount.count, "geometry clear marks dirty even when already empty")
            equalGeometry(control, actual)
        }
        repeat(7) { append(100 + it) }
        assertSame(grown, actual.vertexData)
        // Interoperation with the original public vertex writer remains valid after raw appends.
        val rect = KoolCanvasRect(1f, 2f, 7f, 11f)
        legacy(control, rect, color); legacy(actual, rect, color)
        equalGeometry(control, actual)
    }

    private val viewport = KoolCanvasViewport(640, 480)
    private val textureId = KoolCanvasTextureId("direct-rect-texture")
    private val pixels = KoolCanvasArgbImage(8, 8, IntArray(64) { 0xff50b070.toInt() })
    private val store = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
        override fun staticArgbImage(id: KoolCanvasTextureId) = if (id == textureId) pixels else null
        override fun frame(id: KoolCanvasTextureId): KoolCanvasFrame? = null
    }
    private fun renderer(direct: Boolean) = KoolCanvasFrameRenderer(store).apply {
        configureDirectRectVertexWritesForTest(direct)
    }
    private fun meshes(scene: Scene) = scene.children.filterIsInstance<Mesh<*>>().filter { it.isVisible }.sortedBy { it.drawGroupId }
    private fun picture(scene: Scene) = meshes(scene).map { mesh ->
        mesh.updateGeometryBounds()
        val instanceData = mesh.instances?.instanceData
        listOf(mesh.drawGroupId, mesh.shader!!::class.java.name, mesh.geometry.numVertices,
            mesh.geometry.numIndices, mesh.geometry.modCount.count, vertices(mesh.geometry), indices(mesh.geometry),
            mesh.geometryBounds.min.toString(), mesh.geometryBounds.max.toString(),
            if (instanceData == null) emptyList<Int>() else List(instanceData.limit * instanceData.strideBytes / 4) {
                instanceData.buffer.getInt32(it * 4)
            })
    }
    private fun rect(index: Int, state: KoolCanvasState = KoolCanvasState.Default,
                     style: KoolCanvasPaintStyle = KoolCanvasPaintStyle.Fill,
                     blend: KoolCanvasBlendMode = KoolCanvasBlendMode.SourceOver) = KoolCanvasCommand.DrawRect(
        KoolCanvasRect(index % 43 + .25f, index % 31 + .5f, index % 43 + 13.75f, index % 31 + 8.5f),
        KoolCanvasPaint(color = KoolCanvasColor(0x9faf6821.toInt()), alphaMultiplier = .63f, style = style, blendMode = blend), state)
    private fun texture() = KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(textureId, 8, 8),
        KoolCanvasRect.fromSize(8f, 8f), KoolCanvasRect(20f, 21f, 36f, 37f), KoolCanvasPaint.Default, KoolCanvasState.Default)

    @Test fun `actual renderer mixed runs preserve raw geometry indices bounds and ordering through growth and refill`() {
        val control = renderer(false); val actual = renderer(true)
        val left = Scene("rect-control"); val right = Scene("rect-direct")
        try {
            for (count in listOf(700, 2, 0, 1100)) {
                val commands = buildList<KoolCanvasCommand> {
                    repeat(count) { add(rect(it)) }
                    if (count > 0) {
                        add(texture()); add(rect(901)); add(texture())
                        add(rect(902, KoolCanvasState(clip = KoolCanvasRect(3f, 4f, 30f, 27f))))
                        add(rect(903, KoolCanvasState(transform = KoolCanvasTransform.Identity.translate(3f, 2f).rotate(17f))))
                        add(rect(904, style = KoolCanvasPaintStyle.Stroke))
                        add(rect(905, blend = KoolCanvasBlendMode.Add))
                        add(rect(906))
                    }
                }
                val frame = KoolCanvasFrame(viewport, commands)
                control.render(left, frame); actual.render(right, frame)
                assertEquals(picture(left), picture(right), "actual renderer count=$count")
                if (count >= 700) assertTrue(meshes(right).any { it.geometry.numVertices >= count * 4 }, "real primitive capacity growth")
            }
        } finally {
            control.releaseCachedMeshes(left); actual.releaseCachedMeshes(right)
            left.release(); right.release()
        }
    }

    @Test fun `clipped transformed stroked and nonalpha rectangles stay on original fallbacks`() {
        val control = renderer(false); val actual = renderer(true)
        val left = Scene("rect-fallback-control"); val right = Scene("rect-fallback-candidate")
        try {
            val commands = listOf(
                rect(1, KoolCanvasState(clip = KoolCanvasRect(2f, 3f, 12f, 9f))),
                rect(2, KoolCanvasState(transform = KoolCanvasTransform(translateX = 3f, translateY = 2f))),
                rect(3, KoolCanvasState(transform = KoolCanvasTransform())), // Value identity is not the singleton.
                rect(4, style = KoolCanvasPaintStyle.Stroke), rect(5, style = KoolCanvasPaintStyle.FillAndStroke),
                rect(6, blend = KoolCanvasBlendMode.Add), rect(7, blend = KoolCanvasBlendMode.Source),
                rect(8).copy(rect = KoolCanvasRect(4f, 4f, 4f, 7f)),
            )
            val frame = KoolCanvasFrame(viewport, commands)
            control.render(left, frame); actual.render(right, frame)
            assertEquals(picture(left), picture(right))
        } finally {
            control.releaseCachedMeshes(left); actual.releaseCachedMeshes(right)
            left.release(); right.release()
        }
    }
}
