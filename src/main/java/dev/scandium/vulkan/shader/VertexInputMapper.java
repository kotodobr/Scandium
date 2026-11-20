package dev.scandium.vulkan.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.scandium.vulkan.VulkanContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Converts a Minecraft {@link VertexFormat} into Vulkan vertex attributes, mirroring how vanilla sets up the GL
 * attribute pointers (VertexFormatElement.Usage):
 * <ul>
 *     <li>COLOR and NORMAL are normalized (UNORM/SNORM), everything else is not</li>
 *     <li>non-float UV elements (overlay UV1, lightmap UV2) are integer attributes in GL (glVertexAttribIPointer).
 *     Some shaders still declare them as {@code vec2}; Vulkan requires the format class to match the shader type,
 *     so those use SSCALED/USCALED (integer in memory, float in the shader) instead of SINT/UINT</li>
 *     <li>PADDING elements produce no attribute</li>
 * </ul>
 * 3-component 8/16-bit formats aren't universally supported for vertex buffers; when missing, the 4-component
 * format is used (it reads into the following padding byte(s), which the shader ignores).
 */
public final class VertexInputMapper {
    public record Attribute(int location, int format, int offset, String name) {
    }

    public record Layout(int stride, List<Attribute> attributes) {
    }

    private VertexInputMapper() {
    }

    /** @param shaderInputs vertex shader inputs (name -> GLSL type) from the translator */
    public static Layout map(VulkanContext context, VertexFormat format, Map<String, String> shaderInputs) {
        List<String> names = format.getElementAttributeNames();
        List<VertexFormatElement> elements = format.getElements();
        List<Attribute> attributes = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < elements.size(); i++) {
            VertexFormatElement element = elements.get(i);
            String name = names.get(i);
            String shaderType = shaderInputs.get(name);
            if (element.getUsage() != VertexFormatElement.Usage.PADDING && shaderType != null) {
                int vkFormat = vkFormat(context, element, shaderType);
                attributes.add(new Attribute(i, vkFormat, offset, name));
            }
            offset += element.getByteSize();
        }
        return new Layout(format.getVertexSize(), attributes);
    }

    private static int vkFormat(VulkanContext context, VertexFormatElement element, String shaderType) {
        VertexFormatElement.Usage usage = element.getUsage();
        boolean normalized = usage == VertexFormatElement.Usage.COLOR || usage == VertexFormatElement.Usage.NORMAL;
        boolean shaderInt = shaderType.startsWith("i") || shaderType.startsWith("u");
        int count = element.getCount();
        Kind kind;
        if (element.getType() == VertexFormatElement.Type.FLOAT) {
            kind = Kind.FLOAT;
        } else if (normalized) {
            kind = Kind.NORM;
        } else if (shaderInt) {
            kind = Kind.INT;
        } else {
            kind = Kind.SCALED;
        }
        int vk = lookup(element.getType(), kind, count);
        if (vk == VK_FORMAT_UNDEFINED) {
            throw new ShaderTranslationException("No Vulkan vertex format for " + element + " as " + shaderType);
        }
        if (count == 3 && element.getType() != VertexFormatElement.Type.FLOAT && !context.supportsVertexFormat(vk)) {
            vk = lookup(element.getType(), kind, 4);
        }
        return vk;
    }

    private enum Kind { FLOAT, NORM, INT, SCALED }

    private static int lookup(VertexFormatElement.Type type, Kind kind, int count) {
        int index = Math.max(0, Math.min(4, count) - 1);
        int[] table = switch (type) {
            case FLOAT -> new int[]{VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32_SFLOAT,
                    VK_FORMAT_R32G32B32A32_SFLOAT};
            case UBYTE -> switch (kind) {
                case NORM -> new int[]{VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8_UNORM, VK_FORMAT_R8G8B8A8_UNORM};
                case INT -> new int[]{VK_FORMAT_R8_UINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8B8_UINT, VK_FORMAT_R8G8B8A8_UINT};
                default -> new int[]{VK_FORMAT_R8_USCALED, VK_FORMAT_R8G8_USCALED, VK_FORMAT_R8G8B8_USCALED,
                        VK_FORMAT_R8G8B8A8_USCALED};
            };
            case BYTE -> switch (kind) {
                case NORM -> new int[]{VK_FORMAT_R8_SNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8B8_SNORM, VK_FORMAT_R8G8B8A8_SNORM};
                case INT -> new int[]{VK_FORMAT_R8_SINT, VK_FORMAT_R8G8_SINT, VK_FORMAT_R8G8B8_SINT, VK_FORMAT_R8G8B8A8_SINT};
                default -> new int[]{VK_FORMAT_R8_SSCALED, VK_FORMAT_R8G8_SSCALED, VK_FORMAT_R8G8B8_SSCALED,
                        VK_FORMAT_R8G8B8A8_SSCALED};
            };
            case USHORT -> switch (kind) {
                case NORM -> new int[]{VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16B16_UNORM,
                        VK_FORMAT_R16G16B16A16_UNORM};
                case INT -> new int[]{VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16B16_UINT,
                        VK_FORMAT_R16G16B16A16_UINT};
                default -> new int[]{VK_FORMAT_R16_USCALED, VK_FORMAT_R16G16_USCALED, VK_FORMAT_R16G16B16_USCALED,
                        VK_FORMAT_R16G16B16A16_USCALED};
            };
            case SHORT -> switch (kind) {
                case NORM -> new int[]{VK_FORMAT_R16_SNORM, VK_FORMAT_R16G16_SNORM, VK_FORMAT_R16G16B16_SNORM,
                        VK_FORMAT_R16G16B16A16_SNORM};
                case INT -> new int[]{VK_FORMAT_R16_SINT, VK_FORMAT_R16G16_SINT, VK_FORMAT_R16G16B16_SINT,
                        VK_FORMAT_R16G16B16A16_SINT};
                default -> new int[]{VK_FORMAT_R16_SSCALED, VK_FORMAT_R16G16_SSCALED, VK_FORMAT_R16G16B16_SSCALED,
                        VK_FORMAT_R16G16B16A16_SSCALED};
            };
            case UINT -> new int[]{VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32_UINT,
                    VK_FORMAT_R32G32B32A32_UINT};
            case INT -> new int[]{VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32_SINT,
                    VK_FORMAT_R32G32B32A32_SINT};
        };
        return table[index];
    }
}
