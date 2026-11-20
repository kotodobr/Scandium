package dev.scandium.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.scandium.config.ScandiumConfig;
import dev.scandium.render.RenderStats;
import dev.scandium.render.VisibleSections;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips block entities in sections the occlusion graph considers invisible. Ported from Vodium. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullingMixin {
    @Shadow
    public abstract <E extends BlockEntity> BlockEntityRenderer<E> getRenderer(E blockEntity);

    @Inject(
            method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private <E extends BlockEntity> void scandium$cullBlockEntity(E blockEntity, float partialTick, PoseStack poseStack,
                                                                  MultiBufferSource buffers, CallbackInfo ci) {
        if (!ScandiumConfig.get().blockEntityCullingActive() || !VisibleSections.isReady()) {
            return;
        }
        BlockEntityRenderer<E> renderer = this.getRenderer(blockEntity);
        // Beacons and similar draw far outside their own section.
        if (renderer == null || renderer.shouldRenderOffScreen(blockEntity)) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        if (!VisibleSections.isSectionVisible(pos.getX(), pos.getY(), pos.getZ())) {
            RenderStats.blockEntityCulled();
            ci.cancel();
        }
    }
}
