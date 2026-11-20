package dev.scandium.gc;

import dev.scandium.Scandium;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@code /scandium alloc}: records 15 seconds of allocation samples with Java Flight Recorder and reports which
 * code allocates the most - the code that makes the young generation fill up and causes GC pauses.
 * <p>
 * Uses the {@code jdk.ObjectAllocationSample} event (JDK 16+): the JVM samples allocations with a throttle, so the
 * overhead stays around 1%. Each sample carries a weight (bytes allocated since the previous sample), which makes
 * the totals a good estimate of real allocation volume.
 * <p>
 * Sites are grouped by the first stack frame outside the JDK and common libraries, i.e. the game or mod method
 * responsible for the allocation. In production builds Minecraft names are intermediary ({@code class_1234}).
 */
public final class AllocationProfiler {
    private static final Duration DURATION = Duration.ofSeconds(15);
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** Frames from these packages are skipped when looking for the responsible method. */
    private static final String[] LIBRARY_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "it.unimi.dsi.", "com.google.", "org.joml.",
            "org.lwjgl.", "com.mojang.datafixers.", "com.mojang.serialization.", "org.apache.", "io.netty.",
            "org.spongepowered.", "com.llamalad7."
    };

    private AllocationProfiler() {
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * Starts a recording on a background thread. {@code report} receives the human-readable result lines (called
     * from that background thread - callers have to hop to their own thread if needed).
     *
     * @return false if a recording is already running
     */
    public static boolean start(Consumer<List<String>> report) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        Thread thread = new Thread(() -> {
            try {
                report.accept(record());
            } catch (Throwable t) {
                Scandium.LOGGER.warn("Allocation profiling failed", t);
                report.accept(List.of("Allocation profiling failed: " + t));
            } finally {
                RUNNING.set(false);
            }
        }, "Scandium Allocation Profiler");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static List<String> record() throws IOException, InterruptedException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("scandium");
        Files.createDirectories(dir);
        Path file = dir.resolve("alloc-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".jfr");

        long gcCountBefore = GcMonitor.get().collectionCount();
        long gcPauseBefore = GcMonitor.get().totalPauseMillis();
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "300/s");
            recording.start();
            Thread.sleep(DURATION.toMillis());
            recording.stop();
            recording.dump(file);
        }
        long gcCount = GcMonitor.get().collectionCount() - gcCountBefore;
        long gcPause = GcMonitor.get().totalPauseMillis() - gcPauseBefore;

        Map<String, long[]> bySite = new HashMap<>();
        Map<String, long[]> byType = new HashMap<>();
        long total = 0;
        for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
            if (!"jdk.ObjectAllocationSample".equals(event.getEventType().getName())) {
                continue;
            }
            long weight = event.getLong("weight");
            total += weight;
            RecordedClass type = event.getClass("objectClass");
            String typeName = type == null ? "?" : type.getName();
            add(byType, typeName, weight);
            add(bySite, responsibleFrame(event.getStackTrace()), weight);
        }

        double seconds = DURATION.toMillis() / 1000.0;
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Allocation rate ~%.0f MB/s (%.0f MB in %.0f s), GC: %d collections, %d ms paused",
                total / seconds / (1 << 20), total / (double) (1 << 20), seconds, gcCount, gcPause));
        lines.add("Top allocating methods:");
        appendTop(lines, bySite, total, 10);
        lines.add("Top allocated types:");
        appendTop(lines, byType, total, 5);
        lines.add("Full recording: " + FabricLoader.getInstance().getGameDir().relativize(file)
                + " (open with JDK Mission Control)");
        return lines;
    }

    private static void add(Map<String, long[]> map, String key, long weight) {
        map.computeIfAbsent(key, k -> new long[1])[0] += weight;
    }

    private static void appendTop(List<String> lines, Map<String, long[]> map, long total, int limit) {
        map.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(limit)
                .forEach(e -> lines.add(String.format(Locale.ROOT, "  %5.1f%%  %6.1f MB  %s",
                        total == 0 ? 0 : 100.0 * e.getValue()[0] / total, e.getValue()[0] / (double) (1 << 20),
                        e.getKey())));
    }

    /** The first frame that belongs to the game or a mod, plus its caller for context. */
    private static String responsibleFrame(RecordedStackTrace stack) {
        if (stack == null || stack.getFrames().isEmpty()) {
            return "(no stack)";
        }
        List<RecordedFrame> frames = stack.getFrames();
        for (int i = 0; i < frames.size(); i++) {
            RecordedFrame frame = frames.get(i);
            if (!frame.isJavaFrame() || frame.getMethod() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            if (isLibrary(type)) {
                continue;
            }
            return shortName(frame);
        }
        return shortName(frames.get(0));
    }

    private static boolean isLibrary(String className) {
        for (String prefix : LIBRARY_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String shortName(RecordedFrame frame) {
        String type = frame.getMethod().getType().getName();
        int dot = type.lastIndexOf('.');
        String simple = dot >= 0 ? type.substring(dot + 1) : type;
        return simple + "." + frame.getMethod().getName() + ":" + frame.getLineNumber();
    }
}
