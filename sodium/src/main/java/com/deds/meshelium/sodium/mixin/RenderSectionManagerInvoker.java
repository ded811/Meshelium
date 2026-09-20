/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches Sodium's private {@code RenderSectionManager.getSearchDistance(FogParameters)}
 * for the GPU-visibility distance gate (D-023, contract section 6.3).
 *
 * <h2>Why an invoker and not a reimplementation</h2>
 * <p>D-010's rule: call Sodium's own code, never copy a formula whose
 * answer decides which sections are drawn. The search distance is
 * {@code useFogOcclusion ? min(renderDistance * 16, fog.cullDistance() + 0.5)
 * : renderDistance * 16} with a fog-alpha condition folded in (javap,
 * contract section 1.4); three of those inputs are Sodium's own state and
 * reading them from outside would pin three more internals. The invoker
 * pins one private method by name and descriptor, and the plugin checks
 * it exists before this mixin is applied; when it does not, the gate
 * widens to the render distance (draw more, never fewer).
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This interface names one private method
 * of Sodium's and contains no Sodium code.
 */
@Mixin(RenderSectionManager.class)
public interface RenderSectionManagerInvoker {

    @Invoker("getSearchDistance")
    float meshelium$getSearchDistance(FogParameters fog);
}
