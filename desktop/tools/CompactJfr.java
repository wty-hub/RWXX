import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.io.BufferedWriter;
import jdk.jfr.consumer.*;

/** Stream diagnostic events without expanding the same JFR metadata for every stack frame. */
class CompactJfr {
    private static final Set<String> EVENTS = Set.of("jdk.ExecutionSample", "jdk.NativeMethodSample",
        "jdk.GarbageCollection", "jdk.GCPhasePause", "jdk.ThreadPark", "jdk.ThreadSleep",
        "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.FileWrite", "jdk.FileRead", "jdk.SafepointBegin",
        "jdk.SystemGC", "jdk.DirectBufferStatistics", "jdk.ObjectAllocationSample", "rwx.ClockSync",
        // Per-thread CPU is the load-independent metric: wall clock on a busy desktop measures the
        // machine, not the build, and this session measured the same jar swinging between 66 and 231
        // presents per second.
        "jdk.ThreadCPULoad", "jdk.CPULoad");
    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
    public static void main(String[] args) throws Exception {
        long count = 0;
        try (RecordingFile input = new RecordingFile(Path.of(args[0]));
             BufferedWriter output = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            while (input.hasMoreEvents()) {
                RecordedEvent event = input.readEvent();
                String type = event.getEventType().getName();
                if (!EVENTS.contains(type)) continue;
                RecordedThread thread = event.hasField("sampledThread") ? event.getThread("sampledThread")
                    : event.hasField("eventThread") ? event.getThread("eventThread") : null;
                var instant = event.getStartTime();
                long start = instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
                StringBuilder row = new StringBuilder("{\"type\":").append(quote(type))
                    .append(",\"start\":").append(start).append(",\"duration\":").append(event.getDuration().toNanos())
                    .append(",\"thread\":").append(quote(thread == null ? null : thread.getJavaName()))
                    .append(",\"stack\":[");
                RecordedStackTrace stack = event.hasField("stackTrace") ? event.getStackTrace() : null;
                if (stack != null) {
                    boolean first = true;
                    for (RecordedFrame frame : stack.getFrames()) {
                        if (!first) row.append(',');
                        first = false;
                        RecordedMethod method = frame.getMethod();
                        row.append(quote(method.getType().getName() + "." + method.getName()));
                    }
                }
                if (type.equals("jdk.GarbageCollection")) {
                    row.append("],\"name\":").append(quote(event.getString("name")))
                       .append(",\"cause\":").append(quote(event.getString("cause"))).append('}');
                } else if (type.equals("jdk.ObjectAllocationSample")) {
                    row.append("],\"objectClass\":").append(quote(event.getClass("objectClass").getName()))
                       .append(",\"weight\":").append(event.getLong("weight")).append('}');
                } else if (type.equals("rwx.ClockSync")) {
                    row.append(']');
                    for (String field : new String[] {"monoStart", "monoEnd", "epochMillis"}) {
                        row.append(',').append(quote(field)).append(':').append(event.getLong(field));
                    }
                    row.append('}');
                } else if (type.equals("jdk.DirectBufferStatistics")) {
                    row.append(']');
                    for (String field : new String[] {"maxCapacity", "count", "totalCapacity", "memoryUsed"}) {
                        row.append(',').append(quote(field)).append(':').append(event.getLong(field));
                    }
                    row.append('}');
                } else if (type.equals("jdk.ThreadCPULoad")) {
                    row.append("],\"user\":").append(event.getFloat("user"))
                       .append(",\"system\":").append(event.getFloat("system")).append('}');
                } else if (type.equals("jdk.CPULoad")) {
                    row.append("],\"jvmUser\":").append(event.getFloat("jvmUser"))
                       .append(",\"jvmSystem\":").append(event.getFloat("jvmSystem"))
                       .append(",\"machineTotal\":").append(event.getFloat("machineTotal")).append('}');
                } else if (type.equals("jdk.ThreadPark")) {
                    row.append("],\"requestedTimeoutNanos\":").append(event.getLong("timeout"))
                       .append(",\"until\":").append(event.getLong("until")).append('}');
                } else row.append("]}");
                output.write(row.toString()); output.newLine(); count++;
            }
        }
        System.out.println("Compact JFR events: " + count);
    }
}
