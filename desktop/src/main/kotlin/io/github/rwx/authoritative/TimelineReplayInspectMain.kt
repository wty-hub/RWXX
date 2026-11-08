package io.github.rwx.authoritative

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/** Headless inspection of completed v7 archives; every independent slice is reconstructed. */
object TimelineReplayInspectMain {
    @JvmStatic fun main(args: Array<String>) {
        val values = linkedMapOf<String, String>()
        for (argument in args) {
            require(argument.startsWith("--") && '=' in argument) { "Use --input=... --output=NEW_JSON" }
            val key = argument.substring(2).substringBefore('='); val value = argument.substringAfter('=')
            require(value.isNotBlank() && values.put(key, value) == null) { "Empty or duplicate inspection option" }
        }
        require(values.keys == setOf("input", "output")) { "Use --input=... --output=NEW_JSON" }
        val input = Path.of(values.getValue("input")).toAbsolutePath()
        val output = Path.of(values.getValue("output")).toAbsolutePath()
        require(!Files.exists(output)) { "Inspection evidence already exists" }
        Files.createDirectories(output.parent)
        System.setProperty("java.awt.headless", "true")
        var failure: Throwable? = null
        var team = -1; var slices = 0; var samples = 0; var maximumUnits = 0
        var firstTick = -1L; var finalTick = -1L; var finalPhase = ""
        var firstTime = -1L; var untilTime = -1L; var joinedAt = -1L; var historyFrom = -1L
        var gapCount = 0; var terminalCovered = false
        var completeIndex = false; var allReadOnly = true
        var visual: AuthoritativeMapVisualProfile? = null
        try {
            check(GameEngine.getInstance() == null) { "Inspection must not initialize a game engine" }
            TimelineArchive.open(input).use { reader ->
                check(reader.readOnly && reader.context.mode == TimelineStreamMode.HISTORY)
                team = reader.context.teamId; joinedAt = reader.context.joinedAt; historyFrom = reader.context.historyFrom
                check(reader.index.isNotEmpty()) { "Archive contains no committed slices" }
                firstTime = reader.index.first().from; untilTime = reader.index.last().until
                gapCount = (if (firstTime > historyFrom) 1 else 0) + reader.index.zipWithNext().count { (a, b) -> a.until < b.from }
                for (entry in reader.index) {
                    val slice = checkNotNull(reader.seek(entry.from)) { "Indexed slice is unavailable" }
                    check(slice.readOnly && slice.state.context.mode == TimelineStreamMode.HISTORY && slice.state.header.controlEpoch == 0L)
                    check(slice.from == entry.from && slice.until == entry.until)
                    slices++
                    // Reconstruct once, sample both included boundaries. Preserve a true one-time
                    // sample for a one-subtick slice instead of reporting a duplicate as new coverage.
                    for (time in listOf(entry.from, entry.until - 1).distinct()) {
                        val frame = checkNotNull(TimelineViewAdapter.sample(slice.state, time, samples + 1L, displayOnly = true)) {
                            "An indexed slice has no complete authorized boundary sample"
                        }
                        check(frame.readOnly && frame.unavailableUnitHandles.isEmpty()) { "Archive boundary sample is incomplete or writable" }
                        allReadOnly = allReadOnly && frame.readOnly
                        val profile = AuthoritativeMapVisualProfile.from(frame.snapshot)
                        if (visual == null) visual = profile else check(visual == profile) { "Public map artwork changed" }
                        if (samples == 0) firstTick = time / TimelineProtocol.SUBDIVISIONS
                        finalTick = time / TimelineProtocol.SUBDIVISIONS
                        samples++; maximumUnits = maxOf(maximumUnits, frame.snapshot.units.size)
                        finalPhase = frame.snapshot.matchStatus.phase.name
                        if (frame.finishedAt?.let { it < entry.until && time >= it } == true) terminalCovered = true
                    }
                }
                completeIndex = true
            }
            check(GameEngine.getInstance() == null) { "Inspection initialized a game engine" }
        } catch (error: Throwable) { failure = error }
        val profile = visual ?: AuthoritativeMapVisualProfile()
        val result = buildJsonObject {
            put("passed", failure == null); put("protocolVersion", TimelineProtocol.VERSION)
            put("containerVersion", TimelineArchive.CONTAINER_VERSION); put("teamId", team)
            put("slices", slices); put("samples", samples); put("views", samples); put("visualViews", samples)
            put("completeIndex", completeIndex); put("readOnly", allReadOnly && completeIndex)
            put("gapCount", gapCount); put("hasGaps", gapCount > 0)
            put("partial", gapCount > 0 || !terminalCovered); put("terminalCovered", terminalCovered)
            put("partialMeaning", "Missing authorized intervals or no sampled final match marker; a complete file index does not imply a complete match.")
            put("joinedAt", joinedAt); put("historyFrom", historyFrom); put("from", firstTime); put("until", untilTime)
            put("firstTick", firstTick); put("finalTick", finalTick); put("finalPhase", finalPhase)
            put("maximumDisclosedUnits", maximumUnits); put("staticVisualConsistent", visual != null && failure == null)
            put("catalogVersion", profile.catalogVersion); put("groundCells", profile.groundCells)
            put("decorationCells", profile.decorationCells); put("resourcePools", profile.resourcePools)
            put("groundSha256", profile.groundSha256); put("decorationSha256", profile.decorationSha256)
            put("noGameEngine", GameEngine.getInstance() == null)
            put("scope", "Complete container/index/checksum verification, independent reconstruction of every indexed slice and sampling of each included boundary; no network or engine, no assertion that the whole match was recorded.")
            failure?.let { put("error", it.message ?: it.javaClass.simpleName) }
        }
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result) + "\n",
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        exitProcess(if (failure == null) 0 else 1)
    }
}
