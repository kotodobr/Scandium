package dev.scandium.gc;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import dev.scandium.Scandium;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Locale;

/**
 * Detects which garbage collector the JVM runs and which of its flags are relevant for scheduling.
 */
public final class GcEnvironment {
    public enum Collector {
        G1("G1"),
        ZGC("ZGC"),
        GENERATIONAL_ZGC("Generational ZGC"),
        SHENANDOAH("Shenandoah"),
        PARALLEL("Parallel"),
        SERIAL("Serial"),
        UNKNOWN("Unknown");

        public final String displayName;

        Collector(String displayName) {
            this.displayName = displayName;
        }
    }

    public final Collector collector;
    /** True when System.gc() does not stop the world for the whole collection. */
    public final boolean explicitGcIsConcurrent;
    public final boolean explicitGcDisabled;
    public final long maxHeapBytes;
    public final List<String> jvmArguments;
    private final HotSpotDiagnosticMXBean hotspot;

    private GcEnvironment() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        this.jvmArguments = runtime.getInputArguments();
        this.hotspot = loadHotspotBean();
        this.collector = detectCollector();
        this.explicitGcDisabled = flag("DisableExplicitGC");
        this.explicitGcIsConcurrent = switch (collector) {
            // ZGC and Shenandoah run System.gc() as a regular concurrent cycle.
            case ZGC, GENERATIONAL_ZGC, SHENANDOAH -> true;
            case G1 -> flag("ExplicitGCInvokesConcurrent");
            default -> false;
        };
        this.maxHeapBytes = Runtime.getRuntime().maxMemory();
    }

    private static HotSpotDiagnosticMXBean loadHotspotBean() {
        try {
            return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        } catch (Throwable t) {
            return null; // non-HotSpot JVM (OpenJ9 etc.)
        }
    }

    private Collector detectCollector() {
        if (flag("UseZGC")) {
            return flag("ZGenerational") ? Collector.GENERATIONAL_ZGC : Collector.ZGC;
        }
        if (flag("UseShenandoahGC")) return Collector.SHENANDOAH;
        if (flag("UseG1GC")) return Collector.G1;
        if (flag("UseParallelGC")) return Collector.PARALLEL;
        if (flag("UseSerialGC")) return Collector.SERIAL;
        // Fall back to bean names if the diagnostic bean is not available.
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = bean.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("g1")) return Collector.G1;
            if (name.startsWith("zgc")) return Collector.ZGC;
            if (name.startsWith("shenandoah")) return Collector.SHENANDOAH;
            if (name.startsWith("ps ")) return Collector.PARALLEL;
            if (name.equals("copy") || name.equals("marksweepcompact")) return Collector.SERIAL;
        }
        return Collector.UNKNOWN;
    }

    public boolean flag(String name) {
        String value = option(name);
        return "true".equalsIgnoreCase(value);
    }

    public String option(String name) {
        if (hotspot == null) return null;
        try {
            return hotspot.getVMOption(name).getValue();
        } catch (IllegalArgumentException e) {
            return null; // flag doesn't exist on this JVM version
        }
    }

    /**
     * Sets a "manageable" HotSpot flag at runtime. Returns false if the flag does not exist or is not writable.
     */
    public boolean trySetOption(String name, String value) {
        if (hotspot == null) return false;
        try {
            VMOption option = hotspot.getVMOption(name);
            if (!option.isWriteable()) return false;
            VMOption.Origin origin = option.getOrigin();
            if (origin == VMOption.Origin.VM_CREATION || origin == VMOption.Origin.ENVIRON_VAR
                    || origin == VMOption.Origin.CONFIG_FILE) {
                // The user configured this explicitly on the command line: respect their choice.
                return false;
            }
            hotspot.setVMOption(name, value);
            return true;
        } catch (IllegalArgumentException | SecurityException e) {
            Scandium.LOGGER.debug("Could not set VM option {}={}", name, value, e);
            return false;
        }
    }

    public boolean hasArgument(String prefix) {
        for (String arg : jvmArguments) {
            if (arg.startsWith(prefix)) return true;
        }
        return false;
    }

    private static GcEnvironment instance;

    public static synchronized GcEnvironment get() {
        if (instance == null) {
            instance = new GcEnvironment();
        }
        return instance;
    }
}
