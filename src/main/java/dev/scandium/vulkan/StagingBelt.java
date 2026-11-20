package dev.scandium.vulkan;

import dev.scandium.vulkan.memory.VulkanMemoryAllocator;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Uploads to device-local memory through one persistently mapped host buffer.
 * <p>
 * Data is copied into the belt immediately; the GPU copy commands are collected and recorded in a single
 * submission on {@link #flush}. When the belt is full it flushes itself and starts over; uploads larger than
 * the belt get a temporary staging buffer of their own.
 */
public final class StagingBelt implements AutoCloseable {
    private final VulkanContext context;
    private final VulkanMemoryAllocator allocator;
    private final VulkanCommands commands;
    private final VulkanBuffer belt;
    private final List<Consumer<VkCommandBuffer>> pending = new ArrayList<>();
    private final List<VulkanBuffer> oversized = new ArrayList<>();
    private long cursor;

    public StagingBelt(VulkanContext context, VulkanMemoryAllocator allocator, VulkanCommands commands, long capacity) {
        this.context = context;
        this.allocator = allocator;
        this.commands = commands;
        this.belt = VulkanBuffer.create(context, allocator, capacity, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    /** Copies {@code data} (position..limit) into {@code dst} at {@code dstOffset}. */
    public synchronized void copyToBuffer(VulkanBuffer dst, long dstOffset, ByteBuffer data) {
        long size = data.remaining();
        Source src = stage(data, 4);
        this.pending.add(cmd -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0).srcOffset(src.offset).dstOffset(dstOffset).size(size);
                vkCmdCopyBuffer(cmd, src.buffer, dst.handle, region);
            }
        });
    }

    /**
     * Uploads tightly packed pixels into mip level 0 and (re)generates the remaining levels. The texture ends up
     * in SHADER_READ_ONLY layout.
     */
    public synchronized void uploadTexture(VulkanTexture texture, ByteBuffer pixels) {
        Source src = stage(pixels, 4);
        this.pending.add(cmd -> {
            texture.transition(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0).bufferOffset(src.offset);
                region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).layerCount(1);
                region.get(0).imageExtent().set(texture.width, texture.height, 1);
                vkCmdCopyBufferToImage(cmd, src.buffer, texture.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }
            texture.generateMipmaps(cmd);
        });
    }

    /** Records all pending copies in one submission and waits for it. */
    public synchronized void flush() {
        if (this.pending.isEmpty()) {
            return;
        }
        List<Consumer<VkCommandBuffer>> work = new ArrayList<>(this.pending);
        this.pending.clear();
        this.commands.submitAndWait(cmd -> {
            for (Consumer<VkCommandBuffer> op : work) {
                op.accept(cmd);
            }
        });
        this.cursor = 0;
        for (VulkanBuffer buffer : this.oversized) {
            buffer.close();
        }
        this.oversized.clear();
    }

    private record Source(long buffer, long offset) {
    }

    private Source stage(ByteBuffer data, long alignment) {
        long size = data.remaining();
        if (size > this.belt.size) {
            VulkanBuffer temp = VulkanBuffer.create(this.context, this.allocator, size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), temp.mappedAddress, size);
            this.oversized.add(temp);
            return new Source(temp.handle, 0);
        }
        long offset = (this.cursor + alignment - 1) / alignment * alignment;
        if (offset + size > this.belt.size) {
            flush(); // belt full: push what we have, then reuse it from the start
            offset = 0;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), this.belt.mappedAddress + offset, size);
        this.cursor = offset + size;
        return new Source(this.belt.handle, offset);
    }

    @Override
    public synchronized void close() {
        flush();
        this.belt.close();
    }
}
