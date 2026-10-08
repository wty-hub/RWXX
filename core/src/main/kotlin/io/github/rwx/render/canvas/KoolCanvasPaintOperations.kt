package io.github.rwx.render.canvas

/** Immutable value transformation: a missing team effect preserves the original paint effect. */
internal object KoolCanvasPaintOperations {
    private val reportReuse = System.getenv("RWX_FRAME_METRICS") != null
    @Volatile private var reported = false

    fun textureEffect(paint: KoolCanvasPaint, teamEffect: KoolCanvasTextureEffect?, reuse: Boolean): KoolCanvasPaint {
        val resolved = teamEffect ?: paint.textureEffect
        if (reuse && resolved == paint.textureEffect) {
            if (reportReuse && !reported) {
                reported = true
                println("RWXTexturePaintCopies reuseConfirmed=true")
            }
            return paint
        }
        return paint.copy(textureEffect = resolved)
    }
}
