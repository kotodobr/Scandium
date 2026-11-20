package dev.scandium.vulkan.shadow;

import dev.scandium.vulkan.shader.PipelineState;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Mirror of the OpenGL state that matters for drawing, updated by {@code GlStateManagerMixin} whenever the game
 * changes it. Render thread only. The shadow renderer turns it into a {@link PipelineState} at each draw, so the
 * Vulkan copy uses exactly the state the GL draw used - including custom RenderTypes of mods.
 */
public final class GlStateTracker {
    // blend
    static boolean blend;
    static int srcColor = 1;
    static int dstColor = 0;
    static int srcAlpha = 1;
    static int dstAlpha = 0;
    static int blendEquation = 0x8006; // GL_FUNC_ADD
    // depth
    static boolean depthTest;
    static int depthFunc = 0x0201; // GL_LESS
    static boolean depthMask = true;
    // raster
    static boolean cull;
    static boolean polygonOffset;
    static float offsetFactor;
    static float offsetUnits;
    static boolean red = true;
    static boolean green = true;
    static boolean blue = true;
    static boolean alpha = true;
    // viewport / scissor (GL coordinates: origin bottom-left)
    static int viewportX;
    static int viewportY;
    static int viewportW;
    static int viewportH;
    static boolean scissor;
    static int scissorX;
    static int scissorY;
    static int scissorW;
    static int scissorH;
    // framebuffer and clear values
    static int drawFramebuffer;
    static float clearR;
    static float clearG;
    static float clearB;
    static float clearA;
    static double clearDepth = 1.0;
    // textures
    static int activeUnit;
    static final int[] BOUND = new int[32];
    // pixel unpack
    static int unpackRowLength;
    static int unpackSkipRows;
    static int unpackSkipPixels;
    static int unpackAlignment = 4;

    private GlStateTracker() {
    }

    // ---- hooks (called from GlStateManagerMixin)

    public static void blend(boolean enabled) {
        blend = enabled;
    }

    public static void blendFunc(int src, int dst) {
        srcColor = src;
        dstColor = dst;
        srcAlpha = src;
        dstAlpha = dst;
    }

    public static void blendFuncSeparate(int sc, int dc, int sa, int da) {
        srcColor = sc;
        dstColor = dc;
        srcAlpha = sa;
        dstAlpha = da;
    }

    public static void blendEquation(int equation) {
        blendEquation = equation;
    }

    public static void depthTest(boolean enabled) {
        depthTest = enabled;
    }

    public static void depthFunc(int func) {
        depthFunc = func;
    }

    public static void depthMask(boolean mask) {
        depthMask = mask;
    }

    public static void cull(boolean enabled) {
        cull = enabled;
    }

    public static void polygonOffset(boolean enabled) {
        polygonOffset = enabled;
    }

    public static void polygonOffset(float factor, float units) {
        offsetFactor = factor;
        offsetUnits = units;
    }

    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        red = r;
        green = g;
        blue = b;
        alpha = a;
    }

    public static void viewport(int x, int y, int w, int h) {
        viewportX = x;
        viewportY = y;
        viewportW = w;
        viewportH = h;
    }

    public static void scissor(boolean enabled) {
        scissor = enabled;
    }

    public static void scissorBox(int x, int y, int w, int h) {
        scissorX = x;
        scissorY = y;
        scissorW = w;
        scissorH = h;
    }

    public static void bindFramebuffer(int target, int framebuffer) {
        // GL_FRAMEBUFFER (0x8D40) and GL_DRAW_FRAMEBUFFER (0x8CA9) select the draw target; READ only reads.
        if (target == 0x8D40 || target == 0x8CA9) {
            drawFramebuffer = framebuffer;
        }
    }

    public static void clearColor(float r, float g, float b, float a) {
        clearR = r;
        clearG = g;
        clearB = b;
        clearA = a;
    }

    public static void clearDepth(double depth) {
        clearDepth = depth;
    }

    public static void activeTexture(int glUnit) {
        activeUnit = glUnit - 0x84C0; // GL_TEXTURE0
    }

    public static void bindTexture(int texture) {
        if (activeUnit >= 0 && activeUnit < BOUND.length) {
            BOUND[activeUnit] = texture;
        }
    }

    public static int boundTexture() {
        return activeUnit >= 0 && activeUnit < BOUND.length ? BOUND[activeUnit] : 0;
    }

    public static void pixelStore(int name, int value) {
        switch (name) {
            case 0x0CF2 -> unpackRowLength = value;  // GL_UNPACK_ROW_LENGTH
            case 0x0CF3 -> unpackSkipRows = value;   // GL_UNPACK_SKIP_ROWS
            case 0x0CF4 -> unpackSkipPixels = value; // GL_UNPACK_SKIP_PIXELS
            case 0x0CF5 -> unpackAlignment = value;  // GL_UNPACK_ALIGNMENT
            default -> {
            }
        }
    }

    /** The current GL state as a Vulkan pipeline state for the given topology. */
    static PipelineState snapshot(int topology) {
        int mask = (red ? VK_COLOR_COMPONENT_R_BIT : 0) | (green ? VK_COLOR_COMPONENT_G_BIT : 0)
                | (blue ? VK_COLOR_COMPONENT_B_BIT : 0) | (alpha ? VK_COLOR_COMPONENT_A_BIT : 0);
        return new PipelineState(blend,
                PipelineState.blendFactorFromGl(srcColor), PipelineState.blendFactorFromGl(dstColor),
                PipelineState.blendFactorFromGl(srcAlpha), PipelineState.blendFactorFromGl(dstAlpha),
                PipelineState.blendOpFromGl(blendEquation),
                depthTest ? PipelineState.compareOpFromGl(depthFunc) : VK_COMPARE_OP_ALWAYS,
                depthTest, depthMask, mask, cull, polygonOffset, offsetFactor, offsetUnits, topology,
                java.util.List.of());
    }
}
