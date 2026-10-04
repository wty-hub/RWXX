package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import com.corrodinggames.rts.gameFramework.graphics.Texture
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import io.github.rwx.geometry.Rect
import kotlin.test.*

class KoolGeneratedTargetInitializationTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    private fun draw(root: KoolGraphicsEngine, texture: Texture): KoolCanvasFrame {
        root.beginFrame(8, 6)
        root.b(texture, 0f, 0f, null)
        return root.snapshot()
    }

    private fun textureId(frame: KoolCanvasFrame) =
        (frame.commands.last() as KoolCanvasCommand.DrawTexture).texture.id

    @Test
    fun `pending generated targets freeze as empty frames and draw nothing over existing geometry`() {
        for (hasAlpha in listOf(false, true)) {
            val store = KoolCanvasCpuTextureStore()
            val root = KoolGraphicsEngine(textureStore = store)
            val texture = root.b(4, 3, hasAlpha)
            root.beginFrame(8, 6)
            root.a(Rect(0, 0, 8, 6), KoolPaint().apply { setColor(0xff234567.toInt()) })
            val background = root.snapshot()
            root.b(texture, 0f, 0f, null)
            val packet = store.freezeFrame(root.snapshot(), 1, 1, 0, 0)
            val frozen = assertIs<FrozenCanvasResource.Frame>(packet.resourceLease.resources().values.single())
            assertEquals(KoolCanvasViewport(4, 3), frozen.frame.viewport)
            assertTrue(frozen.frame.commands.isEmpty(), "pending targets must not contain a nested clear")
            var resolvedTextures = 0
            val renderStore = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
                override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter): Texture2d {
                    resolvedTextures++
                    return KoolCanvasTextureRegistry.resolve(texture, filter)
                }
            }
            val installed = FrozenCanvasGpuResources.install(packet.resourceLease)
            val scene = Scene("pending-generated-target-$hasAlpha")
            try {
                val renderer = KoolCanvasFrameRenderer(renderStore)
                renderer.render(scene, background)
                val backgroundMesh = scene.children.filterIsInstance<Mesh<*>>().single { it.isVisible }
                val vertices = backgroundMesh.geometry.numVertices
                renderer.render(scene, packet.frame)
                assertSame(backgroundMesh, scene.children.filterIsInstance<Mesh<*>>().single { it.isVisible })
                assertEquals(vertices, backgroundMesh.geometry.numVertices, "pending target must not add clearing geometry")
                assertEquals(0, resolvedTextures, "empty Frame must bypass ordinary texture and magenta placeholder resolution")
            } finally {
                scene.release()
                installed.close()
                assertNull(KoolCanvasTextureRegistry.frame(textureId(packet.frame)))
                packet.close()
                texture.o()
            }
        }
    }

    @Test
    fun `first target commit replaces pending content with pixels or recorded commands and releases its source`() {
        for (mode in RenderTargetMode.entries) {
            val store = KoolCanvasCpuTextureStore()
            val root = KoolGraphicsEngine(textureStore = store)
            val texture = root.b(4, 3, true)
            val pending = store.freezeFrame(draw(root, texture), 1, 1, 0, 0)
            val id = textureId(draw(root, texture))
            val target = root.b(texture, mode)
            var committed: FrameEnvelope? = null
            try {
                target.b(0xff123456.toInt())
                target.p()
                val current = store.freezeFrame(draw(root, texture), 2, 1, 0, 0)
                committed = current
                assertNotEquals(textureId(pending.frame), textureId(current.frame))
                when (mode) {
                    RenderTargetMode.IMMEDIATE -> {
                        assertNull(store.frame(id))
                        val pixels = assertIs<FrozenCanvasResource.Pixels>(current.resourceLease.resources().values.single())
                        assertContentEquals(IntArray(12) { 0xff123456.toInt() }, pixels.image.pixels)
                    }
                    RenderTargetMode.DEFAULT -> {
                        val frame = assertIs<FrozenCanvasResource.Frame>(current.resourceLease.resources().values.single())
                        assertTrue(frame.frame.commands.isNotEmpty())
                        assertEquals(0xff123456.toInt(), assertIs<KoolCanvasCommand.Clear>(frame.frame.commands.single()).color.argb)
                    }
                }
                target.q()
                texture.o()
                assertNull(store.frame(id))
                assertNull(store.argbImageView(id))
                val oldEmpty = assertIs<FrozenCanvasResource.Frame>(pending.resourceLease.resources().values.single())
                assertTrue(oldEmpty.frame.commands.isEmpty(), "the pending lease must remain detached after commit and release")
                val sources = KoolCanvasCpuTextureStore::class.java.getDeclaredField("sources")
                    .apply { isAccessible = true }.get(store) as Map<*, *>
                assertTrue(sources.isEmpty(), "released generated target must not retain a logical source")
            } finally {
                committed?.close()
                pending.close()
                target.q()
                texture.o()
            }
        }
    }

    @Test
    fun `missing drawable and null stream remain missing resources`() {
        val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(textureStore = store, assetBytes = { null })
        val missingDrawable = root.a(Int.MIN_VALUE, true)
        val missingStream = root.a(null as java.io.InputStream?, true)
        try {
            for (texture in listOf(missingDrawable, missingStream)) {
                val packet = store.freezeFrame(draw(root, texture), 1, 1, 0, 0)
                try {
                    assertSame(FrozenCanvasResource.Missing, packet.resourceLease.resources().values.single())
                } finally {
                    packet.close()
                }
            }
        } finally {
            missingDrawable.o()
            missingStream.o()
        }
    }

    @Test
    fun `default texture store releases an uncommitted generated target without resolving a texture`() {
        val root = KoolGraphicsEngine()
        val texture = root.b(4, 3, false)
        val id = textureId(draw(root, texture))
        try {
            assertTrue(assertNotNull(KoolCanvasTextureRegistry.frame(id)).commands.isEmpty())
            assertNull(KoolCanvasTextureRegistry.argbImageView(id))
            texture.o()
            texture.o()
            assertNull(KoolCanvasTextureRegistry.frame(id))
            assertNull(KoolCanvasTextureRegistry.argbImageView(id))
        } finally {
            texture.o()
        }
    }
}
