package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import io.github.rwx.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class KoolImmediateTargetClearTest {
    @Test
    fun `dirty pooled full clears initialize every pixel before partial drawing or alpha clear`() {
        val foreground = 0xffd04020.toInt()
        val background = 0x7d103040
        val draw = KoolCanvasCommand.DrawRect(KoolCanvasRect(1f, 1f, 3f, 2f),
            KoolCanvasPaint.Default.copy(color = KoolCanvasColor(foreground)), KoolCanvasState.Default)
        val cases = listOf(
            "transparent clear alone" to listOf(clear(0, KoolCanvasBlendMode.Clear)),
            "black then transparent" to listOf(clear(0xff000000.toInt()), clear(0, KoolCanvasBlendMode.Clear), draw),
            "final colored clear" to listOf(clear(0, KoolCanvasBlendMode.Clear), clear(background), draw),
            "colored then alpha clear" to listOf(clear(background), clear(0, KoolCanvasBlendMode.ClearAlpha), draw),
        )
        for ((name, commands) in cases) {
            val pool = KoolCanvasPixelPool(maxRetainedBytes = WIDTH * HEIGHT * 4L, maxArraysPerSize = 1)
            TargetFixture(pool).use { target ->
                val old = pool.borrow(WIDTH * HEIGHT)
                val dirty = old.pixels
                dirty.fill(0x6a909090)
                old.close()
                var oldPixelReads = 0
                target.texture.setArgbPixelLoader { oldPixelReads++; initialPixels() }
                target.recordRawCommands(commands)
                target.graphics.p()
                val expected = IntArray(WIDTH * HEIGHT) {
                    when (name) {
                        "final colored clear" -> background
                        "colored then alpha clear" -> background and 0x00ffffff
                        else -> 0
                    }
                }
                if (name != "transparent clear alone") for (x in 1..2) expected[x + WIDTH] = foreground
                assertSame(dirty, target.pixels(), "$name must exercise the returned dirty pool array")
                assertEquals(0, oldPixelReads, "$name must not resolve discarded pixels")
                assertContentEquals(expected, target.pixels(), "$name clears untouched pixels and retains RGB at alpha clear")
            }
        }
    }

    @Test
    fun `dirty pooled incremental updates copy prior pixels and clear only a missing tail`() {
        val foreground = 0xffd04020.toInt()
        val draw = KoolCanvasCommand.DrawRect(KoolCanvasRect(1f, 1f, 3f, 2f),
            KoolCanvasPaint.Default.copy(color = KoolCanvasColor(foreground)), KoolCanvasState.Default)
        val priorCases = listOf(null, initialPixels().copyOf(5), initialPixels(), initialPixels().copyOf(16))
        for (previous in priorCases) for (alphaClear in listOf(false, true)) {
            val pool = KoolCanvasPixelPool(maxRetainedBytes = WIDTH * HEIGHT * 4L, maxArraysPerSize = 1)
            TargetFixture(pool).use { target ->
                val old = pool.borrow(WIDTH * HEIGHT)
                val dirty = old.pixels
                dirty.fill(0x6a909090)
                old.close()
                val original = previous?.copyOf()
                if (previous != null) target.texture.setCommittedArgbPixels(previous)
                target.recordRawCommands(if (alphaClear) listOf(clear(0, KoolCanvasBlendMode.ClearAlpha), draw) else listOf(draw))
                target.graphics.p()
                val expected = previous?.copyOf(WIDTH * HEIGHT) ?: IntArray(WIDTH * HEIGHT)
                if (alphaClear) for (index in expected.indices) expected[index] = expected[index] and 0x00ffffff
                for (x in 1..2) expected[x + WIDTH] = foreground
                assertSame(dirty, target.pixels(), "must incrementally redraw into returned dirty storage")
                assertContentEquals(expected, target.pixels(), "priorSize=${previous?.size} alphaClear=$alphaClear")
                if (previous != null) assertContentEquals(original, previous, "copying cannot modify caller-owned prior pixels")
            }
        }
    }

    @Test
    fun `leading full clears match separately committed clears without crossing draw or alpha clear`() {
        val background = 0xff103040.toInt()
        val foreground = 0xffd04020.toInt()
        val region = KoolCanvasRect(1f, 1f, 3f, 2f)
        val draw = KoolCanvasCommand.DrawRect(region, KoolCanvasPaint.Default.copy(color = KoolCanvasColor(foreground)), KoolCanvasState.Default)
        val partialClear = draw.copy(paint = KoolCanvasPaint.Default.copy(blendMode = KoolCanvasBlendMode.Clear))
        val cases = listOf(
            "black then transparent" to listOf(clear(0xff000000.toInt()), clear(0, KoolCanvasBlendMode.Clear), draw),
            "black then clear alpha" to listOf(clear(0xff000000.toInt()), clear(0, KoolCanvasBlendMode.ClearAlpha)),
            "colored clear then clear alpha" to listOf(clear(background), clear(0, KoolCanvasBlendMode.ClearAlpha)),
            "draw then clear alpha" to listOf(clear(background), draw, clear(0, KoolCanvasBlendMode.ClearAlpha)),
            "partial rectangle clear" to listOf(clear(background), partialClear),
        )
        for ((name, commands) in cases) {
            TargetFixture().use { folded ->
                TargetFixture().use { separate ->
                    folded.recordRawCommands(commands)
                    folded.graphics.p()
                    for (command in commands) {
                        separate.recordRawCommands(listOf(command))
                        separate.graphics.p()
                    }
                    val expected = IntArray(WIDTH * HEIGHT) {
                        when (name) {
                            "black then transparent", "black then clear alpha" -> 0
                            "colored clear then clear alpha", "draw then clear alpha" -> background and 0x00ffffff
                            else -> background
                        }
                    }
                    for (x in 1..2) {
                        when (name) {
                            "black then transparent" -> expected[x + WIDTH] = foreground
                            "draw then clear alpha" -> expected[x + WIDTH] = foreground and 0x00ffffff
                            "partial rectangle clear" -> expected[x + WIDTH] = 0
                        }
                    }
                    assertContentEquals(separate.pixels(), folded.pixels(), name)
                    assertContentEquals(expected, folded.pixels(), "$name clear and draw boundaries")
                }
            }
        }
    }

    @Test
    fun `omitted leading clear writes retain profile counts and stop at a target clear`() {
        TargetFixture().use { target ->
            val commands = listOf(clear(0xff103040.toInt()), clear(0, KoolCanvasBlendMode.Clear))
            val frame = KoolCanvasFrame(KoolCanvasViewport(WIDTH, HEIGHT), commands)
            val generic = target.rasterize(frame, IntArray(WIDTH * HEIGHT) { 0xff909090.toInt() }, zeroInitialized = false)
            val folded = target.rasterize(frame, IntArray(WIDTH * HEIGHT), zeroInitialized = true)
            assertContentEquals(assertNotNull(generic.first), assertNotNull(folded.first))
            assertContentEquals(IntArray(WIDTH * HEIGHT), folded.first)
            assertEquals(mapOf("totalCommands" to 2, "clearCommands" to 2), folded.second)
            assertEquals(generic.second, folded.second)

            val targetClear = clear(0, KoolCanvasBlendMode.Clear).copy(renderTarget = KoolCanvasRenderTargetId("other-target"))
            val blockedFrame = frame.copy(commands = listOf(clear(0xff103040.toInt()), targetClear, clear(0, KoolCanvasBlendMode.Clear)))
            val pixels = IntArray(WIDTH * HEIGHT)
            val blocked = target.rasterize(blockedFrame, pixels, zeroInitialized = true)
            assertNull(blocked.first, "a target clear must retain the unsupported-command fallback")
            assertContentEquals(IntArray(WIDTH * HEIGHT) { 0xff103040.toInt() }, pixels, "must stop before the later clear")
            assertEquals(mapOf("totalCommands" to 3, "clearCommands" to 2), blocked.second)
        }
    }

    @Test
    fun `full clear matches a fresh target without resolving prior lazy pixels`() {
        for ((color, blend) in listOf(
            0 to KoolCanvasBlendMode.Clear,
            0xff226644.toInt() to KoolCanvasBlendMode.Source,
            0x80224466.toInt() to KoolCanvasBlendMode.SourceOver,
        )) {
            TargetFixture().use { previous ->
                TargetFixture().use { fresh ->
                    var oldPixelReads = 0
                    previous.texture.setArgbPixelLoader { oldPixelReads++; initialPixels() }
                    for (target in listOf(previous, fresh)) {
                        target.graphics.a(color, blend)
                        target.graphics.a(Rect(1, 1, 3, 3))
                        target.graphics.a(Rect(0, 0, WIDTH, HEIGHT), paint(0xffd04020.toInt()))
                        target.graphics.p()
                    }
                    assertEquals(0, oldPixelReads, "$blend must not load discarded pixels")
                    assertContentEquals(fresh.pixels(), previous.pixels(), "$blend dirty and fresh targets")
                    assertEquals(if (blend == KoolCanvasBlendMode.Clear) 0 else color, previous.pixels()[0], "$blend clear outside geometry clip")
                    assertEquals(0xffd04020.toInt(), previous.pixels()[1 + WIDTH], "$blend geometry inside clip")
                }
            }
        }
    }

    @Test
    fun `partial clears and no clear preserve pixels like a separately drawn initial image`() {
        val operations: List<Pair<String, (GraphicsEngine) -> Unit>> = listOf(
            "no clear" to { target: GraphicsEngine -> target.a(Rect(1, 1, 3, 2), paint(0xffc06030.toInt())) },
            "partial clear" to { target: GraphicsEngine -> target.a(Rect(1, 1, 3, 2), paint(0, KoolCanvasBlendMode.Clear)) },
            "clear alpha" to { target: GraphicsEngine -> target.a(0, KoolCanvasBlendMode.ClearAlpha) },
        )
        for ((name, operation) in operations) {
            TargetFixture().use { previous ->
                TargetFixture().use { drawn ->
                    val initial = initialPixels()
                    previous.texture.setCommittedArgbPixels(initial)
                    // Independent initialization: draw a source image into a fresh target.
                    val source = drawn.root.a(WIDTH, HEIGHT, true).apply { j = initial.copyOf(); p() }
                    try {
                        drawn.graphics.a(0, KoolCanvasBlendMode.Clear)
                        drawn.graphics.b(source, 0f, 0f, null)
                        drawn.graphics.p()
                        operation(previous.graphics)
                        operation(drawn.graphics)
                        previous.graphics.p()
                        drawn.graphics.p()
                        val expected = initial.copyOf()
                        when (name) {
                            "no clear" -> for (x in 1..2) expected[x + WIDTH] = 0xffc06030.toInt()
                            "partial clear" -> for (x in 1..2) expected[x + WIDTH] = 0
                            "clear alpha" -> for (index in expected.indices) expected[index] = expected[index] and 0x00ffffff
                        }
                        assertContentEquals(drawn.pixels(), previous.pixels(), name)
                        assertContentEquals(expected, previous.pixels(), "$name retained pixels")
                    } finally {
                        source.o()
                    }
                }
            }
        }
    }

    @Test
    fun `full replacement and incremental clear leave a completed lease unchanged`() {
        TargetFixture().use { target ->
            val initial = initialPixels()
            target.graphics.a(0, KoolCanvasBlendMode.Clear)
            val source = target.root.a(WIDTH, HEIGHT, true).apply { j = initial.copyOf(); p() }
            val packet = try {
                target.graphics.b(source, 0f, 0f, null)
                target.graphics.p()
                target.root.beginFrame(WIDTH, HEIGHT)
                target.root.b(target.texture, 0f, 0f, null)
                target.store.freezeFrame(target.root.snapshot(), 1L, 1L, 1, 1L)
            } finally {
                source.o()
            }
            try {
                target.recordRawCommands(listOf(clear(0xff103040.toInt()), clear(0, KoolCanvasBlendMode.Clear),
                    KoolCanvasCommand.DrawRect(KoolCanvasRect.fromSize(WIDTH.toFloat(), HEIGHT.toFloat()),
                        KoolCanvasPaint.Default.copy(color = KoolCanvasColor(0xff184080.toInt())), KoolCanvasState.Default)))
                target.graphics.p()
                target.graphics.a(Rect(1, 1, 3, 2), paint(0, KoolCanvasBlendMode.Clear))
                target.graphics.p()
                target.graphics.q()
                target.texture.o()
                val resource = packet.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
                assertContentEquals(initial, resource.image.pixels, "old lease after full and partial replacement and disposal")
            } finally {
                packet.close()
            }
        }
    }

    private class TargetFixture(pool: KoolCanvasPixelPool? = null) : AutoCloseable {
        val store = if (pool == null) KoolCanvasCpuTextureStore() else KoolCanvasCpuTextureStore(pool, true)
        val root = KoolGraphicsEngine(textureStore = store)
        val texture = root.b(WIDTH, HEIGHT, true)
        val graphics = root.b(texture, RenderTargetMode.IMMEDIATE)

        fun pixels(): IntArray = assertNotNull(texture.argbPixelsRef)

        @Suppress("UNCHECKED_CAST")
        fun recordRawCommands(commands: List<KoolCanvasCommand>) {
            // The normal recorder already folds non-alpha clears. Supply a raw frame here to
            // exercise the rasterizer's own leading-clear boundary rules independently.
            val buffer = KoolGraphicsEngine::class.java.getDeclaredField("commandBuffer")
                .apply { isAccessible = true }.get(graphics) as KoolCanvasCommandBuffer
            val recorded = KoolCanvasCommandBuffer::class.java.getDeclaredField("commands")
                .apply { isAccessible = true }.get(buffer) as MutableList<KoolCanvasCommand>
            recorded.clear()
            recorded.addAll(commands)
        }

        @Suppress("UNCHECKED_CAST")
        fun rasterize(frame: KoolCanvasFrame, pixels: IntArray, zeroInitialized: Boolean): Pair<IntArray?, Map<String, Int>> {
            val profileClass = KoolGraphicsEngine::class.java.declaredClasses.single { it.simpleName == "CpuTargetProfile" }
            val profile = profileClass.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(texture, WIDTH, HEIGHT)
            val method = KoolGraphicsEngine::class.java.declaredMethods.single { it.name == "rasterizeFrameCommands" }
                .apply { isAccessible = true }
            val result = method.invoke(graphics, frame, pixels, WIDTH, HEIGHT, emptySet<KoolCanvasTextureId>(), profile, zeroInitialized) as IntArray?
            val counts = listOf("totalCommands", "clearCommands").associateWith { name ->
                profileClass.getDeclaredField(name).apply { isAccessible = true }.getInt(profile)
            }
            return result to counts
        }

        override fun close() {
            graphics.q()
            texture.o()
            store.close()
        }
    }

    private companion object {
        const val WIDTH = 4
        const val HEIGHT = 3

        fun initialPixels(): IntArray = IntArray(WIDTH * HEIGHT) { 0xff000000.toInt() or (0x102030 + it * 0x010203) }

        fun paint(color: Int, blend: KoolCanvasBlendMode = KoolCanvasBlendMode.SourceOver): KoolPaint = KoolPaint().apply {
            setColor(color)
            a(blend)
        }

        fun clear(color: Int, blend: KoolCanvasBlendMode = KoolCanvasBlendMode.SourceOver) =
            KoolCanvasCommand.Clear(KoolCanvasColor(color), blend)
    }
}
