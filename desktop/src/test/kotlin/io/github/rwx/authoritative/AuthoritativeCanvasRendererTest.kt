package io.github.rwx.authoritative

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.net.authoritative.*
import io.github.rwx.render.canvas.*
import java.awt.event.KeyEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthoritativeCanvasRendererTest {
    @Test fun `readonly first view and home locate the same own units without granting command authority`() = fixture { live, assets, _, _ ->
        val source = snapshot().copy(mapWidth = 100, mapHeight = 100,
            fog = ByteArray(10_000), terrain = ByteArray(10_000))
        live.updateView(source)
        val expected = live.cameraSnapshot(1, 1)
        val replay = AuthoritativeCanvasRenderer(assets,
            { error("Camera changes cannot send orders") },
            sendAction = { error("Camera changes cannot send actions") },
            selectionChanged = { error("Readonly camera cannot send Select") })
        replay.setViewport(KoolCanvasViewport(800, 600))
        replay.connectionState(true, displayOnly = true, label = "Replay")
        replay.updateView(source.copy(units = source.units.map { it.copy(controllable = false) }))
        assertEquals(expected, replay.cameraSnapshot(1, 1))
        assertTrue(replay.render().commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().size >= 6,
            "Own recorded units must be in the initial camera")
        replay.pointerDown(300f, 200f, 2)
        replay.pointerMoved(260f, 160f)
        replay.pointerUp(260f, 160f, 2)
        assertNotEquals(expected, replay.cameraSnapshot(1, 1))
        replay.keyPressed(KeyEvent.VK_H)
        assertEquals(expected, replay.cameraSnapshot(1, 1))
        click(replay, 100f, 100f)
        replay.keyPressed(KeyEvent.VK_A, control = true)
        replay.keyPressed(KeyEvent.VK_S)
        replay.keyPressed(KeyEvent.VK_1)
        click(replay, 200f, 200f, 3)
        assertTrue(replay.selectedHandles.isEmpty())
        assertTrue(replay.actionProbeUi().readOnly)
        assertFalse(replay.actionProbeUi().running)
    }

    @Test fun `home without any disclosed own unit keeps the map center and ignores enemy command flags`() = fixture { _, assets, _, _ ->
        val base = snapshot().copy(mapWidth = 100, mapHeight = 100,
            fog = ByteArray(10_000), terrain = ByteArray(10_000))
        fun camera(units: List<ViewUnit>): io.github.rwx.session.GameCameraSnapshot {
            val renderer = AuthoritativeCanvasRenderer(assets, { error("Camera cannot send orders") })
            renderer.setViewport(KoolCanvasViewport(800, 600))
            renderer.connectionState(true, displayOnly = true)
            renderer.updateView(base.copy(units = units))
            return renderer.cameraSnapshot(1, 1)
        }
        assertEquals(camera(emptyList()), camera(listOf(unit(10, "builder", 100f, 100f,
            team = 1, controllable = true))))
    }

    @Test fun `timeline readonly loading and recovery never invoke a selection sender`() = fixture { _, assets, _, _ ->
        val renderer = AuthoritativeCanvasRenderer(assets, { error("Readonly view cannot issue commands") },
            selectionChanged = { error("Readonly view cannot send Select") })
        renderer.updateView(snapshot())
        renderer.connectionState(true, displayOnly = true)
        renderer.render()
        renderer.connectionState(true, isSynchronizing = true)
        renderer.render()
        renderer.clearTimelineFrame()
        assertTrue(renderer.render().commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().isEmpty())
        assertEquals(-1L, renderer.viewSequence)
    }

    @Test
    fun `timeline commands can start at tick zero without waiting for another seat`() = fixture { renderer, _, _, intents ->
        renderer.updateView(snapshot().copy(tick = 0), 0L)
        val frame = renderer.render(0L)
        assertFalse(frame.commands.filterIsInstance<KoolCanvasCommand.DrawText>().any { "Waiting for both players" in it.text })
        click(renderer, 100f, 100f)
        renderer.keyPressed(KeyEvent.VK_S)
        assertEquals(OrderKind.STOP, intents.single().kind)
    }

    @Test
    fun `displaying standard unit sprites creates canvas frames without starting a game engine`() = fixture { renderer, assets, store, _ ->
        val before = GameEngine.getInstance()
        renderer.updateView(snapshot(), 0L)
        val frame = renderer.render(100_000_000L)
        val images = frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>()
        assertTrue(images.size >= 6, "Standard units must draw actual body, turret and shadow assets")
        assertTrue(images.all { it.texture.id.value.startsWith("authority-catalog/") })
        assertTrue(images.all { it.paint.color == KoolCanvasColor.White })
        assertTrue(images.all { assertNotNull(store.argbImageView(it.texture.id)).pixels.any { pixel -> pixel ushr 24 > 0 } })
        val packet = store.freezeFrame(frame, 1, 1, 0, 0, renderer.cameraSnapshot(1, 1))
        try {
            assertTrue(packet.resourceLease.resourceCount >= 6)
            assets.close()
            assertTrue(packet.resourceLease.resourceCount >= 6, "Completed frame owns its sprite pixels")
            assertSame(before, GameEngine.getInstance(), "Display must not create or replace a simulation")
        } finally { packet.close() }
        assertTrue(packet.resourceLease.isReleased)
    }

    @Test
    fun `build and production shortcuts without a panel never manufacture type commands`() = fixture { renderer, _, _, orders ->
        renderer.updateView(snapshot(), 0L)
        renderer.render(100_000_000L)
        click(renderer, 100f, 100f)
        assertEquals(setOf(1L), renderer.selectedHandles)
        for (key in listOf(KeyEvent.VK_F, KeyEvent.VK_T, KeyEvent.VK_E, KeyEvent.VK_P, KeyEvent.VK_B)) {
            renderer.keyPressed(key)
        }
        assertTrue(orders.isEmpty(), "No capability panel means no build or production command")
        click(renderer, 280f, 100f)
        renderer.keyPressed(KeyEvent.VK_P)
        renderer.keyPressed(KeyEvent.VK_B)
        assertTrue(orders.isEmpty(), "A factory type is not a production capability")
        renderer.keyPressed(KeyEvent.VK_S)
        assertEquals(OrderKind.STOP, orders.single().kind)
        assertEquals(listOf(2L), orders.single().unitHandles)
        assertEquals(CommandTarget.None, orders.single().target)
    }

    @Test
    fun `box selection attack move and visible enemy attack use only disclosed handles`() = fixture { renderer, _, _, orders ->
        renderer.updateView(snapshot(), 0L)
        renderer.render(100_000_000L)
        val start = renderer.screenPoint(80f, 80f)
        val end = renderer.screenPoint(300f, 125f)
        renderer.pointerDown(start.x, start.y, 1)
        renderer.pointerMoved(end.x, end.y)
        renderer.pointerUp(end.x, end.y, 1)
        assertEquals(setOf(1L, 2L), renderer.selectedHandles)
        renderer.keyPressed(KeyEvent.VK_S)
        assertEquals(OrderKind.STOP, orders.single().kind)
        renderer.keyPressed(KeyEvent.VK_A)
        click(renderer, 200f, 200f)
        assertEquals(OrderKind.ATTACK_MOVE, orders.last().kind)
        assertEquals(setOf(1L, 2L), orders.last().unitHandles.toSet())
        val point = assertIs<CommandTarget.Point>(orders.last().target)
        assertEquals(200f, point.x, .001f)
        assertEquals(200f, point.y, .001f)
        click(renderer, 100f, 100f)
        click(renderer, 300f, 280f, 3)
        assertEquals(OrderKind.ATTACK_UNIT, orders.last().kind)
        assertEquals(CommandTarget.Unit(3L), orders.last().target)
        assertEquals(listOf(1L), orders.last().unitHandles)
        assertEquals(listOf(1L, 2L, 3L), orders.map { it.sequence })
        renderer.keyPressed(KeyEvent.VK_ESCAPE)
        assertTrue(renderer.selectedHandles.isEmpty())
        click(renderer, 200f, 200f, 3)
        assertEquals(3, orders.size, "Clearing selection leaves no unit authority to command")
    }

    @Test
    fun `resynchronization clears selection cancels targeting and blocks commands until ready`() = fixture { renderer, _, _, orders ->
        renderer.updateView(snapshot(), 0L)
        renderer.render(100_000_000L)
        click(renderer, 100f, 100f)
        renderer.keyPressed(KeyEvent.VK_A)
        renderer.connectionState(true, isSynchronizing = true)
        assertTrue(renderer.selectedHandles.isEmpty())
        renderer.keyPressed(KeyEvent.VK_S)
        click(renderer, 200f, 200f, 3)
        click(renderer, 100f, 100f)
        renderer.keyPressed(KeyEvent.VK_S)
        assertTrue(orders.isEmpty(), "A live socket is not enough while the stream is resynchronizing")
        renderer.connectionState(true, isSynchronizing = false)
        renderer.updateView(snapshot().copy(sequence = 8, tick = 106), 200_000_000L)
        click(renderer, 100f, 100f)
        renderer.keyPressed(KeyEvent.VK_S)
        assertEquals(OrderKind.STOP, orders.single().kind)
        assertEquals(8L, orders.single().viewSequence)
    }

    @Test
    fun `zoom keeps the cursor anchor stable and middle drag pans the display camera`() = fixture { renderer, _, _, _ ->
        renderer.updateView(snapshot(), 0L)
        renderer.render(100_000_000L)
        val first = renderer.screenPoint(100f, 100f)
        renderer.wheel(first.x, first.y, -2)
        val zoomed = renderer.screenPoint(100f, 100f)
        assertEquals(first.x, zoomed.x, .001f)
        assertEquals(first.y, zoomed.y, .001f)
        renderer.pointerDown(zoomed.x, zoomed.y, 2)
        renderer.pointerMoved(zoomed.x + 30, zoomed.y + 20)
        renderer.pointerUp(zoomed.x + 30, zoomed.y + 20, 2)
        val panned = renderer.screenPoint(100f, 100f)
        assertEquals(zoomed.x + 30, panned.x, .001f)
        assertEquals(zoomed.y + 20, panned.y, .001f)
    }

    @Test
    fun `a removed enemy never remains in the next canvas frame through interpolation`() = fixture { renderer, assets, _, _ ->
        val initial = snapshot()
        renderer.updateView(initial, 0L)
        renderer.render(100_000_000L)
        val enemyImage = assertNotNull(assets.unit("c_tank", 0xffdf3b32.toInt())).body.id
        renderer.updateView(initial.copy(sequence = 8, units = initial.units.filterNot { it.handle == 3L }), 100_000_000L)
        val next = renderer.render(100_000_001L)
        assertFalse(next.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().any { it.texture.id == enemyImage })
        renderer.updateView(initial.copy(sequence = 7), 200_000_000L)
        assertEquals(8L, renderer.viewSequence, "An older snapshot must not restore hidden entities")
    }

    @Test
    fun `finished result and replay source show their status and disable all gameplay inputs`() = fixture { renderer, _, _, intents ->
        val initial = snapshot()
        renderer.updateView(initial, 0L)
        renderer.render(100_000_000L)
        click(renderer, 100f, 100f)
        renderer.connectionState(true, displayOnly = true, label = "Replay playing")
        renderer.keyPressed(KeyEvent.VK_F)
        click(renderer, 200f, 200f, 3)
        renderer.keyPressed(KeyEvent.VK_S)
        assertTrue(intents.isEmpty())
        assertTrue(renderer.render().commands.filterIsInstance<KoolCanvasCommand.DrawText>().any { "Replay playing" in it.text })
        renderer.connectionState(true)
        renderer.updateView(initial.copy(sequence = 8, units = emptyList(),
            matchStatus = MatchStatus(MatchPhase.FINISHED, listOf(0), listOf(1))), 200_000_000L)
        val result = renderer.render(300_000_000L)
        assertTrue(result.commands.filterIsInstance<KoolCanvasCommand.DrawText>().any { it.text == "Victory" })
        renderer.keyPressed(KeyEvent.VK_A, control = true)
        renderer.keyPressed(KeyEvent.VK_S)
        assertTrue(intents.isEmpty())
        renderer.connectionState(false, "Connection lost")
        assertTrue(renderer.render().commands.filterIsInstance<KoolCanvasCommand.DrawText>().any { "Disconnected" in it.text })
    }

    private fun click(renderer: AuthoritativeCanvasRenderer, worldX: Float, worldY: Float, button: Int = 1) {
        val p = renderer.screenPoint(worldX, worldY)
        renderer.pointerDown(p.x, p.y, button)
        renderer.pointerUp(p.x, p.y, button)
    }

    private fun fixture(block: (AuthoritativeCanvasRenderer, AuthoritativeVisualAssets, KoolCanvasCpuTextureStore, MutableList<ViewUnitOrder>) -> Unit) {
        val root = sequenceOf(File("assets"), File("../assets")).first { it.isDirectory }
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { path -> File(root, path).takeIf(File::isFile)?.readBytes() }.use { assets ->
                val inputs = mutableListOf<ViewUnitOrder>()
                val renderer = AuthoritativeCanvasRenderer(assets, inputs::add)
                renderer.setViewport(KoolCanvasViewport(800, 600))
                block(renderer, assets, store, inputs)
            }
        }
    }

    private fun snapshot() = PlayerViewSnapshot(7, 100, 0, 4000.0, 20, 20, 20, 20, ByteArray(400), listOf(
        unit(1, "builder", 100f, 100f), unit(2, "landFactory", 280f, 100f),
        unit(3, "c_tank", 300f, 280f, team = 1, controllable = false), unit(4, "commandCenter", 100f, 280f),
    ), ByteArray(400))

    private fun unit(handle: Long, type: String, x: Float, y: Float, team: Int = 0, controllable: Boolean = true) =
        ViewUnit(handle, type, team, x, y, 0f, 0f, 100f, 100f, 1f, controllable)
}
