package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.util.MsdfFont
import io.github.rwx.ui.emoji.EmojiRaster
import io.github.rwx.ui.emoji.EmojiRasterizer
import io.github.rwx.ui.emoji.EmojiRasterizerBridge
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoolCanvasEmojiTextTest {
    @Test
    fun `native game text records complete emoji clusters as frozen color images`() = fixture { rasterizer ->
        KoolCanvasCpuTextureStore().use { store ->
            val graphics = KoolGraphicsEngine(textureStore = store)
            val paint = KoolPaint().apply { setTextSize(16f) }
            val text = "A😀🇨🇳👨‍👩‍👧1️⃣B"
            graphics.beginFrame(400, 100)
            graphics.a(text, 10f, 40f, paint)
            val frame = graphics.snapshot()
            val images = frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>()
            val words = frame.commands.filterIsInstance<KoolCanvasCommand.DrawText>()
            assertEquals(listOf("😀", "🇨🇳", "👨‍👩‍👧", "1️⃣"), rasterizer.calls)
            assertEquals(listOf("A", "B"), words.map { it.text })
            assertEquals(4, images.size)
            val prefixWidth = KoolCanvasFontRegistry.textWidth("A", 16f)
            assertEquals(10f + prefixWidth, images.first().destination.left)
            assertEquals(10f + prefixWidth + 4 * 32f, words.last().baseline.x)
            val expectedWidth = prefixWidth + 4 * 32f + KoolCanvasFontRegistry.textWidth("B", 16f)
            assertEquals(expectedWidth.roundToInt(), graphics.b(text, paint))
            store.freezeFrame(frame, 1, 1, 0, 0).use { packet ->
                assertEquals(4, packet.resourceLease.resourceCount)
                assertTrue(packet.resourceLease.resources().values.all { resource ->
                    resource is FrozenCanvasResource.Pixels && resource.image.pixels.any { it == PIXEL }
                })
            }
            assertTrue(images.all { it.texture.requiresOrderedAlpha })
        }
    }

    @Test
    fun `mixed text aligns as one line and emoji retains its color with text opacity`() = fixture {
        KoolCanvasCpuTextureStore().use { store ->
            val graphics = KoolGraphicsEngine(textureStore = store)
            val paint = KoolPaint().apply { setTextSize(20f); setColor(0x804080c0.toInt()) }
            val width = KoolCanvasFontRegistry.textWidth("A", 20f) + 40f + KoolCanvasFontRegistry.textWidth("B", 20f)
            for (align in listOf(KoolPaint.Align.LEFT, KoolPaint.Align.CENTER, KoolPaint.Align.RIGHT)) {
                paint.setTextAlign(align)
                graphics.beginFrame(400, 100)
                graphics.a("A🍎B", 150f, 50f, paint)
                val commands = graphics.snapshot().commands
                val text = commands.first() as KoolCanvasCommand.DrawText
                val image = commands[1] as KoolCanvasCommand.DrawTexture
                val expected = 150f - when (align) {
                    KoolPaint.Align.LEFT -> 0f
                    KoolPaint.Align.CENTER -> width / 2
                    KoolPaint.Align.RIGHT -> width
                }
                assertEquals(expected, text.baseline.x, .0001f)
                assertEquals(KoolCanvasTextAlign.Left, text.paint.textAlign)
                assertEquals(0x804080c0.toInt(), text.paint.color.argb)
                assertEquals(0x80ffffff.toInt(), image.paint.color.argb)
                assertEquals(20f, image.destination.height)
                assertTrue(assertNotNull(store.argbImageView(image.texture.id)).pixels.all { it == PIXEL })
            }
        }
    }

    @Test
    fun `BMP emoji reach the native rasterizer as complete clusters without standalone selectors`() = fixture { rasterizer ->
        KoolCanvasCpuTextureStore().use { store ->
            val graphics = KoolGraphicsEngine(textureStore = store)
            val paint = KoolPaint().apply { setTextSize(16f) }
            val text = "A\uFE0F‼️↔️Ⓜ️B"
            graphics.beginFrame(400, 100)
            graphics.a(text, 10f, 40f, paint)
            val commands = graphics.snapshot().commands
            assertEquals(listOf("‼️", "↔️", "Ⓜ️"), rasterizer.calls)
            assertEquals(3, commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().size)
            assertEquals(listOf("A\uFE0F", "B"),
                commands.filterIsInstance<KoolCanvasCommand.DrawText>().map { it.text })
            val expectedWidth = KoolCanvasFontRegistry.textWidth("A\uFE0F", 16f) + 3 * 32f +
                KoolCanvasFontRegistry.textWidth("B", 16f)
            assertEquals(expectedWidth.roundToInt(), graphics.b(text, paint))
            assertEquals(listOf("‼️", "↔️", "Ⓜ️"), rasterizer.calls)
        }
    }

    @Test
    fun `ordinary text and a missing bridge keep the single original MSDF command`() = fixture { rasterizer ->
        KoolCanvasCpuTextureStore().use { store ->
            val graphics = KoolGraphicsEngine(textureStore = store)
            for (text in listOf("普通文字 A2000", "普通文字 A\uFE0F #\uFE0F")) {
                graphics.beginFrame(400, 100)
                graphics.a(text, 12f, 30f, null)
                val ordinary = graphics.snapshot().commands.single() as KoolCanvasCommand.DrawText
                assertEquals(text, ordinary.text)
            }
            assertTrue(rasterizer.calls.isEmpty())
            val helper = KoolGraphicsEngine::class.java.getDeclaredField("emojiText").apply { isAccessible = true }
            assertNull(helper.get(graphics), "Plain HUD/map target text must not allocate an emoji cache")
            EmojiRasterizerBridge.uninstall(rasterizer)
            graphics.beginFrame(400, 100)
            graphics.a("A😀B", 12f, 30f, null)
            assertEquals("A😀B", (graphics.snapshot().commands.single() as KoolCanvasCommand.DrawText).text)
            assertEquals(KoolCanvasFontRegistry.textWidth("A😀B", 16f).roundToInt(), graphics.b("A😀B", null))
            assertNull(helper.get(graphics))
        }
    }

    @Test
    fun `newline positions use normal line height while emoji measurement uses the longest complete line`() = fixture {
        KoolCanvasCpuTextureStore().use { store ->
            val graphics = KoolGraphicsEngine(textureStore = store)
            graphics.beginFrame(400, 100)
            graphics.a("😀\n🍎🍎", 10f, 30f, null)
            val images = graphics.snapshot().commands.filterIsInstance<KoolCanvasCommand.DrawTexture>()
            assertEquals(3, images.size)
            assertEquals(images[0].destination.top + KoolCanvasFontRegistry.lineHeight(16f), images[1].destination.top)
            assertEquals(10f, images[1].destination.left)
            assertEquals(42f, images[2].destination.left)
            assertEquals(64, graphics.b("😀\n🍎🍎", null))
        }
    }

    @Test
    fun `bounded cache eviction and clear keep completed frame resources valid`() = fixture { rasterizer ->
        KoolCanvasCpuTextureStore().use { store ->
            val helper = KoolCanvasEmojiText(store, maximumEntries = 2)
            val buffer = KoolCanvasCommandBuffer(KoolCanvasViewport(100, 60))
            helper.draw("😀", KoolCanvasPoint(5f, 30f), KoolCanvasPaint.Default, buffer)
            val first = (buffer.snapshot().commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            store.freezeFrame(buffer.snapshot(), 1, 1, 0, 0).use { completed ->
                helper.beginFrame()
                buffer.beginFrame(KoolCanvasViewport(100, 60))
                helper.draw("🍎🎮", KoolCanvasPoint(5f, 30f), KoolCanvasPaint.Default, buffer)
                assertNull(store.argbImageView(first))
                val second = buffer.snapshot()
                store.freezeFrame(second, 2, 1, 1, 0).use { next ->
                    helper.clear()
                    second.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().forEach { assertNull(store.argbImageView(it.texture.id)) }
                    assertEquals(2, next.resourceLease.resourceCount)
                    assertEquals(1, completed.resourceLease.resourceCount)
                    assertTrue((completed.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels).image.pixels.all { it == PIXEL })
                }
            }
            assertEquals(listOf("😀", "🍎", "🎮"), rasterizer.calls)
        }
    }

    @Test
    fun `cache does not evict pixels already referenced by another label in the same frame`() = fixture {
        KoolCanvasCpuTextureStore().use { store ->
            val helper = KoolCanvasEmojiText(store, maximumEntries = 1)
            val buffer = KoolCanvasCommandBuffer(KoolCanvasViewport(100, 60))
            helper.draw("😀", KoolCanvasPoint(5f, 30f), KoolCanvasPaint.Default, buffer)
            helper.draw("🍎", KoolCanvasPoint(30f, 30f), KoolCanvasPaint.Default, buffer)
            val commands = buffer.snapshot().commands
            assertTrue(commands.first() is KoolCanvasCommand.DrawTexture)
            assertNotNull(store.argbImageView((commands.first() as KoolCanvasCommand.DrawTexture).texture.id))
            assertTrue(commands.last() is KoolCanvasCommand.DrawText)
            helper.clear()
        }
    }

    @Test
    fun `platform replacement gets its own pixels without invalidating the old source`() = fixture {
        KoolCanvasCpuTextureStore().use { store ->
            val graphics = KoolGraphicsEngine(textureStore = store)
            graphics.beginFrame(100, 60)
            graphics.a("😀", 5f, 30f, null)
            val replacement = FakeRasterizer(0xff1144ee.toInt())
            EmojiRasterizerBridge.install(replacement)
            graphics.a("😀", 30f, 30f, null)
            val commands = graphics.snapshot().commands.filterIsInstance<KoolCanvasCommand.DrawTexture>()
            assertNotEquals(commands[0].texture.id, commands[1].texture.id)
            assertEquals(PIXEL, assertNotNull(store.argbImageView(commands[0].texture.id)).pixels.first())
            assertEquals(0xff1144ee.toInt(), assertNotNull(store.argbImageView(commands[1].texture.id)).pixels.first())
        }
    }

    @Test
    fun `failed or invalid platform glyphs fall back without aborting the frame or retrying every draw`() = fixture {
        val broken = object : EmojiRasterizer {
            var attempts = 0
            override fun rasterize(emoji: String, sizePx: Int): EmojiRaster? {
                attempts++
                if (emoji == "😀") error("Broken platform glyph")
                return EmojiRaster(Int.MAX_VALUE, 10, byteArrayOf())
            }
        }
        EmojiRasterizerBridge.install(broken)
        KoolCanvasCpuTextureStore().use { store ->
            val graphics = KoolGraphicsEngine(textureStore = store)
            repeat(3) {
                graphics.beginFrame(100, 60)
                graphics.a("😀🍎", 5f, 30f, null)
                assertTrue(graphics.snapshot().commands.all { it is KoolCanvasCommand.DrawText })
            }
            assertEquals(2, broken.attempts)
        }
    }

    private fun fixture(block: (FakeRasterizer) -> Unit) {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        val previousFont = KoolCanvasFontRegistry.base
        val previousRasterizer = EmojiRasterizerBridge.currentRasterizer()
        val rasterizer = FakeRasterizer()
        KoolCanvasFontRegistry.installBaseFont(MsdfFont.DEFAULT_FONT)
        EmojiRasterizerBridge.install(rasterizer)
        try { block(rasterizer) }
        finally {
            EmojiRasterizerBridge.currentRasterizer()?.let(EmojiRasterizerBridge::uninstall)
            previousRasterizer?.let(EmojiRasterizerBridge::install)
            KoolCanvasFontRegistry.installBaseFont(previousFont)
        }
    }

    private class FakeRasterizer(private val pixel: Int = PIXEL) : EmojiRasterizer {
        val calls = mutableListOf<String>()
        override fun rasterize(emoji: String, sizePx: Int): EmojiRaster {
            calls += emoji
            return EmojiRaster(4, 2, ByteArray(32).also { pixels ->
                for (index in 0 until 8) {
                    pixels[index * 4] = (pixel ushr 16).toByte()
                    pixels[index * 4 + 1] = (pixel ushr 8).toByte()
                    pixels[index * 4 + 2] = pixel.toByte()
                    pixels[index * 4 + 3] = (pixel ushr 24).toByte()
                }
            })
        }
    }

    private companion object { const val PIXEL = 0xffd05030.toInt() }
}
