package io.github.rwx.render.canvas

import java.util.IdentityHashMap
import java.util.Collections
import java.util.RandomAccess
import java.util.concurrent.atomic.AtomicReferenceArray

/** Immutable legacy List view. Normal replay reads its primitive backing without creating commands. */
internal class KoolCanvasPackedTextureCommands private constructor(
    private val legacy: Array<KoolCanvasCommand?>,
    private val rows: IntArray,
    private val chunks: Array<Chunk>,
    internal val packedSize: Int,
    internal val textures: List<KoolCanvasTextureRef>,
    private val paints: List<KoolCanvasPaint>,
    private val clips: List<KoolCanvasRect?>,
    private val containsRootClear: Boolean,
    private val prefixClear: KoolCanvasCommand.Clear? = null,
    private val baseView: KoolCanvasPackedTextureCommands? = null,
) : AbstractList<KoolCanvasCommand>(), RandomAccess {
    override val size: Int get() = legacy.size + if (prefixClear != null) 1 else 0
    @Volatile private var decoded: AtomicReferenceArray<KoolCanvasCommand>? = null

    internal fun rowAt(index: Int): Int = if (prefixClear != null && index == 0) -1
        else rows[index - if (prefixClear != null) 1 else 0]
    internal fun legacyAt(index: Int): KoolCanvasCommand? = if (prefixClear != null && index == 0) prefixClear
        else legacy[index - if (prefixClear != null) 1 else 0]
    internal fun hasRootClear(): Boolean = containsRootClear
    internal fun withSurfaceClear(clear: KoolCanvasCommand.Clear): KoolCanvasPackedTextureCommands {
        require(clear.renderTarget == null)
        return if (containsRootClear) this else KoolCanvasPackedTextureCommands(legacy, rows, chunks, packedSize,
            textures, paints, clips, true, clear, this)
    }
    internal fun coordinate(row: Int, field: Int): Float = chunks[row / CHUNK_ROWS].coordinates[(row % CHUNK_ROWS) * STRIDE + field]
    internal fun textureIndex(row: Int): Int = chunks[row / CHUNK_ROWS].textureIndices[row % CHUNK_ROWS]
    internal fun texture(row: Int): KoolCanvasTextureRef = textures[textureIndex(row)]
    internal fun paint(row: Int): KoolCanvasPaint = paints[chunks[row / CHUNK_ROWS].paintIndices[row % CHUNK_ROWS]]
    internal fun clip(row: Int): KoolCanvasRect? = clips[chunks[row / CHUNK_ROWS].clipIndices[row % CHUNK_ROWS]]
    internal fun role(row: Int): KoolCanvasDrawRole = KoolCanvasDrawRole.entries[chunks[row / CHUNK_ROWS].roles[row % CHUNK_ROWS]]
    internal fun unitId(row: Int): Long = chunks[row / CHUNK_ROWS].unitIds[row % CHUNK_ROWS]
    internal fun chunkCount(): Int = chunks.size
    internal fun largestCoordinateChunkBytes(): Int = chunks.maxOfOrNull { it.coordinates.size * 4 } ?: 0
    internal fun materializedCount(): Int = baseView?.materializedCount()
        ?: decoded?.let { cache -> (0 until cache.length()).count { cache.get(it) != null } } ?: 0

    override fun get(index: Int): KoolCanvasCommand {
        checkElementIndex(index)
        if (prefixClear != null) return if (index == 0) prefixClear else checkNotNull(baseView)[index - 1]
        legacy[index]?.let { return it }
        val row = rows[index]
        val cache = decoded ?: synchronized(this) {
            decoded ?: AtomicReferenceArray<KoolCanvasCommand>(packedSize).also { decoded = it }
        }
        cache.get(row)?.let { return it }
        val transform = if (chunks[row / CHUNK_ROWS].identityTransforms[row % CHUNK_ROWS]) KoolCanvasTransform.Identity else KoolCanvasTransform(
            coordinate(row, 8), coordinate(row, 9), coordinate(row, 10), coordinate(row, 11),
            coordinate(row, 12), coordinate(row, 13))
        val command = KoolCanvasCommand.DrawTexture(texture(row),
            KoolCanvasRect(coordinate(row, 0), coordinate(row, 1), coordinate(row, 2), coordinate(row, 3)),
            KoolCanvasRect(coordinate(row, 4), coordinate(row, 5), coordinate(row, 6), coordinate(row, 7)),
            paint(row), KoolCanvasState(transform, clip(row), null, role(row), unitId(row)))
        return if (cache.compareAndSet(row, null, command)) command else checkNotNull(cache.get(row))
    }

    private fun checkElementIndex(index: Int) {
        if (index !in indices) throw IndexOutOfBoundsException("index=$index size=$size")
    }

    private class Chunk(size: Int) {
        val coordinates = FloatArray(size * STRIDE)
        val textureIndices = IntArray(size)
        val paintIndices = IntArray(size)
        val clipIndices = IntArray(size)
        val roles = IntArray(size)
        val unitIds = LongArray(size)
        val identityTransforms = BooleanArray(size)
    }

    internal class Builder(private val logicalSize: Int) {
        private val legacy = arrayOfNulls<KoolCanvasCommand>(logicalSize)
        private val rows = IntArray(logicalSize) { -1 }
        private val chunks = ArrayList<Chunk>()
        private val textures = ArrayList<KoolCanvasTextureRef>()
        private val textureLookup = IdentityHashMap<KoolCanvasTextureRef, Int>()
        private val paints = ArrayList<KoolCanvasPaint>()
        private val paintLookup = HashMap<KoolCanvasPaint, Int>()
        private val clips = arrayListOf<KoolCanvasRect?>(null)
        private val clipLookup = IdentityHashMap<KoolCanvasRect, Int>()
        private var logicalIndex = 0
        private var rowCount = 0
        private var lastPaint: KoolCanvasPaint? = null
        private var lastPaintIndex = -1
        private var lastPaintKind = -1
        private var defaultPaintIndex = -1
        private var nearestPaintIndex = -1
        private var published = false
        private var containsRootClear = false

        fun addLegacy(command: KoolCanvasCommand) {
            check(!published)
            legacy[logicalIndex++] = command
            if (command is KoolCanvasCommand.Clear && command.renderTarget == null) containsRootClear = true
        }

        fun textureIndex(resolved: KoolCanvasTextureRef): Int {
            check(!published)
            return textureLookup.getOrPut(resolved) { textures.size.also { textures += resolved } }
        }

        fun add(command: KoolCanvasCommand.DrawTexture, textureIndex: Int) {
            check(!published)
            val row = rowCount++
            if (row % CHUNK_ROWS == 0) chunks += Chunk(CHUNK_ROWS)
            val chunk = chunks[row / CHUNK_ROWS]
            val local = row % CHUNK_ROWS
            rows[logicalIndex++] = row
            chunk.textureIndices[local] = textureIndex
            // Legacy texture-run predicates intentionally recognize these two singleton paints.
            // Keep canonical and merely value-equal paints separate so fallback routing is unchanged.
            val paintKind = when {
                command.paint === KoolCanvasPaint.Default -> 0
                command.paint === KoolCanvasPaint.DefaultNearest -> 1
                else -> 2
            }
            chunk.paintIndices[local] = if (lastPaintKind == paintKind && lastPaint == command.paint) lastPaintIndex else {
                val paintIndex = when (paintKind) {
                    0 -> if (defaultPaintIndex >= 0) defaultPaintIndex else paints.size.also {
                        paints += command.paint; defaultPaintIndex = it
                    }
                    1 -> if (nearestPaintIndex >= 0) nearestPaintIndex else paints.size.also {
                        paints += command.paint; nearestPaintIndex = it
                    }
                    else -> paintLookup.getOrPut(command.paint) { paints.size.also { paints += command.paint } }
                }
                paintIndex.also {
                    lastPaint = command.paint; lastPaintIndex = it; lastPaintKind = paintKind
                }
            }
            val clip = command.state.clip
            chunk.clipIndices[local] = if (clip == null) 0 else clipLookup.getOrPut(clip) { clips.size.also { clips += clip } }
            val s = command.source; val d = command.destination; val t = command.state.transform
            val coordinates = chunk.coordinates
            val offset = local * STRIDE
            coordinates[offset] = s.left; coordinates[offset + 1] = s.top
            coordinates[offset + 2] = s.right; coordinates[offset + 3] = s.bottom
            coordinates[offset + 4] = d.left; coordinates[offset + 5] = d.top
            coordinates[offset + 6] = d.right; coordinates[offset + 7] = d.bottom
            coordinates[offset + 8] = t.scaleX; coordinates[offset + 9] = t.skewY
            coordinates[offset + 10] = t.skewX; coordinates[offset + 11] = t.scaleY
            coordinates[offset + 12] = t.translateX; coordinates[offset + 13] = t.translateY
            chunk.roles[local] = command.state.drawRole.ordinal; chunk.unitIds[local] = command.state.semanticUnitId
            chunk.identityTransforms[local] = t === KoolCanvasTransform.Identity
        }

        fun build(): List<KoolCanvasCommand> {
            check(!published)
            check(logicalIndex == logicalSize)
            // Transfer exclusive ownership. No mutable storage escapes the builder or list,
            // and every builder mutation rejects calls after this point. No pool reuses arrays.
            published = true
            if (rowCount == 0) return Collections.unmodifiableList(legacy.map { checkNotNull(it) })
            return KoolCanvasPackedTextureCommands(legacy, rows, chunks.toTypedArray(), rowCount,
                Collections.unmodifiableList(textures.toList()), Collections.unmodifiableList(paints.toList()),
                Collections.unmodifiableList(clips.toList()), containsRootClear)
        }
    }

    companion object {
        private const val STRIDE = 14
        private const val CHUNK_ROWS = 512
        internal fun canPack(command: KoolCanvasCommand.DrawTexture): Boolean =
            (command.state.drawRole == KoolCanvasDrawRole.Generic || command.state.drawRole == KoolCanvasDrawRole.UnitShadow) &&
                ((command.paint.textureEffect as? KoolCanvasTextureEffect.TeamColor)?.amount?.isFinite() != false) &&
                KoolCanvasSpriteAtlas.isSupported(command)
    }
}
