package dev.scandium.mixin.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.scandium.config.ScandiumConfig;
import dev.scandium.render.region.RegionManager;
import dev.scandium.render.region.TerrainBufferSlot;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;
import dev.scandium.render.ChunkUploadBudget;
import dev.scandium.render.MeshWorkerPool;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;
import java.util.concurrent.Executor;

/** Dedicated mesh thread pool and time-budgeted GPU uploads. Ported from Vodium. */
@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMixin {
    @Shadow
    @Final
    private Queue<Runnable> toUpload;

    /**
     * Replaces the constructor argument rather than the field: the executor is final and vanilla also builds its
     * mailbox from it, so both end up on the same pool.
     */
    @ModifyVariable(method = "<init>", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private static Executor scandium$dedicatedMeshPool(Executor vanilla) {
        return MeshWorkerPool.get(vanilla);
    }

    /**
     * Rebases vertex positions to the region corner here, on the mesh worker thread, so the render thread only
     * copies bytes. The condition must match VertexBufferRegionMixin exactly, or the section would be shifted.
     */
    @Inject(method = "uploadSectionLayer", at = @At("HEAD"))
    private void scandium$rebaseOffThread(BufferBuilder.RenderedBuffer rendered, VertexBuffer buffer,
                                          CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        int layerIndex = ((TerrainBufferSlot) buffer).scandium$getLayerIndex();
        if (!RegionManager.isManaged(layerIndex)) {
            return;
        }
        BufferBuilder.DrawState state = rendered.drawState();
        // Index-only re-sorts bring no vertices.
        if (state.indexOnly() || state.vertexCount() == 0) {
            return;
        }
        if (state.sequentialIndex() == (layerIndex == RegionManager.TRANSLUCENT_LAYER_INDEX)) {
            return;
        }
        TerrainBufferSlot slot = (TerrainBufferSlot) buffer;
        RegionManager.rebaseForRegion(slot.scandium$getSectionX(), slot.scandium$getSectionY(),
                slot.scandium$getSectionZ(), state.format(), rendered.vertexBuffer(), state.vertexCount());
    }

    /** Only caller is LevelRenderer.compileSections, once per frame on the render thread. */
    @Inject(method = "uploadAllPendingUploads", at = @At("HEAD"), cancellable = true)
    private void scandium$budgetedUpload(CallbackInfo ci) {
        if (ScandiumConfig.get().chunkUploadBudgetActive()) {
            ChunkUploadBudget.drain(this.toUpload);
            ci.cancel();
        }
    }
}
