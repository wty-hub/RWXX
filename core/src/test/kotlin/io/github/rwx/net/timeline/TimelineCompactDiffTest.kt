package io.github.rwx.net.timeline

import kotlin.test.*

class TimelineCompactDiffTest {
    private val f = TimelineTestFixture
    private val unit = TimelineObservedUnit(1, TimelineValue.Vec3(256, 512, -1),
        65000, 80, 100, 65535, 0, "tank", 1, true)

    @Test fun `equal independent one record and empty bodies produce only the genuinely changed splice`() {
        val source = TimelineStore(f.context())
        val initial = f.commit(source, f.root() + f.unit() + listOf(
            TimelineOperation.Create(2, TimelineObjectKind.VISUAL, 0),
            TimelineOperation.Declare(2, 65536, TimelineMode.STEP, TimelineValueType.UINT, false),
            TimelineOperation.Declare(2, 65537, TimelineMode.PULSE, TimelineValueType.UINT, false,
                metadata = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(0)))))), highWater = 2)
        val encoder = TimelineEncoder(clock = { 0L })
        val peer = TimelineStore(initial.context)
        val reset = assertNotNull(encoder.encode(TimelineSendCursor(initial.context, 1, 1, null, true), initial))
        val received = peer.apply(reset.header, reset.operations)
        val desired = initial.copy(header = initial.header.copy(observedThrough = 11, retainedUntil = 12),
            objects = initial.objects.mapValues { (handle, obj) ->
                obj.copy(curves = obj.curves.mapValues { (id, curve) ->
                    val segments = if (handle == 1L && id == 3)
                        listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, TimelineValue.SInt(70)))
                    else curve.segments.map { it.clipped(it.lo, it.hi) }
                    curve.copy(curveRevision = curve.curveRevision + 20, segments = segments)
                })
            })
        TimelineProfile.validate(desired)
        assertNotSame(initial.objects.getValue(1).curves.getValue(1).segments.single(),
            desired.objects.getValue(1).curves.getValue(1).segments.single())
        val delta = assertNotNull(encoder.encode(TimelineSendCursor(initial.context, 1, 1, received, false), desired))
        val expected = listOf(f.step(1, 3, TimelineValue.SInt(70), revision = 1))
        val formats = received.objects.values.flatMap { obj -> obj.curves.values.map {
            TimelineCurveKey(obj.handle, it.attributeId) to it.format
        } }.toMap()
        assertEquals(TimelineBinary.encodeOperations(expected, formats), delta.operations)
        val updated = peer.apply(delta.header, delta.operations)
        for (time in 0L..11L) for (id in 1..9)
            assertEquals(TimelineProfile.sample(desired, 1, id, time), TimelineProfile.sample(updated, 1, id, time))
        assertEquals(TimelineValue.SInt(80), TimelineProfile.sample(received, 1, 3, 10))
    }

    @Test fun `publisher commit preserves existing map nodes but replaces values without mutating prior publication`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observation(0))
        val first = assertNotNull(publisher.publish(0))
        val digest = TimelineCanonical.digest(first)
        val curves = mutableCurves(publisher, 1)
        val entries = curves.entries.toList()
        val oldHealth = curves.getValue(3)
        publisher.observe(observation(1, unit.copy(healthQ = 70)))
        val next = assertNotNull(publisher.publish(100_000_000))
        assertEquals(next.objects.getValue(1).curves.keys.toList(), curves.keys.toList())
        entries.zip(curves.entries).forEach { (before, after) -> assertSame(before, after) }
        assertNotSame(oldHealth, curves.getValue(3))
        assertEquals(TimelineValue.SInt(70), TimelineProfile.sample(next, 1, 3, 1024))
        assertEquals(digest, TimelineCanonical.digest(first))
    }

    @Test fun `publisher reset restores committed key order after a late optional progress declaration`() {
        val ability = TimelineRecord(listOf(
            TimelineField(1, true, TimelineTlvValue.U32(2)), TimelineField(2, true, TimelineTlvValue.U32(0)),
            TimelineField(3, true, TimelineTlvValue.Bool(false)), TimelineField(4, true, TimelineTlvValue.Text("action.tank")),
            TimelineField(5, true, TimelineTlvValue.Text("tank")), TimelineField(6, true, TimelineTlvValue.I64(100)),
            TimelineField(7, true, TimelineTlvValue.U32(1))))
        val action = TimelineObservedAction(91, ability, 1, 0, -1, null, false)
        val publisher = TimelinePublisher(f.context(), liveWindowSeconds = 1)
        publisher.observe(observation(0, action = action))
        val first = assertNotNull(publisher.publish(0))
        val handle = first.objects.values.single { it.kind == TimelineObjectKind.ACTION }.handle
        val curves = mutableCurves(publisher, handle)
        assertEquals(listOf(1, 2, 3, 5), curves.keys.toList())
        val originalEntry = curves.entries.first()
        publisher.observe(observation(120, action = action.copy(progressQ = 100)))
        assertEquals(listOf(1, 2, 3, 5, 4), curves.keys.toList())
        val reset = assertNotNull(publisher.publish(2_000_000_000))
        assertEquals(TimelineTransactionKind.RESET, reset.header.kind)
        assertEquals(listOf(1, 2, 3, 4, 5), curves.keys.toList())
        assertEquals(reset.objects.getValue(handle).curves.keys.toList(), curves.keys.toList())
        assertNotSame(originalEntry, curves.entries.first())
        assertEquals(TimelineValue.UInt(100), TimelineProfile.sample(reset, handle, 4, 120 * 1024L))
        assertFalse(first.objects.getValue(handle).curves.containsKey(4))
    }

    private fun observation(tick: Long, value: TimelineObservedUnit = unit, action: TimelineObservedAction? = null) =
        TimelineObservation(tick * 1024, 1000, (f.running() as TimelineValue.Blob).value,
            f.bytes(0, 0, 0, 0), listOf(value), action?.let { 1L }, action?.let { listOf(it) } ?: emptyList())

    /** Read-only inspection distinguishes node reuse from equal rebuilt maps; no mutable test hook. */
    @Suppress("UNCHECKED_CAST")
    private fun mutableCurves(publisher: TimelinePublisher, handle: Long): Map<Int, TimelineCurve> {
        val field = TimelinePublisher::class.java.getDeclaredField("objects").apply { isAccessible = true }
        val obj = (field.get(publisher) as Map<Long, *>).getValue(handle)!!
        return obj.javaClass.getDeclaredField("curves").apply { isAccessible = true }.get(obj) as Map<Int, TimelineCurve>
    }
}
