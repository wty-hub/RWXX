package io.github.rwx.app

import io.github.rwx.AuthoritativeHostLaunch
import io.github.rwx.AuthoritativeRoomLauncher
import io.github.rwx.i18n.I18n
import io.github.rwx.net.timeline.TimelineInvitation
import io.github.rwx.ui.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AuthoritativeRoomControllerTest {
    @Test fun `new room actions remain separate from the original protocol actions`() {
        assertEquals(MultiplayerOutcome.HostAuthoritativeRequested,
            MultiplayerNavigation.outcomeFor(MultiplayerAction.HostAuthoritative))
        assertEquals(MultiplayerOutcome.JoinAuthoritativeRequested,
            MultiplayerNavigation.outcomeFor(MultiplayerAction.JoinAuthoritative))
        assertEquals(MultiplayerOutcome.HostGameRequested, MultiplayerNavigation.outcomeFor(MultiplayerAction.HostGame))
        assertEquals(MultiplayerOutcome.JoinDirectRequested, MultiplayerNavigation.outcomeFor(MultiplayerAction.JoinDirect))
    }

    @Test fun `hosting is deferred blocks duplicate submissions and exposes only the returned invitation`() {
        val fixture = Fixture()
        fixture.controller.showHost()
        val dialog = fixture.dialogs.single()
        assertEquals(listOf("endpoint", "playerName"), dialog.form!!.fields.map { it.id })
        val submit = assertNotNull(dialog.buttons.first().onFormPress)
        val form = mapOf("endpoint" to " 192.168.1.2:5124 ", "playerName" to " Alice ")
        submit(form)
        submit(form)
        fixture.controller.showJoin()
        assertTrue(fixture.controller.isBusy)
        assertEquals(1, fixture.pending.size)
        assertTrue(fixture.hostCalls.isEmpty(), "Launching must not run in the dialog button callback")
        fixture.runPending()
        assertEquals(listOf("192.168.1.2:5124" to "Alice"), fixture.hostCalls)
        assertEquals(INVITATION, fixture.dialogs.last().textInput!!.initialText)
        assertFalse(fixture.controller.isBusy)
        assertTrue(fixture.joinCalls.isEmpty())
    }

    @Test fun `join errors never expose the invitation even when the platform exception contains it`() {
        val fixture = Fixture().apply { failJoin = true }
        fixture.controller.showJoin()
        val submit = fixture.dialogs.last().buttons.first().onFormPress!!
        submit(mapOf("invitation" to " $INVITATION ", "playerName" to "Bob"))
        fixture.runPending()
        assertEquals(listOf(INVITATION to "Bob"), fixture.joinCalls)
        val result = fixture.dialogs.last()
        assertEquals(I18n.multiplayer.authority.failedJoin(), result.message)
        assertFalse(result.toString().contains(INVITATION))
        assertNull(result.textInput)
        assertFalse(fixture.controller.isBusy)
        fixture.controller.showJoin()
        val input = fixture.dialogs.last().form!!.fields.filterIsInstance<DialogFormField.Text>()
            .single { it.id == "invitation" }
        assertEquals("", input.initialText, "An invitation must not be remembered after failure")
    }

    @Test fun `unsupported platforms explain the capability and never schedule a launcher`() {
        val fixture = Fixture().apply { supported = false }
        fixture.controller.showHost()
        fixture.controller.showJoin()
        assertEquals(2, fixture.dialogs.size)
        assertTrue(fixture.dialogs.all { it.message == I18n.multiplayer.authority.unavailable() })
        assertTrue(fixture.pending.isEmpty())
        assertTrue(fixture.hostCalls.isEmpty() && fixture.joinCalls.isEmpty())
    }

    @Test fun `invalid player input cannot reach either platform operation and leaves retry available`() {
        for (form in listOf(
            mapOf("endpoint" to "", "playerName" to "Alice"),
            mapOf("endpoint" to "host:65536", "playerName" to "Alice"),
            mapOf("endpoint" to "alice@host:5124", "playerName" to "Alice"),
            mapOf("endpoint" to "host:5124", "playerName" to ""),
            mapOf("endpoint" to "host:5124", "playerName" to "a\u0000b"),
            mapOf("endpoint" to "host:5124", "playerName" to "界".repeat(43)),
        )) {
            val fixture = Fixture()
            fixture.controller.showHost()
            fixture.dialogs.last().buttons.first().onFormPress!!(form)
            fixture.runPending()
            assertTrue(fixture.hostCalls.isEmpty())
            assertFalse(fixture.controller.isBusy)
        }
        for (invitation in listOf("", "invalid-$INVITATION", INVITATION.substringBefore('#'))) {
            val fixture = Fixture()
            fixture.controller.showJoin()
            fixture.dialogs.last().buttons.first().onFormPress!!(mapOf("invitation" to invitation, "playerName" to "Bob"))
            fixture.runPending()
            assertTrue(fixture.joinCalls.isEmpty())
            assertEquals(I18n.multiplayer.authority.invalidInvitation(), fixture.dialogs.last().message)
            assertFalse(fixture.controller.isBusy)
        }
    }

    private class Fixture {
        val dialogs = mutableListOf<Dialog>()
        val pending = ArrayDeque<suspend () -> Unit>()
        val hostCalls = mutableListOf<Pair<String, String>>()
        val joinCalls = mutableListOf<Pair<String, String>>()
        var supported = true
        var failJoin = false
        private val launcher = object : AuthoritativeRoomLauncher {
            override fun host(endpoint: String, playerName: String): AuthoritativeHostLaunch {
                hostCalls += endpoint to playerName
                return AuthoritativeHostLaunch(INVITATION)
            }
            override fun join(invitation: String, playerName: String) {
                joinCalls += invitation to playerName
                if (failJoin) error("Failed to launch with secret invitation: $invitation")
            }
        }
        val controller = AuthoritativeRoomController(
            launcher = { launcher.takeIf { supported } },
            initialPlayerName = { "Alice" },
            showDialog = dialogs::add,
            launchUi = { pending.addLast(it) },
            ioDispatcher = Dispatchers.Unconfined,
        )
        fun runPending() = runBlocking { pending.removeFirst().invoke() }
    }

    private companion object {
        val INVITATION = TimelineInvitation("192.168.1.2", 5124, "ab".repeat(32), "private-seat-".repeat(4)).encode()
    }
}
