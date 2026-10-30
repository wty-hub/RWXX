package io.github.rwx.diagnostics;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/** Optional JVM diagnostics, with no static dependency on desktop-only management APIs. */
public final class RuntimeDiagnostics {
    private static final RuntimeDiagnostics SYSTEM = new RuntimeDiagnostics(RuntimeDiagnostics.class.getClassLoader());
    private final ClassLoader loader;
    private boolean cpuInitialized;
    private LongSupplier cpu;
    private boolean buffersInitialized;
    private List<?> bufferBeans;
    private Method bufferName;
    private Method bufferMemory;

    /** Construction does not load or enumerate any platform diagnostic classes. */
    public RuntimeDiagnostics(ClassLoader loader) { this.loader = loader; }

    public static LongSupplier currentThreadCpuClock() { return SYSTEM.cpuClock(); }

    /** Null means that this runtime cannot supply buffer-pool measurements. */
    public static List<BufferPoolSnapshot> bufferPoolSnapshot() { return SYSTEM.bufferPools(); }

    /** Missing/failed/negative measurements remain unavailable; zero is a valid CPU measurement. */
    public static long cpuTime(LongSupplier clock) {
        if (clock == null) return -1;
        try { return Math.max(-1, clock.getAsLong()); }
        catch (RuntimeException | LinkageError ignored) { return -1; }
    }

    public synchronized LongSupplier cpuClock() {
        if (cpuInitialized) return cpu;
        cpuInitialized = true;
        try {
            Class<?> factory = platformClass("java.lang.management.ManagementFactory");
            Class<?> api = platformClass("java.lang.management.ThreadMXBean");
            Object bean = factory.getMethod("getThreadMXBean").invoke(null);
            if (!Boolean.TRUE.equals(api.getMethod("isCurrentThreadCpuTimeSupported").invoke(bean)) ||
                    !Boolean.TRUE.equals(api.getMethod("isThreadCpuTimeEnabled").invoke(bean))) return null;
            // Reflect on the exported public interface, never a JDK-private implementation class.
            Method read = api.getMethod("getCurrentThreadCpuTime");
            cpu = () -> {
                try { return Math.max(-1, ((Number) read.invoke(bean)).longValue()); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return -1; }
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            cpu = null;
        }
        return cpu;
    }

    public synchronized List<BufferPoolSnapshot> bufferPools() {
        if (!buffersInitialized) {
            buffersInitialized = true;
            try {
                Class<?> factory = platformClass("java.lang.management.ManagementFactory");
                Class<?> api = platformClass("java.lang.management.BufferPoolMXBean");
                bufferName = api.getMethod("getName");
                bufferMemory = api.getMethod("getMemoryUsed");
                bufferBeans = (List<?>) factory.getMethod("getPlatformMXBeans", Class.class).invoke(null, api);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                bufferBeans = null;
            }
        }
        if (bufferBeans == null) return null;
        try {
            List<BufferPoolSnapshot> result = new ArrayList<>(bufferBeans.size());
            for (Object bean : bufferBeans) result.add(new BufferPoolSnapshot(
                    (String) bufferName.invoke(bean), ((Number) bufferMemory.invoke(bean)).longValue()));
            return Collections.unmodifiableList(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private Class<?> platformClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    /** Plain core-owned data, also usable on runtimes without java.lang.management. */
    public static final class BufferPoolSnapshot {
        private final String name;
        private final long memoryUsed;
        public BufferPoolSnapshot(String name, long memoryUsed) { this.name = name; this.memoryUsed = memoryUsed; }
        public String getName() { return name; }
        /** Negative means the runtime did not provide a memory usage value. */
        public long getMemoryUsed() { return memoryUsed; }
    }
}
