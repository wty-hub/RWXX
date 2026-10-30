package io.github.rwx.diagnostics

import java.nio.ByteBuffer
import java.util.function.LongSupplier
import kotlin.test.*

class RuntimeDiagnosticsTest {
    @Test fun `missing desktop APIs are lazy cached and explicitly unavailable`() {
        var attempts = 0
        val loader = object : ClassLoader(RuntimeDiagnostics::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("java.lang.management.")) {
                    attempts++
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }
        val diagnostics = RuntimeDiagnostics(loader)
        assertEquals(0, attempts, "Constructing a disabled diagnostic bridge must do no discovery")
        assertNull(diagnostics.cpuClock())
        assertEquals(-1L, RuntimeDiagnostics.cpuTime(diagnostics.cpuClock()))
        assertEquals(1, attempts, "An unavailable clock must not retry on every draw")
        assertNull(diagnostics.bufferPools())
        val afterDiscovery = attempts
        repeat(3) {
            assertNull(diagnostics.cpuClock())
            assertNull(diagnostics.bufferPools())
        }
        assertEquals(afterDiscovery, attempts)
    }

    @Test fun `CPU errors and negative values never turn into zero work`() {
        assertEquals(-1L, RuntimeDiagnostics.cpuTime(null))
        assertEquals(-1L, RuntimeDiagnostics.cpuTime(LongSupplier { -19L }))
        assertEquals(-1L, RuntimeDiagnostics.cpuTime(LongSupplier { throw UnsupportedOperationException() }))
        assertEquals(-1L, RuntimeDiagnostics.cpuTime(LongSupplier { throw NoClassDefFoundError("management") }))
        assertEquals(0L, RuntimeDiagnostics.cpuTime(LongSupplier { 0L }))
        assertEquals(123L, RuntimeDiagnostics.cpuTime(LongSupplier { 123L }))
    }

    @Test fun `desktop public management interfaces provide CPU and live buffer snapshots`() {
        val diagnostics = RuntimeDiagnostics(RuntimeDiagnostics::class.java.classLoader)
        val clock = assertNotNull(diagnostics.cpuClock())
        val start = RuntimeDiagnostics.cpuTime(clock)
        val end = RuntimeDiagnostics.cpuTime(clock)
        assertTrue(start >= 0L)
        assertTrue(end >= start)
        val before = assertNotNull(diagnostics.bufferPools()).first { it.name == "direct" }
        val allocation = ByteBuffer.allocateDirect(4096)
        allocation.put(0, 7.toByte())
        val after = assertNotNull(diagnostics.bufferPools()).first { it.name == "direct" }
        assertTrue(before.memoryUsed >= 0L)
        assertTrue(after.memoryUsed >= 4096L)
        assertEquals(7.toByte(), allocation.get(0)) // Keep the buffer reachable through the second sample.
    }

    @Test fun `bridge signatures expose only portable core types`() {
        val types = RuntimeDiagnostics::class.java.declaredMethods.flatMap {
            it.parameterTypes.toList() + it.returnType
        } + RuntimeDiagnostics::class.java.declaredFields.map { it.type }
        assertTrue(types.none { it.name.startsWith("java.lang.management.") })
    }
}
