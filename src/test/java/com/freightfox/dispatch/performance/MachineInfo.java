package com.freightfox.dispatch.performance;

import org.hibernate.Version;
import org.springframework.boot.SpringBootVersion;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/** Describes the machine, JVM and library versions a performance run used. */
final class MachineInfo {

    private static final String NOT_AVAILABLE = "n/a";
    private static final double BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0;
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;
    private static final String ORTOOLS_POM_PROPERTIES = "/META-INF/maven/com.google.ortools/ortools-java/pom.properties";

    private MachineInfo() {
    }

    /**
     * @param database database product and version, or null if not known (e.g. no Spring context)
     * @return label -> value, in display order
     */
    static Map<String, String> describe(String database) {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("OS", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        info.put("CPU", cpuDescription());
        info.put("Available processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        info.put("Physical memory", physicalMemory());
        info.put("Max heap (Runtime.maxMemory)", String.format(Locale.ROOT, "%.0f MB",
                Runtime.getRuntime().maxMemory() / BYTES_PER_MB));
        info.put("Heap JVM flags", heapFlags());
        info.put("Garbage collectors", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", ")));
        info.put("Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ", "
                + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version") + ")");
        info.put("Spring Boot", valueOrNa(SpringBootVersion.getVersion()));
        info.put("Hibernate ORM", valueOrNa(Version.getVersionString()));
        info.put("OR-Tools (ortools-java)", orToolsVersion());
        if (database != null) {
            info.put("Database", database);
        }
        return info;
    }

    private static String cpuDescription() {
        // Windows exposes the CPU model in an environment variable; elsewhere the count is enough.
        String identifier = System.getenv("PROCESSOR_IDENTIFIER");
        return identifier == null || identifier.isBlank() ? NOT_AVAILABLE : identifier.strip();
    }

    private static String physicalMemory() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            return String.format(Locale.ROOT, "%.1f GB", sunOs.getTotalMemorySize() / BYTES_PER_GB);
        }
        return NOT_AVAILABLE;
    }

    private static String heapFlags() {
        String flags = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-Xm") || arg.startsWith("-XX:MaxRAM") || arg.startsWith("-XX:+Use"))
                .collect(Collectors.joining(" "));
        return flags.isEmpty() ? "(JVM defaults)" : flags;
    }

    private static String orToolsVersion() {
        try (InputStream in = MachineInfo.class.getResourceAsStream(ORTOOLS_POM_PROPERTIES)) {
            if (in == null) {
                return NOT_AVAILABLE;
            }
            Properties properties = new Properties();
            properties.load(in);
            return valueOrNa(properties.getProperty("version"));
        } catch (IOException e) {
            return NOT_AVAILABLE;
        }
    }

    private static String valueOrNa(String value) {
        return value == null || value.isBlank() ? NOT_AVAILABLE : value;
    }
}
