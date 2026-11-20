package dev.scandium.mixin.vulkan.present;

import com.mojang.blaze3d.platform.Window;
import dev.scandium.vulkan.present.VulkanPresent;
import dev.scandium.vulkan.shadow.ShadowRenderer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stage 5: a Vulkan window plus a hidden OpenGL context (see VulkanPresent). */
@Mixin(Window.class)
public abstract class WindowPresentMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", remap = false,
            target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private long scandium$createWindow(int width, int height, CharSequence title, long monitor, long share) {
        return VulkanPresent.createWindows(width, height, title, monitor, share);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", remap = false,
            target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"))
    private void scandium$makeContextCurrent(long window) {
        GLFW.glfwMakeContextCurrent(VulkanPresent.contextWindow(window));
    }

    /** VSync is a property of the swapchain now; the hidden GL context must never wait for a vblank. */
    @Redirect(method = "updateVsync", at = @At(value = "INVOKE", remap = false,
            target = "Lorg/lwjgl/glfw/GLFW;glfwSwapInterval(I)V"))
    private void scandium$vsync(int interval) {
        VulkanPresent.setVsync(interval != 0);
        GLFW.glfwSwapInterval(VulkanPresent.isTakeover() ? 0 : interval);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void scandium$closeVulkan(CallbackInfo ci) {
        VulkanPresent.shutdown(ShadowRenderer.runtime());
    }
}
