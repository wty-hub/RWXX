package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.local.Locale
import de.fabmax.kool.scene.Scene
import io.github.rwx.i18n.I18n
import io.github.rwx.input.KoolKeyCodeMapping
import io.github.rwx.logger
import io.github.rwx.mod.registry.UiRegistry
import io.github.rwx.ui.component.PlatformTextInputBridge
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.CoreUiEvent
import io.github.rwx.ui.host.DialogSceneHost
import io.github.rwx.ui.host.LoadingDialogSceneHost
import io.github.rwx.ui.model.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class InGameDialogController(
    private val gameSession: GameSession,
    private val koolCanvasScene: Scene,
    private val dialogSceneHost: DialogSceneHost,
    private val loadingDialogSceneHost: LoadingDialogSceneHost,
    private val currentScreen: () -> AppScreen,
    private val viewport: () -> KoolCanvasViewport,
    private val showUnavailableDialog: (String) -> Unit
) {
    fun showDialogOverGame(dialog: Dialog) {
        val screen = currentScreen()
        val shouldOverlayGameCanvas = screen == AppScreen.InGame && !gameSession.rendersIntoKoolCanvas

        fun restoreGameCanvas() {
            // Drop keys the dialog swallowed (Enter reopens chat, Shift stays held) before the
            // match canvas takes focus again.
            gameSession.setModalKeyCapture(false)
            if (currentScreen() == AppScreen.InGame && !gameSession.rendersIntoKoolCanvas) {
                gameSession.setGameVisible(
                    true,
                    viewport(),
                    koolOverlay = shouldKeepKoolVisibleForExternalGame(
                        gameSession.compositesExternalGameFrameInKool,
                        UiRegistry.hasActiveHudLayers(),
                    ),
                    pausedBackground = false,
                )
            }
        }

        fun DialogButton.restoreGameAfterPress(): DialogButton {
            if (!shouldOverlayGameCanvas) return this
            return copy(
                onPress = if (onInputPress == null && onFormPress == null) {
                    {
                        try {
                            onPress?.invoke()
                        } finally {
                            restoreGameCanvas()
                        }
                        Unit
                    }
                } else {
                    onPress
                },
                onInputPress = onInputPress?.let { original ->
                    { input: String ->
                        try {
                            original(input)
                        } finally {
                            restoreGameCanvas()
                        }
                    }
                },
                onFormPress = onFormPress?.let { original ->
                    { values: Map<String, String> ->
                        try {
                            original(values)
                        } finally {
                            restoreGameCanvas()
                        }
                    }
                },
            )
        }

        if (shouldOverlayGameCanvas) {
            koolCanvasScene.isVisible = true
            gameSession.setModalKeyCapture(true)
            gameSession.setGameVisible(true, viewport(), koolOverlay = true, pausedBackground = true)
        }
        dialogSceneHost.show(
            dialog.copy(buttons = dialog.buttons.map { it.restoreGameAfterPress() })
        )
    }

    /** Escape dismisses the dialog without pressing a button, so the match still has to release keys. */
    fun onDialogDismissed() {
        if (currentScreen() != AppScreen.InGame || gameSession.rendersIntoKoolCanvas) return
        gameSession.setModalKeyCapture(false)
        gameSession.setGameVisible(
            true,
            viewport(),
            koolOverlay = shouldKeepKoolVisibleForExternalGame(
                gameSession.compositesExternalGameFrameInKool,
                UiRegistry.hasActiveHudLayers(),
            ),
            pausedBackground = false,
        )
    }

    fun showLegacyMessageDialog(event: CoreUiEvent.MessageDialogRequested) {
        showDialogOverGame(
            Dialog(
                title = event.title,
                message = event.message,
                buttons = listOf(DialogButton(I18n.common.ok())),
            )
        )
    }

    fun showLegacyPasswordDialog(event: CoreUiEvent.PasswordDialogRequested) {
        val loadingDialogSuspended = loadingDialogSceneHost.temporarilyHide()
        showDialogOverGame(
            Dialog(
                title = event.title,
                message = event.prompt,
                textInput = DialogTextInput(hint = event.title),
                buttons = listOf(
                    DialogButton(
                        label = event.confirmButtonLabel,
                        onInputPress = { input ->
                            gameSession.requestSessionTask(action = {
                                event.handler.submitPassword(input)
                            }, onComplete = { result ->
                                result.onFailure { error ->
                                    logger.warn(error) { "Legacy password dialog submit failed" }
                                    showUnavailableDialog("Unable to submit input: ${error.message ?: error.javaClass.simpleName}")
                                }.also {
                                    if (loadingDialogSuspended) {
                                        loadingDialogSceneHost.restoreFromTemporaryHide()
                                    }
                                }
                            })
                        },
                    ),
                    DialogButton(
                        label = event.cancelButtonLabel,
                        onPress = {
                            gameSession.requestSessionTask(action = {
                                event.handler.cancelPasswordEntry()
                            }, onComplete = { result ->
                                result.onFailure { error ->
                                    logger.warn(error) { "Legacy password dialog cancel failed" }
                                }.also {
                                    if (loadingDialogSuspended) {
                                        loadingDialogSceneHost.restoreFromTemporaryHide()
                                    }
                                }
                            })
                        },
                    ),
                ),
            )
        )
    }

    fun showLegacyFormDialog(event: CoreUiEvent.FormDialogRequested) {
        showDialogOverGame(
            Dialog(
                title = event.title,
                message = event.message,
                form = DialogForm(
                    event.fields.map { field ->
                        DialogFormField.Text(
                            id = field.id,
                            label = field.label,
                            initialText = field.initialText,
                            hint = field.hint,
                        )
                    }
                ),
                buttons = listOf(
                    DialogButton(
                        label = event.confirmButtonLabel,
                        onFormPress = { values ->
                            gameSession.requestSessionTask(action = {
                                event.handler.submit(values)
                            }, onComplete = { result -> result.onFailure { error ->
                                logger.warn(error) { "Legacy form dialog submit failed" }
                                showUnavailableDialog("Unable to submit input: ${error.message ?: error.javaClass.simpleName}")
                            } })
                        },
                    ),
                    DialogButton(event.cancelButtonLabel),
                ),
                scrollableForm = event.fields.size > 6,
            )
        )
    }

    fun showSaveGameDialog() {
        showDialogOverGame(
            Dialog(
                title = I18n.ingame.saveGame(),
                message = I18n.ingame.savePrompt(),
                textInput = DialogTextInput(
                    initialText = "rwx_save",
                    hint = I18n.ingame.saveName(),
                ),
                buttons = listOf(
                    DialogButton(
                        label = I18n.common.save(),
                        onInputPress = { name ->
                            val saveName = name.trim().ifBlank { "rwx_save" }
                            gameSession.requestSaveGame(saveName)
                        },
                    ),
                    DialogButton(I18n.common.cancel()),
                ),
            ),
        )
    }

    fun showExportMapDialog() {
        val initialName = defaultExportMapName()
        showDialogOverGame(
            Dialog(
                title = I18n.ingame.exportMap(),
                message = I18n.ingame.exportPrompt(),
                textInput = DialogTextInput(
                    initialText = initialName,
                    hint = I18n.ingame.mapName(),
                ),
                buttons = listOf(
                    DialogButton(
                        label = I18n.ingame.export(),
                        onInputPress = { name ->
                            val exportName = name.trim().ifBlank { initialName }
                            gameSession.requestExportMap(exportName)
                        },
                    ),
                    DialogButton(I18n.common.cancel()),
                ),
            ),
        )
    }

    fun showInGameChatDialog(teamOnly: Boolean, openedFromShortcut: Boolean = false) {
        if (openedFromShortcut) {
            // The Enter that opened chat is still down. Ignore it until release so it cannot
            // submit the empty field, and so a lost key-up cannot block the next open.
            gameSession.suppressKeyUntilRelease(KoolKeyCodeMapping.ANDROID_ENTER)
            gameSession.suppressKeyUntilRelease(KoolKeyCodeMapping.ANDROID_NUMPAD_ENTER)
            if (teamOnly) {
                gameSession.suppressKeyUntilRelease(KoolKeyCodeMapping.ANDROID_SHIFT_LEFT)
                gameSession.suppressKeyUntilRelease(KoolKeyCodeMapping.ANDROID_SHIFT_RIGHT)
            }
            PlatformTextInputBridge.armSwallowSubmitUntilRelease()
        }
        val history = gameSession.multiplayerChatHistory()
        showDialogOverGame(
            Dialog(
                title = if (teamOnly) I18n.ingame.teamChat() else I18n.pausemenu.chat(),
                message = if (history.isEmpty()) I18n.battleroom.emptyChat() else "",
                listItems = history.asReversed().map { line ->
                    DialogListItem(line.text, line.teamColorIndex)
                },
                textInput = DialogTextInput(hint = I18n.battleroom.sendMessage(), focusOnShow = true),
                buttons = listOf(
                    DialogButton(
                        label = I18n.battleroom.send(),
                        onInputPress = { message ->
                            val text = message.trim()
                            if (text.isNotBlank()) {
                                gameSession.requestChatMessage(text, teamOnly)
                            }
                        },
                    ),
                    DialogButton(I18n.common.cancel()),
                ),
            ),
        )
    }

    fun showInGamePlayerListDialog() {
        val players = gameSession.multiplayerPlayerList()
        showDialogOverGame(
            Dialog(
                title = I18n.battleroom.players(),
                message = if (players.isEmpty()) I18n.ingame.noPlayers() else "",
                listItems = players.map { player ->
                    val ping = inGamePlayerPingSuffix(player.pingLabel)
                    DialogListItem(
                        text = I18n.ingame.playerInfo(player.name, player.spawnLabel, player.teamLabel, ping),
                        colorIndex = player.nameColorIndex,
                    )
                },
                buttons = listOf(DialogButton(I18n.battleroom.close())),
            )
        )
    }

    fun showExitGameDialog(onExit: (Boolean) -> Unit) {
        val multiplayer = gameSession.runningMultiplayerExitInfo()
        val title = when {
            multiplayer?.isHost == true -> Locale.get("menus.ingame.multiplayerClose.title")
            multiplayer != null -> Locale.get("menus.ingame.multiplayerClose.titleDisconnect")
            else -> "Exit Game"
        }
        val message = when {
            multiplayer?.isHost == true -> Locale.get("menus.ingame.multiplayerClose.messageEndGame")
            multiplayer != null -> Locale.get("menus.ingame.multiplayerClose.messageDisconnect")
            else -> "Are you sure you want to exit this game?"
        }
        val exitLabel = when {
            multiplayer?.isHost == true -> Locale.get("menus.ingame.exitGame")
            multiplayer != null -> Locale.get("menus.ingame.multiplayerClose.disconnectButton")
            else -> "Exit"
        }
        val buttons = buildList {
            add(
                DialogButton(exitLabel) {
                    if (multiplayer != null) {
                        gameSession.requestSessionTask({ gameSession.disconnectRunningMultiplayer() }) { result ->
                            result.onSuccess { onExit(true) }.onFailure { error ->
                                logger.warn(error) { "Disconnect multiplayer failed" }
                                showUnavailableDialog("Unable to disconnect: ${error.message ?: error.javaClass.simpleName}")
                            }
                        }
                    } else onExit(false)
                }
            )
            if (multiplayer?.isHost == true) {
                add(
                    DialogButton(Locale.get("menus.ingame.multiplayerClose.returnToBattleroom")) {
                        gameSession.requestSessionTask({ gameSession.scheduleReturnToBattleRoom() }) { result ->
                            if (result.getOrNull() != true) showUnavailableDialog("Unable to schedule return to battle room")
                        }
                    }
                )
            }
            add(DialogButton(I18n.common.cancel()))
        }
        showDialogOverGame(
            Dialog(
                title = title,
                message = message,
                buttons = buttons,
            ),
        )
    }

    fun requestInGameKoolOverlay(enqueue: () -> Unit) {

        enqueue()
        if (currentScreen() == AppScreen.InGame && !gameSession.rendersIntoKoolCanvas) {

            gameSession.setGameVisible(true, viewport(), koolOverlay = true, pausedBackground = true)
        } else {

        }
    }

    fun requestKoolOverlayForQueuedEvent(event: CoreUiEvent) {
        if (!requiresKoolOverlay(event)) return
        if (currentScreen() != AppScreen.InGame || gameSession.rendersIntoKoolCanvas) return

        koolCanvasScene.isVisible = true
        gameSession.setGameVisible(true, viewport(), koolOverlay = true, pausedBackground = true)
    }

    private fun defaultExportMapName(): String {
        val mapName = gameSession.currentMapDisplayName()
            ?.takeIf { it.isNotBlank() }
            ?: gameSession.runningMapPath()
                ?.substringAfterLast('/')
                ?.removeSuffix(".tmx")
                ?.replace('_', ' ')
                ?.takeIf { it.isNotBlank() }
            ?: "Map"
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy"))
        return "New $mapName - $date".replace("  ", " ")
    }
}

internal fun requiresKoolOverlay(event: CoreUiEvent): Boolean = when (event) {
    CoreUiEvent.InGameExitRequested,
    CoreUiEvent.InGameReturnToBattleRoomRequested,
    CoreUiEvent.InGameSettingsRequested,
    CoreUiEvent.InGameSaveRequested,
    CoreUiEvent.InGameExportMapRequested,
    CoreUiEvent.InGameSurrenderRequested,
    is CoreUiEvent.InGameChatRequested,
    CoreUiEvent.InGamePlayerListRequested,
    is CoreUiEvent.InGameMapJumpRequested,
    CoreUiEvent.InGameMapListRequested,
    CoreUiEvent.InGameModWindowRequested,
    is CoreUiEvent.MessageDialogRequested,
    is CoreUiEvent.PasswordDialogRequested,
    is CoreUiEvent.FormDialogRequested -> true

    else -> false
}

internal fun inGamePlayerPingSuffix(pingLabel: String): String {
    val label = pingLabel.trim()
    if (label.isEmpty()) return ""
    return if (label.toIntOrNull() != null) "  ${label}ms" else "  $label"
}
