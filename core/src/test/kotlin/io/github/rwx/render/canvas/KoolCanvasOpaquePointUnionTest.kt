package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import com.corrodinggames.rts.gameFramework.graphics.Texture
import io.github.rwx.geometry.Rect
import kotlin.random.Random
import kotlin.test.*

class KoolCanvasOpaquePointUnionTest {
    private fun dense(count: Int = 512) = FloatArray(count * 2) { index ->
        if (index % 2 == 0) ((index / 2) % 8).toFloat() else ((index / 16) % 8).toFloat()
    }
    private fun oldCells(points: FloatArray, start: Int, end: Int, side: Int): Set<Pair<Int, Int>> {
        val result = mutableSetOf<Pair<Int, Int>>()
        var index = start
        while (index + 1 <= end) {
            val x = points[index].toInt(); val y = points[index + 1].toInt()
            for (row in y until y + side) for (column in x until x + side) result += column to row
            index += 2
        }
        return result
    }
    private fun unionCells(result: KoolCanvasOpaquePointUnion.Result): Set<Pair<Int, Int>> {
        val cells = mutableSetOf<Pair<Int, Int>>()
        for (rect in 0 until result.rectCount) {
            val left = result.coordinate(rect, 0); val top = result.coordinate(rect, 1)
            val right = result.coordinate(rect, 2); val bottom = result.coordinate(rect, 3)
            assertEquals(top + 1, bottom)
            assertTrue(left < right)
            for (y in top until bottom) for (x in left until right)
                assertTrue(cells.add(x to y), "output spans must not overlap")
        }
        return cells
    }

    @Test fun `random duplicate overlapping negative squares have identical integer coverage and sample membership`() {
        val random = Random(0x51a7)
        for (side in 1..8) repeat(8) {
            val points = FloatArray(1024) { (random.nextInt(16) - 8).toFloat() }
            val result = assertNotNull(KoolCanvasOpaquePointUnion.tryUnion(points, 0, points.lastIndex, side.toFloat()))
            assertEquals(512, result.inputPoints)
            assertTrue(result.rectCount * 2 < result.inputPoints)
            val reference = oldCells(points, 0, points.lastIndex, side)
            assertEquals(reference, unionCells(result), "side=$side")
            // For integer edges every ordinary MSAA4 sample lies strictly inside one unit cell.
            val samples = listOf(.375 to .125, .875 to .375, .125 to .625, .625 to .875)
            for (cell in listOf(-9 to -9, -1 to -1, 0 to 0, 7 to 7, 15 to 15)) for ((sx, sy) in samples) {
                val px = cell.first + sx; val py = cell.second + sy
                var oldCovered = false
                for (index in points.indices step 2) if (px >= points[index] && px < points[index] + side &&
                    py >= points[index + 1] && py < points[index + 1] + side) oldCovered = true
                val newCovered = (0 until result.rectCount).any { px >= result.coordinate(it, 0) &&
                    px < result.coordinate(it, 2) && py >= result.coordinate(it, 1) && py < result.coordinate(it, 3) }
                assertEquals(oldCovered, newCovered)
            }
        }
    }

    @Test fun `word crossing and largest permitted grid preserve exact coverage without cropping`() {
        val crossing = FloatArray(2048) { i -> if (i % 2 == 0) ((i / 2) % 65 - 37).toFloat() else ((i / 2) % 9 - 5).toFloat() }
        val result = assertNotNull(KoolCanvasOpaquePointUnion.tryUnion(crossing, 0, crossing.lastIndex, 8f))
        assertEquals(oldCells(crossing, 0, crossing.lastIndex, 8), unionCells(result))
        val corners = FloatArray(128) { i -> if ((i / 2) % 2 == 0) -256f else 248f }
        val fullGrid = assertNotNull(KoolCanvasOpaquePointUnion.tryUnion(corners, 0, corners.lastIndex, 8f))
        assertEquals(oldCells(corners, 0, corners.lastIndex, 8), unionCells(fullGrid))
        assertTrue((-256 to -256) in unionCells(fullGrid)); assertTrue((255 to 255) in unionCells(fullGrid))
        for (coordinate in listOf(-1048576f, 1048576f)) {
            val extreme = FloatArray(128) { coordinate }
            val accepted = assertNotNull(KoolCanvasOpaquePointUnion.tryUnion(extreme, 0, extreme.lastIndex, 8f))
            assertEquals(oldCells(extreme, 0, extreme.lastIndex, 8), unionCells(accepted))
        }
    }

    @Test fun `ineligible state opacity or viewport never enters union`() {
        val viewport = KoolCanvasViewport(128, 128); val state = KoolCanvasState.Default
        val paint = KoolCanvasPaint.Default
        assertTrue(KoolCanvasOpaquePointUnion.isEligible(viewport, state, paint, true))
        for (unsupported in listOf(state.copy(clip = KoolCanvasRect(0f, 0f, 128f, 128f)),
            state.copy(transform = KoolCanvasTransform()), state.copy(transform = KoolCanvasTransform.Identity.translate(1f, 0f)),
            state.copy(renderTarget = KoolCanvasRenderTargetId("point-union-target")), state.copy(drawRole = KoolCanvasDrawRole.UnitShadow)))
            assertFalse(KoolCanvasOpaquePointUnion.isEligible(viewport, unsupported, paint, true))
        val effect = KoolCanvasTextureEffect.TeamColor(KoolCanvasTeamColorMode.entries.first(), KoolCanvasColor.White, .5f)
        for (unsupported in listOf(paint.copy(color = KoolCanvasColor(0x80ff0000.toInt())), paint.copy(alphaMultiplier = .5f),
            paint.copy(blendMode = KoolCanvasBlendMode.Add), paint.copy(textureEffect = effect)))
            assertFalse(KoolCanvasOpaquePointUnion.isEligible(viewport, state, unsupported, true))
        assertFalse(KoolCanvasOpaquePointUnion.isEligible(viewport, state, paint, false))
        assertFalse(KoolCanvasOpaquePointUnion.isEligible(KoolCanvasViewport(65537, 128), state, paint, true))
    }

    private fun pointPaint(color: Int = 0xff00ff00.toInt(), size: Float = 2f) = KoolPaint().apply { b(color); a(size) }
    private fun engine(enabled: Boolean, store: KoolCanvasCpuTextureStore = KoolCanvasCpuTextureStore()) =
        KoolGraphicsEngine(textureStore = store).apply { configureUnionOpaquePointsForTest(enabled) }
    private fun commands(frame: KoolCanvasFrame) = frame.commands.flatMap { command ->
        when (command) {
            is KoolCanvasCommand.DrawRect -> listOf(listOf(command.rect.left.toRawBits(), command.rect.top.toRawBits(),
                command.rect.right.toRawBits(), command.rect.bottom.toRawBits(), command.paint, command.state))
            is KoolCanvasCommand.DrawRectBatch -> List(command.rects.size) { index ->
                listOf(command.rects.coordinate(index, 0).toRawBits(), command.rects.coordinate(index, 1).toRawBits(),
                    command.rects.coordinate(index, 2).toRawBits(), command.rects.coordinate(index, 3).toRawBits(),
                    command.paint, command.state)
            }
            else -> error("Expected ordered rectangle draws, got $command")
        }
    }
    private fun pixels(frame: KoolCanvasFrame): Map<Pair<Int, Int>, Int> {
        val result = mutableMapOf<Pair<Int, Int>, Int>()
        frame.commands.forEach {
            val command = assertIs<KoolCanvasCommand.DrawRect>(it)
            val rect = command.rect
            for (y in rect.top.toInt() until rect.bottom.toInt()) for (x in rect.left.toInt() until rect.right.toInt())
                result[x to y] = command.paint.color.argb
        }
        return result
    }

    @Test fun `default recorder preserves exact dense point coverage and explicit rollback`() {
        val control = engine(false)
        val actual = KoolGraphicsEngine()
        val points = dense()
        control.beginFrame(128, 128); actual.beginFrame(128, 128)
        control.a(points, 0, points.size, pointPaint())
        actual.a(points, 0, points.size, pointPaint())
        val reference = control.snapshot(); val frame = actual.snapshot()
        assertEquals(pixels(reference), pixels(frame))
        if (System.getenv("RWX_UNION_OPAQUE_POINTS") == "0") {
            assertEquals(commands(reference), commands(frame), "explicit rollback retains the legacy command sequence")
        } else {
            assertTrue(frame.commands.size * 2 < reference.commands.size, "the default recorder uses exact union")
        }
    }

    @Test fun `real recorder keeps per call team color order and retained frame after coordinate mutation and refill`() {
        val store = KoolCanvasCpuTextureStore(); val control = engine(false); val actual = engine(true, store)
        val points = dense()
        val colors = listOf(0xff00ff00.toInt(), 0xffff0000.toInt(), 0xffffff00.toInt())
        control.beginFrame(128, 128); actual.beginFrame(128, 128)
        for (color in colors) {
            val paint = pointPaint(color)
            control.a(points, 0, points.size, paint); actual.a(points, 0, points.size, paint)
        }
        assertEquals(pixels(control.snapshot()), pixels(actual.snapshot()))
        assertEquals(listOf(3L, 1536L), actual.opaquePointUnionCountersForTest().take(2))
        val actualFrame = actual.snapshot(); val oldCommands = commands(actualFrame)
        assertTrue(actualFrame.commands.size * 2 < control.snapshot().commands.size)
        // Each color remains contiguous and each call is independently retained, including hidden draws.
        assertEquals(colors, actualFrame.commands.map { (it as KoolCanvasCommand.DrawRect).paint.color.argb }.distinct())
        val frozen = store.freezeFrame(actualFrame, 1, 1, 0, 0)
        try {
            points.fill(31.25f)
            actual.beginFrame(128, 128); actual.a(points, 0, points.size, pointPaint())
            assertEquals(oldCommands, commands(frozen.frame))
            assertEquals(512, actual.snapshot().commands.size, "fractional refill takes complete old path")
        } finally { frozen.close(); store.close() }
    }

    @Test fun `unsupported batches retain the entire old command sequence with no partial emission`() {
        data class Case(val points: FloatArray, val paint: KoolPaint = pointPaint(), val width: Int = 128,
                        val setup: (KoolGraphicsEngine) -> Unit = {}, val offscreen: RenderTargetMode? = null)
        val invalidLast = listOf(.25f, -0f, Float.NaN, Float.POSITIVE_INFINITY, 1048577f).map { value ->
            dense().also { it[it.lastIndex] = value }
        }
        val cases = invalidLast.map { Case(it) } + listOf(
            Case(dense(63)), Case(FloatArray(128) { i -> if (i % 2 == 0) (i * 2).toFloat() else 0f }, pointPaint(size = 1f)),
            Case(dense().also { it[it.lastIndex] = 513f }), Case(dense(), pointPaint(size = 1.5f)),
            Case(dense(), pointPaint(size = 9f)), Case(dense(), pointPaint(0x80ff0000.toInt())), Case(dense(), pointPaint(0xfe00ff00.toInt())),
            Case(dense(), pointPaint().apply { a(KoolCanvasBlendMode.Add) }), Case(dense(), width = 65537),
            Case(dense(), setup = { it.a(Rect(0, 0, 128, 128)) }),
            Case(dense(), setup = { it.b(3f, 2f) }),
            Case(dense(), setup = { it.beginDrawRole(KoolCanvasDrawRole.UnitShadow.ordinal, 19) }),
            Case(dense(), offscreen = RenderTargetMode.DEFAULT), Case(dense(), offscreen = RenderTargetMode.IMMEDIATE),
            Case(dense(), offscreen = RenderTargetMode.GPU_TARGET),
        )
        for ((caseIndex, case) in cases.withIndex()) {
            fun fixture(enabled: Boolean): KoolGraphicsEngine {
                val root = engine(enabled)
                val target = if (case.offscreen == null) root else (root.b(Texture().apply { p = 128; q = 128; updateCenter() },
                    case.offscreen) as KoolGraphicsEngine).apply { configureUnionOpaquePointsForTest(enabled) }
                target.beginFrame(case.width, 128); case.setup(target)
                target.a(case.points, 0, case.points.size, case.paint)
                return target
            }
            val control = fixture(false); val actual = fixture(true)
            assertEquals(commands(control.snapshot()), commands(actual.snapshot()), "case=$caseIndex")
            assertEquals(listOf(0L, 0L, 0L), actual.opaquePointUnionCountersForTest(), "case=$caseIndex")
        }
    }

    @Test fun `offset count inclusive end and overflow preserve old bounds rather than conventional point count`() {
        val points = dense()
        val cases = listOf(0 to points.size, 1 to (points.size - 3), -7 to points.size,
            0 to Int.MAX_VALUE, 0 to 1, 0 to 0, 0 to -1, (points.size + 3) to 2)
        for ((offset, count) in cases) {
            val control = engine(false); val actual = engine(true)
            control.beginFrame(128, 128); actual.beginFrame(128, 128)
            control.a(points, offset, count, pointPaint()); actual.a(points, offset, count, pointPaint())
            if (actual.opaquePointUnionCountersForTest()[0] == 0L)
                assertEquals(commands(control.snapshot()), commands(actual.snapshot()), "$offset/$count")
            else assertEquals(pixels(control.snapshot()), pixels(actual.snapshot()), "$offset/$count")
        }
        for (enabled in listOf(false, true)) {
            val engine = engine(enabled); engine.beginFrame(128, 128)
            assertFailsWith<IndexOutOfBoundsException> { engine.a(points, Int.MAX_VALUE, 1, pointPaint()) }
            engine.beginFrame(128, 128); engine.a(null as FloatArray?, 0, 100, pointPaint())
            assertTrue(engine.snapshot().commands.isEmpty())
        }
    }
}
