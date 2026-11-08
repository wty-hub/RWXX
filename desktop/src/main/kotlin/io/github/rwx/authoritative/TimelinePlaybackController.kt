package io.github.rwx.authoritative

import io.github.rwx.net.timeline.TimelineProtocol
import java.math.BigDecimal
import java.math.BigInteger

internal data class TimelinePlaybackPosition(val paneId: Int, val time: Long, val followsLive: Boolean,
    val paused: Boolean, val speed: Double, val buffering: Boolean)

/** UI clock only. It neither predicts game state nor changes any wire receipt or watermark. */
internal class TimelinePlaybackController(
    val ticksPerSecond: Int,
    val historyFrom: Long = 0,
    val renderDelayMillis: Int = 100,
) {
    private data class Rate(val numerator: BigInteger, val denominator: BigInteger, val negative: Boolean,
        val smallNumerator: Long?, val smallDenominator: Long?)
    private data class Pane(var time: Long, var live: Boolean, var speed: Double = 0.0, var rate: Rate? = null,
        var anchorTime: Long = time, var anchorNanos: Long? = null, var lastNanos: Long? = null,
        var buffering: Boolean = false)
    private val panes = linkedMapOf(0 to Pane(historyFrom, true))
    private var nextPane = 1
    private var liveTime = historyFrom
    private var liveFrom = historyFrom
    private var liveUntil = historyFrom + 1
    private var liveReceivedAt: Long? = null
    private var terminalTime: Long? = null
    private val liveRate = rate(1.0)
    init {
        require(ticksPerSecond in 1..1000 && historyFrom in 0..TimelineProtocol.MAX_TIME && renderDelayMillis in 0..500)
    }

    @Synchronized fun paneIds(): List<Int> = panes.keys.toList()
    @Synchronized fun addPane(time: Long): Int {
        require(panes.size < 4) { "At most four time panes are supported" }
        require(time in historyFrom..TimelineProtocol.MAX_TIME)
        check(nextPane < Int.MAX_VALUE) { "Pane identity exhausted" }
        return nextPane++.also { panes[it] = Pane(time, false) }
    }
    @Synchronized fun removePane(id: Int) { require(id != 0) { "The primary pane cannot be removed" }; panes.remove(id) }
    @Synchronized fun followsLive(id: Int): Boolean = pane(id).live

    @Synchronized fun updateLive(observed: Long, from: Long, until: Long, nowNanos: Long, finishedAt: Long? = null) {
        require(from >= historyFrom && observed in from..TimelineProtocol.MAX_TIME && until > observed)
        require(finishedAt == null || finishedAt in historyFrom..observed)
        if (liveReceivedAt == null || observed != liveTime) {
            liveTime = observed
            liveReceivedAt = maxOf(nowNanos, liveReceivedAt ?: nowNanos)
        }
        liveFrom = from; liveUntil = until; terminalTime = finishedAt
    }

    @Synchronized fun followLive(id: Int = 0) {
        pane(id).apply { live = true; speed = 1.0; rate = liveRate; buffering = false; anchorNanos = null }
    }
    @Synchronized fun seek(id: Int, time: Long, nowNanos: Long = System.nanoTime()) {
        require(time in historyFrom..TimelineProtocol.MAX_TIME)
        pane(id).apply { this.time = time; if (live) { speed = 0.0; rate = null }; live = false; buffering = false; reanchor(this, nowNanos) }
    }
    @Synchronized fun pause(id: Int = 0, nowNanos: Long = System.nanoTime()) {
        val position = position(id, nowNanos)
        pane(id).apply { time = position.time; live = false; speed = 0.0; rate = null; buffering = false; reanchor(this, nowNanos) }
    }
    /** The supplied finite decimal speed is exact in the UI clock; negative values play backward. */
    @Synchronized fun play(id: Int = 0, speed: Double = 1.0, nowNanos: Long = System.nanoTime()) {
        require(speed.isFinite() && kotlin.math.abs(speed) in 0.25..8.0)
        val position = position(id, nowNanos)
        pane(id).apply { time = position.time; live = false; this.speed = speed
            rate = this@TimelinePlaybackController.rate(speed); reanchor(this, nowNanos) }
    }
    @Synchronized fun setBuffering(id: Int, buffering: Boolean, nowNanos: Long) {
        val p = pane(id)
        if (p.buffering != buffering) {
            position(id, nowNanos)
            p.buffering = buffering; reanchor(p, nowNanos)
        }
    }

    @Synchronized fun position(id: Int = 0, nowNanos: Long): TimelinePlaybackPosition {
        val p = pane(id)
        val now = maxOf(nowNanos, p.lastNanos ?: nowNanos)
        p.lastNanos = now
        if (p.live) {
            val received = liveReceivedAt
            val elapsed = if (received == null) 0 else elapsed(now, received)
            val candidate = addTime(liveTime, delta(elapsed, liveRate))
            val delay = ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS * renderDelayMillis / 1000
            p.time = (candidate - minOf(candidate, delay)).coerceIn(liveFrom, liveUntil - 1)
            terminalTime?.let { p.time = minOf(p.time, it).coerceAtLeast(liveFrom) }
        } else if (!p.buffering && p.speed != 0.0) {
            val anchor = p.anchorNanos ?: now.also { p.anchorNanos = it; p.anchorTime = p.time }
            p.time = addTime(p.anchorTime, delta(elapsed(now, anchor), checkNotNull(p.rate))).coerceAtLeast(historyFrom)
        }
        return TimelinePlaybackPosition(id, p.time, p.live, !p.live && p.speed == 0.0,
            if (p.live) 1.0 else p.speed, p.buffering)
    }

    private fun pane(id: Int) = requireNotNull(panes[id]) { "Unknown time pane" }
    private fun reanchor(pane: Pane, now: Long) {
        val monotonic = maxOf(now, pane.lastNanos ?: now)
        pane.anchorTime = pane.time; pane.anchorNanos = monotonic; pane.lastNanos = monotonic
    }
    private fun elapsed(now: Long, then: Long): Long = if (now <= then) 0 else
        try { Math.subtractExact(now, then) } catch (_: ArithmeticException) { Long.MAX_VALUE }
    private fun rate(speed: Double): Rate {
        val decimal = BigDecimal.valueOf(kotlin.math.abs(speed)).stripTrailingZeros()
        var numerator = decimal.unscaledValue().multiply(BigInteger.valueOf(ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS))
        var denominator = BigInteger.valueOf(1_000_000_000L)
        if (decimal.scale() >= 0) denominator = denominator.multiply(BigInteger.TEN.pow(decimal.scale()))
        else numerator = numerator.multiply(BigInteger.TEN.pow(-decimal.scale()))
        val divisor = numerator.gcd(denominator)
        numerator /= divisor; denominator /= divisor
        return Rate(numerator, denominator, speed < 0,
            numerator.takeIf { it.bitLength() < 64 }?.toLong(), denominator.takeIf { it.bitLength() < 64 }?.toLong())
    }
    private fun delta(nanos: Long, rate: Rate): Long {
        val magnitude = if (rate.smallNumerator != null && rate.smallDenominator != null) {
            try { Math.multiplyExact(nanos, rate.smallNumerator) / rate.smallDenominator }
            catch (_: ArithmeticException) { bigDelta(nanos, rate) }
        } else bigDelta(nanos, rate)
        return if (rate.negative) -magnitude else magnitude
    }
    private fun bigDelta(nanos: Long, rate: Rate): Long {
        val result = BigInteger.valueOf(nanos).multiply(rate.numerator).divide(rate.denominator)
        return if (result.bitLength() >= 64) Long.MAX_VALUE else result.toLong()
    }
    private fun addTime(time: Long, delta: Long): Long =
        if (delta >= 0) if (time > TimelineProtocol.MAX_TIME - delta) TimelineProtocol.MAX_TIME else time + delta
        else if (time < -delta) 0 else time + delta
}
