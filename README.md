# Scandium

Fabric mod for Minecraft **1.20.4**: garbage-collector scheduling, memory-leak fixes, memory-usage
optimizations, plus render and physics optimizations from Vodium. No dependencies besides Fabric Loader (Fabric API is not required).

## 1. GC scheduling (`dev.scandium.gc`)

The JVM only sees memory, not the game. Scandium tells it when a collection is cheap:

| Moment | What happens |
|---|---|
| Leaving a world / server | Collection 1 s after disconnect: the whole old world is garbage now |
| Resource reload finished (incl. game start) | Collection while the loading overlay fades out |
| `/reload` on a server | Collection after data packs are reloaded |
| Game paused / in menus | Idle collection, only if ≥ `minGarbageMbForIdleGc` MB of garbage piled up |
| Dedicated server with 0 players | Same idle collection |
| Heap ≥ 85 % full during play | Collection, **only** if it runs concurrently (no freeze) |

Collections always run on a separate thread (`Scandium GC Scheduler`), never on the game thread, and respect
a cooldown (`minSecondsBetweenGc`).

The collector itself also gets **hints** through manageable HotSpot flags (never overriding flags you set yourself):

* **G1**: `G1PeriodicGCInterval` (default 120 s): after 2 minutes without a GC, G1 starts a *concurrent*
  cycle and gives unused memory back to the OS.
* **ZGC / Shenandoah**: `SoftMaxHeapSize` = 80 % of `-Xmx`: the collector keeps the heap below this and
  starts cycles earlier and more smoothly, using the rest only under real pressure.

At startup the log contains `[JVM advice]` lines about your JVM arguments. The F3 screen shows GC type,
pause times, the estimated live set, garbage, and the allocation rate.

### Recommended JVM arguments

```
-Xms4G -Xmx4G -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent -XX:MaxGCPauseMillis=50
```

or, on Java 21+:

```
-Xms4G -Xmx4G -XX:+UseZGC -XX:+ZGenerational
```

`-XX:+ExplicitGCInvokesConcurrent` matters for G1: without it every scheduled collection is a full
stop-the-world GC. Scandium then only collects in moments where a freeze is invisible (loading, pause, menus)
and skips the "heap almost full" collection during gameplay.
Do **not** use `-XX:+DisableExplicitGC`: it switches scheduling off completely.

## 2. Leak fixes (`dev.scandium.mixin.leak`, own implementations)

* **Biome temperature cache.** Vanilla gives every biome its own `ThreadLocal` with a 1024-entry map, so memory
  grows with biomes × threads. Replaced by one direct-mapped 512-slot cache per thread shared by all biomes
  (a few KB per thread, no allocations).
* **Texture/resource reading (MC-226729).** The native buffer was only freed on `IOException`; now it is freed on
  any failure, including a failed `memRealloc` (which also lost the old buffer).
* **Huge screenshots.** If a huge tiled screenshot fails, the native tile buffer is freed and the output
  file stream is closed. The broken partial file is deleted.
* **Stale client references.** `crosshairPickEntity` / `hitResult` kept the whole previous world alive in the
  main menu; they are cleared on disconnect.

## 3. Memory optimizations from FerriteCore (`dev.scandium.ferrite`, MIT)

Ported from FerriteCore 6.0.3 for 1.20.4 (see `THIRD_PARTY_NOTICES.md`): FastMap block-state neighbor tables
and property maps, multipart predicate caching and model deduplication, block-state shape cache deduplication,
baked-quad vertex deduplication, `ModelResourceLocation` string deduplication, compact model sides, and an
optional small threading detector. Scandium declares `breaks: ferritecore`, so don't install both.

## 4. Render and physics optimizations from Vodium

Ported from Vodium 0.8.6 (1.20.4, `vodium-optim` branch), translated from Yarn to Mojang names.

| Optimization | Side | What it does |
|---|---|---|
| Region batching | client | Sections share GPU buffers per 4x4x4 region; each region and layer is drawn with one `glMultiDrawElementsBaseVertex` instead of one call per section. Neighbouring sections are merged into a single draw. Fragmented buffers are compacted one region per frame |
| Dedicated mesh pool | client | Chunk meshes are built on their own threads, not in the queue behind world generation |
| Upload budget | client | Finished meshes are uploaded to the GPU over several frames instead of one spike |
| Rebuild limit | client | At most 64 sections are sent to rebuild per frame (after teleports/joining) |
| Translucent sort distance | client | Water/glass farther than 128 blocks is not re-sorted on every camera step |
| Entity / block entity culling | client | Uses the vanilla occlusion graph: helps indoors and in caves, little in open fields |
| Particle culling | client | Particles outside the frustum build no geometry (vanilla never checks) |
| Fast block collisions | server | The per-tick "blocks inside the entity" scan is skipped when all touched sections are air |
| Mob AI throttle | server | Mobs without a target and with no player within 32 blocks think every 8 ticks. A deliberate behaviour change far from players; can be switched off |

The render optimizations (including region batching) are skipped automatically when Sodium (or a fork:
Embeddium, Rubidium, Magnesium) or Vodium is installed; the physics ones when Lithium (or Canary/Radium) or
Vodium is installed. `/scandium enable|disable` rebuilds the terrain, because region batching keeps the geometry
outside the vanilla buffers. The F3 screen shows how many entities, block entities and particles were culled.

## Commands

| Command | |
|---|---|
| `/scandium disable` | Stops GC scheduling and reverts JVM hints immediately; after a restart all optimizations are off (vanilla baseline). Persisted in the config. |
| `/scandium enable` | Turns everything back on (memory optimizations after a restart). |
| `/scandium bench` | Client-side, works on any server: records 15 s of frame times and prints avg, 1 % low, 0.1 % low and lowest FPS, plus GC pauses during the run. "X % low" = average of the slowest X % of frames. |

`enable`/`disable` need operator level 2 on a dedicated server; in singleplayer they work without cheats.

## Configuration

* `config/scandium.json`: GC scheduling, hints, leak fixes, F3 overlay.
* `config/scandium-ferrite.mixin.properties`: the individual FerriteCore optimizations (needs a restart).

## Building

```
./gradlew build
```

The jar ends up in `build/libs/`. JDK 21 is used for Gradle (`gradle.properties`); the mod targets Java 17.
