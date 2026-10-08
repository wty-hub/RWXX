package io.github.rwx.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.corrodinggames.rts.gameFramework.network.GameInputStream
import java.nio.file.Files

class ReplayPanBenchmarkTest {
    private val enabled = mapOf("RWX_REPLAY_PAN_OUTPUT" to "replay-pan.ndjson")

    @Test fun `embedded map export preserves stream position and refuses overwrites`() {
        val directory = Files.createTempDirectory("rwx-map-export").toFile()
        try {
            val bytes = "<map width=\"320\" height=\"200\"/>".toByteArray()
            val stream = GameInputStream(bytes)
            stream.activeInputStream.skipNBytes(7)
            val destination = directory.resolve("europe.tmx")
            val (size, digest) = ReplayPanBenchmark.exportMap(stream, destination)
            assertEquals(bytes.size, size)
            assertTrue(bytes.contentEquals(destination.readBytes()))
            assertEquals(64, digest.length)
            assertEquals(bytes.size - 7, stream.activeInputStream.available())
            assertEquals(bytes[7].toInt() and 255, stream.activeInputStream.read())
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { ReplayPanBenchmark.exportMap(stream, destination) }
            assertEquals(bytes.size - 8, stream.activeInputStream.available())
            assertTrue(bytes.contentEquals(destination.readBytes()))
            assertFailsWith<IllegalArgumentException> {
                ReplayPanBenchmark.options(mapOf("RWX_MAP_PAN_OUTPUT" to "local.ndjson", "RWX_EXPORT_REPLAY_MAP" to destination.path))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `edge camera spans the true map and oversized viewports keep native centering`() {
        val edge = ReplayPanBenchmark.options(enabled + ("RWX_REPLAY_PAN_CAMERA_MODE" to "edge-jump"))!!
        assertEquals("edge-jump", edge.cameraMode)
        assertEquals(0f, ReplayPanBenchmark.edgePosition(6400f, 1280f, false))
        assertEquals(5120f, ReplayPanBenchmark.edgePosition(6400f, 1280f, true))
        assertEquals(-100f, ReplayPanBenchmark.edgePosition(6400f, 6600f, false))
        assertEquals(-100f, ReplayPanBenchmark.edgePosition(6400f, 6600f, true))
    }

    @Test fun `death stimulus requires a fog enabled ordinary local map without injected units`() {
        val local = mapOf("RWX_MAP_PAN_OUTPUT" to "local.ndjson", "RWX_BENCHMARK_FOG" to "on",
            "RWX_BENCHMARK_PLAYER_DEATH_SECONDS" to "20")
        val valid = ReplayPanBenchmark.options(local)!!
        assertEquals(20, valid.playerDeathSeconds)
        assertEquals(2, valid.localFogMode)
        assertEquals(0, ReplayPanBenchmark.options(local - "RWX_BENCHMARK_PLAYER_DEATH_SECONDS" + ("RWX_BENCHMARK_FOG" to "off"))!!.localFogMode)
        for (invalid in listOf(local + ("RWX_BENCHMARK_UNITS" to "500"), local + ("RWX_BENCHMARK_FOG" to "off"),
            local + ("RWX_BENCHMARK_PLAYER_DEATH_SECONDS" to "0"),
            enabled + ("RWX_BENCHMARK_PLAYER_DEATH_SECONDS" to "20"))) {
            assertFailsWith<IllegalArgumentException> { ReplayPanBenchmark.options(invalid) }
        }
    }

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
