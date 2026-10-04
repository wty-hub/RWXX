package com.corrodinggames.rts.gameFramework

import kotlin.test.*

class ReplayFogStateTest {
    @Test fun `shared allied vision matches separate circles through motion refog and map changes`() {
        val reference = ReplayFogState()
        val shared = ReplayFogState()
        val random = kotlin.random.Random(310)
        for (revealed in listOf(false, true)) {
            reference.configure(37, 29, revealed)
            shared.configure(37, 29, revealed)
            repeat(12) { frame ->
                reference.beginFrame(frame % 3 != 0)
                shared.beginFrame(frame % 3 != 0)
                repeat(40) {
                    val ids = (0 until 15).filter { random.nextBoolean() }.toIntArray()
                    val x = random.nextDouble(-20.0, 55.0).toFloat()
                    val y = random.nextDouble(-20.0, 50.0).toFloat()
                    val radius = random.nextInt(0, 26)
                    ids.forEach { reference.reveal(it, x, y, radius) }
                    shared.revealAll(ids, x, y, radius)
                }
                for (id in 0 until 15) {
                    val expected = reference.get(id)
                    val actual = shared.get(id)
                    if (expected == null) assertNull(actual)
                    else expected.indices.forEach { assertContentEquals(expected[it], actual[it], "team=$id frame=$frame column=$it") }
                }
            }
        }
    }
    @Test fun `vision moves refogs explored tiles and preserves unknown shroud`() {
        val fog = ReplayFogState()
        fog.configure(40, 40, false)
        fog.ensureTeam(0)
        fog.beginFrame(true)
        fog.reveal(0, 10f, 10f, 6)
        val data = fog.get(0)
        assertEquals(0, data[10][10].toInt())
        assertEquals(0, data[13][10].toInt())
        assertEquals(2, data[14][10].toInt())
        assertEquals(5, data[15][10].toInt())
        assertEquals(10, data[39][39].toInt())
        fog.beginFrame(true)
        fog.reveal(0, 25f, 25f, 6)
        assertEquals(5, data[10][10].toInt())
        assertEquals(0, data[25][25].toInt())
        assertEquals(10, data[39][39].toInt())
    }

    @Test fun `snapshots isolate future exploration and restore all player grids`() {
        val fog = ReplayFogState()
        fog.configure(40, 40, false)
        fog.reveal(0, 10f, 10f, 6)
        fog.reveal(1, 30f, 30f, 6)
        val checkpoint = fog.snapshot()
        fog.beginFrame(true)
        fog.reveal(0, 30f, 30f, 6)
        fog.restore(checkpoint)
        assertEquals(0, fog.get(0)[10][10].toInt())
        assertEquals(10, fog.get(0)[30][30].toInt())
        assertEquals(0, fog.get(1)[30][30].toInt())
        fog.reveal(0, 30f, 30f, 6)
        assertEquals(10, checkpoint.teams()[0]!![30][30].toInt())
    }

    @Test fun `revealed map permanent exploration small ranges and map replacement`() {
        val fog = ReplayFogState()
        fog.configure(10, 10, true)
        assertEquals(5, fog.ensureTeam(0)[9][9].toInt())
        fog.reveal(0, 0f, 0f, 1)
        fog.beginFrame(false)
        assertEquals(0, fog.get(0)[0][0].toInt())
        fog.reveal(0, -100f, -100f, 15)
        fog.configure(20, 20, false)
        assertNull(fog.get(0))
        assertEquals(10, fog.ensureTeam(0)[0][0].toInt())
        fog.reset()
        assertNull(fog.get(0))
        assertNull(fog.ensureTeam(0))
    }
}
