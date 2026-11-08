package io.github.rwx.net.authoritative

/** Authorized presentation data; excludes orders, cargo, enemy resources and hidden references. */
data class ViewUnit(
    val handle: Long,
    val typeName: String,
    val teamId: Int,
    val x: Float,
    val y: Float,
    val height: Float,
    val heading: Float,
    val health: Float,
    val maxHealth: Float,
    val buildProgress: Float,
    val controllable: Boolean,
    /** Public visual tech level; queues, orders and target references remain engine-private. */
    val upgradeLevel: Int = 1,
)

/** A complete in-memory authorized sample. Timeline replication owns its wire representation. */
data class PlayerViewSnapshot(
    val sequence: Long,
    val tick: Int,
    val teamId: Int,
    val credits: Double,
    val mapWidth: Int,
    val mapHeight: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    /** Row-major fog[y * mapWidth + x], with engine values 0..10. */
    val fog: ByteArray,
    val units: List<ViewUnit>,
    /** Public static terrain: 1 water, 2 cliff, 4 resource pool, 8 blocks building. */
    val terrain: ByteArray,
    val matchStatus: MatchStatus = MatchStatus(),
    val mapVisual: PublicMapVisual? = null,
)

/** Public static presentation map. Cells and artwork are row-major y * width + x. */
data class PublicMapDefinition(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val terrain: ByteArray,
    val visual: PublicMapVisual? = null,
)
