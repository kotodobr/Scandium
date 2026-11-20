package dev.scandium.vulkan;

import dev.scandium.vulkan.memory.VulkanMemoryAllocator;

import java.nio.file.Path;

/**
 * Everything the backend needs to create and fill GPU resources, created and destroyed together:
 * device, memory allocator, command submission, staging uploads, samplers, descriptor sets, shader compiler and
 * the on-disk pipeline cache.
 */
public final class VulkanRuntime implements AutoCloseable {
    /** 16 MB covers a full block atlas upload at 1024x1024 in one go. */
    private static final long STAGING_SIZE = 16L << 20;

    public final VulkanContext context;
    public final VulkanMemoryAllocator allocator;
    public final VulkanCommands commands;
    public final StagingBelt staging;
    public final SamplerCache samplers;
    public final DescriptorAllocator descriptors;
    public final SpirvCompiler compiler;
    public final PipelineCacheStore pipelineCache;

    private VulkanRuntime(VulkanContext context, Path pipelineCacheFile) {
        this.context = context;
        this.allocator = new VulkanMemoryAllocator(context);
        this.commands = new VulkanCommands(context);
        this.staging = new StagingBelt(context, this.allocator, this.commands, STAGING_SIZE);
        this.samplers = new SamplerCache(context);
        this.descriptors = new DescriptorAllocator(context);
        this.compiler = new SpirvCompiler();
        this.pipelineCache = new PipelineCacheStore(context, pipelineCacheFile);
    }

    public static VulkanRuntime create(boolean validation, Path pipelineCacheFile) {
        VulkanContext context = VulkanContext.create(validation);
        try {
            return new VulkanRuntime(context, pipelineCacheFile);
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    @Override
    public void close() {
        this.staging.close();
        this.pipelineCache.close();
        this.compiler.close();
        this.descriptors.close();
        this.samplers.close();
        this.commands.close();
        this.allocator.close();
        this.context.close();
    }
}
