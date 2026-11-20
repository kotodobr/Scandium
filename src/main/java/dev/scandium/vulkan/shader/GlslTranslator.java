package dev.scandium.vulkan.shader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a Minecraft core shader pair ({@code #version 150}, OpenGL conventions) into Vulkan GLSL
 * ({@code #version 450}) that shaderc accepts:
 * <ul>
 *     <li>loose {@code uniform} values of both stages are merged into one std140 uniform block at
 *     set 0 / binding 0, declared identically in both stages; its layout is computed so the CPU side can fill
 *     it (see {@link UniformMember})</li>
 *     <li>samplers get bindings 1..n (order: the JSON sampler list, then first appearance)</li>
 *     <li>vertex inputs get {@code location} = index of the attribute in the vertex format, as vanilla binds them
 *     with glBindAttribLocation</li>
 *     <li>varyings get matching locations by name in both stages; fragment outputs get 0..n</li>
 *     <li>{@code gl_VertexID}/{@code gl_InstanceID} become {@code gl_VertexIndex}/{@code gl_InstanceIndex}</li>
 *     <li>depth range: OpenGL clips z to [-w, w], Vulkan to [0, w]; the vertex {@code main} is wrapped and
 *     remaps z after the original code ran. (The Y flip is done with a negative viewport height at draw time.)</li>
 * </ul>
 * Input is expected with {@code #moj_import} already resolved ({@link ShaderSourceLoader}). Pure text
 * processing, no GL or Vulkan involved.
 */
public final class GlslTranslator {
    private static final Pattern UNIFORM = Pattern.compile(
            "^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*(\\[\\s*(\\d+)\\s*])?\\s*;\\s*$", Pattern.MULTILINE);
    private static final Pattern IN_OUT = Pattern.compile(
            "^\\s*((?:flat|smooth|noperspective|centroid)\\s+)?(in|out)\\s+(\\w+)\\s+(\\w+)\\s*(\\[\\s*(\\d+)\\s*])?\\s*;\\s*$",
            Pattern.MULTILINE);
    private static final Pattern VERSION = Pattern.compile("^\\s*#version.*$", Pattern.MULTILINE);
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)");

    public record UniformMember(String name, String type, int arrayLength, int offset, int size) {
    }

    public record SamplerBinding(String name, String type, int binding) {
    }

    public record Result(String vertexSource, String fragmentSource, List<UniformMember> uniforms, int uniformBlockSize,
                         List<SamplerBinding> samplers, Map<String, String> vertexInputs, List<String> warnings) {
        public static final int UNIFORM_BINDING = 0;
    }

    private record Declaration(String qualifier, String direction, String type, String name, int arrayLength) {
    }

    private GlslTranslator() {
    }

    /**
     * @param attributeNames vertex-format attribute names in order (location = index)
     * @param samplerOrder   sampler names from the shader JSON, may be empty
     */
    public static Result translate(String name, String vertex, String fragment, List<String> attributeNames,
                                   List<String> samplerOrder) {
        List<String> warnings = new ArrayList<>();
        vertex = stripVersion(vertex);
        fragment = stripVersion(fragment);

        // ---- uniforms of both stages
        Map<String, Declaration> uniforms = new LinkedHashMap<>();
        Map<String, Declaration> samplers = new LinkedHashMap<>();
        collectUniforms(vertex, uniforms, samplers, name, warnings);
        collectUniforms(fragment, uniforms, samplers, name, warnings);
        vertex = UNIFORM.matcher(vertex).replaceAll("");
        fragment = UNIFORM.matcher(fragment).replaceAll("");

        List<UniformMember> members = new ArrayList<>();
        int offset = 0;
        for (Declaration d : uniforms.values()) {
            Std140 layout = Std140.of(d.type, d.arrayLength);
            if (layout == null) {
                throw new ShaderTranslationException(name + ": unsupported uniform type " + d.type + " " + d.name);
            }
            offset = align(offset, layout.alignment);
            members.add(new UniformMember(d.name, d.type, d.arrayLength, offset, layout.size));
            offset += layout.size;
        }
        int blockSize = align(offset, 16);

        List<SamplerBinding> samplerBindings = new ArrayList<>();
        int binding = 1;
        for (String samplerName : samplerOrder) {
            Declaration d = samplers.remove(samplerName);
            if (d != null) {
                samplerBindings.add(new SamplerBinding(d.name, d.type, binding++));
            }
        }
        for (Declaration d : samplers.values()) {
            samplerBindings.add(new SamplerBinding(d.name, d.type, binding++));
        }

        StringBuilder resources = new StringBuilder();
        if (!members.isEmpty()) {
            resources.append("layout(std140, set = 0, binding = ").append(Result.UNIFORM_BINDING)
                    .append(") uniform ScandiumUniforms {\n");
            for (Declaration d : uniforms.values()) {
                resources.append("    ").append(d.type).append(' ').append(d.name)
                        .append(d.arrayLength > 0 ? "[" + d.arrayLength + "]" : "").append(";\n");
            }
            resources.append("};\n");
        }
        for (SamplerBinding s : samplerBindings) {
            resources.append("layout(set = 0, binding = ").append(s.binding).append(") uniform ")
                    .append(s.type).append(' ').append(s.name).append(";\n");
        }

        // ---- vertex stage: inputs and varyings
        Map<String, String> vertexInputs = new LinkedHashMap<>();
        Map<String, Integer> varyingLocations = new LinkedHashMap<>();
        int[] nextVarying = {0};
        StringBuilder vertexOut = new StringBuilder();
        Matcher m = IN_OUT.matcher(vertex);
        while (m.find()) {
            Declaration d = declaration(m);
            String replacement;
            if (d.direction.equals("in")) {
                int location = attributeNames.indexOf(d.name);
                if (location < 0) {
                    warnings.add(name + ": vertex input " + d.name + " is not in the vertex format");
                    location = attributeNames.size() + vertexInputs.size();
                }
                vertexInputs.put(d.name, d.type);
                replacement = "layout(location = " + location + ") in " + d.type + ' ' + d.name + ";";
            } else {
                int location = nextVarying[0];
                varyingLocations.put(d.name, location);
                nextVarying[0] += locationsUsed(d.type, d.arrayLength);
                replacement = "layout(location = " + location + ") " + qualifierText(d) + "out " + d.type + ' ' + d.name
                        + arraySuffix(d) + ";";
            }
            m.appendReplacement(vertexOut, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(vertexOut);

        // ---- fragment stage: varyings by name, outputs 0..n
        StringBuilder fragmentOut = new StringBuilder();
        int nextOutput = 0;
        m = IN_OUT.matcher(fragment);
        while (m.find()) {
            Declaration d = declaration(m);
            String replacement;
            if (d.direction.equals("in")) {
                Integer location = varyingLocations.get(d.name);
                if (location == null) {
                    warnings.add(name + ": fragment input " + d.name + " is not written by the vertex shader");
                    location = nextVarying[0];
                    nextVarying[0] += locationsUsed(d.type, d.arrayLength);
                }
                replacement = "layout(location = " + location + ") " + qualifierText(d) + "in " + d.type + ' ' + d.name
                        + arraySuffix(d) + ";";
            } else {
                replacement = "layout(location = " + nextOutput++ + ") out " + d.type + ' ' + d.name + arraySuffix(d) + ";";
            }
            m.appendReplacement(fragmentOut, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(fragmentOut);

        String vertexBody = vertexOut.toString()
                .replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex")
                .replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex");
        Matcher main = MAIN.matcher(vertexBody);
        if (!main.find()) {
            throw new ShaderTranslationException(name + ": vertex shader has no main()");
        }
        vertexBody = main.replaceFirst("void scandium_main()")
                + "\nvoid main() {\n    scandium_main();\n"
                + "    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";

        String header = "#version 450\n// Translated by Scandium from Minecraft shader '" + name + "'\n";
        String vertexSource = header + resources + vertexBody;
        String fragmentSource = header + resources + fragmentOut;
        return new Result(vertexSource, fragmentSource, members, blockSize, samplerBindings, vertexInputs, warnings);
    }

    private static void collectUniforms(String source, Map<String, Declaration> uniforms,
                                        Map<String, Declaration> samplers, String name, List<String> warnings) {
        Matcher m = UNIFORM.matcher(source);
        while (m.find()) {
            String type = m.group(1);
            String uniformName = m.group(2);
            int arrayLength = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            Declaration d = new Declaration(null, "uniform", type, uniformName, arrayLength);
            Map<String, Declaration> target = type.startsWith("sampler") || type.startsWith("isampler")
                    || type.startsWith("usampler") ? samplers : uniforms;
            Declaration existing = target.putIfAbsent(uniformName, d);
            if (existing != null && !existing.type.equals(type)) {
                throw new ShaderTranslationException(name + ": uniform " + uniformName + " is declared as "
                        + existing.type + " and " + type);
            }
        }
    }

    private static Declaration declaration(Matcher m) {
        String qualifier = m.group(1) == null ? null : m.group(1).trim();
        int arrayLength = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
        return new Declaration(qualifier, m.group(2), m.group(3), m.group(4), arrayLength);
    }

    private static String qualifierText(Declaration d) {
        return d.qualifier == null ? "" : d.qualifier + ' ';
    }

    private static String arraySuffix(Declaration d) {
        return d.arrayLength > 0 ? "[" + d.arrayLength + "]" : "";
    }

    /** Interface locations: one per vector, one per matrix column. */
    private static int locationsUsed(String type, int arrayLength) {
        int perElement = switch (type) {
            case "mat2" -> 2;
            case "mat3" -> 3;
            case "mat4" -> 4;
            default -> 1;
        };
        return perElement * Math.max(1, arrayLength);
    }

    private static String stripVersion(String source) {
        return VERSION.matcher(source).replaceAll("");
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    /** std140 base alignment and size of the types Minecraft shaders use. */
    private record Std140(int alignment, int size) {
        static Std140 of(String type, int arrayLength) {
            Std140 base = switch (type) {
                case "float", "int", "uint", "bool" -> new Std140(4, 4);
                case "vec2", "ivec2", "uvec2" -> new Std140(8, 8);
                case "vec3", "ivec3", "uvec3" -> new Std140(16, 12);
                case "vec4", "ivec4", "uvec4" -> new Std140(16, 16);
                case "mat2" -> new Std140(16, 32);
                case "mat3" -> new Std140(16, 48);
                case "mat4" -> new Std140(16, 64);
                default -> null;
            };
            if (base == null || arrayLength <= 0) {
                return base;
            }
            // Array elements are padded to 16 bytes each.
            int stride = align(base.size, 16);
            return new Std140(16, stride * arrayLength);
        }
    }
}
