package com.corrodinggames.rts.gameFramework.ui

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import io.github.rwx.render.canvas.KoolCanvasCommand
import io.github.rwx.render.canvas.KoolCanvasCpuTextureStore
import io.github.rwx.render.canvas.KoolGraphicsEngine
import io.github.rwx.ui.emoji.EmojiRaster
import io.github.rwx.ui.emoji.EmojiRasterizer
import io.github.rwx.ui.emoji.EmojiRasterizerBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageManagerTest {
    @Test
    fun `in game player chat sends complete emoji clusters through the canvas image path`() {
        val previous = EmojiRasterizerBridge.currentRasterizer()
        val clusters = mutableListOf<String>()
        val rasterizer = object : EmojiRasterizer {
            override fun rasterize(emoji: String, sizePx: Int): EmojiRaster {
                clusters += emoji
                return EmojiRaster(2, 2, ByteArray(16) { 0xff.toByte() })
            }
        }
        EmojiRasterizerBridge.install(rasterizer)
        try {
            withMessages { _, manager, graphics ->
                manager.addMessage("玩家🍎", "来了 🇨🇳\n👨‍👩‍👧 🎮")
                manager.draw(0f, 95)
                val frame = graphics.snapshot()
                assertEquals(listOf("🍎", "🇨🇳", "👨‍👩‍👧", "🎮"), clusters)
                assertEquals(4, frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().size)
                assertEquals("玩家: 来了  ", frame.commands.filterIsInstance<KoolCanvasCommand.DrawText>().joinToString("") { it.text })
            }
        } finally {
            EmojiRasterizerBridge.uninstall(rasterizer)
            previous?.let(EmojiRasterizerBridge::install)
        }
    }

    @Test
    fun `multiline server help leaves space before the next player message`() = withMessages { _, manager, graphics ->
        manager.addMessage("玩家", "来了")
        val help = manager.addMessage(null, "【系统消息】：中文帮助\n.cnhelp\n.enhelp - View help in English\n请联系服务器管理员了解服务器规则。")
        help.textColor = 0xff99ccff.toInt()

        val end = manager.draw(0f, 95)
        val lines = graphics.snapshot().commands.filterIsInstance<KoolCanvasCommand.DrawText>()

        assertEquals(listOf("【系统消息】：中文帮助", ".cnhelp", ".enhelp - View help in English",
            "请联系服务器管理员了解服务器规则。", "玩家: 来了"), lines.map { it.text })
        assertEquals(95f, lines.first().baseline.y)
        assertTrue(lines.zipWithNext().all { (a, b) -> b.baseline.y - a.baseline.y >= a.paint.textSize })
        assertTrue(end > lines.last().baseline.y)
        assertTrue(lines.take(4).all { it.paint.color.argb == help.textColor })
        assertEquals(-1, lines.last().paint.color.argb)
    }

    @Test
    fun `mixed newlines and empty lines retain their space with scaled and larger fonts`() = withMessages { engine, manager, graphics ->
        manager.addMessage(null, "older")
        manager.addMessage(null, "first\r\n\r\nlast\r")

        for (scale in listOf(0.75f, 1f, 2f)) {
            engine.screenScale = scale
            // A font taller than the original fixed spacing must still fit on each line.
            manager.textPaint.b(32f * scale)
            graphics.beginFrame(1280, 720)
            val end = manager.draw(0f, 95)
            val lines = graphics.snapshot().commands.filterIsInstance<KoolCanvasCommand.DrawText>()

            assertEquals(listOf("first", "", "last", "", "older"), lines.map { it.text })
            assertTrue(lines.zipWithNext().all { (a, b) -> b.baseline.y - a.baseline.y >= a.paint.textSize })
            assertTrue(end > lines.last().baseline.y)
        }
    }

    private fun withMessages(block: (GameLogic, MessageManager, KoolGraphicsEngine) -> Unit) {
        val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instanceField.get(null)
        instanceField.set(null, null)
        val store = KoolCanvasCpuTextureStore()
        try {
            val engine = GameLogic()
            engine.screenScale = 1f
            engine.gameUI = GameUI()
            engine.settingsEngine = SettingsEngine::class.java.getDeclaredConstructor().run {
                isAccessible = true
                newInstance()
            }
            engine.settingsEngine.showPlayerChatInGame = true
            val graphics = KoolGraphicsEngine(textureStore = store)
            engine.renderGraphicsEngine = graphics
            graphics.beginFrame(1280, 720)
            val manager = MessageManager(engine, engine.gameUI)
            block(engine, manager, graphics)
        } finally {
            store.close()
            instanceField.set(null, previousEngine)
        }
    }
}
