package com.corrodinggames.rts.gameFramework.ui

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.map.TileAtlasCache
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.map.Tileset
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.graphics.Texture
import io.github.rwx.render.canvas.KoolCanvasCpuTextureStore
import io.github.rwx.render.canvas.FrozenCanvasResource
import io.github.rwx.render.canvas.KoolCanvasCommand
import io.github.rwx.render.canvas.KoolCanvasRect
import io.github.rwx.render.canvas.KoolGraphicsEngine
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Known terrain landmarks must survive the real minimap's quadrant rendering and fog copies. */
class MinimapBackgroundTest {
    @Test fun `rectangular terrain is reduced into all four minimap quadrants`() = withMinimap { engine, minimap, _, _ ->
        for (size in listOf(120, 121, 200)) {
            minimap.release()
            engine.sidebarWidth = size.toFloat()
            minimap.setup()
            assertQuadrants(minimap, size)
        }
    }

    @Test fun `partial fog refresh keeps the scaled terrain and restores revealed pixels`() = withMinimap { engine, minimap, _, _ ->
        engine.sidebarWidth = 120f
        minimap.setup()
        val original = assertNotNull(minimap.unitsTexture.argbPixelsCopy)
        engine.tileMap.fogEnabled = true
        engine.playerTeam.fogOfWarData = Array(MAP_TILES_X) { ByteArray(MAP_TILES_Y) }
        for (x in 0 until MAP_TILES_X / 2) for (y in 0 until MAP_TILES_Y / 2) {
            engine.playerTeam.fogOfWarData[x][y] = 10
        }
        minimap.drawFog(0f, .5f)
        val fogged = assertNotNull(minimap.unitsTexture.argbPixelsCopy)
        assertEquals(0xff000000.toInt(), fogged[30 + 30 * 120])
        assertEquals(original[90 + 90 * 120], fogged[90 + 90 * 120])
        engine.playerTeam.fogOfWarData.forEach { it.fill(0) }
        minimap.drawFog(0f, .5f)
        assertContentEquals(original, minimap.unitsTexture.argbPixelsCopy)
    }

    @Test fun `setup replaces image buffers preallocated before the sidebar reaches its final size`() = withMinimap { engine, minimap, root, store ->
        engine.sidebarWidth = 120f
        minimap.createImageBuffers()
        assertEquals(120, minimap.unitsTexture.width())

        // GameLogic initializes the buffers before the desktop viewport and display density settle.
        // The first setup must use the final size without relying on a preceding draw or release.
        engine.sidebarWidth = 500f
        val landmarks = listOf(8 to 6, 68 to 6, 8 to 50, 68 to 50)
        for ((left, top) in landmarks) for (x in left until left + 4) for (y in top until top + 4) {
            engine.tileMap.groundLayer.tileIds[x * MAP_TILES_Y + y] = 5
        }
        minimap.setup()
        for (texture in listOf(minimap.backgroundTexture, minimap.fogTexture, minimap.unitsTexture)) {
            assertEquals(500, texture.width())
            assertEquals(500, texture.height())
        }
        assertQuadrants(minimap, 500)
        val pixels = assertNotNull(minimap.unitsTexture.argbPixelsCopy)
        for ((left, top) in landmarks) {
            val centerX = (left + 2) * 500 / MAP_TILES_X
            val centerY = (top + 2) * 500 / MAP_TILES_Y
            assertEquals(0xffc8c8c8.toInt(), pixels[centerX + centerY * 500],
                "landmark ($left,$top) survives the final-size setup")
        }
        root.beginFrame(800, 600)
        root.b(minimap.unitsTexture, 300f, 0f, null)
        val envelope = store.freezeFrame(root.snapshot(), 1, 1, 0, 0)
        try {
            val draw = assertIs<KoolCanvasCommand.DrawTexture>(envelope.frame.commands.single())
            assertEquals(KoolCanvasRect(0f, 0f, 500f, 500f), draw.source)
            assertEquals(KoolCanvasRect(300f, 0f, 800f, 500f), draw.destination)
            val resource = assertIs<FrozenCanvasResource.Pixels>(envelope.resourceLease.resources()[draw.texture.id])
            assertEquals(500, resource.image.width)
            assertEquals(500, resource.image.height)
            assertContentEquals(pixels, resource.image.pixels)
        } finally {
            envelope.close()
        }
    }

    @Test fun `draw rebuilds the terrain for one pixel and large sidebar resizes`() = withMinimap { engine, minimap, root, _ ->
        engine.settingsEngine = SettingsEngine::class.java.getDeclaredConstructor().run {
            isAccessible = true
            newInstance()
        }
        for (size in listOf(120, 121, 500, 120)) {
            engine.sidebarWidth = size.toFloat()
            root.beginFrame(800, 600)
            minimap.draw(0f)
            assertEquals(size.toFloat(), minimap.width)
            assertEquals(size.toFloat(), minimap.height)
            for (texture in listOf(minimap.backgroundTexture, minimap.fogTexture, minimap.unitsTexture)) {
                assertEquals(size, texture.width(), "sidebar=$size texture width")
                assertEquals(size, texture.height(), "sidebar=$size texture height")
            }
            assertQuadrants(minimap, size)
            val draw = root.snapshot().commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().single()
            assertEquals(KoolCanvasRect(0f, 0f, size.toFloat(), size.toFloat()), draw.source)
            assertEquals(KoolCanvasRect(800f - size, 0f, 800f, size.toFloat()), draw.destination)
        }
    }

    @Test fun `small terrain landmarks remain scaled in the published minimap texture`() = withMinimap { engine, minimap, root, store ->
        val layer = engine.tileMap.groundLayer
        layer.tileIds.fill(1)
        val landmarks = listOf(8 to 6, 68 to 6, 8 to 50, 68 to 50)
        for ((left, top) in landmarks) for (x in left until left + 4) for (y in top until top + 4) {
            layer.tileIds[x * MAP_TILES_Y + y] = 5
        }
        for (size in listOf(120, 200)) {
            minimap.release()
            engine.sidebarWidth = size.toFloat()
            minimap.setup()
            val pixels = assertNotNull(minimap.unitsTexture.argbPixelsCopy)
            for ((left, top) in landmarks) {
                val expectedLeft = left * size / MAP_TILES_X
                val expectedTop = top * size / MAP_TILES_Y
                val expectedRight = (left + 4) * size / MAP_TILES_X
                val expectedBottom = (top + 4) * size / MAP_TILES_Y
                val white = buildList {
                    for (y in expectedTop - 2..expectedBottom + 2) for (x in expectedLeft - 2..expectedRight + 2) {
                        val pixel = pixels[x + y * size]
                        if ((pixel shr 8 and 255) >= 170 && (pixel and 255) >= 170) add(x to y)
                    }
                }
                assertTrue(white.isNotEmpty(), "size=$size landmark=($left,$top) is visible")
                assertTrue(kotlin.math.abs(white.minOf { it.first } - expectedLeft) <= 1)
                assertTrue(kotlin.math.abs(white.minOf { it.second } - expectedTop) <= 1)
                assertTrue(kotlin.math.abs(white.maxOf { it.first } + 1 - expectedRight) <= 1)
                assertTrue(kotlin.math.abs(white.maxOf { it.second } + 1 - expectedBottom) <= 1)
            }
            repeat(2) { frame ->
                root.beginFrame(800, 600)
                root.b(minimap.unitsTexture, 800f - size, 0f, null)
                val envelope = store.freezeFrame(root.snapshot(), frame.toLong(), 1, 0, 0)
                try {
                    val draw = assertIs<KoolCanvasCommand.DrawTexture>(envelope.frame.commands.single())
                    assertEquals(KoolCanvasRect(0f, 0f, size.toFloat(), size.toFloat()), draw.source)
                    assertEquals(KoolCanvasRect(800f - size, 0f, 800f, size.toFloat()), draw.destination)
                    val resource = assertIs<FrozenCanvasResource.Pixels>(envelope.resourceLease.resources()[draw.texture.id])
                    assertEquals(size, resource.image.width)
                    assertEquals(size, resource.image.height)
                    assertContentEquals(pixels, resource.image.pixels)
                } finally {
                    envelope.close()
                }
            }
        }
    }

    private fun assertQuadrants(minimap: Minimap, size: Int) {
        val image = assertNotNull(minimap.unitsTexture.argbPixelsCopy)
        assertEquals(size * size, image.size)
        val points = listOf(size / 4 to size / 4, size * 3 / 4 to size / 4,
            size / 4 to size * 3 / 4, size * 3 / 4 to size * 3 / 4)
        for ((index, point) in points.withIndex()) {
            assertEquals(DIMMED_COLORS[index], image[point.first + point.second * size],
                "size=$size quadrant=$index sample=$point")
        }
    }

    private fun withMinimap(block: (GameLogic, Minimap, KoolGraphicsEngine, KoolCanvasCpuTextureStore) -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val teams = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val oldEngine = instance.get(null)
        var oldTeams: Any? = null
        val oldDesktop = GameEngine.isJavaDesktopVersion
        val oldNonAndroid = GameEngine.isNonAndroidVersion
        val oldPC = GameEngine.isPCOrIOSVersion
        val oldIOS = GameEngine.isIOSVersion
        val oldFullAtlas = TileMap.fogTileAtlasCacheFullScale
        val oldHalfAtlas = TileMap.fogTileAtlasCacheHalfScale
        val store = KoolCanvasCpuTextureStore()
        val minimap = Minimap()
        val atlases = mutableListOf<TileAtlasCache>()
        var sourceTexture: Texture? = null
        try {
            instance.set(null, null)
            val engine = GameLogic().apply { replayEngine = ReplayEngine() }
            oldTeams = teams.get(null)
            teams.set(null, arrayOfNulls<PlayerTeam>((oldTeams as Array<*>).size))
            GameEngine.isJavaDesktopVersion = true
            GameEngine.isNonAndroidVersion = true
            GameEngine.isPCOrIOSVersion = true
            GameEngine.isIOSVersion = false
            val root = KoolGraphicsEngine(textureStore = store)
            engine.renderGraphicsEngine = root
            engine.screenWidth = 800f
            engine.screenHeight = 600f
            val map = TileMap().apply {
                tileCountX = MAP_TILES_X; tileCountY = MAP_TILES_Y
                tileWorldSizeX = 20; tileWorldSizeY = 20
                tileScaleX = .05f; tileScaleY = .05f
                fogEnabled = false
            }
            engine.tileMap = map
            engine.playerTeam = GameTeam(0, false)
            val tileConstructor = MapTile::class.java.getDeclaredConstructor().apply { isAccessible = true }
            map.uniqueTiles = arrayOf(null, *Array(COLORS.size) { index ->
                tileConstructor.newInstance().apply { atlasSlotIndex = index }
            })
            val tilesetXml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                """<tileset firstgid="1" tilewidth="20" tileheight="20"><image source="minimap-fixture.png"/></tileset>"""
                    .byteInputStream()).documentElement
            val source = root.a(COLORS.size * 20, 20, false).apply {
                setCommittedArgbPixels(IntArray(COLORS.size * 20 * 20) { pixel -> COLORS[(pixel % (COLORS.size * 20)) / 20] })
            }
            sourceTexture = source
            val tileset = Tileset(map, tilesetXml).apply { tilesetBitmap = source }
            Tileset::class.java.getDeclaredField("columns").apply { isAccessible = true }.setInt(tileset, COLORS.size)
            Tileset::class.java.getDeclaredField("invColumns").apply { isAccessible = true }.setFloat(tileset, 1f / COLORS.size)
            fun atlas(scale: Float) = TileAtlasCache(scale, false, root).apply {
                atlases += this
                initializeAtlasTexture()
                clearAtlas()
                for (slot in COLORS.indices) {
                    assertEquals(slot, allocateSlotForTile(tileset, slot))
                }
                finalizeAtlas()
            }
            TileMap.fogTileAtlasCacheFullScale = atlas(1f)
            TileMap.fogTileAtlasCacheHalfScale = atlas(.5f)
            map.groundLayer = MapLayer(map, "ground", MAP_TILES_X, MAP_TILES_Y).apply {
                for (x in 0 until MAP_TILES_X) for (y in 0 until MAP_TILES_Y) {
                    val quadrant = (if (x >= MAP_TILES_X / 2) 1 else 0) + (if (y >= MAP_TILES_Y / 2) 2 else 0)
                    tileIds[x * MAP_TILES_Y + y] = (quadrant + 1).toShort()
                }
            }
            minimap.init()
            minimap.bindGraphicsBackend(root)
            block(engine, minimap, root, store)
        } finally {
            minimap.release()
            atlases.forEach { it.release() }
            sourceTexture?.o()
            store.close()
            instance.set(null, oldEngine)
            if (oldTeams != null) teams.set(null, oldTeams)
            GameEngine.isJavaDesktopVersion = oldDesktop
            GameEngine.isNonAndroidVersion = oldNonAndroid
            GameEngine.isPCOrIOSVersion = oldPC
            GameEngine.isIOSVersion = oldIOS
            TileMap.fogTileAtlasCacheFullScale = oldFullAtlas
            TileMap.fogTileAtlasCacheHalfScale = oldHalfAtlas
        }
    }

    private companion object {
        const val MAP_TILES_X = 80
        const val MAP_TILES_Y = 60
        val COLORS = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt(), 0xffffffff.toInt())
        val DIMMED_COLORS = intArrayOf(0xffc80000.toInt(), 0xff00c800.toInt(), 0xff0000c8.toInt(), 0xffc8c800.toInt())
    }
}
