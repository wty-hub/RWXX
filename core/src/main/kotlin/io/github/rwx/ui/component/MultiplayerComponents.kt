package io.github.rwx.ui.component

import io.github.rwx.ui.smallCornerRadius
import io.github.rwx.ui.mediumCornerRadius
import de.fabmax.kool.modules.ui2.*
import io.github.rwx.i18n.I18n
import io.github.rwx.ui.ColorSchemeDefinition
import io.github.rwx.ui.model.MultiplayerAccessFilter
import io.github.rwx.ui.model.MultiplayerLobbyKind
import io.github.rwx.ui.model.MultiplayerModFilter
import io.github.rwx.ui.model.MultiplayerRoomBrowser
import io.github.rwx.ui.model.MultiplayerRoomItem
import io.github.rwx.ui.model.MultiplayerRoomListModel
import io.github.rwx.ui.model.MultiplayerSlotFilter
import io.github.rwx.ui.model.MultiplayerStatusFilter
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.fraction
import io.github.rwx.ui.remainingAfter
import io.github.rwx.ui.splitEvenly


fun UiScope.MultiplayerRoomRow(
    room: MultiplayerRoomItem,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.multiplayerRoomRowWidth,
    onPressed: () -> Unit,
) {
    val compact = contentWidth.value < MULTIPLAYER_COMPACT_WIDTH_DP
    val hovered = remember(false)
    val isHovered = hovered.use()
    val background = if (isHovered) theme.palette.surfaceRaised else theme.palette.surfaceSunken
    val border = if (isHovered) theme.palette.primary else theme.palette.borderSubtle
    val rowHeight = if (compact) {
        UiTheme.Layout.menuButtonHeight + Dp(28f)
    } else {
        UiTheme.Layout.menuButtonHeight
    }
    if (compact) {
        Column(width = contentWidth, height = rowHeight) {
            modifier
                .margin(UiTheme.Spacing.xs)
                .padding(horizontal = UiTheme.Spacing.md, vertical = UiTheme.Spacing.xs)
                .background(RoundRectBackground(background, theme.smallCornerRadius))
                .border(RoundRectBorder(border, theme.smallCornerRadius, Dp(1f)))
                .onEnter { hovered.value = true }
                .onExit { hovered.value = false }
                .onClick { onPressed() }

            EmojiAwareText(
                text = "${room.hostName} | ${room.mapName}",
                textFont = UiTheme.Fonts.bodySmall,
                textColor = theme.palette.textPrimary,
                contentWidth = Grow.Std,
                contentHeight = Dp(34f),
                wrap = true,
            )
            Text(roomStatusLabel(room)) {
                modifier
                    .width(Grow.Std)
                    .height(Dp(28f))
                    .font(UiTheme.Fonts.caption)
                    .textAlign(AlignmentX.Start, AlignmentY.Center)
                    .textColor(theme.palette.textSecondary)
            }
        }
        return
    }

    Row(width = contentWidth, height = rowHeight) {
        modifier
            .margin(UiTheme.Spacing.xs)
            .padding(horizontal = UiTheme.Spacing.md)
            .background(RoundRectBackground(background, theme.smallCornerRadius))
            .border(RoundRectBorder(border, theme.smallCornerRadius, Dp(1f)))
            .onEnter { hovered.value = true }
            .onExit { hovered.value = false }
            .onClick { onPressed() }

        multiplayerCell(room.hostName, contentWidth.fraction(0.18f, Dp(120f), Dp(280f)), theme, AlignmentX.Start)
        multiplayerCell(room.mapName, Grow.Std, theme, AlignmentX.Start)
        multiplayerCell(room.playersLabel, Dp(128f), theme, AlignmentX.Center)
        multiplayerCell(room.localizedStateLabel, Dp(116f), theme, AlignmentX.Center)
        multiplayerCell(room.versionLabel, Dp(100f), theme, AlignmentX.Center)
        multiplayerCell(room.localizedTransportLabel, Dp(120f), theme, AlignmentX.Center)
        multiplayerCell(roomMarkers(room), Dp(56f), theme, AlignmentX.Center)
    }
}


fun UiScope.MultiplayerRoomList(
    model: MultiplayerRoomListModel,
    theme: ColorSchemeDefinition,
    contentWidth: Dp = UiTheme.Layout.multiplayerRoomRowWidth,
    viewportHeight: Dp = UiTheme.Layout.scrollViewportHeight,
    actions: MultiplayerRoomListActions,
) {
    val searchText = remember("")
    val statusIndex = remember(0)
    val slotIndex = remember(0)
    val accessIndex = remember(0)
    val modIndex = remember(0)
    val statusFilters = MultiplayerStatusFilter.entries
    val slotFilters = MultiplayerSlotFilter.entries
    val accessFilters = MultiplayerAccessFilter.entries
    val modFilters = MultiplayerModFilter.entries
    val selectedStatus = statusFilters[statusIndex.use().coerceIn(statusFilters.indices)]
    val selectedSlots = slotFilters[slotIndex.use().coerceIn(slotFilters.indices)]
    val selectedAccess = accessFilters[accessIndex.use().coerceIn(accessFilters.indices)]
    val selectedMods = modFilters[modIndex.use().coerceIn(modFilters.indices)]
    val visibleRooms = MultiplayerRoomBrowser.visibleRooms(
        rooms = model.rooms,
        query = searchText.use(),
        status = selectedStatus,
        slots = selectedSlots,
        access = selectedAccess,
        mods = selectedMods,
    )

    MultiplayerLobbySwitcher(
        selected = model.lobbyKind,
        theme = theme,
        contentWidth = contentWidth,
        onBack = actions.onBack,
        onConfigure = actions.onConfigure,
        onSelected = actions.onSwitchLobby,
    )
    MultiplayerRoomFilterBar(
        searchText = searchText.use(),
        statusFilters = statusFilters,
        selectedStatusIndex = statusIndex.use().coerceIn(statusFilters.indices),
        slotFilters = slotFilters,
        selectedSlotIndex = slotIndex.use().coerceIn(slotFilters.indices),
        accessFilters = accessFilters,
        selectedAccessIndex = accessIndex.use().coerceIn(accessFilters.indices),
        modFilters = modFilters,
        selectedModIndex = modIndex.use().coerceIn(modFilters.indices),
        theme = theme,
        contentWidth = contentWidth,
        onSearchTextChanged = { searchText.value = it },
        onStatusSelected = { statusIndex.value = it },
        onSlotsSelected = { slotIndex.value = it },
        onAccessSelected = { accessIndex.value = it },
        onModsSelected = { modIndex.value = it },
    )

    // Keep the room viewport and status lane mounted in every loading / filter state.
    // Otherwise the centered menu shrinks before the first refresh and the footer jumps.
    Box(width = contentWidth, height = viewportHeight) {
        ScrollableVerticalList(
            items = visibleRooms,
            theme = theme,
            width = Grow.Std,
            height = Grow.Std,
        ) { room ->
            MultiplayerRoomRow(room, theme, contentWidth) {
                actions.onJoinRoom(room.roomId)
            }
        }
        if (visibleRooms.isEmpty()) {
            Text(if (model.rooms.isEmpty()) model.statusText else I18n.multiplayer.noMatches()) {
                modifier
                    .width(Grow.Std)
                    .height(Grow.Std)
                    .font(UiTheme.Fonts.bodySmall)
                    .textAlign(AlignmentX.Center, AlignmentY.Center)
                    .textColor(theme.palette.textSecondary)
            }
        }
    }

    Box(width = contentWidth, height = MULTIPLAYER_STATUS_HEIGHT) {
        if (visibleRooms.isNotEmpty() && model.statusText.isNotEmpty()) {
            Text(model.statusText) {
                modifier
                    .width(Grow.Std)
                    .height(Grow.Std)
                    .font(UiTheme.Fonts.caption)
                    .textAlign(AlignmentX.Center, AlignmentY.Center)
                    .textColor(theme.palette.textSecondary)
            }
        }
    }

    val buttonWidth = if (contentWidth.value < MULTIPLAYER_COMPACT_WIDTH_DP) {
        contentWidth.remainingAfter(UiTheme.Spacing.sm)
    } else {
        contentWidth.splitEvenly(
            count = 3,
            totalGap = Dp(3f * UiTheme.Spacing.sm.value),
            minWidth = Dp(180f),
            maxWidth = UiTheme.Layout.menuButtonWidth,
        )
    }
    val hostAuthoritative = actions.onHostAuthoritative
    val joinAuthoritative = actions.onJoinAuthoritative
    if (contentWidth.value < MULTIPLAYER_COMPACT_WIDTH_DP) {
        Column(width = contentWidth) {
            TextIconButton(I18n.multiplayer.refresh(), Icon.Refresh, buttonWidth, theme) {
                actions.onRefresh()
            }
            TextIconButton(I18n.common.join(), Icon.Multiplayer, buttonWidth, theme) {
                actions.onJoinDirect()
            }
            TextIconButton(I18n.multiplayer.hostGame(), Icon.Start, buttonWidth, theme) {
                actions.onHostGame()
            }
            TextIconButton(I18n.multiplayer.rejoinLastGame(), Icon.Back, buttonWidth, theme) {
                actions.onRejoinLastGame()
            }
            if (hostAuthoritative != null && joinAuthoritative != null) {
                TextIconButton(I18n.multiplayer.authority.hostButton(), Icon.Start, buttonWidth, theme) {
                    hostAuthoritative()
                }
                TextIconButton(I18n.multiplayer.authority.joinButton(), Icon.Multiplayer, buttonWidth, theme) {
                    joinAuthoritative()
                }
            }
        }
    } else {
        Row {
            modifier.alignX(AlignmentX.Center)
            TextIconButton(I18n.multiplayer.refresh(), Icon.Refresh, buttonWidth, theme) {
                actions.onRefresh()
            }
            TextIconButton(I18n.common.join(), Icon.Multiplayer, buttonWidth, theme) {
                actions.onJoinDirect()
            }
            TextIconButton(I18n.multiplayer.hostGame(), Icon.Start, buttonWidth, theme) {
                actions.onHostGame()
            }
        }
        Row {
            modifier.alignX(AlignmentX.Center)
            TextIconButton(I18n.multiplayer.rejoinLastGame(), Icon.Back, UiTheme.Layout.menuButtonWidth, theme) {
                actions.onRejoinLastGame()
            }
        }
        if (hostAuthoritative != null && joinAuthoritative != null) {
            Row {
                modifier.alignX(AlignmentX.Center)
                TextIconButton(I18n.multiplayer.authority.hostButton(), Icon.Start, buttonWidth, theme) {
                    hostAuthoritative()
                }
                TextIconButton(I18n.multiplayer.authority.joinButton(), Icon.Multiplayer, buttonWidth, theme) {
                    joinAuthoritative()
                }
            }
        }
    }
}

private fun UiScope.MultiplayerRoomFilterBar(
    searchText: String,
    statusFilters: List<MultiplayerStatusFilter>,
    selectedStatusIndex: Int,
    slotFilters: List<MultiplayerSlotFilter>,
    selectedSlotIndex: Int,
    accessFilters: List<MultiplayerAccessFilter>,
    selectedAccessIndex: Int,
    modFilters: List<MultiplayerModFilter>,
    selectedModIndex: Int,
    theme: ColorSchemeDefinition,
    contentWidth: Dp,
    onSearchTextChanged: (String) -> Unit,
    onStatusSelected: (Int) -> Unit,
    onSlotsSelected: (Int) -> Unit,
    onAccessSelected: (Int) -> Unit,
    onModsSelected: (Int) -> Unit,
) {
    val compact = contentWidth.value < MULTIPLAYER_COMPACT_WIDTH_DP
    if (compact) {
        Column(width = contentWidth) {
            modifier.margin(bottom = UiTheme.Spacing.sm)
            MultiplayerSearchField(searchText, contentWidth, theme, onSearchTextChanged)
            Row(width = contentWidth) {
                modifier.margin(top = UiTheme.Spacing.xs)
                val comboWidth = contentWidth.splitEvenly(
                    count = 2,
                    totalGap = UiTheme.Spacing.sm,
                    minWidth = Dp(96f),
                    maxWidth = contentWidth,
                )
                MultiplayerFilterCombo(statusFilters, selectedStatusIndex, comboWidth, theme, onStatusSelected)
                MultiplayerFilterCombo(slotFilters, selectedSlotIndex, comboWidth, theme, onSlotsSelected)
                    .modifier.margin(start = UiTheme.Spacing.sm)
            }
            Row(width = contentWidth) {
                modifier.margin(top = UiTheme.Spacing.xs)
                val comboWidth = contentWidth.splitEvenly(
                    count = 2,
                    totalGap = UiTheme.Spacing.sm,
                    minWidth = Dp(96f),
                    maxWidth = contentWidth,
                )
                MultiplayerFilterCombo(accessFilters, selectedAccessIndex, comboWidth, theme, onAccessSelected)
                MultiplayerFilterCombo(modFilters, selectedModIndex, comboWidth, theme, onModsSelected)
                    .modifier.margin(start = UiTheme.Spacing.sm)
            }
        }
        return
    }

    val gap = UiTheme.Spacing.sm
    val comboGaps = Dp(4f * gap.value)
    val comboWidth = Dp(
        ((contentWidth.value - MULTIPLAYER_SEARCH_MIN_WIDTH_DP - comboGaps.value) / 4f)
            .coerceIn(MULTIPLAYER_FILTER_COMBO_MIN_WIDTH_DP, MULTIPLAYER_FILTER_COMBO_MAX_WIDTH_DP)
    )
    val searchWidth = contentWidth.remainingAfter(Dp(comboWidth.value * 4f + comboGaps.value), Dp(120f))
    Row(width = contentWidth, height = UiTheme.Layout.menuButtonHeight) {
        modifier.margin(bottom = UiTheme.Spacing.sm)
        MultiplayerSearchField(searchText, searchWidth, theme, onSearchTextChanged)
        MultiplayerFilterCombo(statusFilters, selectedStatusIndex, comboWidth, theme, onStatusSelected)
            .modifier.margin(start = gap)
        MultiplayerFilterCombo(slotFilters, selectedSlotIndex, comboWidth, theme, onSlotsSelected)
            .modifier.margin(start = gap)
        MultiplayerFilterCombo(accessFilters, selectedAccessIndex, comboWidth, theme, onAccessSelected)
            .modifier.margin(start = gap)
        MultiplayerFilterCombo(modFilters, selectedModIndex, comboWidth, theme, onModsSelected)
            .modifier.margin(start = gap)
    }
}

private fun UiScope.MultiplayerSearchField(
    searchText: String,
    width: Dp,
    theme: ColorSchemeDefinition,
    onSearchTextChanged: (String) -> Unit,
) {
    Row(width = width, height = UiTheme.Layout.menuButtonHeight) {
        Icon(Icon.Search, UiTheme.Layout.textButtonGlyphSize, theme.palette.primary)
            .modifier
            .alignY(AlignmentY.Center)
            .margin(end = UiTheme.Spacing.xs)
        RwxTextField(searchText) {
            modifier
                .width(width.remainingAfter(Dp(UiTheme.Layout.textButtonGlyphSize.value + UiTheme.Spacing.xs.value)))
                .height(UiTheme.Layout.menuButtonHeight)
                .padding(start = UiTheme.Spacing.sm)
                .hint(I18n.multiplayer.searchHint())
                .font(UiTheme.Fonts.bodySmall)
                .colors(
                    textColor = theme.palette.textPrimary,
                    hintColor = theme.palette.textSecondary,
                    lineColor = theme.palette.borderSubtle,
                    lineColorFocused = theme.palette.primary,
                    cursorColor = theme.palette.primary,
                    selectionColor = theme.palette.primaryContainer,
                )
                .onChange(onSearchTextChanged)
        }
    }
}

private fun UiScope.MultiplayerFilterCombo(
    items: List<Any>,
    selectedIndex: Int,
    width: Dp,
    theme: ColorSchemeDefinition,
    onSelected: (Int) -> Unit,
): UiScope = RwxComboBox {
    modifier
        .width(width)
        .height(UiTheme.Layout.menuButtonHeight)
        .font(UiTheme.Fonts.bodySmall)
        .items(items)
        .selectedIndex(selectedIndex)
        .colors(
            textColor = theme.palette.textPrimary,
            textBackgroundColor = theme.palette.surfaceSunken,
            textBackgroundHoverColor = theme.palette.surfaceRaised,
            expanderColor = theme.palette.primaryContainer,
            expanderHoverColor = theme.palette.primary,
            expanderArrowColor = theme.palette.textPrimary,
        )
        .popupColors(
            popupTextColor = theme.palette.textPrimary,
            popupBackgroundColor = theme.palette.surfaceBase,
            popupHoverColor = theme.palette.primaryContainer,
            popupHoverTextColor = theme.palette.textPrimary,
            popupBorderColor = theme.palette.borderSubtle,
        )
        .onItemSelected(onSelected)
}

private fun UiScope.MultiplayerLobbySwitcher(
    selected: MultiplayerLobbyKind,
    theme: ColorSchemeDefinition,
    contentWidth: Dp,
    onBack: () -> Unit,
    onConfigure: () -> Unit,
    onSelected: (MultiplayerLobbyKind) -> Unit,
) {
    val kinds = MultiplayerLobbyKind.entries.filter { it != MultiplayerLobbyKind.P2P }
    val nameButtonWidth = Dp(196f)
    if (contentWidth.value < MULTIPLAYER_COMPACT_WIDTH_DP) {
        Column(width = contentWidth) {
            modifier.margin(bottom = UiTheme.Spacing.sm)
            Row(width = contentWidth, height = UiTheme.Layout.menuButtonHeight) {
                IconButton(Icon.Back, theme, onPressed = onBack)
                Box(width = Grow.Std, height = Grow.Std) { }
                TextIconButton(
                    label = I18n.multiplayer.configurePlayerName(),
                    icon = Icon.Settings,
                    width = nameButtonWidth,
                    theme = theme,
                    font = UiTheme.Fonts.bodySmall,
                    onPressed = onConfigure,
                )
            }
            kinds.forEach { kind ->
                TextIconButton(
                    label = kind.label,
                    icon = Icon.Refresh,
                    width = contentWidth,
                    theme = theme,
                    emphasized = kind == selected,
                    font = UiTheme.Fonts.bodySmall,
                ) { onSelected(kind) }
            }
        }
        return
    }
    val buttonsWidth = contentWidth.remainingAfter(Dp(UiTheme.Layout.iconButtonSize.value + nameButtonWidth.value))
    val buttonWidth = buttonsWidth.splitEvenly(
        count = kinds.size,
        totalGap = Dp(kinds.size * UiTheme.Spacing.sm.value),
        minWidth = Dp(140f),
        maxWidth = UiTheme.Layout.menuButtonWidth,
    )
    Row(width = contentWidth, height = UiTheme.Layout.menuButtonHeight) {
        modifier.margin(bottom = UiTheme.Spacing.sm)
        IconButton(Icon.Back, theme, onPressed = onBack)
        kinds.forEach { kind ->
            val active = kind == selected
            TextIconButton(
                label = kind.label,
                icon = if (kind == MultiplayerLobbyKind.P2P) Icon.Multiplayer else Icon.Refresh,
                width = buttonWidth,
                theme = theme,
                emphasized = active,
                font = UiTheme.Fonts.bodySmall,
            ) {
                onSelected(kind)
            }
        }
        Row(width = Grow.Std, height = Grow.Std) {
            Box(width = Grow.Std, height = Grow.Std) { }
            TextIconButton(
                label = I18n.multiplayer.configurePlayerName(),
                icon = Icon.Settings,
                width = nameButtonWidth,
                theme = theme,
                font = UiTheme.Fonts.bodySmall,
                onPressed = onConfigure,
            )
        }
    }
}

private fun UiScope.multiplayerCell(
    text: String,
    width: Dimension,
    theme: ColorSchemeDefinition,
    align: AlignmentX,
) {
    Box(width = width, height = UiTheme.Layout.menuButtonHeight) {
        EmojiAwareText(
            text = text,
            textFont = UiTheme.Fonts.bodySmall,
            textColor = theme.palette.textPrimary,
            contentWidth = Grow.Std,
            contentHeight = UiTheme.Layout.menuButtonHeight,
            alignX = align,
            alignY = AlignmentY.Center,
            clip = true,
        )
    }
}

private fun roomStatusLabel(room: MultiplayerRoomItem): String =
    "${room.playersLabel} | ${room.localizedStateLabel} | ${room.versionLabel} | ${room.localizedTransportLabel} ${roomMarkers(room)}"
        .trim()

private fun roomMarkers(room: MultiplayerRoomItem): String = buildString {
    if (room.requiresPassword) append(I18n.multiplayer.passwordMarker())
    if (room.hasMods) {
        if (isNotEmpty()) append(" ")
        append(I18n.multiplayer.modMarker())
    }
}

internal const val MULTIPLAYER_COMPACT_WIDTH_DP: Float = 720f
internal val MULTIPLAYER_STATUS_HEIGHT: Dp = Dp(28f)
private const val MULTIPLAYER_SEARCH_MIN_WIDTH_DP: Float = 200f
private const val MULTIPLAYER_FILTER_COMBO_MIN_WIDTH_DP: Float = 88f
private const val MULTIPLAYER_FILTER_COMBO_MAX_WIDTH_DP: Float = 168f

data class MultiplayerRoomListActions(
    val onJoinRoom: (String) -> Unit,
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSwitchLobby: (MultiplayerLobbyKind) -> Unit,
    val onHostGame: () -> Unit,
    val onJoinDirect: () -> Unit,
    val onRejoinLastGame: () -> Unit,
    val onConfigure: () -> Unit,
    val onHostAuthoritative: (() -> Unit)? = null,
    val onJoinAuthoritative: (() -> Unit)? = null,
)
