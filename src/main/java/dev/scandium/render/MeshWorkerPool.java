package dev.scandium.render;

import dev.scandium.Scandium;
import dev.scandium.config.ScandiumConfig;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated thread pool for building chunk meshes.
 * <p>
 * Vanilla builds meshes on {@code Util.backgroundExecutor()}, the same pool that runs world generation and chunk
 * loading. While exploring, mesh building queues up behind world generation and sections pop in with stutters.
 * With its own pool neither waits for the other. Threads run at slightly lower priority: if the CPU is short,
 * mesh building should yield to the render thread, not the other way round. Ported from Vodium.
 */
public final class MeshWorkerPool {
    private static ExecutorService pool;

    private MeshWorkerPool() {
    }

    /** @param vanilla the executor vanilla passed in; returned unchanged when the feature is off */
    public static synchronized Executor get(Executor vanilla) {
        ScandiumConfig cfg = ScandiumConfig.get();
        if (!cfg.enabled || !cfg.dedicatedMeshThreads) {
            return vanilla;
        }
        if (pool == null) {
            int threads = cfg.meshThreadCount > 0 ? cfg.meshThreadCount : defaultThreadCount();
            AtomicInteger counter = new AtomicInteger(1);
            ThreadFactory factory = task -> {
                Thread thread = new Thread(task, "Scandium Mesh " + counter.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            };
            Scandium.LOGGER.info("Chunk meshes: dedicated pool with {} threads", threads);
            pool = Executors.newFixedThreadPool(threads, factory);
        }
        return pool;
    }

    /** Leave cores for the render thread and world generation; taking all of them only moves the contention. */
    private static int defaultThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(cores - 2, 6));
    }
}
