package io.github.rwx.settings

import io.github.rwx.PREFERENCE_NAME
import io.github.rwx.PreferenceStorage
import java.util.UUID

/** Identity for this installation, persisted before the engine or multiplayer starts. */
data class InstallationIdentity(val uuid: String, val networkClientId: String) {
    companion object {
        @JvmStatic
        fun loadOrCreate(storage: PreferenceStorage): InstallationIdentity = synchronized(storage) {
            val preferences = storage.preference(PREFERENCE_NAME)
            val savedUuid = preferences.getString("uuid", null)?.takeIf(String::isNotBlank)
            val savedClientId = preferences.getString("networkClientId", null)?.takeIf(String::isNotBlank)
            // Adopt the existing multiplayer identity when upgrading an older installation.
            val uuid = savedUuid ?: savedClientId ?: UUID.randomUUID().toString()
            val clientId = savedClientId ?: uuid
            if (savedUuid == null || savedClientId == null) {
                preferences.putString("uuid", uuid).putString("networkClientId", clientId)
                storage.flush()
            }
            InstallationIdentity(uuid, clientId)
        }
    }
}
