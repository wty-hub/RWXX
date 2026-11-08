package io.github.rwx

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.util.MsdfFont
import io.github.rwx.render.canvas.KoolCanvasCommand
import io.github.rwx.render.canvas.KoolCanvasCpuTextureStore
import io.github.rwx.render.canvas.KoolCanvasFontRegistry
import io.github.rwx.render.canvas.KoolGraphicsEngine
import io.github.rwx.render.canvas.KoolPaint
import io.github.rwx.ui.emoji.EmojiRasterizerBridge
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real platform glyphs through the native game GraphicsEngine, with no windows or game simulation. */
class InGameEmojiCanvasTest {
    @Test
    fun `desktop in game chat keeps colored emoji in the recorded and frozen canvas frame`() {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        val previousFont = KoolCanvasFontRegistry.base
        val previousRasterizer = EmojiRasterizerBridge.currentRasterizer()
        val rasterizer = DesktopEmojiRasterizer()
        EmojiRasterizerBridge.install(rasterizer)
        KoolCanvasFontRegistry.installBaseFont(MsdfFont.DEFAULT_FONT)
        try {
            KoolCanvasCpuTextureStore().use { store ->
                val graphics = KoolGraphicsEngine(textureStore = store)
                val paint = KoolPaint().apply { setTextSize(24f) }
                val message = "Player: chat 🍎 🎮 🔥 🇨🇳 ⚔️ 🏆 ‼️ ↔️ Ⓜ️"
                graphics.beginFrame(760, 90)
                // MessageManager.draw uses exactly this native text entry point.
                graphics.a(message, 20f, 48f, paint)
                val frame = graphics.snapshot()
                val images = frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>()
                assertEquals(9, images.size, "Each emoji cluster must produce a visible image command")
                assertTrue(frame.commands.filterIsInstance<KoolCanvasCommand.DrawText>().joinToString("") { it.text }.startsWith("Player: chat "))
                for ((index, command) in images.withIndex()) {
                    val image = assertNotNull(store.argbImageView(command.texture.id))
                    val visible = image.pixels.count { it ushr 24 >= 16 }
                    assertTrue(visible > 20, "Emoji $index has no visible pixels")
                    val colored = image.pixels.count { pixel ->
                        val red = pixel ushr 16 and 255
                        val green = pixel ushr 8 and 255
                        val blue = pixel and 255
                        pixel ushr 24 >= 16 && (kotlin.math.abs(red - green) > 12 || kotlin.math.abs(green - blue) > 12)
                    }
                    if (index == 0 || index == 3) assertTrue(colored > 20, "Apple and flag must retain their colors")
                    assertEquals(0x00ffffff, command.paint.color.argb and 0x00ffffff)
                }
                assertTrue(graphics.b(message, paint) > graphics.b("Player: chat      ", paint))
                store.freezeFrame(frame, 1, 1, 0, 0).use { packet ->
                    assertEquals(9, packet.resourceLease.resourceCount)
                    // Frozen resources remain owned even after their recording-store entries disappear.
                    savePreview(frame.commands, store)
                    images.forEach { store.unregister(it.texture.id) }
                    assertEquals(9, packet.resourceLease.resourceCount)
                }
            }
        } finally {
            EmojiRasterizerBridge.currentRasterizer()?.let(EmojiRasterizerBridge::uninstall)
            previousRasterizer?.let(EmojiRasterizerBridge::install)
            KoolCanvasFontRegistry.installBaseFont(previousFont)
        }
    }

    private fun savePreview(commands: List<KoolCanvasCommand>, store: KoolCanvasCpuTextureStore) {
        val preview = BufferedImage(760, 90, BufferedImage.TYPE_INT_ARGB)
        val graphics = preview.createGraphics()
        try {
            graphics.color = Color(20, 23, 28)
            graphics.fillRect(0, 0, preview.width, preview.height)
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 24)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            for (command in commands) when (command) {
                is KoolCanvasCommand.DrawText -> {
                    graphics.color = Color(command.paint.color.argb, true)
                    graphics.drawString(command.text, command.baseline.x, command.baseline.y)
                }
                is KoolCanvasCommand.DrawTexture -> {
                    val pixels = assertNotNull(store.argbImageView(command.texture.id))
                    val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                    image.setRGB(0, 0, pixels.width, pixels.height, pixels.pixels, 0, pixels.width)
                    val rect = command.destination
                    graphics.drawImage(image, rect.left.roundToInt(), rect.top.roundToInt(),
                        rect.width.roundToInt(), rect.height.roundToInt(), null)
                }
                else -> Unit
            }
        } finally { graphics.dispose() }
        val output = File("build/verification/in-game-chat-emoji-headless.png")
        output.parentFile.mkdirs()
        ImageIO.write(preview, "png", output)
    }
}
