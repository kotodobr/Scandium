package dev.scandium.gc;

import dev.scandium.Scandium;
import dev.scandium.config.ScandiumConfig;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides <em>when</em> the garbage collector should run.
 * <p>
 * The JVM only knows about memory, not about the game: it happily starts a long collection in the middle of a
 * fight and stays idle while the player sits in the pause menu. Scandium gives it guidance in three ways:
 * <ol>
 *     <li><b>Event collections</b>: right after something turns a lot of memory into garbage at once (leaving a
 *     world, a resource reload) a collection is requested while the player is looking at a loading screen
 *     anyway.</li>
 *     <li><b>Idle collections</b>: while the game is paused or a server is empty, the heap is cleaned if enough
 *     garbage piled up. A pause the player can't see costs nothing, and it removes the need for one later.</li>
 *     <li><b>Hints</b>: manageable JVM flags (G1 periodic collections, ZGC/Shenandoah soft heap limit) are set
 *     so the collector itself works more steadily. See {@link #applyJvmHints()}.</li>
 * </ol>
 * Collections always run on a dedicated thread, never on the client or server thread.
 */
public final class GcScheduler {
    public enum Reason {
        WORLD_LEAVE("leaving the world"),
        RESOURCE_RELOAD("resource reload"),
        DATA_RELOAD("data-pack reload"),
        PAUSED("game paused"),
        SERVER_EMPTY("server empty"),
        HIGH_PRESSURE("heap almost full"),
        MANUAL("manual request");

        final String description;

        Reason(String description) {
            this.description = description;
        }
    }

    /** Something that makes the current moment a good time for a collection. */
    public enum IdleSource {
        CLIENT_PAUSED,
        SERVER_EMPTY
    }

    private static final GcScheduler INSTANCE = new GcScheduler();
    /** Event collections may follow each other faster than idle ones, but not back to back. */
    private static final long EVENT_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Scandium GC Scheduler");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final AtomicBoolean collectionPending = new AtomicBoolean();
    private final Set<IdleSource> idleSources = EnumSet.noneOf(IdleSource.class);
    private volatile long lastCollectionNanos = System.nanoTime() - TimeUnit.HOURS.toNanos(1);
    private long idleSinceNanos = -1;
    private long lastSampleNanos;
    private volatile String lastCollectionSummary = "none yet";

    private GcScheduler() {
    }

    public static GcScheduler get() {
        return INSTANCE;
    }

    /** Original values of the VM options changed by {@link #applyJvmHints()}, to restore them on disable. */
    private final java.util.Map<String, String> originalOptions = new java.util.LinkedHashMap<>();

    public void init() {
        GcMonitor.get().install();
        if (ScandiumConfig.get().enabled) {
            applyJvmHints();
        } else {
            Scandium.LOGGER.info("Scandium is disabled (config 'enabled': false / /scandium disable)");
        }
        if (ScandiumConfig.get().logJvmAdvice) {
            JvmAdvisor.logAdvice(GcEnvironment.get());
        }
    }

    // ------------------------------------------------------------------ hints

    /**
     * Sets HotSpot flags that are "manageable", i.e. changeable at runtime, and that tell the collector how the
     * game wants it to behave. Flags the user set explicitly on the command line are never overwritten.
     */
    public synchronized void applyJvmHints() {
        ScandiumConfig cfg = ScandiumConfig.get();
        GcEnvironment env = GcEnvironment.get();
        switch (env.collector) {
            case G1 -> {
                if (cfg.g1PeriodicGcIntervalSeconds > 0) {
                    // JEP 346: after this long without any GC, G1 starts a concurrent cycle and uncommits unused
                    // memory. Keeps the heap tidy during long calm phases instead of one big cleanup later.
                    long ms = TimeUnit.SECONDS.toMillis(cfg.g1PeriodicGcIntervalSeconds);
                    if (setHint(env, "G1PeriodicGCInterval", Long.toString(ms))) {
                        Scandium.LOGGER.info("GC hint: G1 periodic concurrent collection every {}s of GC inactivity",
                                cfg.g1PeriodicGcIntervalSeconds);
                    }
                }
            }
            case ZGC, GENERATIONAL_ZGC, SHENANDOAH -> {
                if (cfg.softMaxHeapFraction > 0 && cfg.softMaxHeapFraction < 1) {
                    long soft = (long) (env.maxHeapBytes * cfg.softMaxHeapFraction);
                    if (setHint(env, "SoftMaxHeapSize", Long.toString(soft))) {
                        Scandium.LOGGER.info("GC hint: soft heap limit {} MB (of {} MB max)",
                                soft >> 20, env.maxHeapBytes >> 20);
                    }
                }
            }
            default -> {
            }
        }
    }

    private boolean setHint(GcEnvironment env, String name, String value) {
        String original = env.option(name);
        if (!env.trySetOption(name, value)) {
            return false;
        }
        if (original != null) {
            originalOptions.putIfAbsent(name, original);
        }
        return true;
    }

    /** Puts every VM option changed by {@link #applyJvmHints()} back to the value it had before. */
    public synchronized void revertJvmHints() {
        GcEnvironment env = GcEnvironment.get();
        originalOptions.forEach((name, value) -> {
            if (env.trySetOption(name, value)) {
                Scandium.LOGGER.info("GC hint reverted: {}={}", name, value);
            }
        });
        originalOptions.clear();
    }

    /** Runtime master switch used by {@code /scandium enable|disable}. Persists to the config. */
    public void setEnabled(boolean enabled) {
        ScandiumConfig cfg = ScandiumConfig.get();
        if (cfg.enabled == enabled) {
            return;
        }
        cfg.enabled = enabled;
        cfg.save();
        synchronized (idleSources) {
            idleSources.clear();
            idleSinceNanos = -1;
        }
        if (enabled) {
            applyJvmHints();
        } else {
            revertJvmHints();
        }
        Scandium.LOGGER.info("Scandium {}", enabled ? "enabled" : "disabled");
    }

    // ------------------------------------------------------------------ inputs from the game

    /** Called once per tick from the client or server thread. Must stay cheap. */
    public void tick() {
        long now = System.nanoTime();
        if (now - lastSampleNanos < TimeUnit.SECONDS.toNanos(1)) {
            return;
        }
        lastSampleNanos = now;
        GcMonitor.get().sample();

        ScandiumConfig cfg = ScandiumConfig.get();
        if (!canCollect(cfg)) {
            return;
        }
        long sinceLast = now - lastCollectionNanos;
        if (sinceLast < TimeUnit.SECONDS.toNanos(cfg.minSecondsBetweenGc)) {
            return;
        }
        GcMonitor monitor = GcMonitor.get();

        // 1. Idle collection
        synchronized (idleSources) {
            if (!idleSources.isEmpty() && idleSinceNanos >= 0
                    && now - idleSinceNanos >= TimeUnit.SECONDS.toNanos(cfg.idleDelaySeconds)
                    && monitor.estimatedGarbage() >= ((long) cfg.minGarbageMbForIdleGc << 20)) {
                Reason reason = idleSources.contains(IdleSource.CLIENT_PAUSED) ? Reason.PAUSED : Reason.SERVER_EMPTY;
                submit(reason, 0);
                return;
            }
        }

        // 2. High pressure during gameplay: only if a collection does not freeze the game.
        if (cfg.gcOnHighPressure && GcEnvironment.get().explicitGcIsConcurrent) {
            double occupancy = (double) GcMonitor.usedHeap() / GcEnvironment.get().maxHeapBytes;
            if (occupancy >= cfg.highPressureThreshold
                    && monitor.estimatedGarbage() >= ((long) cfg.minGarbageMbForIdleGc << 20)) {
                submit(Reason.HIGH_PRESSURE, 0);
            }
        }
    }

    public void setIdle(IdleSource source, boolean idle) {
        ScandiumConfig cfg = ScandiumConfig.get();
        if (idle && source == IdleSource.CLIENT_PAUSED && !cfg.gcWhilePaused) return;
        if (idle && source == IdleSource.SERVER_EMPTY && !cfg.gcWhenServerEmpty) return;
        synchronized (idleSources) {
            boolean changed = idle ? idleSources.add(source) : idleSources.remove(source);
            if (changed) {
                idleSinceNanos = idleSources.isEmpty() ? -1 : System.nanoTime();
            }
        }
    }

    /**
     * Requests a collection because of a game event. {@code delayMillis} lets the game drop its last references
     * first (e.g. a world is still referenced for a moment after the disconnect screen appears).
     */
    public void onEvent(Reason reason, long delayMillis) {
        ScandiumConfig cfg = ScandiumConfig.get();
        boolean enabled = switch (reason) {
            case WORLD_LEAVE -> cfg.gcOnWorldLeave;
            case RESOURCE_RELOAD, DATA_RELOAD -> cfg.gcAfterResourceReload;
            default -> true;
        };
        if (!enabled || !canCollect(cfg)) {
            return;
        }
        if (System.nanoTime() - lastCollectionNanos < EVENT_COOLDOWN_NANOS) {
            return;
        }
        submit(reason, delayMillis);
    }

    // ------------------------------------------------------------------ execution

    private boolean canCollect(ScandiumConfig cfg) {
        return cfg.enabled && cfg.gcScheduling && !GcEnvironment.get().explicitGcDisabled;
    }

    private void submit(Reason reason, long delayMillis) {
        if (!collectionPending.compareAndSet(false, true)) {
            return;
        }
        // Mark the cooldown immediately so tick() doesn't keep resubmitting while we wait for the delay.
        lastCollectionNanos = System.nanoTime();
        executor.schedule(() -> collect(reason), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void collect(Reason reason) {
        try {
            long before = GcMonitor.usedHeap();
            long start = System.nanoTime();
            System.gc();
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            long after = GcMonitor.usedHeap();
            lastCollectionNanos = System.nanoTime();
            lastCollectionSummary = String.format("%s: %d -> %d MB in %d ms",
                    reason.name().toLowerCase(), before >> 20, after >> 20, tookMs);
            Scandium.LOGGER.info("Scheduled GC ({}): {} MB -> {} MB, {} ms{}", reason.description,
                    before >> 20, after >> 20, tookMs,
                    GcEnvironment.get().explicitGcIsConcurrent ? " (concurrent)" : "");
        } catch (Throwable t) {
            Scandium.LOGGER.warn("Scheduled GC failed", t);
        } finally {
            collectionPending.set(false);
        }
    }

    public String lastCollectionSummary() {
        return lastCollectionSummary;
    }
}
