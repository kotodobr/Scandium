package dev.scandium.vulkan;

import dev.scandium.Scandium;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A VkPipelineCache persisted between game starts. Pipeline creation compiles shaders to GPU code, which can take
 * tens of milliseconds per pipeline; with the cache the second start skips that work. The data starts with a
 * header (vendor, device, driver UUID) that the driver checks itself and ignores when it doesn't match, so a GPU
 * or driver change simply starts with an empty cache.
 */
public final class PipelineCacheStore implements AutoCloseable {
    private final VulkanContext context;
    private final Path file;
    public final long cache;
    public final long loadedBytes;

    public PipelineCacheStore(VulkanContext context, Path file) {
        this.context = context;
        this.file = file;
        ByteBuffer initial = null;
        long loaded = 0;
        try {
            if (Files.isRegularFile(file)) {
                byte[] bytes = Files.readAllBytes(file);
                initial = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
                loaded = bytes.length;
            }
        } catch (IOException e) {
            Scandium.LOGGER.warn("Could not read Vulkan pipeline cache {}: {}", file, e.toString());
        }
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (initial != null) {
                info.pInitialData(initial);
            }
            LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(context.device, info, null, pCache);
            if (result != VK_SUCCESS && initial != null) {
                // Corrupt file: start empty rather than fail.
                Scandium.LOGGER.warn("Vulkan pipeline cache rejected ({}), starting empty", result);
                info.pInitialData(null);
                loaded = 0;
                result = vkCreatePipelineCache(context.device, info, null, pCache);
            }
            check(result, "vkCreatePipelineCache");
            this.cache = pCache.get(0);
        } finally {
            if (initial != null) {
                MemoryUtil.memFree(initial);
            }
        }
        this.loadedBytes = loaded;
    }

    /** Writes the cache to disk atomically (temp file + move), so a crash never leaves a half-written file. */
    public void save() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pSize = stack.mallocPointer(1);
            check(vkGetPipelineCacheData(this.context.device, this.cache, pSize, null), "vkGetPipelineCacheData");
            int size = (int) pSize.get(0);
            if (size == 0) {
                return;
            }
            ByteBuffer data = MemoryUtil.memAlloc(size);
            try {
                check(vkGetPipelineCacheData(this.context.device, this.cache, pSize, data), "vkGetPipelineCacheData");
                byte[] bytes = new byte[(int) pSize.get(0)];
                data.get(bytes);
                Files.createDirectories(this.file.getParent());
                Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
                Files.write(temp, bytes);
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                MemoryUtil.memFree(data);
            }
        } catch (IOException e) {
            Scandium.LOGGER.warn("Could not save Vulkan pipeline cache {}: {}", this.file, e.toString());
        }
    }

    @Override
    public void close() {
        save();
        vkDestroyPipelineCache(this.context.device, this.cache, null);
    }
}
