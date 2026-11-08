package io.github.rwx.net.timeline

/**
 * Synchronous native hook boundary. It is disabled until an authoritative owner explicitly
 * installs a journal; a disabled hook does not read engine state or validate its arguments.
 */
object TimelineNativeEvents {
    internal fun interface Listener {
        fun record(kind: TimelineEngineEventKind, subject: Any?, x: Float, y: Float, z: Float)
    }

    private class Binding(val owner: Thread, val listener: Listener)
    private val lock = Any()
    @Volatile private var binding: Binding? = null

    /** Public presentation durations are fixed policy, not native graphics-quality settings. */
    fun install(journal: TimelineEngineEvents, ticksPerSecond: Int = journal.ticksPerSecond): AutoCloseable {
        journal.checkOwnerThread()
        require(ticksPerSecond == journal.ticksPerSecond)
        val second = ticksPerSecond.toLong() * TimelineProtocol.SUBDIVISIONS
        return installListener { kind, subject, x, y, z ->
            val duration = when (kind) {
                TimelineEngineEventKind.FIRE -> maxOf(1L, second / 10)
                TimelineEngineEventKind.HIT -> maxOf(1L, second / 5)
                TimelineEngineEventKind.EXPLOSION -> second
            }
            try {
                if (subject == null) journal.recordWorld(kind, x, y, z, duration)
                else journal.recordSubjectWorld(subject, kind, x, y, z, duration)
            } catch (_: ProtocolException) {
                // A malformed native fact fails presentation delivery, never the actual
                // projectile/damage/death operation that called this optional observer.
                journal.failNativeEvent()
            }
        }
    }

    internal fun installListener(listener: Listener): AutoCloseable {
        val own = Binding(Thread.currentThread(), listener)
        synchronized(lock) {
            check(binding == null) { "A native timeline event listener is already installed" }
            binding = own
        }
        return AutoCloseable {
            check(Thread.currentThread() === own.owner) { "Native event listener must close on its owner thread" }
            synchronized(lock) {
                // Closing a previous generation twice must never detach its successor.
                if (binding === own) binding = null
            }
        }
    }

    @JvmStatic fun fire(subject: Any?, x: Float, y: Float, z: Float) {
        val active = binding ?: return
        if (subject == null) return
        emit(active, TimelineEngineEventKind.FIRE, subject, x, y, z)
    }
    @JvmStatic fun hit(x: Float, y: Float, z: Float) {
        val active = binding ?: return
        emit(active, TimelineEngineEventKind.HIT, null, x, y, z)
    }
    @JvmStatic fun explosion(x: Float, y: Float, z: Float) {
        val active = binding ?: return
        emit(active, TimelineEngineEventKind.EXPLOSION, null, x, y, z)
    }
    @JvmStatic fun unitDeath(subject: Any?, x: Float, y: Float, z: Float) {
        val active = binding ?: return
        if (subject == null) return
        emit(active, TimelineEngineEventKind.EXPLOSION, subject, x, y, z)
    }

    private fun emit(active: Binding, kind: TimelineEngineEventKind, subject: Any?, x: Float, y: Float, z: Float) {
        check(Thread.currentThread() === active.owner) { "Native events require the simulation owner thread" }
        active.listener.record(kind, subject, x, y, z)
    }
}
