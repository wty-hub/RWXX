package io.github.rwx.ui.model

import io.github.rwx.i18n.I18n
import java.util.Locale
import io.github.rwx.p2p.P2PRoomAdvertisement
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiplayerRoomBrowserTest {
    @Test
    fun `P2P display brand keeps the existing discovery protocol`() {
        val originalLocale = I18n.currentLocale
        try {
            I18n.setLocale(Locale.ENGLISH)
            assertEquals("RWXX P2P", MultiplayerLobbyKind.P2P.label)
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE)
            assertEquals("RWXX 点对点联机", MultiplayerLobbyKind.P2P.label)
        } finally {
            I18n.setLocale(originalLocale)
        }
        val advertisement = P2PRoomAdvertisement()
        assertEquals("rwx-p2p-lobby", advertisement.magic)
        assertEquals(1, advertisement.schema)
    }

    private val rooms = listOf(
        room("Alice", "Crossing", state = "battleroom", current = 2, max = 10),
        room("Bob", "Ice Base", state = "ingame", current = 10, max = 10, password = true, mods = true),
        room("Cara", "Crossing", state = "chat", current = 1, max = 4, mods = true),
        room("Dave", "Beach", state = "locked", current = -1, max = 8),
    )

    @Test
    fun `localized room display preserves discovery values and filters`() {
        val originalLocale = I18n.currentLocale
        try {
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE)
            val waitingRoom = rooms.first()
            assertEquals("等待中", waitingRoom.localizedStateLabel)
            assertEquals("局域网", waitingRoom.localizedTransportLabel)
            assertEquals("battleroom", waitingRoom.stateLabel)
            assertEquals("LAN", waitingRoom.transportLabel)
            assertEquals(listOf("Alice"), visible(status = MultiplayerStatusFilter.Waiting).map { it.hostName })
            I18n.setLocale(Locale.ENGLISH)
            assertEquals("Waiting", waitingRoom.localizedStateLabel)
            assertEquals("LAN", waitingRoom.localizedTransportLabel)
        } finally {
            I18n.setLocale(originalLocale)
        }
    }

    @Test
    fun `search matches host or map and keeps the original order`() {
        assertEquals(listOf("Alice", "Cara"), visible(query = "cross").map { it.hostName })
        assertEquals(listOf("Bob"), visible(query = "BASE").map { it.hostName })
        assertEquals(listOf("Alice"), visible(query = "alice").map { it.hostName })
        assertEquals(rooms.map { it.hostName }, visible(query = "  ").map { it.hostName })
    }

    @Test
    fun `status filter separates waiting, in game, and everything else`() {
        assertEquals(listOf("Alice"), visible(status = MultiplayerStatusFilter.Waiting).map { it.hostName })
        assertEquals(listOf("Bob"), visible(status = MultiplayerStatusFilter.InGame).map { it.hostName })
        assertEquals(listOf("Cara", "Dave"), visible(status = MultiplayerStatusFilter.Other).map { it.hostName })
    }

    @Test
    fun `open slots ignore full rooms and unknown player counts`() {
        assertEquals(listOf("Alice", "Cara"), visible(slots = MultiplayerSlotFilter.Open).map { it.hostName })
    }

    @Test
    fun `password and mod filters can be combined with search`() {
        assertEquals(listOf("Bob"), visible(access = MultiplayerAccessFilter.Password).map { it.hostName })
        assertEquals(listOf("Alice", "Cara", "Dave"), visible(access = MultiplayerAccessFilter.Open).map { it.hostName })
        assertEquals(listOf("Bob", "Cara"), visible(mods = MultiplayerModFilter.Required).map { it.hostName })
        assertEquals(listOf("Alice", "Dave"), visible(mods = MultiplayerModFilter.None).map { it.hostName })
        assertEquals(
            listOf("Alice"),
            visible(
                query = "crossing",
                status = MultiplayerStatusFilter.Waiting,
                slots = MultiplayerSlotFilter.Open,
                access = MultiplayerAccessFilter.Open,
                mods = MultiplayerModFilter.None,
            ).map { it.hostName },
        )
    }

    private fun visible(
        query: String = "",
        status: MultiplayerStatusFilter = MultiplayerStatusFilter.All,
        slots: MultiplayerSlotFilter = MultiplayerSlotFilter.All,
        access: MultiplayerAccessFilter = MultiplayerAccessFilter.All,
        mods: MultiplayerModFilter = MultiplayerModFilter.All,
    ): List<MultiplayerRoomItem> = MultiplayerRoomBrowser.visibleRooms(rooms, query, status, slots, access, mods)

    private fun room(
        host: String,
        map: String,
        state: String,
        current: Int,
        max: Int,
        password: Boolean = false,
        mods: Boolean = false,
    ): MultiplayerRoomItem = MultiplayerRoomItem(
        roomId = host,
        hostName = host,
        mapName = map,
        playersLabel = "$current/$max",
        versionLabel = "v1",
        requiresPassword = password,
        hasMods = mods,
        transportLabel = "LAN",
        stateLabel = state,
        currentPlayers = current,
        maxPlayers = max,
    )
}
