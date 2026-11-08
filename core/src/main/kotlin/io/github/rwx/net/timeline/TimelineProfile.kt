package io.github.rwx.net.timeline

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.TreeMap

/** Pure validation and sampling of profile 1. It never consults a game world or renderer. */
object TimelineProfile {
    private data class Attribute(val mode: TimelineMode, val type: TimelineValueType, val predictive: Boolean = false)
    private val rootAttributes = mapOf(1 to Attribute(TimelineMode.STEP, TimelineValueType.SINT),
        2 to Attribute(TimelineMode.STEP, TimelineValueType.BLOB), 3 to Attribute(TimelineMode.STEP, TimelineValueType.BLOB))
    private val unitAttributes = mapOf(
        1 to Attribute(TimelineMode.LINEAR, TimelineValueType.VEC3, true),
        2 to Attribute(TimelineMode.ANGLE, TimelineValueType.UINT, true),
        3 to Attribute(TimelineMode.STEP, TimelineValueType.SINT),
        4 to Attribute(TimelineMode.STEP, TimelineValueType.SINT),
        5 to Attribute(TimelineMode.LINEAR, TimelineValueType.UINT, true),
        6 to Attribute(TimelineMode.STEP, TimelineValueType.SINT),
        7 to Attribute(TimelineMode.STEP, TimelineValueType.BLOB),
        8 to Attribute(TimelineMode.STEP, TimelineValueType.UINT),
        9 to Attribute(TimelineMode.STEP, TimelineValueType.BOOL))
    private val actionAttributes = mapOf(1 to Attribute(TimelineMode.STEP, TimelineValueType.UINT),
        2 to Attribute(TimelineMode.STEP, TimelineValueType.SINT), 3 to Attribute(TimelineMode.STEP, TimelineValueType.SINT),
        4 to Attribute(TimelineMode.LINEAR, TimelineValueType.UINT, true), 5 to Attribute(TimelineMode.STEP, TimelineValueType.BOOL))

    fun validateContext(context: TimelineContext) {
        protocolRequire(context.profileId == 1 && context.teamId in 0..63 && context.ticksPerSecond in 1..1000,
            "Unsupported timeline profile")
        protocolRequire(context.matchId.size == 16 && context.accessId.size == 16 && context.mapKey.size == 32,
            "Invalid timeline context identity")
        protocolRequire(context.joinedAt in 0..TimelineProtocol.MAX_TIME && context.historyFrom in context.joinedAt..TimelineProtocol.MAX_TIME,
            "Invalid authorization start")
        protocolRequire(context.maxPredictionTime in 1..context.ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS,
            "Invalid prediction horizon")
        protocolRequire((context.acceptedFrom == null) == (context.acceptedUntil == null), "Incomplete accepted window")
        context.acceptedFrom?.let { from ->
            protocolRequire(from >= context.historyFrom && context.acceptedUntil!! > from &&
                context.acceptedUntil <= TimelineProtocol.MAX_TIME, "Invalid accepted history window")
        }
        TimelineBinary.validateMapRecord(context.mapRecord)
        val encoded = TimelineBinary.encodeRecord(context.mapRecord)
        protocolRequire(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()),
            context.mapKey.toByteArray()), "Map key mismatch")
        val width = mapNumber(context, 2).toInt()
        val terrain = mapBytes(context, 6)
        val pool = TimelineBinary.Cursor(mapBytes(context, 10))
        val count = pool.vuint()
        var expected = 0
        for (cell in 0 until terrain.size) if ((terrain[cell].toInt() and 4) != 0) expected++
        protocolRequire(count == expected.toLong(), "Resource pool count differs from terrain")
        repeat(count.toInt()) {
            val x = pool.vuint().toInt()
            val y = pool.vuint().toInt()
            protocolRequire((terrain[y * width + x].toInt() and 4) != 0, "Resource pool differs from terrain")
        }
        pool.finish()
    }

    fun sample(state: TimelineState, handle: Long, attributeId: Int, time: Long): TimelineValue? {
        protocolRequire(time in 0..TimelineProtocol.MAX_TIME, "Invalid sample time")
        if (time < state.header.retainedFrom || time >= state.header.retainedUntil) return null
        val obj = state.objects[handle] ?: return null
        if (time < obj.observedFrom || (obj.close != null && time >= obj.close.until)) return null
        return valueAt(obj.curves[attributeId], time)
    }

    /** Fog's explicit missing-value default is independent of entity disclosure. */
    fun fogPage(state: TimelineState, page: Int, time: Long): ByteArray {
        val cells = mapNumber(state.context, 2) * mapNumber(state.context, 3)
        protocolRequire(page >= 0 && page.toLong() * 256 < cells, "Invalid fog page")
        val length = minOf(256L, cells - page.toLong() * 256).toInt()
        val value = sample(state, 0, 4096 + page, time) as? TimelineValue.Blob ?: return ByteArray(length) { 10 }
        return decodeFog(value.value, length)
    }

    fun references(obj: TimelineObject): List<Long> = when (obj.kind) {
        TimelineObjectKind.PANEL -> listOf(reference(obj, 2))
        TimelineObjectKind.ACTION -> listOf(reference(obj, 2), reference(obj, 3))
        else -> emptyList()
    }

    fun validate(state: TimelineState, previous: TimelineState? = null) {
        val header = state.header
        val context = state.context
        protocolRequire(state.objects.size <= TimelineProtocol.MAX_OBJECTS, "Too many observations")
        val root = state.objects[0] ?: throw ProtocolException("Missing ROOT")
        protocolRequire(root.kind == TimelineObjectKind.ROOT && root.close == null, "Invalid ROOT")
        var recordCount = 0L
        val unitSweep = TreeMap<Long, Int>()
        val actionSweep = TreeMap<Long, Int>()
        val currentPanels = ArrayList<TimelineObject>()
        val observedEnd = minOf(header.observedThrough + 1, header.retainedUntil)
        for ((key, obj) in state.objects) {
            protocolRequire(key == obj.handle && (key == 0L) == (obj.kind == TimelineObjectKind.ROOT) && key >= 0 && key <= header.objectHighWater,
                "Invalid observation identity")
            protocolRequire(obj.observedFrom in context.joinedAt..header.observedThrough, "Observation outside authorization")
            TimelineBinary.validateDescriptor(obj.kind, obj.descriptor)
            obj.close?.let { protocolRequire(it.until in obj.observedFrom..header.observedThrough, "Invalid observation close") }
            val limit = if (obj.kind == TimelineObjectKind.ROOT) TimelineProtocol.MAX_ROOT_ATTRIBUTES else TimelineProtocol.MAX_ATTRIBUTES
            protocolRequire(obj.curves.size <= limit, "Too many attributes")
            val lo = maxOf(obj.observedFrom, header.retainedFrom)
            val hi = minOf(obj.close?.until ?: Long.MAX_VALUE, observedEnd)
            if (lo < hi) when (obj.kind) {
                TimelineObjectKind.UNIT -> addInterval(unitSweep, lo, hi)
                TimelineObjectKind.ACTION -> addInterval(actionSweep, lo, hi)
                else -> Unit
            }
            if (context.mode == TimelineStreamMode.LIVE && obj.kind == TimelineObjectKind.PANEL &&
                activeAt(obj, header.observedThrough)) currentPanels.add(obj)
            val mandatory = when (obj.kind) {
                TimelineObjectKind.ROOT -> rootAttributes.keys
                TimelineObjectKind.UNIT -> unitAttributes.keys
                else -> emptySet()
            }
            mandatory.forEach { id ->
                val curve = obj.curves[id] ?: throw ProtocolException("Missing required attribute")
                if (lo < hi) requireCoverage(curve, lo, hi)
            }
            for ((id, curve) in obj.curves) {
                protocolRequire(id == curve.attributeId && id > 0 && curve.curveRevision >= 0 && curve.eventHighWater >= 0,
                    "Invalid curve identity")
                val registration = attribute(obj.kind, id, context)
                if (registration == null) protocolRequire(id >= 65536 && !curve.criticalAttribute, "Unknown critical or reserved attribute")
                else protocolRequire(curve.criticalAttribute && curve.mode == registration.mode && curve.valueType == registration.type,
                    "Incorrect registered curve format")
                TimelineBinary.validateFormat(curve.mode, curve.valueType)
                TimelineBinary.validateMetadata(curve.mode, curve.metadata, context.ticksPerSecond)
                recordCount += curve.segments.size.toLong() + curve.events.size
                protocolRequire(recordCount <= TimelineProtocol.MAX_CURVE_RECORDS, "Too many curve records")
                if (curve.mode == TimelineMode.PULSE) {
                    protocolRequire(curve.segments.isEmpty(), "Pulse has segments")
                    var last: TimelinePulse? = null
                    var sameTimeCount = 0
                    var sameTimeBytes = 0L
                    val ids = HashSet<Long>()
                    curve.events.forEach { event ->
                        TimelineBinary.validatePulse(curve.valueType, event)
                        protocolRequire(event.time >= obj.observedFrom && event.time <= header.observedThrough &&
                            (obj.close == null || event.time < obj.close.until), "Event outside observation")
                        protocolRequire(event.time <= Long.MAX_VALUE - curve.maxTailTime &&
                            (event.time >= header.retainedFrom || event.time + curve.maxTailTime > header.retainedFrom),
                            "Expired or overflowing event tail")
                        protocolRequire(event.eventId <= curve.eventHighWater && ids.add(event.eventId), "Invalid event high water or duplicate")
                        protocolRequire(last == null || event.time > last!!.time ||
                            (event.time == last!!.time && event.eventId > last!!.eventId), "Unordered pulse")
                        sameTimeCount = if (last?.time == event.time) sameTimeCount + 1 else 1
                        sameTimeBytes = (if (last?.time == event.time) sameTimeBytes else 0L) + pulseSize(event)
                        protocolRequire(sameTimeCount <= TimelineProtocol.MAX_SPLICE_RECORDS, "Too many simultaneous events on one curve")
                        // A timestamp group cannot straddle two replacement intervals. Reserve the
                        // maximum nine-byte encoding for each of the six PulseSplice header values.
                        protocolRequire(sameTimeBytes <= TimelineProtocol.MAX_OPERATION_BYTES - 6 * 9,
                            "Simultaneous pulse group cannot fit a restoring operation")
                        validateValue(state, obj, curve, event.value)
                        last = event
                    }
                } else {
                    protocolRequire(curve.events.isEmpty() && curve.eventHighWater == 0L, "Non-pulse has event state")
                    var end = maxOf(obj.observedFrom, header.retainedFrom)
                    curve.segments.forEach { segment ->
                        TimelineBinary.validateSegment(curve.mode, curve.valueType, segment)
                        protocolRequire(segment.lo >= end && (obj.close == null || segment.hi <= obj.close.until),
                            "Segment overlaps or lies outside observation/window")
                        if (segment.predicted) protocolRequire(registration?.predictive == true &&
                            segment.lo >= header.sealedThrough, "Prediction is not permitted or enters sealed history")
                        when (segment) {
                            is TimelineSegment.Step -> {
                                protocolRequire(!segment.predicted && segment.lo <= header.observedThrough, "STEP cannot predict or start in the future")
                                validateValue(state, obj, curve, segment.value)
                                if (obj.kind == TimelineObjectKind.ROOT && id == 2) {
                                    val result = status((segment.value as TimelineValue.Blob).value, header.observedThrough)
                                    protocolRequire(result.phase != 1 || result.finishedAt <= segment.lo, "Terminal status precedes its finish time")
                                }
                            }
                            is TimelineSegment.Interpolated -> {
                                if (segment.value0 != segment.value1) {
                                    val horizon = if (segment.predicted) {
                                        protocolRequire(header.observedThrough <= TimelineProtocol.MAX_TIME - context.maxPredictionTime,
                                            "Prediction time overflow")
                                        header.observedThrough + context.maxPredictionTime
                                    } else header.observedThrough
                                    protocolRequire(segment.anchor1Time <= horizon, "Anchor exceeds observation/prediction horizon")
                                }
                                validateValue(state, obj, curve, segment.value0)
                                validateValue(state, obj, curve, segment.value1)
                            }
                        }
                        end = segment.hi
                    }
                }
            }
        }
        requireMaximum(unitSweep, TimelineProtocol.MAX_UNITS, "Too many simultaneous units")
        requireMaximum(actionSweep, 128, "Too many simultaneous actions")
        validateReferences(state)
        validateSelections(state)
        protocolRequire(currentPanels.size <= 1, "Multiple current action panels")
        currentPanels.firstOrNull()?.let { panel ->
            val selected = selection(valueAt(root.curves[3], header.observedThrough))
            protocolRequire(selected == listOf(reference(panel, 2)), "Current panel does not match selection")
        }
        validateTerminal(state, previous)
        if (previous != null && header.kind == TimelineTransactionKind.UPDATE && context.mode == TimelineStreamMode.LIVE) {
            val oldSelection = valueAt(previous.objects[0]?.curves?.get(3), previous.header.observedThrough)
            val newSelection = valueAt(root.curves[3], header.observedThrough)
            val oldCapabilities = activeCapabilities(previous)
            val newCapabilities = activeCapabilities(state)
            val newlyClosed = state.objects.values.any { it.close != null && previous.objects[it.handle]?.close == null }
            val unitPermissionChanged = state.objects.values.any { unit ->
                val old = previous.objects[unit.handle]
                unit.kind == TimelineObjectKind.UNIT && old?.kind == TimelineObjectKind.UNIT &&
                    activeAt(unit, header.observedThrough) && activeAt(old, previous.header.observedThrough) &&
                    listOf(6, 7, 9).any { id -> valueAt(unit.curves[id], header.observedThrough) !=
                        valueAt(old.curves[id], previous.header.observedThrough) }
            }
            fun ended(snapshot: TimelineState): Boolean {
                val value = valueAt(snapshot.objects[0]?.curves?.get(2), snapshot.header.observedThrough) as? TimelineValue.Blob ?: return false
                val result = status(value.value, snapshot.header.observedThrough)
                return result.phase == 1 || context.teamId.toLong() in result.defeated
            }
            if (oldSelection != newSelection || oldCapabilities != newCapabilities || newlyClosed || unitPermissionChanged ||
                (!ended(previous) && ended(state)))
                protocolRequire(header.controlEpoch > previous.header.controlEpoch, "Control change did not revoke old epoch")
        }
    }

    private fun attribute(kind: TimelineObjectKind, id: Int, context: TimelineContext): Attribute? = when (kind) {
        TimelineObjectKind.ROOT -> rootAttributes[id] ?: if (id in 4096..8191 &&
            (id - 4096).toLong() * 256 < mapNumber(context, 2) * mapNumber(context, 3)) Attribute(TimelineMode.STEP, TimelineValueType.BLOB) else null
        TimelineObjectKind.UNIT -> unitAttributes[id]
        TimelineObjectKind.PANEL -> if (id == 1) Attribute(TimelineMode.STEP, TimelineValueType.BOOL) else null
        TimelineObjectKind.ACTION -> actionAttributes[id]
        TimelineObjectKind.VISUAL -> if (id == 1) Attribute(TimelineMode.PULSE, TimelineValueType.BLOB) else null
    }

    private fun validateValue(state: TimelineState, obj: TimelineObject, curve: TimelineCurve, value: TimelineValue) {
        TimelineBinary.validateValue(curve.valueType, value)
        if (curve.attributeId >= 65536) return
        when (obj.kind) {
            TimelineObjectKind.ROOT -> when (curve.attributeId) {
                2 -> status((value as TimelineValue.Blob).value, state.header.observedThrough)
                3 -> selection(value)
                in 4096..8191 -> {
                    val cells = mapNumber(state.context, 2) * mapNumber(state.context, 3)
                    decodeFog((value as TimelineValue.Blob).value, minOf(256L, cells - (curve.attributeId - 4096).toLong() * 256).toInt())
                }
            }
            TimelineObjectKind.UNIT -> when (curve.attributeId) {
                1 -> {
                    value as TimelineValue.Vec3
                    protocolRequire(value.x >= 0 && value.x < mapNumber(state.context, 2) * mapNumber(state.context, 4) * 256 &&
                        value.y >= 0 && value.y < mapNumber(state.context, 3) * mapNumber(state.context, 5) * 256, "Position outside map")
                }
                2 -> protocolRequire((value as TimelineValue.UInt).value in 0..65535, "Invalid heading")
                4 -> protocolRequire((value as TimelineValue.SInt).value > 0, "Invalid maximum health")
                5 -> protocolRequire((value as TimelineValue.UInt).value in 0..65535, "Invalid build progress")
                6 -> protocolRequire((value as TimelineValue.SInt).value in -2..63, "Invalid owner team")
                7 -> text((value as TimelineValue.Blob).value, 1, 128)
                8 -> protocolRequire((value as TimelineValue.UInt).value in 1..3, "Invalid tech level")
            }
            TimelineObjectKind.ACTION -> when (curve.attributeId) {
                1 -> protocolRequire((value as TimelineValue.UInt).value in 0..2, "Invalid action readiness")
                2, 3 -> protocolRequire((value as TimelineValue.SInt).value in -1..65536, "Invalid action count")
                4 -> protocolRequire((value as TimelineValue.UInt).value in 0..65535, "Invalid action progress")
            }
            TimelineObjectKind.VISUAL -> if (curve.attributeId == 1) visualEvent(state, curve, (value as TimelineValue.Blob).value)
            TimelineObjectKind.PANEL -> Unit
        }
    }

    private fun validateReferences(state: TimelineState) {
        for (obj in state.objects.values) {
            if (obj.kind != TimelineObjectKind.PANEL && obj.kind != TimelineObjectKind.ACTION) continue
            val subject = state.objects[reference(obj, 2)] ?: throw ProtocolException("Dangling subject reference")
            protocolRequire(subject.kind == TimelineObjectKind.UNIT, "Subject is not a UNIT")
            containedObservation(obj, subject)
            val lo = maxOf(obj.observedFrom, state.header.retainedFrom)
            val hi = minOf(obj.close?.until ?: Long.MAX_VALUE, state.header.observedThrough + 1, state.header.retainedUntil)
            if (lo < hi) requireControllable(subject, state.context.teamId, lo, hi)
            if (obj.kind == TimelineObjectKind.PANEL) {
                val selected = TimelineBinary.decodeSelection((obj.descriptor[5]!!.value as TimelineTlvValue.Bytes).value)
                protocolRequire(selected == listOf(subject.handle), "Panel selection does not match its subject")
            } else {
                val panel = state.objects[reference(obj, 3)] ?: throw ProtocolException("Dangling panel reference")
                protocolRequire(panel.kind == TimelineObjectKind.PANEL && reference(panel, 2) == subject.handle,
                    "Action references a different panel owner")
                containedObservation(obj, panel)
            }
        }
    }

    private fun containedObservation(child: TimelineObject, parent: TimelineObject) {
        protocolRequire(child.observedFrom >= parent.observedFrom &&
            (parent.close == null || (child.close != null && child.close.until <= parent.close.until)),
            "Capability exceeds its subject observation")
    }

    private fun validateSelections(state: TimelineState) {
        val curve = state.objects.getValue(0).curves.getValue(3)
        for (segment in curve.segments) {
            val lo = maxOf(segment.lo, state.header.retainedFrom)
            val hi = minOf(segment.hi, state.header.retainedUntil, state.header.observedThrough + 1)
            if (lo >= hi) continue
            for (handle in selection((segment as TimelineSegment.Step).value)) {
                val unit = state.objects[handle] ?: throw ProtocolException("Selection references a missing UNIT")
                protocolRequire(unit.kind == TimelineObjectKind.UNIT && lo >= unit.observedFrom &&
                    (unit.close == null || hi <= unit.close.until), "Selection exceeds UNIT observation")
                requireControllable(unit, state.context.teamId, lo, hi)
            }
        }
    }

    private fun requireControllable(unit: TimelineObject, team: Int, lo: Long, hi: Long) {
        fun requires(id: Int, valid: (TimelineValue) -> Boolean) {
            val curve = unit.curves[id] ?: throw ProtocolException("Capability owner missing control state")
            requireCoverage(curve, lo, hi)
            curve.segments.forEach { if (it.lo < hi && it.hi > lo)
                protocolRequire(valid((it as TimelineSegment.Step).value), "Capability owner is not controllable") }
        }
        requires(6) { (it as TimelineValue.SInt).value == team.toLong() }
        requires(9) { (it as TimelineValue.Bool).value }
    }

    private data class MatchStatus(val phase: Int, val finishedAt: Long, val winners: List<Long>, val defeated: List<Long>)
    private fun status(bytes: TimelineBytes, observed: Long): MatchStatus {
        val cursor = TimelineBinary.Cursor(bytes)
        val phase = cursor.u8()
        val finished = cursor.vuint()
        fun teams(): List<Long> {
            val count = cursor.vuint()
            protocolRequire(count <= 64, "Too many result teams")
            val result = ArrayList<Long>(count.toInt())
            var previous = -1L
            repeat(count.toInt()) { val value = cursor.vuint(); protocolRequire(value in 0..63 && value > previous,
                "Invalid result teams"); result.add(value); previous = value }
            return result
        }
        val winners = teams()
        val defeated = teams()
        cursor.finish()
        protocolRequire(phase in 0..1 && finished <= observed && (phase != 0 || (finished == 0L && winners.isEmpty())) &&
            winners.none { it in defeated }, "Invalid match status")
        return MatchStatus(phase, finished, winners, defeated)
    }

    private fun validateTerminal(state: TimelineState, previous: TimelineState?) {
        val curve = state.objects.getValue(0).curves.getValue(2)
        var sealedFinished: MatchStatus? = null
        for (segment in curve.segments) {
            val value = status(((segment as TimelineSegment.Step).value as TimelineValue.Blob).value, state.header.observedThrough)
            if (segment.lo < state.header.sealedThrough) {
                if (sealedFinished != null) protocolRequire(value.phase == 1, "Sealed terminal state reverted")
                if (value.phase == 1) sealedFinished = value
            }
        }
        if (previous != null && previous.header.sealedThrough > previous.header.retainedFrom) {
            val old = valueAt(previous.objects[0]?.curves?.get(2), previous.header.sealedThrough - 1) as? TimelineValue.Blob
            if (old != null && status(old.value, previous.header.observedThrough).phase == 1) {
                val now = valueAt(curve, state.header.observedThrough) as? TimelineValue.Blob
                // HISTORY's observedThrough may be outside the requested window.
                val atEnd = now ?: valueAt(curve, minOf(state.header.observedThrough, state.header.retainedUntil - 1)) as? TimelineValue.Blob
                protocolRequire(atEnd != null && status(atEnd.value, state.header.observedThrough).phase == 1, "Terminal state lost on RESET")
            }
        }
        val now = valueAt(curve, state.header.observedThrough) as? TimelineValue.Blob ?: return
        val currentStatus = status(now.value, state.header.observedThrough)
        if (currentStatus.phase == 1 || state.context.teamId.toLong() in currentStatus.defeated) {
            protocolRequire(activeCapabilities(state).isEmpty(), "Terminal/defeated seat still has capabilities")
        }
    }

    private fun activeCapabilities(state: TimelineState): Set<Long> = state.objects.values.asSequence().filter {
        (it.kind == TimelineObjectKind.PANEL || it.kind == TimelineObjectKind.ACTION) && activeAt(it, state.header.observedThrough)
    }.map { it.handle }.toSet()

    private fun visualEvent(state: TimelineState, curve: TimelineCurve, bytes: TimelineBytes) {
        val record = TimelineBinary.decodeRecord(bytes)
        TimelineBinary.validateSchema(record, mapOf(1 to TimelineFieldRule(2, true, true),
            2 to TimelineFieldRule(6, true, true), 3 to TimelineFieldRule(3, true, true),
            4 to TimelineFieldRule(2, true, true), 5 to TimelineFieldRule(5, false, false)))
        protocolRequire((record[1]!!.value as TimelineTlvValue.U32).value in 1..3, "Invalid visual effect")
        val position = TimelineBinary.Cursor((record[2]!!.value as TimelineTlvValue.Bytes).value)
        val x = position.vsint(); val y = position.vsint(); position.vsint(); position.finish()
        protocolRequire(x >= 0 && x < mapNumber(state.context, 2) * mapNumber(state.context, 4) * 256 &&
            y >= 0 && y < mapNumber(state.context, 3) * mapNumber(state.context, 5) * 256, "Effect position outside map")
        protocolRequire((record[3]!!.value as TimelineTlvValue.U64).value <= curve.maxTailTime, "Effect exceeds declared tail")
        record[5]?.let { protocolRequire((it.value as TimelineTlvValue.Text).value.toByteArray(Charsets.UTF_8).size in 1..128,
            "Invalid effect presentation key") }
    }

    private fun decodeFog(bytes: TimelineBytes, length: Int): ByteArray {
        val cursor = TimelineBinary.Cursor(bytes)
        val result = ByteArray(length)
        val encoding = cursor.u8()
        var runs = 0
        var rleBytes = 1
        when (encoding) {
            0 -> for (i in result.indices) { val value = cursor.u8(); protocolRequire(value <= 10, "Invalid fog value"); result[i] = value.toByte() }
            1 -> {
                val count = cursor.vuint()
                protocolRequire(count in 1..length.toLong(), "Invalid fog run count")
                var offset = 0
                var prior = -1
                repeat(count.toInt()) {
                    val run = cursor.vuint()
                    val value = cursor.u8()
                    protocolRequire(run > 0 && run <= length - offset && value <= 10 && value != prior, "Invalid fog run")
                    java.util.Arrays.fill(result, offset, offset + run.toInt(), value.toByte())
                    offset += run.toInt(); prior = value
                }
                protocolRequire(offset == length, "Fog page is incomplete")
            }
            else -> throw ProtocolException("Unknown fog encoding")
        }
        cursor.finish()
        var at = 0
        while (at < length) {
            var until = at + 1
            while (until < length && result[until] == result[at]) until++
            runs++; rleBytes += vuintSize((until - at).toLong()) + 1; at = until
        }
        rleBytes += vuintSize(runs.toLong())
        protocolRequire(encoding == if (rleBytes < length + 1) 1 else 0, "Fog page is not canonically encoded")
        return result
    }

    private fun text(bytes: TimelineBytes, minimum: Int, maximum: Int): String {
        protocolRequire(bytes.size in minimum..maximum, "Text size out of range")
        val decoded = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString() }
        catch (e: java.nio.charset.CharacterCodingException) { throw ProtocolException("Invalid UTF-8", e) }
        protocolRequire(decoded.none { it.code in 0..31 || it.code in 127..159 }, "Control character in text")
        return decoded
    }

    private fun selection(value: TimelineValue?): List<Long> = TimelineBinary.decodeSelection(
        (value as? TimelineValue.Blob)?.value ?: throw ProtocolException("Missing selection value"))
    private fun reference(obj: TimelineObject, id: Int): Long =
        (obj.descriptor[id]?.value as? TimelineTlvValue.U64)?.value ?: throw ProtocolException("Missing descriptor reference")
    private fun mapNumber(context: TimelineContext, id: Int): Long = (context.mapRecord[id]!!.value as TimelineTlvValue.U32).value
    private fun mapBytes(context: TimelineContext, id: Int): TimelineBytes = (context.mapRecord[id]!!.value as TimelineTlvValue.Bytes).value
    private fun activeAt(obj: TimelineObject, time: Long): Boolean = obj.observedFrom <= time && (obj.close == null || time < obj.close.until)
    private fun valueAt(curve: TimelineCurve?, time: Long): TimelineValue? {
        if (curve == null || curve.mode == TimelineMode.PULSE || time !in 0..TimelineProtocol.MAX_TIME) return null
        var low = 0; var high = curve.segments.size - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val segment = curve.segments[middle]
            if (time < segment.lo) high = middle - 1
            else if (time >= segment.hi) low = middle + 1
            else return TimelineBinary.sample(curve.mode, curve.valueType, segment, time)
        }
        return null
    }
    private fun requireCoverage(curve: TimelineCurve, lo: Long, hi: Long) {
        var next = lo
        for (segment in curve.segments) {
            if (segment.hi <= next) continue
            protocolRequire(segment.lo <= next, "Required attribute has a gap")
            next = segment.hi
            if (next >= hi) return
        }
        throw ProtocolException("Required attribute does not cover observation")
    }
    private fun addInterval(sweep: MutableMap<Long, Int>, lo: Long, hi: Long) {
        sweep[lo] = (sweep[lo] ?: 0) + 1
        sweep[hi] = (sweep[hi] ?: 0) - 1
    }
    private fun requireMaximum(sweep: Map<Long, Int>, maximum: Int, message: String) {
        var active = 0
        sweep.values.forEach { active += it; protocolRequire(active <= maximum, message) }
    }
    private fun vuintSize(value: Long): Int { var n = value; var size = 1; while (n >= 128) { n = n ushr 7; size++ }; return size }
    private fun pulseSize(event: TimelinePulse): Long {
        fun signedSize(value: Long) = vuintSize((value shl 1) xor (value shr 63))
        val valueBytes = when (val value = event.value) {
            is TimelineValue.SInt -> signedSize(value.value)
            is TimelineValue.Vec3 -> signedSize(value.x) + signedSize(value.y) + signedSize(value.z)
            is TimelineValue.UInt -> vuintSize(value.value)
            is TimelineValue.Bool -> 1
            is TimelineValue.Blob -> vuintSize(value.value.size.toLong()) + value.value.size
        }
        return vuintSize(event.time).toLong() + vuintSize(event.eventId) + valueBytes
    }
}
