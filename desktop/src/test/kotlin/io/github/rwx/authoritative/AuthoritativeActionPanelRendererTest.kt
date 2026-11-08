package io.github.rwx.authoritative

import io.github.rwx.net.authoritative.*
import io.github.rwx.render.canvas.*
import java.awt.event.KeyEvent
import java.io.File
import kotlin.test.*

/** Pure DTO, canvas and input tests. No sockets, simulation, native display or UI automation. */
class AuthoritativeActionPanelRendererTest {
    @Test
    fun `timeline selection is local and cleared selection rejects an old sampled panel`() = fixture { f ->
        f.select()
        assertEquals(listOf(1L), f.selections.last())
        val old = f.panel(listOf(action(11)))
        f.accept(old.actions)
        f.renderer.keyPressed(KeyEvent.VK_1)
        assertEquals(1, f.actions.size)
        f.renderer.keyPressed(KeyEvent.VK_ESCAPE)
        f.renderer.render(400_000_000L)
        assertTrue(f.selections.last().isEmpty())
        f.renderer.updateActionPanel(old, 400_000_000L)
        f.renderer.keyPressed(KeyEvent.VK_1)
        assertEquals(1, f.actions.size, "An old sampled capability cannot restore cleared selection")
    }

    @Test
    fun `same timeline panel identity survives a new local display sequence before input`() = fixture { f ->
        f.select(); f.accept(listOf(action(11)), panelSequence = 40)
        f.renderer.updateView(view().copy(sequence = 2, tick = 106), 16_000_000L)
        f.renderer.updateActionPanel(f.panel(listOf(action(11)), 40), 16_000_000L)
        // Production resamples before queued input. The visible panel handle stays stable,
        // so already presented button geometry still denotes the same capability.
        f.renderer.keyPressed(KeyEvent.VK_1)
        assertEquals(40L, f.actions.single().panelSequence)
        assertEquals(2L, f.actions.single().viewSequence)
    }

    @Test
    fun `same panel replacing an action cannot reinterpret an already drawn number key`() = fixture { f ->
        f.select(); f.accept(listOf(action(11)), panelSequence = 40)
        f.renderer.updateView(view().copy(sequence = 2, tick = 106), 16_000_000L)
        f.renderer.updateActionPanel(f.panel(listOf(action(99)), 40), 16_000_000L)
        f.renderer.keyPressed(KeyEvent.VK_1)
        assertTrue(f.actions.isEmpty(), "The old drawn row still denotes handle 11, never the replacement 99")
        f.renderer.render(16_000_000L)
        f.renderer.keyPressed(KeyEvent.VK_1)
        assertEquals(99L, f.actions.single().actionHandle)
    }

    @Test
    fun `action and unit orders share one monotonic sequence and cancel needs no target or funds`() = fixture { f ->
        f.select()
        f.accept(listOf(action(11, readiness = ActionReadiness.UNKNOWN),
            action(12, readiness = ActionReadiness.BLOCKED, canCancel = true)))
        f.renderer.keyPressed(KeyEvent.VK_1, shift = true)
        f.renderer.keyPressed(KeyEvent.VK_S)
        f.renderer.keyPressed(KeyEvent.VK_2)
        assertEquals(1, f.actions.size, "Only BLOCKED prevents execution; UNKNOWN remains server-validated")
        f.renderer.keyPressed(KeyEvent.VK_2, control = true)
        assertEquals(1L, f.actions[0].sequence)
        assertEquals(2L, f.intents.single().sequence)
        assertEquals(3L, f.actions[1].sequence)
        assertFalse(f.actions[0].queued, "Production already has its own queue; Shift must not set waypoint queued")
        assertTrue(f.actions[1].cancel)
        assertEquals(0f, f.actions[1].x)
        assertEquals(0f, f.actions[1].y)
        assertEquals(0L, f.actions[1].targetHandle)
        assertFalse(f.actions[1].queued)
        // The dedicated Cancel button is independent of the disabled action body.
        f.renderer.render(0L)
        f.renderer.pointerDown(748f, 287f, 1)
        f.renderer.pointerUp(748f, 287f, 1)
        assertEquals(3, f.actions.size)
        assertTrue(f.actions.last().cancel)
    }

    @Test
    fun `point actions may enter fog while unit targeting requires a currently disclosed handle`() = fixture { f ->
        f.renderer.updateView(view().copy(fog = ByteArray(400) { 10 }), 0L)
        f.select()
        f.accept(listOf(action(11, ViewActionKind.BUILD, ActionTargetMode.POINT, "turret"),
            action(12, ViewActionKind.REPAIR, ActionTargetMode.VISIBLE_UNIT)))
        f.renderer.keyPressed(KeyEvent.VK_1)
        f.click(200f, 200f, shift = true)
        val point = f.actions.single()
        assertEquals(200f, point.x, .001f)
        assertEquals(200f, point.y, .001f)
        assertEquals(0L, point.targetHandle)
        assertTrue(point.queued)
        f.renderer.keyPressed(KeyEvent.VK_2)
        f.click(200f, 200f)
        assertEquals(1, f.actions.size, "Empty ground must not turn into a global object-id query")
        f.click(280f, 100f)
        assertEquals(2L, f.actions.last().targetHandle)
        assertEquals(0f, f.actions.last().x)
        assertEquals(0f, f.actions.last().y)
        f.renderer.keyPressed(KeyEvent.VK_1)
        f.click(200f, 200f, button = 3)
        assertTrue(f.intents.isEmpty(), "Right click cancels targeting without moving the unit")
    }

    @Test
    fun `fresh panels rebind one matching target action and remove ambiguous or missing actions`() = fixture { f ->
        f.select()
        val build = action(11, ViewActionKind.BUILD, ActionTargetMode.POINT, "turret")
        f.accept(listOf(build))
        f.renderer.keyPressed(KeyEvent.VK_1)
        f.renderer.updateView(view().copy(sequence = 2, tick = 112), 200_000_000L)
        f.renderer.render(200_000_000L)
        f.accept(listOf(build.copy(handle = 99)), panelSequence = 2, now = 200_000_000L)
        f.click(200f, 200f)
        assertEquals(99L, f.actions.single().actionHandle)
        assertEquals(2L, f.actions.single().panelSequence)
        f.renderer.keyPressed(KeyEvent.VK_1)
        f.renderer.updateView(view().copy(sequence = 3, tick = 124), 400_000_000L)
        f.renderer.render(400_000_000L)
        f.accept(listOf(build.copy(handle = 100), build.copy(handle = 101)), panelSequence = 3, now = 400_000_000L)
        f.click(200f, 200f)
        assertEquals(1, f.actions.size, "An ambiguous refreshed action must revoke pending targeting")
    }

    @Test
    fun `a newly received panel cannot reinterpret buttons or number keys from an older drawn panel`() = fixture { f ->
        f.select()
        f.accept(listOf(action(11)))
        f.renderer.updateView(view().copy(sequence = 2, tick = 112), 200_000_000L)
        f.renderer.render(200_000_000L)
        val replacement = action(11, ViewActionKind.TARGET_GROUND, ActionTargetMode.POINT)
        f.renderer.updateActionPanel(f.panel(listOf(replacement), 2), 200_000_000L)
        f.renderer.keyPressed(KeyEvent.VK_1)
        f.renderer.pointerDown(650f, 195f, 1)
        f.renderer.pointerUp(650f, 195f, 1)
        assertTrue(f.actions.isEmpty(), "Input from stale button geometry must not invoke the new capability")
        f.renderer.render(200_000_000L)
        f.renderer.keyPressed(KeyEvent.VK_1)
        f.click(200f, 200f)
        assertEquals(2L, f.actions.single().panelSequence)
        assertEquals(200f, f.actions.single().x, .001f)
    }

    @Test
    fun `compact action rows expose full reviewed costs queue and stock data on hover`() = fixture { f ->
        f.select()
        f.accept(listOf(action(11).copy(upfrontCost = ViewActionCost(123456, 60f, 70f, 80f, 90),
            streamingCost = ViewActionCost(credits = 4321), queuedCount = 1234, stockpileCount = 5678,
            progress = .75f, canCancel = true)))
        f.renderer.pointerMoved(650f, 195f)
        val text = f.renderer.render(0L).commands.filterIsInstance<KoolCanvasCommand.DrawText>().joinToString(" ") { it.text }
        for (token in listOf("123456cr", "60 energy", "70 HP", "80 shield", "90 ammo", "4321cr", "Queue 1234", "Stock 5678", "75%")) {
            assertTrue(token in text, "The complete public value must remain readable: $token")
        }
        assertTrue("Cancel" in text)
    }

    @Test
    fun `owner disappearance type change replay finish disconnect and stale panels revoke capabilities`() {
        for (reason in listOf("hidden", "type", "upgrade", "replay", "finished", "disconnected", "ticks", "wall")) fixture { f ->
            f.select()
            val oldPanel = f.panel(listOf(action(22)), 100)
            f.accept(listOf(action(11)))
            when (reason) {
                "hidden" -> f.renderer.updateView(view().copy(sequence = 2, units = view().units.drop(1)), 1L)
                "type" -> f.renderer.updateView(view().copy(sequence = 2, units = view().units.map {
                    if (it.handle == 1L) it.copy(typeName = "extractor") else it }), 1L)
                "upgrade" -> f.renderer.updateView(view().copy(sequence = 2, units = view().units.map {
                    if (it.handle == 1L) it.copy(upgradeLevel = 2) else it }), 1L)
                "replay" -> f.renderer.connectionState(true, displayOnly = true)
                "finished" -> f.renderer.updateView(view().copy(sequence = 2,
                    matchStatus = MatchStatus(MatchPhase.FINISHED)), 1L)
                "disconnected" -> f.renderer.connectionState(false)
                "ticks" -> f.renderer.updateView(view().copy(sequence = 2, tick = 221), 1L)
            }
            val now = if (reason == "wall") 3_000_000_001L else 1L
            f.renderer.render(now)
            if (reason != "wall") f.renderer.updateActionPanel(oldPanel, now)
            f.renderer.keyPressed(KeyEvent.VK_1)
            assertTrue(f.actions.isEmpty(), "$reason must revoke action execution and reject old replies")
        }
    }

    @Test
    fun `every one of the bounded 128 actions is reachable by pagination and fixed static keys`() = fixture { f ->
        f.select()
        f.accept(List(128) { index -> action(index + 1L, type = "type$index") })
        val shown = mutableSetOf<String>()
        repeat(32) {
            val frame = f.renderer.render(0L)
            shown += frame.commands.filterIsInstance<KoolCanvasCommand.DrawText>()
                .map { it.text }.filter { "Produce Type" in it }.map { it.substringAfter("Produce ") }
            f.renderer.keyPressed(KeyEvent.VK_PAGE_DOWN)
        }
        assertEquals((0..127).map { "Type$it" }.toSet(), shown)
        f.renderer.keyPressed(KeyEvent.VK_4)
        assertEquals(128L, f.actions.single().actionHandle)
        f.renderer.wheel(750f, 200f, -1)
        f.renderer.render(0L)
        f.renderer.keyPressed(KeyEvent.VK_1)
        assertEquals(121L, f.actions.last().actionHandle)
    }

    @Test
    fun `guard patrol queue but rally target ground and attack mode never set waypoint queue`() = fixture { f ->
        f.select()
        for ((index, kind) in listOf(ViewActionKind.GUARD, ViewActionKind.PATROL, ViewActionKind.RALLY,
            ViewActionKind.TARGET_GROUND, ViewActionKind.ATTACK_MODE).withIndex()) {
            val mode = when (kind) { ViewActionKind.GUARD -> ActionTargetMode.VISIBLE_UNIT
                ViewActionKind.ATTACK_MODE -> ActionTargetMode.NONE; else -> ActionTargetMode.POINT }
            val now = index * 200_000_000L
            if (index > 0) { f.renderer.updateView(view().copy(sequence = index + 1L, tick = 100 + index * 12), now); f.renderer.render(now) }
            f.accept(listOf(action(index + 11L, kind, mode)), panelSequence = index + 1L, now = now)
            f.renderer.keyPressed(KeyEvent.VK_1, shift = true)
            if (mode == ActionTargetMode.VISIBLE_UNIT) f.click(280f, 100f) else if (mode == ActionTargetMode.POINT) f.click(200f, 200f)
            assertEquals(kind in setOf(ViewActionKind.GUARD, ViewActionKind.PATROL), f.actions.last().queued, kind.name)
        }
    }

    @Test
    fun `module labels expose slot and reviewed semantics without leaking authority or native ids`() = fixture { f ->
        val renderer = f.renderer
        for ((suffix, expected) in mapOf("selectSlot1" to "Select slot 1", "selectSlot6" to "Select slot 6",
            "selectBuildSlot" to "Select build slot", "cancelConstruction" to "Cancel construction",
            "reclaimModule" to "Reclaim module", "buildNuke" to "Build nuke", "launchNuke" to "Launch nuke",
            "buildAntiNuke" to "Build anti-nuke", "blink" to "Blink", "enableAntiNuke" to "Enable anti-nuke",
            "disableAntiNuke" to "Disable anti-nuke")) {
            val action = action(1, ViewActionKind.TOGGLE).copy(producedTypeKey = "",
                presentationKey = "module.slot2.authority.action.toggle.modularSpider.$suffix")
            assertEquals("Slot 2 / $expected", renderer.actionLabel(action))
        }
        assertEquals("Slot 1 / Upgrade Turret T2", renderer.actionLabel(action(1, ViewActionKind.UPGRADE, type = "turretT2")
            .copy(presentationKey = "module.slot1.authority.action.upgrade.modularSpider_emptySlot.17")))
        assertEquals("Slot 3 / Action #17", renderer.actionLabel(action(1, ViewActionKind.TOGGLE, type = "")
            .copy(presentationKey = "module.slot3.authority.action.toggle.privateOwnerKey.17")))
        assertEquals("Hold fire", renderer.actionLabel(action(1, ViewActionKind.ATTACK_MODE, type = "")
            .copy(presentationKey = "authority.action.attack_mode.tank.holdFire")))
    }

    private class Fixture(val renderer: AuthoritativeCanvasRenderer, val selections: MutableList<List<Long>>,
        val intents: MutableList<ViewUnitOrder>, val actions: MutableList<ViewActionCommand>) {
        fun select() { renderer.updateView(view(), 0L); renderer.render(0L); click(100f, 100f); renderer.render(0L) }
        fun click(x: Float, y: Float, button: Int = 1, shift: Boolean = false) {
            val point = renderer.screenPoint(x, y)
            renderer.pointerDown(point.x, point.y, button, shift); renderer.pointerUp(point.x, point.y, button)
        }
        fun panel(actions: List<ViewAction>, panelSequence: Long = 1) =
            PlayerActionPanel(0, panelSequence, renderer.viewSequence, 0, 1, actions)
        fun accept(actions: List<ViewAction>, panelSequence: Long = 1, now: Long = 0) {
            renderer.updateActionPanel(panel(actions, panelSequence), now); renderer.render(now)
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = sequenceOf(File("assets"), File("../assets")).first { it.isDirectory }
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { File(root, it).readBytes() }.use { assets ->
                val selections = mutableListOf<List<Long>>()
                val intents = mutableListOf<ViewUnitOrder>()
                val actions = mutableListOf<ViewActionCommand>()
                val renderer = AuthoritativeCanvasRenderer(assets, intents::add, actions::add, selections::add)
                renderer.setViewport(KoolCanvasViewport(800, 600))
                block(Fixture(renderer, selections, intents, actions))
            }
        }
    }

    companion object {
        private fun view() = PlayerViewSnapshot(1, 100, 0, 4000.0, 20, 20, 20, 20, ByteArray(400), listOf(
            ViewUnit(1, "builder", 0, 100f, 100f, 0f, 0f, 100f, 100f, 1f, true),
            ViewUnit(2, "landFactory", 0, 280f, 100f, 0f, 0f, 100f, 100f, 1f, true),
            ViewUnit(3, "tank", 1, 300f, 280f, 0f, 0f, 100f, 100f, 1f, false)), ByteArray(400))
        private fun action(handle: Long, kind: ViewActionKind = ViewActionKind.PRODUCE,
            mode: ActionTargetMode = ActionTargetMode.NONE, type: String = "tank",
            readiness: ActionReadiness = ActionReadiness.READY, canCancel: Boolean = false) =
            ViewAction(handle, kind, mode, "stock.${kind.name.lowercase()}", type,
                upfrontCost = ViewActionCost(credits = 500), readiness = readiness, queuedCount = 2, canCancel = canCancel)
    }
}
