package dev.scandium.mixin.vulkan.present;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.scandium.vulkan.present.VulkanPresent;
import dev.scandium.vulkan.shadow.ShadowRenderer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The end of every frame: present through the Vulkan swapchain instead of swapping GL buffers. */
@Mixin(value = RenderSystem.class, remap = false)
public abstract class RenderSystemPresentMixin {
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"))
    private static void scandium$present(long window) {
        if (VulkanPresent.isTakeover()) {
            ShadowRenderer.present();
        } else {
            GLFW.glfwSwapBuffers(window);
        }
    }
}
