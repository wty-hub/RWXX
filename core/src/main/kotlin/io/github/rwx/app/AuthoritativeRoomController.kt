package io.github.rwx.app

import de.fabmax.kool.util.FrontendScope
import io.github.rwx.AuthoritativeRoomLauncher
import io.github.rwx.i18n.I18n
import io.github.rwx.net.timeline.TimelineInvitation
import io.github.rwx.ui.model.Dialog
import io.github.rwx.ui.model.DialogButton
import io.github.rwx.ui.model.DialogForm
import io.github.rwx.ui.model.DialogFormField
import io.github.rwx.ui.model.DialogTextInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

/** Only launches the platform capability: never sends a new-protocol request to the legacy session. */
internal class AuthoritativeRoomController(
    private val launcher: () -> AuthoritativeRoomLauncher?,
    private val initialPlayerName: () -> String,
    private val showDialog: (Dialog) -> Unit,
    private val launchUi: (suspend () -> Unit) -> Unit = { action ->
        FrontendScope.launch {
            // Dialog buttons dismiss their old dialog after invoking the action. Present the next
            // dialog on a later turn so that dismissal cannot hide validation/status/results.
            yield()
            action()
        }
        Unit
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val busy = AtomicBoolean(false)
    private var lastEndpoint = ""
    val isBusy: Boolean get() = busy.get()

    fun showHost() {
        if (isBusy) return
        if (launcher() == null) return unavailable()
        showDialog(Dialog(
            title = I18n.multiplayer.authority.hostTitle(),
            message = I18n.multiplayer.authority.hostInfo(),
            form = DialogForm(listOf(
                DialogFormField.Text("endpoint", I18n.multiplayer.authority.endpointLabel(), lastEndpoint,
                    I18n.multiplayer.authority.endpointHint()),
                playerNameField(),
            )),
            buttons = listOf(DialogButton(I18n.multiplayer.authority.create(), onFormPress = ::submitHost),
                DialogButton(I18n.common.cancel())),
            scrollableMessage = true,
        ))
    }

    fun showJoin() {
        if (isBusy) return
        if (launcher() == null) return unavailable()
        showDialog(Dialog(
            title = I18n.multiplayer.authority.joinTitle(),
            message = I18n.multiplayer.authority.joinInfo(),
            form = DialogForm(listOf(
                DialogFormField.Text("invitation", I18n.multiplayer.authority.invitationLabel(), "",
                    I18n.multiplayer.authority.invitationHint()),
                playerNameField(),
            )),
            buttons = listOf(DialogButton(I18n.common.join(), onFormPress = ::submitJoin),
                DialogButton(I18n.common.cancel())),
            scrollableMessage = true,
        ))
    }

    private fun playerNameField() = DialogFormField.Text("playerName", I18n.multiplayer.authority.playerNameLabel(),
        initialPlayerName().ifBlank { "Player" })

    private fun submitHost(values: Map<String, String>) = submit(hosting = true) { platform ->
        val name = checkedName(values["playerName"].orEmpty())
        val endpoint = values["endpoint"].orEmpty().trim()
        try { TimelineInvitation.endpoint(endpoint) }
        catch (_: IllegalArgumentException) { throw InvalidForm(I18n.multiplayer.authority.invalidEndpoint()) }
        val room = withContext(ioDispatcher) { platform.host(endpoint, name) }
        lastEndpoint = endpoint
        showDialog(Dialog(
            title = I18n.multiplayer.authority.invitationTitle(),
            message = I18n.multiplayer.authority.invitationInfo(),
            textInput = DialogTextInput(initialText = room.invitation, focusOnShow = true),
            buttons = listOf(DialogButton(I18n.common.close())),
            scrollableMessage = true,
        ))
    }

    private fun submitJoin(values: Map<String, String>) = submit(hosting = false) { platform ->
        val name = checkedName(values["playerName"].orEmpty())
        val invitation = values["invitation"].orEmpty().trim()
        try { TimelineInvitation.parse(invitation) }
        catch (_: IllegalArgumentException) { throw InvalidForm(I18n.multiplayer.authority.invalidInvitation()) }
        withContext(ioDispatcher) { platform.join(invitation, name) }
        message(I18n.multiplayer.authority.launchedTitle(), I18n.multiplayer.authority.launchedInfo())
    }

    private fun submit(hosting: Boolean, action: suspend (AuthoritativeRoomLauncher) -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        launchUi {
            try {
                val platform = launcher() ?: return@launchUi unavailable()
                message(I18n.multiplayer.authority.starting(), I18n.multiplayer.authority.opening())
                action(platform)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: InvalidForm) {
                message(I18n.common.actionUnavailable(), invalid.safeMessage)
            } catch (_: Exception) {
                // Exceptions can contain the entire invitation, credentials or a ProcessBuilder argv.
                // Do not put their text/stack trace in UI, logs, crash telemetry or remembered rooms.
                message(I18n.multiplayer.connectionError(), if (hosting) I18n.multiplayer.authority.failedHost()
                    else I18n.multiplayer.authority.failedJoin())
            } finally { busy.set(false) }
        }
    }

    private fun checkedName(raw: String): String = raw.trim().also { name ->
        if (name.isEmpty() || name.toByteArray(Charsets.UTF_8).size > 128 || name.any(Character::isISOControl)) {
            throw InvalidForm(I18n.multiplayer.authority.invalidName())
        }
    }

    private fun unavailable() = message(I18n.common.actionUnavailable(), I18n.multiplayer.authority.unavailable())
    private fun message(title: String, text: String) = showDialog(Dialog(title, text))
    private class InvalidForm(val safeMessage: String) : IllegalArgumentException()
}
