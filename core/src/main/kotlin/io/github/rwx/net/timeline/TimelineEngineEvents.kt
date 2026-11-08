package io.github.rwx.net.timeline

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Presentation facts, never native effect-manager requests or entity references. */
enum class TimelineEngineEventKind(val code: Long) { FIRE(1), HIT(2), EXPLOSION(3) }

enum class TimelineEngineEventFailure { CAPACITY_EXCEEDED, IDENTITY_EXHAUSTED, INVALID_NATIVE_EVENT }

/**
 * Owner-thread journal for a single authoritative simulation. Creating this object does not
 * install a native listener. Legacy modes need no journal and must leave that listener null.
 *
 * A step grants only its pre-step, immutable fog for the whole interval [start, end). Facts
 * are stamped end-1; this is an observation bucket, not an ordering of native callbacks.
 * Revealing a tile at the end of the step never authorizes earlier facts retroactively.
 */
class TimelineEngineEvents(
    private val mapWidth: Int,
    private val mapHeight: Int,
    tileWidth: Int,
    tileHeight: Int,
    val ticksPerSecond: Int,
    private val capacityPerGrant: Int = 65_536,
    private val maxGrants: Int = 64,
) {
    private val owner = Thread.currentThread()
    private val tileWidthQ = tileWidth.toLong() * 256
    private val tileHeightQ = tileHeight.toLong() * 256
    private val widthQ = mapWidth * tileWidthQ
    private val heightQ = mapHeight * tileHeightQ
    private val cells = mapWidth * mapHeight
    private val maximumDuration = ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS * 10
    private val grants = LinkedHashSet<Grant>()
    private data class Step(val start: Long, val end: Long)
    private var step: Step? = null
    private var completedThrough = 0L
    @Volatile var failure: TimelineEngineEventFailure? = null
        private set

    init {
        require(mapWidth in 1..1024 && mapHeight in 1..1024 && tileWidth in 1..4096 && tileHeight in 1..4096)
        require(ticksPerSecond in 1..1000 && capacityPerGrant in 1..65_536 && maxGrants in 1..64)
    }

    /** This capability belongs to one authorization generation, not a client-provided seat ID. */
    inner class Grant internal constructor(val joinedAt: Long) : AutoCloseable {
        private val queue = ArrayDeque<TimelineObservedEvent>()
        private var scopeFog: TimelineBytes? = null
        private var scopeSubjects: Set<Any> = emptySet()
        private var validatedFog: TimelineBytes? = null
        private var nextIdentity = 0L
        private var closed = false
        @Volatile var failure: TimelineEngineEventFailure? = null
            private set

        /** Call before advancing the engine. An absent scope means no authorization. */
        fun authorizeStep(fog: TimelineBytes, authorizedSubjects: Collection<Any> = emptyList()) {
            checkOwner()
            check(!closed && this in grants) { "Event authorization generation is closed" }
            check(failure == null) { "Event journal has failed" }
            val active = checkNotNull(step) { "Begin the simulation step before granting event sight" }
            check(scopeFog == null) { "Event sight is already fixed for this step" }
            protocolRequire(joinedAt <= active.start, "Event grant starts after the step boundary")
            protocolRequire(fog.size == cells && (fog === validatedFog || (0 until cells).all { fog[it].toInt() in 0..10 }),
                "Invalid event sight")
            protocolRequire(authorizedSubjects.size <= TimelineProtocol.MAX_UNITS, "Too many event subjects")
            val identities = if (authorizedSubjects.isEmpty()) emptySet() else
                Collections.newSetFromMap(IdentityHashMap<Any, Boolean>(authorizedSubjects.size)).also { it.addAll(authorizedSubjects) }
            validatedFog = fog
            scopeSubjects = identities
            scopeFog = fog
        }

        /** Only detached facts cross to a publisher. A failed batch is never marked complete. */
        fun drain(): List<TimelineObservedEvent> {
            checkOwner()
            check(!closed && this in grants) { "Event authorization generation is closed" }
            check(step == null) { "Finish the step before draining event facts" }
            check(failure == null) { "Event journal has failed: $failure" }
            if (queue.isEmpty()) return emptyList()
            val result = frozenList(queue)
            queue.clear()
            return result
        }

        override fun close() {
            checkOwner()
            if (closed) return
            closed = true
            queue.clear()
            clearScope()
            validatedFog = null
            grants.remove(this)
        }

        internal fun clearScope() { scopeFog = null; scopeSubjects = emptySet() }
        internal fun failNativeEvent() { if (failure == null) fail(TimelineEngineEventFailure.INVALID_NATIVE_EVENT) }

        internal fun append(kind: TimelineEngineEventKind, position: TimelineValue.Vec3, duration: Long,
                            stamp: Long, originalTile: Int, subject: Any? = null) {
            val fog = scopeFog ?: return
            if (closed || failure != null) return
            if (subject != null && subject !in scopeSubjects) return
            val projectedTile = tile(position)
            // Quantization may cross a tile boundary: neither original nor projected point
            // may gain authorization merely from rounding onto the other side.
            if (fog[originalTile].toInt() >= 5 || fog[projectedTile].toInt() >= 5) return
            if (queue.size >= capacityPerGrant) { fail(TimelineEngineEventFailure.CAPACITY_EXCEEDED); return }
            if (nextIdentity == Long.MAX_VALUE) { fail(TimelineEngineEventFailure.IDENTITY_EXHAUSTED); return }
            val identity = ++nextIdentity
            val coordinateBytes = ByteArrayOutputStream().let { out ->
                TimelineBinary.writeVSInt(out, position.x)
                TimelineBinary.writeVSInt(out, position.y)
                TimelineBinary.writeVSInt(out, position.z)
                TimelineBytes(out.toByteArray())
            }
            val effect = TimelineRecord(listOf(
                TimelineField(1, true, TimelineTlvValue.U32(kind.code)),
                TimelineField(2, true, TimelineTlvValue.Bytes(coordinateBytes)),
                TimelineField(3, true, TimelineTlvValue.U64(duration)),
                TimelineField(4, true, TimelineTlvValue.U32(seed(kind, stamp, position, duration))),
            ))
            queue.addLast(TimelineObservedEvent(identity, stamp, effect, duration))
        }

        private fun fail(reason: TimelineEngineEventFailure) {
            failure = reason
            queue.clear()
            clearScope()
        }
    }

    fun openGrant(joinedAt: Long): Grant {
        checkOwner()
        check(failure == null) { "Native event journal has failed" }
        check(step == null) { "Attach event grants only between simulation steps" }
        protocolRequire(joinedAt in 0..TimelineProtocol.MAX_TIME, "Invalid event join boundary")
        check(grants.size < maxGrants) { "Event grant capacity exceeded" }
        return Grant(joinedAt).also { grants.add(it) }
    }

    fun beginStep(start: Long, end: Long) {
        checkOwner()
        check(step == null) { "An event step is already open" }
        protocolRequire(start in completedThrough..TimelineProtocol.MAX_TIME && end in 1..TimelineProtocol.MAX_TIME && end > start,
            "Invalid event step boundaries")
        grants.forEach { it.clearScope() }
        step = Step(start, end)
    }

    fun endStep() {
        checkOwner()
        val finished = checkNotNull(step) { "No event step is open" }
        completedThrough = finished.end
        step = null
        grants.forEach { it.clearScope() }
    }

    /** Pure quantized entry point for already detached owner-thread facts. */
    fun record(kind: TimelineEngineEventKind, position: TimelineValue.Vec3, duration: Long) {
        checkOwner()
        val active = step ?: return // Loading, teardown and unscoped engine work cannot disclose facts.
        validate(position, duration)
        val originalTile = tile(position)
        grants.forEach { it.append(kind, position, duration, active.end - 1, originalTile) }
    }

    /** Native callbacks supply position only; projectile/unit IDs and native random state stay out. */
    fun recordWorld(kind: TimelineEngineEventKind, x: Float, y: Float, z: Float, duration: Long) {
        recordWorld(null, kind, x, y, z, duration)
    }

    /** The subject is only tested against frozen identity membership, never retained in a fact. */
    fun recordSubjectWorld(subject: Any, kind: TimelineEngineEventKind, x: Float, y: Float, z: Float, duration: Long) {
        recordWorld(subject, kind, x, y, z, duration)
    }

    private fun recordWorld(subject: Any?, kind: TimelineEngineEventKind, x: Float, y: Float, z: Float, duration: Long) {
        checkOwner()
        if (failure != null) return
        val active = step ?: return
        protocolRequire(x.isFinite() && y.isFinite() && z.isFinite(), "Invalid native event position")
        // A muzzle can extend past a map edge. Such a point has no authorized map cell;
        // omitting it must not interrupt the engine's legitimate weapon/death operation.
        if (x.toDouble() < 0 || x.toDouble() >= widthQ.toDouble() / 256 ||
            y.toDouble() < 0 || y.toDouble() >= heightQ.toDouble() / 256) return
        val position = TimelineValue.Vec3(quantize(x).coerceAtMost(widthQ - 1), quantize(y).coerceAtMost(heightQ - 1), quantize(z))
        validate(position, duration)
        val originalTile = (y.toDouble() * 256 / tileHeightQ).toInt() * mapWidth + (x.toDouble() * 256 / tileWidthQ).toInt()
        grants.forEach { it.append(kind, position, duration, active.end - 1, originalTile, subject) }
    }

    private fun validate(position: TimelineValue.Vec3, duration: Long) {
        protocolRequire(position.x in 0 until widthQ && position.y in 0 until heightQ &&
            position.z in TimelineProtocol.MIN_SINT..TimelineProtocol.MAX_SINT && duration in 0..maximumDuration,
            "Invalid event fact")
    }

    private fun tile(position: TimelineValue.Vec3) = (position.y / tileHeightQ).toInt() * mapWidth + (position.x / tileWidthQ).toInt()
    internal fun checkOwnerThread() = checkOwner()
    internal fun failNativeEvent() {
        checkOwner()
        failure = TimelineEngineEventFailure.INVALID_NATIVE_EVENT
        grants.forEach { it.failNativeEvent() }
    }
    private fun checkOwner() = check(Thread.currentThread() === owner) { "Engine events require the simulation owner thread" }
    private fun quantize(value: Float): Long = try {
        BigDecimal.valueOf(value.toDouble()).multiply(BigDecimal.valueOf(256)).setScale(0, RoundingMode.HALF_UP).longValueExact()
    } catch (_: ArithmeticException) { throw ProtocolException("Event position cannot be quantized") }

    /** No global RNG, source ID or callback order can influence the presentation seed. */
    private fun seed(kind: TimelineEngineEventKind, time: Long, p: TimelineValue.Vec3, duration: Long): Long {
        fun mix(input: Long): Long {
            var value = input
            value = (value xor (value ushr 30)) * -4658895280553007687L
            value = (value xor (value ushr 27)) * -7723592293110705685L
            return value xor (value ushr 31)
        }
        var value = mix(kind.code)
        value = mix(value xor time)
        value = mix(value xor p.x)
        value = mix(value xor p.y)
        value = mix(value xor p.z)
        return mix(value xor duration) and TimelineProtocol.MAX_U32
    }
}
