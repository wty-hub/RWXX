package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import kotlin.test.*

class KoolCompactTextureInstancesTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }
    private val viewport = KoolCanvasViewport(512, 512)
    private val texture = Texture2d(TexFormat.RGBA, name = "compact-instance-source")
    private val store = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
        override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter) = this@KoolCompactTextureInstancesTest.texture
        override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? = null
    }
    private fun renderer(candidate: Boolean) = KoolCanvasFrameRenderer(store,
        useSpriteAtlas = false, compactTextureInstances = candidate)
    private fun visible(scene: Scene) = scene.children.filterIsInstance<Mesh<*>>()
        .filter { it.isVisible }.sortedBy { it.drawGroupId }
    private fun command(index: Int = 0, alpha: Boolean = false,
                        state: KoolCanvasState = KoolCanvasState.Default): KoolCanvasCommand.DrawTexture {
        val ref = KoolCanvasTextureRef(KoolCanvasTextureId(if (alpha) "alpha" else "opaque"), 32, 32, hasAlpha = alpha)
        val x = (index % 32).toFloat() * 8f
        val y = (index / 32 % 32).toFloat() * 8f
        return KoolCanvasCommand.DrawTexture(ref, KoolCanvasRect(1f, 2f, 17f, 18f),
            KoolCanvasRect(x, y, x + 8f, y + 8f), KoolCanvasPaint.Default, state)
    }
    private fun batch(count: Int): List<KoolCanvasCommand> {
        val recorder = KoolCanvasCommandBuffer(textureBatches = true)
        repeat(count) { index ->
            val command = command(index)
            recorder.drawTexture(command.texture, command.destination, command.paint, command.source)
        }
        return recorder.snapshot().commands
    }
    private fun snapshot(scene: Scene) = visible(scene).map { mesh ->
        listOf(mesh.drawGroupId, mesh.shader!!::class.simpleName, mesh.geometry.numIndices, mesh.geometry.numVertices,
            mesh.instances?.let { instances ->
                val data = instances.instanceData
                // Compare bit patterns so a capacity change cannot hide float geometry changes.
                List(data.limit * data.strideBytes / 4) { data.buffer.getInt32(it * 4) }
            })
    }

    @Test fun smallOpaqueAndAffineRunsAvoid8192EntryReservationWhileOrderedRunsKeepTheirCapacity() {
        for (state in listOf(KoolCanvasState.Default,
            KoolCanvasState(transform = KoolCanvasTransform.Identity.rotate(23f)))) {
            val control = Scene("compact-small-control")
            val candidate = Scene("compact-small-candidate")
            val frame = KoolCanvasFrame(viewport, listOf(command(state = state)))
            renderer(false).render(control, frame)
            renderer(true).render(candidate, frame)
            assertEquals(8192, visible(control).single().instances!!.maxInstances)
            assertEquals(128, visible(candidate).single().instances!!.maxInstances)
            assertEquals(snapshot(control), snapshot(candidate))
        }
        val ordered = Scene("compact-ordered")
        renderer(true).render(ordered, KoolCanvasFrame(viewport, listOf(command(alpha = true))))
        assertEquals(128, visible(ordered).single().instances!!.maxInstances)
    }

    @Test fun knownLargeNumericBatchAllocatesItsRequiredCapacityImmediately() {
        val scene = Scene("compact-known-batch")
        val commands = batch(3000)
        assertIs<KoolCanvasCommand.DrawTextureBatch>(commands.single())
        renderer(true).render(scene, KoolCanvasFrame(viewport, commands))
        val mesh = visible(scene).single()
        assertEquals(3000, mesh.instances!!.numInstances)
        assertEquals(3000, mesh.instances!!.maxInstances)
        val control = Scene("compact-known-control")
        renderer(false).render(control, KoolCanvasFrame(viewport, commands))
        assertEquals(snapshot(control), snapshot(scene))
    }

    @Test fun ordinaryAndSplitNumericRunsGrowPast8192AndRetainTheirBufferAcrossSmallerFrames() {
        for (numeric in listOf(false, true)) {
            val candidate = renderer(true)
            val scene = Scene("compact-grown-$numeric")
            val commands = if (numeric) batch(9000) else List(9000) { command(it) }
            val frame = KoolCanvasFrame(viewport, commands)
            candidate.render(scene, frame)
            val mesh = visible(scene).single()
            val data = mesh.instances!!.instanceData
            val capacity = mesh.instances!!.maxInstances
            assertEquals(9000, mesh.instances!!.numInstances)
            assertTrue(capacity >= 9000)
            val control = Scene("compact-grown-control-$numeric")
            renderer(false).render(control, frame)
            assertEquals(snapshot(control), snapshot(scene))
            repeat(3) {
                candidate.render(scene, KoolCanvasFrame(viewport, listOf(command())))
                assertSame(mesh, visible(scene).single())
                assertEquals(1, mesh.instances!!.numInstances)
                candidate.render(scene, frame)
                assertSame(data, mesh.instances!!.instanceData)
                assertEquals(capacity, mesh.instances!!.maxInstances)
                assertEquals(snapshot(control), snapshot(scene))
            }
        }
    }

    @Test fun alternatingOpaqueAlphaAndPrimitiveBarriersKeepOrderingAndInstanceBits() {
        val control = renderer(false)
        val candidate = renderer(true)
        val left = Scene("compact-order-control")
        val right = Scene("compact-order-candidate")
        repeat(5) { frame ->
            val barrier = KoolCanvasCommand.DrawRect(KoolCanvasRect(4f, 4f, 40f, 40f),
                KoolCanvasPaint.Default.copy(alphaMultiplier = 0.5f), KoolCanvasState.Default)
            val commands = batch(600 + frame) + command(alpha = true) + barrier + batch(5) +
                command(state = KoolCanvasState(transform = KoolCanvasTransform.Identity.rotate(-17f)))
            val recorded = KoolCanvasFrame(viewport, commands)
            control.render(left, recorded)
            candidate.render(right, recorded)
            assertEquals(snapshot(left), snapshot(right), "frame $frame")
            assertTrue(visible(right).size >= 4)
        }
    }
}
