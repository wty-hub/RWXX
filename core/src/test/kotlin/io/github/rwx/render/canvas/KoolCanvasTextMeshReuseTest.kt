package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.modules.ui2.MsdfUiShader
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.MsdfFontData
import kotlin.test.*

class KoolCanvasTextMeshReuseTest {
    init {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        KoolCanvasFontRegistry.installBaseFont(MsdfFont.DEFAULT_FONT)
    }
    private val viewport = KoolCanvasViewport(200, 100)
    private fun text(value: String = "AA", state: KoolCanvasState = KoolCanvasState.Default,
                     paint: KoolCanvasPaint = KoolCanvasPaint.Default) =
        KoolCanvasCommand.DrawText(value, KoolCanvasPoint(15f, 45f), paint, state)
    private fun barrier() = KoolCanvasCommand.DrawRect(KoolCanvasRect(0f, 0f, 50f, 50f),
        KoolCanvasPaint(color = KoolCanvasColor(0x8000ff00.toInt())), KoolCanvasState.Default)
    private fun render(renderer: KoolCanvasFrameRenderer, scene: Scene, vararg commands: KoolCanvasCommand) =
        renderer.render(scene, KoolCanvasFrame(viewport, commands.toList()))
    private fun textMeshes(scene: Scene) = scene.children.filterIsInstance<Mesh<*>>()
        .filter { it.name.startsWith("rwx-kool-canvas-text-") }
    private fun visible(scene: Scene) = scene.children.filterIsInstance<Mesh<*>>()
        .filter { it.isVisible }.sortedBy { it.drawGroupId }
    private fun vertices(mesh: Mesh<*>): List<Int> {
        val data = mesh.geometry.vertexData
        return List(mesh.geometry.numVertices * data.strideBytes / 4) { data.buffer.getFloat32(it * 4).toRawBits() }
    }

    @Test fun `lookup probes preserve canonical keys across order shifts clips HUD and material changes`() {
        val controls = listOf(false, true).map { keys ->
            KoolCanvasFrameRenderer(reuseTextMeshKeys = keys, prepareText = false) to Scene("text-key-$keys")
        }
        repeat(12) { frame ->
            val commands = listOf(text("A"), text("BBBB", paint = KoolCanvasPaint(blendMode = KoolCanvasBlendMode.Add)),
                text("AA", state = KoolCanvasState(transform = KoolCanvasTransform.Identity.translate(frame.toFloat(), -3f),
                    clip = if (frame % 3 == 0) KoolCanvasRect(1000f, 1000f, 1100f, 1100f) else KoolCanvasRect(0f, 0f, 120f, 80f))),
                text("8", paint = KoolCanvasPaint(typefaceKey = "fallback-key")),
                text("A", state = KoolCanvasState(drawRole = KoolCanvasDrawRole.PerformanceHud)))
            controls.forEach { (renderer, scene) ->
                render(renderer, scene, *(if (frame % 2 == 0) listOf(barrier()) + commands else commands).toTypedArray())
            }
            fun picture(scene: Scene) = visible(scene).map { mesh ->
                listOf(mesh.drawGroupId, mesh.geometry.numVertices, mesh.geometry.numIndices) + vertices(mesh) +
                    List(mesh.geometry.numIndices) { mesh.geometry.indices[it] }
            }
            assertEquals(picture(controls[0].second), picture(controls[1].second), "frame=$frame")
            assertEquals(controls[0].first.textMeshCacheSnapshot(), controls[1].first.textMeshCacheSnapshot())
        }
    }

    @Test fun `shifted preceding batches retain real text mesh shader and buffer owners`() {
        for (enabled in listOf(false, true)) {
            val scene = Scene("text-shift-$enabled")
            val renderer = KoolCanvasFrameRenderer(reuseTextMeshes = enabled)
            render(renderer, scene, text())
            val original = textMeshes(scene).single()
            val shader = original.shader
            val buffer = original.geometry.vertexData.buffer
            repeat(4) { index ->
                if (index % 2 == 0) render(renderer, scene, barrier(), text())
                else render(renderer, scene, text())
            }
            if (enabled) {
                assertSame(original, textMeshes(scene).single())
                assertSame(shader, original.shader)
                assertSame(buffer, original.geometry.vertexData.buffer)
                assertEquals(1L, renderer.textMeshCacheSnapshot().created)
                assertEquals(4L, renderer.textMeshCacheSnapshot().reused)
            } else {
                assertEquals(2, textMeshes(scene).size)
                assertEquals(0L, renderer.textMeshCacheSnapshot().reused)
            }
        }
    }

    @Test fun `same font text separated by a barrier stays in distinct ordered meshes`() {
        val scene = Scene("text-simultaneous")
        val renderer = KoolCanvasFrameRenderer(reuseTextMeshes = true)
        render(renderer, scene, text("A"), barrier(), text("BBBB"))
        val original = textMeshes(scene).toSet()
        assertEquals(2, original.size)
        render(renderer, scene, barrier(), text("A"), barrier(), text("BBBB"))
        assertEquals(original, textMeshes(scene).toSet())
        val ordered = visible(scene)
        assertEquals(listOf(false, true, false, true), ordered.map { it.shader is MsdfUiShader })
        val texts = ordered.filter { it.shader is MsdfUiShader }
        assertNotSame(texts[0], texts[1])
        assertTrue(texts[0].geometry.numVertices < texts[1].geometry.numVertices)
        assertEquals(4, ordered.map { it.drawGroupId }.distinct().size)
    }

    @Test fun `recycled geometry rewrites text clip and transforms exactly like a fresh mesh`() {
        val scene = Scene("text-geometry-reuse")
        val renderer = KoolCanvasFrameRenderer(reuseTextMeshes = true)
        render(renderer, scene, text())
        val original = textMeshes(scene).single()
        val oldVertices = vertices(original)
        val changed = text("BBBB", KoolCanvasState(transform = KoolCanvasTransform.Identity.translate(7f, -3f),
            clip = KoolCanvasRect(3f, 4f, 120f, 80f)))
        render(renderer, scene, barrier(), changed)
        assertSame(original, textMeshes(scene).single())
        val reference = Scene("text-geometry-reference")
        render(KoolCanvasFrameRenderer(reuseTextMeshes = false), reference, barrier(), changed)
        assertNotEquals(oldVertices, vertices(original))
        assertEquals(vertices(textMeshes(reference).single()), vertices(original))
        assertEquals(textMeshes(reference).single().geometry.numIndices, original.geometry.numIndices)
        render(renderer, scene, barrier(), changed.copy(state = changed.state.copy(clip = KoolCanvasRect(1000f, 1000f, 1100f, 1100f))))
        assertFalse(original.isVisible)
        assertEquals(0, original.geometry.numVertices)
    }

    @Test fun `blend typeface and HUD material changes cannot recycle a different owner`() {
        val variants = listOf(text(paint = KoolCanvasPaint(blendMode = KoolCanvasBlendMode.Add)),
            text(paint = KoolCanvasPaint(typefaceKey = "different-font-key")),
            text(state = KoolCanvasState(drawRole = KoolCanvasDrawRole.PerformanceHud)))
        for ((index, changed) in variants.withIndex()) {
            val scene = Scene("text-material-$index")
            val renderer = KoolCanvasFrameRenderer(reuseTextMeshes = true)
            render(renderer, scene, text())
            val original = textMeshes(scene).single()
            render(renderer, scene, barrier(), changed)
            assertNotSame(original, textMeshes(scene).single { it.isVisible })
            assertEquals(0L, renderer.textMeshCacheSnapshot().reused)
            assertEquals(2L, renderer.textMeshCacheSnapshot().created)
        }
    }

    @Test fun `a font atlas replacement keeps a separate shader font owner`() {
        val base = MsdfFont.DEFAULT_FONT
        val atlas = Texture2d(TexFormat.RGBA, name = "text-reuse-replacement-atlas")
        try {
            val scene = Scene("text-font-owner")
            val renderer = KoolCanvasFrameRenderer(reuseTextMeshes = true)
            render(renderer, scene, text())
            val original = textMeshes(scene).single()
            KoolCanvasFontRegistry.installBaseFont(MsdfFont(MsdfFontData(atlas, base.data.meta)))
            render(renderer, scene, barrier(), text())
            val replacement = textMeshes(scene).single { it.isVisible }
            assertNotSame(original, replacement)
            assertSame(atlas, (replacement.shader as MsdfUiShader).fontMap)
            assertSame(base.data.map, (original.shader as MsdfUiShader).fontMap)
            assertEquals(0L, renderer.textMeshCacheSnapshot().reused)
        } finally {
            KoolCanvasFontRegistry.installBaseFont(base)
            atlas.release()
        }
    }

    @Test fun `HUD refresh after recycling changes only its isolated geometry`() {
        var rates: CanvasFrameRateSample? = null
        val scene = Scene("text-hud-owner")
        val renderer = KoolCanvasFrameRenderer(performanceRates = { rates }, reuseTextMeshes = true)
        val hud = text("255fps", KoolCanvasState(drawRole = KoolCanvasDrawRole.PerformanceHud))
        val world = text("World unit")
        render(renderer, scene, hud, barrier(), world)
        render(renderer, scene, barrier(), hud, barrier(), world)
        val meshes = visible(scene).filter { it.shader is MsdfUiShader }
        assertEquals(2, meshes.size)
        val hudBefore = vertices(meshes[0])
        val worldBefore = vertices(meshes[1])
        rates = CanvasFrameRateSample(1.0, 120.0, 255.0, 60.0, 60.0, .5)
        assertTrue(renderer.refreshPerformanceHud(viewport))
        assertNotEquals(hudBefore, vertices(meshes[0]))
        assertEquals(worldBefore, vertices(meshes[1]))
        assertFalse(renderer.refreshPerformanceHud(viewport))
    }

    @Test fun `scene replacement and deferred pruning cannot reuse retired mesh owners`() {
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            val renderer = KoolCanvasFrameRenderer(reuseTextMeshes = true)
            val firstScene = Scene("text-first-scene")
            render(renderer, firstScene, text())
            val first = textMeshes(firstScene).single()
            val nextScene = Scene("text-next-scene")
            render(renderer, nextScene, text())
            val next = textMeshes(nextScene).single()
            assertNotSame(first, next)
            repeat(62) { render(renderer, nextScene) }
            assertTrue(textMeshes(nextScene).isEmpty())
            assertEquals(1L, renderer.textMeshCacheSnapshot().pruned)
            assertFalse(next.isReleased)
            assertTrue(pending.isNotEmpty())
            while (pending.isNotEmpty()) pending.removeAt(0).invoke()
            assertTrue(next.isReleased)
            render(renderer, nextScene, barrier(), text())
            assertNotSame(next, textMeshes(nextScene).single())
        } finally {
            pending.forEach { it.invoke() }
            KoolCanvasGpuRetirement.install(null)
        }
    }
}
