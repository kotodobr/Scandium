package dev.scandium.leak;

import net.minecraft.world.level.biome.Biome;

/**
 * Replacement for the per-biome temperature cache.
 * <p>
 * Vanilla gives <em>every biome</em> its own {@code ThreadLocal} holding a 1024-entry linked hash map, so the
 * memory used is (number of biomes) x (number of threads that ever asked for a temperature) x ~80 KB, and the maps
 * of short-lived worker threads linger until the thread dies.
 * <p>
 * Scandium uses one small direct-mapped cache per thread, shared by all biomes. Each slot stores the biome it
 * belongs to, so different biomes never see each other's values. A slot collision simply overwrites the old
 * value; recomputing a temperature is cheap (one noise sample above y=80), so a perfect hit rate isn't needed.
 * The memory cost is a few kilobytes per thread, independent of how many biomes exist.
 */
public final class SharedTemperatureCache {
    private static final int SIZE = 512; // power of two
    private static final int MASK = SIZE - 1;

    private static final ThreadLocal<SharedTemperatureCache> CACHE = ThreadLocal.withInitial(SharedTemperatureCache::new);

    private final long[] positions = new long[SIZE];
    private final Biome[] owners = new Biome[SIZE];
    private final float[] values = new float[SIZE];

    private SharedTemperatureCache() {
    }

    /** Returns the cache of the current thread. Callers keep it in a local to avoid a second lookup. */
    public static SharedTemperatureCache current() {
        return CACHE.get();
    }

    /** @return the cached value, or {@code NaN} on a miss */
    public float lookup(Biome biome, long packedPos) {
        int slot = slot(biome, packedPos);
        if (this.owners[slot] == biome && this.positions[slot] == packedPos) {
            return this.values[slot];
        }
        return Float.NaN;
    }

    public void store(Biome biome, long packedPos, float value) {
        int slot = slot(biome, packedPos);
        this.owners[slot] = biome;
        this.positions[slot] = packedPos;
        this.values[slot] = value;
    }

    private static int slot(Biome biome, long packedPos) {
        return mix(packedPos, System.identityHashCode(biome)) & MASK;
    }

    private static int mix(long pos, int biomeHash) {
        long h = pos * 0x9E3779B97F4A7C15L + biomeHash;
        h ^= h >>> 29;
        return (int) (h ^ (h >>> 32));
    }
}
