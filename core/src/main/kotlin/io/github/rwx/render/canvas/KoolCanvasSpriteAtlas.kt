package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.*
import de.fabmax.kool.util.Uint8Buffer

/** Renderer-owned atlas. Only immutable decoded pixels may enter it; generated targets never do. */
internal class KoolCanvasSpriteAtlas(
    private val textureStore: KoolCanvasTextureStore,
    private val pageSize: Int = 2048,
    private val maxPages: Int = 4,
    private val incrementalUploads: Boolean = System.getenv("RWX_INCREMENTAL_SPRITE_ATLAS") == "1",
    private val prepareNestedFrames: Boolean = incrementalUploads || System.getenv("RWX_PROJECTED_SPRITE_ATLAS") == "1",
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
        val appended = mutableListOf<KoolCanvasAtlasAppend>()
    }

    private val pages = mutableListOf<Page>()
    private val slots = mutableMapOf<KoolCanvasTextureId, Slot>()
    private var serial = 0
    private var frameNumber = 0L
    private val owner = nextOwner.incrementAndGet()
    private val visitedFrames = mutableSetOf<KoolCanvasTextureId>()
    private val prepareStack = ArrayDeque<List<KoolCanvasCommand>>()
    private val uploadOwners = mutableMapOf<Texture2d, Uint8Buffer>()
    // At most 32 MiB for default pages. An in-flight texture never lends its upload memory.
    private val freeUploads = ArrayDeque<Uint8Buffer>()
    private var poolEpoch = 0L

    private fun retireTexture(texture: Texture2d, recycle: Boolean = true) {
        val data = uploadOwners.remove(texture)
        val epoch = poolEpoch
        KoolCanvasGpuRetirement.retire {
            // These are data-only textures, with no asynchronous loader. A cold texture can still
            // retain its complete page after release; detach it only after its last GPU fence.
            texture.uploadData = null
            texture.release()
            if (recycle && epoch == poolEpoch && data != null && freeUploads.size < 2) freeUploads.addLast(data)
        }
    }

    fun clear() {
        poolEpoch++
        pages.forEach { page -> page.textures.values.forEach { retireTexture(it, recycle = false) } }
        pages.clear(); slots.clear(); freeUploads.clear(); visitedFrames.clear(); prepareStack.clear()
    }

    fun prepare(commands: List<KoolCanvasCommand>) {
        frameNumber++
        // Resolve nested recorded canvases before any atlas payload is published. A late slot used
        // to sample the previous full image for one frame, until the next prepare rebuilt the page.
        visitedFrames.clear(); prepareStack.clear(); prepareStack.addLast(commands)
        while (prepareStack.isNotEmpty()) for (command in prepareStack.removeLast()) {
            val textureCommand = when (command) {
                is KoolCanvasCommand.DrawTexture -> command
                is KoolCanvasCommand.DrawTextureRepeat -> KoolCanvasCommand.DrawTexture(
                    command.texture, command.source, command.destination, command.paint, command.state)
                else -> null
            }
            if (textureCommand != null && isSupported(textureCommand)) {
                slot(textureCommand)?.page?.lastUsed = frameNumber
                if (prepareNestedFrames && visitedFrames.add(textureCommand.texture.id)) {
                    textureStore.frame(textureCommand.texture.id)?.let { prepareStack.addLast(it.commands) }
                }
            }
        }
        // Full-image fallback replaces immutable upload owners. Supported appends keep both owners
        // and previously published texels; the desktop backend fences staging and orders the copy.
        for (page in pages) commitAppends(page)
        val expired = pages.filter { frameNumber - it.lastUsed > 60 }
        if (expired.isNotEmpty()) {
            slots.entries.removeAll { it.value.page in expired }
            expired.forEach { page -> page.textures.values.forEach(::retireTexture) }
            pages.removeAll(expired.toSet())
        }
    }

    private fun commitAppends(page: Page) {
        if (!page.dirty) return
        val updated = incrementalUploads && page.textures.values.all { texture ->
            KoolCanvasAtlasUpdates.append(texture, page.appended) }
        if (!updated) {
            page.textures.values.forEach(::retireTexture)
            page.textures.clear()
        }
        page.dirty = false
        page.appended.clear()
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
        if (incrementalUploads) {
            val rgba = ByteArray(paddedWidth * paddedHeight * 4)
            repeat(paddedHeight) { row ->
                val source = ((y - 2 + row) * pageSize + x - 2) * 4
                page.rgbaPixels.copyInto(rgba, row * paddedWidth * 4, source, source + paddedWidth * 4)
            }
            page.appended += KoolCanvasAtlasAppend(x - 2, y - 2, paddedWidth, paddedHeight, rgba)
        }
        page.lastUsed = frameNumber
        CanvasRenderStageTrace.record("atlas-slot", slotStart, image.width.toLong() * image.height * 4, page.serial.toLong())
        return Slot(page, x, y, image.width, image.height).also { slots[command.texture.id] = it }
    }

    fun texture(page: Page, filter: KoolCanvasTextureFilter): Texture2d {
        // Also handles a supported late expansion. If its first payload is not on the GPU yet,
        // replace it with an immutable complete payload rather than showing a one-frame hole.
        if (incrementalUploads) commitAppends(page)
        return page.textures.getOrPut(filter) {
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
        val name = "rwx-sprite-atlas-$owner-${page.serial}-$frameNumber-$filter"
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
    }

    private fun fits(page: Page, width: Int, height: Int): Boolean {
        val nextRow = if (page.cursorX + width + 4 > pageSize) page.cursorY + page.rowHeight else page.cursorY
        return nextRow + height + 4 <= pageSize
    }

    companion object {
        private val nextOwner = java.util.concurrent.atomic.AtomicLong()
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
