package dev.scandium.mixin.client;

import dev.scandium.client.FrameBenchmark;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Handles {@code /scandium bench} on the client, before it is sent to the server: it measures the client's
 * frames, and this way it also works on servers that don't have Scandium installed.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientCommandMixin {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void scandium$clientCommands(String command, CallbackInfo ci) {
        String normalized = command.trim().replaceAll("\\s+", " ");
        if (normalized.equalsIgnoreCase("scandium bench")) {
            FrameBenchmark.start();
            ci.cancel();
        } else if (normalized.equalsIgnoreCase("scandium vkshot")) {
            dev.scandium.client.VulkanScreenshot.take();
            ci.cancel();
        } else if (normalized.equalsIgnoreCase("scandium present vulkan")
                || normalized.equalsIgnoreCase("scandium present opengl")) {
            boolean vulkan = normalized.toLowerCase(java.util.Locale.ROOT).endsWith("vulkan");
            dev.scandium.vulkan.present.VulkanPresent.setRequestedSource(vulkan
                    ? dev.scandium.vulkan.present.VulkanPresent.Source.VULKAN
                    : dev.scandium.vulkan.present.VulkanPresent.Source.OPENGL);
            net.minecraft.client.Minecraft.getInstance().gui.getChat().addMessage(net.minecraft.network.chat.Component
                    .literal(dev.scandium.vulkan.present.VulkanPresent.isTakeover()
                            ? "Window picture from " + (vulkan ? "the Vulkan frame" : "OpenGL (read back)")
                            : "Vulkan presentation is off (\"vulkanPresent\": true + restart)"));
            ci.cancel();
        }
    }
}
