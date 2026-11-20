package dev.scandium.mixin.render;

import dev.scandium.config.ScandiumConfig;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Limits how many sections are sent to (asynchronous) rebuild per frame.
 * <p>
 * After a teleport or when flying into new terrain, vanilla schedules hundreds of rebuilds in one go: the
 * builder queue clogs, then the upload queue arrives in one burst. Deferred sections keep their dirty flag and
 * go next frame - only the peak is spread out.
 * <p>
 * Two calls have to be intercepted: right after rebuildSectionAsync vanilla calls setNotDirty. Skipping only
 * the scheduling would leave the section without geometry forever. Ported from Vodium.
 */
@Mixin(LevelRenderer.class)
public abstract class SectionRebuildBudgetMixin {
    @Unique
    private int scandium$scheduledThisFrame;
    @Unique
    private boolean scandium$skippedLast;

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void scandium$resetBudget(CallbackInfo ci) {
        this.scandium$scheduledThisFrame = 0;
        this.scandium$skippedLast = false;
    }

    @Redirect(
            method = "compileSections",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;rebuildSectionAsync(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V")
    )
    private void scandium$limitScheduling(SectionRenderDispatcher.RenderSection section,
                                          SectionRenderDispatcher dispatcher, RenderRegionCache cache) {
        int limit = ScandiumConfig.get().rebuildLimitActive();
        if (limit > 0 && this.scandium$scheduledThisFrame >= limit) {
            this.scandium$skippedLast = true;
            return;
        }
        this.scandium$skippedLast = false;
        this.scandium$scheduledThisFrame++;
        section.rebuildSectionAsync(dispatcher, cache);
    }

    /**
     * Also matches the setNotDirty after the synchronous "build near" path, which runs before the async loop
     * while {@code skippedLast} is still false, so it is unaffected.
     */
    @Redirect(
            method = "compileSections",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setNotDirty()V")
    )
    private void scandium$keepDirtyForSkipped(SectionRenderDispatcher.RenderSection section) {
        if (this.scandium$skippedLast) {
            this.scandium$skippedLast = false;
            return;
        }
        section.setNotDirty();
    }
}
