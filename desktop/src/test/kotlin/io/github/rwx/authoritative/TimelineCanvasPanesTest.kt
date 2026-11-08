package io.github.rwx.authoritative

import io.github.rwx.render.canvas.*
import kotlin.test.*

class TimelineCanvasPanesTest {
    private val viewport = KoolCanvasViewport(101, 99)
    private val paint = KoolCanvasPaint(color = KoolCanvasColor(0xff112233.toInt()))
    private val texture = KoolCanvasTextureRef(KoolCanvasTextureId("timeline-test"), 16, 16, hasAlpha = true)

    @Test fun `one two three and four layouts preserve odd pixels without overlapping hit regions`() {
        assertEquals(listOf(TimelinePaneViewport(9, KoolCanvasRect(0f, 0f, 101f, 99f), viewport)),
            TimelineCanvasPanes.layout(viewport, listOf(9)))
        val two = TimelineCanvasPanes.layout(viewport, listOf(3, 8))
        assertEquals(KoolCanvasViewport(50, 99), two[0].viewport)
        assertEquals(KoolCanvasViewport(51, 99), two[1].viewport)
        assertEquals(3, TimelineCanvasPanes.hitTest(two, KoolCanvasPoint(49.999f, 98.999f))?.paneId)
        assertEquals(8, TimelineCanvasPanes.hitTest(two, KoolCanvasPoint(50f, 0f))?.paneId)
        assertEquals(KoolCanvasPoint(0f, 0f), TimelineCanvasPanes.localPoint(two[1], KoolCanvasPoint(50f, 0f)))
        val four = TimelineCanvasPanes.layout(viewport, listOf(11, 12, 13, 14))
        assertEquals(listOf(KoolCanvasViewport(50, 49), KoolCanvasViewport(51, 49),
            KoolCanvasViewport(50, 50), KoolCanvasViewport(51, 50)), four.map { it.viewport })
        assertEquals(viewport.width * viewport.height, four.sumOf { it.viewport.width * it.viewport.height })
        assertEquals(14, TimelineCanvasPanes.hitTest(four, KoolCanvasPoint(50f, 49f))?.paneId)
        val three = TimelineCanvasPanes.layout(viewport, listOf(11, 12, 13))
        assertNull(TimelineCanvasPanes.hitTest(three, KoolCanvasPoint(50f, 49f)))
        for (point in listOf(KoolCanvasPoint(-1f, 0f), KoolCanvasPoint(101f, 0f), KoolCanvasPoint(0f, 99f),
            KoolCanvasPoint(Float.NaN, 0f), KoolCanvasPoint(0f, Float.POSITIVE_INFINITY))) {
            assertNull(TimelineCanvasPanes.hitTest(four, point))
            assertNull(TimelineCanvasPanes.localPoint(four[0], point))
        }
        assertNull(TimelineCanvasPanes.localPoint(two[0], KoolCanvasPoint(50f, 0f)))
    }

    @Test fun `every command variant preserves payload and ordering while only its state is translated`() {
        val panes = TimelineCanvasPanes.layout(viewport, listOf(10, 20))
        val originalState = KoolCanvasState(KoolCanvasTransform(scaleX = 2f, skewY = .5f, skewX = -.25f,
            scaleY = 3f, translateX = 7f, translateY = 9f), KoolCanvasRect(-5f, 4f, 200f, 70f),
            drawRole = KoolCanvasDrawRole.Waypoint, semanticUnitId = 42)
        val input = variants(panes[1].viewport, originalState)
        val stats = KoolCanvasVisualStats(2, 5)
        val frames = listOf(10 to KoolCanvasFrame(panes[0].viewport, emptyList()),
            20 to KoolCanvasFrame(panes[1].viewport, input, stats))
        val combined = TimelineCanvasPanes.compose(viewport, frames, 20)
        val body = combined.commands.drop(1).dropLast(1) // compositor background and active border
        assertEquals(10, input.size); assertEquals(input.size, body.size)
        assertSame(stats, combined.visualStats)
        assertTrue(combined.commands.none { it is KoolCanvasCommand.Clear })
        for (index in input.indices) {
            val before = input[index]
            val after = body[index]
            if (before is KoolCanvasCommand.Clear) {
                val rect = assertIs<KoolCanvasCommand.DrawRect>(after)
                assertEquals(panes[1].rect, rect.rect); assertEquals(panes[1].rect, rect.state.clip)
                assertEquals(before.color, rect.paint.color); assertEquals(before.blendMode, rect.paint.blendMode)
            } else {
                assertEquals(before::class, after::class)
                val state = checkNotNull(state(after))
                assertEquals(KoolCanvasRect(50f, 4f, 101f, 70f), state.clip)
                assertEquals(57f, state.transform.translateX)
                assertEquals(originalState.transform.map(KoolCanvasPoint(3f, 4f)).let { KoolCanvasPoint(it.x + 50, it.y) },
                    state.transform.map(KoolCanvasPoint(3f, 4f)))
                assertEquals(KoolCanvasDrawRole.Waypoint, state.drawRole); assertEquals(42L, state.semanticUnitId)
                assertEquals(before, replaceState(after, originalState))
            }
        }
        val image = body.filterIsInstance<KoolCanvasCommand.DrawTexture>().single()
        assertSame(texture, image.texture)
        assertSame(input.filterIsInstance<KoolCanvasCommand.DrawTextureBatch>().single().quads,
            body.filterIsInstance<KoolCanvasCommand.DrawTextureBatch>().single().quads)
        assertSame(input.filterIsInstance<KoolCanvasCommand.DrawFogBatch>().single().masks,
            body.filterIsInstance<KoolCanvasCommand.DrawFogBatch>().single().masks)
        assertTrue(input.filterNot { it is KoolCanvasCommand.Clear }.all { state(it) === originalState })
    }

    @Test fun `disjoint clips drop commands and a late pane clear cannot erase another pane`() {
        val panes = TimelineCanvasPanes.layout(viewport, listOf(1, 2))
        val leftText = KoolCanvasCommand.DrawText("left", KoolCanvasPoint(1f, 1f), paint, KoolCanvasState.Default)
        val right = listOf(
            KoolCanvasCommand.DrawText("before", KoolCanvasPoint(1f, 1f), paint, KoolCanvasState.Default),
            KoolCanvasCommand.Clear(KoolCanvasColor(0x44112233), KoolCanvasBlendMode.Source),
            KoolCanvasCommand.DrawText("clipped", KoolCanvasPoint(1f, 1f), paint,
                KoolCanvasState(clip = KoolCanvasRect(200f, 0f, 300f, 20f))),
            KoolCanvasCommand.DrawText("empty", KoolCanvasPoint(1f, 1f), paint,
                KoolCanvasState(clip = KoolCanvasRect(0f, 0f, 0f, 20f))),
            KoolCanvasCommand.DrawText("after", KoolCanvasPoint(1f, 1f), paint,
                KoolCanvasState(transform = KoolCanvasTransform(scaleX = 100f), clip = KoolCanvasRect(0f, 0f, 3f, 4f))))
        val combined = TimelineCanvasPanes.compose(viewport, listOf(1 to KoolCanvasFrame(panes[0].viewport, listOf(leftText)),
            2 to KoolCanvasFrame(panes[1].viewport, right)), 1)
        assertEquals(listOf("left", "before", "after"), combined.commands.filterIsInstance<KoolCanvasCommand.DrawText>().map { it.text })
        val clear = combined.commands.filterIsInstance<KoolCanvasCommand.DrawRect>().single { it.paint.color.argb == 0x44112233 }
        assertEquals(panes[1].rect, clear.rect); assertEquals(panes[1].rect, clear.state.clip)
        val after = combined.commands.filterIsInstance<KoolCanvasCommand.DrawText>().last()
        assertEquals(KoolCanvasRect(50f, 0f, 53f, 4f), after.state.clip) // clip was already local-screen space
    }

    @Test fun `all offscreen variants reject even when their pane clip would discard them`() {
        val target = KoolCanvasRenderTargetId("not-the-root")
        val state = KoolCanvasState(renderTarget = target, clip = KoolCanvasRect(500f, 500f, 600f, 600f))
        for (command in variants(viewport, state)) {
            val offscreen = if (command is KoolCanvasCommand.Clear) command.copy(renderTarget = target) else command
            assertFailsWith<IllegalArgumentException> {
                TimelineCanvasPanes.compose(viewport, listOf(0 to KoolCanvasFrame(viewport, listOf(offscreen))), 0)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            TimelineCanvasPanes.compose(viewport, listOf(0 to KoolCanvasFrame(KoolCanvasViewport(100, 99), emptyList())), 0)
        }
        assertFailsWith<IllegalArgumentException> { TimelineCanvasPanes.layout(viewport, emptyList()) }
        assertFailsWith<IllegalArgumentException> { TimelineCanvasPanes.layout(viewport, listOf(1, 1)) }
        assertFailsWith<IllegalArgumentException> { TimelineCanvasPanes.layout(viewport, listOf(1, 2, 3, 4, 5)) }
        assertFailsWith<IllegalArgumentException> { TimelineCanvasPanes.compose(viewport,
            listOf(0 to KoolCanvasFrame(viewport, emptyList())), 1) }
    }

    @Test fun `zero and single pixel surfaces never invent drawable or hittable area`() {
        val empty = KoolCanvasViewport(0, 0)
        val layout = TimelineCanvasPanes.layout(empty, listOf(0, 1))
        assertNull(TimelineCanvasPanes.hitTest(layout, KoolCanvasPoint(0f, 0f)))
        val clear = KoolCanvasCommand.Clear(KoolCanvasColor.White)
        assertTrue(TimelineCanvasPanes.compose(empty, layout.map { it.paneId to KoolCanvasFrame(it.viewport, listOf(clear)) }, 0).commands.isEmpty())
        val tiny = TimelineCanvasPanes.layout(KoolCanvasViewport(1, 1), listOf(0, 1))
        assertEquals(1, TimelineCanvasPanes.hitTest(tiny, KoolCanvasPoint(0f, 0f))?.paneId)
        assertEquals(KoolCanvasViewport(0, 1), tiny[0].viewport)
    }

    private fun variants(viewport: KoolCanvasViewport, state: KoolCanvasState): List<KoolCanvasCommand> {
        val batch = KoolCanvasCommandBuffer(viewport, textureBatches = true, rectBatches = true, fogBatches = true)
        batch.drawTexture(texture, KoolCanvasRect(0f, 0f, 10f, 10f))
        batch.drawRect(KoolCanvasRect(1f, 2f, 3f, 4f), paint)
        batch.setDrawRole(KoolCanvasDrawRole.MapFog)
        batch.drawRect(KoolCanvasRect(0f, 0f, 5f, 5f), KoolCanvasPaint(color = KoolCanvasColor(0x88000000.toInt())))
        val recorded = batch.snapshot().commands
        return listOf(
            KoolCanvasCommand.Clear(KoolCanvasColor(0xff998877.toInt()), KoolCanvasBlendMode.Source),
            KoolCanvasCommand.DrawTexture(texture, texture.fullRect, KoolCanvasRect(1f, 2f, 3f, 4f), paint, state),
            KoolCanvasCommand.DrawTextureRepeat(texture, texture.fullRect, KoolCanvasRect(1f, 2f, 30f, 40f), 3, paint, state),
            recorded.filterIsInstance<KoolCanvasCommand.DrawTextureBatch>().single().copy(state = state),
            recorded.filterIsInstance<KoolCanvasCommand.DrawRectBatch>().single().copy(state = state),
            recorded.filterIsInstance<KoolCanvasCommand.DrawFogBatch>().single().copy(state = state),
            KoolCanvasCommand.DrawRect(KoolCanvasRect(1f, 2f, 3f, 4f), paint, state),
            KoolCanvasCommand.DrawLine(KoolCanvasPoint(1f, 2f), KoolCanvasPoint(3f, 4f), paint, state),
            KoolCanvasCommand.DrawCircle(KoolCanvasPoint(1f, 2f), 3f, paint, state),
            KoolCanvasCommand.DrawText("时间窗🛰️", KoolCanvasPoint(1f, 2f), paint, state))
    }

    private fun state(command: KoolCanvasCommand): KoolCanvasState? = when (command) {
        is KoolCanvasCommand.Clear -> null
        is KoolCanvasCommand.DrawTexture -> command.state
        is KoolCanvasCommand.DrawTextureRepeat -> command.state
        is KoolCanvasCommand.DrawTextureBatch -> command.state
        is KoolCanvasCommand.DrawRectBatch -> command.state
        is KoolCanvasCommand.DrawFogBatch -> command.state
        is KoolCanvasCommand.DrawRect -> command.state
        is KoolCanvasCommand.DrawLine -> command.state
        is KoolCanvasCommand.DrawCircle -> command.state
        is KoolCanvasCommand.DrawText -> command.state
    }
    private fun replaceState(command: KoolCanvasCommand, state: KoolCanvasState): KoolCanvasCommand = when (command) {
        is KoolCanvasCommand.Clear -> command
        is KoolCanvasCommand.DrawTexture -> command.copy(state = state)
        is KoolCanvasCommand.DrawTextureRepeat -> command.copy(state = state)
        is KoolCanvasCommand.DrawTextureBatch -> command.copy(state = state)
        is KoolCanvasCommand.DrawRectBatch -> command.copy(state = state)
        is KoolCanvasCommand.DrawFogBatch -> command.copy(state = state)
        is KoolCanvasCommand.DrawRect -> command.copy(state = state)
        is KoolCanvasCommand.DrawLine -> command.copy(state = state)
        is KoolCanvasCommand.DrawCircle -> command.copy(state = state)
        is KoolCanvasCommand.DrawText -> command.copy(state = state)
    }
}
