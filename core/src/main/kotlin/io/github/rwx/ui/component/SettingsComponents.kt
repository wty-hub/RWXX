package io.github.rwx.ui.component

import io.github.rwx.ui.smallCornerRadius
import io.github.rwx.ui.mediumCornerRadius
import de.fabmax.kool.modules.ui2.*
import io.github.rwx.i18n.I18n
import io.github.rwx.ui.model.AndroidStoragePreference
import io.github.rwx.ui.model.MaxFrameRateOption
import io.github.rwx.ui.ColorSchemeDefinition
import io.github.rwx.ui.model.SettingColorSchemeItem
import io.github.rwx.ui.model.SettingKeyBindingRow
import io.github.rwx.ui.model.SettingSlider
import io.github.rwx.ui.model.SettingsPage
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.fraction
import io.github.rwx.ui.remainingAfter
import io.github.rwx.ui.splitEvenly
import io.github.rwx.ui.model.visibleSettingsPages
import io.github.rwx.ui.model.localizedKeyBindingName

fun UiScope.SettingsCardTitle(
    title: String,
    hint: String,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.settingsContentWidth,
) {
    Row(width = contentWidth, height = UiTheme.Layout.menuButtonHeight) {
        modifier.margin(bottom = UiTheme.Spacing.sm)
        Box(width = UiTheme.Layout.textButtonIconSlotSize, height = Grow.Std) {}
        Text(title) {
            modifier
                .width(Grow.Std)
                .height(Grow.Std)
                .font(UiTheme.Fonts.headingMedium)
                .textAlign(AlignmentX.Center, AlignmentY.Center)
                .textColor(theme.palette.textPrimary)
        }
        Box(width = UiTheme.Layout.textButtonIconSlotSize, height = Grow.Std) {
            Icon(Icon.Settings, UiTheme.Layout.textButtonGlyphSize, theme.palette.primary).modifier
                .align(AlignmentX.Center, AlignmentY.Center)
        }
    }
    Text(hint) {
        modifier
            .width(contentWidth)
            .margin(bottom = UiTheme.Spacing.lg)
            .font(UiTheme.Fonts.bodySmall)
            .textAlign(AlignmentX.Center, AlignmentY.Center)
            .isWrapText(true)
            .textColor(theme.palette.textSecondary)
    }
}

fun UiScope.SettingsTabs(
    currentPage: SettingsPage,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.settingsContentWidth,
    onBack: () -> Unit = {},
    onPageSelected: (SettingsPage) -> Unit,
) {
    val pages = visibleSettingsPages()
    val tabsWidth = contentWidth.remainingAfter(UiTheme.Layout.iconButtonSize)
    val tabWidth = tabsWidth.splitEvenly(
        count = pages.size,
        totalGap = Dp(pages.size * UiTheme.Spacing.sm.value),
        minWidth = Dp(96f),
        maxWidth = contentWidth,
    )
    Row(width = contentWidth) {
        modifier
            .margin(bottom = UiTheme.Spacing.md)
            .padding(UiTheme.Spacing.xs)
            .background(RoundRectBackground(theme.palette.surfaceSunken, theme.mediumCornerRadius))
            .border(RoundRectBorder(theme.palette.borderSubtle, theme.mediumCornerRadius, Dp(1f)))
        IconButton(Icon.Back, theme, onPressed = onBack)
        pages.forEach { page ->
            val isSelected = page == currentPage
            TextIconButton(
                label = page.tabTitle,
                icon = page.settingsIcon,
                width = tabWidth,
                theme = theme,
                emphasized = isSelected,
                font = UiTheme.Fonts.bodySmall,
            ) {
                onPageSelected(page)
            }
        }
    }
}

fun UiScope.SettingsToggleCard(
    label: String,
    checked: Boolean,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.settingsContentWidth,
    icon: Icon? = null,
    onChanged: (Boolean) -> Unit,
) {
    val rowInnerWidth = contentWidth.remainingAfter(Dp(UiTheme.Spacing.md.value * 2f), Dp(240f))
    val iconSlotWidth = if (icon == null) Dp.ZERO else UiTheme.Layout.settingsRowIconSlotSize
    val controlWidth = Dp(104f)
    val labelWidth = rowInnerWidth.remainingAfter(Dp(iconSlotWidth.value + controlWidth.value), Dp(140f))
    Row(width = contentWidth, height = UiTheme.Layout.settingsRowHeight) {
        modifier
            .margin(bottom = UiTheme.Spacing.xs)
            .padding(horizontal = UiTheme.Spacing.md)
            .background(RoundRectBackground(theme.palette.surfaceSunken, theme.smallCornerRadius))
            .border(RoundRectBorder(theme.palette.borderSubtle, theme.smallCornerRadius, Dp(1f)))
        if (icon != null) {
            Box(width = iconSlotWidth, height = Grow.Std) {
                Icon(icon, UiTheme.Layout.textButtonGlyphSize, theme.palette.primary).modifier
                    .align(AlignmentX.Start, AlignmentY.Center)
            }
        }
        Text(label) {
            modifier
                .width(labelWidth)
                .height(UiTheme.Layout.settingsRowHeight)
                .font(UiTheme.Fonts.bodySmall)
                .textAlign(AlignmentX.Start, AlignmentY.Center)
                .isWrapText(true)
                .textColor(theme.palette.textPrimary)
        }
        Box(width = controlWidth, height = Grow.Std) {
            Switch(state = checked) {
                modifier
                    .align(AlignmentX.End, AlignmentY.Center)
                    .onToggle(onChanged)
            }
        }
    }
}

fun UiScope.SettingsMaxFrameRateCard(
    selected: MaxFrameRateOption,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.settingsContentWidth,
    icon: Icon? = null,
    onSelected: (MaxFrameRateOption) -> Unit,
) {
    val rowInnerWidth = contentWidth.remainingAfter(Dp(UiTheme.Spacing.md.value * 2f), Dp(240f))
    val iconSlotWidth = if (icon == null) Dp.ZERO else UiTheme.Layout.settingsRowIconSlotSize
    val controlWidth = rowInnerWidth.fraction(0.4f, Dp(204f), Dp(260f))
    val labelWidth = rowInnerWidth.remainingAfter(Dp(iconSlotWidth.value + controlWidth.value), Dp(96f))
    Row(width = contentWidth, height = UiTheme.Layout.settingsRowHeight) {
        modifier
            .margin(bottom = UiTheme.Spacing.xs)
            .padding(horizontal = UiTheme.Spacing.md)
            .background(RoundRectBackground(theme.palette.surfaceSunken, theme.smallCornerRadius))
            .border(RoundRectBorder(theme.palette.borderSubtle, theme.smallCornerRadius, Dp(1f)))
        if (icon != null) {
            Box(width = iconSlotWidth, height = Grow.Std) {
                Icon(icon, UiTheme.Layout.textButtonGlyphSize, theme.palette.primary).modifier
                    .align(AlignmentX.Start, AlignmentY.Center)
            }
        }
        Text(I18n.settings.display.maxFrameRate()) {
            modifier
                .width(labelWidth)
                .height(UiTheme.Layout.settingsRowHeight)
                .font(UiTheme.Fonts.bodySmall)
                .textAlign(AlignmentX.Start, AlignmentY.Center)
                .textColor(theme.palette.textPrimary)
        }
        Row(width = controlWidth, height = UiTheme.Layout.menuButtonHeight) {
            val options = MaxFrameRateOption.entries
            val stepWidth = Dp(44f)
            val valueWidth = controlWidth.remainingAfter(Dp(stepWidth.value * 2f))
            Button("-") {
                modifier
                    .width(stepWidth)
                    .height(UiTheme.Layout.menuButtonHeight)
                    .font(UiTheme.Fonts.bodySmall)
                    .onClick { options.getOrNull(selected.ordinal - 1)?.let(onSelected) }
            }
            Text(selected.toString()) {
                modifier
                    .width(valueWidth)
                    .height(UiTheme.Layout.menuButtonHeight)
                    .font(UiTheme.Fonts.bodySmall)
                    .textAlign(AlignmentX.Center, AlignmentY.Center)
                    .textColor(theme.palette.textPrimary)
            }
            Button("+") {
                modifier
                    .width(stepWidth)
                    .height(UiTheme.Layout.menuButtonHeight)
                    .font(UiTheme.Fonts.bodySmall)
                    .onClick { options.getOrNull(selected.ordinal + 1)?.let(onSelected) }
            }
        }
    }
}

fun UiScope.SettingsFactoryProductionCard(
    singleUnit: Boolean,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.settingsContentWidth,
    onSelected: (Boolean) -> Unit,
) {
    val innerWidth = contentWidth.remainingAfter(Dp(UiTheme.Spacing.md.value * 2f), Dp(240f))
    Column(width = contentWidth) {
        modifier
            .margin(bottom = UiTheme.Spacing.xs)
            .padding(UiTheme.Spacing.md)
            .background(RoundRectBackground(theme.palette.surfaceSunken, theme.smallCornerRadius))
            .border(RoundRectBorder(theme.palette.borderSubtle, theme.smallCornerRadius, Dp(1f)))
        Text(I18n.settings.gameplay.factoryProduction()) {
            modifier
                .font(UiTheme.Fonts.bodySmall)
                .textColor(theme.palette.textPrimary)
                .margin(bottom = UiTheme.Spacing.xs)
        }
        for (mode in listOf(false, true)) {
            val selected = singleUnit == mode
            val label = if (mode) I18n.settings.gameplay.singleUnitProduction()
                        else I18n.settings.gameplay.allFactoriesProduction()
            TextIconButton(
                label = label,
                icon = if (selected) Icon.Apply else Icon.Gameplay,
                width = innerWidth,
                theme = theme,
                emphasized = selected,
                font = UiTheme.Fonts.bodySmall,
            ) { onSelected(mode) }
        }
    }
}

fun UiScope.SettingsStorageLocationCard(
    selected: AndroidStoragePreference,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.settingsContentWidth,
    onSelected: (AndroidStoragePreference) -> Unit,
) {
    val rowInnerWidth = contentWidth.remainingAfter(
        Dp(UiTheme.Spacing.md.value * 2f),
        Dp(280f),
    )
    val controlWidth = rowInnerWidth.fraction(0.38f, Dp(128f), Dp(240f))
    val labelWidth = rowInnerWidth.remainingAfter(
        Dp(UiTheme.Layout.settingsRowIconSlotSize.value + controlWidth.value),
        Dp(96f),
    )
    Row(width = contentWidth, height = UiTheme.Layout.settingsRowHeight) {
        modifier
            .margin(bottom = UiTheme.Spacing.xs)
            .padding(horizontal = UiTheme.Spacing.md)
            .background(RoundRectBackground(theme.palette.surfaceSunken, theme.smallCornerRadius))
            .border(RoundRectBorder(theme.palette.borderSubtle, theme.smallCornerRadius, Dp(1f)))

        Box(width = UiTheme.Layout.settingsRowIconSlotSize, height = Grow.Std) {
            Icon(Icon.Save, UiTheme.Layout.textButtonGlyphSize, theme.palette.primary)
                .modifier.align(AlignmentX.Start, AlignmentY.Center)
        }
        Text(I18n.settings.storage.location()) {
            modifier
                .width(labelWidth)
                .height(Grow.Std)
                .font(UiTheme.Fonts.bodySmall)
                .textAlign(AlignmentX.Start, AlignmentY.Center)
                .clipToBounds(true)
                .textColor(theme.palette.textPrimary)
        }
        Row(width = controlWidth, height = UiTheme.Layout.menuButtonHeight) {
            val buttonWidth = Dp(controlWidth.value / AndroidStoragePreference.entries.size)
            AndroidStoragePreference.entries.forEach { preference ->
                val isSelected = preference == selected
                Button(preference.shortLabel()) {
                    modifier
                        .width(buttonWidth)
                        .height(UiTheme.Layout.menuButtonHeight)
                        .font(UiTheme.Fonts.caption)
                        .colors(
                            buttonColor = if (isSelected) {
                                theme.palette.primaryContainer
                            } else {
                                theme.palette.surfaceBase
                            },
                            textColor = theme.palette.textPrimary,
                            buttonHoverColor = theme.palette.surfaceRaised,
                            textHoverColor = theme.palette.primary,
                        )
                        .onClick { onSelected(preference) }
                }
            }
        }
    }
}

fun UiScope.SettingsSliderCard(
    slider: SettingSlider,
    value: Float,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.settingsContentWidth,
    icon: Icon? = null,
    onChanged: (Float) -> Unit,
    onChangeEnd: (Float) -> Unit = onChanged,
) {
    val rowInnerWidth = contentWidth.remainingAfter(Dp(UiTheme.Spacing.md.value * 2f), Dp(280f))
    val iconSlotWidth = if (icon == null) Dp.ZERO else UiTheme.Layout.settingsRowIconSlotSize
    val sliderWidth = rowInnerWidth.fraction(0.34f, minWidth = Dp(96f), maxWidth = Dp(360f))
    val valueWidth = rowInnerWidth.fraction(0.12f, minWidth = Dp(80f), maxWidth = Dp(120f))
    val labelWidth =
        rowInnerWidth.remainingAfter(Dp(iconSlotWidth.value + sliderWidth.value + valueWidth.value), Dp(120f))
    Row(width = contentWidth, height = UiTheme.Layout.settingsRowHeight) {
        modifier
            .margin(bottom = UiTheme.Spacing.xs)
            .padding(horizontal = UiTheme.Spacing.md)
            .background(RoundRectBackground(theme.palette.surfaceSunken, theme.smallCornerRadius))
            .border(RoundRectBorder(theme.palette.borderSubtle, theme.smallCornerRadius, Dp(1f)))
        if (icon != null) {
            Box(width = iconSlotWidth, height = Grow.Std) {
                Icon(icon, UiTheme.Layout.textButtonGlyphSize, theme.palette.primary).modifier
                    .align(AlignmentX.Start, AlignmentY.Center)
            }
        }
        Text(slider.i18nText.text()) {
            modifier
                .width(labelWidth)
                .height(UiTheme.Layout.settingsRowHeight)
                .font(UiTheme.Fonts.bodySmall)
                .textAlign(AlignmentX.Start, AlignmentY.Center)
                .isWrapText(true)
                .textColor(theme.palette.textPrimary)
        }
        Slider(value = slider.normalize(value), min = slider.min, max = slider.max) {
            modifier
                .width(sliderWidth)
                .height(UiTheme.Layout.settingsRowHeight)
                .padding(horizontal = UiTheme.Spacing.md)
                .colors(
                    knobColor = theme.palette.primary,
                    trackColor = theme.palette.borderSubtle,
                    trackColorActive = theme.palette.secondary,
                )
                .onChange(onChanged)
                .onChangeEnd(onChangeEnd)
        }
        Text(slider.formatted(value)) {
            modifier
                .width(valueWidth)
                .height(UiTheme.Layout.settingsRowHeight)
                .font(UiTheme.Fonts.bodySmall)
                .textAlign(AlignmentX.End, AlignmentY.Center)
                .textColor(theme.palette.textSecondary)
        }
    }
}

fun UiScope.SettingsKeyBindingCard(
    row: SettingKeyBindingRow,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.settingsContentWidth,
    onBind: (Int) -> Unit,
    onClear: (Int) -> Unit,
) {
    val rowInnerWidth = contentWidth.remainingAfter(Dp(UiTheme.Spacing.md.value * 2f), Dp(320f))
    val iconSlotWidth = UiTheme.Layout.settingsRowIconSlotSize
    val slotWidth = rowInnerWidth.fraction(0.18f, minWidth = Dp(116f), maxWidth = Dp(190f))
    val clearWidth = Dp(42f)
    val labelWidth = rowInnerWidth.remainingAfter(
        Dp(iconSlotWidth.value + (slotWidth.value + clearWidth.value + UiTheme.Spacing.xs.value) * 2f),
        Dp(120f),
    )

    Row(width = contentWidth, height = UiTheme.Layout.settingsRowHeight) {
        modifier
            .margin(bottom = UiTheme.Spacing.xs)
            .padding(horizontal = UiTheme.Spacing.md)
            .background(RoundRectBackground(theme.palette.surfaceSunken, theme.smallCornerRadius))
            .border(RoundRectBorder(theme.palette.borderSubtle, theme.smallCornerRadius, Dp(1f)))

        Box(width = iconSlotWidth, height = Grow.Std) {
            Icon(Icon.Interface, UiTheme.Layout.textButtonGlyphSize, theme.palette.primary).modifier
                .align(AlignmentX.Start, AlignmentY.Center)
        }
        Text(localizedKeyBindingName(row.keyBinding)) {
            modifier
                .width(labelWidth)
                .height(UiTheme.Layout.settingsRowHeight)
                .font(UiTheme.Fonts.bodySmall)
                .textAlign(AlignmentX.Start, AlignmentY.Center)
                .isWrapText(true)
                .textColor(theme.palette.textPrimary)
        }
        KeyBindingSlotButton(
            label = if (row.activeSlot == 0) I18n.settings.keyBindings.pressKey() else row.primaryText,
            slot = 0,
            width = slotWidth,
            active = row.activeSlot == 0,
            overlaps = row.primaryOverlaps,
            theme = theme,
            onBind = onBind,
        )
        ClearKeyBindingButton(0, clearWidth, theme, onClear)
        KeyBindingSlotButton(
            label = if (row.activeSlot == 1) I18n.settings.keyBindings.pressKey() else row.secondaryText,
            slot = 1,
            width = slotWidth,
            active = row.activeSlot == 1,
            overlaps = row.secondaryOverlaps,
            theme = theme,
            onBind = onBind,
        )
        ClearKeyBindingButton(1, clearWidth, theme, onClear)
    }
}

private fun UiScope.KeyBindingSlotButton(
    label: String,
    slot: Int,
    width: Dp,
    active: Boolean,
    overlaps: Boolean,
    theme: ColorSchemeDefinition,
    onBind: (Int) -> Unit,
) {
    val hovered = remember(false)
    val isHovered = hovered.use()
    val background = when {
        active -> theme.palette.primaryContainer
        isHovered -> theme.palette.surfaceRaised
        else -> theme.palette.surfaceBase
    }
    val border = when {
        overlaps -> theme.palette.danger
        active || isHovered -> theme.palette.primary
        else -> theme.palette.borderSubtle
    }
    val textColor = when {
        overlaps -> theme.palette.danger
        isHovered || active -> theme.palette.primary
        else -> theme.palette.textPrimary
    }

    Box(width = width, height = Dp(44f)) {
        modifier
            .alignY(AlignmentY.Center)
            .margin(start = UiTheme.Spacing.xs)
            .padding(horizontal = UiTheme.Spacing.sm)
            .background(RoundRectBackground(background, theme.smallCornerRadius))
            .border(RoundRectBorder(border, theme.smallCornerRadius, Dp(1f)))
            .onEnter { hovered.value = true }
            .onExit { hovered.value = false }
            .onClick { onBind(slot) }
        Text(label.ifBlank { I18n.settings.keyBindings.unassigned() }) {
            modifier
                .width(Grow.Std)
                .height(Grow.Std)
                .font(UiTheme.Fonts.bodySmall)
                .textAlign(AlignmentX.Center, AlignmentY.Center)
                .clipToBounds(true)
                .textColor(textColor)
        }
    }
}

private fun UiScope.ClearKeyBindingButton(
    slot: Int,
    width: Dp,
    theme: ColorSchemeDefinition,
    onClear: (Int) -> Unit,
) {
    val hovered = remember(false)
    val isHovered = hovered.use()
    val border = if (isHovered) theme.palette.danger else theme.palette.borderSubtle
    val iconColor = if (isHovered) theme.palette.danger else theme.palette.textSecondary
    Box(width = width, height = Dp(44f)) {
        modifier
            .alignY(AlignmentY.Center)
            .margin(start = UiTheme.Spacing.xs)
            .background(RoundRectBackground(theme.palette.surfaceBase, theme.smallCornerRadius))
            .border(RoundRectBorder(border, theme.smallCornerRadius, Dp(1f)))
            .onEnter { hovered.value = true }
            .onExit { hovered.value = false }
            .onClick { onClear(slot) }
        Icon(Icon.Close, Dp(20f), iconColor).modifier.align(AlignmentX.Center, AlignmentY.Center)
    }
}

fun UiScope.SettingsColorSchemeCard(
    item: SettingColorSchemeItem,
    selected: Boolean,
    activeScheme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.settingsContentWidth,
    onSelected: () -> Unit,
) {
    val hovered = remember(false)
    val isHovered = hovered.use()
    val background = when {
        isHovered -> activeScheme.palette.surfaceRaised
        selected -> activeScheme.palette.primaryContainer
        else -> activeScheme.palette.surfaceSunken
    }
    val border = if (selected || isHovered) activeScheme.palette.primary else activeScheme.palette.borderSubtle

    Box(width = contentWidth, height = UiTheme.Layout.settingsRowHeight) {
        modifier
            .margin(bottom = UiTheme.Spacing.xs)
            .padding(horizontal = UiTheme.Spacing.md, vertical = UiTheme.Spacing.xs)
            .background(RoundRectBackground(background, activeScheme.smallCornerRadius))
            .border(RoundRectBorder(border, activeScheme.smallCornerRadius, Dp(1f)))
            .onEnter { hovered.value = true }
            .onExit { hovered.value = false }
            .onClick { onSelected() }

        Row(width = Grow.Std, height = Grow.Std) {
            modifier.align(AlignmentX.Center, AlignmentY.Center)
            Box(width = UiTheme.Layout.settingsRowIconSlotSize, height = Grow.Std) {
                Icon(Icon.ColorScheme, UiTheme.Layout.textButtonGlyphSize, item.scheme.palette.primary)
                    .modifier.align(AlignmentX.Start, AlignmentY.Center)
            }
            Text(item.label) {
                modifier
                    .width(Grow.Std)
                    .height(Grow.Std)
                    .margin(end = UiTheme.Spacing.sm)
                    .font(UiTheme.Fonts.bodySmall)
                    .textAlign(AlignmentX.Start, AlignmentY.Center)
                    .clipToBounds(true)
                    .textColor(activeScheme.palette.textPrimary)
            }
            if (selected) {
                Icon(Icon.Apply, UiTheme.Layout.textButtonGlyphSize, activeScheme.palette.primary)
                    .modifier.alignY(AlignmentY.Center)
            } else {
                Box(width = UiTheme.Layout.textButtonGlyphSize, height = UiTheme.Layout.textButtonGlyphSize) {}
            }
        }
    }
}

internal val SettingsPage.settingsIcon: Icon
    get() = when (this) {
        SettingsPage.Display -> Icon.Display
        SettingsPage.Audio -> Icon.Settings
        SettingsPage.Interface -> Icon.Interface
        SettingsPage.Gameplay -> Icon.Gameplay
        SettingsPage.KeyBindings -> Icon.Interface
        SettingsPage.ColorScheme -> Icon.ColorScheme
    }
