package io.github.rwx.ui.host

import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.scene.Scene
import io.github.rwx.i18n.I18n
import io.github.rwx.ui.ColorSchemeDefinition
import io.github.rwx.ui.ResponsiveContentWidth
import io.github.rwx.ui.ResponsiveViewportHeight
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.mediumCornerRadius
import io.github.rwx.ui.component.*
import io.github.rwx.ui.model.*

/** Cyberpunk command panel with a scrollable action stack on smaller viewports. */
class PauseMenuSceneHost(
    private val model: SettingsModel = SettingsModel(),
    private val onAction: (PauseMenuAction) -> Unit = {},
) {
    var items: List<PauseMenuItem> = emptyList()
        private set

    private val menuItems = mutableStateListOf<PauseMenuItem>()

    fun updateItems(conditions: PauseMenuConditions) {
        val next = PauseMenuViewModel.items(conditions)
        if (next != items) {
            items = next
            menuItems.atomic {
                clear()
                addAll(next)
            }
        }
    }

    fun dispatch(action: PauseMenuAction) = onAction(action)

    fun createScene(): Scene = UiScene(PAUSE_SCENE_NAME) {
        addPanelSurface(PanelStyle.Pause, "pause-panel", model) { theme ->
            val visibleItems = menuItems.use().toList()
            val panelWidth = ResponsiveContentWidth(
                defaultWidth = Dp(600f), minWidth = Dp(260f), maxWidth = Dp(600f),
            )
            val actionHeight = Dp(visibleItems.size * 64f)
            val listHeight = ResponsiveViewportHeight(
                defaultHeight = actionHeight,
                minHeight = Dp(96f),
                maxHeight = Dp(actionHeight.value.coerceAtLeast(96f)),
                verticalChrome = Dp(220f),
            )
            Box(width = panelWidth, height = FitContent) {
                modifier
                    .background(LinearGradientBackground(
                        theme.palette.surfaceBase.withAlpha(0.98f),
                        theme.palette.surfaceRaised.withAlpha(0.94f),
                        cornerRadius = theme.mediumCornerRadius,
                    ))
                    .border(RoundRectBorder(theme.palette.primary.withAlpha(0.5f), theme.mediumCornerRadius, Dp(1f)))
                    .padding(UiTheme.Spacing.lg)
                CyberCardRail(theme, active = true)
                Column(width = Grow.Std, height = FitContent) {
                    Row(width = Grow.Std, height = Dp(24f)) {
                        Text(I18n.pausemenu.command()) {
                            modifier.width(Grow.Std).font(UiTheme.Fonts.caption)
                                .textColor(theme.palette.primary)
                        }
                        if (panelWidth.value >= 420f) Row(width = FitContent, height = Dp(4f)) {
                            modifier.alignY(AlignmentY.Center)
                            repeat(5) { index ->
                                Box(width = Dp(10f + index * 3f), height = Dp(3f)) {
                                    modifier.margin(start = Dp(4f))
                                        .backgroundColor(if (index == 4) theme.palette.secondary else theme.palette.primary.withAlpha(0.5f))
                                }
                            }
                        }
                    }
                    Text(I18n.pausemenu.title()) {
                        modifier.width(Grow.Std)
                            .font(if (panelWidth.value < 420f) UiTheme.Fonts.headingSmall else UiTheme.Fonts.headingMedium)
                            .isWrapText(true)
                            .textColor(theme.palette.textPrimary)
                            .margin(bottom = UiTheme.Spacing.md)
                    }
                    Box(width = Grow.Std, height = Dp(1f)) {
                        modifier.backgroundColor(theme.palette.primary.withAlpha(0.3f))
                            .margin(bottom = UiTheme.Spacing.md)
                    }
                    ScrollableVerticalList(
                        items = visibleItems,
                        theme = theme,
                        width = Grow.Std,
                        height = listHeight,
                        isScrollByDrag = true,
                    ) { item ->
                        PauseActionButton(item, visibleItems.indexOf(item) + 1, theme) { dispatch(item.action) }
                    }
                }
            }
        }
    }

    companion object {
        const val PAUSE_SCENE_NAME: String = "pause"
    }
}

private fun UiScope.PauseActionButton(
    item: PauseMenuItem,
    number: Int,
    theme: ColorSchemeDefinition,
    onPressed: () -> Unit,
) {
    val hovered = remember(false)
    val isHovered = hovered.use()
    val isResume = item.action == PauseMenuAction.Resume
    val isDanger = item.action == PauseMenuAction.Surrender || item.action == PauseMenuAction.ExitGame
    val accent = if (isDanger) theme.palette.danger else theme.palette.primary
    val background = when {
        isHovered -> accent.withAlpha(0.18f)
        isResume -> theme.palette.primaryContainer
        else -> theme.palette.surfaceSunken
    }
    Box(width = Grow.Std, height = Dp(60f)) {
        modifier.margin(vertical = Dp(2f))
            .padding(horizontal = UiTheme.Spacing.md)
            .background(RoundRectBackground(background, Dp(2f)))
            .border(RoundRectBorder(accent.withAlpha(if (isHovered || isResume) 0.95f else 0.3f), Dp(2f), Dp(1f)))
            .onEnter { hovered.value = true }
            .onExit { hovered.value = false }
            .onClick { onPressed() }
        Box(width = Dp(3f), height = Grow.Std) {
            modifier.alignX(AlignmentX.Start)
                .backgroundColor(if (isHovered && !isDanger) theme.palette.secondary else accent)
        }
        Row(width = Grow.Std, height = Grow.Std) {
            Text(number.toString().padStart(2, '0')) {
                modifier.width(Dp(32f)).height(Grow.Std).font(UiTheme.Fonts.caption)
                    .textAlign(AlignmentX.Center, AlignmentY.Center)
                    .textColor(accent.withAlpha(0.65f))
            }
            Box(width = Dp(40f), height = Grow.Std) {
                Icon(item.action.pauseIcon, Dp(24f), accent).modifier.align(AlignmentX.Center, AlignmentY.Center)
            }
            Text(item.label) {
                modifier.width(Grow.Std).height(Grow.Std).font(UiTheme.Fonts.bodySmall)
                    .textAlign(AlignmentX.Start, AlignmentY.Center)
                    .clipToBounds(true)
                    .textColor(if (isHovered || isDanger) accent else theme.palette.textPrimary)
            }
            Text("›") {
                modifier.width(Dp(24f)).height(Grow.Std).font(UiTheme.Fonts.headingSmall)
                    .textAlign(AlignmentX.End, AlignmentY.Center)
                    .textColor(if (isHovered) accent else theme.palette.borderSubtle)
            }
        }
    }
}

private val PauseMenuAction.pauseIcon: Icon
    get() = when (this) {
        PauseMenuAction.Resume -> Icon.Continue
        PauseMenuAction.Save -> Icon.Save
        PauseMenuAction.Settings -> Icon.Settings
        PauseMenuAction.Chat -> Icon.Send
        PauseMenuAction.Players -> Icon.Multiplayer
        PauseMenuAction.Surrender -> Icon.Surrender
        PauseMenuAction.ExitGame -> Icon.Exit
    }
