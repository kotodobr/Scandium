package dev.scandium.client;

import dev.scandium.Scandium;
import dev.scandium.config.ScandiumConfig;
import dev.scandium.gc.GcEnvironment;
import dev.scandium.gc.GcMonitor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * {@code /scandium bench}: records every frame time for 15 seconds and reports average, 1% low, 0.1% low and
 * lowest FPS, plus how much the GC paused the game meanwhile.
 * <p>
 * "X% low" follows the usual definition of frame-time tools: the average of the slowest X% of frames, converted
 * to FPS (frame-time based, so one 200 ms hitch weighs as much as it is felt). "Lowest" is the single slowest
 * frame. Only touched from the render thread.
 */
public final class FrameBenchmark {
    private static final long DURATION_NANOS = TimeUnit.SECONDS.toNanos(15);

    private static boolean running;
    private static long startNanos;
    private static long lastFrameNanos;
    private static long[] frameTimes = new long[16384];
    private static int frameCount;
    private static long gcCountAtStart;
    private static long gcPauseAtStart;

    private FrameBenchmark() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (running) {
            message(mc, Component.literal("Benchmark is already running").withStyle(ChatFormatting.YELLOW));
            return;
        }
        running = true;
        frameCount = 0;
        startNanos = System.nanoTime();
        lastFrameNanos = startNanos;
        gcCountAtStart = GcMonitor.get().collectionCount();
        gcPauseAtStart = GcMonitor.get().totalPauseMillis();
        message(mc, Component.literal("Scandium benchmark started: 15 s, keep playing normally...")
                .withStyle(ChatFormatting.AQUA));
    }

    /** Called at the start of every frame. */
    public static void onFrame() {
        if (!running) {
            return;
        }
        long now = System.nanoTime();
        if (frameCount == frameTimes.length) {
            frameTimes = Arrays.copyOf(frameTimes, frameTimes.length * 2);
        }
        frameTimes[frameCount++] = now - lastFrameNanos;
        lastFrameNanos = now;
        if (now - startNanos >= DURATION_NANOS) {
            running = false;
            report(now - startNanos);
        }
    }

    private static void report(long totalNanos) {
        Minecraft mc = Minecraft.getInstance();
        int n = frameCount;
        if (n < 10) {
            message(mc, Component.literal("Benchmark failed: too few frames").withStyle(ChatFormatting.RED));
            return;
        }
        long[] sorted = Arrays.copyOf(frameTimes, n);
        Arrays.sort(sorted); // ascending: slowest frames at the end

        double avgFps = n / (totalNanos / 1e9);
        double low1 = lowFps(sorted, 0.01);
        double low01 = lowFps(sorted, 0.001);
        double lowest = 1e9 / sorted[n - 1];
        long gcCount = GcMonitor.get().collectionCount() - gcCountAtStart;
        long gcPause = GcMonitor.get().totalPauseMillis() - gcPauseAtStart;

        String state = ScandiumConfig.get().enabled ? "enabled" : "disabled";
        String summary = String.format(Locale.ROOT,
                "avg %.1f | 1%% low %.1f | 0.1%% low %.1f | lowest %.1f FPS", avgFps, low1, low01, lowest);
        String details = String.format(Locale.ROOT,
                "%d frames in %.1f s, worst frame %.1f ms | GC (%s): %d collections, %d ms paused | Scandium %s",
                n, totalNanos / 1e9, sorted[n - 1] / 1e6, GcEnvironment.get().collector.displayName,
                gcCount, gcPause, state);

        message(mc, Component.literal("Scandium benchmark: ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(summary).withStyle(ChatFormatting.WHITE)));
        message(mc, Component.literal(details).withStyle(ChatFormatting.GRAY));
        Scandium.LOGGER.info("Benchmark: {} | {}", summary, details);
    }

    /** Average FPS of the slowest {@code fraction} of frames (at least one frame). */
    private static double lowFps(long[] ascending, double fraction) {
        int n = ascending.length;
        int count = Math.max(1, (int) Math.round(n * fraction));
        long sum = 0;
        for (int i = n - count; i < n; i++) {
            sum += ascending[i];
        }
        double avgFrameNanos = (double) sum / count;
        return 1e9 / avgFrameNanos;
    }

    private static void message(Minecraft mc, Component text) {
        mc.gui.getChat().addMessage(text);
    }
}
