package dev.scandium.vulkan;

import dev.scandium.vulkan.memory.VulkanMemoryAllocator;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.LongBuffer;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A buffer whose memory comes from the {@link VulkanMemoryAllocator}. Host-visible buffers are mapped for their
 * whole lifetime ({@link #mappedAddress}).
 */
public final class VulkanBuffer implements AutoCloseable {
    private final VulkanContext context;
    private final VulkanMemoryAllocator allocator;
    private final VulkanMemoryAllocator.Allocation allocation;
    public final long handle;
    public final long size;
    /** Address of the mapping, 0 if not host-visible. */
    public final long mappedAddress;

    private VulkanBuffer(VulkanContext context, VulkanMemoryAllocator allocator, long handle, long size,
                         VulkanMemoryAllocator.Allocation allocation) {
        this.context = context;
        this.allocator = allocator;
        this.handle = handle;
        this.size = size;
        this.allocation = allocation;
        this.mappedAddress = allocation.mappedAddress;
    }

    public static VulkanBuffer create(VulkanContext context, VulkanMemoryAllocator allocator, long size, int usage,
                                      int memoryFlags) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(context.device, info, null, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(context.device, buffer, requirements);
            VulkanMemoryAllocator.Allocation allocation;
            try {
                allocation = allocator.allocate(requirements, memoryFlags, VulkanMemoryAllocator.Kind.LINEAR);
                check(vkBindBufferMemory(context.device, buffer, allocation.memory, allocation.offset),
                        "vkBindBufferMemory");
            } catch (RuntimeException e) {
                vkDestroyBuffer(context.device, buffer, null);
                throw e;
            }
            return new VulkanBuffer(context, allocator, buffer, size, allocation);
        }
    }

    @Override
    public void close() {
        vkDestroyBuffer(this.context.device, this.handle, null);
        this.allocator.free(this.allocation);
    }
}
