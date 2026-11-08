package io.github.rwx.authoritative

import com.corrodinggames.rts.gameFramework.graphics.TeamColoring
import io.github.rwx.net.authoritative.PublicTerrainCatalog
import io.github.rwx.render.canvas.KoolCanvasCpuTextureStore
import io.github.rwx.render.canvas.KoolCanvasRect
import io.github.rwx.render.canvas.KoolCanvasTextureId
import io.github.rwx.render.canvas.KoolCanvasTextureRef
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Display-only catalog. Network type names select a closed table, never an asset path or unit parser.
 * These are the bundled level-one sprites' first frames, with static building backgrounds flattened.
 * The pure TeamColoring arithmetic has no engine dependency or initialization side effects.
 */
internal class AuthoritativeVisualAssets(
    private val store: KoolCanvasCpuTextureStore,
    private val readAsset: (String) -> ByteArray?,
) : AutoCloseable {
    data class Sprite(
        val body: KoolCanvasTextureRef,
        val bodySource: KoolCanvasRect,
        val width: Float,
        val height: Float,
        val turret: KoolCanvasTextureRef? = null,
        val turretSource: KoolCanvasRect? = null,
        val shadow: KoolCanvasTextureRef? = null,
        val bodyPivotX: Float = width / 2f,
        val bodyPivotY: Float = height / 2f,
        val turretWidth: Float = turretSource?.width ?: 0f,
        val turretHeight: Float = turretSource?.height ?: 0f,
        val turretPivotX: Float = turretWidth / 2f,
        val turretPivotY: Float = turretHeight / 2f,
        val turretOffsetX: Float = 0f,
        val turretOffsetY: Float = 0f,
        val shadowSource: KoolCanvasRect? = shadow?.fullRect,
        val shadowOffsetX: Float = 1f,
        val shadowOffsetY: Float = 1f,
        val rotatesWithHeading: Boolean = true,
        // Bundled mobile art faces up; wire headings use degrees with zero pointing right.
        val headingOffsetDegrees: Float = 90f,
        /** Verified fixed rest poses; independent turret/arm animation is deliberately absent. */
        val fixedParts: List<FixedPart> = emptyList(),
        val limitations: List<String> = emptyList(),
        /** Settings and invulnerability remain explicit rest-pose limitations in the manifest. */
        val shadowCondition: String = "reference_pose",
    )

    data class FixedPart(val texture: KoolCanvasTextureRef, val source: KoolCanvasRect,
        val width: Float, val height: Float, val pivotX: Float, val pivotY: Float,
        val offsetX: Float, val offsetY: Float, val angle: Float)

    /** A fixed cell in an owned atlas. The wire provides only its closed catalog number. */
    data class TerrainSprite(val texture: KoolCanvasTextureRef, val source: KoolCanvasRect)

    private data class ImageSpec(val path: String, val width: Int, val height: Int)
    private data class Definition(
        val body: ImageSpec,
        val frameWidth: Int = body.width,
        val frameHeight: Int = body.height,
        val background: ImageSpec? = null,
        val turret: ImageSpec? = null,
        val scale: Float = 1f,
        val mobile: Boolean = false,
        val turretOffsetY: Float = 0f,
    )

    private val instance = nextInstance.incrementAndGet()
    private val decoded = mutableMapOf<String, BufferedImage?>()
    private val sprites = mutableMapOf<Triple<String, Int, Int>, Sprite?>()
    private val terrainSprites = mutableMapOf<Int, TerrainSprite?>()
    private val terrainAtlases = mutableMapOf<String, KoolCanvasTextureRef?>()
    private val registered = mutableListOf<KoolCanvasTextureId>()
    private var closed = false

    /** [teamColor] is packed ARGB. Returned pixels are already tinted; draw with white paint. */
    fun unit(typeName: String, teamColor: Int, upgradeLevel: Int = 1): Sprite? {
        check(!closed) { "Asset catalog is closed" }
        val canonical = if (typeName == "c_tank") "tank" else typeName
        val definition = definitions[canonical]
        val manifest = AuthoritativeUnitVisualManifest.entries[canonical]
        if (definition == null && manifest == null) return null
        val level = upgradeLevel.takeIf { it in 1..3 } ?: return null
        val variant = manifest?.variants?.get("level$level")
        val key = Triple(canonical, teamColor or (0xff shl 24), if (variant == null) 1 else level)
        if (sprites.containsKey(key)) return sprites[key]
        val result = if (definition != null && variant == null) create(definition, key.second)
            ?.copy(limitations = manifest?.limitations.orEmpty())
        else manifest?.let { createManifest(it, key.second, variant) }
        sprites[key] = result
        return result
    }

    /** Neither unknown ids nor the transparent cell can cause a file read. */
    fun terrainTile(id: Int): TerrainSprite? {
        check(!closed) { "Asset catalog is closed" }
        val tile = PublicTerrainCatalog.tile(id) ?: return null
        if (terrainSprites.containsKey(id)) return terrainSprites[id]
        val atlas = if (terrainAtlases.containsKey(tile.assetPath)) terrainAtlases[tile.assetPath] else {
            load(ImageSpec(tile.assetPath, tile.imageWidth, tile.imageHeight))?.let { register(it) }
                .also { terrainAtlases[tile.assetPath] = it }
        }
        val result = atlas?.let {
            TerrainSprite(it, KoolCanvasRect(tile.sourceX.toFloat(), tile.sourceY.toFloat(),
                (tile.sourceX + tile.width).toFloat(), (tile.sourceY + tile.height).toFloat()))
        }
        terrainSprites[id] = result
        return result
    }

    private fun create(definition: Definition, color: Int): Sprite? {
        val sheet = load(definition.body) ?: return null
        val background = definition.background?.let { load(it) ?: return null }
        val turretImage = definition.turret?.let { load(it) ?: return null }
        val frameWidth = definition.frameWidth
        val frameHeight = definition.frameHeight
        val width = maxOf(frameWidth, background?.width ?: 0)
        val height = maxOf(frameHeight, background?.height ?: 0)
        // Keep an uncomposited frame's straight-alpha RGB exact, including partially clear edges.
        val composite = if (background == null) sheet.getSubimage(0, 0, frameWidth, frameHeight)
        else BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
            val graphics = target.createGraphics()
            try {
                graphics.drawImage(background,
                    width / 2 - background.width / 2, height / 2 - background.height / 2, null)
                val x = width / 2 - frameWidth / 2
                val y = height / 2 - frameHeight / 2
                graphics.drawImage(sheet, x, y, x + frameWidth, y + frameHeight,
                    0, 0, frameWidth, frameHeight, null)
            } finally {
                graphics.dispose()
            }
        }
        val body = register(composite, color)
        val turret = turretImage?.let { register(it, color) }
        val shadow = if (definition.mobile) register(composite, color, shadow = true) else null
        val scale = definition.scale
        return Sprite(
            body = body, bodySource = body.fullRect, width = width * scale, height = height * scale,
            turret = turret, turretSource = turret?.fullRect, shadow = shadow,
            bodyPivotX = width / 2 * scale, bodyPivotY = height / 2 * scale,
            turretWidth = (turret?.width ?: 0) * scale, turretHeight = (turret?.height ?: 0) * scale,
            turretPivotX = (turret?.width ?: 0) / 2 * scale,
            turretPivotY = (turret?.height ?: 0) / 2 * scale,
            turretOffsetY = definition.turretOffsetY,
            rotatesWithHeading = definition.mobile,
        )
    }

    private fun createManifest(spec: AuthoritativeUnitVisualManifest.Spec, color: Int, variant: String?): Sprite? {
        val bodyLayer = spec.body?.let { if (variant == null) it else it.copy(image = variant) } ?: return null
        val body = raster(bodyLayer, color, spec.colorMode) ?: return null
        val back = spec.back?.let { raster(it, color, spec.colorMode) ?: run { body.flush(); return null } }
        val prepared = mutableListOf<BufferedImage>()
        try {
            for (part in spec.turrets) prepared += raster(part, color, spec.colorMode) ?: return null
            val left = floor(minOf(bodyLayer.x - body.width / 2, spec.back?.let { it.x - (back?.width ?: 0) / 2 } ?: Float.POSITIVE_INFINITY)).toInt()
            val top = floor(minOf(bodyLayer.y - body.height / 2, spec.back?.let { it.y - (back?.height ?: 0) / 2 } ?: Float.POSITIVE_INFINITY)).toInt()
            val right = ceil(maxOf(bodyLayer.x - body.width / 2 + body.width,
                spec.back?.let { it.x - (back?.width ?: 0) / 2 + (back?.width ?: 0) } ?: Float.NEGATIVE_INFINITY)).toInt()
            val bottom = ceil(maxOf(bodyLayer.y - body.height / 2 + body.height,
                spec.back?.let { it.y - (back?.height ?: 0) / 2 + (back?.height ?: 0) } ?: Float.NEGATIVE_INFINITY)).toInt()
            val composite = BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_ARGB)
            try {
                if (back == null) {
                    // A raw straight-alpha frame must not suffer a Java2D premultiply round-trip.
                    composite.setRGB((bodyLayer.x - body.width / 2 - left).toInt(),
                        (bodyLayer.y - body.height / 2 - top).toInt(), body.width, body.height,
                        body.getRGB(0, 0, body.width, body.height, null, 0, body.width), 0, body.width)
                } else {
                    val graphics = composite.createGraphics()
                    try {
                        val background = checkNotNull(spec.back)
                        graphics.drawImage(back, (background.x - back.width / 2 - left).toInt(),
                            (background.y - back.height / 2 - top).toInt(), null)
                        graphics.drawImage(body, (bodyLayer.x - body.width / 2 - left).toInt(),
                            (bodyLayer.y - body.height / 2 - top).toInt(), null)
                    } finally { graphics.dispose() }
                }
                val bodyRef = register(composite)
                val parts = spec.turrets.mapIndexed { index, part ->
                    val ref = register(prepared[index])
                    FixedPart(ref, ref.fullRect, part.width * part.scale, part.height * part.scale,
                        part.width / 2 * part.scale, part.height / 2 * part.scale, part.x, part.y, part.angle)
                }
                // Preserve the reference body's bounds so the existing shadow owner remains exact.
                val externalShadow = spec.shadow?.let { raster(it, color, spec.colorMode) }
                val shadow = try {
                    when {
                        spec.autoShadow -> register(composite, shadow = true, shadowAlpha = spec.shadowAlpha)
                        externalShadow != null && externalShadow.width == composite.width && externalShadow.height == composite.height -> register(externalShadow)
                        else -> null
                    }
                } finally { externalShadow?.flush() }
                return Sprite(bodyRef, bodyRef.fullRect, composite.width * spec.scale, composite.height * spec.scale,
                    shadow = shadow, bodyPivotX = -left * spec.scale, bodyPivotY = -top * spec.scale,
                    shadowOffsetX = spec.shadowX, shadowOffsetY = spec.shadowY, rotatesWithHeading = spec.rotates,
                    fixedParts = parts, limitations = spec.limitations, shadowCondition = spec.shadowCondition)
            } finally { composite.flush() }
        } finally {
            body.flush(); back?.flush(); prepared.forEach(BufferedImage::flush)
        }
    }

    private fun raster(layer: AuthoritativeUnitVisualManifest.Layer, color: Int, mode: String): BufferedImage? {
        val image = AuthoritativeUnitVisualManifest.images[layer.image] ?: return null
        val source = load(ImageSpec(image.path, image.width, image.height)) ?: return null
        val pixels = source.getRGB(layer.sourceX, layer.sourceY, layer.width, layer.height, null, 0, layer.width)
        for (index in pixels.indices) pixels[index] = when {
            layer.shadow -> ((pixels[index] ushr 24) / 3) shl 24
            !layer.tint || mode == "disabled" -> pixels[index]
            mode == "pureGreen" -> TeamColoring.pureGreen(pixels[index], color)
            mode == "hueAdd" -> TeamColoring.hueAdd(pixels[index], color)
            mode == "hueShift" -> TeamColoring.hueShift(pixels[index], color)
            else -> return null
        }
        return BufferedImage(layer.width, layer.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, layer.width, layer.height, pixels, 0, layer.width)
        }
    }

    private fun load(spec: ImageSpec): BufferedImage? {
        if (decoded.containsKey(spec.path)) return decoded[spec.path]
        val bytes = try { readAsset(spec.path) } catch (_: java.io.IOException) { null }
        val expectedHash = AuthoritativeUnitVisualManifest.images[spec.path]?.sha256
        val result = if (bytes == null || bytes.size !in 8..MAX_ENCODED_BYTES ||
            !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) ||
            (expectedHash != null && MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) } != expectedHash)) null else {
            val reader = ImageIO.getImageReadersByFormatName("png").asSequence().firstOrNull()
                ?: return null
            try {
                MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                    reader.input = input
                    if (reader.getWidth(0) != spec.width || reader.getHeight(0) != spec.height) null
                    else reader.read(0)
                }
            } catch (_: java.io.IOException) {
                null
            } finally {
                reader.dispose()
            }
        }
        decoded[spec.path] = result
        return result
    }

    private fun register(image: BufferedImage, color: Int? = null, shadow: Boolean = false, shadowAlpha: Int = 85): KoolCanvasTextureRef {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        if (color != null || shadow) for (index in pixels.indices) {
            pixels[index] = if (shadow) (((pixels[index] ushr 24) * shadowAlpha / 255) shl 24)
                else TeamColoring.pureGreen(pixels[index], checkNotNull(color))
        }
        val id = KoolCanvasTextureId("authority-catalog/$instance/${registered.size}")
        store.registerArgb(id, image.width, image.height, pixels, alphaBleed = false)
        registered += id
        return KoolCanvasTextureRef(id, image.width, image.height,
            hasAlpha = true, requiresOrderedAlpha = true, premultipliedAlpha = false)
    }

    override fun close() {
        if (closed) return
        closed = true
        registered.forEach(store::unregister)
        registered.clear()
        sprites.clear()
        terrainSprites.clear()
        terrainAtlases.clear()
        decoded.values.filterNotNull().forEach(BufferedImage::flush)
        decoded.clear()
    }

    fun shadowVisible(sprite: Sprite, height: Float, buildProgress: Float): Boolean = when (sprite.shadowCondition) {
        "surface_gt_minus2" -> height > -2f
        "surface_ge_zero" -> height >= 0f
        "completed_non_invulnerable" -> buildProgress >= 1f
        else -> true
    }

    companion object {
        private const val MAX_ENCODED_BYTES = 1024 * 1024
        private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private val nextInstance = AtomicLong()
        private val definitions = mapOf(
            "builder" to Definition(ImageSpec("drawable/builder.png", 20, 20), mobile = true),
            "tank" to Definition(ImageSpec("units/tanks/tank.png", 48, 30), frameWidth = 16,
                turret = ImageSpec("units/tanks/tank_turret.png", 10, 40), scale = 0.8f, mobile = true),
            "landFactory" to Definition(ImageSpec("drawable/land_factory_front.png", 50, 74),
                background = ImageSpec("drawable/land_factory_back.png", 50, 74)),
            // The server pins these stock replacements. Their frames differ from native unit art.
            // Stock buildings face -90 degrees: turret_1 y=6 is six world pixels upward.
            // Independent turret aim/idle-sweep is not present in the current view schema.
            "turret" to Definition(ImageSpec("units/turrets/turret_base.png", 35, 32),
                turret = ImageSpec("units/turrets/turret_top.png", 12, 46), turretOffsetY = -6f),
            "extractor" to Definition(ImageSpec("units/extractor/extractor.png", 148, 59),
                frameWidth = 37, background = ImageSpec("units/extractor/extractor_back.png", 51, 44)),
            "commandCenter" to Definition(ImageSpec("drawable/base.png", 212, 68), frameWidth = 53,
                background = ImageSpec("drawable/base_back.png", 53, 68)),
        )
        internal val supportedTypes: Set<String> = definitions.keys + "c_tank"
        internal val publicTypes: Set<String> = AuthoritativeUnitVisualManifest.entries.keys
        internal val approvedUnitPaths: Set<String> = AuthoritativeUnitVisualManifest.images.keys
        internal val bundledPaths: Set<String> = definitions.values.flatMap {
            listOfNotNull(it.body.path, it.background?.path, it.turret?.path)
        }.toSet()
    }
}
