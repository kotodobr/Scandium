package dev.scandium.mixin.vulkan.shadow;

import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.scandium.vulkan.shadow.ShadowRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stage 8: while the Vulkan frame is shown, a draw into the main target never reaches GL - so the GL side of
 * {@code ShaderInstance.apply()} (binding the program, binding textures to units, uploading every uniform) is
 * wasted. The Vulkan copy reads uniform values from the Uniform objects and textures from the sampler map, both
 * still set by the game. BlendMode.apply stays: it changes blend state the Vulkan pipelines are built from.
 * <p>
 * Vanilla's Uniform.upload() has no effective dirty check, so values skipped here are simply uploaded at the next
 * apply once GL draws again; the "program already bound" cache is reset at that moment (ShadowRenderer).
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceGlOffMixin {
    @Redirect(method = "apply", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/ProgramManager;glUseProgram(I)V"))
    private void scandium$useProgram(int program) {
        if (!ShadowRenderer.glOffForMainTarget()) {
            ProgramManager.glUseProgram(program);
        }
    }

    @Redirect(method = "apply", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;activeTexture(I)V"))
    private void scandium$activeTexture(int unit) {
        if (!ShadowRenderer.glOffForMainTarget()) {
            RenderSystem.activeTexture(unit);
        }
    }

    @Redirect(method = "apply", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;bindTexture(I)V"))
    private void scandium$bindTexture(int texture) {
        if (!ShadowRenderer.glOffForMainTarget()) {
            RenderSystem.bindTexture(texture);
        }
    }

    @Redirect(method = "apply", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Uniform;upload()V"))
    private void scandium$uploadUniform(Uniform uniform) {
        if (!ShadowRenderer.glOffForMainTarget()) {
            uniform.upload();
        }
    }
}
