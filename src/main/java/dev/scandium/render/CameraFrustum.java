package dev.scandium.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * The camera frustum of the current frame, captured at the end of {@code LevelRenderer.setupRender}.
 * Vanilla only tests terrain sections and entities against it - never particles.
 * <p>
 * Stores the reference: vanilla builds a new Frustum every frame and doesn't modify it after setupRender.
 * Render thread only. Ported from Vodium.
 */
public final class CameraFrustum {
    @Nullable
    private static Frustum frustum;

    private CameraFrustum() {
    }

    /**
     * @param captured true when vanilla uses a captured debug frustum (F3 + "capture frustum"); it doesn't match
     *                 what is on screen, so culling is switched off in that mode
     */
    public static void set(Frustum frustum, boolean captured) {
        CameraFrustum.frustum = captured ? null : frustum;
    }

    public static void clear() {
        frustum = null;
    }

    public static boolean isReady() {
        return frustum != null;
    }

    /** @return true if the box is visible, or if no frustum is known yet (never cull blindly) */
    public static boolean isVisible(AABB box) {
        Frustum current = frustum;
        return current == null || current.isVisible(box);
    }
}
