package com.deds.meshelium.neoforge.mixin;

import com.deds.meshelium.neoforge.EarlyWindowBypass;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import net.neoforged.fml.loading.EarlyLoadingScreenController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops Minecraft from adopting NeoForge's early loading screen window
 * when the game is starting on Vulkan.
 *
 * <p>NeoForge patches {@code createGlfwWindow} to ask
 * {@code EarlyLoadingScreenController.current()} whether an early window
 * already exists. When one does, the game takes it over instead of
 * creating its own — and that window has an OpenGL context, which Vulkan
 * cannot draw to. The result is GLFW error 65540 during startup, behind a
 * dialog that wrongly blames the player's graphics drivers. NeoForge issue
 * #3230; the fix, PR #3259, is still unmerged.
 *
 * <p>This redirect answers that question with null on Vulkan, which sends
 * the game down the branch it already takes for players who never had an
 * early screen: it creates its own window, with the hints the Vulkan
 * backend set a few instructions earlier. On OpenGL the answer is left
 * exactly as NeoForge gave it, so those players keep their loading screen.
 *
 * <p>This is the only place the swap can be made. It is the main thread,
 * the backend is already known, the hints are already set, and the window
 * does not exist yet. See {@link EarlyWindowBypass} for the two earlier
 * approaches that failed and why.
 *
 * <p>{@code require = 0} on purpose: if a future NeoForge build stops
 * asking that question, or asks it somewhere else, this shim quietly does
 * not apply. Meshelium then behaves as it did before — the crash on the
 * first launch, and a config already rewritten so the second one works.
 * Refusing to load the whole mod over a compatibility shim would be the
 * wrong trade.
 */
@Mixin(Window.class)
public abstract class WindowEarlyDisplayMixin {

    @Redirect(
            method = "createGlfwWindow",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/EarlyLoadingScreenController;"
                            + "current()Lnet/neoforged/fml/loading/EarlyLoadingScreenController;"),
            require = 0)
    private static EarlyLoadingScreenController meshelium$skipEarlyWindowOnVulkan(
            int width, int height, String title, long monitor, GpuBackend backend) {
        EarlyLoadingScreenController early = EarlyLoadingScreenController.current();
        if (early == null || !(backend instanceof VulkanBackend)) {
            return early;
        }
        // dismiss() changes nothing unless it can finish the job, so a
        // false here means the game is in the state NeoForge left it in.
        return EarlyWindowBypass.dismiss(early) ? null : early;
    }
}
