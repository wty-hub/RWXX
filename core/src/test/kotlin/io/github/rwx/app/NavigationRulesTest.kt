package io.github.rwx.app

import io.github.rwx.render.canvas.KoolCanvasBlendMode
import io.github.rwx.render.canvas.KoolCanvasColor
import io.github.rwx.render.canvas.KoolCanvasCommand
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.ui.AppScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationRulesTest {
    @Test
    fun `escape from the battle room closes the room`() {
        assertEquals(
            BackNavigationAction.CloseBattleRoom,
            backActionForScreen(AppScreen.BattleRoom, rendersIntoKoolCanvas = true),
        )
        assertEquals(
            BackNavigationAction.CloseBattleRoom,
            backActionForScreen(AppScreen.BattleRoom, rendersIntoKoolCanvas = false),
        )
    }

    @Test
    fun `the settings page handles its own escape instead of the global table`() {
        // ESC in the settings page must mirror its own Back button: the global table would send a
        // page opened from the in-game pause menu to the main menu and drop the running match.
        assertEquals(
            BackNavigationAction.MainMenu,
            backActionForScreen(AppScreen.Settings, rendersIntoKoolCanvas = true),
        )
        assertTrue(screenHandlesOwnBackNavigation(AppScreen.Settings))
        for (screen in AppScreen.entries.filterNot { it == AppScreen.Settings }) {
            assertFalse(screenHandlesOwnBackNavigation(screen), "unexpected own-back screen: $screen")
        }
    }

    @Test
    fun `escape resumes a paused game and keeps the other screen destinations`() {
        assertEquals(
            BackNavigationAction.MainMenu,
            backActionForScreen(AppScreen.Multiplayer, rendersIntoKoolCanvas = true),
        )
        assertEquals(
            BackNavigationAction.Pause,
            backActionForScreen(AppScreen.InGame, rendersIntoKoolCanvas = true),
        )
        assertEquals(
            BackNavigationAction.Pause,
            backActionForScreen(AppScreen.InGame, rendersIntoKoolCanvas = false),
        )
        assertEquals(
            BackNavigationAction.InGame,
            backActionForScreen(AppScreen.Paused, rendersIntoKoolCanvas = true),
        )
        assertEquals(
            BackNavigationAction.InGame,
            backActionForScreen(AppScreen.Paused, rendersIntoKoolCanvas = false),
        )
    }

    @Test
    fun `external game frame is presented by Kool only when compositing is enabled`() {
        fun usesFrame(gameVisible: Boolean, loading: Boolean, composite: Boolean) =
            shouldUseRwCanvasFrameForFrame(
                isRwGameVisible = gameVisible,
                isRwMenuBackgroundVisible = false,
                isResumeBackgroundVisible = false,
                isRwGameLoading = loading,
                isLastExternalFrameBackgroundVisible = false,
                rendersIntoKoolCanvas = false,
                compositesExternalGameFrameInKool = composite,
            )

        assertTrue(usesFrame(gameVisible = true, loading = false, composite = true))
        assertFalse(usesFrame(gameVisible = true, loading = false, composite = false))
        assertFalse(usesFrame(gameVisible = false, loading = true, composite = true))
        assertFalse(usesFrame(gameVisible = false, loading = false, composite = true))
    }

    @Test
    fun `external game keeps Kool visible with either a composited frame or mod HUD`() {
        assertTrue(shouldKeepKoolVisibleForExternalGame(true, false))
        assertTrue(shouldKeepKoolVisibleForExternalGame(false, true))
        assertFalse(shouldKeepKoolVisibleForExternalGame(false, false))
    }

    @Test
    fun `unavailable external frame uses an opaque black clear at the current viewport`() {
        val viewport = KoolCanvasViewport(1920, 1080)
        val frame = KoolCanvasFrame(viewport, emptyList()).withDefaultSurfaceClear()

        assertEquals(viewport, frame.viewport)
        assertEquals(
            KoolCanvasCommand.Clear(
                color = KoolCanvasColor(0xff000000.toInt()),
                blendMode = KoolCanvasBlendMode.Source,
            ),
            frame.commands.single(),
        )
    }
}
