package io.github.rwx.benchmark

import com.corrodinggames.rts.game.units.UnitMovementType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VanillaBattleBenchmarkTest {
    @Test fun `benchmark is absent by default and accepts only agreed unit counts`() {
        assertEquals(null, VanillaBattleBenchmark.options(emptyMap()))
        for (units in listOf(500, 661, 1000, 2000)) {
            assertEquals(units, VanillaBattleBenchmark.options(mapOf("RWX_BENCHMARK_UNITS" to "$units"))!!.units)
        }
        assertFailsWith<IllegalArgumentException> { VanillaBattleBenchmark.options(mapOf("RWX_BENCHMARK_UNITS" to "20")) }
    }
    @Test fun `sea scenario requires naval or airborne compatible types and land scenario excludes ships`() {
        assertTrue(VanillaBattleBenchmark.Mix.SeaAir.accepts(UnitMovementType.WATER))
        assertTrue(VanillaBattleBenchmark.Mix.SeaAir.accepts(UnitMovementType.AIR))
        assertFalse(VanillaBattleBenchmark.Mix.SeaAir.accepts(UnitMovementType.LAND))
        assertFalse(VanillaBattleBenchmark.Mix.LandAir.accepts(UnitMovementType.WATER))
        assertFalse(VanillaBattleBenchmark.Mix.All.accepts(UnitMovementType.BUILDING))
    }
    @Test fun `idle capacity is an explicit mode separate from combat acceptance`() {
        val environment = mapOf("RWX_BENCHMARK_UNITS" to "2000", "RWX_BENCHMARK_MODE" to "idle")
        assertEquals(VanillaBattleBenchmark.Mode.Idle, VanillaBattleBenchmark.options(environment)!!.mode)
        assertEquals(VanillaBattleBenchmark.Mode.Moving,
            VanillaBattleBenchmark.options(environment + ("RWX_BENCHMARK_MODE" to "moving"))!!.mode)
        assertEquals(VanillaBattleBenchmark.Mode.Combat,
            VanillaBattleBenchmark.options(environment - "RWX_BENCHMARK_MODE")!!.mode)
        assertFailsWith<IllegalStateException> {
            VanillaBattleBenchmark.options(environment + ("RWX_BENCHMARK_MODE" to "unknown"))
        }
    }
}
