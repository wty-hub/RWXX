package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.ModInfo
import kotlin.test.*

/** View aliases and action production share the full frozen stock catalog; no local three-type path. */
class EngineStockTankTest {
    private class Fixture(val engine: GameLogic) {
        fun stock(native: UnitTypeEnum): CustomUnitConfig {
            val (name, path) = when (native) {
                UnitTypeEnum.tank -> "c_tank" to "units/tanks/tank.ini"
                UnitTypeEnum.turret -> "c_turret_t1" to "units/turrets/turret_t1.ini"
                UnitTypeEnum.extractor -> "extractorT1" to "units/extractor/extractor.ini"
                else -> error("Unexpected fixture type")
            }
            return CustomUnitConfig().apply { this.name = name; configPath = "assets/$path" }
        }
        fun register(native: UnitTypeEnum, config: CustomUnitConfig) {
            CustomUnitConfig.activeConfigs.add(config)
            CustomUnitConfig.unitTypeOverrides[native] = config
        }
        fun projector(catalog: StockUnitCatalog? = StockUnitCatalog.capture(engine)) =
            EngineViewProjector(engine, GameTeam(0, false), frozenStockCatalog = catalog)
        fun name(projector: EngineViewProjector, type: UnitType): String? =
            EngineViewProjector::class.java.getDeclaredMethod("standardTypeName", UnitType::class.java)
                .apply { isAccessible = true }.invoke(projector, type) as String?
    }

    private fun withRegistry(block: Fixture.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null)
        GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        val previousConfigs = CustomUnitConfig.activeConfigs
        val previousModId = ModInfo.nextId
        val previousMusicCount = ModInfo.musicCount
        CustomUnitConfig.unitTypeOverrides.clear()
        CustomUnitConfig.activeConfigs = arrayListOf()
        try { Fixture(engine).block() } finally {
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            CustomUnitConfig.activeConfigs = previousConfigs
            ModInfo.nextId = previousModId
            ModInfo.musicCount = previousMusicCount
            instance.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    @Test fun `native fixture has no authority to disclose custom replacements`() = withRegistry {
        val config = stock(UnitTypeEnum.tank)
        register(UnitTypeEnum.tank, config)
        val projector = projector(null)
        assertEquals("tank", name(projector, UnitTypeEnum.tank))
        assertEquals("builder", name(projector, UnitTypeEnum.builder))
        assertNull(name(projector, config))
    }

    @Test fun `registered stock aliases resolve through the captured production catalog`() = withRegistry {
        val natives = listOf(UnitTypeEnum.tank, UnitTypeEnum.turret, UnitTypeEnum.extractor)
        val configs = natives.associateWith { stock(it).also { config -> register(it, config) } }
        val catalog = StockUnitCatalog.capture(engine)
        val projector = projector(catalog)
        for ((native, config) in configs) {
            assertSame(config, catalog.typeFor(native.name))
            assertEquals(native.name, name(projector, config))
            assertNull(name(projector, stock(native)), "Matching metadata cannot authorize a different object")
        }
    }

    @Test fun `registry replacement permanently revokes a previously captured alias`() = withRegistry {
        val original = stock(UnitTypeEnum.tank)
        register(UnitTypeEnum.tank, original)
        val projector = projector()
        assertEquals("tank", name(projector, original))
        CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = stock(UnitTypeEnum.tank)
        assertNull(name(projector, original))
        CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = original
        assertNull(name(projector, original), "Restoring a changed registry cannot revive its authority")
    }

    @Test fun `mod name path and replacement spoofing cannot create a frozen catalog`() = withRegistry {
        val config = stock(UnitTypeEnum.tank)
        register(UnitTypeEnum.tank, config)
        config.modInfo = ModInfo()
        assertFailsWith<IllegalStateException> { projector() }
        config.modInfo = null
        config.name = "custom_tank"
        assertFailsWith<IllegalStateException> { projector() }
        config.name = "c_tank"
        for (path in listOf<String?>(null, "", "assets/units/tanks/tank.ini.bak", "mods/tank.ini", "assets/../units/tanks/tank.ini")) {
            config.configPath = path
            assertFailsWith<IllegalStateException>("Unexpected path: $path") { projector() }
        }
        config.configPath = "assets/units/tanks/tank.ini"
        CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = UnitTypeEnum.builder
        assertFailsWith<IllegalStateException> { projector() }
    }

    @Test fun `packaged path separators are accepted without trusting the decompiled shadow field`() = withRegistry {
        val config = stock(UnitTypeEnum.tank).apply {
            configPath = "C:\\RWXX\\assets\\units\\tanks\\tank.ini"
            overrideAndReplace = "tank_shadow.png"
        }
        register(UnitTypeEnum.tank, config)
        assertEquals("tank", name(projector(), config))
    }
}
