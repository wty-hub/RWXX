package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import de.fabmax.kool.input.InputStack
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.Pointer
import de.fabmax.kool.math.MutableVec2f
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameCameraSnapshot
import io.github.rwx.session.GamePointerFrameContext
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import kotlin.test.*

class KoolPointerForwardingTest {
    private class Fixture(legacy: Boolean) {
        val session = RecordingSession()
        var screen: AppScreen = AppScreen.InGame
        var modal = false
        var surface = KoolCanvasViewport(1280, 720)
        var surfaceReads = 0
        val controller = InputController(session, { screen }, { 1f }, {},
            isModalOverlayOpen = { modal },
            pointerViewport = { surfaceReads++; surface },
            legacyPointerForwarding = legacy,
        ).also { it.install() }

        fun render(pointer: Pointer) = controller.forwardInputForFrame(true, pointer)

        /** The registered listener's sink receives the old PointerState before Kool polls the new one. */
        fun dispatchPreviousSnapshot(pointer: Pointer) {
            InputStack.defaultInputHandler.pointerListeners.updated().forEach { listener ->
                // No window/context is needed: this listener's only enabled action delegates to onPointer.
                val delegate = GatedPointerListener::class.java.getDeclaredField("delegate")
                    .apply { isAccessible = true }.get(listener) as KoolLegacyPointerSink
                delegate.onPointer(pointer)
            }
        }
    }

    private fun withRouting(legacy: Boolean = false, block: (Fixture) -> Unit) {
        InputStack.updateHandlerStack()
        val handlers = InputStack.handlerStack.toList()
        val default = InputStack.defaultInputHandler
        val pointers = default.pointerListeners.updated().toList()
        val keyboardListeners = default.simpleKeyboardListener.keyListeners.toMap()
        InputStack.handlerStack.clear()
        InputStack.handlerStack += default
        default.pointerListeners.clear()
        default.simpleKeyboardListener.keyListeners.clear()
        try {
            block(Fixture(legacy))
        } finally {
            default.pointerListeners.clear()
            pointers.forEach { default.pointerListeners += it }
            default.pointerListeners.update()
            default.simpleKeyboardListener.keyListeners.clear()
            default.simpleKeyboardListener.keyListeners.putAll(keyboardListeners)
            InputStack.handlerStack.clear()
            handlers.forEach { InputStack.handlerStack += it }
            InputStack.updateHandlerStack()
        }
    }

    @Test fun `default installs no stale pointer listener while legacy comparison retains it`() {
        withRouting { f ->
            assertTrue(InputStack.defaultInputHandler.pointerListeners.updated().isEmpty())
            assertEquals(1, InputStack.defaultInputHandler.simpleKeyboardListener.keyListeners[
                KeyboardInput.KEY_ESC]?.updated()?.count { it.name == "rwx-escape" })
            f.controller.forwardInputForFrame(false, pointer(100f, scroll = 1f))
            assertTrue(f.session.events.isEmpty())
            assertEquals(0, f.session.cameraReads)
            assertEquals(0, f.surfaceReads)
            assertTrue(InputStack.handlerStack.any { it.name == "rwx-game-keyboard" },
                "Keyboard registration must still synchronize during non-render drive")
        }
        withRouting(legacy = true) { f ->
            assertEquals(1, InputStack.defaultInputHandler.pointerListeners.updated().size)
            f.controller.forwardInputForFrame(false, pointer(100f, scroll = 1f))
            assertEquals(listOf("wheel:120", "hover:100.0"), f.session.events)
        }
    }

    @Test fun `one wheel poll is forwarded once instead of repeated from previous snapshot or non-render drive`() {
        for (legacy in listOf(false, true)) withRouting(legacy) { f ->
            val noWheel = pointer(100f)
            val wheel = pointer(100f, scroll = 1f)
            f.dispatchPreviousSnapshot(noWheel)
            f.render(wheel)
            f.controller.forwardInputForFrame(false, wheel)
            f.dispatchPreviousSnapshot(wheel)
            f.render(noWheel)
            assertEquals(if (legacy) listOf(120, 120, 120) else listOf(120), f.session.wheels)
        }
    }

    @Test fun `reverse drag forwards current positions without stale samples and retains each seen camera`() {
        for (legacy in listOf(false, true)) withRouting(legacy) { f ->
            var previous = pointer(100f)
            val cameras = mutableListOf<GameCameraSnapshot>()
            for ((index, x) in listOf(100f, 200f, 50f).withIndex()) {
                val seen = f.session.camera.copy(revision = index.toLong(), x = index * 75f)
                f.session.camera = seen
                cameras += seen
                val current = pointer(x, down = true)
                f.dispatchPreviousSnapshot(previous)
                f.render(current)
                previous = current
            }
            assertEquals(if (legacy) listOf(100f, 100f, 200f, 200f, 50f) else listOf(100f, 200f, 50f),
                f.session.buttons.filter { it.down }.map { it.x })
            if (!legacy) {
                assertEquals(3, f.session.cameraReads)
                assertEquals(3, f.surfaceReads)
                f.session.buttons.forEachIndexed { index, event ->
                    assertSame(cameras[index], event.context.camera)
                    assertEquals(f.surface, event.context.surfaceViewport)
                }
            }
        }
    }

    @Test fun `wheel press release and paused focus release keep original ordering and captured surface`() = withRouting { f ->
        val seen = f.session.camera
        f.surface = KoolCanvasViewport(1920, 1080)
        f.render(pointer(150f, down = true, scroll = 1f))
        assertEquals(listOf("wheel:120", "down:150.0"), f.session.events)
        val press = f.session.buttons.single()
        assertSame(seen, press.context.camera)
        assertEquals(f.surface, press.context.surfaceViewport)
        // A paused engine may publish a new camera or resize before the host loses focus.
        f.session.camera = seen.copy(revision = 99, zoom = 0.5f)
        f.surface = KoolCanvasViewport(3200, 1800)
        f.controller.resetOnHostFocusLost()
        assertEquals(listOf("wheel:120", "down:150.0", "up:150.0"), f.session.events)
        assertSame(press.context, f.session.buttons.last().context)
        assertEquals(1, f.session.cameraReads)
        assertEquals(1, f.surfaceReads)
    }

    @Test fun `modal wheel blocking screen gating and ordinary release are preserved`() = withRouting { f ->
        f.render(pointer(100f, down = true))
        f.modal = true
        f.render(pointer(100f, scroll = 1f))
        assertTrue(f.session.wheels.isEmpty())
        assertEquals(listOf(true, false), f.session.buttons.map { it.down })
        assertEquals(listOf("down:100.0", "up:100.0", "hover:100.0"), f.session.events)
        f.modal = false
        f.screen = AppScreen.MainMenu
        f.render(pointer(200f, down = true, scroll = 1f))
        assertEquals(3, f.session.events.size)
        f.screen = AppScreen.InGame
        f.render(pointer(200f, scroll = -1f))
        assertEquals(listOf(-120), f.session.wheels)
        assertEquals("hover:200.0", f.session.events.last())
    }

    private fun pointer(x: Float, down: Boolean = false, scroll: Float = 0f) = Pointer().apply {
        (pos as MutableVec2f).set(x, 120f)
        (this.scroll as MutableVec2f).set(0f, scroll)
        Pointer::class.java.getDeclaredField("isValid").apply { isAccessible = true }.setBoolean(this, true)
        Pointer::class.java.getDeclaredField("buttonMask").apply { isAccessible = true }.setInt(this, if (down) 1 else 0)
    }

    private data class Button(val x: Float, val down: Boolean, val context: GamePointerFrameContext)
    private class RecordingSession : GameSession() {
        var camera = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 10f, 20f, 1f)
        var cameraReads = 0
        val buttons = mutableListOf<Button>()
        val wheels = mutableListOf<Int>()
        val events = mutableListOf<String>()
        override val acceptsKoolInput = true
        override val acceptsKoolKeyboardInput = true
        override val rendererMode: RendererMode = object : RendererMode { override val id = "test" }
        override fun cameraSnapshot(): GameCameraSnapshot { cameraReads++; return camera }
        override fun submitPointer(screenX: Float, screenY: Float, isDown: Boolean, pointerId: Int,
                                   frameContext: GamePointerFrameContext) {
            buttons += Button(screenX, isDown, frameContext)
            events += "${if (isDown) "down" else "up"}:$screenX"
        }
        override fun movePointer(screenX: Float, screenY: Float, frameContext: GamePointerFrameContext) {
            events += "hover:$screenX"
        }
        override fun submitMouseWheel(amount: Int) { wheels += amount; events += "wheel:$amount" }
        override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1, 1), emptyList())
        override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine = error("No engine needed for pointer routing")
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
    }
}
