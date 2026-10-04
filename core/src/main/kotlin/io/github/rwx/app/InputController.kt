package io.github.rwx.app

import de.fabmax.kool.input.InputStack
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.Pointer
import de.fabmax.kool.input.PointerInput
import io.github.rwx.mod.registry.UiRegistry
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.component.PlatformTextInputBridge

internal class InputController(
    private val gameSession: GameSession,
    private val currentScreen: () -> AppScreen,
    pointerScale: () -> Float,
    private val navigateBack: () -> Unit,
    private val dismissDialog: () -> Boolean = { false },
    private val isModalOverlayOpen: () -> Boolean = { false },
    pointerViewport: () -> KoolCanvasViewport? = { null },
    private val legacyPointerForwarding: Boolean = "1" == System.getenv("RWX_LEGACY_POINTER_FORWARDING"),
) {
    private val legacyPointerSink = LegacyGamePointerSink(
        gameSession = gameSession,
        scaleProvider = KoolPointerScaleProvider(pointerScale),
        blockWorldWheel = isModalOverlayOpen,
        viewportProvider = pointerViewport,
    )
    private val legacyKeyboardSink = LegacyGameKeyboardSink(gameSession)

    private val gameKeyboardHandler = GameKeyboardInputHandler(
        isEnabled = {
            shouldForwardKoolInputForScreen(currentScreen(), gameSession.acceptsKoolKeyboardInput) &&
                !isModalOverlayOpen() && !PlatformTextInputBridge.isEditing()
        },
        sink = legacyKeyboardSink,
    )

    val preparedComponentName: String
        get() = legacyPointerSink::class.simpleName ?: "LegacyGamePointerSink"

    fun install() {
        KeyboardInput.addKeyListener(
            keyCode = KeyboardInput.KEY_ESC,
            name = "rwx-escape",
            filter = InputStack.KEY_FILTER_ALL,
        ) { event ->
            if (!event.isPressed) return@addKeyListener
            // Esc while typing must not leave the screen. Chinese IME composition is cancelled by
            // the platform editor before this runs; a plain Esc closes a dialog (in-game chat)
            // and otherwise only drops the field.
            if (PlatformTextInputBridge.isEditing()) {
                PlatformTextInputBridge.dismissKeyboard()
                dismissDialog()
                return@addKeyListener
            }
            if (dismissDialog()) return@addKeyListener
            if (currentScreen() == AppScreen.InGame && !gameSession.acceptsKoolKeyboardInput) {
                return@addKeyListener
            }
            if (!UiRegistry.cancelWorldPositionSelection()) navigateBack()
        }
        if (legacyPointerForwarding) {
            InputStack.defaultInputHandler.pointerListeners += GatedPointerListener(
                { shouldForwardKoolInputForScreen(currentScreen(), gameSession.acceptsKoolInput) },
                legacyPointerSink,
            )
        }
    }

    fun resetOnHostFocusLost() {
        legacyPointerSink.resetOnHostFocusLost()
        legacyKeyboardSink.resetOnHostFocusLost()
    }

    fun forwardInputForFrame(isRenderLoopFrame: Boolean, pointer: Pointer = PointerInput.pointerState.primaryPointer) {
        gameKeyboardHandler.syncRegistration()
        // Kool 0.19 dispatches InputStack from KeyboardInput.poll BEFORE updating PointerInput.
        // Forward the new pointer only after poll, avoiding the stale listener and startup drive.
        if (!legacyPointerForwarding && !isRenderLoopFrame) return
        if (shouldForwardKoolInputForScreen(currentScreen(), gameSession.acceptsKoolInput)) {
            legacyPointerSink.onPointer(pointer)
        }
    }
}

/** Kool pointer positions are already in framebuffer pixels; Slick's engine uses AWT logical pixels. */
internal fun pointerToGameScale(parentScreenScale: Float, usesLogicalPointerCoordinates: Boolean): Float =
    if (usesLogicalPointerCoordinates && parentScreenScale.isFinite() && parentScreenScale > 0f) {
        1f / parentScreenScale
    } else {
        1f
    }
