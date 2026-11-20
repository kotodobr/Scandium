package dev.scandium.mixin.render;

import com.mojang.blaze3d.shaders.Uniform;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla's {@code ShaderInstance.apply()} runs before every draw and, for every sampler, asks the driver for the
 * uniform location by name ({@code glGetUniformLocation}, a string lookup in the driver) and uploads the texture
 * unit again ({@code glUniform1i}). Both are fixed once the program is linked: the location never changes and the
 * unit for a sampler is always its index. Profiling Scandium showed these calls taking ~70% of the render
 * thread's native time with the Vulkan backend, and they cost the plain OpenGL path as well.
 * <p>
 * Locations are cached per shader instance (a reload creates new instances), and a unit is only uploaded when it
 * differs from what this program already holds - uniform values are program state in GL and persist.
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderSamplerCacheMixin {
    /**
     * Created lazily, not with a field initializer: apply() can run before the constructor's injected
     * initializers (it crashed with a NullPointerException on the loading overlay).
     */
    @Unique
    private Object2IntOpenHashMap<String> scandium$samplerLocations;
    @Unique
    private Int2IntOpenHashMap scandium$uploadedUnits;

    @Unique
    private static Object2IntOpenHashMap<String> scandium$newLocationMap() {
        Object2IntOpenHashMap<String> map = new Object2IntOpenHashMap<>();
        map.defaultReturnValue(Integer.MIN_VALUE);
        return map;
    }

    @Unique
    private static Int2IntOpenHashMap scandium$newUnitMap() {
        Int2IntOpenHashMap map = new Int2IntOpenHashMap();
        map.defaultReturnValue(Integer.MIN_VALUE);
        return map;
    }

    @Redirect(method = "apply", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Uniform;glGetUniformLocation(ILjava/lang/CharSequence;)I"))
    private int scandium$cachedLocation(int program, CharSequence name) {
        if (this.scandium$samplerLocations == null) {
            this.scandium$samplerLocations = scandium$newLocationMap();
        }
        String key = name.toString();
        int location = this.scandium$samplerLocations.getInt(key);
        if (location == Integer.MIN_VALUE) {
            location = Uniform.glGetUniformLocation(program, name);
            this.scandium$samplerLocations.put(key, location);
        }
        return location;
    }

    @Redirect(method = "apply", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Uniform;uploadInteger(II)V"))
    private void scandium$uploadUnitOnce(int location, int unit) {
        if (location < 0) {
            return; // sampler optimized out of the program: GL ignores location -1 anyway
        }
        if (this.scandium$uploadedUnits == null) {
            this.scandium$uploadedUnits = scandium$newUnitMap();
        }
        if (this.scandium$uploadedUnits.get(location) != unit) {
            Uniform.uploadInteger(location, unit); // the program is bound by apply() at this point
            this.scandium$uploadedUnits.put(location, unit);
        }
    }
}
