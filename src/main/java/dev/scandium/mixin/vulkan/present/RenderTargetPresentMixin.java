package dev.scandium.mixin.vulkan.present;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.scandium.vulkan.present.VulkanPresent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * blitToScreen copies the main target into the default framebuffer. With Vulkan presenting, that framebuffer
 * belongs to the invisible GL window: the copy is never seen (the GL fallback reads the main target directly).
 */
@Mixin(RenderTarget.class)
public abstract class RenderTargetPresentMixin {
    @Inject(method = "blitToScreen(IIZ)V", at = @At("HEAD"), cancellable = true)
    private void scandium$skipInvisibleBlit(int width, int height, boolean disableBlend, CallbackInfo ci) {
        if (VulkanPresent.isTakeover()) {
            ci.cancel();
        }
    }
}
