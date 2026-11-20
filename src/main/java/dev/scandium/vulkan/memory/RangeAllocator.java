package dev.scandium.vulkan.memory;

import java.util.Map;
import java.util.TreeMap;

/**
 * First-fit allocator of byte ranges with per-request alignment, used inside one VkDeviceMemory block.
 * Free ranges are kept in an ordered map and merged with their neighbours on free, so the list stays short.
 * Not thread-safe; the owning allocator synchronizes.
 */
public final class RangeAllocator {
    /** offset -> length of every free range. */
    private final TreeMap<Long, Long> free = new TreeMap<>();
    private long capacity;
    private long used;

    public RangeAllocator(long capacity) {
        this.capacity = capacity;
        this.free.put(0L, capacity);
    }

    /** @return the offset, or -1 if no free range fits */
    public long allocate(long size, long alignment) {
        for (Map.Entry<Long, Long> entry : this.free.entrySet()) {
            long start = entry.getKey();
            long length = entry.getValue();
            long aligned = alignUp(start, alignment);
            long end = start + length;
            if (aligned + size > end) {
                continue;
            }
            this.free.remove(start);
            if (aligned > start) {
                this.free.put(start, aligned - start); // padding before the block stays free
            }
            if (aligned + size < end) {
                this.free.put(aligned + size, end - (aligned + size));
            }
            this.used += size;
            return aligned;
        }
        return -1;
    }

    public void free(long offset, long size) {
        this.used -= size;
        long start = offset;
        long length = size;
        Map.Entry<Long, Long> before = this.free.floorEntry(offset);
        if (before != null && before.getKey() + before.getValue() == offset) {
            start = before.getKey();
            length += before.getValue();
            this.free.remove(before.getKey());
        }
        Map.Entry<Long, Long> after = this.free.ceilingEntry(offset + size);
        if (after != null && after.getKey() == offset + size) {
            length += after.getValue();
            this.free.remove(after.getKey());
        }
        this.free.put(start, length);
    }

    /** Makes [old capacity, newCapacity) available (the backing buffer was grown). */
    public void extend(long newCapacity) {
        if (newCapacity <= this.capacity) {
            return;
        }
        long start = this.capacity;
        long length = newCapacity - this.capacity;
        java.util.Map.Entry<Long, Long> before = this.free.floorEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) {
            this.free.remove(before.getKey());
            length += start - before.getKey();
            start = before.getKey();
        }
        this.free.put(start, length);
        this.capacity = newCapacity;
    }

    public boolean isEmpty() {
        return this.used == 0;
    }

    long used() {
        return this.used;
    }

    public long capacity() {
        return this.capacity;
    }

    static long alignUp(long value, long alignment) {
        if (alignment <= 1) {
            return value;
        }
        long remainder = value % alignment;
        return remainder == 0 ? value : value + (alignment - remainder);
    }
}
