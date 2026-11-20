package dev.scandium.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.scandium.Scandium;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime configuration of Scandium, stored in {@code config/scandium.json}.
 * <p>
 * The FerriteCore-derived memory optimizations are toggled separately in
 * {@code config/scandium-ferrite.mixin.properties}, because they must be known before mixins are applied.
 */
public final class ScandiumConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ScandiumConfig instance;

    /**
     * Master switch, toggled by {@code /scandium enable|disable}. When false: no scheduled collections, JVM hints
     * reverted, runtime fixes inactive - immediately. Mixin-based optimizations (FerriteCore, leak fixes) are only
     * skipped after a restart, because bytecode that's already transformed can't be un-applied.
     */
    public volatile boolean enabled = true;

    // ---------------------------------------------------------------- GC scheduling
    /** Master switch for every explicit / scheduled collection Scandium performs. */
    public boolean gcScheduling = true;
    /** Collect when leaving a world/server (the old world becomes garbage all at once). */
    public boolean gcOnWorldLeave = true;
    /** Collect after resource-pack / data-pack reloads (old models, textures, recipes become garbage). */
    public boolean gcAfterResourceReload = true;
    /** Collect while a singleplayer game is paused, if there is enough garbage to make it worthwhile. */
    public boolean gcWhilePaused = true;
    /** Collect on a dedicated server while no players are online. */
    public boolean gcWhenServerEmpty = true;
    /**
     * Allow a collection during normal gameplay when the heap is close to full. Only used when the JVM can
     * collect concurrently (G1 with -XX:+ExplicitGCInvokesConcurrent, ZGC, Shenandoah), so it never stutters.
     */
    public boolean gcOnHighPressure = true;
    /** Heap occupancy (0..1) above which {@link #gcOnHighPressure} kicks in. */
    public double highPressureThreshold = 0.85;
    /** Minimum amount of collectable garbage (MB) before an idle collection is worth it. */
    public int minGarbageMbForIdleGc = 256;
    /** Minimum seconds between two collections requested by Scandium. */
    public int minSecondsBetweenGc = 30;
    /** Seconds the game must stay paused / server empty before an idle collection is started. */
    public int idleDelaySeconds = 3;

    // ---------------------------------------------------------------- GC hints (manageable JVM flags)
    /**
     * Hint G1 to run a periodic concurrent cycle (and give unused memory back to the OS) when no GC happened
     * for this many seconds. 0 = leave the JVM default.
     */
    public int g1PeriodicGcIntervalSeconds = 120;
    /**
     * For ZGC/Shenandoah: soft heap limit as a fraction of -Xmx. The collector then tries to stay below it and
     * only grows up to -Xmx under real pressure, which gives smoother, earlier collections. 0 = don't touch.
     */
    public double softMaxHeapFraction = 0.80;
    /** Print recommendations about JVM flags in the log at startup. */
    public boolean logJvmAdvice = true;

    // ---------------------------------------------------------------- leak fixes
    public boolean fixBiomeTemperatureCache = true;
    public boolean fixTextureReadLeak = true;
    public boolean fixHugeScreenshotLeak = true;
    public boolean fixClientStaleReferences = true;

    // ---------------------------------------------------------------- render (from Vodium, client)
    /** Region batching: sections share buffers per 4x4x4 region, one multi-draw per region and layer. */
    public boolean regionBatching = true;
    /** Water/glass/ice through regions too (riskiest part: draw order matters for blending). */
    public boolean translucentBatching = true;
    /** Merge sections lying back to back in a region buffer into one draw. */
    public boolean regionDrawMerging = true;
    /** Keep the shared index buffer bound in each region's VAO instead of rebinding every frame. */
    public boolean regionIndexBindCache = true;
    /** Repack fragmented region buffers (one region per frame) to give VRAM back. */
    public boolean arenaCompaction = true;
    /** Don't draw regions completely hidden by opaque fog (Nether, underwater). Check visually before using. */
    public boolean fogCulling = false;
    /** Build chunk meshes on Scandium's own thread pool instead of the one shared with world generation. */
    public boolean dedicatedMeshThreads = true;
    /** Mesh threads; 0 = automatic (cores - 2, between 2 and 6). Restart required. */
    public int meshThreadCount = 0;
    /** Spread uploads of finished chunk meshes over several frames instead of one spike. */
    public boolean chunkUploadBudget = true;
    public int chunkUploadBudgetMicros = 2000;
    public int chunkUploadMaxBudgetMicros = 8000;
    /** Upload queue length above which the budget grows towards the max. */
    public int chunkUploadBacklog = 96;
    public int minChunkUploadsPerFrame = 4;
    /** Max sections sent to (async) rebuild per frame; 0 = unlimited. */
    public int maxChunkRebuildsPerFrame = 64;
    /** Translucent sections farther than this (blocks) are not re-sorted on camera movement; 0 = vanilla. */
    public int translucentSortDistance = 128;
    /** Skip building geometry for particles outside the camera frustum. */
    public boolean particleCulling = true;
    /** Extra margin around the particle box (its quad can be larger than the collision box). */
    public double particleCullMargin = 4.0;
    /** Don't render entities in sections the vanilla occlusion graph considers invisible. */
    public boolean entityCulling = true;
    /** Same for block entities (chests, signs, banners...). */
    public boolean blockEntityCulling = true;

    // ---------------------------------------------------------------- physics (from Vodium, server)
    /** Skip the per-tick "which blocks is this entity inside" scan when all touched sections are air. */
    public boolean fastBlockCollision = true;
    /** Mobs without a target and without players nearby re-evaluate goals/paths/brains less often. */
    public boolean mobAiThrottle = true;
    public double mobAiActivationRange = 32.0;
    /** Throttled mobs think every N ticks (even number). */
    public int mobAiThrottleInterval = 8;

    // ---------------------------------------------------------------- experimental Vulkan backend (client)
    /**
     * Opt-in. Stage 1: initializes Vulkan on a background thread and runs a headless self-test; rendering still
     * uses OpenGL. See docs/VULKAN.md.
     */
    public boolean vulkanBackend = false;
    /**
     * Stage 4 debug mode: replay every game frame into an offscreen Vulkan frame while OpenGL keeps presenting.
     * Costs CPU per draw; /scandium vkshot saves the Vulkan frame next to a GL screenshot. Restart required.
     */
    public boolean vulkanShadowRender = false;
    /**
     * Stage 5: the window is shown by Vulkan (swapchain); OpenGL runs in a hidden context and the shadow renderer
     * provides the picture. Falls back to the GL picture (read back) while the Vulkan frame can't be complete, and
     * to a normal OpenGL window if anything fails at startup. Implies the shadow renderer. Restart required.
     */
    public boolean vulkanPresent = false;
    /**
     * Stage 7: while the window shows the Vulkan frame, OpenGL skips the draws and uploads the Vulkan frame already
     * covers (main-target draws, dynamic vertex data, terrain). Off = GL keeps doing everything (debugging).
     */
    public boolean vulkanSkipGl = true;
    /** Enable VK_LAYER_KHRONOS_validation (needs the Vulkan SDK installed) and log its messages. */
    public boolean vulkanValidation = false;

    // ---------------------------------------------------------------- client UI
    /** Show Scandium memory/GC statistics on the F3 screen. */
    public boolean debugOverlay = true;

    public static ScandiumConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        Path file = path();
        ScandiumConfig cfg = null;
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                cfg = GSON.fromJson(reader, ScandiumConfig.class);
            } catch (IOException | JsonParseException e) {
                Scandium.LOGGER.error("Failed to read {}, using defaults", file, e);
            }
        }
        if (cfg == null) {
            cfg = new ScandiumConfig();
        }
        cfg.sanitize();
        instance = cfg;
        cfg.save();
    }

    public void save() {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            Scandium.LOGGER.error("Failed to write {}", file, e);
        }
    }

    private void sanitize() {
        highPressureThreshold = clamp(highPressureThreshold, 0.5, 0.98);
        softMaxHeapFraction = softMaxHeapFraction <= 0 ? 0 : clamp(softMaxHeapFraction, 0.5, 1.0);
        minGarbageMbForIdleGc = Math.max(0, minGarbageMbForIdleGc);
        minSecondsBetweenGc = Math.max(5, minSecondsBetweenGc);
        idleDelaySeconds = Math.max(0, idleDelaySeconds);
        g1PeriodicGcIntervalSeconds = Math.max(0, g1PeriodicGcIntervalSeconds);
        meshThreadCount = clampInt(meshThreadCount, 0, 32);
        chunkUploadBudgetMicros = clampInt(chunkUploadBudgetMicros, 100, 50_000);
        chunkUploadMaxBudgetMicros = clampInt(chunkUploadMaxBudgetMicros, chunkUploadBudgetMicros, 50_000);
        chunkUploadBacklog = clampInt(chunkUploadBacklog, 0, 100_000);
        minChunkUploadsPerFrame = clampInt(minChunkUploadsPerFrame, 1, 1000);
        maxChunkRebuildsPerFrame = clampInt(maxChunkRebuildsPerFrame, 0, 10_000);
        translucentSortDistance = clampInt(translucentSortDistance, 0, 2048);
        particleCullMargin = clamp(particleCullMargin, 0, 64);
        mobAiActivationRange = clamp(mobAiActivationRange, 0, 512);
        // Vanilla already re-evaluates goals only every second tick; the interval has to be even.
        mobAiThrottleInterval = Math.max(2, clampInt(mobAiThrottleInterval, 2, 200) & ~1);
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // Runtime checks: everything also obeys the /scandium master switch.
    public boolean particleCullingActive() { return enabled && particleCulling; }
    public boolean entityCullingActive() { return enabled && entityCulling; }
    public boolean blockEntityCullingActive() { return enabled && blockEntityCulling; }
    public boolean chunkUploadBudgetActive() { return enabled && chunkUploadBudget; }
    public int rebuildLimitActive() { return enabled ? maxChunkRebuildsPerFrame : 0; }
    public int translucentSortDistanceActive() { return enabled ? translucentSortDistance : 0; }
    public boolean fastBlockCollisionActive() { return enabled && fastBlockCollision; }
    public boolean mobAiThrottleActive() { return enabled && mobAiThrottle && mobAiActivationRange > 0; }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("scandium.json");
    }
}
