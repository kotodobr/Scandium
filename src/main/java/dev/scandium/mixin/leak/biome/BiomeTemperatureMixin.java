package dev.scandium.mixin.leak.biome;

import dev.scandium.leak.SharedTemperatureCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

/**
 * See {@link SharedTemperatureCache} for why the vanilla cache is replaced.
 */
@Mixin(Biome.class)
public abstract class BiomeTemperatureMixin {
    /** Never read: only exists so the vanilla field isn't null for mods that touch it reflectively. */
    @Unique
    private static final ThreadLocal<?> SCANDIUM$UNUSED_CACHE = new ThreadLocal<>();

    @Shadow
    private float getHeightAdjustedTemperature(BlockPos pos) {
        throw new AssertionError();
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Ljava/lang/ThreadLocal;withInitial(Ljava/util/function/Supplier;)Ljava/lang/ThreadLocal;")
    )
    private ThreadLocal<?> scandium$skipPerBiomeCache(Supplier<?> supplier) {
        return SCANDIUM$UNUSED_CACHE;
    }

    /**
     * @author Scandium
     * @reason Replace the per-biome, per-thread 1024-entry map with one small shared cache per thread.
     * An overwrite instead of a cancelling inject keeps this hot path free of callback allocations.
     */
    @Overwrite
    @Deprecated
    private float getTemperature(BlockPos pos) {
        Biome self = (Biome) (Object) this;
        long packed = pos.asLong();
        SharedTemperatureCache cache = SharedTemperatureCache.current();
        float value = cache.lookup(self, packed);
        if (Float.isNaN(value)) {
            value = this.getHeightAdjustedTemperature(pos);
            cache.store(self, packed, value);
        }
        return value;
    }
}
