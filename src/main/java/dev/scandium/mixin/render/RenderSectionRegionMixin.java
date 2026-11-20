package dev.scandium.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.scandium.render.region.RegionManager;
import dev.scandium.render.region.TerrainBufferSlot;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tags section buffers with their coordinates and layer, and gives region space back when a section is
 * released or moved. Ported from Vodium.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionRegionMixin {
    @Shadow
    public abstract BlockPos getOrigin();

    /**
     * getBuffer is called exactly where the upload is queued, so the coordinates are taken while they still
     * match the built geometry. Sections are reused with a new origin as the player moves; reading the origin
     * later, during the upload itself, would race.
     */
    @Inject(method = "getBuffer", at = @At("RETURN"))
    private void scandium$tagBuffer(RenderType layer, CallbackInfoReturnable<VertexBuffer> cir) {
        VertexBuffer buffer = cir.getReturnValue();
        // Tagged even without region batching: the Vulkan shadow renderer needs the section of every terrain buffer.
        if (buffer == null) {
            return;
        }
        BlockPos origin = this.getOrigin();
        ((TerrainBufferSlot) buffer).scandium$setTerrainSlot(SectionPos.blockToSectionCoord(origin.getX()),
                SectionPos.blockToSectionCoord(origin.getY()), SectionPos.blockToSectionCoord(origin.getZ()),
                RegionManager.layerIndex(layer));
    }

    @Inject(method = "releaseBuffers", at = @At("HEAD"))
    private void scandium$freeOnRelease(CallbackInfo ci) {
        this.scandium$free();
    }

    /** Sections are reused with a new origin: free before the coordinates change, or the old slot leaks. */
    @Inject(method = "setOrigin", at = @At("HEAD"))
    private void scandium$freeOnMove(int x, int y, int z, CallbackInfo ci) {
        BlockPos origin = this.getOrigin();
        if (origin.getX() != x || origin.getY() != y || origin.getZ() != z) {
            this.scandium$free();
        }
    }

    @Unique
    private void scandium$free() {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        BlockPos origin = this.getOrigin();
        int x = SectionPos.blockToSectionCoord(origin.getX());
        int y = SectionPos.blockToSectionCoord(origin.getY());
        int z = SectionPos.blockToSectionCoord(origin.getZ());
        RegionManager.freeSection(x, y, z);
        dev.scandium.vulkan.shadow.ShadowRenderer.onSectionFreed(x, y, z);
    }
}
