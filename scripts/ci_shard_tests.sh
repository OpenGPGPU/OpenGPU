#!/usr/bin/env bash
# Partition ScalaTest suites across CI runners and intersect with select_tests.sh.
#
# Usage:
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
#   graphics     — opengpu/graphics/**
#   system-mem   — AXI masters and the register-only host
#   system-host  — GpuHostSystemAxi plus the fill-throughput model
#   system-walk  — 4-CU desktop page-walk and address repros (one shared model)
#   system-tex   — programmable texture and parallel ordering
#   system-wide  — WideSharedGraphicsSpec (1/2/4 CU)
#   system-core  — GpuSystemSpec
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
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
test_root="$root/src/test/scala"

shard_prefixes() {
  case "$1" in
    graphics) printf '%s\n' "opengpu/graphics" ;;
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
        "opengpu/util"
      ;;
    *)
      echo "unknown shard: $1" >&2
      exit 2
      ;;
  esac
}

all_shards=(graphics system-mem system-host system-walk system-tex system-wide system-core system-rt-narrow system-rt-16 system-rt-32 system-rt-batch system-pool core-a core-b)

# Map one opengpu/system spec file to a shard, or SKIP.
# A new file must be added here; otherwise CI selection fails closed.
system_shard_for() {
  case "$(basename "$1")" in
    AxiStimulusOrderingSpec.scala|ComputeMemoryAxiMasterSpec.scala|GpuHostAxiSpec.scala|GpuWorkloadSpec.scala)
      printf '%s\n' system-mem ;;
    GpuHostSystemAxiSpec.scala|GpuFillThroughputSpec.scala)
      printf '%s\n' system-host ;;
    RuntimeVectorAddressSpec.scala|SharedCuTwoLevelWalkSpec.scala)
      printf '%s\n' system-walk ;;
    ProgrammableTextureAxiSpec.scala|ParallelGraphicsOrderingSpec.scala)
      printf '%s\n' system-tex ;;
    WideSharedGraphicsSpec.scala)
      printf '%s\n' system-wide ;;
    GpuSystemSpec.scala)
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
    FullPoolRuntimeScalingSpec.scala|RuntimeSharedGraphicsPerformanceSpec.scala|FullPoolTextureScalingSpec.scala)
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
  # Hierarchical compile helps designs that instantiate GpuComputeUnit.
  # ubuntu-latest has 16 GB and the JVM already reserves 6 GB, so the C++
  # build stays at two jobs. The wide runtime shards each elaborate one
  # full-GPU model (16-lane also builds the four-CU variant). Two compilers
  # on those models SIGTERM the runner agent, which GitHub reports as
  # "The operation was canceled." The 32-lane CU is wider than that: one
  # g++ of its optimized eval function, beside a 6 GB heap, still kills the
  # agent after the swap file is full. simLowMem splits that C++ and
  # compiles it at -O0. The memory shard has no compute unit, so it stays
  # flat.
  case "$1" in
    system-rt-32)
      printf '%s\n' '-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1'
      ;;
    system-rt-16|system-rt-batch)
      printf '%s\n' '-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1'
      ;;
    system-host|system-walk|system-tex|system-wide|system-core|system-pool|system-rt-narrow)
      printf '%s\n' '-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=2'
      ;;
    graphics|system-mem|core-a|core-b)
      ;;
    *)
      echo "unknown shard: $1" >&2
      return 2
      ;;
  esac
}

fqcn_of() {
  # $1: absolute path to *Spec.scala
  local rel=${1#"$test_root"/}
  rel=${rel%.scala}
  printf '%s\n' "${rel//\//.}"
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
  local -a out=()
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    fqcn=$(fqcn_of "$file")
    if suite_selected "$fqcn" "$selected"; then
      out+=("$fqcn")
    fi
  done < <(shard_spec_files "$shard" | sort -u)
  if [ "${#out[@]}" -gt 0 ]; then
    printf '%s\n' "${out[*]}"
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
    echo "usage: $0 {shards-json|suites|flags} ..." >&2
    exit 2
    ;;
esac
