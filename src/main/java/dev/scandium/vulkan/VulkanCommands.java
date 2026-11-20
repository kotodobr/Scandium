package dev.scandium.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.function.Consumer;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Command pool plus a fence for one-shot, synchronous submissions (uploads, setup, tests). */
public final class VulkanCommands implements AutoCloseable {
    private final VulkanContext context;
    private final long pool;
    private final long fence;

    public VulkanCommands(VulkanContext context) {
        this.context = context;
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(context.queueFamily);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(context.device, poolInfo, null, pPool), "vkCreateCommandPool");
            this.pool = pPool.get(0);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer pFence = stack.mallocLong(1);
            check(vkCreateFence(context.device, fenceInfo, null, pFence), "vkCreateFence");
            this.fence = pFence.get(0);
        }
    }

    /** Records with {@code recorder}, submits and waits until the GPU finished. */
    public void submitAndWait(Consumer<VkCommandBuffer> recorder) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(this.pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pBuffer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(this.context.device, allocInfo, pBuffer), "vkAllocateCommandBuffers");
            VkCommandBuffer cmd = new VkCommandBuffer(pBuffer.get(0), this.context.device);
            try {
                VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");
                recorder.accept(cmd);
                check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

                VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                        .sType$Default()
                        .pCommandBuffers(stack.pointers(cmd));
                synchronized (this.context.queue) { // the queue is shared with the shadow renderer
                    check(vkQueueSubmit(this.context.queue, submit, this.fence), "vkQueueSubmit");
                }
                check(vkWaitForFences(this.context.device, this.fence, true, 10_000_000_000L), "vkWaitForFences");
                check(vkResetFences(this.context.device, this.fence), "vkResetFences");
            } finally {
                vkFreeCommandBuffers(this.context.device, this.pool, cmd);
            }
        }
    }

    @Override
    public void close() {
        vkDestroyFence(this.context.device, this.fence, null);
        vkDestroyCommandPool(this.context.device, this.pool, null);
    }
}
