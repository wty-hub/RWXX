package io.github.rwx

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPreferencesTest {
    @Test
    fun `personal settings survive deleting the old app and launching an updated app`() = withDirectory { directory ->
        val userData = File(directory, "home/Library/Application Support/rwx")
        val oldApp = File(directory, "previous/RWXX.app").apply { mkdirs() }
        val oldPreferences = resolveMacFile(oldApp, userData)
        writePersonalSettings(oldPreferences)

        assertTrue(oldApp.deleteRecursively())
        val updatedApp = File(directory, "updated/RWXX.app").apply { mkdirs() }
        val updatedPreferences = resolveMacFile(updatedApp, userData)

        assertEquals(File(userData, "preferences.toml"), updatedPreferences)
        assertPersonalSettings(preferences(updatedPreferences))
        assertFalse(File(updatedApp, "preferences.toml").exists())
    }

    @Test
    fun `first mac launch migrates the complete legacy file without modifying the source`() = withDirectory { directory ->
        val appRoot = File(directory, "RWXX.app")
        val legacyFile = File(appRoot, "preferences.toml")
        writePersonalSettings(legacyFile)
        val originalBytes = legacyFile.readBytes()
        val userData = File(directory, "home/Library/Application Support/rwx")

        val migratedFile = resolveMacFile(appRoot, userData)

        assertEquals(File(userData, "preferences.toml"), migratedFile)
        assertContentEquals(originalBytes, migratedFile.readBytes())
        assertContentEquals(originalBytes, legacyFile.readBytes())
        assertPersonalSettings(preferences(migratedFile))
    }

    @Test
    fun `existing user preferences take precedence over stale preferences in any app bundle`() = withDirectory { directory ->
        val userData = File(directory, "home/Library/Application Support/rwx")
        val userFile = File(userData, "preferences.toml")
        writePersonalSettings(userFile)
        val savedBytes = userFile.readBytes()
        val appRoot = File(directory, "RWXX.app")
        preferences(File(appRoot, "preferences.toml")).apply {
            preference(PREFERENCE_NAME).putString("lastNetworkPlayerName", "Stale bundled name")
            flush()
        }

        val resolved = resolveMacFile(appRoot, userData)

        assertContentEquals(savedBytes, resolved.readBytes())
        assertPersonalSettings(preferences(resolved))
    }

    @Test
    fun `fresh mac installation selects user preferences without creating an empty file`() = withDirectory { directory ->
        val appRoot = File(directory, "RWXX.app")
        val userData = File(directory, "home/Library/Application Support/rwx")

        val resolved = resolveMacFile(appRoot, userData)

        assertEquals(File(userData, "preferences.toml"), resolved)
        assertFalse(resolved.exists())
        assertFalse(File(appRoot, "preferences.toml").exists())
    }

    @Test
    fun `explicit launch directory keeps mac preferences isolated from personal settings`() = withDirectory { directory ->
        val launchRoot = File(directory, "isolated-run")
        val isolatedFile = File(launchRoot, "preferences.toml")
        writePersonalSettings(isolatedFile)
        val userData = File(directory, "home/Library/Application Support/rwx")
        val userFile = File(userData, "preferences.toml")
        preferences(userFile).apply {
            preference(PREFERENCE_NAME).putString("lastNetworkPlayerName", "Personal name")
            flush()
        }
        val userBytes = userFile.readBytes()

        val resolved = DesktopPreferences.resolveFile(launchRoot, userData, "Mac OS X", launchRoot.path)

        assertEquals(isolatedFile, resolved)
        assertPersonalSettings(preferences(resolved))
        assertContentEquals(userBytes, userFile.readBytes())
    }

    @Test
    fun `windows and linux keep preferences in the application root`() = withDirectory { directory ->
        for (osName in listOf("Windows 11", "Linux")) {
            val appRoot = File(directory, osName)
            val legacyFile = File(appRoot, "preferences.toml")
            writePersonalSettings(legacyFile)
            val userData = File(directory, "$osName-user-data")

            val resolved = DesktopPreferences.resolveFile(appRoot, userData, osName, null)

            assertEquals(legacyFile, resolved)
            assertPersonalSettings(preferences(resolved))
            assertFalse(File(userData, "preferences.toml").exists())
        }
    }

    @Test
    fun `migration failure preserves legacy preferences and does not silently fall back`() = withDirectory { directory ->
        val appRoot = File(directory, "RWXX.app")
        val legacyFile = File(appRoot, "preferences.toml")
        writePersonalSettings(legacyFile)
        val originalBytes = legacyFile.readBytes()
        val userData = File(directory, "blocked-user-data").apply { writeText("not a directory") }

        assertFailsWith<IOException> { resolveMacFile(appRoot, userData) }

        assertContentEquals(originalBytes, legacyFile.readBytes())
        assertEquals("not a directory", userData.readText())
    }

    private fun resolveMacFile(rootDir: File, userDataDir: File): File =
        DesktopPreferences.resolveFile(rootDir, userDataDir, "Mac OS X", null)

    private fun preferences(file: File): TomlPreferenceStorage =
        TomlPreferenceStorage(StorageLocation("/LOCAL/preferences.toml", file))

    private fun writePersonalSettings(file: File) {
        preferences(file).apply {
            preference(PREFERENCE_NAME)
                .putString("lastNetworkPlayerName", "Saved player 玩家")
                .putFloat("masterVolume", 0.25f)
                .putString("key.attack", "CTRL+A")
                .putString("rwxColorSchemeId", "saved-theme")
            preference("other").putString("customSetting", "keep this value")
            flush()
        }
    }

    private fun assertPersonalSettings(storage: TomlPreferenceStorage) {
        with(storage.preference(PREFERENCE_NAME)) {
            assertEquals("Saved player 玩家", getString("lastNetworkPlayerName", null))
            assertEquals(0.25f, getFloat("masterVolume", 1f))
            assertEquals("CTRL+A", getString("key.attack", null))
            assertEquals("saved-theme", getString("rwxColorSchemeId", null))
        }
        assertEquals("keep this value", storage.preference("other").getString("customSetting", null))
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = createTempDirectory("rwx-desktop-preferences-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
