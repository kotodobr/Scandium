package dev.scandium.vulkan.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.scandium.Scandium;
import dev.scandium.vulkan.VulkanContext;
import dev.scandium.vulkan.VulkanRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * All of the game's core shaders as Vulkan pipelines, rebuilt after every resource reload (resource packs can
 * replace shaders). Each entry keeps its translation result (uniform layout, sampler bindings) for the render
 * layer of stage 4.
 * <p>
 * Until the Vulkan swapchain exists the pipelines are built against an offscreen render pass with the formats
 * the backend will use (RGBA8 colour, 32-bit depth); building them proves translation, SPIR-V compilation,
 * vertex layouts and state mapping on the real shaders, and fills the pipeline cache.
 */
public final class ShaderPipelineSet implements AutoCloseable {
    /** One unit of work, captured on the render thread (plain data, safe to pass to the Vulkan thread). */
    public record Job(String name, ShaderSourceLoader.CoreShader sources, VertexFormat format, List<String> attributeNames,
                      PipelineState state) {
    }

    public record Entry(String name, GlslTranslator.Result shader, VertexInputMapper.Layout input, PipelineState state,
                        PipelineFactory.Built built) {
    }

    public record Report(int total, int built, List<String> failures, List<String> warnings, long millis) {
        public String summary() {
            return built + "/" + total + " pipelines" + (failures.isEmpty() ? "" : ", " + failures.size() + " failed")
                    + " (" + millis + " ms)";
        }
    }

    private final VulkanRuntime rt;
    private final long renderPass;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private ShaderPipelineSet(VulkanRuntime rt, long renderPass) {
        this.rt = rt;
        this.renderPass = renderPass;
    }

    public static ShaderPipelineSet build(VulkanRuntime rt, List<Job> jobs, List<Report> reportOut) {
        long start = System.nanoTime();
        ShaderPipelineSet set = new ShaderPipelineSet(rt, createRenderPass(rt.context));
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Job job : jobs) {
            try {
                GlslTranslator.Result shader = GlslTranslator.translate(job.name, job.sources.vertexSource(),
                        job.sources.fragmentSource(), job.attributeNames, job.sources.samplers());
                warnings.addAll(shader.warnings());
                warnings.addAll(job.state.unknown().stream().map(u -> job.name + ": unknown state shard " + u).toList());
                VertexInputMapper.Layout input = VertexInputMapper.map(rt.context, job.format, shader.vertexInputs());
                PipelineFactory.Built built = PipelineFactory.create(rt, job.name, shader, input, job.state, set.renderPass);
                Entry previous = set.entries.put(job.name, new Entry(job.name, shader, input, job.state, built));
                if (previous != null) {
                    PipelineFactory.destroy(rt.context.device, previous.built);
                }
            } catch (RuntimeException e) {
                failures.add(job.name + ": " + e.getMessage());
            }
        }
        rt.pipelineCache.save();
        reportOut.add(new Report(jobs.size(), set.entries.size(), failures, warnings,
                (System.nanoTime() - start) / 1_000_000));
        return set;
    }

    public Entry get(String name) {
        return this.entries.get(name);
    }

    public int size() {
        return this.entries.size();
    }

    private static long createRenderPass(VulkanContext context) {
        int depthFormat = pickDepthFormat(context);
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
            attachments.get(0).format(VK_FORMAT_R8G8B8A8_UNORM).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            attachments.get(1).format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
            colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                    .pColorAttachments(colorRef).pDepthStencilAttachment(depthRef);
            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(attachments).pSubpasses(subpass);
            LongBuffer pPass = stack.mallocLong(1);
            check(vkCreateRenderPass(context.device, info, null, pPass), "vkCreateRenderPass");
            return pPass.get(0);
        }
    }

    private static int pickDepthFormat(VulkanContext context) {
        int[] candidates = {VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT};
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            for (int format : candidates) {
                vkGetPhysicalDeviceFormatProperties(context.physicalDevice, format, props);
                if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    return format;
                }
            }
        }
        Scandium.LOGGER.warn("No preferred depth format supported, using D16");
        return VK_FORMAT_D16_UNORM;
    }

    @Override
    public void close() {
        for (Entry entry : this.entries.values()) {
            PipelineFactory.destroy(this.rt.context.device, entry.built);
        }
        this.entries.clear();
        vkDestroyRenderPass(this.rt.context.device, this.renderPass, null);
    }
}
