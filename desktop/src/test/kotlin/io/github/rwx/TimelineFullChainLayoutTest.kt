package io.github.rwx

import kotlin.math.floor
import kotlin.test.*

/** Public-terrain geometry only: these checks do not boot the engine or run a capacity workload. */
class TimelineFullChainLayoutTest {
    private val size = 512
    private val tile = 20
    private val tankRadius = 11f
    private val baseRadius = 30f
    private fun plan(terrain: ByteArray = ByteArray(size * size),
                     scenario: TimelineFullChainLayout.Scenario = TimelineFullChainLayout.Scenario.REGIONAL) =
        TimelineFullChainLayout.plan(size, size, tile, tile, terrain, tankRadius, baseRadius, scenario)

    @Test fun `two scenarios share every coordinate with exact balanced ownership across all regions`() {
        val regional = plan()
        val interleaved = plan(scenario = TimelineFullChainLayout.Scenario.INTERLEAVED)
        assertEquals(64, regional.bases.size)
        assertEquals(65472, regional.tanks.size)
        assertEquals(regional.bases, interleaved.bases)
        assertEquals(regional.tanks.map { it.point }, interleaved.tanks.map { it.point })
        assertEquals(regional.availableTankSlotsPerRegion, interleaved.availableTankSlotsPerRegion)
        assertEquals(regional.spacing, interleaved.spacing)
        assertTrue(regional.availableTankSlotsPerRegion.all { it >= 1023 })
        assertEquals(65472, regional.tanks.map { it.point }.toSet().size)
        assertTrue(regional.tanks.all { it.teamId == it.region })
        for (placement in listOf(regional, interleaved)) {
            assertEquals((0..63).toSet(), placement.tanks.map { it.teamId }.toSet())
            assertTrue(placement.tanks.groupingBy { it.teamId }.eachCount().values.all { it == 1023 })
            assertTrue(placement.tanks.groupingBy { it.region }.eachCount().values.all { it == 1023 })
        }
        val shortTeams = HashSet<Int>()
        for (region in 0..63) {
            val local = interleaved.tanks.subList(region * 1023, (region + 1) * 1023)
            val counts = local.groupingBy { it.teamId }.eachCount()
            assertEquals(64, counts.size)
            assertEquals(63, counts.values.count { it == 16 })
            shortTeams.add(counts.entries.single { it.value == 15 }.key)
        }
        assertEquals(64, shortTeams.size)
        val first = interleaved.tanks.take(1023)
        val quadrants = first.groupBy { (if (it.point.x >= 640) 1 else 0) + (if (it.point.y >= 640) 2 else 0) }
        assertEquals(4, quadrants.size)
        assertTrue(quadrants.values.all { points -> points.map { it.teamId }.toSet().size >= 32 },
            "Owners must be spatially mixed rather than assigned whole strips")
        assertEquals(regional, plan())
    }

    @Test fun `row major blocked cells and resource pools preserve full footprint and cross region clearance`() {
        val terrain = ByteArray(size * size)
        for (y in 0 until size) for (x in 0 until size) terrain[y * size + x] = when {
            x % 64 == 31 && y % 64 == 2 -> 4 // Interrupt the preferred CC strip center.
            x % 64 == 35 && y % 64 in 20..42 -> 2
            x % 64 in 47..49 && y % 64 in 47..49 -> 8
            y == 17 -> 1
            else -> 0
        }.toByte()
        val original = terrain.copyOf()
        val layout = plan(terrain)
        assertContentEquals(original, terrain)
        fun clear(point: TimelineFullChainLayout.Point, radius: Float) {
            val left = floor((point.x - radius) / tile).toInt()
            val right = floor((point.x + radius) / tile).toInt()
            val top = floor((point.y - radius) / tile).toInt()
            val bottom = floor((point.y + radius) / tile).toInt()
            assertTrue(left >= 0 && top >= 0 && right < size && bottom < size)
            for (y in top..bottom) for (x in left..right) assertEquals(0, terrain[y * size + x].toInt())
        }
        for (base in layout.bases) clear(base, baseRadius + tile)
        val cells = HashMap<Pair<Int, Int>, MutableList<TimelineFullChainLayout.Point>>()
        for (owned in layout.tanks) {
            val point = owned.point
            clear(point, tankRadius + 1)
            val col = owned.region % 8
            val row = owned.region / 8
            assertTrue(point.x in (col * 1280f)..<(col + 1) * 1280f)
            assertTrue(point.y in (row * 1280f)..<(row + 1) * 1280f)
            val cell = floor(point.x / layout.spacing).toInt() to floor(point.y / layout.spacing).toInt()
            for (y in cell.second - 1..cell.second + 1) for (x in cell.first - 1..cell.first + 1) {
                for (prior in cells[x to y].orEmpty()) assertTrue(distanceSquared(point, prior) >= 26.0 * 26.0)
            }
            cells.getOrPut(cell) { ArrayList() }.add(point)
            for (base in layout.bases) assertTrue(distanceSquared(point, base) >= 61.0 * 61.0)
        }
        for (i in layout.bases.indices) for (j in 0 until i)
            assertTrue(distanceSquared(layout.bases[i], layout.bases[j]) >= 64.0 * 64.0)
    }

    @Test fun `a single region shortage rejects the whole placement instead of overlapping or borrowing capacity`() {
        val terrain = ByteArray(size * size)
        for (y in 3 * 64 until 4 * 64) for (x in 3 * 64 until 4 * 64) terrain[y * size + x] = 4
        val failure = assertFailsWith<IllegalStateException> { plan(terrain) }
        assertContains(failure.message.orEmpty(), "placementCapacity: region 27")
        val small = assertFailsWith<IllegalStateException> {
            TimelineFullChainLayout.plan(64, 64, tile, tile, ByteArray(64 * 64), tankRadius, baseRadius,
                TimelineFullChainLayout.Scenario.INTERLEAVED)
        }
        assertContains(small.message.orEmpty(), "placementCapacity")
    }

    @Test fun `malformed dimensions flags and radii fail before placement`() {
        assertFailsWith<IllegalArgumentException> { plan(ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { plan(ByteArray(size * size) { 16 }) }
        assertFailsWith<IllegalArgumentException> { plan(ByteArray(size * size) { -1 }) }
        for (radius in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, Float.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                TimelineFullChainLayout.plan(size, size, tile, tile, ByteArray(size * size), radius, baseRadius,
                    TimelineFullChainLayout.Scenario.REGIONAL)
            }
            assertFailsWith<IllegalArgumentException> {
                TimelineFullChainLayout.plan(size, size, tile, tile, ByteArray(size * size), tankRadius, radius,
                    TimelineFullChainLayout.Scenario.REGIONAL)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            TimelineFullChainLayout.plan(7, 8, tile, tile, ByteArray(56), tankRadius, baseRadius,
                TimelineFullChainLayout.Scenario.REGIONAL)
        }
    }

    private fun distanceSquared(a: TimelineFullChainLayout.Point, b: TimelineFullChainLayout.Point): Double {
        val dx = a.x.toDouble() - b.x
        val dy = a.y.toDouble() - b.y
        return dx * dx + dy * dy
    }
}
