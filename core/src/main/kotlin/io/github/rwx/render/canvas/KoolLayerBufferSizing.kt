package io.github.rwx.render.canvas

/** Startup-only experiment: every map target in a process uses the same fixed pixel size. */
internal object KoolLayerBufferSizing {
    const val PROPERTY = "rwx.koolLayerBufferPixels"
    val startupPixels: Int = configuredPixels(System.getProperty(PROPERTY))

    private fun configuredPixels(value: String?): Int = when (value?.toIntOrNull()) {
        256 -> 256
        384 -> 384
        512 -> 512
        else -> 512
    }
}
