package io.github.rwx

import kotlin.math.abs
import kotlin.test.*

/** Geometry/public-terrain tests only. These do not initialize an engine or claim room capacity. */
class TimelineStaticSpawnsTest {
    @Test fun `seat patches are deterministic separated and preserve the public terrain input`() {
        val width = 160; val height = 160; val terrain = ByteArray(width * height)
        val original = terrain.copyOf()
        val starts = listOf(TimelineSpawnTile(49, 83), TimelineSpawnTile(46, 18))
        val first = TimelineStaticSpawns.plan(width, height, terrain, starts)
        val second = TimelineStaticSpawns.plan(width, height, terrain.copyOf(), starts.toList())
        assertEquals(first, second); assertContentEquals(original, terrain)
        assertEquals((2..63).toList(), first.keys.toList())
        for ((_, patch) in first) {
            assertEquals(patch.commandCenter.x + 4, patch.builder.x)
            assertEquals(patch.commandCenter.y, patch.builder.y)
            assertTrue(patch.commandCenter.x in 2 until width - 5)
            assertTrue(patch.commandCenter.y in 2 until height - 3)
            for (start in starts) assertSeparated(start, patch.commandCenter)
        }
        val points = first.values.map { it.commandCenter }
        for (i in points.indices) for (j in 0 until i) assertSeparated(points[i], points[j])
        assertEquals(first[37], second[37], "Seat identity does not depend on connection arrival order")
    }

    @Test fun `every reserved command center and builder patch excludes all four public blocking flags row major`() {
        val width = 64; val height = 48
        val terrain = ByteArray(width * height)
        // Deliberately asymmetric obstacles expose x/y or row/column transposition.
        for (y in 0 until height) for (x in 0 until width) {
            terrain[y * width + x] = when {
                x < 12 -> 1 // water
                x in 25..28 -> 2 // cliff
                y in 20..23 -> 4 // resource pool
                x in 45..47 && y > 30 -> 8 // static building prohibition
                else -> 0
            }.toByte()
        }
        val patches = TimelineStaticSpawns.plan(width, height, terrain)
        assertTrue(patches.isNotEmpty())
        for (patch in patches.values) for (dy in -2..3) for (dx in -2..5) {
            val x = patch.commandCenter.x + dx; val y = patch.commandCenter.y + dy
            assertEquals(0, terrain[y * width + x].toInt(), "Reserved land footprint crosses blocked public terrain")
        }
    }

    @Test fun `missing land stays unavailable without a hidden occupancy lookup or fallback spawn`() {
        assertTrue(TimelineStaticSpawns.plan(40, 40, ByteArray(1600) { 1 }).isEmpty())
        assertTrue(TimelineStaticSpawns.plan(7, 5, ByteArray(35)).isEmpty())
        val width = 30; val height = 20
        val terrain = ByteArray(width * height) { 1 }
        for (y in 8..13) for (x in 12..19) terrain[y * width + x] = 0
        val only = TimelineStaticSpawns.plan(width, height, terrain)
        assertEquals(setOf(2), only.keys)
        assertEquals(TimelineSpawnTile(14, 10), only.getValue(2).commandCenter)
        assertNull(only[3])
    }

    @Test fun `invalid dimensions terrain bits and public start anchors are rejected`() {
        assertFailsWith<IllegalArgumentException> { TimelineStaticSpawns.plan(0, 2, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { TimelineStaticSpawns.plan(4, 4, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { TimelineStaticSpawns.plan(10, 10, ByteArray(100) { 16 }) }
        assertFailsWith<IllegalArgumentException> { TimelineStaticSpawns.plan(10, 10, ByteArray(100), listOf(TimelineSpawnTile(-1, 4))) }
    }

    private fun assertSeparated(a: TimelineSpawnTile, b: TimelineSpawnTile) =
        assertTrue(abs(a.x - b.x) >= TimelineStaticSpawns.SPACING || abs(a.y - b.y) >= TimelineStaticSpawns.SPACING,
            "Reserved starting regions overlap: $a and $b")
}
