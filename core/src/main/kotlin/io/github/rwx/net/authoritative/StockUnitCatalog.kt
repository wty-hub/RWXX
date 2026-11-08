package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.UnitStats
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.gameFramework.GameEngine
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Frozen server-side identities for bundled content, captured before the first simulation tick.
 * This is a type authorization table, not permission to expose an entity or invoke all its actions.
 * Call [assertValid] once at each owner-thread update boundary; individual lookups also check their
 * relevant registry slots in O(1), without scanning the full registry for every visible unit.
 */
class StockUnitCatalog private constructor(
    private val engine: GameEngine,
    private val ownerThread: Thread,
    private val activeRegistry: List<CustomUnitConfig>,
    private val configs: List<FrozenConfig>,
    private val overrides: Map<UnitType, UnitType>,
    private val frozenEntries: List<Entry>,
    private val byType: IdentityHashMap<UnitType, Lookup>,
    private val byKey: Map<String, Lookup>,
) {
    data class Entry(val key: String, val type: UnitType, val name: String, val path: String?)

    // Only immutable associations are captured here. Every lookup still reads the actual
    // override slot and every FrozenConfig field; no successful validation is remembered.
    private class Lookup(val entry: Entry, val native: UnitTypeEnum?, val replacement: UnitType?,
                         val config: FrozenConfig?)

    private data class FrozenConfig(
        val config: CustomUnitConfig,
        val spec: StockUnitManifest.Spec,
        val index: Int,
        val pathAtCapture: String?,
        val sourcePathAtCapture: String?,
        val resourcePathAtCapture: String?,
        val configHashAtCapture: Int,
        val visibilityAtCapture: LogicBoolean?,
        val unitStatsAtCapture: UnitStats,
        val visibleToEnemiesAtCapture: Boolean,
    ) {
        fun isCurrent(registry: List<CustomUnitConfig>): Boolean =
            registry.getOrNull(index) === config && config.modInfo == null && config.name == spec.name &&
                config.configPath == pathAtCapture && config.sourceFilePath == sourcePathAtCapture &&
                config.resourceLoadPath == resourcePathAtCapture && config.configHash == configHashAtCapture &&
                config.isVisible === visibilityAtCapture && config.unitStats === unitStatsAtCapture &&
                unitStatsAtCapture.isVisibleToEnemies == visibleToEnemiesAtCapture
    }

    private var invalidated = false
    private var activeSightLookup: NativeSightLookup? = null

    /** Receiver shape only; does not validate, iterate, revoke, or call arbitrary size/get. */
    internal fun hasKnownValidationBackings(): Boolean {
        checkOwner()
        return !invalidated && activeRegistry.javaClass === java.util.ArrayList::class.java &&
            CustomUnitConfig.activeConfigs?.javaClass === java.util.ArrayList::class.java &&
            CustomUnitConfig.unitTypeOverrides?.javaClass === java.util.HashMap::class.java
    }

    /**
     * A lookup for one callback-free native sight loop, after its world-list flush and exact-class
     * prescan. It remembers no validation across captures. Unknown collection implementations
     * must use the ordinary per-entry path: even their size/get methods can run mod callbacks.
     */
    internal fun openNativeSightLookupOrNull(): NativeSightLookup? {
        checkOwner()
        if (activeSightLookup != null || activeRegistry.javaClass !== java.util.ArrayList::class.java ||
            CustomUnitConfig.activeConfigs?.javaClass !== java.util.ArrayList::class.java ||
            CustomUnitConfig.unitTypeOverrides.javaClass !== java.util.HashMap::class.java) return null
        // This can reject an altered, currently uninstantiated config earlier than per-unit lookup.
        // The stronger fail-closed boundary is intentional; no dynamic getter runs after it.
        assertValid()
        return NativeSightLookup().also { activeSightLookup = it }
    }

    internal inner class NativeSightLookup internal constructor() : AutoCloseable {
        // One immutable identity association, only for this validated callback-free scope.
        // The explicit flag distinguishes an unknown type's cached null result from no lookup.
        private var hasCachedType = false
        private var cachedType: UnitType? = null
        private var cachedEntry: Lookup? = null

        fun keyFor(type: UnitType): String? {
            checkOwner()
            check(activeSightLookup === this && !invalidated) { "Native sight lookup is no longer active" }
            // byType is a private immutable identity association, including native replacement aliases.
            // Only this validated, callback-free window may omit reading mutable registry slots.
            if (hasCachedType && cachedType === type) return cachedEntry?.entry?.key
            val entry = byType[type]
            cachedType = type
            cachedEntry = entry
            hasCachedType = true
            return entry?.entry?.key
        }

        /**
         * Opaque catalog association for local synthetic-tank shape capture. No caller supplies a
         * config/name as authority. This does not qualify closure/action callbacks or the world.
         */
        internal fun nativeTankIdentityFor(catalog: StockUnitCatalog): CustomUnitConfig? {
            checkOwner()
            if (catalog !== this@StockUnitCatalog || activeSightLookup !== this || invalidated) return null
            val config = byType[UnitTypeEnum.tank]?.entry?.type as? CustomUnitConfig ?: return null
            return config.takeIf { it.javaClass === CustomUnitConfig::class.java }
        }

        override fun close() {
            checkOwner()
            if (activeSightLookup === this) activeSightLookup = null
            hasCachedType = false
            cachedType = null
            cachedEntry = null
        }
    }

    /** Unique public keys, with the effective build/produce type after audited stock replacement. */
    val entries: List<Entry>
        get() {
            assertValid()
            return frozenEntries
        }

    fun keyFor(type: UnitType): String? {
        checkOwner()
        if (!registryShapeIsCurrent()) return invalidate()
        val entry = byType[type] ?: return null
        if (!entryIsCurrent(entry)) return invalidate()
        return entry.entry.key
    }

    fun typeFor(key: String): UnitType? {
        checkOwner()
        if (!registryShapeIsCurrent()) return invalidate()
        val entry = byKey[key] ?: return null
        if (!entryIsCurrent(entry)) return invalidate()
        return entry.entry.type
    }

    /** Any registry replacement, reordering, metadata change or newly active mod invalidates the room. */
    fun assertValid() {
        checkOwner()
        val current = registryShapeIsCurrent() && configs.all { it.isCurrent(activeRegistry) } &&
            overrides.all { (type, replacement) -> CustomUnitConfig.unitTypeOverrides[type] === replacement }
        if (!current) invalidated = true
        check(!invalidated) { "Standard unit registry changed after authoritative capture" }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === ownerThread) { "Stock unit catalog requires the engine owner thread" }
        check(GameEngine.getInstance() === engine) { "Stock unit catalog belongs to another engine" }
    }

    private fun registryShapeIsCurrent(): Boolean = !invalidated &&
        CustomUnitConfig.activeConfigs === activeRegistry && activeRegistry.size == configs.size &&
        CustomUnitConfig.unitTypeOverrides.size == overrides.size

    private fun entryIsCurrent(lookup: Lookup): Boolean {
        val native = lookup.native
        if (native != null && CustomUnitConfig.unitTypeOverrides[native] !== lookup.replacement) return false
        return lookup.config?.isCurrent(activeRegistry) ?: (lookup.entry.type !is CustomUnitConfig)
    }

    private fun <T> invalidate(): T? {
        invalidated = true
        return null
    }

    companion object {
        private val NATIVE_BY_KEY = UnitTypeEnum.values().asList()
            .filterNot { it in StockUnitManifest.excludedNative }.associateBy { it.name }

        /** No runtime INI parsing, global name resolver, action interning or dynamic unit getters. */
        fun capture(engine: GameEngine): StockUnitCatalog {
            check(GameEngine.getInstance() === engine) { "Stock unit capture belongs to another engine" }
            check(engine.currentTick == 0) { "Capture standard units before the first simulation tick" }
            val registry = checkNotNull(CustomUnitConfig.activeConfigs) { "Standard unit registry is not ready" }
            val allowed = (StockUnitManifest.bundled + StockUnitManifest.recoveryPlaceholder).associateBy { it.name }
            val byConfig = IdentityHashMap<CustomUnitConfig, FrozenConfig>()
            val names = HashSet<String>()
            val configs = registry.mapIndexed { index, config ->
                val spec = allowed[config.name]
                check(spec != null && config.modInfo == null && matchesPath(config.configPath, spec.path)) {
                    "Unsupported active unit configuration in authoritative room"
                }
                check(names.add(spec.name) && !byConfig.containsKey(config)) { "Duplicate standard unit configuration" }
                // All audited bundled units omit graphics.isVisible. Never evaluate a new predicate,
                // including a constant one: its presence requires a separate content/privacy review.
                check(config.isVisible == null) { "Unit visibility predicates are not supported in authoritative rooms" }
                val stats = checkNotNull(config.unitStats) { "Standard unit statistics are not ready" }
                FrozenConfig(config, spec, index, config.configPath, config.sourceFilePath,
                    config.resourceLoadPath, config.configHash, config.isVisible, stats,
                    stats.isVisibleToEnemies).also { byConfig[config] = it }
            }
            val overrides = HashMap(CustomUnitConfig.unitTypeOverrides)
            for ((native, replacement) in overrides) {
                val frozen = (replacement as? CustomUnitConfig)?.let(byConfig::get)
                check(native is UnitTypeEnum && native in NATIVE_BY_KEY.values && frozen?.spec?.replaces === native) {
                    "Unsupported unit replacement in authoritative room"
                }
            }
            for (frozen in configs) frozen.spec.replaces?.let { native ->
                check(overrides[native] === frozen.config) { "Stock replacement is not registered to its native type" }
            }

            val byType = IdentityHashMap<UnitType, Lookup>()
            val byKey = LinkedHashMap<String, Lookup>()
            for ((key, native) in NATIVE_BY_KEY) {
                val effective = overrides[native] ?: native
                val spec = (effective as? CustomUnitConfig)?.let { byConfig[it]?.spec }
                val entry = Entry(key, effective, spec?.name ?: native.name, spec?.path)
                val lookup = Lookup(entry, native, overrides[native], (effective as? CustomUnitConfig)?.let(byConfig::get))
                byKey[key] = lookup
                // A native object remains an audited native type even when normal production is replaced.
                byType[native] = lookup
                byType[effective] = lookup
            }
            for (frozen in configs) {
                val spec = frozen.spec
                if (spec === StockUnitManifest.recoveryPlaceholder || spec.replaces != null) continue
                check(spec.key !in byKey) { "Standard unit public key collision" }
                Entry(spec.key, frozen.config, spec.name, spec.path).also {
                    val lookup = Lookup(it, null, null, frozen)
                    byKey[it.key] = lookup
                    byType[it.type] = lookup
                }
            }
            val entries = Collections.unmodifiableList(byKey.values.map { it.entry }.sortedBy { it.key })
            return StockUnitCatalog(engine, Thread.currentThread(), registry, configs, overrides,
                entries, byType, Collections.unmodifiableMap(byKey)).also { it.assertValid() }
        }

        private fun matchesPath(path: String?, expected: String): Boolean {
            val normalized = path?.replace('\\', '/') ?: return false
            if (normalized.split('/').any { it == "." || it == ".." } || '\u0000' in normalized) return false
            return normalized == expected || normalized.endsWith("/$expected")
        }
    }
}
