package io.github.rwx

import kotlin.math.hypot
import kotlin.test.*

/** Pure CLI and public-terrain checks. No engine boot, GUI, capacity run or background worker. */
class TimelineScaleAcceptanceTest {
    private fun parse(vararg values: String) = TimelineScaleAcceptanceMain.options(arrayOf("--output=scale-test-new.json", *values))

    @Test fun `defaults and explicit limits select an approved workload only`() {
        val defaults = parse()
        assertEquals(1000, defaults.units); assertEquals(2, defaults.seats)
        assertEquals(120, defaults.ticks); assertEquals(120, defaults.deadlineSeconds)
        assertEquals("none", defaults.projection); assertEquals(AuthoritativeDesktopMain.DEFAULT_MAP, defaults.mapPath)
        assertTrue(defaults.output.isAbsolute)
        val large = parse("--units=65536", "--seats=64", "--ticks=10000", "--deadline-seconds=600", "--projection=publisher", "--map=arena")
        assertEquals(65536, large.units); assertEquals(64, large.seats)
        assertEquals(TimelineDesktopServer.ARENA_MAP, large.mapPath)
        assertEquals(TimelineDesktopServer.LARGE_ARENA_MAP, parse("--map=large-arena").mapPath)
    }

    @Test fun `unknown duplicate malformed and unsafe options are rejected before engine initialization`() {
        for (args in listOf(arrayOf("--seats=1"), arrayOf("--seats=65"), arrayOf("--units=65537"),
            arrayOf("--seats=64", "--units=127"), arrayOf("--ticks=0"), arrayOf("--deadline-seconds=0"),
            arrayOf("--deadline-seconds=601"), arrayOf("--units=12", "--units=12"), arrayOf("--projection=all-seats"),
            arrayOf("--map=../map.tmx"), arrayOf("--units"), arrayOf("--not-a-setting=1"))) {
            assertFailsWith<IllegalArgumentException>(args.contentToString()) { parse(*args) }
        }
        assertFailsWith<IllegalArgumentException> { TimelineScaleAcceptanceMain.options(emptyArray()) }
    }

    @Test fun `layout is deterministic nonoverlapping and keeps exactly one base per seat with balanced native tank slots`() {
        val terrain = ByteArray(128 * 128)
        val before = terrain.copyOf()
        val layout = TimelineScaleAcceptanceMain.plan(128, 128, 20, 20, terrain, 64, 1024, 11f, 30f)
        assertEquals(layout, TimelineScaleAcceptanceMain.plan(128, 128, 20, 20, terrain.copyOf(), 64, 1024, 11f, 30f))
        assertContentEquals(before, terrain)
        assertEquals(64, layout.bases.size); assertEquals(960, layout.tanks.size)
        assertEquals(64, layout.bases.toSet().size); assertEquals(960, layout.tanks.toSet().size)
        val perSeat = IntArray(64)
        layout.tanks.indices.forEach { perSeat[it % 64]++ }
        assertTrue(perSeat.all { it == 15 }, "Every human seat receives the same number of real movable tank slots")
        for (i in layout.bases.indices) for (j in 0 until i) assertTrue(distance(layout.bases[i], layout.bases[j]) >= 60)
        for (i in layout.tanks.indices) {
            for (j in 0 until i) assertTrue(distance(layout.tanks[i], layout.tanks[j]) >= 22)
            for (base in layout.bases) assertTrue(distance(base, layout.tanks[i]) >= 61)
        }
    }

    @Test fun `layout respects asymmetric row major terrain and full radius footprints`() {
        val width = 64; val height = 48; val terrain = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) terrain[y * width + x] = when {
            x < 8 -> 1
            y in 20..22 -> 2
            x in 28..30 -> 4
            x > 48 && y < 12 -> 8
            else -> 0
        }.toByte()
        val layout = TimelineScaleAcceptanceMain.plan(width, height, 20, 20, terrain, 4, 100, 11f, 30f)
        fun clear(point: TimelineScaleAcceptanceMain.Point, radius: Float) {
            val left = ((point.x - radius) / 20).toInt(); val right = ((point.x + radius) / 20).toInt()
            val top = ((point.y - radius) / 20).toInt(); val bottom = ((point.y + radius) / 20).toInt()
            assertTrue(left >= 0 && top >= 0 && right < width && bottom < height)
            for (y in top..bottom) for (x in left..right) assertEquals(0, terrain[y * width + x].toInt())
        }
        layout.bases.forEach { clear(it, 50f) }; layout.tanks.forEach { clear(it, 12f) }
    }

    @Test fun `insufficient real land fails instead of reusing coordinates or silently reducing unit count`() {
        assertFailsWith<IllegalStateException> { TimelineScaleAcceptanceMain.plan(32, 32, 20, 20, ByteArray(1024) { 1 }, 2, 20, 11f, 30f) }
        val error = assertFailsWith<IllegalStateException> { TimelineScaleAcceptanceMain.plan(16, 16, 20, 20, ByteArray(256), 2, 1000, 11f, 30f) }
        assertContains(error.message.orEmpty(), "placementCapacity")
        assertFailsWith<IllegalArgumentException> { TimelineScaleAcceptanceMain.plan(16, 16, 20, 20, ByteArray(255), 2, 20, 11f, 30f) }
        assertFailsWith<IllegalArgumentException> { TimelineScaleAcceptanceMain.plan(16, 16, 20, 20, ByteArray(256) { 16 }, 2, 20, 11f, 30f) }
        assertFailsWith<IllegalArgumentException> { TimelineScaleAcceptanceMain.plan(16, 16, 20, 20, ByteArray(256), 2, 20, Float.NaN, 30f) }
    }

    @Test fun `move endpoints stay in bounds and cross only public walkable cells`() {
        val terrain = ByteArray(10 * 10)
        val target = TimelineScaleAcceptanceMain.movementTarget(195f, 195f, 10, 10, 20, 20, terrain)
        assertTrue(target.x in 0f..<200f && target.y in 0f..<200f)
        assertTrue(hypot((target.x - 195f).toDouble(), (target.y - 195f).toDouble()) >= 20.0)
        val blocked = ByteArray(100) { 1 }
        blocked[0] = 0
        assertFailsWith<IllegalStateException> { TimelineScaleAcceptanceMain.movementTarget(5f, 5f, 10, 10, 20, 20, blocked) }
    }

    private fun distance(a: TimelineScaleAcceptanceMain.Point, b: TimelineScaleAcceptanceMain.Point) =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
}
