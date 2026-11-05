package io.github.rwx.session

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.CustomUnitConfigParser
import com.corrodinggames.rts.game.units.custom.condition.resources.Resource
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.mod.ModManager
import com.corrodinggames.rts.gameFramework.network.GameModeType
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import com.corrodinggames.rts.gameFramework.network.NetworkCallbacks
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.ui.GameUI
import com.corrodinggames.rts.gameFramework.ui.MessageManager
import com.corrodinggames.rts.gameFramework.ui.WarLogDisplay
import io.github.rwx.PREFERENCE_NAME
import io.github.rwx.PreferenceStorage
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.settings.MemoryPreferenceStorage
import io.github.rwx.ui.CoreUiEventQueue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.*

class LocalBattleRoomOptionsTest {
    @Test
    fun `cold UI room reads saved Medium difficulty after owner initializes the engine`() = withFixture { fixture ->
        val session = RecordingSession(fixture::createEngine)
        val config = config()
        assertNull(GameEngine.getInstance())

        assertTrue(session.enterLocalBattleRoomWithSavedDefaults(config))

        val engine = assertNotNull(fixture.engine)
        assertEquals(listOf("owner", "ensure", "read saved local AI difficulty", "explicit"),
            session.events.take(4))
        assertEquals(0, engine.networkEngine.roomSettings.aiDifficulty)
        assertEquals(0, engine.settingsEngine.aiDifficulty)
        assertEquals(1, config.room.options.aiDifficulty, "The UI draft must remain detached")
        assertNotSame(config.room.options, session.enteredConfig!!.room.options)
        assertEquals(1, session.engineStarts)
    }

    @Test
    fun `warm room installs configured Medium before refreshing and creating AI`() = withFixture { fixture ->
        val engine = fixture.createEngine()
        engine.networkEngine.roomSettings.aiDifficulty = 1
        val observedAiDifficulties = mutableListOf<Int>()
        engine.networkEngine.callbacks = object : NetworkCallbacks() {
            override fun a(team: PlayerTeam) {
                observedAiDifficulties += engine.networkEngine.roomSettings.aiDifficulty
                assertEquals(0, team.teamPingTime)
            }
        }
        val options = GameRoomSettings().apply { aiDifficulty = 0 }

        engine.configureLocalBattleRoom(config(options = options, aiCount = 2), "test")

        assertEquals(listOf(0, 0), observedAiDifficulties)
        assertEquals(0, engine.settingsEngine.aiDifficulty,
            "Server refresh must read the newly installed room options")
        assertEquals(listOf(0, 0), battleRoomPlayerTeams().filter { it.isTeamSpectator }.map { it.teamPingTime })
        assertEquals(listOf("AI - Medium", "AI - Medium"),
            battleRoomPlayerTeams().filter { it.isTeamSpectator }.map { it.teamName })
    }

    @Test
    fun `explicit launch keeps AI2 and every custom rule after server reset`() = withFixture { fixture ->
        val engine = fixture.createEngine()
        val session = RecordingSession(fixture::createEngine)
        val options = GameRoomSettings().apply {
            aiDifficulty = 2
            startingCredits = 7
            startingUnits = 4
            incomeMultiplier = 2.5f
            noNukes = true
            sharedControl = true
            j = true
            useDisplayedCostAsResourceCost = true
            teamLock = true
            fixedAllyTeams = true
            allowSpectators = false
            roomLock = true
            randomSeed = 12345
            fogMode = 1
            revealedMap = false
        }

        assertTrue(session.enterLocalBattleRoomLive(config(options = options)))

        val live = engine.networkEngine.roomSettings
        assertNotSame(options, live)
        assertEquals(2, live.aiDifficulty)
        assertEquals(2, engine.settingsEngine.aiDifficulty)
        assertEquals(7, live.startingCredits)
        assertEquals(4, live.startingUnits)
        assertEquals(2.5f, live.incomeMultiplier)
        assertTrue(live.noNukes)
        assertTrue(live.sharedControl)
        assertTrue(live.j)
        assertTrue(live.useDisplayedCostAsResourceCost)
        assertTrue(live.teamLock)
        assertTrue(live.fixedAllyTeams)
        assertFalse(live.allowSpectators)
        assertTrue(live.roomLock)
        assertEquals(12345, live.randomSeed)
        assertEquals(1, live.fogMode)
        assertFalse(live.revealedMap)
        assertFalse(session.events.contains("read saved local AI difficulty"))
    }

    @Test
    fun `selected map replaces stale option filename using existing custom-map rules`() {
        for ((path, mode, filename) in listOf(
            Triple("maps/skirmish/selected.tmx", GameModeType.skirmishMap, "selected.tmx"),
            Triple("/SD/rusted_warfare_maps/custom.tmx", GameModeType.customMap, "custom.tmx"),
            Triple("/SD/rusted_warfare_maps/MOD|mod-id/maps/map.tmx", GameModeType.customMap,
                "/MOD|mod-id/maps/map.tmx"),
        )) withFixture { fixture ->
            val engine = fixture.createEngine()
            val session = RecordingSession(fixture::createEngine)
            val options = GameRoomSettings()

            assertTrue(session.enterLocalBattleRoomLive(config(path, options)))

            assertEquals(path, engine.currentMapPath)
            assertEquals(path, engine.networkEngine.selectedMapPath)
            assertEquals(mode, engine.networkEngine.roomSettings.gameModeType)
            assertEquals(filename, engine.networkEngine.roomSettings.mapPath)
            assertEquals("[z;p10]Crossing Large (10p).tmx", options.mapPath)
        }
    }

    @Test
    fun `saved game keeps its full path and saved mode after server startup`() = withFixture { fixture ->
        val engine = fixture.createEngine()
        val options = GameRoomSettings().apply {
            gameModeType = GameModeType.savedGame
            aiDifficulty = 2
        }
        val path = "saves/selected save.rwsave"

        assertTrue(RecordingSession(fixture::createEngine).enterLocalBattleRoomLive(config(path, options)))

        assertEquals(GameModeType.savedGame, engine.networkEngine.roomSettings.gameModeType)
        assertEquals(path, engine.networkEngine.roomSettings.mapPath)
        assertEquals(path, engine.networkEngine.selectedMapPath)
        assertEquals(2, engine.networkEngine.roomSettings.aiDifficulty)
    }

    @Test
    fun `sandbox overrides fog only and leaves caller options detached`() = withFixture { fixture ->
        val engine = fixture.createEngine()
        val options = GameRoomSettings().apply {
            aiDifficulty = 2
            fogMode = 2
            revealedMap = false
            startingCredits = 5
            startingUnits = 3
            incomeMultiplier = 1.5f
            noNukes = true
        }

        assertTrue(RecordingSession(fixture::createEngine).enterLocalBattleRoomLive(config(options = options).copy(sandbox = true)))

        val live = engine.networkEngine.roomSettings
        assertTrue(engine.networkEngine.isSandboxMode)
        assertEquals(0, live.fogMode)
        assertTrue(live.revealedMap)
        assertEquals(2, live.aiDifficulty)
        assertEquals(5, live.startingCredits)
        assertEquals(3, live.startingUnits)
        assertEquals(1.5f, live.incomeMultiplier)
        assertTrue(live.noNukes)
        assertEquals(2, options.fogMode)
        assertFalse(options.revealedMap)
        options.startingCredits = 9
        assertEquals(5, live.startingCredits)
        live.startingUnits = 7
        assertEquals(3, options.startingUnits)
    }

    @Test
    fun `saved-default UI entry changes only AI and preserves other requested rules`() = withFixture { fixture ->
        val session = RecordingSession(fixture::createEngine)
        val options = GameRoomSettings().apply {
            aiDifficulty = 2
            startingCredits = 8
            startingUnits = 3
            incomeMultiplier = 1.75f
            noNukes = true
        }

        assertTrue(session.enterLocalBattleRoomWithSavedDefaults(config(options = options)))

        val live = fixture.engine!!.networkEngine.roomSettings
        assertEquals(0, live.aiDifficulty)
        assertEquals(8, live.startingCredits)
        assertEquals(3, live.startingUnits)
        assertEquals(1.75f, live.incomeMultiplier)
        assertTrue(live.noNukes)
        assertEquals(2, options.aiDifficulty)
    }

    private fun config(
        path: String = "maps/skirmish/selected.tmx",
        options: GameRoomSettings = GameRoomSettings(),
        aiCount: Int = 0,
    ) = BattleRoomLaunchConfig(aiPlayerCount = aiCount, room = BattleRoomCoreConfig(path, options))

    private class RecordingSession(private val start: () -> GameEngine) : GameSession() {
        override val rendererMode = object : RendererMode { override val id = "local-room-test" }
        val events = mutableListOf<String>()
        var engineStarts = 0
        var enteredConfig: BattleRoomLaunchConfig? = null
        private var ownerDepth = 0

        override fun <T> executeEngineTask(label: String, action: () -> T): T {
            events += "owner"
            ownerDepth++
            return try { action() } finally { ownerDepth-- }
        }

        override fun <T> runEngineCommand(label: String, command: (GameEngine) -> T?): T? {
            if (label == "read saved local AI difficulty") {
                assertTrue(ownerDepth > 0, "Saved preferences must be read on the engine owner")
                events += label
            }
            return super.runEngineCommand(label, command)
        }

        override fun enterLocalBattleRoomLive(config: BattleRoomLaunchConfig): Boolean {
            events += "explicit"
            enteredConfig = config
            return super.enterLocalBattleRoomLive(config)
        }

        override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine {
            events += "ensure"
            engineStarts++
            return start().also { gameEngine = it }
        }
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
        override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1, 1), emptyList())
    }

    /** Exercises the real server reset, room refresh, and AI creation, without loading assets or a window. */
    private class Fixture {
        var engine: GameLogic? = null
            private set

        fun createEngine(): GameLogic = engine ?: object : GameLogic() {
            override fun usesCoreUnitTypes() = false
        }.also { created ->
            created.settingsEngine = SettingsEngine::class.java.getDeclaredConstructor().run {
                isAccessible = true
                newInstance()
            }
            created.modManager = ModManager()
            created.networkEngine = NetworkEngine()
            // Server reset rebuilds every built-in action list even when this fixture has no
            // renderable unit prototypes. Its cost rules query isInGameOrLobby(), which requires
            // the same idle replay service that a started GameLogic owns.
            created.replayEngine = ReplayEngine()
            assertFalse(created.replayEngine.j())
            created.gameUI = GameUI().also { ui ->
                ui.messageManager = MessageManager::class.java.getDeclaredConstructor(GameEngine::class.java, GameUI::class.java).run {
                    isAccessible = true
                    newInstance(created, ui)
                }
                ui.warLogDisplay = WarLogDisplay(created)
            }
            created.currentMapPath = "maps/skirmish/selected.tmx"
            engine = created
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        // Initializing the legacy static team/config types creates dummy units, whose constructors
        // read the engine clock. Give that class initialization its normal engine context before
        // snapshotting globals. The singleton is cleared below, so the cold-entry test still has to
        // create its own engine and load saved preferences through ensureStarted().
        if (previousEngine == null) GameLogic()
        val previousConfigs = CustomUnitConfig.allConfigs.toList()
        val previousActive = CustomUnitConfig.activeConfigs
        val previousSync = CustomUnitConfig.validUnitsForSync
        val previousPlaceholder = CustomUnitConfig.instance
        val previousConfigsById = CustomUnitConfig.configsById
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        val previousUnitTypes = UnitTypeEnum.ae
        val previousPrototypes = listOf(BaseUnit.bF, BaseUnit.bG, BaseUnit.bH)
        val teamFields = listOf("TEAM_NEUTRAL", "TEAM_ENEMIES", "teamColorArray", "teamInstances").map { name ->
            PlayerTeam::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val previousTeams = teamFields.map { field ->
            field.get(null).let { if (it is Array<*>) it.clone() else it }
        }
        val previousMaxRadius = CustomUnitConfigParser.maxUnitRadius
        val previousMaxBuildingRadius = CustomUnitConfigParser.maxBuildingRadius
        val activeResources = Resource::class.java.getDeclaredField("B").apply { isAccessible = true }
        val previousResources = activeResources.get(null)
        val allResources = Resource::class.java.getDeclaredField("A").apply { isAccessible = true }
            .get(null) as List<*>
        val previousResourceFlags = allResources.filterIsInstance<Resource>().map { it to it.a }
        val storage = MemoryPreferenceStorage().apply { preference(PREFERENCE_NAME).putInt("aiDifficulty", 0) }
        startKoin { modules(module { single<PreferenceStorage> { storage } }) }
        instance.set(null, null)
        CustomUnitConfig.allConfigs.clear()
        CustomUnitConfig.configsById = arrayListOf()
        try {
            test(Fixture())
        } finally {
            CoreUiEventQueue.drain {}
            CustomUnitConfig.allConfigs.clear()
            CustomUnitConfig.allConfigs.addAll(previousConfigs)
            CustomUnitConfig.activeConfigs = previousActive
            CustomUnitConfig.validUnitsForSync = previousSync
            CustomUnitConfig.instance = previousPlaceholder
            CustomUnitConfig.configsById = previousConfigsById
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            UnitTypeEnum.ae = previousUnitTypes
            BaseUnit.bF = previousPrototypes[0]
            BaseUnit.bG = previousPrototypes[1]
            BaseUnit.bH = previousPrototypes[2]
            CustomUnitConfigParser.maxUnitRadius = previousMaxRadius
            CustomUnitConfigParser.maxBuildingRadius = previousMaxBuildingRadius
            previousResourceFlags.forEach { (resource, active) -> resource.a = active }
            activeResources.set(null, previousResources)
            teamFields.zip(previousTeams).forEach { (field, value) -> field.set(null, value) }
            instance.set(null, previousEngine)
            stopKoin()
        }
    }
}
