package io.github.rwx.ui.model

import io.github.rwx.i18n.I18n
import io.github.rwx.session.InGameMenuOptionSnapshot
import io.github.rwx.ui.AppScreen


sealed interface PauseMenuAction {
    data object Resume : PauseMenuAction
    data object Save : PauseMenuAction
    data object Settings : PauseMenuAction
    data object Chat : PauseMenuAction
    data object Players : PauseMenuAction
    data object Surrender : PauseMenuAction
    data object ExitGame : PauseMenuAction
    data class Legacy(val menuId: Int) : PauseMenuAction
}

/** Conditions that drive which pause-menu buttons are shown. */
data class PauseMenuConditions(
    val canSave: Boolean = false,
    val isMultiplayer: Boolean = false,
)

/** One rendered pause-menu entry. */
data class PauseMenuItem(
    val action: PauseMenuAction,
    val label: String,
)

object PauseMenuViewModel {
    /** Keep the engine's ordering and visibility; mod entries retain their supplied labels. */
    fun items(options: List<InGameMenuOptionSnapshot>): List<PauseMenuItem> = buildList {
        add(PauseMenuItem(PauseMenuAction.Resume, I18n.pausemenu.resume()))
        options.forEach { option ->
            // The native dispatcher gives mod callbacks priority even when they reuse a built-in
            // ID. Preserve that priority for every matching row, including the native menu row.
            if (option.isModOverride) {
                add(PauseMenuItem(PauseMenuAction.Legacy(option.menuId), option.label))
                return@forEach
            }
            val action = when (option.menuId) {
                2 -> PauseMenuAction.Settings
                12 -> PauseMenuAction.Save
                13 -> PauseMenuAction.Chat
                14 -> PauseMenuAction.Players
                19 -> PauseMenuAction.Surrender
                10, 15, 20 -> PauseMenuAction.ExitGame
                else -> PauseMenuAction.Legacy(option.menuId)
            }
            val label = when (option.menuId) {
                2 -> I18n.pausemenu.settings()
                9 -> if (option.label in I18n.pausemenu.stopRecording.translations.values) {
                    I18n.pausemenu.stopRecording()
                } else {
                    I18n.pausemenu.startRecording()
                }
                12 -> I18n.pausemenu.save()
                13 -> I18n.pausemenu.chat()
                14 -> I18n.pausemenu.players()
                15, 20 -> I18n.pausemenu.exitGame()
                16 -> I18n.ingame.teamChat()
                18 -> I18n.ingame.exportMap()
                19 -> I18n.pausemenu.surrender()
                24 -> I18n.levelselect.linkedMaps.title()
                // ID 10 can mean either Disconnect or Exit Game. The native menu already captures
                // its translated state-dependent label, as it does for briefing / hide-interface.
                else -> option.label
            }
            add(PauseMenuItem(action, label))
        }
    }

    /** Initial menu before a running engine can supply its authoritative menu snapshot. */
    fun items(conditions: PauseMenuConditions): List<PauseMenuItem> = buildList {
        add(PauseMenuItem(PauseMenuAction.Resume, I18n.pausemenu.resume()))
        if (conditions.canSave) add(PauseMenuItem(PauseMenuAction.Save, I18n.pausemenu.save()))
        add(PauseMenuItem(PauseMenuAction.Settings, I18n.pausemenu.settings()))
        if (conditions.isMultiplayer) {
            add(PauseMenuItem(PauseMenuAction.Chat, I18n.pausemenu.chat()))
            add(PauseMenuItem(PauseMenuAction.Players, I18n.pausemenu.players()))
            add(PauseMenuItem(PauseMenuAction.Surrender, I18n.pausemenu.surrender()))
        }
        add(PauseMenuItem(PauseMenuAction.ExitGame, I18n.pausemenu.exitGame()))
    }
}

sealed interface PauseMenuOutcome {
    data class Navigate(val screen: AppScreen) : PauseMenuOutcome
    data object SaveGame : PauseMenuOutcome
    data object OpenSettings : PauseMenuOutcome
    data object Chat : PauseMenuOutcome
    data object Players : PauseMenuOutcome
    data object Surrender : PauseMenuOutcome
    data object ExitGame : PauseMenuOutcome
    data class SelectLegacyOption(val menuId: Int) : PauseMenuOutcome
}

object PauseMenuNavigation {
    fun outcomeFor(action: PauseMenuAction): PauseMenuOutcome = when (action) {
        PauseMenuAction.Resume -> PauseMenuOutcome.Navigate(AppScreen.InGame)
        PauseMenuAction.Save -> PauseMenuOutcome.SaveGame
        PauseMenuAction.Settings -> PauseMenuOutcome.OpenSettings
        PauseMenuAction.Chat -> PauseMenuOutcome.Chat
        PauseMenuAction.Players -> PauseMenuOutcome.Players
        PauseMenuAction.Surrender -> PauseMenuOutcome.Surrender
        PauseMenuAction.ExitGame -> PauseMenuOutcome.ExitGame
        is PauseMenuAction.Legacy -> PauseMenuOutcome.SelectLegacyOption(action.menuId)
    }
}
