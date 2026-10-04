package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.MsdfAtlasInfo
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.MsdfFontData
import de.fabmax.kool.util.MsdfGlyph
import de.fabmax.kool.util.MsdfMeta
import de.fabmax.kool.util.MsdfMetrics
import de.fabmax.kool.util.MsdfRect
import de.fabmax.kool.util.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class KoolPrimitiveTextMetricsTest {
    init {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
    }

    private fun fontData(advance: Float = .61f, extra: List<MsdfGlyph> = emptyList()): MsdfFontData {
        val glyphs = listOf('A', 'B', ' ', '\t', 'Ω', '选', '择', '单', '位', '\u0661', '\u0669')
            .map { MsdfGlyph(it.code, advance, MsdfRect(-.08f, -.21f, .55f, .8f), MsdfRect(2f, 3f, 12f, 17f)) } +
            ('0'..'9').map { MsdfGlyph(it.code, .37f + (it - '0') * .031f,
                MsdfRect(0f, -.17f, .47f, .73f), MsdfRect(14f, 3f, 24f, 17f)) } + extra
        val meta = MsdfMeta(
            atlas = MsdfAtlasInfo("msdf", 4f, 32f, 128, 128, "bottom"),
            name = "primitive-metrics-oracle",
            metrics = MsdfMetrics(1f, 1.27f, .83f, -.24f, -.1f, .05f),
            glyphs = glyphs,
        )
        return MsdfFontData(Texture2d(TexFormat.RGBA, name = "primitive-metrics-oracle-atlas"), meta)
    }

    private fun bits(metrics: TextMetrics): List<Int> = listOf(
        metrics.baselineWidth.toRawBits(), metrics.height.toRawBits(), metrics.yBaseline.toRawBits(),
        metrics.paddingStart.toRawBits(), metrics.paddingEnd.toRawBits(), metrics.ascentPx.toRawBits(),
        metrics.descentPx.toRawBits(), metrics.width.toRawBits(), metrics.numLines,
    )

    private fun assertOracle(helper: KoolPrimitiveTextMetrics, font: MsdfFont, text: String, fixedDigits: Boolean = true) {
        val expected = font.textDimensions(text, TextMetrics(), fixedDigits)
        val actual = helper.textDimensions(font, text, fixedDigits)
        assertEquals(bits(expected), bits(actual), "text=$text size=${font.sizePts} scale=${font.scale} italic=${font.italic} digits=$fixedDigits")
    }

    @Test fun `all metrics match real Kool bits across sizes scales digit modes and multiline text`() {
        val data = fontData()
        try {
            val helper = KoolPrimitiveTextMetrics()
            val texts = listOf("", "A B", "11\n888888", "A\nB\n", "\n\n", "选择单位2000", "ΩA\t9",
                "\u0661A\u0669", "\u0001Z", "\uD83D\uDE00", " A  B ")
            for (italic in listOf(-.25f, 0f, .25f)) for (size in listOf(1f, 13.75f, 31f)) {
                val font = MsdfFont(data, sizePts = size, italic = italic)
                for (scale in listOf(.75f, 1f, 1.125f)) {
                    font.scale = scale
                    for (fixedDigits in listOf(false, true)) for (text in texts) {
                        assertOracle(helper, font, text, fixedDigits)
                    }
                }
            }
        } finally { data.map.release() }
    }

    @Test fun `UTF16 lookup preserves completed glyph map duplicate and truncated Unicode semantics`() {
        val data = fontData(extra = listOf(MsdfGlyph('A'.code, .93f), MsdfGlyph(0x10041, .17f),
            MsdfGlyph(0xD83D, .23f), MsdfGlyph(0xDE00, .41f)))
        try {
            val helper = KoolPrimitiveTextMetrics()
            val font = MsdfFont(data, sizePts = 17.25f)
            for (text in listOf("AAA", "A\uD83D\uDE00B", "\uD83D\n\uDE00", "\uFFFFA")) {
                assertOracle(helper, font, text)
            }
        } finally { data.map.release() }
    }

    @Test fun `atlas replacement with the same font name uses its own glyph data and retains old results`() {
        val first = fontData(.31f)
        val replacement = fontData(.79f)
        try {
            val helper = KoolPrimitiveTextMetrics()
            val oldFont = MsdfFont(first, sizePts = 19f)
            val newFont = MsdfFont(replacement, sizePts = 19f)
            val old = helper.textDimensions(oldFont, "AB选择")
            assertNotEquals(bits(old), bits(helper.textDimensions(newFont, "AB选择")))
            repeat(3) {
                assertOracle(helper, newFont, "AB选择")
                assertOracle(helper, oldFont, "AB选择")
            }
            assertEquals(bits(oldFont.textDimensions("AB选择", TextMetrics())), bits(old))
        } finally { first.map.release(); replacement.map.release() }
    }

    @Test fun `fresh results prevent italic padding or caller mutations leaking into later dimensions`() {
        val data = fontData()
        try {
            val helper = KoolPrimitiveTextMetrics()
            val italic = MsdfFont(data, sizePts = 12.5f, italic = .25f)
            val first = helper.textDimensions(italic, "A\nBB")
            val before = bits(first)
            repeat(3) { assertOracle(helper, italic, "A\nBB") }
            assertEquals(before, bits(first))
            first.paddingStart = 123f
            first.paddingEnd = 456f
            val next = helper.textDimensions(italic, "A\nBB")
            assertNotSame(first, next)
            assertEquals(before, bits(next))
        } finally { data.map.release() }
    }

    @Test fun `renderer opt in preserves aligned clipped transformed and HUD geometry bits`() {
        val previous = KoolCanvasFontRegistry.base
        val data = fontData()
        try {
            KoolCanvasFontRegistry.installBaseFont(MsdfFont(data, italic = .25f))
            val viewport = KoolCanvasViewport(200, 100)
            var rates: CanvasFrameRateSample? = null
            val commands = listOf(
                KoolCanvasCommand.DrawText("AB选择", KoolCanvasPoint(75.25f, 45.5f),
                    KoolCanvasPaint(textSize = 13.75f, textAlign = KoolCanvasTextAlign.Center),
                    KoolCanvasState(transform = KoolCanvasTransform.Identity.translate(7f, -3f), clip = KoolCanvasRect(3f, 4f, 150f, 80f))),
                KoolCanvasCommand.DrawText("11\n888", KoolCanvasPoint(180f, 65f),
                    KoolCanvasPaint(textAlign = KoolCanvasTextAlign.Right), KoolCanvasState.Default),
                KoolCanvasCommand.DrawText("255fps", KoolCanvasPoint(15f, 20f), KoolCanvasPaint.Default,
                    KoolCanvasState(drawRole = KoolCanvasDrawRole.PerformanceHud)),
                KoolCanvasCommand.DrawText("AB", KoolCanvasPoint(15f, 45f), KoolCanvasPaint.Default,
                    KoolCanvasState(clip = KoolCanvasRect(1000f, 1000f, 1100f, 1100f))),
            )
            val renderers = listOf(false, true).map { enabled ->
                val renderer = KoolCanvasFrameRenderer(primitiveTextMetrics = enabled, performanceRates = { rates })
                val scene = Scene("primitive-text-metrics-$enabled")
                renderer.render(scene, KoolCanvasFrame(viewport, commands))
                renderer to scene
            }
            fun geometry(scene: Scene): List<List<Int>> = scene.children.filterIsInstance<Mesh<*>>()
                .filter { it.name.startsWith("rwx-kool-canvas-text-") }.sortedBy { it.drawGroupId }.map { mesh ->
                    val vertices = mesh.geometry.vertexData
                    listOf(if (mesh.isVisible) 1 else 0, mesh.drawGroupId, mesh.geometry.numVertices, mesh.geometry.numIndices) +
                        List(mesh.geometry.numVertices * vertices.strideBytes / 4) { vertices.buffer.getFloat32(it * 4).toRawBits() } +
                        List(mesh.geometry.numIndices) { mesh.geometry.indices[it] }
                }
            val expected = geometry(renderers[0].second)
            assertTrue(expected.any { it[2] > 0 }, "The real builder must produce nonempty glyph geometry")
            assertEquals(expected, geometry(renderers[1].second))
            rates = CanvasFrameRateSample(1.0, 120.0, 255.0, 60.0, 60.0, .5)
            renderers.forEach { assertTrue(it.first.refreshPerformanceHud(viewport)) }
            assertEquals(geometry(renderers[0].second), geometry(renderers[1].second))
        } finally { KoolCanvasFontRegistry.installBaseFont(previous); data.map.release() }
    }
}
