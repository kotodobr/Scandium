package dev.scandium.mixin;

import dev.scandium.config.ScandiumConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Lets the user switch individual leak fixes off in {@code config/scandium.json}.
 */
public final class ScandiumMixinPlugin implements IMixinConfigPlugin {
    private static final String LEAK = "dev.scandium.mixin.leak.";
    private static final String RENDER = "dev.scandium.mixin.render.";
    private static final String PHYSICS = "dev.scandium.mixin.physics.";
    /** Mods that replace the vanilla section renderer; Scandium's render hooks would fight them (or cull wrongly). */
    private static final String[] RENDERER_REPLACEMENTS = {"sodium", "embeddium", "rubidium", "magnesium", "vodium"};
    /** Mods that optimize entity collisions / mob AI themselves. */
    private static final String[] PHYSICS_REPLACEMENTS = {"lithium", "canary", "radium", "vodium"};
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Scandium");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        ScandiumConfig cfg = ScandiumConfig.get();
        boolean optimization = mixinClassName.startsWith(LEAK) || mixinClassName.startsWith(RENDER)
                || mixinClassName.startsWith(PHYSICS);
        if (optimization && !cfg.enabled) {
            // Disabled via /scandium disable: run vanilla code after the restart. GC hooks, F3 and the command
            // itself stay so Scandium can be re-enabled.
            return false;
        }
        if (mixinClassName.startsWith(RENDER)) {
            String conflict = loadedMod(RENDERER_REPLACEMENTS);
            if (conflict != null) {
                LOGGER.info("Render optimization {} skipped: {} is installed", simpleName(mixinClassName), conflict);
                return false;
            }
        }
        if (mixinClassName.startsWith(PHYSICS)) {
            // Lithium optimizes entity collisions and mob AI itself; Vodium ships these exact mixins.
            String conflict = loadedMod(PHYSICS_REPLACEMENTS);
            if (conflict != null) {
                LOGGER.info("Physics optimization {} skipped: {} is installed", simpleName(mixinClassName), conflict);
                return false;
            }
        }
        if (mixinClassName.startsWith("dev.scandium.mixin.vulkan.shadow.")) {
            // Hooks on every GL state change and draw: only when the Vulkan shadow renderer is wanted
            // (directly, or because Vulkan presents the window and needs its frames).
            return cfg.vulkanBackend && (cfg.vulkanShadowRender || cfg.vulkanPresent);
        }
        if (mixinClassName.startsWith("dev.scandium.mixin.vulkan.present.")) {
            return cfg.vulkanBackend && cfg.vulkanPresent;
        }
        if (mixinClassName.startsWith(LEAK + "biome.")) return cfg.fixBiomeTemperatureCache;
        if (mixinClassName.startsWith(LEAK + "texture.")) return cfg.fixTextureReadLeak;
        if (mixinClassName.startsWith(LEAK + "screenshot.")) return cfg.fixHugeScreenshotLeak;
        if (mixinClassName.startsWith(LEAK + "client.")) return cfg.fixClientStaleReferences;
        return true;
    }

    private static String loadedMod(String... ids) {
        for (String id : ids) {
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(id)) {
                return id;
            }
        }
        return null;
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
