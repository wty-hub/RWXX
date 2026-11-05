package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.BattleRoomCoreConfig
import io.github.rwx.session.BattleRoomLaunchConfig
import io.github.rwx.session.BattleRoomSnapshot
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CompletableFuture

class BattleRoomLaunchControllerTest {
    @Test
    fun `leaving room before start acknowledgement does not schedule adoption`() {
        val session = RecordingSession(resumable = false, deferred = true)
        val fixture = Fixture(session)
        fixture.controller.startBattleRoomGame()
        fixture.controller.cancelPendingStart()
        session.completeNextTask()
        session.drainSessionCompletions()
        assertEquals(listOf("start room"), session.events)
        assertTrue(session.pendingTasks.isEmpty())
    }

    @Test
    fun `being kicked before adoption acknowledgement stays in the lobby`() {
        val session = RecordingSession(resumable = false, deferred = true)
        val fixture = Fixture(session)
        fixture.controller.startBattleRoomGame()
        session.completeNextTask()
        session.drainSessionCompletions()
        BattleRoomCloseController(
            cancelPendingStart = fixture.controller::cancelPendingStart,
            cancelPendingJoin = {},
            closeRoom = {},
            exitToLobby = { fixture.screen = AppScreen.Multiplayer },
            showMessage = {},
        ).handle("Kicked", "Kicked by host")
        session.completeNextTask()
        session.drainSessionCompletions()
        assertEquals(AppScreen.Multiplayer, fixture.screen)
        assertEquals(listOf("start room", "load started room"), session.events)
    }

    @Test
    fun `confirmation from a closed room cannot launch its previous config`() {
        val session = RecordingSession(resumable = true)
        val fixture = Fixture(session)
        fixture.controller.startBattleRoomGame()
        fixture.controller.cancelPendingStart()
        assertNotNull(fixture.confirmStart).invoke()
        assertTrue(session.events.isEmpty())
        assertEquals(0, fixture.fallbackStarts)
    }

    @Test
    fun `previous acknowledgement cannot clear a later room start guard`() {
        val session = RecordingSession(resumable = false, deferred = true)
        val fixture = Fixture(session)
        fixture.controller.startBattleRoomGame()
        fixture.controller.cancelPendingStart()
        fixture.controller.startBattleRoomGame()
        session.completeNextTask()
        session.drainSessionCompletions()
        fixture.controller.startBattleRoomGame()
        assertEquals(1, session.pendingTasks.size)
        session.completeNextTask()
        session.drainSessionCompletions()
        session.completeNextTask()
        session.drainSessionCompletions()
        assertEquals(listOf("start room", "start room", "load started room", "show game"), session.events)
    }

    @Test
    fun `independent owner acknowledges start and adoption before UI navigation`() {
        val session = RecordingSession(resumable = false, deferred = true)
        val fixture = Fixture(session)
        fixture.controller.startBattleRoomGame()
        fixture.controller.startBattleRoomGame()
        assertEquals(1, session.pendingTasks.size)
        assertTrue(session.events.isEmpty())

        session.completeNextTask()
        assertEquals(listOf("start room"), session.events)
        assertTrue(session.pendingTasks.isEmpty())
        session.drainSessionCompletions()
        assertEquals(1, session.pendingTasks.size)
        assertEquals(listOf("start room"), session.events)

        session.completeNextTask()
        assertEquals(listOf("start room", "load started room"), session.events)
        session.drainSessionCompletions()
        assertEquals(listOf("start room", "load started room", "show game"), session.events)
    }

    @Test
    fun `confirmed new game starts the live sandbox room instead of reusing the old map`() {
        val session = RecordingSession(resumable = true)
        val fixture = Fixture(session)

        fixture.controller.startBattleRoomGame()

        assertEquals(emptyList(), session.events)
        assertNull(fixture.fallbackConfig)
        assertNotNull(fixture.confirmStart).invoke()

        assertEquals(listOf("start room", "load started room", "show game"), session.events)
        assertEquals(0, fixture.fallbackStarts)
    }

    @Test
    fun `first game and confirmed replacement use the same live room start`() {
        val session = RecordingSession(resumable = false)
        val fixture = Fixture(session)

        fixture.controller.startBattleRoomGame()

        assertNull(fixture.confirmStart)
        assertEquals(listOf("start room", "load started room", "show game"), session.events)
        assertEquals(0, fixture.fallbackStarts)
    }

    @Test
    fun `confirmed draft sandbox retains its launch config when no live room exists`() {
        val session = RecordingSession(resumable = true, live = false)
        val config = BattleRoomLaunchConfig(sandbox = true, room = session.snapshot.room)
        session.stage(config)
        val fixture = Fixture(session)

        fixture.controller.startBattleRoomGame()
        assertNotNull(fixture.confirmStart).invoke()

        assertEquals(config, fixture.fallbackConfig)
        assertEquals(1, fixture.fallbackStarts)
        assertEquals(emptyList(), session.events)
    }

    private class Fixture(val session: RecordingSession) {
        var screen = AppScreen.BattleRoom
        var confirmStart: (() -> Unit)? = null
        var fallbackStarts = 0
        var fallbackConfig: BattleRoomLaunchConfig? = null
        val controller = BattleRoomLaunchController(
            gameSession = session,
            storage = { null },
            viewport = { KoolCanvasViewport(1280, 720) },
            currentScreen = { screen },
            showStartNewGameDialog = { confirmStart = it },
            enterRwGame = { startNew, config ->
                check(startNew)
                fallbackStarts++
                fallbackConfig = config
            },
            clearPendingRwStartState = {},
            clearPendingStartState = {},
            setPendingStartState = { _, _ -> error("Started map should be ready") },
            navigateToInGame = {
                screen = AppScreen.InGame
                session.events += "show game"
            },
            showUnavailableDialog = { error(it) },
        )
    }

    private class RecordingSession(val resumable: Boolean, val live: Boolean = true, val deferred: Boolean = false) : GameSession() {
        override val usesIndependentEngineLoop get() = deferred
        val pendingTasks = mutableListOf<() -> Unit>()
        override fun <T> submitSessionTask(action: () -> T): CompletableFuture<T> {
            if (!deferred) return super.submitSessionTask(action)
            val future = CompletableFuture<T>()
            pendingTasks += { runCatching(action).onSuccess(future::complete).onFailure(future::completeExceptionally) }
            return future
        }
        fun completeNextTask() { pendingTasks.removeAt(0).invoke() }
        override val rendererMode = object : RendererMode { override val id = "test" }
        val events = mutableListOf<String>()
        val snapshot = BattleRoomSnapshot(
            room = BattleRoomCoreConfig("sandbox.tmx", GameRoomSettings()),
            mapDisplayName = "Sandbox",
            mapTypeLabel = "Sandbox",
            players = emptyList(),
            isHost = true,
        )

        fun stage(config: BattleRoomLaunchConfig) {
            updateLoadState { it.copy(pendingRendererBattleRoomConfig = config) }
        }

        override fun canResume() = resumable
        override fun currentBattleRoom(refreshNetworkStatus: Boolean) = snapshot
        override fun isBattleRoomLive() = live
        override fun startBattleRoom(): Boolean {
            events += "start room"
            return true
        }
        override fun adoptStartedGameFromEngine(viewport: KoolCanvasViewport): Boolean {
            events += "load started room"
            return true
        }
        override fun isMapLoaded(mapPath: String?) = "load started room" in events
        override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1280, 720), emptyList())
        override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine = error("Not needed")
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
    }
}
