package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWindowActivityTest {
    @Test
    fun `startup remains foreground until the host publishes its state`() {
        val activity = DesktopWindowActivityState()
        assertTrue(activity.isForeground)
        assertEquals(144, desktopPresentationFrameRate(144, activity))
    }

    @Test
    fun `inactive hidden and minimized windows share a thirty frame ceiling`() {
        for (activity in listOf(
            DesktopWindowActivityState(active = false),
            DesktopWindowActivityState(showing = false),
            DesktopWindowActivityState(iconified = true),
            DesktopWindowActivityState(active = false, showing = false, iconified = true),
        )) {
            assertEquals(30, desktopPresentationFrameRate(300, activity))
            assertEquals(30, desktopPresentationFrameRate(60, activity))
            assertEquals(20, desktopPresentationFrameRate(20, activity))
        }
    }

    @Test
    fun `reactivating a visible restored window restores its configured frame rate`() {
        val background = DesktopWindowActivityState(active = false, iconified = true)
        assertEquals(30, desktopPresentationFrameRate(120, background))
        assertEquals(30, desktopPresentationFrameRate(120, background.copy(active = true)))
        assertEquals(120, desktopPresentationFrameRate(120, background.copy(active = true, iconified = false)))
    }
}
