package dev.scandium.vulkan.present;

import dev.scandium.vulkan.VulkanContext;
import dev.scandium.vulkan.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The window's swapchain. Images are only ever written by transfer (a blit of the Vulkan frame, or a copy of the
 * GL fallback picture), so the usage is TRANSFER_DST and no image views or framebuffers are needed.
 * <p>
 * Format: 8-bit UNORM like the GL default framebuffer (BGRA preferred, it is what Windows drivers list first).
 * Present mode: FIFO with VSync, otherwise MAILBOX (no tearing, no waiting) or IMMEDIATE.
 */
public final class Swapchain implements AutoCloseable {
    private final VulkanContext context;
    public final long handle;
    public final int format;
    public final int width;
    public final int height;
    public final boolean vsync;
    public final long[] images;
    /** One per image: signalled by the frame's submission, waited on by vkQueuePresentKHR. */
    public final long[] renderFinished;

    private Swapchain(VulkanContext context, long handle, int format, int width, int height, boolean vsync, long[] images,
                      long[] renderFinished) {
        this.context = context;
        this.handle = handle;
        this.format = format;
        this.width = width;
        this.height = height;
        this.vsync = vsync;
        this.images = images;
        this.renderFinished = renderFinished;
    }

    /** @param oldSwapchain the swapchain being replaced (resize), or VK_NULL_HANDLE */
    public static Swapchain create(VulkanContext context, long surface, int width, int height, boolean vsync,
                                   long oldSwapchain) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDevice gpu = context.physicalDevice;
            IntBuffer supported = stack.mallocInt(1);
            check(vkGetPhysicalDeviceSurfaceSupportKHR(gpu, context.queueFamily, surface, supported),
                    "vkGetPhysicalDeviceSurfaceSupportKHR");
            if (supported.get(0) == VK_FALSE) {
                throw new VulkanException("The graphics queue can't present to this window");
            }

            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu, surface, caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
                throw new VulkanException("Swapchain images can't be transfer destinations on this driver");
            }
            int w = width;
            int h = height;
            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                w = caps.currentExtent().width();
                h = caps.currentExtent().height();
            }
            w = Math.max(caps.minImageExtent().width(), Math.min(caps.maxImageExtent().width(), w));
            h = Math.max(caps.minImageExtent().height(), Math.min(caps.maxImageExtent().height(), h));

            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) {
                imageCount = Math.min(imageCount, caps.maxImageCount());
            }

            int format = pickFormat(gpu, surface, stack);
            int presentMode = pickPresentMode(gpu, surface, vsync, stack);
            int compositeAlpha = (caps.supportedCompositeAlpha() & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) != 0
                    ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : Integer.lowestOneBit(caps.supportedCompositeAlpha());

            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(format)
                    .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(compositeAlpha)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);
            info.imageExtent().width(w).height(h);
            LongBuffer pSwapchain = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(context.device, info, null, pSwapchain), "vkCreateSwapchainKHR");
            long handle = pSwapchain.get(0);

            IntBuffer count = stack.mallocInt(1);
            check(vkGetSwapchainImagesKHR(context.device, handle, count, null), "vkGetSwapchainImagesKHR");
            LongBuffer pImages = stack.mallocLong(count.get(0));
            check(vkGetSwapchainImagesKHR(context.device, handle, count, pImages), "vkGetSwapchainImagesKHR");
            long[] images = new long[count.get(0)];
            long[] semaphores = new long[images.length];
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer pSemaphore = stack.mallocLong(1);
            for (int i = 0; i < images.length; i++) {
                images[i] = pImages.get(i);
                check(vkCreateSemaphore(context.device, semaphoreInfo, null, pSemaphore), "vkCreateSemaphore");
                semaphores[i] = pSemaphore.get(0);
            }
            dev.scandium.Scandium.LOGGER.info("Vulkan swapchain: {}x{}, {} images, present mode {}", w, h, images.length,
                    switch (presentMode) {
                        case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
                        case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
                        case VK_PRESENT_MODE_FIFO_KHR -> "FIFO (VSync)";
                        default -> String.valueOf(presentMode);
                    });
            return new Swapchain(context, handle, format, w, h, vsync, images, semaphores);
        }
    }

    private static int pickFormat(VkPhysicalDevice gpu, long surface, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, surface, count, null), "vkGetPhysicalDeviceSurfaceFormatsKHR");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, surface, count, formats), "vkGetPhysicalDeviceSurfaceFormatsKHR");
        int fallback = formats.get(0).format();
        for (int wanted : new int[]{VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM}) {
            for (int i = 0; i < formats.capacity(); i++) {
                if (formats.get(i).format() == wanted && formats.get(i).colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    return wanted;
                }
            }
        }
        return fallback;
    }

    private static int pickPresentMode(VkPhysicalDevice gpu, long surface, boolean vsync, MemoryStack stack) {
        if (vsync) {
            return VK_PRESENT_MODE_FIFO_KHR; // always available
        }
        IntBuffer count = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, surface, count, null), "vkGetPhysicalDeviceSurfacePresentModesKHR");
        IntBuffer modes = stack.mallocInt(count.get(0));
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, surface, count, modes), "vkGetPhysicalDeviceSurfacePresentModesKHR");
        boolean mailbox = false;
        boolean immediate = false;
        for (int i = 0; i < modes.capacity(); i++) {
            mailbox |= modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR;
            immediate |= modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR;
        }
        // VSync off in GL means swap interval 0: never wait. IMMEDIATE is the same; MAILBOX goes through the
        // compositor on windowed Windows and was measured capping at ~400 FPS.
        return immediate ? VK_PRESENT_MODE_IMMEDIATE_KHR : mailbox ? VK_PRESENT_MODE_MAILBOX_KHR : VK_PRESENT_MODE_FIFO_KHR;
    }

    public boolean isBgra() {
        return this.format == VK_FORMAT_B8G8R8A8_UNORM || this.format == VK_FORMAT_B8G8R8A8_SRGB;
    }

    @Override
    public void close() {
        for (long semaphore : this.renderFinished) {
            vkDestroySemaphore(this.context.device, semaphore, null);
        }
        vkDestroySwapchainKHR(this.context.device, this.handle, null);
    }
}
