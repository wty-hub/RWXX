package io.github.rwx

/** A snapshot of the Swing host, independent of Kool's canvas focus and mouse-over flags. */
internal data class DesktopWindowActivityState(
    val active: Boolean = true,
    val showing: Boolean = true,
    val iconified: Boolean = false,
    val refreshRate: Int = 0,
) {
    val isForeground: Boolean get() = active && showing && !iconified
}

/** The EDT publishes window events; render and engine threads only read the immutable snapshot. */
internal object DesktopWindowActivity {
    @Volatile
    var state = DesktopWindowActivityState()
        private set

    fun update(active: Boolean, showing: Boolean, iconified: Boolean, refreshRate: Int = 0) {
        state = DesktopWindowActivityState(active, showing, iconified, refreshRate)
    }
}

/** Background presentation remains live without redrawing at the foreground high-refresh rate. */
internal fun desktopPresentationFrameRate(
    targetFrameRate: Int,
    activity: DesktopWindowActivityState = DesktopWindowActivity.state,
): Int = if (activity.isForeground) targetFrameRate else minOf(targetFrameRate, 30)
