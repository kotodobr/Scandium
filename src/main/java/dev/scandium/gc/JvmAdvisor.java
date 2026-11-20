package dev.scandium.gc;

import dev.scandium.Scandium;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Logs concrete, actionable advice about the JVM arguments the game was started with.
 */
final class JvmAdvisor {
    private JvmAdvisor() {
    }

    static void logAdvice(GcEnvironment env) {
        long maxMb = env.maxHeapBytes >> 20;
        Scandium.LOGGER.info("JVM: {} {}, GC: {}, max heap {} MB, explicit GC {}",
                System.getProperty("java.vm.name"), System.getProperty("java.version"),
                env.collector.displayName, maxMb,
                env.explicitGcDisabled ? "disabled" : env.explicitGcIsConcurrent ? "concurrent" : "stop-the-world");

        List<String> advice = new ArrayList<>();
        if (env.explicitGcDisabled) {
            advice.add("-XX:+DisableExplicitGC is set, so Scandium's GC scheduling cannot run. "
                    + "Remove it to let Scandium collect on world leave, reloads and in the pause menu.");
        }
        switch (env.collector) {
            case G1 -> {
                if (!env.explicitGcIsConcurrent) {
                    advice.add("Add -XX:+ExplicitGCInvokesConcurrent: scheduled collections then run "
                            + "concurrently instead of freezing the game, and Scandium can also clean up "
                            + "during gameplay when the heap is nearly full.");
                }
            }
            case PARALLEL, SERIAL -> advice.add(env.collector.displayName
                    + " GC causes long freezes with Minecraft's heap sizes. Use G1 (-XX:+UseG1GC) or, on Java 21+, "
                    + "generational ZGC (-XX:+UseZGC -XX:+ZGenerational).");
            default -> {
            }
        }
        if (maxMb < 2048) {
            advice.add("Max heap is only " + maxMb + " MB. Modded Minecraft usually needs 4-6 GB (-Xmx4G).");
        }
        long physical = physicalMemory();
        if (physical > 0 && env.maxHeapBytes > physical * 0.75) {
            advice.add("Max heap (" + maxMb + " MB) is more than 75% of physical RAM ("
                    + (physical >> 20) + " MB). The OS may start swapping; lower -Xmx.");
        }
        if (maxMb > 12 * 1024 && env.collector == GcEnvironment.Collector.G1) {
            advice.add("Very large heap with G1: more heap means longer collections, not better performance. "
                    + "6-10 GB is enough for almost every modpack.");
        }
        if (!env.hasArgument("-Xms") && maxMb >= 2048) {
            advice.add("Consider setting -Xms equal to -Xmx (or at least half of it) to avoid repeated heap "
                    + "resizing during loading.");
        }
        for (String line : advice) {
            Scandium.LOGGER.warn("[JVM advice] {}", line);
        }
        JvmProfiles.Profile profile = JvmProfiles.recommend();
        if (!JvmProfiles.matchesCurrent(profile)) {
            Scandium.LOGGER.warn("[JVM advice] Recommended profile: {} ({}). Arguments: {}  (also: /scandium jvm)",
                    profile.kind().displayName, profile.reason(), profile.commandLine());
        }
    }

    private static long physicalMemory() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
