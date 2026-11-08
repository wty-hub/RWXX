package io.github.rwx.net.timeline

/** Internal authorized identities never become wire handles. No world, order or target references. */
data class TimelineObservedUnit(
    val key: Long,
    val position: TimelineValue.Vec3,
    val headingQ: Long,
    val healthQ: Long,
    val maxHealthQ: Long,
    val progressQ: Long,
    val ownerTeam: Int,
    val appearanceKey: String,
    val techLevel: Int,
    val controllable: Boolean,
)

data class TimelineObservedAction(
    val key: Long,
    val ability: TimelineRecord,
    val readiness: Int,
    val queuedCount: Long,
    val stockpileCount: Long,
    val progressQ: Long?,
    val canCancel: Boolean,
)

/** Effects are already projected; [effect] is the registered VISUAL.events record. */
data class TimelineObservedEvent(val key: Long, val time: Long, val effect: TimelineRecord, val maxTailTime: Long)

class TimelineObservation(
    val time: Long,
    val creditsQ: Long,
    val matchStatus: TimelineBytes,
    val fog: TimelineBytes,
    units: List<TimelineObservedUnit>,
    val selectedUnitKey: Long? = null,
    actions: List<TimelineObservedAction> = emptyList(),
    events: List<TimelineObservedEvent> = emptyList(),
    selectedUnitKeys: List<Long> = selectedUnitKey?.let { listOf(it) } ?: emptyList(),
) {
    val units: List<TimelineObservedUnit> = frozenList(units)
    val actions: List<TimelineObservedAction> = frozenList(actions)
    val events: List<TimelineObservedEvent> = frozenList(events)
    val selectedUnitKeys: List<Long> = frozenList(selectedUnitKeys)
}
