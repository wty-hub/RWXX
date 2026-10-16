package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.modules.ui2.UiTextVertexLayout
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.scene.geometry.IndexedVertexList
import de.fabmax.kool.util.*
import kotlin.test.*

class KoolCanvasTextVisibilityTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }
    private val viewport = KoolCanvasViewport(200, 100)
    private fun font() = MsdfFont(MsdfFontData(Texture2d(TexFormat.RGBA, name = "visibility-test"),
        MsdfMeta(MsdfAtlasInfo("msdf", 4f, 32f, 128, 128, "bottom"), "visibility-test",
            MsdfMetrics(1f, 1.27f, .83f, -.24f, -.1f, .05f),
            "AB选择 18".map { MsdfGlyph(it.code, if (it == '8') .9f else .6f,
                MsdfRect(-.15f, -.25f, .65f, .9f), MsdfRect(2f, 3f, 12f, 17f)) },
            kerning = listOf(MsdfKerning('A'.code, 'B'.code, -.1f)))), sizePts = 17f, italic = -.4f,
        glowColor = Color(.2f, .3f, .4f, .5f))

    private fun command(x: Float, y: Float, transform: KoolCanvasTransform = KoolCanvasTransform.Identity,
                        align: KoolCanvasTextAlign = KoolCanvasTextAlign.Left, text: String = "AB选择18") =
        KoolCanvasCommand.DrawText(text, KoolCanvasPoint(x, y),
            KoolCanvasPaint.Default.copy(textSize = 17f, textAlign = align), KoolCanvasState(transform = transform))

    private fun visible(scene: Scene) = scene.children.filterIsInstance<Mesh<*>>().filter { it.isVisible }
    private fun geometry(scene: Scene) = visible(scene).flatMap { mesh ->
        val vertices = mesh.geometry.vertexData
        List(mesh.geometry.numVertices * vertices.strideBytes / 4) { vertices.buffer.getFloat32(it * 4).toRawBits() }
    }

    @Test fun `never rejects library glyphs intersecting any viewport edge under align scale skew rotation or reflection`() {
        val previous = KoolCanvasFontRegistry.base; val font = font()
        val control = KoolCanvasFrameRenderer(cullOffscreenText = false, reuseTextGeometry = false)
        val candidate = KoolCanvasFrameRenderer(cullOffscreenText = true, reuseTextGeometry = false)
        val left = Scene("text-visibility-control"); val right = Scene("text-visibility-candidate")
        try {
            KoolCanvasFontRegistry.installBaseFont(font)
            val transforms = listOf(KoolCanvasTransform.Identity,
                KoolCanvasTransform.Identity.translate(80f, 20f).rotate(25f).scale(.7f, 1.3f),
                KoolCanvasTransform(scaleX = -1f, skewX = .2f, translateX = 200f))
            for (transform in transforms) for (align in KoolCanvasTextAlign.entries)
                for (x in listOf(-200f, -50.4f, -.4f, 100.5f, 199.6f, 250f, 400f))
                    for (y in listOf(-100f, -.5f, 10.4f, 99.6f, 125f, 200f)) {
                        val frame = KoolCanvasFrame(viewport, listOf(command(x, y, transform, align)))
                        control.render(left, frame); candidate.render(right, frame)
                        if (visible(right).isNotEmpty()) assertEquals(geometry(left), geometry(right))
                        else {
                            val positions = visible(left).flatMap { mesh ->
                                @Suppress("UNCHECKED_CAST")
                                val vertices = (mesh.geometry as IndexedVertexList<UiTextVertexLayout>).vertexData
                                val scratch = MutableVec3f()
                                List(mesh.geometry.numVertices) { i -> vertices.get(i) { layout ->
                                    get(layout.position, scratch) }; scratch.x + 100f to 50f - scratch.y }
                            }
                            assertTrue(positions.isEmpty() || positions.maxOf { it.first } < 0f ||
                                positions.minOf { it.first } > 200f || positions.maxOf { it.second } < 0f ||
                                positions.minOf { it.second } > 100f, "visible text rejected at $x,$y/$align/$transform")
                        }
                    }
            assertTrue(candidate.culledOffscreenTextCount() > 0)
        } finally { KoolCanvasFontRegistry.installBaseFont(previous); font.data.map.release() }
    }

    @Test fun `culled text creates no mesh and returns when camera moves while HUD and multiline retain original path`() {
        val previous = KoolCanvasFontRegistry.base; val font = font()
        try {
            KoolCanvasFontRegistry.installBaseFont(font)
            val renderer = KoolCanvasFrameRenderer(cullOffscreenText = true)
            val scene = Scene("visibility-motion")
            renderer.render(scene, KoolCanvasFrame(viewport, listOf(command(500f, 30f))))
            assertEquals(0, renderer.cachedMeshCount())
            assertEquals(1L, renderer.culledOffscreenTextCount())
            renderer.render(scene, KoolCanvasFrame(viewport, listOf(command(20f, 30f))))
            assertTrue(visible(scene).isNotEmpty())
            renderer.render(scene, KoolCanvasFrame(viewport, listOf(command(500f, 30f, text = "AB\n18"),
                command(500f, 30f).copy(state = KoolCanvasState(drawRole = KoolCanvasDrawRole.PerformanceHud)))))
            assertEquals(1L, renderer.culledOffscreenTextCount())
        } finally { KoolCanvasFontRegistry.installBaseFont(previous); font.data.map.release() }
    }

    @Test fun `nonfinite transforms are left for the original draw path`() {
        val font = font()
        try {
            assertFalse(KoolCanvasTextVisibility().outside(font, command(500f, 20f,
                KoolCanvasTransform(translateX = Float.NaN)), viewport))
        } finally { font.data.map.release() }
    }

    @Test fun `bounded metrics cache matches actual library quads through font changes kerning and digit widths`() {
        val source = font(); val replacement = font()
        val cache = KoolCanvasTextVisibility(capacity = 8)
        try {
            val templates = KoolCanvasTextTemplates()
            for (data in listOf(source.data, replacement.data)) for (italic in listOf(-.4f, 0f, .4f))
                for (size in listOf(9.7f, 17f, 31.3f)) for (scale in listOf(.7f, 1f, 1.3f)) {
                    val font = MsdfFont(data, sizePts = size, italic = italic).apply { this.scale = scale }
                    for (text in listOf("AB", "1818", "选择", " A missing B ")) {
                        val expected = templates.geometry(font, text)
                        val xs = expected.vertices.indices.step(5).map { expected.vertices[it] }
                        val ys = expected.vertices.indices.step(5).map { expected.vertices[it + 1] }
                        val bounds = cache.bounds(font, text)
                        assertEquals(xs.min(), bounds.left); assertEquals(xs.max(), bounds.right)
                        assertEquals(ys.min(), bounds.top); assertEquals(ys.max(), bounds.bottom)
                        assertEquals(font.textDimensions(text, TextMetrics()).baselineWidth, bounds.baselineWidth)
                        assertSame(bounds, cache.bounds(font, text))
                        assertTrue(cache.size <= 8)
                    }
                }
            cache.clear()
            assertEquals(0, cache.size)
        } finally { source.data.map.release(); replacement.data.map.release() }
    }
}
