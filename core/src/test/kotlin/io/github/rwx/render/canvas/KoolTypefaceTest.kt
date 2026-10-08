package io.github.rwx.render.canvas

import kotlin.test.*

class KoolTypefaceTest {
    @Test fun `font identity preserves legacy family style and cached style derivation`() {
        val face = KoolTypeface.a("queue-test-family", 0)
        assertEquals("queue-test-family:0", face.koolKey)
        assertFalse(face.a())
        assertSame(face, KoolTypeface.a(face, 0))
        val derived = KoolTypeface.a(face, 65)
        assertEquals("queue-test-family:65", derived.koolKey)
        assertTrue(derived.a())
        assertSame(derived, KoolTypeface.a(face, 65))
        assertEquals("default:0", KoolTypeface.a.koolKey)
        assertEquals("default:1", KoolTypeface.b.koolKey)
        assertEquals("sans-serif:0", KoolTypeface.c.koolKey)
        if (System.getenv("RWX_REUSE_TYPEFACE_KEYS") == "1") {
            assertSame(face.koolKey, face.koolKey)
            assertSame(derived.koolKey, derived.koolKey)
        }
    }
}
