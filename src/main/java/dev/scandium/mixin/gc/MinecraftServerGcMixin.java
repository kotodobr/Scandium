package dev.scandium.mixin.gc;

import dev.scandium.gc.GcScheduler;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerGcMixin {
    @Shadow
    public abstract boolean isDedicatedServer();

    @Shadow
    public abstract int getPlayerCount();

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void scandium$tickScheduler(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        // The integrated server shares the JVM with the client, which drives the scheduler itself.
        if (this.isDedicatedServer()) {
            GcScheduler scheduler = GcScheduler.get();
            scheduler.setIdle(GcScheduler.IdleSource.SERVER_EMPTY, this.getPlayerCount() == 0);
            scheduler.tick();
        }
    }

    @Inject(method = "reloadResources", at = @At("RETURN"))
    private void scandium$gcAfterDataReload(Collection<String> packs, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        // Old recipes, loot tables, advancements, tags... become garbage once /reload completes.
        cir.getReturnValue().thenRun(() -> GcScheduler.get().onEvent(GcScheduler.Reason.DATA_RELOAD, 500));
    }
}
