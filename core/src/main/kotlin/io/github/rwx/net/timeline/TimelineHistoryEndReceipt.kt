package io.github.rwx.net.timeline

/** Small immutable evidence of an accepted reason-2 End for the exact verified HISTORY library. */
data class TimelineHistoryEndReceipt(
    val streamId: Long,
    val epoch: Long,
    val revision: Long,
    val acceptedFrom: Long,
    val acceptedUntil: Long,
)
