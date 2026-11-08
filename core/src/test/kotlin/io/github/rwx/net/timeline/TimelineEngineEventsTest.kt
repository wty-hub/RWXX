package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TimelineEngineEventsTest {
    private val f = TimelineTestFixture
    private val left = TimelineValue.Vec3(256, 512, -33)
    private val right = TimelineValue.Vec3(6000, 512, 99)
    private fun journal(capacity: Int = 65_536) = TimelineEngineEvents(2, 2, 20, 20, 60, capacity)
    private fun fog(vararg values: Int) = f.bytes(*values)
    private fun coordinate(event: TimelineObservedEvent): TimelineValue.Vec3 {
        val cursor = TimelineBinary.Cursor((event.effect[2]!!.value as TimelineTlvValue.Bytes).value)
        return TimelineValue.Vec3(cursor.vsint(), cursor.vsint(), cursor.vsint()).also { cursor.finish() }
    }

    @Test fun `event scope is immutable pre step sight with an exclusive end stamp`() {
        val journal = journal()
        val grant = journal.openGrant(0)
        val sourceFog = byteArrayOf(0, 10, 10, 10)
        val scopeFog = TimelineBytes(sourceFog)
        journal.beginStep(0, 1024)
        grant.authorizeStep(scopeFog)
        sourceFog[1] = 0 // Even the source array cannot change the already fixed authorization.
        journal.record(TimelineEngineEventKind.FIRE, left, 100)
        journal.record(TimelineEngineEventKind.EXPLOSION, right, 200)
        assertFailsWith<IllegalStateException> { grant.authorizeStep(fog(0, 0, 10, 10)) }
        assertFailsWith<IllegalStateException> { grant.drain() }
        journal.endStep()
        val only = grant.drain().single()
        assertEquals(1L, only.key)
        assertEquals(1023L, only.time)
        assertEquals(left, coordinate(only))
        assertEquals(setOf(1, 2, 3, 4), only.effect.fields.map { it.id }.toSet())
        assertEquals(1L, (only.effect[1]!!.value as TimelineTlvValue.U32).value)
        assertEquals(100L, only.maxTailTime)
        assertEquals(100L, (only.effect[3]!!.value as TimelineTlvValue.U64).value)
        assertTrue((only.effect[4]!!.value as TimelineTlvValue.U32).value in 0..TimelineProtocol.MAX_U32)

        journal.beginStep(1024, 2048)
        grant.authorizeStep(fog(0, 0, 10, 10))
        journal.record(TimelineEngineEventKind.EXPLOSION, right, 200)
        journal.endStep()
        assertEquals(listOf(2047L), grant.drain().map { it.time })
        assertTrue(grant.drain().isEmpty())
    }

    @Test fun `each grant counts only its own authorized facts and needs an explicit step scope`() {
        val journal = journal()
        val a = journal.openGrant(0)
        val b = journal.openGrant(0)
        journal.record(TimelineEngineEventKind.FIRE, left, 0) // Unscoped loading work is ignored.
        journal.beginStep(0, 1024)
        a.authorizeStep(fog(0, 10, 10, 10))
        b.authorizeStep(fog(10, 0, 10, 10))
        repeat(20) { journal.record(TimelineEngineEventKind.FIRE, right, 0) }
        journal.record(TimelineEngineEventKind.HIT, left, 10)
        journal.endStep()
        assertEquals(1L, a.drain().single().key)
        assertEquals((1L..20L).toList(), b.drain().map { it.key })
        journal.beginStep(1024, 2048)
        journal.record(TimelineEngineEventKind.FIRE, left, 0)
        journal.endStep()
        assertTrue(a.drain().isEmpty())
        assertTrue(b.drain().isEmpty())
    }

    @Test fun `subject authorization uses frozen identity rather than equality or a later reveal`() {
        class Subject {
            override fun equals(other: Any?): Boolean = error("Subject equality is not an authorization operation")
            override fun hashCode(): Int = error("Subject hash is not an authorization operation")
        }
        val known = Subject()
        val hidden = Subject()
        val identities = arrayListOf<Any>(known)
        val journal = journal()
        val grant = journal.openGrant(0)
        journal.beginStep(0, 1024)
        grant.authorizeStep(fog(0, 0, 0, 0), identities)
        identities.clear()
        identities.add(hidden)
        journal.recordSubjectWorld(hidden, TimelineEngineEventKind.EXPLOSION, 1f, 2f, 0f, 100)
        journal.recordSubjectWorld(known, TimelineEngineEventKind.EXPLOSION, 1f, 2f, 0f, 100)
        journal.endStep()
        assertEquals(1L, grant.drain().single().key)
        journal.beginStep(1024, 2048)
        grant.authorizeStep(fog(0, 0, 0, 0))
        journal.recordSubjectWorld(known, TimelineEngineEventKind.FIRE, 1f, 2f, 0f, 100)
        journal.recordWorld(TimelineEngineEventKind.HIT, 1f, 2f, 0f, 100)
        journal.endStep()
        val pointFact = grant.drain().single()
        assertEquals(2L, pointFact.key)
        assertEquals(2L, (pointFact.effect[1]!!.value as TimelineTlvValue.U32).value)
    }

    @Test fun `join boundary and closed generation cannot gain earlier events`() {
        val journal = journal()
        val old = journal.openGrant(0)
        val joining = journal.openGrant(1024)
        journal.beginStep(0, 1024)
        old.authorizeStep(fog(0, 0, 0, 0))
        assertFailsWith<ProtocolException> { joining.authorizeStep(fog(0, 0, 0, 0)) }
        assertFailsWith<IllegalStateException> { journal.openGrant(0) }
        journal.record(TimelineEngineEventKind.FIRE, left, 0)
        journal.endStep()
        old.close()
        assertFailsWith<IllegalStateException> { old.drain() }
        assertTrue(joining.drain().isEmpty())
        journal.beginStep(1024, 2048)
        assertFailsWith<IllegalStateException> { old.authorizeStep(fog(0, 0, 0, 0)) }
        joining.authorizeStep(fog(0, 0, 0, 0))
        journal.record(TimelineEngineEventKind.FIRE, left, 0)
        journal.endStep()
        assertEquals(1L, joining.drain().single().key)
    }

    @Test fun `overflow fails the affected grant without delivering a silently truncated batch`() {
        val journal = journal(capacity = 1)
        val a = journal.openGrant(0)
        val b = journal.openGrant(0)
        journal.beginStep(0, 1024)
        a.authorizeStep(fog(0, 10, 10, 10))
        b.authorizeStep(fog(10, 10, 0, 10))
        repeat(100) { journal.record(TimelineEngineEventKind.FIRE, right, 0) }
        assertNull(a.failure)
        journal.record(TimelineEngineEventKind.FIRE, left, 0)
        assertNull(a.failure)
        journal.record(TimelineEngineEventKind.HIT, left, 1)
        assertEquals(TimelineEngineEventFailure.CAPACITY_EXCEEDED, a.failure)
        journal.record(TimelineEngineEventKind.EXPLOSION, TimelineValue.Vec3(256, 6000, 0), 1)
        journal.endStep()
        assertFailsWith<IllegalStateException> { a.drain() }
        assertNull(b.failure)
        assertEquals(1L, b.drain().single().key)
        journal.beginStep(1024, 2048)
        assertFailsWith<IllegalStateException> { a.authorizeStep(fog(0, 0, 0, 0)) }
        journal.endStep()
    }

    @Test fun `native position rounding cannot move an unauthorized event into a visible tile`() {
        val journal = journal()
        val grant = journal.openGrant(0)
        journal.beginStep(0, 1024)
        grant.authorizeStep(fog(10, 0, 10, 10))
        journal.recordWorld(TimelineEngineEventKind.FIRE, -1f, 1f, 2.25f, 0)
        journal.recordWorld(TimelineEngineEventKind.FIRE, 40f, 1f, 2.25f, 0)
        journal.recordWorld(TimelineEngineEventKind.FIRE, 19.999f, 1f, 2.25f, 0)
        journal.recordWorld(TimelineEngineEventKind.FIRE, 20f, 1f, 2.25f, 0)
        journal.endStep()
        assertEquals(TimelineValue.Vec3(5120, 256, 576), coordinate(grant.drain().single()))
        journal.beginStep(1024, 2048)
        grant.authorizeStep(fog(0, 10, 10, 10))
        journal.recordWorld(TimelineEngineEventKind.FIRE, 19.999f, 1f, 2.25f, 0)
        journal.endStep()
        assertTrue(grant.drain().isEmpty())
    }

    @Test fun `malformed facts and cross thread access fail without changing a valid batch`() {
        val journal = journal()
        val grant = journal.openGrant(0)
        journal.beginStep(0, 1024)
        assertFailsWith<ProtocolException> { grant.authorizeStep(fog(0, 0, 0, 11)) }
        grant.authorizeStep(fog(0, 0, 0, 0))
        assertFailsWith<ProtocolException> { journal.record(TimelineEngineEventKind.HIT, left.copy(x = -1), 0) }
        assertFailsWith<ProtocolException> { journal.record(TimelineEngineEventKind.HIT, left, 614401) }
        assertFailsWith<ProtocolException> { journal.recordWorld(TimelineEngineEventKind.HIT, Float.NaN, 0f, 0f, 0) }
        val failure = AtomicReference<Throwable?>()
        Thread { failure.set(runCatching { journal.record(TimelineEngineEventKind.FIRE, left, 0) }.exceptionOrNull()) }
            .apply { start(); join() }
        assertIs<IllegalStateException>(failure.get())
        journal.record(TimelineEngineEventKind.HIT, left, 0)
        journal.endStep()
        assertEquals(1L, grant.drain().single().key)
        assertFailsWith<ProtocolException> { journal.beginStep(0, 1024) }
    }

    @Test fun `hidden facts and reordered native callbacks cannot change published event bytes`() {
        fun state(extraHidden: Boolean): TimelineState {
            val journal = journal()
            val grant = journal.openGrant(0)
            val scope = fog(0, 10, 10, 10)
            val publisher = TimelinePublisher(f.context())
            fun observation(time: Long, events: List<TimelineObservedEvent>) = TimelineObservation(time, 1000,
                (f.running() as TimelineValue.Blob).value, scope, emptyList(), events = events)
            publisher.observe(observation(0, emptyList()))
            publisher.publish(0)
            journal.beginStep(0, 1024)
            grant.authorizeStep(scope)
            if (extraHidden) {
                journal.record(TimelineEngineEventKind.HIT, left.copy(x = 512), 200)
                repeat(20) { journal.record(TimelineEngineEventKind.EXPLOSION, right, it.toLong()) }
                journal.record(TimelineEngineEventKind.FIRE, left, 100)
            } else {
                journal.record(TimelineEngineEventKind.FIRE, left, 100)
                journal.record(TimelineEngineEventKind.HIT, left.copy(x = 512), 200)
            }
            journal.endStep()
            publisher.observe(observation(1024, grant.drain()))
            return publisher.publish(200_000_000)!!
        }
        val a = state(false)
        val b = state(true)
        assertEquals(2, a.objects.values.sumOf { obj -> obj.curves.values.sumOf { it.events.size } })
        assertEquals(TimelineCanonical.digest(a), TimelineCanonical.digest(b))
        val bytesA = ByteArrayOutputStream().also { TimelineCanonical.write(a, it) }.toByteArray()
        val bytesB = ByteArrayOutputStream().also { TimelineCanonical.write(b, it) }.toByteArray()
        assertContentEquals(bytesA, bytesB)
    }
}
