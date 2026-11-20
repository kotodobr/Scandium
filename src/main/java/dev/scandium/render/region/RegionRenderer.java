package dev.scandium.render.region;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.scandium.config.ScandiumConfig;
import dev.scandium.render.RenderStats;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/**
 * Draws a layer region by region: one glMultiDrawElementsBaseVertex per region instead of one call per section.
 * Called inside vanilla's renderSectionLayer after the shader, samplers and uniforms are set up and before they
 * are cleared. Ported from Vodium (shader-pack path removed).
 */
public final class RegionRenderer {
    /** 4x4x4 = 64 sections per region: no batch can have more draws. */
    private static final int MAX_DRAWS = 1 << (RegionManager.REGION_SECTION_SHIFT * 3);
    private static final IntBuffer COUNTS = MemoryUtil.memAllocInt(MAX_DRAWS);
    private static final IntBuffer BASE_VERTICES = MemoryUtil.memAllocInt(MAX_DRAWS);
    private static final PointerBuffer INDEX_OFFSETS = MemoryUtil.memAllocPointer(MAX_DRAWS);

    private RegionRenderer() {
    }

    public static void drawLayer(int layerIndex, double cameraX, double cameraY, double cameraZ) {
        RenderSystem.assertOnRenderThread();
        ObjectArrayList<TerrainRegion> regions = RegionManager.getTouchedRegions();
        if (regions.isEmpty()) {
            return;
        }
        ShaderInstance shader = RenderSystem.getShader();
        Uniform chunkOffset = shader == null ? null : shader.CHUNK_OFFSET;
        RenderSystem.AutoStorageIndexBuffer indexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        int glMode = VertexFormat.Mode.QUADS.asGLMode;
        boolean translucent = layerIndex == RegionManager.TRANSLUCENT_LAYER_INDEX;
        boolean bindCache = ScandiumConfig.get().regionIndexBindCache;
        int ensuredIndices = 0;

        // Fog culling: beyond FogEnd vanilla's linear fog outputs the pure fog colour.
        float fogEnd = RenderSystem.getShaderFogEnd();
        float[] fogColor = RenderSystem.getShaderFogColor();
        boolean fogCull = ScandiumConfig.get().enabled && ScandiumConfig.get().fogCulling
                && fogColor[3] >= 1.0f && fogEnd > 0.0f && Float.isFinite(fogEnd);
        boolean fogCylinder = RenderSystem.getShaderFogShape() == FogShape.CYLINDER;
        double fogEndSquared = (double) fogEnd * fogEnd;
        int drawCalls = 0;

        // Translucent must be drawn back to front. Vanilla walks the visible list backwards, and regions are
        // collected in the same order, so walking them backwards gives the same result (only region borders
        // can differ - grouping by region is the price of batching).
        for (int i = 0; i < regions.size(); i++) {
            TerrainRegion region = regions.get(translucent ? regions.size() - 1 - i : i);
            IntArrayList counts = region.getBatchCounts(layerIndex);
            if (counts.isEmpty()) {
                continue;
            }
            RegionArena arena = region.getArena(layerIndex);
            if (arena == null) {
                continue;
            }
            if (fogCull && isBeyondFog(region, cameraX, cameraY, cameraZ, fogEndSquared, fogCylinder)) {
                continue;
            }
            IntArrayList baseVertices = region.getBatchBaseVertex(layerIndex);
            IntArrayList indexOffsets = translucent ? region.getBatchIndexOffset() : null;
            RegionIndexArena indexArena = translucent ? region.getTranslucentIndices() : null;
            if (translucent && indexArena == null) {
                continue;
            }

            int draws = Math.min(counts.size(), MAX_DRAWS);
            int maxIndexCount = 0;
            for (int d = 0; d < draws; d++) {
                int s = translucent ? draws - 1 - d : d; // back to front inside the region too
                int count = counts.getInt(s);
                COUNTS.put(d, count);
                BASE_VERTICES.put(d, baseVertices.getInt(s));
                // Write 0 explicitly: the same buffer keeps translucent offsets from the previous layer.
                INDEX_OFFSETS.put(d, translucent ? indexOffsets.getInt(s) : 0L);
                maxIndexCount = Math.max(maxIndexCount, count);
            }
            COUNTS.limit(draws).position(0);
            BASE_VERTICES.limit(draws).position(0);
            INDEX_OFFSETS.limit(draws).position(0);

            // VAO first: the element buffer binding is stored in the bound VAO.
            arena.bindForDraw();
            int glType;
            if (translucent) {
                indexArena.bindForDraw();
                glType = GL11.GL_UNSIGNED_INT;
            } else {
                if (maxIndexCount > ensuredIndices) {
                    ensuredIndices = Math.max(maxIndexCount, RegionManager.maxIndexCount(layerIndex));
                    indexBuffer.bind(ensuredIndices);
                    arena.markSequentialIndexBinding();
                } else if (!bindCache || !arena.hasSequentialIndexBinding()) {
                    indexBuffer.bind(ensuredIndices);
                    arena.markSequentialIndexBinding();
                }
                // Read after bind: growing may switch the type from short to int.
                glType = indexBuffer.type().asGLType;
            }

            if (chunkOffset != null) {
                chunkOffset.set((float) (region.getOriginX() - cameraX), (float) (region.getOriginY() - cameraY),
                        (float) (region.getOriginZ() - cameraZ));
                chunkOffset.upload();
            }
            GL32.glMultiDrawElementsBaseVertex(glMode, COUNTS, glType, INDEX_OFFSETS, BASE_VERTICES);
            drawCalls++;

            COUNTS.clear();
            BASE_VERTICES.clear();
            INDEX_OFFSETS.clear();
        }

        // The same shader program is used for moving blocks (pistons, falling blocks) through immediate-mode
        // buffers; a leftover region offset would displace them.
        if (chunkOffset != null) {
            chunkOffset.set(0.0f, 0.0f, 0.0f);
            chunkOffset.upload();
        }
        RenderStats.regionDraws(drawCalls);
    }

    /** Whether the whole region is drowned in fog (measured to the region's nearest point). */
    private static boolean isBeyondFog(TerrainRegion region, double camX, double camY, double camZ,
                                       double fogEndSquared, boolean cylinder) {
        int size = 1 << RegionManager.REGION_BLOCK_SHIFT;
        double dx = axisDistance(camX, region.getOriginX(), size);
        double dz = axisDistance(camZ, region.getOriginZ(), size);
        double distanceSquared = dx * dx + dz * dz;
        if (!cylinder) {
            double dy = axisDistance(camY, region.getOriginY(), size);
            distanceSquared += dy * dy;
        }
        return distanceSquared > fogEndSquared;
    }

    private static double axisDistance(double camera, int origin, int size) {
        if (camera < origin) {
            return origin - camera;
        }
        double far = origin + (double) size;
        return camera > far ? camera - far : 0.0;
    }
}
