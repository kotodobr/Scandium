package dev.scandium.vulkan.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Fixed-function state of a pipeline: blending, depth, culling, write masks, depth bias, topology. All values are
 * already Vulkan enums. Used as part of the pipeline cache key, hence a record.
 * <p>
 * Two sources: {@link #fromRenderType} parses a RenderType's description text (shard names survive obfuscation,
 * field names don't), and the shadow renderer converts the live GL state ({@code GlStateTracker}).
 */
public record PipelineState(boolean blend, int srcColor, int dstColor, int srcAlpha, int dstAlpha, int blendOp,
                            int depthCompare, boolean depthTest, boolean depthWrite, int colorWriteMask, boolean cull,
                            boolean polygonOffset, float offsetFactor, float offsetUnits, int topology,
                            List<String> unknown) {

    public static final int ALL_COLORS = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    /** Vanilla's defaults when a RenderType doesn't mention a shard: opaque, LEQUAL depth, culling, full writes. */
    public static PipelineState opaque(int topology) {
        return new PipelineState(false, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE,
                VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD, VK_COMPARE_OP_LESS_OR_EQUAL, true, true, ALL_COLORS, true,
                false, 0, 0, topology, List.of());
    }

    /** @param description {@code RenderType.toString()} */
    public static PipelineState fromRenderType(String description, int topology) {
        int[] factors = null; // src color, dst color, src alpha, dst alpha
        int compare = VK_COMPARE_OP_LESS_OR_EQUAL;
        boolean depthTest = true;
        boolean depthWrite = true;
        int colorMask = ALL_COLORS;
        boolean cull = true;
        boolean polygonOffset = false;
        List<String> unknown = new ArrayList<>();

        String text = description.toLowerCase(Locale.ROOT);
        int transparency = text.indexOf("_transparency");
        if (transparency >= 0) {
            int start = Math.max(text.lastIndexOf('[', transparency) + 1, text.lastIndexOf(", ", transparency) + 2);
            String shard = text.substring(start, transparency);
            factors = switch (shard) {
                case "no" -> null;
                case "additive" -> new int[]{VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE};
                case "lightning" -> new int[]{VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_SRC_ALPHA,
                        VK_BLEND_FACTOR_ONE};
                case "glint" -> new int[]{VK_BLEND_FACTOR_SRC_COLOR, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO,
                        VK_BLEND_FACTOR_ONE};
                case "crumbling" -> new int[]{VK_BLEND_FACTOR_DST_COLOR, VK_BLEND_FACTOR_SRC_COLOR, VK_BLEND_FACTOR_ONE,
                        VK_BLEND_FACTOR_ZERO};
                case "translucent" -> translucent();
                default -> {
                    unknown.add(shard + "_transparency");
                    yield translucent();
                }
            };
        }
        if (text.contains("depth_test[always]")) {
            depthTest = false;
            compare = VK_COMPARE_OP_ALWAYS;
        } else if (text.contains("depth_test[==]")) {
            compare = VK_COMPARE_OP_EQUAL;
        } else if (text.contains("depth_test[>]")) {
            compare = VK_COMPARE_OP_GREATER;
        }
        if (text.contains("writecolor=false")) {
            colorMask = 0;
        }
        if (text.contains("writedepth=false")) {
            depthWrite = false;
        }
        if (text.contains("cull[false]")) {
            cull = false;
        }
        if (text.contains("polygon_offset_layering")) {
            polygonOffset = true;
        }
        boolean blend = factors != null;
        int[] f = blend ? factors : new int[]{VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO};
        return new PipelineState(blend, f[0], f[1], f[2], f[3], VK_BLEND_OP_ADD, compare, depthTest, depthWrite,
                colorMask, cull, polygonOffset, polygonOffset ? -1.0f : 0.0f, polygonOffset ? -10.0f : 0.0f,
                topology, unknown);
    }

    private static int[] translucent() {
        return new int[]{VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE,
                VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA};
    }

    /** VertexFormat.Mode's GL primitive (asGLMode) to Vulkan topology. QUADS are drawn as triangles via indices. */
    public static int topologyFromGl(int glMode) {
        return switch (glMode) {
            case 0 -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case 1 -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case 3 -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case 5 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case 6 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            default -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        };
    }

    /** GL blend factor enum to Vulkan. */
    public static int blendFactorFromGl(int gl) {
        return switch (gl) {
            case 0 -> VK_BLEND_FACTOR_ZERO;
            case 1 -> VK_BLEND_FACTOR_ONE;
            case 0x0300 -> VK_BLEND_FACTOR_SRC_COLOR;
            case 0x0301 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case 0x0302 -> VK_BLEND_FACTOR_SRC_ALPHA;
            case 0x0303 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case 0x0304 -> VK_BLEND_FACTOR_DST_ALPHA;
            case 0x0305 -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case 0x0306 -> VK_BLEND_FACTOR_DST_COLOR;
            case 0x0307 -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case 0x0308 -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case 0x8001 -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case 0x8002 -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case 0x8003 -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case 0x8004 -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            default -> VK_BLEND_FACTOR_ONE;
        };
    }

    public static int blendOpFromGl(int gl) {
        return switch (gl) {
            case 0x800A -> VK_BLEND_OP_SUBTRACT;
            case 0x800B -> VK_BLEND_OP_REVERSE_SUBTRACT;
            case 0x8007 -> VK_BLEND_OP_MIN;
            case 0x8008 -> VK_BLEND_OP_MAX;
            default -> VK_BLEND_OP_ADD;
        };
    }

    public static int compareOpFromGl(int gl) {
        return switch (gl) {
            case 0x0200 -> VK_COMPARE_OP_NEVER;
            case 0x0201 -> VK_COMPARE_OP_LESS;
            case 0x0202 -> VK_COMPARE_OP_EQUAL;
            case 0x0203 -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case 0x0204 -> VK_COMPARE_OP_GREATER;
            case 0x0205 -> VK_COMPARE_OP_NOT_EQUAL;
            case 0x0206 -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            default -> VK_COMPARE_OP_ALWAYS;
        };
    }
}
