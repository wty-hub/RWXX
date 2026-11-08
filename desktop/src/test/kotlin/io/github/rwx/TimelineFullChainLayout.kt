package io.github.rwx

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Static geometry for the opt-in capacity harness; no engine, hidden state or collision changes. */
internal object TimelineFullChainLayout {
    enum class Scenario { REGIONAL, INTERLEAVED }
    data class Point(val x: Float, val y: Float)
    data class OwnedPoint(val point: Point, val teamId: Int, val region: Int)
    data class Placement(val bases: List<Point>, val tanks: List<OwnedPoint>, val spacing: Int,
                         val availableTankSlotsPerRegion: List<Int>)

    private enum class Side { TOP, BOTTOM, LEFT, RIGHT }
    private data class Slot(val point: Point, val morton: Long)
    private data class Region(val base: Point, val slots: List<Slot>)
    private const val GRID = 8
    private const val TANKS_PER_REGION = 1023

    fun plan(width: Int, height: Int, tileWidth: Int, tileHeight: Int, terrain: ByteArray,
             tankRadius: Float, baseRadius: Float, scenario: Scenario): Placement {
        require(width in GRID..1024 && height in GRID..1024 && tileWidth in 1..4096 && tileHeight in 1..4096)
        require(terrain.size == width * height && terrain.all { it.toInt() in 0..15 })
        val worldWidth = width * tileWidth
        val worldHeight = height * tileHeight
        require(tankRadius.isFinite() && baseRadius.isFinite() && tankRadius > 0 && baseRadius > 0 &&
            tankRadius <= max(worldWidth, worldHeight) && baseRadius <= max(worldWidth, worldHeight))
        val spacing = max(ceil(tankRadius.toDouble() * 2 + 4).toInt(),
            ceil(max(worldWidth, worldHeight).toDouble() / GRID / 48).toInt())
        val tankPadding = ceil(tankRadius.toDouble() + 2).toInt()
        val baseClearance = baseRadius.toDouble() + max(tileWidth, tileHeight)
        val basePadding = ceil(baseClearance + 2).toInt()
        val strip = basePadding * 2
        val exclusion = baseRadius.toDouble() + tankRadius + max(tileWidth, tileHeight)
        val prefix = IntArray((width + 1) * (height + 1))
        val stride = width + 1
        for (y in 0 until height) for (x in 0 until width) {
            val at = (y + 1) * stride + x + 1
            prefix[at] = (if (terrain[y * width + x].toInt() == 0) 0 else 1) +
                prefix[at - 1] + prefix[at - stride] - prefix[at - stride - 1]
        }
        fun clear(point: Point, radius: Double): Boolean {
            if (point.x - radius < 0 || point.y - radius < 0) return false
            val x0 = floor((point.x - radius) / tileWidth).toInt()
            val y0 = floor((point.y - radius) / tileHeight).toInt()
            val x1 = floor((point.x + radius) / tileWidth).toInt() + 1
            val y1 = floor((point.y + radius) / tileHeight).toInt() + 1
            if (x1 > width || y1 > height) return false
            return prefix[y1 * stride + x1] - prefix[y0 * stride + x1] -
                prefix[y1 * stride + x0] + prefix[y0 * stride + x0] == 0
        }
        val bases = ArrayList<Point>(64)
        val tanks = ArrayList<OwnedPoint>(64 * TANKS_PER_REGION)
        val capacities = ArrayList<Int>(64)
        repeat(64) { region ->
            val col = region % GRID
            val row = region / GRID
            val left = col * width / GRID * tileWidth
            val right = (col + 1) * width / GRID * tileWidth
            val top = row * height / GRID * tileHeight
            val bottom = (row + 1) * height / GRID * tileHeight
            var chosen: Region? = null
            var available = 0
            for (side in Side.entries) {
                if (right - left < 2 * basePadding || bottom - top < 2 * basePadding) continue
                val horizontal = side == Side.TOP || side == Side.BOTTOM
                val low = if (horizontal) left + basePadding else top + basePadding
                val high = if (horizontal) right - basePadding else bottom - basePadding
                val step = if (horizontal) tileWidth else tileHeight
                val center = (low.toLong() + high) / 2
                val candidates = (low..high step step).sortedWith(compareBy<Int> { kotlin.math.abs(it.toLong() - center) }.thenBy { it })
                val base = candidates.asSequence().map { at -> when (side) {
                    Side.TOP -> Point(at.toFloat(), (top + basePadding).toFloat())
                    Side.BOTTOM -> Point(at.toFloat(), (bottom - basePadding).toFloat())
                    Side.LEFT -> Point((left + basePadding).toFloat(), at.toFloat())
                    Side.RIGHT -> Point((right - basePadding).toFloat(), at.toFloat())
                } }.firstOrNull { clear(it, baseClearance) } ?: continue
                val x0 = left + tankPadding + if (side == Side.LEFT) strip else 0
                val x1 = right - tankPadding - if (side == Side.RIGHT) strip else 0
                val y0 = top + tankPadding + if (side == Side.TOP) strip else 0
                val y1 = bottom - tankPadding - if (side == Side.BOTTOM) strip else 0
                val slots = ArrayList<Slot>()
                if (x0 <= x1 && y0 <= y1) {
                    var gridY = 0
                    for (y in y0..y1 step spacing) {
                        var gridX = 0
                        for (x in x0..x1 step spacing) {
                            val point = Point(x.toFloat(), y.toFloat())
                            val dx = point.x.toDouble() - base.x
                            val dy = point.y.toDouble() - base.y
                            if (clear(point, tankRadius.toDouble() + 1) && dx * dx + dy * dy >= exclusion * exclusion)
                                slots.add(Slot(point, morton(gridX, gridY)))
                            gridX++
                        }
                        gridY++
                    }
                }
                available = max(available, slots.size)
                if (slots.size >= TANKS_PER_REGION) {
                    slots.sortBy { it.morton }
                    chosen = Region(base, slots)
                    break
                }
            }
            val selected = checkNotNull(chosen) {
                "placementCapacity: region $region has only $available clear tank slots; requires $TANKS_PER_REGION and one command center"
            }
            bases.add(selected.base)
            capacities.add(selected.slots.size)
            repeat(TANKS_PER_REGION) { index ->
                val slot = selected.slots[index.toLong().times(selected.slots.size).div(TANKS_PER_REGION).toInt()]
                val owner = if (scenario == Scenario.REGIONAL) region else (index + region) % 64
                tanks.add(OwnedPoint(slot.point, owner, region))
            }
        }
        return Placement(bases.toList(), tanks.toList(), spacing, capacities.toList())
    }

    private fun morton(x: Int, y: Int): Long {
        var result = 0L
        for (bit in 0 until 31) {
            result = result or (((x.toLong() ushr bit) and 1) shl (bit * 2))
            result = result or (((y.toLong() ushr bit) and 1) shl (bit * 2 + 1))
        }
        return result
    }
}
