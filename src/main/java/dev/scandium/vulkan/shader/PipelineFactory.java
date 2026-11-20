package dev.scandium.vulkan.shader;

import dev.scandium.vulkan.SpirvCompiler;
import dev.scandium.vulkan.VulkanRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Builds Vulkan pipelines from translated Minecraft shaders in two steps:
 * <ol>
 *     <li>{@link Program}: shader modules, descriptor set layout and pipeline layout - once per shader</li>
 *     <li>pipeline: a program plus vertex layout, fixed-function state and render pass - once per combination
 *     (the same shader is used with many blend/depth states)</li>
 * </ol>
 * Viewport and scissor are dynamic (Minecraft changes them freely), everything else is baked.
 */
public final class PipelineFactory {
    public record Program(long vertexModule, long fragmentModule, long setLayout, long pipelineLayout,
                          GlslTranslator.Result shader) {
    }

    /** Program plus one pipeline, as used by {@link ShaderPipelineSet}; destroy with {@link #destroy}. */
    public record Built(Program program, long pipeline) {
    }

    private PipelineFactory() {
    }

    public static Program createProgram(VulkanRuntime rt, String name, GlslTranslator.Result shader) {
        VkDevice device = rt.context.device;
        long vertex = VK_NULL_HANDLE;
        long fragment = VK_NULL_HANDLE;
        long setLayout = VK_NULL_HANDLE;
        try (MemoryStack stack = stackPush()) {
            vertex = rt.compiler.compileModule(rt.context, shader.vertexSource(), SpirvCompiler.Stage.VERTEX, name + ".vsh");
            fragment = rt.compiler.compileModule(rt.context, shader.fragmentSource(), SpirvCompiler.Stage.FRAGMENT, name + ".fsh");

            // set 0: uniform block (binding 0) + samplers (1..n), visible to both stages (the lightmap is sampled in
            // the vertex shader, fog values are read in both).
            int bindingCount = (shader.uniforms().isEmpty() ? 0 : 1) + shader.samplers().size();
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
            int b = 0;
            int stages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
            if (!shader.uniforms().isEmpty()) {
                bindings.get(b++).binding(GlslTranslator.Result.UNIFORM_BINDING)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1).stageFlags(stages);
            }
            for (GlslTranslator.SamplerBinding sampler : shader.samplers()) {
                bindings.get(b++).binding(sampler.binding())
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(stages);
            }
            VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            LongBuffer pHandle = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, setInfo, null, pHandle), "vkCreateDescriptorSetLayout");
            setLayout = pHandle.get(0);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(setLayout));
            check(vkCreatePipelineLayout(device, layoutInfo, null, pHandle), "vkCreatePipelineLayout");
            return new Program(vertex, fragment, setLayout, pHandle.get(0), shader);
        } catch (RuntimeException e) {
            if (setLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (vertex != VK_NULL_HANDLE) vkDestroyShaderModule(device, vertex, null);
            if (fragment != VK_NULL_HANDLE) vkDestroyShaderModule(device, fragment, null);
            throw e;
        }
    }

    public static long createPipeline(VulkanRuntime rt, Program program, VertexInputMapper.Layout input,
                                      PipelineState state, long renderPass) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            shaderStages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(program.vertexModule())
                    .pName(stack.UTF8("main"));
            shaderStages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(program.fragmentModule())
                    .pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer vertexBinding = VkVertexInputBindingDescription.calloc(1, stack);
            vertexBinding.get(0).binding(0).stride(input.stride()).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes =
                    VkVertexInputAttributeDescription.calloc(input.attributes().size(), stack);
            for (int i = 0; i < input.attributes().size(); i++) {
                VertexInputMapper.Attribute a = input.attributes().get(i);
                attributes.get(i).location(a.location()).binding(0).format(a.format()).offset(a.offset());
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default().pVertexBindingDescriptions(vertexBinding).pVertexAttributeDescriptions(attributes);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default().topology(state.topology());
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default().viewportCount(1).scissorCount(1);
            // Front face CCW like GL: with the negative-height viewport the image (and winding) matches GL.
            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(state.cull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(state.polygonOffset())
                    .depthBiasConstantFactor(state.offsetUnits())
                    .depthBiasSlopeFactor(state.offsetFactor())
                    .lineWidth(1.0f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(state.depthTest())
                    .depthWriteEnable(state.depthTest() && state.depthWrite()) // GL: no depth writes without the test
                    .depthCompareOp(state.depthCompare());
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0)
                    .blendEnable(state.blend())
                    .srcColorBlendFactor(state.srcColor())
                    .dstColorBlendFactor(state.dstColor())
                    .colorBlendOp(state.blendOp())
                    .srcAlphaBlendFactor(state.srcAlpha())
                    .dstAlphaBlendFactor(state.dstAlpha())
                    .alphaBlendOp(state.blendOp())
                    .colorWriteMask(state.colorWriteMask());
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(blend);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default()
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewport)
                    .pRasterizationState(rasterization)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depth)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamic)
                    .layout(program.pipelineLayout())
                    .renderPass(renderPass)
                    .subpass(0);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(rt.context.device, rt.pipelineCache.cache, info, null, pPipeline),
                    "vkCreateGraphicsPipelines");
            return pPipeline.get(0);
        }
    }

    public static Built create(VulkanRuntime rt, String name, GlslTranslator.Result shader, VertexInputMapper.Layout input,
                               PipelineState state, long renderPass) {
        Program program = createProgram(rt, name, shader);
        try {
            return new Built(program, createPipeline(rt, program, input, state, renderPass));
        } catch (RuntimeException e) {
            destroyProgram(rt.context.device, program);
            throw e;
        }
    }

    public static void destroyProgram(VkDevice device, Program program) {
        vkDestroyPipelineLayout(device, program.pipelineLayout(), null);
        vkDestroyDescriptorSetLayout(device, program.setLayout(), null);
        vkDestroyShaderModule(device, program.vertexModule(), null);
        vkDestroyShaderModule(device, program.fragmentModule(), null);
    }

    public static void destroy(VkDevice device, Built built) {
        vkDestroyPipeline(device, built.pipeline(), null);
        destroyProgram(device, built.program());
    }
}
