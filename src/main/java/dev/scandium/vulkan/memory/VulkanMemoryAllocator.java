package dev.scandium.vulkan.memory;

import dev.scandium.vulkan.VulkanContext;
import dev.scandium.vulkan.VulkanException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Sub-allocates GPU memory out of large blocks.
 * <p>
 * Drivers limit the number of VkDeviceMemory allocations (often 4096) and each one is slow, while Minecraft
 * needs thousands of buffers and textures. So memory is taken in 64 MB blocks per memory type and handed out in
 * aligned ranges. Buffers and optimal-tiling images live in separate blocks, which sidesteps
 * {@code bufferImageGranularity} entirely. Requests of half a block or more get a dedicated allocation.
 * Host-visible blocks are mapped once for their whole lifetime. Thread-safe.
 */
public final class VulkanMemoryAllocator implements AutoCloseable {
    public static final long BLOCK_SIZE = 64L << 20;

    public enum Kind {
        /** Buffers and linear-tiling images. */
        LINEAR,
        /** Optimal-tiling images. */
        OPTIMAL
    }

    /** A sub-range of a block (or a whole dedicated allocation). */
    public static final class Allocation {
        private final Block block;
        public final long memory;
        public final long offset;
        public final long size;
        /** Host address of this range, 0 if not host-visible. */
        public final long mappedAddress;
        private boolean freed;

        private Allocation(Block block, long offset, long size) {
            this.block = block;
            this.memory = block.memory;
            this.offset = offset;
            this.size = size;
            this.mappedAddress = block.mapped == 0 ? 0 : block.mapped + offset;
        }
    }

    private static final class Block {
        final long memory;
        final long mapped;
        final RangeAllocator ranges;
        final boolean dedicated;
        final int typeIndex;
        final Kind kind;

        Block(long memory, long mapped, long size, boolean dedicated, int typeIndex, Kind kind) {
            this.memory = memory;
            this.mapped = mapped;
            this.ranges = new RangeAllocator(size);
            this.dedicated = dedicated;
            this.typeIndex = typeIndex;
            this.kind = kind;
        }
    }

    private final VulkanContext context;
    private final Map<Long, List<Block>> pools = new HashMap<>();
    private long allocatedBytes;
    private long usedBytes;

    public VulkanMemoryAllocator(VulkanContext context) {
        this.context = context;
    }

    public synchronized Allocation allocate(VkMemoryRequirements requirements, int requiredFlags, Kind kind) {
        int typeIndex = this.context.findMemoryType(requirements.memoryTypeBits(), requiredFlags);
        boolean hostVisible = (this.context.memoryTypeFlags(typeIndex) & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
        long size = requirements.size();
        long alignment = requirements.alignment();

        if (size >= BLOCK_SIZE / 2) {
            Block block = createBlock(typeIndex, size, hostVisible, true, kind);
            long offset = block.ranges.allocate(size, 1);
            this.usedBytes += size;
            return new Allocation(block, offset, size);
        }

        List<Block> pool = this.pools.computeIfAbsent(poolKey(typeIndex, kind), k -> new ArrayList<>());
        for (Block block : pool) {
            long offset = block.ranges.allocate(size, alignment);
            if (offset >= 0) {
                this.usedBytes += size;
                return new Allocation(block, offset, size);
            }
        }
        Block block = createBlock(typeIndex, BLOCK_SIZE, hostVisible, false, kind);
        pool.add(block);
        long offset = block.ranges.allocate(size, alignment);
        if (offset < 0) {
            throw new VulkanException("Fresh memory block can't hold " + size + " bytes");
        }
        this.usedBytes += size;
        return new Allocation(block, offset, size);
    }

    public synchronized void free(Allocation allocation) {
        if (allocation.freed) {
            return;
        }
        allocation.freed = true;
        Block block = allocation.block;
        block.ranges.free(allocation.offset, allocation.size);
        this.usedBytes -= allocation.size;
        if (block.dedicated) {
            destroyBlock(block);
            return;
        }
        // Keep one empty block per pool around to avoid allocate/free churn; release the others.
        List<Block> pool = this.pools.get(poolKey(block.typeIndex, block.kind));
        if (block.ranges.isEmpty() && pool != null && pool.size() > 1) {
            pool.remove(block);
            destroyBlock(block);
        }
    }

    private Block createBlock(int typeIndex, long size, boolean hostVisible, boolean dedicated, Kind kind) {
        try (MemoryStack stack = stackPush()) {
            VkMemoryAllocateInfo info = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(size)
                    .memoryTypeIndex(typeIndex);
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(this.context.device, info, null, pMemory), "vkAllocateMemory");
            long memory = pMemory.get(0);
            long mapped = 0;
            if (hostVisible) {
                PointerBuffer pData = stack.mallocPointer(1);
                check(vkMapMemory(this.context.device, memory, 0, VK_WHOLE_SIZE, 0, pData), "vkMapMemory");
                mapped = pData.get(0);
            }
            this.allocatedBytes += size;
            return new Block(memory, mapped, size, dedicated, typeIndex, kind);
        }
    }

    private void destroyBlock(Block block) {
        if (block.mapped != 0) {
            vkUnmapMemory(this.context.device, block.memory);
        }
        vkFreeMemory(this.context.device, block.memory, null);
        this.allocatedBytes -= block.ranges.capacity();
    }

    private static long poolKey(int typeIndex, Kind kind) {
        return ((long) typeIndex << 1) | kind.ordinal();
    }

    public synchronized String stats() {
        int blocks = 0;
        for (List<Block> pool : this.pools.values()) {
            blocks += pool.size();
        }
        return String.format("%d MB used of %d MB in %d blocks", this.usedBytes >> 20, this.allocatedBytes >> 20, blocks);
    }

    public synchronized long usedBytes() {
        return this.usedBytes;
    }

    @Override
    public synchronized void close() {
        for (List<Block> pool : this.pools.values()) {
            for (Block block : pool) {
                destroyBlock(block);
            }
        }
        this.pools.clear();
    }
}
