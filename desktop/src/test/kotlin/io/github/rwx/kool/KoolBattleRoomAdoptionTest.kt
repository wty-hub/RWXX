package io.github.rwx.kool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameMode
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.PlatformStorage
import io.github.rwx.render.canvas.KoolCanvasViewport
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoolBattleRoomAdoptionTest {
    @Test
    fun `starting a sandbox on another map replaces the previous running map path`() {
        val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instanceField.get(null)
        instanceField.set(null, null)
        try {
            var loads = 0
            val engine = object : GameLogic() {
                override fun loadGame(newGame: Boolean, mode: GameMode) {
                    loads++
                    hasLoadedLevel = true
                }

                override fun updateWindowResolution(width: Int, height: Int) = Unit

                override fun updateWindowResolution(width: Int, height: Int, renderSurfaceScale: Float) = Unit
            }
            engine.networkEngine = NetworkEngine().apply {
                gameHasBeenStarted = true
                singleplayerServer = true
                isSandboxMode = true
                selectedMapPath = "new-sandbox.tmx"
            }
            val storage = Proxy.newProxyInstance(
                PlatformStorage::class.java.classLoader,
                arrayOf(PlatformStorage::class.java),
            ) { _, method, _ -> error("Unexpected storage access: ${method.name}") } as PlatformStorage
            val session = KoolDesktopGameSession(storage)
            try {
            val viewport = KoolCanvasViewport(1280, 720)
            session.markRendererMapReady("old-skirmish.tmx", viewport)

            assertTrue(session.adoptStartedGameFromEngine(viewport))

            assertEquals(1, loads)
            assertEquals("new-sandbox.tmx", engine.currentMapPath)
            assertEquals("new-sandbox.tmx", session.runningMapPath())
            assertTrue(session.isMapLoaded("new-sandbox.tmx"))
            assertTrue(engine.networkEngine.isSandboxMode)
            } finally {
                session.close()
            }
        } finally {
            instanceField.set(null, previousEngine)
        }
    }
}
