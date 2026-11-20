package dev.scandium.vulkan.shadow;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.scandium.vulkan.VulkanBuffer;

/**
 * Vulkan copy of one GL VertexBuffer's contents. Dynamic buffers (all immediate-mode drawing) live in the frame
 * ring and are only valid during the frame they were uploaded in; static buffers (sky, stars, clouds) own a
 * host-visible Vulkan buffer that is replaced - never overwritten - on re-upload, because an earlier draw of
 * the same frame may still read it.
 */
public final class VertexMirror {
    /** Chunk section buffers: terrain is stage 6, not mirrored here. */
    public boolean terrain;

    VertexFormat format;
    VertexFormat.Mode mode;
    int vertexCount;
    int indexCount;
    boolean sequentialIndex;
    VertexFormat.IndexType indexType;

    long vertexBuffer;
    long vertexOffset;
    long indexBuffer;
    long indexOffset;
    /** Frame number the ring data belongs to (dynamic buffers). */
    long frame = -1;

    VulkanBuffer ownedVertex;
    VulkanBuffer ownedIndex;

    boolean hasData() {
        return this.vertexBuffer != 0 && this.vertexCount > 0;
    }
}
