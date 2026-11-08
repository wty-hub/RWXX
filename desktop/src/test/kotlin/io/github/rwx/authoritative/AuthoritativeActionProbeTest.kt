package io.github.rwx.authoritative

import io.github.rwx.net.authoritative.*
import io.github.rwx.render.canvas.*
import java.awt.event.KeyEvent
import java.io.File
import java.io.IOException
import kotlin.test.*

/** CPU-only UI acceptance: real pointer/key handlers and canvas geometry, no display or engine. */
class AuthoritativeActionProbeTest {
    @Test
    fun `probe selects produces and ctrl cancels through drawn and presented normal UI`() = fixture { f ->
        val first = f.draw()
        assertNull(f.pump(-1))
        assertTrue(f.renderer.selectedHandles.isEmpty())
        assertIs<AuthoritativeActionProbe.Input.Click>(f.pump(first))
        assertEquals(setOf(1L), f.renderer.selectedHandles)
        f.draw()
        val selectedFrame = f.revision
        f.accept()
        assertNull(f.pump(selectedFrame), "A received panel is insufficient until its own frame is presented")
        assertTrue(f.sent.isEmpty())
        assertIs<AuthoritativeActionProbe.Input.Click>(f.pump(f.revision))
        assertEquals(1, f.sent.size)
        assertFalse(f.sent.single().cancel)
        assertEquals(2, f.probe.uiActionCount)
        f.refresh(queued = 1, credits = 3500.0)
        val queuedFrame = f.revision
        val cancel = assertIs<AuthoritativeActionProbe.Input.Key>(f.pump(queuedFrame))
        assertEquals(KeyEvent.VK_1, cancel.key)
        assertTrue(cancel.control)
        assertEquals(listOf(false, true), f.sent.map { it.cancel })
        assertTrue(f.probe.queueObserved)
        assertFalse(f.probe.completed)
        f.refresh(queued = 0, credits = 4000.0)
        assertNull(f.pump(queuedFrame), "An earlier queued frame cannot stand in for a presented refund")
        assertFalse(f.probe.completed)
        assertNull(f.pump(f.revision))
        assertTrue(f.probe.completed)
        assertTrue(f.probe.panelSeen && f.probe.cancelObserved)
        assertEquals(3, f.probe.uiActionCount)
        assertEquals(listOf(1L, 2L), f.sent.map { it.sequence })
        assertTrue(f.intents.isEmpty(), "Probe must use dynamic normal UI, never a fabricated type shortcut")
    }

    @Test
    fun `panel refresh before input waits for the replacement panel to be drawn and presented`() = fixture { f ->
        f.selectAndAccept()
        val presentedPanel = f.revision
        f.now += 200_000_000L
        f.view = f.view.copy(sequence = 2, tick = 106)
        f.renderer.updateView(f.view, f.now)
        f.draw()
        f.accept(draw = false)
        assertNull(f.pump(presentedPanel))
        assertEquals(1, f.probe.uiActionCount)
        assertTrue(f.sent.isEmpty())
        f.draw()
        assertIs<AuthoritativeActionProbe.Input.Click>(f.pump(f.revision))
        assertEquals(1, f.sent.size)
    }

    @Test
    fun `normal send callback failure does not count success and later presented input retries`() = fixture { f ->
        f.selectAndAccept()
        f.failNextSend = true
        assertIs<AuthoritativeActionProbe.Input.Click>(f.pump(f.revision))
        assertEquals(1, f.probe.uiActionCount)
        assertEquals(0, f.renderer.actionProbeUi().actionSends)
        assertTrue(f.sent.isEmpty())
        assertNull(f.pump(f.revision), "Do not spam the same presented frame")
        f.draw()
        assertIs<AuthoritativeActionProbe.Input.Click>(f.pump(f.revision))
        assertEquals(2, f.probe.uiActionCount)
        assertEquals(1, f.renderer.actionProbeUi().actionSends)
    }

    @Test
    fun `queue disappearance without a prompt credit refund cannot complete the probe`() = fixture { f ->
        f.selectAndAccept(); f.pump(f.revision)
        f.refresh(queued = 1, credits = 3500.0); f.pump(f.revision)
        assertTrue(f.probe.queueObserved)
        f.refresh(queued = 0, credits = 3500.0); f.pump(f.revision)
        assertFalse(f.probe.completed)
        assertFalse(f.probe.cancelObserved)
        f.refresh(queued = 0, credits = 3510.0); f.pump(f.revision)
        assertFalse(f.probe.completed, "A little passive income cannot imitate cancellation")
        f.refresh(queued = 0, credits = 4000.0, tickAdvance = 121); f.pump(f.revision)
        assertFalse(f.probe.completed, "Late income after natural production must not be called a refund")
    }

    @Test
    fun `builder on later pages is reached only through presented normal page keys`() = fixture { f ->
        f.fillers = 8
        f.selectAndAccept()
        repeat(2) {
            val next = assertIs<AuthoritativeActionProbe.Input.Key>(f.pump(f.revision))
            assertEquals(KeyEvent.VK_PAGE_DOWN, next.key)
            assertTrue(f.sent.isEmpty())
            f.draw()
        }
        assertIs<AuthoritativeActionProbe.Input.Click>(f.pump(f.revision))
        assertEquals(1, f.sent.size)
        f.refresh(queued = 1, credits = 3500.0)
        val cancel = assertIs<AuthoritativeActionProbe.Input.Key>(f.pump(f.revision))
        assertEquals(KeyEvent.VK_1, cancel.key)
        assertTrue(f.sent.last().cancel)
    }

    @Test
    fun `disabled replay waiting and pre-existing queues never issue probe actions`() {
        val ready = AuthoritativeActionProbeUi(connected = true, running = true, tick = 1,
            commandCenterHandle = 1, commandCenterPoint = KoolCanvasPoint(10f, 10f))
        for (probe in listOf(AuthoritativeActionProbe(false, false), AuthoritativeActionProbe(true, true))) {
            probe.recordFrame(1, ready)
            assertFalse(probe.enabled)
            assertNull(probe.nextInput(ready, 1, 0))
            assertEquals("{\"enabled\":false,\"completed\":false,\"panelSeen\":false,\"uiActionCount\":0,\"queueObserved\":false,\"cancelObserved\":false}", probe.json())
        }
        val waiting = AuthoritativeActionProbe(true, false)
        waiting.recordFrame(1, ready.copy(tick = 0))
        assertNull(waiting.nextInput(ready.copy(tick = 0), 1, 0))
        waiting.recordFrame(2, ready.copy(readOnly = true))
        assertNull(waiting.nextInput(ready.copy(readOnly = true), 2, 0))
        fixture { f ->
            f.selectAndAccept(queued = 1)
            assertNull(f.pump(f.revision))
            assertNotNull(f.probe.failure)
            assertTrue(f.sent.isEmpty())
        }
    }

    private class Fixture(val renderer: AuthoritativeCanvasRenderer,
        val sent: MutableList<ViewActionCommand>, val intents: MutableList<ViewUnitOrder>,
        val sendFailure: BooleanArray) {
        val probe = AuthoritativeActionProbe(true, false)
        var now = 0L
        var revision = 0L
        var panelSequence = 0L
        var view = view()
        var fillers = 0
        var failNextSend: Boolean
            get() = sendFailure[0]
            set(value) { sendFailure[0] = value }
        fun draw(): Long {
            renderer.render(now)
            probe.recordFrame(++revision, renderer.actionProbeUi())
            return revision
        }
        fun pump(presented: Long): AuthoritativeActionProbe.Input? {
            val input = probe.nextInput(renderer.actionProbeUi(), presented, now)
            when (input) {
                is AuthoritativeActionProbe.Input.Click -> {
                    renderer.pointerDown(input.point.x, input.point.y, 1)
                    renderer.pointerUp(input.point.x, input.point.y, 1)
                }
                is AuthoritativeActionProbe.Input.Key -> renderer.keyPressed(input.key, control = input.control)
                null -> Unit
            }
            if (input != null) probe.inputResult(renderer.actionProbeUi())
            return input
        }
        fun selectAndAccept(queued: Int = 0) { draw(); pump(revision); draw(); accept(queued) }
        fun accept(queued: Int = 0, draw: Boolean = true) {
            val actions = List(fillers) { index -> ViewAction(100L + index, ViewActionKind.TOGGLE,
                ActionTargetMode.NONE, "stock.toggle.$index") } + ViewAction(11, ViewActionKind.PRODUCE,
                ActionTargetMode.NONE, "stock.produce.builder", "builder", ViewActionCost(credits = 500),
                readiness = ActionReadiness.READY, queuedCount = queued, canCancel = queued > 0)
            renderer.updateActionPanel(PlayerActionPanel(0, ++panelSequence, renderer.viewSequence,
                0, 1, actions), now)
            if (draw) this.draw()
        }
        fun refresh(queued: Int, credits: Double, tickAdvance: Int = 6) {
            now += 200_000_000L
            view = view.copy(sequence = view.sequence + 1, tick = view.tick + tickAdvance, credits = credits)
            renderer.updateView(view, now)
            draw()
            accept(queued)
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = sequenceOf(File("assets"), File("../assets")).first { it.isDirectory }
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { File(root, it).readBytes() }.use { assets ->
                val sent = mutableListOf<ViewActionCommand>()
                val intents = mutableListOf<ViewUnitOrder>()
                val sendFailure = booleanArrayOf(false)
                val renderer = AuthoritativeCanvasRenderer(assets, intents::add, { action ->
                    if (sendFailure[0]) { sendFailure[0] = false; throw IOException("fixture send rejected") }
                    sent += action
                })
                renderer.setViewport(KoolCanvasViewport(800, 600))
                renderer.updateView(view(), 0)
                block(Fixture(renderer, sent, intents, sendFailure))
            }
        }
    }

    companion object {
        private fun view() = PlayerViewSnapshot(1, 100, 0, 4000.0, 20, 20, 20, 20,
            ByteArray(400), listOf(ViewUnit(1, "commandCenter", 0, 200f, 200f,
                0f, 0f, 100f, 100f, 1f, true)), ByteArray(400))
    }
}
