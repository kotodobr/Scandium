package dev.scandium.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.scandium.vulkan.shadow.ShadowRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

/**
 * {@code /scandium vkshot}: saves the current Vulkan shadow frame and, for comparison, a normal OpenGL screenshot
 * of the same moment into the screenshots folder.
 */
public final class VulkanScreenshot {
    private VulkanScreenshot() {
    }

    public static void take() {
        Minecraft mc = Minecraft.getInstance();
        if (!ShadowRenderer.requestScreenshot()) {
            mc.gui.getChat().addMessage(Component.literal("The Vulkan shadow renderer is off: set \"vulkanBackend\" and "
                    + "\"vulkanShadowRender\" to true in config/scandium.json and restart.").withStyle(ChatFormatting.RED));
            return;
        }
        // Next frame: the chat screen is closed by then, both images show the same view.
        RenderSystem.recordRenderCall(() -> Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(),
                message -> mc.gui.getChat().addMessage(Component.literal("OpenGL: ").append(message))));
        mc.gui.getChat().addMessage(Component.literal("Vulkan frame: screenshots/scandium-vulkan-*.png")
                .withStyle(ChatFormatting.AQUA));
    }
}
