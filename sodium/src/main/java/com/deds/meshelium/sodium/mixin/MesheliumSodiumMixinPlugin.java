/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.sodium.mixin;

import com.deds.meshelium.sodium.MesheliumSodiumHooks;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Keeps the Sodium mixins from loading when Sodium is not installed.
 *
 * <p>Every mixin in this config targets a Sodium class. Without Sodium
 * those classes do not exist, and a mixin whose target is missing is an
 * error rather than a no-op - so the config has to be able to say "not
 * today" before Mixin tries to resolve anything. That is what a config
 * plugin is for, and it is the same mechanism Sodium itself uses.
 *
 * <h2>It asks the class loader, not a loader API (2026-09-14)</h2>
 * <p>Until 2026-09-14 this class asked {@code FabricLoader.isModLoaded},
 * which made it the one file in {@code sodium/} that could not compile
 * on NeoForge and so kept the whole adapter Fabric-only. The owner's
 * question that day was whether the adapter could be built into the
 * NeoForge jar at all; the answer was yes, and this was one of exactly
 * three loader-bound lines in the adapter. It now asks its own class
 * loader whether Sodium's {@code RenderSectionManager.class} is a
 * visible resource - which is the question actually being asked, "can
 * the mixins below see their targets" - and needs no loader API on
 * either side.
 *
 * <p>Why not a loader's mod list: Mixin plugins run at the first class
 * transform, which on NeoForge is before {@code Main.main} starts, and
 * {@code ModList.get()} is null there (it is created inside
 * {@code ModLoader.gatherAndInitializeMods}, from
 * {@code ClientModLoader.begin}). On both loaders it is also before
 * {@code MesheliumPlatform} is installed, so the usual seam does not
 * exist yet either. FML's {@code LoadingModList} would answer at plugin
 * time, but only by reflection from a shared source set, and it answers
 * "is there a mod FILE with id sodium" rather than "are the classes
 * visible".
 *
 * <p>Why the resource check is right on NeoForge (FML 11.0.16, javap):
 * this class is defined by FML's transforming loader -
 * {@code ModuleClassLoader.loadClass} consults its package lookup and
 * defines through the module reader before it asks any parent - and its
 * {@code getResource} enumerates that package lookup, then every
 * module's reader, and only when that enumeration is EMPTY falls back to
 * the application class loader. Sodium's nested mod jar is a MOD-type
 * file in the same game layer, so the resource resolves when Sodium is
 * installed and not otherwise; {@code .class} resources are exempt from
 * module encapsulation, so no export is needed. That fallback is also
 * the reason {@code neoforge/build.gradle} keeps the nested Sodium jar
 * {@code compileOnly} and nothing else: on the JVM classpath it would
 * make this check answer "present" for a Sodium that FML never loaded
 * as a mod. On NeoForge {@code requiredMods = ["sodium"]} in the
 * mods.toml keeps the config from registering at all without Sodium, so
 * this check is the second line there and the only line on Fabric,
 * where Knot sees every mod jar flat and {@code fabric.mod.json} lists
 * the config unconditionally.
 *
 * <h2>The kill switch</h2>
 * <p>With Sodium present the adapter is ON, and
 * {@code -Dmeshelium.sodium.adapter=false} turns it off. It was opt-in
 * while the render path was a pass-through that changed nothing; it now
 * draws Sodium's opaque terrain with mesh shaders, which is the point of
 * installing both mods, so making a player pass a JVM flag to get it would
 * be the wrong default.
 *
 * <p>Defaulting ON is only defensible because the failure mode is benign.
 * Meshelium reads Sodium's buffers and writes none of Sodium's state, so
 * every path that declines - no mesh-shader device, a pass it does not
 * handle, an exception - hands the frame back to Sodium's own renderer
 * intact. The kill switch exists for the case that is NOT covered by that:
 * a draw that succeeds and paints the wrong pixels.
 *
 * <p>The declined decision reaches {@code latest.log} through the gate
 * ({@code MesheliumGate}'s "adapter is not running" WARN prints the
 * property's value on both loaders); the {@code System.out} line in
 * {@link #onLoad} is a console-only witness. ModDevGradle's log4j
 * configuration routes loggers, not stdout, to the log file, and this
 * runs at the first class transform, before any mod could redirect
 * stdout - so grepping the log for it is wrong and not a failure.
 *
 * <h2>The hook check (D-021)</h2>
 * <p>The per-region run cache is correct only while all five of Sodium's
 * record-mutation sites are hooked, and those hooks are {@code require =
 * 0} so that a Sodium update which renames one costs a slower frame, not
 * a crash. Mixin's own response to a missing target under
 * {@code required: false} is a warning in the log and a mixin applied
 * without that injector - nothing the renderer could see. So
 * {@link #preApply} looks each target up by name and descriptor in the
 * class node, before the mixin is applied, and records the answer in
 * {@link MesheliumSodiumHooks}; the draw list arms the cache only when all
 * five were found. The plugin never touches {@code SodiumTerrainDrawer}
 * or anything else that names a Blaze3D type: this runs during class
 * transformation, where loading such a class early is a fault charged to
 * some other mod's mixin.
 *
 * <h2>The two config-loader mixins</h2>
 * <p>The render-distance overlay (D-019) is registered through Sodium's
 * per-loader config loader: {@code ConfigLoaderFabricMixin} on Fabric,
 * {@code ConfigLoaderForgeMixin} on NeoForge. Both are listed in the one
 * JSON and each is admitted only where its own target class is a
 * visible resource, keyed the same way as the presence check. That is
 * safe because Mixin 0.8.7 asks {@link #shouldApplyMixin} per declared
 * target BEFORE it resolves the target class
 * ({@code MixinInfo.readDeclaredTargets}; the resolve is
 * {@code readTargetClasses(List)} on the survivors only), and a mixin
 * left with no targets is dropped by {@code MixinConfig.prepareMixins}
 * without a log line; the "@Mixin target was not found" error lives on
 * the resolve step, which a declined target never reaches (D-026).
 */
public final class MesheliumSodiumMixinPlugin implements IMixinConfigPlugin {

    /** Kept in step with {@code MesheliumGate.SODIUM_MOD_ID}. */
    private static final String SODIUM_MOD_ID = "sodium";

    /**
     * Set to {@code false} to keep Meshelium out of Sodium's renderer
     * entirely. Any other value, including unset, arms the adapter.
     */
    public static final String ADAPTER_PROPERTY = "meshelium.sodium.adapter";

    /**
     * The class whose visibility means "Sodium is here": the manager
     * every render mixin in this config targets.
     */
    private static final String SODIUM_PRESENCE_CLASS =
            "net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.class";

    /** {@code ConfigLoaderFabricMixin}'s target, present in the Fabric jar only. */
    private static final String CONFIG_LOADER_FABRIC_CLASS =
            "net/caffeinemc/mods/sodium/fabric/config/ConfigLoaderFabric.class";

    /** {@code ConfigLoaderForgeMixin}'s target, present in the NeoForge nested jar only. */
    private static final String CONFIG_LOADER_FORGE_CLASS =
            "net/caffeinemc/mods/sodium/neoforge/config/ConfigLoaderForge.class";

    private boolean apply;

    /** Sodium present at all, kill switch or not: what the config-overlay mixins follow. */
    private boolean sodiumPresent;

    @Override
    public void onLoad(String mixinPackage) {
        boolean sodium = resourcePresent(SODIUM_PRESENCE_CLASS);
        // Opt-OUT, not opt-in: only the literal string "false" declines.
        // Boolean.getBoolean would have inverted that, treating every
        // typo and every unset property as a refusal.
        boolean declined = "false".equalsIgnoreCase(System.getProperty(ADAPTER_PROPERTY));
        this.sodiumPresent = sodium;
        this.apply = sodium && !declined;
        if (sodium && declined) {
            // Worth a line either way: with the adapter off, "Sodium is
            // drawing everything" and "Meshelium is broken" look identical
            // from the outside. Console only - see the class javadoc.
            System.out.println("[Meshelium] Sodium (mod id '" + SODIUM_MOD_ID + "') is installed and -D"
                    + ADAPTER_PROPERTY + "=false is set, so Meshelium is not touching Sodium's "
                    + "renderer. Sodium draws the terrain on its own.");
        }
    }

    /**
     * Whether a {@code .class} resource is visible to this plugin's own
     * class loader. Any failure to ask counts as absent: a wrong "no"
     * leaves Sodium drawing alone, a wrong "yes" would apply mixins to
     * classes that are not there.
     */
    private static boolean resourcePresent(String resource) {
        try {
            ClassLoader loader = MesheliumSodiumMixinPlugin.class.getClassLoader();
            return loader != null && loader.getResource(resource) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Once-only: the search-distance invoker's target is absent. */
    private static boolean searchDistanceMissingReported;

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName != null && mixinClassName.endsWith(".ConfigLoaderFabricMixin")) {
            // The render-distance overlay (D-019) has nothing to do with who
            // draws the terrain: it stops Sodium's own screen resetting a
            // widened value to 12, and that reset happens whether or not
            // the adapter is declined. So this mixin follows Sodium's
            // presence alone, exactly as the manifest entrypoint it
            // replaced did. Found by the declined form of the stand-down
            // suite the day the registration moved (2026-09-08). Keyed
            // on its own target's presence too, because the Fabric jar
            // has ConfigLoaderFabric and the NeoForge one does not.
            return this.sodiumPresent && resourcePresent(CONFIG_LOADER_FABRIC_CLASS);
        }
        if (mixinClassName != null && mixinClassName.endsWith(".ConfigLoaderForgeMixin")) {
            // The NeoForge twin of the same registration (2026-09-14):
            // same D-019 reasoning, same presence-only rule, its own
            // target, which exists only in Sodium's NeoForge jar.
            return this.sodiumPresent && resourcePresent(CONFIG_LOADER_FORGE_CLASS);
        }
        if (!this.apply) {
            return false;
        }
        if (mixinClassName != null && mixinClassName.endsWith(".RenderSectionManagerInvoker")) {
            // An @Invoker has no require = 0: a missing target is an apply
            // error, which would take the whole config down with it. So
            // the target is looked up in the class bytes before the mixin
            // is admitted, and an absent method costs a wider distance gate
            // (contract section 6.3), never a crash.
            return searchDistancePresent(targetClassName);
        }
        return true;
    }

    /**
     * Read the manager's class node through Mixin's own bytecode provider
     * (no class loading) and look for {@code getSearchDistance(FogParameters)}.
     * Any failure to read counts as absent.
     *
     * <p>What "no class loading" means on each loader, recorded so nobody
     * "optimises" this into a hard throw (2026-09-14, FML 11.0.16 javap).
     * On NeoForge the provider is {@code FMLClassBytecodeProvider}: the
     * one-argument {@code getClassNode(name)} used here is
     * {@code getClassNode(name, true, 0)}, which reads through
     * {@code TransformingClassLoader.buildTransformedClassNodeFor} to
     * {@code ModuleClassLoader.getMaybeTransformedClassBytes} - a
     * package-lookup read of the bytes with the processors that run
     * BEFORE the mixin processor applied (access transformer, dist
     * cleaner), no {@code defineClass}, no class-tracker entry, and no
     * re-entry into Mixin's own apply. The two-argument form with
     * {@code false} THROWS {@code IllegalArgumentException} ("FML service
     * does not currently support retrieval of untransformed bytecode"),
     * so the one-argument call is the only one that works on both
     * loaders; Fabric's provider reads the bytes through Knot the same
     * way. Do not change the call.</p>
     */
    private static boolean searchDistancePresent(String targetClassName) {
        if (MesheliumSodiumHooks.searchDistanceFound()) {
            return true;
        }
        try {
            ClassNode node = org.spongepowered.asm.service.MixinService.getService()
                    .getBytecodeProvider().getClassNode(targetClassName);
            if (node != null) {
                for (MethodNode method : node.methods) {
                    if (MesheliumSodiumHooks.SEARCH_DISTANCE_NAME.equals(method.name)
                            && MesheliumSodiumHooks.SEARCH_DISTANCE_DESC.equals(method.desc)) {
                        MesheliumSodiumHooks.markFound(MesheliumSodiumHooks.HOOK_SEARCH_DISTANCE);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            // Fall through: absent.
        }
        if (!searchDistanceMissingReported) {
            searchDistanceMissingReported = true;
            System.out.println("[Meshelium] Sodium's RenderSectionManager has no method "
                    + MesheliumSodiumHooks.SEARCH_DISTANCE_TARGET + ", so the GPU-visibility "
                    + "distance gate widens to the render distance instead of Sodium's fog "
                    + "search distance. A Sodium update moved it; the rendering draws more, "
                    + "never fewer.");
        }
        return false;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
        // Matched on the node's own internal name rather than the string
        // parameter, whose form (dotted or slashed) is Mixin's business.
        // The node is Sodium's class before our mixin touches it, so a
        // method found here is one the injector will find too.
        String name = targetClass.name;
        if (MesheliumSodiumHooks.RENDER_REGION.equals(name)) {
            check(targetClass, MesheliumSodiumHooks.REMOVE_SECTION_NAME,
                    MesheliumSodiumHooks.REMOVE_SECTION_DESC,
                    MesheliumSodiumHooks.HOOK_REMOVE_SECTION);
            check(targetClass, MesheliumSodiumHooks.BUFFER_CHANGE_NAME,
                    MesheliumSodiumHooks.BUFFER_CHANGE_DESC,
                    MesheliumSodiumHooks.HOOK_BUFFER_CHANGE);
            check(targetClass, MesheliumSodiumHooks.SEGMENT_CHANGE_NAME,
                    MesheliumSodiumHooks.SEGMENT_CHANGE_DESC,
                    MesheliumSodiumHooks.HOOK_SEGMENT_CHANGE);
            check(targetClass, MesheliumSodiumHooks.DELETE_NAME,
                    MesheliumSodiumHooks.DELETE_DESC,
                    MesheliumSodiumHooks.HOOK_DELETE);
        } else if (MesheliumSodiumHooks.RENDER_REGION_MANAGER.equals(name)) {
            check(targetClass, MesheliumSodiumHooks.UPLOAD_NAME,
                    MesheliumSodiumHooks.UPLOAD_DESC,
                    MesheliumSodiumHooks.HOOK_UPLOAD);
        } else if (MesheliumSodiumHooks.VK_INDIRECT_CONTEXT.equals(name)) {
            // The ONLY instruction-level check in this plugin, and it has to
            // be: a @ModifyConstant whose constant has moved matches nothing,
            // and an injection point that matches nothing throws
            // InjectionError - which extends Error, so neither Mixin's
            // apply-time catch nor "required": false softens it. Checking
            // that <init> merely EXISTS would prove nothing, because <init>
            // survives every rename.
            checkConstant(targetClass, "<init>", "()V",
                    MesheliumSodiumHooks.INDIRECT_RING_STOCK_BYTES,
                    MesheliumSodiumHooks.HOOK_INDIRECT_RING);
        } else if (MesheliumSodiumHooks.CHUNK_RENDER_LIST.equals(name)) {
            // A FIELD, not a method, and the only one this plugin checks.
            // The accessor that reads it is the one seam we ship that is
            // not a whole-method HEAD/TAIL, so it gets the same treatment
            // the five record hooks get: verified in the node Mixin hands
            // us, before the mixin is applied, and used only when found.
            checkField(targetClass, MesheliumSodiumHooks.GEOMETRY_MAP_NAME,
                    MesheliumSodiumHooks.GEOMETRY_MAP_DESC,
                    MesheliumSodiumHooks.HOOK_GEOMETRY_MAP);
        } else if (MesheliumSodiumHooks.RENDER_SECTION_MANAGER.equals(name)) {
            // Belt and braces for the invoker: shouldApplyMixin already
            // decided from the same bytes; this marks from the node Mixin
            // hands us in case that path was never consulted. Quiet: the
            // loud line lives in searchDistancePresent.
            for (MethodNode method : targetClass.methods) {
                if (MesheliumSodiumHooks.SEARCH_DISTANCE_NAME.equals(method.name)
                        && MesheliumSodiumHooks.SEARCH_DISTANCE_DESC.equals(method.desc)) {
                    MesheliumSodiumHooks.markFound(MesheliumSodiumHooks.HOOK_SEARCH_DISTANCE);
                    break;
                }
            }
        }
    }

    /** Record one hook target as present, or say exactly which one is not. */
    private static void check(ClassNode targetClass, String name, String desc, int bit) {
        for (MethodNode method : targetClass.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                MesheliumSodiumHooks.markFound(bit);
                return;
            }
        }
        // System.out, like onLoad: this runs before any logger is safe to
        // touch, and it must be loud, because from the outside a cache
        // that never armed and a cache that works are the same picture.
        System.out.println("[Meshelium] Sodium's " + targetClass.name + " has no method "
                + name + desc + ", so Meshelium's per-region run cache stays off and the "
                + "per-section enumeration draws every pass. A Sodium update moved it; the "
                + "rendering is unaffected, only slower at distance.");
    }

    /**
     * Record one field target as present, or say exactly which one is not.
     *
     * <p>The method twin of this ({@link #check}) exists because a missing
     * injection target is silent; a missing ACCESSOR target is worse than
     * silent, because the interface is then unimplemented on the target
     * class and the first call throws {@code AbstractMethodError} mid
     * frame. Neither outcome is acceptable, so the answer is recorded and
     * the call site asks first.
     */
    private static void checkField(ClassNode targetClass, String name, String desc, int bit) {
        for (FieldNode field : targetClass.fields) {
            if (name.equals(field.name) && desc.equals(field.desc)) {
                MesheliumSodiumHooks.markFound(bit);
                return;
            }
        }
        // System.out for the same reason as check(): this runs during
        // class transformation, before a logger is safe to touch.
        System.out.println("[Meshelium] Sodium's " + targetClass.name + " has no field "
                + name + " " + desc + ", so Meshelium rebuilds the per-region geometry bitmap "
                + "from the section iterator instead of reading Sodium's own. A Sodium update "
                + "moved it; the rendering is unaffected, only slightly slower at distance.");
    }

    /**
     * Record that one method really does load one int literal, or say so.
     *
     * <p>512,000 is far above {@code Short.MAX_VALUE}, so javac emits it as
     * an {@code LDC} of an {@code Integer} rather than as {@code SIPUSH} or
     * {@code BIPUSH}; this looks for exactly that. A narrower literal would
     * need the push opcodes checked too.
     */
    private static void checkConstant(ClassNode targetClass, String name, String desc,
            int literal, int bit) {
        for (MethodNode method : targetClass.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)
                    || method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value
                        && value.intValue() == literal) {
                    MesheliumSodiumHooks.markFound(bit);
                    return;
                }
            }
        }
        // System.out, like the other checks: this runs during class
        // transformation, before a logger is safe to touch.
        System.out.println("[Meshelium] Sodium's " + targetClass.name + " no longer loads the "
                + "literal " + literal + " in " + name + desc + ", so its indirect-command ring "
                + "keeps Sodium's own size. A Sodium update moved it. If that update did not also "
                + "fix the ring's grow-and-copy, a long session at a high render distance can "
                + "still stall for five seconds inside the terrain pass.");
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
    }
}
