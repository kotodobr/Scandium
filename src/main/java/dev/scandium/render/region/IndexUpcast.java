package dev.scandium.render.region;

import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Converts a section's indices to 32 bit (see {@link RegionIndexArena}). Render thread only, so one scratch
 * buffer is reused. Ported from Vodium.
 */
final class IndexUpcast {
    private static ByteBuffer scratch;

    private IndexUpcast() {
    }

    /** @return 32-bit indices, valid until the next call */
    static ByteBuffer to32Bit(ByteBuffer source, VertexFormat.IndexType type, int indexCount) {
        if (type == VertexFormat.IndexType.INT) {
            return source;
        }
        int neededBytes = indexCount * RegionIndexArena.INDEX_BYTES;
        if (scratch == null || scratch.capacity() < neededBytes) {
            if (scratch != null) {
                MemoryUtil.memFree(scratch);
            }
            scratch = MemoryUtil.memAlloc(Math.max(neededBytes, 64 * 1024)).order(ByteOrder.nativeOrder());
        }
        // Required: the limit was shrunk for the previous section, and absolute puts are checked against it.
        scratch.clear();
        // duplicate() resets the byte order to BIG_ENDIAN, the data is native.
        ByteBuffer in = source.duplicate().order(ByteOrder.nativeOrder());
        for (int i = 0; i < indexCount; i++) {
            // Indices are unsigned: without the mask sections with >32767 vertices would go negative.
            scratch.putInt(i * RegionIndexArena.INDEX_BYTES, in.getShort(i * 2) & 0xFFFF);
        }
        scratch.limit(neededBytes).position(0);
        return scratch;
    }
}
