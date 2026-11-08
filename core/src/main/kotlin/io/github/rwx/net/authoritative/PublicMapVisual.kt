package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.map.TileMap

/** Only public, static display cells. Both layers use row-major indexing; tile ID zero is empty. */
data class PublicMapVisual(
    val catalogVersion: Int,
    val groundTileIds: IntArray,
    val decorationTileIds: IntArray,
) {
    fun detached(): PublicMapVisual = copy(groundTileIds = groundTileIds.copyOf(),
        decorationTileIds = decorationTileIds.copyOf())
}

/** Closed bundled artwork table. Neither the wire nor a map can select a filesystem path. */
object PublicTerrainCatalog {
    const val VERSION = 1
    const val MAX_TILE_ID = 234
    const val MAX_VISUAL_CELLS = 1_048_576

    data class Tile(val assetPath: String, val imageWidth: Int, val imageHeight: Int,
        val sourceX: Int, val sourceY: Int, val width: Int, val height: Int)

    private data class Sheet(val name: String, val width: Int, val height: Int,
        val count: Int = (width / 20) * (height / 20))
    // Ordering is part of catalog version 1. The remaining misc sprites are not approved terrain.
    private val sheets = listOf(
        Sheet("snow.png", 160, 200), Sheet("ice.png", 100, 40),
        Sheet("shallowwater.png", 60, 40), Sheet("water.png", 60, 20),
        Sheet("snow2ice_ridge.png", 60, 140), Sheet("ice2shallowwater_ridge.png", 60, 140),
        Sheet("shallowwater2water_flat.png", 60, 140), Sheet("ice2ice_flat.png", 60, 140),
        Sheet("ice2shallowwater_broken.png", 60, 140), Sheet("stone2snow.png", 60, 140),
        Sheet("misc.png", 79, 160, 9),
    )
    private val tiles = listOf<Tile?>(null) + sheets.flatMap { sheet ->
        List(sheet.count) { index -> Tile("tilesets/bitmaps/${sheet.name}", sheet.width, sheet.height,
            index % (sheet.width / 20) * 20, index / (sheet.width / 20) * 20, 20, 20) }
    }
    private val ids = buildMap {
        var next = 1
        for (sheet in sheets) for (index in 0 until sheet.count) put(sheet.name to index, next++)
    }

    fun tile(id: Int): Tile? = tiles.getOrNull(id)
    fun id(imageKey: String, localIndex: Int): Int? = ids[imageKey to localIndex]
    fun isValid(id: Int): Boolean = id in 0..MAX_TILE_ID
}

/** Server-only baseline, captured once before simulation and shared across connection generations. */
class EnginePublicMapSnapshot private constructor(
    private val map: TileMap,
    private val width: Int,
    private val height: Int,
    private val tileWidth: Int,
    private val tileHeight: Int,
    private val terrain: ByteArray,
    private val visual: PublicMapVisual?,
) {
    fun terrainCopy(): ByteArray = terrain.copyOf()
    fun visualCopy(): PublicMapVisual? = visual?.detached()

    internal fun requireMap(current: TileMap) {
        check(current === map && current.tileCountX == width && current.tileCountY == height &&
            current.tileWorldSizeX == tileWidth && current.tileWorldSizeY == tileHeight) {
            "Public map baseline belongs to another map"
        }
    }

    companion object {
        /** Must be called on the engine owner thread before the first simulation tick. */
        fun capture(map: TileMap): EnginePublicMapSnapshot {
            check(map.tileWorldSizeX == 20 && map.tileWorldSizeY == 20 && map.tileCountX in 1..1024 &&
                map.tileCountY in 1..1024 && map.tileCountX.toLong() * map.tileCountY <= PublicTerrainCatalog.MAX_VISUAL_CELLS) {
                "Unsupported public map dimensions"
            }
            val ground = checkNotNull(map.groundLayer) { "Public ground layer is missing" }
            val layers = checkNotNull(map.mapLayers) { "Public map layers are missing" }
            check(layers.all { it.name in setOf("Ground", "Items", "Units") } &&
                layers.map { it.name }.distinct().size == layers.size && layers.any { it === ground } &&
                ground.name == "Ground" && map.groundDetailsLayer == null && map.groundDetails2Layer == null &&
                map.groundOverlayLayer == null) { "Unsupported public map layer" }
            val items = layers.singleOrNull { it.name == "Items" }
            check(map.pathingOverrideLayer === items) { "Unsupported decoration layer" }
            val cells = map.tileCountX * map.tileCountY
            val groundIds = IntArray(cells)
            val decorationIds = IntArray(cells)
            val terrain = ByteArray(cells)
            for (y in 0 until map.tileCountY) for (x in 0 until map.tileCountX) {
                val index = y * map.tileCountX + x
                val base = checkNotNull(ground.getTileAt(x, y)) { "Public ground cell is missing" }
                val decoration = items?.getTileAt(x, y)
                groundIds[index] = tileId(base)
                decorationIds[index] = decoration?.let(::tileId) ?: 0
                terrain[index] = (bits(base) or bits(decoration)).toByte()
            }
            return baseline(map, terrain, PublicMapVisual(PublicTerrainCatalog.VERSION, groundIds, decorationIds))
        }

        /** Compatibility for engine projection fixtures without a loaded display tileset. */
        internal fun captureTerrainOnly(map: TileMap): EnginePublicMapSnapshot {
            val terrain = ByteArray(map.tileCountX * map.tileCountY)
            val items = map.pathingOverrideLayer?.takeIf { it.name == "Items" }
            for (y in 0 until map.tileCountY) for (x in 0 until map.tileCountX) {
                terrain[y * map.tileCountX + x] = (bits(map.groundLayer?.getTileAt(x, y)) or
                    bits(items?.getTileAt(x, y))).toByte()
            }
            return baseline(map, terrain, null)
        }

        private fun baseline(map: TileMap, terrain: ByteArray, visual: PublicMapVisual?) =
            EnginePublicMapSnapshot(map, map.tileCountX, map.tileCountY, map.tileWorldSizeX,
                map.tileWorldSizeY, terrain, visual)

        private fun tileId(tile: MapTile): Int {
            val tileset = checkNotNull(tile.tileset) { "Public tile has no tileset" }
            check(!tileset.containsUnits) { "Unit tilesets are not public terrain" }
            val id = checkNotNull(PublicTerrainCatalog.id(tileset.imageKey, tile.tilesetLocalIndex)) {
                "Unsupported public terrain tile"
            }
            val approved = checkNotNull(PublicTerrainCatalog.tile(id))
            val source = tileset.getTileRectCached(tile.tilesetLocalIndex)
            check(source.a == approved.sourceX && source.b == approved.sourceY &&
                source.c == approved.sourceX + approved.width && source.d == approved.sourceY + approved.height) {
                "Public tileset metrics differ from the approved catalog"
            }
            return id
        }

        private fun bits(tile: MapTile?): Int {
            if (tile == null) return 0
            return (if (tile.isWater) 1 else 0) or (if (tile.isCliff) 2 else 0) or
                (if (tile.isResourcePool) 4 else 0) or (if (tile.blocksBuildingPlacement) 8 else 0)
        }
    }
}
