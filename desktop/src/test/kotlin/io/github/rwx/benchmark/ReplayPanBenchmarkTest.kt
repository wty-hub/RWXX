package io.github.rwx.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayPanBenchmarkTest {
    private val enabled = mapOf("RWX_REPLAY_PAN_OUTPUT" to "replay-pan.ndjson")

    @Test fun `ordinary pan and jump never opt into zoom`() {
        assertEquals(null, ReplayPanBenchmark.options(emptyMap()))
        val defaults = ReplayPanBenchmark.options(enabled)!!
        assertEquals("pan", defaults.cameraMode)
        assertEquals("none", defaults.zoomMode)
        assertEquals(null, defaults.cameraTrace)
        val jump = ReplayPanBenchmark.options(enabled + ("RWX_REPLAY_PAN_CAMERA_MODE" to "jump"))!!
        assertEquals("jump", jump.cameraMode)
        assertEquals("none", jump.zoomMode)
    }

    @Test fun `continuous zoom requires an explicit mode and rejects invalid bounds`() {
        val alias = ReplayPanBenchmark.options(enabled + ("RWX_REPLAY_PAN_CAMERA_MODE" to "pan-zoom"))!!
        assertEquals("pan", alias.cameraMode)
        assertEquals("cycle", alias.zoomMode)
        assertEquals(.35f, alias.zoomMinimum)
        assertEquals(1.5f, alias.zoomMaximum)
        assertEquals("cycle", ReplayPanBenchmark.options(enabled + ("RWX_REPLAY_PAN_ZOOM_MODE" to "cycle"))!!.zoomMode)
        for (entry in listOf("RWX_REPLAY_PAN_ZOOM_MODE" to "step", "RWX_REPLAY_PAN_ZOOM_MIN" to "NaN",
            "RWX_REPLAY_PAN_ZOOM_MIN" to "0", "RWX_REPLAY_PAN_ZOOM_MAX" to "Infinity",
            "RWX_REPLAY_PAN_ZOOM_MAX" to ".2", "RWX_REPLAY_PAN_ZOOM_PERIOD_SECONDS" to "0")) {
            assertFailsWith<IllegalArgumentException> { ReplayPanBenchmark.options(enabled + entry) }
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayPanBenchmark.options(enabled + mapOf("RWX_REPLAY_PAN_CAMERA_MODE" to "pan-zoom", "RWX_REPLAY_PAN_ZOOM_MODE" to "none"))
        }
    }

    @Test fun `cycle starts at ordinary zoom and visits both bounds continuously`() {
        fun target(phase: Double) = ReplayPanBenchmark.cycleZoom(phase, .35f, 1.5f)
        assertEquals(1f, target(0.0))
        assertEquals(.35f, target(.25))
        assertEquals(1.5f, target(.75))
        assertEquals(1f, target(1.0))
        for (index in 0..1000) assertTrue(target(index / 1000.0) in .35f..1.5f)
        for (edge in listOf(.25, .75, 1.0)) assertEquals(target(edge), target(edge - .00001), .0001f)
    }

    @Test fun `target requests respect fit map and density scaled UI zoom limits`() {
        fun constrained(request: Float, density: Float = 1f) = ReplayPanBenchmark.constrainedTargetZoom(
            request, 1280f, 720f, 4000f, 2000f, density)
        assertEquals(.32f, constrained(.1f))
        assertEquals(4.6f, constrained(10f))
        assertEquals(.16f, constrained(.1f, 2f))
        assertEquals(2.3f, constrained(10f, 2f))
        assertEquals(.75f, constrained(.75f))
    }
}
