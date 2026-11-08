package io.github.rwx.net.authoritative

/** Closed command semantics. No native ActionId, engine object ID or asset path crosses the wire. */
enum class ViewActionKind {
    BUILD, PRODUCE, UPGRADE, RALLY, REPAIR, RECLAIM,
    LOAD_INTO, LOAD_UP, UNLOAD, STOP_UNLOAD, TARGET_GROUND, TOGGLE,
    GUARD, PATROL, ATTACK_MODE,
}
enum class ActionTargetMode { NONE, POINT, VISIBLE_UNIT }
enum class ActionReadiness { READY, BLOCKED, UNKNOWN }

/** Only reviewed, self-owned resource values; arbitrary custom resources and flags stay on server. */
data class ViewActionCost(
    val credits: Int = 0,
    val energy: Float = 0f,
    val hp: Float = 0f,
    val shield: Float = 0f,
    val ammo: Int = 0,
)

data class ViewAction(
    val handle: Long,
    val kind: ViewActionKind,
    val targetMode: ActionTargetMode,
    val presentationKey: String,
    val producedTypeKey: String = "",
    val upfrontCost: ViewActionCost? = null,
    val streamingCost: ViewActionCost? = null,
    val readiness: ActionReadiness = ActionReadiness.UNKNOWN,
    /** -1 means unknown; stockpiles are deliberately separate from real queue entries. */
    val queuedCount: Int = -1,
    val stockpileCount: Int = -1,
    val progress: Float = -1f,
    val canCancel: Boolean = false,
)

/** A separate bounded response: it never takes capacity away from the complete world view. */
data class PlayerActionPanel(
    val querySequence: Long,
    val panelSequence: Long,
    val viewSequence: Long,
    val teamId: Int,
    /** Zero plus an empty action list is the uniform response to an invalid/cleared selection. */
    val unitHandle: Long,
    val actions: List<ViewAction>,
)
