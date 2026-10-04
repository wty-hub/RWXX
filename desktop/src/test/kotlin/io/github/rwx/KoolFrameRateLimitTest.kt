package io.github.rwx

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.WindowFlags
import de.fabmax.kool.pipeline.backend.RenderBackendJvm
import de.fabmax.kool.platform.KoolWindowJvm
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.platform.WindowSubsystem
import java.lang.reflect.Proxy
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Exercises the patched Kool limiter with a window stub, without opening a graphics context. */
class KoolFrameRateLimitTest {
    @Test
    fun `high refresh wait yields most of its CPU budget`() {
        val clock = ManagementFactory.getThreadMXBean()
        if (!clock.isCurrentThreadCpuTimeSupported || !clock.isThreadCpuTimeEnabled) return
        val context = testContext()
        KoolDesktopMain.syncDesktopFrameRateLimit(context, 300)
        val limit = Lwjgl3Context::class.java.getDeclaredMethod("checkFrameRateLimits").apply { isAccessible = true }
        val start = System.nanoTime()
        val cpuStart = clock.currentThreadCpuTime
        repeat(60) { limit.invoke(context) }
        val cpu = clock.currentThreadCpuTime - cpuStart
        val elapsed = System.nanoTime() - start
        assertTrue(cpu < elapsed * .6, "Limiter used ${cpu / 1e6} ms CPU during ${elapsed / 1e6} ms wait")
    }

    @Test
    fun `every frame waits for the configured interval rather than alternating with an uncapped frame`() {
        val context = testContext()
        val checkLimit = Lwjgl3Context::class.java.getDeclaredMethod("checkFrameRateLimits").apply { isAccessible = true }
        for (fps in listOf(30, 60, 120)) {
            KoolDesktopMain.syncDesktopFrameRateLimit(context, fps)
            val completions = List(6) {
                checkLimit.invoke(context)
                System.nanoTime()
            }
            val shortestInterval = completions.zipWithNext { before, after -> after - before }.min()
            assertTrue(
                shortestInterval >= 1_000_000_000L / fps * 0.85,
                "At $fps FPS, an interval was only ${shortestInterval / 1_000_000.0} ms",
            )
        }
    }

    private fun testContext(): Lwjgl3Context {
        val window = stub<KoolWindowJvm> { method ->
            when (method) {
                "getFlags" -> WindowFlags(isFocused = true)
                "isMouseOverWindow" -> false
                "pollEvents" -> Unit
                else -> error("Unexpected window call: $method")
            }
        }
        val subsystem = stub<WindowSubsystem> { method ->
            when (method) {
                "isCloseRequested" -> false
                else -> error("Unexpected subsystem call: $method")
            }
        }
        val config = KoolConfigJvm(windowSubsystem = subsystem, maxFrameRate = 30)
        val configField = KoolSystem::class.java.getDeclaredField("initConfig").apply { isAccessible = true }
        val initializedField = KoolSystem::class.java.getDeclaredField("isInitialized").apply { isAccessible = true }
        val previousConfig = configField.get(null)
        val previousInitialized = initializedField.getBoolean(null)
        val context = try {
            if (!previousInitialized) KoolSystem.initialize(config)
            Lwjgl3Context::class.java.getDeclaredConstructor(KoolConfigJvm::class.java).run {
                isAccessible = true
                newInstance(config)
            }
        } finally {
            configField.set(null, previousConfig)
            initializedField.setBoolean(null, previousInitialized)
        }
        val backend = stub<RenderBackendJvm> { method ->
            when (method) {
                "getWindow" -> window
                else -> error("Unexpected backend call: $method")
            }
        }
        Lwjgl3Context::class.java.getDeclaredField("backend").apply { isAccessible = true }.set(context, backend)
        return context
    }

    private inline fun <reified T> stub(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            call(method.name)
        } as T
}
