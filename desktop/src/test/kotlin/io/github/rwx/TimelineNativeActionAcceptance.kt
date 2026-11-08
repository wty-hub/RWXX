package io.github.rwx

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.Projectile
import com.corrodinggames.rts.game.units.AttackMode
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitMovementType
import com.corrodinggames.rts.game.units.TransportUnitInterface
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.logic.CustomAction
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import io.github.rwx.net.authoritative.*
import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Opt-in real simulation checks. A successful apply alone is deliberately insufficient evidence. */
internal object TimelineNativeActionAcceptance {
    fun run(engine: GameEngine, publicMap: EnginePublicMapSnapshot, stock: StockUnitCatalog,
        closure: StockActionClosure, output: File): Map<String, Boolean> {
        val checks = linkedMapOf<String, Boolean>()
        val inventory = mutableListOf<JsonObject>()
        val diagnostics = linkedMapOf<String, JsonElement>()
        val adapter = StockActionAdapter(engine, stock, closure)
        val originalUnits = BaseUnit.getGlobalUnitList().toSet()
        val originalProjectiles = Projectile.a.toSet()
        var failure: Throwable? = null
        var completed = false
        fun verify(name: String, passed: Boolean) { checks[name] = passed; check(passed) { "Action acceptance failed: $name" } }
        try {
            val team = checkNotNull(PlayerTeam.k(0))
            team.credits = 1_000_000.0
            // Enumerate loaded identities rather than inferring runtime action sets from INI regexes.
            for (entry in stock.entries) {
                val unit = entry.type.a()
                try {
                    unit.setUnitTeam(team)
                    val entries = if (unit is OrderableUnit) adapter.describe(unit) else emptyList()
                    inventory += buildJsonObject {
                        put("type", entry.key)
                        put("orderable", unit is OrderableUnit)
                        put("actions", entries.size)
                        put("kinds", JsonArray(entries.map { JsonPrimitive(it.kind.name) }.distinct()))
                    }
                } finally { unit.removeFromGame() }
            }
            GameObject.dL()
            verify("loadedStockTypesEnumerated", inventory.size == stock.entries.size && inventory.size == 152)
            val map = engine.tileMap
            val clear = buildList {
                for (y in 8 until map.tileCountY - 8) for (x in 8 until map.tileCountX - 8) {
                    if ((-4..4).all { dx -> (-4..4).all { dy ->
                        !engine.pathfindingEngine.isTileBlockedForMovement(UnitMovementType.LAND, x + dx, y + dy)
                    } } && originalUnits.none { abs(it.posX - (x + .5f) * map.tileWorldSizeX) +
                        abs(it.posY - (y + .5f) * map.tileWorldSizeY) < 250f } &&
                        originalUnits.filter { it.team != null && it.team !== team && team.c(it.team) }.none {
                            val dx = it.posX - (x + .5f) * map.tileWorldSizeX
                            val dy = it.posY - (y + .5f) * map.tileWorldSizeY
                            dx * dx + dy * dy < 1_000_000f
                        }) add(x to y)
                }
            }
            val center = clear.minBy { abs(it.first - map.tileCountX / 4) + abs(it.second - map.tileCountY / 4) }
            fun spawn(key: String, dx: Float = 0f, dy: Float = 0f): OrderableUnit {
                val unit = checkNotNull(stock.typeFor(key)) { "Missing stock type $key" }.a() as OrderableUnit
                unit.posX = (center.first + .5f) * map.tileWorldSizeX + dx
                unit.posY = (center.second + .5f) * map.tileWorldSizeY + dy
                unit.setUnitTeam(team)
                unit.isActive = true
                unit.n()
                PlayerTeam.c(unit)
                engine.unitSpatialIndex.a(unit)
                GameObject.dL()
                return unit
            }
            if (engine.currentTick == 0) AuthoritativeDesktopMain.advance(engine)
            val projector = EngineViewProjector(engine, team, publicMap, stock, closure)
            var query = 0L
            var sequence = 0L
            fun entityHandle(view: PlayerViewSnapshot, actor: OrderableUnit): Long = view.units.single {
                it.teamId == actor.team.teamId && it.typeName == stock.keyFor(actor.r()) &&
                    it.x == actor.posX && it.y == actor.posY
            }.handle
            fun panel(actor: OrderableUnit): Pair<PlayerViewSnapshot, PlayerActionPanel> {
                val view = projector.snapshot()
                check(actor.team === team)
                return view to projector.actionPanel(ViewActionPanelQuery(++query, view.sequence, entityHandle(view, actor)))
            }
            fun send(actor: OrderableUnit, kind: ViewActionKind, produced: String? = null,
                x: Float = 0f, y: Float = 0f, target: OrderableUnit? = null, cancel: Boolean = false,
                presentationKey: String? = null, queued: Boolean = false): Boolean {
                val (view, panel) = panel(actor)
                val action = panel.actions.firstOrNull { it.kind == kind && (produced == null || it.producedTypeKey == produced) &&
                    (presentationKey == null || it.presentationKey == presentationKey) }
                    ?: error("Missing $kind/$produced on ${stock.keyFor(actor.r())}; actions=${panel.actions.map { it.kind to it.producedTypeKey }}")
                return projector.applyAction(ViewActionCommand(++sequence, view.sequence,
                    panel.panelSequence, panel.unitHandle, action.handle, when {
                        cancel || action.targetMode == ActionTargetMode.NONE -> CommandTarget.None
                        action.targetMode == ActionTargetMode.POINT -> CommandTarget.Point(x, y)
                        target != null -> CommandTarget.Unit(entityHandle(view, target))
                        else -> CommandTarget.None
                    }, queued = queued, cancel = cancel))
            }
            fun advanceUntil(limit: Int = 12_000, done: () -> Boolean): Boolean {
                repeat(limit) {
                    AuthoritativeDesktopMain.advance(engine)
                    if (done()) return true
                }
                return false
            }

            val factory = spawn("landFactory")
            val (_, firstPanel) = panel(factory)
            verify("factoryAdvertisesProductionUpgradeAndRally", firstPanel.actions.any { it.kind == ViewActionKind.PRODUCE } &&
                firstPanel.actions.any { it.kind == ViewActionKind.UPGRADE } && firstPanel.actions.any { it.kind == ViewActionKind.RALLY })
            verify("factoryUpgradeAccepted", send(factory, ViewActionKind.UPGRADE))
            verify("factoryActuallyUpgrades", advanceUntil { factory.upgradeLevel == 2 })
            val (_, upgradedPanel) = panel(factory)
            verify("upgradeChangesAdvertisedProduction", upgradedPanel.actions.count { it.kind == ViewActionKind.PRODUCE } >
                firstPanel.actions.count { it.kind == ViewActionKind.PRODUCE })

            verify("productionAcceptedThroughCapability", send(factory, ViewActionKind.PRODUCE, "tank"))
            AuthoritativeDesktopMain.advance(engine)
            val queuedPanel = panel(factory).second
            val queuedAction = queuedPanel.actions.single { it.kind == ViewActionKind.PRODUCE && it.producedTypeKey == "tank" }
            verify("realQueueCountAndCancelAdvertised", queuedAction.queuedCount > 0 && queuedAction.canCancel)
            team.credits = 0.0
            verify("cancelAcceptedWithNoFunds", send(factory, ViewActionKind.PRODUCE, "tank", cancel = true))
            AuthoritativeDesktopMain.advance(engine)
            val cancelled = panel(factory).second.actions.single { it.kind == ViewActionKind.PRODUCE && it.producedTypeKey == "tank" }
            verify("engineCancelsAndRefunds", cancelled.queuedCount == 0 && team.credits > 0.0)
            team.credits = 1_000_000.0
            verify("rallyAccepted", send(factory, ViewActionKind.RALLY, x = factory.posX + 100f, y = factory.posY))
            AuthoritativeDesktopMain.advance(engine)
            val priorTanks = BaseUnit.getGlobalUnitList().filter { it.r() === stock.typeFor("tank") }.toSet()
            verify("secondProductionAccepted", send(factory, ViewActionKind.PRODUCE, "tank"))
            verify("engineProducesEffectiveStockTank", advanceUntil { BaseUnit.getGlobalUnitList().any {
                it.team === team && it.r() === stock.typeFor("tank") && it !in priorTanks && !it.isDead
            } })
            val produced = BaseUnit.getGlobalUnitList().filterIsInstance<OrderableUnit>().single {
                it.team === team && it.r() === stock.typeFor("tank") && it !in priorTanks && !it.isDead }
            verify("engineAppliesRallyToProducedTank", produced.lastWaypoint?.let {
                it.targetX == factory.posX + 101f && it.targetY == factory.posY } == true)

            val airSite = clear.filter { point -> BaseUnit.getGlobalUnitList().none {
                val dx = it.posX - (point.first + .5f) * map.tileWorldSizeX
                val dy = it.posY - (point.second + .5f) * map.tileWorldSizeY
                dx * dx + dy * dy < 32_400f
            } }.minBy { abs(it.first - center.first) + abs(it.second - center.second) }
            val airX = (airSite.first + .5f) * map.tileWorldSizeX
            val airY = (airSite.second + .5f) * map.tileWorldSizeY
            val airBuilder = spawn("builder", (airSite.first - center.first - 4) * map.tileWorldSizeX.toFloat(),
                (airSite.second - center.second) * map.tileWorldSizeY.toFloat())
            val beforeAirFactory = BaseUnit.getGlobalUnitList().toSet()
            verify("newCatalogBuildingOutsideLegacyThreeTypeListAccepted", send(airBuilder, ViewActionKind.BUILD,
                "airFactory", airX, airY))
            var priorCredits = team.credits
            var charged = false
            val airCompleted = advanceUntil(6000) {
                if (team.credits < priorCredits - .00001) charged = true
                priorCredits = team.credits
                BaseUnit.getGlobalUnitList().any { it !in beforeAirFactory && it.team === team &&
                    it.r() === stock.typeFor("airFactory") && !it.isDead && it.buildProgress >= 1f }
            }
            verify("engineBuildsCatalogAirFactoryAndChargesResources", airCompleted && charged)

            val turret = spawn("turret", 180f, 0f)
            verify("stockTurretUpgradeAdvertisedAndAccepted", send(turret, ViewActionKind.UPGRADE, "turretT2"))
            val converted = advanceUntil { stock.keyFor(turret.r()) == "turretT2" }
            if (!converted) diagnostics["turret"] = buildJsonObject {
                put("type", stock.keyFor(turret.r()))
                put("tick", engine.currentTick); put("active", turret.isActive)
                put("buildProgress", turret.buildProgress); put("dead", turret.isDead)
                put("registered", turret in BaseUnit.getGlobalUnitList())
                val queue = turret as FactoryQueueInterface
                put("queueSize", queue.dx().size)
                put("progress", queue.dw()?.m)
                put("credits", team.credits)
            }
            verify("stockTurretActuallyConverts", converted)
            verify("convertedTurretRemainsDisclosed", projector.snapshot().units.any { it.typeName == "turretT2" && it.teamId == 0 })

            val builder = spawn("builder", -100f, 0f)
            // The stock land builder repairs buildings; mobile units require the repair bay.
            val damaged = spawn("turret", -75f, 0f)
            damaged.currentHealth = damaged.maxHealth / 2f
            val health = damaged.currentHealth
            verify("repairAccepted", send(builder, ViewActionKind.REPAIR, target = damaged))
            verify("engineActuallyRepairs", advanceUntil(1200) { damaged.currentHealth > health + 1f })
            verify("reclaimAccepted", send(builder, ViewActionKind.RECLAIM, target = damaged))
            verify("engineActuallyReclaims", advanceUntil(3000) { damaged.isDead || damaged.isDestroyed || damaged.currentHealth < health })
            engine.commandController.clearAllCommands()
            builder.clearAllWaypoints()
            val resource = spawn("creditsCrates", -30f, 60f)
            resource.setUnitTeam(PlayerTeam.TEAM_NULL)
            engine.unitSpatialIndex.a(resource)
            val resourceHealth = resource.currentHealth
            verify("visibleNeutralResourceReclaimAccepted", send(builder, ViewActionKind.RECLAIM, target = resource))
            verify("engineActuallyReclaimsNeutralResource", advanceUntil(3000) {
                resource.isDead || resource.isDestroyed || resource.currentHealth < resourceHealth })
            engine.commandController.clearAllCommands()
            builder.clearAllWaypoints()

            // Keep both positions inside the nine-by-nine region proven to be land.
            val carrier = spawn("hovercraft", -40f, -50f)
            val passenger = spawn("builder", -15f, -50f)
            verify("transportLoadAccepted", send(carrier, ViewActionKind.LOAD_UP, target = passenger))
            verify("engineActuallyLoads", advanceUntil(3000) { passenger.transportContainer === carrier })
            verify("loadedPassengerIsNotDisclosed", projector.snapshot().units.none {
                it.x == passenger.posX && it.y == passenger.posY && it.typeName == "builder" })
            verify("transportUnloadAccepted", send(carrier, ViewActionKind.UNLOAD))
            AuthoritativeDesktopMain.advance(engine)
            val transport = carrier as TransportUnitInterface
            verify("engineStartsUnloading", transport.isTransportUnloadingActive)
            verify("transportStopUnloadAccepted", send(carrier, ViewActionKind.STOP_UNLOAD))
            AuthoritativeDesktopMain.advance(engine)
            verify("engineStopsUnloadWithoutDroppingPassenger", !transport.isTransportUnloadingActive && passenger.transportContainer === carrier)
            verify("transportResumeUnloadAccepted", send(carrier, ViewActionKind.UNLOAD))
            verify("engineActuallyUnloads", advanceUntil(3000) { passenger.transportContainer == null })
            repeat(2) { AuthoritativeDesktopMain.advance(engine) }
            verify("passengerLoadIntoAccepted", send(passenger, ViewActionKind.LOAD_INTO, target = carrier))
            verify("engineLoadsFromPassengerDirection", advanceUntil(3000) { passenger.transportContainer === carrier })

            val holdFire = panel(produced).second.actions.single {
                it.kind == ViewActionKind.ATTACK_MODE && it.presentationKey.endsWith(".holdFire") }
            verify("attackModeAccepted", send(produced, ViewActionKind.ATTACK_MODE, presentationKey = holdFire.presentationKey))
            AuthoritativeDesktopMain.advance(engine)
            verify("engineChangesAttackMode", produced.attackMode == AttackMode.holdFire)
            verify("guardAccepted", send(produced, ViewActionKind.GUARD, target = carrier))
            AuthoritativeDesktopMain.advance(engine)
            verify("engineGuardsSelectedFriendly", produced.currentWaypoint?.targetUnit === carrier)
            val patrolX = produced.posX + 150f
            val patrolY = produced.posY
            verify("patrolAccepted", send(produced, ViewActionKind.PATROL, x = patrolX, y = patrolY))
            AuthoritativeDesktopMain.advance(engine)
            verify("queuedPatrolAccepted", send(produced, ViewActionKind.PATROL, x = patrolX - 300f, y = patrolY, queued = true))
            AuthoritativeDesktopMain.advance(engine)
            val patrolPoints = (0 until produced.waypointCount).map { produced.getWaypointAt(it).targetX }
            verify("engineAppendsPatrolInsteadOfReplacing", patrolX in patrolPoints && patrolX - 300f in patrolPoints)
            produced.clearAllWaypoints()

            val bunker = spawn("mechBunker", 120f, -120f)
            verify("stockDeployAccepted", send(bunker, ViewActionKind.UPGRADE, "mechBunkerDeployed"))
            verify("engineDeploysStockMech", advanceUntil(1200) { stock.keyFor(bunker.r()) == "mechBunkerDeployed" })
            verify("stockUndeployAccepted", send(bunker, ViewActionKind.UPGRADE, "mechBunker"))
            verify("engineUndeploysStockMech", advanceUntil(1200) { stock.keyFor(bunker.r()) == "mechBunker" })

            val spider = spawn("modularSpider", 0f, 180f) as CustomUnit
            verify("engineCreatesSixModularSlots", advanceUntil(120) { spider.C?.size == 6 })
            val selectSlot = adapter.describe(spider).single {
                (it.action as? CustomAction)?.actionDef?.displayName == "selectSlot1" }
            verify("parentSlotSelectionAccepted", send(spider, selectSlot.kind,
                presentationKey = "authority.action.${selectSlot.key}"))
            verify("engineSelectsParentSlot", advanceUntil(120) { spider.unitFlags and (1 shl 1) != 0 })
            fun slotUnit(): CustomUnit = spider.a(spider.unitConfig.attachmentSlotDefinitions.first { it.b() == "unitSlot1" }) as CustomUnit
            verify("moduleBuildThroughParentAccepted", send(spider, ViewActionKind.UPGRADE, "modularSpider_gunturret"))
            verify("engineStartsModuleConstruction", advanceUntil(120) {
                stock.keyFor(slotUnit().r()) == "modularSpider_gunturret" && slotUnit().buildProgress < 1f })
            val incompletePanel = panel(spider).second
            val moduleButtons = incompletePanel.actions.filter { it.presentationKey.startsWith("module.slot1.") }
            verify("incompleteModuleOnlyAdvertisesConstructionCancel", moduleButtons.size == 1)
            val cancelConstruction = moduleButtons.single()
            val creditsAfterPurchase = team.credits
            verify("moduleConstructionCancelThroughParentAccepted", send(spider, cancelConstruction.kind,
                presentationKey = cancelConstruction.presentationKey))
            verify("engineCancelsModuleConstructionAndRefunds", advanceUntil(120) {
                stock.keyFor(slotUnit().r()) == "modularSpider_emptySlot" && team.credits >= creditsAfterPurchase + 1999.0 })
            verify("moduleCooldownBlocksImmediateRebuild", !send(spider, ViewActionKind.UPGRADE, "modularSpider_gunturret"))
            repeat(150) { AuthoritativeDesktopMain.advance(engine) }
            verify("secondModuleBuildThroughParentAccepted", send(spider, ViewActionKind.UPGRADE, "modularSpider_gunturret"))
            verify("engineCompletesModuleConstruction", advanceUntil(3000) {
                stock.keyFor(slotUnit().r()) == "modularSpider_gunturret" && slotUnit().buildProgress >= 1f })
            verify("moduleRemainsPrivateEntityBehindParentHandle", projector.snapshot().units.none {
                it.typeName.startsWith("modularSpider_") && it.typeName != "modularSpider_nonEmpty" })
            verify("moduleReclaimThroughParentAccepted", send(spider, ViewActionKind.UPGRADE, "modularSpider_emptySlot"))
            verify("engineReclaimsParentModule", advanceUntil(600) { stock.keyFor(slotUnit().r()) == "modularSpider_emptySlot" })

            val nuke = spawn("NukeLaucher", 160f, 160f)
            verify("stockNukeProductionAccepted", send(nuke, ViewActionKind.PRODUCE))
            verify("engineBuildsStockNuclearAmmo", advanceUntil(6000) { nuke.ammo > 0 })
            val beforeLaunch = panel(nuke)
            val destination = buildList {
                for (y in 2 until map.tileCountY - 2) for (x in 2 until map.tileCountX - 2) {
                    val px = (x + .5f) * map.tileWorldSizeX
                    val py = (y + .5f) * map.tileWorldSizeY
                    if (beforeLaunch.first.fog[y * map.tileCountX + x] >= 5 &&
                        !engine.pathfindingEngine.isTileBlockedForMovement(UnitMovementType.LAND, x, y) &&
                        BaseUnit.getGlobalUnitList().none {
                            val dx = it.posX - px; val dy = it.posY - py
                            dx * dx + dy * dy < 250_000f
                        }) add(px to py)
                }
            }.maxBy { (it.first - nuke.posX).let { dx -> dx * dx } + (it.second - nuke.posY).let { dy -> dy * dy } }
            val hiddenTarget = checkNotNull(stock.typeFor("tank")).a() as OrderableUnit
            hiddenTarget.posX = destination.first; hiddenTarget.posY = destination.second
            hiddenTarget.setUnitTeam(checkNotNull(PlayerTeam.k(1)))
            hiddenTarget.isActive = true; hiddenTarget.n(); PlayerTeam.c(hiddenTarget)
            engine.unitSpatialIndex.a(hiddenTarget); GameObject.dL()
            verify("nuclearDestinationHasNoDisclosedEnemyHandle", projector.snapshot().units.none {
                it.teamId == 1 && it.x == hiddenTarget.posX && it.y == hiddenTarget.posY })
            val targetHealth = hiddenTarget.currentHealth
            verify("stockNukeGroundIntentIntoFogAccepted", send(nuke, ViewActionKind.TARGET_GROUND,
                x = destination.first, y = destination.second))
            verify("engineLaunchesStockNuclearProjectile", advanceUntil(600) { Projectile.a.any { it.sourceUnit === nuke } })
            verify("engineConsumesNuclearAmmo", nuke.ammo == 0)
            verify("engineNuclearGroundImpactDamagesHiddenTarget", advanceUntil(3000) {
                hiddenTarget.isDead || hiddenTarget.currentHealth < targetHealth })

            // Selection, capability publication and RALLY cross the actual v7 timeline transport.
            diagnostics["timelineRally"] = timelineRally(engine, team, factory, publicMap, stock, closure, output, ::verify)
            val previous = BaseUnit.getGlobalUnitList().toSet()
            verify("productionAfterTcpRallyAccepted", send(factory, ViewActionKind.PRODUCE, "tank"))
            verify("engineProducesAfterTcpRally", advanceUntil { BaseUnit.getGlobalUnitList().any {
                it.team === team && it.r() === stock.typeFor("tank") && it !in previous && !it.isDead
            } })
            val afterTcp = BaseUnit.getGlobalUnitList().filterIsInstance<OrderableUnit>().single {
                it.team === team && it.r() === stock.typeFor("tank") && it !in previous && !it.isDead }
            verify("tcpRallyReachesRealEngineProduction", afterTcp.lastWaypoint?.let {
                it.targetX == factory.posX + 61f && it.targetY == factory.posY } == true)
            completed = true
        } catch (error: Throwable) { failure = error }
        finally {
            engine.commandController.clearAllCommands()
            Projectile.a.filter { it !in originalProjectiles }.forEach { it.remove() }
            BaseUnit.getGlobalUnitList().filter { it !in originalUnits }.forEach { it.removeFromGame() }
            GameObject.dL()
        }
        output.parentFile?.mkdirs()
        output.writeText(buildJsonObject {
            put("protocolVersion", TimelineProtocol.VERSION)
            put("completed", completed)
            put("passed", completed && failure == null && checks.isNotEmpty() && checks.values.all { it })
            put("checks", JsonObject(checks.mapValues { JsonPrimitive(it.value) }))
            put("loadedTypeInventory", JsonArray(inventory))
            put("diagnostics", JsonObject(diagnostics))
            put("unsupportedActions", JsonArray(adapter.unsupportedActions().map { item -> buildJsonObject {
                put("type", item.actorKey); put("ordinal", item.ordinal); put("class", item.actionClass); put("reason", item.reason)
            } }))
            put("closureFailures", JsonArray(closure.failures().map { JsonPrimitive(it.toString()) }))
            put("scope", "Real engine action effects plus a real v7 timeline selection/capability/RALLY command and resulting produced-unit waypoint; not complete standard-action or native GUI acceptance")
            failure?.let { put("error", it.toString()) }
        }.toString() + "\n")
        failure?.let { throw it }
        return checks
    }

    /** All engine work remains on this caller; publication, archive and socket work use production workers. */
    private fun timelineRally(engine: GameEngine, team: PlayerTeam, factory: OrderableUnit,
        publicMap: EnginePublicMapSnapshot, stock: StockUnitCatalog, closure: StockActionClosure,
        output: File, verify: (String, Boolean) -> Unit): JsonObject {
        val random = SecureRandom()
        fun id() = TimelineBytes(ByteArray(16).also(random::nextBytes))
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val start = engine.currentTick.toLong() * TimelineProtocol.SUBDIVISIONS
        val seat = TimelineSeat(team.teamId, id(), joinedAt = start, historyFrom = start)
        val mapRecord = EngineTimelineProjector.mapRecord(engine, publicMap)
        val match = id()
        val context = TimelineContext(match, seat.accessId, TimelineCanonical.mapKey(mapRecord), mapRecord,
            team.teamId, 60, joinedAt = start, historyFrom = start)
        val projection = EngineTimelineProjector(engine, team, publicMap, stock, closure,
            status = { EngineMatchStatus.current() })
        val clock = AtomicLong(start)
        val listener = AtomicReference<TimelineServer?>()
        val archive = Files.createTempDirectory(output.absoluteFile.parentFile.toPath(), "timeline-rally-")
            .resolve("seat-${team.teamId}.rwxr")
        val session = TimelineSession(context, archive, onPublished = { listener.get()?.signal(team.teamId) })
        fun capture() {
            clock.set(engine.currentTick.toLong() * TimelineProtocol.SUBDIVISIONS)
            check(session.offer(projection.observe())) { "Timeline observation rejected: ${session.failure}" }
        }
        var report: JsonObject? = null
        try {
            capture()
            TimelineServer(InetAddress.getLoopbackAddress(), 0, mapOf(token to seat), mapRecord, match,
                session, clock::get, historyUntil = { session.archivedUntil }).use { server ->
                listener.set(server)
                TimelineClient("127.0.0.1", server.localPort, "Native rally fixture", token).use { client ->
                    fun await(condition: () -> Boolean) = waitUntil {
                        check(client.failure == null) { "Timeline client failed: ${client.failure}" }
                        check(session.failure == null) { "Timeline session failed: ${session.failure}" }
                        condition()
                    }
                    client.openTimeline(1)
                    await { client.latestStates().size == 1 }
                    val first = client.latestStates().values.single()
                    val actor = first.objects.values.single { obj ->
                        obj.kind == TimelineObjectKind.UNIT && obj.close == null &&
                            (TimelineProfile.sample(first, obj.handle, 6, first.header.observedThrough) as? TimelineValue.SInt)?.value == team.teamId.toLong() &&
                            (TimelineProfile.sample(first, obj.handle, 7, first.header.observedThrough) as? TimelineValue.Blob)?.value
                                ?.toByteArray()?.toString(Charsets.UTF_8) == "landFactory" &&
                            (TimelineProfile.sample(first, obj.handle, 1, first.header.observedThrough) as? TimelineValue.Vec3)?.let {
                                it.x == Math.round(factory.posX.toDouble() * 256) && it.y == Math.round(factory.posY.toDouble() * 256)
                            } == true
                    }
                    client.select(selection(first, actor.handle))
                    var selected = false
                    await {
                        server.drainRequests { request ->
                            check(request.frame.type == 6) { "Unexpected input before selection" }
                            selected = projection.apply(request, session::unitKey, session::actionKey)
                            if (selected) capture()
                        }
                        selected
                    }
                    verify("timelineFactorySelectionAccepted", selected)
                    var rallyAction: TimelineObject? = null
                    await {
                        val state = client.latestStates().values.single()
                        rallyAction = state.objects.values.singleOrNull { obj -> obj.kind == TimelineObjectKind.ACTION && obj.close == null &&
                            obj.descriptor.number(2) == actor.handle && obj.descriptor.record(4).number(1) == 6L &&
                            obj.descriptor.record(4).number(2) == 1L }
                        rallyAction != null
                    }
                    verify("timelineRallyCapabilityPublished", rallyAction != null)
                    val seen = client.latestStates().values.single()
                    client.sendCommand(rally(seen, actor.handle, checkNotNull(rallyAction).handle,
                        Math.round((factory.posX + 60f).toDouble() * 256), Math.round(factory.posY.toDouble() * 256)))
                    var applied = false
                    await {
                        server.drainRequests { request ->
                            check(request.frame.type == 5) { "Unexpected input after selection" }
                            applied = projection.apply(request, session::unitKey, session::actionKey)
                        }
                        applied
                    }
                    verify("tcpActionPanelAndExecutionAccepted", applied)
                    AuthoritativeDesktopMain.advance(engine)
                    capture()
                    await { client.latestStates().values.single().header.observedThrough == clock.get() }
                    val stats = client.wireStatistics()
                    verify("timelineRallyNoStateMismatch", stats.stateMismatches == 0L)
                    report = buildJsonObject {
                        put("protocolVersion", TimelineProtocol.VERSION); put("selectionApplied", selected)
                        put("rallyCapabilityReceived", true); put("rallyApplied", applied)
                        put("startTick", start / TimelineProtocol.SUBDIVISIONS); put("endTick", engine.currentTick)
                        put("transactions", stats.transactions); put("receivedBytes", stats.receivedBytes)
                        put("writtenBytes", stats.writtenBytes); put("stateMismatches", stats.stateMismatches)
                        put("archive", archive.toString())
                    }
                }
            }
            listener.set(null)
            session.finishAsync().get(10, TimeUnit.SECONDS)
            check(session.failure == null) { "Timeline rally archive failed: ${session.failure}" }
            verify("timelineRallySessionFinished", Files.isRegularFile(archive))
        } finally {
            listener.set(null)
            session.close()
            session.stoppedAsync().get(5, TimeUnit.SECONDS)
        }
        return checkNotNull(report)
    }

    private fun selection(state: TimelineState, actor: Long): TimelineFrame {
        fun n(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
        val selected = ByteArrayOutputStream().apply { TimelineBinary.writeVUInt(this, 1); TimelineBinary.writeVUInt(this, actor) }
        return TimelineFrame(6, TimelineRecord(listOf(n(1, state.header.streamId, true), n(2, state.header.epoch), n(3, 1),
            n(4, state.header.revision), TimelineMessages.bytesField(5, TimelineBytes(selected.toByteArray())))))
    }

    private fun rally(state: TimelineState, actor: Long, action: Long, x: Long, y: Long): TimelineFrame {
        fun n(id: Int, value: Long, u32: Boolean = false) = TimelineMessages.numberField(id, value, u32)
        val selected = ByteArrayOutputStream().apply { TimelineBinary.writeVUInt(this, 1); TimelineBinary.writeVUInt(this, actor) }
        val target = TimelineRecord(listOf(n(1, 1, true), TimelineField(2, true, TimelineTlvValue.I64(x)),
            TimelineField(3, true, TimelineTlvValue.I64(y))))
        return TimelineFrame(5, TimelineRecord(listOf(n(1, state.header.streamId, true), n(2, state.header.epoch), n(3, 1),
            n(4, state.header.revision), n(5, state.header.controlEpoch), n(6, 5, true),
            TimelineMessages.bytesField(7, TimelineBytes(selected.toByteArray())), n(8, action),
            TimelineMessages.recordField(9, target), n(10, 0, true))))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for action transport" }
            Thread.sleep(5)
        }
    }
}
