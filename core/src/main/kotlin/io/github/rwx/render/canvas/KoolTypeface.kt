package io.github.rwx.render.canvas

class KoolTypeface private constructor(
    private val nativeHandle: Int,
    private val style: Int = defaultStyle(nativeHandle),
    private val family: String? = null,
) {
    private val stableKey = if (reuseTypefaceKeys) (family ?: "default") + ":" + style else null

    fun a(): Boolean = (style and 1) != 0

    val koolKey: String
        get() = stableKey ?: ((family ?: "default") + ":" + style)

    override fun equals(other: Any?): Boolean =
        this === other || (other is KoolTypeface && style == other.style && nativeHandle == other.nativeHandle)

    override fun hashCode(): Int = (31 * nativeHandle) + style

    @Suppress("deprecation")
    protected fun finalize() {
        release(nativeHandle)
    }

    companion object {
        private val reuseTypefaceKeys = (System.getenv("RWX_REUSE_TYPEFACE_KEYS") == "1").also {
            println("[RWX canvas] reuseTypefaceKeys=$it")
        }
        private val cache = mutableMapOf<Int, MutableMap<Int, KoolTypeface>>()

        @JvmField
        val a: KoolTypeface = a(null as String?, 0)

        @JvmField
        val b: KoolTypeface = a(null as String?, 1)

        @JvmField
        val c: KoolTypeface = a("sans-serif", 0)

        @JvmField
        val d: KoolTypeface = a("serif", 0)

        @JvmField
        val e: KoolTypeface = a("monospace", 0)

        @JvmField
        val f: Array<KoolTypeface> = arrayOf(a, b, a(null as String?, 2), a(null as String?, 3))

        @JvmStatic
        fun a(family: String?, style: Int): KoolTypeface = KoolTypeface(0, style, family)

        @JvmStatic
        fun a(typeface: KoolTypeface?, style: Int): KoolTypeface {
            var nativeHandle = 0
            if (typeface != null) {
                if (typeface.style == style) {
                    return typeface
                }
                nativeHandle = typeface.nativeHandle
            }
            val typefaceCache = cache.getOrPut(nativeHandle) { HashMap(4) }
            typefaceCache[style]?.let { return it }

            return KoolTypeface(0, style, typeface?.family).apply {
                typefaceCache[style] = this
            }
        }

        private fun release(nativeHandle: Int) = Unit

        private fun defaultStyle(nativeHandle: Int): Int = 0
    }
}
