package io.github.rwx.render.canvas

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class KoolCanvasSurfaceClearTest {
    private val viewport = KoolCanvasViewport(1280, 720)
    private val blackClear = KoolCanvasCommand.Clear(
        KoolCanvasColor(0xff000000.toInt()), KoolCanvasBlendMode.Source,
    )

    private fun label(text: String) = KoolCanvasCommand.DrawText(
        text, KoolCanvasPoint(12f, 24f), KoolCanvasPaint.Default, KoolCanvasState.Default,
    )

    @Test
    fun `an existing surface clear preserves the frame and its original position`() {
        for (blend in KoolCanvasBlendMode.entries) {
            val commands = listOf(label("before"), KoolCanvasCommand.Clear(KoolCanvasColor.White, blend), label("after"))
            val frame = KoolCanvasFrame(viewport, Collections.unmodifiableList(commands))

            assertSame(frame, frame.withSurfaceClear())
            assertSame(frame.commands, frame.withSurfaceClear().commands)
            assertEquals(commands, frame.commands)
        }
    }

    @Test
    fun `an offscreen clear keeps its position after the prepended surface clear`() {
        val offscreenClear = KoolCanvasCommand.Clear(
            KoolCanvasColor.White, KoolCanvasBlendMode.ClearAlpha, KoolCanvasRenderTargetId("offscreen"),
        )
        val original = Collections.unmodifiableList(listOf(label("before"), offscreenClear, label("after")))
        val stats = KoolCanvasVisualStats(2, 5)
        val frame = KoolCanvasFrame(viewport, original, stats)
        val cleared = frame.withSurfaceClear()

        assertSame(viewport, cleared.viewport)
        assertSame(stats, cleared.visualStats)
        assertEquals(listOf(blackClear) + original, cleared.commands)
        for (index in original.indices) assertSame(original[index], cleared.commands[index + 1])
        assertEquals(3, original.size)
        assertSame(cleared, cleared.withSurfaceClear())
        assertFalse(cleared.commands is MutableList<*>)
    }

    @Test
    fun `empty and populated views retain list indexing iteration and equality semantics`() {
        for (count in listOf(0, 1, 17)) {
            val original = Collections.unmodifiableList(List(count) { label("label-$it") })
            val actual = KoolCanvasFrame(viewport, original).withSurfaceClear().commands
            val expected = listOf(blackClear) + original

            assertEquals(expected.size, actual.size)
            assertEquals(expected, actual)
            assertEquals(actual, expected)
            assertEquals(expected.hashCode(), actual.hashCode())
            assertEquals(expected, actual.toList())
            assertEquals(original, actual.subList(1, actual.size))
            for (index in expected.indices) assertEquals(expected[index], actual[index])
            val iterator = actual.listIterator(actual.size)
            for (command in expected.asReversed()) assertEquals(command, iterator.previous())
            assertFalse(iterator.hasPrevious())
            for (command in expected) assertEquals(command, iterator.next())
            assertFalse(iterator.hasNext())
            assertFailsWith<IndexOutOfBoundsException> { actual[-1] }
            assertFailsWith<IndexOutOfBoundsException> { actual[actual.size] }
            assertFailsWith<IndexOutOfBoundsException> { actual.listIterator(actual.size + 1) }
        }
    }
}
