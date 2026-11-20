package dev.scandium.render.region;

import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Sub-allocator inside one buffer: hands out offsets and merges freed holes. No GL here, only arithmetic, shared
 * by the vertex and the index arena. Ported from Vodium.
 */
final class SubAllocator {
    /** Free block: upper 32 bits offset, lower 32 bits length. Kept sorted by offset. */
    private final LongArrayList freeBlocks = new LongArrayList();
    private final int alignment;
    /** Everything above was never handed out. */
    private int highWater;
    /** Bytes really in use, to estimate fragmentation. */
    private int allocated;

    SubAllocator(int alignment) {
        this.alignment = alignment;
    }

    int align(int bytes) {
        int remainder = bytes % this.alignment;
        return remainder == 0 ? bytes : bytes + (this.alignment - remainder);
    }

    int getHighWater() {
        return this.highWater;
    }

    /** Bytes lost to holes between used blocks. */
    int getWastedBytes() {
        return this.highWater - this.allocated;
    }

    boolean isEmpty() {
        return this.allocated == 0;
    }

    /** @return byte offset of the block (size is aligned up) */
    int allocate(int bytes) {
        int size = align(bytes);
        // Reuse holes first, otherwise the buffer only ever grows.
        for (int i = 0; i < this.freeBlocks.size(); i++) {
            long block = this.freeBlocks.getLong(i);
            int blockOffset = offsetOf(block);
            int blockLength = lengthOf(block);
            if (blockLength < size) {
                continue;
            }
            if (blockLength == size) {
                this.freeBlocks.removeLong(i);
            } else {
                this.freeBlocks.set(i, pack(blockOffset + size, blockLength - size));
            }
            this.allocated += size;
            return blockOffset;
        }
        int offset = this.highWater;
        this.highWater += size;
        this.allocated += size;
        return offset;
    }

    void free(int offset, int bytes) {
        int size = align(bytes);
        this.allocated -= size;
        int index = 0;
        while (index < this.freeBlocks.size() && offsetOf(this.freeBlocks.getLong(index)) < offset) {
            index++;
        }
        this.freeBlocks.add(index, pack(offset, size));
        mergeAt(index);
        if (index > 0) {
            mergeAt(index - 1);
        }
        trimTail();
    }

    /** After a repack: exactly newHighWater bytes in use, no holes. */
    void reset(int newHighWater) {
        this.freeBlocks.clear();
        this.highWater = newHighWater;
        this.allocated = newHighWater;
    }

    void clear() {
        this.freeBlocks.clear();
        this.highWater = 0;
        this.allocated = 0;
    }

    private void mergeAt(int index) {
        if (index < 0 || index + 1 >= this.freeBlocks.size()) {
            return;
        }
        long current = this.freeBlocks.getLong(index);
        long next = this.freeBlocks.getLong(index + 1);
        if (offsetOf(current) + lengthOf(current) == offsetOf(next)) {
            this.freeBlocks.set(index, pack(offsetOf(current), lengthOf(current) + lengthOf(next)));
            this.freeBlocks.removeLong(index + 1);
        }
    }

    /** A freed tail goes back to the bump pointer instead of staying in the list. */
    private void trimTail() {
        if (this.freeBlocks.isEmpty()) {
            return;
        }
        int last = this.freeBlocks.size() - 1;
        long block = this.freeBlocks.getLong(last);
        if (offsetOf(block) + lengthOf(block) == this.highWater) {
            this.highWater = offsetOf(block);
            this.freeBlocks.removeLong(last);
        }
    }

    private static long pack(int offset, int length) {
        return ((long) offset << 32) | (length & 0xFFFFFFFFL);
    }

    private static int offsetOf(long block) {
        return (int) (block >>> 32);
    }

    private static int lengthOf(long block) {
        return (int) block;
    }
}
