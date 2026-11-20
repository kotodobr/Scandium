package dev.scandium.mixin.vulkan.shadow;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.scandium.vulkan.shadow.ShadowVertexBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Terrain section buffers are drawn by their own path (stage 6); the shadow renderer leaves them alone. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionTerrainMixin {
    @Shadow
    @Final
    private Map<RenderType, VertexBuffer> buffers;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void scandium$markTerrain(CallbackInfo ci) {
        for (VertexBuffer buffer : this.buffers.values()) {
            ((ShadowVertexBuffer) buffer).scandium$mirror().terrain = true;
        }
    }
}
