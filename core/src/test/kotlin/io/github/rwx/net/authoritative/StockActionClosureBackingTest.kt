package com.corrodinggames.rts.game.units.custom

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitAction
import com.corrodinggames.rts.game.units.UnitContainer
import com.corrodinggames.rts.game.units.custom.hooks.AttachmentSlotDefinition
import com.corrodinggames.rts.game.units.custom.logic.CustomAction
import com.corrodinggames.rts.game.units.custom.logic.CustomActionDef
import com.corrodinggames.rts.game.units.custom.logic.actions.AttachmentAction
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.game.units.custom.price.UnitPrice
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.authoritative.StockActionClosure
import io.github.rwx.net.authoritative.StockUnitCatalog
import io.github.rwx.net.authoritative.StockUnitManifest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Java package-local setup; production probes themselves access fields via the existing Access shim. */
class StockActionClosureBackingTest {
    private class CallbackFailure : RuntimeException("Legacy fixture callback")
    private enum class FailureOn { NONE, SIZE, GET }
    private class TraceList<E>(val trace: MutableList<String>) : java.util.ArrayList<E>() {
        var fail = FailureOn.NONE
        override val size: Int get() {
            trace.add("size")
            if (fail == FailureOn.SIZE) throw CallbackFailure()
            return super.size
        }
        override fun get(index: Int): E {
            trace.add("get:$index")
            if (fail == FailureOn.GET) throw CallbackFailure()
            return super.get(index)
        }
        override fun iterator(): MutableIterator<E> { trace.add("iterator"); return super.iterator() }
        override fun equals(other: Any?): Boolean { trace.add("equals"); return super.equals(other) }
        override fun hashCode(): Int { trace.add("hashCode"); return super.hashCode() }
    }
    private class Slot(val trace: MutableList<String>) : AttachmentSlotDefinition() {
        var armed = false
        override fun a(): Short {
            trace.add("slot.a")
            if (armed) throw CallbackFailure()
            return super.a()
        }
    }
    private class Fixture(val engine: GameLogic) {
        fun config() = CustomUnitConfig().apply {
            val spec = StockUnitManifest.bundled.first { it.name == "mechFactory" }
            name = spec.name; configPath = "assets/${spec.path}"; sourceFilePath = configPath
            resourceLoadPath = configPath; configHash = 17
            specialActionLists = Array(3) { UnitContainer() }
            CustomUnitConfig.activeConfigs.add(this)
            spec.replaces?.let { CustomUnitConfig.unitTypeOverrides[it] = this }
        }
        fun action(config: CustomUnitConfig, subclass: Boolean = false): CustomAction {
            val definition = CustomActionDef().apply { name = "shapeFixture"; price = UnitPrice.a(0) }
            config.customActionDefs.add(definition)
            val action = if (subclass) object : CustomAction(definition, null) {} else CustomAction(definition, null)
            config.specialActionLists.forEach { it.a.add(action) }
            return action
        }
        fun capture() = StockActionClosure.capture(engine, StockUnitCatalog.capture(engine))
    }
    private fun <T> world(block: Fixture.() -> T): T {
        val field = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = field.get(null); val platform = GameEngine.isNonAndroidVersion
        field.set(null, null); GameEngine.isNonAndroidVersion = true
        try {
            // Config's static dependencies may create native teams/prototypes. They need
            // a complete current engine before even reading the old static registries.
            val engine = GameLogic().apply { networkEngine = NetworkEngine() }
            val registry = CustomUnitConfig.activeConfigs
            val overrides = HashMap(CustomUnitConfig.unitTypeOverrides)
            return try {
                CustomUnitConfig.activeConfigs = arrayListOf(); CustomUnitConfig.unitTypeOverrides.clear()
                Fixture(engine).block()
            } finally {
                CustomUnitConfig.activeConfigs = registry
                CustomUnitConfig.unitTypeOverrides.clear(); CustomUnitConfig.unitTypeOverrides.putAll(overrides)
            }
        } finally {
            field.set(null, previous); GameEngine.isNonAndroidVersion = platform
        }
    }

    @Test fun `known ArrayList final FastArrayList and reference arrays qualify without evaluating policy or condition`() = world {
        val config = config(); val action = action(config)
        var callbacks = 0
        action.actionDef.isActive = object : LogicBoolean() {
            override fun read(unit: OrderableUnit): Boolean { callbacks++; throw CallbackFailure() }
            override fun getMatchFailReasonForPlayer(unit: OrderableUnit): String { callbacks++; throw CallbackFailure() }
        }
        action.unitAction = object : UnitAction() {
            override fun a(): UnitPrice? { callbacks++; throw CallbackFailure() }
            override fun b(): UnitPrice? { callbacks++; throw CallbackFailure() }
        }
        val closure = capture()
        assertTrue(closure.hasKnownValidatorBackings())
        assertTrue(closure.hasKnownValidatorBackings())
        closure.assertValid()
        assertNotNull(closure.token(action))
        assertEquals(0, callbacks) // Does not mean these dynamic operations are qualified.
    }

    private data class FailureTrace(val trace: List<String>, val exceptionClass: String, val causeClass: String?)
    private fun unknownMenuFailure(withProbe: Boolean, failureOn: FailureOn): FailureTrace = world {
        val config = config(); val action = action(config)
        val trace = arrayListOf<String>(); val unknown = TraceList<Any?>(trace).apply { add(action) }
        config.specialActionLists[2].a = unknown // Last menu, same backing retained by its check.
        val closure = capture()
        trace.clear(); unknown.fail = failureOn
        if (withProbe) assertFalse(closure.hasKnownValidatorBackings())
        assertTrue(trace.isEmpty(), "Qualification called a container method")
        val error = assertFailsWith<IllegalStateException> { closure.assertValid() }
        assertIs<CallbackFailure>(error.cause)
        unknown.fail = FailureOn.NONE
        val observed = trace.toList()
        assertFailsWith<IllegalStateException> { closure.assertValid() }
        assertEquals(observed, trace, "Permanent revocation re-executed callbacks")
        FailureTrace(observed, error.javaClass.name, error.cause?.javaClass?.name)
    }

    @Test fun `unknown captured backing probe does nothing and fallback preserves size and get exceptions in order`() {
        for (mode in listOf(FailureOn.SIZE, FailureOn.GET)) {
            val ordinary = unknownMenuFailure(false, mode)
            val probed = unknownMenuFailure(true, mode)
            assertEquals(ordinary, probed)
            assertEquals(if (mode == FailureOn.SIZE) listOf("size") else listOf("size", "get:0"), probed.trace)
        }
    }

    @Test fun `replacing current backing with an unknown subclass is rejected before any container callback`() = world {
        val config = config(); action(config)
        val original = config.specialActionLists[2].a
        val closure = capture()
        val trace = arrayListOf<String>()
        val unknown = TraceList<Any?>(trace).apply { addAll(original); fail = FailureOn.SIZE }
        config.specialActionLists[2].a = unknown
        assertFalse(closure.hasKnownValidatorBackings())
        assertTrue(trace.isEmpty())
        // Legacy identity check rejects before size; probing must not move that callback.
        assertFailsWith<IllegalStateException> { closure.assertValid() }
        assertTrue(trace.isEmpty())
        config.specialActionLists[2].a = original
        assertFalse(closure.hasKnownValidatorBackings())
    }

    @Test fun `a changed known identity and a null field still reach ordinary validation without pre-revocation`() {
        world {
            val config = config(); action(config)
            val original = config.specialActionLists[2].a
            val closure = capture()
            config.specialActionLists[2].a = java.util.ArrayList(original)
            assertTrue(closure.hasKnownValidatorBackings()) // A shape check, not content validation.
            assertFailsWith<IllegalStateException> { closure.assertValid() }
            config.specialActionLists[2].a = original
            assertFalse(closure.hasKnownValidatorBackings())
            assertFailsWith<IllegalStateException> { closure.assertValid() }
        }
        world {
            val config = config(); action(config)
            val original = config.specialActionLists
            val closure = capture()
            config.specialActionLists = null
            assertTrue(closure.hasKnownValidatorBackings())
            // A true qualification neither asserts nor revokes: restoration before legacy
            // validation still succeeds, just as it did without this pure probe.
            config.specialActionLists = original
            closure.assertValid()
            config.specialActionLists = null
            assertTrue(closure.hasKnownValidatorBackings())
            assertFailsWith<IllegalStateException> { closure.assertValid() }
        }
    }

    @Test fun `unsupported manual nodes still contribute their late backing probes`() = world {
        val config = config(); val denied = action(config, subclass = true)
        val trace = arrayListOf<String>(); val unknown = TraceList<Any?>(trace)
        denied.actionDef.logicActions.add(AttachmentAction().apply { onlyOnSlots = unknown })
        val closure = capture()
        assertTrue(closure.failures().any { it.reason == "Unreviewed custom action subclass" })
        assertNull(closure.token(denied))
        trace.clear(); unknown.fail = FailureOn.SIZE
        assertFalse(closure.hasKnownValidatorBackings())
        assertTrue(trace.isEmpty())
        val failure = assertFailsWith<IllegalStateException> { closure.assertValid() }
        assertIs<CallbackFailure>(failure.cause)
        assertEquals(listOf("size"), trace)
    }

    @Test fun `unsupported attachment virtual getter is not speculatively called by qualification`() = world {
        val config = config(); val trace = arrayListOf<String>(); val slot = Slot(trace)
        config.attachmentSlotDefinitions.add(slot)
        val closure = capture()
        assertTrue(closure.failures().any { it.reason == "Unreviewed attachment slot subclass" })
        trace.clear(); slot.armed = true
        assertFalse(closure.hasKnownValidatorBackings())
        assertTrue(trace.isEmpty())
        val failure = assertFailsWith<IllegalStateException> { closure.assertValid() }
        assertIs<CallbackFailure>(failure.cause)
        assertEquals(listOf("slot.a"), trace)
    }

    @Test fun `parser fallback backing qualification cannot authorize changed semantics or undo revocation`() = world {
        val config = config()
        val projectile = CustomProjectileTemplate().apply { projectileId = 0; projectileName = "1"; b = 10 }
        config.projectileTemplates.add(projectile); config.projectileTemplatesById = arrayOf(projectile)
        val closure = capture()
        assertTrue(closure.hasKnownValidatorBackings())
        closure.assertValid()
        projectile.b = 11
        assertTrue(closure.hasKnownValidatorBackings())
        assertFailsWith<IllegalStateException> { closure.assertValid() }
        projectile.b = 10
        assertFalse(closure.hasKnownValidatorBackings())
    }

    @Test fun `qualification is owner confined and does not call any validator on another thread`() = world {
        val config = config(); action(config); val closure = capture()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { assertFailsWith<IllegalStateException> { closure.hasKnownValidatorBackings() } }
            catch (problem: Throwable) { failure.set(problem) }
        }
        thread.start(); thread.join(5000)
        assertFalse(thread.isAlive)
        failure.get()?.let { throw AssertionError("Owner check failed", it) }
        assertTrue(closure.hasKnownValidatorBackings())
    }
}
