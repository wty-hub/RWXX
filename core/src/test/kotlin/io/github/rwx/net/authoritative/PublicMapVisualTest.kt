package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.map.Tileset
import com.corrodinggames.rts.gameFramework.GameEngine
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class PublicMapVisualTest {
    @Test fun `catalog has only bounded crops of the eleven audited bundled PNGs`() {
        assertNull(PublicTerrainCatalog.tile(0))
        assertNull(PublicTerrainCatalog.tile(-1))
        assertNull(PublicTerrainCatalog.tile(PublicTerrainCatalog.MAX_TILE_ID + 1))
        val tiles = (1..PublicTerrainCatalog.MAX_TILE_ID).map { assertNotNull(PublicTerrainCatalog.tile(it)) }
        assertEquals(11, tiles.map { it.assetPath }.distinct().size)
        for (tile in tiles) {
            assertTrue(tile.assetPath.startsWith("tilesets/bitmaps/"))
            assertEquals(20, tile.width); assertEquals(20, tile.height)
            assertTrue(tile.sourceX >= 0 && tile.sourceY >= 0)
            assertTrue(tile.sourceX + tile.width <= tile.imageWidth)
            assertTrue(tile.sourceY + tile.height <= tile.imageHeight)
        }
        assertEquals(1, PublicTerrainCatalog.id("snow.png", 0))
        assertEquals(234, PublicTerrainCatalog.id("misc.png", 8))
        assertNull(PublicTerrainCatalog.id("misc.png", 9))
        assertNull(PublicTerrainCatalog.id("units.png", 0))
        assertNull(PublicTerrainCatalog.id("../snow.png", 0))
        assertNull(PublicTerrainCatalog.id("[EMBED]1", 0))
        assertNull(PublicTerrainCatalog.id("snow.png", -1))
    }

    @Test fun `capture includes four Items resource pools but never reads the Units layer`() {
        val engineBefore = GameEngine.getInstance()
        val map = PublicMapFixture.create()
        val baseline = EnginePublicMapSnapshot.capture(map)
        val visual = assertNotNull(baseline.visualCopy())
        assertContentEquals(IntArray(12) { PublicTerrainCatalog.id("ice.png", 0)!! }, visual.groundTileIds)
        assertEquals(listOf(0, 3, 8, 11), visual.decorationTileIds.indices.filter { visual.decorationTileIds[it] != 0 })
        assertEquals(listOf(0, 3, 8, 11), baseline.terrainCopy().indices.filter { baseline.terrainCopy()[it].toInt() and 4 != 0 })
        assertSame(engineBefore, GameEngine.getInstance(), "Static extraction must not initialize an engine")
    }

    @Test fun `frozen public baseline is independent of hidden map changes and returned array mutation`() {
        val map = PublicMapFixture.create()
        val baseline = EnginePublicMapSnapshot.capture(map)
        val originalVisual = assertNotNull(baseline.visualCopy())
        val originalTerrain = baseline.terrainCopy()
        map.groundLayer.getTileAt(1, 1).tilesetLocalIndex = 4
        map.groundLayer.getTileAt(1, 1).isWater = true
        map.pathingOverrideLayer.setTileAt(0, 0, null, false)
        map.mapLayers.single { it.name == "Units" }.setTileAt(2, 1, null, false)
        baseline.terrainCopy().fill(15)
        baseline.visualCopy()!!.groundTileIds.fill(0)
        baseline.visualCopy()!!.decorationTileIds.fill(0)
        assertContentEquals(originalTerrain, baseline.terrainCopy())
        assertContentEquals(originalVisual.groundTileIds, baseline.visualCopy()!!.groundTileIds)
        assertContentEquals(originalVisual.decorationTileIds, baseline.visualCopy()!!.decorationTileIds)
        baseline.requireMap(map)
        assertFailsWith<IllegalStateException> { baseline.requireMap(PublicMapFixture.create()) }
        map.tileCountX++
        assertFailsWith<IllegalStateException> { baseline.requireMap(map) }
    }

    @Test fun `static public extraction accepts the one million cell profile ceiling without an engine`() {
        assertEquals(1_048_576, PublicTerrainCatalog.MAX_VISUAL_CELLS)
        val engineBefore = GameEngine.getInstance()
        val map = PublicMapFixture.flat(1024, 1024)
        val baseline = EnginePublicMapSnapshot.capture(map)
        assertEquals(1_048_576, baseline.terrainCopy().size)
        assertTrue(baseline.terrainCopy().all { it == 0.toByte() })
        val visual = assertNotNull(baseline.visualCopy())
        assertEquals(1_048_576, visual.groundTileIds.size)
        assertTrue(visual.groundTileIds.all { it == PublicTerrainCatalog.id("snow.png", 0) })
        assertTrue(visual.decorationTileIds.all { it == 0 })
        assertSame(engineBefore, GameEngine.getInstance())
    }

    @Test fun `capture rejects unapproved layers tiles metrics and oversized maps`() {
        PublicMapFixture.create().let { map ->
            map.mapLayers.add(MapLayer(map, "HiddenItems", 4, 3))
            assertFailsWith<IllegalStateException> { EnginePublicMapSnapshot.capture(map) }
        }
        for (name in listOf("units.png", "../ice.png", "[EMBED]1")) {
            PublicMapFixture.create().let { map ->
                map.groundLayer.getTileAt(0, 0).tileset.imageKey = name
                assertFailsWith<IllegalStateException> { EnginePublicMapSnapshot.capture(map) }
            }
        }
        PublicMapFixture.create().let { map ->
            map.groundLayer.getTileAt(0, 0).tileset.containsUnits = true
            assertFailsWith<IllegalStateException> { EnginePublicMapSnapshot.capture(map) }
        }
        PublicMapFixture.create().let { map ->
            map.groundLayer.getTileAt(0, 0).tilesetLocalIndex = 999
            assertFailsWith<IllegalStateException> { EnginePublicMapSnapshot.capture(map) }
        }
        PublicMapFixture.create().let { map ->
            PublicMapFixture.field(map.groundLayer.getTileAt(0, 0).tileset, "tileWidth", 24)
            assertFailsWith<IllegalStateException> { EnginePublicMapSnapshot.capture(map) }
        }
        PublicMapFixture.create().let { map ->
            map.tileCountX = 1025; map.tileCountY = 1024
            assertFailsWith<IllegalStateException> { EnginePublicMapSnapshot.capture(map) }
        }
        PublicMapFixture.create().let { map ->
            map.tileCountX = 1025; map.tileCountY = 1
            assertFailsWith<IllegalStateException> { EnginePublicMapSnapshot.capture(map) }
        }
    }
}

/** Loaded-cell fixture with no engine, map file, rendering backend, unit parser or script execution. */
internal object PublicMapFixture {
    fun flat(width: Int, height: Int): TileMap {
        val map = TileMap().apply { tileCountX = width; tileCountY = height }
        val ground = MapLayer(map, "Ground", width, height)
        val items = MapLayer(map, "Items", width, height)
        map.groundLayer = ground; map.pathingOverrideLayer = items
        map.mapLayers.addAll(listOf(ground, items, MapLayer(map, "Units", width, height)))
        val snow = tile(tileset(map, "snow.png", 8), 0)
        for (y in 0 until height) for (x in 0 until width) ground.setTileAt(x, y, snow, false)
        return map
    }

    fun create(): TileMap {
        val map = TileMap().apply {
            tileCountX = 4; tileCountY = 3
            fogEnabled = true; fogPeriodicMaintenanceEnabled = true
        }
        val ground = MapLayer(map, "Ground", 4, 3)
        val items = MapLayer(map, "Items", 4, 3)
        val units = MapLayer(map, "Units", 4, 3)
        map.groundLayer = ground
        map.pathingOverrideLayer = items
        map.mapLayers.addAll(listOf(ground, items, units))
        val ice = tileset(map, "ice.png", 5)
        val misc = tileset(map, "misc.png", 3)
        for (y in 0 until 3) for (x in 0 until 4) ground.setTileAt(x, y, tile(ice, 0), false)
        for ((x, y) in listOf(0 to 0, 3 to 0, 0 to 2, 3 to 2)) {
            items.setTileAt(x, y, tile(misc, 4).apply { isResourcePool = true }, false)
        }
        // Deliberately invalid display data: exporting this layer would fail the catalog check.
        units.setTileAt(2, 1, tile(tileset(map, "private-unit-path.png", 1), 999), false)
        return map
    }

    private fun tileset(map: TileMap, name: String, columns: Int): Tileset {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val element = document.createElement("tileset").apply {
            setAttribute("firstgid", "1"); setAttribute("tilewidth", "20"); setAttribute("tileheight", "20")
            appendChild(document.createElement("image").apply { setAttribute("source", name) })
        }
        return Tileset(map, element).apply {
            field(this, "columns", columns)
            field(this, "invColumns", 1f / columns)
        }
    }

    private fun tile(tileset: Tileset, index: Int): MapTile =
        MapTile::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
            this.tileset = tileset; tilesetLocalIndex = index
        }

    fun field(target: Tileset, name: String, value: Any) {
        Tileset::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
