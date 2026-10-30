package com.corrodinggames.rts.gameFramework

import com.corrodinggames.rts.game.GameLogic
import kotlin.test.*

class ReplayPlaybackSpeedTest {
    private val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }

    private fun withReplay(block: (GameLogic, ReplayEngine) -> Unit) {
        val previousEngine = instanceField.get(null)
        instanceField.set(null, null)
        try {
            val engine = GameLogic()
            val replay = ReplayEngine()
            engine.replayEngine = replay
            replay.P = true
            ReplayEngine::class.java.getDeclaredField("isReplaying").apply { isAccessible = true }
                .setBoolean(replay, true)
            block(engine, replay)
        } finally {
            instanceField.set(null, previousEngine)
        }
    }

    private fun seekSession(engine: GameLogic, replay: ReplayEngine) {
        val sessionType = ReplayEngine::class.java.declaredClasses.single { it.simpleName == "SeekSession" }
        val session = sessionType.getDeclaredConstructor(GameEngine::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(engine, 30_000)
        ReplayEngine::class.java.getDeclaredField("seekSession").apply { isAccessible = true }.set(replay, session)
    }

    @Test fun `pause preserves selected speed and speed buttons leave playback paused`() = withReplay { engine, replay ->
        engine.gameSpeed = 4f
        replay.togglePlayback()
        assertEquals(0f, engine.gameSpeed)
        assertEquals(4f, replay.playbackSpeed)
        replay.slowDown()
        assertEquals(0f, engine.gameSpeed)
        assertEquals(2f, replay.playbackSpeed)
        replay.speedUp()
        assertEquals(0f, engine.gameSpeed)
        assertEquals(4f, replay.playbackSpeed)
        replay.togglePlayback()
        assertEquals(4f, engine.gameSpeed)
    }

    @Test fun `slow motion and fast playback clamp at speed limits without wrapping`() = withReplay { engine, replay ->
        engine.gameSpeed = 1f
        replay.slowDown()
        assertEquals(.5f, engine.gameSpeed)
        replay.slowDown()
        assertEquals(.25f, engine.gameSpeed)
        replay.slowDown()
        assertEquals(.25f, engine.gameSpeed)
        repeat(8) { replay.speedUp() }
        assertEquals(64f, engine.gameSpeed)
        replay.speedUp()
        assertEquals(64f, engine.gameSpeed)
        replay.slowDown()
        assertEquals(32f, engine.gameSpeed)
        repeat(8) { replay.slowDown() }
        assertEquals(.25f, engine.gameSpeed)
    }

    @Test fun `externally selected speed steps to the adjacent supported speed`() = withReplay { engine, replay ->
        engine.gameSpeed = 3f
        replay.speedUp()
        assertEquals(4f, engine.gameSpeed)
        engine.gameSpeed = 3f
        replay.slowDown()
        assertEquals(2f, engine.gameSpeed)
    }

    @Test fun `seek keeps configured speed visible and blocks playback changes`() = withReplay { engine, replay ->
        engine.gameSpeed = 8f
        seekSession(engine, replay)
        engine.gameSpeed = 0f
        assertEquals(8f, replay.playbackSpeed)
        replay.togglePlayback()
        replay.slowDown()
        replay.speedUp()
        assertEquals(0f, engine.gameSpeed)
        assertEquals(8f, replay.playbackSpeed)
    }

    @Test fun `paused seek keeps the remembered resume speed`() = withReplay { engine, replay ->
        engine.gameSpeed = .5f
        replay.togglePlayback()
        seekSession(engine, replay)
        assertEquals(.5f, replay.playbackSpeed)
    }

    @Test fun `ended playback ignores transport actions`() = withReplay { engine, replay ->
        engine.gameSpeed = 4f
        replay.togglePlayback()
        ReplayEngine::class.java.getDeclaredField("isStopped").apply { isAccessible = true }.setBoolean(replay, true)
        replay.togglePlayback()
        replay.slowDown()
        replay.speedUp()
        assertEquals(0f, engine.gameSpeed)
        assertEquals(4f, replay.playbackSpeed)
    }

    @Test fun `seek reload preserves resume speed and closing replay resets it`() = withReplay { engine, replay ->
        engine.gameSpeed = 2f
        replay.togglePlayback()
        val restoring = ReplayEngine::class.java.getDeclaredField("restoringSeek").apply { isAccessible = true }
        restoring.setBoolean(replay, true)
        replay.e()
        assertEquals(2f, replay.playbackSpeed)
        restoring.setBoolean(replay, false)
        replay.e()
        assertEquals(1f, replay.playbackSpeed)
    }
}
