package dev.scandium.render.region;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.scandium.Scandium;
import dev.scandium.render.VisibleSections;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * A region of 4x4x4 sections (64x64x64 blocks): one shared buffer per layer plus the bookkeeping of where each
 * section lives inside it. Section vertices are stored relative to the region's corner, so the whole region is
 * drawn with one ChunkOffset and one glMultiDrawElementsBaseVertex. Ported from Vodium.
 */
public final class TerrainRegion {
    /** One section's slots: one per layer, offset -1 when the layer has no geometry. */
    static final class SectionSlots {
        final int[] byteOffset = new int[RegionManager.LAYER_COUNT];
        final int[] byteLength = new int[RegionManager.LAYER_COUNT];
        final int[] indexCount = new int[RegionManager.LAYER_COUNT];
        /** Only the translucent layer has its own indices. */
        int translucentIndexOffset = -1;
        int translucentIndexLength;

        SectionSlots() {
            for (int i = 0; i < RegionManager.LAYER_COUNT; i++) {
                this.byteOffset[i] = -1;
            }
        }

        boolean isEmpty() {
            for (int offset : this.byteOffset) {
                if (offset >= 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private final RegionArena[] arenas = new RegionArena[RegionManager.LAYER_COUNT];
    private final Long2ObjectMap<SectionSlots> sections = new Long2ObjectOpenHashMap<>();
    @Nullable
    private RegionIndexArena translucentIndices;

    /** This frame's draw batch: index count and base vertex per layer. */
    private final IntArrayList[] batchCounts = new IntArrayList[RegionManager.LAYER_COUNT];
    private final IntArrayList[] batchBaseVertex = new IntArrayList[RegionManager.LAYER_COUNT];
    /** Offsets into the index arena, translucent layer only. */
    private final IntArrayList batchIndexOffset = new IntArrayList(64);

    private final int originX;
    private final int originY;
    private final int originZ;

    /** Frame number in which the region last joined the batch. */
    int lastFrame = -1;
    /**
     * Ranges released since this frame's batches were built. They are NOT returned to the allocator yet:
     * the frame order is setupRender (batches) -> compileSections (uploads) -> draw, so a section rebuilt during
     * compileSections is still drawn from its old offsets in the same frame. Freeing those ranges immediately let
     * another section's upload land there, and the frame drew foreign geometry or nothing at the rebuilt section
     * - the flicker when building or flying. Released ranges keep their old contents until the batches are
     * rebuilt ({@link #flushPendingFrees}), so for one frame the old geometry is shown, like vanilla does.
     * Entries: {layer (or -1 for translucent indices), offset, length}.
     */
    private final it.unimi.dsi.fastutil.ints.IntArrayList pendingFrees = new it.unimi.dsi.fastutil.ints.IntArrayList();

    private void deferFree(int layer, int offset, int length) {
        if (this.pendingFrees.isEmpty()) {
            RegionManager.notePendingFrees(this);
        }
        this.pendingFrees.add(layer);
        this.pendingFrees.add(offset);
        this.pendingFrees.add(length);
    }

    /** Called once no batch references the released ranges anymore (batch rebuild, before compaction). */
    void flushPendingFrees() {
        for (int i = 0; i < this.pendingFrees.size(); i += 3) {
            int layer = this.pendingFrees.getInt(i);
            int offset = this.pendingFrees.getInt(i + 1);
            int length = this.pendingFrees.getInt(i + 2);
            if (layer < 0) {
                if (this.translucentIndices != null) {
                    this.translucentIndices.free(offset, length);
                }
            } else if (this.arenas[layer] != null) {
                this.arenas[layer].free(offset, length);
            }
        }
        this.pendingFrees.clear();
    }

    /** Compaction is needed but must not happen on the upload path. */
    private boolean compactionPending;

    TerrainRegion(int regionX, int regionY, int regionZ) {
        this.originX = regionX << RegionManager.REGION_BLOCK_SHIFT;
        this.originY = regionY << RegionManager.REGION_BLOCK_SHIFT;
        this.originZ = regionZ << RegionManager.REGION_BLOCK_SHIFT;
        for (int i = 0; i < RegionManager.LAYER_COUNT; i++) {
            this.batchCounts[i] = new IntArrayList(64);
            this.batchBaseVertex[i] = new IntArrayList(64);
        }
    }

    public int getOriginX() {
        return this.originX;
    }

    public int getOriginY() {
        return this.originY;
    }

    public int getOriginZ() {
        return this.originZ;
    }

    @Nullable
    public RegionArena getArena(int layerIndex) {
        return this.arenas[layerIndex];
    }

    @Nullable
    public RegionIndexArena getTranslucentIndices() {
        return this.translucentIndices;
    }

    /**
     * Stores a translucent section's sorted indices: on a full rebuild and on every re-sort. A re-sort keeps the
     * index count, so the block is reused in place and the allocator isn't touched (re-sorts are frequent).
     *
     * @return true if the section had no indices before (it now becomes drawable)
     */
    boolean storeTranslucentIndices(long sectionKey, ByteBuffer indices, int indexCount) {
        SectionSlots slots = this.sections.get(sectionKey);
        if (slots == null) {
            return false; // re-sort arrived before the vertices; the vertex upload brings its own indices
        }
        boolean wasMissing = slots.translucentIndexOffset < 0;
        if (this.translucentIndices == null) {
            this.translucentIndices = new RegionIndexArena(64 * 1024);
        }
        int needed = indexCount * RegionIndexArena.INDEX_BYTES;
        if (slots.translucentIndexOffset < 0 || slots.translucentIndexLength != needed) {
            if (slots.translucentIndexOffset >= 0) {
                deferFree(-1, slots.translucentIndexOffset, slots.translucentIndexLength);
            }
            slots.translucentIndexOffset = this.translucentIndices.allocate(needed);
            slots.translucentIndexLength = needed;
        }
        this.translucentIndices.upload(slots.translucentIndexOffset, indices);
        slots.indexCount[RegionManager.TRANSLUCENT_LAYER_INDEX] = indexCount;
        return wasMissing;
    }

    private void freeTranslucentIndices(SectionSlots slots) {
        if (slots.translucentIndexOffset >= 0 && this.translucentIndices != null) {
            deferFree(-1, slots.translucentIndexOffset, slots.translucentIndexLength);
        }
        slots.translucentIndexOffset = -1;
        slots.translucentIndexLength = 0;
    }

    /** The layer's arena for the given format; a format change re-creates it rather than corrupting it. */
    private RegionArena arenaFor(int layerIndex, VertexFormat format) {
        RegionArena arena = this.arenas[layerIndex];
        if (arena != null && !arena.getFormat().equals(format)) {
            Scandium.LOGGER.warn("Vertex format of layer {} changed ({} -> {}): re-creating the region arena",
                    layerIndex, arena.getFormat(), format);
            releaseLayer(layerIndex);
            arena = null;
        }
        if (arena == null) {
            arena = new RegionArena(format, 64 * 1024);
            this.arenas[layerIndex] = arena;
        }
        return arena;
    }

    private void releaseLayer(int layerIndex) {
        RegionArena arena = this.arenas[layerIndex];
        if (arena != null) {
            arena.close();
            this.arenas[layerIndex] = null;
        }
        for (SectionSlots slots : this.sections.values()) {
            slots.byteOffset[layerIndex] = -1;
            slots.byteLength[layerIndex] = 0;
            slots.indexCount[layerIndex] = 0;
        }
        this.batchCounts[layerIndex].clear();
        this.batchBaseVertex[layerIndex].clear();
    }

    /** Stores a section's vertices (already rebased to the region corner). */
    void store(long sectionKey, int layerIndex, VertexFormat format, ByteBuffer vertices, int byteLength, int indexCount) {
        SectionSlots slots = this.sections.get(sectionKey);
        if (slots == null) {
            slots = new SectionSlots();
            this.sections.put(sectionKey, slots);
        }
        RegionArena arena = arenaFor(layerIndex, format);
        // A rebuilt section almost certainly changed size: free the old block, allocate a new one.
        if (slots.byteOffset[layerIndex] >= 0) {
            deferFree(layerIndex, slots.byteOffset[layerIndex], slots.byteLength[layerIndex]);
        }
        int offset = arena.allocate(byteLength);
        arena.upload(offset, vertices);
        slots.byteOffset[layerIndex] = offset;
        slots.byteLength[layerIndex] = byteLength;
        slots.indexCount[layerIndex] = indexCount;

        // Never compact here: a chunk upload would turn into a full repack, a stutter exactly while walking.
        if (arena.needsCompaction() && !this.compactionPending) {
            this.compactionPending = true;
            RegionManager.compactionRequested();
        }
    }

    boolean isCompactionPending() {
        return this.compactionPending;
    }

    /** Compacts the layers that need it. Called outside the upload path, one region per frame. */
    void compactNow() {
        flushPendingFrees();
        if (this.compactionPending) {
            this.compactionPending = false;
            RegionManager.compactionDone();
        }
        for (int layer = 0; layer < RegionManager.LAYER_COUNT; layer++) {
            RegionArena arena = this.arenas[layer];
            if (arena != null && arena.needsCompaction()) {
                compact(layer, arena);
            }
        }
    }

    /** Moves used blocks to the start of a new buffer (GPU-side copies). */
    private void compact(int layerIndex, RegionArena arena) {
        ObjectArrayList<SectionSlots> occupied = new ObjectArrayList<>(this.sections.size());
        for (SectionSlots slots : this.sections.values()) {
            if (slots.byteOffset[layerIndex] >= 0) {
                occupied.add(slots);
            }
        }
        occupied.sort((a, b) -> Integer.compare(a.byteOffset[layerIndex], b.byteOffset[layerIndex]));
        int needed = 0;
        for (int i = 0; i < occupied.size(); i++) {
            needed += occupied.get(i).byteLength[layerIndex];
        }
        arena.beginRepack(needed);
        int cursor = 0;
        for (int i = 0; i < occupied.size(); i++) {
            SectionSlots slots = occupied.get(i);
            int length = slots.byteLength[layerIndex];
            arena.repackBlock(slots.byteOffset[layerIndex], cursor, length);
            slots.byteOffset[layerIndex] = cursor;
            cursor += length;
        }
        arena.endRepack(cursor);
        VisibleSections.markDirty(); // batches reference offsets that just moved
    }

    void freeSection(long sectionKey) {
        SectionSlots slots = this.sections.remove(sectionKey);
        if (slots == null) {
            return;
        }
        for (int layer = 0; layer < RegionManager.LAYER_COUNT; layer++) {
            int offset = slots.byteOffset[layer];
            RegionArena arena = this.arenas[layer];
            if (offset >= 0 && arena != null) {
                deferFree(layer, offset, slots.byteLength[layer]);
            }
        }
        freeTranslucentIndices(slots);
    }

    /** Frees slots of layers that the section's new geometry no longer has. */
    void dropStaleLayers(long sectionKey, int layerMask) {
        SectionSlots slots = this.sections.get(sectionKey);
        if (slots == null) {
            return;
        }
        for (int layer = 0; layer < RegionManager.LAYER_COUNT; layer++) {
            if (slots.byteOffset[layer] < 0 || (layerMask & (1 << layer)) != 0) {
                continue;
            }
            RegionArena arena = this.arenas[layer];
            if (arena != null) {
                deferFree(layer, slots.byteOffset[layer], slots.byteLength[layer]);
            }
            slots.byteOffset[layer] = -1;
            if (layer == RegionManager.TRANSLUCENT_LAYER_INDEX) {
                freeTranslucentIndices(slots);
            }
        }
        if (slots.isEmpty()) {
            this.sections.remove(sectionKey);
        }
    }

    /** No live sections left, only ranges waiting to be released. */
    boolean isOnlyPending() {
        return this.sections.isEmpty();
    }

    public boolean hasSections() {
        return !this.sections.isEmpty() || !this.pendingFrees.isEmpty();
    }

    // ------------------------------------------------------------------ per-frame batch

    void resetBatches() {
        for (int i = 0; i < RegionManager.LAYER_COUNT; i++) {
            this.batchCounts[i].clear();
            this.batchBaseVertex[i].clear();
        }
        this.batchIndexOffset.clear();
    }

    /** Adds a visible section to the batch, for all layers at once. */
    void addVisible(long sectionKey) {
        SectionSlots slots = this.sections.get(sectionKey);
        if (slots == null) {
            return;
        }
        for (int layer = 0; layer < RegionManager.LAYER_COUNT; layer++) {
            int offset = slots.byteOffset[layer];
            if (offset < 0) {
                continue;
            }
            RegionArena arena = this.arenas[layer];
            if (arena == null) {
                continue;
            }
            if (layer == RegionManager.TRANSLUCENT_LAYER_INDEX) {
                // Vertices arrived, the face order didn't yet: wait for the next sort.
                if (slots.translucentIndexOffset < 0) {
                    continue;
                }
                this.batchIndexOffset.add(slots.translucentIndexOffset);
            }
            this.batchCounts[layer].add(slots.indexCount[layer]);
            this.batchBaseVertex[layer].add(offset / arena.getStride());
        }
    }

    /**
     * Finishes the batch: merges sections that are adjacent in the buffer into one draw. Legal because QUADS read
     * the shared sequential index buffer: one draw with the first section's baseVertex and the summed index count
     * covers exactly both, if they lie back to back. Translucent is never merged (own indices, back-to-front
     * order).
     */
    void finishBatches(boolean merge) {
        for (int layer = 0; layer < RegionManager.LAYER_COUNT; layer++) {
            IntArrayList counts = this.batchCounts[layer];
            if (counts.isEmpty()) {
                continue;
            }
            if (merge && layer != RegionManager.TRANSLUCENT_LAYER_INDEX) {
                mergeLayer(counts, this.batchBaseVertex[layer]);
            }
            int max = 0;
            for (int i = 0; i < counts.size(); i++) {
                max = Math.max(max, counts.getInt(i));
            }
            // The shared index buffer must cover the longest draw, which is longer than any section after merging.
            RegionManager.noteIndexCount(layer, max);
        }
    }

    private static void mergeLayer(IntArrayList counts, IntArrayList baseVertex) {
        int size = counts.size();
        if (size < 2) {
            return;
        }
        // Insertion sort by base vertex: at most 64 elements, nearly sorted already.
        for (int i = 1; i < size; i++) {
            int base = baseVertex.getInt(i);
            int count = counts.getInt(i);
            int j = i - 1;
            while (j >= 0 && baseVertex.getInt(j) > base) {
                baseVertex.set(j + 1, baseVertex.getInt(j));
                counts.set(j + 1, counts.getInt(j));
                j--;
            }
            baseVertex.set(j + 1, base);
            counts.set(j + 1, count);
        }
        int write = 0;
        for (int i = 1; i < size; i++) {
            int run = counts.getInt(write);
            // Vertex count from index count is only valid for QUADS (6 indices per 4 vertices).
            if (run % 6 != 0) {
                write++;
                baseVertex.set(write, baseVertex.getInt(i));
                counts.set(write, counts.getInt(i));
                continue;
            }
            int endVertex = baseVertex.getInt(write) + run / 6 * 4;
            if (baseVertex.getInt(i) == endVertex) {
                counts.set(write, counts.getInt(write) + counts.getInt(i));
                continue;
            }
            write++;
            baseVertex.set(write, baseVertex.getInt(i));
            counts.set(write, counts.getInt(i));
        }
        counts.size(write + 1);
        baseVertex.size(write + 1);
    }

    public IntArrayList getBatchCounts(int layerIndex) {
        return this.batchCounts[layerIndex];
    }

    public IntArrayList getBatchBaseVertex(int layerIndex) {
        return this.batchBaseVertex[layerIndex];
    }

    public IntArrayList getBatchIndexOffset() {
        return this.batchIndexOffset;
    }

    void close() {
        this.pendingFrees.clear();
        if (this.compactionPending) {
            this.compactionPending = false;
            RegionManager.compactionDone();
        }
        for (int i = 0; i < RegionManager.LAYER_COUNT; i++) {
            if (this.arenas[i] != null) {
                this.arenas[i].close();
                this.arenas[i] = null;
            }
        }
        if (this.translucentIndices != null) {
            this.translucentIndices.close();
            this.translucentIndices = null;
        }
        this.sections.clear();
        resetBatches();
    }
}
