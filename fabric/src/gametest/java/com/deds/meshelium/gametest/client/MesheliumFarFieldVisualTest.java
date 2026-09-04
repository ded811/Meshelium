/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * The pre5 instrument (docs/FARFIELD-WAVES.md): the far field,
 * photographed in a world that HAS oceans, forests and cliffs in it.
 * Runs only under -Pmeshelium.farvisual, so the normal suite never
 * pays for it.
 */
package com.deds.meshelium.gametest.client;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.farfield.FarField;
import com.deds.meshelium.farfield.FarFieldConfig;
import com.deds.meshelium.farfield.extract.ExtractDispatch;
import com.deds.meshelium.farfield.extract.ShellExtractor;
import com.deds.meshelium.terrain.host.TerrainResidency;
import com.deds.meshelium.vk.TerrainDrawer;

import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A DIAGNOSTIC, not an acceptance test. Its product is nine PNGs and a
 * pile of {@code FARVISUAL} log lines for a human to read; its
 * assertions exist only to stop a run producing pictures that LOOK like
 * evidence and are not.
 *
 * <h2>Why this class exists at all</h2>
 * <p>{@link MesheliumFarFieldTest} runs in the harness SUPERFLAT: seed
 * 1, one biome, one block, one grass colour, no water, no cliffs, no
 * caves, and a horizon that is a straight line. Every appearance defect
 * the owner has reported since pre1 is invisible in that world BY
 * CONSTRUCTION: superflat has no ocean to be gray, no leaf to be the
 * wrong green and no cliff to be holed. So every colour and shape claim
 * this project has made was argued from javap output and PNG assets
 * rather than from a rendered pixel, and the same defects survived fix
 * after fix (docs/FARFIELD-WAVES.md, pre4 playtest; the same shape of
 * blind spot the sibling repo's superflat merge tests had). This class
 * is the instrument that closes that gap.</p>
 *
 * <p>The live list it was built to photograph, from the pre4 playtest:
 * <b>F1</b> gaps on the sides of cliffs, <b>F2</b> gaps at caves ("the
 * main one"), <b>F3</b> the ring at the loading edge, <b>F4</b> random
 * missing chunks, <b>F5</b> grass and leaf colour still wrong and
 * oceans reading as two colours that do not work together. Behind them
 * the older ones the same pictures also answer: E1 plants as cubes, E2
 * gray oceans, E5 the one-chunk ring, E6 hollowed chunks, E7 the bare
 * hole under a player who flies up.</p>
 *
 * <h2>The leg, once per scene</h2>
 * <ol>
 *   <li>A REAL {@code minecraft:normal} noise world at a fixed seed
 *       (the benchmark's pattern - the gametest default world is a
 *       superflat and would photograph nothing).</li>
 *   <li>The scene SELF-LOCATES: {@code findClosestBiome3d} against the
 *       server's own biome source with exactly the parameters
 *       {@code /locate biome} uses (a radius, 32 horizontal steps, 64
 *       vertical). Hard-coding a coordinate found once for one seed
 *       would silently photograph the wrong biome on any other.</li>
 *   <li>Teleport there, SPECTATOR (no gravity, so a camera pinned 45
 *       blocks up survives the whole scene), world frozen with the 26.2
 *       gamerule names: {@code minecraft:advance_time},
 *       {@code advance_weather}, {@code spawn_mobs},
 *       {@code random_tick_speed}. The old camelCase names PARSE-FAIL
 *       SILENTLY and the freeze then simply never happens, which over
 *       an ocean means kelp random-ticks forever and the world never
 *       settles.</li>
 *   <li>Near render distance {@value #NEAR_RD}, far radius
 *       {@value #FAR_L1}: everything past 128 blocks and inside about
 *       512 can only be on screen because the far field put it
 *       there.</li>
 *   <li>{@value #HOPS} teleports of {@value #HOP_BLOCKS} blocks in a
 *       straight line, settling between each, so the terrain that was
 *       under the camera is abandoned, extracted, written to the store,
 *       and becomes far terrain.</li>
 *   <li>Turn around. Photograph the SAME pose twice, far field ON then
 *       OFF, and once more looking down from {@value #DOWN_CLIMB}
 *       blocks higher.</li>
 * </ol>
 *
 * <h2>The pose is the point</h2>
 * <p>The look-back camera sits 30 to 45 blocks above the local surface
 * with a shallow downward pitch. That is chosen so ONE frame carries
 * the whole comparison: real near-field terrain across the bottom
 * third, the far field across the middle, sky above. A colour defect is
 * then a visible SEAM inside a single picture and needs no second image
 * to judge - if the far grass is a different green from the near grass,
 * the line where they meet says so.</p>
 *
 * <h2>What the assertions can and cannot do</h2>
 * <p>They are deliberately few, and each one exists to kill a specific
 * way a run could produce misleading pictures:</p>
 * <ul>
 *   <li>Far sections resident AND admitted, admissions measured as a
 *       DELTA inside each scene - a cumulative counter would let scene
 *       one's work pass scene three.</li>
 *   <li>The far store must actually receive a write during the walk,
 *       so a scene whose terrain was never cached fails at the walk
 *       rather than later, with the extract/write/drop counters in the
 *       message.</li>
 *   <li>The ON and OFF frames of a scene must differ by at least two
 *       percent of their pixels. Two identical PNGs are exactly what
 *       "the far field rendered nothing" looks like, and a coordinator
 *       reading them side by side would reasonably conclude "no bug
 *       here".</li>
 * </ul>
 * <p>Everything COLOUR is for the human eye, on purpose. No assertion in
 * this file can tell a wrong green from a right one, and one that
 * pretended to would be worse than none.</p>
 *
 * <h2>The one exception: the fly-up ladder is an ACCEPTANCE leg (S6)</h2>
 * <p>{@link #flyUpLadder} does not photograph a judgement call. It climbs
 * a mixed scene with occlusion pinned off and asserts a SET DIFFERENCE:
 * resident, in-frustum, in-disc sections at or below the camera and past
 * the local horizon — vanilla's 7x7 adjacency core included, since
 * pre21 — minus everything vanilla lists and everything our masks draw,
 * must be EMPTY. That difference is a hole by construction, not an
 * opinion about a pixel, so it belongs in an assertion — and its absence
 * for five releases is why the fly-up defect shipped five times. It runs
 * twice: with the far field out of the frame, and (pre21) with it armed
 * over a primed store, which is the owner's own scene and adds a far-side
 * verdict at the collapsed handover band. The sentence above about this
 * file asserting nothing was true when it was written and is no longer;
 * do not quote it at a future wave. See the constant block above
 * {@link #LADDER} for the design.</p>
 *
 * <h2>Load-order discipline</h2>
 * <p>This class is LAST in the entrypoint list, after
 * {@link MesheliumFarFieldTest}, whose zero-cost-off leg asserts that
 * {@code FarFieldResidency} was never LOADED in a session that never
 * enabled the far field. Every reference to that class here therefore
 * lives inside {@link FarWalkerProbe}, a nested class of its own, for
 * the reason spelled out on {@code MesheliumFarFieldTest}'s probe of
 * the same name: a separate class file is not loaded until something
 * touches it, so this test class merely being CONSTRUCTED cannot be
 * what breaks that invariant.</p>
 *
 * <h2>The missing-column CENSUS (pre6, and the real deliverable)</h2>
 * <p>Three defects survive: whole chunks entirely missing from the far
 * field, a ring around the player that can have missing chunks, and
 * chunks that flash clear before loading in. All three are the same
 * question at different radii - <i>why is THIS column not drawn?</i> -
 * and every previous round of work answered it by INFERENCE from
 * aggregate counters. Inference has now been wrong three times running.
 * Two sessions ago the store turned out never to have been opened, and
 * an instrument found that in ninety seconds after three rounds of
 * reasoning had missed it.</p>
 *
 * <p>So the census does not infer. It walks every column of the
 * walker's own wanted square and asks
 * {@code FarFieldResidency.classifyColumn} for a verdict, whose test
 * order mirrors the promotion scan's own skip chain. Every verdict gets
 * a count, every non-drawn verdict gets a handful of concrete
 * coordinates, and one {@code CENSUS-ANSWER} line states how many
 * columns that should be drawn are not, and the single biggest reason.
 * It runs TWICE per scene:</p>
 * <ul>
 *   <li><b>arrival</b>, seconds after the camera reaches the pose. This
 *       is what a travelling player sees.</li>
 *   <li><b>settled</b>, after the chunk stream and the build pipeline
 *       have both gone quiet. This is the best the far field will ever
 *       do at that pose.</li>
 * </ul>
 * <p>The difference between the two is the whole diagnosis. Bad at
 * arrival and clean when settled is a TIMING defect - the flash. Still
 * bad when settled is a TERMINAL defect - the missing chunks. Nothing
 * else in the suite separates those two.</p>
 *
 * <h2>The boustrophedon SURVEY, and why it is opt-in</h2>
 * <p>The default walk is a straight 384-block corridor, which populates
 * a CORRIDOR. Judging "the ring around the player has missing chunks"
 * from a look-down shot over a corridor is not possible: most of the
 * ring was never travelled, so most of the ring is legitimately empty
 * and the picture shows a defect that is really the walk's shape. The
 * survey walks lanes back and forth instead, populating a genuine AREA,
 * and then puts the camera in the MIDDLE of it, so every direction of
 * the ring has cached terrain behind it and a hole in the ring is a
 * real hole.</p>
 *
 * <p>It is opt-in because it costs minutes per scene:
 * {@value #SURVEY_LANES} lanes of {@value #SURVEY_LANE_STEPS} steps,
 * each stop settling the chunk stream, so budget two to five minutes a
 * surveyed scene on top of the ordinary run. The far radius drops to
 * {@value #SURVEY_L1} for a surveyed scene, which is not a softening of
 * the test but the only honest choice: the surveyed rectangle's
 * half-extent is what bounds the radius at which the ring can be full,
 * and asking for a ring wider than the area that was walked would fill
 * the census with ABSENT columns that mean nothing but "we never went
 * there".</p>
 *
 * <h2>The handover (flash) clock, and leg B's continuity oracle</h2>
 * <p>Measured, not inferred. Since seam steps 3-4 BOTH walk modes
 * sample every tick (the corridor's settles are per-tick now), which
 * feeds two consumers: the flash DISTRIBUTION (still reported only
 * under the survey, whose uniform cadence keeps the percentiles
 * honest) and the continuity oracle (both modes), which fails the
 * scene if a far-domain column that was DRAWN - BOUND (live, retained,
 * or far; the invariant's own set) or walker-resident or near-owned -
 * ever becomes undrawn while interior
 * to the domain. Every survey tick classifies the whole wanted square; a
 * column whose verdict goes from {@code NEAR_COVERED} to anything else
 * has just left vanilla's compile disc, and the clock runs from there
 * until the column reports resident. The distribution is reported in
 * FRAMES (from the drawer's own free-running counter, so the endpoints
 * are exact) and in ticks. Two honesty notes travel with it: detection
 * is sample-quantised, so a gap can be over-reported by up to one tick
 * at each end; and columns that never became resident at all are
 * reported SEPARATELY as censored rather than dropped, because a median
 * taken over only the ones that made it is exactly the statistic that
 * would hide the defect.</p>
 *
 * <h2>How to run it</h2>
 * <pre>
 *   ./gradlew runClientGameTest -Pmeshelium.backend=vulkan \
 *       -Pmeshelium.terrain -Pmeshelium.farvisual -Pmeshelium.res=1920x1080
 * </pre>
 * <p>The resolution matters more than it looks: the harness window
 * defaults to 854x480, and a horizon defect two chunks wide is a
 * handful of pixels there.</p>
 *
 * <p>The fly-up ladder runs last and adds five to ten minutes plus
 * {@value #LADDER_RUNGS} PNGs per pass. It sets the render distance and
 * pins occlusion off for its own duration and restores both. Four
 * switches, because it is an acceptance leg that will be tuned again and
 * the three photographic scenes are ~22 minutes of round trip in the
 * way:</p>
 * <pre>
 *   ... -Pmeshelium.vmargs="-Dmeshelium.test.flyup.only=true"   the ladder ALONE
 *   ... -Pmeshelium.vmargs="-Dmeshelium.test.flyup=false"       the scenes alone
 *   ... -Pmeshelium.vmargs="-Dmeshelium.test.flyup.rd=2"        the owner's rd (16 default)
 *   ... -Pmeshelium.vmargs="-Dmeshelium.test.flyup.far=false"   skip the far-armed pass
 * </pre>
 *
 * <p>And the seed, which the ladder's scene locator can genuinely need:
 * {@code -Pmeshelium.seed=<n>} moves the whole run (it reaches
 * {@link #SEED} through {@code meshelium.bench.seed}), or
 * {@code -Pmeshelium.vmargs='-Dmeshelium.farvisual.seed=<n>'} moves this
 * diagnostic alone. There is no {@code -Pmeshelium.farvisual.seed}.</p>
 *
 * <p>With the survey, which also turns on the flash clock. The survey
 * has no dedicated {@code -P} of its own on purpose: adding one means
 * editing {@code build.gradle}, and this change was scoped to two
 * files. The generic passthrough that already exists for exactly this
 * case does the job:</p>
 * <pre>
 *   ./gradlew runClientGameTest -Pmeshelium.backend=vulkan \
 *       -Pmeshelium.terrain -Pmeshelium.farvisual -Pmeshelium.res=1920x1080 \
 *       -Pmeshelium.vmargs="-Dmeshelium.test.farsurvey=true"
 * </pre>
 * <p>Add {@code -Dmeshelium.test.farsurvey.scenes=forest} inside the
 * same quotes to survey one scene and leave the others on the fast
 * corridor walk. If a dedicated property is wanted later, it is three
 * lines beside {@code -Pmeshelium.farvisual} in build.gradle:
 * {@code if (project.hasProperty('meshelium.farsurvey')) { vmArg
 * '-Dmeshelium.test.farsurvey=true' }}.</p>
 */
public final class MesheliumFarFieldVisualTest implements FabricClientGameTest {

    /** {@code -Pmeshelium.farvisual}. Without it this class does nothing. */
    private static final boolean ARMED = Boolean.getBoolean("meshelium.test.farvisual");

    /**
     * {@code -Dmeshelium.test.farsurvey=true}: walk lanes back and forth
     * to populate an AREA instead of a corridor, and run the per-tick
     * handover clock. Opt-in; see the class javadoc for the invocation
     * and for why it has no dedicated {@code -P}.
     */
    private static final boolean SURVEY = Boolean.getBoolean("meshelium.test.farsurvey");

    /**
     * {@code -Dmeshelium.test.farsurvey.scenes=forest,cliff}: restrict
     * the survey to named scenes, so one long walk can be taken over the
     * biome the owner is actually complaining about while the others
     * keep the fast corridor. Empty (the default) surveys every scene.
     */
    private static final String SURVEY_SCENES =
            System.getProperty("meshelium.test.farsurvey.scenes", "").trim();

    /**
     * Fixed, so the coordinator can re-shoot the same three scenes after
     * a fix and compare like with like. Falls back to the benchmark's
     * seed property so {@code -Pmeshelium.seed} moves this run too - the
     * whole point of a biome-sensitive diagnostic is being able to take
     * it on more than one world.
     */
    private static final String SEED = System.getProperty("meshelium.farvisual.seed",
            System.getProperty("meshelium.bench.seed", "4242"));

    /**
     * How to actually change the seed, spelled the way the command line
     * takes it. Verified against build.gradle this session rather than
     * assumed: {@code -Pmeshelium.seed=<n>} becomes
     * {@code -Dmeshelium.bench.seed} (build.gradle:127-128), which
     * {@link #SEED} falls back to and which
     * {@code TestWorldBuilder.adjustSettings -> setSeed} then applies; and
     * {@code -Dmeshelium.farvisual.seed} is only reachable through the
     * generic passthrough at build.gradle:292-293. <b>There is no
     * {@code -Pmeshelium.farvisual.seed}</b>, and three failure messages in
     * this file used to print one - a reader following them would have
     * changed nothing and concluded the seed was not the problem.
     */
    private static final String SEED_HINT =
            "-Pmeshelium.seed=<seed>, or -Pmeshelium.vmargs="
            + "'-Dmeshelium.farvisual.seed=<seed>' to move this diagnostic alone";

    private static final String WORLD_NAME = "meshelium-farvisual";

    /**
     * Near render distance for every scene. Small on purpose: the far
     * field only draws STRICTLY beyond vanilla's horizon, so a small
     * near field is what puts far terrain in the middle of the frame
     * instead of in the last few pixels above the ground.
     */
    private static final int NEAR_RD = 8;

    /**
     * Far radius, chunks. About 512 blocks, four times the near horizon.
     *
     * <p>Not silently clamped away: the walker limits L1 to one chunk
     * inside the projection far plane, which is
     * {@code max(rd * 4, cloudRange)} chunks (Camera.update, cited in
     * FarFieldFogMixin). At rd {@value #NEAR_RD} with vanilla's default
     * cloud range of 128 that ceiling is 127 chunks, so
     * {@value #FAR_L1} passes through untouched.</p>
     */
    private static final int FAR_L1 = 32;

    /** Blocks per teleport while walking the terrain into the far field. */
    private static final int HOP_BLOCKS = 128;
    /**
     * Hops. {@value #HOPS} x {@value #HOP_BLOCKS} = 384 blocks = 24
     * chunks, which lands the scene's origin column squarely inside the
     * far ring {@code [rd+1, L1]} = [9, 32] and comfortably inside the
     * fog wall the far field pushes out to {@code L1 * 16} = 512 (the
     * fog band only begins at about 461, so the origin terrain is
     * photographed clear rather than through haze).
     *
     * <p>rd {@value #NEAR_RD} holds a 17x17 chunk window and each hop is
     * 8 chunks, so the origin column is abandoned - and therefore
     * extracted - by the second hop.</p>
     */
    private static final int HOPS = 3;

    // ------------------------------------------------------------------
    // Survey geometry. Every number here is derived from one fact: the
    // client keeps chunks out to renderDistance + 3 and the extractor
    // only sees a column when the client FORGETS it, so a lane extracts
    // a band about 2 * (rd + 3) = 22 chunks (352 blocks) wide, centred
    // on the lane.
    // ------------------------------------------------------------------

    /** Blocks per survey stop. Three chunks: fine enough that the near/far
     * boundary sweeps across the ring rather than jumping over it, which
     * is what the handover clock needs, and coarse enough that a lane is
     * a manageable number of chunk-stream settles. */
    private static final int SURVEY_STEP_BLOCKS = 48;
    /** Stops per lane: {@value} x 48 = 672 blocks = 42 chunks along. */
    private static final int SURVEY_LANE_STEPS = 14;
    /** Lanes. */
    private static final int SURVEY_LANES = 5;
    /**
     * Blocks between lanes: 10 chunks against a 22-chunk extraction
     * band, so consecutive lanes overlap by more than half. A spacing
     * chosen to merely touch would leave un-extracted stripes that the
     * census would report as ABSENT, and an ABSENT stripe caused by the
     * WALK would be indistinguishable in the log from one caused by the
     * defect.
     */
    private static final int SURVEY_LANE_SPACING = 160;
    /**
     * Far radius for a surveyed scene, chunks.
     *
     * <p>Bounded by the surveyed rectangle, not by taste. The camera
     * finishes in the middle of an area whose half-extents are 21 chunks
     * along the lanes and 20 across them; a ring of {@value} chunks
     * therefore sits two to three chunks inside cached terrain in every
     * direction, including the diagonals. Ask for more and the census
     * fills with ABSENT columns that only mean "nobody walked there".
     * It also frames the down-shot: 18 chunks is 288 blocks, and the
     * down camera covers a horizontal radius of roughly 338.</p>
     */
    private static final int SURVEY_L1 = 18;
    /**
     * Longest a single survey stop will wait for the chunk stream. The
     * stops overlap heavily so the usual answer is the 40-tick floor;
     * this only costs anything at a lane turn.
     */
    private static final int SURVEY_STOP_MAX_TICKS = 400;
    /** Concrete coordinates logged per census reason. Enough to go and look. */
    private static final int SAMPLES_PER_REASON = 12;

    /**
     * Height the camera travels at between stops.
     *
     * <p>Fixed, and above almost all overworld terrain, because the
     * alternative is asking the server for a surface height at a column
     * whose chunk is not loaded yet: {@code Level.getHeight} answers
     * {@code seaLevel + 1} for an unloaded column, so a mountain hop
     * would teleport the camera into solid rock. Nothing about
     * extraction depends on camera height - the client abandons whole
     * chunk COLUMNS - so travelling high costs nothing and removes the
     * question.</p>
     */
    private static final double TRAVEL_Y = 200.0;

    /** Blocks the down-shot climbs above the look-back camera. */
    private static final double DOWN_CLIMB = 150.0;
    /**
     * Down-shot pitch. Not 90: straight down is all ground and no
     * context. At 65 degrees, with vanilla's default 70-degree field of
     * view, the frame spans depression angles 30 to 100, which from
     * about 195 blocks up covers horizontal radius 0 to roughly 338
     * blocks: the near field across the lower part of the frame and the
     * near/far seam as a RING across the upper part. That ring is where
     * E5 (a one-chunk gap between the near edge and the far ring) and
     * E7 (the ground below vanishing on the way up) both live.
     */
    private static final String DOWN_PITCH = "65";

    /** {@code /locate biome}'s own radius, for the two common scenes. */
    private static final int SEARCH_RADIUS = 6400;
    /** Peaks are rarer than oceans; give the cliff twice the reach. */
    private static final int CLIFF_SEARCH_RADIUS = 12800;

    /**
     * Minimum fraction of pixels that must differ between the far-ON and
     * far-OFF frames of a scene: two percent.
     *
     * <p>Turning the far field off does two visible things at once: the
     * far geometry stops drawing, and the fog wall snaps back from
     * {@code L1 * 16} to {@code rd * 16} (FarFieldFogMixin only pushes
     * the fog out while {@code farSectionsResident} is non-zero, so the
     * OFF frame really is vanilla's horizon). Between them that repaints
     * most of everything above the near ground. Two percent is a floor
     * far below what a working far field produces and far above the
     * handful of pixels an animated water texture moves, which is the
     * one thing in a frozen world that still changes between two
     * frames.</p>
     */
    private static final double MIN_PIXEL_DIFF = 0.02;

    private static final int DRAW_TIMEOUT_TICKS = 1200;
    private static final int FAR_TIMEOUT_TICKS = 2400;
    private static final int RD_TIMEOUT_TICKS = 1200;

    /** Every log line this class emits starts with this. */
    private static final String TAG = "FARVISUAL";

    // ------------------------------------------------------------------
    // S6: THE FLY-UP LADDER (docs/FARFIELD-WAVES.md, "FLY-UP ANSWERED")
    //
    // Five fixes to the fly-up hole shipped without one of them ever being
    // reproduced under test. This leg reproduces it, and it is the first
    // thing in this file that ASSERTS rather than photographs, because the
    // defect it looks for is one an assertion can actually see: a resident,
    // on-screen, in-disc section that nothing draws. Everything else here
    // is a colour or a shape and belongs to the human eye.
    //
    // What each piece of the leg is for:
    //   * occlusion FORCED OFF. The hole is on the BFS visibility feed
    //     (drawTaskCulled) and does not exist on the occlusion path, which
    //     never reads visibleSections. AUTO at these render distances
    //     already picks the BFS feed, but "already" is not "provably", and
    //     the previous five waves are what an unpinned assumption costs.
    //   * far field OFF. A far shell drawn over a near hole is a picture
    //     with no hole in it and a defect still in the code.
    //   * a MIXED scene, located by relief rather than by biome name. The
    //     retired p90 arm was DOWN precisely when the disc held both low
    //     ground and high ground; a uniform ocean cannot exercise it, and
    //     S2's verification table was desk-checked on a uniform ocean.
    //   * a LADDER, not a hop. The defect lives in a WINDOW of altitude
    //     (S2's own arithmetic: 64 blocks of climb per section), so a
    //     single 150-block jump can pass straight over it.
    //   * an A/B at four rungs. Without it a green run cannot distinguish
    //     "the fix works" from "this pose never had a hole".
    //
    // pre21 (docs/FARFIELD-WAVES.md, "FLY-UP ANSWERED (seventh attempt:
    // the core)"): the owner reproduced the layer at RENDER DISTANCE 2,
    // WITH the far field on. Four things changed here because of it:
    //   * the expectation set INCLUDES vanilla's 7x7 adjacency core. It was
    //     excluded ("reported, never asserted"), and at rd 2 the whole 5x5
    //     disc is the core, so the leg had expected=0 on every rung there
    //     and the vacuity guard refused the run - correctly, and blindly.
    //   * the frustum gate is per SECTION as well as per region, using the
    //     drawer's own frustum: vanilla's visibleSections is per-section
    //     frustum-filtered, so a section behind the camera in an on-screen
    //     region is not a hole, and at rd 2 most of the disc is behind or
    //     beside the camera.
    //   * the vacuity floor is derived from the render distance (the 64 was
    //     sized for rd 16; at rd 2 the whole disc is 25 columns).
    //   * a SECOND PASS with the far field ARMED, in the owner's own
    //     sequence: play at a larger render distance so the store holds
    //     the surroundings, drop to the ladder's rd, climb. The near-field
    //     assertion is identical (a far shell cannot occupy a live near
    //     position - ADMIT_CONTESTED), and the far side is checked for a
    //     second hole at the collapsed handover band (coverRadius == rd at
    //     rd 2): the inner ring must be fully drawn, the continuity oracle
    //     must stay empty, and the flash clock's invariant-scoped numbers
    //     (boundGaps, censoredBound) must be zero across the A/B nudges,
    //     which are the only horizontal steps a climb has.
    // ------------------------------------------------------------------

    /**
     * {@code -Dmeshelium.test.flyup.only=true} runs the ladder ALONE and
     * skips the ocean, forest and cliff scenes.
     *
     * <p>It exists because the ladder is a leg that will be tuned again.
     * Behind the three scenes one iteration is a ~24-minute round trip of
     * which ~22 minutes are unrelated photography; alone it is a few
     * minutes. Same shape as the {@code farvisual} / {@code farsurvey}
     * switches above, and permanent on purpose - the next person to move a
     * threshold in here should not have to rediscover it.</p>
     *
     * <pre>
     *   ./gradlew runClientGameTest -Pmeshelium.backend=vulkan \
     *       -Pmeshelium.terrain -Pmeshelium.farvisual -Pmeshelium.res=1920x1080 \
     *       -Pmeshelium.vmargs="-Dmeshelium.test.flyup.only=true"
     * </pre>
     */
    private static final boolean LADDER_ONLY =
            Boolean.getBoolean("meshelium.test.flyup.only");

    /**
     * {@code -Dmeshelium.test.flyup=false} switches the ladder off. On by
     * default under {@code -Pmeshelium.farvisual}: an acceptance leg that
     * has to be asked for is an acceptance leg that does not run, which is
     * the whole history of this defect. {@link #LADDER_ONLY} implies it, so
     * the two flags cannot contradict each other into a run that does
     * nothing at all.
     */
    private static final boolean LADDER = LADDER_ONLY
            || !"false".equalsIgnoreCase(System.getProperty("meshelium.test.flyup", "true"));

    /**
     * Near render distance for the ladder, raised from {@value #NEAR_RD}.
     *
     * <p>Chosen from the geometry, not from taste. Vanilla puts a section
     * on its ray-marched arm as soon as any axis exceeds 3 sections
     * ({@code MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE}), so the fraction
     * of the disc that can be refused is {@code 1 - 49 / (pi * rd^2)}: 94%
     * at rd 16, 98% at the owner's rd 32. rd 8 — this class's default — is
     * only 76% and puts the whole ring within 128 blocks of the camera,
     * where it is a handful of pixels. 16 buys the owner's geometry at a
     * quarter of his compile and chunk-generation cost.</p>
     *
     * <p>pre21: overridable with {@code -Dmeshelium.test.flyup.rd}, because
     * the owner reproduces the defect at rd 2 — a geometry where vanilla's
     * 7x7 adjacency core is LARGER than the whole disc and vanilla's BFS is
     * clamped to {@code |dsy| <= 2}, neither of which rd 16 can exercise.
     * The coordinator runs the ladder at BOTH 2 and 16.</p>
     */
    private static final int LADDER_RD = Integer.getInteger("meshelium.test.flyup.rd", 16);

    /**
     * pre21: {@code -Dmeshelium.test.flyup.far=false} skips the far-armed
     * second pass. On by default: the owner's report was made with the far
     * field on, and a leg that only reproduces his scene when asked is
     * the leg this defect has been hiding behind for seven attempts.
     */
    private static final boolean LADDER_FAR =
            !"false".equalsIgnoreCase(System.getProperty("meshelium.test.flyup.far", "true"));

    /**
     * pre21: the render distance the far-armed pass PRIMES the far store
     * at before dropping to {@link #LADDER_RD}. The owner's LOD horizon
     * exists because he has played the area at a larger render distance
     * and the store holds it; a fresh harness world has nothing beyond the
     * loaded square, so the pass loads the scene at this radius with the
     * far field on (live extraction fills the store), then drops to the
     * ladder's radius — which is also the one transition the collapsed
     * handover band has never been through. No-op when it is not larger
     * than {@link #LADDER_RD}.
     */
    private static final int LADDER_FAR_PRIME_RD =
            Integer.getInteger("meshelium.test.flyup.primeRd", 16);

    /** Blocks per rung. The window is measured in sections; this is half of one. */
    private static final int LADDER_STEP = 8;
    /** Rungs. {@value} x 8 = 200 blocks, the whole band the owner flies through. */
    private static final int LADDER_RUNGS = 26;
    /**
     * Pitch bounds, degrees down. The pitch is DERIVED per rung rather
     * than fixed, and that is load-bearing rather than cosmetic: the
     * expectation set counts only sections whose REGION passes the cull
     * frustum, so a pitch that frames the sky at the bottom of the ladder
     * or the camera's own feet at the top would empty the expectation and
     * turn the whole leg vacuous. Each rung aims at the middle of its own
     * ring — {@code atan(lift / (rd * 8))} — which keeps the same band of
     * terrain in frame all the way up. Logged per rung, because a reader
     * comparing two PNGs needs to know the camera moved its aim.
     */
    private static final int LADDER_PITCH_MIN = 5;
    private static final int LADDER_PITCH_MAX = 45;
    /** Concrete section coordinates named in an ABSENT failure message. */
    private static final int LADDER_SAMPLES = 16;
    /**
     * Minimum {@code expected} population for a rung's ABSENT assertion to
     * mean anything, as a FRACTION of the disc rather than a constant. A
     * zero difference over a zero expectation is the vacuous green this
     * project has already shipped once; below the floor the rung is logged
     * and NOT asserted, and the leg fails at the end if too few rungs were
     * assertable.
     *
     * <p>pre21: the old constant, 64, was one fourteenth of the 921
     * columns in vanilla's compile disc at rd 16; at rd 2 the whole disc
     * is 25 columns and 64 could never be met, so the leg refused the
     * owner's setting outright. {@link #ladderMinExpected} scales it as
     * {@code disc / 22} (41 at rd 16, ~150 at rd 32) with a floor of
     * {@value #LADDER_MIN_EXPECTED_FLOOR} sections, which at rd 2 is a
     * sixth of the disc's columns — thin, and the A/B's "absent with the
     * fix off must be positive" is the defense that carries the weight
     * there. Why 22 and not the old fourteenth: the per-section frustum
     * filter (also pre21) removes the sections behind the camera from the
     * expectation, which at rd 16 leaves the five lowest rungs at 43-64
     * sections; a fourteenth (65) would log them and assert none, and the
     * first pre21 run did exactly that (21 of 26 asserted). At 41 every rung
     * of the rd-16 climb is asserted again, as every rung was before.</p>
     */
    private static final int LADDER_MIN_EXPECTED_DIVISOR = 22;
    /** The absolute floor under {@link #ladderMinExpected}. */
    private static final int LADDER_MIN_EXPECTED_FLOOR = 4;
    /** Rungs that must have been assertable, out of {@value #LADDER_RUNGS}. */
    private static final int LADDER_MIN_ASSERTED = 18;
    /** Rungs (index into the ladder) that also run the fix-off A/B. */
    private static final int[] LADDER_AB_RUNGS = {2, 6, 12, 20};
    /** Ticks a rung waits before its ARRIVAL audit. */
    private static final int LADDER_ARRIVE_TICKS = 10;
    /** Ticks a rung waits before its SETTLED audit, on top of the quiesce. */
    private static final int LADDER_SETTLE_TICKS = 30;

    // ------------------------------------------------------------------
    // Relief locator: a COARSE survey of the whole neighbourhood, then a
    // FINE pass around the best candidates.
    //
    // The first cut swept +/-1536 blocks at one resolution and refused on
    // seed 4242 - correctly, and usefully: the leg's thresholds encode the
    // precondition (the disc must be BIMODAL, or a disc-wide percentile
    // cannot lie and there is nothing to reproduce), so the thing to widen
    // is the SEARCH, never the thresholds. getBaseHeight is a noise query
    // that generates no chunk and blocks on nothing, so the search is
    // nearly free against a run measured in minutes: the two passes below
    // cost about 17,000 of them where the first cut spent 2,401.
    //
    // Two passes rather than one wide fine pass because the fine window is
    // the ladder's own 256-block disc, and sampling +/-6144 at 64 blocks
    // would be 36,865 columns to find features - a coastline, a mountain
    // foot - that are kilometres across and visible at 128.
    // ------------------------------------------------------------------

    /** Coarse survey half-extent, blocks. 97 x 97 = 9,409 noise columns. */
    private static final int RELIEF_COARSE_HALF = 6144;
    /** Coarse survey spacing, blocks. */
    private static final int RELIEF_COARSE_STEP = 128;
    /**
     * Coarse window half-extent in SAMPLES: 4 x 128 = 512 blocks. Same
     * SAMPLE count as the fine window (9 x 9 = 81) over four times the
     * area, so the two passes' percentages are read the same way - the
     * coarse one just answers at the scale of the coastline rather than at
     * the scale of the disc.
     */
    private static final int RELIEF_COARSE_WINDOW = 4;
    /**
     * Coarse candidates carried into the fine pass, best first. More than
     * one because the coarse pass measures a 1024-block box and the ladder
     * needs a 512-block one: the best coarse cell can lose its high ground
     * when the window shrinks, and the second can win.
     */
    private static final int RELIEF_CANDIDATES = 12;
    /**
     * Fine box half-extent around a coarse candidate, blocks. 25 x 25 =
     * 625 columns, of which the inner 17 x 17 can be evaluated with a full
     * {@value #RELIEF_WINDOW}-sample window - so the fine pass searches
     * +/-512 blocks around each candidate, which is the coarse grid's own
     * spacing four times over and cannot fall between two coarse cells.
     */
    private static final int RELIEF_FINE_HALF = 768;
    /** Fine sample spacing, blocks. */
    private static final int RELIEF_STEP = 64;
    /** Fine window half-extent in SAMPLES: 4 x 64 = 256 blocks = the rd-16 disc. */
    private static final int RELIEF_WINDOW = 4;
    /** A column at or below this is "low ground" for the locator. */
    private static final int RELIEF_LOW_Y = 64;
    /** A column at or above this is "high ground": four sections over the low. */
    private static final int RELIEF_HIGH_Y = 128;
    /** Fraction of the window that must be low ground. */
    private static final double RELIEF_MIN_LOW_FRACTION = 0.25;
    /** Fraction of the window that must be high ground. */
    private static final double RELIEF_MIN_HIGH_FRACTION = 0.12;

    // ------------------------------------------------------------------
    // Scenes
    // ------------------------------------------------------------------

    /**
     * One photographed biome.
     *
     * @param id           short name, used in every screenshot and log line
     * @param shotBase     screenshot index of this scene's first shot, fixed
     *                     per scene so a scene that cannot be located does
     *                     not renumber the others
     * @param cameraLift   blocks above the local surface for the look-back
     *                     camera
     * @param lookPitch    look-back pitch, degrees down
     * @param searchRadius blocks to search outward from spawn
     * @param required     true when the run FAILS if the biome is not in
     *                     range; false for best-effort scenes
     * @param wanted       biome predicates, first choice first
     */
    private record Scene(String id, int shotBase, double cameraLift, String lookPitch,
            int searchRadius, boolean required, List<Choice> wanted) {
    }

    /** One biome predicate with a human name for the log. */
    private record Choice(String name, Predicate<Holder<Biome>> predicate) {
    }

    /**
     * The three scenes, in the order they are shot.
     *
     * <p>Predicates are TAGS wherever a tag says what we mean, because a
     * tag survives a biome being added: {@code IS_DEEP_OCEAN} will still
     * be an ocean next version. The cliff uses an explicit key list
     * because no vanilla tag means "steep" - {@code IS_MOUNTAIN}
     * contains meadow and cherry grove, which are gentle, and the whole
     * point of that scene is a vertical face for the missing-skirt
     * defect (B5) to hole.</p>
     */
    /**
     * What counts as HIGH GROUND, shared by the cliff scene and by the
     * relief locator (which centres one of its surveys on the nearest
     * one - the world's own answer to "where are the mountains", which
     * beats sweeping outward from spawn and hoping).
     *
     * <p>An explicit key list because no vanilla tag means "steep":
     * {@code IS_MOUNTAIN} contains meadow and cherry grove, which are
     * gentle, and both consumers want a vertical face.</p>
     */
    private static final List<Choice> HIGH_GROUND = List.of(
            new Choice("peaks", biome -> biome.is(Biomes.JAGGED_PEAKS)
                    || biome.is(Biomes.FROZEN_PEAKS)
                    || biome.is(Biomes.STONY_PEAKS)
                    || biome.is(Biomes.ERODED_BADLANDS)),
            new Choice("windswept_hills", biome -> biome.is(BiomeTags.IS_HILL)));

    private static final List<Scene> SCENES = List.of(
            // Ocean: F5's "two main colours that dont work right" and E2's
            // gray water live here, with A4 and A5 (opaque water at the
            // wrong height) and E3's chunk walls in kelp.
            new Scene("ocean", 0, 45.0, "10", SEARCH_RADIUS, true, List.of(
                    new Choice("deep_ocean", biome -> biome.is(BiomeTags.IS_DEEP_OCEAN)),
                    new Choice("any_ocean", biome -> biome.is(BiomeTags.IS_OCEAN)))),
            // Forest: F5's grass and leaf colour, and E1 (plants drawn as
            // cubes). A canopy is also the densest cutout geometry the far
            // mesher ever sees.
            new Scene("forest", 3, 45.0, "10", SEARCH_RADIUS, true, List.of(
                    new Choice("forest", biome -> biome.is(BiomeTags.IS_FOREST)),
                    new Choice("jungle", biome -> biome.is(BiomeTags.IS_JUNGLE)))),
            // Cliff: F1 (gaps on cliff sides, still there after the
            // neighbour minimum), F2 (gaps at caves, "the main one") and
            // E6 (hollowed chunks) all need a vertical face. Lower camera
            // and a nearly level pitch, so the silhouette fills the frame
            // instead of being looked down on.
            new Scene("cliff", 6, 30.0, "4", CLIFF_SEARCH_RADIUS, false, HIGH_GROUND));

    /**
     * A cardinal walk direction, and the yaw that looks back down it.
     * (The ladder does not walk; it needs a yaw the locator computes from
     * where the high ground actually is, which no cardinal can give it.)
     */
    private enum Walk {
        EAST(1, 0, "-90", "90"),
        WEST(-1, 0, "90", "-90"),
        SOUTH(0, 1, "0", "180"),
        NORTH(0, -1, "180", "0");

        final int dx;
        final int dz;
        /** Yaw while travelling: facing the way we are going. */
        final String travelYaw;
        /** Yaw at the far camera: back down the corridor we just walked. */
        final String lookBackYaw;

        Walk(int dx, int dz, String travelYaw, String lookBackYaw) {
            this.dx = dx;
            this.dz = dz;
            this.travelYaw = travelYaw;
            this.lookBackYaw = lookBackYaw;
        }
    }

    // ------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!ARMED) {
            return; // not a farvisual run; the normal suite pays nothing
        }
        // These are HARD failures rather than skips. The property was
        // passed on purpose, so a run that quietly photographed a
        // dormant renderer would be worse than no run at all: the
        // coordinator would read nine pictures of plain vanilla and
        // conclude the far field is broken.
        if (!"vulkan".equalsIgnoreCase(
                System.getProperty("meshelium.test.expectBackend", ""))) {
            throw new AssertionError("the far-field visual diagnostic needs "
                    + "-Pmeshelium.backend=vulkan; on the OpenGL backend Meshelium is "
                    + "dormant by design and there is no far field to photograph");
        }
        if (!Boolean.getBoolean(TerrainDrawer.PROPERTY)) {
            throw new AssertionError("the far-field visual diagnostic needs "
                    + "-Pmeshelium.terrain; without it the drawer never arms, the far "
                    + "field draws through retained paths that are never reached, and "
                    + "every screenshot would be plain vanilla");
        }
        if (!MesheliumConfig.terrainRenderingConfigured()) {
            throw new AssertionError("enableTerrainRendering is FALSE in "
                    + "config/meshelium.json; FarFieldResidency.pump gates on it, so the "
                    + "far field can never arm and the diagnostic would photograph an "
                    + "empty ring");
        }

        context.runOnClient(client -> {
            // Small near field, fixed field of view, nothing animated in
            // the sky. Set BEFORE world creation so the login
            // ClientInformation already carries rd 8 and the world never
            // generates a bigger window than the diagnostic needs. That
            // also sidesteps the trap MesheliumFarFieldTest documents at
            // its own setup - raising the option AFTER
            // waitForChunksRender, whose internal timeout is fixed -
            // because nothing is raised at all.
            client.options.renderDistance().set(NEAR_RD);
            client.options.fov().set(70);
            client.options.cloudStatus().set(CloudStatus.OFF);
            // The 30fps trap (benchmark, 2026-08-10): an unfocused
            // harness window is capped to 30fps after a minute, which
            // here would not corrupt a measurement but would triple
            // every settle in the run.
            client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(260);
            client.options.save();
        });

        List<String> shot = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            try (TestSingleplayerContext singleplayer = context.worldBuilder()
                    .adjustSettings(settings -> {
                        settings.setName(WORLD_NAME);
                        settings.setSeed(SEED);
                        settings.setAllowCommands(true);
                        settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                        // A REAL noise world. The default harness world is
                        // the fabric-consistent FLAT preset, which is the
                        // exact blind spot this class exists to escape.
                        settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(
                                settings.getSettings().worldgenLoadContext()
                                        .lookupOrThrow(Registries.WORLD_PRESET)
                                        .getOrThrow(WorldPresets.NORMAL)));
                    })
                    .create()) {

                TestServerContext server = singleplayer.getServer();
                // NOT waitForChunksRender(): the framework helper has a
                // fixed internal timeout that a noise world can outrun.
                // Settle on observables with no deadline of their own.
                settleLoadedChunks(context);
                freezeWorld(server);
                server.runCommand("gamemode spectator @p");
                try {
                    context.waitFor(client ->
                            client.options.getEffectiveRenderDistance() == NEAR_RD,
                            RD_TIMEOUT_TICKS);
                } catch (Throwable t) {
                    int effective = context.computeOnClient(client ->
                            client.options.getEffectiveRenderDistance());
                    throw new AssertionError("the near render distance never settled at "
                            + NEAR_RD + " (effective " + effective + "). Everything about "
                            + "the framing below assumes a 128-block near horizon, so the "
                            + "screenshots would be unreadable against the manifest", t);
                }
                context.waitFor(client -> TerrainDrawer.framesDrawn() > 0
                        && TerrainDrawer.lastDrawnSections() > 0, DRAW_TIMEOUT_TICKS);
                assertNoErrors();

                log("world ready: seed=" + SEED + " renderDistance=" + NEAR_RD
                        + " farRadiusChunks=" + FAR_L1
                        + " nearHorizonBlocks=" + (NEAR_RD * 16)
                        + " farHorizonBlocks=" + (FAR_L1 * 16)
                        + " cacheFolder=" + FarField.cacheFolder());

                setFarRadius(context, FAR_L1);
                if (LADDER_ONLY) {
                    // The tuning loop: the ladder alone, in the same world,
                    // with the three scenes' minutes given back.
                    log("flyup.only: skipping the ocean, forest and cliff scenes");
                    for (Scene scene : SCENES) {
                        skipped.add(scene.id());
                    }
                } else {
                    for (Scene scene : SCENES) {
                        if (runScene(context, singleplayer, scene)) {
                            shot.add(scene.id());
                        } else {
                            skipped.add(scene.id());
                        }
                    }
                }
                assertNoErrors();
                // S6: the fly-up ladder. Last, because it moves the render
                // distance and the occlusion lever and every scene above
                // depends on both. It is an ACCEPTANCE leg, not a
                // diagnostic: it fails the run.
                if (LADDER) {
                    flyUpLadder(context, singleplayer);
                    shot.add("flyup");
                } else {
                    skipped.add("flyup");
                }
                assertNoErrors();
            }
        } finally {
            armFarField(context, false);
            context.runOnClient(client -> {
                System.clearProperty("meshelium.farfield.l1RadiusChunks");
                System.clearProperty("meshelium.farfield.l1Enabled");
            });
        }

        log("SUMMARY shot=" + shot + " skipped=" + skipped);
        // In flyup.only mode the ladder is the whole run and it asserts for
        // itself; the two-scene floor is about the PHOTOGRAPHIC legs being
        // worth reading, which is a different question.
        int floor = LADDER_ONLY ? 1 : 2;
        if (shot.size() < floor) {
            throw new AssertionError("the visual diagnostic produced only " + shot
                    + " - it needs " + (LADDER_ONLY ? "the ladder"
                            : "at least the ocean and the forest") + " to be worth reading. "
                    + "Skipped: " + skipped + ". Try another world with " + SEED_HINT);
        }
    }

    /**
     * One scene, with a counter dump on the way out whichever way it
     * ends. A diagnostic that fails silently is the thing this whole
     * class exists to stop happening.
     */
    private static boolean runScene(ClientGameTestContext context,
            TestSingleplayerContext singleplayer, Scene scene) {
        try {
            return shootScene(context, singleplayer, scene);
        } catch (RuntimeException | Error failure) {
            log("===== BEGIN " + scene.id() + " FAILURE =====");
            log(scene.id() + " failure=" + failure);
            for (Map.Entry<String, Long> entry : counterSnapshot(context).entrySet()) {
                log(scene.id() + " " + entry.getKey() + "=" + entry.getValue());
            }
            log(scene.id() + " farBroken=" + FarWalkerProbe.broken());
            log(scene.id() + " residency=" + TerrainResidency.counters());
            log("===== END " + scene.id() + " FAILURE =====");
            throw failure;
        }
    }

    private static boolean shootScene(ClientGameTestContext context,
            TestSingleplayerContext singleplayer, Scene scene) {
        TestServerContext server = singleplayer.getServer();

        // --- drain the PREVIOUS scene before locating this one ---
        // A gauge that never returned to zero would let scene one's
        // residents satisfy scene three's "far sections are resident"
        // check, and the admissions delta would be the only honest
        // number left in the block.
        armFarField(context, false);
        waitForFarDrain(context);

        // --- locate ---
        Choice used = null;
        BlockPos located = null;
        for (Choice choice : scene.wanted()) {
            located = findBiome(server, choice.predicate(), scene.searchRadius());
            if (located != null) {
                used = choice;
                break;
            }
        }
        if (located == null) {
            String names = scene.wanted().stream().map(Choice::name).toList().toString();
            if (scene.required()) {
                throw new AssertionError("no " + names + " within " + scene.searchRadius()
                        + " blocks of spawn for seed " + SEED + " - refusing to photograph "
                        + "some other biome under a '" + scene.id() + "' label. Pick a "
                        + "different world with " + SEED_HINT);
            }
            MesheliumLog.LOGGER.warn("{} SCENE {} SKIPPED: none of {} within {} blocks "
                    + "of spawn for seed {}. Three of the reported defects (cave mouths, "
                    + "cliff sides, hollowed chunks) have no picture in this run.",
                    TAG, scene.id(), names, scene.searchRadius(), SEED);
            return false;
        }
        final BlockPos origin = located;
        final Choice biome = used;

        // --- choose the direction that keeps the far terrain on-biome ---
        // Walking blind risks the classic own goal: leave a deep ocean
        // after 384 blocks, turn around, and photograph a forest under
        // an "ocean" filename. findClosestBiome3d answers with the
        // CLOSEST match to spawn, which for a large biome is usually a
        // point on its EDGE, so half the directions lead straight out of
        // it. Each probe is a short-radius biome-source query at the
        // candidate far point and generates no chunks.
        Walk walk = chooseWalk(server, biome.predicate(), origin);

        // --- corridor or survey: ONLY the walk differs ---
        // Everything downstream - the pose, the A/B pair, the two
        // censuses, the counter block - is identical, so a survey run
        // and a corridor run of the same scene are read exactly the same
        // way and differ only in how much of the ring had cached terrain
        // behind it when the picture was taken.
        boolean surveying = surveyThisScene(scene);
        int l1 = surveying ? SURVEY_L1 : FAR_L1;
        int walkBlocks = surveying
                ? SURVEY_LANES * SURVEY_LANE_STEPS * SURVEY_STEP_BLOCKS
                : HOP_BLOCKS * HOPS;
        // Lane direction is the chosen walk; lanes step along its
        // left-hand perpendicular (-dz, dx). The camera finishes in the
        // MIDDLE of the surveyed rectangle, which is the whole point:
        // "the ring around the player has missing chunks" cannot be
        // judged from a corner.
        int perpDx = -walk.dz;
        int perpDz = walk.dx;
        int alongCentre = SURVEY_LANE_STEPS * SURVEY_STEP_BLOCKS / 2;
        int acrossCentre = (SURVEY_LANES - 1) * SURVEY_LANE_SPACING / 2;
        int farX = surveying
                ? origin.getX() + walk.dx * alongCentre + perpDx * acrossCentre
                : origin.getX() + walk.dx * HOP_BLOCKS * HOPS;
        int farZ = surveying
                ? origin.getZ() + walk.dz * alongCentre + perpDz * acrossCentre
                : origin.getZ() + walk.dz * HOP_BLOCKS * HOPS;
        log("SCENE " + scene.id() + " begin: biome=" + biome.name()
                + " origin=" + origin.getX() + "," + origin.getZ()
                + " walk=" + walk + " mode=" + (surveying ? "survey" : "corridor")
                + " walkBlocks=" + walkBlocks + " farRadiusChunks=" + l1
                + " cameraColumn=" + farX + "," + farZ);

        // --- stand on the scene, arm the far field, walk away ---
        teleport(server, origin.getX(), TRAVEL_Y, origin.getZ(), walk.travelYaw, "0");
        settleLoadedChunks(context);
        armFarField(context, true);
        setFarRadius(context, l1);

        Map<String, Long> before = counterSnapshot(context);
        long writesBefore = FarField.farStoreWrites.sum();
        long admissionsBefore = FarWalkerProbe.admissions();
        // The handover clock starts empty per scene: a column that left
        // vanilla's disc in the OCEAN must not be resolved by a
        // residency event in the forest.
        context.runOnClient(client -> FarWalkerProbe.resetFlash());

        if (surveying) {
            surveyWalk(context, server, scene, origin, walk);
        } else {
            for (int hop = 1; hop < HOPS; hop++) {
                teleport(server, origin.getX() + walk.dx * HOP_BLOCKS * hop, TRAVEL_Y,
                        origin.getZ() + walk.dz * HOP_BLOCKS * hop, walk.travelYaw, "0");
                // Leg B: settle one tick at a time, sampling the
                // continuity oracle on every tick - the corridor walk is
                // the travel leg the invariant is judged on, and a
                // 20-tick settle would let a flash open and close between
                // observations (the transitions-not-steady-states rule).
                settleLoadedChunksSampling(context);
            }
        }
        // --- the final approach, and the ARRIVAL census ---
        // Both walks stop one leg short so this last move can be
        // measured. Twenty ticks after arriving is the only moment in
        // the whole run that looks like a travelling player's frame:
        // the camera is somewhere new, vanilla has abandoned a swathe of
        // columns behind and beside it, and the walker has had one
        // second to serve them. Taking this census after the settles -
        // as an earlier draft did - produces the settled census twice
        // and answers nothing.
        teleport(server, farX, TRAVEL_Y, farZ, walk.travelYaw, "0");
        context.waitTicks(20);
        dumpCensus(scene, "arrival", surveying, takeCensus(context));
        settleLoadedChunksSampling(context);
        // Extraction is synchronous on the client's forget path, but the
        // store write is queued onto the far IO thread. Wait for it, so
        // a read-back failure below cannot be blamed on a write that had
        // simply not happened yet.
        try {
            context.waitFor(client -> FarField.farStoreWrites.sum() > writesBefore,
                    DRAW_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("scene " + scene.id() + ": walking "
                    + walkBlocks + " blocks wrote nothing to the far store "
                    + "(extracts=" + ExtractDispatch.farExtracts.sum()
                    + " writes=" + FarField.farStoreWrites.sum()
                    + " dropped=" + FarField.farStoreDroppedWrites.sum()
                    + " errors=" + FarField.farStoreErrors.sum()
                    + " armed=" + ExtractDispatch.armed()
                    + ") - there is nothing to photograph", t);
        }

        // --- the look-back pose, resolved ONCE and reused verbatim ---
        // Absolute, and the SAME command string for both halves of the
        // pair: a relative height would climb the camera another lift
        // every time the pose is restored and the A/B would stop being
        // an A/B. The surface query is safe here because the camera
        // column's chunk is loaded - the approach above settled on it.
        double cameraY = surfaceY(server, farX, farZ) + scene.cameraLift();
        String lookBack = tpCommand(farX, cameraY, farZ, walk.lookBackYaw, scene.lookPitch());
        server.runCommand(lookBack);
        context.waitTicks(40);
        quiesceResidency(context);

        try {
            context.waitFor(client -> FarWalkerProbe.resident() > 0
                    && FarWalkerProbe.admissions() > admissionsBefore, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("scene " + scene.id() + ": no far section ever became "
                    + "resident at the camera. " + FarWalkerProbe.describe()
                    + " storeReads=" + FarField.farStoreReads.sum()
                    + " storeWrites=" + FarField.farStoreWrites.sum()
                    + " extracts=" + ExtractDispatch.farExtracts.sum(), t);
        }
        long resident = settleFarResident(context);
        if (resident <= 0) {
            throw new AssertionError("scene " + scene.id() + ": far sections became resident "
                    + "and then all left again before the screenshot: "
                    + FarWalkerProbe.describe());
        }
        assertNoErrors();

        // SETTLED census: the best this pose will ever look. Read
        // against the arrival block above - clean here and dirty there
        // is the flash; dirty in both is the missing chunks.
        Census settled = takeCensus(context);
        dumpCensus(scene, "settled", surveying, settled);

        // --- V(n): far field ON ---
        server.runCommand(lookBack);
        context.waitTicks(20);
        Path on = context.takeScreenshot(TestScreenshotOptions.of(
                shotName(scene, 0, "far_on", surveying)));
        log(scene.id() + " shot " + on.getFileName() + " farResident=" + resident);

        // --- V(n+1): identical pose, far field OFF ---
        armFarField(context, false);
        try {
            context.waitFor(client -> FarWalkerProbe.resident() == 0, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("scene " + scene.id() + ": switching the far field off "
                    + "left " + FarWalkerProbe.resident() + " far sections drawing, so the "
                    + "OFF frame would not be an OFF frame and the pair would be "
                    + "unreadable", t);
        }
        context.waitTicks(20);
        server.runCommand(lookBack);
        context.waitTicks(20);
        Path off = context.takeScreenshot(TestScreenshotOptions.of(
                shotName(scene, 1, "far_off", surveying)));
        log(scene.id() + " shot " + off.getFileName() + " farResident=0");

        // --- V(n+2): far field back ON, looking down from high up ---
        armFarField(context, true);
        try {
            context.waitFor(client -> FarWalkerProbe.resident() > 0, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("scene " + scene.id() + ": the far field did not come "
                    + "back after being toggled off and on: " + FarWalkerProbe.describe(), t);
        }
        settleFarResident(context);
        // Straight up: the walker re-arms on a camera CHUNK COLUMN
        // crossing, and climbing crosses none, so the far set
        // photographed here is the same set the pair above photographed.
        server.runCommand(tpCommand(farX, cameraY + DOWN_CLIMB, farZ,
                walk.lookBackYaw, DOWN_PITCH));
        context.waitTicks(80);
        quiesceResidency(context);
        Path down = context.takeScreenshot(TestScreenshotOptions.of(
                shotName(scene, 2, "down", surveying)));
        log(scene.id() + " shot " + down.getFileName()
                + " farResident=" + FarWalkerProbe.resident());
        assertNoErrors();

        // --- the numbers ---
        double difference = pixelDifference(on, off);
        dumpCounters(context, scene, biome.name(), origin, walk, surveying, l1, walkBlocks,
                farX, farZ, cameraY, before, on, off, down, difference);
        Flash flash = dumpFlash(context, scene, surveying);
        assertSeamLegB(context, scene, surveying, before, flash);

        if (difference < MIN_PIXEL_DIFF) {
            throw new AssertionError("scene " + scene.id() + ": the far-ON and far-OFF "
                    + "frames are " + percent(difference) + " different, under the "
                    + percent(MIN_PIXEL_DIFF) + " floor. The far field reported "
                    + resident + " resident sections and then changed almost nothing on "
                    + "screen. Two near-identical PNGs read as 'no bug here', which is why "
                    + "this fails instead of shipping them: " + on.getFileName() + " vs "
                    + off.getFileName());
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Scene location
    // ------------------------------------------------------------------

    /**
     * The benchmark's biome lookup, generalised over the predicate. The
     * query goes straight to the overworld's biome source with vanilla's
     * own {@code /locate biome} parameters (32 horizontal steps, 64
     * vertical), so the answer is a function of the seed alone and no
     * chunk has to be generated to compute it.
     *
     * <p>Mirrored from {@code MesheliumBenchmarkTest.resolveOceanCamera}
     * rather than called: that method is private, hard-codes
     * {@code Biomes.DEEP_OCEAN} and builds a benchmark camera string,
     * none of which fits three scenes.</p>
     */
    private static BlockPos findBiome(TestServerContext server,
            Predicate<Holder<Biome>> predicate, int radius) {
        return server.computeOnServer(mc -> {
            var level = mc.overworld();
            var hit = level.findClosestBiome3d(predicate,
                    level.getRespawnData().pos(), radius, 32, 64);
            return hit == null ? null : hit.getFirst();
        });
    }

    /**
     * Pick the walk direction whose FAR END is still in the scene's
     * biome, so the terrain the camera turns around to photograph is the
     * biome the filename claims it is.
     *
     * <p>Falls back to EAST and says so in the log. For the cliff scene
     * the fallback is the normal outcome and is fine: peaks are small,
     * and standing outside one looking back at it is exactly the frame
     * that shows a cliff FACE.</p>
     */
    private static Walk chooseWalk(TestServerContext server,
            Predicate<Holder<Biome>> predicate, BlockPos origin) {
        int reach = HOP_BLOCKS * HOPS;
        for (Walk walk : Walk.values()) {
            int x = origin.getX() + walk.dx * reach;
            int z = origin.getZ() + walk.dz * reach;
            BlockPos hit = server.computeOnServer(mc -> {
                var found = mc.overworld().findClosestBiome3d(predicate,
                        new BlockPos(x, 64, z), 48, 8, 32);
                return found == null ? null : found.getFirst();
            });
            if (hit != null) {
                return walk;
            }
        }
        log("no cardinal direction keeps the far end on-biome; walking EAST and "
                + "photographing whatever the corridor turns out to be");
        return Walk.EAST;
    }

    // ------------------------------------------------------------------
    // The boustrophedon survey
    // ------------------------------------------------------------------

    /** Is this scene surveyed, or does it take the fast corridor walk? */
    private static boolean surveyThisScene(Scene scene) {
        if (!SURVEY) {
            return false;
        }
        if (SURVEY_SCENES.isEmpty()) {
            return true;
        }
        for (String wanted : SURVEY_SCENES.split(",")) {
            if (wanted.trim().equalsIgnoreCase(scene.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walk {@value #SURVEY_LANES} lanes back and forth over the scene,
     * so the far store ends up holding an AREA rather than a corridor.
     *
     * <p>The lanes run along the chosen walk direction and step across
     * its left-hand perpendicular, alternating direction each lane so
     * the camera never teleports the full length of a lane. That
     * alternation is not cosmetic: a long jump moves the camera further
     * than the client's storage window in one tick, which forgets and
     * re-requests a whole window of chunks at once and produces a burst
     * of extraction that has nothing to do with how a player travels.
     * Walking back the way we came keeps every step inside the
     * window.</p>
     *
     * <p>Stops at the end of the last lane, NOT at the camera column:
     * the caller makes that final move itself so it can census the
     * arrival before anything settles.</p>
     */
    private static void surveyWalk(ClientGameTestContext context, TestServerContext server,
            Scene scene, BlockPos origin, Walk walk) {
        int perpDx = -walk.dz;
        int perpDz = walk.dx;
        long startedAt = System.nanoTime();
        int stops = 0;
        for (int lane = 0; lane < SURVEY_LANES; lane++) {
            int across = lane * SURVEY_LANE_SPACING;
            for (int step = 0; step <= SURVEY_LANE_STEPS; step++) {
                // Boustrophedon: even lanes run outward, odd lanes back.
                int index = (lane % 2 == 0) ? step : SURVEY_LANE_STEPS - step;
                int along = index * SURVEY_STEP_BLOCKS;
                int x = origin.getX() + walk.dx * along + perpDx * across;
                int z = origin.getZ() + walk.dz * along + perpDz * across;
                teleport(server, x, TRAVEL_Y, z, walk.travelYaw, "0");
                surveyStopSettle(context);
                stops++;
            }
            // One line per lane, not per stop: a survey is 70-odd stops
            // and a per-stop line would bury the census block that
            // follows it.
            log(scene.id() + " survey lane=" + (lane + 1) + "/" + SURVEY_LANES
                    + " stops=" + stops
                    + " extracts=" + ExtractDispatch.farExtracts.sum()
                    + " storeWrites=" + FarField.farStoreWrites.sum()
                    + " farResident=" + FarWalkerProbe.resident()
                    + " elapsedSeconds=" + ((System.nanoTime() - startedAt) / 1_000_000_000L));
        }
        log(scene.id() + " survey done: stops=" + stops
                + " lanes=" + SURVEY_LANES + "x" + SURVEY_LANE_STEPS
                + " step=" + SURVEY_STEP_BLOCKS + " spacing=" + SURVEY_LANE_SPACING
                + " areaBlocks=" + (SURVEY_LANE_STEPS * SURVEY_STEP_BLOCKS) + "x"
                + ((SURVEY_LANES - 1) * SURVEY_LANE_SPACING)
                + " seconds=" + ((System.nanoTime() - startedAt) / 1_000_000_000L)
                + " " + FarWalkerProbe.flashLine());
    }

    /**
     * The survey's settle: one tick at a time, sampling the handover
     * clock on EVERY tick.
     *
     * <p>Per-tick is the whole reason the flash distribution is only
     * offered under the survey. {@link #settleLoadedChunks} waits in
     * 20-tick blocks, which would quantise a handover gap that is
     * plausibly a handful of frames long into a single unusable bucket.
     * The chunk-count check still only runs every 20 samples, because
     * that is the observable being waited on and polling it per tick
     * would cost a client round trip for nothing.</p>
     *
     * <p>Stability is two consecutive equal counts, i.e. 40 ticks of
     * quiet, which is also the floor on a stop. Non-fatal on timeout:
     * losing a whole survey to one slow stop would be a bad trade for a
     * diagnostic, and the stop simply contributes a little less
     * extraction.</p>
     */
    private static void surveyStopSettle(ClientGameTestContext context) {
        int last = -1;
        int stable = 0;
        for (int tick = 0; tick < SURVEY_STOP_MAX_TICKS; tick++) {
            context.waitTicks(1);
            context.runOnClient(client -> FarWalkerProbe.sampleFlash());
            if (tick % 20 != 19) {
                continue;
            }
            int now = context.computeOnClient(client -> client.level == null ? -1
                    : client.level.getChunkSource().getLoadedChunksCount());
            if (now > 0 && now == last) {
                if (++stable >= 2) {
                    return;
                }
            } else {
                stable = 0;
            }
            last = now;
        }
        log("a survey stop never settled within " + SURVEY_STOP_MAX_TICKS + " ticks "
                + "(loadedChunks " + last + "); carrying on, that stop simply extracted "
                + "less than the others");
    }

    /**
     * Surface height at a column, from the SERVER, which holds every
     * heightmap type for every loaded chunk.
     *
     * <p>{@code Level.getHeight} is guarded by {@code hasChunk} and
     * answers {@code getSeaLevel() + 1} for an unloaded column (javap,
     * 26.2 merged jar, ip 37-49), so this can neither generate a chunk
     * nor block the server thread on one - but it also means the answer
     * is only meaningful for a column the player is standing in, which
     * is the only way it is called. Over water {@code WORLD_SURFACE} is
     * the water top, which is what an ocean camera wants; over a forest
     * it is the canopy.</p>
     */
    private static int surfaceY(TestServerContext server, int x, int z) {
        Integer y = server.computeOnServer(mc ->
                mc.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        return y == null ? 64 : y;
    }

    // ------------------------------------------------------------------
    // Screenshots
    // ------------------------------------------------------------------

    /**
     * {@code V3_forest_far_on} and friends, or
     * {@code V3_forest_survey_far_on} when the scene was surveyed.
     *
     * <p>The V-numbering stays put across both modes so the manifest
     * still reads scene by scene; the mode goes in the middle so a
     * folder holding both runs cannot silently overwrite one with the
     * other. Two pictures of the same pose over differently populated
     * caches under one filename would be the worst possible artefact of
     * this change.</p>
     */
    private static String shotName(Scene scene, int offset, String what, boolean surveying) {
        return "V" + (scene.shotBase() + offset) + "_" + scene.id() + "_"
                + (surveying ? "survey_" : "") + what;
    }

    /**
     * Fraction of pixels that differ between two screenshots, 0.0 to 1.0.
     *
     * <p>Bytes first: Minecraft writes both files through the same
     * encoder, so identical pixels give identical files and the
     * catastrophic case ("the far field drew nothing") is one array
     * compare. Only a difference is decoded, and only to put a
     * MAGNITUDE on it, because "they differ" and "they differ across a
     * third of the frame" are very different things to somebody judging
     * whether the far field is doing its job.</p>
     *
     * <p>{@link NativeImage} rather than ImageIO: it is Minecraft's own
     * decoder, it is certain to be on the classpath, and vanilla already
     * calls it from background worker threads while loading textures, so
     * it is safe here on the gametest thread. A decode failure degrades
     * to the byte answer instead of failing the run - a diagnostic must
     * not lose nine screenshots to a broken comparison.</p>
     */
    private static double pixelDifference(Path a, Path b) {
        byte[] left;
        byte[] right;
        try {
            left = Files.readAllBytes(a);
            right = Files.readAllBytes(b);
        } catch (Exception unreadable) {
            throw new AssertionError("could not read back the screenshots just written to "
                    + a + " and " + b, unreadable);
        }
        if (Arrays.equals(left, right)) {
            return 0.0;
        }
        try (NativeImage first = NativeImage.read(left);
                NativeImage second = NativeImage.read(right)) {
            if (first.getWidth() != second.getWidth()
                    || first.getHeight() != second.getHeight()) {
                return 1.0; // different sizes: nothing sensible to compare
            }
            int[] one = first.getPixels();
            int[] two = second.getPixels();
            if (one.length != two.length || one.length == 0) {
                return 1.0;
            }
            int differing = 0;
            for (int i = 0; i < one.length; i++) {
                if (one[i] != two[i]) {
                    differing++;
                }
            }
            return differing / (double) one.length;
        } catch (Throwable decodeFailed) {
            MesheliumLog.LOGGER.warn("{} could not decode {} / {} for a pixel diff; "
                    + "falling back to the byte answer, which says they differ", TAG,
                    a.getFileName(), b.getFileName(), decodeFailed);
            return 1.0;
        }
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.3f%%", fraction * 100.0);
    }

    // ------------------------------------------------------------------
    // The missing-column census
    // ------------------------------------------------------------------

    /**
     * One census of the walker's own wanted square.
     *
     * <p>Every field is a plain int, long, array or string ON PURPOSE.
     * The record must not name a {@code FarFieldResidency} type, or the
     * load-order discipline in the class javadoc would be broken by the
     * method signature of {@link #takeCensus} alone. Translation from
     * the walker's types happens inside {@link FarWalkerProbe}, which is
     * the one place allowed to name them.</p>
     *
     * @param stateNames    verdict names, parallel to {@code stateCounts},
     *                      supplied by the walker so the log and the code
     *                      can never disagree about what code 7 means
     * @param missingReason true for the verdicts that count as "should be
     *                      drawn and is not"; also supplied by the walker,
     *                      so this class needs none of its constants
     * @param innerWanted   pre21: wanted columns within Chebyshev
     *                      {@code innerRing + 1} of the camera — the far
     *                      ring's first two rings, where it meets vanilla's
     *                      pad-1 disc
     * @param innerUndrawn  pre21: of those, the ones neither far-resident
     *                      nor near-owned. The fly-up ladder's far-side
     *                      hole number: zero, or the horizon has a gap
     *                      right where the near disc ends
     * @param innerSamples  pre21: coordinates and verdicts for
     *                      {@code innerUndrawn}, capped
     */
    private record Census(boolean armed, int cameraChunkX, int cameraChunkZ,
            int nearEdge, int l1, int innerRing,
            boolean scanPending, int scanRing, int scanIndex,
            int residentColumnCount, int inFlightCount, int queuedCount, int absentCount,
            int partialCount, int refillCount, int latePendingCount,
            int pendingAdmissionColumns, int pendingAdmissionSections,
            int squareColumns, int wantedColumns, int drawnColumns, int hollowColumns,
            long residentSections,
            int[] stateCounts, String[] stateNames, boolean[] missingReason,
            int[] sectionHistogram, List<String> samples,
            int innerWanted, int innerUndrawn, List<String> innerSamples) {
    }

    /**
     * The near/far handover distribution: for a column that leaves
     * vanilla's compile disc, how long until its far sections are
     * resident.
     *
     * @param measured  false in corridor mode, where the settle is
     *                  20 ticks wide and the numbers would be noise
     * @param handovers columns observed leaving the disc
     * @param instant   of those, already resident at the moment they
     *                  left, i.e. no flash at all - the outcome we want
     * @param reentered columns that went back inside the disc before
     *                  resolving; dropped rather than counted as fast
     * @param censored  columns that left the disc and were never once
     *                  observed DRAWN again before they left the ring or
     *                  the scene ended. The number that makes the
     *                  medians honest. Since the leg-B first-run catch
     *                  "drawn" means BOUND - live resident, retained
     *                  (held or parked), or far - unioned with the
     *                  walker's residents: anything less reads an atomic
     *                  supersede or a park as a flash and measures swap
     *                  latency instead of visible absence.
     * @param censoredBound  of the censored, the ones that were BOUND at
     *                  some point of their current far-domain life (the
     *                  everBound memo, reset when the column leaves the
     *                  ring entirely). THE INVARIANT-SCOPED NUMBER: a
     *                  bound position may never become undrawn without a
     *                  counted exit, so this must be ZERO. The remainder
     *                  (censored - censoredBound) is first-visit ground
     *                  nothing was ever bound at - discovery's problem
     *                  by the design's own scope, reported, not
     *                  asserted.
     * @param boundGaps of the RESOLVED handovers, the ones with a
     *                  nonzero gap whose column was ever-bound when the
     *                  clock started. Same invariant scope, same
     *                  contract: ZERO - a bound column's exits are all
     *                  atomic, so any measured gap on ever-bound ground
     *                  is a free that dodged the park.
     */
    private record Flash(boolean measured, long samples, long handovers, long instant,
            long reentered, int resolved, int censored,
            int censoredBound, int boundGaps,
            long frameMin, long frameMedian, long frameP95, long frameMax,
            long tickMin, long tickMedian, long tickP95, long tickMax) {
    }

    /**
     * Take a census. On the CLIENT thread, without exception: every
     * structure the walker's classifier reads is render-thread confined
     * (see the Threading section of {@code FarFieldResidency}), so
     * running this from the gametest thread would read a fastutil table
     * mid-rehash and produce either a wrong census or a crash inside a
     * diagnostic, which is the worst of both.
     */
    private static Census takeCensus(ClientGameTestContext context) {
        return context.computeOnClient(client -> FarWalkerProbe.census());
    }

    /** One clearly delimited, greppable census block. */
    private static void dumpCensus(Scene scene, String phase, boolean surveying, Census c) {
        String id = scene.id();
        String head = id + " CENSUS phase=" + phase;
        log("===== BEGIN " + id + " CENSUS " + phase + " =====");
        if (!c.armed()) {
            log(head + " armed=false - the walker has no camera, no world, or no ring "
                    + "(l1 <= nearEdge), so it has no opinion about any column. Read "
                    + "nothing into the numbers below: check farBroken, the master switch "
                    + "and the far radius before reading anything else in this run.");
            log("===== END " + id + " CENSUS " + phase + " =====");
            return;
        }
        log(head + " mode=" + (surveying ? "survey" : "corridor")
                + " camera=" + c.cameraChunkX() + "," + c.cameraChunkZ()
                + " nearEdge=" + c.nearEdge() + " l1=" + c.l1()
                + " innerRing=" + c.innerRing() + " scanPending=" + c.scanPending()
                + " scanRing=" + c.scanRing() + " scanIndex=" + c.scanIndex());
        log(head + " square=" + c.squareColumns() + " wanted=" + c.wantedColumns()
                + " drawn=" + c.drawnColumns() + " hollow=" + c.hollowColumns()
                + " notDrawn=" + (c.wantedColumns() - c.drawnColumns()));
        // EVERY verdict, every time, zeroes included. A grep that comes
        // back empty is indistinguishable from a grep that comes back
        // zero, and the second is the answer far more often than the
        // first.
        for (int state = 0; state < c.stateCounts().length; state++) {
            log(id + " CENSUS-REASON phase=" + phase + " " + c.stateNames()[state]
                    + "=" + c.stateCounts()[state]);
        }
        StringBuilder hist = new StringBuilder();
        int last = c.sectionHistogram().length - 1;
        for (int n = 0; n <= last; n++) {
            hist.append(' ').append(n == last ? (n + "plus") : Integer.toString(n))
                    .append('=').append(c.sectionHistogram()[n]);
        }
        log(id + " CENSUS-SECTIONS phase=" + phase
                + " residentColumns=" + c.residentColumnCount()
                + " residentSections=" + c.residentSections()
                + " sectionsPerColumn" + hist);
        log(id + " CENSUS-SETS phase=" + phase
                + " resident=" + c.residentColumnCount()
                + " inFlight=" + c.inFlightCount() + " queued=" + c.queuedCount()
                + " absent=" + c.absentCount() + " partial=" + c.partialCount()
                + " refill=" + c.refillCount() + " late=" + c.latePendingCount()
                + " pendingAdmissionColumns=" + c.pendingAdmissionColumns()
                + " pendingAdmissionSections=" + c.pendingAdmissionSections());
        for (String sample : c.samples()) {
            log(id + " CENSUS-SAMPLE phase=" + phase + " " + sample);
        }
        log(id + " CENSUS-ANSWER phase=" + phase + " mode="
                + (surveying ? "survey" : "corridor") + " " + censusAnswer(c));
        log("===== END " + id + " CENSUS " + phase + " =====");
    }

    /**
     * The question the owner is actually asking, in one line: of the
     * columns that should be drawn, how many are not, and what is the
     * single biggest reason.
     */
    private static String censusAnswer(Census c) {
        int wanted = c.wantedColumns();
        if (wanted <= 0) {
            return "wanted=0 - vanilla covers everything inside l1 at this pose, so there "
                    + "is no far field to be missing and nothing to answer";
        }
        int missing = wanted - c.drawnColumns();
        int topState = -1;
        int topCount = 0;
        int secondState = -1;
        int secondCount = 0;
        for (int state = 0; state < c.stateCounts().length; state++) {
            if (!c.missingReason()[state]) {
                continue;
            }
            int n = c.stateCounts()[state];
            if (n > topCount) {
                secondState = topState;
                secondCount = topCount;
                topState = state;
                topCount = n;
            } else if (n > secondCount) {
                secondState = state;
                secondCount = n;
            }
        }
        String out = "wanted=" + wanted + " drawn=" + c.drawnColumns()
                + " hollow=" + c.hollowColumns() + " notDrawn=" + missing
                + " (" + percent(missing / (double) wanted) + " of wanted)";
        if (missing <= 0 || topState < 0) {
            return out + " biggestReason=NONE every wanted column is drawn at this pose";
        }
        out += " biggestReason=" + c.stateNames()[topState] + " count=" + topCount
                + " (" + percent(topCount / (double) missing) + " of notDrawn)";
        if (secondState >= 0 && secondCount > 0) {
            out += " runnerUp=" + c.stateNames()[secondState] + " count=" + secondCount;
        }
        return out;
    }

    /** The handover distribution, as its own greppable block. */
    private static Flash dumpFlash(ClientGameTestContext context, Scene scene,
            boolean surveying) {
        String id = scene.id();
        Flash f = context.computeOnClient(client -> FarWalkerProbe.flash(surveying));
        if (!f.measured()) {
            log(id + " CENSUS-FLASH measured=false - the corridor samples the continuity "
                    + "oracle per tick (leg B) but the DISTRIBUTION is only honest under "
                    + "the survey's uniform per-tick cadence. Re-run this scene with "
                    + "-Dmeshelium.test.farsurvey=true for the numbers.");
            return f;
        }
        log(id + " CENSUS-FLASH measured=true samples=" + f.samples()
                + " handovers=" + f.handovers()
                + " alreadyDrawn=" + f.instant()
                + " reentered=" + f.reentered()
                + " resolved=" + f.resolved()
                + " censored=" + f.censored());
        // The invariant/discovery split. The assertions bind the BOUND
        // half alone: a gap or censoring on ever-bound ground is a free
        // that dodged the park; the never-bound half is first-visit
        // ground the store has nothing for and vanilla never compiled -
        // out of the invariant's scope by the design's own construction
        // ("nothing was ever bound; it is discovery's problem").
        log(id + " CENSUS-FLASH-SCOPE boundGaps=" + f.boundGaps()
                + " censoredBound=" + f.censoredBound()
                + " | discovery: censoredNeverBound="
                + (f.censored() - f.censoredBound())
                + " (never-bound gaps are the remainder of the "
                + "distribution below)");
        if (f.resolved() <= 0) {
            log(id + " CENSUS-FLASH-GAP no column that left vanilla's disc ever became "
                    + "resident during this scene. That is not a slow handover, it is a "
                    + "handover that never completes: read the settled census, not this.");
            return f;
        }
        log(id + " CENSUS-FLASH-GAP frames min=" + f.frameMin()
                + " median=" + f.frameMedian() + " p95=" + f.frameP95()
                + " max=" + f.frameMax()
                + " | ticks min=" + f.tickMin() + " median=" + f.tickMedian()
                + " p95=" + f.tickP95() + " max=" + f.tickMax());
        // The notes travel WITH the numbers, because without them a
        // reader will take a nearest-rank median over resolved-only
        // samples as the whole story, and it is not.
        log(id + " CENSUS-FLASH-NOTE nearest-rank percentiles over the " + f.resolved()
                + " RESOLVED handovers, INCLUDING the " + f.instant() + " that were "
                + "already resident at the moment they left the disc (gap 0). The "
                + f.censored() + " censored columns are NOT in the distribution: those "
                + "left vanilla's disc and were never once observed resident before they "
                + "left the ring or the scene ended, which is the worst outcome there is "
                + "and would drag every percentile up if it could be given a number. "
                + "Consistency: resolved + censored + reentered should equal handovers ("
                + (f.resolved() + f.censored() + f.reentered()) + " vs " + f.handovers()
                + "). Detection is sample-quantised at one tick, so a gap can read up to "
                + "one tick high at each end.");
        return f;
    }

    // ------------------------------------------------------------------
    // Counters
    // ------------------------------------------------------------------

    /**
     * Every far-field counter there is, in one map.
     *
     * <p>Three owners, and the split is what makes the block readable:
     * {@code FarField} is the STORE (bytes on disk),
     * {@code ExtractDispatch} is the WRITE side (what the client
     * abandoned and we caught), {@code FarFieldResidency} is the READ
     * and DRAW side (what came back and reached the GPU). A scene with
     * extracts and writes but no admissions is a read-path bug; one with
     * neither is an extraction bug; and the two are indistinguishable
     * from a screenshot.</p>
     */
    private static Map<String, Long> counterSnapshot(ClientGameTestContext context) {
        // ON the client thread: the LongAdders are safe from anywhere,
        // but the state machine's plain gauges (farSaveBehind,
        // jobsQueued) are game-thread-confined, and the scene
        // assertions built on these deltas must not read torn values.
        return context.computeOnClient(client -> counterSnapshotOnClient());
    }

    private static Map<String, Long> counterSnapshotOnClient() {
        Map<String, Long> c = new LinkedHashMap<>();
        // Store (FarField)
        c.put("farStoreWrites", FarField.farStoreWrites.sum());
        c.put("farStoreReads", FarField.farStoreReads.sum());
        c.put("farStoreErrors", FarField.farStoreErrors.sum());
        c.put("farStoreBytesWritten", FarField.farStoreBytesWritten.sum());
        c.put("farStoreDroppedWrites", FarField.farStoreDroppedWrites.sum());
        // S1 (pre18, R5): what meshing with the 3x3 costs in reads, and
        // how much of it the decoded-shell LRU absorbs. reads+hits is the
        // apron demand (8 per meshed column); reads alone is the extra
        // store traffic; absent is the frontier, where the seam keeps
        // pre18 behaviour by construction.
        c.put("farApronReads", FarField.farApronReads.sum());
        c.put("farApronHits", FarField.farApronHits.sum());
        c.put("farApronAbsent", FarField.farApronAbsent.sum());
        // R4: why variant enumeration declined, SPLIT BY CAUSE, because
        // one number could not tell a knowingly-deferred multipart from
        // the owner's own grass falling back to the baked table. Censused
        // over the jar (SpriteUvResolver's counter block): multiDraw can
        // only ever be bamboo / chorus_plant / fire / soul_fire, and
        // overBound, probeThrew and noGeometry are all UNREACHABLE in
        // vanilla - a non-zero there is a mod or a defect. None of them
        // can be reached by grass_block, sand, dirt or stone. These count
        // CALLS, not blocks: the state cache is dropped on every walker
        // re-arm, so one state can be re-refused many times per flight.
        c.put("farVariantMultiDraw",
                com.deds.meshelium.farfield.mesh.SpriteUvResolver
                        .farVariantMultiDraw.sum());
        c.put("farVariantProbeThrew",
                com.deds.meshelium.farfield.mesh.SpriteUvResolver
                        .farVariantProbeThrew.sum());
        c.put("farVariantOverBound",
                com.deds.meshelium.farfield.mesh.SpriteUvResolver
                        .farVariantOverBound.sum());
        c.put("farVariantNoGeometry",
                com.deds.meshelium.farfield.mesh.SpriteUvResolver
                        .farVariantNoGeometry.sum());
        // Save M1 (pre16 Phase 1): the write-acknowledgement ledger.
        // ackOk + ackFailed must equal the submits (every write path exit
        // acks exactly once); ackFailed must equal farExtractWriteLost
        // below (the pump drains and attributes every failure to the
        // exact victim - the delta heuristic this replaced could only
        // blame the newest submitter). ackFailed nonzero with
        // farStoreDroppedWrites flat would mean a non-eviction loss
        // (dead store, unencodable shell) - different fix, now visible.
        c.put("farSaveAckOk", FarField.farSaveAckOk.sum());
        c.put("farSaveAckFailed", FarField.farSaveAckFailed.sum());
        // Extractor (ExtractDispatch)
        c.put("farExtracts", ExtractDispatch.farExtracts.sum());
        c.put("farExtractCells", ExtractDispatch.farExtractCells.sum());
        c.put("farExtractOnDemand", ExtractDispatch.farExtractOnDemand.sum());
        // The upgrade ledger, record-map form (pre16 M2-M4):
        // farExtractIncomplete counts shells submitted with a quality
        // reason bit set, farExtractUpgrades the whole rewrites that
        // paid them off (counted at the write ack), and
        // farExtractSweepExtracts the columns the safety-net walk found
        // wanting and FILED (it extracts nothing itself since M4).
        c.put("farExtractIncomplete", ExtractDispatch.farExtractIncomplete.sum());
        c.put("farExtractUpgrades", ExtractDispatch.farExtractUpgrades.sum());
        c.put("farExtractSweepExtracts", ExtractDispatch.farExtractSweepExtracts.sum());
        c.put("farExtractStaleFiled", ExtractDispatch.farExtractStaleFiled.sum());
        c.put("farExtractStaleRefreshes",
                ExtractDispatch.farExtractStaleRefreshes.sum());
        // The save state machine's owner-facing ledger. Behind is the
        // repairable class (GONE_BEHIND gauge; E1 repair decrements),
        // lost is permanent and monotone - ZERO IS THE CONTRACT for
        // both; jobsQueued is the scheduler's live backlog and must
        // fall to zero while the player stands still (the role
        // farExtractSweepOwed's arrears used to play); editsCoalesced
        // is the E4 churn bound working (farms land here, not on the
        // budget); degradedLeft prices the design's open upgrade-pin
        // question.
        c.put("farSaveBehind", ExtractDispatch.farSaveBehind());
        c.put("farSaveLost", ExtractDispatch.farSaveLost.sum());
        c.put("farSaveLeftUnsaved", ExtractDispatch.farSaveLeftUnsaved.sum());
        c.put("farSaveGaugeUnderflow", ExtractDispatch.farSaveGaugeUnderflow.sum());
        c.put("jobsQueued", (long) ExtractDispatch.jobsQueued());
        c.put("editsPending",
                (long) ExtractDispatch.editsPending());
        c.put("farSaveEditsCoalesced", ExtractDispatch.farSaveEditsCoalesced.sum());
        c.put("farSaveDegradedLeft",
                ExtractDispatch.farSaveDegradedLeft.sum());
        c.put("farExtractSweepOwed", ExtractDispatch.farExtractSweepOwed.sum());
        // T2 retired the idle boost; this must now be ZERO on every run,
        // and it is exported precisely so the deletion reads as a number
        // beside a pre21 log rather than as a claim in a changelog.
        c.put("farExtractIdleBoosts", ExtractDispatch.farExtractIdleBoosts.sum());
        c.put("farExtractBudgetRefusals", ExtractDispatch.farExtractBudgetRefusals.sum());
        c.put("farSavePins", ExtractDispatch.farSavePins.sum());
        c.put("farSavePinReleases", ExtractDispatch.farSavePinReleases.sum());
        c.put("farSaveCaptureSkipped", ExtractDispatch.farSaveCaptureSkipped.sum());
        c.put("farSaveLightUncaptured", ExtractDispatch.farSaveLightUncaptured.sum());
        c.put("farSavePinTripwire", ExtractDispatch.farSavePinTripwire.sum());
        c.put("farExtractSlices", ExtractDispatch.farExtractSlices.sum());
        c.put("farExtractSlicesSpent", ExtractDispatch.farExtractSlicesSpent.sum());
        c.put("farExtractNanos", ExtractDispatch.farExtractNanos.sum());
        // T2 (the fraction rule). The two that matter most are the first
        // pair: farBudgetGrantedNanos / farBudgetFrameNanos IS the share
        // of the frame the rule took, and it must sit at a tenth at the
        // shipped Background Saving point whatever the frame rate. The
        // clamp counters say which arm was binding when it does not, and
        // farExtractNanos / farBudgetFrameNanos is what the far field
        // actually COST as a share, which is the number the owner feels.
        c.put("farBudgetGrantedNanos", ExtractDispatch.farBudgetGrantedNanos.sum());
        c.put("farBudgetFrameNanos", ExtractDispatch.farBudgetFrameNanos.sum());
        c.put("farBudgetCapBound", ExtractDispatch.farBudgetCapBound.sum());
        c.put("farBudgetFloorBound", ExtractDispatch.farBudgetFloorBound.sum());
        c.put("farBudgetNoFitRefusals", ExtractDispatch.farBudgetNoFitRefusals.sum());
        c.put("farBudgetOverrunNanos", ExtractDispatch.farBudgetOverrunNanos.sum());
        c.put("farBudgetWorstSliceMicros", ExtractDispatch.worstSliceMicros());
        c.put("farBudgetWorstOverrunMicros", ExtractDispatch.worstOverrunMicros());
        c.put("farBudgetFrameMicrosNow", ExtractDispatch.frameMicrosNow());
        c.put("farBudgetPermilleNow", ExtractDispatch.budgetPermilleNow());
        c.put("farBudgetMicrosNow", ExtractDispatch.budgetMicrosNow());
        // Phase 3: the credit bucket is gone with the atom that needed
        // it, and the gauge that replaces it says which half of the
        // pipeline is the bottleneck.
        c.put("farWorkerBacklogNow", (long) ExtractDispatch.workerBacklogNow());
        c.put("farLiveCaptureCount", (long) ExtractDispatch.liveCaptureCount());
        c.put("farCaptureCostMicros", ExtractDispatch.captureCostMicros());
        c.put("farCaptures", ExtractDispatch.farCaptures.sum());
        c.put("farCaptureNanos", ExtractDispatch.farCaptureNanos.sum());
        c.put("farCaptureWorkerFull", ExtractDispatch.farCaptureWorkerFull.sum());
        c.put("farCaptureDeferrals", ExtractDispatch.farCaptureDeferrals.sum());
        c.put("farChunkEventsLastSlice",
                (long) ExtractDispatch.chunkEventsLastSlice());
        // The probe-residency gate and brightest-face rule
        // (ShellExtractor). Expectations, so the numbers are evidence and
        // not decoration: farLightHomeNotResident is ZERO since Phase 3
        // (its one producer was the LIVE wrapper's home-residency gate,
        // which is deleted with the live walk - a nonzero reading now
        // would mean a resurrected live walk); farLightProbeGateRefusals
        // counts
        // frontier wall CELLS whose every probe was refused (flat
        // fallback stored, plane reported pending);
        // farLightBrightestFacePicks is ZERO on a superflat world by
        // construction — every cell has an UP face — so only the
        // real-terrain runs can prove the brightest-face fold fired.
        c.put("farLightHomeNotResident",
                ShellExtractor.farLightHomeNotResident.sum());
        c.put("farLightProbeGateRefusals",
                ShellExtractor.farLightProbeGateRefusals.sum());
        c.put("farLightBrightestFacePicks",
                ShellExtractor.farLightBrightestFacePicks.sum());
        // Save M1: exact write-loss attribution on the extractor side.
        c.put("farExtractWriteLost", ExtractDispatch.farExtractWriteLost.sum());
        // THE OWNERSHIP LEDGER, ENFORCING since seam steps 3-4: the only
        // counter surface the seam has (the O6/P9 handover census died
        // with the mechanisms it counted). What each number proves:
        // parked counts every hold opened (the rebuild handover and the
        // step-4 coverage park - one per free of a covered drawn
        // position); resolved* partition every hold's ending (swap = the
        // atomic exchange, vanilla = vanilla's own truth, uncovered = E1,
        // live since step 4); evictedWall is E2 - the ONE sanctioned
        // violation, zero except under genuine memory pressure, with
        // ringSum/evictedWall the mean eviction distance farthest-first
        // pushes into the fog; demoted is a DEAD edge and must read zero
        // forever (its producers were the deleted deadlines);
        // holdMillis/Max span every resolution, MISS holds included;
        // awaitingQuadsPeak proves the AWAITING_BUDGET_QUADS bound in
        // the bench; illegalTransitions and auditMismatches are the
        // table's contract - ZERO, always.
        c.put("ledgerParked", TerrainResidency.ledgerParked());
        c.put("ledgerDemoted", TerrainResidency.ledgerDemoted());
        c.put("ledgerResolvedSwap", TerrainResidency.ledgerResolvedSwap());
        c.put("ledgerResolvedVanilla", TerrainResidency.ledgerResolvedVanilla());
        c.put("ledgerResolvedUncovered", TerrainResidency.ledgerResolvedUncovered());
        c.put("ledgerEvictedWall", TerrainResidency.ledgerEvictedWall());
        c.put("ledgerEvictedWallRingSum", TerrainResidency.ledgerEvictedWallRingSum());
        c.put("ledgerHoldMillis", TerrainResidency.ledgerHoldMillis());
        c.put("ledgerHoldMaxMillis", TerrainResidency.ledgerHoldMaxMillis());
        c.put("ledgerAwaitingQuadsGauge", TerrainResidency.ledgerAwaitingQuads());
        c.put("ledgerAwaitingQuadsPeak", TerrainResidency.ledgerAwaitingQuadsPeak());
        c.put("ledgerIllegalTransitions", TerrainResidency.ledgerIllegalTransitions());
        c.put("ledgerAuditMismatches", TerrainResidency.ledgerAuditMismatches());
        // Residency (FarFieldResidency, through the probe)
        c.putAll(FarWalkerProbe.counters());
        return c;
    }

    /** One clearly delimited, greppable block per scene (client-thread snapshot). */
    private static void dumpCounters(ClientGameTestContext context,
            Scene scene, String biome, BlockPos origin, Walk walk,
            boolean surveying, int l1, int walkBlocks,
            int farX, int farZ, double cameraY, Map<String, Long> before,
            Path on, Path off, Path down, double difference) {
        String id = scene.id();
        log("===== BEGIN " + id + " =====");
        log(id + " biome=" + biome + " seed=" + SEED);
        log(id + " mode=" + (surveying ? "survey" : "corridor")
                + " origin=" + origin.getX() + "," + origin.getZ()
                + " walk=" + walk + " walkBlocks=" + walkBlocks);
        log(id + " camera=" + farX + "," + formatY(cameraY) + "," + farZ
                + " yaw=" + walk.lookBackYaw + " pitch=" + scene.lookPitch());
        log(id + " downCamera=" + farX + "," + formatY(cameraY + DOWN_CLIMB) + "," + farZ
                + " yaw=" + walk.lookBackYaw + " pitch=" + DOWN_PITCH);
        log(id + " renderDistance=" + NEAR_RD + " farRadiusChunks=" + l1
                + " nearHorizonBlocks=" + (NEAR_RD * 16)
                + " farHorizonBlocks=" + (l1 * 16));
        log(id + " shot.far_on=" + on.getFileName());
        log(id + " shot.far_off=" + off.getFileName());
        log(id + " shot.down=" + down.getFileName());
        log(id + " pixelDifference=" + percent(difference)
                + " floor=" + percent(MIN_PIXEL_DIFF));

        Map<String, Long> now = counterSnapshot(context);
        for (Map.Entry<String, Long> entry : now.entrySet()) {
            long start = before.getOrDefault(entry.getKey(), 0L);
            log(id + " " + entry.getKey() + "=" + entry.getValue()
                    + " (scene " + signed(entry.getValue() - start) + ")");
        }
        // The section-level admission story, as SCENE deltas. This is
        // the line that says whether "whole chunks missing" is an
        // admission problem or a read problem, and the three refusals
        // must be read apart because they have three different fates:
        // SKIPPED means our own far copy is already at that position
        // (harmless bookkeeping), CONTESTED means the near field holds
        // it and the column is owed a re-read when the owner lets go,
        // and NO_BUDGET means region ids or arena bytes ran out and the
        // scan is allowed back. DEFER is never lost - it retries.
        long ok = sceneDelta(before, now, "farAdmissions");
        long skipped = sceneDelta(before, now, "farAdmitSkipped");
        long contested = sceneDelta(before, now, "farContestedSections");
        long noBudget = sceneDelta(before, now, "farAdmitNoBudget");
        long deferred = sceneDelta(before, now, "farAdmitDeferred");
        long released = sceneDelta(before, now, "farBlockersReleased");
        long allSkippedColumns = sceneDelta(before, now, "farColumnsAllSkipped");
        long offered = ok + skipped + contested + noBudget;
        log(id + " CENSUS-ADMIT ok=" + ok + " skipped=" + skipped
                + " contested=" + contested + " noBudget=" + noBudget
                + " deferred=" + deferred + " blockersReleased=" + released
                + " offered=" + offered
                + " okShare=" + (offered > 0 ? percent(ok / (double) offered) : "n/a")
                + " skippedShare=" + (offered > 0
                        ? percent(skipped / (double) offered) : "n/a")
                + " contestedShare=" + (offered > 0
                        ? percent(contested / (double) offered) : "n/a")
                + " columnsAdmittedNothing=" + allSkippedColumns);
        log(id + " farBroken=" + FarWalkerProbe.broken());
        // Near-field context, because a far-field number only means
        // something beside the near field it is supposed to extend.
        TerrainResidency.Counters near = TerrainResidency.counters();
        log(id + " nearSectionsResident=" + near.sectionsResident()
                + " nearRetainedSections=" + near.retainedSections()
                + " arenaUsedBytes=" + near.arenaUsedBytes()
                + " regionsLive=" + near.regionsLive());
        log(id + " drawerFrames=" + TerrainDrawer.framesDrawn()
                + " drawerLastSections=" + TerrainDrawer.lastDrawnSections()
                + " coveragePassive=" + TerrainDrawer.coveragePassive());
        log("===== END " + id + " =====");
    }

    private static String signed(long delta) {
        return delta >= 0 ? "+" + delta : Long.toString(delta);
    }

    /**
     * This scene's share of a cumulative counter. Absent keys answer 0
     * on both sides, so a counter added to the walker after a run's
     * baseline was captured degrades to "everything since the start"
     * rather than to a negative number.
     */
    private static long sceneDelta(Map<String, Long> before, Map<String, Long> now,
            String key) {
        return now.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
    }

    private static void log(String line) {
        MesheliumLog.LOGGER.info("{} {}", TAG, line);
    }

    // ------------------------------------------------------------------
    // Far-walker access, deliberately in its own class
    // ------------------------------------------------------------------

    /**
     * Every {@code FarFieldResidency} reference in this file lives here.
     *
     * <p>Same reasoning as {@code MesheliumFarFieldTest.FarWalkerProbe},
     * and worth repeating because it is easy to undo by accident: the
     * JVM links a class by verifying all of its methods, and although
     * the verifier does not resolve the OWNER of a {@code getstatic},
     * "does not" is a property of the current implementation rather than
     * something the far field's central zero-cost invariant should be
     * bet on. A nested class is a separate class file that is not loaded
     * until something touches it, so this test class simply existing in
     * the entrypoint list cannot be what loads the walker in a run that
     * never enables the far field.</p>
     */
    private static final class FarWalkerProbe {

        private FarWalkerProbe() {
        }

        static long resident() {
            return com.deds.meshelium.farfield.FarFieldResidency.farSectionsResident.sum();
        }

        static long admissions() {
            return com.deds.meshelium.farfield.FarFieldResidency.farAdmissions.sum();
        }

        static boolean broken() {
            return com.deds.meshelium.farfield.FarFieldResidency.isBroken();
        }

        /** The residency half of the counter dump. */
        static Map<String, Long> counters() {
            Map<String, Long> c = new LinkedHashMap<>();
            // A GAUGE, not a total: admit adds, release subtracts. Named
            // so nobody reads its "scene delta" as work done.
            c.put("farSectionsResidentGauge",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farSectionsResident.sum());
            c.put("farShellRequests",
                    com.deds.meshelium.farfield.FarFieldResidency.farShellRequests.sum());
            c.put("farMeshedSections",
                    com.deds.meshelium.farfield.FarFieldResidency.farMeshedSections.sum());
            c.put("farAdmissions",
                    com.deds.meshelium.farfield.FarFieldResidency.farAdmissions.sum());
            c.put("farReleases",
                    com.deds.meshelium.farfield.FarFieldResidency.farReleases.sum());
            c.put("farPromoteBudgetStalls",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farPromoteBudgetStalls.sum());
            c.put("farMeshSkippedCells",
                    com.deds.meshelium.farfield.FarFieldResidency.farMeshSkippedCells.sum());
            c.put("farMeshErrors",
                    com.deds.meshelium.farfield.FarFieldResidency.farMeshErrors.sum());
            c.put("farLateShells",
                    com.deds.meshelium.farfield.FarFieldResidency.farLateShells.sum());
            c.put("farInFlightRescues",
                    com.deds.meshelium.farfield.FarFieldResidency.farInFlightRescues.sum());
            c.put("farSupersededByVanilla",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farSupersededByVanilla.sum());
            c.put("farContestedSections",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farContestedSections.sum());
            c.put("farBlockersReleased",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farBlockersReleased.sum());
            c.put("farWaterOpaqueQuads",
                    com.deds.meshelium.farfield.FarFieldResidency.farWaterOpaqueQuads.sum());
            // The pre6 admission-outcome tallies. farAdmissions above is
            // the OK arm plus (since seam step 2) the REPLACED arm - the
            // outcomes that put geometry on screen - so before these
            // existed "the section was refused" and "the section was
            // never offered" produced the same numbers.
            c.put("farAdmitSkipped",
                    com.deds.meshelium.farfield.FarFieldResidency.farAdmitSkipped.sum());
            c.put("farAdmitNoBudget",
                    com.deds.meshelium.farfield.FarFieldResidency.farAdmitNoBudget.sum());
            c.put("farAdmitDeferred",
                    com.deds.meshelium.farfield.FarFieldResidency.farAdmitDeferred.sum());
            c.put("farColumnsAllSkipped",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farColumnsAllSkipped.sum());
            // N5 (pre13): the stationary-heal ledger. farHolesDrained
            // moving with the camera parked is the whole fix; it was
            // structurally zero before, because every mark that recorded
            // a budget-refused column was consumed only by the ring walk
            // and the ring walk only ran on a camera section crossing.
            c.put("farHolesFiled",
                    com.deds.meshelium.farfield.FarFieldResidency.farHolesFiled.sum());
            c.put("farHolesDrained",
                    com.deds.meshelium.farfield.FarFieldResidency.farHolesDrained.sum());
            c.put("farIdleRescans",
                    com.deds.meshelium.farfield.FarFieldResidency.farIdleRescans.sum());
            // pre13 audit: nonzero means the fill guard said yes and the
            // admission said ADMIT_NO_BUDGET anyway - i.e. the arena, not
            // the region budget - and the retry paths parked themselves
            // rather than re-mesh refused columns forever.
            c.put("farHoleRetryBackoffs",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farHoleRetryBackoffs.sum());
            // Phase 6: three documented tripwires that had NO reader
            // anywhere (the zero-referent sweep found them) - an unread
            // tripwire reads exactly like one that works, so they join
            // the scene dumps instead of being deleted: farReloads
            // climbing on its own means something is churning
            // farMeshSignature behind the settings screen (L9's warning),
            // and standDownPumps in the hundreds means the stand-down
            // deadline is doing the releasing rather than vanilla's
            // compile queue emptying.
            c.put("farReloads",
                    com.deds.meshelium.farfield.FarFieldResidency.farReloads.sum());
            c.put("farReloadSections",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farReloadSections.sum());
            c.put("farStandDownPumps",
                    com.deds.meshelium.farfield.FarFieldResidency
                            .farStandDownPumps.sum());
            return c;
        }

        /** One line of far state for an assertion message. */
        static String describe() {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, Long> entry : counters().entrySet()) {
                out.append(entry.getKey()).append('=').append(entry.getValue()).append(' ');
            }
            return out.append("broken=").append(broken()).toString();
        }

        // --------------------------------------------------------------
        // The census. CLIENT THREAD ONLY.
        // --------------------------------------------------------------

        /**
         * Walk the walker's own wanted square and ask it for a verdict
         * per column.
         *
         * <p>The square is taken from
         * {@code FarFieldResidency.walkerSnapshot()}, not recomputed
         * from the client's camera. Those two differ by up to one pump,
         * and a census that judged the walker against a ring the walker
         * is not working on yet would invent NEVER_SCANNED columns and
         * miss real ones. Cost is {@code (2*l1+1)^2} classifications of
         * a few hash probes each: about 1,400 columns at l1 18, which is
         * microseconds, and it runs once per phase rather than per
         * frame.</p>
         */
        static Census census() {
            var snap = com.deds.meshelium.farfield.FarFieldResidency.walkerSnapshot();
            int states = com.deds.meshelium.farfield.FarFieldResidency.COL_STATE_COUNT;
            String[] names = new String[states];
            boolean[] missingReason = new boolean[states];
            for (int state = 0; state < states; state++) {
                names[state] = com.deds.meshelium.farfield.FarFieldResidency
                        .columnStateName(state);
                // "Should be drawn and is not" excludes the three
                // resident verdicts, the columns vanilla owns, the
                // columns past L1, and the not-armed sentinel. What
                // remains is exactly the population the owner is asking
                // about.
                missingReason[state] = !com.deds.meshelium.farfield.FarFieldResidency
                                .isResidentState(state)
                        && state != com.deds.meshelium.farfield.FarFieldResidency
                                .COL_NEAR_COVERED
                        && state != com.deds.meshelium.farfield.FarFieldResidency
                                .COL_OUTSIDE_RING
                        && state != com.deds.meshelium.farfield.FarFieldResidency
                                .COL_WALKER_IDLE;
            }
            int[] counts = new int[states];
            int[] sectionHistogram = new int[9];
            int[] sampled = new int[states];
            List<String> samples = new ArrayList<>();
            List<String> innerSamples = new ArrayList<>();
            if (!snap.armed()) {
                return new Census(false, 0, 0, 0, 0, 0, false, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0L,
                        counts, names, missingReason, sectionHistogram, samples,
                        0, 0, innerSamples);
            }
            int l1 = snap.l1();
            int camX = snap.cameraChunkX();
            int camZ = snap.cameraChunkZ();
            int square = 0;
            int wanted = 0;
            int drawn = 0;
            int hollow = 0;
            int innerWanted = 0;
            int innerUndrawn = 0;
            long residentSections = 0L;
            for (int dx = -l1; dx <= l1; dx++) {
                for (int dz = -l1; dz <= l1; dz++) {
                    int cx = camX + dx;
                    int cz = camZ + dz;
                    int state = com.deds.meshelium.farfield.FarFieldResidency
                            .classifyColumn(cx, cz);
                    square++;
                    counts[state]++;
                    if (state == com.deds.meshelium.farfield.FarFieldResidency
                            .COL_NEAR_COVERED
                            || state == com.deds.meshelium.farfield.FarFieldResidency
                                    .COL_OUTSIDE_RING) {
                        continue;
                    }
                    wanted++;
                    // pre21: the inner edge, where the ring meets vanilla's
                    // pad-1 disc. Near-owned counts as drawn here (the near
                    // field draws it), exactly as the flash clock reads it.
                    int d = Math.max(Math.abs(dx), Math.abs(dz));
                    boolean inner = d <= snap.innerRing() + 1;
                    if (inner) {
                        innerWanted++;
                        if (!com.deds.meshelium.farfield.FarFieldResidency
                                .isResidentState(state)
                                && state != com.deds.meshelium.farfield.FarFieldResidency
                                        .COL_NEAR_OWNED) {
                            innerUndrawn++;
                            if (innerSamples.size() < SAMPLES_PER_REASON) {
                                innerSamples.add(names[state] + " " + cx + "," + cz
                                        + " d=" + d);
                            }
                        }
                    }
                    if (com.deds.meshelium.farfield.FarFieldResidency
                            .isResidentState(state)) {
                        drawn++;
                        if (state != com.deds.meshelium.farfield.FarFieldResidency
                                .COL_RESIDENT) {
                            hollow++;
                        }
                        int n = com.deds.meshelium.farfield.FarFieldResidency
                                .residentSectionCount(cx, cz);
                        if (n > 0) {
                            residentSections += n;
                        }
                        sectionHistogram[Math.min(sectionHistogram.length - 1,
                                Math.max(0, n))]++;
                        continue;
                    }
                    if (sampled[state] < SAMPLES_PER_REASON) {
                        sampled[state]++;
                        samples.add(names[state] + " " + cx + "," + cz
                                + " d=" + Math.max(Math.abs(dx), Math.abs(dz)));
                    }
                }
            }
            return new Census(true, camX, camZ, snap.nearEdge(), l1, snap.innerRing(),
                    snap.scanPending(), snap.scanRing(), snap.scanIndex(),
                    snap.residentColumnCount(), snap.inFlightCount(), snap.queuedCount(),
                    snap.absentCount(), snap.partialCount(), snap.refillCount(),
                    snap.latePendingCount(), snap.pendingAdmissionColumns(),
                    snap.pendingAdmissionSections(),
                    square, wanted, drawn, hollow, residentSections,
                    counts, names, missingReason, sectionHistogram, samples,
                    innerWanted, innerUndrawn, innerSamples);
        }

        // --------------------------------------------------------------
        // The near/far handover clock. CLIENT THREAD ONLY: every field
        // below is touched exclusively from sampleFlash / resetFlash /
        // flash, and all three are only ever called inside runOnClient
        // or computeOnClient.
        // --------------------------------------------------------------

        /** Verdict at the previous sample, per column key. */
        private static final Map<Long, Integer> lastState = new HashMap<>();
        /**
         * Columns that have left the disc and are not DRAWN yet:
         * key -> {sampleClock, framesDrawn} at the moment they left.
         */
        private static final Map<Long, long[]> awaiting = new HashMap<>();
        /** Resolved handovers: {sampleGap, frameGap}. */
        private static final List<long[]> gaps = new ArrayList<>();
        private static long sampleClock;
        private static long handovers;
        private static long instantHandovers;
        private static long reenteredDisc;
        // ---- Leg B continuity oracle (seam steps 3-4) ----
        /** Was the column DRAWN (far-resident | bound | near-owned) last sample? */
        private static final Map<Long, Boolean> lastDrawn = new HashMap<>();
        /**
         * Columns observed BOUND at least once in their current
         * far-domain life; reset when a column leaves the ring entirely
         * (OUTSIDE_RING/WALKER_IDLE - a legitimate E1 exit ends the
         * life, and boundness history from a spent life must not indict
         * the next one). The flash acceptance is scoped by this: gaps
         * and censorings on ever-bound ground are invariant breaches,
         * on never-bound ground they are discovery.
         */
        private static final java.util.HashSet<Long> everBound = new java.util.HashSet<>();
        /** The sampleClock the column was last observed at. */
        private static final Map<Long, Long> lastSeenAt = new HashMap<>();
        /** First offenders, capped; scene-fatal when non-empty. */
        private static final List<String> violations = new ArrayList<>();
        private static int prevCamX;
        private static int prevCamZ;
        private static boolean havePrevCam;
        private static long lastReloads = Long.MIN_VALUE;

        static void resetFlash() {
            lastState.clear();
            awaiting.clear();
            gaps.clear();
            sampleClock = 0;
            handovers = 0;
            instantHandovers = 0;
            reenteredDisc = 0;
            lastDrawn.clear();
            lastSeenAt.clear();
            violations.clear();
            everBound.clear();
            havePrevCam = false;
            lastReloads = Long.MIN_VALUE;
        }

        /** Leg B: the continuity offenders recorded so far (client thread). */
        static List<String> continuityViolations() {
            return new ArrayList<>(violations);
        }

        /**
         * One sample of the whole wanted square. Called once per tick by
         * {@code surveyStopSettle}, which is what makes the sample clock
         * a tick clock for the length of a survey.
         *
         * <p>A handover is detected as a VERDICT TRANSITION, from
         * {@code NEAR_COVERED} to anything else. That is the moment
         * vanilla stops being responsible for the column, which is
         * exactly the moment the player can start seeing a hole. Three
         * outcomes, all counted separately because averaging them
         * together is how a handover defect hides:</p>
         * <ul>
         *   <li>already resident at the transition - no flash, gap
         *       zero, and the outcome the far field is FOR;</li>
         *   <li>not resident - the clock starts, and stops when the
         *       column first reports resident;</li>
         *   <li>back inside the disc before resolving - discarded, not
         *       recorded as a fast handover, because the camera turning
         *       round is not the far field succeeding.</li>
         * </ul>
         */
        static void sampleFlash() {
            var snap = com.deds.meshelium.farfield.FarFieldResidency.walkerSnapshot();
            if (!snap.armed()) {
                return;
            }
            sampleClock++;
            long frames = TerrainDrawer.framesDrawn();
            int l1 = snap.l1();
            int camX = snap.cameraChunkX();
            int camZ = snap.cameraChunkZ();
            // The drawn set is the invariant's BOUND set - live
            // resident, retained (held or parked), far - unioned with
            // the walker's residents. The leg's first real run proved
            // anything narrower is wrong: judging from residents +
            // parks alone read the handover band's supersede bind (far
            // copy exchanged for a LIVE copy in one hold, never a frame
            // undrawn) as 23 violations. One O(bound) snapshot per
            // tick, test-cadence only.
            var boundCols = TerrainResidency.boundColumnsSnapshot();
            // Reload guard: a vanilla reload legitimately reshuffles
            // ownership wholesale (and reloadFarField drops the far
            // field by the owner's own instruction), so a sample pair
            // spanning one is not evidence. E3 (world era) cannot occur
            // inside a scene; E2 is asserted zero separately.
            long reloadsNow = com.deds.meshelium.farfield.FarFieldResidency
                    .farVanillaReloads.sum();
            boolean reloadEpoch = lastReloads != Long.MIN_VALUE && reloadsNow != lastReloads;
            lastReloads = reloadsNow;
            for (int dx = -l1; dx <= l1; dx++) {
                for (int dz = -l1; dz <= l1; dz++) {
                    int cx = camX + dx;
                    int cz = camZ + dz;
                    long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
                    int state = com.deds.meshelium.farfield.FarFieldResidency
                            .classifyColumn(cx, cz);
                    // Boxing note: the verdict codes are all inside the
                    // Integer cache, so this put allocates nothing; only
                    // the Long key does, and that is a few kilobytes of
                    // young-gen churn per tick in a diagnostic.
                    Integer previous = lastState.put(key, state);
                    int prev = previous == null ? -1 : previous.intValue();
                    boolean isResident = com.deds.meshelium.farfield.FarFieldResidency
                            .isResidentState(state);
                    boolean drawnNow = isResident || boundCols.contains(key)
                            || state == com.deds.meshelium.farfield.FarFieldResidency
                                    .COL_NEAR_OWNED;
                    if (state == com.deds.meshelium.farfield.FarFieldResidency
                            .COL_OUTSIDE_RING
                            || state == com.deds.meshelium.farfield.FarFieldResidency
                                    .COL_WALKER_IDLE) {
                        // A legitimate whole-life exit (E1's territory, or
                        // no walker at all): spend the boundness memo so a
                        // past life cannot indict the next one.
                        everBound.remove(key);
                    } else if (drawnNow) {
                        everBound.add(key);
                    }
                    // ---- Leg B, the continuity oracle. Judged BEFORE the
                    // flash clock's near-covered early-out so a column
                    // that fell back inside the disc still updates its
                    // drawn memo. A violation is: drawn at the previous
                    // tick, observed again THIS tick (no gap in the
                    // record), comfortably INTERIOR to the far domain at
                    // both ticks (boundary churn is E1's business by
                    // construction), no reload between the ticks - and
                    // not drawn now by anybody. Exactly "a covered,
                    // drawn position became unbound".
                    Long seenAt = lastSeenAt.put(key, sampleClock);
                    Boolean wasDrawn = lastDrawn.put(key, drawnNow);
                    if (!drawnNow && !reloadEpoch
                            && Boolean.TRUE.equals(wasDrawn)
                            && seenAt != null && seenAt == sampleClock - 1
                            && state != com.deds.meshelium.farfield.FarFieldResidency
                                    .COL_NEAR_COVERED
                            && state != com.deds.meshelium.farfield.FarFieldResidency
                                    .COL_OUTSIDE_RING
                            && state != com.deds.meshelium.farfield.FarFieldResidency
                                    .COL_WALKER_IDLE) {
                        int ringNow = Math.max(Math.abs(dx), Math.abs(dz));
                        boolean interiorNow = ringNow >= snap.innerRing() + 2
                                && ringNow <= l1 - 2;
                        boolean interiorPrev = havePrevCam
                                && Math.max(Math.abs(cx - prevCamX),
                                        Math.abs(cz - prevCamZ)) >= snap.innerRing() + 2
                                && Math.max(Math.abs(cx - prevCamX),
                                        Math.abs(cz - prevCamZ)) <= l1 - 2;
                        if (interiorNow && interiorPrev && violations.size() < 32) {
                            violations.add("(" + cx + "," + cz + ") state="
                                    + com.deds.meshelium.farfield.FarFieldResidency
                                            .columnStateName(state)
                                    + " ring=" + ringNow + " tick=" + sampleClock
                                    + " evictedWall=" + TerrainResidency.ledgerEvictedWall()
                                    + " uncovered=" + TerrainResidency.ledgerResolvedUncovered()
                                    + " parked=" + TerrainResidency.ledgerParked());
                        }
                    }
                    // ---- The flash clock, on the DRAWN set.
                    if (state == com.deds.meshelium.farfield.FarFieldResidency
                            .COL_NEAR_COVERED) {
                        if (awaiting.remove(key) != null) {
                            reenteredDisc++;
                        }
                        continue;
                    }
                    if (prev == com.deds.meshelium.farfield.FarFieldResidency
                            .COL_NEAR_COVERED) {
                        handovers++;
                        if (drawnNow) {
                            instantHandovers++;
                            gaps.add(new long[] {0L, 0L, 1L});
                        } else {
                            awaiting.put(key, new long[] {sampleClock, frames,
                                    everBound.contains(key) ? 1L : 0L});
                        }
                        continue;
                    }
                    long[] started = awaiting.get(key);
                    if (started != null && drawnNow) {
                        gaps.add(new long[] {sampleClock - started[0],
                                frames - started[1], started[2]});
                        awaiting.remove(key);
                    }
                }
            }
            prevCamX = camX;
            prevCamZ = camZ;
            havePrevCam = true;
        }

        /** The distribution, or a {@code measured=false} shell in corridor mode. */
        static Flash flash(boolean surveying) {
            int censoredBound = 0;
            for (long[] started : awaiting.values()) {
                if (started[2] != 0) {
                    censoredBound++;
                }
            }
            int boundGaps = 0;
            for (long[] gap : gaps) {
                if (gap[0] > 0 && gap[2] != 0) {
                    boundGaps++;
                }
            }
            if (!surveying || sampleClock == 0) {
                return new Flash(false, sampleClock, handovers, instantHandovers,
                        reenteredDisc, gaps.size(), awaiting.size(),
                        censoredBound, boundGaps,
                        -1, -1, -1, -1, -1, -1, -1, -1);
            }
            long[] frameGaps = new long[gaps.size()];
            long[] tickGaps = new long[gaps.size()];
            for (int i = 0; i < gaps.size(); i++) {
                tickGaps[i] = gaps.get(i)[0];
                frameGaps[i] = gaps.get(i)[1];
            }
            Arrays.sort(frameGaps);
            Arrays.sort(tickGaps);
            return new Flash(true, sampleClock, handovers, instantHandovers,
                    reenteredDisc, gaps.size(), awaiting.size(),
                    censoredBound, boundGaps,
                    nearestRank(frameGaps, 0.0), nearestRank(frameGaps, 0.5),
                    nearestRank(frameGaps, 0.95), nearestRank(frameGaps, 1.0),
                    nearestRank(tickGaps, 0.0), nearestRank(tickGaps, 0.5),
                    nearestRank(tickGaps, 0.95), nearestRank(tickGaps, 1.0));
        }

        /** A one-line progress form for the survey log. */
        static String flashLine() {
            return "flashSamples=" + sampleClock + " handovers=" + handovers
                    + " alreadyResident=" + instantHandovers
                    + " resolved=" + gaps.size() + " stillWaiting=" + awaiting.size();
        }
    }

    /**
     * Nearest-rank percentile over a SORTED array, with 0.0 meaning the
     * minimum and 1.0 the maximum. Chosen over an interpolating
     * percentile because a handover gap is a whole number of frames and
     * a fractional answer would be inventing precision the sampler does
     * not have.
     */
    private static long nearestRank(long[] sorted, double quantile) {
        if (sorted.length == 0) {
            return -1;
        }
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.length) {
            index = sorted.length - 1;
        }
        return sorted[index];
    }

    // ------------------------------------------------------------------
    // Helpers (shaped like MesheliumFarFieldTest's, so the two files
    // read side by side)
    // ------------------------------------------------------------------

    /**
     * Flip the master switch through the PROPERTY, which outranks the
     * config field and is re-read per call, then do what the Far Terrain
     * screen does and refresh the extraction seams. Copied from
     * {@code MesheliumFarFieldTest.armFarField}: without the refresh the
     * chunk-lifecycle mixins keep their boot-time answer, a mid-session
     * enable never reaches them, nothing is ever extracted, and every
     * scene would die at the store-write wait with no clue why.
     */
    private static void armFarField(ClientGameTestContext context, boolean armed) {
        context.runOnClient(client -> {
            if (armed) {
                System.setProperty("meshelium.farfield.enabled", "true");
            } else {
                System.clearProperty("meshelium.farfield.enabled");
            }
            ExtractDispatch.refreshArmed();
        });
    }

    /**
     * Pin the layer-1 radius, both halves of it: the radius property and
     * the layer switch it is gated behind. {@code layerRadiusChunks}
     * applies the layer's own on/off AFTER the override, so a config
     * file with layer 1 disabled would answer 0 and the whole diagnostic
     * would photograph an empty ring. Verified rather than assumed - the
     * resolver is the same one the walker calls.
     */
    private static void setFarRadius(ClientGameTestContext context, int chunks) {
        context.runOnClient(client -> {
            System.setProperty("meshelium.farfield.l1Enabled", "true");
            System.setProperty("meshelium.farfield.l1RadiusChunks",
                    Integer.toString(chunks));
        });
        if (FarFieldConfig.l1RadiusChunks() != chunks) {
            throw new AssertionError("the far radius did not take: asked for " + chunks
                    + " chunks, FarFieldConfig.l1RadiusChunks() answers "
                    + FarFieldConfig.l1RadiusChunks());
        }
    }

    /** Best-effort drain between scenes; bounded, and never fatal on its own. */
    private static void waitForFarDrain(ClientGameTestContext context) {
        for (int i = 0; i < 60; i++) {
            if (FarWalkerProbe.resident() == 0) {
                return;
            }
            context.waitTicks(20);
        }
        log("the far ring did not fully drain between scenes (still "
                + FarWalkerProbe.resident() + " resident); the next scene's residency "
                + "GAUGE is therefore not proof on its own, and its ADMISSIONS delta is");
    }

    /**
     * {@code execute as @p at @s run tp @s ...} with an ABSOLUTE height.
     * Absolute on purpose: a relative {@code ~} height climbs the camera
     * on every restore and the two halves of an A/B stop being a pair.
     */
    private static String tpCommand(int x, double y, int z, String yaw, String pitch) {
        return "execute as @p at @s run tp @s " + x + " " + formatY(y) + " " + z
                + " " + yaw + " " + pitch;
    }

    private static void teleport(TestServerContext server, int x, double y, int z,
            String yaw, String pitch) {
        server.runCommand(tpCommand(x, y, z, yaw, pitch));
    }

    /** Command-safe decimal, root locale (a comma would split the argument). */
    private static String formatY(double y) {
        return String.format(Locale.ROOT, "%.1f", y);
    }

    /**
     * SEAM leg B (steps 3-4): the travel-leg acceptance, run at the end
     * of every scene. This is the design's gametest leg B in the class
     * the standing entrypoint-staleness rule allows (no new test class):
     * the continuity oracle sampled between ticks during the walk, plus
     * the CENSUS-FLASH acceptance wired as an ASSERTION in survey mode
     * rather than a printout, plus the vacuity defenses that stop a run
     * from going green without the transitions having run at all.
     *
     * <p>What each check means:</p>
     * <ul>
     *   <li><b>continuity violations empty</b> - no far-domain column
     *       that was DRAWN (bound: live, retained or far) ever became undrawn
     *       while still comfortably inside the domain. Boundary columns
     *       are excluded by the oracle itself (their releases are E1 by
     *       construction), reload samples are skipped (the oracle reads
     *       the reload counter per tick), and E2 is separately asserted
     *       zero - so within a scene the violation set must be EMPTY,
     *       which is exactly the invariant with its three exits
     *       accounted;</li>
     *   <li><b>ledgerParked and ledgerResolvedSwap moved</b> - proof the
     *       transitions actually ran (the vacuous-green defense: a walk
     *       that parked nothing tested nothing);</li>
     *   <li><b>ledgerEvictedWall did not move</b> - the AWAITING budget
     *       is sized so this test never reaches E2; a wall eviction here
     *       is a real regression, not noise;</li>
     *   <li><b>ledgerDemoted == 0 and ledgerIllegalTransitions == 0</b> -
     *       the dead demote edge stayed dead and the legal table held;</li>
     *   <li><b>survey mode: handovers &gt; 0, boundGaps == 0,
     *       censoredBound == 0</b> - the CENSUS-FLASH acceptance,
     *       scoped to the INVARIANT (the leg's first-run lesson: an
     *       assertion stricter than its invariant is a bug in the
     *       assertion). A column that was ever BOUND in its current
     *       far-domain life must exit atomically - park at the free,
     *       swap or supersede at the bind - so any measured gap or
     *       censoring on ever-bound ground is a free that dodged the
     *       park. NEVER-bound ground (first visit: vanilla never
     *       compiled it, the store may have nothing) is discovery's
     *       population, explicitly out of the invariant's scope by the
     *       design's own construction; its gaps and censorings are
     *       REPORTED (CENSUS-FLASH-SCOPE / the distribution) and not
     *       asserted. The whole-population p95 stays in the log as the
     *       player-experience number.</li>
     * </ul>
     */
    private static void assertSeamLegB(ClientGameTestContext context, Scene scene,
            boolean surveying, Map<String, Long> before, Flash f) {
        List<String> violations =
                context.computeOnClient(client -> FarWalkerProbe.continuityViolations());
        if (!violations.isEmpty()) {
            throw new AssertionError("scene " + scene.id() + ": " + violations.size()
                    + " far-domain column(s) that were drawn became undrawn with no "
                    + "E1/E2/E3 exit to account for it - the coverage-continuity "
                    + "invariant is broken. First offenders: " + violations);
        }
        Map<String, Long> now = counterSnapshot(context);
        long parked = sceneDelta(before, now, "ledgerParked");
        long swaps = sceneDelta(before, now, "ledgerResolvedSwap");
        long walled = sceneDelta(before, now, "ledgerEvictedWall");
        if (parked <= 0) {
            throw new AssertionError("scene " + scene.id() + ": ledgerParked never moved "
                    + "- the walk freed a corridor of covered terrain and nothing "
                    + "parked, so the continuity leg tested nothing (vacuous green)");
        }
        if (swaps <= 0) {
            throw new AssertionError("scene " + scene.id() + ": ledgerResolvedSwap never "
                    + "moved - no parked hold was ever resolved by the atomic exchange, "
                    + "so the seam's one legal ending never ran (vacuous green)");
        }
        if (walled != 0) {
            throw new AssertionError("scene " + scene.id() + ": ledgerEvictedWall moved by "
                    + walled + " - the AWAITING quad budget is sized so this test never "
                    + "hits E2; a wall eviction here is a real regression");
        }
        long demoted = now.getOrDefault("ledgerDemoted", 0L);
        long illegal = now.getOrDefault("ledgerIllegalTransitions", 0L);
        if (demoted != 0 || illegal != 0) {
            throw new AssertionError("scene " + scene.id() + ": ledgerDemoted=" + demoted
                    + " ledgerIllegalTransitions=" + illegal + " - both are structurally "
                    + "zero since seam step 4 (dead demote edge; legal-table contract)");
        }
        if (surveying && f.measured()) {
            if (f.handovers() <= 0) {
                throw new AssertionError("scene " + scene.id() + ": the survey observed "
                        + "no handovers at all - the flash clock sampled a walk in which "
                        + "no column ever left vanilla's disc, which is not a survey "
                        + "(vacuous green)");
            }
            if (f.censoredBound() != 0) {
                throw new AssertionError("scene " + scene.id() + ": CENSUS-FLASH "
                        + "censoredBound=" + f.censoredBound() + " - columns that WERE "
                        + "bound left vanilla's disc and were never once observed drawn "
                        + "again. The invariant-scoped acceptance is zero: bound ground "
                        + "exits atomically or not at all, so this is a free that dodged "
                        + "the park. (Total censored=" + f.censored() + "; the never-"
                        + "bound remainder is discovery and is reported, not asserted)");
            }
            if (f.boundGaps() != 0) {
                throw new AssertionError("scene " + scene.id() + ": CENSUS-FLASH "
                        + "boundGaps=" + f.boundGaps() + " - ever-bound columns showed a "
                        + "nonzero drawn-ness gap after leaving vanilla's disc. Bound "
                        + "ground must keep drawing through the park and exit only by "
                        + "swap/supersede/E1/E2/E3, so any measured gap here is a free "
                        + "that took the plain path at a covered position. (Whole-"
                        + "population tick p95=" + f.tickP95() + " median="
                        + f.tickMedian() + " max=" + f.tickMax()
                        + " - the never-bound share is discovery)");
            }
        }
    }

    /**
     * The corridor's leg-B settle: {@link #settleLoadedChunks}'s
     * observable and stability rule (chunk count stable across two
     * consecutive 20-tick checks, two-minute budget, FATAL on timeout),
     * walked one tick at a time with a continuity/flash sample per tick.
     * The survey has its own per-tick settle; this one is what makes the
     * corridor walk - the shape a travelling player actually produces -
     * observable between ticks instead of in 20-tick blocks.
     */
    private static void settleLoadedChunksSampling(ClientGameTestContext context) {
        int last = -1;
        int stable = 0;
        for (int tick = 0; tick < 2400; tick++) {
            context.waitTicks(1);
            context.runOnClient(client -> FarWalkerProbe.sampleFlash());
            if (tick % 20 != 19) {
                continue;
            }
            int now = context.computeOnClient(client -> client.level == null ? -1
                    : client.level.getChunkSource().getLoadedChunksCount());
            if (now > 0 && now == last) {
                if (++stable >= 2) {
                    return;
                }
            } else {
                stable = 0;
            }
            last = now;
        }
        throw new AssertionError("the client's loaded-chunk count never settled after a "
                + "teleport (last " + last + ") - the world is still streaming");
    }

    /**
     * Wait until the client's loaded-chunk count stops moving.
     *
     * <p>120 iterations of 20 ticks, which is two minutes of budget and
     * deliberately generous. Every stop in this run teleports into
     * terrain that has never been generated, and a noise world at rd
     * {@value #NEAR_RD} has 289 columns to make each time; the loop
     * returns after 40 ticks when the world is already there, so the
     * budget only costs anything on the stops that actually need it.</p>
     */
    private static void settleLoadedChunks(ClientGameTestContext context) {
        int last = -1;
        for (int i = 0; i < 120; i++) {
            int now = context.computeOnClient(client -> client.level == null ? -1
                    : client.level.getChunkSource().getLoadedChunksCount());
            if (now > 0 && now == last) {
                return;
            }
            last = now;
            context.waitTicks(20);
        }
        throw new AssertionError("the client's loaded-chunk count never settled after a "
                + "teleport (last " + last + ") - the world is still streaming");
    }

    /**
     * The draw test's quiesce: the near-field build pipeline flat and the
     * staging queue empty, so a screenshot is of a finished frame rather
     * than of one still filling in. Non-fatal: a photograph of a
     * half-built frame is still worth having, as long as the log says so.
     */
    private static void quiesceResidency(ClientGameTestContext context) {
        for (int i = 0; i < 30; i++) {
            long before = TerrainResidency.counters().encodedSections();
            context.waitTicks(20);
            TerrainResidency.Counters after = TerrainResidency.counters();
            if (after.encodedSections() == before && after.stagingBacklogEntries() == 0) {
                return;
            }
        }
        log("the near-field build pipeline never went quiet; the screenshot that follows "
                + "may show terrain still filling in");
    }

    /**
     * Wait for the far resident gauge to stop moving and return it. The
     * walker only re-arms on a camera crossing or a slider change, so
     * with a pinned camera it always converges; this bounds how long a
     * scene will wait for that, and returns the LAST OBSERVED value
     * rather than a sentinel when it does not.
     */
    private static long settleFarResident(ClientGameTestContext context) {
        long last = -1;
        for (int i = 0; i < 40; i++) {
            context.waitTicks(20);
            long now = FarWalkerProbe.resident();
            if (now == last) {
                return now;
            }
            last = now;
        }
        return FarWalkerProbe.resident();
    }

    /**
     * The deterministic scene, with the 26.2 gamerule names. The old
     * camelCase names fail to parse and the freeze silently never
     * happens: harmless on plains, fatal over an ocean, where kelp and
     * seagrass random-tick forever and the world never settles.
     */
    private static void freezeWorld(TestServerContext server) {
        server.runCommand("time set noon");
        server.runCommand("gamerule minecraft:advance_time false");
        server.runCommand("weather clear");
        server.runCommand("gamerule minecraft:advance_weather false");
        server.runCommand("gamerule minecraft:spawn_mobs false");
        server.runCommand("gamerule minecraft:random_tick_speed 0");
        server.runCommand("kill @e[type=!minecraft:player]");
    }

    // ------------------------------------------------------------------
    // S6: the fly-up ladder
    // ------------------------------------------------------------------

    /**
     * One rung's reading, kept so the summary can be read as a SERIES.
     * A single rung's numbers cannot separate "the arm never fired" from
     * "the arm fired and did not suffice"; the shape of the series across
     * the climb can.
     */
    private record Rung(int index, int y, int camSy, int topSy, int loSy,
            int horizon, int band, int core, long bind, long farResident,
            boolean asserted,
            TerrainResidency.CoverageAudit arrival,
            TerrainResidency.CoverageAudit settled) {
    }

    /** pre21: the two ladder passes. */
    private enum LadderPass {
        /** The far field out of the frame: a shell cannot hide a near hole. */
        FAR_OFF("far-off", "L%02d_flyup_y%d"),
        /** The owner's scene: a tiny near disc inside a full LOD horizon. */
        FAR_ON("far-on", "L%02d_flyup_far_y%d");

        final String tag;
        final String shot;

        LadderPass(String tag, String shot) {
            this.tag = tag;
            this.shot = shot;
        }
    }

    /**
     * pre21: the rd-scaled vacuity floor. See
     * {@link #LADDER_MIN_EXPECTED_DIVISOR}.
     */
    private static int ladderMinExpected() {
        return Math.max(LADDER_MIN_EXPECTED_FLOOR,
                TerrainResidency.vanillaCompileDiscColumns(LADDER_RD)
                        / LADDER_MIN_EXPECTED_DIVISOR);
    }

    /**
     * Climb a mixed scene in {@value #LADDER_STEP}-block rungs with
     * occlusion off and assert, at every rung, that nothing resident and
     * on screen is drawn by nobody — twice: once with the far field out
     * of the frame, once with it armed over a primed store (pre21, the
     * owner's own scene).
     *
     * <p>See the constant block at the head of this class for why each
     * element of the setup is there. The one thing worth repeating here:
     * the assertion is a SET DIFFERENCE
     * ({@code TerrainResidency.auditUnlistedCoverage}), not a counter.
     * Every counter in this renderer is a cardinality of something we
     * hold, and {@code unlistedBandDrawn} in particular is HIGH exactly
     * when a fix ran, whether or not the fix sufficed — which is how five
     * fixes shipped against a defect none of them closed.</p>
     */
    private static void flyUpLadder(ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        TestServerContext server = singleplayer.getServer();
        log("LADDER begin rd=" + LADDER_RD + " minExpected=" + ladderMinExpected()
                + " far=" + LADDER_FAR + " primeRd=" + LADDER_FAR_PRIME_RD);
        // Pass A: the far field must be out of the frame entirely. A shell
        // drawn over a near hole is a clean picture and a live defect.
        armFarField(context, false);
        waitForFarDrain(context);
        context.runOnClient(client ->
                System.setProperty("meshelium.terrainDraw.bfsOnly", "true"));
        try {
            pinLadderRenderDistance(context, LADDER_RD);
            Relief scene = findMixedScene(server);
            runLadder(context, server, scene, LadderPass.FAR_OFF);
            if (LADDER_FAR) {
                // Pass B: the same scene, the same rd, the same expectation
                // set - with the LOD horizon behind it. The owner's report
                // was made this way, and the contrast is what made a
                // partial fix look like a layer.
                primeFarField(context, server, scene);
                runLadder(context, server, scene, LadderPass.FAR_ON);
            }
        } finally {
            armFarField(context, false);
            context.runOnClient(client -> {
                System.clearProperty("meshelium.terrainDraw.bfsOnly");
                System.clearProperty(
                        "meshelium.drawUnlistedLive.altitude");
                client.options.renderDistance().set(NEAR_RD);
                client.options.save();
            });
        }
    }

    /**
     * Set the render distance, wait for it to take, and re-prove that
     * occlusion culling is OFF at it: the BFS visibility feed is the only
     * path this defect lives on, and a pin that silently came undone
     * would have the whole leg measuring the wrong renderer.
     */
    private static void pinLadderRenderDistance(ClientGameTestContext context, int rd) {
        context.runOnClient(client -> {
            client.options.renderDistance().set(rd);
            client.options.save();
        });
        context.waitFor(client -> client.options.getEffectiveRenderDistance() == rd,
                RD_TIMEOUT_TICKS);
        boolean occlusionOff = context.computeOnClient(client ->
                !MesheliumConfig.occlusionCullingEnabled(
                        client.options.getEffectiveRenderDistance()));
        if (!occlusionOff) {
            throw new AssertionError("the ladder could not pin occlusion culling OFF at rd "
                    + rd + " (meshelium.terrainDraw.bfsOnly=true did not take). The BFS "
                    + "visibility feed is the only path this defect lives on, so the "
                    + "whole leg would be measuring the wrong renderer");
        }
    }

    /**
     * pre21, pass B's scene construction: the owner's "rd 2 with LOD on".
     *
     * <p>A fresh harness world has no LOD horizon: the store holds only
     * what live extraction has walked, and at rd 2 that is the 7x7 the
     * server sends. The owner's horizon exists because he has played the
     * area at a larger render distance. So: arm the far field, load the
     * scene at {@link #LADDER_FAR_PRIME_RD} (live extraction fills the
     * store with the loaded square), wait for the store writes to stop,
     * then drop to {@link #LADDER_RD}. The drop is a vanilla reload; the
     * far field stands down, re-arms with {@code coverRadius(rd)} and
     * refills the vacated rings from pins and the store. That refill is
     * the collapsed handover band's first test, and
     * {@link #settleFarResident} is what waits for it.</p>
     *
     * <p>The flash clock is reset AFTER the settle, deliberately: the
     * drop itself is a reload storm and the clock's accounting across a
     * reload epoch is guarded only for the continuity oracle. What pass B
     * measures is the climb and its A/B nudges — the only horizontal
     * steps a climb has, and at rd 2 each one hands five live-bound
     * columns to the far field across a zero-width band.</p>
     */
    private static void primeFarField(ClientGameTestContext context,
            TestServerContext server, Relief scene) {
        String yaw = Integer.toString(scene.yaw());
        log("LADDER far-on: arming the far field and priming the store at rd "
                + LADDER_FAR_PRIME_RD + " over " + scene.x() + "," + scene.z()
                + ", then dropping to rd " + LADDER_RD);
        armFarField(context, true);
        if (LADDER_FAR_PRIME_RD > LADDER_RD) {
            pinLadderRenderDistance(context, LADDER_FAR_PRIME_RD);
            teleport(server, scene.x(), TRAVEL_Y, scene.z(), yaw, "0");
            settleLoadedChunks(context);
            long writes = settleFarStoreWrites(context);
            log("LADDER far-on: store writes settled at " + writes
                    + " (farResident=" + FarWalkerProbe.resident() + " at rd "
                    + LADDER_FAR_PRIME_RD + ")");
            pinLadderRenderDistance(context, LADDER_RD);
            settleLoadedChunks(context);
        }
        try {
            context.waitFor(client -> FarWalkerProbe.resident() > 0, FAR_TIMEOUT_TICKS);
        } catch (Throwable t) {
            throw new AssertionError("pass far-on: the far field never became resident at rd "
                    + LADDER_RD + " after priming at rd " + LADDER_FAR_PRIME_RD + ": "
                    + FarWalkerProbe.describe(), t);
        }
        long resident = settleFarResident(context);
        Census c = takeCensus(context);
        log("LADDER far-on: far ring settled at " + resident + " sections; "
                + censusLine(c));
        if (!c.innerSamples().isEmpty()) {
            log("LADDER far-on: inner-ring undrawn after prime: " + c.innerSamples());
        }
        context.runOnClient(client -> FarWalkerProbe.resetFlash());
    }

    /** Wait for the far store's write counter to stop moving; bounded. */
    private static long settleFarStoreWrites(ClientGameTestContext context) {
        long last = -1;
        for (int i = 0; i < 90; i++) {
            context.waitTicks(20);
            long now = FarField.farStoreWrites.sum();
            if (now == last) {
                return now;
            }
            last = now;
        }
        log("the far store's writes never stopped moving while priming (last " + last
                + "); the far-on pass runs against a store still filling in");
        return FarField.farStoreWrites.sum();
    }

    /** One compact census line for the ladder's log. */
    private static String censusLine(Census c) {
        if (!c.armed()) {
            return "CENSUS armed=false";
        }
        StringBuilder states = new StringBuilder();
        for (int state = 0; state < c.stateCounts().length; state++) {
            if (c.stateCounts()[state] == 0) {
                continue;
            }
            if (states.length() > 0) {
                states.append(' ');
            }
            states.append(c.stateNames()[state]).append('=').append(c.stateCounts()[state]);
        }
        return "CENSUS nearEdge=" + c.nearEdge() + " innerRing=" + c.innerRing()
                + " l1=" + c.l1() + " wanted=" + c.wantedColumns()
                + " drawn=" + c.drawnColumns()
                + " notDrawn=" + (c.wantedColumns() - c.drawnColumns())
                + " inner[wanted=" + c.innerWanted() + " undrawn=" + c.innerUndrawn()
                + "] states[" + states + "]";
    }

    private static void runLadder(ClientGameTestContext context, TestServerContext server,
            Relief scene, LadderPass pass) {
        int cameraX = scene.x();
        int cameraZ = scene.z();
        String yaw = Integer.toString(scene.yaw());
        boolean far = pass == LadderPass.FAR_ON;
        log("LADDER scene " + scene + " yaw=" + yaw + " pass=" + pass.tag);

        teleport(server, cameraX, TRAVEL_Y, cameraZ, yaw, "0");
        settleLoadedChunks(context);
        int surface = surfaceY(server, cameraX, cameraZ);
        double baseY = surface + 2.0;
        int minExpected = ladderMinExpected();
        log("LADDER base y=" + formatY(baseY) + " (surface " + surface + ") rd=" + LADDER_RD
                + " minExpected=" + minExpected + " pass=" + pass.tag);

        List<Rung> rungs = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> farFailures = new ArrayList<>();
        int asserted = 0;
        int mixedWitness = 0;
        long absentWithFixOff = 0;
        int abRungsRun = 0;

        for (int i = 0; i < LADDER_RUNGS; i++) {
            double y = baseY + i * LADDER_STEP;
            String pitch = ladderPitch(y - surface);
            String pose = tpCommand(cameraX, y, cameraZ, yaw, pitch);
            server.runCommand(pose);
            ladderWait(context, LADDER_ARRIVE_TICKS);
            TerrainResidency.CoverageAudit arrival = coverageAudit(context);
            ladderWait(context, LADDER_SETTLE_TICKS);
            quiesceLadder(context);
            server.runCommand(pose); // undo any drift; the pose is the A/B
            ladderWait(context, 5);
            TerrainResidency.CoverageAudit settled = coverageAudit(context);

            int camSy = cameraSectionY(context);
            int topSy = TerrainResidency.unlistedTerrainTopSy();
            int loSy = TerrainResidency.unlistedTerrainLowSy();
            int horizon = TerrainResidency.unlistedHorizonEntries();
            int band = TerrainResidency.unlistedBandDrawn();
            int core = TerrainResidency.unlistedBandCoreDrawn();
            long bind = TerrainResidency.unlistedBindMarks();
            long farResident = far ? FarWalkerProbe.resident() : 0L;
            boolean assertable = settled.expected() >= minExpected;
            if (assertable) {
                asserted++;
            }
            // THE MIXED-SCENE WITNESS. p90 at or above the camera means the
            // arm S2 shipped would have been DOWN at this rung — i.e. this
            // rung is inside the configuration five fixes never covered. A
            // ladder that never sees it is a ladder over uniform terrain.
            boolean p90Down = topSy != Integer.MIN_VALUE && topSy >= camSy;
            if (p90Down && assertable) {
                mixedWitness++;
            }
            rungs.add(new Rung(i, (int) Math.round(y), camSy, topSy, loSy, horizon,
                    band, core, bind, farResident, assertable, arrival, settled));
            log("LADDER rung=" + i + " y=" + formatY(y) + " pitch=" + pitch
                    + " camSy=" + camSy
                    + " topSy=" + topSy + " loSy=" + loSy
                    + " p90ArmWouldBeDown=" + p90Down
                    + " horizon=" + horizon + " band=" + band + " core=" + core
                    + " bind=" + bind
                    + (far ? " farResident=" + farResident : "")
                    + " arrival[" + arrival + "]"
                    + " settled[" + settled + "]"
                    + (assertable ? "" : " NOT-ASSERTED(thin)")
                    + " pass=" + pass.tag);
            if (!settled.samples().isEmpty()) {
                log("LADDER rung=" + i + " ABSENT sections: " + settled.samples()
                        + " pass=" + pass.tag);
            }
            context.takeScreenshot(TestScreenshotOptions.of(
                    String.format(Locale.ROOT, pass.shot, i, Math.round(y))));

            if (assertable && settled.absent() > 0) {
                failures.add("rung " + i + " (y " + formatY(y) + ", camSy " + camSy
                        + "): " + settled.absent() + " of " + settled.expected()
                        + " expected sections drawn by NOBODY - "
                        + settled + " :: " + settled.samples());
            }

            boolean abRung = contains(LADDER_AB_RUNGS, i);
            if (abRung) {
                abRungsRun++;
                absentWithFixOff += ladderFixOffAudit(context, server, cameraX, y,
                        cameraZ, yaw, pitch, i, pass);
            }
            // pre21, the far side of pass B: the inner ring must be drawn
            // at every census. On a pure climb the ring never moves, so a
            // census at the first rung, the A/B rungs (after their
            // nudges) and the last rung is the whole story.
            if (far && (i == 0 || abRung || i == LADDER_RUNGS - 1)) {
                Census c = takeCensus(context);
                log("LADDER rung=" + i + " " + censusLine(c) + " pass=" + pass.tag);
                if (c.innerUndrawn() > 0) {
                    farFailures.add("rung " + i + ": " + c.innerUndrawn() + " of "
                            + c.innerWanted() + " far columns within Chebyshev "
                            + (c.innerRing() + 1) + " of the camera are wanted and not "
                            + "drawn - " + c.innerSamples());
                }
            }
        }

        // --- the verdict first, then the three vacuity defenses ---
        // Order matters: a real hole is the answer whatever else is
        // thin, and a vacuity message thrown over it would read as
        // "the harness is broken" when the harness had just worked.
        if (!failures.isEmpty()) {
            throw new AssertionError("THE FLY-UP HOLE IS STILL OPEN (pass " + pass.tag
                    + "). Resident, in-frustum, in-disc sections at or below the camera "
                    + "and past the local horizon - core and ring alike - that neither "
                    + "vanilla's visibleSections nor any Meshelium mask draws:\n  "
                    + String.join("\n  ", failures)
                    + "\nSeries: " + summary(rungs));
        }
        if (far) {
            assertLadderFarSide(context, pass, farFailures);
        }
        if (asserted < LADDER_MIN_ASSERTED) {
            throw new AssertionError("the ladder (pass " + pass.tag + ") could only assert "
                    + asserted + " of " + LADDER_RUNGS + " rungs (needed "
                    + LADDER_MIN_ASSERTED + "): at the others fewer than " + minExpected
                    + " sections were resident, in frustum, in the disc and past the "
                    + "local horizon, so an empty difference there proves nothing. The "
                    + "scene is too small or the near field never filled in. Series: "
                    + summary(rungs));
        }
        if (mixedWitness == 0) {
            throw new AssertionError("no rung of the ladder (pass " + pass.tag + ") had the "
                    + "resident p90 at or above the camera's section, so the scene was NOT "
                    + "mixed and this run never entered the configuration the S2 arm could "
                    + "not see (an ocean beside a mountain). The relief locator picked "
                    + cameraX + "," + cameraZ + "; a uniform scene cannot reproduce this "
                    + "defect and a green here would mean nothing");
        }
        if (abRungsRun > 0 && absentWithFixOff == 0) {
            throw new AssertionError("with -Dmeshelium.drawUnlistedLive.altitude=false the "
                    + "ladder (pass " + pass.tag + ") still found ZERO undrawn expected "
                    + "sections across " + abRungsRun + " A/B rungs. That means vanilla "
                    + "listed everything at this pose and the ladder is not exercising the "
                    + "defect at all - a green run would be measuring nothing. Move the "
                    + "scene or the pitch");
        }
        log("LADDER PASSED (" + pass.tag + "): " + asserted + "/" + LADDER_RUNGS
                + " rungs asserted, " + mixedWitness
                + " of them with the retired p90 arm down, "
                + absentWithFixOff + " undrawn sections found across " + abRungsRun
                + " fix-off A/B rungs and 0 with the fix on");
    }

    /**
     * pre21: pass B's far-side verdict. Three things, in the order of
     * their weight: the inner ring drawn at every census (a second hole
     * in the owner's picture would be here, where the collapsed handover
     * band meets vanilla's pad-1 disc); the continuity oracle empty over
     * the climb (no drawn far-domain column became undrawn); and the
     * flash clock's invariant-scoped numbers zero across the A/B nudges
     * (bound ground exits atomically). The nudges are the only handovers
     * a climb produces, so their count is REPORTED beside the numbers:
     * zero handovers would make the last two vacuous, and the log says
     * so rather than the assertion pretending otherwise.
     */
    private static void assertLadderFarSide(ClientGameTestContext context, LadderPass pass,
            List<String> farFailures) {
        Flash f = context.computeOnClient(client -> FarWalkerProbe.flash(true));
        List<String> violations =
                context.computeOnClient(client -> FarWalkerProbe.continuityViolations());
        log("LADDER FAR-SIDE (" + pass.tag + "): samples=" + f.samples()
                + " handovers=" + f.handovers() + " alreadyDrawn=" + f.instant()
                + " reentered=" + f.reentered() + " resolved=" + f.resolved()
                + " censored=" + f.censored()
                + " boundGaps=" + f.boundGaps() + " censoredBound=" + f.censoredBound()
                + " continuityViolations=" + violations.size()
                + (f.handovers() == 0 ? " (no handover observed: the flash numbers are "
                        + "vacuous on a pure climb; the A/B nudges are the only horizontal "
                        + "steps)" : ""));
        if (!farFailures.isEmpty()) {
            throw new AssertionError("THE FAR SIDE HAS A HOLE AT THE INNER EDGE (pass "
                    + pass.tag + ", rd " + LADDER_RD + "): the far ring did not meet "
                    + "vanilla's pad-1 disc:\n  " + String.join("\n  ", farFailures)
                    + "\nRead the state names: NEVER_SCANNED/ABSENT on a first-visit "
                    + "column is the prime not reaching it (discovery); anything else on a "
                    + "column that was live at the prime rd is the collapsed handover band "
                    + "losing a column it was bound to keep");
        }
        if (!violations.isEmpty()) {
            throw new AssertionError("pass " + pass.tag + ": " + violations.size()
                    + " far-domain column(s) that were drawn became undrawn during the "
                    + "climb with no E1/E2/E3 exit - the coverage-continuity invariant is "
                    + "broken at rd " + LADDER_RD + ". First offenders: " + violations);
        }
        if (f.censoredBound() != 0) {
            throw new AssertionError("pass " + pass.tag + ": CENSUS-FLASH censoredBound="
                    + f.censoredBound() + " - columns that WERE bound left vanilla's disc "
                    + "on an A/B nudge and were never once observed drawn again. Bound "
                    + "ground exits atomically or not at all; at rd " + LADDER_RD
                    + " the handover band is " + "zero chunks wide and this is it failing");
        }
        if (f.boundGaps() != 0) {
            throw new AssertionError("pass " + pass.tag + ": CENSUS-FLASH boundGaps="
                    + f.boundGaps() + " - ever-bound columns showed a nonzero drawn-ness "
                    + "gap after leaving vanilla's disc on an A/B nudge (rd " + LADDER_RD
                    + ", zero-width handover band). Whole-population tick p95="
                    + f.tickP95() + " median=" + f.tickMedian() + " max=" + f.tickMax());
        }
    }

    /**
     * pre21: one wait, sampled per tick for the far-side oracles. Free
     * when the far field is off ({@code sampleFlash} returns on an
     * unarmed walker), and what makes pass B's continuity verdict a
     * per-tick observation rather than a 20-tick one.
     */
    private static void ladderWait(ClientGameTestContext context, int ticks) {
        for (int i = 0; i < ticks; i++) {
            context.waitTicks(1);
            context.runOnClient(client -> FarWalkerProbe.sampleFlash());
        }
    }

    /**
     * The A/B at one rung: drop the whole altitude family
     * ({@code -Dmeshelium.drawUnlistedLive.altitude=false} turns off both
     * O7's rule, S6's band rule and the mixin in one flag) and re-audit.
     *
     * <p>The property is read once per SWEEP and the sweep early-returns on
     * an unchanged camera section, so flipping it is not enough — the
     * camera has to cross a section boundary for the new answer to reach
     * the masks. One chunk sideways and back does it twice, and returns
     * the camera to the identical pose so the two audits are a pair.</p>
     *
     * @return undrawn expected sections with the fix OFF
     */
    private static long ladderFixOffAudit(ClientGameTestContext context,
            TestServerContext server, int x, double y, int z, String yaw, String pitch,
            int rung, LadderPass pass) {
        context.runOnClient(client ->
                System.setProperty("meshelium.drawUnlistedLive.altitude", "false"));
        nudgeCameraSection(context, server, x, y, z, yaw, pitch);
        TerrainResidency.CoverageAudit off = coverageAudit(context);
        context.runOnClient(client ->
                System.clearProperty("meshelium.drawUnlistedLive.altitude"));
        nudgeCameraSection(context, server, x, y, z, yaw, pitch);
        TerrainResidency.CoverageAudit back = coverageAudit(context);
        log("LADDER rung=" + rung + " A/B fixOff[" + off + "] fixOn[" + back + "]"
                + " pass=" + pass.tag);
        if (!off.samples().isEmpty()) {
            log("LADDER rung=" + rung + " A/B fixOff ABSENT sections: " + off.samples()
                    + " pass=" + pass.tag);
        }
        if (back.absent() > 0) {
            throw new AssertionError("rung " + rung + " (pass " + pass.tag + "): with the "
                    + "altitude family back ON " + back.absent() + " expected sections are "
                    + "still drawn by nobody (the A/B's restore half) - " + back + " :: "
                    + back.samples());
        }
        return off.absent();
    }

    /**
     * Force a camera-SECTION crossing and come back to the same pose. One
     * chunk east, then the pose again: two crossings, so the unlisted-live
     * sweep runs twice and no early-out can carry a stale verdict.
     *
     * <p>pre21: sampled per tick, because with the far field armed these
     * two steps are the only horizontal motion the ladder has, and at rd
     * 2 each one hands the disc's trailing column of five to the far field
     * across a zero-width handover band — the flash clock's whole
     * population for pass B.</p>
     */
    private static void nudgeCameraSection(ClientGameTestContext context,
            TestServerContext server, int x, double y, int z, String yaw, String pitch) {
        server.runCommand(tpCommand(x + 16, y, z, yaw, pitch));
        ladderWait(context, 10);
        server.runCommand(tpCommand(x, y, z, yaw, pitch));
        ladderWait(context, 10);
    }

    /**
     * Pitch for a camera {@code lift} blocks above the local surface: aim
     * at the middle of the ladder's own ring, {@code rd * 8} blocks out,
     * clamped to {@value #LADDER_PITCH_MIN}..{@value #LADDER_PITCH_MAX}
     * degrees. See {@link #LADDER_PITCH_MIN} for why this is derived.
     */
    private static String ladderPitch(double lift) {
        double degrees = Math.toDegrees(Math.atan2(Math.max(0.0, lift), LADDER_RD * 8.0));
        long clamped = Math.round(Math.max(LADDER_PITCH_MIN,
                Math.min(LADDER_PITCH_MAX, degrees)));
        return Long.toString(clamped);
    }

    /**
     * Arm the drawer's coverage capture, wait for a bfs frame to take it,
     * and hand it to the residency for the set difference.
     *
     * <p>The capture never happens on the occlusion path, which is a real
     * answer rather than a timeout: that path does not read
     * {@code visibleSections} and cannot have this class of hole. The
     * caller has already pinned the BFS feed, so a missing capture here
     * means the pin came undone and the message says so.</p>
     */
    private static TerrainResidency.CoverageAudit coverageAudit(
            ClientGameTestContext context) {
        context.runOnClient(client -> TerrainDrawer.armCoverageAudit());
        try {
            context.waitFor(client -> TerrainDrawer.coverageAuditCaptured(), 200);
        } catch (Throwable t) {
            throw new AssertionError("no bfs-path frame captured the coverage audit within "
                    + "200 ticks. Either the drawer stopped owning the frame or occlusion "
                    + "culling came back on mid-ladder; either way every reading after "
                    + "this point would be about a different renderer", t);
        }
        return context.computeOnClient(client -> TerrainResidency.auditUnlistedCoverage(
                TerrainDrawer.coverageAuditKeys(),
                TerrainDrawer.coverageAuditWords(),
                TerrainDrawer.coverageAuditRegions(),
                TerrainDrawer.COVERAGE_AUDIT_STRIDE,
                TerrainDrawer.COVERAGE_AUDIT_IN_FRUSTUM,
                TerrainDrawer.COVERAGE_AUDIT_NO_MASK,
                cameraSectionYOnClient(client),
                client.options.getEffectiveRenderDistance(),
                LADDER_SAMPLES,
                // pre21: the per-section half of the frustum gate, on the
                // capture frame's own frustum. Vanilla's list is filtered
                // per section; without this a section behind the camera
                // would read as a hole.
                TerrainDrawer::coverageAuditSectionInFrustum));
    }

    private static int cameraSectionY(ClientGameTestContext context) {
        return context.computeOnClient(client -> cameraSectionYOnClient(client));
    }

    /**
     * The camera's section Y, read exactly the way {@code TerrainResidency.pump}
     * reads it — {@code blockPosition().getY() >> 4}, an arithmetic shift so
     * negative Y floors the way {@code SectionPos.blockToSectionCoord} does.
     * A second, differently-rounded copy of that arithmetic here would make
     * every band reading off by one below y=0.
     */
    private static int cameraSectionYOnClient(net.minecraft.client.Minecraft client) {
        net.minecraft.client.Camera camera = client.gameRenderer == null
                ? null : client.gameRenderer.mainCamera();
        if (camera == null || !camera.isInitialized() || camera.blockPosition() == null) {
            return Integer.MIN_VALUE;
        }
        return camera.blockPosition().getY() >> 4;
    }

    /** A short, bounded quiesce: the ladder pays this 26 times per pass. */
    private static void quiesceLadder(ClientGameTestContext context) {
        for (int i = 0; i < 4; i++) {
            long before = TerrainResidency.counters().encodedSections();
            ladderWait(context, 20);
            TerrainResidency.Counters after = TerrainResidency.counters();
            if (after.encodedSections() == before && after.stagingBacklogEntries() == 0) {
                return;
            }
        }
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) {
            if (value == wanted) {
                return true;
            }
        }
        return false;
    }

    private static String summary(List<Rung> rungs) {
        StringBuilder out = new StringBuilder();
        for (Rung r : rungs) {
            out.append(r.index()).append(':').append(r.settled().absent())
                    .append('/').append(r.settled().expected()).append(' ');
        }
        return out.toString().trim();
    }

    /**
     * One evaluated window: a candidate camera column and what the disc
     * around it actually contains. Carried out of the locator even when it
     * FAILS, so the message can say what the seed has instead of only what
     * it lacks.
     */
    private record Relief(int x, int z, int centreY, int highestY, int lowPct, int highPct,
            int yaw) {

        @Override
        public String toString() {
            return x + "," + z + " columnY=" + centreY + " highestInDisc=" + highestY
                    + " low=" + lowPct + "% high=" + highPct + "%";
        }
    }

    /**
     * Find a camera column with LOW ground under it and HIGH ground inside
     * the ladder's disc - the only configuration in which a disc-wide
     * percentile can lie about where the terrain is, and therefore the only
     * configuration that reproduces the residual five fixes left.
     *
     * <p>Located by RELIEF, not by biome. A biome name is a promise about
     * climate parameters, not about height: "deep ocean" next to "ocean" is
     * flat, and {@code IS_MOUNTAIN} contains meadow. The locator asks the
     * chunk generator for base heights directly
     * ({@code ChunkGenerator.getBaseHeight}, a noise query that generates no
     * chunk and blocks on nothing) and slides a window the size of the
     * ladder's own disc across them.</p>
     *
     * <p><b>Two passes.</b> A COARSE survey of +/-{@value #RELIEF_COARSE_HALF}
     * blocks at {@value #RELIEF_COARSE_STEP}-block spacing ranks every low
     * column by how bimodal its kilometre-scale neighbourhood is; the best
     * {@value #RELIEF_CANDIDATES} are then re-sampled at
     * {@value #RELIEF_STEP} blocks over +/-{@value #RELIEF_FINE_HALF} and
     * judged against the real thresholds with the real disc. The first cut
     * had one pass over +/-1536 and refused on seed 4242 - which was the
     * leg working, not failing: the thresholds ARE the precondition, and
     * weakening them would manufacture exactly the green-for-no-reason this
     * whole leg exists to prevent.</p>
     *
     * <p><b>The thresholds.</b> The camera column must be at or below y
     * {@value #RELIEF_LOW_Y} (ocean floor / low plain), at least
     * {@value #RELIEF_MIN_LOW_FRACTION} of the disc must be too - so the low
     * half is a body of water or plain rather than a puddle - and at least
     * {@value #RELIEF_MIN_HIGH_FRACTION} of it must reach y
     * {@value #RELIEF_HIGH_Y}, four sections above sea level. Heights are
     * {@code OCEAN_FLOOR_WG}, i.e. SOLID ground: over ocean that reads the
     * seabed while the resident column tops at the water surface (section Y
     * 3), so a passing scene has its two modes four or five SECTIONS apart,
     * which is what holds the retired p90 above the camera for the low
     * rungs of the climb.</p>
     */
    private static Relief findMixedScene(TestServerContext server) {
        // TWO survey origins, and the second is the point of the exercise.
        // Sweeping outward from spawn only works if spawn happens to be
        // near a coast with highland behind it; the world already knows
        // where its mountains are, and findClosestBiome3d will say so for
        // the cost of a biome query that generates no chunk. So: spawn,
        // then the nearest peaks or hills. A coast at the foot of a
        // mountain range is exactly the scene this leg needs, and that is
        // where the second survey is centred.
        List<int[]> origins = new ArrayList<>();
        List<String> originNames = new ArrayList<>();
        int[] spawn = server.computeOnServer(mc -> {
            var level = mc.overworld();
            var pos = level.getRespawnData().pos();
            return new int[] {pos.getX(), pos.getZ()};
        });
        origins.add(spawn);
        originNames.add("spawn");
        for (Choice choice : HIGH_GROUND) {
            BlockPos hit = findBiome(server, choice.predicate(), CLIFF_SEARCH_RADIUS);
            if (hit != null) {
                origins.add(new int[] {hit.getX(), hit.getZ()});
                originNames.add(choice.name() + " at " + hit.getX() + "," + hit.getZ());
                break;
            }
        }
        log("LADDER locator: surveying " + originNames + " - +/-" + RELIEF_COARSE_HALF
                + " blocks each at " + RELIEF_COARSE_STEP + "-block spacing. This is a few "
                + "seconds of noise queries and generates no chunks");

        final int coarseSide = 2 * (RELIEF_COARSE_HALF / RELIEF_COARSE_STEP) + 1;
        final int coarseHalf = coarseSide / 2;
        final int fineSide = 2 * (RELIEF_FINE_HALF / RELIEF_STEP) + 1;
        final int fineHalf = fineSide / 2;
        Relief bestSeen = null;
        int bestSeenScore = -1;

        for (int o = 0; o < origins.size(); o++) {
            int originX = origins.get(o)[0];
            int originZ = origins.get(o)[1];
            int[] coarse = sampleBaseHeights(server, originX, originZ,
                    coarseSide, RELIEF_COARSE_STEP);
            logFieldShape(originNames.get(o), coarse, coarseSide, RELIEF_COARSE_STEP);

            // --- pass 1: rank low columns by kilometre-scale bimodality ---
            List<int[]> candidates = new ArrayList<>(); // {score, i, j}
            for (int i = RELIEF_COARSE_WINDOW; i < coarseSide - RELIEF_COARSE_WINDOW; i++) {
                for (int j = RELIEF_COARSE_WINDOW;
                        j < coarseSide - RELIEF_COARSE_WINDOW; j++) {
                    if (coarse[i * coarseSide + j] > RELIEF_LOW_Y) {
                        continue; // the camera must sit over the LOW half
                    }
                    int score = bimodalityScore(coarse, coarseSide, i, j,
                            RELIEF_COARSE_WINDOW);
                    if (score > 0) {
                        candidates.add(new int[] {score, i, j});
                    }
                }
            }
            candidates.sort((a, b) -> Integer.compare(b[0], a[0]));
            int refining = Math.min(RELIEF_CANDIDATES, candidates.size());
            log("LADDER locator: " + originNames.get(o) + " gave " + candidates.size()
                    + " coarse candidates; refining the best " + refining);

            // --- pass 2: the real disc, the real thresholds ---
            for (int c = 0; c < refining; c++) {
                int[] cand = candidates.get(c);
                int cx = originX + (cand[1] - coarseHalf) * RELIEF_COARSE_STEP;
                int cz = originZ + (cand[2] - coarseHalf) * RELIEF_COARSE_STEP;
                int[] fine = sampleBaseHeights(server, cx, cz, fineSide, RELIEF_STEP);
                Relief bestPassing = null;
                int bestPassingScore = -1;
                for (int i = RELIEF_WINDOW; i < fineSide - RELIEF_WINDOW; i++) {
                    for (int j = RELIEF_WINDOW; j < fineSide - RELIEF_WINDOW; j++) {
                        if (fine[i * fineSide + j] > RELIEF_LOW_Y) {
                            continue;
                        }
                        Relief here = evaluateWindow(fine, fineSide, RELIEF_STEP,
                                cx + (i - fineHalf) * RELIEF_STEP,
                                cz + (j - fineHalf) * RELIEF_STEP, i, j, RELIEF_WINDOW);
                        int score = reliefScore(here);
                        // PASSING beats scoring, always. Ranked by the
                        // weaker term a 100%-low 11%-high window outranks a
                        // 40/20 one that actually meets both, and returning
                        // the top-scoring window would hand the ladder a
                        // scene the thresholds had just rejected.
                        if (passesReliefThresholds(here) && score > bestPassingScore) {
                            bestPassingScore = score;
                            bestPassing = here;
                        }
                        if (score > bestSeenScore) {
                            bestSeenScore = score;
                            bestSeen = here;
                        }
                    }
                }
                if (bestPassing != null) {
                    log("LADDER locator: MIXED scene found from " + originNames.get(o)
                            + ", coarse candidate " + c + " -> " + bestPassing);
                    return bestPassing;
                }
            }
            log("LADDER locator: " + originNames.get(o) + " exhausted; best window anywhere "
                    + "so far [" + bestSeen + "]");
        }
        throw new AssertionError("no MIXED scene for seed " + SEED + " within +/-"
                + RELIEF_COARSE_HALF + " blocks of " + originNames + ". The leg needs a "
                + "camera column at or below y " + RELIEF_LOW_Y + " whose "
                + (RELIEF_WINDOW * RELIEF_STEP) + "-block disc is at least "
                + (int) (RELIEF_MIN_LOW_FRACTION * 100) + "% at or below y " + RELIEF_LOW_Y
                + " AND at least " + (int) (RELIEF_MIN_HIGH_FRACTION * 100)
                + "% at or above y " + RELIEF_HIGH_Y + ". The BEST window found anywhere "
                + "was [" + bestSeen + "] - read that against the "
                + "'LADDER locator: <origin> field' lines above, which say what this "
                + "world's neighbourhood actually contains rather than only what it "
                + "lacks.\n"
                + "These thresholds are the PRECONDITION, not a tuning knob: a uniform "
                + "disc cannot reproduce the fly-up defect, because the arm this wave "
                + "retired was only ever wrong where the disc held two terrain heights at "
                + "once. Weakening them manufactures a green that means nothing. Widen the "
                + "search or change the world: " + SEED_HINT);
    }

    /**
     * How close a window comes to satisfying BOTH thresholds, as a
     * per-mille of the weaker term - the same arithmetic
     * {@link #bimodalityScore} ranks the coarse pass by, so the two passes
     * agree on what "better" means. 1000 or more means both are met.
     */
    private static int reliefScore(Relief r) {
        return Math.min(r.lowPct() * 1000 / (int) Math.round(RELIEF_MIN_LOW_FRACTION * 100),
                r.highPct() * 1000 / (int) Math.round(RELIEF_MIN_HIGH_FRACTION * 100));
    }

    /** Does a window meet the leg's actual precondition? */
    private static boolean passesReliefThresholds(Relief r) {
        return r.centreY() <= RELIEF_LOW_Y
                && r.lowPct() >= RELIEF_MIN_LOW_FRACTION * 100
                && r.highPct() >= RELIEF_MIN_HIGH_FRACTION * 100;
    }

    /**
     * How close a window comes to satisfying BOTH thresholds, as a per-mille
     * of the weaker one. 1000 or more means it already meets both at this
     * resolution; the ranking is by the weaker term on purpose, because a
     * cell that is 90% ocean and 0% mountain is not a candidate however
     * much ocean it has.
     */
    private static int bimodalityScore(int[] field, int side, int i, int j, int window) {
        int total = 0;
        int low = 0;
        int high = 0;
        for (int di = -window; di <= window; di++) {
            for (int dj = -window; dj <= window; dj++) {
                int h = field[(i + di) * side + (j + dj)];
                total++;
                if (h <= RELIEF_LOW_Y) {
                    low++;
                }
                if (h >= RELIEF_HIGH_Y) {
                    high++;
                }
            }
        }
        int lowTarget = Math.max(1, (int) (total * RELIEF_MIN_LOW_FRACTION));
        int highTarget = Math.max(1, (int) (total * RELIEF_MIN_HIGH_FRACTION));
        return Math.min(low * 1000 / lowTarget, high * 1000 / highTarget);
    }

    /** The full reading for one window, including the yaw toward its high ground. */
    private static Relief evaluateWindow(int[] field, int side, int step,
            int x, int z, int i, int j, int window) {
        int total = 0;
        int low = 0;
        int high = 0;
        int highest = Integer.MIN_VALUE;
        int highDi = 0;
        int highDj = 0;
        for (int di = -window; di <= window; di++) {
            for (int dj = -window; dj <= window; dj++) {
                int h = field[(i + di) * side + (j + dj)];
                total++;
                if (h <= RELIEF_LOW_Y) {
                    low++;
                }
                if (h >= RELIEF_HIGH_Y) {
                    high++;
                }
                if (h > highest) {
                    highest = h;
                    highDi = di;
                    highDj = dj;
                }
            }
        }
        return new Relief(x, z, field[i * side + j], highest,
                (int) Math.round(100.0 * low / total),
                (int) Math.round(100.0 * high / total),
                yawToward(highDi * step, highDj * step));
    }

    /**
     * One rectangular field of generator base heights, centred on
     * {@code (x, z)}. ONE server round trip for the whole grid: the query
     * is cheap but the hop is not, and the first cut's per-column hop would
     * have made a 17,000-sample search absurd.
     *
     * <p>{@code OCEAN_FLOOR_WG} rather than {@code WORLD_SURFACE_WG}: the
     * aquifer fill is not something to depend on for a threshold, and solid
     * ground is what separates a seabed from a mountain. See
     * {@link #findMixedScene} for why that is the right axis.</p>
     */
    private static int[] sampleBaseHeights(TestServerContext server, int centreX,
            int centreZ, int side, int step) {
        final int half = side / 2;
        int[] field = server.computeOnServer(mc -> {
            net.minecraft.server.level.ServerLevel level = mc.overworld();
            net.minecraft.world.level.chunk.ChunkGenerator generator =
                    level.getChunkSource().getGenerator();
            net.minecraft.world.level.levelgen.RandomState random =
                    level.getChunkSource().randomState();
            int[] out = new int[side * side];
            for (int i = 0; i < side; i++) {
                for (int j = 0; j < side; j++) {
                    out[i * side + j] = generator.getBaseHeight(
                            centreX + (i - half) * step, centreZ + (j - half) * step,
                            Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                }
            }
            return out;
        });
        if (field == null) {
            throw new AssertionError("the relief locator could not read the chunk "
                    + "generator's base heights, so the ladder has no way to find a mixed "
                    + "scene and would climb over whatever spawn happens to be");
        }
        return field;
    }

    /**
     * Say what the seed CONTAINS, not only that it lacked something. A
     * refusal that reports "no mixed scene" teaches the next reader
     * nothing; this line tells them whether the neighbourhood is all ocean,
     * all highland, or genuinely two-mode but too coarsely interleaved.
     */
    private static void logFieldShape(String name, int[] field, int side, int step) {
        int low = 0;
        int high = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        for (int h : field) {
            if (h <= RELIEF_LOW_Y) {
                low++;
            }
            if (h >= RELIEF_HIGH_Y) {
                high++;
            }
            min = Math.min(min, h);
            max = Math.max(max, h);
            sum += h;
        }
        log("LADDER locator: " + name + " field " + side + "x" + side + " at " + step
                + "-block spacing (" + field.length + " noise columns): y min=" + min
                + " mean=" + (sum / Math.max(1, field.length)) + " max=" + max
                + ", at-or-below-" + RELIEF_LOW_Y + "=" + (100 * low / field.length)
                + "%, at-or-above-" + RELIEF_HIGH_Y + "=" + (100 * high / field.length)
                + "%");
    }

    /**
     * Minecraft yaw that faces the offset {@code (dx, dz)}: 0 is +Z (south)
     * and yaw increases clockwise toward -X (west), so it is
     * {@code atan2(-dx, dz)} in degrees. Zero offset looks south.
     */
    private static int yawToward(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return 0;
        }
        return (int) Math.round(Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /**
     * The near field must be healthy throughout. A far-field bug that
     * turned the mod passive would show up here first, and that is the
     * standing rule the far field lives under: a cache problem may never
     * cost the player their terrain renderer. A broken walker matters
     * just as much here, because it stops the ring growing at that
     * instant and every picture after it is quietly incomplete.
     */
    private static void assertNoErrors() {
        String drawError = TerrainDrawer.lastError();
        if (drawError != null) {
            throw new AssertionError("terrain drawer reported an error: " + drawError);
        }
        String residencyError = TerrainResidency.lastError();
        if (residencyError != null) {
            throw new AssertionError("terrain residency reported an error: " + residencyError);
        }
        if (TerrainDrawer.coveragePassive()) {
            throw new AssertionError("the coverage guard went passive during the visual "
                    + "diagnostic - every screenshot after this point is vanilla, not "
                    + "Meshelium: " + TerrainResidency.counters());
        }
        if (FarWalkerProbe.broken()) {
            throw new AssertionError("the far-field walker latched BROKEN; the far ring "
                    + "stopped growing at that moment and the pictures after it are "
                    + "incomplete: " + FarWalkerProbe.describe());
        }
    }
}
