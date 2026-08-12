package com.deds.meshelium.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Thin runtime GLSL→SPIR-V wrapper over LWJGL's shaderc bindings — the
 * road vanilla cannot offer: {@code GlslCompiler}'s {@code ShaderType} is
 * VERTEX/FRAGMENT only (seam doc Q5), so Meshelium drives shaderc directly
 * for task/mesh stages.
 *
 * <p>Recipe per docs/VANILLA-FRAME-PATH.md Q5.2: compiler + options with
 * {@code set_target_env(vulkan, env_version_vulkan_1_2)} (0x402000 — same
 * value vanilla's {@code GlslCompiler.<init>} passes; vulkan_1_2 implies
 * SPIR-V 1.5 ≥ the 1.4 mesh shaders need, so no extra
 * {@code set_target_spirv} call), then {@code shaderc_compile_into_spv}
 * with kinds {@code shaderc_task_shader = 26} / {@code shaderc_mesh_shader
 * = 27} / {@code shaderc_fragment_shader = 1} (all javap -constants
 * verified against lwjgl-shaderc 3.4.1). None of vanilla's preprocessor
 * options (auto-bind, auto-map, moj_import) are wanted: Meshelium's shaders
 * carry explicit {@code layout(set, binding)} qualifiers.</p>
 *
 * <p>Failures throw {@link ShaderCompileException} carrying shaderc's full
 * error text; callers decide whether that disables a feature or fails a
 * boot. This class never logs and never swallows.</p>
 */
public final class MesheliumShaderCompiler {

    /** shaderc kind for a mesh stage — javap -constants, lwjgl-shaderc 3.4.1. */
    public static final int KIND_MESH = Shaderc.shaderc_mesh_shader;
    /** shaderc kind for a task stage (unused until wave 5, verified now). */
    public static final int KIND_TASK = Shaderc.shaderc_task_shader;
    /** shaderc kind for a fragment stage. */
    public static final int KIND_FRAGMENT = Shaderc.shaderc_fragment_shader;

    /** Compile (or resource-load) failure with the full shaderc log attached. */
    public static final class ShaderCompileException extends RuntimeException {
        public ShaderCompileException(String resourcePath, String detail) {
            super("Failed to compile '" + resourcePath + "': " + detail);
        }

        public ShaderCompileException(String resourcePath, String detail, Throwable cause) {
            super("Failed to compile '" + resourcePath + "': " + detail, cause);
        }
    }

    private MesheliumShaderCompiler() {
    }

    /**
     * Loads Vulkan GLSL from the mod's resources, compiles it to SPIR-V and
     * wraps it in a {@code VkShaderModule}.
     *
     * @param device       the live device ({@code VulkanDevice.vkDevice()})
     * @param resourcePath absolute classpath resource, e.g.
     *                     {@code "/assets/meshelium/shaders/hello.mesh"}
     * @param shadercKind  one of the {@code KIND_*} constants
     * @return the {@code VkShaderModule} handle (caller owns destruction)
     */
    public static long compileResourceToModule(VkDevice device, String resourcePath, int shadercKind) {
        return compileResourceToModule(device, resourcePath, shadercKind, java.util.Map.of());
    }

    /**
     * Same, with host-injected preprocessor macros — the Nvidium
     * ShaderLoader-define pattern (NVIDIUM-ARCHITECTURE.md §4/§8) carried
     * over: wave 4 injects {@code MESHELIUM_WG_SIZE}, wave 9 retunes it per
     * vendor by recompiling (shaders are runtime-compiled anyway, so a
     * macro gives the same tunability as a SPIR-V specialization constant
     * without relying on {@code LocalSizeId} support in the shipped
     * shaderc/driver pair — see the wave-4 notes in
     * docs/VANILLA-FRAME-PATH.md).
     *
     * @param macros name → value, passed to
     *               {@code shaderc_compile_options_add_macro_definition}
     *               (javap-verified CharSequence overload, lwjgl-shaderc 3.4.1)
     */
    public static long compileResourceToModule(VkDevice device, String resourcePath, int shadercKind,
            java.util.Map<String, String> macros) {
        String source = loadResource(resourcePath);

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new ShaderCompileException(resourcePath, "shaderc_compiler_initialize returned NULL");
        }
        long options = 0L;
        long result = 0L;
        try {
            options = Shaderc.shaderc_compile_options_initialize();
            if (options == 0L) {
                throw new ShaderCompileException(resourcePath, "shaderc_compile_options_initialize returned NULL");
            }
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            for (java.util.Map.Entry<String, String> macro : macros.entrySet()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options,
                        macro.getKey(), macro.getValue());
            }

            result = Shaderc.shaderc_compile_into_spv(compiler, source, shadercKind,
                    resourcePath, "main", options);
            if (result == 0L) {
                throw new ShaderCompileException(resourcePath, "shaderc_compile_into_spv returned NULL");
            }
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                // The full shaderc error text — the one debugging artefact
                // that matters when a driver's glslang rejects EXT mesh GLSL.
                throw new ShaderCompileException(resourcePath, "shaderc status " + status + ":\n"
                        + Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
            if (spirv == null) {
                throw new ShaderCompileException(resourcePath, "successful result carried no SPIR-V bytes");
            }
            // The module must be created while `result` (which owns the
            // SPIR-V memory) is still alive; vkCreateShaderModule copies.
            return createModule(device, resourcePath, spirv);
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            if (options != 0L) {
                Shaderc.shaderc_compile_options_release(options);
            }
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static long createModule(VkDevice device, String resourcePath, ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);
            LongBuffer module = stack.mallocLong(1);
            int vkResult = VK10.vkCreateShaderModule(device, createInfo, null, module);
            if (vkResult != VK10.VK_SUCCESS) {
                throw new ShaderCompileException(resourcePath, "vkCreateShaderModule failed: " + vkResult);
            }
            return module.get(0);
        }
    }

    private static String loadResource(String resourcePath) {
        try (InputStream in = MesheliumShaderCompiler.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new ShaderCompileException(resourcePath, "resource not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ShaderCompileException(resourcePath, "I/O error reading resource", e);
        }
    }
}
