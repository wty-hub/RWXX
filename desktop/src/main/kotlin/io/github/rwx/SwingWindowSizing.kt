package io.github.rwx

import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle

/** All inputs use AWT logical screen coordinates; Kool applies the display scale afterward. */
internal fun initialWindowedFrameBounds(
    contentSize: Dimension,
    frameInsets: Insets,
    screenBounds: Rectangle,
    screenInsets: Insets,
): Rectangle {
    val workX = screenBounds.x + screenInsets.left
    val workY = screenBounds.y + screenInsets.top
    val workWidth = (screenBounds.width - screenInsets.left - screenInsets.right).coerceAtLeast(1)
    val workHeight = (screenBounds.height - screenInsets.top - screenInsets.bottom).coerceAtLeast(1)
    val width = (contentSize.width.toLong() + frameInsets.left + frameInsets.right)
        .coerceIn(1L, workWidth.toLong()).toInt()
    val height = (contentSize.height.toLong() + frameInsets.top + frameInsets.bottom)
        .coerceIn(1L, workHeight.toLong()).toInt()
    return Rectangle(workX + (workWidth - width) / 2, workY + (workHeight - height) / 2, width, height)
}
