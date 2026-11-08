package io.github.rwx

import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.test.*

class TimelineFullChainSupportTest {
    @Test fun `capacity options cannot silently reduce the required workload`() {
        val options = FullChainSupport.options(arrayOf("--output=/tmp/new-fullchain-result.json"))
        assertEquals(600, options.ticks)
        assertEquals(TimelineFullChainLayout.Scenario.entries, options.scenarios)
        assertEquals(180, options.deadlineSeconds)
        for (arguments in listOf(arrayOf("--ticks=599"), arrayOf("--ticks=3601"), arrayOf("--scenario=unknown"),
            arrayOf("--deadline-seconds=0"), arrayOf("--output=/tmp/other"), arrayOf("--unknown=true"))) {
            assertFails { FullChainSupport.options(arrayOf("--output=/tmp/new-fullchain-result.json", *arguments)) }
        }
    }

    @Test fun `evidence publication cannot overwrite an existing file or leave temporary metadata`() {
        val directory = Files.createTempDirectory("fullchain-evidence-test-")
        try {
            val path = directory.resolve("new.json")
            val first = buildJsonObject { put("passed", false); put("completed", false) }
            FullChainSupport.writeNew(path, first)
            assertEquals(first, FullChainSupport.read(path))
            assertFailsWith<FileAlreadyExistsException> { FullChainSupport.writeNew(path, buildJsonObject { put("passed", true) }) }
            assertEquals(first, FullChainSupport.read(path))
            assertEquals(listOf(path), Files.list(directory).use { it.toList() })
        } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun `latency percentiles use only actually completed tick samples`() {
        val samples = longArrayOf(100, 1, 9, 4, 0, 0, 0)
        assertEquals(4.0, FullChainSupport.percentile(samples, 4, .5))
        assertEquals(100.0, FullChainSupport.percentile(samples, 4, .95))
        assertEquals(Double.POSITIVE_INFINITY, FullChainSupport.percentile(samples, 0, .95))
        assertContentEquals(longArrayOf(100, 1, 9, 4, 0, 0, 0), samples)
    }

    @Test fun `probe identity never falls back to a nearby or foreign moving unit`() {
        val exact = unit(1, 100, 200)
        val moved = unit(2, 101, 200)
        val enemy = unit(3, 100, 200, team = 10)
        val building = unit(4, 100, 200, type = "commandCenter")
        val uncontrolled = unit(5, 100, 200, controlled = false)
        val closed = unit(6, 100, 200).copy(close = TimelineClose(50, TimelineCloseKind.ENDED))
        assertEquals(1L, FullChainSupport.exactProbe(state(listOf(exact, moved, enemy, building, uncontrolled, closed)), 100, 200)?.handle)
        val missing = state(listOf(moved, enemy, building, uncontrolled, closed, unit(7, 110, 200), unit(8, 120, 200), unit(9, 130, 200)))
        assertNull(FullChainSupport.exactProbe(missing, 100, 200))
        val diagnostic = FullChainSupport.probeDiagnostic(missing, 100, 200)
        assertEquals(listOf(2L, 7L, 8L), diagnostic.getValue("nearest3ActiveControllableOwnTanks").jsonArray.map {
            it.jsonObject.getValue("disclosureHandle").jsonPrimitive.long
        })
        assertNull(FullChainSupport.exactProbe(state(listOf(exact, unit(2, 100, 200))), 100, 200), "Ambiguous exact positions must fail")
    }

    @Test fun `owner step tail retains chronological bounded observations and labels admission separately`() {
        val tail = FullChainSupport.OwnerStepTail(3)
        tail.add(1, "ADMISSION", 10, 20); tail.add(2, "ADMISSION", 20, 30)
        tail.add(3, "MEASURED", 30, 40); val old = tail.snapshot()
        tail.add(4, "MEASURED", 40, 50); tail.add(5, "POST_MEASUREMENT", 50, 60)
        assertEquals(0L, old.getValue("droppedCount").jsonPrimitive.long)
        val snapshot = tail.snapshot()
        assertEquals(5L, snapshot.getValue("completedSteps").jsonPrimitive.long)
        assertEquals(2L, snapshot.getValue("droppedCount").jsonPrimitive.long)
        val steps = snapshot.getValue("steps").jsonArray
        assertEquals(listOf(3, 4, 5), steps.map { it.jsonObject.getValue("tick").jsonPrimitive.int })
        assertEquals(listOf("MEASURED", "MEASURED", "POST_MEASUREMENT"), steps.map { it.jsonObject.getValue("scope").jsonPrimitive.contentOrNull })
        assertEquals(listOf(10L, 20L, 30L), old.getValue("steps").jsonArray.map { it.jsonObject.getValue("nativeAdvanceNanos").jsonPrimitive.long })
        assertFailsWith<IllegalArgumentException> { tail.add(6, "ADMISSION", 100, 99) }
    }

    @Test fun `owner cpu diagnostics distinguish unavailable clocks from measured zero and wall waits`() {
        val tail = FullChainSupport.OwnerStepTail(4)
        tail.add(1, "MEASURED", 50, 100, 3, 8)
        tail.add(2, "MEASURED", 0, 10)
        val snapshot = tail.snapshot()
        val steps = snapshot.getValue("steps").jsonArray
        assertEquals(3L, steps[0].jsonObject.getValue("nativeOwnerCpuNanos").jsonPrimitive.long)
        assertEquals(8L, steps[0].jsonObject.getValue("wholeRoomOwnerCpuNanos").jsonPrimitive.long)
        assertFalse(steps[1].jsonObject.containsKey("nativeOwnerCpuNanos"))
        assertFalse(steps[1].jsonObject.containsKey("wholeRoomOwnerCpuNanos"))
        assertEquals(1, snapshot.getValue("byScope").jsonArray.single().jsonObject.getValue("ownerCpuSamples").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { tail.add(3, "MEASURED", 1, 2, 1, null) }
        assertFailsWith<IllegalArgumentException> { tail.add(3, "MEASURED", 1, 2, -1, 0) }
        assertFailsWith<IllegalArgumentException> { tail.add(3, "MEASURED", 1, 2, 3, 2) }
        assertEquals(2L, tail.snapshot().getValue("completedSteps").jsonPrimitive.long)
    }

    private fun state(units: List<TimelineObject>): TimelineState {
        val bytes = TimelineBytes(ByteArray(16))
        val context = TimelineContext(bytes, bytes, TimelineBytes(ByteArray(32)), TimelineRecord.EMPTY, 9, 60)
        return TimelineState(context, TimelineHeader(1, 1, TimelineTransactionKind.RESET, 1, 0, 100, 100,
            0, 1000, 1, 1, TimelineBytes(ByteArray(32)), 100), units.associateBy { it.handle })
    }
    private fun unit(handle: Long, x: Long, y: Long, team: Int = 9, type: String = "tank", controlled: Boolean = true): TimelineObject {
        fun step(id: Int, value: TimelineValue) = TimelineCurve(id, TimelineMode.STEP, value.type, true,
            segments = listOf(TimelineSegment.Step(0, Long.MAX_VALUE, false, value)))
        val point = TimelineValue.Vec3(x, y, 0)
        val position = TimelineCurve(1, TimelineMode.LINEAR, TimelineValueType.VEC3, true, segments = listOf(
            TimelineSegment.Interpolated(0, Long.MAX_VALUE, false, 0, Long.MAX_VALUE, point, point)))
        return TimelineObject(handle, TimelineObjectKind.UNIT, 0, curves = mapOf(1 to position,
            6 to step(6, TimelineValue.SInt(team.toLong())), 7 to step(7, TimelineValue.Blob(TimelineBytes(type.toByteArray()))),
            9 to step(9, TimelineValue.Bool(controlled))))
    }
}
