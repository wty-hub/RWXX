package io.github.rwx.session

import com.corrodinggames.rts.gameFramework.statistics.AndroidMenu
import java.util.Collections

/** Detached values only: no menu item, engine object or mutable CharSequence crosses to the UI. */
data class InGameMenuOptionSnapshot(
    val menuId: Int,
    val label: String,
    val isModOverride: Boolean = false,
)

internal fun AndroidMenu.snapshotOptions(modMenuIds: Set<Int> = emptySet()): List<InGameMenuOptionSnapshot> =
    Collections.unmodifiableList(List(size()) { index ->
        val item = getItem(index)
        InGameMenuOptionSnapshot(item.itemId, item.title.toString(), item.itemId in modMenuIds)
    })
