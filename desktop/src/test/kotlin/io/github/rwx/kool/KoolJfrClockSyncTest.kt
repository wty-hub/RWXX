package io.github.rwx.kool

import java.nio.file.Files
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoolJfrClockSyncTest {
    @Test
    fun `recorded clock markers retain monotonic bounds and are rate limited`() {
        val file = Files.createTempFile("rwx-clock-sync", ".jfr")
        try {
            Recording().use { recording ->
                recording.enable("rwx.ClockSync")
                recording.start()
                val before = System.nanoTime()
                val sampler = KoolJfrClockSampler()
                sampler.sample(1)
                sampler.sample(2)
                sampler.sample(1_000_000_002)
                val after = System.nanoTime()
                recording.stop()
                recording.dump(file)
                val events = RecordingFile.readAllEvents(file).filter { it.eventType.name == "rwx.ClockSync" }
                assertEquals(2, events.size)
                for (event in events) {
                    val start = event.getLong("monoStart")
                    val end = event.getLong("monoEnd")
                    assertTrue(start in before..after)
                    assertTrue(end in start..after)
                    assertTrue(event.getLong("epochMillis") > 0)
                    assertEquals(Thread.currentThread().threadId(), event.thread.javaThreadId)
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
