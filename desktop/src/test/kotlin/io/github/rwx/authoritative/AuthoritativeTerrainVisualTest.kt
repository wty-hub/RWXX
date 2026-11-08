package io.github.rwx.authoritative

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.net.authoritative.PlayerViewSnapshot
import io.github.rwx.net.authoritative.PublicMapVisual
import io.github.rwx.net.authoritative.PublicTerrainCatalog
import io.github.rwx.net.authoritative.ViewUnit
import io.github.rwx.render.canvas.*
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Bundled PNGs through the display-only asset and canvas path; never opens a map or a window. */
class AuthoritativeTerrainVisualTest {
    private val assetRoot: Path get() = listOf(Path.of("assets"), Path.of("..", "assets"))
        .firstOrNull { Files.isRegularFile(it.resolve("tilesets/bitmaps/snow.png")) }
        ?: error("Bundled test assets were not found")

    private fun readAsset(path: String): ByteArray = Files.readAllBytes(assetRoot.resolve(path))

    @Test
    fun `every approved cell crops the actual eleven bundled atlases without recoloring or extra reads`() {
        val before = GameEngine.getInstance()
        val requested = mutableListOf<String>()
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { path -> requested += path; readAsset(path) }.use { assets ->
                val sheets = linkedMapOf<String, BufferedImage>()
                val draws = mutableListOf<KoolCanvasCommand.DrawTexture>()
                try {
                    var count = 0
                    for ((name, dimensions) in expectedSheets) {
                        val path = "tilesets/bitmaps/$name"
                        val original = ImageIO.read(assetRoot.resolve(path).toFile())
                        sheets[path] = original
                        assertEquals(dimensions.first, original.width, name)
                        assertEquals(dimensions.second, original.height, name)
                        val cells = if (name == "misc.png") 9 else original.width / 20 * (original.height / 20)
                        for (localIndex in 0 until cells) {
                            val id = assertNotNull(PublicTerrainCatalog.id(name, localIndex))
                            val tile = assertNotNull(assets.terrainTile(id))
                            assertSame(tile, assets.terrainTile(id), "Cells and atlas registration must be cached")
                            assertEquals(original.width, tile.texture.width)
                            assertEquals(original.height, tile.texture.height)
                            val x = localIndex % (original.width / 20) * 20
                            val y = localIndex / (original.width / 20) * 20
                            assertEquals(KoolCanvasRect(x.toFloat(), y.toFloat(), x + 20f, y + 20f), tile.source)
                            val pixels = assertNotNull(store.argbImageView(tile.texture.id))
                            val crop = IntArray(400) { index -> pixels.pixels[(y + index / 20) * pixels.width + x + index % 20] }
                            assertContentEquals(original.getRGB(x, y, 20, 20, null, 0, 20), crop,
                                "$name cell $localIndex must preserve the exact straight-alpha PNG colors")
                            val left = (count % 18) * 40f
                            val top = (count / 18) * 46f
                            draws += KoolCanvasCommand.DrawTexture(tile.texture, tile.source,
                                KoolCanvasRect(left, top, left + 40, top + 40),
                                KoolCanvasPaint.DefaultNearest, KoolCanvasState.Default)
                            count++
                        }
                    }
                    assertEquals(PublicTerrainCatalog.MAX_TILE_ID, count)
                    assertEquals(expectedSheets.keys.map { "tilesets/bitmaps/$it" }, requested)
                    assertEquals(11, draws.map { it.texture.id }.distinct().size,
                        "All 234 cells share only eleven registered atlas images")
                    assertSame(before, GameEngine.getInstance())
                    saveCatalogPreview(draws, store)
                    store.freezeFrame(KoolCanvasFrame(KoolCanvasViewport(720, 644), draws), 1, 1, 0, 0).use { packet ->
                        assertEquals(11, packet.resourceLease.resourceCount)
                        assets.close()
                        draws.map { it.texture.id }.distinct().forEach { assertNull(store.argbImageView(it)) }
                        assertEquals(11, packet.resourceLease.resourceCount,
                            "Closing the catalog must leave queued frames owning all their immutable atlas pixels")
                    }
                    assertFailsWith<IllegalStateException> { assets.terrainTile(1) }
                } finally { sheets.values.forEach(BufferedImage::flush) }
            }
        }
    }

    @Test
    fun `zero unknown and unapproved terrain cells never request a file`() {
        var reads = 0
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { reads++; error("An unknown id must not read") }.use { assets ->
                for (id in listOf(Int.MIN_VALUE, -1, 0, PublicTerrainCatalog.MAX_TILE_ID + 1, Int.MAX_VALUE)) {
                    assertNull(assets.terrainTile(id))
                }
                assertNull(PublicTerrainCatalog.id("misc.png", 9))
                assertNull(PublicTerrainCatalog.id("../snow.png", 0))
                assertEquals(0, reads)
                assertEquals(0, store.textureRevision)
            }
        }
    }

    @Test
    fun `terrain rejects missing truncated wrong type oversized and unexpected dimension assets`() {
        fun encoded(type: String, width: Int, height: Int) = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), type, output)
            output.toByteArray()
        }
        val id = assertNotNull(PublicTerrainCatalog.id("snow.png", 0))
        val invalid = listOf(null, byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
            encoded("jpeg", 160, 200), encoded("png", 20, 20), ByteArray(1024 * 1024 + 1))
        for (bytes in invalid) {
            var reads = 0
            KoolCanvasCpuTextureStore().use { store ->
                AuthoritativeVisualAssets(store) { reads++; bytes }.use { assets ->
                    assertNull(assets.terrainTile(id))
                    assertNull(assets.terrainTile(id + 1), "A rejected atlas must be cached for its other cells")
                    assertEquals(1, reads)
                    assertEquals(0, store.textureRevision)
                }
            }
        }
    }

    @Test
    fun `unreadable local terrain asset falls back without interrupting a frame`() {
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { throw java.io.IOException("Missing bundled PNG") }.use { assets ->
                val renderer = AuthoritativeCanvasRenderer(assets, sendIntent = {})
                renderer.updateView(snapshot(2, 2, IntArray(4) { 1 }, IntArray(4)).copy(units = emptyList()), 0L)
                val frame = renderer.render()
                assertTrue(frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().isEmpty())
                assertTrue(frame.commands.filterIsInstance<KoolCanvasCommand.DrawRect>()
                    .any { it.paint.color.argb == 0xff49683d.toInt() })
            }
        }
    }

    @Test
    fun `complete ground and decoration layers precede units and fog in row major order`() = fixture { renderer, assets, _ ->
        val groundIds = intArrayOf(id("snow.png", 0), id("ice.png", 1), id("water.png", 2), id("snow.png", 7))
        val decorationIds = intArrayOf(0, id("misc.png", 0), id("stone2snow.png", 3), 0)
        val view = snapshot(2, 2, groundIds, decorationIds).copy(fog = byteArrayOf(0, 0, 0, 10))
        renderer.updateView(view, 0L)
        val commands = renderer.render(100_000_000L).commands
        val textures = commands.filterIsInstance<KoolCanvasCommand.DrawTexture>()
        val expected = groundIds.map { assertNotNull(assets.terrainTile(it)) } +
            decorationIds.filter { it != 0 }.map { assertNotNull(assets.terrainTile(it)) }
        assertEquals(expected.map { it.texture.id to it.source }, textures.take(6).map { it.texture.id to it.source })
        assertEquals(listOf(KoolCanvasRect(0f, 0f, 20f, 20f), KoolCanvasRect(20f, 0f, 40f, 20f),
            KoolCanvasRect(0f, 20f, 20f, 40f), KoolCanvasRect(20f, 20f, 40f, 40f)), textures.take(4).map { it.destination })
        assertEquals(listOf(KoolCanvasRect(20f, 0f, 40f, 20f), KoolCanvasRect(0f, 20f, 20f, 40f)),
            textures.drop(4).take(2).map { it.destination })
        assertTrue(textures.take(6).all { it.paint == KoolCanvasPaint.DefaultNearest })
        val body = assertNotNull(assets.unit("builder", 0xff16b84e.toInt())).body.id
        val unitIndex = commands.indexOfFirst { it is KoolCanvasCommand.DrawTexture && it.texture.id == body }
        assertTrue(unitIndex > commands.indexOf(textures[5]))
        val fogIndex = commands.indexOfFirst { it is KoolCanvasCommand.DrawRect && it.paint.color.argb == 0xee000000.toInt() }
        assertTrue(fogIndex > unitIndex, "Fog must cover sprites as well as both static map layers")
        assertEquals(4, renderer.renderedGroundCells)
        assertEquals(2, renderer.renderedDecorationCells)
        assertEquals(5, renderer.terrainTextures)
    }

    @Test
    fun `evidence profile uses the permitted row major cells and canonical integer hashes`() = fixture { renderer, _, _ ->
        val initial = snapshot(2, 2, intArrayOf(1, 2, 0, 234), intArrayOf(0, 226, 0, 0))
            .copy(terrain = byteArrayOf(4, 0, 5, 8))
        renderer.updateView(initial, 0L)
        val profile = renderer.mapVisualProfile()
        assertEquals(1, profile.catalogVersion)
        assertEquals(3, profile.groundCells)
        assertEquals(1, profile.decorationCells)
        assertEquals(2, profile.resourcePools)
        assertEquals("af9a64e7e135e0d7b0c732c2c690b707e929af7d4dcb72eeda2975ff71255feb", profile.groundSha256)
        assertEquals("cc5578b368098c4a5f2ab9ff510863732589c23e48c5ab24f9112e4a383c7202", profile.decorationSha256)
        assertEquals(profile, AuthoritativeMapVisualProfile.from(initial.copy(sequence = 2,
            units = emptyList(), mapVisual = initial.mapVisual?.detached())))
        val changed = initial.copy(mapVisual = initial.mapVisual?.copy(groundTileIds = intArrayOf(2, 1, 0, 234)))
        assertNotEquals(profile.groundSha256, AuthoritativeMapVisualProfile.from(changed).groundSha256,
            "Equal cell counts cannot hide a changed row-major layout")
    }

    @Test
    fun `public artwork never restores an enemy omitted from the next view`() = fixture { renderer, assets, _ ->
        val ground = IntArray(400) { id("snow.png", it % 8) }
        val decoration = IntArray(400).apply { this[100] = id("misc.png", 0) }
        val initial = snapshot(20, 20, ground, decoration).copy(units = listOf(
            unit(1, "builder", 100f, 100f), unit(2, "c_tank", 120f, 120f, 1, false)))
        renderer.updateView(initial, 0L)
        renderer.render(100_000_000L)
        val enemy = assertNotNull(assets.unit("c_tank", 0xffdf3b32.toInt()))
        val enemyIds = listOfNotNull(enemy.body.id, enemy.turret?.id, enemy.shadow?.id).toSet()
        renderer.updateView(initial.copy(sequence = 2, units = initial.units.take(1)), 100_000_000L)
        val frame = renderer.render(100_000_001L)
        assertFalse(frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().any { it.texture.id in enemyIds })
        renderer.updateView(initial, 200_000_000L)
        assertEquals(2L, renderer.viewSequence)
        assertFalse(renderer.render().commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().any { it.texture.id in enemyIds })
    }

    @Test
    fun `absent or unsupported visual catalog keeps debug terrain without loading atlas files`() {
        val requested = mutableListOf<String>()
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { path -> requested += path; readAsset(path) }.use { assets ->
                val renderer = AuthoritativeCanvasRenderer(assets, sendIntent = {})
                val initial = snapshot(2, 2, IntArray(4) { 1 }, IntArray(4)).copy(units = emptyList())
                renderer.updateView(initial.copy(mapVisual = null), 0L)
                assertTrue(renderer.render().commands.filterIsInstance<KoolCanvasCommand.DrawRect>()
                    .any { it.paint.color.argb == 0xff49683d.toInt() })
                renderer.updateView(initial.copy(sequence = 2,
                    mapVisual = initial.mapVisual?.copy(catalogVersion = PublicTerrainCatalog.VERSION + 1)), 1L)
                renderer.render()
                assertTrue(requested.isEmpty())
            }
        }
    }

    private fun id(name: String, index: Int) = assertNotNull(PublicTerrainCatalog.id(name, index))

    private fun snapshot(width: Int, height: Int, ground: IntArray, decoration: IntArray) = PlayerViewSnapshot(
        1, 100, 0, 4000.0, width, height, 20, 20, ByteArray(width * height),
        listOf(unit(1, "builder", 10f, 10f)), ByteArray(width * height),
        mapVisual = PublicMapVisual(PublicTerrainCatalog.VERSION, ground, decoration))

    private fun unit(handle: Long, type: String, x: Float, y: Float, team: Int = 0, controllable: Boolean = true) =
        ViewUnit(handle, type, team, x, y, 0f, 0f, 100f, 100f, 1f, controllable)

    private fun fixture(block: (AuthoritativeCanvasRenderer, AuthoritativeVisualAssets, KoolCanvasCpuTextureStore) -> Unit) {
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store, ::readAsset).use { assets ->
                val renderer = AuthoritativeCanvasRenderer(assets, sendIntent = {})
                renderer.setViewport(KoolCanvasViewport(800, 600))
                block(renderer, assets, store)
            }
        }
    }

    private fun saveCatalogPreview(draws: List<KoolCanvasCommand.DrawTexture>, store: KoolCanvasCpuTextureStore) {
        val preview = BufferedImage(720, (draws.size + 17) / 18 * 46, BufferedImage.TYPE_INT_ARGB)
        val graphics = preview.createGraphics()
        try {
            graphics.color = Color(35, 41, 49)
            graphics.fillRect(0, 0, preview.width, preview.height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            draws.forEachIndexed { index, command ->
                val pixels = assertNotNull(store.argbImageView(command.texture.id))
                val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                try {
                    image.setRGB(0, 0, pixels.width, pixels.height, pixels.pixels, 0, pixels.width)
                    val dest = command.destination
                    val src = command.source
                    graphics.drawImage(image, dest.left.toInt(), dest.top.toInt(), dest.right.toInt(), dest.bottom.toInt(),
                        src.left.toInt(), src.top.toInt(), src.right.toInt(), src.bottom.toInt(), null)
                    graphics.color = Color(230, 235, 241)
                    graphics.drawString((index + 1).toString(), dest.left + 1, dest.top + 45)
                } finally { image.flush() }
            }
        } finally { graphics.dispose() }
        try {
            val output = Path.of("build/verification/authority-public-terrain-catalog-headless.png")
            Files.createDirectories(output.parent)
            ImageIO.write(preview, "png", output.toFile())
        } finally { preview.flush() }
    }

    companion object {
        private val expectedSheets = linkedMapOf(
            "snow.png" to (160 to 200), "ice.png" to (100 to 40), "shallowwater.png" to (60 to 40),
            "water.png" to (60 to 20), "snow2ice_ridge.png" to (60 to 140), "ice2shallowwater_ridge.png" to (60 to 140),
            "shallowwater2water_flat.png" to (60 to 140), "ice2ice_flat.png" to (60 to 140),
            "ice2shallowwater_broken.png" to (60 to 140), "stone2snow.png" to (60 to 140), "misc.png" to (79 to 160),
        )
    }
}
