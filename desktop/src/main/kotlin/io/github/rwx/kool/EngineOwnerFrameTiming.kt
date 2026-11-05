package io.github.rwx.kool

/** Exact monotonic endpoints, visible only inside the current owner's realtime tick callback. */
internal object EngineOwnerFrameTiming {
    internal class Interval {
        var startNanos: Long = 0L
            private set
        var endNanos: Long = 0L
            private set
        var active: Boolean = false
            private set

        fun begin(startNanos: Long, endNanos: Long) {
            this.startNanos = startNanos
            this.endNanos = endNanos
            active = true
        }

        fun clear() {
            active = false
            startNanos = 0L
            endNanos = 0L
        }
    }

    private val ownerInterval = ThreadLocal<Interval>()

    fun current(): Interval? = ownerInterval.get()?.takeIf { it.active }

    /** One object and one ThreadLocal entry per worker, reused across callbacks. */
    fun attachOwner(): Interval {
        check(ownerInterval.get() == null) { "Owner timing is already attached" }
        return Interval().also { ownerInterval.set(it) }
    }

    fun detachOwner(interval: Interval) {
        check(ownerInterval.get() === interval) { "Owner timing belongs to a different worker" }
        interval.clear()
        ownerInterval.remove()
    }
}
