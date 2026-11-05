package io.github.rwx.session

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameMode
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.ui.CoreUiEvent
import io.github.rwx.ui.CoreUiEventQueue
import kotlin.test.*

class MultiplayerExitStateTest {
    @Test
    fun `kick packet closes the room with its reason before or after the match starts`() {
        for (started in listOf(false, true)) withEngine { engine ->
            engine.networkEngine = NetworkEngine().apply {
                networkGameActive = true
                gameHasBeenStarted = started
            }
            engine.hasLoadedLevel = started
            engine.isStopped = false
            engine.isPaused = false
            val kick = GameOutputStream().apply { writeStringUTF("Kicked by host") }.buildPacketData(150)
            CoreUiEventQueue.drain {}
            try {
                engine.networkEngine.handlePreregisterInfo(kick)

                assertFalse(engine.hasLoadedLevel)
                assertFalse(engine.networkEngine.networkGameActive)
                assertFalse(engine.networkEngine.gameHasBeenStarted)
                assertTrue(engine.isStopped)
                assertTrue(engine.isPaused)
                val events = mutableListOf<CoreUiEvent>().also { CoreUiEventQueue.drain(it::add) }
                assertEquals(
                    listOf(CoreUiEvent.BattleRoomClosed("Kicked", "Kicked: Kicked by host")),
                    events.filterIsInstance<CoreUiEvent.BattleRoomClosed>(),
                )
            } finally {
                CoreUiEventQueue.drain {}
            }
        }
    }

    @Test
    fun `disconnect unloads and stops the match before the next external frame`() = withEngine { engine ->
        engine.networkEngine = NetworkEngine().apply {
            networkGameActive = true
            gameHasBeenStarted = true
        }
        engine.hasLoadedLevel = true
        engine.isStopped = false
        engine.isPaused = false

        engine.networkEngine.disconnectNetworking("exited")

        assertFalse(engine.hasLoadedLevel)
        assertFalse(engine.networkEngine.networkGameActive)
        assertFalse(engine.networkEngine.gameHasBeenStarted)
        assertTrue(engine.isStopped)
        assertTrue(engine.isPaused)
    }

    @Test
    fun `a new level resumes the engine after disconnect`() = withEngine { engine ->
        engine.networkEngine = NetworkEngine()
        engine.networkEngine.disconnectNetworking("exited")
        assertTrue(engine.isStopped)
        assertTrue(engine.isPaused)
        val session = object : GameSession() {
            override val rendererMode = object : RendererMode { override val id = "exit-test" }
            override fun ensureStarted(viewport: KoolCanvasViewport) = engine
            override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
            override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1, 1), emptyList())
            fun launch() = loadMap(engine, "next.tmx")
        }

        session.launch()

        assertTrue(engine.hasLoadedLevel)
        assertEquals("next.tmx", engine.currentMapPath)
        assertFalse(engine.isStopped)
        assertFalse(engine.isPaused)
    }

    private fun withEngine(action: (GameLogic) -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = instance.get(null)
        instance.set(null, null)
        try {
            val engine = object : GameLogic() {
                override fun stopAndReset() { hasLoadedLevel = false }
                override fun loadGame(newGame: Boolean, mode: GameMode) { hasLoadedLevel = true }
                override fun setPendingMessageBox(title: String?, message: String?) = Unit
                override fun alert(message: String?, duration: Int) = Unit
            }
            engine.replayEngine = ReplayEngine()
            action(engine)
        } finally {
            instance.set(null, previous)
        }
    }
}
