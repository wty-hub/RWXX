package io.github.rwx.net.authoritative

import com.corrodinggames.rts.gameFramework.ReplayFogState
import io.github.rwx.net.timeline.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.*

/** The unchanged full-scan implementation is the oracle, not a second copy of the sparse algorithm. */
class OwnedSeatVisionTest {
    private class PairOfVisions {
        val owned = OwnedSeatVision()
        val oracle = ReplayFogState()
        var width = 0
        var height = 0
        fun configure(w: Int, h: Int, revealed: Boolean) {
            width = w; height = h
            owned.configure(w, h, revealed)
            oracle.configure(w, h, revealed)
            oracle.ensureTeam(0)
        }
        fun begin() { owned.beginFrame(); oracle.beginFrame(true) }
        fun reveal(x: Float, y: Float, radius: Int) { owned.reveal(x, y, radius); oracle.reveal(0, x, y, radius) }
        fun reset() { owned.reset(); oracle.reset() }
        fun expected() = ByteArray(width * height) { index -> oracle.get(0)[index % width][index / width] }
        fun compare(label: String = "") {
            assertContentEquals(expected(), owned.grid().copyBytes(), label)
            for (y in 0 until height) for (x in 0 until width)
                assertEquals(oracle.get(0)[x][y], owned.grid().valueAt(x, y), "$label ($x,$y)")
        }
    }

    @Test fun `random movement radius overlap and repeated calls match full refog byte for byte`() {
        val pair = PairOfVisions()
        val random = Random(0x51_67_48)
        pair.configure(47, 31, false)
        var previousCopy = pair.owned.grid().copyBytes()
        var previousExpected = previousCopy.copyOf()
        repeat(160) { frame ->
            // Same configuration must preserve exploration; no tick or change counter is supplied.
            pair.configure(47, 31, false)
            pair.begin()
            repeat(random.nextInt(1, 13)) {
                val x = random.nextInt(-8, 55) + random.nextFloat()
                val y = random.nextInt(-8, 39) + random.nextFloat()
                val radius = random.nextInt(0, 30)
                pair.reveal(x, y, radius)
                if (it % 3 == 0) pair.reveal(x, y, radius)
            }
            pair.compare("frame $frame")
            assertContentEquals(previousExpected, previousCopy, "Previously returned samples cannot be refogged")
            previousCopy = pair.owned.grid().copyBytes()
            previousExpected = previousCopy.copyOf()
            // External writes cannot affect either current sight or future exploration history.
            pair.owned.grid().copyBytes().fill(if (frame % 2 == 0) 0 else 10)
            if (frame % 7 == 0) {
                pair.begin()
                pair.compare("repeat begin $frame")
                pair.begin()
                pair.compare("same-call-boundary repeat $frame")
            }
        }
    }

    @Test fun `overlap cannot duplicate tracked cells and far exploration keeps every five to ten value`() {
        val pair = PairOfVisions()
        pair.configure(64, 48, false)
        pair.reveal(16.5f, 20.5f, 13)
        val before = pair.owned.grid().copyBytes()
        assertTrue(before.any { it.toInt() in 5..9 })
        assertTrue(before.any { it == 10.toByte() })
        pair.begin()
        pair.compare()
        val after = pair.owned.grid().copyBytes()
        before.indices.forEach { index ->
            assertEquals(if (before[index] < 5) 5.toByte() else before[index], after[index])
        }
        // A small map reaches its entire cell capacity. Repeated overlap cannot append duplicates.
        pair.configure(17, 11, false)
        repeat(1000) { pair.reveal(8f, 5f, 40) }
        pair.compare()
        assertTrue(pair.owned.grid().copyBytes().all { it == 0.toByte() })
        repeat(3) { pair.begin(); pair.compare() }
        assertTrue(pair.owned.grid().copyBytes().all { it == 5.toByte() })
        // Exercise primitive-list growth beyond its initial capacity without exposing its storage.
        pair.configure(512, 512, false)
        pair.reveal(256f, 256f, 1024)
        pair.compare("large full visibility")
        pair.begin()
        assertContentEquals(pair.expected(), pair.owned.grid().copyBytes())
        assertTrue(pair.owned.grid().copyBytes().all { it == 5.toByte() })
    }

    @Test fun `dense zero overlap and fresh refog still match native circles including extreme inputs`() {
        val pair = PairOfVisions()
        pair.configure(48, 32, false)
        val radii = intArrayOf(1, 2, 3, 9, 23, Int.MAX_VALUE)
        val points = listOf(0f to 0f, 24.25f to 16.5f, 47.75f to 31.75f,
            Float.NaN to 16f, Float.POSITIVE_INFINITY to Float.NEGATIVE_INFINITY)
        repeat(12) { frame ->
            pair.begin()
            pair.compare("fresh refog $frame")
            // Start with overlapping circles and a mixture of zero, falloff and unexplored cells.
            repeat(60) { circle ->
                pair.reveal(16f + (circle % 9) * 0.5f, 12f + (circle % 7) * 0.5f, 9)
            }
            pair.compare("mixed dense overlap $frame")
            pair.reveal(24f, 16f, 100)
            assertTrue(pair.owned.grid().copyBytes().all { it == 0.toByte() })
            val detached = pair.owned.grid().copyBytes()
            for (radius in radii) for ((x, y) in points) pair.reveal(x, y, radius)
            pair.compare("zero overlap $frame")
            pair.begin()
            pair.compare("refog after zero overlap $frame")
            assertTrue(pair.owned.grid().copyBytes().all { it == 5.toByte() })
            assertTrue(detached.all { it == 0.toByte() }, "Refog cannot mutate a returned snapshot")
            // The same actual cells must be eligible again immediately, without a tick change.
            pair.reveal(24f, 16f, 2)
            pair.compare("same-boundary reveal $frame")
            assertEquals(0.toByte(), pair.owned.grid().valueAt(24, 16))
        }
    }

    @Test fun `configuration replacement reset and borrowed bindings preserve old grid identity`() {
        val pair = PairOfVisions()
        pair.configure(13, 9, false)
        pair.reveal(1.25f, 2.5f, 2)
        val oldGrid = pair.owned.grid()
        val oldBytes = oldGrid.copyBytes()
        pair.configure(13, 9, false)
        assertSame(oldGrid, pair.owned.grid())
        pair.configure(13, 9, true)
        assertNotSame(oldGrid, pair.owned.grid())
        assertContentEquals(oldBytes, oldGrid.copyBytes(), "A nested configure cannot redirect a borrowed grid")
        pair.compare()
        assertTrue(pair.owned.grid().copyBytes().all { it == 5.toByte() })
        pair.reveal(12f, 8f, 3)
        pair.compare()
        val revealedGrid = pair.owned.grid()
        val beforeReset = revealedGrid.copyBytes()
        pair.reset()
        assertFailsWith<IllegalStateException> { pair.owned.grid() }
        pair.owned.beginFrame(); pair.owned.reveal(1f, 1f, 5)
        assertContentEquals(beforeReset, revealedGrid.copyBytes(), "Defeat/reset cannot mutate an already borrowed grid")
        pair.configure(9, 13, false)
        pair.compare()
        assertTrue(pair.owned.grid().copyBytes().all { it == 10.toByte() })
        pair.reveal(8.75f, 12.5f, 1)
        pair.compare()
        pair.configure(9, 13, true); pair.compare()
        pair.configure(9, 13, false); pair.compare()
        assertContentEquals(oldBytes, oldGrid.copyBytes())
    }

    @Test fun `edge float and radius behavior is identical and coordinates never wrap rows`() {
        val pair = PairOfVisions()
        pair.configure(11, 7, false)
        val points = listOf(0f to 0f, -1.25f to 3.5f, 10.999f to 6.999f, 30f to -30f,
            Float.NaN to 2f, Float.POSITIVE_INFINITY to Float.NEGATIVE_INFINITY)
        for (radius in listOf(-1, 0, 1, 2, 3, 5, 16, Int.MAX_VALUE)) for ((x, y) in points) {
            pair.begin(); pair.reveal(x, y, radius); pair.compare("radius=$radius point=($x,$y)")
        }
        assertFailsWith<IndexOutOfBoundsException> { pair.owned.grid().valueAt(11, 0) }
        assertFailsWith<IndexOutOfBoundsException> { pair.owned.grid().valueAt(-1, 1) }
        assertFailsWith<IndexOutOfBoundsException> { pair.owned.grid().valueAt(0, 7) }
        assertFailsWith<IllegalArgumentException> { pair.owned.configure(0, 1, false) }
        assertFailsWith<IllegalArgumentException> { pair.owned.configure(1025, 1024, false) }
    }

    private class WirePeer(val context: TimelineContext) {
        private val publisher = TimelinePublisher(context)
        private val store = TimelineStore(context)
        private val receiver = TimelineReceiver(store)
        private var now = 0L
        private val encoder = TimelineEncoder(clock = { now })
        fun publish(observation: TimelineObservation, at: Long): Pair<ByteArray, TimelineState> {
            now = at
            publisher.observe(observation)
            val source = assertNotNull(publisher.publish(at))
            val previous = store.current
            val publication = assertNotNull(encoder.encode(TimelineSendCursor(context, 1,
                previous?.header?.epoch ?: 1, previous, previous == null), source))
            val h = publication.header
            assertTrue(publication.operations.size <= TimelineProtocol.MAX_CHUNK_BYTES)
            val frames = listOf(h.beginFrame(),
                timelineFrame(107, tn(1, h.streamId, true), tn(2, h.epoch), tn(3, h.revision),
                    tn(4, 0), tb(5, publication.operations)),
                timelineFrame(108, tn(1, h.streamId, true), tn(2, h.epoch), tn(3, h.revision)))
            val bytes = ByteArrayOutputStream().also { out -> frames.forEach {
                TimelineMessages.validate(it, fromClient = false)
                TimelineBinary.writeFrame(out, it)
            } }.toByteArray()
            val input = ByteArrayInputStream(bytes)
            repeat(frames.size) {
                val frame = TimelineBinary.readFrame(input)
                TimelineMessages.validate(frame, fromClient = false)
                when (frame.type) {
                    106 -> receiver.begin(TimelineMessages.header(frame.body))
                    107 -> receiver.chunk(frame)
                    108 -> assertNotNull(receiver.commit(frame))
                }
            }
            assertEquals(0, input.available())
            return bytes to assertNotNull(store.current)
        }
    }

    @Test fun `oracle and sparse fog produce identical live v7 frames full retained hashes and terminal fence`() {
        val f = TimelineTestFixture
        val width = 19; val height = 13
        val map = TimelineRecord(f.map().fields.map { field -> field.copy(value = when (field.id) {
            2 -> TimelineTlvValue.U32(width.toLong())
            3 -> TimelineTlvValue.U32(height.toLong())
            6 -> TimelineTlvValue.Bytes(TimelineBytes(ByteArray(width * height)))
            8, 9 -> TimelineTlvValue.Bytes(TimelineBytes(ByteArray(width * height * 2)))
            else -> field.value
        }) })
        val context = f.context(map)
        val original = WirePeer(context)
        val sparse = WirePeer(context)
        val pair = PairOfVisions()
        val random = Random(81173)
        val unit = TimelineObservedUnit(77, TimelineValue.Vec3(256, 512, -1), 65000,
            25600, 25600, 65535, 0, "tank", 1, true)
        var firstState: TimelineState? = null
        var firstDigest: TimelineBytes? = null
        fun compare(time: Long, now: Long, expected: ByteArray, actual: ByteArray, terminalAt: Long? = null) {
            val status = if (terminalAt == null) f.running().value else f.finished(terminalAt).value
            val units = if (terminalAt == null) listOf(unit) else emptyList()
            val credits = 1000 + (terminalAt ?: time)
            val a = original.publish(TimelineObservation(time, credits, status, TimelineBytes(expected), units), now)
            val b = sparse.publish(TimelineObservation(time, credits, status, TimelineBytes(actual), units), now)
            assertContentEquals(a.first, b.first, "Actual Begin/Chunk/Commit bytes changed at $time")
            val canonicalA = ByteArrayOutputStream().also { TimelineCanonical.write(a.second, it) }.toByteArray()
            val canonicalB = ByteArrayOutputStream().also { TimelineCanonical.write(b.second, it) }.toByteArray()
            assertContentEquals(canonicalA, canonicalB)
            assertEquals(TimelineCanonical.digest(a.second), TimelineCanonical.digest(b.second))
            if (firstState == null) { firstState = b.second; firstDigest = TimelineCanonical.digest(b.second) }
            assertEquals(firstDigest, TimelineCanonical.digest(firstState!!), "Later reveals cannot mutate committed history")
        }
        repeat(25) { frame ->
            pair.configure(width, height, frame / 5 % 2 == 1)
            pair.begin()
            repeat(3) { pair.reveal(random.nextFloat() * width, random.nextFloat() * height, random.nextInt(1, 8)) }
            pair.compare()
            compare(frame * 6144L, frame * 100_000_000L, pair.expected(), pair.owned.grid().copyBytes())
        }
        val authorizedBeforeDefeat = pair.owned.grid().copyBytes()
        val expectedBeforeDefeat = pair.expected()
        pair.reset()
        val finish = 25 * 6144L
        compare(finish, 2_500_000_000L, expectedBeforeDefeat, authorizedBeforeDefeat, finish)
        compare(finish + 1, 3_600_000_000L, expectedBeforeDefeat, authorizedBeforeDefeat, finish)
    }
}
