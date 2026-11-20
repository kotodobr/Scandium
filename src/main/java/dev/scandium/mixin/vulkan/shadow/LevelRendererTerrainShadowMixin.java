package dev.scandium.mixin.vulkan.shadow;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.scandium.vulkan.shadow.ShadowRenderer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Terrain layers into the Vulkan shadow frame, at the point where GL has the layer's shader set up. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTerrainShadowMixin {
    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Inject(method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderInstance;clear()V"))
    private void scandium$shadowTerrain(RenderType renderType, PoseStack poseStack, double camX, double camY,
                                        double camZ, Matrix4f projection, CallbackInfo ci) {
        ShadowRenderer.onTerrainLayer(renderType, camX, camY, camZ, this.visibleSections);
    }
}
