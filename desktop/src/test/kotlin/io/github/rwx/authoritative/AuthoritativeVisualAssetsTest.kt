package io.github.rwx.authoritative

import com.corrodinggames.rts.gameFramework.graphics.TeamColoring
import io.github.rwx.render.canvas.KoolCanvasCpuTextureStore
import io.github.rwx.render.canvas.KoolCanvasTextureRef
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthoritativeVisualAssetsTest {
    private val assetRoot: Path get() = listOf(Path.of("assets"), Path.of("..", "assets"))
        .firstOrNull { Files.isRegularFile(it.resolve("drawable/builder.png")) }
        ?: error("Bundled test assets were not found")

    private fun readAsset(path: String): ByteArray = Files.readAllBytes(assetRoot.resolve(path))

    @Test
    fun `all supported units register visible bounded straight alpha sprites from bundled assets`() {
        val requested = mutableSetOf<String>()
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { path ->
                requested += path
                assertTrue(path in AuthoritativeVisualAssets.bundledPaths)
                assertTrue(assetRoot.resolve(path).normalize().startsWith(assetRoot.normalize()))
                readAsset(path)
            }.use { catalog ->
                for (type in AuthoritativeVisualAssets.supportedTypes) {
                    val sprite = assertNotNull(catalog.unit(type, 0xffe04040.toInt()), type)
                    assertTrue(sprite.width > 0f && sprite.height > 0f)
                    assertEquals(sprite.body.fullRect, sprite.bodySource)
                    for (ref in listOfNotNull(sprite.body, sprite.turret, sprite.shadow)) {
                        val image = assertNotNull(store.argbImage(ref.id), "$type must register CPU pixels")
                        assertEquals(ref.width, image.width)
                        assertEquals(ref.height, image.height)
                        assertEquals(ref.width * ref.height, image.pixels.size)
                        assertTrue(ref.hasAlpha && ref.requiresOrderedAlpha)
                        assertTrue(!ref.premultipliedAlpha && !image.premultipliedAlpha)
                        assertTrue(image.pixels.any { it ushr 24 > 0 }, "$type must not be blank")
                        assertTrue(image.pixels.any { it ushr 24 == 0 }, "$type must retain transparency")
                    }
                    if (type == "builder" || type == "tank" || type == "c_tank") {
                        assertTrue(sprite.rotatesWithHeading)
                        val shadow = assertNotNull(sprite.shadow)
                        assertTrue(assertNotNull(store.argbImage(shadow.id)).pixels.all {
                            it and 0x00ffffff == 0 && it ushr 24 <= 85
                        })
                    } else assertTrue(!sprite.rotatesWithHeading)
                }
                val tank = assertNotNull(catalog.unit("tank", -1))
                assertEquals(16, tank.body.width, "A tank sprite must not show the three-frame sheet")
                assertEquals(30, tank.body.height)
                assertEquals(12.8f, tank.width)
                assertEquals(24f, tank.height)
                assertEquals(8f, tank.turretWidth)
                assertEquals(32f, tank.turretHeight)
                assertEquals(53, assertNotNull(catalog.unit("commandCenter", -1)).body.width)
                assertSame(tank, catalog.unit("c_tank", -1))
            }
        }
        assertEquals(AuthoritativeVisualAssets.bundledPaths, requested)
    }

    @Test
    fun `network names cannot select files or trigger unknown asset reads`() {
        var reads = 0
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { reads++; error("Unknown type must not read assets") }.use { catalog ->
                for (name in listOf("", "../builder", "drawable/builder.png", "/tmp/image.png",
                    "https://example.invalid/tank.png", "file:///tmp/unit", "c_tank.ini", "Builder", "customUnit")) {
                    assertNull(catalog.unit(name, -1))
                }
                assertEquals(0, reads)
                assertEquals(0, store.textureRevision)
            }
        }
    }

    @Test
    fun `green masks change to team color while alpha and neutral pixels remain intact`() {
        val original = ImageIO.read(assetRoot.resolve("drawable/builder.png").toFile())
        val source = original.getRGB(0, 0, original.width, original.height, null, 0, original.width)
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store, ::readAsset).use { catalog ->
                val redRef = assertNotNull(catalog.unit("builder", 0xffff0000.toInt())).body
                val blueRef = assertNotNull(catalog.unit("builder", 0xff0000ff.toInt())).body
                val red = assertNotNull(store.argbImage(redRef.id)).pixels
                val blue = assertNotNull(store.argbImage(blueRef.id)).pixels
                var colored = 0
                for (i in source.indices) {
                    assertEquals(source[i] ushr 24, red[i] ushr 24)
                    assertEquals(source[i] ushr 24, blue[i] ushr 24)
                    val r = source[i] ushr 16 and 255
                    val g = source[i] ushr 8 and 255
                    val b = source[i] and 255
                    if (source[i] ushr 24 > 0 && g > r && r == b) {
                        colored++
                        assertTrue((red[i] ushr 16 and 255) > (red[i] and 255))
                        assertTrue((blue[i] and 255) > (blue[i] ushr 16 and 255))
                    } else if (source[i] ushr 24 > 0 && (r != b || r == g)) {
                        assertEquals(source[i], red[i])
                        assertEquals(source[i], blue[i])
                    }
                }
                assertTrue(colored > 0, "The bundled builder must contain an actual team-color mask")
            }
        }
    }

    @Test
    fun `turret and extractor use the pinned stock replacements and their complete first frame pixels`() {
        val fingerprints = mapOf(
            "units/turrets/turret_base.png" to "febec2cde7c9102c3729dae6e7fdd81b718f9e1ec11af68b0f332e4ec2c0b938",
            "units/turrets/turret_top.png" to "9f8aa25d4e0f3d5a920981d1927439f2f32ec60a363ad751c8a11f640136005f",
            "units/extractor/extractor.png" to "455955725cdbf5708e2bb82eb4fd8a9ff5f9eba621a1f060b477549a4312146a",
            "units/extractor/extractor_back.png" to "91af6007c19fe84cf3bcef0ba17aaefd274c1a856894343850c9c2513e33c2c3",
        )
        for ((path, expected) in fingerprints) {
            val actual = MessageDigest.getInstance("SHA-256").digest(readAsset(path))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "The closed stock-art identity changed: $path")
        }
        // These files happen to match today, while the stock turret base is a different image.
        assertContentEquals(readAsset("drawable/turret_top.png"), readAsset("units/turrets/turret_top.png"))
        assertContentEquals(readAsset("drawable/extractor.png"), readAsset("units/extractor/extractor.png"))
        assertContentEquals(readAsset("drawable/extractor_back.png"), readAsset("units/extractor/extractor_back.png"))

        val requested = mutableSetOf<String>()
        val color = 0xffa03050.toInt()
        fun tintedPixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            .map { TeamColoring.pureGreen(it, color) }.toIntArray()
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { path -> requested += path; readAsset(path) }.use { catalog ->
                val turret = assertNotNull(catalog.unit("turret", color))
                assertEquals(35, turret.body.width)
                assertEquals(32, turret.body.height)
                assertEquals(35f, turret.width)
                assertEquals(32f, turret.height)
                assertFalse(turret.rotatesWithHeading)
                assertEquals(0f, turret.turretOffsetX)
                assertEquals(-6f, turret.turretOffsetY, "Stock y=6 is forward from a fixed -90 degree building")
                assertEquals(12f, turret.turretWidth)
                assertEquals(46f, turret.turretHeight)
                val base = ImageIO.read(assetRoot.resolve("units/turrets/turret_base.png").toFile())
                val top = ImageIO.read(assetRoot.resolve("units/turrets/turret_top.png").toFile())
                try {
                    assertContentEquals(tintedPixels(base), assertNotNull(store.argbImageView(turret.body.id)).pixels)
                    assertContentEquals(tintedPixels(top), assertNotNull(store.argbImageView(assertNotNull(turret.turret).id)).pixels)
                } finally { base.flush(); top.flush() }

                val extractor = assertNotNull(catalog.unit("extractor", color))
                assertEquals(51, extractor.body.width, "The full 51-pixel background remains centered behind the 37-pixel frame")
                assertEquals(59, extractor.body.height, "Four frames divide the sheet width; its height is not cropped to native 56")
                assertEquals(51f, extractor.width)
                assertEquals(59f, extractor.height)
                assertFalse(extractor.rotatesWithHeading)
                val sheet = ImageIO.read(assetRoot.resolve("units/extractor/extractor.png").toFile())
                val back = ImageIO.read(assetRoot.resolve("units/extractor/extractor_back.png").toFile())
                val expected = BufferedImage(51, 59, BufferedImage.TYPE_INT_ARGB)
                try {
                    assertEquals(148, sheet.width)
                    assertEquals(59, sheet.height)
                    assertEquals(51, back.width)
                    assertEquals(44, back.height)
                    val graphics = expected.createGraphics()
                    try {
                        graphics.drawImage(back, 0, 7, null)
                        graphics.drawImage(sheet, 7, 0, 44, 59, 0, 0, 37, 59, null)
                    } finally { graphics.dispose() }
                    assertContentEquals(tintedPixels(expected), assertNotNull(store.argbImageView(extractor.body.id)).pixels,
                        "Composite must contain the complete first stock frame over its full background")
                } finally { sheet.flush(); back.flush(); expected.flush() }
                assertEquals(fingerprints.keys, requested, "Canonical wire names must select only the stock file table")
            }
        }
    }

    @Test
    fun `native turret base with a different height cannot replace the approved stock image`() {
        KoolCanvasCpuTextureStore().use { store ->
            AuthoritativeVisualAssets(store) { path ->
                if (path == "units/turrets/turret_base.png") readAsset("drawable/turret_base.png")
                else readAsset(path)
            }.use { catalog ->
                assertNull(catalog.unit("turret", -1))
                assertEquals(0, store.textureRevision)
            }
        }
    }

    @Test
    fun `missing corrupt oversized and wrong dimension images fail without registered textures`() {
        val wrongSize = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", output)
            output.toByteArray()
        }
        for (bytes in listOf(null, byteArrayOf(1, 2, 3), ByteArray(1024 * 1024 + 1), wrongSize)) {
            KoolCanvasCpuTextureStore().use { store ->
                AuthoritativeVisualAssets(store) { bytes }.use { catalog ->
                    assertNull(catalog.unit("builder", -1))
                    assertEquals(0, store.textureRevision)
                }
            }
        }
    }

    @Test
    fun `catalog releases only its textures and cannot be used after close`() {
        KoolCanvasCpuTextureStore().use { store ->
            val first = AuthoritativeVisualAssets(store, ::readAsset)
            val second = AuthoritativeVisualAssets(store, ::readAsset)
            val refs = mutableListOf<KoolCanvasTextureRef>()
            val sprite = assertNotNull(first.unit("tank", -1))
            refs += listOfNotNull(sprite.body, sprite.turret, sprite.shadow)
            val surviving = assertNotNull(second.unit("builder", -1)).body
            first.close()
            first.close()
            refs.forEach { assertNull(store.argbImage(it.id)) }
            assertNotNull(store.argbImage(surviving.id))
            assertFailsWith<IllegalStateException> { first.unit("builder", -1) }
            second.close()
            assertNull(store.argbImage(surviving.id))
        }
    }
}
