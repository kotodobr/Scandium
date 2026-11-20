package dev.scandium.mixin.render;

import dev.scandium.config.ScandiumConfig;
import dev.scandium.render.CameraFrustum;
import dev.scandium.render.RenderStats;
import dev.scandium.render.VisibleSections;
import dev.scandium.render.region.RegionManager;
import dev.scandium.render.region.RegionRenderer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Guardian;
import com.mojang.blaze3d.vertex.PoseStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region batching, occlusion culling of entities and block entities, frustum capture for particle culling, and a
 * distance limit for translucent re-sorting. Ported from Vodium (Yarn to Mojang names).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCullingMixin {
    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    @Nullable
    private ClientLevel level;

    @Unique
    private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> SCANDIUM$EMPTY = new ObjectArrayList<>(0);

    /**
     * One pass over the visible sections feeds both the culling set and the region batches. Vanilla only
     * rebuilds {@code visibleSections} in applyFrustum, so the derived sets are only rebuilt when that (or a
     * section/region change) happened.
     */
    @Inject(method = "setupRender", at = @At("RETURN"))
    private void scandium$collectVisibleSections(Camera camera, Frustum frustum, boolean hasCapturedFrustum,
                                                 boolean isSpectator, CallbackInfo ci) {
        // Captured before any early exit: particles are culled against it even when section culling is off.
        CameraFrustum.set(frustum, hasCapturedFrustum);
        RenderStats.beginFrame();

        ScandiumConfig cfg = ScandiumConfig.get();
        boolean culling = cfg.entityCullingActive() || cfg.blockEntityCullingActive();
        boolean regions = RegionManager.isActive();
        if (regions) {
            RegionManager.maintain(); // at most one region compaction per frame
        }
        if (!culling) {
            VisibleSections.reset();
        }
        if ((!culling && !regions) || this.level == null || !VisibleSections.consumeDirty()) {
            return;
        }

        if (regions) {
            RegionManager.beginFrame();
        }
        if (culling) {
            VisibleSections.begin(this.level.getMinSection(), this.level.getMaxSection() - 1);
        }
        ObjectArrayList<SectionRenderDispatcher.RenderSection> sections = this.visibleSections;
        for (int i = 0, size = sections.size(); i < size; i++) {
            BlockPos origin = sections.get(i).getOrigin();
            int x = SectionPos.blockToSectionCoord(origin.getX());
            int y = SectionPos.blockToSectionCoord(origin.getY());
            int z = SectionPos.blockToSectionCoord(origin.getZ());
            if (culling) {
                VisibleSections.add(x, y, z);
            }
            if (regions) {
                RegionManager.addVisibleSection(x, y, z);
            }
        }
        if (regions) {
            // Merging looks at buffer neighbours, so it needs the complete visible set of each region.
            RegionManager.endFrame();
        }
        if (culling) {
            VisibleSections.end();
        }
    }

    /** The only place vanilla rebuilds {@code visibleSections}. */
    @Inject(method = "applyFrustum", at = @At("TAIL"))
    private void scandium$onFrustumApplied(Frustum frustum, CallbackInfo ci) {
        VisibleSections.markDirty();
    }

    /**
     * The only point where everything about a section is known at once: vanilla calls this right after the new
     * compiled data was set, i.e. after all of the section's uploads finished. Freeing region slots of layers the
     * section no longer has must happen here, not during the frame (the old data would make a fresh slot look
     * stale and leave holes in the terrain).
     */
    @Inject(method = "addRecentlyCompiledSection", at = @At("HEAD"))
    private void scandium$onSectionCompiled(SectionRenderDispatcher.RenderSection section, CallbackInfo ci) {
        VisibleSections.markDirty();
        boolean shadow = dev.scandium.vulkan.shadow.ShadowRenderer.isActive();
        if (!RegionManager.isActive() && !shadow) {
            return;
        }
        int layerMask = 0;
        for (RenderType layer : ((CompiledSectionAccessor) section.getCompiled()).scandium$getNonEmptyLayers()) {
            int index = RegionManager.layerIndex(layer);
            if (index >= 0) {
                layerMask |= 1 << index;
            }
        }
        BlockPos origin = section.getOrigin();
        int x = SectionPos.blockToSectionCoord(origin.getX());
        int y = SectionPos.blockToSectionCoord(origin.getY());
        int z = SectionPos.blockToSectionCoord(origin.getZ());
        if (RegionManager.isActive()) {
            RegionManager.syncSectionLayers(x, y, z, layerMask);
        }
        dev.scandium.vulkan.shadow.ShadowRenderer.onSectionLayers(x, y, z, layerMask);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void scandium$onLevelChange(@Nullable ClientLevel level, CallbackInfo ci) {
        CameraFrustum.clear();
        VisibleSections.invalidate();
        RegionManager.clear();
        RegionManager.refreshActive();
        dev.scandium.vulkan.shadow.ShadowRenderer.clearTerrain();
    }

    /** All sections are about to be rebuilt: the only safe moment to switch region batching on or off. */
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void scandium$onAllChanged(CallbackInfo ci) {
        VisibleSections.invalidate();
        RegionManager.clear();
        RegionManager.refreshActive();
        dev.scandium.vulkan.shadow.ShadowRenderer.clearTerrain();
    }

    /** Layers drawn by regions get an empty section list, so the vanilla per-section loop draws nothing. */
    @Redirect(
            method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;listIterator(I)Lit/unimi/dsi/fastutil/objects/ObjectListIterator;", remap = false)
    )
    private ObjectListIterator<SectionRenderDispatcher.RenderSection> scandium$skipManagedLayer(
            ObjectArrayList<SectionRenderDispatcher.RenderSection> list, int index, RenderType renderType,
            PoseStack poseStack, double camX, double camY, double camZ, org.joml.Matrix4f projection) {
        if (RegionManager.isManaged(RegionManager.layerIndex(renderType))
                || dev.scandium.vulkan.shadow.ShadowRenderer.glOff()) {
            return SCANDIUM$EMPTY.listIterator(0);
        }
        return list.listIterator(index);
    }

    /** Draws the regions at the end of renderSectionLayer: vanilla shader setup is done, its cleanup follows. */
    @Inject(
            method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderInstance;clear()V")
    )
    private void scandium$drawRegions(RenderType renderType, PoseStack poseStack, double camX, double camY,
                                      double camZ, org.joml.Matrix4f projection, CallbackInfo ci) {
        int layerIndex = RegionManager.layerIndex(renderType);
        if (RegionManager.isManaged(layerIndex) && !dev.scandium.vulkan.shadow.ShadowRenderer.glOff()) {
            RegionRenderer.drawLayer(layerIndex, camX, camY, camZ);
        }
    }

    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void scandium$cullEntity(Entity entity, double camX, double camY, double camZ, float partialTick,
                                     PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
        if (!ScandiumConfig.get().entityCullingActive() || !VisibleSections.isReady()
                || scandium$alwaysRender(entity)) {
            return;
        }
        if (!VisibleSections.intersectsVisible(entity.getBoundingBox())) {
            RenderStats.entityCulled();
            ci.cancel();
        }
    }

    /** Entities that draw outside their box or are meant to be seen through walls. */
    @Unique
    private static boolean scandium$alwaysRender(Entity entity) {
        return entity.noCulling
                || entity.isCurrentlyGlowing()
                || entity.isVehicle()
                || entity instanceof EnderDragon
                || entity instanceof EndCrystal
                || entity instanceof Guardian;
    }

    /**
     * Don't re-sort translucent faces of far sections. When the camera moves a block, vanilla walks all visible
     * sections and schedules up to 15 re-sorts; for a section 100 blocks away the face order doesn't change.
     * Returning false is resortTransparency's normal "nothing to do" answer, so vanilla's budget of 15 goes to
     * the near sections where it matters.
     */
    @Redirect(
            method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;resortTransparency(Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;)Z")
    )
    private boolean scandium$limitSortDistance(SectionRenderDispatcher.RenderSection section, RenderType type,
                                               SectionRenderDispatcher dispatcher, RenderType renderType,
                                               PoseStack poseStack, double camX, double camY, double camZ,
                                               org.joml.Matrix4f projection) {
        int limit = ScandiumConfig.get().translucentSortDistanceActive();
        if (limit > 0) {
            BlockPos origin = section.getOrigin();
            double dx = origin.getX() + 8.0 - camX;
            double dy = origin.getY() + 8.0 - camY;
            double dz = origin.getZ() + 8.0 - camZ;
            double reach = limit + 14.0; // half the section diagonal: the limit is meant for geometry
            if (dx * dx + dy * dy + dz * dz > reach * reach) {
                return false;
            }
        }
        return section.resortTransparency(type, dispatcher);
    }
}
