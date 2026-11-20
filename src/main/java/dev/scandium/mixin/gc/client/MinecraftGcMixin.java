package dev.scandium.mixin.gc.client;

import dev.scandium.gc.GcScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds client-side "good moments for a collection" into the {@link GcScheduler}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftGcMixin {
    @Shadow
    @Nullable
    public ClientLevel level;

    @Shadow
    public abstract boolean isPaused();

    @Inject(method = "tick", at = @At("HEAD"))
    private void scandium$tickScheduler(CallbackInfo ci) {
        GcScheduler scheduler = GcScheduler.get();
        // Paused singleplayer, or sitting in menus without a world: nobody notices a GC pause right now.
        scheduler.setIdle(GcScheduler.IdleSource.CLIENT_PAUSED, this.level == null || this.isPaused());
        scheduler.tick();
    }

    /** disconnect() is also called defensively before a world is created/joined; only count real world exits. */
    @Unique
    private boolean scandium$hadLevel;

    @Inject(method = {"disconnect(Lnet/minecraft/client/gui/screens/Screen;)V", "clearClientLevel"}, at = @At("HEAD"))
    private void scandium$rememberLevel(Screen screen, CallbackInfo ci) {
        this.scandium$hadLevel = this.level != null;
    }

    @Inject(method = {"disconnect(Lnet/minecraft/client/gui/screens/Screen;)V", "clearClientLevel"}, at = @At("TAIL"))
    private void scandium$gcAfterWorldLeave(Screen screen, CallbackInfo ci) {
        if (this.scandium$hadLevel) {
            // The whole client world (and in singleplayer the integrated server) just became garbage.
            GcScheduler.get().onEvent(GcScheduler.Reason.WORLD_LEAVE, 1000);
        }
        this.scandium$hadLevel = false;
    }

    @Inject(method = "onResourceLoadFinished", at = @At("HEAD"))
    private void scandium$gcAfterResourceReload(CallbackInfo ci) {
        // Previous models, atlases and sounds are now unreachable; the loading overlay is still fading out.
        GcScheduler.get().onEvent(GcScheduler.Reason.RESOURCE_RELOAD, 500);
    }
}
