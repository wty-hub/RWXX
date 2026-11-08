package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.UnitStats
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.ModInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.*

class StockUnitCatalogTest {
    private class PoisonCondition : LogicBoolean() {
        var reads = 0
        override fun read(unit: OrderableUnit): Boolean {
            reads++
            error("A unit visibility predicate must never be evaluated")
        }
        override fun getMatchFailReasonForPlayer(unit: OrderableUnit): String =
            error("A unit visibility explanation must never be evaluated")
    }

    private class Fixture(val engine: GameLogic) {
        fun config(spec: StockUnitManifest.Spec) = CustomUnitConfig().apply {
            name = spec.name
            configPath = "assets/${spec.path}"
            sourceFilePath = configPath
            resourceLoadPath = configPath
            configHash = spec.name.hashCode()
        }

        fun load(spec: StockUnitManifest.Spec): CustomUnitConfig = config(spec).also {
            CustomUnitConfig.activeConfigs.add(it)
            spec.replaces?.let { native -> CustomUnitConfig.unitTypeOverrides[native] = it }
        }

        fun loadAll(): Map<String, CustomUnitConfig> = StockUnitManifest.bundled.associate { it.name to load(it) }
        fun capture() = StockUnitCatalog.capture(engine)
    }

    private fun withRegistry(block: Fixture.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null)
        GameEngine.isNonAndroidVersion = true
        // LogicBoolean's static defaults require an engine even when this class runs in isolation.
        val engine = GameLogic()
        val previousRegistry = CustomUnitConfig.activeConfigs
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        val previousModId = ModInfo.nextId
        val previousMusicCount = ModInfo.musicCount
        CustomUnitConfig.activeConfigs = arrayListOf()
        CustomUnitConfig.unitTypeOverrides.clear()
        try {
            Fixture(engine).block()
        } finally {
            CustomUnitConfig.activeConfigs = previousRegistry
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            ModInfo.nextId = previousModId
            ModInfo.musicCount = previousMusicCount
            instance.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    @Test fun `manifest exactly covers repository loadable bundled configs and explicit exclusions`() {
        val root = listOf(Path.of("assets/units"), Path.of("../assets/units"))
            .firstOrNull { Files.isDirectory(it) } ?: error("Bundled unit sources not found")
        val loaded = linkedMapOf<String, Pair<String, String?>>()
        val unloaded = mutableSetOf<String>()
        Files.walk(root).use { files ->
            files.filter { it.toString().endsWith(".ini") }.forEach { file ->
                var section = ""
                val core = linkedMapOf<String, String>()
                for (raw in Files.readAllLines(file)) {
                    val line = raw.trim()
                    if (line.startsWith("[")) section = line.removePrefix("[").removeSuffix("]")
                    if (section != "core" || line.startsWith("#") || ':' !in line) continue
                    core[line.substringBefore(':').trim()] = line.substringAfter(':').trim()
                }
                val path = "units/" + root.relativize(file).toString().replace('\\', '/')
                if (core["dont_load"].equals("true", ignoreCase = true)) unloaded += path
                else loaded[path] = checkNotNull(core["name"]) to core["overrideAndReplace"]?.takeUnless { it == "NONE" }
            }
        }
        assertEquals(StockUnitManifest.unloadedPaths, unloaded)
        assertEquals(131, loaded.size + unloaded.size)
        val expected = (StockUnitManifest.bundled + StockUnitManifest.recoveryPlaceholder)
            .associate { it.path to (it.name to it.replaces?.name) }
        assertEquals(expected, loaded, "Any new, renamed or removed bundled unit needs explicit manifest review")
        assertEquals(120, StockUnitManifest.bundled.size)
        assertEquals(18, StockUnitManifest.bundled.count { it.replaces != null })
    }

    @Test fun `full stock roster resolves all native replacements and non native transformation nodes`() = withRegistry {
        val loaded = loadAll()
        val recovery = load(StockUnitManifest.recoveryPlaceholder)
        val catalog = capture()
        for (native in UnitTypeEnum.values()) {
            if (native in StockUnitManifest.excludedNative) {
                assertNull(catalog.keyFor(native))
                assertNull(catalog.typeFor(native.name))
            } else {
                assertEquals(native.name, catalog.keyFor(native))
                assertSame(CustomUnitConfig.c(native) ?: native, catalog.typeFor(native.name))
            }
        }
        for (spec in StockUnitManifest.bundled) {
            assertEquals(spec.key, catalog.keyFor(loaded.getValue(spec.name)))
            assertSame(loaded.getValue(spec.name), catalog.typeFor(spec.key))
        }
        assertNull(catalog.keyFor(recovery))
        assertNull(catalog.typeFor("missing"))
        assertNull(catalog.typeFor("c_tank"), "The wire key is tank, never a parser alias")
        assertEquals(catalog.entries.map { it.key }.sorted(), catalog.entries.map { it.key })
        assertEquals(catalog.entries.size, catalog.entries.map { it.key }.toSet().size)
        for (key in listOf("extractorT3_reinforced", "c_turret_t2_lightning", "c_antiAirTurretT3",
            "mechBunkerDeployed", "c_amphibiousJet_transition", "modularSpider_speedIncomplete", "flare_10s")) {
            assertNotNull(catalog.typeFor(key), "Real transformed, attached and temporary stock nodes must survive")
        }
        catalog.assertValid()
    }

    @Test fun `native only registry is valid without broad custom unit authorization`() = withRegistry {
        val catalog = capture()
        assertSame(UnitTypeEnum.tank, catalog.typeFor("tank"))
        assertEquals("builder", catalog.keyFor(UnitTypeEnum.builder))
        assertNull(catalog.keyFor(config(StockUnitManifest.bundled.first())))
    }

    @Test fun `same name and path in an unregistered instance never grants authority`() = withRegistry {
        val spec = StockUnitManifest.bundled.first { it.replaces == UnitTypeEnum.tank }
        val registered = load(spec)
        val catalog = capture()
        assertEquals("tank", catalog.keyFor(registered))
        assertNull(catalog.keyFor(config(spec)))
        assertSame(registered, catalog.typeFor("tank"))
    }

    @Test fun `capture rejects unknown mods wrong paths duplicate names and unregistered replacements`() = withRegistry {
        val spec = StockUnitManifest.bundled.first { it.name == "mechFactory" }
        val candidate = load(spec)
        candidate.modInfo = ModInfo()
        assertFailsWith<IllegalStateException> { capture() }
        candidate.modInfo = null
        candidate.name = "not_in_the_closed_manifest"
        assertFailsWith<IllegalStateException> { capture() }
        candidate.name = spec.name
        for (path in listOf<String?>(null, "", "assets/${spec.path}.bak", "assets/../${spec.path}", "mods/fake.ini")) {
            candidate.configPath = path
            assertFailsWith<IllegalStateException>("Bad source: $path") { capture() }
        }
        candidate.configPath = "C:\\RWXX\\assets\\${spec.path.replace('/', '\\')}"
        capture().assertValid()
        CustomUnitConfig.activeConfigs.add(config(spec))
        assertFailsWith<IllegalStateException> { capture() }
        CustomUnitConfig.activeConfigs.removeAt(1)
        val tank = StockUnitManifest.bundled.first { it.replaces == UnitTypeEnum.tank }
        CustomUnitConfig.activeConfigs.add(config(tank))
        assertFailsWith<IllegalStateException> { capture() }
    }

    @Test fun `registry replacement revokes old objects and cannot be repaired by restoring the old value`() = withRegistry {
        val spec = StockUnitManifest.bundled.first { it.replaces == UnitTypeEnum.tank }
        val registered = load(spec)
        val catalog = capture()
        CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = config(spec)
        assertNull(catalog.typeFor("tank"))
        assertNull(catalog.keyFor(registered))
        CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = registered
        assertFailsWith<IllegalStateException> { catalog.assertValid() }
        assertNull(catalog.typeFor("tank"))
    }

    @Test fun `previously unreplaced native lookup detects a same size override move and stays revoked`() = withRegistry {
        val registered = load(StockUnitManifest.bundled.first { it.replaces == UnitTypeEnum.tank })
        val lookups: List<Pair<String, (StockUnitCatalog) -> Any?>> = listOf(
            "native alias" to { catalog -> catalog.keyFor(UnitTypeEnum.builder) },
            "public key" to { catalog -> catalog.typeFor("builder") },
        )
        for ((label, lookup) in lookups) {
            val catalog = capture()
            assertNotNull(lookup(catalog), label)
            val originalSize = CustomUnitConfig.unitTypeOverrides.size
            CustomUnitConfig.unitTypeOverrides.remove(UnitTypeEnum.tank)
            CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.builder] = registered
            assertEquals(originalSize, CustomUnitConfig.unitTypeOverrides.size,
                "The registry shape guard alone cannot detect a moved override")
            assertNull(lookup(catalog), "$label must check its actual previously empty override slot")
            CustomUnitConfig.unitTypeOverrides.remove(UnitTypeEnum.builder)
            CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = registered
            assertNull(catalog.keyFor(UnitTypeEnum.builder), "Restoring the frozen slots cannot revive $label")
            assertNull(catalog.typeFor("tank"), "Invalidation remains catalog-wide")
            assertFailsWith<IllegalStateException> { catalog.assertValid() }
        }
    }

    @Test fun `capture rejects dynamic and constant unit visibility predicates without evaluating them`() = withRegistry {
        val candidate = load(StockUnitManifest.bundled.first { it.name == "mechFactory" })
        val poison = PoisonCondition()
        for (condition in listOf(poison, LogicBoolean.trueBoolean, LogicBoolean.falseBoolean)) {
            candidate.isVisible = condition
            assertFailsWith<IllegalStateException> { capture() }
            assertEquals(0, poison.reads)
        }
        candidate.isVisible = null
        capture().assertValid()
    }

    @Test fun `injected unit visibility predicate revokes native alias lookup permanently without reads`() = withRegistry {
        val candidate = load(StockUnitManifest.bundled.first { it.replaces == UnitTypeEnum.tank })
        val catalog = capture()
        val poison = PoisonCondition()
        candidate.isVisible = poison
        assertNull(catalog.keyFor(UnitTypeEnum.tank))
        assertEquals(0, poison.reads)
        candidate.isVisible = null
        assertNull(catalog.keyFor(candidate))
        assertNull(catalog.typeFor("tank"))
        assertFailsWith<IllegalStateException> { catalog.assertValid() }
        assertEquals(0, poison.reads)
    }

    @Test fun `owner boundary rejects injected unit visibility even before a related lookup`() = withRegistry {
        val candidate = load(StockUnitManifest.bundled.first { it.name == "mechFactory" })
        val catalog = capture()
        val poison = PoisonCondition()
        candidate.isVisible = poison
        assertFailsWith<IllegalStateException> { catalog.assertValid() }
        candidate.isVisible = null
        assertNull(catalog.typeFor("builder"))
        assertFailsWith<IllegalStateException> { catalog.assertValid() }
        assertEquals(0, poison.reads)
    }

    @Test fun `enemy visibility boolean accepts either frozen value and changes permanently revoke lookup`() = withRegistry {
        val spec = StockUnitManifest.bundled.first { it.name == "mechFactory" }
        val candidate = load(spec)
        for (initial in listOf(false, true)) {
            candidate.unitStats.isVisibleToEnemies = initial
            val catalog = capture()
            assertSame(candidate, catalog.typeFor(spec.key))
            candidate.unitStats.isVisibleToEnemies = !initial
            assertNull(catalog.typeFor(spec.key))
            candidate.unitStats.isVisibleToEnemies = initial
            assertNull(catalog.keyFor(candidate))
            assertFailsWith<IllegalStateException> { catalog.assertValid() }
        }
    }

    @Test fun `owner boundary freezes enemy visibility value and statistics identity`() = withRegistry {
        val candidate = load(StockUnitManifest.bundled.first { it.name == "mechFactory" })
        val originalStats = candidate.unitStats
        val initial = originalStats.isVisibleToEnemies
        val boolCatalog = capture()
        originalStats.isVisibleToEnemies = !initial
        assertFailsWith<IllegalStateException> { boolCatalog.assertValid() }
        originalStats.isVisibleToEnemies = initial
        assertFailsWith<IllegalStateException> { boolCatalog.assertValid() }

        val identityCatalog = capture()
        candidate.unitStats = UnitStats(true).apply { isVisibleToEnemies = initial }
        assertNull(identityCatalog.keyFor(candidate))
        candidate.unitStats = originalStats
        assertFailsWith<IllegalStateException> { identityCatalog.assertValid() }
    }

    @Test fun `same list slot replaced with identical metadata revokes non replacement configuration`() = withRegistry {
        val spec = StockUnitManifest.bundled.first { it.name == "mechFactory" }
        val original = load(spec)
        val catalog = capture()
        CustomUnitConfig.activeConfigs[0] = config(spec)
        assertNull(catalog.keyFor(original))
        assertNull(catalog.typeFor(spec.key))
        assertFailsWith<IllegalStateException> { catalog.assertValid() }
    }

    @Test fun `full boundary validation detects unrelated slot and metadata changes`() = withRegistry {
        val configs = loadAll()
        val catalog = capture()
        configs.getValue("mechFactory").configHash++
        assertFailsWith<IllegalStateException> { catalog.assertValid() }
        assertNull(catalog.typeFor("builder"))
    }

    @Test fun `whole registry replacement and late capture fail closed`() = withRegistry {
        loadAll()
        val catalog = capture()
        CustomUnitConfig.activeConfigs = ArrayList(CustomUnitConfig.activeConfigs)
        assertNull(catalog.typeFor("builder"))
        assertFailsWith<IllegalStateException> { catalog.assertValid() }
        engine.currentTick = 1
        assertFailsWith<IllegalStateException> { capture() }
    }

    @Test fun `only the capture thread may read the engine catalog`() = withRegistry {
        val catalog = capture()
        var result: Throwable? = null
        thread { result = runCatching { catalog.typeFor("builder") }.exceptionOrNull() }.join()
        assertIs<IllegalStateException>(result)
    }
}
