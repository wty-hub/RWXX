package io.github.rwx.authoritative

import io.github.rwx.net.timeline.TimelineProtocol
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.*

class TimelinePlaybackControllerTest {
    private val second = 60L * 1024

    @Test fun `live display delay uses granted curves only and terminal time never advances`() {
        val playback = TimelinePlaybackController(60, renderDelayMillis = 100)
        playback.updateLive(10 * second, 9 * second, 11 * second, 0)
        assertEquals(10 * second - second / 10, playback.position(nowNanos = 0).time)
        assertEquals(10 * second - second / 20, playback.position(nowNanos = 50_000_000).time)
        assertEquals(11 * second - 1, playback.position(nowNanos = 20_000_000_000).time)
        playback.updateLive(12 * second + 1, 10 * second, 13 * second, 21_000_000_000, 12 * second)
        assertEquals(12 * second, playback.position(nowNanos = 100_000_000_000).time)
        assertTrue(playback.position(nowNanos = 200_000_000_000).followsLive)
    }

    @Test fun `pause seek reverse and buffering use exact independent clocks`() {
        val playback = TimelinePlaybackController(60, historyFrom = second, renderDelayMillis = 0)
        playback.updateLive(10 * second, second, 11 * second, 0)
        playback.pause(nowNanos = 0)
        assertEquals(10 * second, playback.position(nowNanos = 100_000_000).time)
        playback.seek(0, 5 * second, 100_000_000)
        playback.play(speed = -0.25, nowNanos = 100_000_000)
        assertEquals(5 * second - second / 4, playback.position(nowNanos = 1_100_000_000).time)
        // A regressing wall clock cannot replay an already elapsed interval twice.
        assertEquals(5 * second - second / 4, playback.position(nowNanos = 500_000_000).time)
        playback.setBuffering(0, true, 1_100_000_000)
        assertEquals(5 * second - second / 4, playback.position(nowNanos = 10_000_000_000).time)
        playback.setBuffering(0, false, 10_000_000_000)
        assertEquals(5 * second - second / 2, playback.position(nowNanos = 11_000_000_000).time)
        assertEquals(second, playback.position(nowNanos = 100_000_000_000).time)
        playback.followLive()
        playback.seek(0, 2 * second, 100_000_000_000)
        assertTrue(playback.position(nowNanos = 101_000_000_000).paused)
        assertEquals(2 * second, playback.position(nowNanos = 101_000_000_000).time)
    }

    @Test fun `four panes stay independent and long time calculations saturate without wrapping`() {
        val playback = TimelinePlaybackController(1000, renderDelayMillis = 0)
        val one = playback.addPane(TimelineProtocol.MAX_TIME - 1000)
        val two = playback.addPane(2000)
        val three = playback.addPane(3000)
        assertFailsWith<IllegalArgumentException> { playback.addPane(4000) }
        playback.play(one, 8.0, Long.MIN_VALUE)
        assertEquals(TimelineProtocol.MAX_TIME, playback.position(one, Long.MAX_VALUE).time)
        assertEquals(2000L, playback.position(two, Long.MAX_VALUE).time)
        assertEquals(3000L, playback.position(three, Long.MAX_VALUE).time)
        playback.removePane(two)
        assertTrue(playback.addPane(4000) > three)
        assertFailsWith<IllegalArgumentException> { playback.removePane(0) }
        assertFailsWith<IllegalArgumentException> { playback.play(0, Double.NaN, 0) }
        assertFailsWith<IllegalArgumentException> { playback.play(0, 0.1, 0) }
    }

    @Test fun `arbitrary CLI decimal speeds remain exact without quarter speed rounding`() {
        val start = 10_000_000_000L
        for (speed in listOf(0.3, 0.3333333333333333, 1.2345678901234567, 7.999999999999999, -0.3, -1.2345678901234567)) {
            val playback = TimelinePlaybackController(997, renderDelayMillis = 0)
            playback.seek(0, start, 0); playback.play(0, speed, 0)
            for (nanos in listOf(1L, 999_999_999L, 1_000_000_000L, 12_345_678_901L)) {
                val magnitude = BigDecimal.valueOf(nanos).multiply(BigDecimal.valueOf(997L * 1024))
                    .multiply(BigDecimal.valueOf(kotlin.math.abs(speed))).divide(BigDecimal.valueOf(1_000_000_000), 0, RoundingMode.DOWN).longValueExact()
                val expected = start + if (speed < 0) -magnitude else magnitude
                assertEquals(expected, playback.position(0, nanos).time)
                assertEquals(speed, playback.position(0, nanos).speed)
            }
        }
    }
}
