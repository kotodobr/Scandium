package dev.scandium.vulkan;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.scandium.Scandium;
import dev.scandium.config.ScandiumConfig;
import dev.scandium.mixin.vulkan.GameRendererAccessor;
import dev.scandium.vulkan.shader.GlslTranslator;
import dev.scandium.vulkan.shader.PipelineState;
import dev.scandium.vulkan.shader.ShaderPipelineSet;
import dev.scandium.vulkan.shader.ShaderSourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point of the experimental Vulkan backend (opt-in: {@code vulkanBackend} in config/scandium.json).
 * <p>
 * Stages 1-3: core, resources and shader translation. The runtime lives on its own thread for the whole session;
 * after every resource reload all of the game's core shaders are translated and built into Vulkan pipelines.
 * Rendering still uses OpenGL. See docs/VULKAN.md.
 * <p>
 * Written from scratch for Scandium; no code from other Vulkan Minecraft projects is used.
 */
public final class VulkanBackend {
    private static volatile String status = "off (vulkanBackend=false)";
    private static volatile String shaderStatus = "";
    private static ExecutorService executor;
    /** Only touched on the executor thread. */
    private static VulkanRuntime runtime;
    private static ShaderPipelineSet pipelines;
    /** Translated shaders by name, for the render thread (shadow renderer). Replaced as a whole on reload. */
    private static volatile Map<String, GlslTranslator.Result> translations = Map.of();
    private static volatile int pipelineGeneration;
    private static final CompletableFuture<VulkanRuntime> READY = new CompletableFuture<>();

    private VulkanBackend() {
    }

    public static String status() {
        return shaderStatus.isEmpty() ? status : status + ", " + shaderStatus;
    }

    /** Called once on client start. Never throws: any failure just leaves the game on OpenGL. */
    public static synchronized void initAsync() {
        ScandiumConfig cfg = ScandiumConfig.get();
        if (!cfg.vulkanBackend || executor != null) {
            return;
        }
        status = "starting...";
        Path cacheFile = FabricLoader.getInstance().getGameDir().resolve("scandium").resolve("vulkan-pipeline-cache.bin");
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(() -> {
                LwjglStack.enlargeForCurrentThread(1 << 20);
                r.run();
            }, "Scandium Vulkan");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(() -> {
            long start = System.nanoTime();
            try {
                runtime = VulkanRuntime.create(cfg.vulkanValidation, cacheFile);
                VulkanSelfTest.run(runtime);
                READY.complete(runtime);
                long ms = (System.nanoTime() - start) / 1_000_000;
                status = runtime.context.deviceName + ", self-test OK (" + ms + " ms)";
                Scandium.LOGGER.info("Vulkan backend: {} - self-test passed in {} ms. Pipeline cache: {} bytes loaded. "
                                + "Memory: {}. Rendering stays on OpenGL for now.",
                        runtime.context.describe(), ms, runtime.pipelineCache.loadedBytes, runtime.allocator.stats());
            } catch (Throwable t) {
                status = "failed: " + t.getMessage();
                Scandium.LOGGER.warn("Vulkan backend unavailable, staying on OpenGL", t);
                if (runtime != null) {
                    runtime.close();
                    runtime = null;
                }
                READY.complete(null);
            }
        });
    }

    /** Blocks until the runtime finished initializing; null if Vulkan is unavailable or disabled. */
    public static VulkanRuntime awaitRuntime() {
        if (executor == null) {
            return null;
        }
        try {
            return READY.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Scandium.LOGGER.warn("Vulkan runtime did not become ready", e);
            return null;
        }
    }

    public static GlslTranslator.Result translation(String shaderName) {
        return translations.get(shaderName);
    }

    public static int pipelineGeneration() {
        return pipelineGeneration;
    }

    /**
     * Render thread, after every resource reload: snapshot the shaders the game just loaded (sources resolved
     * through resource packs, vertex formats, chunk layer states) and rebuild the Vulkan pipelines from them.
     */
    public static void onResourcesReloaded(Minecraft mc) {
        if (executor == null) {
            return;
        }
        List<ShaderPipelineSet.Job> jobs = new ArrayList<>();
        List<String> loadFailures = new ArrayList<>();
        try {
            Map<String, ShaderInstance> shaders = ((GameRendererAccessor) mc.gameRenderer).scandium$getShaders();
            int triangles = PipelineState.topologyFromGl(VertexFormat.Mode.QUADS.asGLMode);
            for (ShaderInstance instance : shaders.values()) {
                addJob(mc, jobs, loadFailures, instance.getName(), instance, PipelineState.opaque(triangles));
            }
            // Chunk layers with their real fixed-function state (blend, depth, cull) - what terrain will use.
            for (RenderType layer : RenderType.chunkBufferLayers()) {
                String description = layer.toString();
                String layerName = description.substring(description.indexOf('[') + 1, description.indexOf(':'));
                ShaderInstance instance = mc.gameRenderer.getShader("rendertype_" + layerName);
                if (instance != null) {
                    addJob(mc, jobs, loadFailures, "layer/" + layerName, instance,
                            PipelineState.fromRenderType(description, PipelineState.topologyFromGl(layer.mode().asGLMode)));
                }
            }
        } catch (RuntimeException e) {
            Scandium.LOGGER.warn("Vulkan: could not snapshot shaders", e);
            return;
        }
        executor.execute(() -> rebuildPipelines(jobs, loadFailures));
    }

    private static void addJob(Minecraft mc, List<ShaderPipelineSet.Job> jobs, List<String> failures, String jobName,
                               ShaderInstance instance, PipelineState state) {
        try {
            ShaderSourceLoader.CoreShader sources = ShaderSourceLoader.load(mc.getResourceManager(), instance.getName());
            VertexFormat format = instance.getVertexFormat();
            jobs.add(new ShaderPipelineSet.Job(jobName, sources, format, format.getElementAttributeNames(), state));
        } catch (Exception e) {
            failures.add(jobName + ": " + e.getMessage());
        }
    }

    private static void rebuildPipelines(List<ShaderPipelineSet.Job> jobs, List<String> loadFailures) {
        if (runtime == null) {
            return;
        }
        try {
            if (pipelines != null) {
                pipelines.close();
                pipelines = null;
            }
            List<ShaderPipelineSet.Report> reports = new ArrayList<>();
            pipelines = ShaderPipelineSet.build(runtime, jobs, reports);
            Map<String, GlslTranslator.Result> translated = new java.util.HashMap<>();
            for (ShaderPipelineSet.Job job : jobs) {
                ShaderPipelineSet.Entry entry = pipelines.get(job.name());
                if (entry != null && !job.name().startsWith("layer/")) {
                    translated.put(job.name(), entry.shader());
                }
            }
            translations = Map.copyOf(translated);
            pipelineGeneration++;
            ShaderPipelineSet.Report report = reports.get(0);
            List<String> failures = new ArrayList<>(loadFailures);
            failures.addAll(report.failures());
            int total = report.total() + loadFailures.size();
            shaderStatus = "shaders " + report.built() + "/" + total;
            Scandium.LOGGER.info("Vulkan: translated and built {} of {} shader pipelines in {} ms (pipeline cache: {})",
                    report.built(), total, report.millis(), runtime.pipelineCache.loadedBytes > 0 ? "warm" : "cold");
            for (String failure : failures) {
                Scandium.LOGGER.warn("Vulkan shader failed: {}", failure);
            }
            for (String warning : report.warnings()) {
                Scandium.LOGGER.debug("Vulkan shader warning: {}", warning);
            }
            if (!report.warnings().isEmpty()) {
                Scandium.LOGGER.info("Vulkan: {} shader translation warnings (unused inputs etc.), see debug log",
                        report.warnings().size());
            }
        } catch (Throwable t) {
            shaderStatus = "shader build failed";
            Scandium.LOGGER.warn("Vulkan: building shader pipelines failed", t);
        }
    }
}
