package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import io.github.rwx.i18n.I18n
import io.github.rwx.logger
import io.github.rwx.session.BattleRoomTeamLayout
import io.github.rwx.session.GameSession
import io.github.rwx.session.resolveBattleRoomPlayerSnapshot
import io.github.rwx.ui.host.DialogSceneHost
import io.github.rwx.ui.model.Dialog
import io.github.rwx.ui.model.DialogButton

internal class BattleRoomAdminController(
    private val gameSession: GameSession,
    private val dialogSceneHost: DialogSceneHost,
    private val updateBattleRoomFromNetwork: () -> Unit,
    private val showUnavailableDialog: (String) -> Unit,
) {
    fun addAiToBattleRoom() {
        if (gameSession.currentBattleRoom() == null) {
            showUnavailableDialog(I18n.battleroom.hostRequired.addAi())
            return
        }
        requestMutation("Add AI failed", I18n.battleroom.failure.addAi()) {
            check(gameSession.addBattleRoomAi(1)) { I18n.battleroom.rejected.addAi() }
        }
    }

    fun showBattleRoomOptionsDialog() {
        val initialOptions = gameSession.currentBattleRoom()?.room?.options
        if (initialOptions == null) {
            showUnavailableDialog(I18n.battleroom.hostRequired.options())
            return
        }
        dialogSceneHost.show(
            Dialog(
                title = I18n.battleroom.options(),
                message = I18n.battleroom.optionsHint(),
                form = battleRoomOptionsForm(
                    initialOptions,
                    maxPlayers = gameSession.currentBattleRoom()?.maxPlayers ?: DEFAULT_MAX_PLAYERS,
                ),
                buttons = listOf(
                    DialogButton(I18n.battleroom.apply(), onFormPress = ::applyBattleRoomOptionsForm),
                    DialogButton(I18n.common.cancel()),
                ),
                scrollableForm = true,
                compactOnAndroid = true,
            ),
        )
    }

    fun showPlayerConfigDialog(playerId: String) {
        val snapshot = gameSession.currentBattleRoom()
        val player = snapshot?.players
            ?.let { players -> resolveBattleRoomPlayerSnapshot(playerId, players) }
        if (player == null) {
            showUnavailableDialog(I18n.battleroom.hostRequired.playerConfig())
            return
        }
        val canKick = snapshot.isHost && !player.isLocal
        val buttons = buildList {
            add(
                DialogButton(
                    I18n.battleroom.apply(),
                    onFormPress = { values ->
                        requestMutation("Apply player config failed", I18n.battleroom.failure.playerConfig()) {
                            val spawn = values["spawn"]?.toIntOrNull()
                            val team = values["team"]?.toIntOrNull()
                            // Host-only override fields are absent from a non-host form.
                            val startingUnits = values["startingUnits"]?.toIntOrNull()
                            val aiDifficulty = values["aiDifficulty"]?.toIntOrNull()
                            check(
                                gameSession.configureBattleRoomPlayer(
                                    player.id, spawn, team, startingUnits, aiDifficulty,
                                )
                            ) {
                                I18n.battleroom.rejected.playerConfig()
                            }
                        }
                    },
                ),
            )
            if (canKick) {
                add(DialogButton(if (player.isAI) I18n.battleroom.removeAi() else I18n.battleroom.kick()) { kickBattleRoomPlayer(player.id) })
            }
            add(DialogButton(I18n.common.cancel()))
        }
        dialogSceneHost.show(
            Dialog(
                title = I18n.battleroom.playerConfig(),
                message = I18n.battleroom.playerConfigHint(),
                form = playerConfigForm(player, isHost = snapshot.isHost),
                buttons = buttons,
                compactOnAndroid = true,
            ),
        )
    }

    private fun kickBattleRoomPlayer(playerId: String) {
        requestMutation("Kick player failed", I18n.battleroom.failure.kick()) {
            check(gameSession.kickBattleRoomPlayer(playerId)) { I18n.battleroom.rejected.kick() }
        }
    }

    fun sendBattleRoomChatMessage(message: String) {
        val text = message.trim()
        if (text.isBlank()) return
        if (gameSession.currentBattleRoom() == null) {
            showUnavailableDialog(I18n.battleroom.hostRequired.chat())
            return
        }
        requestMutation("Send battle room chat failed", I18n.battleroom.failure.chat(), refreshRoom = false) {
            check(gameSession.sendBattleRoomMessage(text)) { I18n.battleroom.rejected.chat() }
        }
    }

    private fun applyTeamLayout(layout: BattleRoomTeamLayout) {
        if (gameSession.currentBattleRoom() == null) {
            showUnavailableDialog(I18n.battleroom.hostRequired.teams())
            return
        }
        requestMutation("Set team layout failed", I18n.battleroom.failure.teams()) {
            check(gameSession.applyBattleRoomTeamLayout(layout)) {
                I18n.battleroom.rejected.teams()
            }
        }
    }

    private fun applyBattleRoomOptionsForm(values: Map<String, String>) {
        val base = gameSession.currentBattleRoom()?.room?.options ?: GameRoomSettings()
        val options = values.toGameRoomSettings(base)
        val layout = values["teamLayout"]?.toBattleRoomTeamLayoutOrNull()
        if (gameSession.currentBattleRoom() == null) {
            showUnavailableDialog(I18n.battleroom.hostRequired.options())
            return
        }
        requestMutation("Apply battle room options failed", I18n.battleroom.failure.options()) {
            check(gameSession.applyBattleRoomOptions(options)) {
                I18n.battleroom.rejected.options()
            }
            values["maxPlayers"]?.toIntOrNull()?.let { maxPlayers ->
                gameSession.setBattleRoomMaxPlayers(maxPlayers)
            }
            layout?.let {
                check(gameSession.applyBattleRoomTeamLayout(it)) {
                    I18n.battleroom.rejected.teams()
                }
            }
        }
    }

    private fun requestMutation(
        logMessage: String,
        failurePrefix: String,
        refreshRoom: Boolean = true,
        action: () -> Unit,
    ) {
        gameSession.requestSessionTask(action) { result ->
            result.onSuccess { if (refreshRoom) updateBattleRoomFromNetwork() }.onFailure { error ->
                logger.warn(error) { logMessage }
                showUnavailableDialog("$failurePrefix: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }
}
