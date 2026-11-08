package io.github.rwx.net.timeline

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TimelineAdmissionTransportTest {
    private val f = TimelineTestFixture
    private class Job(val reserved: TimelineSeat, val result: CompletableFuture<TimelineSeat>)
    private class Admissions : TimelineAdmissionProvider {
        val jobs = ArrayBlockingQueue<Job>(2)
        val calls = AtomicInteger()
        override fun admit(reservedSeat: TimelineSeat): CompletableFuture<TimelineSeat> {
            calls.incrementAndGet()
            val future = CompletableFuture<TimelineSeat>()
            if (!jobs.offer(Job(reservedSeat, future))) future.completeExceptionally(IOException("Owner admission queue is full"))
            return future
        }
        fun next(): Job = checkNotNull(jobs.poll(3, TimeUnit.SECONDS)) { "Admission was not enqueued" }
    }
    private fun seat(team: Int = 0) = TimelineSeat(team, TimelineBytes(ByteArray(16) { (team + 1).toByte() }))
    private fun server(provider: TimelineAdmissionProvider, seats: Map<String, TimelineSeat> = mapOf(TOKEN to seat())) =
        TimelineServer(InetAddress.getByName("127.0.0.1"), 0, seats, f.map(), TimelineBytes(ByteArray(16)),
            TimelineFeedFactory { request -> object : TimelineFeed {
                override fun next(cursor: TimelineSendCursor): TimelinePublication? {
                    if (!cursor.reset) return null
                    val from = request.context.historyFrom; val ops = f.root(from = from)
                    val header = f.header(ops, observed = maxOf(20, from), sealed = from, from = from,
                        highWater = 0, control = (cursor.previous?.header?.controlEpoch ?: 0) + 1,
                        epoch = cursor.epoch).copy(streamId = request.streamId)
                    return TimelinePublication(header, TimelineBinary.encodeOperations(ops))
                }
            } }, serverTime = { 20 }, historyUntil = { 20 }, admissionProvider = provider)
    private fun await(test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!test()) { check(System.nanoTime() < deadline) { "Admission operation timed out" }; Thread.sleep(2) }
    }

    @Test fun `owner resolves the first grant before ServerHello and reconnect preserves access range with admission two`() {
        val owner = Admissions(); val workers = Executors.newSingleThreadExecutor()
        try {
            server(owner).use { server ->
                val opening = workers.submit<TimelineClient> { TimelineClient("127.0.0.1", server.localPort, "First", TOKEN) }
                val job = owner.next()
                assertFalse(opening.isDone); assertTrue(server.connectedTeamIds().isEmpty())
                assertEquals(seat(), job.reserved)
                // This represents the owner finishing team/fog/Session setup, not a network callback.
                assertTrue(job.result.complete(job.reserved.copy(joinedAt = 5, historyFrom = 5)))
                opening.get(3, TimeUnit.SECONDS).use { client ->
                    await { server.authenticationStatistics().firstHellosWritten == 1L }
                    assertEquals(1L, server.authenticationStatistics().installedPeers)
                    assertEquals(0L, server.authenticationStatistics().failedBeforeFirstHello)
                    assertEquals(1L, client.serverHello.number(14)); assertEquals(5L, client.serverHello.number(13))
                    assertEquals(5L, client.serverHello.number(8)); assertEquals(seat().accessId, client.serverHello.bytes(12))
                    client.openTimeline(1); await { client.latest(1) != null }
                    assertEquals(5L, client.latest(1)!!.header.retainedFrom)
                }
                await { server.connectedTeamIds().isEmpty() }
                TimelineClient("127.0.0.1", server.localPort, "Resume", TOKEN).use { client ->
                    await { server.authenticationStatistics().firstHellosWritten == 2L }
                    assertEquals(2L, client.serverHello.number(14)); assertEquals(5L, client.serverHello.number(13))
                    assertEquals(5L, client.serverHello.number(8)); assertEquals(seat().accessId, client.serverHello.bytes(12))
                    assertEquals(1, owner.calls.get(), "The existing grant never re-enters owner admission")
                }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun `invalid token and concurrent first connection never enqueue another owner request`() {
        val owner = Admissions(); val workers = Executors.newSingleThreadExecutor()
        try {
            server(owner).use { server ->
                assertFailsWith<IOException> { TimelineClient("127.0.0.1", server.localPort, "Invalid", "z".repeat(32)) }
                assertEquals(0, owner.calls.get())
                val first = workers.submit<TimelineClient> { TimelineClient("127.0.0.1", server.localPort, "First", TOKEN) }
                val job = owner.next()
                assertFailsWith<IOException> { TimelineClient("127.0.0.1", server.localPort, "Duplicate", TOKEN) }
                assertEquals(1, owner.calls.get()); assertFalse(job.result.isCancelled)
                assertTrue(job.result.complete(job.reserved.copy(joinedAt = 5, historyFrom = 5)))
                first.get(3, TimeUnit.SECONDS).use { assertTrue(it.isConnected) }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun `revoked pending token cancels only its own future and cannot disturb an admitted peer`() {
        val owner = Admissions(); val workers = Executors.newFixedThreadPool(2)
        val secondToken = "b".repeat(32)
        try {
            server(owner, mapOf(TOKEN to seat(), secondToken to seat(1))).use { server ->
                val first = workers.submit<TimelineClient> { TimelineClient("127.0.0.1", server.localPort, "Healthy", TOKEN) }
                val healthyJob = owner.next(); healthyJob.result.complete(healthyJob.reserved.copy(joinedAt = 5, historyFrom = 5))
                first.get(3, TimeUnit.SECONDS).use { healthy ->
                    healthy.openTimeline(1); await { healthy.latest(1) != null }
                    val other = workers.submit<TimelineClient> { TimelineClient("127.0.0.1", server.localPort, "Pending", secondToken) }
                    val revoked = owner.next(); server.revokeSeat(secondToken)
                    await { revoked.result.isCancelled }
                    assertFalse(revoked.result.complete(revoked.reserved.copy(joinedAt = 6, historyFrom = 6)))
                    assertIs<IOException>(assertFailsWith<ExecutionException> { other.get(3, TimeUnit.SECONDS) }.cause)
                    assertEquals(setOf(0), server.connectedTeamIds()); assertTrue(healthy.isConnected)
                    assertNotNull(healthy.latest(1))
                }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun `pending owner wait shares the original accept deadline and Ping cannot renew it`() {
        val owner = Admissions()
        server(owner).use { server ->
            server.timeoutMillis = 350
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2000
                val started = System.nanoTime()
                // Consume part of the same authentication deadline before the owner request exists.
                CountDownLatch(1).await(100, TimeUnit.MILLISECONDS)
                fun send(frame: TimelineFrame) { TimelineBinary.writeFrame(socket.getOutputStream(), frame); socket.getOutputStream().flush() }
                send(timelineFrame(1, tn(1, 1, true), TimelineMessages.textField(2, "Waiting"), TimelineMessages.textField(3, TOKEN)))
                val job = owner.next(); val pumping = AtomicBoolean(true)
                val pump = Thread {
                    var nonce = 1L
                    while (pumping.get()) try {
                        send(timelineFrame(7, tn(1, nonce++), tn(2, 0))); Thread.sleep(25)
                    } catch (_: IOException) { break }
                }.apply { isDaemon = true; start() }
                try {
                    val error = assertFailsWith<IOException> { TimelineBinary.readFrame(socket.getInputStream()) }
                    assertFalse(error is SocketTimeoutException)
                    await { job.result.isCancelled }
                    await { server.authenticationStatistics().failedBeforeFirstHello == 1L }
                    val failure = server.authenticationStatistics().failures.single()
                    assertEquals(TimelineAuthenticationPhase.OWNER_ADMISSION, failure.phase)
                    assertEquals(TimelineAuthenticationFailureReason.AUTH_DEADLINE, failure.reason)
                    assertEquals(0, failure.matchedTeamId)
                    assertEquals(false, failure.ownerFutureDone, "Capture must precede finally cancelling its own future")
                    assertEquals(false, failure.ownerFutureCancelled)
                    assertTrue(failure.elapsedMillis >= 250)
                    assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(250))
                    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
                    assertTrue(server.connectedTeamIds().isEmpty())
                } finally { pumping.set(false); socket.close(); pump.join(2000) }
            }
        }
    }

    @Test fun `server close cancels pending admission and mismatched reserved identity is rejected`() {
        val owner = Admissions(); val workers = Executors.newSingleThreadExecutor()
        try {
            server(owner).use { server ->
                val invalid = workers.submit<TimelineClient> { TimelineClient("127.0.0.1", server.localPort, "Invalid seat", TOKEN) }
                val job = owner.next(); job.result.complete(job.reserved.copy(accessId = TimelineBytes(ByteArray(16) { 9 })))
                assertIs<IOException>(assertFailsWith<ExecutionException> { invalid.get(3, TimeUnit.SECONDS) }.cause)
                assertTrue(server.connectedTeamIds().isEmpty())
                val pending = workers.submit<TimelineClient> { TimelineClient("127.0.0.1", server.localPort, "Pending", TOKEN) }
                val waiting = owner.next(); server.close()
                await { waiting.result.isCancelled }
                assertIs<IOException>(assertFailsWith<ExecutionException> { pending.get(3, TimeUnit.SECONDS) }.cause)
                assertTrue(server.connectedTeamIds().isEmpty())
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun `failed authentication metadata is bounded immutable and never stores credentials or player names`() {
        server(Admissions()).use { server ->
            val invalidToken = "z".repeat(32); val privateName = "private-name-not-a-diagnostic"
            repeat(70) { index ->
                Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 2000
                    TimelineBinary.writeFrame(socket.getOutputStream(), timelineFrame(1, tn(1, 1, true),
                        TimelineMessages.textField(2, privateName), TimelineMessages.textField(3, invalidToken)))
                    socket.getOutputStream().flush()
                    assertEquals(102, TimelineBinary.readFrame(socket.getInputStream()).type)
                }
                await { server.authenticationStatistics().failedBeforeFirstHello == index + 1L }
            }
            val stats = server.authenticationStatistics()
            assertEquals(70L, stats.acceptedAttempts); assertEquals(0L, stats.installedPeers); assertEquals(0L, stats.firstHellosWritten)
            assertEquals(64, stats.retainedFailureCapacity); assertEquals(64, stats.failures.size); assertEquals(6L, stats.droppedFailures)
            assertEquals((7L..70L).toList(), stats.failures.map { it.attemptId })
            assertTrue(stats.failures.all { it.phase == TimelineAuthenticationPhase.TOKEN && it.matchedTeamId == null &&
                it.reason == TimelineAuthenticationFailureReason.REJECTED && it.ownerFutureDone == null && it.exceptionClass == null })
            assertFalse(stats.toString().contains(invalidToken)); assertFalse(stats.toString().contains(privateName))
            assertFailsWith<UnsupportedOperationException> { (stats.failures as MutableList<TimelineAuthenticationFailure>).clear() }
        }
    }

    @Test fun `installed peer whose first Hello cannot be written records one failure without owner payloads`() {
        TimelineServer(InetAddress.getByName("127.0.0.1"), 0, mapOf(TOKEN to seat()), f.map(),
            TimelineBytes(ByteArray(16)), TimelineFeedFactory { null }, serverTime = { -1 }).use { server ->
            assertFailsWith<IOException> { TimelineClient("127.0.0.1", server.localPort, "No payload logging", TOKEN) }
            await { server.authenticationStatistics().failedBeforeFirstHello == 1L }
            val stats = server.authenticationStatistics(); val failure = stats.failures.single()
            assertEquals(1L, stats.acceptedAttempts); assertEquals(1L, stats.installedPeers); assertEquals(0L, stats.firstHellosWritten)
            assertEquals(TimelineAuthenticationPhase.FIRST_SERVER_HELLO, failure.phase)
            assertEquals(0, failure.matchedTeamId); assertEquals(ProtocolException::class.java.name, failure.exceptionClass)
            assertTrue(failure.reason in setOf(TimelineAuthenticationFailureReason.RUNTIME_EXCEPTION,
                TimelineAuthenticationFailureReason.IO_EXCEPTION, TimelineAuthenticationFailureReason.CLOSED_BEFORE_HELLO))
            assertNull(failure.ownerFutureDone); assertNull(failure.ownerFutureCancelled)
        }
    }

    private companion object { val TOKEN = "a".repeat(32) }
}
