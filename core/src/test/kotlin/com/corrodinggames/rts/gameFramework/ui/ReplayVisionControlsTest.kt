package com.corrodinggames.rts.gameFramework.ui

import kotlin.test.*

class ReplayVisionControlsTest {
    private fun click(controls: ReplayVisionControls, layout: ReplayVisionControls.Layout,
                      box: ReplayVisionControls.Box, enabled: Boolean = true, actions: MutableList<Int>) {
        val x = box.left() + box.width() / 2
        val y = box.top() + box.height() / 2
        assertTrue(controls.handle(layout, true, x, y, 0, 8, enabled, actions::add))
        assertTrue(controls.handle(layout, false, x, y, 0, 8, enabled, actions::add))
    }

    @Test fun `menu captures toggle rows outside dismissal and disabled seeking input`() {
        val controls = ReplayVisionControls()
        val layout = ReplayVisionControls.layout(800f, 600f, 1f, 35f, 40f, 8)
        val actions = mutableListOf<Int>()
        click(controls, layout, layout.button(), actions = actions)
        assertTrue(controls.isOpen)
        click(controls, layout, layout.toggle(), actions = actions)
        click(controls, layout, layout.row(1), actions = actions)
        assertEquals(listOf(-1, 1), actions)
        click(controls, layout, layout.toggle(), enabled = false, actions = actions)
        click(controls, layout, layout.row(2), enabled = false, actions = actions)
        assertEquals(listOf(-1, 1), actions)
        assertTrue(controls.handle(layout, true, 0f, 0f, 0, 8, true, actions::add))
        assertTrue(controls.handle(layout, false, 0f, 0f, 0, 8, true, actions::add))
        assertFalse(controls.isOpen)
        assertFalse(controls.handle(layout, true, 0f, 0f, 0, 8, true, actions::add))
    }

    @Test fun `narrow layout scrolls bounds selection and reset`() {
        val controls = ReplayVisionControls()
        val actions = mutableListOf<Int>()
        for ((width, height) in listOf(320f to 240f, 800f to 600f, 1920f to 1080f)) {
            val layout = ReplayVisionControls.layout(width, height, 2f, 40f, 50f, 8)
            assertTrue(layout.panel().left() >= 0)
            assertTrue(layout.panel().left() + layout.panel().width() <= width)
            assertTrue(layout.panel().top() >= layout.button().top() + layout.button().height())
            assertTrue(layout.panel().top() + layout.panel().height() <= height)
        }
        val layout = ReplayVisionControls.layout(320f, 240f, 1f, 35f, 40f, 8)
        assertTrue(layout.visibleRows() < 8)
        click(controls, layout, layout.button(), actions = actions)
        click(controls, layout, layout.next(), actions = actions)
        val first = controls.firstRow
        assertTrue(first > 0)
        click(controls, layout, layout.row(0), actions = actions)
        assertEquals(first, actions.last())
        repeat(20) { controls.handle(layout, false, 0f, 0f, -1, 8, true, actions::add) }
        assertEquals(8 - layout.visibleRows(), controls.firstRow)
        assertTrue(controls.close()) // same dismissal used by Escape
        controls.reset()
        assertEquals(0, controls.firstRow)
        assertFalse(controls.isOpen)
    }

    @Test fun `dragging off a row cancels selection and a map press cannot open menu`() {
        val controls = ReplayVisionControls()
        val layout = ReplayVisionControls.layout(800f, 600f, 1f, 35f, 40f, 8)
        val actions = mutableListOf<Int>()
        assertFalse(controls.handle(layout, true, 0f, 0f, 0, 8, true, actions::add))
        assertFalse(controls.handle(layout, true, layout.button().left(), layout.button().top(), 0, 8, true, actions::add))
        controls.handle(layout, false, 0f, 0f, 0, 8, true, actions::add)
        click(controls, layout, layout.button(), actions = actions)
        val row = layout.row(0)
        controls.handle(layout, true, row.left() + 1, row.top() + 1, 0, 8, true, actions::add)
        assertTrue(controls.handle(layout, false, 0f, 0f, 0, 8, true, actions::add))
        assertTrue(actions.isEmpty())
    }
}
