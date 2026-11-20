package dev.scandium.vulkan;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/** A failed Vulkan call, with the readable result code. */
public final class VulkanException extends RuntimeException {
    public VulkanException(String message) {
        super(message);
    }

    public static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new VulkanException(call + " failed: " + VulkanResults.name(result));
        }
    }
}
