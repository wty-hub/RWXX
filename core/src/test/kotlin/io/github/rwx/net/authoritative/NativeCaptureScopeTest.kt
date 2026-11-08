package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.custom.hooks.AttachmentSlotDefinition
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.buildings.CommandCenter
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.UnitStats
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.timeline.EngineTimelineProjector
import io.github.rwx.net.timeline.TimelineCanonical
import java.lang.reflect.InvocationTargetException
import kotlin.test.*

/** Native constructors/exact classes exercise the optimized path; subclasses explicitly test fallback. */
class NativeCaptureScopeTest {
    private class CallbackBuilder : BuilderUnit(true) {
        var onType: (() -> Unit)? = null
        override fun r(): UnitTypeEnum { onType?.invoke(); return UnitTypeEnum.builder }
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> = arrayListOf()
    }

    private class CallbackCustom(config: CustomUnitConfig) : CustomUnit(true, config) {
        var onType: (() -> Unit)? = null
        var onUnselectable: (() -> Boolean)? = null
        var onUpgrade: (() -> Int)? = null
        var onVisibility: (() -> Boolean)? = null
        var onControl: (() -> Boolean)? = null
        override fun r(): CustomUnitConfig? { onType?.invoke(); return super.r() }
        override fun t(): Boolean = onUnselectable?.invoke() ?: super.t()
        override fun getUpgradeLevel(): Int = onUpgrade?.invoke() ?: super.getUpgradeLevel()
        override fun isVisibleToEnemies(): Boolean = onVisibility?.invoke() ?: super.isVisibleToEnemies()
        override fun canNotBeGivenOrdersByPlayer(): Boolean = onControl?.invoke() ?: super.canNotBeGivenOrdersByPlayer()
    }

    private class World(val engine: GameLogic, val own: GameTeam, val enemy: GameTeam,
                        val config: CustomUnitConfig) {
        val map get() = engine.tileMap
        fun <T : BaseUnit> add(unit: T, team: PlayerTeam = own, x: Float = 10f, y: Float = 10f): T = unit.apply {
            this.team = team; posX = x; posY = y
            BaseUnit.getGlobalUnitList().add(this)
        }
        fun stock() = StockUnitCatalog.capture(engine)
        fun projector(stock: StockUnitCatalog) = EngineViewProjector(engine, own, frozenStockCatalog = stock)
    }

    private fun withWorld(block: World.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val teamRegistry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null)
        GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        val previousTeams = teamRegistry.get(null)
        val previousWorld = BaseUnit.getGlobalUnitList().toList()
        val previousRegistry = CustomUnitConfig.activeConfigs
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        BaseUnit.getGlobalUnitList().clear()
        CustomUnitConfig.activeConfigs = arrayListOf()
        CustomUnitConfig.unitTypeOverrides.clear()
        teamRegistry.set(null, arrayOfNulls<PlayerTeam>((previousTeams as Array<*>).size))
        try {
            engine.networkEngine = NetworkEngine()
            engine.networkEngine.roomSettings.fogMode = 2
            engine.networkEngine.roomSettings.revealedMap = false
            engine.commandController = CommandController()
            engine.replayEngine = ReplayEngine()
            engine.tileMap = PublicMapFixture.flat(64, 48).apply {
                fogEnabled = true; fogPeriodicMaintenanceEnabled = true
            }
            val own = GameTeam(0).apply {
                teamColorId = 0; credits = 1000.0
                fogOfWarData = Array(64) { ByteArray(48) { 10 } }
            }
            val enemy = GameTeam(1).apply { teamColorId = 1 }
            engine.playerTeam = own
            val spec = StockUnitManifest.bundled.single { it.replaces === UnitTypeEnum.tank }
            val config = CustomUnitConfig().apply {
                name = spec.name; configPath = "assets/${spec.path}"
                sourceFilePath = configPath; resourceLoadPath = configPath
                turrets = emptyArray(); frameWidth = 20; frameHeight = 20; techLevel = 1
                unitStats.maxHp = 100; unitStats.fogOfWarSightRange = 2
                unitStats.isVisibleToEnemies = true
                fogOfWarSightRangeWhileNotBuilt = -1
            }
            CustomUnitConfig.activeConfigs.add(config)
            CustomUnitConfig.unitTypeOverrides[UnitTypeEnum.tank] = config
            World(engine, own, enemy, config).block()
        } finally {
            BaseUnit.getGlobalUnitList().clear()
            previousWorld.forEach { BaseUnit.getGlobalUnitList().add(it) }
            CustomUnitConfig.activeConfigs = previousRegistry
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            teamRegistry.set(null, previousTeams)
            instance.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    @Test fun `exact native public fields match fallback real v7 bytes across same tick ownership and visibility changes`() = withWorld {
        val scout = add(CustomUnit(true, config))
        val target = add(CustomUnit(true, config), enemy, 30f, 10f)
        val center = add(CommandCenter(true), own, 1210f, 910f)
        add(BuilderUnit(true), own, 1110f, 910f)
        val sentinel = CallbackBuilder().apply { team = enemy; isDead = true }
        val stock = stock()
        val closure = StockActionClosure.capture(engine, stock)
        val publicMap = EnginePublicMapSnapshot.capture(map)
        fun stream() = EngineTimelineTestStream(EngineTimelineProjector(engine, own, publicMap, stock, closure,
            { MatchStatus(MatchPhase.RUNNING) }), engineTimelineTestContext(engine, publicMap))
        val optimized = stream()
        val fallback = stream()
        val direct = projector(stock)
        val cases: List<Pair<String, () -> Unit>> = listOf(
            "visible enemy" to {},
            "hidden enemy" to { target.y.isVisibleToEnemies = false },
            "allied visibility exception" to { enemy.teamColorId = own.teamColorId; target.y.isVisibleToEnemies = false },
            "neutral visibility exception" to { target.team = PlayerTeam.TEAM_ALL; target.y.isVisibleToEnemies = false },
            "tech and control" to { config.techLevel = 2; config.canNotBeGivenOrdersByPlayer = true },
            "unselectable" to { config.isUnselectable = true },
            "attachment still excluded" to { target.attachmentData = AttachmentSlotDefinition().apply { m = false } },
            "cargo still excluded" to { target.transportContainer = scout },
            "nonfinite health still excluded" to { target.currentHealth = Float.NaN },
            "invalid tech still excluded" to { config.techLevel = 4 },
        )
        // No tick-key cache may hide changes made between owner calls at this exact same tick.
        engine.currentTick = 1
        for ((round, case) in cases.withIndex()) {
            target.team = enemy; enemy.teamColorId = 1
            target.y = UnitStats(false).apply { isVisibleToEnemies = true; fogOfWarSightRange = 2 }
            target.currentHealth = 100f; target.transportContainer = null; target.attachmentData = null
            config.techLevel = 1; config.canNotBeGivenOrdersByPlayer = false; config.isUnselectable = false
            scout.currentHealth = 100f - round
            center.currentHealth = 4000f - round
            case.second()
            val actual = optimized.capture(round * 100_000_000L)
            BaseUnit.getGlobalUnitList().add(sentinel)
            val expected = try { fallback.capture(round * 100_000_000L) }
                finally { BaseUnit.getGlobalUnitList().remove(sentinel) }
            assertContentEquals(expected.wire, actual.wire, case.first)
            assertContentEquals(expected.canonical, actual.canonical, case.first)
            assertEquals(TimelineCanonical.digest(expected.state), TimelineCanonical.digest(actual.state), case.first)
            val current = direct.snapshot()
            when (round) {
                0 -> assertEquals(4, current.units.size)
                1, 6, 7, 8 -> assertEquals(3, current.units.size)
                2, 3 -> assertEquals(4, current.units.size)
                4 -> assertTrue(current.units.filter { it.typeName == "tank" }.all { !it.controllable && it.upgradeLevel == 2 })
                5, 9 -> assertEquals(2, current.units.size)
            }
            assertEquals(1, engine.currentTick)
        }
    }

    @Test fun `unknown disclosure getters preserve fallback invalidation even when a later getter restores metadata`() {
        for (getter in listOf("t", "upgrade", "visibility", "control")) withWorld {
            val first = add(CallbackCustom(config), if (getter == "visibility") enemy else own)
            add(CustomUnit(true, config))
            val last = add(CallbackCustom(config), enemy)
            val stock = stock()
            val projector = projector(stock)
            val originalHash = config.configHash
            val calls = arrayListOf<String>()
            fun mutate() { calls += getter; config.configHash = originalHash + 1 }
            when (getter) {
                "t" -> { first.posX = -20f; first.onUnselectable = { mutate(); false } }
                "upgrade" -> first.onUpgrade = { mutate(); 4 }
                "visibility" -> first.onVisibility = { mutate(); false }
                "control" -> first.onControl = { mutate(); true }
            }
            last.onType = {
                if (config.configHash != originalHash) { calls += "restore"; config.configHash = originalHash }
            }
            projector.snapshot()
            assertEquals(listOf(getter, "restore"), calls, "Whole-world prescan must not execute $getter")
            assertEquals(originalHash, config.configHash)
            assertNull(stock.keyFor(config), "A later restoration cannot undo ordinary lookup invalidation")
            assertFailsWith<IllegalStateException> { stock.assertValid() }
        }
    }

    @Test fun `capture scope is closed before selected actor revoke callbacks and does not authorize removed handles`() = withWorld {
        val actor = add(CallbackBuilder())
        add(CommandCenter(true), own, 1210f, 910f)
        val stock = stock()
        engine.currentTick = 1
        val projector = projector(stock)
        val first = projector.snapshot()
        val handle = first.units.single { it.typeName == "builder" }.handle
        val panel = projector.actionPanel(ViewActionPanelQuery(1, first.sequence, handle))
        assertEquals(handle, panel.unitHandle)
        BaseUnit.getGlobalUnitList().remove(actor)
        actor.isDead = true
        var callbacks = 0
        actor.onType = {
            callbacks++
            // The current world contains only exact CommandCenter, so a lingering DTO scope would
            // make this nested open return null. An old selected owner is a separate callback boundary.
            assertNotNull(stock.openNativeSightLookupOrNull()).close()
        }
        val current = projector.snapshot()
        assertTrue(callbacks > 0)
        assertEquals(listOf("commandCenter"), current.units.map { it.typeName })
        assertFalse(projector.apply(ViewUnitOrder(1, first.sequence, OrderKind.STOP, listOf(handle))))
        assertTrue(projector.actionPanel(ViewActionPanelQuery(2, current.sequence, handle)).actions.isEmpty())
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `fog hidden exact native exclusions preserve real authorization bytes full hash and terminal`() = withWorld {
        val scout = add(CustomUnit(true, config))
        add(CommandCenter(true), own)
        val target = add(CustomUnit(true, config), enemy, 810f, 610f)
        val sentinel = CallbackBuilder().apply { team = enemy; isDead = true }
        val stock = stock()
        val closure = StockActionClosure.capture(engine, stock)
        val publicMap = EnginePublicMapSnapshot.capture(map)
        var status = MatchStatus(MatchPhase.RUNNING)
        fun stream() = EngineTimelineTestStream(EngineTimelineProjector(engine, own, publicMap, stock, closure,
            { status }), engineTimelineTestContext(engine, publicMap))
        val optimized = stream(); val fallback = stream()
        val cases: List<Pair<String, () -> Unit>> = listOf(
            "ordinary hidden foreign" to {},
            "NaN health" to { target.currentHealth = Float.NaN },
            "negative health" to { target.currentHealth = -1f },
            "NaN max health" to { target.maxHealth = Float.NaN },
            "zero max health" to { target.maxHealth = 0f },
            "nonfinite height" to { target.posZ = Float.POSITIVE_INFINITY },
            "nonfinite heading" to { target.rotationSpeed = Float.NaN },
            "NaN progress" to { target.buildProgress = Float.NaN },
            "invalid progress" to { target.buildProgress = 2f },
            "cargo" to { target.transportContainer = scout },
            "parented" to { target.parentEntity = scout },
            "attachment" to { target.attachmentData = AttachmentSlotDefinition().apply { m = false } },
            "dead" to { target.isDead = true },
            "unselectable" to { config.isUnselectable = true },
            "invalid tech" to { config.techLevel = 4 },
            "null team" to { target.team = null },
            "unknown native configuration identity" to { target.unitConfig = CustomUnitConfig() })
        for ((round, case) in cases.withIndex()) {
            target.team = enemy; target.unitConfig = config; target.isDead = false
            target.y = UnitStats(false).apply { isVisibleToEnemies = true; fogOfWarSightRange = 2 }
            target.currentHealth = 100f; target.maxHealth = 100f; target.posZ = 0f; target.rotationSpeed = 0f
            target.buildProgress = 1f; target.transportContainer = null; target.parentEntity = null; target.attachmentData = null
            config.techLevel = 1; config.isUnselectable = false
            case.second(); engine.currentTick = round * 6 + 1
            val actual = optimized.capture(round * 1_000_000_000L)
            BaseUnit.getGlobalUnitList().add(sentinel)
            val expected = try { fallback.capture(round * 1_000_000_000L) }
                finally { BaseUnit.getGlobalUnitList().remove(sentinel) }
            assertContentEquals(expected.wire, actual.wire, case.first)
            assertContentEquals(expected.canonical, actual.canonical, case.first)
            assertEquals(TimelineCanonical.digest(expected.state), TimelineCanonical.digest(actual.state), case.first)
            val active = actual.state.objects.values.filter {
                it.kind == io.github.rwx.net.timeline.TimelineObjectKind.UNIT && it.close == null
            }
            assertTrue(active.size <= 2, "Hidden foreign records must never be granted: ${case.first}")
        }
        target.team = enemy; target.unitConfig = config; config.isUnselectable = false; config.techLevel = 1
        status = MatchStatus(MatchPhase.FINISHED, listOf(0), listOf(1))
        engine.currentTick = cases.size * 6 + 1
        val actual = optimized.capture(cases.size * 1_000_000_000L)
        BaseUnit.getGlobalUnitList().add(sentinel)
        val expected = try { fallback.capture(cases.size * 1_000_000_000L) }
            finally { BaseUnit.getGlobalUnitList().remove(sentinel) }
        assertContentEquals(expected.wire, actual.wire, "terminal")
        assertContentEquals(expected.canonical, actual.canonical, "terminal")
        assertEquals(TimelineCanonical.digest(expected.state), TimelineCanonical.digest(actual.state))
        assertTrue(actual.state.objects.values.none {
            it.kind == io.github.rwx.net.timeline.TimelineObjectKind.UNIT && it.close == null
        })
    }

    @Test fun `hidden native null stats retains NPE or earlier exclusion exactly as fallback`() = withWorld {
        val scout = add(CustomUnit(true, config))
        add(CommandCenter(true), own)
        val target = add(CustomUnit(true, config), enemy, 810f, 610f)
        val sentinel = CallbackBuilder().apply { team = enemy; isDead = true }
        val stock = stock()
        val cases: List<Triple<String, () -> Unit, Boolean>> = listOf(
            Triple("valid before enemy stats access", {}, true),
            Triple("dead short circuit", { target.isDead = true }, false),
            Triple("NaN health short circuit", { target.currentHealth = Float.NaN }, false),
            Triple("zero max health short circuit", { target.maxHealth = 0f }, false),
            Triple("cargo short circuit", { target.transportContainer = scout }, false),
            Triple("unselectable short circuit", { config.isUnselectable = true }, false),
            Triple("allied visibility short circuit", { enemy.teamColorId = own.teamColorId }, false),
            Triple("neutral visibility short circuit", { target.team = PlayerTeam.TEAM_ALL }, false),
            Triple("own stats fails in sight", { target.team = own }, true))
        for ((label, mutate, mustThrow) in cases) {
            target.team = enemy; enemy.teamColorId = 1; target.y = null
            target.isDead = false; target.currentHealth = 100f; target.maxHealth = 100f
            target.transportContainer = null; config.isUnselectable = false
            mutate()
            val actual = runCatching { projector(stock).snapshot() }
            BaseUnit.getGlobalUnitList().add(sentinel)
            val expected = try { runCatching { projector(stock).snapshot() } }
                finally { BaseUnit.getGlobalUnitList().remove(sentinel) }
            if (mustThrow) {
                assertIs<NullPointerException>(actual.exceptionOrNull(), label)
                assertIs<NullPointerException>(expected.exceptionOrNull(), label)
            } else {
                val view = actual.getOrThrow(); val reference = expected.getOrThrow()
                assertEquals(reference.units.map { it.typeName to it.teamId }, view.units.map { it.typeName to it.teamId }, label)
                assertTrue(view.units.none { it.teamId != 0 }, label)
            }
            assertNotNull(stock.openNativeSightLookupOrNull(), "Every failure/exclusion closes its scope: $label").close()
        }
    }

    @Test fun `foreign null native configuration still throws before team sight filtering`() = withWorld {
        add(CommandCenter(true), own)
        val target = add(CustomUnit(true, config), enemy, 810f, 610f)
        val sentinel = CallbackBuilder().apply { team = enemy; isDead = true }
        val stock = stock()
        target.unitConfig = null
        try {
            assertFailsWith<NullPointerException> { projector(stock).snapshot() }
            BaseUnit.getGlobalUnitList().add(sentinel)
            try { assertFailsWith<NullPointerException> { projector(stock).snapshot() } }
            finally { BaseUnit.getGlobalUnitList().remove(sentinel) }
            assertNotNull(stock.openNativeSightLookupOrNull()).close()
        } finally { target.unitConfig = config }
    }

    @Test fun `float division crossing the fog edge defers exclusion and exception to original predicate`() = withWorld {
        val target = add(CustomUnit(true, config), enemy, 36_700_124f, 0f)
        val stock = stock(); val projector = projector(stock)
        // Raw predicate-only boundary, not a v7 MapInit fixture: v7 dimensions remain <=1024.
        map.tileCountX = 1_048_575; map.tileCountY = 1
        map.tileWorldSizeX = 35; map.tileWorldSizeY = 20
        val fog = OwnedSeatVision().apply { configure(map.tileCountX, 1, false) }.grid()
        assertTrue(target.posX.toDouble() < map.tileCountX.toDouble() * map.tileWorldSizeX)
        assertEquals(map.tileCountX, (target.posX / map.tileWorldSizeX).toInt())
        val scope = assertNotNull(stock.openNativeSightLookupOrNull())
        try {
            val types = arrayOf(BaseUnit::class.java, map.javaClass, OwnedSeatVision.Grid::class.java, scope.javaClass)
            val cull = EngineViewProjector::class.java.getDeclaredMethod("cullHiddenNativeUnit", *types).apply { isAccessible = true }
            val full = EngineViewProjector::class.java.getDeclaredMethod("mayDisclose", *types).apply { isAccessible = true }
            fun invoke(method: java.lang.reflect.Method, lookup: StockUnitCatalog.NativeSightLookup?): Boolean =
                try { method.invoke(projector, target, map, fog, lookup) as Boolean }
                catch (error: InvocationTargetException) { throw error.cause!! }
            fun optimized(): Boolean = if (invoke(cull, scope)) false else invoke(full, scope)
            target.currentHealth = Float.NaN
            assertFalse(invoke(cull, scope), "The prefilter must not read an out-of-grid fog cell")
            assertFalse(optimized()); assertFalse(invoke(full, null))
            target.currentHealth = 100f
            assertFailsWith<IndexOutOfBoundsException> { optimized() }
            assertFailsWith<IndexOutOfBoundsException> { invoke(full, null) }
        } finally { scope.close() }
    }

    @Test fun `unknown hidden native subclass still executes original type and disclosure getter order`() = withWorld {
        add(CommandCenter(true), own)
        val target = add(CallbackCustom(config), enemy, 810f, 610f)
        val stock = stock(); val calls = arrayListOf<String>()
        target.onType = { calls += "type" }
        target.onUnselectable = { calls += "unselectable"; false }
        target.onUpgrade = { calls += "upgrade"; 1 }
        target.onVisibility = { calls += "visibility"; true }
        val view = projector(stock).snapshot()
        assertEquals(listOf("type", "type", "unselectable", "upgrade", "visibility"), calls)
        assertEquals(listOf("commandCenter"), view.units.map { it.typeName })
        assertNotNull(stock.openNativeSightLookupOrNull()).close()
    }
}
