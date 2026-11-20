package dev.scandium.mixin.vulkan;

import dev.scandium.vulkan.VulkanBackend;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After every resource reload (including the first one) the Vulkan pipelines are rebuilt from the new shaders. */
@Mixin(Minecraft.class)
public abstract class VulkanReloadMixin {
    @Inject(method = "onResourceLoadFinished", at = @At("HEAD"))
    private void scandium$rebuildVulkanPipelines(CallbackInfo ci) {
        VulkanBackend.onResourcesReloaded((Minecraft) (Object) this);
    }
}
