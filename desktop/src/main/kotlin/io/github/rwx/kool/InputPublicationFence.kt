package io.github.rwx.kool

import java.util.concurrent.atomic.AtomicLong

/** Tickets mark normal input; only the owner advances adoption and completed publication. */
internal class InputPublicationFence {
    private val requested = AtomicLong()
    private var adopted = 0L
    private val outOfOrder = HashSet<Long>()
    @Volatile private var published = 0L

    fun request(): Long = requested.incrementAndGet()
    fun currentRequest(): Long = requested.get()
    fun adopted(ticket: Long) {
        if (ticket <= adopted) return
        if (ticket != adopted + 1) { outOfOrder.add(ticket); return }
        adopted = ticket
        while (outOfOrder.remove(adopted + 1)) adopted++
    }
    fun published() { published = adopted }
    fun isPublished(ticket: Long): Boolean = published >= ticket
}
