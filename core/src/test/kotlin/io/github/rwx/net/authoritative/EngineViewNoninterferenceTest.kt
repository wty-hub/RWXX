package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.land.TankUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.graphics.Texture
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.timeline.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks the observable wire output, rather than reconstructing the projection algorithm. */
class EngineViewNoninterferenceTest {
    private class TestTank : TankUnit(true) {
        override fun a(texture: Texture?, frames: Int) { T(20); U(20) }
    }

    private data class World(val engine: GameLogic, val player: GameTeam, val enemy: GameTeam,
        val own: TestTank, val visibleEnemy: TestTank, val publicMap: EnginePublicMapSnapshot,
        val stock: StockUnitCatalog, val closure: StockActionClosure) {
        fun stream() = EngineTimelineTestStream(EngineTimelineProjector(engine, player, publicMap, stock,
            closure, { EngineMatchStatus.current() }), engineTimelineTestContext(engine, publicMap))
    }

    private fun withWorld(block: (World) -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val registry = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null)
        GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        val previousTeams = registry.get(null) as Array<*>
        val previousUnits = BaseUnit.getGlobalUnitList().toList()
        val previousConfigs = CustomUnitConfig.activeConfigs
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        CustomUnitConfig.activeConfigs = arrayListOf()
        CustomUnitConfig.unitTypeOverrides.clear()
        registry.set(null, arrayOfNulls<PlayerTeam>(previousTeams.size))
        BaseUnit.getGlobalUnitList().clear()
        try {
            engine.apply {
                networkEngine = NetworkEngine().apply { roomSettings.fogMode = 2 }
                commandController = CommandController()
                replayEngine = ReplayEngine()
                tileMap = PublicMapFixture.flat(64, 64).apply {
                    tileCountX = 64; tileCountY = 64
                    tileWorldSizeX = 20; tileWorldSizeY = 20
                    tileScaleX = .05f; tileScaleY = .05f
                    fogEnabled = true; fogPeriodicMaintenanceEnabled = true
                }
            }
            val player = GameTeam(0).apply {
                teamColorId = 0
                credits = 1600.0
                fogOfWarData = Array(64) { ByteArray(64) { 10 } }
                fogOfWarData[0][0] = 0
            }
            val enemy = GameTeam(1).apply { teamColorId = 1 }
            engine.playerTeam = player
            val own = TestTank().apply { team = player; posX = 5f; posY = 5f }
            val visibleEnemy = TestTank().apply { team = enemy; posX = 15f; posY = 15f }
            BaseUnit.getGlobalUnitList().addAll(listOf(own, visibleEnemy))
            val map = EnginePublicMapSnapshot.capture(engine.tileMap)
            val stock = StockUnitCatalog.capture(engine)
            block(World(engine, player, enemy, own, visibleEnemy, map, stock, StockActionClosure.capture(engine, stock)))
        } finally {
            BaseUnit.getGlobalUnitList().clear()
            previousUnits.forEach { BaseUnit.getGlobalUnitList().add(it) }
            CustomUnitConfig.activeConfigs = previousConfigs
            CustomUnitConfig.unitTypeOverrides.clear()
            CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            registry.set(null, previousTeams)
            instance.set(null, previousEngine)
            GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    @Test
    fun `arbitrary changes to undisclosed world do not alter disclosed wire bytes`() = withWorld { world ->
        val (engine, player, enemy, own, visibleEnemy) = world
        fun encodeView() = world.stream().capture(0)
        val baseline = encodeView()
        val random = Random(0x51_67_48)
        repeat(64) { round ->
            val hidden = List(random.nextInt(1, 257)) {
                TestTank().apply {
                    team = enemy
                    posX = random.nextDouble(800.0, 1279.0).toFloat()
                    posY = random.nextDouble(800.0, 1279.0).toFloat()
                    objectId = random.nextLong()
                    rotationSpeed = random.nextFloat() * 360f
                    currentHealth = random.nextFloat() * maxHealth
                    buildProgress = random.nextFloat()
                    isDead = random.nextBoolean()
                }
            }
            // Different engine IDs, hidden targets and enemy finances must not enter a public DTO.
            own.objectId = random.nextLong()
            visibleEnemy.objectId = random.nextLong()
            own.targetUnit = hidden.first()
            own.attackTargetUnit = hidden.last()
            enemy.credits = random.nextDouble(0.0, 1_000_000.0)
            // Legacy rendering fog is not authoritative: even an all-revealed or stale matrix
            // must not broaden the independently computed current line of sight.
            player.fogOfWarData.forEach { column ->
                for (index in column.indices) column[index] = if (round % 2 == 0) 0 else random.nextInt(11).toByte()
            }
            BaseUnit.getGlobalUnitList().clear()
            BaseUnit.getGlobalUnitList().addAll((hidden + listOf(own, visibleEnemy)).shuffled(random))
            assertSamePublication(baseline, encodeView(), "Hidden world mutation at round $round")
        }
    }

    @Test
    fun `full state digest excludes hidden births deaths finances and target references`() = withWorld { world ->
        val (engine, player, enemy, own, visibleEnemy) = world
        fun authorized() = world.stream().capture(0).state
        val baseline = TimelineCanonical.digest(authorized()).toByteArray()
        assertEquals(16, baseline.size)
        val random = Random(0x43_16_79)
        repeat(32) { round ->
            val hidden = List(random.nextInt(1, 129)) {
                TestTank().apply {
                    team = enemy
                    posX = random.nextDouble(900.0, 1279.0).toFloat()
                    posY = random.nextDouble(900.0, 1279.0).toFloat()
                    objectId = random.nextLong()
                    currentHealth = random.nextFloat() * maxHealth
                    buildProgress = random.nextFloat()
                    isDead = random.nextBoolean()
                }
            }
            own.objectId = random.nextLong()
            visibleEnemy.objectId = random.nextLong()
            own.targetUnit = hidden.first()
            own.attackTargetUnit = hidden.last()
            enemy.credits = random.nextDouble(0.0, 1_000_000.0)
            player.fogOfWarData.forEach { it.fill(if (round % 2 == 0) 0.toByte() else 10.toByte()) }
            BaseUnit.getGlobalUnitList().clear()
            BaseUnit.getGlobalUnitList().addAll((hidden + listOf(own, visibleEnemy)).shuffled(random))
            assertContentEquals(baseline, TimelineCanonical.digest(authorized()).toByteArray(),
                "Complete authorized-state digest exposed hidden mutation at round $round")
        }
        own.currentHealth -= 1f
        assertTrue(!baseline.contentEquals(TimelineCanonical.digest(authorized()).toByteArray()),
            "The digest must still cover authorized public health changes")
    }

    @Test
    fun `persistent delta streams cannot distinguish hidden world changes or a hidden death`() = withWorld { world ->
        val (engine, player, enemy, own, visibleEnemy) = world
        val beforeStream = world.stream()
        val afterStream = world.stream()
        val random = Random(0x62_17_35)
        var enemyHandle = 0L
        var updateCount = 0
        var rememberedEnemyPosition: TimelineValue? = null

        fun hiddenWorld(round: Int, alternate: Boolean) {
            // These births, removals and dead units remain well outside either scout position.
            val hidden = List(random.nextInt(if (alternate) 65 else 0, if (alternate) 129 else 65)) {
                TestTank().apply {
                    team = enemy
                    posX = random.nextDouble(900.0, 1279.0).toFloat()
                    posY = random.nextDouble(1100.0, 1279.0).toFloat()
                    objectId = random.nextLong()
                    currentHealth = random.nextFloat() * maxHealth
                    buildProgress = random.nextFloat()
                    rotationSpeed = random.nextFloat() * 360f
                    isDead = random.nextBoolean()
                }
            }
            own.objectId = random.nextLong()
            visibleEnemy.objectId = random.nextLong()
            own.targetUnit = hidden.firstOrNull()
            own.attackTargetUnit = hidden.lastOrNull()
            enemy.credits = random.nextDouble(0.0, 1_000_000.0)
            // After losing sight, one history retains this enemy and the other kills it.
            visibleEnemy.isDead = round >= 8 && alternate
            if (round >= 8) {
                visibleEnemy.currentHealth = if (alternate) 0f else visibleEnemy.maxHealth
                visibleEnemy.objectId = random.nextLong()
            }
            player.fogOfWarData.forEach { column -> column.fill(if (alternate) 0.toByte() else 10.toByte()) }
            BaseUnit.getGlobalUnitList().clear()
            BaseUnit.getGlobalUnitList().addAll((hidden + listOf(own, visibleEnemy)).shuffled(random))
        }

        repeat(64) { round ->
            engine.currentTick = round * 6
            own.posX = if (round < 8) 5f + round else 600f + round
            own.posY = if (round < 8) 5f else 100f
            own.currentHealth = own.maxHealth - round
            player.credits = 1600.0 + round
            hiddenWorld(round, alternate = false)
            val before = beforeStream.capture(round * 100_000_000L)
            hiddenWorld(round, alternate = true)
            val after = afterStream.capture(round * 100_000_000L)
            assertSamePublication(before, after, "Hidden timeline history at round $round")
            assertEquals(round + 1L, before.state.header.revision)
            if (round == 0) {
                assertEquals(TimelineTransactionKind.RESET, before.state.header.kind)
                enemyHandle = before.state.objects.values.single { it.kind == TimelineObjectKind.UNIT &&
                    TimelineProfile.sample(before.state, it.handle, 6, 0) == TimelineValue.SInt(1) }.handle
                rememberedEnemyPosition = TimelineProfile.sample(before.state, enemyHandle, 1, 0)
                assertNotNull(rememberedEnemyPosition)
            } else {
                assertEquals(TimelineTransactionKind.UPDATE, before.state.header.kind)
                updateCount++
                val closes = before.operations.filterIsInstance<TimelineOperation.Close>()
                if (round == 8) assertEquals(listOf(TimelineOperation.Close(enemyHandle,
                    before.state.header.observedThrough, TimelineCloseKind.ENDED)), closes)
                else assertTrue(closes.isEmpty(), "Hidden death emitted another observation end")
                if (round >= 8) {
                    val oldEnemy = assertNotNull(before.state.objects[enemyHandle], "Authorized history must be retained")
                    assertEquals(TimelineCloseKind.ENDED, oldEnemy.close!!.kind,
                        "Loss of observation cannot assert that the hidden enemy died")
                    assertEquals(rememberedEnemyPosition, TimelineProfile.sample(before.state, enemyHandle, 1, 0))
                    assertNull(TimelineProfile.sample(before.state, enemyHandle, 1, before.state.header.observedThrough))
                    if (round > 8) assertTrue(before.operations.none {
                        (it is TimelineOperation.Splice && it.handle == enemyHandle) ||
                            (it is TimelineOperation.Pulse && it.handle == enemyHandle)
                    }, "Hidden death changed previously authorized enemy history")
                }
            }
        }
        assertEquals(63, updateCount)
    }

    @Test
    fun `fresh projections and later discoveries assign handles independently of hidden list ordering`() = withWorld { world ->
        val (engine, player, enemy, own, visibleEnemy) = world
        val otherVisible = TestTank().apply { team = enemy; posX = 25f; posY = 25f }
        val discoveredFirst = TestTank().apply { team = enemy; posX = 605f; posY = 100f }
        val discoveredSecond = TestTank().apply { team = enemy; posX = 615f; posY = 100f }
        val hiddenBefore = TestTank().apply { team = enemy; posX = 1100f; posY = 1100f }
        val hiddenAfter = TestTank().apply { team = enemy; posX = 1200f; posY = 1200f }
        val random = Random(0x71_25_39)

        val before = world.stream()
        val after = world.stream()

        fun install(round: Int, alternate: Boolean) {
            val hidden = if (alternate) hiddenAfter else hiddenBefore
            for (unit in listOf(own, visibleEnemy, otherVisible, discoveredFirst, discoveredSecond, hidden)) {
                unit.objectId = random.nextLong()
            }
            enemy.credits = random.nextDouble(1.0, 1_000_000.0)
            own.targetUnit = hidden
            own.attackTargetUnit = hidden
            // This enemy has left both projections before its private death can matter.
            visibleEnemy.isDead = round > 0 && alternate
            hidden.isDead = alternate
            player.fogOfWarData.forEach { it.fill(if (alternate) 0.toByte() else 10.toByte()) }
            BaseUnit.getGlobalUnitList().clear()
            BaseUnit.getGlobalUnitList().addAll(if (alternate)
                listOf(discoveredSecond, otherVisible, hidden, discoveredFirst, visibleEnemy, own)
            else listOf(own, visibleEnemy, discoveredFirst, hidden, otherVisible, discoveredSecond))
        }

        var oldEnemyHandles = emptyList<Long>()
        repeat(3) { round ->
            engine.currentTick = round * 6
            own.posX = if (round == 0) 5f else 600f
            own.posY = if (round == 0) 5f else 100f
            own.currentHealth = own.maxHealth - round
            discoveredFirst.currentHealth = discoveredFirst.maxHealth - round
            player.credits = 1600.0 + round
            install(round, alternate = false)
            val a = before.capture(round * 100_000_000L)
            install(round, alternate = true)
            val b = after.capture(round * 100_000_000L)
            assertSamePublication(a, b, "Fresh public handle assignment at round $round")
            assertEquals(1L, a.state.objects.values.single { it.kind == TimelineObjectKind.UNIT &&
                TimelineProfile.sample(a.state, it.handle, 6, a.state.header.observedThrough) == TimelineValue.SInt(0) }.handle,
                "A continuously visible unit must retain its handle")
            val created = a.operations.filterIsInstance<TimelineOperation.Create>().filter { it.kind == TimelineObjectKind.UNIT }
            val closes = a.operations.filterIsInstance<TimelineOperation.Close>()
            if (round == 0) {
                assertEquals(3, created.size)
                oldEnemyHandles = created.filter { it.handle != 1L }.map { it.handle }
            } else if (round == 1) {
                assertEquals(oldEnemyHandles, closes.map { it.handle })
                assertTrue(closes.all { it.closeKind == TimelineCloseKind.ENDED })
                assertEquals(listOf(605L * 256, 615L * 256), created.map {
                    assertIs<TimelineValue.Vec3>(TimelineProfile.sample(a.state, it.handle, 1, a.state.header.observedThrough)).x
                })
                assertEquals(2, created.size)
                assertTrue(created.all { it.handle !in oldEnemyHandles })
                oldEnemyHandles.forEach { assertNotNull(TimelineProfile.sample(a.state, it, 1, 0)) }
            } else {
                assertTrue(created.isEmpty())
                assertTrue(closes.isEmpty(), "Hidden death must not emit another observation end")
            }
        }
    }

}

/** Real v7 transaction frames, decoding, atomic commit and full canonical bytes shared by engine tests. */
internal class EngineTimelineTestStream(val projection: EngineTimelineProjector, val context: TimelineContext) {
    val publisher = TimelinePublisher(context)
    val store = TimelineStore(context)
    private val receiver = TimelineReceiver(store)
    private var clock = 0L
    private val encoder = TimelineEncoder(clock = { clock })

    data class Result(val state: TimelineState, val operations: List<TimelineOperation>,
        val wire: ByteArray, val canonical: ByteArray)

    fun capture(nowNanos: Long): Result = publish(projection.observe(), nowNanos)

    fun publish(observation: TimelineObservation, nowNanos: Long): Result {
        clock = nowNanos
        publisher.observe(observation)
        val source = assertNotNull(publisher.publish(nowNanos), "An authorized change must be published")
        val previous = store.current
        val publication = assertNotNull(encoder.encode(TimelineSendCursor(context, 1,
            previous?.header?.epoch ?: 1, previous, previous == null), source))
        val formats = previous?.objects?.values?.flatMap { obj -> obj.curves.values.map {
            TimelineCurveKey(obj.handle, it.attributeId) to it.format
        } }?.toMap().orEmpty()
        val operations = TimelineBinary.decodeOperations(publication.operations,
            if (publication.header.kind == TimelineTransactionKind.RESET) emptyMap() else formats)
        val frames = ArrayList<TimelineFrame>()
        val h = publication.header
        frames.add(h.beginFrame())
        val data = publication.operations.toByteArray()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(data.size, offset + TimelineProtocol.MAX_CHUNK_BYTES)
            frames.add(timelineFrame(107, tn(1, h.streamId, true), tn(2, h.epoch), tn(3, h.revision),
                tn(4, offset.toLong()), tb(5, TimelineBytes(data.copyOfRange(offset, end)))))
            offset = end
        }
        frames.add(timelineFrame(108, tn(1, h.streamId, true), tn(2, h.epoch), tn(3, h.revision)))
        val wire = ByteArrayOutputStream().also { out -> frames.forEach {
            TimelineMessages.validate(it, fromClient = false)
            TimelineBinary.writeFrame(out, it)
        } }.toByteArray()
        val input = ByteArrayInputStream(wire)
        repeat(frames.size) {
            val frame = TimelineBinary.readFrame(input)
            TimelineMessages.validate(frame, fromClient = false)
            when (frame.type) {
                106 -> receiver.begin(TimelineMessages.header(frame.body))
                107 -> receiver.chunk(frame)
                108 -> assertNotNull(receiver.commit(frame))
                else -> error("Unexpected transaction frame")
            }
        }
        assertEquals(0, input.available())
        val state = assertNotNull(store.current)
        val canonical = ByteArrayOutputStream().also { TimelineCanonical.write(state, it) }.toByteArray()
        return Result(state, operations, wire, canonical)
    }
}

internal fun engineTimelineTestContext(engine: GameEngine, publicMap: EnginePublicMapSnapshot,
    access: Int = 2, joinedAt: Long = 0): TimelineContext {
    val map = EngineTimelineProjector.mapRecord(engine, publicMap)
    return TimelineContext(TimelineBytes(ByteArray(16) { 1 }), TimelineBytes(ByteArray(16) { access.toByte() }),
        TimelineCanonical.mapKey(map), map, 0, 60, joinedAt = joinedAt, historyFrom = joinedAt)
}

private fun assertSamePublication(before: EngineTimelineTestStream.Result,
    after: EngineTimelineTestStream.Result, label: String) {
    assertContentEquals(before.wire, after.wire, "$label changed actual v7 transaction bytes")
    assertContentEquals(before.canonical, after.canonical, "$label changed the complete retained authorized state")
    assertEquals(TimelineCanonical.digest(before.state), TimelineCanonical.digest(after.state),
        "$label changed the complete canonical digest")
}
