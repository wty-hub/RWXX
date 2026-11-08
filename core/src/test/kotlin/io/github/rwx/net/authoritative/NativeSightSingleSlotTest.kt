package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.gameFramework.GameEngine
import java.lang.reflect.InvocationTargetException
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Counts real private-map lookups; all original catalog and scope validation still executes. */
class NativeSightSingleSlotTest {
    private class CountingMap(values: Map<UnitType, Any>) : IdentityHashMap<UnitType, Any>() {
        var gets = 0
        init { putAll(values) }
        override fun get(key: UnitType): Any? { gets++; return super.get(key) }
    }
    private class Fixture(val stock: StockUnitCatalog, val config: CustomUnitConfig, val map: CountingMap,
                          val engine: GameLogic)

    private fun withCatalog(block: Fixture.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val platform = GameEngine.isNonAndroidVersion
        instance.set(null, null); GameEngine.isNonAndroidVersion = true
        // As in the existing scope fixtures, initialize the engine before content statics.
        val engine = try { GameLogic() } catch (failure: Throwable) {
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = platform
            throw failure
        }
        val registry = CustomUnitConfig.activeConfigs
        val overrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        try {
            val spec = StockUnitManifest.bundled.single { it.replaces === UnitTypeEnum.tank }
            val config = CustomUnitConfig().apply {
                name = spec.name; configPath = "assets/${spec.path}"
                sourceFilePath = configPath; resourceLoadPath = configPath
            }
            CustomUnitConfig.activeConfigs = arrayListOf(config)
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = config
            val stock = StockUnitCatalog.capture(engine)
            val field = StockUnitCatalog::class.java.getDeclaredField("byType").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val original = field.get(stock) as Map<UnitType, Any>
            val counting = CountingMap(original)
            field.set(stock, counting)
            try { Fixture(stock, config, counting, engine).block() }
            finally { field.set(stock, original) }
        } finally {
            CustomUnitConfig.activeConfigs = registry
            CustomUnitConfig.unitTypeOverrides.clear(); CustomUnitConfig.unitTypeOverrides.putAll(overrides)
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = platform
        }
    }

    @Test fun `one slot caches consecutive identity hits and unknown null results but not aliases or earlier keys`() = withCatalog {
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            repeat(64) { assertEquals("tank", scope.keyFor(config)) }
            assertEquals(1, map.gets)
            assertEquals("builder", scope.keyFor(UnitTypeEnum.builder))
            assertEquals("tank", scope.keyFor(config))
            assertEquals(3, map.gets, "The earlier key was evicted, not held in a multi-entry cache")
            val unknown = CustomUnitConfig().apply { name = config.name; configPath = config.configPath }
            repeat(64) { assertNull(scope.keyFor(unknown)) }
            assertEquals(4, map.gets, "Unknown types cache a real null association without acquiring authority")
            assertNull(scope.keyFor(CustomUnitConfig()))
            assertEquals(5, map.gets, "An equal-looking distinct identity is a miss")
            assertEquals("tank", scope.keyFor(UnitTypeEnum.tank))
            assertEquals("tank", scope.keyFor(config))
            assertEquals(7, map.gets, "Native replacement aliases retain distinct type identities")
        } finally { scope.close() }
    }

    @Test fun `cache hits still reject foreign owner engine expired and permanently invalidated scope`() = withCatalog {
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        assertEquals("tank", scope.keyFor(config)); assertEquals(1, map.gets)
        val wrongThread = AtomicReference<Throwable?>()
        thread { wrongThread.set(runCatching { scope.keyFor(config) }.exceptionOrNull()) }.join()
        assertIs<IllegalStateException>(wrongThread.get())
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        instance.set(null, null)
        try { assertFailsWith<IllegalStateException> { scope.keyFor(config) } }
        finally { instance.set(null, engine) }
        assertEquals(1, map.gets)
        scope.close()
        assertFailsWith<IllegalStateException> { scope.keyFor(config) }
        assertEquals(1, map.gets)
        for (name in listOf("cachedType", "cachedEntry")) {
            assertNull(scope.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(scope), "Close must clear $name")
        }
        assertFalse(scope.javaClass.getDeclaredField("hasCachedType").apply { isAccessible = true }.getBoolean(scope))
        val next = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            assertEquals("tank", next.keyFor(config)); assertEquals(2, map.gets, "A new scope starts independently")
            scope.close()
            assertEquals("tank", next.keyFor(config)); assertEquals(2, map.gets, "Old close cannot revoke or clear the new scope")
            config.configHash++
            assertNull(stock.keyFor(config), "The ordinary path still detects and permanently records registry mutation")
            config.configHash--
            val before = map.gets
            assertFailsWith<IllegalStateException> { next.keyFor(config) }
            assertEquals(before, map.gets)
        } finally { next.close() }
        assertFailsWith<IllegalStateException> { stock.openNativeSightLookupOrNull() }
    }

    @Test fun `nonnull type contract is unchanged and a null argument cannot poison a warm slot`() = withCatalog {
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            assertEquals("tank", scope.keyFor(config))
            val method = scope.javaClass.getDeclaredMethod("keyFor", UnitType::class.java)
            val failure = assertFailsWith<InvocationTargetException> { method.invoke(scope, *arrayOfNulls<Any>(1)) }
            assertIs<NullPointerException>(failure.cause)
            assertEquals("tank", scope.keyFor(config)); assertEquals(1, map.gets)
        } finally { scope.close() }
    }
}
