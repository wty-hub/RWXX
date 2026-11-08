package io.github.rwx.authoritative

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.net.authoritative.PlayerViewSnapshot
import io.github.rwx.net.authoritative.ViewUnit
import io.github.rwx.render.canvas.*
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.test.*

/** Real packaged artwork and static reference poses; never starts a game or a native window. */
class AuthoritativeStockVisualManifestTest {
    private val assetRoot get() = listOf(Path.of("assets"), Path.of("../assets"))
        .first { Files.isRegularFile(it.resolve("units/tanks/tank.png")) }
    private fun readAsset(path: String) = Files.readAllBytes(assetRoot.resolve(path))

    @Test
    fun `all approved PNG source bytes decode at their pinned dimensions`() {
        assertEquals(152, AuthoritativeUnitVisualManifest.entries.size)
        assertEquals(AuthoritativeVisualAssets.publicTypes, AuthoritativeUnitVisualManifest.entries.keys)
        for ((path, spec) in AuthoritativeUnitVisualManifest.images) {
            val bytes = readAsset(path)
            assertTrue(bytes.size <= 1024 * 1024)
            assertEquals(spec.sha256, MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }, path)
            ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().first()
                try {
                    assertEquals("png", reader.formatName.lowercase(), path)
                    reader.input = input
                    assertEquals(spec.width, reader.getWidth(0), path)
                    assertEquals(spec.height, reader.getHeight(0), path)
                    val decoded = reader.read(0)
                    try { assertEquals(spec.width * spec.height, decoded.width * decoded.height) }
                    finally { decoded.flush() }
                } finally { reader.dispose() }
            }
        }
    }

    @Test
    fun `all 152 public keys have explicit static art or declared nontexture state and own frozen pixels`() {
        val engine = GameEngine.getInstance()
        val requested = mutableSetOf<String>()
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { path ->
                requested += path
                assertTrue(path in AuthoritativeVisualAssets.approvedUnitPaths, "Only generated fixed PNG paths may load: $path")
                readAsset(path)
            }.use { assets ->
                val references = mutableListOf<KoolCanvasTextureRef>()
                for ((key, spec) in AuthoritativeUnitVisualManifest.entries) {
                    val sprite = assets.unit(key, 0xff16b84e.toInt())
                    if (spec.body == null) {
                        assertNull(sprite, key)
                        assertTrue(spec.limitations.isNotEmpty(), "Procedural/invisible types must not pretend to have a complete texture")
                        continue
                    }
                    assertNotNull(sprite, key)
                    assertSame(sprite, assets.unit(key, 0xff16b84e.toInt()), key)
                    assertTrue(sprite.width > 0f && sprite.height > 0f)
                    references += listOfNotNull(sprite.body, sprite.shadow, sprite.turret)
                    references += sprite.fixedParts.map { it.texture }
                    for (part in sprite.fixedParts) {
                        assertTrue(part.width > 0 && part.height > 0)
                        assertEquals(part.texture.fullRect, part.source)
                        assertTrue(part.offsetX.isFinite() && part.offsetY.isFinite() && part.angle.isFinite())
                    }
                    if (spec.turrets.isNotEmpty() && key !in AuthoritativeVisualAssets.supportedTypes) {
                        assertEquals(spec.turrets.size, sprite.fixedParts.size, "Every confirmed fixed turret must be visible: $key")
                        assertTrue("independent_turret_state_required" in sprite.limitations)
                    }
                }
                assertSame(engine, GameEngine.getInstance(), "Art lookup must never initialize a simulation")
                assertTrue(requested.isNotEmpty())
                savePreview(assets, store)
                val unique = references.distinctBy { it.id }
                val draws = unique.map { KoolCanvasCommand.DrawTexture(it, it.fullRect,
                    KoolCanvasRect.fromSize(1f, 1f), KoolCanvasPaint.Default, KoolCanvasState.Default) }
                store.freezeFrame(KoolCanvasFrame(KoolCanvasViewport(1, 1), draws), 1, 1, 0, 0).use { packet ->
                    assertEquals(unique.size, packet.resourceLease.resourceCount)
                    assets.close()
                    unique.forEach { assertNull(store.argbImageView(it.id)) }
                    assertEquals(unique.size, packet.resourceLease.resourceCount, "Published poses retain every atlas/part until consumption")
                }
            }
        }
    }

    @Test
    fun `reviewed native level two images are selected and absent levels explicitly reuse the base pose`() {
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store, ::readAsset).use { assets ->
                for (key in listOf("landFactory", "airFactory", "seaFactory", "laserDefence", "supplyDepot")) {
                    val base = assertNotNull(assets.unit(key, -1))
                    val upgraded = assertNotNull(assets.unit(key, -1, 2))
                    assertNotEquals(base.body.id, upgraded.body.id, key)
                    assertFalse(assertNotNull(store.argbImage(base.body.id)).pixels.contentEquals(
                        assertNotNull(store.argbImage(upgraded.body.id)).pixels), "A verified level-two PNG must actually change displayed pixels: $key")
                    assertSame(base, assets.unit(key, -1, 3), "No reviewed level-three variant means the explicit base fallback")
                }
                assertSame(assets.unit("builder", -1), assets.unit("builder", -1, 2))
                assertNull(assets.unit("tank", -1, 4))
                assertNull(assets.unit("units/tanks/tank.png", -1))
            }
        }
    }

    @Test
    fun `native ladybug uses the actual PlayerTeam color mask rather than an uncolored source frame`() {
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store, ::readAsset).use { assets ->
                val red = assertNotNull(assets.unit("ladybug", 0xffff0000.toInt()))
                val blue = assertNotNull(assets.unit("ladybug", 0xff0000ff.toInt()))
                val redPixels = assertNotNull(store.argbImage(red.body.id)).pixels
                val bluePixels = assertNotNull(store.argbImage(blue.body.id)).pixels
                assertEquals(17, red.body.width)
                assertEquals(26, red.body.height)
                assertFalse(redPixels.contentEquals(bluePixels))
                val original = ImageIO.read(assetRoot.resolve("drawable/ladybug.png").toFile())
                try {
                    for (y in 0 until 26) for (x in 0 until 17) {
                        val source = original.getRGB(x, y)
                        assertEquals(com.corrodinggames.rts.gameFramework.graphics.TeamColoring.pureGreen(source, 0xffff0000.toInt()),
                            redPixels[y * 17 + x])
                    }
                } finally { original.flush() }
            }
        }
    }

    @Test
    fun `tree rest composite includes all 286 opaque secondary frame pixels under the main frame`() {
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store, ::readAsset).use { assets ->
                val sprite = assertNotNull(assets.unit("tree", 0xffff0000.toInt()))
                assertEquals(25, sprite.body.width)
                assertEquals(30, sprite.body.height)
                val sheet = ImageIO.read(assetRoot.resolve("drawable/trees.png").toFile())
                val expected = BufferedImage(25, 30, BufferedImage.TYPE_INT_ARGB)
                try {
                    val base = sheet.getRGB(25, 0, 25, 30, null, 0, 25)
                    val main = sheet.getRGB(0, 0, 25, 30, null, 0, 25)
                    assertEquals(286, base.count { it ushr 24 > 0 })
                    val graphics = expected.createGraphics()
                    try {
                        graphics.drawImage(sheet, 0, 0, 25, 30, 25, 0, 50, 30, null)
                        graphics.drawImage(sheet, 0, 0, 25, 30, 0, 0, 25, 30, null)
                    } finally { graphics.dispose() }
                    val pixels = assertNotNull(store.argbImage(sprite.body.id)).pixels
                    assertContentEquals(expected.getRGB(0, 0, 25, 30, null, 0, 25), pixels)
                    assertFalse(main.contentEquals(pixels), "A single frame silently loses the native secondary layer")
                    assertTrue("tree_subtype_scale_and_falling_state_required" in sprite.limitations)
                } finally { sheet.flush(); expected.flush() }
            }
        }
    }

    @Test
    fun `seven native extra shadows use the exact first frame alpha times the native 128 paint alpha`() {
        val offsets = mapOf("missileShip" to 3f, "gunBoat" to 1f, "battleShip" to 3f, "heavyTank" to 2f,
            "attackSubmarine" to 0f, "builderShip" to 3f, "experimentalHoverTank" to 4f)
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store, ::readAsset).use { assets ->
                for ((key, offset) in offsets) {
                    val sprite = assertNotNull(assets.unit(key, -1))
                    val shadow = assertNotNull(sprite.shadow, key)
                    assertEquals(offset, sprite.shadowOffsetX, key)
                    assertEquals(offset, sprite.shadowOffsetY, key)
                    val layer = assertNotNull(AuthoritativeUnitVisualManifest.entries.getValue(key).body)
                    val source = ImageIO.read(assetRoot.resolve(layer.image).toFile())
                    try {
                        val original = source.getRGB(layer.sourceX, layer.sourceY, layer.width, layer.height, null, 0, layer.width)
                        val expected = IntArray(original.size) { ((original[it] ushr 24) * 128 / 255) shl 24 }
                        assertContentEquals(expected, assertNotNull(store.argbImage(shadow.id)).pixels, key)
                    } finally { source.flush() }
                    assertTrue("extra_shadow_setting_not_in_view" in sprite.limitations)
                    if (key != "heavyTank" && key != "attackSubmarine") {
                        assertFalse(assets.shadowVisible(sprite, -2f, 1f))
                        assertTrue(assets.shadowVisible(sprite, -1.99f, 1f))
                    }
                }
                val submarine = assertNotNull(assets.unit("attackSubmarine", -1))
                assertFalse(assets.shadowVisible(submarine, -.01f, 1f))
                assertTrue(assets.shadowVisible(submarine, 0f, 1f))
                val heavy = assertNotNull(assets.unit("heavyTank", -1))
                assertFalse(assets.shadowVisible(heavy, 0f, .99f))
                assertTrue(assets.shadowVisible(heavy, 0f, 1f))
                assertTrue("shadow_invulnerability_state_not_in_view" in heavy.limitations)
            }
        }
    }

    @Test
    fun `submarine shadow is submitted only for the reviewed surface state`() {
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store, ::readAsset).use { assets ->
                val renderer = AuthoritativeCanvasRenderer(assets, sendIntent = {})
                renderer.setViewport(KoolCanvasViewport(800, 600))
                val unit = ViewUnit(1, "attackSubmarine", 0, 100f, 100f, 0f, -90f, 100f, 100f, 1f, true)
                val view = PlayerViewSnapshot(1, 100, 0, 4000.0, 20, 20, 20, 20, ByteArray(400), listOf(unit), ByteArray(400))
                val shadow = assertNotNull(assertNotNull(assets.unit("attackSubmarine", 0xff16b84e.toInt())).shadow).id
                renderer.updateView(view, 0L)
                assertTrue(renderer.render(100_000_000L).commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().any { it.texture.id == shadow })
                renderer.updateView(view.copy(sequence = 2, units = listOf(unit.copy(height = -1f))), 100_000_000L)
                assertFalse(renderer.render(200_000_000L).commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().any { it.texture.id == shadow })
            }
        }
    }

    @Test
    fun `renderer submits all fixed components and native level art then removes the complete disappeared pose`() {
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store, ::readAsset).use { assets ->
                val renderer = AuthoritativeCanvasRenderer(assets, sendIntent = {})
                renderer.setViewport(KoolCanvasViewport(800, 600))
                val view = PlayerViewSnapshot(1, 100, 0, 4000.0, 20, 20, 20, 20, ByteArray(400), listOf(
                    ViewUnit(1, "experimentalTank", 0, 100f, 100f, 0f, -90f, 100f, 100f, 1f, true),
                    ViewUnit(2, "landFactory", 0, 250f, 100f, 0f, -90f, 100f, 100f, 1f, true, upgradeLevel = 2)), ByteArray(400))
                renderer.updateView(view, 0L)
                val tank = assertNotNull(assets.unit("experimentalTank", 0xff16b84e.toInt()))
                assertTrue(tank.fixedParts.size > 1)
                val expected = (listOf(tank.body.id) + tank.fixedParts.map { it.texture.id } +
                    assertNotNull(assets.unit("landFactory", 0xff16b84e.toInt(), 2)).body.id).toSet()
                val drawn = renderer.render(100_000_000L).commands.filterIsInstance<KoolCanvasCommand.DrawTexture>()
                assertTrue(drawn.map { it.texture.id }.containsAll(expected))
                renderer.updateView(view.copy(sequence = 2, units = emptyList()), 100_000_000L)
                renderer.updateView(view, 200_000_000L)
                assertFalse(renderer.render(200_000_001L).commands.filterIsInstance<KoolCanvasCommand.DrawTexture>()
                    .any { it.texture.id in expected }, "A complete multi-part pose cannot be revived by the old snapshot")
            }
        }
    }

    private fun savePreview(assets: AuthoritativeVisualAssets, store: KoolCanvasCpuTextureStore) {
        val entries = AuthoritativeUnitVisualManifest.entries.values.toList()
        val image = BufferedImage(1200, 60 + (entries.size + 11) / 12 * 140, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        val decoded = mutableMapOf<KoolCanvasTextureId, BufferedImage>()
        try {
            graphics.color = Color(21, 30, 40); graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.WHITE
            graphics.drawString("STATIC REFERENCE POSES: independent aim, animation, legs/arms, modules and procedural state are not reproduced.", 10, 20)
            graphics.drawString("* marks remaining visible-state requirements. The checked-in audit lists each limitation and exact approved source.", 10, 38)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            fun texture(ref: KoolCanvasTextureRef) = decoded.getOrPut(ref.id) {
                val pixels = assertNotNull(store.argbImageView(ref.id))
                BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB).also {
                    it.setRGB(0, 0, pixels.width, pixels.height, pixels.pixels, 0, pixels.width)
                }
            }
            entries.forEachIndexed { index, spec ->
                val x = index % 12 * 100
                val y = 60 + index / 12 * 140
                graphics.color = Color(50, 63, 76); graphics.drawRect(x, y, 99, 139)
                val sprite = assets.unit(spec.key, 0xff16b84e.toInt())
                if (sprite != null) {
                    val previewScale = min(1f, 82 / max(sprite.width, sprite.height))
                    val original = graphics.transform
                    try {
                        graphics.translate(x + 50.0, y + 58.0); graphics.scale(previewScale.toDouble(), previewScale.toDouble())
                        sprite.shadow?.takeIf { assets.shadowVisible(sprite, 0f, 1f) }?.let { shadow ->
                            graphics.drawImage(texture(shadow), AffineTransform.getTranslateInstance(
                                (sprite.shadowOffsetX - sprite.bodyPivotX).toDouble(),
                                (sprite.shadowOffsetY - sprite.bodyPivotY).toDouble()).apply {
                                scale(sprite.width / shadow.width.toDouble(), sprite.height / shadow.height.toDouble()) }, null)
                        }
                        graphics.drawImage(texture(sprite.body), AffineTransform.getTranslateInstance(
                            -sprite.bodyPivotX.toDouble(), -sprite.bodyPivotY.toDouble()).apply {
                            scale(sprite.width / sprite.body.width.toDouble(), sprite.height / sprite.body.height.toDouble()) }, null)
                        for (part in sprite.fixedParts) {
                            val transform = AffineTransform.getTranslateInstance(part.offsetX.toDouble(), part.offsetY.toDouble())
                            transform.rotate(Math.toRadians(part.angle.toDouble()))
                            transform.translate(-part.pivotX.toDouble(), -part.pivotY.toDouble())
                            transform.scale(part.width / part.texture.width.toDouble(), part.height / part.texture.height.toDouble())
                            graphics.drawImage(texture(part.texture), transform, null)
                        }
                        sprite.turret?.let { turret -> graphics.drawImage(texture(turret),
                            (sprite.turretOffsetX - sprite.turretPivotX).toInt(), (sprite.turretOffsetY - sprite.turretPivotY).toInt(),
                            sprite.turretWidth.toInt(), sprite.turretHeight.toInt(), null) }
                    } finally { graphics.transform = original }
                }
                graphics.color = Color(218, 230, 240)
                val label = spec.key + if (spec.limitations.isNotEmpty()) " *" else ""
                graphics.drawString(label.take(15), x + 3, y + 115)
                if (label.length > 15) graphics.drawString(label.drop(15).take(16), x + 3, y + 129)
            }
        } finally { graphics.dispose(); decoded.values.forEach(BufferedImage::flush) }
        try {
            val output = Path.of("build/verification/authority-stock-reference-poses-headless.png")
            Files.createDirectories(output.parent); ImageIO.write(image, "png", output.toFile())
        } finally { image.flush() }
    }
}
