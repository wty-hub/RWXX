package io.github.rwx.net.timeline

/** Successful complete client-to-server input writes; no claim of gameplay acceptance or execution. */
data class TimelineInputWriteStatistics(
    val commandsWritten: Long,
    val selectionsWritten: Long,
)
