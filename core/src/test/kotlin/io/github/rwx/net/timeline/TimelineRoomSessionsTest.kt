package io.github.rwx.net.timeline

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** No engine or extra JVM: controllable worker futures exercise cancellation/cleanup ownership. */
class TimelineRoomSessionsTest {
    @Test fun `cancelled admission stays tracked until its own worker cleanup completes`() {
        val sessions = TimelineRoomSessions()
        val stopped = CompletableFuture<Unit>(); val closes = AtomicInteger()
        val reservation = assertNotNull(sessions.reserve())
        reservation.attach(stopped) { closes.incrementAndGet() }
        reservation.close()
        assertEquals(1, closes.get()); assertEquals(0 to 1, sessions.counts())
        val roomStopped = sessions.stoppedAsync()
        assertFalse(roomStopped.isDone)
        stopped.complete(Unit)
        roomStopped.get(1, TimeUnit.SECONDS)
        assertEquals(0 to 0, sessions.counts())
        reservation.close(); assertEquals(1, closes.get())
    }

    @Test fun `extra cleanup budget rejects more creation without displacing a formal grant`() {
        val sessions = TimelineRoomSessions(formalLimit = 2, extraLimit = 1)
        val formalStopped = CompletableFuture<Unit>(); val formalCloses = AtomicInteger()
        val formal = assertNotNull(sessions.reserve())
        formal.attach(formalStopped) { formalCloses.incrementAndGet() }; formal.promote()
        assertEquals(1 to 0, sessions.counts())
        val retiredStopped = CompletableFuture<Unit>()
        val retired = assertNotNull(sessions.reserve())
        retired.attach(retiredStopped) { }; retired.close()
        assertFalse(sessions.canReserve()); assertNull(sessions.reserve())
        assertEquals(1 to 1, sessions.counts()); assertEquals(0, formalCloses.get())
        retiredStopped.complete(Unit)
        val next = assertNotNull(sessions.reserve()); next.close()
        sessions.close(); assertEquals(1, formalCloses.get())
        val allStopped = sessions.stoppedAsync(); assertFalse(allStopped.isDone)
        formalStopped.complete(Unit); allStopped.get(1, TimeUnit.SECONDS)
        assertEquals(0 to 0, sessions.counts()); assertNull(sessions.reserve())
    }

    @Test fun `room close covers an in progress creation that attaches after the stop signal`() {
        val sessions = TimelineRoomSessions()
        val creating = assertNotNull(sessions.reserve())
        sessions.close()
        val roomStopped = sessions.stoppedAsync(); assertFalse(roomStopped.isDone)
        val stopped = CompletableFuture<Unit>(); val closes = AtomicInteger()
        creating.attach(stopped) { closes.incrementAndGet() }
        assertEquals(1, closes.get()); assertFalse(roomStopped.isDone)
        sessions.close(); creating.close(); assertEquals(1, closes.get())
        stopped.complete(Unit); roomStopped.get(1, TimeUnit.SECONDS)
        assertEquals(0 to 0, sessions.counts())
    }

    @Test fun `abandoned creation releases its slot and old completion cannot remove its replacement`() {
        val sessions = TimelineRoomSessions(formalLimit = 1, extraLimit = 1)
        val abandoned = assertNotNull(sessions.reserve())
        val waiting = sessions.stoppedAsync(); abandoned.close(); waiting.get(1, TimeUnit.SECONDS)
        val firstStopped = CompletableFuture<Unit>()
        val first = assertNotNull(sessions.reserve()); first.attach(firstStopped) { }
        first.close(); firstStopped.complete(Unit)
        val replacementStopped = CompletableFuture<Unit>()
        val replacement = assertNotNull(sessions.reserve()); replacement.attach(replacementStopped) { }
        first.close(); firstStopped.complete(Unit)
        assertEquals(0 to 1, sessions.counts())
        sessions.close(); assertFalse(sessions.stoppedAsync().isDone)
        replacementStopped.complete(Unit)
        sessions.stoppedAsync().get(1, TimeUnit.SECONDS)
        assertEquals(0 to 0, sessions.counts())
    }
}
