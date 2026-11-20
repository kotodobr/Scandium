package dev.scandium.mixin.leak.texture;

import com.mojang.blaze3d.platform.TextureUtil;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * Off-heap buffer leak while reading resources (MC-226729).
 * <p>
 * Vanilla only frees the native buffer when an {@link IOException} is thrown. Any other failure - a mod's input
 * stream throwing a RuntimeException, an OutOfMemoryError, or {@code memRealloc} returning {@code null} when the
 * native allocation fails (which also loses the original buffer) - leaks the whole buffer. With large resource
 * packs these are multi-megabyte native allocations the GC can never reclaim.
 */
@Mixin(value = TextureUtil.class, remap = false)
public abstract class TextureUtilReadMixin {
    /**
     * @author Scandium
     * @reason free the native buffer on every failure path, not just IOException
     */
    @Overwrite
    private static ByteBuffer readResource(ReadableByteChannel channel, int initialSize) throws IOException {
        ByteBuffer buffer = MemoryUtil.memAlloc(initialSize);
        boolean success = false;
        try {
            while (channel.read(buffer) != -1) {
                if (!buffer.hasRemaining()) {
                    ByteBuffer grown = MemoryUtil.memRealloc(buffer, buffer.capacity() * 2);
                    if (grown == null) {
                        throw new OutOfMemoryError("Could not grow native resource buffer to "
                                + (buffer.capacity() * 2L) + " bytes");
                    }
                    buffer = grown;
                }
            }
            success = true;
            return buffer;
        } finally {
            if (!success) {
                MemoryUtil.memFree(buffer);
            }
        }
    }
}
