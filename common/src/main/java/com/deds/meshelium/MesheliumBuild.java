/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Which Minecraft this jar was built for, and which Sodium it was written
 * against - the two facts the build knows and the code used to guess at.
 *
 * <h2>Where the answers come from</h2>
 * <p>{@code meshelium-build.properties} at the root of the jar. The build
 * expands it from {@code versions/mc<target>.properties} (root
 * {@code build.gradle}, {@code mesheliumBuildExpansion}), so a jar built
 * with {@code -Pmc=26.2} says 26.2 here and a jar built for 26.3 says 26.3,
 * from one source tree. See {@code versions/README.md}.
 *
 * <p>A jar without the file is a broken build rather than a jar for some
 * default version: the static initialiser throws, and the first thing to
 * read these fields is the backend gate, which logs the failure and stays
 * off. "We could not tell" must never read as "26.2".
 *
 * <h2>Loader-free on purpose</h2>
 * <p>Lives in {@code common/}, which by the root build's rule may not name
 * a loader API. {@code java.base} only.
 */
public final class MesheliumBuild {

    /** The resource the build expands; a jar without it fails to boot the gate. */
    public static final String RESOURCE = "/meshelium-build.properties";

    /** The Minecraft version this jar was built for, e.g. {@code "26.3"}. */
    public static final String MINECRAFT_TARGET;

    /**
     * The Sodium this jar's adapter was compiled and tested against, exactly
     * as a loader reports it, e.g. {@code "0.9.2+mc26.3"}.
     */
    public static final String SODIUM_BUILT_AGAINST;

    /**
     * Sodium base versions (the part before {@code +}) proven identical to
     * {@link #SODIUM_BUILT_AGAINST} and therefore accepted as the same
     * Sodium. Empty unless {@code versions/mc<target>.properties} says
     * otherwise; for 26.2 it holds {@code 0.9.2-beta.1}, whose class files
     * are byte-for-byte those of 0.9.2.
     */
    public static final Set<String> SODIUM_ALSO_MATCHING;

    /**
     * The {@code -XX:StackShadowPages} this Minecraft's launch profile
     * passes (32 for 26.3), or 0 when it passes none and the launch check
     * is off (26.2). From {@code launch_shadow_pages} in
     * {@code versions/mc<target>.properties}.
     */
    public static final int LAUNCH_SHADOW_PAGES;

    static {
        Properties props = new Properties();
        try (InputStream in = MesheliumBuild.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Meshelium jar has no " + RESOURCE
                        + "; the build did not expand it, so this jar cannot say which "
                        + "Minecraft it is for");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Meshelium could not read " + RESOURCE, e);
        }
        MINECRAFT_TARGET = required(props, "minecraft_target");
        SODIUM_BUILT_AGAINST = required(props, "sodium_built_against");
        Set<String> also = new LinkedHashSet<>();
        for (String s : props.getProperty("sodium_also_matching", "").split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                also.add(t);
            }
        }
        SODIUM_ALSO_MATCHING = Set.copyOf(also);
        String shadow = props.getProperty("launch_shadow_pages", "0").trim();
        if (shadow.startsWith("${")) {
            throw new IllegalStateException("Meshelium jar's " + RESOURCE
                    + " was not expanded (launch_shadow_pages=" + shadow + ")");
        }
        LAUNCH_SHADOW_PAGES = shadow.isEmpty() ? 0 : Integer.parseInt(shadow);
    }

    private MesheliumBuild() {
    }

    private static String required(Properties props, String key) {
        String v = props.getProperty(key, "").trim();
        if (v.isEmpty() || v.startsWith("${")) {
            throw new IllegalStateException(RESOURCE + " has no expanded value for " + key
                    + " (got '" + v + "'); the build's processResources did not run over it. "
                    + "Known keys: " + Arrays.toString(props.stringPropertyNames().toArray()));
        }
        return v;
    }
}
