package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Owner-confined, engine-free observation builder. Only immutable publications cross threads. */
class TimelinePublisher(val context: TimelineContext, val liveWindowSeconds: Int = 30) {
    private val store = TimelineStore(context)
    private val encoder = TimelineEncoder()
    private val second = context.ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS
    private val interval = maxOf(1L, second / 10)
    private val window = liveWindowSeconds * second
    // Leave one normal publication interval below the target five-second horizon.
    private val sealInterval = minOf(second * 5 - interval, maxOf(1L, window - interval))
    private val sealNanos = sealInterval * 1_000_000_000L / second
    private val widthQ = number(2) * number(4) * 256
    private val heightQ = number(3) * number(5) * 256
    private val cells = (number(2) * number(3)).toInt()
    private val objects = LinkedHashMap<Long, MutableObject>()
    private val activeUnits = LinkedHashMap<Long, UnitTrack>()
    private val actions = LinkedHashMap<Long, ActionTrack>()
    private val continuous = LinkedHashMap<TimelineCurveKey, Motion>()
    private val effects = ArrayList<Long>()
    private data class EffectLoad(var count: Int = 0, var bytes: Long = 0)
    private val effectGroups = HashMap<Long, MutableMap<Long, EffectLoad>>()
    private val pendingEffects = HashMap<Long, MutableList<TimelinePulse>>()
    private val effectHighWater = HashMap<Long, Long>()
    private val seenEffects = HashMap<Long, Long>()
    // Inputs own immutable bytes/strings. Cache only successful validation, with a fixed bound.
    private val appearances = HashMap<String, TimelineValue.Blob>()
    private var lastFog: TimelineBytes? = null
    private var trimmedFrom: Long? = null
    private var firstTime: Long? = null
    private var observationOwner: Thread? = null
    private var time = 0L
    private var highWater = 0L
    private var control = 1L
    private var selected: Long? = null
    private var currentSelection: List<Long> = emptyList()
    private var panel: Long? = null
    private var changed = false
    private var noWorkObserved = false
    private var confirmationScanCount = 0L
    private var fittingSampleCheckCount = 0L
    private var lastPublishNanos: Long? = null
    private var lastSealNanos: Long? = null
    @Volatile private var publishedUnits: Map<Long, Long> = emptyMap()
    @Volatile private var publishedActions: Map<Long, Long> = emptyMap()
    @Volatile private var published: TimelineState? = null
    val latest: TimelineState? get() = published

    /** Owner-only diagnostic; no concurrent Session/transport view of mutable samples. */
    internal val retainedRawSampleCount: Long
        get() {
            check(observationOwner === Thread.currentThread()) { "Raw samples belong to the observation owner" }
            return continuous.values.sumOf { it.samples.size.toLong() }
        }

    /** Primitive payload capacity only; no shared or materialized timeline cache is counted. */
    internal val retainedRawStorageLongCount: Long
        get() {
            check(observationOwner === Thread.currentThread()) { "Raw storage belongs to the observation owner" }
            return continuous.values.sumOf { it.samples.allocatedLongCount }
        }

    /** Counts confirmation attempts on the observation owner, without exposing mutable state. */
    internal val confirmationScans: Long
        get() {
            check(observationOwner === Thread.currentThread()) { "Confirmation scans belong to the observation owner" }
            return confirmationScanCount
        }

    /** Owner-only work diagnostic; the bound is checked without timing or allocation assertions. */
    internal val fittingSampleChecks: Long
        get() {
            check(observationOwner === Thread.currentThread()) { "Fit work belongs to the observation owner" }
            return fittingSampleCheckCount
        }

    init { protocolRequire(context.mode == TimelineStreamMode.LIVE && liveWindowSeconds in 1..120, "Publisher requires bounded LIVE context") }

    private class MutableObject(val handle: Long, val kind: TimelineObjectKind, val from: Long, val descriptor: TimelineRecord) {
        var close: TimelineClose? = null
        val curves = LinkedHashMap<Int, TimelineCurve>()
        var motions: MutableMap<Int, Motion>? = null
        val references = TimelineProfile.references(TimelineObject(handle, kind, from, descriptor))
        fun freeze() = TimelineObject(handle, kind, from, descriptor, close, curves)
    }
    private class UnitTrack(val handle: Long, var value: TimelineObservedUnit)
    private class ActionTrack(val handle: Long, var value: TimelineObservedAction)
    private class Motion(val objectHandle: Long, val attribute: Int, val mode: TimelineMode, val type: TimelineValueType) {
        val samples = TimelineRawSamples(type)
        var dirty = true
    }

    fun unitKey(handle: Long): Long? = publishedUnits[handle]
    fun actionKey(handle: Long): Long? = publishedActions[handle]

    fun observe(observation: TimelineObservation) {
        noWorkObserved = false
        val input = validateObservation(observation)
        val initial = firstTime == null
        time = observation.time
        if (initial) {
            observationOwner = Thread.currentThread()
            firstTime = time
            objects[0] = MutableObject(0, TimelineObjectKind.ROOT, time, TimelineRecord.EMPTY)
        }
        var revoke = false
        var selectedIdentityChanged = false
        val activeIterator = activeUnits.entries.iterator()
        while (activeIterator.hasNext()) {
            val entry = activeIterator.next()
            if (entry.key !in input) {
                close(entry.value.handle, time)
                activeIterator.remove()
                revoke = true
            }
        }
        val fresh = observation.units.filter { it.key !in activeUnits }.sortedWith(unitOrder)
        fresh.forEach { unit ->
            val handle = allocate(TimelineObjectKind.UNIT, time)
            activeUnits[unit.key] = UnitTrack(handle, unit)
        }
        observation.units.forEach { unit ->
            val track = activeUnits.getValue(unit.key)
            val old = track.value
            if (!initial && (old.ownerTeam != unit.ownerTeam || old.controllable != unit.controllable || old.appearanceKey != unit.appearanceKey)) {
                revoke = true
                if (unit.key == selected) selectedIdentityChanged = true
            }
            val obj = objects.getValue(track.handle)
            val first = obj.curves.isEmpty()
            motion(obj, 1, TimelineMode.LINEAR, unit.position)
            motion(obj, 2, TimelineMode.ANGLE, TimelineValue.UInt(unit.headingQ))
            if (first || old.healthQ != unit.healthQ) step(obj, 3, TimelineValue.SInt(unit.healthQ))
            if (first || old.maxHealthQ != unit.maxHealthQ) step(obj, 4, TimelineValue.SInt(unit.maxHealthQ))
            motion(obj, 5, TimelineMode.LINEAR, TimelineValue.UInt(unit.progressQ))
            if (first || old.ownerTeam != unit.ownerTeam) step(obj, 6, TimelineValue.SInt(unit.ownerTeam.toLong()))
            if (first || old.appearanceKey != unit.appearanceKey) step(obj, 7, appearance(unit.appearanceKey))
            if (first || old.techLevel != unit.techLevel) step(obj, 8, TimelineValue.UInt(unit.techLevel.toLong()))
            if (first || old.controllable != unit.controllable) step(obj, 9, TimelineValue.Bool(unit.controllable))
            track.value = unit
        }
        val root = objects.getValue(0)
        step(root, 1, TimelineValue.SInt(observation.creditsQ))
        val oldStatus = at(root.curves[2], time) as? TimelineValue.Blob
        if (oldStatus != null && oldStatus.value != observation.matchStatus && ended(observation.matchStatus)) revoke = true
        step(root, 2, TimelineValue.Blob(observation.matchStatus))
        val validSelection = observation.selectedUnitKeys.filter { key ->
            input[key]?.let { it.ownerTeam == context.teamId && it.controllable } == true && !ended(observation.matchStatus)
        }
        val selectionHandles = validSelection.map { activeUnits.getValue(it).handle }.sorted()
        if (selectionHandles != currentSelection) revoke = true
        currentSelection = selectionHandles
        val chosen = validSelection.singleOrNull()
        if (chosen != selected || selectedIdentityChanged) {
            closeCapabilities()
            selected = chosen
            revoke = true
        }
        step(root, 3, TimelineValue.Blob(selection(selectionHandles)))
        if (chosen != null) {
            val subject = activeUnits.getValue(chosen).handle
            if (panel == null) {
                panel = allocate(TimelineObjectKind.PANEL, time, TimelineRecord(listOf(
                    TimelineField(2, true, TimelineTlvValue.U64(subject)),
                    TimelineField(5, true, TimelineTlvValue.Bytes(selection(subject))))))
                step(objects.getValue(panel!!), 1, TimelineValue.Bool(true))
                revoke = true
            }
            val incoming = observation.actions.associateBy { it.key }
            for (key in actions.keys.toList()) {
                val old = actions.getValue(key)
                if (incoming[key]?.ability != old.value.ability) {
                    close(old.handle, time); actions.remove(key); revoke = true
                }
            }
            observation.actions.filter { it.key !in actions }.sortedWith(actionOrder).forEach { action ->
                val handle = allocate(TimelineObjectKind.ACTION, time, TimelineRecord(listOf(
                    TimelineField(2, true, TimelineTlvValue.U64(subject)), TimelineField(3, true, TimelineTlvValue.U64(panel!!)),
                    TimelineField(4, true, TimelineTlvValue.Record(action.ability)))))
                actions[action.key] = ActionTrack(handle, action)
                revoke = true
            }
            observation.actions.forEach { action ->
                val track = actions.getValue(action.key)
                val obj = objects.getValue(track.handle)
                step(obj, 1, TimelineValue.UInt(action.readiness.toLong()))
                step(obj, 2, TimelineValue.SInt(action.queuedCount))
                step(obj, 3, TimelineValue.SInt(action.stockpileCount))
                motion(obj, 4, TimelineMode.LINEAR, action.progressQ?.let { TimelineValue.UInt(it) })
                step(obj, 5, TimelineValue.Bool(action.canCancel))
                track.value = action
            }
        } else if (panel != null || actions.isNotEmpty()) { closeCapabilities(); revoke = true }
        if (revoke && !initial) control = increment(control)
        if (lastFog != observation.fog) {
            for (page in 0 until (cells + 255) / 256) {
                val offset = page * 256
                val length = minOf(256, cells - offset)
                val previousFog = lastFog
                if (previousFog != null && (offset until offset + length).all { previousFog[it] == observation.fog[it] }) continue
                val values = ByteArray(length) { observation.fog[offset + it] }
                if (values.all { it.toInt() == 10 } && root.curves[4096 + page] == null) continue
                step(root, 4096 + page, TimelineValue.Blob(encodeFog(values)))
            }
        }
        lastFog = observation.fog
        observation.events.filter { it.key !in seenEffects }.map { it to TimelineBinary.encodeRecord(it.effect) }
            .sortedWith(compareBy<Pair<TimelineObservedEvent, TimelineBytes>> { it.first.time }
                .thenComparator { a, b -> compareBytes(a.second, b.second) }).forEach { (event, payload) ->
            appendEffect(event, payload)
            seenEffects[event.key] = event.time
        }
    }

    /** Explicit clock injection keeps cadence tests deterministic; callers may also schedule this themselves. */
    fun publish(nowNanos: Long = System.nanoTime()): TimelineState? = publishInternal(nowNanos, false)

    /** Intake is closed and drained; confirm existing observations without advancing their time. */
    internal fun publishConfirmed(nowNanos: Long): TimelineState? = publishInternal(nowNanos, true)

    private fun publishInternal(nowNanos: Long, confirmNow: Boolean): TimelineState? {
        val start = firstTime ?: return null
        val previousNanos = lastPublishNanos
        protocolRequire(previousNanos == null || nowNanos >= previousNanos, "Publication clock regressed")
        if (!confirmNow && previousNanos != null && nowNanos - previousNanos < 100_000_000L) return null
        val beforeHeartbeat = previousNanos != null && nowNanos - previousNanos < 1_000_000_000L
        val oldSeal = store.current?.header?.sealedThrough ?: start
        val terminal = (at(objects.getValue(0).curves[2], time) as? TimelineValue.Blob)?.let { status(it.value, time).first } == true
        val sealDue = time > oldSeal && (confirmNow || terminal || time - oldSeal >= sealInterval ||
            lastSealNanos?.let { nowNanos - it >= sealNanos } == true)
        if (noWorkObserved && !changed && beforeHeartbeat && !sealDue) return null
        // Only the complete negative check below grants this shortcut. An attempted
        // observation, heartbeat, publication, or failed work must establish it again.
        noWorkObserved = false
        confirmationScanCount++
        val mustConfirm = sealDue || continuous.values.any { track ->
            val obj = objects[track.objectHandle]
            obj != null && obj.close == null && track.samples.isNotEmpty() && track.dirty
        }
        if (!changed && !mustConfirm && beforeHeartbeat) {
            noWorkObserved = true
            return null
        }
        if (!changed && !mustConfirm && published?.header?.observedThrough == time) {
            // This observation is already committed and the full scan found no pending
            // confirmation. Peer encoders provide receipt heartbeats; each stream still
            // hashes its entire committed state independently. An uncommitted newer time
            // must continue below, even when every observed value stayed constant.
            lastPublishNanos = nowNanos
            noWorkObserved = true
            return null
        }
        flushEffects()
        continuous.values.forEach { track ->
            val obj = objects[track.objectHandle] ?: return@forEach
            if (obj.close == null && track.samples.isNotEmpty() && (track.dirty ||
                (sealDue && obj.curves[track.attribute]?.segments?.any { it.predicted && it.lo < time } == true))) rebuild(obj, track, null, oldSeal)
        }
        var sealed = if (sealDue) time else oldSeal
        objects.values.forEach { obj -> obj.curves.values.forEach { curve ->
            curve.segments.forEach { if (it.predicted) sealed = minOf(sealed, it.lo) }
        } }
        sealed = maxOf(oldSeal, sealed)
        val retained = maxOf(start, context.historyFrom, time - window)
        trim(retained)
        val until = if (time > TimelineProtocol.MAX_TIME - context.maxPredictionTime) Long.MAX_VALUE else time + context.maxPredictionTime + 1
        val desired = TimelineState(context, TimelineHeader(1, 1, TimelineTransactionKind.RESET, 1, 0,
            time, sealed, retained, until, control, 1, TimelineCanonical.sha256(TimelineBytes(byteArrayOf(0))), highWater),
            objects.mapValues { it.value.freeze() })
        // This candidate is private. The actual encoded result below must pass Store's
        // complete Profile and canonical-capacity validation before any publication.
        val previous = store.current
        fun cursor(reset: Boolean) = TimelineSendCursor(context, 1, if (reset) (previous?.header?.epoch ?: 0) + 1 else previous!!.header.epoch, previous, reset)
        val publication = try { encoder.encode(cursor(previous == null), desired) }
            catch (_: TimelineResetRequired) { encoder.encode(cursor(true), desired) }
        if (publication == null) { lastPublishNanos = nowNanos; changed = false; return null }
        val state = store.apply(publication.header, publication.operations)
        objects.keys.retainAll(state.objects.keys)
        state.objects.forEach { (handle, obj) ->
            objects.getValue(handle).apply {
                // Replacing existing values preserves our owned LinkedHashMap nodes.
                // RESET/new declarations may change iteration order, which is retained.
                val oldKeys = curves.keys.iterator()
                val newKeys = obj.curves.keys.iterator()
                var sameOrder = curves.size == obj.curves.size
                while (sameOrder && oldKeys.hasNext()) sameOrder = oldKeys.next() == newKeys.next()
                if (!sameOrder) curves.clear()
                curves.putAll(obj.curves)
                close = obj.close
            }
        }
        continuous.entries.removeIf { it.key.handle !in objects }
        continuous.values.forEach { track ->
            val floor = minOf(state.header.sealedThrough, time - context.maxPredictionTime)
            track.samples.trimPredecessors(floor)
        }
        seenEffects.entries.removeIf { it.value < retained - second * 10 }
        effectGroups.values.forEach { groups -> groups.entries.removeIf { it.key < retained && it.key <= retained - second * 10 } }
        publishedUnits = frozenMap(activeUnits.entries.associate { it.value.handle to it.key })
        publishedActions = frozenMap(actions.entries.associate { it.value.handle to it.key })
        published = state
        lastPublishNanos = nowNanos
        if (previous == null || state.header.sealedThrough > previous.header.sealedThrough) lastSealNanos = nowNanos
        changed = false
        return state
    }

    private fun step(obj: MutableObject, id: Int, value: TimelineValue) {
        val previous = obj.curves[id]
        if (at(previous, time) == value) return
        val curve = previous ?: TimelineCurve(id, TimelineMode.STEP, value.type, true)
        val prefix = clippedPrefix(curve.segments, time)
        obj.curves[id] = curve.copy(segments = prefix + TimelineSegment.Step(time, Long.MAX_VALUE, false, value))
        changed = true
    }

    private fun motion(obj: MutableObject, id: Int, mode: TimelineMode, value: TimelineValue?) {
        var track = obj.motions?.get(id)
        if (value == null) {
            if (track != null && track.samples.isNotEmpty()) {
                rebuild(obj, track, time, store.current?.header?.sealedThrough ?: firstTime!!)
                track.samples.clear(); track.dirty = false; changed = true
            }
            return
        }
        if (track == null) {
            track = Motion(obj.handle, id, mode, value.type)
            val tracks = obj.motions ?: HashMap<Int, Motion>(4).also { obj.motions = it }
            tracks[id] = track
            continuous[TimelineCurveKey(obj.handle, id)] = track
        }
        if (track.samples.isEmpty()) {
            val curve = obj.curves[id] ?: TimelineCurve(id, mode, value.type, true)
            obj.curves[id] = curve.copy(segments = clippedPrefix(curve.segments, time) + constant(time, Long.MAX_VALUE, value))
            track.samples.appendObserved(time, value); track.dirty = false; changed = true
            return
        }
        track.samples.appendObserved(time, value)
        val current = at(obj.curves[id], time)
        if (current == null || !near(mode, current, value)) track.dirty = true
    }

    /** A synthetic lower-bound point followed by an unchanged slice of the raw run. */
    private inner class RebuildPoints(private val raw: TimelinePointRun, private val start: Int,
                                     private val from: Long, private val firstValue: TimelineValue) : TimelinePointRun {
        override val size: Int get() = 1 + raw.size - start
        private fun checkIndex(index: Int) { check(index in 0 until size) { "Rebuild point index outside run" } }
        override fun timeAt(index: Int): Long { checkIndex(index); return if (index == 0) from else raw.timeAt(start + index - 1) }
        override fun valueAt(index: Int): TimelineValue { checkIndex(index); return if (index == 0) firstValue else raw.valueAt(start + index - 1) }
        override fun nearAt(index: Int, mode: TimelineMode, actual: TimelineValue): Boolean {
            checkIndex(index)
            return if (index == 0) near(mode, actual, firstValue) else raw.nearAt(start + index - 1, mode, actual)
        }
    }
    private object EmptyPoints : TimelinePointRun {
        override val size = 0
        override fun timeAt(index: Int): Long = error("Empty point run")
        override fun valueAt(index: Int): TimelineValue = error("Empty point run")
        override fun nearAt(index: Int, mode: TimelineMode, actual: TimelineValue): Boolean = error("Empty point run")
    }

    private fun rebuild(obj: MutableObject, track: Motion, closeAt: Long?, oldSeal: Long) {
        // This synchronous owner stack performs no raw append/trim/callback. A borrowed
        // run checks both owner and generation; only immutable endpoint values escape.
        val samples = track.samples.borrow()
        if (samples.size == 0) return
        val curve = obj.curves.getValue(track.attribute)
        val from = maxOf(oldSeal, samples.timeAt(0))
        val lastTime = samples.timeAt(samples.size - 1)
        val lastValue = samples.valueAt(samples.size - 1)
        val points: TimelinePointRun = if (from <= lastTime) {
            val firstValue = rawAt(samples, track.mode, track.type, from) ?: at(curve, from) ?: samples.valueAt(0)
            var start = 0
            while (start < samples.size && samples.timeAt(start) <= from) start++
            RebuildPoints(samples, start, from, firstValue)
        } else EmptyPoints
        val replacement = ArrayList<TimelineSegment>()
        var lastFitStart = 0
        var previousEndIndex = -1
        var previousEndValue: TimelineValue? = null
        for (span in fitSpans(track, points)) {
            val aTime = points.timeAt(span.from); val bTime = points.timeAt(span.until)
            val aValue = if (previousEndIndex == span.from) checkNotNull(previousEndValue) else points.valueAt(span.from)
            val bValue = points.valueAt(span.until)
            lastFitStart = span.from
            replacement.add(TimelineSegment.Interpolated(aTime, bTime, false, aTime, bTime, aValue, bValue))
            previousEndIndex = span.until; previousEndValue = bValue
        }
        if (closeAt != null) {
            if (lastTime < closeAt) replacement.add(constant(maxOf(from, lastTime), closeAt, lastValue))
        } else {
            val prediction = if (points.size >= 2) predict(track, points, lastFitStart) else predict(track, samples)
            if (prediction == lastValue || lastTime > TimelineProtocol.MAX_TIME - context.maxPredictionTime)
                replacement.add(constant(maxOf(from, lastTime), Long.MAX_VALUE, lastValue))
            else replacement.add(TimelineSegment.Interpolated(lastTime, lastTime + context.maxPredictionTime, true,
                lastTime, lastTime + context.maxPredictionTime, lastValue, prediction))
        }
        obj.curves[track.attribute] = curve.copy(segments = clippedPrefix(curve.segments, from) + replacement)
        track.dirty = false
    }

    private fun predict(track: Motion, samples: TimelinePointRun, fromIndex: Int = 0): TimelineValue {
        val bTime = samples.timeAt(samples.size - 1)
        val bValue = samples.valueAt(samples.size - 1)
        val aTime = samples.timeAt(fromIndex)
        if (aTime == bTime) return bValue
        val aValue = samples.valueAt(fromIndex)
        fun extrapolate(x: Long, y: Long): Long {
            val n = BigInteger.valueOf(y) * BigInteger.valueOf(bTime - aTime) +
                (BigInteger.valueOf(y) - BigInteger.valueOf(x)) * BigInteger.valueOf(context.maxPredictionTime)
            return rounded(n, BigInteger.valueOf(bTime - aTime)).coerceIn(BigInteger.valueOf(TimelineProtocol.MIN_SINT),
                BigInteger.valueOf(TimelineProtocol.MAX_SINT)).toLong()
        }
        return when (val v = bValue) {
            is TimelineValue.Vec3 -> (aValue as TimelineValue.Vec3).let { old -> TimelineValue.Vec3(
                extrapolate(old.x, v.x).coerceIn(0, widthQ - 1), extrapolate(old.y, v.y).coerceIn(0, heightQ - 1), extrapolate(old.z, v.z)) }
            is TimelineValue.UInt -> {
                val old = (aValue as TimelineValue.UInt).value
                if (track.mode == TimelineMode.ANGLE) {
                    val delta = angleDelta(old, v.value)
                    val projected = rounded(BigInteger.valueOf(delta) * BigInteger.valueOf(context.maxPredictionTime),
                        BigInteger.valueOf(bTime - aTime)).coerceIn(BigInteger.valueOf(-32767), BigInteger.valueOf(32768)).toLong()
                    TimelineValue.UInt(Math.floorMod(v.value + projected, 65536L))
                } else TimelineValue.UInt(extrapolate(old, v.value).coerceIn(0, 65535))
            }
            else -> throw ProtocolException("Unregistered continuous observation")
        }
    }

    private fun fits(track: Motion, samples: TimelinePointRun, from: Int, until: Int): Boolean {
        val aTime = samples.timeAt(from); val bTime = samples.timeAt(until)
        val segment = TimelineSegment.Interpolated(aTime, bTime, false, aTime, bTime, samples.valueAt(from), samples.valueAt(until))
        for (index in from + 1 until until) {
            fittingSampleCheckCount++
            val actual = TimelineBinary.sample(track.mode, track.type, segment, samples.timeAt(index))!!
            if (!samples.nearAt(index, track.mode, actual)) return false
        }
        return true
    }

    private data class FitSpan(val from: Int, val until: Int)

    /**
     * End-point feasibility is not monotone. Balanced subdivision and bounded disjoint
     * pairing keep exact sample checks O(n log n) without binary-searching that predicate.
     */
    private fun fitSpans(track: Motion, samples: TimelinePointRun): List<FitSpan> {
        if (samples.size < 2) return emptyList()
        var spans = ArrayList<FitSpan>()
        fun split(from: Int, until: Int) {
            if (until == from + 1 || fits(track, samples, from, until)) spans.add(FitSpan(from, until))
            else {
                val middle = (from + until).ushr(1)
                split(from, middle); split(middle, until)
            }
        }
        split(0, samples.size - 1)
        var levels = 0; var capacity = 1
        while (capacity < samples.size) { capacity = capacity shl 1; levels++ }
        var unchangedPasses = 0
        for (pass in 0 until levels * 2) {
            if (spans.size < 2 || unchangedPasses == 2) break
            val merged = ArrayList<FitSpan>(spans.size)
            var at = 0; var joined = false
            if ((pass and 1) != 0) merged.add(spans[at++])
            while (at < spans.size) {
                val left = spans[at]
                if (at + 1 < spans.size) {
                    val right = spans[at + 1]
                    if (fits(track, samples, left.from, right.until)) {
                        merged.add(FitSpan(left.from, right.until)); at += 2; joined = true; continue
                    }
                    merged.add(left); merged.add(right); at += 2; continue
                }
                merged.add(left); at++
            }
            spans = merged
            unchangedPasses = if (joined) 0 else unchangedPasses + 1
        }
        return spans
    }

    private fun rawAt(samples: TimelinePointRun, mode: TimelineMode, type: TimelineValueType, point: Long): TimelineValue? {
        for (index in 0 until samples.size) if (samples.timeAt(index) == point) return samples.valueAt(index)
        for (index in 0 until samples.size - 1) {
            val aTime = samples.timeAt(index); val bTime = samples.timeAt(index + 1)
            if (aTime < point && point < bTime) return TimelineBinary.sample(mode, type,
                TimelineSegment.Interpolated(aTime, bTime, false, aTime, bTime, samples.valueAt(index), samples.valueAt(index + 1)), point)
        }
        return null
    }

    private fun close(handle: Long, at: Long) {
        val obj = objects.getValue(handle)
        if (obj.close != null) return
        obj.motions?.values?.forEach { track ->
            rebuild(obj, track, at, store.current?.header?.sealedThrough ?: firstTime!!)
            track.samples.clear(); track.dirty = false
        }
        obj.curves.replaceAll { _, curve -> curve.copy(segments = clippedPrefix(curve.segments, at), events = curve.events.filter { it.time < at }) }
        obj.close = TimelineClose(at, TimelineCloseKind.ENDED)
        changed = true
    }

    private fun closeCapabilities() {
        actions.values.forEach { close(it.handle, time) }
        actions.clear()
        panel?.let { close(it, time) }
        panel = null
    }

    private fun trim(from: Long) {
        // No new observation can precede an already retained lower bound. Avoid rebuilding
        // every immutable curve while the window has not moved (including the first 30 s).
        if (trimmedFrom == from) return
        objects.values.forEach { obj -> obj.curves.replaceAll { _, curve ->
            val clipSegments = curve.segments.firstOrNull()?.lo?.let { it < from } == true
            val clipEvents = curve.events.firstOrNull()?.let { it.time < from && it.time + curve.maxTailTime <= from } == true
            if (!clipSegments && !clipEvents) curve else curve.copy(
                segments = if (clipSegments) curve.segments.filter { it.hi > from }.map { if (it.lo < from) it.clipped(from, it.hi) else it } else curve.segments,
                events = if (clipEvents) curve.events.filter { it.time >= from || it.time + curve.maxTailTime > from } else curve.events)
        } }
        repeat(3) {
            val referenced = HashSet<Long>()
            objects.values.forEach { referenced.addAll(it.references) }
            objects.entries.removeIf { (_, obj) -> obj.close?.let { close ->
                close.until <= Long.MAX_VALUE - (obj.curves.values.maxOfOrNull { it.maxTailTime } ?: 0) &&
                    close.until + (obj.curves.values.maxOfOrNull { it.maxTailTime } ?: 0) <= from && obj.handle !in referenced
            } == true }
        }
        trimmedFrom = from
    }

    private fun appendEffect(event: TimelineObservedEvent, payload: TimelineBytes) {
        val blob = TimelineValue.Blob(payload)
        val packed = varSize(event.time) + 9 + varSize(blob.value.size.toLong()) + blob.value.size
        var chosen: Long? = null
        for (handle in effects) {
            val group = effectGroups.getValue(handle)[event.time]
            if ((group?.count ?: 0) < TimelineProtocol.MAX_SPLICE_RECORDS &&
                (group?.bytes ?: 0) + packed <= TimelineProtocol.MAX_OPERATION_BYTES - 54) {
                chosen = handle; break
            }
        }
        if (chosen == null) {
            val handle = allocate(TimelineObjectKind.VISUAL, event.time)
            effects.add(handle)
            chosen = handle
            effectGroups[handle] = HashMap()
            effectHighWater[handle] = 0
            objects.getValue(handle).curves[1] = TimelineCurve(1, TimelineMode.PULSE, TimelineValueType.BLOB, true, metadata =
                TimelineRecord(listOf(TimelineField(1, true, TimelineTlvValue.U64(second * 10)))))
        }
        val handle = checkNotNull(chosen)
        val id = increment(effectHighWater.getValue(handle))
        effectHighWater[handle] = id
        val group = effectGroups.getValue(handle).getOrPut(event.time) { EffectLoad() }
        group.count++; group.bytes += packed
        pendingEffects.getOrPut(handle) { ArrayList() }.add(TimelinePulse(event.time, id, blob))
        changed = true
    }

    private fun flushEffects() {
        val order = compareBy<TimelinePulse> { it.time }.thenBy { it.eventId }
        for ((handle, additions) in pendingEffects) {
            additions.sortWith(order)
            val obj = objects.getValue(handle)
            val curve = obj.curves.getValue(1)
            val combined = ArrayList<TimelinePulse>(curve.events.size + additions.size)
            var old = 0; var added = 0
            while (old < curve.events.size && added < additions.size) {
                if (order.compare(curve.events[old], additions[added]) <= 0) combined.add(curve.events[old++])
                else combined.add(additions[added++])
            }
            while (old < curve.events.size) combined.add(curve.events[old++])
            while (added < additions.size) combined.add(additions[added++])
            obj.curves[1] = curve.copy(eventHighWater = effectHighWater.getValue(handle), events = combined)
        }
        pendingEffects.clear()
    }

    private fun allocate(kind: TimelineObjectKind, at: Long, descriptor: TimelineRecord = TimelineRecord.EMPTY): Long {
        protocolRequire(objects.size < TimelineProtocol.MAX_OBJECTS, "Observation object capacity exceeded")
        highWater = increment(highWater)
        objects[highWater] = MutableObject(highWater, kind, at, descriptor)
        changed = true
        return highWater
    }

    private fun validateObservation(observation: TimelineObservation): Map<Long, TimelineObservedUnit> {
        protocolRequire(observation.time in maxOf(time, context.historyFrom)..TimelineProtocol.MAX_TIME, "Observation time regressed")
        protocolRequire(observation.creditsQ in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT, "Invalid observed credits")
        protocolRequire(observation.fog.size == cells && (observation.fog === lastFog ||
            (0 until cells).all { observation.fog[it].toInt() in 0..10 }), "Invalid observed fog")
        protocolRequire(observation.units.size <= TimelineProtocol.MAX_UNITS, "Invalid observed unit identities")
        val units = HashMap<Long, TimelineObservedUnit>(observation.units.size)
        observation.units.forEach { unit ->
            protocolRequire(units.put(unit.key, unit) == null, "Invalid observed unit identities")
            protocolRequire(unit.key > 0 && unit.position.x in 0 until widthQ && unit.position.y in 0 until heightQ &&
                unit.position.z in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT && unit.headingQ in 0..65535 &&
                unit.healthQ in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT && unit.maxHealthQ in 1..TimelineProtocol.MAX_SINT &&
                unit.progressQ in 0..65535 && unit.ownerTeam in -2..63 && unit.techLevel in 1..3, "Invalid observed unit")
            appearance(unit.appearanceKey)
        }
        status(observation.matchStatus, observation.time)
        protocolRequire(observation.selectedUnitKeys.size <= 256 && observation.selectedUnitKeys.all { it > 0 } &&
            observation.selectedUnitKeys.distinct().size == observation.selectedUnitKeys.size, "Invalid observed selection")
        protocolRequire(observation.actions.size <= 128 && observation.actions.map { it.key }.toSet().size == observation.actions.size, "Invalid observed actions")
        observation.actions.forEach { action ->
            protocolRequire(action.key > 0 && action.readiness in 0..2 && action.queuedCount in -1..65536 && action.stockpileCount in -1..65536 &&
                (action.progressQ == null || action.progressQ in 0..65535), "Invalid observed action")
            TimelineBinary.validateDescriptor(TimelineObjectKind.ACTION, TimelineRecord(listOf(
                TimelineField(2, true, TimelineTlvValue.U64(1)), TimelineField(3, true, TimelineTlvValue.U64(2)),
                TimelineField(4, true, TimelineTlvValue.Record(action.ability)))))
        }
        protocolRequire(observation.events.map { it.key }.toSet().size == observation.events.size, "Duplicate observed effect")
        observation.events.forEach { event ->
            val earliest = if (event.key in seenEffects) context.joinedAt else maxOf(context.joinedAt, store.current?.header?.sealedThrough ?: context.historyFrom)
            protocolRequire(event.key > 0 && event.time in earliest..observation.time &&
                (seenEffects[event.key] == null || seenEffects[event.key] == event.time) &&
                event.maxTailTime in 0..second * 10, "Invalid observed effect time")
            TimelineBinary.validateSchema(event.effect, mapOf(1 to TimelineFieldRule(2, true, true), 2 to TimelineFieldRule(6, true, true),
                3 to TimelineFieldRule(3, true, true), 4 to TimelineFieldRule(2, true, true), 5 to TimelineFieldRule(5, false, false)))
            protocolRequire((event.effect[1]!!.value as TimelineTlvValue.U32).value in 1..3 &&
                (event.effect[3]!!.value as TimelineTlvValue.U64).value <= event.maxTailTime, "Invalid observed effect")
            val position = TimelineBinary.Cursor((event.effect[2]!!.value as TimelineTlvValue.Bytes).value)
            protocolRequire(position.vsint() in 0 until widthQ && position.vsint() in 0 until heightQ, "Effect outside map")
            position.vsint(); position.finish()
            protocolRequire(TimelineBinary.recordSize(event.effect) <= 65536, "Effect payload exceeds BLOB limit")
        }
        return units
    }

    private fun appearance(key: String): TimelineValue.Blob {
        appearances[key]?.let { return it }
        val bytes = key.toByteArray(Charsets.UTF_8)
        protocolRequire(bytes.size in 1..128, "Invalid observed appearance")
        TimelineBinary.validateRecord(TimelineRecord(listOf(TimelineField(1, false, TimelineTlvValue.Text(key)))))
        val value = TimelineValue.Blob(TimelineBytes(bytes))
        if (appearances.size < 1024) appearances[key] = value
        return value
    }

    private fun status(bytes: TimelineBytes, observed: Long): Pair<Boolean, Boolean> {
        val cursor = TimelineBinary.Cursor(bytes)
        val phase = cursor.u8(); val finished = cursor.vuint()
        fun teams(): List<Long> {
            val count = cursor.vuint(); protocolRequire(count <= 64, "Invalid match team count")
            val result = ArrayList<Long>(); var last = -1L
            repeat(count.toInt()) { val team = cursor.vuint(); protocolRequire(team in 0..63 && team > last, "Invalid match teams"); result.add(team); last = team }
            return result
        }
        val winners = teams(); val defeated = teams(); cursor.finish()
        protocolRequire(phase in 0..1 && finished <= observed && (phase == 1 || (finished == 0L && winners.isEmpty())) && winners.none { it in defeated }, "Invalid observed match status")
        return (phase == 1) to (context.teamId.toLong() in defeated)
    }
    private fun ended(bytes: TimelineBytes): Boolean = status(bytes, time).let { it.first || it.second }
    private fun number(id: Int) = (context.mapRecord[id]!!.value as TimelineTlvValue.U32).value
    private fun constant(lo: Long, hi: Long, value: TimelineValue) = TimelineSegment.Interpolated(lo, hi, false, lo, Long.MAX_VALUE, value, value)
    private fun clippedPrefix(segments: List<TimelineSegment>, until: Long): List<TimelineSegment> = segments.filter { it.lo < until }
        .map { if (it.hi > until) it.clipped(it.lo, until) else it }
    private fun at(curve: TimelineCurve?, point: Long): TimelineValue? {
        if (curve == null) return null
        var low = 0; var high = curve.segments.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1); val segment = curve.segments[middle]
            if (point < segment.lo) high = middle - 1 else if (point >= segment.hi) low = middle + 1
            else return TimelineBinary.sample(curve.mode, curve.valueType, segment, point)
        }
        return null
    }
    private fun selection(handle: Long?): TimelineBytes = selection(handle?.let { listOf(it) } ?: emptyList())
    private fun selection(handles: List<Long>): TimelineBytes = ByteArrayOutputStream().let { out ->
        TimelineBinary.writeVUInt(out, handles.size.toLong()); handles.forEach { TimelineBinary.writeVUInt(out, it) }; TimelineBytes(out.toByteArray()) }
    private fun near(mode: TimelineMode, a: TimelineValue, b: TimelineValue): Boolean = when (a) {
        is TimelineValue.Vec3 -> b is TimelineValue.Vec3 && kotlin.math.abs(a.x - b.x) <= 64 && kotlin.math.abs(a.y - b.y) <= 64 && kotlin.math.abs(a.z - b.z) <= 64
        is TimelineValue.UInt -> b is TimelineValue.UInt && if (mode == TimelineMode.ANGLE) kotlin.math.abs(angleDelta(a.value, b.value)) <= 182 else kotlin.math.abs(a.value - b.value) <= 1
        else -> a == b
    }
    private fun angleDelta(a: Long, b: Long): Long = Math.floorMod(b - a, 65536L).let { if (it > 32768) it - 65536 else it }
    private fun rounded(n: BigInteger, d: BigInteger): BigInteger {
        val qr = n.abs().divideAndRemainder(d)
        val magnitude = qr[0] + if (qr[1].shiftLeft(1) >= d) BigInteger.ONE else BigInteger.ZERO
        return if (n.signum() < 0) -magnitude else magnitude
    }
    private fun increment(value: Long): Long { protocolRequire(value < Long.MAX_VALUE, "Timeline identity exhausted"); return value + 1 }
    private fun encodeFog(values: ByteArray): TimelineBytes {
        val raw = byteArrayOf(0) + values
        val runs = ArrayList<Pair<Int, Byte>>()
        var at = 0
        while (at < values.size) { var end = at + 1; while (end < values.size && values[end] == values[at]) end++; runs.add((end - at) to values[at]); at = end }
        val encoded = ByteArrayOutputStream().let { out -> out.write(1); TimelineBinary.writeVUInt(out, runs.size.toLong());
            runs.forEach { (length, value) -> TimelineBinary.writeVUInt(out, length.toLong()); out.write(value.toInt()) }; out.toByteArray() }
        return TimelineBytes(if (encoded.size < raw.size) encoded else raw)
    }
    private fun varSize(value: Long): Long { var n = value; var size = 1L; while (n >= 128) { n = n ushr 7; size++ }; return size }
    private fun compareBytes(a: TimelineBytes, b: TimelineBytes): Int {
        for (i in 0 until minOf(a.size, b.size)) { val compare = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255); if (compare != 0) return compare }
        return a.size.compareTo(b.size)
    }
    private val unitOrder = compareBy<TimelineObservedUnit> { it.appearanceKey }.thenBy { it.ownerTeam }
        .thenBy { it.position.x }.thenBy { it.position.y }.thenBy { it.position.z }.thenBy { it.headingQ }
        .thenBy { it.healthQ }.thenBy { it.maxHealthQ }.thenBy { it.progressQ }.thenBy { it.techLevel }.thenBy { it.controllable }
    private val actionOrder = Comparator<TimelineObservedAction> { a, b -> compareBytes(TimelineBinary.encodeRecord(a.ability), TimelineBinary.encodeRecord(b.ability)) }
        .thenBy { it.readiness }.thenBy { it.queuedCount }.thenBy { it.stockpileCount }.thenBy { it.progressQ }.thenBy { it.canCancel }
}
