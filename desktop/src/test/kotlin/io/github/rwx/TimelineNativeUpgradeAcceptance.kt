package io.github.rwx

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.gameFramework.Command
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import io.github.rwx.net.authoritative.*
import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** Real queues and real conversions, exercised through the same private capabilities as a client. */
internal object TimelineNativeUpgradeAcceptance {
    private data class Step(val target: String, val level: Int? = null, val refund: Int = 0)
    private data class Chain(val name: String, val initial: String, val steps: List<Step>, val native: Boolean = false)
    private val chains = listOf(
        Chain("nativeLandFactory", "landFactory", listOf(Step("landFactory", 2)), native = true),
        Chain("nativeAirFactory", "airFactory", listOf(Step("airFactory", 2)), native = true),
        Chain("nativeSeaFactory", "seaFactory", listOf(Step("seaFactory", 2)), native = true),
        Chain("extractor", "extractor", listOf(Step("extractorT2"), Step("extractorT3"),
            Step("extractorT3_overclocked"), Step("extractorT3", refund = 4_000),
            Step("extractorT3_reinforced"), Step("extractorT3", refund = 2_000))),
        Chain("fabricator", "fabricator", listOf(Step("fabricatorT2"), Step("fabricatorT3"))),
        Chain("turretGun", "turret", listOf(Step("turretT2"), Step("turretT3"))),
        Chain("turretArtillery", "turret", listOf(Step("turret_artillery"), Step("c_turret_t2_artillery"))),
        Chain("turretFlame", "turret", listOf(Step("turret_flamethrower"))),
        Chain("turretLightning", "turret", listOf(Step("c_turret_t1_lightning"), Step("c_turret_t2_lightning"))),
        Chain("antiAir", "antiAirTurret", listOf(Step("antiAirTurretT2"), Step("c_antiAirTurretT3"))),
        Chain("antiAirFlak", "antiAirTurret", listOf(Step("antiAirTurretFlak"))),
        Chain("mechFactory", "mechFactory", listOf(Step("mechFactoryT2"))),
        Chain("outpost", "outpostT1", listOf(Step("outpostT2"))),
    )

    fun run(engine: GameEngine, publicMap: EnginePublicMapSnapshot, stock: StockUnitCatalog,
        closure: StockActionClosure, outputFile: File): Map<String, Boolean> {
        val checks = linkedMapOf<String, Boolean>()
        val reports = mutableListOf<JsonObject>()
        val failures = mutableListOf<Throwable>()
        val ownUnits = Collections.newSetFromMap(IdentityHashMap<BaseUnit, Boolean>())
        val ownCommands = Collections.newSetFromMap(IdentityHashMap<Command, Boolean>())
        val startTick = engine.currentTick
        var query = 0L
        var sequence = 0L
        var failure: Throwable? = null

        fun verify(name: String, passed: Boolean) {
            checks[name] = passed
            check(passed) { "Upgrade acceptance failed: $name" }
        }
        fun forgetOwnCommands() {
            engine.commandController.pendingCommands.removeAll(ownCommands)
            engine.commandController.queuedCommands.removeAll(ownCommands)
            engine.commandController.executedCommands.removeAll(ownCommands)
        }
        fun remove(unit: BaseUnit) {
            if (ownUnits.remove(unit)) unit.removeFromGame()
            GameObject.dL()
        }
        fun state(actor: OrderableUnit): JsonObject = buildJsonObject {
            put("type", stock.keyFor(actor.r())); put("upgradeLevel", actor.upgradeLevel)
            put("tick", engine.currentTick); put("dead", actor.isDead); put("destroyed", actor.isDestroyed)
            put("active", actor.isActive); put("buildProgress", actor.buildProgress)
            put("registered", actor in BaseUnit.getGlobalUnitList())
            val queue = actor as? FactoryQueueInterface
            put("queueSize", queue?.dx()?.size ?: -1); put("queueProgress", queue?.dw()?.m)
            put("credits", actor.team.credits)
        }

        try {
            verify("usesExistingFrozenCatalogAfterSimulationStarted", engine.currentTick > 0)
            stock.assertValid(); closure.assertValid()
            verify("noUnrelatedCommandsCompeteWithCostObservation", engine.commandController.pendingCommands.isEmpty() &&
                engine.commandController.queuedCommands.isEmpty())
            val team = checkNotNull(PlayerTeam.k(0)) { "Upgrade acceptance requires the existing first seat" }
            // The caller owns match setup. Do not reset income, health, tech, queues or team state.
            verify("existingSeatCanFundAllUpgradeChains", team.credits.isFinite() && team.credits >= 150_000.0)
            val map = checkNotNull(engine.tileMap)
            val publicTerrain = publicMap.terrainCopy()
            check(publicTerrain.size == map.tileCountX * map.tileCountY) { "Public terrain dimensions changed" }
            val home = BaseUnit.getGlobalUnitList().firstOrNull {
                it.team === team && !it.isDead && stock.keyFor(it.r()) == "commandCenter"
            } ?: BaseUnit.getGlobalUnitList().firstOrNull { it.team === team && !it.isDead }
                ?: error("Upgrade acceptance requires a surviving first-seat unit")
            val homeX = home.posX; val homeY = home.posY
            val projector = EngineViewProjector(engine, team, publicMap, stock, closure)

            fun disclosed(view: PlayerViewSnapshot, actor: OrderableUnit): ViewUnit = view.units.singleOrNull {
                it.teamId == team.teamId && it.typeName == stock.keyFor(actor.r()) &&
                    it.x == actor.posX && it.y == actor.posY
            } ?: error("Upgrade actor is not uniquely disclosed: ${stock.keyFor(actor.r())}")

            fun spawn(key: String, attempts: MutableList<JsonObject>): Pair<OrderableUnit, JsonObject> {
                val created = checkNotNull(stock.typeFor(key)) { "Missing upgrade fixture stock type: $key" }.a()
                ownUnits += created
                val unit = created as? OrderableUnit ?: error("Upgrade fixture is not orderable: $key")
                unit.setUnitTeam(team)
                val all = BaseUnit.getGlobalUnitList().filter { it !== unit && !it.isDead && !it.isDestroyed }
                val enemy = all.filter { it.team != null && team.c(it.team) }
                val requiresResourcePool = unit.r().p()
                fun squared(x: Float, y: Float, otherX: Float, otherY: Float): Double =
                    (x - otherX).toDouble().let { it * it } + (y - otherY).toDouble().let { it * it }
                fun snapToBuildPosition(x: Int, y: Int) {
                    // Match OrderableUnit.a(UnitCommand,...): the build request is snapped using
                    // the actual type's footprint offsets, which are not always half a tile.
                    val requestedX = (x + .5f) * map.tileWorldSizeX
                    val requestedY = (y + .5f) * map.tileWorldSizeY
                    map.updateCursorTileIndexFromWorldPoint(requestedX - unit.tileOffsetX + 1f,
                        requestedY - unit.tileOffsetY + 1f)
                    unit.posX = map.cursorTileX + unit.tileOffsetX
                    unit.posY = map.cursorTileY + unit.tileOffsetY
                }
                // Resource pools commonly sit beside the player's base. Actual building collision
                // checks apply there; the extra 250-unit open-area preference must not exclude them.
                val candidates = buildList {
                    val margin = if (requiresResourcePool) 0 else 4
                    for (y in margin until map.tileCountY - margin) for (x in margin until map.tileCountX - margin) {
                        if (requiresResourcePool && (publicTerrain[y * map.tileCountX + x].toInt() and 4) == 0) continue
                        snapToBuildPosition(x, y)
                        if (!requiresResourcePool && enemy.any { squared(unit.posX, unit.posY, it.posX, it.posY) < 1_000_000.0 }) continue
                        if (!requiresResourcePool && all.any { squared(unit.posX, unit.posY, it.posX, it.posY) < 62_500.0 }) continue
                        add(x to y)
                    }
                }.sortedBy { squared((it.first + .5f) * map.tileWorldSizeX,
                    (it.second + .5f) * map.tileWorldSizeY, homeX, homeY) }
                val site = candidates.firstOrNull { (x, y) ->
                    snapToBuildPosition(x, y)
                    val enemyDistance = enemy.minOfOrNull { squared(unit.posX, unit.posY, it.posX, it.posY) }
                    val safeEnemyDistance = enemyDistance == null || enemyDistance >= 1_000_000.0
                    val reason = unit.getPlacementFailureReason(false, null)
                    val legal = unit.canPlaceAtCurrentPosition(null)
                    attempts += buildJsonObject {
                        put("tileX", x); put("tileY", y); put("x", unit.posX); put("y", unit.posY)
                        put("footprintOffsetX", unit.tileOffsetX); put("footprintOffsetY", unit.tileOffsetY)
                        put("publicResourcePool", (publicTerrain[y * map.tileCountX + x].toInt() and 4) != 0)
                        put("requiresResourcePool", requiresResourcePool)
                        put("ownBaseType", stock.keyFor(home.r())); put("ownBaseX", homeX); put("ownBaseY", homeY)
                        put("ownBaseDistanceSquared", squared(unit.posX, unit.posY, homeX, homeY))
                        put("nearestEnemyDistanceSquared", enemyDistance); put("safeEnemyDistance", safeEnemyDistance)
                        put("legalEnginePlacement", legal); put("footprintFailureReason", reason)
                    }
                    safeEnemyDistance && legal
                } ?: error("No legal $key placement at least 1,000 world units from enemies")
                val legal = unit.canPlaceAtCurrentPosition(null)
                check(legal) { "Selected stock placement became invalid" }
                unit.isActive = true
                unit.n(); PlayerTeam.c(unit); engine.unitSpatialIndex.a(unit); GameObject.dL()
                val ground = map.getTileAt(site.first, site.second)
                val overlay = map.getPathingOverrideTileAt(site.first, site.second)
                return unit to buildJsonObject {
                    put("x", unit.posX); put("y", unit.posY); put("tileX", site.first); put("tileY", site.second)
                    put("legalEnginePlacement", legal); put("groundWater", ground?.isWater == true)
                    put("resourcePool", overlay?.isResourcePool == true)
                    put("requiresResourcePool", requiresResourcePool); put("candidateCount", candidates.size)
                    put("attemptCount", attempts.size)
                    put("footprintOffsetX", unit.tileOffsetX); put("footprintOffsetY", unit.tileOffsetY)
                    put("ownBaseDistanceSquared", squared(unit.posX, unit.posY, homeX, homeY))
                    put("nearestEnemyDistanceSquared", enemy.minOfOrNull { squared(unit.posX, unit.posY, it.posX, it.posY) })
                }
            }

            for (chain in chains) {
                var placement: JsonObject? = null
                val placementAttempts = mutableListOf<JsonObject>()
                val steps = mutableListOf<JsonObject>()
                var chainFailure: Throwable? = null
                try {
                    val spawned = spawn(chain.initial, placementAttempts)
                    val actor = spawned.first
                    placement = spawned.second
                    verify("${chain.name}.nativeIdentity", !chain.native || actor !is CustomUnit)
                    for ((index, step) in chain.steps.withIndex()) {
                        val label = "${chain.name}.${index + 1}.${step.target}"
                        val expectedType = checkNotNull(stock.typeFor(step.target))
                        val expectedLevel = step.level ?: (expectedType as? CustomUnitConfig)?.techLevel ?: 1
                        val before = state(actor)
                        val creditChanges = mutableListOf<JsonObject>()
                        val queueSamples = mutableListOf<JsonObject>()
                        var panel: PlayerActionPanel? = null
                        var action: ViewAction? = null
                        var completed = false
                        var charged = false
                        var observedQueue = false
                        var largestCreditIncrease = 0.0
                        var advancedTicks = 0
                        var stepFailure: Throwable? = null
                        try {
                            val view = projector.snapshot()
                            val owner = disclosed(view, actor)
                            val requestedPanel = projector.actionPanel(ViewActionPanelQuery(++query, view.sequence, owner.handle))
                            panel = requestedPanel
                            val selectedAction = requestedPanel.actions.singleOrNull { it.kind == ViewActionKind.UPGRADE &&
                                (if (chain.native) it.producedTypeKey.isEmpty() else it.producedTypeKey == step.target) }
                                ?: error("Missing unique upgrade to ${step.target}; advertised=${requestedPanel.actions.map { it.kind to it.producedTypeKey }}")
                            action = selectedAction
                            verify("$label.advertised", selectedAction.targetMode == ActionTargetMode.NONE)
                            val requiresCharge = (selectedAction.upfrontCost?.credits ?: 0) > 0 ||
                                (selectedAction.streamingCost?.credits ?: 0) > 0
                            verify("$label.expectedCost", if (step.refund > 0) !requiresCharge else requiresCharge)
                            var priorCredits = team.credits
                            val pending = engine.commandController.pendingCommands.toSet()
                            val accepted = try {
                                projector.applyAction(ViewActionCommand(++sequence, view.sequence,
                                    requestedPanel.panelSequence, requestedPanel.unitHandle, selectedAction.handle))
                            } finally {
                                ownCommands.addAll(engine.commandController.pendingCommands.filter { it !in pending })
                            }
                            verify("$label.accepted", accepted)
                            val firstTick = engine.currentTick
                            while (engine.currentTick - firstTick < 12_000) {
                                val previousTick = engine.currentTick
                                AuthoritativeDesktopMain.advance(engine)
                                check(engine.currentTick > previousTick) { "Simulation stopped advancing during $label" }
                                advancedTicks = engine.currentTick - firstTick
                                val delta = team.credits - priorCredits
                                check(delta.isFinite()) { "Non-finite resource balance during $label" }
                                if (delta < -0.00001) charged = true
                                if (delta > largestCreditIncrease) largestCreditIncrease = delta
                                if (delta != 0.0) creditChanges += buildJsonObject {
                                    put("tick", engine.currentTick); put("before", priorCredits)
                                    put("after", team.credits); put("delta", delta)
                                }
                                priorCredits = team.credits
                                val queue = actor as? FactoryQueueInterface
                                if ((queue?.dx()?.size ?: 0) > 0) observedQueue = true
                                if (advancedTicks == 1 || advancedTicks % 300 == 0) queueSamples += state(actor)
                                check(!actor.isDead && !actor.isDestroyed) { "Upgrade fixture died during $label" }
                                completed = actor.r() === expectedType && actor.upgradeLevel == expectedLevel && actor.buildProgress >= 1f
                                if (completed) break
                            }
                            queueSamples += state(actor)
                            verify("$label.queueObserved", observedQueue)
                            verify("$label.completed", completed)
                            verify("$label.actualCharge", !requiresCharge || charged)
                            if (step.refund > 0) verify("$label.actualRefund", largestCreditIncrease + .00001 >= step.refund)
                            val fresh = projector.snapshot()
                            val shown = disclosed(fresh, actor)
                            verify("$label.disclosedWithUpgradeLevel", fresh.tick == engine.currentTick && shown.controllable &&
                                shown.typeName == step.target && shown.upgradeLevel == expectedLevel && shown.buildProgress >= 1f)
                        } catch (error: Throwable) {
                            stepFailure = error
                            throw error
                        } finally {
                            steps += buildJsonObject {
                                put("target", step.target); put("expectedUpgradeLevel", expectedLevel)
                                put("expectedRefund", step.refund); put("before", before); put("after", state(actor))
                                put("passed", stepFailure == null); put("advancedTicks", advancedTicks)
                                put("completed", completed); put("observedQueue", observedQueue); put("observedCreditDebit", charged)
                                put("largestCreditIncrease", largestCreditIncrease)
                                put("creditChanges", JsonArray(creditChanges)); put("queueSamples", JsonArray(queueSamples))
                                panel?.let { p ->
                                    put("panelSequence", p.panelSequence); put("viewSequence", p.viewSequence)
                                    put("advertised", JsonArray(p.actions.map { a -> buildJsonObject {
                                        put("kind", a.kind.name); put("type", a.producedTypeKey); put("key", a.presentationKey)
                                        put("upfrontCredits", a.upfrontCost?.credits); put("streamingCredits", a.streamingCost?.credits)
                                    } }))
                                }
                                action?.let { put("selectedPresentation", it.presentationKey) }
                                stepFailure?.let { put("error", it.toString()) }
                            }
                        }
                    }
                    verify("${chain.name}.chainCompleted", steps.size == chain.steps.size)
                } catch (error: Throwable) {
                    chainFailure = error; failures += error
                    checks["${chain.name}.chainCompleted"] = false
                } finally {
                    reports += buildJsonObject {
                        put("chain", chain.name); put("initialType", chain.initial); put("native", chain.native)
                        put("passed", chainFailure == null); put("placement", placement ?: JsonNull)
                        put("placementAttempts", JsonArray(placementAttempts))
                        put("steps", JsonArray(steps)); chainFailure?.let { put("error", it.toString()) }
                    }
                    forgetOwnCommands()
                    ownUnits.toList().forEach(::remove)
                }
            }
            check(failures.isEmpty()) { "${failures.size} upgrade chains failed; see the recorded per-chain evidence" }
        } catch (error: Throwable) {
            failure = error
        } finally {
            forgetOwnCommands()
            ownUnits.toList().forEach(::remove)
            GameObject.dL()
        }
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(buildJsonObject {
            put("protocolVersion", TimelineProtocol.VERSION); put("startTick", startTick); put("endTick", engine.currentTick)
            val completed = failure == null && reports.size == chains.size && checks.isNotEmpty() && checks.values.all { it }
            put("completed", completed); put("passed", completed)
            put("checks", JsonObject(checks.mapValues { JsonPrimitive(it.value) }))
            put("chains", JsonArray(reports))
            put("scope", "Real stock upgrade queues, costs, refunds, conversions and resulting seat views; not exhaustive standard-action or GUI acceptance")
            failure?.let { put("error", it.toString()) }
        }.toString() + "\n")
        failure?.let { error -> failures.filter { it !== error }.forEach(error::addSuppressed); throw error }
        return checks
    }
}
