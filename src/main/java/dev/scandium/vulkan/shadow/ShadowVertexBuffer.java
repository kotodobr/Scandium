package dev.scandium.vulkan.shadow;

/** Mixed into VertexBuffer: its Vulkan mirror and whether GL uses it as a dynamic (per-draw) buffer. */
public interface ShadowVertexBuffer {
    VertexMirror scandium$mirror();

    boolean scandium$isDynamic();
}
