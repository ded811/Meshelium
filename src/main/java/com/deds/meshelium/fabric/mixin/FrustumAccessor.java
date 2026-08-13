/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.fabric.mixin;

import net.minecraft.client.renderer.culling.Frustum;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Wave-12 accessor: {@code net.minecraft.client.renderer.culling.Frustum}
 * keeps the culling matrix it was built from in {@code private final
 * org.joml.Matrix4f matrix} (javap-verified; the camera position half of
 * its state is already public via {@code getCamX/Y/Z()}). The
 * {@code meshelium.tune.cachedCull} candidate keys its exact memoization on
 * (snapshot epoch, camera position bits, frustum camera bits, THIS matrix's
 * 16 raw float bits): every {@code Frustum.isVisible} verdict is a pure
 * function of {matrix, camX/Y/Z} and the tested box, so bit-identical key
 * ⇒ bit-identical verdicts — the cache can never serve a stale cull to a
 * changed frustum, only skip recomputing an identical one.
 */
@Mixin(Frustum.class)
public interface FrustumAccessor {

    @Accessor("matrix")
    Matrix4f meshelium$matrix();
}
