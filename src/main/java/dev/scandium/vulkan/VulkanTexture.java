package dev.scandium.vulkan;

import dev.scandium.vulkan.memory.VulkanMemoryAllocator;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A sampled 2D texture (RGBA8 by default) with optional mip chain. Tracks the layout of all levels as one unit:
 * the texture is either being written (TRANSFER_DST) or sampled (SHADER_READ_ONLY); {@link #generateMipmaps}
 * handles the per-level transitions it needs internally.
 */
public final class VulkanTexture implements AutoCloseable {
    private final VulkanContext context;
    private final VulkanMemoryAllocator allocator;
    private final VulkanMemoryAllocator.Allocation allocation;
    public final long image;
    public final long view;
    public final int width;
    public final int height;
    public final int mipLevels;
    public final int format;
    private int layout = VK_IMAGE_LAYOUT_UNDEFINED;

    private VulkanTexture(VulkanContext context, VulkanMemoryAllocator allocator, VulkanMemoryAllocator.Allocation allocation,
                          long image, long view, int width, int height, int mipLevels, int format) {
        this.context = context;
        this.allocator = allocator;
        this.allocation = allocation;
        this.image = image;
        this.view = view;
        this.width = width;
        this.height = height;
        this.mipLevels = mipLevels;
        this.format = format;
    }

    public static int fullMipChain(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    public static VulkanTexture create(VulkanContext context, VulkanMemoryAllocator allocator, int width, int height,
                                       int mipLevels, int format, int extraUsage) {
        try (MemoryStack stack = stackPush()) {
            int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | extraUsage;
            if (mipLevels > 1) {
                usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT; // mip generation blits from the previous level
            }
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            LongBuffer pHandle = stack.mallocLong(1);
            check(vkCreateImage(context.device, info, null, pHandle), "vkCreateImage");
            long image = pHandle.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(context.device, image, requirements);
            VulkanMemoryAllocator.Allocation allocation;
            try {
                allocation = allocator.allocate(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        VulkanMemoryAllocator.Kind.OPTIMAL);
                check(vkBindImageMemory(context.device, image, allocation.memory, allocation.offset), "vkBindImageMemory");
            } catch (RuntimeException e) {
                vkDestroyImage(context.device, image, null);
                throw e;
            }

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(mipLevels).layerCount(1);
            check(vkCreateImageView(context.device, viewInfo, null, pHandle), "vkCreateImageView");
            return new VulkanTexture(context, allocator, allocation, image, pHandle.get(0), width, height, mipLevels, format);
        }
    }

    public int layout() {
        return this.layout;
    }

    /** Transitions all levels; records nothing if already in that layout. */
    public void transition(VkCommandBuffer cmd, int newLayout) {
        if (this.layout == newLayout) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            barrier(cmd, stack, 0, this.mipLevels, this.layout, newLayout);
        }
        this.layout = newLayout;
    }

    /**
     * Fills levels 1..n by successive linear blits from the level above. Expects level 0 in TRANSFER_DST
     * (fresh upload); leaves all levels in SHADER_READ_ONLY.
     */
    public void generateMipmaps(VkCommandBuffer cmd) {
        if (this.mipLevels <= 1) {
            transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            return;
        }
        try (MemoryStack stack = stackPush()) {
            int w = this.width;
            int h = this.height;
            for (int level = 1; level < this.mipLevels; level++) {
                // previous level: written -> read source
                barrier(cmd, stack, level - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                int nw = Math.max(1, w / 2);
                int nh = Math.max(1, h / 2);
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level - 1).layerCount(1);
                blit.get(0).srcOffsets(1).set(w, h, 1);
                blit.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).layerCount(1);
                blit.get(0).dstOffsets(1).set(nw, nh, 1);
                // this level comes from UNDEFINED the first time
                barrier(cmd, stack, level, 1, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                vkCmdBlitImage(cmd, this.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, this.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
                barrier(cmd, stack, level - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                w = nw;
                h = nh;
            }
            barrier(cmd, stack, this.mipLevels - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        this.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    private void barrier(VkCommandBuffer cmd, MemoryStack stack, int baseLevel, int levels, int oldLayout, int newLayout) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(this.image)
                .srcAccessMask(accessFor(oldLayout))
                .dstAccessMask(accessFor(newLayout));
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(baseLevel)
                .levelCount(levels).layerCount(1);
        vkCmdPipelineBarrier(cmd, stageFor(oldLayout), stageFor(newLayout), 0, null, null, barrier);
    }

    private static int accessFor(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_ACCESS_TRANSFER_READ_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_ACCESS_SHADER_READ_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            default -> 0;
        };
    }

    private static int stageFor(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            default -> VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        };
    }

    @Override
    public void close() {
        vkDestroyImageView(this.context.device, this.view, null);
        vkDestroyImage(this.context.device, this.image, null);
        this.allocator.free(this.allocation);
    }
}
