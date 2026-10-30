package io.github.rwx.render.canvas

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.map.TileAtlasCache
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.graphics.PreparedMapTextureDraw
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import io.github.rwx.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise the actual map traversal rather than reproducing its coordinate arithmetic in a test. */
class MapLayerPreparedTilesTest {
    @Test fun `actual terrain alpha fog edges and tile changes record identically at every scale`() {
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
        val oldFogAtlas = TileMap.fogSmoothAtlasTexture
        val store = KoolCanvasCpuTextureStore()
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
            val map = TileMap().apply {
                tileCountX = 8; tileCountY = 6
                tileWorldSizeX = 20; tileWorldSizeY = 20
                tileScaleX = .05f; tileScaleY = .05f
            }
            engine.tileMap = map
            engine.playerTeam = GameTeam(0, false).apply {
                fogOfWarData = Array(8) { x -> ByteArray(6) { y -> intArrayOf(0, 5, 10)[(x + y) % 3].toByte() } }
            }
            val tileConstructor = MapTile::class.java.getDeclaredConstructor().apply { isAccessible = true }
            map.uniqueTiles = arrayOf(null, tileConstructor.newInstance().apply { atlasSlotIndex = 0 },
                tileConstructor.newInstance().apply { atlasSlotIndex = 500 },
                tileConstructor.newInstance().apply { atlasSlotIndex = 1 })
            fun atlas(scale: Float) = TileAtlasCache(scale, false, root).apply {
                initializeAtlasTexture()
                for (slot in listOf(0, 500)) getAtlasTextureForIndex(slot).apply {
                    j = IntArray(width() * height()) { pixel ->
                        if (slot == 500) 0x80408020.toInt() else 0xff000000.toInt() or (pixel * 131 and 0xffffff)
                    }
                    p()
                }
            }
            TileMap.fogTileAtlasCacheFullScale = atlas(1f)
            TileMap.fogTileAtlasCacheHalfScale = atlas(.5f)
            TileMap.fogSmoothAtlasTexture = root.a(353, 353, true).apply {
                j = IntArray(353 * 353) { 0x80000000.toInt() }; p()
            }
            val layer = MapLayer(map, "ground", 8, 6).apply {
                for (x in 0 until 8) for (y in 0 until 6) tileIds[x * 6 + y] = ((x + y) % 4).toShort()
            }
            map.groundLayer = layer
            fun cell() = (root.b(root.b(256, 192, true), RenderTargetMode.GPU_TARGET) as KoolGraphicsEngine)
            val control = cell()
            val candidate = cell()
            var preparedDraws = 0
            val ordinary: GraphicsEngine = object : GraphicsEngine by control {
                override fun prepareMapTextureDraw(paint: KoolPaint?): PreparedMapTextureDraw? = null
            }
            val optimized: GraphicsEngine = object : GraphicsEngine by candidate {
                override fun prepareMapTextureDraw(paint: KoolPaint?): PreparedMapTextureDraw? {
                    val writer = candidate.prepareMapTextureDraw(paint, true) ?: return null
                    return object : PreparedMapTextureDraw {
                        override fun draw(texture: com.corrodinggames.rts.gameFramework.graphics.Texture?,
                            source: Rect?, destination: io.github.rwx.geometry.RectF?): Boolean =
                            writer.draw(texture, source, destination).also { if (it) preparedDraws++ }
                        override fun close() = writer.close()
                    }
                }
            }
            for (fog in listOf(false, true)) for (scale in listOf(.2f, .35f, .49f, .5f, .75f, .99f, 1f, 1.5f)) {
                map.fogEnabled = fog
                for (offset in listOf(-3.25f, 0f, 23.75f)) {
                    for ((recording, graphics) in listOf(control to ordinary, candidate to optimized)) {
                        recording.beginFrame(256, 192)
                        graphics.a(Rect(1, 2, 251, 187))
                        graphics.a(true)
                        layer.renderLayerRegion(graphics, offset, offset, 0f, 0f, 160f, 120f, scale, scale, fog, false, false)
                        graphics.a(false)
                        if (fog) layer.renderLayerRegion(graphics, offset, offset, 0f, 0f, 160f, 120f, scale, scale, true, true, true)
                        graphics.f()
                    }
                    assertEquals(control.snapshot(), candidate.snapshot(), "fog=$fog scale=$scale offset=$offset")
                }
                // Public tile replacement must be observed on the next render, even at the same zoom.
                layer.tileIds[7] = ((layer.tileIds[7] + 1) % 4).toShort()
            }
            assertTrue(preparedDraws > 500, "fixture must execute the optimized atlas path")
        } finally {
            store.close()
            instance.set(null, oldEngine)
            if (oldTeams != null) teams.set(null, oldTeams)
            GameEngine.isJavaDesktopVersion = oldDesktop
            GameEngine.isNonAndroidVersion = oldNonAndroid
            GameEngine.isPCOrIOSVersion = oldPC
            GameEngine.isIOSVersion = oldIOS
            TileMap.fogTileAtlasCacheFullScale = oldFullAtlas
            TileMap.fogTileAtlasCacheHalfScale = oldHalfAtlas
            TileMap.fogSmoothAtlasTexture = oldFogAtlas
        }
    }
}
