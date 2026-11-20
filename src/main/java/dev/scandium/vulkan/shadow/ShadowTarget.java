package dev.scandium.vulkan.shadow;

import dev.scandium.vulkan.VulkanBuffer;
import dev.scandium.vulkan.VulkanRuntime;
import dev.scandium.vulkan.memory.VulkanMemoryAllocator;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The Vulkan counterpart of Minecraft's main render target: RGBA8 colour + depth, a render pass that keeps
 * contents between passes (LOAD/STORE, so passes can be interrupted for uploads), and a read-back buffer for
 * screenshots. Created at the size of the game's main target and recreated when the window is resized.
 */
final class ShadowTarget implements AutoCloseable {
    static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    private final VulkanRuntime rt;
    final int width;
    final int height;
    final int depthFormat;
    final long colorImage;
    final long depthImage;
    private final VulkanMemoryAllocator.Allocation colorMemory;
    private final VulkanMemoryAllocator.Allocation depthMemory;
    private final long colorView;
    private final long depthView;
    final long renderPass;
    final long framebuffer;
    final VulkanBuffer readback;
    private boolean initialized;

    ShadowTarget(VulkanRuntime rt, int width, int height, int depthFormat, long renderPass) {
        this.rt = rt;
        this.width = width;
        this.height = height;
        this.depthFormat = depthFormat;
        this.renderPass = renderPass;
        VkDevice device = rt.context.device;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pHandle = stack.mallocLong(1);
            this.colorImage = createImage(stack, COLOR_FORMAT,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            this.colorMemory = bind(stack, this.colorImage);
            this.depthImage = createImage(stack, depthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
            this.depthMemory = bind(stack, this.depthImage);
            this.colorView = createView(stack, this.colorImage, COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT);
            this.depthView = createView(stack, this.depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT);

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .renderPass(renderPass)
                    .pAttachments(stack.longs(this.colorView, this.depthView))
                    .width(width)
                    .height(height)
                    .layers(1);
            check(vkCreateFramebuffer(device, framebufferInfo, null, pHandle), "vkCreateFramebuffer");
            this.framebuffer = pHandle.get(0);
        }
        this.readback = VulkanBuffer.create(rt.context, rt.allocator, (long) width * height * 4,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    /** Render pass shared by the target and every pipeline (pipelines must be built against a compatible pass). */
    static long createRenderPass(VkDevice device, int depthFormat) {
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
            attachments.get(0).format(COLOR_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            attachments.get(1).format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD).stencilStoreOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
            colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                    .pColorAttachments(colorRef).pDepthStencilAttachment(depthRef);
            // Passes are split around uploads: make earlier passes' writes visible to later ones.
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
            dependency.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
                            | VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                            | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT
                            | VK_ACCESS_UNIFORM_READ_BIT);
            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(attachments).pSubpasses(subpass).pDependencies(dependency);
            LongBuffer pPass = stack.mallocLong(1);
            check(vkCreateRenderPass(device, info, null, pPass), "vkCreateRenderPass");
            return pPass.get(0);
        }
    }

    /** First use: move both images from UNDEFINED into the layouts the render pass expects. */
    void initializeLayouts(VkCommandBuffer cmd) {
        if (this.initialized) {
            return;
        }
        this.initialized = true;
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(2, stack);
            barriers.get(0).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.colorImage).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            barriers.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            int depthAspect = VK_IMAGE_ASPECT_DEPTH_BIT
                    | (this.depthFormat == VK_FORMAT_D32_SFLOAT || this.depthFormat == VK_FORMAT_D16_UNORM
                    ? 0 : VK_IMAGE_ASPECT_STENCIL_BIT);
            barriers.get(1).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.depthImage).dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            barriers.get(1).subresourceRange().aspectMask(depthAspect).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                    0, null, null, barriers);
        }
    }

    /** Copies the colour image into {@link #readback}; must be recorded outside a render pass. */
    void recordReadback(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            colorBarrier(cmd, stack, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(this.width, this.height, 1);
            vkCmdCopyImageToBuffer(cmd, this.colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, this.readback.handle, region);
            colorBarrier(cmd, stack, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            VkMemoryBarrier.Buffer host = VkMemoryBarrier.calloc(1, stack);
            host.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, host, null, null);
        }
    }

    private void colorBarrier(VkCommandBuffer cmd, MemoryStack stack, int oldLayout, int newLayout, int srcAccess,
                              int dstAccess, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(this.colorImage).srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
    }

    private long createImage(MemoryStack stack, int format, int usage) {
        VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        info.extent().set(this.width, this.height, 1);
        LongBuffer pImage = stack.mallocLong(1);
        check(vkCreateImage(this.rt.context.device, info, null, pImage), "vkCreateImage");
        return pImage.get(0);
    }

    private VulkanMemoryAllocator.Allocation bind(MemoryStack stack, long image) {
        VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(this.rt.context.device, image, requirements);
        VulkanMemoryAllocator.Allocation allocation = this.rt.allocator.allocate(requirements,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, VulkanMemoryAllocator.Kind.OPTIMAL);
        check(vkBindImageMemory(this.rt.context.device, image, allocation.memory, allocation.offset), "vkBindImageMemory");
        return allocation;
    }

    private long createView(MemoryStack stack, long image, int format, int aspect) {
        VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
        info.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
        LongBuffer pView = stack.mallocLong(1);
        check(vkCreateImageView(this.rt.context.device, info, null, pView), "vkCreateImageView");
        return pView.get(0);
    }

    @Override
    public void close() {
        VkDevice device = this.rt.context.device;
        this.readback.close();
        vkDestroyFramebuffer(device, this.framebuffer, null);
        vkDestroyImageView(device, this.colorView, null);
        vkDestroyImageView(device, this.depthView, null);
        vkDestroyImage(device, this.colorImage, null);
        vkDestroyImage(device, this.depthImage, null);
        this.rt.allocator.free(this.colorMemory);
        this.rt.allocator.free(this.depthMemory);
    }
}
