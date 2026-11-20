package dev.scandium.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Samplers are immutable and few: Minecraft only switches filtering (blur), mipmapping and clamping per texture.
 * One VkSampler per combination, created on first use.
 */
public final class SamplerCache implements AutoCloseable {
    private final VulkanContext context;
    private final Map<Integer, Long> samplers = new HashMap<>();

    public SamplerCache(VulkanContext context) {
        this.context = context;
    }

    /**
     * @param linear   linear min/mag filtering (vanilla "blur"), nearest otherwise
     * @param mipmap   sample mip levels (linear between levels when {@code linear})
     * @param clamp    clamp to edge instead of repeat
     */
    public synchronized long get(boolean linear, boolean mipmap, boolean clamp) {
        int key = (linear ? 1 : 0) | (mipmap ? 2 : 0) | (clamp ? 4 : 0);
        Long existing = this.samplers.get(key);
        if (existing != null) {
            return existing;
        }
        try (MemoryStack stack = stackPush()) {
            int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            int address = clamp ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(filter)
                    .minFilter(filter)
                    .mipmapMode(linear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(address)
                    .addressModeV(address)
                    .addressModeW(address)
                    .minLod(0.0f)
                    .maxLod(mipmap ? VK_LOD_CLAMP_NONE : 0.0f)
                    .borderColor(VK_BORDER_COLOR_INT_TRANSPARENT_BLACK);
            LongBuffer pSampler = stack.mallocLong(1);
            check(vkCreateSampler(this.context.device, info, null, pSampler), "vkCreateSampler");
            this.samplers.put(key, pSampler.get(0));
            return pSampler.get(0);
        }
    }

    @Override
    public synchronized void close() {
        for (long sampler : this.samplers.values()) {
            vkDestroySampler(this.context.device, sampler, null);
        }
        this.samplers.clear();
    }
}
