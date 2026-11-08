package io.github.rwx.net.timeline

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.Projectile
import com.corrodinggames.rts.game.units.land.TankUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.Utility
import com.corrodinggames.rts.gameFramework.graphics.Texture
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.utility.TransactionalArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TimelineNativeEventsTest {
    private val subject = Any()
    private data class Fact(val kind: TimelineEngineEventKind, val x: Float, val y: Float, val z: Float)
    private fun capture(out: MutableList<Fact>) = TimelineNativeEvents.installListener { kind, _, x, y, z -> out.add(Fact(kind, x, y, z)) }

    @Test fun `disabled hooks are inert even without an engine or valid world coordinates`() {
        TimelineNativeEvents.fire(subject, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        TimelineNativeEvents.hit(Float.NaN, Float.NaN, Float.NaN)
        val failure = AtomicReference<Throwable?>()
        Thread { failure.set(runCatching { TimelineNativeEvents.explosion(Float.NaN, Float.NaN, Float.NaN) }.exceptionOrNull()) }
            .apply { start(); join() }
        assertNull(failure.get())
    }

    @Test fun `listener is exclusive owner bound and an old close cannot detach its successor`() {
        val first = ArrayList<Fact>()
        val token = capture(first)
        try {
            assertFailsWith<IllegalStateException> { capture(ArrayList()) }
            val failure = AtomicReference<Throwable?>()
            Thread { failure.set(runCatching { TimelineNativeEvents.fire(subject, 1f, 2f, 3f) }.exceptionOrNull()) }
                .apply { start(); join() }
            assertIs<IllegalStateException>(failure.get())
            Thread { failure.set(runCatching { token.close() }.exceptionOrNull()) }.apply { start(); join() }
            assertIs<IllegalStateException>(failure.get())
            TimelineNativeEvents.fire(subject, 1f, 2f, 3f)
            TimelineNativeEvents.hit(4f, 5f, -6f)
            assertEquals(listOf(Fact(TimelineEngineEventKind.FIRE, 1f, 2f, 3f), Fact(TimelineEngineEventKind.HIT, 4f, 5f, -6f)), first)
        } finally { token.close() }
        val successor = ArrayList<Fact>()
        capture(successor).use {
            token.close()
            TimelineNativeEvents.explosion(7f, 8f, 9f)
            assertEquals(listOf(Fact(TimelineEngineEventKind.EXPLOSION, 7f, 8f, 9f)), successor)
        }
        TimelineNativeEvents.fire(subject, 0f, 0f, 0f)
        assertEquals(2, first.size)
        assertEquals(1, successor.size)
    }

    @Test fun `native adapter records only frozen visible positions and fixed presentation durations`() {
        val journal = TimelineEngineEvents(2, 2, 20, 20, 60)
        val grant = journal.openGrant(0)
        assertFailsWith<IllegalArgumentException> { TimelineNativeEvents.install(journal, 30) }
        TimelineNativeEvents.install(journal).use {
            TimelineNativeEvents.fire(subject, 1f, 2f, 3f) // Before a scope: no historical reconstruction.
            journal.beginStep(0, 1024)
            grant.authorizeStep(TimelineTestFixture.bytes(0, 10, 10, 10), listOf(subject))
            TimelineNativeEvents.fire(subject, 1f, 2f, 3f)
            TimelineNativeEvents.hit(21f, 2f, 3f)
            TimelineNativeEvents.explosion(1f, 2f, -3f)
            journal.endStep()
        }
        val facts = grant.drain()
        assertEquals(listOf(1L, 3L), facts.map { (it.effect[1]!!.value as TimelineTlvValue.U32).value })
        assertEquals(listOf(6144L, 61440L), facts.map { it.maxTailTime })
        assertEquals(listOf(1023L, 1023L), facts.map { it.time })
    }

    private class NativeFixtureTank : TankUnit(true) {
        override fun a(texture: Texture?, frames: Int) { T(20); U(20) }
        override fun bu() { isDead = true }
        override fun e(): Boolean = true
        override fun bt() {}
        override fun remove() {}
    }

    @Test fun `real projectile factories emit once per overload and removal is not a death event`() {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null)
        GameEngine.isNonAndroidVersion = true
        GameLogic().apply { networkEngine = NetworkEngine() }
        @Suppress("UNCHECKED_CAST")
        val objects = GameObject::class.java.getDeclaredField("a").apply { isAccessible = true }.get(null) as TransactionalArrayList<GameObject>
        val priorPending = objects.pendingOperations.toList()
        val priorCompleted = objects.completedOperations.toList()
        val priorFast = GameObject.fastGameObjectList.toList()
        val priorProjectiles = Projectile.a.toList()
        objects.pendingOperations.clear()
        objects.completedOperations.clear()
        val facts = ArrayList<Fact>()
        try {
            capture(facts).use {
                val source = NativeFixtureTank().apply { posZ = 7f; drawOrder = 4; unitCounter = 31 }
                val low = Projectile.a(source, 1f, 2f)
                assertEquals(31, source.unitCounter)
                assertEquals(0f, low.posZ) // The original low-level factory's simulation value stays unchanged.
                assertEquals(5, low.drawOrder)
                val expectedSeed = Utility.getDeterministicRandomFloatForUnit(source, 0f, 1f, 31)
                val high = Projectile.a(source, 3f, 4f, 9f, 2)
                assertEquals(32, source.unitCounter)
                assertEquals(expectedSeed, high.randomSeed)
                assertEquals(9f, high.posZ)
                assertEquals(2.toShort(), high.techLevel)
                assertEquals(listOf(Fact(TimelineEngineEventKind.FIRE, 1f, 2f, 7f), Fact(TimelineEngineEventKind.FIRE, 3f, 4f, 9f)), facts)
                source.removeFromGame()
                assertEquals(2, facts.size)
                val dying = NativeFixtureTank().apply { posX = 5f; posY = 6f; posZ = 7f }
                dying.bv()
                dying.bv()
                assertEquals(3, facts.size)
                assertEquals(Fact(TimelineEngineEventKind.EXPLOSION, 5f, 6f, 7f), facts.last())
            }
            val privacyJournal = TimelineEngineEvents(2, 2, 20, 20, 60)
            val privacyGrant = privacyJournal.openGrant(0)
            val visible = NativeFixtureTank().apply { posX = 1f; posY = 2f }
            val hiddenCargo = NativeFixtureTank().apply { posX = 1f; posY = 2f; transportContainer = visible }
            privacyJournal.beginStep(0, 1024)
            privacyGrant.authorizeStep(TimelineTestFixture.bytes(0, 0, 0, 0), listOf(visible))
            TimelineNativeEvents.install(privacyJournal).use {
                // Native transport teardown clears this field before markForDeath/bv.
                // Only frozen identity membership can still distinguish the hidden cargo.
                hiddenCargo.transportContainer = null
                hiddenCargo.bv()
                TimelineNativeEvents.fire(hiddenCargo, 1f, 2f, 0f)
                visible.bv()
            }
            privacyJournal.endStep()
            assertEquals(1, privacyGrant.drain().size)
            val journal = TimelineEngineEvents(2, 2, 20, 20, 60)
            val grant = journal.openGrant(0)
            val source = NativeFixtureTank().apply { unitCounter = 51 }
            journal.beginStep(0, 1024)
            grant.authorizeStep(TimelineTestFixture.bytes(0, 0, 0, 0), listOf(source))
            TimelineNativeEvents.install(journal).use {
                val expectedSeed = Utility.getDeterministicRandomFloatForUnit(source, 0f, 1f, 51)
                val invalid = Projectile.a(source, Float.NaN, 2f, 9f, 2)
                assertTrue(invalid.posX.isNaN())
                assertEquals(52, source.unitCounter)
                assertEquals(expectedSeed, invalid.randomSeed)
                assertEquals(9f, invalid.posZ)
                assertEquals(TimelineEngineEventFailure.INVALID_NATIVE_EVENT, journal.failure)
                assertEquals(TimelineEngineEventFailure.INVALID_NATIVE_EVENT, grant.failure)
                TimelineNativeEvents.fire(subject, 1f, 2f, 3f)
            }
            journal.endStep()
            assertFailsWith<IllegalStateException> { grant.drain() }
        } finally {
            objects.pendingOperations.clear()
            objects.pendingOperations.addAll(priorPending)
            objects.completedOperations.clear()
            objects.completedOperations.addAll(priorCompleted)
            GameObject.fastGameObjectList.clear()
            priorFast.forEach { GameObject.fastGameObjectList.add(it) }
            Projectile.a.clear()
            Projectile.a.addAll(priorProjectiles)
            instance.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform
        }
    }
}
