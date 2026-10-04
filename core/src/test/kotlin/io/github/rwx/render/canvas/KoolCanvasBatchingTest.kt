package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.Uint8Buffer
import kotlin.test.*

class KoolCanvasBatchingTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }
    private val viewport = KoolCanvasViewport(1920, 1080)
    private val image = KoolCanvasArgbImage(2, 2, intArrayOf(-1, -1, -1, -1))
    private val store = object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
        override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? =
            if (id.value.startsWith("static-")) image else null
    }

    private fun sprite(id: String, x: Float = 20f, effect: KoolCanvasTextureEffect? = null,
                       state: KoolCanvasState = KoolCanvasState.Default): KoolCanvasCommand.DrawTexture =
        KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(KoolCanvasTextureId(id), 2, 2, hasAlpha = true),
            KoolCanvasRect(0f, 0f, 2f, 2f), KoolCanvasRect(x, 20f, x + 2f, 22f),
            KoolCanvasPaint(textureEffect = effect), state)

    private fun uploadBytes(texture: Texture2d): Uint8Buffer =
        assertIs<Uint8Buffer>(assertIs<BufferedImageData2d>(texture.uploadData).data)

    private fun assertRgba(buffer: Uint8Buffer, size: Int, x: Int, y: Int, vararg channels: Int) {
        assertContentEquals(channels.toList(), (0 until 4).map { buffer[(y * size + x) * 4 + it].toInt() })
    }

    @Test
    fun `different sprites and team colors share one adjacent ordered batch`() {
        val scene = Scene("canvas-batching-test")
        val renderer = KoolCanvasFrameRenderer(store)
        val commands = List(2000) { index ->
            sprite(if (index % 2 == 0) "static-body" else "static-turret", index.toFloat(),
                if (index % 2 == 0) KoolCanvasTextureEffect.TeamColor(KoolCanvasTeamColorMode.entries[(index / 2) % 3],
                    KoolCanvasColor(if (index % 4 == 0) 0xff00ff00.toInt() else 0xffff0000.toInt())) else null,
                KoolCanvasState(transform = KoolCanvasTransform.Identity.rotate(index.toFloat()),
                    clip = KoolCanvasRect(0f, 0f, 1700f, 1080f)))
        }
        renderer.render(scene, KoolCanvasFrame(viewport, commands))
        val visible = scene.children.filterIsInstance<Mesh<*>>().filter { it.isVisible }
        assertEquals(1, visible.size)
        assertEquals(2000, visible.single().instances?.numInstances)
        assertTrue(visible.single().instances!!.maxInstances < 8192)
        val data = visible.single().instances!!.instanceData
        val teamOffset = KoolCanvasSpriteInstanceLayout.team.byteOffset
        assertEquals(0f, data.buffer.getFloat32(teamOffset))
        assertEquals(1f, data.buffer.getFloat32(teamOffset + 4))
        assertEquals(1f, data.buffer.getFloat32(2 * data.strideBytes + teamOffset))
        assertEquals(0f, data.buffer.getFloat32(2 * data.strideBytes + teamOffset + 4))
        val modeOffset = KoolCanvasSpriteInstanceLayout.axisYModeAmount.byteOffset + 8
        assertEquals(1f, data.buffer.getFloat32(modeOffset))
        assertEquals(2f, data.buffer.getFloat32(2 * data.strideBytes + modeOffset))
        assertEquals(3f, data.buffer.getFloat32(4 * data.strideBytes + modeOffset))
    }

    @Test
    fun `sprite instances preserve affine placement flipped UVs and clipping across frames`() {
        val scene = Scene("canvas-sprite-placement-test")
        val renderer = KoolCanvasFrameRenderer(store)
        val command = sprite("static-body", state = KoolCanvasState(
            transform = KoolCanvasTransform(scaleX = 2f, skewX = -1f, skewY = 0.5f, scaleY = 3f,
                translateX = 10f, translateY = -5f),
            clip = KoolCanvasRect(10f, 20f, 200f, 400f),
        )).copy(source = KoolCanvasRect(2f, 0f, 0f, 2f), destination = KoolCanvasRect(3f, 4f, 7f, 9f))
        repeat(2) {
            renderer.render(scene, KoolCanvasFrame(viewport, listOf(command, command)))
            val mesh = scene.children.filterIsInstance<Mesh<*>>().single { it.isVisible }
            assertEquals(2, mesh.instances!!.numInstances)
            val data = mesh.instances!!.instanceData
            fun values(offset: Int) = FloatArray(4) { data.buffer.getFloat32(offset + it * 4) }
            assertContentEquals(floatArrayOf(-948f, 531.5f, 8f, -2f),
                values(KoolCanvasSpriteInstanceLayout.originAxisX.byteOffset))
            assertContentEquals(floatArrayOf(-5f, -15f, 0f, 0f),
                values(KoolCanvasSpriteInstanceLayout.axisYModeAmount.byteOffset))
            assertContentEquals(floatArrayOf(4f / 2048f, 2f / 2048f, 2f / 2048f, 4f / 2048f),
                values(KoolCanvasSpriteInstanceLayout.uv.byteOffset))
            assertContentEquals(floatArrayOf(-950f, 140f, -760f, 520f),
                values(KoolCanvasSpriteInstanceLayout.clip.byteOffset))
            assertEquals(data.buffer.getFloat32(KoolCanvasSpriteInstanceLayout.originAxisX.byteOffset),
                data.buffer.getFloat32(data.strideBytes + KoolCanvasSpriteInstanceLayout.originAxisX.byteOffset))
        }
    }

    @Test
    fun `atlas growth refreshes the texture of a reused batch`() {
        val scene = Scene("canvas-atlas-growth-test")
        val renderer = KoolCanvasFrameRenderer(store)
        renderer.render(scene, KoolCanvasFrame(viewport, listOf(sprite("static-body"))))
        val mesh = scene.children.filterIsInstance<Mesh<*>>().single { it.isVisible }
        val shader = mesh.shader as KoolCanvasSpriteShader
        val firstTexture = shader.colorMap
        renderer.render(scene, KoolCanvasFrame(viewport, listOf(sprite("static-body"), sprite("static-turret"))))
        assertSame(mesh, scene.children.filterIsInstance<Mesh<*>>().single { it.isVisible })
        assertEquals(2, mesh.instances!!.numInstances)
        assertNotSame(firstTexture, shader.colorMap)
        val grownTexture = shader.colorMap
        renderer.render(scene, KoolCanvasFrame(viewport, listOf(sprite("static-turret"))))
        assertSame(grownTexture, shader.colorMap)
        assertEquals(1, mesh.instances!!.numInstances)
    }

    @Test
    fun `primitive barrier preserves sprite command order`() {
        val scene = Scene("canvas-order-test")
        val line = KoolCanvasCommand.DrawLine(KoolCanvasPoint(0f, 0f), KoolCanvasPoint(20f, 20f),
            KoolCanvasPaint(color = KoolCanvasColor(0x8000ff00.toInt())), KoolCanvasState.Default)
        KoolCanvasFrameRenderer(store).render(scene,
            KoolCanvasFrame(viewport, listOf(sprite("static-body"), line, sprite("static-turret"))))
        val visible = scene.children.filterIsInstance<Mesh<*>>().filter { it.isVisible }.sortedBy { it.drawGroupId }
        assertEquals(3, visible.size)
        assertEquals(listOf(true, false, true), visible.map { it.instances != null })
        assertEquals(3, visible.map { it.drawGroupId }.distinct().size)
    }

    @Test
    fun `atlas rejects dynamic displacement premultiplied and out of bounds sources`() {
        val atlas = KoolCanvasSpriteAtlas(store, pageSize = 16, maxPages = 1)
        assertNull(atlas.slot(sprite("dynamic-map")))
        val base = sprite("static-body")
        assertNull(atlas.slot(base.copy(texture = base.texture.copy(premultipliedAlpha = true))))
        assertNull(atlas.slot(base.copy(source = KoolCanvasRect(-1f, 0f, 2f, 2f))))
        assertNull(atlas.slot(base.copy(paint = KoolCanvasPaint(textureEffect =
            KoolCanvasTextureEffect.Displacement(base.texture, 1f)))))
        assertNotNull(atlas.slot(base.copy(source = KoolCanvasRect(2f, 2f, 0f, 0f))))
    }

    @Test
    fun `atlas guard pixels repeat all source edges and flipped UVs remain flipped`() {
        val colored = KoolCanvasArgbImage(2, 2,
            intArrayOf(0x80123456.toInt(), 0xff9abcde.toInt(), 0x00112233, 0x4065a4e3))
        val coloredStore = object : KoolCanvasTextureStore by store {
            override fun staticArgbImage(id: KoolCanvasTextureId) = colored
        }
        val atlas = KoolCanvasSpriteAtlas(coloredStore, pageSize = 8, maxPages = 1)
        val command = sprite("static-body")
        atlas.prepare(listOf(command))
        val slot = atlas.slot(command)!!
        val texture = atlas.texture(slot.page, KoolCanvasTextureFilter.Linear)
        val upload = assertIs<BufferedImageData2d>(texture.uploadData)
        assertEquals(TexFormat.RGBA, upload.format)
        assertEquals(8, upload.width)
        assertEquals(8, upload.height)
        val bytes = uploadBytes(texture)
        assertEquals(8 * 8 * 4, bytes.capacity)
        assertEquals(0, bytes.position)
        // Check the bytes the backend will upload, including alpha and transparent RGB.
        assertRgba(bytes, 8, 0, 0, 0x12, 0x34, 0x56, 0x80)
        assertRgba(bytes, 8, 5, 0, 0x9a, 0xbc, 0xde, 0xff)
        assertRgba(bytes, 8, 0, 5, 0x11, 0x22, 0x33, 0x00)
        assertRgba(bytes, 8, 5, 5, 0x65, 0xa4, 0xe3, 0x40)
        assertRgba(bytes, 8, 2, 2, 0x12, 0x34, 0x56, 0x80)
        assertRgba(bytes, 8, 3, 2, 0x9a, 0xbc, 0xde, 0xff)
        assertRgba(bytes, 8, 2, 3, 0x11, 0x22, 0x33, 0x00)
        assertRgba(bytes, 8, 3, 3, 0x65, 0xa4, 0xe3, 0x40)
        assertRgba(bytes, 8, 6, 0, 0, 0, 0, 0)
        assertRgba(bytes, 8, 0, 6, 0, 0, 0, 0)
        assertRgba(bytes, 8, 7, 7, 0, 0, 0, 0)
        val uv = slot.uv(KoolCanvasRect(2f, 2f, 0f, 0f))
        assertTrue(uv.left > uv.right)
        assertTrue(uv.top > uv.bottom)
    }

    @Test
    fun `atlas growth keeps previous texture upload bytes immutable while publishing fresh pixels`() {
        val pendingRetirements = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pendingRetirements += it }
        try {
        val growingStore = object : KoolCanvasTextureStore by store {
            override fun staticArgbImage(id: KoolCanvasTextureId) = KoolCanvasArgbImage(2, 2,
                IntArray(4) { if (id.value == "static-body") 0x80123456.toInt() else 0x40a1b2c3 })
        }
        val atlas = KoolCanvasSpriteAtlas(growingStore, pageSize = 16, maxPages = 1)
        val body = sprite("static-body")
        val turret = sprite("static-turret")
        atlas.prepare(listOf(body))
        val firstSlot = atlas.slot(body)!!
        val firstTexture = atlas.texture(firstSlot.page, KoolCanvasTextureFilter.Linear)
        val firstPayload = uploadBytes(firstTexture)
        val beforeGrowth = firstPayload.toArray()
        atlas.prepare(listOf(body, turret))
        val addedSlot = atlas.slot(turret)!!
        assertSame(firstSlot.page, addedSlot.page)
        val grownTexture = atlas.texture(addedSlot.page, KoolCanvasTextureFilter.Linear)
        val grownPayload = uploadBytes(grownTexture)
        assertNotSame(firstTexture, grownTexture)
        assertNotSame(firstPayload, grownPayload)
        assertContentEquals(beforeGrowth, uploadBytes(firstTexture).toArray(),
            "Old upload data may still be consumed by an in-flight frame")
        assertRgba(firstPayload, 16, addedSlot.x, addedSlot.y, 0, 0, 0, 0)
        assertRgba(grownPayload, 16, addedSlot.x, addedSlot.y, 0xa1, 0xb2, 0xc3, 0x40)
        assertRgba(grownPayload, 16, firstSlot.x, firstSlot.y, 0x12, 0x34, 0x56, 0x80)
        val nearestTexture = atlas.texture(addedSlot.page, KoolCanvasTextureFilter.Nearest)
        val nearestPayload = uploadBytes(nearestTexture)
        assertNotSame(grownPayload, nearestPayload)
        assertContentEquals(grownPayload.toArray(), nearestPayload.toArray())
        assertEquals(1, pendingRetirements.size)
        } finally {
            pendingRetirements.forEach { it() }
            KoolCanvasGpuRetirement.install(null)
        }
    }

    @Test
    fun `atlas reuses upload memory only after GPU retirement and preserves live filter payloads`() {
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            val atlas = KoolCanvasSpriteAtlas(store, pageSize = 16, maxPages = 1)
            val body = sprite("static-body")
            val turret = sprite("static-turret")
            atlas.prepare(listOf(body))
            val page = atlas.slot(body)!!.page
            val old = atlas.texture(page, KoolCanvasTextureFilter.Linear)
            val oldBytes = uploadBytes(old)
            atlas.prepare(listOf(body, turret))
            val live = atlas.texture(page, KoolCanvasTextureFilter.Linear)
            val liveBytes = uploadBytes(live)
            val before = liveBytes.toArray()
            assertNotSame(oldBytes, liveBytes)
            assertEquals(1, pending.size)
            pending.removeAt(0).invoke() // The old frame's completion fence has passed.
            assertTrue(old.isReleased)
            val nearest = atlas.texture(page, KoolCanvasTextureFilter.Nearest)
            assertSame(oldBytes, uploadBytes(nearest))
            assertNotSame(liveBytes, uploadBytes(nearest))
            assertContentEquals(before, liveBytes.toArray())
            assertContentEquals(before, uploadBytes(nearest).toArray())
        } finally {
            pending.forEach { it() }
            KoolCanvasGpuRetirement.install(null)
        }
    }

    @Test
    fun `selection rings retain every marker using one instanced batch`() {
        val scene = Scene("canvas-rings-test")
        val commands = List(2000) { index -> KoolCanvasCommand.DrawCircle(KoolCanvasPoint(index.toFloat(), 20f), 12f,
            KoolCanvasPaint(style = KoolCanvasPaintStyle.Stroke, color = KoolCanvasColor(0xff00ff00.toInt())),
            KoolCanvasState(drawRole = KoolCanvasDrawRole.SelectionRing, semanticUnitId = index.toLong())) }
        KoolCanvasFrameRenderer(store).render(scene, KoolCanvasFrame(viewport, commands,
            KoolCanvasVisualStats(2000, 2000, true)))
        val visible = scene.children.filterIsInstance<Mesh<*>>().filter { it.isVisible }
        assertEquals(1, visible.size)
        assertEquals(2000, visible.single().instances?.numInstances)
        assertEquals(4, visible.single().geometry.numVertices)
    }

    @Test
    fun `adaptive display samples stable waypoint IDs but preserves unknown and selection commands`() {
        val policy = KoolCanvasAdaptiveVisuals()
        fun waypoint(id: Long, role: KoolCanvasDrawRole = KoolCanvasDrawRole.Waypoint) =
            KoolCanvasCommand.DrawLine(KoolCanvasPoint(0f, 0f), KoolCanvasPoint(20f, 20f), KoolCanvasPaint.Default,
                KoolCanvasState(drawRole = role, semanticUnitId = id))
        val waypoints = (20L downTo 1L).map { waypoint(it) }
        val generic = waypoint(20L, KoolCanvasDrawRole.Generic)
        val selection = waypoint(20L, KoolCanvasDrawRole.SelectionRing)
        val shadow = waypoint(20L, KoolCanvasDrawRole.UnitShadow)
        val frame = KoolCanvasFrame(viewport, waypoints + listOf(generic, selection, shadow), KoolCanvasVisualStats(257, 513, true))
        policy.prepare(frame)
        assertEquals((1L..8L).toList(), waypoints.filter { policy.shouldDraw(it) }.map { it.state.semanticUnitId }.sorted())
        assertTrue(policy.shouldDraw(generic))
        assertTrue(policy.shouldDraw(selection))
        assertFalse(policy.shouldDraw(shadow))
        policy.prepare(frame.copy(visualStats = KoolCanvasVisualStats(192, 384, true)))
        assertTrue(policy.simplifiedWaypoints)
        assertTrue(policy.simplifiedShadows)
        policy.prepare(frame.copy(visualStats = KoolCanvasVisualStats(191, 383, true)))
        assertTrue(frame.commands.all { policy.shouldDraw(it) })
        policy.prepare(frame.copy(visualStats = KoolCanvasVisualStats(2000, 2000, false)))
        assertTrue(frame.commands.all { policy.shouldDraw(it) })
        // A large offscreen selection still limits command previews; a small visible scene keeps shadows.
        policy.prepare(frame.copy(visualStats = KoolCanvasVisualStats(1000, 10, true)))
        assertEquals(8, waypoints.count { policy.shouldDraw(it) })
        assertTrue(policy.shouldDraw(selection))
        assertTrue(policy.shouldDraw(shadow))
    }

    @Test fun `repeated picture refreshes only the isolated performance HUD text mesh`() {
        KoolCanvasFontRegistry.installBaseFont(MsdfFont.DEFAULT_FONT)
        var rates: CanvasFrameRateSample? = null
        val scene = Scene("canvas-hud-repeat-test")
        val renderer = KoolCanvasFrameRenderer(store, performanceRates = { rates })
        val hud = KoolCanvasCommand.DrawText("255fps", KoolCanvasPoint(100f, 35f), KoolCanvasPaint.Default,
            KoolCanvasState(drawRole = KoolCanvasDrawRole.PerformanceHud))
        val otherText = hud.copy(text = "Original unit text", baseline = KoolCanvasPoint(100f, 85f), state = KoolCanvasState.Default)
        renderer.render(scene, KoolCanvasFrame(viewport, listOf(sprite("static-body"), hud, otherText)))
        val meshes = scene.children.filterIsInstance<Mesh<*>>().filter { it.isVisible }.sortedBy { it.drawGroupId }
        assertEquals(3, meshes.size)
        val spriteData = meshes[0].instances!!.instanceData
        val oldHudVertices = meshes[1].geometry.numVertices
        val otherVertices = meshes[2].geometry.numVertices
        rates = CanvasFrameRateSample(1.0, 120.0, 255.0, 60.0, 60.0, .5)
        assertTrue(renderer.refreshPerformanceHud(viewport))
        assertSame(spriteData, meshes[0].instances!!.instanceData)
        assertEquals(1, meshes[0].instances!!.numInstances)
        assertTrue(meshes[1].geometry.numVertices > oldHudVertices)
        assertEquals(otherVertices, meshes[2].geometry.numVertices)
        assertFalse(renderer.refreshPerformanceHud(viewport), "Same published counter must reuse its text geometry")
        assertEquals("255fps", hud.text)
    }
}
