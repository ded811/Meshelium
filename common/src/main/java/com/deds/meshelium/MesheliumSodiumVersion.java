/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

/**
 * Which Sodium this build of Meshelium was written against.
 *
 * <h2>Why the fact needs a home in the jar</h2>
 * <p>Until 2026-09-16 it lived in exactly one place, {@code
 * gradle.properties}, as two Modrinth coordinates. A Modrinth coordinate is
 * not the shape a loader reports: Sodium's own {@code fabric.mod.json}
 * says {@code "version": "0.9.2+mc26.3"} (unzip of the shipped jar). So
 * nothing that RAN could answer "is the Sodium in this game the Sodium the
 * adapter was compiled against", and the adapter mixes into Sodium
 * internals that are explicitly not a stable API. The player saw the
 * consequence, never the cause.
 *
 * <p>Since 2026-09-20 the answer comes from {@link MesheliumBuild}, which
 * reads it out of {@code meshelium-build.properties}; the build expands
 * that file from the ONE {@code sodium_base_version} in {@code
 * versions/mc<target>.properties}, the same property the Modrinth
 * coordinate is derived from. "Bump both or neither" is therefore true by
 * construction, where before a configuration-time check compared two
 * hand-kept copies. This class keeps the name every caller already uses.
 *
 * <h2>Loader-free on purpose</h2>
 * <p>It lives in {@code common/}, which by the root build's rule may not
 * name a loader API. It names nothing but {@code java.lang}, so the Sodium
 * adapter, the options screen and the gate can all read it whatever the
 * loader is.
 *
 * <h2>Clean room</h2>
 * <p>Sodium is PolyForm Shield. This file contains version STRINGS and no
 * Sodium code.
 */
public final class MesheliumSodiumVersion {

    /**
     * The Sodium this jar's adapter was compiled and tested against,
     * exactly as a loader reports it: {@code 0.9.2+mc26.3} for a 26.3 jar,
     * {@code 0.9.2+mc26.2} for a 26.2 one.
     */
    public static final String BUILT_AGAINST = MesheliumBuild.SODIUM_BUILT_AGAINST;

    private MesheliumSodiumVersion() {
    }

    /**
     * A reported version with its build metadata removed: everything
     * before the first {@code +}. {@code "0.9.2-beta.1+mc26.2"} becomes
     * {@code "0.9.2-beta.1"}.
     *
     * <p>The {@code +mc26.2} half is the Minecraft version, which the
     * loader has already matched for us, and it is the half most likely to
     * differ harmlessly.
     */
    public static String baseVersion(String reported) {
        if (reported == null) {
            return "";
        }
        int plus = reported.indexOf('+');
        return plus < 0 ? reported : reported.substring(0, plus);
    }

    /** {@link #BUILT_AGAINST} with its build metadata removed. */
    public static String builtAgainstBase() {
        return baseVersion(BUILT_AGAINST);
    }

    /**
     * Is {@code reported} the Sodium this jar was built against?
     *
     * <p><b>The WHOLE version before the {@code +}, character for
     * character, and not a numeric prefix.</b> A first draft of this
     * compared "the numeric prefix only", which reduces both
     * {@code 0.9.2-beta.1} and {@code 0.9.2-beta.11} to {@code 0.9.2} and
     * so answers "same Sodium" for a build eleven betas later, on a
     * pre-release whose internals are explicitly not a stable API. The
     * pre-release tag is the part that moves fastest and the part the
     * adapter's hooks actually ride on, so it is the part that must match.
     * {@code matches("0.9.2-beta.11+mc26.2")} is FALSE against a
     * {@code 0.9.2} build, and the options row and the startup WARN both
     * key on this answer.
     *
     * <p>The one relaxation is {@link MesheliumBuild#SODIUM_ALSO_MATCHING}:
     * versions the build has PROVEN identical to the pinned one (26.2's
     * {@code 0.9.2-beta.1} is 0.9.2 with a different version string, class
     * for class). Those are the same Sodium in every sense the adapter
     * cares about, so they match.
     *
     * <p>An empty or null report is NOT a match: "we could not tell" must
     * never render as "yes, that is the one".
     */
    public static boolean matches(String reported) {
        String base = baseVersion(reported);
        return !base.isEmpty()
                && (base.equals(builtAgainstBase()) || MesheliumBuild.SODIUM_ALSO_MATCHING.contains(base));
    }
}
