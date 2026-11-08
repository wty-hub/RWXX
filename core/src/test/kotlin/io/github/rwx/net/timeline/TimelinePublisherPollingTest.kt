package io.github.rwx.net.timeline

import kotlin.test.*

class TimelinePublisherPollingTest {
    private val f = TimelineTestFixture
    private val still = TimelineObservedUnit(1, TimelineValue.Vec3(256, 512, -1),
        65520, 100, 100, 0, 0, "tank", 1, true)

    private fun observe(tick: Long, units: List<TimelineObservedUnit> = listOf(still),
                        action: TimelineObservedAction? = null) = TimelineObservation(
        tick * 1024, 1000, (f.running() as TimelineValue.Blob).value, f.bytes(0, 0, 0, 0), units,
        action?.let { 1L }, action?.let { listOf(it) } ?: emptyList())

    @Test fun `one negative scan serves five hundred idle polls across exactly 1024 motions`() {
        val ability = TimelineRecord(listOf(
            TimelineField(1, true, TimelineTlvValue.U32(2)), TimelineField(2, true, TimelineTlvValue.U32(0)),
            TimelineField(3, true, TimelineTlvValue.Bool(false)), TimelineField(4, true, TimelineTlvValue.Text("action.tank")),
            TimelineField(5, true, TimelineTlvValue.Text("tank")), TimelineField(6, true, TimelineTlvValue.I64(100)),
            TimelineField(7, true, TimelineTlvValue.U32(1))))
        val action = TimelineObservedAction(91, ability, 1, 0, -1, 0, false)
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observe(0, (1L..341L).map { still.copy(key = it) }, action))
        // Three motion attributes for each UNIT and one ACTION progress curve.
        assertEquals(1024L, publisher.retainedRawSampleCount)
        val first = publisher.publish(0)!!
        val initialScans = publisher.confirmationScans
        assertNull(publisher.publish(99_999_999))
        assertEquals(initialScans, publisher.confirmationScans)
        assertNull(publisher.publish(100_000_000))
        assertEquals(initialScans + 1, publisher.confirmationScans)
        repeat(500) { index -> assertNull(publisher.publish(101_000_000 + index * 1_000_000L)) }
        assertEquals(initialScans + 1, publisher.confirmationScans)
        assertSame(first, publisher.latest)
    }

    @Test fun `equal same-time new-time and dirty observations each revoke the idle conclusion`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observe(0))
        val first = publisher.publish(0)!!
        val digest = TimelineCanonical.digest(first)
        publisher.publish(100_000_000)
        var scans = publisher.confirmationScans
        publisher.observe(observe(0))
        assertNull(publisher.publish(101_000_000))
        assertEquals(++scans, publisher.confirmationScans)
        publisher.observe(observe(1))
        assertNull(publisher.publish(102_000_000))
        assertEquals(++scans, publisher.confirmationScans)

        val moved = still.copy(position = TimelineValue.Vec3(4096, 8192, -1000))
        publisher.observe(observe(1, listOf(moved)))
        val changed = publisher.publish(200_000_000)!!
        assertEquals(++scans, publisher.confirmationScans)
        assertEquals(moved.position, TimelineProfile.sample(changed, 1, 1, 1024))
        assertEquals(digest, TimelineCanonical.digest(first))
        // A successful publication cannot set the negative-cache flag on its own.
        assertNull(publisher.publish(300_000_000))
        assertEquals(++scans, publisher.confirmationScans)
        assertNull(publisher.publish(400_000_000))
        assertEquals(scans, publisher.confirmationScans)
    }

    @Test fun `heartbeat boundary rescans and failed observations cannot hide behind an idle conclusion`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observe(0))
        publisher.publish(0)
        publisher.publish(100_000_000)
        var scans = publisher.confirmationScans
        assertFailsWith<ProtocolException> { publisher.publish(-1) }
        assertEquals(scans, publisher.confirmationScans)
        assertFailsWith<ProtocolException> { publisher.observe(observe(1, listOf(still.copy(headingQ = 65536)))) }
        assertNull(publisher.publish(101_000_000))
        assertEquals(++scans, publisher.confirmationScans)
        assertNull(publisher.publish(999_999_999))
        assertEquals(scans, publisher.confirmationScans)

        // A complete negative scan still runs each second, but committed frozen
        // observations leave receipt heartbeats to the independent peer encoder.
        assertNull(publisher.publish(1_000_000_000))
        assertEquals(++scans, publisher.confirmationScans)
        assertFailsWith<ProtocolException> { publisher.publish(999_999_999) }
        assertNull(publisher.publish(1_099_999_999))
        assertEquals(scans, publisher.confirmationScans)
        assertNull(publisher.publish(1_100_000_000))
        assertEquals(scans, publisher.confirmationScans)
        assertNull(publisher.publish(1_200_000_000))
        assertEquals(scans, publisher.confirmationScans)
        assertNull(publisher.publish(2_000_000_000))
        assertEquals(++scans, publisher.confirmationScans)
    }

    @Test fun `new observations confirm due predictions even when sampled positions still match`() {
        val publisher = TimelinePublisher(f.context(), liveWindowSeconds = 1)
        publisher.observe(observe(0))
        publisher.publish(0)
        publisher.observe(observe(1, listOf(still.copy(position = TimelineValue.Vec3(384, 512, -1)))))
        val predicted = publisher.publish(100_000_000)!!
        assertTrue(predicted.objects.getValue(1).curves.getValue(1).segments.any { it.predicted })
        assertNull(publisher.publish(200_000_000))
        val scans = publisher.confirmationScans

        val expected = TimelineValue.Vec3(7936, 512, -1)
        assertEquals(expected, TimelineProfile.sample(predicted, 1, 1, 60 * 1024L))
        publisher.observe(observe(60, listOf(still.copy(position = expected))))
        val confirmed = publisher.publish(300_000_000)!!
        assertEquals(scans + 1, publisher.confirmationScans)
        assertTrue(confirmed.header.sealedThrough > predicted.header.sealedThrough)
        assertEquals(expected, TimelineProfile.sample(confirmed, 1, 1, 60 * 1024L))
        assertTrue(confirmed.objects.getValue(1).curves.getValue(1).segments.none {
            it.predicted && it.lo < confirmed.header.sealedThrough
        })
    }

    @Test fun `retention and visibility close still replace the library after idle polls`() {
        val publisher = TimelinePublisher(f.context(), liveWindowSeconds = 1)
        publisher.observe(observe(0))
        val first = publisher.publish(0)!!
        val firstDigest = TimelineCanonical.digest(first)
        publisher.publish(100_000_000)
        publisher.observe(observe(120))
        val retained = publisher.publish(2_000_000_000)!!
        assertEquals(60 * 1024L, retained.header.retainedFrom)
        assertNull(publisher.publish(2_100_000_000))
        val scans = publisher.confirmationScans
        publisher.observe(observe(121, emptyList()))
        val closed = publisher.publish(2_200_000_000)!!
        assertEquals(scans + 1, publisher.confirmationScans)
        assertEquals(121 * 1024L, closed.objects.getValue(1).close!!.until)
        assertNull(TimelineProfile.sample(closed, 1, 1, 121 * 1024L))
        assertNull(publisher.unitKey(1))
        assertEquals(firstDigest, TimelineCanonical.digest(first))
    }
}
