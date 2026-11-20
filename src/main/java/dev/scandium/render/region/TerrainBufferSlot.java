package dev.scandium.render.region;

/**
 * Mixed into VertexBuffer so the buffer knows which section and layer it belongs to: the upload only receives the
 * buffer, not the section. Ported from Vodium.
 */
public interface TerrainBufferSlot {
    /** @param layerIndex index in RenderType.chunkBufferLayers(), -1 if not terrain */
    void scandium$setTerrainSlot(int sectionX, int sectionY, int sectionZ, int layerIndex);

    int scandium$getSectionX();

    int scandium$getSectionY();

    int scandium$getSectionZ();

    int scandium$getLayerIndex();
}
