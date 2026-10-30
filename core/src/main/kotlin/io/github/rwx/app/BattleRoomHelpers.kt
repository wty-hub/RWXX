package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import io.github.rwx.BATTLE_ROOM_AUTO_TEAM_VALUE
import io.github.rwx.BATTLE_ROOM_CLEAR_OVERRIDE
import io.github.rwx.BATTLE_ROOM_SPECTATOR_SPAWN_VALUE
import io.github.rwx.i18n.I18n
import io.github.rwx.p2p.MapFeatureDetector
import io.github.rwx.session.BattleRoomSnapshot
import io.github.rwx.session.BattleRoomTeamLayout
import io.github.rwx.session.teamLabelFor
import io.github.rwx.ui.model.*
import java.util.*

internal const val BATTLE_ROOM_JOIN_POLL_INTERVAL_MS: Long = 100L

private const val BATTLE_ROOM_JOIN_TIMEOUT_NANOS: Long = 120_000_000_000L

internal enum class BattleRoomJoinPollResult {
    Connecting,
    Connected,
    Failed,
    TimedOut,
}

internal fun battleRoomJoinPollResult(
    startedAtNanos: Long,
    nowNanos: Long,
    hasBattleRoomSnapshot: Boolean,
    isJoinInProgress: Boolean,
    errorMessage: String?,
    timeoutNanos: Long = BATTLE_ROOM_JOIN_TIMEOUT_NANOS,
): BattleRoomJoinPollResult =
    when {
        !errorMessage.isNullOrBlank() -> BattleRoomJoinPollResult.Failed
        hasBattleRoomSnapshot && !isJoinInProgress -> BattleRoomJoinPollResult.Connected
        nowNanos - startedAtNanos >= timeoutNanos -> BattleRoomJoinPollResult.TimedOut
        else -> BattleRoomJoinPollResult.Connecting
    }

internal fun MultiplayerRoomItem.joinDisplayLabel(): String =
    hostName.takeIf { it.isNotBlank() }
        ?.let { host -> "$host / $mapName" }
        ?: mapName.ifBlank { I18n.multiplayer.server() }

internal fun joinRoomDialogMessage(room: MultiplayerRoomItem): String =
    listOfNotNull(
        localizeLegacyRoomText(room.infoText).trim().takeIf { it.isNotBlank() },
        listOf(
            I18n.multiplayer.roomInfo.host(room.hostName),
            I18n.multiplayer.roomInfo.map(room.mapName),
            I18n.multiplayer.roomInfo.players(room.playersLabel),
            I18n.multiplayer.roomInfo.state(room.localizedStateLabel),
            I18n.multiplayer.roomInfo.version(room.versionLabel),
            I18n.multiplayer.roomInfo.transport(room.localizedTransportLabel),
            I18n.multiplayer.roomInfo.password(
                if (room.requiresPassword) I18n.common.required() else I18n.common.no(),
            ),
            I18n.multiplayer.roomInfo.mods(
                if (room.hasMods) I18n.common.required() else I18n.common.no(),
            ),
        ).joinToString("\n"),
    ).joinToString("\n\n")

internal fun defaultBattleRoomAiPlayerCount(playerCount: Int?): Int =
    (playerCount ?: 1).minus(1).coerceAtLeast(0)


internal fun String.toBattleRoomTeamLayoutOrNull(): BattleRoomTeamLayout? =
    when (this) {
        "2" -> BattleRoomTeamLayout.TwoSides
        "3" -> BattleRoomTeamLayout.ThreeSides
        "ffa" -> BattleRoomTeamLayout.Ffa
        "spectators" -> BattleRoomTeamLayout.Spectators
        "allvsai" -> BattleRoomTeamLayout.AllVsAi
        "allvs2" -> BattleRoomTeamLayout.AllVs2
        "random" -> BattleRoomTeamLayout.Random
        else -> null
    }

// GameRoomSettings <-> GameRoomSettings mapping lives in

internal fun Map<String, String>.toGameRoomSettings(base: GameRoomSettings = GameRoomSettings()): GameRoomSettings =
    GameRoomSettings().apply {
        aiDifficulty = get("aiDifficulty")?.toIntOrNull() ?: base.aiDifficulty
        startingUnits = get("startingUnits")?.toIntOrNull() ?: base.startingUnits
        fogMode = get("fogMode")?.toIntOrNull() ?: base.fogMode
        revealedMap = get("revealedMap")?.toBooleanStrictOrNull() ?: base.revealedMap
        startingCredits = get("startingCredits")?.toIntOrNull() ?: base.startingCredits
        incomeMultiplier = get("incomeMultiplier")?.toFloatOrNull() ?: base.incomeMultiplier
        noNukes = get("noNukes")?.toBooleanStrictOrNull() ?: base.noNukes
        sharedControl = get("sharedControl")?.toBooleanStrictOrNull() ?: base.sharedControl
        allowSpectators = get("allowSpectators")?.toBooleanStrictOrNull() ?: base.allowSpectators
        teamLock = get("teamLock")?.toBooleanStrictOrNull() ?: base.teamLock
        roomLock = get("roomLocked")?.toBooleanStrictOrNull() ?: base.roomLock
        fixedAllyTeams = get("fixedAllyTeams")?.toBooleanStrictOrNull() ?: base.fixedAllyTeams
    }


internal fun battleRoomOptionsForm(options: GameRoomSettings, maxPlayers: Int = 10): DialogForm =
    DialogForm(
        fields = listOf(
            DialogFormField.Choice(
                id = "maxPlayers",
                label = I18n.battleroom.maxPlayers(),
                options = (2..10).map { DialogFormOption(it.toString(), it.toString()) },
                selectedIndex = (maxPlayers - 2).coerceIn(0, 8),
            ),
            DialogFormField.Choice(
                id = "aiDifficulty",
                label = I18n.battleroom.aiDifficulty.label(),
                options = listOf(
                    DialogFormOption(I18n.battleroom.aiDifficulty.veryEasy(), "-2"),
                    DialogFormOption(I18n.battleroom.aiDifficulty.easy(), "-1"),
                    DialogFormOption(I18n.battleroom.aiDifficulty.medium(), "0"),
                    DialogFormOption(I18n.battleroom.aiDifficulty.hard(), "1"),
                    DialogFormOption(I18n.battleroom.aiDifficulty.veryHard(), "2"),
                    DialogFormOption(I18n.battleroom.aiDifficulty.impossible(), "3"),
                ),
                selectedIndex = listOf("-2", "-1", "0", "1", "2", "3")
                    .indexOf(options.aiDifficulty.toString())
                    .coerceAtLeast(0),
            ),
            DialogFormField.Choice(
                id = "startingUnits",
                label = I18n.battleroom.startingUnits.label(),
                options = (1..5).map {
                    DialogFormOption(startingUnitsLabel(it), it.toString())
                },
                selectedIndex = (options.startingUnits - 1).coerceIn(0, 4),
            ),
            DialogFormField.Choice(
                id = "fogMode",
                label = I18n.battleroom.fog.label(),
                options = listOf(
                    DialogFormOption(I18n.battleroom.fog.none(), "0"),
                    DialogFormOption(I18n.battleroom.fog.basic(), "1"),
                    DialogFormOption(I18n.battleroom.fog.lineOfSight(), "2"),
                ),
                selectedIndex = options.fogMode.coerceIn(0, 2),
            ),
            DialogFormField.Toggle("revealedMap", I18n.battleroom.revealedMap(), options.revealedMap),
            DialogFormField.Choice(
                id = "startingCredits",
                label = I18n.battleroom.startingCredits.label(),
                options = (0..8).map {
                    DialogFormOption(startingCreditsLabel(it), it.toString())
                },
                selectedIndex = (0..8)
                    .indexOf(options.startingCredits)
                    .coerceAtLeast(0),
            ),
            DialogFormField.Choice(
                id = "incomeMultiplier",
                label = I18n.battleroom.income(),
                options = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f).map {
                    DialogFormOption("${it}x", it.toString())
                },
                selectedIndex = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f)
                    .indexOf(options.incomeMultiplier)
                    .coerceAtLeast(1),
            ),
            DialogFormField.Choice(
                id = "teamLayout",
                label = I18n.battleroom.teamLayout.label(),
                options = listOf(
                    DialogFormOption(I18n.battleroom.teamLayout.noChange(), ""),
                    DialogFormOption(I18n.battleroom.teamLayout.twoSides(), "2"),
                    DialogFormOption(I18n.battleroom.teamLayout.threeSides(), "3"),
                    DialogFormOption(I18n.battleroom.teamLayout.ffa(), "ffa"),
                    DialogFormOption(I18n.battleroom.teamLayout.spectators(), "spectators"),
                    DialogFormOption(I18n.battleroom.teamLayout.random(), "random"),
                    DialogFormOption(I18n.battleroom.teamLayout.allVsAi(), "allvsai"),
                    DialogFormOption(I18n.battleroom.teamLayout.allVsTwo(), "allvs2"),
                ),
            ),
            DialogFormField.Toggle("noNukes", I18n.battleroom.noNukes(), options.noNukes),
            DialogFormField.Toggle("sharedControl", I18n.battleroom.sharedControl(), options.sharedControl),
            DialogFormField.Toggle("allowSpectators", I18n.battleroom.allowSpectators(), options.allowSpectators),
            DialogFormField.Toggle("teamLock", I18n.battleroom.teamLock(), options.teamLock),
            DialogFormField.Toggle("roomLocked", I18n.battleroom.lockRoom(), options.roomLock),
            DialogFormField.Toggle("fixedAllyTeams", I18n.battleroom.fixedAllyTeams(), options.fixedAllyTeams),
        ),
    )

internal fun playerConfigForm(player: BattleRoomPlayer, isHost: Boolean = false): DialogForm {
    val spawn = player.spawnLabel.toIntOrNull() ?: 1
    val baseFields = listOf(
        DialogFormField.Choice(
            id = "spawn",
            label = I18n.battleroom.spawnPoint(),
            options = (1..10).map { DialogFormOption(it.toString(), it.toString()) } +
                    DialogFormOption(I18n.battleroom.spectator(), BATTLE_ROOM_SPECTATOR_SPAWN_VALUE.toString()),
            selectedIndex = if (player.isSpectator) 10 else (spawn - 1).coerceIn(0, 9),
        ),
        DialogFormField.Choice(
            id = "team",
            label = I18n.battleroom.heading.team(),
            options = listOf(DialogFormOption(I18n.battleroom.auto(), BATTLE_ROOM_AUTO_TEAM_VALUE.toString())) +
                    (1..10).map { DialogFormOption(teamLabelFor(it - 1), it.toString()) },
            selectedIndex = 0,
        ),
    )
    val hostFields = if (isHost) playerOverrideFields() else emptyList()
    return DialogForm(fields = baseFields + hostFields)
}

private fun playerOverrideFields(): List<DialogFormField> {
    val clear = BATTLE_ROOM_CLEAR_OVERRIDE.toString()
    return listOf(
        DialogFormField.Choice(
            id = "startingUnits",
            label = I18n.battleroom.startingUnits.overrideLabel(),
            options = listOf(DialogFormOption(I18n.battleroom.defaultValue(), clear)) +
                    (1..5).map { DialogFormOption(startingUnitsLabel(it), it.toString()) },
            selectedIndex = 0,
        ),
        DialogFormField.Choice(
            id = "aiDifficulty",
            label = I18n.battleroom.aiDifficulty.overrideLabel(),
            options = listOf(DialogFormOption(I18n.battleroom.defaultValue(), clear)) + listOf(
                DialogFormOption(I18n.battleroom.aiDifficulty.veryEasy(), "-2"),
                DialogFormOption(I18n.battleroom.aiDifficulty.easy(), "-1"),
                DialogFormOption(I18n.battleroom.aiDifficulty.medium(), "0"),
                DialogFormOption(I18n.battleroom.aiDifficulty.hard(), "1"),
                DialogFormOption(I18n.battleroom.aiDifficulty.veryHard(), "2"),
                DialogFormOption(I18n.battleroom.aiDifficulty.impossible(), "3"),
            ),
            selectedIndex = 0,
        ),
    )
}

internal fun BattleRoomSnapshot.toBattleRoomModel(
    previewAssetPath: String?,
    chatLines: List<BattleRoomChatLine>,
): BattleRoomModel {
    val requiredRwxFeatures = MapFeatureDetector.requiredFeaturesForMap(room.mapPath)
    val rwxModeLabel = requiredRwxFeatures.ModeLabel()
    return BattleRoomModel(
        info = BattleRoomInfo(
            mapName = if (mapDisplayName == "Unknown map") I18n.multiplayer.unknown() else mapDisplayName,
            mapTypeLabel = localizedMapTypeLabel(mapTypeLabel),
            detailLines = battleRoomDetailLines(
                settings = room.options,
                networkStatusText = networkStatusText,
                requiredModsSummary = requiredModsSummary,
                customStartingUnitsLabel = startingUnitsLabel,
            ),
            mapPreviewAssetPath = previewAssetPath,
            rwxModeLabel = rwxModeLabel,
            rwxCompatibilityLabel = rwxModeLabel?.let {
                if (isNetworkMultiplayer) {
                    if (rwxP2PSession) I18n.battleroom.p2pEnabled() else I18n.battleroom.originalBlocked()
                } else {
                    I18n.battleroom.singlePlayer()
                }
            },
        ),
        players = players,
        chatLines = chatLines.toList(),
        isHost = isHost,
    )
}

internal fun battleRoomDetailLines(
    settings: GameRoomSettings,
    networkStatusText: String? = null,
    requiredModsSummary: String? = null,
    customStartingUnitsLabel: String? = null,
): List<String> {
    val statusLines = networkStatusText
        ?.takeIf { it.isNotBlank() }
        ?.let(::originalBattleRoomStatusLines)
    val lines = statusLines ?: buildList {
        add(I18n.battleroom.detail.startingCredits(startingCreditsLabel(settings.startingCredits)))
        add(I18n.battleroom.detail.fog(fogLabel(settings.fogMode)))
        if (settings.startingUnits != 1) {
            add(I18n.battleroom.detail.startingUnits(startingUnitsLabel(settings.startingUnits, customStartingUnitsLabel)))
        }
        if (settings.incomeMultiplier != 1.0f) {
            add(I18n.battleroom.detail.income(incomeLabel(settings.incomeMultiplier)))
        }
        if (settings.noNukes) add(I18n.battleroom.noNukes())
        if (settings.sharedControl) add(I18n.battleroom.detail.sharedControl())
        if (settings.roomLock) add(I18n.battleroom.detail.roomLocked())
        if (settings.fixedAllyTeams) add(I18n.battleroom.fixedAllyTeams())
    }
    if (requiredModsSummary.isNullOrBlank() || lines.any { it.contains("Required Mods") || it == I18n.battleroom.detail.requiredModsHeader() }) {
        return lines
    }
    return lines + I18n.battleroom.detail.requiredMods(requiredModsSummary)
}

internal fun originalBattleRoomStatusLines(statusText: String): List<String> =
    statusText.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.startsWith("Game Mode:") || it.startsWith("Map:") }
        .map(::localizeLegacyRoomLine)
        .toList()

internal fun startingCreditsLabel(code: Int): String =
    when (code) {
        0 -> I18n.battleroom.startingCredits.defaultValue()
        1 -> "$0"
        2 -> "$1000"
        3 -> "$2000"
        4 -> "$5000"
        5 -> "$10000"
        6 -> "$50000"
        7 -> "$100000"
        8 -> "$200000"
        else -> "$999"
    }

internal fun startingUnitsLabel(value: Int, customLabel: String? = null): String =
    when (value) {
        1 -> I18n.battleroom.startingUnits.normal()
        2 -> I18n.battleroom.startingUnits.smallArmy()
        3 -> I18n.battleroom.startingUnits.engineers()
        4 -> I18n.battleroom.startingUnits.engineersNoBase()
        5 -> I18n.battleroom.startingUnits.spider()
        9 -> I18n.battleroom.startingUnits.custom()
        else -> customLabel?.takeIf { it != "Unknown" } ?: I18n.multiplayer.unknown()
    }

private fun incomeLabel(value: Float): String =
    if (value == value.toInt().toFloat()) {
        value.toInt().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", value)
    }

private fun fogLabel(fogMode: Int): String =
    when (fogMode) {
        0 -> I18n.battleroom.fog.none()
        1 -> I18n.battleroom.fog.basic()
        2 -> I18n.battleroom.fog.lineOfSight()
        else -> I18n.multiplayer.unknown()
    }

internal const val DEFAULT_MAX_PLAYERS: Int = 10

/** A cached selection is only a preview source for the exact map reported by the room. */
internal fun battleRoomMapPreview(
    mapPath: String,
    isSavedGame: Boolean,
    selectedMapPath: String?,
    selectedPreviewPath: String?,
    resolvePreview: (String) -> String?,
): String? {
    if (isSavedGame || mapPath.isBlank()) return null
    if (selectedMapPath == mapPath && selectedPreviewPath != null) return selectedPreviewPath
    return resolvePreview(mapPath)
}
