package io.github.rwx.net.timeline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TimelineNumericFrameBankTest {
    private fun frame(bank: TimelineNumericFrameBank, entity: TimelineNumericFrameBank.EntityToken,
                      time: Long, x: Long): TimelineNumericFrameBank.FrameToken = bank.beginFrame(time, 1).use { capture ->
        capture.add(entity, x, 2, -3, 65535, 17)
        capture.commit()
    }

    @Test fun `same time captures are separate immutable identities with all numeric columns`() {
        TimelineNumericFrameBank(100, 100).use { bank ->
            val entity = bank.newEntity(); val reader = bank.openReader()
            val first = frame(bank, entity, 1024, 10)
            val original = reader.lease(first)
            val second = frame(bank, entity, 1024, 20)
            val replacement = reader.lease(second)
            assertNotEquals(first.serial, second.serial)
            assertEquals(first.time, second.time)
            assertSame(first, original.token); assertSame(second, replacement.token)
            assertEquals(listOf(entity.ordinal, 10L, 2L, -3L, 65535L, 17L), listOf(
                original.entityOrdinal(0), original.x(0), original.y(0), original.z(0), original.heading(0), original.progress(0)))
            assertEquals(20L, replacement.x(0))
            assertEquals(2, bank.usage().committedFrames)
            assertEquals(96L, bank.usage().payloadBytes)
            original.close(); replacement.close(); reader.close()
        }
    }

    @Test fun `capture reserves bytes and abort or invalid input leaves committed frame unchanged`() {
        val charge = TimelineNumericFrameBank.accountedBytes(1)
        TimelineNumericFrameBank(100, 100, TimelineNumericFrameBank.Limits(maxFrames = 1, maxBytes = charge * 2)).use { bank ->
            val entity = bank.newEntity(); val reader = bank.openReader()
            val first = frame(bank, entity, 0, 7); val original = reader.lease(first)
            val capture = bank.beginFrame(1024, 1)
            assertEquals(charge * 2, bank.usage().accountedBytes)
            assertEquals(96L, bank.usage().payloadBytes)
            assertTrue(bank.usage().captureInProgress)
            assertFailsWith<IllegalStateException> { bank.beginFrame(1024, 0) }
            assertFailsWith<IllegalStateException> { bank.newEntity() }
            assertFailsWith<IllegalStateException> { bank.retire(entity) }
            assertFailsWith<IllegalArgumentException> { capture.add(entity, 100, 0, 0, 0, 0) }
            assertFailsWith<IllegalStateException> { capture.commit() }
            capture.close()
            assertEquals(charge, bank.usage().accountedBytes)
            assertEquals(7L, original.x(0))
            assertFailsWith<TimelineNumericFrameBank.Capacity> { bank.beginFrame(1024, 100) }
            assertEquals(7L, original.x(0))
            val next = frame(bank, entity, 1024, 8)
            assertEquals(charge, bank.usage().accountedBytes)
            assertFailsWith<TimelineNumericFrameBank.Expired> { original.x(0) }
            assertEquals(8L, reader.lease(next).use { it.x(0) })
            original.close(); reader.close()
        }
    }

    @Test fun `expiry between scalar reads rejects partial work and never redirects the old token`() {
        TimelineNumericFrameBank(100, 100, TimelineNumericFrameBank.Limits(maxFrames = 1)).use { bank ->
            val entity = bank.newEntity(); val reader = bank.openReader()
            val first = frame(bank, entity, 0, 11)
            val readFirst = CountDownLatch(1); val continueReading = CountDownLatch(1)
            val second = AtomicReference<TimelineNumericFrameBank.FrameToken>()
            val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                try {
                    reader.lease(first).use { lease ->
                        assertEquals(11L, lease.x(0))
                        readFirst.countDown()
                        check(continueReading.await(5, TimeUnit.SECONDS))
                        assertFailsWith<TimelineNumericFrameBank.Expired> { lease.y(0) }
                        assertFailsWith<TimelineNumericFrameBank.Expired> { lease.rowCount }
                        assertSame(first, lease.token)
                        assertFailsWith<TimelineNumericFrameBank.Expired> { reader.lease(first) }
                    }
                    reader.lease(checkNotNull(second.get())).use { lease ->
                        assertEquals(22L, lease.x(0))
                        assertSame(second.get(), lease.token)
                    }
                } catch (error: Throwable) { failure.set(error); readFirst.countDown() }
            }
            thread.start()
            try {
                assertTrue(readFirst.await(5, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Consumer failed before expiry", it) }
                second.set(frame(bank, entity, 1024, 22))
                assertEquals(1, bank.usage().committedFrames)
            } finally { continueReading.countDown(); thread.join(5000); reader.close() }
            assertFalse(thread.isAlive)
            failure.get()?.let { throw AssertionError("Consumer failed", it) }
            assertEquals(0, bank.usage().leases)
        }
    }

    @Test fun `reader and lease quotas are bounded and releases do not revoke another reader`() {
        TimelineNumericFrameBank(100, 100, TimelineNumericFrameBank.Limits(maxReaders = 2)).use { bank ->
            val entity = bank.newEntity(); val token = frame(bank, entity, 0, 1)
            val first = bank.openReader(); val second = bank.openReader()
            assertFailsWith<TimelineNumericFrameBank.Capacity> { bank.openReader() }
            val a = first.lease(token); val b = first.lease(token); val other = second.lease(token)
            assertFailsWith<TimelineNumericFrameBank.Capacity> { first.lease(token) }
            assertEquals(3, bank.usage().leases)
            assertEquals(TimelineNumericFrameBank.accountedBytes(1), bank.usage().accountedBytes)
            a.close(); a.close()
            first.lease(token).close()
            first.close()
            assertFailsWith<TimelineNumericFrameBank.Expired> { b.x(0) }
            assertEquals(1L, other.x(0))
            assertEquals(1, bank.usage().leases)
            other.close(); second.close()
            bank.openReader().close()
        }
    }

    @Test fun `entity retirement and opaque epochs cannot be replaced by matching native numbers`() {
        TimelineNumericFrameBank(100, 100).use { bank ->
            TimelineNumericFrameBank(100, 100).use { foreign ->
                val entity = bank.newEntity(); val foreignEntity = foreign.newEntity()
                assertEquals(entity.ordinal, foreignEntity.ordinal) // Equal numbers do not grant membership.
                val first = frame(bank, entity, 0, 3)
                val other = frame(foreign, foreignEntity, 0, 4)
                assertEquals(first.serial, other.serial)
                val reader = bank.openReader()
                assertFailsWith<IllegalArgumentException> { reader.lease(other) }
                assertFailsWith<IllegalArgumentException> { bank.retire(foreignEntity) }
                bank.beginFrame(1024, 1).use { capture ->
                    assertFailsWith<IllegalArgumentException> { capture.add(foreignEntity, 0, 0, 0, 0, 0) }
                }
                bank.retire(entity)
                assertFailsWith<IllegalArgumentException> { bank.retire(entity) }
                val reborn = bank.newEntity()
                assertTrue(reborn.ordinal > entity.ordinal)
                bank.beginFrame(1024, 1).use { capture ->
                    assertFailsWith<IllegalArgumentException> { capture.add(entity, 0, 0, 0, 0, 0) }
                    capture.add(reborn, 0, 0, 0, 0, 0); capture.commit()
                    assertFailsWith<IllegalStateException> { capture.add(reborn, 1, 1, 1, 1, 1) }
                    assertFailsWith<IllegalStateException> { capture.commit() }
                }
                reader.lease(first).use { assertEquals(entity.ordinal, it.entityOrdinal(0)) }
                reader.close()
            }
        }
    }

    @Test fun `fixed numeric bounds duplicate rows and committed time are checked before publication`() {
        TimelineNumericFrameBank(100, 100).use { bank ->
            val a = bank.newEntity(); val b = bank.newEntity()
            bank.beginFrame(TimelineProtocol.MAX_TIME, 2).use { capture ->
                capture.add(a, 0, 99, TimelineProtocol.MIN_SINT, 0, 65535)
                assertFailsWith<IllegalArgumentException> { capture.add(a, 0, 0, 0, 0, 0) }
                assertFailsWith<IllegalArgumentException> { capture.add(b, -1, 0, 0, 0, 0) }
                assertFailsWith<IllegalArgumentException> { capture.add(b, 0, 100, 0, 0, 0) }
                assertFailsWith<IllegalArgumentException> { capture.add(b, 0, 0, TimelineProtocol.MAX_SINT + 1, 0, 0) }
                assertFailsWith<IllegalArgumentException> { capture.add(b, 0, 0, 0, 65536, 0) }
                assertFailsWith<IllegalArgumentException> { capture.add(b, 0, 0, 0, 0, -1) }
                capture.add(b, 99, 0, TimelineProtocol.MAX_SINT, 65535, 0)
                val token = capture.commit()
                bank.openReader().use { reader -> reader.lease(token).use { lease ->
                    assertEquals(TimelineProtocol.MIN_SINT, lease.z(0))
                    assertEquals(TimelineProtocol.MAX_SINT, lease.z(1))
                    assertFailsWith<IllegalArgumentException> { lease.x(2) }
                } }
            }
            assertFailsWith<IllegalArgumentException> { bank.beginFrame(TimelineProtocol.MAX_TIME - 1, 0) }
            assertFailsWith<IllegalArgumentException> { bank.beginFrame(Long.MAX_VALUE, 0) }
            assertFailsWith<IllegalArgumentException> { bank.beginFrame(TimelineProtocol.MAX_TIME, TimelineProtocol.MAX_UNITS + 1) }
        }
    }

    @Test fun `owner and consumer confinement remain enforced and close revokes all columns`() {
        val bank = TimelineNumericFrameBank(100, 100)
        val entity = bank.newEntity(); val token = frame(bank, entity, 0, 4)
        val reader = bank.openReader(); val lease = reader.lease(token)
        val failure = AtomicReference<Throwable?>()
        Thread {
            try {
                assertFailsWith<IllegalStateException> { bank.newEntity() }
                assertFailsWith<IllegalStateException> { bank.beginFrame(1024, 0) }
                assertFailsWith<IllegalStateException> { lease.x(0) }
                assertFailsWith<IllegalStateException> { reader.lease(token) }
            } catch (error: Throwable) { failure.set(error) }
        }.also { it.start(); it.join(5000); assertFalse(it.isAlive) }
        failure.get()?.let { throw AssertionError("Thread confinement check failed", it) }
        bank.discardBefore(token.serial) // Exclusive boundary preserves the token itself.
        assertEquals(4L, lease.x(0))
        bank.close(); bank.close()
        assertFailsWith<TimelineNumericFrameBank.Expired> { lease.x(0) }
        assertFailsWith<TimelineNumericFrameBank.Expired> { reader.lease(token) }
        assertEquals(0L, bank.usage().accountedBytes)
        assertEquals(0L, bank.usage().payloadBytes)
        assertEquals(0, bank.usage().readers)
        assertEquals(0, bank.usage().leases)
        assertTrue(TimelineNumericFrameBank.Lease::class.java.methods.none { it.returnType.isArray ||
            Iterator::class.java.isAssignableFrom(it.returnType) || kotlin.Function::class.java.isAssignableFrom(it.returnType) })
    }
}
