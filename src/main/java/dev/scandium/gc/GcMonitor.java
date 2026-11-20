package dev.scandium.gc;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import dev.scandium.Scandium;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Locale;
import java.util.Map;

/**
 * Listens to the JVM's GC notifications and keeps cheap statistics that the scheduler uses for its decisions:
 * how much memory survived the last collection (an estimate of the live set), how long pauses were, and how fast
 * the game allocates.
 * <p>
 * All fields are written from the JMX notification thread and read from game threads, hence {@code volatile}.
 */
public final class GcMonitor implements NotificationListener {
    private static final GcMonitor INSTANCE = new GcMonitor();

    /** Heap used right after the most recent collection of any kind. */
    private volatile long heapAfterLastGc = -1;
    /**
     * Heap used right after the most recent collection that also processed the old generation (full, mixed or
     * concurrent cycle). This is the best available estimate of how much memory the game really needs.
     */
    private volatile long heapAfterLastMajorGc = -1;
    private volatile long lastPauseMillis;
    private volatile long longestPauseMillis;
    private volatile long totalPauseMillis;
    private volatile long collectionCount;
    private volatile long bytesFreedTotal;
    private volatile long lastGcTimeNanos = System.nanoTime();

    // allocation-rate sampling (only touched from the scheduler's sample() call)
    private long sampleUsed = -1;
    private long sampleFreed;
    private long sampleTime;
    private volatile double allocationRateBytesPerSecond;

    private GcMonitor() {
    }

    public static GcMonitor get() {
        return INSTANCE;
    }

    void install() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(this, null, null);
            }
        }
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
            return;
        }
        try {
            GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            record(info);
        } catch (RuntimeException e) {
            Scandium.LOGGER.debug("Failed to process GC notification", e);
        }
    }

    private void record(GarbageCollectionNotificationInfo info) {
        GcInfo gc = info.getGcInfo();
        long before = sum(gc.getMemoryUsageBeforeGc());
        long after = sum(gc.getMemoryUsageAfterGc());
        String name = info.getGcName().toLowerCase(Locale.ROOT);
        String action = info.getGcAction().toLowerCase(Locale.ROOT);

        // "Cycles"/"Concurrent" beans report the length of a concurrent cycle, not a stop-the-world pause.
        boolean isPause = !name.contains("cycles") && !name.contains("concurrent");
        boolean isMajor = action.contains("major") || name.contains("old") || name.contains("cycles")
                || name.contains("concurrent") || name.contains("marksweep");

        heapAfterLastGc = after;
        if (isMajor) {
            heapAfterLastMajorGc = after;
        }
        if (isPause) {
            long duration = gc.getDuration();
            lastPauseMillis = duration;
            totalPauseMillis += duration;
            if (duration > longestPauseMillis) {
                longestPauseMillis = duration;
            }
        }
        if (before > after) {
            bytesFreedTotal += before - after;
        }
        collectionCount++;
        lastGcTimeNanos = System.nanoTime();
    }

    private static long sum(Map<String, MemoryUsage> usage) {
        long total = 0;
        for (MemoryUsage u : usage.values()) {
            total += u.getUsed();
        }
        return total;
    }

    /** Updates the allocation-rate estimate. Called about once per second. */
    void sample() {
        long now = System.nanoTime();
        long used = usedHeap();
        long freed = bytesFreedTotal;
        if (sampleUsed >= 0) {
            double seconds = (now - sampleTime) / 1e9;
            if (seconds > 0.05) {
                // Everything that was freed in between must have been allocated, plus whatever growth remains.
                long allocated = (used - sampleUsed) + (freed - sampleFreed);
                double rate = Math.max(0, allocated) / seconds;
                // light smoothing so the F3 number is readable
                allocationRateBytesPerSecond = allocationRateBytesPerSecond == 0
                        ? rate : allocationRateBytesPerSecond * 0.7 + rate * 0.3;
            }
        }
        sampleUsed = used;
        sampleFreed = freed;
        sampleTime = now;
    }

    public static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Rough amount of memory a full collection could reclaim right now. */
    public long estimatedGarbage() {
        long baseline = heapAfterLastMajorGc >= 0 ? heapAfterLastMajorGc : heapAfterLastGc;
        if (baseline < 0) {
            return 0;
        }
        return Math.max(0, usedHeap() - baseline);
    }

    public long heapAfterLastGc() {
        return heapAfterLastGc;
    }

    public long heapAfterLastMajorGc() {
        return heapAfterLastMajorGc;
    }

    public long lastPauseMillis() {
        return lastPauseMillis;
    }

    public long longestPauseMillis() {
        return longestPauseMillis;
    }

    public long totalPauseMillis() {
        return totalPauseMillis;
    }

    public long collectionCount() {
        return collectionCount;
    }

    public double allocationRateBytesPerSecond() {
        return allocationRateBytesPerSecond;
    }

    public double secondsSinceLastGc() {
        return (System.nanoTime() - lastGcTimeNanos) / 1e9;
    }
}
