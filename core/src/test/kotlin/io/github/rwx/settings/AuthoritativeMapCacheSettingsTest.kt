package io.github.rwx.settings

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.PREFERENCE_NAME
import io.github.rwx.i18n.I18n
import io.github.rwx.ui.model.SettingsModel
import io.github.rwx.ui.model.SettingsPage
import io.github.rwx.ui.model.SettingsPageItem
import io.github.rwx.ui.model.SettingsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthoritativeMapCacheSettingsTest {
    @Test
    fun `new settings and empty preferences default to sixteen maps`() {
        val storage = MemoryPreferenceStorage()
        assertEquals(16f, SettingsModel().authoritativeMapCacheLimit.value)
        assertEquals(16f, SettingsModel().also(GameSettingsRepository(storage)::loadInto)
            .authoritativeMapCacheLimit.value)
        assertFalse(storage.preference(PREFERENCE_NAME).contains(AuthoritativeMapCacheSettings.KEY))
    }

    @Test
    fun `disabled and custom limits survive a fresh repository and model`() {
        val storage = MemoryPreferenceStorage()
        val repository = GameSettingsRepository(storage).apply { engineExecutor = { } }
        val model = SettingsModel()
        for (limit in listOf(0, 1, 37, 128, 256, 16)) {
            model.authoritativeMapCacheLimit.value = limit.toFloat()
            repository.saveFrom(model)
            model.authoritativeMapCacheLimit.value = 99f
            assertEquals(limit, storage.preference(PREFERENCE_NAME).getInt(AuthoritativeMapCacheSettings.KEY, -1))
            val restored = SettingsModel().also(GameSettingsRepository(storage)::loadInto)
            assertEquals(limit.toFloat(), restored.authoritativeMapCacheLimit.value)
        }
    }

    @Test
    fun `out of range and malformed stored preferences cannot escape the cache bounds`() {
        val storage = MemoryPreferenceStorage()
        val preferences = storage.preference(PREFERENCE_NAME)
        for ((stored, expected) in listOf(Int.MIN_VALUE to 0, -1 to 0, 0 to 0, 257 to 256, Int.MAX_VALUE to 256)) {
            preferences.putInt(AuthoritativeMapCacheSettings.KEY, stored)
            assertEquals(expected.toFloat(), SettingsModel().also(GameSettingsRepository(storage)::loadInto)
                .authoritativeMapCacheLimit.value)
        }
        for (malformed in listOf("NaN", "Infinity", "17.5", "not-a-number")) {
            preferences.putString(AuthoritativeMapCacheSettings.KEY, malformed)
            assertEquals(16f, SettingsModel().also(GameSettingsRepository(storage)::loadInto)
                .authoritativeMapCacheLimit.value)
        }
    }

    @Test
    fun `saving rounds fractional input clamps extremes and replaces nonfinite input`() {
        val storage = MemoryPreferenceStorage()
        val repository = GameSettingsRepository(storage).apply { engineExecutor = { } }
        val model = SettingsModel()
        val inputs = listOf(
            -Float.MAX_VALUE to 0, -0.25f to 0, 12.49f to 12, 12.5f to 13,
            255.9f to 256, Float.MAX_VALUE to 256,
            Float.NaN to 16, Float.POSITIVE_INFINITY to 16, Float.NEGATIVE_INFINITY to 16,
        )
        for ((input, expected) in inputs) {
            model.authoritativeMapCacheLimit.value = input
            repository.saveFrom(model)
            assertEquals(expected.toFloat(), model.authoritativeMapCacheLimit.value, "Normalized UI value for $input")
            assertEquals(expected, storage.preference(PREFERENCE_NAME).getInt(AuthoritativeMapCacheSettings.KEY, -1))
            assertEquals(expected.toFloat(), SettingsModel().also(GameSettingsRepository(storage)::loadInto)
                .authoritativeMapCacheLimit.value)
        }
    }

    @Test
    fun `preview normalizes the editable value without replacing the saved preference`() {
        val storage = MemoryPreferenceStorage()
        storage.preference(PREFERENCE_NAME).putInt(AuthoritativeMapCacheSettings.KEY, 31)
        val repository = GameSettingsRepository(storage).apply { engineExecutor = { } }
        val model = SettingsModel().also(repository::loadInto)
        for ((input, expected) in listOf(8.6f to 9f, -10f to 0f, 1000f to 256f, Float.NaN to 16f)) {
            model.authoritativeMapCacheLimit.value = input
            repository.applyLive(model)
            assertEquals(expected, model.authoritativeMapCacheLimit.value)
            assertEquals(31, storage.preference(PREFERENCE_NAME).getInt(AuthoritativeMapCacheSettings.KEY))
        }
    }

    @Test
    fun `desktop gameplay exposes the cache setting with integer steps and explicit disabled value`() {
        val previousNonAndroid = GameEngine.isNonAndroidVersion
        val previousPc = GameEngine.isPCOrIOSVersion
        val previousIos = GameEngine.isIOSVersion
        try {
            GameEngine.isNonAndroidVersion = true
            GameEngine.isPCOrIOSVersion = true
            GameEngine.isIOSVersion = false
            val model = SettingsModel()
            val page = SettingsViewModel(model).pageAt(SettingsPage.Gameplay.ordinal)
            val slider = page.items.filterIsInstance<SettingsPageItem.Slider>()
                .single { it.slider.state === model.authoritativeMapCacheLimit }.slider
            assertEquals(SettingsPage.Gameplay, page.page)
            assertSame(model.authoritativeMapCacheLimit, slider.state)
            assertSame(I18n.settings.gameplay.authoritativeMapCacheLimit, slider.i18nText)
            assertEquals(0f, slider.min)
            assertEquals(256f, slider.max)
            assertEquals(1f, slider.step)
            assertEquals(17f, slider.normalize(16.6f))
            assertEquals("0", slider.formatted(0f))
            assertEquals("256", slider.formatted(999f))
            assertTrue(slider.i18nText.translations.getValue("en").contains("0"))
            assertTrue(slider.i18nText.translations.getValue("zh-CN").contains("0"))

            GameEngine.isNonAndroidVersion = false
            GameEngine.isPCOrIOSVersion = false
            assertTrue(SettingsViewModel(model).pageAt(SettingsPage.Gameplay.ordinal).items
                .filterIsInstance<SettingsPageItem.Slider>()
                .none { it.slider.state === model.authoritativeMapCacheLimit })
        } finally {
            GameEngine.isNonAndroidVersion = previousNonAndroid
            GameEngine.isPCOrIOSVersion = previousPc
            GameEngine.isIOSVersion = previousIos
        }
    }
}
