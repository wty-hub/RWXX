package com.corrodinggames.rts.gameFramework.ui

import kotlin.test.*

class ReplayPlaybackControlsTest {
    private val layout = ReplayPlaybackControls.layout(800f, 1f, 40f)

    private fun click(controls: ReplayPlaybackControls, box: ReplayPlaybackControls.Box,
                      enabled: Boolean = true, actions: MutableList<Int>) {
        val x = box.left() + box.width() / 2
        val y = box.top() + box.height() / 2
        assertTrue(controls.handle(layout, true, x, y, enabled, actions::add))
        assertTrue(controls.handle(layout, false, x, y, enabled, actions::add))
    }

    @Test fun `transport emits each action once on release and leaves informational boxes alone`() {
        val controls = ReplayPlaybackControls()
        val actions = mutableListOf<Int>()
        for ((box, action) in listOf(layout.slower() to ReplayPlaybackControls.SLOWER,
                layout.playPause() to ReplayPlaybackControls.PLAY_PAUSE,
                layout.faster() to ReplayPlaybackControls.FASTER)) {
            val x = box.left() + 1
            val y = box.top() + 1
            val before = actions.size
            assertTrue(controls.handle(layout, true, x, y, true, actions::add))
            assertTrue(controls.handle(layout, true, x, y, true, actions::add))
            assertEquals(before, actions.size)
            assertTrue(controls.handle(layout, false, x, y, true, actions::add))
            assertEquals(action, actions.last())
            assertEquals(before + 1, actions.size)
            assertFalse(controls.handle(layout, false, x, y, true, actions::add))
        }
        for (box in listOf(layout.speed(), layout.vision())) {
            val x = box.left() + 1
            val y = box.top() + 1
            assertFalse(controls.contains(layout, x, y))
            assertFalse(controls.handle(layout, true, x, y, true, actions::add))
            assertFalse(controls.handle(layout, false, x, y, true, actions::add))
        }
        assertEquals(3, actions.size)
    }

    @Test fun `dragging off a button cancels while captured release cannot reach the map`() {
        val controls = ReplayPlaybackControls()
        val actions = mutableListOf<Int>()
        val box = layout.playPause()
        assertTrue(controls.handle(layout, true, box.left() + 1, box.top() + 1, true, actions::add))
        assertTrue(controls.contains(layout, 0f, 500f))
        assertTrue(controls.handle(layout, true, 0f, 500f, true, actions::add))
        assertTrue(controls.handle(layout, false, 0f, 500f, true, actions::add))
        assertFalse(controls.contains(layout, 0f, 500f))
        assertTrue(actions.isEmpty())

        // Releasing over a different transport button also cancels the original press.
        controls.handle(layout, true, box.left() + 1, box.top() + 1, true, actions::add)
        controls.handle(layout, false, layout.faster().left() + 1, box.top() + 1, true, actions::add)
        assertTrue(actions.isEmpty())
    }

    @Test fun `disabled seeking transport captures presses without changing playback`() {
        val controls = ReplayPlaybackControls()
        val actions = mutableListOf<Int>()
        for (box in listOf(layout.slower(), layout.playPause(), layout.faster())) {
            click(controls, box, enabled = false, actions = actions)
        }
        // Becoming disabled during a press must also prevent the action on release.
        val box = layout.playPause()
        controls.handle(layout, true, box.left() + 1, box.top() + 1, true, actions::add)
        assertTrue(controls.handle(layout, false, box.left() + 1, box.top() + 1, false, actions::add))
        assertTrue(actions.isEmpty())
    }

    @Test fun `a map press cannot become a transport click and reset cancels an active press`() {
        val controls = ReplayPlaybackControls()
        val actions = mutableListOf<Int>()
        val box = layout.playPause()
        assertFalse(controls.handle(layout, true, 0f, 500f, true, actions::add))
        assertFalse(controls.handle(layout, true, box.left() + 1, box.top() + 1, true, actions::add))
        assertFalse(controls.handle(layout, false, box.left() + 1, box.top() + 1, true, actions::add))
        controls.handle(layout, true, box.left() + 1, box.top() + 1, true, actions::add)
        controls.reset()
        assertFalse(controls.handle(layout, false, box.left() + 1, box.top() + 1, true, actions::add))
        assertTrue(actions.isEmpty())
        click(controls, box, actions = actions)
        assertEquals(listOf(ReplayPlaybackControls.PLAY_PAUSE), actions)
    }

    @Test fun `toolbar scales to narrow and large viewports without overlapping controls`() {
        for ((width, scale) in listOf(240f to 2f, 320f to 2f, 800f to 1f, 1920f to 2f)) {
            val scaled = ReplayPlaybackControls.layout(width, scale, 40f)
            val boxes = listOf(scaled.slower(), scaled.playPause(), scaled.faster(), scaled.speed(), scaled.vision())
            assertTrue(boxes.first().left() >= 8)
            assertTrue(boxes.last().left() + boxes.last().width() <= width - 8)
            assertEquals(40f + 30 * scaled.unit(), scaled.bottom(), .001f)
            for ((first, second) in boxes.zipWithNext()) {
                assertTrue(first.left() + first.width() < second.left())
                assertEquals(first.height(), second.height())
            }
        }
    }
}
