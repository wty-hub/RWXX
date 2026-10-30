package io.github.rwx.render.canvas

import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasFrameLifecycleTraceTest {
    @Test fun `failed submit cannot become a frame edge and repeated pictures keep the actual submission`() {
        val rows = CanvasFrameLifecycleBuffer(16)
        rows.produced(2, 9, 100)
        rows.chosen(2, 9, 110)
        rows.submitted(2, 9, 5, false, 120)
        rows.submitted(2, 9, 6, true, 130)
        rows.presented(2, 9, 140)
        rows.chosen(2, 9, 150)
        rows.submitted(2, 9, 7, true, 160)
        rows.presented(2, 9, 170)
        val output = StringWriter()
        assertEquals("", output.toString())
        rows.closeTo(output)
        val text = output.toString()
        assertFalse(text.contains(",submitted,2,9,5,"))
        assertTrue(text.contains("140,presented,2,9,6,"))
        assertTrue(text.contains("170,presented,2,9,7,"))
        rows.produced(2, 10, 180)
        rows.closeTo(output)
        assertEquals(text, output.toString(), "shutdown is idempotent and late calls cannot write")
    }

    @Test fun `full buffer retains its prefix and declares every lost event`() {
        val rows = CanvasFrameLifecycleBuffer(2)
        rows.produced(1, 1, 1)
        rows.chosen(1, 1, 2)
        rows.submitted(1, 1, 3, true, 3)
        rows.presented(1, 1, 4)
        val output = StringWriter()
        rows.closeTo(output)
        assertEquals(4, output.toString().lineSequence().filter(String::isNotEmpty).count())
        assertTrue(output.toString().contains(",trace-complete,-1,-1,-1,2,2,2"))
    }

    @Test fun `new choice cannot borrow previous successful submission after a failed submit`() {
        val rows = CanvasFrameLifecycleBuffer(16)
        rows.chosen(1, 1, 1)
        rows.submitted(1, 1, 10, true, 2)
        rows.presented(1, 1, 3)
        rows.chosen(1, 1, 4)
        rows.submitted(1, 1, 11, false, 5)
        rows.presented(1, 1, 6)
        val output = StringWriter()
        rows.closeTo(output)
        assertTrue(output.toString().contains("6,presented,1,1,-1,"))
        assertFalse(output.toString().contains(",submitted,1,1,11,"))
        assertTrue(output.toString().trimEnd().endsWith(",true"))
    }

    @Test fun `input buffering preserves camera bits schema and explicit overflow`() {
        val rows = CanvasInputTraceBuffer(1)
        fun append() = rows.append("camera-effect", 7, "wheel", -1, 1, 2, 3, 4, 5, 6,
            -0.0f, 1.25f, Float.NaN, true)
        val output = StringWriter()
        append(); append()
        assertEquals("", output.toString())
        rows.closeTo(output)
        assertTrue(output.toString().contains("camera-effect,7,wheel,-1,1,2,3,4,5,6,-0.0,1.25,NaN,true"))
        assertTrue(output.toString().contains("trace-overflow,-1,diagnostic,1,"))
        val text = output.toString()
        append(); rows.closeTo(output)
        assertEquals(text, output.toString())
    }
}
