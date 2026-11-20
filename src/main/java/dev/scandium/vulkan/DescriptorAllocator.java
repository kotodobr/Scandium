package dev.scandium.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

/**
 * Allocates descriptor sets from a growing list of pools. When the current pool runs out (or is fragmented) a
 * new one is created; {@link #reset} returns everything at once - meant for per-frame sets that are rewritten
 * every frame, the cheapest way to manage them.
 */
public final class DescriptorAllocator implements AutoCloseable {
    private static final int SETS_PER_POOL = 512;

    private final VulkanContext context;
    private final List<Long> pools = new ArrayList<>();
    private int current = -1;

    public DescriptorAllocator(VulkanContext context) {
        this.context = context;
    }

    public synchronized long allocate(long setLayout) {
        if (this.current < 0) {
            nextPool();
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSet = stack.mallocLong(1);
            for (int attempt = 0; attempt < 2; attempt++) {
                VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default()
                        .descriptorPool(this.pools.get(this.current))
                        .pSetLayouts(stack.longs(setLayout));
                int result = vkAllocateDescriptorSets(this.context.device, info, pSet);
                if (result == VK_SUCCESS) {
                    return pSet.get(0);
                }
                if (result != VK_ERROR_OUT_OF_POOL_MEMORY && result != VK_ERROR_FRAGMENTED_POOL) {
                    check(result, "vkAllocateDescriptorSets");
                }
                nextPool();
            }
            throw new VulkanException("Descriptor set doesn't fit into a fresh pool");
        }
    }

    /** Frees every set from every pool; pools are kept for reuse. */
    public synchronized void reset() {
        for (long pool : this.pools) {
            vkResetDescriptorPool(this.context.device, pool, 0);
        }
        this.current = this.pools.isEmpty() ? -1 : 0;
    }

    private void nextPool() {
        if (this.current + 1 < this.pools.size()) {
            this.current++;
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS_PER_POOL * 4);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(SETS_PER_POOL * 2);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(SETS_PER_POOL);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(SETS_PER_POOL);
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(SETS_PER_POOL)
                    .pPoolSizes(sizes);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateDescriptorPool(this.context.device, info, null, pPool), "vkCreateDescriptorPool");
            this.pools.add(pPool.get(0));
            this.current = this.pools.size() - 1;
        }
    }

    @Override
    public synchronized void close() {
        for (long pool : this.pools) {
            vkDestroyDescriptorPool(this.context.device, pool, null);
        }
        this.pools.clear();
        this.current = -1;
    }
}
