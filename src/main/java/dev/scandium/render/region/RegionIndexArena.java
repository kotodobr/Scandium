package dev.scandium.render.region;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;

/**
 * Per-region INDEX buffer for the translucent layer, whose faces are sorted by camera distance, so every section
 * has its own index order. All indices are upcast to 32 bit: one multi-draw call takes exactly one index type,
 * and vanilla picks the width per section.
 * <p>
 * Uploads go through GL_COPY_WRITE_BUFFER: binding GL_ELEMENT_ARRAY_BUFFER would change whatever VAO happens to
 * be bound. The buffer is only bound as element array at draw time, inside the region's VAO. Ported from Vodium.
 */
public final class RegionIndexArena implements AutoCloseable {
    public static final int INDEX_BYTES = 4;

    private final SubAllocator allocator = new SubAllocator(INDEX_BYTES);
    private int bufferId;
    private int capacity;
    /** The buffer changed: the VAO still remembers the old one. */
    private boolean bindingDirty = true;

    public RegionIndexArena(int initialBytes) {
        RenderSystem.assertOnRenderThread();
        this.capacity = this.allocator.align(Math.max(initialBytes, INDEX_BYTES * 1024));
        this.bufferId = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.bufferId);
        GlStateManager._glBufferData(GL31.GL_COPY_WRITE_BUFFER, this.capacity, GlConst.GL_STATIC_DRAW);
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
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
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, this.bufferId);
        GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, offset, data);
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    /** Binds as element array buffer. Only with the region's VAO bound - the binding is stored there. */
    public void bindForDraw() {
        RenderSystem.assertOnRenderThread();
        if (this.bindingDirty) {
            GlStateManager._glBindBuffer(GlConst.GL_ELEMENT_ARRAY_BUFFER, this.bufferId);
            this.bindingDirty = false;
        }
    }

    private void grow(int minCapacity) {
        int newCapacity = this.capacity;
        while (newCapacity < minCapacity) {
            newCapacity <<= 1;
        }
        int newBuffer = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newBuffer);
        GlStateManager._glBufferData(GL31.GL_COPY_WRITE_BUFFER, newCapacity, GlConst.GL_STATIC_DRAW);
        GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
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
        this.bindingDirty = true;
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
        this.allocator.clear();
        this.capacity = 0;
        this.bindingDirty = true;
    }
}
