package io.github.rwx.settings

import io.github.rwx.PREFERENCE_NAME
import io.github.rwx.ui.model.SettingsModel
import io.github.rwx.ui.model.SettingsPage
import io.github.rwx.ui.model.SettingsPageItem
import io.github.rwx.ui.model.SettingsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UnitGroupSettingsTest {
    @Test
    fun `shift number updates groups by default`() {
        val repository = GameSettingsRepository(MemoryPreferenceStorage())
        assertTrue(SettingsModel().shiftDigitUpdatesGroup.value)
        assertTrue(SettingsModel().also(repository::loadInto).shiftDigitUpdatesGroup.value)
    }

    @Test
    fun `both shift number behaviors survive saving and reloading`() {
        val storage = MemoryPreferenceStorage()
        val repository = GameSettingsRepository(storage)
        repository.engineExecutor = { }
        val model = SettingsModel()
        for (updatesGroup in listOf(false, true)) {
            model.shiftDigitUpdatesGroup.value = updatesGroup
            repository.saveFrom(model)
            assertEquals(updatesGroup, storage.preference(PREFERENCE_NAME).getBoolean("shiftDigitUpdatesGroup", !updatesGroup))
            assertEquals(updatesGroup, SettingsModel().also(repository::loadInto).shiftDigitUpdatesGroup.value)
        }
    }

    @Test
    fun `gameplay settings expose the shift number toggle`() {
        val model = SettingsModel()
        val page = SettingsViewModel(model).pageAt(SettingsPage.Gameplay.ordinal)
        val toggle = page.items.filterIsInstance<SettingsPageItem.Toggle>()
            .single { it.toggle.state === model.shiftDigitUpdatesGroup }
        assertSame(model.shiftDigitUpdatesGroup, toggle.toggle.state)
    }
}
