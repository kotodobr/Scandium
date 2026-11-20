package dev.scandium.vulkan.shadow;

import dev.scandium.vulkan.VulkanTexture;

/** Vulkan copy of one GL texture plus the GL sampling parameters set on it. */
final class TextureMirror {
    VulkanTexture texture;
    int vkFormat;
    /** GL_TEXTURE_MAX_LEVEL, set before the levels are allocated. */
    int maxLevel;
    boolean linear;
    boolean mipmap;
    boolean clamp;
    /** False until real pixels arrived (render-target textures never get any). */
    boolean uploaded;
}
