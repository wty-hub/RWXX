package io.github.rwx.settings

import kotlin.math.roundToInt

object AuthoritativeMapCacheSettings {
    const val KEY = "authoritativeMapCacheLimit"
    const val DEFAULT_LIMIT = 16
    const val MAX_LIMIT = 256

    fun normalize(value: Int): Int = value.coerceIn(0, MAX_LIMIT)
    fun normalize(value: Float): Int = if (value.isFinite()) normalize(value.roundToInt()) else DEFAULT_LIMIT
}
