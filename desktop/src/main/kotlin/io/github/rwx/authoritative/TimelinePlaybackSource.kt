package io.github.rwx.authoritative

import io.github.rwx.net.timeline.TimelineHistoryEndReceipt
import io.github.rwx.net.timeline.TimelineInputWriteStatistics

/** Common controls for network timelines and permanently read-only archive playback. */
internal interface TimelinePlaybackSource : ViewSource {
    val playback: TimelinePlaybackController
    var activePane: Int
    fun samplePane(paneId: Int, nowNanos: Long): TimelineDisplayFrame?
    fun completedHistoryForPane(paneId: Int): TimelineHistoryEndReceipt? = null
    fun inputWriteStatistics(): TimelineInputWriteStatistics? = null
}
