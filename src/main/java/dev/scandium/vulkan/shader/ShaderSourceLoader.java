package dev.scandium.vulkan.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a core shader the way the game sees it (resource packs included): the JSON program definition and the
 * vertex/fragment sources with every {@code #moj_import} expanded. Must run where the resource manager may be
 * used (render thread after a reload); the result is plain text, safe to hand to another thread.
 */
public final class ShaderSourceLoader {
    private static final Pattern IMPORT = Pattern.compile("^\\s*#moj_import\\s+([<\"])(.+?)[>\"]\\s*$", Pattern.MULTILINE);

    public record CoreShader(String name, String vertexSource, String fragmentSource, List<String> samplers) {
    }

    private ShaderSourceLoader() {
    }

    public static CoreShader load(ResourceProvider resources, String shaderName) throws IOException {
        ResourceLocation id = new ResourceLocation(shaderName);
        String base = "shaders/core/";
        JsonObject json;
        try (Reader reader = open(resources, new ResourceLocation(id.getNamespace(), base + id.getPath() + ".json"))) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }
        String vertexName = json.get("vertex").getAsString();
        String fragmentName = json.get("fragment").getAsString();
        List<String> samplers = new ArrayList<>();
        JsonArray samplerArray = json.has("samplers") ? json.getAsJsonArray("samplers") : new JsonArray();
        for (JsonElement element : samplerArray) {
            samplers.add(element.getAsJsonObject().get("name").getAsString());
        }
        String vertex = readProgram(resources, vertexName, ".vsh", id.getNamespace());
        String fragment = readProgram(resources, fragmentName, ".fsh", id.getNamespace());
        return new CoreShader(shaderName, vertex, fragment, samplers);
    }

    private static String readProgram(ResourceProvider resources, String program, String extension, String defaultNamespace)
            throws IOException {
        ResourceLocation id = program.contains(":") ? new ResourceLocation(program)
                : new ResourceLocation(defaultNamespace, program);
        ResourceLocation file = new ResourceLocation(id.getNamespace(), "shaders/core/" + id.getPath() + extension);
        return expand(resources, read(resources, file), file, new HashSet<>());
    }

    /** {@code <x>} resolves to shaders/include/x, {@code "x"} relative to the including file. */
    private static String expand(ResourceProvider resources, String source, ResourceLocation file, Set<ResourceLocation> seen)
            throws IOException {
        Matcher m = IMPORT.matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String target = m.group(2);
            ResourceLocation included;
            if (m.group(1).equals("<")) {
                ResourceLocation ref = target.contains(":") ? new ResourceLocation(target)
                        : new ResourceLocation(file.getNamespace(), target);
                included = new ResourceLocation(ref.getNamespace(), "shaders/include/" + ref.getPath());
            } else {
                String dir = file.getPath().substring(0, file.getPath().lastIndexOf('/') + 1);
                included = new ResourceLocation(file.getNamespace(), dir + target);
            }
            String replacement = "";
            if (seen.add(included)) { // each file once, like vanilla's preprocessor
                replacement = expand(resources, read(resources, included), included, seen)
                        .replaceAll("(?m)^\\s*#version.*$", "");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String read(ResourceProvider resources, ResourceLocation location) throws IOException {
        try (Reader reader = open(resources, location)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) > 0) {
                text.append(buffer, 0, n);
            }
            return text.toString();
        }
    }

    private static Reader open(ResourceProvider resources, ResourceLocation location) throws IOException {
        Optional<Resource> resource = resources.getResource(location);
        if (resource.isEmpty()) {
            throw new IOException("Missing shader resource " + location);
        }
        return resource.get().openAsReader();
    }
}
