package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.*
import de.fabmax.kool.util.Uint8Buffer

/** Renderer-owned atlas. Only immutable decoded pixels may enter it; generated targets never do. */
internal class KoolCanvasSpriteAtlas(
    private val textureStore: KoolCanvasTextureStore,
    private val pageSize: Int = 2048,
    private val maxPages: Int = 4,
) {
    internal data class Slot(val page: Page, val x: Int, val y: Int, val width: Int, val height: Int) {
        fun uv(source: KoolCanvasRect): KoolCanvasRect = KoolCanvasRect(
            (x + source.left) / page.size, (y + source.top) / page.size,
            (x + source.right) / page.size, (y + source.bottom) / page.size,
        )
    }

    internal class Page(val serial: Int, val size: Int) {
        val rgbaPixels = ByteArray(size * size * 4)
        var cursorX = 0
        var cursorY = 0
        var rowHeight = 0
        var dirty = false
        var lastUsed = 0L
        val textures = mutableMapOf<KoolCanvasTextureFilter, Texture2d>()
    }

    private val pages = mutableListOf<Page>()
    private val slots = mutableMapOf<KoolCanvasTextureId, Slot>()
    private var serial = 0
    private var frameNumber = 0L
    private val uploadOwners = mutableMapOf<Texture2d, Uint8Buffer>()
    // At most 32 MiB for default pages. An in-flight texture never lends its upload memory.
    private val freeUploads = ArrayDeque<Uint8Buffer>()

    private fun retireTexture(texture: Texture2d) {
        val data = uploadOwners.remove(texture)
        KoolCanvasGpuRetirement.retire {
            texture.release()
            if (data != null && freeUploads.size < 2) freeUploads.addLast(data)
        }
    }

    fun prepare(commands: List<KoolCanvasCommand>) {
        frameNumber++
        for (command in commands) {
            if (command is KoolCanvasCommand.DrawTexture && isSupported(command)) {
                slot(command)?.page?.lastUsed = frameNumber
            }
        }
        // Changing an atlas creates a new GPU owner. Never modify upload memory referenced by an
        // in-flight frame. Texture retirement waits for the renderer's GPU completion fence.
        for (page in pages) if (page.dirty) {
            page.textures.values.forEach(::retireTexture)
            page.textures.clear()
            page.dirty = false
        }
        val expired = pages.filter { frameNumber - it.lastUsed > 60 }
        if (expired.isNotEmpty()) {
            slots.entries.removeAll { it.value.page in expired }
            expired.forEach { page -> page.textures.values.forEach(::retireTexture) }
            pages.removeAll(expired.toSet())
        }
    }

    fun slot(command: KoolCanvasCommand.DrawTexture): Slot? {
        if (!isSupported(command)) return null
        slots[command.texture.id]?.let { return it }
        val image = textureStore.staticArgbImage(command.texture.id) ?: return null
        if (image.premultipliedAlpha || image.width != command.texture.width || image.height != command.texture.height ||
            image.width <= 0 || image.height <= 0 || image.width + 4 > pageSize || image.height + 4 > pageSize) return null
        val slotStart = CanvasRenderStageTrace.start()
        val page = pages.firstOrNull { fits(it, image.width, image.height) }
            ?: if (pages.size < maxPages) Page(++serial, pageSize).also { pages += it } else return null
        val paddedWidth = image.width + 4
        val paddedHeight = image.height + 4
        if (page.cursorX + paddedWidth > pageSize) {
            page.cursorY += page.rowHeight
            page.cursorX = 0
            page.rowHeight = 0
        }
        val x = page.cursorX + 2
        val y = page.cursorY + 2
        // Repeat edge texels into two guard pixels, including corners, to preserve filtered edges.
        for (dy in -2 until image.height + 2) {
            val sy = dy.coerceIn(0, image.height - 1)
            for (dx in -2 until image.width + 2) {
                val sx = dx.coerceIn(0, image.width - 1)
                val pixel = image.pixels[sy * image.width + sx]
                val offset = ((y + dy) * pageSize + x + dx) * 4
                // Convert only the new sprite and its guard region, preserving transparent RGB.
                page.rgbaPixels[offset] = (pixel ushr 16).toByte()
                page.rgbaPixels[offset + 1] = (pixel ushr 8).toByte()
                page.rgbaPixels[offset + 2] = pixel.toByte()
                page.rgbaPixels[offset + 3] = (pixel ushr 24).toByte()
            }
        }
        page.cursorX += paddedWidth
        page.rowHeight = maxOf(page.rowHeight, paddedHeight)
        page.dirty = true
        page.lastUsed = frameNumber
        CanvasRenderStageTrace.record("atlas-slot", slotStart, image.width.toLong() * image.height * 4, page.serial.toLong())
        return Slot(page, x, y, image.width, image.height).also { slots[command.texture.id] = it }
    }

    fun texture(page: Page, filter: KoolCanvasTextureFilter): Texture2d = page.textures.getOrPut(filter) {
        val textureStart = CanvasRenderStageTrace.start()
        // Bulk-copy into an independent upload owner; later page growth must not change memory
        // still referenced by an in-flight texture. Each filter retains its own immutable payload.
        val allocateStart = CanvasRenderStageTrace.start()
        val data = freeUploads.removeFirstOrNull() ?: Uint8Buffer(page.rgbaPixels.size)
        data.position = 0
        data.limit = data.capacity
        CanvasRenderStageTrace.record("atlas-allocate", allocateStart, page.rgbaPixels.size.toLong())
        val copyStart = CanvasRenderStageTrace.start()
        data.put(page.rgbaPixels)
        CanvasRenderStageTrace.record("atlas-copy", copyStart, page.rgbaPixels.size.toLong())
        data.position = 0
        val name = "rwx-sprite-atlas-${page.serial}-$frameNumber-$filter"
        Texture2d(
            data = BufferedImageData2d(data, pageSize, pageSize, TexFormat.RGBA, name),
            mipMapping = MipMapping.Off,
            samplerSettings = if (filter == KoolCanvasTextureFilter.Nearest)
                SamplerSettings().clamped().nearest().noAnisotropy()
            else SamplerSettings().clamped().linear().noAnisotropy(),
            name = name,
        ).also {
            uploadOwners[it] = data
            CanvasRenderStageTrace.record("atlas-texture", textureStart, page.rgbaPixels.size.toLong(), page.serial.toLong())
        }
    }

    private fun fits(page: Page, width: Int, height: Int): Boolean {
        val nextRow = if (page.cursorX + width + 4 > pageSize) page.cursorY + page.rowHeight else page.cursorY
        return nextRow + height + 4 <= pageSize
    }

    companion object {
        fun isSupported(command: KoolCanvasCommand.DrawTexture): Boolean =
            command.state.renderTarget == null && !command.texture.premultipliedAlpha &&
                (command.paint.textureEffect == null || command.paint.textureEffect is KoolCanvasTextureEffect.TeamColor) &&
                (command.paint.blendMode == KoolCanvasBlendMode.SourceOver ||
                    command.paint.blendMode == KoolCanvasBlendMode.Add || command.paint.blendMode == KoolCanvasBlendMode.Source) &&
                !command.source.hasZeroArea && !command.destination.hasZeroArea &&
                command.source.boundsLeft >= 0f && command.source.boundsTop >= 0f &&
                command.source.boundsRight <= command.texture.width && command.source.boundsBottom <= command.texture.height
    }
}
