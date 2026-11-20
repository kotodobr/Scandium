package dev.scandium.mixin.vulkan.shadow;

import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Sampler name to texture object (Integer id, AbstractTexture or RenderTarget), as set by the game. */
@Mixin(ShaderInstance.class)
public interface ShaderInstanceAccessor {
    @Accessor("samplerMap")
    Map<String, Object> scandium$getSamplerMap();
}
