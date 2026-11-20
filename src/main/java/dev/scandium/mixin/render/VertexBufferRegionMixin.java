package dev.scandium.mixin.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.scandium.render.region.RegionManager;
import dev.scandium.render.region.TerrainBufferSlot;
import dev.scandium.vulkan.shadow.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends a section's vertices into its region's shared buffer instead of its own VBO. The vanilla upload is
 * cancelled entirely - keeping both copies would double the terrain's VRAM. Ported from Vodium.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferRegionMixin implements TerrainBufferSlot {
    @Unique
    private int scandium$sectionX;
    @Unique
    private int scandium$sectionY;
    @Unique
    private int scandium$sectionZ;
    @Unique
    private int scandium$layerIndex = -1;

    @Override
    public void scandium$setTerrainSlot(int sectionX, int sectionY, int sectionZ, int layerIndex) {
        this.scandium$sectionX = sectionX;
        this.scandium$sectionY = sectionY;
        this.scandium$sectionZ = sectionZ;
        this.scandium$layerIndex = layerIndex;
    }

    @Override
    public int scandium$getSectionX() {
        return this.scandium$sectionX;
    }

    @Override
    public int scandium$getSectionY() {
        return this.scandium$sectionY;
    }

    @Override
    public int scandium$getSectionZ() {
        return this.scandium$sectionZ;
    }

    @Override
    public int scandium$getLayerIndex() {
        return this.scandium$layerIndex;
    }

    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void scandium$uploadToRegion(BufferBuilder.RenderedBuffer rendered, CallbackInfo ci) {
        if (!RegionManager.isManaged(this.scandium$layerIndex)) {
            return;
        }
        BufferBuilder.DrawState state = rendered.drawState();
        boolean translucent = this.scandium$layerIndex == RegionManager.TRANSLUCENT_LAYER_INDEX;

        // Opaque layers must use the shared sequential index buffer (baseVertex relies on it); translucent must
        // bring its own sorted indices. Anything else goes the vanilla way. Must match the rebase condition in
        // SectionRenderDispatcherMixin exactly, otherwise geometry would be shifted.
        if (state.sequentialIndex() == translucent) {
            // Vanilla path with section-relative vertices (not rebased, see SectionRenderDispatcherMixin).
            ShadowRenderer.onTerrainUpload(this.scandium$sectionX, this.scandium$sectionY, this.scandium$sectionZ,
                    this.scandium$layerIndex, rendered, false);
            if (ShadowRenderer.glOff()) {
                rendered.release();
                ci.cancel();
            }
            return;
        }

        // The Vulkan shadow renderer (if on) copies the same, already region-relative data.
        ShadowRenderer.onTerrainUpload(this.scandium$sectionX, this.scandium$sectionY, this.scandium$sectionZ,
                this.scandium$layerIndex, rendered, true);
        if (ShadowRenderer.glOff()) {
            // Vulkan draws the terrain: the GL region arenas would only be written, never read (stage 7).
            rendered.release();
            ci.cancel();
            return;
        }
        try {
            if (state.indexOnly()) {
                // Re-sort after the camera moved: same vertices, new face order.
                if (!translucent) {
                    return;
                }
                RegionManager.uploadTranslucentIndices(this.scandium$sectionX, this.scandium$sectionY,
                        this.scandium$sectionZ, rendered.indexBuffer(), state.indexType(), state.indexCount());
            } else {
                RegionManager.upload(this.scandium$sectionX, this.scandium$sectionY, this.scandium$sectionZ,
                        this.scandium$layerIndex, state.format(), rendered.vertexBuffer(), state.vertexCount(),
                        state.indexCount());
                // Order matters: the vertices create the section's slot that the indices go into.
                if (translucent) {
                    RegionManager.uploadTranslucentIndices(this.scandium$sectionX, this.scandium$sectionY,
                            this.scandium$sectionZ, rendered.indexBuffer(), state.indexType(), state.indexCount());
                }
            }
            ci.cancel();
        } finally {
            // Vanilla releases in its finally block; since it's cancelled, we must, or the builder never returns.
            if (ci.isCancelled()) {
                rendered.release();
            }
        }
    }
}
