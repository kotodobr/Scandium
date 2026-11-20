package dev.scandium.gc;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks a tested set of JVM arguments for this machine: heap size from the number of installed mods and the
 * physical RAM, collector from the Java version.
 * <ul>
 *     <li><b>Generational ZGC</b> (Java 21+, at least 8 GB RAM): pauses well under a millisecond regardless of heap
 *     size; costs some throughput and memory headroom, so it gets a slightly larger heap.</li>
 *     <li><b>G1 low-pause</b> otherwise: shorter pause target, larger minimum young generation (Minecraft
 *     allocates a lot of short-lived garbage), explicit GCs concurrent so Scandium's scheduling never freezes.</li>
 * </ul>
 */
public final class JvmProfiles {
    public enum Kind {
        ZGC_GENERATIONAL("Generational ZGC"),
        G1_LOW_PAUSE("G1 low-pause");

        public final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }
    }

    public record Profile(Kind kind, int heapMb, List<String> arguments, String reason) {
        public String commandLine() {
            return String.join(" ", arguments);
        }
    }

    private JvmProfiles() {
    }

    public static Profile recommend() {
        int javaVersion = Runtime.version().feature();
        long physicalMb = physicalMemoryMb();
        int mods = FabricLoader.getInstance().getAllMods().size();
        boolean zgc = javaVersion >= 21 && (physicalMb < 0 || physicalMb >= 8192);

        // Heap from the size of the mod list: vanilla-like setups waste memory (and GC time) with a huge heap.
        int heapMb = mods < 60 ? 4096 : mods < 150 ? 6144 : 8192;
        if (zgc) {
            heapMb += 1024; // ZGC needs headroom to collect concurrently
        }
        String reason = mods + " mods, Java " + javaVersion
                + (physicalMb > 0 ? ", " + physicalMb / 1024 + " GB RAM" : "");
        if (physicalMb > 0) {
            // Never more than half of the RAM: the OS, the driver and off-heap game memory need the rest.
            int cap = (int) Math.max(2048, physicalMb / 2) / 512 * 512;
            if (heapMb > cap) {
                heapMb = cap;
                reason += ", heap capped at half the RAM";
            }
        }
        String heap = heapMb % 1024 == 0 ? (heapMb / 1024) + "G" : heapMb + "M";

        List<String> args = new ArrayList<>();
        args.add("-Xms" + heap);
        args.add("-Xmx" + heap);
        if (zgc) {
            args.add("-XX:+UseZGC");
            args.add("-XX:+ZGenerational");
        } else {
            args.add("-XX:+UseG1GC");
            args.add("-XX:+UnlockExperimentalVMOptions");
            args.add("-XX:MaxGCPauseMillis=30");
            args.add("-XX:G1NewSizePercent=30");
            args.add("-XX:G1MaxNewSizePercent=40");
            args.add("-XX:G1ReservePercent=20");
            args.add("-XX:G1HeapRegionSize=8M");
            args.add("-XX:+ParallelRefProcEnabled");
            args.add("-XX:+ExplicitGCInvokesConcurrent");
        }
        // The perf-data file is written on every safepoint; on slow disks that write can stretch GC pauses.
        args.add("-XX:+PerfDisableSharedMem");
        return new Profile(zgc ? Kind.ZGC_GENERATIONAL : Kind.G1_LOW_PAUSE, heapMb, args, reason);
    }

    /** Whether the running JVM already uses the recommended collector. */
    public static boolean matchesCurrent(Profile profile) {
        GcEnvironment env = GcEnvironment.get();
        return switch (profile.kind()) {
            case ZGC_GENERATIONAL -> env.collector == GcEnvironment.Collector.GENERATIONAL_ZGC;
            case G1_LOW_PAUSE -> env.collector == GcEnvironment.Collector.G1 && env.explicitGcIsConcurrent;
        };
    }

    private static long physicalMemoryMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize() >> 20;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
