#!/usr/bin/env bash
# Partition ScalaTest suites across CI runners and intersect with select_tests.sh.
#
# Usage:
#   ci_shard_tests.sh pr-suites <selected>
#     Print bounded unit suites for the required PR lane.
#
#   ci_shard_tests.sh shards-json <selected>
#     Print a JSON array of shard names that have work for <selected>.
#     <selected> is ALL, NONE, or space-separated sbt testOnly globs.
#
#   ci_shard_tests.sh suites <shard> <selected>
#     Print space-separated fully-qualified suite names for sbt testOnly,
#     or nothing if this shard has no work.
#
#   ci_shard_tests.sh flags <shard>
#     Print extra sbt -D options for this shard, or nothing.
#
# Shard layout (each runner stays serial inside one JVM so Chisel
# elaboration cannot OOM the shared heap). The old single `system` shard
# ran every full-GPU Verilator model back to back and was the hour-plus
# job. Those suites now fan out, and the desktop scaling sweeps below are
# left to local `sbt test`:
#   graphics-0..7 — stable file buckets under opengpu/graphics/**; concrete
#                    suites from one file stay together to reuse models
#   system-mem   — AXI masters and the register-only host
#   system-host  — GpuHostSystemAxi plus the fill-throughput model
#   system-walk  — 4-CU desktop page-walk and address repros (one shared model)
#   system-tex-0..7 — programmable texture and ordering, bucketed by suite
#   system-wide-0..3 — wide shared graphics, bucketed by suite
#   system-core  — GpuSystem command, geometry, and indirect compute
#   system-rt-narrow — 4- and 8-lane runtime shaders (side 16, one CU)
#   system-rt-16     — 16-lane runtime shader (side 32, one and four CUs)
#   system-rt-32     — 32-lane runtime shader (side 16, one CU)
#   system-rt-batch  — side-64 runtime full-batch control
#   system-pool  — one desktop full-pool control (GpuConfig defaults, side 64)
#   core-a       — opengpu/core/{execute,memory}/**
#   core-b       — remaining opengpu/core/** plus dispatch/dma/command/util
#
# CI skip (still run by a local full `sbt test`):
#   FullPoolRuntimeScalingSpec            — 1/2/4 CU desktop, side 64
#   RuntimeSharedGraphicsPerformanceSpec  — 1/2/4 CU runtime scaling, side 64
#   FullPoolTextureScalingSpec            — texture on/off sweeps; suite names
#                                           in that file are not the filename
#   PersistentTile* / Drm* product specs  — full GpuHostSystemAxi models,
#                                           including 16-lane and side-64 cases
#   GpuSystemPayloadSpec                  — full GpuSystem graphics kernels
#   SharedPayloadClientSystemSpec         — full GpuSystem graphics kernels
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
test_root="$root/src/test/scala"

shard_prefixes() {
  case "$1" in
    core-a)   printf '%s\n' "opengpu/core/execute" "opengpu/core/memory" ;;
    core-b)
      printf '%s\n' \
        "opengpu/core/frontend" \
        "opengpu/core/backend" \
        "opengpu/core/vector" \
        "opengpu/core/simt" \
        "opengpu/core/trap" \
        "opengpu/core/system" \
        "opengpu/dispatch" \
        "opengpu/dma" \
        "opengpu/command" \
        "opengpu/testutil" \
        "opengpu/util" \
        "opengpu/config"
      ;;
    *)
      echo "unknown shard: $1" >&2
      exit 2
      ;;
  esac
}

all_shards=(graphics-0 graphics-1 graphics-2 graphics-3 graphics-4 graphics-5 graphics-6 graphics-7 system-mem system-host system-walk system-tex-0 system-tex-1 system-tex-2 system-tex-3 system-tex-4 system-tex-5 system-tex-6 system-tex-7 system-wide-0 system-wide-1 system-wide-2 system-wide-3 system-core system-rt-narrow system-rt-16 system-rt-32 system-rt-batch system-pool core-a core-b)

graphics_shard_files() {
  # Hash paths rather than source bytes or directory order: editing a test or
  # adding a new file must not move all the other compiled models to new runners.
  python3 - "$test_root" "${1#graphics-}" <<'PY'
from pathlib import Path
import hashlib
import sys

root = Path(sys.argv[1])
bucket = int(sys.argv[2])
for path in sorted((root / "opengpu/graphics").rglob("*Spec.scala")):
    key = path.relative_to(root).as_posix().encode("utf-8")
    if hashlib.sha256(key).digest()[0] % 8 == bucket:
        print(path)
PY
}

# Map one opengpu/system spec file to a shard, or SKIP.
# A new file must be added here; otherwise CI selection fails closed.
system_shard_for() {
  case "$(basename "$1")" in
    AxiStimulusOrderingSpec.scala|ComputeMemoryAxiMasterSpec.scala|GpuHostAxiSpec.scala|GpuHostAxiOwnershipSpec.scala|GpuWorkloadSpec.scala|TileBinHostMemoryFabricSpec.scala|NativeBinnedGraphicsMemoryFabricSpec.scala|NativeTypedGraphicsMemoryFabricSpec.scala|NativeVertexInputCapabilitiesAxiSpec.scala|NativeTextureOperationCapabilitiesAxiSpec.scala|NativeUniformCapabilitiesAxiSpec.scala|ComputeIndirectCapabilitiesAxiSpec.scala|ComputeResourceCapabilitiesAxiSpec.scala|NativePrimitiveQueryCapabilitiesAxiSpec.scala|NativeTransformFeedbackCapabilitiesAxiSpec.scala|NativeShaderExecutionCapabilitiesAxiSpec.scala|NativeRgba8TextureMemoryFabricSpec.scala)
      printf '%s\n' system-mem ;;
    GpuHostSystemAxiSpec.scala|HostExecutionAvailabilitySpec.scala|GpuFillThroughputSpec.scala|GpuHostVmOwnershipSpec.scala|GpuHostIndirectComputeSpec.scala|ComputeResourceOwnerHostSpec.scala|ComputeResourceFullHostSpec.scala|NativeControlHostDmaSpec.scala)
      printf '%s\n' system-host ;;
    RuntimeVectorAddressSpec.scala|SharedCuTwoLevelWalkSpec.scala)
      printf '%s\n' system-walk ;;
    ProgrammableTextureAxiSpec.scala|ParallelGraphicsOrderingSpec.scala)
      printf '%s\n' system-tex ;;
    WideSharedGraphicsSpec.scala|FixedFragmentWorkersSpec.scala)
      printf '%s\n' system-wide ;;
    GpuSystemSpec.scala|GpuSystemComputeGeometrySpec.scala|GpuSystemIndirectComputeSpec.scala|GpuSystemIndirectTranslationResetSpec.scala|ComputeTextureAvailabilitySpec.scala)
      printf '%s\n' system-core ;;
    RuntimeShaderFourLaneSpec.scala|RuntimeShaderEightLaneSpec.scala)
      printf '%s\n' system-rt-narrow ;;
    RuntimeShaderSixteenLaneSpec.scala)
      printf '%s\n' system-rt-16 ;;
    RuntimeShaderThirtyTwoLaneSpec.scala)
      printf '%s\n' system-rt-32 ;;
    RuntimeSharedGraphicsFullBatchSpec.scala)
      printf '%s\n' system-rt-batch ;;
    FullPoolRuntimeFullBatchSpec.scala)
      printf '%s\n' system-pool ;;
    FullPoolRuntimeScalingSpec.scala|RuntimeSharedGraphicsPerformanceSpec.scala|FullPoolTextureScalingSpec.scala|PersistentTileHostAxiSpec.scala|PersistentTileStoreFaultSpec.scala|PersistentTileTextureVisibilitySpec.scala|PersistentTileProductMsaaSpec.scala|DrmProgrammableMsaaAxiSpec.scala|DrmFragmentImageAxiSpec.scala|DrmComputeProgramReplaySpec.scala|GpuSystemPayloadSpec.scala|SharedPayloadClientSystemSpec.scala)
      printf '%s\n' SKIP ;;
    *)
      echo "system spec $(basename "$1") is not assigned to a CI shard" >&2
      return 1
      ;;
  esac
}

validate_system_assignment() {
  local file status=0
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    system_shard_for "$file" >/dev/null || status=1
  done < <(find "$test_root/opengpu/system" -name '*Spec.scala' -print)
  return "$status"
}

system_shard_files() {
  local shard=$1 file assigned
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    assigned=$(system_shard_for "$file")
    if [ "$assigned" = "$shard" ]; then
      printf '%s\n' "$file"
    fi
  done < <(find "$test_root/opengpu/system" -name '*Spec.scala' -print)
}

# Top-level specs directly under opengpu/core/ (not in a subpackage).
core_b_toplevel() {
  find "$test_root/opengpu/core" -maxdepth 1 -name '*Spec.scala' -print
}

shard_spec_files() {
  local shard=$1 prefix
  case "$shard" in
    graphics-[0-7])
      graphics_shard_files "$shard"
      return
      ;;
    system-tex-[0-7]|system-wide-[0-3])
      system_shard_files "${shard%-*}"
      return
      ;;
    system-*)
      system_shard_files "$shard"
      return
      ;;
  esac
  while IFS= read -r prefix; do
    [ -d "$test_root/$prefix" ] || continue
    find "$test_root/$prefix" -name '*Spec.scala' -print
  done < <(shard_prefixes "$shard")
  if [ "$shard" = "core-b" ]; then
    core_b_toplevel
  fi
}

shard_flags() {
  # Hierarchical compilation shares repeated CUs. The low-memory profiles
  # split generated C++ beside the 6 GiB JVM on a 16 GiB hosted runner.
  # Graphics use measured -O1 builds; the widest runtime profile retains -O0.
  case "$1" in
    graphics-[0-7])
      # Keep one compiler and the existing source/function splits. -O1 avoids
      # the unoptimized simulation cost while bounding measured compiler RSS.
      printf '%s\n' '-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1'
      ;;
    system-host|core-b)
      # Native/default-width graphics can elaborate several CUs. Bound both
      # compiler concurrency and optimized C++ size on the hosted 16 GB runner.
      # This composition adds default 128-thread host and actual-CU regressions.
      printf '%s\n' '-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1'
      ;;
    system-rt-32)
      printf '%s\n' '-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1'
      ;;
    system-rt-16|system-rt-batch|system-tex-[0-7]|system-wide-[0-3])
      printf '%s\n' '-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1'
      ;;
    system-walk|system-core|system-pool|system-rt-narrow)
      printf '%s\n' '-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=2'
      ;;
    system-mem|core-a)
      ;;
    *)
      echo "unknown shard: $1" >&2
      return 2
      ;;
  esac
}

fqcns_of() {
  # One source file can declare several concrete suites or use a different
  # filename. Abstract bases and private hardware harnesses are not suites.
  # Tests use public top-level class declarations with the Spec suffix.
  awk '
    FNR == 1 { package_name = "" }
    $1 == "package" { package_name = $2 }
    /^(final[[:space:]]+)?class[[:space:]]+/ {
      name = ($1 == "final" ? $3 : $2)
      sub(/[^[:alnum:]_].*$/, "", name)
      if (name ~ /Spec$/) print package_name "." name
    }
  ' "$@"
}

fqcns_for_shard() {
  local shard=$1 buckets
  shift
  case "$shard" in
    system-tex-[0-7]) buckets=8 ;;
    system-wide-[0-3]) buckets=4 ;;
    *) fqcns_of "$@"; return ;;
  esac
  # These files contain many inherited configurations, each compiling distinct
  # full-system models. Partition concrete suite names instead of putting all
  # configurations from one source file back on a single runner.
  fqcns_of "$@" | python3 -c '
import hashlib, sys
bucket, count = map(int, sys.argv[1:])
for name in sys.stdin:
    name = name.strip()
    if hashlib.sha256(name.encode("utf-8")).digest()[0] % count == bucket:
        print(name)
' "${shard##*-}" "$buckets"
}

matches_glob() {
  # $1 = fqcn, $2 = sbt-style glob (e.g. opengpu.core.*)
  local fqcn=$1 glob=$2 re
  re=$(printf '%s' "$glob" | sed 's/\./\\./g; s/\*/.*/g')
  [[ "$fqcn" =~ ^${re}$ ]]
}

suite_selected() {
  # $1 = fqcn, $2 = selected (ALL | NONE | globs)
  local fqcn=$1 selected=$2 glob
  case "$selected" in
    NONE) return 1 ;;
    ALL)  return 0 ;;
  esac
  for glob in $selected; do
    if matches_glob "$fqcn" "$glob"; then
      return 0
    fi
  done
  return 1
}

suites_for_shard() {
  local shard=$1 selected=$2 file fqcn
  local -a files=() out=()
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    files+=("$file")
  done < <(shard_spec_files "$shard" | sort -u)
  [ "${#files[@]}" -gt 0 ] || return 0
  while IFS= read -r fqcn; do
    if suite_selected "$fqcn" "$selected"; then
      out+=("$fqcn")
    fi
  done < <(fqcns_for_shard "$shard" "${files[@]}")
  if [ "${#out[@]}" -gt 0 ]; then
    printf '%s\n' "${out[*]}"
  fi
}

pr_suites() {
  local selected=$1 suite
  local -a catalog=() matched=()
  local declared
  declared=$(fqcns_of $(find "$test_root" -name '*Spec.scala' -print))
  while IFS= read -r suite; do
    case "$suite" in ''|\#*) continue ;; esac
    # grep -q may exit before printf drains a large catalog. With pipefail,
    # that producer's SIGPIPE otherwise turns a valid match into a failure.
    if ! grep -Fxq -- "$suite" <<< "$declared"; then
      echo "PR suite does not exist: $suite" >&2
      return 1
    fi
    catalog+=("$suite")
    if suite_selected "$suite" "$selected"; then
      matched+=("$suite")
    fi
  done < "$root/scripts/ci_pr_suites.txt"
  # A valid code selection outside the small catalog still runs bounded smoke
  # tests. It must not silently degrade to compilation alone.
  if [ "$selected" != NONE ] && [ "${#matched[@]}" -eq 0 ]; then
    matched=("${catalog[@]}")
  fi
  if [ "${#matched[@]}" -gt 0 ]; then
    printf '%s\n' "${matched[*]}"
  fi
}

shards_json() {
  local selected=$1 shard suites
  local -a active=()
  validate_system_assignment
  if [ "$selected" = "NONE" ]; then
    printf '[]\n'
    return
  fi
  for shard in "${all_shards[@]}"; do
    suites=$(suites_for_shard "$shard" "$selected")
    if [ -n "${suites:-}" ]; then
      active+=("\"$shard\"")
    fi
  done
  if [ "${#active[@]}" -eq 0 ]; then
    printf '[]\n'
  else
    local IFS=,
    printf '[%s]\n' "${active[*]}"
  fi
}

cmd=${1:-}
case "$cmd" in
  pr-suites)
    [ $# -ge 2 ] || { echo "usage: $0 pr-suites <selected>" >&2; exit 2; }
    shift
    pr_suites "$*"
    ;;
  shards-json)
    [ $# -ge 2 ] || { echo "usage: $0 shards-json <selected>" >&2; exit 2; }
    shift
    shards_json "$*"
    ;;
  suites)
    [ $# -ge 3 ] || { echo "usage: $0 suites <shard> <selected>" >&2; exit 2; }
    validate_system_assignment
    shard=$2
    shift 2
    suites_for_shard "$shard" "$*"
    ;;
  flags)
    [ $# -eq 2 ] || { echo "usage: $0 flags <shard>" >&2; exit 2; }
    shard_flags "$2"
    ;;
  *)
    echo "usage: $0 {pr-suites|shards-json|suites|flags} ..." >&2
    exit 2
    ;;
esac
