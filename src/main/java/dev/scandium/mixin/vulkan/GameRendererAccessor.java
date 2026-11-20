package dev.scandium.mixin.vulkan;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The game's loaded core shaders, by name. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("shaders")
    Map<String, ShaderInstance> scandium$getShaders();
}
