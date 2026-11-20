package dev.scandium.render;

/** Per-frame counters of culled objects for the F3 screen. Render thread only. */
public final class RenderStats {
    private static int entities;
    private static int blockEntities;
    private static int particles;
    private static int lastEntities;
    private static int lastBlockEntities;
    private static int lastParticles;
    private static int regionDraws;
    private static int lastRegionDraws;

    private RenderStats() {
    }

    public static void beginFrame() {
        lastEntities = entities;
        lastBlockEntities = blockEntities;
        lastParticles = particles;
        lastRegionDraws = regionDraws;
        regionDraws = 0;
        entities = 0;
        blockEntities = 0;
        particles = 0;
    }

    public static void entityCulled() {
        entities++;
    }

    public static void blockEntityCulled() {
        blockEntities++;
    }

    public static void particleCulled() {
        particles++;
    }

    public static void regionDraws(int calls) {
        regionDraws += calls;
    }

    public static String regionSummary() {
        return "regions: " + dev.scandium.render.region.RegionManager.regionCount() + ", terrain draw calls: "
                + lastRegionDraws;
    }

    public static String summary() {
        return "culled per frame: " + lastEntities + " entities, " + lastBlockEntities + " block entities, "
                + lastParticles + " particles";
    }
}
