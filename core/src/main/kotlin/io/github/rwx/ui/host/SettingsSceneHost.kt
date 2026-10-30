package io.github.rwx.ui.host

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.KeyBinding
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.file.FileHelper
import de.fabmax.kool.KoolContext
import de.fabmax.kool.input.InputStack
import de.fabmax.kool.input.KeyEvent
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.scene.Scene
import io.github.rwx.PlatformBridge
import io.github.rwx.input.KoolKeyCodeMapping
import io.github.rwx.i18n.I18n
import io.github.rwx.session.GameSession
import io.github.rwx.ui.ResponsiveContentWidth
import io.github.rwx.ui.ResponsiveViewportHeight
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.component.*
import io.github.rwx.ui.model.*
import io.github.rwx.ui.remainingAfter
import org.koin.mp.KoinPlatform.getKoin
import com.corrodinggames.rts.gameFramework.InputController as LegacyInputController

class SettingsSceneHost(
    val model: SettingsModel = SettingsModel(),
    private val onAction: (SettingsAction) -> Unit = {},
) {
    private val viewModel = SettingsViewModel(model)
    private val currentPage: MutableStateValue<Int> = mutableStateOf(0)
    private val keyBindingRevision: MutableStateValue<Int> = mutableStateOf(0)
    private val activeKeyCapture: MutableStateValue<KeyBindingCaptureTarget?> = mutableStateOf(null)
    private var engineSession: GameSession? = null
    private val fallbackInputController: LegacyInputController = LegacyInputController().also { controller ->
        controller.a()
        SettingsEngine.getInstance().loadKeyBindingsInto(controller)
    }

    init {
        InputStack.defaultInputHandler.keyboardListeners += InputStack.KeyboardListener(::handleKeyBindingCapture)
    }

    fun dispatch(action: SettingsAction) = onAction(action)

    fun configureEngineOwnership(session: GameSession) {
        engineSession = session
    }

    private fun <T> requestEngineTask(action: () -> T, onComplete: (Result<T>) -> Unit = {}) {
        val session = engineSession
        if (session == null) onComplete(runCatching(action)) else session.requestSessionTask(action, onComplete)
    }

    fun showPage(page: SettingsPage) {
        currentPage.value = page.ordinal
    }

    fun createScene(): Scene = UiScene(SETTINGS_SCENE_NAME) {
        addPanelSurface(PanelStyle.Menu, "settings-panel", model) { theme ->
            val metrics = settingsLayoutMetrics()

            val pageIndex = currentPage.use()
            val pageContent = viewModel.pageAt(pageIndex)
            val activeCapture = activeKeyCapture.use()
            keyBindingRevision.use()

            SettingsTabs(
                currentPage = pageContent.page,
                theme = theme,
                contentWidth = metrics.contentWidth,
                onBack = { dispatch(SettingsAction.Back) },
                onPageSelected = ::showPage,
            )

            val rows = settingsRows(pageContent, activeCapture)

            ScrollableVerticalList(
                items = rows,
                theme = theme,
                width = metrics.contentWidth,
                height = metrics.viewportHeight,
                isScrollByDrag = true,
                framed = true,
                contentPadding = UiTheme.Spacing.sm,
                bottomContentPadding = UiTheme.Spacing.lg,
            ) { row ->
                when (row) {
                    is SettingsScrollRow.SectionTitle -> SectionTitle(row.text, theme, metrics.rowWidth)
                    is SettingsScrollRow.BodyText -> BodyText(row.text, theme, metrics.rowWidth)
                    is SettingsScrollRow.Toggle -> {
                        val current = row.toggle.state.use()
                        SettingsToggleCard(
                            label = row.toggle.i18nText.text(),
                            checked = current,
                            theme = theme,
                            contentWidth = metrics.rowWidth,
                            icon = pageContent.page.settingsIcon,
                        ) {
                            handleToggleChange(row.toggle, it)
                        }
                    }

                    is SettingsScrollRow.Slider -> {
                        val current = row.slider.state.use()
                        SettingsSliderCard(
                            slider = row.slider,
                            value = current,
                            theme = theme,
                            contentWidth = metrics.rowWidth,
                            icon = pageContent.page.settingsIcon,
                            onChanged = {
                                row.slider.state.value = row.slider.normalize(it)
                                dispatch(SettingsAction.PreviewChanges)
                            },
                            // Kool's drag-end event can report the drag-start value after the
                            // pointer has been released. The latest onChanged value is already in
                            // the model, so only persist it here instead of overwriting it.
                            onChangeEnd = { dispatch(SettingsAction.ApplyChanges) },
                        )
                    }

                    SettingsScrollRow.MaxFrameRate -> {
                        SettingsMaxFrameRateCard(
                            selected = MaxFrameRateOption.fromFramesPerSecond(model.maxFrameRate.use()),
                            theme = theme,
                            contentWidth = metrics.rowWidth,
                            icon = pageContent.page.settingsIcon,
                        ) { option ->
                            model.maxFrameRate.value = option.framesPerSecond
                            dispatch(SettingsAction.ApplyChanges)
                        }
                    }

                    SettingsScrollRow.FactoryProduction -> {
                        SettingsFactoryProductionCard(
                            singleUnit = model.singleUnitProduction.use(),
                            theme = theme,
                            contentWidth = metrics.rowWidth,
                        ) { singleUnit ->
                            model.singleUnitProduction.value = singleUnit
                            dispatch(SettingsAction.ApplyChanges)
                        }
                    }

                    is SettingsScrollRow.KeyBinding -> {
                        SettingsKeyBindingCard(
                            row = row.row,
                            theme = theme,
                            contentWidth = metrics.rowWidth,
                            onBind = { slot -> activeKeyCapture.value = KeyBindingCaptureTarget(row.row.index, slot) },
                            onClear = { slot -> clearKeyBinding(row.row.index, slot) },
                        )
                    }

                    is SettingsScrollRow.ColorSchemeSelector -> {
                        SettingsColorSchemeCard(row.item, row.selected, theme, metrics.rowWidth) {
                            model.selectedColorSchemeId.value = row.item.id
                            dispatch(SettingsAction.ApplyChanges)
                        }
                    }

                    is SettingsScrollRow.StorageLocation -> {
                        SettingsStorageLocationCard(
                            selected = AndroidStoragePreference.fromStorageType(row.selectedType),
                            theme = theme,
                            contentWidth = metrics.rowWidth,
                            onSelected = ::handleStorageLocationChange,
                        )
                    }
                }
            }

        }
    }

    private fun settingsRows(
        pageContent: SettingsPageContent,
        activeCapture: KeyBindingCaptureTarget?,
    ): List<SettingsScrollRow> {
        val rows = mutableListOf<SettingsScrollRow>(SettingsScrollRow.SectionTitle(pageContent.page.title))

        when (pageContent.page) {
            SettingsPage.Display, SettingsPage.Audio, SettingsPage.Interface, SettingsPage.Gameplay -> {
                pageContent.items.forEach { item ->
                    when (item) {
                        is SettingsPageItem.Toggle -> rows.add(SettingsScrollRow.Toggle(item.toggle))
                        is SettingsPageItem.Slider -> rows.add(SettingsScrollRow.Slider(item.slider))
                        SettingsPageItem.MaxFrameRate -> rows.add(SettingsScrollRow.MaxFrameRate)
                        SettingsPageItem.FactoryProduction -> rows.add(SettingsScrollRow.FactoryProduction)
                        is SettingsPageItem.ColorSchemeSelector -> Unit
                        is SettingsPageItem.StorageLocation -> rows.add(
                            SettingsScrollRow.StorageLocation(item.selectedType)
                        )
                    }
                }
            }

            SettingsPage.KeyBindings -> {
                rows.add(SettingsScrollRow.BodyText(I18n.settings.keyBindings.hint()))
                val controller = keyBindingController()
                keyBindingRows(controller, activeCapture).forEach { row ->
                    if (row.keyBinding.d()) {
                        rows.add(SettingsScrollRow.SectionTitle(localizedKeyBindingName(row.keyBinding)))
                    } else {
                        rows.add(SettingsScrollRow.KeyBinding(row))
                    }
                }
            }

            SettingsPage.ColorScheme -> {
                rows.add(SettingsScrollRow.BodyText(I18n.settings.colorScheme.hint()))
                pageContent.items.filterIsInstance<SettingsPageItem.ColorSchemeSelector>().forEach { selectorItem ->
                    rows.add(SettingsScrollRow.ColorSchemeSelector(selectorItem.item, selectorItem.selected))
                }
            }
        }
        return rows
    }

    private fun handleToggleChange(toggle: SettingToggle, requestedValue: Boolean) {
        toggle.state.value = requestedValue
        dispatch(SettingsAction.ApplyChanges)
    }

    private fun handleStorageLocationChange(preference: AndroidStoragePreference) {
        if (preference == AndroidStoragePreference.External) {
            val host=getKoin().get<PlatformBridge>().filePickerHost?:return
            host.requestExternalStorage { selection ->
                if (selection == null) return@requestExternalStorage
                requestEngineTask({
                    val settings = GameEngine.getInstance()?.settingsEngine ?: return@requestEngineTask false
                    settings.externalSAFLink = selection.uri
                    settings.externalSAFPathShown = selection.displayPath
                    settings.externalSAFPathExtra = ""
                    settings.loadMainExternalFolder(false)
                }) { result ->
                    if (result.getOrDefault(false)) applyStoragePreference(preference, force = true)
                }
            }
            return
        }
        applyStoragePreference(preference)
    }

    private fun applyStoragePreference(
        preference: AndroidStoragePreference,
        force: Boolean = false,
    ) {
        if (!force && model.storageType.value == preference.storageType) return
        model.storageType.value = preference.storageType
        dispatch(SettingsAction.ApplyChanges)
        requestEngineTask({
            if (GameEngine.getInstance() != null) {
                FileHelper.initialize()
                GameEngine.getInstance()?.modManager?.let { manager ->
                    manager.loadAllMods()
                    manager.loadModSelection()
                }
            }
        })
    }

    private fun handleKeyBindingCapture(keyEvents: List<KeyEvent>, @Suppress("UNUSED_PARAMETER") ctx: KoolContext) {
        val target = activeKeyCapture.value ?: return
        for (event in keyEvents) {
            if (event.isConsumed || event.isCharTyped || event.isReleased) {
                continue
            }
            if (event.isRepeated) {
                event.isConsumed = true
                continue
            }
            val keyCode = KoolKeyCodeMapping.androidKeyCode(event) ?: continue
            event.isConsumed = true
            if (event.keyCode == KeyboardInput.KEY_ESC) {
                activeKeyCapture.value = null
                return
            }
            if (KoolKeyCodeMapping.isModifierOnly(event)) {
                return
            }
            val controller = keyBindingController()
            val keyBinding = controller.al.getOrNull(target.bindingIndex) ?: return
            keyBinding.a(keyCode, target.slot, KoolKeyCodeMapping.modifierMask(event), true)
            saveKeyBindings(controller)
            activeKeyCapture.value = null
            keyBindingRevision.value += 1
            return
        }
    }

    private fun clearKeyBinding(bindingIndex: Int, slot: Int) {
        val controller = keyBindingController()
        val keyBinding = controller.al.getOrNull(bindingIndex) ?: return
        keyBinding.a(0, slot, 0, true)
        saveKeyBindings(controller)
        activeKeyCapture.value = null
        keyBindingRevision.value += 1
    }

    private fun saveKeyBindings(controller: LegacyInputController) {
        if (engineSession?.usesIndependentEngineLoop == true) {
            val bindings = controller.al.filter { it.isDefault && !it.d() }.map { it.e() to controller.a(it) }
            requestEngineTask({
                val settings = SettingsEngine.getInstance()
                val target = GameEngine.getInstance()?.inputController ?: LegacyInputController().also { it.a() }
                bindings.forEach { (name, value) -> target.a(name, value) }
                settings.saveKeyBindingsFromInputController(target)
            })
        } else {
            SettingsEngine.getInstance().saveKeyBindingsFromInputController(controller)
        }
        dispatch(SettingsAction.ApplyChanges)
    }

    private fun keyBindingController(): LegacyInputController =
        if (engineSession?.usesIndependentEngineLoop == true) fallbackInputController
        else GameEngine.getInstance()?.inputController ?: fallbackInputController

    private fun keyBindingRows(
        controller: LegacyInputController,
        activeCapture: KeyBindingCaptureTarget?,
    ): List<SettingKeyBindingRow> {
        return controller.al.mapIndexedNotNull { index, keyBinding ->
            if (!keyBinding.isDefault) return@mapIndexedNotNull null
            SettingKeyBindingRow(
                index = index,
                keyBinding = keyBinding,
                primaryText = localizedKeyBindingSlot(keyBinding.b(PRIMARY_KEY_SLOT)),
                secondaryText = localizedKeyBindingSlot(keyBinding.b(SECONDARY_KEY_SLOT)),
                primaryOverlaps = hasOverlappingBinding(controller, keyBinding, PRIMARY_KEY_SLOT),
                secondaryOverlaps = hasOverlappingBinding(controller, keyBinding, SECONDARY_KEY_SLOT),
                activeSlot = activeCapture?.takeIf { it.bindingIndex == index }?.slot,
            )
        }
    }

    private fun hasOverlappingBinding(
        controller: LegacyInputController,
        keyBinding: KeyBinding,
        slot: Int,
    ): Boolean {
        val binding = keyBinding.a(slot) ?: return false
        if (binding.d()) return false
        for (other in controller.al) {
            if (other === keyBinding) continue
            for (otherBinding in other.bindings) {
                if (!otherBinding.d() && binding.a(otherBinding)) {
                    return true
                }
            }
        }
        return false
    }

    companion object {
        const val SETTINGS_SCENE_NAME: String = "settings"
        private const val PRIMARY_KEY_SLOT: Int = 0
        private const val SECONDARY_KEY_SLOT: Int = 1
    }
}

private data class KeyBindingCaptureTarget(
    val bindingIndex: Int,
    val slot: Int,
)

private data class SettingsLayoutMetrics(
    val contentWidth: Dp,
    val rowWidth: Dp,
    val viewportHeight: Dp,
)

private fun UiScope.settingsLayoutMetrics(): SettingsLayoutMetrics {
    val contentWidth = ResponsiveContentWidth(
        defaultWidth = UiTheme.Layout.settingsContentWidth,
        minWidth = UiTheme.Layout.settingsMinContentWidth,
        maxWidth = UiTheme.Layout.settingsMaxContentWidth,
    )
    val viewportPadding = UiTheme.Spacing.sm
    return SettingsLayoutMetrics(
        contentWidth = contentWidth,
        rowWidth = contentWidth.remainingAfter(Dp(viewportPadding.value * 2f), UiTheme.Layout.settingsMinContentWidth),
        viewportHeight = ResponsiveViewportHeight(
            defaultHeight = UiTheme.Layout.settingsViewportHeight,
            minHeight = UiTheme.Layout.settingsMinViewportHeight,
            maxHeight = UiTheme.Layout.settingsMaxViewportHeight,
            // Tabs plus the menu surface's inner / outer padding must remain inside the viewport.
            verticalChrome = Dp(160f),
        ),
    )
}
