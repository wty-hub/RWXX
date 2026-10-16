package io.github.rwx.kool.vulkan

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.scene.geometry.IndexedVertexList
import de.fabmax.kool.scene.geometry.Usage
import de.fabmax.kool.util.MemoryLayout
import de.fabmax.kool.util.MixedBuffer
import de.fabmax.kool.util.Struct
import kotlin.test.*

/** Exercises the patched Mesh constructor and its real async capture path without a GPU. */
class KoolMeshShadowCapacityTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    private object Instances : Struct("shadow-capacity-test", MemoryLayout.TightlyPacked) {
        val value = float4("value")
    }

    @Test
    fun `only private async buffers start small and optional instances remain optional`() {
        val geometry = IndexedVertexList(VertexLayouts.Position, initialSize = 7)
        val instances = MeshInstanceList(Instances, initialSize = 11)
        val mesh = Mesh(geometry, instances)
        val plain = Mesh(IndexedVertexList(VertexLayouts.Position, initialSize = 19))
        try {
            assertSame(geometry, mesh.geometry)
            assertSame(instances, mesh.instances)
            assertEquals(7, geometry.vertexData.capacity)
            assertEquals(7, geometry.indices.capacity)
            assertEquals(11, instances.maxInstances)
            assertEquals(19, plain.geometry.vertexData.capacity)
            for (item in listOf(mesh, plain)) {
                assertEquals(1, drawGeometry(item).vertexData.capacity)
                assertEquals(1, drawGeometry(item).indices.capacity)
                assertEquals(0, drawGeometry(item).numVertices)
                assertEquals(0, drawGeometry(item).numIndices)
                assertNotSame(item.geometry, drawGeometry(item))
            }
            assertEquals(1, checkNotNull(drawInstances(mesh)).maxInstances)
            assertTrue(checkNotNull(drawInstances(mesh)).isResizable)
            assertNotSame(instances, drawInstances(mesh))
            assertNull(drawInstances(plain))
        } finally {
            mesh.release()
            plain.release()
        }
    }

    @Test
    fun `real async captures grow copy and replace both private buffers without touching the source`() {
        val geometry = IndexedVertexList(VertexLayouts.Position, initialSize = 7, usage = Usage.DYNAMIC)
        val instances = MeshInstanceList(Instances, initialSize = 11).apply { usage = Usage.STATIC }
        val mesh = Mesh(geometry, instances)
        val copiedGeometry = drawGeometry(mesh)
        val copiedInstances = checkNotNull(drawInstances(mesh))
        try {
            assertTrue(asyncField.getBoolean(mesh), "Before collection Mesh uses its normal async capture path")
            for ((count, seed) in listOf(37 to 1f, 2051 to 9f, 3 to 21f)) {
                fill(geometry, instances, count, seed)
                val geometryVersion = geometry.modCount.count
                val instancesVersion = instances.modCount.count
                mesh.captureBuffer()

                assertSame(copiedGeometry, drawGeometry(mesh))
                assertSame(copiedInstances, drawInstances(mesh))
                assertEquals(count, copiedGeometry.numVertices)
                assertEquals(geometry.numIndices, copiedGeometry.numIndices)
                assertEquals(count, copiedInstances.numInstances)
                assertTrue(copiedGeometry.vertexData.capacity >= count)
                assertTrue(copiedGeometry.indices.capacity >= geometry.numIndices)
                assertTrue(copiedInstances.maxInstances >= count)
                assertEquals(instances.usage, copiedInstances.usage)
                assertEquals(geometryVersion, geometry.modCount.count)
                assertEquals(instancesVersion, instances.modCount.count)
                assertEquals(geometryVersion, copiedGeometry.modCount.count)
                assertBufferEquals(geometry.vertexData.buffer, copiedGeometry.vertexData.buffer,
                    count * geometry.layout.structSize)
                assertBufferEquals(instances.instanceData.buffer, copiedInstances.instanceData.buffer,
                    count * instances.layout.structSize)
                for (index in 0 until geometry.numIndices) assertEquals(geometry.indices[index], copiedGeometry.indices[index])

                val vertices = copiedGeometry.vertexData
                val instanceData = copiedInstances.instanceData
                mesh.captureBuffer()
                assertSame(vertices, copiedGeometry.vertexData)
                assertSame(instanceData, copiedInstances.instanceData)
            }
            geometry.clear()
            instances.clear()
            assertEquals(3, copiedGeometry.numVertices, "The previous capture remains detached until the next capture")
            assertEquals(3, copiedInstances.numInstances)
            mesh.captureBuffer()
            assertEquals(0, copiedGeometry.numVertices)
            assertEquals(0, copiedGeometry.numIndices)
            assertEquals(0, copiedInstances.numInstances)
        } finally {
            mesh.release()
            assertTrue(copiedGeometry.isReleased)
            assertTrue(copiedInstances.isReleased)
            assertTrue(geometry.isReleased)
            assertTrue(instances.isReleased)
        }
    }

    @Test
    fun `synchronous captures leave private buffers unused and a later async capture still works`() {
        val geometry = IndexedVertexList(VertexLayouts.Position, initialSize = 7)
        val instances = MeshInstanceList(Instances, initialSize = 11)
        val mesh = Mesh(geometry, instances)
        try {
            fill(geometry, instances, 123, 5f)
            asyncField.setBoolean(mesh, false)
            repeat(3) { mesh.captureBuffer() }
            assertEquals(0, drawGeometry(mesh).numVertices)
            assertEquals(1, drawGeometry(mesh).vertexData.capacity)
            assertEquals(0, checkNotNull(drawInstances(mesh)).numInstances)
            assertEquals(1, checkNotNull(drawInstances(mesh)).maxInstances)
            assertEquals(123, geometry.numVertices)
            assertEquals(123, instances.numInstances)

            asyncField.setBoolean(mesh, true)
            mesh.captureBuffer()
            assertEquals(123, drawGeometry(mesh).numVertices)
            assertEquals(123, checkNotNull(drawInstances(mesh)).numInstances)
            assertBufferEquals(geometry.vertexData.buffer, drawGeometry(mesh).vertexData.buffer,
                geometry.numVertices * geometry.layout.structSize)
        } finally { mesh.release() }
    }

    private fun fill(geometry: IndexedVertexList<VertexLayouts.Position>, instances: MeshInstanceList<Instances>,
        count: Int, seed: Float) {
        geometry.clear()
        instances.clear()
        repeat(count) { index ->
            geometry.addVertex { layout -> set(layout.position, seed + index, -seed, index * .25f) }
            instances.addInstance { layout -> set(layout.value, seed, index.toFloat(), index * .5f, -seed) }
        }
        for (index in 0 until count - 2 step 3) geometry.addTriIndices(index, index + 1, index + 2)
    }

    private fun assertBufferEquals(expected: MixedBuffer, actual: MixedBuffer, byteCount: Int) {
        for (index in 0 until byteCount) assertEquals(expected.getUint8(index), actual.getUint8(index), "Byte $index")
    }

    private fun drawGeometry(mesh: Mesh<*>): IndexedVertexList<*> = geometryField.get(mesh) as IndexedVertexList<*>
    private fun drawInstances(mesh: Mesh<*>): MeshInstanceList<*>? = instancesField.get(mesh) as MeshInstanceList<*>?

    companion object {
        private val geometryField = Mesh::class.java.getDeclaredField("drawGeometry").apply { isAccessible = true }
        private val instancesField = Mesh::class.java.getDeclaredField("drawInstances").apply { isAccessible = true }
        private val asyncField = Mesh::class.java.getDeclaredField("isAsyncRendering").apply { isAccessible = true }
    }
}
