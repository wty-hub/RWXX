package io.github.rwx

import io.github.rwx.net.timeline.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure report arithmetic and bounded sample replacement. No engine or acceptance workload. */
class TimelineScaleRecordDiagnosticsTest {
    private fun state(time: Long, revision: Long, curves: Map<Int, TimelineCurve>): TimelineState {
        val context = TimelineContext(TimelineBytes(ByteArray(16)), TimelineBytes(ByteArray(16) { 1 }),
            TimelineBytes(ByteArray(32)), TimelineRecord.EMPTY, 0, 60)
        val header = TimelineHeader(1, 1, TimelineTransactionKind.RESET, revision, 0,
            time, time, 0, TimelineProtocol.FOREVER, 1, 1, TimelineBytes(ByteArray(32)), 1)
        return TimelineState(context, header, mapOf(1L to TimelineObject(1, TimelineObjectKind.UNIT, 0, curves = curves)))
    }
    private fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long

    @Test fun `record payload estimates match actual codec lengths for all values segments and pulses`() {
        val segments = listOf(
            TimelineMode.STEP to TimelineSegment.Step(0, TimelineProtocol.FOREVER, false, TimelineValue.SInt(TimelineProtocol.MIN_SINT)),
            TimelineMode.LINEAR to TimelineSegment.Interpolated(1, 2, true, 0, 3,
                TimelineValue.Vec3(-129, 0, 129), TimelineValue.Vec3(65536, -65536, 0)),
            TimelineMode.ANGLE to TimelineSegment.Interpolated(0, 3, false, 0, 3, TimelineValue.UInt(65535), TimelineValue.UInt(1)),
            TimelineMode.STEP to TimelineSegment.Step(0, 3, false, TimelineValue.Bool(true)),
            TimelineMode.STEP to TimelineSegment.Step(0, 3, false, TimelineValue.Blob(TimelineBytes(ByteArray(130)))))
        val curves = segments.mapIndexed { index, (mode, segment) ->
            val type = when (segment) {
                is TimelineSegment.Step -> segment.value.type
                is TimelineSegment.Interpolated -> segment.value0.type
            }
            index + 1 to TimelineCurve(index + 1, mode, type, true, segments = listOf(segment))
        }.toMap().toMutableMap()
        val pulse = TimelinePulse(129, 16384, TimelineValue.UInt(Long.MAX_VALUE))
        curves[65536] = TimelineCurve(65536, TimelineMode.PULSE, TimelineValueType.UINT, false,
            eventHighWater = pulse.eventId, events = listOf(pulse))
        val diagnostics = TimelineScaleAcceptanceMain.PublisherRecordDiagnostics()
        val library = state(129, 1, curves)
        diagnostics.observe(1); diagnostics.published(library, 7); diagnostics.capture(library, 7)
        val report = diagnostics.report()
        val sample = report.getValue("lastCountedSuccessfulLibrary").jsonObject
        assertEquals(6L, sample.long("curves")); assertEquals(5L, sample.long("segments"))
        assertEquals(1L, sample.long("predictedSegments")); assertEquals(1L, sample.long("events"))
        assertEquals(6L, sample.long("curveRecords")); assertEquals(7L, sample.long("publicationNativeTick"))
        var actual = TimelineBinary.encodePulse(TimelineValueType.UINT, pulse).size.toLong()
        for ((key, curve) in curves) if (key != 65536) {
            val bytes = TimelineBinary.encodeSegment(curve.mode, curve.valueType, curve.segments.single()).size.toLong()
            assertEquals(bytes, sample.getValue("attributes").jsonObject.getValue("unit.$key").jsonObject.long("recordPayloadBytesEstimated"))
            actual += bytes
        }
        assertEquals(actual, sample.long("recordPayloadBytesEstimated"))
        assertContains(report.getValue("scope").jsonPrimitive.content, "not heap")
    }

    @Test fun `same simulated second replaces final snapshot and preserves exact disclosure peaks without retained states`() {
        val diagnostics = TimelineScaleAcceptanceMain.PublisherRecordDiagnostics()
        val initial = state(0, 1, emptyMap())
        diagnostics.observe(10); diagnostics.published(initial, 0); diagnostics.capture(initial, 0)
        val corrected = state(1024, 2, mapOf(1 to TimelineCurve(1, TimelineMode.STEP, TimelineValueType.BOOL,
            true, segments = listOf(TimelineSegment.Step(0, TimelineProtocol.FOREVER, false, TimelineValue.Bool(true))))))
        diagnostics.observe(12); diagnostics.observe(11)
        diagnostics.published(corrected, 1); diagnostics.capture(corrected, 1)
        assertEquals(0L, diagnostics.report().getValue("lastCountedSuccessfulLibrary").jsonObject.long("curves"))
        diagnostics.capture(corrected, 2, replaceSameSecond = true)
        val next = state(60L * TimelineProtocol.SUBDIVISIONS, 3, emptyMap())
        diagnostics.published(next, 60); diagnostics.capture(next, 60)
        val report = diagnostics.report()
        assertEquals(10L, report.long("initialDisclosedUnits")); assertEquals(11L, report.long("lastDisclosedUnits"))
        assertEquals(12L, report.long("peakDisclosedUnits")); assertEquals(1L, report.long("peakCountedCurveRecords"))
        val samples = report.getValue("perSimulationSecond").jsonArray
        assertEquals(2, samples.size)
        assertEquals(2L, samples[0].jsonObject.long("revision")); assertEquals(1L, samples[0].jsonObject.long("publicationNativeTick"))
        assertEquals(2L, samples[0].jsonObject.long("countedAtNativeTick"))
        assertEquals(60L, report.getValue("lastSuccessfulPublication").jsonObject.long("nativeTick"))
    }
}
