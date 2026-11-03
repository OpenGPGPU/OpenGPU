#!/usr/bin/env bash
# Build an isolated, small storage-and-texture-enabled ARTI/QEMU model. Never modify QEMU inputs.
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo "usage: $0 /path/to/qemu-build /path/to/qemu-source /path/to/arti-generated/embedded" >&2
  exit 2
fi
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
qemu_build="$(cd "$1" && pwd)"
qemu_source="$(cd "$2" && pwd)"
wrapper="$(cd "$3" && pwd)"
for file in "$qemu_build/build.ninja" "$qemu_source/hw/misc/libarti_rtl_model.a" \
            "$wrapper/arti_rtl_model.cpp" "$wrapper/arti_rtl_model.h"; do
  [ -f "$file" ] || { echo "missing prerequisite: $file" >&2; exit 1; }
done
for cmd in sbt verilator make clang++ ninja ar python3; do
  command -v "$cmd" >/dev/null || { echo "missing prerequisite: $cmd" >&2; exit 1; }
done
model_feature="${ARTI_STORAGE_ATOMICS:-0}"
if [[ "$model_feature" != 0 && "$model_feature" != 1 ]]; then
  echo 'ARTI_STORAGE_ATOMICS must be 0 or 1' >&2; exit 2
fi
model_arg=""
if [[ "$model_feature" = 1 ]]; then model_arg=" storage-atomics"; fi
mkdir -p "$repo_dir/.artifacts"
run_dir="$(mktemp -d "$repo_dir/.artifacts/compute-resources-model.XXXXXX")"
printf 'Artifacts: %s\n' "$run_dir"
(cd "$repo_dir" && sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 \
  'set Compile / unmanagedSources += baseDirectory.value / "scripts" / "EmitComputeResourcesArti.scala"' \
  "runMain opengpu.elaboration.EmitComputeResourcesArti $run_dir/rtl$model_arg")
(cd "$run_dir/rtl" && verilator --cc --Mdir "$run_dir/model" --no-timing --threads 1 \
  --output-split 20000 --output-split-cfuncs 500 -Wno-fatal \
  --top-module GpuHostSystemAxi -f filelist.f)
make -C "$run_dir/model" -f VGpuHostSystemAxi.mk -j 2 CXXFLAGS=-std=gnu++17 OPT_FAST=-O1 OPT_SLOW=-O0
verilator_root="$(verilator -V | sed -n 's/^[[:space:]]*VERILATOR_ROOT[[:space:]]*=[[:space:]]*//p' | head -1)"
clang++ -std=gnu++17 -fPIC -O1 -Wno-undefined-bool-conversion \
  -I "$run_dir/model" -I "$verilator_root/include" -I "$wrapper" \
  -c "$wrapper/arti_rtl_model.cpp" -o "$run_dir/model/arti_rtl_model.o"
(cd "$run_dir/model" && ar rcs libarti_rtl_model.a VGpuHostSystemAxi*.o \
  verilated.o verilated_threads.o arti_rtl_model.o)
python3 - "$qemu_build" "$qemu_source" "$run_dir" <<'PY'
from pathlib import Path
import shlex
import subprocess
import sys
build, source, out = map(Path, sys.argv[1:])
lines = subprocess.check_output(['ninja', '-t', 'commands', 'qemu-system-aarch64'], cwd=build, text=True).splitlines()
links = []
for line in lines:
    args = shlex.split(line)
    if '-o' in args and args.index('-o') + 1 < len(args):
        target = args[args.index('-o') + 1]
        if target in ('qemu-system-aarch64-unsigned', 'qemu-system-aarch64'):
            links.append((args, target))
if len(links) != 1:
    raise SystemExit('expected one QEMU link command')
args, target = links[0]
old = str(source / 'hw/misc/libarti_rtl_model.a')
if args.count(old) != 1 or args.count(target) != 1:
    raise SystemExit('QEMU link command does not match supplied source')
args[args.index(old)] = str(out / 'model/libarti_rtl_model.a')
args[args.index(target)] = str(out / 'qemu-system-aarch64')
subprocess.run(args, cwd=build, check=True)
if sys.platform == 'darwin':
    subprocess.run(['codesign', '--force', '-s', '-', str(out / 'qemu-system-aarch64')], check=True)
PY
printf 'Isolated QEMU: %s/qemu-system-aarch64\n' "$run_dir"
