package dev.scandium.mixin.leak.client;

import dev.scandium.config.ScandiumConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the previous world alive after leaving it.
 * <p>
 * {@code crosshairPickEntity} and {@code hitResult} are only refreshed while a world is loaded. After
 * disconnecting they keep pointing at the last targeted entity (and through it, at its level, chunks and every
 * entity in it) until the next world is joined - a whole world's worth of memory that the GC cannot free while the
 * player sits in the menu.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftStaleReferencesMixin {
    @Shadow
    @Nullable
    public Entity crosshairPickEntity;

    @Shadow
    @Nullable
    public HitResult hitResult;

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void scandium$dropTargetsOnDisconnect(Screen screen, CallbackInfo ci) {
        if (!ScandiumConfig.get().enabled) return;
        this.crosshairPickEntity = null;
        this.hitResult = null;
    }

    @Inject(method = "clearClientLevel", at = @At("TAIL"))
    private void scandium$dropTargetsOnLevelClear(Screen screen, CallbackInfo ci) {
        if (!ScandiumConfig.get().enabled) return;
        this.crosshairPickEntity = null;
        this.hitResult = null;
    }
}
