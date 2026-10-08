package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.util.MsdfFont
import kotlin.test.*

class KoolCanvasRenderFontsTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    @Test fun `private derived views do not inherit scale or leak across renderers or public callers`() {
        val source = MsdfFont.DEFAULT_FONT.copy(weight = .12f).apply { scale = 2f }
        val first = KoolCanvasRenderFonts(2)
        val second = KoolCanvasRenderFonts(2)
        val derived = first.font(source, 17f)
        assertEquals(1f, derived.scale)
        assertSame(derived, first.font(source, 17f))
        assertNotSame(derived, second.font(source, 17f))
        assertNotSame(derived, first.font(source.copy(), 17f))
        first.font(source, 19f)
        assertNotSame(derived, first.font(source, 17f))
        assertEquals(2f, source.scale)
        first.clear()
        assertNotSame(derived, first.font(source, 17f))
    }

    @Test fun `a font replacement and delayed lease keep distinct cached views`() {
        val previous = KoolCanvasFontRegistry.base
        val cache = KoolCanvasRenderFonts()
        var old: KoolCanvasFontSnapshot? = null
        try {
            KoolCanvasFontRegistry.installBaseFont(previous.copy(weight = .12f))
            old = KoolCanvasFontRegistry.snapshot()
            val first = KoolCanvasFontRegistry.font(20f, null, cache)
            KoolCanvasFontRegistry.installBaseFont(previous.copy(weight = .34f))
            val next = KoolCanvasFontRegistry.font(20f, null, cache)
            assertEquals(.34f, next.weight)
            assertNotSame(first, next)
            assertSame(first, KoolCanvasFontRegistry.withSnapshot(old) { KoolCanvasFontRegistry.font(20f, null, cache) })
            val public = KoolCanvasFontRegistry.font(20f)
            public.scale = .5f
            assertEquals(1f, next.scale)
            assertEquals(1f, KoolCanvasFontRegistry.font(20f).scale)
        } finally {
            old?.let(KoolCanvasFontRegistry::releaseSnapshot)
            KoolCanvasFontRegistry.installBaseFont(previous)
        }
    }
}
