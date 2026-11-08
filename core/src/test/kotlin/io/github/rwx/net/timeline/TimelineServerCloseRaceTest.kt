package io.github.rwx.net.timeline

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TimelineServerCloseRaceTest {
    private val f = TimelineTestFixture
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition()) { check(System.nanoTime() < deadline) { "Close race fixture timed out" }; Thread.sleep(2) }
    }

    @Test fun `concurrent client removal and server shutdown repeatedly close real peers and their streams`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(32) { turn ->
                val tokens = listOf("r".repeat(32), "s".repeat(32))
                val seats = tokens.take(1 + turn % 2).mapIndexed { team, token -> token to
                    TimelineSeat(team, TimelineBytes(ByteArray(16) { team.toByte() })) }.toMap()
                val feeds = TimelineFeedFactory { request -> object : TimelineFeed {
                    override fun next(cursor: TimelineSendCursor): TimelinePublication? {
                        if (!cursor.reset) return null
                        val operations = f.root() + f.unit(team = request.teamId.toLong())
                        val header = f.header(operations, epoch = cursor.epoch).copy(streamId = request.streamId)
                        return TimelinePublication(header, TimelineBinary.encodeOperations(operations))
                    }
                } }
                TimelineServer(InetAddress.getLoopbackAddress(), 0, seats, f.map(), TimelineBytes(ByteArray(16)),
                    feeds, serverTime = { 10 }, historyUntil = { 10 }).use { server ->
                    val clients = ArrayList<TimelineClient>()
                    try {
                        seats.keys.forEach { token ->
                            clients.add(TimelineClient("127.0.0.1", server.localPort, "Close race", token).also { it.openTimeline(1) })
                        }
                        await { clients.all { it.latest(1) != null } }
                        val start = CountDownLatch(1)
                        val closingServer = workers.submit { check(start.await(4, TimeUnit.SECONDS)); server.close() }
                        val closingClients = workers.submit { check(start.await(4, TimeUnit.SECONDS)); clients.forEach { it.close() } }
                        start.countDown()
                        closingServer.get(4, TimeUnit.SECONDS); closingClients.get(4, TimeUnit.SECONDS)
                        await { server.connectedTeamIds().isEmpty() }
                        assertTrue(clients.all { !it.isConnected })
                        server.close() // Idempotence after the concurrent close remains intact.
                    } finally { clients.forEach { it.close() } }
                }
            }
        } finally { workers.shutdownNow() }
    }
}
