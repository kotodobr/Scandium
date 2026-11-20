package dev.scandium.vulkan.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.scandium.Scandium;
import dev.scandium.mixin.vulkan.shadow.ShaderInstanceAccessor;
import dev.scandium.vulkan.DescriptorAllocator;
import dev.scandium.vulkan.VulkanBackend;
import dev.scandium.vulkan.VulkanBuffer;
import dev.scandium.vulkan.VulkanRuntime;
import dev.scandium.vulkan.VulkanTexture;
import dev.scandium.vulkan.shader.GlslTranslator;
import dev.scandium.vulkan.shader.PipelineFactory;
import dev.scandium.vulkan.shader.PipelineState;
import dev.scandium.vulkan.shader.VertexInputMapper;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Stage 4 of the Vulkan backend: replays everything the game draws with OpenGL into an offscreen Vulkan frame.
 * <p>
 * The game keeps rendering with OpenGL; this class follows along. It mirrors GL textures and vertex buffers into
 * Vulkan, and at every {@code VertexBuffer} draw issued through a {@code ShaderInstance} it records the same draw
 * - same shader (translated), same uniforms, same textures, same blend/depth/cull state, same viewport - into a
 * Vulkan command buffer. This proves the whole render-state layer on real game frames while OpenGL still
 * presents them; {@code /scandium vkshot} saves the Vulkan frame next to a GL screenshot for comparison.
 * <p>
 * Only draws into the game's main render target are mirrored. Terrain is drawn by its own path (stage 6).
 * Render thread only. Debug feature: costs CPU time per draw, enabled with {@code vulkanShadowRender}.
 */
public final class ShadowRenderer {
    private static final int FRAMES = 2;
    private static final long RING_SIZE = 64L << 20;
    private static final int QUAD_INDEX_START = 1 << 16;

    private static boolean active;
    private static VulkanRuntime rt;
    private static long renderPass;
    private static int depthFormat;
    private static long commandPool;
    private static final Slot[] SLOTS = new Slot[FRAMES];
    private static int slotIndex;
    private static int lastSubmitted = -1;
    private static boolean frameOpen;
    private static boolean passOpen;
    private static long frameNumber;
    private static ShadowTarget target;
    private static int generation = -1;

    private static final Int2ObjectOpenHashMap<TextureMirror> TEXTURES = new Int2ObjectOpenHashMap<>();
    private static final Map<String, PipelineFactory.Program> PROGRAMS = new HashMap<>();
    private static final Map<InputKey, VertexInputMapper.Layout> INPUTS = new HashMap<>();
    private static final Map<PipelineKey, Long> PIPELINES = new HashMap<>();
    private static VulkanTexture fallbackTexture;
    private static VulkanBuffer quadIndices;
    private static VulkanBuffer lineIndices;
    private static int sequentialCapacity;

    private static boolean screenshotRequested;
    private static int drawsThisFrame;
    private static int terrainDrawsThisFrame;
    private static int regionDrawsThisFrame;
    private static int skippedThisFrame;
    private static volatile String stats = "";
    private static boolean ringOverflowLogged;

    private record InputKey(String shader, VertexFormat format) {
    }

    private record PipelineKey(String shader, VertexFormat format, PipelineState state) {
    }

    private static final class Slot {
        long commandBuffer;
        VkCommandBuffer cmd;
        long fence;
        VulkanBuffer ring;
        long ringCursor;
        DescriptorAllocator descriptors;
        final List<Runnable> deferred = new ArrayList<>();
        boolean submitted;
        /** Signalled when the acquired swapchain image may be written (stage 5). */
        long imageAvailable;
        /** GL picture read back for the fallback presentation. */
        VulkanBuffer glReadback;
    }

    private ShadowRenderer() {
    }

    public static boolean isActive() {
        return active;
    }

    public static String stats() {
        return stats;
    }

    /** Client start, after the Vulkan runtime is ready. Blocks briefly; on failure everything stays a no-op. */
    public static void activate(VulkanRuntime runtime) {
        if (active) {
            return;
        }
        try {
            rt = runtime;
            depthFormat = pickDepthFormat();
            renderPass = ShadowTarget.createRenderPass(rt.context.device, depthFormat);
            try (MemoryStack stack = stackPush()) {
                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                        .queueFamilyIndex(rt.context.queueFamily);
                LongBuffer pHandle = stack.mallocLong(1);
                check(vkCreateCommandPool(rt.context.device, poolInfo, null, pHandle), "vkCreateCommandPool");
                commandPool = pHandle.get(0);
                for (int i = 0; i < FRAMES; i++) {
                    Slot slot = new Slot();
                    VkCommandBufferAllocateInfo alloc = VkCommandBufferAllocateInfo.calloc(stack)
                            .sType$Default().commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                            .commandBufferCount(1);
                    PointerBuffer pCmd = stack.mallocPointer(1);
                    check(vkAllocateCommandBuffers(rt.context.device, alloc, pCmd), "vkAllocateCommandBuffers");
                    slot.cmd = new VkCommandBuffer(pCmd.get(0), rt.context.device);
                    VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
                    check(vkCreateFence(rt.context.device, fenceInfo, null, pHandle), "vkCreateFence");
                    slot.fence = pHandle.get(0);
                    slot.ring = VulkanBuffer.create(rt.context, rt.allocator, RING_SIZE,
                            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                                    | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                                    | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                    slot.descriptors = new DescriptorAllocator(rt.context);
                    VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
                    check(vkCreateSemaphore(rt.context.device, semaphoreInfo, null, pHandle), "vkCreateSemaphore");
                    slot.imageAvailable = pHandle.get(0);
                    SLOTS[i] = slot;
                }
            }
            active = true;
            Scandium.LOGGER.info("Vulkan shadow renderer active: game frames are replayed into an offscreen Vulkan "
                    + "frame (/scandium vkshot to compare)");
        } catch (Throwable t) {
            active = false;
            Scandium.LOGGER.warn("Vulkan shadow renderer unavailable", t);
        }
    }

    // ================================================================== frames

    /** Start of every game frame (Minecraft.runTick): submits the previous Vulkan frame. */
    public static void onFrameBoundary() {
        updateGlOff();
        if (!active) {
            return;
        }
        try {
            endFrame();
            stats = (glOff ? "GL draws off, " : "") + drawsThisFrame + " draws + " + terrainDrawsThisFrame
                    + " terrain sections (" + regionDrawsThisFrame + " region draws), " + skippedThisFrame
                    + " skipped, " + TEXTURES.size() + " textures, " + TERRAIN.size() + " sections, "
                    + PIPELINES.size() + " pipelines";
            drawsThisFrame = 0;
            terrainDrawsThisFrame = 0;
            regionDrawsThisFrame = 0;
            skippedThisFrame = 0;
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static Slot frame() {
        if (!frameOpen) {
            beginFrame();
        }
        return SLOTS[slotIndex];
    }

    private static void beginFrame() {
        Slot slot = SLOTS[slotIndex];
        if (slot.submitted) {
            check(vkWaitForFences(rt.context.device, slot.fence, true, Long.MAX_VALUE), "vkWaitForFences");
            check(vkResetFences(rt.context.device, slot.fence), "vkResetFences");
            slot.submitted = false;
        }
        runDeferred(slot);
        slot.ringCursor = 0;
        slot.descriptors.reset();
        frameNumber++;

        if (generation != VulkanBackend.pipelineGeneration()) {
            // Shaders were reloaded: drop everything compiled from the old sources.
            waitIdle();
            destroyPipelines();
            generation = VulkanBackend.pipelineGeneration();
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (target == null || target.width != main.width || target.height != main.height) {
            if (target != null) {
                ShadowTarget old = target;
                waitIdle();
                old.close();
            }
            target = new ShadowTarget(rt, Math.max(1, main.width), Math.max(1, main.height), depthFormat, renderPass);
        }

        try (MemoryStack stack = stackPush()) {
            check(vkResetCommandBuffer(slot.cmd, 0), "vkResetCommandBuffer");
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(slot.cmd, begin), "vkBeginCommandBuffer");
        }
        target.initializeLayouts(slot.cmd);
        frameOpen = true;
    }

    private static void endFrame() {
        if (!frameOpen) {
            return;
        }
        Slot slot = SLOTS[slotIndex];
        endPass();
        boolean screenshot = screenshotRequested;
        if (screenshot) {
            target.recordReadback(slot.cmd);
            screenshotRequested = false;
        }
        check(vkEndCommandBuffer(slot.cmd), "vkEndCommandBuffer");
        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(slot.cmd));
            synchronized (rt.context.queue) {
                check(vkQueueSubmit(rt.context.queue, submit, slot.fence), "vkQueueSubmit");
            }
        }
        slot.submitted = true;
        frameOpen = false;
        lastSubmitted = slotIndex;
        if (screenshot) {
            check(vkWaitForFences(rt.context.device, slot.fence, true, Long.MAX_VALUE), "vkWaitForFences");
            saveScreenshot();
        }
        slotIndex = (slotIndex + 1) % FRAMES;
    }

    // ================================================================== stage 7: GL off

    /** Decided once per frame: GL skips the work the Vulkan frame already covers. */
    private static boolean glOff;

    public static boolean glOff() {
        return glOff;
    }

    /**
     * GL only skips work while the window shows the Vulkan frame. When that stops (switch to the GL picture,
     * renderer failure), GL lacks the terrain it never received: every section is rebuilt once.
     */
    private static void updateGlOff() {
        boolean off = active && dev.scandium.vulkan.present.VulkanPresent.isTakeover()
                && dev.scandium.vulkan.present.VulkanPresent.requestedSource()
                == dev.scandium.vulkan.present.VulkanPresent.Source.VULKAN
                && VulkanBackend.pipelineGeneration() > 0
                && dev.scandium.config.ScandiumConfig.get().vulkanSkipGl;
        if (glOff && !off) {
            // apply() skipped glUseProgram while GL was off: vanilla's bound-program cache is stale.
            dev.scandium.mixin.vulkan.shadow.ShaderInstanceStaticAccessor.scandium$setLastProgramId(-1);
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                Scandium.LOGGER.info("OpenGL takes over drawing again: rebuilding terrain");
                mc.levelRenderer.allChanged();
            }
        }
        glOff = off;
    }

    /** Anything GL would do for the main target right now is invisible (stage 8: ShaderInstance.apply). */
    public static boolean glOffForMainTarget() {
        return glOff && targetsMain();
    }

    /** GL draw of a mirrored VertexBuffer into the main target: not needed while the Vulkan frame is shown. */
    public static boolean skipGlDraw(ShadowVertexBuffer owner) {
        return glOff && !owner.scandium$mirror().terrain && targetsMain();
    }

    /**
     * GL upload of vertex data that only feeds skipped draws: dynamic buffers drawn into the main target, and
     * terrain on the vanilla path (region batching handles its own path in VertexBufferRegionMixin).
     */
    public static boolean skipGlUpload(ShadowVertexBuffer owner) {
        if (!glOff) {
            return false;
        }
        VertexMirror mirror = owner.scandium$mirror();
        if (mirror.terrain) {
            int layer = ((dev.scandium.render.region.TerrainBufferSlot) owner).scandium$getLayerIndex();
            return layer >= 0 && !dev.scandium.render.region.RegionManager.isManaged(layer);
        }
        return owner.scandium$isDynamic() && targetsMain() && mirror.hasData();
    }

    public static VulkanRuntime runtime() {
        return rt;
    }

    public static void waitIdleForPresent() {
        if (rt != null) {
            waitIdle();
        }
    }

    /** Whether the Vulkan frame can show everything: shaders translated and the renderer healthy. */
    private static boolean vulkanFrameComplete() {
        return active && VulkanBackend.pipelineGeneration() > 0;
    }

    /**
     * Stage 5, instead of glfwSwapBuffers: ends the frame, copies the picture into the next swapchain image and
     * presents it. The picture is the Vulkan frame, or - while that can't be complete, after a failure, or when
     * {@code /scandium present opengl} asks for it - the GL main target read back with glReadPixels.
     */
    public static void present() {
        if (rt == null || SLOTS[0] == null) {
            return;
        }
        try {
            dev.scandium.vulkan.present.VulkanPresent.Source source =
                    vulkanFrameComplete() && dev.scandium.vulkan.present.VulkanPresent.requestedSource()
                            == dev.scandium.vulkan.present.VulkanPresent.Source.VULKAN
                            ? dev.scandium.vulkan.present.VulkanPresent.Source.VULKAN
                            : dev.scandium.vulkan.present.VulkanPresent.Source.OPENGL;
            boolean wasActive = active;
            active = true; // the GL fallback still needs frame bookkeeping after a renderer failure
            Slot slot = frame();
            endPass();
            dev.scandium.vulkan.present.Swapchain swapchain = dev.scandium.vulkan.present.VulkanPresent.swapchain(rt);
            if (swapchain == null) {
                endFrame();
                active = wasActive;
                return;
            }
            int image;
            try (MemoryStack stack = stackPush()) {
                IntBuffer pIndex = stack.mallocInt(1);
                int result = KHRSwapchain.vkAcquireNextImageKHR(rt.context.device, swapchain.handle, Long.MAX_VALUE,
                        slot.imageAvailable, VK_NULL_HANDLE, pIndex);
                if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                    dev.scandium.vulkan.present.VulkanPresent.invalidate();
                    endFrame();
                    active = wasActive;
                    return;
                }
                if (result != VK_SUCCESS && result != KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                    check(result, "vkAcquireNextImageKHR");
                }
                image = pIndex.get(0);
            }
            long swapImage = swapchain.images[image];
            if (screenshotRequested && wasActive) {
                target.recordReadback(slot.cmd);
                screenshotRequested = false;
                pendingScreenshot = true;
            }
            try (MemoryStack stack = stackPush()) {
                imageBarrier(slot.cmd, stack, swapImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                if (source == dev.scandium.vulkan.present.VulkanPresent.Source.VULKAN) {
                    blitTargetTo(slot.cmd, stack, swapImage, swapchain);
                } else {
                    copyGlPictureTo(slot, stack, swapImage, swapchain);
                }
                imageBarrier(slot.cmd, stack, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            }
            check(vkEndCommandBuffer(slot.cmd), "vkEndCommandBuffer");
            try (MemoryStack stack = stackPush()) {
                VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                        .sType$Default()
                        .waitSemaphoreCount(1)
                        .pWaitSemaphores(stack.longs(slot.imageAvailable))
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                        .pCommandBuffers(stack.pointers(slot.cmd))
                        .pSignalSemaphores(stack.longs(swapchain.renderFinished[image]));
                VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                        .sType$Default()
                        .pWaitSemaphores(stack.longs(swapchain.renderFinished[image]))
                        .swapchainCount(1)
                        .pSwapchains(stack.longs(swapchain.handle))
                        .pImageIndices(stack.ints(image));
                int result;
                synchronized (rt.context.queue) {
                    check(vkQueueSubmit(rt.context.queue, submit, slot.fence), "vkQueueSubmit");
                    result = KHRSwapchain.vkQueuePresentKHR(rt.context.queue, presentInfo);
                }
                if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                    dev.scandium.vulkan.present.VulkanPresent.invalidate();
                } else if (result != VK_SUCCESS) {
                    check(result, "vkQueuePresentKHR");
                }
            }
            slot.submitted = true;
            frameOpen = false;
            lastSubmitted = slotIndex;
            if (pendingScreenshot) {
                pendingScreenshot = false;
                check(vkWaitForFences(rt.context.device, slot.fence, true, Long.MAX_VALUE), "vkWaitForFences");
                saveScreenshot();
            }
            slotIndex = (slotIndex + 1) % FRAMES;
            active = wasActive;
            presentSource = source;
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static boolean pendingScreenshot;
    private static volatile dev.scandium.vulkan.present.VulkanPresent.Source presentSource;

    public static dev.scandium.vulkan.present.VulkanPresent.Source presentSource() {
        return presentSource;
    }

    private static void blitTargetTo(VkCommandBuffer cmd, MemoryStack stack, long swapImage,
                                     dev.scandium.vulkan.present.Swapchain swapchain) {
        imageBarrier(cmd, stack, target.colorImage, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
        long b = blit.address();
        VkImageSubresourceLayers.naspectMask(b + VkImageBlit.SRCSUBRESOURCE, VK_IMAGE_ASPECT_COLOR_BIT);
        VkImageSubresourceLayers.nlayerCount(b + VkImageBlit.SRCSUBRESOURCE, 1);
        VkImageSubresourceLayers.naspectMask(b + VkImageBlit.DSTSUBRESOURCE, VK_IMAGE_ASPECT_COLOR_BIT);
        VkImageSubresourceLayers.nlayerCount(b + VkImageBlit.DSTSUBRESOURCE, 1);
        long src1 = b + VkImageBlit.SRCOFFSETS + VkOffset3D.SIZEOF;
        VkOffset3D.nx(src1, target.width);
        VkOffset3D.ny(src1, target.height);
        VkOffset3D.nz(src1, 1);
        long dst1 = b + VkImageBlit.DSTOFFSETS + VkOffset3D.SIZEOF;
        VkOffset3D.nx(dst1, swapchain.width);
        VkOffset3D.ny(dst1, swapchain.height);
        VkOffset3D.nz(dst1, 1);
        // Blit converts RGBA -> BGRA and scales if the window and the game target differ for a frame (resize).
        boolean sameSize = target.width == swapchain.width && target.height == swapchain.height;
        vkCmdBlitImage(cmd, target.colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, swapImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, sameSize ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
        imageBarrier(cmd, stack, target.colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    }

    /**
     * Fallback picture: the GL main target, read with glReadPixels straight into a mapped Vulkan buffer (GL rows go
     * bottom-up, so they are flipped in place), then copied into the swapchain image.
     */
    private static void copyGlPictureTo(Slot slot, MemoryStack stack, long swapImage,
                                        dev.scandium.vulkan.present.Swapchain swapchain) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int w = Math.min(main.width, swapchain.width);
        int h = Math.min(main.height, swapchain.height);
        long size = (long) w * h * 4;
        if (slot.glReadback == null || slot.glReadback.size < size) {
            if (slot.glReadback != null) {
                slot.glReadback.close(); // this slot's previous frame is finished (fence waited in beginFrame)
            }
            slot.glReadback = hostBuffer(Math.max(size, 4L << 20), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        }
        long address = slot.glReadback.mappedAddress;
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 4);
        org.lwjgl.opengl.GL11.nglReadPixels(0, 0, w, h,
                swapchain.isBgra() ? org.lwjgl.opengl.GL12.GL_BGRA : org.lwjgl.opengl.GL11.GL_RGBA,
                org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, address);
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, 0);
        long row = (long) w * 4;
        long temp = MemoryUtil.nmemAlloc(row);
        try {
            for (int y = 0; y < h / 2; y++) {
                long top = address + y * row;
                long bottom = address + (long) (h - 1 - y) * row;
                MemoryUtil.memCopy(top, temp, row);
                MemoryUtil.memCopy(bottom, top, row);
                MemoryUtil.memCopy(temp, bottom, row);
            }
        } finally {
            MemoryUtil.nmemFree(temp);
        }
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        long r = region.address();
        VkImageSubresourceLayers.naspectMask(r + VkBufferImageCopy.IMAGESUBRESOURCE, VK_IMAGE_ASPECT_COLOR_BIT);
        VkImageSubresourceLayers.nlayerCount(r + VkBufferImageCopy.IMAGESUBRESOURCE, 1);
        VkExtent3D.nwidth(r + VkBufferImageCopy.IMAGEEXTENT, w);
        VkExtent3D.nheight(r + VkBufferImageCopy.IMAGEEXTENT, h);
        VkExtent3D.ndepth(r + VkBufferImageCopy.IMAGEEXTENT, 1);
        vkCmdCopyBufferToImage(slot.cmd, slot.glReadback.handle, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    private static void imageBarrier(VkCommandBuffer cmd, MemoryStack stack, long image, int oldLayout, int newLayout,
                                     int srcAccess, int dstAccess, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
    }

    private static void beginPass(Slot slot) {
        if (passOpen) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack)
                    .sType$Default().renderPass(renderPass).framebuffer(target.framebuffer);
            writeRect(begin.address() + VkRenderPassBeginInfo.RENDERAREA, 0, 0, target.width, target.height);
            vkCmdBeginRenderPass(slot.cmd, begin, VK_SUBPASS_CONTENTS_INLINE);
        }
        passOpen = true;
    }

    private static void endPass() {
        if (passOpen) {
            vkCmdEndRenderPass(SLOTS[slotIndex].cmd);
            passOpen = false;
        }
    }

    // ================================================================== clears

    public static void onClear(int mask) {
        if (!active || !targetsMain()) {
            return;
        }
        try {
            Slot slot = frame();
            beginPass(slot);
            boolean color = (mask & 0x4000) != 0; // GL_COLOR_BUFFER_BIT
            boolean depth = (mask & 0x0100) != 0; // GL_DEPTH_BUFFER_BIT
            if (!color && !depth) {
                return;
            }
            try (MemoryStack stack = stackPush()) {
                VkClearAttachment.Buffer attachments = VkClearAttachment.calloc((color ? 1 : 0) + (depth ? 1 : 0), stack);
                int i = 0;
                if (color) {
                    VkClearAttachment a = attachments.get(i++);
                    a.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0);
                    a.clearValue().color().float32(0, GlStateTracker.clearR).float32(1, GlStateTracker.clearG)
                            .float32(2, GlStateTracker.clearB).float32(3, GlStateTracker.clearA);
                }
                if (depth) {
                    VkClearAttachment a = attachments.get(i);
                    a.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    a.clearValue().depthStencil().depth((float) GlStateTracker.clearDepth);
                }
                VkClearRect.Buffer rect = VkClearRect.calloc(1, stack);
                VkClearRect.nlayerCount(rect.address(), 1);
                setScissorRect(rect.address() + VkClearRect.RECT);
                vkCmdClearAttachments(slot.cmd, attachments, rect);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ================================================================== textures

    public static void onTexParameter(int name, int value) {
        if (!active) {
            return;
        }
        TextureMirror mirror = mirrorForBound(true);
        if (mirror == null) {
            return;
        }
        switch (name) {
            case 0x2801 -> { // GL_TEXTURE_MIN_FILTER
                mirror.linear = value == 0x2601 || value == 0x2701 || value == 0x2703;
                mirror.mipmap = value >= 0x2700 && value <= 0x2703;
            }
            case 0x2802 -> { // GL_TEXTURE_WRAP_S
                mirror.clamp = value == 0x812F || value == 0x812D;
            }
            case 0x813D -> mirror.maxLevel = Math.max(0, value); // GL_TEXTURE_MAX_LEVEL
            default -> {
            }
        }
    }

    public static void onTexImage(int level, int internalFormat, int width, int height) {
        if (!active || level != 0) {
            return; // levels 1..n are allocated together with level 0
        }
        try {
            int texture = GlStateTracker.boundTexture();
            int vkFormat = vkFormat(internalFormat);
            if (texture == 0 || vkFormat == VK_FORMAT_UNDEFINED || width <= 0 || height <= 0) {
                return;
            }
            TextureMirror mirror = mirrorForBound(true);
            if (mirror.texture != null) {
                VulkanTexture old = mirror.texture;
                defer(old::close);
            }
            int levels = Math.min(mirror.maxLevel + 1, VulkanTexture.fullMipChain(width, height));
            mirror.vkFormat = vkFormat;
            mirror.texture = VulkanTexture.create(rt.context, rt.allocator, width, height, Math.max(1, levels), vkFormat, 0);
            mirror.uploaded = false;
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void onTexSubImage(int level, int x, int y, int width, int height, int format, int type, long pixels) {
        if (!active || pixels == 0 || width <= 0 || height <= 0 || type != 0x1401) { // GL_UNSIGNED_BYTE only
            return;
        }
        try {
            TextureMirror mirror = mirrorForBound(false);
            if (mirror == null || mirror.texture == null || level >= mirror.texture.mipLevels) {
                return;
            }
            int srcBpp = switch (format) {
                case 0x1908, 0x80E1 -> 4; // GL_RGBA, GL_BGRA
                case 0x1907 -> 3;         // GL_RGB
                case 0x8227 -> 2;         // GL_RG
                case 0x1903 -> 1;         // GL_RED
                default -> 0;
            };
            int dstBpp = mirror.vkFormat == VK_FORMAT_R8_UNORM ? 1 : mirror.vkFormat == VK_FORMAT_R8G8_UNORM ? 2 : 4;
            if (srcBpp == 0 || (srcBpp != dstBpp && !(srcBpp == 3 && dstBpp == 4))) {
                return;
            }
            Slot slot = frame();
            long size = (long) width * height * dstBpp;
            long offset = ringAlloc(slot, size, 16);
            if (offset < 0) {
                return;
            }
            int rowLength = GlStateTracker.unpackRowLength > 0 ? GlStateTracker.unpackRowLength : width;
            int alignment = Math.max(1, GlStateTracker.unpackAlignment);
            long srcStride = ((long) rowLength * srcBpp + alignment - 1) / alignment * alignment;
            long src = pixels + GlStateTracker.unpackSkipRows * srcStride + (long) GlStateTracker.unpackSkipPixels * srcBpp;
            long dst = slot.ring.mappedAddress + offset;
            for (int row = 0; row < height; row++) {
                long s = src + row * srcStride;
                long d = dst + (long) row * width * dstBpp;
                if (srcBpp == dstBpp && format != 0x80E1) {
                    MemoryUtil.memCopy(s, d, (long) width * dstBpp);
                } else {
                    for (int px = 0; px < width; px++) {
                        long sp = s + (long) px * srcBpp;
                        long dp = d + (long) px * 4;
                        byte r = MemoryUtil.memGetByte(sp);
                        byte g = MemoryUtil.memGetByte(sp + 1);
                        byte b = MemoryUtil.memGetByte(sp + 2);
                        byte a = srcBpp == 4 ? MemoryUtil.memGetByte(sp + 3) : (byte) 0xFF;
                        if (format == 0x80E1) { // BGRA -> RGBA
                            byte t = r;
                            r = b;
                            b = t;
                        }
                        MemoryUtil.memPutByte(dp, r);
                        MemoryUtil.memPutByte(dp + 1, g);
                        MemoryUtil.memPutByte(dp + 2, b);
                        MemoryUtil.memPutByte(dp + 3, a);
                    }
                }
            }
            endPass();
            mirror.texture.transition(slot.cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0).bufferOffset(offset);
                region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).layerCount(1);
                region.get(0).imageOffset().set(x, y, 0);
                region.get(0).imageExtent().set(width, height, 1);
                vkCmdCopyBufferToImage(slot.cmd, slot.ring.handle, mirror.texture.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }
            mirror.uploaded = true;
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void onDeleteTexture(int texture) {
        if (!active) {
            return;
        }
        TextureMirror mirror = TEXTURES.remove(texture);
        if (mirror != null && mirror.texture != null) {
            VulkanTexture old = mirror.texture;
            defer(old::close);
        }
    }

    private static TextureMirror mirrorForBound(boolean create) {
        int texture = GlStateTracker.boundTexture();
        if (texture == 0) {
            return null;
        }
        TextureMirror mirror = TEXTURES.get(texture);
        if (mirror == null && create) {
            mirror = new TextureMirror();
            TEXTURES.put(texture, mirror);
        }
        return mirror;
    }

    private static int vkFormat(int internalFormat) {
        return switch (internalFormat) {
            case 0x1908, 0x8058, 0x1907, 0x8051 -> VK_FORMAT_R8G8B8A8_UNORM; // RGBA, RGBA8, RGB, RGB8
            case 0x1903, 0x8229 -> VK_FORMAT_R8_UNORM;                       // RED, R8
            case 0x8227, 0x822B -> VK_FORMAT_R8G8_UNORM;                     // RG, RG8
            default -> VK_FORMAT_UNDEFINED; // depth and float targets aren't mirrored
        };
    }

    // ================================================================== vertex buffers

    /** VertexBuffer.upload: copy the data now, the pointer is only valid during the call. */
    public static void onUpload(ShadowVertexBuffer owner, BufferBuilder.RenderedBuffer rendered) {
        if (!active) {
            return;
        }
        VertexMirror mirror = owner.scandium$mirror();
        if (mirror.terrain) {
            // Region batching reports its uploads itself (VertexBufferRegionMixin); only the vanilla path here.
            dev.scandium.render.region.TerrainBufferSlot slotInfo = (dev.scandium.render.region.TerrainBufferSlot) owner;
            int layer = slotInfo.scandium$getLayerIndex();
            if (layer >= 0 && !dev.scandium.render.region.RegionManager.isManaged(layer)) {
                onTerrainUpload(slotInfo.scandium$getSectionX(), slotInfo.scandium$getSectionY(),
                        slotInfo.scandium$getSectionZ(), layer, rendered, false);
            }
            return;
        }
        try {
            BufferBuilder.DrawState state = rendered.drawState();
            Slot slot = frame();
            boolean dynamic = owner.scandium$isDynamic();
            if (!state.indexOnly()) {
                mirror.format = state.format();
                mirror.mode = state.mode();
                mirror.vertexCount = state.vertexCount();
                ByteBuffer vertices = rendered.vertexBuffer();
                long size = vertices.remaining();
                if (dynamic) {
                    long offset = ringAlloc(slot, size, 16);
                    if (offset < 0) {
                        mirror.vertexBuffer = 0;
                        return;
                    }
                    MemoryUtil.memCopy(MemoryUtil.memAddress(vertices), slot.ring.mappedAddress + offset, size);
                    mirror.vertexBuffer = slot.ring.handle;
                    mirror.vertexOffset = offset;
                    mirror.frame = frameNumber;
                } else {
                    replaceOwned(mirror, true);
                    mirror.ownedVertex = hostBuffer(Math.max(16, size), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                    MemoryUtil.memCopy(MemoryUtil.memAddress(vertices), mirror.ownedVertex.mappedAddress, size);
                    mirror.vertexBuffer = mirror.ownedVertex.handle;
                    mirror.vertexOffset = 0;
                    mirror.frame = -1;
                }
            }
            mirror.indexCount = state.indexCount();
            mirror.sequentialIndex = state.sequentialIndex();
            mirror.indexType = state.indexType();
            if (!state.sequentialIndex()) {
                ByteBuffer indices = rendered.indexBuffer();
                long size = indices.remaining();
                if (dynamic) {
                    long offset = ringAlloc(slot, size, 16);
                    if (offset < 0) {
                        mirror.vertexBuffer = 0;
                        return;
                    }
                    MemoryUtil.memCopy(MemoryUtil.memAddress(indices), slot.ring.mappedAddress + offset, size);
                    mirror.indexBuffer = slot.ring.handle;
                    mirror.indexOffset = offset;
                    mirror.frame = frameNumber;
                } else {
                    replaceOwned(mirror, false);
                    mirror.ownedIndex = hostBuffer(Math.max(16, size), VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
                    MemoryUtil.memCopy(MemoryUtil.memAddress(indices), mirror.ownedIndex.mappedAddress, size);
                    mirror.indexBuffer = mirror.ownedIndex.handle;
                    mirror.indexOffset = 0;
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void onClose(ShadowVertexBuffer owner) {
        if (!active) {
            return;
        }
        VertexMirror mirror = owner.scandium$mirror();
        replaceOwned(mirror, true);
        replaceOwned(mirror, false);
        mirror.vertexBuffer = 0;
    }

    private static void replaceOwned(VertexMirror mirror, boolean vertex) {
        VulkanBuffer old = vertex ? mirror.ownedVertex : mirror.ownedIndex;
        if (old != null) {
            defer(old::close); // an earlier draw of this frame may still read it
            if (vertex) {
                mirror.ownedVertex = null;
            } else {
                mirror.ownedIndex = null;
            }
        }
    }

    // ================================================================== draws

    /** VertexBuffer._drawWithShader, after the shader's uniforms and samplers were applied. */
    public static void onDraw(ShadowVertexBuffer owner, ShaderInstance shader) {
        if (!active) {
            return;
        }
        VertexMirror mirror = owner.scandium$mirror();
        if (mirror.terrain || !targetsMain()) {
            return;
        }
        try {
            if (!mirror.hasData() || (mirror.frame >= 0 && mirror.frame != frameNumber)) {
                skippedThisFrame++;
                return;
            }
            GlslTranslator.Result translated = VulkanBackend.translation(shader.getName());
            if (translated == null) {
                skippedThisFrame++;
                return;
            }
            Slot slot = frame();
            DrawSetup setup = prepare(slot, shader, translated, mirror.format,
                    PipelineState.topologyFromGl(mirror.mode.asGLMode));
            long uniformOffset = 0;
            if (!translated.uniforms().isEmpty()) {
                uniformOffset = ringAlloc(slot, translated.uniformBlockSize(), rt.context.minUniformBufferOffsetAlignment);
                if (uniformOffset < 0) {
                    return;
                }
                packUniforms(shader, translated, slot.ring.mappedAddress + uniformOffset);
            }

            long indexBuffer = 0;
            long indexOffset = 0;
            int indexType = VK_INDEX_TYPE_UINT32;
            boolean indexed;
            if (!mirror.sequentialIndex) {
                indexed = true;
                indexBuffer = mirror.indexBuffer;
                indexOffset = mirror.indexOffset;
                indexType = mirror.indexType == VertexFormat.IndexType.SHORT ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
            } else if (mirror.mode == VertexFormat.Mode.QUADS || mirror.mode == VertexFormat.Mode.LINES) {
                indexed = true;
                ensureSequentialIndices(mirror.indexCount);
                indexBuffer = (mirror.mode == VertexFormat.Mode.QUADS ? quadIndices : lineIndices).handle;
            } else {
                indexed = false;
            }

            beginPass(slot);
            VkCommandBuffer cmd = slot.cmd;
            try (MemoryStack stack = stackPush()) {
                // Negative height flips Y so the image (and the winding) matches GL; GL's viewport origin is
                // bottom-left, Vulkan's top-left.
                setViewportAndScissor(cmd, stack);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, setup.pipeline);
                bindSet(cmd, stack, setup, translated, uniformOffset);
                vkCmdBindVertexBuffers(cmd, 0, stack.longs(mirror.vertexBuffer), stack.longs(mirror.vertexOffset));
                if (indexed) {
                    vkCmdBindIndexBuffer(cmd, indexBuffer, indexOffset, indexType);
                    vkCmdDrawIndexed(cmd, mirror.indexCount, 1, 0, 0, 0);
                } else {
                    vkCmdDraw(cmd, mirror.indexCount, 1, 0, 0);
                }
            }
            drawsThisFrame++;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Everything a draw with one shader + state needs except per-draw data (uniform offset, buffers). */
    private record DrawSetup(PipelineFactory.Program program, long pipeline, long set) {
    }

    /**
     * Program, vertex layout and pipeline for the current GL state, textures made readable (outside the render
     * pass), and a descriptor set. The uniform block is bound as a dynamic buffer: one set serves any number of
     * draws, each with its own offset into the frame ring (terrain draws thousands of sections per layer).
     */
    private static DrawSetup prepare(Slot slot, ShaderInstance shader, GlslTranslator.Result translated,
                                     VertexFormat format, int topology) {
        PipelineFactory.Program program = PROGRAMS.computeIfAbsent(shader.getName(),
                name -> PipelineFactory.createProgram(rt, name, translated));
        VertexInputMapper.Layout input = INPUTS.computeIfAbsent(new InputKey(shader.getName(), format),
                k -> VertexInputMapper.map(rt.context, k.format(), translated.vertexInputs()));
        PipelineState state = GlStateTracker.snapshot(topology);
        long pipeline = PIPELINES.computeIfAbsent(new PipelineKey(shader.getName(), format, state),
                k -> PipelineFactory.createPipeline(rt, program, input, k.state(), renderPass));

        long[] views = new long[translated.samplers().size()];
        long[] samplers = new long[views.length];
        Map<String, Object> samplerMap = ((ShaderInstanceAccessor) shader).scandium$getSamplerMap();
        for (int i = 0; i < views.length; i++) {
            TextureMirror texture = TEXTURES.get(textureId(samplerMap.get(translated.samplers().get(i).name())));
            if (texture != null && texture.texture != null && texture.uploaded) {
                if (texture.texture.layout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    endPass();
                    texture.texture.transition(slot.cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
                views[i] = texture.texture.view;
                samplers[i] = rt.samplers.get(texture.linear, texture.mipmap, texture.clamp);
            } else {
                views[i] = fallbackView(slot);
                samplers[i] = rt.samplers.get(false, false, true);
            }
        }
        long set = slot.descriptors.allocate(program.setLayout());
        writeDescriptors(set, slot, translated, views, samplers);
        return new DrawSetup(program, pipeline, set);
    }

    private static void bindSet(VkCommandBuffer cmd, MemoryStack stack, DrawSetup setup,
                                GlslTranslator.Result translated, long uniformOffset) {
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, setup.program.pipelineLayout(), 0,
                stack.longs(setup.set), translated.uniforms().isEmpty() ? null : stack.ints((int) uniformOffset));
    }

    /** Current GL viewport/scissor; the negative height flips Y so the image and winding match GL. */
    private static void setViewportAndScissor(VkCommandBuffer cmd, MemoryStack stack) {
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        long v = viewport.address();
        VkViewport.nx(v, GlStateTracker.viewportX);
        VkViewport.ny(v, target.height - GlStateTracker.viewportY);
        VkViewport.nwidth(v, GlStateTracker.viewportW);
        VkViewport.nheight(v, -GlStateTracker.viewportH);
        VkViewport.nmaxDepth(v, 1.0f);
        vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        setScissorRect(scissor.address());
        vkCmdSetScissor(cmd, 0, scissor);
    }

    // ================================================================== terrain (stage 6)

    /** One chunk section's geometry for one block layer. */
    private static final class TerrainLayer {
        VulkanBuffer vertices;
        VulkanBuffer indices;
        int indexCount;
        int indexType = VK_INDEX_TYPE_UINT32;
        boolean sequential = true;
        /** Region batching stores vertices relative to the region corner, vanilla to the section corner. */
        boolean regionRelative;
        /** Opaque region-relative sections live in their region's arena instead of an own buffer (stage 8). */
        TerrainArena arena;
        long arenaOffset;
        long arenaSize;
    }

    /**
     * Stage 8: one device-local vertex buffer per region (4x4x4 sections) and opaque layer, sub-allocated per
     * section. All sections of a region share the ChunkOffset (vertices are region-relative) and the quad index
     * buffer, so the whole region is one vkCmdDrawIndexedIndirect instead of one bind + draw per section.
     */
    private static final class TerrainArena {
        VulkanBuffer buffer;
        final dev.scandium.vulkan.memory.RangeAllocator ranges;
        final int regionX;
        final int regionY;
        final int regionZ;

        TerrainArena(VulkanBuffer buffer, int regionX, int regionY, int regionZ) {
            this.buffer = buffer;
            this.ranges = new dev.scandium.vulkan.memory.RangeAllocator(buffer.size);
            this.regionX = regionX;
            this.regionY = regionY;
            this.regionZ = regionZ;
        }
    }

    @SuppressWarnings("unchecked")
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<TerrainArena>[] ARENAS =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap[5];

    static {
        for (int i = 0; i < ARENAS.length; i++) {
            ARENAS[i] = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        }
    }

    private static final long ARENA_INITIAL = 1L << 20;
    private static final int BLOCK_STRIDE = com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK.getVertexSize();

    /** Copies section vertices into the region's arena; false if the ring is full (caller falls back). */
    private static boolean uploadToArena(Slot slot, TerrainLayer data, int sx, int sy, int sz, int layer, ByteBuffer src) {
        long size = src.remaining();
        long staging = ringAlloc(slot, size, 16);
        if (staging < 0) {
            return false;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(src), slot.ring.mappedAddress + staging, size);
        int rx = sx >> 2;
        int ry = sy >> 2;
        int rz = sz >> 2;
        TerrainArena arena = ARENAS[layer].get(net.minecraft.core.SectionPos.asLong(rx, ry, rz));
        if (arena == null) {
            arena = new TerrainArena(arenaBuffer(Math.max(ARENA_INITIAL, size * 2)), rx, ry, rz);
            ARENAS[layer].put(net.minecraft.core.SectionPos.asLong(rx, ry, rz), arena);
        }
        long offset = arena.ranges.allocate(size, BLOCK_STRIDE);
        if (offset < 0) {
            growArena(slot, arena, Math.max(arena.buffer.size * 2, arena.buffer.size + size * 2));
            offset = arena.ranges.allocate(size, BLOCK_STRIDE);
        }
        endPass();
        try (MemoryStack stack = stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            long r = region.address();
            VkBufferCopy.nsrcOffset(r, staging);
            VkBufferCopy.ndstOffset(r, offset);
            VkBufferCopy.nsize(r, size);
            vkCmdCopyBuffer(slot.cmd, slot.ring.handle, arena.buffer.handle, region);
        }
        data.arena = arena;
        data.arenaOffset = offset;
        data.arenaSize = size;
        return true;
    }

    private static VulkanBuffer arenaBuffer(long size) {
        return VulkanBuffer.create(rt.context, rt.allocator, size,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    }

    /** Grows by a GPU copy into a bigger buffer: offsets stay valid; the old buffer goes after its frames. */
    private static void growArena(Slot slot, TerrainArena arena, long newSize) {
        VulkanBuffer old = arena.buffer;
        VulkanBuffer grown = arenaBuffer(newSize);
        endPass();
        try (MemoryStack stack = stackPush()) {
            // Sections copied into the old buffer earlier this frame must land before it is moved.
            VkMemoryBarrier.Buffer before = VkMemoryBarrier.calloc(1, stack);
            before.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(slot.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, before,
                    null, null);
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            long r = region.address();
            VkBufferCopy.nsize(r, old.size);
            vkCmdCopyBuffer(slot.cmd, old.handle, grown.handle, region);
            // Later copies into the new buffer must not race the move above.
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(slot.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier,
                    null, null);
        }
        arena.buffer = grown;
        arena.ranges.extend(newSize);
        defer(old::close);
    }

    private static void releaseArenaSlot(TerrainLayer data) {
        if (data.arena != null) {
            TerrainArena arena = data.arena;
            long offset = data.arenaOffset;
            long size = data.arenaSize;
            data.arena = null;
            defer(() -> arena.ranges.free(offset, size)); // a submitted frame may still read the range
        }
    }

    /** Per-frame grouping of visible arena sections by region. */
    private static final class RegionBatch {
        TerrainArena arena;
        final it.unimi.dsi.fastutil.ints.IntArrayList counts = new it.unimi.dsi.fastutil.ints.IntArrayList();
        final it.unimi.dsi.fastutil.ints.IntArrayList vertexOffsets = new it.unimi.dsi.fastutil.ints.IntArrayList();
    }

    private static final it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<TerrainArena, RegionBatch> BATCHES =
            new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<>();
    private static final java.util.ArrayList<RegionBatch> BATCH_POOL = new java.util.ArrayList<>();

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<TerrainLayer[]> TERRAIN =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final int LAYERS = 5;

    /**
     * A section layer was uploaded (from the region path or the vanilla VertexBuffer path). Copies the geometry
     * right away: the source buffer is released after the call.
     */
    public static void onTerrainUpload(int sectionX, int sectionY, int sectionZ, int layer,
                                       BufferBuilder.RenderedBuffer rendered, boolean regionRelative) {
        if (!active || layer < 0 || layer >= LAYERS) {
            return;
        }
        try {
            BufferBuilder.DrawState state = rendered.drawState();
            Slot slot = frame();
            long key = net.minecraft.core.SectionPos.asLong(sectionX, sectionY, sectionZ);
            TerrainLayer[] layers = TERRAIN.computeIfAbsent(key, k -> new TerrainLayer[LAYERS]);
            TerrainLayer data = layers[layer];
            if (data == null) {
                data = new TerrainLayer();
                layers[layer] = data;
            }
            if (!state.indexOnly()) {
                if (state.vertexCount() == 0) {
                    freeLayer(layers, layer);
                    return;
                }
                if (data.vertices != null) {
                    VulkanBuffer old = data.vertices;
                    defer(old::close);
                    data.vertices = null;
                }
                releaseArenaSlot(data);
                data.regionRelative = regionRelative;
                boolean opaque = layer != dev.scandium.render.region.RegionManager.TRANSLUCENT_LAYER_INDEX;
                if (!(regionRelative && opaque && state.sequentialIndex()
                        && uploadToArena(slot, data, sectionX, sectionY, sectionZ, layer, rendered.vertexBuffer()))) {
                    data.vertices = deviceLocalCopy(slot, rendered.vertexBuffer(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                }
            }
            data.indexCount = state.indexCount();
            data.sequential = state.sequentialIndex();
            if (!state.sequentialIndex()) {
                ByteBuffer indices = rendered.indexBuffer();
                if (data.indices != null) {
                    VulkanBuffer old = data.indices;
                    defer(old::close);
                }
                data.indices = deviceLocalCopy(slot, indices, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
                data.indexType = state.indexType() == VertexFormat.IndexType.SHORT ? VK_INDEX_TYPE_UINT16
                        : VK_INDEX_TYPE_UINT32;
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Terrain lives in VRAM. The first version kept it in host-visible (system) memory, and the GPU read every
     * vertex of the world over PCIe each frame: the frame time was spent waiting for that (vkWaitForFences). Now
     * the data goes through the frame ring as staging and a GPU-side copy lands it in a device-local buffer.
     * The copy is recorded outside the render pass; the pass's external dependency orders it before the draws.
     * When the ring is full (a burst of uploads after a teleport), that section stays in host memory instead of
     * being dropped.
     */
    private static VulkanBuffer deviceLocalCopy(Slot slot, ByteBuffer source, int usage) {
        long size = Math.max(16, source.remaining());
        long offset = ringAlloc(slot, size, 16);
        if (offset < 0) {
            VulkanBuffer host = hostBuffer(size, usage);
            MemoryUtil.memCopy(MemoryUtil.memAddress(source), host.mappedAddress, source.remaining());
            return host;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(source), slot.ring.mappedAddress + offset, source.remaining());
        VulkanBuffer device = VulkanBuffer.create(rt.context, rt.allocator, size, usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        endPass();
        try (MemoryStack stack = stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            long r = region.address();
            VkBufferCopy.nsrcOffset(r, offset);
            VkBufferCopy.ndstOffset(r, 0);
            VkBufferCopy.nsize(r, size);
            vkCmdCopyBuffer(slot.cmd, slot.ring.handle, device.handle, region);
        }
        return device;
    }

    /** The section was rebuilt: drop layers its new geometry doesn't have. */
    public static void onSectionLayers(int sectionX, int sectionY, int sectionZ, int layerMask) {
        if (!active) {
            return;
        }
        TerrainLayer[] layers = TERRAIN.get(net.minecraft.core.SectionPos.asLong(sectionX, sectionY, sectionZ));
        if (layers == null) {
            return;
        }
        for (int layer = 0; layer < LAYERS; layer++) {
            if ((layerMask & (1 << layer)) == 0) {
                freeLayer(layers, layer);
            }
        }
    }

    /** The section was unloaded or moved to another position. */
    public static void onSectionFreed(int sectionX, int sectionY, int sectionZ) {
        if (!active) {
            return;
        }
        TerrainLayer[] layers = TERRAIN.remove(net.minecraft.core.SectionPos.asLong(sectionX, sectionY, sectionZ));
        if (layers != null) {
            for (int layer = 0; layer < LAYERS; layer++) {
                freeLayer(layers, layer);
            }
        }
    }

    /** World change or full rebuild (allChanged): every section is re-uploaded. */
    public static void clearTerrain() {
        if (!active) {
            return;
        }
        for (TerrainLayer[] layers : TERRAIN.values()) {
            for (int layer = 0; layer < LAYERS; layer++) {
                freeLayer(layers, layer);
            }
        }
        TERRAIN.clear();
        for (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<TerrainArena> arenas : ARENAS) {
            for (TerrainArena arena : arenas.values()) {
                VulkanBuffer buffer = arena.buffer;
                defer(buffer::close);
            }
            arenas.clear();
        }
    }

    private static void freeLayer(TerrainLayer[] layers, int layer) {
        TerrainLayer data = layers[layer];
        if (data == null) {
            return;
        }
        layers[layer] = null;
        releaseArenaSlot(data);
        if (data.vertices != null) {
            VulkanBuffer old = data.vertices;
            defer(old::close);
        }
        if (data.indices != null) {
            VulkanBuffer old = data.indices;
            defer(old::close);
        }
    }

    /**
     * LevelRenderer.renderSectionLayer, after the layer's shader and uniforms are set up: draws every visible
     * section of this layer into the Vulkan frame. Translucent goes back to front like vanilla (reverse order of
     * the visible list). Each section gets its own copy of the uniform block with its ChunkOffset patched in.
     */
    public static void onTerrainLayer(net.minecraft.client.renderer.RenderType renderType, double camX, double camY,
                                      double camZ, java.util.List<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection> visible) {
        if (!active || !targetsMain()) {
            return;
        }
        int layer = dev.scandium.render.region.RegionManager.layerIndex(renderType);
        ShaderInstance shader = com.mojang.blaze3d.systems.RenderSystem.getShader();
        if (layer < 0 || shader == null) {
            return;
        }
        try {
            GlslTranslator.Result translated = VulkanBackend.translation(shader.getName());
            if (translated == null) {
                return;
            }
            Slot slot = frame();
            VertexFormat format = com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK;
            DrawSetup setup = prepare(slot, shader, translated, format, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            int blockSize = translated.uniformBlockSize();
            long template = 0;
            int chunkOffset = -1;
            if (blockSize > 0) {
                template = MemoryUtil.nmemAlloc(blockSize);
                packUniforms(shader, translated, template);
                for (GlslTranslator.UniformMember member : translated.uniforms()) {
                    if (member.name().equals("ChunkOffset")) {
                        chunkOffset = member.offset();
                    }
                }
            }
            boolean translucent = layer == dev.scandium.render.region.RegionManager.TRANSLUCENT_LAYER_INDEX;
            int drawn = 0;
            try {
                beginPass(slot);
                VkCommandBuffer cmd = slot.cmd;
                try (MemoryStack stack = stackPush()) {
                    setViewportAndScissor(cmd, stack);
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, setup.pipeline);
                    LongBuffer vertexBuffer = stack.mallocLong(1);
                    LongBuffer vertexOffset = stack.longs(0);
                    IntBuffer dynamicOffset = stack.mallocInt(1);
                    LongBuffer sets = stack.longs(setup.set);
                    if (blockSize == 0) {
                        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                setup.program.pipelineLayout(), 0, sets, null);
                    }
                    int count = visible.size();
                    long boundIndexBuffer = VK_NULL_HANDLE;
                    int boundIndexType = -1;
                    for (int i = 0; i < count; i++) {
                        var section = visible.get(translucent ? count - 1 - i : i);
                        net.minecraft.core.BlockPos origin = section.getOrigin();
                        int sx = origin.getX() >> 4;
                        int sy = origin.getY() >> 4;
                        int sz = origin.getZ() >> 4;
                        TerrainLayer[] layers = TERRAIN.get(net.minecraft.core.SectionPos.asLong(sx, sy, sz));
                        TerrainLayer data = layers == null ? null : layers[layer];
                        if (data != null && data.arena != null && data.indexCount > 0) {
                            RegionBatch batch = BATCHES.get(data.arena);
                            if (batch == null) {
                                batch = BATCH_POOL.isEmpty() ? new RegionBatch() : BATCH_POOL.remove(BATCH_POOL.size() - 1);
                                batch.arena = data.arena;
                                BATCHES.put(data.arena, batch);
                            }
                            batch.counts.add(data.indexCount);
                            batch.vertexOffsets.add((int) (data.arenaOffset / BLOCK_STRIDE));
                            continue;
                        }
                        if (data == null || data.vertices == null || data.indexCount == 0
                                || (!data.sequential && data.indices == null)) {
                            continue;
                        }
                        if (blockSize > 0) {
                            long offset = ringAlloc(slot, blockSize, rt.context.minUniformBufferOffsetAlignment);
                            if (offset < 0) {
                                break;
                            }
                            long address = slot.ring.mappedAddress + offset;
                            MemoryUtil.memCopy(template, address, blockSize);
                            if (chunkOffset >= 0) {
                                // Same origin the GL path uses: region corner for region batching, else the section.
                                int ox = data.regionRelative ? (sx >> 2) << 6 : sx << 4;
                                int oy = data.regionRelative ? (sy >> 2) << 6 : sy << 4;
                                int oz = data.regionRelative ? (sz >> 2) << 6 : sz << 4;
                                MemoryUtil.memPutFloat(address + chunkOffset, (float) (ox - camX));
                                MemoryUtil.memPutFloat(address + chunkOffset + 4, (float) (oy - camY));
                                MemoryUtil.memPutFloat(address + chunkOffset + 8, (float) (oz - camZ));
                            }
                            dynamicOffset.put(0, (int) offset);
                            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    setup.program.pipelineLayout(), 0, sets, dynamicOffset);
                        }
                        vertexBuffer.put(0, data.vertices.handle);
                        vkCmdBindVertexBuffers(cmd, 0, vertexBuffer, vertexOffset);
                        // Opaque layers share one quad index buffer: bind it once, not per section.
                        long indexBuffer;
                        int indexType;
                        if (data.sequential) {
                            ensureSequentialIndices(data.indexCount);
                            indexBuffer = quadIndices.handle;
                            indexType = VK_INDEX_TYPE_UINT32;
                        } else {
                            indexBuffer = data.indices.handle;
                            indexType = data.indexType;
                        }
                        if (indexBuffer != boundIndexBuffer || indexType != boundIndexType) {
                            vkCmdBindIndexBuffer(cmd, indexBuffer, 0, indexType);
                            boundIndexBuffer = indexBuffer;
                            boundIndexType = indexType;
                        }
                        vkCmdDrawIndexed(cmd, data.indexCount, 1, 0, 0, 0);
                        drawn++;
                    }
                    drawn += drawRegionBatches(slot, cmd, stack, setup, sets, dynamicOffset, template, blockSize,
                            chunkOffset, camX, camY, camZ);
                }
            } finally {
                if (template != 0) {
                    MemoryUtil.nmemFree(template);
                }
            }
            terrainDrawsThisFrame += drawn;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * One indirect multi-draw per region: commands {indexCount, 1, 0, vertexOffset, 0} go to the frame ring, the
     * region's ChunkOffset into one uniform block. Without multiDrawIndirect the same commands run as a loop of
     * vkCmdDrawIndexed, still with a single vertex-buffer bind per region.
     */
    private static int drawRegionBatches(Slot slot, VkCommandBuffer cmd, MemoryStack stack, DrawSetup setup,
                                         LongBuffer sets, IntBuffer dynamicOffset, long template, int blockSize,
                                         int chunkOffset, double camX, double camY, double camZ) {
        if (BATCHES.isEmpty()) {
            return 0;
        }
        int sections = 0;
        int maxIndices = 0;
        for (RegionBatch batch : BATCHES.values()) {
            for (int i = 0; i < batch.counts.size(); i++) {
                maxIndices = Math.max(maxIndices, batch.counts.getInt(i));
            }
        }
        ensureSequentialIndices(maxIndices);
        vkCmdBindIndexBuffer(cmd, quadIndices.handle, 0, VK_INDEX_TYPE_UINT32);
        LongBuffer vertexBuffer = stack.mallocLong(1);
        LongBuffer zero = stack.longs(0);
        for (RegionBatch batch : BATCHES.values()) {
            int n = batch.counts.size();
            TerrainArena arena = batch.arena;
            if (blockSize > 0) {
                long offset = ringAlloc(slot, blockSize, rt.context.minUniformBufferOffsetAlignment);
                if (offset < 0) {
                    break;
                }
                long address = slot.ring.mappedAddress + offset;
                MemoryUtil.memCopy(template, address, blockSize);
                if (chunkOffset >= 0) {
                    MemoryUtil.memPutFloat(address + chunkOffset, (float) ((arena.regionX << 6) - camX));
                    MemoryUtil.memPutFloat(address + chunkOffset + 4, (float) ((arena.regionY << 6) - camY));
                    MemoryUtil.memPutFloat(address + chunkOffset + 8, (float) ((arena.regionZ << 6) - camZ));
                }
                dynamicOffset.put(0, (int) offset);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, setup.program.pipelineLayout(), 0, sets,
                        dynamicOffset);
            }
            vertexBuffer.put(0, arena.buffer.handle);
            vkCmdBindVertexBuffers(cmd, 0, vertexBuffer, zero);
            if (rt.context.multiDrawIndirect) {
                long commands = ringAlloc(slot, n * 20L, 4);
                if (commands < 0) {
                    break;
                }
                long address = slot.ring.mappedAddress + commands;
                for (int i = 0; i < n; i++) {
                    long c = address + i * 20L;
                    MemoryUtil.memPutInt(c, batch.counts.getInt(i));         // indexCount
                    MemoryUtil.memPutInt(c + 4, 1);                          // instanceCount
                    MemoryUtil.memPutInt(c + 8, 0);                          // firstIndex
                    MemoryUtil.memPutInt(c + 12, batch.vertexOffsets.getInt(i)); // vertexOffset
                    MemoryUtil.memPutInt(c + 16, 0);                         // firstInstance
                }
                vkCmdDrawIndexedIndirect(cmd, slot.ring.handle, commands, n, 20);
            } else {
                for (int i = 0; i < n; i++) {
                    vkCmdDrawIndexed(cmd, batch.counts.getInt(i), 1, 0, batch.vertexOffsets.getInt(i), 0);
                }
            }
            sections += n;
            regionDrawsThisFrame++;
        }
        for (RegionBatch batch : BATCHES.values()) {
            batch.counts.clear();
            batch.vertexOffsets.clear();
            batch.arena = null;
            BATCH_POOL.add(batch);
        }
        BATCHES.clear();
        return sections;
    }

    private static void writeDescriptors(long set, Slot slot, GlslTranslator.Result translated, long[] views,
                                         long[] samplers) {
        try (MemoryStack stack = stackPush()) {
            boolean uniforms = !translated.uniforms().isEmpty();
            int count = (uniforms ? 1 : 0) + views.length;
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(count, stack);
            int w = 0;
            if (uniforms) {
                VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                info.get(0).buffer(slot.ring.handle).offset(0).range(translated.uniformBlockSize());
                writes.get(w++).sType$Default().dstSet(set).dstBinding(GlslTranslator.Result.UNIFORM_BINDING)
                        .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).pBufferInfo(info);
            }
            for (int i = 0; i < views.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).sampler(samplers[i]).imageView(views[i]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                writes.get(w++).sType$Default().dstSet(set).dstBinding(translated.samplers().get(i).binding())
                        .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            }
            vkUpdateDescriptorSets(rt.context.device, writes, null);
        }
    }

    /**
     * Writes the shader's current uniform values in the std140 layout computed by the translator. Values not set
     * by the game stay zero.
     */
    private static void packUniforms(ShaderInstance shader, GlslTranslator.Result translated, long address) {
        MemoryUtil.memSet(address, 0, translated.uniformBlockSize());
        for (GlslTranslator.UniformMember member : translated.uniforms()) {
            Uniform uniform = shader.getUniform(member.name());
            if (uniform == null) {
                continue;
            }
            long base = address + member.offset();
            int type = uniform.getType();
            int count = uniform.getCount();
            if (type <= Uniform.UT_INT4) {
                IntBuffer ints = uniform.getIntBuffer();
                for (int i = 0; i < Math.min(count, 4); i++) {
                    MemoryUtil.memPutInt(base + i * 4L, ints.get(i));
                }
            } else if (type <= Uniform.UT_FLOAT4) {
                FloatBuffer floats = uniform.getFloatBuffer();
                for (int i = 0; i < Math.min(count, 4); i++) {
                    MemoryUtil.memPutFloat(base + i * 4L, floats.get(i));
                }
            } else {
                // Matrices: column-major; std140 pads every column to 16 bytes.
                int columns = type == Uniform.UT_MAT2 ? 2 : type == Uniform.UT_MAT3 ? 3 : 4;
                FloatBuffer floats = uniform.getFloatBuffer();
                for (int c = 0; c < columns; c++) {
                    for (int r = 0; r < columns; r++) {
                        MemoryUtil.memPutFloat(base + c * 16L + r * 4L, floats.get(c * columns + r));
                    }
                }
            }
        }
    }

    private static int textureId(Object sampler) {
        if (sampler instanceof RenderTarget renderTarget) {
            return renderTarget.getColorTextureId();
        }
        if (sampler instanceof AbstractTexture texture) {
            return texture.getId();
        }
        if (sampler instanceof Integer id) {
            return id;
        }
        return 0;
    }

    private static boolean targetsMain() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        return main != null && GlStateTracker.drawFramebuffer == main.frameBufferId;
    }

    /** GL scissor (bottom-left origin) to a Vulkan rect, or the whole target when the test is off. */
    private static void setScissorRect(long rect) {
        int w = target.width;
        int h = target.height;
        if (!GlStateTracker.scissor) {
            writeRect(rect, 0, 0, w, h);
            return;
        }
        int x0 = Math.max(0, GlStateTracker.scissorX);
        int y0 = Math.max(0, h - (GlStateTracker.scissorY + GlStateTracker.scissorH));
        int x1 = Math.min(w, GlStateTracker.scissorX + GlStateTracker.scissorW);
        int y1 = Math.min(h, h - GlStateTracker.scissorY);
        writeRect(rect, x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    /**
     * Writes a VkRect2D at {@code address}. LWJGL's nested-struct accessors ({@code rect.extent()}) allocate a new
     * Java wrapper on every call; at thousands of draws per frame that was the largest source of garbage in the
     * game (/scandium alloc), so hot paths write fields by address.
     */
    private static void writeRect(long address, int x, int y, int width, int height) {
        VkOffset2D.nx(address + VkRect2D.OFFSET, x);
        VkOffset2D.ny(address + VkRect2D.OFFSET, y);
        VkExtent2D.nwidth(address + VkRect2D.EXTENT, width);
        VkExtent2D.nheight(address + VkRect2D.EXTENT, height);
    }

    // ================================================================== helpers

    private static long ringAlloc(Slot slot, long size, long alignment) {
        long offset = (slot.ringCursor + alignment - 1) / alignment * alignment;
        if (offset + size > slot.ring.size) {
            if (!ringOverflowLogged) {
                ringOverflowLogged = true;
                Scandium.LOGGER.info("Vulkan shadow renderer: frame ring full ({} MB) - uploads fall back to host "
                        + "memory for the rest of this frame", RING_SIZE >> 20);
            }
            return -1;
        }
        slot.ringCursor = offset + size;
        return offset;
    }

    private static VulkanBuffer hostBuffer(long size, int usage) {
        return VulkanBuffer.create(rt.context, rt.allocator, size, usage,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    /** Index buffers reproducing vanilla's shared sequential buffers for QUADS and LINES (4 vertices -> 6 indices). */
    private static void ensureSequentialIndices(int indexCount) {
        if (indexCount <= sequentialCapacity && quadIndices != null) {
            return;
        }
        int capacity = Math.max(QUAD_INDEX_START, Integer.highestOneBit(Math.max(1, indexCount - 1)) << 1);
        capacity = (capacity + 5) / 6 * 6;
        if (quadIndices != null) {
            VulkanBuffer oldQuads = quadIndices;
            VulkanBuffer oldLines = lineIndices;
            defer(oldQuads::close);
            defer(oldLines::close);
        }
        quadIndices = hostBuffer(capacity * 4L, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        lineIndices = hostBuffer(capacity * 4L, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        int[] quad = {0, 1, 2, 2, 3, 0};
        int[] line = {0, 1, 2, 3, 2, 1};
        for (int i = 0; i < capacity; i++) {
            int vertexBase = i / 6 * 4;
            MemoryUtil.memPutInt(quadIndices.mappedAddress + i * 4L, vertexBase + quad[i % 6]);
            MemoryUtil.memPutInt(lineIndices.mappedAddress + i * 4L, vertexBase + line[i % 6]);
        }
        sequentialCapacity = capacity;
    }

    /** 1x1 white texture for samplers whose GL texture has no Vulkan copy (render targets, early textures). */
    private static long fallbackView(Slot slot) {
        if (fallbackTexture == null) {
            fallbackTexture = VulkanTexture.create(rt.context, rt.allocator, 1, 1, 1, VK_FORMAT_R8G8B8A8_UNORM, 0);
            long offset = ringAlloc(slot, 4, 16);
            MemoryUtil.memPutInt(slot.ring.mappedAddress + offset, 0xFFFFFFFF);
            endPass();
            fallbackTexture.transition(slot.cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0).bufferOffset(offset);
                region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.get(0).imageExtent().set(1, 1, 1);
                vkCmdCopyBufferToImage(slot.cmd, slot.ring.handle, fallbackTexture.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }
            fallbackTexture.transition(slot.cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        return fallbackTexture.view;
    }

    /** Runs {@code action} once no submitted frame can still use the resource. */
    private static void defer(Runnable action) {
        int slot = frameOpen ? slotIndex : lastSubmitted;
        if (slot < 0) {
            action.run();
            return;
        }
        SLOTS[slot].deferred.add(action);
    }

    private static void runDeferred(Slot slot) {
        for (Runnable action : slot.deferred) {
            action.run();
        }
        slot.deferred.clear();
    }

    private static void waitIdle() {
        synchronized (rt.context.queue) {
            vkQueueWaitIdle(rt.context.queue);
        }
        for (Slot slot : SLOTS) {
            if (slot.submitted) {
                vkWaitForFences(rt.context.device, slot.fence, true, Long.MAX_VALUE);
                vkResetFences(rt.context.device, slot.fence);
                slot.submitted = false;
            }
            runDeferred(slot);
        }
    }

    private static void destroyPipelines() {
        for (long pipeline : PIPELINES.values()) {
            vkDestroyPipeline(rt.context.device, pipeline, null);
        }
        PIPELINES.clear();
        for (PipelineFactory.Program program : PROGRAMS.values()) {
            PipelineFactory.destroyProgram(rt.context.device, program);
        }
        PROGRAMS.clear();
        INPUTS.clear();
    }

    private static int pickDepthFormat() {
        int[] candidates = {VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT};
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            for (int format : candidates) {
                vkGetPhysicalDeviceFormatProperties(rt.context.physicalDevice, format, props);
                if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    return format;
                }
            }
        }
        return VK_FORMAT_D16_UNORM;
    }

    // ================================================================== screenshots

    public static boolean requestScreenshot() {
        if (!active) {
            return false;
        }
        screenshotRequested = true;
        return true;
    }

    private static void saveScreenshot() {
        int w = target.width;
        int h = target.height;
        long address = target.readback.mappedAddress;
        NativeImage image = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = MemoryUtil.memGetInt(address + ((long) y * w + x) * 4L); // bytes R,G,B,A = int ABGR (LE)
                image.setPixelRGBA(x, y, rgba | 0xFF000000);
            }
        }
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        Path file = dir.resolve("scandium-vulkan-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern(
                "yyyy-MM-dd_HH.mm.ss")) + ".png");
        Thread writer = new Thread(() -> {
            try (image) {
                java.nio.file.Files.createDirectories(dir);
                image.writeToFile(file);
                Scandium.LOGGER.info("Vulkan shadow frame saved to {}", file);
            } catch (Exception e) {
                Scandium.LOGGER.warn("Could not save Vulkan shadow frame", e);
            }
        }, "Scandium Vulkan Screenshot");
        writer.setDaemon(true);
        writer.start();
    }

    private static void fail(Throwable t) {
        active = false;
        Scandium.LOGGER.error("Vulkan shadow renderer disabled after an error (OpenGL rendering is unaffected)", t);
        stats = "failed: " + Objects.toString(t.getMessage());
    }
}
