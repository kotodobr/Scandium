package dev.scandium.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

/**
 * The 16x16x16 sections that the vanilla occlusion graph considers visible this frame. Filled at the end of
 * {@code LevelRenderer.setupRender}, read while entities and block entities are rendered. Its dirty flag also
 * covers the region batches built from the same list.
 * <p>
 * Render thread only. Ported from Vodium; Scandium additionally never culls things outside the world's
 * build height, where no sections exist (e.g. an elytra flight above the build limit).
 */
public final class VisibleSections {
    private static final LongOpenHashSet VISIBLE = new LongOpenHashSet(8192);
    /** Vanilla only rebuilds its section list after a noticeable camera turn or an occlusion-graph update. */
    private static volatile boolean dirty = true;
    /** false until setupRender ran in this world: then nothing is culled. */
    private static boolean ready;
    private static int minSectionY;
    private static int maxSectionY;

    private VisibleSections() {
    }

    public static void markDirty() {
        dirty = true;
    }

    public static boolean consumeDirty() {
        boolean wasDirty = dirty;
        dirty = false;
        return wasDirty;
    }

    public static void begin(int minSectionY, int maxSectionY) {
        VISIBLE.clear();
        VisibleSections.minSectionY = minSectionY;
        VisibleSections.maxSectionY = maxSectionY;
    }

    public static void add(int sectionX, int sectionY, int sectionZ) {
        VISIBLE.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    public static void end() {
        ready = true;
    }

    /** Drops the set without requesting a rebuild (culling is off). */
    public static void reset() {
        VISIBLE.clear();
        ready = false;
    }

    public static void invalidate() {
        VISIBLE.clear();
        ready = false;
        dirty = true;
    }

    public static boolean isReady() {
        return ready;
    }

    /** @return true if at least one section touched by the box is visible (a box usually spans 1-4 sections) */
    public static boolean intersectsVisible(AABB box) {
        int minX = SectionPos.posToSectionCoord(box.minX);
        int minY = SectionPos.posToSectionCoord(box.minY);
        int minZ = SectionPos.posToSectionCoord(box.minZ);
        int maxX = SectionPos.posToSectionCoord(box.maxX);
        int maxY = SectionPos.posToSectionCoord(box.maxY);
        int maxZ = SectionPos.posToSectionCoord(box.maxZ);

        if (minY < minSectionY || maxY > maxSectionY) {
            return true; // partly outside the world's sections: no data, don't cull
        }
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (VISIBLE.contains(SectionPos.asLong(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isSectionVisible(int blockX, int blockY, int blockZ) {
        int sectionY = SectionPos.blockToSectionCoord(blockY);
        if (sectionY < minSectionY || sectionY > maxSectionY) {
            return true;
        }
        return VISIBLE.contains(SectionPos.asLong(
                SectionPos.blockToSectionCoord(blockX), sectionY, SectionPos.blockToSectionCoord(blockZ)));
    }
}
