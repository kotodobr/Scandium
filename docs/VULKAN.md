# Scandium Vulkan backend

Experimental, opt-in (`"vulkanBackend": true` in `config/scandium.json`). Written from scratch for Scandium:
no code from VulkanMod (LGPL-3.0) or other Vulkan Minecraft projects is used or adapted, so Scandium stays MIT.

## Why it has to be a full backend

Minecraft's window is created with an OpenGL context, and a GLFW window that owns a GL context cannot get a
Vulkan surface (`VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`). Drawing only part of a frame with Vulkan would need GL/Vulkan
interop (`GL_EXT_memory_object` + semaphores) every frame, which costs more than it saves. So Vulkan takes over
presentation only once it can draw *everything* - until then all rendering stays on OpenGL.

## Stages

| # | Stage | Status |
|---|---|---|
| 1 | **Core**: instance, GPU selection (Vulkan 1.2, graphics+compute queue, discrete preferred), logical device, buffers, command submission, runtime GLSL→SPIR-V (shaderc), headless self-test (compute + offscreen graphics with read-back) | done |
| 2 | **Resources**: memory sub-allocator (64 MB blocks per memory type, buffers and optimal images in separate blocks, dedicated allocations for large requests), staging belt, textures with blit-generated mip chains, sampler cache, growing descriptor pools, pipeline cache persisted to `scandium/vulkan-pipeline-cache.bin`; self-test samples an uploaded texture through a descriptor set | done |
| 3 | **Shader translation**: core shaders (`#version 150`, loose uniforms, `#moj_import`) → Vulkan GLSL 450 (one std140 uniform block with computed offsets, sampler bindings, attribute locations from the vertex format, varyings matched by name, `gl_VertexID`→`gl_VertexIndex`, z remapped to Vulkan's 0..1 depth). Vertex formats → Vulkan attributes (normalized colour/normal, integer UVs as SINT or SSCALED depending on the shader type). Fixed-function state parsed from `RenderType.toString()` (blend, depth, cull, write masks, polygon offset). All game shaders are rebuilt as pipelines after every resource reload: 64/64 on vanilla 1.20.4 (1.0 s cold, 0.4 s with the pipeline cache) | done |
| 4 | **Render state layer** (shadow mode, `vulkanShadowRender`): GL state tracked through `GlStateManager` hooks, GL textures mirrored (levels, filters, unpack state, RGBA/RGB/BGRA/RG/R), every `VertexBuffer` upload mirrored (dynamic data in a 64 MB per-frame ring, static buffers owned), every shader draw into the main target replayed with translated shader, std140 uniforms from `ShaderInstance`, descriptor-bound textures, pipelines keyed by shader/format/state, negative-height viewport. `/scandium vkshot` saves the Vulkan frame next to a GL screenshot; sky, sun/moon, stars, entities, particles, hand, GUI and text match | done |
| 4b | Mirror the remaining paths: secondary render targets (Fabulous, entity outlines, post chains), `BufferUploader.draw` without a shader | next |
| 5 | **Window + swapchain** (`vulkanPresent`): visible GLFW window with `GLFW_NO_API` + Vulkan surface/swapchain (BGRA8, FIFO with VSync, MAILBOX/IMMEDIATE without), GL in a hidden 64x64 window's context; `glfwSwapBuffers` replaced by a blit of the Vulkan frame; resize/minimize handled; GL picture read back as fallback (first load, renderer failure, `/scandium present opengl`); normal GL window if anything fails at startup. Verified in game: the window shows the Vulkan frame | done |
| 7 | **GL off** (`vulkanSkipGl`): while the window shows the Vulkan frame, GL skips main-target draws, dynamic vertex uploads, terrain uploads/draws and the invisible blitToScreen; switching back to the GL picture rebuilds terrain. Profiling fixes on the way: terrain in device-local memory (was host memory read over PCIe every frame: 78 -> 398 FPS), cached sampler locations in `ShaderInstance.apply` (was ~70% of render-thread native time; also helps plain GL), IMMEDIATE instead of MAILBOX without VSync (MAILBOX capped at ~400 FPS): 512 FPS | done |
| 8 | **Throughput**: GL side of `ShaderInstance.apply` (program, texture units, uniform uploads) skipped while GL is off; opaque terrain in device-local arenas per region and layer, drawn with one `vkCmdDrawIndexedIndirect` per region (`multiDrawIndirect`), translucent and vanilla-path sections still per section. Vulkan path now ~43% faster than OpenGL in the same scene | done |
| 6 | **Terrain** (shadow mode): section uploads mirrored from both paths (region batching: region-relative vertices; vanilla: section-relative), sorted translucent indices and re-sorts included, freed on unload/move/empty layer/world change; drawn per visible section in vanilla order (translucent back to front) with a dynamic uniform offset carrying each section's ChunkOffset. The Vulkan frame (terrain, water, fog, clouds, entities, hand, GUI) matches the GL screenshot. Next: region arenas + `vkCmdDrawIndexedIndirect` instead of one draw per section | done |
| 7 | **Compatibility**: post-processing chains (`PostChain`), Fabric API rendering events, fallback to OpenGL on any init failure | |

Stages 2-4 can be developed and tested offscreen while the game keeps rendering with OpenGL; stage 5 is the
switch-over point.

## Measurements (RTX 5070 Ti, 854x480, F3 closed, same spot, `/scandium bench`)

| Mode | avg | 1% low | 0.1% low | worst frame |
|---|---|---|---|---|
| OpenGL (Scandium, sampler cache) | 826 | 220 | 52 | 115 ms (GC pause) |
| Vulkan presents, GL draws off (stage 7) | 690 | 225 | 81 | 29 ms |
| + GL side of `ShaderInstance.apply` skipped, terrain arenas + indirect draw per region (stage 8) | **1184** | **343** | **161** | **12 ms** |

## Requirements

- Vulkan 1.2 driver (Windows/Linux). macOS uses the bundled MoltenVK (not tested yet).
- `vulkanValidation: true` enables `VK_LAYER_KHRONOS_validation` if the Vulkan SDK is installed.
