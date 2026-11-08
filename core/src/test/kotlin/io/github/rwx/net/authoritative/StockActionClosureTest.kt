package com.corrodinggames.rts.game.units.custom

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitContainer
import com.corrodinggames.rts.game.units.UnitAction
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.UnitMovementType
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.actions.ActionDisplayType
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.custom.condition.StoredResourceEntry
import com.corrodinggames.rts.game.units.custom.condition.StoredResources
import com.corrodinggames.rts.game.units.custom.condition.resources.Resource
import com.corrodinggames.rts.game.units.custom.logic.CustomAction
import com.corrodinggames.rts.game.units.custom.logic.CustomActionDef
import com.corrodinggames.rts.game.units.custom.logic.BuildType
import com.corrodinggames.rts.game.units.custom.logic.LogicAction
import com.corrodinggames.rts.game.units.custom.logic.actions.AttachmentAction
import com.corrodinggames.rts.game.units.custom.logic.actions.SpawnUnitAction
import com.corrodinggames.rts.game.units.custom.logic.actions.TransportAction
import com.corrodinggames.rts.game.units.custom.logic.actions.WaypointAction
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.game.units.custom.price.UnitPrice
import com.corrodinggames.rts.game.units.custom.hooks.AttachmentSlotDefinition
import com.corrodinggames.rts.game.units.custom.hooks.setFixtureCreation
import com.corrodinggames.rts.game.units.custom.hooks.setFixtureIdentity
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBooleanGameFunctions.HasFlagBoolean
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import io.github.rwx.geometry.PointF
import io.github.rwx.net.authoritative.StockActionClosure
import io.github.rwx.net.authoritative.StockUnitCatalog
import io.github.rwx.net.authoritative.StockUnitManifest
import kotlin.concurrent.thread
import kotlin.test.*

/** Package-local fixture assembly accesses Java fields directly; production accessors remain read-only. */
class StockActionClosureTest {
    private class Fixture(val engine: GameLogic) {
        fun config(name: String = "mechFactory"): CustomUnitConfig {
            val spec = StockUnitManifest.bundled.first { it.name == name }
            return CustomUnitConfig().apply {
                this.name = spec.name; configPath = "assets/${spec.path}"
                sourceFilePath = configPath; resourceLoadPath = configPath; configHash = name.hashCode()
                specialActionLists = Array(3) { UnitContainer() }
                CustomUnitConfig.activeConfigs.add(this)
                spec.replaces?.let { CustomUnitConfig.unitTypeOverrides[it] = this }
            }
        }

        fun action(config: CustomUnitConfig, name: String = "fixture"): CustomAction {
            val definition = CustomActionDef().apply { this.name = name; price = UnitPrice.a(0) }
            config.customActionDefs.add(definition)
            return CustomAction(definition, null).also { action -> config.specialActionLists.forEach { it.a.add(action) } }
        }

        fun reference(config: CustomUnitConfig, target: UnitType) = UnitTypeReference().apply {
            unitType = target; config.unitTypeReferences.add(this)
        }

        fun handler(config: CustomUnitConfig, vararg actions: CustomAction) = CustomUnitActionHandler().apply {
            this.actions = FastArrayList<CustomAction>().also { list -> actions.forEach(list::add) }
            config.actionHandlers.add(this)
        }

        fun spawner(config: CustomUnitConfig, target: UnitType) = UnitSpawner().apply {
            spawnQueue = FastArrayList<SpawnConfig>().apply { add(SpawnConfig(reference(config, target))) }
        }

        fun parserFallback(config: CustomUnitConfig) = CustomProjectileTemplate().apply {
            projectileId = 0; projectileName = "1"; b = 10
            config.projectileTemplates.add(this)
            config.projectileTemplatesById = arrayOf(this)
        }

        fun capture() = StockActionClosure.capture(engine, StockUnitCatalog.capture(engine))
    }

    private fun withWorld(block: Fixture.() -> Unit) {
        val field = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = field.get(null)
        val platform = GameEngine.isNonAndroidVersion
        field.set(null, null); GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        val registry = CustomUnitConfig.activeConfigs
        val overrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        CustomUnitConfig.activeConfigs = arrayListOf(); CustomUnitConfig.unitTypeOverrides.clear()
        try { Fixture(engine).block() } finally {
            CustomUnitConfig.activeConfigs = registry
            CustomUnitConfig.unitTypeOverrides.clear(); CustomUnitConfig.unitTypeOverrides.putAll(overrides)
            field.set(null, previous); GameEngine.isNonAndroidVersion = platform
        }
    }

    @Test fun `all 120 frozen configurations and cyclic compiled references close without evaluating conditions`() = withWorld {
        val configs = StockUnitManifest.bundled.map { config(it.name) }
        val actions = configs.mapIndexed { index, config -> action(config, "fixture_$index") }
        val poison = object : LogicBoolean() {
            override fun read(unit: OrderableUnit): Boolean = error("Closure evaluated a condition")
            override fun getMatchFailReasonForPlayer(unit: OrderableUnit): String = error("Closure evaluated text")
        }
        configs.forEachIndexed { index, config ->
            actions[index].actionDef.convertTo = reference(config, configs[(index + 1) % configs.size])
            actions[index].actionDef.isActive = poison
            actions[index].actionDef.alsoTriggerAction = handler(config, actions[(index + 1) % actions.size])
        }
        val closure = capture()
        assertEquals(120, closure.configCount)
        assertTrue(closure.failures().isEmpty(), closure.failures().toString())
        actions.forEach { assertTrue(closure.allows(it)) }
        assertSame(closure.token(actions.first()), closure.token(actions.first()))
    }

    @Test fun `spawn attachment transport waypoint and hidden trigger outputs are closed by instance identity`() = withWorld {
        val config = config()
        val main = action(config)
        val hidden = action(config, "hidden")
        hidden.actionDef.isVisible = LogicBoolean.falseBoolean
        main.actionDef.alsoTriggerAction = handler(config, hidden)
        main.actionDef.alsoQueueAction = handler(config, main)
        main.actionDef.whenBuildingTriggerAction = handler(config, hidden)
        main.actionDef.logicActions.add(SpawnUnitAction().apply { produceUnits = spawner(config, UnitTypeEnum.builder) })
        main.actionDef.logicActions.add(AttachmentAction().apply { addNewUnits = spawner(config, UnitTypeEnum.tank) })
        main.actionDef.logicActions.add(TransportAction().apply { addUnitsIntoTransport = spawner(config, UnitTypeEnum.builder) })
        main.actionDef.logicActions.add(WaypointAction().apply {
            waypointUnitType = reference(config, UnitTypeEnum.landFactory)
            triggerActionIfFailed = handler(config, hidden)
        })
        config.unitsSpawnedOnDeath = spawner(config, UnitTypeEnum.tank)
        val closure = capture()
        assertTrue(closure.failures().isEmpty(), closure.failures().toString())
        assertTrue(closure.allows(main))
        assertTrue(closure.allows(hidden), "Content permission is independent of UI visibility")
        assertFalse(closure.allows(CustomAction(main.actionDef, null)), "A copied action is not a captured capability")
    }

    @Test fun `replacing a resolved output with another approved stock type revokes permanently`() = withWorld {
        val config = config()
        val action = action(config)
        val output = reference(config, UnitTypeEnum.tank)
        action.actionDef.convertTo = output
        val closure = capture()
        assertTrue(closure.allows(action))
        output.unitType = UnitTypeEnum.builder
        assertFalse(closure.allows(action))
        output.unitType = UnitTypeEnum.tank
        assertNull(closure.token(action))
        assertFailsWith<IllegalStateException> { closure.assertValid() }
    }

    @Test fun `single reference detects both null transitions and remains revoked after restoration`() = withWorld {
        val config = config()
        val action = action(config)
        // Register the referenced type before capture so only the single deathSpawn slot changes.
        val spawn = spawner(config, UnitTypeEnum.tank)
        assertNull(config.unitsSpawnedOnDeath)
        val absent = capture()
        assertTrue(absent.allows(action))
        config.unitsSpawnedOnDeath = spawn
        assertFalse(absent.allows(action))
        config.unitsSpawnedOnDeath = null
        assertNull(absent.token(action))
        assertFailsWith<IllegalStateException> { absent.assertValid() }

        config.unitsSpawnedOnDeath = spawn
        val present = capture()
        assertTrue(present.allows(action))
        config.unitsSpawnedOnDeath = null
        assertFalse(present.allows(action))
        config.unitsSpawnedOnDeath = spawn
        assertNull(present.token(action))
        assertFailsWith<IllegalStateException> { present.assertValid() }
    }

    @Test fun `unfollowed single reference still checks identity without evaluating its policy`() = withWorld {
        val config = config()
        val action = action(config)
        val policy = object : UnitAction() {
            override fun a(): UnitPrice? = error("Closure evaluated price policy")
            override fun b(): UnitPrice? = error("Closure evaluated streaming price policy")
        }
        action.unitAction = policy
        val closure = capture()
        assertTrue(closure.failures().isEmpty(), "Unfollowed policy is not a content dependency")
        assertTrue(closure.allows(action))
        action.unitAction = UnitAction()
        assertFalse(closure.allows(action))
        action.unitAction = policy
        assertNull(closure.token(action))
        assertFailsWith<IllegalStateException> { closure.assertValid() }
    }

    @Test fun `replacement reference and replacement linked list are both detected even with same contents`() = withWorld {
        val config = config()
        val action = action(config)
        val child = action(config, "child")
        val handler = handler(config, child)
        action.actionDef.alsoTriggerAction = handler
        val first = capture()
        handler.actions = FastArrayList<CustomAction>().apply { add(child) }
        assertFalse(first.allows(action))
        val second = capture()
        action.actionDef.alsoTriggerAction = handler(config, child)
        assertFalse(second.allows(action))
    }

    @Test fun `spawn queue entry and resolved reference substitution cannot inherit frozen permission`() = withWorld {
        val config = config()
        val action = action(config)
        val spawner = spawner(config, UnitTypeEnum.tank)
        action.actionDef.logicActions.add(SpawnUnitAction().apply { spawnUnits = spawner })
        val first = capture()
        spawner.spawnQueue[0] = SpawnConfig(spawner.spawnQueue[0].unitType)
        assertFalse(first.allows(action))
        val second = capture()
        spawner.spawnQueue[0].unitType = UnitTypeReference().apply { unitType = UnitTypeEnum.tank }
        assertFalse(second.allows(action))
    }

    @Test fun `unknown logic and unlinked handler report explicit local failures without executing or logging`() = withWorld {
        val config = config()
        val allowed = action(config, "allowed")
        val unsupported = action(config, "unsupported")
        unsupported.actionDef.logicActions.add(object : LogicAction() {
            override fun doAction(unit: CustomUnit, action: AbstractUnitAction, point: PointF?, target: BaseUnit?, depth: Int): Boolean =
                error("Closure executed an action")
        })
        val unlinked = action(config, "unlinked")
        unlinked.actionDef.alsoQueueAction = CustomUnitActionHandler()
        val closure = capture()
        assertTrue(closure.allows(allowed))
        assertFalse(closure.allows(unsupported))
        assertFalse(closure.allows(unlinked))
        assertTrue(closure.failures().any { it.reason.startsWith("Unreviewed logic handler") })
        assertTrue(closure.failures().any { it.reason == "linkedActions is not linked" })
        closure.assertValid()
    }

    @Test fun `unregistered same name config and unresolved references never become permitted`() = withWorld {
        val config = config()
        val action = action(config)
        val clone = CustomUnitConfig().apply { name = config.name; configPath = config.configPath }
        action.actionDef.convertTo = reference(config, clone)
        val closure = capture()
        assertFalse(closure.allows(action))
        assertTrue(closure.failures().any { "outside" in it.reason })
        action.actionDef.convertTo = UnitTypeReference()
        val unlinked = capture()
        assertFalse(unlinked.allows(action))
        assertTrue(unlinked.failures().any { "unlinked" in it.reason })
    }

    @Test fun `changing action list membership or logic handler object revokes the captured graph`() = withWorld {
        val config = config()
        val action = action(config)
        action.actionDef.logicActions.add(SpawnUnitAction())
        val first = capture()
        action.actionDef.logicActions[0] = SpawnUnitAction()
        assertFalse(first.allows(action))
        val second = capture()
        config.specialActionLists[0].a[0] = CustomAction(action.actionDef, null)
        assertFalse(second.allows(action))
    }

    @Test fun `projectile spawn graph is frozen including cached projectile resolution`() = withWorld {
        val config = config()
        val action = action(config)
        val projectile = CustomProjectileTemplate().apply {
            customUnitConfig = config; spawnUnit = spawner(config, UnitTypeEnum.tank)
        }
        config.projectileTemplates.add(projectile)
        config.projectileTemplatesById = arrayOf(projectile)
        val reference = CustomUnitProjectileReference().apply { unitType = config; this.projectile = projectile }
        config.unitTypeReferences.add(reference)
        projectile.spawnProjectilesOnExplode = UnitSpawnList().apply {
            a = FastArrayList<UnitSpawnEntry>().apply { add(UnitSpawnEntry(reference)) }
        }
        action.actionDef.fireTurretAtGroundProjectile = projectile
        val closure = capture()
        assertTrue(closure.allows(action), closure.failures().toString())
        reference.projectile = CustomProjectileTemplate().apply { customUnitConfig = config }
        assertFalse(closure.allows(action))
    }

    @Test fun `audited parser fallback is accepted only as the same object in both registered slots`() = withWorld {
        val config = config()
        val action = action(config)
        action.actionDef.convertTo = reference(config, config)
        val fallback = parserFallback(config)
        val closure = capture()
        assertTrue(closure.failures().isEmpty(), closure.failures().toString())
        assertTrue(closure.allows(action))
        val replacement = CustomProjectileTemplate().apply { projectileId = 0; projectileName = "1"; b = 10 }
        config.projectileTemplates[0] = replacement
        config.projectileTemplatesById[0] = replacement
        assertFalse(closure.allows(action), "Both slots changed to an equivalent object must still revoke")
        config.projectileTemplates[0] = fallback
        config.projectileTemplatesById[0] = fallback
        assertNull(closure.token(action), "Restoring the old fallback cannot recover a revoked closure")
    }

    @Test fun `unregistered null owner template cannot borrow a registered fallback identity`() = withWorld {
        val config = config()
        val action = action(config)
        parserFallback(config)
        action.actionDef.fireTurretAtGroundProjectile = CustomProjectileTemplate().apply {
            projectileId = 0; projectileName = "1"; b = 10
        }
        val closure = capture()
        assertFalse(closure.allows(action))
        assertTrue(closure.failures().any { it.reason == "Projectile is not registered on its frozen stock owner" })
    }

    @Test fun `fallback exception excludes other stock keys and templates with spawn edges`() = withWorld {
        val tank = config("c_tank")
        val tankAction = action(tank)
        tankAction.actionDef.convertTo = reference(tank, tank)
        parserFallback(tank)
        val factory = config()
        val factoryAction = action(factory)
        factoryAction.actionDef.convertTo = reference(factory, factory)
        parserFallback(factory).spawnUnit = spawner(factory, UnitTypeEnum.builder)
        val closure = capture()
        assertFalse(closure.allows(tankAction), "A weapon configuration is outside the fallback allowlist")
        assertFalse(closure.allows(factoryAction), "A null-owner fallback cannot acquire a unit production edge")
        assertEquals(2, closure.failures().count { it.reason == "Projectile is not registered on its frozen stock owner" })
    }

    @Test fun `fallback default metadata and no spawn shape remain frozen after capture`() = withWorld {
        val config = config()
        val action = action(config)
        val fallback = parserFallback(config)
        val first = capture()
        assertTrue(first.allows(action))
        fallback.b = 11
        assertFalse(first.allows(action))
        fallback.b = 10
        val second = capture()
        fallback.projectileObserverId = "new-observer"
        assertFalse(second.allows(action), "Adding an extension cannot reuse the audited parser fallback")
    }

    @Test fun `modular attachment creation and slot replacement are frozen without creating children`() = withWorld {
        val parent = config("modularSpider")
        val module = config("modularSpider_emptySlot")
        val action = action(parent)
        val slot = AttachmentSlotDefinition().apply { setFixtureCreation(spawner(parent, module)) }
        parent.attachmentSlotDefinitions.add(slot)
        val first = capture()
        assertTrue(first.allows(action), first.failures().toString())
        slot.setFixtureCreation(spawner(parent, module))
        assertFalse(first.allows(action), "An equivalent new onCreate spawner is still a new capability graph")
        val second = capture()
        parent.attachmentSlotDefinitions[0] = AttachmentSlotDefinition()
        assertFalse(second.allows(action))
    }

    @Test fun `mutating a slot identity visibility or fixed flag predicate revokes its old closure`() = withWorld {
        val parent = config("modularSpider")
        val action = action(parent)
        val flag = HasFlagBoolean()
        val slot = AttachmentSlotDefinition().apply { N = flag }
        parent.attachmentSlotDefinitions.add(slot)
        fun reset() {
            slot.setFixtureIdentity(0, "unitSlot1"); slot.o = true
            flag.flagId = 1; flag.flagMask = 2
        }
        val changes: List<Pair<String, () -> Unit>> = listOf(
            "slot index" to { slot.setFixtureIdentity(1, "unitSlot1") },
            "slot name" to { slot.setFixtureIdentity(0, "unitSlot2") },
            "slot visibility" to { slot.o = false },
            "flag id" to { flag.flagId = 2 },
            "flag mask" to { flag.flagMask = 4 },
        )
        for ((label, change) in changes) {
            reset()
            val closure = capture()
            assertTrue(closure.allows(action), label)
            change()
            assertFalse(closure.allows(action), label)
            reset()
            assertNull(closure.token(action), "$label restoration cannot recover a revoked token")
        }
    }

    @Test fun `unrelated unsupported manual action does not poison a stock conversion lifecycle`() = withWorld {
        val config = config()
        val allowed = action(config, "allowed")
        allowed.actionDef.convertTo = reference(config, config)
        val unsupported = action(config, "unsupported")
        unsupported.actionDef.alsoTriggerAction = CustomUnitActionHandler()
        val closure = capture()
        assertTrue(closure.allows(allowed), closure.failures().toString())
        assertFalse(closure.allows(unsupported))
    }

    @Test fun `action semantic names and static queue policies cannot change under a frozen token`() = withWorld {
        val config = config()
        val changes: List<Pair<String, (CustomActionDef) -> Unit>> = listOf(
            "name" to { it.name = "ccancel" },
            "displayName" to { it.displayName = "sell" },
            "stringId" to { it.stringId = "tank" },
            "id" to { it.id = 100 },
            "queueType" to { it.queueType = ActionType.none },
            "buildType" to { it.buildType = "changed" },
            "actionType" to { it.actionType = BuildType.convert },
            "displayType" to { it.displayType = ActionDisplayType.action },
            "aiUse" to { it.aiUse = com.corrodinggames.rts.game.units.custom.logic.ActionType.launch },
            "extraLagHidingInUI" to { it.extraLagHidingInUI = true },
            "isAlsoViewableByAllies" to { it.isAlsoViewableByAllies = true },
            "isAlsoViewableByEnemies" to { it.isAlsoViewableByEnemies = true },
            "canPlayerCancel" to { it.canPlayerCancel = false },
            "allowMultipleInQueue" to { it.allowMultipleInQueue = false },
            "onlyOneUnitAtATime" to { it.onlyOneUnitAtATime = true },
            "highPriorityQueue" to { it.highPriorityQueue = true },
            "alwaysSinglePress" to { it.alwaysSinglePress = true },
            "hideQueueInterface" to { it.hideQueueInterface = true },
            "addToBuildQueue" to { it.addToBuildQueue = true },
            "hideInBuildMenu" to { it.hideInBuildMenu = true },
            "convertToKeepCurrentTags" to { it.convertToKeepCurrentTags = true },
            "displayRemainingStockpile" to { it.displayRemainingStockpile = true },
            "techLevel" to { it.techLevel = 2 },
            "forceNano" to { it.forceNano = true },
            "buildSpeed" to { it.buildSpeed = 0.75f },
            "buildSpeedIgnoreFactorySpeedModifiers" to { it.buildSpeedIgnoreFactorySpeedModifiers = true },
            "whenBuildingCannotMove" to { it.whenBuildingCannotMove = true },
            "pos" to { it.pos = 1f },
            "whenBuildingRotateTo" to { it.whenBuildingRotateTo = 45f },
            "whenBuildingRotateToOrBackwards" to { it.whenBuildingRotateToOrBackwards = true },
            "whenBuildingRotateToWaitTillRotated" to { it.whenBuildingRotateToWaitTillRotated = true },
            "whenBuildingRotateToAimAtActionTarget" to { it.whenBuildingRotateToAimAtActionTarget = true },
            "addEnergy" to { it.addEnergy = 2f },
            "fireTurretAtGroundIndex" to { it.fireTurretAtGroundIndex = 3 },
            "fireTurretAtGroundCount" to { it.fireTurretAtGroundCount = 3 },
            "fireTurretAtGroundTerrainFilter" to { it.fireTurretAtGroundTerrainFilter = UnitMovementType.LAND },
        )
        assertEquals(36, changes.size)
        assertEquals(changes.size, changes.map { it.first }.toSet().size)
        for ((index, change) in changes.withIndex()) {
            val action = action(config, "metadata_$index")
            // The real [action_cancel] has no explicit name: displayName carries the section suffix.
            action.actionDef.name = null
            action.actionDef.displayName = "cancel"
            val field = CustomActionDef::class.java.getField(change.first)
            val original = field.get(action.actionDef)
            val closure = capture()
            assertNotNull(closure.token(action), change.first)
            change.second(action.actionDef)
            assertFalse(closure.allows(action), "Changed ${change.first} retained a token")
            assertFailsWith<IllegalStateException>(change.first) { closure.assertValid() }
            field.set(action.actionDef, original)
            assertNull(closure.token(action), "Restoring ${change.first} revived a revoked token")
        }
    }

    @Test fun `definition floats preserve raw NaN payload signed zero and nullable presence`() = withWorld {
        val config = config()
        var index = 0
        fun changed(name: String, original: Float?, replacement: Float?) {
            val action = action(config, "raw_float_${index++}")
            val field = CustomActionDef::class.java.getField(name)
            field.set(action.actionDef, original)
            val closure = capture()
            assertTrue(closure.allows(action), name)
            // The same raw representation remains equal even for a newly boxed NaN.
            field.set(action.actionDef, original?.let { Float.fromBits(it.toRawBits()) })
            assertTrue(closure.allows(action), "Equal raw bits changed $name")
            field.set(action.actionDef, replacement)
            assertFalse(closure.allows(action), "Different raw bits/presence retained $name")
            field.set(action.actionDef, original)
            assertNull(closure.token(action), "Restoring raw bits revived $name")
            assertFailsWith<IllegalStateException> { closure.assertValid() }
        }
        for (name in listOf("buildSpeed", "pos", "whenBuildingRotateTo", "addEnergy")) {
            changed(name, 0f, Float.fromBits(Int.MIN_VALUE))
            changed(name, Float.fromBits(0x7fc00001), Float.fromBits(0x7fc00002))
        }
        for (name in listOf("whenBuildingRotateTo", "addEnergy")) {
            changed(name, null, 0f)
            changed(name, 0f, null)
        }
    }

    @Suppress("DEPRECATION", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @Test fun `definition strings and nullable integer use value equality rather than reference identity`() = withWorld {
        val config = config()
        val action = action(config)
        val textFields = listOf("name", "displayName", "stringId", "buildType").map { CustomActionDef::class.java.getField(it) }
        for (field in textFields) field.set(action.actionDef, String("same-${field.name}".toCharArray()))
        val index = CustomActionDef::class.java.getField("fireTurretAtGroundIndex")
        val first = java.lang.Integer(1000)
        val second = java.lang.Integer(1000)
        assertNotSame(first, second)
        index.set(action.actionDef, first)
        val closure = capture()
        assertTrue(closure.allows(action))
        for (field in textFields) {
            val original = field.get(action.actionDef) as String
            val replacement = String(original.toCharArray())
            assertNotSame(original, replacement)
            field.set(action.actionDef, replacement)
        }
        index.set(action.actionDef, second)
        assertTrue(closure.allows(action), "Equal strings and Integer values must preserve permission")
        index.set(action.actionDef, null)
        assertFalse(closure.allows(action))
        index.set(action.actionDef, second)
        assertNull(closure.token(action))
    }

    @Test fun `price identity all five amounts flags and added resources are frozen`() = withWorld {
        val config = config()
        val changes: List<Pair<String, (UnitPrice) -> Unit>> = listOf(
            "credits" to { it.b = 1 }, "energy" to { it.c = 1f }, "hp" to { it.d = 1f },
            "shield" to { it.e = 1f }, "ammo" to { it.f = 1 }, "setFlag" to { it.g = 1 },
            "unsetFlag" to { it.h = 1 }, "requiredFlag" to { it.i = 1 }, "excludedFlag" to { it.j = 1 },
        )
        for ((index, change) in changes.withIndex()) {
            val action = action(config, "price_$index")
            val closure = capture()
            change.second(action.actionDef.price)
            assertFalse(closure.allows(action), "Changed ${change.first} retained a token")
        }
        val action = action(config, "price_identity")
        val first = capture()
        action.actionDef.price = UnitPrice.a(0)
        assertFalse(first.allows(action), "Equal prices held by a new object cannot reuse the token")
        action.actionDef.streamingCost = UnitPrice.a(3)
        action.actionDef.addResources = UnitPrice.a(5)
        val second = capture()
        action.actionDef.streamingCost.b++
        assertFalse(second.allows(action))
        val third = capture()
        action.actionDef.addResources.f++
        assertFalse(third.allows(action), "Changing output ammo can change the action's semantic kind")
    }

    @Test fun `custom resource price containers entries and amounts remain frozen`() = withWorld {
        val config = config()
        val action = action(config)
        val amount = StoredResourceEntry(Resource.D, 2.0)
        action.actionDef.price.k = StoredResources().apply { b.add(amount) }
        val first = capture()
        assertTrue(first.allows(action))
        amount.b = 3.0
        assertFalse(first.allows(action))
        val second = capture()
        action.actionDef.price.k.b[0] = StoredResourceEntry(Resource.D, 3.0)
        assertFalse(second.allows(action))
        val third = capture()
        action.actionDef.price.k = StoredResources().apply { b.add(StoredResourceEntry(Resource.D, 3.0)) }
        assertFalse(third.allows(action))
    }

    @Test fun `capture bounds and owner lifecycle are enforced`() = withWorld {
        val config = config()
        val action = action(config)
        val closure = capture()
        var wrongThread: Throwable? = null
        thread { try { closure.allows(action) } catch (failure: Throwable) { wrongThread = failure } }.join()
        assertIs<IllegalStateException>(wrongThread)
        engine.currentTick = 1
        assertFailsWith<IllegalStateException> { capture() }
        engine.currentTick = 0
        repeat(4_097) { config.unitTypeReferences.add(UnitTypeReference().apply { unitType = UnitTypeEnum.tank }) }
        assertFailsWith<IllegalStateException> { capture() }
    }
}
