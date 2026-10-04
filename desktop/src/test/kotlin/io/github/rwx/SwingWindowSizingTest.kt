package io.github.rwx

import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingWindowSizingTest {
    private val noInsets = Insets(0, 0, 0, 0)
    private val decorated = Insets(31, 8, 8, 8)

    @Test
    fun `a fitting requested content size keeps its logical size`() {
        val bounds = initialWindowedFrameBounds(Dimension(1280, 720), decorated,
            Rectangle(0, 0, 1920, 1080), Insets(0, 0, 40, 0))
        assertEquals(Rectangle(312, 140, 1296, 759), bounds)
        assertEquals(1280, bounds.width - decorated.left - decorated.right)
        assertEquals(720, bounds.height - decorated.top - decorated.bottom)
    }

    @Test
    fun `150 percent laptop work area clamps both default and oversized logical windows`() {
        // A 1920x1200 display at 150% exposes 1280x800 logical bounds, with a 48px logical taskbar.
        for (requested in listOf(Dimension(1280, 720), Dimension(1920, 1080))) {
            val bounds = initialWindowedFrameBounds(requested, decorated,
                Rectangle(0, 0, 1280, 800), Insets(0, 0, 48, 0))
            assertEquals(Rectangle(0, 0, 1280, 752), bounds)
            assertEquals(1264, bounds.width - decorated.left - decorated.right)
            assertEquals(713, bounds.height - decorated.top - decorated.bottom)
        }
    }

    @Test
    fun `undecorated benchmark preserves 1280 by 720 logical content on the scaled laptop`() {
        val bounds = initialWindowedFrameBounds(Dimension(1280, 720), noInsets,
            Rectangle(0, 0, 1280, 800), Insets(0, 0, 48, 0))
        assertEquals(Rectangle(0, 16, 1280, 720), bounds)
        assertEquals(1920, (bounds.width * 1.5).toInt())
        assertEquals(1080, (bounds.height * 1.5).toInt())
    }

    @Test
    fun `work area positioning honors a secondary monitor origin and taskbars on multiple edges`() {
        val work = Rectangle(-1280, 24, 1280, 728)
        val bounds = initialWindowedFrameBounds(Dimension(800, 600), decorated,
            Rectangle(-1280, 0, 1280, 800), Insets(24, 0, 48, 0))
        assertEquals(Rectangle(-1048, 68, 816, 639), bounds)
        assertTrue(work.contains(bounds))
    }

    @Test
    fun `small work areas constrain the outer frame including decorations`() {
        val bounds = initialWindowedFrameBounds(Dimension(1280, 720), decorated,
            Rectangle(0, 0, 800, 600), Insets(0, 0, 40, 0))
        assertEquals(Rectangle(0, 0, 800, 560), bounds)
    }
}
