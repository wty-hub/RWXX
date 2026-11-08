package io.github.rwx.net.authoritative

/** Engine/UI command semantics. These are not network messages or a serialization schema. */
enum class OrderKind { MOVE, ATTACK_MOVE, STOP, ATTACK_UNIT }

sealed interface CommandTarget {
    data object None : CommandTarget
    data class Point(val x: Float, val y: Float) : CommandTarget
    data class Unit(val handle: Long) : CommandTarget
}

/** Opaque authorized-projection handles, never global engine object IDs. */
data class ViewUnitOrder(
    val sequence: Long,
    val viewSequence: Long,
    val kind: OrderKind,
    val unitHandles: List<Long>,
    val target: CommandTarget = CommandTarget.None,
) {
    val x: Float get() = (target as? CommandTarget.Point)?.x ?: 0f
    val y: Float get() = (target as? CommandTarget.Point)?.y ?: 0f
    val targetHandle: Long get() = (target as? CommandTarget.Unit)?.handle ?: 0L
}

/** A local projection query. Zero unitHandle clears the selected action owner. */
data class ViewActionPanelQuery(val querySequence: Long, val viewSequence: Long, val unitHandle: Long)

/** An engine/UI operation using capabilities issued by the active authorized projection. */
data class ViewActionCommand(
    val sequence: Long,
    val viewSequence: Long,
    val panelSequence: Long,
    val unitHandle: Long,
    val actionHandle: Long,
    val target: CommandTarget = CommandTarget.None,
    val queued: Boolean = false,
    val cancel: Boolean = false,
) {
    val x: Float get() = (target as? CommandTarget.Point)?.x ?: 0f
    val y: Float get() = (target as? CommandTarget.Point)?.y ?: 0f
    val targetHandle: Long get() = (target as? CommandTarget.Unit)?.handle ?: 0L
}
