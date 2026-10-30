package com.corrodinggames.rts.gameFramework.ui

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.InputController
import com.corrodinggames.rts.gameFramework.InputHandler
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.settings.MemoryPreferenceStorage
import io.github.rwx.ui.model.SettingsModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnitGroupHotkeyTest {
    @Test
    fun `shift number merges selection and updates each of the ten groups without duplicates`() = withGroups { engine, renderer ->
        for (index in 0..9) {
            engine.gameUI.clearSelection()
            val original = unit()
            val selected = unit().also { engine.gameUI.selectUnit(it) }
            val shared = unit().also { engine.gameUI.selectUnit(it) }
            val group = group(renderer, index).also { it.units.addAll(listOf(original, shared)) }

            press(engine, renderer, index, SHIFT)

            assertEquals(setOf(original, selected, shared), group.units.toSet())
            assertEquals(3, group.units.size)
            assertTrue(original.isSelected)
            assertTrue(selected.isSelected)
            assertTrue(shared.isSelected)
            assertEquals(3, engine.gameUI.selectedUnitCount)
            assertTrue(group(renderer, (index + 1) % 10).units.none { it === selected })
        }
    }

    @Test
    fun `shift number creates an empty group from the current selection`() = withGroups { engine, renderer ->
        val selected = unit().also { engine.gameUI.selectUnit(it) }
        press(engine, renderer, 0, SHIFT)
        assertEquals(listOf(selected), group(renderer, 0).units)
        assertTrue(selected.isSelected)
    }

    @Test
    fun `disabling group updates retains additive selection only`() = withGroups { engine, renderer ->
        engine.settingsEngine.shiftDigitUpdatesGroup = false
        val original = unit()
        val selected = unit().also { engine.gameUI.selectUnit(it) }
        group(renderer, 0).units.add(original)
        press(engine, renderer, 0, SHIFT)
        assertEquals(listOf(original), group(renderer, 0).units)
        assertTrue(original.isSelected)
        assertTrue(selected.isSelected)
    }

    @Test
    fun `plain number recalls and ctrl number replaces the group in either mode`() = withGroups { engine, renderer ->
        for (updatesGroup in listOf(false, true)) {
            engine.settingsEngine.shiftDigitUpdatesGroup = updatesGroup
            engine.gameUI.clearSelection()
            val original = unit()
            val selected = unit().also { engine.gameUI.selectUnit(it) }
            val group = group(renderer, 0)
            group.units.clear()
            group.units.add(original)

            press(engine, renderer, 0, 0)
            assertTrue(original.isSelected)
            assertFalse(selected.isSelected)

            engine.gameUI.clearSelection()
            engine.gameUI.selectUnit(selected)
            press(engine, renderer, 0, CTRL)
            assertEquals(listOf(selected), group.units)
        }
    }

    @Test
    fun `queued settings apply the saved behavior even if the UI value changes`() = withGroups { engine, _ ->
        val repository = GameSettingsRepository(MemoryPreferenceStorage())
        val queued = ArrayDeque<() -> Unit>()
        repository.engineExecutor = { queued.addLast(it) }
        val model = SettingsModel()
        for (updatesGroup in listOf(false, true)) {
            model.shiftDigitUpdatesGroup.value = updatesGroup
            repository.saveFrom(model)
            model.shiftDigitUpdatesGroup.value = !updatesGroup
            queued.removeFirst().invoke()
            assertEquals(updatesGroup, engine.settingsEngine.shiftDigitUpdatesGroup)
            assertEquals(updatesGroup, SettingsModel().also(repository::loadInto).shiftDigitUpdatesGroup.value)
        }
    }

    private fun group(renderer: GameInterfaceRenderer, index: Int) = renderer.unitGroupMarkers[index] as UnitGroupMarker

    private fun unit(): BaseUnit = BuilderUnit(true).also {
        it.team = PlayerTeam.TEAM_ALL
        GameObject.fastGameObjectList.add(it)
    }

    private fun press(engine: GameLogic, renderer: GameInterfaceRenderer, index: Int, modifiers: Int) {
        val key = engine.inputController.a(if (index == 9) 0 else index + 1)
        InputController.b = object : InputHandler() {
            override fun a(keyCode: Int, modifierFlags: Int, allowExtraModifiers: Boolean): Boolean =
                keyCode == key && if (allowExtraModifiers) modifiers and modifierFlags == modifierFlags else modifiers == modifierFlags
        }
        renderer.e(0f)
        InputController.b = object : InputHandler() {
            override fun a(keyCode: Int, modifierFlags: Int, allowExtraModifiers: Boolean) = false
        }
        renderer.e(0f)
    }

    private fun withGroups(block: (GameLogic, GameInterfaceRenderer) -> Unit) {
        val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instanceField.get(null)
        val previousInput = InputController.b
        val previousObjects = GameObject.fastGameObjectList.toList()
        instanceField.set(null, null)
        GameObject.fastGameObjectList.clear()
        try {
            val engine = GameLogic()
            engine.playerTeam = PlayerTeam.TEAM_ALL
            engine.replayEngine = ReplayEngine()
            engine.settingsEngine = SettingsEngine::class.java.getDeclaredConstructor().run {
                isAccessible = true
                newInstance()
            }
            engine.settingsEngine.showUnitGroups = false
            engine.settingsEngine.keyboardSupport = true
            engine.inputController = InputController()
            engine.gameUI = GameUI()
            val renderer = object : GameInterfaceRenderer(engine, engine.gameUI) {
                override fun initializeUI() = Unit
            }
            repeat(10) { renderer.unitGroupMarkers.add(UnitGroupMarker(renderer, true)) }
            block(engine, renderer)
        } finally {
            GameObject.fastGameObjectList.clear()
            previousObjects.forEach { GameObject.fastGameObjectList.add(it) }
            InputController.b = previousInput
            instanceField.set(null, previousEngine)
        }
    }

    companion object {
        private const val CTRL = 1
        private const val SHIFT = 2
    }
}
