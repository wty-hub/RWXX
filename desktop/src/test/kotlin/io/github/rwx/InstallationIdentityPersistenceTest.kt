package io.github.rwx

import io.github.rwx.settings.InstallationIdentity
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallationIdentityPersistenceTest {
    @Test
    fun `identity is on disk before engine startup and survives reopening preferences`() = withLocation { location ->
        val identity = InstallationIdentity.loadOrCreate(TomlPreferenceStorage(location))

        assertTrue(location.file.readText().isNotBlank())
        // Open another storage immediately, without an engine save or shutdown flush.
        val persisted = TomlPreferenceStorage(location).preference(PREFERENCE_NAME)
        assertEquals(identity.uuid, persisted.getString("uuid", null))
        assertEquals(identity.networkClientId, persisted.getString("networkClientId", null))
        repeat(3) {
            assertEquals(identity, InstallationIdentity.loadOrCreate(TomlPreferenceStorage(location)))
        }
    }

    @Test
    fun `legacy multiplayer identity is adopted without replacing it or unrelated settings`() = withLocation { location ->
        val legacyClientId = "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"
        TomlPreferenceStorage(location).apply {
            preference(PREFERENCE_NAME)
                .putString("networkClientId", legacyClientId)
                .putString("lastNetworkPlayerName", "Saved player")
                .putFloat("masterVolume", 0.25f)
            preference("other").putString("savedValue", "keep me")
            flush()
        }

        val identity = InstallationIdentity.loadOrCreate(TomlPreferenceStorage(location))

        assertEquals(legacyClientId, identity.uuid)
        assertEquals(legacyClientId, identity.networkClientId)
        val reopened = TomlPreferenceStorage(location)
        assertEquals(identity, InstallationIdentity.loadOrCreate(reopened))
        with(reopened.preference(PREFERENCE_NAME)) {
            assertEquals(legacyClientId, getString("uuid", null))
            assertEquals(legacyClientId, getString("networkClientId", null))
            assertEquals("Saved player", getString("lastNetworkPlayerName", null))
            assertEquals(0.25f, getFloat("masterVolume", 1f))
        }
        assertEquals("keep me", reopened.preference("other").getString("savedValue", null))
    }

    private fun withLocation(block: (StorageLocation) -> Unit) {
        val directory = createTempDirectory("rwx-installation-identity-").toFile()
        try {
            block(StorageLocation("/SD/rustedWarfare/preferences.toml", File(directory, "preferences.toml")))
        } finally {
            directory.deleteRecursively()
        }
    }
}
