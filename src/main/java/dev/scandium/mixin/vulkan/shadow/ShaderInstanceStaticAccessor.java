package dev.scandium.mixin.vulkan.shadow;

import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's "program currently bound" cache; reset when GL starts drawing again after being skipped. */
@Mixin(ShaderInstance.class)
public interface ShaderInstanceStaticAccessor {
    @Accessor("lastProgramId")
    static void scandium$setLastProgramId(int programId) {
        throw new AssertionError();
    }
}
