package dev.scandium.client;

import dev.scandium.Scandium;
import dev.scandium.config.ScandiumConfig;
import net.minecraft.client.Minecraft;
import dev.scandium.gc.GcEnvironment;
import dev.scandium.gc.GcMonitor;
import dev.scandium.gc.GcScheduler;
import net.fabricmc.api.ClientModInitializer;

import java.util.List;
import java.util.Locale;

public final class ScandiumClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Everything else is driven by mixins; the GC scheduler itself is initialized by the common entrypoint.
        dev.scandium.vulkan.VulkanBackend.initAsync();
        if (ScandiumConfig.get().vulkanBackend
                && (ScandiumConfig.get().vulkanShadowRender || ScandiumConfig.get().vulkanPresent)) {
            // Wait for the runtime so textures created during startup are mirrored as well.
            dev.scandium.vulkan.VulkanRuntime runtime = dev.scandium.vulkan.VulkanBackend.awaitRuntime();
            if (runtime != null) {
                dev.scandium.vulkan.shadow.ShadowRenderer.activate(runtime);
            }
        }
        Scandium.clientToggleHook = () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.level != null) {
                    mc.levelRenderer.allChanged(); // re-decides region batching and rebuilds every section
                }
            });
        };
    }

    /** Lines appended to the left side of the F3 screen. */
    public static void appendDebugLines(List<String> lines) {
        if (!ScandiumConfig.get().debugOverlay) {
            return;
        }
        GcEnvironment env = GcEnvironment.get();
        GcMonitor monitor = GcMonitor.get();
        long live = monitor.heapAfterLastMajorGc() >= 0 ? monitor.heapAfterLastMajorGc() : monitor.heapAfterLastGc();
        lines.add("");
        if (!ScandiumConfig.get().enabled) {
            lines.add("[Scandium] DISABLED (/scandium enable)");
        }
        lines.add(String.format(Locale.ROOT, "[Scandium] GC: %s, %d collections, pause last %d / max %d ms",
                env.collector.displayName, monitor.collectionCount(),
                monitor.lastPauseMillis(), monitor.longestPauseMillis()));
        lines.add(String.format(Locale.ROOT, "[Scandium] Live set ~%s MB, garbage ~%d MB, alloc %.0f MB/s",
                live >= 0 ? Long.toString(live >> 20) : "?", monitor.estimatedGarbage() >> 20,
                monitor.allocationRateBytesPerSecond() / (1024 * 1024)));
        lines.add("[Scandium] Last scheduled GC: " + GcScheduler.get().lastCollectionSummary());
        lines.add("[Scandium] Render " + dev.scandium.render.RenderStats.summary());
        if (ScandiumConfig.get().vulkanBackend) {
            lines.add("[Scandium] Vulkan: " + dev.scandium.vulkan.VulkanBackend.status());
            if (dev.scandium.vulkan.shadow.ShadowRenderer.isActive()
                    || !dev.scandium.vulkan.shadow.ShadowRenderer.stats().isEmpty()) {
                lines.add("[Scandium] Vulkan shadow frame: " + dev.scandium.vulkan.shadow.ShadowRenderer.stats());
            }
            if (dev.scandium.vulkan.present.VulkanPresent.isTakeover()) {
                lines.add("[Scandium] Window presented by Vulkan, picture from: "
                        + dev.scandium.vulkan.shadow.ShadowRenderer.presentSource()
                        + " (/scandium present vulkan|opengl)");
            }
        }
        if (dev.scandium.render.region.RegionManager.isActive()) {
            lines.add("[Scandium] " + dev.scandium.render.RenderStats.regionSummary());
        }
    }
}
