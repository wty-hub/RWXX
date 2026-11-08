package io.github.rwx

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class DesktopTimelineStorageTest {
    @Test fun `mac uses a separate timeline application support directory`() {
        val home = File("/test-user")
        val actual = local("Mac OS X", home)
        assertEquals(File(home, "Library/Application Support/rwx-timeline"), actual)
        assertNotEquals(File(home, "Library/Application Support/rwx"), actual)
    }

    @Test fun `windows keeps its own directory under appdata or the home fallback`() {
        val home = File("/test-user")
        val roaming = File(home, "AppData/Roaming")
        assertEquals(File(roaming, "rwx-timeline"), local("Windows 11", home, appData = roaming.path))
        assertEquals(File(home, "rwx-timeline"), local("Windows 11", home))
        assertNotEquals(File(roaming, "rwx"), local("Windows 11", home, appData = roaming.path))
    }

    @Test fun `linux keeps its own directory under xdg data or the home fallback`() {
        val home = File("/test-user")
        val xdg = File(home, "custom-data")
        assertEquals(File(xdg, "rwx-timeline"), local("Linux", home, xdgDataHome = xdg.path))
        assertEquals(File(home, ".local/share/rwx-timeline"), local("Linux", home))
        assertNotEquals(File(home, ".local/share/rwx"), local("Linux", home))
    }

    @Test fun `mac default preferences do not adopt existing 1x user settings`() {
        val directory = createTempDirectory("timeline-independent-preferences-").toFile()
        try {
            val legacy = File(directory, "Library/Application Support/rwx/preferences.toml")
            legacy.parentFile.mkdirs()
            legacy.writeText("legacy-settings-must-stay")
            val root = File(directory, "RWXX-Timeline.app")
            val timelineLocal = local("Mac OS X", directory)
            val actual = DesktopPreferences.resolveFile(root, timelineLocal, "Mac OS X", null)
            assertEquals(File(timelineLocal, "preferences.toml"), actual)
            assertFalse(actual.exists())
            assertEquals("legacy-settings-must-stay", legacy.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `explicit portable root takes precedence over the packaged application root`() {
        withRootOverrides(launch = "/isolated-portable", packaged = "/Applications/RWXX-Timeline.app") {
            assertEquals(File("/isolated-portable"), DesktopPlatformStorage.defaultRoot())
            assertEquals(File("/isolated-portable/preferences.toml"), DesktopPreferences.resolveFile(
                DesktopPlatformStorage.defaultRoot(), File("/separate-user-data/rwx-timeline"), "Mac OS X", "/isolated-portable"))
        }
    }

    @Test fun `packaged root still applies when no portable override was specified`() {
        withRootOverrides(launch = null, packaged = "/Applications/RWXX-Timeline.app") {
            assertEquals(File("/Applications/RWXX-Timeline.app"), DesktopPlatformStorage.defaultRoot())
        }
    }

    private fun local(os: String, home: File, appData: String? = null, xdgDataHome: String? = null): File =
        DesktopPlatformStorage.defaultLocalBaseDir(os, home.path, appData, xdgDataHome)

    private fun withRootOverrides(launch: String?, packaged: String?, block: () -> Unit) {
        val oldLaunch = System.getProperty("launch.dir")
        val oldPackaged = System.getProperty("rwx.appDir")
        fun set(name: String, value: String?) {
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
        try {
            set("launch.dir", launch)
            set("rwx.appDir", packaged)
            block()
        } finally {
            set("launch.dir", oldLaunch)
            set("rwx.appDir", oldPackaged)
        }
    }
}
