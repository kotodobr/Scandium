package dev.scandium.mixin.client;

import dev.scandium.client.FrameBenchmark;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One call per rendered frame, feeding {@link FrameBenchmark}. */
@Mixin(Minecraft.class)
public abstract class FrameTimeMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void scandium$recordFrame(boolean renderLevel, CallbackInfo ci) {
        FrameBenchmark.onFrame();
        dev.scandium.vulkan.shadow.ShadowRenderer.onFrameBoundary();
    }
}
