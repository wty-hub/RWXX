package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PausedNetworkFrameTest {
    private val viewport = KoolCanvasViewport(1280, 720)

    @Test
    fun `native multiplayer keeps advancing while its pause panel covers the game`() {
        val session = FrameDrivenSession(networkMultiplayer = true)

        assertSame(session.frame, pausedNetworkFrameForSession(session, viewport, .02f))
        assertEquals(listOf(viewport to .02f), session.updates)
    }

    @Test
    fun `local games and recorded multiplayer replays stay frozen in the pause panel`() {
        // GameEngine.isNetworkGameActive excludes replay playback even for recorded online games.
        val session = FrameDrivenSession(networkMultiplayer = false)

        assertNull(pausedNetworkFrameForSession(session, viewport, .02f))
        assertEquals(emptyList(), session.updates)
    }

    @Test
    fun `independent multiplayer owner keeps exclusive responsibility for advancing the match`() {
        val session = FrameDrivenSession(networkMultiplayer = true, usesIndependentEngineLoop = true)

        assertNull(pausedNetworkFrameForSession(session, viewport, .02f))
        assertEquals(emptyList(), session.updates)
        assertEquals(0, session.networkReads)
    }

    private class FrameDrivenSession(
        private val networkMultiplayer: Boolean,
        override val usesIndependentEngineLoop: Boolean = false,
    ) : GameSession() {
        override val rendererMode: RendererMode = object : RendererMode { override val id = "test" }
        override fun loadPendingMapNow(): KoolCanvasFrame = error("No map loading in this test")
        override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine = error("No engine needed for frame routing")
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit

        val frame = KoolCanvasFrame(KoolCanvasViewport(1280, 720), emptyList())
        val updates = mutableListOf<Pair<KoolCanvasViewport, Float>>()
        var networkReads = 0
            private set

        override fun isNetworkMultiplayerActive(): Boolean {
            networkReads++
            return networkMultiplayer
        }

        override fun updateFrame(
            viewport: KoolCanvasViewport,
            deltaSeconds: Float,
            drainVisibleLayerBuffers: Boolean,
        ): KoolCanvasFrame {
            updates += viewport to deltaSeconds
            return frame
        }
    }
}
