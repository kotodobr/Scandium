package dev.scandium.render.region;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.scandium.config.ScandiumConfig;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;

/**
 * Shared vertex buffer for one layer of one region, sub-allocated per section.
 * <p>
 * Vanilla keeps one VBO and one VAO per section and therefore draws each one with its own call. If the sections
 * of a region lie in one buffer, they only differ by vertex offset and can be drawn with a single
 * glMultiDrawElementsBaseVertex. Indices aren't stored per section: for QUADS vanilla uses a shared sequential
 * index buffer and baseVertex is added after reading.
 * <p>
 * Render thread only. Ported from Vodium.
 */
public final class RegionArena implements AutoCloseable {
    private final SubAllocator allocator;
    private final VertexFormat format;
    private final int stride;

    private int bufferId;
    private int vaoId;
    private int capacity;
    private boolean vertexStateDirty = true;
    /**
     * The shared sequential index buffer is already bound in this VAO. The element-array binding is VAO state, and
     * vanilla keeps the same buffer name for the lifetime of the context (growing only re-fills it), so binding it
     * once is enough.
     */
    private boolean sequentialIndexBound;

    /** Target buffer while compacting. */
    private int repackBufferId;
    private int repackCapacity;

    public RegionArena(VertexFormat format, int initialBytes) {
        RenderSystem.assertOnRenderThread();
        this.format = format;
        this.stride = format.getVertexSize();
        this.allocator = new SubAllocator(this.stride);
        this.capacity = this.allocator.align(Math.max(initialBytes, this.stride * 1024));
        this.bufferId = GlStateManager._glGenBuffers();
        this.vaoId = GlStateManager._glGenVertexArrays();
        GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, this.bufferId);
        GlStateManager._glBufferData(GlConst.GL_ARRAY_BUFFER, this.capacity, GlConst.GL_STATIC_DRAW);
        GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, 0);
    }

    /**
     * The vertex format is checked by callers, not trusted: attribute pointers and stride are stored in the VAO;
     * vertices of another format would be read with the wrong stride (triangles across the map).
     */
    public VertexFormat getFormat() {
        return this.format;
    }

    public int getStride() {
        return this.stride;
    }

    public int allocate(int bytes) {
        RenderSystem.assertOnRenderThread();
        int offset = this.allocator.allocate(bytes);
        if (this.allocator.getHighWater() > this.capacity) {
            grow(this.allocator.getHighWater());
        }
        return offset;
    }

    public void free(int offset, int bytes) {
        RenderSystem.assertOnRenderThread();
        this.allocator.free(offset, bytes);
    }

    public void upload(int offset, ByteBuffer data) {
        RenderSystem.assertOnRenderThread();
        GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, this.bufferId);
        GL15.glBufferSubData(GlConst.GL_ARRAY_BUFFER, offset, data);
        GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, 0);
    }

    /** Grows by a GPU-side copy: all offsets handed out stay valid. */
    private void grow(int minCapacity) {
        int newCapacity = this.capacity;
        while (newCapacity < minCapacity) {
            newCapacity <<= 1;
        }
        int newBuffer = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, newBuffer);
        GlStateManager._glBufferData(GlConst.GL_ARRAY_BUFFER, newCapacity, GlConst.GL_STATIC_DRAW);
        GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, 0);

        // Copy only the old contents: the high-water mark already includes the new (not yet written) block.
        int copied = Math.min(this.capacity, this.allocator.getHighWater());
        if (copied > 0) {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.bufferId);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newBuffer);
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0L, 0L, copied);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        }
        GlStateManager._glDeleteBuffers(this.bufferId);
        this.bufferId = newBuffer;
        this.capacity = newCapacity;
        this.vertexStateDirty = true; // attribute pointers referenced the old buffer
    }

    /** Binds the region's VAO and (re)describes the vertex format if needed. */
    public void bindForDraw() {
        RenderSystem.assertOnRenderThread();
        GlStateManager._glBindVertexArray(this.vaoId);
        if (this.vertexStateDirty) {
            GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, this.bufferId);
            this.format.setupBufferState();
            this.vertexStateDirty = false;
        }
    }

    public boolean hasSequentialIndexBinding() {
        return this.sequentialIndexBound;
    }

    public void markSequentialIndexBinding() {
        this.sequentialIndexBound = true;
    }

    /** Worth compacting only if the waste is both large in absolute terms and relative to the buffer. */
    public boolean needsCompaction() {
        if (!ScandiumConfig.get().arenaCompaction) {
            return false;
        }
        int wasted = this.allocator.getWastedBytes();
        return wasted > 512 * 1024 && wasted > this.allocator.getHighWater() / 2;
    }

    /**
     * Prepares a NEW buffer for the compacted contents. Copying within one buffer is only allowed for
     * non-overlapping ranges, and moving blocks towards the start almost always overlaps (drivers crash).
     * The new buffer is sized to the real need, so compaction also returns VRAM.
     */
    public void beginRepack(int neededBytes) {
        RenderSystem.assertOnRenderThread();
        int newCapacity = this.allocator.align(Math.max(neededBytes, this.stride * 1024));
        this.repackBufferId = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, this.repackBufferId);
        GlStateManager._glBufferData(GlConst.GL_ARRAY_BUFFER, newCapacity, GlConst.GL_STATIC_DRAW);
        GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, 0);
        this.repackCapacity = newCapacity;
    }

    public void repackBlock(int fromOffset, int toOffset, int bytes) {
        if (this.repackBufferId == 0 || bytes <= 0) {
            return;
        }
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.bufferId);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.repackBufferId);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, fromOffset, toOffset, bytes);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    public void endRepack(int newHighWater) {
        RenderSystem.assertOnRenderThread();
        if (this.repackBufferId == 0) {
            return;
        }
        GlStateManager._glDeleteBuffers(this.bufferId);
        this.bufferId = this.repackBufferId;
        this.capacity = this.repackCapacity;
        this.repackBufferId = 0;
        this.repackCapacity = 0;
        this.allocator.reset(newHighWater);
        this.vertexStateDirty = true;
    }

    public int getCapacity() {
        return this.capacity;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (this.bufferId != 0) {
            GlStateManager._glDeleteBuffers(this.bufferId);
            this.bufferId = 0;
        }
        if (this.vaoId != 0) {
            GlStateManager._glDeleteVertexArrays(this.vaoId);
            this.vaoId = 0;
        }
        if (this.repackBufferId != 0) {
            GlStateManager._glDeleteBuffers(this.repackBufferId);
            this.repackBufferId = 0;
        }
        this.allocator.clear();
        this.capacity = 0;
    }
}
