package com.corrodinggames.rts.gameFramework.ui

import kotlin.test.*

class ReplayTimelineControlsTest {
    @Test fun `drag captures outside track previews and seeks only on release`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(800f, 1f, 80f)
        val requests = mutableListOf<Int>()
        assertTrue(controls.handle(layout, true, layout.trackLeft(), 90f, 5000, 100_000, requests::add))
        assertEquals(0, controls.previewMillis)
        assertTrue(controls.handle(layout, true, 2000f, 500f, 5000, 100_000, requests::add))
        assertEquals(100_000, controls.previewMillis)
        assertTrue(requests.isEmpty())
        assertTrue(controls.handle(layout, false, 2000f, 500f, 5000, 100_000, requests::add))
        assertEquals(listOf(100_000), requests)
        assertFalse(controls.isCaptured)
    }

    @Test fun `buttons clamp and missing duration does not seek`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(320f, 2f, 80f)
        val requests = mutableListOf<Int>()
        val buttonY = (layout.buttonTop() + layout.buttonBottom()) / 2
        for ((x, current, expected) in listOf(Triple(layout.buttonLeft(-1) + 1, 3000, 0),
                Triple(layout.buttonLeft(1) + 1, 98_000, 100_000))) {
            controls.handle(layout, true, x, buttonY, current, 100_000, requests::add)
            controls.handle(layout, false, x, buttonY, current, 100_000, requests::add)
            assertEquals(expected, requests.last())
        }
        controls.handle(layout, true, layout.trackLeft(), 90f, 0, -1, requests::add)
        controls.handle(layout, false, layout.trackLeft(), 90f, 0, -1, requests::add)
        assertEquals(2, requests.size)
        assertTrue(layout.left() >= 0)
        assertTrue(layout.trackRight() > layout.trackLeft())
    }

    @Test fun `timeline endpoints seek while labels and button gap stay inert`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(800f, 1f, 80f)
        val requests = mutableListOf<Int>()
        for (x in listOf(layout.trackLeft(), layout.trackRight())) {
            controls.handle(layout, true, x, layout.centerY(), 50_000, 100_000, requests::add)
            controls.handle(layout, false, x, layout.centerY(), 50_000, 100_000, requests::add)
        }
        assertEquals(listOf(0, 100_000), requests)
        val center = (layout.buttonLeft(-1) + layout.buttonWidth() + layout.buttonLeft(1)) / 2
        for (y in listOf(layout.top() + 47 * layout.unit(), layout.buttonTop() + 5)) {
            controls.handle(layout, true, center, y, 50_000, 100_000, requests::add)
            assertEquals(-1, controls.previewMillis)
            controls.handle(layout, false, center, y, 50_000, 100_000, requests::add)
        }
        // Releasing a step button over the track cancels it instead of seeking.
        controls.handle(layout, true, layout.buttonLeft(1) + 1, layout.buttonTop() + 1,
            50_000, 100_000, requests::add)
        controls.handle(layout, false, layout.trackRight(), layout.centerY(),
            50_000, 100_000, requests::add)
        assertEquals(listOf(0, 100_000), requests)
        assertTrue(layout.buttonTop() > layout.centerY())
    }

    @Test fun `duration unavailable does not create a seek request`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(800f, 1f, 80f)
        val requests = mutableListOf<Int>()
        controls.handle(layout, true, layout.trackLeft(), 90f, 5000, -1, requests::add)
        controls.handle(layout, false, layout.trackLeft(), 90f, 5000, -1, requests::add)
        assertTrue(requests.isEmpty())
    }

    @Test fun `press beginning on map is never converted into timeline drag`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(1280f, 1f, 80f)
        assertFalse(controls.handle(layout, true, 0f, 500f, 0, 1000) { fail("unexpected seek") })
        assertFalse(controls.handle(layout, true, layout.trackLeft(), 90f, 0, 1000) { fail("unexpected seek") })
        assertFalse(controls.handle(layout, false, layout.trackLeft(), 90f, 0, 1000) { fail("unexpected seek") })
    }

    private fun toggle(controls: ReplayTimelineControls, layout: ReplayTimelineControls.Layout, duration: Int = 100_000) {
        val collapsed = controls.isCollapsed
        val x = layout.toggleLeft(collapsed) + layout.toggleWidth(collapsed) / 2
        val y = (layout.toggleTop(collapsed) + layout.toggleBottom(collapsed)) / 2
        assertTrue(controls.handle(layout, true, x, y, 5000, duration) { fail("toggle must not seek") })
        assertEquals(collapsed, controls.isCollapsed, "Toggle only on release")
        assertTrue(controls.handle(layout, false, x, y, 5000, duration) { fail("toggle must not seek") })
        assertEquals(!collapsed, controls.isCollapsed)
        assertEquals(-1, controls.previewMillis)
    }

    @Test fun `collapsed panel releases map area and expands without seeking`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(800f, 1f, 80f)
        toggle(controls, layout)
        assertFalse(controls.contains(layout, layout.trackLeft(), layout.centerY()))
        assertFalse(controls.contains(layout, layout.buttonLeft(-1) + 1, layout.buttonTop() + 1))
        assertFalse(controls.handle(layout, true, layout.trackRight(), layout.centerY(), 5000, 100_000) { fail("hidden track") })
        assertFalse(controls.handle(layout, false, layout.trackRight(), layout.centerY(), 5000, 100_000) { fail("hidden track") })
        toggle(controls, layout)
        assertTrue(controls.contains(layout, layout.trackLeft(), layout.centerY()))
    }

    @Test fun `toggle supports unavailable duration and input reset preserves collapsed state`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(800f, 1f, 80f)
        assertFalse(controls.contains(layout, layout.trackLeft(), layout.centerY(), false))
        toggle(controls, layout, -1)
        controls.resetInput()
        assertTrue(controls.isCollapsed)
        toggle(controls, layout, -1)
        toggle(controls, layout)
        controls.reset()
        assertFalse(controls.isCollapsed, "A new replay starts expanded")
    }

    @Test fun `dragging away cancels toggle and map drag cannot expand it`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(800f, 1f, 80f)
        val x = layout.toggleLeft(false) + 1
        val y = layout.toggleTop(false) + 1
        assertTrue(controls.handle(layout, true, x, y, 0, 1000) { fail("unexpected seek") })
        assertTrue(controls.handle(layout, false, 0f, 500f, 0, 1000) { fail("unexpected seek") })
        assertFalse(controls.isCollapsed)
        toggle(controls, layout)
        assertFalse(controls.handle(layout, true, 0f, 500f, 0, 1000) { fail("unexpected seek") })
        assertFalse(controls.handle(layout, true, layout.toggleLeft(true) + 1, layout.toggleTop(true) + 1,
            0, 1000) { fail("unexpected seek") })
        controls.handle(layout, false, layout.toggleLeft(true) + 1, layout.toggleTop(true) + 1,
            0, 1000) { fail("unexpected seek") }
        assertTrue(controls.isCollapsed)
    }

    @Test fun `step and collapse buttons fit narrow layouts without overlap`() {
        for ((width, scale) in listOf(320f to 2f, 800f to 1f, 1920f to 2f)) {
            val layout = ReplayTimelineControls.layout(width, scale, 80f)
            assertTrue(layout.buttonLeft(-1) >= layout.left())
            assertTrue(layout.buttonLeft(-1) + layout.buttonWidth() < layout.buttonLeft(1))
            assertTrue(layout.buttonLeft(1) + layout.buttonWidth() < layout.toggleLeft(false))
            assertTrue(layout.toggleLeft(false) + layout.toggleWidth(false) <= layout.left() + layout.width())
        }
    }
}
