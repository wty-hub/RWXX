package io.github.rwx.app

import kotlin.test.*

class LocalBenchmarkFogModeTest {
    @Test fun `only explicit local measurement config changes fog before ordinary map loading`() {
        assertNull(localBenchmarkFogMode(emptyMap()))
        assertNull(localBenchmarkFogMode(mapOf("RWX_BENCHMARK_FOG" to "off")))
        assertNull(localBenchmarkFogMode(mapOf("RWX_REPLAY_PAN_OUTPUT" to "replay.ndjson", "RWX_BENCHMARK_FOG" to "off")))
        val local = mapOf("RWX_MAP_PAN_OUTPUT" to "live.ndjson")
        assertNull(localBenchmarkFogMode(local))
        assertEquals(0, localBenchmarkFogMode(local + ("RWX_BENCHMARK_FOG" to "off")))
        assertEquals(2, localBenchmarkFogMode(local + ("RWX_BENCHMARK_FOG" to "on")))
        assertFailsWith<IllegalStateException> { localBenchmarkFogMode(local + ("RWX_BENCHMARK_FOG" to "unexpected")) }
    }
}
