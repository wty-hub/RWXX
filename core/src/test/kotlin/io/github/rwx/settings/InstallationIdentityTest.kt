package io.github.rwx.settings

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.Utility
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.PREFERENCE_NAME
import io.github.rwx.Preference
import io.github.rwx.PreferenceStorage
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InstallationIdentityTest {
    @Test
    fun `identity is durable before starting a game or joining multiplayer`() {
        val storage = DurablePreferenceStorage()

        val identity = InstallationIdentity.loadOrCreate(storage)

        assertEquals(identity.uuid, UUID.fromString(identity.uuid).toString())
        assertEquals(identity.uuid, identity.networkClientId)
        assertEquals(1, storage.flushCount)
        // Reopen only the durable snapshot: no normal-exit settings save is performed.
        val reopened = storage.reopen()
        assertEquals(identity.uuid, reopened.preference(PREFERENCE_NAME).getString("uuid", null))
        assertEquals(identity.networkClientId, reopened.preference(PREFERENCE_NAME).getString("networkClientId", null))
        assertEquals(identity, InstallationIdentity.loadOrCreate(reopened))
        assertEquals(0, reopened.flushCount, "opening an existing installation should not rewrite its identity")
    }

    @Test
    fun `upgrading preserves both existing identity values and other settings`() {
        val storage = DurablePreferenceStorage(
            mapOf("uuid" to "legacy-installation", "networkClientId" to "legacy-client", "networkServerId" to "room-id"),
        )

        assertEquals(InstallationIdentity("legacy-installation", "legacy-client"), InstallationIdentity.loadOrCreate(storage))
        assertEquals(0, storage.flushCount)
        assertEquals("room-id", storage.preference(PREFERENCE_NAME).getString("networkServerId", null))
    }

    @Test
    fun `an older client identity is adopted when installation uuid is missing`() {
        val storage = DurablePreferenceStorage(mapOf("networkClientId" to "existing-client"))

        val identity = InstallationIdentity.loadOrCreate(storage)

        assertEquals(InstallationIdentity("existing-client", "existing-client"), identity)
        assertEquals(identity, InstallationIdentity.loadOrCreate(storage.reopen()))
    }

    @Test
    fun `missing multiplayer identity uses the saved installation uuid`() {
        val storage = DurablePreferenceStorage(mapOf("uuid" to "existing-installation"))

        val identity = InstallationIdentity.loadOrCreate(storage)

        assertEquals(InstallationIdentity("existing-installation", "existing-installation"), identity)
        assertEquals(identity, InstallationIdentity.loadOrCreate(storage.reopen()))
    }

    @Test
    fun `blank identity values are repaired once and persisted`() {
        for ((uuid, clientId) in listOf("" to " ", "\t" to "saved-client", "saved-installation" to "")) {
            val storage = DurablePreferenceStorage(mapOf("uuid" to uuid, "networkClientId" to clientId))

            val identity = InstallationIdentity.loadOrCreate(storage)

            val expected = uuid.takeIf(String::isNotBlank) ?: clientId.takeIf(String::isNotBlank)
            if (expected != null) assertEquals(expected, identity.uuid)
            else assertEquals(identity.uuid, UUID.fromString(identity.uuid).toString())
            assertEquals(identity.uuid, identity.networkClientId)
            assertEquals(1, storage.flushCount)
            assertEquals(identity, InstallationIdentity.loadOrCreate(storage.reopen()))
        }
    }

    @Test
    fun `separate installations receive distinct identities`() {
        val first = InstallationIdentity.loadOrCreate(DurablePreferenceStorage())
        val second = InstallationIdentity.loadOrCreate(DurablePreferenceStorage())

        assertNotEquals(first.uuid, second.uuid)
        assertNotEquals(first.networkClientId, second.networkClientId)
    }

    @Test
    fun `reopening the desktop engine keeps the saved client id and server hash`() {
        val storage = DurablePreferenceStorage()
        val identity = InstallationIdentity.loadOrCreate(storage)
        val firstHash = desktopClientHash(storage, "same-server", identity)

        val reopened = storage.reopen()
        assertEquals(identity, InstallationIdentity.loadOrCreate(reopened))
        val reopenedHash = desktopClientHash(reopened, "same-server", identity)

        assertEquals(Utility.sha256Hex(identity.networkClientId + "same-server"), firstHash)
        assertEquals(firstHash, reopenedHash)
        assertEquals(identity, InstallationIdentity.loadOrCreate(reopened.reopen()))
    }

    @Test
    fun `different servers retain separate hashes without replacing installation identity`() {
        val storage = DurablePreferenceStorage()
        val identity = InstallationIdentity.loadOrCreate(storage)

        val firstHash = desktopClientHash(storage, "first-server", identity)
        val secondHash = desktopClientHash(storage, "second-server", identity)

        assertNotEquals(firstHash, secondHash)
        assertEquals(identity, InstallationIdentity.loadOrCreate(storage.reopen()))
    }

    private fun desktopClientHash(storage: PreferenceStorage, serverId: String, identity: InstallationIdentity): String {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousDesktop = GameEngine.isPCOrIOSVersion
        val previousIos = GameEngine.isIOSVersion
        startKoin { modules(module { single<PreferenceStorage> { storage } }) }
        try {
            instance.set(null, null)
            GameEngine.isPCOrIOSVersion = true
            GameEngine.isIOSVersion = false
            val engine = GameLogic()
            engine.settingsEngine = SettingsEngine::class.java.getDeclaredConstructor().run {
                isAccessible = true
                newInstance()
            }
            val network = NetworkEngine().apply { serverUuid = serverId }

            val hash = network.ownClientIdHashed

            assertEquals(identity.uuid, engine.settingsEngine.uuid)
            assertEquals(identity.networkClientId, engine.settingsEngine.networkClientId)
            return hash
        } finally {
            instance.set(null, previousEngine)
            GameEngine.isPCOrIOSVersion = previousDesktop
            GameEngine.isIOSVersion = previousIos
            stopKoin()
        }
    }

    /** Writes survive a simulated process restart only after flush, like the platform stores. */
    private class DurablePreferenceStorage(initial: Map<String, String> = emptyMap()) : PreferenceStorage {
        private val durable = initial.toMutableMap()
        private val values = initial.toMutableMap()
        var flushCount = 0
            private set

        fun reopen() = DurablePreferenceStorage(durable)

        override fun flush() {
            durable.clear()
            durable.putAll(values)
            flushCount++
        }

        override fun preference(name: String): Preference {
            require(name == PREFERENCE_NAME)
            return object : Preference {
                override fun getBoolean(key: String, defValue: Boolean) = values[key]?.toBooleanStrictOrNull() ?: defValue
                override fun getInt(key: String, defValue: Int) = values[key]?.toIntOrNull() ?: defValue
                override fun getLong(key: String, defValue: Long) = values[key]?.toLongOrNull() ?: defValue
                override fun getFloat(key: String, defValue: Float) = values[key]?.toFloatOrNull() ?: defValue
                override fun getString(key: String, defValue: String?) = values[key] ?: defValue
                override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value.toString() }
                override fun putInt(key: String, value: Int) = apply { values[key] = value.toString() }
                override fun putLong(key: String, value: Long) = apply { values[key] = value.toString() }
                override fun putFloat(key: String, value: Float) = apply { values[key] = value.toString() }
                override fun putString(key: String, value: String?) = apply {
                    if (value == null) values.remove(key) else values[key] = value
                }
                override fun contains(key: String) = key in values
                override fun remove(key: String) = values.remove(key)
                override fun clear() = values.clear()
            }
        }
    }
}
