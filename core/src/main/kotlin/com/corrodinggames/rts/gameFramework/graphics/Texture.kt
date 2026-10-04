package com.corrodinggames.rts.gameFramework.graphics

import com.corrodinggames.rts.game.ColorMode
import com.corrodinggames.rts.gameFramework.GameEngine

open class Texture : Cloneable {
    @JvmField
    var a: Array<Texture>? = null

    @JvmField
    var b: Array<Texture>? = null

    @JvmField
    var c: Array<Texture>? = null

    @JvmField
    var d: Int = nextTextureId()

    @JvmField
    var e: Int = 1

    @JvmField
    var f: Int = 0

    @JvmField
    var g: String? = null

    @JvmField
    var h: Int? = null

    @JvmField
    var j: IntArray? = null

    private var committedArgbPixels: IntArray? = null
    private var immutableArgbPixelsRelease: (() -> Unit)? = null
    private var argbPixelLoader: (() -> IntArray?)? = null
    private var orderedAlphaRequired: Boolean = false
    private var premultipliedAlpha: Boolean = false
    private var pixelRevision: Int = 0
    private var shaderProgram: ShaderProgram? = null

    @JvmField
    var m: Boolean = false

    @JvmField
    var n: Boolean = false

    @JvmField
    var p: Int = 0

    @JvmField
    var q: Int = 0

    @JvmField
    var r: Int = 0

    @JvmField
    var s: Int = 0

    @JvmField
    var t: Float = 0f

    @JvmField
    var u: Float = 0f

    @JvmField
    var v: Boolean = false

    @JvmField
    var l: Boolean = true

    @JvmField
    var o: Boolean = false

    @JvmField
    var w: Boolean = false

    open fun a(colorMode: ColorMode): Array<Texture>? =
        when (colorMode) {
            ColorMode.pureGreen -> a
            ColorMode.hueAdd -> b
            ColorMode.hueShift -> c
            else -> {
                GameEngine.logColored("getTeamImageCache coloringMode:$colorMode")
                a
            }
        }

    open fun a(colorMode: ColorMode, textures: Array<Texture>?) {
        when (colorMode) {
            ColorMode.pureGreen -> a = textures
            ColorMode.hueAdd -> b = textures
            ColorMode.hueShift -> c = textures
            else -> {
                GameEngine.logColored("setTeamImageCache coloringMode:$colorMode")
                a = textures
            }
        }
    }

    open fun a(name: String?) {
        g = name
    }

    open fun a(): String? = g

    open fun b(): IntArray? {
        i()
        return j
    }

    val argbPixelsCopy: IntArray?
        get() = loadArgbPixelsIfNeeded()?.clone()

    /**
     * The live ARGB pixel array, without the defensive copy [argbPixelsCopy] performs.
     *
     * Display backends that only need to read the pixels to upload them use this: cloning a
     * 512x512 layer buffer cell on every frame is pure garbage. The caller must treat the array as
     * read-only and must not retain it beyond the current frame, because the owner keeps mutating
     * or replacing it.
     */
    val argbPixelsRef: IntArray?
        get() = loadArgbPixelsIfNeeded()

    /**
     * Whether the backend should expand the RGB of opaque texels into neighbouring transparent
     * ones before uploading. Only decoded images need it: their transparent texels carry an
     * arbitrary RGB that linear filtering would bleed into sprite edges. Textures whose pixels are
     * produced by the backend itself (layer buffers, fog overlays, render targets) are drawn with
     * matching extents, so bleeding them only costs an O(pixels) pass on every update.
     */
    @JvmField
    var alphaBleedRequired: Boolean = false

    open fun c(): Texture = this

    open fun a(value: Boolean) {
        o = value
        e()
    }

    open fun b(value: Boolean) {
        w = value
    }

    open fun d(): Boolean = w

    protected open fun e() = Unit

    open fun f(): Boolean = m

    open fun g() {
        r = p / 2
        s = q / 2
        t = p / 2.0f
        u = q / 2.0f
    }

    open fun a(texture: Texture) {
        texture.o = o
        texture.m = m
        texture.p = p
        texture.q = q
        texture.r = r
        texture.s = s
        texture.t = t
        texture.u = u
        texture.alphaBleedRequired = alphaBleedRequired
    }

    public open override fun clone(): Texture {
        val texture = Texture()
        texture.o = o
        texture.m = m
        texture.p = p
        texture.q = q
        texture.alphaBleedRequired = alphaBleedRequired
        texture.g()
        val pixels = argbPixelsCopy
        if (pixels != null) {
            texture.j = pixels
            texture.committedArgbPixels = pixels.clone()
        }
        return texture
    }

    open fun a(width: Int, height: Int, copyPixels: Boolean): Texture {
        val texture = Texture()
        texture.o = o
        texture.m = m
        texture.p = width
        texture.q = height
        texture.alphaBleedRequired = alphaBleedRequired
        texture.g()
        if (copyPixels) {
            texture.j = IntArray(width * height)
            val copyWidth = minOf(width, p)
            val copyHeight = minOf(height, q)
            for (x in 0 until copyWidth) {
                for (y in 0 until copyHeight) {
                    texture.j!![x + (y * width)] = a(x, y)
                }
            }
            texture.committedArgbPixels = texture.j!!.clone()
            texture.m = containsTransparentPixels(texture.j!!)
        }
        return texture
    }

    open fun i() {
        if (j == null) {
            j()
        }
    }

    open fun j() {
        val committedPixels = loadArgbPixelsIfNeeded()
        if (j == null && committedPixels != null) {
            j = committedPixels.clone()
            return
        }
        if (j == null) {
            if (p <= 0 || q <= 0) {
                return
            }
            j = IntArray(p * q)
        }
    }

    open fun k(): Boolean = true

    open fun a(x: Int, y: Int): Int {
        loadArgbPixelsIfNeeded()
        val editable = j
        if (editable != null) {
            return editable[x + (y * p)]
        }
        val committed = committedArgbPixels
        if (committed != null) {
            return committed[x + (y * p)]
        }
        return 0
    }

    open fun a(x: Int, y: Int, color: Int) {
        loadArgbPixelsIfNeeded()
        val editable = j
        when {
            editable != null -> {
                editable[x + (y * p)] = color
                pixelRevision++
            }

            committedArgbPixels != null -> {
                ensureWritableCommittedArgbPixels()
                committedArgbPixels!![x + (y * p)] = color
                pixelRevision++
            }

            else -> {
                if (p <= 0 || q <= 0) {
                    p = maxOf(p, x + 1)
                    q = maxOf(q, y + 1)
                    g()
                }
                committedArgbPixels = IntArray(p * q)
                committedArgbPixels!![x + (y * p)] = color
                pixelRevision++
            }
        }
    }

    open fun l(): Int = q

    open fun m(): Int = p

    open fun n() = Unit

    open fun o() {
        if (w) {
            GameEngine.logColored("remove with keepInGPUMemory=true")
        }
        argbPixelLoader = null
        if (immutableArgbPixelsRelease != null) {
            committedArgbPixels = null
            releaseImmutableArgbPixels()
        }
    }

    open fun p() {
        val editable = j
        if (editable != null) {
            val committed = editable.clone()
            releaseImmutableArgbPixels()
            committedArgbPixels = committed
            argbPixelLoader = null
            m = containsTransparentPixels(committed)
            pixelRevision++
        }
        e++
    }

    open fun q() = Unit

    open fun r() {
        j = null
    }

    open fun s() {
        r()
    }

    open fun t() = Unit

    open fun u(): Int = p * q * 8

    open fun v() {
        a = null
        b = null
        c = null
        pixelRevision++
        e++
    }

    open fun w() = Unit

    open fun x() = Unit

    open fun y() = Unit

    open fun z() = Unit

    open fun A(): Boolean = false

    open fun B(): ShaderProgram? = shaderProgram

    open fun a(shaderProgram: ShaderProgram?) {
        this.shaderProgram = shaderProgram
    }

    fun shaderProgram(): ShaderProgram? = B()

    fun setShaderProgram(shaderProgram: ShaderProgram?) = a(shaderProgram)

    fun setSourceName(name: String?) = a(name)

    fun sourceName(): String? = a()

    fun editablePixels(): IntArray? = b()

    fun resolvedTexture(): Texture = c()

    fun updateCenter() = g()

    fun copyTextureSettingsTo(texture: Texture) = a(texture)

    fun width(): Int = m()

    fun height(): Int = l()

    fun commitPixels() = p()

    fun discardEditPixels() = s()

    fun releaseTexture() = o()

    fun estimatedMemoryBytes(): Int = u()

    fun ensureLoaded() = w()

    fun setCommittedArgbPixels(pixels: IntArray) {
        val detached = pixels.clone()
        j = null
        releaseImmutableArgbPixels()
        committedArgbPixels = detached
        argbPixelLoader = null
        m = containsTransparentPixels(detached)
        pixelRevision++
    }

    /** Backend-owned read-only pixels. The caller supplies a retained owner before replacement. */
    internal fun setImmutableArgbPixels(pixels: IntArray, release: () -> Unit) {
        releaseImmutableArgbPixels()
        j = null
        committedArgbPixels = pixels
        immutableArgbPixelsRelease = release
        argbPixelLoader = null
        m = containsTransparentPixels(pixels)
        pixelRevision++
    }

    private fun ensureWritableCommittedArgbPixels() {
        if (immutableArgbPixelsRelease != null) {
            committedArgbPixels = committedArgbPixels!!.clone()
            releaseImmutableArgbPixels()
        }
    }

    private fun releaseImmutableArgbPixels() {
        val release = immutableArgbPixelsRelease
        immutableArgbPixelsRelease = null
        release?.invoke()
    }

    fun setArgbPixelLoader(loader: (() -> IntArray?)?) {
        if (j == null && committedArgbPixels == null) {
            argbPixelLoader = loader
        }
    }

    protected fun invalidateArgbPixelSnapshot(loader: (() -> IntArray?)? = null) {
        j = null
        releaseImmutableArgbPixels()
        committedArgbPixels = null
        argbPixelLoader = loader
        pixelRevision++
    }

    fun setPremultipliedAlpha(value: Boolean) {
        premultipliedAlpha = value
    }

    fun usesPremultipliedAlpha(): Boolean = premultipliedAlpha

    fun getPixelRevision(): Int = pixelRevision

    fun requireOrderedAlpha() {
        orderedAlphaRequired = true
    }

    fun requiresOrderedAlpha(): Boolean = orderedAlphaRequired

    companion object {
        private var x: Int = 0

        private fun containsTransparentPixels(pixels: IntArray): Boolean =
            pixels.any { (it ushr 24) != 0xff }

        private fun nextTextureId(): Int {
            val id = x
            x = id + 1
            return id
        }
    }

    private fun loadArgbPixelsIfNeeded(): IntArray? {
        j?.let { return it }
        committedArgbPixels?.let { return it }
        val loaded = argbPixelLoader?.invoke()
        argbPixelLoader = null
        if (loaded != null) {
            committedArgbPixels = loaded
            m = containsTransparentPixels(loaded)
        }
        return loaded
    }
}
