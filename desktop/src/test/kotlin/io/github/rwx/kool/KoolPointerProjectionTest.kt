package io.github.rwx.kool

import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameCameraSnapshot
import io.github.rwx.session.GamePointerFrameContext
import io.github.rwx.session.GameHudLayoutSnapshot
import io.github.rwx.render.canvas.KoolCanvasRect
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoolPointerProjectionTest {
    @Test fun `camera drag has no movement from stale frames or camera inertia`() {
        val seen = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f)
        var current = seen
        var previous = 800f to 500f
        for ((x, y) in listOf(840f to 520f, 840f to 520f, 850f to 530f, 850f to 530f)) {
            // The owner advances while rendering continues to display the same older picture.
            current = current.copy(x = current.x + 75f, y = current.y - 30f)
            val projected = projectSeenPointer(seen, current, current.viewport, x, y, screenRelative = true)
            assertEquals(x - previous.first to y - previous.second,
                projected.first - previous.first to projected.second - previous.second)
            previous = projected
        }
    }

    @Test fun `camera button and hover use screen space with both mouse order settings`() {
        assertTrue(isScreenRelativePointer(2, true, 1))
        assertTrue(isScreenRelativePointer(1, true, 2))
        assertTrue(isScreenRelativePointer(3, true, 1))
        assertTrue(isScreenRelativePointer(-1, true, 1))
        assertTrue(isScreenRelativePointer(1, false, 1))
        assertEquals(false, isScreenRelativePointer(1, true, 1))
        assertEquals(false, isScreenRelativePointer(2, true, 2))
    }

    @Test fun `resized camera drag scales screen coordinates without camera compensation`() {
        val seen = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f)
        val surface = KoolCanvasViewport(1920, 900)
        val current = seen.copy(viewport = surface, x = 5000f, y = 6000f, zoom = 0.5f)
        assertEquals(1200f to 625f, projectSeenPointer(seen, current, surface, 1200f, 625f,
            surfaceViewport = surface, screenRelative = true))
    }

    @Test fun `click keeps its seen world target across a camera and resolution change`() {
        val seen = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f)
        val current = GameCameraSnapshot(2, 2, KoolCanvasViewport(1920, 1080), 400f, 350f, 0.75f)
        val projected = projectSeenPointer(seen, current, current.viewport, 800f, 500f)
        assertEquals(500f, projected.first / current.zoom + current.x)
        assertEquals(450f, projected.second / current.zoom + current.y)
    }

    @Test fun `seen sidebar click scales its HUD position instead of becoming a world command`() {
        val seen = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f, sidebarWidth = 200f)
        val current = seen.copy(viewport = KoolCanvasViewport(1920, 1080), x = 5000f, y = 5000f)
        val projected = projectSeenPointer(seen, current, current.viewport, 1200f, 600f)
        assertEquals(1840f to 900f, projected)
    }

    @Test fun `old picture stretched with a changed aspect ratio still selects the seen world point`() {
        val seen = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f, sidebarWidth = 200f)
        val surface = KoolCanvasViewport(1920, 900)
        val current = seen.copy(revision = 2, viewportRevision = 2, viewport = surface,
            x = 400f, y = 350f, zoom = 0.75f, sidebarWidth = 220f)
        // The seen (800, 500) point appears at (1200, 625) in the resized framebuffer.
        val projected = projectSeenPointer(seen, current, current.viewport, 1200f, 625f, surface)
        assertEquals(500f, projected.first / current.zoom + current.x)
        assertEquals(450f, projected.second / current.zoom + current.y)
    }

    @Test fun `stretched sidebar left edge remains in HUD when sidebar widths scale differently`() {
        val seen = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f, sidebarWidth = 200f)
        val surface = KoolCanvasViewport(1920, 900)
        val current = seen.copy(viewport = surface, x = 5000f, y = 5000f, sidebarWidth = 220f)
        val projected = projectSeenPointer(seen, current, current.viewport, 1650f, 625f, surface)
        assertEquals(1722f, projected.first) // Seen x1100, 10% into sidebar: current x1700 + 22.
        assertTrue(projected.first >= current.viewport.width - current.sidebarWidth)
        assertEquals(625f, projected.second)
    }

    @Test fun `queued viewport and camera changes use the event context rather than later publication`() {
        val seen = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f)
        val event = GamePointerFrameContext(seen, KoolCanvasViewport(1920, 900))
        var current = seen
        val loop = EngineOwnerLoop({ 1_000_000L }, {}, { throw it })
        try {
            loop.submit { current = seen.copy(viewport = KoolCanvasViewport(1600, 1000),
                x = 400f, y = 350f, zoom = 0.75f) }.get(5, TimeUnit.SECONDS)
            val projected = loop.submit {
                projectSeenPointer(event.camera, current, current.viewport, 1200f, 625f, event.surfaceViewport)
            }.get(5, TimeUnit.SECONDS)
            assertEquals(500f, projected.first / current.zoom + current.x)
            assertEquals(450f, projected.second / current.zoom + current.y)
        } finally { loop.close() }
    }

    @Test fun `invalid current zoom cannot move a seen HUD click into the current world`() {
        val seen = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f, sidebarWidth = 200f)
        val surface = KoolCanvasViewport(1920, 900)
        val current = seen.copy(viewport = surface, zoom = Float.NaN, sidebarWidth = 220f)
        val projected = projectSeenPointer(seen, current, current.viewport, 1650f, 625f, surface)
        assertTrue(projected.first.isFinite() && projected.second.isFinite())
        assertTrue(projected.first >= current.viewport.width - current.sidebarWidth)
    }

    @Test fun `minimap map fraction survives stretched old picture resize in both directions`() {
        val sizes = listOf(KoolCanvasViewport(1280, 720), KoolCanvasViewport(1920, 900))
        for ((oldSize, newSize) in listOf(sizes[0] to sizes[1], sizes[1] to sizes[0])) {
            val seen = hudCamera(oldSize)
            val current = hudCamera(newSize)
            val oldMini = seen.hudLayout!!.minimap!!
            val x = oldMini.left + 125f
            val physicalX = x * newSize.width / oldSize.width
            val physicalY = 150f * newSize.height / oldSize.height
            val projected = projectSeenPointer(seen, current, newSize, physicalX, physicalY, newSize)
            val newMini = current.hudLayout!!.minimap!!
            assertEquals(0.5f, (projected.first - newMini.left) / newMini.width)
            assertEquals(0.6f, (projected.second - newMini.top) / newMini.height)
        }
    }

    @Test fun `unselect button y270 remains on the same control after resize and back`() {
        val sizes = listOf(KoolCanvasViewport(1280, 720), KoolCanvasViewport(1920, 900))
        for ((oldSize, newSize) in listOf(sizes[0] to sizes[1], sizes[1] to sizes[0])) {
            val seen = hudCamera(oldSize)
            val current = hudCamera(newSize)
            val oldButton = seen.hudLayout!!.unselectButton!!
            val x = (oldButton.left + oldButton.right) * 0.5f
            val projected = projectSeenPointer(seen, current, newSize,
                x * newSize.width / oldSize.width, 270f * newSize.height / oldSize.height, newSize)
            assertEquals(270f, projected.second)
            assertEquals((current.hudLayout!!.unselectButton!!.left + current.hudLayout!!.unselectButton!!.right) * 0.5f, projected.first)
        }
    }

    @Test fun `UI scale changes preserve minimap fraction unselect fraction and bottom group identity`() {
        val seen = hudCamera(KoolCanvasViewport(1280, 720))
        val current = hudCamera(KoolCanvasViewport(1920, 900), scale = 1.5f, sidebar = 375f)
        val projectedMini = projectSeenPointer(seen, current, current.viewport, 1155f, 150f)
        assertEquals(225f, projectedMini.second)
        val projectedButton = projectSeenPointer(seen, current, current.viewport, 1155f, 270f)
        assertEquals(396f, projectedButton.second) // Original scale formula gives38px height: top377 +19.
        val oldGroup = seen.hudLayout!!.unitGroupButtons[1]
        val newGroup = current.hudLayout!!.unitGroupButtons[1]
        val projectedGroup = projectSeenPointer(seen, current, current.viewport,
            (oldGroup.left + oldGroup.right) * 0.5f, (oldGroup.top + oldGroup.bottom) * 0.5f)
        assertEquals((newGroup.left + newGroup.right) * 0.5f, projectedGroup.first)
        assertEquals((newGroup.top + newGroup.bottom) * 0.5f, projectedGroup.second)
    }

    @Test fun `DPI changes without resizing preserve minimap unselect and group targets in both directions`() {
        val viewport = KoolCanvasViewport(2560, 1440)
        val normal = hudCamera(viewport)
        val retina = hudCamera(viewport, scale = 2f, sidebar = 500f)
        for ((seen, current) in listOf(normal to retina, retina to normal)) {
            val oldHud = seen.hudLayout!!
            val newHud = current.hudLayout!!
            val controls = listOf(oldHud.minimap!! to newHud.minimap!!,
                oldHud.unselectButton!! to newHud.unselectButton!!) +
                oldHud.unitGroupButtons.zip(newHud.unitGroupButtons)
            for ((oldRect, newRect) in controls) {
                // A queued click still refers to the old picture, even though the new HUD is larger
                // or smaller on the same framebuffer. Preserve the position within each control.
                val x = oldRect.left + oldRect.width * 0.25f
                val y = oldRect.top + oldRect.height * 0.75f
                val projected = projectSeenPointer(seen, current, viewport, x, y, viewport)
                assertEquals(newRect.left + newRect.width * 0.25f, projected.first)
                assertEquals(newRect.top + newRect.height * 0.75f, projected.second)
            }
        }
    }

    @Test fun `DPI changes without resizing preserve sidebar action column and row in both directions`() {
        val viewport = KoolCanvasViewport(2560, 1440)
        val normal = hudCamera(viewport)
        val retina = hudCamera(viewport, scale = 2f, sidebar = 500f)
        for ((seen, current) in listOf(normal to retina, retina to normal)) {
            val oldHud = seen.hudLayout!!
            val newHud = current.hudLayout!!
            for (columnFraction in listOf(0.25f, 0.75f)) {
                val x = viewport.width - seen.sidebarWidth + seen.sidebarWidth * columnFraction
                val y = oldHud.actionTop + oldHud.actionRowPitch * 2.5f
                for (screenRelative in listOf(false, true)) {
                    val projected = projectSeenPointer(seen, current, viewport, x, y, viewport,
                        screenRelative = screenRelative)
                    assertEquals(viewport.width - current.sidebarWidth + current.sidebarWidth * columnFraction,
                        projected.first)
                    assertEquals(newHud.actionTop + newHud.actionRowPitch * 2.5f, projected.second, 0.001f)
                    assertTrue(projected.first >= viewport.width - current.sidebarWidth)
                }
            }
        }
    }

    @Test fun `alternate left bottom minimap is classified before world camera projection`() {
        val seen = hudCamera(KoolCanvasViewport(1280, 720), leftMinimap = true)
        val current = hudCamera(KoolCanvasViewport(1920, 900), leftMinimap = true)
        val projected = projectSeenPointer(seen, current, current.viewport, 187.5f, 775f, current.viewport)
        assertEquals(125f to 800f, projected)
    }

    @Test fun `packet minimap can change after HUD button geometry was recorded`() {
        val seen = hudCamera(KoolCanvasViewport(1280, 720))
        val oldLayout = seen.hudLayout!!
        val postDrawMini = KoolCanvasRect(1000f, 0f, 1280f, 280f)
        val packet = seen.copy(hudLayout = oldLayout.copy(minimap = postDrawMini))
        val current = hudCamera(KoolCanvasViewport(1920, 900))
        val button = oldLayout.unselectButton!!
        assertEquals(button, packet.hudLayout!!.unselectButton)
        val projected = projectSeenPointer(packet, current, current.viewport, postDrawMini.left + 140f, 140f)
        assertEquals(125f, projected.second) // The displayed minimap's280px geometry is used.
    }

    private fun hudCamera(viewport: KoolCanvasViewport, scale: Float = 1f, sidebar: Float = 250f,
        leftMinimap: Boolean = false): GameCameraSnapshot {
        val mini = if (leftMinimap) KoolCanvasRect(0f, viewport.height - sidebar, sidebar, viewport.height.toFloat())
            else KoolCanvasRect(viewport.width - sidebar, 0f, viewport.width.toFloat(), sidebar)
        val buttonHeight = ((viewport.height / 14f) / scale).coerceIn(25f * scale, 40f * scale)
        val unselectHeight = (buttonHeight.toDouble() * 0.9).toFloat().toInt()
        val unselect = KoolCanvasRect(viewport.width - sidebar + 2f, mini.bottom + 2f,
            viewport.width - 2f, mini.bottom + 2f + unselectHeight)
        val groupSlot = (sidebar - 20f).toInt() / 3
        val groups = List(3) { index ->
            val left = viewport.width - sidebar + 10f + index * groupSlot
            val top = (viewport.height - 30f * scale).toInt().toFloat()
            KoolCanvasRect(left, top, left + groupSlot - 5, top + (31f * scale).toInt())
        }
        return GameCameraSnapshot(1, 1, viewport, 100f, 200f, 2f, sidebarWidth = sidebar,
            hudLayout = GameHudLayoutSnapshot(mini, unselect, mini.bottom + 4f + unselectHeight,
                buttonHeight + 2f, groups, scale, !leftMinimap))
    }

    @Test fun `invalid zoom or absent initial snapshot cannot produce infinite coordinates`() {
        val current = GameCameraSnapshot(2, 2, KoolCanvasViewport(1920, 1080), 400f, 350f, 1f)
        val invalid = current.copy(viewport = KoolCanvasViewport(1280, 720), zoom = 0f)
        assertEquals(1200f to 750f, projectSeenPointer(invalid, current, current.viewport, 800f, 500f))
        val initial = projectSeenPointer(null, null, KoolCanvasViewport(0, 0), 800f, 500f)
        assertTrue(initial.first.isFinite() && initial.second.isFinite())
        assertEquals(800f to 500f, GamePointerFrameContext(invalid, KoolCanvasViewport(0, 0))
            .positionInSeenViewport(800f, 500f))
    }
}
