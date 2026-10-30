package io.github.rwx.ui.model

import com.corrodinggames.rts.gameFramework.KeyBinding
import io.github.rwx.i18n.I18n
import io.github.rwx.ui.ColorSchemeDefinition
import java.util.Locale

// Translate display labels only. The engine names remain stable identifiers for saved bindings.
internal fun localizedKeyBindingName(binding: KeyBinding): String {
    val id = binding.e()
    keyBindingNames[id]?.let { return it() }
    val match = numberedBinding.matchEntire(id) ?: return binding.name
    val number = match.groupValues[2]
    return when (match.groupValues[1]) {
        "unit_action" -> I18n.settings.keyBindings.actions.unitAction(number)
        "create_group" -> I18n.settings.keyBindings.actions.createGroup(number)
        "select_group" -> I18n.settings.keyBindings.actions.selectGroup(number)
        "add_group_to_selection" -> I18n.settings.keyBindings.actions.addGroupToSelection(number)
        else -> binding.name
    }
}

private val numberedBinding = Regex("(unit_action|create_group|select_group|add_group_to_selection)_([0-9]+)")

private val keyBindingNames = mapOf(
    "camera_up" to I18n.settings.keyBindings.actions.cameraUp,
    "camera_down" to I18n.settings.keyBindings.actions.cameraDown,
    "camera_left" to I18n.settings.keyBindings.actions.cameraLeft,
    "camera_right" to I18n.settings.keyBindings.actions.cameraRight,
    "zoom_in" to I18n.settings.keyBindings.actions.zoomIn,
    "zoom_out" to I18n.settings.keyBindings.actions.zoomOut,
    "send_chat" to I18n.settings.keyBindings.actions.sendChat,
    "send_team_chat" to I18n.settings.keyBindings.actions.sendTeamChat,
    "ping_map" to I18n.settings.keyBindings.actions.pingMap,
    "show_menu" to I18n.settings.keyBindings.actions.showMenu,
    "save_game" to I18n.settings.keyBindings.actions.saveGame,
    "deselect_units" to I18n.settings.keyBindings.actions.deselectUnits,
    "go_to_notification" to I18n.settings.keyBindings.actions.goToNotification,
    "select_whole_army" to I18n.settings.keyBindings.actions.selectWholeArmy,
    "select_command_center" to I18n.settings.keyBindings.actions.selectCommandCenter,
    "cycle_builders" to I18n.settings.keyBindings.actions.cycleBuilders,
    "cycle_extractors" to I18n.settings.keyBindings.actions.cycleExtractors,
    "cycle_upgradable_fabricators" to I18n.settings.keyBindings.actions.cycleUpgradableFabricators,
    "cycle_land_factories" to I18n.settings.keyBindings.actions.cycleLandFactories,
    "cycle_air_factories" to I18n.settings.keyBindings.actions.cycleAirFactories,
    "next_music_track" to I18n.settings.keyBindings.actions.nextMusicTrack,
    "game_speed_(single_player)" to I18n.settings.keyBindings.actions.gameSpeed,
    "slower" to I18n.settings.keyBindings.actions.slower,
    "faster" to I18n.settings.keyBindings.actions.faster,
    "pause_game" to I18n.settings.keyBindings.actions.pauseGame,
    "unit_actions" to I18n.settings.keyBindings.actions.unitActions,
    "attack_move" to I18n.settings.keyBindings.actions.attackMove,
    "stop" to I18n.settings.keyBindings.actions.stop,
    "guard_unit" to I18n.settings.keyBindings.actions.guardUnit,
    "patrol" to I18n.settings.keyBindings.actions.patrol,
    "reclaim" to I18n.settings.keyBindings.actions.reclaim,
    "action_upgrade" to I18n.settings.keyBindings.actions.actionUpgrade,
    "action_set_rally" to I18n.settings.keyBindings.actions.actionSetRally,
    "unit_groups" to I18n.settings.keyBindings.actions.unitGroups,
)

internal fun localizedColorSchemeName(scheme: ColorSchemeDefinition): String =
    colorSchemeNames[scheme.id.value]?.invoke() ?: scheme.displayName

private val colorSchemeNames = mapOf(
    "rwx" to I18n.settings.colorScheme.names.rwx,
    "material-purple" to I18n.settings.colorScheme.names.materialPurple,
    "material-default" to I18n.settings.colorScheme.names.materialDefault,
    "amber-blue" to I18n.settings.colorScheme.names.amberBlue,
    "aqua-blue" to I18n.settings.colorScheme.names.aquaBlue,
    "bahama-and-trinidad" to I18n.settings.colorScheme.names.bahamaAndTrinidad,
    "gold-sunset" to I18n.settings.colorScheme.names.goldSunset,
    "flutter-dash" to I18n.settings.colorScheme.names.flutterDash,
    "hippie-blue" to I18n.settings.colorScheme.names.hippieBlue,
    "pink-sakura" to I18n.settings.colorScheme.names.pinkSakura,
    "blumine" to I18n.settings.colorScheme.names.blumine,
    "green-money" to I18n.settings.colorScheme.names.greenMoney,
    "rosewood" to I18n.settings.colorScheme.names.rosewood,
    "verdun-lime" to I18n.settings.colorScheme.names.verdunLime,
    "grey-law" to I18n.settings.colorScheme.names.greyLaw,
    "red-tornado" to I18n.settings.colorScheme.names.redTornado,
)

internal fun localizedKeyBindingSlot(value: String): String {
    if (value.isBlank() || value.equals("NONE", ignoreCase = true) || value == "<null>") {
        return I18n.settings.keyBindings.unassigned()
    }
    return value.split('+').joinToString("+") { token ->
        // Keyboard legends such as Ctrl, Shift, Alt and letter keys stay recognizable on the keyboard.
        when (val key = token.uppercase(Locale.ROOT)) {
            "UP", "DPAD_UP" -> I18n.settings.keyBindings.keys.up()
            "DOWN", "DPAD_DOWN" -> I18n.settings.keyBindings.keys.down()
            "LEFT", "DPAD_LEFT" -> I18n.settings.keyBindings.keys.left()
            "RIGHT", "DPAD_RIGHT" -> I18n.settings.keyBindings.keys.right()
            "SPACE" -> I18n.settings.keyBindings.keys.space()
            "ENTER", "RETURN" -> I18n.settings.keyBindings.keys.enter()
            "NUMPADENTER", "NUMPAD_ENTER" -> I18n.settings.keyBindings.keys.numpad(I18n.settings.keyBindings.keys.enter())
            "TAB" -> I18n.settings.keyBindings.keys.tab()
            "ESC", "ESCAPE" -> I18n.settings.keyBindings.keys.escape()
            "DEL", "BACKSPACE" -> I18n.settings.keyBindings.keys.backspace()
            "FORWARD_DEL", "DELETE" -> I18n.settings.keyBindings.keys.delete()
            "INSERT" -> I18n.settings.keyBindings.keys.insert()
            "HOME", "MOVE_HOME" -> I18n.settings.keyBindings.keys.home()
            "END", "MOVE_END" -> I18n.settings.keyBindings.keys.end()
            "PAGE_UP", "PAGEUP", "PRIOR" -> I18n.settings.keyBindings.keys.pageUp()
            "PAGE_DOWN", "PAGEDOWN", "NEXT" -> I18n.settings.keyBindings.keys.pageDown()
            "PAUSE", "BREAK" -> I18n.settings.keyBindings.keys.pause()
            "CAPS_LOCK", "CAPSLOCK" -> I18n.settings.keyBindings.keys.capsLock()
            "NUM_LOCK", "NUMLOCK" -> I18n.settings.keyBindings.keys.numLock()
            "SCROLL_LOCK", "SCROLLLOCK" -> I18n.settings.keyBindings.keys.scrollLock()
            "MINUS" -> "−"
            "PLUS" -> "+"
            "EQUALS" -> "="
            "COMMA" -> ","
            "PERIOD" -> "."
            "SLASH" -> "/"
            "SEMICOLON" -> ";"
            "APOSTROPHE" -> "'"
            "GRAVE" -> "`"
            "BACKSLASH" -> "\\"
            "LEFT_BRACKET", "LBRACKET" -> "["
            "RIGHT_BRACKET", "RBRACKET" -> "]"
            "UNKNOWN", "<ABSTRACT>" -> I18n.settings.keyBindings.keys.unknown()
            else -> numpadKey.matchEntire(key)?.let {
                val keyName = it.groupValues[1]
                I18n.settings.keyBindings.keys.numpad(numpadSymbols[keyName] ?: keyName)
            }
                ?: token
        }
    }
}

private val numpadSymbols = mapOf(
    "DIVIDE" to "/", "MULTIPLY" to "×", "SUBTRACT" to "−", "ADD" to "+",
    "DOT" to ".", "COMMA" to ",", "EQUALS" to "=", "LEFT_PAREN" to "(", "RIGHT_PAREN" to ")",
)
private val numpadKey = Regex("NUMPAD_?([0-9]|DIVIDE|MULTIPLY|SUBTRACT|ADD|DOT|COMMA|EQUALS|LEFT_PAREN|RIGHT_PAREN)")
