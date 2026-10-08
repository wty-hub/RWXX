package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.*
import kotlin.test.*

class KoolCanvasTextTemplatesTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }
    private fun data(advance: Float = .6f) = MsdfFontData(Texture2d(TexFormat.RGBA, name = "template-test"),
        MsdfMeta(MsdfAtlasInfo("msdf", 4f, 32f, 128, 128, "bottom"), "template-test",
            MsdfMetrics(1f, 1.27f, .83f, -.24f, -.1f, .05f),
            ("AB选择 0123456789Ω").map { MsdfGlyph(it.code, advance + if (it == '8') .2f else 0f,
                MsdfRect(-.08f, -.21f, .55f, .8f), MsdfRect(2f, 3f, 12f, 17f)) },
            kerning = listOf(MsdfKerning('A'.code, 'B'.code, -.09f))))

    private fun geometry(scene: Scene) = scene.children.filterIsInstance<Mesh<*>>()
        .filter { it.name.startsWith("rwx-kool-canvas-text-") }.sortedBy { it.drawGroupId }.map { mesh ->
            val vertices = mesh.geometry.vertexData
            listOf(if (mesh.isVisible) 1 else 0, mesh.drawGroupId, mesh.geometry.numVertices, mesh.geometry.numIndices) +
                List(mesh.geometry.numVertices * vertices.strideBytes / 4) { vertices.buffer.getFloat32(it * 4).toRawBits() } +
                List(mesh.geometry.numIndices) { mesh.geometry.indices[it] }
        }

    @Test fun `cached text matches library geometry through motion scale rotation clip and font replacement`() {
        val previous = KoolCanvasFontRegistry.base
        val fonts = listOf(data(), data(.83f))
        val controls = listOf(false to false, true to false, true to true).map { (geometry, preparation) ->
            KoolCanvasFrameRenderer(reuseTextGeometry = geometry, prepareText = preparation, reuseTextMeshKeys = preparation) to Scene("text-template-$geometry-$preparation")
        }
        try {
            for (fontData in fonts) for (italic in listOf(-.25f, 0f, .25f)) {
                KoolCanvasFontRegistry.installBaseFont(MsdfFont(fontData, italic = italic,
                    weight = .03f, glowColor = Color(.2f, .3f, .4f, .5f)))
                repeat(4) { frame ->
                    val commands = listOf("AB", "11\n888888", "选择9Ω", " A B ", "missing").mapIndexed { index, text ->
                        KoolCanvasCommand.DrawText(text, KoolCanvasPoint(15.4f + frame * 7.3f, 45.7f + index * 33f),
                            KoolCanvasPaint.Default.copy(textSize = 13.75f + index * 2,
                                color = KoolCanvasColor(0x8045aaff.toInt()), textAlign = KoolCanvasTextAlign.Center),
                            KoolCanvasState(transform = KoolCanvasTransform.Identity.scale(.73f, .89f).rotate(12f),
                                clip = KoolCanvasRect(1f, 2f, 199f, 180f)))
                    }
                    controls.forEach { (renderer, scene) -> renderer.render(scene, KoolCanvasFrame(KoolCanvasViewport(240, 200), commands)) }
                    controls.drop(1).forEach { (_, scene) ->
                        assertEquals(geometry(controls[0].second), geometry(scene), "font=$italic frame=$frame scene=${scene.name}")
                    }
                }
            }
        } finally { KoolCanvasFontRegistry.installBaseFont(previous); fonts.forEach { it.map.release() } }
    }

    @Test fun `reused lookup never mutates stored keys or cached dimensions across font changes and eviction`() {
        val firstData = data(); val replacement = data(.83f)
        try {
            val cache = KoolCanvasTextTemplates(maximumVertices = 32, reuseLookups = true)
            val font = MsdfFont(firstData, sizePts = 13.7f, italic = -.25f)
            val first = cache.geometry(font, "AB")
            val expected = font.textDimensions("AB", TextMetrics())
            fun bits(metrics: TextMetrics) = listOf(metrics.width, metrics.height, metrics.baselineWidth,
                metrics.yBaseline, metrics.ascentPx, metrics.descentPx, metrics.paddingStart, metrics.paddingEnd)
                .map { it.toRawBits() } + metrics.numLines
            assertEquals(bits(expected), bits(checkNotNull(first.metrics)))
            cache.geometry(font, "选择9Ω")
            assertSame(first, cache.geometry(font, "AB"))
            font.scale = .7f
            val scaled = cache.geometry(font, "AB")
            assertNotSame(first, scaled)
            assertEquals(bits(font.textDimensions("AB", TextMetrics())), bits(checkNotNull(scaled.metrics)))
            font.scale = 1f
            assertSame(first, cache.geometry(font, "AB"))
            assertNotSame(first, cache.geometry(MsdfFont(replacement, sizePts = 13.7f, italic = -.25f), "AB"))
            repeat(20) { cache.geometry(font, "A$it") }
            assertContentEquals(first.vertices, cache.geometry(font, "AB").vertices)
            assertEquals(bits(expected), bits(checkNotNull(cache.geometry(font, "AB").metrics)))
        } finally { firstData.map.release(); replacement.map.release() }
    }

    @Test fun `bounded cache survives eviction and distinguishes mutable font scale`() {
        val data = data()
        try {
            val font = MsdfFont(data, sizePts = 17.3f)
            val cache = KoolCanvasTextTemplates(maximumVertices = 24)
            val first = cache.geometry(font, "AB")
            assertSame(first, cache.geometry(font, "AB"))
            repeat(30) { cache.geometry(font, "A$it") }
            assertTrue(cache.size <= 3)
            assertContentEquals(first.vertices, cache.geometry(font, "AB").vertices)
            font.scale = .7f
            assertFalse(first.vertices.contentEquals(cache.geometry(font, "AB").vertices))
            cache.clear()
            assertEquals(0, cache.size)
        } finally { data.map.release() }
    }
}
