package io.github.rwx.kool

import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

/** JFR's native clock and System.nanoTime can drift on Windows; record both in the same event. */
@Name("rwx.ClockSync")
@Label("RWX diagnostic clock sync")
@StackTrace(false)
internal class KoolJfrClockSync : Event() {
    @JvmField var monoStart: Long = 0
    @JvmField var monoEnd: Long = 0
    @JvmField var epochMillis: Long = 0
}

/** Used only while the opt-in engine frame trace is enabled. */
internal class KoolJfrClockSampler {
    private var nextSample = 0L

    fun sample(now: Long) {
        if (now < nextSample) return
        nextSample = now + 1_000_000_000L
        val event = KoolJfrClockSync()
        if (!event.isEnabled) return
        event.begin()
        event.monoStart = System.nanoTime()
        event.epochMillis = System.currentTimeMillis()
        event.monoEnd = System.nanoTime()
        event.end()
        event.commit()
    }
}
