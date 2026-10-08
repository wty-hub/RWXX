package io.github.rwx.render.canvas

import kotlin.test.*

class KoolCanvasPaintOperationsTest {
    @Test fun `unchanged effects reuse only the exact immutable paint and preserve all values`() {
        val effect = KoolCanvasTextureEffect.TeamColor(KoolCanvasTeamColorMode.PureGreen, KoolCanvasColor(0xff55aaff.toInt()), .42f)
        for (existing in listOf(null, effect)) {
            val paint = KoolCanvasPaint(color = KoolCanvasColor(0x80554433.toInt()), alphaMultiplier = .37f,
                blendMode = KoolCanvasBlendMode.Add, textureEffect = existing, textSize = 19f)
            for (requested in listOf(null, existing)) {
                val old = KoolCanvasPaintOperations.textureEffect(paint, requested, false)
                val candidate = KoolCanvasPaintOperations.textureEffect(paint, requested, true)
                assertEquals(old, candidate)
                assertNotSame(paint, old)
                assertSame(paint, candidate)
            }
        }
    }

    @Test fun `a different team effect stays detached and leaves an earlier retained command unchanged`() {
        val first = KoolCanvasTextureEffect.TeamColor(KoolCanvasTeamColorMode.PureGreen, KoolCanvasColor(0xff55aaff.toInt()), .42f)
        val next = first.copy(color = KoolCanvasColor(0xffaa5533.toInt()))
        val paint = KoolCanvasPaint(textureEffect = first)
        val previous = KoolCanvasCommand.DrawText("retained", KoolCanvasPoint(1f, 2f), paint, KoolCanvasState.Default)
        val changed = KoolCanvasPaintOperations.textureEffect(paint, next, true)
        assertNotSame(paint, changed)
        assertEquals(paint.copy(textureEffect = next), changed)
        assertSame(paint, previous.paint)
        assertEquals(first, previous.paint.textureEffect)
    }
}
