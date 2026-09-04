/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield;

import com.deds.meshelium.MesheliumLog;
import com.deds.meshelium.MesheliumConfig;
import com.deds.meshelium.farfield.mesh.ShellMesher;
import com.deds.meshelium.farfield.mesh.SpriteUvResolver;
import com.deds.meshelium.farfield.store.ShellCodec;
import com.deds.meshelium.terrain.EncodedSectionMesh;
import com.deds.meshelium.terrain.host.SectionBuildTap;
import com.deds.meshelium.terrain.host.TerrainResidency;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Far-field wave W3: the bridge between the shell store and the terrain
 * residency — the both-direction walker that decides WHICH cached shells
 * should be drawable right now, feeds the promote side through
 * {@link FarField#requestShell} and the demote side through
 * {@code TerrainResidency.releaseFarColumn}, and carries the far field's
 * OWN counters (standing rule, docs/FARFIELD-WAVES.md: far-field
 * failures may never touch the four wave-8 coverage-guard drop
 * counters; nothing in this class can reach them — the only
 * TerrainResidency entry points it calls are the far-specific ones).
 *
 * <h2>The shapes and their metrics (H2, the corner gap)</h2>
 * Every boundary this class draws is one of two shapes, and pre7 mixed
 * them. The owner saw the mixture before anyone here did: "its like the
 * center is doing regular cylindrical chunk loading, and the box that
 * swaps the lod chunks in behind me is doing square shaped chunk loading.
 * thats what the gap looks like, the corner of a square"
 * (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre7, item H2). The audit,
 * with every test named and its metric fixed:
 * <table border="1">
 *   <caption>Distance tests, after the H2 fix</caption>
 *   <tr><th>test</th><th>metric</th><th>why</th></tr>
 *   <tr><td>{@link #nearCovered}</td><td>EUCLIDEAN, pad 1</td>
 *       <td>it IS vanilla's {@code ChunkTrackingView.isWithinDistance}
 *       (javap below); copying it is the point</td></tr>
 *   <tr><td>{@link #coverRadius}</td><td>EUCLIDEAN radius</td>
 *       <td>the same disc, shortened by the handover band (H1)</td></tr>
 *   <tr><td>{@link #stillWanted} outer edge</td><td>EUCLIDEAN
 *       ({@link #withinRing})</td><td>WAS Chebyshev; the fog wall it has
 *       to meet is radial</td></tr>
 *   <tr><td>{@link #releaseColumns} demote</td><td>EUCLIDEAN
 *       ({@link #withinRing}, +2 band)</td><td>WAS Chebyshev; it must
 *       agree with the promote side or the corners never release</td></tr>
 *   <tr><td>{@link #classifyColumn} OUTSIDE_RING</td><td>EUCLIDEAN</td>
 *       <td>a census must report the walker's own boundary</td></tr>
 *   <tr><td>the far-plane clamp in {@link #pumpInner}</td>
 *       <td>EUCLIDEAN radius</td><td>{@code Camera.update}'s depthFar is
 *       a radius; now consistent with the edge it clamps</td></tr>
 *   <tr><td>{@link #ringChunk} / {@link #ringIndex} /
 *       {@link #scanCursorPassed}</td><td>CHEBYSHEV</td>
 *       <td>correct and deliberately unchanged: these are ENUMERATION
 *       order, not membership. A square ring walk visits every column of
 *       a disc exactly once; the disc test filters</td></tr>
 *   <tr><td>{@link #innerScanRing}</td><td>CHEBYSHEV, derived from the
 *       disc</td><td>correct: a ring's most distant point is its corner
 *       {@code (r,r)}, so the smallest ring with
 *       {@code !nearCovered(r,r,coverEdge)} is the first ring holding any
 *       uncovered column</td></tr>
 * </table>
 *
 * <p><b>Where the square-cornered gap actually came from, and it was not
 * in this class.</b> The far field's own inner edge has been a disc since
 * pre2. The square is VANILLA'S, and there are two of them, at the same
 * radius and with different jobs:</p>
 * <ul>
 *   <li>what vanilla LISTS is the pad-1 DISC —
 *       {@code SectionOcclusionGraph.getRelativeFrom} refuses any
 *       neighbour outside {@code isInViewDistance} (javap ip 8-19), so
 *       {@code visibleSections} is disc-bounded, and the drawer's
 *       BFS-mask path draws exactly that list;</li>
 *   <li>what vanilla KEEPS is a Chebyshev SQUARE —
 *       {@code RotatingSectionStorage.containsSection} is
 *       {@code |x - cx| <= radius && |z - cz| <= radius} (javap), and a
 *       compiled mesh survives until its grid slot is recycled at radius
 *       + 1, where {@code RenderSection.setSectionNode} calls
 *       {@code reset()} as its first instruction (javap ip 0).</li>
 * </ul>
 * <p>So a column that was compiled and has since RECEDED out of the disc
 * while staying inside the square holds a live section that vanilla does
 * not list. Nobody drew it: not vanilla, and not us, because
 * {@code admitFarSectionLocked}'s {@code isOccupied} pre-flight correctly
 * refuses a far copy at an owned position. Square minus disc is four
 * corner lobes — about 170 columns at nearEdge 16 — bounded outside by two
 * straight edges meeting at a right angle. The fix is in
 * {@code TerrainResidency.syncUnlistedLiveMaskLocked}, which keeps the
 * near field DRAWING everything it OWNS; the far field's own boundaries
 * needed only the outer-edge metric above. Nothing here should stop
 * wanting the lobes: on a FIRST visit the same columns were never
 * compiled, so no live section owns them and the far copy is the only
 * candidate there is.</p>
 *
 * <h2>The ring and its hysteresis</h2>
 * The wanted ring is everything vanilla cannot draw, out to L1: a column
 * is wanted when it is OUTSIDE vanilla's compilable set and inside
 * EUCLIDEAN distance {@code L1} ({@link #withinRing}). nearEdge is vanilla's effective render
 * distance ({@code Options.getEffectiveRenderDistance()}, javap:
 * {@code public int getEffectiveRenderDistance();} — the drawer's own
 * occlusion Auto mode reads the same value) and L1 is
 * {@link FarFieldConfig#l1RadiusChunks()}, both re-read every pump so
 * both slider directions apply live (the ground-cover lesson,
 * FARFIELD-CODEBASE-SEAM.md section 5.3). Promotion asks
 * {@link #nearCovered} at {@link #coverRadius}, which is {@code nearEdge}
 * shortened by the handover band (section H1 below): a column vanilla has
 * ALREADY BUILT is not ours, and a column it has merely claimed is.
 * Demotion is the OUTER edge only, past Euclidean
 * {@code L1+1} with that dead band — the inner edge
 * is deliberately not a demote test, because a far copy inside the near
 * field is released by the ARRIVAL of the real section instead (see
 * {@link #releaseColumns}).
 *
 * <h3>H3/M4, subsumed at SEAM steps 3-4: the hold is the state, not a
 * bridge</h3>
 * "the ones that do get replaced flash clear briefly, they should swap in
 * before the old chunk gets unloaded" (pre7, item H3). Both copies
 * resident at one position is structurally impossible — {@code RegionStore}
 * binds exactly one owner per position — so the exchange is built on the
 * NEAR side: {@code TerrainResidency.onMeshReleased} PARKS the real
 * section rather than freeing it whenever the position is far-domain
 * under the published coverage geometry, and {@code admitFarSectionLocked}
 * takes the parked slot directly. The exchange is one statement sequence
 * in one lock window under one draw-epoch bump, so no published snapshot
 * ever has the position unowned.
 *
 * <p>Two generations of BRIDGE preceded this (H3's proven bridge off the
 * contested watch, M4's speculative bridge off ring geometry) and both
 * are deleted, with their deadlines, caps, LRU recycle and refusal
 * census: they negotiated TIMING so the free-to-bind gap was unlikely,
 * where the park removes the gap from the state space — a covered, drawn
 * position is never unbound, only replaced, and a hold ends exclusively
 * by that replacement, vanilla's own re-upload, E1 (coverage exit), E2
 * (the AWAITING quad budget, farthest-first) or E3 (era end). The walker
 * still hears {@link #onFarBlockerReleased} at the same moment it always
 * did — the park files the demand in the same lock hold — and still
 * spends the same read. The counters to read the seam by are
 * {@code TerrainResidency.ledgerResolvedSwap()} (the exchange working)
 * against {@code ledgerEvictedWall()} (the one sanctioned violation,
 * zero except under genuine memory pressure).</p>
 *
 * <ul>
 *   <li>{@link #pumpInner} publishes the COVERAGE GEOMETRY to the
 *       residency every armed pump
 *       ({@code TerrainResidency.publishFarCoverage}), so
 *       {@code onMeshReleased} can park on the far field's domain. A
 *       stand-down does NOT clear it (step 3's split: geometry is always
 *       valid while a world is up; only ADMISSION gates on the settle),
 *       and a reload storm gets a synchronous republish at
 *       {@code invalidateCompiledGeometry} HEAD;</li>
 *   <li>{@link #drainUnblockedColumns} runs FIRST and runs even when
 *       {@code TerrainResidency.farPromotionHasRoom()} says no. Gating it
 *       behind the fill guard is why the old bridges expired at the
 *       owner's settings, where the ring is far larger than the region
 *       budget and the guard is closed much of the time.</li>
 * </ul>
 *
 * <p><b>B2, the correction the pre12 review forced, and it matters more
 * than the change it corrects.</b> Ungating this drain was first argued as
 * "a handover recovery is not fill". That is true of a BRIDGED section and
 * false of the column it belongs to: {@link #issueRequest} reads a whole
 * column and every section of it that is not bridged is ordinary net-new
 * fill, so the ungating removed the far field's only fill guard — whose
 * {@code max - nearReserve} arm is enforced in no other line of the
 * codebase — and with it the property that far fill can never reach
 * {@code forceEvictFarLocked} or the wave-11 retained pressure sweep
 * inside the pump's lock window. It could even defeat itself: that sweep
 * deliberately does not protect speculative bridges, so it would have
 * freed the very bridges this fix arms. The guard now sits on the
 * ADMISSION ({@code admitFarSectionLocked} refuses a non-bridged
 * admission with {@code ADMIT_NO_BUDGET} while it is closed) rather than
 * on the producer, because only the admission can tell a swap from net-new
 * fill: a park is per SECTION (the ledger byte) and
 * {@link #unblockedColumns} is per COLUMN.</p>
 *
 * <h3>Why the inner edge is a DISC and not the Chebyshev square (B3)</h3>
 * pre1 promoted at Chebyshev {@code dist >= nearEdge+1}, on the argument
 * that the vanilla client holds a Chebyshev SQUARE of chunks and
 * sections, so "outside the square" guarantees no vanilla-built section
 * can contest a far position. The square part is true; the conclusion
 * that vanilla FILLS it is not, and the owner saw the difference:
 * "around the edges of my render distance theres some issues with chunks
 * not loading" (docs/FARFIELD-WAVES.md, OWNER PLAYTEST OF pre1, B3).
 *
 * <p>What vanilla actually compiles is a pad-1 DISC inscribed in that
 * square. {@code SectionOcclusionGraph.getRelativeFrom} gates BFS
 * traversal on {@code isInViewDistance}, which is
 * {@code ChunkTrackingView.isInViewDistance(SectionPos.x(camera),
 * SectionPos.z(camera), viewArea.getViewDistance(), SectionPos.x(pos),
 * SectionPos.z(pos))} (javap ip 0-26), and that delegates to
 * {@code isWithinDistance(..., false)}, whose whole body is
 * {@code max(0,|dx|-1)^2 + max(0,|dz|-1)^2 < vd*vd} (javap ip 0-77, pad
 * selected at ip 0-10, STRICT less-than at ip 63-77).
 * {@code ViewArea.getViewDistance()} is {@code RotatingSectionStorage
 * .radius()}, and the ViewArea is constructed with
 * {@code Options.getEffectiveRenderDistance()}
 * ({@code LevelRenderer.invalidateCompiledGeometry} ip 149-184) — so the
 * disc's radius is exactly nearEdge. A section outside it is never
 * polled, never enters {@code visibleSections}, and is therefore never
 * compiled and never reaches Meshelium at all
 * (docs/FRONTIER-HOLES-RECON.md sections 1.2 and 1.3).</p>
 *
 * <p>On the compass axes the disc reaches {@code |dx| = nearEdge}
 * exactly, so the old square edge was right there and nothing changes.
 * On the diagonals it stops at {@code floor(nearEdge/sqrt(2)) + 1},
 * which for nearEdge 16 is chunk 12 against a far ring that used to
 * start at 17, and for nearEdge 32 is chunk 23 against 33. Those four
 * corner lobes were drawn by NOBODY. Vanilla hid them behind its own
 * round fog wall, inscribed in the same square; pushing the fog out to
 * the far horizon (FarFieldFogMixin) is what put them on screen. This is
 * the wave-16 lesson applied the way FRONTIER-HOLES-RECON.md's status
 * header demands: "the fix belongs in the DATA layer (real chunks the
 * client actually holds)", which is precisely what a shell cache is.</p>
 *
 * <p><b>Contention is now routine, and already handled.</b> Far columns
 * inside the square can meet a live or retained near section that
 * drifted into a corner lobe while the camera moved
 * (FRONTIER-HOLES-RECON.md section 4.1). {@code admitFarSectionLocked}
 * refuses those before touching anything: the {@code farResident} /
 * {@code retained} / {@code successorQueued} triple, then the
 * {@code RegionStore.isOccupied} pre-flight that the W3 review added
 * precisely to catch a LIVE owner. In the other order — a real compile
 * landing on an admitted far section — vanilla wins through the existing
 * supersede branch ({@code drainPendingUploadsLocked}'s
 * {@code orphanedAtMillis != 0} arm, and
 * {@code onSectionCompiledEmpty}). What the square DID buy was that
 * neither path ever ran; they are now load-bearing.
 *
 * <p>Region-id cost of the extra area is small next to its size: the
 * lobes lie INSIDE the vanilla grid, so their 8x8-chunk regions are
 * already counted by the {@code regionsTouched(pinnedRd)} reserve in
 * {@code farPromotionHasRoom()}, and mostly already allocated by near
 * sections.</p>
 *
 * <h3>H1: the disc is what vanilla WILL draw, not what it HAS drawn</h3>
 * pre2 through pre6 all read the compile disc as vanilla's territory and
 * declined it. The disc is the set vanilla CAN compile; it is not the set
 * vanilla HAS compiled, and the owner has reported the difference in five
 * consecutive playtests ("we still have that ring between the rendered
 * chunks and lod chunks", "blank chunks between my loaded area and where
 * im at", "the ring around us can have missing chunks").
 *
 * <p><b>Why the gap is structural rather than slow.</b> Follow one column
 * of FIRST-VISIT terrain in, along a compass axis, at effective render
 * distance E:</p>
 * <ol>
 *   <li>at Chebyshev distance 20 the column is wanted (outside the disc),
 *       the walker reads it, the store misses, {@code tryExtractLoaded}
 *       fails because the client does not hold the chunk, and the column
 *       lands in {@link #absentSet}. Every subsequent ring walk SKIPS it,
 *       so no read is ever issued for it again;</li>
 *   <li>the server sends the chunk. Its tracking test is the same
 *       {@code ChunkTrackingView.isWithinDistance} the client uses, with
 *       pad 2 instead of pad 1 ({@code Positioned.contains(II)Z} passes
 *       {@code iconst_1}, javap), so the outermost column it ever sends
 *       is at {@code |dx| = E+1}: {@code (|dx|-2)^2 < E^2} gives
 *       {@code |dx| < E+2};</li>
 *   <li>{@code ExtractDispatch} extracts on ARRIVAL but only when all
 *       four lateral neighbours are loaded, so the outermost column it
 *       can ever write is one further in, at {@code |dx| = E} — its
 *       outward neighbour at {@code E+1} is the last one sent. And
 *       {@code |dx| = E} is INSIDE the compile disc:
 *       {@code (E-1)^2 < E^2};</li>
 *   <li>{@code onShellWritten} clears the absent memo and files the
 *       column in {@link #lateShells} (a column the far field is already
 *       DRAWING goes to {@link #refreshColumns} instead - the seam
 *       step 2 replace-in-place path; and since pre19 the column's eight
 *       RESIDENT neighbours go to {@link #apronRefreshColumns}, because
 *       the mesher reads one cell into each of them). The next pump calls
 *       {@link #stillWanted}, which asked {@link #nearCovered} at radius
 *       {@code nearEdge}, which answers TRUE, and the drain DROPS the
 *       entry.</li>
 * </ol>
 * <p>So on the compass axes the shell for a first-visit column became
 * available exactly one chunk INSIDE the point where the far field
 * stopped wanting it, and the one code path that could have used it threw
 * it away. Vanilla has not built the column either — it has only just
 * received it — so nobody draws it until vanilla's compile lands. That is
 * the ring, it fires on every column of unexplored terrain, and no amount
 * of speeding the far pipeline up can close it: the window it had was
 * negative. It is narrower on the diagonals, where the pad-2 send frontier
 * reaches {@code E/sqrt(2) + 2} and the pad-1 disc corner only
 * {@code E/sqrt(2) + 1}, which is why pre2's corner-lobe fix helped and
 * left the axes alone.
 *
 * <p><b>The band, and why a band is the right shape.</b> The inner edge
 * now sits at {@link #coverRadius}, {@code nearEdge} minus a handover band
 * of at most {@value #DEFAULT_HANDOVER_BAND_CHUNKS} chunks (property
 * {@code meshelium.farfield.handoverBandChunks}; 0 restores the pre6
 * behaviour exactly). Not the whole disc, because the un-built part of the
 * disc is ALWAYS its outer rim: vanilla compiles nearest-first, and that
 * is a property of the code rather than an assumption —
 * {@code SectionTaskDynamicQueue.poll(Vec3)} scans the whole task list and
 * returns the minimum {@code getRenderOrigin().distToCenterSqr(camera)}
 * (javap ip 77-93, with a recompile quota of 2 that only reorders
 * rebuilds), the occlusion BFS seeds from the camera's own section and
 * propagates outward, and {@code LevelRenderer.compileSections} promotes
 * anything within {@code distSqr < 768} to a synchronous compile. A band
 * of 4 chunks is 64 blocks, about 2.1 seconds of compile lag at elytra
 * cruise, against the 0.53 seconds one chunk used to buy.
 *
 * <p><b>Contention is the point, not a side effect.</b> Inside the band
 * most columns ARE built, and every section of those is refused by
 * {@code admitFarSectionLocked}'s three-way pre-flight and reported as
 * {@link #ADMIT_CONTESTED} with a watch armed. That is correct and it is
 * cheap ONCE; what would not be cheap is discovering it again on every
 * camera chunk crossing, which is what {@link #nearOwnedColumns} exists to
 * prevent. A column whose whole admission pass was contested is memoized
 * and skipped by the ring walk until {@link #onFarBlockerReleased} says
 * the near field let go — the pre6 recovery, finally pointed at the
 * columns it was built for.
 *
 * <h3>A contested position is borrowed, not lost (pre6)</h3>
 * Those refusals used to be one outcome, {@link #ADMIT_SKIPPED}, and it
 * was terminal: nothing re-requested the section, so a corner column
 * whose near owner was later evicted kept the hole until the camera
 * walked it out past {@code L1+2} and back. Two reviews flagged it and
 * the owner reported it from the other end — "chunks still just entirely
 * are missing", "the ring around us can have missing chunks".
 *
 * <p>They are now two outcomes, because they are two different facts.
 * {@link #ADMIT_SKIPPED} means the far field's OWN copy is at that
 * position: terminal, correctly, and retrying it forever is exactly the
 * loop {@link MeshedColumn#incomplete} exists to refuse.
 * {@link #ADMIT_CONTESTED} means a live, retained or queued NEAR section
 * is standing there, which is a statement about right now — the chunk
 * unloads, retention evicts, the player moves. The residency arms a
 * watch on the position at the moment it refuses, every near-side path
 * that can unown a position discharges it, and the walker hears
 * {@link #onFarBlockerReleased} exactly once when the slot is really
 * free (the residency re-checks before telling it, so a slot handed
 * straight to a successor re-arms instead of spending a read). No
 * polling, no timer, no re-scan of resident columns: a contested
 * position costs nothing for as long as the near field holds it, and
 * one column re-read when it stops.</p>
 *
 * <h2>Budgets (the walker discipline, FARFIELD-WAVES.md standing rules)</h2>
 * At most {@value #MAX_IN_FLIGHT} shell requests in flight; at most
 * {@code TerrainResidency}'s 64 far admissions per pump; at most
 * {@value #RELEASE_COLUMN_BUDGET} column releases per pump. The scan
 * arms on a camera chunk crossing or either slider moving, walks square
 * rings near-first with a persistent cursor, and stays pending across
 * pumps until the annulus is covered. A settings reload adds NO budget of
 * its own: it drops the ring in one pump and then rides exactly those
 * numbers on the way back up.
 *
 * <h2>Settings apply to what is already drawn (J4, reshaped by L9)</h2>
 * {@link #noteAppearanceSettings} watches two signatures every pump. A row
 * that changes MESHING triggers {@link #reloadFarField}: the far field is
 * dropped WHOLE and the ordinary near-first refill brings it back, because
 * the owner watched pre9 do it column by column and called it "random
 * jittering all over". A row that changes what is EXTRACTED cannot be
 * applied to terrain already on disk at all, and the most that is done -
 * and the most the tooltips claim - is that the terrain the client is
 * still holding is saved again.
 *
 * <h2>Threading</h2>
 * All maps and cursors here are RENDER-THREAD confined (the client main
 * thread; every entry point below says which). The two exceptions:
 * {@code resultQueue} has its own monitor (the IO thread posts, the
 * pump drains — the monitor never nests inside any other lock), and the
 * public counters are LongAdders. Calls INTO TerrainResidency take its
 * store lock internally; calls FROM TerrainResidency's lock window into
 * the {@code onAdmission*}/{@code onSectionEvicted} bookkeeping are
 * lock-free map updates on the render thread, honoring the residency's
 * no-vanilla-under-LOCK discipline (nothing here calls vanilla).
 *
 * <h2>The two-hop mesh pipeline</h2>
 * {@link FarField#requestShell} delivers bytes on the IO thread, which
 * decodes and posts the shell here; the NEXT pump resolves the palette
 * on the game thread ({@link SpriteUvResolver} — vanilla objects stay
 * on the game thread) and hands shell + resolved palette by value back
 * to the IO thread ({@link FarField#submitCompute}) for the pure-CPU
 * {@link ShellMesher} pass; the meshed sections then post back and are
 * admitted inside the residency's lock window beside
 * {@code drainPendingUploadsLocked} (the seam dossier's plug point).
 * The W3 brief's "resolve at request-issue time" is honored one hop
 * late by necessity: the palette names are only knowable after the
 * store read — the thread contract (resolver on the game thread, mesh
 * off-thread, no vanilla objects off-thread) is kept exactly.
 *
 * <h2>Region-id budget (landmine L3, the real arithmetic)</h2>
 * Far sections allocate region ids from the SAME
 * {@code MesheliumScaling.current().maxRegions()} budget as the near
 * field (2048 standard at option rd &lt;= 32; 2816 at pinned 48; 8 KiB
 * of GPU section records per id — SectionRecord.BYTES_PER_REGION).
 * Regions are 8x8 chunks by 4 sections. Promotion is gated by
 * {@code TerrainResidency.farPromotionHasRoom()}:
 * {@code regionCount < min(85% of maxRegions, maxRegions -
 * regionsTouched(pinnedRd))} — 85% sits under the existing 90%
 * retained-eviction high-water so far fill can never trip it, and the
 * reserve keeps vanilla's worst-case grid demand admissible even with
 * the far ring full. Worked numbers, surface-band vertical spans
 * (~1-2 region Y rows per column):
 * <pre>
 * standard pin (rd 32, maxRegions 2048, reserve 700 -> gate 1348):
 *   L1 96: ceil(193/8) = 25..26 region columns per axis = 625-676,
 *          minus the <= 64 wholly inside the near hole, x2 rows
 *          ~= 1230-1340 far ids on top of 300-700 near live, so the
 *          gate CLOSES and the ring renders as roughly L1 78-88.
 *   L1 64 (the shipped default): 17..18 per axis = 289-324 columns,
 *          minus 64, x2 rows ~= 450-520 far ids; total ~750-1220 —
 *          fits, including a busy near field.
 * pinned 48 (maxRegions 2816, reserve 1372 -> gate 1444):
 *   L1 64 fits comfortably; L1 96 still closes the gate.
 * </pre>
 * The first pass of this block understated the demand (it assumed ~1.3
 * region rows per column and a smaller footprint) and concluded L1 96
 * fits; it does not. Region rows are 4 sections tall with boundaries at
 * y = 0/64/128, so an ordinary sea-level surface band straddles TWO
 * rows more often than one. That correction is why
 * {@code FarFieldConfig.DEFAULT_L1_RADIUS_CHUNKS} is 64: larger radii
 * stay available on the slider, they simply stop short of their
 * nominal number.
 * When the gate closes, promotion stalls and counts — the far ring
 * simply stops short of L1 until ids free up; correctness never spends
 * a drop counter. Backstop: a LIVE section hitting the region wall
 * force-evicts far entries first ({@code forceEvictFarLocked}),
 * requeueing the section exactly like the wave-11 retained ladder.
 *
 * <h2>Failure posture</h2>
 * Any throwable in the pump, the mesh hop or the admission drain
 * latches {@link #broken} (logged once), which stops all promotion and
 * demotion for the session; already-admitted far sections keep drawing
 * and die with the world. Structurally, no code path here or in the
 * residency's far methods touches droppedArenaFull / droppedOversize /
 * droppedRegionBudget / droppedEncoding, so a far-field bug can never
 * turn the mod passive (landmine L4).
 */
public final class FarFieldResidency {

    // ------------------------------------------------------------------
    // Counters (the W3 brief's list + the water-opaque honesty counter).
    // Public LongAdders for the W4 bench export, the FarField pattern.
    // ------------------------------------------------------------------

    /** Gauge: far sections currently resident (add on admit, subtract on release). */
    public static final LongAdder farSectionsResident = new LongAdder();
    /** Shell read requests issued to the store. */
    public static final LongAdder farShellRequests = new LongAdder();
    /** Sections produced by the off-thread mesher. */
    public static final LongAdder farMeshedSections = new LongAdder();
    /** Sections admitted into the terrain residency (drawable). */
    public static final LongAdder farAdmissions = new LongAdder();
    /** Sections released (demote, supersede, evict, empty-compile). */
    public static final LongAdder farReleases = new LongAdder();
    /** Pumps or sections stalled by the region/arena budget gates. */
    public static final LongAdder farPromoteBudgetStalls = new LongAdder();
    /** Shell cells skipped for an unresolvable palette entry. */
    public static final LongAdder farMeshSkippedCells = new LongAdder();
    /** Far meshing/admission failures (latches {@link #broken}-adjacent paths). */
    public static final LongAdder farMeshErrors = new LongAdder();
    /**
     * Shells that arrived AFTER the scan had written the column off as
     * absent, i.e. columns that would otherwise have stayed a hole in the
     * horizon until the next camera chunk crossing. Compare against
     * {@code ExtractDispatch.farExtracts}: a ratio near 1.0 while
     * travelling is the signature of the walking-away seam gap.
     */
    public static final LongAdder farLateShells = new LongAdder();
    /**
     * Shells written while a read for the same column was still in
     * flight (queued, running, or posted but undrained). Without the
     * rescue in {@link #onShellWritten} these were LOST WAKEUPS: the
     * read's MISS lands after the notification and writes the column off
     * for the session. Nonzero means this defect is firing. Kept
     * separate from {@link #farLateShells} on purpose - that adder is
     * the published ratio-against-farExtracts diagnostic, and folding
     * these in would make pre3 numbers incomparable with pre2.
     */
    public static final LongAdder farInFlightRescues = new LongAdder();
    /**
     * APRON STALENESS (pre19): resident columns filed for a re-mesh
     * because a NEIGHBOUR they apron-read was rewritten - one increment
     * per column newly entered into {@link #apronRefreshColumns}, not per
     * write. Read it as a fanout ratio against
     * {@code FarField.farStoreWrites}: a flat zero while the owner edits
     * terrain at the far ring's inner edge means the fanout is dead, and
     * a ratio near 8 means the write stream is landing on isolated
     * resident ground (the dedupe is not firing, which on a travel leg
     * would mean the arc assumption below is wrong).
     */
    public static final LongAdder farApronRefreshFiled = new LongAdder();
    /**
     * APRON STALENESS (pre19): neighbour re-reads actually ISSUED by
     * {@link #drainApronRefreshColumns}. The pair with
     * {@link #farApronRefreshFiled} is the whole cost story: filed minus
     * issued is what the {@value #APRON_REFRESH_ISSUES_PER_PUMP}-per-pump
     * cap and {@link #stillWanted} between them threw away, and a
     * persistent gap of thousands means the cap is too tight for the
     * write rate rather than that the seam is closed.
     */
    public static final LongAdder farApronRefreshIssued = new LongAdder();
    /**
     * Far sections released because the REAL section landed at their
     * position (the residency's slot steal). This is the only way a far
     * copy should ever leave the INNER edge; a travel leg that sees this
     * at zero means a distance sweep is winning the race instead and the
     * seam is holed.
     */
    public static final LongAdder farSupersededByVanilla = new LongAdder();
    /**
     * Far sections refused admission because a NEAR-FIELD owner held the
     * position (live, retained, or a queued vanilla upload). Every one
     * of these is a section that is covered on screen RIGHT NOW and will
     * need the far copy the moment its owner leaves; since seam step 4
     * that moment announces itself - the free parks and files the demand
     * - so no watch is armed. See {@link #onFarBlockerReleased}.
     */
    public static final LongAdder farContestedSections = new LongAdder();
    /**
     * Contested positions whose near-field owner has since let go, i.e.
     * watch discharges that reached the walker. Zero on a travel leg
     * that logged a healthy {@link #farContestedSections} would mean the
     * recovery is not firing and the hole this fix closes is back with
     * it; the two are not
     * expected to match, because a position whose owner never leaves is
     * a position vanilla keeps drawing.
     */
    public static final LongAdder farBlockersReleased = new LongAdder();
    /** Fluid faces drawn opaque (the pre1 water decision, ShellMesher javadoc). */
    public static final LongAdder farWaterOpaqueQuads = new LongAdder();

    /**
     * Times vanilla threw its whole compiled geometry away and rebuilt it
     * while we were watching ({@link #detectVanillaReload}). One per render
     * distance change, resource reload or graphics change; a number that
     * climbs on its own means something is invalidating every frame and
     * the far field will never leave its stand-down.
     */
    public static final LongAdder farVanillaReloads = new LongAdder();

    /**
     * Pumps the far field spent standing down through one of those
     * rebuilds. At 60 fps a healthy render-distance change is a few dozen;
     * hundreds means {@link #MAX_STAND_DOWN_NANOS} is doing the releasing
     * rather than vanilla's compile queue emptying, which is worth knowing
     * but is not itself a defect.
     */
    public static final LongAdder farStandDownPumps = new LongAdder();

    /**
     * L9: far-field RELOADS, i.e. times a mesh-time appearance setting
     * moved and {@link #reloadFarField} dropped the whole far field so the
     * ordinary near-first refill could bring it back. One per mesh-time
     * setting change noticed with a world loaded (the reload itself is a
     * no-op when nothing is resident); a number that climbs on its own
     * means something is moving
     * {@code FarFieldConfig.farMeshSignature()} behind the settings
     * screen, which would keep the horizon permanently rebuilding.
     */
    public static final LongAdder farReloads = new LongAdder();

    /** Sections dropped by those reloads (the reload's size, cumulative). */
    public static final LongAdder farReloadSections = new LongAdder();

    // ------------------------------------------------------------------
    // Admission-outcome tallies (the pre6 census). PURE COUNTERS: every
    // one of the four increments below sits beside a decision that was
    // already made, and removing them all again would leave the walker
    // making exactly the same choices in exactly the same order. They
    // exist because {@link #farAdmissions} counts only the OK arm, so
    // "the section never made it" and "the section was never offered"
    // were indistinguishable from outside, and the difference between
    // them is the difference between an admission bug and a read bug.
    // ------------------------------------------------------------------

    /**
     * Sections refused with {@link #ADMIT_SKIPPED}: the far field's OWN
     * copy is already at that position.
     *
     * <p>A skip is TERMINAL for that section, by deliberate design
     * ({@link MeshedColumn#incomplete} is not set for it, because every
     * already-resident section of a retried column reports skipped and
     * treating that as a hole would re-request the column forever). That
     * is correct here precisely because the section IS drawn - by us. A
     * high count is the ordinary signature of a refill re-read, not of a
     * hole; the refusals that used to hide in this number and really
     * were holes now count as {@link #farContestedSections}.</p>
     */
    public static final LongAdder farAdmitSkipped = new LongAdder();
    /**
     * Sections refused with {@link #ADMIT_NO_BUDGET}: the absolute
     * ceilings (region ids, arena bytes) or, since B2, the FILL GUARD
     * itself — {@code admitFarSectionLocked} now turns a non-bridged
     * admission away while {@code farPromotionHasRoom()} is closed, which
     * is where that guard moved to when the M4 recovery drain stopped
     * being gated at the producer. Unlike a skip this is recoverable: it
     * marks the column {@link #partialColumns} and the scan comes back for
     * it.
     *
     * <p><b>The counter to watch in a playtest.</b> At the owner's
     * settings the ring is bigger than the region budget, so this SHOULD
     * climb steadily once the horizon has filled — that is the guard doing
     * its job, and the number that says far fill is being stopped before
     * it can reach {@code forceEvictFarLocked} or the retained pressure
     * sweep. What must NOT climb with it is
     * {@code TerrainResidency.ledgerEvictedWall()}: parked exchanges
     * bypass the guard, so the two rising together would mean the bypass
     * is not working and the seam is being spent for memory.</p>
     *
     * <p>Kept separate from {@link #farPromoteBudgetStalls}, which also
     * counts whole PUMPS that never issued a read because
     * {@code farPromotionHasRoom()} was false - two very different
     * events that were sharing one number.</p>
     */
    public static final LongAdder farAdmitNoBudget = new LongAdder();
    /**
     * Sections deferred with {@link #ADMIT_DEFER} (staging ring full).
     * Never lost: the same section is retried on the next pump. High
     * next to {@link #farAdmissions} means the far field is upload-bound
     * rather than read-bound.
     */
    public static final LongAdder farAdmitDeferred = new LongAdder();
    /**
     * Columns that finished their whole admission pass with an EMPTY
     * manifest: read, decoded, meshed, every section offered, not one
     * admitted. The column is then dropped from {@link #queuedSet} by
     * {@link #retireColumn} and entered in NO other set, so the only
     * things that can ask for it again are a filed successor demand
     * ({@link #onFarBlockerReleased}) and the next camera CHUNK crossing
     * re-arming the ring walk - for a stationary player, only the
     * former. This is the cumulative form of the census's
     * {@link #COL_DROPPED} bucket and the most direct evidence there is
     * for "whole chunks entirely missing".
     *
     * <p>Deliberately not counted for a column retired EARLY (stale
     * generation, or it left the ring while queued): those are correct
     * outcomes, and folding them in would hide the defect inside the
     * normal traffic of a moving camera.</p>
     */
    public static final LongAdder farColumnsAllSkipped = new LongAdder();

    /**
     * N5: columns filed for a budget-refusal retry ({@link #holedColumns}).
     * Reads as the pressure on the region budget seen from the walker's
     * side; if it climbs while {@code maxRegions()} is not the binding
     * arm, look at the arena guard instead.
     */
    public static final LongAdder farHolesFiled = new LongAdder();

    /**
     * N5: budget-refusal retries actually ISSUED, i.e. holes healed
     * without the player moving. The number that says the stationary
     * camera now heals the ring; it was structurally zero before pre13.
     */
    public static final LongAdder farHolesDrained = new LongAdder();

    /**
     * N5: ring passes re-armed because work was still outstanding and the
     * camera had not moved - the backstop behind {@link #holedColumns}
     * for any hole nothing thought to file.
     *
     * <p>Expected to be small and to STOP. A value that climbs forever at
     * one per {@value #IDLE_RESCAN_NANOS} nanoseconds means the ring
     * genuinely cannot be completed at these settings (the store has no
     * shells, or the region budget is short), not that the rescan is
     * broken - read it with {@code farAdmitNoBudget} and
     * {@code farExtractSweepOwed}.</p>
     */
    public static final LongAdder farIdleRescans = new LongAdder();

    /**
     * pre13 audit: times the budget-refusal retry loop was told to stand
     * down because its retries were provably futile.
     *
     * <h2>The hole in the N5 termination argument this closes</h2>
     * <p>{@link #drainHoledColumns} and
     * {@link #maybeRescanForOutstandingWork} both argue termination from
     * {@code farPromotionHasRoom()}: retry only when the retry can
     * succeed. That is sound for the arm of the gate that method
     * measures - the region-id count - and it is NOT sound for the other
     * two producers of {@link #ADMIT_NO_BUDGET}, which
     * {@code farFillHasRoomLocked} cannot see:
     * {@code RegionStore.hasCapacityFor} and, the reachable one,
     * {@code TerrainArena.allocQuads} returning {@code ALLOC_FAILED}.
     * The arena arm of that guard is measured against the DEVICE CEILING
     * while the allocation is made in the arena's CURRENT size, and the
     * far admission path does not grow the arena (only the live upload
     * drain does). So a stationary player whose arena is physically full
     * but far below the ceiling had a guard that said yes and an
     * allocator that said no, forever - and the retry loop would spend
     * every {@value #MAX_IN_FLIGHT} read slot re-reading, decoding and
     * MESHING the same refused columns at
     * {@value #HOLED_DRAIN_PER_PUMP} a pump for the rest of the session,
     * starving the ring walk of the frontier reads it needs to make any
     * progress at all. Unbounded work with no possible outcome is the
     * failure mode the whole class is written to avoid.</p>
     *
     * <p>Nonzero here means exactly that state: the guard is open and
     * refusals are still arriving. It is the number to bring to the
     * arena, not to the region budget - {@code farAdmitNoBudget} climbing
     * with this at zero is the ordinary region-gate story instead.</p>
     */
    public static final LongAdder farHoleRetryBackoffs = new LongAdder();

    // ------------------------------------------------------------------
    // Admission outcome codes (TerrainResidency reports one per polled
    // section; see onAdmissionOutcome)
    // ------------------------------------------------------------------

    /** Section admitted; advance to the next. */
    public static final int ADMIT_OK = 0;
    /** Staging ring full; retry the SAME section next pump. */
    public static final int ADMIT_DEFER = 1;
    /** Region/arena budget refused it; drop the section, count a stall. */
    public static final int ADMIT_NO_BUDGET = 2;
    /**
     * The position already holds a FAR section - ours. TERMINAL, and the
     * only refusal that is: there is nothing to recover, and every retry
     * of an already-resident section would report this again, which is
     * the loop {@link MeshedColumn#incomplete} refuses to enter. The one
     * admission this does NOT stop is a REFRESH ({@link Admission#refresh},
     * seam step 2), which exists precisely to displace our own stale copy
     * and comes back as {@link #ADMIT_REPLACED} instead.
     *
     * <p>Also reported when the position is contested but the residency
     * could not arm a watch for it (its contested set is at its cap), so
     * the walker's rule stays simple: SKIPPED means nobody will ever
     * come back for this section.</p>
     */
    public static final int ADMIT_SKIPPED = 3;
    /**
     * A NEAR-FIELD owner holds the position - a retained copy, a queued
     * vanilla upload, or a live section - and the residency has ARMED a
     * watch on it. Not terminal: "somebody else holds it FOR NOW". The
     * walker drops the section exactly like a skip (no in-place retry,
     * no {@link MeshedColumn#incomplete} mark, no re-scan) and waits to
     * be told the owner left, through {@link #onFarBlockerReleased}.
     *
     * <p>The distinction is the whole pre6 fix. Before it, a far column
     * that landed on a live or retained near section was written off for
     * good: when that owner went away nothing re-requested the far copy,
     * and the column stayed a hole until the camera walked it out past
     * {@code L1+2} and back in. Since pre2 put the ring's inner edge on
     * vanilla's compile DISC, far columns land inside the vanilla grid
     * as a matter of course, so that was not an edge case - it is the
     * owner's "the ring around us can have missing chunks".</p>
     */
    public static final int ADMIT_CONTESTED = 4;
    /**
     * SEAM step 2: the section REPLACED our own resident far copy in
     * place - {@code admitFarSectionLocked}'s refresh swap, the
     * {@code previousOwner} arm's bridged exchange generalised to a
     * FAR predecessor. Free and bind in one lock hold under one
     * draw-epoch bump, so the position is drawable in every snapshot
     * across the exchange. Reported only for an {@link Admission}
     * carrying {@code refresh}; the walker counts it as an admission
     * but must NOT move the {@link #farSectionsResident} gauge (the
     * swap freed exactly as much as it bound) and must NOT double-enter
     * the section in the column manifest (it is already listed - see
     * {@link #retireColumn}'s per-section merge).
     */
    public static final int ADMIT_REPLACED = 5;

    /** Requests in flight cap (the W3 brief's walker budget). */
    private static final int MAX_IN_FLIGHT = 8;
    /**
     * Ring positions examined per pump. Bounds the steady-state scan,
     * where every candidate is already known and so spends no in-flight
     * budget; without it one pump walks the whole annulus (see
     * {@link #continuePromotionScan}).
     *
     * <p>RAISED FROM 512 (pre3, defects E4/E9). The budget has to be read
     * against the re-arm in {@link #pumpInner}, which rewinds the cursor
     * to the innermost ring on every camera CHUNK crossing. One pass over
     * the L1-64 annulus at rd 16 is {@code sum(8r, r=13..64) = 16,016}
     * probes; at 512 per pump that is 32 pumps at best, and more whenever
     * the in-flight cap zeroes a pump's progress (the loop returns before
     * decrementing its step budget). So anything faster than about one
     * chunk per half second rewound the scan before it had ever reached
     * the far half of the ring, and nothing else can find those columns:
     * {@link #drainLateShells} is fed only by {@link #onShellWritten},
     * which never fires beyond rd+3 of the camera. That is the owner's
     * "first join goes outward from the centre but still misses a lot of
     * chunks", and why the outer ring never filled while travelling.
     * 4096 finishes a pass in about four pumps, which beats a chunk
     * crossing at creative fly and elytra speed, leaving the rewind free
     * to do its real job: serving the columns the near field just
     * stopped drawing FIRST. Cost is a fraction of a millisecond of
     * integer arithmetic and hash probes per pump, and the read pipeline
     * is still throttled by {@link #MAX_IN_FLIGHT}, which the loop tests
     * before it spends a step.</p>
     */
    private static final int SCAN_STEPS_PER_PUMP = 4096;
    /**
     * How far INSIDE vanilla's compile disc the far field is willing to
     * cover, in chunks (class javadoc, section H1). The disc is what
     * vanilla will eventually draw; its outer rim is the part it has not
     * drawn yet, and until this constant existed nobody drew that rim.
     *
     * <p>4 chunks is 64 blocks: about 2.1 seconds of vanilla compile lag
     * at elytra cruise (~30 m/s, 1.875 chunks per second), against the
     * single chunk — 0.53 seconds — the old inner edge allowed. It is
     * deliberately a small constant and not a radius fraction, because
     * what it has to cover is a LATENCY times a SPEED, and neither of
     * those scales with the render-distance slider.</p>
     */
    private static final int DEFAULT_HANDOVER_BAND_CHUNKS = 4;
    /**
     * Ceiling on the handover band. Past this the band stops being the
     * un-built rim and starts being ordinary near terrain that vanilla
     * has already built, which costs one contested admission pass per
     * column for nothing (see {@link #nearOwnedColumns}).
     */
    private static final int MAX_HANDOVER_BAND_CHUNKS = 8;
    /**
     * The band as resolved once at class load from
     * {@code meshelium.farfield.handoverBandChunks} (the
     * {@code FarFieldConfig} property prefix), clamped to
     * {@code 0..}{@value #MAX_HANDOVER_BAND_CHUNKS}.
     *
     * <p>ZERO restores the pre6 inner edge exactly — the near-cover test
     * falls back to {@code nearEdge} and every other change here becomes
     * inert, because a memo of fully-contested columns cannot be
     * populated by columns the walker never wants. That is the A/B lever
     * for the playtest that has to confirm this fix, and it is the reason
     * this is a property rather than a bare constant.</p>
     */
    private static final int HANDOVER_BAND_CHUNKS = resolveHandoverBand();
    /** Column releases per pump (the leaf-tier walker's 64/pump shape). */
    private static final int RELEASE_COLUMN_BUDGET = 64;
    /**
     * Ceiling on {@link #nearOwnedColumns}. The old outer bound (the
     * contested watch's cap) died with the watch at seam step 4, so this
     * cap is now the set's ONLY bound - kept at the same figure. Past it
     * the memo is dropped wholesale, which costs one redundant read per
     * column and never a hole.
     */
    private static final int NEAR_OWNED_CAP = 8192;

    /**
     * N5: how long the walker will sit on a completed ring pass with work
     * still outstanding before walking it again, in nanoseconds.
     *
     * <p>Two seconds. The cost is one pass of hash probes -
     * {@value #SCAN_STEPS_PER_PUMP} a pump, so about 15 pumps and under
     * 60,000 probes at L1 120 - which spread over two seconds is a
     * fraction of a millisecond of game thread per second. It is the
     * backstop, not the mechanism: {@link #holedColumns},
     * {@link #unblockedColumns}, {@link #refreshColumns},
     * {@link #apronRefreshColumns} and {@link #lateShells} are all
     * event-driven and heal in one pump (the apron set within
     * {@code ceil(n / 2)} pumps, its cap being the one deliberate
     * exception). This exists so that a hole
     * NOBODY filed still heals without the player moving, which is the
     * property the owner actually asked for.</p>
     *
     * <p>Gated on {@code farPromotionHasRoom()} for the same reason the
     * holed drain is: re-walking a ring the residency cannot admit into
     * is work with no possible outcome.</p>
     */
    private static final long IDLE_RESCAN_NANOS = 2_000_000_000L;

    /** Cap on {@link #holedColumns}; overflow re-arms the ring walk wholesale. */
    private static final int HOLED_COLUMN_CAP = 4096;

    /** At most this many budget-refusal retries issued per pump. */
    private static final int HOLED_DRAIN_PER_PUMP = 8;
    /** Result-queue bound; unreachable while MAX_IN_FLIGHT holds. */
    private static final int RESULT_QUEUE_CAP = 64;

    /**
     * Shortest stand-down after a vanilla reload, in nanoseconds.
     *
     * <p>{@code LevelRenderer.hasRenderedAllSections()} is TRUE the
     * instant after {@code invalidateCompiledGeometry} runs, because
     * {@code SectionRenderDispatcher.clearCompileQueue()} (javap ip
     * 141-145) empties the queue before the new {@code ViewArea} is built
     * and nothing has been scheduled into it yet. So the settle test needs
     * a floor, or the far field would decide the storm was over in the
     * same frame it started. 500 ms is about ten client ticks, comfortably
     * past the frame in which the occlusion BFS re-seeds and starts
     * scheduling.</p>
     */
    private static final long MIN_STAND_DOWN_NANOS = 500_000_000L;
    /**
     * Hard release for the stand-down, in nanoseconds. Vanilla's compile
     * queue may legitimately never empty (a player flying at elytra speed
     * through fresh terrain keeps it full), so "wait for quiet" needs a
     * deadline or one render-distance change would park the far field for
     * the rest of the session. Ten seconds is long enough for the reload
     * proper and short enough that the horizon comes back while the player
     * is still looking at it.
     */
    private static final long MAX_STAND_DOWN_NANOS = 10_000_000_000L;
    /**
     * Consecutive pumps with vanilla's compile queue empty before the far
     * field calls the storm over. More than one because the queue empties
     * between BFS waves, not only at the end.
     */
    private static final int STAND_DOWN_SETTLED_PUMPS = 8;

    // Result kinds (IO thread -> pump).
    private static final int RESULT_MISS = 0;
    private static final int RESULT_DECODED = 1;
    private static final int RESULT_MESHED = 2;
    private static final int RESULT_ERROR = 3;

    private static final class IoResult {
        final int gen;
        final long chunkKey;
        final int kind;
        final ShellCodec.Shell shell;      // DECODED only
        /**
         * S1 (pre18, R5): the column's eight neighbours, decoded by the
         * same IO task, laid out {@code (dz + 1) * 3 + (dx + 1)} with a
         * null centre and a null for every neighbour the store has
         * nothing for. Null wholesale when the apron is off. DECODED only.
         */
        final ShellCodec.Shell[] ring;
        final ShellMesher.Result meshed;   // MESHED only
        /** Seam step 2: this read was issued by {@link #drainRefreshColumns}. */
        final boolean refresh;

        IoResult(int gen, long chunkKey, int kind,
                ShellCodec.Shell shell, ShellMesher.Result meshed, boolean refresh) {
            this(gen, chunkKey, kind, shell, null, meshed, refresh);
        }

        IoResult(int gen, long chunkKey, int kind, ShellCodec.Shell shell,
                ShellCodec.Shell[] ring, ShellMesher.Result meshed,
                boolean refresh) {
            this.gen = gen;
            this.chunkKey = chunkKey;
            this.kind = kind;
            this.shell = shell;
            this.ring = ring;
            this.meshed = meshed;
            this.refresh = refresh;
        }
    }

    /** One meshed column waiting for (or mid-way through) admission. */
    private static final class MeshedColumn {
        final int gen;
        final long chunkKey;
        final ShellMesher.SectionMesh[] sections;
        /** Next section to admit. */
        int next;
        /** Section Ys actually admitted (become the release manifest). */
        final IntArrayList admittedSy = new IntArrayList(4);
        /**
         * At least one section was refused for BUDGET, so the column is
         * resident with a hole the scan must be allowed to refill.
         *
         * <p>Deliberately NOT set for {@link #ADMIT_SKIPPED}: that means
         * OUR far section is already at the position, and marking the
         * column would re-request it forever, because the retry reports
         * skipped again for every section that came back. Deliberately
         * not set for {@link #ADMIT_CONTESTED} either, for the opposite
         * reason: that hole IS real, but re-scanning for it is the wrong
         * instrument - the retry would be refused by the same near-field
         * owner every time, at one read per camera crossing, until the
         * owner happened to leave. The recovery is event-driven instead
         * ({@link #onFarBlockerReleased}), so it costs one read WHEN the
         * blocker goes and nothing at all before that. Since H1 that is
         * not merely the cheaper choice, it is the load-bearing one: the
         * handover band puts hundreds of vanilla-owned columns inside the
         * wanted set, and {@link #nearOwnedColumns} is what stops the ring
         * walk paying for them twice a second.</p>
         */
        boolean incomplete;
        /**
         * Sections of this column refused with {@link #ADMIT_CONTESTED},
         * i.e. positions a near-field owner holds and the residency has
         * armed a watch on. Compared against the section count in
         * {@link #retireColumn}: all of them contested and none admitted
         * means the near field owns the WHOLE column and the walker may
         * stop asking until a watch discharges
         * ({@link #nearOwnedColumns}). Any other refusal in the pass —
         * a budget stall, an already-ours skip, a stale retirement —
         * leaves the count short of the section total and the memo unset,
         * which is the safe direction: the ring walk keeps its retry.
         */
        int contested;
        /**
         * Seam step 2: this column was read by {@link #drainRefreshColumns}
         * because a FRESHER shell was stored for it while it was drawing.
         * Carried onto every {@link Admission} it offers, where it is the
         * residency's permission to REPLACE our own resident far section
         * in place instead of skipping it. Deliberately a property of the
         * REQUEST and not of "is the column resident right now": an
         * ordinary re-read of a resident column (a refill, a discharged
         * watch) must keep coming back as cheap skips, or every false
         * wakeup would re-upload identical geometry.
         */
        final boolean refresh;

        MeshedColumn(int gen, long chunkKey, ShellMesher.SectionMesh[] sections,
                boolean refresh) {
            this.gen = gen;
            this.chunkKey = chunkKey;
            this.sections = sections;
            this.refresh = refresh;
        }
    }

    /**
     * What {@code TerrainResidency} admits: one far section's identity +
     * mesh. {@code refresh} is seam step 2's replace-in-place permission
     * ({@link MeshedColumn#refresh}): with it set, a position already
     * holding OUR far section is swapped rather than skipped, and the
     * outcome comes back as {@link #ADMIT_REPLACED}.
     */
    public record Admission(int sx, int sy, int sz, EncodedSectionMesh mesh,
            boolean refresh) {}

    // ------------------------------------------------------------------
    // State. Render-thread confined unless noted.
    // ------------------------------------------------------------------

    /** IO -> pump handoff; guarded by its own monitor, never nested. */
    private static final ArrayDeque<IoResult> resultQueue = new ArrayDeque<>();

    /**
     * World era. Bumped on world join/leave/level-identity change and on
     * residency dispose; results and columns carrying an older value are
     * discarded without touching bookkeeping (their maps were reset).
     * Written on the render thread, captured by value into IO closures.
     */
    private static volatile int generation;

    /** Latch: the far field is off for the session (logged once). */
    private static volatile boolean broken;
    private static final AtomicBoolean brokenLogged = new AtomicBoolean();

    /** Chunk columns with at least one admitted far section: key -> admitted sy list. */
    private static final Long2ObjectOpenHashMap<IntArrayList> residentColumns =
            new Long2ObjectOpenHashMap<>();
    /** Chunk columns with a request somewhere in the two-hop pipeline. */
    private static final LongOpenHashSet inFlightSet = new LongOpenHashSet();
    /**
     * Chunk columns the store answered "no shell" (or meshed to nothing).
     * Skipped by the scan so misses are not re-read every re-arm;
     * invalidated per column by {@link #onShellWritten} when the
     * extractor stores a fresh shell, wholesale on world change.
     * TODO(pre2): the save-importer needs a bulk invalidation hook here.
     */
    private static final LongOpenHashSet absentSet = new LongOpenHashSet();
    /**
     * Columns already meshed and queued for admission. Reserved against
     * the promotion scan: without this a column that is meshed but not
     * yet admitted matches none of the other skip sets, so every camera
     * re-arm re-reads it from disk, re-decodes and re-meshes it. Kept
     * SEPARATE from {@link #inFlightSet} on purpose - that set is what
     * enforces {@link #MAX_IN_FLIGHT}, and parking queued columns in it
     * would throttle the read pipeline behind the admission budget.
     */
    private static final LongOpenHashSet queuedSet = new LongOpenHashSet();
    /**
     * Resident columns that are missing at least one section because a
     * budget refusal turned them away. The promotion scan does NOT skip
     * these, so the hole refills once ids or arena free up; sections
     * already resident come back cheaply as skips. Membership is
     * cleared the next time the column retires complete.
     *
     * <p>Force-evicted holes are deliberately not tracked here: that
     * path only runs at the hard memory wall (rarer now that far
     * promotion stops below the retained pressure sweep) and it heals
     * when the camera moves the column out of the hysteresis band.</p>
     */
    private static final LongOpenHashSet partialColumns = new LongOpenHashSet();
    /**
     * Resident columns that LOST a far section to a residency-side path
     * (vanilla supersede, empty recompile, force-evict) while keeping at
     * least one other, so {@link #residentColumns} still lists them and
     * the promotion scan's completeness test reads them as whole.
     *
     * <p>Since pre2 the inner edge is not a distance demote: a column
     * inside vanilla's disc is retired by the ARRIVAL of the real
     * sections. Arrival is per POSITION, and vanilla's compile set is
     * the union over time of BFS cylinder AND camera frustum AND chunk
     * arrived - so a transit covers SOME sections of a column and not
     * others (the surface section over a band floor a neighbour pushed
     * down, or every section of a column that passed BEHIND the player
     * and was never in the frustum). Those columns come out the back of
     * the near field with a hole and, without this mark, no way to ask
     * for it back short of crossing L1+2. That is the owner's hollowed
     * chunks, and a contributor to the general holes.</p>
     *
     * <p>The mark is ONE-SHOT: {@link #issueRequest} consumes it, so a
     * column costs exactly one extra read per loss even when every
     * section comes back already-ours. Deliberately NOT reusing
     * {@link #partialColumns}: that set is discharged by
     * {@link #retireColumn}, which returns early when nothing was
     * admitted, so an all-skipped retry would leave the mark standing
     * and re-read the column on every camera crossing forever.</p>
     */
    private static final LongOpenHashSet refillColumns = new LongOpenHashSet();
    /**
     * Columns whose shell arrived AFTER the scan had already written them
     * off as absent. This is the ORDINARY case when walking away, not an
     * edge case: crossing a chunk boundary re-arms the scan, the ring walk
     * reaches the trailing strip in that same pump, and only later does
     * the server's forget packet reach {@code ClientLevel.unload} so the
     * extractor can write the shell. The client's storage window is
     * {@code max(2, viewDistance) + 3} chunks wide, so a chunk is usually
     * not unloaded until THREE chunks past where it stopped being drawn.
     *
     * <p>Clearing the absent memo alone was not enough: nothing re-scans
     * a column until the NEXT camera chunk crossing, so the horizon
     * carried a hole for a whole chunk of travel exactly at the seam the
     * near field had just stopped covering. That is the owner's "flashing
     * for a second while the lod is popping in".</p>
     */
    private static final LongOpenHashSet lateShells = new LongOpenHashSet();
    /**
     * Past this many pending late shells a wholesale re-arm is cheaper
     * than carrying the list (a render-distance shrink or a teleport
     * abandons whole rings at once).
     */
    private static final int LATE_SHELL_CAP = 4096;
    /**
     * SEAM step 2 (docs/FARFIELD-SEAM-DESIGN.md): columns the far field
     * is ALREADY DRAWING whose stored shell just got fresher
     * ({@link #onShellWritten} on a resident, wanted column - the
     * player edited it, or the sweep upgraded a degraded record). Each
     * is owed a re-read whose admission REPLACES the resident sections
     * in place ({@code admitFarSectionLocked}'s refresh swap): the old
     * mesh draws until the new one binds, in the same lock hold, under
     * one draw-epoch bump. This is what deleted
     * {@code releaseStaleResident}, whose release-then-refill hop
     * un-drew an edited column for the whole store round trip - the
     * audit's "Save pipeline tears out drawn far columns".
     *
     * <p>Uncapped on purpose, the {@link #unblockedColumns} argument:
     * bounded by the SOURCE, because only a column in
     * {@link #residentColumns} can enter, one entry per column. Kept
     * across a vanilla-reload stand-down ({@link #onVanillaReload}) -
     * unlike the opinion sets, an entry here is a fact about the disk
     * versus the drawn mesh, and both survive the storm.</p>
     */
    private static final LongOpenHashSet refreshColumns = new LongOpenHashSet();
    /**
     * APRON STALENESS (pre19, docs/FARFIELD-WAVES.md "APRON STALENESS
     * ANSWERED"): resident columns whose own record is unchanged but
     * whose MESH is stale, because one of the eight neighbours they
     * apron-read was rewritten.
     *
     * <p>Since pre18 a column's mesh is a function of its neighbours'
     * stored contents as well as its own - {@code ShellMesher.Apron}
     * loads the eight neighbours' edge cells into
     * {@code occluders} (AO and contact probes), {@code fluidField}
     * (corner averages and flow), {@code airLight} (S5's per-corner
     * light donors) and the glassy set, and takes each neighbour's
     * {@code minY} as the fluid sampler's UNKNOWN floor. So rewriting C
     * makes every resident neighbour of C wrong along their shared
     * plane, and pre18 accepted that as staleness that "heals on their
     * own refresh or revisit". With S5's per-corner light the symptom
     * upgraded from a missing stretch of dark line to a light STEP along
     * the chunk plane, which is the thing desk-check 3 of "S3 AND S5
     * ANSWERED" exists to make zero.
     *
     * <p><b>Separate from {@link #refreshColumns}, and lower priority,
     * on purpose.</b> A direct refresh is a column whose OWN truth
     * changed - the player edited it and is looking at it. An entry here
     * is a one-cell seam correction on a column that is otherwise
     * correct, and it has no deadline. Filing both into one set would
     * put the seam corrections in front of the edits and, far worse, in
     * front of the fill: {@link #drainRefreshColumns} runs UNGUARDED and
     * ahead of {@link #drainLateShells}, {@link #drainHoledColumns} and
     * the ring walk, so an 8-way fanout over a travel leg's continuous
     * write stream would take the whole {@value #MAX_IN_FLIGHT}-deep read
     * pipeline away from net-new fill and turn a cosmetic seam into the
     * owner's "the ring around us can have missing chunks". The two
     * bounds below ({@link #APRON_REFRESH_RESERVED_SLOTS} and
     * {@link #APRON_REFRESH_ISSUES_PER_PUMP}) are what make that
     * structurally impossible.
     *
     * <p>Uncapped in SIZE for {@link #refreshColumns}' reason - only a
     * column already in {@link #residentColumns} may enter, one entry
     * per column - and drained with the same rules: a busy column keeps
     * its entry, a column that left the ring is dropped, and a column
     * that was RELEASED is simply dropped rather than re-filed as fill
     * (unlike a direct refresh, nothing here is owed a read: the
     * column's own record never changed, so the ordinary ring walk
     * brings it back with the fresh apron already on disk).</p>
     */
    private static final LongOpenHashSet apronRefreshColumns = new LongOpenHashSet();
    /**
     * APRON STALENESS: the hard per-pump ceiling on neighbour re-reads,
     * and the whole cost bound of the fanout.
     *
     * <p>Two, and the number is argued from both ends. It is the second
     * of the drain's two bounds - see
     * {@link #APRON_REFRESH_RESERVED_SLOTS} for the first, which is the
     * one that makes starvation structurally impossible rather than
     * merely unlikely.</p>
     *
     * <p><b>As a CEILING</b>: two read ISSUES per pump, whatever the
     * write rate, so the fanout cannot convert a burst of writes into a
     * burst of reads. The naive shape has no such bound - {@link
     * #drainRefreshColumns} stops only at {@value #MAX_IN_FLIGHT} - so
     * an 8-way fanout over a travel leg's continuous write stream can
     * hold every slot ahead of the fill and starve the horizon.</p>
     *
     * <p><b>As a FLOOR</b>: 2 per pump against the config's 60 fps
     * frame-rate floor is 120 columns a second, which is more than the
     * travel write rate this fanout is amplifying (96 columns/s of new
     * ground at creative flight, docs/FARFIELD-WAVES.md "P3 ANSWERED"
     * 1e). So on a quiet pipeline the drain keeps up with the writes
     * themselves and only the dilation of the frontier arc can lag - and
     * on a travel leg that arc is being re-read by the ring walk anyway,
     * with the fresh apron already on disk. The case the owner actually
     * watches, a light source at a chunk plane, is at most 8 neighbours
     * on a settled ring and drains in four pumps: 33-66 ms.</p>
     */
    private static final int APRON_REFRESH_ISSUES_PER_PUMP = 2;
    /**
     * APRON STALENESS: read slots this drain may never take - the bound
     * that makes the priority ordering a guarantee instead of a habit.
     *
     * <p>{@link #drainApronRefreshColumns} issues only while
     * {@code inFlightSet.size() < MAX_IN_FLIGHT - } this, so it can
     * never leave fewer than {@value #APRON_REFRESH_RESERVED_SLOTS} of
     * the {@value #MAX_IN_FLIGHT} slots for the drains that run after it
     * - {@link #drainLateShells}, {@link #drainHoledColumns} and the
     * ring walk, i.e. all of net-new fill. <b>Half the pipeline, in
     * every pump, whatever the write rate</b>, which is the claim a
     * statistical share of throughput could not make: the far field owns
     * ONE IO thread and meshing shares it with store reads
     * ({@code FarField.submitCompute}), so at the slow end of the 1-3 ms
     * a column costs end to end, a per-pump issue cap alone would still
     * have let seam corrections take most of that thread.
     *
     * <p>The other direction is deliberate too, and it is why this is a
     * reserve rather than a hard slot count: when the pipeline is
     * already full of fill this drain issues NOTHING and the entries
     * wait. That is the right answer, not a starvation bug - a full
     * pipeline means the ring walk is re-reading this ground anyway, and
     * a ring-walk read carries the same fresh 3x3 the seam correction
     * wanted. The entries it defers are bounded by
     * {@link #residentColumns} and drain the moment the ring settles,
     * which is also the moment the player can see the seam.</p>
     */
    private static final int APRON_REFRESH_RESERVED_SLOTS = 4;
    /**
     * Columns owed a re-read because a NEAR-FIELD owner that had refused
     * one of their sections ({@link #ADMIT_CONTESTED}) has just let go -
     * the recovery half of the pre6 contested fix, fed by
     * {@link #onFarBlockerReleased} and drained beside
     * {@link #lateShells} on the very next pump.
     *
     * <p>Why a pump-drained set and not just a {@link #refillColumns}
     * mark: the refill mark is only ever consumed by the ring walk, and
     * the ring walk only runs while {@link #scanPending}, which for a
     * STATIONARY player is never. A blocker leaving is exactly the event
     * that does not move the camera - a chunk unloading behind the
     * player who stopped, retention evicting on a timer, a rebuild whose
     * successor never came - so hanging the recovery on the scan alone
     * would fix the moving case and leave the standing-still case
     * exactly as broken as it is today. Both marks are set: the set gets
     * the read issued now, the mark keeps the ring walk from skipping
     * the column as complete if the drain's budget deferred it.</p>
     *
     * <p>Bounded by the source since seam step 4: an entry appears only
     * when a parked position files a successor demand, the demand queue
     * is drained every pump, and this is a SET of columns - so it can
     * never outgrow the parked population, which the AWAITING quad
     * budget bounds.</p>
     */
    private static final LongOpenHashSet unblockedColumns = new LongOpenHashSet();
    /**
     * Columns the NEAR FIELD owns outright: every section offered came
     * back {@link #ADMIT_CONTESTED} and none was admitted. Skipped by the
     * ring walk exactly like {@link #absentSet}, and for the same reason —
     * re-reading them can only produce the same answer.
     *
     * <p>This set is what makes the handover band affordable. Without it,
     * widening the inner edge inside vanilla's disc (class javadoc H1)
     * would hand the ring walk a few hundred columns that vanilla has
     * already built, and it would re-read every one of them on every
     * camera CHUNK crossing — twice a second at elytra cruise — spending
     * the whole {@link #MAX_IN_FLIGHT} budget on columns that are on
     * screen and starving the ones that are not. That is the exact
     * failure the band exists to fix, arrived at from the other side.</p>
     *
     * <p>Discharge since seam step 4 is the PARK: a free of any of the
     * column's positions while it is still covered parks and files a
     * successor demand, and {@link #onFarBlockerReleased} removes the
     * memo first and unconditionally. {@link #issueRequest} clears it
     * too, so any other route back to the column supersedes the memo.
     * A position freed while UNCOVERED files nothing - the far field
     * does not want it at that moment, and vanilla draws nothing there
     * either - so a memo can outlive its facts in one narrow shape
     * (every section of a memo'd column freed inside vanilla's disc,
     * then the camera moves until the column is far-domain). The
     * wholesale discharges bound that staleness: a render-distance
     * change, a vanilla reload, and the N5 idle rescan below all drop
     * the memo set entire.</p>
     */
    private static final LongOpenHashSet nearOwnedColumns = new LongOpenHashSet();
    /**
     * N5: columns that came back from the admission pass WITHOUT the
     * geometry they were offered, because the residency had no room -
     * {@link #ADMIT_NO_BUDGET} - and which nothing else in this class will
     * ever ask for again while the player stands still.
     *
     * <h2>The bug this set closes, and it is the owner's exact words</h2>
     * <p>"if i position myself right and dont move it will stay
     * unrendered until i move around a bit." Every other recovery mark in
     * this class was already pump-drained for precisely that reason - see
     * {@link #unblockedColumns}, whose javadoc says it out loud: "the
     * ring walk only runs while scanPending, which for a STATIONARY
     * player is never". Two paths were left behind that door:</p>
     * <ol>
     *   <li>{@link #retireColumn} on a column where EVERY section was
     *       refused. {@code admittedSy} is empty, so the method returns
     *       before it can set {@link #partialColumns}, and its own
     *       comment states the consequence - the column "leaves here
     *       entered in no set at all, so nothing will ask for it again".
     *       At the owner's settings that is not rare: the region gate is
     *       {@code min(85% x 2048, 2048 - 700) = 1348} against a ring
     *       needing well over that, so wholesale refusal is the ordinary
     *       outcome for the outer band;</li>
     *   <li>{@link #onSectionEvicted}, which marks
     *       {@link #refillColumns} and nothing else - and that mark is
     *       consumed ONLY by the ring walk.</li>
     * </ol>
     * <p>Both leave a hole that a camera section crossing heals and
     * nothing else does, which is the reported behaviour exactly. (The
     * flicker's OTHER half - a held bridge expiring into a hole when the
     * read behind it was refused - died at seam step 4: a park has no
     * deadline, so a refused read leaves the real geometry drawing
     * instead of a hole standing.)</p>
     *
     * <h2>Why its drain is GATED when unblockedColumns' is not</h2>
     * <p>An unblocked column is a handover with a clock on it. A
     * budget-refused column is the opposite: re-reading it while the
     * guard is still closed can only earn another {@code ADMIT_NO_BUDGET}
     * and would spend the whole {@value #MAX_IN_FLIGHT} read pipeline
     * doing it, every pump, forever. So this drain runs only when
     * {@code TerrainResidency.farPromotionHasRoom()} is open - i.e.
     * exactly when the retry can succeed - which also makes the set
     * self-limiting: the entry is removed when it is read, and re-added
     * only if the fresh offer is refused again.</p>
     */
    private static final LongOpenHashSet holedColumns = new LongOpenHashSet();
    /** Meshed columns awaiting admission (drained under the residency lock). */
    private static final ArrayDeque<MeshedColumn> pendingAdmissions = new ArrayDeque<>();
    /** Sections across {@link #pendingAdmissions}; the residency's cheap gate. */
    private static int pendingAdmissionSections;

    // Walker arm state (the leaf-tier pattern: re-arm on camera crossing
    // or slider change, budgeted resume across pumps).
    private static long lastCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
    private static int lastNearEdge = -1;
    /**
     * {@link #coverRadius}{@code (lastNearEdge)}, cached at the re-arm.
     * Not recomputed per column: {@link #stillWanted} runs inside the
     * residency's LOCK window (through {@link #pollAdmission} and
     * {@link #onFarBlockerReleased}) and the scan calls it up to
     * {@value #SCAN_STEPS_PER_PUMP} times a pump, so the arithmetic is
     * done once per arm and read as a field thereafter.
     */
    private static int lastCoverEdge = -1;
    private static int lastL1 = -1;
    private static boolean scanPending;
    private static int scanRing;
    private static int scanIndex;
    private static boolean demotePending;
    /**
     * N5: {@code System.nanoTime()} when the ring walk was last armed, by
     * any route. The idle backstop's clock; see
     * {@link #maybeRescanForOutstandingWork}.
     */
    private static long lastScanArmNanos = System.nanoTime();
    /**
     * N5: columns retired with an empty manifest since the last arm.
     * A cheap "something went missing" signal for the backstop, distinct
     * from the lifetime {@link #farColumnsAllSkipped} counter, which
     * cannot say whether the loss was a minute ago or a session ago.
     */
    private static int farColumnsAllSkippedSinceScan;
    /**
     * pre13 audit: {@code farAdmitNoBudget.sum()} and
     * {@code farAdmissions.sum()} as of the last armed pump, and whether
     * the fill guard was open at that pump. Together they detect a
     * refusal the guard did not predict, with no forward progress beside
     * it — see {@link #farHoleRetryBackoffs}. Lifetime counters that are
     * never reset, so these need no per-world clearing.
     */
    private static long lastNoBudgetSeen;
    private static long lastAdmissionsSeen;
    private static boolean lastPumpGuardOpen;
    /**
     * pre13 audit: {@code System.nanoTime()} until which the
     * budget-refusal retries stand down. Compared with a subtraction, so
     * nanoTime's arbitrary (possibly negative) origin is not a problem.
     */
    private static long holeRetryBlockedUntilNanos = System.nanoTime();

    /**
     * Sentinel for the two appearance watches below: no reading taken yet,
     * so the first pump only establishes a baseline. Without it the very
     * first armed pump of a session would reload a ring that has nothing
     * resident and re-save a window that has just been extracted.
     */
    private static final int SIGNATURE_UNSET = Integer.MIN_VALUE;
    /**
     * {@code FarFieldConfig.farMeshSignature()} as of the last pump. When
     * it moves, the whole far field is reloaded
     * ({@link #reloadFarField}).
     *
     * <p>A WATCH rather than a callback from the settings screen, on
     * purpose: the screen is one of three ways these values move (a hand
     * edit of {@code meshelium-farfield.json} and a {@code -D} override
     * are the others, and both are documented as applying live), and a
     * watch costs four property lookups per pump against a walker that
     * already reads two.</p>
     */
    private static int lastMeshSignature = SIGNATURE_UNSET;
    /**
     * {@code FarFieldConfig.farSaveSignature()} as of the last pump. When
     * it moves, the extract-once tracker is dropped so the terrain the
     * client is still holding is written to the store again with the new
     * setting ({@code ExtractDispatch.forgetExtractedColumns}). Terrain
     * already on disk keeps what it was saved with, and the tooltips say
     * exactly that.
     */
    private static int lastSaveSignature = SIGNATURE_UNSET;

    /** Level identity watch — catches world changes no other hook saw. */
    private static WeakReference<Object> lastLevel = new WeakReference<>(null);

    /**
     * Identity watch on {@code LevelRenderer.viewArea()} — the far field's
     * detector for "vanilla just threw all of its terrain away".
     *
     * <p>Why the ViewArea and not the render-distance number: a fresh
     * {@code ViewArea} is exactly what
     * {@code LevelRenderer.invalidateCompiledGeometry} constructs
     * (javap ip 148-184) and it is the ONE object every full rebuild
     * replaces, whatever triggered it. The render-distance path reaches it
     * through {@code LevelExtractor.extract}, whose first four
     * instructions are
     * {@code if (options.getEffectiveRenderDistance() != lastViewDistance)
     * allChanged()} (javap ip 0-18), and {@code allChanged} sets
     * {@code shouldInvalidateCompiledGeometry} (ip 77-79) which the same
     * method consumes at ip 177-212. A resource reload, a graphics change
     * and {@code MesheliumExtendedRd}'s own stepped render-distance
     * backoff all arrive the same way, so one reference compare per pump
     * covers every one of them and cannot be fooled by a slider that ends
     * up back where it started.</p>
     *
     * <p>Weak so a dead ViewArea is never pinned by this class.</p>
     */
    private static WeakReference<Object> lastViewArea = new WeakReference<>(null);

    /** True while the far field is standing aside for a vanilla rebuild. */
    private static boolean standDown;

    /**
     * P3: the widest storage radius the client has held since the last
     * time it demonstrably finished abandoning chunks. See
     * {@link #sweepRadiusFor}. Reset with the rest of the per-world
     * state.
     */
    private static int heldSweepRadius;

    /** {@code System.nanoTime()} at the reload that started the stand-down. */
    private static long standDownStartNanos;
    /** Consecutive pumps seen with vanilla's compile queue empty. */
    private static int standDownSettledPumps;

    private FarFieldResidency() {
    }

    // ------------------------------------------------------------------
    // The pump (render thread; called by TerrainResidency.pump AFTER its
    // lock window — collect-under-LOCK happened there, this half acts)
    // ------------------------------------------------------------------

    /** Once per residency pump. Never throws (latches instead). */
    public static void pump() {
        if (broken) {
            return;
        }
        try {
            pumpInner();
        } catch (Throwable t) {
            latchBroken("far-field pump", t);
        }
    }

    private static void pumpInner() {
        Minecraft mc = Minecraft.getInstance();
        // Vanilla's compile queue, read from vanilla's own accessor:
        // hasRenderedAllSections() is
        //   sectionRenderDispatcher == null || dispatcher.isQueueEmpty()
        // (javap ip 0-22), isQueueEmpty is queue.size() == 0, and
        // SectionTaskDynamicQueue.size() is one List.size(). The same call
        // is already the near field's rebuild-progress signal
        // (MesheliumExtendedRd). This is the "vanilla has chunk work
        // pending" observation the far field defers to.
        boolean nearFieldBusy = mc != null && mc.levelRenderer != null
                && !mc.levelRenderer.hasRenderedAllSections();
        if (detectVanillaReload(mc)) {
            onVanillaReload();
        }
        Object level = mc != null ? mc.level : null;
        if (level != lastLevel.get()) {
            // A world change this class was not explicitly told about
            // (enable-mid-session paths where FarField never armed): the
            // per-world sets must not leak across, especially absentSet —
            // world A's missing chunks are not world B's.
            lastLevel = new WeakReference<>(level);
            resetTransientState();
        }
        boolean enabled = FarFieldConfig.enabled()
                && MesheliumConfig.terrainRenderingConfigured();
        long camera = SectionBuildTap.cameraSectionXZ();
        int nearEdge = mc != null && mc.options != null
                ? mc.options.getEffectiveRenderDistance() : 0;
        // The extraction slice, and it runs BEFORE drainResults, BEFORE
        // the armed test AND BEFORE the stand-down return, all on purpose.
        //
        // Before drainResults (R2, pre12 adversarial review; kept under
        // pre16 M4): the slice is opened here and nowhere else. A rescue
        // job filed by drainResults below is served by the NEXT pump's
        // scheduler pass, one frame later, against that frame's own
        // fresh budget - never against whatever a previous frame left.
        // The two locals moved up with it are a volatile read and an
        // options read, neither with a side effect.
        //
        // This does not by itself GUARANTEE the rescue a budget: the
        // FILL queue is shared. What bounds the loss to about one pump
        // is nearest-first order - a rescued column is WANTED at the
        // ring edge, so its job pops ahead of the safety-net walk's more
        // distant filings. If a playtest shows farExtractBudgetRefusals
        // climbing together with ledgerAwaitingQuadsPeak, the next lever
        // is a reserved share of the slice that only the rescue may
        // spend.
        //
        // Before the armed test: the pump's ack/result drains and the E6
        // capture triage are holding real bookkeeping (and, through the
        // pins, real memory) and have to keep running even when the far
        // RING is switched off (L1 at or inside the render distance), or
        // a player who collapses the ring mid-storm would strand a
        // storm's captures until the world changed. (The pin WALKS
        // themselves are the worker's since M5 and never depended on
        // this pump.)
        //
        // Before the stand-down return: a stand-down is exactly a
        // render-distance change, which is exactly when the E6 walk has
        // just captured the only copies of those chunks left in the
        // game, and their triage (sides, strips, the worker handoff)
        // runs at this pump. ExtractDispatch knows it is standing down
        // and paces itself: no sweep, no FILL, seams capture-only.
        //
        // The sweep radius mirrors ClientChunkCache.calculateStorageRange
        // - max(2, viewDistance) + 3 (javap ip 0-7) - which is the square
        // the client really holds chunks in, and 0 when we have no camera,
        // which makes the sweep a no-op without disturbing the two drains.
        boolean cameraKnown = camera != SectionBuildTap.CAMERA_SECTION_UNKNOWN;
        com.deds.meshelium.farfield.extract.ExtractDispatch.pumpGameThread(
                cameraKnown ? (int) (camera >> 32) : 0,
                cameraKnown ? (int) camera : 0,
                enabled && cameraKnown ? sweepRadiusFor(nearEdge) : 0,
                nearFieldBusy);
        // R2: and NOW the results, against the slice this frame opened.
        drainResults(enabled);
        if (standDown && !releaseStandDown(nearFieldBusy)) {
            // SEAM step 3, the coverage/admission split: a stand-down
            // gates ADMISSION ONLY. The coverage geometry stays exactly
            // as published (adjusted synchronously by republishCoverage
            // if the reload changed the render distance), because a
            // vanilla rebuild does not move the far domain - and the old
            // disarmed publish here is what made every release during a
            // stand-down take the plain-free path: the whole
            // "stand-down disarmed ring" family of failures, including
            // the owner's rd-change annulus. Deleted, not moved.
            // Vanilla is still rebuilding everything it owns. Draining the
            // results above was mandatory (it is what frees the shells and
            // meshes the IO thread already handed over), and the extraction
            // slice above is limited to the capture triage and the EDIT
            // drain while the stand-down holds. Everything else waits: no ring walk, no
            // store reads, no demote sweep, no admissions offered.
            // Already-admitted far sections keep drawing, which is what
            // stops the horizon blinking while the near field is dark.
            farStandDownPumps.increment();
            return;
        }
        int l1 = FarFieldConfig.l1RadiusChunks();
        // Nothing past the projection far plane can produce a fragment, so
        // promoting past it only burns arena bytes, region ids and IO.
        // Vanilla's plane, javap-verified on Camera.update (ip 7-46):
        //   depthFar = max(getEffectiveRenderDistance() * 16 * 4,
        //                  cloudRange().get() * 16)   [blocks]
        // which in chunks is max(nearEdge * 4, cloudRange). Because the
        // clamped value feeds the re-arm comparison below, moving the
        // cloud slider re-arms the walker on its own - the both-direction
        // rule, for free.
        if (mc != null && mc.options != null) {
            int farPlaneChunks = Math.max(nearEdge * 4, mc.options.cloudRange().get());
            l1 = Math.min(l1, Math.max(0, farPlaneChunks - 1));
        }
        boolean armed = enabled && level != null
                && camera != SectionBuildTap.CAMERA_SECTION_UNKNOWN
                && nearEdge > 0 && l1 > nearEdge;
        if (!armed) {
            // Master off / ring gone: both-direction rule — drain every
            // far resident back out, budgeted, until nothing remains.
            // Step 3: this IS one of the moments the far domain stops
            // existing, so the coverage geometry clears with it and
            // nothing may park for a ring that is not there.
            TerrainResidency.clearFarCoverage();
            scanPending = false;
            lastCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN; // re-arm on return
            if (!pendingAdmissions.isEmpty()) {
                dropPendingAdmissions();
            }
            releaseColumns(RELEASE_COLUMN_BUDGET, Long.MIN_VALUE, -1, -1);
            return;
        }
        if (camera != lastCamera || nearEdge != lastNearEdge || l1 != lastL1) {
            if (l1 != lastL1 && mc != null && mc.options != null) {
                // pre13/N1: the region-id budget is sized from the LOD
                // distance as well as from rd (MesheliumScaling.
                // farRegionDemand), so a LOD slider move needs the same
                // live grow a render-distance move needs. The request is
                // idempotent, consumed at the head of the next residency
                // pump, and a no-op when the budget already covers it.
                TerrainResidency.requestPinnedGrow(
                        mc.options.renderDistance().get());
            }
            if (nearEdge != lastNearEdge) {
                // The render-distance slider moved, so every memo of
                // "the near field owns this column" was formed against a
                // disc that no longer exists. Dropping the set costs at
                // most one re-read per column and is the only wholesale
                // discharge it has; the per-column one is the contested
                // watch (see nearOwnedColumns).
                nearOwnedColumns.clear();
            }
            lastCamera = camera;
            lastNearEdge = nearEdge;
            lastCoverEdge = coverRadius(nearEdge);
            lastL1 = l1;
            scanPending = true;
            scanRing = innerScanRing(lastCoverEdge);
            scanIndex = 0;
            // N5: a camera-driven arm resets the backstop's clock, so the
            // idle rescan only ever fires for a player who really has
            // stopped - a moving camera re-arms far more often than the
            // timer would and the backstop stays silent for the whole
            // travel leg.
            lastScanArmNanos = System.nanoTime();
            farColumnsAllSkippedSinceScan = 0;
            demotePending = true;
            // Bounded sprite staleness across resource reloads: the
            // resolver cache lives one arm cycle (SpriteUvResolver doc).
            SpriteUvResolver.clearCache();
        }
        // Step 3: publish the coverage geometry to the near side, every
        // armed pump. Three volatile writes; it is what lets
        // TerrainResidency.onMeshReleased decide, on a build thread, that
        // a section vanilla is dropping is far-domain, and park it
        // instead of freeing it. Placed after the re-arm block so the
        // edges are this pump's.
        TerrainResidency.publishFarCoverage(lastCamera, lastCoverEdge, lastL1);
        if (demotePending) {
            demotePending = releaseColumns(RELEASE_COLUMN_BUDGET, camera, nearEdge, l1);
        }
        noteAppearanceSettings();
        // N5. The fill guard is read ONCE per armed pump now, ahead of the
        // work test rather than inside it, because the idle re-arm below
        // needs the same answer and taking the residency's monitor twice
        // to get it would be worse than taking it in the one pump out of
        // many where nothing is outstanding. It is one uncontended monitor
        // enter either way (farPromotionHasRoom's own javadoc).
        boolean hasRoom = TerrainResidency.farPromotionHasRoom();
        noteRefusalsWhileGuardOpen(hasRoom);
        maybeRescanForOutstandingWork(hasRoom);
        if (!lateShells.isEmpty() || !unblockedColumns.isEmpty()
                || !refreshColumns.isEmpty() || !apronRefreshColumns.isEmpty()
                || !holedColumns.isEmpty() || scanPending) {
            if (!hasRoom) {
                farPromoteBudgetStalls.increment();
            }
            // M4, AND THE REASON THE H3 BRIDGE STOPPED HOLDING.
            //
            // The unblocked drain runs FIRST and runs whatever the fill
            // guard says, and both halves of that sentence are the fix.
            //
            // FIRST, because the old order was written before the bridge
            // existed. Back then a late shell and an unblocked column were
            // both just holes and the late shell was the fresher one. Now
            // an unblocked column is a position where the near field has
            // ALREADY let go and a bridge is ticking against
            // TerrainResidency's handover deadline: it is the only entry
            // here with a clock on it, and losing the whole eight-deep
            // in-flight budget to a burst of late shells - which a travel
            // leg produces continuously, one per column the extractor
            // stores - is how the bridge ran out and the flash came back.
            //
            // WHATEVER THE GUARD SAYS - but read the next paragraph
            // before reusing that sentence anywhere else, because the
            // first version of this change got it wrong.
            //
            // At the owner's settings (render distance 32, LOD 120) the
            // ring is far larger than the region budget, so
            // farPromotionHasRoom() is closed much of the time; gating the
            // recovery behind it meant bridges expired with no read ever
            // issued, which is the pre7 flash arriving three seconds late.
            // That much was right. The justification was not: it argued
            // that a recovery takes no new arena or regions because the
            // far copy lands in a slot the near field is holding open. TRUE
            // OF A BRIDGED SECTION, FALSE OF THE COLUMN. issueRequest reads
            // a whole column and every section of it that is not bridged
            // is ordinary net-new fill, so ungating this drain ungated the
            // far field's ONLY fill guard - and its {@code max -
            // nearReserve} arm is enforced nowhere else in the codebase.
            //
            // The guard was therefore moved to where the distinction is
            // actually visible: TerrainResidency.admitFarSectionLocked now
            // refuses a NON-BRIDGED admission with ADMIT_NO_BUDGET while
            // the guard is closed, and lets the swap through. This drain
            // stays unguarded as a PRODUCER, which is what the bridge
            // needs, and cannot be the thing that overspends, which is
            // what the near field needs. Narrowing the bypass here instead
            // was considered and rejected: a park is per SECTION (the
            // ledger byte) and unblockedColumns is per COLUMN, so any
            // test at this level is all-or-nothing and re-breaks either
            // the parked handover or the contested recovery.
            //
            // Late shells and the ring walk are genuine fill from end to
            // end, so they stay behind the guard here as well, and pay the
            // admission test too.
            drainUnblockedColumns();
            // SEAM step 2: refreshes run beside the unblocked drain and
            // share its posture - unguarded producer, per-section guard
            // at admission (see drainRefreshColumns). After it, because
            // a bridge has a clock on it and a refresh's predecessor
            // keeps drawing however long the read takes.
            drainRefreshColumns();
            // APRON STALENESS (pre19): the neighbour half of the same
            // fact, at lower priority and under a hard per-pump cap.
            // Unguarded for drainRefreshColumns' reason (every issue is
            // a replacement of a resident column, net-zero on the
            // arena), but AFTER it - a column whose own truth changed
            // outranks one whose seam did - and capped, so it can never
            // be the thing that takes the read pipeline away from the
            // guarded fill drains below.
            drainApronRefreshColumns();
            if (hasRoom) {
                drainLateShells();
                // N5: budget-refusal retries come AFTER late shells and
                // BEFORE the ring walk. After, because a late shell is
                // fresh information and this is a retry of something
                // already known; before, because the walk is O(ring) and
                // would otherwise consume the whole in-flight budget on
                // its frontier every pump and never reach the holes the
                // owner is actually looking at.
                drainHoledColumns();
                if (scanPending) {
                    // After a reload (L9) the ring walk IS the refill, and
                    // it starts from the innermost ring, so the horizon
                    // comes back near-first with no special case.
                    continuePromotionScan();
                }
            }
        }
    }

    /**
     * J4/L9: notice that a far-field appearance setting has moved and
     * start applying it to terrain that is already drawn.
     *
     * <p>Two watches, because the two classes of setting can honour two
     * different promises and pretending otherwise is what the tooltips
     * used to do:</p>
     * <ul>
     *   <li>a MESH-time row ({@code FarFieldConfig.farMeshSignature}) can
     *       be applied to everything resident, because the shell records
     *       on disk are unchanged and only the pass over them differs.
     *       The far field RELOADS: {@link #reloadFarField} drops it whole
     *       and the ordinary refill brings it back near-first;</li>
     *   <li>a SAVE-time row ({@code FarFieldConfig.farSaveSignature})
     *       cannot: the data is not in the record. The most that can
     *       honestly be done is to re-save the terrain the client is
     *       still HOLDING, which is what dropping the extract-once
     *       tracker sets in motion - paced by the extraction budget, with
     *       no walk on this thread at all. <b>Since pre20 that is only
     *       half of it:</b> the same number is published to
     *       {@code FarField}, which stamps it into every record it writes
     *       and treats a record carrying a different one as absent. The
     *       in-session half re-saves what the client holds; the on-disk
     *       half stops everything older being DRAWN, which is what makes
     *       a save-time fix reach a cache the player is not currently
     *       standing in. Before it, four {@code BAND_RULE_REVISION} bumps
     *       had re-extracted nothing across a restart.</li>
     * </ul>
     *
     * <p>{@code Control.LIGHTING} is the one row in BOTH, so moving it
     * does both things at once: the ring reloads immediately from the
     * records that exist now, and the client's held chunks are re-saved
     * behind it. Those two do not rendezvous, and
     * {@link #reloadFarField} says plainly what that costs.</p>
     *
     * <p>Both are no-ops on the first pump of a world (the sentinel) -
     * which is what a player changing settings before ever loading a
     * world lands in. With a world up but nothing resident, the mesh arm
     * releases nothing and costs one rewound ring scan whose every
     * candidate is already in flight, absent or resident. Both are also
     * unreachable during a stand-down: the pump returns above this call,
     * so a reload can never land in the middle of vanilla's own rebuild
     * storm and is simply noticed once it ends.</p>
     */
    private static void noteAppearanceSettings() {
        int mesh = FarFieldConfig.farMeshSignature();
        int save = FarFieldConfig.farSaveSignature();
        // pre20 (F): publish it to the store side, which stamps it into
        // every record it writes and rejects every record that carries a
        // different one. THIS is what makes a save-time change reach the
        // bytes on disk - before it, the save signature lived only in the
        // static below, was reset to the sentinel on every world era, and
        // so had never in four BAND_RULE_REVISION bumps caused a single
        // stored record to be re-extracted across a restart. One number,
        // produced here, used by both rules, so the in-session rule (drop
        // the extract-once tracker) and the on-disk rule (treat a
        // mismatched record as absent) cannot drift apart.
        FarField.noteSaveSignature(save);
        boolean meshMoved = lastMeshSignature != SIGNATURE_UNSET
                && lastMeshSignature != mesh;
        boolean saveMoved = lastSaveSignature != SIGNATURE_UNSET
                && lastSaveSignature != save;
        lastMeshSignature = mesh;
        lastSaveSignature = save;
        if (meshMoved) {
            reloadFarField();
        }
        if (saveMoved) {
            com.deds.meshelium.farfield.extract.ExtractDispatch
                    .forgetExtractedColumns();
        }
    }

    /**
     * L9: drop the far field wholesale and let the refill rebuild it.
     *
     * <h2>What the owner asked for, and why the pre9 answer was wrong</h2>
     * pre9 applied a mesh-time setting by queueing every resident column
     * and releasing plus re-requesting a few of them per pump. Each column
     * therefore blinked out and back on its own, scattered across the
     * whole ring for as long as the queue took to drain. The owner's
     * report, verbatim: "when you change lighting types all the chunks
     * flash clear briefly for a second while they are reloaded. and it
     * just seems like random jittering all over", and "i also think when
     * we change settings their, it all just needs to get unloaded and
     * re-rendered. and not do this weird replacing random chunks thing."
     *
     * <p>So the far field is dropped in ONE pump and comes back through
     * the machinery that already exists for filling it. Nothing here is a
     * new refill path: {@link #continuePromotionScan} walks square rings
     * outward from {@link #innerScanRing}, so what the player sees is the
     * horizon rebuilding from its near edge outwards, which is the same
     * thing they watch on every world join - including the bare sky in
     * front of the fog wall while it fills, which {@code FarFieldFogMixin}
     * gate 3 already leaves open on the owner's own 2026-08-19 decision
     * ("the fog belongs at the end of the LOD distance whenever the far
     * field is on, full stop"). The hold in between is real
     * and it is meant to be: for the few seconds the pipeline needs, the
     * distant terrain is simply absent, and the NEAR field is untouched
     * throughout - a full render distance of real vanilla terrain, every
     * frame, with no rebuild of any kind. A deliberate gap beyond it reads
     * as the mod doing what it was told; a scatter reads as a fault.</p>
     *
     * <h2>The drop is cheap, and that is a requirement</h2>
     * The one thing this may not cost is the stall pre8 removed. It does
     * not, because dropping is not the expensive direction:
     * {@code TerrainResidency.releaseAllFar()} takes the residency lock
     * ONCE and does per section exactly what an ordinary demote does (a
     * region-slot remove, an arena park onto the frame epoch, a snapshot
     * tombstone) with no vanilla call, no IO and no allocation. At the
     * 2,000-section ring the owner plays with that is about 32,000 int
     * moves in the region mirrors (a 32 B section record, moved once into
     * the hole and zeroed once at the tail), 2,000 arena parks and 2,000
     * snapshot-log appends, inside ONE monitor acquire instead of the
     * thousand a column-by-column drop takes. Microseconds, not
     * milliseconds, and once per settings change. The
     * REFILL is the expensive direction and it is entirely unchanged: the
     * same {@link #MAX_IN_FLIGHT} reads, the same
     * {@code farPromotionHasRoom} gate, the same 64 admissions per pump
     * inside the residency.
     *
     * <h2>What is dropped and what is kept</h2>
     * Dropped: every far section, every manifest that describes one
     * ({@link #residentColumns}, {@link #partialColumns},
     * {@link #refillColumns}) and every column already MESHED with the old
     * settings ({@link #pendingAdmissions} - admitting those would put the
     * horizon back in two styles, which is the thing pre9's whole-ring
     * queue was careful to avoid and this must be too).
     *
     * <p>Kept, because none of it is an opinion about how a shell is
     * DRAWN: {@link #absentSet} (what the store does or does not hold),
     * {@link #nearOwnedColumns} (who owns a position), {@link #lateShells}
     * and {@link #unblockedColumns} (real holes owed a read), and
     * {@link #inFlightSet} / {@link #queuedSet}, which are ownership
     * rather than opinion - a read already on its way is meshed AFTER this
     * call, on the IO thread, from the settings as they are then, so
     * clearing them would double-read every column in the pipeline. This
     * is the same distinction {@link #onVanillaReload} draws, arrived at
     * from the other side.</p>
     *
     * <p><b>The one stale window, named rather than hidden.</b> A column
     * whose mesh was finished by the IO thread but not yet drained sits in
     * {@code resultQueue} carrying the OLD settings, and it is admitted on
     * the next pump. It is bounded by the in-flight cap - at most eight
     * columns, all of them near the inner edge because that is where the
     * scan was - and nothing corrects it, so it stands until the player
     * travels. Draining it properly would mean stamping every result with
     * the signature it was meshed under, a format change in the mesh hop
     * for a defect measured in single columns; if it is ever actually
     * seen, the cheap honest answer is to clear the result queue here.</p>
     *
     * <p><b>Rapid changes are self-limiting.</b> Every mesh-time row is a
     * toggle or a cycle, never a drag, so the worst case is a player
     * clicking Lighting through its three steps: each click drops whatever
     * came back since the last one (a handful of columns) and restarts the
     * refill. The horizon is blank across the sequence either way, and
     * exactly one rebuild follows the last click.</p>
     *
     * <p><b>The save-signature interaction, and it is a real limitation.</b>
     * Real Light is in both signatures, so its flip reloads the ring AND
     * drops the extract-once tracker. Those two run at different speeds by
     * nature: the reload refills within seconds from the records on disk
     * NOW, while re-extraction repopulates the client's held window over
     * about a minute at {@code ExtractDispatch}'s adaptive slice. The ring
     * therefore comes back lit the way the OLD records allow (the mesher
     * falls back from Real Light to Glowing Blocks for a record carrying
     * no light plane) and does not pick the new plane up until those
     * columns leave and re-enter the ring. Left standing on purpose: the
     * two candidate fixes are a second reload once extraction settles, and
     * a per-column re-read as each record is rewritten - and the second is
     * exactly the scatter this change exists to remove. The Lighting
     * tooltip promises only what happens.</p>
     *
     * <p>Render thread, called from {@link #noteAppearanceSettings} inside
     * the pump's act-outside-the-lock half.</p>
     */
    private static void reloadFarField() {
        // Retire the meshed-but-unadmitted columns FIRST: retireColumn
        // moves whatever a partially admitted column already got into
        // residentColumns, so doing it first is what lets the release
        // below cover those sections too.
        if (!pendingAdmissions.isEmpty()) {
            dropPendingAdmissions();
        }
        int released = TerrainResidency.releaseAllFar();
        farReleases.add(released);
        farSectionsResident.add(-released);
        farReloads.increment();
        farReloadSections.add(released);
        residentColumns.clear();
        partialColumns.clear();
        refillColumns.clear();
        // SEAM step 2: a pending refresh is subsumed by the reload - the
        // refill below re-reads every column from the store, which holds
        // the fresh shell already. APRON STALENESS: and so is a pending
        // neighbour re-mesh, for the stronger version of the same
        // reason - the refill re-reads every column's whole 3x3.
        refreshColumns.clear();
        apronRefreshColumns.clear();
        // N5: every hole filed against the OLD appearance settings is
        // about to be re-offered by the refill below, so carrying the
        // list would spend the read pipeline twice on the same columns.
        holedColumns.clear();
        // Re-arm the ring walk from the innermost ring so the refill is
        // near-first. lastCoverEdge is valid here by construction: the
        // pump returns before this call unless it armed, and arming is
        // what computes it.
        scanPending = true;
        scanRing = innerScanRing(lastCoverEdge);
        scanIndex = 0;
        lastScanArmNanos = System.nanoTime();
        farColumnsAllSkippedSinceScan = 0;
    }

    // ------------------------------------------------------------------
    // The vanilla-reload stand-down (H1: get out of the way during the
    // storm)
    // ------------------------------------------------------------------

    /**
     * Has vanilla replaced its {@code ViewArea} since the last pump?
     *
     * <p>One reference compare against {@link #lastViewArea}. See that
     * field for the bytecode chain that makes a new ViewArea the exact
     * signature of a full geometry rebuild. The FIRST sighting arms the
     * watch without reporting a reload, because a walker that has just
     * started has nothing stale to throw away.</p>
     */
    /**
     * P3: the radius the catch-up sweep should walk this pump, which is
     * NOT simply the client's current storage range.
     *
     * <h2>The defect this fixes</h2>
     * <p>{@code ClientChunkCache.calculateStorageRange} is
     * {@code max(2, viewDistance) + 3} (javap ip 0-7), and the view
     * distance it reads is the OPTION. The option changes the instant the
     * player lets go of the slider; the chunks do not. The client goes on
     * holding the whole old window until the server has been told, has
     * untracked, and its {@code ClientboundForgetLevelChunkPacket}s have
     * arrived - at best a tick or two, and in that interval the sweep had
     * already collapsed to the NEW radius. So the one mechanism that
     * guarantees the extract-once tracker is complete stopped looking at
     * the abandoned annulus at precisely the moment that annulus was
     * about to be abandoned, and every unsaved column in it went to the
     * drop seam and the capture path instead. Dropping render distance 32
     * to 8 is 4,512 such columns.</p>
     *
     * <h2>The rule, and why it is self-limiting</h2>
     * <p>The radius only ever RISES freely; it comes down when the client
     * has demonstrably finished abandoning things, which is the stand-down
     * being over and {@code ExtractDispatch}'s pins and pending captures
     * both being empty. All are the same event seen from different sides -
     * a render-distance change sets the stand-down (a fresh
     * {@code ViewArea}, see {@link #detectVanillaReload}) and fills the
     * capture list - so the hold covers exactly the storm and ends with
     * it. No timer, so nothing to tune and nothing to expire at the wrong
     * moment.</p>
     *
     * <p>Cost of holding it too wide for a few seconds: the sweep probes
     * a bigger square, and a probe over a column the client no longer
     * holds is one hash miss plus one {@code ClientChunkCache} slot read.
     * At the busy probe rate that is 128 of them a pump whatever the
     * radius is - the probe count is fixed, only the pass LENGTH grows -
     * so the ceiling on the cost is a slower pass, never more game-thread
     * time per frame.</p>
     */
    private static int sweepRadiusFor(int nearEdge) {
        int storage = Math.max(2, nearEdge) + 3;
        if (storage >= heldSweepRadius) {
            heldSweepRadius = storage;
        } else if (!standDown
                && com.deds.meshelium.farfield.extract.ExtractDispatch
                        .pinnedCount() == 0
                && com.deds.meshelium.farfield.extract.ExtractDispatch
                        .pendingCaptureCount() == 0) {
            // M5: the hold covers the storm for as long as anything is
            // still pinned or awaiting its E6 triage - the same "cover
            // exactly the storm" rule the retained queue used to anchor.
            heldSweepRadius = storage;
        }
        return heldSweepRadius;
    }

    /**
     * SEAM step 3: the reload-storm republish, called from
     * {@code TerrainResidency.onVanillaGeometryInvalidated} at
     * {@code LevelRenderer.invalidateCompiledGeometry} HEAD - render
     * thread, synchronously BEFORE {@code releaseAllBuffers} iterates
     * (javap: release at ip 138, ViewArea swap at ip 149/184), so
     * program order alone guarantees the storm's frees are judged
     * against the render distance the reload is applying, not last
     * pump's. This class detects the same reload only on its NEXT pump
     * (ViewArea identity), which is exactly why the residency calls in
     * here instead of waiting.
     *
     * <p>Adjusts the INNER edge (coverRadius of the live rd) and the
     * camera; the outer edge is read back from the standing publication
     * rather than from {@code lastL1}, because a reload that lands
     * mid-stand-down has already had the arm state reset and
     * {@code lastL1} is -1 there. Never creates a publication (the
     * caller gates on one existing), so master-off stays master-off.
     * Render thread only, like every walker static.</p>
     */
    public static void republishCoverage(int nearEdge) {
        int l1 = TerrainResidency.publishedFarCoverageL1();
        if (l1 <= 0 || nearEdge <= 0) {
            return;
        }
        long camera = SectionBuildTap.cameraSectionXZ();
        if (camera == SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            camera = lastCamera;
        }
        if (camera == SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            return; // no camera anywhere: nothing sane to publish
        }
        TerrainResidency.publishFarCoverage(camera, coverRadius(nearEdge), l1);
    }

    private static boolean detectVanillaReload(Minecraft mc) {
        Object viewArea = mc != null && mc.levelRenderer != null
                ? mc.levelRenderer.viewArea() : null;
        Object previous = lastViewArea.get();
        if (viewArea == previous) {
            return false;
        }
        lastViewArea = new WeakReference<>(viewArea);
        // A null ViewArea is a level teardown, which onWorldLeave and the
        // level-identity watch already handle far more thoroughly.
        return previous != null && viewArea != null;
    }

    /**
     * Vanilla threw its terrain away: do the same to our OPINIONS, keep
     * our ownership, and stand aside until it has rebuilt.
     *
     * <p>The owner's instruction, close to verbatim: when Minecraft
     * reloads all its chunks the far field should reload itself rather
     * than patch the old state into the new shape. That is what this is,
     * with one deliberate exception.</p>
     *
     * <p><b>What is dropped</b> is every memo formed against the geometry
     * that just stopped existing: {@link #absentSet} (a verdict reached at
     * the old radius, and after a SHRINK the abandoned rings are exactly
     * the columns most likely to have been written off), {@link #nearOwnedColumns}
     * (formed against a compile disc that is gone), and the two pending
     * re-read sets, whose entries are re-derived by the fresh scan anyway.
     * The arm state is reset so the next armed pump recomputes
     * {@link #lastCoverEdge} and rewinds the ring cursor to the innermost
     * ring. All of that is set clears and four field writes - the whole
     * reason a reload is the cheap answer is that nothing here walks a
     * ring, touches the residency, or calls vanilla.</p>
     *
     * <p><b>What is NOT dropped, and why {@link #resetTransientState} is
     * the wrong call here.</b> That method clears {@link #residentColumns}
     * and bumps the generation, which is correct when the residency is
     * about to be disposed and every far section dies with the arena. A
     * render-distance change disposes nothing: the far sections are still
     * in the arena and still drawing. Clearing the map would strand every
     * one of them with no manifest and no way to release them - a leak
     * that lasts until the world changes - and blank the horizon at the
     * exact moment vanilla's own terrain is gone. {@link #inFlightSet},
     * {@link #queuedSet} and {@link #pendingAdmissions} are kept for the
     * same reason: they are ownership, not opinion, and dropping them
     * would double-read every column already on its way.</p>
     */
    private static void onVanillaReload() {
        // Step 3 note: the coverage geometry is deliberately NOT touched
        // here - a reload does not move the far domain, and the rd-change
        // case was already republished synchronously at the storm's head
        // (republishCoverage). Only opinions formed against the dead
        // geometry are dropped below.
        farVanillaReloads.increment();
        absentSet.clear();
        nearOwnedColumns.clear();
        lateShells.clear();
        unblockedColumns.clear();
        // refreshColumns is deliberately KEPT (seam step 2): unlike the
        // opinion sets above it records a FACT - the disk holds a
        // fresher shell than the resident mesh - and both sides of that
        // fact survive a vanilla rebuild (residentColumns is kept too,
        // same argument). Dropping it would leave the stale mesh drawn
        // for the session, since onShellWritten has already fired.
        // apronRefreshColumns is KEPT for the identical reason: the fact
        // it records is "a neighbour's record on disk is fresher than
        // the content this resident mesh was built from", and a vanilla
        // rebuild does not touch either side of it. It is also the set
        // with no second chance at all - onShellWritten fires once per
        // write and there is no scan that re-derives the seam.
        // N5: same rule as the two sets above. A vanilla rebuild frees
        // every section in the game, so a refusal recorded before it is
        // an opinion about a residency that no longer exists.
        holedColumns.clear();
        farColumnsAllSkippedSinceScan = 0;
        scanPending = false;
        demotePending = false;
        lastCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
        lastNearEdge = -1;
        lastCoverEdge = -1;
        lastL1 = -1;
        if (!standDown) {
            // A reload that lands while we are ALREADY standing down does
            // not restart the clock. MesheliumExtendedRd steps the render
            // distance on its own timer, and every step is a reload, so a
            // restarting deadline would let a ramp park the far field for
            // as long as it lasted. The settled counter still resets, so
            // the ordinary exit is unaffected; only the backstop is
            // measured from the first reload of the burst.
            standDownStartNanos = System.nanoTime();
        }
        standDown = true;
        standDownSettledPumps = 0;
        com.deds.meshelium.farfield.extract.ExtractDispatch.setStandDown(true);
    }

    /**
     * Should the stand-down end this pump?
     *
     * <p>Two ways out, and the ordinary one is vanilla's own compile queue
     * going quiet for {@value #STAND_DOWN_SETTLED_PUMPS} pumps in a row
     * once at least {@link #MIN_STAND_DOWN_NANOS} has passed. The backstop
     * is {@link #MAX_STAND_DOWN_NANOS}, because a queue that never empties
     * is a real state (see that constant) and "wait for quiet" without a
     * deadline is how a feature turns itself off for the session.</p>
     *
     * @return true when the far field may resume this pump
     */
    private static boolean releaseStandDown(boolean nearFieldBusy) {
        long elapsed = System.nanoTime() - standDownStartNanos;
        if (elapsed >= MAX_STAND_DOWN_NANOS) {
            endStandDown();
            return true;
        }
        if (elapsed < MIN_STAND_DOWN_NANOS) {
            standDownSettledPumps = 0;
            return false;
        }
        if (nearFieldBusy) {
            standDownSettledPumps = 0;
            return false;
        }
        if (++standDownSettledPumps < STAND_DOWN_SETTLED_PUMPS) {
            return false;
        }
        endStandDown();
        return true;
    }

    private static void endStandDown() {
        standDown = false;
        standDownSettledPumps = 0;
        com.deds.meshelium.farfield.extract.ExtractDispatch.setStandDown(false);
        // The arm state was cleared at the reload, so the pump that
        // follows this one re-arms from scratch: fresh coverEdge, cursor
        // rewound to innermost, demote sweep re-armed. Nothing else is
        // needed to refill the rings the render distance just vacated -
        // they are ordinary wanted columns again and the store already has
        // most of them.
    }

    /**
     * Issue reads for columns whose shell landed AFTER the scan had
     * already written them off as absent. Same in-flight cap as the ring
     * walk, so this can never widen the read pipeline; leftovers wait for
     * the next pump.
     */
    private static void drainLateShells() {
        for (var it = lateShells.iterator(); it.hasNext();) {
            if (inFlightSet.size() >= MAX_IN_FLIGHT) {
                return; // budget spent; the rest resume next pump
            }
            long key = it.nextLong();
            it.remove();
            if (!stillWanted(key)) {
                continue; // left the ring while its shell was being written
            }
            // The completeness test is the promotion scan's, refill mark
            // included: a column carrying a one-shot re-read mark is
            // resident but HOLED, and skipping it here would consume the
            // late shell that could have filled the hole.
            if ((residentColumns.containsKey(key) && !partialColumns.contains(key)
                    && !refillColumns.contains(key))
                    || inFlightSet.contains(key) || queuedSet.contains(key)) {
                continue;
            }
            issueRequest(key, false);
        }
    }

    /**
     * Issue re-reads for columns whose near-field blocker has gone
     * ({@link #onFarBlockerReleased}). Shares the ring walk's in-flight
     * cap, so the read pipeline never widens; leftovers stay in the set
     * and resume next pump.
     *
     * <p>The completeness test is {@link #drainLateShells}'s, and it is
     * the refill mark that makes it right here: a column owed a
     * recovery was marked by {@link #onFarBlockerReleased} in the same
     * breath as this entry, so "resident, not partial, not marked" can
     * only mean the hole was filled after the event was posted - the
     * admission drain runs in the same pump and can admit the freed
     * position before this drain ever looks at it. Skipping that is the
     * difference between one recovery and one recovery plus a pointless
     * disk read.</p>
     *
     * <p>What keeps this from becoming a re-read treadmill is the
     * source: an entry exists only because a WATCHED position was
     * released, each watch fires once, and the residency re-arms rather
     * than fires when the slot was taken straight back.</p>
     */
    private static void drainUnblockedColumns() {
        for (var it = unblockedColumns.iterator(); it.hasNext();) {
            if (inFlightSet.size() >= MAX_IN_FLIGHT) {
                return; // budget spent; the rest resume next pump
            }
            long key = it.nextLong();
            it.remove();
            if (!stillWanted(key)) {
                continue; // the ring moved on while the blocker held it
            }
            if (inFlightSet.contains(key) || queuedSet.contains(key)) {
                continue; // a read is already on its way; it will carry this
            }
            // No absent test. It used to skip here for the same reason
            // onFarBlockerReleased used to return, and it was wrong for
            // the same reason: nothing re-opens the door once the
            // extract-once tracker has spent the column's single
            // onShellWritten. issueRequest clears the memo, and a genuine
            // MISS re-files it one round trip later, so the cost of
            // being wrong in this direction is one store read per watch
            // discharge.
            if (residentColumns.containsKey(key) && !partialColumns.contains(key)
                    && !refillColumns.contains(key)) {
                continue; // the hole was filled between the event and here
            }
            issueRequest(key, false);
        }
    }

    /**
     * SEAM step 2: issue replace-in-place re-reads for drawn columns
     * whose stored shell went fresher ({@link #refreshColumns}, fed by
     * {@link #onShellWritten}). Runs beside the unblocked drain,
     * UNGUARDED by the fill guard, because a refresh is not net-new
     * fill: every section of it that is still resident swaps in place -
     * the exchange frees as much as it binds, the bridged-swap B2
     * argument verbatim - and the sections that are NOT resident pay
     * the guard where the distinction is actually visible, per section,
     * inside {@code admitFarSectionLocked}. Shares the ring walk's
     * in-flight cap, so the read pipeline never widens.
     *
     * <p>A column whose pipeline is BUSY (an older read in flight, a
     * meshed column awaiting admission) KEEPS its entry for the next
     * pump instead of dropping it: the older read carries the older
     * shell and comes back as skips, so issuing the refresh behind it
     * is the only way the fresh geometry ever lands. Both busy states
     * clear on their own within a pump or two, so the hold cannot
     * spin.</p>
     */
    private static void drainRefreshColumns() {
        for (var it = refreshColumns.iterator(); it.hasNext();) {
            if (inFlightSet.size() >= MAX_IN_FLIGHT) {
                return; // budget spent; the rest resume next pump
            }
            long key = it.nextLong();
            if (!stillWanted(key)) {
                it.remove(); // left the ring; releaseColumns owns it now
                continue;
            }
            if (inFlightSet.contains(key) || queuedSet.contains(key)) {
                continue; // busy with an older read; keep the entry
            }
            it.remove();
            if (!residentColumns.containsKey(key)) {
                // Released between the write and this pump (superseded
                // empty, ring reload): what is owed now is ordinary
                // net-new fill, and it belongs behind the fill guard
                // with the rest of the fill.
                if (lateShells.size() < LATE_SHELL_CAP) {
                    lateShells.add(key);
                }
                continue;
            }
            issueRequest(key, true);
        }
    }

    /**
     * APRON STALENESS (pre19): issue replace-in-place re-reads for drawn
     * columns whose own record is current but whose MESH was built from
     * a neighbour's older content ({@link #apronRefreshColumns}, fed by
     * {@link #fileApronNeighbors}).
     *
     * <p>Identical machinery to {@link #drainRefreshColumns} - the same
     * {@code issueRequest(key, true)}, so the same replace-in-place
     * admission and the same continuity guarantee - and three deliberate
     * differences:</p>
     * <ul>
     *   <li>it runs AFTER the direct refreshes, because a column whose
     *       own truth changed outranks a column whose seam did;</li>
     *   <li>it is bounded twice - it never leaves fewer than
     *       {@value #APRON_REFRESH_RESERVED_SLOTS} of the
     *       {@value #MAX_IN_FLIGHT} read slots for the fill drains that
     *       run after it, and it issues at most
     *       {@value #APRON_REFRESH_ISSUES_PER_PUMP} reads a pump. That
     *       pair is the fanout's entire cost bound. Without it an 8-way
     *       fanout over a travel leg's write stream would hold the whole
     *       pipeline ahead of the guarded fill drains and starve the
     *       horizon - trading a one-cell seam for missing columns, which
     *       is a bad trade in any playtest;</li>
     *   <li>a column released between the file and the drain is DROPPED,
     *       not re-filed as fill. A direct refresh re-files because the
     *       column's own record went fresher and something must read it;
     *       nothing is owed here, because the column's record never
     *       changed and the ring walk will read it back with the fresh
     *       apron already on disk.</li>
     * </ul>
     *
     * <p>UNGUARDED by the fill guard, for {@link #drainRefreshColumns}'
     * reason exactly: every section of a resident column swaps in place,
     * the exchange frees as much as it binds, and the sections that are
     * NOT resident pay the guard per section inside
     * {@code admitFarSectionLocked}.</p>
     */
    private static void drainApronRefreshColumns() {
        int issued = 0;
        for (var it = apronRefreshColumns.iterator(); it.hasNext();) {
            if (inFlightSet.size() >= MAX_IN_FLIGHT - APRON_REFRESH_RESERVED_SLOTS
                    || issued >= APRON_REFRESH_ISSUES_PER_PUMP) {
                // Two independent bounds, both structural: never take
                // the fill's reserved half of the pipeline, and never
                // issue more than two in one pump. The rest resume next
                // pump - or are subsumed by a ring-walk read of the same
                // column, which carries the same fresh 3x3.
                return;
            }
            long key = it.nextLong();
            if (!stillWanted(key)) {
                it.remove(); // left the ring; releaseColumns owns it now
                continue;
            }
            if (refreshColumns.contains(key)) {
                // A direct refresh was filed after this entry: that read
                // brings the whole fresh 3x3 with it, so this one is
                // subsumed rather than deferred.
                it.remove();
                continue;
            }
            if (inFlightSet.contains(key) || queuedSet.contains(key)) {
                continue; // busy with an older read; keep the entry
            }
            it.remove();
            if (!residentColumns.containsKey(key)) {
                continue; // released; nothing is owed (see the javadoc)
            }
            issueRequest(key, true);
            issued++;
            farApronRefreshIssued.increment();
        }
    }

    /**
     * pre13 audit: notice budget refusals that arrived while the fill
     * guard was OPEN, and stand the retry paths down when they do.
     *
     * <p>The whole termination argument for {@link #drainHoledColumns}
     * and for the backstop below is "retry only when the retry can
     * succeed", read off {@code farPromotionHasRoom()}. That method
     * measures the region-id count and the arena against the DEVICE
     * ceiling; {@link #ADMIT_NO_BUDGET} has two other producers it cannot
     * see (see {@link #farHoleRetryBackoffs}), and against those the
     * retry loop is a treadmill with no exit. One refusal taken while the
     * guard was open is enough evidence to wait
     * {@value #IDLE_RESCAN_NANOS} nanoseconds before asking again: if the
     * obstruction clears - the arena grows, the near field frees a region
     * - the very next window retries at full rate, and if it does not,
     * the cost of the whole mechanism falls to one counter read a pump.
     * Two seconds is the same clock the backstop uses, and it is chosen
     * the same way: far below the several-second scale at which a player
     * notices a hole, far above the pump.</p>
     *
     * <p>The guard is read from the PREVIOUS pump on purpose. A refusal
     * is recorded under the residency lock at admission time, which is
     * after this pump handed its columns over, so pairing this pump's
     * refusals with this pump's guard reading would blame the wrong
     * window every time the guard closed between the two.</p>
     *
     * <p><b>Any forward progress vetoes the stand-down.</b> A window that
     * both admitted and refused is the gate sitting on its own boundary,
     * which is the ordinary end of a fill and not a treadmill - parking
     * the retries for it would turn a partial fill into a two-second
     * trickle. Only a window that refused and admitted NOTHING is
     * evidence that the obstruction is one the guard cannot see.</p>
     */
    private static void noteRefusalsWhileGuardOpen(boolean hasRoom) {
        long refusals = farAdmitNoBudget.sum();
        long refusedSince = refusals - lastNoBudgetSeen;
        lastNoBudgetSeen = refusals;
        long admits = farAdmissions.sum();
        long admittedSince = admits - lastAdmissionsSeen;
        lastAdmissionsSeen = admits;
        boolean wasOpen = lastPumpGuardOpen;
        lastPumpGuardOpen = hasRoom;
        if (wasOpen && refusedSince > 0L && admittedSince == 0L) {
            holeRetryBlockedUntilNanos = System.nanoTime() + IDLE_RESCAN_NANOS;
            farHoleRetryBackoffs.increment();
        }
    }

    /** True while {@link #noteRefusalsWhileGuardOpen} has the retries parked. */
    private static boolean holeRetriesBlocked() {
        return System.nanoTime() - holeRetryBlockedUntilNanos < 0L;
    }

    /**
     * N5, the backstop: re-walk the ring when a pass has finished, work
     * is still outstanding and the camera has not moved.
     *
     * <h2>Why a timer here, when every mark now has a drain</h2>
     * <p>Because the enumerated marks are only as complete as the
     * enumeration, and this class has now been wrong about that three
     * times - the pre6 contested watch, the pre9 late shell and pre13's
     * two budget-refusal paths were each "the last one". The mechanism
     * the owner asked for is not "these particular holes heal", it is
     * "the ring heals while I stand still", and only a pass over the ring
     * can promise that.</p>
     *
     * <h2>Its four gates, and what each one is worth</h2>
     * <ul>
     *   <li><b>the previous pass finished</b> - a pass in progress is
     *       already doing this;</li>
     *   <li><b>{@code hasRoom}</b> - re-walking a ring the residency
     *       cannot admit into produces nothing but IO. This is also what
     *       keeps the backstop off entirely at settings where the region
     *       budget is the binding arm, which is exactly where a treadmill
     *       would hurt most;</li>
     *   <li><b>no parked retries</b> ({@link #holeRetriesBlocked}, added
     *       by the pre13 audit) - the gate above is one arm short of the
     *       refusals that actually exist, and a pass taken into a refusal
     *       the guard did not predict re-reads every partial column for
     *       nothing;</li>
     *   <li><b>outstanding work</b> - {@code absentSet} does NOT count.
     *       A column written off because the store has nothing is
     *       re-opened by {@link #onShellWritten}, not by looking again;
     *       counting it would hold the timer armed for the whole of every
     *       session in unvisited terrain. What counts is a column we know
     *       is drawable and know is not drawn.</li>
     * </ul>
     *
     * <p>Cost when it does fire: one ring pass, {@value #SCAN_STEPS_PER_PUMP}
     * hash probes a pump, about 15 pumps at L1 120 - under a fifth of a
     * second at 100 fps, once every {@value #IDLE_RESCAN_NANOS}
     * nanoseconds, and the steady state on a settled ring is that every
     * probe is a skip.</p>
     */
    private static void maybeRescanForOutstandingWork(boolean hasRoom) {
        if (scanPending || !hasRoom || lastCoverEdge < 0) {
            return;
        }
        if (holeRetriesBlocked()) {
            // The refusals are coming from something the guard cannot
            // see, and a ring pass re-reads every partial column into the
            // same refusal. Deliberately does NOT touch lastScanArmNanos:
            // the moment the block lifts, the pass is due immediately.
            return;
        }
        if (partialColumns.isEmpty() && refillColumns.isEmpty()
                && holedColumns.isEmpty() && farColumnsAllSkippedSinceScan == 0) {
            lastScanArmNanos = System.nanoTime(); // nothing owed: keep the timer fresh
            return;
        }
        long now = System.nanoTime();
        if (now - lastScanArmNanos < IDLE_RESCAN_NANOS) {
            return;
        }
        lastScanArmNanos = now;
        farColumnsAllSkippedSinceScan = 0;
        // Step 4: drop the near-owned memos with the rescan. The watch
        // that used to discharge them per-position is gone, and the one
        // staleness shape that survives (see nearOwnedColumns) is exactly
        // a column this pass should re-read once. Cost: one redundant
        // CONTESTED round per still-owned column per idle rescan, and
        // the rescan itself only fires with work outstanding.
        nearOwnedColumns.clear();
        scanPending = true;
        scanRing = innerScanRing(lastCoverEdge);
        scanIndex = 0;
        farIdleRescans.increment();
    }

    /**
     * N5: re-issue reads for columns the residency refused for want of
     * region or arena budget ({@link #holedColumns}).
     *
     * <p>Called ONLY with the fill guard open, and that is the whole
     * termination argument: an entry is removed when it is read, and can
     * only come back if the fresh offer is refused again - which requires
     * the guard to have closed between this drain and the admission a few
     * pumps later. So the steady state with a closed guard is zero reads,
     * and the steady state with an open one is at most
     * {@value #HOLED_DRAIN_PER_PUMP} columns a pump until the set is
     * empty.</p>
     *
     * <p>The per-pump cap is deliberately tighter than
     * {@value #MAX_IN_FLIGHT}. It runs after the deadline work (a
     * handover bridge has a clock on it, a late shell is fresh
     * information) and BEFORE the ring walk, which is the one ordering
     * choice here that costs something: while holes are outstanding they
     * can take every in-flight slot and the walk's frontier gets none.
     * That is the right trade only because the set drains
     * unconditionally - an entry is removed when it is read, whatever the
     * outcome - so the starvation is bounded by
     * {@value #HOLED_COLUMN_CAP} / {@value #HOLED_DRAIN_PER_PUMP} pumps
     * and not by whether the retries succeed. A walk that ran first would
     * spend the same slots on its frontier every pump and never reach the
     * holes the owner is actually looking at.</p>
     *
     * <p><b>And the "only when the retry can succeed" gate is one arm
     * short</b>, which is what {@link #farHoleRetryBackoffs} and
     * {@link #noteRefusalsWhileGuardOpen} exist for: read them before
     * trusting the termination argument above.</p>
     */
    private static void drainHoledColumns() {
        if (holedColumns.isEmpty() || holeRetriesBlocked()) {
            return;
        }
        int issued = 0;
        for (var it = holedColumns.iterator(); it.hasNext();) {
            if (inFlightSet.size() >= MAX_IN_FLIGHT || issued >= HOLED_DRAIN_PER_PUMP) {
                return; // budget spent; the rest resume next pump
            }
            long key = it.nextLong();
            it.remove();
            if (!stillWanted(key)) {
                continue; // the ring moved on; the demote sweep owns it now
            }
            if (inFlightSet.contains(key) || queuedSet.contains(key)) {
                continue; // a read is already on its way; it will carry this
            }
            if (residentColumns.containsKey(key) && !partialColumns.contains(key)
                    && !refillColumns.contains(key)) {
                continue; // filled by something else between the file and here
            }
            issued++;
            farHolesDrained.increment();
            issueRequest(key, false);
        }
    }

    // ------------------------------------------------------------------
    // Promote: the near-first square-ring scan with a persistent cursor
    // ------------------------------------------------------------------

    private static void continuePromotionScan() {
        int camX = (int) (lastCamera >> 32);
        int camZ = (int) lastCamera;
        // Steps examined this pump, budgeted independently of the
        // in-flight cap. In the STEADY state every candidate is already
        // resident, in flight or known-absent, so none of them spend
        // in-flight budget and the scan would otherwise walk the entire
        // O(L1^2) annulus in one pump - about 33k hash probes at L1 96,
        // inside the residency pump, every time the camera crosses a
        // section boundary and re-arms the scan. That is precisely the
        // kind of periodic hitch this mod exists to remove. The cursor
        // (scanRing/scanIndex) already survives across pumps, so a
        // budget here just spreads the same work over a few frames.
        int steps = SCAN_STEPS_PER_PUMP;
        while (scanRing <= lastL1) {
            int perimeter = 8 * scanRing; // scanRing >= innerScanRing >= 1
            while (scanIndex < perimeter) {
                if (inFlightSet.size() >= MAX_IN_FLIGHT || steps <= 0) {
                    return; // budget spent; the cursor resumes next pump
                }
                steps--;
                long key = ringChunk(camX, camZ, scanRing, scanIndex);
                scanIndex++;
                // The rings from innerScanRing up to lastCoverEdge are
                // only PARTLY ours: their axis-adjacent columns are
                // inside the covered disc and their diagonal columns are
                // not (the corner lobes, class javadoc B3). The radius is
                // the SHORTENED one (section H1) — vanilla's compile disc
                // less the handover band — so the rim vanilla has claimed
                // but not built is ours to cover. Two integer multiplies,
                // ahead of every hash probe.
                int dx = colX(key) - camX;
                int dz = colZ(key) - camZ;
                if (nearCovered(dx, dz, lastCoverEdge)) {
                    continue;
                }
                // The OUTER edge, and it belongs HERE and not only in
                // stillWanted (H2 shape audit). The walk enumerates
                // CHEBYSHEV rings, which is the right way to visit a disc
                // exactly once, but a ring of radius r reaches Euclidean
                // r*sqrt(2) at its corners — so without this test the
                // outermost rings hand the pipeline columns that a store
                // read, a decode, a palette resolve and a mesh pass later
                // would be dropped by pollAdmission's stillWanted anyway,
                // 27 percent of the annulus doing a full round trip to be
                // thrown away at the last step. Two multiplies, ahead of
                // every hash probe, exactly like the near-cover test.
                if (!withinRing(dx, dz, lastL1)) {
                    continue;
                }
                if ((residentColumns.containsKey(key) && !partialColumns.contains(key)
                        && !refillColumns.contains(key))
                        || inFlightSet.contains(key)
                        || queuedSet.contains(key) || absentSet.contains(key)
                        // The near field owns every section of this
                        // column and the residency is watching all of
                        // them; a re-read can only be told so again.
                        // Discharged by onFarBlockerReleased.
                        || nearOwnedColumns.contains(key)) {
                    continue;
                }
                issueRequest(key, false);
            }
            scanRing++;
            scanIndex = 0;
        }
        scanPending = false; // full annulus covered
    }

    /** Chunk at index {@code i} of the Chebyshev ring of radius {@code r}. */
    private static long ringChunk(int camX, int camZ, int r, int i) {
        int top = 2 * r + 1;
        int side = 2 * r - 1;
        int dx;
        int dz;
        if (i < top) {
            dx = -r + i;
            dz = -r;
        } else if (i < top + side) {
            dx = r;
            dz = -r + 1 + (i - top);
        } else if (i < top + side + top) {
            dx = r - (i - top - side);
            dz = r;
        } else {
            dx = -r;
            dz = r - 1 - (i - top - side - top);
        }
        return colKey(camX + dx, camZ + dz);
    }

    private static void issueRequest(long key, boolean refresh) {
        // One-shot refill: the re-read discharges the mark whatever the
        // admission pass reports. An all-skipped retry (the column lost
        // nothing after all) must not leave the column re-readable, or
        // the scan spends its whole in-flight budget on the wake of
        // already-resident columns instead of the real holes.
        refillColumns.remove(key);
        // A retry after a lost-wakeup miss finds the column already
        // written off; this request supersedes that verdict, and a real
        // MISS re-files it if the store genuinely has nothing.
        absentSet.remove(key);
        // Same rule for the near-owned memo: whatever routed back here -
        // a discharged watch, a late shell, a refill mark - is newer
        // information than the memo, and the admission pass re-files it
        // if the near field still owns the whole column.
        nearOwnedColumns.remove(key);
        inFlightSet.add(key);
        farShellRequests.increment();
        final int gen = generation;
        // S1: the read brings the column's 3x3 back with it, so the mesher
        // can answer a face on a chunk plane from the record on the other
        // side of it (AO, fluid corners and flow, per-face light, the
        // fluid overlay). One IO task, one queue slot, one result - the
        // apron rides the read that was already being made rather than
        // adding a hop or a second in-flight budget.
        Consumer<FarField.Neighborhood> consumer = hood -> postResult(hood == null
                ? new IoResult(gen, key, RESULT_MISS, null, null, refresh)
                : new IoResult(gen, key, RESULT_DECODED, hood.center(),
                        hood.ring(), null, refresh));
        FarField.requestShellNeighborhood(colX(key), colZ(key), consumer);
    }

    // ------------------------------------------------------------------
    // Results (IO thread posts; the pump drains on the render thread)
    // ------------------------------------------------------------------

    private static void postResult(IoResult result) {
        synchronized (resultQueue) {
            if (resultQueue.size() < RESULT_QUEUE_CAP) {
                resultQueue.addLast(result);
                return;
            }
        }
        // Unreachable while the in-flight cap holds (<= 8 live results +
        // stale-generation leftovers against a cap of 64); counted, not
        // thrown — an IO-thread throw would hit FarField's task catch.
        farMeshErrors.increment();
    }

    private static void drainResults(boolean enabled) {
        for (;;) {
            IoResult res;
            synchronized (resultQueue) {
                res = resultQueue.pollFirst();
            }
            if (res == null) {
                return;
            }
            if (res.gen != generation) {
                continue; // a dead world's leftovers; every set was reset
            }
            switch (res.kind) {
                case RESULT_DECODED -> {
                    if (!enabled || broken) {
                        inFlightSet.remove(res.chunkKey);
                        break;
                    }
                    // Hop 2: palette -> primitives on the GAME thread,
                    // then the pure mesh pass back on the IO thread.
                    // The SHELL, not shell.palette: since store format 4 a
                    // palette entry is a block STATE and the resolver needs
                    // the record's state indices to find it. A record older
                    // than format 4 carries none and resolves exactly as it
                    // did before (ShellCodec's version 4 section).
                    SpriteUvResolver.ResolvedPalette palette =
                            SpriteUvResolver.resolve(res.shell);
                    // S1: the neighbours' palettes resolve HERE, on the
                    // game thread, for the same reason the centre's does -
                    // SpriteUvResolver.resolve reads the registry, the
                    // model manager and the atlas. Everything it returns
                    // is by-value primitives, so the mesh hop below stays
                    // legal on the IO thread (FARFIELD-CODEBASE-SEAM 6.4).
                    // Per-state caching makes a neighbour's palette a
                    // handful of map hits: the 3x3 shares nearly every
                    // block state.
                    final ShellMesher.NeighborColumn[] neighbors =
                            res.ring == null ? null
                                    : new ShellMesher.NeighborColumn[9];
                    if (neighbors != null) {
                        for (int d = 0; d < 9; d++) {
                            ShellCodec.Shell nb = res.ring[d];
                            if (nb == null) {
                                continue;
                            }
                            neighbors[d] = new ShellMesher.NeighborColumn(
                                    nb, SpriteUvResolver.resolve(nb));
                        }
                    }
                    final ShellCodec.Shell shell = res.shell;
                    final int gen = res.gen;
                    final long key = res.chunkKey;
                    final boolean refresh = res.refresh;
                    boolean queued = FarField.submitCompute(() -> {
                        try {
                            ShellMesher.Result meshed =
                                    ShellMesher.mesh(shell, palette, neighbors);
                            postResult(new IoResult(gen, key, RESULT_MESHED, null, meshed,
                                    refresh));
                        } catch (Throwable t) {
                            farMeshErrors.increment();
                            latchBroken("far shell meshing", t);
                            postResult(new IoResult(gen, key, RESULT_ERROR, null, null,
                                    refresh));
                        }
                    });
                    if (!queued) {
                        inFlightSet.remove(res.chunkKey); // IO parked; behaves as a miss
                    }
                }
                case RESULT_MESHED -> {
                    inFlightSet.remove(res.chunkKey);
                    ShellMesher.Result meshed = res.meshed;
                    farMeshedSections.add(meshed.sections().length);
                    farMeshSkippedCells.add(meshed.skippedCells());
                    farWaterOpaqueQuads.add(meshed.fluidQuads());
                    if (!enabled || broken) {
                        break;
                    }
                    if (meshed.sections().length == 0) {
                        // Nothing drawable there. For a REFRESH this means
                        // the fresh truth of a drawn column meshes to
                        // nothing (fully mined out); the resident mesh
                        // keeps drawing until the column demotes - the
                        // continuity invariant outranks a rare stale
                        // section, and the memo is accurate either way:
                        // the store really holds nothing drawable.
                        absentSet.add(res.chunkKey);
                        break;
                    }
                    pendingAdmissions.addLast(new MeshedColumn(
                            res.gen, res.chunkKey, meshed.sections(), res.refresh));
                    pendingAdmissionSections += meshed.sections().length;
                    queuedSet.add(res.chunkKey); // reserved against the scan
                }
                case RESULT_MISS -> {
                    inFlightSet.remove(res.chunkKey);
                    // The store has nothing - but the client very often
                    // still HOLDS this chunk. Vanilla stops drawing at
                    // the edge of its compile disc while the client keeps
                    // chunks out to renderDistance+3, and the receive
                    // seam cannot store that frontier column because its
                    // outward neighbour has not arrived. So between the
                    // two, nobody draws it: the owner's "blank chunks
                    // between my loaded area and where im at". File its
                    // extraction if it is really there (pre16 M4: the
                    // rescue is an event producer; the scheduler runs the
                    // job nearest-first within a pump) and re-request
                    // instead of writing the column off.
                    boolean rescued = com.deds.meshelium.farfield.extract
                            .ExtractDispatch.tryExtractLoaded(
                                    colX(res.chunkKey), colZ(res.chunkKey));
                    if (!rescued) {
                        absentSet.add(res.chunkKey);
                    } else {
                        // A shell for this column exists or is coming, so
                        // the column must NOT be written off - a pending
                        // job recorded as absent is a permanent hole,
                        // which is the one way deferring this rescue
                        // could make things worse than walking inline.
                        //
                        // F1 (pre12 adversarial review): re-offered
                        // through the RECOVERY set, not the late-shell
                        // set.
                        //
                        // A column reaches here because a read MISSED, and
                        // a read that missed is very often the second hop
                        // of a handover: the near field let the position
                        // go and PARKED it, the walker spent its read and
                        // the store had nothing yet. lateShells is drained
                        // BEHIND the fill guard, so re-filing there parked
                        // the retry behind exactly the gate the recovery
                        // was freed from on its first hop. The park has no
                        // deadline any more (the geometry keeps drawing
                        // through a MISS), but unblockedColumns is still
                        // the right queue: it is drained first and
                        // unguarded, and a parked position holds arena
                        // bytes that resolve sooner the sooner the swap
                        // lands.
                        //
                        // It still terminates on its own for the same
                        // reason: the retry recurs only while the answer
                        // stays BUDGET, and the pump's own drains
                        // (retained, deferred, sweep) are extracting the
                        // same column in the background, after which the
                        // read HITS. Note that onShellWritten cannot carry
                        // this one — inFlightSet was cleared at the top of
                        // this case and absentSet was never set, so its
                        // "genuinely never written off" arm returns before
                        // it can file anything. This site is the only path
                        // back.
                        //
                        // Capped, because this is the one producer of
                        // unblockedColumns that is not bounded by the
                        // watch or the bridge population. Past the cap it
                        // degrades to the old behaviour, and past BOTH
                        // caps the column is simply left un-memoized: it
                        // is not in absentSet, so the ring walk finds it
                        // again on a later pass.
                        if (unblockedColumns.size() < LATE_SHELL_CAP) {
                            unblockedColumns.add(res.chunkKey);
                        } else if (lateShells.size() < LATE_SHELL_CAP) {
                            lateShells.add(res.chunkKey);
                        }
                    }
                }
                case RESULT_ERROR -> inFlightSet.remove(res.chunkKey);
                default -> inFlightSet.remove(res.chunkKey); // unreachable
            }
        }
    }

    // ------------------------------------------------------------------
    // Admission handshake (render thread, called by TerrainResidency
    // INSIDE its lock window beside drainPendingUploadsLocked)
    // ------------------------------------------------------------------

    /**
     * Cheap gate for the residency's per-pump far drain.
     *
     * <p>False while the far field is standing down through a vanilla
     * rebuild, which is the third leg of the stand-down: the walk and the
     * reads stop in {@link #pumpInner}, extraction stops in
     * {@code ExtractDispatch}, and this stops far admissions taking the
     * residency's lock and its staging ring while vanilla is trying to
     * upload an entire render distance of real sections. Nothing is lost -
     * {@link #pendingAdmissions} keeps its columns and drains on the first
     * pump after the storm.</p>
     */
    public static boolean admissionsPending() {
        return !standDown && pendingAdmissionSections > 0;
    }

    /**
     * The next section to admit, or null. Stale columns (world era or
     * ring membership changed while queued) are retired here; a column
     * partially admitted before going stale keeps its admitted sections
     * tracked so the demote sweep releases them normally.
     */
    public static Admission pollAdmission() {
        for (;;) {
            MeshedColumn col = pendingAdmissions.peekFirst();
            if (col == null) {
                return null;
            }
            if (col.gen != generation) {
                pendingAdmissionSections -= col.sections.length - col.next;
                pendingAdmissions.pollFirst(); // old era: bookkeeping already reset
                // Unreachable today (a generation bump goes through
                // resetTransientState, which clears both structures), but
                // this arm must not be the one path that strands a scan
                // reservation if that ever stops being true.
                queuedSet.remove(col.chunkKey);
                continue;
            }
            if (col.next >= col.sections.length || !stillWanted(col.chunkKey)) {
                retireColumn(col);
                continue;
            }
            ShellMesher.SectionMesh s = col.sections[col.next];
            return new Admission(colX(col.chunkKey), s.sy(), colZ(col.chunkKey), s.mesh(),
                    col.refresh);
        }
    }

    /**
     * Outcome for the section {@link #pollAdmission} just returned.
     * DEFER leaves the cursor in place (same section retries next pump);
     * everything else advances it.
     */
    public static void onAdmissionOutcome(int outcome) {
        MeshedColumn col = pendingAdmissions.peekFirst();
        if (col == null || col.next >= col.sections.length) {
            return; // defensive; poll/outcome always pair up
        }
        if (outcome == ADMIT_DEFER) {
            farAdmitDeferred.increment();
            return;
        }
        if (outcome == ADMIT_OK) {
            farAdmissions.increment();
            farSectionsResident.increment();
            col.admittedSy.add(col.sections[col.next].sy());
        } else if (outcome == ADMIT_REPLACED) {
            // Seam step 2: the refresh swap. An admission - fresh geometry
            // is on screen - but NOT a new resident: the exchange freed
            // our own predecessor in the same lock hold, so the gauge
            // stays flat. The sy still joins the column's manifest so a
            // replace-only column retires through the ordinary resident
            // arm; retireColumn's per-section merge is what keeps the
            // already-listed entry from doubling.
            farAdmissions.increment();
            col.admittedSy.add(col.sections[col.next].sy());
        } else if (outcome == ADMIT_SKIPPED) {
            farAdmitSkipped.increment();
        } else if (outcome == ADMIT_CONTESTED) {
            // A real hole, and the ONLY thing to do about it here is
            // nothing. Since seam step 4 the recovery needs no watch: a
            // free of the position, if it is still covered then, PARKS
            // and files the demand itself. Marking the column incomplete
            // would hand the job to the ring walk instead, which cannot
            // do it - the walk has no way to know the owner is still
            // there, so it would re-read the column on every camera
            // crossing and be refused every time. The recovery arrives
            // as onFarBlockerReleased, exactly as before.
            farContestedSections.increment();
            col.contested++;
        } else if (outcome == ADMIT_NO_BUDGET) {
            farAdmitNoBudget.increment();
            farPromoteBudgetStalls.increment();
            // A hole, not a terminal state: record it so the scan is
            // allowed to come back for this column once budget frees.
            // Without this the column registers as resident on the
            // strength of any ONE admitted section and the missing ones
            // are never re-requested, so a stationary player keeps a
            // gap in the horizon until they travel far enough for the
            // hysteresis band to drop the column.
            col.incomplete = true;
        }
        col.next++;
        pendingAdmissionSections--;
        if (col.next >= col.sections.length) {
            retireColumn(col);
        }
    }

    /** Move a finished/stale column out of the queue, keeping its manifest. */
    private static void retireColumn(MeshedColumn col) {
        // Census only (pre6): a column that was offered in FULL and
        // admitted NOTHING leaves here entered in no set at all, so
        // nothing will ask for it again. Read before col.next is forced
        // to the end, because that is what separates "every section was
        // refused" from "retired early because it went stale".
        boolean offeredInFull = col.next >= col.sections.length;
        queuedSet.remove(col.chunkKey);
        pendingAdmissionSections -= col.sections.length - col.next;
        col.next = col.sections.length;
        pendingAdmissions.remove(col);
        if (col.admittedSy.isEmpty()) {
            if (offeredInFull && col.sections.length > 0) {
                farColumnsAllSkipped.increment();
                farColumnsAllSkippedSinceScan++;
                // N5: the "entered in no set at all" case, and it is only
                // benign when the refusals were SKIPPED (something else
                // already owns every position, so nothing is owed) or
                // CONTESTED (the watch below owns the recovery). A
                // NO_BUDGET refusal is a real hole with no owner, and
                // this is the ONE place it can be filed.
                if (col.incomplete) {
                    fileHole(col.chunkKey);
                }
                if (col.contested == col.sections.length) {
                    // Every section of the column is held by a near-field
                    // owner with a watch armed on it (section H1). Memoize
                    // it so the ring walk stops re-reading a column that
                    // is on screen; the watch is what brings it back.
                    // Sized defensively - see NEAR_OWNED_CAP.
                    if (nearOwnedColumns.size() >= NEAR_OWNED_CAP) {
                        nearOwnedColumns.clear();
                    }
                    nearOwnedColumns.add(col.chunkKey);
                }
            }
            return;
        }
        IntArrayList existing = residentColumns.get(col.chunkKey);
        if (existing == null) {
            residentColumns.put(col.chunkKey, col.admittedSy);
        } else {
            // SEAM step 2: the manifest is keyed per SECTION - replace,
            // never append. A refresh column's ADMIT_REPLACED sections
            // are already listed here from the admission that made the
            // column resident; the old blind addAll would have entered
            // them twice, and the demote sweep would then have released
            // each twice - the duplicate-manifest hazard that was the
            // whole justification for releaseStaleResident's deleted
            // release-first hop. Net-new sections (a refresh filling an
            // old budget hole, an ordinary refill) still append. O(n*m)
            // with both sides bounded by the column's section count.
            for (int i = 0; i < col.admittedSy.size(); i++) {
                int sy = col.admittedSy.getInt(i);
                if (!existing.contains(sy)) {
                    existing.add(sy);
                }
            }
        }
        if (col.incomplete) {
            partialColumns.add(col.chunkKey);
            // N5: and the pump-drained twin, because partialColumns is
            // consumed by the ring walk alone. A column that admitted
            // SOME of its sections and lost the rest to the region budget
            // is a visible hole in the horizon and, before pre13, waited
            // for the camera to cross a section boundary.
            fileHole(col.chunkKey);
        } else {
            partialColumns.remove(col.chunkKey);
        }
    }

    /**
     * N5: file a column for a budget-refusal retry. Deliberately silent
     * about WHY the residency had no room - the drain re-asks that
     * question at the moment it matters.
     *
     * <p>Overflow re-arms the ring walk wholesale rather than carrying
     * thousands of keys, the {@link #onShellWritten} precedent: past
     * {@value #HOLED_COLUMN_CAP} holes the pass is cheaper than the
     * list, and a pass covers every one of them by construction.</p>
     */
    private static void fileHole(long key) {
        // Filter at the SOURCE, not only at the drain. Both producers are
        // hot on a travel leg - vanilla supersedes a far section at the
        // inner edge on every chunk crossing - and a column vanilla has
        // taken back is not a hole, it is the handover working. The drain
        // asks the same question again because the ring can move between
        // the two; asking it here is what keeps the set small enough that
        // the cap is never the thing that answers.
        if (!stillWanted(key)) {
            return;
        }
        if (holedColumns.size() >= HOLED_COLUMN_CAP) {
            holedColumns.clear();
            if (lastCoverEdge >= 0) {
                scanPending = true;
                scanRing = innerScanRing(lastCoverEdge);
                scanIndex = 0;
            }
            return;
        }
        if (holedColumns.add(key)) {
            farHolesFiled.increment();
        }
    }

    private static void dropPendingAdmissions() {
        MeshedColumn col;
        while ((col = pendingAdmissions.peekFirst()) != null) {
            retireColumn(col);
        }
    }

    // ------------------------------------------------------------------
    // Demote (render thread)
    // ------------------------------------------------------------------

    /**
     * Release columns outside the wanted ring (or ALL columns when
     * {@code camera == Long.MIN_VALUE}), at most {@code budget} columns.
     *
     * @return true when the sweep must resume next pump (budget spent)
     */
    private static boolean releaseColumns(int budget, long camera, int nearEdge, int l1) {
        if (residentColumns.isEmpty()) {
            return false;
        }
        boolean releaseAll = camera == Long.MIN_VALUE;
        int camX = (int) (camera >> 32);
        int camZ = (int) camera;
        LongArrayList toRelease = new LongArrayList();
        for (var it = residentColumns.keySet().iterator(); it.hasNext();) {
            long key = it.nextLong();
            if (!releaseAll) {
                int dx = colX(key) - camX;
                int dz = colZ(key) - camZ;
                // OUTER edge only, keeping the L1+1 dead band. The inner
                // edge is deliberately NOT a distance test any more.
                //
                // EUCLIDEAN since the H2 shape audit, matching
                // {@link #stillWanted} exactly. It had to move with the
                // promote side or the two would disagree about the corners:
                // a Chebyshev demote at L1+2 against a Euclidean want at L1
                // would hold every diagonal column from Euclidean L1 out to
                // Chebyshev L1+1 — 27 percent of the ring — resident,
                // unwanted, fogged out, and unreleasable. Same +2 hysteresis
                // as before, so the dead band a camera can idle in is the
                // same width it always was.
                //
                // It used to drop the far copy at dist <= nearEdge-1, one
                // chunk INSIDE vanilla's grid, without ever checking that
                // vanilla had the real section yet. That gave vanilla 16
                // blocks of camera travel to receive, build and upload a
                // chunk before its far stand-in was thrown away, and any
                // time it lost that race the column became a hole. With
                // the fog wall now pushed out to the LOD distance, that
                // hole is plainly visible instead of being buried in fog:
                // it is the owner's "flashing for a second while the lod
                // is popping in".
                //
                // A far copy that ends up inside the near field is
                // released by the ARRIVAL of the real thing instead: the
                // slot steal in TerrainResidency.drainPendingUploadsLocked
                // and the empty-compile signal in onSectionCompiledEmpty.
                // That is exactly the handover discipline the near field
                // already uses for its own rebuilds, and covering vanilla's
                // load-in is the one job a far copy is uniquely able to do.
                // Not a leak: bounded by the near square, single-owner by
                // construction (the region slot has exactly one owner, and
                // admission refuses an occupied one), and the memory wall
                // still has forceEvictFarLocked behind it.
                if (withinRing(dx, dz, l1 + 1)) {
                    continue;
                }
            }
            toRelease.add(key);
            if (toRelease.size() >= budget) {
                break;
            }
        }
        for (int i = 0; i < toRelease.size(); i++) {
            long key = toRelease.getLong(i);
            IntArrayList sys = residentColumns.remove(key);
            partialColumns.remove(key); // the column is leaving the ring entirely
            refillColumns.remove(key);
            unblockedColumns.remove(key); // ... so an owed re-read is moot too
            refreshColumns.remove(key); // ... including a pending refresh (E1)
            apronRefreshColumns.remove(key); // ... and a pending seam re-mesh
            nearOwnedColumns.remove(key); // ... and so is a near-owner memo
            if (sys == null || sys.isEmpty()) {
                continue;
            }
            int released = TerrainResidency.releaseFarColumn(
                    colX(key), colZ(key), sys.elements(), sys.size());
            farReleases.add(released);
            farSectionsResident.add(-released);
        }
        return toRelease.size() >= budget;
    }

    // ------------------------------------------------------------------
    // Residency-side bookkeeping events (render thread, under the
    // residency LOCK — cheap map updates only, no vanilla, no IO)
    // ------------------------------------------------------------------

    /**
     * A far section left residency through a residency-side path
     * (vanilla supersede, empty recompile, force-evict). Deferred by the
     * residency to its pump drain so even build-thread frees reach this
     * render-thread map safely.
     */
    public static void onSectionEvicted(int sx, int sy, int sz) {
        farReleases.increment();
        farSectionsResident.add(-1);
        long key = colKey(sx, sz);
        IntArrayList sys = residentColumns.get(key);
        if (sys == null) {
            return;
        }
        sys.rem(sy);
        if (sys.isEmpty()) {
            residentColumns.remove(key);
            partialColumns.remove(key);
            refillColumns.remove(key);
        } else {
            // The column is now INCOMPLETE but still listed, so the
            // promotion scan's completeness test reads it as whole and
            // would skip it until it crosses L1+2 (pre2 removed the
            // inner-edge demote, so nothing else re-reads it). Mark it:
            // the scan re-reads it ONCE the moment it is outside
            // vanilla's disc again. Costs nothing until then - the scan
            // continues on nearCovered before it reaches the
            // completeness test - and the sections still resident come
            // back as cheap skips.
            refillColumns.add(key);
            // N5: and the pump-drained twin. The refill mark alone waits
            // for the ring walk, which waits for the camera to move -
            // and NONE of this method's three producers (vanilla
            // supersede, empty recompile, force-evict) needs the camera
            // to have moved to fire. That is a hole opening under a
            // stationary player with nothing scheduled to close it.
            fileHole(key);
        }
    }

    /**
     * The near-field owner at {@code (sx,sy,sz)} let go and the position
     * PARKED (seam step 4): a successor is owed there. Called by the
     * residency's pump drain (render thread, under its LOCK - map
     * updates only, the {@link #onSectionEvicted} contract), once per
     * filed demand. The slot is deliberately NOT re-checked for
     * occupancy any more: the parked copy IS the occupant, held drawing
     * precisely so the read issued here can swap it out; the one skip
     * (a queued vanilla successor) happens in the drain.
     *
     * <p>This is the pre6 fix's one new wakeup, and it is deliberately
     * an EVENT rather than a retry: the walker never asks "is it free
     * yet", it is told, so a contested position costs zero work for
     * however long the near field keeps it and exactly one re-read when
     * that ends.</p>
     *
     * <p>Marks {@link #unblockedColumns} (so the read happens on the
     * next pump even with the camera parked) and, for a column that is
     * already drawing something, the one-shot refill (so the ring walk
     * stops reading it as complete if the drain is budget-deferred).
     * Both are per COLUMN, because a read is: the sections that are
     * still resident come back as cheap {@link #ADMIT_SKIPPED}s and only
     * the freed position actually admits.</p>
     */
    public static void onFarBlockerReleased(int sx, int sy, int sz) {
        if (broken) {
            return;
        }
        farBlockersReleased.increment();
        long key = colKey(sx, sz);
        // Discharge the memo FIRST and unconditionally. It was formed on
        // the claim "the near field owns every section of this column",
        // and that claim has just stopped being true; leaving it standing
        // on a column the ring later re-enters would be the one way this
        // memo could turn a transient refusal into a permanent hole.
        nearOwnedColumns.remove(key);
        if (!stillWanted(key)) {
            return; // vanilla's business now, or past L1
        }
        // The absent memo used to end the recovery here, on the argument
        // that onShellWritten owns the door back in. It does not: the
        // extract-once tracker means onShellWritten fires AT MOST ONCE per
        // column per session, so a column written off while a near-field
        // owner was standing on it - which is the ordinary state of every
        // column in the corner lobes, where vanilla holds a live section
        // it no longer lists and the client no longer holds the chunk -
        // had already spent its one wakeup and could never be read again.
        // The memo is dropped instead, and issueRequest drops it too, so
        // the recovery costs exactly one store read; a genuine MISS
        // re-files the column immediately. Bounded by the source: an entry
        // exists only because a WATCHED position was released, and each
        // watch fires once.
        absentSet.remove(key);
        IntArrayList sys = residentColumns.get(key);
        if (sys != null && sys.contains(sy)) {
            // We are ALREADY drawing that position: a read for this
            // column overtook the watch (it was in flight when the
            // blocker let go and admitted the section on the way past).
            // Nothing is owed, and re-reading would spend IO to be told
            // ADMIT_SKIPPED. This is the one false wakeup the event can
            // produce, and the manifest answers it exactly.
            return;
        }
        if (sys != null) {
            refillColumns.add(key); // resident but holed; the scan may look again
        }
        unblockedColumns.add(key);
    }

    /**
     * The residency store was disposed with {@code droppedSections} far
     * sections still resident (world change; the arena died wholesale).
     * Render thread.
     */
    public static void onResidencyDisposed(int droppedSections) {
        try {
            if (droppedSections > 0) {
                farReleases.add(droppedSections);
                farSectionsResident.add(-droppedSections);
            }
            resetTransientState();
        } catch (Throwable t) {
            latchBroken("far dispose bookkeeping", t);
        }
    }

    // ------------------------------------------------------------------
    // World lifecycle + store events (game thread, wired via FarField)
    // ------------------------------------------------------------------

    /** A world became current ({@code FarField.onWorldJoin}). */
    static void onWorldJoin() {
        try {
            resetTransientState();
        } catch (Throwable t) {
            latchBroken("far world-join", t);
        }
    }

    /**
     * The current world is going away ({@code FarField.onWorldLeave}):
     * release every far resident through the residency's release path
     * NOW (unbudgeted — dispose is imminent and frees are fence-parked
     * either way), then reset the per-world state.
     */
    static void onWorldLeave() {
        try {
            while (releaseColumns(Integer.MAX_VALUE, Long.MIN_VALUE, -1, -1)) {
                // single pass releases everything; loop is belt and braces
            }
            resetTransientState();
        } catch (Throwable t) {
            latchBroken("far world-leave", t);
        }
    }

    /**
     * The extractor just stored a fresh shell for this column
     * ({@code FarField.submitShell}, game thread): it is no longer
     * absent, so the walker may promote it on its next arm.
     *
     * <p><b>SEAM step 2: a fresher shell for a column the far field is
     * ALREADY DRAWING is a REPLACEMENT, never a release.</b> The column
     * is filed on {@link #refreshColumns} for a re-read whose admission
     * swaps the resident sections in place
     * ({@code admitFarSectionLocked}'s refresh arm) - the old mesh draws
     * until the new one binds. The predecessor here,
     * {@code releaseStaleResident}, released the column FIRST and
     * re-filed it as a hole, which un-drew an edited column for the
     * whole store round trip: the audit's standing invariant violation
     * ("Save pipeline tears out drawn far columns"). Its one legitimate
     * concern - {@code retireColumn} appending the refreshed section
     * list onto the old manifest - is now answered where it lives, by
     * that method's per-section merge. A resident column that is NOT
     * still wanted files nothing (the old early-out, kept): it is
     * outside the ring, so {@link #releaseColumns} owns it.</p>
     *
     * <p><b>APRON STALENESS (pre19): the eight NEIGHBOURS are filed too,
     * and BEFORE the early-out.</b> Since pre18 the mesh of a column is
     * a function of its neighbours' stored contents, so this write
     * invalidates not one mesh but up to nine
     * ({@link #apronRefreshColumns}). The fanout deliberately runs ahead
     * of the resident test above, because the written column's own
     * disposition says nothing about its neighbours': the sharpest case
     * is a column just INSIDE the near-covered disc, which is not wanted
     * and files nothing for itself, sitting against a resident column
     * one chunk further out - exactly the inner-edge seam an edit near
     * the handover band lands on.</p>
     */
    static void onShellWritten(int chunkX, int chunkZ) {
        long key = colKey(chunkX, chunkZ);
        fileApronNeighbors(chunkX, chunkZ);
        if (residentColumns.containsKey(key) && stillWanted(key)) {
            absentSet.remove(key); // coherence; a resident column is never absent
            refreshColumns.add(key);
            // APRON STALENESS: a seam re-mesh owed to this column is
            // SUBSUMED by its own refresh - issueRequest reads the whole
            // 3x3, so one read discharges both. Dropped here rather than
            // in the drain so the two sets cannot both spend the
            // pipeline on the same column a pump apart.
            apronRefreshColumns.remove(key);
            return;
        }
        boolean wasAbsent = absentSet.remove(key);
        if (!wasAbsent) {
            // NOT simply "never written off". A read issued BEFORE this
            // write is still out and, being FIFO behind it on the one IO
            // thread, will answer MISS - and that miss is drained AFTER
            // this call returns, which re-files the column as absent.
            // Returning here would be a lost wakeup with no second
            // chance: the extract-once tracker means onShellWritten never
            // fires for this column again, so it stays written off for
            // the whole session with a valid shell sitting on disk. That
            // is a permanent hole, and it is one of the confirmed causes
            // of the owner's "lots of holes".
            if (!inFlightSet.contains(key)) {
                return; // genuinely never written off; the scan will reach it
            }
            farInFlightRescues.increment();
        } else {
            farLateShells.increment();
        }
        if (lateShells.size() >= LATE_SHELL_CAP) {
            // A ring-sized burst (render-distance shrink, teleport): one
            // wholesale re-arm beats carrying thousands of keys.
            lateShells.clear();
            if (lastCoverEdge >= 0) {
                scanPending = true;
                scanRing = innerScanRing(lastCoverEdge);
                scanIndex = 0;
            }
            return;
        }
        lateShells.add(key);
    }

    /**
     * APRON STALENESS (pre19): a record at {@code (chunkX, chunkZ)} just
     * changed on disk, so every RESIDENT column of its 3x3 that
     * apron-read its edge cells now draws a mesh built from the old
     * content. File those for a replace-in-place re-mesh
     * ({@link #apronRefreshColumns}).
     *
     * <p><b>Which rewrites this is for, and which were already
     * covered.</b> S4 (pre19) mirrors vanilla's own light-update set, so
     * a light change at a chunk plane dirties BOTH columns, both are
     * rewritten and both were already refreshed by the arm above - but
     * only if the two writes land before either refresh READ goes out,
     * and they routinely do not: two EDIT jobs against a millisecond
     * budget commonly ripen in different pumps, and the earlier column's
     * refresh then reads the later one's stale record. So even the
     * self-covering family needed this. The families with no coverage at
     * all are the ones S4 named as its own residuals plus three more:
     * a boundary edit that changes no light value (a dark Nether or End
     * plane); an E2 quality UPGRADE, where a degraded record is
     * re-extracted whole and its edge cells APPEAR where the
     * over-included boundary plane used to be; the sweep's DEGRADED
     * REPAIR, same shape with no arrival to fan out from; the PIN path
     * ({@code onPinShellAcked}), whose write has no neighbour event of
     * any kind; and a band-floor move, which shifts the record's
     * {@code minY} and with it the apron's UNKNOWN floor. The S1 tint
     * field is NOT on that list and deliberately so: {@code cellTint}
     * reads the CENTRE shell only, and S1's cell field is a pure
     * function of world position, so a tint-only rewrite cannot make a
     * neighbour wrong.
     *
     * <p><b>Cost, at the file site.</b> Eight iterations of two hash
     * probes and a {@link #stillWanted} test - no allocation, no clock,
     * no IO, and the set dedupes overlapping neighbourhoods, which is
     * what keeps a wholesale re-save (a save-signature move dropping the
     * extract-once tracker) at one refresh per resident column rather
     * than nine. Inert when the apron is off: with
     * {@code -Dmeshelium.farfield.apron=false} the mesher meshes one
     * record at a time exactly as pre18 did and there is no cross-record
     * fact to go stale, so the kill switch turns off the invalidation
     * with the feature.</p>
     */
    private static void fileApronNeighbors(int chunkX, int chunkZ) {
        if (!FarField.apronEnabled()) {
            return;
        }
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue; // the centre is the direct-refresh arm's
                }
                long nk = colKey(chunkX + dx, chunkZ + dz);
                // RESIDENT ONLY. A column that is not drawing has no
                // stale mesh to replace, and manufacturing fill here
                // would put speculative reads in front of the ring
                // walk's real ones - the refresh path applies to
                // resident columns and nothing else.
                if (!residentColumns.containsKey(nk) || !stillWanted(nk)) {
                    continue;
                }
                // Already owed a re-read of its own: that read carries
                // the fresh apron with it (issueRequest reads the whole
                // 3x3, and the write above dropped this column's decode
                // from the IO thread's LRU), so a second entry would
                // only spend the pipeline twice.
                if (refreshColumns.contains(nk)) {
                    continue;
                }
                if (apronRefreshColumns.add(nk)) {
                    farApronRefreshFiled.increment();
                }
            }
        }
    }

    /** New world era: clear everything transient, force a re-arm. */
    private static void resetTransientState() {
        generation++;
        residentColumns.clear();
        inFlightSet.clear();
        queuedSet.clear();
        partialColumns.clear();
        refillColumns.clear();
        lateShells.clear();
        unblockedColumns.clear();
        refreshColumns.clear();
        apronRefreshColumns.clear();
        holedColumns.clear();
        farColumnsAllSkippedSinceScan = 0;
        // A real reading, never a zero: nanoTime's origin is arbitrary
        // and may be negative, so a zero stamp is not a valid "long ago".
        lastScanArmNanos = System.nanoTime();
        nearOwnedColumns.clear();
        absentSet.clear();
        // The signatures re-baseline on the first armed pump rather than
        // firing a reload into a world that has nothing resident yet.
        lastMeshSignature = SIGNATURE_UNSET;
        lastSaveSignature = SIGNATURE_UNSET;
        pendingAdmissions.clear();
        pendingAdmissionSections = 0;
        // The IO thread may still be posting results for the old era;
        // nothing drains them once the world changed, so they would pin
        // their decoded shells and encoded meshes for the rest of the
        // session. Generation-stamped results are discarded anyway, so
        // dropping them here loses nothing.
        synchronized (resultQueue) {
            resultQueue.clear();
        }
        scanPending = false;
        demotePending = false;
        lastCamera = SectionBuildTap.CAMERA_SECTION_UNKNOWN;
        lastNearEdge = -1;
        lastCoverEdge = -1;
        lastL1 = -1;
        // P3: the held sweep radius describes the window of a world we
        // are leaving. Carrying it would make the next world's first
        // passes walk a square that has no chunks in it.
        heldSweepRadius = 0;
        // E3: the near side must not park for coverage belonging to the
        // world we just left. Republished on the first armed pump of the
        // next one.
        TerrainResidency.clearFarCoverage();
        SpriteUvResolver.clearCache();
    }

    // ------------------------------------------------------------------
    // The latch
    // ------------------------------------------------------------------

    /** True when a far-field failure parked the feature this session. */
    public static boolean isBroken() {
        return broken;
    }

    /**
     * Park the far field for the session (logged once). Safe from any
     * thread. Deliberately touches NOTHING in the drawer or the
     * residency — the far field going dark leaves already-admitted
     * sections drawing until the world changes, and the near field
     * never notices.
     */
    public static void latchBroken(String where, Throwable cause) {
        broken = true;
        // Nothing will pump this class again, so nothing would ever lift a
        // stand-down that happened to be in force. Release it: the store
        // side of the far field keeps working through the chunk-lifecycle
        // seams even when the walker is parked, and leaving extraction
        // switched off would silently stop the cache filling.
        try {
            com.deds.meshelium.farfield.extract.ExtractDispatch.setStandDown(false);
        } catch (Throwable ignored) {
            // A latch path may not throw; the flag is best effort here.
        }
        try {
            // And retract the coverage. Nothing will pump this class
            // again, so nothing will ever fill a park the near side opens
            // on our behalf; clearing the geometry stops NEW parks at the
            // source, and the E1 sweep releases the already-parked ones
            // within a few pumps (its outer-edge test answers false
            // everywhere without a publication) - the right failure
            // direction for a latch: plain frees, never held geometry.
            TerrainResidency.clearFarCoverage();
        } catch (Throwable ignored) {
            // Same rule: best effort, never throw out of the latch.
        }
        // Nothing will ever drain the results again (pump() returns on
        // the latch), so release whatever the IO thread already handed
        // over instead of pinning those shells and encoded meshes for
        // the rest of the JVM session. The monitor is never nested, so
        // this is safe from the IO thread as well as the render thread.
        synchronized (resultQueue) {
            resultQueue.clear();
        }
        if (brokenLogged.compareAndSet(false, true)) {
            MesheliumLog.LOGGER.error(
                    "Meshelium far field failed ({}); the far field is parked for this "
                            + "session — near-field rendering is unaffected", where, cause);
        }
    }

    // ------------------------------------------------------------------
    // The missing-column census (pre6). READ-ONLY, and RENDER THREAD
    // ONLY.
    //
    // Everything below answers questions; nothing below changes an
    // answer. It exists because three of the owner's standing defects
    // (whole chunks missing, a holed ring, a blank moment at the
    // handover) are all the same question asked at different radii -
    // "why is THIS column not drawn?" - and until now the only way to
    // answer it was to infer it from aggregate counters. Three rounds
    // of that inference were wrong. The walker knows the answer per
    // column; these methods make it say so.
    //
    // THREADING, and it is not a nicety: every set and map read here is
    // one of the render-thread-confined structures listed in the class
    // Threading section. There is no lock on them and none is added,
    // because adding one WOULD be a behaviour change to the pump. A
    // caller on another thread can observe a fastutil table mid-rehash
    // and get a wrong answer or an exception. From the gametest harness
    // that means the entire census has to run inside runOnClient /
    // computeOnClient, never on the test thread.
    // ------------------------------------------------------------------

    /** Census: far sections are drawn here and the walker reads the column as whole. */
    public static final int COL_RESIDENT = 0;
    /**
     * Census: drawn, but at least one section was refused for BUDGET, so
     * the column is hollow ({@link #partialColumns}). Recoverable - the
     * scan does not skip these, and the hole refills when ids or arena
     * free up.
     */
    public static final int COL_RESIDENT_PARTIAL = 1;
    /**
     * Census: drawn, but it LOST a section to a residency-side path
     * (vanilla supersede, empty recompile, force-evict) and is carrying
     * a one-shot re-read mark ({@link #refillColumns}). This is the
     * hollowed-chunk state; the mark is consumed by the next
     * {@link #issueRequest}.
     */
    public static final int COL_RESIDENT_REFILL = 2;
    /** Census: a store read is outstanding for it right now ({@link #inFlightSet}). */
    public static final int COL_IN_FLIGHT = 3;
    /**
     * Census: meshed and sitting in {@link #pendingAdmissions} waiting
     * for the residency's admission drain ({@link #queuedSet}). A large
     * count here with a small {@link #farAdmissions} delta is admission
     * throughput, not a read problem.
     */
    public static final int COL_QUEUED = 4;
    /**
     * Census: its shell was written after the scan had passed, and a
     * re-read is owed on the next pump ({@link #lateShells}). Transient
     * by construction; a persistent population means
     * {@link #drainLateShells} is being starved by {@link #MAX_IN_FLIGHT}.
     */
    public static final int COL_LATE_PENDING = 5;
    /**
     * Census: the store answered MISS, the {@code tryExtractLoaded}
     * rescue answered false (not held, or stored-empty), and the column is
     * written off until something calls {@link #onShellWritten} for it
     * ({@link #absentSet}). This is the WRITE side failing, not the read
     * side: the terrain was never cached.
     */
    public static final int COL_ABSENT = 6;
    /**
     * Census: inside the COVERED disc — vanilla's compile disc less the
     * handover band (class javadoc H1) — so it is not ours to draw.
     * Counted, not ignored, because the boundary between this bucket and
     * the rest is exactly where the near/far handover flash lives.
     *
     * <p>Read the H1 change into any comparison with an older census
     * run. This bucket USED to include the un-built rim of vanilla's disc,
     * which is where the owner's ring lives, and a census that reported
     * "1112 of 1112 wanted columns drawn" was reporting on a wanted set
     * that excluded the defect by definition. The band's columns now
     * appear in the wanted population, so the missing count is expected
     * to RISE on the first run after this change even if the fix works;
     * the number to compare is the on-screen ring, and the counters to
     * watch beside it are {@link #farContestedSections} (should be large,
     * meaning vanilla owns most of the band) against
     * {@link #COL_NEAR_OWNED} and {@link #COL_DROPPED}.</p>
     */
    public static final int COL_NEAR_COVERED = 7;
    /** Census: past L1 (after the projection-far-plane clamp); not wanted. */
    public static final int COL_OUTSIDE_RING = 8;
    /**
     * Census: wanted, and the ring cursor has not reached it in the
     * current pass. Pure timing. If this dominates a settled scene the
     * scan is losing its race with the camera and
     * {@link #SCAN_STEPS_PER_PUMP} is the number to look at; if it
     * dominates only right after a teleport, that is the scan working
     * as designed.
     */
    public static final int COL_NEVER_SCANNED = 9;
    /**
     * Census: wanted, the cursor has ALREADY walked past it this pass,
     * and it is in none of the walker's sets - not resident, not in
     * flight, not queued, not late, not absent. Nothing will look at
     * this column again until the next camera CHUNK crossing re-arms
     * the scan, and for a stationary player that is never.
     *
     * <p>The three known routes in, all of them shaped exactly like the
     * owner's "whole chunks entirely missing":</p>
     * <ol>
     *   <li>every section of the column was refused, so
     *       {@link #retireColumn} dropped it with an empty manifest and
     *       released its {@link #queuedSet} reservation. Read the two
     *       refusals apart before concluding anything:
     *       {@link #ADMIT_SKIPPED} means our own far copies are already
     *       there and the column is only "dropped" in the bookkeeping
     *       sense, while {@link #ADMIT_CONTESTED} means the near field is
     *       drawing it and the far copy is owed back when that ends -
     *       which now arrives as {@link #onFarBlockerReleased} rather
     *       than never;</li>
     *   <li>the decode landed but {@link FarField#submitCompute} refused
     *       the mesh job (IO parked), which clears the in-flight mark
     *       and files the column nowhere;</li>
     *   <li>a {@code RESULT_ERROR} came back.</li>
     * </ol>
     * <p>Cross-check against {@link #farColumnsAllSkipped} and
     * {@link #farAdmitSkipped}: if those move with this bucket, route 1
     * is the cause and admission contention is the defect.</p>
     */
    public static final int COL_DROPPED = 10;
    /**
     * Census: the walker is not armed (no camera, no world, master off,
     * or {@code l1 <= nearEdge}), so it has no opinion about any column.
     * A census that is all of these is measuring nothing and must not be
     * read as "everything is fine".
     */
    public static final int COL_WALKER_IDLE = 11;
    /**
     * Census: a near-field owner that had refused one of this column's
     * sections just let go, and the re-read is owed on the next pump
     * ({@link #unblockedColumns}). Transient like
     * {@link #COL_LATE_PENDING}; a persistent population means
     * {@link #drainUnblockedColumns} is being starved by
     * {@link #MAX_IN_FLIGHT}. Reported only for a column that is not
     * resident at all - one that is drawing something says so first,
     * and its {@link #refillColumns} mark shows the same thing as
     * {@link #COL_RESIDENT_REFILL}.
     */
    public static final int COL_UNBLOCK_PENDING = 12;
    /**
     * Census: the NEAR FIELD is drawing every section of this column.
     * Inside the handover band, its whole admission pass came back
     * {@link #ADMIT_CONTESTED} with a watch armed on every position, and
     * the walker has memoized it ({@link #nearOwnedColumns}) rather than
     * re-read a column that is already on screen.
     *
     * <p><b>Not a missing column.</b> It is the ordinary, expected verdict
     * for most of the band on settled ground, and a census that folds it
     * into its missing population is reporting the near field's success as
     * the far field's failure. Use {@link #isNearFieldDrawnState} rather
     * than testing {@link #COL_NEAR_COVERED} alone.</p>
     *
     * <p>What WOULD be a defect is this bucket staying full at a position
     * the player can see a hole in: since seam step 4 that would mean a
     * covered near-side free that neither parked nor filed its demand -
     * a hole in the coverage-park rule itself. Cross-check
     * {@link #farBlockersReleased} against {@link #farContestedSections}
     * over a travel leg, and the ledger's parked/resolved partition on
     * the residency side.</p>
     */
    public static final int COL_NEAR_OWNED = 13;
    /** Verdict count, so a caller can size a histogram without guessing. */
    public static final int COL_STATE_COUNT = 14;

    /** Short greppable name for a {@link #classifyColumn} verdict. */
    public static String columnStateName(int state) {
        return switch (state) {
            case COL_RESIDENT -> "RESIDENT";
            case COL_RESIDENT_PARTIAL -> "RESIDENT_PARTIAL";
            case COL_RESIDENT_REFILL -> "RESIDENT_REFILL";
            case COL_IN_FLIGHT -> "IN_FLIGHT";
            case COL_QUEUED -> "QUEUED";
            case COL_LATE_PENDING -> "LATE_PENDING";
            case COL_ABSENT -> "ABSENT";
            case COL_NEAR_COVERED -> "NEAR_COVERED";
            case COL_OUTSIDE_RING -> "OUTSIDE_RING";
            case COL_NEVER_SCANNED -> "NEVER_SCANNED";
            case COL_DROPPED -> "DROPPED";
            case COL_WALKER_IDLE -> "WALKER_IDLE";
            case COL_UNBLOCK_PENDING -> "UNBLOCK_PENDING";
            case COL_NEAR_OWNED -> "NEAR_OWNED";
            default -> "UNKNOWN(" + state + ")";
        };
    }

    /** True for the three verdicts that mean "the far field is drawing something here". */
    public static boolean isResidentState(int state) {
        return state == COL_RESIDENT || state == COL_RESIDENT_PARTIAL
                || state == COL_RESIDENT_REFILL;
    }

    /**
     * True for the verdicts that mean "the NEAR field is drawing this, so
     * the far field being absent is correct" — the two ways a column can
     * be vanilla's: inside the covered disc, or inside the handover band
     * with the near field proven to own every section of it.
     *
     * <p>A census that measures "wanted but not drawn" must exclude both.
     * Excluding only {@link #COL_NEAR_COVERED} was sufficient before the
     * band existed and is not any more: it would count the band's
     * ordinary, correct state as a hole.</p>
     */
    public static boolean isNearFieldDrawnState(int state) {
        return state == COL_NEAR_COVERED || state == COL_NEAR_OWNED;
    }

    /**
     * Why is this chunk column not drawn by the far field right now?
     *
     * <p>The test order deliberately MIRRORS
     * {@link #continuePromotionScan}'s own skip chain - near-cover,
     * then completeness, then in flight, then queued, then absent - so a
     * verdict is a report of what the walker would decide, not a second
     * opinion formed from the same data by different arithmetic. The one
     * departure is that residency is reported before an outstanding
     * re-read: a column that is resident-partial AND being re-read is
     * labelled by what is on screen, because the census's job is to
     * explain the picture.</p>
     *
     * <p>RENDER THREAD ONLY (see the section header). Never throws.</p>
     *
     * @return one of the {@code COL_*} verdicts
     */
    public static int classifyColumn(int chunkX, int chunkZ) {
        if (lastCamera == SectionBuildTap.CAMERA_SECTION_UNKNOWN
                || lastNearEdge <= 0 || lastL1 <= 0) {
            return COL_WALKER_IDLE;
        }
        int camX = (int) (lastCamera >> 32);
        int camZ = (int) lastCamera;
        int dx = chunkX - camX;
        int dz = chunkZ - camZ;
        if (nearCovered(dx, dz, lastCoverEdge)) {
            return COL_NEAR_COVERED;
        }
        if (!withinRing(dx, dz, lastL1)) {
            return COL_OUTSIDE_RING;
        }
        // Chebyshev, and ONLY for the cursor comparison below: scanRing is
        // an enumeration index, not a membership radius.
        int dist = Math.max(Math.abs(dx), Math.abs(dz));
        long key = colKey(chunkX, chunkZ);
        if (residentColumns.containsKey(key)) {
            if (partialColumns.contains(key)) {
                return COL_RESIDENT_PARTIAL;
            }
            return refillColumns.contains(key) ? COL_RESIDENT_REFILL : COL_RESIDENT;
        }
        if (inFlightSet.contains(key)) {
            return COL_IN_FLIGHT;
        }
        if (queuedSet.contains(key)) {
            return COL_QUEUED;
        }
        if (lateShells.contains(key)) {
            return COL_LATE_PENDING;
        }
        if (unblockedColumns.contains(key)) {
            return COL_UNBLOCK_PENDING;
        }
        if (absentSet.contains(key)) {
            return COL_ABSENT;
        }
        // After ABSENT and before the cursor test, mirroring the walk's
        // own skip chain: the memo is the last of the scan's skip sets.
        if (nearOwnedColumns.contains(key)) {
            return COL_NEAR_OWNED;
        }
        if (scanPending && !scanCursorPassed(dx, dz, dist)) {
            return COL_NEVER_SCANNED;
        }
        return COL_DROPPED;
    }

    /**
     * How many far sections are admitted for this column, or -1 when the
     * column is not resident at all. The difference between 1 and 4 here
     * is the difference between a hollowed column and a whole one, and
     * neither {@link #classifyColumn} nor {@link #farSectionsResident}
     * can tell them apart.
     *
     * <p>RENDER THREAD ONLY.</p>
     */
    public static int residentSectionCount(int chunkX, int chunkZ) {
        IntArrayList sys = residentColumns.get(colKey(chunkX, chunkZ));
        return sys == null ? -1 : sys.size();
    }

    /**
     * Has the ring cursor already walked past this camera-relative
     * offset in the CURRENT pass? Only meaningful while
     * {@link #scanPending}; a finished pass has covered everything.
     *
     * <p>Rings below {@code innerScanRing} need no special case: that
     * value is the smallest {@code r} whose CORNER escapes the COVERED
     * disc, and the corner is the most distant point of a ring, so every
     * column on a lower ring answers {@link #nearCovered} true and is
     * classified before this is reached. Both this method and
     * {@link #classifyColumn} read the same {@link #lastCoverEdge} the
     * walk does, so the band moves them together.</p>
     */
    private static boolean scanCursorPassed(int dx, int dz, int dist) {
        if (dist < scanRing) {
            return true;
        }
        if (dist > scanRing) {
            return false;
        }
        return ringIndex(dx, dz, dist) < scanIndex;
    }

    /**
     * Exact inverse of {@link #ringChunk}: the index this offset occupies
     * in the Chebyshev ring of radius {@code r}. The two must be read
     * together - {@code ringChunk} lays the ring out as top row
     * (both corners, {@code 2r+1} entries), right column
     * ({@code 2r-1} interior entries), bottom row (both corners, walked
     * backwards), left column (interior, walked backwards) - and the
     * corner-owning rows are tested FIRST here for the same reason.
     */
    private static int ringIndex(int dx, int dz, int r) {
        if (r <= 0) {
            return 0;
        }
        int top = 2 * r + 1;
        int side = 2 * r - 1;
        if (dz == -r) {
            return dx + r;
        }
        if (dz == r) {
            return top + side + (r - dx);
        }
        if (dx == r) {
            return top + (dz + r - 1);
        }
        if (dx == -r) {
            return top + side + top + (r - 1 - dz);
        }
        return 0; // not on the ring; unreachable for r = Chebyshev distance
    }

    /**
     * The walker's own view of the world, so a diagnostic reports the
     * ring the WALKER is working on rather than one recomputed from the
     * client's camera. They differ by up to a pump, and at 20 hops a
     * scene that difference is exactly the window the missing columns
     * hide in.
     *
     * @param armed                   the walker has a camera, a world and a ring
     * @param cameraChunkX            walker's camera column X (chunk coords)
     * @param cameraChunkZ            walker's camera column Z (chunk coords)
     * @param nearEdge                vanilla's effective render distance,
     *                                as the walker last read it
     * @param coverEdge               the radius the near-cover test
     *                                actually runs at: {@code nearEdge}
     *                                less the handover band (H1). Equal to
     *                                {@code nearEdge} means the band is
     *                                off and the walker is behaving
     *                                exactly as it did in pre6
     * @param l1                      the far radius AFTER the
     *                                projection-far-plane clamp
     * @param innerRing               innermost ring the scan starts from
     * @param scanPending             a ring pass is in progress
     * @param scanRing                cursor ring
     * @param scanIndex               cursor index within that ring
     * @param residentColumnCount     columns with at least one admitted far section
     * @param inFlightCount           reads outstanding
     * @param queuedCount             columns meshed and awaiting admission
     * @param absentCount             columns written off as having no shell
     * @param partialCount            resident columns holed by a budget refusal
     * @param refillCount             resident columns carrying a one-shot re-read mark
     * @param latePendingCount        columns whose shell landed after the scan passed
     * @param nearOwnedCount          columns memoized as wholly owned by
     *                                the near field ({@link #nearOwnedColumns});
     *                                on settled ground this is most of the
     *                                handover band and is a healthy number
     * @param pendingAdmissionColumns columns in the admission queue
     * @param pendingAdmissionSections sections in the admission queue
     */
    public record WalkerSnapshot(boolean armed, int cameraChunkX, int cameraChunkZ,
            int nearEdge, int coverEdge, int l1, int innerRing,
            boolean scanPending, int scanRing, int scanIndex,
            int residentColumnCount, int inFlightCount, int queuedCount,
            int absentCount, int partialCount, int refillCount, int latePendingCount,
            int nearOwnedCount,
            int pendingAdmissionColumns, int pendingAdmissionSections) {
    }

    /**
     * One read-only snapshot of the walker's arm state and set sizes.
     * RENDER THREAD ONLY.
     */
    public static WalkerSnapshot walkerSnapshot() {
        boolean armed = lastCamera != SectionBuildTap.CAMERA_SECTION_UNKNOWN
                && lastNearEdge > 0 && lastL1 > 0;
        return new WalkerSnapshot(armed,
                armed ? (int) (lastCamera >> 32) : 0,
                armed ? (int) lastCamera : 0,
                lastNearEdge, lastCoverEdge, lastL1,
                lastCoverEdge > 0 ? innerScanRing(lastCoverEdge) : -1,
                scanPending, scanRing, scanIndex,
                residentColumns.size(), inFlightSet.size(), queuedSet.size(),
                absentSet.size(), partialColumns.size(), refillColumns.size(),
                lateShells.size(), nearOwnedColumns.size(),
                pendingAdmissions.size(), pendingAdmissionSections);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Is this column inside the walker's wanted set right now?
     *
     * <p>THE WINDOW, and it is the whole of the H1 fix. This is what the
     * late-shell drain, the unblocked drain and {@link #pollAdmission}
     * ask, so it is what decides whether a shell that has been read,
     * decoded and meshed is allowed to reach the screen. Against
     * {@code nearEdge} the answer for a first-visit column went false one
     * chunk BEFORE its shell existed, and every one of those columns was
     * meshed and then thrown away at the last step. Against
     * {@link #lastCoverEdge} the column stays wanted for the whole of the
     * handover band, which is the difference between a negative window
     * and a two-second one.</p>
     *
     * <p>Deliberately NOT a "has vanilla built it" test, for two reasons.
     * It runs inside the residency's LOCK window (through
     * {@link #pollAdmission} and {@link #onFarBlockerReleased}), where the
     * no-vanilla-under-LOCK discipline forbids asking the level renderer
     * anything; and a predicate that flipped the moment vanilla's compile
     * landed would retire a column halfway through its own admission pass,
     * abandoning the sections vanilla has NOT built beside the one it has.
     * Ownership is decided per POSITION, at admission, by
     * {@code admitFarSectionLocked} — which is the only place that can see
     * it without racing.</p>
     */
    private static boolean stillWanted(long chunkKey) {
        if (lastCamera == SectionBuildTap.CAMERA_SECTION_UNKNOWN) {
            return false;
        }
        int camX = (int) (lastCamera >> 32);
        int camZ = (int) lastCamera;
        int dx = colX(chunkKey) - camX;
        int dz = colZ(chunkKey) - camZ;
        // Both edges EUCLIDEAN since the H2 shape audit: the inner one
        // because it is vanilla's own compile test, the outer one because
        // it has to meet a radial fog wall. The Chebyshev distance that
        // used to bound L1 here is gone; the square that remains is the
        // ring WALK's enumeration order, which is a different thing and
        // deliberately unchanged (class javadoc).
        return !nearCovered(dx, dz, lastCoverEdge) && withinRing(dx, dz, lastL1);
    }

    /**
     * The radius the near-cover test actually runs at: vanilla's
     * effective render distance less the handover band (class javadoc,
     * section H1).
     *
     * <p>The band is {@link #HANDOVER_BAND_CHUNKS}, tapered on small
     * render distances by {@code nearEdge / 4} so it can never swallow a
     * meaningful fraction of a short view — at nearEdge 8 the band is 2
     * chunks, at 16 and above it is the full 4. The result is floored at
     * 1 so the covered disc never collapses onto the camera's own column,
     * which would put shell geometry under the player's feet.</p>
     *
     * <p>Worked, at the shipped band of 4: nearEdge 8 gives 6, nearEdge 16
     * gives 12, nearEdge 32 gives 28. With the band property set to 0 this
     * is the identity and every inner-edge test is exactly pre6's.</p>
     */
    private static int coverRadius(int nearEdge) {
        if (nearEdge <= 0) {
            return nearEdge;
        }
        int band = Math.min(HANDOVER_BAND_CHUNKS, nearEdge / 4);
        return Math.max(1, nearEdge - band);
    }

    /**
     * Resolve {@link #HANDOVER_BAND_CHUNKS} once, at class load. Reads a
     * system property rather than {@code FarFieldConfig} on purpose: this
     * is a diagnostic lever for the playtest that has to confirm the H1
     * fix, not a player-facing setting, and the walker must not acquire a
     * new config dependency on the pump's hot path to get it.
     */
    private static int resolveHandoverBand() {
        // The FarFieldConfig property prefix, spelled out because that
        // constant is private to the config class.
        Integer override = Integer.getInteger("meshelium.farfield.handoverBandChunks");
        if (override == null) {
            return DEFAULT_HANDOVER_BAND_CHUNKS;
        }
        return Math.max(0, Math.min(MAX_HANDOVER_BAND_CHUNKS, override));
    }

    /**
     * Is this camera-relative chunk offset inside the set vanilla can
     * COMPILE? Our own copy of vanilla's arithmetic, not a call into it:
     * the vanilla method is
     * {@code ChunkTrackingView.isWithinDistance(int, int, int, int, int,
     * boolean)}, a public static interface method whose entire body
     * (javap, 26.2 merged jar, read in this authoring session) is
     * <pre>
     *   ip  0-10  pad = includeOuter ? 2 : 1
     *   ip 12-26  dx  = max(0, |x - cx| - pad)
     *   ip 28-43  dz  = max(0, |z - cz| - pad)
     *   ip 45-77  return dx*dx + dz*dz &lt; vd*vd     (STRICT less-than)
     * </pre>
     * with {@code includeOuter} false at the client BFS call site
     * ({@code ChunkTrackingView.isInViewDistance(IIIII)Z} passes
     * {@code iconst_0} at ip 6). Reimplemented rather than invoked
     * because that class lives in {@code net.minecraft.server.level} and
     * the walker must stay callable from the pump with no vanilla state
     * beyond the two ints it already holds; the bytecode above is the
     * contract this must be re-checked against on every MC update.
     *
     * <p>{@code radiusChunks <= 0} answers false: with no render distance
     * there is no near field to defer to.</p>
     *
     * <p><b>The walker no longer passes {@code nearEdge} here.</b> Since
     * H1 every call site passes {@link #lastCoverEdge}, the same disc
     * shortened by the handover band, because "can compile" and "has
     * compiled" are different sets and only the second one is a reason to
     * stand aside. The arithmetic below is still vanilla's, exactly, and
     * is what has to be re-checked against the bytecode on every MC
     * update; which radius to feed it is this class's decision and is
     * argued in section H1 of the class javadoc.</p>
     */
    private static boolean nearCovered(int dx, int dz, int radiusChunks) {
        if (radiusChunks <= 0) {
            return false;
        }
        long px = Math.max(0, Math.abs(dx) - 1);
        long pz = Math.max(0, Math.abs(dz) - 1);
        return px * px + pz * pz < (long) radiusChunks * radiusChunks;
    }

    /**
     * Is this camera-relative chunk offset inside the ring's OUTER edge,
     * at {@code radiusChunks}? EUCLIDEAN, inclusive — the metric the far
     * horizon is actually made of (class javadoc, "The shapes and their
     * metrics"), not the Chebyshev square the ring WALK enumerates.
     *
     * <p>The fog wall this edge has to meet is a radial distance:
     * {@code FarFieldFogMixin} sets {@code FogData.renderDistanceEnd} to
     * {@code min(l1RadiusChunks * 16, projectionFarPlane)} BLOCKS, and fog
     * is applied per fragment by distance from the camera, so full opacity
     * is reached on a CIRCLE of radius {@code L1 * 16} blocks. Against a
     * Chebyshev outer edge the ring's diagonal reached {@code L1 * sqrt(2)}
     * — 90 chunks at L1 64, against a wall at 64 — so 27 percent of every
     * column the walker read, meshed, admitted, gave a region id to and
     * kept resident could not put a single unfogged fragment on screen.
     * That is not a hole, it is the opposite, and it is why this edge had
     * to be brought onto the same metric as the rest: the boundaries now
     * agree everywhere, so nothing is claimed by two owners and nothing by
     * none.</p>
     *
     * <p>Inclusive ({@code <=}) rather than vanilla's strict {@code <}
     * deliberately: this is Meshelium's own edge, not a reimplementation
     * of a vanilla test, and the column at exactly {@code L1} is the one
     * whose FAR face sits at {@code (L1 + 1) * 16} blocks — just past the
     * fog end, which is what stops a sliver of sky opening between the
     * last drawn column and full fog. {@code nearCovered} above keeps
     * vanilla's strict comparison because it IS vanilla's test.</p>
     */
    private static boolean withinRing(int dx, int dz, int radiusChunks) {
        if (radiusChunks < 0) {
            return false;
        }
        long x = dx;
        long z = dz;
        return x * x + z * z <= (long) radiusChunks * radiusChunks;
    }

    /**
     * The innermost Chebyshev ring that holds at least one column outside
     * the covered disc — where the promotion scan starts. The argument is
     * {@link #lastCoverEdge}, not {@code nearEdge}: the scan has to reach
     * the handover band or the band is wanted and never walked.
     *
     * <p>A ring's most distant point is its diagonal corner
     * {@code (r, r)}, so the answer is the smallest {@code r} with
     * {@code !nearCovered(r, r, coverEdge)}, i.e. {@code 2*(r-1)^2 >=
     * coverEdge^2}, i.e. {@code r >= coverEdge/sqrt(2) + 1}. Seeded just
     * BELOW that and walked up, so the result is exact integer
     * arithmetic against {@link #nearCovered} itself rather than a
     * floating-point rounding that could silently skip the innermost
     * lobe ring. At most a couple of iterations, once per re-arm.</p>
     *
     * <p>Worked at the shipped band of 4: nearEdge 16 gives coverEdge 12
     * and an inner ring of 10 (pre6 started at 13, pre1 at 17); nearEdge
     * 32 gives coverEdge 28 and 21 (pre6 24, pre1 33). The extra rings
     * are the cheapest part of the change — three more rings at nearEdge
     * 16 is {@code 8*(10+11+12) = 264} extra probes per pass, against a
     * budget of {@value #SCAN_STEPS_PER_PUMP}. For coverEdge below 4 it
     * lands on {@code coverEdge+1} and nothing changes, which is the same
     * threshold FRONTIER-HOLES-RECON.md section 1.4 derives for the
     * corner lobes existing at all.</p>
     */
    private static int innerScanRing(int coverEdge) {
        int r = Math.max(1, (int) (coverEdge * 0.7071067811865476) - 1);
        while (nearCovered(r, r, coverEdge)) {
            r++;
        }
        return r;
    }

    private static long colKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int colX(long key) {
        return (int) (key >> 32);
    }

    private static int colZ(long key) {
        return (int) key;
    }
}
