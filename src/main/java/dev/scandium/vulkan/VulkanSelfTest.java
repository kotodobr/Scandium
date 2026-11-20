package dev.scandium.vulkan;

import dev.scandium.vulkan.memory.VulkanMemoryAllocator;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Proves that every part of the backend works on this machine before anything depends on it. Runs headless (no
 * window, no swapchain) and reads results back to the CPU:
 * <ol>
 *     <li>memory allocator: hundreds of sub-allocations, all returned afterwards</li>
 *     <li>compute: shader writes 65536 values into a storage buffer</li>
 *     <li>graphics: triangle into an offscreen target (render pass, pipeline, copy)</li>
 *     <li>textures: staging upload with generated mip chain, sampled through a descriptor set</li>
 * </ol>
 */
public final class VulkanSelfTest {
    private static final int COMPUTE_COUNT = 65536;
    private static final int SIZE = 64;

    private static final String COMPUTE_SHADER = """
            #version 450
            layout(local_size_x = 64) in;
            layout(std430, set = 0, binding = 0) buffer Data { uint values[]; };
            layout(push_constant) uniform Push { uint count; };
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i < count) {
                    values[i] = i * i + 7u;
                }
            }
            """;

    /** Triangle from gl_VertexIndex (no vertex buffer). Covers the image centre, not the corners. */
    private static final String TRIANGLE_VERTEX = """
            #version 450
            const vec2 POSITIONS[3] = vec2[](vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(0.0, 0.6));
            void main() {
                gl_Position = vec4(POSITIONS[gl_VertexIndex], 0.0, 1.0);
            }
            """;

    private static final String RED_FRAGMENT = """
            #version 450
            layout(location = 0) out vec4 color;
            void main() {
                color = vec4(1.0, 0.0, 0.0, 1.0);
            }
            """;

    /** One triangle covering the whole target. */
    private static final String FULLSCREEN_VERTEX = """
            #version 450
            const vec2 POSITIONS[3] = vec2[](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
            void main() {
                gl_Position = vec4(POSITIONS[gl_VertexIndex], 0.0, 1.0);
            }
            """;

    private static final String TEXTURED_FRAGMENT = """
            #version 450
            layout(set = 0, binding = 0) uniform sampler2D tex;
            layout(location = 0) out vec4 color;
            void main() {
                color = texture(tex, gl_FragCoord.xy / 64.0);
            }
            """;

    private VulkanSelfTest() {
    }

    public static void run(VulkanRuntime rt) {
        allocatorTest(rt);
        computeTest(rt);
        triangleTest(rt);
        textureTest(rt);
    }

    // ------------------------------------------------------------------ allocator

    private static void allocatorTest(VulkanRuntime rt) {
        long before = rt.allocator.usedBytes();
        List<VulkanBuffer> buffers = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                long size = 4096L << (i % 5); // 4 KB .. 64 KB
                VulkanBuffer buffer = VulkanBuffer.create(rt.context, rt.allocator, size,
                        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                MemoryUtil.memSet(buffer.mappedAddress, i & 0xFF, size); // would crash on a bad mapping
                buffers.add(buffer);
            }
        } finally {
            buffers.forEach(VulkanBuffer::close);
        }
        if (rt.allocator.usedBytes() != before) {
            throw new VulkanException("Allocator self-test: " + (rt.allocator.usedBytes() - before)
                    + " bytes not returned");
        }
    }

    // ------------------------------------------------------------------ compute

    private static void computeTest(VulkanRuntime rt) {
        VkDevice device = rt.context.device;
        long shader = VK_NULL_HANDLE;
        long setLayout = VK_NULL_HANDLE;
        long pipelineLayout = VK_NULL_HANDLE;
        long pipeline = VK_NULL_HANDLE;
        try (MemoryStack stack = stackPush();
             VulkanBuffer buffer = VulkanBuffer.create(rt.context, rt.allocator, COMPUTE_COUNT * 4L,
                     VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            MemoryUtil.memSet(buffer.mappedAddress, 0, buffer.size);
            shader = rt.compiler.compileModule(rt.context, COMPUTE_SHADER, SpirvCompiler.Stage.COMPUTE, "selftest.comp");
            setLayout = createSetLayout(device, stack, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer pHandle = stack.mallocLong(1);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(4);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push);
            check(vkCreatePipelineLayout(device, layoutInfo, null, pHandle), "vkCreatePipelineLayout");
            pipelineLayout = pHandle.get(0);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(vkCreateComputePipelines(device, rt.pipelineCache.cache, pipelineInfo, null, pHandle),
                    "vkCreateComputePipelines");
            pipeline = pHandle.get(0);

            long set = rt.descriptors.allocate(setLayout);
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.get(0).buffer(buffer.handle).offset(0).range(VK_WHOLE_SIZE);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(device, write, null);

            long boundPipeline = pipeline;
            long boundLayout = pipelineLayout;
            rt.commands.submitAndWait(cmd -> {
                try (MemoryStack s = stackPush()) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, boundPipeline);
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, boundLayout, 0, s.longs(set), null);
                    vkCmdPushConstants(cmd, boundLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, s.ints(COMPUTE_COUNT));
                    vkCmdDispatch(cmd, (COMPUTE_COUNT + 63) / 64, 1, 1);
                    hostReadBarrier(cmd, s, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
                }
            });

            for (int i = 0; i < COMPUTE_COUNT; i++) {
                int expected = i * i + 7;
                int actual = MemoryUtil.memGetInt(buffer.mappedAddress + i * 4L);
                if (actual != expected) {
                    throw new VulkanException("Compute self-test: values[" + i + "] = " + actual
                            + ", expected " + expected);
                }
            }
        } finally {
            rt.descriptors.reset();
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (setLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (shader != VK_NULL_HANDLE) vkDestroyShaderModule(device, shader, null);
        }
    }

    // ------------------------------------------------------------------ graphics

    private static void triangleTest(VulkanRuntime rt) {
        try (OffscreenTarget target = new OffscreenTarget(rt)) {
            target.draw(TRIANGLE_VERTEX, RED_FRAGMENT, VK_NULL_HANDLE, VK_NULL_HANDLE);
            expectPixel(target, SIZE / 2, SIZE / 2, 0xFF0000FF, "triangle centre (red)");
            expectPixel(target, 1, 1, 0x0000FFFF, "triangle corner (blue clear colour)");
        }
    }

    private static void textureTest(VulkanRuntime rt) {
        VkDevice device = rt.context.device;
        long setLayout = VK_NULL_HANDLE;
        try (MemoryStack stack = stackPush();
             VulkanTexture texture = VulkanTexture.create(rt.context, rt.allocator, SIZE, SIZE,
                     VulkanTexture.fullMipChain(SIZE, SIZE), VK_FORMAT_R8G8B8A8_UNORM, 0);
             OffscreenTarget target = new OffscreenTarget(rt)) {
            // Four quadrants: red / green above, blue / white below.
            ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
            try {
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        int rgba = quadrantColour(x, y);
                        pixels.put((byte) (rgba >>> 24)).put((byte) (rgba >>> 16)).put((byte) (rgba >>> 8)).put((byte) rgba);
                    }
                }
                pixels.flip();
                rt.staging.uploadTexture(texture, pixels);
                rt.staging.flush();
            } finally {
                MemoryUtil.memFree(pixels);
            }
            if (texture.layout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                throw new VulkanException("Texture self-test: texture not ready for sampling after upload");
            }

            setLayout = createSetLayout(device, stack, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    VK_SHADER_STAGE_FRAGMENT_BIT);
            long set = rt.descriptors.allocate(setLayout);
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.get(0)
                    .sampler(rt.samplers.get(false, false, true))
                    .imageView(texture.view)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfo);
            vkUpdateDescriptorSets(device, write, null);

            target.draw(FULLSCREEN_VERTEX, TEXTURED_FRAGMENT, setLayout, set);
            int[][] probes = {{16, 16}, {48, 16}, {16, 48}, {48, 48}};
            for (int[] p : probes) {
                expectPixel(target, p[0], p[1], quadrantColour(p[0], p[1]), "sampled texture quadrant");
            }
        } finally {
            rt.descriptors.reset();
            if (setLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, setLayout, null);
        }
    }

    private static int quadrantColour(int x, int y) {
        boolean right = x >= SIZE / 2;
        boolean bottom = y >= SIZE / 2;
        if (!bottom) {
            return right ? 0x00FF00FF : 0xFF0000FF;
        }
        return right ? 0xFFFFFFFF : 0x0000FFFF;
    }

    private static void expectPixel(OffscreenTarget target, int x, int y, int expected, String what) {
        int actual = target.pixel(x, y);
        if (actual != expected) {
            throw new VulkanException("Graphics self-test: " + what + " at " + x + "," + y + " is 0x"
                    + Integer.toHexString(actual) + ", expected 0x" + Integer.toHexString(expected));
        }
    }

    /** 64x64 RGBA8 colour target with render pass, framebuffer and a host-visible read-back buffer. */
    private static final class OffscreenTarget implements AutoCloseable {
        private final VulkanRuntime rt;
        private final VkDevice device;
        private final VulkanMemoryAllocator.Allocation memory;
        private final long image;
        private final long view;
        private final long renderPass;
        private final long framebuffer;
        private final VulkanBuffer readback;

        OffscreenTarget(VulkanRuntime rt) {
            this.rt = rt;
            this.device = rt.context.device;
            int format = VK_FORMAT_R8G8B8A8_UNORM;
            try (MemoryStack stack = stackPush()) {
                LongBuffer pHandle = stack.mallocLong(1);
                VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                        .sType$Default()
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(format)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                imageInfo.extent().set(SIZE, SIZE, 1);
                check(vkCreateImage(this.device, imageInfo, null, pHandle), "vkCreateImage");
                this.image = pHandle.get(0);

                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetImageMemoryRequirements(this.device, this.image, requirements);
                this.memory = rt.allocator.allocate(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        VulkanMemoryAllocator.Kind.OPTIMAL);
                check(vkBindImageMemory(this.device, this.image, this.memory.memory, this.memory.offset),
                        "vkBindImageMemory");

                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default().image(this.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
                viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                check(vkCreateImageView(this.device, viewInfo, null, pHandle), "vkCreateImageView");
                this.view = pHandle.get(0);

                VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack);
                attachment.get(0).format(format).samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
                colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
                subpass.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                        .pColorAttachments(colorRef);
                VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
                dependency.get(0).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                        .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
                        .sType$Default().pAttachments(attachment).pSubpasses(subpass).pDependencies(dependency);
                check(vkCreateRenderPass(this.device, renderPassInfo, null, pHandle), "vkCreateRenderPass");
                this.renderPass = pHandle.get(0);

                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                        .sType$Default().renderPass(this.renderPass).pAttachments(stack.longs(this.view))
                        .width(SIZE).height(SIZE).layers(1);
                check(vkCreateFramebuffer(this.device, framebufferInfo, null, pHandle), "vkCreateFramebuffer");
                this.framebuffer = pHandle.get(0);
            }
            this.readback = VulkanBuffer.create(rt.context, rt.allocator, SIZE * SIZE * 4L,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }

        /** Clears to blue, draws 3 vertices with the given shaders, copies the result to the read-back buffer. */
        void draw(String vertexSource, String fragmentSource, long setLayout, long set) {
            long vertex = VK_NULL_HANDLE;
            long fragment = VK_NULL_HANDLE;
            long layout = VK_NULL_HANDLE;
            long pipeline = VK_NULL_HANDLE;
            try (MemoryStack stack = stackPush()) {
                vertex = this.rt.compiler.compileModule(this.rt.context, vertexSource, SpirvCompiler.Stage.VERTEX,
                        "selftest.vert");
                fragment = this.rt.compiler.compileModule(this.rt.context, fragmentSource, SpirvCompiler.Stage.FRAGMENT,
                        "selftest.frag");
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
                if (setLayout != VK_NULL_HANDLE) {
                    layoutInfo.pSetLayouts(stack.longs(setLayout));
                }
                LongBuffer pHandle = stack.mallocLong(1);
                check(vkCreatePipelineLayout(this.device, layoutInfo, null, pHandle), "vkCreatePipelineLayout");
                layout = pHandle.get(0);
                pipeline = createPipeline(stack, vertex, fragment, layout);

                long boundPipeline = pipeline;
                long boundLayout = layout;
                this.rt.commands.submitAndWait(cmd -> {
                    try (MemoryStack s = stackPush()) {
                        VkClearValue.Buffer clear = VkClearValue.calloc(1, s);
                        clear.get(0).color().float32(0, 0.0f).float32(1, 0.0f).float32(2, 1.0f).float32(3, 1.0f);
                        VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(s)
                                .sType$Default().renderPass(this.renderPass).framebuffer(this.framebuffer)
                                .pClearValues(clear);
                        begin.renderArea().extent().set(SIZE, SIZE);
                        vkCmdBeginRenderPass(cmd, begin, VK_SUBPASS_CONTENTS_INLINE);
                        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, boundPipeline);
                        if (set != VK_NULL_HANDLE) {
                            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, boundLayout, 0,
                                    s.longs(set), null);
                        }
                        vkCmdDraw(cmd, 3, 1, 0, 0);
                        vkCmdEndRenderPass(cmd);

                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, s);
                        region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        region.get(0).imageExtent().set(SIZE, SIZE, 1);
                        vkCmdCopyImageToBuffer(cmd, this.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                this.readback.handle, region);
                        hostReadBarrier(cmd, s, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                    }
                });
            } finally {
                if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(this.device, pipeline, null);
                if (layout != VK_NULL_HANDLE) vkDestroyPipelineLayout(this.device, layout, null);
                if (vertex != VK_NULL_HANDLE) vkDestroyShaderModule(this.device, vertex, null);
                if (fragment != VK_NULL_HANDLE) vkDestroyShaderModule(this.device, fragment, null);
            }
        }

        private long createPipeline(MemoryStack stack, long vertex, long fragment, long layout) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default();
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).width(SIZE).height(SIZE).minDepth(0.0f).maxDepth(1.0f);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent().set(SIZE, SIZE);
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default().viewportCount(1).pViewports(viewport).scissorCount(1).pScissors(scissor);
            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                    | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(blend);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages).pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly).pViewportState(viewportState)
                    .pRasterizationState(rasterization).pMultisampleState(multisample).pColorBlendState(colorBlend)
                    .layout(layout).renderPass(this.renderPass).subpass(0);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(this.device, this.rt.pipelineCache.cache, info, null, pPipeline),
                    "vkCreateGraphicsPipelines");
            return pPipeline.get(0);
        }

        /** RGBA8 pixel as 0xRRGGBBAA. */
        int pixel(int x, int y) {
            long address = this.readback.mappedAddress + ((long) y * SIZE + x) * 4L;
            int r = MemoryUtil.memGetByte(address) & 0xFF;
            int g = MemoryUtil.memGetByte(address + 1) & 0xFF;
            int b = MemoryUtil.memGetByte(address + 2) & 0xFF;
            int a = MemoryUtil.memGetByte(address + 3) & 0xFF;
            return (r << 24) | (g << 16) | (b << 8) | a;
        }

        @Override
        public void close() {
            this.readback.close();
            vkDestroyFramebuffer(this.device, this.framebuffer, null);
            vkDestroyRenderPass(this.device, this.renderPass, null);
            vkDestroyImageView(this.device, this.view, null);
            vkDestroyImage(this.device, this.image, null);
            this.rt.allocator.free(this.memory);
        }
    }

    private static long createSetLayout(VkDevice device, MemoryStack stack, int type, int stages) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
        bindings.get(0).binding(0).descriptorType(type).descriptorCount(1).stageFlags(stages);
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(bindings);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device, info, null, pLayout), "vkCreateDescriptorSetLayout");
        return pLayout.get(0);
    }

    /** Makes device writes visible to the host before the fence wait returns. */
    private static void hostReadBarrier(VkCommandBuffer cmd, MemoryStack stack, int srcStage, int srcAccess) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
        vkCmdPipelineBarrier(cmd, srcStage, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
    }
}
