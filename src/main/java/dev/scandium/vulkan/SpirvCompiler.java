package dev.scandium.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;

/**
 * GLSL to SPIR-V at runtime with shaderc. Needed because Minecraft's shaders are GLSL text that resource packs
 * can replace, so they can't all be precompiled at build time.
 */
public final class SpirvCompiler implements AutoCloseable {
    public enum Stage {
        VERTEX(shaderc_vertex_shader),
        FRAGMENT(shaderc_fragment_shader),
        COMPUTE(shaderc_compute_shader);

        final int kind;

        Stage(int kind) {
            this.kind = kind;
        }
    }

    private final long compiler;
    private final long options;

    public SpirvCompiler() {
        this.compiler = shaderc_compiler_initialize();
        if (this.compiler == 0) {
            throw new VulkanException("shaderc_compiler_initialize failed");
        }
        this.options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(this.options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_optimization_level(this.options, shaderc_optimization_level_performance);
    }

    /** Compiles and wraps the result into a VkShaderModule (caller destroys it). */
    public long compileModule(VulkanContext context, String source, Stage stage, String name) {
        long result = shaderc_compile_into_spv(this.compiler, source, stage.kind, name, "main", this.options);
        try {
            if (result == 0) {
                throw new VulkanException("shaderc returned no result for " + name);
            }
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new VulkanException("Shader " + name + " failed to compile: "
                        + shaderc_result_get_error_message(result));
            }
            ByteBuffer spirv = shaderc_result_get_bytes(result);
            try (MemoryStack stack = stackPush()) {
                VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(spirv);
                LongBuffer pModule = stack.mallocLong(1);
                check(vkCreateShaderModule(context.device, info, null, pModule), "vkCreateShaderModule");
                return pModule.get(0);
            }
        } finally {
            if (result != 0) {
                shaderc_result_release(result);
            }
        }
    }

    @Override
    public void close() {
        shaderc_compile_options_release(this.options);
        shaderc_compiler_release(this.compiler);
    }
}
