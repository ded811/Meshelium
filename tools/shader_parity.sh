#!/bin/sh
# Byte-identity check for every shipped shader variant at the macro
# defaults TerrainDrawPipeline / TerrainOcclusion inject (Meshelium,
# LGPL-3.0-only, Copyright (C) 2026 Ded811).
#
# WHY. The D-023 stage 2-3 shader edits (GPU-VISIBILITY-CONTRACT.md 0.2
# rule 1, 12.1) add code only under MESHELIUM_SODIUM_TASK (terrain.task,
# terrain.mesh) and MESHELIUM_SODIUM (the occlusion rasters). Every
# standalone variant, and the stage-1 Sodium list path, must therefore
# compile to the SAME SPIR-V bytes before and after the edit: a moved
# hash is a behaviour change in a renderer that was not supposed to be
# touched. "Defined 0 vs undefined" is inside the baseline on purpose -
# the pre-edit run already passed -DMESHELIUM_SODIUM_TASK=0 and
# -DMESHELIUM_SODIUM=0 on the rasters.
#
# HOW. glslc + spirv-val from Vulkan SDK 1.4.357.0, --target-env
# vulkan1.2, no -O: the invocation MesheliumShaderCompiler makes. The
# first 16 hex of the sha256 of each .spv is printed per row; rows named
# in the baseline file are compared and any moved or failed row fails
# the run; rows NOT in the baseline are the new variants (they must
# compile and validate, and get a baseline once the coordinator records
# one). A missing tool or file fails loudly.
#
# usage: sh tools/shader_parity.sh <shaders dir> <out dir> [baseline]
#   shaders dir : common/src/main/resources/assets/meshelium/shaders
#   out dir     : scratch directory for the .spv files (created)
#   baseline    : defaults to tools/shader_parity.baseline next to this
#                 script; "-" disables the compare (print hashes only)
# exit status: 0 clean; 1 a baseline row moved or failed to compile;
#              2 a NEW (non-baseline) row failed to compile or validate;
#              3 both; 4 usage / tool missing; 5 a half-res marker comment
#              appears the wrong number of times in a raster.

R="$1"; OUT="$2"; BASE="$3"
HERE=$(cd "$(dirname "$0")" && pwd)
[ -z "$BASE" ] && BASE="$HERE/shader_parity.baseline"
SDK="${VULKAN_SDK:-/c/VulkanSDK/1.4.357.0}"
G="$SDK/Bin/glslc.exe"; V="$SDK/Bin/spirv-val.exe"
if [ -z "$R" ] || [ -z "$OUT" ]; then
  echo "usage: sh tools/shader_parity.sh <shaders dir> <out dir> [baseline|-]" >&2; exit 4
fi
if [ ! -f "$R/terrain.task" ]; then echo "no terrain.task under $R" >&2; exit 4; fi
if [ ! -x "$G" ] || [ ! -x "$V" ]; then echo "glslc/spirv-val not found under $SDK/Bin (set VULKAN_SDK)" >&2; exit 4; fi
mkdir -p "$OUT" || exit 4
RESULTS="$OUT/shader_parity.results"; : > "$RESULTS"

COMMON="-DMESHELIUM_ARENA_BLOCKS=1 -DMESHELIUM_ARENA_BLOCK_SHIFT=24 -DMESHELIUM_ARENA_BLOCK_MASK=16777215u -DMESHELIUM_WG_SIZE=32 -DMESHELIUM_TASK_WG_SIZE=32 -DMESHELIUM_VIS_UVEC4S=1024 -DMESHELIUM_PACKED_VARYINGS=1 -DMESHELIUM_NO_DISCARD=0 -DMESHELIUM_TRANS_QUADS=1 -DMESHELIUM_LIGHT_STUB=0 -DMESHELIUM_DIAG_NOFOG=0 -DMESHELIUM_SODIUM=0 -DMESHELIUM_SODIUM_BATCH=0 -DMESHELIUM_SODIUM_DRAWID=0 -DMESHELIUM_SODIUM_TASK=0 -DMESHELIUM_SODIUM_MESH_FRUSTUM=0 -DMESHELIUM_SODIUM_FRUSTUM_SLACK=1.125"

# c <row name> <stage> <source> <macros...>: compile, validate, print
# "<hash>  <name>" and record the row; a failure prints "FAIL  <name>"
# and records "-" so the compare below can tell moved from broken.
c() {
  name=$1; stage=$2; src=$3; shift 3
  if "$G" --target-env=vulkan1.2 -fshader-stage=$stage "$@" -o "$OUT/$name.spv" "$src" 2>"$OUT/$name.err" && "$V" --target-env vulkan1.2 "$OUT/$name.spv" 2>>"$OUT/$name.err"; then
    h=$(sha256sum "$OUT/$name.spv" | cut -c1-16)
    printf "%s  %s\n" "$h" "$name"; printf "%s %s\n" "$name" "$h" >> "$RESULTS"
  else
    printf "FAIL              %s  (see %s)\n" "$name" "$OUT/$name.err"; printf "%s -\n" "$name" >> "$RESULTS"
  fi
}

echo "# baseline rows (GPU-VISIBILITY-CONTRACT.md 12.1)"
# standalone task-cull, standard lists
c task_std task "$R/terrain.task" $COMMON -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
c mesh_task_std mesh "$R/terrain.mesh" $COMMON -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
c frag_std frag "$R/terrain.frag" $COMMON -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
# standalone task-cull, extended lists
c task_ext task "$R/terrain.task" $COMMON -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=1
c mesh_task_ext mesh "$R/terrain.mesh" $COMMON -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=1
# standalone cpu-cull
c mesh_cpu mesh "$R/terrain.mesh" $COMMON -DMESHELIUM_TASK_CULL=0 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
c frag_cpu frag "$R/terrain.frag" $COMMON -DMESHELIUM_TASK_CULL=0 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
# standalone translucent (TRANS_QUADS=64)
TRANS=$(echo $COMMON | sed 's/-DMESHELIUM_TRANS_QUADS=1/-DMESHELIUM_TRANS_QUADS=64/')
c mesh_trans mesh "$R/terrain.mesh" $TRANS -DMESHELIUM_TASK_CULL=0 -DMESHELIUM_TRANSLUCENT=1 -DMESHELIUM_LISTS_SSBO=0
c frag_trans frag "$R/terrain.frag" $TRANS -DMESHELIUM_TASK_CULL=0 -DMESHELIUM_TRANSLUCENT=1 -DMESHELIUM_LISTS_SSBO=0
# unpacked-varyings twin of task-cull std
UNPACKED=$(echo $COMMON | sed 's/-DMESHELIUM_PACKED_VARYINGS=1/-DMESHELIUM_PACKED_VARYINGS=0/')
c mesh_task_unpacked mesh "$R/terrain.mesh" $UNPACKED -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
c frag_unpacked frag "$R/terrain.frag" $UNPACKED -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
# stage-1 Sodium list path (batched, gl_DrawID), discard and no-discard
SOD=$(echo $COMMON | sed 's/-DMESHELIUM_SODIUM=0/-DMESHELIUM_SODIUM=1/; s/-DMESHELIUM_SODIUM_BATCH=0/-DMESHELIUM_SODIUM_BATCH=1/; s/-DMESHELIUM_SODIUM_DRAWID=0/-DMESHELIUM_SODIUM_DRAWID=1/')
c mesh_sodium_drawid mesh "$R/terrain.mesh" $SOD -DMESHELIUM_TASK_CULL=0 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
c frag_sodium frag "$R/terrain.frag" $SOD -DMESHELIUM_TASK_CULL=0 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
SODND=$(echo $SOD | sed 's/-DMESHELIUM_NO_DISCARD=0/-DMESHELIUM_NO_DISCARD=1/')
c frag_sodium_nodiscard frag "$R/terrain.frag" $SODND -DMESHELIUM_TASK_CULL=0 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
# the frustum lever's gpu mode (SodiumTerrainDrawer.PROPERTY_FRUSTUM=gpu): a new row
SODFR=$(echo $SOD | sed 's/-DMESHELIUM_SODIUM_MESH_FRUSTUM=0/-DMESHELIUM_SODIUM_MESH_FRUSTUM=1/')
c mesh_sodium_drawid_frustum mesh "$R/terrain.mesh" $SODFR -DMESHELIUM_TASK_CULL=0 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
# occlusion rasters: standard, extended, mark-new
OCC="-DMESHELIUM_OCC_REGIONS=512 -DMESHELIUM_SODIUM=0"
c region_raster_std mesh "$R/occlusion/region_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=0
c section_task_std task "$R/occlusion/section_raster.task" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=0
c section_mesh_std mesh "$R/occlusion/section_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=0
c box_frag_std frag "$R/occlusion/box.frag" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=0
c region_raster_ext mesh "$R/occlusion/region_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0
c section_task_ext task "$R/occlusion/section_raster.task" $OCC -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0
c section_mesh_mark mesh "$R/occlusion/section_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=1
c box_frag_mark frag "$R/occlusion/box.frag" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=1

echo "# new rows: the D-023 GPU-visibility pipeline (no baseline; must compile and validate)"
# TerrainDrawPipeline.createSodiumTask: SODIUM=1, TASK_CULL=1, SODIUM_TASK=1, BATCH=0, DRAWID=0
# (contract 5.2); the discard-free twin is the frag with NO_DISCARD=1.
SODTASK=$(echo $COMMON | sed 's/-DMESHELIUM_SODIUM=0/-DMESHELIUM_SODIUM=1/; s/-DMESHELIUM_SODIUM_TASK=0/-DMESHELIUM_SODIUM_TASK=1/')
c task_sodium task "$R/terrain.task" $SODTASK -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
c mesh_sodium_task mesh "$R/terrain.mesh" $SODTASK -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
c frag_sodium_task frag "$R/terrain.frag" $SODTASK -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
SODTASKND=$(echo $SODTASK | sed 's/-DMESHELIUM_NO_DISCARD=0/-DMESHELIUM_NO_DISCARD=1/')
c frag_sodium_task_nodiscard frag "$R/terrain.frag" $SODTASKND -DMESHELIUM_TASK_CULL=1 -DMESHELIUM_TRANSLUCENT=0 -DMESHELIUM_LISTS_SSBO=0
# TerrainOcclusion.ensureSodiumPipelines: SODIUM=1, LISTS_SSBO=1, MARK_NEW=0 (contract 2.2, 5.6)
OCCSOD="-DMESHELIUM_OCC_REGIONS=512 -DMESHELIUM_SODIUM=1"
c region_raster_sodium mesh "$R/occlusion/region_raster.mesh" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0
c section_task_sodium task "$R/occlusion/section_raster.task" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0
c section_mesh_sodium mesh "$R/occlusion/section_raster.mesh" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0
c box_frag_sodium frag "$R/occlusion/box.frag" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0
# the packed section raster (SodiumTerrainDrawer.PROPERTY_OCC_BOXES_PER_WG=4): new rows
c section_task_sodium_x4 task "$R/occlusion/section_raster.task" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_BOXES_PER_WG=4
c section_mesh_sodium_x4 mesh "$R/occlusion/section_raster.mesh" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_BOXES_PER_WG=4

# NEXT (c): the half-res occlusion depth (no baseline yet)
#
# THE MARKER GUARD, first. Every half-res edit lives under a macro, and
# the section raster has TWO arms that need it (the packed Sodium arm and
# the shared arm that covers the Sodium one-box and standalone arms) while
# the region raster has one. An insertion that lands in the wrong
# preprocessor branch compiles fine and silently leaves one arm without
# the inflation or the near force - which is exactly the mistake plan
# revision 1 made. Counting the markers is the cheap check that cannot
# miss it, and it runs BEFORE the rows so a miscount fails loudly.
for f in region_raster:1 section_raster:2; do
  n=${f%%:*}; want=${f##*:}
  for m in "// OCC_HALFRES_INFLATE" "// OCC_HALFRES_NEARCLIP" "// OCC_HALFRES_FLATPOS"; do
    got=$(grep -c "$m" "$R/occlusion/$n.mesh")
    [ "$got" = "$want" ] || { echo "$n.mesh: $m appears $got times, want $want" >&2; exit 5; }
  done
done

# The BIAS comparator variants (MESHELIUM_OCC_HALFRES=1 alone). Built and
# measured, never shippable: the slope bias can push a near-eye box's depth
# out of [0, 1], where Vulkan leaves z_f undefined without depth clamping.
c region_raster_std_half mesh "$R/occlusion/region_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1
c region_raster_ext_half mesh "$R/occlusion/region_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1
c section_mesh_std_half mesh "$R/occlusion/section_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1
c section_mesh_mark_half mesh "$R/occlusion/section_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=1 -DMESHELIUM_OCC_HALFRES=1
c region_raster_sodium_half mesh "$R/occlusion/region_raster.mesh" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1
# The Sodium ONE-BOX arm: the row plan revision 1's insertion point would
# have compiled without the inflation at all.
c section_mesh_sodium_half mesh "$R/occlusion/section_raster.mesh" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1
c section_mesh_sodium_x4_half mesh "$R/occlusion/section_raster.mesh" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_BOXES_PER_WG=4 -DMESHELIUM_OCC_HALFRES=1

# The FLAT arm: THE arm, and the only one a default flip can act on. Built
# on BOTH hosts in this change, so the standalone never ships only the
# comparator.
c region_raster_sodium_flat mesh "$R/occlusion/region_raster.mesh" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1 -DMESHELIUM_OCC_HALFRES_FLAT=1
c section_mesh_sodium_flat mesh "$R/occlusion/section_raster.mesh" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1 -DMESHELIUM_OCC_HALFRES_FLAT=1
c section_mesh_sodium_x4_flat mesh "$R/occlusion/section_raster.mesh" $OCCSOD -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_BOXES_PER_WG=4 -DMESHELIUM_OCC_HALFRES=1 -DMESHELIUM_OCC_HALFRES_FLAT=1
c region_raster_std_flat mesh "$R/occlusion/region_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1 -DMESHELIUM_OCC_HALFRES_FLAT=1
c region_raster_ext_flat mesh "$R/occlusion/region_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=1 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1 -DMESHELIUM_OCC_HALFRES_FLAT=1
c section_mesh_std_flat mesh "$R/occlusion/section_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=0 -DMESHELIUM_OCC_HALFRES=1 -DMESHELIUM_OCC_HALFRES_FLAT=1
c section_mesh_mark_flat mesh "$R/occlusion/section_raster.mesh" $OCC -DMESHELIUM_LISTS_SSBO=0 -DMESHELIUM_MARK_NEW=1 -DMESHELIUM_OCC_HALFRES=1 -DMESHELIUM_OCC_HALFRES_FLAT=1

# Pass D. No macros: one variant, always the same text.
c depth_downsample_mesh mesh "$R/occlusion/depth_downsample.mesh"
c depth_downsample_frag frag "$R/occlusion/depth_downsample.frag"

# ---- compare against the baseline ----
status=0
if [ "$BASE" != "-" ]; then
  if [ ! -f "$BASE" ]; then echo "baseline $BASE not found" >&2; exit 4; fi
  moved=0; newfail=0; checked=0
  while read -r name got; do
    want=$(grep -E "^[0-9a-f]{16}  $name\$" "$BASE" | cut -c1-16)
    if [ -n "$want" ]; then
      checked=$((checked + 1))
      if [ "$got" = "-" ]; then
        echo "BROKEN   $name (baseline $want, did not compile)"; moved=$((moved + 1))
      elif [ "$got" != "$want" ]; then
        echo "MOVED    $name (baseline $want, now $got)"; moved=$((moved + 1))
      fi
    elif [ "$got" = "-" ]; then
      echo "NEW-FAIL $name (no baseline; failed to compile or validate)"; newfail=$((newfail + 1))
    else
      echo "NEW      $name $got (no baseline row yet)"
    fi
  done < "$RESULTS"
  echo "# compared $checked baseline rows: $moved moved/broken, $newfail new rows failing"
  [ "$moved" -gt 0 ] && status=$((status | 1))
  [ "$newfail" -gt 0 ] && status=$((status | 2))
fi
exit $status
