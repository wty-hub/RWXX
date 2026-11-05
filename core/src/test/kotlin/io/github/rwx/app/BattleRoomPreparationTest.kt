package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameModeType
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.BattleRoomLaunchConfig
import io.github.rwx.session.BattleRoomSnapshot
import io.github.rwx.session.GameSession
import io.github.rwx.ui.host.BattleRoomSceneHost
import io.github.rwx.ui.model.LevelSelectMode
import io.github.rwx.ui.model.LevelSelectViewModel
import io.github.rwx.ui.model.LevelSelectViewModelFactory
import io.github.rwx.ui.model.MapEntry
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class BattleRoomPreparationTest {
    @Test
    fun `UI preparation resolves saved defaults inside its queued session task`() {
        val session = RecordingSession()
        val errors = mutableListOf<String>()
        val controller = BattleRoomController(
            session,
            object : LevelSelectViewModelFactory {
                override fun create(mode: LevelSelectMode): LevelSelectViewModel = error("No map scan is needed")
            },
            BattleRoomSceneHost(),
            LevelSelectMode.SavedGames,
            errors::add,
        )
        val map = MapEntry("saves/selected.rwsave", playerCount = 4, type = LevelSelectMode.SavedGames)

        controller.prepareForMap(map)
        assertNull(session.prepared, "Preparing the UI must not read the uninitialized engine inline")
        session.completeTask()

        val config = assertNotNull(session.prepared)
        assertEquals(map.mapAssetPath, config.room.mapPath)
        assertEquals(GameModeType.savedGame, config.room.options.gameModeType)
        assertEquals(3, config.aiPlayerCount)
        assertTrue(errors.isEmpty())
    }

    private class RecordingSession : GameSession() {
        override val rendererMode = object : RendererMode { override val id = "preparation-test" }
        var prepared: BattleRoomLaunchConfig? = null
        private var pending: (() -> Unit)? = null

        override fun <T> submitSessionTask(action: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { future ->
            pending = { runCatching(action).fold(future::complete, future::completeExceptionally); Unit }
        }

        fun completeTask() = assertNotNull(pending).also { pending = null }.invoke()

        internal override fun enterLocalBattleRoomWithSavedDefaults(config: BattleRoomLaunchConfig): Boolean {
            prepared = config
            return true
        }
        override fun enterLocalBattleRoomLive(config: BattleRoomLaunchConfig): Boolean =
            error("The UI must use the saved-default entry; explicit callers keep the explicit entry")
        override fun currentBattleRoom(refreshNetworkStatus: Boolean): BattleRoomSnapshot? = null
        override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine = error("No engine needed")
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
        override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1, 1), emptyList())
    }
}
