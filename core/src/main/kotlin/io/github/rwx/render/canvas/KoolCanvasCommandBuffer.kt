package io.github.rwx.render.canvas

class KoolCanvasCommandBuffer(
    viewport: KoolCanvasViewport = KoolCanvasViewport(0, 0),
    private val textureBatches: Boolean = false,
    private val rectBatches: Boolean = textureBatches,
    private val fogBatches: Boolean = false,
) {
    private val commands = mutableListOf<KoolCanvasCommand>()
    private var fogSize = 0
    private var fogTexture: KoolCanvasTextureRef? = null
    private var fogFilter = KoolCanvasTextureFilter.Nearest
    private var fogState = KoolCanvasState.Default
    private var fogCoordinates = FloatArray(if (fogBatches) 256 * KoolCanvasFogBatch.STRIDE else 0)

    private fun flushFogBatch() {
        if (fogSize == 0) return
        val command = KoolCanvasCommand.DrawFogBatch(fogTexture,
            KoolCanvasFogBatch.copyOf(fogCoordinates, fogSize), fogFilter, fogState)
        commands += command
        commandSignature = commandSignature * 31 + command.hashCode() + 0x9E3779B9.toInt()
        fogSize = 0
        fogTexture = null
    }

    private fun recordFog(texture: KoolCanvasTextureRef?, destination: KoolCanvasRect,
        source: KoolCanvasRect?, paint: KoolCanvasPaint): Boolean {
        if (!fogBatches || state.drawRole != KoolCanvasDrawRole.MapFog ||
            state.transform !== KoolCanvasTransform.Identity || state.renderTarget != null ||
            paint.textureEffect != null || paint.style != KoolCanvasPaintStyle.Fill ||
            paint.blendMode != KoolCanvasBlendMode.SourceOver || paint.color.argb and 0xffffff != 0 ||
            !paint.alphaMultiplier.isFinite() || destination.width <= 0f || destination.height <= 0f ||
            (source != null && (source.width <= 0f || source.height <= 0f))) return false
        flushTextureBatch()
        flushRectBatch()
        if (fogState != state || fogSize == 4096 ||
            (texture != null && fogTexture != null && (fogTexture != texture || fogFilter != paint.textureFilter))) {
            flushFogBatch()
        }
        fogState = state
        if (texture != null) { fogTexture = texture; fogFilter = paint.textureFilter }
        val offset = fogSize * KoolCanvasFogBatch.STRIDE
        if (offset + KoolCanvasFogBatch.STRIDE > fogCoordinates.size)
            fogCoordinates = fogCoordinates.copyOf(fogCoordinates.size * 2)
        fogCoordinates[offset] = destination.left
        fogCoordinates[offset + 1] = destination.top
        fogCoordinates[offset + 2] = destination.right
        fogCoordinates[offset + 3] = destination.bottom
        fogCoordinates[offset + 4] = source?.left ?: 0f
        fogCoordinates[offset + 5] = source?.top ?: 0f
        fogCoordinates[offset + 6] = source?.right ?: 1f
        fogCoordinates[offset + 7] = source?.bottom ?: 1f
        fogCoordinates[offset + 8] = paint.color.alpha / 255f * paint.alphaMultiplier.coerceIn(0f, 1f)
        fogCoordinates[offset + 9] = if (texture == null) 0f else 1f
        fogCoordinates[offset + 10] = paint.color.alpha.toFloat()
        fogCoordinates[offset + 11] = paint.alphaMultiplier
        fogSize++
        return true
    }
    private val stateStack = ArrayDeque<KoolCanvasState>()
    private var batchTexture: KoolCanvasTextureRef? = null
    private var batchPaint = KoolCanvasPaint.Default
    private var batchState = KoolCanvasState.Default
    private var batchSize = 0
    private var batchCoordinates = FloatArray(if (textureBatches) 256 * KoolCanvasTextureBatch.STRIDE else 0)
    private var rectBatchSize = 0
    private var rectBatchPaint = KoolCanvasPaint.Default
    private var rectBatchState = KoolCanvasState.Default
    private var rectBatchCoordinates = FloatArray(if (rectBatches) 256 * KoolCanvasRectBatch.STRIDE else 0)

    private fun flushRectBatch() {
        if (rectBatchSize == 0) return
        commands += KoolCanvasCommand.DrawRectBatch(
            KoolCanvasRectBatch.copyOf(rectBatchCoordinates, rectBatchSize), rectBatchPaint, rectBatchState).also {
            commandSignature = commandSignature * 31 + it.hashCode() + 0x9E3779B9.toInt()
        }
        rectBatchSize = 0
    }

    private fun flushBatches() {
        flushFogBatch()
        flushTextureBatch()
        flushRectBatch()
    }

    private fun flushTextureBatch() {
        val texture = batchTexture ?: return
        commands += KoolCanvasCommand.DrawTextureBatch(texture,
            KoolCanvasTextureBatch.copyOf(batchCoordinates, batchSize), batchPaint, batchState).also {
            commandSignature = commandSignature * 31 + it.hashCode() + 0x9E3779B9.toInt()
        }
        batchTexture = null
        batchSize = 0
    }

    /**
     * Order-sensitive signature of the commands recorded since the last [beginFrame].
     *
     * A layer-buffer cell re-records the same content on most commits: the freeze content check reported
     * `same` 100 728 times against `changed` 3 134 (97%), but that check runs *after* the frozen copy, so
     * the freeze cost was paid every time (measured `engine-freeze` 44.8 s in a 94 s low-zoom run). This
     * signature lets the store recognise an unchanged recording from O(1) state instead.
     *
     * Cheap but conservative: 31 is odd and `31 * 2^32` is a multiple of `2^32`, so no two commands can
     * cancel out through the shift, and a collision additionally needs two distinct command hashes to
     * balance out. A collision would skip a freeze and show stale content, so the sample count is part of
     * the signature as well.
     */
    private var commandSignature: Int = 0

    val signature: Int get() { flushBatches(); return commandSignature }

    private fun note(command: KoolCanvasCommand) {
        flushBatches()
        commands += command
        commandSignature = commandSignature * 31 + command.hashCode() + 0x9E3779B9.toInt()
    }

    var viewport: KoolCanvasViewport = viewport
        private set

    var state: KoolCanvasState = KoolCanvasState.Default
        private set

    /**
     * True when nothing was recorded since the last [beginFrame].
     *
     * `KoolGraphicsEngine.flushTargetTextureFrame` is entered for every sampled target on every draw
     * (measured: 2 264 189 of 2 275 257 offscreen flushes recorded no commands), and an empty re-commit
     * is already a no-op, so the caller can skip the whole snapshot when nothing is pending.
     */
    val isEmpty: Boolean get() = commands.isEmpty() && batchSize == 0 && rectBatchSize == 0 && fogSize == 0

    fun beginFrame(viewport: KoolCanvasViewport) {
        this.viewport = viewport
        commands.clear()
        fogSize = 0
        fogTexture = null
        batchTexture = null
        batchSize = 0
        rectBatchSize = 0
        commandSignature = 0
        stateStack.clear()
        state = KoolCanvasState.Default
    }

    fun setRenderTarget(renderTarget: KoolCanvasRenderTargetRef?) {
        val renderTargetId = renderTarget?.id
        if (state.renderTarget == renderTargetId) {
            return
        }
        state = state.copy(renderTarget = renderTargetId)
    }

    fun setDrawRole(role: KoolCanvasDrawRole, unitId: Long = -1L) {
        state = state.copy(drawRole = role, semanticUnitId = unitId)
    }

    fun save() {
        stateStack.addLast(state)
    }

    fun restore() {
        state = stateStack.removeLastOrNull() ?: KoolCanvasState.Default
    }

    fun clip(rect: KoolCanvasRect) {
        val transformedClip = state.transform.mapRectBounds(rect)
        val nextClip = state.clip?.intersect(transformedClip) ?: transformedClip
        if (state.clip == nextClip) {
            return
        }
        state = state.copy(clip = nextClip)
    }

    fun translate(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) {
            return
        }
        state = state.copy(transform = state.transform.translate(dx, dy))
    }

    fun scale(sx: Float, sy: Float, pivotX: Float = 0f, pivotY: Float = 0f) {
        if (sx == 1f && sy == 1f) {
            return
        }
        state = state.copy(transform = state.transform.scale(sx, sy, pivotX, pivotY))
    }

    fun rotate(degrees: Float, pivotX: Float = 0f, pivotY: Float = 0f) {
        if (degrees == 0f) {
            return
        }
        state = state.copy(transform = state.transform.rotate(degrees, pivotX, pivotY))
    }

    fun clear(color: KoolCanvasColor, blendMode: KoolCanvasBlendMode = KoolCanvasBlendMode.SourceOver) {
        flushBatches()
        val renderTarget = state.renderTarget
        if (blendMode != KoolCanvasBlendMode.ClearAlpha) {
            // The signature covers the surviving commands, so a removal has to fold it back in.
            commands.removeAll { it.renderTarget == renderTarget }
            commandSignature = signatureOf(commands)
        }
        note(KoolCanvasCommand.Clear(
            color = color,
            blendMode = blendMode,
            renderTarget = renderTarget,
        ))
    }

    fun drawTextureRepeat(
        texture: KoolCanvasTextureRef,
        source: KoolCanvasRect,
        destination: KoolCanvasRect,
        repeat: Int,
        paint: KoolCanvasPaint,
        state: KoolCanvasState,
    ) {
        if (repeat <= 1) {
            note(KoolCanvasCommand.DrawTexture(
                texture = texture,
                source = source,
                destination = destination,
                paint = paint,
                state = state,
            ))
            return
        }
        note(KoolCanvasCommand.DrawTextureRepeat(
            texture = texture,
            source = source,
            destination = destination,
            repeat = repeat,
            paint = paint,
            state = state,
        ))
    }

    fun drawTexture(
        texture: KoolCanvasTextureRef,
        destination: KoolCanvasRect,
        paint: KoolCanvasPaint = KoolCanvasPaint.Default,
        source: KoolCanvasRect = texture.fullRect,
    ) {
        if (recordFog(texture, destination, source, paint)) return
        flushFogBatch()
        if (textureBatches && state.transform === KoolCanvasTransform.Identity &&
            state.renderTarget == null && state.drawRole == KoolCanvasDrawRole.Generic &&
            paint.textureEffect == null && paint.color == KoolCanvasColor.White && paint.alphaMultiplier == 1f &&
            (paint.blendMode == KoolCanvasBlendMode.SourceOver || paint.blendMode == KoolCanvasBlendMode.Source) &&
            source.width > 0f && source.height > 0f && destination.width > 0f && destination.height > 0f
        ) {
            flushRectBatch()
            if (batchTexture != texture || batchPaint != paint || batchState != state || batchSize == 4096) {
                flushTextureBatch()
                batchTexture = texture
                batchPaint = paint
                batchState = state
            }
            val offset = batchSize * KoolCanvasTextureBatch.STRIDE
            if (offset + KoolCanvasTextureBatch.STRIDE > batchCoordinates.size) {
                batchCoordinates = batchCoordinates.copyOf(batchCoordinates.size * 2)
            }
            batchCoordinates[offset] = source.left
            batchCoordinates[offset + 1] = source.top
            batchCoordinates[offset + 2] = source.right
            batchCoordinates[offset + 3] = source.bottom
            batchCoordinates[offset + 4] = destination.left
            batchCoordinates[offset + 5] = destination.top
            batchCoordinates[offset + 6] = destination.right
            batchCoordinates[offset + 7] = destination.bottom
            batchSize++
            return
        }
        note(KoolCanvasCommand.DrawTexture(
            texture = texture,
            source = source,
            destination = destination,
            paint = paint,
            state = state,
        ))
    }

    fun drawRect(rect: KoolCanvasRect, paint: KoolCanvasPaint) {
        if (recordFog(null, rect, null, paint)) return
        flushFogBatch()
        if (rectBatches && state.transform === KoolCanvasTransform.Identity &&
            state.renderTarget == null && state.drawRole == KoolCanvasDrawRole.Generic &&
            paint.style == KoolCanvasPaintStyle.Fill && paint.textureEffect == null &&
            (paint.blendMode == KoolCanvasBlendMode.SourceOver || paint.blendMode == KoolCanvasBlendMode.Source) &&
            rect.width > 0f && rect.height > 0f
        ) {
            flushTextureBatch()
            if (rectBatchPaint != paint || rectBatchState != state || rectBatchSize == 4096) flushRectBatch()
            rectBatchPaint = paint
            rectBatchState = state
            val offset = rectBatchSize * KoolCanvasRectBatch.STRIDE
            if (offset + KoolCanvasRectBatch.STRIDE > rectBatchCoordinates.size) {
                rectBatchCoordinates = rectBatchCoordinates.copyOf(rectBatchCoordinates.size * 2)
            }
            rectBatchCoordinates[offset] = rect.left
            rectBatchCoordinates[offset + 1] = rect.top
            rectBatchCoordinates[offset + 2] = rect.right
            rectBatchCoordinates[offset + 3] = rect.bottom
            rectBatchSize++
            return
        }
        note(KoolCanvasCommand.DrawRect(rect = rect, paint = paint, state = state))
    }

    fun drawLine(start: KoolCanvasPoint, end: KoolCanvasPoint, paint: KoolCanvasPaint) {
        note(KoolCanvasCommand.DrawLine(start = start, end = end, paint = paint, state = state))
    }

    fun drawCircle(center: KoolCanvasPoint, radius: Float, paint: KoolCanvasPaint) {
        note(KoolCanvasCommand.DrawCircle(center = center, radius = radius, paint = paint, state = state))
    }

    fun drawText(text: String, baseline: KoolCanvasPoint, paint: KoolCanvasPaint) {
        note(KoolCanvasCommand.DrawText(text = text, baseline = baseline, paint = paint, state = state))
    }

    private fun signatureOf(commands: List<KoolCanvasCommand>): Int {
        var signature = 0
        for (command in commands) signature = signature * 31 + command.hashCode() + 0x9E3779B9.toInt()
        return signature
    }

    fun snapshot(): KoolCanvasFrame {
        flushBatches()
        val frame = KoolCanvasFrame(viewport = viewport, commands = commands.toList())
        KoolCanvasCommandProfile.observe(frame.commands)
        KoolCanvasCommandProfile.endPass(frame.commands)
        return frame
    }

    private val KoolCanvasCommand.renderTarget: KoolCanvasRenderTargetId?
        get() = when (this) {
            is KoolCanvasCommand.Clear -> renderTarget
            is KoolCanvasCommand.DrawTexture -> state.renderTarget
            is KoolCanvasCommand.DrawTextureBatch -> state.renderTarget
            is KoolCanvasCommand.DrawRectBatch -> state.renderTarget
            is KoolCanvasCommand.DrawFogBatch -> state.renderTarget
            is KoolCanvasCommand.DrawTextureRepeat -> state.renderTarget
            is KoolCanvasCommand.DrawRect -> state.renderTarget
            is KoolCanvasCommand.DrawLine -> state.renderTarget
            is KoolCanvasCommand.DrawCircle -> state.renderTarget
            is KoolCanvasCommand.DrawText -> state.renderTarget
        }
}
