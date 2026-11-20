package dev.scandium.render.region;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.scandium.config.ScandiumConfig;
import dev.scandium.render.VisibleSections;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.SectionPos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Registry of terrain regions ("region batching").
 * <p>
 * Vanilla keeps a VBO and a VAO per section and draws each section with its own call. Here sections are packed
 * into shared buffers per 4x4x4 region, and each region is drawn with one glMultiDrawElementsBaseVertex. The
 * translucent layer has its own index arena because its faces are sorted by camera distance.
 * <p>
 * Whether regions are active is decided once per world/renderer reload ({@link #refreshActive()}), never in the
 * middle of a frame: geometry lives either in regions or in vanilla buffers, and switching requires a rebuild of
 * all sections. Render thread only (except {@link #rebaseForRegion}). Ported from Vodium.
 */
public final class RegionManager {
    /** Region side in sections: 1 << 2 = 4. */
    public static final int REGION_SECTION_SHIFT = 2;
    /** Region side in blocks: 1 << 6 = 64. */
    public static final int REGION_BLOCK_SHIFT = REGION_SECTION_SHIFT + 4;
    public static final int LAYER_COUNT = 5;
    /** Index of translucent in RenderType.chunkBufferLayers(). */
    public static final int TRANSLUCENT_LAYER_INDEX = 3;

    private static final Long2ObjectMap<TerrainRegion> REGIONS = new Long2ObjectOpenHashMap<>();
    /** Regions holding released ranges that may still be drawn this frame. */
    private static final ObjectArrayList<TerrainRegion> PENDING_FREE = new ObjectArrayList<>();
    /** Emptied regions, closed once no batch can reference them. */
    private static final ObjectArrayList<TerrainRegion> PENDING_CLOSE = new ObjectArrayList<>();

    static void notePendingFrees(TerrainRegion region) {
        PENDING_FREE.add(region);
    }

    /** Regions with at least one visible section this frame. */
    private static final ObjectArrayList<TerrainRegion> TOUCHED = new ObjectArrayList<>(256);
    /** Longest draw per layer, in indices; the shared sequential buffer must cover it. Never decreases. */
    private static final int[] MAX_INDEX_COUNT = new int[LAYER_COUNT];

    private static volatile boolean active;
    private static volatile boolean translucentActive;
    private static List<RenderType> layers;
    private static int frame;
    private static int pendingCompactions;

    private RegionManager() {
    }

    /** Re-reads the config. Only call when all sections are about to be rebuilt (world load, allChanged). */
    public static void refreshActive() {
        ScandiumConfig cfg = ScandiumConfig.get();
        active = cfg.enabled && cfg.regionBatching;
        translucentActive = active && cfg.translucentBatching;
    }

    public static boolean isActive() {
        return active;
    }

    /** Whether this layer is drawn through regions. Translucent can be switched off separately (riskiest). */
    public static boolean isManaged(int layerIndex) {
        if (!active || layerIndex < 0 || layerIndex >= LAYER_COUNT) {
            return false;
        }
        return layerIndex != TRANSLUCENT_LAYER_INDEX || translucentActive;
    }

    public static int layerIndex(RenderType layer) {
        if (layers == null) {
            layers = RenderType.chunkBufferLayers();
        }
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i) == layer) {
                return i;
            }
        }
        return -1;
    }

    private static long regionKey(int sectionX, int sectionY, int sectionZ) {
        return SectionPos.asLong(sectionX >> REGION_SECTION_SHIFT, sectionY >> REGION_SECTION_SHIFT,
                sectionZ >> REGION_SECTION_SHIFT);
    }

    private static TerrainRegion getOrCreate(int sectionX, int sectionY, int sectionZ) {
        long key = regionKey(sectionX, sectionY, sectionZ);
        TerrainRegion region = REGIONS.get(key);
        if (region == null) {
            region = new TerrainRegion(sectionX >> REGION_SECTION_SHIFT, sectionY >> REGION_SECTION_SHIFT,
                    sectionZ >> REGION_SECTION_SHIFT);
            REGIONS.put(key, region);
        }
        return region;
    }

    /** Takes the (already rebased) vertices of a section into its region's buffer. */
    public static void upload(int sectionX, int sectionY, int sectionZ, int layerIndex,
                              VertexFormat format, ByteBuffer vertices, int vertexCount, int indexCount) {
        RenderSystem.assertOnRenderThread();
        if (!isManaged(layerIndex) || vertexCount == 0) {
            return;
        }
        TerrainRegion region = getOrCreate(sectionX, sectionY, sectionZ);
        // New offsets for this section: the batches must be rebuilt (previously only addRecentlyCompiledSection did
        // that, after ALL of the section's layers were uploaded - with the upload budget that could be frames later).
        VisibleSections.markDirty();
        region.store(SectionPos.asLong(sectionX, sectionY, sectionZ), layerIndex, format, vertices,
                vertexCount * format.getVertexSize(), indexCount);
    }

    /** Sorted indices of a translucent section: after a full rebuild and after each re-sort (no vertices). */
    public static void uploadTranslucentIndices(int sectionX, int sectionY, int sectionZ,
                                                ByteBuffer indices, VertexFormat.IndexType indexType, int indexCount) {
        RenderSystem.assertOnRenderThread();
        if (!isManaged(TRANSLUCENT_LAYER_INDEX) || indexCount == 0) {
            return;
        }
        TerrainRegion region = REGIONS.get(regionKey(sectionX, sectionY, sectionZ));
        if (region == null) {
            return;
        }
        boolean becameDrawable = region.storeTranslucentIndices(SectionPos.asLong(sectionX, sectionY, sectionZ),
                IndexUpcast.to32Bit(indices, indexType, indexCount), indexCount);
        // Only the first indices change the batches; a normal re-sort keeps the address. Re-sorts happen almost
        // every frame while moving, rebuilding batches for them would undo the whole point.
        if (becameDrawable) {
            VisibleSections.markDirty();
        }
    }

    /**
     * Moves vertex positions from section space into region space. Runs on the mesh WORKER thread before the
     * upload is queued, so the render thread only copies bytes. Thread-safe: pure arithmetic on the buffer.
     */
    public static void rebaseForRegion(int sectionX, int sectionY, int sectionZ, VertexFormat format,
                                       ByteBuffer vertices, int vertexCount) {
        if (vertexCount == 0) {
            return;
        }
        int regionX = (sectionX >> REGION_SECTION_SHIFT) << REGION_BLOCK_SHIFT;
        int regionY = (sectionY >> REGION_SECTION_SHIFT) << REGION_BLOCK_SHIFT;
        int regionZ = (sectionZ >> REGION_SECTION_SHIFT) << REGION_BLOCK_SHIFT;
        float dx = (sectionX << 4) - regionX;
        float dy = (sectionY << 4) - regionY;
        float dz = (sectionZ << 4) - regionZ;
        int stride = format.getVertexSize();
        // duplicate() resets the byte order to BIG_ENDIAN, the data is native.
        ByteBuffer buffer = vertices.duplicate().order(ByteOrder.nativeOrder());
        for (int i = 0; i < vertexCount; i++) {
            int base = i * stride; // position = first three floats of the BLOCK format
            buffer.putFloat(base, buffer.getFloat(base) + dx);
            buffer.putFloat(base + 4, buffer.getFloat(base + 4) + dy);
            buffer.putFloat(base + 8, buffer.getFloat(base + 8) + dz);
        }
    }

    /**
     * Frees slots of layers the section no longer has. Only valid once the section's compiled data was swapped
     * in (addRecentlyCompiledSection); earlier, the old data would make a freshly uploaded layer look stale.
     */
    public static void syncSectionLayers(int sectionX, int sectionY, int sectionZ, int layerMask) {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> syncSectionLayers(sectionX, sectionY, sectionZ, layerMask));
            return;
        }
        TerrainRegion region = REGIONS.get(regionKey(sectionX, sectionY, sectionZ));
        if (region != null) {
            region.dropStaleLayers(SectionPos.asLong(sectionX, sectionY, sectionZ), layerMask);
        }
    }

    public static void freeSection(int sectionX, int sectionY, int sectionZ) {
        RenderSystem.assertOnRenderThread();
        if (REGIONS.isEmpty()) {
            return;
        }
        VisibleSections.markDirty();
        long key = regionKey(sectionX, sectionY, sectionZ);
        TerrainRegion region = REGIONS.get(key);
        if (region == null) {
            return;
        }
        region.freeSection(SectionPos.asLong(sectionX, sectionY, sectionZ));
        // An emptied region is closed only after the batches were rebuilt: this frame may still draw it.
        if (!region.hasSections() || region.isOnlyPending()) {
            REGIONS.remove(key);
            PENDING_CLOSE.add(region);
        }
    }

    public static void clear() {
        RenderSystem.assertOnRenderThread();
        for (int i = 0; i < PENDING_CLOSE.size(); i++) {
            PENDING_CLOSE.get(i).close();
        }
        PENDING_CLOSE.clear();
        PENDING_FREE.clear();
        VisibleSections.markDirty();
        for (TerrainRegion region : REGIONS.values()) {
            region.close();
        }
        REGIONS.clear();
        TOUCHED.clear();
        pendingCompactions = 0;
        Arrays.fill(MAX_INDEX_COUNT, 0);
    }

    // ------------------------------------------------------------------ per-frame batches

    public static void beginFrame() {
        // Batches are being rebuilt: nothing references released ranges or removed regions anymore.
        for (int i = 0; i < PENDING_FREE.size(); i++) {
            PENDING_FREE.get(i).flushPendingFrees();
        }
        PENDING_FREE.clear();
        for (int i = 0; i < PENDING_CLOSE.size(); i++) {
            PENDING_CLOSE.get(i).close();
        }
        PENDING_CLOSE.clear();
        TOUCHED.clear();
        frame++;
    }

    /** Compacts at most one region per frame, outside the upload path, so the cost is spread out. */
    public static void maintain() {
        if (pendingCompactions == 0 || !ScandiumConfig.get().arenaCompaction) {
            return;
        }
        for (TerrainRegion region : REGIONS.values()) {
            if (region.isCompactionPending()) {
                region.compactNow();
                return;
            }
        }
    }

    static void compactionRequested() {
        pendingCompactions++;
    }

    static void compactionDone() {
        if (pendingCompactions > 0) {
            pendingCompactions--;
        }
    }

    /** Marks a section visible this frame (the region is looked up once per section, for all layers). */
    public static void addVisibleSection(int sectionX, int sectionY, int sectionZ) {
        TerrainRegion region = REGIONS.get(regionKey(sectionX, sectionY, sectionZ));
        if (region == null) {
            return;
        }
        // Frame stamp instead of contains(): avoids O(sections * regions) per frame.
        if (region.lastFrame != frame) {
            region.lastFrame = frame;
            region.resetBatches();
            TOUCHED.add(region);
        }
        region.addVisible(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    /** Batches complete: merge neighbours (only possible once the region's visible set is complete). */
    public static void endFrame() {
        boolean merge = ScandiumConfig.get().regionDrawMerging;
        for (int i = 0; i < TOUCHED.size(); i++) {
            TOUCHED.get(i).finishBatches(merge);
        }
    }

    static void noteIndexCount(int layerIndex, int indexCount) {
        if (indexCount > MAX_INDEX_COUNT[layerIndex]) {
            MAX_INDEX_COUNT[layerIndex] = indexCount;
        }
    }

    public static int maxIndexCount(int layerIndex) {
        return MAX_INDEX_COUNT[layerIndex];
    }

    public static ObjectArrayList<TerrainRegion> getTouchedRegions() {
        return TOUCHED;
    }

    public static int regionCount() {
        return REGIONS.size();
    }
}
