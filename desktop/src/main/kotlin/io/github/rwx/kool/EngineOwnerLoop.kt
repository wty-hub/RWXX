package io.github.rwx.kool

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import io.github.rwx.desktopScheduledFrameStartNanos

/** One owner for the legacy engine; presentation never supplies its clock or waits for it. */
internal class EngineOwnerLoop(
    private val periodNanos: () -> Long,
    private val tick: (Float) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val legacyPacing: Boolean = System.getenv("RWX_LEGACY_OWNER_PACING") == "1",
    private val legacyShortOwnerPark: Boolean = System.getenv("RWX_GUARD_SHORT_OWNER_PARK") != "1" ||
        System.getenv("RWX_LEGACY_SHORT_OWNER_PARK") == "1",
    private val stableFrameDeadlines: Boolean = System.getenv("RWX_STABLE_FRAME_DEADLINES") == "1",
) : AutoCloseable {
    private data class InputTransition(val identity: String, val down: Boolean)
    private data class Task(val transition: InputTransition?, val execute: () -> Unit)
    private val tasks = ConcurrentLinkedQueue<Task>()
    private val running = AtomicBoolean(true)
    private val queueGate = Any()
    private var resetClock = true
    private val thread = Thread(::run, "RWX-engine-owner").apply { isDaemon = true; start() }
    val isOwner: Boolean get() = Thread.currentThread() === thread

    fun resetClock() { check(isOwner); resetClock = true }

    fun <T> submit(action: () -> T): CompletableFuture<T> {
        return enqueue(null, action)
    }

    /** A down/up pair must each be observed by a legacy loop; FIFO execution alone is insufficient. */
    fun <T> submitInput(identity: String, down: Boolean, action: () -> T): CompletableFuture<T> =
        enqueue(InputTransition(identity, down), action)

    private fun <T> enqueue(transition: InputTransition?, action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val inline = isOwner
        synchronized(queueGate) {
            if (!running.get()) return future.apply { completeExceptionally(IllegalStateException("Engine is closed")) }
            if (!inline) {
                tasks.add(Task(transition) { complete(future, action) })
                LockSupport.unpark(thread)
            }
        }
        if (inline) complete(future, action)
        return future
    }

    fun <T> call(action: () -> T): T = if (isOwner) action() else submit(action).get()

    private fun <T> complete(future: CompletableFuture<T>, action: () -> T) {
        try { future.complete(action()) } catch (error: Throwable) { future.completeExceptionally(error) }
    }

    private fun run() {
        if (System.getenv("RWX_FRAME_METRICS") != null || System.getenv("RWX_ENGINE_FRAME_TRACE") != null) {
            println("RWXOwnerPacing hybrid=${!legacyPacing} spinBudgetNanos=$SPIN_BUDGET_NANOS maxHybridPeriodNanos=$MAX_HYBRID_PERIOD_NANOS " +
                "shortParkGuard=${!legacyPacing && !legacyShortOwnerPark} minCoarseParkNanos=$MIN_COARSE_PARK_NANOS " +
                "stableFrameDeadlines=$stableFrameDeadlines")
        }
        var previous = System.nanoTime()
        var deadline = previous
        var pacingPeriod = Long.MAX_VALUE
        val inputStates = mutableMapOf<String, Boolean>()
        val transitionsObserved = mutableSetOf<String>()
        while (running.get() || tasks.isNotEmpty()) {
            while (true) {
                val next = tasks.peek() ?: break
                val transition = next.transition
                if (running.get() && transition != null && inputStates[transition.identity] != transition.down &&
                    transition.identity in transitionsObserved) break
                tasks.poll()
                if (transition != null) {
                    inputStates[transition.identity] = transition.down
                    transitionsObserved += transition.identity
                }
                next.execute()
            }
            if (!running.get()) break
            val now = System.nanoTime()
            if (resetClock) { previous = now; deadline = now; resetClock = false }
            if (now < deadline) {
                val remaining = deadline - now
                if (!legacyPacing && pacingPeriod <= MAX_HYBRID_PERIOD_NANOS) {
                    // Return to the outer loop after every wait so input, reset and close remain responsive.
                    val coarsePark = remaining - SPIN_BUDGET_NANOS
                    // A tiny coarse request can still oversleep by a Windows timer tick.
                    if (coarsePark <= 0L || !legacyShortOwnerPark && coarsePark < MIN_COARSE_PARK_NANOS) Thread.onSpinWait()
                    else LockSupport.parkNanos(this, coarsePark)
                } else LockSupport.parkNanos(this, remaining)
                continue
            }
            val delta = ((now - previous).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
            previous = now
            try { tick(delta) } catch (error: Throwable) { onFailure(error) }
            transitionsObserved.clear()
            // This is the original outer throttle, not a fixed simulation accumulator.
            val previousPeriod = pacingPeriod
            pacingPeriod = periodNanos().coerceAtLeast(1L)
            val pacingStart = if (stableFrameDeadlines && previousPeriod == pacingPeriod)
                desktopScheduledFrameStartNanos(deadline, now, pacingPeriod) else now
            deadline = pacingStart + pacingPeriod
        }
    }

    override fun close() {
        synchronized(queueGate) { running.set(false) }
        LockSupport.unpark(thread)
        if (!isOwner) thread.join(5000)
    }

    private companion object {
        const val SPIN_BUDGET_NANOS = 1_500_000L
        const val MIN_COARSE_PARK_NANOS = 1_500_000L
        const val MAX_HYBRID_PERIOD_NANOS = 3_333_333L
    }
}
