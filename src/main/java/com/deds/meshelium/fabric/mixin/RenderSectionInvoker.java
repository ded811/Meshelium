/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import net.minecraft.client.renderer.chunk.CompiledSectionMesh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code checkSectionMesh}, the last thing vanilla's upload path does.
 *
 * <p>The upload seam has to reproduce vanilla's bookkeeping exactly when it
 * cancels the upload, and this is the one private step it cannot reach
 * otherwise. Idempotent by inspection - it returns early unless every
 * buffer is flagged uploaded, and again if the mesh is already installed -
 * so calling it and then falling through to vanilla is safe in both
 * directions.</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public interface RenderSectionInvoker {

    @Invoker("checkSectionMesh")
    void meshelium$checkSectionMesh(CompiledSectionMesh mesh);
}
