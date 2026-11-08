package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import kotlin.test.*

class TimelinePublisherTest {
    private val f = TimelineTestFixture
    private fun unit(key: Long = 77, tick: Long = 0, moving: Boolean = false, owner: Int = 0): TimelineObservedUnit =
        TimelineObservedUnit(key, TimelineValue.Vec3(256 + if (moving) tick * 32 else 0, 512, -1),
            if (moving) (65500 + tick * 64) % 65536 else 65500, 25600, 25600,
            if (moving) tick * 100 else 65535, owner, "tank", 1, owner == 0)
    private fun observation(tick: Long, units: List<TimelineObservedUnit> = listOf(unit()), credits: Long = 1000,
                            selected: Long? = null, actions: List<TimelineObservedAction> = emptyList(),
                            events: List<TimelineObservedEvent> = emptyList(), fog: TimelineBytes = f.bytes(0, 0, 0, 0),
                            status: TimelineBytes = (f.running() as TimelineValue.Blob).value,
                            selection: List<Long> = selected?.let { listOf(it) } ?: emptyList()) =
        TimelineObservation(tick * 1024, credits, status, fog, units, selected, actions, events, selection)
    private fun ability(cost: Long = 100) = TimelineRecord(listOf(
        TimelineField(1, true, TimelineTlvValue.U32(2)), TimelineField(2, true, TimelineTlvValue.U32(0)),
        TimelineField(3, true, TimelineTlvValue.Bool(false)), TimelineField(4, true, TimelineTlvValue.Text("action.tank")),
        TimelineField(5, true, TimelineTlvValue.Text("tank")), TimelineField(6, true, TimelineTlvValue.I64(cost)),
        TimelineField(7, true, TimelineTlvValue.U32(1))))
    private fun action(cost: Long = 100, ready: Int = 1, progress: Long? = null) =
        TimelineObservedAction(91, ability(cost), ready, 0, -1, progress, false)
    private fun tickNanos(tick: Long) = tick * 1_000_000_000L / 60

    @Test fun `first observation publishes dense public handles canonical fog and immutable values`() {
        val publisher = TimelinePublisher(f.context())
        val input = mutableListOf(unit(900, owner = 1), unit(300).copy(position = TimelineValue.Vec3(128, 512, -1)))
        val snapshot = observation(0, input)
        input.clear()
        publisher.observe(snapshot)
        val state = assertNotNull(publisher.publish(0))
        assertEquals(setOf(0L, 1L, 2L), state.objects.keys)
        assertEquals(300L, publisher.unitKey(1))
        assertEquals(900L, publisher.unitKey(2))
        assertEquals(TimelineValue.SInt(1000), TimelineProfile.sample(state, 0, 1, 0))
        assertContentEquals(ByteArray(4), TimelineProfile.fogPage(state, 0, 0))
        assertNull(publisher.publish(50_000_000))
        assertSame(state, publisher.latest)
    }

    @Test fun `reused immutable fog and appearance preserve history while changed step fields are recorded`() {
        val publisher = TimelinePublisher(f.context())
        val fog = f.bytes(0, 0, 0, 0)
        val original = unit()
        publisher.observe(observation(0, listOf(original), fog = fog))
        val initial = publisher.publish(0)!!
        val initialDigest = TimelineCanonical.digest(initial)
        // Both an identical instance and equal newly owned bytes use the same public values.
        publisher.observe(observation(1, listOf(original), fog = fog))
        publisher.observe(observation(2, listOf(original.copy(appearanceKey = String("tank".toCharArray()))), fog = f.bytes(0, 0, 0, 0)))
        val changed = original.copy(healthQ = 20000, maxHealthQ = 30000, ownerTeam = 1,
            appearanceKey = "builder", techLevel = 2, controllable = false)
        publisher.observe(observation(3, listOf(changed), fog = f.bytes(5, 0, 10, 0)))
        val state = publisher.publish(200_000_000)!!
        assertEquals(initialDigest, TimelineCanonical.digest(initial))
        assertEquals(TimelineValue.SInt(25600), TimelineProfile.sample(state, 1, 3, 2048))
        assertEquals(TimelineValue.SInt(20000), TimelineProfile.sample(state, 1, 3, 3072))
        assertEquals(TimelineValue.SInt(30000), TimelineProfile.sample(state, 1, 4, 3072))
        assertEquals(TimelineValue.SInt(1), TimelineProfile.sample(state, 1, 6, 3072))
        assertEquals(TimelineValue.Blob(TimelineBytes("builder".toByteArray())), TimelineProfile.sample(state, 1, 7, 3072))
        assertEquals(TimelineValue.UInt(2), TimelineProfile.sample(state, 1, 8, 3072))
        assertEquals(TimelineValue.Bool(false), TimelineProfile.sample(state, 1, 9, 3072))
        assertContentEquals(byteArrayOf(0, 0, 0, 0), TimelineProfile.fogPage(state, 0, 2048))
        assertContentEquals(byteArrayOf(5, 0, 10, 0), TimelineProfile.fogPage(state, 0, 3072))
        assertTrue(state.header.controlEpoch > initial.header.controlEpoch)
    }

    @Test fun `validation caches do not accept malformed replacements or duplicate source identities`() {
        val publisher = TimelinePublisher(f.context())
        val good = unit()
        val fog = f.bytes(0, 0, 0, 0)
        publisher.observe(observation(0, listOf(good), fog = fog))
        val initial = publisher.publish(0)!!
        assertFailsWith<ProtocolException> { publisher.observe(observation(1, listOf(good, good), fog = fog)) }
        assertFailsWith<ProtocolException> { publisher.observe(observation(1, listOf(good.copy(appearanceKey = "tank\u0000")), fog = fog)) }
        assertFailsWith<ProtocolException> { publisher.observe(observation(1, listOf(good), fog = f.bytes(0, 0, 11, 0))) }
        assertSame(initial, publisher.latest)
        publisher.observe(observation(1, listOf(good), credits = 2000, fog = fog))
        assertEquals(TimelineValue.SInt(2000), TimelineProfile.sample(publisher.publish(200_000_000)!!, 0, 1, 1024))
    }

    @Test fun `advancing retention removes closed capabilities and preserves active step and fog values`() {
        val publisher = TimelinePublisher(f.context(), liveWindowSeconds = 1)
        val fog = f.bytes(0, 5, 10, 0)
        publisher.observe(observation(0, selected = 77, actions = listOf(action()), fog = fog))
        val first = publisher.publish(0)!!
        publisher.observe(observation(1, fog = fog))
        publisher.publish(200_000_000)
        publisher.observe(observation(120, credits = 2000, fog = fog))
        val retained = publisher.publish(2_000_000_000)!!
        assertEquals(61440L, retained.header.retainedFrom)
        assertEquals(setOf(0L, 1L), retained.objects.keys)
        assertEquals(TimelineValue.SInt(25600), TimelineProfile.sample(retained, 1, 3, 61440))
        assertContentEquals(byteArrayOf(0, 5, 10, 0), TimelineProfile.fogPage(retained, 0, 61440))
        assertTrue(first.objects.values.any { it.kind == TimelineObjectKind.ACTION })
        assertNull(first.objects.values.single { it.kind == TimelineObjectKind.ACTION }.close)
    }

    @Test fun `stable public motion and build progress do not generate per tick curve updates`() {
        val publisher = TimelinePublisher(f.context())
        val states = ArrayList<TimelineState>()
        for (tick in 0L..126L) {
            publisher.observe(observation(tick, listOf(unit(tick = tick, moving = true))))
            if (tick % 6 == 0L) publisher.publish(tickNanos(tick))?.let { states.add(it) }
        }
        val state = assertNotNull(publisher.latest)
        assertTrue(states.size < 12, "Stable curves should not need all 22 possible publication slots")
        val position = state.objects.getValue(1).curves.getValue(1)
        val progress = state.objects.getValue(1).curves.getValue(5)
        assertTrue(position.curveRevision < 10)
        assertTrue(progress.curveRevision < 10)
        for (tick in 0L..state.header.observedThrough / 1024) {
            val expected = unit(tick = tick, moving = true)
            val actual = TimelineProfile.sample(state, 1, 1, tick * 1024) as TimelineValue.Vec3
            assertTrue(kotlin.math.abs(actual.x - expected.position.x) <= 64)
            val build = TimelineProfile.sample(state, 1, 5, tick * 1024) as TimelineValue.UInt
            assertTrue(kotlin.math.abs(build.value - expected.progressQ) <= 1)
        }
        assertTrue(state.header.observedThrough - state.header.sealedThrough <= 5 * 61440)
    }

    @Test fun `sharp turn corrects unsealed prediction while old sealed samples remain unchanged`() {
        val publisher = TimelinePublisher(f.context())
        for (tick in 0L..12L) {
            publisher.observe(observation(tick, listOf(unit(tick = tick, moving = true))))
            if (tick % 6 == 0L) publisher.publish(tickNanos(tick))
        }
        val before = publisher.latest!!
        val digest = TimelineCanonical.digest(before)
        for (tick in 13L..24L) {
            val turned = unit(tick = tick, moving = true).copy(position = TimelineValue.Vec3(256 + 12 * 32, 512 + (tick - 12) * 64, -1))
            publisher.observe(observation(tick, listOf(turned)))
            if (tick % 6 == 0L) publisher.publish(tickNanos(tick))
        }
        val after = publisher.latest!!
        assertTrue(after.header.revision > before.header.revision)
        assertEquals(digest, TimelineCanonical.digest(before))
        for (tick in 0L until before.header.sealedThrough / 1024) assertEquals(
            TimelineProfile.sample(before, 1, 1, tick * 1024), TimelineProfile.sample(after, 1, 1, tick * 1024))
        val actual = TimelineProfile.sample(after, 1, 1, after.header.observedThrough) as TimelineValue.Vec3
        assertTrue(kotlin.math.abs(actual.y - (512 + (after.header.observedThrough / 1024 - 12) * 64)) <= 64)
    }

    @Test fun `visibility close keeps authorized history and rediscovery allocates a different identity`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observation(0, listOf(unit(owner = 1))))
        val initial = publisher.publish(0)!!
        publisher.observe(observation(1, emptyList()))
        val closed = publisher.publish(200_000_000)!!
        assertEquals(TimelineCloseKind.ENDED, closed.objects.getValue(1).close!!.kind)
        assertNotNull(TimelineProfile.sample(closed, 1, 1, 512))
        assertNull(TimelineProfile.sample(closed, 1, 1, 1024))
        assertNull(publisher.unitKey(1))
        publisher.observe(observation(2, listOf(unit(owner = 1).copy(position = TimelineValue.Vec3(1024, 512, -1)))))
        val returned = publisher.publish(400_000_000)!!
        assertEquals(2L, returned.header.objectHighWater)
        assertEquals(77L, publisher.unitKey(2))
        assertNotNull(returned.objects[1]?.close)
        assertEquals(TimelineProfile.sample(initial, 1, 1, 0), TimelineProfile.sample(returned, 1, 1, 0))
    }

    @Test fun `short observation between publications is recorded even if no current unit remains`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observation(0, emptyList()))
        publisher.publish(0)
        publisher.observe(observation(1, listOf(unit(owner = 1))))
        publisher.observe(observation(2, emptyList()))
        val state = publisher.publish(200_000_000)!!
        assertEquals(1024L, state.objects.getValue(1).observedFrom)
        assertEquals(2048L, state.objects.getValue(1).close!!.until)
        assertNotNull(TimelineProfile.sample(state, 1, 1, 1500))
    }

    @Test fun `action state reuses capability but descriptor change revokes and replaces it`() {
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observation(0, selected = 77, actions = listOf(action())))
        val first = publisher.publish(0)!!
        val handle = first.objects.values.single { it.kind == TimelineObjectKind.ACTION }.handle
        publisher.observe(observation(1, selected = 77, actions = listOf(action(ready = 2))))
        val changed = publisher.publish(200_000_000)!!
        assertEquals(91L, publisher.actionKey(handle))
        assertEquals(first.header.controlEpoch, changed.header.controlEpoch)
        assertEquals(TimelineValue.UInt(2), TimelineProfile.sample(changed, handle, 1, 1024))
        publisher.observe(observation(2, selected = 77, actions = listOf(action(cost = 200))))
        val replaced = publisher.publish(400_000_000)!!
        assertNull(publisher.actionKey(handle))
        assertNotNull(replaced.objects.getValue(handle).close)
        assertTrue(replaced.header.controlEpoch > changed.header.controlEpoch)
        assertEquals(1, replaced.objects.values.count { it.kind == TimelineObjectKind.ACTION && it.close == null })
    }

    @Test fun `multiselect is published fully while current action panel remains single owner only`() {
        val publisher = TimelinePublisher(f.context())
        val units = listOf(unit(77), unit(88).copy(position = TimelineValue.Vec3(512, 512, -1)))
        publisher.observe(observation(0, units, selected = 77, actions = listOf(action())))
        val first = publisher.publish(0)!!
        publisher.observe(observation(1, units, selection = listOf(88, 77)))
        val multi = publisher.publish(200_000_000)!!
        val bytes = (TimelineProfile.sample(multi, 0, 3, 1024) as TimelineValue.Blob).value
        assertEquals(listOf(1L, 2L), TimelineBinary.decodeSelection(bytes))
        assertTrue(multi.objects.values.none { it.kind == TimelineObjectKind.PANEL && it.close == null })
        assertTrue(multi.header.controlEpoch > first.header.controlEpoch)
    }

    @Test fun `visible effects retain payload and hidden world is absent from observer interface`() {
        val position = ByteArrayOutputStream().let { out -> listOf(256L, 512L, 0L).forEach { TimelineBinary.writeVSInt(out, it) }; TimelineBytes(out.toByteArray()) }
        val effect = TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U32(3)),
            TimelineField(2, true, TimelineTlvValue.Bytes(position)), TimelineField(3, true, TimelineTlvValue.U64(2048)),
            TimelineField(4, true, TimelineTlvValue.U32(99))))
        val event = TimelineObservedEvent(90000, 1024, effect, 2048)
        val publisher = TimelinePublisher(f.context())
        publisher.observe(observation(0))
        publisher.publish(0)
        publisher.observe(observation(1, events = listOf(event)))
        publisher.observe(observation(2))
        val state = publisher.publish(200_000_000)!!
        val visual = state.objects.values.single { it.kind == TimelineObjectKind.VISUAL }
        val pulse = visual.curves.getValue(1).events.single()
        assertEquals(1L, pulse.eventId)
        assertEquals(1024L, pulse.time)
        assertEquals(TimelineValue.Blob(TimelineBinary.encodeRecord(effect)), pulse.value)
        assertEquals(2L, visual.handle)
    }

    @Test fun `full projection stream ignores source key magnitudes and input ordering`() {
        val a = TimelinePublisher(f.context())
        val b = TimelinePublisher(f.context())
        val publicFirst = unit(7).copy(position = TimelineValue.Vec3(256, 512, -1))
        val publicSecond = unit(8, owner = 1).copy(position = TimelineValue.Vec3(512, 512, -1))
        a.observe(observation(0, listOf(publicFirst, publicSecond)))
        b.observe(observation(0, listOf(publicSecond.copy(key = 99991), publicFirst.copy(key = 400))))
        assertEquals(TimelineCanonical.digest(a.publish(0)!!), TimelineCanonical.digest(b.publish(0)!!))
        a.observe(observation(1, listOf(publicFirst)))
        b.observe(observation(1, listOf(publicFirst.copy(key = 400))))
        assertEquals(TimelineCanonical.digest(a.publish(200_000_000)!!), TimelineCanonical.digest(b.publish(200_000_000)!!))
    }

    @Test fun `65536 simultaneous visible effects are bucketed completely with key independent hash`() {
        val position = ByteArrayOutputStream().let { out ->
            listOf(256L, 512L, 0L).forEach { TimelineBinary.writeVSInt(out, it) }; TimelineBytes(out.toByteArray())
        }
        val events = List(65536) { index ->
            TimelineObservedEvent(index.toLong() + 1, 0, TimelineRecord(listOf(
                TimelineField(1, true, TimelineTlvValue.U32(1)), TimelineField(2, true, TimelineTlvValue.Bytes(position)),
                TimelineField(3, true, TimelineTlvValue.U64(1024)), TimelineField(4, true, TimelineTlvValue.U32(index.toLong())))), 1024)
        }
        val a = TimelinePublisher(f.context())
        a.observe(observation(0, emptyList(), events = events))
        val first = a.publish(0)!!
        val visual = first.objects.values.filter { it.kind == TimelineObjectKind.VISUAL }
        assertEquals(16, visual.size)
        assertEquals(65536, visual.sumOf { it.curves.getValue(1).events.size })
        assertTrue(visual.all { it.curves.getValue(1).events.size == 4096 })
        val digest = TimelineCanonical.digest(first)
        val b = TimelinePublisher(f.context())
        b.observe(observation(0, emptyList(), events = events.asReversed().map { it.copy(key = it.key + 1_000_000) }))
        assertEquals(digest, TimelineCanonical.digest(b.publish(0)!!))
    }
}
