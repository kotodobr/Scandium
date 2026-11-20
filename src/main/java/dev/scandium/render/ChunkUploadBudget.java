package dev.scandium.render;

import dev.scandium.config.ScandiumConfig;

import java.util.Queue;

/**
 * Time budget for uploading finished chunk meshes to the GPU.
 * <p>
 * Vanilla ({@code SectionRenderDispatcher.uploadAllPendingUploads}) drains the whole queue every frame. While
 * flying, after a teleport or when joining, dozens of uploads land in a single frame: a micro-stutter. Here the
 * queue is drained by time; the rest simply runs next frame (the method is called every frame).
 * <p>
 * Safe because a section's compiled data is only swapped in after all of its uploads finished: a delayed upload
 * means "appears a frame later", not "hole in the world". Ported from Vodium.
 */
public final class ChunkUploadBudget {
    private ChunkUploadBudget() {
    }

    public static void drain(Queue<Runnable> uploadQueue) {
        int pending = uploadQueue.size();
        if (pending == 0) {
            return;
        }
        ScandiumConfig cfg = ScandiumConfig.get();
        long deadline = System.nanoTime() + budgetNanosFor(cfg, pending);
        int done = 0;
        Runnable upload;
        while ((upload = uploadQueue.poll()) != null) {
            upload.run();
            done++;
            // Always do a minimum, otherwise a slow machine could exhaust the budget before the first upload.
            if (done >= cfg.minChunkUploadsPerFrame && System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    /** The longer the queue, the more time it gets - otherwise chunks trail behind during a fast flight. */
    private static long budgetNanosFor(ScandiumConfig cfg, int pending) {
        long base = cfg.chunkUploadBudgetMicros * 1000L;
        long max = Math.max(base, cfg.chunkUploadMaxBudgetMicros * 1000L);
        int backlog = cfg.chunkUploadBacklog;
        if (backlog <= 0 || pending <= backlog) {
            return base;
        }
        double overload = Math.min(1.0, (double) (pending - backlog) / backlog);
        return base + (long) ((max - base) * overload);
    }
}
